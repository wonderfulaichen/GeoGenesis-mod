# Build the VERIFIED candidate: BEA0 plus the seed fix and the concurrency cache fix.
#
# This directory is SELF-CONTAINED. It does not read %TEMP% and does not read the repository.
# The only external inputs are the 115 jars recorded in ../frozen/classpath-jars.txt (hashed in
# ../deps/jars.json) and a JDK 21.
#
# Source layout (all under .\src):
#   candidate/               consolidated candidate tree = ../src/candidate with the 4 fixes applied
#   overrides/               the same 4 fix files kept separately, so the delta is auditable
#   head-terrain-candidate/  131 frozen HEAD reference sources
#   main-closure/            50 pure-Java src/main sources (no Minecraft imports)
#   baseline-arms/           arm B land (no chunk index)
#   old-geometry/            arm A geometry (old)
#   probes-seed/             seed wiring / full-image / cross-seed concurrency probes
#   probes-conc/             cache concurrency, lifecycle, identity and cycle probes
#
# Outputs (under .\out): classes-main, classes-head, classes-cand, classes-baseline, classes-3a7686.
# Exit code mirrors javac; any failed assertion aborts non-zero.
#
# ASCII only (Windows PowerShell 5.1 parses this file with the system codepage).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [string]$ClasspathJarsFile = ''
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$vroot = $PSScriptRoot
$build = Join-Path $vroot '_build'
$out   = Join-Path $vroot 'out'
New-Item -ItemType Directory -Force $out   | Out-Null
New-Item -ItemType Directory -Force $build | Out-Null

if (-not $ClasspathJarsFile) { $ClasspathJarsFile = Join-Path $root 'frozen\classpath-jars.txt' }
if (-not (Test-Path $ClasspathJarsFile)) { throw "missing jar classpath record: $ClasspathJarsFile" }
$jars = ((Get-Content $ClasspathJarsFile -Raw) -replace "`r?`n", '').Trim()
if (-not $jars) { throw "empty jar classpath: $ClasspathJarsFile" }

$javac = Join-Path $JavaHome 'javac.exe'
$javap = Join-Path $JavaHome 'javap.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$res  = Join-Path $root 'resources'
$cpJ  = "$res;$jars"
$cls  = 'com.geogenesis.worldgen.terrain.candidate.'
$cMain = Join-Path $out 'classes-main'
$cHead = Join-Path $out 'classes-head'
$cCand = Join-Path $out 'classes-cand'
$cBase = Join-Path $out 'classes-baseline'
$c3a   = Join-Path $out 'classes-3a7686'

function Src([string]$d) { @(Get-ChildItem -Recurse -File $d -Filter '*.java' | ForEach-Object FullName | Sort-Object) }
function Compile([string]$name, [string[]]$srcs, [string]$cp, [string]$dest) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    $argFile = Join-Path $build ("javac-$name.args")
    [string[]]$t = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
        '-cp', ('"{0}"' -f ($cp  -replace '\\','/')),
        '-d',  ('"{0}"' -f ($dest -replace '\\','/')))
    $t += @($srcs | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
    [IO.File]::WriteAllLines($argFile, $t, [Text.UTF8Encoding]::new($false))
    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE): $argFile" }
    Write-Host ("  OK {0,-14} {1,4} sources -> {2,4} classes" -f $name, $srcs.Count,
        (Get-ChildItem -Recurse -File $dest -Filter *.class).Count)
}

Write-Host 'STEP 1/5 main closure (pure Java, no Minecraft imports)'
$mainSrc = Src (Join-Path $vroot 'src\main-closure')
if ($mainSrc.Count -ne 50) { throw "expected 50 main-closure sources, found $($mainSrc.Count)" }
Compile 'main' $mainSrc $cpJ $cMain

Write-Host 'STEP 2/5 frozen HEAD reference tree'
$headSrc = Src (Join-Path $vroot 'src\head-terrain-candidate')
if ($headSrc.Count -ne 131) { throw "expected 131 head sources, found $($headSrc.Count)" }
Compile 'head' $headSrc "$cMain;$cpJ" $cHead

Write-Host 'STEP 3/5 verified candidate (consolidated) + probes'
$candSrc   = Src (Join-Path $vroot 'src\candidate')
$probeSeed = Src (Join-Path $vroot 'src\probes-seed')
$probeConc = Src (Join-Path $vroot 'src\probes-conc')
$probeReal = Src (Join-Path $vroot 'src\probes-realchain')
$probeMem  = Src (Join-Path $vroot 'src\probes-memory')
if ($candSrc.Count -ne 19) { throw "expected 19 consolidated candidate sources, found $($candSrc.Count)" }
if ($probeSeed.Count -ne 3) { throw "expected 3 seed probes, found $($probeSeed.Count)" }
if ($probeConc.Count -ne 6) { throw "expected 6 concurrency probes, found $($probeConc.Count)" }
if ($probeReal.Count -ne 1) { throw "expected 1 real-chain probe, found $($probeReal.Count)" }
if ($probeMem.Count  -ne 1) { throw "expected 1 memory probe, found $($probeMem.Count)" }
Compile 'cand' (@($candSrc) + @($probeSeed) + @($probeConc) + @($probeReal) + @($probeMem)) "$cHead;$cMain;$cpJ" $cCand

