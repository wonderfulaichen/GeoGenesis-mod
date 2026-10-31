package com.geogenesis.worldgen.terrain.candidate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link LocalBoundaryGeometry} 的校验与导出。不画图、不接整幅、不改任何既有文件。
 *
 * <pre>
 * LocalBoundaryGeometryProbe &lt;outDir&gt; &lt;cx&gt; &lt;cz&gt; &lt;radius&gt; [dumpRadius]
 * </pre>
 *
 * <p>判据失败以 {@code System.exit(1)} 承载（项目铁律：否则门禁名存实亡）。
 * 测试分 12 项，各自一个方法；{@link Report} 累计 fails/warns 与日志。</p>
 *
 * <p>产物：{@code verify.txt}、{@code scene.json}、{@code segments.csv}、{@code regions.csv}、
 * {@code sites.csv}、{@code normals.csv}、{@code dump.csv}、{@code triple.json}、
 * {@code normal-profile.csv/json}。</p>
 */
public final class LocalBoundaryGeometryProbe {

    /** 交付口径：最大段长（块）。 */
    static final double DELIVER_MAXSEG = 2.0;
    /** 交付口径：弦偏差（块）。 */
    static final double DELIVER_CHORD = 0.5;
    /** 换窗口一致性阈值。 */
    static final double WIN_DIST_TOL = 0.05;
    static final double WIN_WEIGHT_TOL = 1e-3;

    private LocalBoundaryGeometryProbe() { }

    /** 累加日志与计数的小容器（避免 main 里散落 int 变量）。 */
    static final class Report {
        final List<String> log = new ArrayList<>();
        int fails, warns;

        void add(String s) { log.add(s); }

        void fail(String s) { log.add("    FAIL: " + s); fails++; }

        void warn(String s) { log.add("    WARN: " + s); warns++; }

        void pass(String s) { log.add("    PASS" + (s == null || s.isEmpty() ? "" : "（" + s + "）")); }

        /** 布尔判据：真则 PASS，假则 FAIL。 */
        void check(boolean ok, String passMsg, String failMsg) {
            if (ok) pass(passMsg);
            else fail(failMsg);
        }
    }

    /** 一次运行的上下文。 */
    record Ctx(LocalBoundaryGeometry geo, Path out, double cx, double cz, double radius,
               double dumpRadius, double[] box) { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 4) {
            throw new IllegalArgumentException("usage: <outDir> <cx> <cz> <radius> [dumpRadius]");
        }
        Path out = Path.of(args[0]);
        double cx = Double.parseDouble(args[1]);
        double cz = Double.parseDouble(args[2]);
        double radius = Double.parseDouble(args[3]);
        double dumpRadius = args.length >= 5
                ? Double.parseDouble(args[4]) : Math.min(140, radius * 0.6);
        Files.createDirectories(out);

        Report r = new Report();
        long t0 = System.nanoTime();
        // 走主代理实际使用的接口：owner 只被只读 partition/slots/slotCats
        Object owner = LocalBoundaryGeometry.galleryOwner();
        LocalBoundaryGeometry geo = LocalBoundaryGeometry.of(owner, cx, cz, radius, 5, DELIVER_CHORD);
        geo.setMaxSeg(DELIVER_MAXSEG);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        Ctx c = new Ctx(geo, out, cx, cz, radius, dumpRadius, geo.warpBox());

        header(r, c, buildMs);
        checkProductionReplica(r, c);
        checkCandidateCompleteness(r, c);
        checkInverseResidual(r, c);
        checkDensifyConvergence(r, c);
        checkEdgeValidity(r, c);
        checkAntisymmetry(r, c);
        checkNormalSweep(r, c);
        checkQueryDomain(r, c);
        checkActiveCount(r, c);
        checkWindowInvariance(r, c);
        List<Triple> triples = checkJunctions(r, c);
        checkNormalProfile(r, c, triples);

