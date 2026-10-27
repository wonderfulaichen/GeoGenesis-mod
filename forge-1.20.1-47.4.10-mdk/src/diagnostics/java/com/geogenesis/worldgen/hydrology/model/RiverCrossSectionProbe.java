package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>{@link RiverCrossSection} 门禁探针（纯合成输入）</h1>
 *
 * <p>只断言<b>合成输入 → 独立预期值</b>：不读世界、不跑生产图、不消费 {@code HydrologyPipeline}。
 * 数值锚点使用独立字面量，另检查对称性、单调性和只下挖等属性；容差 {@code 1e-9} 或精确相等。</p>
 *
 * <h2>覆盖（对齐方案 §5 P0 「建立独立预期值的合成断面」）</h2>
 * <ol>
 *   <li>深度标定：{@code W=1/4/6/12} 及全部 1..12 的字面量、饱和与退化输入；</li>
 *   <li>横断面：普通河段中心最深 / 岸缘为 1 / 左右严格对称 / 向内单调；浅接口不反向加深，非正深度标干；</li>
 *   <li>穿块边界：<b>等距必等深</b>（与所在 chunk 无关）、边界两侧无台阶、{@code dist>half} 的补点仍是浅岸；</li>
 *   <li>只下挖：{@code B ≤ E} 恒成立、天然深槽不被填高、非有限值（{@code NaN}/{@code ±∞}）传播不静默；</li>
 *   <li>整数水柱：正/负 {@code floor} 边界、连续 0.55 但 0 块、非有限输入；</li>
 *   <li>湿床：实际列小数水面、至少一块空间、双重减法写回、负地形、深槽保留和原 fill .5 门槛；</li>
 *   <li>整条横断面的水柱分布：中心 ≥ 中段 ≥ 岸，且岸仍保有 1 块（不把认可的湿宽削窄）。</li>
 * </ol>
 *
 * <h2>明确不覆盖（本探针不能充当验收）</h2>
 * <ul>
 *   <li>生产列生成是否正确调用本类 —— {@code ModelHydrologyProvider} 由主线程接入，本探针不验证该调用；</li>
 *   <li>实机渲染、流体 tick、洞穴/装饰后的断面形态；</li>
 *   <li>接点过渡（串联 / 汇流 / 湖口 / 跨区）—— 本轮不实现，故不测；</li>
 *   <li>任何「物理正确」判断：常量是游戏尺度候选标定，不是水力学定律。</li>
 * </ul>
 *
 * <h2>运行（无专用 Gradle 任务，构建文件未改）</h2>
 * <pre>{@code
 *   javac -d build\tmp\rcs \
 *       src\main\java\com\geogenesis\worldgen\hydrology\model\RiverCrossSection.java \
 *       src\diagnostics\java\com\geogenesis\worldgen\hydrology\model\RiverCrossSectionProbe.java
 *   java -cp build\tmp\rcs com.geogenesis.worldgen.hydrology.model.RiverCrossSectionProbe
 * }</pre>
 * <p>FAIL 时 {@code System.exit(1)}（门禁不能只在日志里红）。</p>
 */
public final class RiverCrossSectionProbe {

    private RiverCrossSectionProbe() { }

    private static int pass, fail;
    private static final List<String> FAILS = new ArrayList<>();

    /** 字面量容差：预期值由 offline 独立算得，1e-9 足够区分「同式反推」与「抄错一位」。 */
    private static final double TOL = 1e-9;

    public static void main(String[] args) {
        testCenterDepthAnchors();
        testCenterDepthIndependentSamples();
        testCenterDepthMonotoneInWidth();
        testCenterDepthClampAndDegenerate();
        testTargetDepthCenterAndShore();
        testShallowInterfaces();
        testZeroDepthMarksNoWater();
        testTargetDepthLateralSymmetry();
        testTargetDepthMonotoneInland();
        testCrossSectionPerWidth();
        testSeamAndCenterSmoothness();
        testBeyondHalfKeepsShoreDepth();
        testChunkBoundaryCrossing();
        testWideRiverAcrossChunkBoundary();
        testBedOnlyCutsDown();
        testNaturalDeepSlotNotFilled();
        testBedPropagatesNonFinite();
        testWetBedColumnAnchors();
        testWetBedDryAndDeepSlots();
        testWetBedWriteback();
        testWaterBlocksFloorPositive();
        testWaterBlocksFloorNegative();
        testWaterBlocksFractionalDepth();
        testWaterBlocksNonFinite();
        testSectionColumnProfile();

        System.out.printf("%n=== RiverCrossSectionProbe: PASS=%d FAIL=%d ===%n", pass, fail);
        for (String f : FAILS) System.out.println("  FAIL: " + f);
        if (fail > 0) {
            System.out.println("结论：FAIL");
            System.exit(1);
        }
        System.out.println("结论：ALL PASS（仅纯函数合成用例；生产接入与实机仍须另行验收）");
    }

