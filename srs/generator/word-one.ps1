# One document in one Word instance: fill the table of contents, save, export a PDF. Called by render.ps1.
param([string]$File, [string]$Pdf, [string]$Log)

function Note($message) { Add-Content -Path $Log -Value ("{0:HH:mm:ss} {1}" -f (Get-Date), $message) }

$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
$word.Options.CheckGrammarAsYouType = $false
$word.Options.CheckSpellingAsYouType = $false
$doc = $word.Documents.Open($File, $false, $false, $false)
$doc.Repaginate()
foreach ($toc in $doc.TablesOfContents) { $toc.Update() }
$doc.Save()
Note ("saved {0}" -f [IO.Path]::GetFileName($File))
if ($Pdf) {
    $doc.ExportAsFixedFormat($Pdf, 17)
    Note ("exported {0}" -f [IO.Path]::GetFileName($Pdf))
}
$doc.Close()
$word.Quit()
