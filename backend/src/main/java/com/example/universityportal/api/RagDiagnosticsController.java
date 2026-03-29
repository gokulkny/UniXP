package com.example.universityportal.api;

import com.example.universityportal.rag.vector.RagRetrievalProperties;
import com.example.universityportal.rag.vector.VectorRagIndexService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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

  public RagDiagnosticsController(
      Environment environment,
      RagRetrievalProperties ragProperties,
      ObjectProvider<JdbcTemplate> jdbcTemplate,
      ObjectProvider<VectorRagIndexService> indexService
  ) {
    this.environment = environment;
    this.ragProperties = ragProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.indexService = indexService;
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
}
