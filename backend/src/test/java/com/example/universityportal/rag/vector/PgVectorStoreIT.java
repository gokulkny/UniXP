package com.example.universityportal.rag.vector;

import com.example.universityportal.PortalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PortalApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles({"test", "vector"})
class PgVectorStoreIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
      .withDatabaseName("portal")
      .withUsername("portal")
      .withPassword("portal");

  @DynamicPropertySource
  static void dataSourceProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("portal.embedding.dimension", () -> "1536");
  }

  @Autowired
  private PgVectorStore pgVectorStore;

  @Test
  void upsertAndSearch_returnsSimilarChunk() {
    float[] vec = new float[1536];
    for (int i = 0; i < vec.length; i++) {
      vec[i] = (i % 17) / 100f;
    }
    pgVectorStore.deleteBySourceFileId("test-file");
    pgVectorStore.upsert(List.of(
        new VectorChunkRecord(
            UUID.randomUUID(),
            "test-file",
            "test.pdf",
            "TEST_DOC",
            0,
            "3.5.2",
            "documentos doutorado",
            "APA style requires author and year in parenthetical citations.",
            vec
        )
    ));

    List<VectorSearchResult> hits = pgVectorStore.search(vec, 5);
    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).contains("APA");
  }
}
