# Stops the local Kafka broker.

. "$PSScriptRoot\local-infrastructure-paths.ps1"

& "$KafkaHome\bin\windows\kafka-server-stop.bat"
Write-Host "Stop signal sent to Kafka."
