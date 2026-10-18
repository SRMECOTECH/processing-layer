# ONE-TIME: prepares the local Kafka broker (KRaft mode - no ZooKeeper needed).
#   1. copies Kafka's sample config, pointing its data folder to $KafkaDataDirectory
#   2. formats that data folder with a new cluster id
# Safe to run again: it stops if the data folder is already formatted.

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$KafkaHome\bin\windows\kafka-server-start.bat")) {
    throw "Kafka not found in $KafkaHome. Download kafka_2.13-4.3.1.tgz from https://kafka.apache.org/downloads and extract it there."
}

if (Test-Path "$KafkaDataDirectory\meta.properties") {
    Write-Host "Kafka is already set up in $KafkaDataDirectory - nothing to do."
    return
}

New-Item -ItemType Directory -Force $KafkaDataDirectory | Out-Null
$kafkaDataDirectoryForwardSlashes = $KafkaDataDirectory -replace '\\', '/'
(Get-Content "$KafkaHome\config\server.properties") `
    -replace '^log\.dirs=.*$', "log.dirs=$kafkaDataDirectoryForwardSlashes" |
    Set-Content -Path $KafkaServerProperties -Encoding ascii

$clusterId = (& "$KafkaHome\bin\windows\kafka-storage.bat" random-uuid | Select-Object -Last 1).Trim()
& "$KafkaHome\bin\windows\kafka-storage.bat" format --standalone -t $clusterId -c $KafkaServerProperties
if ($LASTEXITCODE -ne 0) { throw "Formatting Kafka storage failed." }

Write-Host "Kafka is set up. Data folder: $KafkaDataDirectory. Start it with start-kafka.ps1"
