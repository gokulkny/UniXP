package com.example.universityportal.rag.vector;

import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
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
  /** Active table for upsert/search; matches {@code portal.embedding.dimension}. */
  private final String chunksTable;

  public PgVectorStore(
      JdbcTemplate jdbcTemplate,
      @Value("${portal.embedding.dimension:1536}") int embeddingDimension
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.chunksTable = VectorTableNames.chunksTable(embeddingDimension);
  }

  @Override
  public void deleteBySourceFileId(String sourceFileId) {
    jdbcTemplate.update("DELETE FROM " + VectorTableNames.TABLE_1536 + " WHERE source_file_id = ?", sourceFileId);
    jdbcTemplate.update("DELETE FROM " + VectorTableNames.TABLE_4096 + " WHERE source_file_id = ?", sourceFileId);
  }

  @Override
  public void upsert(List<VectorChunkRecord> chunks) {
    if (chunks == null || chunks.isEmpty()) return;
    // Use plain ? for PGobject(type=vector); ::vector cast in SQL can break JDBC binding.
    String sql = """
        INSERT INTO %s (id, source_file_id, source_file_name, doc_id, chunk_index, section, topic, content, embedding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (source_file_id, chunk_index)
        DO UPDATE SET
          source_file_name = EXCLUDED.source_file_name,
          doc_id = EXCLUDED.doc_id,
          section = EXCLUDED.section,
          topic = EXCLUDED.topic,
          content = EXCLUDED.content,
          embedding = EXCLUDED.embedding
        """.formatted(chunksTable);
    for (VectorChunkRecord c : chunks) {
      try {
        PGobject vec = toPgVector(c.embedding());
        jdbcTemplate.update(sql,
            c.id(),
            c.sourceFileId(),
            c.sourceFileName(),
            c.docId(),
            c.chunkIndex(),
            c.section(),
            c.topic(),
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
    return search(queryEmbedding, topK, ChunkSearchConstraints.none());
  }

  @Override
  public List<VectorSearchResult> search(float[] queryEmbedding, int topK, ChunkSearchConstraints constraints) {
    if (topK < 1) topK = 5;
    if (constraints == null) constraints = ChunkSearchConstraints.none();
    try {
      PGobject queryVec = toPgVector(queryEmbedding);
      StringBuilder sql = new StringBuilder();
      sql.append("""
          SELECT id, source_file_id, source_file_name, doc_id, section, topic, content,
                 (embedding <=> ?::vector) AS distance
          FROM %s
          """.formatted(chunksTable));
      List<Object> args = new ArrayList<>();
      args.add(queryVec);
      appendConstraintWhere(sql, args, constraints);
      sql.append(" ORDER BY embedding <=> ?::vector ASC LIMIT ? ");
      args.add(queryVec);
      args.add(topK);

      return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
        double distance = rs.getDouble("distance");
        double similarity = 1.0 - distance;
        return new VectorSearchResult(
            rs.getObject("id", UUID.class),
            rs.getString("source_file_id"),
            rs.getString("source_file_name"),
            rs.getString("doc_id"),
            rs.getString("section"),
            rs.getString("topic"),
            rs.getString("content"),
            similarity
        );
      }, args.toArray());
    } catch (SQLException e) {
      throw new IllegalStateException("Vector search failed", e);
    }
  }

  private static void appendConstraintWhere(StringBuilder sql, List<Object> args, ChunkSearchConstraints c) {
    if (!c.hasSqlFilters()) {
      sql.append(" WHERE 1=1 ");
      return;
    }
    sql.append(" WHERE 1=1 ");
    if (c.docIdPattern() != null && !c.docIdPattern().isBlank()) {
      sql.append(" AND doc_id ILIKE ? ");
      args.add(c.docIdPattern());
    }
    if (c.excludeMajorSection4()) {
      sql.append(" AND (section IS NULL OR trim(section) = '' OR section NOT LIKE '4.%') ");
    }
    if (c.preferInitialSubmissionDocuments() && c.preferredSectionPrefix() != null && !c.preferredSectionPrefix().isBlank()) {
      String p = c.preferredSectionPrefix().trim();
      boolean phd = p.startsWith("3.5.2");
      sql.append(" AND ( ");
      sql.append(" section LIKE ? OR section = ? ");
      args.add(p + "%");
      args.add(p);
      if (phd) {
        sql.append("""
             OR (LOWER(COALESCE(topic,'')) LIKE '%documento%' AND LOWER(COALESCE(topic,'')) LIKE '%doutorado%')
             OR (LOWER(content) LIKE ? AND (LOWER(content) LIKE '%documento%' OR LOWER(content) LIKE '%documentos%'))
            """);
      } else {
        sql.append("""
             OR (LOWER(COALESCE(topic,'')) LIKE '%documento%' AND LOWER(COALESCE(topic,'')) LIKE '%mestrado%')
             OR (LOWER(content) LIKE ? AND (LOWER(content) LIKE '%documento%' OR LOWER(content) LIKE '%documentos%'))
            """);
      }
      args.add("%" + p + "%");
      sql.append(") ");
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

    String queryText = String.join(" ", clean);
    List<Object> args = new ArrayList<>();
    args.add(queryText);
    args.add(queryText);
    args.add(limit);

    String sql = """
        SELECT id, source_file_id, source_file_name, doc_id, section, topic, content,
               ts_rank_cd(content_tsv, websearch_to_tsquery('portuguese', ?)) AS lex_score
        FROM %s
        WHERE content_tsv @@ websearch_to_tsquery('portuguese', ?)
        ORDER BY lex_score DESC, chunk_index ASC
        LIMIT ?
        """.formatted(chunksTable);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("doc_id"),
        rs.getString("section"),
        rs.getString("topic"),
        rs.getString("content"),
        rs.getDouble("lex_score")
    ), args.toArray());
  }

  @Override
  public List<VectorSearchResult> searchByLexicalTokensSimple(List<String> tokens, int limit) {
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

    StringBuilder where = new StringBuilder(" WHERE (");
    List<Object> args = new ArrayList<>();
    int i = 0;
    for (String token : clean) {
      if (i > 0) where.append(" OR ");
      where.append("LOWER(content) LIKE ?");
      args.add("%" + token + "%");
      i++;
    }
    where.append(")");

    String sql = """
        SELECT id, source_file_id, source_file_name, doc_id, section, topic, content, chunk_index
        FROM %s
        %s
        ORDER BY chunk_index ASC
        LIMIT ?
        """.formatted(chunksTable, where);

    args.add(limit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("doc_id"),
        rs.getString("section"),
        rs.getString("topic"),
        rs.getString("content"),
        0.08
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
        SELECT id, source_file_id, source_file_name, doc_id, section, topic, content, chunk_index
        FROM (
          SELECT id, source_file_id, source_file_name, doc_id, section, topic, content, chunk_index,
                 ROW_NUMBER() OVER (PARTITION BY source_file_id ORDER BY chunk_index ASC) AS rn
          FROM %s
          WHERE source_file_id IN (%s)
        ) t
        WHERE rn <= ?
        ORDER BY source_file_id ASC, chunk_index ASC
        """.formatted(chunksTable, placeholders);

    List<Object> args = new ArrayList<>(clean.size() + 1);
    args.addAll(clean);
    args.add(perDocLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("doc_id"),
        rs.getString("section"),
        rs.getString("topic"),
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

    String queryText = String.join(" ", clean);
    List<Object> args = new ArrayList<>();
    args.add(queryText);
    args.add(limit);

    String sql = """
        SELECT id, source_file_id, source_file_name, doc_id, section, topic, content, chunk_index
        FROM %s
        WHERE content_tsv @@ plainto_tsquery('portuguese', ?)
        ORDER BY chunk_index ASC
        LIMIT ?
        """.formatted(chunksTable);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
        rs.getObject("id", UUID.class),
        rs.getString("source_file_id"),
        rs.getString("source_file_name"),
        rs.getString("doc_id"),
        rs.getString("section"),
        rs.getString("topic"),
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
