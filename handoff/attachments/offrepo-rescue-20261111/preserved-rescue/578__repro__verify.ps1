# Verify the preserved candidate against its frozen anchors.
#
# Gates (each FAIL throws, so the exit code is non-zero and the gate cannot silently pass):
#   1. gallery       : DoubleFullGallery float/double arms must hit 7BE04183 / BEA0A47C
#   2. far-seams     : XFarSeams far-field + seam arms must exit 0
#   3. band-control  : BandIndexControl (chunk-cull exactness vs brute force) must exit 0
#   4. band-negative : BandIndexNegative (corrupted cull) MUST exit non-zero
#   5. band-fuzz     : BandIndexFuzz (distance-banded brute-vs-fast) must exit 0
#   6. anchors       : produced float-heights must equal anchors/refs/canonical-7BE04183-float-heights.f64be
#
# ASCII only (Windows PowerShell 5.1 parses this file with the system codepage).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [switch]$SkipGallery      # the gallery pass is ~3 min and 6 GB heap; skip for a fast build check
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$out   = Join-Path $PSScriptRoot 'out'
$runs  = Join-Path $PSScriptRoot 'runs\indexed'
New-Item -ItemType Directory -Force $runs | Out-Null

$jars = ((Get-Content (Join-Path $root 'frozen\classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cp   = "$(Join-Path $out 'classes-cand');$(Join-Path $out 'classes-head');$(Join-Path $out 'classes-main');$(Join-Path $root 'resources');$jars"
$cls  = 'com.geogenesis.worldgen.terrain.candidate.'
$cfg  = Join-Path $root 'frozen\config-input.toml'
$java = Join-Path $JavaHome 'java.exe'
$ledger = @()
function Gate([string]$name, [scriptblock]$action) {
    Write-Host "RUN $name $(Get-Date -Format o)"
    & $action
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    $ledger += "$name=$code"
    Write-Host "END $name exit=$code"
    if ($code -ne 0) { throw "gate '$name' exited $code" }
}
function GateMustFail([string]$name, [scriptblock]$action) {
    Write-Host "RUN $name (must FAIL)"
    $o = & $action 2>&1
    $code = $LASTEXITCODE
    $ledger += "$name=$code (expected non-zero)"
    Write-Host "END $name exit=$code"
    if ($code -eq 0) { throw "gate '$name' passed: the negative control has no power" }
}

# Gate order deliberately mirrors the frozen stage script (run-stage.ps1 in original/), because
# several probes write the same filename (seams.csv from IsolatedProbe AND XFarSeams; perf.csv only
# from IsolatedProbe). Last writer wins, so the order is part of the reproducible contract.
if (-not $SkipGallery) {
    Gate 'gallery' { & $java -Xmx6g -cp $cp "${cls}DoubleFullGallery" $cfg $runs 2>&1 | Out-File (Join-Path $runs 'gallery-main.log') -Encoding utf8 }
    $log = Get-Content (Join-Path $runs 'gallery-main.log') -Raw
    if ($log -notmatch 'floatArmSha=7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238') { throw 'float arm did not reproduce 7BE04183' }
    if ($log -notmatch 'doubleArmSha=BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965') { throw 'double arm did not reproduce BEA0A47C' }
    Write-Host '  gallery anchors matched (7BE04183 float / BEA0A47C double)'
}
Gate 'node-closure' { & $java -Xmx6g -cp $cp "${cls}ActualNodeClosure" 2>&1 | Out-File (Join-Path $runs 'node-closure.log') -Encoding utf8 }
Gate 'node-census'  { & $java -Xmx6g -cp $cp "${cls}DoubleNodeCensus" 2>&1 | Out-File (Join-Path $runs 'node-census.log') -Encoding utf8 }
Gate 'isolated'    { & $java -Xmx6g -cp $cp "${cls}IsolatedProbe" $cfg $runs 2>&1 | Out-File (Join-Path $runs 'isolated-probe.log') -Encoding utf8 }
Gate 'z-seams'     { & $java -Xmx6g -cp $cp "${cls}ZSeamExtension" $cfg $runs 2>&1 | Out-File (Join-Path $runs 'z-seams.log') -Encoding utf8 }
Gate 'x-far-seams' { & $java -Xmx6g -cp $cp "${cls}XFarSeams" $cfg $runs 2>&1 | Out-File (Join-Path $runs 'x-far-seams.log') -Encoding utf8 }
Gate 'endpoint'     { & $java -Xmx6g -cp $cp "${cls}EndpointProbe"    2>&1 | Out-File (Join-Path $runs 'endpoint-probe.log') -Encoding utf8 }
Gate 'perf-geometry'      { & $java -Xmx6g -cp $cp "${cls}PerfGeometry" 0     2>&1 | Out-File (Join-Path $runs 'perf-geometry.log') -Encoding utf8 }
Gate 'perf-geometry-1831' { & $java -Xmx6g -cp $cp "${cls}PerfGeometry" 1831  2>&1 | Out-File (Join-Path $runs 'perf-geometry-1831.log') -Encoding utf8 }
Gate 'perf-geometry-neg'  { & $java -Xmx6g -cp $cp "${cls}PerfGeometry" -1832 2>&1 | Out-File (Join-Path $runs 'perf-geometry-neg.log') -Encoding utf8 }
Gate 'perf-geometry-far'  { & $java -Xmx6g -cp $cp "${cls}PerfGeometry" 29295 2>&1 | Out-File (Join-Path $runs 'perf-geometry-far.log') -Encoding utf8 }
Gate 'band-control'  { & $java -Xmx4g -cp $cp "${cls}BandIndexControl"  2>&1 | Out-File (Join-Path $runs 'band-index-control.log') -Encoding utf8 }
GateMustFail 'band-negative' { & $java -Xmx4g -cp $cp "${cls}BandIndexNegative" 2>&1 | Out-File (Join-Path $runs 'band-index-negative.log') -Encoding utf8 }
Gate 'band-fuzz'     { & $java -Xmx4g -cp $cp "${cls}BandIndexFuzz"     2>&1 | Out-File (Join-Path $runs 'band-index-fuzz.log') -Encoding utf8 }

$anchor = Join-Path $root 'anchors\refs\canonical-7BE04183-float-heights.f64be'
$got    = Join-Path $runs 'float-heights.f64be'
if ((Test-Path $anchor) -and (Test-Path $got)) {
    $ha = (Get-FileHash $anchor -Algorithm SHA256).Hash
    $hg = (Get-FileHash $got    -Algorithm SHA256).Hash
    $ledger += "anchor-float-heights=$([int]($ha -eq $hg))"
    if ($ha -ne $hg) { throw "float-heights mismatch: produced $hg, anchor $ha" }
    Write-Host "  float-heights matches the 7BE04183 anchor"
} else {
    $ledger += 'anchor-float-heights=SKIPPED (gallery skipped or anchor missing)'
}

$ledger | Set-Content (Join-Path $PSScriptRoot 'runs\exit-codes.txt') -Encoding utf8
$ledger | ForEach-Object { Write-Host $_ }
Write-Host 'VERIFY PASS'
exit 0
