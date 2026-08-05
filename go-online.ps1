# Builds the dashboard into the backend and starts one process serving both.
#
# Development runs two ports: Vite on 5173 for the interface, Spring Boot on 8000
# for the API. A free tunnel gives one. This builds the interface into the
# backend's static resources so a single port serves everything, and /api stops
# being a cross-origin call.
#
#   .\go-online.ps1 -PublicUrl https://your-name.ngrok-free.dev
#
# Then, in a second terminal:
#   ngrok http 8000 --url your-name.ngrok-free.dev

param(
    # The address people will actually type. Emailed confirmation and invitation
    # links are built from it — leave it as localhost and every link you send is
    # dead on any machine but this one.
    [Parameter(Mandatory = $true)]
    [string]$PublicUrl,

    # Skips the frontend build when only the backend changed.
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $SkipBuild) {
    Write-Host "Building the dashboard..." -ForegroundColor Cyan
    Push-Location "$root\frontend"
    npm run build
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Frontend build failed" }
    Pop-Location
}

# A signing key that survives restarts. Without one the application generates a
# throwaway at startup, so every session ends whenever the process does — and the
# key is printed in the log, where anyone reading it can forge a token.
$keyFile = "$root\backend\config\jwt-secret.txt"
if (-not (Test-Path $keyFile)) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes) | Out-File -FilePath $keyFile -Encoding ascii -NoNewline
    Write-Host "Generated a signing key at backend\config\jwt-secret.txt" -ForegroundColor Yellow
}
$env:JWT_SECRET = (Get-Content $keyFile -Raw).Trim()

$env:PUBLIC_URL = $PublicUrl.TrimEnd('/')

# Same origin now, so the browser never makes a cross-origin call. Listing the
# public address anyway costs nothing and keeps the development ports working.
$env:APP_CORS_ORIGINS = "$($env:PUBLIC_URL),http://localhost:5173"

# Behind the tunnel every request arrives from it, so X-Forwarded-For is the only
# way to tell visitors apart. Without this one person's failed sign-ins exhaust
# the rate limit for everybody.
$env:APP_TRUST_PROXY_HEADERS = "true"

Write-Host ""
Write-Host "Public URL : $env:PUBLIC_URL" -ForegroundColor Green
Write-Host "Serving    : http://localhost:8000  (interface and API)" -ForegroundColor Green
Write-Host ""
Write-Host "In another terminal, expose it:" -ForegroundColor Cyan
$hostName = ([Uri]$env:PUBLIC_URL).Host
Write-Host "  ngrok http 8000 --url $hostName"
Write-Host ""

Push-Location "$root\backend"
try {
    .\mvnw.cmd spring-boot:run
} finally {
    Pop-Location
}
