[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$YtDlpPath = "",
    [string]$CjxlPath = "",
    [switch]$AllowExistingOnFailure
)

$ErrorActionPreference = 'Stop'
$quality = 69
$effort = 10
$avatarSize = 176
$libJxlVersion = '0.12.0'
$libJxlArchiveSha256 = 'ff147dc7ac4ce55392974ccc70f2a8a8ec0eff3ae28529b072258b66c8f01ab2'
$libJxlUrl = "https://github.com/libjxl/libjxl/releases/download/v$libJxlVersion/jxl-x64-windows-static.7z"
$sevenZipUrl = 'https://www.7-zip.org/a/7zr.exe'
$sevenZipSha256 = '56b8cc9f4971cef253644fafe54063ed7fdca551d4dee0f8c6baa81b855acd72'
$catalogPath = Join-Path $ProjectRoot 'app\src\main\assets\creators.json'
$outputDirectory = Join-Path $ProjectRoot 'app\src\main\assets\bundled_avatars'
$manifestPath = Join-Path $outputDirectory 'manifest.json'
$buildRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot '..\KOMPILACJA'))
$toolRoot = Join-Path $buildRoot "tools\libjxl-$libJxlVersion"

function Resolve-Executable {
    param([string]$Requested, [string]$Name, [string[]]$Candidates)
    if ($Requested) {
        $resolved = [IO.Path]::GetFullPath($Requested)
        if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
        throw "Nie znaleziono $Name pod podana sciezka: $resolved"
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

function Get-Cjxl {
    $found = Resolve-Executable -Requested $CjxlPath -Name 'cjxl.exe' -Candidates @(
        (Join-Path $toolRoot 'cjxl.exe'),
        (Join-Path $toolRoot 'bin\cjxl.exe')
    )
    if ($found) { return $found }
    if (Test-Path -LiteralPath $toolRoot -PathType Container) {
        $found = Get-ChildItem -LiteralPath $toolRoot -Recurse -Filter 'cjxl.exe' -File |
            Select-Object -First 1 -ExpandProperty FullName
        if ($found) { return $found }
    }

    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $archive = Join-Path $toolRoot 'libjxl.7z'
    Write-Host "Pobieranie oficjalnego cjxl $libJxlVersion..."
    Invoke-WebRequest -UseBasicParsing -Uri $libJxlUrl -OutFile $archive
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    if ($actualHash -ne $libJxlArchiveSha256) {
        Remove-Item -LiteralPath $archive -Force
        throw "Suma SHA-256 oficjalnego archiwum cjxl jest nieprawidlowa."
    }
    $sevenZip = Join-Path $buildRoot 'tools\7zr.exe'
    if (-not (Test-Path -LiteralPath $sevenZip -PathType Leaf)) {
        Invoke-WebRequest -UseBasicParsing -Uri $sevenZipUrl -OutFile $sevenZip
    }
    $actual7zHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sevenZip).Hash.ToLowerInvariant()
    if ($actual7zHash -ne $sevenZipSha256) {
        throw 'Suma SHA-256 oficjalnego ekstraktora 7-Zip jest nieprawidlowa.'
    }
    & $sevenZip x -y "-o$toolRoot" $archive | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Nie udalo sie rozpakowac oficjalnego libjxl.' }
    Remove-Item -LiteralPath $archive -Force
    $found = Get-ChildItem -LiteralPath $toolRoot -Recurse -Filter 'cjxl.exe' -File |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $found) { throw 'Archiwum libjxl nie zawiera cjxl.exe.' }
    return $found
}

function Normalize-AvatarUrl {
    param([string]$Url)
    if (-not $Url -or $Url.Length -gt 4096) { return $null }
    $uri = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$uri)) { return $null }
    if ($uri.Scheme -ne 'https' -or $uri.Port -ne 443) { return $null }
    $avatarHost = $uri.DnsSafeHost.ToLowerInvariant().TrimEnd('.')
    if ($avatarHost -notin @('yt3.ggpht.com', 'yt3.googleusercontent.com')) { return $null }
    $builder = [UriBuilder]$uri
    $path = $builder.Path
    if ($path -match '=s\d+[^/?#]*$') {
        $path = [regex]::Replace($path, '=s\d+[^/?#]*$', '=s176-c-k-c0x00ffffff-no-rj')
    } else {
        $path += '=s176-c-k-c0x00ffffff-no-rj'
    }
    $builder.Path = $path
    return $builder.Uri.AbsoluteUri
}

