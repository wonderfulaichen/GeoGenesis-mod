package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 墙体取证探针（2026-09-09）。用户墙顶 (-167,155,-366)，seed 9139912035078620160。
 * 网格分解显示墙列 (-167,-366)：final=154，light=147，pipe=147，carved=147，
 * 而左邻 (-168,-366) final=146、右邻 (-166,-366) final=137/carved=135。
 * 本探针对墙列与其左右邻逐列 dump：全 samples（dist/w/surf/lip/fall/frozen）、
 * erosionDeltaE、carved、final。判定：
 * (1) carve  discontinuity：相邻列 outer 0→0.8 跳变（nearest 在 fall 窄谷 vs 普通宽谷间切换）；
 * (2) erosion stripe：eDelta 在墙列是否为 +8（tile 输出）还是 ~0（管线 artifact）。
 */
public final class WallForensicsProbe {

    private WallForensicsProbe() { }

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        double hs = 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);

        int cx = Math.floorDiv(-167, 16), cz = Math.floorDiv(-366, 16);
        HydrologyChunkResult result = terrain.calculateHydrologyChunk(cx, cz);
        Map<Long, HydrologyBlockCarvedColumn> colMap = new HashMap<>();
        for (HydrologyBlockCarvedColumn c : result.carvedColumns()) {
            colMap.put((((long) c.blockX()) << 32) | (c.blockZ() & 0xffffffffL), c);
        }

        System.out.println("=== WallForensicsProbe fall-bank trace ===");
        for (int[] c : new int[][]{{-166, -373}, {-166, -372}, {-165, -373}, {-166, -374}, {-167, -374}}) {
            int bx = c[0], bz = c[1];
            Cell fin = terrain.sampleCell(bx, bz);
            Cell light = terrain.sampleCellLight(bx, bz);
            HydrologyBlockCarvedColumn col =
                    colMap.get((((long) bx) << 32) | (bz & 0xffffffffL));
            double eDelta = generator.erosionDeltaE(bx / hs, bz / hs);
            System.out.printf("col=(%d,%d) final=%.1f light=%.1f eDelta=%.2f | carved=%s cut=%s surf=%s mask=%s fill=%s%n",
                    bx, bz, fin.height, light != null ? light.height : -1, eDelta,
                    col != null ? String.format("%.1f", col.carvedGroundY()) : "n/a",
                    col != null ? String.format("%.1f", col.erosion()) : "n/a",
                    col != null ? String.format("%.1f", col.waterSurfaceY()) : "n/a",
                    col != null ? String.format("%.2f", col.erosionMask()) : "n/a",
                    col != null ? col.fillWater() : "n/a");
            List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
            boolean hasLip141 = false;
            for (int i = 0; i < sm.size(); i++) {
                HydrologyBlockSample s = sm.get(i);
                if (s.lipSurfaceY() > 133.0) hasLip141 = true;
                System.out.printf("    [%d] dist=%.2f w=%.2f surf=%.2f lip=%.2f fall=%.2f frozen=%s bank=%.2f lake=%s%n",
                        i, s.distToCenter(), s.width(), s.surfaceY(), s.lipSurfaceY(),
                        s.fallDrop(), s.frozen(), s.bankSurfaceY(), s.isLake());
            }
            System.out.printf("    -> hasUpstreamLip(>133)=%b (samples=%d)%n", hasLip141, sm.size());
        }
    }
}
