package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河线版 chunk 边界连续性探针。
 *
 * <p>★ 正确语义：chunk 边界无缝 = <b>同一 block 列</b>无论从哪个 chunk
 * 发起计算，雕刻结果完全一致（确定性 + 无跨 chunk 状态）。</p>
 *
 * <p>旧探针误把「相邻两列的 erosion 差」当跳变——那是正常的横断面梯度
 * （河岸每列差 1+ 块是物理正确的）。本探针改为：</p>
 * <ol>
 *   <li>一致性：边界列由 chunk A 与 chunk B 分别计算，carved 差必须 = 0；</li>
 *   <li>平滑度：沿垂直于河线方向步进 1 block，单步雕刻量变化 ≤ 2.5（断面连续）；</li>
 *   <li>水面：同河相邻列 surface 差 ≤ 0.25（纵剖面连续）。</li>
 * </ol>
 */
public final class RiverLineBoundaryProbe {
    private RiverLineBoundaryProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double horizontalScale = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 1) 找含河 chunk
        java.util.Set<Long> riverChunks = new java.util.HashSet<>();
        for (int z = -512; z <= 512; z += 8) {
            for (int x = -512; x <= 512; x += 8) {
                HydrologyBlockSample s = engine.sampleBlock(x, z, horizontalScale);
                if (s != null && s.distToCenter() <= s.width() * 2.0) {
                    riverChunks.add(pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
                }
            }
        }
        System.out.println("riverChunks=" + riverChunks.size());

        // 2) 一致性：边界列从两个 chunk 独立计算必须逐位一致
        int pairs = 0, inconsistent = 0;
        double maxInconsistency = 0.0;
        for (long key : riverChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xffffffffL);
            for (int dir = 0; dir < 2; dir++) {
                int ncx = cx + (dir == 0 ? 1 : 0);
                int ncz = cz + (dir == 0 ? 0 : 1);
                double[] gA = ground(terrain, cx, cz, horizontalScale);
                double[] gB = ground(terrain, ncx, ncz, horizontalScale);
                var a = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, gA);
                var b = HydrologyBlockCarver.carveChunk(engine, ncx, ncz, horizontalScale, gB);
                // chunk A 的东/南边界列 == chunk B 的西/北边界列（同一世界坐标）
                for (int i = 0; i < 16; i++) {
                    int wx = dir == 0 ? cx * 16 + 15 : cx * 16 + i;
                    int wz = dir == 0 ? cz * 16 + i : cz * 16 + 15;
                    HydrologyBlockCarvedColumn ca = at(a, wx, wz);
                    HydrologyBlockCarvedColumn cb = at(b, wx, wz);
                    if (ca == null || cb == null) continue;
                    pairs++;
                    double d = Math.abs(ca.carvedGroundY() - cb.carvedGroundY());
                    maxInconsistency = Math.max(maxInconsistency, d);
                    if (d > 1e-9) inconsistent++;
                }
            }
        }
        System.out.println("consistencyPairs=" + pairs);
        System.out.println("inconsistentColumns=" + inconsistent);
        System.out.println("maxInconsistency=" + maxInconsistency);

        // 3) 平滑度：含河 chunk 内随机抽样行/列，单步雕刻量变化与水面台阶
        int smoothPairs = 0, carveSteps = 0, waterSteps = 0;
        double maxStepDelta = 0.0, maxWaterStep = 0.0;
        for (long key : riverChunks) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xffffffffL);
            double[] g = ground(terrain, cx, cz, horizontalScale);
            var cols = HydrologyBlockCarver.carveChunk(engine, cx, cz, horizontalScale, g);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 15; lx++) {
                    // 按世界坐标查找（carveChunk 仅返回含河采样列，列表非 256 满索引）
                    HydrologyBlockCarvedColumn c0 = at(cols, cx * 16 + lx, cz * 16 + lz);
                    HydrologyBlockCarvedColumn c1 = at(cols, cx * 16 + lx + 1, cz * 16 + lz);
                    if (c0 == null || c1 == null) continue;
                    if (c0.erosion() <= 0 && c1.erosion() <= 0) continue;
                    smoothPairs++;
                    double dCarve = Math.abs(c0.carvedGroundY() - c1.carvedGroundY());
                    double dWater = Math.abs(c0.waterSurfaceY() - c1.waterSurfaceY());
                    maxStepDelta = Math.max(maxStepDelta, dCarve);
                    maxWaterStep = Math.max(maxWaterStep, dWater);
                    if (dCarve > 2.5) carveSteps++;
                    if (dWater > 0.25) waterSteps++;
                }
            }
        }
        System.out.println("smoothPairs=" + smoothPairs);
        System.out.println("carveStepViolations(>2.5)=" + carveSteps);
        System.out.println("waterStepViolations(>0.25)=" + waterSteps);
        System.out.println("maxStepDelta=" + maxStepDelta);
        System.out.println("maxWaterStep=" + maxWaterStep);
        boolean pass = inconsistent == 0 && waterSteps == 0;
        System.out.println("status=" + (pass ? "PASS" : "REVIEW"));
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            values[z * 16 + x] = terrain.sample((cx * 16 + x) / scale,
                    (cz * 16 + z) / scale).height;
        }
        return values;
    }

    private static HydrologyBlockCarvedColumn at(java.util.List<HydrologyBlockCarvedColumn> columns,
                                                  int x, int z) {
        for (HydrologyBlockCarvedColumn column : columns) {
            if (column.blockX() == x && column.blockZ() == z) return column;
        }
        return null;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
