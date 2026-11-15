# Step 1 - run in a NORMAL (non-admin) PowerShell window.
# Clears only caches and temp files that apps rebuild on their own.
# Docker: removes build cache and untagged images only - containers, tagged images and volumes (your data) stay.
$ErrorActionPreference = 'SilentlyContinue'
function Free { [math]::Round((Get-PSDrive C).Free / 1GB, 2) }
$start = Free
"Free before: $start GB"

function Step([string]$name, [scriptblock]$body) {
    $before = Free
    & $body *> $null
    "{0,-45} freed {1,6} GB" -f $name, [math]::Round((Free) - $before, 2)
}

$tempRoot = "$env:LOCALAPPDATA\Temp"
$cut = (Get-Date).AddDays(-1)

Step 'User Temp (untouched for 24h+)' {
    Get-ChildItem $tempRoot -Force | Where-Object { $_.Name -ne 'claude' -and $_.LastWriteTime -lt $cut } |
        Remove-Item -Recurse -Force
}
Step 'npm cache' { npm cache clean --force }
Step 'uv cache (unused entries)' { uv cache prune }
Step 'pip cache' { pip cache purge }
Step 'NVIDIA DirectX shader cache' { Get-ChildItem "$env:LOCALAPPDATA\NVIDIA\DXCache" -Force | Remove-Item -Recurse -Force }
Step 'VS Code caches + crash dumps' {
    foreach ($d in 'CachedExtensionVSIXs', 'Crashpad', 'Cache', 'GPUCache') {
        Get-ChildItem "$env:APPDATA\Code\$d" -Force | Remove-Item -Recurse -Force
    }
}
Step 'WinGet downloaded installers' { Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Force | Remove-Item -Recurse -Force }
Step 'JetBrains caches of uninstalled IDEs' {
    foreach ($d in 'IdeaIC2025.1', 'IntelliJIdea2025.2') { Remove-Item "$env:LOCALAPPDATA\JetBrains\$d" -Recurse -Force }
}
Step 'Windows crash dumps (user)' { Get-ChildItem "$env:LOCALAPPDATA\CrashDumps" -Force | Remove-Item -Recurse -Force }

# Docker prune. This frees space INSIDE Docker's disk file; step 2 shrinks the file itself.
if (-not (Get-Process 'Docker Desktop')) {
    "Starting Docker Desktop to prune it..."
    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
}
$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; docker info *> $null } until ($LASTEXITCODE -eq 0 -or (Get-Date) -gt $deadline)
if ($LASTEXITCODE -eq 0) {
    "--- Docker usage before ---"; docker system df
    docker builder prune -af *> $null
    docker image prune -f *> $null
    wsl -d docker-desktop -u root -e sh -c "fstrim -av" *> $null   # best effort: lets step 2 shrink the file further
    "--- Docker usage after ---"; docker system df
} else {
    "Docker did not start within 4 minutes - Docker prune skipped."
}

"Free after: $(Free) GB  (total freed: $([math]::Round((Free) - $start, 2)) GB)"
"Next: quit Docker Desktop from the system tray, then run 2-clean-admin.ps1 as administrator."
