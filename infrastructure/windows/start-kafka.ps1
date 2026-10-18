# Starts the local Kafka broker on localhost:9092 in its own console window.
# Close that window (or run stop-kafka.ps1) to stop it.

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

if (-not (Test-Path $KafkaServerProperties)) { throw "Kafka is not set up yet. Run setup-kafka.ps1 first." }

# Windows 11 no longer ships "wmic", which Kafka's script uses only to pick a heap size - set it ourselves.
$env:KAFKA_HEAP_OPTS = '-Xmx1G -Xms1G'
# On Windows, Java's internal pipes use a socket file in the temp folder; some temp folders refuse it
# ("SocketException: Invalid argument: connect"). A short, plain folder always works.
New-Item -ItemType Directory -Force $JavaSocketTempDirectory | Out-Null
$env:KAFKA_OPTS = "-Djdk.net.unixdomain.tmpdir=$JavaSocketTempDirectory"

Start-Process -FilePath "$KafkaHome\bin\windows\kafka-server-start.bat" -ArgumentList "`"$KafkaServerProperties`"" -WorkingDirectory $KafkaHome
Write-Host "Kafka is starting on localhost:9092 (watch its console window)."
