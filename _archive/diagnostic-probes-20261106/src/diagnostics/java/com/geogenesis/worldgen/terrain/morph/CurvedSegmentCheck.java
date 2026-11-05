package com.geogenesis.worldgen.terrain.morph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Small standalone check; console only, no maps, production entry points or output files. */
final class CurvedSegmentCheck {
    private record Oracle(double ax, double az, double bx, double bz, int rx, int rz) { }
    private static int assertions;

    public static void main(String[] args) {
        try {
            checkZeroAndOracle();
            checkTiesAndSelf();
            checkGeometry();
            checkResolution();
            checkOrderAndConcurrency();
            reportSupportSeams();
            System.out.println("PASS CurvedSegmentCheck assertions=" + assertions);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("FAIL CurvedSegmentCheck");
            System.exit(1);
        }
    }

    private static MorphNoise.Erosion original(MorphNoise.Node raw, float strength) {
        return new MorphNoise.Erosion(raw, 7349, 3, strength, 17.3f, .53f, 1.87f, .61f);
    }

    private static MorphNoise.Node raw() {
        // Float deliberately differs: every cached height must use eval(input), not compute.
        return new MorphNoise.DualNode((x, z) -> -999f,
                (x, z) -> .5 + .19 * Math.sin(x * .057) + .16 * Math.cos(z * .063)
                        + .08 * Math.sin(x * .021 + z * .047));
    }

