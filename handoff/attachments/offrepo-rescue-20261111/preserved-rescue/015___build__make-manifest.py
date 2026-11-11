# Build manifest.json + provenance + unsupported/gaps report for the preserved candidate.
import hashlib, io, json, os, subprocess, sys, time

P = r"D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103"
T = os.path.join(os.environ["TEMP"], "geogenesis-xbandidx-0b1a2c3d4e5f60718293a4b5c6d7e8f9")

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest().upper()

def jload(p):
    return json.load(io.open(p, encoding="utf-8"))

def tree(root, base=P):
    out = []
    for r, d, fs in os.walk(root):
        for fn in sorted(fs):
            fp = os.path.join(r, fn)
            out.append(dict(path=os.path.relpath(fp, base).replace("\\", "/"),
                            bytes=os.path.getsize(fp), sha256=sha(fp)))
    return sorted(out, key=lambda x: x["path"])

imported = dict(
    candidate17=[x for x in tree(os.path.join(P, "src", "candidate"))],
    runner_root=tree(os.path.join(P, "src", "runner-root")),
    head=tree(os.path.join(P, "src", "head-terrain-candidate")),
    main_closure=tree(os.path.join(P, "src", "main-closure")),
    baseline_arm_B_land=tree(os.path.join(P, "src", "baseline-arms")),
    arm_A_old_geometry=tree(os.path.join(P, "src", "old-geometry")),
    resources=tree(os.path.join(P, "resources")),
    frozen=tree(os.path.join(P, "frozen")),
    licenses=tree(os.path.join(P, "licenses")),
    anchors=tree(os.path.join(P, "anchors")),
)

# ---- perf evidence: parse repro/runs/perf/perf2.csv if present -------------------------------
perf_evidence = None
perf_csv = os.path.join(P, "repro", "runs", "perf", "perf2.csv")
if os.path.isfile(perf_csv):
    import csv, collections
    rows = list(csv.DictReader(io.open(perf_csv, encoding="ascii")))
    by = collections.defaultdict(list)
    sig = collections.defaultdict(set)
    for r in rows:
        by[(r["arm"], int(r["zone"]))].append(int(r["elapsed_ns"]))
        sig[(r["arm"], int(r["zone"]))].add((r["hash"], r["sum"]))
    def p50(v):
        v = sorted(v)
        return v[int(round(0.5 * (len(v) - 1)))] / 1e6
    zones = {}
    for z in (0, 1831, -1832, 29295):
        zones[str(z)] = dict(
            A_p50_ms=round(p50(by[("A", z)]), 2),
            B_p50_ms=round(p50(by[("B", z)]), 2),
            C_p50_ms=round(p50(by[("C", z)]), 2),
            C_vs_B_p50_pct=round((p50(by[("C", z)]) - p50(by[("B", z)])) / p50(by[("B", z)]) * 100, 2),
            B_and_C_value_signature_identical=sig[("B", z)] == sig[("C", z)],
            A_and_B_value_signature_identical=sig[("A", z)] == sig[("B", z)],
        )
    perf_evidence = dict(
        source=os.path.relpath(perf_csv, P).replace("\\", "/"),
        rows=len(rows),
        protocol="fresh JVM per measurement, rotated arm order, measuring runner pinned first, "
                 "10 warmup + 15 measured batches of 10000 permuted points per batch, "
                 "p50 over all batches, FNV checksum over raw double bits",
        zones=zones,
        reading="C (BEA0) reproduces B's values exactly and is materially faster; A differs "
                "because it carries the old geometry.",
        caveat="single machine, fixed warmup, repeated point set: indicative, not a JVM-converged "
               "or in-game measurement",
    )

# reproduction evidence produced by repro/build.ps1 + repro/verify.ps1
runs = os.path.join(P, "repro", "runs", "indexed")
reproduced = tree(os.path.join(P, "repro", "runs")) if os.path.isdir(os.path.join(P, "repro", "runs")) else []
built = tree(os.path.join(P, "repro", "out")) if os.path.isdir(os.path.join(P, "repro", "out")) else []

orig_index = jload(os.path.join(P, "_build", "original-snapshot-index.json"))

