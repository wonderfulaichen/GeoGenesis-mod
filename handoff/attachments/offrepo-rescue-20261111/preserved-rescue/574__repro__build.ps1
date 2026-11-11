# Reproduce the BEA0 candidate from the sources preserved in THIS directory.
# Self-contained: the only external inputs are the 115 jars recorded in frozen/classpath-jars.txt
# (hashed in deps/jars.json) and a JDK 21. Nothing here reads the temp sandbox or the repo.
#
# Outputs (all under .\out):
#   classes-main     the 50 pure-Java src/main sources the candidate actually reaches
#   classes-head     frozen HEAD float reference tree (131 files, shared by every arm)
#   classes-cand     candidate set + runner mains  -> arm "indexed" / BEA0 producer
#   classes-baseline pristine land (no chunk index) + Xfix geometry -> arm B
#   classes-3a7686   merged 3A7686C5 candidate (old geometry + old land) -> arm A
#   classes-perf     PerfE, compiled once so all arms measure with identical bytecode
#
# Exit code mirrors javac. Any failure aborts non-zero.
#
# ASCII only (Windows PowerShell 5.1 parses this file with the system codepage).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [string]$ClasspathJarsFile = ''
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$build = Join-Path $root '_build'
$out   = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force $out | Out-Null
New-Item -ItemType Directory -Force $build | Out-Null

if (-not $ClasspathJarsFile) { $ClasspathJarsFile = Join-Path $root 'frozen\classpath-jars.txt' }
$jars = ((Get-Content $ClasspathJarsFile -Raw) -replace "`r?`n", '').Trim()
if (-not $jars) { throw "empty jar classpath: $ClasspathJarsFile" }

$javac = Join-Path $JavaHome 'javac.exe'
$javap = Join-Path $JavaHome 'javap.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$res     = Join-Path $root 'resources'
$cpJars  = "$res;$jars"
$cls     = 'com.geogenesis.worldgen.terrain.candidate.'
$cMain   = Join-Path $out 'classes-main'
$cHead   = Join-Path $out 'classes-head'
$cCand   = Join-Path $out 'classes-cand'
$cBase   = Join-Path $out 'classes-baseline'
$c3a     = Join-Path $out 'classes-3a7686'
$cPerf   = Join-Path $out 'classes-perf'

function Src([string]$dir) {
    @(Get-ChildItem -Recurse -File $dir -Filter '*.java' | ForEach-Object FullName | Sort-Object)
}
function Compile([string]$name, [string[]]$sources, [string]$cp, [string]$dest) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    $argFile = Join-Path $build ("javac-$name.args")
    [string[]]$t = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
        '-cp', ('"{0}"' -f ($cp  -replace '\\','/')),
        '-d',  ('"{0}"' -f ($dest -replace '\\','/')))
    $t += @($sources | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
    [IO.File]::WriteAllLines($argFile, $t, [Text.UTF8Encoding]::new($false))
    & $javac "@$argFile"
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "javac failed ($code): $argFile" }
    Write-Host ("  OK {0,-14} {1,4} sources -> {2,4} classes" -f $name, $sources.Count,
        (Get-ChildItem -Recurse -File $dest -Filter *.class).Count)
}

Write-Host 'STEP 1/6 main closure (pure Java, no Minecraft imports)'
$mainSrc = Src (Join-Path $root 'src\main-closure')
if ($mainSrc.Count -ne 50) { throw "expected 50 main-closure sources, found $($mainSrc.Count)" }
Compile 'main' $mainSrc $cpJars $cMain

Write-Host 'STEP 2/6 frozen HEAD float reference tree'
$headSrc = Src (Join-Path $root 'src\head-terrain-candidate')
if ($headSrc.Count -ne 131) { throw "expected 131 head sources, found $($headSrc.Count)" }
Compile 'head' $headSrc "$cMain;$cpJars" $cHead

Write-Host 'STEP 3/6 candidate set + runner mains'
$candSrc = @()
$candSrc += Src (Join-Path $root 'src\candidate')
$candSrc += Src (Join-Path $root 'src\runner-root')
$candSrc += $mainSrc
if ($candSrc.Count -lt 70) { throw "candidate source set too small: $($candSrc.Count)" }
Compile 'cand' $candSrc "$cHead;$cMain;$cpJars" $cCand

