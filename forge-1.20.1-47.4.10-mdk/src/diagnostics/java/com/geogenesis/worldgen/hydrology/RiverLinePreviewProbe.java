package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河线网络 ASCII 大范围形态预览（贴谷/蜿蜒/密度目检）。
 *
 * <p>图例：~ 无河 | : 细河 | + 中河 | # 宽河 | &gt; 海洋出口段</p>
 *
 * <p>运行：gradlew.bat runRiverLinePreviewProbe --args="12345 1024 16"</p>
 */
public final class RiverLinePreviewProbe {
    private RiverLinePreviewProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        int rows = 0, riverCells = 0, wideCells = 0, oceanCells = 0;
        for (int z = -radius; z <= radius; z += step) {
            StringBuilder row = new StringBuilder();
            for (int x = -radius; x <= radius; x += step) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, 1.0);
                char c = symbol(s);
                if (s != null) {
                    riverCells++;
                    if (c == '#') wideCells++;
                    if (c == '>') oceanCells++;
                }
                row.append(c);
            }
            System.out.println(row);
            rows++;
        }
        System.out.println("=== RiverLinePreviewProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("radius=" + radius + " step=" + step);
        System.out.println("rows=" + rows);
        System.out.println("riverCells=" + riverCells
                + " wideCells=" + wideCells + " oceanOutletCells=" + oceanCells);
        System.out.println("cachedRegions=" + engine.cachedRegions());
        System.out.println("legend=~=none, :=narrow, +=medium, #=wide, >=ocean-outlet");
        System.out.println("status=PREVIEW_ONLY");
    }

    private static char symbol(HydrologyBlockSample s) {
        if (s == null) return '~';
        if (s.distToCenter() > s.width() * 2.0) return '~';   // 影响带外不显示
        if (s.outletType() == RiverOutlet.Type.OCEAN && s.distToCenter() <= s.width()) return '>';
        if (s.width() >= 8.0) return '#';
        if (s.width() >= 4.5) return '+';
        return ':';
    }
}
