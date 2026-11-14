package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 沙箱阶段一验收探针：<b>洋底体检</b>——从生产 {@link MorphTerrainSampler} 取整幅洋深，
 * 报告深度分带 / 双尺度粗糙度 / 剖面线，对照地球数值与沙箱标定结果。
 *
 * <p>对照基准（沙箱 1000² 实测，commit a686478 的 tectonicsand 包）：
 * 洋深 p50 ≈ −4870 m 换算、4~5 km 主带 ~45%（地球 45%）、6~7 km 带 ~2%、
 * 深海丘陵使 25 km 尺度坡度 ≈ 2~4 m/km。</p>
 *
 * <p>⚠ 单位：生产洋深 = e × 127 格（海侧）；"地质米"换算沿用沙箱口径
 * {@code m = e × 11000}（即 1 格 ≈ 86.6 地质米）。本探针只报告，不做 PASS/FAIL
 * （形态验收靠人眼 + P1 陆侧 SHA 断言）。</p>
 *
 * <p>用法：{@code gradlew runOceanFloorProbe [-PprobeArgs="<size> <cx> <cz> <radius>]"}<br>
 * 默认 size=800, (0,0), R=1750wu。</p>
 */
public final class OceanFloorProbe {

    /**
     * 抽查窗口（多窗口跑一次）：覆盖洋盆与陆块不同的区域，
     * 验证"陆地占比/深度分带"的窗口稳定性（用户 2026-11-14 要求）。
     * ⚠ 2026-11-15（阶段三）：核链占据场波长 13500wu ⇒ 窗口必须 ≥1 个波长
     * （radius 6750），否则整窗落在单一 occ 气候里（全陆或全海），统计无意义。
     */
    private static final long SEED = 9139912035078620160L;
    private static final double[][] WINDOWS = {
            {0, 0}, {0, 16000}, {-16000, -16000}, {22000, 0}, {16000, 22000}, {-26000, 26000}};

    public static void main(String[] args) {
        // 参数口径 = [size, cx, cz, radius]（与 build.gradle/javadoc 一致）：
        // cx/cz 非 0 时跑单窗口，否则跑 WINDOWS 多窗口体检
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 800;
        double cx = args.length > 1 ? Double.parseDouble(args[1]) : 0;
        double cz = args.length > 2 ? Double.parseDouble(args[2]) : 0;
        double radius = args.length > 3 ? Double.parseDouble(args[3]) : 6750.0;
        long seed = 9139912035078620160L;

        MorphTerrainSampler sampler = new MorphTerrainSampler(seed,
                TerrainParams.defaults(), -64, 320);

        // 诊断：大陆性 c 沿 x 轴（z=0）的变化——判断"远窗口常量地形"是场本身平还是采样问题
        System.out.println("[diag] continentality 沿 x（z=0，每 700wu）:");
        StringBuilder cb = new StringBuilder();
        for (double x = -14000; x <= 14000; x += 700) {
            cb.append(String.format(Locale.ROOT, "%.2f ", sampler.continentality(x, 0)));
        }
        System.out.println("  " + cb);

        if (cx != 0 || cz != 0) {
            window(sampler, n, cx, cz, radius);
        } else {
            for (double[] w : WINDOWS) {
                window(sampler, n, w[0], w[1], radius);
            }
        }
    }

    private static void window(MorphTerrainSampler sampler, int n, double cx, double cz,
                               double radius) {
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            double wz = cz - radius + 2 * radius * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + 2 * radius * i / (n - 1);
                h[j][i] = sampler.sampleE(wx, wz);
            }
        }

        // ---- 1) 深度分带（1000 地质米一档，占洋面 %）----
        ArrayList<Double> sea = new ArrayList<>();
        int land = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double m = h[j][i] * 11000.0;
                if (m > 0) {
                    land++;
                } else {
                    sea.add(m);
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "=== 窗口 (%.0f,%.0f)  陆地占比 %.3f（全球目标 0.339）===%n",
                cx, cz, land / (double) (n * n));
        int[] bands = new int[9];
        for (double v : sea) {
            int k = Math.min(8, (int) Math.floor(-v / 1000.0));
            bands[Math.max(0, k)]++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 9; k++) {
            if (bands[k] == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%d-%dkm %.1f%%  ", k, k + 1,
                    100.0 * bands[k] / sea.size()));
        }
        System.out.println("  洋底深度分带: " + sb);
        System.out.println("  （地球：0~1km ~13%  1~2 ~5%  2~3 ~12%  3~4 ~8%  4~5 45%  5~6 20%  6~7 2%）");

        // ---- 2) 深度分位（洋面内的 p10/p50/p90）----
        sea.sort(Double::compare);
        int m = sea.size();
        if (m > 0) {
            System.out.printf(Locale.ROOT, "  洋深(m): p10=%.0f p25=%.0f p50=%.0f p75=%.0f p90=%.0f p99=%.0f%n",
                    -sea.get((int) (m * 0.10)), -sea.get((int) (m * 0.25)),
                    -sea.get((int) (m * 0.50)), -sea.get((int) (m * 0.75)),
                    -sea.get((int) (m * 0.90)), -sea.get((int) (m * 0.99)));
        }

        // ---- 3) 双尺度粗糙度（深海丘陵指标）----
        double sum1 = 0, sum10 = 0;
        int c1 = 0, c10 = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (h[j][i] > 0) {
                    continue;
                }
                sum1 += Math.abs(h[j][i + 1] - h[j][i]);
                c1++;
                if (i + 10 < n) {
                    sum10 += Math.abs(h[j][i + 10] - h[j][i]) / 10.0;
                    c10++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  洋底粗糙度: 1格 %.0f m/格   10格 %.0f m/格（比值 %.1f）%n",
                sum1 / Math.max(1, c1) * 11000, sum10 / Math.max(1, c10) * 11000,
                (sum1 / Math.max(1, c1)) / Math.max(1e-9, sum10 / Math.max(1, c10)));
    }
}