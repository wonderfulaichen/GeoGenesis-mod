# P0-P2 only. Does not instantiate the new full-world chain or alter baseline files.
$ErrorActionPreference = 'Stop'
$mdk = Split-Path -Parent $PSScriptRoot
Push-Location $mdk
try {
    $r = 'build/world-engine-p0-p2'
    $java = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
    $base = 'build/terrain-upgrade/baseline'
    $jars = (Get-Content "$base/classpath-jars.txt" -Raw).Trim()
    $cp = "$r/new-classes;$base/classes;$base/src-main/resources;$jars"
    New-Item -ItemType Directory -Force "$r/new-classes" | Out-Null
    $src = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
    $files = Get-ChildItem "$src/*.java" | ForEach-Object { "$src/$($_.Name)" }
    & "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d "$r/new-classes" $files
    if ($LASTEXITCODE) { throw 'candidate compile failed' }
    $oldCp = "$r/old-classes;$base/classes;$base/src-main/resources;$jars"
    New-Item -ItemType Directory -Force "$r/old-classes" | Out-Null
    $oldFiles = Get-ChildItem "$r/frozen-source/*.java" | ForEach-Object { "$r/frozen-source/$($_.Name)" }
    & "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $oldCp -d "$r/old-classes" $oldFiles "$src/EngineLandFingerprint.java" "$src/EngineOceanFingerprint.java"
    if ($LASTEXITCODE) { throw 'frozen fingerprint compile failed' }
    & "$java/java.exe" -Xmx4g '-Dfile.encoding=UTF-8' -cp $oldCp com.geogenesis.worldgen.terrain.candidate.EngineOceanFingerprint "$r/evidence/old-ocean.f64be"
    if ($LASTEXITCODE) { throw 'old ocean fingerprint failed' }
    foreach ($name in @('EngineLandFingerprint','EngineOceanFingerprint')) {
        $kind = if ($name -eq 'EngineLandFingerprint') { 'land' } else { 'ocean' }
        & "$java/java.exe" -Xmx4g '-Dfile.encoding=UTF-8' -cp $cp "com.geogenesis.worldgen.terrain.candidate.$name" "$r/evidence/new-$kind.f64be"
        if ($LASTEXITCODE) { throw "$name failed" }
        if ((Get-FileHash "$r/evidence/new-$kind.f64be").Hash -ne (Get-FileHash "$r/evidence/old-$kind.f64be").Hash) { throw "$kind bitwise drift" }
        Write-Host "FROZEN $kind BITWISE PASS"
    }
    foreach ($name in @('RecipeMorphCatalogCheck','RecipeJunctionMorphCheck','FullMorphLandCheck','NodeMorphEngineCheck','OceanRecipeCheck','OceanMorphConceptsCheck','OceanTransitionCheck','DistanceFieldCheck')) {
        & "$java/java.exe" -Xmx4g '-Dfile.encoding=UTF-8' -cp $cp "com.geogenesis.worldgen.terrain.candidate.$name" "$r/evidence/$name"
        if ($LASTEXITCODE) { throw "$name failed" }
    }
    $ErrorActionPreference = 'Continue'
    & "$java/java.exe" -Xmx4g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.NodeMorphEngineCheck "$r/evidence/negative-clamp" 'truncate-negative'
    $negativeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($negativeExit -eq 0) { throw 'negative clamp control incorrectly passed' }
    Write-Host 'NEGATIVE CLAMP EXPECTED FAIL CONFIRMED'
    & "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.FullMorphGallery "$base/config-input.toml" "$r/regression-seven-arms"
    if ($LASTEXITCODE) { throw 'legacy Gallery failed' }
    foreach ($arm in @('baseline-coastal','baseline-islands','plateau-only-coastal','plateau-only-islands','morph-coastal','morph-islands','shelf-coastal')) {
        if ((Get-FileHash "$r/regression-seven-arms/$arm.f64be").Hash -ne (Get-FileHash "$r/frozen-seven-arms/$arm.f64be").Hash) { throw "$arm drift" }
        Write-Host "SEVEN ARM SAME $arm"
    }
    Write-Host 'WORLD ENGINE P0-P2 VERIFY PASS'
}
finally { Pop-Location }
