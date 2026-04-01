package com.example.universityportal.rag.vector;

/**
 * Selects the pgvector table matching the configured embedding dimension.
 */
public final class VectorTableNames {
  public static final String TABLE_1536 = "rag_chunk";
  public static final String TABLE_4096 = "rag_chunk_4096";

  private VectorTableNames() {}

  public static String chunksTable(int embeddingDimension) {
    return embeddingDimension == 4096 ? TABLE_4096 : TABLE_1536;
  }
}
