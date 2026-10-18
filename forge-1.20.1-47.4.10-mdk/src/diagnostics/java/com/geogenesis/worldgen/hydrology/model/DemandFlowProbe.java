package com.geogenesis.worldgen.hydrology.model;

import java.util.*;
import java.util.concurrent.*;

/** Independent forward mass-balance oracle and negative controls for demand certificates. */
public final class DemandFlowProbe {
    private static int failures, checks;
    private static void check(boolean ok, String text) {
        checks++;if (!ok)failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + text);
    }
    private record Fixture(List<DemandFlow.Node<Integer>> graph, double[] exact) { }
    private static Fixture tree(long seed) {
        var random = new Random(seed);int n = 73;
        var inputs = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++)inputs.add(new ArrayList<>());
        for (int i = 0; i < n - 1; i++)inputs.get(i + 1 + random.nextInt(n - i - 1)).add(i);
        var graph = new ArrayList<DemandFlow.Node<Integer>>();double[] value = new double[n];
        for (int i = 0; i < n; i++) {
            double source = random.nextInt(7), loss = random.nextInt(9);
            double total = source;
            for (int child : inputs.get(i))total += value[child];
            value[i] = Math.max(0, total - loss);
            graph.add(new DemandFlow.Node<>(source, loss, 1, inputs.get(i)));
        }
        return new Fixture(graph, value);
    }
    private static void forwardOracle() {
        boolean valid = true;int compared = 0;
        for (long seed = 0; seed < 32; seed++) {
            var f = tree(seed);var solver = new DemandFlow<Integer>(f.graph()::get, 512);
            for (int i = 0; i < f.graph().size(); i++)for (double d : new double[]{0, 1, 5, 19, Double.POSITIVE_INFINITY}) {
                var a = solver.query(i, d).amount();double q = f.exact()[i];compared++;
                valid &= a.known() && a.bound() <= q && a.reaches(d) == (q >= d);
                valid &= !a.exact() || a.exactValue() == q;
                valid &= a.exact() || Double.isNaN(a.exactValue());
            }
        }
        check(valid, "independent forward oracle: " + compared + " demands over 32 lossy basin trees");
    }
    private static void longCatchment() {
        var solver = new DemandFlow<Integer>(i -> new DemandFlow.Node<>(1, 0, 1,
                i < 40000 ? List.of(i + 1) : List.of()), 32768);
        var a = solver.query(0, 150);
        check(a.amount().kind() == DemandFlow.Kind.AT_LEAST && a.amount().reaches(150)
                && a.cells() >= 150 && a.cells() <= 256,
                "40001-cell catchment proves threshold within 256 cells using geometric checkpoints");
        check(Double.isNaN(a.amount().exactValue()), "saturated lower bound is not a fabricated exact discharge");
        check(!solver.query(0, Double.POSITIVE_INFINITY).amount().known(), "exact 40001-cell request stays UNKNOWN at budget");
        check(solver.query(0, 150).equals(a), "failed large demand cannot poison later small demand");
        var starved = new DemandFlow<Integer>(i -> new DemandFlow.Node<>(1, 0, 1, List.of(i + 1)), 12);
        check(!starved.query(0, 150).amount().known(), "budget exhaustion is UNKNOWN, never zero runoff");
        check(starved.query(0, 12).amount().reaches(12), "last workset slot can still certify its own rain");
        var weighted = new DemandFlow<Integer>(i -> new DemandFlow.Node<>(1, 0, i == 3 ? 4 : 1,
                List.of(i + 1)), 6);
        check(weighted.query(0, 3).amount().reaches(3), "weighted cell limit evaluates accumulated lower bound before UNKNOWN");
    }
    private static void lossAndIdentity() {
        var graph = List.of(new DemandFlow.Node<Integer>(1, 0, 1, List.of()),
                new DemandFlow.Node<Integer>(0, 10, 1, List.of(0)),
                new DemandFlow.Node<Integer>(20, 0, 1, List.of(1)));
        var solver = new DemandFlow<Integer>(graph::get, 64);
        check(solver.query(1, 1).amount().exactValue() == 0, "dry upper pan cannot borrow downstream rain");
        check(solver.query(2, 21).amount().exactValue() == 20, "upstream evaporation applied before downstream injection");
        var wet = List.of(new DemandFlow.Node<Integer>(30, 0, 1, List.of()),
                new DemandFlow.Node<Integer>(0, 10, 1, List.of(0)),
                new DemandFlow.Node<Integer>(0, 7, 1, List.of(1)));
        check(new DemandFlow<Integer>(wet::get, 64).query(2, 14).amount().exactValue() == 13,
                "each basin subtracts its own loss exactly once; skipping either loss turns this red");
        var cycle = new DemandFlow<Integer>(i -> new DemandFlow.Node<>(0, 0, 1, List.of(1 - i)), 64);
        check(!cycle.query(0, 1).amount().known(), "cycle rejected instead of fabricated flow");
        boolean refused = false;
        try { new DemandFlow.Node<Integer>(1, 0, 1, List.of(2, 2)); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "duplicate input identity rejected before double rain credit");
        var diamond = List.of(new DemandFlow.Node<Integer>(0, 0, 1, List.of(1, 2)),
                new DemandFlow.Node<Integer>(0, 0, 1, List.of(3)),
                new DemandFlow.Node<Integer>(0, 0, 1, List.of(3)),
                new DemandFlow.Node<Integer>(20, 0, 1, List.of()));
        check(!new DemandFlow<Integer>(diamond::get, 64).query(0, 21).amount().known(),
                "shared predecessor violates unique successor, never added through both branches");
    }
    private static void orderAndCancel() throws Exception {
        var f = tree(43);var solver = new DemandFlow<Integer>(f.graph()::get, 512);
        var ref = solver.query(72, 5);solver.query(72, 1000);
        check(ref.equals(solver.query(72, 5)), "large then small demand preserves canonical result");
        var pool = Executors.newFixedThreadPool(3);
        try {
            var jobs = new ArrayList<Future<DemandFlow.Result>>();
            for (int i = 0; i < 3; i++)jobs.add(pool.submit(() -> new DemandFlow<Integer>(f.graph()::get, 512).query(72, 5)));
            for (var job : jobs)check(ref.equals(job.get()), "fresh concurrent demand result matches reference");
        } finally { pool.shutdownNow(); }
        boolean cancelled = false;Thread.currentThread().interrupt();
        try { solver.query(72, 5); } catch (CancellationException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        check(cancelled && ref.equals(solver.query(72, 5)), "cancel surfaces and retry is identical");
    }
    public static void main(String[] args) throws Exception {
        forwardOracle();longCatchment();lossAndIdentity();orderAndCancel();
        System.out.println("CHECKS=" + checks + " FAILURES=" + failures);System.exit(failures == 0 ? 0 : 1);
    }
}
