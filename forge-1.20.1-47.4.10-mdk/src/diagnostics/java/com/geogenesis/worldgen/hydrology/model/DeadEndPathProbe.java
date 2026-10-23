package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 对每个保留的 DEAD_END，逐步复现 {@code spillCell} 的实际行为，
 * 报告：起点 bed、spillCell 返回值、返回点 bed、抬升、是否超 MAX_SPILL_RISE。
 */
public final class DeadEndPathProbe {

    public static void main(String[] args) {
        System.setProperty("geogenesis.drainageAudit", "true");
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int bx = rx * 512 + 256, bz = rz * 512 + 256;
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        double[] bed = provider.pipeForProbe(bx, bz).bed();
        var audit = provider.drainageForProbe(rx, rz);
        if (audit == null) throw new AssertionError("audit missing");

        System.out.println("=== 保留 DEAD_END 的 spillCell 行为 ===");
        for (var s : audit.segments()) {
            if (s.end() != DeterministicSkeleton.End.DEAD_END) continue;
            int i = (int) Math.round((s.mouth().x() - ox) / step);
            int j = (int) Math.round((s.mouth().z() - oz) / step);
            if (i < 0 || j < 0 || i >= n || j >= n) {
                System.out.printf("  mouth=(%.0f,%.0f) 在窗口外%n", s.mouth().x(), s.mouth().z());
                continue;
            }
            int k = i * n + j;
            boolean onFrame = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            var visited = new java.util.HashSet<Integer>();
            visited.add(k);
            int sp = DeterministicSkeleton.spillCell(k, bed, n, n, visited);
            double rise = sp >= 0 ? bed[sp] - bed[k] : Double.NaN;
            System.out.printf(
                    "  mouth=(%.0f,%.0f) bed=%.3f onFrame=%b  spillCell=%d  spillBed=%.3f  抬升=%.3f  %s%n",
                    s.mouth().x(), s.mouth().z(), bed[k], onFrame, sp,
                    sp >= 0 ? bed[sp] : Double.NaN, rise,
                    sp < 0 ? "→ 无出口" : (rise <= 12.0 ? "→ 应可溢出" : "→ 超12上限"));
        }
    }
}
