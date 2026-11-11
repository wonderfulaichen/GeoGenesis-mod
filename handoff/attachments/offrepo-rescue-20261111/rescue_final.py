#!/usr/bin/env python3
"""
Build two compact rescue packages from the last two external backups, then
verify every copied file byte-for-byte (SHA-256) against the ORIGINAL backup.

  morph-p0-rescue/       : all 16 non-runtime orphan files from morph-p0
  preserved-rescue/      : minimal reproduction set from preserved-candidate
                           (sources, scripts, manifests, verified results)
                           -- excludes .f64be / .class / duplicate CSV/PNG
                           generated data, which are recorded by hash only.

Writes a manifest for each with original relative path + size + sha256.
Read-only w.r.t. the external backups. Usage:
  python rescue_final.py <RESCUE_DIR> <MORPH_P0> <PRESERVED> <MAIN_REPO>
"""
import hashlib
import json
import os
import shutil
import sys
from collections import Counter


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def flat_name(idx, rel):
    parts = rel.split("/")
    tail = "__".join(parts[-2:]) if len(parts) >= 2 else parts[0]
    clean = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in tail)
    return "%03d__%s" % (idx, clean[:110])


def copy_flat(src_root, dst_dir, rels, prefix):
    os.makedirs(dst_dir, exist_ok=True)
    entries = []
    for i, rel in enumerate(rels, 1):
        src = os.path.join(src_root, rel.replace("/", os.sep))
        if not os.path.isfile(src):
            raise RuntimeError("missing source: " + src)
        name = flat_name(i, rel)
        dst = os.path.join(dst_dir, name)
        shutil.copyfile(src, dst)
        h_src, h_dst = sha256(src), sha256(dst)
        if h_src != h_dst:
            raise RuntimeError("copy mismatch: " + rel)
        entries.append({"flat": name, "original": prefix + rel,
                        "size": os.path.getsize(src), "sha256": h_src})
    return entries


def main():
    if len(sys.argv) < 5:
        print("usage: rescue_final.py <RESCUE_DIR> <MORPH_P0> <PRESERVED> <MAIN_REPO>")
        return 2
    rescue, morph, preserved, main = [os.path.abspath(x) for x in sys.argv[1:5]]

    # ---- 1. morph-p0: 16 non-runtime orphans from v3 audit ----
    audit_m = json.load(open(os.path.join(rescue, "audit_morph_p0_v3.json"),
                             encoding="utf-8"))
    morph_rels = sorted(
        f["rel"] for f in audit_m["orphans"]
        if not f["rel"].endswith((".class", ".f64be")))
    m_entries = copy_flat(morph, os.path.join(rescue, "morph-p0-rescue"),
                          morph_rels, "GeoGenesis-morph-p0/")

    # ---- 2. preserved: minimal reproduction set ----
    # Rules (deliberately narrow): keep sources / build+verify scripts /
    # manifests / licenses / verified summaries and hash indexes.
    keep_ext = {".java", ".ps1", ".py", ".json", ".md", ".txt", ".gradle",
                ".kts", ".bat", ".sh", ".args", ".toml", ".cfg", ".xml"}
    skip_dirs = {"_build/original", "original/compare", "original/compare-3a7686",
                 "repro/runs", "verified/_build/runs", "anchors/refs"}
    p_rels = []
    for dp, dn, fn in os.walk(preserved):
        dn[:] = [d for d in dn if d not in {"__pycache__"}]
        rel_dir = os.path.relpath(dp, preserved).replace("\\", "/")
        if any(rel_dir == s or rel_dir.startswith(s + "/") for s in skip_dirs):
            continue
        for n in fn:
            rel = (rel_dir + "/" + n).lstrip("/")
            ext = os.path.splitext(n)[1].lower()
            if ext not in keep_ext and n not in {"manifest.json", "PROVENANCE.json",
                                                 "UNSUPPORTED-AND-GAPS.json"}:
                continue
            # large generated CSVs / logs are recorded in the audit JSON by hash
            if n.endswith(".csv") or n.endswith(".log"):
                continue
            p_rels.append(rel)
    p_rels.sort()

    # Deduplicate by content: the preserved snapshot contains 4 near-identical
    # source trees (original/**, src/**, verified/src/**, repro/**). Keeping all
    # four would bloat the rescue package ~4x with zero extra information.
    # The manifest records EVERY original path for each kept hash, so no
    # provenance is lost even though only one physical copy is stored.
    seen = {}
    for rel in p_rels:
        p = os.path.join(preserved, rel.replace("/", os.sep))
        h = sha256(p)
        seen.setdefault(h, []).append(rel)
    p_entries = copy_flat(preserved, os.path.join(rescue, "preserved-rescue"),
                          [v[0] for v in seen.values()],
                          "GeoGenesis-preserved-candidate-20261103/")
    for e in p_entries:
        e["all_original_paths"] = [
            "GeoGenesis-preserved-candidate-20261103/" + x
            for x in seen[e["sha256"]]]

    for name, entries in (("morph-p0-rescue-manifest.json", m_entries),
                          ("preserved-rescue-manifest.json", p_entries)):
        with open(os.path.join(rescue, name), "w", encoding="utf-8") as fh:
            json.dump({"schema": "offrepo-rescue/final/1.0",
                       "note": "preserved-rescue is content-deduplicated; "
                               "all_original_paths lists every source location "
                               "that had the same bytes.",
                       "files": entries}, fh, ensure_ascii=False, indent=1)

    # ---- 3. verify every copied file against ORIGINAL backup again ----
    bad = []
    for label, root, entries in (("morph", morph, m_entries),
                                 ("preserved", preserved, p_entries)):
        for e in entries:
            orig = os.path.join(root, e["original"].split("/", 1)[1].replace("/", os.sep))
            copy = os.path.join(rescue, label + "-p0-rescue" if False else
                                ("morph-p0-rescue" if label == "morph" else "preserved-rescue"),
                                e["flat"])
            if not os.path.isfile(orig) or sha256(orig) != e["sha256"] or sha256(copy) != e["sha256"]:
                bad.append(e["original"])
    print("morph-p0 rescued:", len(m_entries), "files,",
          sum(e["size"] for e in m_entries), "bytes")
    print("preserved rescued:", len(p_entries), "files,",
          sum(e["size"] for e in p_entries), "bytes")
    print("ext-info:", Counter(os.path.splitext(e["original"])[1] for e in p_entries))
    print("re-verify against originals:", "PASS" if not bad else "FAIL " + str(bad[:5]))
    print("max path length:", max(len(os.path.join(rescue, e["flat"]))
                                  for e in m_entries + p_entries))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
