# Interleaved A/B perf on the tile path: tile build (geometry + index) vs sampling, same geometry
# on both arms so the only difference is the chunk index. ASCII only (PowerShell 5.1).
#
#   baseline  = pristine DoubleFullMorphLand AD971E97 (no chunk index)
#   optimized = chunk-index DoubleFullMorphLand (sqrt-form chunk cull)
#
# Round order alternates; every measurement is a fresh JVM; arm identity is asserted with javap -p
# because without -p the private chunk* fields are invisible and the check would pass for nothing.
param([string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$base = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"
$cls = 'com.geogenesis.worldgen.terrain.candidate.'

$origDir = Join-Path $root 'classes-baseline'
New-Item -ItemType Directory -Force $origDir | Out-Null
$xoptCand = Join-Path $env:TEMP 'geogenesis-xopt-90834f08a3e84eb3ad44d2b0c4b986ca/diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
$pristine = Join-Path $xoptCand 'DoubleFullMorphLand.java'
$deps = @(Get-ChildItem "$xoptCand/Double*.java" | Where-Object { $_.Name -ne 'DoubleFullMorphLand.java' } | ForEach-Object FullName) +
    @("$xoptCand/DualCoordinateCall.java")
$cpBase = "$root\classes-cand;$root\classes-head;$cpbase"
$argF = Join-Path $root 'javac-baseline-arm.args'
[string[]]$t = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}"' -f ($cpBase -replace '\\','/')),
    '-d', ('"{0}"' -f ($origDir -replace '\\','/')),
    ('"{0}"' -f ($pristine -replace '\\','/')))
$t += @($deps | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argF, $t, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argF"
if ($LASTEXITCODE -ne 0) { throw 'javac baseline arm failed' }
Write-Host 'COMPILE BASELINE-ARM PASS'

$cpBaseArm = "$origDir;$root\classes-cand;$root\classes-head;$cpbase"
$cpOptArm  = "$root\classes-cand;$root\classes-head;$cpbase"

foreach ($pair in @(@('baseline',$cpBaseArm,$false), @('optimized',$cpOptArm,$true))) {
    $arm = $pair[0]; $cp = $pair[1]; $want = $pair[2]
    $u = & "$JavaHome\javap.exe" -p -cp $cp 'com.geogenesis.worldgen.terrain.candidate.DoubleFullMorphLand$Tile'
    $hasChunk = ($u | Select-String 'chunkStart') -ne $null
    $hasCull = ($u | Select-String 'chunkOutsideBand') -ne $null
    if ($hasChunk -ne $want -or $hasCull -ne $want) { throw "arm $arm wrong Tile (chunkStart=$hasChunk cull=$hasCull want=$want)" }
    Write-Host ("ARM {0}: chunkStart={1} chunkOutsideBand={2} (asserted)" -f $arm, $hasChunk, $hasCull)
}

$zones = @(0, 1831, -1832, 29295)
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add('round,arm,zone,iter,buildMs,sampleMs,edges,segments,maxSeg,sum')
for ($round = 0; $round -lt 5; $round++) {
    $order = if ($round % 2 -eq 0) { @('baseline','optimized') } else { @('optimized','baseline') }
    foreach ($arm in $order) {
        $cpArm = if ($arm -eq 'baseline') { $cpBaseArm } else { $cpOptArm }
        foreach ($z in $zones) {
            $out = & java -Xmx8g -cp $cpArm "${cls}PerfGeometry" $z 2>$null
            foreach ($line in $out) {
                # -? is required: negative zones emit "-1832,..." and a ^\d+, filter silently
                # dropped them, which is why the earlier ab-perf.csv had no neg zone at all.
                if ($line -match '^-?\d+,') { $rows.Add("$round,$arm,$line") }
            }
        }
    }
}
$rows | Set-Content (Join-Path $root 'ab-perf.csv') -Encoding utf8
Write-Host 'AB PERF DONE'
