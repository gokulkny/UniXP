ALTER TABLE rag_chunk
  ADD COLUMN IF NOT EXISTS doc_id TEXT,
  ADD COLUMN IF NOT EXISTS section TEXT,
  ADD COLUMN IF NOT EXISTS topic TEXT;

UPDATE rag_chunk
SET doc_id = UPPER(REGEXP_REPLACE(REGEXP_REPLACE(source_file_name, '\\.pdf$', '', 'i'), '[^A-Za-z0-9]+', '_', 'g'))
WHERE doc_id IS NULL OR doc_id = '';

ALTER TABLE rag_chunk
  ALTER COLUMN doc_id SET DEFAULT 'UNKNOWN_DOC';

UPDATE rag_chunk
SET doc_id = 'UNKNOWN_DOC'
WHERE doc_id IS NULL OR doc_id = '';

ALTER TABLE rag_chunk
  ALTER COLUMN doc_id SET NOT NULL;

ALTER TABLE rag_chunk
  ADD COLUMN IF NOT EXISTS content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('portuguese', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS rag_chunk_content_tsv_idx
  ON rag_chunk USING GIN (content_tsv);

CREATE INDEX IF NOT EXISTS rag_chunk_doc_id_idx
  ON rag_chunk (doc_id);
