package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>{@link LakeWidthEnvelope} 判据探针（独立 main，FAIL ⇒ 非零退出）</h1>
 *
 * <p>合成几何 + 合成 {@code lake[]/basinId[]/basinOutlet[]}，不启动 MC、不求解湖泊。</p>
 *
 * <h2>期望距离一律手算，不调实现</h2>
 * <p>凡断言"上游节点 cap 应是多少"，期望值都写成 {@code scale + 手数出来的 block 数 / 32}，
 * <b>不</b>调用 {@link LakeWidthEnvelope} 的内部量。否则实现错、期望跟着错，测不出来。</p>
 *
 * <h2>几何必须照生产语义</h2>
 * <ul>
 *   <li>入湖：骨架把<b>湖格本身</b>追加为末节点 ⇒ 末节点必须落在湖内。</li>
 *   <li>出湖：源点是<b>非湖</b>的溢出口格 ⇒ 身份只能靠 {@code sourceBasinOf}。</li>
 * </ul>
 */
public final class EnvelopeProbe {

    /** 手算锥值：{@code scale + blocks/32}。 */
    private static double coneByHand(double scale, double blocks) {
        return Math.min(LakeWidthEnvelope.MAX_HALF,
                Math.max(LakeWidthEnvelope.MIN_HALF, scale + blocks / 32.0));
    }

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("=== LakeWidthEnvelope ===");
        System.out.printf("MAX_HALF=%.1f MIN_HALF=%.1f slope=1/32%n%n",
                LakeWidthEnvelope.MAX_HALF, LakeWidthEnvelope.MIN_HALF);
        noLakes();
        mouthAtInterface();
        seriesThreeTracesHandComputed();
        midTrunkConfluenceHandComputed();
        resampledShapesUnequalCoarseIndex();
        stackedContinuity();
        dryBankUnknown();
        missingIdentity();
        inheritedAnchor();
        noRivers();
        cyclesAndDegenerate();
        evidenceRows();
        determinism();
        System.out.printf("%nCHECKS=%d FAILURES=%d%n", checks, failures);
        System.out.println(failures == 0 ? "PASS" : "FAIL: 见上面 [FAIL] 行");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ① 无湖

    private static void noLakes() {
        Fixture f = new Fixture(400, 1);
        int[] cells = f.line(100, 200, 300, 200);
        var res = f.build(List.of(cells), List.of(), null, Map.of());
        chk("无湖: 全 MAX_HALF", allEqual(res.limits().get(0), LakeWidthEnvelope.MAX_HALF), brief(res.limits().get(0)));
        chk("无湖: 无证据", res.interfaces().isEmpty(), "");
        chk("无湖: 行长 == 点数", res.limits().get(0).length == cells.length, "");
        chk("无湖: applied=0", res.applied() == 0, "");
    }

    // ② 接口处严格等于 scale

