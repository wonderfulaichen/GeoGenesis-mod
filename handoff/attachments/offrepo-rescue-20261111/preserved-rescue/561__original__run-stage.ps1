# Stage runner: serial, per-arm classpath (ASCII only).
#   -Arm pristine : classes-baseline FIRST -> DoubleFullMorphLand* resolves to the pristine
#                   (no chunk index) classes; classes-cand stays on the path only so the shared
#                   runner mains (IsolatedProbe/ZSeamExtension/...) resolve. Ordering is asserted
#                   below with javap, so a silent shadowing cannot slip through.
#   -Arm indexed  : classes-cand FIRST     -> DoubleFullMorphLand$Tile WITH chunk index
# The previous version used classes-cand for both arms, so the two stages were the same code and
# their identical outputs proved nothing. Arm binding is now explicit and asserted per run.
param(
    [Parameter(Mandatory=$true)][ValidateSet('pristine','indexed')][string]$Arm,
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [string]$Heap = '8g'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$base = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"
if ($Arm -eq 'pristine') {
    $cp = "$root\classes-baseline;$root\classes-cand;$root\classes-head;$cpbase"
} else {
    $cp = "$root\classes-cand;$root\classes-head;$cpbase"
}
$cls = 'com.geogenesis.worldgen.terrain.candidate.'
$config = Join-Path $base 'config-input.toml'
$out = Join-Path $root $Arm
New-Item -ItemType Directory -Force $out | Out-Null
$status = Join-Path $root "exit-codes-$Arm.txt"

# --- assert the loaded Tile really is the intended arm (no silent shadowing)
# javap must be given -p: without it private members are hidden, chunkStart is invisible and the
# check silently reports False for every arm (that is how the earlier e-perf arm audit was fooled).
$u = & "$JavaHome\javap.exe" -p -cp $cp 'com.geogenesis.worldgen.terrain.candidate.DoubleFullMorphLand$Tile'
$hasChunk = ($u | Select-String 'chunkStart') -ne $null
$hasChunkProbe = ($u | Select-String 'chunkOutsideBand') -ne $null
$want = ($Arm -eq 'indexed')
if ($hasChunk -ne $want -or $hasChunkProbe -ne $want) {
    throw "arm $Arm loaded the wrong Tile (chunkStart=$hasChunk, chunkOutsideBand=$hasChunkProbe, want=$want)"
}
Write-Host ("ARM {0}: Tile chunkStart={1} chunkOutsideBand={2} (asserted)" -f $Arm, $hasChunk, $hasChunkProbe)

Set-Content -Path $status -Value "# arm=$Arm chunkStart=$hasChunk chunkOutsideBand=$hasChunkProbe" -Encoding utf8

function run([string]$name, [scriptblock]$action) {
    Write-Host "START $name $(Get-Date -Format o)"
    & $action 2>&1 | Out-File (Join-Path $out "$name.log") -Encoding utf8
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    "$name=$code" | Add-Content $status
    Write-Host "END $name exit=$code $(Get-Date -Format o)"
    if ($code -ne 0) { throw "gate failure: $name exited $code (see $name.log)" }
}

$jvm = "-Xmx$Heap"
run 'node-closure' { & java $jvm -cp $cp "${cls}ActualNodeClosure" }
run 'node-census'  { & java $jvm -cp $cp "${cls}DoubleNodeCensus" }
run 'gallery-main' { & java $jvm -cp $cp "${cls}DoubleFullGallery" $config $out }
run 'isolated-probe' { & java $jvm -cp $cp "${cls}IsolatedProbe" $config $out }
run 'z-seams' { & java $jvm -cp $cp "${cls}ZSeamExtension" $config $out }
run 'x-far-seams' { & java $jvm -cp $cp "${cls}XFarSeams" $config $out }
run 'endpoint-probe' { & java $jvm -cp $cp "${cls}EndpointProbe" }
run 'perf-geometry' { & java $jvm -cp $cp "${cls}PerfGeometry" 0 }
run 'perf-geometry-1831' { & java $jvm -cp $cp "${cls}PerfGeometry" 1831 }
run 'perf-geometry-neg' { & java $jvm -cp $cp "${cls}PerfGeometry" -1832 }
run 'perf-geometry-far' { & java $jvm -cp $cp "${cls}PerfGeometry" 29295 }
Write-Host "STAGE $Arm FINISHED"
