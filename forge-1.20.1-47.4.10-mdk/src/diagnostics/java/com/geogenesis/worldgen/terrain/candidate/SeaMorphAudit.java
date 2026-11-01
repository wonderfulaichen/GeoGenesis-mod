package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * <b>海侧选型的台阶审计</b>（只读，不产出图）。
 *
 * <h3>要回答的问题</h3>
 * <p>用户 2026-11-01 批注：「形态过渡是不是也能为海陆过渡服务？」</p>
 * <p>回答之前必须先确认一件事实：<b>海侧现在到底有没有过渡</b>。</p>
 *
 * <h3>两条链的结构差异（本探针的取证对象）</h3>
 * <pre>
 *   陆侧 FullMorphLand
 *     tile → entryOfSite[] + weights(x,z) → mergeToEntries → RecipeJunctionMorph.sample(x,z,w[18])
 *     ⇒ 【连续】18 条目权重，raw/amp/bias 三层分别加权 ⇒ 形态渐变
 *
 *   海侧 UnifiedWorld
 *     pickOcean(c)      = Math.round(t·4)     ← 单点硬选，无权重
 *     pickCoast(x,z)    = Math.floor(n·5)     ← 单点硬选，无权重
 *     ⇒ 【离散】选中的那一个配方决定高度，切换处是阶跃
 * </pre>
 *
 * <h3>为什么这不是小事</h3>
 * <p>{@code AGENTS.md} 硬约束第 3 条：<b>禁止用「最近邻配对」定义参与地形合成的场</b>
 * —— 配对不变区域内场是分片常数，切换处沿直线/等值线阶跃。</p>
 * <p>{@code pickOcean} 的切换面是 {@code c} 的等值线，{@code pickCoast} 的切换面是
 * {@code coastSel} 的等值线，两者都是光滑场的等值线 ⇒ 阶跃沿等值线成带状。</p>
 *
 * <p>本探针只测量台阶的<b>幅度与数量</b>，不做任何修改。</p>
 */
