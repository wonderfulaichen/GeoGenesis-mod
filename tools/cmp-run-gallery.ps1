# Compile a worktree's diagnostics candidate and run one gallery with the frozen window.
# Read-only for source trees; outputs land under that worktree's build/cmp.
# ASCII only: Windows PowerShell 5.1 mis-parses BOM-less UTF-8 with CJK comments.
param(
    [Parameter(Mandatory = $true)][string]$Sha,
    [Parameter(Mandatory = $true)][string]$Main,
    [string]$OutName = 'out',
    [string[]]$ExtraArgs = @(),
    [string]$Base = 'D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk\build\terrain-upgrade\baseline',
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\Office software\Development Project\GeoGenesis-mod'
$mdk = Join-Path $root "build/wt-$Sha/forge-1.20.1-47.4.10-mdk"
if (-not (Test-Path $mdk)) { throw "worktree missing: $mdk" }
$r = Join-Path $mdk 'build/cmp'
New-Item -ItemType Directory -Force "$r/classes" | Out-Null
New-Item -ItemType Directory -Force "$r/args" | Out-Null

$jars = (Get-Content -Raw "$Base/classpath-jars.txt").Trim()
$cp = "$r/classes;$Base/classes;$Base/src-main/resources;$jars"
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
if ($LASTEXITCODE -ne 0) { throw "javac failed for $Sha (exit $LASTEXITCODE)" }
Write-Host "COMPILE PASS $Sha ($($files.Count) files)"

$toml = Join-Path $Base 'config-input.toml'
$out = Join-Path $r $OutName
$argv = @($toml, $out) + $ExtraArgs
& "$JavaHome/java.exe" -Xmx8g '-Dfile.encoding=UTF-8' -cp $cp "com.geogenesis.worldgen.terrain.candidate.$Main" @argv
if ($LASTEXITCODE -ne 0) { throw "$Main failed for $Sha (exit $LASTEXITCODE)" }
Write-Host "GALLERY PASS $Sha $Main -> $out"
