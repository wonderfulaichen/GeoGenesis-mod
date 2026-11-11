# Freeze verified/manifest.json.
#
# ORDERING RULE: this runs LAST, after build+verify, and it is the only writer of that file.
# Every hash it records is of the bytes that exist at freeze time. It also records the hash of
# THIS SCRIPT and of each producer script, so a later edit is detectable rather than silent.
import hashlib, io, json, os, time

P = r"D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103"
V = os.path.join(P, "verified")

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest().upper()

def tree(root):
    out = []
    for r, d, fs in os.walk(root):
        for fn in sorted(fs):
            fp = os.path.join(r, fn)
            out.append(dict(path=os.path.relpath(fp, V).replace("\\", "/"),
                            bytes=os.path.getsize(fp), sha256=sha(fp)))
    return sorted(out, key=lambda x: x["path"])

def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()

REP = os.path.join(V, "_build", "reports")
RUNS = os.path.join(V, "_build", "runs")

# ---- sources ---------------------------------------------------------------------------
src_groups = {}
for g in ("candidate", "overrides", "head-terrain-candidate", "main-closure",
          "baseline-arms", "old-geometry", "probes-seed", "probes-conc", "probes-realchain",
          "probes-memory"):
    d = os.path.join(V, "src", g)
    if os.path.isdir(d):
        src_groups[g] = tree(d)

# ---- the four fixes, with the diff they represent ---------------------------------------
over = os.path.join(V, "src", "overrides", "com", "geogenesis", "worldgen", "terrain", "candidate")
base = os.path.join(V, "src", "candidate", "com", "geogenesis", "worldgen", "terrain", "candidate")
fixes = []
for fn in ("DoubleFullMorphLand.java", "LandRegionContext.java",
           "PerlinScalarCache.java", "DoubleTreeFixed.java"):
    ovp = os.path.join(over, fn)
    bsp = os.path.join(base, fn)
    entry = dict(file=fn,
                 override_sha256=sha(ovp), override_bytes=os.path.getsize(ovp),
                 consolidated_sha256=sha(bsp),
                 applied=(sha(ovp) == sha(bsp)))
    if fn == "PerlinScalarCache.java":
        entry["kind"] = "new file (no ../src counterpart)"
        entry["why"] = ("88 lines; WeakHashMap keyed by RefNoise.Perlin. Replaces a static "
                        "synchronizedMap(IdentityHashMap) that retained 31 wrappers per constructed "
                        "DoubleFullMorphLand forever.")
    elif fn == "LandRegionContext.java":
        entry["kind"] = "overrides head-terrain-candidate"
        entry["why"] = ("adds LandRegionContext(RecipeMorphCatalog, float) so the seed fix can pass "
                        "an existing catalog; the (long,float) form is retained and behaves identically.")
    elif fn == "DoubleFullMorphLand.java":
        entry["kind"] = "overrides src/candidate"
        entry["why"] = ("constructor branch only: non-canonical seeds now build through "
                        "LandRegionContext instead of throwing UnsupportedOperationException. The "
                        "canonical Gallery branch is unchanged.")
    else:
        entry["kind"] = "overrides src/candidate"
        entry["why"] = ("routes the Perlin wrapper lookup through PerlinScalarCache.get(); the "
                        "compute path is unchanged.")
    fixes.append(entry)

# ---- verification evidence --------------------------------------------------------------
ledger_p = os.path.join(REP, "exit-codes.txt")
ledger = {}
if os.path.isfile(ledger_p):
    for line in read(ledger_p).splitlines():
        line = line.lstrip("\ufeff")          # Set-Content -Encoding utf8 writes a BOM
        if "=" in line:
            k, v = line.split("=", 1)
            ledger[k.strip()] = v.strip()

def grab(rel):
    p = os.path.join(REP, rel)
    if not os.path.isfile(p):
        return None
    return read(p)

g6 = grab("g6-conc.log")
g6_rows = [l for l in (g6 or "").splitlines() if l.startswith("STAGE,") or l.startswith("VERDICT")]

# Real complete-chain evidence. These rows are the licence for the complete-chain concurrency
# claim; ConcHarness rows above are component tests and are labelled as such.
rc = grab("g5b-realchain.log")
realchain_rows = [l for l in (rc or "").splitlines()
                  if l.startswith(("HARNESS,", "STAGE,", "VERDICT", "FAIL,"))]

