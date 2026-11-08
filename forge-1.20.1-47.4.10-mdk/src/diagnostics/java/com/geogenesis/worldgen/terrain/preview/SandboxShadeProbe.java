package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxShade;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;
import java.util.Locale;

/**
 * <b>沙盘坡度光照分布探针</b>（★ 2026-11-08）。
 *
 * <h2>为什么需要它</h2>
 * <p>上一版沙盘的坡度光照<b>几乎处处触底</b>（量纲错误，见
 * {@link SandboxShade} 的 javadoc），表现为「整幅死平、偏暗、没有层次」。
 * 但这种缺陷<b>只能靠实机肉眼发现</b> —— 交接文档里已经因为「靠实机试错」
 * 浪费了两轮沙盘版本。</p>
 * <p>本探针把「明暗层次够不够」变成<b>可量化的数字</b>：
 * 在<b>真实地形</b>（生产采样路径）上算出每格的光照倍率，报告</p>
 * <ul>
 *   <li><b>饱和率</b>：贴在 {@code LIGHT_MIN}（死平/死黑）或 {@code LIGHT_MAX}
 *       （死亮）的格子占比 —— 这两个数是「有没有层次」的直接证据；</li>
 *   <li><b>动态范围</b>：P05~P95 的光照跨度；</li>
 *   <li><b>水域与陆地的分别统计</b>：水面必须平（低方差）、陆地必须有层次。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <pre>
 *   陆地饱和率（下限+上限） ≤ 35%   ← 不能整幅死平
 *   陆地 P95-P05 光照跨度 ≥ 0.25    ← 必须有可读的明暗层次
 *   水面 P95-P05 光照跨度 ≤ 0.12    ← 水面必须平
 * </pre>
 *
 * <p>用法：{@code gradlew runSandboxShadeProbe}</p>
 */
public final class SandboxShadeProbe {

    private static final int ORIGIN_X = -1400;
    private static final int ORIGIN_Z = -1400;
    private static final int SPAN = 3072;
    private static final long SEED = 12345L;
    private static final double SEA_LEVEL = 63.0;

    /** 陆地饱和率上限（贴在两端）。 */
    private static final double MAX_LAND_SATURATION = 0.35;
    /** 陆地动态范围下限（P95-P05）。 */
    private static final double MIN_LAND_DYNAMIC_RANGE = 0.25;
    /** 水面动态范围上限。 */
    private static final double MAX_WATER_DYNAMIC_RANGE = 0.12;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        System.out.println("=== 沙盘坡度光照分布（S3 视觉判据）===");
        System.out.printf("视口: origin=(%d,%d) span=%d 网格=%d%n%n",
                ORIGIN_X, ORIGIN_Z, SPAN, TerrainFieldData.class.getSimpleName().isEmpty() ? 0 : 128);

        TerrainFieldData d = TerrainFieldData.sample(terrain, ORIGIN_X, ORIGIN_Z,
                SPAN, SPAN, 128, 128, 1L);
        int gw = d.gridW, gh = d.gridH;

        // ---- 归一化高度 r（与柱高同量纲）----
        double base = SEA_LEVEL - 4.0;
        double hi = base;
        for (double h : d.height) if (h > hi) hi = h;
        double span = Math.max(60.0, hi - base);
        System.out.printf("base=%.1f 最高地表=%.1f span=%.1f%n%n", base, hi, span);

