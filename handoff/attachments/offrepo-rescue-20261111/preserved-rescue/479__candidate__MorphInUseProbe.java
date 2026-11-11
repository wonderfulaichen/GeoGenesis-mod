package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 证明 {@link UnifiedWorld} 用的是<b>已定稿的</b> {@link FullMorphLand}，
 * 而不是更早的 {@link CombineCandidate}。
 *
 * <p>两者换算口径相同（{@code max(GROUND_E + h·E_SPAN, 0)}），
 * 所以数值范围一样；但<b>过渡机制不同</b> ⇒ 同一点的高度应当不同。
 * 本探针直接对比两者的输出差、以及"谁更接近已定稿的基准"。</p>
 */
public final class MorphInUseProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        Locale.setDefault(java.util.Locale.ROOT);
        var cat = new RecipeMorphCatalog(SEED);
        CombineCandidate oldLand = cat.original();
        FullMorphLand morph = new FullMorphLand(cat);

        System.out.println("=== CombineCandidate(旧) vs FullMorphLand(定稿) ===");
        System.out.println("     x       z    oldCC      morphE     diff");
        int n = 0, diff = 0;
        double maxAbs = 0, sumAbs = 0;
        java.util.Random rnd = new java.util.Random(20261101);
        for (int i = 0; i < 3000; i++) {
            double x = rnd.nextDouble() * 14000 - 7000;
            double z = rnd.nextDouble() * 14000 - 7000;
            double a = Math.max(CombineCandidate.GROUND_E + oldLand.height01(x, z)
                    * CombineCandidate.E_SPAN, 0.0);
            double b = morph.e(x, z);
            n++;
            double d = Math.abs(b - a);
            if (d > 1e-9) diff++;
            maxAbs = Math.max(maxAbs, d);
            sumAbs += d;
            if (i < 12) {
                System.out.printf(Locale.ROOT, "  %6.0f  %6.0f  %+.5f   %+.5f   %.5f%n",
                        x, z, a, b, d);
            }
        }
        System.out.printf(Locale.ROOT,
                "%n样本 %d：不同 %d (%.1f%%)  平均差 %.5f e  最大差 %.5f e (%.1f 格)%n",
                n, diff, 100.0 * diff / n, sumAbs / n, maxAbs, maxAbs * 257.0);

        // 关键判据：若两者逐位相同，说明 UnifiedWorld 拿错了层
        System.out.println(diff > 0
                ? "VERDICT: 两者【不同】⇒ landHeight 用的确实是 FullMorphLand（定稿层）"
                : "VERDICT: 两者【完全相同】⇒ 严重问题：UnifiedWorld 可能仍在用 CombineCandidate");
    }

    private MorphInUseProbe() { }
}
