package com.example.universityportal.api;

import com.example.universityportal.rag.vector.RagRetrievalProperties;
import com.example.universityportal.rag.vector.VectorRagIndexService;
import com.example.universityportal.rag.vector.VectorRagRetriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local debugging: confirms the app sees the same DB as your SQL client and whether indexing can run.
 */
@RestController
@RequestMapping("/api/rag")
public class RagDiagnosticsController {

  private final Environment environment;
  private final RagRetrievalProperties ragProperties;
  private final ObjectProvider<JdbcTemplate> jdbcTemplate;
  private final ObjectProvider<VectorRagIndexService> indexService;
  private final ObjectProvider<VectorRagRetriever> retriever;

  public RagDiagnosticsController(
      Environment environment,
      RagRetrievalProperties ragProperties,
      ObjectProvider<JdbcTemplate> jdbcTemplate,
      ObjectProvider<VectorRagIndexService> indexService,
      ObjectProvider<VectorRagRetriever> retriever
  ) {
    this.environment = environment;
    this.ragProperties = ragProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.indexService = indexService;
    this.retriever = retriever;
  }

  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("activeProfiles", String.join(",", environment.getActiveProfiles()));
    body.put("defaultProfiles", String.join(",", environment.getDefaultProfiles()));
    body.put("portalRagRetrievalMode", ragProperties.getRetrievalMode().name());
    body.put("jdbcTemplatePresent", jdbcTemplate.getIfAvailable() != null);
    body.put("vectorRagIndexServicePresent", indexService.getIfAvailable() != null);
    body.put("portalGoogleServiceAccountJsonSet", isEnvSet("PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON"));
    body.put("portalOpenRouterApiKeySet", isEnvSet("PORTAL_OPENROUTER_API_KEY"));

    JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
    if (jdbc == null) {
      body.put("ragChunkCount", null);
      body.put("ragChunkError", "No JdbcTemplate (use profile 'vector' with spring.datasource.*)");
      return ResponseEntity.ok(body);
    }
    try {
      Long n = jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk", Long.class);
      body.put("ragChunkCount", n);
      body.put("ragChunkError", null);
    } catch (Exception e) {
      body.put("ragChunkCount", null);
      body.put("ragChunkError", e.getMessage());
    }
    return ResponseEntity.ok(body);
  }

  @PostMapping("/reindex")
  public ResponseEntity<Map<String, Object>> reindex() {
    VectorRagIndexService svc = indexService.getIfAvailable();
    Map<String, Object> body = new LinkedHashMap<>();
    if (svc == null) {
      body.put("ok", false);
      body.put("error", "VectorRagIndexService not available (need DataSource + vector/hybrid mode)");
      return ResponseEntity.status(503).body(body);
    }
    String sa = System.getenv("PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON");
    if (sa == null || sa.isBlank()) {
      body.put("ok", false);
      body.put("error", "PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON not set");
      return ResponseEntity.status(400).body(body);
    }
    try {
      svc.indexAll(sa);
      body.put("ok", true);
      JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
      if (jdbc != null) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk", Long.class);
        body.put("ragChunkCount", n);
      }
      return ResponseEntity.ok(body);
    } catch (Exception e) {
      body.put("ok", false);
      body.put("error", e.getMessage());
      return ResponseEntity.status(500).body(body);
    }
  }

  private static boolean isEnvSet(String key) {
    String v = System.getenv(key);
    return v != null && !v.isBlank();
  }

  /**
   * Debug retrieval for a specific query. Helps diagnose "no context" refusals.
   * This endpoint returns a boolean and a short preview, not the full content.
   */
  @PostMapping("/debug-retrieve")
  public ResponseEntity<Map<String, Object>> debugRetrieve(@RequestBody Map<String, Object> payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("portalRagRetrievalMode", ragProperties.getRetrievalMode().name());
    VectorRagRetriever r = retriever.getIfAvailable();
    body.put("vectorRagRetrieverPresent", r != null);
    if (r == null) {
      body.put("ok", false);
      body.put("error", "VectorRagRetriever not available (need profile 'vector')");
      return ResponseEntity.status(503).body(body);
    }

    String query = payload == null ? null : (String) payload.get("query");
    if (query == null || query.isBlank()) {
      body.put("ok", false);
      body.put("error", "query is required");
      return ResponseEntity.badRequest().body(body);
    }

    try {
      Optional<String> ctx = r.retrieve(query);
      body.put("ok", true);
      body.put("hasContext", ctx.isPresent() && !ctx.get().isBlank());
      String preview = ctx.orElse("");
      if (preview.length() > 300) preview = preview.substring(0, 300);
      body.put("contextPreview", preview);
      body.put("contextChars", ctx.map(String::length).orElse(0));
      return ResponseEntity.ok(body);
    } catch (Exception e) {
      body.put("ok", false);
      body.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      return ResponseEntity.status(500).body(body);
    }
  }

  /**
   * Evaluate retrieval coverage on a small bilingual gold set.
   * Request body:
   * {
   *   "cases": [{"query":"...", "label":"en->pt", "expectedHasContext": true}, ...]
   * }
   */
  @PostMapping("/evaluate")
  public ResponseEntity<Map<String, Object>> evaluate(@RequestBody Map<String, Object> payload) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("portalRagRetrievalMode", ragProperties.getRetrievalMode().name());
    VectorRagRetriever r = retriever.getIfAvailable();
    if (r == null) {
      body.put("ok", false);
      body.put("error", "VectorRagRetriever not available (need profile 'vector')");
      return ResponseEntity.status(503).body(body);
    }
    Object rawCases = payload == null ? null : payload.get("cases");
    if (!(rawCases instanceof List<?> cases) || cases.isEmpty()) {
      body.put("ok", false);
      body.put("error", "cases[] is required");
      return ResponseEntity.badRequest().body(body);
    }

    int total = 0;
    int expectedComparisons = 0;
    int expectedMatched = 0;
    int hasContextCount = 0;
    Map<String, Integer> hasContextByLabel = new LinkedHashMap<>();
    Map<String, Integer> totalByLabel = new LinkedHashMap<>();
    List<Map<String, Object>> results = new java.util.ArrayList<>();

    for (Object item : cases) {
      if (!(item instanceof Map<?, ?> m)) continue;
      Object q = m.get("query");
      if (!(q instanceof String query) || query.isBlank()) continue;
      String label = m.get("label") instanceof String s && !s.isBlank() ? s : "unlabeled";
      Boolean expected = m.get("expectedHasContext") instanceof Boolean b ? b : null;
      total++;
      totalByLabel.put(label, totalByLabel.getOrDefault(label, 0) + 1);

      try {
        Optional<String> ctx = r.retrieve(query);
        boolean hasContext = ctx.isPresent() && !ctx.get().isBlank();
        if (hasContext) {
          hasContextCount++;
          hasContextByLabel.put(label, hasContextByLabel.getOrDefault(label, 0) + 1);
        }
        if (expected != null) {
          expectedComparisons++;
          if (expected == hasContext) expectedMatched++;
        }
        String preview = ctx.orElse("");
        if (preview.length() > 180) preview = preview.substring(0, 180);
        results.add(Map.of(
            "query", query,
            "label", label,
            "hasContext", hasContext,
            "contextChars", ctx.map(String::length).orElse(0),
            "contextPreview", preview
        ));
      } catch (Exception e) {
        results.add(Map.of(
            "query", query,
            "label", label,
            "error", e.getClass().getSimpleName() + ": " + e.getMessage()
        ));
      }
    }

    body.put("ok", true);
    body.put("totalCases", total);
    body.put("hasContextCount", hasContextCount);
    body.put("hasContextRate", total == 0 ? 0.0 : ((double) hasContextCount / (double) total));
    body.put("hasContextByLabel", hasContextByLabel);
    body.put("totalByLabel", totalByLabel);
    body.put("expectedComparisons", expectedComparisons);
    body.put("expectedMatched", expectedMatched);
    body.put("expectedMatchRate", expectedComparisons == 0 ? null : ((double) expectedMatched / (double) expectedComparisons));
    body.put("results", results);
    return ResponseEntity.ok(body);
  }
}
