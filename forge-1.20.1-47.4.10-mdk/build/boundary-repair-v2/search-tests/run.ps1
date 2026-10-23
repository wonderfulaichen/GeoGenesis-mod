<#
  Independent verification of build/boundary-repair-v2/OrderedSpillSearch.java.

  Writes ONLY inside build/boundary-repair-v2/search-tests/. The subject source, src/main, the
  plans and the previous evidence directories are read, never modified (the mutants are written to
  out/mutants/ from a copy).

  Stages
    1. compile subject + probe against the existing classpath
    2. run the probe (synthetic windows, invariants, frozen window)       -> must exit 0
    3. three source mutations: compile must succeed, then the named assertion must turn red
    4. guard: the protected inputs must be byte-identical afterwards
#>
param(
    [string]$RunName = "LATEST",
    [switch]$SkipMutations,[switch]$FullMutations
)

$ErrorActionPreference = "Stop"
$repo = "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"
Set-Location $repo

$root   = "build/boundary-repair-v2"
$st     = "$root/search-tests"
$out    = "$st/out/$RunName"
$cls    = "$out/classes"
$mut    = "$out/mutants"

New-Item -ItemType Directory -Force -Path $out, $cls, $mut | Out-Null
python "$st/fix_bom.py" | Out-Null

$protected = @(
    "$root/OrderedSpillSearch.java",
    "build/boundary-integrated-20261023/frozen/bed--1-1.csv",
    "build/boundary-integrated-20261023/frozen/witness-summary.txt"
)
$hashBefore = @{}
foreach ($p in $protected) { $hashBefore[$p] = (Get-FileHash $p -Algorithm SHA256).Hash }

$cp = (Get-Content "build/local-flow-reassessment/classpath.txt" -Raw).Trim()
$probeSrc = Get-ChildItem "$st/src/com/geogenesis/worldgen/hydrology/model/*.java" | ForEach-Object { $_.FullName }
$log = "$out/run.log"
"# run $RunName" | Set-Content $log

function Step($title) {
    Write-Host ""
    Write-Host "== $title"
    "" | Add-Content $log
    "== $title" | Add-Content $log
}

# ---------------------------------------------------------------- stage 1: compile
Step "stage 1 compile subject + probe"
$compileLog = "$out/compile.log"
& javac -proc:none -encoding UTF-8 -cp $cp -d $cls "$root/OrderedSpillSearch.java" $probeSrc 2>&1 |
    Tee-Object -FilePath $compileLog | Out-String | Add-Content $log
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILE FAILED (see $compileLog)"
    Add-Content $log "STAGE 1 FAIL"
    exit 1
}
Write-Host "compile exit 0"
Add-Content $log "compile exit 0"

# ---------------------------------------------------------------- stage 2: probe
Step "stage 2 probe"
$probeLog = "$out/probe.log"
& java "-Dfile.encoding=UTF-8" -cp "$cls;$cp" com.geogenesis.worldgen.hydrology.model.SearchProbe $out 2>&1 |
    Tee-Object -FilePath $probeLog | Out-String | Add-Content $log
$probeExit = $LASTEXITCODE
Write-Host "probe exit $probeExit"
Add-Content $log "probe exit $probeExit"
if ($probeExit -ne 0) {
    Write-Host "PROBE FAILED (see $probeLog)"
    Add-Content $log "STAGE 2 FAIL"
    exit 1
}

# ---------------------------------------------------------------- stage 3: mutations
$mutReports = @()
if (-not $SkipMutations) {
    $mutations = @("far-jump", "ignore-boundary", "boundary-le-regression", "exhausted-as-no-escape",
                   "batch-budget-ignored", "no-completed-cache", "bfs-restarts-each-batch",
                   "candidate-path-stale", "bfs-overwrites-candidate-path")
    if (-not $FullMutations) {
        # the four oracle-level controls are always run; the batching ones only with -FullMutations
        $mutations = @("far-jump", "ignore-boundary", "boundary-le-regression", "exhausted-as-no-escape")
    }
    foreach ($name in $mutations) {
        Step "stage 3 mutation $name"
        $dir = "$mut/$name"
        New-Item -ItemType Directory -Force -Path "$dir/classes" | Out-Null
        $src = "$dir/OrderedSpillSearch.java"
        python "$st/mutations.py" $name $src | Out-Null
        if (-not (Test-Path $src)) { throw "mutation $name produced no source" }

        & javac -proc:none -encoding UTF-8 -cp $cp -d "$dir/classes" $src $probeSrc 2>&1 |
            Tee-Object -FilePath "$dir/compile.log" | Out-String | Add-Content $log
        $mutCompile = $LASTEXITCODE
        Write-Host "mutant ${name} compile exit $mutCompile"
        Add-Content $log "mutant ${name} compile exit $mutCompile"
        if ($mutCompile -ne 0) {
            Write-Host "MUTANT $name DID NOT COMPILE"
            Add-Content $log "STAGE 3 FAIL $name (compile)"
            exit 1
        }

        & java "-Dfile.encoding=UTF-8" -cp "$dir/classes;$cp" `
            com.geogenesis.worldgen.hydrology.model.SearchProbe $dir 2>&1 |
            Tee-Object -FilePath "$dir/probe.log" | Out-String | Add-Content $log
        $mutExit = $LASTEXITCODE

        $expectFile = "$dir/expected-red.txt"
        python "$st/mutations.py" --expect-red $name $expectFile | Out-Null
        $expected = (Get-Content $expectFile -Raw).Trim()
        $red = @()
        foreach ($e in $expected.Split('|')) {
            if (Select-String -Path "$dir/probe.log" -Pattern ([regex]::Escape("FAIL $e")) -Quiet) { $red += $e }
        }
        $ok = ($mutExit -ne 0) -and ($red.Count -eq $expected.Split('|').Count)
        $mutReports += [pscustomobject]@{ name = $name; exit = $mutExit; expected = $expected; red = ($red -join ','); ok = $ok }
        $summary = "mutant ${name}: exit=$mutExit expectedRed=$($mutReports[-1].expected) red=$($mutReports[-1].red) ok=$ok"
        Write-Host $summary
        Add-Content $log $summary
        if (-not $ok) {
            Write-Host "MUTATION $name WAS NOT CAUGHT BY THE EXPECTED ASSERTIONS"
            Add-Content $log "STAGE 3 FAIL $name"
            exit 1
        }
    }
}

# ---------------------------------------------------------------- stage 4: inputs untouched
Step "stage 4 protected inputs unchanged"
$hashOk = $true
foreach ($p in $protected) {
    $now = (Get-FileHash $p -Algorithm SHA256).Hash
    $same = $now -eq $hashBefore[$p]
    if (-not $same) { $hashOk = $false }
    Write-Host ("{0} {1} {2}" -f ($(if ($same) { "UNCHANGED" } else { "CHANGED  " })), $now.Substring(0,16), $p)
    Add-Content $log ("{0} {1} {2}" -f ($(if ($same) { "UNCHANGED" } else { "CHANGED  " })), $now, $p)
}
if (-not $hashOk) {
    Write-Host "PROTECTED INPUTS CHANGED"
    Add-Content $log "STAGE 4 FAIL"
    exit 1
}

if ($mutReports.Count -gt 0) { $mutReports | Format-Table -AutoSize | Out-String | Add-Content $log }
Add-Content $log "ALL STAGES PASS"
Write-Host ""
Write-Host "ALL STAGES PASS -> $out"
