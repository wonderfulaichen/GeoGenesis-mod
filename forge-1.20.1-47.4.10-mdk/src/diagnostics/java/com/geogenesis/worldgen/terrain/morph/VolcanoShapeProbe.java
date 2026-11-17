package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>火山形态判据门禁（2026-11-17 阶段六修正）</b>——验收 {@link VolcanoRecipe}
 * 在生产链里的实际形态，替代已删除的 {@code TectonicFeaturesProbe} 陆侧部分。
 *
 * <p>背景：c72d06a 曾把 {@code LandFeatures} 当「外挂特征层」接线，特写图实测
 * 火山呈带状、覆盖半个窗口。用户裁定「火山应该是类型」⇒ 归位到类型层覆盖族
 * （{@code MorphLandField} 的 overlay 链）。本探针锁死用户目检定稿的形态。</p>
 *
 * <h3>测什么（两类量，分开锁）</h3>
 * <ol>
 *   <li><b>锥体几何</b>（用生产同种子 recipe 直测——不受底形坡度污染）：
 *       <ul>
 *         <li>锥顶净高 ∈ [0.15, 0.28]e（定稿 amp=0.24e + 裙摆 0.0288 − 火口 0.06
 *             ⇒ 名义 0.209e ≈ 49 格；0.24e 全高 = 57 格）。</li>
 *         <li>火口凹坑 ≥ 0.03e（中心 42wu=0.35R 内的最小值 vs 锥顶）。</li>
 *       </ul></li>
 *   <li><b>生产集成</b>（走完整链）：
 *       <ul>
 *         <li>footprint（gate&gt;0.10）半径 ∈ [90, 220]wu（定稿 R=120 ⇒ 锥+裙 ≈1.4R）。</li>
 *         <li>锥顶不淹：{@code sampleE(中心) &gt; 0}——海岸混合带不许把锥顶压进水里。</li>
 *         <li>类型同源：{@code terrainType(中心) == VOLCANO}。</li>
 *         <li>独立：中心间距 ≥ 500wu（网格 1200 + 抖动 ⇒ 理论最小 744wu，
 *             不许再「连成带」）；覆盖 &lt; 3%（旧外挂层实测 1/3）。</li>
 *       </ul></li>
 * </ol>
 *
 * <p>用法：{@code gradlew runVolcanoShapeProbe [-PprobeArgs="<scanSpan> <step>"]}</p>
 */
public final class VolcanoShapeProbe {

    private static final long SEED = 9139912035078620160L;

    // ---- 判据（定稿形态的容差带）----
    private static final double PEAK_MIN = 0.15, PEAK_MAX = 0.28;
    private static final double CRATER_DIP_MIN = 0.03;
    //   gate>0.10（edifice>0.01e）由裙摆主导：0.01e 在裙摆 1.37R=164wu 处，
    //   ×椭圆拉伸 1.5 + 域扭曲 30wu ⇒ 理论最大 276wu。
    private static final double RADIUS_MIN = 90.0, RADIUS_MAX = 280.0;
    private static final double SPACING_MIN = 500.0;
    private static final double COVERAGE_MAX = 0.03;

    public static void main(String[] args) {
        double span = args.length > 0 ? Double.parseDouble(args[0]) : 8000.0;
        double step = args.length > 1 ? Double.parseDouble(args[1]) : 50.0;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);
        VolcanoRecipe recipe = new VolcanoRecipe();
        recipe.seed(SEED + MorphAssembly.OFF_VOLCANO);

        // ---- 1) 粗扫生产 gate>0.10 → 聚簇（生产集成证据）----
        List<double[]> cells = new ArrayList<>();   // [x, z, gate]
        long coveredCells = 0;
        long totalCells = 0;
        for (double z = -span; z <= span; z += step) {
            for (double x = -span; x <= span; x += step) {
                double g = smp.landField().sample(x, z).volcanoGate();
                totalCells++;
                if (g > 0.10) {
                    cells.add(new double[]{x, z, g});
                    coveredCells++;
                }
            }
        }
        double coverage = coveredCells / (double) Math.max(1, totalCells);
        System.out.printf(Locale.ROOT, "[scan] span=%.0fwu step=%.0f  gate>0.10 格 %d/%d（%.2f%%，判据 <%.0f%%）%n",
                span, step, coveredCells, totalCells, coverage * 100, COVERAGE_MAX * 100);

        List<double[]> centers = cluster(cells);
        System.out.println("[簇] 共 " + centers.size() + " 座火山");

        // ---- 2) 逐座测形态 ----
        boolean allOk = coverage <= COVERAGE_MAX;
        int checked = 0;
        for (double[] c : centers) {
            Measured m = measure(smp, recipe, c[0], c[1]);
            if (m == null) {
                continue;
            }
            checked++;
            boolean ok = m.peak >= PEAK_MIN && m.peak <= PEAK_MAX
                    && m.craterDip >= CRATER_DIP_MIN
                    && m.radius >= RADIUS_MIN && m.radius <= RADIUS_MAX
                    && m.summitDry && m.isVolcanoType;
            allOk &= ok;
            System.out.printf(Locale.ROOT,
                    "  (%6.0f,%6.0f) 锥顶=%.3fe 火口凹=%.3fe R=%4.0fwu 淹顶=%s 类型=%s  %s%n",
                    c[0], c[1], m.peak, m.craterDip, m.radius,
                    m.summitDry ? "否" : "是✗",
                    m.isVolcanoType ? "VOLCANO" : "其他✗",
                    ok ? "ok" : "✗判据");
        }

