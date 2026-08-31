package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 谷壁断层探针（2026-08-31）。
 *
 * <p>用户报告：两条河的谷壁叠加处极易出现断层（台阶），因为各河谷壁把地形雕向
 * 自己那条河的水面（追踪原始地形高度），而 {@code carveSurfaceY} 只混合 delta&lt;k(4)
 * 的样本——两河相距 &gt;4 格时，Voronoi 属主切换线上目标水面瞬间从 A 跳到 B，
 * smin 只平滑了距离、没平滑目标水面 → 谷壁台阶。</p>
 *
 * <p>本探针量测：全 block 网格上，谷壁列(dist&gt;width)与其相邻列的雕刻高差。
 * 单条河的正常谷壁是连续缓坡（相邻差小）；两河叠加的缝处会出现大跳（断层）。
 * 统计"谷壁相邻列高差 &ge; 阈值的次数"，并按该列是否多河影响(samples&ge;2)分类。</p>
 */
public final class ValleySeamProbe {

    private ValleySeamProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        // 先收集含河节点的 chunk（+ 邻域），再扫这些 chunk，避免扫到无河区域
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
        // 绝对 block 坐标 → 量测值
        java.util.Map<Long, Double> carvedM = new java.util.HashMap<>();
        java.util.Map<Long, Double> origM = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> wallM = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> multiM = new java.util.HashMap<>();
        java.util.Map<Long, Boolean> fallM = new java.util.HashMap<>();  // 瀑布/冻结列（合法大台阶，排除）
        for (long key : riverChunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            double[] ground = ground(terrain, cx, cz, hs);
            for (HydrologyBlockCarvedColumn c : HydrologyBlockCarver.carveChunk(engine, cx, cz, hs, ground)) {
                long bk = (((long) c.blockX()) << 32) | (c.blockZ() & 0xffffffffL);
                carvedM.put(bk, c.carvedGroundY());
                origM.put(bk, c.originalGroundY());
                List<HydrologyBlockSample> sm = engine.sampleBlockAll(c.blockX(), c.blockZ(), hs);
                if (!sm.isEmpty()) {
                    HydrologyBlockSample s0 = sm.get(0);
                    wallM.put(bk, s0.distToCenter() > Math.max(s0.width(), 1.0));
                    multiM.put(bk, sm.size() >= 2);
                    fallM.put(bk, s0.frozen() || s0.fallDrop() > 0.0);
                }
            }
        }
        java.util.function.BiFunction<Integer, Integer, Long> K =
                (x, z) -> (((long) x) << 32) | (z & 0xffffffffL);

        int totalWallPairs = 0, wallCount = 0;
        int fault = 0, faultMulti = 0;   // 雕刻诱发的断层：carvedStep 明显 > origStep
        double maxFault = 0.0;
        List<Integer> hist = new ArrayList<>();   // 雕刻诱发断层量分桶
        for (long bk : carvedM.keySet()) {
            int x = (int) (bk >> 32), z = (int) bk;
            if (!Boolean.TRUE.equals(wallM.get(bk))) continue;
            wallCount++;
            boolean bkFall = Boolean.TRUE.equals(fallM.get(bk));
            int[][] dirs = {{1, 0}, {0, 1}};
            for (int[] dd : dirs) {
                long nk = K.apply(x + dd[0], z + dd[1]);
                if (!carvedM.containsKey(nk) || !Boolean.TRUE.equals(wallM.get(nk))) continue;
                if (bkFall || Boolean.TRUE.equals(fallM.get(nk))) continue;  // 排除瀑布列
                totalWallPairs++;
                double carvedStep = Math.abs(carvedM.get(bk) - carvedM.get(nk));
                double origStep = Math.abs(origM.get(bk) - origM.get(nk));
                double faultAmt = carvedStep - origStep;   // 雕刻凭空增加的不连续（>0 = 断层）
                int bucket = (int) Math.floor(Math.max(0, faultAmt));
                while (hist.size() <= bucket) hist.add(0);
                hist.set(bucket, hist.get(bucket) + 1);
                if (faultAmt >= 2.0) {   // 雕刻额外造成 >=2 格台阶
                    fault++;
                    if (Boolean.TRUE.equals(multiM.get(bk)) || Boolean.TRUE.equals(multiM.get(nk))) {
                        faultMulti++;
                    }
                }
                maxFault = Math.max(maxFault, faultAmt);
            }
        }

        System.out.println("=== ValleySeamProbe ===");
        System.out.println("seed=" + seed + " 含河chunk=" + riverChunks.size() + " 谷壁列=" + wallCount);
        System.out.println("谷壁相邻列对(排除瀑布)=" + totalWallPairs);
        System.out.println("★ 雕刻诱发断层(carvedStep−origStep>=2)=" + fault
                + "  其中多河叠加=" + faultMulti
                + "  占比=" + (fault > 0 ? String.format("%.0f%%", 100.0 * faultMulti / fault) : "-"));
        System.out.println("最大雕刻断层量=" + String.format("%.1f", maxFault) + " 格");
        System.out.println("雕刻断层量分桶(额外台阶格数:对数)：");
        for (int b = 0; b < hist.size() && b <= 8; b++) {
            if (hist.get(b) > 0) System.out.println("  " + b + "~" + (b + 1) + "格: " + hist.get(b));
        }
        System.out.println("status=" + (faultMulti == 0 ? "PASS(谷壁无多河断层)" : "FAIL(存在多河谷壁断层)"));
    }

    private static double[] ground(CellGenerator terrain, int cx, int cz, double scale) {
        double[] values = new double[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                values[x * 16 + z] = terrain.sample((cx * 16 + x) / scale, (cz * 16 + z) / scale).height;
            }
        }
        return values;
    }
}
