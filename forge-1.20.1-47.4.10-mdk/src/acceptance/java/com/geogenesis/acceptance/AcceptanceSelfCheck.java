package com.geogenesis.acceptance;

import com.geogenesis.worldgen.terrain.Cell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 验收支线的离线自检（不需要 MC Bootstrap，只要类路径上有 MC jar）。
 *
 * <p>跑法：{@code java -cp "<classes:resources:minecraft>" com.geogenesis.acceptance.AcceptanceSelfCheck}</p>
 *
 * <p>退出码 0 = 全部通过；1 = 有一条不符合预期。只碰 {@link AcceptanceProbe} 的纯逻辑与文本
 * 渲染，<b>不加载任何 chunk</b>。</p>
 *
 * <h3>负控（必须红）</h3>
 * <ul>
 *   <li>水柱中空、只有岩浆（岩浆不算水）；</li>
 *   <li><b>计划断口</b>：计划不连通时 link 必须 {@code UNVERIFIED}（不是 pass）；</li>
 *   <li>计划连通但实际缺列 ⇒ link {@code FAIL}；</li>
 *   <li>零计划湿列 ⇒ coverage {@code MISSING} 且门禁不绿；</li>
 *   <li>水面之上有植被时连通性仍成立（植被不遮水）；</li>
 *   <li><b>背景水基线</b>（{@link AcceptanceProbe#backgroundGate}）：两臂格式/元数据/坐标
 *       集合的每一条都要有正控与负控（见 {@link #checkBackgroundGate}）。</li>
 * </ul>
 */
public final class AcceptanceSelfCheck {

    public static void main(String[] args) throws IOException {
        checkPlan();
        checkRuns();
        checkConnectivity();
        checkLinks();
        checkCoverage();
        checkChunkCoords();
        checkGates();
        checkReportAndTopView();
        checkBackgroundGate();
        System.out.println("[accept-selfcheck] ALL PASS");
    }

    /** 计划口径：河/干/海/无瀑布唇/水位低于地面/关水文。 */
    private static void checkPlan() {
        expectPlan("river", AcceptanceProbe.plan(cell(170.4, 1, 172.6, 173.2), true, 63.0),
                true, 170, 173, 3, 1);
        expectPlan("dryLand", AcceptanceProbe.plan(cell(170.4, 0, 0.0, 0.0), true, 63.0),
                false, 170, AcceptanceProbe.NO_WATER, 0, 0);
        expectPlan("ocean", AcceptanceProbe.plan(cell(50.3, 0, 0.0, 0.0), true, 63.0),
                true, 50, 63, 13, 0);
        expectPlan("noLip", AcceptanceProbe.plan(cell(170.4, 1, 172.6, 172.6), true, 63.0),
                true, 170, 172, 2, 0);
        expectPlan("surfaceBelowGround", AcceptanceProbe.plan(cell(170.4, 1, 169.0, 169.0), true, 63.0),
                false, 170, AcceptanceProbe.NO_WATER, 0, 0);
        expectPlan("hydrologyOff", AcceptanceProbe.plan(cell(50.3, 0, 0.0, 0.0), false, 63.0),
                false, 50, AcceptanceProbe.NO_WATER, 0, 0);
    }

    /** 水段模型 + 中空/岩浆负控。 */
    private static void checkRuns() {
        AcceptanceProbe.ColumnView solid = column(new int[]{171, 172, 173});
        expect("runsContiguous", solid.runs().size() == 1 && solid.waterTop() == 173
                && solid.waterBottom() == 171 && solid.waterCount() == 3);

        AcceptanceProbe.ColumnView hollow = column(new int[]{171, 172, 174, 175});
        expect("runsHollowDetected", hollow.runs().size() == 2 && hollow.waterCount() == 4);

        AcceptanceProbe.ColumnView none = column(new int[]{});
        expect("runsNone", none.runs().isEmpty()
                && none.waterTop() == AcceptanceProbe.NO_WATER && none.waterCount() == 0);

        AcceptanceProbe.Plan plan = AcceptanceProbe.plan(cell(170.4, 1, 172.6, 172.6), true, 63.0);
        AcceptanceProbe.Verdict okCont = new AcceptanceProbe.Verdict("ok(size=9)", false);
        expect("hollowColumnFails", !AcceptanceProbe.evaluate(
                AcceptanceProbe.TARGETS.get(1), plan, hollow, okCont).pass());
        expect("solidColumnPasses", AcceptanceProbe.evaluate(
                AcceptanceProbe.TARGETS.get(1), plan, column(new int[]{171, 172}), okCont).pass());

        // 负控：把水柱换成岩浆（含水流体但非水体方块）⇒ 必须 FAIL
        AcceptanceProbe.ColumnView lava = AcceptanceProbe.syntheticColumn(
                new int[]{}, new int[]{171, 172});
        expect("lavaNotWater", !AcceptanceProbe.evaluate(
                AcceptanceProbe.TARGETS.get(1), plan, lava, okCont).pass());
        // 岩浆不在 LAVA→WATER 语义里，合成替身把它标成 selftest:waterlogged_solid；
        // 这里只断言"不是水"，不断言 waterlogged 语义（真实 lava 走 FluidTags.LAVA，不带 WATER）。
        expect("lavaIsNotWaterBody", lava.waterCount() == 0);

        // 负控：continuity == null（没探测）不得算通过
        expect("continuityMissingFails", !AcceptanceProbe.evaluate(
                AcceptanceProbe.TARGETS.get(1), plan, column(new int[]{171, 172}), null).pass());
    }

    /** 8 邻：窄对角必须连通；隔一格不连通；水面之上植被不影响。 */
    private static void checkConnectivity() {
        AcceptanceProbe.WetFn diagonal = mask(0, 0, 1, 1, 2, 2, 3, 3);
        expect("diagonalConnected", AcceptanceProbe.connected(diagonal, 0, 0, 3, 3, 12));
        expect("diagonalComponentSize", AcceptanceProbe.component(diagonal, 0, 0, 12).size() == 4);
        expect("diagonalNotIsolated",
                !AcceptanceProbe.continuity(AcceptanceProbe.component(diagonal, 0, 0, 12)).fatal());

        expect("gapNotConnected", !AcceptanceProbe.connected(mask(0, 0, 2, 0), 0, 0, 2, 0, 12));
        AcceptanceProbe.Component lone = AcceptanceProbe.component(mask(5, 5), 5, 5, 12);
        expect("isolatedFatal", lone.size() == 1 && AcceptanceProbe.continuity(lone).fatal());
        expect("componentCountsWetOnly",
                AcceptanceProbe.component(mask(0, 0, 1, 0, 0, 1), 0, 0, 12).size() == 3);

        // 水面之上有植被（含水草/甘蔗等）：waterTop/waterCount 不受影响，
        // topNonWater 记录到植被顶，并逐块列出 aboveWater 明细
        AcceptanceProbe.ColumnView canopy = AcceptanceProbe.syntheticColumn(
                new int[]{171, 172}, new int[]{173});
        expect("canopyDoesNotHideWater",
                canopy.waterTop() == 172 && canopy.waterCount() == 2 && canopy.topNonWater() == 173);
        expect("canopyAboveWaterDetail", canopy.aboveWater().size() == 1
                && canopy.aboveWater().get(0).y() == 173
                && canopy.aboveWater().get(0).waterlogged());
    }

    /** Link：计划断口必须 UNVERIFIED（不绿）；计划连通实际缺列必须 FAIL。 */
    private static void checkLinks() {
        AcceptanceProbe.Link axis = AcceptanceProbe.Link.of("axis", 10, 20, 14, 20);
        expect("axisColumnsCount", axis.columns().size() == 5);

        // 计划 5 列全湿，实际 5 列全在窗口内有水 ⇒ PASS
        AcceptanceProbe.PlanRef planAllWet = planRef(10, 20, 11, 20, 12, 20, 13, 20, 14, 20);
        AcceptanceProbe.LinkResult all = AcceptanceProbe.checkLink(axis, planAllWet,
                lookup(10, 20, 11, 20, 12, 20, 13, 20, 14, 20));
        expect("linkAllMatchedPasses", all.status() == AcceptanceProbe.LinkStatus.PASS);

        // 计划连通但实际缺中间列 ⇒ FAIL
        expect("linkMissingColumnFails", AcceptanceProbe.checkLink(axis, planAllWet,
                lookup(10, 20, 14, 20)).status() == AcceptanceProbe.LinkStatus.FAIL);

        // ★ 计划断口（只在两端出水、中间计划干）⇒ UNVERIFIED，且 pass()==false
        AcceptanceProbe.PlanRef planGap = planRef(10, 20, 14, 20);
        AcceptanceProbe.LinkResult gap = AcceptanceProbe.checkLink(axis, planGap,
                lookup(10, 20, 14, 20));
        expect("linkPlanGapUnverified", gap.status() == AcceptanceProbe.LinkStatus.UNVERIFIED);
        expect("linkPlanGapNotPass", !gap.pass());

        // 计划断口 + 实际竟然连通 ⇒ 仍然 UNVERIFIED（不能因实际凑巧连通就当验过）
        expect("linkPlanGapUnverifiedEvenIfActualConnected",
                AcceptanceProbe.checkLink(axis, planGap,
                        lookup(10, 20, 11, 20, 12, 20, 13, 20, 14, 20)).status()
                        == AcceptanceProbe.LinkStatus.UNVERIFIED);

        // 对角 5 列坐标正确
        AcceptanceProbe.Link diag = AcceptanceProbe.Link.of("diag", -604, 596, -608, 600);
        expect("diagColumnsCount", diag.columns().size() == 5);
        expect("diagStartsAtA", diag.columns().get(0)[0] == -604 && diag.columns().get(0)[1] == 596);
        expect("diagEndsAtB", diag.columns().get(4)[0] == -608 && diag.columns().get(4)[1] == 600);

        // 总量门禁：UNVERIFIED 不得绿
        expect("linkGateUnverifiedNotGreen", AcceptanceProbe.linkGate(
                List.of(gap)) != null);
        expect("linkGateEmptyNotGreen", AcceptanceProbe.linkGate(List.of()) != null);
        expect("linkGateAllPassGreen", AcceptanceProbe.linkGate(List.of(
                AcceptanceProbe.checkLink(axis, planAllWet,
                        lookup(10, 20, 11, 20, 12, 20, 13, 20, 14, 20)))) == null);
    }
    /** 覆盖：单 chunk 计划全干 ⇒ DRY（合法，不当水证据）；多 chunk 零湿 ⇒ MISSING 不绿。 */
    private static void checkCoverage() {
        // ★ 单 chunk 且计划全干 ⇒ DRY（扩展 chunk 34,-149 的合法情形）
        AcceptanceProbe.Coverage dry = AcceptanceProbe.coverage("drySingleChunk",
                List.of(new int[]{0, 0}), planRef(), lookup());
        expect("coverageDryChunkIsDry", dry.dryLegal() && !dry.missing()
                && dry.planWetColumns() == 0);
        expect("coverageDryNotWaterEvidence", !dry.verifiedWater());
        expect("coverageDryGateGreen", AcceptanceProbe.coverageGate(dry) == null);

        // ★ 多 chunk 汇总零计划湿列 ⇒ MISSING（覆盖未建立，不得绿）
        AcceptanceProbe.Coverage missing = AcceptanceProbe.coverage("zeroWetMultiChunk",
                List.of(new int[]{0, 0}, new int[]{1, 0}), planRef(), lookup());
        expect("coverageMultiChunkZeroWetMissing", missing.missing());
        expect("coverageMissingGateNotGreen", AcceptanceProbe.coverageGate(missing) != null);

        // 计划有湿列且实际匹配 ⇒ VERIFIED（唯一的水证据）
        AcceptanceProbe.PlanRef oneWet = planRef(1, 1);
        AcceptanceProbe.Coverage ok = AcceptanceProbe.coverage("oneWet",
                List.of(new int[]{0, 0}), oneWet, lookup(1, 1));
        expect("coverageVerified", "VERIFIED".equals(ok.status())
                && ok.planWetColumns() == 1 && ok.matchedColumns() == 1);
        expect("coverageVerifiedIsWaterEvidence", ok.verifiedWater());
        expect("coverageVerifiedGateGreen", AcceptanceProbe.coverageGate(ok) == null);

        // 计划湿但实际没有 ⇒ PARTIAL，不绿
        AcceptanceProbe.Coverage partial = AcceptanceProbe.coverage("partial",
                List.of(new int[]{0, 0}), oneWet, lookup());
        expect("coveragePartialNotGreen", AcceptanceProbe.coverageGate(partial) != null);

        // 多列：计划两列湿、实际只匹配一列 ⇒ PARTIAL
        AcceptanceProbe.PlanRef twoWet = planRef(1, 1, 2, 2);
        AcceptanceProbe.Coverage half = AcceptanceProbe.coverage("half",
                List.of(new int[]{0, 0}), twoWet, lookup(1, 1));
        expect("coverageHalfPartial", "PARTIAL".equals(half.status())
                && AcceptanceProbe.coverageGate(half) != null);
        var deep=AcceptanceProbe.plan(cell(170.4,1,173.2,173.2),true,63);
        var hollow=AcceptanceProbe.coverage("hollow",List.of(new int[]{0,0}),
                (x,z)->deep,(x,z,lo,hi)->lo<=171 && hi>=171);
        expect("coverageWholePlannedColumnRequired",!hollow.passes() && hollow.matchedColumns()==0);
    }

    /**
     * 已加载 chunk 坐标：<b>用真实 {@link net.minecraft.world.level.ChunkPos} 调用生产 helper</b>。
     *
     * <p>负控必须有 cx != cz 且一正一负（如 (528,-2400)）：旧的 {@code ChunkPos.asLong}
     * 手解会把它换成 (-2400,528)。这里不写假公式，直接让 helper 走真实 {@code getPos().x/z}。</p>
     */
    private static void checkChunkCoords() {
        net.minecraft.world.level.ChunkPos a = new net.minecraft.world.level.ChunkPos(528, -2400);
        net.minecraft.world.level.ChunkPos b = new net.minecraft.world.level.ChunkPos(-2400, 528);
        net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos(0, 0);

        // 真实 asLong 顺序证明：x 低 32 位、z 高 32 位（bug 的根因本身）
        long packed = net.minecraft.world.level.ChunkPos.asLong(528, -2400);
        expect("chunkPosAsLongIsXLowZHigh",
                (int) packed == 528 && (int) (packed >> 32) == -2400);

        // 调用生产 helper（同一份代码；在 AcceptanceProbe，无 Forge 静态初始化），
        // 负控 cx != cz 且一正一负
        List<int[]> coords = AcceptanceProbe.chunkCoordsOf(List.of(a, b, c));
        expect("chunkCoordsCount", coords.size() == 3);
        expect("chunkCoordsSortedByXThenZ",
                coords.get(0)[0] == -2400 && coords.get(0)[1] == 528
                        && coords.get(1)[0] == 0 && coords.get(1)[1] == 0
                        && coords.get(2)[0] == 528 && coords.get(2)[1] == -2400);
        // ★ 核心负控：(528,-2400) 必须保持 (528,-2400)，不得变成 (-2400,528)
        expect("chunkCoordsKeepsXZDistinct",
                coords.get(2)[0] == 528 && coords.get(2)[1] == -2400);
        expect("chunkCoordsNoSwappedEntry",
                !(coords.get(0)[0] == 528 && coords.get(0)[1] == -2400));
    }

    /** 逐点门禁 + 跨缝后继。 */
    private static void checkGates() {
        AcceptanceProbe.Plan wetPlan = AcceptanceProbe.plan(cell(170.4, 1, 172.6, 172.6), true, 63.0);
        AcceptanceProbe.Plan dryPlan = AcceptanceProbe.plan(cell(170.4, 0, 0.0, 0.0), true, 63.0);
        AcceptanceProbe.ColumnView dry = column(new int[]{});
        AcceptanceProbe.ColumnView wet = column(new int[]{171, 172});

        expect("gatePlannedWetActualDry", "plannedWetButActualDry".equals(
                AcceptanceProbe.pointGate(wetPlan, dry, AcceptanceProbe.Kind.LIP_WEST)));
        expect("gatePlannedWetActualWet",
                AcceptanceProbe.pointGate(wetPlan, wet, AcceptanceProbe.Kind.SEAM) == null);
        expect("gateDryPlanNotGated",
                AcceptanceProbe.pointGate(dryPlan, dry, AcceptanceProbe.Kind.LIP_EAST) == null);
        expect("gateExtChunkExempt",
                AcceptanceProbe.pointGate(wetPlan, dry, AcceptanceProbe.Kind.EXT_CHUNK) == null);

        // 跨缝后继：只从计划掩码取，且必须在种子组件内、位于缝的另一侧
        AcceptanceProbe.WetFn crossing = mask(-1020, 508, -1020, 509, -1020, 510,
                -1020, 511, -1020, 512, -1020, 513);
        int[] succ = AcceptanceProbe.planSuccessorAcross(crossing, -1020, 508, 512, 12);
        expect("seamSuccessorFound", succ != null && succ[0] == -1020 && succ[1] == 513);
        expect("seamSuccessorNoneWhenNoCross", AcceptanceProbe.planSuccessorAcross(
                mask(-1020, 505, -1020, 506, -1020, 507, -1020, 508), -1020, 508, 512, 12) == null);
    }

    /** 报告 + 俯视 PNG：关键键存在、三态 status 都能渲染、PNG 落盘。 */
    private static void checkReportAndTopView() throws IOException {
        List<AcceptanceProbe.Point> points = new ArrayList<>();
        points.add(AcceptanceProbe.missing(AcceptanceProbe.TARGETS.get(0), "selfcheck"));
        points.add(AcceptanceProbe.error(AcceptanceProbe.TARGETS.get(1), "boom"));
        AcceptanceProbe.Plan plan = AcceptanceProbe.plan(cell(170.4, 1, 172.6, 173.2), true, 63.0);
        points.add(new AcceptanceProbe.Point(AcceptanceProbe.TARGETS.get(2), plan,
                AcceptanceProbe.syntheticColumn(new int[]{171, 172, 173}, new int[]{175}),
                "ok(size=20)", "FAIL", false, "selftest", "n/a"));
        List<AcceptanceProbe.ChunkRecord> chunks = List.of(
                new AcceptanceProbe.ChunkRecord(1, 2, true, "", 42L),
                new AcceptanceProbe.ChunkRecord(3, 4, false, "access=null", 7L));
        List<AcceptanceProbe.LinkResult> links = List.of(
                new AcceptanceProbe.LinkResult("axis", AcceptanceProbe.LinkStatus.PASS,
                        "planConnected=true actualConnected=true", List.of("  col=0,0 planWet=true")),
                new AcceptanceProbe.LinkResult("gap", AcceptanceProbe.LinkStatus.UNVERIFIED,
                        "planNotConnected=unverified", List.of()));
        List<AcceptanceProbe.Coverage> coverages = List.of(
                new AcceptanceProbe.Coverage("extChunk", 1, 256, 0, 0, "MISSING", ""),
                new AcceptanceProbe.Coverage("loaded3x3", 9, 2304, 3, 3, "VERIFIED", ""));

        List<String> header = List.of(
                "schema=geogenesis-acceptance/5",
                "verdict=FAIL",
                "exitCode=1",
                "reportKind=BlockStateReadback");
        Path dir = Path.of("build", "accept-selfcheck");
        Path report = dir.resolve("acceptance-report.txt");
        AcceptanceProbe.writeReport(report, header, chunks, points, links, coverages);
        String text = Files.readString(report, StandardCharsets.UTF_8);
        String[] required = {
                "schema=geogenesis-acceptance/5",
                "exitCode=1",
                "[links]", "link=axis status=PASS pass=true",
                "link=gap status=UNVERIFIED pass=false",
                "[coverage]", "coverage=extChunk status=MISSING pass=false",
                "coverage=loaded3x3 status=VERIFIED pass=true",
                "[chunks]", "chunk=3,4 ok=false ms=7 why=access=null",
                "[points]", "label=seam(-1020,508)", "status=MISSING", "status=ERROR",
                "plan.waterTopY=173", "plan.waterHeight=3",
                "actual.topNonWaterY=175",
                "actual.topNonWaterNote=topmost non-air non-water",
                "actual.aboveWaterCount=2", "  aboveWater y=175",
                "actual.waterRunList=171..173"};
        for (String key : required) {
            if (!text.contains(key)) fail("report missing: " + key);
        }

        Path png = dir.resolve("acceptance-topview.png");
        AcceptanceProbe.writeTopView(png, AcceptanceProbe.panels(AcceptanceProbe.TARGETS),
                (cx, cz) -> null);
        if (!Files.isRegularFile(png) || Files.size(png) < 1000L) fail("topview png not written");
        System.out.println("[accept-selfcheck] report=" + report.toAbsolutePath()
                + " bytes=" + Files.size(report) + " topview=" + Files.size(png) + "B");
    }

    // ===== 背景水基线（backgroundGate）自检 =====

    /**
     * 正控：两臂仅 arm 不同 ⇒ 绿；18 条新增背景水<b>两臂相同</b> ⇒ 仍绿；行序无关 ⇒ 绿。
     * 负控：两臂水行集合/元数据/格式的任一条不合契约都必须红（见下两个方法）。
     */
    private static void checkBackgroundGate() {
        List<String> ref = background("reference", 0, -30);
        List<String> cand = background("candidate", 0, -30);
        List<String> ref18 = background("reference", 18, -30);
        List<String> cand18 = background("candidate", 18, -30);
        expect("bgEqualArmsGreen", AcceptanceProbe.backgroundGate(ref, cand) == null);
        expect("bgReferenceCannotSelfApprove",
                AcceptanceProbe.backgroundGate(ref, new ArrayList<>(ref)) != null);
        expect("bg18NewBackgroundBothArmsGreen",
                AcceptanceProbe.backgroundGate(ref18, cand18) == null);
        // 行序无关：chunk 集合与 water 集合比对，不依赖行序
        expect("bgLineOrderIrrelevantGreen",
                AcceptanceProbe.backgroundGate(ref18, reversed(cand18)) == null);
        expect("bgNullReportFails", AcceptanceProbe.backgroundGate(null, cand) != null);
        expect("bgEmptyReportFails",
                AcceptanceProbe.backgroundGate(List.of(), List.of()) != null);
        checkBackgroundWater(ref, cand);
        checkBackgroundMeta(ref, cand);
        checkChunkSet();
        checkOverflow();
    }

    /** 负控：water 行的新增/删除/状态变化/重复坐标/重复行/缺失块/越界。 */
    private static void checkBackgroundWater(List<String> ref, List<String> cand) {
        String plain = "water=2,-8,2,minecraft:water";
        String flowing = "water=2,-8,2,minecraft:water[level=1]";
        gate("bgCandidateOnlyFails", ref, add(cand, plain), "added=1");
        gate("bgCandidateRemovalFails", add(ref, plain), cand, "removed=1");
        gate("bgSourceToFlowFails", add(ref, plain), add(cand, flowing), "changed=1");
        gate("bgDuplicateCoordFails", ref, add(add(cand, plain), flowing),
                "DuplicateWaterCoord");
        gate("bgDuplicateLineFails", ref, add(add(cand, plain), plain), "DuplicateLine");
        gate("bgDuplicateChunkCoordFails", ref, add(cand, "chunk=00,00"), "DuplicateChunkCoord");
        gate("bgMissingChunkFails", ref, remove(cand, "chunk=5,5"), "ChunkCount(35)");
        gate("bgWaterOutsideChunksFails", ref, add(cand, "water=3000,-8,2,minecraft:water"),
                "WaterOutsideChunks");
        gate("bgWaterOutsideHeightFails", ref, add(cand, "water=2,9999,2,minecraft:water"),
                "WaterOutsideWorldHeight");
        gate("bgWaterStateCommaFails", ref, add(cand, "water=2,-8,2,minecraft:water,"),
                "waterStateHasComma");
        gate("bgWaterEmptyStateFails", ref, add(cand, "water=2,-8,2,"), "waterEmptyState");
        gate("bgWaterBlankStateFails", ref, add(cand, "water=2,-8,2, "), "waterEmptyState");
    }

    /**
     * 负控：<b>chunk 集合</b>必须两臂相等 —— 36 个 chunk 全不同、water 完全相同不得假绿。
     *
     * <p>换走的 chunk 必须<b>不含水</b>，否则会被 {@code WaterOutsideChunks} 先抓住、
     * 测不到 chunk 集合比较本身。</p>
     */
    private static void checkChunkSet() {
        List<String> ref = background("reference", 18, -30);
        List<String> cand = background("candidate", 18, -30);
        expect("bgEqualChunkSetsGreen", AcceptanceProbe.backgroundGate(ref, cand) == null);
        // 35 + 换一个（仍 36）：chunk(5,5) 无水 ⇒ 只可能由 chunk 集合差异触发
        gate("bgChunkSwapKeepsSameCountFails", ref, replace(cand, "chunk=5,5", "chunk=9,9"),
                "chunkSetMismatch");
        // ★ 关键漏项：两臂 water 均为空（集合相等），但 36 个 chunk 全不同 ⇒ 必须红
        gate("bgAllChunksDifferDryFails", background("reference", 0, -30),
                shiftChunks(background("candidate", 0, -30), 1000), "chunkSetMismatch");
        checkExpectedChunkSet();
    }

    /**
     * 三参版本：candidate 的 chunk 集必须<b>恰等于</b>期望集。
     *
     * <p>关键负控 = <b>两臂同错</b>（同一批错 chunk，两参版本会绿）⇒ 三参必须红。</p>
     */
    private static void checkExpectedChunkSet() {
        Set<String> expected = chunkKeys(background("candidate", 0, -30));
        List<String> ref = background("reference", 18, -30);
        List<String> cand = background("candidate", 18, -30);
        expect("bgExpectedChunkSetGreen",
                AcceptanceProbe.backgroundGate(ref, cand, expected) == null);
        // 两臂同错：同一 delta 平移两臂（dry ⇒ 无 water 行，不会被 WaterOutsideChunks 先拦）
        // ⇒ 两参绿、三参必须红。这是两参版本无法覆盖的假绿形态。
        List<String> refBad = shiftChunks(background("reference", 0, -30), 1000);
        List<String> candBad = shiftChunks(background("candidate", 0, -30), 1000);
        expect("bgSameWrongSetPassesTwoArg",
                AcceptanceProbe.backgroundGate(refBad, candBad) == null);
        gate3("bgSameWrongSetFailsThreeArg", refBad, candBad, expected,
                "unexpectedChunkSet");
        gate3("bgExpectedMissingFails", ref, cand, Set.of(), "expectedChunksMissing");
        gate3("bgOneUnexpectedKeyFails", ref, cand,
                without(expected, "5,5"), "actualOnly:5,5");
        gate3("bgOneExpectedOnlyFails", ref, cand,
                with(expected, "99,99"), "expectedOnly:99,99");
        // 三参不改两参语义：两参该红的仍然红
        gate3("bgThreeArgInheritsTwoArgFailure", ref, add(cand, "water=2,-8,2,minecraft:water"),
                expected, "added=1");
    }

    /** chunk 键集（纯 cx,cz）。 */
    private static Set<String> chunkKeys(List<String> report) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : report) {
            if (line.startsWith("chunk=")) out.add(line.substring(6));
        }
        return out;
    }

    private static Set<String> without(Set<String> keys, String key) {
        Set<String> out = new LinkedHashSet<>(keys);
        out.remove(key);
        return out;
    }

    private static Set<String> with(Set<String> keys, String key) {
        Set<String> out = new LinkedHashSet<>(keys);
        out.add(key);
        return out;
    }

    /** 负控：meta 键缺失/多余/空空白值/取值不符（seed/config/plan/order/ticks/height/source）+ 格式。 */
    private static void checkBackgroundMeta(List<String> ref, List<String> cand) {
        String seed = "meta.seed=9139912035078620160";
        gate("bgMissingMetaFails", ref, remove(cand, "meta.plan=deadbeef"), "MetaMissing(plan)");
        gate("bgEmptyMetaFails", ref, replace(cand, seed, "meta.seed="), "metaEmptyValue");
        gate("bgBlankMetaFails", ref, replace(cand, seed, "meta.seed= "), "metaEmptyValue");
        gate("bgUnknownMetaKeyFails", ref, add(cand, "meta.extra=1"), "metaUnknownKey");
        gate("bgSeedMismatchFails", ref, replace(cand, seed, "meta.seed=1"), "seed");
        gate("bgConfigMismatchFails", ref,
                replace(cand, "meta.config=model=true,hier=true", "meta.config=model=false"), "config");
        gate("bgPlanMismatchFails", ref, replace(cand, "meta.plan=deadbeef", "meta.plan=cafe"), "plan");
        gate("bgOrderMismatchFails", ref, replace(cand, "meta.order=forward", "meta.order=reverse"), "order");
        gate("bgSourceMismatchFails", ref, replace(cand, "meta.source=sha256:abc123", "meta.source=sha256:zz"), "source");
        gate("bgTicksMismatchFails", ref, replace(cand, "meta.ticks=200", "meta.ticks=199"), "MetaTicks");
        gate("bgHeightZeroFails", ref, replace(cand, "meta.height=384", "meta.height=0"), "MetaHeight");
        gate("bgArmWrongFails", ref, replace(cand, "arm=candidate", "arm=reference"), "Arm");
        gate("bgCandidateVsCandidateFails", cand, cand, "referenceArm(candidate)");
        gate("bgSchemaValueFails", ref, replace(cand, "schema=" + AcceptanceProbe.WATER_SCHEMA,
                "schema=geogenesis-water-background/0"), "schemaValue");
        gate("bgUnknownLineFails", ref, add(cand, "extra=1"), "unknownLine");
    }

    /**
     * 正控：极值但合法的 {@code minY/height} 不得被错误判越界（{@code minY+height} 用 int 会溢出）。
     *
     * <p>{@code minY=1000, height=2147483647} ⇒ 真实上界 2147484647，水 {@code y=2000} 合法。
     * 旧写法 {@code minY+height}（int）溢出为 {@code -2147482649} ⇒
     * {@code 2000 >= -2147482649} 成立 ⇒ 误报 {@code WaterOutsideWorldHeight}（假红）；
     * long 写法必须绿。</p>
     */
    private static void checkOverflow() {
        List<String> ref = hugeWorld("reference");
        List<String> cand = hugeWorld("candidate");
        expect("bgExtremeHeightNoFalseRed", AcceptanceProbe.backgroundGate(ref, cand) == null);
    }

    private static List<String> hugeWorld(String arm) {
        return replace(replace(background(arm, 1, 2000),
                "meta.minY=-64", "meta.minY=1000"),
                "meta.height=384", "meta.height=2147483647");
    }

    /** 背景水报告构造：arm + 8 meta + 36 chunk + N 条水（坐标必落在 chunk(0,0) 与世界高度内）。 */
    private static List<String> background(String arm, int waterCells, int startY) {
        List<String> out = new ArrayList<>();
        out.add("schema=" + AcceptanceProbe.WATER_SCHEMA);
        out.add("arm=" + arm);
        out.add("meta.seed=9139912035078620160");
        out.add("meta.config=model=true,hier=true");
        out.add("meta.plan=deadbeef");
        out.add("meta.order=forward");
        out.add("meta.ticks=" + AcceptanceProbe.WATER_TICKS);
        out.add("meta.minY=-64");
        out.add("meta.height=384");
        out.add("meta.source=sha256:abc123");
        for (int i = 0; i < AcceptanceProbe.WATER_CHUNKS; i++) {
            out.add("chunk=" + (i % 6) + "," + (i / 6));
        }
        for (int i = 0; i < waterCells; i++) {
            out.add("water=" + (i % 16) + "," + (startY + i) + "," + ((i / 16) % 2)
                    + ",minecraft:water");
        }
        return out;
    }

    private static List<String> add(List<String> report, String line) {
        List<String> out = new ArrayList<>(report);
        out.add(line);
        return out;
    }

    private static List<String> remove(List<String> report, String line) {
        List<String> out = new ArrayList<>(report);
        out.remove(line);
        return out;
    }

    private static List<String> replace(List<String> report, String from, String to) {
        List<String> out = new ArrayList<>(report);
        out.set(out.indexOf(from), to);
        return out;
    }

    private static List<String> reversed(List<String> report) {
        List<String> out = new ArrayList<>(report);
        java.util.Collections.reverse(out);
        return out;
    }

    /** 把全部 {@code chunk=} 行整体平移（保持 36 个、互不相同），用于"两臂 chunk 全不同"负控。 */
    private static List<String> shiftChunks(List<String> report, int delta) {
        List<String> out = new ArrayList<>(report.size());
        for (String line : report) {
            if (!line.startsWith("chunk=")) { out.add(line); continue; }
            int comma = line.indexOf(',', 6);
            out.add("chunk=" + (Integer.parseInt(line.substring(6, comma)) + delta)
                    + "," + (Integer.parseInt(line.substring(comma + 1)) + delta));
        }
        return out;
    }

    /** 断言门禁为红，且失败说明含给定片段。 */
    private static void gate(String name, List<String> ref, List<String> cand, String mustContain) {
        String why = AcceptanceProbe.backgroundGate(ref, cand);
        boolean ok = why != null && why.contains(mustContain);
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + why);
        if (!ok) System.exit(1);
    }

    /** 断言三参（含期望 chunk 集）门禁为红，且失败说明含给定片段。 */
    private static void gate3(String name, List<String> ref, List<String> cand,
                              Set<String> expected, String mustContain) {
        String why = AcceptanceProbe.backgroundGate(ref, cand, expected);
        boolean ok = why != null && why.contains(mustContain);
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + why);
        if (!ok) System.exit(1);
    }

    // ===== helpers =====

    private static Cell cell(double height, int riverType, double surface, double lip) {
        Cell c = new Cell();
        c.height = height;
        c.riverType = (byte) riverType;
        c.riverSurfaceY = surface;
        c.riverLipY = lip;
        return c;
    }

    /** 合成列（不依赖真实 chunk）。 */
    private static AcceptanceProbe.ColumnView column(int[] waterYs) {
        return AcceptanceProbe.syntheticColumn(waterYs);
    }

    /** 坐标集合掩码。 */
    private static AcceptanceProbe.WetFn mask(int... xz) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < xz.length; i += 2) {
            set.add((((long) xz[i]) << 32) | (xz[i + 1] & 0xFFFFFFFFL));
        }
        return (x, z) -> set.contains((((long) x) << 32) | (z & 0xFFFFFFFFL));
    }

    /** 计划引用：只让给定坐标出水（水面区间固定为 [y+1, y+2]）。 */
    private static AcceptanceProbe.PlanRef planRef(int... xz) {
        java.util.Set<Long> set = coords(xz);
        return (x, z) -> {
            if (!set.contains(pack(x, z))) return null;
            return AcceptanceProbe.plan(cell(170.4, 1, 172.6, 172.6), true, 63.0);
        };
    }

    /** 只让给定坐标"在计划窗口内有水"的替身（离线无真实 chunk）。 */
    private static AcceptanceProbe.WindowWet lookup(int... xz) {
        java.util.Set<Long> set = coords(xz);
        return (x, z, fromY, toY) -> set.contains(pack(x, z));
    }

    private static java.util.Set<Long> coords(int... xz) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < xz.length; i += 2) set.add(pack(xz[i], xz[i + 1]));
        return set;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static void expectPlan(String name, AcceptanceProbe.Plan p, boolean water,
                                   int surfaceY, int waterTopY, int waterHeight, int lipAbove) {
        boolean ok = p.water() == water && p.surfaceY() == surfaceY
                && p.waterTopY() == waterTopY && p.waterHeight() == waterHeight
                && p.lipAboveTop() == lipAbove;
        System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + p);
        if (!ok) System.exit(1);
    }

    private static void expect(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) System.exit(1);
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(1);
    }
}
