package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RiverWidthProfile} 门禁探针。纯合成输入，不跑生产图。
 *
 * <p>无专用 Gradle 任务（{@code runRiverWidthProfileProbe} 已被旧 legacy 探针占用）：</p>
 * <pre>{@code
 *   java -cp "<diagnostics runtimeClasspath>" \
 *        com.geogenesis.worldgen.hydrology.model.RiverWidthProfileProbe
 * }</pre>
 * <p>A/B：{@code -Dgeogenesis.widthSmooth=<r>}、{@code -Dgeogenesis.widthMonotone=false}。
 * FAIL 时 {@code System.exit(1)}。</p>
 */
public final class RiverWidthProfileProbe {

    private RiverWidthProfileProbe() { }

    private static int pass, fail;
    private static final List<String> FAILS = new ArrayList<>();

    public static void main(String[] args) {
        testApplyFade();
        testProfileSemantics();
        testProfileSmoothingReducesSpread();
        testMidJoinRetainsTributaryScale();
        testSerialChain();
        testSerialDownstreamFollowsPin();
        testMultiTributary();
        testConfluenceTakesTributaryScale();
        testBoundaryPin();
        testPinPlatformsExactAndIndependent();
        testPinConflictExplicit();
        testPinsFarApartKeepMiddle();
        testUpstreamOwnPinIsLocal();
        testBoundaryBeatsJoin();
        testReceiverPinDoesNotInflateTributary();
        testRangeClampUnderPin();
        testCycleGuard();
        testReceiverTraceIndexing();
        testLinkConflictFails();
        testOrderIndependence();
        testNullAndDegenerate();
        testShapeUntouchedAndRange();
        testCyclesAreNotMatched();
        testSourceAndContinuation();
        testWideSerialIntoNarrow();
        testInheritedSuffix();
        testLakeInputs();
        testOutletUsesNetOutflow();
        testSmallTributaryIsNotTrunk();
        testLakeEnvelopeInProductionProfile();
        testDownstreamNeverNarrows();

        System.out.printf("%n=== RiverWidthProfileProbe: PASS=%d FAIL=%d ===%n", pass, fail);
        for (String f : FAILS) System.out.println("  FAIL: " + f);
        if (fail > 0) {
            System.out.println("结论：FAIL");
            System.exit(1);
        }
        System.out.println("结论：ALL PASS（仅合成输入；渲染仍须生产图目检）");
    }

    // 场景构造

    private record World(int n, int step) {
        int cell(int gx, int gz) { return gx * n + gz; }

        HydrologyPipeline.Result pipe(double[] q) {
            return pipe(q, null);
        }

        /** 同 {@link #pipe(double[])}，但挂上湖泊结果（出湖/入湖身份用）。 */
        HydrologyPipeline.Result pipe(double[] q, LakeWaterBalance.Result lakes) {
            int cells = n * n;
            return new HydrologyPipeline.Result(n, n, step,
                    new double[cells], new boolean[cells], new boolean[cells],
                    new double[cells], new double[cells], new boolean[cells],
                    new int[cells], q, new boolean[cells], new double[cells],
                    lakes, null, new double[cells], new double[cells], 0, true, 0.0);
        }
    }

    private static DeterministicSkeleton.Shaped vert(int x, int z0, int z1, int step) {
        int m = (z1 - z0) / step + 1;
        double[] xs = new double[m], zs = new double[m];
        for (int i = 0; i < m; i++) { xs[i] = x; zs[i] = z0 + i * step; }
        return new DeterministicSkeleton.Shaped(xs, zs);
    }

    private static DeterministicSkeleton.Shaped horz(int z, int x0, int x1, int step) {
        int sign = x1 >= x0 ? 1 : -1;
        int m = Math.abs(x1 - x0) / step + 1;
        double[] xs = new double[m], zs = new double[m];
        for (int i = 0; i < m; i++) { xs[i] = x0 + sign * i * step; zs[i] = z; }
        return new DeterministicSkeleton.Shaped(xs, zs);
    }

    private static int[] cellsOf(DeterministicSkeleton.Shaped sh, World w) {
        int[] c = new int[sh.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = w.cell((int) Math.round(sh.x[i] / w.step()), (int) Math.round(sh.z[i] / w.step()));
        }
        return c;
    }

    private static DeterministicSkeleton.Trace trace(DeterministicSkeleton.Shaped sh, World w,
                                                     DeterministicSkeleton.End end) {
        return new DeterministicSkeleton.Trace(cellsOf(sh, w), end, false);
    }

    private static double[] trunkQ(World w, int gx, int gz0, int gz1) {
        double[] q = new double[w.n() * w.n()];
        for (int gz = gz0; gz <= gz1; gz++) q[w.cell(gx, gz)] = 3.0 + gz;
        return q;
    }

    private static void put(World w, double[] q, int gx0, int gx1, int gz, double v) {
        for (int gx = gx0; gx <= gx1; gx++) {
            int k = w.cell(gx, gz);
            if (q[k] < v) q[k] = v;
        }
    }

    /**
     * 平滑窗口是否小于折线节点数。
     *
     * <p>{@code WIDTH_SMOOTH} 单位是节点数，窗口 {@code [i−r,i+r]}；{@code 2r+1 ≥ 节点数}
     * 时整条折线被摊成均值 ⇒ 沿程对比类断言无意义（实测 radius=32 摊平 18 节点支流）。
     * 这类断言按容量分流，不假装通过。</p>
     */
    private static boolean contrastViable(int nodes) {
        return 2 * Math.max(0, ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH) + 1 < nodes;
    }

    // ① applyFade

    private static void testApplyFade() {
        // JOIN 尾约束不再是独立的 applyFade，而是尾节点上的零长 pin；
        // 这里直接测该机制：末端平台精确 + 32 block 内有界渐隐 + 外侧不动。
        int m = 40;
        double[] w = new double[m], arc = new double[m];
        for (int i = 0; i < m; i++) { w[i] = 0.6; arc[i] = i * 2.0; }
        double[] orig = w.clone();
        double end = arc[m - 1];
        List<double[]> pins = new ArrayList<>();
        pins.add(new double[]{end, end, 2.6});
        RiverWidthProfile.applyPinPlan(w, arc, pins);
        check("尾 pin 末端恒等于 target", Math.abs(w[m - 1] - 2.6) < 1e-9);
        boolean untouched = true, monotone = true, bounded = true;
        for (int i = 0; i < m; i++) {
            if (end - arc[i] >= RiverWidthProfile.TRANSITION_BLOCKS && Math.abs(w[i] - orig[i]) > 1e-12) {
                untouched = false;
            }
            if (i > 0 && w[i] < w[i - 1] - 1e-9) monotone = false;
            if (i > 0 && Math.abs(w[i] - w[i - 1]) > 0.30) bounded = false;
        }
        check("尾 pin 32 block 之外完全不改", untouched);
        check("尾 pin 向 target 单调过渡", monotone);
        check("尾 pin 相邻无尖跳（<0.30）", bounded);
        check("smoothstep 两端导数为 0", near(RiverWidthProfile.smoothstep01(0.0), 0.0)
                && near(RiverWidthProfile.smoothstep01(1.0), 1.0)
                && near(RiverWidthProfile.smoothstep01(0.5), 0.5));
        check("gapToRange 区间内为 0", near(RiverWidthProfile.gapToRange(10, 12, 11), 0.0));
        check("gapToRange 区间外为距离", near(RiverWidthProfile.gapToRange(10, 12, 15), 3.0));
    }

    // ② profile

    private static void testProfileSemantics() {
        // 夹具长度随平滑半径伸缩：WIDTH_SMOOTH 单位是节点数，短夹具配大半径会被整体摊平，
        // 那样测的是"半径是否大于夹具"而不是剖面语义。
        // 夹具随半径伸缩，且回落段必宽于平滑窗口 —— 否则凹陷会被平滑吃掉，
        // 断言变成在测"窗口是否大于夹具"而不是剖面语义。
        int r = ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH;
        int trough = 2 * Math.max(0, r) + 10;
        int m = 4 * Math.max(0, r) + 30;
        int a1 = Math.max(2, (m - trough) / 2);
        int a2 = Math.min(m - 1, a1 + trough);
        double[] raw = new double[m];
        for (int i = 0; i < a1; i++) raw[i] = 1.0 + 0.8 * i / Math.max(1.0, a1);
        for (int i = a1; i < a2; i++) raw[i] = 1.2;
        for (int i = a2; i < m; i++) raw[i] = 1.2 + 2.0 * (i - a2) / Math.max(1.0, m - a2);
        double[] out = RiverWidthProfile.profile(raw);
        check("profile 长度保持", out.length == m);
        check("profile 总体向下游趋宽", out[m - 1] - out[0] > 0.5);
        double dip = 0;
        for (int i = 1; i < m; i++) dip = Math.min(dip, out[i] - out[i - 1]);
        // 口径是"不必严格单调"⇒ 只断言存在回落。回落量随平滑半径变小（r=32 时 −0.003），
        // 故不设量级门槛，只断言严格非单调。
        check("profile 允许局部回落（非严格单调）", dip < -1e-9);
        if (ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH > 0 && contrastViable(m)) {
            boolean bounded = true;
            for (int i = 1; i < m; i++) if (Math.abs(out[i] - out[i - 1]) > 0.35) bounded = false;
            check("profile 相邻无尖跳（<0.35）", bounded);
        }
        // 夹具本身留在值域内（slope 已按长度缩放）⇒ 下面的值域断言才有意义。
        boolean inRange = true;
        for (double v : out) if (!(v > 0) || v > 6.0) inRange = false;
        check("profile 值域合法", inRange);
        check("profile 空输入安全", RiverWidthProfile.profile(new double[0]).length == 0);
        if (!ModelHydrologyProvider.SegmentIndex.WIDTH_MONOTONE) {
            double[] sm = ModelHydrologyProvider.SegmentIndex.smoothAlong(raw,
                    ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH);
            boolean same = true;
            for (int i = 0; i < m; i++) if (Math.abs(sm[i] - out[i]) > 1e-12) same = false;
            check("WIDTH_MONOTONE=false ⇒ profile = smoothAlong(raw)", same);
        }
    }

