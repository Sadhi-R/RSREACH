# Starts the Vite dev server. Proxies /api to http://localhost:8080 (see vite.config.ts).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\frontend\apps\web")
if (-not (Test-Path "node_modules")) {
    npm install
}
npm run dev
