$env:PORTAL_OPENROUTER_API_KEY="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"
$env:PORTAL_OPENROUTER_MODEL="openai/gpt-4o-mini"
$env:PORTAL_USE_MODERATION="true"
$json = Get-Content -Raw "C:\Users\gokul\Dummy projects\backend\src\main\resources\our-compound-435121-u7-87d06f1c1c69.json"
$env:PORTAL_GOOGLE_SERVICE_ACCOUNT_JSON = $json
npm install
npm run build
mvn -f "backend\pom.xml" spring-boot:run