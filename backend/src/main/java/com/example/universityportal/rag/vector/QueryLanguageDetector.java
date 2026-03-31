package com.example.universityportal.rag.vector;

import java.util.Set;

public final class QueryLanguageDetector {
  private static final Set<String> PT_HINTS = Set.of(
      "de", "do", "da", "dos", "das", "qual", "quais", "como", "quando", "onde", "edital",
      "programa", "pesquisa", "mestrado", "doutorado", "educacao", "educação"
  );
  private static final Set<String> EN_HINTS = Set.of(
      "what", "which", "how", "when", "where", "notice", "program", "research",
      "master", "doctoral", "phd", "objective"
  );

  private QueryLanguageDetector() {}

  public static QueryLanguage detect(String text) {
    if (text == null || text.isBlank()) return QueryLanguage.UNKNOWN;
    String s = text.toLowerCase();

    // Accent-bearing chars are strong PT indicators.
    if (s.matches(".*[ãõáàâéêíóôúç].*")) return QueryLanguage.PT;

    int pt = 0;
    int en = 0;
    for (String t : s.split("[^\\p{L}\\p{N}]+")) {
      if (t == null || t.isBlank()) continue;
      if (PT_HINTS.contains(t)) pt++;
      if (EN_HINTS.contains(t)) en++;
    }
    if (pt > en) return QueryLanguage.PT;
    if (en > pt) return QueryLanguage.EN;
    return QueryLanguage.UNKNOWN;
  }
}