function Get-AvatarUrl {
    param($Creator, [string]$YtDlp)
    $pendingUrls = [Collections.Generic.Queue[string]]::new()
    $sources = @($Creator.sources | ForEach-Object { $_ })
    $profileChannelId = [string]$Creator.profileChannelId
    if ($profileChannelId -match '^UC[A-Za-z0-9_-]{22}$') {
        $pendingUrls.Enqueue("https://www.youtube.com/channel/$profileChannelId")
    }
    foreach ($source in $sources) {
        $pendingUrls.Enqueue([string]$source.url)
    }
    $visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    while ($pendingUrls.Count -gt 0) {
        $metadataUrl = $pendingUrls.Dequeue()
        if (-not $visited.Add($metadataUrl)) { continue }
        try {
            $jsonText = & $YtDlp --playlist-items 0 --dump-single-json --skip-download --no-warnings -- $metadataUrl 2>$null
            if ($LASTEXITCODE -ne 0 -or -not $jsonText) { continue }
            $metadata = ($jsonText -join "`n") | ConvertFrom-Json
            $candidateItem = $metadata.thumbnails |
                Where-Object { $_.url -and $_.width -eq $_.height } |
                Sort-Object width -Descending |
                Select-Object -First 1
            $candidate = [string]$candidateItem.url
            Write-Verbose "Wybrany URL avatara: $candidate"
            $normalized = Normalize-AvatarUrl -Url $candidate
            if ($normalized) { return $normalized }
            $fallbackUrls = @(
                [string]$metadata.channel_url,
                [string]$metadata.uploader_url
            )
            if ([string]$metadata.channel_id -match '^UC[A-Za-z0-9_-]{22}$') {
                $fallbackUrls += "https://www.youtube.com/channel/$($metadata.channel_id)"
            }
            foreach ($fallbackUrl in $fallbackUrls) {
                $fallbackUri = $null
                if (
                    $fallbackUrl -and
                    [Uri]::TryCreate($fallbackUrl, [UriKind]::Absolute, [ref]$fallbackUri) -and
                    $fallbackUri.Scheme -eq 'https' -and
                    $fallbackUri.DnsSafeHost.TrimEnd('.').ToLowerInvariant() -in
                        @('youtube.com', 'www.youtube.com')
                ) {
                    $pendingUrls.Enqueue($fallbackUri.AbsoluteUri)
                }
            }
        } catch {
            Write-Verbose "Nie udalo sie odczytac avatara z ${metadataUrl}: $($_.Exception.Message)"
            continue
        }
    }
    return $null
}

function Assert-Image176 {
    param([string]$Path)
    Add-Type -AssemblyName System.Drawing
    $image = [Drawing.Image]::FromFile($Path)
    try {
        if ($image.Width -ne $avatarSize -or $image.Height -ne $avatarSize) {
            throw "CDN zwrocil obraz $($image.Width)x$($image.Height), oczekiwano 176x176."
        }
    } finally {
        $image.Dispose()
    }
}

