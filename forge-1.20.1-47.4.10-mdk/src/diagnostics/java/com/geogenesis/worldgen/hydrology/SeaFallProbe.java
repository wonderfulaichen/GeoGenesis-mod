package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入海瀑布探针（2026-08-31）：用户报告"到海平面还在生成瀑布"。
 *
 * <p>统计所有瀑布列（fallDrop&gt;0）按【唇口水位相对海平面】分类：
 * 唇口在水面以下是荒谬的（水下不可能有瀑布），应归零。</p>
 */
public final class SeaFallProbe {

    private SeaFallProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        double seaLevel = terrain.heightCurve().seaLevelY();

        Set<Long> riverChunks = new LinkedHashSet<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    for (int i = 0; i < river.nodes.length; i++) {
                        int bx = (int) Math.floor(river.nodes[i].x() * hs);
                        int bz = (int) Math.floor(river.nodes[i].z() * hs);
                        int ccx = Math.floorDiv(bx, 16), ccz = Math.floorDiv(bz, 16);
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                riverChunks.add((((long) (ccx + dx)) << 32) | ((ccz + dz) & 0xffffffffL));
                            }
                        }
                    }
                }
            }
        }

        int fallCols = 0, belowSea = 0, nearSea = 0;
        double worstLip = Double.POSITIVE_INFINITY;
        int worstBx = 0, worstBz = 0;
        List<String> samples = new ArrayList<>();
        for (long key : riverChunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int bx = cx * 16 + lx, bz = cz * 16 + lz;
                    List<HydrologyBlockSample> sm = engine.sampleBlockAll(bx, bz, hs);
                    if (sm.isEmpty()) continue;
                    HydrologyBlockSample s = sm.get(0);
                    if (s.fallDrop() <= 0.0) continue;
                    fallCols++;
                    double lip = s.lipSurfaceY();
                    if (lip <= seaLevel) {
                        belowSea++;
                        if (lip < worstLip) { worstLip = lip; worstBx = bx; worstBz = bz; }
                        if (samples.size() < 15) {
                            samples.add(String.format("    (%d,%d) lip=%.1f 潭面=%.1f 落差=%.1f",
                                    bx, bz, lip, s.surfaceY(), s.fallDrop()));
                        }
                    } else if (lip <= seaLevel + 4.0) {
                        nearSea++;
                    }
                }
            }
        }
        System.out.println("=== SeaFallProbe ===");
        System.out.printf("seed=%d 海平面=%.1f%n", seed, seaLevel);
        System.out.printf("瀑布列总数=%d%n", fallCols);
        System.out.printf("唇口在【海平面及以下】的瀑布列=%d  ← 水下瀑布，应为 0%n", belowSea);
        System.out.printf("唇口在海平面以上 4 格内的瀑布列=%d  ← 入海口临界，需人工看%n", nearSea);
        if (belowSea > 0) {
            System.out.printf("最低唇口=%.1f @ (%d,%d)（比海平面低 %.1f 格）%n",
                    worstLip, worstBx, worstBz, seaLevel - worstLip);
            System.out.println("样例：");
            for (String line : samples) System.out.println(line);
        }
        System.out.println(belowSea == 0 ? "status=PASS" : "status=FAIL");
    }
}
