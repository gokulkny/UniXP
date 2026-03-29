package com.example.universityportal.rag.vector;

import com.example.universityportal.rag.DrivePdfDocument;
import com.example.universityportal.rag.GoogleDrivePdfLoader;
import com.example.universityportal.rag.RagEnvKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Profile("vector")
public class VectorRagIndexService {
  private static final Logger log = LoggerFactory.getLogger(VectorRagIndexService.class);

  private final GoogleDrivePdfLoader drivePdfLoader;
  private final EmbeddingClient embeddingClient;
  private final VectorStore vectorStore;
  private final RagRetrievalProperties ragProperties;
  private final JdbcTemplate jdbcTemplate;

  public VectorRagIndexService(
      GoogleDrivePdfLoader drivePdfLoader,
      EmbeddingClient embeddingClient,
      VectorStore vectorStore,
      RagRetrievalProperties ragProperties,
      JdbcTemplate jdbcTemplate
  ) {
    this.drivePdfLoader = drivePdfLoader;
    this.embeddingClient = embeddingClient;
    this.vectorStore = vectorStore;
    this.ragProperties = ragProperties;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Runs after the app is fully started (avoids ordering issues vs. Flyway / web tier). */
  @EventListener(ApplicationReadyEvent.class)
  public void indexOnStartup() {
    var mode = ragProperties.getRetrievalMode();
    log.info("VectorRagIndexService: startup hook running, portal.rag.retrieval-mode={}", mode);
    if (mode != RagRetrievalProperties.RetrievalMode.vector && mode != RagRetrievalProperties.RetrievalMode.hybrid) {
      log.warn(
          "Vector RAG indexing skipped: need portal.rag.retrieval-mode=vector or hybrid (current={}). "
              + "Table rag_chunk will stay empty until you enable vector/hybrid and restart.",
          mode
      );
      return;
    }

    String sa = System.getenv("PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON");
    if (sa == null || sa.isBlank()) {
      log.warn(
          "Vector RAG indexing skipped: PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON is not set. "
              + "No rows will be written to rag_chunk."
      );
      return;
    }

    log.info("Vector RAG: starting full index from Google Drive into Postgres (rag_chunk)...");
    logRagChunkCount("before indexing");
    try {
      indexAll(sa);
      logRagChunkCount("after indexing");
    } catch (Exception e) {
      log.error("Vector RAG indexing failed; rag_chunk may be empty or partial", e);
      logRagChunkCount("after failure");
    }
  }

  private void logRagChunkCount(String label) {
    try {
      Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_chunk", Long.class);
      log.info("Vector RAG: rag_chunk row count {}: {}", label, n);
    } catch (Exception e) {
      log.warn("Vector RAG: could not read rag_chunk count ({}): {}", label, e.getMessage());
    }
  }

  public void indexAll(String serviceAccountJson) throws Exception {
    int chunkSize = RagEnvKeys.getEnvInt("PORTAL_RAG_CHUNK_SIZE_CHARS", 1200);
    int overlap = RagEnvKeys.getEnvInt("PORTAL_RAG_CHUNK_OVERLAP_CHARS", 150);
    // Match legacy TF-IDF path (min 120); env PORTAL_RAG_MIN_CHUNK_CHARS overrides.
    int minChars = RagEnvKeys.getEnvInt("PORTAL_RAG_MIN_CHUNK_CHARS", ragProperties.getMinChunkChars());
    int batchSize = RagEnvKeys.getEnvInt("PORTAL_EMBEDDING_BATCH_SIZE", 16);

    log.info(
        "Vector RAG: loading PDFs from Drive (chunkSize={}, overlap={}, minChunkChars={}, embedBatchSize={})",
        chunkSize, overlap, minChars, batchSize
    );
    List<DrivePdfDocument> docs = drivePdfLoader.loadChunkedDocuments(serviceAccountJson, chunkSize, overlap, minChars);
    if (docs.isEmpty()) {
      log.warn(
          "Vector RAG: no documents returned from Drive (check portal.drive.fileIds, sharing, and PDF text). "
              + "rag_chunk will remain empty."
      );
      return;
    }
    log.info("Vector RAG: loaded {} PDF document(s) from Drive; embedding and upserting...", docs.size());

    for (DrivePdfDocument doc : docs) {
      log.info("Vector indexing file id={}, name={}, chunks={}",
          doc.sourceFileId(), doc.sourceFileName(), doc.chunkTexts().size());
      vectorStore.deleteBySourceFileId(doc.sourceFileId());

      List<String> texts = doc.chunkTexts();
      List<String> batchTexts = new ArrayList<>();
      List<Integer> batchIndices = new ArrayList<>();

      for (int i = 0; i < texts.size(); i++) {
        String text = texts.get(i);
        if (text == null || text.isBlank()) continue;
        batchTexts.add(text);
        batchIndices.add(i);
        if (batchTexts.size() >= batchSize) {
          flushEmbeddings(doc, batchTexts, batchIndices);
          batchTexts.clear();
          batchIndices.clear();
        }
      }
      if (!batchTexts.isEmpty()) {
        flushEmbeddings(doc, batchTexts, batchIndices);
      }
    }

    log.info("Vector RAG indexing complete for {} document(s)", docs.size());
    logRagChunkCount("after indexAll loop");
  }

  private void flushEmbeddings(DrivePdfDocument doc, List<String> texts, List<Integer> indices)
      throws java.io.IOException, InterruptedException {
    List<float[]> vectors = embeddingClient.embedBatch(texts);
    if (vectors.size() != texts.size()) {
      throw new IllegalStateException("Embedding batch size mismatch");
    }
    List<VectorChunkRecord> records = new ArrayList<>();
    for (int j = 0; j < texts.size(); j++) {
      records.add(new VectorChunkRecord(
          UUID.randomUUID(),
          doc.sourceFileId(),
          doc.sourceFileName(),
          indices.get(j),
          texts.get(j),
          vectors.get(j)
      ));
    }
    vectorStore.upsert(records);
    log.info(
        "Vector RAG: upserted {} chunk row(s) for file id={}, name={}",
        records.size(), doc.sourceFileId(), doc.sourceFileName()
    );
  }
}
