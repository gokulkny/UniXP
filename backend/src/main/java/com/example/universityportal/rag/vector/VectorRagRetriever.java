package com.example.universityportal.rag.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("vector")
public class VectorRagRetriever {
  private static final Logger log = LoggerFactory.getLogger(VectorRagRetriever.class);

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

    try {
      float[] qvec = embeddingClient.embed(query.trim());
      int topK = Math.max(1, properties.getTopK());
      int pool = mode == RagRetrievalProperties.RetrievalMode.hybrid
          ? Math.min(50, topK * Math.max(1, properties.getHybridCandidateMultiplier()))
          : topK;

      List<VectorSearchResult> raw = vectorStore.search(qvec, pool);
      if (raw.isEmpty()) {
        log.debug("Vector search returned no rows");
        return Optional.empty();
      }

      List<VectorSearchResult> ranked = mode == RagRetrievalProperties.RetrievalMode.hybrid
          ? KeywordOverlapReranker.rerank(query, raw)
          : raw;

      int take = Math.min(ranked.size(), topK);
      List<VectorSearchResult> top = ranked.subList(0, take);
      String ctx = assembleContext(top);
      if (ctx.isBlank()) return Optional.empty();
      return Optional.of(ctx);
    } catch (Exception e) {
      log.warn("Vector retrieval failed: {}", e.getMessage());
      return Optional.empty();
    }
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
}
