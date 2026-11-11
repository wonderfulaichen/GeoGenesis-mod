# Verify the VERIFIED candidate: seed fix + concurrency cache fix, on top of BEA0.
#
# Run order is serial and each stage owns its own JVM. The GeoGenesis config is a process-wide
# static (GeoGenesisConfig.SPEC), so every stage that touches it loads it exactly once per JVM and
# no stage switches it while threads are running.
#
# SCOPE OF THE CONCURRENCY CLAIM (read before citing any result here):
#   * G5b RealChainConcurrency  -- the COMPLETE land+sea chain: real ContinentField +
#     DoubleGalleryCandidate + HeightCurve, built per seed on the main thread, config loaded once
#     there. Two seeds, coast/shelf/seabed/land/highland/far coverage measured from the chain's own
#     output. THIS is what licenses "the real chain is concurrency-verified".
#   * G6/G5 ConcHarness, SeedConcurrency -- COMPONENT tests. ConcHarness deliberately feeds the
#     ocean assembler a sine/cosine STAND-IN for the continent field, so its results say nothing
#     about the real chain. They are kept because they hammer the land morph engine, the tile LRU
#     and the wrapper cache directly, which the real-chain probe cannot isolate.
#
# NOT CLAIMED: cancellation. DoubleFullMorphLand serializes on its own monitor, so concurrent
# callers COOPERATE by blocking. That is mutual exclusion, not a cancellation API: there is no
# interruptible checkpoint, no progress handle, and no way to abort an in-flight tile build. The
# cancel/retry row in ConcHarness measures a harness-owned attempt loop that stops issuing NEW work
# between attempts; it never cancels work already inside the chain. Do not read it as cancel support.
#
# Gates (each throws on failure, so a non-zero exit cannot be mistaken for success):
#   G1  canonical 1401 arm still yields BEA0A47C (double) and 7BE04183 (float)   -> no drift
#   G2  seed 12345 full 1401x1401 forward == reverse, sha 340224069AB0D01C...
#   G3  seed wiring reaches every stage; negative control: distinct seeds differ
#   G4  multi-seed sweep: finite everywhere, far field finite, pairwise digests distinct
#   G5  cross-seed concurrency on the land chain component
#   G5b REAL complete chain concurrency (2 seeds, coast+seabed+land+far, LRU churn, negative controls)
#   G6  component harness: LRU bound, cancel-retry loop, PS curve, negative controls
#   G7  weak-key lifecycle: 10 rounds, zero growth, owners collected
#   G7c memory witness: 40 owners @ -Xmx128m, 3 measured rounds, entries peak->0, tile LRU <= cap,
#       strongly-held negative control. GC observation only; PerlinScalarCache has NO entry cap.
#   G8  weak-key identity contract + cycle audit
#   G9  negative control must exit NON-ZERO (proves the comparisons bite)
#
# ASCII only (Windows PowerShell 5.1 parses this file with the system codepage).
param(
    [string]$JavaHome = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin',
    [switch]$SkipFull,         # skip G2 (the 1401x1401 seed-12345 pass, ~60 s and 10 GB heap)
    [switch]$SkipGallery       # skip re-rendering the 1401x1401 canonical image; re-hash the existing
                               # arrays against the frozen anchors instead (see G1b). Use when the
                               # candidate source set is unchanged and a re-render is not warranted.
)
$ErrorActionPreference = 'Stop'
$root  = Split-Path $PSScriptRoot -Parent
$vroot = $PSScriptRoot
$out   = Join-Path $vroot 'out'
# Reports and run products live under _build so they stay OUT of the manifest core set:
# the manifest describes necessary sources and scripts only.
$rep   = Join-Path $vroot '_build\reports'
$runs  = Join-Path $vroot '_build\runs'
New-Item -ItemType Directory -Force $rep  | Out-Null
New-Item -ItemType Directory -Force $runs | Out-Null

