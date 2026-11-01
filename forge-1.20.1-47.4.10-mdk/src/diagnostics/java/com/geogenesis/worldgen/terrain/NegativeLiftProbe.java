package com.geogenesis.worldgen.terrain;

import java.util.Locale;

/**
 * 定位「有海洋特征的海洋点，但 {@code e < cell.eLand}」的 44 个样本。
 *
 * <p>{@link OceanTypeWiringCheck} 的 T2 报了 44 例负抬升（最负 {@code −7.454e-3}）。
 * 本探针逐点打印全部中间量，把机制查清 —— <b>不猜</b>。</p>
 *
 * <h3>可能来源（待证）</h3>
 * <ol>
 *   <li>{@code landFactor} 门控：{@code oceanFeatW = total·(1−landFactor)}，
 *       在 {@code landFactor>0} 处特征被乘小于 1 的因子。但那只是"变小"，不会为负。</li>
 *   <li>海床护栏 {@code smoothMin(oceanFeatW, cap, eps)}：{@code cap = −eLand − 0.02}。
 *       若 {@code cap < 0}（即 {@code eLand > −0.02}，接近海岸线），护栏会把增量压成 0，
 *       甚至若 {@code smoothMin} 实现允许负输出，就会出现"负抬升"。</li>
 *   <li>{@code softCapLandE}：对 {@code e ≤ 0} 只做 {@code max(−1.0, e)}，不产生负值。</li>
 * </ol>
 * <p>本探针直接打印 {@code cap}、{@code eLand}、{@code landFactor}、{@code oceanFeat.total}，
 * 让 ①/②/③ 可被数据直接判定。</p>
 */
public final class NegativeLiftProbe {

    private static final int HALF = 4096;
    private static final int STEP = 32;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        int found = 0;
        System.out.println("=== negative-lift offenders (e < cell.eLand while oceanFeat.total > 0) ===");
        System.out.println("     x      z    terrainType        eLand       e        lift    featTotal   landFactor?  eLand+0.02");
        for (int z = -HALF; z < HALF && found < 60; z += STEP) {
            for (int x = -HALF; x < HALF && found < 60; x += STEP) {
                Cell c = gen.sample(x, z);
                if (c.e >= 0 || !c.terrainType.isOcean()) continue;
                if (c.oceanFeat == null || c.oceanFeat.total <= 0) continue;
                double lift = c.e - c.eLand;
                if (lift >= -5e-3) continue;
                found++;
                // 由 cell 反推门控量（不额外采样，避免与生产路径不一致）
                System.out.printf(Locale.ROOT,
                        "  %6d %6d  %-16s  %+.6f  %+.6f  %+.6f  %+.6f   %+.6f%n",
                        x, z, c.terrainType, c.eLand, c.e, lift, c.oceanFeat.total, c.eLand + 0.02);
            }
        }
        System.out.printf(Locale.ROOT, "offenders printed = %d%n", found);
        System.out.println(found == 0
                ? "NEG-LIFT PROBE: none found"
                : "NEG-LIFT PROBE: 见上表 —— 若 eLand+0.02 > 0 则属海床护栏 cap<0 的边界情形");
    }

    private NegativeLiftProbe() { }
}