    // ① 深度标定

    /** 两端锚点必须<b>精确</b>：{@code W=1 → 1}、{@code W=12 → 4}（不靠容差）。 */
    private static void testCenterDepthAnchors() {
        check("anchor W=1 恰好 1.0", RiverCrossSection.centerDepth(1.0) == 1.0,
                "got=" + RiverCrossSection.centerDepth(1.0));
        check("anchor W=12 恰好 4.0", RiverCrossSection.centerDepth(12.0) == 4.0,
                "got=" + RiverCrossSection.centerDepth(12.0));
        check("常量自洽 Hmax=4", RiverCrossSection.CENTER_DEPTH_MAX == 4.0,
                "CENTER_DEPTH_MAX=" + RiverCrossSection.CENTER_DEPTH_MAX);
        check("常量自洽 Hedge=1", RiverCrossSection.EDGE_DEPTH == 1.0,
                "EDGE_DEPTH=" + RiverCrossSection.EDGE_DEPTH);
    }

    /**
     * 1..12 全量字面量（独立算得：{@code 1+3·((W−1)/11)^0.75}，由外部工具在写码前产出）。
     */
    private static void testCenterDepthIndependentSamples() {
        double[][] expected = {
                {1, 1.0},
                {2, 1.4966800782285106},
                {3, 1.835312994620578},
                {4, 2.1321857433707407},
                {5, 2.4048234055825786},
                {6, 2.6607499349512365},
                {7, 2.9041018660036357},
                {8, 3.137471195312568},
                {9, 3.362621931637811},
                {10, 3.5808253917971946},
                {11, 3.7930373338666685},
                {12, 4.0},
        };
        for (double[] e : expected) near("标定 W=" + (int) e[0], e[1], RiverCrossSection.centerDepth(e[0]), TOL);
    }

    /** 全宽单调不减且每次都真的变深（防止把 u 写死或漏掉指数）。 */
    private static void testCenterDepthMonotoneInWidth() {
        double prev = RiverCrossSection.centerDepth(RiverCrossSection.MIN_FULL_WIDTH);
        for (int w = 2; w <= 12; w++) {
            double cur = RiverCrossSection.centerDepth(w);
            check("宽度单调 W=" + w, cur > prev, "prev=" + prev + " cur=" + cur);
            check("宽度上界 W=" + w, cur <= RiverCrossSection.CENTER_DEPTH_MAX + TOL, "cur=" + cur);
            prev = cur;
        }
    }

    /** 饱和与非有限：越界夹端点，NaN/±∞ 退回最小值（保守，不放大下挖）。 */
    private static void testCenterDepthClampAndDegenerate() {
        near("W=0 饱和到 1", 1.0, RiverCrossSection.centerDepth(0.0), 0.0);
        near("W=0.5 饱和到 1", 1.0, RiverCrossSection.centerDepth(0.5), 0.0);
        near("W=-3 饱和到 1", 1.0, RiverCrossSection.centerDepth(-3.0), 0.0);
        near("W=13 饱和到 4", 4.0, RiverCrossSection.centerDepth(13.0), 0.0);
        near("W=100 饱和到 4", 4.0, RiverCrossSection.centerDepth(100.0), 0.0);
        near("W=NaN 退回 1", 1.0, RiverCrossSection.centerDepth(Double.NaN), 0.0);
        near("W=+Inf 退回 1", 1.0, RiverCrossSection.centerDepth(Double.POSITIVE_INFINITY), 0.0);
        near("W=-Inf 退回 1", 1.0, RiverCrossSection.centerDepth(Double.NEGATIVE_INFINITY), 0.0);
    }

    // ② 横断面

    /** 字面量断面：{@code c=2.5, half=6} ⇒ 中心 2.5、r=0.5（d=3）1.84375、岸 1.0。 */
    private static void testTargetDepthCenterAndShore() {
        near("横断面中心 d=0", 2.5, RiverCrossSection.targetDepth(2.5, 0.0, 6.0), TOL);
        near("横断面 r=0.5 (d=3)", 1.84375, RiverCrossSection.targetDepth(2.5, 3.0, 6.0), TOL);
        near("横断面岸缘 d=half", 1.0, RiverCrossSection.targetDepth(2.5, 6.0, 6.0), 0.0);
        near("横断面 r=0.25 (d=1.5)", 2.318359375, RiverCrossSection.targetDepth(2.5, 1.5, 6.0), TOL);
    }