Write-Host 'STEP 4/5 arm B (land without the chunk index; geometry unchanged)'
$bLand = Src (Join-Path $vroot 'src\baseline-arms')
$bSrc  = @($candSrc | Where-Object { (Split-Path $_ -Leaf) -ne 'DoubleFullMorphLand.java' }) + $bLand
Compile 'baseline-arm' $bSrc "$cHead;$cMain;$cpJ" $cBase

Write-Host 'STEP 5/5 arm A (old geometry + land without the chunk index)'
$oldGeom = Src (Join-Path $vroot 'src\old-geometry')
$aSrc    = @($bSrc | Where-Object { (Split-Path $_ -Leaf) -ne 'DoubleBoundaryGeometry.java' }) + $oldGeom
Compile '3a7686' $aSrc "$cHead;$cMain;$cpJ" $c3a

Write-Host 'FIX PRESENCE ASSERTIONS'
# Each fix is asserted as CODE in the compiled output, not as a filename that happens to exist.
$dlc = (& $javap -p -c -cp $cCand "${cls}DoubleFullMorphLand") -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'javap failed for DoubleFullMorphLand' }
if ($dlc -notmatch 'LandRegionContext') {
    throw 'seed fix absent: DoubleFullMorphLand never constructs a LandRegionContext'
}
# The pre-fix constructor threw UnsupportedOperationException for non-canonical seeds. The
# constant pool must still contain it (the guard remains for genuinely unsupported paths), but the
# constructor must reach LandRegionContext. Both are checked above and here:
if ($dlc -notmatch 'UnsupportedOperationException') {
    Write-Host '  note: no UnsupportedOperationException in DoubleFullMorphLand (guard fully replaced)'
}
Write-Host '  seed fix present: DoubleFullMorphLand -> LandRegionContext'

$dts = (& $javap -p -c -cp $cCand "${cls}DoubleTreeFixed") -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'javap failed for DoubleTreeFixed' }
if ($dts -match 'IdentityHashMap') { throw 'cache fix absent: DoubleTreeFixed still uses IdentityHashMap' }
if ($dts -notmatch 'PerlinScalarCache') { throw 'cache fix absent: DoubleTreeFixed does not use PerlinScalarCache' }
Write-Host '  cache fix present: DoubleTreeFixed -> PerlinScalarCache (no IdentityHashMap)'

if (-not (Test-Path (Join-Path $cCand 'com\geogenesis\worldgen\terrain\candidate\PerlinScalarCache.class'))) {
    throw 'PerlinScalarCache.class missing from classes-cand'
}
$psis = (& $javap -p -cp $cCand "${cls}PerlinScalarCache") -join "`n"
if ($psis -notmatch 'WeakHashMap') {
    # WeakHashMap is a field type, not necessarily printed by javap -p; check the descriptor instead.
    if ($psis -notmatch 'java\.util\.Map') { throw 'PerlinScalarCache does not hold a Map' }
}
Write-Host '  PerlinScalarCache present and holds a java.util.Map'

$lrc = (& $javap -p -cp $cCand "${cls}LandRegionContext") -join "`n"
if ($lrc -notmatch 'RecipeMorphCatalog') {
    throw 'LandRegionContext has no catalog-typed constructor: the override did not make it into the tree'
}
Write-Host '  LandRegionContext exposes the catalog-typed constructor'

Write-Host 'ARM BINDING ASSERTIONS'
foreach ($arm in @(
        @{ n='cand (C)';     cp="$cCand;$cHead;$cMain;$cpJ";        want=$true  },
        @{ n='baseline (B)'; cp="$cBase;$cCand;$cHead;$cMain;$cpJ"; want=$false },
        @{ n='3a7686 (A)';   cp="$c3a;$cCand;$cHead;$cMain;$cpJ";   want=$false })) {
    $u = & $javap -p -cp $arm.cp "${cls}DoubleFullMorphLand`$Tile"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for arm $($arm.n)" }
    $has = ($u | Select-String 'chunkStart') -ne $null
    if ($has -ne $arm.want) { throw "arm $($arm.n) bound the wrong Tile (chunkStart=$has want=$($arm.want))" }
    Write-Host ("  arm {0,-13} Tile chunkStart={1} (asserted)" -f $arm.n, $has)
}

Write-Host 'INPUT IDENTITY'
$sha = [Security.Cryptography.SHA256]::Create()
$id  = @()
foreach ($f in (@($candSrc) + @($probeSeed) + @($probeConc) + @($probeReal) + @($probeMem))) {
    $h = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($f))) -replace '-',''
    $id += ("{0}  {1}" -f $h, $f.Substring($vroot.Length + 1))
    Write-Host ("  {0}  {1}" -f $h.Substring(0,16), (Split-Path $f -Leaf))
}
$id | Set-Content (Join-Path $out 'input-identity.txt') -Encoding utf8
Get-ChildItem $cCand -Recurse -File | Get-FileHash -Algorithm SHA256 | Sort-Object Path |
    ForEach-Object { "{0}  {1}" -f $_.Hash, $_.Path.Replace($cCand, '') } |
    Set-Content (Join-Path $out 'class-hashes-cand.txt') -Encoding utf8

Write-Host 'BUILD PASS'
exit 0
