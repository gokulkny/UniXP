package com.example.universityportal.rag.vector;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

  @Override
  public List<VectorSearchResult> searchByLexicalTokens(List<String> tokens, int limit) {
    if (tokens == null || tokens.isEmpty()) return List.of();
    if (limit < 1) limit = 10;

    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String t : tokens) {
      if (t == null) continue;
      String s = t.trim().toLowerCase();
      if (s.isBlank()) continue;
      if (s.length() == 1) continue;
      clean.add(s);
      if (clean.size() >= 8) break;
    }
    if (clean.isEmpty()) return List.of();

    StringBuilder scoreExpr = new StringBuilder();
    List<Object> args = new ArrayList<>();
    int i = 0;
    for (String token : clean) {
      if (i > 0) scoreExpr.append(" + ");
      scoreExpr.append("CASE WHEN LOWER(content) LIKE ? OR LOWER(source_file_name) LIKE ? THEN 1 ELSE 0 END");
      args.add("%" + token + "%");
      args.add("%" + token + "%");
      i++;
    }

    String sql = """
        SELECT id, source_file_id, source_file_name, content, (%s) AS lex_score
        FROM rag_chunk
        ORDER BY lex_score DESC, chunk_index ASC
        LIMIT ?
        """.formatted(scoreExpr);
    args.add(limit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("content"),
        rs.getDouble("lex_score")
    ), args.toArray());
  }

  @Override
  public List<VectorSearchResult> fetchLeadingChunksBySource(List<String> sourceFileIds, int perDocLimit) {
    if (sourceFileIds == null || sourceFileIds.isEmpty()) return List.of();
    if (perDocLimit < 1) perDocLimit = 2;

    List<String> clean = new ArrayList<>();
    for (String id : sourceFileIds) {
      if (id == null) continue;
      String s = id.trim();
      if (!s.isBlank()) clean.add(s);
      if (clean.size() >= 10) break;
    }
    if (clean.isEmpty()) return List.of();

    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < clean.size(); i++) {
      if (i > 0) placeholders.append(",");
      placeholders.append("?");
    }
    String sql = """
        SELECT id, source_file_id, source_file_name, content, chunk_index
        FROM (
          SELECT id, source_file_id, source_file_name, content, chunk_index,
                 ROW_NUMBER() OVER (PARTITION BY source_file_id ORDER BY chunk_index ASC) AS rn
          FROM rag_chunk
          WHERE source_file_id IN (%s)
        ) t
        WHERE rn <= ?
        ORDER BY source_file_id ASC, chunk_index ASC
        """.formatted(placeholders);

    List<Object> args = new ArrayList<>(clean.size() + 1);
    args.addAll(clean);
    args.add(perDocLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("content"),
        // Mild prior; reranker will order final list.
        0.05
    ), args.toArray());
  }

  @Override
  public List<VectorSearchResult> searchByRequiredTokens(List<String> requiredTokens, int limit) {
    if (requiredTokens == null || requiredTokens.isEmpty()) return List.of();
    if (limit < 1) limit = 20;

    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String t : requiredTokens) {
      if (t == null) continue;
      String s = t.trim().toLowerCase();
      if (s.length() < 2) continue;
      clean.add(s);
      if (clean.size() >= 5) break;
    }
    if (clean.isEmpty()) return List.of();

    StringBuilder where = new StringBuilder(" WHERE ");
    List<Object> args = new ArrayList<>();
    int i = 0;
    for (String token : clean) {
      if (i > 0) where.append(" AND ");
      where.append("(LOWER(content) LIKE ? OR LOWER(source_file_name) LIKE ?)");
      String like = "%" + token + "%";
      args.add(like);
      args.add(like);
      i++;
    }

    String sql = """
        SELECT id, source_file_id, source_file_name, content, chunk_index
        FROM rag_chunk
        %s
        ORDER BY chunk_index ASC
        LIMIT ?
        """.formatted(where);
    args.add(limit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("content"),
        10.0
    ), args.toArray());
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
