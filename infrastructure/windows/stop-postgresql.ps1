# Stops the local PostgreSQL server.

. "$PSScriptRoot\local-infrastructure-paths.ps1"

& "$PostgresHome\bin\pg_ctl.exe" -D $PostgresDataDirectory -m fast -w stop