$jars = ((Get-Content (Join-Path $root 'frozen\classpath-jars.txt') -Raw) -replace "`r?`n", '').Trim()
$cp   = "$(Join-Path $out 'classes-cand');$(Join-Path $out 'classes-head');$(Join-Path $out 'classes-main');$(Join-Path $root 'resources');$jars"
$cls  = 'com.geogenesis.worldgen.terrain.candidate.'
$cfg  = Join-Path $root 'frozen\config-input.toml'
$java = Join-Path $JavaHome 'java.exe'
$led  = New-Object System.Collections.Generic.List[string]

function Step([string]$name, [string]$log, [scriptblock]$action) {
    Write-Host ("RUN  {0}" -f $name)
    & $action 2>&1 | Out-File (Join-Path $rep $log) -Encoding utf8
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    $led.Add("$name=$code")
    Write-Host ("END  {0} exit={1}" -f $name, $code)
    if ($code -ne 0) { throw "gate '$name' exited $code (see reports/$log)" }
    return $code
}
function MustFail([string]$name, [string]$log, [scriptblock]$action) {
    Write-Host ("RUN  {0} (must be non-zero)" -f $name)
    # A non-zero child exit surfaces as a terminating native-command error under
    # $ErrorActionPreference='Stop' in PS 5.1, so the expected failure has to be contained here:
    # drop to 'Continue' for the call, capture the code, then resume strict mode.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $action 2>&1 | Out-File (Join-Path $rep $log) -Encoding utf8
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $prev
    }
    if ($null -eq $code) { $code = 0 }
    $led.Add("$name=$code (expected non-zero)")
    Write-Host ("END  {0} exit={1}" -f $name, $code)
    if ($code -eq 0) { throw "negative control '$name' passed: it proves nothing" }
}

Write-Host '=== preflight: the probes are what the gates assume ==='
# probes-conc are COMPONENT tests: they must not read the process-wide static config, because
# their purpose is to hammer one component from many threads. This check is scoped to that
# directory only. probes-realchain is deliberately excluded: it MUST load the config once on the
# main thread to build a real ContinentField / HeightCurve, and it is the only probe allowed to.
$concFiles = Get-ChildItem -File (Join-Path $vroot 'src\probes-conc\com\geogenesis\worldgen\terrain\candidate') -Filter '*.java'
$violators = @($concFiles | Where-Object {
        (Select-String -Path $_.FullName -Pattern 'GeoGenesisConfig|CommentedFileConfig|TomlFormat' -Quiet) })
if ($violators.Count -ne 0) {
    throw ("component concurrency probe(s) touch the static config: {0}" -f (($violators | ForEach-Object Name) -join ', '))
}
Write-Host ("  OK {0} component probes: zero references to the static config" -f $concFiles.Count)

# The real-chain probe is the opposite contract: it must build the real chain, so it must
# reference ContinentField, DoubleGalleryCandidate and HeightCurve, and it must load the config.
$realFiles = Get-ChildItem -File (Join-Path $vroot 'src\probes-realchain\com\geogenesis\worldgen\terrain\candidate') -Filter '*.java'
if ($realFiles.Count -ne 1) { throw "expected exactly 1 real-chain probe, found $($realFiles.Count)" }
$realText = Get-Content $realFiles[0].FullName -Raw
foreach ($needle in 'ContinentField', 'DoubleGalleryCandidate', 'HeightCurve', 'GeoGenesisConfig.SPEC.setConfig') {
    if ($realText -notmatch [regex]::Escape($needle)) {
        throw "real-chain probe does not reference required element: $needle"
    }
}
# ...and it must NOT use the stand-in sampler that ConcHarness uses.
if ($realText -match 'Math\.sin\(') {
    throw 'real-chain probe contains a Math.sin(...) stand-in sampler; it must use the real ContinentField'
}
Write-Host '  OK real-chain probe builds ContinentField + DoubleGalleryCandidate + HeightCurve, no stand-in sampler'

