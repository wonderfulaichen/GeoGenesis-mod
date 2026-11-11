#!/usr/bin/env python3
"""
Full byte-level audit of the external GeoGenesis-offrepo-20261103 backup.

For EVERY file in the backup, compute:
  - size
  - SHA-256 (independent checksum)
  - git blob SHA-1 = sha1(b"blob <size>\\0" + bytes)  (same as `git hash-object --no-filters`)

Then decide whether that exact content is recoverable elsewhere:
  - in_git_objects : blob SHA-1 present in the main repo's object database
                     (`git cat-file --batch-all-objects` covers ALL refs, history,
                      stash, reflog-reachable and unreachable objects present locally)
  - in_workspace   : blob SHA-1 present among files currently on disk in the main
                     working tree (excluding .git internals); size prefilter avoids
                     reading unrelated 29 GB
  - orphan         : neither  => content would be lost if the backup is deleted

Usage (paths given as arguments so this script itself stays pure ASCII):
  python full_audit.py <OFFREPO_ROOT> <MAIN_REPO_ROOT> [OUT_JSON]

Output: JSON with full per-file classification + console summary.
Read-only: this script never writes to the backup or to the repository.
"""

import hashlib
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict


def blob_and_sha256(path):
    """Return (git_blob_sha1, sha256_hex, size) computed from raw bytes."""
    h1 = hashlib.sha1()
    h2 = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            size += len(chunk)
            h1.update(chunk)
            h2.update(chunk)
    # git blob hash covers the header too, so restart with the prefix
    g = hashlib.sha1()
    g.update(b"blob " + str(size).encode("ascii") + b"\0")
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            g.update(chunk)
    return g.hexdigest(), h2.hexdigest(), size


def walk_files(root, skip_git=True):
    for dirpath, dirnames, filenames in os.walk(root):
        if skip_git and ".git" in dirnames:
            dirnames.remove(".git")
        for name in filenames:
            p = os.path.join(dirpath, name)
            try:
                yield os.path.relpath(p, root).replace("\\", "/"), p
            except OSError:
                continue


def main():
    if len(sys.argv) < 3:
        print("usage: full_audit.py <OFFREPO_ROOT> <MAIN_REPO_ROOT> [OUT_JSON]")
        return 2
    off_root = os.path.abspath(sys.argv[1])
    main_root = os.path.abspath(sys.argv[2])
    out_json = sys.argv[3] if len(sys.argv) > 3 else None

    if not os.path.isdir(off_root) or not os.path.isdir(main_root):
        print("FATAL: root directory missing")
        return 2

    # ---- 1. Hash every file in the backup ----
    off = []
    for rel, p in walk_files(off_root):
        try:
            b, s, n = blob_and_sha256(p)
            off.append({"rel": rel, "size": n, "blob": b, "sha256": s})
        except OSError as e:
            off.append({"rel": rel, "error": str(e)})
    print("[1] backup files hashed:", len(off))
    off_sizes = {f["size"] for f in off if "error" not in f}
    off_blobs = {f["blob"] for f in off if "error" not in f}

    # ---- 2. All blob object names in the main repo's object database ----
    cp = subprocess.run(
        ["git", "-C", main_root, "cat-file", "--batch-all-objects",
         "--batch-check=%(objectname) %(objecttype)"],
        capture_output=True, text=True, check=True)
    git_blobs = set()
    for line in cp.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1] == "blob":
            git_blobs.add(parts[0])
    print("[2] blobs in main repo object DB:", len(git_blobs))

    # ---- 3. Blob hashes of files currently on disk in the main working tree.
    # Size prefilter: most of the 29 GB cannot match any backup file size.
    ws_blobs = set()
    ws_hits = 0
    ws_scanned = 0
    for rel, p in walk_files(main_root):
        try:
            n = os.path.getsize(p)
        except OSError:
            continue
        if n not in off_sizes:
            continue
        ws_scanned += 1
        try:
            b, s, _ = blob_and_sha256(p)
        except OSError:
            continue
        if b in off_blobs:
            ws_blobs.add(b)
            ws_hits += 1
    print("[3] working-tree files size-matched & read:", ws_scanned,
          " matched backup blobs:", ws_hits, " distinct:", len(ws_blobs))

    # ---- 4. Classify ----
    counts = Counter()
    orphan = []
    ws_only = []
    git_only = []
    both = []
    errors = []
    for f in off:
        if "error" in f:
            counts["error"] += 1
            errors.append(f)
            continue
        in_git = f["blob"] in git_blobs
        in_ws = f["blob"] in ws_blobs
        if in_git and in_ws:
            tag = "both"
            both.append(f)
        elif in_git:
            tag = "git_objects"
            git_only.append(f)
        elif in_ws:
            tag = "workspace_only"
            ws_only.append(f)
        else:
            tag = "orphan"
            orphan.append(f)
        counts[tag] += 1
        f["class"] = tag

    print("\n[4] classification:")
    for k in ("both", "git_objects", "workspace_only", "orphan", "error"):
        print("   %-16s %d" % (k, counts[k]))

    total_bytes = sum(f.get("size", 0) for f in off)
    orphan_bytes = sum(f.get("size", 0) for f in orphan)
    print("   backup bytes:", total_bytes, " orphan bytes:", orphan_bytes)

    if orphan:
        print("\n   ORPHAN LIST (content nowhere else):")
        for f in orphan[:40]:
            print("     %10d  %s" % (f["size"], f["rel"]))
        if len(orphan) > 40:
            print("     ... and", len(orphan) - 40, "more")
    if ws_only:
        print("\n   WORKSPACE_ONLY LIST (not in object DB, still on disk):")
        for f in ws_only[:40]:
            print("     %10d  %s" % (f["size"], f["rel"]))
        if len(ws_only) > 40:
            print("     ... and", len(ws_only) - 40, "more")

    result = {
        "offrepo_root": off_root,
        "main_repo_root": main_root,
        "backup_file_count": len(off),
        "backup_bytes": total_bytes,
        "git_blob_count": len(git_blobs),
        "workspace_distinct_matching_blobs": len(ws_blobs),
        "classification": dict(counts),
        "orphan_bytes": orphan_bytes,
        "orphan": orphan,
        "workspace_only": ws_only,
        "git_objects": git_only,
        "both": both,
        "errors": errors,
        "all_files": off,
    }
    if out_json:
        os.makedirs(os.path.dirname(out_json) or ".", exist_ok=True)
        with open(out_json, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=1)
        print("\nJSON written:", out_json)

    # Non-zero exit makes a "problem found" impossible to overlook.
    if errors or orphan:
        print("\nAUDIT RESULT: PROBLEM (orphan or unreadable files present)")
        return 1
    print("\nAUDIT RESULT: PASS (no orphan content)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
