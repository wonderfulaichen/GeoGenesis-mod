package com.geogenesis.worldgen.terrain;

/**
 * Cell 边界断裂精准验证 — 沿 X 轴横穿 cell 边界，统计 typeWeights/eLand 突变是否
 * 集中在 CELL_SPACING(=400) 整数倍处。
 *
 * 运行：gradlew.bat runCellBoundaryProbe
 *
 * 验证假设：SEARCH_RADIUS 过小 + SIGMA=200 时，窗口进出格点在边界瞬间距离较小
 * → 权重不可忽略 → 移出/移入格点类型不同 → typeWeights 在 1 格内跳变，
 * argmax 临界时翻转 dominantType → eLand 突变。
 *
 * 输出两列跳变：
 *  - rawJump：sampleBlend 原始 typeWeights 相邻格最大差（纯 Voronoi 权重场，与 c 无关）
 *  - eLandJump：经 cAffinity 调制 + 样条混合后的 eLand 相邻格最大差
 */
public final class CellBoundaryProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;

        TerrainParams p = TerrainParams.defaults();
        TypeLandShape landShape = new TypeLandShape(p);
        landShape.seed(seed);
        ContinentField continent = new ContinentField(p);
        continent.seed(seed);
        CoastlineField coastline = new CoastlineField(p);
        coastline.seed(seed);
        double continentBias = p.continentBias();

        System.out.println("=== CellBoundaryProbe (seed=" + seed + ") ===");
        System.out.println("沿 X 轴扫描 z=0 直线，统计每相邻格 typeWeights rawJump / eLand jump");
        System.out.println("假设：跳变应集中在 X = 400 整数倍（cell 边界）");
        System.out.println();

        int half = 100; // 每条边界两侧扫描 100 块
        int[] boundaries = {400, 800, 1200, 1600, 2000};

        double globalMaxRaw = 0, globalMaxE = 0;
        int globalMaxRawX = 0, globalMaxEX = 0;

        for (int b : boundaries) {
            System.out.println("--- Cell 边界 X=" + b + " 附近扫描 ---");
            double maxRaw = 0, maxE = 0;
            int rawX = 0, eX = 0;
            String rawType = "";

            TerrainCharacterField.BlendResult prev = null;
            double prevELand = Double.NaN;
            for (int x = b - half; x <= b + half; x++) {
                double wx = x, wz = 0;
                TerrainCharacterField.BlendResult blend = landShape.sampleBlend(wx, wz);
                double c = continent.sample(wx, wz);
                double cBiased = c - continentBias;
                double cEdge = cBiased + coastline.warpDisplacement(wx, wz, cBiased);
                double eLand = landShape.sample(blend, wx, wz, cEdge);

                if (prev != null) {
                    double[] tw1 = prev.typeWeights;
                    double[] tw2 = blend.typeWeights;
                    double maxW = 0;
                    int maxT = -1;
                    for (int t = 0; t < TerrainClass.COUNT; t++) {
                        double d = Math.abs(tw1[t] - tw2[t]);
                        if (d > maxW) { maxW = d; maxT = t; }
                    }
                    double eJump = Math.abs(eLand - prevELand);
                    if (maxW > maxRaw) { maxRaw = maxW; rawX = x; rawType = TerrainClass.values()[maxT].name(); }
                    if (eJump > maxE) { maxE = eJump; eX = x; }
                    if (maxW > globalMaxRaw) { globalMaxRaw = maxW; globalMaxRawX = x; }
                    if (eJump > globalMaxE) { globalMaxE = eJump; globalMaxEX = x; }

                    // 打印显著原始跳变
                    if (maxW > 0.003) {
                        System.out.printf("  X=%d→%d  rawJump=%.4f (%s)  eLandJump=%.4f%n",
                            x - 1, x, maxW, rawType, eJump);
                    }
                }
                prev = blend;
                prevELand = eLand;
            }
            System.out.printf("  边界峰值: maxRawJump=%.4f @X=%d  maxELandJump=%.4f @X=%d%n%n",
                maxRaw, rawX, maxE, eX);
        }

        System.out.println("=== 结论 ===");
        System.out.printf("全局 maxRawJump=%.4f @X=%d (≈%.1f块)  全局 maxELandJump=%.4f @X=%d%n",
            globalMaxRaw, globalMaxRawX, globalMaxRaw * 384, globalMaxE, globalMaxEX);
        System.out.println("rawJump 是纯 Voronoi 权重场相邻格最大差（排除 cAffinity 影响）");
    }
}
