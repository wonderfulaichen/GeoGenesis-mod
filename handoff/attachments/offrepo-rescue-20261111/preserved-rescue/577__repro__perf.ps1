# Reproduce the 3-arm interleaved perf comparison (A = merged 3A7686C5, B = pristine land, C = BEA0).
#
# Protocol (inherited from the sandbox's perf2.ps1, which is the corrected version):
#   * one fresh JVM per measurement, rotated arm order so drift cannot favour an arm;
#   * the measuring runner PerfE2 is compiled ONCE into classes-perf2 and placed FIRST on all
#     three arms, so the measuring bytecode is identical and cannot skew the comparison;
#   * arm identity is asserted with `javap -p` before any timing is taken;
#   * each row carries an FNV checksum over the raw double bits, so a faster arm that computes
#     something else is detectable.
#
# -Rounds defaults to the sandbox's 6. Reduce it for a quick smoke test; the numbers are only
# comparable at the same round count.
#
# ASCII only (Windows PowerShell 5.1 parses this file with the system codepage).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [int]$Rounds = 6
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$out   = Join-Path $PSScriptRoot 'out'
$perf2 = Join-Path $out 'classes-perf2'
$runs  = Join-Path $PSScriptRoot 'runs\perf'
New-Item -ItemType Directory -Force $runs | Out-Null
New-Item -ItemType Directory -Force $perf2 | Out-Null

$javac = Join-Path $JavaHome 'javac.exe'
$javap = Join-Path $JavaHome 'javap.exe'
$java  = Join-Path $JavaHome 'java.exe'
$jars  = ((Get-Content (Join-Path $root 'frozen\classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$res   = Join-Path $root 'resources'
$cfg   = Join-Path $root 'frozen\config-input.toml'
$cls   = 'com.geogenesis.worldgen.terrain.candidate.'

# PerfE2 + LocalSeamOnly compile against the C arm and are then pinned first on every arm.
$mSrc = @((Join-Path $root 'src\runner-root\PerfE2.java'), (Join-Path $root 'src\runner-root\LocalSeamOnly.java'))
foreach ($s in $mSrc) { if (-not (Test-Path $s)) { throw "missing measuring source: $s" } }
& $javac --release 17 -encoding UTF-8 -nowarn -proc:none `
    -cp "$(Join-Path $out 'classes-cand');$(Join-Path $out 'classes-head');$(Join-Path $out 'classes-main');$res;$jars" `
    -d $perf2 @mSrc
if ($LASTEXITCODE -ne 0) { throw 'javac PerfE2 failed' }
Write-Host 'COMPILE MEASURING RUNNER PASS'

$common = "$(Join-Path $out 'classes-head');$(Join-Path $out 'classes-main');$res;$jars"
$cps = @{
    A = "$perf2;$(Join-Path $out 'classes-3a7686');$common"
    B = "$perf2;$(Join-Path $out 'classes-baseline');$(Join-Path $out 'classes-cand');$common"
    C = "$perf2;$(Join-Path $out 'classes-cand');$common"
}
$audit = @()
foreach ($arm in @('A','B','C')) {
    $u = & $javap -p -cp $cps[$arm] "${cls}DoubleFullMorphLand`$Tile"
    if ($LASTEXITCODE -ne 0) { throw "javap failed for arm $arm" }
    $found = ($u | Select-String 'chunkStart') -ne $null
    $want  = ($arm -eq 'C')
    if ($found -ne $want) { throw "arm $arm loaded the wrong Tile (chunkStart=$found, want=$want)" }
    $audit += ("ARM {0}: chunkStart={1} (asserted)" -f $arm, $found)
    Write-Host $audit[-1]
}

$orders = @('ABC','ACB','BAC','BCA','CAB','CBA')
$zones  = @(0, 1831, -1832, 29295)
$csv    = Join-Path $runs 'perf2.csv'
'round,position,arm,zone,batch,elapsed_ns,hash,sum' | Set-Content $csv -Encoding ASCII

for ($r = 0; $r -lt $Rounds; $r++) {
    $order = $orders[$r % $orders.Count]
    for ($pos = 0; $pos -lt 3; $pos++) {
        $arm = [string]$order[$pos]
        foreach ($z in $zones) {
            $o = & $java -Xmx8g -cp $cps[$arm] "${cls}PerfE2" $z $cfg 2>&1
            $code = $LASTEXITCODE
            if ($code -ne 0) { throw "PerfE2 failed: round=$r arm=$arm zone=$z exit=$code`n$o" }
            $data = @($o | Where-Object { "$_" -match '^-?\d+,\d+,\d+,[0-9a-f]{16},' })
            if ($data.Count -ne 25) { throw "unexpected row count $($data.Count) for round=$r arm=$arm zone=$z" }
            $data | ForEach-Object { "$r,$pos,$arm,$_" } | Add-Content $csv -Encoding ASCII
        }
        Write-Host ("  round {0} arm {1} done" -f $r, $arm)
    }
}
$audit | Set-Content (Join-Path $runs 'arms.txt') -Encoding utf8
Write-Host ("PERF DONE rounds={0} rows={1}" -f $Rounds, (Get-Content $csv).Count)
exit 0
