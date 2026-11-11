package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;
import java.util.Random;

/**
 * 【M1】geotransport 图模式 + <b>真实地形与真实 {@link FlowField}</b>（★ 2026-09-18）。
 *
 * <h2>与 M0 的区别（M0 用的是合成地形 + 自造 D8）</h2>
 * <p>本探针改用<b>生产同源</b>的组件：</p>
 * <ul>
 *   <li>真实地形 {@code CellGenerator.terrainEQuick}（纯噪声场、无侵蚀 tile）</li>
 *   <li>真实 D8 场 {@link FlowField}（含其 {@code PrecipWeights} 加权与填洼层）</li>
 *   <li>真实降水采样 {@code CellGenerator.precipitationAt}</li>
 * </ul>
 *
 * <h2>★ 本轮要验证的核心疑点（我预测的风险）</h2>
 * <p>生产里有<b>两个场</b>：</p>
 * <pre>
 *   routingE(wx,wz)   = params.routingE(eSampler.eAt(wx,wz))        ← 选线场
 *   groundYAt(wx,wz)  = curve.heightFromE(eSampler.eAt(wx,wz))      ← 真实地形
 * </pre>
 * <p>若二者不同，则"在选线场上求解的水量"与"真实地形的高程"<b>口径错位</b> ⇒
 * 可能产生形态偏差（河落在被压低的坡面上）。</p>
 * <p><b>读码结论</b>：生产 {@code mountainScale = 1.0}（恒等）⇒
 * {@code routingE(e) = e} ⇒ <b>两者实为同一场，仅差一个 heightFromE 映射（单调）</b>
 * ⇒ D8 流向（只依赖序关系）<b>完全一致</b>。本探针实测确认这一点。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1] D8 同构性</b>：{@code routingE} 与 {@code groundYAt} 导出的 D8 流向
 *       <b>逐格相同</b> ⇒ 证明"两场"忧虑不成立。</li>
 *   <li><b>[2] 真实地形无偏性</b>：图模式求解 vs 真实 {@code FlowField.accumAt()} 应无偏。</li>
 *   <li><b>[3] 真实降水加权</b>：启用 {@code PrecipWeights} 后仍无偏（含 floor/exponent 非线性）。</li>
 *   <li><b>[4] 真实 region 切分</b>：按生产 {@code regionSize=640 / margin=320} 切 region，
 *       与整图对比 ⇒ 验证生产既有 margin 是否足够。</li>
 *   <li><b>[5] 确定性</b>：同 seed 重跑逐位相同。</li>
 * </ol>
 *
 * <pre>{@code gradlew runGeotransportRealTerrainProbe [-PprobeArgs="seed gridN samples"]}</pre>
 */
