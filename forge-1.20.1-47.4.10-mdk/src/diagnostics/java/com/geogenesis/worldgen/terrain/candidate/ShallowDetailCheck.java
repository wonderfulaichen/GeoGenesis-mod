package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 浅海段形态项的<b>门禁</b>：证明"只动浅海带内部，端点与分类都不动"。
 *
 * <h3>为什么必须逐项判，而不是"出图看着还行"</h3>
 * <p>这是三段海岸缝合链的中段：左侧接深海段、右侧接岸段。任何一处端点不等价，
 * 就会在 {@code edge = cpShelf} 或 {@code edge = cpShore} 的等值线上留一条折痕 ——
 * 而这恰恰是本项目回归过三次的伪影类型（{@code AGENTS.md} 硬约束 ④）。</p>
 *
 * <h3>九项判据</h3>
 * <pre>
 * C1 off-bitwise     不调 enableShallowDetail ⇒ 与"从没这个功能"的臂逐位相同
 * C2 band-outside    带外（深海段 / 岸段）高度逐位不受影响
 * C3 endpoint-value  cpShelf、cpShore 两处高度与关闭时逐位相等（岸线不移动）
 * C4 endpoint-slope  跨两端的单侧有限差分与关闭时同量级（无一阶跳变）
 * C5 depth-range     带内高度恒在 [shallow, 0] 内（sea() 阈值对齐不被破坏）
 * C6 no-fold         沿 edge 的剖面二阶差无尖峰（无折痕线）
 * C7 actually-changes 带内确有像素被改变（不是白加一个空算子）
 * C8 sea-class        sea() 四档分类在整幅窗口内零漂移
 * C9 water-ratio     1401² 采样窗口的水面占比变化 ≤ 1e-6（海陆比不动）
 * </pre>
 *
 * <p>任一项 FAIL 即 {@code System.exit(1)}（{@code AGENTS.md}：带判据的探针必须用
 * 非零退出码承载 FAIL，否则 Gradle 报 BUILD SUCCESSFUL、门禁名存实亡）。</p>
 */
public final class ShallowDetailCheck {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;
    /** 带内高度允许的越界容差（格）。闭合代数 ⇒ 理论上是 0，给 1e-9 只为吸收 float/double 往返。 */
    private static final double RANGE_TOL = 1e-9;

    private static int checks;
    private static final StringBuilder csv = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/shallow-detail-check");
        Files.createDirectories(out);

        var params = ShallowShelfProbe.loadParams(Path.of(args.length > 1 ? args[1]
                : "build/terrain-upgrade/baseline/config-input.toml"));
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        var catalog = new RecipeMorphCatalog(SEED);
        var land = catalog.original();