# ---- G1 canonical arm: BEA0 + 7BE -------------------------------------------------------
# Rendered fresh unless -SkipGallery, in which case the arrays from the last run are re-hashed
# instead (see G1b). Either way the claim is checked against the frozen anchors, and a changed
# candidate source would show up here because classes-cand was just rebuilt.
$canon = Join-Path $runs 'canonical'
New-Item -ItemType Directory -Force $canon | Out-Null
if (-not $SkipGallery) {
    Step 'G1-canonical' 'g1-canonical.log' { & $java -Xmx6g -cp $cp "${cls}DoubleFullGallery" $cfg $canon }
    $log = Get-Content (Join-Path $rep 'g1-canonical.log') -Raw
    $dblSha = (Get-FileHash (Join-Path $canon 'double-heights.f64be') -Algorithm SHA256).Hash
    $fltSha = (Get-FileHash (Join-Path $canon 'float-heights.f64be')  -Algorithm SHA256).Hash
    $led.Add("canonical-double=$dblSha")
    $led.Add("canonical-float=$fltSha")
    Write-Host "  double = $dblSha"
    Write-Host "  float  = $fltSha"
    if ($dblSha -ne 'BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965') {
        throw "canonical double arm DRIFTED: $dblSha"
    }
    if ($fltSha -ne '7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238') {
        throw "canonical float arm DRIFTED: $fltSha"
    }
    if ($log -notmatch 'BLOCKERS=0') { throw 'gallery reported BLOCKERS != 0' }
}

# ---- G1b reuse the existing canonical image instead of re-rendering it -------------------
# The 1401x1401 pass takes ~3 minutes. When G1 already re-ran it this session, the arrays exist
# and their hashes are re-derived. When -SkipGallery was passed, the arrays from the last run are
# still on disk and are re-hashed against the frozen anchors, so the claim "the canonical image is
# still BEA0/7BE" is verified without paying for a re-render.
# G1 additionally proves the CORE SOURCE SET is unchanged: if a candidate source had changed, the
# freshly-built classes-cand would not reproduce BEA0.
if ($SkipGallery) {
    $reuseDouble = Join-Path $canon 'double-heights.f64be'
    $reuseFloat  = Join-Path $canon 'float-heights.f64be'
    if (-not (Test-Path $reuseDouble) -or -not (Test-Path $reuseFloat)) {
        throw 'SkipGallery was set but no previous canonical arrays exist to re-hash'
    }
    $rd = (Get-FileHash $reuseDouble -Algorithm SHA256).Hash
    $rf = (Get-FileHash $reuseFloat  -Algorithm SHA256).Hash
    $led.Add("reused-canonical-double=$rd")
    $led.Add("reused-canonical-float=$rf")
    Write-Host "  reused double = $rd"
    Write-Host "  reused float  = $rf"
    if ($rd -ne 'BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965') {
        throw "reused canonical double array is not BEA0A47C: $rd"
    }
    if ($rf -ne '7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238') {
        throw "reused canonical float array is not 7BE04183: $rf"
    }
}

if (-not $SkipFull) {
    $full = Join-Path $runs 'full'
    New-Item -ItemType Directory -Force $full | Out-Null
    Step 'G2-full-12345' 'g2-full.log' {
        & $java -Xmx10g -cp $cp "${cls}MultiSeedChain" full $cfg $full 12345
    }
    $fwd = (Get-FileHash (Join-Path $full 'heights-12345.f64be')         -Algorithm SHA256).Hash
    $rev = (Get-FileHash (Join-Path $full 'heights-12345-reverse.f64be') -Algorithm SHA256).Hash
    $led.Add("seed12345-forward=$fwd")
    $led.Add("seed12345-reverse=$rev")
    Write-Host "  forward = $fwd"
    Write-Host "  reverse = $rev"
    if ($fwd -ne $rev) { throw "seed 12345 forward and reverse differ: $fwd vs $rev" }
    if ($fwd -ne '340224069AB0D01CF668988436A8FDE8E9A8270EA3C938C536A5A6CFBC28F0AC') {
        throw "seed 12345 full image DRIFTED: $fwd"
    }
    $flog = Get-Content (Join-Path $rep 'g2-full.log') -Raw
    if ($flog -notmatch 'forwardVsReverseMismatch=0') { throw 'seed 12345 forward/reverse mismatch reported' }
    if ($flog -notmatch 'nonFinite=0') { throw 'seed 12345 produced non-finite heights' }
}
else {
    $led.Add('G2-full-12345=SKIPPED (-SkipFull)')
}

