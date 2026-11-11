# Stage verified/: the seed fix + the concurrency cache fix, plus only the probes that carry them.
# Reads the two source sandboxes read-only; writes only under verified/. original/ and src/ untouched.
import hashlib, io, json, os, shutil

P   = r"D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103"
V   = os.path.join(P, "verified")
MS  = os.path.join(os.environ["TEMP"], "geogenesis-multiseed-a151c7624405493790c525e6fbd2db83")
CC  = os.path.join(os.environ["TEMP"], "geogenesis-conc-own-1999225333")
MSC = os.path.join(MS, "diagnostics", "java", "com", "geogenesis", "worldgen", "terrain", "candidate")
CCC = os.path.join(CC, "diagnostics", "java", "com", "geogenesis", "worldgen", "terrain", "candidate")
PKG = os.path.join("com", "geogenesis", "worldgen", "terrain", "candidate")

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest().upper()

records = []
def take(src, subdir, note, cls):
    dst_dir = os.path.join(V, subdir, PKG)
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, os.path.basename(src))
    shutil.copyfile(src, dst)
    records.append(dict(kind=cls, note=note, source=src,
                        dest=os.path.relpath(dst, P).replace("\\", "/"),
                        bytes=os.path.getsize(dst), sha256=sha(dst)))

# ---- overrides: these four SHADOW files from ../src -----------------------------------------
take(os.path.join(MSC, "DoubleFullMorphLand.java"), "src/overrides",
     "seed fix: non-canonical seeds now build through LandRegionContext instead of throwing; "
     "the canonical Gallery branch is byte-for-byte unchanged inside the same method", "override/seed")
take(os.path.join(MSC, "LandRegionContext.java"), "src/overrides",
     "adds LandRegionContext(RecipeMorphCatalog, float) so the seed fix can pass an existing "
     "catalog; the (long,float) constructor is kept and behaves identically", "override/seed")
take(os.path.join(CCC, "PerlinScalarCache.java"), "src/overrides",
     "weak-keyed synchronized cache replacing the static synchronizedMap(IdentityHashMap) that "
     "leaked 31 entries per constructed instance", "override/concurrency")
take(os.path.join(CCC, "DoubleTreeFixed.java"), "src/overrides",
     "routes the Perlin wrapper lookup through PerlinScalarCache; compute path unchanged", "override/concurrency")

# ---- seed probes (each is a separate JVM entry point; none switches config in-process) -------
for n, note in [
    ("SeedWiringGate.java", "asserts base(seed) reaches every stage; negative control = distinct seeds must not share a chain digest"),
    ("MultiSeedChain.java", "sweep + full 1401x1401 forward/reverse for a non-canonical seed, bit-compared"),
    ("SeedConcurrency.java", "shared-instance bit identity, cold-vs-evicted tile identity, interleaved seeds without crosstalk"),
]:
    take(os.path.join(MS, n), "src/probes-seed", note, "probe/seed")

# ---- concurrency / lifecycle probes ---------------------------------------------------------
for n, note in [
    ("ConcHarness.java", "LRU bound, multi-seed stage, cancel-retry stage, PS cache curve, mandatory negative controls"),
    ("ConcLifecycleProbe.java", "WeakReference + ReferenceQueue over 10 rounds; growth must be 0 steps"),
    ("ConcCycleAudit.java", "walks cached values and asserts no value reaches its own weak key"),
    ("ConcPerlinIdentityContract.java", "asserts RefNoise.Perlin keeps Object identity semantics, which is what makes WeakHashMap correct here"),
    ("ConcValueDump.java", "dumps values with and without GC pressure so bit identity can be compared across cache states"),
    ("ConcMemFixedDiag.java", "retained heap across 40 owners under -Xmx128m, fixed cache only"),
]:
    take(os.path.join(CCC, n), "src/probes-conc", note, "probe/concurrency")

os.makedirs(os.path.join(V, "src", "probes-root"), exist_ok=True)
os.makedirs(os.path.join(V, "reports"), exist_ok=True)

io.open(os.path.join(V, "_staging.json"), "w", encoding="utf-8", newline="\n").write(
    json.dumps(records, indent=2, ensure_ascii=False) + "\n")
for r in records:
    print("%-22s %6d  %s  %s" % (r["kind"], r["bytes"], r["sha256"][:16], os.path.basename(r["dest"])))
print("staged", len(records), "files")
