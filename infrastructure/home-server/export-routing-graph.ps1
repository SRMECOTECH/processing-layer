# Packs the routing graph imported on THIS laptop (Docker volume of the main platform) into one file
# for the home server, with a SHA-256 next to it. Run after the import finished (docs/ROUTING_ENGINE_SETUP.md).
#
#   .\infrastructure\home-server\export-routing-graph.ps1
#   .\infrastructure\home-server\export-routing-graph.ps1 -Region asia/india/eastern-zone -OutDir E:\
#
# Then copy both files to the HP (scp, or a USB drive) and run install-routing-graph.sh there.
# The HP must run the same GraphHopper image and config.yml (same repository commit): the graph is tied to them.

param(
    [string] $Region = 'asia/india',
    [string] $OutDir = 'D:\dev\routing-export',
    [string] $Volume = 'gps-streaming-platform_gps-routing-data'
)

$ErrorActionPreference = 'Stop'
$name = $Region -replace '/', '-'
$file = "$name-graph.tar"

docker run --rm -v "${Volume}:/data:ro" alpine:3.20 test -d "/data/graph-cache/$name"
if ($LASTEXITCODE -ne 0) {
    throw "No finished graph for $Region in volume $Volume. Import it first: docker compose --profile routing up -d graphhopper"
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
Write-Host "Packing /data/graph-cache/$name into $OutDir\$file ..."
docker run --rm -v "${Volume}:/data:ro" -v "${OutDir}:/out" alpine:3.20 sh -c "tar -C /data/graph-cache -cf /out/$file $name && cd /out && sha256sum $file > $file.sha256"
if ($LASTEXITCODE -ne 0) { throw "Packing failed" }

$size = [math]::Round((Get-Item (Join-Path $OutDir $file)).Length / 1GB, 2)
Write-Host "Done: $OutDir\$file ($size GB) and $file.sha256"
Write-Host "Copy to the HP:   scp `"$OutDir\$file`" `"$OutDir\$file.sha256`" <user>@<hp-ip>:~/"
Write-Host "Install on the HP: sh infrastructure/home-server/install-routing-graph.sh ~/$file"