    /** 浅湖/海接口的 Hc 不来自宽度标定：不足 1 格时不允许岸缘反而更深。 */
    private static void testShallowInterfaces() {
        // Hc, S, E, expected B, expected integer blocks (independent literals).
        double[][] cases = {
                {0.25, 64.75, 65.0, 64.5, 0},
                {0.4, 63.0, 64.0, 62.6, 1},
                {0.75, -0.125, 0.0, -0.875, 0},
                {0.75, 0.125, 1.0, -0.625, 1},
                {1.0, -1.0, 0.0, -2.0, 1},
        };
        for (double[] c : cases) {
            double hc = c[0], surface = c[1], eroded = c[2];
            for (double d : new double[]{-12, -6, -3, 0, 3, 6, 12}) {
                double target = RiverCrossSection.targetDepth(hc, d, 6.0);
                near("浅接口保留 Hc=" + hc + " d=" + d, hc, target, 0.0);
                double bed = RiverCrossSection.bed(eroded, surface, target);
                near("浅接口床面 Hc=" + hc + " d=" + d, c[3], bed, TOL);
                eqInt("浅接口整数水柱 Hc=" + hc + " d=" + d,
                        (int) c[4], RiverCrossSection.waterBlocks(surface, bed));
                check("浅接口不抬地形 Hc=" + hc, bed <= eroded, "bed=" + bed);
            }
        }
        near("浅接口退化半宽仍不加深", 0.4, RiverCrossSection.targetDepth(0.4, 0, 0), 0.0);
        near("浅接口保留天然深槽", 55, RiverCrossSection.bed(55, 64, 0.4), 0.0);
    }

    /** 返回零就是无水标记；bed/waterBlocks 本身仍只处理几何，不代替调用方湿列判定。 */
    private static void testZeroDepthMarksNoWater() {
        for (double hc : new double[]{0, -0.0, -0.25, -5}) {
            for (double d : new double[]{-12, -6, -3, 0, 3, 6, 12}) {
                double target = RiverCrossSection.targetDepth(hc, d, 6);
                near("非正 Hc 返回无水零 Hc=" + hc + " d=" + d, 0, target, 0.0);
                check("无水不进入湿列 Hc=" + hc, !(target > 0), "target=" + target);
                double bed = RiverCrossSection.bed(64, 64, target);
                near("无水平地床面不变", 64, bed, 0.0);
                eqInt("无水平地水柱零", 0, RiverCrossSection.waterBlocks(64, bed));
                near("无水标记也不填高天然深槽", 55,
                        RiverCrossSection.bed(55, 64, target), 0.0);
            }
        }
        // 即使天然槽几何上能容水，target=0 仍要求消费者明确标干，不能从水柱反推 wet。
        eqInt("几何水柱不能代替无水标记", 9,
                RiverCrossSection.waterBlocks(64, RiverCrossSection.bed(55, 64, 0)));
    }

    /** 左右严格对称（{@code distance} 取绝对值 ⇒ 逐位相同，非近似）。 */
    private static void testTargetDepthLateralSymmetry() {
        double half = 4.5, center = 3.4;
        double[] ds = {0.0, 0.25, 1.5, 3.25, 4.49, 4.5, 9.0};
        for (double d : ds) {
            double plus = RiverCrossSection.targetDepth(center, d, half);
            double minus = RiverCrossSection.targetDepth(center, -d, half);
            check("左右对称 d=" + d, plus == minus, "plus=" + plus + " minus=" + minus);
        }
    }

    /** 向内单调：距离越大越浅（非严格，但中心必严格深于中段）。 */
    private static void testTargetDepthMonotoneInland() {
        double center = 4.0, half = 6.0;
        double prev = RiverCrossSection.targetDepth(center, 0.0, half);
        near("中心即 Hc", center, prev, 0.0);
        for (double d = 0.5; d <= half + TOL; d += 0.5) {
            double cur = RiverCrossSection.targetDepth(center, d, half);
            check("向内单调 d=" + d, cur <= prev + 1e-15, "prev=" + prev + " cur=" + cur);
            check("不下探到 1 以下 d=" + d, cur >= 1.0 - 1e-15, "cur=" + cur);
            prev = cur;
        }
        check("中心严格深于中段", RiverCrossSection.targetDepth(center, 0.0, half)
                        > RiverCrossSection.targetDepth(center, half * 0.5, half) + 1e-6,
                "center=" + RiverCrossSection.targetDepth(center, 0.0, half)
                        + " mid=" + RiverCrossSection.targetDepth(center, half * 0.5, half));
    }