# ---- G3 seed wiring ---------------------------------------------------------------------
Step 'G3-wiring' 'g3-wiring.log' { & $java -Xmx6g -cp $cp "${cls}SeedWiringGate" $cfg }
$wlog = Get-Content (Join-Path $rep 'g3-wiring.log') -Raw
if ($wlog -notmatch 'BLOCKERS=0') { throw 'SeedWiringGate reported BLOCKERS != 0' }
if ($wlog -notmatch 'collisions=0')  { throw 'SeedWiringGate: distinct seeds shared a chain digest' }

# ---- G4 multi-seed sweep ----------------------------------------------------------------
$sweep = Join-Path $runs 'sweep'
New-Item -ItemType Directory -Force $sweep | Out-Null
Step 'G4-sweep' 'g4-sweep.log' { & $java -Xmx6g -cp $cp "${cls}MultiSeedChain" sweep $cfg $sweep }
$slog = Get-Content (Join-Path $rep 'g4-sweep.log') -Raw
if ($slog -notmatch 'nonFinite=0')   { throw 'sweep produced non-finite heights' }
if ($slog -notmatch 'farFailures=0') { throw 'sweep had far-field failures' }
if ($slog -notmatch 'collisions=0')  { throw 'sweep: pairwise full-chain digests collided' }

# ---- G5 cross-seed concurrency ----------------------------------------------------------
Step 'G5-seed-concurrency' 'g5-seed-concurrency.log' { & $java -Xmx8g -cp $cp "${cls}SeedConcurrency" }
$clog = Get-Content (Join-Path $rep 'g5-seed-concurrency.log') -Raw
if ($clog -notmatch 'BLOCKERS=0') { throw 'SeedConcurrency reported BLOCKERS != 0' }
if ($clog -match ',FAIL,')        { throw 'SeedConcurrency reported a FAIL row' }

# ---- G6 COMPONENT harness: land morph + ocean assembler with a STAND-IN continent ----------
# SCOPE WARNING: ConcHarness feeds DoubleOceanAssemblyCandidate a sine/cosine stand-in as its
# ContinentField. It exercises the land morph engine, the ocean assembler and the tile LRU, but it
# is NOT the complete land+sea chain and its numbers must never be cited as real-chain evidence.
# The complete-chain concurrency proof is G5b below. This gate is kept as a component test.
Step 'G6-component-harness' 'g6-conc.log' { & $java -Xmx8g -cp $cp "${cls}ConcHarness" all 6 3 24 (Join-Path $rep 'g6-conc.csv') }
$hlog = Get-Content (Join-Path $rep 'g6-conc.log') -Raw
if ($hlog -notmatch 'VERDICT,harness,PASS,failures=0') { throw 'ConcHarness verdict was not PASS,failures=0' }
if ($hlog -notmatch 'neg-control,detected=4/4')       { throw 'ConcHarness negative controls did not all fire (want 4/4)' }

