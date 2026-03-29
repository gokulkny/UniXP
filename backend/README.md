# University portal backend

## Vector RAG (local Postgres + pgvector)

1. Start local database:

```bash
docker compose up -d
```

2. **Activate the `vector` Spring profile** and start Postgres before the app (see [`application-vector.properties`](src/main/resources/application-vector.properties)):

   - `mvn spring-boot:run -Dspring-boot.run.profiles=vector`
   - or set `SPRING_PROFILES_ACTIVE=vector`

   The default profile is **`legacy`**, which keeps JDBC auto-configuration **off** so the app runs without Postgres. The `vector` profile turns on the DataSource + Flyway + pgvector indexing.

**Important:** Vector beans (`PgVectorStore`, `VectorRagIndexService`, `VectorRagRetriever`) use **`@Profile("vector")`** so they register **after** datasource config is applied. Using only `@ConditionalOnBean(DataSource)` on `@Component` classes can fail because the `DataSource` bean is created by auto-configuration **after** component scanning.

   Optional: copy [`application-local-vector.properties.example`](src/main/resources/application-local-vector.properties.example) for custom URLs. Use `portal.rag.retrieval-mode=hybrid` in that file if you want keyword reranking.

3. Required environment variables:

- `PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON` — service account JSON with Drive read access
- `PORTAL_OPENROUTER_API_KEY` — used for **chat** and **embeddings** (`/v1/embeddings`)

4. On startup, with `vector` or `hybrid`, the app runs Flyway migrations, then indexes configured Drive PDFs (`portal.drive.fileIds`) into table `rag_chunk` with embeddings (`portal.embedding.model`, default `openai/text-embedding-3-small`, dimension **1536** — must match the Flyway `vector(1536)` column).

### Retrieval modes

| `portal.rag.retrieval-mode` | Behavior |
| --- | --- |
| `legacy` | In-memory TF‑IDF only (no DB required) |
| `vector` | pgvector similarity search + OpenRouter embeddings |
| `vector` + keyword rerank on top candidates | `hybrid` |

### AWS migration

- **Database:** Enable the `pgvector` extension on **Amazon RDS for PostgreSQL** or **Aurora PostgreSQL**. Point `spring.datasource.url` (and credentials or IAM auth) at that instance; no application code change.
- **Embeddings:** Add a second `EmbeddingClient` implementation (e.g. Amazon Bedrock Titan) and select it via configuration when you are ready; keep the same `vector` dimension as the DB column or run a re-index migration after changing models.

### Tests

`PgVectorStoreIT` uses Testcontainers (`pgvector/pgvector:pg16`). It runs when Docker is available; otherwise it is **skipped** so `mvn test` still passes.

### Debugging empty `rag_chunk`

1. With the app running (profile `vector`), open **http://localhost:8080/api/rag/status** — check `ragChunkCount`, `vectorRagIndexServicePresent`, and env flags.
2. To retry indexing without restarting: **POST** **http://localhost:8080/api/rag/reindex** (same env vars as startup).
3. Check logs for **rag_chunk row count** before/after indexing and any **Vector RAG indexing failed** stack trace.
