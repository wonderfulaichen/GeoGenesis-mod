package com.geogenesis.worldgen.terrain;

/**
 * 地形形状量化诊断：扫描一片区域，按主导类型分桶统计 eLand 的
 * 全局分布(mean/std/min/max) 与局部起伏度(3×3 邻域 eLand std)。
 * <p>
 * 用途：验证"山脉不像山脉、高原不像高原"的根因 —— 若 SplineConfig 内层样条
 * lo==hi（常数区间），混合公式 eLand = blendLo + (blendHi-blendLo)*modulated 中的
 * modulated（承载类型形状噪声）被 (blendHi-blendLo)=0 消去，则各地形类型退化为
 * 类型中心值的加权平均 → 同一类型主导区内部 localStd 应极小（平坦平台），且
 * 山脉/高原的 center 值若接近则两者高度难以区分。
 * <p>
 * 运行：gradlew runShapeProbe            (默认 seed 12345)
 *       gradlew runShapeProbe --args=98765
 */
public final class TerrainShapeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        final int R = 2000;      // 扫描半径（块）
        final int step = 20;     // 采样步长（块）
        final int n = R / step;  // 网格数（100×100 = 10000 点）

        double[][] e = new double[n][n];
        int[][] dom = new int[n][n];
        double[] sum = new double[TerrainClass.COUNT];
        double[] sum2 = new double[TerrainClass.COUNT];
        int[] cnt = new int[TerrainClass.COUNT];
        double[] mn = new double[TerrainClass.COUNT];
        double[] mx = new double[TerrainClass.COUNT];
        for (int t = 0; t < TerrainClass.COUNT; t++) { mn[t] = 1e9; mx[t] = -1e9; }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int wx = (i - n / 2) * step;
                int wz = (j - n / 2) * step;
                Cell c = gen.sample(wx, wz);
                double ev = c.typeWeights != null ? c.eLand : 0.0;
                e[i][j] = ev;
                int d = c.typeWeights != null
                        ? TypeLandShape.dominantFromWeights(c.typeWeights).ordinal() : 0;
                dom[i][j] = d;
                sum[d] += ev; sum2[d] += ev * ev; cnt[d]++;
                if (ev < mn[d]) mn[d] = ev;
                if (ev > mx[d]) mx[d] = ev;
            }
        }

        System.out.println("=== TerrainShapeProbe seed=" + seed
                + " region=" + (2 * R) + "x" + (2 * R) + " step=" + step + " ===");
        System.out.println("全局 eLand 分布 (按主导类型):");
        for (int t = 0; t < TerrainClass.COUNT; t++) {
            if (cnt[t] == 0) {
                System.out.println("  " + TerrainClass.values()[t].name() + " : (无样本)");
                continue;
            }
            double mean = sum[t] / cnt[t];
            double std = Math.sqrt(Math.max(0.0, sum2[t] / cnt[t] - mean * mean));
            System.out.printf("  %-12s n=%6d  mean=%.3f  std=%.3f  min=%.3f  max=%.3f%n",
                    TerrainClass.values()[t].name(), cnt[t], mean, std, mn[t], mx[t]);
        }

        // 局部起伏度：3×3 邻域 eLand std（窗口约 step*2 = 40 块）
        double[] ls = new double[TerrainClass.COUNT];
        int[] lc = new int[TerrainClass.COUNT];
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                double m = 0;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) m += e[i + a][j + b];
                m /= 9.0;
                double v = 0;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) {
                        double d2 = e[i + a][j + b] - m;
                        v += d2 * d2;
                    }
                v = Math.sqrt(v / 9.0);
                int t = dom[i][j];
                ls[t] += v; lc[t]++;
            }
        }
        System.out.println();
        System.out.println("局部起伏度 (3×3 邻域 eLand std, 窗口≈" + (step * 2) + "块):");
        for (int t = 0; t < TerrainClass.COUNT; t++) {
            if (lc[t] == 0) continue;
            System.out.printf("  %-12s localStd=%.4f%n",
                    TerrainClass.values()[t].name(), ls[t] / lc[t]);
        }
        System.out.println();
        System.out.println("判读：localStd 极低(如<0.01≈<4格/40块) = 该类型内部平坦化(无脊线/平顶特征)。");
        System.out.println("      eLand 是 HeightCurve 坐标(0≈海平面63,1≈接近世界顶), 1e≈约 (maxY-63) 格高度差。");
    }
}
