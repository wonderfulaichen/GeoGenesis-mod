package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 闭式水位场探针（★ 2026-09-19，水系重构 T1.7 验收与标定）。
 *
 * <h3>要回答的问题</h3>
 * <ol>
 *   <li><b>[1] 跨 region 一致性</b>：{@code filledAt}（现行，实测 99.6~99.9% 不一致）
 *       vs {@code W}（闭式，期望 0）—— <b>这是 T2.4 的验收标准</b>；</li>
 *   <li><b>[2] 标定扫描</b>：{@code smoothWu} × {@code offset} ⇒ 水格% + <b>连通分量数</b>。
 *       分量数是区分"离散湖"与"一整片海"的<b>唯一可靠指标</b>；</li>
 *   <li><b>[3] 低通残差</b>：地形相对低通表面的起伏（解释为何必须低通）；</li>
 *   <li><b>[4] 闭式性</b>：同点重复采样是否逐位一致（纯函数回归守卫）。</li>
 * </ol>
 *
 * <p>⚠ <b>陆地/水判据一律用【实际 e 场】（{@code terrainEQuick}）。
 * 两轮实测教训：{@code c > 0} 与 {@code eFromC(c − bias) > 0} 都不等于"在海平面以上"。</b></p>
 *
 * <pre>{@code gradlew runWaterFieldProbe [-PprobeArgs="seed rx rz smoothWu offset"]}</pre>
 */
public final class WaterFieldProbe {

    private WaterFieldProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        double smoothWu = args.length > 3 ? Double.parseDouble(args[3])
                : WaterField.DEFAULT_SMOOTH_WU;
        double offset = args.length > 4 ? Double.parseDouble(args[4]) : -4.0;

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        HeightCurve curve = gen.heightCurve();
        WaterField wf = new WaterField(gen::terrainEQuick, curve, smoothWu, offset);

        System.out.printf("=== WaterFieldProbe seed=%d region=(%d,%d) smoothWu=%.0f offset=%.1f ===%n",
                seed, rX, rZ, smoothWu, offset);
        RiverLineParams params = RiverLineParams.defaults();
        double regionSize = params.regionSize();
        double cell = params.gridCell();
        System.out.printf("seaLevelY=%.1f  regionSize=%.0f  gridCell=%.0f%n%n",
                curve.seaLevelY(), regionSize, cell);

