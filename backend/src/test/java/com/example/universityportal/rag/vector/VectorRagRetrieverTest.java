package com.example.universityportal.rag.vector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorRagRetrieverTest {

  @Test
  void englishQueryUsesPortugueseTranslationBeforeEmbedding() {
    FakeEmbeddingClient embedding = new FakeEmbeddingClient();
    FakeVectorStore store = new FakeVectorStore();
    RagRetrievalProperties props = baseProps();
    OpenRouterQueryTranslator translator = new OpenRouterQueryTranslator() {
      @Override
      public String translateToPortuguese(String query) {
        return "quais documentos para doutorado edital 07 2026 1";
      }
    };
    VectorRagRetriever retriever = new VectorRagRetriever(embedding, store, props, translator);

    Optional<String> context = retriever.retrieve("What documents are required for notice 07-2026.1?");

    assertTrue(context.isPresent());
    assertTrue(embedding.inputs.stream().anyMatch(s -> s.contains("documentos para doutorado")));
  }

  @Test
  void prefersMatchingDocIdOverIrrelevantApaChunk() {
    FakeEmbeddingClient embedding = new FakeEmbeddingClient();
    FakeVectorStore store = new FakeVectorStore();
    RagRetrievalProperties props = baseProps();
    props.setTopK(1);
    OpenRouterQueryTranslator translator = new OpenRouterQueryTranslator() {
      @Override
      public String translateToPortuguese(String query) {
        return "documentos doutorado edital 07 2026 1";
      }
    };
    VectorRagRetriever retriever = new VectorRagRetriever(embedding, store, props, translator);

    Optional<String> context = retriever.retrieve("PhD requirements for notice 07-2026.1");

    assertTrue(context.isPresent());
    assertTrue(context.get().toLowerCase().contains("doutorado"));
    assertTrue(!context.get().toLowerCase().contains("apa"));
  }

  private static RagRetrievalProperties baseProps() {
    RagRetrievalProperties props = new RagRetrievalProperties();
    props.setRetrievalMode(RagRetrievalProperties.RetrievalMode.vector);
    props.setTopK(3);
    props.setSimilarityMinScore(0.35);
    return props;
  }

  private static final class FakeEmbeddingClient implements EmbeddingClient {
    private final List<String> inputs = new ArrayList<>();

    @Override
    public float[] embed(String text) {
      inputs.add(text);
      return new float[] {0.1f, 0.2f, 0.3f};
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
      List<float[]> vectors = new ArrayList<>();
      for (String text : texts) {
        inputs.add(text);
        vectors.add(new float[] {0.1f, 0.2f, 0.3f});
      }
      return vectors;
    }
  }

  private static final class FakeVectorStore implements VectorStore {
    @Override
    public void deleteBySourceFileId(String sourceFileId) {}

    @Override
    public void upsert(List<VectorChunkRecord> chunks) {}

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int topK) {
      return List.of(
          new VectorSearchResult(
              UUID.randomUUID(), "apa", "apa_guidelines.pdf", "APA_GUIDELINES", "1.0", "citation",
              "APA guidelines with citation examples only.", 0.92
          ),
          new VectorSearchResult(
              UUID.randomUUID(), "edital", "edital_07_2026_1.pdf", "EDITAL_07_2026_1", "3.5.2", "documentos doutorado",
              "Os documentos para inscricao no doutorado incluem historico e diploma.", 0.61
          )
      );
    }

    @Override
    public List<VectorSearchResult> searchByLexicalTokens(List<String> tokens, int limit) {
      return search(null, limit);
    }

    @Override
    public List<VectorSearchResult> searchByRequiredTokens(List<String> requiredTokens, int limit) {
      return List.of(
          new VectorSearchResult(
              UUID.randomUUID(), "edital", "edital_07_2026_1.pdf", "EDITAL_07_2026_1", "3.5.2", "documentos doutorado",
              "Documentos exigidos no edital 07 2026 1 para doutorado.", 1.0
          )
      );
    }
  }
}
