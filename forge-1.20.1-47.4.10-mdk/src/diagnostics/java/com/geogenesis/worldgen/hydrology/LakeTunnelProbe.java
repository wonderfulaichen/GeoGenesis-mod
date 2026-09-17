package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「山脊隧穿」假设验证探针（2026-09-17）。
 *
 * <h2>待验证的假设</h2>
 * <p>{@code RiverLineRegion.computeFlood} 的 BFS 用<b>粗格</b>判"是否该淹"，而采样函数
 * {@code erodedHeightAt} 取 <b>格心 + 4 角的最小值</b>：</p>
 * <pre>
 *   h = min(格心, 左上, 右上, 左下, 右下)
 * </pre>
 * <p>⇒ 一个粗格<b>只要有一个角低于水位就算淹</b> ⇒ BFS 可能<b>穿过山脊"隧穿"</b>
 * （山脊两侧的低格被同一个粗格连通）⇒ 淹水区越过分水岭延伸。</p>
 * <p>这可能解释实机看到的"<b>填出山腰外的悬空水板</b>"与边界上的<b>格网直角</b>。</p>
 *
 * <h2>本探针做什么</h2>
 * <p>对同一湖，用两种采样各跑一次 BFS（其余参数完全一致）：</p>
 * <ol>
 *   <li><b>5 点取 min</b>（现状）；</li>
 *   <li><b>格心单点</b>（严格）。</li>
 * </ol>
 * <p>比较：淹水格数、包围盒、以及 ASCII 形状（可直接看出是否"隧穿"与直角）。</p>
 *
 * <pre>{@code gradlew runLakeTunnelProbe [-PprobeArgs="seed lakeX lakeZ"]}</pre>
 */
public final class LakeTunnelProbe {

    /** 与生产一致：BFS 粗格 = claimGrid / 2；搜索窗 pad = 72wu（按物理距离换算）。 */
    private static final double CLAIM_GRID = 24.0;   // RiverLineParams.defaults().gridCell()
    private static final double BFS_GRID = CLAIM_GRID * 0.5;
    private static final double PAD_WU = 72.0;

    private LakeTunnelProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        double lakeX = args.length > 1 ? Double.parseDouble(args[1]) : 9.0;
        double lakeZ = args.length > 2 ? Double.parseDouble(args[2]) : 376.0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();

        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();

        // 找目标湖
        RiverLineRegion.LakeNode target = null;
        int rx = (int) Math.floor(lakeX / rp.regionSize());
        int rz = (int) Math.floor(lakeZ / rp.regionSize());
        for (int dx = -1; dx <= 1 && target == null; dx++) {
            for (int dz = -1; dz <= 1 && target == null; dz++) {
                RiverLineRegion r = net.region(rx + dx, rz + dz);
                if (r == null) continue;
                for (RiverLineRegion.LakeNode ln : r.lakes) {
                    if (Math.hypot(ln.x - lakeX, ln.z - lakeZ) < 30) { target = ln; break; }
                }
            }
        }
        System.out.printf("=== LakeTunnelProbe seed=%d 目标湖 wu(%.1f,%.1f) ===%n", seed, lakeX, lakeZ);
        if (target == null) {
            System.out.println("未找到该湖。");
            return;
        }
        java.util.function.ToDoubleBiFunction<Double, Double> ey =
                (a, b) -> gen.sampleWu(a, b).height;
        double level = target.erodedWaterLevel(ey);
        System.out.printf("湖：中心 wu(%.1f,%.1f)  轮廓格 %d  spill(无侵蚀)=%.3f  "
                        + "erodedWaterLevel=%.3f%n",
                target.x, target.z, target.cellX == null ? 0 : target.cellX.length,
                target.height, level);