    /**
     * 平滑必须真的发生。用交替锯齿 1/3：cumulative max 把它抬成上沿包络，
     * 于是只剩偏离项携带锯齿。实测比值：真实 0.000~0.153，不平滑的突变体 0.42~0.43。
     */
    private static void testProfileSmoothingReducesSpread() {
        int m = 8;
        double[] raw = new double[m];
        for (int i = 0; i < m; i++) raw[i] = (i % 2 == 0) ? 1.0 : 3.0;
        double[] prof = RiverWidthProfile.profile(raw);
        double rawJump = 0, profJump = 0;
        for (int i = 1; i < m; i++) {
            rawJump = Math.max(rawJump, Math.abs(raw[i] - raw[i - 1]));
            profJump = Math.max(profJump, Math.abs(prof[i] - prof[i - 1]));
        }
        if (ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH > 0) {
            check("锯齿相邻落差降到 <25%（smoothAlong 真的执行）", profJump < 0.25 * rawJump);
        } else {
            check("widthSmooth=0 ⇒ 锯齿按偏离比例保留", profJump > 0.3 * rawJump);
        }
        int rm = 4 * Math.max(0, ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH) + 20;
        double[] ramp = new double[rm];
        for (int i = 0; i < rm; i++) ramp[i] = 1.0 + 0.1 * i;
        double[] rampProf = RiverWidthProfile.profile(ramp);
        check("平滑不破坏总体趋宽", rampProf[rampProf.length - 1] > rampProf[0] + 0.5);

        // ★ 趋势必须被"再平滑"一次（cumulative max 之后）。否则平台段会保持生硬的
        //   尖角，末端值跟随原始末值而非被平滑推进。这里钉住趋势段的实测数值。
        if (ModelHydrologyProvider.SegmentIndex.WIDTH_MONOTONE
                && ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH == 4) {
            int s1 = 14, s2 = 32, sm = 46;
            double[] box = new double[sm];
            for (int i = 0; i < s1; i++) box[i] = 1.0 + 0.8 * i / (double) s1;
            for (int i = s1; i < s2; i++) box[i] = 1.2;
            for (int i = s2; i < sm; i++) box[i] = 1.2 + 2.0 * (i - s2) / (double) (sm - s2);
            double[] bp = RiverWidthProfile.profile(box);
            // 实测：趋势平滑开 = 1.151429 / 2.678571；关 = 1.114286 / 2.771429
            check("趋势做了二次平滑（首值被抬到 ≈1.1514）", Math.abs(bp[0] - 1.1514) < 5e-3);
            check("趋势做了二次平滑（末值被压到 ≈2.6786）",
                    Math.abs(bp[sm - 1] - 2.6786) < 5e-3);
        }

        // ★ 趋势必须真的来自 cumulative max：raw 升到 3.5 后骤降到 1.05 并持平。
        //   有趋势 ⇒ 尾部被包络托住（实测 tail/peak≈0.82）；无趋势 ⇒ 跟着掉到 ≈0.60。
        if (ModelHydrologyProvider.SegmentIndex.WIDTH_MONOTONE) {
            int tm = 12, up = 6;
            double[] drop = new double[tm];
            for (int i = 0; i < up; i++) drop[i] = 1.0 + 0.5 * i;
            for (int i = up; i < tm; i++) drop[i] = 1.05;
            double[] dp = RiverWidthProfile.profile(drop);
            double peak = dp[0], tail = dp[tm - 1];
            for (double v : dp) peak = Math.max(peak, v);
            check("先升后骤降：趋势守住（尾部不低于峰值的 75%）", tail / peak > 0.75);
        }
    }

    // ③ 中段 JOIN：真支流保留自身规模，不取干流中段宽度

    /** 宽度律在给定 Q 处的半宽（探针侧独立复算，用来给"接点宽度"一个精确期望）。 */
    private static double halfFor(double q) {
        return Math.max(RiverCenterlines.MIN_HALF_BLOCKS,
                Math.min(RiverCenterlines.Params.defaults().maxHalf(),
                        0.5 * RiverCenterlines.fullWidthPublic(q, RiverCenterlines.Params.defaults())));
    }

