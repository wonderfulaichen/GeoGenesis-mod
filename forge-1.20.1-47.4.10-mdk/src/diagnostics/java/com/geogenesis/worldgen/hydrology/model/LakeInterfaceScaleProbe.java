package com.geogenesis.worldgen.hydrology.model;

import java.util.List;
import java.util.Map;

/**
 * <h1>{@link LakeInterfaceScale} 的判据探针（独立 main，FAIL ⇒ 非零退出）</h1>
 *
 * <p>全部用<b>合成掩码</b>（{@code lake[]} + {@code basinId[]}）驱动，不启动 MC、不生成地形、
 * 不求解湖泊 —— 只验证"量的定义"是否按契约成立。真实地形上的比例是否好看<b>不在本探针范围</b>。</p>
 *
 * <h2>为什么窗口要开得很大（400×400 格 = 400 block @step 1）</h2>
 * <p>走廊/横向各 {@value LakeInterfaceScale#REACH_BLOCKS} block，且<b>撞窗口即 UNKNOWN</b>。
 * 若合成窗口只有几十格宽，除了"截断用例"以外每个用例都会因窗口裁切而 UNKNOWN
 * ⇒ 测不到真正要测的量。故除截断用例外，窗口一律 400 格，接口置于湖附近的中部。</p>
 *
 * <h2>覆盖（每条失败即 exit 1）</h2>
 * <ol>
 *   <li>小湖（3×3）：宽度只由湿区几何给出（与集水面积/Q 无关）⇒ 半宽夹到 0.5；</li>
 *   <li>大湖：半宽 = 湿宽/16 且不超 6；</li>
 *   <li>狭长湖：不退化成"总面积等效直径"（并打印该直径作反证）；</li>
 *   <li>饱和：一侧仍湿已满 96 ⇒ 至少 192 横宽、半宽饱和 6、status=OK_SATURATED；</li>
 *   <li>身份缺失：无 basinId / basin&lt;0 / lake[]==null ⇒ UNKNOWN_NO_IDENTITY + NaN；</li>
 *   <li>相邻湖防串盆：紧邻的另一盆地水体不计入本湖宽度；</li>
 *   <li>窗口截断（横向撞窗 / 走廊撞窗）⇒ UNKNOWN_CLIPPED + NaN，不得当小湖；</li>
 *   <li>step=1/2/4 同一世界几何：半宽差 ≤ step/8（= 宽度量化误差 2·step 的推论）；</li>
 *   <li>方向：对向接口同一宽度、缩放不变、斜向可用、零向量/NaN ⇒ UNKNOWN_DEGENERATE、
 *       指离湖 ⇒ UNKNOWN_DRY；</li>
 *   <li>接口格在干岸：沿 inward 找到真实湿湖（不出现"岸线交点湖宽=0"）；</li>
 *   <li>显式 basin：允许 ≠ basinId[cell]（出湖 sourceBasinOf 场景），不存在的 basin ⇒ UNKNOWN_DRY；</li>
 *   <li>结构性误用（nx/step/length/cell）⇒ IllegalArgumentException；</li>
 *   <li>采样次数有界（≤ 2 侧 × 97 偏移 × 193 走廊样本）、可复现。</li>
 * </ol>
 */
public final class LakeInterfaceScaleProbe {

    /** 除"截断用例"外的合成窗口边长（格）。 */
    private static final int W = 400;

