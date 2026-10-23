package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 打印 reach 格路径中「z 接近接缝行」的那一段，判断横走是在拓扑里还是 shape 造出来的。
 */
public final class ReachWanderProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int polyIdx = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int targetZ = args.length > 4 ? Integer.parseInt(args[4]) : 608;
        int band = args.length > 5 ? Integer.parseInt(args[5]) : 6;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);

        int[][] reaches = provider.reachNodesWorldForProbe(bx, bz);
        int[] pts = reaches[polyIdx];
        int m = pts.length / 2;
        System.out.println("reach#" + polyIdx + " 节点数=" + m);

        // 找出所有 |z-targetZ|<=band 的节点索引范围
        int first = -1, last = -1, cnt = 0;
        for (int i = 0; i < m; i++) {
            if (Math.abs(pts[i * 2 + 1] - targetZ) <= band) {
                cnt++;
                if (first < 0) first = i;
                last = i;
            }
        }
        System.out.println("格路径中 |z-" + targetZ + "|<=" + band + " 的节点数=" + cnt
                + "  索引范围=" + first + ".." + last);
        if (cnt > 0) {
            int lo = Math.max(0, first - 4), hi = Math.min(m - 1, last + 4);
            System.out.println("--- 该段节点（世界坐标）---");
            for (int i = lo; i <= hi && i < lo + 60; i++) {
                System.out.printf("   [%d] (%d,%d)%n", i, pts[i * 2], pts[i * 2 + 1]);
            }
            if (hi - lo >= 60) System.out.println("   ... (段太长，截断)");
        }

        // 统计该链的 z 极值与横向游程
        int zmin = Integer.MAX_VALUE, zmax = Integer.MIN_VALUE;
        for (int i = 0; i < m; i++) {
            zmin = Math.min(zmin, pts[i * 2 + 1]);
            zmax = Math.max(zmax, pts[i * 2 + 1]);
        }
        System.out.println("格路径 z 范围: " + zmin + ".." + zmax);

        // 最长纯横向连续段（dz==0 且 |dx|>0 连续）
        int runH = 0, bestH = 0, bestAt = -1;
        for (int i = 1; i < m; i++) {
            int dz = pts[i * 2 + 1] - pts[(i - 1) * 2 + 1];
            int dx = pts[i * 2] - pts[(i - 1) * 2];
            if (dz == 0 && dx != 0) {
                runH++;
                if (runH > bestH) { bestH = runH; bestAt = i; }
            } else {
                runH = 0;
            }
        }
        System.out.println("格路径最长纯横向连续步数=" + bestH + "（≈" + (bestH * 2) + " 块）"
                + (bestAt > 0 ? "  位置索引≈" + bestAt : ""));
    }
}
