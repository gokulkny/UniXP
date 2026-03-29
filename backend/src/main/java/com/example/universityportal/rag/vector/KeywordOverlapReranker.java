package com.example.universityportal.rag.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight keyword overlap on candidate chunks (hybrid rerank).
 */
public final class KeywordOverlapReranker {
  private KeywordOverlapReranker() {}

  public static List<VectorSearchResult> rerank(String query, List<VectorSearchResult> candidates) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    List<String> qt = tokenize(query);
    if (qt.isEmpty()) return new ArrayList<>(candidates);

    List<Scored> scored = new ArrayList<>();
    for (VectorSearchResult c : candidates) {
      String lc = c.content().toLowerCase(Locale.ROOT);
      int hits = 0;
      for (String t : qt) {
        if (lc.contains(t)) hits++;
      }
      double combined = c.score() + hits * 0.15;
      scored.add(new Scored(c, combined, hits));
    }
    scored.sort(Comparator.comparingDouble((Scored s) -> s.combined).reversed());
    List<VectorSearchResult> out = new ArrayList<>();
    for (Scored s : scored) {
      out.add(s.result);
    }
    return out;
  }

  private static List<String> tokenize(String text) {
    if (text == null || text.isBlank()) return List.of();
    String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      if (p != null && p.length() >= 2) out.add(p);
    }
    return out;
  }

  private record Scored(VectorSearchResult result, double combined, int hits) {}
}
