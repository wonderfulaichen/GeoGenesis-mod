package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Differential check: the ordinary ArrayList path is the oracle, including int overflow. */
public final class SegmentBucketsCheck {
    private static final long SEED = 0x5345474255434bL;
    private final Method crosses;
    private final Constructor<?> buckets;
    private long checks;
    private long randomQueries;

    private SegmentBucketsCheck() throws ReflectiveOperationException {
        Class<?> skeleton = Class.forName(
                "com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton");
        crosses = skeleton.getDeclaredMethod("segmentCrossesAny",
                int.class, int.class, int.class, int.class, List.class);
        crosses.setAccessible(true);
        buckets = Class.forName(skeleton.getName() + "$SegmentBuckets").getDeclaredConstructor();
        buckets.setAccessible(true);
    }

    public static void main(String[] args) {
        try {
            SegmentBucketsCheck test = new SegmentBucketsCheck();
            test.geometry();
            test.bucketLimits();
            test.coordinateLimits();
            test.incrementalRandom();
            test.overflowRandom();
            System.out.println("SegmentBucketsCheck PASS checks=" + test.checks
                    + " randomQueries=" + test.randomQueries + " seed=" + SEED);
        } catch (Throwable failure) {
            System.err.println("SegmentBucketsCheck FAIL");
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private Pair pair(String name) throws ReflectiveOperationException {
        return new Pair(name, (List<int[]>) buckets.newInstance());
    }

    private final class Pair {
        private final String name;
        private final List<int[]> plain = new ArrayList<>();
        private final List<int[]> indexed;

        private Pair(String name, List<int[]> indexed) {
            this.name = name;
            this.indexed = indexed;
        }

        private void add(int... segment) {
            if (!indexed.add(segment) || !plain.add(segment)) {
                throw new AssertionError(name + ": add returned false");
            }
            if (indexed.size() != plain.size()
                    || indexed.get(plain.size() - 1) != segment) {
                throw new AssertionError(name + ": ArrayList append semantics changed");
            }
        }

        private void query(int... q) throws ReflectiveOperationException {
            boolean expected = invoke(q, plain);
            boolean actual = invoke(q, indexed);
            checks++;
            if (actual != expected) {
                StringBuilder detail = new StringBuilder(name).append(" query=")
                        .append(Arrays.toString(q)).append(" size=").append(plain.size())
                        .append(" expected=").append(expected).append(" actual=").append(actual);
                for (int i = 0; i < Math.min(plain.size(), 12); i++) {
                    detail.append(" segment[").append(i).append("]=")
                            .append(Arrays.toString(plain.get(i)));
                }
                throw new AssertionError(detail.toString());
            }
        }

        private boolean invoke(int[] q, List<int[]> segments) throws ReflectiveOperationException {
            return (Boolean) crosses.invoke(null, q[0], q[1], q[2], q[3], segments);
        }
    }

    private void geometry() throws ReflectiveOperationException {
        int[][] segments = {
                {-17, -9, 17, 9}, {-8, -24, -8, 24}, {7, -17, 8, 17},
                {-32, 8, 32, 8}, {0, 0, 0, 0}, {-9, -9, -1, -1},
                {8191, -8192, 8191, -8100}, {-8192, 8191, -8100, 8191}
        };
        int[][] queries = {
                {-17, 9, 17, -9}, {-17, -9, 17, 9}, {17, 9, 25, 9},
                {-8, -30, -8, 30}, {-8, 24, 4, 24}, {-10, 0, -6, 0},
                {7, 0, 9, 0}, {-40, 8, 40, 8}, {-1, -1, -1, -1},
                {-9, -1, -1, -9}, {200, 201, 204, 205},
                {-8192, 8190, -8100, 8190}, {8190, -8150, 8191, -8150},
                {-8192, -8192, 8191, 8191}, {-8192, 8191, 8191, -8192}
        };
        Pair incremental = pair("geometry incremental");
        for (int[] q : queries) incremental.query(q);
        for (int[] s : segments) {
            // Singleton cases prevent an earlier crossing from masking a missing candidate.
            Pair singleton = pair("geometry singleton " + Arrays.toString(s));
            singleton.add(s);
            incremental.add(s);
            for (int[] q : queries) {
                singleton.query(q);
                incremental.query(q);
            }
        }
        for (int x = -17; x <= 17; x++) {
            Pair boundary = pair("signed bucket boundary " + x);
            boundary.add(x, -9, x, 9);
            boundary.query(x - 1, 0, x + 1, 0);
            boundary.query(x, -9, x, 9);
            boundary.query(x + 8, -1, x + 9, 1);
        }
    }

    private void bucketLimits() throws ReflectiveOperationException {
        int[][] spans = {
                {0, 0, 511, 511},       // Exactly 64 * 64 = 4096 AABB buckets.
                {0, 0, 512, 511},       // 65 * 64 > 4096, all coordinates still safe.
                {-512, -512, -1, -1},
                {-512, -512, 0, -1},
                {-8192, 0, 8191, 0},    // Long, thin: 2048 buckets, must not truncate.
                {0, -8192, 0, 8191},
                {-8192, -8192, 8191, 8191}
        };
        for (int[] s : spans) {
            Pair p = pair("bucket span " + Arrays.toString(s));
            p.add(-100, -101, -100, -99);
            p.query(s); // Oversized query against an otherwise active index.
            p.add(s);   // Oversized insertion, then append again after fallback.
            p.query(250, 260, 260, 250);
            p.query(-260, -250, -250, -260);
            p.query(-1, -1, 1, 1);
            p.add(7000, 7000, 7000, 7010);
            p.query(6999, 7005, 7001, 7005);
            p.query(-101, -100, -99, -100);
            p.query(7500, 7500, 7501, 7501);
        }
        // Also cross 4096 distinct occupied buckets via many small additions.
        Pair cumulative = pair("cumulative bucket limit");
        for (int i = 0; i < 4100; i++) {
            int x = (i % 65) * 8;
            int y = (i / 65) * 8;
            cumulative.add(x + 2, y + 1, x + 2, y + 3);
            if (i >= 4094) cumulative.query(x + 1, y + 2, x + 3, y + 2);
        }
        cumulative.query(1, 2, 3, 2);
        cumulative.query(-20, -20, -10, -10);
    }

    private void coordinateLimits() throws ReflectiveOperationException {
        int[] values = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -100000, -8193,
                -8192, -8191, -1, 0, 1, 8190, 8191, 8192, 100000,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int value : values) {
            for (int coordinate = 0; coordinate < 4; coordinate++) {
                int[] extreme = {-7, -5, 11, 9};
                extreme[coordinate] = value;
                Pair p = pair("coordinate " + coordinate + " value " + value);
                p.add(-8, 6, 12, -6);
                p.query(extreme); // Query-only out-of-range must retain overflow behavior.
                p.query(-10, 0, 10, 0);
                p.add(extreme);   // Insertion out-of-range, including just outside each limit.
                p.query(-10, 0, 10, 0);
                p.query(extreme);
                p.query(Integer.MIN_VALUE, 37, Integer.MAX_VALUE, -41);
                p.add(100, 95, 100, 105);
                p.query(95, 100, 105, 100);
            }
        }
    }

    private void incrementalRandom() throws ReflectiveOperationException {
        Random random = new Random(SEED);
        for (int batch = 0; batch < 100; batch++) {
            Pair p = pair("random active batch " + batch);
            for (int i = 0; i < 200; i++) {
                int[] q = localSegment(random);
                if (i % 2 == 0) {
                    int[] s = localSegment(random);
                    p.query(s);
                    p.add(s);
                    p.query(s[0], s[3], s[2], s[1]);
                }
                p.query(q);
                randomQueries++;
            }
        }
    }

    private static int[] localSegment(Random random) {
        int x = random.nextInt(401) - 200;
        int y = random.nextInt(401) - 200;
        return new int[] {x, y, x + random.nextInt(49) - 24,
                y + random.nextInt(49) - 24};
    }

    private void overflowRandom() throws ReflectiveOperationException {
        Random random = new Random(SEED ^ 0x4f564552464c4f57L);
        for (int i = 0; i < 4000; i++) {
            // Independent pairs avoid an unrelated early match concealing overflow mismatches.
            Pair p = pair("overflow random " + i);
            p.add(localSegment(random));
            p.query(randomSegment(random));
            p.add(randomSegment(random));
            p.query(localSegment(random));
            p.query(randomSegment(random));
            randomQueries += 3;
        }
    }

    private static int[] randomSegment(Random random) {
        return new int[] {random.nextInt(), random.nextInt(),
                random.nextInt(), random.nextInt()};
    }
}