    /** 全干窗口的理论采样上界：2 侧 × 97 偏移 × 193 走廊样本。 */
    private static final int SAMPLE_BOUND = 2 * 97 * 193;

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("=== LakeInterfaceScale（河湖接口横向尺度）===");
        System.out.printf("REACH=%.0f pitch=%.1f half=width/16 clamps=[%.1f,%.1f] smallBodyWidth=%.0f window=%d%n%n",
                LakeInterfaceScale.REACH_BLOCKS, LakeInterfaceScale.LATERAL_PITCH_BLOCKS,
                LakeInterfaceScale.MIN_HALF, LakeInterfaceScale.MAX_HALF,
                LakeInterfaceScale.SMALL_BODY_WIDTH, W);
        smallPond();
        largeLake();
        elongatedLake();
        saturatedBody();
        missingIdentity();
        adjacentBasins();
        windowClipping();
        stepGeneralisation();
        directionConsistency();
        dryBankMouth();
        explicitBasin();
        misuse();
        boundedSamples();
        System.out.printf("%nCHECKS=%d FAILURES=%d%n", checks, failures);
        System.out.println(failures == 0
                ? "PASS: 合成掩码下的量测契约成立（比例好坏未评，需生产大图 A/B）"
                : "FAIL: 见上面 [FAIL] 行");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ① 小湖 ------------------------------------------------

