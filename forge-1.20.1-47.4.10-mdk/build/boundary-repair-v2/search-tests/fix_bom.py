from pathlib import Path

root = Path("build/boundary-repair-v2/search-tests")
for p in list(root.rglob("*.java")) + [Path("build/boundary-repair-v2/OrderedSpillSearch.java")]:
    b = p.read_bytes()
    stripped = b.replace(b"\xef\xbb\xbf", b"")
    if not stripped.endswith(b"\n"):
        stripped += b"\n"
    if stripped != b:
        p.write_bytes(stripped)
        print("fixed", p)
print("done")
