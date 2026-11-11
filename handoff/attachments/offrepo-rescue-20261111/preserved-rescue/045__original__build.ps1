# BandIndex sandbox runner (index stage).
# Compile: (a) workspace-HEAD float reference arm [baseline classes only, not needed for candidate compile
#          because candidate only references candidate-package sources + src/main config], (b) candidate
#          Double* sources from this directory's diagnostics copy with the BandIndex Tile.
# ASCII only (PS 5.1 CJK parsing).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$mdk  = Join-Path $repo 'forge-1.20.1-47.4.10-mdk'
$base = Join-Path $mdk 'build/terrain-upgrade/baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"

# ---- 1) head float-arm reference classes (for the frozen 7BE04183 float arm in galleries)
$headSrc = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\src\diagnostics\java'
$skip = @('ProvinceDebugProbe.java', 'ProvinceSampleCheck.java', 'SkipRateProbe.java')
$headFiles = @(Get-ChildItem "$headSrc/com/geogenesis/worldgen/terrain/candidate/*.java" |
    Where-Object { $skip -notcontains $_.Name } | ForEach-Object FullName)
$argHead = Join-Path $root 'javac-head.args'
[string[]]$t1 = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}"' -f ($cpbase -replace '\\','/')),
    '-d', ('"{0}/classes-head"' -f ($root -replace '\\','/')))
$t1 += @($headFiles | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argHead, $t1, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argHead"
if ($LASTEXITCODE -ne 0) { throw "javac head failed ($LASTEXITCODE)" }
Write-Host "COMPILE HEAD PASS ($($headFiles.Count) files)"

# ---- 2) candidate: Double* + DualCoordinateCall + ActualNodeClosure from THIS directory,
#          plus runner mains at the root, on top of head classes
$cand = Join-Path $root 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
$candFiles = @(Get-ChildItem "$cand/Double*.java" | ForEach-Object FullName) +
    @("$cand/DualCoordinateCall.java", "$cand/ActualNodeClosure.java") +
    @(Get-ChildItem $root -Filter '*.java' | ForEach-Object FullName)
$cls = 'classes-cand'
$argCand = Join-Path $root 'javac-cand.args'
[string[]]$t2 = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}/classes-head;{1}"' -f (($root -replace '\\','/')), ($cpbase -replace '\\','/')),
    '-d', ('"{0}/{1}"' -f (($root -replace '\\','/')), $cls))
$t2 += @($candFiles | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argCand, $t2, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argCand"
if ($LASTEXITCODE -ne 0) { throw "javac candidate failed ($LASTEXITCODE)" }
Write-Host "COMPILE CAND PASS ($($candFiles.Count) files)"

# ---- 3) identity of the two load-bearing sources
$sha = [Security.Cryptography.SHA256]::Create()
foreach ($n in 'DoubleBoundaryGeometry.java','DoubleFullMorphLand.java') {
    $h = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes("$cand/$n"))) -replace '-',''
    "{0} {1}" -f $h, $n
}
Write-Host 'BUILD PASS'
