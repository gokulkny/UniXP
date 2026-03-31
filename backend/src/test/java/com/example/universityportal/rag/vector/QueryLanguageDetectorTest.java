package com.example.universityportal.rag.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryLanguageDetectorTest {

  @Test
  void detectsPortugueseWithAccents() {
    QueryLanguage lang = QueryLanguageDetector.detect("Qual e o objetivo do edital de educacao?");
    assertEquals(QueryLanguage.PT, lang);
  }

  @Test
  void detectsEnglishQuestion() {
    QueryLanguage lang = QueryLanguageDetector.detect("What is the main objective of notice 07-2026.1?");
    assertEquals(QueryLanguage.EN, lang);
  }
}

