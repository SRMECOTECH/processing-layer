# ONE-TIME: creates the local PostgreSQL 14 server and the new gps_tracking database.
#   1. initdb   - creates the data folder; the "postgres" superuser gets a random password
#   2. creates the application login "gps_app" and the database "gps_tracking" owned by it
#   3. saves the gps_app password into local-secrets.properties (git-ignored) for the Spring service
# Tables are NOT created here - the Spring service creates them with Flyway migrations on start-up.

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$PostgresHome\bin\initdb.exe")) {
    throw "PostgreSQL binaries not found in $PostgresHome."
}
if (Test-Path "$PostgresDataDirectory\PG_VERSION") {
    Write-Host "PostgreSQL is already set up in $PostgresDataDirectory - nothing to do."
    return
}

New-Item -ItemType Directory -Force (Split-Path $PostgresDataDirectory -Parent) | Out-Null

# 1. Create the server data folder. The superuser password goes to a temporary file only for initdb.
$superuserPassword = New-RandomPassword
$passwordFile = New-TemporaryFile
Set-Content -Path $passwordFile -Value $superuserPassword -Encoding ascii -NoNewline
& "$PostgresHome\bin\initdb.exe" -D $PostgresDataDirectory -U postgres --pwfile=$passwordFile --auth=scram-sha-256 --encoding=UTF8 --locale=C
Remove-Item $passwordFile
if ($LASTEXITCODE -ne 0) { throw "initdb failed." }
Write-LocalSecret 'local.postgres.superuser.password' $superuserPassword

# 2. Create the application login and database. Single-user mode needs no running server and no psql.
$applicationPassword = New-RandomPassword
$bootstrapSql = @"
CREATE ROLE gps_app LOGIN PASSWORD '$applicationPassword';
CREATE DATABASE gps_tracking OWNER gps_app ENCODING 'UTF8';
"@
$sqlFile = New-TemporaryFile
Set-Content -Path $sqlFile -Value $bootstrapSql -Encoding ascii   # ascii: PostgreSQL must not receive a byte-order mark
$singleUserOutput = cmd /c "`"$PostgresHome\bin\postgres.exe`" --single -D `"$PostgresDataDirectory`" postgres < `"$sqlFile`" 2>&1"
Remove-Item $sqlFile
if ($singleUserOutput -match 'ERROR:') { throw "Creating the gps_app login and gps_tracking database failed. Check the output above." }

# 3. Hand the application password to the Spring service.
Write-LocalSecret 'spring.datasource.password' $applicationPassword

Write-Host "PostgreSQL is set up. Database gps_tracking, login gps_app (password saved in $LocalSecretsFile)."
Write-Host "Start it with start-postgresql.ps1"