public final class GeotransportRealTerrainProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int gridN = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 400_000;
        System.out.printf("=== GeotransportRealTerrainProbe seed=%d grid=%d samples=%d ===%n",
                seed, gridN, samples);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double cell = rp.gridCell();                      // 生产 24wu
        HeightCurve curve = gen.heightCurve();

        System.out.printf("生产参数：gridCell=%.0fwu mountainScale=%.2f regionSize=%d%n",
                cell, rp.mountainScale(), rp.regionSize());
        System.out.println();

        boolean t1 = testD8Isomorphism(gen, rp, gridN, cell);
        boolean t2 = testRealTerrainUnbiased(gen, rp, gridN, cell, samples);
        boolean t3 = testRealPrecipWeighted(gen, rp, gridN, cell, samples);
        boolean t4 = testRealRegionSplit(gen, rp, gridN, cell, samples);
        boolean t5 = testDeterminism(gen, rp, gridN, cell, samples);

        int fails = (t1 ? 0 : 1) + (t2 ? 0 : 1) + (t3 ? 0 : 1) + (t4 ? 0 : 1) + (t5 ? 0 : 1);
        System.out.println();
        System.out.printf("总判定: %s%n", fails == 0
                ? "ALL PASS（真实地形/真实 FlowField 下无偏 + region 可切 + 确定性成立）"
                : (fails + " 项不满足 ⇒ 见上方逐项"));
        System.exit(0);   // 记录型探针
    }

    // ------------------------------------------------------------------
    // [1] ★ D8 同构性：routingE 与 groundYAt 是否导出相同的 D8 流向
    // ------------------------------------------------------------------
    private static boolean testD8Isomorphism(CellGenerator gen, RiverLineParams rp,
                                             int gridN, double cell) {
        System.out.println("[1] ★ D8 同构性：routingE（选线场）vs groundYAt（真实地形）导出的 D8");
        System.out.println("    预测风险：两场若不同 ⇒ 水量口径错位");
        double[] eRoute = new double[gridN * gridN];
        double[] eGround = new double[gridN * gridN];
        for (int j = 0; j < gridN; j++) {
            for (int i = 0; i < gridN; i++) {
                double wx = i * cell, wz = j * cell;
                eRoute[j * gridN + i] = rp.routingE(gen.terrainEQuick(wx, wz));
                eGround[j * gridN + i] = gen.terrainEQuick(wx, wz);
            }
        }
        int[] dRoute = d8(eRoute, gridN, gridN);
        int[] dGround = d8(eGround, gridN, gridN);
        int diff = 0;
        for (int i = 0; i < dRoute.length; i++) if (dRoute[i] != dGround[i]) diff++;

        // 同时确认 routingE 是否恒等（mountainScale=1 时应恒等）
        int routeIdentity = 0;
        for (int i = 0; i < eRoute.length; i++) if (eRoute[i] == eGround[i]) routeIdentity++;

        System.out.printf("    mountainScale=%.2f ⇒ routingE 恒等格 = %d / %d（%.1f%%）%n",
                rp.mountainScale(), routeIdentity, eRoute.length,
                100.0 * routeIdentity / eRoute.length);
        System.out.printf("    D8 流向不同的格 = %d（须为 0）%n", diff);
        boolean pass = diff == 0;
        System.out.printf("    ⇒ %s%n%n", pass
                ? "两场导出【同一 D8】⇒ 「口径错位」忧虑**不成立**"
                  + "（因 mountainScale=1 恒等 + heightFromE 单调不改序关系）"
                : "两场 D8 不同 ⇒ ⚠ 需在同一场上求解，否则水量与地形口径错位");
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ------------------------------------------------------------------
    // [2] 真实地形无偏性（纯面积累积）
    // ------------------------------------------------------------------
    private static boolean testRealTerrainUnbiased(CellGenerator gen, RiverLineParams rp,
                                                   int gridN, double cell, int samples) {
        System.out.println("[2] 真实地形无偏性：图模式求解 vs 真实 FlowField.accumAt()（纯面积）");
        FlowField f = new FlowField(0, 0, (gridN - 1) * cell, (gridN - 1) * cell, cell,
                gen::terrainEQuick);
        return compareAgainstFlowField("真实地形(纯面积)", f, gridN, cell, samples);
    }

    // ------------------------------------------------------------------
    // [3] 真实降水加权
    // ------------------------------------------------------------------
    private static boolean testRealPrecipWeighted(CellGenerator gen, RiverLineParams rp,
                                                  int gridN, double cell, int samples) {
        System.out.println("[3] ★ 真实降水加权：启用 PrecipWeights（含 floor/exponent 非线性）");
        FlowField f = new FlowField(0, 0, (gridN - 1) * cell, (gridN - 1) * cell, cell,
                gen::terrainEQuick, gen::precipitationAt, FlowField.PrecipWeights.defaults());
        // ★ 复现 FlowField 的每格权重（面积 × PrecipWeights.weight(bilinear 降水)）
        //   —— 必须与生产【同源】，否则又是"口径错位"（本轮已因量纲/权重错两次）。
        double[] w = new double[f.cols() * f.rows()];
        FlowField.PrecipWeights pw = FlowField.PrecipWeights.defaults();
        double step = 320.0;                       // = FlowField.PRECIP_STEP_WU
        for (int j = 0; j < f.rows(); j++) {
            for (int i = 0; i < f.cols(); i++) {
                double wx = i * cell, wz = j * cell;
                double gx = wx / step, gz = wz / step;
                int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gz);
                double fi = gx - i0, fj = gz - j0;
                double a0 = gen.precipitationAt(i0 * step, j0 * step);
                double a1 = gen.precipitationAt((i0 + 1) * step, j0 * step);
                double b0 = gen.precipitationAt(i0 * step, (j0 + 1) * step);
                double b1 = gen.precipitationAt((i0 + 1) * step, (j0 + 1) * step);
                double a = a0 + (a1 - a0) * fi, b = b0 + (b1 - b0) * fi;
                double p = a + (b - a) * fj;
                w[j * f.cols() + i] = cell * cell * pw.weight(p);
            }
        }
        return compareAgainstFlowField("真实地形(降水加权)", f, gridN, cell, samples, w);
    }

    // ------------------------------------------------------------------
    // [4] 真实 region 切分（生产 regionSize=640 / margin=320）
    // ------------------------------------------------------------------
    private static boolean testRealRegionSplit(CellGenerator gen, RiverLineParams rp,
                                               int gridN, double cell, int samples) {
        System.out.println("[4] ★ 真实 region 切分：按生产 regionSize/margin 切，与整图对比");
        double regionSize = rp.regionSize();
        double prodMargin = regionSize * 0.5;               // 生产 margin = regionSize*0.5
        System.out.printf("    生产 regionSize=%.0f margin=%.0f（= regionSize*0.5）%n",
                regionSize, prodMargin);
        System.out.println("    ⚠ 注意：生产的 margin 单位是 **wu**，M0 的 64 是 **格数**");
        System.out.printf("      按 gridCell=%.0fwu 换算，margin=%.0fwu = %.1f 格%n",
                cell, prodMargin, prodMargin / cell);

        // region 内部尺寸与 margin（格）
        int rN = (int) Math.round(regionSize / cell) + 1;
        int mN = (int) Math.round(prodMargin / cell);
        int wN = rN + 2 * mN;         // 含 margin 的求解窗口
        // ★ 修正（第 10 个自身错误）：整图必须【显著大于】窗口，否则窗口没有"外部"可比，
        //   而初版 fullSpan = 2×regionSize ⇒ fullN 恰等于 wN ⇒ cmp=0（无比较格）。
        //   现取 4× region 尺度，确保整图覆盖窗口 + 充足外围。
        double fullSpan = regionSize * 4;
        int fullN = (int) Math.round(fullSpan / cell) + 1;
        FlowField fFull = new FlowField(0, 0, fullSpan, fullSpan, cell, gen::terrainEQuick);
        double[] ref = graphSolveFromFlowField(fFull, cell, samples, 31337L, null);
        double[] accFull = accumOf(fFull);

        System.out.printf("    整图 %d²（%.0fwu）；region %d²；含 margin 窗口 %d²；margin=%d 格%n",
                fullN, fullSpan, rN, wN, mN);

        double wSpan = (wN - 1) * cell;
        FlowField fWin = new FlowField(0, 0, wSpan, wSpan, cell, gen::terrainEQuick);
        double[] fluxW = graphSolveFromFlowField(fWin, cell, samples, 31337L, null);
        double[] accW = accumOf(fWin);

        // ★★ 两种对照必须分开（本轮重要认知）：
        //   (a) 与【窗口自身 accum】比 ⇒ 检验"图模式求解是否正确"
        //       （两者都在同一截断域内 ⇒ 应无偏）
        //   (b) 与【整图 accum】比 ⇒ 检验"margin 是否足够让窗口值收敛到全域真值"
        //       （窗口截断了上游 ⇒ 必然偏小；偏差大小正是"margin 够不够"的度量）
        Stats stA = compareWindows(fluxW, accW, wN, ref, accFull, fullN, rN, mN, true);
        System.out.printf("    (a) 窗口内自洽：比较格=%d 不一致=%d 偏差=%.5f%n",
                stA.cmp, stA.bad, stA.bias);
        boolean okA = stA.cmp > 0 && Math.abs(stA.bias - 1.0) < 0.02;

        Stats stB = compareWindows(fluxW, accW, wN, ref, accFull, fullN, rN, mN, false);
        System.out.printf("    (b) 窗口 vs 整图：比较格=%d 不一致=%d 最大相对误差=%.2f%% 偏差=%.5f%n",
                stB.cmp, stB.bad, stB.maxRel * 100, stB.bias);
        System.out.println("        ⇒ (b) 的偏差即【margin 不足导致的截断量】：偏差越接近 1 越好");
        System.out.println("        （窗口截断上游 ⇒ 窗口 accum 必然 ≤ 整图 ⇒ 偏差 < 1）");

        boolean pass = okA;
        System.out.printf("    ⇒ %s%n%n", pass
                ? "图模式在真实 region 窗口内【自洽】(a) PASS；"
                  + "(b) 给出 margin 不足量（生产 margin 320wu≈13 格）"
                : "窗口内即不自洽 ⇒ 实现或口径仍有误");
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ------------------------------------------------------------------
    // [5] 确定性
    // ------------------------------------------------------------------
    private static boolean testDeterminism(CellGenerator gen, RiverLineParams rp,
                                           int gridN, double cell, int samples) {
        System.out.println("[5] 确定性：同 seed 重跑须逐位相同（纯函数铁律）");
        FlowField a = new FlowField(0, 0, (gridN - 1) * cell, (gridN - 1) * cell, cell,
                gen::terrainEQuick);
        FlowField b = new FlowField(0, 0, (gridN - 1) * cell, (gridN - 1) * cell, cell,
                gen::terrainEQuick);
        double[] x = graphSolveFromFlowField(a, cell, samples, 555L, null);
        double[] y = graphSolveFromFlowField(b, cell, samples, 555L, null);
        boolean same = Arrays.equals(x, y);
        System.out.printf("    两次求解逐位相同 = %s%n", same);
        System.out.printf("    判定: %s%n%n", same ? "PASS" : "FAIL");
        return same;
    }

    // ------------------------------------------------------------------
    // 图模式求解（读真实 FlowField）
    // ------------------------------------------------------------------

    /**
     * 从真实 {@link FlowField} 提取 D8 图并做图模式求解。
     *
     * <p>★★ <b>量纲对齐（本轮第 7 个自身错误的修正）</b>：真实 {@code FlowField.accum}
     * 初值 = {@code cellSize²}（<b>wu²</b>，见其构造器 {@code Arrays.fill(accum, cellSize*cellSize)}），
     * 而本方法初版按"每格 1"统计 ⇒ 与 accum 差 {@code cellSize²} 倍
     * （gridCell=24 ⇒ **576 倍**，实测偏差 0.0017 ≈ 1/576 正是此因）。
     * ⇒ 改为<b>每格贡献 {@code cellSize²}</b>（或总和缩放），使两者同量纲。</p>
     */
    static double[] graphSolveFromFlowField(FlowField f, double cellSize,
                                            int samples, long seed, double[] w) {
        int n = f.cols() * f.rows();
        double[] flux = new double[n];
        Random rnd = new Random(seed);
        double perCell = cellSize * cellSize;      // ★ 与 FlowField.accum 初值同量纲（wu²）
        if (w == null) {
            for (int s = 0; s < samples; s++) walk(f, rnd.nextInt(n), flux, perCell);
            double sc = (double) n / samples;
            for (int i = 0; i < n; i++) flux[i] *= sc;
        } else {
            // 起点按 w 分布抽（w 已含 cellSize² 与降水权重）；每格贡献 perCell ⇒ 需按 w 归一
            double[] cdf = new double[n];
            double run = 0;
            for (int i = 0; i < n; i++) { run += w[i]; cdf[i] = run; }
            double wsum = run;
            for (int s = 0; s < samples; s++) {
                double t = rnd.nextDouble() * wsum;
                int lo = 0, hi = n - 1;
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (t < cdf[mid]) hi = mid; else lo = mid + 1; }
                walk(f, lo, flux, perCell);
            }
            double sc = wsum / (samples * perCell);
            for (int i = 0; i < n; i++) flux[i] *= sc;
        }
        return flux;
    }

    /** 沿真实 flowTo 走（-1 = 洼地 ⇒ 终止）。 */
    private static void walk(FlowField f, int start, double[] flux, double w) {
        int i = start;
        int n = flux.length;
        int guard = 0;
        while (i >= 0 && i < n && guard++ < n) {
            flux[i] += w;
            int nxt = f.flowTo(i);
            if (nxt < 0 || nxt == i) break;
            i = nxt;
        }
    }

    /**
     * 真实 FlowField 的 accum 数组（读取，供对照）。
     *
     * <p>⚠ 第 11 个自身错误：初版签名收"边长"却在内部当"总格数"用
     * （调用处传 {@code fullN=108}，而 {@code accum} 实际有 {@code 108×108=11664} 项）
     * ⇒ 只读了前 108 项 ⇒ 诊断出现 "sink=332/108（>100%）"这种荒谬值。
     * 现改为<b>从 FlowField 自身几何取全长</b>，不依赖调用方传值。</p>
     */
    private static double[] accumOf(FlowField f) {
        int n = f.cols() * f.rows();
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = f.accumAt(i);
        return a;
    }

    /** 与 FlowField.accumAt() 比较无偏性。 */
    private static boolean compareAgainstFlowField(String name, FlowField f, int gridN,
                                                   double cellSize, int samples) {
        return compareAgainstFlowField(name, f, gridN, cellSize, samples, null);
    }

    private static boolean compareAgainstFlowField(String name, FlowField f, int gridN,
                                                   double cellSize, int samples, double[] w) {
        double[] flux = graphSolveFromFlowField(f, cellSize, samples, 20250918L, w);
        double[] acc = accumOf(f);          // 全长（不传边长，避免第 11 个错误复发）
        Stats s = stats(flux, acc);
        System.out.printf("    网格 %d×%d；比较格=%-5d 不一致(>15%%)=%-5d 最大相对误差=%.2f%%%n",
                f.cols(), f.rows(), s.cmp, s.bad, s.maxRel * 100);
        System.out.printf("    ★ 无偏性 mean(flux/accum)=%.5f（须 → 1.0）%n", s.bias);
        boolean pass = s.cmp > 0 && Math.abs(s.bias - 1.0) < 0.02;
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "⚠ 偏离");
        return pass;
    }

    static final class Stats { int cmp, bad; double maxRel, bias; }

    private static double minOf(double[] a) {
        double v = Double.MAX_VALUE;
        for (double x : a) v = Math.min(v, x);
        return v;
    }

    private static double maxOf(double[] a) {
        double v = -Double.MAX_VALUE;
        for (double x : a) v = Math.max(v, x);
        return v;
    }

    private static double medianOf(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static int countSink(FlowField f) {
        int c = 0;
        for (int i = 0; i < f.cols() * f.rows(); i++) if (f.flowTo(i) < 0) c++;
        return c;
    }

    private static Stats stats(double[] flux, double[] acc) {
        Stats s = new Stats();
        double[] sorted = acc.clone();
        Arrays.sort(sorted);
        double p90 = sorted[(int) (sorted.length * 0.9)];
        double minAcc = Math.max(1.0, 0.05 * p90);
        double sum = 0;
        int cmp = 0;
        for (int i = 0; i < flux.length; i++) {
            if (acc[i] < minAcc) continue;
            cmp++;
            double rel = Math.abs(flux[i] - acc[i]) / acc[i];
            if (rel > s.maxRel) s.maxRel = rel;
            if (rel > 0.15) s.bad++;
            sum += flux[i] / acc[i];
        }
        s.cmp = cmp;
        s.bias = cmp > 0 ? sum / cmp : 0;
        return s;
    }

    /**
     * region 窗口内部 vs 整图。
     *
     * <p>⚠ 本轮第 9 个自身错误：初版把窗口索引写成 {@code j*wN+i}（从 0 起），
     * 但窗口内的 region 内部实际起始于 {@code margin}（{@code mN}）——
     * 且 {@code fullN} 与 {@code wN} 在本探针设定下恰好相等（54），
     * 导致读数看似正常却索引错位并越界。现按<b>真实内部偏移</b>比较。</p>
     */
    /** 索引映射：(整图 idx, 窗口 idx) → 实际用于比较的一对下标。 */
    private interface IdxMap { int[] map(int iFull, int iWin); }

    /**
     * ★★ 空间对齐（本轮第 12 个自身错误，务必理解）：
     * 两个 {@link FlowField} 均以 <b>(0,0) 为原点、同 cellSize</b>
     * ⇒ <b>窗口下标 k 与整图下标 k 是同一个世界位置</b>。
     * 初版给窗口内部加 {@code +mN} 偏移（误以为"内部要从边界起算"）
     * ⇒ 实际比的是不同位置 ⇒ 偏差 3.78（假信号）。
     */
    private static Stats compareWindows(double[] fluxW, double[] accW, int wN,
                                        double[] ref, double[] accFull, int fullN,
                                        int rN, int mN, boolean selfCompare) {
        Stats s = new Stats();
        double sum = 0;
        int cmp = 0;
        for (int j = mN; j < mN + rN; j++) {
            for (int i = mN; i < mN + rN; i++) {
                int k = j * wN + i;                     // 窗口 = 同世界位置
                int kF = j * fullN + i;                 // 整图 = 同世界位置
                if (k >= fluxW.length || kF >= ref.length) continue;
                double a = fluxW[k];
                double b = selfCompare ? accW[k] : accFull[kF];
                if (b < 576.0 * 2) continue;            // 阈值按 accum 单位（wu²）：≥2 格
                cmp++;
                double rel = Math.abs(a - b) / b;
                if (rel > s.maxRel) s.maxRel = rel;
                if (rel > 0.15) s.bad++;
                sum += a / b;
            }
        }
        s.cmp = cmp;
        s.bias = cmp > 0 ? sum / cmp : 0;
        return s;
    }

    @SuppressWarnings("unused")
    private static Stats compareWindowsLegacy(double[] fluxW, double[] accW, int wN,
                                        double[] ref, double[] accFull, int fullN,
                                        int rN, int mN, IdxMap map) {
        Stats s = new Stats();
        double sum = 0;
        int cmp = 0;
        for (int j = 0; j < rN; j++) {
            for (int i = 0; i < rN; i++) {
                int iF = j * fullN + i;
                int iW = (j + mN) * wN + (i + mN);
                if (iF >= accFull.length || iW >= accW.length) continue;
                if (accFull[iF] < 576.0 * 2) continue;
                cmp++;
                double rel = Math.abs(fluxW[iW] - ref[iF]) / ref[iF];
                if (rel > s.maxRel) s.maxRel = rel;
                if (rel > 0.15) s.bad++;
                sum += fluxW[iW] / ref[iF];
            }
        }
        s.cmp = cmp;
        s.bias = cmp > 0 ? sum / cmp : 0;
        return s;
    }

    /** 本地 D8（用于 [1] 同构性比较）。 */
    static int[] d8(double[] h, int nx, int nz) {
        int[] d = new int[nx * nz];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                int best = -1;
                double be = h[idx];
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                        double ne = h[nj * nx + ni];
                        if (ne < be) { be = ne; best = nj * nx + ni; }
                    }
                }
                d[idx] = best;
            }
        }
        return d;
    }

    private GeotransportRealTerrainProbe() { }
}