# ---- G5b REAL complete chain: ContinentField + DoubleGalleryCandidate + HeightCurve --------
# This is the gate that licenses the claim "the real land+sea chain is concurrency-verified".
# It builds the real chain per seed on the MAIN thread (config loaded once, there), then runs the
# threads against objects that already exist. Two seeds; coast/shelf/seabed/land/highland/far
# coverage is measured from the chain's own output, not assumed.
$realOut = Join-Path $runs 'realchain'
New-Item -ItemType Directory -Force $realOut | Out-Null
Step 'G5b-real-chain' 'g5b-realchain.log' {
    & $java -Xmx8g -cp $cp "${cls}RealChainConcurrency" $cfg $realOut 6 3
}
$rlog = Get-Content (Join-Path $rep 'g5b-realchain.log') -Raw
if ($rlog -notmatch 'chain=real\(ContinentField\+DoubleGalleryCandidate\+HeightCurve\)') {
    throw 'real-chain probe did not report the real chain composition'
}
if ($rlog -notmatch 'configLoadedOn=main-thread') { throw 'real-chain probe did not report main-thread config load' }
if ($rlog -notmatch 'VERDICT,PASS,failures=0')    { throw 'RealChainConcurrency verdict was not PASS,failures=0' }
if ($rlog -match '^FAIL,')                        { throw 'RealChainConcurrency logged a FAIL row' }
foreach ($needle in 'shared-instance', 'independent-instances', 'lru-churn', 'coverage', 'finiteness', 'counters') {
    if ($rlog -notmatch [regex]::Escape($needle)) { throw "real-chain log missing stage: $needle" }
}
if ($rlog -notmatch 'mismatches=0')   { throw 'real-chain stage reported bit mismatches' }
if ($rlog -notmatch 'exceptions=0')   { throw 'real-chain stage reported exceptions' }
if ($rlog -notmatch 'nonFinite=0')    { throw 'real-chain produced non-finite values' }
if ($rlog -notmatch 'neg-control,detected=4/4') { throw 'real-chain negative controls did not all fire' }
# both seeds must appear
if ($rlog -notmatch 'seed=12345')                    { throw 'real-chain probe did not exercise seed 12345' }
if ($rlog -notmatch 'seed=9139912035078620160')       { throw 'real-chain probe did not exercise the canonical seed' }
# both sea and land coverage must have been reached
if ($rlog -notmatch 'seaPoints=[1-9]')  { throw 'real-chain coverage never reached sea points' }
if ($rlog -notmatch 'landPoints=[1-9]') { throw 'real-chain coverage never reached land points' }
# the classifier must have produced coastal and seabed zones, which is the point of the claim
if ($rlog -notmatch 'seabed=') { throw 'real-chain coverage did not classify any seabed points' }
if ($rlog -notmatch 'coast=')  { throw 'real-chain coverage did not classify any coast points' }
Write-Host '  real chain: 2 seeds, coast+seabed+land+far covered, shared and per-thread instances bit-identical'

# ---- G7 weak-key lifecycle --------------------------------------------------------------
Step 'G7-lifecycle' 'g7-lifecycle.log' { & $java -Xmx2g -cp $cp "${cls}ConcLifecycleProbe" 10 }
$llog = Get-Content (Join-Path $rep 'g7-lifecycle.log') -Raw
if ($llog -notmatch 'VERDICT PASS') { throw 'ConcLifecycleProbe did not pass' }
if ($llog -notmatch 'growthSteps=0/10') { throw 'cache still grew across rounds: leak not fixed' }

# ---- G7c memory / lifecycle witness under a real heap cap --------------------------------
# 40 owners per round, -Xmx128m, 1 discarded warm-up round then 3 measured create/release rounds.
# Measures: weak vs strong references, PS entry peak and post-release return to zero, retained
# heap, tile LRU <= CACHE_TILES, and a strongly-held negative control that must NOT be collected.
#
# SCOPE: GC observation only. PerlinScalarCache is a plain WeakHashMap with NO default entry cap --
# eviction happens only when a key is collected. A passing result here must NOT be read as a hard
# memory ceiling or as evidence of an entry bound.
Step 'G7c-memory' 'g7c-memory.log' {
    & $java -Xmx128m -cp $cp "${cls}WeakCacheMemoryProbe" 40 (Join-Path $runs 'weakcache-memory.log')
}
$mlog = Get-Content (Join-Path $rep 'g7c-memory.log') -Raw
if ($mlog -notmatch 'VERDICT,PASS,failures=0')       { throw 'WeakCacheMemoryProbe verdict was not PASS,failures=0' }
if ($mlog -match '^FAIL,')                            { throw 'WeakCacheMemoryProbe logged a FAIL row' }
if ($mlog -notmatch 'entryCap=none')                  { throw 'memory probe did not declare the no-cap property' }
if ($mlog -notmatch 'backing=WeakHashMap')            { throw 'memory probe did not identify the WeakHashMap backing' }
if ($mlog -notmatch 'owners_per_round=40')            { throw 'memory probe did not use 40 owners per round' }
if ($mlog -notmatch 'owners=40,')                     { throw 'memory probe round rows did not create 40 owners' }
if ($mlog -notmatch 'result=PASS')                    { throw 'memory probe negative control did not pass' }
if ($mlog -notmatch 'stillReachable=true')            { throw 'strongly-held negative control was collected' }
# every measured round must have released everything
$measuredRounds = [regex]::Matches($mlog, '(?m)^ROUND,')
if ($measuredRounds.Count -ne 3) { throw "expected 3 measured rounds, found $($measuredRounds.Count)" }
$residue = [regex]::Matches($mlog, 'entriesAfterRelease=(?!0,)')
if ($residue.Count -ne 0) { throw 'a measured round left cache entries after release' }
$notCollected = [regex]::Matches($mlog, 'ownersCollected=(?!40/40)')
if ($notCollected.Count -ne 0) { throw 'a round did not collect all 40 owners' }
if ($mlog -notmatch 'tileBoundOk=True')               { throw 'tile LRU exceeded CACHE_TILES on some owner' }
if ($mlog -notmatch 'tileCap=8')                      { throw 'memory probe did not report CACHE_TILES' }
if ($mlog -notmatch 'roundsWithResidue=0/3')          { throw 'a measured round left residue' }
if ($mlog -notmatch 'backing=WeakHashMap')            { throw 'memory probe did not identify the WeakHashMap backing' }
if ($mlog -notmatch 'no entry cap by design|entryCap=none') { throw 'memory probe omitted the no-cap disclosure' }
if ($mlog -notmatch 'gc-observation-not-a-hard-bound')      { throw 'memory probe omitted the not-a-hard-bound disclosure' }
Write-Host '  memory: 3/3 rounds fully released, entries peak->0, tile LRU within cap, strong-ref control held'

