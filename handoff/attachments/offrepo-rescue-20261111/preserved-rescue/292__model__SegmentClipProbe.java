package com.geogenesis.worldgen.hydrology.model;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 原四面裁剪 oracle 与真实实现逐项对拍；nearest 指纹供冻结/候选 JVM 比较。 */
public final class SegmentClipProbe {
    private static MethodHandle clip;
    private static long checks;
    private static volatile long sink;

    private SegmentClipProbe() { }

    public static void main(String[] args) {
        try {
            var method = ModelHydrologyProvider.SegmentIndex.class.getDeclaredMethod("segmentMeetsBlock",
                    double.class, double.class, double.class, double.class, int.class, int.class);
            method.setAccessible(true);
            clip = MethodHandles.lookup().unreflect(method);
            knownCases();
            boundaryCases();
            randomCases();
            nearestFingerprint();
            System.out.println("CLIP_PASS checks=" + checks);
            if (Arrays.asList(args).contains("--bench")) bench();
        } catch (Throwable e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static boolean actual(double x0, double z0, double x1, double z1, int x, int z) throws Throwable {
        return (boolean) clip.invokeExact(x0, z0, x1, z1, x, z);
    }

    // 冻结实现，保留小数组、面顺序、比较与除法，不调用候选 helper。
    private static boolean oracle(double x0, double z0, double x1, double z1, int blockX, int blockZ) {
        double xmin = blockX - 0.5, xmax = blockX + 0.5;
        double zmin = blockZ - 0.5, zmax = blockZ + 0.5;
        double t0 = 0, t1 = 1;
        double dx = x1 - x0, dz = z1 - z0;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x0 - xmin, xmax - x0, z0 - zmin, zmax - z0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return false;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r; }
                else          { if (r < t0) return false; if (r < t1) t1 = r; }
            }
        }
        return t0 <= t1;
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void compare(double x0, double z0, double x1, double z1, int x, int z) throws Throwable {
        boolean want = oracle(x0, z0, x1, z1, x, z);
        require(actual(x0, z0, x1, z1, x, z) == want,
                "clip mismatch " + Arrays.toString(new double[]{x0, z0, x1, z1}) + " block=" + x + "," + z);
    }

    private static void knownCases() throws Throwable {
        double[][] cases = {
                {-2, 0, 2, 0, 1}, {0, -2, 0, 2, 1}, {-2, -2, 2, 2, 1},
                {-2, .5, 2, .5, 1}, {-2, Math.nextUp(.5), 2, Math.nextUp(.5), 0},
                {-2, -.5, 2, -.5, 1}, {-2, Math.nextDown(-.5), 2, Math.nextDown(-.5), 0},
                {0, 0, 0, 0, 1}, {.5, .5, .5, .5, 1}, {.6, 0, .6, 0, 0},
                {-2, 2, -.5, .5, 1}, {-2, 2, -.5001, .5, 0},
                {-2, 2, 2, 2, 0}, {2, -2, 2, 2, 0}
        };
        for (double[] c : cases) {
            boolean want = c[4] != 0;
            require(actual(c[0], c[1], c[2], c[3], 0, 0) == want, "known clip " + Arrays.toString(c));
            require(oracle(c[0], c[1], c[2], c[3], 0, 0) == want, "known oracle");
        }
    }

