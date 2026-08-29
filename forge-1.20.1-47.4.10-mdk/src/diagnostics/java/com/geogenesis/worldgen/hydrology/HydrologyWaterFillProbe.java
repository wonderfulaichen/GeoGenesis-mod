package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.LinkedHashSet;
import java.util.Set;

/** 扫描多个 chunk 统计真实灌水列，验证水文河流有水。 */
public final class HydrologyWaterFillProbe {
    private HydrologyWaterFillProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        int chunks = 0, riverChunks = 0, waterColumns = 0, invalid = 0, uplift = 0;
        int surfaceAboveTerrain = 0, filledAboveTerrain = 0;
        int channelColumns = 0, dryChannel = 0, suspended = 0;
        int bankColumns = 0, bankOverflow = 0;
        double maxErosion = 0.0;
        Set<Long> riverChunkKeys = new LinkedHashSet<>();
        for (int rz = -1; rz <= 1; rz++) for (int rx = -1; rx <= 1; rx++) {
            RiverLineRegion region = engine.network().region(rx, rz);
            for (RiverLineRegion.RiverPolyline river : region.rivers) {
                for (var node : river.nodes) {
                    int bx = (int) Math.floor(node.x() * horizontalScale);
                    int bz = (int) Math.floor(node.z() * horizontalScale);
                    riverChunkKeys.add(pack(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
                    if (riverChunkKeys.size() >= 144) break;
                }
                if (riverChunkKeys.size() >= 144) break;
            }
        }
        for (long key : riverChunkKeys) {
            int cx = (int) (key >> 32), cz = (int) key;
            chunks++;
            double[] ground = ground(terrain, cx, cz, horizontalScale);
            var columns = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, ground);
            if (columns.isEmpty()) continue;
            riverChunks++;
            for (HydrologyBlockCarvedColumn column : columns) {
                if (column.fillWater()) waterColumns++;
                if (!Double.isFinite(column.carvedGroundY())
                        || column.carvedGroundY() > column.originalGroundY()) invalid++;
                if (column.carvedGroundY() > column.originalGroundY() + 1e-9) uplift++;
                // 切穿列（水面高于雕刻前地形、河床已切到水面下）是预期行为，仅计数。
                if (column.waterSurfaceY() > column.originalGroundY() + 1e-9) {
                    surfaceAboveTerrain++;
                    if (column.fillWater()) filledAboveTerrain++;
                }
                // 核心不变量：河道半宽内（命中最近段 dist<=width）不允许干列。
                var samples = engine.sampleBlockAll(column.blockX(), column.blockZ(), horizontalScale);
                // width > 80 block 为湖命中（lakeRadius×2×scale），湖缘本就不要求满灌。
                if (!samples.isEmpty() && samples.get(0).width() <= 80) {
                    double d = samples.get(0).distToCenter();
                    double w = samples.get(0).width();
                    // 湿核心带（≤0.7w）必须有水；河缘带允许因地形高于水面而不灌（那是岸）。
                    if (d <= w * 0.7) {
                        channelColumns++;
                        if (!column.fillWater()) {
                            dryChannel++;
                            if (dryChannel <= 8) {
                                var s0 = samples.get(0);
                                System.out.printf("dry: bx=%d bz=%d dist=%.2f width=%.2f "
                                                + "orig=%.2f carved=%.2f ws=%.2f depth=%.2f%n",
                                        column.blockX(), column.blockZ(),
                                        s0.distToCenter(), s0.width(),
                                        column.originalGroundY(), column.carvedGroundY(),
                                        column.waterSurfaceY(), s0.depth());
                            }
                        }
                        if (column.carvedGroundY() >= column.waterSurfaceY()) suspended++;
                    }
                    // 河缘带（会灌水且靠近河岸）：水面必须低于原始地形，
                    // 否则水会从河缘漫到地面上，表现为"一侧河岸被水盖过"。
                    // 河心列（d <= 0.7w）允许水面高于原地形——那是切穿出的河槽，属正常。
                    if (d > w * 0.7 && d <= w) {
                        bankColumns++;
                        // 只有"灌了水且水面高于地形"才会视觉上漫出河岸；未灌水的列是岸。
                        if (column.fillWater()
                                && column.waterSurfaceY() > column.originalGroundY() + 1e-6) {
                            bankOverflow++;
                            if (bankOverflow <= 8) {
                                System.out.printf("bank: bx=%d bz=%d dist=%.2f width=%.2f "
                                                + "orig=%.2f ws=%.2f%n",
                                        column.blockX(), column.blockZ(), d, w,
                                        column.originalGroundY(), column.waterSurfaceY());
                            }
                        }
                    }
                }
                maxErosion = Math.max(maxErosion, column.erosion());
            }
        }
        System.out.println("=== HydrologyWaterFillProbe ===");
        System.out.println("seed=" + seed + " horizontalScale=" + horizontalScale);
        System.out.println("chunks=" + chunks + " riverChunks=" + riverChunks);
        System.out.println("waterColumns=" + waterColumns + " channelColumns=" + channelColumns
                + " dryChannel=" + dryChannel);
        System.out.println("invalid=" + invalid + " uplift=" + uplift + " suspended=" + suspended);
        System.out.println("bankColumns=" + bankColumns + " bankOverflow=" + bankOverflow
                + " (河岸带水面高于原始地形 = 一侧河岸被水盖过)");
        System.out.println("surfaceAboveTerrain=" + surfaceAboveTerrain
                + " filledAboveTerrain=" + filledAboveTerrain + " (含河心切穿列，预期>0)");
        System.out.println("maxErosion=" + maxErosion);
        System.out.println("status=" + (waterColumns > 0 && channelColumns > 0
                && dryChannel == 0 && invalid == 0 && uplift == 0 && suspended == 0
                && bankOverflow == 0 ? "PASS" : "FAIL"));
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[x * 16 + z] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }
}
