# ONE-TIME: prepares the local Kafka broker (KRaft mode - no ZooKeeper needed).
#   1. copies Kafka's sample config, pointing its data folder to $KafkaDataDirectory
#   2. formats that data folder with a new cluster id
#   3. applies the platform's no-data-loss settings (a month of retention ...) - also to a broker set up earlier
# Safe to run again: it does not format an already formatted data folder, it only brings the settings up to date.
# Restart Kafka (stop-kafka.ps1, start-kafka.ps1) after running it on an existing setup.

. "$PSScriptRoot\local-infrastructure-paths.ps1"
$ErrorActionPreference = 'Stop'

if (-not (Test-Path "$KafkaHome\bin\windows\kafka-server-start.bat")) {
    throw "Kafka not found in $KafkaHome. Download kafka_2.13-4.3.1.tgz from https://kafka.apache.org/downloads and extract it there."
}

# Sets (or adds) one key=value in the broker's config file.
function Set-KafkaSetting([string]$Key, [string]$Value) {
    $lines = @(Get-Content $KafkaServerProperties)
    $pattern = '^\s*#?\s*' + [regex]::Escape($Key) + '\s*='
    if ($lines | Where-Object { $_ -match $pattern }) {
        $lines = $lines | ForEach-Object { if ($_ -match $pattern) { "$Key=$Value" } else { $_ } }
    } else {
        $lines += "$Key=$Value"
    }
    $lines | Set-Content -Path $KafkaServerProperties -Encoding ascii
}

# No data loss (docs/DATA_SAFETY_GUIDE.md) - the same settings as docker-compose.yml:
#   a month of data, deleted by age only; a stopped consumer group keeps its place for as long;
#   only declared topics; a replica that missed records never becomes leader;
#   one broker = one copy, so every record is on disk before the broker says "ok".
function Set-NoDataLossSettings {
    Set-KafkaSetting 'log.retention.hours' '744'
    Set-KafkaSetting 'log.retention.bytes' '-1'
    Set-KafkaSetting 'offsets.retention.minutes' '44640'
    Set-KafkaSetting 'auto.create.topics.enable' 'false'
    Set-KafkaSetting 'unclean.leader.election.enable' 'false'
    Set-KafkaSetting 'log.flush.interval.messages' '1'
    Write-Host "No-data-loss settings applied to $KafkaServerProperties"
}

if (Test-Path "$KafkaDataDirectory\meta.properties") {
    Write-Host "Kafka is already set up in $KafkaDataDirectory - only bringing its settings up to date."
    Set-NoDataLossSettings
    return
}

New-Item -ItemType Directory -Force $KafkaDataDirectory | Out-Null
$kafkaDataDirectoryForwardSlashes = $KafkaDataDirectory -replace '\\', '/'
(Get-Content "$KafkaHome\config\server.properties") `
    -replace '^log\.dirs=.*$', "log.dirs=$kafkaDataDirectoryForwardSlashes" |
    Set-Content -Path $KafkaServerProperties -Encoding ascii
Set-NoDataLossSettings

$clusterId = (& "$KafkaHome\bin\windows\kafka-storage.bat" random-uuid | Select-Object -Last 1).Trim()
& "$KafkaHome\bin\windows\kafka-storage.bat" format --standalone -t $clusterId -c $KafkaServerProperties
if ($LASTEXITCODE -ne 0) { throw "Formatting Kafka storage failed." }

Write-Host "Kafka is set up. Data folder: $KafkaDataDirectory. Start it with start-kafka.ps1"
