# Build + verify the P0 extracted morph terrain package.
#
# READ-ONLY with respect to the repository. Nothing here writes into the workspace or into the
# preserved candidate directory.
#
# ★ The morph package is compiled against the REAL PRODUCTION SOURCES of TerrainParams /
#   HeightCurve / ContinentField / the noise + spline packages -- NOT against a prebuilt
#   classes-main directory. That is deliberate: compiling against prebuilt classes would let a
#   hidden transitive dependency survive unnoticed. The only thing on the classpath is
#   DataFixerUpper, which Minecraft's own worldgen.noise package needs in order to compile.
#
# ★ The parity reference (the verified candidate) IS taken from prebuilt classes, because that
#   code is frozen and we are only calling into it to compare numbers.
#
# ASCII only: Windows PowerShell 5.1 parses this file with the system codepage.

param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot',
    [string]$RepoRoot = 'D:/Office software/Development Project/GeoGenesis-mod/forge-1.20.1-47.4.10-mdk',
    [string]$Preserved = 'D:/Office software/Development Project/GeoGenesis-preserved-candidate-20261103',
    [switch]$SkipCanonical
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out  = Join-Path $root 'out'
$rep  = Join-Path $root 'reports'
New-Item -ItemType Directory -Force $out | Out-Null
New-Item -ItemType Directory -Force $rep | Out-Null

$javac = Join-Path $JavaHome 'bin\javac.exe'
$java  = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

# ---- production source closure (real sources, not prebuilt classes) -------------------------
$wsMain = Join-Path $RepoRoot 'src\main\java'
if (-not (Test-Path $wsMain)) { throw "missing production source root: $wsMain" }
$terrainDir = Join-Path $wsMain 'com\geogenesis\worldgen\terrain'
$noiseDir   = Join-Path $wsMain 'com\geogenesis\worldgen\noise'

$support = @(
    'TerrainParams.java', 'HeightCurve.java', 'ContinentField.java',
    'SplineConfig.java', 'OceanSplineConfig.java', 'MidSplineConfig.java',
    'UnifiedSpline.java', 'SplineUtil.java'
) | ForEach-Object { Join-Path $terrainDir $_ }
foreach ($f in $support) { if (-not (Test-Path $f)) { throw "missing production source: $f" } }
$support += @(Get-ChildItem $noiseDir -File -Filter '*.java' | ForEach-Object FullName)

# ---- DataFixerUpper: the only jar needed, and only because worldgen/noise declares Codecs ----
$jarList = Join-Path $Preserved 'frozen\classpath-jars.txt'
if (-not (Test-Path $jarList)) { throw "missing jar classpath record: $jarList" }
$allJars = ((Get-Content $jarList -Raw) -replace "`r?`n", '').Trim() -split ';'
$dfu = @($allJars | Where-Object { $_ -match 'datafixerupper' })
if ($dfu.Count -ne 1) { throw "expected exactly one datafixerupper jar, found $($dfu.Count)" }
# DataFixerUpper pulls in fastutil at runtime (the Codec statics in worldgen/noise touch it).
$fastutil = @($allJars | Where-Object { $_ -match 'fastutil' })
if ($fastutil.Count -ne 1) { throw "expected exactly one fastutil jar, found $($fastutil.Count)" }
$noiseCp = "$($dfu[0]);$($fastutil[0])"

