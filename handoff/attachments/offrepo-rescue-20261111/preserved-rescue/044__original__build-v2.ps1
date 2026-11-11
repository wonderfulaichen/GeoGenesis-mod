# BandIndex sandbox runner (stage 1: index implementation + verification).
# Sources:
#   diagnostics/  = xopt-90834f08 candidate set (DoubleBoundaryGeometry 8C26D068, DoubleFullMorphLand AD971E97)
#   src-head/     = frozen workspace-HEAD float reference (produces canonical 7BE04183 float arm)
#   IsolatedProbe/XFarSeams/ZSeamExtension/EndpointProbe/PerfGeometry/SeamForensics = xopt runner mains
# ASCII only (PS 5.1 CJK parsing).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$base = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"

# ---- 1) head float-arm reference classes
$headFiles = @(Get-ChildItem "$root/src-head/java/com/geogenesis/worldgen/terrain/candidate/*.java" | ForEach-Object FullName)
if ($headFiles.Count -lt 100) { throw "head source set too small: $($headFiles.Count)" }
$argHead = Join-Path $root 'javac-head.args'
[string[]]$t1 = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}"' -f ($cpbase -replace '\\','/')),
    '-d', ('"{0}/classes-head"' -f ($root -replace '\\','/')))
$t1 += @($headFiles | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argHead, $t1, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argHead"
if ($LASTEXITCODE -ne 0) { throw "javac head failed ($LASTEXITCODE)" }
Write-Host "COMPILE HEAD PASS ($($headFiles.Count) files)"

# ---- 2) candidate set from THIS directory
$cand = Join-Path $root 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
$candFiles = @(Get-ChildItem "$cand/Double*.java" | ForEach-Object FullName) +
    @("$cand/DualCoordinateCall.java", "$cand/ActualNodeClosure.java") +
    @(Get-ChildItem $root -Filter '*.java' | ForEach-Object FullName)
$argCand = Join-Path $root 'javac-cand.args'
[string[]]$t2 = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}/classes-head;{1}"' -f (($root -replace '\\','/')), ($cpbase -replace '\\','/')),
    '-d', ('"{0}/classes-cand"' -f ($root -replace '\\','/')))
$t2 += @($candFiles | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argCand, $t2, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argCand"
if ($LASTEXITCODE -ne 0) { throw "javac candidate failed ($LASTEXITCODE)" }
Write-Host "COMPILE CAND PASS ($($candFiles.Count) files)"

# ---- 3) identity record
$sha = [Security.Cryptography.SHA256]::Create()
$rows = @()
foreach ($p in @("$cand/DoubleBoundaryGeometry.java", "$cand/DoubleFullMorphLand.java")) {
    $h = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($p))) -replace '-',''
    $rows += ("{0}  {1}" -f $h, (Split-Path $p -Leaf))
}
$rows | Set-Content (Join-Path $root 'candidate-identity.txt') -Encoding utf8
Get-ChildItem "$root/classes-cand" -Recurse -File |
    Get-FileHash -Algorithm SHA256 | Sort-Object Path |
    ForEach-Object { "{0}  {1}" -f $_.Hash, $_.Path.Replace($root,'') } |
    Set-Content (Join-Path $root 'class-hashes-cand.txt') -Encoding utf8
Write-Host 'BUILD PASS'
