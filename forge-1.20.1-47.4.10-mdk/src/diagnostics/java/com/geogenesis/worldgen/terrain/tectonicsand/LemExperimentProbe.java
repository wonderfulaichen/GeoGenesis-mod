package com.geogenesis.worldgen.terrain.tectonicsand;

import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import java.util.Locale;

/**
 * <b>阶段四预研：LEM 离线实验</b>——在<b>生产地形</b>上跑沙箱 LEM，量化收益与成本。
 *
 * <h3>为什么先做实验而不是直接接线</h3>
 * <p>沙箱记录（audit 第十轮）显示 LEM 30 轮只把相邻格平均高差从 0.1828 降到 0.1809
 * （≈1%）——它主要贡献的是<b>树枝状谷形</b>而非削低地形。生产接线成本很高
 * （区域格 + 缓存 + 预热 + 接缝近似），所以先用本探针量化三件事，收益不足就不接：</p>
 * <ol>
 *   <li><b>谷形结构</b>：汇水面积加权下切量分布（有多少格被切、切多深）</li>
 *   <li><b>海岸分形维数 D</b>：盒计数法（真实大陆 1.20~1.35，圆 → 1.00）。
 *       这是「海岸犬牙交错」的定量判据</li>
 *   <li><b>耗时</b>：单区域（512 块 = 256 格 @ step2）的毫秒数，用于估算整世界成本</li>
 * </ol>
 *
 * <h3>做法</h3>
 * <p>取生产 {@link MorphTerrainSampler} 在指定窗口的 e 场（step=2 块，与水文模块同口径），
 * 跑 {@link SandLandscape#evolve}（30 轮，沙箱标定参数），对比前后指标。</p>
 *
 * <p>⚠ 本探针<b>只报告不判定</b>（无 PASS/FAIL）——它是决策输入，不是门禁。</p>
 *
 * <p>用法：{@code gradlew runLemExperimentProbe [-PprobeArgs="&lt;size&gt; &lt;step&gt; &lt;cx&gt; &lt;cz&gt; &lt;iters&gt;"]}<br>
 * 默认 size=256, step=2, (cx,cz)=(0,0) wu, iters=30（= 512 块窗口，与水文区域同尺度）。</p>
 */