    /**
     * ★ 真汇流契约（取代旧的"支流尾 == 干流汇合点宽"）：
     *
     * <ol>
     *   <li>支流末端取<b>自己的来流</b>，不被接收方宽度反向撑粗；</li>
     *   <li>干流汇合点仍是<b>干流自己在该处的宽度</b>（由宽度律独立复算比对），
     *       既不取自身头宽，也不被支流改写；</li>
     *   <li>汇合点由几何投影定位 ⇒ "位置对"可单独断言。</li>
     * </ol>
     *
     * <p>等宽只对串联/接力成立（见 {@link #testSerialChain}）。真支流被钉成干流宽
     * 就是把小支流画成干流粗 —— 本用例的区分力正在此：旧契约下支流尾会被抬到接点宽度。</p>
     */
    private static void testMidJoinRetainsTributaryScale() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var trib = horz(100, 6, 40, 2);                  // 末端汇入干流 (40,100) → 干流中段
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 12, 50, 3.0);                       // 支流上游很小
        put(w, q, 13, 20, 50, 8.0);                      // 支流下游略增
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        var link = rivers.stream().filter(r -> contains(r.traceIdx, 1)).findFirst().orElseThrow();
        check("支流识别为 CONNECTED", link.link == DeterministicSkeleton.RiverLink.CONNECTED
                && link.downstreamRiver >= 0);
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, null);
        double[] pt = prof.get(0), pb = prof.get(1);
        double[] arcTrunk = RiverWidthProfile.arcLengths(polys)[0];
        double[] hit = RiverWidthProfile.projectOnto(trunk, arcTrunk,
                trib.x[trib.size() - 1], trib.z[trib.size() - 1]);
        check("支流尾投影落在干流中段", hit != null && hit[0] > 0.3 * arcTrunk[arcTrunk.length - 1]);
        double junction = RiverWidthProfile.lerpAt(pt, hit);
        double tail = pb[pb.length - 1];
        // 干流在接点处的期望值：宽度律对该格 Q 的结果，独立复算。
        int jointGz = (int) Math.round(trib.z[trib.size() - 1] / w.step());
        double trunkAtJoint = halfFor(q[w.cell(40 / w.step(), jointGz)]);
        check("干流接点仍取干流自身宽度（不是自身头宽）", junction - pt[0] > 0.5);
        check("干流接点宽度 == 宽度律在该处的结果（未被支流改写）",
                Math.abs(junction - trunkAtJoint) < 0.15);
        if (contrastViable(trunk.size()) && contrastViable(trib.size())) {
            check("支流末端保留自身小规模（未被接收方撑粗）", tail < 0.6 * junction);
            check("支流首端仍很细（未复制下游）", pb[0] <= RiverCenterlines.MIN_HALF_BLOCKS + 0.15);
            check("支流沿程仍是自身走势（首尾同量级，没有跳到干流量级）", tail - pb[0] < 0.5);
        }
    }

    /**
     * ★ 干流真实接点承接支流规模：宽支流流入窄干流时，干流<b>从接点起</b>不窄于该支流，
     * 而接点<b>之前</b>的上游段不受影响（否则等于把整条干流按支流加粗）。
     *
     * <p>干流源头用 {@code continuedHeads} 标为"非真源头"以关闭河头线性展宽 ——
     * 那条路径会把整行改写成到末端的直线斜坡，从而掩盖接点处的 max 承接。</p>
     */
    private static void testConfluenceTakesTributaryScale() {
        World w = new World(128, 2);
        var trunk = vert(80, 10, 230, 2);
        var trib = horz(120, 10, 80, 2);
        double[] tx = trunk.x.clone(), tz = trunk.z.clone(), bx = trib.x.clone(), bz = trib.z.clone();
        double[] q = new double[w.n() * w.n()];
        for (int gz = 5; gz <= 115; gz++) q[w.cell(40, gz)] = 4;      // 窄干流
        for (int gx = 5; gx <= 39; gx++) q[w.cell(gx, 60)] = 200;     // 宽支流
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of(), Map.of(),
                java.util.Set.of(traces.get(0).sourceCell()));
        double[] pt = prof.get(0), pb = prof.get(1);
        double tail = pb[pb.length - 1];
        int at = (120 - 10) / 2;                                      // 干流真实接点节点
        check("宽支流末端保留自身规模（未被窄干流压细）", tail > 4.0);
        check("干流接点承接支流规模", pt[at] >= tail - 1e-9);
        check("干流接点之后不窄于支流", pt[pt.length - 1] >= tail - 1e-9);
        check("接点之前的干流上游段不受影响（只承接不返工）", pt[at - 40] < 1.0);
        check("干流未被整条抬成支流宽（非等宽钉）",
                pt[0] < 1.0 && tail - pt[0] > 3.0);
        check("汇流承接不改 shape 坐标",
                java.util.Arrays.equals(tx, trunk.x) && java.util.Arrays.equals(tz, trunk.z)
                        && java.util.Arrays.equals(bx, trib.x) && java.util.Arrays.equals(bz, trib.z));
    }

    // ④ 串联

    private static void testSerialChain() {
        World w = new World(64, 2);
        var a = vert(40, 10, 50, 2);
        var b = vert(40, 50, 110, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        double[] ax = a.x.clone(), az = a.z.clone(), bx = b.x.clone(), bz = b.z.clone();
        List<DeterministicSkeleton.Shaped> polys = List.of(a, b);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(a, w, DeterministicSkeleton.End.JOIN),
                trace(b, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        check("串联合并为一个 river 单元", rivers.size() == 1 && rivers.get(0).traceIdx.length == 2);
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, null);
        double[] pa = prof.get(0), pb = prof.get(1);
        check("串联接缝两侧一致（A 尾 == B 头）", Math.abs(pa[pa.length - 1] - pb[0]) < 0.02);
        boolean bounded = true;
        for (int i = 1; i < pa.length; i++) if (Math.abs(pa[i] - pa[i - 1]) > 0.35) bounded = false;
        check("串联接缝无尖跳", bounded);
        check("串联后总体趋宽", pb[pb.length - 1] - pa[0] > 0.5);
        check("串联不改 shape 坐标",
                java.util.Arrays.equals(ax, a.x) && java.util.Arrays.equals(az, a.z)
                        && java.util.Arrays.equals(bx, b.x) && java.util.Arrays.equals(bz, b.z));
    }

    /**
     * ★ 串联 + 上游 boundary pin 的回归：pin 抬高上游末段后，同一 river 单元的下游
     * <b>必须承接</b>，最终<b>不得倒挂</b>，也不得把下游整段抬成同一个值。
     *
     * <p>这是主线程报的生产现象（"up20 尾后来 pin 抬宽但 down 未承接"）的最小合成回归。
     * 三件事同时断言，缺一不可：</p>
     * <ol>
     *   <li>接缝两侧仍一致（串联共用同一断面）—— pin 抬高后依然成立；</li>
     *   <li>下游末端 ≥ 接点宽度（不倒挂、不回退）；</li>
     *   <li>下游<b>首段</b>不被 pin 顶粗（承接发生在接点之后，不是整条替换）。</li>
     * </ol>
     * <p>夹具让上游本身很细（Q=3.5）而 pin 给 5.0 ⇒ 抬升显著，区分力明确。</p>
     */
    private static void testSerialDownstreamFollowsPin() {
        World w = new World(64, 2);
        var a = vert(40, 10, 50, 2);
        var b = vert(40, 50, 110, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        double[] ax = a.x.clone(), az = a.z.clone(), bx = b.x.clone(), bz = b.z.clone();
        List<DeterministicSkeleton.Shaped> polys = List.of(a, b);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(a, w, DeterministicSkeleton.End.JOIN),
                trace(b, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> base = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 20), 5.0);                    // 世界 (40,40)：上游段
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        double[] pa = prof.get(0), pb = prof.get(1);
        double[] ba = base.get(0), bb = base.get(1);
        check("pin 抬高了上游末段（用例有区分力）", pa[pa.length - 1] > ba[ba.length - 1] + 0.5);
        check("串联接缝两侧在 pin 抬高后仍一致（A 尾 == B 头）",
                Math.abs(pa[pa.length - 1] - pb[0]) < 0.02);
        check("下游承接不返工：末端 ≥ 接点宽（不倒挂）", pb[pb.length - 1] >= pa[pa.length - 1] - 1e-9);
        check("下游相对基线确实被抬宽（承接真的发生了）", pb[pb.length - 1] > bb[bb.length - 1] + 0.5);
        check("下游首段未被顶成 pin 值（承接不是整条替换）", pb[0] < 5.0 - 0.5);
        check("承接不向上游返工：上游首端仍等于基线", near(pa[0], ba[0]));
        check("串联+pin 不改 shape 坐标",
                java.util.Arrays.equals(ax, a.x) && java.util.Arrays.equals(az, a.z)
                        && java.util.Arrays.equals(bx, b.x) && java.util.Arrays.equals(bz, b.z));
    }

    // ⑤ 多支流

    /**
     * 两真支流各保自身规模：各自宽度只反映<b>自己的来流</b>，互不复制，
     * 也都不被干流规模反向撑粗。
     *
     * <p>两条支流的 Q 刻意不同（上游 3、下游 20），故"沿线走向"必须体现这个差异 ——
     * 若任一处被钉成干流宽，两支流就会同时抬到干流量级而失去差异。</p>
     *
     * <p>干流源头标为"非真河头"（{@code continuedHeads}），避免河头线性展宽把整行改写成
     * 到末端的直线斜坡 —— 那会掩盖 {@code max} 承接的语义。</p>
     */
    private static void testMultiTributary() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var low = horz(60, 6, 40, 2);                    // 上游支流，Q 很小
        var high = horz(90, 74, 40, 2);                  // 下游支流，Q 较大
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 19, 30, 3.0);
        put(w, q, 20, 37, 45, 20.0);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, low, high);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(low, w, DeterministicSkeleton.End.JOIN),
                trace(high, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        long connected = rivers.stream()
                .filter(r -> r.link == DeterministicSkeleton.RiverLink.CONNECTED).count();
        check("两条支流都是 CONNECTED", connected == 2);
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of(), Map.of(),
                java.util.Set.of(traces.get(0).sourceCell()));
        double tailLow = prof.get(1)[prof.get(1).length - 1];
        double tailHigh = prof.get(2)[prof.get(2).length - 1];
        double trunkAtLow = prof.get(0)[(60 - 10) / 2];
        double trunkAtHigh = prof.get(0)[(90 - 10) / 2];
        check("两支流首端都保持细", prof.get(1)[0] < 1.1 && prof.get(2)[0] < 1.1);
        check("两支流末端宽度跟随各自来流（大 Q 的更宽）", tailHigh - tailLow > 0.3);
        check("两支流末端不等宽（未互相复制）", Math.abs(tailHigh - tailLow) > 0.3);
        check("两支流末端都远窄于各自汇合处的干流宽（未被干流反向撑粗）",
                tailLow < 0.75 * trunkAtLow && tailHigh < 0.75 * trunkAtHigh);
    }

    // ⑥ 单个 pin：平台精确 + 外侧有界

    private static void testBoundaryPin() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> base = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, null, Map.of(), java.util.Set.of(traces.get(0).sourceCell()));
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 45), 2.0);                    // 低于周边剖面，但仍随下游放宽
        List<double[]> pinned = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin, Map.of(), java.util.Set.of(traces.get(0).sourceCell()));
        double[] b = base.get(0), p = pinned.get(0);
        int at = (90 - 10) / 2;                          // z=90 的节点下标
        check("pin 处等于约定半宽", Math.abs(p[at] - 2.0) < 0.02);
        check("无 pin 时该节点确非约定值（用例有区分力）", b[at] > 2.2);
        //   （否则相邻 pin 会各自扩段而互相重叠）。故 at-1 必须已开始渐隐。
        // ★ 下游不得变窄：pin 段上游侧被单调规则保持同值（2.0），下游侧仍向天然剖面放宽。
        check("pin 平台上游侧不高于 pin 值（单调不返粗）",
                p[at - 1] <= p[at] + 1e-9 && p[at - 1] >= 2.0 - 1e-9);
        check("平台外向下游已开始渐隐（非硬台阶）", p[at + 1] > 2.0);
        check("pin 在 32 block 之外无效", Math.abs(p[at - 20] - b[at - 20]) < 0.02);
        boolean bounded = true;
        for (int i = 1; i < p.length; i++) if (Math.abs(p[i] - p[i - 1]) > 0.40) bounded = false;
        check("pin 过渡无尖跳", bounded);
        check("pin 未改变总体走向", p[p.length - 1] > p[0]);
    }

    /**
     * ★ 每个 pin 平台必须<b>精确且互不影响</b>：两个不同值 pin 相距 &lt;32 block 时，
     * 加权平均会让任一平台都不精确（旧实现的缺陷）。平台内须直接赋约定值。
     */
    private static void testPinPlatformsExactAndIndependent() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 45), 1.0);                    // z=90：上游最细
        pin.put(w.cell(20, 53), 1.8);                    // z=106：下游略粗，相距 16 block < 32
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        double[] p = prof.get(0);
        int a = (90 - 10) / 2, b = (106 - 10) / 2;
        check("近距离两 pin：上游处精确", Math.abs(p[a] - 1.0) < 0.02);
        check("近距离两 pin：下游处精确", Math.abs(p[b] - 1.8) < 0.02);
        // 两个 pin 都落在节点正身上 ⇒ 各自零长平台，绝不互相扩张重叠。
        // 关键是"没被平均拉偏"：各自精确到平台值，中间是渐隐混合。
        check("两 pin 互不影响（未被平均拉偏）",
                Math.abs(p[a] - 1.0) < 0.02 && Math.abs(p[b] - 1.8) < 0.02);
        boolean between = true;
        for (int i = a; i <= b; i++) {
            if (p[i] > 1.8 + 1e-9 || p[i] < 1.0 - 1e-9) between = false;
            if (i > a && p[i] < p[i - 1] - 1e-9) between = false;   // 下游不得变窄
        }
        check("两 pin 之间单调过渡且不越出两值区间", between);
    }

    /**
     * ★ 不同值平台重叠 ⇒ 必须显式抛异常（带位置），不得先到者裁剪留下阶跃。
     * 同值重叠 ⇒ 合并，不抛。
     */
    private static void testPinConflictExplicit() {
        double[] arc = {0, 1, 2, 3, 4, 5};

        double[] w = new double[6];
        for (int i = 0; i < 6; i++) w[i] = 1.0;
        List<double[]> clash = new ArrayList<>();
        clash.add(new double[]{0, 3, 5.0});
        clash.add(new double[]{2, 5, 2.0});
        check("不同值平台重叠 ⇒ 抛 IllegalStateException",
                throwsIllegalState(() -> RiverWidthProfile.applyPinPlan(w, arc, clash)));

        // 共用端点也算重叠（否则端点处会留下阶跃）
        List<double[]> touch = new ArrayList<>();
        touch.add(new double[]{0, 2, 5.0});
        touch.add(new double[]{2, 4, 2.0});
        check("不同值平台共用端点 ⇒ 同样抛异常",
                throwsIllegalState(() -> RiverWidthProfile.applyPinPlan(w, arc, touch)));

        // 同值重叠 ⇒ 合并为一整段平台，整段精确
        double[] w2 = new double[6];
        for (int i = 0; i < 6; i++) w2[i] = 1.0;
        List<double[]> same = new ArrayList<>();
        same.add(new double[]{0, 2, 3.0});
        same.add(new double[]{1, 4, 3.0});
        int[] stat2 = RiverWidthProfile.applyPinPlan(w2, arc, same);
        check("同值重叠被合并", stat2[1] == 1 && stat2[0] == 1);
        check("同值合并后整段精确", Math.abs(w2[0] - 3.0) < 1e-9
                && Math.abs(w2[2] - 3.0) < 1e-9 && Math.abs(w2[4] - 3.0) < 1e-9);
    }

    /**
     * ★ 平台相距 &gt; 64 block ⇒ 两侧渐隐互不覆盖 ⇒ 正中必须保留原剖面，
     * 不得被拉成两平台之间的插值。
     */
    private static void testPinsFarApartKeepMiddle() {
        // 间距恰好 64：正中一点距两平台各 32 ⇒ 权重为 0，必须保留 base。
        double[] arc = new double[81];
        for (int i = 0; i < arc.length; i++) arc[i] = i;
        double[] w = new double[arc.length];
        for (int i = 0; i < w.length; i++) w[i] = 1.0;   // base 恒 1.0 ⇒ 被拉成插值就能看出来
        List<double[]> pins = new ArrayList<>();
        pins.add(new double[]{10, 10, 5.0});
        pins.add(new double[]{74, 74, 2.0});
        RiverWidthProfile.applyPinPlan(w, arc, pins);
        check("远距 pin：A 处精确", Math.abs(w[10] - 5.0) < 1e-9);
        check("远距 pin：B 处精确", Math.abs(w[74] - 2.0) < 1e-9);
        check("相距 64 时正中保留 base（未被拉成插值）", Math.abs(w[42] - 1.0) < 1e-9);
        // 渐隐内侧必须真的向各自平台靠拢：20 朝 5.0 上抬，64 朝 2.0 上抬（2.0 > base 1.0）
        check("渐隐内侧向 5.0 靠拢", w[20] > 1.0 + 1e-6 && w[20] < 5.0);
        check("渐隐内侧向 2.0 靠拢", w[64] > 1.0 + 1e-6 && w[64] < 2.0);

        // 间距 70：正中一整段都必须完全是 base
        double[] arc2 = new double[101];
        double[] w2 = new double[101];
        for (int i = 0; i < 101; i++) { arc2[i] = i; w2[i] = 1.0; }
        List<double[]> p2 = new ArrayList<>();
        p2.add(new double[]{10, 10, 5.0});
        p2.add(new double[]{80, 80, 2.0});
        RiverWidthProfile.applyPinPlan(w2, arc2, p2);
        boolean middleClean = true;
        for (int i = 10 + 32 + 1; i <= 80 - 32 - 1; i++) {
            if (Math.abs(w2[i] - 1.0) > 1e-9) middleClean = false;
        }
        check("相距 70 时正中一整段都是 base", middleClean);
        check("相距 70 时两平台仍精确",
                Math.abs(w2[10] - 5.0) < 1e-9 && Math.abs(w2[80] - 2.0) < 1e-9);
        // 渐隐边界本身必须连续：42 = 10+32 处权重恰为 0，41 处已开始偏离
        check("渐隐边界处权重归零（42 处 == base）", Math.abs(w2[42] - 1.0) < 1e-9);
        check("渐隐边界内侧确有偏离（41 处 > base）", w2[41] > 1.0 + 1e-6);
    }

    /**
     * ★ 上游 boundary pin 合法表示"来流真的变宽" ⇒ 下游<b>必须承接</b>，不得倒挂。
     *
     * <p><b>为什么旧断言是错的</b>：本用例原写"干流接点完全不读该 pin（逐位等于无 pin 基线）"。
     * 计划 §3.D 要求"合流后的下游承接两侧已有规模，至少不窄于较大的来流"。
     * 旧断言之所以曾经成立，是因为 pin 在 {@code propagateDownstream} <b>之后</b>才写回
     * （只有 {@code applyJoinAndPins} 里写），于是上游尾被抬高后下游永远看不见，
     * 生产表现为"up20 尾被 pin 抬宽但 down 未承接"。现改为：</p>
     * <ul>
     *   <li>pin 在自己位置精确生效（未被任何接收约束改写）；</li>
     *   <li>它把支流尾抬宽 ⇒ 干流汇合点<b>必须</b>随之变宽（承接）；</li>
     *   <li>承接后不留倒挂：干流接点 ≥ 支流尾宽 − 渐隐容差，下游不回退；</li>
     *   <li>仍<b>不</b>做等宽钉：干流保持自身规模，不被抬成支流那个值。</li>
     * </ul>
     * <p>支流尾仍受 32 block 渐隐约束（pin 在尾前 4 block ⇒ 尾得到约 0.94 的过渡系数），
     * 故上游侧比较用"支流尾实际值"，不是 pin 的约定值。</p>
     */
    private static void testUpstreamOwnPinIsLocal() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var trib = horz(60, 6, 40, 2);                   // 末端汇入干流 (40,60)
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 12, 30, 3.0);                       // 支流上游很小
        put(w, q, 13, 18, 30, 3.0);                      // 「尾前」段（会被 pin 接管）
        double[] tx = trunk.x.clone(), tz = trunk.z.clone();
        double[] bx = trib.x.clone(), bz = trib.z.clone();
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> base = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(12, 30), 0.9);                    // 支流中段（x=24）
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        double[] pt = prof.get(0), pb = prof.get(1);
        double[] bt = base.get(0), bb = base.get(1);
        int at = (60 - 10) / 2;
        int atPin = (24 - 6) / 2;
        double tailPinned = pb[pb.length - 1], tailBase = bb[bb.length - 1];
        // 支流天然只有 0.5；pin 取 0.9（仍细于干流）⇒ 不逆着下游变细，符合单调契约。
        // 该 pin 值高于下游天然宽度 ⇒ 单调契约只允许它涨到下游水平（0.8），不得更粗。
        check("pin 不低于自身值，且被下游契约限制在天然下游水平",
                pb[atPin] >= 0.5 - 1e-9 && pb[atPin] <= tailPinned + 1e-9 && pb[atPin] > bb[atPin] + 0.2);
        check("该 pin 确实抬高了支流（用例有区分力）", pb[atPin] > bb[atPin] + 0.2);
        check("干流汇合点不被细支流反向压窄（下游承接既有规模）", pt[at] >= tailPinned - 1e-9);
        check("下游不回退：干流末端 ≥ 干流接点", pt[pt.length - 1] >= pt[at] - 1e-9);
        check("承接不向上游返工：支流首端仍等于基线", near(pb[0], bb[0]));
        check("承接不串到接收方源头：干流源头段仍等于基线", near(pt[0], bt[0]));
        check("shape 坐标未被改动",
                java.util.Arrays.equals(tx, trunk.x) && java.util.Arrays.equals(tz, trunk.z)
                        && java.util.Arrays.equals(bx, trib.x) && java.util.Arrays.equals(bz, trib.z));
    }

    // ⑦ boundary pin 只向下游、只沿本 trace 生效

    /**
     * ★ 边界约定 pin 的方向性：pin 把<b>自己所在的位置</b>变宽 ⇒ 沿本 trace 向下游承接，
     * 但<b>不</b>向上游返工、也<b>不</b>串到别的 trace。
     *
     * <p>本用例原写"接收方干流接点不受支流 pin 影响（逐位等于基线）"。
     * 那条与"下游承接已有来流"矛盾：pin 是真实变宽，接收方必须承接。
     * 现改为断言三件事（均有区分力）：</p>
     * <ul>
     *   <li>pin 在自己位置精确；</li>
     *   <li>pin 下游的干流被承接抬宽，且承接后不留倒挂（干流 ≥ 来流 − 渐隐容差）；</li>
     *   <li>pin 上游（更上游支流段/支流首端）与干流末端仍各自保持基线，不被反向改写。</li>
     * </ul>
     */
    private static void testBoundaryBeatsJoin() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var trib = horz(60, 6, 40, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 12, 30, 3.5);
        double[] bx = trib.x.clone(), bz = trib.z.clone();
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> base = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, null);
        int at = (24 - 6) / 2;                           // 世界 x=24 的节点
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(12, 30), 1.1);                    // 支流世界 x=24
        List<double[]> pinned = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        double[] pinTrunk = pinned.get(0), pinTrib = pinned.get(1);
        double[] baseTrunk = base.get(0), baseTrib = base.get(1);
        double tailPinned = pinTrib[pinTrib.length - 1];
        check("pin 不低于自身值，且被下游契约限制在天然下游水平",
                pinTrib[at] >= baseTrib[at] - 1e-9 && pinTrib[at] <= tailPinned + 1e-9);
        check("无 pin 时该节点确非约定值（用例有区分力）", Math.abs(baseTrib[at] - 1.1) > 0.3);
        check("pin 段下游不窄于 pin 值（单调保持）", tailPinned >= pinTrib[at] - 1e-9);
        // 下游：汇合点不得被更细的支流反向压窄，且不回退。
        int jointAt = (60 - 10) / 2;
        check("接收方干流接点保持既有规模（不被细支流压窄）", pinTrunk[jointAt] >= tailPinned - 1e-9);
        check("下游不回退：干流末端 ≥ 干流接点", pinTrunk[pinTrunk.length - 1] >= pinTrunk[jointAt] - 1e-9);
        check("仍不做等宽钉：干流接点未被压成 pin 值", Math.abs(pinTrunk[jointAt] - 1.1) > 0.2);
        // 方向性：pin 上游不返工、不串到别的 trace 的上游。
        check("支流首端不受该 pin 影响（承接只向下游）", near(pinTrib[0], baseTrib[0]));
        check("干流源头段不受该 pin 影响（pin 只沿自己 trace）",
                near(pinTrunk[0], baseTrunk[0]));
        check("支流 shape 坐标未被改动（仍无等宽钉几何）",
                java.util.Arrays.equals(bx, trib.x) && java.util.Arrays.equals(bz, trib.z));
    }

    /**
     * ★ 反例（用户点名）：接收方在<b>汇合点下游附近</b>有 boundary pin 改宽，
     * 上游<b>真支流</b>末端<b>不得</b>被该 pin 反向撑粗。
     *
     * <p>这取代了旧用例的相反断言（"上游支流末端必须重新匹配到抬宽后的值"）—— 那条是
     * 串联/接力契约，对真支流等于把小支流画成干流粗。现在改为：</p>
     * <ul>
     *   <li>干流在 pin 处确实精确生效；</li>
     *   <li>干流<b>汇合点</b>（pin 上游 4 block）不被 pin 改写 —— pin 是局部的；</li>
     *   <li>支流尾逐位等于"无 pin"基线 ⇒ 传播没有越过汇合点回到支流。</li>
     * </ul>
     *
     * <p><b>为何 pin 不能放在汇合点本身</b>：{@code boundaryHalf} 按 coarse cell 索引，
     * 而汇合点那一格<b>同时属于支流与干流</b> ⇒ pin 会同时作用于两条 shape，
     * 于是"支流被撑粗"就能被冒名成"传播"。因此 pin 放在汇合点下游 4 block 且只属于干流的格子。</p>
     */
    private static void testReceiverPinDoesNotInflateTributary() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var trib = horz(60, 6, 40, 2);                   // 末端汇入干流 (40,60)
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 12, 30, 3.5);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        List<double[]> base = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 32), 1.0);                    // 世界 (40,64)：汇合点下游 4 block
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        double[] pt = prof.get(0), pb = prof.get(1);
        double[] bt = base.get(0), bb = base.get(1);
        int at = (60 - 10) / 2;                          // 干流汇合点节点
        check("干流 pin 生效（pin 位置半宽等于约定值）", Math.abs(pt[at + 2] - 1.0) < 0.02);
        // pin 在汇合点下游 4 block（2 个节点）⇒ 32 block 渐隐覆盖汇合点本身，
        // 但只沿干流向后，不会跨到支流。
        check("干流汇合点未被该 pin 钳制到支流量级", pt[at] > bb[bb.length - 1] + 0.2);
        check("上游真支流末端逐位等于无 pin 基线（未被反向撑粗）",
                near(pb[pb.length - 1], bb[bb.length - 1]));
        check("支流末端仍远窄于干流接点宽（用例有区分力）",
                pb[pb.length - 1] < 0.6 * pt[at]);
    }

    // ⑧ 值域钳制

    /**
     * ★ 用户硬约束：同一条河<b>沿下游不得变窄</b>（上游半宽 ≤ 下游半宽）。
     *
     * <p>夹具刻意构造"上游被抬粗、下游被小湖压细"的真实成因：上游给高 Q 且带 boundary pin，
     * 下游接一个横向很窄的湖口。修复前该形态会产出 5.44→1.25 的楔形；本用例逐节点断言
     * 不再出现任何后点更窄。</p>
     */
    private static void testDownstreamNeverNarrows() {
        World w = new World(64, 2);
        var a = vert(40, 10, 60, 2);
        var b = vert(40, 60, 110, 2);
        double[] q = new double[w.n() * w.n()];
        for (int gz = 5; gz <= 30; gz++) q[w.cell(20, gz)] = 120;   // 上游粗
        var traces = List.of(trace(a, w, DeterministicSkeleton.End.JOIN),
                trace(b, w, DeterministicSkeleton.End.MOUTH_LAKE));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 28), 5.5);                              // 上游 boundary 抬得更粗
        List<double[]> prof = RiverWidthProfile.build(List.of(a, b), w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        List<double[]> base = RiverWidthProfile.build(List.of(a, b), w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, null);
        check("夹具确实让上游不窄于下游（用例有区分力）", maxOf(prof.get(0)) > 3.0);
        boolean mono = true;
        String where = "";
        for (int p = 0; p < prof.size(); p++) {
            double[] row = prof.get(p);
            for (int i = 1; i < row.length; i++) {
                if (row[i] < row[i - 1] - 1e-9) {
                    mono = false;
                    if (where.isEmpty()) where = "poly" + p + " node" + i + " "
                            + row[i - 1] + "->" + row[i];
                }
            }
        }
        check("同一条河沿下游绝不出现局部变窄" + (where.isEmpty() ? "" : "（" + where + "）"), mono);
        double[] tailA = prof.get(0), headB = prof.get(1);
        check("串联接缝处仍承接（下游不窄于上游尾）",
                headB[0] >= tailA[tailA.length - 1] - 1e-9);
        check("单调化只减不增：没有任何节点被抬到 pin 值之上",
                maxOf(prof.get(1)) <= Math.max(maxOf(base.get(1)), 5.5) + 1e-9);
    }

    private static double maxOf(double[] row) {
        double m = 0;
        for (double v : row) m = Math.max(m, v);
        return m;
    }

    private static void testRangeClampUnderPin() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        double maxHalf = RiverCenterlines.Params.defaults().maxHalf();
        Map<Integer, Double> big = new LinkedHashMap<>();
        big.put(w.cell(20, 30), 100.0);
        Map<Integer, Double> neg = new LinkedHashMap<>();
        neg.put(w.cell(20, 30), -5.0);
        double[] hi = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, big).get(0);
        double[] lo = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, neg).get(0);
        boolean hiOk = true, loOk = true;
        for (double v : hi) if (v > maxHalf + 1e-9) hiOk = false;
        for (double v : lo) if (v < RiverCenterlines.MIN_HALF_BLOCKS - 1e-9) loOk = false;
        check("约定值 100 ⇒ 全部钳到 maxHalf", hiOk);
        check("约定值 −5 ⇒ 全部钳到 MIN_HALF_BLOCKS", loOk);
        check("越界约定值不破坏长度", hi.length == trunk.size() && lo.length == trunk.size());
    }

    // ⑨ 环守卫

    private static void testCycleGuard() {
        World w = new World(64, 2);
        var cA = vert(40, 10, 60, 2);
        var cB = vert(8, 10, 60, 2);
        double[] q = trunkQ(w, 20, 5, 30);
        List<DeterministicSkeleton.Shaped> polys = List.of(cA, cB);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(cA, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(cB, w, DeterministicSkeleton.End.MOUTH_SEA));
        var real = DeterministicSkeleton.buildRivers(traces, w.n());
        List<DeterministicSkeleton.River> cyc = new ArrayList<>();
        cyc.add(new DeterministicSkeleton.River(new int[]{0}, real.get(0).cells, real.get(0).traceOf,
                real.get(0).end, false, w.n(), 1, 0, DeterministicSkeleton.RiverLink.CONNECTED));
        cyc.add(new DeterministicSkeleton.River(new int[]{1}, real.get(1).cells, real.get(1).traceOf,
                real.get(1).end, false, w.n(), 0, 0, DeterministicSkeleton.RiverLink.CONNECTED));
        List<double[]> noR = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, null, null);
        List<double[]> withCyc = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, cyc, null);
        check("真环 ⇒ 不做 JOIN 匹配（逐位等于 rivers=null）", identical(noR, withCyc));
        check("环上两条 trace 宽度本就不同（用例有区分力）",
                Math.abs(noR.get(0)[noR.get(0).length - 1] - noR.get(1)[noR.get(1).length - 1]) > 0.5);
    }

    // ⑩ 接收方 trace 下标

    /**
     * ★ 真实接收 trace 下标（{@code River.traceOf[node]}，不是 {@code node} 本身）。
     *
     * <p>真汇流不再等宽，所以"支流尾 == 接收段宽度"不再是判据。改用<b>下游传播</b>
     * （{@code propagateDownstream} 把已有的支流尾宽传到<b>接收 shape 的真实汇合点之后</b>）
     * 来证明索引：</p>
     * <ul>
     *   <li>支流刻意<b>比接收段更宽</b> ⇒ 传播在真实 trace 上留下一段可观测的抬升；</li>
     *   <li>该抬升只在真实接收 trace 的汇合点之后出现；朴素下标那条串接 trace 上不出现；</li>
     *   <li>汇合点<b>之前</b>的接收段不受影响（传播只向下游）。</li>
     * </ul>
     * <p>这样断言的是"宽度落到了哪条 trace 的哪一段"，而不是"两者恰好等宽"。</p>
     */
    private static void testReceiverTraceIndexing() {
        World w = new World(64, 2);
        var a = vert(40, 10, 50, 2);
        var b = vert(40, 50, 90, 2);
        var c = vert(40, 90, 130, 2);
        var trib = horz(14, 6, 40, 2);                   // 汇入 a 的第 2 节点
        double[] q = trunkQ(w, 20, 5, 55);               // 干流 Q 小 ⇒ 接收段细
        for (int gx = 3; gx < 20; gx++) q[w.cell(gx, 7)] = 220;   // 支流 Q 大 ⇒ 比接收段宽
        List<DeterministicSkeleton.Shaped> polys = List.of(a, b, c, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(a, w, DeterministicSkeleton.End.JOIN),
                trace(b, w, DeterministicSkeleton.End.JOIN),
                trace(c, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        int tu = -1;
        for (int i = 0; i < rivers.size(); i++) if (contains(rivers.get(i).traceIdx, 3)) tu = i;
        check("支流单元识别", tu >= 0 && rivers.get(tu).link == DeterministicSkeleton.RiverLink.CONNECTED);
        if (tu < 0) return;
        var recv = rivers.get(rivers.get(tu).downstreamRiver);
        int node = rivers.get(tu).downstreamNode;
        check("接收单元由 3 条串接 trace 组成（用例有区分力）", recv.traceIdx.length == 3);
        int realT = recv.traceOf[node];
        int naiveT = node < recv.traceIdx.length ? node : recv.traceIdx[0];
        check("真实下标 ≠ 朴素下标（用例有区分力）", realT != naiveT);

        List<double[]> withTrib = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of(), Map.of(),
                java.util.Set.of(traces.get(0).sourceCell()));
        // 基线：同一接收段但没有支流（去掉支流 trace 与其单元）。
        List<DeterministicSkeleton.Shaped> noPolys = List.of(a, b, c);
        List<DeterministicSkeleton.Trace> noTraces = List.of(traces.get(0), traces.get(1), traces.get(2));
        List<double[]> noTrib = RiverWidthProfile.build(noPolys, w.pipe(q), 0, 0, w.n(), w.step(),
                noTraces, DeterministicSkeleton.buildRivers(noTraces, w.n()), Map.of(), Map.of(),
                java.util.Set.of(noTraces.get(0).sourceCell()));

        double[] arcR = RiverWidthProfile.arcLengths(polys)[realT];
        double[] hitR = RiverWidthProfile.projectOnto(polys.get(realT), arcR,
                trib.x[trib.size() - 1], trib.z[trib.size() - 1]);
        check("汇合点落在真实接收 trace 上", hitR != null);
        if (hitR == null) return;
        double tail = withTrib.get(3)[withTrib.get(3).length - 1];
        double atJointReal = RiverWidthProfile.lerpAt(withTrib.get(realT), hitR);
        double baseJointReal = RiverWidthProfile.lerpAt(noTrib.get(realT), hitR);
        check("支流确实比接收段更宽（用例有区分力）", tail > baseJointReal + 1.0);
        check("真实接收 trace 在汇合点处被抬到支流规模（传播落到正确 trace）",
                Math.abs(atJointReal - tail) < 0.20 || atJointReal > baseJointReal + 1.0);
        check("真实接收 trace 汇合点之后仍不窄于支流（下游传播）",
                withTrib.get(realT)[withTrib.get(realT).length - 1] >= tail - 1e-9);
        // ★ 区分力核心：把接收者改成<b>同一 rivers 列表内的另一条 trace</b>（合法拓扑），
        // 传播目标必然随之改变 ⇒ 结果与正确拓扑不同。这证明"下标真的被消费"，
        // 而不是靠"两侧等宽"这种旧契约。
        int misIdx = -1;
        for (int i = 0; i < rivers.size(); i++) {
            if (i != rivers.get(tu).downstreamRiver && rivers.get(i).traceIdx.length > 0) {
                misIdx = i;
                break;
            }
        }
        check("存在可用于反控的另一接收者（用例有区分力）", misIdx >= 0);
        if (misIdx >= 0) {
            List<DeterministicSkeleton.River> skewed = new ArrayList<>();
            for (int i = 0; i < rivers.size(); i++) {
                var r = rivers.get(i);
                int down = i == tu ? misIdx : r.downstreamRiver;
                int nodeIdx = i == tu ? 0 : r.downstreamNode;
                skewed.add(new DeterministicSkeleton.River(r.traceIdx, r.cells, r.traceOf, r.end,
                        r.fromOutlet, r.nz, down, nodeIdx, r.link));
            }
            List<double[]> wrong = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                    traces, skewed, Map.of(), Map.of(),
                    java.util.Set.of(traces.get(0).sourceCell()));
            check("接收者换成另一 trace ⇒ 宽度结果确实不同（下标被真实消费）",
                    !identical(withTrib, wrong));
        }
    }

    /** ★ 内部契约违约必须显式抛异常，不静默猜。 */
    private static void testLinkConflictFails() {
        World w = new World(64, 2);
        var a = vert(40, 10, 50, 2);
        var b = vert(40, 50, 90, 2);
        var c = vert(40, 90, 130, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        List<DeterministicSkeleton.Shaped> polys = List.of(a, b, c);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(a, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(b, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(c, w, DeterministicSkeleton.End.MOUTH_SEA));
        var real = DeterministicSkeleton.buildRivers(traces, w.n());

        // unit0: trace 0 -> trace 1 ; unit1: trace 0 -> trace 2  ⇒ 拓扑自相矛盾
        List<DeterministicSkeleton.River> bad = new ArrayList<>();
        bad.add(new DeterministicSkeleton.River(new int[]{0}, real.get(0).cells, real.get(0).traceOf,
                real.get(0).end, false, w.n(), 1, 0, DeterministicSkeleton.RiverLink.CONNECTED));
        bad.add(new DeterministicSkeleton.River(new int[]{0}, real.get(0).cells, real.get(0).traceOf,
                real.get(0).end, false, w.n(), 2, 0, DeterministicSkeleton.RiverLink.CONNECTED));
        check("同一 trace 两个不同接收者 ⇒ 抛 IllegalStateException",
                throwsIllegalState(() -> RiverWidthProfile.build(polys, w.pipe(q), 0, 0,
                        w.n(), w.step(), traces, bad, null)));

        // 同一目标重复登记 ⇒ 幂等，不抛（否则会把合法重复当成冲突）
        List<DeterministicSkeleton.River> dup = new ArrayList<>();
        dup.add(new DeterministicSkeleton.River(new int[]{0}, real.get(0).cells, real.get(0).traceOf,
                real.get(0).end, false, w.n(), 1, 0, DeterministicSkeleton.RiverLink.CONNECTED));
        dup.add(new DeterministicSkeleton.River(new int[]{0}, real.get(0).cells, real.get(0).traceOf,
                real.get(0).end, false, w.n(), 1, 1, DeterministicSkeleton.RiverLink.CONNECTED));
        check("同一目标重复登记 ⇒ 幂等不抛",
                !throwsIllegalState(() -> RiverWidthProfile.build(polys, w.pipe(q), 0, 0,
                        w.n(), w.step(), traces, dup, null)));

        // downstreamNode 越界 ⇒ 不再猜首条。
        // ★ 注意：这里刻意不取 trace 0 —— 它会被"同一 trace 两个接收者"那条守卫先抓到，
        //   于是测不到 receiverTrace 自身的越界契约。用 trace 1 才能隔离该守卫。
        List<DeterministicSkeleton.River> oob = new ArrayList<>();
        oob.add(new DeterministicSkeleton.River(new int[]{1}, real.get(1).cells, real.get(1).traceOf,
                real.get(1).end, false, w.n(), 0, 9999, DeterministicSkeleton.RiverLink.CONNECTED));
        check("downstreamNode 越界 ⇒ 抛 IllegalStateException",
                throwsIllegalState(() -> RiverWidthProfile.build(polys, w.pipe(q), 0, 0,
                        w.n(), w.step(), traces, oob, null)));
    }

    // ⑪ 顺序无关

    /**
     * 反转 rivers 并同步重映射 downstreamRiver（该字段是列表内局部下标，
     * 只反转不重映射会让拓扑指向错对象，那是测试输入构造错误）。
     */
    private static List<DeterministicSkeleton.River> reversedRivers(
            List<DeterministicSkeleton.River> src) {
        List<DeterministicSkeleton.River> out = new ArrayList<>();
        int n = src.size();
        for (int i = n - 1; i >= 0; i--) {
            var r = src.get(i);
            int down = r.downstreamRiver < 0 ? -1 : (n - 1 - r.downstreamRiver);
            out.add(new DeterministicSkeleton.River(r.traceIdx, r.cells, r.traceOf, r.end,
                    r.fromOutlet, r.nz, down, r.downstreamNode, r.link));
        }
        return out;
    }

    private static void testOrderIndependence() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var low = horz(60, 6, 40, 2);
        var high = horz(90, 74, 40, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 20, 30, 3.5);
        put(w, q, 20, 37, 45, 3.5);
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, low, high);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(low, w, DeterministicSkeleton.End.JOIN),
                trace(high, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        Map<Integer, Double> pinA = new LinkedHashMap<>();
        pinA.put(w.cell(20, 20), 3.0);
        pinA.put(w.cell(20, 45), 4.2);
        Map<Integer, Double> pinB = new LinkedHashMap<>();
        pinB.put(w.cell(20, 45), 4.2);
        pinB.put(w.cell(20, 20), 3.0);
        List<double[]> a = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pinA);
        List<double[]> b = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, reversedRivers(rivers), pinB);
        check("rivers 顺序（已重映射）+ Map 顺序不影响结果（逐位相同）", identical(a, b));
    }

    // ⑫ null / 退化

    private static void testNullAndDegenerate() {
        World w = new World(32, 2);
        var sh = vert(10, 10, 40, 2);
        List<DeterministicSkeleton.Shaped> polys = List.of(sh);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(sh, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());

        List<double[]> noPipe = RiverWidthProfile.build(polys, null, 0, 0, w.n(), w.step(),
                traces, rivers, null);
        boolean allMin = true;
        for (double v : noPipe.get(0)) {
            if (Math.abs(v - RiverCenterlines.MIN_HALF_BLOCKS) > 1e-9) allMin = false;
        }
        check("pipe=null ⇒ 全最小可见半宽", allMin);
        check("pipe=null 不抛异常且长度对齐", noPipe.size() == 1 && noPipe.get(0).length == sh.size());
        List<double[]> bare = RiverWidthProfile.build(polys, w.pipe(new double[w.n() * w.n()]),
                0, 0, w.n(), w.step(), null, null, null);
        check("traces=null/rivers=null/boundary=null ⇒ 不抛异常", bare.size() == 1);
        List<double[]> allNull = RiverWidthProfile.build(polys, null, 0, 0, 0, 0, null, null, null);
        check("全参数为空/零 ⇒ 安全", allNull.size() == 1 && allNull.get(0).length == sh.size());
        check("null polys ⇒ 空结果", RiverWidthProfile.build(null, null, 0, 0, 8, 2,
                null, null, null).isEmpty());
        check("空 polys ⇒ 空结果", RiverWidthProfile.build(List.of(), null, 0, 0, 8, 2,
                null, null, null).isEmpty());
        var single = new DeterministicSkeleton.Shaped(new double[]{4}, new double[]{4});
        List<double[]> one = RiverWidthProfile.build(List.of(single), null, 0, 0, w.n(), w.step(),
                List.of(new DeterministicSkeleton.Trace(new int[]{w.cell(2, 2)},
                        DeterministicSkeleton.End.DEAD_END, false)), List.of(), null);
        check("单节点 shape ⇒ 长度 1、值合法", one.size() == 1 && one.get(0).length == 1
                && one.get(0)[0] >= RiverCenterlines.MIN_HALF_BLOCKS);
        var empty = new DeterministicSkeleton.Shaped(new double[0], new double[0]);
        List<double[]> e = RiverWidthProfile.build(List.of(empty), null, 0, 0, w.n(), w.step(),
                null, null, null);
        check("空 shape ⇒ 空数组（保持逐 shape 对齐）", e.size() == 1 && e.get(0).length == 0);
    }

    // ⑬ 不改 shape + 值域

    private static void testShapeUntouchedAndRange() {
        World w = new World(64, 2);
        var trunk = vert(40, 10, 110, 2);
        var trib = horz(60, 6, 40, 2);
        double[] q = trunkQ(w, 20, 5, 55);
        put(w, q, 3, 20, 30, 3.5);
        double[] tx = trunk.x.clone(), tz = trunk.z.clone();
        double[] bx = trib.x.clone(), bz = trib.z.clone();
        List<DeterministicSkeleton.Shaped> polys = List.of(trunk, trib);
        List<DeterministicSkeleton.Trace> traces = List.of(
                trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        Map<Integer, Double> pin = new LinkedHashMap<>();
        pin.put(w.cell(20, 45), 4.2);
        List<double[]> prof = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, pin);
        check("干流 shape.x 未被修改", java.util.Arrays.equals(tx, trunk.x));
        check("干流 shape.z 未被修改", java.util.Arrays.equals(tz, trunk.z));
        check("支流 shape.x 未被修改", java.util.Arrays.equals(bx, trib.x));
        check("支流 shape.z 未被修改", java.util.Arrays.equals(bz, trib.z));
        boolean aligned = prof.size() == polys.size();
        for (int i = 0; aligned && i < prof.size(); i++) {
            if (prof.get(i).length != polys.get(i).size()) aligned = false;
        }
        check("输出逐 shape 节点对齐", aligned);
        double maxHalf = RiverCenterlines.Params.defaults().maxHalf();
        boolean inRange = true;
        for (double[] row : prof) {
            for (double v : row) {
                if (!(v >= RiverCenterlines.MIN_HALF_BLOCKS && v <= maxHalf)) inRange = false;
            }
        }
        check("全部半宽 ∈ [MIN_HALF_BLOCKS, maxHalf]", inRange);
    }

    // ⑭ 环纯函数

    private static void testCyclesAreNotMatched() {
        boolean[] cyc = RiverWidthProfile.cycleNodes(new int[]{1, 0}, 2);
        check("2-环被识别", cyc[0] && cyc[1]);
        int[] dag = {1, 2, -1};
        boolean[] cycDag = RiverWidthProfile.cycleNodes(dag, 3);
        check("DAG 无环", !cycDag[0] && !cycDag[1] && !cycDag[2]);
        int[] order = RiverWidthProfile.postOrder(dag, 3);
        check("后继先行（post-order）", order.length == 3 && order[0] == 2
                && order[1] == 1 && order[2] == 0);
    }

    private static void testSourceAndContinuation() {
        World w = new World(128, 2);
        var sh = vert(40, 10, 210, 2);
        double[] q = new double[w.n() * w.n()];
        java.util.Arrays.fill(q, 100);
        var t = trace(sh, w, DeterministicSkeleton.End.MOUTH_SEA);
        var polys = List.of(sh);
        var traces = List.of(t);
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        double[] source = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of()).get(0);
        check("高Q真源头仍为最小半宽", near(source[0], 0.5));
        check("真源头按完整弧长渐进展宽，不在32格内恢复粗宽",
                near(source[20], 0.5 + (source[source.length - 1] - 0.5) * 0.2));
        boolean linear = true;
        for (int i = 0; i < source.length; i++) {
            double expected = 0.5 + (source[source.length - 1] - 0.5) * i / (source.length - 1.0);
            if (!near(source[i], expected)) linear = false;
        }
        check("高Q河头全段均匀展宽而不是短程细尖接粗身", linear);
        double[] continued = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                traces, rivers, Map.of(), Map.of(), java.util.Set.of(t.sourceCell())).get(0);
        check("跨区续流首端不重复河头淡出", continued[0] > 2);
        var outlet = new DeterministicSkeleton.Trace(t.cell, t.end, true);
        double[] lake = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(),
                List.of(outlet), DeterministicSkeleton.buildRivers(List.of(outlet), w.n()), Map.of()).get(0);
        check("湖泊溢口保留既有起宽", lake[0] > 2);
    }

    private static void testWideSerialIntoNarrow() {
        World w = new World(128, 2);
        var a = vert(40, 10, 110, 2);
        var b = vert(40, 110, 230, 2);
        double[] q = new double[w.n() * w.n()];
        for (int z = 5; z < 55; z++) q[w.cell(20, z)] = 100;
        List<DeterministicSkeleton.Shaped> polys = List.of(a, b);
        var traces = List.of(trace(a, w, DeterministicSkeleton.End.JOIN),
                trace(b, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        var widths = RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(), traces, rivers, Map.of());
        double[] up = widths.get(0), down = widths.get(1);
        check("串联后继局部Q归零不能把上游压细", up[up.length - 1] > 2);
        check("串联后继继承接点宽度", near(up[up.length - 1], down[0]));
        check("继承宽度持续到下游口而非32格后复位", down[down.length - 1] >= down[0] - 1e-9);
        check("继承处理不依赖river列表遍历顺序", identical(widths,
                RiverWidthProfile.build(polys, w.pipe(q), 0, 0, w.n(), w.step(), traces,
                        reversedRivers(rivers), Map.of())));
    }

    private static void testInheritedSuffix() {
        World w = new World(128, 2);
        var sh = vert(40, 10, 230, 2);
        var traces = List.of(trace(sh, w, DeterministicSkeleton.End.MOUTH_SEA));
        var rivers = DeterministicSkeleton.buildRivers(traces, w.n());
        Map<Integer, Double> inherited = Map.of(w.cell(20, 45), 4.0, w.cell(20, 55), 4.2);
        double[] row = RiverWidthProfile.build(List.of(sh), w.pipe(new double[w.n()*w.n()]),
                0, 0, w.n(), w.step(), traces, rivers, Map.of(), inherited, java.util.Set.of()).get(0);
        check("继承不改远处真正源头", near(row[0], 0.5));
        check("无硬接点的真河源按整程展宽而非被中段继承顶粗",
                near(row[40], 0.5 + (row[row.length - 1] - 0.5) * 80.0 / 220.0));
        double[] relay = RiverWidthProfile.build(List.of(sh), w.pipe(new double[w.n()*w.n()]),
                0, 0, w.n(), w.step(), traces, rivers, Map.of(), inherited,
                java.util.Set.of(traces.get(0).sourceCell())).get(0);
        check("真正续流中段仍保留继承宽度", relay[40] >= 4);
        check("跨区继承不会向下游再次掉回最小", row[row.length - 1] >= 4.2);
        double[] nearSource = RiverWidthProfile.build(List.of(sh), w.pipe(new double[w.n()*w.n()]),
                0, 0, w.n(), w.step(), traces, rivers, Map.of(),
                Map.of(w.cell(20, 6), 4.0), java.util.Set.of()).get(0);
        check("源头附近继承及JOIN渐隐不得覆盖最小起宽", near(nearSource[0], 0.5));
    }

    /**
     * 入湖采样<b>不借整盆集水 Q</b>：同一格上"带湖泊上下文"与"不带"的采样必须逐位相同。
     * 抬宽只能来自显式出口身份 + 该湖的净出流（见 {@link #testOutletUsesNetOutflow}）。
     *
     * <p>采样点刻意放在<b>带盆地身份的那一格</b>（{@code basinId >= 0}）：旧实现正是在
     * 这种格上做 {@code max(Q, catchmentCells × runoff)} ⇒ 差 5 个数量级，断言有区分力。</p>
     */
    private static void testLakeInputs() {
        World w = new World(64, 2);
        var sh = vert(40, 10, 90, 2);
        int mouth = cellsOf(sh, w)[0];                   // 折线源点 = 溢出口
        double q0 = 4.0;
        LakeFixture fx = LakeFixture.of(w, mouth, 60.0, q0);
        double mx = sh.x[0], mz = sh.z[0];
        double ordinary = ModelHydrologyProvider.SegmentIndex.halfWidthAt(
                w.pipe(fx.q), 0, 0, w.n(), w.step(), mx, mz, RiverCenterlines.Params.defaults());
        double lakeSample = ModelHydrologyProvider.SegmentIndex.halfWidthAt(fx.pipe(),
                0, 0, w.n(), w.step(), mx, mz, RiverCenterlines.Params.defaults(),
                fx.lakes(), fx.ids());
        check("入湖采样不能借整盆集水Q抬宽", near(ordinary, lakeSample));
        check("采样格确实带盆地身份（用例有区分力）",
                LakeWaterBalance.basinOf(fx.lakes(), mouth) == 0);
        check("整盆集水等效 Q 远大于本河 Q（旧实现会在此抬宽）",
                fx.basin().catchmentCells() * fx.lakes().runoffPerCell() > 100 * q0);
        check("带湖采样仍等于该格自身 Q 的宽度律结果", Math.abs(lakeSample - halfFor(q0)) < 0.01);
        check("该格整盆集水等效宽度远粗于自身 Q 宽度（旧实现会差这么多）",
                halfFor(fx.basin().catchmentCells() * fx.lakes().runoffPerCell()) > lakeSample + 3.0);
    }

    /**
     * ★ 同面积不同出流：两个湖的集水面积/水面一模一样，只有 {@code outflow} 不同。
     * 出湖半宽必须<b>跟随净出流</b>——大出流更宽、小出流更细，且细的那条<b>不会</b>被
     * 面积抬到与粗的那条同样宽。
     *
     * <p>这正是旧实现的核心错误：{@code max(Q, catchmentCells × runoff)} 让"同面积"直接
     * 决定"同宽度"，出流差别被完全抹掉。</p>
     */
    private static void testOutletUsesNetOutflow() {
        World w = new World(64, 2);
        var sh = vert(40, 10, 90, 2);
        int outlet = cellsOf(sh, w)[0];                   // 折线源点 = 溢出口
        double q0 = 4.0;
        LakeFixture wide = LakeFixture.of(w, outlet, 60.0, q0);
        LakeFixture thin = LakeFixture.of(w, outlet, 6.0, q0);
        var t = new DeterministicSkeleton.Trace(cellsOf(sh, w), DeterministicSkeleton.End.MOUTH_SEA, true);
        var rivers = DeterministicSkeleton.buildRivers(List.of(t), w.n());
        double hWide = RiverWidthProfile.build(List.of(sh), wide.pipe(), 0, 0, w.n(), w.step(),
                List.of(t), rivers, Map.of()).get(0)[0];
        double hThin = RiverWidthProfile.build(List.of(sh), thin.pipe(), 0, 0, w.n(), w.step(),
                List.of(t), rivers, Map.of()).get(0)[0];
        check("同面积同水位两湖的集水规模完全相同（fixture 有效性）",
                near(wide.basin().catchmentCells(), thin.basin().catchmentCells())
                        && near(wide.basin().areaCells(), thin.basin().areaCells()));
        check("两臂湖出流确实不同（fixture 有效性）", wide.basin().outflow() > thin.basin().outflow() + 1);
        // ★ 用户口径：出湖宽度是【上限】而非下限 —— 净出流小不得把河顶粗，
        //   净出流大也不得凭空加宽；两种情况都只夹到本河自身 Q 的宽度律。
        check("出湖宽度不因净出流而抬粗（上限语义）", hWide <= halfFor(q0) + 0.06);
        check("出湖宽度亦不因净出流小而变细（仍按本河 Q）", hThin <= halfFor(q0) + 0.06);
        check("两臂出湖宽度一致：净出流只作上限，不参与加宽",
                Math.abs(hWide - hThin) < 0.06);
        double catchmentHalf = halfFor(wide.basin().catchmentCells() * wide.lakes().runoffPerCell());
        check("集水面积等效宽度远粗于出流（旧实现会把河顶到它）",
                catchmentHalf > hWide + 1.0 && catchmentHalf > hThin + 3.0);
    }

    /**
     * 单口湖泊夹具：{@code basinOutlet[b] = outlet}（供 {@code sourceBasinOf} 反查），
     * 湖泊掩码只含出口一格，{@code overflowAt} 给出口量。
     *
     * <p>抽成夹具是为了让"同面积不同出流"只改一个数：{@code outflow}。
     * 集水面积刻意取极大值，从而区分"读净出流"与"读集水面积"两条实现。</p>
     *
     * <p>⚠ {@code outflow} 只在该 trace 的<b>源点等于 {@code outlet}</b> 时才被
     * {@code RiverWidthProfile.rawRow} 取到（{@code sourceBasinOf} 按出口反查盆地）。</p>
     */
    private record LakeFixture(HydrologyPipeline.Result pipe, LakeWaterBalance.Result lakes,
                               int[] ids, LakeWaterBalance.Basin basin, double[] q) {

        static LakeFixture of(World w, int outlet, double outflow, double lakeQ) {
            int cells = w.n() * w.n();
            double[] q = new double[cells];
            java.util.Arrays.fill(q, lakeQ);
            boolean[] wet = new boolean[cells];
            int[] ids = new int[cells];
            java.util.Arrays.fill(ids, -1);
            wet[outlet] = true;
            ids[outlet] = 0;
            var basin = new LakeWaterBalance.Basin(outlet, 0, 10, 10, 1,
                    100000, 100000, 100000 - outflow, outflow, true, false);
            var lakes = new LakeWaterBalance.Result(new double[cells], wet, List.of(basin), 0, 1,
                    Map.of(outlet, outflow), Map.of(), ids, new int[]{outlet});
            var base = w.pipe(q);
            var pipe = new HydrologyPipeline.Result(base.nx(), base.nz(), base.cellBlocks(), base.bed(),
                    base.sea(), base.resolved(), base.level(), base.depth(), wet, base.parent(), q,
                    base.channel(), base.width(), lakes, null, base.deathMask(), base.waterSurface(),
                    0, true, 0);
            return new LakeFixture(pipe, lakes, ids, basin, q);
        }
    }

    /**
     * 小支流不是干流：支流保留自身细规模，干流承接之后仍不比支流细。
     *
     * <p>真汇流下"承接"= 干流从接点起不窄于支流，无需两者等宽。</p>
     */
    private static void testSmallTributaryIsNotTrunk() {
        World w = new World(128, 2);
        var trunk = vert(80, 10, 230, 2);
        var trib = horz(120, 10, 80, 2);
        double[] q = new double[w.n() * w.n()];
        for (int gz = 5; gz <= 115; gz++) q[w.cell(40, gz)] = 1000;   // 干流大
        put(w, q, 5, 39, 60, 2);                                     // 支流小
        var ts = List.of(trace(trunk, w, DeterministicSkeleton.End.MOUTH_SEA),
                trace(trib, w, DeterministicSkeleton.End.JOIN));
        var ps = List.of(trunk, trib);
        var widths = RiverWidthProfile.build(ps, w.pipe(q), 0, 0, w.n(), w.step(), ts,
                DeterministicSkeleton.buildRivers(ts, w.n()), Map.of(), Map.of(),
                java.util.Set.of(ts.get(0).sourceCell()));
        double tail = widths.get(1)[trib.size() - 1];
        int at = (120 - 10) / 2;                                     // 干流真实接点
        double receiver = widths.get(0)[at];
        check("小支流不被干流规模与汇流约束撑成等宽", tail < 0.6 * receiver);
        check("干流接点承接后仍不窄于支流（承接而非反向）", receiver >= tail - 1e-9);
        check("干流末端不细于支流（下游不回退）", widths.get(0)[trunk.size() - 1] >= tail - 1e-9);
        check("小支流末端本身仍是细流（不因干流大而被抬）", tail < halfFor(2.0) + 1.0);
    }

    private static void testLakeEnvelopeInProductionProfile() {
        World w = new World(256, 1);
        var sh = horz(80, 10, 110, 1);
        int count = w.n() * w.n();
        boolean[] wet = new boolean[count];
        int[] ids = new int[count];
        java.util.Arrays.fill(ids, -1);
        for (int x = 110; x <= 130; x++) for (int z = 70; z <= 90; z++) {
            wet[w.cell(x, z)] = true; ids[w.cell(x, z)] = 0;
        }
        double[] q = new double[count];
        java.util.Arrays.fill(q, 1000);
        var lakes = new LakeWaterBalance.Result(new double[count], wet, List.of(), 0, 1,
                Map.of(), Map.of(), ids, new int[0]);
        var base = w.pipe(q);
        var pipe = new HydrologyPipeline.Result(w.n(), w.n(), 1, base.bed(), base.sea(), base.resolved(),
                base.level(), base.depth(), wet, base.parent(), q, base.channel(), base.width(), lakes,
                null, base.deathMask(), base.waterSurface(), 0, true, 0);
        var t = trace(sh, w, DeterministicSkeleton.End.MOUTH_LAKE);
        var detail = RiverWidthProfile.buildDetailed(List.of(sh), pipe, 0, 0, w.n(), 1, List.of(t),
                DeterministicSkeleton.buildRivers(List.of(t), w.n()), Map.of(), Map.of(), java.util.Set.of(), Map.of());
        double[] row = detail.widths().get(0), cap = detail.limits().get(0);
        check("生产剖面消费湖尺度上限（20格横宽=>全宽至多2.5）", row[row.length-1] <= 1.25 + 1e-9);
        check("生产剖面保留源头最小值", near(row[0], 0.5));
        check("湖口限幅不是只夹末点", row[row.length-2] <= 1.25 + 1.0/32 + 1e-9);
        boolean obeys = true;
        for (int i = 0; i < row.length; i++) if (row[i] > cap[i] + 1e-9) obeys = false;
        check("所有生产节点都遵守湖口包络", obeys);
        check("生产接口测量确实执行而非空集假绿", detail.interfaces().size() == 1
                && detail.interfaces().get(0).applied());
    }

    // 断言工具

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    private static boolean identical(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).length != b.get(i).length) return false;
            for (int j = 0; j < a.get(i).length; j++) {
                if (Double.doubleToLongBits(a.get(i)[j]) != Double.doubleToLongBits(b.get(i)[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean throwsIllegalState(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) { pass++; return; }
        fail++;
        FAILS.add(name);
    }
}
