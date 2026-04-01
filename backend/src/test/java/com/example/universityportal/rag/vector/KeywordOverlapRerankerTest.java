package com.example.universityportal.rag.vector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordOverlapRerankerTest {

  @Test
  void favorsIdentifierMatchOverLooseYearOverlap() {
    VectorSearchResult weak = new VectorSearchResult(
        UUID.randomUUID(),
        "doc-a",
        "generic",
        "DOC_A",
        "1.0",
        "geral",
        "analise bibliometrica no trienio 2007 a 2009",
        0.80
    );
    VectorSearchResult strong = new VectorSearchResult(
        UUID.randomUUID(),
        "doc-b",
        "edital_07_2026_1",
        "EDITAL_07_2026_1",
        "3.5.2",
        "documentos doutorado",
        "edital 07 2026 1 objetivo principal",
        0.70
    );
    List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(
        "Qual e o objetivo principal do Edital 07 - 2026.1?",
        List.of(weak, strong),
        QueryLanguage.PT
    );
    assertEquals("doc-b", ranked.get(0).sourceFileId());
  }

  @Test
  void prefersLanguageMatchWhenSignalsAreSimilar() {
    VectorSearchResult english = new VectorSearchResult(
        UUID.randomUUID(),
        "doc-en",
        "notice",
        "NOTICE",
        "2.1",
        "requirements",
        "main objective of notice and selection process",
        0.60
    );
    VectorSearchResult portuguese = new VectorSearchResult(
        UUID.randomUUID(),
        "doc-pt",
        "edital",
        "EDITAL",
        "2.1",
        "requisitos",
        "objetivo principal do edital e processo seletivo",
        0.60
    );
    List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(
        "What is the main objective of the notice?",
        List.of(portuguese, english),
        QueryLanguage.EN
    );
    assertEquals("doc-en", ranked.get(0).sourceFileId());
  }
}

