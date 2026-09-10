package com.geogenesis.worldgen.climate;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * {@link PrecipField} / {@link LatPrecipProfile}（Phase B）验证探针（2026-09-11）。
 *
 * <p>验证：① <b>合成理想山脊</b>上的地形雨/雨影/焚风（物性单元测试）；
 * ② 纬度降水廓线形状；③ 确定性；④ 真实地形上的统计与非平凡性；
 * ⑤ 端到端（{@link CellGenerator} 的 {@code Cell.precipitation} 与 Climate 一致）；⑥ 性能。</p>
 *
 * <p>用法：{@code gradlew runPrecipFieldProbe [seed]}</p>
 */
public final class PrecipFieldProbe {

    private static final double LAT_SCALE = 5000.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== PrecipField probe seed=%d ===%n", seed);

        // ================= [1] 合成理想山脊（x=0 处高斯山脊，沿 z 延伸） =================
        // 高度只随 x 变化：h(x) = 60·exp(−x²/(2·40²))
        PrecipField.HeightFn ridge = (x, z) -> 60.0 * Math.exp(-(x * x) / (2.0 * 40.0 * 40.0));
        PrecipField pf = new PrecipField(seed, ridge, LAT_SCALE,
            PrecipField.Params.defaults(), WindField.Params.defaults());

        // lat01=0.5 → 西风带（风朝 +x）→ 西坡(x<0)迎风、东坡(x>0)背风
        double zTest = 0.5 * LAT_SCALE;
        WindField.Wind wAt = WindField.sample(seed, 0.0, zTest, LAT_SCALE, WindField.Params.defaults());
        PrecipField.Mod west = pf.at(-40, zTest);
        PrecipField.Mod east = pf.at(+40, zTest);
        boolean pass1 = west.orographicGain() > 0.05 && west.shadowLoss() < 1e-9
                     && east.orographicGain() < 1e-9 && east.shadowLoss() > 0.05
                     && east.foehnWarm() > 0.0;
        System.out.printf("[1] 理想山脊(lat01=0.5, 风向 %+.2f,%+.2f):%n", wAt.x(), wAt.z());
        System.out.printf("    迎风(西,x=-40): 地形雨=%.3f 雨影=%.3f 焚风=%.2f°C%n",
            west.orographicGain(), west.shadowLoss(), west.foehnWarm());
        System.out.printf("    背风(东,x=+40): 地形雨=%.3f 雨影=%.3f 焚风=%.2f°C%n",
            east.orographicGain(), east.shadowLoss(), east.foehnWarm());
        System.out.println("    要求: 迎风 地形雨>0 且 雨影=0；背风 地形雨=0 且 雨影>0 且 焚风>0  →  "
            + (pass1 ? "PASS" : "FAIL"));

        // 山脊顶部：两者都应≈0（互斥且完备的边界情形）
        PrecipField.Mod crest = pf.at(0, zTest);
        boolean pass1b = crest.orographicGain() < 1e-9 && crest.shadowLoss() < 1e-9;
        System.out.printf("[1b] 山脊顶(x=0): 地形雨=%.3f 雨影=%.3f (应均为 0) %s%n",
            crest.orographicGain(), crest.shadowLoss(), pass1b ? "PASS" : "FAIL");

        // ================= [2] 纬度降水廓线 =================
        double pItcz = LatPrecipProfile.at(0.0);
        double pSub = LatPrecipProfile.at(0.33);
        double pWest = LatPrecipProfile.at(0.55);
        double pPolar = LatPrecipProfile.at(1.0);
        boolean pass2 = pItcz > pWest && pWest > pSub && pItcz > 1.2 && pSub < 0.4;
        System.out.printf("[2] 纬度廓线: ITCZ=%.2f 副热带(0.33)=%.2f 西风带(0.55)=%.2f 极地=%.2f %s%n",
            pItcz, pSub, pWest, pPolar, pass2 ? "PASS" : "FAIL");
        System.out.println("    要求: ITCZ 峰 > 西风带次峰 > 副热带谷");

        // ================= [3] 确定性 =================
        int bad = 0;
        for (int i = 0; i < 200; i++) {
            double x = i * 61 - 6000, z = i * 37 - 3000;
            PrecipField.Mod a = pf.at(x, z);
            PrecipField.Mod b = pf.at(x, z);
            if (a.orographicGain() != b.orographicGain() || a.shadowLoss() != b.shadowLoss()
                    || a.foehnWarm() != b.foehnWarm()) bad++;
        }
        boolean pass3 = bad == 0;
        System.out.printf("[3] 确定性(合成地形, n=200): 不一致=%d %s%n", bad, pass3 ? "PASS" : "FAIL");

        // ================= [4] 真实地形：统计与非平凡性 + 性能 =================
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        PrecipField real = new PrecipField(seed,
            (x, z) -> gen.heightCurve().heightFromE(gen.terrainEQuick(x, z)),
            tp.latitudeScale(), PrecipField.Params.defaults(), WindField.Params.defaults());

