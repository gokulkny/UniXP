-- Local dev: PostgreSQL with pgvector (Docker pgvector/pgvector or RDS/Aurora with extension).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_chunk (
  id UUID PRIMARY KEY,
  source_file_id TEXT NOT NULL,
  source_file_name TEXT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  embedding vector(1536) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT rag_chunk_source_chunk_unique UNIQUE (source_file_id, chunk_index)
);

CREATE INDEX rag_chunk_embedding_hnsw ON rag_chunk USING hnsw (embedding vector_cosine_ops);
