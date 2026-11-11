package com.geogenesis.worldgen.hydrology.model;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <h1>C4 门禁：河宽后继拓扑在单次 {@code buildDetailed} 内只派生一次</h1>
 *
 * <p>{@link RiverWidthProfile} 的 taper / propagate / join / reconcile / 两次 constrain 原先各建一份
 * {@code succ + cycle + postOrder}。本探针固定三件事：</p>
 * <ol>
 *   <li><b>逐位等价</b>：每个场景把 widths / limits / interfaces 的 IEEE-754 raw bits 打进指纹行
 *       （{@code ROW} 与 {@code CASE ... sha256}）。baseline 与候选两个 JVM 的<b>非</b> {@code PERF}
 *       行必须逐字节相同；</li>
 *   <li><b>异常同类型、同消息、同阶段</b>：{@code EXC} 行打印 type / message / 归一化调用链
 *       （滤掉新增的 {@code $Topology} 复用管道帧）。首次派生必须仍在原阶段：
 *       traces 非空 ⇒ {@code taperSources}，traces 为空 ⇒ {@code propagateDownstream}；</li>
 *   <li><b>分配实测</b>：{@code PERF} 行给 {@code ThreadMXBean} 线程分配字节/次与热循环 ns/次，
 *       不预设提速。</li>
 * </ol>
 *
 * <pre>{@code
 *   java -cp "<candidate>;<baseline main>;<diagnostics cp>" \
 *        com.geogenesis.worldgen.hydrology.model.RiverWidthReuseProbe
 * }</pre>
 *
 * <p>纯合成输入，不跑生产图。渲染、全图收益与实机 WGP 仍须另行验收。FAIL 时 {@code System.exit(1)}。</p>
 */
public final class RiverWidthReuseProbe {

    private RiverWidthReuseProbe() { }

    private static final int N = 64;
    private static final int STEP = 2;
    private static final DeterministicSkeleton.End SEA = DeterministicSkeleton.End.MOUTH_SEA;
    private static final DeterministicSkeleton.End JOIN = DeterministicSkeleton.End.JOIN;
    private static final DeterministicSkeleton.RiverLink CONNECTED = DeterministicSkeleton.RiverLink.CONNECTED;
    private static final DeterministicSkeleton.RiverLink TERMINAL = DeterministicSkeleton.RiverLink.TERMINAL;

    private static int cases;
    private static int exceptions;
    private static final List<String> FAILS = new ArrayList<>();
    private static volatile double sink;

    public static void main(String[] args) {
        System.out.println("ENV widthSmooth=" + ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH
                + " widthMonotone=" + ModelHydrologyProvider.SegmentIndex.WIDTH_MONOTONE);
        fixtureCases();
        exceptionCases();
        apiCompatibility();
        perf();
        System.out.printf("SUMMARY cases=%d exceptions=%d fails=%d%n", cases, exceptions, FAILS.size());
        for (String f : FAILS) System.out.println("  FAIL: " + f);
        if (!FAILS.isEmpty()) {
            System.out.println("PROBE FAIL");
            System.exit(1);
        }
        System.out.println("PROBE PASS (synthetic input only; non-PERF lines must match baseline JVM byte-for-byte)");
    }

    // ① 逐位指纹场景