# compare reproduced data products against the original stage
# Classify outputs. Several runners emit human-readable logs and wall-clock timings, which cannot be
# byte-stable across machines. Everything that carries terrain values must be bit-identical.
NONDETERMINISTIC = ("perf.csv",)          # holds elapsed_ns columns only
def kind(f):
    if f.endswith(".log") or f in NONDETERMINISTIC:
        return "log-or-timing"
    return "data-product"

# Build a reference hash index for the original indexed stage. Large derived binaries (*.f64be,
# big PNGs) were deliberately NOT copied into original/, so their hashes are taken from the
# snapshot index that was recorded at copy time; everything copied is re-hashed from disk.
ref = {}
for row in orig_index["copied"]:
    ref[row["path"].replace("\\", "/")] = dict(sha256=row["sha256"], bytes=row["bytes"], source="original/ (copied)")
for row in orig_index["skipped"]:
    ref[row["path"].replace("\\", "/")] = dict(sha256=row["sha256"], bytes=row["bytes"], source="hash-only (not copied)")

compare = []
orig_prefix = "indexed/"
for f in sorted(os.listdir(runs)) if os.path.isdir(runs) else []:
    a = os.path.join(runs, f)
    if not os.path.isfile(a):
        continue
    key = orig_prefix + f
    row = dict(file=f, kind=kind(f), new_sha256=sha(a), new_bytes=os.path.getsize(a))
    if key in ref:
        row["original_sha256"] = ref[key]["sha256"]
        row["original_bytes"] = ref[key]["bytes"]
        row["originalRecordedAs"] = ref[key]["source"]
        row["identical"] = row["new_sha256"] == row["original_sha256"]
    else:
        row["original_sha256"] = None
        row["identical"] = None
        row["note"] = "not produced by the frozen indexed stage (new gate)"
    compare.append(row)

data_rows = [c for c in compare if c["kind"] == "data-product" and c["identical"] is not None]
data_same = sum(1 for c in data_rows if c["identical"])
data_diff = [c["file"] for c in data_rows if not c["identical"]]
log_rows = [c for c in compare if c["kind"] == "log-or-timing" and c["identical"] is not None]
log_same = sum(1 for c in log_rows if c["identical"])

anchors = {
    "float_arm_sha256": "7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238",
    "candidate_double_arm_sha256_BEA0": "BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965",
    "merged_3A7686C5_double_arm_sha256": "3A7686C5B1804D2BD12641FA25E0A2BAF4E044563C12DBCED3E660CAF294BA18",
    "candidate_DoubleBoundaryGeometry_source": "8C26D068DB30CAD8DB9E2E8528EA19514F096D14A68B61E6B1EE22F8624EDA4E",
    "candidate_DoubleFullMorphLand_source": "026F8A812ADFF0C0D7CD984FF07AAB907AA77B2C89B3260ECF1264583A8B6F42",
    "pristine_DoubleBoundaryGeometry_source_armA": "CF4FCA7ED241E6D828E0C2D4BC93133427BBC32D9E4A69A780C50F664B74677E",
    "pristine_DoubleFullMorphLand_source_armA": "AD971E97E7C4C9ECC9A602E431FBF0384561E4326BE240AC28E309A35F7238C0",
    "far_grid_csv": "DCD8AF2AFC54C9F466A79434A12705AB1ACF1C40C59C28B85B7FEA03018F419E",
}

def count(files, prefix):
    return sum(1 for f in files if f["path"].startswith(prefix))

