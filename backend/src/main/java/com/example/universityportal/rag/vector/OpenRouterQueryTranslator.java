package com.example.universityportal.rag.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterQueryTranslator {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

  public String translateToPortuguese(String query) throws IOException, InterruptedException {
    String apiKey = System.getenv("PORTAL_OPENROUTER_API_KEY");
    if (apiKey == null || apiKey.isBlank() || query == null || query.isBlank()) return query;
    String model = env("PORTAL_RAG_TRANSLATION_MODEL", "openai/gpt-4o-mini");
    int timeoutSec = envInt("PORTAL_RAG_TRANSLATION_TIMEOUT_SECONDS", 12);
    Map<String, Object> payload = Map.of(
        "model", model,
        "temperature", 0.0,
        "max_tokens", 128,
        "messages", List.of(
            Map.of("role", "system", "content", "Translate the user query to Portuguese. Return only the translated query."),
            Map.of("role", "user", "content", query)
        )
    );
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
        .timeout(Duration.ofSeconds(Math.max(5, timeoutSec)))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() < 200 || resp.statusCode() >= 300) return query;
    JsonNode root = objectMapper.readTree(resp.body());
    JsonNode content = root.path("choices").path(0).path("message").path("content");
    String translated = content.isMissingNode() ? "" : content.asText("").trim();
    return translated.isBlank() ? query : translated;
  }

  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  private static int envInt(String key, int fallback) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) return fallback;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