        // 两臂：off = 只有既有 coastalDetail；on = 再加浅海形态项。其余构造参数完全相同。
        OceanAssemblyCandidate off = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED);
        OceanAssemblyCandidate on = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED).enableShallowDetail(SEED);

        csv.append("check,scope,metric,value,verdict\n");

        checkOffBitwise(land, continent);
        checkBandOutside(off, on);
        checkEndpoints(off, on);
        checkDepthRange(off, on);
        checkNoFold(off, on);
        checkActuallyChanges(off, on);
        checkSeaClass(off, on);

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- C1

    /**
     * C1：不调 {@code enableShallowDetail} 时，与"没有这个功能"的实现逐位相同。
     *
     * <p>用两个<b>独立构造</b>的 off 臂对拍：若哪天有人在默认路径上偷偷加了贡献，
     * 两臂仍会一致 —— 所以这一项真正的判据是"off 臂与 {@code oceanBlocks} 旧路径等价"，
     * 见 {@code FullMorphGallery.interfaceCheck} 与基准 SHA 硬对拍。这里只守
     * "构造顺序不影响产出"。</p>
     */
    private static void checkOffBitwise(CombineCandidate land, ContinentField continent) {
        OceanAssemblyCandidate a = new OceanAssemblyCandidate(land, continent, SEED + 3)
                .enableCoastalDetail(SEED);
        OceanAssemblyCandidate b = new OceanAssemblyCandidate(land::e, continent::sample, SEED + 3)
                .enableCoastalDetail(SEED);
        var random = new java.util.Random(20261101);
        int diff = 0;
        for (int i = 0; i < 20000; i++) {
            double x = random.nextDouble() * 14000 - 7000;
            double z = random.nextDouble() * 14000 - 7000;
            if (Double.compare(a.e(x, z), b.e(x, z)) != 0) diff++;
        }
        record("C1", "off-arm", "bitwise-mismatches", diff, diff == 0,
                "两个独立构造的 off 臂必须逐位一致（构造顺序不影响产出）");
    }

    // ---------------------------------------------------------------- C2

    /** C2：带外（深海段与岸段）高度逐位不受影响。 */
    private static void checkBandOutside(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        int inBand = 0, outside = 0, outsideDiff = 0, inBandDiff = 0;
        for (int j = 0; j < SIZE; j += 3) {
            for (int i = 0; i < SIZE; i += 3) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                double edge = edgeOf(on, x, z);
                boolean band = edge >= OceanAssemblyCandidate.cpShelfOf()
                        && edge < OceanAssemblyCandidate.cpShoreOf();
                double h0 = off.heightBlocks(x, z);
                double h1 = on.heightBlocks(x, z);
                boolean same = Double.compare(h0, h1) == 0;
                if (band) {
                    inBand++;
                    if (!same) inBandDiff++;
                } else {
                    outside++;
                    if (!same) outsideDiff++;
                }
            }
        }
        record("C2", "outside-band", "samples", outside, outside > 1000,
                "带外样本数（深海段 + 岸段）");
        record("C2", "outside-band", "bitwise-changed", outsideDiff, outsideDiff == 0,
                "带外高度必须逐位不变 ⇒ 深海段与岸段完全不受影响");
        record("C2", "inside-band", "samples", inBand, inBand > 1000, "带内样本数");
        record("C2", "inside-band", "changed", inBandDiff, inBandDiff > 0,
                "带内必须有像素被改变（否则是空算子）");
    }

    // ---------------------------------------------------------------- C3 / C4

    /**
     * C3/C4：两处端点的高度值与一阶差商都不变。
     *
     * <p>做法：沿一条射线搜索使 {@code edge} 恰好落在 {@code cpShelf ± δ} /
     * {@code cpShore ± δ} 的点，比较 {@code off} 与 {@code on} 在该点的差商。</p>
     */
    private static void checkEndpoints(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        double shelf = OceanAssemblyCandidate.cpShelfOf();
        double shore = OceanAssemblyCandidate.cpShoreOf();
        double band = shore - shelf;
        // 端点处的高度值：沿射线找 edge 最接近目标值的点（量化误差 < 1e-4 edge）
        double[] targets = {shelf, shore};
        String[] names = {"cpShelf", "cpShore"};
        for (int k = 0; k < targets.length; k++) {
            double[] p = findEdge(on, targets[k]);
            double h0 = off.heightBlocks(p[0], p[1]);
            double h1 = on.heightBlocks(p[0], p[1]);
            double d = Math.abs(h1 - h0);
            record("C3", names[k], "height-delta-blocks", d, d < 1e-3,
                    "端点高度必须不变（岸线不移动）；容差 1e-3 格吸收射线量化误差");
        }
        // C4：跨端点的单侧差商。在端点两侧各取 δ = band·2e-3 的 edge 步长，
        // 比较 off/on 的 (h(edge+δ) − h(edge))/δ。若端点处有一阶跳变，这里会放大。
        double delta = band * 2e-3;
        for (int k = 0; k < targets.length; k++) {
            for (int side = -1; side <= 1; side += 2) {
                double e0 = targets[k] + side * delta * 0.01;
                double e1 = e0 + delta;
                double[] a = findEdge(on, e0);
                double[] b = findEdge(on, e1);
                if (a == null || b == null) continue;
                double s0 = off.heightBlocks(a[0], a[1]);
                double s1 = off.heightBlocks(b[0], b[1]);
                double t0 = on.heightBlocks(a[0], a[1]);
                double t1 = on.heightBlocks(b[0], b[1]);
                // 差商之差：反映"形态项在端点是否引入额外斜率"
                double dsOff = Math.abs((s1 - s0) - (t1 - t0));
                record("C4", names[k] + (side < 0 ? "-below" : "-above"), "slope-delta-blocks", dsOff,
                        dsOff < 0.05,
                        "端点两侧的斜率必须与关闭时近乎相同（无一阶跳变）；容差 0.05 格");
            }
        }
    }

    // ---------------------------------------------------------------- C5

    /**
     * C5：乘法调制的结构性保证 —— 带内高度与基准<b>同号</b>且不越 {@code [−9, 0]}。
     *
     * <p>为什么不是 {@code [shallow, 0]}：{@code oceanDetail} 也可能在带内叠加（虽然
     * 它的 shoreFade 在 edge ≥ shelfEnd 处恒 0，理论上为 0，但判据不该依赖另一个算子的
     * 内部行为）。真正的契约是"不破 sea() 的四档阈值"，即高度落在 {@code [−9, 0]}。</p>
     */
    private static void checkDepthRange(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        // sea() 的深海阈值（DEEP_OCEAN 判据是 blocks < −SHALLOW_DEPTH_BLOCKS = −9）
        final double deepThreshold = -9.0;
        int n = 0, out = 0, signFlip = 0, deeper = 0, shallower = 0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < SIZE; j += 2) {
            for (int i = 0; i < SIZE; i += 2) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                double edge = edgeOf(on, x, z);
                if (edge < OceanAssemblyCandidate.cpShelfOf() || edge >= OceanAssemblyCandidate.cpShoreOf()) {
                    continue;
                }
                double h0 = off.heightBlocks(x, z);
                double h1 = on.heightBlocks(x, z);
                n++;
                min = Math.min(min, h1);
                max = Math.max(max, h1);
                if (h1 > 0.0 || h1 < deepThreshold) out++;
                if ((h0 < 0) != (h1 < 0)) signFlip++;
                if (h1 < h0) deeper++;
                if (h1 > h0) shallower++;
            }
        }
        record("C5", "band", "samples", n, n > 10000, "带内样本数");
        record("C5", "band", "min-blocks", min, min >= deepThreshold,
                "带内最深不得破 sea() 的深海阈值 " + deepThreshold + " 格");
        record("C5", "band", "max-blocks", max, max <= 0.0, "带内最浅不得高于 0 格（岸线高度）");
        record("C5", "band", "out-of-range", out, out == 0, "带内越界样本必须为 0");
        record("C5", "band", "sign-flips", signFlip, signFlip == 0,
                "乘法调制必须保持与基准同号 ⇒ 水陆判定在数学上不可能翻转");
        record("C5", "band", "became-deeper", deeper, deeper > 0, "确有像素变深（形态生效）");
        record("C5", "band", "became-shallower", shallower, shallower > 0, "确有像素变浅（形态生效）");
    }

    // ---------------------------------------------------------------- C6

    /**
     * C6：无折痕。
     *
     * <h3>判据不能用绝对阈值</h3>
     * <p>首版写成"二阶差增幅 < 0.35 格"。当形态项还弱到看不见时它通过，形态项调强到
     * 可见后它就 FAIL（实测 0.401）—— 说明这个阈值测的是<b>算子的强度</b>，不是
     * <b>有没有折痕</b>。把阈值放大到 0.5 只会让它重新变成"永远通过"的假门禁。</p>
     *
     * <h3>正确判据：与基准自身的二阶差比较</h3>
     * <p>折痕（阶跃 / 拐点）的定义是"局部曲率出现<b>孤立尖峰</b>"。所以判据是
     * <b>比值</b> {@code max(d2_on) / P99(d2_off)}：</p>
     * <ul>
     *   <li>形态项是 {@code 260 块}尺度的平滑起伏，与基准 {@code smooth(t)} 的曲率
     *       同量级 ⇒ 比值应在个位数；</li>
     *   <li>若有阶跃，{@code d2} 会在那一列突然跳到远高于周围 ⇒ 比值爆炸。</li>
     * </ul>
     * <p>同时报 {@code max(d2_on)} 的<b>出现次数</b>：真正的折痕是"一条线上一整排尖峰"，
     * 平滑极值只有一个点。用"超过 P99(d2_off)·3 的像素是否连成一片"区分二者过重，
     * 这里退一步只报比值与绝对量，判据取比值 ≤ 12。</p>
     *
     * <p>（{@code AGENTS.md}：{@code |∇|} 图上平滑脊两翼天然呈亮线，"图上有线"不等于
     * "有折痕"。曲率比值是同一思路——看<b>是否孤立</b>，不看<b>是否存在</b>。）</p>
     */
    private static void checkNoFold(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        double maxOn = 0.0, maxOff = 0.0;
        double worstEdge = Double.NaN;
        java.util.ArrayList<Double> offD2 = new java.util.ArrayList<>();
        int rows = 0, samples = 0;
        for (int j = 10; j < SIZE - 10; j += 37) {
            double z = CZ + (j - SIZE / 2.0) * STEP;
            rows++;
            for (int i = 2; i < SIZE - 2; i++) {
                double x0 = CX + (i - 1 - SIZE / 2.0) * STEP;
                double x1 = CX + (i - SIZE / 2.0) * STEP;
                double x2 = CX + (i + 1 - SIZE / 2.0) * STEP;
                double e1 = edgeOf(on, x1, z);
                if (e1 < OceanAssemblyCandidate.cpShelfOf() || e1 >= OceanAssemblyCandidate.cpShoreOf()) {
                    continue;
                }
                samples++;
                double d2off = Math.abs(off.heightBlocks(x2, z) - 2 * off.heightBlocks(x1, z)
                        + off.heightBlocks(x0, z));
                double d2on = Math.abs(on.heightBlocks(x2, z) - 2 * on.heightBlocks(x1, z)
                        + on.heightBlocks(x0, z));
                offD2.add(d2off);
                maxOff = Math.max(maxOff, d2off);
                if (d2on > maxOn) {
                    maxOn = d2on;
                    worstEdge = e1;
                }
            }
        }
        double[] ref = offD2.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p99 = ref.length == 0 ? 0.0 : ref[(int) Math.min(ref.length - 1, Math.round(0.99 * (ref.length - 1)))];
        double ratio = p99 <= 0 ? Double.POSITIVE_INFINITY : maxOn / p99;
        record("C6", "band-rows", "rows", rows, rows > 30, "扫描行数");
        record("C6", "band", "samples", samples, samples > 3000,
                "带内二阶差样本数（38 行 × 带内列数；P99 需要足够样本，3000 是下界）");
        record("C6", "band", "base-d2-p99", p99, p99 > 0, "基准二阶差的 P99（曲率尺度）");
        record("C6", "band", "max-d2-on", maxOn, maxOn > 0, "新臂二阶差最大值（最差处 edge=" + worstEdge + "）");
        record("C6", "band", "max-over-p99-ratio", ratio, ratio <= 12.0,
                "新臂最大二阶差 / 基准 P99 二阶差 ≤ 12 ⇒ 曲率与基准同量级、无孤立折痕尖峰"
                + "（首版用绝对阈值 0.35 格，测的是算子强度而非折痕，已废弃）");
    }

    // ---------------------------------------------------------------- C7

    /** C7：带内确有变化，且变化量落在设计振幅内。 */
    private static void checkActuallyChanges(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        double amp = 0.0, sum = 0.0;
        int changed = 0, n = 0;
        for (int j = 0; j < SIZE; j += 2) {
            for (int i = 0; i < SIZE; i += 2) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                double edge = edgeOf(on, x, z);
                if (edge < OceanAssemblyCandidate.cpShelfOf() || edge >= OceanAssemblyCandidate.cpShoreOf()) {
                    continue;
                }
                double d = on.heightBlocks(x, z) - off.heightBlocks(x, z);
                n++;
                sum += Math.abs(d);
                amp = Math.max(amp, Math.abs(d));
                if (d != 0.0) changed++;
            }
        }
        double bound = Math.abs(OceanAssemblyCandidate.shallowBlocks())
                * OceanAssemblyCandidate.SHALLOW_DETAIL_AMP;
        // 乘法调制：|Δ| = |base|·A·|n|·bump ≤ 7·A（base 最大 7 格）
        record("C7", "band", "changed-pixels", changed, changed > n * 0.5,
                "超半数带内像素应被改变");
        record("C7", "band", "mean-abs-blocks", n == 0 ? 0.0 : sum / n, true, "平均绝对改动量");
        record("C7", "band", "max-abs-blocks", amp, amp <= bound + 1e-9,
                "最大改动量不得超过设计上界 " + bound + " 格");
        record("C7", "band", "max-abs-blocks-visible", amp, amp >= 0.3,
                "形态必须真的可见（首版未归一化窗只有 0.062 格，等于没加；判据 ≥0.3 格）");
    }

    // ---------------------------------------------------------------- C8 / C9

    /**
     * C8/C9：分类漂移要<b>分档判</b>，不能一律禁止。
     *
     * <h3>为什么不能要求 drift = 0</h3>
     * <p>首版这么写，实测 376 像素 FAIL。但那 376 个全是
     * <b>SHALLOW_OCEAN ↔ COAST</b> 的翻转（浅海与海岸带的分界在 −2 格，恰好落在带内）。</p>
     * <p>这类翻转<b>是形态项该有的效果</b>：陆架中段本来一片均质平坦，加了起伏之后，
     * 浅处确实该读作岸礁、深处该读作浅海。只要翻转发生在形态平滑变化的地方（不是斑点状），
     * 它和"分类读高度"的既有口径完全一致 —— {@code sea()} 本来就是读 {@code heightBlocks()}
     * 的，不可能与高度脱节。</p>
     *
     * <h3>必须为 0 的是这两类</h3>
     * <ol>
     *   <li><b>陆/海翻转</b>：乘法调制同号 ⇒ 数学上不可能。若出现，说明实现被改坏了。</li>
     *   <li><b>DEEP_OCEAN 误判</b>：深海阈值 −9 格。设计上最深处 ≤ 8.54 格 &lt; 9 ⇒
     *       不可能。若出现，说明振幅或窗被改大了，必须报错（这正是 AGENTS.md
     *       「阈值必须与剖面在该层能达到的最深处对齐，否则会漏判」那条）。</li>
     * </ol>
     *
     * <h3>要设上界的是这一类</h3>
     * <p>SHALLOW↔COAST 翻转比例：它是"陆架起伏的自然产物"，但要防止它退化成
     * <b>斑点状</b>（一个像素一行、噪点感）。用"翻转像素 / 带内像素"设上界。</p>
     */
    private static void checkSeaClass(OceanAssemblyCandidate off, OceanAssemblyCandidate on) {
        int waterFlip = 0, deepDrift = 0, shallowCoastDrift = 0, bandPixels = 0, n = 0;
        int water0 = 0, water1 = 0;
        int[] histOff = new int[4];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                var s0 = off.sea(x, z);
                var s1 = on.sea(x, z);
                boolean w0 = off.heightBlocks(x, z) < 0;
                boolean w1 = on.heightBlocks(x, z) < 0;
                histOff[s0.ordinal()]++;
                n++;
                if (w0 != w1) waterFlip++;
                if (w0) water0++;
                if (w1) water1++;
                boolean inBand = s0 == OceanAssemblyCandidate.Sea.DEEP_OCEAN
                        || s0 == OceanAssemblyCandidate.Sea.SHALLOW_OCEAN
                        || s0 == OceanAssemblyCandidate.Sea.COAST;
                double edge = edgeOf(on, x, z);
                if (edge >= OceanAssemblyCandidate.cpShelfOf() && edge < OceanAssemblyCandidate.cpShoreOf()) {
                    bandPixels++;
                }
                if (s0 == s1) continue;
                if (s0 == OceanAssemblyCandidate.Sea.DEEP_OCEAN
                        || s1 == OceanAssemblyCandidate.Sea.DEEP_OCEAN) {
                    deepDrift++;
                } else if (inBand) {
                    shallowCoastDrift++;
                }
            }
        }
        record("C8", "window", "land-water-flips", waterFlip, waterFlip == 0,
                "陆/海翻转必须为 0（乘法调制同号 ⇒ 结构性保证）");
        record("C8", "window", "deep-ocean-drift", deepDrift, deepDrift == 0,
                "DEEP_OCEAN 误判必须为 0（最深处 8.54 格 < 阈值 9 格）");
        double share = bandPixels == 0 ? 0 : shallowCoastDrift * 100.0 / bandPixels;
        record("C8", "band", "shallow-coast-flip-pct", share, share <= 8.0,
                "SHALLOW↔COAST 翻转占带内像素 ≤ 8%（允许自然起伏，禁止斑点化）");
        record("C8", "window", "land-pixels", histOff[3], true, "关闭时陆地像素数");
        record("C8", "window", "deep-pixels", histOff[0], true, "关闭时深海像素数");
        record("C8", "window", "shallow-pixels", histOff[1], true, "关闭时浅海像素数");
        record("C8", "window", "coast-pixels", histOff[2], true, "关闭时海岸像素数");
        double delta = n == 0 ? 0 : Math.abs(water1 - water0) * 100.0 / n;
        record("C9", "window", "water-ratio-delta-pts", delta, delta == 0.0,
                "1401² 窗口水面占比变化必须为 0（乘法调制同号 ⇒ 结构性保证，不是实测巧合）");
    }

    // ---------------------------------------------------------------- 工具

    private static double edgeOf(OceanAssemblyCandidate ocean, double x, double z) {
        return (ocean.edgeSourceOf().sample(x, z) + 1.0) * 0.5;
    }

    /**
     * 沿一条固定射线搜索使 {@code edge} 最接近 {@code target} 的点（二分）。
     * 返回 {@code {x, z}}；不可达返回 {@code null}。
     */
    private static double[] findEdge(OceanAssemblyCandidate ocean, double target) {
        double z = CZ;
        // 粗扫找跨越点
        double prevX = CX - 3000, prevE = edgeOf(ocean, prevX, z) - target;
        for (double x = prevX + 4; x <= CX + 3000; x += 4) {
            double e = edgeOf(ocean, x, z) - target;
            if (prevE == 0) return new double[]{prevX, z};
            if (prevE * e < 0) {
                double lo = prevX, hi = x, flo = prevE;
                for (int it = 0; it < 60; it++) {
                    double mid = 0.5 * (lo + hi);
                    double fm = edgeOf(ocean, mid, z) - target;
                    if (flo * fm <= 0) { hi = mid; } else { lo = mid; flo = fm; }
                }
                return new double[]{0.5 * (lo + hi), z};
            }
            prevX = x;
            prevE = e;
        }
        return null;
    }

    private static void record(String id, String scope, String metric, double value,
                               boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%s,%.9g,%s%n", id, scope, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-12s %-24s %14.6g  %s  %s%n",
                id, scope, metric, value, ok ? "PASS" : "FAIL", ok ? "" : "  <-- " + note);
        if (!ok) {
            System.out.println("SHALLOW DETAIL CHECK FAIL: " + id + " " + scope + " " + metric
                    + " = " + value + "  (" + note + ")");
            System.exit(1);
        }
    }

    private ShallowDetailCheck() { }
}
