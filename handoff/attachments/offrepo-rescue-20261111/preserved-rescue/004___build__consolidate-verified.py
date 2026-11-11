# Consolidate verified/src: a SINGLE buildable source tree = ../src (BEA0) with the four overrides
# already applied, plus the probes. build.ps1 no longer needs to shadow anything, which removes a
# whole class of "which copy compiled?" ambiguity.
#
# Idempotent; refuses to run if verified/src already holds a consolidated tree unless forced.
import hashlib, io, json, os, shutil, sys

P = r"D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103"
V = os.path.join(P, "verified")
SRC = os.path.join(P, "src")
PKG = os.path.join("com", "geogenesis", "worldgen", "terrain", "candidate")

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest().upper()

def cp(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    return dict(dest=os.path.relpath(dst, P).replace("\\", "/"),
                source=os.path.relpath(src, P).replace("\\", "/"),
                bytes=os.path.getsize(dst), sha256=sha(dst))

records = {"candidate": [], "overrides": [], "probes": [], "shared": []}

# ---- 1) the candidate tree, with the four overrides applied ------------------------------
over = os.path.join(V, "src", "overrides", PKG)
over_names = sorted(os.listdir(over))
cand_dir = os.path.join(V, "src", "candidate", PKG)
os.makedirs(cand_dir, exist_ok=True)
for fn in sorted(os.listdir(os.path.join(SRC, "candidate", PKG))):
    src = os.path.join(SRC, "candidate", PKG, fn)
    dst = os.path.join(cand_dir, fn)
    # An override with this exact name wins over the ../src copy.
    if fn in over_names:
        records["overrides"].append(cp(os.path.join(over, fn), dst))
    else:
        records["candidate"].append(cp(src, dst))
# Overrides that are brand new (no ../src counterpart)
for fn in over_names:
    if not os.path.exists(os.path.join(SRC, "candidate", PKG, fn)):
        records["overrides"].append(cp(os.path.join(over, fn), os.path.join(cand_dir, fn)))

# ---- 2) shared reference trees the candidate compiles against ---------------------------
for name, srcdir in [("head-terrain-candidate", os.path.join(SRC, "head-terrain-candidate")),
                     ("main-closure", os.path.join(SRC, "main-closure")),
                     ("baseline-arms", os.path.join(SRC, "baseline-arms")),
                     ("old-geometry", os.path.join(SRC, "old-geometry"))]:
    dstroot = os.path.join(V, "src", name)
    for root, dirs, files in os.walk(srcdir):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            src = os.path.join(root, fn)
            rel = os.path.relpath(src, srcdir)
            records["shared"].append(cp(src, os.path.join(dstroot, rel)))

# ---- 3) the probes ----------------------------------------------------------------------
# probes-seed / probes-conc are already in their final place under verified/src; only hash them,
# do not copy onto themselves.
for sub in ("probes-seed", "probes-conc"):
    srcdir = os.path.join(V, "src", sub, PKG)
    for fn in sorted(os.listdir(srcdir)):
        if fn.endswith(".java"):
            p = os.path.join(srcdir, fn)
            records["probes"].append(dict(dest=os.path.relpath(p, P).replace("\\", "/"),
                                          source=os.path.relpath(p, P).replace("\\", "/"),
                                          bytes=os.path.getsize(p), sha256=sha(p)))

io.open(os.path.join(V, "_build", "consolidation.json"), "w", encoding="utf-8", newline="\n").write(
    json.dumps(records, indent=2, ensure_ascii=False) + "\n")

for k, v in records.items():
    print("%-10s %3d files  %8d bytes" % (k, len(v), sum(r["bytes"] for r in v)))
print("overrides applied:")
for r in records["overrides"]:
    print("   ", os.path.basename(r["dest"]), r["sha256"][:16])