        // r[gx*gh+gz]；水域按海平面（与 yardsite 水格记海平面同策）
        double[] r = new double[gw * gh];
        boolean[] water = new boolean[gw * gh];
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                int i = gx * gh + gz;
                boolean w = d.e[i] < 0.0;
                water[i] = w;
                double surface = w ? SEA_LEVEL : d.height[i];
                r[i] = (surface - base) / span;
            }
        }

        // ---- 逐格算光照 ----
        double[] land = new double[gw * gh];
        double[] wat = new double[gw * gh];
        int landN = 0, watN = 0;
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                int i = gx * gh + gz;
                double h = r[i];
                double left = gx > 0 ? r[(gx - 1) * gh + gz] : h;
                double right = gx < gw - 1 ? r[(gx + 1) * gh + gz] : h;
                double up = gz > 0 ? r[gx * gh + (gz - 1)] : h;
                double down = gz < gh - 1 ? r[gx * gh + (gz + 1)] : h;
                double light = SandboxShade.lightFor(left, right, up, down, water[i]);
                if (water[i]) wat[watN++] = light;
                else land[landN++] = light;
            }
        }

        System.out.printf("[1] 陆地: %d 格，水面: %d 格%n", landN, watN);
        report("陆地", Arrays.copyOf(land, landN));
        report("水面", Arrays.copyOf(wat, watN));

        // ---- 负对照：上一版（量纲错误）的算法 ----
        //   旧算法把「每细格上升的方块数」当无量纲坡度用，分母写死 2.0。
        //   这里用同一份数据跑一遍，证明本探针的判据【有牙齿】：
        //   旧算法必须 FAIL，否则说明判据测不出问题。
        System.out.println();
        System.out.println("[负对照] 上一版算法（分母写死 2.0，量纲错误）：");
        double[] oldLand = new double[gw * gh];
        int oldN = 0;
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                int i = gx * gh + gz;
                if (water[i]) continue;
                double h = d.height[i];
                double left = gx > 0 ? d.height[(gx - 1) * gh + gz] : h;
                double right = gx < gw - 1 ? d.height[(gx + 1) * gh + gz] : h;
                double up = gz > 0 ? d.height[gx * gh + (gz - 1)] : h;
                double down = gz < gh - 1 ? d.height[gx * gh + (gz + 1)] : h;
                double slopeX = (right - left) / 2.0;
                double slopeZ = (down - up) / 2.0;
                double light = 1.0 - (slopeX * 0.55 + slopeZ * 0.55);
                if (light < SandboxShade.LIGHT_MIN) light = SandboxShade.LIGHT_MIN;
                if (light > SandboxShade.LIGHT_MAX) light = SandboxShade.LIGHT_MAX;
                oldLand[oldN++] = light;
            }
        }
        double[] oldSorted = Arrays.copyOf(oldLand, oldN);
        Arrays.sort(oldSorted);
        int oldSat = 0;
        for (double v : oldSorted) {
            if (v <= SandboxShade.LIGHT_MIN + 1e-6 || v >= SandboxShade.LIGHT_MAX - 1e-6) oldSat++;
        }
        double oldSatFrac = oldN == 0 ? 0 : oldSat / (double) oldN;
        double oldRange = p(oldSorted, 0.95) - p(oldSorted, 0.05);
        System.out.printf("    旧算法: 饱和率=%.1f%% 动态范围=%.3f%n", oldSatFrac * 100, oldRange);
        report("旧-陆地", oldSorted);

        // ---- 判定 ----
        double[] ls = Arrays.copyOf(land, landN);
        Arrays.sort(ls);
        double[] ws = Arrays.copyOf(wat, watN);
        Arrays.sort(ws);

        double landRange = p(ls, 0.95) - p(ls, 0.05);
        double waterRange = p(ws, 0.95) - p(ws, 0.05);
        int landSat = 0;
        for (double v : ls) {
            if (v <= SandboxShade.LIGHT_MIN + 1e-6 || v >= SandboxShade.LIGHT_MAX - 1e-6) landSat++;
        }
        double landSatFrac = landN == 0 ? 0 : landSat / (double) landN;

        System.out.println();
        boolean pass = true;
        // 负对照：旧算法必须 FAIL，否则本探针测不出问题（判据失效）
        System.out.printf("旧算法饱和率 = %.1f%% 动态范围 = %.3f%n", oldSatFrac * 100, oldRange);
        if (oldSatFrac <= MAX_LAND_SATURATION && oldRange >= MIN_LAND_DYNAMIC_RANGE) {
            System.out.println("FAIL: 负对照未复现旧缺陷 ⇒ 本探针判据失效（测不出问题）");
            pass = false;
        }
        System.out.printf("陆地饱和率 = %.1f%%（上限 %.0f%%）%n", landSatFrac * 100, MAX_LAND_SATURATION * 100);
        if (landSatFrac > MAX_LAND_SATURATION) {
            System.out.println("FAIL: 陆地光照大面积饱和 ⇒ 整幅死平，看不出坡度层次");
            pass = false;
        }
        System.out.printf("陆地动态范围 = %.3f（下限 %.2f）%n", landRange, MIN_LAND_DYNAMIC_RANGE);
        if (landRange < MIN_LAND_DYNAMIC_RANGE) {
            System.out.println("FAIL: 陆地明暗层次不足 ⇒ 地形看着像平板");
            pass = false;
        }
        System.out.printf("水面动态范围 = %.3f（上限 %.2f）%n", waterRange, MAX_WATER_DYNAMIC_RANGE);
        if (waterRange > MAX_WATER_DYNAMIC_RANGE) {
            System.out.println("FAIL: 水面不平 ⇒ 海面出现假的明暗斑块");
            pass = false;
        }

        System.out.println(pass ? "PASS" : "FAIL");
        exportTsv(d);
        System.exit(pass ? 0 : 1);
    }

    /**
     * 导出高度/e 供离线出图脚本使用（{@code tools/render_sandbox_offline.py}）。
     *
     * <p>交接文档 §七 要求「改动前先离线出图自检，别再靠实机试错」，
     * 而离线脚本需要真实地形数据 ⇒ 这里顺手导出。</p>
     */
    private static void exportTsv(TerrainFieldData d) {
        String path = System.getProperty("geogenesis.sandboxTsv");
        if (path == null || path.isEmpty()) return;
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(path, "UTF-8");
            w.println("# gx gz height e biomeId terrainId snow");
            for (int gx = 0; gx < d.gridW; gx++) {
                for (int gz = 0; gz < d.gridH; gz++) {
                    int i = gx * d.gridH + gz;
                    w.printf("%d %d %.6f %.6f %d %d %d%n", gx, gz, d.height[i], d.e[i],
                            d.biomeId[i], d.terrainId[i], d.snow[i] ? 1 : 0);
                }
            }
            w.close();
            System.out.println("导出 TSV: " + path);
        } catch (Exception ex) {
            System.out.println("导出 TSV 失败: " + ex);
        }
    }

    private static void report(String tag, double[] v) {
        if (v.length == 0) {
            System.out.printf("    %s: 无样本%n", tag);
            return;
        }
        double[] s = v.clone();
        Arrays.sort(s);
        int satLo = 0, satHi = 0;
        for (double x : s) {
            if (x <= SandboxShade.LIGHT_MIN + 1e-6) satLo++;
            if (x >= SandboxShade.LIGHT_MAX - 1e-6) satHi++;
        }
        System.out.printf("    %s: P05=%.3f P25=%.3f P50=%.3f P75=%.3f P95=%.3f  贴下限=%.1f%% 贴上限=%.1f%%%n",
                tag, p(s, 0.05), p(s, 0.25), p(s, 0.50), p(s, 0.75), p(s, 0.95),
                100.0 * satLo / s.length, 100.0 * satHi / s.length);
    }

    private static double p(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        double idx = q * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double t = idx - lo;
        return sorted[lo] * (1 - t) + sorted[hi] * t;
    }

    private SandboxShadeProbe() { }
}