public final class SeaMorphAudit {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    /** e → 格（陆侧 236、海侧 127，见 OceanRecipeCatalog 的量纲说明）。 */
    private static final double BLOCKS_LAND = 236.0;
    private static final double BLOCKS_SEA = 127.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);

        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        FullMorphLand morph = new FullMorphLand(cat);
        CoastRecipeCatalog coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, true);
        UnifiedWorld w = new UnifiedWorld(morph, coast, ocean, SEED);

        int gn = SIZE;
        double x0 = CX - SIZE / 2.0 * STEP;
        double z0 = CZ - SIZE / 2.0 * STEP;

        // 每点预先算好：海型配方下标、海型高度、海岸配方下标、水边高程
        int[] oIdx = new int[gn * gn];
        double[] oH = new double[gn * gn];
        int[] cIdx = new int[gn * gn];
        double[] cShore = new double[gn * gn];

        OceanRecipeCatalog.Recipe[] allO = OceanRecipeCatalog.Recipe.values();
        CoastRecipeCatalog.Recipe[] allC = CoastRecipeCatalog.Recipe.values();

        for (int j = 0; j < gn; j++) {
            double z = z0 + j * STEP;
            for (int i = 0; i < gn; i++) {
                double x = x0 + i * STEP;
                int p = j * gn + i;

                var or = w.pickOcean(w.c(x, z));
                oIdx[p] = or.id;
                oH[p] = CombineCandidate.seaE(ocean.height01(or, x, z));

                var cr = w.pickCoast(x, z);
                cIdx[p] = cr.id;
                cShore[p] = CoastRecipeCatalog.waterEdgeE(cr);
            }
            if (j % 200 == 0) System.out.println("  scan row=" + j);
        }

        System.out.println();
        auditOcean(oIdx, oH, allO, gn);
        System.out.println();
        auditCoast(cIdx, cShore, allC, gn);

        // ---- 补充：地形类型本身的占比，判断台阶影响面 ----
        int[] oCount = new int[allO.length];
        for (int v : oIdx) oCount[v]++;
        System.out.printf(Locale.ROOT, "%n  --- 海型配方在窗口内的样本占比（%d 点）---%n", gn * gn);
        for (int k = 0; k < allO.length; k++) {
            System.out.printf(Locale.ROOT, "    [%d] %-18s %6.2f%%%n",
                    k, allO[k], 100.0 * oCount[k] / (gn * gn));
        }
        int[] cCount = new int[allC.length];
        for (int v : cIdx) cCount[v]++;
        System.out.printf(Locale.ROOT, "  --- 海岸配方（只在水边带内才有意义）---%n");
        for (int k = 0; k < allC.length; k++) {
            System.out.printf(Locale.ROOT, "    [%d] %-18s %6.2f%%%n",
                    k, allC[k], 100.0 * cCount[k] / (gn * gn));
        }
    }

    /** 海型配方下标变化处的海床高度阶跃。 */
    private static void auditOcean(int[] idx, double[] h, OceanRecipeCatalog.Recipe[] all, int gn) {
        System.out.println("=== 海型选型的台阶（pickOcean = Math.round(t·4)，无权重）===");
        // 按 (k → k') 分桶
        int n = all.length;
        java.util.List<java.util.List<Double>> buckets = new java.util.ArrayList<>();
        for (int i = 0; i < n * n; i++) buckets.add(new java.util.ArrayList<>());

        long changes = 0;
        double maxAbs = 0;
        for (int j = 0; j < gn - 1; j++) {
            for (int i = 0; i < gn - 1; i++) {
                int p = j * gn + i;
                // 只取右邻与下邻，避免重复
                for (int q : new int[]{p + 1, p + gn}) {
                    if (idx[q] == idx[p]) continue;
                    changes++;
                    double d = Math.abs(h[q] - h[p]);
                    buckets.get(idx[p] * n + idx[q]).add(d);
                    if (d > maxAbs) maxAbs = d;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  相邻样本对中发生配方切换 = %d 处（窗口 %d 点）%n",
                changes, gn * gn);
        if (changes == 0) {
            System.out.println("  ⇒ 无切换（异常：说明 pickOcean 恒选同一个）");
            return;
        }
        System.out.printf(Locale.ROOT, "  海床高度阶跃 |Δe| : max = %.4f e  (%.1f 格，按海侧 127 格/e)%n",
                maxAbs, maxAbs * BLOCKS_SEA);
        System.out.println("  明细（只看 Δe 显著的分组，阈 0.01 e）：");
        System.out.printf(Locale.ROOT, "    %-38s %8s %9s %9s %9s%n",
                "切换", "计数", "P50(格)", "P90(格)", "max(格)");
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                var v = buckets.get(a * n + b);
                if (v.isEmpty()) continue;
                double[] arr = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                if (arr[arr.length - 1] < 0.01) continue;
                System.out.printf(Locale.ROOT, "    %-38s %8d %9.1f %9.1f %9.1f%n",
                        all[a] + " → " + all[b], arr.length,
                        q(arr, .50) * BLOCKS_SEA, q(arr, .90) * BLOCKS_SEA,
                        arr[arr.length - 1] * BLOCKS_SEA);
            }
        }
    }

    /** 海岸配方下标变化处的水边高程阶跃。 */
    private static void auditCoast(int[] idx, double[] shore, CoastRecipeCatalog.Recipe[] all,
                                   int gn) {
        System.out.println("=== 海岸类型选型的台阶（pickCoast = Math.floor(n·5)，无权重）===");
        int n = all.length;
        long changes = 0;
        double maxAbs = 0;
        java.util.List<java.util.List<Double>> buckets = new java.util.ArrayList<>();
        for (int i = 0; i < n * n; i++) buckets.add(new java.util.ArrayList<>());

        for (int j = 0; j < gn - 1; j++) {
            for (int i = 0; i < gn - 1; i++) {
                int p = j * gn + i;
                for (int q : new int[]{p + 1, p + gn}) {
                    if (idx[q] == idx[p]) continue;
                    changes++;
                    double d = Math.abs(shore[q] - shore[p]);
                    buckets.get(idx[p] * n + idx[q]).add(d);
                    if (d > maxAbs) maxAbs = d;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  相邻样本对中发生海岸类型切换 = %d 处%n", changes);
        System.out.printf(Locale.ROOT,
                "  水边高程阶跃 |Δe| : max = %.4f e  (%.1f 格，按陆侧 236 格/e)%n",
                maxAbs, maxAbs * BLOCKS_LAND);
        System.out.println("  ⇒ 该值直接等于岸线上（d=0 处）的高度断差：coastBand 在 d=0 取 waterEdgeE(cr)");
        System.out.printf(Locale.ROOT, "    %-38s %8s %9s %9s%n", "切换", "计数", "P50(格)", "max(格)");
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                var v = buckets.get(a * n + b);
                if (v.isEmpty()) continue;
                double[] arr = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                System.out.printf(Locale.ROOT, "    %-38s %8d %9.1f %9.1f%n",
                        all[a] + " → " + all[b], arr.length,
                        q(arr, .50) * BLOCKS_LAND, arr[arr.length - 1] * BLOCKS_LAND);
            }
        }
    }

    private static double q(double[] a, double p) {
        return a[Math.max(0, Math.min(a.length - 1, (int) Math.round(p * (a.length - 1))))];
    }

    private SeaMorphAudit() { }
}
