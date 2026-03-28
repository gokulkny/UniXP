package com.example.universityportal.ai;

import com.example.universityportal.dto.ChatResponse;
import com.example.universityportal.rag.GoogleDrivePdfRagService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final Set<String> CASUAL_KEYWORDS = new HashSet<>(List.of(
      "hi", "hello", "hey", "yo", "hola", "thanks", "thank you", "good morning",
      "good afternoon", "good evening", "how are you", "what's up", "whats up"
  ));

  private final OpenRouterChatClient openRouterChatClient;
  private final GoogleDrivePdfRagService ragService;

  // Demo-only in-memory conversation history.
  private final Map<String, Deque<ChatTurn>> historyByUser = new ConcurrentHashMap<>();

  public ChatService(OpenRouterChatClient openRouterChatClient, GoogleDrivePdfRagService ragService) {
    this.openRouterChatClient = openRouterChatClient;
    this.ragService = ragService;
  }

  public ChatResponse chat(String username, String userMessage, boolean strictFromDocs, String requestId) {
    try {
      int memoryTurns = getEnvInt("PORTAL_CHAT_MEMORY_TURNS", 10);
      if (memoryTurns < 1) memoryTurns = 10;

      String openRouterApiKey = getEnv("PORTAL_OPENROUTER_API_KEY", null);
      String openRouterModel = getEnv("PORTAL_OPENROUTER_MODEL", "openai/gpt-4o-mini");
      IntentType intent = classifyIntent(userMessage);
      boolean ragStrict = getEnvBool("PORTAL_RAG_STRICT", false);
      boolean effectiveStrictMode = ragStrict || strictFromDocs;
      String systemPrompt = getEnv(
          "PORTAL_AI_SYSTEM_PROMPT",
          """
          You are a Retrieval-Augmented Generation (RAG) assistant.

          Your task is to answer questions strictly based on the provided context.

          Core Rules
          1. Grounding
          - Only use information explicitly present in the retrieved context.
          - You may combine and synthesize information from multiple parts of the context.
          - Do not use prior knowledge or assumptions.
          - If the answer is not found in the context, respond with:
            "This information is not specified in the provided material."

          1b. Condition preservation
          - If the context states rules with multiple conditions/exceptions, preserve all conditions and exceptions.
          - Do not simplify multi-condition rules into a single general statement.
          - If needed, list the conditions as short bullet points.

          2. Answer Style
          - Be concise and direct.
          - Do not add unnecessary explanations.
          - Do not include advice or instructional phrases like:
            "You should", "Make sure to", "This helps ensure"

          3. Answer Length Control
          - Definitions → 1–2 lines
          - Conceptual questions → short explanation (optionally 1 example)
          - Formatting/output tasks → return only the final answer
          - Complex reasoning → structured but concise

          4. No Hallucination
          - Do not infer missing details.
          - Do not generalize beyond the context.
          - Do not fabricate rules or facts.

          5. Output Cleanliness
          - Avoid unnecessary headings.
          - Avoid excessive formatting.
          - No repetition or filler text

          6. Confidence Handling
          - If clearly supported → answer confidently
          - If partially supported → say "Based on the provided information..."
          - If unsupported → say it is not specified

          Correction tasks
          - If the user asks to correct/format something (e.g., citation), fix all issues you can find from the Context, not just the first one.
          - Output only the fully corrected result unless the user asks for explanations.

          Few-shot examples
          Q: What does “pp.” mean?
          A: “pp.” refers to multiple pages in a citation.

          Q: What font is required?
          A: This information is not specified in the provided material.

          Q: Format citation for Smith, 2020
          A: (Smith, 2020)
          """
      );
      String casualSystemPrompt = getEnv(
          "PORTAL_AI_CASUAL_SYSTEM_PROMPT",
          "You are a friendly university assistant. Respond naturally, briefly, and conversationally."
      );
      String ragNoContextMessage = getEnv(
          "PORTAL_RAG_NO_CONTEXT_MESSAGE",
          "This information is not specified in the provided material."
      );

      // Strict mode must always use document-grounded flow, regardless of intent.
      boolean useCasualFlow = !effectiveStrictMode && intent == IntentType.CASUAL;
      if (useCasualFlow && isSimpleGreeting(userMessage)) {
        String greetingReply = "Hi! I can help with questions from your uploaded documents.";
        log.info("AI reply (requestId={}, username={}): {}", requestId, username, greetingReply);
        return ChatResponse.ok(greetingReply);
      }
      String systemPromptWithRag = useCasualFlow ? casualSystemPrompt : systemPrompt;
      boolean hasRagContext = false;
      String retrievedContext = null;
      if (!useCasualFlow && ragService != null) {
        try {
          var maybeContext = ragService.retrieveContext(userMessage);
          if (maybeContext.isPresent()) {
            hasRagContext = true;
            String context = maybeContext.get();
            retrievedContext = context;
            systemPromptWithRag = systemPrompt
                + "\n\nContext:\n"
                + context
                + "\n\nInstructions: Answer using only the Context above. "
                + "You should combine and synthesize information from multiple lines or sections in the Context when needed. "
                + "Only if the answer truly cannot be derived from the Context, reply exactly: "
                + "\"This information is not specified in the provided material.\"";
          }
        } catch (Exception e) {
          // Never break chat due to retrieval issues.
          log.warn("RAG retrieval failed (requestId={}, username={}): {}", requestId, username, e.getMessage());
        }
      }

      if (effectiveStrictMode && !hasRagContext) {
        log.info("RAG strict mode refusal (requestId={}, username={}): no relevant document context", requestId, username);
        return ChatResponse.ok(ragNoContextMessage);
      }

      int policyLevel = getEnvInt("PORTAL_POLICY_LEVEL", 1);
      String blockMessage = getEnv("PORTAL_CHAT_BLOCK_MESSAGE", "Sorry, I can’t help with that request.");
      boolean logFull = getEnvBool("PORTAL_CHAT_LOG_FULL", true);
      int logMaxChars = getEnvInt("PORTAL_CHAT_LOG_MAX_CHARS", 4000);

      // Lightweight server-side safety (no external moderation calls).
      // For the demo, we combine policy strictness + optional keyword matching.
      if (isBlocked(userMessage, policyLevel)) {
        log.warn("AI blocked by rule filter (requestId={}, username={}, policyLevel={})", requestId, username, policyLevel);
        return ChatResponse.ok(blockMessage);
      }

      // Store user's message immediately (demo behavior).
      Deque<ChatTurn> deque = historyByUser.computeIfAbsent(username, k -> new ConcurrentLinkedDeque<>());
      deque.addLast(new ChatTurn("user", userMessage));

      // Trim approximately to the last N user/assistant turns.
      trimHistory(deque, memoryTurns * 2);

      // Build history excluding the "current" latest user message, since the client adds it separately.
      List<ChatTurn> history = new ArrayList<>(deque);
      String latestUserMessage = userMessage;

      // history currently includes the latest user message at the end; remove it before sending,
      // so we don't duplicate it in the chat completion payload.
      if (!history.isEmpty() && "user".equalsIgnoreCase(history.get(history.size() - 1).role())) {
        history.remove(history.size() - 1);
      }

      if (logFull) {
        log.info("AI input (requestId={}, username={}): {}", requestId, username, userMessage);
      } else {
        log.info("AI input (requestId={}, username={}): {}",
            requestId, username, truncate(userMessage, logMaxChars));
      }

      String reply;
      try {
        reply = openRouterChatClient.generateReply(
            openRouterApiKey,
            openRouterModel,
            systemPromptWithRag,
            history,
            latestUserMessage,
            requestId
        );
      } catch (IOException io) {
        // Resilience path when provider is timing out: return extractive answer from retrieved context.
        if (!useCasualFlow && hasRagContext && retrievedContext != null && !retrievedContext.isBlank()) {
          reply = "Based on the provided information, " + bestMatchingSentence(retrievedContext, latestUserMessage);
        } else {
          throw io;
        }
      }
      reply = postProcessAnswer(reply);

      deque.addLast(new ChatTurn("assistant", reply));
      trimHistory(deque, memoryTurns * 2);

      if (logFull) {
        log.info("AI reply (requestId={}, username={}): {}", requestId, username, reply);
      } else {
        log.info("AI reply (requestId={}, username={}): {}",
            requestId, username, truncate(reply, logMaxChars));
      }

      return ChatResponse.ok(reply);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("AI request interrupted (requestId={}, username={}): {}", requestId, username, e.getMessage());
      return ChatResponse.fail("AI request was interrupted. Please try again later.");
    } catch (IOException e) {
      // Don't interrupt the servlet thread on network timeouts/errors.
      log.warn("AI request failed (requestId={}, username={}): {}", requestId, username, e.getMessage());
      return ChatResponse.fail("AI request failed. Please try again later.");
    } catch (Exception e) {
      log.warn("AI request failed (requestId={}, username={}): {}", requestId, username, e.getMessage());
      return ChatResponse.fail("AI request failed. Please try again later.");
    }
  }

  private String postProcessAnswer(String answer) {
    if (answer == null) return null;
    String a = answer.trim();
    if (a.isEmpty()) return a;

    // Trim overlong outputs (keep backend response predictable).
    int maxChars = getEnvInt("PORTAL_CHAT_MAX_ANSWER_CHARS", 1200);
    if (maxChars > 0 && a.length() > maxChars) {
      a = a.substring(0, maxChars).trim();
    }

    // Remove common "advice" lead-ins (best-effort; prompt should prevent these).
    String[] advicePrefixes = {
        "You should ",
        "You should\n",
        "Make sure to ",
        "Make sure to\n",
        "This helps ensure ",
        "This helps ensure\n"
    };
    for (String p : advicePrefixes) {
      if (a.startsWith(p)) {
        a = a.substring(p.length()).trim();
        break;
      }
    }

    return a;
  }

  private void trimHistory(Deque<ChatTurn> deque, int maxItems) {
    while (deque.size() > maxItems) {
      deque.pollFirst();
    }
  }

  private String getEnv(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  private int getEnvInt(String key, int defaultValue) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private boolean getEnvBool(String key, boolean defaultValue) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return defaultValue;
    return Boolean.parseBoolean(v.trim());
  }

  private String truncate(String s, int maxChars) {
    if (s == null) return null;
    if (maxChars <= 0) return "";
    if (s.length() <= maxChars) return s;
    return s.substring(0, Math.max(0, maxChars)) + "…(truncated)";
  }

  private boolean isBlocked(String userMessage, int policyLevel) {
    if (userMessage == null || userMessage.isBlank()) return false;

    String defaultBlockRegex =
        "(?is)\\b(" +
            // credentials / sensitive
            "password|api[_-]?key|secret|ssn|credit[_ -]?card|cvv|bank[_ -]?account|" +
            // wrongdoing patterns
            "hack|phish|steal|fraud|scam|forgery|" +
            // violence / self-harm keywords
            "kill|murder|bomb|weapon|suicide|self-harm" +
        ")\\b";

    String custom = System.getenv("PORTAL_BLOCK_REGEX");
    String regex = (custom == null || custom.isBlank()) ? defaultBlockRegex : custom;

    // Policy interpretation:
    // 0: only block high-risk violence/self-harm/weapon
    // 1: block broader harmful categories (default regex)
    // 2: same as 1 in this simplified demo (but kept for configurability)
    String policyRegex;
    if (policyLevel <= 0) {
      policyRegex = "(?is)\\b(kill|murder|bomb|weapon|suicide|self-harm)\\b";
    } else {
      policyRegex = regex;
    }

    return userMessage.matches(policyRegex);
  }

  private String bestMatchingSentence(String context, String query) {
    if (context == null || context.isBlank()) {
      return "This information is not specified in the provided material.";
    }
    String[] sentences = context.split("(?<=[.!?])\\s+");
    if (sentences.length == 0) {
      return context.substring(0, Math.min(300, context.length())).trim();
    }
    List<String> terms = new ArrayList<>();
    if (query != null) {
      for (String part : query.toLowerCase().split("[^a-z0-9]+")) {
        String t = part == null ? "" : part.trim();
        if (t.length() >= 2) terms.add(t);
      }
    }
    String best = "";
    int bestScore = -1;
    for (String s : sentences) {
      String candidate = s == null ? "" : s.trim();
      if (candidate.length() < 20) continue;
      String lc = candidate.toLowerCase();
      int score = 0;
      for (String t : terms) {
        if (lc.contains(t)) score++;
      }
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    if (best.isBlank()) {
      return context.substring(0, Math.min(300, context.length())).trim();
    }
    return best;
  }

  private boolean isSimpleGreeting(String userMessage) {
    if (userMessage == null) return false;
    String m = userMessage.trim().toLowerCase();
    return m.length() <= 25 && CASUAL_KEYWORDS.contains(m);
  }

  private IntentType classifyIntent(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) return IntentType.CASUAL;
    String m = userMessage.toLowerCase();
    String trimmed = m.trim();
    if (trimmed.length() <= 20) {
      for (String kw : CASUAL_KEYWORDS) {
        if (trimmed.equals(kw) || trimmed.startsWith(kw + " ") || trimmed.contains(" " + kw + " ")) {
          return IntentType.CASUAL;
        }
      }
    }

    String[] ragSignals = {
        "citation", "cite", "apa", "mla", "rule", "format", "reference", "bibliography",
        "et al", "document", "pdf", "guideline", "style", "source"
    };
    for (String s : ragSignals) {
      if (m.contains(s)) return IntentType.RAG_QUERY;
    }

    // Default to casual to avoid "not specified" for normal conversation.
    return IntentType.CASUAL;
  }

  private enum IntentType {
    CASUAL,
    RAG_QUERY
  }
}

