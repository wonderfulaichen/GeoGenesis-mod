# One single serial final pass: build -> probes -> both stage arms -> perf -> figures.
# Serial on purpose: the perf numbers are only comparable if nothing else of mine is running.
# ASCII only (PowerShell 5.1 parses this file with the system codepage).
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$java = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$cls = 'com.geogenesis.worldgen.terrain.candidate.'
$log = Join-Path $root 'final-serial.log'
Set-Content -Path $log -Value "# final serial pass $(Get-Date -Format o)" -Encoding utf8

function step([string]$name, [scriptblock]$action) {
    $t0 = Get-Date
    & $action *>> $log
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    "$name=$code" | Add-Content $log
    Write-Host ("{0,-26} exit={1,-3} {2,6:N1}s" -f $name, $code, ((Get-Date) - $t0).TotalSeconds)
    if ($code -ne 0 -and $name -notmatch 'ExpectedNonZero') { throw "step $name failed ($code)" }
    return $code
}

$cpCand = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'cp.ps1') -Arm cand
$cpA = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'cp.ps1') -Arm a3a7686

step 'build'            { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'build-v2.ps1') }
step 'BandIndexControl' { & "$java/java.exe" -Xmx8g -cp "$cpCand" "${cls}BandIndexControl" }
step 'BandIndexFuzz'    { & "$java/java.exe" -Xmx8g -cp "$cpCand" "${cls}BandIndexFuzz" }
step 'MultiSeedBrute'   { & "$java/java.exe" -Xmx8g -cp "$cpCand" "${cls}MultiSeedBruteProbe" }
step 'BandEdgeProbe'    { & "$java/java.exe" -Xmx8g -cp "$cpCand" "${cls}BandEdgeProbe" }
# Bash-less way to encode "this MUST fail": capture, then assert the exit code explicitly.
$negOut = & "$java/java.exe" -Xmx8g -cp "$cpCand" "${cls}BandIndexNegative" 2>&1
$negCode = $LASTEXITCODE
$negOut | Add-Content $log
"BandIndexNegative(expected non-zero)=$negCode" | Add-Content $log
Write-Host ("{0,-26} exit={1}" -f 'BandIndexNegative', $negCode)
if ($negCode -eq 0) { throw 'BandIndexNegative passed: the negative control has no power' }
step 'stage-pristine'   { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'run-stage.ps1') -Arm pristine }
step 'stage-indexed'    { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'run-stage.ps1') -Arm indexed }
step 'ab-perf'          { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'ab-perf.ps1') }
step 'e-perf'           { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'e-perf.ps1') }
step 'perf-summary'     { python -u (Join-Path $root 'perf-summary.py') }
step 'e-perf-summary'   { python -u (Join-Path $root 'e-perf-sum.py') }
step 'stage-compare'    { python -u (Join-Path $root 'stage-compare.py') }
step 'height-bitcompare' { python -u (Join-Path $root 'height-bitcompare.py') }
step 'squared-counterexample' { python -u (Join-Path $root 'squared-counterexample.py') }
step 'source-diff'      { python -u (Join-Path $root 'source-diff.py') }
step 'compare-indexed'  {
    Remove-Item -Recurse -Force (Join-Path $root 'compare') -ErrorAction SilentlyContinue
    python -u (Join-Path $root 'compare.py') --baseline (Join-Path $root 'refs/canonical-7BE04183-float-heights.f64be') --candidate (Join-Path $root 'indexed/double-heights.f64be') --delivered 'D:/Office software/Development Project/GeoGenesis-mod/handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png' --out (Join-Path $root 'compare')
}
step 'compare-3a7686'   {
    Remove-Item -Recurse -Force (Join-Path $root 'compare-3a7686') -ErrorAction SilentlyContinue
    python -u (Join-Path $root 'compare.py') --baseline (Join-Path $root 'refs/canonical-7BE04183-float-heights.f64be') --candidate (Join-Path $root 'refs/merged-3A7686C5-double-heights.f64be') --delivered 'D:/Office software/Development Project/GeoGenesis-mod/handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png' --out (Join-Path $root 'compare-3a7686')
}
step 'render-far'       { python -u (Join-Path $root 'render-far-x.py') indexed }
Write-Host 'FINAL SERIAL DONE'
"# FINAL SERIAL DONE (some steps exit non-zero by design: BandIndexNegative, height-bitcompare context pair, stage-compare timing-only rows)" | Add-Content $log