    private static void fixtureCases() {
        World w = new World(N, STEP);
        var trunk = vert(40, 10, 110, STEP);
        var trib = horz(100, 6, 40, STEP);
        var trib2 = horz(60, 6, 40, STEP);
        var a = vert(40, 10, 50, STEP);
        var b = vert(40, 50, 90, STEP);
        var c = vert(40, 90, 130, STEP);
        var single = new DeterministicSkeleton.Shaped(new double[]{40}, new double[]{10});
        var empty = new DeterministicSkeleton.Shaped(new double[0], new double[0]);
        double[] q = ramp(w, 20, 5, 55, 3.0);
        put(w, q, 3, 12, 50, 40.0);
        put(w, q, 3, 12, 30, 20.0);

        emit(new Case("null-polys", null, null, null, null, null, null, Set.of(), null));
        emit(new Case("empty-polys", List.of(), null, null, null, null, null, Set.of(), null));
        emit(new Case("one-node", List.of(single), null, List.of(trace(single, w, SEA)), null,
                null, null, Set.of(), null));
        emit(new Case("all-null", List.of(trunk), null, null, null, null, null, Set.of(), null));

        var chain = List.of(a, b, c);
        emit(new Case("no-traces", chain, w.pipe(q), null,
                List.of(unit(new int[]{0, 1, 2}, chain, w, -1, -1, TERMINAL)), null, null, Set.of(), null));
        emit(new Case("empty-traces", List.of(a, b), w.pipe(q), List.of(),
                List.of(unit(new int[]{0, 1}, List.of(a, b), w, -1, -1, TERMINAL)), null, null, Set.of(), null));

        var chainTraces = List.of(trace(a, w, JOIN), trace(b, w, JOIN), trace(c, w, SEA));
        var chainRivers = DeterministicSkeleton.buildRivers(chainTraces, w.n());
        emit(new Case("serial-chain", chain, w.pipe(q), chainTraces, chainRivers, null, null, Set.of(), null));

        var joinTraces = List.of(trace(trunk, w, SEA), trace(trib, w, JOIN));
        emit(new Case("mid-join", List.of(trunk, trib), w.pipe(q), joinTraces,
                DeterministicSkeleton.buildRivers(joinTraces, w.n()), null, null, Set.of(), null));

        var twoTraces = List.of(trace(trunk, w, SEA), trace(trib, w, JOIN), trace(trib2, w, JOIN));
        emit(new Case("two-tributaries", List.of(trunk, trib, trib2), w.pipe(q), twoTraces,
                DeterministicSkeleton.buildRivers(twoTraces, w.n()), null, null, Set.of(), null));

        emit(new Case("cycle", List.of(a, b), w.pipe(q), List.of(trace(a, w, SEA), trace(b, w, SEA)),
                List.of(unit(new int[]{0}, List.of(a, b), w, 1, 0, CONNECTED),
                        unit(new int[]{1}, List.of(a, b), w, 0, 0, CONNECTED)), null, null, Set.of(), null));

        emit(new Case("duplicate-edge", List.of(a, b), w.pipe(q), List.of(trace(a, w, SEA), trace(b, w, SEA)),
                List.of(unit(new int[]{0}, List.of(a, b), w, 1, 0, CONNECTED),
                        unit(new int[]{0}, List.of(a, b), w, 1, 1, CONNECTED)), null, null, Set.of(), null));

        emit(new Case("empty-trace-cell", List.of(empty, b), w.pipe(q),
                List.of(emptyTrace(), trace(b, w, SEA)), null, null, null, Set.of(), null));
        emit(new Case("degenerate-shape", List.of(single, b), w.pipe(q),
                List.of(trace(single, w, SEA), trace(b, w, SEA)), null, null, null, Set.of(), null));

        Map<Integer, Double> boundary = new LinkedHashMap<>();
        boundary.put(w.cell(20, 35), 3.0);
        emit(new Case("boundary-pins", chain, w.pipe(q), chainTraces, chainRivers, boundary, null, Set.of(), null));

        Map<Integer, Double> inheritedHalf = new LinkedHashMap<>();
        inheritedHalf.put(w.cell(20, 15), 4.0);
        inheritedHalf.put(w.cell(20, 35), 2.0);
        Map<Integer, Double> inheritedLimits = new LinkedHashMap<>();
        inheritedLimits.put(w.cell(20, 30), 5.5);
        emit(new Case("inherited", chain, w.pipe(q), chainTraces, chainRivers, null, inheritedHalf,
                Set.of(chainTraces.get(0).sourceCell()), inheritedLimits));

        double[] q2 = new double[w.n() * w.n()];
        for (int gz = 5; gz <= 25; gz++) q2[w.cell(20, gz)] = 220.0;
        for (int gz = 26; gz <= 65; gz++) q2[w.cell(20, gz)] = 3.0;
        emit(new Case("wide-serial-into-narrow", chain, w.pipe(q2), chainTraces, chainRivers,
                null, null, Set.of(), null));
    }

    private record Case(String name, List<DeterministicSkeleton.Shaped> polys,
                        HydrologyPipeline.Result pipe, List<DeterministicSkeleton.Trace> traces,
                        List<DeterministicSkeleton.River> rivers, Map<Integer, Double> boundary,
                        Map<Integer, Double> inheritedHalf, Set<Integer> heads,
                        Map<Integer, Double> inheritedLimits) { }

    private static void emit(Case c) {
        cases++;
        try {
            fingerprint(c.name(), run(c));
        } catch (RuntimeException e) {
            fail("case " + c.name() + " threw " + e);
            System.out.println("CASE " + c.name() + " threw=" + e);
        }
    }