Write-Host 'STEP 4/6 pristine arm B (Xfix geometry + land WITHOUT chunk index)'
# Arm B is deliberately NOT the old 3A7686 geometry. Per the frozen identity record:
#   A = geometry CF4FCA7E (old) + land AD971E97 (no index)
#   B = geometry 8C26D068 (Xfix) + land AD971E97 (no index)
#   C = geometry 8C26D068 (Xfix) + land B7C57D80 (chunk index)   <- the BEA0 candidate
# So B takes the candidate's geometry unchanged and only swaps the land/morph file. Getting this
# wrong makes A and B identical and destroys the whole point of the three-arm comparison.
$baseLand = Src (Join-Path $root 'src\baseline-arms')
$baseSrc  = @($candSrc | Where-Object { (Split-Path $_ -Leaf) -ne 'DoubleFullMorphLand.java' }) + $baseLand
Compile 'baseline-arm' $baseSrc "$cHead;$cMain;$cpJars" $cBase

Write-Host 'STEP 5/6 arm A (merged 3A7686C5: old geometry + old land)'
$oldGeom = Src (Join-Path $root 'src\old-geometry')
$aSrc    = @($baseSrc | Where-Object { (Split-Path $_ -Leaf) -ne 'DoubleBoundaryGeometry.java' }) + $oldGeom
Compile '3a7686' $aSrc "$cHead;$cMain;$cpJars" $c3a

Write-Host 'STEP 6/6 perf runner (compiled once, first on every arm)'
$perfSrc = @(Join-Path $root 'src\runner-root\PerfE.java')
Compile 'perf' $perfSrc "$cCand;$cHead;$cMain;$cpJars" $cPerf

Write-Host 'ARM BINDING ASSERTIONS'
# javap MUST get -p; without it the private chunk* members are invisible and every arm
# would silently report "not indexed" -- exactly how an earlier arm audit was fooled.
$armSpecs = @(
    @{ name='cand (C)';     cp="$cCand;$cHead;$cMain;$cpJars";        want=$true  },
    @{ name='baseline (B)'; cp="$cBase;$cCand;$cHead;$cMain;$cpJars"; want=$false },
    @{ name='3a7686 (A)';   cp="$c3a;$cCand;$cHead;$cMain;$cpJars";   want=$false })
foreach ($arm in $armSpecs) {
    $u = & $javap -p -cp $arm.cp "${cls}DoubleFullMorphLand`$Tile"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for arm $($arm.name)" }
    $hasChunk = ($u | Select-String 'chunkStart')       -ne $null
    $hasCull  = ($u | Select-String 'chunkOutsideBand') -ne $null
    if ($hasChunk -ne $arm.want -or $hasCull -ne $arm.want) {
        throw ("arm {0} bound the wrong Tile (chunkStart={1} chunkOutsideBand={2}, want={3})" -f
                $arm.name, $hasChunk, $hasCull, $arm.want)
    }
    Write-Host ("  arm {0,-13} Tile chunkStart={1} chunkOutsideBand={2} (asserted)" -f $arm.name, $hasChunk, $hasCull)
}

# The geometry class must DIFFER between A and B, and be IDENTICAL between B and C. Compiling all
# three to the same bytecode would silently collapse the comparison -- this is the check that
# caught exactly that mistake while this snapshot was being assembled.
$gb = Join-Path $cBase 'com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.class'
$gc = Join-Path $cCand 'com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.class'
$ga = Join-Path $c3a  'com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.class'
$hb = (Get-FileHash $gb -Algorithm SHA256).Hash
$hc = (Get-FileHash $gc -Algorithm SHA256).Hash
$ha = (Get-FileHash $ga -Algorithm SHA256).Hash
if ($hb -ne $hc) { throw "arm B and C must share the same geometry class (B=$hb C=$hc)" }
if ($ha -eq $hb) { throw "arm A and B must NOT share the geometry class (both $ha)" }
Write-Host ("  geometry B==C ({0}) and A!=B ({1}) -- asserted" -f $hb.Substring(0,12), $ha.Substring(0,12))

Write-Host 'SOURCE IDENTITY'
$sha = [Security.Cryptography.SHA256]::Create()
$id  = @()
$idFiles = @(
    'src\candidate\com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.java',
    'src\candidate\com\geogenesis\worldgen\terrain\candidate\DoubleFullMorphLand.java',
    'src\old-geometry\com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.java',
    'src\baseline-arms\com\geogenesis\worldgen\terrain\candidate\DoubleFullMorphLand.java')
foreach ($rel in $idFiles) {
    $p = Join-Path $root $rel
    $h = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($p))) -replace '-',''
    $id += ("{0}  {1}" -f $h, $rel)
    Write-Host ("  {0}  {1}" -f $h, $rel)
}
$id | Set-Content (Join-Path $out 'source-identity.txt') -Encoding utf8

Get-ChildItem $cCand -Recurse -File |
    Get-FileHash -Algorithm SHA256 | Sort-Object Path |
    ForEach-Object { "{0}  {1}" -f $_.Hash, $_.Path.Replace($cCand, '') } |
    Set-Content (Join-Path $out 'class-hashes-cand.txt') -Encoding utf8

Write-Host 'BUILD PASS'
exit 0
