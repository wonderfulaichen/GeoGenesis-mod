# Arrange the preserved candidate: sources, frozen config, deps record, licenses, anchors, tools.
# Read-only against the repo and the temp sandbox; writes only under the preserve root.
import hashlib, io, json, os, shutil, subprocess, sys

PRESERVE = r"D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103"
TEMP     = os.path.join(os.environ["TEMP"], "geogenesis-xbandidx-0b1a2c3d4e5f60718293a4b5c6d7e8f9")
REPO     = r"D:\Office software\Development Project\GeoGenesis-mod"
MDK      = os.path.join(REPO, "forge-1.20.1-47.4.10-mdk")
BASE     = os.path.join(MDK, "build", "terrain-upgrade", "baseline")
MAINSRC  = os.path.join(BASE, "src-main", "java")
RES      = os.path.join(BASE, "src-main", "resources")
FTF      = os.path.join(REPO, "\u53c2\u8003", "sources", "FreeTerraForged-1.21.1")
TF       = os.path.join(REPO, "\u53c2\u8003", "sources", "TerraForged-0.3.x")

CLOSURE = os.path.join(os.environ["TEMP"], "ggtest-mainclosure-final.txt")

def sha256(path, blocks=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(blocks)
            if not b: break
            h.update(b)
    return h.hexdigest().upper()

def ensure(p):
    os.makedirs(p, exist_ok=True)
    return p

records = []
def put(src, destdir, note, category):
    ensure(destdir)
    dst = os.path.join(destdir, os.path.basename(src))
    shutil.copyfile(src, dst)
    records.append(dict(category=category, note=note,
                        source=src, dest=os.path.relpath(dst, PRESERVE),
                        bytes=os.path.getsize(dst), sha256=sha256(dst)))

# ---- 1) candidate sources (the 17 files whose union defines candidate BEA0) ------------------
CAND_SRC = os.path.join(TEMP, "diagnostics", "java", "com", "geogenesis", "worldgen", "terrain", "candidate")
cand17 = ["DoubleBoundaryGeometry.java", "DoubleFullMorphLand.java", "DoubleFarBlock.java",
          "DoubleFarDiagnose.java", "DoubleFullGallery.java", "DoubleGalleryCandidate.java",
          "DoubleGalleryProbe.java", "DoubleLandRecipe.java", "DoubleMorphEngine.java",
          "DoubleNodeCensus.java", "DoubleOceanAssemblyCandidate.java", "DoublePerlinCandidate.java",
          "DoubleSeamProfile.java", "DoubleTerraceCheck.java", "DoubleTreeFixed.java",
          "DualCoordinateCall.java", "ActualNodeClosure.java"]
dst = ensure(os.path.join(PRESERVE, "src", "candidate", "com", "geogenesis", "worldgen", "terrain", "candidate"))
for n in cand17:
    put(os.path.join(CAND_SRC, n), dst, "candidate (BEA0) source, package com.geogenesis.worldgen.terrain.candidate", "source/candidate")

# ---- 2) root-level runner probes (default package) -----------------------------------------
dst = ensure(os.path.join(PRESERVE, "src", "runner-root"))
roots = ["BandEdgeProbe.java", "BandIndexControl.java", "BandIndexFuzz.java", "BandIndexNegative.java",
         "EndpointProbe.java", "IsolatedProbe.java", "LocalSeamOnly.java", "MultiSeedBruteProbe.java",
         "PerfE.java", "PerfE2.java", "PerfGeometry.java", "SeamForensics.java", "XFarSeams.java",
         "ZSeamExtension.java"]
for n in roots:
    put(os.path.join(TEMP, n), dst, "sandbox runner main (default package), compiled into classes-cand", "source/runner-root")

# ---- 3) frozen head reference tree (131 files) ---------------------------------------------
HEAD = os.path.join(TEMP, "src-head", "java", "com", "geogenesis", "worldgen", "terrain", "candidate")
dst = ensure(os.path.join(PRESERVE, "src", "head-terrain-candidate", "com", "geogenesis", "worldgen", "terrain", "candidate"))
head_files = sorted(os.listdir(HEAD))
for n in head_files:
    if n.endswith(".java"):
        put(os.path.join(HEAD, n), dst, "frozen workspace-HEAD float reference (compiled into classes-head)", "source/head")

# ---- 4) pure-Java main closure (50 files) --------------------------------------------------
closure = [l.strip() for l in io.open(CLOSURE, encoding="utf-8") if l.strip()]
for fq in closure:
    rel = fq.replace(".", os.sep) + ".java"
    src = os.path.join(MAINSRC, rel)
    put(src, os.path.join(PRESERVE, "src", "main-closure", os.path.dirname(rel)),
        "repo src/main/java, reached by the compiled candidate closure (no Minecraft imports)", "source/main-closure")

# ---- 5) resources that were on the original classpath --------------------------------------
for root, dirs, files in os.walk(RES):
    for fn in files:
        src = os.path.join(root, fn)
        rel = os.path.relpath(root, RES)
        put(src, os.path.join(PRESERVE, "resources", rel),
            "repo src/main/resources as frozen on the original classpath", "resource")

# ---- 6) frozen config / metadata ------------------------------------------------------------
frozen = ensure(os.path.join(PRESERVE, "frozen"))
for name, note in [("config-input.toml", "exact TOML handed to every probe (config-input.toml of the baseline dir)"),
                   ("classpath-jars.txt", "exact 115-jar classpath used by the original build/run scripts"),
                   ("baseline-schema119-metadata-20261029.txt", "recognised standard-figure metadata (schema 119)"),
                   ("verification.json", "baseline PNG reproduction verdict carried by the frozen baseline dir")]:
    put(os.path.join(BASE, name), frozen, note, "frozen")

# ---- 7) licences ----------------------------------------------------------------------------
lic = ensure(os.path.join(PRESERVE, "licenses"))
for src, name, note in [
    (os.path.join(FTF, "LICENSE"), "FTF-LICENSE-MIT-2023.txt",
     "FreeTerraForged 1.21.1 root LICENSE (MIT, Copyright (c) 2023 ReTerraForged); covers the FTF-derived candidate sources"),
    (os.path.join(TF, "LICENSE"), "TF-LICENSE-MIT-2020.txt",
     "TerraForged 0.3.x root LICENSE (MIT, Copyright (c) 2020 TerraForged), kept verbatim including its 'without restricted' wording"),
    (os.path.join(MDK, "LICENSE.txt"), "Forge-LICENSE.txt",
     "Forge/FML licence shipped with the MDK; does NOT cover GeoGenesis code or third-party candidate sources")]:
    if os.path.exists(src):
        dstp = os.path.join(lic, name)
        shutil.copyfile(src, dstp)
        records.append(dict(category="license", note=note, source=src,
                            dest=os.path.relpath(dstp, PRESERVE), bytes=os.path.getsize(dstp), sha256=sha256(dstp)))

# ---- 8) anchors: the two canonical reference arrays (15.7 MB each) --------------------------
anc = ensure(os.path.join(PRESERVE, "anchors", "refs"))
for n, note in [("canonical-7BE04183-float-heights.f64be", "canonical float-arm 1401x1401 height array (anchor 7BE04183)"),
                ("merged-3A7686C5-double-heights.f64be", "merged-commit double-arm height array (anchor 3A7686C5)")]:
    src = os.path.join(TEMP, "refs", n)
    if os.path.exists(src):
        dstp = os.path.join(anc, n)
        shutil.copyfile(src, dstp)
        records.append(dict(category="anchor", note=note, source=src,
                            dest=os.path.relpath(dstp, PRESERVE), bytes=os.path.getsize(dstp), sha256=sha256(dstp)))

# ---- 9) dependency record (no jar bytes copied) ---------------------------------------------
deps = ensure(os.path.join(PRESERVE, "deps"))
jar_list = [j.strip() for j in io.open(os.path.join(BASE, "classpath-jars.txt"), encoding="utf-8").read().replace("\r", "").replace("\n", "").split(";") if j.strip()]
jar_rows = []
for j in jar_list:
    row = dict(path=j, exists=os.path.exists(j))
    if row["exists"]:
        row["bytes"] = os.path.getsize(j)
        row["sha256"] = sha256(j)
    if "forge_gradle\\minecraft_user_repo" in j:
        row["origin"] = "ForgeGradle minecraft_user_repo (Forge 1.20.1-47.4.10 mapped_official_1.20.1 recomp)"
    elif "forge_gradle\\minecraft_repo" in j:
        row["origin"] = "ForgeGradle minecraft_repo (vanilla client-extra)"
    elif "modules-2\\files-2.1" in j:
        parts = j.split(os.sep)
        try:
            i = parts.index("files-2.1")
            row["origin"] = "Gradle modules-2 cache; coordinates %s:%s:%s" % (parts[i+1], parts[i+2], parts[i+3])
            row["maven"] = "%s:%s:%s" % (parts[i+1], parts[i+2], parts[i+3])
        except Exception:
            row["origin"] = "Gradle modules-2 cache"
    else:
        row["origin"] = "unknown"
    jar_rows.append(row)
io.open(os.path.join(deps, "jars.json"), "w", encoding="utf-8").write(
    json.dumps(dict(count=len(jar_rows), totalBytes=sum(r.get("bytes", 0) for r in jar_rows), jars=jar_rows),
               indent=2, ensure_ascii=False))
io.open(os.path.join(deps, "jars.txt"), "w", encoding="utf-8", newline="\n").write(
    "".join("%s  %12d  %s  %s\n" % (r.get("sha256", "MISSING".ljust(64)), r.get("bytes", 0), r.get("maven", "-"), r["path"])
            for r in jar_rows))

# ---- 10) toolchain record -------------------------------------------------------------------
tc = ensure(os.path.join(PRESERVE, "frozen"))
def run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
        return (r.stdout + r.stderr).strip()
    except Exception as e:
        return "ERROR: %s" % e
jdk = r"D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
io.open(os.path.join(tc, "toolchain.txt"), "w", encoding="utf-8", newline="\n").write(
    "JDK dir used by every original script: %s\n"
    "javac --version: %s\n"
    "java -version:\n%s\n\n"
    "python --version: %s\n\n"
    "Original compile flags: --release 17 -encoding UTF-8 -nowarn -proc:none\n"
    "PowerShell: Windows PowerShell 5.1 (primary), scripts are ASCII-only by design.\n"
    % (jdk, run([jdk + "/bin/javac.exe", "-version"]), run([jdk + "/bin/java.exe", "-version"]),
       run(["python", "--version"])))

io.open(os.path.join(PRESERVE, "_build", "layout-records.json"), "w", encoding="utf-8").write(
    json.dumps(records, indent=2, ensure_ascii=False))
print("copied artefacts:", len(records))
print("bytes:", sum(r["bytes"] for r in records))
print("jars recorded:", len(jar_rows), "present:", sum(1 for r in jar_rows if r["exists"]))
