package com.example.universityportal.rag.vector;

import java.io.IOException;
import java.util.List;

public interface EmbeddingClient {
  float[] embed(String text) throws IOException, InterruptedException;

  List<float[]> embedBatch(List<String> texts) throws IOException, InterruptedException;
}
