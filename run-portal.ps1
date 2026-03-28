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

