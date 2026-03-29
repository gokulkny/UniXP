package com.example.universityportal.rag.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portal.rag")
public class RagRetrievalProperties {
  public enum RetrievalMode {
    legacy,
    vector,
    hybrid
  }

  private RetrievalMode retrievalMode = RetrievalMode.legacy;
  private int topK = 5;
  private int maxContextChars = 4500;
  private int maxChunksPerDoc = 4;
  /** Aligned with legacy Drive loader default (120); higher values drop more chunks. */
  private int minChunkChars = 120;
  private int hybridCandidateMultiplier = 2;

  public RetrievalMode getRetrievalMode() {
    return retrievalMode;
  }

  public void setRetrievalMode(RetrievalMode retrievalMode) {
    this.retrievalMode = retrievalMode;
  }

  public int getTopK() {
    return topK;
  }

  public void setTopK(int topK) {
    this.topK = topK;
  }

  public int getMaxContextChars() {
    return maxContextChars;
  }

  public void setMaxContextChars(int maxContextChars) {
    this.maxContextChars = maxContextChars;
  }

  public int getMaxChunksPerDoc() {
    return maxChunksPerDoc;
  }

  public void setMaxChunksPerDoc(int maxChunksPerDoc) {
    this.maxChunksPerDoc = maxChunksPerDoc;
  }

  public int getMinChunkChars() {
    return minChunkChars;
  }

  public void setMinChunkChars(int minChunkChars) {
    this.minChunkChars = minChunkChars;
  }

  public int getHybridCandidateMultiplier() {
    return hybridCandidateMultiplier;
  }

  public void setHybridCandidateMultiplier(int hybridCandidateMultiplier) {
    this.hybridCandidateMultiplier = hybridCandidateMultiplier;
  }
}
