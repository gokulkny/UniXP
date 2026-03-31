package com.example.universityportal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OpenRouterChatClient {
  private static final Logger log = LoggerFactory.getLogger(OpenRouterChatClient.class);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public String generateReply(
      String apiKey,
      String model,
      String systemPrompt,
      List<ChatTurn> history,
      String userMessage,
      String requestId
  ) throws IOException, InterruptedException {

    if (apiKey == null || apiKey.isBlank()) {
      return "AI is not configured. Please contact the admin.";
    }

    // Build OpenRouter-compatible chat payload (OpenAI /v1 chat-completions compatible).
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));

    for (ChatTurn turn : history) {
      if (turn == null || turn.content() == null) continue;
      String role = turn.role();
      if (role == null) continue;
      String normalizedRole = role.toLowerCase(Locale.ROOT);
      if (!normalizedRole.equals("user") && !normalizedRole.equals("assistant")) continue;
      messages.add(Map.of("role", normalizedRole, "content", turn.content()));
    }

    messages.add(Map.of("role", "user", "content", userMessage));

    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("temperature", 0.2);
    payload.put("max_tokens", 400);

    String referer = getenv("PORTAL_OPENROUTER_HTTP_REFERER", null);
    String title = getenv("PORTAL_OPENROUTER_TITLE", null);
    int requestTimeoutSeconds = getenvInt("PORTAL_OPENROUTER_TIMEOUT_SECONDS", 35);
    if (requestTimeoutSeconds < 1) requestTimeoutSeconds = 35;

    String payloadJson = objectMapper.writeValueAsString(payload);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
        .timeout(Duration.ofSeconds(requestTimeoutSeconds))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8));

    // Optional OpenRouter headers (helps with abuse tracking).
    if (referer != null && !referer.isBlank()) builder.header("HTTP-Referer", referer);
    if (title != null && !title.isBlank()) builder.header("X-Title", title);

    HttpRequest request = builder.build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      log.warn("OpenRouter chat timed out (requestId={}, model={}, timeoutSeconds={})",
          requestId, model, requestTimeoutSeconds);
      throw e;
    }

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("OpenRouter chat request failed (requestId={}, status={})", requestId, response.statusCode());
      return "Sorry, I couldn't generate a response right now.";
    }

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode choices = root.get("choices");
    if (choices == null || !choices.isArray() || choices.size() == 0) {
      return "Sorry, I couldn't generate a response right now.";
    }

    JsonNode message = choices.get(0).get("message");
    JsonNode content = message == null ? null : message.get("content");
    if (content == null || content.isNull()) {
      return "Sorry, I couldn't generate a response right now.";
    }

    return content.asText();
  }

  private String getenv(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  private int getenvInt(String key, int defaultValue) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}