    private static void mouthAtInterface() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 0);
        int[] cells = f.line(20, 200, 150, 200);
        var traces = List.of(f.trace(cells, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var res = f.build(List.of(cells), traces, null, Map.of());
        double[] row = res.limits().get(0);
        double scale = LakeInterfaceScale.measure(f.lake, f.basinId, f.n, f.n, f.step,
                cells[cells.length - 1], 0, 1, 0).maxHalf();
        System.out.printf("  [info] 接口: scale=%.4f 末=%.4f 首=%.4f %s%n",
                scale, row[row.length - 1], row[0], kinds(res));
        chk("接口: basin=0", res.interfaces().size() == 1 && res.interfaces().get(0).basin() == 0, kinds(res));
        chk("接口: 末节点 cap == scale", near(row[row.length - 1], scale), row[row.length - 1] + " vs " + scale);
        chk("接口: 已被收紧", row[row.length - 1] < LakeWidthEnvelope.MAX_HALF - 1e-9, "");
        // 手算：末节点往前 130 block ⇒ scale + 130/32 已过 6
        chk("接口: 首节点 = 6（手算 scale+130/32 已饱和）",
                near(row[0], coneByHand(scale, 130)), "cap0=" + row[0]);
        chk("接口: 单调收紧", monotoneNonIncreasing(row), brief(row));
        chk("接口: 值域 OK", inRange(row), "");
        chk("接口: 连续", slopeOk(row), "worst=" + worstSlope(row));
    }

    // ③ 三段串联：间距手算，跨 trace 不重置

    private static void seriesThreeTracesHandComputed() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 0);
        // A x[20..70] 51 点, B x[71..110] 40 点, C x[111..150] 40 点；末端 x=150 = 首个湖格
        int[] a = f.line(20, 200, 70, 200);
        int[] b = f.line(71, 200, 110, 200);
        int[] c = f.line(111, 200, 150, 200);
        // 串联：相邻段端点相距 1 block（几何间隙 = 1）
        var traces = List.of(
                f.trace(a, DeterministicSkeleton.End.JOIN, false),
                f.trace(b, DeterministicSkeleton.End.JOIN, false),
                f.trace(c, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var rivers = List.of(
                f.river(new int[]{0}, a, DeterministicSkeleton.RiverLink.CONNECTED, 1, 0),
                f.river(new int[]{1}, b, DeterministicSkeleton.RiverLink.CONNECTED, 2, 0),
                f.river(new int[]{2}, c, DeterministicSkeleton.RiverLink.TERMINAL, -1, -1));
        var res = f.build(List.of(a, b, c), traces, rivers, Map.of());
        double[] ra = res.limits().get(0), rb = res.limits().get(1), rc = res.limits().get(2);
        double scale = rc[rc.length - 1];
        System.out.printf("  [info] 三段: scale=%.4f A首=%.4f A尾=%.4f B首=%.4f B尾=%.4f C首=%.4f%n",
                scale, ra[0], ra[ra.length - 1], rb[0], rb[rb.length - 1], rc[0]);
        // 手算（全部从接口 x=150 沿几何回溯，不经实现）：
        //   C 弧长 0..39（x=111..150）；B 尾 x=110 ↔ C 首 x=111 间隙 1
        //   接口 39 → B尾 39+1=40 → B首 40+39=79 → A尾 79+1=80
        chk("三段: C 首 = scale + 39/32", near(rc[0], coneByHand(scale, 39)),
                rc[0] + " vs " + coneByHand(scale, 39));
        chk("三段: B 尾 = scale + 40/32（1 间隙 + 39）", near(rb[rb.length - 1], coneByHand(scale, 40)),
                rb[rb.length - 1] + " vs " + coneByHand(scale, 40));
        chk("三段: B 首 = scale + 79/32（B 内 39 + 40）", near(rb[0], coneByHand(scale, 79)),
                rb[0] + " vs " + coneByHand(scale, 79));
        chk("三段: A 尾 = scale + 80/32（79 + 1 间隙）",
                near(ra[ra.length - 1], coneByHand(scale, 80)),
                ra[ra.length - 1] + " vs " + coneByHand(scale, 80));
        chk("三段: 接缝不重置 —— 越上游越宽（A尾 > B尾 > C首）",
                ra[ra.length - 1] > rb[rb.length - 1] && rb[rb.length - 1] > rc[0],
                ra[ra.length - 1] + " > " + rb[rb.length - 1] + " > " + rc[0]);
        chk("三段: 全部单调朝接口", monotoneNonIncreasing(ra) && monotoneNonIncreasing(rb)
                && monotoneNonIncreasing(rc), "");
        chk("三段: 全部连续", slopeOk(ra) && slopeOk(rb) && slopeOk(rc), "");
    }

    // ④ 中段汇入：距离从汇入节点起算，不是从干流首节点

    private static void midTrunkConfluenceHandComputed() {
        Fixture f = new Fixture(600, 1);
        f.water(400, 280, 440, 320, 0);
        int[] main = f.line(100, 300, 400, 300);     // 301 点，末端=湖格
        int[] trib = f.line(300, 150, 300, 299);     // 150 点，尾 x=299 距干流 x=300 为 1
        int joinMain = indexOf(main, f.cell(300, 300));
        var traces = List.of(
                f.trace(main, DeterministicSkeleton.End.MOUTH_LAKE, false),
                f.trace(trib, DeterministicSkeleton.End.JOIN, false));
        var rivers = List.of(
                f.river(new int[]{0}, main, DeterministicSkeleton.RiverLink.TERMINAL, -1, -1),
                f.river(new int[]{1}, trib, DeterministicSkeleton.RiverLink.CONNECTED, 0, joinMain));
        var res = f.build(List.of(main, trib), traces, rivers, Map.of());
        double[] rm = res.limits().get(0), rt = res.limits().get(1);
        double scale = rm[rm.length - 1];
        int tEnd = rt.length - 1;
        System.out.printf("  [info] 中段: scale=%.4f 干流@join=%.4f 干流上游1=%.4f 支流尾=%.4f 支流首=%.4f%n",
                scale, rm[joinMain], rm[joinMain - 1], rt[tEnd], rt[0]);
        // 手算：joinMain 距接口 x=400 → 100 block
        chk("中段: 干流@join = scale + 100/32", near(rm[joinMain], coneByHand(scale, 100)),
                rm[joinMain] + " vs " + coneByHand(scale, 100));
        chk("中段: 干流@join-1 = scale + 101/32", near(rm[joinMain - 1], coneByHand(scale, 101)),
                rm[joinMain - 1] + " vs " + coneByHand(scale, 101));
        // 支流尾 x=299 距干流首节点 x=100 是 199，但真实距离应为 1+100=101
        chk("中段: 支流尾 = scale + 101/32（1 间隙 + 100，不是 199）",
                near(rt[tEnd], coneByHand(scale, 101)), rt[tEnd] + " vs " + coneByHand(scale, 101));
        chk("中段: 支流尾 ≠ 按干流首节点算的 scale+199/32（证明用 joinArc）",
                !near(rt[tEnd], coneByHand(scale, 199)), "got=" + rt[tEnd]);
        // 支流首 x=300,z=150 到汇入点 x=300,z=300 是 150 ⇒ 101+150=251 > 饱和
        chk("中段: 支流首饱和到 6", near(rt[0], LakeWidthEnvelope.MAX_HALF), "trib0=" + rt[0]);
        chk("中段: 支流单调朝接点收紧", monotoneNonIncreasing(rt), "");
        chk("中段: 干流接点上游比接点宽", rm[joinMain - 1] > rm[joinMain], "");
        chk("中段: 干流接点下游不宽于接点", rm[joinMain + 1] <= rm[joinMain] + 1e-9, "");
    }

    // ⑤ shape 重采样节点数 ≠ coarse 索引（防"拿 cells 下标当 shape 下标"）

    private static void resampledShapesUnequalCoarseIndex() {
        // step=2 ⇒ coarse 索引 × 2 = block。本用例刻意贴近接口，保证锥不饱和（否则测不出 joinArc）。
        Fixture f = new Fixture(600, 2);
        f.water(400, 295, 440, 305, 0);                  // 湖 block x∈[800,880] z∈[590,610]（z 宽 20 ⇒ 半宽 1.25）
        int[] main = f.line(100, 300, 400, 300);         // block (200,600)→(800,600)，末节点在湖内
        int[] trib = f.line(385, 250, 385, 299);         // block x=770, z 500→598
        int joinMain = indexOf(main, f.cell(385, 300));  // block (770,600)
        var traces = List.of(
                f.trace(main, DeterministicSkeleton.End.MOUTH_LAKE, false),
                f.trace(trib, DeterministicSkeleton.End.JOIN, false));
        var rivers = List.of(
                f.river(new int[]{0}, main, DeterministicSkeleton.RiverLink.TERMINAL, -1, -1),
                f.river(new int[]{1}, trib, DeterministicSkeleton.RiverLink.CONNECTED, 0, joinMain));
        var res = f.denseBuild(List.of(main, trib), traces, rivers, Map.of(), 2);
        double[] rm = res.limits().get(0), rt = res.limits().get(1);
        double scale = rm[rm.length - 1];
        int joinShape = joinMain * 2;                    // density=2 ⇒ shape 下标 = coarse 下标×2
        System.out.printf("  [info] 重采样: shapeLen=%d cellLen=%d scale=%.4f 干流@join=%.4f 支流尾=%.4f%n",
                rm.length, main.length, scale, rm[joinShape], rt[rt.length - 1]);
        chk("重采样: shape 长度 ≠ cells 长度（确实走了不同索引）",
                rm.length == main.length * 2 - 1, rm.length + " vs " + (main.length * 2 - 1));
        chk("重采样: 全行连续（无索引错位锯齿）",
                slopeOk(rm) && slopeOk(rt), "mainWorst=" + worstSlope(rm));
        chk("重采样: 接口仍在末节点收紧",
                rm[rm.length - 1] < LakeWidthEnvelope.MAX_HALF - 1e-9, "capT=" + rm[rm.length - 1]);
        // 手算：接口 block x=800，干流汇入点 block x=770 ⇒ 30 block
        chk("重采样: 干流@join = scale + 30/32（手算 block 距离）",
                near(rm[joinShape], coneByHand(scale, 30)),
                rm[joinShape] + " vs " + coneByHand(scale, 30));
        // 手算：支流尾 block z=598 距汇入点 z=600 为 2 ⇒ 30+2 = 32
        chk("重采样: 支流尾 = scale + 32/32（joinArc 未被 cells 下标污染）",
                near(rt[rt.length - 1], coneByHand(scale, 32)),
                rt[rt.length - 1] + " vs " + coneByHand(scale, 32));
        chk("重采样: 若误用 cells 下标当 shape 下标，支流尾会算成 scale+数倍的错值",
                !near(rt[rt.length - 1], coneByHand(scale, 2 * joinShape + 2)),
                "got=" + rt[rt.length - 1]);
        chk("重采样: 干流各节点单调朝接口", monotoneNonIncreasing(rm), "");
    }

    // ⑥ 双接口叠加

    private static void stackedContinuity() {
        Fixture f = new Fixture(600, 1);
        f.water(150, 280, 200, 320, 0);
        f.water(470, 280, 520, 320, 1);
        f.outlet(0, f.cell(201, 300));
        int[] cells = f.line(201, 300, 480, 300);
        var traces = List.of(f.trace(cells, DeterministicSkeleton.End.MOUTH_LAKE, true));
        var res = f.build(List.of(cells), traces, null, Map.of());
        double[] row = res.limits().get(0);
        int mid = row.length / 2;
        System.out.printf("  [info] 双接口: 首=%.3f 中=%.3f 尾=%.3f %s%n",
                row[0], row[mid], row[row.length - 1], kinds(res));
        chk("双接口: 认出两个接口", res.interfaces().size() == 2, kinds(res));
        chk("双接口: 都施加限制", res.applied() == 2, "" + res.applied());
        chk("双接口: 源接口 basin=0", res.interfaces().get(0).basin() == 0, "");
        chk("双接口: 两端收紧", row[0] < LakeWidthEnvelope.MAX_HALF - 1e-9
                && row[row.length - 1] < LakeWidthEnvelope.MAX_HALF - 1e-9, "");
        chk("双接口: 中段高于两端", row[mid] > row[0] && row[mid] > row[row.length - 1], "mid=" + row[mid]);
        chk("双接口: 连续", slopeOk(row), "worst=" + worstSlope(row));
        chk("双接口: 值域 OK", inRange(row), "");
    }

    // ⑦ 干岸末节点 ⇒ 诚实未知

    private static void dryBankUnknown() {
        Fixture f = new Fixture(400, 1);
        f.water(360, 180, 400, 220, 0);
        int[] cells = f.line(100, 200, 330, 200);
        var traces = List.of(f.trace(cells, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var res = f.build(List.of(cells), traces, null, Map.of());
        chk("干岸: UNKNOWN_NO_IDENTITY",
                res.interfaces().size() == 1 && LakeInterfaceScale.UNKNOWN_NO_IDENTITY
                        .equals(res.interfaces().get(0).status()), kinds(res));
        chk("干岸: 不施加限制", allEqual(res.limits().get(0), LakeWidthEnvelope.MAX_HALF), "");
        chk("干岸: applied=0", res.applied() == 0, "");
    }

    // ⑧ 身份缺失

    private static void missingIdentity() {
        Fixture f = new Fixture(400, 1);
        int[] cells = f.line(100, 200, 300, 200);
        var traces = List.of(f.trace(cells, DeterministicSkeleton.End.MOUTH_LAKE, true));
        var res = f.build(List.of(cells), traces, null, Map.of());
        chk("身份缺失: 不限制", allEqual(res.limits().get(0), LakeWidthEnvelope.MAX_HALF), "");
        chk("身份缺失: 两条证据", res.interfaces().size() == 2, kinds(res));
        chk("身份缺失: applied=0", res.applied() == 0, "");
        for (var i : res.interfaces()) {
            chk("身份缺失: " + i.kind() + " NaN + status", Double.isNaN(i.maxHalf())
                    && !i.status().isEmpty(), i.status());
        }
    }

    // ⑨ 跨区锚点

    private static void inheritedAnchor() {
        Fixture f = new Fixture(400, 1);
        int[] cells = f.line(100, 200, 300, 200);
        Map<Integer, Double> inherited = new HashMap<>();
        inherited.put(f.cell(100, 200), 1.0);
        inherited.put(f.cell(300, 200), 1.0);
        var res = f.build(List.of(cells), List.of(), null, inherited);
        double[] row = res.limits().get(0);
        int mid = row.length / 2;
        System.out.printf("  [info] 锚点: 首=%.3f 中=%.3f 尾=%.3f%n", row[0], row[mid], row[row.length - 1]);
        chk("锚点: 首=1.0", near(row[0], 1.0), "" + row[0]);
        chk("锚点: 尾=1.0", near(row[row.length - 1], 1.0), "" + row[row.length - 1]);
        chk("锚点: 中段 = 1 + 100/32", near(row[mid], coneByHand(1.0, 100)), "" + row[mid]);
        chk("锚点: 连续", slopeOk(row), "worst=" + worstSlope(row));
        chk("锚点: 无证据行", res.interfaces().isEmpty(), "");
    }

    // ⑩ 无 rivers（已知限制如实记录）

    private static void noRivers() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 0);
        int[] a = f.line(20, 200, 85, 200);
        int[] b = f.line(85, 200, 150, 200);
        var traces = List.of(
                f.trace(a, DeterministicSkeleton.End.JOIN, false),
                f.trace(b, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var res = f.build(List.of(a, b), traces, null, Map.of());
        double[] ra = res.limits().get(0), rb = res.limits().get(1);
        System.out.printf("  [info] 无 rivers: A首=%.3f A尾=%.3f B首=%.3f B尾=%.3f%n",
                ra[0], ra[ra.length - 1], rb[0], rb[rb.length - 1]);
        chk("无 rivers: B 尾收紧", rb[rb.length - 1] < LakeWidthEnvelope.MAX_HALF - 1e-9, "");
        chk("无 rivers: A 段不受影响（接口不在 A 上）",
                allEqual(ra, LakeWidthEnvelope.MAX_HALF), brief(ra));
        // 已知限制：无接点边 ⇒ 距离不跨 trace，接缝可不等，仅靠斜率闭合兜底
        chk("无 rivers: 各段自身连续（闭合兜底）", slopeOk(ra) && slopeOk(rb), "");
        chk("无 rivers: 接缝可不等 —— 已知限制，不是缺陷",
                rb[0] < ra[ra.length - 1] - 1e-9, "Atail=" + ra[ra.length - 1] + " B0=" + rb[0]);
    }

    // ⑪ 环路与退化

    private static void cyclesAndDegenerate() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 0);
        int[] a = f.line(20, 200, 85, 200);
        int[] b = f.line(85, 200, 150, 200);
        var traces = List.of(
                f.trace(a, DeterministicSkeleton.End.JOIN, false),
                f.trace(b, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var rivers = List.of(
                f.river(new int[]{0}, a, DeterministicSkeleton.RiverLink.CONNECTED, 1, 0),
                f.river(new int[]{1}, b, DeterministicSkeleton.RiverLink.CONNECTED, 0, 0));
        chk("环路: cycleNodes 认出", RiverWidthProfile.cycleNodes(new int[]{1, 0}, 2)[0], "");
        var res = f.build(List.of(a, b), traces, rivers, Map.of());
        chk("环路: 不崩溃", res.limits().size() == 2, "");
        chk("环路: 各段连续", slopeOk(res.limits().get(0)) && slopeOk(res.limits().get(1)), "");
        chk("环路: 本 trace 内仍收紧",
                res.limits().get(1)[b.length - 1] < LakeWidthEnvelope.MAX_HALF - 1e-9, "");

        chk("退化: 空折线", f.build(List.of(), List.of(), null, Map.of()).limits().isEmpty(), "");
        int one = f.cell(150, 200);
        var single = f.build(List.of(new int[]{one}),
                List.of(f.trace(new int[]{one}, DeterministicSkeleton.End.JOIN, false)), null, Map.of());
        chk("退化: 单节点", single.limits().size() == 1 && single.limits().get(0).length == 1, "");
        var none = LakeWidthEnvelope.build(null, null, 0, 0, 400, 1, null, null, null);
        chk("退化: 全 null", none.limits().isEmpty() && none.interfaces().isEmpty(), "");
        var ni = f.build(List.of(a), List.of(f.trace(a, DeterministicSkeleton.End.JOIN, false)), null, null);
        chk("退化: inherited=null", ni.limits().size() == 1, "");
    }

    // ⑫ 证据行

    private static void evidenceRows() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 7);
        int[] cells = f.line(20, 200, 150, 200);
        var traces = List.of(f.trace(cells, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var res = f.build(List.of(cells), traces, null, Map.of());
        chk("证据: 一条", res.interfaces().size() == 1, "");
        var i = res.interfaces().get(0);
        chk("证据: kind", LakeWidthEnvelope.KIND_MOUTH.equals(i.kind()), i.kind());
        chk("证据: poly=0", i.poly() == 0, "");
        chk("证据: cell=末节点", i.cell() == cells[cells.length - 1], "");
        chk("证据: basin=7", i.basin() == 7, "" + i.basin());
        chk("证据: samples>0", i.samples() > 0, "" + i.samples());
        chk("证据: transverse 有限", Double.isFinite(i.transverse()), "");
        chk("证据: applied", i.applied(), "");
        chk("证据: maxHalf == limits 末节点", near(i.maxHalf(), res.limits().get(0)[cells.length - 1]), "");
        System.out.printf("  [info] 证据: %s%n", i);
    }

    // ⑬ 确定性

    private static void determinism() {
        Fixture f = new Fixture(400, 1);
        f.water(150, 180, 190, 220, 0);
        int[] a = f.line(20, 200, 85, 200);
        int[] b = f.line(85, 200, 150, 200);
        var traces = List.of(
                f.trace(a, DeterministicSkeleton.End.JOIN, false),
                f.trace(b, DeterministicSkeleton.End.MOUTH_LAKE, false));
        var rivers = List.of(
                f.river(new int[]{0}, a, DeterministicSkeleton.RiverLink.CONNECTED, 1, 0),
                f.river(new int[]{1}, b, DeterministicSkeleton.RiverLink.TERMINAL, -1, -1));
        var r1 = f.build(List.of(a, b), traces, rivers, Map.of());
        var r2 = f.build(List.of(a, b), traces, rivers, Map.of());
        boolean same = r1.limits().size() == r2.limits().size();
        for (int p = 0; same && p < r1.limits().size(); p++) {
            same = Arrays.equals(r1.limits().get(p), r2.limits().get(p));
        }
        chk("确定性: 逐位相同", same, "");
        chk("确定性: 接口数一致", r1.interfaces().size() == r2.interfaces().size(), "");
    }

    // 工具

    private static final class Fixture {
        final int n;
        final int step;
        final int ox = 0;
        final int oz = 0;
        final boolean[] lake;
        final int[] basinId;
        final Map<Integer, Integer> outlets = new HashMap<>();

        Fixture(int n, int step) {
            this.n = n;
            this.step = step;
            this.lake = new boolean[n * n];
            this.basinId = new int[n * n];
            Arrays.fill(basinId, -1);
        }

        void water(int x0, int z0, int x1, int z1, int basin) {
            for (int x = Math.max(0, x0); x <= Math.min(n - 1, x1); x++) {
                for (int z = Math.max(0, z0); z <= Math.min(n - 1, z1); z++) {
                    int k = x * n + z;
                    lake[k] = true;
                    basinId[k] = basin;
                }
            }
        }

        void outlet(int basin, int cell) {
            outlets.put(basin, cell);
        }

        int cell(int x, int z) {
            return x * n + z;
        }

        int[] line(int x0, int z0, int x1, int z1) {
            int dx = Integer.signum(x1 - x0), dz = Integer.signum(z1 - z0);
            int len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0)) + 1;
            int[] out = new int[len];
            for (int i = 0; i < len; i++) out[i] = cell(x0 + i * dx, z0 + i * dz);
            return out;
        }

        DeterministicSkeleton.Trace trace(int[] cells, DeterministicSkeleton.End end, boolean fromOutlet) {
            return new DeterministicSkeleton.Trace(cells, end, fromOutlet);
        }

        /** {@code downstreamRiver} 是 rivers 列表下标；{@code traceOf[i]} = 该格所属 trace 下标。 */
        DeterministicSkeleton.River river(int[] traceIdx, int[] cells,
                                          DeterministicSkeleton.RiverLink link,
                                          int downstreamRiver, int downstreamNode) {
            int[] traceOf = new int[cells.length];
            Arrays.fill(traceOf, traceIdx.length > 0 ? traceIdx[0] : -1);
            int node = downstreamNode < 0 ? -1 : Math.min(downstreamNode, cells.length - 1);
            return new DeterministicSkeleton.River(traceIdx, cells, traceOf,
                    DeterministicSkeleton.End.JOIN, false, n, downstreamRiver, node, link);
        }

        HydrologyPipeline.Result result() {
            int nb = outlets.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            int[] basinOutlet = new int[nb];
            Arrays.fill(basinOutlet, -1);
            outlets.forEach((b, c) -> basinOutlet[b] = c);
            var lakes = new LakeWaterBalance.Result(new double[n * n], lake, List.of(), 0, 1.0,
                    Map.of(), Map.of(), basinId, basinOutlet);
            return new HydrologyPipeline.Result(n, n, step, new double[n * n], new boolean[n * n],
                    new boolean[n * n], new double[n * n], new double[n * n], lake,
                    null, new double[n * n], new boolean[n * n], new double[n * n],
                    lakes, null, null, new double[n * n], 0, true, 0);
        }

        LakeWidthEnvelope.Result build(List<int[]> cellsList,
                                       List<DeterministicSkeleton.Trace> traces,
                                       List<DeterministicSkeleton.River> rivers,
                                       Map<Integer, Double> inherited) {
            return build0(cellsList, traces, rivers, inherited, 1);
        }

        /** {@code density>1} 时每个 coarse 点插值成多个 shape 节点，模拟"重采样后索引不等"。 */
        LakeWidthEnvelope.Result denseBuild(List<int[]> cellsList,
                                            List<DeterministicSkeleton.Trace> traces,
                                            List<DeterministicSkeleton.River> rivers,
                                            Map<Integer, Double> inherited, int density) {
            return build0(cellsList, traces, rivers, inherited, density);
        }

        private LakeWidthEnvelope.Result build0(List<int[]> cellsList,
                                                List<DeterministicSkeleton.Trace> traces,
                                                List<DeterministicSkeleton.River> rivers,
                                                Map<Integer, Double> inherited, int density) {
            List<DeterministicSkeleton.Shaped> polys = new ArrayList<>();
            for (int[] c : cellsList) {
                List<double[]> pts = new ArrayList<>();
                for (int i = 0; i < c.length; i++) {
                    double bx = ox + (c[i] / n) * (double) step;
                    double bz = oz + (c[i] % n) * (double) step;
                    if (i > 0 && density > 1) {
                        double px = ox + (c[i - 1] / n) * (double) step;
                        double pz = oz + (c[i - 1] % n) * (double) step;
                        for (int k = 1; k < density; k++) {
                            pts.add(new double[]{px + (bx - px) * k / density, pz + (bz - pz) * k / density});
                        }
                    }
                    pts.add(new double[]{bx, bz});
                }
                double[] x = new double[pts.size()], z = new double[pts.size()];
                for (int i = 0; i < pts.size(); i++) { x[i] = pts.get(i)[0]; z[i] = pts.get(i)[1]; }
                polys.add(new DeterministicSkeleton.Shaped(x, z));
            }
            return LakeWidthEnvelope.build(polys, result(), ox, oz, n, step, traces, rivers, inherited);
        }
    }

    private static int indexOf(int[] cells, int cell) {
        for (int i = 0; i < cells.length; i++) if (cells[i] == cell) return i;
        return -1;
    }

    private static boolean allEqual(double[] row, double v) {
        for (double x : row) if (Math.abs(x - v) > 1e-9) return false;
        return true;
    }

    private static boolean inRange(double[] row) {
        for (double x : row) {
            if (!Double.isFinite(x) || x < LakeWidthEnvelope.MIN_HALF - 1e-9
                    || x > LakeWidthEnvelope.MAX_HALF + 1e-9) return false;
        }
        return true;
    }

    private static boolean monotoneNonIncreasing(double[] row) {
        for (int i = 1; i < row.length; i++) if (row[i] > row[i - 1] + 1e-9) return false;
        return true;
    }

    private static boolean slopeOk(double[] row) {
        return worstSlope(row) <= LakeWidthEnvelope.HALF_GROWTH_PER_BLOCK + 1e-9;
    }

    private static double worstSlope(double[] row) {
        double worst = 0;
        for (int i = 1; i < row.length; i++) worst = Math.max(worst, Math.abs(row[i] - row[i - 1]));
        return worst;
    }

    private static boolean near(double a, double b) {
        return Double.isFinite(a) && Math.abs(a - b) < 1e-6;
    }

    private static String kinds(LakeWidthEnvelope.Result r) {
        StringBuilder sb = new StringBuilder("[");
        for (var i : r.interfaces()) sb.append(i.kind()).append(":b=").append(i.basin())
                .append(':').append(i.status()).append(' ');
        return sb.append(']').toString();
    }

    private static String brief(double[] row) {
        if (row.length == 0) return "[]";
        return String.format("[%.3f..%.3f] n=%d", row[0], row[row.length - 1], row.length);
    }

    private static void chk(String name, boolean ok) {
        chk(name, ok, null);
    }

    private static void chk(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%s %s %s%n", ok ? "[OK]  " : "[FAIL]", name, detail == null ? "" : "| " + detail);
    }
}
