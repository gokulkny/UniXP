package com.example.universityportal.rag.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("vector")
public class VectorRagRetriever {
  private static final Logger log = LoggerFactory.getLogger(VectorRagRetriever.class);
  private static final Pattern ID_PATTERN = Pattern.compile("\\b\\d{1,4}(?:[./-]\\d{1,4}){0,2}\\b");

  private final EmbeddingClient embeddingClient;
  private final VectorStore vectorStore;
  private final RagRetrievalProperties properties;
  private final OpenRouterQueryTranslator queryTranslator;

  public VectorRagRetriever(
      EmbeddingClient embeddingClient,
      VectorStore vectorStore,
      RagRetrievalProperties properties,
      OpenRouterQueryTranslator queryTranslator
  ) {
    this.embeddingClient = embeddingClient;
    this.vectorStore = vectorStore;
    this.properties = properties;
    this.queryTranslator = queryTranslator;
  }

  public Optional<String> retrieve(String query) {
    RagRetrievalProperties.RetrievalMode mode = properties.getRetrievalMode();
    if (mode != RagRetrievalProperties.RetrievalMode.vector && mode != RagRetrievalProperties.RetrievalMode.hybrid) {
      return Optional.empty();
    }

    if (query == null || query.isBlank()) return Optional.empty();

    QueryLanguage queryLanguage = QueryLanguageDetector.detect(query);
    String retrievalQuery = normalizeRetrievalQuery(query.trim(), queryLanguage);
    String preferredDocId = inferPreferredDocId(retrievalQuery);
    RagQueryIntent intent = RagQueryIntentDetector.detect(query);
    if (intent == RagQueryIntent.GENERAL) {
      intent = RagQueryIntentDetector.detect(retrievalQuery);
    }
    ChunkSearchConstraints constraints = ChunkSearchConstraints.forIntent(intent, docIdLike(preferredDocId));
    log.debug("RAG intent={} sectionFilters={}", intent, constraints.hasSqlFilters());

    List<String> queryVariants = expandQueryVariants(retrievalQuery, intent);
    queryVariants.add(query.trim());
    queryVariants = dedupe(queryVariants);
    List<String> requiredTokens = identifierTokens(retrievalQuery);

    try {
      int topK = Math.max(1, properties.getTopK());
      if (intent == RagQueryIntent.DOCUMENTS_PHD || intent == RagQueryIntent.DOCUMENTS_MASTER) {
        topK = Math.max(topK, 5);
      }
      int poolMultiplier = Math.max(3, properties.getHybridCandidateMultiplier());
      int pool = Math.min(50, topK * poolMultiplier);
      int sectionAwareMin = Math.max(1, properties.getSectionAwareMinChunks());
      List<VectorSearchResult> required = vectorStore.searchByRequiredTokens(requiredTokens, Math.min(20, pool));
      List<VectorSearchResult> raw = new ArrayList<>();
      List<VectorSearchResult> lexical = new ArrayList<>();
      // One embedding API call for all variants (avoids N sequential timeouts on slow models).
      List<float[]> queryVectors = embeddingClient.embedBatch(queryVariants);
      if (queryVectors.size() != queryVariants.size()) {
        throw new IllegalStateException("Embedding batch size mismatch");
      }
      for (int i = 0; i < queryVariants.size(); i++) {
        String variant = queryVariants.get(i);
        float[] qvec = queryVectors.get(i);
        List<VectorSearchResult> constrained = vectorStore.search(qvec, pool, constraints);
        raw.addAll(constrained);
        if (constraints.hasSqlFilters() && constrained.size() < sectionAwareMin) {
          raw.addAll(vectorStore.search(qvec, Math.min(pool, 30), ChunkSearchConstraints.none()));
        }
        lexical.addAll(vectorStore.searchByLexicalTokens(KeywordOverlapReranker.tokenize(variant), Math.min(25, pool)));
      }
      List<VectorSearchResult> leading = List.of();
      if (isIdentifierHeavyQuery(KeywordOverlapReranker.tokenize(retrievalQuery))) {
        List<String> sourceIds = topSourceIds(!required.isEmpty() ? required : lexical, 1);
        leading = vectorStore.fetchLeadingChunksBySource(sourceIds, 4);
      }
      List<VectorSearchResult> merged = mergeById(required, raw, lexical, leading);
      if (merged.isEmpty()) {
        log.debug("Vector search returned no rows (vector and lexical)");
        return Optional.empty();
      }

      // Lightweight lexical rerank improves precision for identifiers like "Notice 07 – 2026.1".
      List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(retrievalQuery, merged, queryLanguage);
      ranked = applyIntentSectionBoost(ranked, intent);
      ranked = boostPreferredDoc(ranked, preferredDocId);
      ranked = prioritizePreferredDoc(ranked, preferredDocId);
      ranked = applyThreshold(ranked);
      if (ranked.isEmpty() || !contextLooksRelevant(ranked, retrievalQuery, intent)) {
        int retries = Math.min(Math.max(1, properties.getMaxRetryVariants()), queryVariants.size());
        for (int i = 0; i < retries; i++) {
          String variant = queryVariants.get(i);
          Optional<String> retry = retrieveLexicalFallback(variant, queryLanguage, KeywordOverlapReranker.tokenize(variant), requiredTokens);
          if (retry.isPresent()) return retry;
        }
        return Optional.empty();
      }

      int take = Math.min(ranked.size(), topK);
      List<VectorSearchResult> top = ranked.subList(0, take);
      String ctx = assembleContext(top);
      if (ctx.isBlank()) return Optional.empty();
      return Optional.of(ctx);
    } catch (Exception e) {
      // Embeddings can timeout under provider/network pressure. Fall back to lexical retrieval
      // so strict-from-docs mode can still answer from available chunks.
      log.warn("Vector retrieval failed, using lexical fallback: {}", e.getMessage());
      return retrieveLexicalFallback(retrievalQuery, queryLanguage, KeywordOverlapReranker.tokenize(retrievalQuery), requiredTokens);
    }
  }