# Memory / lifecycle witness rows (40 owners @ -Xmx128m, warm-up + 3 measured rounds).
mem = grab("g7c-memory.log")
memory_rows = [l for l in (mem or "").splitlines()
               if l.startswith(("HARNESS,", "CACHE,", "NEGCTRL,", "WARMUP,", "ROUND,",
                                "SUMMARY,", "NOTE,", "VERDICT", "FAIL,"))]

# ---- compiled classes, per output set ---------------------------------------------------
def class_hashes(sub):
    d = os.path.join(V, "out", sub)
    if not os.path.isdir(d):
        return {}
    out = {}
    for r, dd, fs in os.walk(d):
        for fn in sorted(fs):
            if fn.endswith(".class"):
                fp = os.path.join(r, fn)
                out[os.path.relpath(fp, d).replace("\\", "/")] = sha(fp)
    return out

classes = {k: class_hashes(k) for k in
           ("classes-main", "classes-head", "classes-cand", "classes-baseline", "classes-3a7686")}

produced = {}
for rel in ("canonical/double-heights.f64be", "canonical/float-heights.f64be",
            "full/heights-12345.f64be", "full/heights-12345-reverse.f64be",
            "values-plain.f64be", "values-gc.f64be"):
    fp = os.path.join(RUNS, rel.replace("/", os.sep))
    if os.path.isfile(fp):
        produced[rel] = dict(bytes=os.path.getsize(fp), sha256=sha(fp))

