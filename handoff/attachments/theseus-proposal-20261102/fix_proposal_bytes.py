#!/usr/bin/env python3
"""Restore the exact bytes of the archived proposal doc from git (read-only).

PowerShell's `git show | Out-String -join "`n"` re-wrote CRLF to LF in one
of the four files. This script writes raw bytes from `git show` so that
`raw == local` holds for all four files.
"""
import subprocess
import sys

REF = "origin/proposal/theseus-main"
PATH = "handoff/theseus-tech-proposal-20261102.md"

raw = subprocess.run(["git", "show", REF + ":" + PATH],
                     capture_output=True, check=True).stdout
with open(PATH, "wb") as f:
    f.write(raw)
local = open(PATH, "rb").read()
print("raw bytes:", len(raw), "local bytes:", len(local), "identical:", raw == local)
sys.exit(0 if raw == local else 1)
