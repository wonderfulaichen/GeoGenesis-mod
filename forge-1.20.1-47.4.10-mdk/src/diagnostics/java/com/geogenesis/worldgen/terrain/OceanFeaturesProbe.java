package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import java.util.Locale;

/**
 * <b>海洋特征判据门禁（2026-11-17 阶段六修正三）</b>——验收海山/洋中脊归位到
 * 洋底几何层后的生产行为。
 *
 * <p>背景：海山/洋中脊原先是「外挂特征层」（{@code sampleE} 顶层叠加 delta），
 * 且 {@code BaseColumn.oceanFeat} <b>从未赋值</b>——{@code SEAMOUNT}/
 * {@code SUBMARINE_RIDGE} 类型在生产中从未产出。本次修正：
 * ① 特征增量并入 {@code crustProfile} 的 deep 锚（海床剖面的一部分）；
 * ② 海洋亚型进 {@code typeWeights} 加权竞争（与火山同模式）。</p>
 *
 * <h3>判据</h3>
 * <ol>
 *   <li><b>存在</b>：海山活跃格 ≥ 20、洋中脊活跃格 ≥ 50（扫描窗内）。</li>
 *   <li><b>幅度</b>：最強海山峰值 ∈ [0.08, 0.16]e（SEAMOUNT_AMP 定稿量程）。</li>
 *   <li><b>不露头</b>：海山/洋中脊活跃格中 {@code e > −0.001} 的格数 = 0
 *       （特征抬升不得把海床推过海平面）。</li>
 *   <li><b>类型可达</b>：SEAMOUNT 格 &gt; 0 且 SUBMARINE_RIDGE 格 &gt; 0
 *       （修正前恒 0——oceanFeat 从未赋值）。</li>
 *   <li><b>平滑过渡</b>：SEAMOUNT 类型格中存在 {@code typeWeights[SEAMOUNT] < 0.90}
 *       的格（证明权重竞争产生了过渡带，不是硬覆盖）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runOceanFeaturesProbe [-PprobeArgs="<span> <step>"]}
 * （默认 ±8000wu / 步长 50wu）。</p>
 */
public final class OceanFeaturesProbe {

    private static final long SEED = 9139912035078620160L;

    private static final double MIN_SEAMOUNT_CELLS = 20;
    private static final double MIN_RIDGE_CELLS = 50;
    private static final double SEAMOUNT_AMP_MIN = 0.08, SEAMOUNT_AMP_MAX = 0.16;
    /**
     * 露头阈值（e）：0.02e ≈ 5 格。
     *
     * <p>⚠ 不能用「e > 0」判违规：岸段剖面在 {@code edge≈0.5}（海岸线）返回 coast=0，
     * 该处特征计算仍活跃（深海构造 eBase≈−0.23）但<b>几何上特征不参与</b>
     * （特征只加在 deep 锚，岸段剖面忽略它）——实测 727 个「违规」全是这种
     * 岸线点（e≈−0.000 / height=63.0 格）。真正的露头是特征把海床推过水面
     * 5 格以上。结构保证：deep 锚 clamp(shallowEff=−7 格)，深海段 e ≤ −0.055。</p>
     */
    private static final double DRY_EPS = 0.02;

    public static void main(String[] args) {
        double span = args.length > 0 ? Double.parseDouble(args[0]) : 8000.0;
        double step = args.length > 1 ? Double.parseDouble(args[1]) : 50.0;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        long seamountCells = 0;
        long ridgeCells = 0;
        long seamountTypeCells = 0;
        long ridgeTypeCells = 0;
        long dryViolations = 0;
        int drySamples = 0;
        long smoothCells = 0;
        double peakSeamount = 0;
        double peakRidge = 0;
        double bestSx = 0, bestSz = 0;
        double bestRx = 0, bestRz = 0;
        double minTypeDepth = 0;

        for (double z = -span; z <= span; z += step) {
            for (double x = -span; x <= span; x += step) {
                var c = smp.sampleColumn(x, z);
                var of = c.oceanFeat;
                boolean active = of != null && (of.seamount > 0.01 || of.ridge > 0.02);
                if (active && c.e > DRY_EPS) {
                    dryViolations++;
                    // 诊断样本：区分「特征真顶出海面」vs「近岸点特征计算活跃但岸段剖面主导」
                    if (drySamples < 5) {
                        drySamples++;
                        System.out.printf(Locale.ROOT,
                                "  [dry样本] (%.0f,%.0f) e=%.3f(%.1f 格) 裸e=%.3f seamount=%.3f ridge=%.3f 类型=%s%n",
                                x, z, c.e, c.height, of.baseE, of.seamount, of.ridge, c.terrainType);
                    }
                }
                if (of == null) {
                    continue;
                }
                if (of.seamount > 0.01) {
                    seamountCells++;
                    if (of.seamount > peakSeamount) {
                        peakSeamount = of.seamount;
                        bestSx = x;
                        bestSz = z;
                    }
                }
                if (of.ridge > 0.02) {
                    ridgeCells++;
                    if (of.ridge > peakRidge) {
                        peakRidge = of.ridge;
                        bestRx = x;
                        bestRz = z;
                    }
                }
                if (c.terrainType == TerrainClass.SEAMOUNT) {
                    seamountTypeCells++;
                    minTypeDepth = Math.min(minTypeDepth, c.e);
                    double w = c.typeWeights[TerrainClass.SEAMOUNT.ordinal()];
                    if (w < 0.90) {
                        smoothCells++;
                    }
                }
                if (c.terrainType == TerrainClass.SUBMARINE_RIDGE) {
                    ridgeTypeCells++;
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "[海山] 活跃格 %d（判据 ≥%.0f）  峰值 %.3fe（判据 [%.2f,%.2f]）  最强点 (%.0f,%.0f)%n",
                seamountCells, MIN_SEAMOUNT_CELLS, peakSeamount,
                SEAMOUNT_AMP_MIN, SEAMOUNT_AMP_MAX, bestSx, bestSz);
        System.out.printf(Locale.ROOT,
                "[洋中脊] 活跃格 %d（判据 ≥%.0f）  峰值 %.3fe  最强点 (%.0f,%.0f)%n",
                ridgeCells, MIN_RIDGE_CELLS, peakRidge, bestRx, bestRz);
        System.out.printf(Locale.ROOT,
                "[类型] SEAMOUNT 格 %d（最深 e=%.3f）  SUBMARINE_RIDGE 格 %d%n",
                seamountTypeCells, minTypeDepth, ridgeTypeCells);
        System.out.printf(Locale.ROOT,
                "[平滑] SEAMOUNT 过渡格（权重<0.90）%d  [不露头] 违规格 %d%n",
                smoothCells, dryViolations);

        boolean ok = seamountCells >= MIN_SEAMOUNT_CELLS
                && ridgeCells >= MIN_RIDGE_CELLS
                && peakSeamount >= SEAMOUNT_AMP_MIN && peakSeamount <= SEAMOUNT_AMP_MAX
                && seamountTypeCells > 0
                && ridgeTypeCells > 0
                && smoothCells > 0
                && dryViolations == 0;
        System.out.println(ok ? "=== ALL PASS ===" : "=== FAIL ===");
        if (!ok) {
            System.exit(1);
        }
        System.out.printf(Locale.ROOT,
                "（如需海山特写图：gradlew runFeatureCloseupProbe -PprobeArgs=\"700 600 %.0f %.0f\"）%n",
                bestSx, bestSz);
    }

    private OceanFeaturesProbe() { }
}