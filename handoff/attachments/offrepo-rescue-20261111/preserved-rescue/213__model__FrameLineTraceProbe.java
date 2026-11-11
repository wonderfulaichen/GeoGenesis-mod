package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 拆解 poly#0 那条 294 块横线：它是由哪些【格路径节点】产生的，末端是否跨界。
 *
 * <p>对比 shaped（落块几何）与 reach（格路径）在同一段上的走向，
 * 判断横走来自拓扑本身（格路径就横着）还是来自 shape 收尾（末端延伸/CR 重采样）。</p>
 */
public final class FrameLineTraceProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int polyIdx = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);

        int[] frame = provider.windowFrameForProbe(bx, bz);
        int ox = frame[0], oz = frame[1], step = frame[2], n = frame[3];
        System.out.println("window origin=(" + ox + "," + oz + ") step=" + step + " n=" + n);
        System.out.println("可画范围 x[" + ox + ".." + (ox + (n - 1) * step)
                + "] z[" + oz + ".." + (oz + (n - 1) * step) + "]");

        // 格路径（拓扑层）
        int[][] reaches = provider.reachNodesWorldForProbe(bx, bz);
        System.out.println("reaches=" + reaches.length);
        if (polyIdx < reaches.length) {
            int[] pts = reaches[polyIdx];
            System.out.println("\n--- reach#" + polyIdx + " 格路径（世界坐标，前后各 8 点）---");
            int m = pts.length / 2;
            for (int i = 0; i < Math.min(8, m); i++) {
                System.out.printf("   [%d] (%d,%d)%n", i, pts[i * 2], pts[i * 2 + 1]);
            }
            if (m > 16) System.out.println("   ... 共 " + m + " 点 ...");
            for (int i = Math.max(0, m - 8); i < m; i++) {
                System.out.printf("   [%d] (%d,%d)%n", i, pts[i * 2], pts[i * 2 + 1]);
            }
            // 末端是否在边框
            int lz = pts[(m - 1) * 2 + 1], lx = pts[(m - 1) * 2];
            System.out.println("   末点=(" + lx + "," + lz + ")  在边框: "
                    + (lx == ox || lz == oz || lx == ox + (n - 1) * step || lz == oz + (n - 1) * step));
        }

        // shaped（落块几何）
        double[][] polys = provider.shapedNodesWorldForProbe(bx, bz);
        if (polyIdx < polys.length) {
            double[] s = polys[polyIdx];
            int m = s.length / 2;
            System.out.println("\n--- shaped#" + polyIdx + " 落块几何（前后各 8 点）---");
            System.out.println("   点数=" + m);
            for (int i = 0; i < Math.min(8, m); i++) {
                System.out.printf("   [%d] (%.1f,%.1f)%n", i, s[i * 2], s[i * 2 + 1]);
            }
            System.out.println("   ...");
            for (int i = Math.max(0, m - 10); i < m; i++) {
                System.out.printf("   [%d] (%.1f,%.1f)%n", i, s[i * 2], s[i * 2 + 1]);
            }
        }
    }
}
