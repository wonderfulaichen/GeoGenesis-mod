#!/usr/bin/env python3
"""
Flatten the 19 preserved evidence files into short, collision-free names.

Reason: keeping the original 2026-11-03 directory tree produced a 262-char path
that `git add` cannot index on Windows ("Filename too long"). This script copies
each file to a short prefixed name and writes a manifest mapping the flat name
back to the ORIGINAL relative path plus its SHA-256, so no provenance is lost.

Read-only with respect to the external backup; writes only inside the rescue dir.
Usage: python flatten_evidence.py <RESCUE_DIR> <BACKUP_SUBDIR> <OFFREPO_ROOT>
"""
import hashlib
import json
import os
import shutil
import sys


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def main():
    if len(sys.argv) < 4:
        print("usage: flatten_evidence.py <RESCUE_DIR> <BACKUP_SUBDIR> <OFFREPO_ROOT>")
        return 2
    rescue = os.path.abspath(sys.argv[1])
    nested = os.path.abspath(sys.argv[2])
    offrepo = os.path.abspath(sys.argv[3])
    prefix = "backup-20261103-pre-handoff/"

    # Original short paths come from full_audit.json's orphan classification.
    audit = json.load(open(os.path.join(rescue, "full_audit.json"), encoding="utf-8"))
    import re
    runtime = [r"/run-accept/saves/", r"/run/", r"/logs/", r"\.mca$", r"\.dat_old$",
               r"\.log\.gz$", r"debug-agent\.log$", r"/advancements/.*\.json$",
               r"/stats/.*\.json$", r"usercache\.json$", r"options\.txt$",
               r"/icon\.png$", r"/level\.dat", r"session\.lock$", r"\.bak$",
               r"\.bin$", r"\.toml$"]
    def is_runtime(rel):
        return any(re.search(p, rel) for p in runtime)
    selected = [f for f in audit["orphan"] if not is_runtime(f["rel"])]

    flat_dir = os.path.join(rescue, "evidence-flat")
    os.makedirs(flat_dir, exist_ok=True)
    manifest = []
    used = set()
    for f in selected:
        rel = f["rel"]
        assert rel.startswith(prefix), rel
        short = rel[len(prefix):]
        src = os.path.join(nested, short.replace("/", os.sep))
        # Flat name: 3-digit index + last two path segments, sanitized.
        parts = short.split("/")
        tail = "__".join(parts[-2:]) if len(parts) >= 2 else parts[0]
        tail = re.sub(r"[^A-Za-z0-9._-]", "_", tail)
        name = "%02d__%s" % (len(manifest) + 1, tail)
        if name in used:
            raise RuntimeError("collision: " + name)
        used.add(name)
        dst = os.path.join(flat_dir, name)
        shutil.copyfile(src, dst)
        h = sha256(dst)
        if h != f["sha256"]:
            raise RuntimeError("hash mismatch after copy: " + short)
        # verify against original backup too
        orig = os.path.join(offrepo, rel.replace("/", os.sep))
        if sha256(orig) != h:
            raise RuntimeError("hash mismatch against backup: " + rel)
        manifest.append({"flat": name, "original": rel, "size": f["size"],
                         "sha256": h, "git_blob": f["blob"]})

    with open(os.path.join(rescue, "evidence-flat-manifest.json"), "w",
              encoding="utf-8") as fh:
        json.dump({"schema": "offrepo-rescue/flat-evidence/1.0",
                   "note": "Flat names exist because the original nested path "
                           "exceeded Windows MAX_PATH and could not be git-added.",
                   "files": manifest}, fh, ensure_ascii=False, indent=1)
    print("flattened:", len(manifest), "files, all SHA-256 verified (nested, flat, backup)")
    print("max flat path length:", max(
        len(os.path.join(flat_dir, m["flat"])) for m in manifest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