    /** inheritedHalf / heads 在生产调用里恒非 null（公开 build 传 Map.of()/Set.of()）⇒ 夹具同口径。 */
    private static RiverWidthProfile.Result run(Case c) {
        Map<Integer, Double> inherited = c.inheritedHalf() == null ? Map.of() : c.inheritedHalf();
        Set<Integer> heads = c.heads() == null ? Set.of() : c.heads();
        return RiverWidthProfile.buildDetailed(c.polys(), c.pipe(), 0, 0, N, STEP,
                c.traces(), c.rivers(), c.boundary(), inherited, heads,
                c.inheritedLimits(), Map.of());
    }

    private static void fingerprint(String name, RiverWidthProfile.Result r) {
        MessageDigest md = sha();
        List<double[]> widths = r.widths(), limits = r.limits();
        System.out.printf("CASE %s rows=%d interfaces=%d%n", name, widths.size(), r.interfaces().size());
        for (int i = 0; i < widths.size(); i++) {
            double[] w = widths.get(i), lim = limits.get(i);
            System.out.printf("ROW %d n=%d w=%016x lim=%016x%n", i, w.length, fnv(w), fnv(lim));
            checkRange(name, w, lim);
            raw(md, w);
            raw(md, lim);
        }
        for (var it : r.interfaces()) {
            String line = String.format("IFACE kind=%s poly=%d cell=%d basin=%d status=%s half=%016x tr=%016x samples=%d",
                    it.kind(), it.poly(), it.cell(), it.basin(), it.status(),
                    Double.doubleToRawLongBits(it.maxHalf()),
                    Double.doubleToRawLongBits(it.transverse()), it.samples());
            System.out.println(line);
            md.update(line.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("CASE " + name + " sha256=" + hex(md.digest()));
    }

    private static void checkRange(String name, double[] w, double[] lim) {
        for (double v : w) {
            if (!(v >= RiverCenterlines.MIN_HALF_BLOCKS && v <= 6.0)) {
                fail(name + ": width out of [0.5,6] = " + v);
                return;
            }
        }
        for (double v : lim) {
            if (!(v >= RiverCenterlines.MIN_HALF_BLOCKS && v <= 6.0)) {
                fail(name + ": limit out of [0.5,6] = " + v);
                return;
            }
        }
    }

    // ② 异常：类型 / 消息 / 阶段

    private static void exceptionCases() {
        World w = new World(N, STEP);
        var a = vert(40, 10, 50, STEP);
        var b = vert(40, 50, 90, STEP);
        var c = vert(40, 90, 130, STEP);
        var chain = List.of(a, b, c);
        double[] q = ramp(w, 20, 5, 55, 3.0);
        var traces = List.of(trace(a, w, JOIN), trace(b, w, JOIN), trace(c, w, SEA));

        var dual = List.of(unit(new int[]{0}, chain, w, 1, 0, CONNECTED),
                unit(new int[]{1}, chain, w, 2, 0, CONNECTED),
                unit(new int[]{2}, chain, w, -1, -1, TERMINAL),
                unit(new int[]{0}, chain, w, 2, 0, CONNECTED));
        String dualMsg = "trace 0 has two different receivers: 1 and 2";
        exceptionCase("dual-receiver", new Case("dual-receiver", chain, w.pipe(q), traces, dual,
                null, null, Set.of(), null), dualMsg, "taperSources>chainEdges>link");
        exceptionCase("no-trace-dual-receiver", new Case("no-trace-dual-receiver", chain, w.pipe(q),
                null, dual, null, null, Set.of(), null), dualMsg, "propagateDownstream>chainEdges>link");

        var oob = List.of(unit(new int[]{1}, chain, w, 0, 9999, CONNECTED));
        String oobMsg = "downstreamNode=9999 outside receiver traceOf.length=" + a.size()
                + " (traceIdx=[0])";
        exceptionCase("node-oob", new Case("node-oob", chain, w.pipe(q), traces,
                List.of(unit(new int[]{0}, chain, w, -1, -1, TERMINAL), oob.get(0)),
                null, null, Set.of(), null), oobMsg, "taperSources>chainEdges>receiverTrace");
        exceptionCase("no-trace-node-oob", new Case("no-trace-node-oob", chain, w.pipe(q), null,
                List.of(unit(new int[]{0}, chain, w, -1, -1, TERMINAL), oob.get(0)),
                null, null, Set.of(), null), oobMsg, "propagateDownstream>chainEdges>receiverTrace");

        var noTraceOf = List.of(new DeterministicSkeleton.River(new int[]{0}, cellsOf(a, w), null,
                JOIN, false, w.n(), 0, 0, CONNECTED));
        exceptionCase("missing-receiver", new Case("missing-receiver", chain, w.pipe(q), traces,
                noTraceOf, null, null, Set.of(), null),
                "CONNECTED receiver river is missing traceOf/traceIdx", "taperSources>chainEdges>receiverTrace");
        exceptionCase("no-trace-missing-receiver", new Case("no-trace-missing-receiver", chain, w.pipe(q),
                null, noTraceOf, null, null, Set.of(), null),
                "CONNECTED receiver river is missing traceOf/traceIdx", "propagateDownstream>chainEdges>receiverTrace");

        var badDown = List.of(new DeterministicSkeleton.River(new int[]{0}, cellsOf(a, w),
                owner(a, 0), JOIN, false, w.n(), 99, 0, CONNECTED));
        String badDownMsg = "CONNECTED river with downstreamRiver=99 outside rivers.size()=1";
        exceptionCase("downstream-oob", new Case("downstream-oob", chain, w.pipe(q), traces,
                badDown, null, null, Set.of(), null), badDownMsg, "taperSources>chainEdges");
        exceptionCase("no-trace-downstream-oob", new Case("no-trace-downstream-oob", chain, w.pipe(q),
                null, badDown, null, null, Set.of(), null),
                badDownMsg, "propagateDownstream>chainEdges");
    }

    private static void exceptionCase(String name, Case c, String msg, String stage) {
        exceptions++;
        try {
            RiverWidthProfile.Result r = run(c);
            fail(name + ": expected IllegalStateException, returned " + r.widths().size() + " rows");
            System.out.println("EXC " + name + " missing");
        } catch (RuntimeException e) {
            String type = e.getClass().getName();
            String actual = String.valueOf(e.getMessage());
            String chain = stage(e);
            System.out.printf("EXC %s type=%s msg=%s stage=%s%n", name, type, actual, chain);
            if (!"java.lang.IllegalStateException".equals(type)) fail(name + ": type " + type);
            if (!msg.equals(actual)) fail(name + ": msg " + actual);
            if (!("buildDetailed>" + stage).equals(chain)) fail(name + ": stage " + chain);
        }
    }

    /** 归一化调用链：保留 RiverWidthProfile 自身的帧，滤掉新增的 {@code $Topology} 复用管道。 */
    private static String stage(Throwable t) {
        List<String> names = new ArrayList<>();
        for (StackTraceElement e : t.getStackTrace()) {
            String cn = e.getClassName();
            if (!cn.startsWith(RiverWidthProfile.class.getName())) continue;
            if (cn.contains("$Topology")) continue;
            names.add(e.getMethodName());
        }
        java.util.Collections.reverse(names);
        return String.join(">", names);
    }

    // ③ 公开 API 兼容（同 JVM 内自洽）

    private static void apiCompatibility() {
        World w = new World(N, STEP);
        var a = vert(40, 10, 50, STEP);
        var b = vert(40, 50, 90, STEP);
        var traces = List.of(trace(a, w, JOIN), trace(b, w, SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        var polys = List.of(a, b);
        double[] q = ramp(w, 20, 5, 45, 3.0);
        var pipe = w.pipe(q);
        var wide = RiverWidthProfile.buildDetailed(polys, pipe, 0, 0, N, STEP, traces, rivers,
                Map.of(), Map.of(), Set.of(), Map.of(), Map.of());
        List<double[]> viaPublic = RiverWidthProfile.build(polys, pipe, 0, 0, N, STEP, traces, rivers, Map.of());
        check("public build 行数一致", viaPublic.size() == wide.widths().size());
        for (int i = 0; i < viaPublic.size() && i < wide.widths().size(); i++) {
            check("public build row " + i + " 逐位一致", same(viaPublic.get(i), wide.widths().get(i)));
        }
        var eleven = RiverWidthProfile.build(polys, pipe, 0, 0, N, STEP, traces, rivers, Map.of(),
                Map.of(), Set.of());
        check("11 参 build 行数一致", eleven.size() == wide.widths().size());
        for (int i = 0; i < eleven.size() && i < wide.widths().size(); i++) {
            check("11 参 build row " + i + " 逐位一致", same(eleven.get(i), wide.widths().get(i)));
        }
    }

    // ④ 分配 / 热循环

    private static void perf() {
        System.out.println("PERF java=" + System.getProperty("java.version"));
        World w = new World(512, STEP);
        runBench("real", bench(w, 60, 4, 20), 100, 7, 12);
        runBench("graph", bench(w, 500, 3, 1), 150, 7, 6);
    }

    private static void runBench(String label, Bench bench, int iters, int reps, int warmup) {
        for (int i = 0; i < warmup; i++) sink = execute(bench, iters);
        long[] bytes = new long[reps], ns = new long[reps];
        int got = 0;
        for (int r = 0; r < reps; r++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long a0 = allocatedBytes();
            long t0 = System.nanoTime();
            sink = execute(bench, iters);
            long t1 = System.nanoTime();
            long a1 = allocatedBytes();
            if (a0 < 0 || a1 < 0) break;
            bytes[got] = (a1 - a0) / iters;
            ns[got] = (t1 - t0) / iters;
            got++;
        }
        long[] b = java.util.Arrays.copyOf(bytes, got), t = java.util.Arrays.copyOf(ns, got);
        StringBuilder series = new StringBuilder();
        for (long v : t) series.append(v).append('/');
        java.util.Arrays.sort(b);
        java.util.Arrays.sort(t);
        System.out.printf("PERF %s traces=%d nodesPerTrace=%d iters=%d reps=%d%n",
                label, bench.traceCount(), bench.nodesPerTrace(), iters, got);
        System.out.println("PERF allocBytesPerCall min=" + (got == 0 ? -1 : b[0])
                + " median=" + (got == 0 ? -1 : b[got / 2]));
        System.out.println("PERF nsPerCall min=" + (got == 0 ? -1 : t[0])
                + " median=" + (got == 0 ? -1 : t[got / 2])
                + " max=" + (got == 0 ? -1 : t[got - 1]));
        System.out.println("PERF nsSeriesPerCall=" + series);
    }

    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean sun)) return -1;
        return sun.getCurrentThreadAllocatedBytes();
    }

    private static double execute(Bench b, int iters) {
        double acc = 0;
        for (int i = 0; i < iters; i++) {
            var r = RiverWidthProfile.buildDetailed(b.polys(), b.pipe(), 0, 0, b.n(), STEP,
                    b.traces(), b.rivers(), Map.of(), Map.of(), Set.of(), Map.of(), Map.of());
            acc += r.widths().get(0)[0];
        }
        return acc;
    }

    private record Bench(List<DeterministicSkeleton.Shaped> polys, HydrologyPipeline.Result pipe,
                         List<DeterministicSkeleton.Trace> traces,
                         List<DeterministicSkeleton.River> rivers, int n,
                         int traceCount, int nodesPerTrace) { }

    private static Bench bench(World w, int chains, int perChain, int cellsPerTrace) {
        List<DeterministicSkeleton.Shaped> polys = new ArrayList<>();
        List<DeterministicSkeleton.Trace> traces = new ArrayList<>();
        List<DeterministicSkeleton.River> rivers = new ArrayList<>();
        double[] q = new double[w.n() * w.n()];
        for (int ch = 0; ch < chains; ch++) {
            int gx = 2 + (ch % 60) * 8, gz0 = 10 + (ch / 60) * 60;
            int[] idx = new int[perChain];
            for (int k = 0; k < perChain; k++) {
                int z0 = gz0 + k * cellsPerTrace, z1 = z0 + cellsPerTrace;
                var sh = vert(gx * w.step(), z0 * w.step(), z1 * w.step(), w.step());
                for (int gz = z0; gz <= z1; gz++) q[w.cell(gx, gz)] = 2.0 + 0.05 * gz;
                idx[k] = polys.size();
                polys.add(sh);
                traces.add(trace(sh, w, JOIN));
            }
            rivers.add(unit(idx, polys, w, -1, -1, TERMINAL));
        }
        for (int ch = 0; ch + 1 < chains; ch += 3) {
            int last = (ch + 1) * perChain - 1;
            rivers.add(unit(new int[]{last}, polys, w, ch + 1, 0, CONNECTED));
        }
        return new Bench(polys, w.pipe(q), traces, rivers, w.n(), polys.size(), cellsPerTrace + 1);
    }

    // 几何 / 拓扑夹具

    private record World(int n, int step) {
        int cell(int gx, int gz) { return gx * n + gz; }

        HydrologyPipeline.Result pipe(double[] q) {
            int cells = n * n;
            return new HydrologyPipeline.Result(n, n, step,
                    new double[cells], new boolean[cells], new boolean[cells],
                    new double[cells], new double[cells], new boolean[cells],
                    new int[cells], q, new boolean[cells], new double[cells],
                    null, null, new double[cells], new double[cells], 0, true, 0.0);
        }
    }

    private static DeterministicSkeleton.Shaped vert(double x, double z0, double z1, int step) {
        int m = (int) Math.round((z1 - z0) / step) + 1;
        double[] xs = new double[m], zs = new double[m];
        for (int i = 0; i < m; i++) { xs[i] = x; zs[i] = z0 + i * step; }
        return new DeterministicSkeleton.Shaped(xs, zs);
    }

    private static DeterministicSkeleton.Shaped horz(double z, double x0, double x1, int step) {
        int sign = x1 >= x0 ? 1 : -1;
        int m = (int) Math.round(Math.abs(x1 - x0) / step) + 1;
        double[] xs = new double[m], zs = new double[m];
        for (int i = 0; i < m; i++) { xs[i] = x0 + sign * i * step; zs[i] = z; }
        return new DeterministicSkeleton.Shaped(xs, zs);
    }

    private static int[] cellsOf(DeterministicSkeleton.Shaped sh, World w) {
        int[] c = new int[sh.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = w.cell((int) Math.round(sh.x[i] / w.step()), (int) Math.round(sh.z[i] / w.step()));
        }
        return c;
    }

    private static int[] owner(DeterministicSkeleton.Shaped sh, int trace) {
        int[] o = new int[sh.size()];
        java.util.Arrays.fill(o, trace);
        return o;
    }

    private static DeterministicSkeleton.Trace trace(DeterministicSkeleton.Shaped sh, World w,
                                                     DeterministicSkeleton.End end) {
        return new DeterministicSkeleton.Trace(cellsOf(sh, w), end, false);
    }

    private static DeterministicSkeleton.Trace emptyTrace() {
        return new DeterministicSkeleton.Trace(new int[0], SEA, false);
    }

    private static DeterministicSkeleton.River unit(int[] traceIdx, List<DeterministicSkeleton.Shaped> polys,
                                                    World w, int down, int node,
                                                    DeterministicSkeleton.RiverLink link) {
        int total = 0;
        for (int t : traceIdx) total += polys.get(t).size();
        int[] cells = new int[total], traceOf = new int[total];
        int k = 0;
        for (int t : traceIdx) {
            int[] c = cellsOf(polys.get(t), w);
            for (int i = 0; i < c.length; i++) { cells[k] = c[i]; traceOf[k] = t; k++; }
        }
        return new DeterministicSkeleton.River(traceIdx, cells, traceOf, JOIN, false, w.n(), down, node, link);
    }

    private static double[] ramp(World w, int gx, int gz0, int gz1, double base) {
        double[] q = new double[w.n() * w.n()];
        for (int gz = gz0; gz <= gz1; gz++) q[w.cell(gx, gz)] = base + gz;
        return q;
    }

    private static void put(World w, double[] q, int gx0, int gx1, int gz, double v) {
        for (int gx = gx0; gx <= gx1; gx++) {
            int k = w.cell(gx, gz);
            if (q[k] < v) q[k] = v;
        }
    }

    // 指纹原语

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void raw(MessageDigest md, double[] row) {
        byte[] b = new byte[8];
        putLong(b, row.length);
        md.update(b);
        for (double v : row) { putLong(b, Double.doubleToRawLongBits(v)); md.update(b); }
    }

    private static void putLong(byte[] b, long v) {
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>>= 8; }
    }

    private static long fnv(double[] row) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, row.length);
        for (double v : row) h = mix(h, Double.doubleToRawLongBits(v));
        return h;
    }

    private static long mix(long h, long v) {
        for (int s = 56; s >= 0; s -= 8) {
            h ^= (v >>> s) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static boolean same(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i])) return false;
        }
        return true;
    }

    private static void check(String what, boolean ok) {
        if (!ok) fail(what);
    }

    private static void fail(String what) { FAILS.add(what); }
}
