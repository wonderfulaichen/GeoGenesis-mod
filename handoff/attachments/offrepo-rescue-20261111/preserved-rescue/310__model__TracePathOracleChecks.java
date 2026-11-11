package com.geogenesis.worldgen.hydrology.model;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** 独立逐矩形 slab 裁切 oracle，不复用 DDA 的步进逻辑。 */
final class TracePathOracleChecks {
    static void run() {
        var random = new Random(741238);
        for (int i = 0; i < 1200; i++) {
            var a = p(random.nextDouble() * 20 - 10, random.nextDouble() * 20 - 10);
            var b = p(random.nextDouble() * 20 - 10, random.nextDouble() * 20 - 10);
            Set<TracePathContract.Cell> expected = clipped(a, b);
            var visits = TracePathContract.walk(a, b, 2, 128);
            var actual = new HashSet<TracePathContract.Cell>();
            for (var v : visits) if (v.leave() > v.enter()) actual.add(v.cell());
            MultiphysicsContractProbe.check(actual.equals(expected), "independent slab oracle " + i);
            var reverse = new HashSet<TracePathContract.Cell>();
            for (var v : TracePathContract.walk(b, a, 2, 128)) if (v.leave() > v.enter()) reverse.add(v.cell());
            MultiphysicsContractProbe.check(reverse.equals(actual), "reversed segment cell coverage " + i);
        }
    }

    private static Set<TracePathContract.Cell> clipped(TracePathContract.Point a, TracePathContract.Point b) {
        var result = new HashSet<TracePathContract.Cell>();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            double[] interval = {0, 1};
            if (axis(a.x(), b.x(), x * 2 - 1, x * 2 + 1, interval)
                    && axis(a.z(), b.z(), z * 2 - 1, z * 2 + 1, interval)
                    && interval[1] > interval[0]) result.add(new TracePathContract.Cell(x, z));
        }
        return result;
    }

    private static boolean axis(double a, double b, double low, double high, double[] interval) {
        if (a == b) return a >= low && a < high;
        double t0 = (low - a) / (b - a), t1 = (high - a) / (b - a);
        interval[0] = Math.max(interval[0], Math.min(t0, t1));
        interval[1] = Math.min(interval[1], Math.max(t0, t1));
        return interval[1] >= interval[0];
    }

    private static TracePathContract.Point p(double x, double z) { return new TracePathContract.Point(x, z); }
    private TracePathOracleChecks() { }
}