        writeArtifacts(r, c, triples);
        r.add("");
        r.add(String.format(Locale.ROOT, "RESULT: %s（fails=%d, warns=%d）",
                r.fails == 0 ? "PASS" : "FAIL", r.fails, r.warns));
        Files.writeString(out.resolve("verify.txt"), String.join("\n", r.log) + "\n",
                StandardCharsets.UTF_8);
        System.out.println(String.join("\n", r.log));
        if (r.fails != 0) System.exit(1);
    }

    // ================================================================ 头部

    private static void header(Report r, Ctx c, long buildMs) {
        LocalBoundaryGeometry g = c.geo();
        r.add("gallerySeed            = " + g.gallerySeed());
        r.add("partitionSeed          = " + g.partitionSeed());
        r.add("slotCount              = " + g.slotCount());
        r.add("slots                  = " + String.join(",", g.slotCategories()));
        r.add(String.format(Locale.ROOT, "center                 = (%.4f, %.4f) blocks",
                g.centerX(), g.centerZ()));
        r.add("centerCell             = " + g.centerCell());
        r.add(String.format(Locale.ROOT, "queryRadius(declared)  = %.1f blocks", g.queryRadius()));
        r.add(String.format(Locale.ROOT, "halo / boxHalfWorld    = %.1f / %.1f blocks",
                LocalBoundaryGeometry.HALO_BLOCKS, g.boxHalfWorld()));
        r.add(String.format(Locale.ROOT, "bandTotal / bandHalf   = %.1f / %.1f blocks",
                LocalBoundaryGeometry.BAND_TOTAL_BLOCKS, LocalBoundaryGeometry.BAND_HALF_BLOCKS));
        r.add("siteCount              = " + g.sites().size()
                + "  (included " + g.includedSiteCount()
                + ", excluded " + g.excludedSiteCount() + ")");
        double[] b = c.box();
        r.add(String.format(Locale.ROOT, "warpPlaneBox           = [%.5f, %.5f] x [%.5f, %.5f] cells",
                b[0], b[1], b[2], b[3]));
        r.add("warpEdgeCount          = " + g.warpEdges().size());
        r.add("worldSegmentCount      = " + g.edges().size());
        r.add(String.format(Locale.ROOT, "chordTol / maxSeg      = %.3f / %.3f blocks",
                g.chordTol(), g.maxSegBlocks()));
        r.add("sceneBuildMs           = " + buildMs);
    }

    // ================================================================ [1]

    /** [1] 赢家/槽位/edge 与生产 {@code RegionPartition.sample} 逐点对拍。 */
    private static void checkProductionReplica(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        int n = 0, badCell = 0, badSlot = 0, badEdge = 0;
        double worstEdge = 0;
        for (int i = 0; i < 211; i++) {
            for (int j = 0; j < 211; j++) {
                double x = c.cx() + (i / 210.0 * 2 - 1) * c.radius();
                double z = c.cz() + (j / 210.0 * 2 - 1) * c.radius();
                RegionPartition.Sample ref = g.referenceSample(x, z);
                LocalBoundaryGeometry.Pick us = g.winnerCell(x, z);
                n++;
                if (g.regionIdOfCell(us.cellX(), us.cellZ()) != ref.regionId()) badSlot++;
                if (RegionPartition.selectIndex(ref.regionId(), g.slotCount())
                        != g.slotOfCell(us.cellX(), us.cellZ())) badSlot++;
                double de = Math.abs((double) g.edgeOfPick(us) - ref.edge());
                worstEdge = Math.max(worstEdge, de);
                if (de > 1e-6) badEdge++;
                LocalBoundaryGeometry.Site w = g.exactWinner(x, z);
                if (w == null || w.cellX != us.cellX() || w.cellZ != us.cellZ()) badCell++;
            }
        }
        r.add("");
        r.add("[1] 赢家/槽位/edge 与 RegionPartition.sample 对拍（211x211 = " + n + " 点）");
        r.add("    grid-cell identity mismatch = " + badCell);
        r.add("    regionId / slot mismatch    = " + badSlot);
        r.add(String.format(Locale.ROOT,
                "    edge mismatch (|d|>1e-6)     = %d  max|d edge| = %.3e", badEdge, worstEdge));
        r.check(badCell == 0 && badSlot == 0 && badEdge == 0,
                null, "候选的格身份/槽位/edge 与旧实现不一致");
    }

    // ================================================================ [2]

    /** [2] 站点候选完整性：闭式枚举界 + 窗口内 fLower>F 剔除。 */
    private static void checkCandidateCompleteness(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        double f = g.maxPotentialInBox();
        double dmax = g.maxSiteRadiusCells();
        double lbMin = g.minExcludedLowerBound();
        double[] b = c.box();
        r.add("");
        r.add("[2] 候选站点完整性（闭式，不是采样断言）");
        r.add(String.format(Locale.ROOT, "    F = fUpper(盒中心格站点) = %.9f（格单位，NATURAL）", f));
        r.add(String.format(Locale.ROOT,
                "    dmax = sqrt(F) = %.9f 格（f >= |delta|^2 ⇒ 可能获胜者轴向位移 <= dmax）", dmax));
        r.add(String.format(Locale.ROOT, "    枚举整数格 = %d，纳入 %d，排除 %d，测试 %d",
                g.enumeratedCells(), g.includedSiteCount(), g.excludedSiteCount(),
                g.exclusionTests()));
        r.add("    排除：1) 枚举窗口外（cell 距 box > dmax + jitter 界）⇒ 永不可能获胜（含无限外部）；");
        r.add("          2) 窗口内但 fLower(site, box) > F ⇒ 盒内每点都输。");
        r.add(String.format(Locale.ROOT, "    窗口内被排除者最小 fLower = %.9f", lbMin));
        if (g.sites().size() < 2) {
            r.fail("场景内站点不足 2 个，无法构成边界");
            return;
        }
        if (Double.isNaN(lbMin)) {
            r.warn("窗口内没有被 fLower 排除的站点（盒很小）；候选集仍完整");
        } else {
            r.check(lbMin > f, null, "窗口内存在被排除站点的 fLower <= F ⇒ 排除不成立");
        }
        // 抽查"枚举窗口之外"的格：必须 fLower > F（否则 dmax/jitter 界有误）。
        // 注意必须真正取到 window 之外（box ± (dmax + 0.7) 之外），
        // 否则抽到的还是窗口内格，那条 fLower>F 并不必然成立。
        int bad = 0, tested = 0;
        double jmax = LocalBoundaryGeometry.JITTER_MAX;
        for (int k = 0; k < 400; k++) {
            double a = Math.PI * 2 * k / 400.0;
            double extra = 0.05 + 2.0 * ((k * 2654435761L % 1000) / 1000.0);  // 越界 0.05..2.05 格
            double reach = dmax + jmax + extra;
            int ax = (int) Math.round(
                    (a < Math.PI ? b[1] : b[0]) + Math.cos(a) * reach);
            int az = (int) Math.round(
                    (a < Math.PI ? b[3] : b[2]) + Math.sin(a) * reach);
            double[] s = g.siteXZ(ax, az);
            double nx = Math.max(0, Math.max(b[0] - s[0], s[0] - b[1]));
            double ny = Math.max(0, Math.max(b[2] - s[1], s[1] - b[3]));
            double lo = nx + ny + nx * nx + ny * ny;
            tested++;
            if (!(lo > f)) bad++;
        }
        r.add(String.format(Locale.ROOT,
                "    枚举窗口外抽查 %d 格（越界 0.05~2.05 格）：fLower > F 的占 %d/%d",
                tested, tested - bad, tested));
        if (bad != 0) r.warn("抽查中有枚举窗口外站点 fLower <= F（dmax/jitter 界需复核）");
    }

    // ================================================================ [3]

    /** [3] 反解残差（超限已由几何层抛异常阻断）。 */
    private static void checkInverseResidual(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        double res = g.maxWarpResidualBlocks();
        double tol = g.invertTolBlocks();
        r.add("");
        r.add("[3] world 反解残差（超 tol 时几何层直接抛异常）");
        r.add(String.format(Locale.ROOT, "    max residual = %.4e blocks, tol = %.4e blocks", res, tol));
        r.add("    ⚠ world 折线是<b>近似</b>：signedDistance 误差量级 = 残差 + 弦偏差，不是精确值");
        r.add("    ⚠ 残差小<b>不能</b>证明原像唯一：若 warp 折叠存在多根，每个根都可残差很小");
        r.check(res <= tol, String.format(Locale.ROOT, "%.1f%% of tol", 100.0 * res / tol),
                "残差超出容限");
    }

    // ================================================================ [4]

    /** [4] world 折线 8→4→2 块（弦偏差 + 最大段长）全 2D 对拍。 */
    private static void checkDensifyConvergence(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        r.add("");
        r.add("[4] world 折线密度 8 -> 4 -> 2 块（弦与段长同时收紧，全 2D 网格对拍）");
        double[] steps = {8.0, 4.0, 2.0};
        List<Map<Integer, double[][]>> snaps = new ArrayList<>();
        for (double st : steps) {
            g.setMaxSeg(st);
            g.setChordTol(st * 0.25);
            snaps.add(snapshot(g));
        }
        g.setMaxSeg(DELIVER_MAXSEG);
        g.setChordTol(DELIVER_CHORD);

        double d84 = gridDelta(snaps.get(0), snaps.get(1), c, 61);
        double d42 = gridDelta(snaps.get(1), snaps.get(2), c, 61);
        double d82 = gridDelta(snaps.get(0), snaps.get(2), c, 61);
        r.add(String.format(Locale.ROOT, "    max |ds| 8-vs-4 = %.4f blocks", d84));
        r.add(String.format(Locale.ROOT, "    max |ds| 4-vs-2 = %.4f blocks", d42));
        r.add(String.format(Locale.ROOT, "    max |ds| 8-vs-2 = %.4f blocks（最粗档真实偏差）", d82));
        r.check(d42 <= 0.25, "4 -> 2 收敛 <= 0.25 块", "4 -> 2 未收敛（> 0.25 块）⇒ 几何欠采样");
        if (d82 > 2.0) {
            r.warn(String.format(Locale.ROOT,
                    "8 块档与 2 块档差 %.3f 块 > 2 ⇒ 8 块档不足以做距离，下游用 <= 2 块档", d82));
        }
    }

    // ================================================================ [5]

    /** [5] 边有效性：每段端点/四分点/中点核验平分线与第三站点遮挡。 */
    private static void checkEdgeValidity(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        int checkedPts = 0, occluded = 0, offBisector = 0;
        for (LocalBoundaryGeometry.WarpEdge we : g.warpEdges()) {
            for (double t : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
                double u = we.ax() + t * (we.bx() - we.ax());
                double v = we.az() + t * (we.bz() - we.az());
                checkedPts++;
                double fa = g.potentialAt(we.a(), u, v);
                double fb = g.potentialAt(we.b(), u, v);
                double scl = 1 + Math.abs(fa);
                if (Math.abs(fa - fb) > 1e-7 * scl) offBisector++;
                Double f3 = thirdSiteMin(g, we, u, v);
                if (f3 != null && f3 < 0.5 * (fa + fb) - 1e-7 * scl) occluded++;
            }
        }
        r.add("");
        r.add("[5] 边有效性（每段端点/四分点/中点逐点核验）");
        r.add("    检验点数 = " + checkedPts + "（边数 " + g.warpEdges().size() + " × 5）");
        r.add("    不在平分线上 = " + offBisector + "（f_a != f_b）");
        r.add("    被第三站点遮挡 = " + occluded + "（存在 c 使 f_c < 中点势）");
        r.add("    符号矩形数 = " + g.rectCount() + "，候选对测试 = " + g.candidatePairs()
                + "，退化对 = " + g.degeneratePairs());
        r.check(offBisector == 0 && occluded == 0 && g.degeneratePairs() == 0,
                null, "存在伪边/遮挡边/退化站点对");
    }

    // ================================================================ [6]

    /** [6] 双腿边界两侧反对称性。 */
    private static void checkAntisymmetry(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        int n = 0;
        double worst = 0;
        for (int k = 0; k < 4000; k++) {
            double a = Math.PI * 2 * k / 4000.0;
            double rr = c.radius() * (0.1 + 0.85 * ((k * 2654435761L % 1000) / 1000.0));
            double x = c.cx() + rr * Math.cos(a), z = c.cz() + rr * Math.sin(a);
            if (Math.hypot(x - c.cx(), z - c.cz()) > c.radius()) continue;
            LocalBoundaryGeometry.Sample s = g.query(x, z);
            int wi = s.winnerIndex();
            int other = bestOther(s, wi);
            if (other < 0) continue;
            double sa = s.signedDistance[wi], sb = s.signedDistance[other];
            if (!Double.isFinite(sa) || !Double.isFinite(sb)) continue;
            if (sa < 60 || sa > 400) continue;
            worst = Math.max(worst, Math.abs(sa + sb));
            n++;
        }
        r.add("");
        r.add("[6] 双腿边界两侧反对称性（s_A + s_B 在边界上应 ~ 0）");
        r.add(String.format(Locale.ROOT, "    样本 n = %d，max|s_A + s_B| = %.4f blocks", n, worst));
        r.check(worst < 1.0, "< 1 block", "两侧距离不满足反对称（> 1 块）");
    }

    // ================================================================ [7]

    /**
     * [7] 普通真实边沿法向 ±120 块权重演变，并<b>报出饱和位置的距离</b>（不只端点）。
     *
     * <p>口径：只取中点落在声明域内的真实边；法向采样点越域则跳过该 offset（不伪造）。
     * 若法向带出现第三格（{@code activeCount>2}），A 侧权重被抢属设计内，另计。</p>
     */
    /** 一条普通边的法向扫描结果。 */
    record SweepResult(boolean mono, boolean third, double satA, double satB, boolean sampled) { }

    /**
     * 对一条边做 ±120 块法向细扫（每 5 块一档），返回单调性/三向/饱和距离，并把抽样行追加到 rows。
     */
    private static SweepResult sweepEdge(LocalBoundaryGeometry g, Ctx c,
                                         LocalBoundaryGeometry.Edge e, List<String> rows) {
        int mid = e.wx.length / 2;
        double mx = e.wx[mid], mz = e.wz[mid];
        double nx = -(e.wz[mid + 1] - e.wz[mid - 1]);
        double nz = (e.wx[mid + 1] - e.wx[mid - 1]);
        double len = Math.hypot(nx, nz);
        if (!(len > 0)) return new SweepResult(true, false, Double.NaN, Double.NaN, false);
        nx /= len;
        nz /= len;

        Integer ia = indexOfCell(g, e.a), ib = indexOfCell(g, e.b);
        if (ia == null || ib == null) return new SweepResult(true, false, Double.NaN, Double.NaN, false);

        Map<Double, double[]> vals = new LinkedHashMap<>();
        int used = 0;
        for (int step = -24; step <= 24; step++) {
            double off = step * 5.0;
            double x = mx + nx * off, z = mz + nz * off;
            if (Math.hypot(x - c.cx(), z - c.cz()) > c.radius()) continue;
            LocalBoundaryGeometry.Sample s = g.query(x, z);
            used++;
            vals.put(off, new double[]{s.weight[ia], s.weight[ib],
                    s.signedDistance[ia], s.signedDistance[ib], sumW(s.weight), s.activeCount});
            if (step % 6 == 0) {
                rows.add(String.format(Locale.ROOT,
                        "%d,%d,%d,%d,%d,%.1f,%.6f,%.6f,%.4f,%.4f,%.6f,%d",
                        e.index, e.a.cellX(), e.a.cellZ(), e.b.cellX(), e.b.cellZ(), off,
                        s.weight[ia], s.weight[ib], s.signedDistance[ia], s.signedDistance[ib],
                        sumW(s.weight), s.activeCount));
            }
        }
        if (used < 3) return new SweepResult(true, false, Double.NaN, Double.NaN, false);

        boolean mono = isMonotonic(vals, 0) && isMonotonic(vals, 1);
        boolean third = false;
        for (double[] v : vals.values()) if ((int) v[5] > 2) third = true;
        return new SweepResult(mono, third, satByDistance(vals, 0), satByDistance(vals, 1), true);
    }

    /**
     * [7] 普通真实边沿法向 ±120 块权重演变，并<b>报出饱和位置的距离</b>（不只端点）。
     *
     * <p>口径：只取中点落在声明域内的真实边；法向采样点越域则跳过该 offset（不伪造）。
     * 若法向带出现第三格（{@code activeCount>2}），A 侧权重被抢属设计内，另计。</p>
     */
    private static void checkNormalSweep(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        r.add("");
        r.add("[7] 普通真实边沿法向 ±120 块的权重演变 + 饱和位置距离");
        List<LocalBoundaryGeometry.Edge> ordinary = new ArrayList<>();
        for (LocalBoundaryGeometry.Edge e : g.edges()) {
            if (e.wx.length < 3) continue;
            int mid = e.wx.length / 2;
            if (Math.hypot(e.wx[mid] - c.cx(), e.wz[mid] - c.cz()) > c.radius()) continue;
            ordinary.add(e);
        }
        r.add("    判定口径：仅取中点落在声明查询域内的真实边；越域 offset 跳过");

        List<String> rows = new ArrayList<>();
        rows.add("segId,cellAX,cellAZ,cellBX,cellBZ,offsetBlocks,weightA,weightB,"
                + "sCellA,sCellB,sumW,activeCount");
        int good = 0, jTrip = 0, bad = 0;
        double worstSatA = 0, worstSatB = 0;
        for (int ei = 0; ei < Math.min(ordinary.size(), 12); ei++) {
            LocalBoundaryGeometry.Edge e = ordinary.get(ei);
            SweepResult sr = sweepEdge(g, c, e, rows);
            if (!sr.sampled()) continue;
            if (!Double.isNaN(sr.satA())) worstSatA = Math.max(worstSatA, sr.satA());
            if (!Double.isNaN(sr.satB())) worstSatB = Math.max(worstSatB, sr.satB());
            if (!sr.mono()) {
                if (sr.third()) jTrip++; else bad++;
            } else {
                good++;
            }
            r.add(String.format(Locale.ROOT,
                    "    seg %d: 权重进入 0.001/0.999 饱和带时的 |s| = %.1f 块（A）/ %.1f 块（B）%s",
                    e.index, sr.satA(), sr.satB(), sr.third() ? "  [三向近邻]" : ""));
        }
        writeLines(c.out().resolve("normals.csv"), rows);
        r.add("    采样普通边 = " + good + "；三向近邻致非单调（设计内）= " + jTrip
                + "；无解释非单调 = " + bad);
        r.add(String.format(Locale.ROOT,
                "    最远饱和距离：A 侧 %.1f 块，B 侧 %.1f 块（带宽 120 块 ⇒ 应在 ~120 附近）",
                worstSatA, worstSatB));
        if (good < 1) {
            r.fail("没有可用的普通真实边");
        } else if (bad != 0) {
            r.fail(bad + " 条边权重沿法向非单调且无第三格可解释");
        } else {
            r.pass("纯双腿边权重单调；三向近邻另计");
        }
        if (worstSatA > 0 && Math.abs(worstSatA - LocalBoundaryGeometry.BAND_HALF_BLOCKS) > 30) {
            r.warn(String.format(Locale.ROOT,
                    "A 侧饱和距离 %.1f 块偏离带宽 120 块超过 30 块", worstSatA));
        }
    }

    /** 某侧权重沿 offset 是否单方向（允许多次同向变化）。 */
    private static boolean isMonotonic(Map<Double, double[]> vals, int idx) {
        Boolean dir = null;
        Double prev = null;
        for (double[] v : vals.values()) {
            double cur = v[idx];
            if (prev != null && Math.abs(cur - prev) > 1e-9) {
                boolean inc = cur > prev;
                if (dir == null) dir = inc;
                else if (dir != inc) return false;
            }
            prev = cur;
        }
        return true;
    }

    /**
     * 该侧权重首次饱和（{@code w >= 0.999} 或 {@code <= 0.001}）时，对应格的有符号距离 {@code |s|}。
     *
     * <p>用 {@code s} 而<b>不是</b> offset：法向是折线差分得到的近似方向，沿它走 120 块
     * 得到的 {@code s} 未必正好 120（实测 119.9999）。{@code s} 才是与带宽直接可比的量。</p>
     *
     * @return {@code |s|}；该侧在采样范围内从未饱和则 {@code NaN}
     */
    private static double satByDistance(Map<Double, double[]> vals, int side) {
        double best = Double.NaN;
        int sIdx = side == 0 ? 2 : 3;
        for (double[] v : vals.values()) {
            double w = v[side];
            if (w >= 0.999 || w <= 0.001) {
                double d = Math.abs(v[sIdx]);
                if (Double.isNaN(best) || d < best) best = d;
            }
        }
        return best;
    }

    // ================================================================ [8]

    /** [8] query 声明域：域外抛异常，域内边缘可查。 */
    private static void checkQueryDomain(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        int threw = 0, silent = 0;
        for (double rr : new double[]{c.radius() * 1.001, c.radius() * 1.5, c.radius() * 4}) {
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * 2 * k / 8;
                try {
                    g.query(c.cx() + rr * Math.cos(a), c.cz() + rr * Math.sin(a));
                    silent++;
                } catch (IllegalArgumentException ex) {
                    threw++;
                }
            }
        }
        r.add("");
        r.add("[8] query 声明域（域外必须抛异常，不许静默返回）");
        r.add("    域外探针 24 个：抛异常 " + threw + "，静默返回 " + silent);
        r.check(silent == 0, null, "域外查询没有抛异常");

        int ok = 0;
        for (int k = 0; k < 32; k++) {
            double a = Math.PI * 2 * k / 32;
            g.query(c.cx() + c.radius() * 0.999 * Math.cos(a),
                    c.cz() + c.radius() * 0.999 * Math.sin(a));
            ok++;
        }
        r.add("    域内边缘 32 点全部可查（" + ok + "）");
    }

    // ================================================================ [9]

    /** [9] activeCount 与 weight>0 的实际计数一致。 */
    private static void checkActiveCount(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        int bad = 0, samples = 0;
        for (int i = 0; i <= 40; i++) {
            for (int j = 0; j <= 40; j++) {
                double x = c.cx() + (i / 40.0 * 2 - 1) * c.radius() * 0.95;
                double z = c.cz() + (j / 40.0 * 2 - 1) * c.radius() * 0.95;
                if (Math.hypot(x - c.cx(), z - c.cz()) > c.radius()) continue;
                LocalBoundaryGeometry.Sample s = g.query(x, z);
                int actual = 0;
                for (double w : s.weight) if (w > 0) actual++;
                samples++;
                if (actual != s.activeCount) bad++;
            }
        }
        r.add("");
        r.add("[9] activeCount = weight > 0 的实际数目（" + samples + " 点）");
        r.add("    不一致点数 = " + bad);
        r.check(bad == 0, null, "activeCount 与 weight>0 计数不一致");
    }

    // ================================================================ [10]

    /**
     * [10] 换窗口一致性：**不只比赢家**，还比两侧共同点的
     * 全部非零权重与 {@code |sd| <= 120} 的距离（超带宽的远边不比，因为盒裁剪会改变它们）。
     *
     * <p>阈值：距离 0.05 块、权重 1e-3（两者都记录实测最大值）。</p>
     */
    private static void checkWindowInvariance(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        r.add("");
        r.add("[10] 换窗口一致性（赢家 + 共同点全部非零权重 + |sd|<=120 的距离）");
        r.add(String.format(Locale.ROOT,
                "    阈值：距离 %.3f 块、权重 %.0e；|sd| > %.1f 块的距离不比较（盒裁剪会改它们）",
                WIN_DIST_TOL, WIN_WEIGHT_TOL, LocalBoundaryGeometry.BAND_HALF_BLOCKS));

        int winBad = 0, cmpWin = 0, cmpWeight = 0, cmpDist = 0;
        double worstW = 0, worstD = 0;
        for (double[] off : new double[][]{{0, 0}, {37, -26}, {-53, 37}, {91, -64}}) {
            double ncx = c.cx() + off[0], ncz = c.cz() + off[1];
            LocalBoundaryGeometry g2 = LocalBoundaryGeometry.of(
                    LocalBoundaryGeometry.galleryOwner(), ncx, ncz, c.radius(), 5, DELIVER_CHORD);
            g2.setMaxSeg(DELIVER_MAXSEG);
            for (int k = 0; k < 90; k++) {
                double a = Math.PI * 2 * k / 90.0;
                double rr = c.radius() * 0.5;
                double x = c.cx() + rr * Math.cos(a), z = c.cz() + rr * Math.sin(a);
                if (Math.hypot(x - ncx, z - ncz) > c.radius()) continue;
                LocalBoundaryGeometry.Sample s1 = g.query(x, z);
                LocalBoundaryGeometry.Sample s2 = g2.query(x, z);
                cmpWin++;
                if (s1.cellX != s2.cellX || s1.cellZ != s2.cellZ) { winBad++; continue; }
                for (int i = 0; i < s1.regions.length; i++) {
                    int j = indexOfId(s2, s1.regions[i]);
                    if (j < 0) continue;
                    double d1 = s1.signedDistance[i], d2 = s2.signedDistance[j];
                    double w1 = s1.weight[i], w2 = s2.weight[j];
                    // 权重：只比双方至少一侧非零的格
                    if (w1 > 0 || w2 > 0) {
                        cmpWeight++;
                        worstW = Math.max(worstW, Math.abs(w1 - w2));
                    }
                    // 距离：只比带宽内的边（远边被盒裁剪，不可比）
                    if (Double.isFinite(d1) && Double.isFinite(d2)
                            && Math.abs(d1) <= LocalBoundaryGeometry.BAND_HALF_BLOCKS
                            && Math.abs(d2) <= LocalBoundaryGeometry.BAND_HALF_BLOCKS) {
                        cmpDist++;
                        worstD = Math.max(worstD, Math.abs(d1 - d2));
                    }
                }
            }
        }
        r.add("    比较点 = " + cmpWin + "（赢家一致 " + (cmpWin - winBad) + "）");
        r.add("    共同权重比较 = " + cmpWeight + " 项，max|dw| = " + String.format(Locale.ROOT, "%.3e", worstW));
        r.add("    带宽内距离比较 = " + cmpDist + " 项，max|ds| = " + String.format(Locale.ROOT, "%.4f 块", worstD));
        boolean ok = winBad == 0 && worstW <= WIN_WEIGHT_TOL && worstD <= WIN_DIST_TOL;
        r.check(ok, null, String.format(Locale.ROOT,
                "换窗口改变了结果（赢家差 %d，max|dw|=%.2e，max|ds|=%.4f）",
                winBad, worstW, worstD));
    }

    // ================================================================ [11]

    /** [11] 三域交汇（warp 平面三边共点）。 */
    private static List<Triple> checkJunctions(Report r, Ctx c) {
        LocalBoundaryGeometry g = c.geo();
        List<Triple> triples = findTriples(g, c.cx(), c.cz(), c.radius());
        r.add("");
        r.add("[11] 三域交汇（warp 平面三边共点 => 真 Voronoi 顶点）");
        if (triples.isEmpty()) {
            r.add("    本场景半径内没有三交汇");
            return triples;
        }
        r.add("    交汇点数 = " + triples.size() + "，三格类别互异者 = " + countDistinct(triples));
        Triple t = triples.get(0);
        r.add(String.format(Locale.ROOT, "    * world = (%.4f, %.4f) blocks", t.wx, t.wz));
        r.add("      三格 = " + t.a + " / " + t.b + " / " + t.c);
        r.add("      槽位 = " + t.slotA + " / " + t.slotB + " / " + t.slotC);
        r.add("      类别 = " + t.catA + " / " + t.catB + " / " + t.catC);
        LocalBoundaryGeometry.Sample ts = g.query(t.wx, t.wz);
        r.add("      交点实测（三格权重应 ~1/3 且与遍历顺序无关）：");
        for (int k = 0; k < ts.regions.length; k++) {
            if (ts.weight[k] <= 0) continue;
            r.add(String.format(Locale.ROOT, "        %s slot=%d %s  s=%+.4f  w=%.6f",
                    ts.regions[k], ts.regionSlots[k], ts.regionCategories[k],
                    ts.signedDistance[k], ts.weight[k]));
        }
        return triples;
    }

    // ================================================================ [12]

    /** [12] 普通真实弯边法向剖面（带宽曲线），并自检只有两格参与。 */
    private static void checkNormalProfile(Report r, Ctx c, List<Triple> triples) {
        LocalBoundaryGeometry g = c.geo();
        r.add("");
        final double half = 160, step = 0.5;
        r.add("[12] 普通真实弯边法向剖面（带宽曲线用；距三交汇 >=200 块，±"
                + (int) half + " 块 / 步 " + step + "）");
        List<double[]> jw = new ArrayList<>();
        for (Triple t : triples) jw.add(new double[]{t.wx, t.wz});
        List<LocalBoundaryGeometry.ProfileCandidate> pcs = g.profileCandidates(jw, 200.0, half);
        r.add("    候选普通边数（未截断、距交汇 >=200、跨度在域内、全程 2 候选）= " + pcs.size());
        if (pcs.isEmpty()) {
            r.warn("本窗口内没有满足条件的普通弯边；需单独构普通边场景，本步跳过");
            return;
        }
        LocalBoundaryGeometry.ProfileCandidate pc = pcs.get(0);
        String csv = g.normalProfileCsv(pc.segId(), pc.alongIndex(), half, step);
        try {
            Files.writeString(c.out().resolve("normal-profile.csv"), csv, StandardCharsets.UTF_8);
            Files.writeString(c.out().resolve("normal-profile.json"),
                    profileJson(pc, half, step), StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.fail("写 normal-profile 失败: " + e);
            return;
        }
        r.add(String.format(Locale.ROOT,
                "    已导出：segId=%d center=(%.3f,%.3f) normal=(%.6f,%.6f)",
                pc.segId(), pc.x(), pc.z(), pc.nx(), pc.nz()));
        r.add("    cellA=" + pc.cellA() + " cellB=" + pc.cellB()
                + String.format(Locale.ROOT, "，距最近交汇 %.3f 块", pc.minJunctionGap()));

        ProfileStat st = analyseProfile(g, csv, pc);
        r.add("    行数 = " + st.rows + "，A/B 之外出现正权重 = " + st.foreign);
        r.add(String.format(Locale.ROOT, "    max|wA + wB - 1| = %.3e（双腿互补）", st.worstSum));
        r.add("    wA 方向翻转次数 = " + st.flips + "（双腿应 <= 1）");
        r.add(String.format(Locale.ROOT,
                "    t 范围 [%.1f, %.1f]，wA 端点 %.4f -> %.4f", st.t0, st.t1, st.w0, st.w1));
        r.add(String.format(Locale.ROOT,
                "    完全饱和位置（距中心最近的样点）：wA=1 在 |t| = %.2f 块；wA=0 在 |t| = %.2f 块",
                st.satHigh, st.satLow));
        if (Math.abs(st.satHigh - 120) > 0.51 || Math.abs(st.satLow - 120) > 0.51) r.fail("法向完全饱和位置偏离120块");
        else if (st.foreign != 0) r.fail("剖面里有第三格获得正权重 ⇒ 该处不是双腿边界");
        else if (!(st.worstSum < 1e-9)) r.fail("权重不互补 ⇒ 有第三格参与");
        else if (st.flips > 1) r.fail("wA 沿法向多次翻转 ⇒ 不满足单条双腿边界语义");
        else r.pass("全线仅 A/B 两格、权重互补、单次单调过渡");
    }

    /** 剖面统计结果。 */
    record ProfileStat(int rows, int foreign, double worstSum, int flips,
                       double t0, double t1, double w0, double w1,
                       double satHigh, double satLow) { }

    /** 解析 normal-profile.csv 做自检（同时报饱和位置）。 */
    private static ProfileStat analyseProfile(LocalBoundaryGeometry g, String csv,
                                              LocalBoundaryGeometry.ProfileCandidate pc) {
        String[] lines = csv.split("\n");
        String[] head = lines[0].split(",");
        int foreign = countForeign(lines, head, pc.cellA().id(), pc.cellB().id());
        int nSites = g.sites().size();
        int base = 3 + 2 * nSites;

        List<Double> ts = new ArrayList<>(), ws = new ArrayList<>();
        double worstSum = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split(",");
            double wA = Double.parseDouble(f[base]);
            double wB = Double.parseDouble(f[base + 1]);
            worstSum = Math.max(worstSum, Math.abs(wA + wB - 1.0));
            ts.add(Double.parseDouble(f[2]));
            ws.add(wA);
        }
        int flips = 0, lastSign = 0;
        for (int i = 1; i < ws.size(); i++) {
            double d = ws.get(i) - ws.get(i - 1);
            int sgn = d > 1e-12 ? 1 : (d < -1e-12 ? -1 : 0);
            if (sgn != 0) {
                if (lastSign != 0 && sgn != lastSign) flips++;
                lastSign = sgn;
            }
        }
        double satHigh = Double.POSITIVE_INFINITY, satLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ws.size(); i++) {
            if (ws.get(i) == 1.0) satHigh = Math.min(satHigh, Math.abs(ts.get(i)));
            if (ws.get(i) == 0.0) satLow = Math.min(satLow, Math.abs(ts.get(i)));
        }
        return new ProfileStat(ts.size(), foreign, worstSum, flips,
                ts.isEmpty() ? Double.NaN : ts.get(0),
                ts.isEmpty() ? Double.NaN : ts.get(ts.size() - 1),
                ws.isEmpty() ? Double.NaN : ws.get(0),
                ws.isEmpty() ? Double.NaN : ws.get(ws.size() - 1),
                satHigh, satLow);
    }

    private static String profileJson(LocalBoundaryGeometry.ProfileCandidate pc,
                                      double half, double step) {
        return "{\n"
                + String.format(Locale.ROOT, "  \"segId\": %d,\n", pc.segId())
                + String.format(Locale.ROOT, "  \"alongIndex\": %d,\n", pc.alongIndex())
                + String.format(Locale.ROOT, "  \"centerBlocks\": [%.6f, %.6f],\n", pc.x(), pc.z())
                + String.format(Locale.ROOT, "  \"normal\": [%.9f, %.9f],\n", pc.nx(), pc.nz())
                + String.format(Locale.ROOT, "  \"cellA\": [%d, %d],\n",
                pc.cellA().cellX(), pc.cellA().cellZ())
                + String.format(Locale.ROOT, "  \"cellB\": [%d, %d],\n",
                pc.cellB().cellX(), pc.cellB().cellZ())
                + String.format(Locale.ROOT, "  \"minJunctionGapBlocks\": %.3f,\n", pc.minJunctionGap())
                + String.format(Locale.ROOT, "  \"worldLengthBlocks\": %.3f,\n", pc.worldLength())
                + String.format(Locale.ROOT, "  \"halfSpanBlocks\": %.1f,\n", half)
                + String.format(Locale.ROOT, "  \"stepBlocks\": %.3f,\n", step)
                + "  \"columns\": \"x,z,distanceAlongNormal,s_*,w_*,weightA,weightB,activeCount\"\n"
                + "}\n";
    }

    // ================================================================ 产物

    private static void writeArtifacts(Report r, Ctx c, List<Triple> triples) {
        LocalBoundaryGeometry g = c.geo();
        try {
            g.writeSegmentsCsv(c.out().resolve("segments.csv"));
            g.writeRegionsCsv(c.out().resolve("regions.csv"));
            Files.writeString(c.out().resolve("scene.json"), g.sceneJson(), StandardCharsets.UTF_8);
            writeSitesCsv(g, c.out().resolve("sites.csv"));
            Files.writeString(c.out().resolve("triple.json"), tripleJson(triples),
                    StandardCharsets.UTF_8);
            writeDump(r, g, c.out().resolve("dump.csv"), c.cx(), c.cz(), c.dumpRadius());
        } catch (Exception e) {
            r.fail("写产物失败: " + e);
        }
    }

    private static String tripleJson(List<Triple> triples) {
        if (triples.isEmpty()) return "[]\n";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < triples.size(); i++) {
            sb.append(triples.get(i).json());
            if (i + 1 < triples.size()) sb.append(",\n");
        }
        return sb.append("\n]\n").toString();
    }

    private static void writeSitesCsv(LocalBoundaryGeometry g, Path p) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("index,cellX,cellZ,siteU,siteV,regionId,slot,category,worldEdgeCount");
        for (LocalBoundaryGeometry.Site s : g.sites()) {
            lines.add(String.format(Locale.ROOT, "%d,%d,%d,%.6f,%.6f,%.9f,%d,%s,%d",
                    s.index(), s.cellX(), s.cellZ(), s.vx(), s.vz(),
                    g.regionIdOfCell(s.cellX(), s.cellZ()),
                    g.slotOfCell(s.cellX(), s.cellZ()),
                    g.categoryOfCell(s.cellX(), s.cellZ()),
                    g.edgeCountOf(s)));
        }
        Files.writeString(p, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeDump(Report r, LocalBoundaryGeometry g, Path p,
                                  double cx, double cz, double radius) throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder head = new StringBuilder(
                "x,z,winnerCellX,winnerCellZ,winnerSlot,winnerCategory,activeCount");
        for (LocalBoundaryGeometry.Site s : g.sites()) {
            head.append(",s_").append(s.id()).append(",w_").append(s.id());
        }
        lines.add(head.toString());
        int n = g.sites().size();
        double lim = Math.min(radius, g.queryRadius());
        for (double z = cz - radius; z <= cz + radius; z += 4) {
            for (double x = cx - radius; x <= cx + radius; x += 4) {
                if (Math.hypot(x - cx, z - cz) > lim) continue;
                LocalBoundaryGeometry.Sample s = g.query(x, z);
                StringBuilder sb = new StringBuilder();
                sb.append(fmt(x)).append(',').append(fmt(z)).append(',')
                        .append(s.cellX).append(',').append(s.cellZ).append(',')
                        .append(s.slot).append(',').append(s.category).append(',')
                        .append(s.activeCount);
                for (int i = 0; i < n; i++) {
                    sb.append(',').append(fmt(s.signedDistance[i])).append(',')
                            .append(fmt(s.weight[i]));
                }
                lines.add(sb.toString());
            }
        }
        Files.writeString(p, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        r.add("");
        r.add("dump: radius=" + radius + " blocks -> dump.csv（赢家 + s_i + w_i，非整幅距离图）");
    }

    // ================================================================ 通用工具

    private static void writeLines(Path p, List<String> lines) {
        try {
            Files.writeString(p, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + p, e);
        }
    }

    private static Integer indexOfCell(LocalBoundaryGeometry g, LocalBoundaryGeometry.Cell c) {
        for (LocalBoundaryGeometry.Site s : g.sites()) {
            if (s.cellX() == c.cellX() && s.cellZ() == c.cellZ()) return s.index();
        }
        return null;
    }

    private static int indexOfId(LocalBoundaryGeometry.Sample s, String id) {
        for (int i = 0; i < s.regions.length; i++) if (s.regions[i].equals(id)) return i;
        return -1;
    }

    private static int bestOther(LocalBoundaryGeometry.Sample s, int win) {
        int best = -1;
        double bd = Double.POSITIVE_INFINITY;
        for (int i = 0; i < s.signedDistance.length; i++) {
            if (i == win) continue;
            double d = Math.abs(s.signedDistance[i]);
            if (Double.isFinite(d) && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static double sumW(double[] w) {
        double s = 0;
        for (double v : w) s += v;
        return s;
    }

    /** 除该边两端外的第三方站点对该点的最小势值；无则 null。 */
    private static Double thirdSiteMin(LocalBoundaryGeometry g,
                                       LocalBoundaryGeometry.WarpEdge we, double u, double v) {
        Double best = null;
        for (LocalBoundaryGeometry.Site s : g.sites()) {
            if (s.cellX() == we.a().cellX() && s.cellZ() == we.a().cellZ()) continue;
            if (s.cellX() == we.b().cellX() && s.cellZ() == we.b().cellZ()) continue;
            double f = g.potentialAt(s, u, v);
            if (best == null || f < best) best = f;
        }
        return best;
    }

    /** 剖面里除 A/B 外获得正权重的列数。 */
    private static int countForeign(String[] lines, String[] head, String aId, String bId) {
        int foreign = 0;
        for (int c = 0; c < head.length; c++) {
            if (!head[c].startsWith("w_")) continue;
            String id = head[c].substring(2);
            if (id.equals(aId) || id.equals(bId)) continue;
            for (int r = 1; r < lines.length; r++) {
                if (lines[r].isEmpty()) continue;
                String[] f = lines[r].split(",");
                if (c >= f.length) continue;
                if (Double.parseDouble(f[c]) > 0) { foreign++; break; }
            }
        }
        return foreign;
    }

    private static Map<Integer, double[][]> snapshot(LocalBoundaryGeometry g) {
        Map<Integer, double[][]> m = new LinkedHashMap<>();
        for (LocalBoundaryGeometry.Edge e : g.edges()) {
            double[][] d = new double[e.wx.length][2];
            for (int i = 0; i < e.wx.length; i++) { d[i][0] = e.wx[i]; d[i][1] = e.wz[i]; }
            m.put(e.index, d);
        }
        return m;
    }

    /** 全 2D 网格上两套折线"每边最近距离"的最大差（只比两套都有的边）。 */
    private static double gridDelta(Map<Integer, double[][]> a, Map<Integer, double[][]> b,
                                    Ctx c, int n2d) {
        double worst = 0;
        for (Map.Entry<Integer, double[][]> en : a.entrySet()) {
            double[][] pb = b.get(en.getKey());
            if (pb == null) continue;
            for (int i = 0; i < n2d; i++) {
                double x = c.cx() + (i / (double) (n2d - 1) * 2 - 1) * c.radius();
                for (int j = 0; j < n2d; j++) {
                    double z = c.cz() + (j / (double) (n2d - 1) * 2 - 1) * c.radius();
                    if (Math.hypot(x - c.cx(), z - c.cz()) > c.radius()) continue;
                    worst = Math.max(worst, Math.abs(polylineDistance(en.getValue(), x, z)
                            - polylineDistance(pb, x, z)));
                }
            }
        }
        return worst;
    }

    private static double polylineDistance(double[][] pts, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < pts.length; i++) {
            best = Math.min(best, LocalBoundaryGeometry.segmentDistance(
                    x, z, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]));
        }
        return best;
    }

    // ================================================================ 三交汇

    record Triple(double px, double pz, double wx, double wz,
                  LocalBoundaryGeometry.Cell a, LocalBoundaryGeometry.Cell b,
                  LocalBoundaryGeometry.Cell c, int slotA, int slotB, int slotC,
                  int degA, int degB, int degC, String catA, String catB, String catC) {

        boolean distinct() { return !catA.equals(catB) && !catB.equals(catC) && !catA.equals(catC); }

        int degSum() { return degA + degB + degC; }

        String key() { return String.format(Locale.ROOT, "%.7f,%.7f", px, pz); }

        String json() {
            return "  {\n"
                    + "    \"warpPlaneCells\": [" + fmt(px) + ", " + fmt(pz) + "],\n"
                    + "    \"worldBlocks\": [" + fmt(wx) + ", " + fmt(wz) + "],\n"
                    + "    \"cells\": [[" + a.cellX() + ", " + a.cellZ() + "], ["
                    + b.cellX() + ", " + b.cellZ() + "], [" + c.cellX() + ", " + c.cellZ() + "]],\n"
                    + "    \"slots\": [" + slotA + ", " + slotB + ", " + slotC + "],\n"
                    + "    \"categories\": [\"" + catA + "\", \"" + catB + "\", \"" + catC + "\"],\n"
                    + "    \"segmentCounts\": [" + degA + ", " + degB + ", " + degC + "]\n"
                    + "  }";
        }
    }

    private static List<Triple> findTriples(LocalBoundaryGeometry g, double cx, double cz,
                                            double maxR) {
        List<LocalBoundaryGeometry.WarpEdge> we = g.warpEdges();
        double tol = 1e-7;
        Map<String, Triple> found = new LinkedHashMap<>();
        for (int i = 0; i < we.size(); i++) {
            for (int j = i + 1; j < we.size(); j++) {
                LocalBoundaryGeometry.WarpEdge p = we.get(i), q = we.get(j);
                LocalBoundaryGeometry.Cell shared = null, aOnly = null, bOnly = null;
                for (LocalBoundaryGeometry.Cell cc : List.of(p.a(), p.b())) {
                    if (q.a().cellX() == cc.cellX() && q.a().cellZ() == cc.cellZ()
                            || q.b().cellX() == cc.cellX() && q.b().cellZ() == cc.cellZ()) {
                        shared = cc;
                    } else {
                        aOnly = cc;
                    }
                }
                for (LocalBoundaryGeometry.Cell cc : List.of(q.a(), q.b())) {
                    if (shared != null && shared.cellX() == cc.cellX() && shared.cellZ() == cc.cellZ()) {
                        continue;
                    }
                    bOnly = cc;
                }
                if (shared == null || aOnly == null || bOnly == null) continue;
                for (double[] ep : new double[][]{{p.ax(), p.az()}, {p.bx(), p.bz()}}) {
                    for (double[] eq : new double[][]{{q.ax(), q.az()}, {q.bx(), q.bz()}}) {
                        if (Math.hypot(ep[0] - eq[0], ep[1] - eq[1]) > tol) continue;
                        LocalBoundaryGeometry.Cell[] tri = {shared, aOnly, bOnly};
                        double f0 = g.potentialAt(tri[0], ep[0], ep[1]);
                        double f1 = g.potentialAt(tri[1], ep[0], ep[1]);
                        double f2 = g.potentialAt(tri[2], ep[0], ep[1]);
                        double tolF = 1e-7 * (1 + Math.abs(f0));
                        if (Math.abs(f0 - f1) > tolF || Math.abs(f0 - f2) > tolF) continue;
                        double[] wd = g.warpInverse(ep[0], ep[1], cx, cz);
                        if (Math.hypot(wd[0] - cx, wd[1] - cz) > maxR) continue;
                        Triple t = new Triple(ep[0], ep[1], wd[0], wd[1], tri[0], tri[1], tri[2],
                                g.slotOfCell(tri[0].cellX(), tri[0].cellZ()),
                                g.slotOfCell(tri[1].cellX(), tri[1].cellZ()),
                                g.slotOfCell(tri[2].cellX(), tri[2].cellZ()),
                                g.edgeCountOf(tri[0]), g.edgeCountOf(tri[1]), g.edgeCountOf(tri[2]),
                                g.categoryOfCell(tri[0].cellX(), tri[0].cellZ()),
                                g.categoryOfCell(tri[1].cellX(), tri[1].cellZ()),
                                g.categoryOfCell(tri[2].cellX(), tri[2].cellZ()));
                        found.putIfAbsent(t.key(), t);
                    }
                }
            }
        }
        List<Triple> list = new ArrayList<>(found.values());
        list.sort((a, b) -> {
            int cmp = Boolean.compare(b.distinct(), a.distinct());
            if (cmp != 0) return cmp;
            return Integer.compare(b.degSum(), a.degSum());
        });
        return list;
    }

    private static int countDistinct(List<Triple> l) {
        int n = 0;
        for (Triple t : l) if (t.distinct()) n++;
        return n;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