    private static void boundaryCases() throws Throwable {
        double[] values = {-2, -.5, Math.nextDown(-.5), Math.nextUp(-.5), -0.0, 0.0,
                .5, Math.nextDown(.5), Math.nextUp(.5), 2, Double.MIN_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double a : values) for (double b : values) for (double c : values) for (double d : values) {
            compare(a, b, c, d, 0, 0);
        }
        for (int x : new int[]{-30000000, -1024, -1, 0, 1, 30000000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            for (int z : new int[]{-30000000, -1, 0, 1, 30000000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                compare(x - 2.0, z - 2.0, x + 2.0, z + 2.0, x, z);
                compare(x + .5, z - 2.0, x + .5, z + 2.0, x, z);
                compare(Math.nextUp(x + .5), z - 2.0, Math.nextUp(x + .5), z + 2.0, x, z);
            }
        }
    }

    private static void randomCases() throws Throwable {
        Random r = new Random(20261028L);
        for (int i = 0; i < 300000; i++) {
            int x = r.nextInt(60000001) - 30000000, z = r.nextInt(60000001) - 30000000;
            double scale = i % 3 == 0 ? 1 : i % 3 == 1 ? 16 : 100000;
            double x0 = x + (r.nextDouble() - .5) * scale, z0 = z + (r.nextDouble() - .5) * scale;
            double x1 = x + (r.nextDouble() - .5) * scale, z1 = z + (r.nextDouble() - .5) * scale;
            if (i % 19 == 0) x1 = x0;
            if (i % 23 == 0) z1 = z0;
            compare(x0, z0, x1, z1, x, z);
        }
    }

    private static void nearestFingerprint() throws Throwable {
        Class<?> type = ModelHydrologyProvider.SegmentIndex.class;
        Constructor<?> ctor = type.getDeclaredConstructor(double[].class, Map.class);
        ctor.setAccessible(true);
        var nearest = type.getDeclaredMethod("nearest", int.class, int.class);
        nearest.setAccessible(true);
        Class<?> hit = nearest.getReturnType();
        MethodHandle query = MethodHandles.lookup().unreflect(nearest).asType(
                MethodType.methodType(Object.class, Object.class, int.class, int.class));
        MethodHandle[] fields = new MethodHandle[4];
        String[] names = {"dist", "half", "depth", "surface"};
        for (int i = 0; i < 4; i++) {
            var m = hit.getDeclaredMethod(names[i]); m.setAccessible(true);
            fields[i] = MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(double.class, Object.class));
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int offset : new int[]{-30000000, -128, -1, 0, 63, 30000000}) {
            double[] segments = {
                    offset - 4, -4, offset + 4, 4, .5, .5, 1, 3,
                    offset + 4, -4, offset - 4, 4, .5, 2, 2, 4,
                    offset - 8, .5, offset + 8, .5, .5, 6, 1, 4,
                    offset, 0, offset, 0, .5, .5, 1, 1
            };
            Map<Long, List<Integer>> tiles = new HashMap<>();
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                tiles.put(key(offset + x, z), List.of(0, 1, 2, 3));
            }
            Object index = ctor.newInstance(segments, tiles);
            Field caps = type.getDeclaredField("widthLimits"); caps.setAccessible(true);
            caps.set(index, new double[]{.5, .5, 1, 1.5, 3, 2, .5, .5});
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                Object value = (Object) query.invokeExact(index, offset + x, z);
                put(sha, value == null ? 0 : 1);
                if (value != null) for (MethodHandle field : fields) {
                    put(sha, Double.doubleToRawLongBits((double) field.invokeExact(value)));
                }
            }
        }
        Object competing = ctor.newInstance(new double[]{-2, 0, 2, 0, .5, .5, 1, 1,
                -2, 0, 2, 0, .5, .5, 1, 1}, Map.of(key(0, 0), List.of(0, 1)));
        Field surfaces = type.getDeclaredField("surface"); surfaces.setAccessible(true);
        double[] planes = (double[]) surfaces.get(competing); planes[0] = 70; planes[1] = 71;
        boolean failed = false;
        try { Object ignored = (Object) query.invokeExact(competing, 0, 0); }
        catch (IllegalStateException e) { failed = e.getMessage().equals("competing corridor planes at 0,0"); }
        require(failed, "conflicting planes must fail at nearest");
        System.out.println("NEAREST_SHA256=" + HexFormat.of().formatHex(sha.digest()));
    }

    private static long key(int x, int z) {
        return ((long) Math.floorDiv(x, 64) << 32) ^ (Math.floorDiv(z, 64) & 0xffffffffL);
    }

    private static void put(MessageDigest sha, long v) {
        for (int i = 7; i >= 0; i--) sha.update((byte) (v >>> (i * 8)));
    }

    private static void bench() throws Throwable {
        var bean = ManagementFactory.getThreadMXBean();
        var alloc = bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported() ? b : null;
        if (alloc != null && !alloc.isThreadAllocatedMemoryEnabled()) alloc.setThreadAllocatedMemoryEnabled(true);
        for (int round = -3; round < 9; round++) {
            long a0 = alloc == null ? 0 : alloc.getCurrentThreadAllocatedBytes();
            long c0 = bean.getCurrentThreadCpuTime(), t0 = System.nanoTime(), count = 0;
            for (int i = 0; i < 2000000; i++) {
                double z = (i & 1023) / 1024.0;
                if (actual(-2, z, 2, z - .3, 0, 0)) count++;
            }
            long wall = System.nanoTime() - t0, cpu = bean.getCurrentThreadCpuTime() - c0;
            long bytes = alloc == null ? -1 : alloc.getCurrentThreadAllocatedBytes() - a0;
            sink = count;
            if (round >= 0) System.out.printf("BENCH round=%d calls=2000000 ns=%d cpuNs=%d bytes=%d covered=%d%n",
                    round, wall, cpu, bytes, count);
        }
    }
}
