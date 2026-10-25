$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../frontend')
$node = 'C:/Program Files/nodejs/node.exe'
if (!(Test-Path $node)) { throw 'Node.js not found. Update its path in scripts/start-frontend.ps1.' }
if (!(Test-Path 'node_modules/vite/bin/vite.js')) { throw 'Run npm install in frontend first.' }
$env:API_TARGET = 'http://127.0.0.1:8080'
Write-Host 'Starting frontend: http://127.0.0.1:5173'
& $node 'node_modules/vite/bin/vite.js' '--host' '127.0.0.1' '--port' '5173' '--strictPort'
exit $LASTEXITCODE
