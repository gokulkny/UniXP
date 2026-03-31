package com.example.universityportal.rag.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight keyword overlap on candidate chunks (hybrid rerank).
 */
public final class KeywordOverlapReranker {
  private KeywordOverlapReranker() {}
  private static final Set<String> STOPWORDS = buildStopwords();

  public static List<VectorSearchResult> rerank(String query, List<VectorSearchResult> candidates) {
    return rerank(query, candidates, QueryLanguage.UNKNOWN);
  }

  public static List<VectorSearchResult> rerank(String query, List<VectorSearchResult> candidates, QueryLanguage queryLanguage) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    List<String> qt = tokenize(query);
    if (qt.isEmpty()) return new ArrayList<>(candidates);
    Set<String> queryTokens = new HashSet<>(qt);

    List<Scored> scored = new ArrayList<>();
    for (VectorSearchResult c : candidates) {
      Set<String> contentTokens = new HashSet<>(tokenize(c.content()));
      int tokenHits = 0;
      int numericHits = 0;
      for (String t : queryTokens) {
        if (contentTokens.contains(t)) {
          tokenHits++;
          if (isNumericToken(t)) numericHits++;
        }
      }
      QueryLanguage contentLanguage = QueryLanguageDetector.detect(c.content());
      double languageBoost = languageCompatibilityBoost(queryLanguage, contentLanguage);
      // Numeric/id tokens (e.g. 07, 2026, 2026.1 split pieces) are strong anchors.
      double combined = c.score() + tokenHits * 0.20 + numericHits * 0.45 + languageBoost;
      scored.add(new Scored(c, combined, tokenHits, numericHits, languageBoost));
    }
    scored.sort(Comparator.comparingDouble((Scored s) -> s.combined).reversed());
    List<VectorSearchResult> out = new ArrayList<>();
    for (Scored s : scored) {
      out.add(s.result);
    }
    return out;
  }

  public static List<String> tokenize(String text) {
    if (text == null || text.isBlank()) return List.of();
    // Unicode-aware tokenization (handles Portuguese accents); keep digits for notice numbers.
    String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      if (p == null) continue;
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (STOPWORDS.contains(t)) continue;
      boolean numeric = isNumericToken(t);
      if (!numeric && t.length() < 3) continue;
      out.add(t);
    }
    return out;
  }

  private static boolean isNumericToken(String t) {
    if (t == null || t.isBlank()) return false;
    for (int i = 0; i < t.length(); i++) {
      if (!Character.isDigit(t.charAt(i))) return false;
    }
    return true;
  }

  private static double languageCompatibilityBoost(QueryLanguage queryLanguage, QueryLanguage contentLanguage) {
    if (queryLanguage == QueryLanguage.UNKNOWN || contentLanguage == QueryLanguage.UNKNOWN) return 0.0;
    if (queryLanguage == contentLanguage) return 0.08;
    // Keep cross-language retrieval possible; just a mild preference.
    return -0.03;
  }

  private static Set<String> buildStopwords() {
    HashSet<String> out = new HashSet<>();
    out.addAll(Arrays.asList(
        // EN
        "the", "and", "for", "with", "that", "this", "from", "into", "onto", "than", "then", "when", "where",
        "what", "which", "who", "whom", "whose", "why", "how", "can", "could", "should", "would", "will", "may",
        "might", "must", "are", "is", "was", "were", "be", "been", "being", "a", "an", "to", "of", "in", "on",
        "at", "by", "as", "or", "not", "it", "its", "your", "you", "we", "our", "their", "they", "them", "he",
        "she", "his", "her", "i", "me", "my",
        // PT
        "de", "do", "da", "dos", "das", "e", "em", "no", "na", "nos", "nas", "ao", "aos", "o", "a", "os", "as",
        "um", "uma", "uns", "umas", "que", "como", "qual", "quais", "onde", "quando", "por", "para", "com", "sem",
        "se", "ser", "foi", "sao", "são", "esta", "está", "estao", "estão"
    ));
    return Set.copyOf(out);
  }

  private record Scored(
      VectorSearchResult result,
      double combined,
      int tokenHits,
      int numericHits,
      double languageBoost
  ) {}
}