        int n = 0, oroHit = 0, shadowHit = 0, foehnHit = 0;
        double sumOro = 0, maxOro = 0, maxShadow = 0, maxFoehn = 0;
        long t0 = System.nanoTime();
        for (double z = -6000; z <= 6000; z += 137) {
            for (double x = -6000; x <= 6000; x += 149) {
                PrecipField.Mod m = real.at(x, z);
                n++;
                sumOro += m.orographicGain();
                maxOro = Math.max(maxOro, m.orographicGain());
                maxShadow = Math.max(maxShadow, m.shadowLoss());
                maxFoehn = Math.max(maxFoehn, m.foehnWarm());
                if (m.orographicGain() > 0.05) oroHit++;
                if (m.shadowLoss() > 0.05) shadowHit++;
                if (m.foehnWarm() > 0.05) foehnHit++;
            }
        }
        long t1 = System.nanoTime();
        boolean pass4 = maxOro > 0.2 && maxShadow > 0.2 && maxFoehn > 0.2
                     && oroHit > 0 && shadowHit > 0 && foehnHit > 0;
        System.out.printf("[4] 真实地形统计(n=%d): 平均地形雨=%.4f 最大=%.3f | 最大雨影=%.3f | 最大焚风=%.2f°C%n",
            n, sumOro / n, maxOro, maxShadow, maxFoehn);
        System.out.printf("    命中占比: 地形雨>0.05 %d(%.1f%%) 雨影>0.05 %d(%.1f%%) 焚风>0.05 %d(%.1f%%)  %s%n",
            oroHit, 100.0 * oroHit / n, shadowHit, 100.0 * shadowHit / n,
            foehnHit, 100.0 * foehnHit / n, pass4 ? "PASS" : "FAIL");
        System.out.printf("    性能: 总 %.1fms / n=%d → 单次 %.1f µs | %s%n",
            (t1 - t0) / 1e6, n, (t1 - t0) / 1e3 / n, real.stats());

        // ================= [5] 端到端：CellGenerator 接入 =================
        int n5 = 0, mism = 0, badRange = 0;
        double sumPrecip = 0, minP = 1e9, maxP = -1e9;
        for (double z = -5000; z <= 5000; z += 211) {
            for (double x = -5000; x <= 5000; x += 223) {
                Cell c = gen.sample(x, z);
                n5++;
                sumPrecip += c.precipitation;
                minP = Math.min(minP, c.precipitation);
                maxP = Math.max(maxP, c.precipitation);
                if (Math.abs(c.precipitation - c.climate.precipitation()) > 1e-12) mism++;
                if (!(c.precipitation >= 0.0 && c.precipitation <= 1.5)) badRange++;
            }
        }
        boolean pass5 = n5 > 0 && mism == 0 && badRange == 0 && maxP > minP;
        System.out.printf("[5] 端到端 n=%d: precip 均值=%.3f 范围=[%.3f, %.3f] | Cell↔Climate 不一致=%d 越界=%d %s%n",
            n5, sumPrecip / n5, minP, maxP, mism, badRange, pass5 ? "PASS" : "FAIL");

        // ================= [6] 降水预览图层（GeoPalette 接入） =================
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        int badDir = 0, n6 = 0;
        for (double z = -5000; z <= 5000; z += 233) {
            for (double x = -5000; x <= 5000; x += 241) {
                Cell c = gen.sample(x, z);
                int rgb = GeoPalette.color(GeoPalette.PreviewLayer.PRECIPITATION,
                    c, (int) x, (int) z, tp.minY(), tp.maxY(), false);
                colors.add(rgb);
                n6++;
                int blue = rgb & 0xFF;                     // 色带内部为 RGB(0xRRGGBB)
                if (c.precipitation > 0.60 && blue < 90) badDir++;        // 高降水应偏蓝
                if (c.precipitation < 0.12 && blue > 150) badDir++;       // 低降水应偏棕
            }
        }
        boolean pass6 = n6 > 0 && colors.size() > 20 && badDir == 0;
        System.out.printf("[6] 降水图层: n=%d 颜色多样性=%d 方向违例=%d %s%n",
            n6, colors.size(), badDir, pass6 ? "PASS" : "FAIL");

        // ================= [7] 降水权重标定（Phase C 用） =================
        FlowField.PrecipWeights pw = FlowField.PrecipWeights.defaults();
        double sumW = 0, minW = 1e9, maxW = -1e9;
        int nw = 0;
        for (double z = -5000; z <= 5000; z += 197) {
            for (double x = -5000; x <= 5000; x += 199) {
                Cell c = gen.sample(x, z);
                if (c.e < 0) continue;                  // 只统计陆地（汇流加权只对陆地有意义）
                double w = pw.weight(c.precipitation);
                sumW += w;
                minW = Math.min(minW, w);
                maxW = Math.max(maxW, w);
                nw++;
            }
        }
        double meanW = nw > 0 ? sumW / nw : 0;
        boolean pass7 = meanW > 0.90 && meanW < 1.10;   // 收紧：确保权重均值真在 1.0 附近（河宽不整体平移）
        System.out.printf("[7] 权重标定: n=%d 均值=%.3f (目标≈1.0) 范围=[%.3f, %.3f] %s%n",
            nw, meanW, minW, maxW, pass7 ? "PASS" : "FAIL");
        if (!pass7) {
            System.out.printf("    → 建议 ref = %.4f（当前 %.4f，exponent=%.2f）%n",
                pw.ref() * Math.pow(meanW, 1.0 / pw.exponent()), pw.ref(), pw.exponent());
        }

        boolean all = pass1 && pass1b && pass2 && pass3 && pass4 && pass5 && pass6 && pass7;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }
}
