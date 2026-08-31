package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 横断面探针（2026-08-31）：给定种子 + 起点方块坐标 + 方向(x/z) + 长度，
 * 逐列打印 original / carved / water / fill 与相邻台阶 —— 直接判断
 * "用户看到的坎"是原地形台地阶，还是雕刻诱发的（两者要分开治）。
 */
public final class SeamCrossProbe {

    private SeamCrossProbe() { }

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int bx0 = Integer.parseInt(args[1]);
        int bz0 = Integer.parseInt(args[2]);
        String axis = args.length > 3 ? args[3] : "x";      // "x" 沿 bx 走 / "z" 沿 bz 走
        int len = args.length > 4 ? Integer.parseInt(args[4]) : 60;
        double hs = 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        int xFrom = "x".equals(axis) ? bx0 - len : bx0;
        int xTo = "x".equals(axis) ? bx0 + len : bx0;
        int zFrom = "z".equals(axis) ? bz0 : bz0 - len;
        int zTo = "z".equals(axis) ? bz0 : bz0 + len;

        Map<Long, HydrologyBlockCarvedColumn> cols = new HashMap<>();
        int c0x = Math.floorDiv(xFrom, 16), c1x = Math.floorDiv(xTo, 16);
        int c0z = Math.floorDiv(zFrom, 16), c1z = Math.floorDiv(zTo, 16);
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                double[] ground = new double[256];
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        ground[x * 16 + z] = terrain.sample((cx * 16 + x) / hs, (cz * 16 + z) / hs).height;
                    }
                }
                for (HydrologyBlockCarvedColumn c
                        : HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground)) {
                    cols.put((((long) c.blockX()) << 32) | (c.blockZ() & 0xffffffffL), c);
                }
            }
        }

        System.out.println("=== SeamCrossProbe ===");
        System.out.println("seed=" + seed + " 起点=(" + bx0 + "," + bz0 + ") 轴向=" + axis + " 长度=±" + len);
        System.out.println("列 | orig  carved   cut  water fill | origStep carvStep | 注");
        java.util.List<HydrologyBlockSample> prevSm = null;
        double prevOrig = Double.NaN, prevCarv = Double.NaN;
        for (int i = -len; i <= len; i++) {
            int bx = "x".equals(axis) ? bx0 + i : bx0;
            int bz = "z".equals(axis) ? bz0 + i : bz0;
            HydrologyBlockCarvedColumn c = cols.get((((long) bx) << 32) | (bz & 0xffffffffL));
            List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
            if (c == null) {
                System.out.printf("%4d | %s%n", i, sm.isEmpty() ? "（无河线命中，未被雕刻）" : "—");
                prevOrig = Double.NaN; prevCarv = Double.NaN; prevSm = null;
                continue;
            }
            double os = Double.isNaN(prevOrig) ? 0 : Math.abs(c.originalGroundY() - prevOrig);
            double cs = Double.isNaN(prevCarv) ? 0 : Math.abs(c.carvedGroundY() - prevCarv);
            String note = "";
            if (cs >= 4.0) note = "★大坎";
            else if (cs >= 2.0) note = "·坎";
            if (os >= 4.0) note += " [原地形本身就有>4格阶]";
            System.out.printf("%4d | %5.1f %6.1f %5.1f %6.1f %s | %8.1f %8.1f | %s%n",
                    i, c.originalGroundY(), c.carvedGroundY(),
                    c.originalGroundY() - c.carvedGroundY(), c.waterSurfaceY(),
                    c.fillWater() ? "Y" : "-", os, cs, note);
            prevOrig = c.originalGroundY(); prevCarv = c.carvedGroundY(); prevSm = sm;
        }
    }
}
