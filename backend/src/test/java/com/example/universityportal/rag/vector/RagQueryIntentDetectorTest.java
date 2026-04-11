package com.example.universityportal.rag.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagQueryIntentDetectorTest {

  @Test
  void detectsPhdDocumentsQuestion() {
    assertEquals(
        RagQueryIntent.DOCUMENTS_PHD,
        RagQueryIntentDetector.detect("What are the required documents for a PhD application?")
    );
  }

  @Test
  void detectsPhdDocumentsInPortuguese() {
    assertEquals(
        RagQueryIntent.DOCUMENTS_PHD,
        RagQueryIntentDetector.detect("Quais documentos para o doutorado?")
    );
  }

  @Test
  void detectsEvaluationIntent() {
    assertEquals(
        RagQueryIntent.EVALUATION_OR_PRODUCTION,
        RagQueryIntentDetector.detect("Como preencher a ficha de produção no estágio 4?")
    );
  }
}
