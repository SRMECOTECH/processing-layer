# Fills in the tables of contents of the generated .docx files with Microsoft Word and exports a PDF of each, next to
# the .docx files.
#   powershell -ExecutionPolicy Bypass -File render.ps1 [-NoPdf]
#
# Each document is handled by word-one.ps1 in its own PowerShell process and Word instance, on a copy in the temp
# folder that is copied back afterwards. Driven in one long-running process over the repository folder, an invisible
# Word was seen to stall on the PDF export; this arrangement has not stalled.
param([switch]$NoPdf)

$folder = Split-Path -Parent $PSScriptRoot
$one = Join-Path $PSScriptRoot "word-one.ps1"
$work = Join-Path ([IO.Path]::GetTempPath()) ("srs-render-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$log = Join-Path $work "render.log"

foreach ($source in @(Get-ChildItem -Path $folder -Filter *.docx | Where-Object { $_.Name -notlike '~$*' })) {
    $copy = Join-Path $work $source.Name
    $pdf = if ($NoPdf) { "" } else { [IO.Path]::ChangeExtension($copy, ".pdf") }
    Copy-Item -LiteralPath $source.FullName -Destination $copy -Force
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $one -File $copy -Pdf $pdf -Log $log
    Copy-Item -LiteralPath $copy -Destination $source.FullName -Force
    if ($pdf -and (Test-Path $pdf)) {
        Copy-Item -LiteralPath $pdf -Destination ([IO.Path]::ChangeExtension($source.FullName, ".pdf")) -Force
    }
}
Get-Content $log
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