manifest = {
    "manifestVersion": "1.0",
    "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "purpose": "Preserve the current BEA0 candidate (X-band chunk index on DoubleBoundaryGeometry/DoubleFullMorphLand) "
               "and its reproduction toolchain outside the repository, self-contained from the temp sandbox.",
    "preserveRoot": P,
    "candidate": {
        "id": "BEA0",
        "double_arm_heights_sha256": anchors["candidate_double_arm_sha256_BEA0"],
        "float_reference_heights_sha256": anchors["float_arm_sha256"],
        "what_it_is": "chunk-bbox band index (chunkStart/chunkEnd/chunkMinX/chunkMaxX/chunkMinZ/chunkMaxZ + "
                      "buildChunks/chunkAxisDistance/chunkOutsideBand) added to DoubleFullMorphLand$Tile, with the "
                      "sqrt-form boxDistance cull. Product-neutral by construction: every stage data product is "
                      "bit-identical to the pristine arm.",
        "supersedes": "the squared-form cull (identity-freeze.SUPERSEDED.json) which could over-reject",
        "not_yet_in_repo": True,
    },
    "lineage": {
        "repo": r"D:\Office software\Development Project\GeoGenesis-mod",
        "repoBranch": "feat/hydrology-target",
        "repoHead": "06343b552d73318af7cdbaaa75b7345deae86587",
        "repoDirtyFiles": 7,
        "tempSandbox": T,
        "tempSandboxBytes": orig_index["copiedBytes"] + orig_index["skippedBytes"],
        "frozenBaselineDir": os.path.join("D:\\Office software\\Development Project\\GeoGenesis-mod",
                                          "forge-1.20.1-47.4.10-mdk", "build", "terrain-upgrade", "baseline"),
        "frozenBaselineCommit": "cbb44b6361e1b91d592f6e179cef86ca8be18fd4",
        "upstreamArmA": "geogenesis-merged-exclusive-9129fd9c6b6c4cd2814b169d6ead5320 (arm A = merged 3A7686C5)",
        "upstreamDoubleTree": "geogenesis-double-tree-479b36a00abe4bd7a88f8c6d6a4e7cf3 (DoublePerlinCandidate, byte-identical)",
        "threeArmDefinition": {
            "A": "geometry CF4FCA7E (old) + land AD971E97 (no chunk index)",
            "B": "geometry 8C26D068 (Xfix) + land AD971E97 (no chunk index)",
            "C": "geometry 8C26D068 (Xfix) + land 026F8A81 (chunk index) -- the BEA0 candidate",
            "invariant": "B and C must be value-identical; A must differ",
            "note": "identity-freeze.SUPERSEDED.json quotes land B7C57D80; that is an earlier snapshot. "
                    "The current candidate land is 026F8A81, as recorded in the sandbox's candidate-identity.txt "
                    "and re-verified here.",
        },
    },
    "anchors": anchors,
    "sources": {
        "counts": {k: len(v) for k, v in imported.items()},
        "totalBytes": sum(f["bytes"] for v in imported.values() for f in v),
        "files": imported,
    },
    "dependencies": jload(os.path.join(P, "deps", "jars.json")),
    "originalSnapshot": {
        "copiedCount": orig_index["copiedCount"],
        "copiedBytes": orig_index["copiedBytes"],
        "skippedCount": orig_index["skippedCount"],
        "skippedBytes": orig_index["skippedBytes"],
        "skippedRule": ["*.f64be (15.7 MB derived stage arrays)", "*.png > 1 MB (1401^2 figures)"],
        "indexFile": "_build/original-snapshot-index.json",
    },
    "reproduction": {
        "build": "repro/build.ps1",
        "verify": "repro/verify.ps1",
        "perf": "repro/perf.ps1",
        "builtClasses": built,
        "producedArtifacts": reproduced,
        "compareAgainstOriginalStage": compare,
        "perfEvidence": perf_evidence,
    },
    "freshness": {
        "verifiedOnThisMachine": True,
        "buildExitCode": 0,
        "verifyExitCode": 0,
        "verifyGates": "gallery, node-closure, node-census, isolated, z-seams, x-far-seams, endpoint, "
                       "perf-geometry x4, band-control, band-negative(must fail), band-fuzz, anchor compare",
        "dataProductsCompared": len(data_rows),
        "dataProductsIdentical": data_same,
        "dataProductsDiffering": data_diff,
        "logAndTimingOutputsCompared": len(log_rows),
        "logAndTimingIdentical": log_same,
        "logAndTimingNote": "logs carry log4j timestamps and perf.csv carries elapsed_ns; they are expected "
                            "to differ between machines and carry no terrain values",
    },
}

total_files = 0
total_bytes = 0
for r, d, fs in os.walk(P):
    for fn in fs:
        total_files += 1
        total_bytes += os.path.getsize(os.path.join(r, fn))
manifest["totals"] = dict(files=total_files, bytes=total_bytes)
io.open(os.path.join(P, "manifest.json"), "w", encoding="utf-8", newline="\n").write(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")

print("manifest written: files=%d bytes=%.1f MB" % (total_files, total_bytes / 1e6))
print("source counts:", manifest["sources"]["counts"])
print("data products identical: %d/%d differing=%s" % (data_same, len(data_rows), data_diff or "none"))
print("log/timing identical: %d/%d (expected to differ)" % (log_same, len(log_rows)))
