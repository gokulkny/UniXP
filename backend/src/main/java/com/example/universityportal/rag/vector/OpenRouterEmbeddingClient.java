package com.example.universityportal.rag.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterEmbeddingClient implements EmbeddingClient {
  private static final Logger log = LoggerFactory.getLogger(OpenRouterEmbeddingClient.class);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build();

  @Value("${portal.embedding.base-url:https://openrouter.ai/api/v1}")
  private String baseUrl;

  @Value("${portal.embedding.model:openai/text-embedding-3-small}")
  private String model;

  @Value("${portal.embedding.dimension:1536}")
  private int expectedDimension;

  @Value("${portal.embedding.timeout-seconds:60}")
  private int timeoutSeconds;

  @Override
  public float[] embed(String text) throws IOException, InterruptedException {
    List<float[]> batch = embedBatch(List.of(text));
    return batch.get(0);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) throws IOException, InterruptedException {
    if (texts == null || texts.isEmpty()) return List.of();

    List<float[]> parsed = postEmbeddingsRequest(texts);
    if (parsed != null) {
      return validateDimensions(parsed);
    }

    log.warn("Batch embedding returned unexpected shape; retrying one string at a time ({} items)", texts.size());
    List<float[]> out = new ArrayList<>(texts.size());
    for (String t : texts) {
      List<float[]> one = postEmbeddingsRequest(List.of(t));
      if (one == null) {
        throw new IOException("Single-item embedding failed after batch shape mismatch");
      }
      out.addAll(validateDimensions(one));
    }
    return out;
  }

  /**
   * POST /embeddings. Returns null if response "data" array size does not match (caller may retry).
   */
  private List<float[]> postEmbeddingsRequest(List<String> texts) throws IOException, InterruptedException {
    String apiKey = System.getenv("PORTAL_OPENROUTER_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IOException("PORTAL_OPENROUTER_API_KEY is not set");
    }

    String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (!url.endsWith("/embeddings")) {
      url = url + "/embeddings";
    }

    Map<String, Object> body = new HashMap<>();
    body.put("model", model);
    body.put("input", texts);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("Embedding API failed status={}, body={}", response.statusCode(), truncate(response.body(), 500));
      throw new IOException("Embedding request failed with status " + response.statusCode());
    }

    JsonNode root = objectMapper.readTree(response.body());
    return parseEmbeddingDataArray(root.get("data"), texts.size());
  }

  /** OpenAI-style API: data[] entries include "index"; sort by index when present. */
  private List<float[]> parseEmbeddingDataArray(JsonNode data, int expectedCount) throws IOException {
    if (data == null || !data.isArray() || data.size() != expectedCount) {
      return null;
    }
    List<JsonNode> items = new ArrayList<>();
    for (int i = 0; i < data.size(); i++) {
      items.add(data.get(i));
    }
    boolean allHaveIndex = items.stream().allMatch(n -> n.has("index"));
    if (allHaveIndex) {
      items.sort(Comparator.comparingInt(n -> n.get("index").asInt()));
    }

    List<float[]> out = new ArrayList<>(expectedCount);
    for (JsonNode item : items) {
      JsonNode emb = item.get("embedding");
      if (emb == null || !emb.isArray()) {
        throw new IOException("Missing embedding in data item");
      }
      float[] vec = new float[emb.size()];
      for (int j = 0; j < emb.size(); j++) {
        vec[j] = (float) emb.get(j).asDouble();
      }
      out.add(vec);
    }
    return out;
  }

  private List<float[]> validateDimensions(List<float[]> vectors) throws IOException {
    List<float[]> out = new ArrayList<>(vectors.size());
    for (float[] vec : vectors) {
      if (expectedDimension > 0 && vec.length != expectedDimension) {
        throw new IOException(
            "Embedding dimension mismatch: expected " + expectedDimension + " for column vector(" + expectedDimension + "), got " + vec.length
                + ". Set portal.embedding.model / portal.embedding.dimension or add a Flyway migration for the new size."
        );
      }
      out.add(vec);
    }
    return out;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    return s.substring(0, max) + "…";
  }
}
