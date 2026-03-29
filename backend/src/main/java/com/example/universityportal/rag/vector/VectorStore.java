package com.example.universityportal.rag.vector;

import java.util.List;
import java.util.UUID;

public interface VectorStore {
  void deleteBySourceFileId(String sourceFileId);

  void upsert(List<VectorChunkRecord> chunks);

  List<VectorSearchResult> search(float[] queryEmbedding, int topK);
}
