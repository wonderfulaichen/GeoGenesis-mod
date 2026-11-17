package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import java.util.Locale;

/**
 * <b>洋中脊剖面诊断（2026-11-17）</b>——回答「这位置属于地质学正常情况吗」。
 *
 * <p>沿一条东西向剖面采样，打印每个断面的控制量，用于判定「深→浅边界」的归属：</p>
 * <ul>
 *   <li>{@code continent}（大陆性 c）：MorphOceanAssembler 的 edge=(c+1)/2 决定
 *       深海/浅海/岸段三段剖面——若深浅边界与它对齐 ⇒ 是「大陆距离坐标」的产物。</li>
 *   <li>{@code baseE}（CrustField 裸 e）：年龄律热沉降——若边界与它对齐 ⇒ 是
 *       「离脊距离」的正常地质过渡。</li>
 *   <li>{@code ridge}/{@code seamount}：特征分量（门控是否在翼上被截断）。</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runRidgeProfileProbe}（默认 (1530,1230) 东西向 ±500wu）。</p>
 */
public final class RidgeProfileProbe {

    private static final long SEED = 9139912035078620160L;
    private static final double CX = 1530;
    private static final double CZ = 1230;
    private static final double HALF = 500;
    private static final int N = 21;

    public static void main(String[] args) {
        double cx = args.length > 0 ? Double.parseDouble(args[0]) : CX;
        double cz = args.length > 1 ? Double.parseDouble(args[1]) : CZ;
        double half = args.length > 2 ? Double.parseDouble(args[2]) : HALF;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);

        System.out.printf(Locale.ROOT,
                "=== 东西剖面 z=%.0f  x∈[%.0f,%.0f] ===%n", cz, cx - half, cx + half);
        System.out.println("     x      continent   edge   baseE   seamnt   ridge      e   height(格)  type");
        for (int i = 0; i < N; i++) {
            double x = cx - half + 2 * half * i / (N - 1);
            var c = smp.sampleColumn(x, cz);
            double edge = (c.continent + 1.0) * 0.5;
            double baseE = c.oceanFeat == null ? Double.NaN : c.oceanFeat.baseE;
            double seamount = c.oceanFeat == null ? 0 : c.oceanFeat.seamount;
            double ridge = c.oceanFeat == null ? 0 : c.oceanFeat.ridge;
            System.out.printf(Locale.ROOT,
                    "%8.0f  %10.3f  %6.3f  %6.3f  %7.3f  %7.3f  %6.3f  %8.1f  %s%n",
                    x, c.continent, edge, baseE, seamount, ridge, c.e, c.height, c.terrainType);
        }
        System.out.println("判读：深浅边界与 edge 对齐 ⇒ 大陆距离坐标产物（不自然）；");
        System.out.println("      与 baseE 对齐 ⇒ 离脊年龄热沉降（正常地质）。");

        // ---- 2D 网格：看「深→浅边界」的走向（e 与 baseE 并排）----
        System.out.println();
        System.out.println("=== 9x9 网格（step 50wu）  e 矩阵（. 空行） ===");
        double gx = 200, gz = 200;
        for (int j = -4; j <= 4; j++) {
            StringBuilder se = new StringBuilder();
            StringBuilder sb = new StringBuilder();
            for (int i = -4; i <= 4; i++) {
                var c = smp.sampleColumn(cx + i * 50, cz + j * 50);
                double baseE = c.oceanFeat == null ? 0 : c.oceanFeat.baseE;
                se.append(String.format(Locale.ROOT, "%6.3f", c.e));
                sb.append(String.format(Locale.ROOT, "%6.3f", baseE));
            }
            System.out.println(String.format(Locale.ROOT, "z=%+6.0f  e:[%s]", cz + j * 50, se));
            System.out.println(String.format(Locale.ROOT, "         b:[%s]", sb));
        }
        System.out.println("（e 与 baseE 的形状一致 ⇒ 边界来自构造年龄律；某一行/列突变 ⇒ 坐标系伪影）");
    }

    private RidgeProfileProbe() { }
}