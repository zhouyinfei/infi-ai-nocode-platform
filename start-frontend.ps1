$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot 'infi-ai-nocode-frontend')
if (-not (Test-Path 'node_modules')) { & npm ci --ignore-scripts; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
& npm run dev
exit $LASTEXITCODE