    /**
     * 1 / 4 / 6 / 12 格四种宽度的完整横断面（字面量独立算得）。
     * {@code W=1} 时 Hc=1 ⇒ 整条断面恒为 1（细河不该被挖深）。
     */
    private static void testCrossSectionPerWidth() {
        double[][] rows = {
                // W,  half, Hc,                              H(r=0.25),          H(r=0.5)
                {1, 0.5, 1.0, 1.0, 1.0},
                {4, 2.0, 2.1321857433707407, 1.9950851260094402, 1.6368544806460417},
                {6, 3.0, 2.6607499349512365, 2.459643497515735, 1.9341718384100706},
                {12, 6.0, 4.0, 3.63671875, 2.6875},
        };
        for (double[] r : rows) {
            int w = (int) r[0];
            double half = r[1], hc = r[2];
            near("断面 W=" + w + " 复核 Hc", hc, RiverCrossSection.centerDepth(w), TOL);
            near("断面 W=" + w + " 中心", hc, RiverCrossSection.targetDepth(hc, 0.0, half), 0.0);
            near("断面 W=" + w + " r=0.25", r[3], RiverCrossSection.targetDepth(hc, half * 0.25, half), TOL);
            near("断面 W=" + w + " r=0.5", r[4], RiverCrossSection.targetDepth(hc, half * 0.5, half), TOL);
            near("断面 W=" + w + " 岸缘", 1.0, RiverCrossSection.targetDepth(hc, half, half), 0.0);
            // 左右各取三列，逐位相同
            for (double f : new double[]{0.0, 0.5, 1.0}) {
                check("断面 W=" + w + " 对称 f=" + f, RiverCrossSection.targetDepth(hc, half * f, half)
                                == RiverCrossSection.targetDepth(hc, -half * f, half),
                        "f=" + f);
            }
            crossSectionTable(w, half, hc);
        }
    }

    /** 断面形状的端点平坦性：岸缘无阶跃、中心无 V 型折痕（AGENTS.md 硬约束 2/4 的数值旁证）。 */
    private static void testSeamAndCenterSmoothness() {
        double hc = 4.0, half = 6.0;
        double nearShore = RiverCrossSection.targetDepth(hc, half * (1 - 1e-3), half);
        // r=1−1e-3 ⇒ H = 1 + 3·(1−r²)² ≈ 1 + 3·4e-6 = 1.000012
        check("岸缘平滑着陆（无阶跃）", nearShore - 1.0 <= 2e-5, "H=" + nearShore);
        double nearCenter = RiverCrossSection.targetDepth(hc, half * 1e-3, half);
        // r=1e-3 ⇒ H ≈ 4 − 3·2e-6 ≈ 3.999994（中心不是折痕，下探极浅）
        check("中心非 V 折痕", hc - nearCenter <= 2e-5, "H=" + nearCenter);
        // 连续性：1e-6 步长的变化量应远小于 1 格
        double a = RiverCrossSection.targetDepth(hc, 3.0, half);
        double b = RiverCrossSection.targetDepth(hc, 3.0 + 1e-6, half);
        check("断面连续（局部差 <1e-4）", Math.abs(a - b) < 1e-4, "a=" + a + " b=" + b);
    }

    /** {@code dist > effectiveHalf}（穿块补连通点）必须取浅岸水深，不挖深沟。 */
    private static void testBeyondHalfKeepsShoreDepth() {
        double hc = 4.0, half = 6.0;
        for (double d : new double[]{6.0, 6.0000001, 7.5, 12.0, 100.0, Double.MAX_VALUE}) {
            near("超半宽仍浅岸 d=" + d, 1.0, RiverCrossSection.targetDepth(hc, d, half), 0.0);
        }
        near("半宽为 0 按岸缘", 1.0, RiverCrossSection.targetDepth(hc, 0.0, 0.0), 0.0);
        near("半宽为负按岸缘", 1.0, RiverCrossSection.targetDepth(hc, 3.0, -2.0), 0.0);
        near("半宽 NaN 按岸缘", 1.0, RiverCrossSection.targetDepth(hc, 3.0, Double.NaN), 0.0);
        near("距离 NaN 按岸缘", 1.0, RiverCrossSection.targetDepth(hc, Double.NaN, half), 0.0);
        near("centerDepth<1 保留浅接口", 0.4, RiverCrossSection.targetDepth(0.4, 0.0, half), 0.0);
        near("centerDepth NaN 夹到 1", 1.0, RiverCrossSection.targetDepth(Double.NaN, 0.0, half), 0.0);
    }

