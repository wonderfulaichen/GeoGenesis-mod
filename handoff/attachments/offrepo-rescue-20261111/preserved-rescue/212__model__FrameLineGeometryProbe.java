package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.ArrayList;
import java.util.List;

/**
 * 定位 z≈608 那条 403 块水平线【由哪条折线画出】。
 *
 * <p>只看真实 provider 的 shaped 折线（= 落块实际用的几何），不猜拓扑。
 * 输出每条折线在接缝行上的横向跨度，用于区分：</p>
 * <ul>
 *   <li>单条折线自身横走（几何问题）；</li>
 *   <li>多条折线各自终点落在同一行、被 shape 的共享锚点/末端延伸串起（契约问题）。</li>
 * </ul>
 */
public final class FrameLineGeometryProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int targetZ = args.length > 3 ? Integer.parseInt(args[3]) : 608;
        int band = args.length > 4 ? Integer.parseInt(args[4]) : 3;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int[] frame = provider.windowFrameForProbe(bx, bz);
        System.out.println("owner window origin=(" + frame[0] + "," + frame[1]
                + ") step=" + frame[2] + " n=" + frame[3]);
        double[][] polys = provider.shapedNodesWorldForProbe(bx, bz);
        System.out.println("shaped polys=" + polys.length);

        int inBand = 0;
        List<String> rows = new ArrayList<>();
        int widest = -1;
        double widestSpan = -1;
        for (int p = 0; p < polys.length; p++) {
            double[] pts = polys[p];
            int n = pts.length / 2;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            int hits = 0;
            for (int i = 0; i < n; i++) {
                double z = pts[i * 2 + 1];
                if (Math.abs(z - targetZ) <= band) {
                    hits++;
                    minX = Math.min(minX, pts[i * 2]);
                    maxX = Math.max(maxX, pts[i * 2]);
                }
            }
            if (hits == 0) continue;
            inBand++;
            double span = maxX - minX;
            if (span > widestSpan) {
                widestSpan = span;
                widest = p;
            }
            if (rows.size() < 15) {
                rows.add(String.format("  poly#%d n=%d 命中行点数=%d  x跨度=%.1f  [%.1f..%.1f]",
                        p, n, hits, span, minX, maxX));
            }
        }
        System.out.println("落在 |z-" + targetZ + "|<=" + band + " 的折线数: " + inBand);
        rows.forEach(System.out::println);
        if (widest >= 0) {
            System.out.println("\n最宽者 poly#" + widest + " 跨度=" + widestSpan + " 块");
            double[] pts = polys[widest];
            StringBuilder sb = new StringBuilder("  前若干点: ");
            for (int i = 0; i < Math.min(12, pts.length / 2); i++) {
                sb.append(String.format("(%.1f,%.1f)", pts[i * 2], pts[i * 2 + 1]));
            }
            System.out.println(sb);
        }
        // 汇总：所有折线在该行的 x 覆盖并集（判断是否多条首尾相接成一条视觉长线）
        double allMin = Double.MAX_VALUE, allMax = -Double.MAX_VALUE;
        for (double[] pts : polys) {
            for (int i = 0; i < pts.length / 2; i++) {
                if (Math.abs(pts[i * 2 + 1] - targetZ) <= band) {
                    allMin = Math.min(allMin, pts[i * 2]);
                    allMax = Math.max(allMax, pts[i * 2]);
                }
            }
        }
        System.out.println("该行所有折线 x 并集: [" + allMin + ".." + allMax
                + "]  跨度=" + (allMax - allMin) + " 块");
    }
}
