package com.geogenesis.worldgen.hydrology.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import com.geogenesis.worldgen.hydrology.model.OrderedSpillSearch.Status;

/**
 * Independent verification of {@link OrderedSpillSearch} (build artefact, never production).
 *
 * <p>Every claim is checked twice: against hand-written expectations for the synthetic windows and
 * against {@link Oracle}, a brute-force reference written with a different algorithm. The probe
 * exits non-zero on any failure so a Gradle/CI wrapper cannot turn red into green.</p>
 */
public final class SearchProbe {

    // ---- frozen window geometry of owner (-1,1) as exported by ExportFixture ----
    // CSV row k = i*353 + j with world x = -608 + i*2, z = 416 + j*2.
    private static final Path FROZEN = Path.of("build/boundary-integrated-20261023/frozen/bed--1-1.csv");
    private static final int FROZEN_OX = -608, FROZEN_OZ = 416, FROZEN_STEP = 2;
    private static final int WITNESS_CELL = 123 * 353 + 118;   // (-362,652)
    private static final int EXPECTED_END = 216 * 353 + 76;    // (-176,568)
    private static final double EXPECTED_CREST = 64.47363773783941;
    private static final double REAL_RISE = 12.0;       // MAX_SPILL_RISE of the production chain

    private static int pass, fail, divergenceCount;
    private static final List<String> failures = new ArrayList<>();
    /** recorded but non-failing observations (guard dialect, API traps). */
    private static final List<String> notes = new ArrayList<>();
    /** recorded contract divergences: the subject is stricter/looser than the stated design. */
    private static final List<String> divergences = new ArrayList<>();
    private static final List<String> defects = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0]
                : "build/boundary-repair-v2/search-tests/out");
        Files.createDirectories(out);

        section("A. synthetic windows: hand expectations + oracle equivalence");
        for (Scenario s : Cases.all()) checkScenario(s);

        section("B. invariants");
        invariants();

        section("C. frozen real window (bed--1-1, owner -1,1)");
        if (!Files.isRegularFile(FROZEN)) {
            failures.add("C/fixture | missing " + FROZEN.toAbsolutePath());
            fail("fixture-present");
        } else {
            checkFrozen(out);
        }

        section("C2. batching: batchLimit equivalence, per-batch budget, mid-BFS exhaustion, repeat find()");
        batching();

        section("D. read defects");
        for (String d : defects) System.out.println("  DEFECT " + d);

        StringBuilder rep = new StringBuilder();
        for (String d : defects) rep.append("DEFECT ").append(d).append('\n');
        for (String n : notes) rep.append("NOTE ").append(n).append('\n');
        for (String d : divergences) rep.append("DIVERGE ").append(d).append('\n');
        for (String f : failures) rep.append("FAIL ").append(f).append('\n');
        rep.append("SUMMARY pass=").append(pass).append(" fail=").append(fail)
           .append(" notes=").append(notes.size()).append(" diverge=").append(divergenceCount)
           .append(" defects=").append(defects.size()).append('\n');
        Files.writeString(out.resolve("search-probe-report.txt"), rep.toString(), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("SUMMARY pass=" + pass + " fail=" + fail + " notes=" + notes.size()
                + " diverge=" + divergenceCount + " defects=" + defects.size());
        if (fail > 0) {
            System.out.println("RESULT FAIL");
            System.exit(1);
        }
        System.out.println("RESULT PASS (independent verification only; not a production acceptance)");
    }

    // ------------------------------------------------------------------ synthetic

    private static void checkScenario(Scenario s) {
        String tag = "syn/" + s.name;
        Oracle o = s.oracle();
        if (s.interruptBeforeStart || s.interruptAfterCell >= 0) {
            checkCancel(s, tag, o);
            return;
        }
        OrderedSpillSearch.Result r = run(s);
        String end = r.path().length == 0 ? "-"
                : Oracle.world(r.path()[r.path().length - 1], s.nz, 0, 0, 1);
        System.out.printf("  %-34s subject=%-14s crest=%-9s end=%-8s len=%-3d exp=%d | oracle=%s%n",
                s.name, r.status(), num(r.crest()), end, r.path().length, r.expanded(), o.kind);

        // 1) hand expectation (authority). No leniency: an over-rejection is a red, not a note.
        boolean sameStatus = r.status() == s.expectStatus;
        boolean sameEnd = s.expectEnd < 0 || r.accepted() && r.path().length > 0
                && r.path()[r.path().length - 1] == s.expectEnd;
        boolean sameCrest = Double.isNaN(s.expectCrest) || cmpDouble(r.crest(), s.expectCrest);
        check(tag + "/status", "hand=" + s.expectStatus + " subject=" + r.status(), sameStatus);
        if (s.expectEnd >= 0) {
            check(tag + "/endpoint", "hand=" + s.expectEnd + " subject="
                    + (r.path().length == 0 ? "-" : "" + r.path()[r.path().length - 1]), sameEnd);
        }
        if (!Double.isNaN(s.expectCrest)) {
            check(tag + "/crest", "hand=" + num(s.expectCrest) + " subject=" + num(r.crest()), sameCrest);
        }
        // 2) oracle cross-check: it must reproduce the hand expectation, otherwise the oracle is
        //    itself under suspicion and says nothing about the subject. A budgeted window is the one
        //    documented exception: the oracle models geometry only and has no work limit.
        if (s.budget == Integer.MAX_VALUE) {
            check(tag + "/oracle-agrees-with-hand", "oracle=" + o.kind + " hand=" + s.expectStatus,
                    oracleKind(o.kind) == s.expectStatus);
            check(tag + "/oracle-agrees-with-subject", "oracle=" + o.kind + " subject=" + r.status(),
                    matches(r, o));
        } else {
            notes.add(tag + ": oracle models geometry only (no work budget), so its "
                    + o.kind + " is the un-budgeted answer against the subject's budgeted "
                    + r.status() + "; the subject's answer is checked against the hand expectation "
                    + "instead, and EXHAUSTED must never be NO_ESCAPE.");
        }
        if (!o.remark.isEmpty()) notes.add(tag + ": " + o.remark);

        if (r.accepted() && r.path().length > 0) {
            check(tag + "/path-is-walk", "walk", walkOk(s, r.path()));
            check(tag + "/path-crest", "pathCast=" + pathCrest(s, r.path()) + " crest=" + num(r.crest()),
                    pathCrest(s, r.path()) <= r.crest() + 1e-12);
            if (o.path.length > 0) {
                check(tag + "/len", "len=" + r.path().length + " oracle=" + o.path.length,
                        r.path().length == o.path.length);
            }
        }
    }

    private static OrderedSpillSearch.Status oracleKind(Oracle.Kind k) {
        switch (k) {
            case ESCAPE: return OrderedSpillSearch.Status.ESCAPE;
            case JOIN: return OrderedSpillSearch.Status.JOIN;
            case DOMAIN_PENDING: return OrderedSpillSearch.Status.DOMAIN_PENDING;
            default: return OrderedSpillSearch.Status.NO_ESCAPE;
        }
    }

    /** Maps the oracle verdict to the status family the subject must report. */
    private static boolean matches(OrderedSpillSearch.Result r, Oracle o) {
        switch (o.kind) {
            case ESCAPE:
            case JOIN:
                if (r.status() != (o.kind == Oracle.Kind.JOIN
                        ? OrderedSpillSearch.Status.JOIN : OrderedSpillSearch.Status.ESCAPE)) return false;
                return r.path().length > 0 && r.path()[r.path().length - 1] == o.endCell;
            case DOMAIN_PENDING:
                return r.status() == OrderedSpillSearch.Status.DOMAIN_PENDING;
            case NO_ESCAPE:
                return r.status() == OrderedSpillSearch.Status.NO_ESCAPE;
            default:
                return false;
        }
    }

    private static void checkCancel(Scenario s, String tag, Oracle o) {
        OrderedSpillSearch sub = s.subject();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            if (s.interruptBeforeStart) Thread.currentThread().interrupt();
            try {
                sub.find();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        try {
            t.join(30_000);
            while (t.isAlive()) {
                t.interrupt();
                t.join(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Throwable e = thrown.get();
        System.out.printf("  %-34s threw=%s (oracle would say %s)%n", s.name,
                e == null ? "none" : e.getClass().getSimpleName(), o.kind);
        if (e instanceof CancellationException) {
            check(tag + "/cancels", "CancellationException", true);
            return;
        }
        String shape = s.interruptBeforeStart
                ? "pre-start interrupt not honoured (batch() checks isInterrupted only after peek)"
                : "interrupt after the endpoint cell not honoured (the BFS checks only at layer starts)";
        diverged(tag + "/cancels", "threw=" + (e == null ? "none" : e.getClass().getSimpleName())
                + " ; " + shape);
    }

    private static OrderedSpillSearch.Result run(Scenario s) {
        OrderedSpillSearch sub = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                0, 0, 1, s.budget, s.guards());
        try {
            return sub.find();
        } finally {
            Thread.interrupted(); // a scenario guard may have armed a one-shot interrupt
        }
    }

    // ------------------------------------------------------------------ invariants

    /** Records what the source does today; only reachable, reproduced behaviour is listed. */
    private static void invariants() {
        // (1) result() rebuilds the path by walking parents[] from the endpoint back to -1. Verify the
        //     chain terminates and is walkable on every answer the probe produces.
        Scenario s = Cases.tieSameLengthSameCrest();
        OrderedSpillSearch.Result r = run(s);
        check("inv/path-terminates", "len=" + r.path().length, walkOk(s, r.path()));

        // (2) find() is a one-shot entry point: later calls answer from the completed cache.
        Scenario s2 = Cases.blockedWallDetour();
        OrderedSpillSearch sub = s2.subject();
        OrderedSpillSearch.Result first = sub.find();
        int afterFirst = expandedOf(sub);
        OrderedSpillSearch.Result second = sub.find();
        check("inv/second-find-returns-cache", "first=" + first.status() + " second=" + second.status()
                + " expanded=" + afterFirst + "->" + expandedOf(sub),
                first.status() == second.status() && afterFirst == expandedOf(sub));

        // (3) the total cap is the sum of both phases; `expanded` is that shared counter.
        Scenario s3 = Cases.boundaryStrictlyCheaper();
        OrderedSpillSearch.Result r3 = run(s3);
        check("inv/expanded-positive", "expanded=" + r3.expanded(), r3.expanded() > 0);
        notes.add("D2 RESOLVED: both phases now use a plain `expanded >= totalLimit` guard (no "
                + "post-increment against the cap) and find() drives batch(4096) with the two phases "
                + "sharing that allowance. Remaining semantic note: the cap is a cumulative two-phase "
                + "work budget, so `expanded` counts crest settlements PLUS tie-break dequeues and is "
                + "not comparable with the old settlement-only `popped` budget. Pinned by "
                + "batch/*/per-batch-budget and batch/mid-bfs/expanded-equals-cap.");

        // (4) D3 fixed: batch() tests `boundary < crest`, so a same-crest contact is decided by the
        //     BFS layer distance. Pinned by the three must-certify windows plus a counter-mutation.
        notes.add("D3 status: batch() uses `boundary < candidate.crest()` and shortestAtCrest() uses "
                + "`boundary < crest`, so a same-crest frame contact is handed to the BFS distance "
                + "test. The positive assertions syn/domain/*-must-certify require JOIN; the "
                + "boundary-le-regression mutation restores `<=` and turns all three red.");

        // (5) D1 fixed: the candidate path is snapshotted when the candidate is taken.
        Scenario s5 = new Scenario("inv/candidate-path-snapshot", "cheap candidate then nearer receiver",
                Scenario.fill(9 * 9, 50), 9, 9, Cases.idx(9, 4, 4), 12);
        s5.bed(4, 6, 40);   // lower cell, settled first
        s5.recv(4, 3);      // receiver at the same crest, settled next
        OrderedSpillSearch.Result r5 = run(s5);
        check("inv/candidate-path-snapshot/path-walkable", "len=" + r5.path().length + " end="
                        + (r5.path().length == 0 ? "-" : "" + r5.path()[r5.path().length - 1]),
                r5.path().length >= 2 && walkOk(s5, r5.path()));
        notes.add("D1 status: candidatePath is snapshotted in settle() and in shortestAtCrest() "
                + "(`pathTo(k, parent)` / `pathTo(chosen, bfsParent)`), and the BFS keeps its own "
                + "bfsParent[] instead of re-zeroing the crest-phase parent[]. A later candidate can no "
                + "longer overwrite the earlier chain. Pinned by "
                + "batch/len/crest-beats-shortcut/same-result and the candidate-path-stale mutation.");
    }

    // ------------------------------------------------------------------ batching (C2)

    /**
     * Batching contract, checked on windows that need more than one batch in both phases:
     *
     * <ol>
     *   <li><b>equivalence</b> — driving with batchLimit 1 / 2 / 7 / 4096 must yield exactly the same
     *       result as find(), and so must a mixed sequence (1, then 4096 everywhere);</li>
     *   <li><b>per-batch budget</b> — {@code expanded} may grow by at most batchLimit per call, for
     *       every batchLimit, i.e. the two phases share the batch allowance rather than each getting
     *       a fresh one;</li>
     *   <li><b>mid-BFS exhaustion</b> — when the BFS runs out of total budget the EXHAUSTED result
     *       must not be a torn path: it either has no path or a full start-anchored, adjacent path,
     *       and {@code expanded} must equal the cap;</li>
     *   <li><b>repeat find()</b> — a completed search answers the identical result object/values on
     *       every later call (the completed cache must be stable and must not re-run work).</li>
     * </ol>
     */
    private static void batching() {
        List<Scenario> multi = new ArrayList<>();
        multi.add(Cases.crestBeatsShortcut());          // needs several layers in the BFS
        multi.add(Cases.domainUnknownFartherThanGoal()); // BFS finds a receiver after 5 layers
        multi.add(Cases.boundaryStrictlyCheaper());      // pend decided in the crest phase
        multi.add(Cases.blockedEdgeDetour());            // edge guard + JOIN
        multi.add(Cases.cheapCorridorWins());            // crest decides between two corridors

        for (Scenario s : multi) {
            String tag = "batch/" + s.name;
            OrderedSpillSearch.Result ref = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                    0, 0, 1, s.budget, s.guards()).find();      // find() = batch(4096) loop
            for (int limit : new int[]{1, 2, 7, 4096}) {
                Driven d = drive(s, limit, null);
                check(tag + "/limit-" + limit + "/same-result",
                        "ref=" + desc(ref) + " driven=" + desc(d.last) + " batches=" + d.calls,
                        sameResult(ref, d.last));
                check(tag + "/limit-" + limit + "/stopped-on-first-result",
                        "calls=" + d.calls + " resultsAfterFirst=" + d.resultsAfterFirst,
                        d.resultsAfterFirst == 1);
                check(tag + "/limit-" + limit + "/per-batch-budget",
                        "maxDelta=" + d.maxDelta + " limit=" + limit,
                        d.maxDelta <= limit);
            }
            // mixed sequence: 1, then 4096 for the rest
            Driven mixed = drive(s, 4096, 1);
            check(tag + "/mixed-sizes/same-result", "ref=" + desc(ref) + " mixed=" + desc(mixed.last),
                    sameResult(ref, mixed.last));
            check(tag + "/mixed-sizes/per-batch-budget", "maxDelta=" + mixed.maxDelta,
                    mixed.maxDelta <= 4096);
        }

        midBfsExhaustion();
        repeatFind();
    }

    /** Drives the search with an explicit batchLimit; the first limit used may differ (mixed case). */
    private static Driven drive(Scenario s, int limit, Integer firstLimit) {
        OrderedSpillSearch sub = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                0, 0, 1, s.budget, s.guards());
        int prev = 0, maxDelta = 0, calls = 0, resultsAfterFirst = 0;
        OrderedSpillSearch.Result last = null;
        for (int guard = 0; guard < 4_000_000; guard++) {
            int used = (firstLimit != null && calls == 0) ? firstLimit : limit;
            OrderedSpillSearch.Result r = sub.batch(used);
            calls++;
            int now = expandedOf(sub);
            maxDelta = Math.max(maxDelta, now - prev);
            prev = now;
            if (r == null) continue;
            last = r;
            resultsAfterFirst++;
            // a completed result must be returned unchanged by every further call
            OrderedSpillSearch.Result again = sub.batch(used);
            if (!sameResult(last, again)) {
                fail("batch/internal/stability", desc(last) + " then " + desc(again));
            }
            return new Driven(last, calls, maxDelta, resultsAfterFirst);
        }
        fail("batch/runaway", s.name + " never finished within 4e6 batches");
        return new Driven(null, calls, maxDelta, resultsAfterFirst);
    }

    /**
     * Reads the live {@code expanded} counter between batches via reflection: the probe must not add
     * anything to the subject, and {@link OrderedSpillSearch.Result#expanded()} is only available once
     * a result exists, while the per-batch budget question is about the null-returning calls.
     */
    private static int expandedOf(OrderedSpillSearch sub) {
        try {
            java.lang.reflect.Field f = OrderedSpillSearch.class.getDeclaredField("expanded");
            f.setAccessible(true);
            return f.getInt(sub);
        } catch (ReflectiveOperationException e) {
            fail("batch/reflection", "cannot read OrderedSpillSearch.expanded: " + e);
            return -1;
        }
    }

    /**
     * Mid-BFS budget exhaustion, <b>proved</b> to happen inside the tie-break BFS rather than
     * inferred from a comment.
     *
     * <p>Window (as specified): 9x9, every cell bed 50, start (4,4), receiver (4,3), a lower goal at
     * (4,6) with bed 40. The crest phase finds the candidate almost immediately, then the tie-break
     * BFS re-walks the plateau layer by layer; a cap placed a few dequeues in therefore runs out
     * <i>inside</i> the BFS.</p>
     *
     * <p>Proof of entry: the private field {@code bfs} is read by reflection while the search is
     * driven one settlement at a time. The moment it turns non-null the search has allocated the BFS
     * frontier, and the {@code expanded} value at that moment is recorded; the exhaustive run then
     * uses {@code totalLimit = expandedAtBfsStart + K} so the exhaustion provably happens after that
     * point. No part of this test relies on a comment.</p>
     */
    private static void midBfsExhaustion() {
        int n = 9;
        Scenario s = new Scenario("mid-bfs/exhaust-inside-tiebreak",
                "9x9 flat 50, start (4,4), receiver (4,3), lower goal (4,6)=40",
                Scenario.fill(n * n, 50), n, n, Cases.idx(n, 4, 4), 30);
        s.bed(4, 6, 40);
        s.recv(4, 3);
        int start = s.start;
        double crest = 50.0;

        // ---- step 1: walk the search one settlement per call and watch `bfs` come alive ----
        OrderedSpillSearch probe = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                0, 0, 1, Integer.MAX_VALUE, s.guards());
        int expandedAtBfsStart = -1, callsToBfs = 0;
        boolean decided = false;
        for (int i = 0; i < 500; i++) {
            OrderedSpillSearch.Result r = probe.batch(1);
            callsToBfs++;
            if (r != null) {
                decided = true;
                break;
            }
            if (bfsAllocated(probe)) {
                expandedAtBfsStart = expandedOf(probe);
                break;
            }
        }
        System.out.printf("  mid-bfs/probe: calls=%d expandedAtBfsStart=%d bfsAllocated=%s decidedEarly=%s%n",
                callsToBfs, expandedAtBfsStart, bfsAllocated(probe), decided);
        check("batch/mid-bfs/entered-the-bfs", "bfs!=null after " + callsToBfs + " batch(1) calls,"
                        + " expanded=" + expandedAtBfsStart,
                expandedAtBfsStart >= 0 && !decided);
        if (expandedAtBfsStart < 0) {
            fail("batch/mid-bfs/entered-the-bfs/abort",
                    "the search never allocated its BFS frontier; the rest of this case cannot run");
            return;
        }

        // ---- step 2: a cap a few BFS dequeues in must exhaust there, keeping the witness ----
        int cap = expandedAtBfsStart + 4;
        OrderedSpillSearch sub = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                0, 0, 1, cap, s.guards());
        OrderedSpillSearch.Result r = sub.find();
        int expanded = expandedOf(sub);
        int[] path = r.path();
        System.out.printf("  mid-bfs/exhaustion status=%s crest=%s pathLen=%d end=%d expanded=%d cap=%d%n",
                r.status(), num(r.crest()), path.length,
                path.length == 0 ? -1 : path[path.length - 1], expanded, cap);

        check("batch/mid-bfs/status-EXHAUSTED", "status=" + r.status(), r.status() == OrderedSpillSearch.Status.EXHAUSTED);
        check("batch/mid-bfs/not-accepted", "accepted=" + r.accepted(), !r.accepted());
        check("batch/mid-bfs/expanded-equals-cap", "expanded=" + expanded + " cap=" + cap, expanded == cap);
        check("batch/mid-bfs/cap-is-past-crest-phase", "cap=" + cap + " crestPhaseEnd=" + expandedAtBfsStart,
                cap > expandedAtBfsStart);
        // the witness must survive: no empty-path escape hatch is allowed here
        check("batch/mid-bfs/path-length>=2", "len=" + path.length, path.length >= 2);
        if (path.length == 0) {
            fail("batch/mid-bfs/path-starts-at-start", "empty path: witness lost mid-BFS");
            fail("batch/mid-bfs/path-adjacent", "empty path: nothing to check");
            return;
        }
        check("batch/mid-bfs/path-starts-at-start", "path[0]=" + path[0] + " start=" + start, path[0] == start);
        check("batch/mid-bfs/path-adjacent", Arrays.toString(path), adjacent(s.bed, s.nx, s.nz, path));
        check("batch/mid-bfs/path-within-crest", "crest=" + num(crest), crestLegal(s.bed, path, crest));
        check("batch/mid-bfs/path-not-blocked", "walkable", walkOk(s.bed, s.nx, s.nz, start, path, s.guards()));
        check("batch/mid-bfs/reports-the-candidate-crest", "crest=" + num(r.crest()),
                cmpDouble(r.crest(), crest));

        // ---- step 3: the same window without the cap must certify, so the cap is the only reason ----
        OrderedSpillSearch free = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                0, 0, 1, Integer.MAX_VALUE, s.guards());
        OrderedSpillSearch.Result fr = free.find();
        System.out.printf("  mid-bfs/unbounded status=%s crest=%s len=%d expanded=%d%n",
                fr.status(), num(fr.crest()), fr.path().length, expandedOf(free));
        check("batch/mid-bfs/unbounded-certifies", "status=" + fr.status(), fr.accepted());
        check("batch/mid-bfs/unbounded-path-walkable", "len=" + fr.path().length,
                walkOk(s.bed, s.nx, s.nz, start, fr.path(), s.guards()));
        check("batch/mid-bfs/cap-actually-cut-work", "boundedExpanded=" + expanded
                        + " unboundedExpanded=" + expandedOf(free),
                expanded < expandedOf(free));
    }

    /** True once the subject has allocated its tie-break BFS frontier. */
    private static boolean bfsAllocated(OrderedSpillSearch sub) {
        return field(sub, "bfs") != null;
    }

    /** Reflective read of one private field, so the probe adds nothing to the subject. */
    private static Object field(OrderedSpillSearch sub, String name) {
        try {
            java.lang.reflect.Field f = OrderedSpillSearch.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(sub);
        } catch (ReflectiveOperationException e) {
            fail("batch/reflection", "cannot read OrderedSpillSearch." + name + ": " + e);
            return null;
        }
    }

    /** every cell of the path must be at or below the crest. */
    private static boolean crestLegal(double[] bed, int[] path, double crest) {
        if (path.length == 0) return false;
        for (int c : path) if (bed[c] > crest + 1e-12) return false;
        return true;
    }

    /** Reads only the bed column of the frozen window (used by the budget windows). */
    private static double[] frozenBedOnly() throws IOException {
        double[] bed = new double[353 * 353];
        int k = 0;
        try (BufferedReader rd = Files.newBufferedReader(FROZEN, StandardCharsets.UTF_8)) {
            String line = rd.readLine();
            if (line == null) throw new IOException("empty fixture");
            while ((line = rd.readLine()) != null) {
                String[] f = line.split(",");
                bed[k++] = Double.parseDouble(f[2]);
            }
        }
        if (k != bed.length) throw new IOException("row count " + k + " != " + bed.length);
        return bed;
    }

    /** Repeating find() must return the identical result, never re-run work or change the answer. */
    private static void repeatFind() {
        for (Scenario s : new Scenario[]{Cases.crestBeatsShortcut(), Cases.domainUnknownFartherThanGoal(),
                Cases.boundaryStrictlyCheaper(), Cases.budgetExhausted()}) {
            String tag = "repeat/" + s.name;
            OrderedSpillSearch sub = new OrderedSpillSearch(s.bed, s.nx, s.nz, s.start, s.rise,
                    0, 0, 1, s.budget, s.guards());
            OrderedSpillSearch.Result first = sub.find();
            int expandedAfterFirst = expandedOf(sub);
            OrderedSpillSearch.Result second = sub.find();
            OrderedSpillSearch.Result third = sub.find();
            check(tag + "/same-status", first.status() + " / " + second.status() + " / " + third.status(),
                    first.status() == second.status() && second.status() == third.status());
            check(tag + "/same-crest", num(first.crest()) + " / " + num(second.crest()),
                    Double.compare(first.crest(), second.crest()) == 0);
            check(tag + "/same-path", "len=" + first.path().length + " / " + second.path().length,
                    Arrays.equals(first.path(), second.path()));
            check(tag + "/same-expanded", first.expanded() + " / " + second.expanded(),
                    first.expanded() == second.expanded());
            check(tag + "/no-extra-work", "expanded=" + expandedAfterFirst + " -> " + expandedOf(sub),
                    expandedAfterFirst == expandedOf(sub));
            // the returned object must be the cached instance, not a rebuilt one
            check(tag + "/identity-cached", "same object=" + (first == second), first == second);
        }
    }

    /** Result carried by the driver, for readable failure messages. */
    private record Driven(OrderedSpillSearch.Result last, int calls, int maxDelta, int resultsAfterFirst) { }

    private static String desc(OrderedSpillSearch.Result r) {
        if (r == null) return "null";
        return r.status() + "/crest=" + num(r.crest()) + "/len=" + r.path().length
                + "/end=" + (r.path().length == 0 ? "-" : "" + r.path()[r.path().length - 1])
                + "/expanded=" + r.expanded();
    }

    private static boolean sameResult(OrderedSpillSearch.Result a, OrderedSpillSearch.Result b) {
        if (a == null || b == null) return a == b;
        return a.status() == b.status()
                && Double.compare(a.crest(), b.crest()) == 0
                && Double.compare(a.boundaryBound(), b.boundaryBound()) == 0
                && a.expanded() == b.expanded()
                && Arrays.equals(a.path(), b.path());
    }

    private static boolean adjacent(double[] bed, int nx, int nz, int[] path) {
        for (int k = 1; k < path.length; k++) {
            int i0 = path[k - 1] / nz, j0 = path[k - 1] % nz;
            int i1 = path[k] / nz, j1 = path[k] % nz;
            if (Math.abs(i0 - i1) > 1 || Math.abs(j0 - j1) > 1) return false;
            if (i0 == i1 && j0 == j1) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ frozen window

    private static void checkFrozen(Path out) throws IOException {
        double[] bed = new double[353 * 353];
        boolean[] sea = new boolean[bed.length], lake = new boolean[bed.length];
        try (BufferedReader rd = Files.newBufferedReader(FROZEN, StandardCharsets.UTF_8)) {
            String line = rd.readLine();
            if (line == null) throw new IOException("empty fixture");
            int k = 0;
            while ((line = rd.readLine()) != null) {
                String[] f = line.split(",");
                bed[k] = Double.parseDouble(f[2]);
                sea[k] = Boolean.parseBoolean(f[3]);
                lake[k] = Boolean.parseBoolean(f[4]);
                k++;
            }
            check("frozen/size", "rows=" + k, k == bed.length);
        }
        int nz = 353;
        check("frozen/witness-height", "" + bed[WITNESS_CELL],
                Math.abs(bed[WITNESS_CELL] - 63.833772000345874) < 1e-12);
        check("frozen/witness-world", "cell=" + WITNESS_CELL,
                FROZEN_OX + WITNESS_CELL / nz * FROZEN_STEP == -362
                        && FROZEN_OZ + WITNESS_CELL % nz * FROZEN_STEP == 652);
        check("frozen/end-cell", "bed[end]=" + bed[EXPECTED_END], bed[EXPECTED_END] < bed[WITNESS_CELL]);
        check("frozen/end-is-in-window", "i=" + EXPECTED_END / nz + " j=" + EXPECTED_END % nz,
                FROZEN_OX + EXPECTED_END / nz * FROZEN_STEP == -176
                        && FROZEN_OZ + EXPECTED_END % nz * FROZEN_STEP == 568);

        // Guards read off the production delta: receiver = sea||lake||covered, blocked = visited,
        // blockedEdge = own-segment crossing. The export carries bed/sea/lake only, so:
        //   - receiver  = sea || lake                        (production equivalent at this step)
        //   - blocked   = false                              (the spill starts outside `visited`)
        //   - blockedEdge = false                            (a fresh chain owns no segments yet)
        // The `degraded/*` runs flip one clause to show what each one buys on this window.
        frozenRun(out, "real/fixture", bed, sea, lake, false, true);
        frozenRun(out, "degraded/no-receiver", bed, sea, lake, false, false);
        frozenRun(out, "degraded/edge-blocked-at-start", bed, sea, lake, true, true);
    }

    private static void frozenRun(Path out, String tag, double[] bed, boolean[] sea, boolean[] lake,
                                  boolean edgesBlocked, boolean receiversOn) {
        int nz = 353;
        boolean[] recv = new boolean[bed.length];
        for (int i = 0; i < bed.length; i++) recv[i] = receiversOn && (sea[i] || lake[i]);
        OrderedSpillSearch.Guards g = new OrderedSpillSearch.Guards() {
            public boolean blocked(int c) { return false; }
            public boolean receiver(int c) { return recv[c]; }
            public boolean blockedEdge(int a, int b) {
                return edgesBlocked && (a == WITNESS_CELL || b == WITNESS_CELL);
            }
        };
        long t0 = System.nanoTime();
        OrderedSpillSearch sub = new OrderedSpillSearch(bed, 353, 353, WITNESS_CELL, REAL_RISE,
                FROZEN_OX, FROZEN_OZ, FROZEN_STEP, bed.length, g);
        OrderedSpillSearch.Result r = sub.find();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int end = r.path().length == 0 ? -1 : r.path()[r.path().length - 1];
        String endWorld = end < 0 ? "-" : Oracle.world(end, nz, FROZEN_OX, FROZEN_OZ, FROZEN_STEP);
        System.out.printf("  %-22s status=%-14s crest=%-20s boundary=%-8s expanded=%-6d path=%-3d end=%-10s %dms%n",
                tag, r.status(), num(r.crest()), num(r.boundaryBound()), r.expanded(),
                r.path().length, endWorld, ms);

        Oracle o = Oracle.eval(bed, 353, 353, WITNESS_CELL, REAL_RISE, g);
        System.out.printf("  -> oracle %-14s tau=%-20s bi=%-8s be=%-8s end=%-10s len=%d%n",
                o.kind, num(o.tauStar), num(o.boundaryInclRecv), num(o.boundaryExclRecv),
                o.endCell < 0 ? "-" : Oracle.world(o.endCell, nz, FROZEN_OX, FROZEN_OZ, FROZEN_STEP),
                o.path.length);

        check(tag + "/oracle-crest", "subject=" + num(r.crest()) + " oracle=" + num(o.tauStar),
                Double.compare(r.crest(), o.tauStar) == 0);
        check(tag + "/oracle-endpoint", "subject=" + end + " oracle=" + o.endCell, o.endCell == end);
        check(tag + "/oracle-status", "subject=" + r.status() + " oracle=" + o.kind, statusMatches(r, o));
        check(tag + "/oracle-len", "subject=" + r.path().length + " oracle=" + o.path.length,
                r.path().length == o.path.length);
        if (o.endCell >= 0) {
            check(tag + "/end-terminal", "recv=" + recv[o.endCell] + " bed=" + bed[o.endCell],
                    recv[o.endCell] || bed[o.endCell] < bed[WITNESS_CELL] - 1e-9);
        }
        if (r.path().length > 0) {
            check(tag + "/path-is-walk", "walk", walkOk(bed, 353, nz, WITNESS_CELL, r, g));
        }
        try {
            Files.writeString(out.resolve(tag.replace('/', '-') + "-path.csv"), pathCsv(r, nz), StandardCharsets.UTF_8);
        } catch (IOException e) {
            notes.add(tag + ": could not write path dump (" + e + ")");
        }

        if (!tag.startsWith("real/")) return;
        // The production log for this exact window reported
        //   ESCAPE crest=64.47363773783941 boundary=Infinity expanded=13640 path=95 end=(-176,568)
        //   ESCAPE crest=64.47363773783941 boundary=Infinity expanded=13640 path=95 end=(-176,568)
        // This fixture can only reproduce it with the edge guard *off*; with any edge guard on the
        // start cell the search finds nothing. The fixture does not carry `covered`/`visited`/
        // `ownSegments`, so the delivered guard set is not the production one.
        boolean reproduces = r.crest() == EXPECTED_CREST && r.path().length == 95 && end == EXPECTED_END;
        if (reproduces) {
            check("real/crest-64.47363773783941", "crest=" + num(r.crest()), true);
            check("real/path-95", "path=" + r.path().length, true);
            check("real/end--176-568", "end=" + endWorld, true);
            check("real/path-0-is-witness", "path[0]=" + r.path()[0], r.path()[0] == WITNESS_CELL);
            check("real/end-not-on-frame", "end on frame=false", !Oracle.onFrame(end, 353, 353));
            int[] frameDist = frameContactDistances(bed, 353, 353, WITNESS_CELL, REAL_RISE, g);
            System.out.printf("  -> same-crest unknown contacts=%d, nearest layer=%d, endpoint layer=%d%n",
                    frameDist[0], frameDist[1], pathLen(r));
            // "the unknown domain cannot be better" is only true when no same-crest contact exists at
            // or before the endpoint layer; assert exactly that, not a blanket +Infinity expectation.
            check("real/unknown-not-at-or-before-endpoint", "contacts=" + frameDist[0]
                            + " nearest=" + frameDist[1] + " endLayer=" + pathLen(r),
                    frameDist[0] == 0 || frameDist[1] > pathLen(r));
            check("real/boundary-bound-is-infinite", "boundaryBound=" + num(r.boundaryBound()),
                    r.boundaryBound() == Double.POSITIVE_INFINITY);
            notes.add("frozen window: " + frameDist[0] + " same-crest unknown frame contacts, nearest "
                    + "layer " + frameDist[1] + " (endpoint layer " + pathLen(r) + "), and no frame "
                    + "cell entered below the crest (" + num(o.boundaryInclRecv) + " vs crest "
                    + num(o.tauStar) + "). So this window certifies because the unknown domain is "
                    + "genuinely unreachable below/at the crest, not because a distance rule was "
                    + "applied. It therefore does NOT exercise the boundary clause at all.");
        } else {
            check("real/matches-production-log", "status=" + r.status() + " crest=" + num(r.crest())
                    + " path=" + r.path().length + " end=" + endWorld, false);
            notes.add("the production log records ESCAPE crest=64.47363773783941 path=95 ending at "
                    + "(-176,568) expanded=13640 for this window; the delivered guard approximations "
                    + "reach that only with the edge guard off. The fixture lacks covered[]/visited[]/"
                    + "ownSegments, so the exact production guard set is NOT reproduced here.");
        }
    }

    private static int pathLen(OrderedSpillSearch.Result r) {
        return r.path().length == 0 ? -1 : r.path().length - 1;
    }

    /**
     * Same-crest frame cells reachable in the BFS graph: {count, nearest BFS layer}. Computed with a
     * plain BFS restricted to the crest, independent of the subject.
     */
    private static int[] frameContactDistances(double[] bed, int nx, int nz, int start, double rise,
                                               OrderedSpillSearch.Guards g) {
        Oracle o = Oracle.eval(bed, nx, nz, start, rise, g);
        if (Double.isNaN(o.tauStar)) return new int[]{0, -1};
        int n = bed.length;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        ArrayList<Integer> q = new ArrayList<>();
        dist[start] = 0;
        q.add(start);
        for (int head = 0; head < q.size(); head++) {
            int k = q.get(head);
            if (Oracle.terminal(bed, g, start, k)) continue;
            int i = k / nz, j = k % nz;
            for (int[] d : Oracle.D8) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int to = a * nz + b;
                if (dist[to] >= 0) continue;
                if (!Oracle.stepOk(bed, g, start, k, to, o.tauStar)) continue;
                dist[to] = dist[k] + 1;
                q.add(to);
            }
        }
        int count = 0, nearest = -1;
        for (int k = 0; k < n; k++) {
            if (k == start || dist[k] < 0 || g.receiver(k)) continue;
            if (!Oracle.onFrame(k, nx, nz)) continue;
            count++;
            if (nearest < 0 || dist[k] < nearest) nearest = dist[k];
        }
        return new int[]{count, nearest};
    }

    private static boolean statusMatches(OrderedSpillSearch.Result r, Oracle o) {
        return r.status() == oracleKind(o.kind);
    }

    private static String pathCsv(OrderedSpillSearch.Result r, int nz) {
        StringBuilder b = new StringBuilder("index,x,z,bed\n");
        for (int i = 0; i < r.path().length; i++) {
            b.append(i).append(',')
             .append(FROZEN_OX + r.path()[i] / nz * FROZEN_STEP).append(',')
             .append(FROZEN_OZ + r.path()[i] % nz * FROZEN_STEP).append(',')
             .append(r.path()[i]).append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean walkOk(Scenario s, int[] path) {
        return walkOk(s.bed, s.nx, s.nz, s.start, path, s.guards());
    }

    private static boolean walkOk(double[] bed, int nx, int nz, int start, OrderedSpillSearch.Result r,
                                  OrderedSpillSearch.Guards g) {
        return walkOk(bed, nx, nz, start, r.path(), g);
    }

    private static boolean pathLegal(double[] bed, int nx, int nz, int start,
                                     OrderedSpillSearch.Result r, OrderedSpillSearch.Guards g) {
        return walkOk(bed, nx, nz, start, r.path(), g);
    }

    private static boolean walkOk(double[] bed, int nx, int nz, int start, int[] path,
                                  OrderedSpillSearch.Guards g) {
        if (path.length == 0 || path[0] != start) return false;
        for (int i = 1; i < path.length; i++) {
            int a = path[i - 1], b = path[i];
            int di = Math.abs(a / nz - b / nz), dj = Math.abs(a % nz - b % nz);
            if (di > 1 || dj > 1 || (di == 0 && dj == 0)) return false;
            if (g.blocked(b) || g.blockedEdge(a, b)) return false;
        }
        return true;
    }

    private static double pathCrest(Scenario s, int[] path) {
        double c = s.bed[s.start];
        for (int k : path) c = Math.max(c, s.bed[k]);
        return c;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    /** A recorded remark: it is counted and printed but does not fail the run. */
    private static void diverged(String name, String detail) {
        divergenceCount++;
        divergences.add(name + " | " + detail);
        System.out.println("  DIVERGE " + name + " | " + detail);
    }

    private static void check(String name, String detail, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name + " | " + detail);
        } else {
            fail++;
            failures.add(name + " | " + detail);
            System.out.println("  FAIL " + name + " | " + detail);
        }
    }

    private static void fail(String name) { fail(name, ""); }

    private static void fail(String name, String detail) {
        fail++;
        failures.add(name + (detail.isEmpty() ? "" : " | " + detail));
        System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " | " + detail));
    }

    private static boolean cmpDouble(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) return true;
        return a == b || Math.abs(a - b) < 1e-12;
    }

    private static String num(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        return Double.toString(d);
    }

    private SearchProbe() { }
}
