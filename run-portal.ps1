$ErrorActionPreference = "Stop"

# Run this script from the project root:
#   powershell -ExecutionPolicy Bypass -File .\run-portal.ps1

$env:PORTAL_OPENROUTER_API_KEY = "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"
$env:PORTAL_OPENROUTER_MODEL = "openai/gpt-4o-mini"
$env:PORTAL_RAG_STRICT = "false"

$serviceAccountPath = "C:\Users\gokul\Dummy projects\backend\src\main\resources\our-compound-435121-u7-87d06f1c1c69.json"
$json = Get-Content -Raw $serviceAccountPath
$env:PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON = $json

npm install
npm run build
mvn -f "backend\pom.xml" spring-boot:run



$env:PORTAL_OPENROUTER_API_KEY = "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"
$env:PORTAL_OPENROUTER_MODEL = "openai/gpt-4o-mini"
$env:PORTAL_RAG_STRICT = "false"
$serviceAccountPath = "C:\Users\gokul\Dummy projects\backend\src\main\resources\our-compound-435121-u7-87d06f1c1c69.json"
$json = Get-Content -Raw $serviceAccountPath
$env:PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON = $json
$env:SPRING_PROFILES_ACTIVE = "vector"
mvn spring-boot:run


npm install

npm run build

$env:PORTAL_OPENROUTER_TIMEOUT_SECONDS = "30"
$env:PORTAL_EMBEDDING_TIMEOUT_SECONDS = "180"
$env:PORTAL_EMBEDDING_MAX_RETRIES = "2"
$env:PORTAL_EMBEDDING_BATCH_SIZE = "2"

$env:PORTAL_OPENROUTER_API_KEY = "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"
$env:PORTAL_EMBEDDING_MODEL = "qwen/qwen3-embedding-8b"
$env:PORTAL_EMBEDDING_DIMENSION = "4096"
$env:PORTAL_RAG_STRICT = "false"

$serviceAccountPath = "C:\Users\gokul\Dummy projects\backend\src\main\resources\our-compound-435121-u7-87d06f1c1c69.json"
$env:PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON = Get-Content -Raw $serviceAccountPath

$env:SPRING_PROFILES_ACTIVE = "vector"

$env:PORTAL_EMBEDDING_RETRY_BACKOFF_MS = "800"

mvn spring-boot:run