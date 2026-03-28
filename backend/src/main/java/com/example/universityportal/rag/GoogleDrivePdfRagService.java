package com.example.universityportal.rag;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.http.HttpCredentialsAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");
  private static final Set<String> STOPWORDS = Set.of(
      "the", "and", "for", "with", "that", "this", "from", "into", "onto", "than", "then", "when", "where",
      "what", "which", "who", "whom", "whose", "why", "how", "can", "could", "should", "would", "will", "may",
      "might", "must", "are", "is", "was", "were", "be", "been", "being", "a", "an", "to", "of", "in", "on",
      "at", "by", "as", "or", "not", "it", "its", "your", "you", "we", "our", "their", "they", "them", "he",
      "she", "his", "her", "i", "me", "my"
  );
  private static final Set<String> SHORT_TOKEN_ALLOWLIST = Set.of(
      // common citation/APA tokens that are meaningful even when short
      "et", "al", "pp", "p", "no", "vol", "ed", "eds"
  );

  private final AtomicBoolean ready = new AtomicBoolean(false);

  @Value("${portal.drive.fileIds:}")
  private String fileIdsCsv;

  public Optional<String> retrieveContext(String query) {
    if (!ready.get()) return Optional.empty();

    int topK = getEnvInt("PORTAL_RAG_TOP_K", 5);
    int maxChars = getEnvInt("PORTAL_RAG_MAX_CONTEXT_CHARS", 6000);
    int minChunkChars = getEnvInt("PORTAL_RAG_MIN_CHUNK_CHARS", 180);
    int maxChunksPerDoc = getEnvInt("PORTAL_RAG_MAX_CHUNKS_PER_DOC", 4);

    List<String> queryTokens = tokenizeForRag(query, true);
    if (queryTokens.isEmpty()) return Optional.empty();

    Map<String, Integer> qtf = termCounts(queryTokens);
    String queryNorm = normalizeWhitespace(query).toLowerCase(Locale.ROOT);

    // Score each chunk with a simple TF-IDF-ish dot product.
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
      // Small bonus for exact phrase presence (helps for "et al", "pp.", etc.).
      String chunkNorm = chunk.normalizedLowerText();
      if (queryNorm.length() >= 3 && chunkNorm.contains(queryNorm)) {
        score += 5.0;
      } else {
        // Bonus for partial phrase match (query without stopwords).
        String qNoStops = removeStopwords(queryNorm);
        if (qNoStops.length() >= 3 && chunkNorm.contains(qNoStops)) score += 2.0;
      }
      scored.add(new ScoredChunk(chunk, score));
    }

    scored.sort(Comparator.comparingDouble((ScoredChunk s) -> s.score).reversed());

    // Diversity: prefer at least some different docs.
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

  // In-memory index
  private volatile List<RagChunk> chunks = List.of();
  private volatile Map<String, Double> idfByTerm = Map.of();

  @PostConstruct
  public void indexOnStartup() {
    String envServiceAccountJson = System.getenv("PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON");
    if (fileIdsCsv == null || fileIdsCsv.isBlank()) {
      log.info("RAG disabled: portal.drive.fileIds is not set.");
      return;
    }
    if (envServiceAccountJson == null || envServiceAccountJson.isBlank()) {
      log.info("RAG disabled: PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON is not set.");
      return;
    }

    try {
      log.info("Starting RAG indexing from Google Drive...");
      List<String> fileIds = parseFileIds(fileIdsCsv);
      if (fileIds.isEmpty()) {
        log.info("RAG disabled: portal.drive.fileIds resolved to empty list.");
        return;
      }

      Drive drive = buildDriveClient(envServiceAccountJson);

      int chunkSize = getEnvInt("PORTAL_RAG_CHUNK_SIZE_CHARS", 1200);
      int chunkOverlap = getEnvInt("PORTAL_RAG_CHUNK_OVERLAP_CHARS", 150);

      List<RagChunk> allChunks = new ArrayList<>();

      for (String fileId : fileIds) {
        File meta = drive.files().get(fileId).setFields("id,name,mimeType").execute();
        if (meta == null) continue;
        if (meta.getMimeType() != null && !meta.getMimeType().toLowerCase(Locale.ROOT).contains("pdf")) {
          log.warn("Skipping non-PDF file: id={}, name={}, mimeType={}", meta.getId(), meta.getName(), meta.getMimeType());
          continue;
        }

        log.info("Downloading PDF: id={}, name={}", meta.getId(), meta.getName());
        byte[] pdfBytes = downloadFileBytes(drive, fileId);
        if (pdfBytes == null || pdfBytes.length == 0) continue;

        String text = extractPdfText(pdfBytes);
        if (text == null || text.isBlank()) {
          log.warn("No extractable text for PDF: id={}, name={}", meta.getId(), meta.getName());
          continue;
        }

        List<String> chunkTexts = chunkText(text, chunkSize, chunkOverlap);
        log.info("Chunked into {} chunks (id={}, name={})", chunkTexts.size(), meta.getId(), meta.getName());

        for (String ct : chunkTexts) {
          String normalized = normalizeWhitespace(ct);
          if (normalized.length() < 120) continue;
          List<String> tokens = tokenizeForRag(normalized, true);
          if (tokens.isEmpty()) continue;

          Map<String, Integer> tf = termCounts(tokens);
          allChunks.add(new RagChunk(meta.getId(), meta.getName(), normalized, normalized.toLowerCase(Locale.ROOT), tf));
        }
      }

      if (allChunks.isEmpty()) {
        log.warn("RAG indexing completed but no chunks were created.");
        return;
      }

      // Build IDF across the whole corpus.
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
        // Smoothed IDF:
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

  private List<String> parseFileIds(String csv) {
    String[] parts = csv.split(",");
    List<String> ids = new ArrayList<>();
    for (String p : parts) {
      String v = p.trim();
      if (!v.isEmpty()) ids.add(v);
    }
    return ids;
  }

  private Drive buildDriveClient(String serviceAccountJson) throws Exception {
    GoogleCredentials credentials = ServiceAccountCredentials.fromStream(
        new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))
    ).createScoped(Collections.singleton(DriveScopes.DRIVE_READONLY));

    HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);

    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Drive.Builder(httpTransport, JSON_FACTORY, requestInitializer)
        .setApplicationName("UniPortal-Demo")
        .build();
  }

  private byte[] downloadFileBytes(Drive drive, String fileId) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      // setFields not needed; we just stream media
      drive.files().get(fileId)
          .executeMediaAndDownloadTo(out);
      return out.toByteArray();
    } finally {
      out.close();
    }
  }

  private String extractPdfText(byte[] pdfBytes) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      // Heuristic: some PDFs can have multiple pages.
      return stripper.getText(doc);
    }
  }

  private List<String> chunkText(String text, int chunkSizeChars, int overlapChars) {
    String normalized = normalizeWhitespace(text);
    if (normalized.length() <= chunkSizeChars) return List.of(normalized);

    int step = Math.max(1, chunkSizeChars - overlapChars);
    List<String> chunks = new ArrayList<>();
    int i = 0;
    while (i < normalized.length()) {
      int end = Math.min(normalized.length(), i + chunkSizeChars);
      String part = normalized.substring(i, end);
      chunks.add(part);
      if (end >= normalized.length()) break;
      i += step;
    }
    return chunks;
  }

  private String normalizeWhitespace(String s) {
    if (s == null) return "";
    return s.replaceAll("\\s+", " ").trim();
  }

  private List<String> tokenize(String text) {
    return tokenizeForRag(text, true);
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

    // Add bigrams to preserve multi-token phrases like "et al".
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

  private int getEnvInt(String key, int defaultValue) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return defaultValue;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}