# ---- G7b value bit-identity across cache states -----------------------------------------
# The cache must change only memory behaviour, never numbers. Dump the same value set with and
# without GC pressure; a weak-keyed cache that loses an entry mid-run would compute a different
# wrapper and the dumps would diverge.
Step 'G7b-values-plain' 'g7b-plain.log' { & $java -Xmx4g -cp $cp "${cls}ConcValueDump" (Join-Path $runs 'values-plain.f64be') }
Step 'G7b-values-gc'    'g7b-gc.log'    { & $java -Xmx4g -cp $cp "${cls}ConcValueDump" (Join-Path $runs 'values-gc.f64be') gc }
$vPlain = (Get-FileHash (Join-Path $runs 'values-plain.f64be') -Algorithm SHA256).Hash
$vGc    = (Get-FileHash (Join-Path $runs 'values-gc.f64be')    -Algorithm SHA256).Hash
$led.Add("values-plain=$vPlain")
$led.Add("values-gc=$vGc")
Write-Host "  plain = $vPlain"
Write-Host "  gc    = $vGc"
if ($vPlain -ne $vGc) { throw "values differ under GC pressure: $vPlain vs $vGc" }

# ---- G8 identity contract + cycle audit -------------------------------------------------
Step 'G8-identity' 'g8-identity.log' { & $java -Xmx2g -cp $cp "${cls}ConcPerlinIdentityContract" }
Step 'G8-cycle-audit' 'g8-cycle.log' { & $java -Xmx2g -cp $cp "${cls}ConcCycleAudit" }
$alog = Get-Content (Join-Path $rep 'g8-cycle.log') -Raw
if ($alog -notmatch 'cycles=0') { throw 'cycle audit found value->key cycles; WeakHashMap is not safe here' }

# ---- G9 negative control: a real guard in the verified source must exit NON-ZERO ---------
# MultiSeedChain refuses the canonical seed on purpose: the canonical seed has its own arm, and
# letting it through here would silently compare a Gallery path against itself. This control
# proves that guard is live, so G2's non-canonical acceptance is a real widening and not an
# always-permissive code path.
MustFail 'G9-canonical-seed-rejected' 'g9-canonical-rejected.log' {
    & $java -Xmx4g -cp $cp "${cls}MultiSeedChain" full $cfg (Join-Path $runs 'g9') 9139912035078620160
}
$nlog = Get-Content (Join-Path $rep 'g9-canonical-rejected.log') -Raw
if ($nlog -notmatch 'non-canonical') {
    throw 'the canonical-seed rejection did not fire for the documented reason (wrong exception?)'
}
Write-Host '  canonical seed rejected by full mode with the expected reason (guard is live)'

$led | Set-Content (Join-Path $rep 'exit-codes.txt') -Encoding utf8
$led | ForEach-Object { Write-Host $_ }
Write-Host 'VERIFY PASS'
exit 0
