package com.example.universityportal.rag.vector;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Profile("vector")
public class PgVectorStore implements VectorStore {
  private final JdbcTemplate jdbcTemplate;

  public PgVectorStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void deleteBySourceFileId(String sourceFileId) {
    jdbcTemplate.update("DELETE FROM rag_chunk WHERE source_file_id = ?", sourceFileId);
  }

  @Override
  public void upsert(List<VectorChunkRecord> chunks) {
    if (chunks == null || chunks.isEmpty()) return;
    // Use plain ? for PGobject(type=vector); ::vector cast in SQL can break JDBC binding.
    String sql = """
        INSERT INTO rag_chunk (id, source_file_id, source_file_name, chunk_index, content, embedding)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (source_file_id, chunk_index)
        DO UPDATE SET
          source_file_name = EXCLUDED.source_file_name,
          content = EXCLUDED.content,
          embedding = EXCLUDED.embedding
        """;
    for (VectorChunkRecord c : chunks) {
      try {
        PGobject vec = toPgVector(c.embedding());
        jdbcTemplate.update(sql,
            c.id(),
            c.sourceFileId(),
            c.sourceFileName(),
            c.chunkIndex(),
            c.content(),
            vec
        );
      } catch (SQLException e) {
        throw new IllegalStateException("Failed to upsert chunk", e);
      }
    }
  }

  @Override
  public List<VectorSearchResult> search(float[] queryEmbedding, int topK) {
    if (topK < 1) topK = 5;
    try {
      PGobject queryVec = toPgVector(queryEmbedding);
      String sql = """
          SELECT id, source_file_id, source_file_name, content,
                 (embedding <=> ?::vector) AS distance
          FROM rag_chunk
          ORDER BY embedding <=> ?::vector ASC
          LIMIT ?
          """;
      return jdbcTemplate.query(sql, (rs, rowNum) -> {
        double distance = rs.getDouble("distance");
        return new VectorSearchResult(
            rs.getObject("id", UUID.class),
            rs.getString("source_file_id"),
            rs.getString("source_file_name"),
            rs.getString("content"),
            -distance
        );
      }, queryVec, queryVec, topK);
    } catch (SQLException e) {
      throw new IllegalStateException("Vector search failed", e);
    }
  }

  private static PGobject toPgVector(float[] embedding) throws SQLException {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(embedding[i]);
    }
    sb.append(']');
    PGobject pg = new PGobject();
    pg.setType("vector");
    pg.setValue(sb.toString());
    return pg;
  }
}