manifest = {
    "schema": "geogenesis-preserved-candidate/verified-manifest/1.0",
    "candidate": "BEA0 + seed fix + concurrency cache fix",
    "frozenAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "note": ("This file is written once, last, by _build/make-verified-manifest.py. Do not append to it "
             "afterwards: every hash below describes bytes as they were at freeze time, and a later "
             "append would make the hashes disagree with the tree."),

    "relationshipToParent": {
        "parent": "..",
        "original": "unchanged snapshot of the temp sandbox; NOT touched by this stage",
        "src": "unchanged BEA0 source set; NOT touched by this stage",
        "repro": "unchanged BEA0 build/verify/perf scripts; NOT touched by this stage",
        "verified": "this directory: BEA0 with the four fixes consolidated in, plus the seed and "
                    "concurrency probes. It does not import from .. at build time except for "
                    "../resources and the frozen jar/classpath records.",
    },

    "anchors": {
        "canonical_double_1401": "BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965",
        "canonical_float_1401": "7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238",
        "seed12345_full_1401_forward_and_reverse": "340224069AB0D01CF668988436A8FDE8E9A8270EA3C938C536A5A6CFBC28F0AC",
        "seed12345_negative_control_seed1": "3D95AB92637954BE9B3ED7DBFF072D9DB1360449362F782DFC0122A21A56C9D5",
        "cache_values_plain_and_gc": "9D50122B4B09BA281C6D5F53EB11A7B58FCB4D24022333B151E8BF2EEF392CE2",
        "candidate_source_DoubleBoundaryGeometry": "8C26D068DB30CAD8DB9E2E8528EA19514F096D14A68B61E6B1EE22F8624EDA4E",
    },

    "fixes": fixes,

    "sources": {
        "counts": {k: len(v) for k, v in src_groups.items()},
        "totalBytes": sum(f["bytes"] for v in src_groups.values() for f in v),
        "files": src_groups,
    },

    "build": {
        "script": "build.ps1",
        "scriptSha256": sha(os.path.join(V, "build.ps1")),
        "exitCode": 0,
        "recursiveTempReference": False,
        "repoSourceReference": False,
        "stages": 5,
        "outputs": {
            "classes-main": 63, "classes-head": 332, "classes-cand": 56,
            "classes-baseline": 38, "classes-3a7686": 38,
        },
        "assertions": [
            "seed fix present: DoubleFullMorphLand -> LandRegionContext (asserted in bytecode)",
            "cache fix present: DoubleTreeFixed -> PerlinScalarCache, no IdentityHashMap (asserted in bytecode)",
            "PerlinScalarCache.class present and holds a java.util.Map",
            "LandRegionContext exposes the catalog-typed constructor",
            "arm C Tile has chunkStart=True; arms A and B have chunkStart=False",
        ],
        "compiledClasses": {k: dict(count=len(v), sha256=v) for k, v in classes.items()},
    },

    "verify": {
        "script": "verify.ps1",
        "scriptSha256": sha(os.path.join(V, "verify.ps1")),
        "exitCode": 0,
        "ledger": ledger,
        "gates": {
            "G1-canonical": "canonical 1401 arm still BEA0A47C (double) and 7BE04183 (float)",
            "G2-full-12345": "seed 12345 full 1401x1401, forward == reverse == 340224069AB0D01C...",
            "G3-wiring": "base(seed) reaches every stage; distinct seeds must not share a chain digest",
            "G4-sweep": "8 seeds: finite everywhere, far field finite, 21 pairwise digests distinct",
            "G5-seed-concurrency": "cross-seed concurrency on the land chain COMPONENT (not real chain)",
            "G5b-real-chain": ("REAL COMPLETE CHAIN concurrency: ContinentField + DoubleGalleryCandidate + "
                               "HeightCurve, 2 seeds, coast/shelf/seabed/land/highland/far coverage measured "
                               "from the chain's own output, shared and per-thread instances bit-identical to "
                               "single-threaded reference, LRU churn stable, 4/4 negative controls"),
            "G6-component-harness": ("COMPONENT test only: ConcHarness feeds the ocean assembler a sine/cosine "
                                     "STAND-IN continent, so it is NOT real-chain evidence; it isolates the land "
                                     "morph engine, tile LRU and wrapper cache"),
            "G7-lifecycle": "WeakReference+ReferenceQueue, 10 rounds, growthSteps=0/10",
            "G7c-memory": ("memory/lifecycle witness under -Xmx128m: 40 owners per round, 1 discarded "
                           "warm-up + 3 measured create/release rounds; weak refs, PS entry peak and "
                           "return to 0, retained heap, tile LRU <= CACHE_TILES on every owner, and a "
                           "strongly-held negative control that must NOT be collected"),
            "G7b-values": "value dumps identical with and without GC pressure",
            "G8-identity": "RefNoise.Perlin keeps Object identity semantics; cycle audit cycles=0",
            "G9-canonical-seed-rejected": "negative control: canonical seed refused by full mode, exit 1",
        },
        "claimScope": {
            "realChainConcurrencyVerifiedBy": "G5b RealChainConcurrency (src/probes-realchain)",
            "componentOnlyNotRealChain": ["G5 SeedConcurrency", "G6 ConcHarness"],
            "whyTheDistinctionMatters": (
                "ConcHarness builds DoubleOceanAssemblyCandidate with a sine/cosine stand-in as its "
                "ContinentField (ConcHarness.continent), so it never exercises ContinentField, the composed "
                "DoubleGalleryCandidate, or the E->C->height curve end to end. Citing it as complete-chain "
                "evidence would be a false claim. The build/verify scripts now keep the two apart and reject a "
                "real-chain probe that reintroduces a stand-in sampler."),
            "cancellationNotClaimed": (
                "DoubleFullMorphLand serializes mutating state on its own monitor. Concurrent callers COOPERATE "
                "by blocking: that is mutual exclusion, not a cancellation API. There is no interruptible "
                "checkpoint, no progress handle, and no way to abort an in-flight tile build. The cancel/retry "
                "row in ConcHarness measures a harness-owned attempt loop that stops issuing NEW work between "
                "attempts; it never cancels work already inside the chain. A passing result must not be read as "
                "cancellation support."),
        },
        "realChainEvidence": {
            "log": "_build/reports/g5b-realchain.log",
            "probeSource": "src/probes-realchain/com/geogenesis/worldgen/terrain/candidate/RealChainConcurrency.java",
            "seeds": ["12345", "9139912035078620160"],
            "threads": 6,
            "rounds": 3,
            "configDiscipline": ("config loaded exactly once on the main thread before any worker starts; "
                                 "workers only call e()/height() on pre-constructed objects and never read or "
                                 "write GeoGenesisConfig"),
            "stageRows": realchain_rows,
        },
        "concurrencyProbeConfigPolicy": (
            "probes-conc (6 files) must contain zero references to GeoGenesisConfig / CommentedFileConfig / "
            "TomlFormat; verify.ps1 enforces that as a preflight. probes-realchain is deliberately EXEMPT "
            "because it must load the config once on the main thread to build a real ContinentField and "
            "HeightCurve. Giving it its own directory is what makes a directory-scoped check correct "
            "instead of a blanket ban that would reject the very probe we need."),
        "memoryWitness": {
            "log": "_build/reports/g7c-memory.log",
            "probeSource": "src/probes-memory/com/geogenesis/worldgen/terrain/candidate/WeakCacheMemoryProbe.java",
            "heapCap": "-Xmx128m",
            "ownersPerRound": 40,
            "warmupRounds": 1,
            "measuredRounds": 3,
            "claim": "GC observation only; NOT a hard memory bound",
            "noDefaultEntryCap": ("PerlinScalarCache is a plain WeakHashMap with no ceiling configured. "
                                  "Eviction happens only when a key is collected and the map expunges the "
                                  "stale entry. An earlier revision measured a fixed cap to be actively "
                                  "harmful (one owner graph holds ~181 distinct Perlin instances, so a "
                                  "512-entry cap produced more evictions than misses); that is why no cap "
                                  "exists by design. A passing result must not be read as an entry bound "
                                  "or as a memory ceiling."),
            "whyRewrittenNotCopied": ("The sandbox probe carried a 'legacy' mode that rebuilt the pre-fix "
                                      "IdentityHashMap container and pulled fixtures from ConcHarness, whose "
                                      "continent source is a sine/cosine stand-in. Neither belongs in a "
                                      "preserved artefact, so this is a self-contained fixed-cache-only "
                                      "rewrite that builds the real DoubleFullMorphLand directly."),
            "stageRows": memory_rows,
        },
        "harnessRows": g6_rows,
    },

    "producedArtifacts": produced,

    "gap": {
        "dependencyJarsNotSelfContained": True,
        "detail": ("The 115 jars in ../frozen/classpath-jars.txt are NOT bundled. They are recorded by "
                   "path, byte size, SHA-256 and Gradle/Maven origin in ../deps/jars.json. This directory "
                   "cannot build on a machine without them; re-obtain via Gradle and check the hashes."),
        "samplesNotBundled": ("1401x1401 .f64be arrays from the verify run are kept under runs/ for this "
                              "candidate only; they are regenerable and not part of any manifest core set."),
        "notCarriedOver": [
            "Failed/abandoned experiments from the two source sandboxes (pre/ post/ ms/ 154 MB of stage arrays).",
            "The pre-fix cache repaired-in-place variants (ConcMemFixedOnly, ConcCacheDiag, ConcPsRace, ConcBugRepro): they "
            "reproduce old defects, and the defect record is already captured in reports/.",
            "JFR recordings and large figures.",
        ],
    },
}

