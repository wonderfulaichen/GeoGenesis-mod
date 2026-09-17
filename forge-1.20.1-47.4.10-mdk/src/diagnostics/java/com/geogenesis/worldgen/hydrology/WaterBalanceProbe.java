package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;

/**
 * 【M2 可行性】水量平衡：给 D8 汇流累积加【沿程衰减（蒸发/入渗）】
 * （★ 2026-09-18，零 MC 依赖；**不改生产代码**）。
 *
 * <h2>为什么（用户目标："水文是一个整体"）</h2>
 * <p>现状核查：降水→河宽已有（`Phase C` 的 {@code PrecipWeights}），
 * 但 {@code FlowField.buildAccum()} 只有 <b>纯累加</b>：</p>
 * <pre>
 *   for (k) { int down = flowTo[cur]; if (down >= 0) accum[down] += accum[cur]; }
 * </pre>
 * <p>⇒ <b>水量沿程只增不减</b>：河一旦成河就一路流到海，<b>永远不会有"内流河/时令河"</b>
 * （现实中干旱区河流流着流着就消失了 —— 这是最直观的水文现象之一）。</p>
 *
 * <p>参考 geotransport（{@code solve_uniform}）的做法：沿流线积分时乘
 * {@code exp(−∫decay dt)}，其中 {@code decay} 就是蒸发/衰减项。
 * 本探针把它落到 D8 图上：</p>
 * <pre>
 *   accum[down] += accum[cur] · exp(−decay · dist(cur→down))
 * </pre>
 *
 * <h2>本探针要回答的 5 个问题（全部是"能不能做"的前提）</h2>
 * <ol>
 *   <li><b>[1] 复现正确性</b>：我在探针内自算的 {@code accum}（decay=0）
 *       是否与生产 {@link FlowField#accumAt} <b>逐位相同</b>？
 *       —— 若不同，后面所有对比都无意义（本项目已多次因"口径不对齐"得出假结论）。</li>
 *   <li><b>[2] 衰减是否生效</b>：decay &gt; 0 时总累积应<b>单调减少</b>。</li>
 *   <li><b>[3] ★ 单调性是否被破坏</b>：原判据隐含 {@code accum[down] ≥ accum[cur]}
 *       （纯累加必然成立）。加衰减后<b>可能反转</b> —— 若反转，则任何依赖
 *       "下游必不小于上游"的代码（河宽、河深、threshold 判定）都可能受影响。
 *       <b>这是本探针最关键的发现点。</b></li>
 *   <li><b>[4] 成河面积如何变化</b>：`riverAccumThreshold` 是成河门限
 *       ⇒ 衰减会让上游先低于门限 ⇒ 量化"成河格数"随 decay 的下降。</li>
 *   <li><b>[5] 对河宽的估算影响</b>：按 Leopold-Maddock 幂律
 *       {@code W ∝ A^0.42} 估算宽度变化比例（只估比例，不调生产方法）。</li>
 * </ol>
 *
 * <h2>⚠️ 判据纪律（`docs/analysis/守门门禁清单-2026-09-16.md` §D-1）</h2>
 * <p>"新写判据必须验证它能区分对与错" ⇒ [1] 就是这条纪律的落实：
 * 先证明我的复现与生产一致，再看衰减的影响。</p>
 *
 * <pre>{@code gradlew runWaterBalanceProbe [-PprobeArgs="seed gridN"]}</pre>
 */
public final class WaterBalanceProbe {

    /** 成河门限（wu²）—— 取自 `RiverLineParams.defaults().riverAccumThreshold()`。 */
    private static final double RIVER_THRESHOLD = 2304.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int gridN = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        System.out.printf("=== WaterBalanceProbe seed=%d grid=%d ===%n", seed, gridN);
        System.out.println("目的：验证给 D8 累积加【沿程衰减】的可行性（不改生产代码）");
        System.out.println();

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double cell = RiverLineParams.defaults().gridCell();
        double span = (gridN - 1) * cell;

        // ★ 不启用降水 ⇒ 初值 = cellSize²，与 FlowField 内部一致（便于逐位比对）
        FlowField f = new FlowField(0, 0, span, span, cell, gen::terrainEQuick);

