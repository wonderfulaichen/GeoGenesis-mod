"""Source mutations for OrderedSpillSearch (verification only).

Each mutation rewrites the *subject* source with a single anchor replacement. The runner compiles
the mutant, proves the mutation compiled (exit 0), and requires the named assertion to turn red.
An anchor that does not occur exactly once aborts: a silent no-op mutation would fake a green.
"""
from pathlib import Path
import sys

SUBJECT = Path("build/boundary-repair-v2/OrderedSpillSearch.java")

MUTATIONS = {
    # far jump: the BFS drops the crest gate, so the tie-break walks cells above the crest
    "far-jump": {
        "old": "if (seen[to] || bed[to] > crest || guards.blocked(to) || guards.blockedEdge(k, to)) continue;",
        "new": "if (seen[to] || guards.blocked(to) || guards.blockedEdge(k, to)) continue;",
        "expect_red": ["syn/cheap/crest-beats-shorter-corridor/endpoint",
                       "syn/cheap/crest-beats-shorter-corridor/path-crest"],
        "why": "the BFS walks the 58 wall it was supposed to avoid: the reported crest stays 52 but "
               "the returned path crosses 58, so the endpoint and the path-crest check both fail",
    },
    # ignore boundary: settle() never records a frame contact, so a reachable frame cell below the
    # crest no longer stops the search and the "nothing reachable" case turns into a false NO_ESCAPE
    "ignore-boundary": {
        "old": "if (i == 0 || j == 0 || i == nx - 1 || j == nz - 1) boundary = Math.min(boundary, node.crest());",
        "new": "if (false) boundary = Math.min(boundary, node.crest());",
        "expect_red": ["syn/cap/frame-at-cap/status"],
        "why": "the frame is reachable at exactly the cap but no terminal exists; with no boundary "
               "recorded the search answers NO_ESCAPE instead of keeping DOMAIN_PENDING (the "
               "strictly-cheaper window is still caught by the BFS frame test, so it stays green)",
    },
    # counter-mutation to the D3 fix: put the old `<=` back. A same-crest frame contact is then
    # rejected in the crest phase, so the "farther contact must still certify" windows go red.
    "boundary-le-regression": {
        "old": "if (candidate != null && boundary < candidate.crest()) return result(Status.DOMAIN_PENDING);",
        "new": "if (candidate != null && boundary <= candidate.crest()) return result(Status.DOMAIN_PENDING);",
        "expect_red": [
            "syn/domain/frame-farther-than-endpoint-must-certify/status",
            "syn/domain/equal-crest-frame-17-layers-out-must-certify/status",
            "syn/domain/equal-crest-15-layers-out-must-certify/status",
        ],
        "why": "restores the old `boundary <= crest` shortcut: any same-crest frame contact rejects "
               "before the BFS distance test, so the three must-ESCAPE windows fail",
    },
    # exhaustion reported as NO_ESCAPE. The anchor keeps the following line so it stays unique:
    # both batch() and shortestAtCrest() contain the bare EXHAUSTED statement.
    "exhausted-as-no-escape": {
        "old": ("if (expanded >= totalLimit) return result(Status.EXHAUSTED);\n"
                "            if (count >= batchLimit) return null;\n"
                "            queue.poll();"),
        "new": ("if (expanded >= totalLimit) return result(Status.NO_ESCAPE);\n"
                "            if (count >= batchLimit) return null;\n"
                "            queue.poll();"),
        "expect_red": ["syn/budget/exhausted/status"],
        "why": "the budget window must report EXHAUSTED (=unknown); NO_ESCAPE would claim the pit is "
               "closed",
    },
    # per-batch budget: let shortestAtCrest() ignore the batch allowance it was handed, so the two
    # phases no longer share one budget and a single batch can overshoot.
    "batch-budget-ignored": {
        "old": "if (count >= batchLimit) return null;\n            int k = bfs.removeFirst(), i = k / nz, j = k % nz;",
        "new": "int k = bfs.removeFirst(), i = k / nz, j = k % nz;",
        "expect_red": ["batch/len/crest-beats-shortcut/limit-1/per-batch-budget",
                       "batch/len/crest-beats-shortcut/limit-2/per-batch-budget"],
        "why": "the tie-break BFS stops honouring the batch allowance, so one batch settles both "
               "phases and exceeds the requested limit",
    },
    # completed-result stability: drop the cache so every later call re-enters the search
    "no-completed-cache": {
        "old": "if (completed != null) return completed;\n        int count = 0;",
        "new": "int count = 0;",
        "expect_red": ["repeat/len/crest-beats-shortcut/identity-cached",
                       "repeat/len/crest-beats-shortcut/same-status"],
        "why": "without the completed cache a repeated find() re-enters a drained queue and answers a "
               "different result instead of the cached one",
    },
    # candidate path snapshot: keep the pointer but rebuild it from the crest-phase parent[]
    "candidate-path-stale": {
        "old": "candidate = new Node(chosen, crest); candidatePath = pathTo(chosen, bfsParent);",
        "new": "candidate = new Node(chosen, crest); candidatePath = pathTo(chosen, parent);",
        "expect_red": [
            "real/fixture/path-is-walk",
            "real/fixture/oracle-len",
            "syn/domain/frame-farther-than-endpoint-must-certify/len",
        ],
        "why": "the tie-break stores its path from the crest-phase parent[] instead of bfsParent, so "
               "the returned path is not the BFS walk: the frozen window collapses to a 1-cell path "
               "and the plateau windows return crest-phase-length detours",
    },
    # the BFS overwrites the snapshot with a truncated one: only the endpoint survives
    "bfs-overwrites-candidate-path": {
        "old": "candidate = new Node(chosen, crest); candidatePath = pathTo(chosen, bfsParent);",
        "new": "candidate = new Node(chosen, crest); candidatePath = new int[]{chosen};",
        "expect_red": ["batch/mid-bfs/unbounded-path-walkable",
                       "syn/len/crest-beats-shortcut/path-is-walk",
                       "syn/tie/world-order/path-is-walk"],
        "why": "the tie-break replaces the snapshotted witness with its endpoint alone, so every "
               "BFS-decided answer loses its walk (the mid-BFS EXHAUSTED path happens to be built "
               "before this line, which is why the unbounded run is the one that reds)",
    },
    # NOTE: a "make the BFS write into the crest-phase parent[] as well" mutation was tried and
    # REMOVED. Both trees are shortest in *length* at the crest, so swapping which one is walked
    # yields a path of identical length that still satisfies start/adjacent/crest — no contract
    # assertion can distinguish it (checked on 200k randomised 6x6 windows with
    # probe_bfs_vs_crest_parent.py: the two chains never differ in an observable way). Keeping it
    # would have produced a fake red, i.e. exactly the "no teeth" failure this run is meant to avoid.
    # The load-bearing half of that change is `candidate-path-stale`, which does red.
    # frontier/layer retention: reset the BFS queue on every batch so cross-batch state is lost
    "bfs-restarts-each-batch": {
        "old": "if (bfs == null) {\n            bfs = new java.util.ArrayDeque<>(); seen = new boolean[bed.length];",
        "new": "if (true) {\n            bfs = new java.util.ArrayDeque<>(); seen = new boolean[bed.length];",
        "expect_red": ["batch/len/crest-beats-shortcut/limit-1/same-result"],
        "why": "restarting the BFS every batch throws away the frontier, so a fine-grained batchLimit "
               "cannot reach the same answer as find()",
    },
}


def anchors(spec):
    """Normalises a mutation spec to a list of (old, new) pairs."""
    if "anchors" in spec:
        return spec["anchors"]
    return [(spec["old"], spec["new"])]


def apply(name: str, target: Path) -> None:
    spec = MUTATIONS[name]
    text = SUBJECT.read_text(encoding="utf-8")
    for old, new in anchors(spec):
        n = text.count(old)
        if n != 1:
            raise SystemExit(f"anchor for {name} occurs {n} times, expected exactly 1: {old[:90]!r}")
        text = text.replace(old, new)
    target.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    if sys.argv[1] == "--expect-red":
        name, target = sys.argv[2], Path(sys.argv[3])
        target.write_text("|".join(MUTATIONS[name]["expect_red"]), encoding="utf-8")
        print("|".join(MUTATIONS[name]["expect_red"]))
        raise SystemExit(0)
    name = sys.argv[1]
    target = Path(sys.argv[2])
    target.parent.mkdir(parents=True, exist_ok=True)
    apply(name, target)
    print(f"MUTATED {name} -> {target}")
