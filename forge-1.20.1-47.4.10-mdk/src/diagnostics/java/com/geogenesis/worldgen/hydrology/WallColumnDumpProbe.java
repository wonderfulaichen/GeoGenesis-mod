package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.List;

/**
 * 单列中间量 dump（2026-09-09）。瀑布落差线断面右岸 n=+4 被刻到潭面、n=+8 高起 → 墙。
 * 逐列打印 samples（dist/width/surface/fallDrop/frozen）与 carved/surface/bed，定位 atFall 判定。
 */
public final class WallColumnDumpProbe {
    private WallColumnDumpProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        double hs = 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 落差线 s=0 断面在 (-165,-376)，段方向 ≈ 纯 +x（node3 -174→node4 -165）
        // 法向 ≈ 纯 z：取 n 沿 z 偏移
        int baseX = -165, baseZ = -376;
        for (int n = -16; n <= 16; n += 4) {
            int bx = baseX, bz = baseZ + n;   // 法向（+n 朝 +z）
            List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
            System.out.printf("col=(%d,%d) hits=%d%n", bx, bz, sm.size());
            for (int i = 0; i < sm.size(); i++) {
                HydrologyBlockSample s = sm.get(i);
                System.out.printf("  [%d] dist=%.2f w=%.2f surf=%.2f lip=%.2f fall=%.2f frozen=%s lake=%s%n",
                        i, s.distToCenter(), s.width(), s.surfaceY(), s.lipSurfaceY(),
                        s.fallDrop(), s.frozen(), s.isLake());
            }
        }
    }
}