    /** Independent literal receiver oracle, including self and the original strict tie order. */
    private static Oracle oracle(MorphNoise.Erosion n, int octave, int cx, int cz) {
        double grid = n.gridSize();
        for (int i = 0; i < octave; i++) grid *= n.distanceFallOff();
        float[] a = MorphCellTable.CELL[MorphNoise.hash2D(n.seed(), cx, cz) & 255];
        double ax = (cx + (double) a[0]) * grid, az = (cz + (double) a[1]) * grid;
        double bx = ax, bz = az, lowest = Double.MAX_VALUE;
        int rx = cx, rz = cz;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx, nz = cz + dz;
                float[] b = (nx == cx && nz == cz) ? a
                        : MorphCellTable.CELL[MorphNoise.hash2D(n.seed(), nx, nz) & 255];
                double px = (nx + (double) b[0]) * grid, pz = (nz + (double) b[1]) * grid;
                double h = MorphNoiseEval.eval(n.input(), px, pz);
                if (h < lowest) {
                    lowest = h;
                    bx = px;
                    bz = pz;
                    rx = nx;
                    rz = nz;
                }
            }
        }
        return new Oracle(ax, az, bx, bz, rx, rz);
    }

    private static void matchOracle(CurvedSegmentErosion arm, int octave, int x, int z) {
        Oracle o = oracle(arm.original(), octave, x, z);
        CurvedSegmentErosion.Edge e = arm.edge(octave, x, z);
        bits(o.ax, e.ax(), "ax");
        bits(o.az, e.az(), "az");
        bits(o.bx, e.bx(), "bx");
        bits(o.bz, e.bz(), "bz");
        require(o.rx == e.receiverX() && o.rz == e.receiverZ(), "receiver cell oracle");
    }

    private static void checkZeroAndOracle() {
        MorphNoise.Node complete = new MorphNoise.Add(MorphNoise.Perlin.ofScale(1234, 91, 3),
                new MorphNoise.Mul(raw(), new MorphNoise.Constant(.37f)));
        for (MorphNoise.Node input : List.of(raw(), complete, new MorphNoise.Constant(.5f))) {
            for (float strength : new float[]{0, .37f, 1}) {
                MorphNoise.Erosion n = original(input, strength);
                CurvedSegmentErosion straight = new CurvedSegmentErosion(n, 0);
                CurvedSegmentErosion curved = new CurvedSegmentErosion(n, .15);
                for (int octave = 0; octave < n.octaves(); octave++) {
                    for (int z = -5; z <= 5; z++) {
                        for (int x = -5; x <= 5; x++) {
                            matchOracle(straight, octave, x, z);
                            matchOracle(curved, octave, x, z);
                        }
                    }
                }
                for (int i = 0; i < 300; i++) {
                    double x = (i * 79 % 431 - 215) * 1.137;
                    double z = (i * 47 % 397 - 198) * .953;
                    bits(MorphNoiseEval.eval(n, x, z), straight.eval(x, z), "bend0 eval");
                }
                for (double x : new double[]{-3e7, -17.3, -0.0, 0, 17.3, 3e7})
                    bits(MorphNoiseEval.eval(n, x, -x), straight.eval(x, -x), "far/boundary eval");
            }
        }
        System.out.println("PASS bend0 bitwise values and independent node/receiver oracle");
    }

    private static void checkTiesAndSelf() {
        CurvedSegmentErosion tied = new CurvedSegmentErosion(original(new MorphNoise.Constant(.5f), 1), .15);
        for (int o = 0; o < 3; o++) {
            for (int z = -4; z <= 4; z++) {
                for (int x = -4; x <= 4; x++) {
                    CurvedSegmentErosion.Edge e = tied.edge(o, x, z);
                    require(e.receiverX() == x - 1 && e.receiverZ() == z - 1, "equal height first tie");
                }
            }
        }
        double grid = 17.3f;
        float[] v = MorphCellTable.CELL[MorphNoise.hash2D(7349, -2, 1) & 255];
        double sx = (-2 + (double) v[0]) * grid, sz = (1 + (double) v[1]) * grid;
        MorphNoise.Node bowl = new MorphNoise.DualNode((x, z) -> -999,
                (x, z) -> (x - sx) * (x - sx) + (z - sz) * (z - sz));
        CurvedSegmentErosion sink = new CurvedSegmentErosion(original(bowl, 1), .15);
        matchOracle(sink, 0, -2, 1);
        CurvedSegmentErosion.Edge e = sink.edge(0, -2, 1);
        require(e.receiverX() == -2 && e.receiverZ() == 1, "self receiver retained");
        for (int i = 0; i <= 32; i++) {
            double[] p = CurvedSegmentErosion.point(e, i / 32.0);
            bits(sx, p[0], "self point x");
            bits(sz, p[1], "self point z");
        }
        bits(25, sink.geometryDistance2(e, sx + 3, sz + 4), "self distance");
        System.out.println("PASS ties including self, forced local minimum and degenerate distance");
    }

    private static void checkGeometry() {
        CurvedSegmentErosion arm = new CurvedSegmentErosion(original(raw(), 1), .15);
        Map<String, Integer> arrivals = new HashMap<>();
        int curved = 0, shared = 0, tangent = 0, sinks = 0, stationary = 0;
        for (int o = 0; o < 3; o++) {
            for (int z = -9; z <= 9; z++) {
                for (int x = -9; x <= 9; x++) {
                    CurvedSegmentErosion.Edge e = arm.edge(o, x, z);
                    double[] start = CurvedSegmentErosion.point(e, 0), end = CurvedSegmentErosion.point(e, 1);
                    bits(e.ax(), start[0], "endpoint Ax");
                    bits(e.az(), start[1], "endpoint Az");
                    bits(e.bx(), end[0], "endpoint Bx");
                    bits(e.bz(), end[1], "endpoint Bz");
                    CurvedSegmentErosion.Edge next = arm.edge(o, e.receiverX(), e.receiverZ());
                    bits(e.bx(), next.ax(), "shared receiver x");
                    bits(e.bz(), next.az(), "shared receiver z");
                    if (e.ax() == e.bx() && e.az() == e.bz()) continue;
                    if (arrivals.merge(o + ":" + e.receiverX() + ":" + e.receiverZ(), 1, Integer::sum) > 1) shared++;
                    double[] mid = CurvedSegmentErosion.point(e, .5);
                    double cross = (mid[0] - e.ax()) * (e.bz() - e.az())
                            - (mid[1] - e.az()) * (e.bx() - e.ax());
                    if (Math.abs(cross) > 1e-9) curved++;
                    checkControlsAndNoLoop(e);
                    double nx = next.bx() - next.ax(), nz = next.bz() - next.az();
                    if (Math.hypot(nx, nz) == 0) { sinks++; continue; }
                    double tx = e.bx() - e.c2x(), tz = e.bz() - e.c2z();
                    if (Math.hypot(tx, tz) < 1e-12) { stationary++; continue; }
                    near(0, tx * nz - tz * nx, 1e-10 * Math.hypot(nx, nz), "downstream tangent");
                    require(tx * nx + tz * nz > 0, "tangent points downstream");
                    tangent++;
                }
            }
        }
        require(curved > 100 && shared > 100 && tangent > 100 && sinks > 0, "geometry coverage");
        require(stationary == 0, "unexpected exact reversal in fixture");
        System.out.printf("PASS geometry curved=%d shared=%d downstream=%d sinkArrivals=%d stationary=%d%n",
                curved, shared, tangent, sinks, stationary);
    }

    private static void checkControlsAndNoLoop(CurvedSegmentErosion.Edge e) {
        double dx = e.bx() - e.ax(), dz = e.bz() - e.az(), length = Math.hypot(dx, dz);
        require(Math.hypot(e.c1x() - e.ax(), e.c1z() - e.az()) <= .367 * length, "C1 bound");
        require(Math.hypot(e.bx() - e.c2x(), e.bz() - e.c2z()) <= .367 * length, "C2 bound");
        // Each fixture curve has a monotonic projection onto its start/end tangent bisector.
        double tx = e.bx() - e.c2x(), tz = e.bz() - e.c2z(), norm = Math.hypot(tx, tz);
        double ux = dx / length + tx / norm, uz = dz / length + tz / norm;
        double last = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 128; i++) {
            double[] p = CurvedSegmentErosion.point(e, i / 128.0);
            double projection = (p[0] - e.ax()) * ux + (p[1] - e.az()) * uz;
            require(projection >= last - 1e-10, "no backtracking/loop on bisector");
            last = projection;
        }
    }

    private static void checkResolution() {
        MorphNoise.Erosion n = original(raw(), 1);
        CurvedSegmentErosion a = new CurvedSegmentErosion(n, .15);
        CurvedSegmentErosion b = new CurvedSegmentErosion(n, .15, 128);
        CurvedSegmentErosion c = new CurvedSegmentErosion(n, .15, 256);
        double max64 = 0, max128 = 0, maxEval64 = 0, maxEval128 = 0;
        for (int i = 0; i < 180; i++) {
            int o = i % 3, x = i * 7 % 19 - 9, z = i * 11 % 19 - 9;
            CurvedSegmentErosion.Edge e = a.edge(o, x, z);
            require(e.equals(b.edge(o, x, z)) && e.equals(c.edge(o, x, z)), "resolution geometry invariant");
            for (int j = 0; j < 7; j++) {
                double[] p = CurvedSegmentErosion.point(e, (j + .37) / 7);
                double px = p[0] + .13 * j * a.grid(o), pz = p[1] - .07 * j * a.grid(o);
                double d64 = Math.sqrt(a.geometryDistance2(e, px, pz));
                double d128 = Math.sqrt(b.geometryDistance2(e, px, pz));
                double d256 = Math.sqrt(c.geometryDistance2(e, px, pz));
                double bound = chordBound(e, 64), fineBound = chordBound(e, 128);
                require(Math.abs(d64 - d256) <= bound + chordBound(e, 256) + 1e-10, "64 chord error bound");
                require(Math.abs(d128 - d256) <= fineBound + chordBound(e, 256) + 1e-10, "128 chord error bound");
                max64 = Math.max(max64, Math.abs(d64 - d256) / a.grid(o));
                max128 = Math.max(max128, Math.abs(d128 - d256) / a.grid(o));
            }
            double px = x * 13.73, pz = z * 19.17;
            maxEval64 = Math.max(maxEval64, Math.abs(a.eval(px, pz) - c.eval(px, pz)));
            maxEval128 = Math.max(maxEval128, Math.abs(b.eval(px, pz) - c.eval(px, pz)));
        }
        require(max64 > 0 && max128 < max64 && maxEval128 < maxEval64, "refinement observed");
        System.out.printf("PASS approximate distances max |d64-d256|/grid=%.9g |d128-d256|/grid=%.9g%n", max64, max128);
        System.out.printf("PASS texture refinement max |v64-v256|=%.9g |v128-v256|=%.9g (256 NOT exact)%n",
                maxEval64, maxEval128);
    }

    /** max |P''| h^2/8 bounds the Bezier-to-polyline Hausdorff distance. */
    private static double chordBound(CurvedSegmentErosion.Edge e, int segments) {
        double a = Math.hypot(e.c2x() - 2 * e.c1x() + e.ax(), e.c2z() - 2 * e.c1z() + e.az());
        double b = Math.hypot(e.bx() - 2 * e.c2x() + e.c1x(), e.bz() - 2 * e.c2z() + e.c1z());
        return 6 * Math.max(a, b) / (8 * segments * segments);
    }

    private static void checkOrderAndConcurrency() throws Exception {
        MorphNoise.Erosion n = original(raw(), 1);
        CurvedSegmentErosion forward = new CurvedSegmentErosion(n, .15);
        CurvedSegmentErosion reverse = new CurvedSegmentErosion(n, .15);
        double[] expected = new double[240];
        for (int i = 0; i < expected.length; i++) expected[i] = forward.eval(qx(i), qz(i));
        for (int i = expected.length - 1; i >= 0; i--)
            bits(expected[i], reverse.eval(qx(i), qz(i)), "reverse order");
        CurvedSegmentErosion concurrent = new CurvedSegmentErosion(n, .15);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 6; thread++) {
                final int offset = thread;
                tasks.add(() -> {
                    for (int j = 0; j < expected.length; j++) {
                        int i = (j * 73 + offset * 31) % expected.length;
                        if (Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(concurrent.eval(qx(i), qz(i))))
                            throw new AssertionError("concurrent value " + i);
                        int o = i % 3, x = i % 17 - 8, z = i % 23 - 11;
                        if (!concurrent.edge(o, x, z).equals(forward.edge(o, x, z)))
                            throw new AssertionError("concurrent edge " + i);
                    }
                    return true;
                });
            }
            for (var future : pool.invokeAll(tasks)) require(future.get(), "concurrent task");
        } finally {
            pool.shutdownNow();
        }
        System.out.println("PASS negative cells, reverse order and six-way cold-cache concurrency");
    }

    private static double qx(int i) { return (i * 97 % 701 - 350) * .837; }
    private static double qz(int i) { return (i * 53 % 653 - 326) * 1.131; }

    private static double support(CurvedSegmentErosion arm, int cx, int cz, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                double d = arm.geometryDistance2(arm.edge(0, cx + dx, cz + dz), x, z);
                if (d < best) best = d;
            }
        }
        return MorphNoiseEval.clamp(Math.sqrt(best) / arm.grid(0), 0, 1);
    }

    private static void reportSupportSeams() {
        MorphNoise.Erosion n = original(raw(), 1);
        CurvedSegmentErosion straight = new CurvedSegmentErosion(n, 0);
        CurvedSegmentErosion curved = new CurvedSegmentErosion(n, .15);
        double maxOld = 0, maxNew = 0, maxChange = 0;
        int changed = 0;
        for (int axis = 0; axis < 2; axis++) {
            for (int cell = -8; cell <= 8; cell++) {
                for (int row = -8; row <= 8; row++) {
                    for (int j = 0; j < 5; j++) {
                        double x = cell * curved.grid(0), z = (row + (j + .31) / 5) * curved.grid(0);
                        int cx = cell, cz = row;
                        if (axis == 1) { double swap = x; x = z; z = swap; cx = row; cz = cell; }
                        int lx = cx - (axis == 0 ? 1 : 0), lz = cz - (axis == 1 ? 1 : 0);
                        double oldJump = support(straight, cx, cz, x, z) - support(straight, lx, lz, x, z);
                        double newJump = support(curved, cx, cz, x, z) - support(curved, lx, lz, x, z);
                        maxOld = Math.max(maxOld, Math.abs(oldJump));
                        maxNew = Math.max(maxNew, Math.abs(newJump));
                        maxChange = Math.max(maxChange, Math.abs(newJump - oldJump));
                        if (Math.abs(newJump - oldJump) > 1e-12) changed++;
                    }
                }
            }
        }
        System.out.printf("REPORT unchanged nine-source support: seam max straight=%.9g curved=%.9g delta=%.9g changed=%d%n",
                maxOld, maxNew, maxChange, changed);
        System.out.println("REPORT sinks retain convergence, no common-tangent guarantee; exact reversal uses zero terminal handle.");
    }

    private static void bits(double expected, double actual, String label) {
        require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
                label + " expected=" + expected + " actual=" + actual);
    }

    private static void near(double expected, double actual, double tolerance, String label) {
        require(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance, label + " actual=" + actual);
    }

    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