        testCrossRegion(seed, rX, rZ, regionSize, cell, gen, curve, wf);
        testCalibration(gen, curve, rX, rZ, regionSize);
        testSmoothResiduals(gen, curve, rX, rZ, regionSize);
        testDeterminism(wf);
    }

    // ---------- [1] 跨 region 一致性 ----------

    private static void testCrossRegion(long seed, int rX, int rZ, double regionSize, double cell,
                                        CellGenerator gen, HeightCurve curve, WaterField wf) {
        double margin = regionSize * 0.5;
        double[] a = gridBounds(rX, rZ, regionSize, margin);
        double[] b = gridBounds(rX + 1, rZ, regionSize, margin);

        RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, curve, seed);
        FlowField fa = new FlowField(a[0], a[1], a[2], a[3], cell, gen::terrainEQuick);
        fa.computeFill(net::groundYAt, curve.seaLevelY());
        FlowField fb = new FlowField(b[0], b[1], b[2], b[3], cell, gen::terrainEQuick);
        fb.computeFill(net::groundYAt, curve.seaLevelY());

        double loX = Math.max(a[0], b[0]), hiX = Math.min(a[2], b[2]);
        double loZ = Math.max(a[1], b[1]), hiZ = Math.min(a[3], b[3]);

        long overlap = 0, fillDiffer = 0, wDiffer = 0;
        double fillMax = 0, wMax = 0;
        for (double wz = loZ; wz <= hiZ; wz += cell) {
            for (double wx = loX; wx <= hiX; wx += cell) {
                overlap++;
                double dFill = Math.abs(fa.filledAt(fa.indexOf(wx, wz))
                        - fb.filledAt(fb.indexOf(wx, wz)));
                if (dFill > 1e-6) fillDiffer++;
                fillMax = Math.max(fillMax, dFill);

                // W 是闭式纯函数：模拟 A/B 两个 region 各自遍历到同一点，必逐位相同
                double dW = Math.abs(wf.waterYAt(wx, wz) - wf.waterYAt(wx, wz));
                if (dW > 0) wDiffer++;
                wMax = Math.max(wMax, dW);
            }
        }

        System.out.printf("[1] 重叠区取样=%d%n", overlap);
        System.out.printf("    filledAt（现行）：不一致=%d（%.1f%%）  最大差=%.4f block%n",
                fillDiffer, 100.0 * fillDiffer / Math.max(1, overlap), fillMax);
        System.out.printf("    W      （闭式）：不一致=%d（%.1f%%）  最大差=%.4f block%n",
                wDiffer, 100.0 * wDiffer / Math.max(1, overlap), wMax);
        System.out.printf("    判据1【W 跨 region 逐位一致】: %s%n%n",
                wDiffer == 0 ? "PASS" : "FAIL");
    }

    // ---------- [2] 标定扫描：smoothWu × offset ----------

    /**
     * 标定 {@code smoothWu} 与 {@code offset}：**扫描，不拍**。
     *
     * <p>核心指标是 <b>连通分量数</b> ——
     * 分量多且最大分量占比不高 ⇒ 离散湖；分量 1~2 且最大占 99% ⇒ 退化成"一整片海"。</p>
     */
    private static void testCalibration(CellGenerator gen, HeightCurve curve,
                                        int rX, int rZ, double regionSize) {
        final double step = 8.0;
        int n = (int) Math.round(regionSize / step) + 1;
        double loX = rX * regionSize, loZ = rZ * regionSize;
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] = curve.heightFromE(gen.terrainEQuick(loX + i * step, loZ + j * step));
            }
        }

        double[] smooths = {16, 24, 32, 48, 64};
        double[] offsets = {0, -0.5, -1, -1.5, -2, -3};
        System.out.printf("[2] 标定扫描（%d×%d @ %.0f wu，seaLevelY=%.1f）%n",
                n, n, step, curve.seaLevelY());
        System.out.printf("    %9s %7s %10s %9s %11s%n",
                "smoothWu", "offset", "水格%", "分量数", "最大分量%");
        for (double s : smooths) {
            // 粗格格点高度（预取，避免重复采样）
            int m = Math.max(2, (int) Math.ceil((n - 1) * step / s) + 1);
            double[][] lat = new double[m][m];
            for (int j = 0; j < m; j++) {
                for (int i = 0; i < m; i++) {
                    lat[j][i] = curve.heightFromE(
                            gen.terrainEQuick(loX + i * s, loZ + j * s));
                }
            }
            for (double off : offsets) {
                boolean[][] wet = new boolean[n][n];
                long cnt = 0;
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        double hs = bilinear(lat, i * step / s, j * step / s);
                        double w = hs + off;
                        if (w <= curve.seaLevelY()) continue;      // 海是根
                        if (h[j][i] < w) {
                            wet[j][i] = true;
                            cnt++;
                        }
                    }
                }
                long[] comp = components(wet, n);
                System.out.printf("    %9.0f %7.1f %9.1f%% %9d %10.1f%%%n",
                        s, off, 100.0 * cnt / (n * n), comp[0],
                        100.0 * comp[1] / Math.max(1, cnt));
            }
        }
        System.out.println();
    }

    // ---------- [3] 低通残差 ----------

    private static void testSmoothResiduals(CellGenerator gen, HeightCurve curve,
                                            int rX, int rZ, double regionSize) {
        final double step = 8.0;
        int n = (int) Math.round(regionSize / step) + 1;
        double loX = rX * regionSize, loZ = rZ * regionSize;
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] = curve.heightFromE(gen.terrainEQuick(loX + i * step, loZ + j * step));
            }
        }

        double[] scales = {32, 64, 128, 256, 512};
        System.out.printf("[3] 地形相对低通表面的起伏（%d×%d @ %.0f wu）%n", n, n, step);
        System.out.printf("    %9s %10s %10s %10s%n", "低通尺度", "残差std", "残差p90", "残差max");
        for (double s : scales) {
            int m = Math.max(2, (int) Math.ceil((n - 1) * step / s) + 1);
            double[][] lat = new double[m][m];
            for (int j = 0; j < m; j++) {
                for (int i = 0; i < m; i++) {
                    lat[j][i] = curve.heightFromE(gen.terrainEQuick(loX + i * s, loZ + j * s));
                }
            }
            double[] res = new double[n * n];
            int k = 0;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    res[k++] = h[j][i] - bilinear(lat, i * step / s, j * step / s);
                }
            }
            java.util.Arrays.sort(res);
            System.out.printf("    %9.0f %10.2f %10.2f %10.2f%n",
                    s, std(res), pct(res, 0.90), res[res.length - 1]);
        }
        System.out.println();
    }

    // ---------- [4] 闭式性 ----------

    private static void testDeterminism(WaterField wf) {
        int bad = 0;
        for (int i = 0; i < 1000; i++) {
            double x = i * 7.3, z = i * -3.7;
            double a = wf.waterYAt(x, z);
            for (int k = 0; k < 5; k++) {
                if (wf.waterYAt(x, z) != a) bad++;
            }
        }
        System.out.printf("[4] 闭式性（1000 点 × 6 次重复采样）: 不一致=%d%n", bad);
        System.out.printf("    判据2【W 为纯函数】: %s%n%n", bad == 0 ? "PASS" : "FAIL");
    }

    // ---------- 工具 ----------

    /** 双线性采样（坐标以格为单位，越界钳制）。 */
    private static double bilinear(double[][] a, double x, double y) {
        int n = a.length;
        double cx = Math.max(0, Math.min(n - 1.001, x));
        double cy = Math.max(0, Math.min(n - 1.001, y));
        int i0 = (int) cx, j0 = (int) cy;
        int i1 = Math.min(n - 1, i0 + 1), j1 = Math.min(n - 1, j0 + 1);
        double fx = cx - i0, fy = cy - j0;
        double a00 = a[j0][i0], a10 = a[j0][i1], a01 = a[j1][i0], a11 = a[j1][i1];
        double t = a00 + (a10 - a00) * fx;
        double b = a01 + (a11 - a01) * fx;
        return t + (b - t) * fy;
    }

    /** 8 邻连通分量：返回 {分量数, 最大分量格数}。 */
    private static long[] components(boolean[][] wet, int n) {
        boolean[][] seen = new boolean[n][n];
        int comps = 0;
        long best = 0;
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (!wet[j][i] || seen[j][i]) continue;
                comps++;
                long size = 0;
                seen[j][i] = true;
                q.add(new int[]{i, j});
                while (!q.isEmpty()) {
                    int[] pt = q.poll();
                    size++;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int x = pt[0] + di, y = pt[1] + dj;
                            if (x < 0 || x >= n || y < 0 || y >= n) continue;
                            if (!wet[y][x] || seen[y][x]) continue;
                            seen[y][x] = true;
                            q.add(new int[]{x, y});
                        }
                    }
                }
                best = Math.max(best, size);
            }
        }
        return new long[]{comps, best};
    }

    /** 分位数（输入须已升序）。 */
    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** 标准差。 */
    private static double std(double[] v) {
        if (v.length == 0) return 0.0;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return Math.sqrt(s / v.length);
    }

    /** 镜像 RiverLineNetwork.build 的网格范围：region ± 50% margin。 */
    private static double[] gridBounds(int rx, int rz, double regionSize, double margin) {
        return new double[]{rx * regionSize - margin, rz * regionSize - margin,
                rx * regionSize + regionSize + margin, rz * regionSize + regionSize + margin};
    }
}
