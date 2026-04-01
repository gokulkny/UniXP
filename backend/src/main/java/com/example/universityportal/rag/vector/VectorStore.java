package com.example.universityportal.rag.vector;

import java.util.List;
import java.util.UUID;

public interface VectorStore {
  void deleteBySourceFileId(String sourceFileId);

  void upsert(List<VectorChunkRecord> chunks);

  List<VectorSearchResult> search(float[] queryEmbedding, int topK);

  /**
   * Optional lexical fallback for identifier-heavy queries (e.g., "07/2026.1").
   * Implementations may return empty when unsupported.
   */
  default List<VectorSearchResult> searchByLexicalTokens(List<String> tokens, int limit) {
    return List.of();
  }

  /**
   * Cross-language substring fallback when FTS returns no rows (e.g. English query vs Portuguese-stemmed tsvector).
   */
  default List<VectorSearchResult> searchByLexicalTokensSimple(List<String> tokens, int limit) {
    return List.of();
  }

  /**
   * Fetch early chunks (lowest chunk_index) for selected source files.
   */
  default List<VectorSearchResult> fetchLeadingChunksBySource(List<String> sourceFileIds, int perDocLimit) {
    return List.of();
  }

  /**
   * Exact lexical rescue for identifier-heavy queries.
   * Implementations may return empty when unsupported.
   */
  default List<VectorSearchResult> searchByRequiredTokens(List<String> requiredTokens, int limit) {
    return List.of();
  }
}
