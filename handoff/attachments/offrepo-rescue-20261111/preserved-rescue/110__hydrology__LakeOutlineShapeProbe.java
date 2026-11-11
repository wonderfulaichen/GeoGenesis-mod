package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 单个湖的轮廓形状（2026-10）—— 判定"是圆形洼地湖"还是"沿谷延伸的长条"。
 * 后者说明河谷被当成湖（本该雕刻成河道，却被填水）。
 *
 * <pre>{@code gradlew runLakeOutlineShapeProbe [-PprobeArgs="seed regionX regionZ [idx]"]}</pre>
 */
public final class LakeOutlineShapeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int idx = args.length > 3 ? Integer.parseInt(args[3]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();
        double hs = tp.horizontalScale();

        RiverLineRegion r = net.region(rx, rz);
        System.out.printf("=== LakeOutlineShapeProbe seed=%d r(%d,%d) 湖数=%d ===%n",
                seed, rx, rz, r.lakes.size());
        if (idx >= r.lakes.size()) { System.out.println("idx 越界"); return; }
        RiverLineRegion.LakeNode ln = r.lakes.get(idx);
        int cells = ln.cellX == null ? 0 : ln.cellX.length;
        System.out.printf("湖[%d] 湖心=(%.0f,%.0f)块 spill=%.2f 轮廓格=%d 水深=%.2f%n",
                idx, ln.x * hs, ln.z * hs, ln.height, cells, ln.depth);

        double ox = ln.x * hs, oz = ln.z * hs;
        int span = 16;
        char[][] g = new char[2 * span + 1][2 * span + 1];
        for (char[] row : g) java.util.Arrays.fill(row, '.');
        int plotted = 0;
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = 0; i < cells; i++) {
            int gx = (int) Math.round((ln.cellX[i] * hs - ox) / 48.0) + span;
            int gz = (int) Math.round((ln.cellZ[i] * hs - oz) / 48.0) + span;
            minX = Math.min(minX, ln.cellX[i] * hs); maxX = Math.max(maxX, ln.cellX[i] * hs);
            minZ = Math.min(minZ, ln.cellZ[i] * hs); maxZ = Math.max(maxZ, ln.cellZ[i] * hs);
            if (gx >= 0 && gx < g.length && gz >= 0 && gz < g[0].length) {
                g[gx][gz] = '#'; plotted++;
            }
        }
        System.out.printf("轮廓 bbox = %.0f x %.0f 块（= %.1f x %.1f 格，格距48块）%n",
                maxX - minX, maxZ - minZ, (maxX - minX) / 48.0, (maxZ - minZ) / 48.0);
        System.out.printf("长宽比 = %.2f  （约1 => 圆洼地；远大于1 => 沿谷长条）%n",
                (maxX - minX) / Math.max(1.0, maxZ - minZ));
        System.out.println("轮廓形状（每字符 = 48 块，+ = 湖心）：");
        for (int i = 0; i < g.length; i++) {
            StringBuilder sb = new StringBuilder("  ");
            for (int j = 0; j < g[0].length; j++) {
                sb.append(i == span && j == span ? '+' : g[i][j]);
            }
            System.out.println(sb);
        }
        System.out.printf("%n绘出 %d / %d 格%n", plotted, cells);
    }

    private LakeOutlineShapeProbe() { }
}
