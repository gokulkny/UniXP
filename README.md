# University Portal (Demo)

Small “University Portal” demo with a Java Spring Boot REST backend and a static HTML/CSS frontend powered by TypeScript (compiled to browser-ready JS).

## Run locally

1. Build the TypeScript frontend

   ```sh
   npm install
   npm run build
   ```

2. Start the Spring Boot backend

   ```sh
   mvn -f backend/pom.xml spring-boot:run
   ```

3. Open

   `http://localhost:8080/`

## Demo login

- Username: any non-empty value
- Password: `demo`

After login, the frontend stores a token in `localStorage` (key: `portal_token`) and sends it as:

`Authorization: Bearer <token>`

## Frontend pages

- `/` (`index.html`)
- `/admissions.html`
- `/courses.html`
- `/login.html`
- `/dashboard.html` (protected)
- `/contact.html`

## Backend API

- `POST /api/login`
  - Body: `{ "username": "...", "password": "..." }`
  - Response: `{ "token": "...", "name": "..." }`
- `GET /api/me` (requires `Authorization: Bearer ...`)
- `GET /api/courses` (public)
- `POST /api/chat` (protected)
  - Headers: `Authorization: Bearer <token>`
  - Body: `{ "message": "..." }`
  - Response: `{ "reply": "...", "error": null }`

## Chatbot setup (AI + misuse prevention)

The chatbot is available on `dashboard.html` after login (floating button).

To enable real AI responses, set the following environment variables when starting the backend:

- `PORTAL_OPENROUTER_API_KEY` (required)
- `PORTAL_OPENROUTER_MODEL` (default: `openai/gpt-4o-mini`)
- `PORTAL_AI_SYSTEM_PROMPT` (default: university assistant behavior + refusal)

Moderation / misuse prevention (server-side):

- `PORTAL_POLICY_LEVEL` (default: `1`)
  - `0`: strict only for highest-risk categories
  - `1`: stricter across broad categories
  - `2`: refuse on any flagged category (demo approximation)
- `PORTAL_CHAT_BLOCK_MESSAGE` (default: `Sorry, I can’t help with that request.`)
- `PORTAL_CHAT_MEMORY_TURNS` (default: `10`)
- `PORTAL_CHAT_LOG_FULL` (default: `true`)
  - When `true`, the backend console logs the user message + full AI reply.
  - When `false`, it logs truncated content only.
- `PORTAL_CHAT_LOG_MAX_CHARS` (default: `4000`)
  - Max characters used for truncation when `PORTAL_CHAT_LOG_FULL=false`.

Optional safety override:

- `PORTAL_BLOCK_REGEX` (optional)
  - If set, used as a regex to decide whether to block user input.
- `PORTAL_OPENROUTER_HTTP_REFERER` (optional)
- `PORTAL_OPENROUTER_TITLE` (optional)

If `PORTAL_OPENROUTER_API_KEY` is not set, the assistant will respond with a message indicating AI is not configured.

## Verify the chatbot

1. Open `http://localhost:8080/login.html` and login (password `demo`).
2. On `dashboard.html`, click the floating chatbot icon (bottom-right).
3. Send a normal question and confirm the assistant replies.
4. Send disallowed/unsafe requests and confirm you get a refusal message (based on rule-based safety filter).

## Google Drive documents (RAG)

The chatbot can optionally use PDF documents from Google Drive to ground responses.

### Configure documents

1. Edit `backend/src/main/resources/application.properties` and set:
   - `portal.drive.fileIds=FILE_ID_1,FILE_ID_2,...`

2. Set environment variable (service account JSON, as a single line string or quoted JSON):
   - `PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON`

### Tuning (optional)

- `PORTAL_RAG_TOP_K` (default `5`)
- `PORTAL_RAG_MAX_CONTEXT_CHARS` (default `6000`)
- `PORTAL_RAG_CHUNK_SIZE_CHARS` (default `1200`)
- `PORTAL_RAG_CHUNK_OVERLAP_CHARS` (default `150`)
- `PORTAL_RAG_STRICT` (default `false`)
  - When `true`, chatbot only answers if relevant PDF context is found.
- `PORTAL_RAG_NO_CONTEXT_MESSAGE` (optional)
  - Custom response when strict mode is on and no relevant doc chunk is found.

On backend startup, PDFs are downloaded, text is extracted, and an in-memory index is built.

