package com.example.universityportal.rag.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("vector")
public class VectorRagRetriever {
  private static final Logger log = LoggerFactory.getLogger(VectorRagRetriever.class);
  private static final Pattern ID_PATTERN = Pattern.compile("\\b\\d{1,4}(?:[./-]\\d{1,2})?\\b");

  private final EmbeddingClient embeddingClient;
  private final VectorStore vectorStore;
  private final RagRetrievalProperties properties;

  public VectorRagRetriever(
      EmbeddingClient embeddingClient,
      VectorStore vectorStore,
      RagRetrievalProperties properties
  ) {
    this.embeddingClient = embeddingClient;
    this.vectorStore = vectorStore;
    this.properties = properties;
  }

  public Optional<String> retrieve(String query) {
    RagRetrievalProperties.RetrievalMode mode = properties.getRetrievalMode();
    if (mode != RagRetrievalProperties.RetrievalMode.vector && mode != RagRetrievalProperties.RetrievalMode.hybrid) {
      return Optional.empty();
    }

    if (query == null || query.isBlank()) return Optional.empty();

    QueryLanguage queryLanguage = QueryLanguageDetector.detect(query);
    List<String> lexicalTokens = KeywordOverlapReranker.tokenize(query);
    List<String> requiredTokens = identifierTokens(query);

    try {
      float[] qvec = embeddingClient.embed(query.trim());
      int topK = Math.max(1, properties.getTopK());
      // In pure vector mode, small topK can miss relevant chunks (especially cross-language queries).
      // Pull a larger candidate pool, then optionally rerank and truncate back to topK.
      int poolMultiplier = Math.max(3, properties.getHybridCandidateMultiplier());
      int pool = Math.min(50, topK * poolMultiplier);

      List<VectorSearchResult> required = vectorStore.searchByRequiredTokens(requiredTokens, Math.min(20, pool));
      List<VectorSearchResult> raw = vectorStore.search(qvec, pool);
      List<VectorSearchResult> lexical = vectorStore.searchByLexicalTokens(lexicalTokens, Math.min(25, pool));
      List<VectorSearchResult> leading = List.of();
      if (isIdentifierHeavyQuery(lexicalTokens)) {
        List<String> sourceIds = topSourceIds(!required.isEmpty() ? required : lexical, 1);
        leading = vectorStore.fetchLeadingChunksBySource(sourceIds, 4);
      }
      List<VectorSearchResult> merged = mergeById(required, raw, lexical, leading);
      if (merged.isEmpty()) {
        log.debug("Vector search returned no rows (vector and lexical)");
        return Optional.empty();
      }

      // Lightweight lexical rerank improves precision for identifiers like "Notice 07 – 2026.1".
      List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(query, merged, queryLanguage);

      int take = Math.min(ranked.size(), topK);
      List<VectorSearchResult> top = ranked.subList(0, take);
      String ctx = assembleContext(top);
      if (ctx.isBlank()) return Optional.empty();
      return Optional.of(ctx);
    } catch (Exception e) {
      // Embeddings can timeout under provider/network pressure. Fall back to lexical retrieval
      // so strict-from-docs mode can still answer from available chunks.
      log.warn("Vector retrieval failed, using lexical fallback: {}", e.getMessage());
      return retrieveLexicalFallback(query, queryLanguage, lexicalTokens, requiredTokens);
    }
  }

  private Optional<String> retrieveLexicalFallback(
      String query,
      QueryLanguage queryLanguage,
      List<String> lexicalTokens,
      List<String> requiredTokens
  ) {
    int topK = Math.max(1, properties.getTopK());
    int poolMultiplier = Math.max(3, properties.getHybridCandidateMultiplier());
    int pool = Math.min(50, topK * poolMultiplier);

    List<VectorSearchResult> required = vectorStore.searchByRequiredTokens(requiredTokens, Math.min(20, pool));
    List<VectorSearchResult> lexical = vectorStore.searchByLexicalTokens(lexicalTokens, Math.min(25, pool));
    List<VectorSearchResult> leading = List.of();
    if (isIdentifierHeavyQuery(lexicalTokens)) {
      List<String> sourceIds = topSourceIds(!required.isEmpty() ? required : lexical, 1);
      leading = vectorStore.fetchLeadingChunksBySource(sourceIds, 4);
    }
    List<VectorSearchResult> merged = mergeById(required, lexical, leading, List.of());
    if (merged.isEmpty()) return Optional.empty();

    List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(query, merged, queryLanguage);
    int take = Math.min(ranked.size(), topK);
    String ctx = assembleContext(ranked.subList(0, take));
    if (ctx.isBlank()) return Optional.empty();
    return Optional.of(ctx);
  }