    private static void smallPond() {
        // 3×3 水塘（9 格）；接口在 x=198 干岸，湖 x∈[200,202] z∈[200,202]
        Mask m = new Mask(W, W, 1);
        m.rect(200, 200, 202, 202, 0);
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(198, 201), 0, 1, 0);
        chk("小湖: 湿宽=2（两侧各 1 格）", near(s.transverseBlocks(), 2), "width=" + s.transverseBlocks());
        chk("小湖: 半宽夹到下限 0.5", near(s.maxHalf(), 0.5), "half=" + s.maxHalf());
        chk("小湖: status=OK_SMALL_BODY（<8 格的比例离散例外）",
                LakeInterfaceScale.OK_SMALL_BODY.equals(s.status()), s.status());
        chk("小湖: 上限与集水面积/流量无关（只看湿区几何）", s.usable(), "half=" + s.maxHalf());
    }

    // ② 大湖 ------------------------------------------------

    private static void largeLake() {
        Mask m = new Mask(W, W, 1);
        m.rect(160, 160, 300, 240, 3);
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(150, 200), 3, 1, 0);
        chk("大湖: 湿宽=80（z 方向 ±40）", near(s.transverseBlocks(), 80), "width=" + s.transverseBlocks());
        chk("大湖: 半宽=湿宽/16=5（未触上下限）", near(s.maxHalf(), 5.0), "half=" + s.maxHalf());
        chk("大湖: status=OK", LakeInterfaceScale.OK.equals(s.status()), s.status());
        chk("大湖: 半宽不超过 6", s.maxHalf() <= LakeInterfaceScale.MAX_HALF, "half=" + s.maxHalf());
    }

    // ③ 狭长湖 ----------------------------------------------

    private static void elongatedLake() {
        // 341×3 = 1023 格，面积等效直径 ≈ 36.1 ⇒ 旧"面积定宽"会允许半宽 ≈2.26
        Mask m = new Mask(W, W, 1);
        m.rect(40, 198, 380, 200, 0);
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(30, 199), 0, 1, 0);
        double areaDiameter = 2 * Math.sqrt((341 * 3) / Math.PI);
        System.out.printf("  [info] 狭长湖: 面积等效直径≈%.1f ⇒ 面积判据允许半宽 %.2f；本判据给出 %.2f%n",
                areaDiameter, areaDiameter / 16, s.maxHalf());
        chk("狭长湖: 湿宽=2（横向仅 3 格）", near(s.transverseBlocks(), 2), "width=" + s.transverseBlocks());
        chk("狭长湖: 半宽=0.5，未按面积放宽", near(s.maxHalf(), 0.5), "half=" + s.maxHalf());
        chk("狭长湖: 严格小于面积等效直径允许值",
                s.maxHalf() < areaDiameter / 16 - 1e-9, "half=" + s.maxHalf());
        chk("狭长湖: 沿长轴走廊仍命中水（不是 UNKNOWN_DRY）",
                !LakeInterfaceScale.UNKNOWN_DRY.equals(s.status()), s.status());
    }

    // ④ 饱和 ------------------------------------------------

    private static void saturatedBody() {
        Mask m = new Mask(W, W, 1);
        m.rect(100, 20, 300, 380, 0);
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(90, 200), 0, 1, 0);
        double lower = 2 * LakeInterfaceScale.REACH_BLOCKS;   // 192
        System.out.printf("  [info] 饱和: width=%.1f（下界 %.0f）half=%.2f samples=%d%n",
                s.transverseBlocks(), lower, s.maxHalf(), s.samples());
        chk("饱和: status=OK_SATURATED", LakeInterfaceScale.OK_SATURATED.equals(s.status()), s.status());
        chk("饱和: 湿宽 ≥ 192（96×2 的下界）", s.transverseBlocks() >= lower - 1e-9,
                "width=" + s.transverseBlocks());
        chk("饱和: 半宽饱和到 6", near(s.maxHalf(), LakeInterfaceScale.MAX_HALF), "half=" + s.maxHalf());
        chk("饱和: 采样仍在解析上界内（湖外 10 格干岸 ⇒ 每偏移 ~21 次）",
                s.samples() <= SAMPLE_BOUND && s.samples() > 2 * 97, "samples=" + s.samples());
        chk("饱和: 不是 UNKNOWN（有界采样足够给结论）", s.usable(), "status=" + s.status());

        // 接口在水里（湿区连续）：每个横向偏移的首个走廊样本即命中 ⇒ 成本 ≈ 2×97
        var inside = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(150, 200), 0, 1, 0);
        System.out.printf("  [info] 湖内接口: samples=%d（≈2×97 首样本命中）width=%.1f%n",
                inside.samples(), inside.transverseBlocks());
        chk("饱和: 湖内接口每偏移首样本即命中 ⇒ samples ≤ 2×97+4",
                inside.samples() <= 2 * 97 + 4, "samples=" + inside.samples());
        chk("饱和: 湖内接口同样误判为饱和（几何一致）",
                LakeInterfaceScale.OK_SATURATED.equals(inside.status()), inside.status());
    }

    // ⑤ 身份缺失 --------------------------------------------

    private static void missingIdentity() {
        Mask m = new Mask(64, 64, 1);
        m.rect(20, 20, 22, 22, 0);
        int cell = m.cell(19, 21);

        // (a) 旧 7 参构造 ⇒ basinId == null
        var legacy = new LakeWaterBalance.Result(new double[64 * 64], m.lake, List.of(), 0,
                1.0, Map.of(), Map.of());
        var a = LakeInterfaceScale.measure(legacy, 64, 64, 1, cell, 1, 0);
        chk("身份缺失: basinId==null ⇒ UNKNOWN_NO_IDENTITY",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(a.status()), a.status());
        chk("身份缺失: 上限 NaN（不得当小湖）", Double.isNaN(a.maxHalf()), "half=" + a.maxHalf());
        chk("身份缺失: usable()==false", !a.usable(), "usable=" + a.usable());

        // (b) 干岸接口 ⇒ basinId=-1，隐式身份缺失
        var b = LakeInterfaceScale.measure(m.result(), 64, 64, 1, cell, 1, 0);
        chk("身份缺失: 干岸接口 basinId=-1 ⇒ UNKNOWN_NO_IDENTITY",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(b.status()), b.status());
        chk("身份缺失: 上限 NaN", Double.isNaN(b.maxHalf()), "half=" + b.maxHalf());

        // (c) 显式 basin=-1
        var c = LakeInterfaceScale.measure(m.result(), 64, 64, 1, cell, -1, 1, 0);
        chk("身份缺失: 显式 basin<0 ⇒ UNKNOWN_NO_IDENTITY",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(c.status()), c.status());

        // (d) lake 数组为 null
        var none = new LakeWaterBalance.Result(new double[4], null, List.of(), 0, 1.0, Map.of(), Map.of());
        var d = LakeInterfaceScale.measure(none, 2, 2, 1, 0, 1, 0);
        chk("身份缺失: lake[]==null ⇒ UNKNOWN_NO_IDENTITY",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(d.status()), d.status());

        // (e) lakes 本身为 null
        var e = LakeInterfaceScale.measure(null, 2, 2, 1, 0, 1, 0);
        chk("身份缺失: lakes==null ⇒ UNKNOWN_NO_IDENTITY（不 NPE）",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(e.status()), e.status());
        chk("身份缺失: lakes==null ⇒ NaN", Double.isNaN(e.maxHalf()), "half=" + e.maxHalf());
    }

    // ⑥ 相邻湖防串盆 ----------------------------------------

    private static void adjacentBasins() {
        // A: x∈[190,202] basin 0；B: x∈[203,215] basin 1 —— 中间【无干岸缝隙】
        Mask m = new Mask(W, W, 1);
        m.rect(190, 180, 202, 220, 0);
        m.rect(203, 180, 215, 220, 1);
        // 若把"任何湖格"都算湿，横向会越过 x=203 把 B 计入 ⇒ 宽度 25 而不是 12
        var a = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(196, 190), 0, 0, 1);
        var b = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(209, 190), 1, 0, 1);
        System.out.printf("  [info] 防串盆: A 宽=%.1f B 宽=%.1f（若串盆两者都会到 25）%n",
                a.transverseBlocks(), b.transverseBlocks());
        chk("防串盆: 本盆湿宽=12（B 湖不计入）", near(a.transverseBlocks(), 12), "width=" + a.transverseBlocks());
        chk("防串盆: 对侧盆地同样只量到自己", near(b.transverseBlocks(), 12), "width=" + b.transverseBlocks());
        chk("防串盆: 半宽=12/16=0.75", near(a.maxHalf(), 12 / 16.0), "half=" + a.maxHalf());
    }

    // ⑦ 窗口截断 --------------------------------------------

    private static void windowClipping() {
        // (a) 横向一直湿到窗口边：64 格窗口里 z 全覆盖
        Mask wide = new Mask(64, 64, 1);
        wide.rect(30, 0, 60, 63, 0);
        var s1 = LakeInterfaceScale.measure(wide.result(), 64, 64, 1, wide.cell(25, 30), 0, 1, 0);
        chk("截断: 横向撞窗 ⇒ UNKNOWN_CLIPPED",
                LakeInterfaceScale.UNKNOWN_CLIPPED.equals(s1.status()), s1.status());
        chk("截断: 上限 NaN（不得当小湖）", Double.isNaN(s1.maxHalf()), "half=" + s1.maxHalf());
        chk("截断: usable()==false", !s1.usable(), "usable=" + s1.usable());

        // (b) 走廊撞窗：湖极窄且 x 直通窗口边 ⇒ 干偏移的走廊走到窗前仍未见水
        Mask corridor = new Mask(64, 64, 1);
        corridor.rect(30, 20, 63, 22, 0);
        var s2 = LakeInterfaceScale.measure(corridor.result(), 64, 64, 1, corridor.cell(25, 21), 0, 1, 0);
        chk("截断: 走廊撞窗 ⇒ UNKNOWN_CLIPPED（保守，不当小湖）",
                LakeInterfaceScale.UNKNOWN_CLIPPED.equals(s2.status()), s2.status());
        chk("截断: 走廊撞窗时上限 NaN", Double.isNaN(s2.maxHalf()), "half=" + s2.maxHalf());
    }

    // ⑧ step=1/2/4 ------------------------------------------

    private static void stepGeneralisation() {
        double base = Double.NaN;
        double worstWidth = 0;
        double worstHalf = 0;
        for (int step : new int[]{1, 2, 4}) {
            Mask m = new Mask(256 / step, 256 / step, step);
            m.rectBlock(60, 100, 200, 140, 0);
            int cell = m.blockCell(48, 120);
            var s = LakeInterfaceScale.measure(m.result(), m.nx, m.nz, step, cell, 0, 1, 0);
            System.out.printf("  [info] step=%d: width=%.1f half=%.3f status=%s samples=%d%n",
                    step, s.transverseBlocks(), s.maxHalf(), s.status(), s.samples());
            chk("step=" + step + ": status=OK（真实湿宽 40 格）",
                    LakeInterfaceScale.OK.equals(s.status()), s.status());
            chk("step=" + step + ": width≈40（格心取整误差 ≤ 2·step）",
                    Math.abs(s.transverseBlocks() - 40) <= 2.0 * step + 1e-9,
                    "width=" + s.transverseBlocks());
            chk("step=" + step + ": 半宽在 [0.5,6] 内",
                    s.maxHalf() >= LakeInterfaceScale.MIN_HALF && s.maxHalf() <= LakeInterfaceScale.MAX_HALF,
                    "half=" + s.maxHalf());
            if (Double.isNaN(base)) base = s.maxHalf();
            worstWidth = Math.max(worstWidth, Math.abs(s.transverseBlocks() - 40));
            worstHalf = Math.max(worstHalf, Math.abs(s.maxHalf() - base));
        }
        System.out.printf("  [info] step 泛化: 最大宽度偏差=%.1f block 最大半宽偏差=%.3f%n",
                worstWidth, worstHalf);
        chk("step 泛化: 宽度偏差 ≤ 2·step_max=8（世界距离量测，不随 step 放大）",
                worstWidth <= 8 + 1e-9, "worstWidth=" + worstWidth);
        chk("step 泛化: 半宽偏差 ≤ step_max/8=0.5（= 2·step/16 的推论）",
                worstHalf <= 0.5 + 1e-9, "worstHalf=" + worstHalf);
    }

    // ⑨ 方向一致性 ------------------------------------------

    private static void directionConsistency() {
        Mask m = new Mask(W, W, 1);
        m.rect(160, 160, 240, 240, 0);
        var r = m.result();
        var east = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0, 1, 0);
        var west = LakeInterfaceScale.measure(r, W, W, 1, m.cell(250, 200), 0, -1, 0);
        chk("方向: 对向接口给出同一湿宽", near(east.transverseBlocks(), west.transverseBlocks()),
                east.transverseBlocks() + " vs " + west.transverseBlocks());
        chk("方向: 对向接口给出同一半宽", near(east.maxHalf(), west.maxHalf()),
                east.maxHalf() + " vs " + west.maxHalf());

        var scaled = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0, 3.7, 0);
        chk("方向: 缩放 inward 结果不变（内部归一化）",
                near(east.maxHalf(), scaled.maxHalf())
                        && near(east.transverseBlocks(), scaled.transverseBlocks()),
                scaled.maxHalf() + " width=" + scaled.transverseBlocks());

        var diag = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0,
                Math.sqrt(0.5), Math.sqrt(0.5));
        chk("方向: 斜向 inward 仍可用（有限、在域内、宽度非零）",
                diag.usable() && diag.transverseBlocks() > 0
                        && diag.maxHalf() >= LakeInterfaceScale.MIN_HALF
                        && diag.maxHalf() <= LakeInterfaceScale.MAX_HALF,
                "half=" + diag.maxHalf() + " width=" + diag.transverseBlocks());

        var zero = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0, 0, 0);
        chk("方向: 零向量 ⇒ UNKNOWN_DEGENERATE",
                LakeInterfaceScale.UNKNOWN_DEGENERATE.equals(zero.status()), zero.status());
        chk("方向: 零向量 ⇒ NaN", Double.isNaN(zero.maxHalf()), "half=" + zero.maxHalf());
        var nan = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0, Double.NaN, 1);
        chk("方向: NaN ⇒ UNKNOWN_DEGENERATE",
                LakeInterfaceScale.UNKNOWN_DEGENERATE.equals(nan.status()), nan.status());

        var away = LakeInterfaceScale.measure(r, W, W, 1, m.cell(150, 200), 0, -1, 0);
        chk("方向: 指离湖 ⇒ UNKNOWN_DRY（不是小湖）",
                LakeInterfaceScale.UNKNOWN_DRY.equals(away.status()), away.status());
        chk("方向: 指离湖 ⇒ NaN", Double.isNaN(away.maxHalf()), "half=" + away.maxHalf());
    }

    // ⑩ 干岸接口 --------------------------------------------

    private static void dryBankMouth() {
        // 接口 x=150 在干岸，湖从 x=160 起：若按"岸线交点湖宽"会得 0
        Mask m = new Mask(W, W, 1);
        m.rect(160, 190, 240, 210, 0);
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(150, 200), 0, 1, 0);
        chk("干岸接口: 沿 inward 找到真实湿湖（宽=20，非 0）",
                near(s.transverseBlocks(), 20), "width=" + s.transverseBlocks());
        chk("干岸接口: 半宽=20/16=1.25（避开岸零宽）", near(s.maxHalf(), 1.25), "half=" + s.maxHalf());
        chk("干岸接口: status=OK", LakeInterfaceScale.OK.equals(s.status()), s.status());
    }

    // ⑪ 显式 basin ------------------------------------------

    private static void explicitBasin() {
        Mask m = new Mask(W, W, 1);
        m.rect(180, 190, 240, 210, 0);
        var r = m.result();
        int dryCell = m.cell(170, 200);
        var implicit = LakeInterfaceScale.measure(r, W, W, 1, dryCell, 1, 0);
        chk("显式 basin: 隐式身份缺失 ⇒ UNKNOWN_NO_IDENTITY",
                LakeInterfaceScale.UNKNOWN_NO_IDENTITY.equals(implicit.status()), implicit.status());
        var explicit = LakeInterfaceScale.measure(r, W, W, 1, dryCell, 0, 1, 0);
        chk("显式 basin: 传入 sourceBasinOf=0 ⇒ 正常测量（宽=20）",
                near(explicit.transverseBlocks(), 20), "width=" + explicit.transverseBlocks());
        var absent = LakeInterfaceScale.measure(r, W, W, 1, dryCell, 5, 1, 0);
        chk("显式 basin: 不存在的 basin ⇒ UNKNOWN_DRY（不借相邻湖）",
                LakeInterfaceScale.UNKNOWN_DRY.equals(absent.status()), absent.status());
        chk("显式 basin: 不存在 basin 时上限 NaN", Double.isNaN(absent.maxHalf()), "half=" + absent.maxHalf());
        var inLake = LakeInterfaceScale.measure(r, W, W, 1, m.cell(190, 200), 1, 0);
        chk("显式 basin: 隐式身份在湖内格可用（= basinId[cell]）",
                inLake.usable() && near(inLake.transverseBlocks(), 20),
                "width=" + inLake.transverseBlocks());
    }

    // ⑫ 结构性误用 ------------------------------------------

    private static void misuse() {
        Mask m = new Mask(16, 16, 1);
        m.rect(2, 2, 4, 4, 0);
        var r = m.result();
        chk("误用: nx=0 ⇒ IllegalArgumentException",
                throwsIae(() -> LakeInterfaceScale.measure(r, 0, 16, 1, 0, 1, 0)));
        chk("误用: step=0 ⇒ IllegalArgumentException",
                throwsIae(() -> LakeInterfaceScale.measure(r, 16, 16, 0, 0, 1, 0)));
        chk("误用: cell 越界 ⇒ IllegalArgumentException",
                throwsIae(() -> LakeInterfaceScale.measure(r, 16, 16, 1, 256, 1, 0)));
        var shortArray = new LakeWaterBalance.Result(new double[16 * 16], new boolean[16], List.of(),
                0, 1.0, Map.of(), Map.of(), new int[16], new int[0]);
        chk("误用: lake.length != nx*nz ⇒ IllegalArgumentException",
                throwsIae(() -> LakeInterfaceScale.measure(shortArray, 16, 16, 1, 0, 1, 0)));
    }

    // ⑬ 采样上界 --------------------------------------------

    private static void boundedSamples() {
        Mask m = new Mask(W, W, 1);          // 全干窗口 ⇒ 最坏情况
        var s = LakeInterfaceScale.measure(m.result(), W, W, 1, m.cell(200, 200), 0, 1, 0);
        System.out.printf("  [info] 最坏采样（全干窗口）: samples=%d（解析上界 %d）status=%s%n",
                s.samples(), SAMPLE_BOUND, s.status());
        chk("采样: 全干窗口仍 ≤ 2×97×193 次数组读", s.samples() <= SAMPLE_BOUND,
                "samples=" + s.samples());
        chk("采样: 全干 ⇒ UNKNOWN_DRY", LakeInterfaceScale.UNKNOWN_DRY.equals(s.status()), s.status());

        Mask lake = new Mask(W, W, 1);
        lake.rect(160, 160, 240, 240, 0);
        int cell = lake.cell(150, 200);
        var a = LakeInterfaceScale.measure(lake.result(), W, W, 1, cell, 0, 1, 0);
        var b = LakeInterfaceScale.measure(lake.result(), W, W, 1, cell, 0, 1, 0);
        chk("采样: 同参数重复调用逐位相同（确定性）",
                a.samples() == b.samples() && Double.compare(a.maxHalf(), b.maxHalf()) == 0
                        && Double.compare(a.transverseBlocks(), b.transverseBlocks()) == 0
                        && a.status().equals(b.status()),
                "samples=" + a.samples() + "/" + b.samples());
        chk("采样: 正常湖每次调用远低于上界", a.samples() < SAMPLE_BOUND / 4,
                "samples=" + a.samples());
    }

    // 工具 --------------------------------------------------

    private static void chk(String name, boolean ok) {
        chk(name, ok, null);
    }

    private static void chk(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%s %s %s%n", ok ? "[OK]  " : "[FAIL]", name, detail == null ? "" : "| " + detail);
    }

    private static boolean near(double a, double b) {
        return Double.isFinite(a) && Math.abs(a - b) < 1e-9;
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /** 合成掩码：只填 lake[] 与 basinId[]；其余字段给最小合法值（被测类不读它们）。 */
    private static final class Mask {
        final int nx;
        final int nz;
        final int step;
        final boolean[] lake;
        final int[] basinId;

        Mask(int nx, int nz, int step) {
            this.nx = nx;
            this.nz = nz;
            this.step = step;
            this.lake = new boolean[nx * nz];
            this.basinId = new int[nx * nz];
            java.util.Arrays.fill(basinId, -1);
        }

        /** 逐格矩形填充（下标 = cell，不是 block）。 */
        void rect(int x0, int z0, int x1, int z1, int basin) {
            for (int x = Math.max(0, x0); x <= Math.min(nx - 1, x1); x++) {
                for (int z = Math.max(0, z0); z <= Math.min(nz - 1, z1); z++) {
                    int k = x * nz + z;
                    lake[k] = true;
                    basinId[k] = basin;
                }
            }
        }

        /** 以 block 坐标填充矩形（step 泛化：同一世界几何，不同网格）。 */
        void rectBlock(int bx0, int bz0, int bx1, int bz1, int basin) {
            rect(Math.round(bx0 / (float) step), Math.round(bz0 / (float) step),
                    Math.round(bx1 / (float) step), Math.round(bz1 / (float) step), basin);
        }

        int cell(int x, int z) {
            return x * nz + z;
        }

        int blockCell(int bx, int bz) {
            return Math.round(bx / (float) step) * nz + Math.round(bz / (float) step);
        }

        LakeWaterBalance.Result result() {
            return new LakeWaterBalance.Result(new double[nx * nz], lake, List.of(), 0, 1.0,
                    Map.of(), Map.of(), basinId, new int[0]);
        }
    }
}
