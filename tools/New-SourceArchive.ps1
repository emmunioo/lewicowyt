[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Destination = (Join-Path (Split-Path -Parent $PSScriptRoot) 'lewicowYT-source.zip')
)

$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath($ProjectRoot)
$destinationPath = [IO.Path]::GetFullPath($Destination)
$git = (Get-Command git.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $git) { throw 'Nie znaleziono git.exe. Użyj archiwum Source code z GitHub Releases.' }

$tracked = @(& $git -C $project ls-files)
if ($LASTEXITCODE -ne 0 -or $tracked.Count -eq 0) {
    throw 'Nie udało się odczytać śledzonych plików repozytorium.'
}
$forbidden = $tracked | Where-Object {
    $_ -match '(^|/)(local\.properties|workspace\.xml|keystore\.properties|signing\.properties)$' -or
    $_ -match '\.(jks|keystore)$'
}
if ($forbidden) {
    throw "Repozytorium śledzi zabronione pliki: $($forbidden -join ', ')"
}

$parent = Split-Path -Parent $destinationPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
& $git -C $project archive --format=zip --output=$destinationPath HEAD
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $destinationPath -PathType Leaf)) {
    throw 'Nie udało się utworzyć archiwum ze śledzonych plików.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($destinationPath)
try {
    $leaks = $archive.Entries.FullName | Where-Object {
        $_ -match '(^|/)(\.idea/|local\.properties$|workspace\.xml$)' -or
        $_ -match '\.(jks|keystore)$'
    }
    if ($leaks) { throw "Archiwum zawiera zabronione wpisy: $($leaks -join ', ')" }
} finally {
    $archive.Dispose()
}
Write-Host "Gotowe: $destinationPath"
