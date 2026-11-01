# Compile the MAIN workspace diagnostics tree and run FullMorphGallery (frozen window).
# Reads src only; writes under the main workspace build/ directory.
# ASCII only: Windows PowerShell 5.1 mis-parses BOM-less UTF-8 with CJK comments.
param(
    [string]$Main = 'FullMorphGallery',
    [string]$OutName = 'b1',
    [string]$MainWorkspace = 'D:\Office software\Development Project\GeoGenesis-mod',
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
)
$ErrorActionPreference = 'Stop'
$mdk = Join-Path $MainWorkspace 'forge-1.20.1-47.4.10-mdk'
$base = Join-Path $mdk 'build/terrain-upgrade/baseline'
if (-not (Test-Path "$base/classpath-jars.txt")) { throw "baseline missing: $base" }
$r = Join-Path $mdk "build/main-$OutName"
New-Item -ItemType Directory -Force "$r/classes" | Out-Null
New-Item -ItemType Directory -Force "$r/args" | Out-Null

$jars = (Get-Content -Raw "$base/classpath-jars.txt").Trim()
$cp = "$r/classes;$base/classes;$base/src-main/resources;$jars"
$fwd = { param($p) $p -replace '\\', '/' }

$src = Join-Path $mdk 'src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
$skip = @('ProvinceDebugProbe.java', 'ProvinceSampleCheck.java', 'SkipRateProbe.java')
$files = Get-ChildItem "$src/*.java" | Where-Object { $skip -notcontains $_.Name } | ForEach-Object { $_.FullName }

$argfile = Join-Path $r 'args/javac.args'
$lines = @('--release', '17', '-encoding', 'UTF-8', '-nowarn', '-proc:none')
$lines += @('-cp', ('"' + (& $fwd $cp) + '"'), '-d', ('"' + (& $fwd "$r/classes") + '"'))
$lines += $files | ForEach-Object { '"' + (& $fwd $_) + '"' }
[IO.File]::WriteAllLines($argfile, $lines, [Text.UTF8Encoding]::new($false))

& "$JavaHome/javac.exe" "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }
Write-Host "COMPILE PASS ($($files.Count) files)"

$toml = Join-Path $base 'config-input.toml'
$out = Join-Path $r 'out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
& "$JavaHome/java.exe" -Xmx8g '-Dfile.encoding=UTF-8' -cp $cp "com.geogenesis.worldgen.terrain.candidate.$Main" $toml $out
if ($LASTEXITCODE -ne 0) { throw "$Main failed (exit $LASTEXITCODE)" }
Write-Host "GALLERY PASS -> $out"
