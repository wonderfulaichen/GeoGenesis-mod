package com.geogenesis.acceptance;

import com.geogenesis.worldgen.terrain.Cell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 *   <li>水面之上有植被时连通性仍成立（植被不遮水）。</li>
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

        Path dir = Path.of("build", "accept-selfcheck");
        Path report = dir.resolve("acceptance-report.txt");
        AcceptanceProbe.writeReport(report,
                List.of("schema=geogenesis-acceptance/4", "verdict=FAIL"),
                chunks, points, links, coverages);
        String text = Files.readString(report, StandardCharsets.UTF_8);
        String[] required = {
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