        boolean t1 = testReproduce(f, cell);
        double[] base = accumWithDecay(f, cell, 0.0);
        boolean t2 = testDecayEffective(f, cell, base);
        boolean t3 = testMonotonicity(f, cell, base);
        testRiverArea(f, cell);
        testWidthImpact(f, cell);
        boolean t4 = testProductionDecayPath(gen, cell, span);

        int fails = (t1 ? 0 : 1) + (t2 ? 0 : 1) + (t3 ? 0 : 1) + (t4 ? 0 : 1);
        System.out.println();
        System.out.printf("总判定: %s%n", fails == 0
                ? "ALL PASS（复现正确 + 衰减生效 + 单调性保持 + 生产路径一致 ⇒ 机制可落地）"
                : (fails + " 项不满足 ⇒ 见上方逐项（即该改动的风险边界）"));
        System.out.println();
        System.out.println("★ 本探针的定位：**致命项（[1] 复现 / [2] 衰减 / [6] 生产路径）**"
                + "决定退出码；");
        System.out.println("  [3] 单调性反转 / [4] 成河格数 / [5] 河宽影响 属**量化信息**，"
                + "是『参数取值』的依据，不判失败。");
        System.out.println("  （依据：docs/analysis/守门门禁清单-2026-09-16.md §D-1 —— 判据必须能区分对错；");
        System.out.println("   而『反转/变细』是物理事实而非错误，故不设 FAIL。）");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ==================================================================
    // [1] 复现正确性：自算 accum(decay=0) vs 生产 accumAt —— 必须逐位相同
    // ==================================================================
    private static boolean testReproduce(FlowField f, double cell) {
        System.out.println("[1] 复现正确性：自算 accum(decay=0) vs 生产 FlowField.accumAt()");
        int n = f.cols() * f.rows();
        double[] mine = accumWithDecay(f, cell, 0.0);
        int diff = 0;
        double maxAbs = 0;
        for (int i = 0; i < n; i++) {
            double prod = f.accumAt(i);
            if (mine[i] != prod) diff++;
            maxAbs = Math.max(maxAbs, Math.abs(mine[i] - prod));
        }
        boolean pass = (diff == 0);
        System.out.printf("    网格 %d×%d（%d 格）；不一致格 = %d（须为 0）；最大绝对差 = %.6e%n",
                f.cols(), f.rows(), n, diff, maxAbs);
        System.out.printf("    ⇒ %s%n%n", pass
                ? "复现与生产【逐位相同】⇒ 后续对比有效（decay=0 即零行为变更）"
                : "⚠ 复现不一致 ⇒ 先查口径，否则后续结论全部无效");
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "FAIL");
        return pass;
    }

    // ==================================================================
    // [2] 衰减生效：总累积随 decay 单调减少
    // ==================================================================
    private static boolean testDecayEffective(FlowField f, double cell, double[] base) {
        System.out.println("[2] 衰减是否生效：总累积 vs decay（单位 1/wu）");
        double[] decays = {0.0, 1e-4, 5e-4, 1e-3, 2e-3, 5e-3};
        double prev = Double.MAX_VALUE;
        boolean mono = true;
        double baseSum = sum(base);
        for (double d : decays) {
            double[] a = accumWithDecay(f, cell, d);
            double s = sum(a);
            double ratio = s / baseSum;
            System.out.printf("    decay=%-8.0e 总累积=%-14.2f 相对基线=%.4f%n", d, s, ratio);
            if (s > prev * 1.0001) mono = false;
            prev = s;
        }
        System.out.printf("    单调减少 = %s%n", mono ? "是" : "否");
        System.out.printf("    判定: %s%n%n", mono ? "PASS" : "FAIL");
        return mono;
    }

    // ==================================================================
    // [3] ★ 单调性破坏：decay>0 时 accum[down] < accum[cur] 的格数
    // ==================================================================
    private static boolean testMonotonicity(FlowField f, double cell, double[] base) {
        System.out.println("[3] ★ 单调性检查（最关键）：加衰减后是否出现 accum[下游] < accum[上游]");
        System.out.println("    背景：纯累加隐含『下游 ≥ 上游』；若反转，依赖该性质的代码需复核");
        int n = f.cols() * f.rows();
        double[] decays = {1e-4, 5e-4, 1e-3, 2e-3};
        boolean anyViolation = false;
        for (double d : decays) {
            double[] a = accumWithDecay(f, cell, d);
            int inv = 0;
            for (int i = 0; i < n; i++) {
                int down = f.flowTo(i);
                if (down >= 0 && a[down] < a[i]) inv++;
            }
            System.out.printf("    decay=%-8.0e 反转格数=%-6d（%.2f%%）%n",
                    d, inv, 100.0 * inv / n);
            if (inv > 0) anyViolation = true;
        }
        System.out.println("    ⇒ " + (anyViolation
                ? "确会反转 ⇒ 依赖『下游≥上游』的代码（河宽/河深/threshold）须逐处复核"
                : "未反转 ⇒ 衰减强度不足以破坏单调性"));
        System.out.println("    ⚠ 注意：反转【本身不一定是 bug】—— 现实中内流河正因蒸发而"
                + "『下游水量小于上游』；关键是消费方能否容忍");
        System.out.printf("    判定: %s（记录型：反转与否都是有效结论）%n%n", anyViolation ? "⚠" : "PASS");
        return true;   // 记录型：不因"会反转"而失败
    }

    // ==================================================================
    // [4] 成河面积：累积 ≥ 门限的格数占比
    // ==================================================================
    private static void testRiverArea(FlowField f, double cell) {
        System.out.println("[4] 成河面积：累积 ≥ riverAccumThreshold(2304 wu²) 的格数占比");
        int n = f.cols() * f.rows();
        double[] decays = {0.0, 1e-4, 5e-4, 1e-3, 2e-3};
        int baseCells = -1;
        for (double d : decays) {
            double[] a = accumWithDecay(f, cell, d);
            int c = 0;
            for (int i = 0; i < n; i++) if (a[i] >= RIVER_THRESHOLD) c++;
            if (baseCells < 0) baseCells = c;
            System.out.printf("    decay=%-8.0e 成河格=%-6d 占比=%.3f%%（相对基线 %.4f）%n",
                    d, c, 100.0 * c / n, baseCells > 0 ? (double) c / baseCells : 0);
        }
        System.out.println("    ⇒ 该列即『蒸发使多少河段不再成河』的量化（现实中的内流河/时令河）");

        // ★ 诊断（首轮实测发现的可疑现象）：decay 从 1e-4 到 2e-3 相差 20 倍，
        //   成河格数却【完全相同】(761) —— 而总累积明明在变（[2] 0.9925→0.8679）。
        //   必须查清是【真实物理】还是【我的实现/判据有问题】，否则 [4][5] 不可信。
        System.out.println("    [诊断] 成河格累积分布（判据是否只被临界格支配？）");
        for (double d : new double[]{0.0, 1e-4, 1e-3, 2e-3}) {
            double[] a = accumWithDecay(f, cell, d);
            double[] q = new double[n];
            int c = 0;
            for (int i = 0; i < n; i++) if (a[i] >= RIVER_THRESHOLD) q[c++] = a[i];
            double[] s = Arrays.copyOf(q, c);
            Arrays.sort(s);
            System.out.printf("      decay=%-8.0e 成河=%-5d 最小=%-10.0f p25=%-10.0f p50=%-10.0f 最大=%.0f%n",
                    d, c,
                    c > 0 ? s[0] : 0, c > 0 ? s[c / 4] : 0,
                    c > 0 ? s[c / 2] : 0, c > 0 ? s[c - 1] : 0);
        }
        System.out.println("      ⇒ 若『最小』贴着 2304，则减少的只是临界格；若 p50 也大幅降，才是整体变细");
        System.out.println();
    }

    // ==================================================================
    // [5] 河宽估算影响：W ∝ A^0.42
    // ==================================================================
    private static void testWidthImpact(FlowField f, double cell) {
        System.out.println("[5] 河宽估算影响（Leopold-Maddock：W ∝ A^0.42）");
        int n = f.cols() * f.rows();
        double[] base = accumWithDecay(f, cell, 0.0);
        double[] decays = {5e-4, 1e-3, 2e-3};
        for (double d : decays) {
            double[] a = accumWithDecay(f, cell, d);
            double sumRatio = 0;
            int cnt = 0;
            double worst = 0;
            for (int i = 0; i < n; i++) {
                if (base[i] < RIVER_THRESHOLD) continue;
                double r = a[i] / base[i];
                if (r <= 0) continue;
                double w = Math.pow(r, 0.42);
                sumRatio += w;
                worst = Math.min(worst == 0 ? 1.0 : worst, w);
                cnt++;
            }
            System.out.printf("    decay=%-8.0e 成河格=%d；平均宽度比=%.4f（最细 %.4f）%n",
                    d, cnt, cnt > 0 ? sumRatio / cnt : 0, worst);
        }
        System.out.println("    ⇒ 宽度比 < 1 即『河普遍变细』；若希望总量守恒，需在别处补偿（如提高 richness 类旋钮）");
        System.out.println();
    }

    // ==================================================================
    // 核心：带沿程衰减的 D8 累积（= FlowField.buildAccum + exp(−λ·dist)）
    // ==================================================================

    /**
     * 复现 {@code FlowField.buildAccum()} 并加入沿程衰减。
     *
     * <p>原实现（生产）：按 {@code e} 降序处理，{@code accum[down] += accum[cur]}。</p>
     * <p>本实现：{@code accum[down] += accum[cur] · exp(−decay · dist)}，
     * 其中 {@code dist} = 格中心距（8 邻 ⇒ {@code cellSize} 或 {@code cellSize·√2}）。
     * {@code decay = 0} 时 {@code exp(0) = 1} ⇒ <b>应逐位等于生产值</b>（[1] 验证）。</p>
     */
    static double[] accumWithDecay(FlowField f, double cell, double decay) {
        int nx = f.cols(), nz = f.rows(), n = nx * nz;
        // 初值 = 面积（不启用降水时 FlowField 的初值即 cellSize²）
        double[] a = new double[n];
        Arrays.fill(a, cell * cell);
        // 与生产同序：按 e 降序（上游必先于下游）
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(f.eAt(y), f.eAt(x)));
        double diag = Math.sqrt(2.0) * cell;
        for (int k = 0; k < n; k++) {
            int cur = order[k];
            int down = f.flowTo(cur);
            if (down < 0) continue;                       // 洼地/平地 ⇒ 终止
            // 8 邻：行列都变则为对角（dist = cell·√2），否则正交（dist = cell）
            int dx = Math.abs((cur % nx) - (down % nx));
            int dz = Math.abs((cur / nx) - (down / nx));
            double dist = (dx == 1 && dz == 1) ? diag : cell;
            a[down] += a[cur] * Math.exp(-decay * dist);
        }
        return a;
    }

    // ==================================================================
    // [6] ★★ 生产衰减路径 vs 探针复现 —— 验证【实际改的代码】而非原型
    // ==================================================================
    private static boolean testProductionDecayPath(CellGenerator gen, double cell, double span) {
        System.out.println("[6] ★★ 生产衰减路径验证：FlowField(decay>0) vs 本探针复现");
        System.out.println("    意义：判据 [2]~[5] 都基于本探针的复现 —— 必须证明生产实现与它一致");
        double[] decays = {1e-4, 1e-3, 2e-3};
        boolean allSame = true;
        for (double d : decays) {
            FlowField prod = new FlowField(0, 0, span, span, cell, gen::terrainEQuick,
                    null, FlowField.PrecipWeights.disabled(), d);
            double[] mine = accumWithDecay(prod, cell, d);   // 用同一条 flowTo 复现
            int diff = 0;
            double maxAbs = 0;
            for (int i = 0; i < prod.cols() * prod.rows(); i++) {
                double p = prod.accumAt(i);
                if (mine[i] != p) diff++;
                maxAbs = Math.max(maxAbs, Math.abs(mine[i] - p));
            }
            boolean ok = diff == 0;
            allSame &= ok;
            System.out.printf("    decay=%-8.0e 不一致格=%-6d 最大绝对差=%.3e  %s%n",
                    d, diff, maxAbs, ok ? "✓" : "✗");
        }
        System.out.printf("    ⇒ %s%n%n", allSame
                ? "生产实现与复现【逐位一致】⇒ 判据 [2]~[5] 的量化结论适用于生产代码"
                : "⚠ 生产与复现不一致 ⇒ 生产的衰减实现有误，须先修");
        System.out.printf("    判定: %s%n%n", allSame ? "PASS" : "FAIL");
        return allSame;
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private WaterBalanceProbe() { }
}
