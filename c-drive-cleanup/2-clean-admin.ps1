#Requires -RunAsAdministrator
# Step 2 - run in an ADMIN PowerShell window, after quitting Docker Desktop from the system tray.
#   powershell -ExecutionPolicy Bypass -File .\2-clean-admin.ps1
#   powershell -ExecutionPolicy Bypass -File .\2-clean-admin.ps1 -ShrinkHibernation   # also shrink hiberfil.sys
param([switch]$ShrinkHibernation)

function Free { [math]::Round((Get-PSDrive C).Free / 1GB, 2) }
$start = Free
"Free before: $start GB"

# 1. Shrink Docker's disk file. It only grows on its own; space freed inside it in step 1 comes back here.
$vhdx = 'C:\Users\Sanjoy Chattopadhyay\AppData\Local\Docker\wsl\disk\docker_data.vhdx'
if (Get-Process 'Docker Desktop', 'com.docker.backend' -ErrorAction SilentlyContinue) {
    "Docker Desktop is still running - quit it from the tray and run this script again. Skipping the Docker disk."
} elseif (Test-Path $vhdx) {
    wsl --shutdown
    Start-Sleep 5
    $before = (Get-Item $vhdx).Length
    $dp = Join-Path $env:TEMP 'compact-docker-vhdx.txt'
    "select vdisk file=`"$vhdx`"`r`nattach vdisk readonly`r`ncompact vdisk`r`ndetach vdisk`r`nexit" | Set-Content $dp -Encoding ascii
    diskpart /s $dp | Out-Null
    Remove-Item $dp
    "Docker disk file: {0} GB -> {1} GB" -f [math]::Round($before / 1GB, 2), [math]::Round((Get-Item $vhdx).Length / 1GB, 2)
}

# 2. NVIDIA App keeps a full copy of the driver installer (610.62) that is already installed.
$before = Free
Remove-Item 'C:\ProgramData\NVIDIA Corporation\NVIDIA App\UpdateFramework\ota-artifacts\grd' -Recurse -Force -ErrorAction SilentlyContinue
"NVIDIA cached driver installer          freed {0} GB" -f [math]::Round((Free) - $before, 2)

# 3. Windows Temp (files untouched for 24h+).
$before = Free
Get-ChildItem 'C:\Windows\Temp' -Force -ErrorAction SilentlyContinue | Where-Object LastWriteTime -lt (Get-Date).AddDays(-1) |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
"Windows Temp                            freed {0} GB" -f [math]::Round((Free) - $before, 2)

# 4. Superseded Windows Update components (takes 5-15 minutes).
"Cleaning up old Windows Update components (this takes a while)..."
$before = Free
Dism.exe /Online /Cleanup-Image /StartComponentCleanup /Quiet
"Windows component store                 freed {0} GB" -f [math]::Round((Free) - $before, 2)

# 5. Optional: shrink hiberfil.sys (~9.5 GB) to about half. Keeps Fast Startup; removes "Hibernate" from the power menu.
if ($ShrinkHibernation) {
    $before = Free
    powercfg /h /type reduced
    "Hibernation file (reduced)              freed {0} GB" -f [math]::Round((Free) - $before, 2)
}

"Free after: $(Free) GB  (total freed: $([math]::Round((Free) - $start, 2)) GB)"