        // ---- 3) 独立性 ----
        double minSpacing = Double.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                double d = Math.hypot(centers.get(i)[0] - centers.get(j)[0],
                        centers.get(i)[1] - centers.get(j)[1]);
                minSpacing = Math.min(minSpacing, d);
            }
        }
        if (centers.size() >= 2) {
            boolean ok = minSpacing >= SPACING_MIN;
            allOk &= ok;
            System.out.printf(Locale.ROOT, "[独立] 最近中心间距 %.0fwu（判据 ≥%.0f）  %s%n",
                    minSpacing, SPACING_MIN, ok ? "ok" : "✗");
        }
        System.out.println("[检查] 测形态 " + checked + "/" + centers.size() + " 座");
        System.out.println(allOk ? "=== ALL PASS ===" : "=== FAIL ===");
        if (!allOk) {
            System.exit(1);
        }
    }

    // ============================================================ 聚簇与测量

    /** 网格吸附（cell = 50wu）+ 4 邻连通聚簇；返回各簇最强 gate 点。 */
    private static List<double[]> cluster(List<double[]> cells) {
        double cs = 50.0;
        java.util.Map<Long, double[]> grid = new java.util.HashMap<>();
        for (double[] c : cells) {
            long key = ((long) Math.floor(c[0] / cs)) * 0x1000000L + (long) Math.floor(c[1] / cs);
            double[] prev = grid.get(key);
            if (prev == null || c[2] > prev[2]) {
                grid.put(key, c);
            }
        }
        List<double[]> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long key : grid.keySet()) {
            if (!seen.add(key)) {
                continue;
            }
            java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
            q.add(key);
            double[] best = null;
            int n = 0;
            while (!q.isEmpty()) {
                long k = q.poll();
                double[] c = grid.get(k);
                n++;
                if (best == null || c[2] > best[2]) {
                    best = c;
                }
                long ix = k / 0x1000000L;
                long iz = k % 0x1000000L;
                for (long[] d : new long[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long nk = (ix + d[0]) * 0x1000000L + (iz + d[1]);
                    if (grid.containsKey(nk) && seen.add(nk)) {
                        q.add(nk);
                    }
                }
            }
            if (best != null && n >= 4) {   // ≥4 格（≈100wu 直径）才算火山
                out.add(best);
            }
        }
        return out;
    }

    /** 单座火山的精细测量。 */
    private static Measured measure(MorphTerrainSampler smp, VolcanoRecipe recipe,
                                    double cx, double cz) {
        // 锥顶精化：recipe 几何在簇心 ±120wu 找最高点（= 撒点真中心）
        double bx = cx, bz = cz, bEd = recipe.height(cx, cz);
        for (double dz = -120; dz <= 120; dz += 6) {
            for (double dx = -120; dx <= 120; dx += 6) {
                double ed = recipe.height(cx + dx, cz + dz);
                if (ed > bEd) {
                    bEd = ed;
                    bx = cx + dx;
                    bz = cz + dz;
                }
            }
        }
        double peak = recipe.height(bx, bz);
        if (peak <= 0.0) {
            return null;
        }

        // 火口凹：锥顶 42wu（0.35R）内 recipe 几何最小值
        double craterMin = peak;
        for (double dz = -42; dz <= 42; dz += 4) {
            for (double dx = -42; dx <= 42; dx += 4) {
                if (dx * dx + dz * dz > 42 * 42) {
                    continue;
                }
                craterMin = Math.min(craterMin, recipe.height(bx + dx, bz + dz));
            }
        }
        double craterDip = peak - craterMin;

        // footprint 半径：生产 gate=0.10 等值线（12 条射线二分）
        double radius = 0;
        for (int k = 0; k < 12; k++) {
            double ang = 2 * Math.PI * k / 12;
            double lo = 0, hi = 400;
            for (int it = 0; it < 8; it++) {
                double mid = (lo + hi) / 2;
                if (smp.landField().sample(bx + mid * Math.cos(ang), bz + mid * Math.sin(ang))
                        .volcanoGate() > 0.10) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            radius += lo;
        }
        radius /= 12;

        // 生产集成：锥顶不淹 + 类型同源
        double eSummit = smp.sampleE(bx, bz);
        boolean summitDry = eSummit > 0.0;
        var col = smp.sampleColumn(bx, bz);
        boolean isVolcanoType = col != null && col.terrainType == TerrainClass.VOLCANO;

        return new Measured(peak, craterDip, radius, summitDry, isVolcanoType);
    }

    private record Measured(double peak, double craterDip, double radius,
                            boolean summitDry, boolean isVolcanoType) { }

    private VolcanoShapeProbe() { }
}
