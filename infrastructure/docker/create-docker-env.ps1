# Writes the git-ignored .env file that docker compose reads, from local-secrets.properties.
# Run once before the first "docker compose up" (and again if a password changes).
#
#   .\infrastructure\docker\create-docker-env.ps1

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$secretsFile = Join-Path $projectRoot 'local-secrets.properties'
$envFile = Join-Path $projectRoot '.env'

if (-not (Test-Path $secretsFile)) {
    throw "local-secrets.properties not found. Copy local-secrets.example.properties to local-secrets.properties and fill it in first."
}

function Read-Secret([string] $key) {
    $line = Get-Content $secretsFile | Where-Object { $_ -match "^\s*$([regex]::Escape($key))\s*=" } | Select-Object -First 1
    if (-not $line) { throw "Key '$key' is missing in local-secrets.properties" }
    return ($line -split '=', 2)[1].Trim()
}

$lines = @(
    '# Written by infrastructure/docker/create-docker-env.ps1 - git-ignored, never commit.',
    "GPS_DATABASE_PASSWORD=$(Read-Secret 'spring.datasource.password')",
    "SMART_TRUCK_DATABASE_USERNAME=$(Read-Secret 'smart-truck.database.username')",
    "SMART_TRUCK_DATABASE_PASSWORD=$(Read-Secret 'smart-truck.database.password')"
)
# No BOM: docker compose must read the first line correctly.
[System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Wrote $envFile (git-ignored)."
