package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 诊断：跨 chunk 边界雕刻量差异的真实来源（原始地形 vs 雕刻场）。 */
public final class RiverLineCarveDiagProbe {
    private RiverLineCarveDiagProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 找一条河的准确位置
        int rx = 0, rz = 0;
        boolean found = false;
        double seaLevel = terrain.heightCurve().seaLevelY();
        outer:
        for (int z = -512; z <= 512; z += 4) {
            for (int x = -512; x <= 512; x += 4) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, 1.0);
                if (s != null && s.distToCenter() <= s.width() * 0.8
                        && terrain.sampleWu(x, z).height >= seaLevel + 5.0) {
                    rx = x; rz = z; found = true; break outer;
                }
            }
        }
        System.out.println("land river near (" + rx + "," + rz + ")");
        if (!found) { System.out.println("no land river found in scan"); return; }

        // 沿 x 方向横穿河道，逐列打印 dist/surface/width/carved
        int k = Math.floorDiv(rx + 8, 16);
        int edgeW = k * 16 - 1, edgeE = k * 16;
        for (int z = rz - 2; z <= rz + 2; z++) {
            System.out.println("-- row z=" + z + " (chunk edge x=" + edgeW + "|" + edgeE + ") --");
            for (int x = rx - 12; x <= rx + 12; x++) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, 1.0);
                if (s == null) continue;
                double orig = terrain.sampleWu(x, z).height;   // 含侵蚀 delta 的真实地表
                var col = carveAt(engine, terrain, x, z);
                String mark = (x == edgeW || x == edgeE) ? " <EDGE>" : "";
                System.out.println(String.format(
                    "x=%d d=%.1f w=%.1f surf=%.1f orig=%.1f carved=%s%s",
                    x, s.distToCenter(), s.width(), s.surfaceY(), orig,
                    col == null ? "n/a" : String.format("%.1f", col.carvedGroundY()), mark));
            }
        }
    }

    private static HydrologyBlockCarvedColumn carveAt(HydrologyExperimentEngine e,
                                                       CellGenerator t, int x, int z) {
        double[] g = new double[256];
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            g[lz * 16 + lx] = t.sampleWu((cx * 16 + lx), (cz * 16 + lz)).height;
        }
        var cols = HydrologyBlockCarver.carveChunk(e, cx, cz, 1.0, g);
        for (HydrologyBlockCarvedColumn c : cols) {
            if (c.blockX() == x && c.blockZ() == z) return c;
        }
        return null;
    }
}