    /**
     * 穿块边界：本类只依赖距离 ⇒ <b>等距必等深</b>，与列所在的 chunk 无关。
     * 中心线落在 {@code x=16.0}（chunk 边界上），边界两侧等距的列必须给出同一水深。
     */
    private static void testChunkBoundaryCrossing() {
        double hc = 2.6607499349512365, half = 3.0, centerX = 16.0;
        double left = RiverCrossSection.targetDepth(hc, centerX - 15.5, half);   // chunk 0 内，d=0.5
        double right = RiverCrossSection.targetDepth(hc, 16.5 - centerX, half);  // chunk 1 内，d=0.5
        check("跨块等距等深 d=0.5", left == right, "left=" + left + " right=" + right);
        double far0 = RiverCrossSection.targetDepth(hc, centerX - 12.5, half);   // chunk 0，d=3.5
        double far1 = RiverCrossSection.targetDepth(hc, 19.5 - centerX, half);   // chunk 1，d=3.5
        check("跨块等距等深 d=3.5", far0 == far1, "far0=" + far0 + " far1=" + far1);
        // 边界两侧 1e-4 格内不得出现台阶
        double a = RiverCrossSection.targetDepth(hc, 0.4999, half);
        double b = RiverCrossSection.targetDepth(hc, 0.5001, half);
        check("边界两侧无台阶", Math.abs(a - b) < 1e-3, "a=" + a + " b=" + b);
    }

    /** 河宽跨 chunk（half=20）时，边界两侧仍是同一条平滑曲线上的两点，不是深/浅两级台阶。 */
    private static void testWideRiverAcrossChunkBoundary() {
        double hc = 4.0, half = 20.0;
        double inner = RiverCrossSection.targetDepth(hc, 15.5, half);
        double outer = RiverCrossSection.targetDepth(hc, 16.5, half);
        check("宽河跨块内侧更深", inner > outer, "inner=" + inner + " outer=" + outer);
        check("宽河跨块无台阶", inner - outer < 0.25, "delta=" + (inner - outer));
        check("宽河跨块仍在区间内", inner <= hc && outer >= 1.0, "inner=" + inner + " outer=" + outer);
        near("宽河中点 r=0.5", 2.6875, RiverCrossSection.targetDepth(hc, 10.0, half), TOL);
    }

    // ③ 只下挖

    /** {@code B ≤ E} 恒成立；{@code E} 高于目标床面才挖，低于则原样（不填）。 */
    private static void testBedOnlyCutsDown() {
        double[][] cases = {
                // E,     S,     H(r), expected B (independent literals)
                {70.0, 70.0, 4.0, 66.0},
                {70.0, 70.0, 1.0, 69.0},
                {70.9, 70.2, 2.5, 67.7},
                {70.0, 66.0, 1.5, 64.5},   // 高岸所需下挖 5.5，不偷偷截到 4
                {-0.5, 0.5, 2.0, -1.5},
                {-64.0, -64.0, 3.0, -67.0},
                {70.0, 70.0, 0.0, 70.0},
                {55.0, 64.0, 0.4, 55.0},
        };
        for (double[] c : cases) {
            double e = c[0], s = c[1], h = c[2];
            double b = RiverCrossSection.bed(e, s, h);
            check(String.format("只下挖 B<=E E=%.2f S=%.2f H=%.2f", e, s, h), b <= e + 1e-12,
                    "B=" + b);
            double cut = e - b;
            check(String.format("下挖量非负 E=%.2f S=%.2f H=%.2f", e, s, h), cut >= -1e-12, "C=" + cut);
            near(String.format("床面独立预期 E=%.2f S=%.2f H=%.2f", e, s, h), c[3], b, 1e-12);
        }
    }