        int[] a = bfs(target, ey, level, true);
        int[] b = bfs(target, ey, level, false);
        System.out.println();
        System.out.printf("[1] 5 点取 min（现状）：淹水粗格 %d%n", a[0]);
        System.out.printf("[2] 格心单点（严格）  ：淹水粗格 %d%n", b[0]);
        System.out.printf("    ⇒ 差值 %+d 格（%.1f%%）%n", a[0] - b[0],
                b[0] == 0 ? 0.0 : 100.0 * (a[0] - b[0]) / b[0]);
        System.out.println();
        System.out.println("判读：若『5 点取 min』显著多于『格心单点』 ⇒ **隧穿假设成立**");
        System.out.println("      （粗格的四角惩罚让 BFS 越过了本不该连通的山脊）⇒");
        System.out.println("      修法 = 通行条件改用【格心】或【更严格的判据】，而非加密网格。");
    }

    /**
     * 复刻 {@code computeFlood} 的 BFS，仅改采样方式。
     *
     * @param fivePoint true = 格心+4角取 min（现状）；false = 格心单点
     * @return {淹水格数, 包围盒宽, 包围盒高}
     */
    private static int[] bfs(RiverLineRegion.LakeNode ln,
                             java.util.function.ToDoubleBiFunction<Double, Double> ey,
                             double level, boolean fivePoint) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < ln.cellX.length; i++) {
            minX = Math.min(minX, ln.cellX[i]); maxX = Math.max(maxX, ln.cellX[i]);
            minZ = Math.min(minZ, ln.cellZ[i]); maxZ = Math.max(maxZ, ln.cellZ[i]);
        }
        int pad = (int) Math.round(PAD_WU / BFS_GRID);
        minX -= pad * BFS_GRID; maxX += pad * BFS_GRID;
        minZ -= pad * BFS_GRID; maxZ += pad * BFS_GRID;
        int nx = (int) Math.floor((maxX - minX) / BFS_GRID) + 1;
        int nz = (int) Math.floor((maxZ - minZ) / BFS_GRID) + 1;
        if ((long) nx * nz > 40000L) return new int[]{-1, nx, nz};

        double best = Double.MAX_VALUE;
        int si = 0, sj = 0;
        for (int i = 0; i < ln.cellX.length; i++) {
            double h = ey.applyAsDouble(ln.cellX[i], ln.cellZ[i]);
            if (h < best) {
                best = h;
                si = (int) Math.round((ln.cellX[i] - minX) / BFS_GRID);
                sj = (int) Math.round((ln.cellZ[i] - minZ) / BFS_GRID);
            }
        }
        si = Math.max(0, Math.min(nx - 1, si));
        sj = Math.max(0, Math.min(nz - 1, sj));

        boolean[] seen = new boolean[nx * nz];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(sj * nx + si);
        seen[sj * nx + si] = true;
        int[] dxx = {1, -1, 0, 0}, dzz = {0, 0, 1, -1};
        int count = 0;
        int loI = nx, hiI = -1, loJ = nz, hiJ = -1;
        while (!q.isEmpty()) {
            int cur = q.poll();
            int ci = cur % nx, cj = cur / nx;
            count++;
            loI = Math.min(loI, ci); hiI = Math.max(hiI, ci);
            loJ = Math.min(loJ, cj); hiJ = Math.max(hiJ, cj);
            for (int d = 0; d < 4; d++) {
                int ni = ci + dxx[d], nj = cj + dzz[d];
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nIdx = nj * nx + ni;
                if (seen[nIdx]) continue;
                double h = fivePoint
                        ? hFive(ey, minX + ni * BFS_GRID, minZ + nj * BFS_GRID, BFS_GRID)
                        : ey.applyAsDouble(minX + ni * BFS_GRID, minZ + nj * BFS_GRID);
                if (h < level - 0.05) { seen[nIdx] = true; q.add(nIdx); }
            }
        }
        return new int[]{count, hiI - loI + 1, hiJ - loJ + 1};
    }

    /** 与 {@code RiverLineRegion.erodedHeightAt} 一致：格心 + 4 角取 min。 */
    private static double hFive(java.util.function.ToDoubleBiFunction<Double, Double> ey,
                                double gx, double gz, double grid) {
        double q = grid * 0.5;
        double h = ey.applyAsDouble(gx, gz);
        h = Math.min(h, ey.applyAsDouble(gx - q, gz - q));
        h = Math.min(h, ey.applyAsDouble(gx + q, gz - q));
        h = Math.min(h, ey.applyAsDouble(gx - q, gz + q));
        h = Math.min(h, ey.applyAsDouble(gx + q, gz + q));
        return h;
    }
}
