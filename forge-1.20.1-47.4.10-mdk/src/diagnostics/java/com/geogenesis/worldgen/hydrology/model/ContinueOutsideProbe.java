package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 直接从边框格调用 continueOutside 的等价逻辑，确认它为什么救不出来。
 *
 * <p>用与生产相同的 OutsideContinuation（erodedHeight / origin / step / seaLevel）。</p>
 */
public final class ContinueOutsideProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;

        var tp = TerrainParams.defaults();
        double hs = Math.max(0.01, tp.horizontalScale());
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        System.out.printf("window origin=(%d,%d) step=%d n=%d%n", ox, oz, step, n);

        double[] bed = provider.pipeForProbe(bx, bz).bed();

        // 站在 x=-458 那一列的最后一行附近，观察 8 邻（含窗外）谁更低
        for (int wx : new int[]{-458, -442, -386}) {
            int gi = (wx - ox) / step;
            int gj = n - 1;
            int k = gi * n + gj;
            System.out.printf("%n站在 (%d,%d) bed=%.3f  格索引=%d%n", wx, oz + gj * step, bed[k], k);
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                int a = gi + d[0], b = gj + d[1];
                boolean in = a >= 0 && b >= 0 && a < n && b < n;
                double h = gen.erodedHeight((ox + a * step) / hs, (oz + b * step) / hs);
                System.out.printf("   d=(%2d,%2d) inWindow=%-5b  world=(%d,%d) h=%.3f  %s%n",
                        d[0], d[1], in, ox + a * step, oz + b * step, h,
                        h < bed[k] - 1e-6 ? "LOWER" : "");
            }
        }
    }
}
