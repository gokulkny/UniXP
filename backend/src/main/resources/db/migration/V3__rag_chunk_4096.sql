-- 4096-d embeddings (e.g. qwen/qwen3-embedding-8b). Used when portal.embedding.dimension=4096.
CREATE TABLE rag_chunk_4096 (
  id UUID PRIMARY KEY,
  source_file_id TEXT NOT NULL,
  source_file_name TEXT NOT NULL,
  doc_id TEXT NOT NULL DEFAULT 'UNKNOWN_DOC',
  chunk_index INT NOT NULL,
  section TEXT,
  topic TEXT,
  content TEXT NOT NULL,
  embedding vector(4096) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('portuguese', coalesce(content, ''))) STORED,
  CONSTRAINT rag_chunk_4096_source_chunk_unique UNIQUE (source_file_id, chunk_index)
);

-- pgvector HNSW (and typical ANN indexes) are limited to 2000 dimensions in many builds.
-- 4096-d vectors cannot use HNSW here; vector search uses exact distance (sequential scan).
-- For large corpora, consider a ≤2000-d embedding model (rag_chunk + HNSW) or a newer pgvector build.
CREATE INDEX rag_chunk_4096_content_tsv_idx ON rag_chunk_4096 USING GIN (content_tsv);
CREATE INDEX rag_chunk_4096_doc_id_idx ON rag_chunk_4096 (doc_id);
