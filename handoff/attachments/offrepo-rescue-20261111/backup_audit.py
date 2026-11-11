#!/usr/bin/env python3
"""
Audit a backup directory against BOTH content sources, with an extra
line-ending-normalised comparison for text files (Windows CRLF checkouts).

For every file in <BACKUP> compute git blob SHA-1 of raw bytes AND of CRLF->LF
normalised bytes. A file is covered if EITHER blob appears in:
  - the main repo object DB (all refs / history / stash / unreachable objects)
  - any tree of the given extra commits (e.g. origin/proposal/theseus-main)
  - the current working tree (size prefilter, then raw + normalised hash)

Output: JSON with per-file class and console summary. Read-only.
"""
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter

TEXT_EXT = {".java", ".md", ".txt", ".json", ".gradle", ".py", ".ps1", ".csv",
            ".toml", ".cfg", ".properties", ".xml", ".yml", ".yaml", ".diff",
            ".log", ".args", ".sh", ".bat", ".gitignore", ""}


def blobs_of(path):
    with open(path, "rb") as f:
        raw = f.read()
    n = len(raw)
    def blob(data):
        h = hashlib.sha1()
        h.update(b"blob " + str(len(data)).encode() + b"\0")
        h.update(data)
        return h.hexdigest()
    b_raw = blob(raw)
    b_norm = None
    if os.path.splitext(path)[1].lower() in TEXT_EXT and b"\r\n" in raw:
        b_norm = blob(raw.replace(b"\r\n", b"\n"))
    s256 = hashlib.sha256(raw).hexdigest()
    return b_raw, b_norm, s256, n


def walk(root, skip_git=True):
    for dp, dn, fn in os.walk(root):
        if skip_git and ".git" in dn:
            dn.remove(".git")
        for name in fn:
            p = os.path.join(dp, name)
            yield os.path.relpath(p, root).replace("\\", "/"), p


def main():
    if len(sys.argv) < 4:
        print("usage: backup_audit.py <BACKUP> <MAIN_REPO> <OUT_JSON> [EXTRA_COMMIT ...]")
        return 2
    backup, main, out = [os.path.abspath(x) for x in sys.argv[1:4]]
    extras = sys.argv[4:]

    files = []
    for rel, p in walk(backup):
        try:
            br, bn, s, n = blobs_of(p)
            files.append({"rel": rel, "size": n, "blob": br, "blob_norm": bn,
                          "sha256": s})
        except OSError as e:
            files.append({"rel": rel, "error": str(e)})
    print("[1] backup files:", len(files))

    # object DB blobs
    cp = subprocess.run(["git", "-C", main, "cat-file", "--batch-all-objects",
                         "--batch-check=%(objectname) %(objecttype)"],
                        capture_output=True, text=True, check=True)
    obj = {l.split()[0] for l in cp.stdout.splitlines()
           if len(l.split()) == 2 and l.split()[1] == "blob"}
    # blobs reachable from extra commits' trees
    extra_blobs = set()
    for c in extras:
        cp = subprocess.run(["git", "-C", main, "ls-tree", "-r", c],
                            capture_output=True, text=True, check=True)
        extra_blobs |= {l.split()[2] for l in cp.stdout.splitlines()
                        if len(l.split()) >= 4}
    print("[2] object-DB blobs:", len(obj), " extra-tree blobs:", len(extra_blobs))
    git_blobs = obj | extra_blobs

    sizes = {f["size"] for f in files if "error" not in f}
    cand = {f["blob"] for f in files if "error" not in f} | \
           {f["blob_norm"] for f in files if "error" not in f and f["blob_norm"]}
    ws = set()
    for rel, p in walk(main):
        try:
            if os.path.getsize(p) not in sizes:
                continue
            br, bn, _, _ = blobs_of(p)
        except OSError:
            continue
        if br in cand:
            ws.add(br)
        if bn and bn in cand:
            ws.add(bn)
    print("[3] workspace distinct matching blobs:", len(ws))

    def covered(f):
        keys = [f["blob"]] + ([f["blob_norm"]] if f["blob_norm"] else [])
        g = any(k in git_blobs for k in keys)
        w = any(k in ws for k in keys)
        return g, w

    counts = Counter()
    orphans = []
    for f in files:
        if "error" in f:
            counts["error"] += 1
            orphans.append(f)
            continue
        g, w = covered(f)
        tag = ("both" if g and w else "git_objects" if g else
               "workspace_only" if w else "orphan")
        f["class"] = tag
        counts[tag] += 1
        if tag == "orphan":
            orphans.append(f)
    print("[4] classification:", dict(counts))
    print("    orphan bytes:", sum(f.get("size", 0) for f in orphans))
    print("AUDIT RESULT:", "PASS" if not orphans else "PROBLEM")

    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"backup": backup, "main": main, "extra_commits": extras,
                   "object_db_blobs": len(obj), "extra_tree_blobs": len(extra_blobs),
                   "classification": dict(counts), "files": files,
                   "orphans": orphans}, fh, ensure_ascii=False, indent=1)
    return 0 if not orphans else 1


if __name__ == "__main__":
    sys.exit(main())
