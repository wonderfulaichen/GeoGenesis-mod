# Snapshot the temp candidate dir into original/ (raw copy), excluding large derived binaries.
# Excluded (hashes recorded instead of bytes):
#   *.f64be                 -> 15.7 MB binary height/coverage arrays (32 files, ~479 MB)
#   *.png with size > 1 MB  -> 1401^2 figures
# Everything else (sources, .class audit copies, .py/.ps1/.args, CSV, small PNG, JSON, logs, .txt) is copied byte for byte.
param(
    [Parameter(Mandatory=$true)][string]$TempRoot,
    [Parameter(Mandatory=$true)][string]$DestRoot
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $DestRoot | Out-Null
$skipped = New-Object System.Collections.Generic.List[object]
$copied  = New-Object System.Collections.Generic.List[object]
$files = Get-ChildItem -Recurse -File -Force $TempRoot
foreach ($f in $files) {
    $rel = $f.FullName.Substring($TempRoot.Length).TrimStart('\')
    $skip = $false
    $reason = ''
    if ($f.Extension -eq '.f64be') { $skip = $true; $reason = 'large binary stage output (derived)' }
    elseif ($f.Extension -eq '.png' -and $f.Length -gt 1MB) { $skip = $true; $reason = 'large figure (>1MB)' }
    if ($skip) {
        $skipped.Add([PSCustomObject]@{ path = $rel; bytes = $f.Length; sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash; reason = $reason })
        continue
    }
    $target = Join-Path $DestRoot $rel
    $dir = Split-Path $target -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    Copy-Item -LiteralPath $f.FullName -Destination $target -Force
    $copied.Add([PSCustomObject]@{ path = $rel; bytes = $f.Length; sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash })
}
$obj = [PSCustomObject]@{
    source = $TempRoot
    copiedCount = $copied.Count
    copiedBytes = ($copied | Measure-Object -Sum bytes).Sum
    skippedCount = $skipped.Count
    skippedBytes = ($skipped | Measure-Object -Sum bytes).Sum
    copied = $copied
    skipped = $skipped
}
$json = $obj | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path (Split-Path $DestRoot -Parent) '_build\original-snapshot-index.json'), $json, [Text.UTF8Encoding]::new($false))
Write-Output ("copied={0} bytes={1} skipped={2} bytes={3}" -f $obj.copiedCount, $obj.copiedBytes, $obj.skippedCount, $obj.skippedBytes)
