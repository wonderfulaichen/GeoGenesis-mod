package com.geogenesis.worldgen.terrain;

/**
 * 地形形态探针（★ 2026-09-12，地质系统 Phase T3 配套）：验证"真平顶高原"与"碗形盆地"。
 *
 * <p>背景：改造前 PLATEAU 实为<b>宽频丘陵</b>（仅放宽频率，无平顶无崖线），
 * BASIN 仅<b>噪声取反</b>（无沉降中心概念）。本探针量化两者的形态特征：
 * <ol>
 *   <li><b>PLATEAU 真平顶</b>：阶地化后"台面"占比应显著上升
 *       （相邻采样点差值≈0 的比例）——台面平是平顶的直接度量</li>
 *   <li><b>BASIN 碗形</b>：值域下限为 {@code basinBase}；"平阔盆底"（低值区）应占多数</li>
 *   <li>确定性、值域合法</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runTerrainShapeProbe [seed]}</p>
 */
public final class TerrainShapeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== TerrainShapeProbe seed=%d ===%n", seed);
        TerrainParams tp = TerrainParams.defaults();

        TypeNoiseProvider withT = new TypeNoiseProvider(tp.beltReliefAmp(), tp.basinBase());
        withT.seed(seed);

        System.out.printf("参数: basinBase=%.3f%n", tp.basinBase());

        // ================= [1] PLATEAU 真平顶：台顶应比台缘平 =================
        //   "平顶"的本质 = 高值段（台顶）的地形梯度显著小于低值段（台缘）。
        //   直接度量该性质，无需 A/B，也无需依赖具体实现手法（幂压缩 / 阶地 / 其它）。
        //
        //   ★ 判据设计两轮教训：
        //   ① 初版统计 |Δv| < 1e-4（要求"完全相同"）过严——噪声本身 Δv≈0.01，无法区分；
        //   ② 次版用"中位数下降"也不本质——它度量整体变平，而非"顶部比边缘平"。
        final int CAP = 60000;
        double[] gHigh = new double[CAP], gLow = new double[CAP];
        int nH = 0, nL = 0;
        double minP = 1e9, maxP = -1e9;
        for (double z = -6000; z <= 6000; z += 1511) {
            double prev = Double.NaN;
            for (double x = -6000; x <= 6000; x += 2.0) {
                double v = withT.computeNoise(TerrainClass.PLATEAU, x, z);
                minP = Math.min(minP, v);
                maxP = Math.max(maxP, v);
                if (!Double.isNaN(prev)) {
                    double g = Math.abs(v - prev) / 2.0;   // 每 wu 梯度
                    double m = (v + prev) * 0.5;
                    if (m > 0.75 && nH < CAP) gHigh[nH++] = g;        // 台顶段
                    else if (m < 0.30 && nL < CAP) gLow[nL++] = g;    // 台缘段
                }
                prev = v;
            }
        }
        double mH = 0, mL = 0;
        for (int i = 0; i < nH; i++) mH += gHigh[i];
        for (int i = 0; i < nL; i++) mL += gLow[i];
        mH /= Math.max(1, nH);
        mL /= Math.max(1, nL);
        // 判据：台顶平均梯度显著小于台缘（<0.65×）
        boolean pass1 = nH > 100 && nL > 100 && mH < mL * 0.65;
        System.out.printf("[1] PLATEAU 台顶 vs 台缘梯度: 台顶(n=%d)=%.5f 台缘(n=%d)=%.5f 比值=%.2f× %s%n",
            nH, mH, nL, mL, mL > 0 ? mH / mL : 0, pass1 ? "PASS" : "FAIL");
        System.out.println("    要求: 台顶梯度 < 0.65×台缘（平顶 = 顶部明显比边缘平缓）");

        boolean pass1b = minP >= -1e-9 && maxP <= 1.0 + 1e-9;
        System.out.printf("[1b] PLATEAU 值域: [%.4f, %.4f] 应在 [0,1]内 %s%n",
            minP, maxP, pass1b ? "PASS" : "FAIL");

        // ================= [2] BASIN 碗形：平阔盆底 =================
        //   碗形 (1-s)^p (p>1) 使低值区（盆底）占多数。统计 <0.2 的占比。
        //   对照：改造前是"噪声取反"，值近似均匀分布（低值区占比应低得多）。
        int lowB = 0, nB = 0;
        double minB = 1e9, maxB = -1e9;
        for (double z = -8000; z <= 8000; z += 257) {
            for (double x = -8000; x <= 8000; x += 263) {
                double v = withT.computeNoise(TerrainClass.BASIN, x, z);
                minB = Math.min(minB, v);
                maxB = Math.max(maxB, v);
                nB++;
                if (v < 0.2) lowB++;
            }
        }
        double lowRatio = 100.0 * lowB / nB;
        boolean pass2 = lowRatio > 45.0;
        System.out.printf("[2] BASIN 平阔盆底(<0.2)占比=%.1f%% (要求>45%%) %s%n",
            lowRatio, pass2 ? "PASS" : "FAIL");
        boolean pass2b = minB >= tp.basinBase() - 1e-9 && maxB <= 0.6 + 1e-9;
        System.out.printf("[2b] BASIN 值域: [%.4f, %.4f] 应在 [basinBase=%.3f, 0.6]内 %s%n",
            minB, maxB, tp.basinBase(), pass2b ? "PASS" : "FAIL");

        // ================= [3] 确定性 =================
        TypeNoiseProvider again = new TypeNoiseProvider(tp.beltReliefAmp(), tp.basinBase());
        again.seed(seed);
        int bad = 0;
        for (int i = 0; i < 300; i++) {
            double x = i * 271.0 - 30000, z = i * 173.0 - 20000;
            if (Double.compare(withT.computeNoise(TerrainClass.PLATEAU, x, z),
                               again.computeNoise(TerrainClass.PLATEAU, x, z)) != 0) bad++;
            if (Double.compare(withT.computeNoise(TerrainClass.BASIN, x, z),
                               again.computeNoise(TerrainClass.BASIN, x, z)) != 0) bad++;
        }
        boolean pass3 = bad == 0;
        System.out.printf("[3] 确定性(n=600): 不一致=%d %s%n", bad, pass3 ? "PASS" : "FAIL");

        boolean all = pass1 && pass1b && pass2 && pass2b && pass3;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
