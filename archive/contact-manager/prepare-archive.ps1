$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$archiveRoot = $PSScriptRoot
$releases = Get-Content -LiteralPath (Join-Path $archiveRoot 'releases.json') -Raw | ConvertFrom-Json
$selected = @($releases | Where-Object { -not $_.draft } | Sort-Object published_at -Descending | Select-Object -First 5)
if ($selected.Count -ne 5) { throw 'Expected five published releases.' }
$manifest = @()
foreach ($release in $selected) {
    $tag = $release.tag_name
    if ($tag -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$') { throw "Unexpected tag: $tag" }
    $destination = Join-Path $archiveRoot $tag
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $commit = git -C $projectRoot rev-parse "$tag^{commit}"
    if ($LASTEXITCODE -ne 0) { throw "Cannot resolve $tag" }
    # Historical binaries remain in versions/ and artifacts/ in the main copy.
    # Keep every other root entry from the exact tag in the source snapshot.
    $entries = @(git -C $projectRoot ls-tree --name-only $tag | Where-Object { $_ -notin @('versions', 'artifacts') })
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $tag" }
    $zip = Join-Path $destination 'source.zip'
    git -C $projectRoot -c core.autocrlf=false archive --format=zip "--output=$zip" $tag -- @entries
    if ($LASTEXITCODE -ne 0) { throw "Cannot archive $tag" }
    [IO.File]::WriteAllText((Join-Path $destination 'release-notes.md'), $release.body.TrimEnd() + "`n", [Text.UTF8Encoding]::new($false))
    $assets = @()
    foreach ($asset in $release.assets) {
        $relativePath = "versions/Contact Manager $tag/$($asset.name)"
        $localPath = Join-Path $projectRoot $relativePath
        if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) { throw "Missing asset: $relativePath" }
        $actualSize = (Get-Item -LiteralPath $localPath).Length
        $actualHash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualSize -ne $asset.size) { throw "Size mismatch: $relativePath" }
        if ($asset.digest -ne "sha256:$actualHash") { throw "SHA-256 mismatch: $relativePath" }
        $assets += [ordered]@{ path=$relativePath; bytes=$actualSize; sha256=$actualHash; source_url=$asset.browser_download_url; github_digest_verified=$true }
    }
    $manifest += [ordered]@{
        tag=$tag
        commit=$commit
        release_url=$release.html_url
        published_at=$release.published_at
        prerelease=$release.prerelease
        source_archive="archive/contact-manager/$tag/source.zip"
        source_archive_sha256=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
        source_archive_excludes=@('versions/', 'artifacts/')
        assets=$assets
    }
}
$result = [ordered]@{
    source_repository='https://github.com/flvmming-twink/Contact-meneger-archive-copy'
    imported_source_head='9190c49e30cf9234e7e8031636cdb0723539f8e5'
    selection='Five most recently published non-draft GitHub releases, including prereleases'
    releases=$manifest
}
[IO.File]::WriteAllText((Join-Path $archiveRoot 'manifest.json'), ($result | ConvertTo-Json -Depth 15) + "`n", [Text.UTF8Encoding]::new($false))
$manifest | ForEach-Object { [pscustomobject]@{ Tag=$_.tag; Commit=$_.commit; VerifiedAssets=$_.assets.Count } } | ConvertTo-Json -Compress