function Compile([string]$name, [string[]]$srcs, [string]$cp, [string]$dest) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    $argFile = Join-Path $out ("javac-$name.args")
    [string[]]$t = @('--release', '17', '-encoding', 'UTF-8', '-nowarn', '-proc:none')
    if ($cp) { $t += @('-cp', ('"{0}"' -f ($cp -replace '\\', '/'))) }
    $t += @('-d', ('"{0}"' -f ($dest -replace '\\', '/')))
    $t += @($srcs | ForEach-Object { '"{0}"' -f ($_.Replace('\', '/')) })
    [IO.File]::WriteAllLines($argFile, $t, [Text.UTF8Encoding]::new($false))
    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE): $argFile" }
    Write-Host ("  OK {0,-10} {1,3} sources -> {2,3} classes" -f $name, $srcs.Count,
        (Get-ChildItem -Recurse -File $dest -Filter *.class).Count)
}

Write-Host 'STEP 1/4 dependency-closure proof: morph alone must need EXACTLY 3 production types'
$morphSrc = @(Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -File -Filter '*.java' |
    ForEach-Object FullName | Sort-Object)
$probeOut = Join-Path $out 'classes-closureprobe'
if (Test-Path $probeOut) { Remove-Item $probeOut -Recurse -Force }
New-Item -ItemType Directory -Force $probeOut | Out-Null
$argFile = Join-Path $out 'javac-closureprobe.args'
[string[]]$t = @('--release', '17', '-encoding', 'UTF-8', '-proc:none',
    '-d', ('"{0}"' -f ($probeOut -replace '\\', '/')))
$t += @($morphSrc | ForEach-Object { '"{0}"' -f ($_.Replace('\', '/')) })
[IO.File]::WriteAllLines($argFile, $t, [Text.UTF8Encoding]::new($false))
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$probeLog = (& $javac "@$argFile" 2>&1) -join "`n"
$probeExit = $LASTEXITCODE
$ErrorActionPreference = $prev
if ($probeExit -eq 0) {
    throw 'closure probe compiled with ZERO classpath: the morph package has no external dependency at all, which contradicts the recorded 3-type closure. Investigate before trusting it.'
}
$expected = @('ContinentField', 'HeightCurve', 'TerrainParams')
foreach ($n in $expected) {
    if ($probeLog -notmatch [regex]::Escape($n)) {
        throw "closure probe did not report the expected missing type '$n' -- the dependency set changed"
    }
}
# No OTHER com.geogenesis type may be missing, and no third-party package may be missing.
$otherMissing = [regex]::Matches($probeLog, 'symbol:\s+class\s+(\w+)') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique |
    Where-Object { $expected -notcontains $_ }
if ($otherMissing) { throw ("closure probe found UNEXPECTED missing types: " + ($otherMissing -join ', ')) }
if ($probeLog -match 'package (?!com\.geogenesis\.worldgen\.terrain\b)([\w\.]+) does not exist') {
    throw ("closure probe found a missing third-party package: " + $Matches[1])
}
Write-Host '  OK morph needs exactly ContinentField + HeightCurve + TerrainParams, nothing else'

Write-Host 'STEP 2/4 morph package (compiled against real production sources)'
$morphOut = Join-Path $out 'classes-morph'
if (Test-Path $morphOut) { Remove-Item $morphOut -Recurse -Force }
# Morph and its 3 production dependencies are compiled in ONE javac invocation. Compiling them
# separately would need an intermediate output dir on the classpath, which is exactly the
# prebuilt-classes pattern this step exists to avoid.
Compile 'morph' ($morphSrc + $support) $noiseCp $morphOut

Write-Host 'STEP 3/4 parity tool (verification only; not part of the production package)'
$vHead = Join-Path $Preserved 'verified\out\classes-head'
$vCand = Join-Path $Preserved 'verified\out\classes-cand'
$vMain = Join-Path $Preserved 'verified\out\classes-main'
foreach ($p in @($vHead, $vCand, $vMain)) {
    if (-not (Test-Path $p)) { throw "missing preserved candidate classes: $p" }
}
$refCp = "$vCand;$vHead;$vMain"
# The VERIFIED CANDIDATE needs the full frozen jar set at runtime (its LandCompositionGallery
# pulls NightConfig in). That is a property of the reference arm, NOT of the morph package:
# step 2 above compiles morph against DataFixerUpper + fastutil only.
$refRunCp = "$refCp;$($allJars -join ';')"
$paritySrc = @(Get-ChildItem (Join-Path $root 'tools\parity\java') -Recurse -File -Filter '*.java' |
    ForEach-Object FullName | Sort-Object)
$parityOut = Join-Path $out 'classes-parity'
if (Test-Path $parityOut) { Remove-Item $parityOut -Recurse -Force }
Compile 'parity' $paritySrc "$morphOut;$refCp" $parityOut

Write-Host 'STEP 4/4 parity runs (new package vs verified candidate)'
$runCp = "$parityOut;$morphOut;$refRunCp"
function Step([string]$name, [string[]]$jvmArgs, [string[]]$progArgs) {
    $log = Join-Path $rep ($name + '.log')
    & $java @jvmArgs -cp $runCp com.geogenesis.morph.parity.ParityMain @progArgs 2>&1 |
        Tee-Object -FilePath $log | Select-Object -Last 6
    if ($LASTEXITCODE -ne 0) { throw "parity step '$name' exited $LASTEXITCODE (see reports\$name.log)" }
    Write-Host ("  PASS {0}" -f $name)
}

Step 'g1-land-12345'   @('-Xmx4g') @('land', '12345', '241')
Step 'g2-world-12345'  @('-Xmx4g') @('seed', '12345', '241')
Step 'g3-world-0'      @('-Xmx4g') @('seed', '0', '121')
Step 'g4-world-seed2'  @('-Xmx4g') @('seed', '2', '121')
Step 'g5-world-neg1'   @('-Xmx4g') @('seed', '-1', '121')

Write-Host 'STEP 4a/4 range guards (out-of-range must THROW, not saturate)'
$guardLog = Join-Path $rep 'g0-guards.log'
& $java -Xmx2g -cp $runCp com.geogenesis.morph.parity.GuardCheck 2>&1 |
    Tee-Object -FilePath $guardLog | Select-Object -Last 14
if ($LASTEXITCODE -ne 0) { throw "guard check exited $LASTEXITCODE (see reports\g0-guards.log)" }
Write-Host '  PASS g0-guards'

if (-not $SkipCanonical) {
    Write-Host 'STEP 4b/4 canonical 1401^2 (BEA0 anchor) + seed 12345 full image'
    Step 'g6-canonical-1401' @('-Xmx6g') @('canonical')
    Step 'g7-full-12345'     @('-Xmx10g') @('full', '12345')
}

Write-Host 'BUILD PASS'
exit 0
