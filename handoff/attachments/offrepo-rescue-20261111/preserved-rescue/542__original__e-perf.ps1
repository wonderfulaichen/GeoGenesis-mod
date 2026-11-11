# Full e-chain perf, three arms, interleaved with balanced (rotated) order, one fresh JVM per
# measurement. ASCII only (PowerShell 5.1 CJK parsing).
#
#   A = original 3A7686C5 candidate (old geometry CF4FCA7E + land AD971E97)   -> classes-3a7686
#   B = new geometry 8C26D068 + pristine land AD971E97 (NO chunk index)       -> classes-baseline
#   C = new geometry 8C26D068 + chunk-index land (sqrt-form cull)             -> classes-cand
#
# Fixes vs the previous version:
#   * arm identity is asserted with `javap -p`; without -p the private chunk* fields are invisible,
#     so the old check printed False for every arm and verified nothing;
#   * the runner PerfE is compiled ONCE into classes-perf and placed FIRST on all three arms, so the
#     measuring bytecode is identical across arms and cannot skew the comparison;
#   * arm B no longer relies on classes-cand being shadowed for the runner.
param([string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'D:\Office software\Development Project\GeoGenesis-mod'
$base = Join-Path $repo 'forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline'
$jars = ((Get-Content (Join-Path $base 'classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cpbase = "$base\classes;$base\src-main\resources;$jars"
$cls = 'com.geogenesis.worldgen.terrain.candidate.'
$config = Join-Path $base 'config-input.toml'

# ---- Arm A: compile the merged 3A7686 candidate set
$mergedRoot = "$env:TEMP\geogenesis-merged-exclusive-9129fd9c6b6c4cd2814b169d6ead5320"
$headSrc = Join-Path $mergedRoot 'src-head/java/com/geogenesis/worldgen/terrain/candidate'
$classesA = Join-Path $root 'classes-3a7686'
New-Item -ItemType Directory -Force $classesA | Out-Null
$srcA = @(Get-ChildItem "$mergedRoot/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/Double*.java" |
    Where-Object { $_.Name -notin @('DoubleFullMorphLand.java','DoubleBoundaryGeometry.java') } |
    ForEach-Object FullName)
$srcA += @(Get-ChildItem $headSrc -Filter *.java | ForEach-Object FullName)
$srcA += @(
    "$mergedRoot\diagnostics\java\com\geogenesis\worldgen\terrain\candidate\DoubleFullMorphLand.java",
    "$mergedRoot\diagnostics\java\com\geogenesis\worldgen\terrain\candidate\DoubleBoundaryGeometry.java",
    "$env:TEMP\geogenesis-double-tree-479b36a00abe4bd7a88f8c6d6a4e7cf3\DoublePerlinCandidate.java",
    "$root/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DualCoordinateCall.java",
    "$root/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/ActualNodeClosure.java")
$argA = Join-Path $root 'javac-3a7686.args'
[string[]]$t = @('--release','17','-encoding','UTF-8','-nowarn','-proc:none',
    '-cp', ('"{0}"' -f ($cpbase -replace '\\','/')),
    '-d', ('"{0}"' -f ($classesA -replace '\\','/')))
$t += @($srcA | ForEach-Object { '"{0}"' -f ($_.Replace('\','/')) })
[IO.File]::WriteAllLines($argA, $t, [Text.UTF8Encoding]::new($false))
& "$JavaHome/javac.exe" "@$argA"
if ($LASTEXITCODE -ne 0) { throw 'javac arm A failed' }
Write-Host 'COMPILE ARM-A (3A7686) PASS'

$cpA = "$classesA;$root\classes-head;$cpbase"
$cpB = "$root\classes-baseline;$root\classes-cand;$root\classes-head;$cpbase"
$cpC = "$root\classes-cand;$root\classes-head;$cpbase"

# ---- runner compiled once, first on every arm
$classesPerf = Join-Path $root 'classes-perf'
New-Item -ItemType Directory -Force $classesPerf | Out-Null
& "$JavaHome/javac.exe" --release 17 -encoding UTF-8 -nowarn -proc:none -cp $cpC -d $classesPerf (Join-Path $root 'PerfE.java')
if ($LASTEXITCODE -ne 0) { throw 'javac PerfE failed' }
Write-Host 'COMPILE RUNNER PASS'
$cpA = "$classesPerf;$cpA"; $cpB = "$classesPerf;$cpB"; $cpC = "$classesPerf;$cpC"

# ---- arm identity assertion (javap -p; the private index fields and cull method must match)
$audit = @()
foreach ($pair in @(@('A',$cpA,$false,$false), @('B',$cpB,$false,$false), @('C',$cpC,$true,$true))) {
    $arm = $pair[0]; $cp = $pair[1]; $wantChunk = $pair[2]; $wantCull = $pair[3]
    $u = & "$JavaHome\javap.exe" -p -cp $cp 'com.geogenesis.worldgen.terrain.candidate.DoubleFullMorphLand$Tile'
    $hasChunk = ($u | Select-String 'chunkStart') -ne $null
    $hasCull = ($u | Select-String 'chunkOutsideBand') -ne $null
    if ($hasChunk -ne $wantChunk -or $hasCull -ne $wantCull) {
        throw "arm $arm wrong Tile (chunkStart=$hasChunk want=$wantChunk, chunkOutsideBand=$hasCull want=$wantCull)"
    }
    $g = & "$JavaHome\javap.exe" -p -c -cp $cp 'com.geogenesis.worldgen.terrain.candidate.DoubleBoundaryGeometry' |
        Select-String 'dtrace|BiomeSource'
    $audit += ("ARM {0}: chunkStart={1} chunkOutsideBand={2} (asserted)" -f $arm, $hasChunk, $hasCull)
    Write-Host $audit[-1]
}

$rounds = 5
$zones = @(0, 1831, -1832, 29295)
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add('round,arm,zone,firstEms,full10kEms,land10kEms,sumFull,sumLand')
$orders = @(@('A','B','C'), @('C','B','A'), @('B','C','A'), @('A','C','B'), @('C','A','B'))
for ($round = 0; $round -lt $rounds; $round++) {
    $order = $orders[$round % $orders.Count]
    Write-Host ("ROUND {0} order {1}" -f $round, ($order -join ','))
    foreach ($arm in $order) {
        $cp = switch ($arm) { 'A' { $cpA } 'B' { $cpB } default { $cpC } }
        foreach ($z in $zones) {
            $out = & java -Xmx8g -cp $cp "${cls}PerfE" $z $config 2>$null
            foreach ($line in $out) { if ($line -match '^-?\d+,') { $rows.Add("$round,$arm,$line") } }
        }
    }
}
$rows | Set-Content (Join-Path $root 'e-perf.csv') -Encoding utf8
$audit | Set-Content (Join-Path $root 'e-perf-arms.txt') -Encoding utf8
Write-Host 'E PERF DONE'