try {
    if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
        throw "Brak katalogu tworcow: $catalogPath"
    }
    $ytDlp = Resolve-Executable -Requested $YtDlpPath -Name 'yt-dlp.exe' -Candidates @(
        'H:\Windows\apk\yt-dlp\yt-dlp.exe',
        'H:\Windows\apk\yt-dlp\yt-dlp'
    )
    if (-not $ytDlp) {
        throw 'Nie znaleziono yt-dlp. Dodaj je do PATH albo podaj -YtDlpPath.'
    }
    $cjxl = Get-Cjxl
    $parsedCreators = Get-Content -Raw -LiteralPath $catalogPath | ConvertFrom-Json
    # Windows PowerShell 5 przekazuje tablicę JSON jako jeden obiekt potoku.
    $creators = @($parsedCreators | ForEach-Object { $_ })
    if ($creators.Count -eq 0) { throw 'Katalog tworcow jest pusty.' }

    $temporaryRoot = Join-Path $buildRoot ("bundled-avatars-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($creator in $creators) {
        $creatorId = [string]$creator.id
        if ($creatorId -notmatch '^[a-z0-9][a-z0-9-]{0,79}$') {
            throw "Niebezpieczny identyfikator twórcy: $creatorId"
        }
        Write-Host "Avatar: $($creator.name)"
        $avatarUrl = Get-AvatarUrl -Creator $creator -YtDlp $ytDlp
        if (-not $avatarUrl) { throw "Nie znaleziono profilowego: $($creator.name)" }
        $jpgPath = Join-Path $temporaryRoot "$creatorId.jpg"
        $jxlPath = Join-Path $temporaryRoot "$creatorId.jxl"
        Invoke-WebRequest -UseBasicParsing -Uri $avatarUrl -OutFile $jpgPath -Headers @{
            'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) lewicowYT-build'
            'Accept' = 'image/jpeg,image/*;q=0.8'
        }
        if ((Get-Item -LiteralPath $jpgPath).Length -gt 2MB) {
            throw "Profilowe jest zbyt duze: $($creator.name)"
        }
        Assert-Image176 -Path $jpgPath
        $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jpgPath).Hash.ToLowerInvariant()
        & $cjxl $jpgPath $jxlPath --quality=$quality --effort=$effort --lossless_jpeg=0 --num_threads=0 --quiet
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jxlPath -PathType Leaf)) {
            throw "Kodowanie JXL nie powiodlo sie: $($creator.name)"
        }
        Remove-Item -LiteralPath $jpgPath -Force
        $entries.Add([ordered]@{
            creatorId = $creatorId
            fileName = "$creatorId.jxl"
            sha256 = $sha256
        })
    }

    $manifest = [ordered]@{
        schemaVersion = 1
        generatedAtMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        imageWidth = $avatarSize
        imageHeight = $avatarSize
        jxlQuality = $quality
        jxlEffort = $effort
        avatars = $entries
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText(
        (Join-Path $temporaryRoot 'manifest.json'),
        $manifestJson,
        [Text.UTF8Encoding]::new($false)
    )

    $expectedFiles = $creators.Count + 1
    $actualFiles = @(Get-ChildItem -LiteralPath $temporaryRoot -File).Count
    if ($actualFiles -ne $expectedFiles) {
        throw "Niepelny pakiet awatarow: $actualFiles zamiast $expectedFiles plikow."
    }
    if (Test-Path -LiteralPath $outputDirectory) {
        $resolvedOutput = [IO.Path]::GetFullPath($outputDirectory)
        $resolvedAssets = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'app\src\main\assets'))
        if (-not $resolvedOutput.StartsWith($resolvedAssets + [IO.Path]::DirectorySeparatorChar)) {
            throw 'Odmowa usuniecia katalogu poza assets.'
        }
        Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
    }
    Move-Item -LiteralPath $temporaryRoot -Destination $outputDirectory
    Write-Host "Gotowe: $($creators.Count) awatarow JXL 176x176, quality=$quality, effort=$effort."
} catch {
    if ($temporaryRoot -and (Test-Path -LiteralPath $temporaryRoot -PathType Container)) {
        $resolvedTemporary = [IO.Path]::GetFullPath($temporaryRoot)
        if (
            $resolvedTemporary.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar) -and
            (Split-Path -Leaf $resolvedTemporary).StartsWith('bundled-avatars-')
        ) {
            Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
        }
    }
    if ($AllowExistingOnFailure -and (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        Write-Warning "Nie odswiezono awatarow; zachowano kompletny pakiet z poprzedniej kompilacji. $($_.Exception.Message)"
        exit 0
    }
    throw
}