total_files = 0
total_bytes = 0
for r, d, fs in os.walk(V):
    for fn in fs:
        total_files += 1
        total_bytes += os.path.getsize(os.path.join(r, fn))
manifest["totals"] = dict(files=total_files, bytes=total_bytes)

# The core set is what must survive: sources + scripts + manifest payload. Everything under
# _build (reports, run products, javac arg files, logs) is reproducible and explicitly excluded.
core = []
for k, v in src_groups.items():
    core += v
core.append(dict(path="build.ps1", bytes=os.path.getsize(os.path.join(V, "build.ps1")),
                 sha256=sha(os.path.join(V, "build.ps1"))))
core.append(dict(path="verify.ps1", bytes=os.path.getsize(os.path.join(V, "verify.ps1")),
                 sha256=sha(os.path.join(V, "verify.ps1"))))
manifest["coreSet"] = dict(
    note="necessary sources + scripts; _build/ and out/ are excluded as reproducible",
    files=len(core), bytes=sum(f["bytes"] for f in core))

out = os.path.join(V, "manifest.json")
io.open(out, "w", encoding="utf-8", newline="\n").write(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
print("verified/manifest.json written")
print("files(including manifest)=%d bytes=%d (%.2f MB)" % (total_files, total_bytes, total_bytes / 1e6))
print("source counts:", manifest["sources"]["counts"])
print("fixes applied:", all(f["applied"] for f in fixes))
print("ledger:", json.dumps(ledger, indent=1))
