# Unified-terrain baseline capture / verify (plan ocean-unified-terrain, step 0)
#
# Captures: 7-arm 1401^2 SHA + 3 judge probes, into build/unified-baseline/baseline-record.txt
#
# Usage:
#   .\tools\unified-baseline.ps1            # capture baseline record
#   .\tools\unified-baseline.ps1 -Verify    # compare against recorded baseline; exit 1 on drift
#
# NOTE: this file MUST stay pure ASCII.
#   Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI; non-ASCII bytes get mangled and
#   can corrupt the following statement (observed: a mangled comment broke $lines init).

param(
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$mdk   = Split-Path -Parent $PSScriptRoot
$java  = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$base  = Join-Path $mdk 'build/terrain-upgrade/baseline'
$work  = Join-Path $mdk 'build/unified-baseline'
$cls   = Join-Path $work 'classes'
$rec   = Join-Path $work 'baseline-record.txt'

if (-not (Test-Path $base)) {
    throw "missing frozen baseline assets: $base (see handoff/GeoGenesis-20261031.md section 3.2)"
}
if (-not (Test-Path "$java/javac.exe")) {
    throw "missing JDK 21: $java"
}

New-Item -ItemType Directory -Force -Path $cls | Out-Null

$jars = (Get-Content (Join-Path $base 'classpath-jars.txt') -Raw).Trim()
$cp   = "$cls;$base/classes;$base/src-main/resources;$jars"

$lines = New-Object System.Collections.Generic.List[string]

Write-Host "=== [1/3] compile diagnostics ==="
Push-Location $mdk
try {
    $files = (Get-ChildItem 'src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/*.java').FullName
    & "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d $cls $files
    if ($LASTEXITCODE -ne 0) { throw "javac candidate failed: $LASTEXITCODE" }

    $extra = @(
        'src/diagnostics/java/com/geogenesis/worldgen/terrain/OceanTypeWiringCheck.java',
        'src/diagnostics/java/com/geogenesis/worldgen/terrain/NegativeLiftProbe.java'
    )
    & "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d $cls $extra
    if ($LASTEXITCODE -ne 0) { throw "javac extra probes failed: $LASTEXITCODE" }

    Write-Host "=== [2/3] FullMorphGallery (7 arms, 1401^2) ==="
    $data = Join-Path $work 'data-v2'
    & "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp `
        com.geogenesis.worldgen.terrain.candidate.FullMorphGallery `
        (Join-Path $base 'config-input.toml') $data
    if ($LASTEXITCODE -ne 0) { throw "FullMorphGallery failed: $LASTEXITCODE" }

    $arms = @('baseline-coastal','baseline-islands','plateau-only-coastal',
              'plateau-only-islands','morph-coastal','morph-islands','shelf-coastal')
    $lines.Add("# unified-terrain baseline (step 0)")
    $lines.Add("# git HEAD: $(& git rev-parse --short HEAD 2>$null)")
    $lines.Add("# produced: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $lines.Add("")
    $lines.Add("## seven arms (f64be, 1401x1401, step=5)")
    foreach ($a in $arms) {
        $p = Join-Path $data "$a.f64be"
        if (-not (Test-Path $p)) { throw "missing arm output: $p" }
        $lines.Add(("{0,-26} {1}" -f "$a.f64be", (Get-FileHash $p -Algorithm SHA256).Hash))
    }
    $lines.Add("")
    $lines.Add("## optional outputs")
    foreach ($n in @('recipe.u8','participants.u8','coverage.f64be','config-input.toml')) {
        $p = Join-Path $data $n
        if (Test-Path $p) {
            $lines.Add(("{0,-26} {1}" -f $n, (Get-FileHash $p -Algorithm SHA256).Hash))
        }
    }
    $lines.Add("")
    $lines.Add("## judge probes")

    Write-Host "=== [3/3] judge probes ==="
    $probes = @(
        @{ name='FullMorphLandCheck';  cls='com.geogenesis.worldgen.terrain.candidate.FullMorphLandCheck';
           args=@((Join-Path $work 'land-check'));                 expect='PASS checks=910' },
        @{ name='ShallowDetailCheck';  cls='com.geogenesis.worldgen.terrain.candidate.ShallowDetailCheck';
           args=@((Join-Path $work 'shallow-check'), (Join-Path $base 'config-input.toml'));
                                                                   expect='PASS checks=35' },
        @{ name='OceanTypeWiringCheck'; cls='com.geogenesis.worldgen.terrain.OceanTypeWiringCheck';
           args=@((Join-Path $work 'ocean-wiring'), '12345');      expect='PASS checks=10' }
    )
    foreach ($pr in $probes) {
        $out  = & "$java/java.exe" -Xmx4g '-Dfile.encoding=UTF-8' -cp $cp $pr.cls @($pr.args) 2>&1
        $code = $LASTEXITCODE
        $tail = ($out | Select-Object -Last 1)
        if ($code -ne 0) { throw "$($pr.name) FAILED (exit=$code): $tail" }
        if ($tail -notmatch [regex]::Escape($pr.expect)) {
            throw "$($pr.name) unexpected verdict: expected '$($pr.expect)', got '$tail'"
        }
        # Record only the fingerprint, never absolute output paths (they differ per machine).
        $lines.Add(("{0,-26} exit=0  {1}" -f $pr.name, $pr.expect))
        Write-Host "  $($pr.name): $($pr.expect)"
    }
}
finally { Pop-Location }

$new = ($lines -join "`n") + "`n"

if ($Verify) {
    if (-not (Test-Path $rec)) { throw "no baseline record to verify against: $rec" }
    $strip = {
        param($t)
        (($t -split "`n") | Where-Object { $_ -notmatch '^#\s+produced:' }) -join "`n"
    }
    $old = & $strip (Get-Content $rec -Raw)
    $cur = & $strip $new
    if ($old -eq $cur) {
        Write-Host ""
        Write-Host "BASELINE VERIFY PASS (identical to recorded baseline)"
        exit 0
    }
    Write-Host ""
    Write-Host "BASELINE VERIFY FAIL - drift detected:"
    Compare-Object ($old -split "`n") ($cur -split "`n") | Format-Table -AutoSize
    exit 1
}

[System.IO.File]::WriteAllText($rec, $new, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ""
Write-Host "baseline recorded -> $rec"
Write-Host "verify with: .\tools\unified-baseline.ps1 -Verify"
