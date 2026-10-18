# Starts the local PostgreSQL server on localhost:5432 (runs in the background, log in $PostgresLogFile).

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$PostgresDataDirectory\PG_VERSION")) { throw "PostgreSQL is not set up yet. Run setup-postgresql.ps1 first." }

& "$PostgresHome\bin\pg_ctl.exe" -D $PostgresDataDirectory -l $PostgresLogFile -w start