  private Optional<String> retrieveLexicalFallback(
      String query,
      QueryLanguage queryLanguage,
      List<String> lexicalTokens,
      List<String> requiredTokens
  ) {
    int topK = Math.max(1, properties.getTopK());
    int poolMultiplier = Math.max(3, properties.getHybridCandidateMultiplier());
    int pool = Math.min(50, topK * poolMultiplier);

    List<VectorSearchResult> required = vectorStore.searchByRequiredTokens(requiredTokens, Math.min(20, pool));
    int lexLimit = Math.min(25, pool);
    List<VectorSearchResult> lexical = vectorStore.searchByLexicalTokens(lexicalTokens, lexLimit);
    if (lexical.isEmpty()) {
      lexical = vectorStore.searchByLexicalTokensSimple(lexicalTokens, lexLimit);
    }
    List<VectorSearchResult> leading = List.of();
    if (isIdentifierHeavyQuery(lexicalTokens)) {
      List<String> sourceIds = topSourceIds(!required.isEmpty() ? required : lexical, 1);
      leading = vectorStore.fetchLeadingChunksBySource(sourceIds, 4);
    }
    List<VectorSearchResult> merged = mergeById(required, lexical, leading, List.of());
    if (merged.isEmpty()) return Optional.empty();

    List<VectorSearchResult> ranked = KeywordOverlapReranker.rerank(query, merged, queryLanguage);
    int take = Math.min(ranked.size(), topK);
    String ctx = assembleContext(ranked.subList(0, take));
    if (ctx.isBlank()) return Optional.empty();
    return Optional.of(ctx);
  }

  private String assembleContext(List<VectorSearchResult> ranked) {
    int maxChars = properties.getMaxContextChars();
    int maxPerDoc = Math.max(1, properties.getMaxChunksPerDoc());
    Map<String, Integer> perDoc = new HashMap<>();
    StringBuilder context = new StringBuilder();
    int totalChars = 0;

    for (VectorSearchResult s : ranked) {
      if (context.length() >= maxChars) break;
      int used = perDoc.getOrDefault(s.sourceFileId(), 0);
      if (used >= maxPerDoc) continue;

      String t = s.content();
      if (t == null || t.isBlank()) continue;
      int remaining = maxChars - totalChars;
      String trimmed = t.length() > remaining ? t.substring(0, Math.max(0, remaining)) : t;
      if (trimmed.isBlank()) continue;

      if (context.length() > 0) context.append("\n\n---\n\n");
      context.append(trimmed.trim());
      totalChars = context.length();
      perDoc.put(s.sourceFileId(), used + 1);
    }

    if (context.length() == 0) return "";
    return context.toString();
  }

  private List<VectorSearchResult> mergeById(
      List<VectorSearchResult> first,
      List<VectorSearchResult> second,
      List<VectorSearchResult> third,
      List<VectorSearchResult> fourth
  ) {
    LinkedHashMap<String, VectorSearchResult> map = new LinkedHashMap<>();
    addAllUnique(map, first);
    addAllUnique(map, second);
    addAllUnique(map, third);
    addAllUnique(map, fourth);
    return new ArrayList<>(map.values());
  }

