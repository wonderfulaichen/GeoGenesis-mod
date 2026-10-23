package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 谁先在窗口边框那一行占格 ⇒ 后续链被"横向接住"。
 *
 * <p>打印每条 reach 在 |z-targetZ|<=band 上的 x 覆盖与索引区间，
 * 判断该行是被一条链从西到东扫过，还是多条链各占一段。</p>
 */
public final class FrameRowOccupantProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int targetZ = args.length > 3 ? Integer.parseInt(args[3]) : 608;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int[][] reaches = provider.reachNodesWorldForProbe(bx, bz);
        System.out.println("reaches=" + reaches.length + " targetZ=" + targetZ);

        int total = 0;
        for (int p = 0; p < reaches.length; p++) {
            int[] pts = reaches[p];
            int m = pts.length / 2;
            int cnt = 0, first = -1, last = -1;
            int xmin = Integer.MAX_VALUE, xmax = Integer.MIN_VALUE;
            for (int i = 0; i < m; i++) {
                if (pts[i * 2 + 1] == targetZ) {
                    cnt++;
                    if (first < 0) first = i;
                    last = i;
                    xmin = Math.min(xmin, pts[i * 2]);
                    xmax = Math.max(xmax, pts[i * 2]);
                }
            }
            if (cnt > 0) {
                total += cnt;
                System.out.printf("  reach#%-3d 该行节点=%-4d 索引[%d..%d]  x[%d..%d] 跨%d块%n",
                        p, cnt, first, last, xmin, xmax, xmax - xmin);
            }
        }
        System.out.println("该行格节点合计=" + total);
    }
}