    /** 天然深槽：{@code E} 已低于目标床面 ⇒ 逐位保留 {@code E}，实际水深可超 Hmax。 */
    private static void testNaturalDeepSlotNotFilled() {
        double s = 70.0, e = s - 9.0;
        for (double h = 1.0; h <= 4.0; h += 1.0) {
            double b = RiverCrossSection.bed(e, s, h);
            check("天然深槽不被填高 H=" + h, b == e, "E=" + e + " B=" + b);
            check("天然深槽水柱可超 Hmax H=" + h,
                    RiverCrossSection.waterBlocks(s, b) > RiverCrossSection.CENTER_DEPTH_MAX,
                    "blocks=" + RiverCrossSection.waterBlocks(s, b));
        }
    }

    /** 非有限输入原样传播（不静默回退成地面高度；“非有限”既可能是 {@code NaN} 也可能是 {@code ±∞}）。 */
    private static void testBedPropagatesNonFinite() {
        check("bed E=NaN 传播", Double.isNaN(RiverCrossSection.bed(Double.NaN, 70.0, 2.0)), "");
        check("bed S=NaN 传播", Double.isNaN(RiverCrossSection.bed(70.0, Double.NaN, 2.0)), "");
        check("bed H=NaN 传播", Double.isNaN(RiverCrossSection.bed(70.0, 70.0, Double.NaN)), "");
        // S=−∞ ⇒ S−H=−∞ ⇒ min(70,−∞)=−∞：必须原样传播，不能被夹回 70（否则静默变成“没挖”）
        check("bed S=-Inf 传播（不夹回 E）",
                RiverCrossSection.bed(70.0, Double.NEGATIVE_INFINITY, 2.0) == Double.NEGATIVE_INFINITY,
                "got=" + RiverCrossSection.bed(70.0, Double.NEGATIVE_INFINITY, 2.0));
        // S=+∞ ⇒ 目标床面 +∞ 高于 E ⇒ min 保留 E（既不挖也不填）
        near("bed S=+Inf 保留 E", 70.0, RiverCrossSection.bed(70.0, Double.POSITIVE_INFINITY, 2.0), 0.0);
    }

    /** 实际列小数水面造成几何深度足够、但 floor 水柱为零的独立预期。 */
    private static void testWetBedColumnAnchors() {
        // E, S, H, expected wet B, expected old floor blocks, expected unchanged fill predicate.
        double[][] cases = {
                {160, 158.8453, 0.6825, 157.999999, 0, 1},
                {70, 69.9, 0.55, 68.999999, 0, 1},
                {0, -64.1547, 0.6825, -65.000001, 0, 1},
                {0, -0.1547, 0.6825, -1.000001, 0, 1},
                {1, 0.8453, 0.6825, -0.000001, 0, 1},
                {70, 70, 4, 66, 4, 1},
                {64, 64, 0.25, 63.75, 1, 0},
                {64, 64, 1e-12, 63.999999, 1, 0},
                {64, 64.25, 0.25, 63.999999, 0, 0},
                {64, 64, 0.5, 63.5, 1, 0},
        };
        for (double[] c : cases) {
            double e = c[0], s = c[1], h = c[2];
            String label = "wetBed E=" + e + " S=" + s + " H=" + h;
            double geometric = RiverCrossSection.bed(e, s, h);
            eqInt(label + " 旧几何水柱", (int) c[4], RiverCrossSection.waterBlocks(s, geometric));
            double b = RiverCrossSection.wetBed(e, s, h);
            near(label + " 独立床面预期", c[3], b, 1e-12);
            check(label + " 至少一块空间", RiverCrossSection.waterBlocks(s, b) >= 1, "B=" + b);
            double cut = e - b;
            double restored = e - cut;
            check(label + " 只下挖", b <= e && cut >= 0, "cut=" + cut);
            check(label + " 相减写回后仍跨 floor", Math.floor(s) > Math.floor(restored), "restored=" + restored);
            check(label + " 不放宽原 fill .5 门槛", (s > restored + 0.5) == (c[5] == 1), "B=" + restored);
        }
    }

    /** 非正深度不雕刻，天然深槽与已有较深的几何床面逐位保留。 */
    private static void testWetBedDryAndDeepSlots() {
        for (double h : new double[]{0, -0.0, -0.5, Double.NEGATIVE_INFINITY, Double.NaN}) {
            for (double e : new double[]{160, -80, -0.0}) {
                double b = RiverCrossSection.wetBed(e, 64, h);
                check("wetBed 无水原样返回 E=" + e + " H=" + h,
                        Double.doubleToLongBits(e) == Double.doubleToLongBits(b), "B=" + b);
            }
        }
        for (double[] c : new double[][]{{55, 64, 0.6825}, {-100, -64.1547, 0.6825}, {60, 64, 4}}) {
            double b = RiverCrossSection.wetBed(c[0], c[1], c[2]);
            near("wetBed 天然深槽原样保留", c[0], b, 0);
            near("wetBed 深槽下挖量零", 0, c[0] - b, 0);
        }
        near("wetBed 已跨 floor 的深断面不变", 66, RiverCrossSection.wetBed(70, 70, 4), 0);
        near("wetBed 已跨 floor 的浅断面不变", 62.6, RiverCrossSection.wetBed(64, 63, 0.4), TOL);
    }

