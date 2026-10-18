# Where the local infrastructure lives. Every other script loads this file first.
# Change a path here (or set the environment variable) and all scripts follow.

function Get-SettingOrDefault([string]$EnvironmentVariable, [string]$DefaultValue) {
    $value = [Environment]::GetEnvironmentVariable($EnvironmentVariable)
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultValue }
    return $value
}

$KafkaHome             = Get-SettingOrDefault 'GPS_KAFKA_HOME'         'D:\dev\kafka_2.13-4.3.1'
$KafkaDataDirectory    = Get-SettingOrDefault 'GPS_KAFKA_DATA'         'D:\dev\data\kafka'
$PostgresHome          = Get-SettingOrDefault 'GPS_POSTGRES_HOME'      'D:\dev\postgresql-14.22'
$PostgresDataDirectory = Get-SettingOrDefault 'GPS_POSTGRES_DATA'      'D:\dev\data\postgresql'
$PostgresLogFile       = Join-Path (Split-Path $PostgresDataDirectory -Parent) 'postgresql.log'
# Short folder for Java's internal socket files (see start-kafka.ps1 for why).
$JavaSocketTempDirectory = Get-SettingOrDefault 'GPS_JAVA_SOCKET_TEMP' 'D:\dev\data\tmp'

# The project root (two folders above this script) and its git-ignored secrets file.
$ProjectRoot           = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$LocalSecretsFile      = Join-Path $ProjectRoot 'local-secrets.properties'

$KafkaServerProperties = Join-Path $KafkaHome 'config\gps-local-server.properties'

function Read-LocalSecret([string]$PropertyName) {
    if (-not (Test-Path $LocalSecretsFile)) { return $null }
    foreach ($line in Get-Content $LocalSecretsFile) {
        if ($line -match "^\s*$([regex]::Escape($PropertyName))\s*=\s*(.*)$") { return $Matches[1].Trim() }
    }
    return $null
}

function Write-LocalSecret([string]$PropertyName, [string]$Value) {
    $lines = @()
    if (Test-Path $LocalSecretsFile) { $lines = @(Get-Content $LocalSecretsFile | Where-Object { $_ -notmatch "^\s*$([regex]::Escape($PropertyName))\s*=" }) }
    $lines += "$PropertyName=$Value"
    Set-Content -Path $LocalSecretsFile -Value $lines -Encoding ascii
}

function New-RandomPassword {
    $characters = [char[]]'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    return -join (1..24 | ForEach-Object { $characters | Get-Random })
}