  private void addAllUnique(LinkedHashMap<String, VectorSearchResult> map, List<VectorSearchResult> list) {
    if (list == null) return;
    for (VectorSearchResult r : list) {
      if (r == null || r.id() == null) continue;
      map.putIfAbsent(r.id().toString(), r);
    }
  }

  private boolean isIdentifierHeavyQuery(List<String> tokens) {
    if (tokens == null || tokens.isEmpty()) return false;
    int digitTokens = 0;
    boolean hasNoticeWord = false;
    for (String t : tokens) {
      if (t == null) continue;
      if (t.chars().allMatch(Character::isDigit)) digitTokens++;
      if ("edital".equals(t) || "notice".equals(t) || "edict".equals(t)) hasNoticeWord = true;
    }
    return hasNoticeWord || digitTokens >= 2;
  }

  private List<String> topSourceIds(List<VectorSearchResult> ranked, int max) {
    LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
    if (ranked != null) {
      for (VectorSearchResult r : ranked) {
        if (r == null || r.sourceFileId() == null || r.sourceFileId().isBlank()) continue;
        ids.putIfAbsent(r.sourceFileId(), Boolean.TRUE);
        if (ids.size() >= max) break;
      }
    }
    return new ArrayList<>(ids.keySet());
  }

  private List<String> identifierTokens(String query) {
    if (query == null || query.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    Matcher m = ID_PATTERN.matcher(query.toLowerCase());
    while (m.find()) {
      String token = m.group();
      if (token == null || token.isBlank()) continue;
      out.add(token);
      // Also add split parts for formats like 2026.1 so SQL can match either representation.
      String[] parts = token.split("[./-]");
      for (String p : parts) {
        if (p != null && p.length() >= 2) out.add(p);
      }
      if (out.size() >= 6) break;
    }
    return dedupe(out);
  }

  private List<String> dedupe(List<String> items) {
    LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    for (String s : items) {
      if (s == null) continue;
      String t = s.trim();
      if (t.length() < 2) continue;
      seen.putIfAbsent(t, Boolean.TRUE);
    }
    return new ArrayList<>(seen.keySet());
  }

  private String normalizeRetrievalQuery(String query, QueryLanguage detectedLanguage) {
    if (detectedLanguage == QueryLanguage.EN) {
      try {
        return queryTranslator.translateToPortuguese(query);
      } catch (Exception e) {
        log.warn("Query translation failed, using original query: {}", e.getMessage());
      }
    }
    return query;
  }

  private List<String> expandQueryVariants(String query, RagQueryIntent intent) {
    List<String> variants = new ArrayList<>();
    variants.add(query);
    variants.add(query + " edital educacao");
    variants.add(query + " mestrado doutorado requisitos");
    variants.add(query + " documentos inscricao doutorado");
    if (intent == RagQueryIntent.DOCUMENTS_PHD) {
      variants.add(query + " 3.5.2 para o doutorado documentos necessarios inscricao");
      variants.add(query + " lista documentos doutorado comprovacao");
    } else if (intent == RagQueryIntent.DOCUMENTS_MASTER) {
      variants.add(query + " 3.5.1 documentos mestrado inscricao");
    }
    return variants;
  }

  private static String docIdLike(String preferredDocId) {
    if (preferredDocId == null || preferredDocId.isBlank()) return null;
    return "%" + preferredDocId + "%";
  }

  private List<VectorSearchResult> applyIntentSectionBoost(List<VectorSearchResult> ranked, RagQueryIntent intent) {
    if (ranked == null || ranked.isEmpty()) return ranked;
    if (intent != RagQueryIntent.DOCUMENTS_PHD && intent != RagQueryIntent.DOCUMENTS_MASTER) return ranked;
    String wantPrefix = intent == RagQueryIntent.DOCUMENTS_PHD ? "3.5.2" : "3.5.1";
    List<VectorSearchResult> out = new ArrayList<>();
    for (VectorSearchResult r : ranked) {
      String sec = r.section() == null ? "" : r.section().trim();
      double boost = 0;
      if (sec.startsWith(wantPrefix)) boost += 0.40;
      if (sec.startsWith("4.")) boost -= 0.55;
      if (boost != 0) {
        out.add(new VectorSearchResult(
            r.id(), r.sourceFileId(), r.sourceFileName(), r.docId(), r.section(), r.topic(), r.content(), r.score() + boost
        ));
      } else {
        out.add(r);
      }
    }
    out.sort((a, b) -> Double.compare(b.score(), a.score()));
    return out;
  }

  private List<VectorSearchResult> applyThreshold(List<VectorSearchResult> ranked) {
    double minScore = properties.getSimilarityMinScore();
    if (ranked == null || ranked.isEmpty()) return List.of();
    List<VectorSearchResult> kept = new ArrayList<>();
    for (VectorSearchResult s : ranked) {
      if (s.score() >= minScore || isRequiredTopicChunk(s)) {
        kept.add(s);
      }
    }
    return kept;
  }

  private boolean contextLooksRelevant(List<VectorSearchResult> ranked, String query, RagQueryIntent intent) {
    List<String> queryTokens = KeywordOverlapReranker.tokenize(query);
    if (!queryTokens.isEmpty()) {
      Set<String> required = new HashSet<>(queryTokens.subList(0, Math.min(4, queryTokens.size())));
      int hits = 0;
      for (VectorSearchResult result : ranked) {
        String content = result.content() == null ? "" : result.content().toLowerCase();
        for (String token : required) {
          if (content.contains(token)) {
            hits++;
            break;
          }
        }
        if (hits >= Math.min(2, required.size())) break;
      }
      if (hits < Math.min(2, Math.max(1, required.size())) && !required.isEmpty()) return false;
    }
    if (intent == RagQueryIntent.DOCUMENTS_PHD) {
      return ranked.stream().limit(8).anyMatch(r -> looksLikePhdSubmissionDocuments(r));
    }
    if (intent == RagQueryIntent.DOCUMENTS_MASTER) {
      return ranked.stream().limit(8).anyMatch(r -> looksLikeMasterSubmissionDocuments(r));
    }
    return true;
  }

  private static boolean looksLikePhdSubmissionDocuments(VectorSearchResult r) {
    String sec = r.section() == null ? "" : r.section().trim();
    if (sec.startsWith("4.")) return false;
    String c = r.content() == null ? "" : r.content().toLowerCase();
    return sec.startsWith("3.5.2")
        || c.contains("3.5.2")
        || (c.contains("documento") && c.contains("doutorado"))
        || (c.contains("para o doutorado") && c.contains("document"));
  }

  private static boolean looksLikeMasterSubmissionDocuments(VectorSearchResult r) {
    String sec = r.section() == null ? "" : r.section().trim();
    if (sec.startsWith("4.")) return false;
    String c = r.content() == null ? "" : r.content().toLowerCase();
    return sec.startsWith("3.5.1")
        || c.contains("3.5.1")
        || (c.contains("documento") && c.contains("mestrado"));
  }

  private String inferPreferredDocId(String query) {
    List<String> ids = identifierTokens(query);
    if (ids.isEmpty()) return "";
    return String.join("_", ids).toUpperCase();
  }

  private List<VectorSearchResult> boostPreferredDoc(List<VectorSearchResult> ranked, String preferredDocId) {
    if (preferredDocId == null || preferredDocId.isBlank() || ranked == null || ranked.isEmpty()) return ranked;
    List<VectorSearchResult> boosted = new ArrayList<>();
    for (VectorSearchResult s : ranked) {
      if (matchesPreferredDoc(s, preferredDocId)) {
        boosted.add(new VectorSearchResult(
            s.id(), s.sourceFileId(), s.sourceFileName(), s.docId(), s.section(), s.topic(), s.content(), s.score() + 0.30
        ));
      } else {
        boosted.add(s);
      }
    }
    boosted.sort((a, b) -> Double.compare(b.score(), a.score()));
    return boosted;
  }

  private List<VectorSearchResult> prioritizePreferredDoc(List<VectorSearchResult> ranked, String preferredDocId) {
    if (preferredDocId == null || preferredDocId.isBlank() || ranked == null || ranked.isEmpty()) return ranked;
    List<VectorSearchResult> preferred = new ArrayList<>();
    List<VectorSearchResult> others = new ArrayList<>();
    for (VectorSearchResult s : ranked) {
      if (matchesPreferredDoc(s, preferredDocId)) preferred.add(s);
      else others.add(s);
    }
    if (preferred.size() >= Math.max(1, properties.getTopK() / 2)) return preferred;
    preferred.addAll(others);
    return preferred;
  }

  private boolean matchesPreferredDoc(VectorSearchResult s, String preferredDocId) {
    if (s == null || preferredDocId == null || preferredDocId.isBlank()) return false;
    if (s.docId() != null && s.docId().toUpperCase().contains(preferredDocId)) return true;
    return s.sourceFileName() != null && s.sourceFileName().toUpperCase().contains(preferredDocId);
  }

  private boolean isRequiredTopicChunk(VectorSearchResult s) {
    if (s == null || s.topic() == null) return false;
    String t = s.topic().toLowerCase();
    return t.contains("documentos") || t.contains("inscri") || t.contains("doutorado");
  }
}