public final class LemExperimentProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 256;
        double step = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        double cx = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;
        double cz = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        int iters = args.length > 4 ? Integer.parseInt(args[4]) : 30;

        MorphTerrainSampler sampler = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        // ★ 窗口自动搜索：LEM 的收益（树枝状谷、海岸分形）只在<b>有海岸线</b>时可见。
        //   纯陆窗口 landFrac=1.0、纯海窗口 0.0 都测不出分形 D ⇒ 必须落在 0.3~0.7。
        //   未显式给 (cx,cz)（args<3）时自动搜索。
        if (args.length < 3) {
            double[] w = findCoastalWindow(sampler, n, step);
            cx = w[0];
            cz = w[1];
            System.out.printf(Locale.ROOT, "[选窗] centre=(%.0f,%.0f) 陆地占比 %.2f%n", cx, cz, w[2]);
        }

        // ---- 1) 取生产 e 场 ----
        long t0 = System.nanoTime();
        double[][] h = new double[n][n];
        double half = (n - 1) * step / 2.0;
        for (int j = 0; j < n; j++) {
            double wz = cz - half + j * step;
            for (int i = 0; i < n; i++) {
                h[j][i] = sampler.sampleE(cx - half + i * step, wz);
            }
        }
        long sampleMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf(Locale.ROOT,
                "=== LEM 离线实验 seed=%d size=%d step=%.0f centre=(%.0f,%.0f) iters=%d ===%n",
                SEED, n, step, cx, cz, iters);
        System.out.printf(Locale.ROOT, "[取场] %d 次生产采样 %d ms%n", n * n, sampleMs);

        Stats before = stats(h, step);
        System.out.println("[LEM 前] " + before);

        // ---- 2) 跑 LEM（沙箱标定参数）----
        double[][] grid = new double[n][];
        for (int j = 0; j < n; j++) {
            grid[j] = h[j].clone();
        }
        long t1 = System.nanoTime();
        SandLandscape.evolve(grid, iters, 0.0,
                new SandLandscape.Params(0.06, 0.42, 1.0, 0.04, 1.6e-4, 0.12,
                        0.05, 1e-4, 30.0, 0.5),
                null);
        long lemMs = (System.nanoTime() - t1) / 1_000_000L;
        System.out.printf(Locale.ROOT, "[LEM] %d 轮 %d ms（%.1f ms/轮）%n",
                iters, lemMs, lemMs / (double) iters);

        // ---- 下切量分布（LEM 前后逐格差，仅陆格）----
        double maxCut = 0;
        double sumCut = 0;
        int cutCells = 0;
        int landCells = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0) {
                    continue;
                }
                landCells++;
                double d = h[j][i] - grid[j][i];   // 正 = 被切掉
                if (d > 0.002) {
                    cutCells++;
                    sumCut += d;
                    maxCut = Math.max(maxCut, d);
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "[下切] 陆格 %d  被切(>0.002e) %d（%.1f%%）  平均 %.3f 格  最深 %.1f 格%n",
                landCells, cutCells, 100.0 * cutCells / Math.max(1, landCells),
                sumCut / Math.max(1, cutCells) * 127.0, maxCut * 127.0);

        Stats after = stats(grid, step);
        System.out.println("[LEM 后] " + after);

        // ---- 3) 判定素材：谷形 / 分形 D / 成本 ----
        System.out.printf(Locale.ROOT,
                "%n[结论素材]%n"
                + "  相邻高差  P50 %.4f -> %.4f（%.1f%%）  最大下切 %.1f 格%n"
                + "  海岸分形D %.3f -> %.3f（目标 1.20~1.35；圆=1.00）%n"
                + "  陆地占比  %.3f -> %.3f%n"
                + "  耗时 %d ms / %d² 格区域 ⇒ 世界铺开约 %.1f ms/km²（step=%.0f）%n",
                before.p50Neighbor, after.p50Neighbor,
                100.0 * (after.p50Neighbor - before.p50Neighbor) / Math.max(1e-9, before.p50Neighbor),
                after.maxCut,
                before.fractalD, after.fractalD,
                before.landFrac, after.landFrac,
                lemMs, n, lemMs / (n * step * n * step / 1e6), step);

        boolean worthwhile = after.fractalD - before.fractalD > 0.03
                || before.p50Neighbor - after.p50Neighbor > 0.01;
        System.out.println(worthwhile
                ? "  ⇒ 收益显著（分形D 或谷深有可见改善），值得接线"
                : "  ⇒ 收益微弱（沙箱结论重现），建议先调参或改轻量方案");
    }

    /** 窗口自动搜索：粗采样找陆地占比最接近 0.45 的窗口（跨海岸）。 */
    private static double[] findCoastalWindow(MorphTerrainSampler sampler, int n, double step) {
        final int m = 48;                 // 粗采样分辨率（每窗 2304 点）
        double half = (n - 1) * step / 2.0;
        double bestScore = Double.MAX_VALUE;
        double[] best = {0, 0, 0};
        for (double cz = -30000; cz <= 30000; cz += 4000) {
            for (double cx = -30000; cx <= 30000; cx += 4000) {
                int land = 0;
                for (int j = 0; j < m; j++) {
                    double wz = cz - half + j * (2 * half / (m - 1));
                    for (int i = 0; i < m; i++) {
                        double wx = cx - half + i * (2 * half / (m - 1));
                        if (sampler.sampleE(wx, wz) > 0) {
                            land++;
                        }
                    }
                }
                double lf = land / (double) (m * m);
                double score = Math.abs(lf - 0.45);
                if (score < bestScore) {
                    bestScore = score;
                    best = new double[]{cx, cz, lf};
                }
            }
        }
        return best;
    }

    /** 窗口统计：分形 D / 相邻高差 P50 / 陆地占比 / 最大下切。 */
    private record Stats(double fractalD, double p50Neighbor, double landFrac, double maxCut) { }

    private static Stats stats(double[][] h, double step) {
        int n = h.length;
        int land = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] > 0) {
                    land++;
                }
            }
        }
        // 相邻高差（四邻域，e 单位）：右邻 n(n−1) + 下邻 n(n−1) = 2n(n−1)
        double[] diffs = new double[2 * n * (n - 1)];
        int cnt = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (i + 1 < n) {
                    diffs[cnt++] = Math.abs(h[j][i + 1] - h[j][i]);
                }
                if (j + 1 < n) {
                    diffs[cnt++] = Math.abs(h[j + 1][i] - h[j][i]);
                }
            }
        }
        java.util.Arrays.sort(diffs, 0, cnt);
        double p50 = cnt > 0 ? diffs[cnt / 2] : 0.0;

        // 盒计数分形维数（与 SandSurfaceProbe 同法：ε=2..16 四档最小二乘）
        int[] boxSizes = {2, 4, 8, 16, 32};
        int[] boxCount = new int[boxSizes.length];
        for (int b = 0; b < boxSizes.length; b++) {
            int e = boxSizes[b];
            int c = 0;
            for (int j = 0; j + e <= n; j += e) {
                for (int i = 0; i + e <= n; i += e) {
                    boolean hasLand = false, hasSea = false;
                    for (int jj = j; jj < j + e && !(hasLand && hasSea); jj++) {
                        for (int ii = i; ii < i + e; ii++) {
                            if (h[jj][ii] > 0) {
                                hasLand = true;
                            } else {
                                hasSea = true;
                            }
                            if (hasLand && hasSea) {
                                break;
                            }
                        }
                    }
                    if (hasLand && hasSea) {
                        c++;
                    }
                }
            }
            boxCount[b] = c;
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int mfit = 0;
        for (int b = 0; b < boxSizes.length - 1; b++) {
            if (boxCount[b] <= 0) {
                continue;
            }
            double lx = Math.log(boxSizes[b]);
            double ly = Math.log(boxCount[b]);
            sx += lx;
            sy += ly;
            sxx += lx * lx;
            sxy += lx * ly;
            mfit++;
        }
        double frac = 1.0;
        if (mfit >= 2) {
            double slope = (mfit * sxy - sx * sy) / Math.max(1e-12, mfit * sxx - sx * sx);
            frac = -slope;
        }
        return new Stats(frac, p50, land / (double) (n * n), 0);
    }

    private LemExperimentProbe() { }
}
