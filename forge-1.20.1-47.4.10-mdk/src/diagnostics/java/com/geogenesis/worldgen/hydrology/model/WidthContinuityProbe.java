package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 生产 provider 同一次求解的 shape + SegmentIndex 段半宽曲线（只读，无图像）。
 *
 * <p>用法 {@code outdir [minRegion maxRegion]}，默认 {@code build/river-local-contract/width-continuity 0 0}。
 * 段半宽只认 seg 四坐标 raw-bits 逐位相等；boundary 用真实 pass1 端口 + 本区 final 逐位消费证明。
 * 这是段宽曲线，不是游戏湿列/水深/流量，也不给全周期 PASS。</p>
 *
 * <h2>JOIN 判据：串联 ≠ 支流（2026-10-26 修订）</h2>
 * <p>旧版把「上游尾半宽 − 接收投影半宽」的绝对值一律当误差（要求全为 0），
 * 那只能验证「上游宽度压向下游」这条旧规则，<b>无法</b>证明比例合理，
 * 而且与「真支流保留自身来水规模、不被钉成干流宽」直接冲突。现按生产的边分类分别判定：</p>
 * <ul>
 *   <li>{@link RiverWidthProfile#serialEdge}（同一单元内串联，或接收头只有一个来流）⇒
 *       {@code SERIAL}，仍要求 {@code gap == 0}（{@link #SERIAL_GAP_TOL}）。这是防重置契约，
 *       故意收紧，不参与「放宽误差凑绿」。</li>
 *   <li>其余 {@code CONNECTED} 边 ⇒ {@code CONFLUENCE}（真汇流）：
 *       <b>不要求等宽</b>，只要求下游承接既有来水规模 {@code receiverHalf >= upHalf}
 *       （{@link #CONFLUENCE_GAP_TOL}）。上游比下游粗即违约。</li>
 *   <li>{@code LIMIT_EXCEPTION}：生产 {@code widthLimits}（{@code < MAX_HALF}）把接收方夹细
 *       ⇒ 单独列出、不计违约，但计入 {@code limitedShortening} 使 {@link #allGreen()} 为假。
 *       <b>不得</b>用它声称总体全绿；待主线程把上限在接点两侧统一后再复测。</li>
 *   <li>{@code headRampExplains}：<b>纯诊断字段，不豁免</b>。河头线性段不是"下游可以承接不足"的理由，
 *       而且该判定只是推断会漏报 ⇒ 违约照算。</li>
 * </ul>
 *
 * <h2>其它硬契约（本轮补齐）</h2>
 * <ul>
 *   <li><b>跨区端口</b>：已消费且收发候选唯一的端口，{@code |gap| ≤ }{@link #PORT_GAP_TOL}
 *       （= 1e-9，<b>不用 1 格容差</b>）才算通过。未消费 / 被拒收 / NOT_UNIQUE 只计数，不判失败。</li>
 *   <li><b>节点上限</b>：{@code half > widthLimits + 1e-9} ⇒ 违约（cap 没落到落块宽度上）。</li>
 *   <li><b>湖接口上限</b>：施加了限制的接口，该节点 {@code half > maxHalf + 1e-9} ⇒ 违约。</li>
 * </ul>
 * <p>落块层 <b>bank jitter cap</b> 不在本探针口径内（由 {@code WidthCoverageProbe} 另测）。</p>
 *
 * <h2>退出码（采集成功 ≠ 验收）</h2>
 * <ul>
 *   <li>{@code 0} —— 采集完成且契约通过；<b>仍不是全周期验收</b>，且要求 {@code allGreen=true}；</li>
 *   <li>{@code 1} —— 契约违约（{@code contractFailures > 0}）；</li>
 *   <li>{@code 2} —— 采集失败（{@code collectionFailures > 0}：证据长度不一致、CONNECTED 却无实测值等）；</li>
 *   <li>{@code 3} —— FATAL：反射/拓扑/配置抛异常（段索引不可读、{@code link()} 冲突、TOML 缺失）。</li>
 * </ul>
 * <p>★ 退出码 0 也<b>不</b>蕴含全绿：{@code LIMIT_EXCEPTION > 0} 时码仍为 0，但 {@code allGreen=false}。
 * 结论必须同时读 {@code allGreen} 与 {@code limitedShortening}。</p>
 */
public final class WidthContinuityProbe {

    static final long DEF_SEED = 9139912035078620160L;
    static final Path DEF_OUT = Path.of("build", "river-local-contract", "width-continuity");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    static final double EPS = 1e-12;
    /** 每块跳变的弧长门槛(block)；更短的步只计数不入统计（溢口前缀会插 ~1e-3 块的重合点）。 */
    static final double ARC_EPS = 0.25;
    /** 串联接力仍要求等宽：只容忍浮点噪声，不放宽。 */
    static final double SERIAL_GAP_TOL = 1e-9;
    /**
     * 真汇流的数值噪声下限（半宽 block）—— <b>不是</b>业务容差。
     *
     * <p>取值理由：剖面对每个节点做了 {@code WIDTH_SMOOTH} 滑动平均并叠加
     * {@code LOCAL_DEVIATION} 的局部偏离 ⇒ 上下游半宽在数学上不再逐位相等，
     * 实测残差量级 {@code 1e-5}。该值 = 1e-3 半宽 = <b>0.002 格全宽</b>，
     * 比任何像素都小 3 个数量级以上，故只用来滤掉数值噪声。</p>
     *
     * <p><b>不是"放宽误差凑绿"</b>：超出的部分一律按 {@code VIOLATION} 计违约；
     * 且全部 {@code shortfall} 仍进入 {@code confluenceShortfallStats} 分布，
     * 即使被判 OK 的负 gap 也照原值统计，没有被隐藏。</p>
     */
    static final double CONFLUENCE_GAP_TOL = 1e-3;
    /** 判断某节点半宽是否被生产上限夹住（{@code half >= limit − tol}）。 */
    static final double LIMIT_TOL = 1e-9;
    /** 无约束上限（生产 {@code widthLimits} 的初值、{@code LakeWidthEnvelope.MAX_HALF}）。 */
    static final double MAX_HALF = LakeWidthEnvelope.MAX_HALF;
    /**
     * 跨区端口等宽容差：<b>只容忍浮点噪声，绝不使用 1 格容差</b>。
     *
     * <p>理由：跨区接力是同一条河被两个 region 各解一遍后必须逐位衔接（HYDROLOGY §〇bi「pass1
     * 端口代表值」契约）。1 格容差会让"半条河宽"的错位（全宽 2 格）静默通过，
     * 那正是历史上跨区属性重置的形态。</p>
     */
    static final double PORT_GAP_TOL = 1e-9;
    /** 节点半宽不得超过生产 {@code widthLimits} 的容差（同 MAX_HALF 量级噪声）。 */
    static final double LIMIT_VIOLATION_TOL = 1e-9;
    /** 河头线性展宽窗口（block），与 {@code RiverWidthProfile.linearSource} 的 {@code TRANSITION_BLOCKS} 同源。 */
    static final double HEAD_RAMP_BLOCKS = RiverWidthProfile.TRANSITION_BLOCKS;

    static final Map<String, View> VIEWS = new LinkedHashMap<>();
    static final List<String> NOTES = new ArrayList<>();
    static final TreeSet<String> NEW_SOLVES = new TreeSet<>();
    static final Stats GLOBAL = new Stats();
    static final DoubleBuf JOIN_GAPS = new DoubleBuf();
    static final DoubleBuf SERIAL_GAPS = new DoubleBuf();
    static final DoubleBuf CONFLUENCE_SHORTFALLS = new DoubleBuf();
    static final DoubleBuf PORT_GAPS = new DoubleBuf();
    /** 0 join 1 joinGap 2 port 3 consumed 4 unique 5 notUnique 6 reject 7 offGrid。 */
    static final long[] CNT = new long[8];
    /** 0 serial 1 serialGapViolations 2 confluence 3 confluenceOk 4 confluenceLimitException
     *  5 headRampExplainedViolations（诊断子集，不豁免） 6 confluenceViolations 7 notCollected。 */
    static final long[] JOIN_CNT = new long[8];
    /** 0 checked 1 gapViolations 2 notCheckedUnique（消费但不可唯一配对）。 */
    static final long[] PORT_CNT = new long[3];
    /** 0 检查节点数 1 width>limit 违约节点数 2 limit<MAX_HALF 的节点数。 */
    static final long[] LIMIT_CHK = new long[3];
    /** 0 检查接口数 1 half>maxHalf 违约接口数。 */
    static final long[] IFACE_CHK = new long[2];
    static final DoubleBuf IFACE_HALF_OVER = new DoubleBuf();
    static final DoubleBuf LIMIT_OVER = new DoubleBuf();
    static final List<String> LIMIT_VIOLATION_SAMPLES = new ArrayList<>();
    /** 湖接口证据汇总（status → 次数）。 */
    static final Map<String, Integer> IFACE_STATUS = new java.util.TreeMap<>();
    static int IFACE_TOTAL, IFACE_APPLIED;
    static final DoubleBuf IFACE_TRANSVERSE = new DoubleBuf();
    static final DoubleBuf IFACE_MAX_HALF = new DoubleBuf();
    static long LIMIT_NODES, LIMIT_CAPPED_NODES;
    static final DoubleBuf LIMIT_CAPPED_EXCESS = new DoubleBuf();
    /** 契约违约（⇒ 退出码 1）。 */
    static int contractFailures;
    static final List<String> CONTRACT_VIOLATIONS = new ArrayList<>();
    /** 已知设计后果造成的变窄例外（如实列出，不计违约）。 */
    static final List<String> LIMIT_EXCEPTIONS = new ArrayList<>();

    static Path out;
    static String cfgSha = "";
    static long seed;
    static int REGION, STEP, minR, maxR, fail, probed;
    static CellGenerator gen;
    static ModelHydrologyProvider prov;

    private WidthContinuityProbe() { }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        out = Path.of(args.length > 0 ? args[0].trim()
                : System.getProperty("geogenesis.widthContinuity.out", DEF_OUT.toString()));
        minR = args.length > 1 ? Integer.parseInt(args[1].trim()) : 0;
        maxR = args.length > 2 ? Integer.parseInt(args[2].trim()) : 0;
        if (minR > maxR) { int t = minR; minR = maxR; maxR = t; }
        Files.createDirectories(out);
        seed = Long.getLong("geogenesis.widthSeed", DEF_SEED);
        TerrainParams tp = loadConfig();
        gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        prov = new ModelHydrologyProvider(gen, seed);
        REGION = staticInt(ModelHydrologyProvider.class, "REGION");
        STEP = staticInt(ModelHydrologyProvider.class, "STEP");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta(tp));
        List<Map<String, Object>> regions = new ArrayList<>();
        try (BufferedWriter shapeCsv = open("shape.csv", "rx,rz,poly,node,x,z");
             BufferedWriter widthCsv = open("widths.csv",
                     "rx,rz,poly,node,x,z,half,arc,end,fromOutlet,limit,succTrace")) {
            boolean reverse = Boolean.getBoolean("geogenesis.widthReverseOrder");
            for (int ix = minR; ix <= maxR; ix++) {
                for (int iz = minR; iz <= maxR; iz++) {
                    int rx = reverse ? maxR - (ix - minR) : ix;
                    int rz = reverse ? maxR - (iz - minR) : iz;
                    regions.add(collectRegion(rx, rz, shapeCsv, widthCsv));
                }
            }
        }
        root.put("regions", regions);
        root.put("global", globalSummary(regions.size()));
        root.put("newlySolvedRegions", new ArrayList<>(NEW_SOLVES));
        root.put("notes", new ArrayList<>(NOTES));
        return emit(root);
    }

    // ---------------- 配置 / 元信息 ----------------

    static TerrainParams loadConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.isRegularFile(src)) throw new IllegalStateException("Missing production config: " + src);
        try {
            cfgSha = sha256(src);
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return GeoGenesisConfig.INSTANCE.buildParams();
        } catch (Exception e) {
            throw new IllegalStateException("Production config could not be loaded", e);
        }
    }

    static Map<String, Object> meta(TerrainParams tp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("probe", "WidthContinuityProbe");
        m.put("readOnly", true);
        m.put("generatedAtUtc", Instant.now().toString());
        m.put("notice", "段宽曲线：half = 生产 SegmentIndex 段端点半宽(block)，不是游戏湿列/水深/流量；"
                + "无图像；未给全周期 PASS。");
        m.put("seed", seed);
        m.put("regionRange", List.of(minR, maxR));
        m.put("regionSize", REGION);
        m.put("step", STEP);
        m.put("providerBoundaryPorts", staticField(ModelHydrologyProvider.class, "BOUNDARY_PORTS"));
        m.put("skeletonPortEgress", staticField(DeterministicSkeleton.class, "PORT_EGRESS"));
        m.put("strategyFingerprint", prov.strategyFingerprintValue());
        m.put("configSha256", cfgSha);
        m.put("configCopy", out.resolve("config-probe.toml").toAbsolutePath().toString());
        m.put("terrainParamsHash", tp.hashCode());
        m.put("horizontalScale", tp.horizontalScale());
        m.put("seaLevel", tp.seaLevel());
        m.put("systemProperties", sysProps());
        m.put("widthKnobs", widthKnobs());
        m.put("judgeRules", judgeRules());
        m.put("evidenceAdded", "SegmentIndex.widthLimits（每段 2 端点上限，生产 clamp 依据）与 "
                + "SegmentIndex.lakeInterfaces（LakeWidthEnvelope.Interface：河湖接口横向湿宽与允许上限）");
        m.put("classSources", classSources());
        return m;
    }

    /** 判据口径随 summary 一起落盘，避免日后拿旧规则解释新数字。 */
    static Map<String, Object> judgeRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeKind", "RiverWidthProfile.serialEdge(up,down,rivers,succ)；succ 由 chainEdges 构建");
        m.put("serial", "gap 必须 == 0（容差 " + SERIAL_GAP_TOL + "）；这是防重置契约，不放宽");
        m.put("confluence", "允许不等宽；只要求 receiverHalf >= upHalf（容差 " + CONFLUENCE_GAP_TOL + "）");
        m.put("limitedShortening", "接收方被 widthLimits 夹住（limit < " + MAX_HALF + " 且 half >= limit − "
                + LIMIT_TOL + "）⇒ LIMIT_EXCEPTION，列出不计违约；"
                + "limit == " + MAX_HALF + " 且 half == " + MAX_HALF + " 只是顶到 12 格总上限，不算此类");
        m.put("headRamp", "接收方满足 linearSource 的施加条件：非 fromOutlet、是其单元首 trace、"
                + "无「CONNECTED 接收点正好落在它首格」的明确续流、无以首格为 anchor 的已解析接入，"
                + "且接点弧长 ≤ " + HEAD_RAMP_BLOCKS + " block ⇒ 只写 headRampExplains 诊断字段，"
                + "【不豁免】：真汇流承接不足照计 contractFailures");
        m.put("exceptionsAreInferred", "两类例外都是【推断】（读不到生产 pins 内部计划）；"
                + "一律全额列出数字，且只会少报违约、不会把本该通过的判成失败");
        m.put("notAFailure", "未消费/被拒收端口、未知湖身份、真汇流本身不等宽");
        m.put("stillNotAcceptance", "段宽属性 ≠ 湿列/水深/断面；本 summary 不给全周期验收结论");
        return m;
    }

    static Map<String, Object> sysProps() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new TreeSet<>(System.getProperties().stringPropertyNames())) {
            if (k.startsWith("geogenesis.") || k.startsWith("hydro.")) m.put(k, System.getProperty(k));
        }
        return m;
    }

    static Map<String, Object> widthKnobs() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : new String[]{"WIDTH_SMOOTH", "WIDTH_BLEND", "WIDTH_MONOTONE"}) {
            m.put(n, staticField(ModelHydrologyProvider.SegmentIndex.class, n));
        }
        return m;
    }

    static List<String> classSources() {
        List<String> list = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{WidthContinuityProbe.class, ModelHydrologyProvider.class,
                DeterministicSkeleton.class, RiverWidthProfile.class, ModelHydrologyProvider.SegmentIndex.class}) {
            list.add(c.getName() + " sha256=" + classSha(c) + " source=" + codeSource(c));
        }
        return list;
    }

    static String classSha(Class<?> type) {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) throw new IllegalStateException("class bytes not found: " + type.getName());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("class sha failed: " + type.getName(), e);
        }
    }

    static String codeSource(Class<?> type) {
        var cs = type.getProtectionDomain().getCodeSource();
        if (cs == null) throw new IllegalStateException("no code source: " + type.getName());
        return String.valueOf(cs.getLocation());
    }

    // ---------------- 单区 ----------------

    static Map<String, Object> collectRegion(int rx, int rz, BufferedWriter shapeCsv,
                                             BufferedWriter widthCsv) throws Exception {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("region", List.of(rx, rz));
        View v = view(rx, rz);       // 取数失败直接抛（不吞），由 run() 走 FATAL/非零退出
        probed++;
        Stats st = new Stats();
        sec.put("curveRows", writeCurves(v, shapeCsv, widthCsv, st));
        GLOBAL.merge(st);
        sec.put("traces", v.traces.size());
        sec.put("polys", v.shapes.size());
        sec.put("status", v.shapes.isEmpty() ? "EMPTY_NO_RIVER" : "COLLECTED");
        sec.put("shapeAlignedWithTraces", v.aligned);
        sec.put("window", List.of(v.ox, v.oz, v.step, v.n));
        sec.put("segRecords", v.segCount());
        sec.put("polySegmentsExpected", v.polySegmentsExpected());
        sec.put("sewBridgeSegments", Math.max(0, v.segCount() - v.polySegmentsExpected()));
        sec.put("segRecordsMissing", Math.max(0, v.polySegmentsExpected() - v.segCount()));
        sec.put("unmatchedSegments", v.unmatchedSeg);
        sec.put("unmatchedNodes", v.unmatchedNodes);
        sec.put("baseOffsetMisses", v.baseOffsetMisses);
        sec.put("nodeHalfMismatchAtSharedNode", v.nodeHalfMismatch);
        sec.put("shapeSHA256RawBits", v.shapeSha);
        sec.put("nodeJumps", st.nodeStats());
        sec.put("perBlockJumps", st.blockStats());
        sec.put("join", collectJoin(v));
        sec.put("boundary", collectBoundary(v));
        sec.put("widthLimits", collectLimits(v));
        sec.put("lakeInterfaces", collectInterfaces(v));
        sec.put("newlySolvedKeys", v.newSolves);
        return sec;
    }

    /**
     * 生产 {@code SegmentIndex.widthLimits} 证据（每段 2 端点，与 {@code seg} 的 h0/h1 同下标）。
     *
     * <p>它不是"当前宽度"，是<b>上限</b>：{@code half} 被夹到 {@code min(half, limit)}。
     * 统计 {@code cappedNodes} 用来把"上限生效导致的变窄"与"真缺陷"分开 —— 前者不算违约。</p>
     */
    static Map<String, Object> collectLimits(View v) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("source", "SegmentIndex.widthLimits：每段 2 端点，布局与 seg 的 h0/h1 相同（si*2 / si*2+1）");
        sec.put("defaultWhenMissing", MAX_HALF);
        if (v.rawLimits == null) {
            sec.put("status", "NOT_PRESENT");
            sec.put("note", "该 RegionData 未带 widthLimits（非 skeletonSeg 构造路径）⇒ 不做上限归因");
            NOTES.add("[limits] region " + v.rx + ":" + v.rz + " widthLimits 缺失，上限归因不可用");
            return sec;
        }
        int expected = Math.max(0, v.segCount()) * 2;
        DoubleBuf values = new DoubleBuf();
        for (double x : v.rawLimits) if (Double.isFinite(x)) values.add(x);
        long nodes = 0, capped = 0, over = 0, strictLimits = 0;
        DoubleBuf excess = new DoubleBuf();
        DoubleBuf overage = new DoubleBuf();
        for (int p = 0; p < v.halves.size(); p++) {
            double[] half = v.halves.get(p), lim = v.limits.get(p);
            if (half == null || lim == null) continue;
            for (int i = 0; i < half.length && i < lim.length; i++) {
                if (!Double.isFinite(half[i]) || !Double.isFinite(lim[i])) continue;
                nodes++;
                if (lim[i] < MAX_HALF - LIMIT_TOL) strictLimits++;
                // 只有【低于全局无约束值】的上限才算"真被夹住"；
                // limit == MAX_HALF 且 half == MAX_HALF 是"顶到 12 格总上限"，不是湖/接点约束。
                if (lim[i] < MAX_HALF - LIMIT_TOL && half[i] >= lim[i] - LIMIT_TOL) {
                    capped++;
                    excess.add(MAX_HALF - lim[i]);
                }
                // ★ 硬契约：落块半宽不得超过生产上限。超了就是 cap 没生效（接点/湖口会破坏几何比例）。
                if (half[i] > lim[i] + LIMIT_VIOLATION_TOL) {
                    over++;
                    overage.add(half[i] - lim[i]);
                    if (LIMIT_VIOLATION_SAMPLES.size() < 40) {
                        LIMIT_VIOLATION_SAMPLES.add("r(" + v.rx + "," + v.rz + ") poly=" + p + " node=" + i
                                + " half=" + fmt(half[i]) + " limit=" + fmt(lim[i]));
                    }
                }
            }
        }
        LIMIT_NODES += nodes;
        LIMIT_CAPPED_NODES += capped;
        LIMIT_CAPPED_EXCESS.merge(excess);
        LIMIT_CHK[0] += nodes;
        LIMIT_CHK[1] += over;
        LIMIT_CHK[2] += strictLimits;
        LIMIT_OVER.merge(overage);
        if (over > 0) {
            contractFailures += (int) over;
            CONTRACT_VIOLATIONS.add("[limit] r(" + v.rx + "," + v.rz + ") " + over
                    + " 个节点 half > widthLimits（最大超出 " + fmt(maxOf(overage)) + "），cap 未生效");
        }
        sec.put("status", "COLLECTED");
        sec.put("arrayLength", v.rawLimits.length);
        sec.put("arrayLengthExpected", expected);
        sec.put("arrayLengthMatchesSeg", v.rawLimits.length == expected);
        sec.put("entries", values.stats());
        sec.put("nodesWithFiniteHalf", nodes);
        sec.put("nodesCappedByStrictLimit", capped);
        sec.put("nodesBelowStrictLimit", strictLimits);
        sec.put("nodesOverLimit", over);
        sec.put("overLimitExcessStats", overage.stats());
        sec.put("entriesBelowGlobalMax", countBelow(v.rawLimits, MAX_HALF - LIMIT_TOL));
        sec.put("cappedBelowGlobalMaxExcess", excess.stats());
        sec.put("rule", "capped：只有 limit < MAX_HALF 且 half >= limit 的节点才计"
                + "（limit == MAX_HALF 且 half == MAX_HALF 是顶到 12 格总上限，不算湖/接点约束）；"
                + "over：half > limit + " + LIMIT_VIOLATION_TOL + " 即契约违约（cap 未生效）");
        if (v.rawLimits.length != expected) {
            fail++;
            NOTES.add("[limits] region " + v.rx + ":" + v.rz + " widthLimits.length=" + v.rawLimits.length
                    + " 与 seg*2=" + expected + " 不一致（上限归因不可信）");
        }
        return sec;
    }

    static int countBelow(double[] a, double threshold) {
        int c = 0;
        for (double x : a) if (Double.isFinite(x) && x < threshold) c++;
        return c;
    }

    /**
     * 生产 {@code SegmentIndex.lakeInterfaces} 证据（{@code LakeWidthEnvelope.Interface} 行）。
     *
     * <p>这是<b>接口测量证据</b>，不是宽度本身：{@code maxHalf} 是该接口允许的上限，
     * {@code transverse} 是量到的横向湿宽。{@code status} 为未知类（{@code UNKNOWN_*}）时
     * 表示<b>没有</b>施加限制 —— 如实计数，不当作"小湖"。
     * 窗口裁切/身份缺失同样显式可见（{@code UNKNOWN_CLIPPED} / {@code UNKNOWN_NO_IDENTITY}）。</p>
     */
    static Map<String, Object> collectInterfaces(View v) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("source", "SegmentIndex.lakeInterfaces = RiverWidthProfile.Result.interfaces() "
                + "= LakeWidthEnvelope.Interface（kind/poly/cell/basin/status/maxHalf/transverse/samples）");
        sec.put("layer", "接口尺度测量证据，不是河道宽度；未施加限制时 status 为 UNKNOWN_*");
        if (v.ifaces == null) {
            sec.put("status", "NOT_PRESENT");
            return sec;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int applied = 0;
        Map<String, Integer> byStatus = new java.util.TreeMap<>();
        DoubleBuf transverse = new DoubleBuf();
        DoubleBuf maxHalf = new DoubleBuf();
        for (LakeWidthEnvelope.Interface i : v.ifaces) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", i.kind());
            row.put("poly", i.poly());
            row.put("cell", i.cell());
            row.put("basin", i.basin());
            row.put("status", i.status());
            row.put("maxHalf", Double.isFinite(i.maxHalf()) ? i.maxHalf() : null);
            row.put("transverse", Double.isFinite(i.transverse()) ? i.transverse() : null);
            row.put("samples", i.samples());
            row.put("applied", i.applied());
            row.put("shapeIndexValid", i.poly() >= 0 && i.poly() < v.shapes.size());
            // ★ 硬契约：施加了限制的接口处，实际落块半宽不得超过该接口 maxHalf。
            checkInterfaceHalf(v, i, row);
            rows.add(row);
            byStatus.merge(String.valueOf(i.status()), 1, Integer::sum);
            IFACE_STATUS.merge(String.valueOf(i.status()), 1, Integer::sum);
            IFACE_TOTAL++;
            if (i.applied()) {
                applied++;
                IFACE_APPLIED++;
                if (Double.isFinite(i.transverse())) transverse.add(i.transverse());
                maxHalf.add(i.maxHalf());
            }
        }
        sec.put("interfaces", rows.size());
        sec.put("applied", applied);
        sec.put("byStatus", byStatus);
        sec.put("transverseStats", transverse.stats());
        sec.put("maxHalfStats", maxHalf.stats());
        sec.put("halfChecked", IFACE_CHK[0]);
        sec.put("halfOverMaxHalf", IFACE_CHK[1]);
        sec.put("contract", "施加了限制的接口：该节点实际半宽 half > maxHalf + " + LIMIT_VIOLATION_TOL
                + " ⇒ contractFailure（上限没落到落块宽度上）。未施加（applied=false / UNKNOWN_*）不判。"
                + "注：落块层的 bank jitter cap 不在本探针口径内，由 WidthCoverageProbe 另测。");
        IFACE_TRANSVERSE.merge(transverse);
        IFACE_MAX_HALF.merge(maxHalf);
        sec.put("status", rows.isEmpty() ? "NO_INTERFACE" : "COLLECTED");
        sec.put("rows", rows);
        return sec;
    }

    /**
     * 接口处的实际半宽 vs {@code maxHalf}。
     *
     * <p>取接口所在折线的<b>该节点</b>半宽：{@code LakeWidthEnvelope} 的 {@code maxHalf} 就是
     * 在 {@code g.arcOf(p, node)} 处施加的，所以口径必须同点位，不能拿整条折线的最大值。</p>
     */
    static void checkInterfaceHalf(View v, LakeWidthEnvelope.Interface i, Map<String, Object> row) {
        row.put("halfAtInterface", null);
        row.put("halfOverMaxHalf", null);
        if (!i.applied() || i.poly() < 0 || i.poly() >= v.halves.size()) return;
        double[] half = v.halves.get(i.poly());
        double[] arc = v.arcs.get(i.poly());
        if (half == null || arc == null || half.length == 0) return;
        int node = interfaceNode(i, arc);
        if (node < 0 || node >= half.length || !Double.isFinite(half[node])) return;
        double h = half[node];
        double over = h - i.maxHalf();
        row.put("halfAtInterface", h);
        row.put("halfOverMaxHalf", over);
        IFACE_CHK[0]++;
        if (over <= LIMIT_VIOLATION_TOL) return;
        IFACE_CHK[1]++;
        IFACE_HALF_OVER.add(over);
        row.put("contractOk", false);
        contractFailures++;
        CONTRACT_VIOLATIONS.add("[interface] " + i.kind() + " poly=" + i.poly() + " node=" + node
                + " half=" + fmt(h) + " > maxHalf=" + fmt(i.maxHalf()) + "（超 " + fmt(over) + "）");
    }

    /**
     * 接口所在折线节点：{@code MOUTH} 用末节点，{@code SOURCE} 用首节点，
     * 其余按 {@code arcOf} 反查（与 {@code LakeWidthEnvelope.relax} 的传入点位一致）。
     */
    static int interfaceNode(LakeWidthEnvelope.Interface i, double[] arc) {
        if (LakeWidthEnvelope.KIND_MOUTH.equals(i.kind())) return arc.length - 1;
        if (LakeWidthEnvelope.KIND_SOURCE.equals(i.kind())) return 0;
        return -1;
    }

    static int writeCurves(View v, BufferedWriter shapeCsv, BufferedWriter widthCsv, Stats st)
            throws Exception {
        int rows = 0;
        for (int p = 0; p < v.shapes.size(); p++) {
            double[] x = v.shapes.get(p)[0], z = v.shapes.get(p)[1];
            double[] arc = v.arcs.get(p), half = v.halves.get(p), limit = v.limits.get(p);
            String end = "NA", outl = "NA";
            if (p < v.traces.size()) {
                end = String.valueOf(v.traces.get(p).end);
                outl = String.valueOf(v.traces.get(p).fromOutlet);
            }
            for (int j = 0; j < x.length; j++) {
                String xs = Double.toString(x[j]), zs = Double.toString(z[j]);
                shapeCsv.write(v.rx + "," + v.rz + "," + p + "," + j + "," + xs + "," + zs + "\n");
                widthCsv.write(v.rx + "," + v.rz + "," + p + "," + j + "," + xs + "," + zs + ","
                        + Double.toString(half[j]) + "," + Double.toString(arc[j]) + ","
                        + end + "," + outl + ","
                        + (limit == null ? "NaN" : Double.toString(limit[j])) + ","
                        + v.succTrace(p) + "\n");
                rows++;
            }
            st.add(arc, half);
        }
        return rows;
    }

    // ---------------- 视图（尽量直取，仅段索引需反射） ----------------

    static View view(int rx, int rz) {
        String key = rx + ":" + rz;
        View hit = VIEWS.get(key);
        if (hit != null) return hit;
        int bx = rx * REGION + REGION / 2, bz = rz * REGION + REGION / 2;
        TreeSet<String> before = solvedKeys();
        DeterministicSkeleton.Result sk = prov.finalSkeletonForProbe(rx, rz);
        int[] frame = prov.windowFrameForProbe(bx, bz);
        double[][] nodes = prov.shapedNodesWorldForProbe(bx, bz);
        View v = new View();
        v.rx = rx;
        v.rz = rz;
        v.ox = frame[0];
        v.oz = frame[1];
        v.step = frame[2];
        v.n = frame[3];
        if (sk == null) throw new IllegalStateException("region " + rx + ":" + rz + " finalSkeleton 为 null");
        v.traces = sk.traces;
        v.rivers = sk.rivers;
        v.merge = sk.mergeEvidence;
        Evidence ev = evidenceOf(rx, rz);
        v.seg = ev.seg();
        v.segIndex = segMap(v.seg);
        v.rawLimits = ev.limits();
        v.ifaces = ev.interfaces();
        for (double[] xy : nodes) {
            int m = xy.length / 2;
            double[] x = new double[m], z = new double[m];
            for (int j = 0; j < m; j++) { x[j] = xy[2 * j]; z[j] = xy[2 * j + 1]; }
            v.shapes.add(new double[][]{x, z});
        }
        v.aligned = prov.shapeAlignedForProbe(rx, rz);
        v.shapeSha = shapeSha(v.shapes);
        v.succ = succOf(v.rivers, v.shapes.size());
        for (int p = 0; p < v.shapes.size(); p++) nodeHalves(v, p);
        TreeSet<String> after = solvedKeys();
        after.removeAll(before);
        v.newSolves = new ArrayList<>(after);
        NEW_SOLVES.addAll(after);
        VIEWS.put(key, v);
        return v;
    }

    /** 段索引 + 新增宽度证据（上限数组、湖接口行）；一次反射取齐。 */
    record Evidence(double[] seg, double[] limits, List<LakeWidthEnvelope.Interface> interfaces) { }

    /**
     * 唯一反射点：private {@code regionFor} → private record {@code RegionData.skeletonSeg()}
     * → private {@code SegmentIndex.seg / widthLimits / lakeInterfaces}。
     *
     * <p>★ 上限与接口是<b>只读诊断载荷</b>：本探针不写回、不改生产。缺失（{@code widthLimits == null}
     * 的旧构造路径、或分层路径无 skeletonSeg）直接抛 ⇒ 走采集失败，不静默当"无上限"。</p>
     */
    static Evidence evidenceOf(int rx, int rz) {
        try {
            Method regionFor = ModelHydrologyProvider.class.getDeclaredMethod("regionFor", int.class, int.class);
            regionFor.setAccessible(true);
            Object rd = regionFor.invoke(prov, rx * REGION + REGION / 2, rz * REGION + REGION / 2);
            Method acc = rd.getClass().getDeclaredMethod("skeletonSeg");
            acc.setAccessible(true);
            Object si = acc.invoke(rd);
            if (si == null) throw new IllegalStateException("region " + rx + ":" + rz + " skeletonSeg 为 null");
            Field segF = si.getClass().getDeclaredField("seg");
            segF.setAccessible(true);
            Field limF = si.getClass().getDeclaredField("widthLimits");
            limF.setAccessible(true);
            Field ifF = si.getClass().getDeclaredField("lakeInterfaces");
            ifF.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<LakeWidthEnvelope.Interface> ifaces = (List<LakeWidthEnvelope.Interface>) ifF.get(si);
            return new Evidence((double[]) segF.get(si), (double[]) limF.get(si),
                    ifaces == null ? List.of() : List.copyOf(ifaces));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("region " + rx + ":" + rz + " 段索引/宽度证据不可读", e);
        }
    }

    /**
     * 生产后继图：直接复用 {@link RiverWidthProfile#chainEdges}（包内 static）——与宽度剖面、
     * 湖接口包络<b>同一份</b>接点语义，探针不另写一套边推导。
     *
     * <p>冲突（同一 trace 两个不同接收者）由 {@code link()} 抛 {@link IllegalStateException}
     * ⇒ 采集失败，不静默选中一个。</p>
     */
    static int[] succOf(List<DeterministicSkeleton.River> rivers, int np) {
        int[] succ = new int[Math.max(0, np)];
        Arrays.fill(succ, -1);
        if (rivers != null) for (var r : rivers) RiverWidthProfile.chainEdges(succ, r, succ.length, rivers);
        return succ;
    }

    static void nodeHalves(View v, int p) {
        double[] x = v.shapes.get(p)[0], z = v.shapes.get(p)[1];
        int m = x.length;
        double[] arc = new double[m], half = new double[m], limit = new double[m];
        Arrays.fill(half, Double.NaN);
        Arrays.fill(limit, Double.NaN);
        for (int j = 1; j < m; j++) arc[j] = arc[j - 1] + Math.hypot(x[j] - x[j - 1], z[j] - z[j - 1]);
        int base = v.baseIndex(p);
        boolean haveLimits = v.rawLimits != null;
        for (int j = 0; j + 1 < m; j++) {
            int idx = findSeg(v, base + j, x[j], z[j], x[j + 1], z[j + 1]);
            if (idx < 0) { v.unmatchedSeg++; continue; }
            int o = idx * 8;
            if (j == 0) half[0] = v.seg[o + 4];
            if (j > 0 && Double.isFinite(half[j]) && !bits(half[j], v.seg[o + 4])) v.nodeHalfMismatch++;
            half[j + 1] = v.seg[o + 5];
            if (haveLimits) {
                // 生产布局：widthLimits[si*2] / [si*2+1] 与 seg 的 h0 / h1 同序同下标。
                if (j == 0) limit[0] = limitAt(v, idx * 2);
                limit[j + 1] = limitAt(v, idx * 2 + 1);
            }
        }
        for (double h : half) if (!Double.isFinite(h)) v.unmatchedNodes++;
        v.arcs.add(arc);
        v.halves.add(half);
        v.limits.add(limit);
    }

    /** 段端点上限；缺失/越界 ⇒ NaN（= 无约束证据，不当 0 也不当 6）。 */
    static double limitAt(View v, int k) {
        if (v.rawLimits == null || k < 0 || k >= v.rawLimits.length) return Double.NaN;
        return v.rawLimits[k];
    }

    static int findSeg(View v, int guess, double x0, double z0, double x1, double z1) {
        if (v.seg == null) return -1;
        if (guess >= 0 && exact(v.seg, guess, x0, z0, x1, z1)) return guess;
        if (guess >= 0) v.baseOffsetMisses++;
        List<Integer> cand = v.segIndex.get(quadKey(x0, z0, x1, z1));
        if (cand == null) return -1;
        for (int k : cand) if (exact(v.seg, k, x0, z0, x1, z1)) return k;
        return -1;
    }

    static boolean exact(double[] seg, int idx, double x0, double z0, double x1, double z1) {
        int o = idx * 8;
        if (o < 0 || o + 7 >= seg.length) return false;
        return bits(seg[o], x0) && bits(seg[o + 1], z0)
                && bits(seg[o + 2], x1) && bits(seg[o + 3], z1);
    }

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static Map<Long, List<Integer>> segMap(double[] seg) {
        Map<Long, List<Integer>> m = new HashMap<>();
        if (seg == null) return m;
        for (int i = 0; i < seg.length / 8; i++) {
            int o = i * 8;
            m.computeIfAbsent(quadKey(seg[o], seg[o + 1], seg[o + 2], seg[o + 3]),
                    k -> new ArrayList<>()).add(i);
        }
        return m;
    }

    static long quadKey(double x0, double z0, double x1, double z1) {
        long h = 0xcbf29ce484222325L;
        for (double d : new double[]{x0, z0, x1, z1}) h = (h ^ Double.doubleToLongBits(d)) * 0x100000001b3L;
        return h;
    }

    static String shapeSha(List<double[][]> shapes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (double[][] sh : shapes) {
                for (double v : sh[0]) md.update(longBytes(Double.doubleToRawLongBits(v)));
                for (double v : sh[1]) md.update(longBytes(Double.doubleToRawLongBits(v)));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("shape sha failed", e);
        }
    }

    static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    // ---------------- JOIN：拓扑取 River，投影只定位 ----------------

    /**
     * 收集一条 CONNECTED 边的接点证据，并按<b>生产边分类</b>判定（见类注释）。
     *
     * <p>{@code kind}：{@link #KIND_SERIAL} / {@link #KIND_CONFLUENCE}，由
     * {@link RiverWidthProfile#serialEdge} 决定 —— 与剖面实际施加的约束同一判据。</p>
     */
    static Map<String, Object> collectJoin(View v) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("topologySource", "Result.rivers 的 link/downstreamRiver/downstreamNode + 接收方 traceOf；"
                + "投影只给位置与残差，不冒充拓扑锚点");
        sec.put("edgeKindSource", "RiverWidthProfile.serialEdge(up,down,rivers,succ)，succ 由 "
                + "RiverWidthProfile.chainEdges 构建 ⇒ 与宽度剖面同一份接点语义");
        sec.put("criteria", Map.of(
                KIND_SERIAL, "gap 必须为 0（容差 " + SERIAL_GAP_TOL + "）：串联接力不得重置宽度",
                KIND_CONFLUENCE, "允许不等宽；只要求 receiverHalf >= upHalf（容差 "
                        + CONFLUENCE_GAP_TOL + "）。支流被钉成干流宽不算通过",
                "gapSign", "gap = 上游末节点半宽 − 接收方投影处半宽；正=上游更宽（真汇流下即违约方向）"));
        sec.put("exceptionRules", "真汇流变窄若由生产 widthLimits < " + MAX_HALF + " 严格夹住 ⇒ "
                + "status=LIMIT_EXCEPTION：单独列出、不计 contractFailures，但仍计入 limitedShortening "
                + "⇒ 阻止「总体全绿」声称（上限须在接点两侧统一后再谈通过）。"
                + "落在 RiverWidthProfile.linearSource 的 " + HEAD_RAMP_BLOCKS + " block 河头窗口内"
                + "【不再豁免】：只写 headRampExplains 诊断字段，违约照算");
        if (v.rivers == null) {
            throw new IllegalStateException("region " + v.rx + ":" + v.rz + " Result.rivers 为 null");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        DoubleBuf gaps = new DoubleBuf();
        DoubleBuf serialGaps = new DoubleBuf();
        DoubleBuf shortfalls = new DoubleBuf();
        int connected = 0, measured = 0, serial = 0, confluence = 0, ok = 0, limited = 0, headRamp = 0, bad = 0;
        for (DeterministicSkeleton.River r : v.rivers) {
            if (r.link != DeterministicSkeleton.RiverLink.CONNECTED) continue;
            connected++;
            Map<String, Object> row = joinRow(v, r);
            rows.add(row);
            String kind = String.valueOf(row.get("kind"));
            String verdict = String.valueOf(row.get("verdict"));
            boolean serialEdge = KIND_SERIAL.equals(kind);
            if (serialEdge) serial++; else if (KIND_CONFLUENCE.equals(kind)) confluence++;
            if (row.get("gap") instanceof Double g) {
                gaps.add(Math.abs(g));
                JOIN_GAPS.add(Math.abs(g));
                measured++;
                if (serialEdge) serialGaps.add(Math.abs(g));
            }
            switch (verdict) {
                case VERDICT_OK -> ok++;
                case VERDICT_LIMIT_EXCEPTION -> limited++;
                case VERDICT_VIOLATION -> bad++;
                default -> { }
            }
            if (Boolean.TRUE.equals(row.get("headRampExplains")) && VERDICT_VIOLATION.equals(verdict)) headRamp++;
            if (row.get("shortfall") instanceof Double sf) {
                shortfalls.add(sf);
                CONFLUENCE_SHORTFALLS.add(sf);
            }
        }
        CNT[0] += connected;
        CNT[1] += measured;
        JOIN_CNT[0] += serial;
        JOIN_CNT[2] += confluence;
        JOIN_CNT[3] += ok;
        JOIN_CNT[4] += limited;
        JOIN_CNT[5] += headRamp;
        JOIN_CNT[6] += bad;
        JOIN_CNT[7] += connected - measured;
        sec.put("connectedRivers", connected);
        sec.put("measuredGaps", measured);
        sec.put("gapAbsStats", gaps.stats());
        sec.put("serial", serial);
        sec.put("confluence", confluence);
        sec.put("serialGapAbsStats", serialGaps.stats());
        sec.put("confluenceShortfallStats", shortfalls.stats());
        sec.put("verdictCounts", verdictCounts(ok, limited, headRamp, bad));
        sec.put("status", connected == 0 ? "NO_CONNECTED_JOIN" : "COLLECTED");
        sec.put("rows", rows);
        if (connected > 0 && measured == 0) {
            fail++;
            NOTES.add("[join] region " + v.rx + ":" + v.rz + " 有 CONNECTED 但无一个可测 gap");
        }
        return sec;
    }

    static final String KIND_SERIAL = "SERIAL";
    static final String KIND_CONFLUENCE = "CONFLUENCE";
    static final String VERDICT_OK = "OK";
    static final String VERDICT_VIOLATION = "VIOLATION";
    static final String VERDICT_LIMIT_EXCEPTION = "LIMIT_EXCEPTION";

    static Map<String, Object> verdictCounts(int ok, int limited, int headRamp, int bad) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(VERDICT_OK, ok);
        m.put(VERDICT_LIMIT_EXCEPTION, limited);
        m.put("headRampExplainedViolations", headRamp);
        m.put(VERDICT_VIOLATION, bad);
        m.put("note", "headRampExplainedViolations 是 VIOLATION 的子集（仅诊断），不再有单独的豁免档");
        return m;
    }

    static Map<String, Object> joinRow(View v, DeterministicSkeleton.River r) {
        Map<String, Object> row = new LinkedHashMap<>();
        int up = r.traceIdx.length == 0 ? -1 : r.traceIdx[r.traceIdx.length - 1];
        int downIdx = r.downstreamRiver, downNode = r.downstreamNode;
        int recv = -1;
        if (downIdx >= 0 && downIdx < v.rivers.size()) {
            int[] traceOf = v.rivers.get(downIdx).traceOf;
            if (downNode >= 0 && downNode < traceOf.length) recv = traceOf[downNode];
        }
        row.put("upstreamTraceIndex", up);
        row.put("receiverTraceIndex", recv);
        row.put("downstreamRiver", downIdx);
        row.put("downstreamNode", downNode);
        row.put("receiverIsUpstreamTrace", recv == up);
        if (up < 0 || recv < 0 || up >= v.shapes.size() || recv >= v.shapes.size() || up >= v.succ.length) {
            fail++;
            row.put("kind", "UNKNOWN");
            row.put("verdict", "NOT_COLLECTED");
            row.put("status", "NOT_COLLECTED");
            row.put("reason", "River 的 trace 下标不可用或越界");
            return row;
        }
        boolean serial = RiverWidthProfile.serialEdge(up, recv, v.rivers, v.succ);
        row.put("kind", serial ? KIND_SERIAL : KIND_CONFLUENCE);
        row.put("succAgrees", v.succ[up] == recv);
        double[] ux = v.shapes.get(up)[0], uz = v.shapes.get(up)[1];
        int last = ux.length - 1;
        double upHalf = last < 0 ? Double.NaN : v.halves.get(up)[last];
        Map<String, Object> proj = project(v, recv, ux[last], uz[last]);
        row.put("upTailWorld", List.of(ux[last], uz[last]));
        row.put("upTailHalf", upHalf);
        row.put("receiverProjection", proj);
        Object rh = proj.get("half");
        if (!(rh instanceof Double d) || !Double.isFinite(d) || !Double.isFinite(upHalf)) {
            fail++;
            row.put("gap", null);
            row.put("verdict", "NOT_COLLECTED");
            row.put("status", "NOT_COLLECTED");
            row.put("reason", "CONNECTED 却取不到段半宽（up=" + up + " recv=" + recv + "）");
            return row;
        }
        double gap = upHalf - d;                    // 正 = 上游更宽
        row.put("receiverHalf", d);
        row.put("gap", gap);
        row.put("status", "COLLECTED");
        judge(v, row, up, recv, upHalf, d, gap, serial, proj);
        return row;
    }

    /**
     * 分类判定并写 {@code verdict / shortfall / exception / reason}。
     *
     * <p>串联：{@code |gap| ≤ SERIAL_GAP_TOL} 才 OK。
     * 真汇流：{@code gap ≤ CONFLUENCE_GAP_TOL} 即 OK（支流更细是合法的）；上游更宽<b>一律计违约</b>。</p>
     *
     * <p>★ 2026-10-26 最终口径：{@code HEAD_RAMP_EXCEPTION} <b>不再豁免</b>。
     * 「下游至少承接较大来流」是计划 §3.D 的硬要求，河头线性段不是免罪理由；
     * 而且 {@link #headRampExplains} 只是<b>推断</b>（读不到生产 pins 的内部计划），
     * 未来会漏报。故该判定降级为纯诊断字段，违约照算 —— 宁可报错，不可假绿。</p>
     * <p>{@code LIMIT_EXCEPTION} 仍单独列（生产上限确实是已知设计后果），
     * 但它<b>会</b>置 {@code limitedShortening > 0}，令 {@link #allGreen()} 为假：
     * 单独列出 ≠ 总体全绿。</p>
     */
    static void judge(View v, Map<String, Object> row, int up, int recv,
                      double upHalf, double recvHalf, double gap, boolean serial, Map<String, Object> proj) {
        row.put("shortfall", null);
        row.put("exception", null);
        row.put("headRampExplains", null);
        if (serial) {
            if (Math.abs(gap) <= SERIAL_GAP_TOL) {
                row.put("verdict", VERDICT_OK);
                return;
            }
            row.put("verdict", VERDICT_VIOLATION);
            row.put("reason", "串联接力必须等宽：" + fmt(gap) + " > " + SERIAL_GAP_TOL);
            contractFailures++;
            JOIN_CNT[1]++;
            CONTRACT_VIOLATIONS.add("[serial] r(" + v.rx + "," + v.rz + ") up=" + up + " recv=" + recv
                    + " gap=" + fmt(gap));
            return;
        }
        if (gap <= CONFLUENCE_GAP_TOL) {
            row.put("verdict", VERDICT_OK);
            return;
        }
        double shortfall = gap;                     // 上游比下游粗多少
        row.put("shortfall", shortfall);            // 全局/分区统计在 collectJoin 里统一累加，避免重复计数
        Integer seg = proj == null ? null : (Integer) proj.get("segmentIndex");
        Double frac = proj == null ? null : (Double) proj.get("fraction");
        double joinArc = proj == null || seg == null ? Double.NaN
                : arcAt(v, recv, seg) + (frac == null ? 0 : frac) * segLen(v, recv, seg);
        // 诊断字段：不改判定。HEAD_RAMP 不再豁免，只记录"为什么可能是河头线性造成的"。
        boolean headRamp = headRampExplains(v, recv, joinArc);
        row.put("headRampExplains", headRamp);
        boolean limitHit = limitExplains(v, recv, seg, frac, recvHalf);
        row.put("limitExplains", limitHit);
        if (limitHit) {
            // 仍单独列（不计 contractFailures），但让 limitedShortening 计数拦住"全绿"声称。
            row.put("verdict", VERDICT_LIMIT_EXCEPTION);
            row.put("exception", LIMIT_EXCEPTION_KIND);
            LIMIT_EXCEPTIONS.add("[limit] r(" + v.rx + "," + v.rz + ") up=" + up + " recv=" + recv
                    + " shortfall=" + fmt(shortfall) + "（生产上限把接收方夹细；不计违约但不算全绿）");
            return;
        }
        row.put("verdict", VERDICT_VIOLATION);
        row.put("reason", "真汇流下游承接不足：上游更宽 " + fmt(shortfall)
                + (headRamp ? "（落在河头线性段内，但不是豁免理由）" : ""));
        contractFailures++;
        CONTRACT_VIOLATIONS.add("[confluence] r(" + v.rx + "," + v.rz + ") up=" + up + " recv=" + recv
                + " upHalf=" + fmt(upHalf) + " recvHalf=" + fmt(recvHalf)
                + (headRamp ? " headRampExplains=true(no-exemption)" : ""));
    }

    static final String LIMIT_EXCEPTION_KIND = "PRODUCTION_WIDTH_LIMIT";

    /** 接收方该节点是否被生产湖/接点上限<b>严格</b>夹住。
     *
     *  <p>{@code limit == MAX_HALF} 表示该节点没有湖/接点约束（只是 12 格总上限），
     *  即使 {@code half} 恰好等于 {@code MAX_HALF} 也不算"被约束夹细"。</p>
     */
    static boolean limitExplains(View v, int recv, Integer seg, Double frac, double recvHalf) {
        double[] lim = v.limits.get(recv);
        if (lim == null || lim.length == 0) return false;
        double l;
        if (seg != null && frac != null && seg >= 0 && seg + 1 < lim.length) {
            double l0 = lim[seg], l1 = lim[seg + 1];
            l = Double.isFinite(l0) && Double.isFinite(l1) ? l0 + (l1 - l0) * frac : Double.NaN;
        } else {
            l = lim[lim.length - 1];
        }
        return Double.isFinite(l) && l < MAX_HALF - LIMIT_TOL && recvHalf >= l - LIMIT_TOL;
    }

    /**
     * 接收方是否处于 {@code RiverWidthProfile.linearSource} 的河头线性段，且是<b>真源头</b>。
     *
     * <p>逐条复用生产的施加条件（{@code taperSources} + {@code applyJoinAndPins}）：</p>
     * <ol>
     *   <li>非 {@code fromOutlet}（出湖不重复河头淡出）；</li>
     *   <li>是所在单元的<b>首 trace</b>（串联后继不重复淡出）；</li>
     *   <li>没有 {@code CONNECTED} 边把它当接收者且接点正好落在它的<b>首格</b>
     *       （那是明确续流，不淡出）；</li>
     *   <li>本区 merge 证据里没有以它首格为 anchor 的已解析接入（typed wire 续流，同样不淡出）；</li>
     *   <li>接点弧长 {@code ≤ HEAD_RAMP_BLOCKS}（{@code linearSource} 只覆盖到首个接点）。</li>
     * </ol>
     * <p>★ <b>只作诊断</b>（2026-10-26 最终口径）：读不到生产 pins 的内部计划 ⇒ 这是<b>推断</b>，
     * 会漏报；而「下游至少承接较大来流」是计划 §3.D 的硬要求。
     * 故该函数只写 {@code headRampExplains} 字段，<b>不</b>豁免违约。
     * 未来主线程把宽度重排成「自身 pins → cap → downstream 传播」后再复测，此字段应自然下降。</p>
     */
    static boolean headRampExplains(View v, int recv, double joinArc) {
        if (!Double.isFinite(joinArc) || recv < 0 || recv >= v.traces.size()) return false;
        var t = v.traces.get(recv);
        if (t.fromOutlet || t.cell.length == 0) return false;
        int head = t.sourceCell();
        // ② 必须是单元首 trace：任何单元把它放在 k>=1 位置 ⇒ 它是串联后继。
        for (var r : v.rivers) {
            for (int k = 1; k < r.traceIdx.length; k++) if (r.traceIdx[k] == recv) return false;
        }
        // ③ 明确续流：CONNECTED 接收点正好是它的首格。
        for (var r : v.rivers) {
            if (r.link != DeterministicSkeleton.RiverLink.CONNECTED) continue;
            if (r.downstreamRiver < 0 || r.downstreamRiver >= v.rivers.size()) continue;
            if (r.downstreamNode < 0) continue;
            var rr = v.rivers.get(r.downstreamRiver);
            if (r.downstreamNode >= rr.cells.length || r.downstreamNode >= rr.traceOf.length) continue;
            if (rr.traceOf[r.downstreamNode] == recv && rr.cells[r.downstreamNode] == head) return false;
        }
        // ④ typed wire 续流：本区已解析的接入若锚在该首格 ⇒ 不淡出。
        if (v.merge != null) {
            for (var e : v.merge) if (e.resolved() && e.anchor() == head) return false;
        }
        // ⑤ 只在首个接点之前那段（linearSource 的覆盖范围）内才可能受影响。
        return joinArc <= HEAD_RAMP_BLOCKS + ARC_EPS;
    }

    static double arcAt(View v, int p, int seg) {
        double[] a = v.arcs.get(p);
        return a == null || seg < 0 || seg >= a.length ? Double.NaN : a[seg];
    }

    static double segLen(View v, int p, int seg) {
        double[] a = v.arcs.get(p);
        return a == null || seg + 1 >= a.length || seg < 0
                ? Double.NaN : a[seg + 1] - a[seg];
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    static Map<String, Object> project(View v, int poly, double px, double pz) {
        Map<String, Object> m = new LinkedHashMap<>();
        double[] x = v.shapes.get(poly)[0], z = v.shapes.get(poly)[1];
        double[] half = v.halves.get(poly);
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY, bestT = 0;
        for (int j = 0; j + 1 < x.length; j++) {
            double dx = x[j + 1] - x[j], dz = z[j + 1] - z[j], len2 = dx * dx + dz * dz;
            double t = len2 > 0
                    ? Math.max(0, Math.min(1, ((px - x[j]) * dx + (pz - z[j]) * dz) / len2)) : 0;
            double d = Math.hypot(px - x[j] - t * dx, pz - z[j] - t * dz);
            if (d < bestD) { bestD = d; bestT = t; best = j; }
        }
        m.put("polyIndex", poly);
        m.put("residual", best < 0 ? null : bestD);
        m.put("segmentIndex", best);
        m.put("fraction", bestT);
        if (best < 0 || half == null) {
            m.put("half", null);
            return m;
        }
        double h0 = half[best], h1 = half[best + 1];
        m.put("polyNodeHalves", List.of(h0, h1));
        m.put("half", Double.isFinite(h0) && Double.isFinite(h1) ? h0 + (h1 - h0) * bestT : null);
        return m;
    }

    // ---------------- 跨区边界 ----------------

    static Map<String, Object> collectBoundary(View v) {
        Map<String, Object> sec = new LinkedHashMap<>();
        sec.put("providerBoundaryPorts", staticField(ModelHydrologyProvider.class, "BOUNDARY_PORTS"));
        sec.put("skeletonPortEgress", staticField(DeterministicSkeleton.class, "PORT_EGRESS"));
        sec.put("source", "承诺=发送方 pass1PortsForProbe 的真实 incoming 端口；"
                + "consumed=本区 final 首步或 mergeEvidence.next 逐位相等；"
                + "half=收发各自 final 折线按 anchor 投影（记 residual）");
        sec.put("contract", "已消费且收发候选唯一(UNIQUE)的端口：|gap| ≤ " + PORT_GAP_TOL
                + " 才算通过；超出即 contractFailure。"
                + "【不用 1 格容差】—— 跨区接力是同一条河被切成两段发布，宽度必须逐位相同。"
                + "NOT_UNIQUE / NOT_CONSUMED / rejected 只计数上报，不判失败（契约未覆盖）。");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int drx = -1; drx <= 1; drx++) {
            for (int drz = -1; drz <= 1; drz++) {
                if (drx == 0 && drz == 0) continue;
                collectSenderPorts(v, v.rx + drx, v.rz + drz, rows);
            }
        }
        int consumed = 0, unique = 0, notUnique = 0, checked = 0, violations = 0;
        DoubleBuf gaps = new DoubleBuf();
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("consumed"))) consumed++;
            String st = String.valueOf(row.get("status"));
            if ("UNIQUE".equals(st)) unique++;
            if ("NOT_UNIQUE".equals(st)) notUnique++;
            if (row.get("gap") instanceof Double g) {
                gaps.add(Math.abs(g));
                PORT_GAPS.add(Math.abs(g));
            }
            if (Boolean.TRUE.equals(row.get("contractChecked"))) {
                checked++;
                if (!Boolean.TRUE.equals(row.get("contractOk"))) violations++;
            }
        }
        sec.put("incomingPorts", rows.size());
        sec.put("consumedPorts", consumed);
        sec.put("uniqueGapPorts", unique);
        sec.put("notUniquePorts", notUnique);
        sec.put("contractCheckedPorts", checked);
        sec.put("contractViolations", violations);
        sec.put("gapAbsStats", gaps.stats());
        sec.put("status", rows.isEmpty() ? "NO_INCOMING_PORT" : "COLLECTED");
        sec.put("rows", rows);
        return sec;
    }

    static void collectSenderPorts(View v, int srx, int srz, List<Map<String, Object>> rows) {
        for (BoundaryPort raw : prov.pass1PortsForProbe(srx, srz)) {
            BoundaryPort p = raw.stamp(srx, srz, REGION);
            if (p.receiverRegionX() != v.rx || p.receiverRegionZ() != v.rz) continue;
            CNT[2]++;
            rows.add(boundaryRow(v, p, srx, srz));
        }
    }

    static Map<String, Object> boundaryRow(View v, BoundaryPort p, int srx, int srz) {
        Map<String, Object> row = new LinkedHashMap<>();
        int ax = p.anchorX(), az = p.anchorZ(), nx = p.nextX(), nz = p.nextZ();
        row.put("senderRegion", List.of(srx, srz));
        row.put("receiverRegion", List.of(v.rx, v.rz));
        row.put("anchorWorld", List.of(ax, az));
        row.put("nextWorld", List.of(nx, nz));
        boolean onGrid = onGrid(v, ax, az) && onGrid(v, nx, nz);
        int anchorCell = cellOf(v, ax, az), nextCell = cellOf(v, nx, nz);
        row.put("anchorCell", anchorCell);
        row.put("nextCell", nextCell);
        row.put("onGrid", onGrid);
        Map<String, Object> acc = acceptance(v, anchorCell, nextCell);
        row.put("acceptance", acc);
        boolean rejected = wireRejected(v, p);
        row.put("rejectedByReceiver", rejected);
        boolean consumed = !rejected && onGrid && !"NOT_CONSUMED".equals(String.valueOf(acc.get("mode")));
        row.put("consumed", consumed);
        if (consumed) CNT[3]++;
        else CNT[6]++;
        if (!onGrid) CNT[7]++;
        List<Map<String, Object>> recv = anchorCandidates(v, ax, az);
        List<Map<String, Object>> send = anchorCandidates(view(srx, srz), ax, az);
        row.put("receiverCandidates", recv);
        row.put("senderCandidates", send);
        Map<String, Object> uniq = uniqueGap(recv, send, consumed);
        row.put("status", uniq.get("status"));
        row.put("gap", uniq.get("gap"));
        row.put("candidatePairs", uniq.get("pairs"));
        if ("UNIQUE".equals(uniq.get("status"))) CNT[4]++;
        else if ("NOT_UNIQUE".equals(uniq.get("status"))) CNT[5]++;
        judgePort(v, row, uniq, p, srx, srz);
        return row;
    }

    /**
     * 跨区端口的契约判定：<b>只有</b>「已消费 + 收发候选唯一 + 有 gap」才可判。
     *
     * <p>其余情形（未消费 / 拒收 / NOT_UNIQUE / 无 gap）属于契约未覆盖，
     * 只计入 {@code rejectedOrUnconsumed} / {@code notUniquePorts}，<b>不</b>自动判失败。</p>
     */
    static void judgePort(View v, Map<String, Object> row, Map<String, Object> uniq,
                          BoundaryPort p, int srx, int srz) {
        boolean consumed = Boolean.TRUE.equals(row.get("consumed"));
        boolean unique = "UNIQUE".equals(uniq.get("status"));
        row.put("contractChecked", false);
        row.put("contractOk", null);
        if (!consumed || !unique || !(uniq.get("gap") instanceof Double gap)) return;
        row.put("contractChecked", true);
        PORT_CNT[0]++;
        boolean ok = Math.abs(gap) <= PORT_GAP_TOL;
        row.put("contractOk", ok);
        if (ok) return;
        contractFailures++;
        PORT_CNT[1]++;
        CONTRACT_VIOLATIONS.add("[port] recv(" + v.rx + "," + v.rz + ") <- send(" + srx + "," + srz + ")"
                + " anchor=(" + p.anchorX() + "," + p.anchorZ() + ") next=(" + p.nextX() + "," + p.nextZ() + ")"
                + " gap=" + fmt(gap) + " |gap|>" + PORT_GAP_TOL);
    }

    static Map<String, Object> acceptance(View v, int anchorCell, int nextCell) {
        Map<String, Object> m = new LinkedHashMap<>();
        int chain = -1;
        for (int i = 0; i < v.traces.size(); i++) {
            int[] c = v.traces.get(i).cell;
            if (c.length >= 2 && c[0] == anchorCell && c[1] == nextCell) { chain = i; break; }
        }
        m.put("incomingChainTrace", chain);
        List<Map<String, Object>> ev = new ArrayList<>();
        boolean merged = false;
        if (v.merge != null) {
            for (DeterministicSkeleton.MergeEvidence e : v.merge) {
                if (e.anchor() != anchorCell) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("resolution", e.resolution());
                r.put("next", e.next());
                r.put("resolved", e.resolved());
                r.put("nextEqualsPortNext", e.next() == nextCell);
                ev.add(r);
                if (e.resolved() && e.next() == nextCell) merged = true;
            }
        }
        m.put("mergeEvidence", ev);
        m.put("mode", chain >= 0 ? "INCOMING_CHAIN" : merged ? "MERGED_BASE_SUCCESSOR" : "NOT_CONSUMED");
        return m;
    }

    static List<Map<String, Object>> anchorCandidates(View v, int ax, int az) {
        List<Map<String, Object>> list = new ArrayList<>();
        int cell = cellOf(v, ax, az);
        if (cell < 0) return list;
        for (int i = 0; i < v.traces.size(); i++) {
            int[] c = v.traces.get(i).cell;
            int node = -1;
            for (int k = 0; k < c.length; k++) if (c[k] == cell) { node = k; break; }
            if (node < 0) continue;
            DeterministicSkeleton.Trace t = v.traces.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceIndex", i);
            row.put("cellNodeIndex", node);
            row.put("end", String.valueOf(t.end));
            row.put("fromOutlet", t.fromOutlet);
            row.put("projection", i < v.shapes.size() ? project(v, i, ax, az) : null);
            list.add(row);
        }
        return list;
    }

    static Map<String, Object> uniqueGap(List<Map<String, Object>> recv, List<Map<String, Object>> send,
                                         boolean consumed) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> pairs = new ArrayList<>();
        if (recv != null && send != null) {
            for (Map<String, Object> rc : recv) {
                for (Map<String, Object> sc : send) {
                    Double rh = halfOf(rc), sh = halfOf(sc);
                    Map<String, Object> pr = new LinkedHashMap<>();
                    pr.put("receiverTrace", rc.get("traceIndex"));
                    pr.put("senderTrace", sc.get("traceIndex"));
                    pr.put("receiverHalf", rh);
                    pr.put("senderHalf", sh);
                    pr.put("gap", rh == null || sh == null ? null : sh - rh);
                    pairs.add(pr);
                }
            }
        }
        boolean unique = consumed && recv != null && send != null
                && recv.size() == 1 && send.size() == 1;
        m.put("pairs", pairs);
        m.put("receiverCandidates", recv == null ? -1 : recv.size());
        m.put("senderCandidates", send == null ? -1 : send.size());
        m.put("status", unique ? "UNIQUE" : consumed ? "NOT_UNIQUE" : "NOT_CONSUMED");
        m.put("gap", unique ? pairs.get(0).get("gap") : null);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Double halfOf(Map<String, Object> cand) {
        Object p = cand.get("projection");
        if (!(p instanceof Map<?, ?>)) return null;
        Object h = ((Map<String, Object>) p).get("half");
        return h instanceof Double d ? d : null;
    }

    static boolean wireRejected(View v, BoundaryPort p) {
        List<String> rejects = prov.wireAssemblyRejectsForProbe(v.rx, v.rz);
        String text = p.toString();
        for (String s : rejects) if (s.contains(text)) return true;
        return false;
    }

    static boolean onGrid(View v, int x, int z) {
        if (v.n <= 0 || v.step <= 0) return false;
        int i = Math.floorDiv(x - v.ox, v.step), j = Math.floorDiv(z - v.oz, v.step);
        return i >= 0 && j >= 0 && i < v.n && j < v.n
                && v.ox + (long) i * v.step == x && v.oz + (long) j * v.step == z;
    }

    static int cellOf(View v, int x, int z) {
        if (v.n <= 0 || v.step <= 0) return -1;
        int i = Math.floorDiv(x - v.ox, v.step), j = Math.floorDiv(z - v.oz, v.step);
        if (i < 0 || j < 0 || i >= v.n || j >= v.n) return -1;
        return i * v.n + j;
    }

    // ---------------- 汇总 / 输出 ----------------

    static Map<String, Object> globalSummary(int regionCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("regions", regionCount);
        m.put("collectedRegions", probed);
        m.put("neighborViewsLoaded", Math.max(0, VIEWS.size() - probed));
        m.put("nodeJumps", GLOBAL.nodeStats());
        m.put("perBlockJumps", GLOBAL.blockStats());
        m.put("joinConnected", CNT[0]);
        m.put("joinMeasuredGaps", CNT[1]);
        m.put("joinGapAbsStats", JOIN_GAPS.stats());
        m.put("joinSerial", JOIN_CNT[0]);
        m.put("joinConfluence", JOIN_CNT[2]);
        m.put("joinVerdicts", verdictCounts((int) JOIN_CNT[3], (int) JOIN_CNT[4],
                (int) JOIN_CNT[5], (int) JOIN_CNT[6]));
        m.put("limitedShortening", JOIN_CNT[4]);
        m.put("allGreen", allGreen());
        m.put("serialGapAbsStats", SERIAL_GAPS.stats());
        m.put("serialGapViolations", JOIN_CNT[1]);
        m.put("confluenceShortfallStats", CONFLUENCE_SHORTFALLS.stats());
        m.put("confluenceViolations", JOIN_CNT[6]);
        m.put("joinNotCollected", JOIN_CNT[7]);
        m.put("incomingPorts", CNT[2]);
        m.put("consumedPorts", CNT[3]);
        m.put("uniqueGapPorts", CNT[4]);
        m.put("notUniquePorts", CNT[5]);
        m.put("portGapAbsStats", PORT_GAPS.stats());
        m.put("rejectedOrUnconsumed", CNT[6]);
        m.put("offGridPorts", CNT[7]);
        m.put("widthLimitNodes", LIMIT_NODES);
        m.put("widthLimitCappedNodes", LIMIT_CAPPED_NODES);
        m.put("widthLimitCappedExcessStats", LIMIT_CAPPED_EXCESS.stats());
        m.put("widthLimitCheckedNodes", LIMIT_CHK[0]);
        m.put("widthLimitOverLimitNodes", LIMIT_CHK[1]);
        m.put("widthLimitStrictNodes", LIMIT_CHK[2]);
        m.put("widthLimitOverLimitExcessStats", LIMIT_OVER.stats());
        m.put("widthLimitViolationSamples", List.copyOf(LIMIT_VIOLATION_SAMPLES));
        m.put("lakeInterfaces", IFACE_TOTAL);
        m.put("lakeInterfacesApplied", IFACE_APPLIED);
        m.put("lakeInterfaceStatus", new LinkedHashMap<>(IFACE_STATUS));
        m.put("lakeInterfaceTransverseStats", IFACE_TRANSVERSE.stats());
        m.put("lakeInterfaceMaxHalfStats", IFACE_MAX_HALF.stats());
        m.put("lakeInterfaceHalfChecked", IFACE_CHK[0]);
        m.put("lakeInterfaceHalfOverMaxHalf", IFACE_CHK[1]);
        m.put("lakeInterfaceHalfOverageStats", IFACE_HALF_OVER.stats());
        m.put("portGapContractChecked", PORT_CNT[0]);
        m.put("portGapViolations", PORT_CNT[1]);
        m.put("portTolerance", PORT_GAP_TOL);
        m.put("contractFailures", contractFailures);
        m.put("contractViolations", List.copyOf(CONTRACT_VIOLATIONS));
        m.put("knownExceptions", List.copyOf(LIMIT_EXCEPTIONS));
        m.put("exitPolicy", "0=采集完成且契约通过（仍非全周期验收）；1=契约违约(contractFailures>0)；"
                + "2=采集失败(collectionFailures>0：证据长度不一致/CONNECTED 无实测值)；"
                + "3=FATAL(反射或拓扑抛异常)");
        m.put("notAFailure", "未消费/被拒收端口、NOT_UNIQUE 端口、未知湖身份、真汇流本身不等宽；"
                + "真汇流只在接收方比上游更细时违约（河头线性段【不】豁免）");
        m.put("mustFail", "① 串联 |gap|>1e-9；② 真汇流 receiverHalf<upHalf；"
                + "③ 已消费且唯一的跨区端口 |gap|>1e-9（无 1 格容差）；"
                + "④ 节点 half>widthLimits；⑤ 接口 half>maxHalf");
        m.put("notCoveredHere", "落块层 bank jitter cap 不在本探针口径内（由 WidthCoverageProbe 另测）；"
                + "本探针不含湿列/水深/断面");
        m.put("greenPolicy", "allGreen 只在 采集失败=0 且 违约=0 且 limitedShortening=0 时为 true；"
                + "有 LIMIT_EXCEPTION 时不得声称总体全绿（待上限在接点两侧统一后复测）");
        m.put("aggregation", "全局统计只含主动采集的 region；neighborViewsLoaded 是边界顺带加载的邻区");
        m.put("caveat", "段宽曲线一手证据；不是湿列/水深/流量，也不构成全周期验收。");
        return m;
    }

    /**
     * 是否可声称「总体全绿」。
     *
     * <p>三个条件缺一不可：无采集失败、无契约违约、<b>无</b> {@code LIMIT_EXCEPTION}。
     * 最后一条是本轮明确要求：上限把接收方夹细虽属"已知设计后果"而单独列出，
     * 但它确实让下游承接不足，故<b>不得</b>用来支撑全绿结论。</p>
     */
    static boolean allGreen() {
        return fail == 0 && contractFailures == 0 && JOIN_CNT[4] == 0;
    }

    static double maxOf(DoubleBuf b) {
        Object v = b.stats().get("max");
        return v instanceof Double d ? d : 0.0;
    }

    /**
     * 写出 summary 并返回退出码。
     *
     * <p>三档分离，避免"采集成功被当成验收通过"：
     * {@code 2 = 采集失败}（{@code collectionFailures > 0}，包括证据缺失/长度不一致）、
     * {@code 1 = 契约违约}（{@code contractFailures > 0}）、{@code 0 = 采集完成且契约通过}。
     * 采集失败优先上报（证据不可信时先修证据，不拿契约结论说话）。</p>
     */
    static int emit(Map<String, Object> root) throws Exception {
        int code = fail > 0 ? 2 : contractFailures > 0 ? 1 : 0;
        root.put("exitCode", code);
        root.put("collectionFailures", fail);
        root.put("contractFailures", contractFailures);
        Files.writeString(out.resolve("summary.json"), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("violations.txt"),
                String.join(System.lineSeparator(), CONTRACT_VIOLATIONS) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("exceptions.txt"),
                String.join(System.lineSeparator(), LIMIT_EXCEPTIONS) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("[out] " + out.toAbsolutePath().resolve("summary.json"));
        for (String s : NOTES) System.out.println("# " + s);
        for (String s : CONTRACT_VIOLATIONS) System.out.println("VIOLATION " + s);
        for (String s : LIMIT_EXCEPTIONS) System.out.println("EXCEPTION " + s);
        System.out.println("# collectionFailures=" + fail + " contractFailures=" + contractFailures
                + " serialGapViolations=" + JOIN_CNT[1] + " confluenceViolations=" + JOIN_CNT[6]);
        System.out.println("# join serial=" + JOIN_CNT[0] + " confluence=" + JOIN_CNT[2]
                + " ok=" + JOIN_CNT[3] + " limitException=" + JOIN_CNT[4]
                + " headRampExplainedViolations=" + JOIN_CNT[5] + " notCollected=" + JOIN_CNT[7]);
        System.out.println("# allGreen=" + allGreen() + "（limitException>0 或任何违约/采集失败 ⇒ 不得声称全绿）");
        System.out.println("# ports checked=" + PORT_CNT[0] + " gapViolations=" + PORT_CNT[1]
                + " tol=" + PORT_GAP_TOL + "（已消费且唯一才计；无 1 格容差）");
        System.out.println("# limits checkedNodes=" + LIMIT_CHK[0] + " overLimit=" + LIMIT_CHK[1]
                + " strictLimitNodes=" + LIMIT_CHK[2]);
        System.out.println("# interfaces halfChecked=" + IFACE_CHK[0] + " halfOverMaxHalf=" + IFACE_CHK[1]);
        System.out.println("# lakeInterfaces=" + IFACE_TOTAL + " applied=" + IFACE_APPLIED
                + " widthLimitCappedNodes=" + LIMIT_CAPPED_NODES + "/" + LIMIT_NODES);
        System.out.println("# EXIT_POLICY 0=collected+contract_pass(still not full-cycle acceptance) "
                + "1=contract_violation 2=collection_failure");
        return code;
    }

    // ---------------- 反射薄层（仅静态常量 / 缓存键集） ----------------

    static int staticInt(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing static int " + cls.getSimpleName() + "." + name, e);
        }
    }

    static String staticField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return String.valueOf(f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("missing static " + cls.getSimpleName() + "." + name, e);
        }
    }

    /** 已求解 region 键集（rx:rz）：用于记录"本视图顺带求解了哪些区"。失败即抛，不吞。 */
    static TreeSet<String> solvedKeys() {
        Object map = sharedCache();
        if (!(map instanceof Map<?, ?> m)) {
            throw new IllegalStateException("SHARED_CACHE 类型意外: "
                    + (map == null ? "null" : map.getClass()));
        }
        TreeSet<String> s = new TreeSet<>();
        synchronized (m) {
            for (Object k : m.keySet()) s.add(keyText(k));
        }
        return s;
    }

    static Object sharedCache() {
        try {
            Field f = ModelHydrologyProvider.class.getDeclaredField("SHARED_CACHE");
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SHARED_CACHE 不可读", e);
        }
    }

    static String keyText(Object cacheKey) {
        try {
            Method rx = cacheKey.getClass().getDeclaredMethod("rx");
            Method rz = cacheKey.getClass().getDeclaredMethod("rz");
            rx.setAccessible(true);
            rz.setAccessible(true);
            return rx.invoke(cacheKey) + ":" + rz.invoke(cacheKey);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("CacheKey 不可读: " + cacheKey.getClass(), e);
        }
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static BufferedWriter open(String name, String header) throws Exception {
        BufferedWriter w = Files.newBufferedWriter(out.resolve(name), StandardCharsets.UTF_8);
        w.write(header);
        w.write("\n");
        return w;
    }

    // ---------------- 内嵌类型 ----------------

    static final class View {
        int rx, rz, ox, oz, n, step;
        boolean aligned;
        int unmatchedSeg, unmatchedNodes, baseOffsetMisses, nodeHalfMismatch;
        String shapeSha;
        List<double[][]> shapes = new ArrayList<>();
        List<double[]> arcs = new ArrayList<>();
        List<double[]> halves = new ArrayList<>();
        /** 逐节点生产宽度上限（{@code SegmentIndex.widthLimits}，缺证据时全 NaN）。 */
        List<double[]> limits = new ArrayList<>();
        List<DeterministicSkeleton.Trace> traces = List.of();
        List<DeterministicSkeleton.River> rivers;
        List<DeterministicSkeleton.MergeEvidence> merge;
        double[] seg;
        /** 原始上限数组（每段 2 端点）；{@code null} = 该 RegionData 未带证据。 */
        double[] rawLimits;
        List<LakeWidthEnvelope.Interface> ifaces = List.of();
        /** 生产后继图（{@link RiverWidthProfile#chainEdges} 构建），供 serialEdge 复用。 */
        int[] succ = new int[0];
        Map<Long, List<Integer>> segIndex = Map.of();
        List<String> newSolves = List.of();

        int segCount() { return seg == null ? 0 : seg.length / 8; }

        /** 该折线在生产后继图里的下游 trace（-1 = 无）。 */
        int succTrace(int p) {
            return p < 0 || p >= succ.length ? -1 : succ[p];
        }

        int polySegmentsExpected() {
            int c = 0;
            for (double[][] sh : shapes) c += Math.max(0, sh[0].length - 1);
            return c;
        }

        int baseIndex(int poly) {
            int base = 0;
            for (int i = 0; i < poly && i < shapes.size(); i++) base += Math.max(0, shapes.get(i)[0].length - 1);
            return base;
        }
    }

    static final class DoubleBuf {
        private double[] a = new double[256];
        private int n;

        void add(double v) {
            if (!Double.isFinite(v)) return;
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        void merge(DoubleBuf o) {
            for (int i = 0; i < o.n; i++) add(o.a[i]);
        }

        Map<String, Object> stats() {
            Map<String, Object> m = new LinkedHashMap<>();
            double[] s = Arrays.copyOf(a, n);
            Arrays.sort(s);
            m.put("n", n);
            m.put("max", n == 0 ? null : s[n - 1]);
            m.put("p50", q(s, 0.50));
            m.put("p95", q(s, 0.95));
            m.put("p99", q(s, 0.99));
            return m;
        }

        private static Object q(double[] s, double p) {
            if (s.length == 0) return null;
            double r = p * (s.length - 1);
            int lo = (int) Math.floor(r), hi = Math.min(s.length - 1, lo + 1);
            return s[lo] + (s[hi] - s[lo]) * (r - lo);
        }
    }

    static final class Stats {
        int widen, narrow, equal, pairs, tiny;
        double tinyMax;
        final DoubleBuf node = new DoubleBuf();
        final DoubleBuf block = new DoubleBuf();

        void add(double[] arc, double[] half) {
            for (int j = 1; j < half.length; j++) {
                double a = half[j - 1], b = half[j];
                if (!Double.isFinite(a) || !Double.isFinite(b)) continue;
                double d = b - a;
                pairs++;
                if (d > EPS) widen++;
                else if (d < -EPS) narrow++;
                else equal++;
                node.add(Math.abs(d));
                double da = arc[j] - arc[j - 1];
                if (da > ARC_EPS) {
                    block.add(Math.abs(d) / da);
                } else {
                    tiny++;
                    tinyMax = Math.max(tinyMax, Math.abs(d));
                }
            }
        }

        void merge(Stats o) {
            widen += o.widen;
            narrow += o.narrow;
            equal += o.equal;
            pairs += o.pairs;
            tiny += o.tiny;
            tinyMax = Math.max(tinyMax, o.tinyMax);
            node.merge(o.node);
            block.merge(o.block);
        }

        Map<String, Object> nodeStats() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("widen", widen);
            m.put("narrow", narrow);
            m.put("equal", equal);
            m.put("pairs", pairs);
            m.put("absDeltaHalf", node.stats());
            return m;
        }

        Map<String, Object> blockStats() {
            Map<String, Object> m = block.stats();
            m.put("minArcStep", ARC_EPS);
            m.put("tinyStepPairsExcluded", tiny);
            m.put("tinyStepMaxAbsDeltaHalf", tiny > 0 ? tinyMax : 0.0);
            return m;
        }
    }
}
