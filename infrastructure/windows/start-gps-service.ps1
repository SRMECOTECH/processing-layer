# Starts the Spring Boot GPS streaming service on http://localhost:8080.
# Build first:  .\mvnw.cmd -DskipTests package
#
#   .\start-gps-service.ps1                         Kafka flow (Kafka and PostgreSQL must be running)
#   .\start-gps-service.ps1 -ProcessingFlow direct  Direct flow (only PostgreSQL must be running)

param(
    [ValidateSet('kafka', 'direct')]
    [string] $ProcessingFlow = 'kafka'
)

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

$serviceJar = Join-Path $ProjectRoot 'gps-application\target\gps-streaming-service.jar'
if (-not (Test-Path $serviceJar)) { throw "Service jar not found. Build it first: .\mvnw.cmd -DskipTests package" }

New-Item -ItemType Directory -Force $JavaSocketTempDirectory | Out-Null
Write-Host "Starting the GPS service with the '$ProcessingFlow' processing flow"
# Run from the project root so the service finds local-secrets.properties.
Push-Location $ProjectRoot
try {
    & java "-Djdk.net.unixdomain.tmpdir=$JavaSocketTempDirectory" -jar $serviceJar "--gps.processing-flow=$ProcessingFlow"
} finally {
    Pop-Location
}