    /** 双重减法模拟 erosion=C / mask=1 写回；epsilon 必须在消减后仍跨整数层。 */
    private static void testWetBedWriteback() {
        int count = 0;
        for (double e : new double[]{-2048, -64, 0, 320, 4096}) {
            for (double s : new double[]{-2048, -64.999999, -64.1547, -1, -0.1547,
                    0, 0.8453, 64, 158.8453, 319.999999, 2048}) {
                for (double h : new double[]{Double.MIN_VALUE, 1e-12, 0.25, 0.500001, 0.6825, 4}) {
                    double b = RiverCrossSection.wetBed(e, s, h);
                    double cut = e - b;
                    double restored = e - cut;
                    String label = "wetBed 写回 E=" + e + " S=" + s + " H=" + h;
                    check(label + " 非负下挖", b <= e && cut >= 0, "B=" + b);
                    check(label + " 不抬高几何床", b <= RiverCrossSection.bed(e, s, h), "B=" + b);
                    check(label + " floor 非空", Math.floor(s) - Math.floor(restored) >= 1, "B'=" + restored);
                    check(label + " 原 fill 通过仍非空", !(s > restored + 0.5)
                            || RiverCrossSection.waterBlocks(s, restored) >= 1, "B'=" + restored);
                    count++;
                }
            }
        }
        System.out.println("wetBed subtraction/writeback cases=" + count);
    }

    // ④ 整数水柱

    /** 正高度的 {@code floor} 边界（含「连续够深但整块恰好 1」的锚点）。 */
    private static void testWaterBlocksFloorPositive() {
        eqInt("S=70.90 B=69.10", 1, RiverCrossSection.waterBlocks(70.90, 69.10));
        eqInt("S=70.00 B=69.00（整数边界）", 1, RiverCrossSection.waterBlocks(70.00, 69.00));
        eqInt("S=70.99 B=68.01", 2, RiverCrossSection.waterBlocks(70.99, 68.01));
        eqInt("S=70.00 B=70.00（无水深）", 0, RiverCrossSection.waterBlocks(70.00, 70.00));
        eqInt("S=70.00 B=70.50（床高于水面）", 0, RiverCrossSection.waterBlocks(70.00, 70.50));
        eqInt("S=70.00 B=66.00", 4, RiverCrossSection.waterBlocks(70.00, 66.00));
    }

    /** 负高度的 {@code floor} 边界（海平面以下；{@code floor} 向 −∞ 取整）。 */
    private static void testWaterBlocksFloorNegative() {
        eqInt("S=-1.5 B=-3.5", 2, RiverCrossSection.waterBlocks(-1.5, -3.5));
        eqInt("S=-1.0 B=-1.0", 0, RiverCrossSection.waterBlocks(-1.0, -1.0));
        eqInt("S=-0.5 B=-0.9（同 floor）", 0, RiverCrossSection.waterBlocks(-0.5, -0.9));
        eqInt("S=0.5 B=-0.5", 1, RiverCrossSection.waterBlocks(0.5, -0.5));
        eqInt("S=-64.2 B=-66.8", 2, RiverCrossSection.waterBlocks(-64.2, -66.8));
        eqInt("S=0.0 B=-0.0001（跨 0 边界，占 1 块）", 1, RiverCrossSection.waterBlocks(0.0, -0.0001));
    }

    /** 连续水深 0.55 但取整后 0 块 —— 不许靠放宽门槛掩盖（方案 §2 的更正）。 */
    private static void testWaterBlocksFractionalDepth() {
        eqInt("S=69.90 B=69.35 连续 0.55 → 0 块", 0, RiverCrossSection.waterBlocks(69.90, 69.35));
        eqInt("S=69.90 B=69.10 连续 0.80 → 0 块", 0, RiverCrossSection.waterBlocks(69.90, 69.10));
        eqInt("S=70.00 B=69.50 连续 0.50 → 1 块", 1, RiverCrossSection.waterBlocks(70.00, 69.50));
        eqInt("S=69.999 B=69.499 连续 0.50 → 0 块", 0, RiverCrossSection.waterBlocks(69.999, 69.499));
    }