  private String assembleContext(List<VectorSearchResult> ranked) {
    int maxChars = properties.getMaxContextChars();
    int maxPerDoc = Math.max(1, properties.getMaxChunksPerDoc());
    Map<String, Integer> perDoc = new HashMap<>();
    StringBuilder context = new StringBuilder();
    int totalChars = 0;

    for (VectorSearchResult s : ranked) {
      if (context.length() >= maxChars) break;
      int used = perDoc.getOrDefault(s.sourceFileId(), 0);
      if (used >= maxPerDoc) continue;

      String t = s.content();
      if (t == null || t.isBlank()) continue;
      int remaining = maxChars - totalChars;
      String trimmed = t.length() > remaining ? t.substring(0, Math.max(0, remaining)) : t;
      if (trimmed.isBlank()) continue;

      if (context.length() > 0) context.append("\n\n---\n\n");
      context.append(trimmed.trim());
      totalChars = context.length();
      perDoc.put(s.sourceFileId(), used + 1);
    }

    if (context.length() == 0) return "";
    return context.toString();
  }

  private List<VectorSearchResult> mergeById(
      List<VectorSearchResult> first,
      List<VectorSearchResult> second,
      List<VectorSearchResult> third,
      List<VectorSearchResult> fourth
  ) {
    LinkedHashMap<String, VectorSearchResult> map = new LinkedHashMap<>();
    addAllUnique(map, first);
    addAllUnique(map, second);
    addAllUnique(map, third);
    addAllUnique(map, fourth);
    return new ArrayList<>(map.values());
  }

  private void addAllUnique(LinkedHashMap<String, VectorSearchResult> map, List<VectorSearchResult> list) {
    if (list == null) return;
    for (VectorSearchResult r : list) {
      if (r == null || r.id() == null) continue;
      map.putIfAbsent(r.id().toString(), r);
    }
  }

  private boolean isIdentifierHeavyQuery(List<String> tokens) {
    if (tokens == null || tokens.isEmpty()) return false;
    int digitTokens = 0;
    boolean hasNoticeWord = false;
    for (String t : tokens) {
      if (t == null) continue;
      if (t.chars().allMatch(Character::isDigit)) digitTokens++;
      if ("edital".equals(t) || "notice".equals(t) || "edict".equals(t)) hasNoticeWord = true;
    }
    return hasNoticeWord || digitTokens >= 2;
  }

  private List<String> topSourceIds(List<VectorSearchResult> ranked, int max) {
    LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
    if (ranked != null) {
      for (VectorSearchResult r : ranked) {
        if (r == null || r.sourceFileId() == null || r.sourceFileId().isBlank()) continue;
        ids.putIfAbsent(r.sourceFileId(), Boolean.TRUE);
        if (ids.size() >= max) break;
      }
    }
    return new ArrayList<>(ids.keySet());
  }

  private List<String> identifierTokens(String query) {
    if (query == null || query.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    Matcher m = ID_PATTERN.matcher(query.toLowerCase());
    while (m.find()) {
      String token = m.group();
      if (token == null || token.isBlank()) continue;
      out.add(token);
      // Also add split parts for formats like 2026.1 so SQL can match either representation.
      String[] parts = token.split("[./-]");
      for (String p : parts) {
        if (p != null && p.length() >= 2) out.add(p);
      }
      if (out.size() >= 6) break;
    }
    return dedupe(out);
  }

  private List<String> dedupe(List<String> items) {
    LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    for (String s : items) {
      if (s == null) continue;
      String t = s.trim();
      if (t.length() < 2) continue;
      seen.putIfAbsent(t, Boolean.TRUE);
    }
    return new ArrayList<>(seen.keySet());
  }
}
