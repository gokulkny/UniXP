package com.example.universityportal.rag;

import com.example.universityportal.rag.vector.RagRetrievalProperties;
import com.example.universityportal.rag.vector.VectorRagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class GoogleDrivePdfRagService {
  private static final Logger log = LoggerFactory.getLogger(GoogleDrivePdfRagService.class);

  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");
  private static final Set<String> STOPWORDS = Set.of(
      "the", "and", "for", "with", "that", "this", "from", "into", "onto", "than", "then", "when", "where",
      "what", "which", "who", "whom", "whose", "why", "how", "can", "could", "should", "would", "will", "may",
      "might", "must", "are", "is", "was", "were", "be", "been", "being", "a", "an", "to", "of", "in", "on",
      "at", "by", "as", "or", "not", "it", "its", "your", "you", "we", "our", "their", "they", "them", "he",
      "she", "his", "her", "i", "me", "my"
  );
  private static final Set<String> SHORT_TOKEN_ALLOWLIST = Set.of(
      "et", "al", "pp", "p", "no", "vol", "ed", "eds"
  );

  private final AtomicBoolean ready = new AtomicBoolean(false);
  private final RagRetrievalProperties ragRetrievalProperties;
  private final GoogleDrivePdfLoader pdfLoader;
  private final VectorRagRetriever vectorRagRetriever;

  private volatile List<RagChunk> chunks = List.of();
  private volatile Map<String, Double> idfByTerm = Map.of();

  public GoogleDrivePdfRagService(
      RagRetrievalProperties ragRetrievalProperties,
      GoogleDrivePdfLoader pdfLoader,
      @Autowired(required = false) VectorRagRetriever vectorRagRetriever
  ) {
    this.ragRetrievalProperties = ragRetrievalProperties;
    this.pdfLoader = pdfLoader;
    this.vectorRagRetriever = vectorRagRetriever;
  }

  public Optional<String> retrieveContext(String query) {
    RagRetrievalProperties.RetrievalMode mode = ragRetrievalProperties.getRetrievalMode();
    if ((mode == RagRetrievalProperties.RetrievalMode.vector || mode == RagRetrievalProperties.RetrievalMode.hybrid)
        && vectorRagRetriever != null) {
      Optional<String> fromVector = vectorRagRetriever.retrieve(query);
      if (fromVector.isPresent() && !fromVector.get().isBlank()) {
        return fromVector;
      }
      if (mode == RagRetrievalProperties.RetrievalMode.vector) {
        return Optional.empty();
      }
    }

    if (!ready.get()) return Optional.empty();

    int maxChars = RagEnvKeys.getEnvInt("PORTAL_RAG_MAX_CONTEXT_CHARS", 6000);
    int minChunkChars = RagEnvKeys.getEnvInt("PORTAL_RAG_MIN_CHUNK_CHARS", 180);
    int maxChunksPerDoc = RagEnvKeys.getEnvInt("PORTAL_RAG_MAX_CHUNKS_PER_DOC", 4);

    List<String> queryTokens = tokenizeForRag(query, true);
    if (queryTokens.isEmpty()) return Optional.empty();

    Map<String, Integer> qtf = termCounts(queryTokens);
    String queryNorm = DrivePdfTextUtils.normalizeWhitespace(query).toLowerCase(Locale.ROOT);

    List<ScoredChunk> scored = new ArrayList<>();
    for (RagChunk chunk : chunks) {
      if (chunk.text().length() < minChunkChars) continue;
      double score = 0.0;
      for (Map.Entry<String, Integer> e : qtf.entrySet()) {
        String term = e.getKey();
        Integer qCount = e.getValue();
        Integer tf = chunk.termCounts().get(term);
        if (tf == null) continue;
        Double idf = idfByTerm.get(term);
        if (idf == null) continue;
        score += (double) qCount * (double) tf * idf;
      }
      String chunkNorm = chunk.normalizedLowerText();
      if (queryNorm.length() >= 3 && chunkNorm.contains(queryNorm)) {
        score += 5.0;
      } else {
        String qNoStops = removeStopwords(queryNorm);
        if (qNoStops.length() >= 3 && chunkNorm.contains(qNoStops)) score += 2.0;
      }
      scored.add(new ScoredChunk(chunk, score));
    }

    scored.sort(Comparator.comparingDouble((ScoredChunk s) -> s.score).reversed());

    Map<String, Integer> perDoc = new HashMap<>();
    StringBuilder context = new StringBuilder();
    int totalChars = 0;

    for (ScoredChunk s : scored) {
      if (context.length() >= maxChars) break;
      RagChunk c = s.chunk;
      int used = perDoc.getOrDefault(c.sourceFileId(), 0);
      if (used >= maxChunksPerDoc) continue;

      String t = c.text();
      if (t == null || t.isBlank()) continue;

      int remaining = maxChars - totalChars;
      String trimmed = t.length() > remaining ? t.substring(0, Math.max(0, remaining)) : t;
      if (trimmed.isBlank()) continue;

      if (context.length() > 0) context.append("\n\n---\n\n");
      context.append(trimmed.trim());

      totalChars = context.length();
      perDoc.put(c.sourceFileId(), used + 1);
    }

    if (context.length() == 0) return Optional.empty();
    return Optional.of(context.toString());
  }

  @PostConstruct
  public void indexOnStartup() {
    String envServiceAccountJson = System.getenv("PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON");
    if (ragRetrievalProperties.getRetrievalMode() != RagRetrievalProperties.RetrievalMode.legacy) {
      log.info("Legacy in-memory RAG skipped (portal.rag.retrieval-mode={})", ragRetrievalProperties.getRetrievalMode());
      return;
    }
    if (envServiceAccountJson == null || envServiceAccountJson.isBlank()) {
      log.info("RAG disabled: PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON is not set.");
      return;
    }

    try {
      log.info("Starting legacy RAG indexing from Google Drive...");
      int chunkSize = RagEnvKeys.getEnvInt("PORTAL_RAG_CHUNK_SIZE_CHARS", 1200);
      int chunkOverlap = RagEnvKeys.getEnvInt("PORTAL_RAG_CHUNK_OVERLAP_CHARS", 150);

      List<DrivePdfDocument> docs = pdfLoader.loadChunkedDocuments(envServiceAccountJson, chunkSize, chunkOverlap, 120);
      List<RagChunk> allChunks = new ArrayList<>();
      for (DrivePdfDocument doc : docs) {
        for (DrivePdfChunk chunk : doc.chunks()) {
          String normalized = chunk.content();
          List<String> tokens = tokenizeForRag(normalized, true);
          if (tokens.isEmpty()) continue;
          Map<String, Integer> tf = termCounts(tokens);
          allChunks.add(new RagChunk(
              doc.sourceFileId(),
              doc.sourceFileName(),
              normalized,
              normalized.toLowerCase(Locale.ROOT),
              tf
          ));
        }
      }

      if (allChunks.isEmpty()) {
        log.warn("RAG indexing completed but no chunks were created.");
        return;
      }

      Map<String, Integer> df = new HashMap<>();
      for (RagChunk c : allChunks) {
        Set<String> seen = new HashSet<>(c.termCounts().keySet());
        for (String term : seen) {
          df.put(term, df.getOrDefault(term, 0) + 1);
        }
      }

      int n = allChunks.size();
      Map<String, Double> idf = new HashMap<>();
      for (Map.Entry<String, Integer> e : df.entrySet()) {
        String term = e.getKey();
        int docFreq = e.getValue();
        double v = Math.log((n + 1.0) / (docFreq + 1.0)) + 1.0;
        idf.put(term, v);
      }

      this.chunks = Collections.unmodifiableList(allChunks);
      this.idfByTerm = Collections.unmodifiableMap(idf);
      ready.set(true);
      log.info("RAG indexing ready: {} chunks, {} indexed terms.", this.chunks.size(), this.idfByTerm.size());
    } catch (Exception e) {
      log.error("RAG indexing failed; chatbot will answer without documents. Error: {}", e.getMessage());
    }
  }

  private static class ScoredChunk {
    final RagChunk chunk;
    final double score;

    ScoredChunk(RagChunk chunk, double score) {
      this.chunk = chunk;
      this.score = score;
    }
  }

  private List<String> tokenizeForRag(String text, boolean includeShortTokens) {
    if (text == null || text.isBlank()) return List.of();
    String normalized = text.toLowerCase(Locale.ROOT);
    String[] parts = TOKEN_SPLIT.split(normalized);
    List<String> base = new ArrayList<>();
    for (String p : parts) {
      if (p == null) continue;
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (STOPWORDS.contains(t)) continue;
      if (t.length() < 3) {
        if (!includeShortTokens) continue;
        if (!SHORT_TOKEN_ALLOWLIST.contains(t)) continue;
      }
      base.add(t);
    }

    if (base.size() < 2) return base;
    List<String> out = new ArrayList<>(base.size() + (base.size() - 1));
    out.addAll(base);
    for (int i = 0; i < base.size() - 1; i++) {
      out.add(base.get(i) + "_" + base.get(i + 1));
    }
    return out;
  }

  private String removeStopwords(String normalizedLower) {
    if (normalizedLower == null || normalizedLower.isBlank()) return "";
    String[] parts = TOKEN_SPLIT.split(normalizedLower);
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p == null) continue;
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (STOPWORDS.contains(t)) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(t);
    }
    return sb.toString().trim();
  }

  private Map<String, Integer> termCounts(List<String> tokens) {
    Map<String, Integer> tf = new HashMap<>();
    for (String t : tokens) {
      tf.put(t, tf.getOrDefault(t, 0) + 1);
    }
    return tf;
  }
}