    /** 非有限输入 ⇒ 0 块（不抛、不返回负数）。 */
    private static void testWaterBlocksNonFinite() {
        eqInt("S=NaN → 0", 0, RiverCrossSection.waterBlocks(Double.NaN, 69.0));
        eqInt("B=NaN → 0", 0, RiverCrossSection.waterBlocks(70.0, Double.NaN));
        eqInt("S=+Inf → 0", 0, RiverCrossSection.waterBlocks(Double.POSITIVE_INFINITY, 69.0));
        eqInt("B=-Inf → 0", 0, RiverCrossSection.waterBlocks(70.0, Double.NEGATIVE_INFINITY));
    }

    // ⑤ 整条断面的水柱分布

    /**
     * 平坦侵蚀地形（{@code E=S=64}）下的整条断面：
     * 中心水柱 = {@code floor(S)-floor(B)}，岸缘仍有 1 块（保住边缘湿列），且由岸向中心单调不减。
     * <p>字面量预期：{@code W=4} 中心 3 / 中段 2 / 岸 1；{@code W=12} 中心 4 / 中段 3 / 岸 1。</p>
     */
    private static void testSectionColumnProfile() {
        double s = 64.0;
        eqInt("W=4 中心水柱", 3, columnBlocks(4, s, 0.0));
        eqInt("W=4 r=0.5 水柱", 2, columnBlocks(4, s, 1.0));
        eqInt("W=4 岸缘水柱", 1, columnBlocks(4, s, 2.0));
        eqInt("W=12 中心水柱", 4, columnBlocks(12, s, 0.0));
        eqInt("W=12 r=0.5 水柱", 3, columnBlocks(12, s, 3.0));
        eqInt("W=12 岸缘水柱", 1, columnBlocks(12, s, 6.0));
        eqInt("W=1 全断面 1 块（细河不挖深）", 1, columnBlocks(1, s, 0.0));
        eqInt("W=1 岸缘 1 块", 1, columnBlocks(1, s, 0.5));

        for (int w : new int[]{1, 4, 6, 12}) {
            double half = w / 2.0, hc = RiverCrossSection.centerDepth(w);
            int prev = Integer.MAX_VALUE;
            for (int k = 0; k <= 24; k++) {
                double d = half * k / 24.0;
                int blocks = columnBlocks(w, s, d);
                check("湿列不丢 W=" + w + " d=" + d, blocks >= 1, "blocks=" + blocks);
                check("水柱横向单调 W=" + w + " d=" + d, blocks <= prev, "prev=" + prev + " blocks=" + blocks);
                prev = blocks;
            }
            check("W=" + w + " 中心水柱不低于目标 floor",
                    columnBlocks(w, s, 0.0) >= (int) Math.floor(hc), "hc=" + hc);
        }
    }

    /** 单列：平坦地形 {@code E=S} 处的整数水柱。 */
    private static int columnBlocks(int width, double surface, double distance) {
        double hc = RiverCrossSection.centerDepth(width);
        double target = RiverCrossSection.targetDepth(hc, distance, width / 2.0);
        double b = RiverCrossSection.bed(surface, surface, target);
        return RiverCrossSection.waterBlocks(surface, b);
    }

    // 输出与断言工具

    /** 打印逐列断面（人眼核对 U 形；不作为判据）。 */
    private static void crossSectionTable(int width, double half, double hc) {
        StringBuilder sb = new StringBuilder(String.format("  断面 W=%-2d Hc=%.6f |", width, hc));
        int steps = width == 1 ? 2 : 8;
        for (int k = -steps; k <= steps; k++) {
            double d = half * k / steps;
            sb.append(String.format(" %6.3f", RiverCrossSection.targetDepth(hc, d, half)));
        }
        System.out.println(sb);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            return;
        }
        fail++;
        FAILS.add(what + " | " + detail);
        System.out.println("FAIL " + what + " | " + detail);
    }

    private static void near(String what, double expected, double actual, double tol) {
        check(what, Math.abs(expected - actual) <= tol,
                String.format("expected=%.15g actual=%.15g tol=%g", expected, actual, tol));
    }

    private static void eqInt(String what, int expected, int actual) {
        check(what, expected == actual, "expected=" + expected + " actual=" + actual);
    }
}
