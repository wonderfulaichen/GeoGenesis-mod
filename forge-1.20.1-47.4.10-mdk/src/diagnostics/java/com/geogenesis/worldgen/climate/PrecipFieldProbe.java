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
        // ★ 2026-09-11：必须【按纬度反解 z】而非硬编码 —— 纬度映射改为余弦后，
        //   旧写法 z = 0.5·LAT_SCALE 实际落在 lat01≈0.25（副热带高压边缘），
        //   风带位置随之改变、风向反向，测试会以"风向反了"假失败报警。
        double zTest = Latitude.zForLatitude(0.5, LAT_SCALE);
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
        // ★ 2026-09-11：焚风必须【有上限】。
        //   单位换算（易错，务必留意）：CellGenerator 以 `temp += foehnWarm / 40` 施加
        //   （DEG_C_PER_E_UNIT=40，1 e 单位 = 40 °C）→ **增益的 °C 值在数值上恰等于 foehnWarm**，
        //   故下面对 foehnWarm 的阈值判断即为 °C 判断，**不需要再乘/除 40**。
        boolean foehnBounded = maxFoehn <= 3.5;    // foehnMax 目标 3.0，留 0.5 容差
        boolean pass4 = maxOro > 0.2 && maxShadow > 0.2 && maxFoehn > 0.2
                     && oroHit > 0 && shadowHit > 0 && foehnHit > 0 && foehnBounded;
        System.out.printf("[4] 真实地形统计(n=%d): 平均地形雨=%.4f 最大=%.3f | 最大雨影=%.3f | 最大焚风=%.2f°C %s%n",
            n, sumOro / n, maxOro, maxShadow, maxFoehn,
            foehnBounded ? "" : "← 超上限!");
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

        // ================= [8] Phase D：荒漠是否落在副热带高压带 =================
        //   Phase D 把 Whittaker 的降水轴换成"区域层湿度 × 纬度降水廓线"。
        //   聚合看会相互抵消（副热带变干 + 赤道变湿），必须【按纬度细分】才看得见。
        //   ★ 2026-09-11 采样方式修正：改为【按 lat01 均匀扫描】+ 二分反解 z。
        //   原实现扫固定 z 网格、再按 lat01 分桶 —— 一旦纬度映射改变（|sin| → cos），
        //   同一个桶会采到【完全不同的 z 窗口】（地形/湿度噪声实况不同），
        //   于是"换映射前后同一桶的荒漠率"不可比，会误判成"荒漠带移位"。
        //   改为按 lat01 均匀取点后，每个桶的纬度含义固定，**跨映射可比**。
        final int B = 8;
        final int LAT_STEPS = 80;                 // 每带 10 个纬度采样点
        int[] total = new int[B], desert = new int[B], rain = new int[B];
        for (int i = 0; i < LAT_STEPS; i++) {
            double lat = (i + 0.5) / LAT_STEPS;
            double z = zAtLatitude(lat, LAT_SCALE);
            int b = Math.min(B - 1, (int) (lat * B));
            for (double x = -6000; x <= 6000; x += 199) {
                Cell c = gen.sample(x, z);
                if (c.e < 0) continue;
                total[b]++;
                if (c.biomeType == WhittakerType.DESERT) desert[b]++;
                if (c.biomeType == WhittakerType.TROPICAL_RAINFOREST) rain[b]++;
            }
        }
        System.out.println("[8] Phase D 群区随纬度（每 1/" + B + " 纬度带，只统计陆地）");
        System.out.println("    lat01带     样本    DESERT    RAINFOREST   参考");
        int peakB = -1;
        double peak = -1;
        for (int b = 0; b < B; b++) {
            if (total[b] == 0) continue;
            double dr = 100.0 * desert[b] / total[b];
            double rr = 100.0 * rain[b] / total[b];
            if (dr > peak) { peak = dr; peakB = b; }
            System.out.printf("    %.3f~%.3f  %5d   %6.1f%%   %8.1f%%     %s%n",
                b / (double) B, (b + 1) / (double) B, total[b], dr, rr,
                b == 0 ? "ITCZ 应最湿" : (b >= 2 && b <= 4 ? "副热带 应最干" : ""));
        }
        boolean pass8 = peakB >= 2 && peakB <= 4;
        System.out.printf("    荒漠占比峰值带 = %.3f~%.3f  %s%n",
            peakB / (double) B, (peakB + 1) / (double) B,
            pass8 ? "PASS（落在副热带高压带 2/8~5/8）" : "FAIL（未落在副热带）");

        // ================= [9] 周期性纬度（无限世界无极点） =================
        double sc = tp.latitudeScale();
        double l0 = Latitude.latitude01(0.0, sc);
        double lPole = Latitude.latitude01(sc * Math.PI / 2.0, sc);
        double lBack = Latitude.latitude01(sc * Math.PI, sc);
        // 远处应继续振荡而非饱和：取一段极远的 z，看 lat01 是否仍横跨 0→1
        double farMin = 1e9, farMax = -1e9;
        for (double z = 200000; z < 260000; z += 137) {
            double v = Latitude.latitude01(z, sc);
            farMin = Math.min(farMin, v);
            farMax = Math.max(farMax, v);
        }
        double farSpan = farMax - farMin;
        boolean pass9 = l0 < 1e-6 && lPole > 0.999 && lBack < 1e-6 && farSpan > 0.9;
        System.out.printf("[9] 周期性纬度: lat(0)=%.4f  lat(π/2·scale)=%.4f  lat(π·scale)=%.4f%n",
            l0, lPole, lBack);
        System.out.printf("    远处 z=20万~26万 的 lat01 跨度=%.3f (>0.9 表示仍在振荡、未饱和) %s%n",
            farSpan, pass9 ? "PASS" : "FAIL");
        System.out.printf("    完整气候周期(赤道→北极→赤道→南极→赤道) = %.0f 格%n",
            Latitude.cycleLength(sc));

        // ================= [10] 纬度分带宽度对称性（2026-09-11 用户反馈复查） =================
        //   用户反馈两件事，逐条量化：
        //   (a) 温度图「冷区占比大于热区」→ 检查 z 向 冷:热 宽度比；
        //       中间版本用 |sin(z/scale)|：极值处平坦、赤道处陡峭 → 冷区被拉宽（理论 2:1）。
        //       日照 ∝ cos(纬度角) → 改用 (1−cos(2z/scale))/2 后应为 1:1（真实地球）。
        //   (b) 纬度图「北比南大」→ 检查 lat01 是否为【严格偶函数】（决定上下是否镜像）。
        double sc3 = tp.latitudeScale();
        double cyc = Latitude.cycleLength(sc3);
        int hotOld = 0, coldOld = 0, hotNew = 0, coldNew = 0;
        double maxAsym = 0;
        for (double z = 0; z < cyc; z += 4.0) {
            if (Math.abs(Math.sin(z / sc3)) < 0.5) hotOld++; else coldOld++;
            double nL = Latitude.latitude01(z, sc3);
            if (nL < 0.5) hotNew++; else coldNew++;
            maxAsym = Math.max(maxAsym, Math.abs(nL - Latitude.latitude01(-z, sc3)));
        }
        double rOld = coldOld / (double) Math.max(1, hotOld);
        double rNew = coldNew / (double) Math.max(1, hotNew);
        boolean pass10 = rNew > 0.90 && rNew < 1.10 && maxAsym == 0.0;
        System.out.printf("[10] 冷:热 带宽比: 旧|sin|=%.2f  现cos=%.2f  (真实地球≈1.00) %s%n",
            rOld, rNew, pass10 ? "PASS" : "FAIL");
        System.out.printf("     南北镜像: max|lat01(z)-lat01(-z)|=%.1e (严格偶函数=0) %s%n",
            maxAsym, maxAsym == 0.0 ? "PASS" : "FAIL");
        System.out.printf("     带宽: 赤道→极地=%.0f 格  完整循环=%.0f 格%n",
            Latitude.poleDistance(sc3), cyc);
        System.out.println("     注: 截图中的“北比南大”不是模型不对称 —— lat01 严格偶函数，");
        System.out.println("         而是【视口从 z=0（赤道）开始】把最上一条带切了一半，属取景偏差。");

        boolean all = pass1 && pass1b && pass2 && pass3 && pass4 && pass5 && pass6
                && pass7 && pass8 && pass9 && pass10;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
    }

    /**
     * 反解纬度：在 z ∈ [0, π·scale/2] 内二分求使 {@code Latitude.latitude01(z, scale) ≈ target} 的 z。
     *
     * <p>用二分而非解析反函数 —— 只要纬度映射在该区间单调递增即可，
     * 因此公式变更（如 2026-09-11 的 {@code |sin|} → {@code cos}）时探针无需同步改。</p>
     */
    private static double zAtLatitude(double target, double scale) {
        double lo = 0.0, hi = Math.PI * scale / 2.0;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (Latitude.latitude01(mid, scale) < target) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }
}
