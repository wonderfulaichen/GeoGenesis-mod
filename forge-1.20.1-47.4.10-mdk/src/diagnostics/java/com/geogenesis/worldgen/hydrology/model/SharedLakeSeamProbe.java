package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TypeLandShape;
import com.geogenesis.worldgen.terrain.SeaBedDetail;
import com.geogenesis.worldgen.terrain.CoastlineField;
import com.geogenesis.worldgen.terrain.LandFeatures;
import com.geogenesis.worldgen.terrain.OceanFeatures;
import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TectonicDeformation;
import com.geogenesis.worldgen.terrain.TypeNoiseProvider;
import com.geogenesis.worldgen.terrain.TerrainClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <h1>跨缝共享湖域判据（canonical API；同包直调，无反射 shim）</h1>
 *
 * <p><b>被测 API</b>（{@code com.geogenesis.worldgen.hydrology.model}，探针同包 ⇒ 直调 package-private）：
 * {@code SharedLakeDomain.canonical/keysForBox/candidates/geom/baseHalf/maxHalf/screened/PLANE_TOL/ABS_MAX_HALF}；
 * {@code ModelHydrologyProvider.sharedLakeDecision(x,z)} / {@code (x,z,List)} / {@code domainForProbe} /
 * {@code clearSharedCaches} / {@code sharedDomainCacheSize|Cap|ChunkWarn} / {@code sharedActive} /
 * {@code setProbeSharedOff}；{@code HierarchicalHydrology.Diag.SHARED_*} 计数器。
 * <b>旧 4 参预算 API 与 {@code CHUNK_CAP} 已删除</b> ⇒ 本探针不再引用（CHUNK 现为 {@code _WARN}，仅告警非门）。</p>
 *
 * <p><b>★ 当前 main 链</b>（{@code HIER && SKELETON_TRACE && CROSS_PASS}）的生产单元是
 * {@code WorldDrainage} 的<b>世界坐标 D8 边</b>：{@code worldForProbe(rx,rz)} 给出本区实际边，
 * {@code reachesForProbe} 每条 reach = 该边<b>两个窗口格索引端点</b>（不再是旧 trace 折线）。
 * ⇒ 因此 {@code windowBuild}/{@code windowIncoming} 重建<b>不再是生产 oracle</b>
 * （旧写法把它当权威 ⇒ 独立 review 指出的<b>假 FAIL</b>）。
 * 本探针三个入口（shape-audit / trace-loops / rolling）现按 {@code worldForProbe(rx,rz) == null} 分流：
 * <b>非 null 走世界链实际边判据；null 则保留旧 window 判据逐字不变</b>。
 * 世界链判据全量读回实际结果，并用<b>独立重建</b>的 canonical routing
 * （{@code WorldBasinRouting}/{@code WorldBasinFlow}，同地形同参数）复核
 * 边身份 / 后继 / 无重复 / 无环 / 盆内蓄水上坡 / 盆 identity 与 lip / 必要 Water；
 * raw D8 后继只作<b>参考</b>（生产 {@code baseNext} 现在是 crest-aware 的统一整数列物理边）。
 * {@code BASIN_PENDING} 只如实记待决，<b>不当生命周期完成、不制造湖终点</b>。rolling 的「原源仍被发布」
 * 改为如实记 {@code originAdmitted}（<b>不</b>再强制存在，也不改点坐标）。</p>
 *
 * <p><b>被复现缺陷</b>（{@code build/hierarchical-evaluation/scratch/FINDINGS.md}）：seed 9139912035078620160，
 * 同一湖盆在 region 缝两侧被分别裁决 —— {@code (-513,-250)} 判湖/176.89，{@code (-512,-250)} 判干/175.31，
 * 两侧床面相同 ⇒ 跨区权威不一致。</p>
 *
 * <p><b>判据</b>：①合成 canonical（闭合 bbox ⇒ key；超 {link SharedLakeDomain#maxHalf} ⇒ null；几何自洽）；
 * ②复现现场两侧【湖 + 平面逐位一致】；③<b>整湖湿/干环过渡</b>（缝带 ±40 逐块扫描：湿区连通、无被缝切开的
 * 干洞；非止缝 2px）；④真实多盆地门 ≥2 受控域 + 另一例 {@code (512,-2336)}；⑤冷/热/逆序/并发列逐位一致；
 * ⑥细解 Δ 只等于显式请求的 region 数（不多解八邻）；⑦计数：{@code maxHalf≤512}、双档试解、域缓存 ≤ 上限；
 * ⑧负例：无跨缝 ⇒ 粗筛选即止（细解 0）、{@code REFUSED_TRUNCATED} 覆盖点一律不受控。</p>
 *
 * <p><b>★ 世界链判据（⑨，仅 {@code worldForProbe != null} 时取代旧 ①③ 型判据）</b>：
 * ★★ <b>盆内蓄水路由已获批准</b>（DemandFlow 方案 + 统一整数列物理边规则；生产
 * {@code WorldBasinRouting.baseNext} 现为 crest-aware）⇒ 原先的「raw D8 严格下降」复核
 * <b>已不适用</b>。现行判据为：
 * ① 实际发布的边 与 reach <b>一一对应</b>（按 {@code (cellA,cellB)} 多重集配对，不依赖发布顺序）；
 * ② 每条发布边等于<b>真实 canonical routing</b> 的 {@code WorldBasinFlow.geometricNext}
 *   （独立 WorldBasinRouting/WorldBasinFlow 实例 + 同一地形重算）；raw D8 后继只作<b>参考</b>；
 * ③ from 端点无重复、发布图无环；
 * ④ <b>盆内允许蓄水路径上坡</b>，但每一处上坡都必须由某个<b>已发布</b>成员盆的盆内路由/lip 解释、
 *   且该跳的<b>整数列 crest</b>（沿 a→b 全列、同 STEP 采样，{@code worldCrest}）≤ 该盆 {@code sill}
 *   —— 不是只看两端 bed；无法解释的上坡（{@code unexplainedRising}）才是缺陷；
 * ⑤ 成员盆拥有<b>唯一 identity</b> 与<b>唯一 lip</b>（inner 在成员内、outer 在外、crest = sill、
 *   inner→outer 即该盆路由首步）；同 identity 不同形态 ⇒ {@code identityConflicts} 缺陷；
 * ⑥ <b>水量覆盖 {@code WorldBasinFlow.Region.water()} 的每一格</b>（不只发布边的 {@code from}）：
 *   每格必须 known（{@code UNKNOWN} = 下界缺失 ⇒ <b>计缺陷，绝不跳过</b>）；门限按<b>语义</b>分档：
 *   {@code AT_LEAST} 是下界，而本次查询需求就是 {@code threshold} ⇒ <b>必须 {@code reaches(threshold)}</b>；
 *   {@code EXACT} 是实测值，<b>低于 threshold 是合法的干格/低流量结果，只统计不算缺陷</b>。
 *   被判为湖者的盆身份必须由该坐标上<b>新鲜的独立 {@code routing.at()} 核对</b>（同 identity 且同形），
 *   <b>不</b>用 {@code Water} 自带的盆当 oracle、<b>不</b>以"曾被发布边触达"当准入（那会错杀未触达盆）；
 *   核对通过的盆<b>必要登记</b>进成员集以接受 lip/identity 自检。
 *   另要求每条发布边的 {@code from} 在 {@code water()} 里有条目<b>且达 threshold（EXACT 也一样）</b>。
 *   ★ 这是生产输出的<b>自洽自检</b>，不是独立水量 oracle：它不证水量守恒，也不证水位正确；
 *   「非 EXACT 不得报有限 discharge()」这条今天按构造恒真 ⇒ 只统计 {@code waterContractLeak}，
 *   <b>不</b>单独占 PASS 位；
 * ⑦<b>负控</b>（shape-audit）：①删掉一条实际边 ⇒ 池配对必须失败；②把一条实际边的终点改到另一格点邻居
 *   ⇒ 一一对应或 canonical 后继判据必须失败（证明判据非空真，而不是自证）；
 *   另有<b>纯合成</b>的水量口径负控（{@code worldWaterGaugeSynthetic}）：EXACT 低 T 应过、
 *   UNKNOWN 全量应失败、非 EXACT 低 T 失败、发布低 T 失败、湖身份不符失败。
 * 待决盆地如实为 {@code BASIN_PENDING} 且在 core 内；<b>不</b>把待决盆地当生命周期完成，
 * <b>不</b>据此制造湖终点；rolling 起点只如实记 {@code originAdmitted}。
 * <p><b>⚠ 本探针不声称水面梯度已验证</b>：它只证「上坡发生在盆内蓄水/溢出路由且该跳整数列 crest ≤ sill」，
 * 不证水面单调下降、不证全局唯一水位、不证任何水域面积。</p>
 *
 * <p><b>NOT VERIFIED / 残余</b>：①只证缝两侧一致与缝带连通，<b>不</b>证全局唯一水位、<b>不</b>声称整湖已修
 * （受控范围只覆盖 canonical 跨缝盆地足迹，缝可能只是【移到】足迹边缘）；②未做全图像素对照/实机；③无 Forge
 * ⇒ 配置取用户 toml <b>工作副本</b>（原件只读）；④探针只读缓存且收尾清空，不改生产产出。
 *
 * <p>用法：{@code gradlew runSharedLakeSeamProbe [-PprobeArgs="&lt;seed&gt; [synth|repro|basin|det|fine|counts|neg]"]}</p>
 */
public final class SharedLakeSeamProbe {

    static final long DEF_SEED = 9139912035078620160L;
    /** 同盆地跨缝水位容差（块）。 */
    static final double TOL_LEVEL = 0.75;
    /** 复现现场 + 另一多盆地例。 */
    static final int RX = -513, RZ = -250, ALT_X = 512, ALT_Z = -2336;
    /** 缝带扫描半宽（块）：证明过渡不止缝上 2px。 */
    static final int BAND = 40;
    static final Set<String> SECTIONS =
            Set.of("synth", "repro", "basin", "det", "fine", "counts", "neg", "cost-on", "cost-off", "lazy", "river", "river-candidates", "contract", "contract-baseline", "boundary", "boundary-local", "local-synth", "closure", "local-view", "outer", "outer-view", "shore", "shore-view", "cell-contract", "cell-view", "block-synth", "block-boundary", "block-view", "transfer-synth", "transfer-view", "summary-synth", "summary-view", "direction-synth", "direction-view", "sink-synth", "sink-view", "channel-origin", "common-acceptance", "erosion-blend", "source-synth", "source-directory", "owner-sources", "owner-source-acceptance", "claims-synth", "owner-claims", "owner-claims-acceptance", "trace-loops", "window-refresh", "window-compare", "window-synth", "window-acceptance", "window-cause", "window-successor", "window-gradient", "storage-replay", "shape-audit", "world-contract", "world-contract-synth", "window-handoff", "rolling-synth", "rolling-window", "basin-rolling-synth", "basin-rolling");
    static final Path OUT = Path.of("build", "shared-lake-seam");

    static int failures;
    static long runSeed;
    static final StringBuilder LOG = new StringBuilder();
    static TerrainParams TP;
    static CellGenerator GEN;
    static ModelHydrologyProvider PROV;

    private SharedLakeSeamProbe() { }

    public static void main(String[] args) throws Exception {
        // ★ 开关必须在触碰 ModelHydrologyProvider 之前设置（SHARED_LAKE 是 static final）。
        System.setProperty("geogenesis.hierarchicalHydrology", "true");
        System.setProperty("geogenesis.skeletonTrace", "true");
        System.setProperty("geogenesis.hierStrictContract", "false");
        System.setProperty("geogenesis.sharedLakeDomains", "true");
        long seed = args.length > 0 ? Long.parseLong(args[0].trim()) : DEF_SEED;
        runSeed = seed;
        Set<String> want = new LinkedHashSet<>();
        if (args.length > 1) for (String s : args[1].split(",")) if (!s.isBlank()) want.add(s.trim());
        boolean all = want.isEmpty();
        if (want.contains("contract") || want.contains("contract-baseline")
                || want.contains("boundary") || want.contains("boundary-local")
                || want.contains("outer") || want.contains("outer-view")
                || want.contains("shore") || want.contains("shore-view") || want.contains("cell-view")
                || want.contains("block-boundary") || want.contains("block-view") || want.contains("direction-view") || want.contains("sink-view")
                || want.contains("rolling-window"))
            System.setProperty("geogenesis.drainageAudit", "true");

        chk(loadCfg(), "用户 toml 复制为工作副本并 cfg.load()（原件只读、绝不写回）",
                "原件: " + Path.of("run", "config", "geogenesis-common.toml").toAbsolutePath());
        TP = params();
        GEN = newGen(TP, seed);
        PROV = new ModelHydrologyProvider(GEN, seed);
        PROV.clear();
        log("=== SharedLakeSeamProbe ===");
        log("seed=" + seed + " hier=" + ModelHydrologyProvider.hierarchical()
                + String.format(" hs=%.3f sea=%d baseHalf=%d maxHalf=%d abstMax=%d sections=%s",
                        TP.horizontalScale(), TP.seaLevel(), SharedLakeDomain.baseHalf(hp()),
                        SharedLakeDomain.maxHalf(hp()), SharedLakeDomain.ABS_MAX_HALF,
                        all ? "all" : want));
        chk(ModelHydrologyProvider.sharedActive(), "开关：hierarchicalHydrology + sharedLakeDomains ⇒ 共享域参与裁决",
                "未开启 ⇒ 后续判据必然空转");
        Set<String> unknown = new LinkedHashSet<>(want);
        unknown.removeAll(SECTIONS);
        chk(unknown.isEmpty(), "段名合法（未知段 ⇒ FAIL，不因『没跑』而静默 PASS）",
                "未知 " + unknown + "（合法: " + SECTIONS + "）");

        if (want.contains("cost-on") || want.contains("cost-off")) {
            secCost(seed, want.contains("cost-off"), args.length > 2 ? Integer.parseInt(args[2]) : -54,
                    args.length > 3 ? Integer.parseInt(args[3]) : -37);
        }
        if (all || want.contains("synth")) secSynth();
        if (all || want.contains("repro")) secRepro();
        if (all || want.contains("basin")) secBasin();
        if (all || want.contains("det")) secDet(seed);
        if (all || want.contains("fine")) secFine();
        if (all || want.contains("counts")) secCounts();
        if (all || want.contains("neg")) secNeg();
        if (all || want.contains("lazy")) secLazy();
        if (want.contains("river")) secRiver();
        if (want.contains("river-candidates")) secRiverCandidates();
        if (want.contains("contract") || want.contains("contract-baseline"))
            secProductionContract(want.contains("contract-baseline"));
        if (want.contains("boundary")) secBoundary();
        if (want.contains("boundary-local")) secBoundaryLocal();
        if (want.contains("local-synth")) localSynthetic();
        if (want.contains("closure")) secClosure();
        if (want.contains("local-view")) secLocalView(seed);
        if (want.contains("outer") || want.contains("outer-view")) secOuter(want.contains("outer-view"));
        if (want.contains("shore") || want.contains("shore-view")) secShore(want.contains("shore-view"));
        if (want.contains("cell-contract") || want.contains("cell-view")) cellContractSynthetic();
        if (want.contains("cell-view")) secCellView();
        if (want.contains("block-synth")) blockSynthetic();
        if (want.contains("block-boundary") || want.contains("block-view")) secBlockBoundary(want.contains("block-view"));
        if (want.contains("transfer-synth")) transferSynthetic();
        if (want.contains("transfer-view")) secTransferView();
        if (want.contains("summary-synth")) summarySynthetic();
        if (want.contains("summary-view")) secSummaryView();
        if (want.contains("direction-synth")) directionSynthetic();
        if (want.contains("direction-view")) secDirectionView();
        if (want.contains("sink-synth")) sinkSynthetic();
        if (want.contains("sink-view")) secSinkView();
        if (want.contains("channel-origin")) secChannelOrigin();
        if (want.contains("common-acceptance")) {
            System.setProperty("geogenesis.commonNodeAcceptance", "true");
            secChannelOrigin();
        }
        if (want.contains("erosion-blend")) secErosionBlend(seed);
        if (want.contains("rolling-synth")) rollingSynthetic();
        if (want.contains("rolling-window")) secRollingWindow(seed);
        if (want.contains("basin-rolling-synth")) basinRollingSynthetic();
        if (want.contains("basin-rolling")) secBasinRolling(seed);
        if (want.contains("source-synth")) sourceDirectorySynthetic();
        if (want.contains("source-directory")) secSourceDirectory();
        if (want.contains("owner-sources") || want.contains("owner-source-acceptance"))
            secOwnerSources(seed, want.contains("owner-source-acceptance"));
        if (want.contains("trace-loops")) secTraceLoops();
        if (want.contains("shape-audit")) secShapeAudit();
        if (want.contains("world-contract-synth")) worldContractSynthetic();
        if (want.contains("world-contract")) secWorldContract(seed);
        if (want.contains("window-synth")) { windowSynthetic(); weightContinuityRegression(); }
        if (want.contains("storage-replay")) replayStorage(Path.of(args[2]));
        if (want.contains("window-handoff")) System.setProperty("geogenesis.windowHandoffProbe", "true");
        if (want.contains("window-refresh") || want.contains("window-compare") || want.contains("window-acceptance") || want.contains("window-cause") || want.contains("window-successor") || want.contains("window-gradient") || want.contains("window-handoff")) {
            if (want.contains("window-gradient")) System.setProperty("geogenesis.windowGradientProbe", "true");
            if (want.contains("window-successor") || want.contains("window-gradient")) System.setProperty("geogenesis.windowSuccessorProbe", "true");
            if (want.contains("window-cause")) System.setProperty("geogenesis.windowCauseProbe", "true");
            secWindowExperiment(seed, !want.contains("window-refresh"), want.contains("window-acceptance"));
        }
        if (want.contains("claims-synth")) claimsSynthetic();
        if (want.contains("owner-claims") || want.contains("owner-claims-acceptance"))
            secOwnerClaims(want.contains("owner-claims-acceptance"));
        notVerified();
        log("\n" + HierarchicalHydrology.Diag.sharedReport());
        emit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- 配置 / 引擎 / 小工具 ---------------------------------------------------
    private static boolean loadCfg() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) { log("[cfg] " + src + " 不存在"); return false; }
        try {
            Files.createDirectories(OUT);
            Path work = OUT.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return true;
        } catch (Throwable e) { log("[cfg] 工作副本加载失败: " + e); return false; }
    }

    private static TerrainParams params() {
        try { return GeoGenesisConfig.INSTANCE.buildParams(); }
        catch (Throwable e) {
            log("[cfg] buildParams 失败 ⇒ defaults 兜底并计 FAIL: " + e);
            failures++;
            return TerrainParams.defaults();
        }
    }

    private static HierarchicalHydrology.Params hp() { return ModelHydrologyProvider.hierParams(); }

    private static CellGenerator newGen(TerrainParams tp, long seed) {
        CellGenerator g = new CellGenerator(tp, tp.minY(), tp.maxY());
        g.seed(seed);
        return g;
    }

    private static Cell[] sampleChunk(CellGenerator gen, int cx, int cz) {
        double hs = Math.max(0.01, gen.params().horizontalScale());
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = gen.sample((cx * 16 + lx) / hs, (cz * 16 + lz) / hs);
            }
        }
        return cells;
    }

    private static double bed(int bx, int bz) {
        double hs = Math.max(0.01, GEN.params().horizontalScale());
        return GEN.erodedHeight(bx / hs, bz / hs);
    }

    private static SharedLakeDomain.Decision dc(int x, int z) {
        return PROV.sharedLakeDecision(x, z);
    }

    private static String snap(SharedLakeDomain.Decision d) {
        return "ctl=" + d.controlled() + ";lake=" + d.lake() + ";surfBits="
                + Double.doubleToRawLongBits(d.surface()) + ";key="
                + (d.key() == null ? "-" : d.key().label()) + ";basin=" + d.basinId()
                + ";st=" + d.status();
    }

    // ==================== [synth] canonical / 几何 / 上限 ====================
    private static void secSynth() {
        log("\n=== [synth] canonical（闭合 / 超限）+ 几何自洽 + 半边长上限 ===");
        var p = hp();
        chk(SharedLakeDomain.baseHalf(p) == p.fineRadius(), "baseHalf = 生产窗半径（" + p.fineRadius() + "）", "不等");
        chk(SharedLakeDomain.maxHalf(p) == Math.min(p.tract(), SharedLakeDomain.ABS_MAX_HALF),
                "maxHalf = min(tract, ABS_MAX_HALF)（" + SharedLakeDomain.maxHalf(p) + "）", "不等");
        chk(SharedLakeDomain.maxHalf(p) <= SharedLakeDomain.ABS_MAX_HALF, "maxHalf ≤ 512（硬上限）", "超上限");

        // 合成闭合 bbox（跨 x=0 缝、居中）⇒ 应得 canonical key；再放大到装不下 ⇒ null。
        int[] small = {-40, 40, -30, 30};
        SharedLakeDomain.Key k = SharedLakeDomain.canonical(small[0], small[1], small[2], small[3], 0, 0, p, 4);
        chk(k != null && k.axis() == 0 && k.boundary() == 0, "合成闭合 bbox（跨 x=0）⇒ canonical 竖缝键", "得 " + k);
        if (k != null) {
            SharedLakeDomain.Geom g = SharedLakeDomain.geom(k, p, 4);
            chk(g.n() == 2 * g.halfSpan() / g.step() + 1 && g.seamCell() >= 0 && g.seamCell() < g.n(),
                    "几何自洽：n = 2·half/step + 1 且缝格在窗内（n=" + g.n() + " seamCell=" + g.seamCell() + "）",
                    "几何违规");
            chk(g.halfSpan() == SharedLakeDomain.baseHalf(p),
                    "默认 geom 半边长 = baseHalf（" + g.halfSpan() + "）", "≠ baseHalf");
        }
        int big = SharedLakeDomain.maxHalf(p) + 64;
        chk(SharedLakeDomain.canonical(-big, big, -big, big, 0, 0, p, 4) == null,
                "超 maxHalf 的 bbox ⇒ canonical = null（交由调用方记 REFUSED_TOO_BIG，不移墙）", "非 null");
        // keysForBox 覆盖：盒点必落在其候选窗内
        List<SharedLakeDomain.Key> ks = SharedLakeDomain.keysForBox(RX, RZ, RX + 15, RZ + 15, p);
        int cover = 0;
        for (SharedLakeDomain.Key kk : ks) {
            SharedLakeDomain.Geom g = SharedLakeDomain.geom(kk, p, 4, SharedLakeDomain.maxHalf(p));
            if (RX >= g.originX() && RZ >= g.originZ()
                    && RX + 15 <= g.originX() + g.n() * g.step() - 1
                    && RZ + 15 <= g.originZ() + g.n() * g.step() - 1) cover++;
        }
        chk(!ks.isEmpty() && cover > 0, "keysForBox 非空且盒被某候选窗包含（候选=" + ks.size() + " 覆盖=" + cover + "）",
                "盒未被覆盖 ⇒ 候选枚举漏");
        chk(SharedLakeDomain.screened(ks.isEmpty() ? new SharedLakeDomain.Key(0, 0, 0) : ks.get(0), p)
                        .status() == SharedLakeDomain.Status.NO_CROSSING,
                "screened() 给出廉价 NO_CROSSING 域（不建窗）", "状态非 NO_CROSSING");
        projectionCheck(p);
    }

    private static void projectionCheck(HierarchicalHydrology.Params p) {
        var key = new SharedLakeDomain.Key(0, 0, 0);
        var coarse = SharedLakeDomain.geom(key, p, 4, SharedLakeDomain.baseHalf(p));
        var fine = SharedLakeDomain.geom(key, p, 4, SharedLakeDomain.maxHalf(p));
        int[] ids = new int[coarse.n() * coarse.n()];
        java.util.Arrays.fill(ids, -1);
        int mid = coarse.n() / 2;
        ids[mid * coarse.n() + mid] = 7;
        int[] projected = SharedLakeDomain.projectBasinId(ids, coarse, fine);
        int found = 0;
        for (int id : projected) if (id == 7) found++;
        int fm = fine.n() / 2;
        chk(projected.length == fine.n() * fine.n() && found == 1
                        && projected[fm * fine.n() + fm] == 7 && projected[0] == -1,
                "粗细不同半径按世界坐标投影，细索引不再访问粗尺寸数组", "投影错误");
    }

    // ==================== [repro] 缝两侧一致 + 整湖过渡 ====================
    private static void secRepro() {
        log("\n=== [repro] (-513,-250) 两侧湖+平面一致 + 整湖湿/干环过渡（缝带 ±" + BAND + "）===");
        PROV.clear();
        int zs = Math.floorDiv(RZ, 16);
        SharedLakeDomain.Decision l = dc(RX, RZ), r = dc(RX + 1, RZ);
        log("    左 " + snap(l));
        log("    右 " + snap(r));
        chk(l.controlled() && r.controlled(), "复现前置：缝两侧均被共享域受控（否则复现被拒绝 ⇒ FAIL 保留证据）",
                "L.ctl=" + l.controlled() + " R.ctl=" + r.controlled());
        chk(l.lake() && r.lake(), "缝两侧均判湖（同一盆地同湖旗标）",
                "L.lake=" + l.lake() + " R.lake=" + r.lake());
        chk(Double.doubleToRawLongBits(l.surface()) == Double.doubleToRawLongBits(r.surface()),
                "缝两侧【水面逐位一致】（同一平面）",
                "L=" + l.surface() + " R=" + r.surface());
        chk(l.basinId() == r.basinId(), "缝两侧同粗盆地 id（" + l.basinId() + "）",
                "L=" + l.basinId() + " R=" + r.basinId());

        // ★ 整湖：缝带逐块扫描（生产列），湿区须连通（无被缝切开的干洞）——不止缝上 2px。
        Map<Long, Cell[]> cells = new HashMap<>();
        int wet = 0, minWet = Integer.MAX_VALUE, maxWet = Integer.MIN_VALUE;
        int seamSplit = 0, shoreSplit = 0;
        StringBuilder splitEv = new StringBuilder();
        for (int z = RZ - BAND; z <= RZ + BAND; z += 10) {
            int run = 0, best = 0, seenWet = 0, wetLeft = 0, wetRight = 0;
            for (int x = RX - BAND; x <= RX + BAND; x++) {
                boolean lake = colLake(cells, x, z);
                if (lake) {
                    run++; seenWet++; best = Math.max(best, run);
                    if (x <= RX) wetLeft++; else wetRight++;
                } else run = 0;
            }
            wet += seenWet;
            minWet = Math.min(minWet, best);
            maxWet = Math.max(maxWet, best);
            if (seenWet >= 3 && best < seenWet) {
                // 分段定位：干格是否【恰落在缝线】？是 ⇒ 缝上切开（硬失败）；
                // 否则只是足迹内残余（域边缘/天然干斑）⇒ 如实报告残余，不声称为整湖已修。
                boolean seamDry = !colLake(cells, RX, z) && !colLake(cells, RX + 1, z);
                if (wetLeft > 0 && wetRight > 0 && seamDry) {
                    seamSplit++;
                    if (splitEv.length() < 200) splitEv.append("[z=").append(z)
                            .append(" wetL=").append(wetLeft).append("/wetR=").append(wetRight).append("] ");
                } else {
                    shoreSplit++;
                }
            }
        }
        log("    缝带扫描 ±" + BAND + " 块（z 步 10）：湿格=" + wet
                + " 最长湿连段∈[" + minWet + "," + maxWet + "] 缝上切开行=" + seamSplit
                + " 足迹内残余分段行=" + shoreSplit + (splitEv.length() > 0 ? " " + splitEv : ""));
        chk(wet > 0, "缝带扫描非空转（确有湿格）", "无湿格 ⇒ 空测");
        chk(seamSplit == 0, "整湖过渡：无【缝上切开】行（缝线两侧都湿却被缝线处干格隔断）——不止缝上 2px",
                "缝上切开行=" + seamSplit + "（残余，如实报告；见 NOT VERIFIED①）");
        if (shoreSplit > 0) {
            log("    ⚠ 足迹内残余分段行=" + shoreSplit + "：非缝线处（域边缘/天然干斑）⇒ 受控只覆盖 canonical 盆地"
                    + "足迹，如实登记残余；不宽松为『整湖全 passed』。");
        }
        chk(zs == Math.floorDiv(RZ, 16), "chunk 索引自洽（z-chunk=" + zs + "）", "索引异常");
    }

    private static boolean colLake(Map<Long, Cell[]> cells, int bx, int bz) {
        int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
        Cell[] orig = cells.computeIfAbsent((((long) cx) << 32) ^ (cz & 0xffffffffL),
                k -> sampleChunk(GEN, cx, cz));
        for (HydrologyBlockCarvedColumn c : PROV.columnsFor(cx, cz, orig)) {
            if (c.blockX() == bx && c.blockZ() == bz) return c.lakePlan();
        }
        return false;
    }

    // ==================== [basin] 真实多盆地门 + 另一例 ====================
    private static void secBasin() {
        log("\n=== [basin] 真实多盆地门（≥2 受控域）+ 另一例 (512,-2336) ===");
        PROV.clear();
        Set<String> controlled = new LinkedHashSet<>();
        for (SharedLakeDomain.Key k : SharedLakeDomain.keysForBox(RX, RZ, RX + 15, RZ + 15, hp())) {
            SharedLakeDomain.Domain d = PROV.domainForProbe(k);
            if (d == null) { chk(false, "域可直取 domainForProbe", k.label()); continue; }
            if (!d.basinPlane().isEmpty()) controlled.add(k.label() + "->" + d.basinPlane());
            log("    域 " + d.label());
        }
        log("    受控域数=" + controlled.size() + " " + controlled);
        chk(controlled.size() >= 2, "真实多盆地门：≥2 个受控域（多盆地一致性确有意义）",
                "受控域=" + controlled.size() + " ⇒ 门禁空转");

        SharedLakeDomain.Decision a = dc(ALT_X, ALT_Z);
        log("    另一例 (" + ALT_X + "," + ALT_Z + ") " + snap(a));
        chk(a.controlled() && a.lake(), "另一例 (512,-2336) 由共享域受控判湖（第二盆地证据）",
                "得 " + snap(a));
    }

    // ==================== [det] 冷 / 热 / 逆序 / 并发列逐位一致 ====================
    private static void secDet(long seed) {
        log("\n=== [det] 冷 / 热 / 逆序 / 并发 列指纹逐位一致 ===");
        int cx = Math.floorDiv(RX, 16), cz = Math.floorDiv(RZ, 16);
        Cell[] orig = sampleChunk(GEN, cx, cz);
        PROV.clear();
        String cold = fp(PROV.columnsFor(cx, cz, orig));
        String hot = fp(PROV.columnsFor(cx, cz, orig));
        chk(cold.equals(hot), "热缓存命中 ⇒ 列指纹逐位相同", "有差异");
        PROV.clear();
        String cold2 = fp(PROV.columnsFor(cx, cz, orig));
        chk(cold.equals(cold2), "冷起重跑 ⇒ 列指纹逐位相同（纯函数；缓存只改时序）", "有差异");
        ModelHydrologyProvider other = new ModelHydrologyProvider(newGen(TP, seed), seed);
        PROV.clear();
        chk(cold.equals(fp(other.columnsFor(cx, cz, orig))),
                "另一 provider 实例（同 seed/参数）⇒ 列指纹逐位相同（跨实例缓存身份）", "有差异");

        // 逆序：同两 chunk 反序取列 ⇒ 各 chunk 指纹仍逐位相同
        int cx2 = Math.floorDiv(RX + 1, 16);
        Cell[] o2 = sampleChunk(GEN, cx2, cz);
        Map<String, String> fwd = new HashMap<>();
        fwd.put("a", fp(PROV.columnsFor(cx, cz, orig)));
        fwd.put("b", fp(PROV.columnsFor(cx2, cz, o2)));
        PROV.clear();
        String revB = fp(PROV.columnsFor(cx2, cz, o2));
        String revA = fp(PROV.columnsFor(cx, cz, orig));
        chk(fwd.get("a").equals(revA) && fwd.get("b").equals(revB),
                "逆序取列 ⇒ 各 chunk 指纹与正序逐位相同（无顺序依赖）", "有差异");

        // 并发：4 线程同 chunk（已热）⇒ 指纹相同且 0 次新增细解
        long f0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        int n = 4;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        String[] fp = new String[n];
        for (int i = 0; i < n; i++) {
            final int t = i;
            Thread th = new Thread(() -> {
                try { go.await(); fp[t] = fp(PROV.columnsFor(cx, cz, orig)); }
                catch (Throwable e) { fp[t] = "ERR:" + e; } finally { done.countDown(); }
            }, "probe-lake-" + t);
            th.setDaemon(true);
            th.start();
        }
        go.countDown();
        boolean joined = false;
        try { joined = done.await(600, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        chk(joined, "并发同 chunk 全部返回（无死锁）", "600s 超时");
        boolean same = fp[0] != null && !fp[0].startsWith("ERR:");
        for (String s : fp) if (s == null || !s.equals(fp[0])) same = false;
        chk(same, "并发各线程列指纹逐位相同（单飞身份）", "有线程不同/异常");
        chk(HierarchicalHydrology.Diag.FINE_SOLVES.get() - f0 == 0, "并发同 chunk（已热）0 次新增细解",
                "Δ=" + (HierarchicalHydrology.Diag.FINE_SOLVES.get() - f0));
    }

    private static String fp(List<HydrologyBlockCarvedColumn> cols) {
        StringBuilder sb = new StringBuilder(cols.size() * 12);
        for (HydrologyBlockCarvedColumn c : cols) {
            sb.append(c.blockX()).append(',').append(c.blockZ()).append(',')
                    .append(c.lakePlan() ? '1' : '0').append(c.fillWater() ? '1' : '0')
                    .append(Double.doubleToRawLongBits(c.waterSurfaceY())).append(',')
                    .append(Double.doubleToRawLongBits(c.carvedGroundY())).append(':');
        }
        return sb.toString();
    }

    // ==================== [fine] 细解 Δ 只 = 显式 region 数 ====================
    private static void secFine() {
        log("\n=== [fine] 细解 Δ 只 = 显式请求的 owner region 数（不多解八邻）===");
        // 三处整块分别位于 region (-2,-1) (-1,-1) (1,-5) —— 冷起逐个请求，Δ 必须恰好 +1。
        int[][] chunks = {
                {Math.floorDiv(RX, 16), Math.floorDiv(RZ, 16)},
                {Math.floorDiv(RX + 1, 16), Math.floorDiv(RZ, 16)},
                {Math.floorDiv(ALT_X, 16), Math.floorDiv(ALT_Z, 16)}};
        Set<Long> seen = new LinkedHashSet<>();
        int asked = 0;
        PROV.clear();
        for (int[] c : chunks) {
            long before = HierarchicalHydrology.Diag.FINE_SOLVES.get();
            PROV.columnsFor(c[0], c[1], sampleChunk(GEN, c[0], c[1]));
            long d = HierarchicalHydrology.Diag.FINE_SOLVES.get() - before;
            long rk = (((long) Math.floorDiv(c[0] * 16 + 8, 512)) << 32)
                    ^ (Math.floorDiv(c[1] * 16 + 8, 512) & 0xffffffffL);
            boolean first = seen.add(rk);
            asked++;
            log(String.format("    chunk(%d,%d) region=%d,%d 新解 Δ=%d（首个=%b）",
                    c[0], c[1], (int) (rk >> 32), (int) rk, d, first));
            chk(d == (first ? 1 : 0), "请求 " + asked + " 个 chunk ⇒ 细解 Δ=" + d
                            + "（新 region 恰 1 次；旧 region 0 次 ⇒ 无八邻扇出）",
                    "Δ=" + d + " first=" + first);
        }
        chk(seen.size() <= 4, "触及 region 数 ≤ 4（复现轻量：实际 " + seen.size() + "）", "超过 4 region");
    }

    // ==================== [counts] maxHalf / 双档试解 / 缓存上限 / WARN 非门 ====================
    private static void secCounts() {
        log("\n=== [counts] maxHalf≤512 / 双档试解 / 域缓存 ≤ 上限 / CHUNK_WARN 仅告警 ===");
        var p = hp();
        chk(SharedLakeDomain.maxHalf(p) <= 512 && SharedLakeDomain.maxHalf(p) == Math.min(p.tract(), 512),
                "maxHalf ≤ 512 且 = min(tract,512)（" + SharedLakeDomain.maxHalf(p) + "）", "违规");
        int cap = ModelHydrologyProvider.sharedDomainCacheCap();
        int warn = ModelHydrologyProvider.sharedDomainChunkWarn();
        log("    域缓存上限=" + cap + " CHUNK_WARN=" + warn + "（仅告警，非门）");

        PROV.clear();
        long cs0 = HierarchicalHydrology.Diag.SHARED_COARSE_SCREENS.get();
        SharedLakeDomain.Key k = null;
        for (SharedLakeDomain.Key kk : SharedLakeDomain.keysForBox(RX, RZ, RX + 15, RZ + 15, p)) {
            if (k == null) k = kk;
        }
        SharedLakeDomain.Domain d0 = PROV.domainForProbe(k);
        long tries = HierarchicalHydrology.Diag.SHARED_COARSE_SCREENS.get() - cs0;
        log("    单域粗筛选试解=" + tries + " 状态=" + (d0 == null ? "null" : d0.status()));
        chk(tries >= 1 && tries <= 2, "双档试解：单域粗筛选 ∈ [1,2]（base → maxHalf 固定顺序）", "试解=" + tries);

        // 触摸大量域 ⇒ 缓存 ≤ 上限（有界）
        PROV.clear();
        for (int x = -2048; x <= 2048; x += 256) {
            for (SharedLakeDomain.Key kk : SharedLakeDomain.keysForBox(x, RZ, x, RZ, p)) {
                PROV.domainForProbe(kk);
            }
        }
        int size = ModelHydrologyProvider.sharedDomainCacheSize();
        log("    广泛触摸后域缓存=" + size + "（上限 " + cap + "）");
        chk(cap > 0 && size <= cap, "域缓存 ≤ 上限（" + size + " ≤ " + cap + "）", "超上限");
        chk(warn > 0, "CHUNK_WARN 可读且为正（" + warn + " = 仅告警阈值，非门）", "不可读");
    }

    // ==================== [neg] 无跨缝 ⇒ 粗止；truncated ⇒ 拒绝 ====================
    private static void secNeg() {
        log("\n=== [neg] 无跨缝 ⇒ 粗筛选即止（细解 0）；truncated ⇒ 不受控 ===");
        PROV.clear();
        // 深内陆点：粗筛选应即止（该域无跨缝盆地 ⇒ 细解不增）。
        int farX = -600, farZ = 700;
        long f0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        long sc0 = HierarchicalHydrology.Diag.SHARED_SCREENED_OUT.get();
        for (SharedLakeDomain.Key k : SharedLakeDomain.keysForBox(farX, farZ, farX, farZ, hp())) {
            SharedLakeDomain.Domain d = PROV.domainForProbe(k);
            if (d != null && d.status() == SharedLakeDomain.Status.NO_CROSSING) { /* 粗止 */ }
        }
        long dFine = HierarchicalHydrology.Diag.FINE_SOLVES.get() - f0;
        SharedLakeDomain.Decision far = dc(farX, farZ);
        log("    远点 (" + farX + "," + farZ + ") 判词=" + snap(far) + " 细解 Δ=" + dFine
                + " 屏幕剔除=" + (HierarchicalHydrology.Diag.SHARED_SCREENED_OUT.get() - sc0));
        chk(far.status() == SharedLakeDomain.Status.NONE || !far.controlled(),
                "远点（无跨缝）⇒ 判词不受控（不给湖）", "得 " + snap(far));
        chk(!far.lake(), "远点 ⇒ 不得凭空出湖", "lake=true");

        // truncated ⇒ 该盆地覆盖点一律不受控
        int truncSamples = 0, violated = 0;
        long t0 = HierarchicalHydrology.Diag.SHARED_REFUSED_TRUNCATED.get();
        for (int[] q : new int[][]{{RX, RZ}, {RX + 1, RZ}, {ALT_X, ALT_Z}, {-300, -250}, {0, -512}}) {
            for (SharedLakeDomain.Key k : SharedLakeDomain.keysForBox(q[0], q[1], q[0], q[1], hp())) {
                SharedLakeDomain.Domain d = PROV.domainForProbe(k);
                if (d == null) continue;
                int b = d.basinIdAt(q[0], q[1]);
                if (b < 0 || d.basinStatus().get(b) != SharedLakeDomain.Status.REFUSED_TRUNCATED) continue;
                truncSamples++;
                if (d.decide(q[0], q[1]).controlled()) violated++;
            }
        }
        long t1 = HierarchicalHydrology.Diag.SHARED_REFUSED_TRUNCATED.get();
        log("    截断盆地覆盖点样本=" + truncSamples + " 违规=" + violated + "（Diag 截断 " + t0 + "→" + t1 + "）");
        if (truncSamples == 0 && t1 <= t0) {
            log("    ⚠ 本 seed/窗口【无触窗框的跨缝盆地】⇒ 截断判据不可测：如实登记 NOT VERIFIED，"
                    + "不伪造 PASS、也不误判 FAIL");
        }
        chk(violated == 0, "REFUSED_TRUNCATED 盆地覆盖点 ⇒ 一律不受控（拒绝给湖，不搞 no-head 假 PASS）",
                "违规 " + violated + " 次");
    }

    private static void secRiver() throws Exception {
        log("\n=== [river] 出区接口现状，仅诊断，不造桥 ===");
        PROV.clear();
        ModelHydrologyProvider.setProbeSharedOff(true);
        com.geogenesis.diagnostics.WorldGenProfiler.configure(true, 0);
        int sampled = 0;
        boolean finite = true;
        for (int[] q : new int[][]{{-513, 194}, {-1, -169}}) {
            int rx = Math.floorDiv(q[0], hp().tract()), rz = Math.floorDiv(q[1], hp().tract());
            var upstream = PROV.coarseLayerFor(rx, rz);
            log("tract=" + rx + "," + rz + " ports=" + upstream.ports());
            for (int dx = -1; dx <= 2; dx++) {
                int x = q[0] + dx, z = q[1];
                int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                var cols = PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz));
                for (var c : cols) {
                    if (c.blockX() != x || c.blockZ() != z) continue;
                    double eroded = bed(x, z), delta = eroded - c.waterSurfaceY();
                    sampled++;
                    finite &= Double.isFinite(eroded) && Double.isFinite(c.waterSurfaceY());
                    String line = "point=" + x + "," + z + " lake=" + c.lakePlan()
                            + " fill=" + c.fillWater() + " surface=" + c.waterSurfaceY()
                            + " eroded=" + eroded + " carved=" + c.carvedGroundY()
                            + " delta=" + delta + " depthLimitWouldReject=" + (delta >= 3.5);
                    log(line);
                    com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("R3", "river-point-" + x + "-" + z, line);
                }
            }
            riverConnectivity(q[0], q[1], 16);
        }
        chk(sampled == 8 && finite, "河流病例八列非空转且高度有限", "sampled=" + sampled);
        log("NOT VERIFIED: 同行干湿不等于断河；缝带8邻连通只证本病例，不代表全河网连续。");
        Files.writeString(OUT.resolve("river-" + System.currentTimeMillis() + ".txt"), LOG.toString());
        ModelHydrologyProvider.setProbeSharedOff(false);
        com.geogenesis.diagnostics.WorldGenProfiler.configure(false, 0);
    }

    private static void secRiverCandidates() throws Exception {
        log("\n=== [river-candidates] 新候选生产列复核（共享开启，与最新面板同口径）===");
        PROV.clear();
        com.geogenesis.diagnostics.WorldGenProfiler.configure(true, 0);
        try {
            for (int[] q : new int[][]{{-710, 511, 1}, {-650, 511, 1}, {-582, 511, 1},
                    {-78, 511, 1}, {-513, 359, 0}, {-513, 447, 0}}) {
                int rx = Math.floorDiv(q[0], hp().tract()), rz = Math.floorDiv(q[1], hp().tract());
                log("candidate=" + q[0] + "," + q[1] + " ports=" + PROV.coarseLayerFor(rx, rz).ports());
                riverConnectivity(q[0], q[1], 16, q[2]);
            }
        } finally { com.geogenesis.diagnostics.WorldGenProfiler.configure(false, 0); }
        log("NOT VERIFIED: connectedAcross=false仅证局部断口，仍须排除湖海/上游源头和约束不可达。");
    }

    private static void riverConnectivity(int x, int z, int radius) throws Exception {
        riverConnectivity(x, z, radius, 0);
    }

    private record RiverConnectivity(boolean startWet, int component, int far, int crossings) {
        boolean connected() { return startWet && far > 0 && crossings > 0; }
    }

    private static RiverConnectivity riverConnectivity(int x, int z, int radius, int axis) throws Exception {
        return riverConnectivity(x, z, radius, axis, true);
    }

    private static RiverConnectivity riverConnectivity(int x, int z, int radius, int axis, boolean requireWet) throws Exception {
        Map<Long, List<HydrologyBlockCarvedColumn>> chunks = new HashMap<>();
        Set<Long> wet = new LinkedHashSet<>();
        StringBuilder mask = new StringBuilder();
        for (int bz = z - radius; bz <= z + radius; bz++) {
            for (int bx = x - radius; bx <= x + radius; bx++) {
                int cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
                long ck = ((long) cx << 32) ^ (cz & 0xffffffffL);
                var cols = chunks.computeIfAbsent(ck,
                        k -> PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz)));
                boolean river = false;
                for (var c : cols) {
                    if (c.blockX() == bx && c.blockZ() == bz) {
                        river = !c.lakePlan() && c.fillWater() && c.waterSurfaceY() > c.carvedGroundY();
                        break;
                    }
                }
                if (river) wet.add(((long) bx << 32) ^ (bz & 0xffffffffL));
                mask.append(river ? '#' : '.');
            }
            mask.append('\n');
        }
        long start = ((long) x << 32) ^ (z & 0xffffffffL);
        Set<Long> seen = new LinkedHashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        if (wet.contains(start)) { seen.add(start); queue.add(start); }
        int far = 0, crossings = 0;
        while (!queue.isEmpty()) {
            long k = queue.remove();
            int bx = (int) (k >> 32), bz = (int) k;
            if (axis == 0 ? bx > x : bz > z) far++;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nb = ((long) (bx + dx) << 32) ^ ((bz + dz) & 0xffffffffL);
                if (!wet.contains(nb)) continue;
                if (axis == 0 ? bx == x && dx == 1 : bz == z && dz == 1) crossings++;
                if (seen.add(nb)) queue.add(nb);
            }
        }
        log("river8 point=" + x + "," + z + " wet=" + wet.size() + " component=" + seen.size()
                + " axis=" + axis + " farCells=" + far + " seamCrossings=" + crossings
                + " connectedAcross=" + (far > 0 && crossings > 0));
        if (requireWet) chk(wet.contains(start) && !seen.isEmpty(), "缝带连通诊断非空转 " + x + "," + z, "起点非河");
        Files.writeString(OUT.resolve("river-mask-" + x + "-" + z + ".txt"), mask);
        return new RiverConnectivity(wet.contains(start), seen.size(), far, crossings);
    }

    private static SharedBoundaryDrainage.TerrainRouting localRouting() {
        double hs = TP.horizontalScale();
        return new SharedBoundaryDrainage.TerrainRouting(hp().step(), hp().tract(), TP.seaLevel(),
                (x, z) -> GEN.erodedHeight(x / hs, z / hs));
    }

    private static void shoreSynthetic() {
        int n = 9, cells = n * n;
        double[] bed = new double[cells], level = new double[cells];
        boolean[] sea = new boolean[cells], resolved = new boolean[cells], lake = new boolean[cells];
        int[] parent = new int[cells], ids = new int[cells];
        java.util.Arrays.fill(bed, 200); java.util.Arrays.fill(level, 200);
        java.util.Arrays.fill(parent, -1); java.util.Arrays.fill(ids, -1); java.util.Arrays.fill(resolved, true);
        var basins = new ArrayList<LakeWaterBalance.Basin>();
        for (int b = 0; b < 3; b++) {
            int k = (1 + 2 * b) * n + 4;
            bed[k] = 90 - 10 * b; level[k] = 100 - 10 * b; ids[k] = b; lake[k] = true;
            basins.add(new LakeWaterBalance.Basin(k, bed[k], level[k], level[k], 1, 2, 2, 1, 1, true, false));
        }
        for (int x = 1; x < 7; x++) {
            int k = x * n + 4; parent[k] = k + n;
            if (x % 2 == 0) { bed[k] = 105 - x * 5; level[k] = bed[k]; }
        }
        sea[7 * n + 4] = true; bed[7 * n + 4] = level[7 * n + 4] = 60;
        var lr = new LakeWaterBalance.Result(new double[cells], lake, basins, 0, 1,
                Map.of(), Map.of(), ids, new int[]{2 * n + 4, 4 * n + 4, 6 * n + 4});
        var p = new HydrologyPipeline.Result(n, n, 4, bed, sea, resolved, level, new double[cells], lake,
                parent, new double[cells], new boolean[cells], new double[cells], lr, null, null, level, 0, true, 0);
        var chain = SharedBoundaryDrainage.basinChain(p, 0, -100, -100);
        chk(chain.state() == SharedBoundaryDrainage.OutletState.CANDIDATE && chain.basins().equals(List.of(0, 1, 2))
                        && chain.edges().get(2).terminal() == SharedBoundaryDrainage.BasinEnd.SEA,
                "成对接口合成三湖下泄到真实海格", chain.toString());
        parent[2 * n + 4] = n + 4;
        chk(SharedBoundaryDrainage.basinChain(p, 0, -100, -100).state()
                        == SharedBoundaryDrainage.OutletState.NO_FEASIBLE_OUTLET,
                "成对接口自回负例不能选为出流", "missed");
        parent[2 * n + 4] = 3 * n + 4;
        basins.set(1, new LakeWaterBalance.Basin(3 * n + 4, 80, 110, 110, 1, 2, 2, 1, 1, true, false));
        chk(SharedBoundaryDrainage.basinChain(p, 0, -100, -100).state()
                        == SharedBoundaryDrainage.OutletState.NO_FEASIBLE_OUTLET,
                "下一湖更高水面拒绝逆坡接续", "missed");
        basins.set(1, new LakeWaterBalance.Basin(3 * n + 4, 80, 100, 100, 1, 2, 2, 1, 1, true, false));
        chk(SharedBoundaryDrainage.basinChain(p, 0, -100, -100).state()
                        == SharedBoundaryDrainage.OutletState.MERGE_PENDING,
                "同平面盆体待合并而非伪独立湖河回环", "missed");
        basins.set(0, new LakeWaterBalance.Basin(n + 4, 90, 100, 95, 1, 1, 1, 1, 0, false, false));
        chk(SharedBoundaryDrainage.basinChain(p, 0, -100, -100).state()
                        == SharedBoundaryDrainage.OutletState.CLOSED_LAKE,
                "未满零净出流湖不强接出口", "missed");
        basins.set(0, new LakeWaterBalance.Basin(n + 4, 90, 100, 100, 1, 2, 2, 1, 1, true, false));
        parent[n + 4] = -1;
        chk(SharedBoundaryDrainage.selectOutlet(p, SharedBoundaryDrainage.auditBasin(p, 0, -100, -100)).state()
                        == SharedBoundaryDrainage.OutletState.NO_FEASIBLE_OUTLET,
                "无有向边不按坐标强定同平面湖下游", "missed");
        parent[n + 4] = 2 * n + 4;
        basins.set(0, new LakeWaterBalance.Basin(n + 4, 90, 100, 100, 1, 2, 2, 1, 1, true, true));
        chk(SharedBoundaryDrainage.basinChain(p, 0, -100, -100).state()
                        == SharedBoundaryDrainage.OutletState.WATER_PENDING,
                "来水截断不得伪装已闭合", "missed");
        shoreRefinementSynthetic();
    }

    private static void shoreRefinementSynthetic() {
        var a = new SharedBoundaryDrainage.WorldCell(0, 0);
        var b = new SharedBoundaryDrainage.WorldCell(4, 0);
        var edge = new SharedBoundaryDrainage.ShoreEdge(a, b, 90, true,
                SharedBoundaryDrainage.BasinEnd.NEXT_LAKE, 1, 90, List.of(a, b));
        WindowOutlets.Terrain valley = (x, z) -> Math.abs(z + 2) < 0.1 ? 90 : 120;
        var pass = SharedBoundaryDrainage.refineShore(edge, 4, 100, valley);
        chk(pass.passable() && pass.path().stream().allMatch(c -> c.z() == -2),
                "亚格穿岸沿真实谷底而非高床中心直线", pass.toString());
        var wall = SharedBoundaryDrainage.refineShore(edge, 4, 100,
                (x, z) -> x == 0 ? 110 : valley.height(x, z));
        chk(!wall.passable() && wall.barrier() == 110, "小走廊真山脊不可假连/强挖", wall.toString());
        var translated = new SharedBoundaryDrainage.ShoreEdge(new SharedBoundaryDrainage.WorldCell(-100, -40),
                new SharedBoundaryDrainage.WorldCell(-96, -40), 90, true,
                SharedBoundaryDrainage.BasinEnd.NEXT_LAKE, 1, 90, List.of());
        var repeat = SharedBoundaryDrainage.refineShore(translated, 4, 100, (x, z) -> valley.height(x + 100, z + 40));
        chk(repeat.passable() && repeat.barrier() == pass.barrier() && repeat.path().size() == pass.path().size(),
                "有界亚格走廊负坐标平移一致", repeat.toString());
        var touch = new SharedBoundaryDrainage.ShoreEdge(a, new SharedBoundaryDrainage.WorldCell(4, 4),
                90, true, SharedBoundaryDrainage.BasinEnd.NEXT_LAKE, 1, 90, List.of());
        var pinhole = SharedBoundaryDrainage.refineShore(touch, 4, 100,
                (x, z) -> (x == -2 && z == -2) || (x == 6 && z == 6) ? 90 : 120);
        chk(!pinhole.passable(), "不同谷底角点不能仅靠格元相邻宣称通水", pinhole.toString());
    }

    private static void cellContractSynthetic() {
        log("\n=== [cell-contract] 固定共享边缘节点与可组合真实通路（不是水面/流向） ===");
        for (int step : new int[]{2, 4, 8}) {
            WindowOutlets.Terrain valley = (x, z) -> z == -step / 2.0 ? 90 : 120;
            var a = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), step, valley);
            var b = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(step, 0), step, valley);
            var from = new SharedBoundaryDrainage.WorldCell(-step / 2, -step / 2);
            var to = new SharedBoundaryDrainage.WorldCell(step + step / 2, -step / 2);
            var forward = SharedBoundaryDrainage.composeCells(List.of(a, b), from, to, 100);
            var order = SharedBoundaryDrainage.composeCells(List.of(b, a), from, to, 100);
            var reverse = SharedBoundaryDrainage.composeCells(List.of(b, a), to, from, 100);
            var reversedPath = new ArrayList<>(forward.path()); java.util.Collections.reverse(reversedPath);
            chk(forward.passable() && forward.equals(order) && reverse.path().equals(reversedPath)
                            && reverse.barrier() == forward.barrier() && continuous(forward.path()),
                    "step=" + step + " 邻格共享角点拼接/逆序逐位同值四邻连续", forward.toString());
            chk(a.ports().size() == 4 * step && a.routes().size() == (step + 1) * (step + 1) - 1
                            && a.routes().stream().allMatch(r -> r.path().size() == 2 && continuous(r.path())),
                    "全部共享边缘整点保留，容量树只存N-1条四邻边", "ports=" + a.ports().size() + " edges=" + a.routes().size());
            compactCellOracle(step);
            var own = SharedBoundaryDrainage.composeCells(List.of(a), from, from, 80);
            chk(!own.passable() && own.barrier() == 90, "同节点查询保留床高，不把空路径当零瓶颈", own.toString());
            var isolated = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), step,
                    (x, z) -> Math.abs(x) == step / 2.0 && Math.abs(z) == step / 2.0 ? 90 : 120);
            chk(isolated.cornerBeds().stream().allMatch(h -> h == 90) && isolated.routes().stream().allMatch(r -> r.barrier() == 120),
                    "各角都低但彼此隔绝必须保留120瓶颈", "routes=" + isolated.routes().size());
            WindowOutlets.Terrain middle = (x, z) -> z == 0 ? 90 : 120;
            var ma = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), step, middle);
            var mb = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(step, 0), step, middle);
            var mid = SharedBoundaryDrainage.composeCells(List.of(ma, mb),
                    new SharedBoundaryDrainage.WorldCell(-step / 2, 0), new SharedBoundaryDrainage.WorldCell(step + step / 2, 0), 100);
            chk(mid.passable() && mid.barrier() == 90 && continuous(mid.path())
                            && ma.cornerBeds().stream().allMatch(h -> h == 120),
                    "角点全高但共享边中间沟槽必须覆盖", mid.toString());
        }
        cellCompositionOracle();
        cellContractConcurrency();
    }

    private static boolean domainFrame(SharedBlockDrainage.Domain d, int x, int z) {
        return x == d.originX() || z == d.originZ() || x == d.originX() + d.sizeX() - 1 || z == d.originZ() + d.sizeZ() - 1;
    }

    private static void directionSynthetic() throws Exception {
        log("\n=== [direction-synth] 真实四邻下降与完整平地出口，不消费容量树parent ===");
        WindowOutlets.Terrain terrace = (x, z) -> Math.abs(z) <= 1 && x >= -4 && x <= 4 ? (x == 4 ? 90 : 100) : 120;
        var a = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, terrace, (x, z) -> false, false);
        var route = SharedBlockDrainage.direction(a, false);
        var t = route.trace(-4, 0);
        chk(t.end() == SharedBlockDrainage.DirectionEnd.BASIN_PENDING && t.flatEdges() > 0
                        && t.maxBedRise() == 0 && t.path().get(t.path().size() - 1).x() == 4 && continuous(t.path()),
                "完整平地先统一真实下降出口，再以距离递减抵达洼地待决", t.toString());
        var b = SharedBlockDrainage.buildAt(4, 0, 8, 4, 0, terrace, (x, z) -> false, true);
        var other = SharedBlockDrainage.direction(b, true);
        boolean overlap = true;
        for (int x = -4; x <= 4; x++) for (int z = -1; z <= 1; z++) overlap &= route.at(x, z).equals(other.at(x, z));
        chk(overlap, "完整平地跨重叠窗口方向与距离同值", "different");
        var flat = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, (x, z) -> 100, (x, z) -> false, false);
        chk(SharedBlockDrainage.direction(flat, false).trace(0, 0).end() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME,
                "触框平地没有出口权威，不按坐标造坡", "false terminal");
        var clipped = SharedBlockDrainage.buildAt(-8, 0, 8, 4, 0, terrace, (x, z) -> false, false);
        chk(SharedBlockDrainage.direction(clipped, false).trace(-4, 0).end() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME,
                "同平地被裁切时待决，不能冒用完整组件的出口", "missed truncation");
        var ocean = SharedBlockDrainage.buildAt(0, 0, 8, 4, 95, terrace, (x, z) -> x == 4 && z == 0, false);
        var sea = SharedBlockDrainage.direction(ocean, false).trace(-4, 0);
        chk(sea.end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE && sea.maxBedRise() == 0,
                "方向到已知海候选不冒充全球入海证书", sea.toString());
        var equalOcean = SharedBlockDrainage.buildAt(0, 0, 8, 4, 105,
                (x, z) -> Math.abs(x) <= 4 && Math.abs(z) <= 1 ? 100 : 120, (x, z) -> x == 4 && z == 0, false);
        chk(SharedBlockDrainage.direction(equalOcean, false).trace(-4, 0).end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE,
                "等床海候选以平地距离递减终止，不循环", "wrong equal-level route");
        directionBoundarySynthetic();
        diagonalGeometrySynthetic();
        lookaheadSynthetic();
        segmentGeometrySynthetic();
        continuationSynthetic();
        directionTransferSynthetic(route, a, flat);
        directionOracleChecks();
        var shifted = SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> terrace.height(x + 32, z + 16), (x, z) -> false, true);
        var translated = SharedBlockDrainage.direction(shifted, false).trace(-36, -16);
        chk(t.path().stream().map(c -> new SharedBoundaryDrainage.WorldCell(c.x() - 32, c.z() - 16)).toList()
                        .equals(translated.path()) && t.end() == translated.end(), "负坐标平移完整方向通路一致", "different");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.Direction>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> SharedBlockDrainage.direction(a, true)));
            boolean same = true;
            var channelEdges = route.channels(7).edges(4);
            for (var f : futures) {
                var cold = f.get(30, TimeUnit.SECONDS);
                same &= route.sameDirection(cold) && channelEdges.equals(cold.channels(7).edges(4));
            }
            chk(same, "四线程方向数组与选中边逐位同值", "different");
        } finally { pool.shutdownNow(); }
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.direction(a, false); }
        catch (java.util.concurrent.CancellationException e) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(cancel && route.sameDirection(SharedBlockDrainage.direction(a, false)), "取消方向求解不发布半产物，重试同值", "missed");
    }

    private static void continuationSynthetic() throws Exception {
        var a = new SharedBoundaryDrainage.WorldCell(-4, -2); var b = new SharedBoundaryDrainage.WorldCell(-4, 2);
        var outside = new SharedBoundaryDrainage.WorldCell(-40, 20);
        var sources = List.of(new SharedBlockDrainage.ContinuationSource(a, a), new SharedBlockDrainage.ContinuationSource(b, b),
                new SharedBlockDrainage.ContinuationSource(outside, null));
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, (x, z) -> 100 - x + z * z, (x, z) -> false, false);
        var direction = SharedBlockDrainage.direction(d, false); var before = direction.channels(10000).edges(4);
        var actual = direction.continueSources(sources, 4);
        chk(!actual.edges().isEmpty() && actual.edges().stream().anyMatch(e -> e.sources().size() == 2)
                        && actual.sources().size() == 3 && actual.sources().stream().filter(e -> e.entry() == null)
                        .allMatch(e -> e.terminal() == null && e.state() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME)
                        && before.isEmpty() && before.equals(direction.channels(10000).edges(4)),
                "已选源接续不以本地面积再筛，合流共边且域外未知，不注入Q/面积", actual.toString());
        var reversed = new ArrayList<>(sources); java.util.Collections.reverse(reversed); reversed.add(sources.get(0));
        chk(actual.equals(direction.continueSources(reversed, 4)), "源身份逆序/重复只记录一次，无重复河边", "different");
        boolean conflict = false;
        try { direction.continueSources(List.of(sources.get(0), new SharedBlockDrainage.ContinuationSource(a, null)), 4); }
        catch (IllegalArgumentException e) { conflict = true; }
        chk(conflict, "同源不同入口不能静默采用先到者", "missed conflict");
        var shifted = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> 100 - (x + 32) + (z + 16) * (z + 16), (x, z) -> false, false), false);
        var translatedSources = sources.stream().map(s -> new SharedBlockDrainage.ContinuationSource(
                new SharedBoundaryDrainage.WorldCell(s.source().x() - 32, s.source().z() - 16), s.entry() == null ? null
                : new SharedBoundaryDrainage.WorldCell(s.entry().x() - 32, s.entry().z() - 16))).toList();
        var translated = shifted.continueSources(translatedSources, 4);
        chk(actual.edges().stream().map(e -> new SharedBlockDrainage.ContinuationEdge(new SharedBlockDrainage.DirectionEdgeId(
                        new SharedBoundaryDrainage.WorldCell(e.id().from().x() - 32, e.id().from().z() - 16),
                        new SharedBoundaryDrainage.WorldCell(e.id().to().x() - 32, e.id().to().z() - 16), 4),
                        e.sources().stream().map(s -> new SharedBoundaryDrainage.WorldCell(s.x() - 32, s.z() - 16)).toList())).toList()
                        .equals(translated.edges()), "世界源与接续边负坐标平移同值", "different");
        var many = new ArrayList<SharedBlockDrainage.ContinuationSource>();
        for (int i = 0; i < 32; i++) many.add(new SharedBlockDrainage.ContinuationSource(new SharedBoundaryDrainage.WorldCell(100 + i, 100), a));
        chk(direction.continueSources(many, 4).edges().stream().allMatch(e -> e.sources().size() == 32),
                "32源上限含最高身份位不丢失，合流共边每源一次", "lost identity");
        boolean limit = false; many.add(new SharedBlockDrainage.ContinuationSource(outside, null));
        try { direction.continueSources(many, 4); } catch (IllegalArgumentException e) { limit = true; }
        chk(limit && direction.continueSources(List.of(), 4).edges().isEmpty(), "显式源预算有界，空输入不自行选源", "missed bound");
        for (int seed = 0; seed < 8; seed++) {
            final int s = seed;
            var domain = SharedBlockDrainage.buildAt(-16, -32, 8, 4, 0,
                    (x, z) -> Math.floorMod((int) x * 17 + (int) z * 23 + s * 11, 7), (x, z) -> false, false);
            var route = SharedBlockDrainage.direction(domain, false); var inputs = new ArrayList<SharedBlockDrainage.ContinuationSource>();
            for (int i = 0; i < 12; i++) {
                var cell = new SharedBoundaryDrainage.WorldCell(domain.originX() + 1 + i, domain.originZ() + 2 + i % 9);
                inputs.add(new SharedBlockDrainage.ContinuationSource(cell, cell));
            }
            var ledger = route.continueSources(inputs, 4); boolean valid = true;
            for (var input : inputs) {
                var trace = route.trace(input.entry().x(), input.entry().z()); var expected = new java.util.HashSet<SharedBlockDrainage.DirectionEdgeId>();
                for (int i = 1; i < trace.path().size(); i++) expected.add(new SharedBlockDrainage.DirectionEdgeId(trace.path().get(i - 1), trace.path().get(i), 4));
                var found = new java.util.HashSet<SharedBlockDrainage.DirectionEdgeId>();
                for (var e : ledger.edges()) if (e.sources().contains(input.source())) found.add(e.id());
                valid &= found.equals(expected);
                valid &= ledger.sources().stream().anyMatch(e -> e.source().equals(input.source()) && e.state() == trace.end()
                        && e.terminal().equals(trace.path().get(trace.path().size() - 1)));
            }
            valid &= ledger.edges().stream().map(e -> e.id().from()).distinct().count() == ledger.edges().size();
            chk(valid, "每个源接续边集合/终端独立逐源oracle（负坐标含平地） seed=" + seed, "different");
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.Continuations>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> direction.continueSources(sources, 4)));
            boolean same = true; for (var f : futures) same &= actual.equals(f.get(30, TimeUnit.SECONDS));
            chk(same, "四线程接续账本逐位一致，不改原方向/面积", "different");
        } finally { pool.shutdownNow(); }
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); direction.continueSources(sources, 4); }
        catch (java.util.concurrent.CancellationException e) { cancel = true; } finally { Thread.interrupted(); }
        chk(cancel && actual.equals(direction.continueSources(sources, 4)), "取消不发布部分源接续，重试同值", "missed");
    }

    private static void directionBoundarySynthetic() {
        WindowOutlets.Terrain east = (x, z) -> 100 - x + 0.01 * z * z;
        WindowOutlets.Terrain west = (x, z) -> 100 + x + 0.01 * z * z;
        var e = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, east, (x, z) -> false, false), false);
        var w = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, west, (x, z) -> false, true), true);
        chk(e.at(0, 0).next().x() == 1 && w.at(0, 0).next().x() == -1,
                "真实坡面决定东西相反方向，不按坐标指定下游", "wrong direction");
        WindowOutlets.Terrain tie = (x, z) -> z == 0 && Math.abs(x) <= 4 ? (Math.abs(x) == 4 ? 90 : 100) : 120;
        var td = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, tie, (x, z) -> false, false);
        chk(SharedBlockDrainage.direction(td, false).trace(0, 0).path().get(3).x() == -3
                        && SharedBlockDrainage.direction(td, false).sameDirection(SharedBlockDrainage.direction(td, true)),
                "平地只在等下降出口间稳定破平局，两侧方向不会相反", "different");
        WindowOutlets.Terrain slope = (x, z) -> 200 - x - z;
        var a = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, slope, (x, z) -> false, false);
        var b = SharedBlockDrainage.buildAt(8, 8, 8, 4, 0, slope, (x, z) -> false, true);
        var combined = SharedBlockDrainage.combine(List.of(a, b));
        var direction = SharedBlockDrainage.direction(combined, false);
        chk(direction.sameDirection(SharedBlockDrainage.direction(SharedBlockDrainage.combine(List.of(b, a)), true)),
                "缺角双域方向组合顺序同值", "different");
        boolean gap = false;
        try { direction.at(-10, 14); } catch (IllegalArgumentException ex) { gap = true; }
        chk(gap && direction.trace(-8, -8).maxBedRise() == 0,
                "未覆盖缺角不能补成地形，路径不逆坡", "missed gap");
        WindowOutlets.Terrain bowl = (x, z) -> x * x + z * z;
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, bowl, (x, z) -> false, false);
        var capacity = d.seaWitness(0, 0); var route = SharedBlockDrainage.direction(d, false).trace(0, 0);
        chk(route.end() == SharedBlockDrainage.DirectionEnd.BASIN_PENDING && route.path().size() == 1
                        && capacity.end() == SharedBlockDrainage.End.UNKNOWN_FRAME,
                "最低点不能用容量逃逸parent强行出流，也不伪装已闭流湖", route.toString());
    }

    private static void directionTransferSynthetic(SharedBlockDrainage.Direction direction,
            SharedBlockDrainage.Domain domain, SharedBlockDrainage.Domain clippedFlat) {
        var transfers = direction.transfers(4);
        var expected = new ArrayList<SharedBlockDrainage.DirectionTransfer>();
        for (int x = domain.originX(); x < domain.originX() + domain.sizeX(); x++)
            for (int z = domain.originZ(); z < domain.originZ() + domain.sizeZ(); z++) {
                var at = direction.at(x, z); var next = at.next(); if (next == null) continue;
                if (Math.floorDiv(x, 4) == Math.floorDiv(next.x(), 4) && Math.floorDiv(z, 4) == Math.floorDiv(next.z(), 4)) continue;
                var trace = direction.trace(next.x(), next.z()); int axis = x == next.x() ? 1 : 0;
                expected.add(new SharedBlockDrainage.DirectionTransfer(at.point(), next, axis,
                        axis == 0 ? Math.max(x, next.x()) : Math.max(z, next.z()), 4, trace.end(), trace.path().get(trace.path().size() - 1)));
            }
        chk(!transfers.isEmpty() && transfers.equals(expected) && transfers.stream().distinct().count() == transfers.size(),
                "全部方块过界边等价逐点父边/终端oracle，无触缝重复", "different");
        chk(transfers.equals(SharedBlockDrainage.direction(domain, true).transfers(4)), "逆序求方向不改接口世界身份", "different");
        chk(SharedBlockDrainage.direction(clippedFlat, false).transfers(4).isEmpty(),
                "触框平地未选父边时不虚构过界接口", "invented transfers");
        WindowOutlets.Terrain slope = (x, z) -> 100 - x + 0.01 * z * z;
        var shifted = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> slope.height(x + 32, z + 16), (x, z) -> false, false), false).transfers(4);
        var normal = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0,
                slope, (x, z) -> false, false), false).transfers(4);
        var translated = normal.stream().map(t -> new SharedBlockDrainage.DirectionTransfer(
                new SharedBoundaryDrainage.WorldCell(t.from().x() - 32, t.from().z() - 16),
                new SharedBoundaryDrainage.WorldCell(t.to().x() - 32, t.to().z() - 16), t.axis(),
                t.boundary() - (t.axis() == 0 ? 32 : 16), t.tract(), t.end(),
                new SharedBoundaryDrainage.WorldCell(t.terminal().x() - 32, t.terminal().z() - 16))).toList();
        chk(shifted.equals(translated) && normal.stream().anyMatch(t -> t.end() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME),
                "负坐标接口平移同值，域外终端仍未知不假入海", "different");
        var wider = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(4, 0, 8, 4, 0,
                slope, (x, z) -> false, true), true).transfers(4);
        var edge = normal.stream().filter(t -> t.from().equals(new SharedBoundaryDrainage.WorldCell(-1, 0))).findFirst().orElseThrow();
        var sameEdge = wider.stream().filter(t -> t.id().equals(edge.id())).findFirst().orElseThrow();
        chk(edge.boundary() == 0 && edge.to().x() == 0 && !edge.terminal().equals(sameEdge.terminal()),
                "同真实边ID不含窗口或局部终端，域变化的未知终端不可冒充全球权威", "different");
        var west = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0,
                (x, z) -> 100 + x + 0.01 * z * z, (x, z) -> false, false), false).transfers(4);
        var south = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0,
                (x, z) -> 100 + z + 0.01 * x * x, (x, z) -> false, false), false).transfers(4);
        chk(west.stream().anyMatch(t -> t.from().x() == 0 && t.to().x() == -1 && t.axis() == 0 && t.boundary() == 0)
                        && south.stream().anyMatch(t -> t.from().z() == 0 && t.to().z() == -1 && t.axis() == 1 && t.boundary() == 0),
                "负向跨零边界与Z轴使用floorDiv，四邻角点只跨一条轴", "missing");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); direction.transfers(4); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && transfers.equals(direction.transfers(4)), "取消接口提取不污染方向重试同值", "missed");
        boolean channelCancel = false;
        try { Thread.currentThread().interrupt(); direction.channels(7); }
        catch (java.util.concurrent.CancellationException ex) { channelCancel = true; }
        finally { Thread.interrupted(); }
        chk(channelCancel && direction.channels(7).edges(4).equals(direction.channels(7).edges(4)), "取消面积累积不发布半拓扑", "missed");
        boolean invalid = true;
        for (double threshold : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { direction.channels(threshold); } catch (IllegalArgumentException ex) { rejected = true; }
            invalid &= rejected;
        }
        chk(invalid, "面积门槛有限正值，不能零阈值把所有排水边当河", "accepted");
    }

    private static void channelOracleChecks(SharedBlockDrainage.Domain d, SharedBlockDrainage.Direction direction) {
        long[] expected = new long[d.nodes()]; boolean[] pending = new boolean[d.nodes()];
        var starts = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (int k = 0; k < d.nodes(); k++) {
            if (!d.coveredAtIndex(k)) continue;
            int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
            if (domainFrame(d, x, z) || direction.at(x, z).state() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME) {
                starts.add(new SharedBoundaryDrainage.WorldCell(x, z));
                for (int[] dir : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                    int px = x + dir[0], pz = z + dir[1];
                    if (px >= d.originX() && pz >= d.originZ() && px < d.originX() + d.sizeX() && pz < d.originZ() + d.sizeZ())
                        starts.add(new SharedBoundaryDrainage.WorldCell(px, pz));
                }
            }
            if (!d.oceanAtIndex(k)) for (var c : direction.trace(x, z).path())
                expected[(c.x() - d.originX()) * d.sizeZ() + c.z() - d.originZ()]++;
        }
        for (var start : starts) for (var c : direction.trace(start.x(), start.z()).path())
            pending[(c.x() - d.originX()) * d.sizeZ() + c.z() - d.originZ()] = true;
        int mismatch = 0;
        for (double threshold : new double[]{1, 7, 7.5, 300}) {
            var channels = direction.channels(threshold); var ids = new java.util.HashSet<SharedBlockDrainage.DirectionEdgeId>();
            for (var edge : channels.edges(4)) ids.add(edge.id());
            for (int k = 0; k < d.nodes(); k++) {
                int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
                var cell = channels.at(x, z); var at = direction.at(x, z);
                var state = expected[k] >= threshold ? SharedBlockDrainage.ChannelState.SELECTED_LOCAL
                        : pending[k] ? SharedBlockDrainage.ChannelState.INPUT_PENDING : SharedBlockDrainage.ChannelState.BELOW_LOCAL_THRESHOLD;
                if (cell.contributingBlocks() != expected[k] || cell.inputPending() != pending[k] || cell.state() != state
                        || (at.next() != null && ids.contains(new SharedBlockDrainage.DirectionEdgeId(at.point(), at.next(), 4)) != (expected[k] >= threshold))) mismatch++;
                if (at.next() != null && expected[k] >= threshold && channels.at(at.next().x(), at.next().z()).contributingBlocks() < expected[k]) mismatch++;
            }
            if (!channels.edges(4).equals(SharedBlockDrainage.direction(d, true).channels(threshold).edges(4))) mismatch++;
        }
        chk(mismatch == 0, "选中面积/未知传播独立逐源追踪oracle，合流一次、达标后不丢短接续 step=" + d.step(), "mismatch=" + mismatch);
    }

    private static void directionOracleChecks() {
        for (int step : new int[]{2, 4, 8}) for (int seed = 0; seed < 4; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> Math.floorMod((int) x * 7 + (int) z * 13 + (int) x * (int) z + s, 5);
            var d = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, (x, z) -> false, false);
            var forward = SharedBlockDrainage.direction(d, false); var reverse = SharedBlockDrainage.direction(d, true);
            int mismatches = 0;
            for (int k = 0; k < d.nodes(); k++) {
                int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
                var actual = forward.at(x, z); var expected = directionOracle(d, x, z);
                var trace = forward.trace(x, z);
                if (!actual.equals(expected) || !continuous(trace.path()) || trace.maxBedRise() != 0
                        || trace.path().stream().distinct().count() != trace.path().size()) mismatches++;
            }
            chk(mismatches == 0 && forward.sameDirection(reverse),
                    "独立逐查询平地BFS/最陡出口oracle step=" + step + " seed=" + seed + " nodes=" + d.nodes(),
                    "mismatch=" + mismatches);
            channelOracleChecks(d, forward);
        }
    }

    private static SharedBlockDrainage.DirectionStep directionOracle(SharedBlockDrainage.Domain d, int x, int z) {
        var start = new SharedBoundaryDrainage.WorldCell(x, z);
        double bed = d.bedAtIndex((x - d.originX()) * d.sizeZ() + z - d.originZ());
        var members = new java.util.TreeSet<SharedBoundaryDrainage.WorldCell>(java.util.Comparator
                .comparingInt(SharedBoundaryDrainage.WorldCell::x).thenComparingInt(SharedBoundaryDrainage.WorldCell::z));
        var pending = new java.util.ArrayDeque<SharedBoundaryDrainage.WorldCell>(); pending.add(start); members.add(start);
        boolean exposed = false; double bestDrop = -1;
        SharedBoundaryDrainage.WorldCell inner = null, outer = null;
        int[][] dirs = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};
        while (!pending.isEmpty()) {
            var c = pending.remove();
            for (int[] dir : dirs) {
                int bx = c.x() + dir[0], bz = c.z() + dir[1];
                if (bx < d.originX() || bz < d.originZ() || bx >= d.originX() + d.sizeX() || bz >= d.originZ() + d.sizeZ()) {
                    exposed = true; continue;
                }
                double h = d.bedAtIndex((bx - d.originX()) * d.sizeZ() + bz - d.originZ());
                var p = new SharedBoundaryDrainage.WorldCell(bx, bz);
                if (h == bed && members.add(p)) pending.add(p);
            }
        }
        for (var c : members) for (int[] dir : dirs) {
            int bx = c.x() + dir[0], bz = c.z() + dir[1];
            if (bx < d.originX() || bz < d.originZ() || bx >= d.originX() + d.sizeX() || bz >= d.originZ() + d.sizeZ()) continue;
            double h = d.bedAtIndex((bx - d.originX()) * d.sizeZ() + bz - d.originZ());
            if (h < bed && bed - h > bestDrop) { bestDrop = bed - h; inner = c; outer = new SharedBoundaryDrainage.WorldCell(bx, bz); }
        }
        if (exposed || outer == null) return new SharedBlockDrainage.DirectionStep(start, null, bed, bed, -1,
                exposed ? SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME : SharedBlockDrainage.DirectionEnd.BASIN_PENDING);
        if (start.equals(inner)) return new SharedBlockDrainage.DirectionStep(start, outer, bed, bed - bestDrop, 0,
                SharedBlockDrainage.DirectionEnd.DOWNHILL);
        var distance = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>(); pending.add(inner); distance.put(inner, 0);
        var parents = new HashMap<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.WorldCell>();
        while (!pending.isEmpty()) {
            var c = pending.remove();
            for (int[] dir : dirs) {
                var p = new SharedBoundaryDrainage.WorldCell(c.x() + dir[0], c.z() + dir[1]);
                if (members.contains(p) && !distance.containsKey(p)) {
                    distance.put(p, distance.get(c) + 1); parents.put(p, c); pending.add(p);
                }
            }
        }
        return new SharedBlockDrainage.DirectionStep(start, parents.get(start), bed, bed, distance.get(start),
                SharedBlockDrainage.DirectionEnd.FLAT_ROUTE);
    }

    private static void sinkSynthetic() throws Exception {
        log("\n=== [sink-synth] 局部汇水与成对最低岸线，不发布湖/出流 ===");
        WindowOutlets.Terrain terrain = (x, z) -> Math.abs(z) <= 1 && x >= -3 && x <= 4
                ? (x <= 0 ? x * x + z * z : x == 1 ? 5 : 4 - x) : 20;
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, terrain, (x, z) -> false, false);
        var direction = SharedBlockDrainage.direction(d, false);
        var basin = direction.basinAt(0, 0);
        chk(basin.state() == SharedBlockDrainage.BasinState.SHORE_CANDIDATE && !basin.storage().isEmpty()
                        && basin.capacity() > 0 && basin.lowestShores().stream().allMatch(s -> s.sill() == 5),
                "最低成对鞍口5而非外侧最低床0，汇水与储水分开", basin.toString());
        var same = SharedBlockDrainage.direction(d, true).basinAt(0, 0);
        chk(basin.equals(same), "逆序汇水与全部最低岸线同值", "different");
        WindowOutlets.Terrain edge = (x, z) -> x * x + z * z;
        var clipped = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, edge, (x, z) -> false, false);
        var unknown = SharedBlockDrainage.direction(clipped, false).basinAt(0, 0);
        chk(unknown.state() == SharedBlockDrainage.BasinState.UNKNOWN_FRAME && unknown.catchment().isEmpty()
                        && unknown.storage().isEmpty() && unknown.lowestShores().isEmpty(),
                "触框汇水不发布部分盆体或假最低鞍口", unknown.toString());
        var shifted = SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> terrain.height(x + 32, z + 16), (x, z) -> false, true);
        var translated = SharedBlockDrainage.direction(shifted, false).basinAt(-32, -16);
        chk(basin.capacity() == translated.capacity() && basin.catchment().stream()
                        .map(c -> new SharedBoundaryDrainage.WorldCell(c.x() - 32, c.z() - 16)).toList().equals(translated.catchment()),
                "负坐标平移完整汇水/容量同值", "different");
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); direction.basinAt(0, 0); }
        catch (java.util.concurrent.CancellationException ex) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(cancel && basin.equals(direction.basinAt(0, 0)), "取消盆体分析不污染方向视图", "missed");
        WindowOutlets.Terrain flatBottom = (x, z) -> Math.abs(z) <= 1 && x >= -3 && x <= 4
                ? (x <= 0 ? 0 : x == 1 ? 5 : 4 - x) : 20;
        var flatDir = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0,
                flatBottom, (x, z) -> false, false), false);
        var left = flatDir.basinAt(-2, 0); var right = flatDir.basinAt(0, 0);
        chk(left.state() == SharedBlockDrainage.BasinState.SHORE_CANDIDATE
                        && left.catchment().equals(right.catchment()) && left.storage().equals(right.storage())
                        && left.capacity() == right.capacity() && left.lowestShores().equals(right.lowestShores()),
                "同连通平底多终点不拆盆且岸线同值", "different flat basin");
        WindowOutlets.Terrain twoExits = (x, z) -> Math.abs(z) <= 1 && Math.abs(x) <= 5
                ? (Math.abs(x) <= 2 ? x * x + z * z : Math.abs(x) == 3 ? 10 : Math.abs(x) == 4 ? 5 : 0) : 20;
        var tiedDirection = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, 1,
                twoExits, (x, z) -> Math.abs(x) == 5 && Math.abs(z) <= 1, false), false);
        var tied = tiedDirection.basinAt(0, 0);
        chk(tied.state() == SharedBlockDrainage.BasinState.SHORE_CANDIDATE
                        && tied.lowestShores().stream().anyMatch(s -> s.outer().x() < 0)
                        && tied.lowestShores().stream().anyMatch(s -> s.outer().x() > 0)
                        && tied.lowestShores().stream().allMatch(s -> s.sill() == 10
                            && s.downstream().end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE),
                "同高多岸线全部保留，不按坐标强选唯一出流", tied.toString());
        relationSynthetic(flatDir, tiedDirection, SharedBlockDrainage.direction(clipped, false));
        sinkCatchmentOracle();
    }

    private static void relationSynthetic(SharedBlockDrainage.Direction flat,
            SharedBlockDrainage.Direction tied, SharedBlockDrainage.Direction unknown) throws Exception {
        var a = new SharedBoundaryDrainage.WorldCell(-4, 0); var b = new SharedBoundaryDrainage.WorldCell(2, 0);
        WindowOutlets.Terrain terrain = (x, z) -> z == 0 && x >= -6 && x <= 6
                ? new double[]{8, 6, 4, 6, 8, 10, 4, 2, 0, 2, 5, 0, -2}[(int) x + 6] : 20;
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, -1, terrain, (x, z) -> x == 6 && z == 0, false);
        var direction = SharedBlockDrainage.direction(d, false);
        var graph = direction.relations(List.of(a, b));
        chk(graph.relations().get(0).state() == SharedBlockDrainage.RelationEnd.NEXT_BASIN
                        && graph.relations().get(0).target().equals(b)
                        && graph.relations().get(1).state() == SharedBlockDrainage.RelationEnd.OCEAN_CANDIDATE,
                "显式两盆链按真实鞍口下降接续，不凭可见湖掩码", graph.toString());
        chk(graph.equals(direction.relations(List.of(b, a, b)))
                        && graph.equals(SharedBlockDrainage.direction(d, true).relations(List.of(a, b))),
                "关系输入逆序/重复sink与冷方向逆序逐位同值", "different");
        budgetSynthetic(graph, direction.relations(List.of(b, a, b)));
        var members = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        int count = 0; for (var basin : graph.basins()) for (var cell : basin.catchment()) { count++; members.add(cell); }
        chk(count == members.size() && count == graph.assignedLocalCells()
                        && graph.assignedLocalCells() + graph.unassignedCoveredCells() == d.nodes(),
                "本地源成员只归属一次，未分配覆盖保留未知非零出流", "duplicate or lost local members");
        chk(direction.relations(List.of(a)).relations().get(0).state() == SharedBlockDrainage.RelationEnd.OUTSIDE_SET,
                "未显式给下一盆不递归扩域/冒充入海", "not outside set");
        var blocked = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, -1,
                (x, z) -> x == 4 && z == 0 ? 12 : terrain.height(x, z), (x, z) -> x == 6 && z == 0, false), false);
        var equalSaddles = blocked.relations(List.of(a, b));
        chk(equalSaddles.relations().stream().allMatch(r -> r.state() == SharedBlockDrainage.RelationEnd.NONDESCENDING),
                "同鞍口互指盆保持合并待决、不发布循环下泄", equalSaddles.toString());
        chk(SharedBlockDrainage.fullBudgets(equalSaddles, new LakeWaterBalance.Climate(1, 0, 0.5), sink -> 0)
                        .basins().stream().allMatch(v -> Double.isNaN(v.conditionalRelease())),
                "循环/同鞍口即使来水充足也不发布零或正出流", "released invalid relation");
        var flatGraph = flat.relations(List.of(new SharedBoundaryDrainage.WorldCell(-2, 0), new SharedBoundaryDrainage.WorldCell(0, 0)));
        chk(flatGraph.basins().size() == 1 && flatGraph.equals(flat.relations(List.of(new SharedBoundaryDrainage.WorldCell(0, 0)))),
                "同平底不同终点统一世界身份、不双算集水", flatGraph.toString());
        chk(tied.relations(List.of(new SharedBoundaryDrainage.WorldCell(0, 0))).relations().get(0).state()
                        == SharedBlockDrainage.RelationEnd.AMBIGUOUS_SHORE,
                "最低岸线平局保持待决而非坐标强选出流", "not ambiguous");
        var u = unknown.relations(List.of(new SharedBoundaryDrainage.WorldCell(0, 0)));
        chk(u.assignedLocalCells() == 0 && u.relations().get(0).state() == SharedBlockDrainage.RelationEnd.UNKNOWN_FRAME,
                "未知盆不分配部分成员/伪出流", u.toString());
        var unresolved = SharedBlockDrainage.direction(SharedBlockDrainage.buildAt(0, 0, 8, 4, -1,
                (x, z) -> x == 2 && z >= 1 ? 2 : terrain.height(x, z), (x, z) -> false, false), false);
        var unresolvedGraph = unresolved.relations(List.of(a, b));
        chk(unresolvedGraph.relations().get(0).state() == SharedBlockDrainage.RelationEnd.UNKNOWN_FRAME
                        && unresolvedGraph.relations().get(1).state() == SharedBlockDrainage.RelationEnd.UNKNOWN_FRAME,
                "已知下一盆身份但盆体触框时传播未知，不误报OUTSIDE_SET", unresolvedGraph.relations().toString());
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); direction.relations(List.of(a, b)); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && graph.equals(direction.relations(List.of(a, b))), "取消关系提取不污染输入，重试同值", "missed");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.BasinRelations>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> direction.relations(List.of(a, b))));
            boolean same = true; for (var future : futures) same &= graph.equals(future.get());
            chk(same, "四线程关系产物逐位同值（不共享可变账本）", "different");
        } finally { pool.shutdownNow(); }
    }

    private static void budgetSynthetic(SharedBlockDrainage.BasinRelations graph,
            SharedBlockDrainage.BasinRelations reverse) {
        var climate = new LakeWaterBalance.Climate(1, 0.25, 0.5);
        var wet = SharedBlockDrainage.fullBudgets(graph, climate, sink -> 2);
        var a = wet.basins().get(0); var b = wet.basins().get(1);
        double land = 0, rain = 0, evap = 0;
        for (var budget : wet.basins()) { land += budget.landRunoff(); rain += budget.lakeRain(); evap += budget.fullEvaporation(); }
        chk(wet.basins().stream().allMatch(v -> v.state() == SharedBlockDrainage.BudgetEnd.FULL_SURPLUS_CANDIDATE)
                        && b.upstreamRelease() == a.conditionalRelease()
                        && Math.abs(b.conditionalRelease() + evap - land - rain - 4) < 1e-12,
                "满盆条件账本收支守恒，A下泄只注入B一次", wet.toString());
        chk(a.landRunoff() == (graph.basins().get(0).catchment().size() - graph.basins().get(0).storage().size()) * 0.5
                        && a.lakeRain() == graph.basins().get(0).storage().size(),
                "满盆湖面降水替代该格陆地径流，不同时双计", a.toString());
        chk(wet.equals(SharedBlockDrainage.fullBudgets(reverse, climate, sink -> 2)), "逆序/重复sink不改水量账本", "different");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var counted = SharedBlockDrainage.fullBudgets(reverse, climate, sink -> { calls.incrementAndGet(); return 2; });
        chk(calls.get() == graph.basins().size() && wet.equals(counted), "外来源每个统一盆身份只读取一次", "double counted");
        var balanced = SharedBlockDrainage.fullBudgets(graph, new LakeWaterBalance.Climate(0, 1, 0.5),
                sink -> graph.basins().stream().filter(v -> v.sink().equals(sink)).findFirst().orElseThrow().storage().size());
        chk(balanced.basins().stream().allMatch(v -> v.state() == SharedBlockDrainage.BudgetEnd.FULL_NOT_SUPPORTED
                        && v.conditionalRelease() == 0), "非零来水恰等于满盆蒸发时无净出流", balanced.toString());
        var dry = SharedBlockDrainage.fullBudgets(graph, new LakeWaterBalance.Climate(0, 1, 0.5), sink -> 0);
        chk(dry.basins().stream().allMatch(v -> v.state() == SharedBlockDrainage.BudgetEnd.FULL_NOT_SUPPORTED
                        && v.conditionalRelease() == 0), "已知零来水/高蒸发不强造满湖出流", dry.toString());
        var zero = SharedBlockDrainage.fullBudgets(graph, new LakeWaterBalance.Climate(0, 0, 0), sink -> 0);
        chk(zero.basins().stream().allMatch(v -> v.state() == SharedBlockDrainage.BudgetEnd.FULL_NOT_SUPPORTED
                        && v.conditionalRelease() == 0), "零损失但零来水也不能认证溢流", zero.toString());
        var unknown = SharedBlockDrainage.fullBudgets(graph, climate, sink -> sink.equals(a.sink()) ? Double.NaN : 0);
        chk(unknown.basins().stream().allMatch(v -> v.state() == SharedBlockDrainage.BudgetEnd.UNKNOWN_INPUT
                        && Double.isNaN(v.conditionalRelease())) && Double.isNaN(unknown.basins().get(1).upstreamRelease()),
                "未知外来水沿盆链传播，标量上游不能冒充区间下界", unknown.toString());
        boolean bad = false, cancel = false;
        try { SharedBlockDrainage.fullBudgets(graph, climate, sink -> -1); }
        catch (IllegalArgumentException ex) { bad = true; }
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.fullBudgets(graph, climate, sink -> 2); }
        catch (java.util.concurrent.CancellationException ex) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(bad && cancel && wet.equals(SharedBlockDrainage.fullBudgets(graph, climate, sink -> 2)),
                "非法外来水拒绝、取消不发布半账本且重试同值", "missed");
        budgetRangeSynthetic(graph, climate, wet);
    }

    private static void budgetRangeSynthetic(SharedBlockDrainage.BasinRelations graph, LakeWaterBalance.Climate climate,
            SharedBlockDrainage.BasinBudgets exact) {
        var bounded = SharedBlockDrainage.fullBudgetRanges(graph, climate, sink -> new SharedBlockDrainage.RateRange(1, 3));
        boolean enclosed = true;
        for (int i = 0; i < exact.basins().size(); i++) {
            double q = exact.basins().get(i).conditionalRelease(); var r = bounded.basins().get(i).conditionalRelease();
            enclosed &= r.lower() <= q && q <= r.upper();
        }
        chk(enclosed, "有限输入区间包住独立标量情景，不重复注入上游", bounded.toString());
        var unknown = SharedBlockDrainage.fullBudgetRanges(graph, climate,
                sink -> new SharedBlockDrainage.RateRange(0, Double.POSITIVE_INFINITY));
        chk(unknown.basins().stream().allMatch(b -> b.state() == SharedBlockDrainage.RangeBudgetEnd.POSITIVE_LOWER_BOUND
                        && b.conditionalRelease().lower() > 0 && b.conditionalRelease().upper() == Double.POSITIVE_INFINITY),
                "非负未知来水不抹掉固定满盆模型已有正盈余下界（非实际溢流）", unknown.toString());
        var dry = SharedBlockDrainage.fullBudgetRanges(graph, new LakeWaterBalance.Climate(0, 1, 0),
                sink -> new SharedBlockDrainage.RateRange(0, 0));
        chk(dry.basins().stream().allMatch(b -> b.state() == SharedBlockDrainage.RangeBudgetEnd.NO_POSITIVE_UPPER_BOUND
                        && b.conditionalRelease().upper() == 0), "已知上界也不足蒸发，无正满盆下泄", dry.toString());
        var undecided = SharedBlockDrainage.fullBudgetRanges(graph, new LakeWaterBalance.Climate(0, 1, 0),
                sink -> new SharedBlockDrainage.RateRange(0, Double.POSITIVE_INFINITY));
        chk(undecided.basins().stream().allMatch(b -> b.state() == SharedBlockDrainage.RangeBudgetEnd.UNRESOLVED
                        && b.conditionalRelease().lower() == 0), "跨越零盈余区间仍待决，不自动当干湖或满湖", undecided.toString());
        int invalid = 0;
        for (double[] r : new double[][]{{-1, 1}, {2, 1}, {Double.NaN, 1}, {0, Double.NaN}, {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}}) {
            try { new SharedBlockDrainage.RateRange(r[0], r[1]); }
            catch (IllegalArgumentException ex) { invalid++; }
        }
        chk(invalid == 5, "非法/倒置/NaN区间拒绝，未知用显式非负上界", "accepted invalid");
        boolean corners = true;
        for (double qa : new double[]{1, 3}) for (double qb : new double[]{1, 3}) {
            double upstream = 0;
            for (int i = 0; i < graph.basins().size(); i++) {
                var basin = graph.basins().get(i);
                double q = Math.max(0, (basin.catchment().size() - basin.storage().size()) * climate.precipitation() * climate.runoffCoeff()
                        + basin.storage().size() * (climate.precipitation() - climate.evaporation()) + upstream + (i == 0 ? qa : qb));
                var r = bounded.basins().get(i).conditionalRelease(); corners &= q >= r.lower() && q <= r.upper(); upstream = q;
            }
        }
        chk(corners, "区间四角独立两盆代数oracle均被包含", "outside range");
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.fullBudgetRanges(graph, climate,
                sink -> new SharedBlockDrainage.RateRange(1, 3)); }
        catch (java.util.concurrent.CancellationException ex) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(cancel && bounded.equals(SharedBlockDrainage.fullBudgetRanges(graph, climate,
                sink -> new SharedBlockDrainage.RateRange(1, 3))), "取消区间不发布半产物且重试同值", "different");
    }

    private static void sinkCatchmentOracle() {
        for (int step : new int[]{2, 4, 8}) for (int seed = 0; seed < 4; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> Math.abs(x) >= 5 || Math.abs(z) >= 5
                    ? 20 : Math.floorMod((int) x * 7 + (int) z * 13 + s, 9);
            var d = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, (x, z) -> false, false);
            var direction = SharedBlockDrainage.direction(d, false);
            int sinks = 0, mismatches = 0;
            for (int k = 0; k < d.nodes(); k++) {
                int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
                var at = direction.at(x, z);
                if (at.state() != SharedBlockDrainage.DirectionEnd.BASIN_PENDING) continue;
                var basin = direction.basinAt(x, z);
                if (basin.state() == SharedBlockDrainage.BasinState.UNKNOWN_FRAME) continue;
                var expected = new LinkedHashSet<SharedBoundaryDrainage.WorldCell>();
                for (int p = 0; p < d.nodes(); p++) {
                    var t = direction.trace(d.originX() + p / d.sizeZ(), d.originZ() + p % d.sizeZ());
                    var end = t.path().get(t.path().size() - 1);
                    if (sameBottomComponent(direction, at.point(), end))
                        expected.add(new SharedBoundaryDrainage.WorldCell(d.originX() + p / d.sizeZ(), d.originZ() + p % d.sizeZ()));
                }
                if (!expected.equals(new java.util.HashSet<>(basin.catchment()))) mismatches++;
                var memberSet = new java.util.HashSet<>(basin.catchment());
                double expectedSill = thresholdTerminalOracle(at.point(), terrain, d.originX(), d.originX() + d.sizeX() - 1,
                        (bx, bz) -> !memberSet.contains(new SharedBoundaryDrainage.WorldCell(bx, bz)), Double.NEGATIVE_INFINITY);
                for (var shore : basin.lowestShores()) if (!basin.catchment().contains(shore.inner())
                        || basin.catchment().contains(shore.outer()) || shore.downstream().path().contains(at.point())
                        || shore.sill() != expectedSill) mismatches++;
                var storage = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
                if (terrain.height(x, z) < expectedSill) storage.add(at.point());
                boolean changed;
                do {
                    changed = false;
                    for (int bx = d.originX(); bx < d.originX() + d.sizeX(); bx++)
                        for (int bz = d.originZ(); bz < d.originZ() + d.sizeZ(); bz++) {
                            var c = new SharedBoundaryDrainage.WorldCell(bx, bz);
                            if (storage.contains(c) || terrain.height(bx, bz) >= expectedSill) continue;
                            if (storage.contains(new SharedBoundaryDrainage.WorldCell(bx - 1, bz))
                                    || storage.contains(new SharedBoundaryDrainage.WorldCell(bx + 1, bz))
                                    || storage.contains(new SharedBoundaryDrainage.WorldCell(bx, bz - 1))
                                    || storage.contains(new SharedBoundaryDrainage.WorldCell(bx, bz + 1))) changed |= storage.add(c);
                        }
                } while (changed);
                double capacity = 0;
                for (int bx = d.originX(); bx < d.originX() + d.sizeX(); bx++)
                    for (int bz = d.originZ(); bz < d.originZ() + d.sizeZ(); bz++)
                        if (storage.contains(new SharedBoundaryDrainage.WorldCell(bx, bz))) capacity += expectedSill - terrain.height(bx, bz);
                if (capacity != basin.capacity() || !storage.equals(new java.util.HashSet<>(basin.storage()))) mismatches++;
                sinks++;
            }
            chk(sinks > 0 && mismatches == 0, "逐节点追方向对照逆向汇水 step=" + step + " seed=" + seed + " sinks=" + sinks,
                    "mismatch=" + mismatches);
        }
    }

    private static boolean sameBottomComponent(SharedBlockDrainage.Direction direction,
            SharedBoundaryDrainage.WorldCell from, SharedBoundaryDrainage.WorldCell to) {
        if (from.equals(to)) return true;
        var a = direction.at(from.x(), from.z()); var b = direction.at(to.x(), to.z());
        if (b.state() != SharedBlockDrainage.DirectionEnd.BASIN_PENDING || a.bed() != b.bed()) return false;
        var seen = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        var queue = new java.util.ArrayDeque<SharedBoundaryDrainage.WorldCell>(); queue.add(from); seen.add(from);
        while (!queue.isEmpty()) {
            var c = queue.remove();
            for (int[] dir : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                var p = new SharedBoundaryDrainage.WorldCell(c.x() + dir[0], c.z() + dir[1]);
                var step = direction.at(p.x(), p.z());
                if (step.state() != SharedBlockDrainage.DirectionEnd.BASIN_PENDING || step.bed() != a.bed()) continue;
                if (p.equals(to)) return true;
                if (seen.add(p)) queue.add(p);
            }
        }
        return false;
    }

    private static void secSinkView() throws Exception {
        log("\n=== [sink-view] A粗格漏小盆/B已有湖/C下游嵌套或未知 ===");
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean();
        var d = SharedBlockDrainage.buildAt(-768, 512, 256, 4, TP.seaLevel(), terrain, ocean, false);
        var direction = SharedBlockDrainage.direction(d, false);
        var basins = new ArrayList<SharedBlockDrainage.LocalBasin>();
        for (int[] sink : new int[][]{{-655, 653}, {-582, 581}}) {
            long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
            var basin = direction.basinAt(sink[0], sink[1]); basins.add(basin);
            chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "盆体/岸线分析无邻fine求解", "increased");
            log("BLOCK_BASIN sink=" + basin.sink() + " state=" + basin.state() + " catchment=" + basin.catchment().size()
                    + " storage=" + basin.storage().size() + " capacity=" + basin.capacity() + " shoreCount=" + basin.lowestShores().size());
            for (var shore : basin.lowestShores()) log("BLOCK_SHORE inner=" + shore.inner() + " outer=" + shore.outer()
                    + " sill=" + shore.sill() + " downstream=" + shore.downstream().end()
                    + " terminal=" + shore.downstream().path().get(shore.downstream().path().size() - 1));
            auditSinkProduction(basin);
            renderSink(basin);
            chk(basin.state() == SharedBlockDrainage.BasinState.SHORE_CANDIDATE
                            && basin.lowestShores().stream().allMatch(s -> s.downstream().end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE),
                    "最低局部岸线有海候选闭合（仅推广门，未证明水量/全球终端）", basin.state().toString());
        }
        auditSinkPair(basins);
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var relations = direction.relations(basins.stream().map(SharedBlockDrainage.LocalBasin::sink).toList());
        log("SINK_RELATIONS assigned=" + relations.assignedLocalCells() + " unassigned=" + relations.unassignedCoveredCells()
                + " covered=" + d.nodes() + " budget=UNKNOWN external inflow not certified");
        for (var relation : relations.relations()) log("SINK_RELATION=" + relation);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "显式两盆关系提取零邻fine", "increased");
        chk(relations.relations().size() == 2
                        && relations.relations().get(0).state() == SharedBlockDrainage.RelationEnd.OCEAN_CANDIDATE
                        && relations.relations().get(1).state() == SharedBlockDrainage.RelationEnd.NEXT_BASIN
                        && relations.assignedLocalCells() == basins.stream().mapToInt(b -> b.catchment().size()).sum(),
                "真实浅盆接续与成员一次归属（几何关系非出流发布）", relations.relations().toString());
        // Same fixed rates as current provider; explicit zero-external scenario is NOT an external-inflow certificate.
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        for (var budget : SharedBlockDrainage.fullBudgets(relations, climate, sink -> 0).basins())
            log("SINK_FULL_ZERO_EXTERNAL_SCENARIO=" + budget);
        var unknownBudget = SharedBlockDrainage.fullBudgets(relations, climate, sink -> Double.NaN);
        for (var budget : unknownBudget.basins()) log("SINK_ACTUAL_EXTERNAL_UNKNOWN=" + budget);
        chk(unknownBudget.basins().stream().allMatch(b -> b.state() == SharedBlockDrainage.BudgetEnd.UNKNOWN_INPUT
                        && Double.isNaN(b.conditionalRelease())), "真实外来水未知不能用条件盈余冒充已发布出流", unknownBudget.toString());
        var ranges = SharedBlockDrainage.fullBudgetRanges(relations, climate,
                sink -> new SharedBlockDrainage.RateRange(0, Double.POSITIVE_INFINITY));
        for (var budget : ranges.basins()) log("SINK_FIXED_FULL_MODEL_RANGE=" + budget);
        chk(ranges.basins().stream().allMatch(b -> b.state() == SharedBlockDrainage.RangeBudgetEnd.POSITIVE_LOWER_BOUND),
                "固定成员/满盆/恒气候模型的正盈余下界（不认证实际水面或全球路由）", ranges.toString());
        auditCoarseIncoming(direction, d, relations);
        var transfers = auditDirectionTransfers(direction, d, relations);
        var channels = auditChannels(direction, d, transfers);
        renderBasinOverview(relations, ranges, transfers, channels);
        Files.writeString(OUT.resolve("sink.txt"), LOG.toString());
    }

    private static void auditCoarseIncoming(SharedBlockDrainage.Direction direction, SharedBlockDrainage.Domain domain,
            SharedBlockDrainage.BasinRelations relations) {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(); int directed = 0, covered = 0, unresolved = 0;
        for (int rx = -3; rx <= -1; rx++) for (int rz = 0; rz <= 2; rz++) {
            if (rx == -2 && rz == 1) continue;
            var coarse = PROV.coarseLayerFor(rx, rz);
            for (var port : coarse.ports()) {
                if (rx + port.dRX() != -2 || rz + port.dRZ() != 1) continue;
                directed++; int k = coarse.cellAt(port.blockX(), port.blockZ());
                boolean resolved = k >= 0 && coarse.pipe().resolved()[k]; if (!resolved) unresolved++;
                int x = port.blockX(), z = port.blockZ();
                boolean inside = x >= domain.originX() && z >= domain.originZ()
                        && x < domain.originX() + domain.sizeX() && z < domain.originZ() + domain.sizeZ();
                String mapping = "OUTSIDE_FIXED_PATCH";
                if (inside) {
                    covered++; var trace = direction.trace(x, z); var terminal = trace.path().get(trace.path().size() - 1);
                    mapping = trace.end() + " terminal=" + terminal;
                    for (var basin : relations.basins()) if (basin.catchment().contains(terminal)) mapping += " basin=" + basin.sink();
                }
                log("COARSE_INCOMING from=" + rx + "," + rz + " port=" + port + " windowResolved=" + resolved
                        + " localWindowQ=" + (k < 0 ? Double.NaN : coarse.pipe().discharge()[k]) + " mapping=" + mapping
                        + " fluxCertificate=NONE (Port carries no Q/complete upstream coverage)");
            }
        }
        log("COARSE_INCOMING_AUDIT directed=" + directed + " insideFixedPatch=" + covered + " windowUnresolved=" + unresolved);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "粗incoming只读取证不新增邻fine", "increased");
    }

    private static List<SharedBlockDrainage.DirectionTransfer> auditDirectionTransfers(
            SharedBlockDrainage.Direction direction, SharedBlockDrainage.Domain domain,
            SharedBlockDrainage.BasinRelations relations) throws Exception {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var transfers = direction.transfers(hp().tract());
        var owner = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int i = 0; i < relations.basins().size(); i++)
            for (var cell : relations.basins().get(i).catchment()) owner.put(cell, i);
        var counts = new java.util.TreeMap<String, Integer>();
        var csv = new StringBuilder("fromX,fromZ,toX,toZ,axis,boundary,tract,end,terminalX,terminalZ,localBasin\n");
        int mismatches = 0;
        for (var t : transfers) {
            var trace = direction.trace(t.to().x(), t.to().z());
            if (trace.end() != t.end() || !trace.path().get(trace.path().size() - 1).equals(t.terminal())
                    || trace.maxBedRise() != 0 || !direction.at(t.from().x(), t.from().z()).next().equals(t.to())) mismatches++;
            int basin = owner.getOrDefault(t.terminal(), -1);
            String key = "axis=" + t.axis() + " boundary=" + t.boundary() + " sign="
                    + (t.axis() == 0 ? t.to().x() - t.from().x() : t.to().z() - t.from().z())
                    + " end=" + t.end() + " localBasin=" + basin;
            counts.merge(key, 1, Integer::sum);
            csv.append(t.from().x()).append(',').append(t.from().z()).append(',').append(t.to().x()).append(',')
                    .append(t.to().z()).append(',').append(t.axis()).append(',').append(t.boundary()).append(',')
                    .append(t.tract()).append(',').append(t.end()).append(',').append(t.terminal().x()).append(',')
                    .append(t.terminal().z()).append(',').append(basin).append('\n');
        }
        log("DIRECTION_TRANSFERS total=" + transfers.size() + " unique=" + transfers.stream().distinct().count()
                + " surfaceDrainageOnly=true channelSelection=NONE fluxCertificate=NONE");
        counts.forEach((key, value) -> log("DIRECTION_TRANSFER_GROUP " + key + " count=" + value));
        chk(mismatches == 0 && transfers.stream().distinct().count() == transfers.size(),
                "真实全部过界边与逐父边/逐追终端一致，无逆坡/重复", "mismatch=" + mismatches);
        for (int rx = Math.floorDiv(domain.originX(), hp().tract());
                rx <= Math.floorDiv(domain.originX() + domain.sizeX() - 1, hp().tract()); rx++)
            for (int rz = Math.floorDiv(domain.originZ(), hp().tract());
                    rz <= Math.floorDiv(domain.originZ() + domain.sizeZ() - 1, hp().tract()); rz++) {
                var coarse = PROV.coarseLayerFor(rx, rz);
                for (var port : coarse.ports()) {
                    final int sourceX = rx, sourceZ = rz;
                    long pairEdges = transfers.stream().filter(t -> Math.floorDiv(t.from().x(), hp().tract()) == sourceX
                            && Math.floorDiv(t.from().z(), hp().tract()) == sourceZ
                            && Math.floorDiv(t.to().x(), hp().tract()) == sourceX + port.dRX()
                            && Math.floorDiv(t.to().z(), hp().tract()) == sourceZ + port.dRZ()).count();
                    log("COARSE_TRANSFER_PAIR source=" + rx + "," + rz + " port=" + port + " blockEdgesInSameTractPair="
                            + pairEdges + " correspondence=NOT_CERTIFIED (coarse channel ports != all surface drainage)");
                }
            }
        for (int[] start : new int[][]{{-712, 504}, {-648, 504}, {-580, 504}}) {
            var trace = direction.trace(start[0], start[1]); var edges = new ArrayList<SharedBlockDrainage.DirectionTransfer>();
            for (int i = 1; i < trace.path().size(); i++) {
                var from = trace.path().get(i - 1); var to = trace.path().get(i);
                transfers.stream().filter(t -> t.from().equals(from) && t.to().equals(to)).forEach(edges::add);
            }
            log("SCENE_DIRECTION_HANDOFF start=" + start[0] + "," + start[1] + " edges=" + edges);
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "全部过界接口与粗端口对照零邻fine", "increased");
        Files.writeString(OUT.resolve("direction-transfers.csv"), csv.toString());
        return transfers;
    }

    private static SharedBlockDrainage.Channels auditChannels(SharedBlockDrainage.Direction direction,
            SharedBlockDrainage.Domain domain, List<SharedBlockDrainage.DirectionTransfer> transfers) throws Exception {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        double threshold = Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150")) * hp().step() * hp().step();
        var channels = direction.channels(threshold); var edges = channels.edges(hp().tract());
        var selected = channels.transfers(hp().tract());
        var allIds = new java.util.HashSet<SharedBlockDrainage.DirectionEdgeId>(); for (var t : transfers) allIds.add(t.id());
        int pending = 0, below = 0, terminal = 0, mismatch = 0; long conserved = 0, land = 0;
        var csv = new StringBuilder("fromX,fromZ,toX,toZ,tract,localAreaBlocks,inputPending\n");
        for (var edge : edges) {
            var id = edge.id(); var next = channels.at(id.to().x(), id.to().z());
            if (!direction.at(id.from().x(), id.from().z()).next().equals(id.to())
                    || next.contributingBlocks() < edge.contributingBlocks() || next.state() != SharedBlockDrainage.ChannelState.SELECTED_LOCAL) mismatch++;
            csv.append(id.from().x()).append(',').append(id.from().z()).append(',').append(id.to().x()).append(',')
                    .append(id.to().z()).append(',').append(id.tract()).append(',').append(edge.contributingBlocks()).append(',')
                    .append(edge.inputPending()).append('\n');
        }
        for (var t : transfers) {
            var cell = channels.at(t.from().x(), t.from().z());
            if (cell.state() == SharedBlockDrainage.ChannelState.INPUT_PENDING) pending++;
            if (cell.state() == SharedBlockDrainage.ChannelState.BELOW_LOCAL_THRESHOLD) below++;
        }
        for (int k = 0; k < domain.nodes(); k++) {
            if (!domain.coveredAtIndex(k)) continue;
            int x = domain.originX() + k / domain.sizeZ(), z = domain.originZ() + k % domain.sizeZ();
            if (!domain.oceanAtIndex(k)) land++;
            if (direction.at(x, z).next() == null) {
                conserved += channels.at(x, z).contributingBlocks();
                if (channels.at(x, z).state() == SharedBlockDrainage.ChannelState.SELECTED_LOCAL) terminal++;
            }
        }
        log("CHANNEL_FIXED_GRAPH thresholdBlocks=" + threshold + " selectedEdges=" + edges.size() + " selectedTransfers="
                + selected.size() + " pendingTransfers=" + pending + " belowTransfers=" + below + " selectedTerminals=" + terminal
                + " land=" + land + " terminalArea=" + conserved + " areaOnly=true fluxCertificate=NONE basinRelease=NOT_INJECTED");
        chk(mismatch == 0 && conserved == land && selected.stream().allMatch(e -> allIds.contains(e.id())),
                "共同方向图选中边下游不丢失/集水一次守恒/接口复用世界ID", "mismatch=" + mismatch);
        int sceneCoverage = 0;
        for (int[] start : new int[][]{{-712, 511}, {-648, 511}, {-580, 511}}) {
            var cell = channels.at(start[0], start[1]); log("CHANNEL_SCENE=" + cell);
            if (cell.state() == SharedBlockDrainage.ChannelState.SELECTED_LOCAL) sceneCoverage++;
        }
        chk(sceneCoverage == 3, "选中拓扑覆盖三个既有河路接口（不可删河或调低门槛凑PASS）", "matched=" + sceneCoverage + "/3");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "选中拓扑无邻fine且不注入粗窗口Q", "increased");
        Files.writeString(OUT.resolve("selected-channel-edges.csv"), csv.toString());
        return channels;
    }

    private static Set<Integer> sourceCandidates(HydrologyPipeline.Result p, double threshold) throws Exception {
        var params = new DeterministicSkeleton.Params(0, threshold, 0, 0, 24, 0, 2, 0, 0);
        return new TreeSet<>(DeterministicSkeleton.collectSources(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.nx(), p.nz(), params));
    }

    private static Set<SharedBoundaryDrainage.WorldCell> sourceWorld(Set<Integer> cells,
            HydrologyPipeline.Result pipe, int ox, int oz) {
        var result = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (int k : cells) result.add(new SharedBoundaryDrainage.WorldCell(
                ox + k / pipe.nz() * pipe.cellBlocks(), oz + k % pipe.nz() * pipe.cellBlocks()));
        return result;
    }

    private static void sourceDirectorySynthetic() {
        log("\n=== [source-synth] 先门限后选源不等于零门限选叶后过滤 ===");
        int n = 9; var level = new double[n * n]; var q = new double[n * n]; var parent = new int[n * n];
        var resolved = new boolean[n * n]; var sea = new boolean[n * n]; var lake = new boolean[n * n];
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            int k = x * n + z; level[k] = 100 - z; q[k] = z + 1; resolved[k] = true;
            parent[k] = z == n - 1 ? -1 : k + 1; sea[k] = z == n - 1;
        }
        var zero = DeterministicSkeleton.build(level, parent, resolved, lake, sea, q, null, n, n,
                new DeterministicSkeleton.Params(0, 0, 0, 0, 24, 0, 2, 0, 0), List.of(), null, false);
        var before = DeterministicSkeleton.build(level, parent, resolved, lake, sea, q, null, n, n,
                new DeterministicSkeleton.Params(0, 3, 0, 0, 24, 0, 2, 0, 0), List.of(), null, false);
        long after = zero.traces.stream().filter(t -> q[t.sourceCell()] >= 3).count();
        chk(after == 0 && before.traces.size() == n && before.traces.stream().allMatch(t -> t.sourceCell() % n == 2),
                "真实build反例：零门限选叶再过滤漏掉全部达标主干，先门限仍有9源", "after=" + after + " before=" + before.traces.size());
        var reversedQ = q.clone(); var reversedLevel = level.clone(); var reversedParent = parent.clone(); var reversedSea = sea.clone();
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            int k = x * n + z; reversedQ[k] = n - z; reversedLevel[k] = 100 + z;
            reversedParent[k] = z == 0 ? -1 : k - 1; reversedSea[k] = z == 0;
        }
        var opposite = DeterministicSkeleton.build(reversedLevel, reversedParent, resolved, lake, reversedSea,
                reversedQ, null, n, n, new DeterministicSkeleton.Params(0, 3, 0, 0, 24, 0, 2, 0, 0), List.of(), null, false);
        chk(opposite.traces.size() == n && opposite.traces.stream().allMatch(t -> t.sourceCell() % n == n - 3),
                "反向坡面同样先门限选起点，不按坐标指定下游", "count=" + opposite.traces.size());
        for (int threshold : new int[]{1, 2, 4, 8, 10}) {
            var actual = DeterministicSkeleton.build(level, parent, resolved, lake, sea, q, null, n, n,
                    new DeterministicSkeleton.Params(0, threshold, 0, 0, 24, 0, 2, 0, 0), List.of(), null, false);
            int expected = threshold <= n - 1 ? n : 0;
            chk(actual.traces.size() == expected && actual.traces.stream().allMatch(t -> t.sourceCell() % n == threshold - 1),
                    "多门限独立坡面起点oracle threshold=" + threshold, "found=" + actual.traces.size());
        }
        boolean unchanged = true;
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            int k = x * n + z; unchanged &= level[k] == 100 - z && q[k] == z + 1
                    && parent[k] == (z == n - 1 ? -1 : k + 1);
        }
        chk(unchanged, "选源实验不改输入床面/父树/来水", "mutated");
    }

    private static void secSourceDirectory() throws Exception {
        log("\n=== [source-directory] A粗层选源顺序/B地形位置差异/C竞争准入；NOT SOURCE AUTHORITY ===");
        sourceDirectorySynthetic();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var coarse = PROV.coarseLayerFor(-2, 0); var cp = coarse.pipe();
        double cells = Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150"));
        double coarseThreshold = cells * cp.lakeResult().runoffPerCell();
        var coarseBefore = sourceWorld(sourceCandidates(cp, coarseThreshold), cp, coarse.originX(), coarse.originZ());
        var coarseAfter = sourceCandidates(cp, 0); coarseAfter.removeIf(k -> cp.discharge()[k] < coarseThreshold);
        var coarseAfterWorld = sourceWorld(coarseAfter, cp, coarse.originX(), coarse.originZ());
        var directory = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (var s : coarse.trunkSeeds()) directory.add(new SharedBoundaryDrainage.WorldCell(s.blockX(), s.blockZ()));
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "粗目录与候选提取零fine依赖", "fine increased");
        var experiment = DeterministicSkeleton.build(cp.level(), cp.parent(), cp.resolved(), cp.lake(), cp.sea(),
                cp.discharge(), cp.lakeResult().basinOutlet(), cp.nx(), cp.nz(),
                new DeterministicSkeleton.Params(Integer.getInteger("geogenesis.sourceSpacing", 24) / 4,
                        coarseThreshold, 0, 0, 24, 0, 1, 0, 0));
        var experimentSeeds = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for (var t : experiment.traces) {
            var w = new SharedBoundaryDrainage.WorldCell(coarse.originX() + t.sourceCell() / cp.nz() * cp.cellBlocks(),
                    coarse.originZ() + t.sourceCell() % cp.nz() * cp.cellBlocks());
            if (Math.floorDiv(w.x(), hp().tract()) == -2 && Math.floorDiv(w.z(), hp().tract()) == 0
                    && !cp.lake()[t.sourceCell()] && !cp.sea()[t.sourceCell()]) experimentSeeds.add(w);
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "门限前移实验只复用同一粗pipe，无邻fine/侵蚀", "fine increased");
        log("D1 COARSE beforeThreshold=" + coarseBefore.size() + " zeroThenFilter=" + coarseAfterWorld.size()
                + " ownerPublished=" + directory.size() + " thresholdFirstOwnerExperiment=" + experimentSeeds.size()
                + " threshold=" + coarseThreshold + " directory=" + directory + " experiment=" + experimentSeeds);
        chk(coarseBefore.size() > coarseAfterWorld.size(), "真实粗窗也复现零门限选叶后过滤的信息丢失", "no difference");
        int cx = -768, cz = 256, radius = hp().fineRadius(), ox = cx - radius, oz = cz - radius;
        var pipe = PROV.pipeForProbe(cx, cz); var reaches = PROV.reachesForProbe(cx, cz);
        double fineThreshold = cells * pipe.lakeResult().runoffPerCell();
        var candidates = sourceCandidates(pipe, fineThreshold);
        var csv = new StringBuilder("reach,x,z,owned,crossesTract,coarseDirectory,coarseBeforeThreshold,coarseZeroThenFilter,fineCandidate,outlet,coarseQ,fineQ\n");
        var points = new ArrayList<SharedBoundaryDrainage.WorldCell>(); var missing = new ArrayList<SharedBoundaryDrainage.WorldCell>();
        int owned = 0, crossed = 0, covered = 0;
        for (int r = 0; r < reaches.size(); r++) {
            var reach = reaches.get(r); int s = reach.sourceCell();
            var world = new SharedBoundaryDrainage.WorldCell(ox + s / pipe.nz() * pipe.cellBlocks(), oz + s % pipe.nz() * pipe.cellBlocks());
            boolean owner = Math.floorDiv(world.x(), hp().tract()) == -2 && Math.floorDiv(world.z(), hp().tract()) == 0;
            boolean cross = false;
            for (int k : reach.cell) if (Math.floorDiv(ox + k / pipe.nz() * pipe.cellBlocks(), hp().tract()) != -2
                    || Math.floorDiv(oz + k % pipe.nz() * pipe.cellBlocks(), hp().tract()) != 0) cross = true;
            boolean outlet = java.util.Arrays.stream(pipe.lakeResult().basinOutlet()).anyMatch(k -> k == s);
            points.add(world); if (owner) owned++;
            if (owner && cross) { crossed++; if (directory.contains(world)) covered++; else missing.add(world); }
            int ck = coarse.cellAt(world.x(), world.z()); double coarseQ = ck < 0 ? Double.NaN : cp.discharge()[ck];
            csv.append(r).append(',').append(world.x()).append(',').append(world.z()).append(',').append(owner).append(',')
                    .append(cross).append(',').append(directory.contains(world)).append(',').append(coarseBefore.contains(world))
                    .append(',').append(coarseAfterWorld.contains(world)).append(',').append(candidates.contains(s))
                    .append(',').append(outlet).append(',').append(coarseQ).append(',').append(pipe.discharge()[s]).append('\n');
            log("D2 source=" + world + " owned=" + owner + " crosses=" + cross + " directory=" + directory.contains(world)
                    + " coarseBefore=" + coarseBefore.contains(world) + " fineCandidate=" + candidates.contains(s)
                    + " outlet=" + outlet + " coarseQ=" + coarseQ + " fineQ=" + pipe.discharge()[s]);
        }
        log("D3 FINE owned=" + owned + " ownedCrossing=" + crossed + " exactDirectoryCovered=" + covered
                + " missing=" + missing + " fineThreshold=" + fineThreshold + " noNearestMatching=true"
                + " thresholdFirstExperimentExact=" + missing.stream().filter(experimentSeeds::contains).count());
        renderSourceDirectory(points, directory, coarseBefore, csv.toString(), cx, cz, radius);
        chk(crossed > 0 && covered == crossed, "粗owner源目录必须覆盖已发布跨缝源，不能用删河/最近点替代", "covered=" + covered + "/" + crossed);
        // #region agent log: D1/D2/D3 source authority hypotheses, read-only production evidence
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void renderSourceDirectory(List<SharedBoundaryDrainage.WorldCell> fine,
            Set<SharedBoundaryDrainage.WorldCell> directory, Set<SharedBoundaryDrainage.WorldCell> before,
            String csv, int cx, int cz, int radius) throws Exception {
        Path out = OUT.resolve("source-directory-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "CURRENT PRODUCTION / SCHEMA77 / SOURCE DIRECTORY NOT APPLIED");
        var g = raw.createGraphics(); int ox = cx - radius, oz = cz - radius;
        for (var point : before) {
            g.setColor(java.awt.Color.YELLOW); g.drawRect(point.x() - ox - 2, point.z() - oz - 2, 4, 4);
        }
        for (var point : directory) {
            g.setColor(java.awt.Color.GREEN); g.fillRect(point.x() - ox - 2, point.z() - oz - 2, 5, 5);
        }
        for (var point : fine) {
            g.setColor(directory.contains(point) ? java.awt.Color.WHITE : java.awt.Color.RED);
            g.drawOval(point.x() - ox - 4, point.z() - oz - 4, 8, 8);
        }
        g.dispose(); writeLocalImage(out.resolve("directory.png"), raw, cx, cz, radius,
                "RED PUBLISHED START ABSENT IN DIRECTORY / GREEN OWNER DIRECTORY / YELLOW BEFORE-THRESHOLD CANDIDATE / NOT NEW WATER");
        Files.writeString(out.resolve("sources.csv"), csv);
        Files.writeString(out.resolve("manifest.txt"), "Fine published starts are NOT all physical headwaters: may be incoming/outlet/join split.\n"
                + "Exact coordinate coverage diagnoses replacement loss, not nearest-route parity or global Q.\n"
                + "Threshold-first candidates are not an accepted source set. No routing domains expanded.\n" + LOG);
        chk(mask.failCount == 0, "源目录大范围底图来自实际provider列，无失败像素", "fail=" + mask.failCount);
        log("SOURCE_DIRECTORY_DIR=" + out.toAbsolutePath());
    }

    private record OwnerSourcePath(SharedBoundaryDrainage.WorldCell source, List<Integer> path, String end) {
        OwnerSourcePath { path = List.copyOf(path); }
    }

    private record WindowGrid(HydrologyPipeline.Result pipe, int ox, int oz) {
        SharedBoundaryDrainage.WorldCell world(int k) {
            return new SharedBoundaryDrainage.WorldCell(ox + k / pipe.nz() * pipe.cellBlocks(), oz + k % pipe.nz() * pipe.cellBlocks());
        }
        int index(SharedBoundaryDrainage.WorldCell w) {
            int dx = w.x() - ox, dz = w.z() - oz, step = pipe.cellBlocks();
            if (dx < 0 || dz < 0 || dx % step != 0 || dz % step != 0 || dx / step >= pipe.nx() || dz / step >= pipe.nz()) return -1;
            return dx / step * pipe.nz() + dz / step;
        }
        /**
         * 生产 {@code worldRegion} 的格索引口径（截断除法，不拒绝界外）——
         * 世界链的 region 故意含<b>一格外环</b>以纳入来边，故其端点可能落在窗口外一步，
         * 此时索引为负。复核边↔reach 身份必须用同一算式，否则会把合法来边误判为未映射。
         */
        int productionIndex(WorldDrainage.Point p) {
            int step = pipe.cellBlocks();
            return (p.x() - ox) / step * pipe.nz() + (p.z() - oz) / step;
        }
    }
    private record WindowEntry(SharedBoundaryDrainage.WorldCell source, SharedBoundaryDrainage.WorldCell from,
                               SharedBoundaryDrainage.WorldCell to, String kind, int trace) { }
    private record WindowPath(WindowEntry entry, List<Integer> cells, String terminal, boolean firstEdge) {
        WindowPath { cells = List.copyOf(cells); }
    }
    private record WindowGraph(Map<Integer, List<Integer>> next, Map<Integer, String> ends) { }

    private static DeterministicSkeleton.Params windowParams(HydrologyPipeline.Result p) {
        int step = p.cellBlocks(), ratio = Math.max(1, 8 / step);
        return new DeterministicSkeleton.Params(Math.max(1, Math.round(Integer.getInteger("geogenesis.sourceSpacing", 24) / (float) step)),
                Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150")) * p.lakeResult().runoffPerCell(),
                2, Double.parseDouble(System.getProperty("geogenesis.skeletonMeanderAmp", "0")), 24,
                Integer.getInteger("geogenesis.skeletonMaxRivers", 0), Integer.getInteger("geogenesis.skeletonMinNodes", 3 * ratio),
                Integer.getInteger("geogenesis.skeletonSelfRadius", 0), Integer.getInteger("geogenesis.skeletonSelfExempt", 6 * ratio));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> windowIncoming(int rx, int rz, WindowGrid g) throws Exception {
        var method = ModelHydrologyProvider.class.getDeclaredMethod("hierIncomingSeeds", int.class, int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(PROV, rx, rz, g.pipe().nx(), g.ox(), g.oz());
    }

    private static DeterministicSkeleton.Result windowBuild(WindowGrid grid, List<Integer> incoming) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("window build");
        var p = grid.pipe();
        return DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(), p.discharge(),
                p.lakeResult().basinOutlet(), p.nx(), p.nz(), windowParams(p), incoming, p.bed(), false);
    }

    // ---- ★ 世界链（WorldDrainage）实际结果审计：独立 at/trace 复核 ----------
    //  当前 main 链的生产单元 = 世界坐标 D8 边；reach = 边的两个窗口格端点。
    //  ⇒ 生产 oracle 是 worldForProbe 的实际结果，不是 windowBuild 重建。
    //  本组函数只读实际结果 + 用【另一个】worldDrainage() 实例独立复核，
    //  待决盆地只如实登记（不当生命周期完成、不制造湖终点）。

    /**
     * 世界链独立审计计数（★ 盆内蓄水路由已批准 ⇒ 判据不再是「raw D8 严格下降」）。
     *
     * <p>{@code unmapped}/{@code unclaimed} = 边↔reach <b>双向</b>多重集配对（按 {@code (cellA,cellB)}、
     * <b>不</b>按序号）的缺边 / 多余 reach；{@code duplicated} = from 端点重复；
     * {@code rising} = 床面上坡的已发布边（只如实计数）；{@code explainedRising} = 其中由 canonical
     * 已发布盆的蓄水/溢出路由解释、且该跳<b>整数列 crest ≤ sill</b> 的（盆内蓄水合法上坡）；
     * {@code unexplainedRising} = <b>无法</b>如此解释的上坡（这才是缺陷）；
     * {@code flat} = 等床面边（平坡路由，如实计数）；{@code routingMismatch}/{@code routingUnresolved}
     * = 与生产同源 {@code WorldBasinFlow.geometricNext} 不一致 / 判不了（预算、未发布盆）；
     * {@code rawMismatch} = 与 raw D8 后继不一致（<b>仅供参考，非判据</b>）；
     * {@code cycles} = 发布图成环；{@code pending}/{@code pendingMismatch} = 待决盆地数与其状态/准入/core 自检；
     * {@code terminated} = 落海边数（仅供参考）；{@code basins} = 出现的已发布成员盆数；
     * {@code lipDefects} = 成员盆 identity/lip 自检缺陷（每盆至多计一次）；
     * {@code identityConflicts} = 同一 identity 出现内容不同的盆。
     *
     * <p><b>水量字段（覆盖 {@code water()} 全部格，不只发布边 from）</b>：
     * {@code waterChecked} = 实际审过的格数；{@code waterDefects} = 缺陷合计；
     * {@code waterUnknown} = 非 known（<b>不跳过</b>，计缺陷）；
     * {@code waterAtLeast} = {@code AT_LEAST} 格数（下界）；
     * {@code waterAtLeastBelowThreshold} = 下界未达本次需求 {@code threshold}（<b>缺陷</b>：
     * 查询需求就是 threshold，返回未达的下界等于没满足需求）；
     * {@code waterExactAtOrAbove}/{@code waterExactBelowThreshold} = {@code EXACT} 实测值达门限 / 低于门限
     * （<b>后者是合法的干格/低流量结果，只统计，不算缺陷</b>）；
     * {@code waterBelowSea} = 生产标注的落海格（{@code EXACT 0 / "below sea"}，只统计）；
     * {@code waterIdentity} = 湖的盆身份与坐标上独立 {@code routing.at()} 不符/未发布（<b>缺陷</b>）；
     * {@code waterIdentityUnresolved} = 独立解析判不了（预算/未发布 ⇒ 如实记待决，非缺陷）；
     * {@code waterContractLeak} = 非 {@code EXACT} 却报出有限 {@code discharge()}（今天按构造恒 0 ⇒
     * 只作统计，<b>不</b>单独占 PASS 位）；
     * {@code waterPublishedMissing}/{@code waterPublishedBelowThreshold} = 已发布边的 from 缺条目 /
     * 未达门限（<b>EXACT 也要求达门限</b>，比"每格"更严）。
     *
     * <p>⚠ <b>本审计不证水面梯度</b>：它只证「上坡只发生在盆内蓄水/溢出路由且该跳整数列 crest ≤ sill」，
     * 绝不声称水面单调下降，也不声称任何全局水位。
     */
    private record WorldAudit(int edges, int reaches, int unmapped, int unclaimed, int duplicated, int rising,
                              int explainedRising, int unexplainedRising, int flat, int routingMismatch,
                              int routingUnresolved, int rawMismatch, int cycles, int pending, int pendingMismatch,
                              int terminated, int basins, int lipDefects, int identityConflicts, int waterDefects,
                              int waterChecked, int waterUnknown, int waterAtLeast, int waterAtLeastBelowThreshold,
                              int waterExactAtOrAbove, int waterExactBelowThreshold, int waterBelowSea,
                              int waterIdentity, int waterIdentityUnresolved, int waterContractLeak,
                              int waterPublishedMissing, int waterPublishedBelowThreshold) { }

    /**
     * 同算法 fresh 重算（非独立实现 oracle）：{@link ModelHydrologyProvider#worldDrainage()} 每次调用都新建实例，
     * 其后继/高度缓存<b>与生产求解那份 Result 无关</b>（同参数口径、独立重算）。
     * ⚠ 不缓存（{@code PROV}/{@code GEN} 会在 [cost] 等段被替换 ⇒ 缓存会持有旧引擎的地形）；
     * 每个审计入口只调用一次并把结果传下去。
     */
    private static WorldDrainage independentDrainage() {
        return PROV.worldDrainage();
    }

    /**
     * 独立世界轨迹终态：预算耗尽只如实记 {@code TRACE_BUDGET_PENDING}（不抛、不算终点），
     * 否则返回 {@code WorldDrainage.State} 名 + 终点坐标 + 是否严格逐边下降。
     * 预算 4096 步（step=4 ⇒ 16km）远超实际链长（到海/待决即止），纯防病态输入。
     */
    private static final int WORLD_TRACE_BUDGET = 4096;

    /** 独立世界轨迹结果（{@code at="-" && state=TRACE_BUDGET_PENDING} ⇒ 无终态权威，只登记不判终点）。 */
    private record WorldTerminal(String state, String at, boolean descending) { }

    private static WorldTerminal worldTrace(WorldDrainage graph, WorldDrainage.Point origin) {
        try {
            var path = graph.trace(origin, WORLD_TRACE_BUDGET);
            var last = path.get(path.size() - 1);
            boolean descending = true;
            for (int k = 1; k < path.size(); k++)
                if (!(graph.at(path.get(k - 1)).bed() > graph.at(path.get(k)).bed())) descending = false;
            return new WorldTerminal(graph.at(last).state().name(), last.x() + "," + last.z(), descending);
        } catch (IllegalStateException e) {
            // 只把【trace 预算】当待决；地形预算耗尽/非有限等其他 IllegalStateException 必须继续上抛。
            if (e.getMessage() == null || !e.getMessage().contains("trace budget")) throw e;
            log("G42 WORLD_TRACE_BUDGET_PENDING origin=" + origin.x() + "," + origin.z()
                    + " budget=" + WORLD_TRACE_BUDGET + "（如实记待决，不当终点）");
            return new WorldTerminal("TRACE_BUDGET_PENDING", "-", true);
        }
    }

    private static WorldDrainage.Point worldPoint(SharedBoundaryDrainage.WorldCell c) {
        return new WorldDrainage.Point(c.x(), c.z());
    }

    /** 与生产同值的盆地/需求预算与气候（probe 无 Forge ⇒ 常量镜像；改生产须同步）。 */
    private static final int WORLD_BASIN_LIMIT = 131072, WORLD_FLOW_LIMIT = 32768;

    private static final LakeWaterBalance.Climate WORLD_CLIMATE = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

    /**
     * 与生产 {@code ModelHydrologyProvider.solveHier} 传给 {@code WorldBasinRouting} 的
     * 地形 lambda <b>逐位同式</b>（{@code x * invHs}，不是 {@code x / hs}——除法与乘倒数在
     * 末位上会不同，足以让 crest 比较翻转、把合法边误报成 routing 不一致）。
     */
    private static WindowOutlets.Terrain worldTerrain() {
        double invHs = 1.0 / Math.max(0.01, GEN.params().horizontalScale());
        return (x, z) -> GEN.erodedHeight(x * invHs, z * invHs);
    }

    /** 与 {@code WorldBasinFlow} 构造器同式的成河门限（成河 = 需求下界不得低于它）。 */
    private static double worldThreshold(int step) {
        double minCells = Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150"));
        return Math.ceil(minCells) * step * (double) step
                * WORLD_CLIMATE.precipitation() * WORLD_CLIMATE.runoffCoeff();
    }

    private static long cellPair(int a, int b) { return ((long) a << 32) | (b & 0xffffffffL); }

    /** 把 {@code at} 解析出的已发布 canonical 盆登记进 map；同 identity 而形态不同 ⇒ 返回 1（冲突）。 */
    private static int collectBasins(WorldBasinRouting routing,
                                     Map<WorldDrainage.Point, WorldBasinRouting.Basin> into,
                                     WorldDrainage.Point at) {
        var b = routing.at(at);
        if (b == null || !b.published()) return 0;
        var prior = into.putIfAbsent(b.identity(), b);
        if (prior == null) return 0;
        return sameBasinShape(prior, b) ? 0 : 1;
    }

    /**
     * 同一 identity 的盆是否同形（<b>忽略 floor</b>：floor 只是该盆自己的谷底高度，
     * 不是盆的身份；成员/sill/唯一 lip/盆内路由/发布性都必须逐项一致）。
     */
    private static boolean sameBasinShape(WorldBasinRouting.Basin a, WorldBasinRouting.Basin b) {
        if (a == null || b == null) return a == b;
        return a.published() == b.published() && a.identity().equals(b.identity())
                && a.members().equals(b.members())
                && Double.doubleToLongBits(a.sill()) == Double.doubleToLongBits(b.sill())
                && java.util.Objects.equals(a.outlet(), b.outlet())
                && a.next().equals(b.next());
    }

    /** {@link #basinExplains} 的判定结果：解释得了 / 解释不了（缺陷）/ 判不了（预算耗尽，非缺陷）。 */
    private static final int RISE_EXPLAINED = 1, RISE_UNEXPLAINED = 0, RISE_UNDECIDABLE = -1;

    /**
     * 与生产 {@code WorldDrainage.crest} / {@code WorldBasin.crest} / {@code WorldBasinRouting.crest}
     * <b>同式的整数列独立采样</b>：沿 {@code a→b} 的<b>全部整数列</b>（同 {@code STEP}、同插值）取床面最大值，
     * 而不是只看两个端点。
     *
     * <p>为什么必须真采样：端点 bed 低 ≠ 这一跳最高点低。两格之间可能夹着一列高于 {@code sill} 的岩脊，
     * 只看端点会把「翻过 lip 上方」的非法上坡误判成合法。{@code crest <= sill} 蕴含两端也 ≤ sill，
     * 故真 crest 是<b>严格更强</b>的判据。</p>
     *
     * <p>只采样几何（{@code routing.height}）⇒ 不读生产的 crest 缓存，独立于发布结果。</p>
     */
    private static double worldCrest(WorldBasinRouting routing, WorldDrainage.Point a,
                                     WorldDrainage.Point b, int step) {
        double h = Math.max(routing.height(a), routing.height(b));
        for (int i = 1; i < step; i++)
            h = Math.max(h, routing.height(new WorldDrainage.Point(
                    a.x() + (b.x() - a.x()) * i / step, a.z() + (b.z() - a.z()) * i / step)));
        return h;
    }

    /**
     * 上坡边的合法解释：某个<b>已发布</b>成员盆的盆内路由 / 溢出走廊（含 lip 首步）就是这一跳，
     * 且该跳的<b>整数列 crest</b>（{@link #worldCrest}，同 STEP 全列采样）不高于该盆 {@code sill}。
     * 水不可能在本盆内被抬到自己的 lip 之上 ⇒ {@code crest ≤ sill} 是物理约束，不是宽松阈值。
     *
     * <p>为什么按【已登记的盆】而不是只看 {@code routing.at(from)}：溢出走廊点（lip.outer 之后、
     * 仍属本盆来水的那几步）不是成员，{@code at()} 会解析成下游的盆，只看 {@code at(from)}
     * 会把合法的抬升步误判成缺陷。故两路都认。别处上坡仍算缺陷。
     *
     * <p>三态：审计侧地形缓存预算耗尽（{@code IllegalStateException}）⇒ {@link #RISE_UNDECIDABLE}，
     * 如实计入 {@code routingUnresolved}（<b>不</b>记缺陷，也<b>不</b>当已解释）。
     */
    private static int basinExplains(Map<WorldDrainage.Point, WorldBasinRouting.Basin> basins,
                                     WorldBasinRouting routing,
                                     WorldDrainage.Point from, WorldDrainage.Point to) {
        int step = hp().step();
        try {
            for (var b : basins.values()) {
                var route = b.routeFrom(from);
                if (route == null || !route.equals(to)) continue;
                if (worldCrest(routing, from, to, step) <= b.sill()) return RISE_EXPLAINED;
            }
            var own = routing.at(from);
            if (own == null || !own.published()) return RISE_UNEXPLAINED;
            var route = own.routeFrom(from);
            return route != null && route.equals(to) && worldCrest(routing, from, to, step) <= own.sill()
                    ? RISE_EXPLAINED : RISE_UNEXPLAINED;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) {
            return RISE_UNDECIDABLE;
        } catch (IllegalStateException budget) {
            return RISE_UNDECIDABLE;          // 审计侧地形缓存/几何预算耗尽 ≠ 生产缺陷
        }
    }

    /**
     * 成员盆自检：已发布 / identity 非空 / lip 三点齐备且 crest 等于 sill /
     * lip 的 inner 在成员内、outer 在成员外 / {@code inner→outer} 就是该盆路由的第一步 /
     * 每个成员都有盆内后继。返回 1 表示一处缺陷。
     */
    private static int basinLipDefect(WorldBasinRouting.Basin b) {
        if (!b.published() || b.identity() == null || b.members().isEmpty()) return 1;
        var lip = b.outlet();
        if (lip == null || lip.inner() == null || lip.outer() == null || !Double.isFinite(lip.crest())) return 1;
        if (!b.members().contains(lip.inner()) || b.members().contains(lip.outer())) return 1;
        if (Double.doubleToLongBits(lip.crest()) != Double.doubleToLongBits(b.sill())) return 1;
        if (!lip.outer().equals(b.routeFrom(lip.inner()))) return 1;
        for (var m : b.members()) if (b.routeFrom(m) == null) return 1;
        return 0;
    }

    /**
     * 世界链的<b>全量</b>水量审计（⚠ 生产输出的自洽自检，<b>不是</b>独立水量 oracle）。
     *
     * <p><b>覆盖范围 = {@code Region.water()} 里的每一格</b>，不只是被发布边的 {@code from}。
     * 判据（★ 与"每格都必须达门限"这种过严口径不同）：</p>
     * <ol>
     *   <li><b>每格</b>必须 {@code known()}（{@code UNKNOWN} = 下界缺失 ⇒ <b>计缺陷，绝不跳过</b>）；</li>
     *   <li>该格的 {@code kind} 决定门限含义：
     *     <ul>
     *       <li>{@code EXACT}：是<b>实测值</b>。低于 {@code threshold} 是<b>合法</b>结果
     *           （干格 / 低流量），<b>只统计</b>，<b>不算缺陷</b>；</li>
     *       <li>{@code AT_LEAST}：是<b>下界</b>，而本次查询的需求就是 {@code threshold}
     *           （{@code WorldBasinFlow.region} 用 {@code demand(p, threshold)}）
     *           ⇒ 下界必须 {@code reaches(threshold)}，否则等于"没达到需求却返回了下界" ⇒ 缺陷。</li>
     *     </ul>
     *   </li>
     *   <li>若该格被判为湖，其盆身份<b>必须由坐标上新鲜的独立 {@code routing.at()} 核对</b>
     *       （同一 identity 且同形）—— <b>不</b>用 {@code Water} 自带的盆当 oracle，也<b>不</b>用
     *       "发布边触达过"当准入条件（那会错杀只有水量条目、没有发布边的未触达盆）。</li>
     *   <li>语义契约：非 {@code EXACT} 不得报出有限 {@code discharge()}（今天按构造恒真 ⇒ 只统计）。</li>
     * </ol>
     * <p>另加<b>发布必要性</b>（比"每格"更强的单点要求）：每条已发布边的 {@code from}
     * <b>必须有水量条目且 {@code reaches(threshold)}</b>（无论 EXACT 还是下界）。</p>
     */
    private record WorldWater(int checked, int defects, int unknown, int atLeast, int atLeastBelowThreshold,
                              int exactAtOrAbove, int exactBelowThreshold, int belowSea, int identity,
                              int contractLeak, int publishedMissing, int publishedBelowThreshold,
                              int identityUnresolved) {
        static final WorldWater BAD = new WorldWater(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static final String BELOW_SEA_REASON = "below sea";

    /**
     * 全量水量审计的输入抽象：{@code water()} 里<b>每一格</b>，加上<b>独立</b>盆解析器。
     * 抽象出来是为了让口径本身可被纯合成负控覆盖（不需要地形/整条生产链）。
     */
    private interface WaterSource {
        Map<WorldDrainage.Point, WorldBasinFlow.Water> water();

        /** 从坐标<b>独立</b>解析 canonical 盆（可抛 {@code Unresolved}/{@code RoutingBudget}）。 */
        WorldBasinRouting.Basin basinAt(WorldDrainage.Point point);

        /** 把独立解析出的盆登记进待审成员集（必要登记：其 lip/identity 也要被审）。 */
        default void register(WorldBasinRouting.Basin basin) { }
    }

    private static WaterSource regionWater(WorldBasinFlow.Region water, WorldBasinRouting routing,
                                           Map<WorldDrainage.Point, WorldBasinRouting.Basin> registry) {
        if (water == null) return null;
        return new WaterSource() {
            @Override public Map<WorldDrainage.Point, WorldBasinFlow.Water> water() { return water.water(); }
            @Override public WorldBasinRouting.Basin basinAt(WorldDrainage.Point point) {
                return routing.at(point);
            }
            @Override public void register(WorldBasinRouting.Basin basin) {
                if (basin != null && basin.published()) registry.putIfAbsent(basin.identity(), basin);
            }
        };
    }

    private static WorldWater worldWater(WaterSource source, List<WorldDrainage.Edge> published,
                                         double threshold) {
        if (source == null) return WorldWater.BAD;
        var all = source.water();
        if (all == null) return WorldWater.BAD;
        int checked = 0, defects = 0, unknown = 0, atLeast = 0, atLeastBelow = 0, exactAbove = 0, exactBelow = 0;
        int belowSea = 0, identity = 0, contractLeak = 0, publishedMissing = 0, publishedBelow = 0;
        int identityUnresolved = 0;
        for (var entry : all.entrySet()) {
            checked++;
            var w = entry.getValue();
            // ① known 是硬前提：UNKNOWN 不能当「无信息」跳过（那会让审计靠缺失变绿）
            if (w == null || !w.known()) { unknown++; defects++; continue; }
            boolean exact = w.amount().exact();
            if (!exact) {
                atLeast++;
                // ④ 语义契约守卫（今天按构造恒真 ⇒ 只统计；非 EXACT 却报有限出流即缺陷）
                if (!Double.isNaN(w.discharge())) { contractLeak++; defects++; }
                // ② 下界必须达本次查询的需求 threshold
                if (!w.reaches(threshold)) { atLeastBelow++; defects++; }
            } else if (w.reaches(threshold)) {
                exactAbove++;
            } else {
                // ② EXACT 低于门限 = 合法干格/低流量 ⇒ 只统计，不算缺陷
                exactBelow++;
            }
            if (exact && w.amount().bound() == 0 && BELOW_SEA_REASON.equals(w.reason())) belowSea++;
            // ③ 湖身份：从坐标独立核对（不用 Water 自带的盆当 oracle）
            var claimed = w.basin();
            if (claimed != null) {
                try {
                    var independent = source.basinAt(entry.getKey());
                    if (independent == null || !independent.published()
                            || !sameBasinShape(independent, claimed)) {
                        identity++; defects++;
                    } else {
                        source.register(independent);      // 必要登记：lip/identity 也要被审
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) {
                    identityUnresolved++;              // 判不了 ⇒ 如实记待决，不记缺陷
                } catch (IllegalStateException budget) {
                    identityUnresolved++;              // 审计侧地形预算耗尽 ≠ 生产缺陷
                }
            }
        }
        // 发布必要性：每条已发布边的 from 必须存在且达门限（EXACT 也一样）
        for (var edge : published) {
            var w = all.get(edge.from());
            if (w == null || !w.known()) { publishedMissing++; defects++; continue; }
            if (!w.reaches(threshold)) { publishedBelow++; defects++; }
        }
        return new WorldWater(checked, defects, unknown, atLeast, atLeastBelow, exactAbove, exactBelow,
                belowSea, identity, contractLeak, publishedMissing, publishedBelow, identityUnresolved);
    }

    // ---- ★ 水量口径的合成负控（纯内存，不碰地形/生产） --------------------------
    /**
     * 用水量口径本身做<b>有限的合成负控</b>：只证明「口径按预期判」，不证明生产数据。
     * <p>① {@code EXACT} 低于 T ⇒ <b>必须通过</b>（合法干格，只统计 {@code exactBelow}）；
     * ② {@code UNKNOWN}（全量）⇒ <b>必须失败</b>（即使它不在任何发布边上）；
     * ③ 非 {@code EXACT}（{@code AT_LEAST}）低于 T ⇒ <b>必须失败</b>（需求就是 T）；
     * ④ 发布边 {@code from} 低于 T ⇒ <b>必须失败</b>（即使它是 {@code EXACT}）；
     * ⑤ 湖身份：独立解析与 {@code Water} 自称不符 ⇒ 必须失败；一致且未发布盆也须被登记。</p>
     * <p>这些用例只喂 {@code worldWater}（纯函数 + 注入式 {@code WaterSource}），
     * 不依赖地形、不依赖生产 region，故可确定复现。</p>
     */
    private static void worldWaterGaugeSynthetic() {
        log("\n=== [worldWater-gauge] 水量口径合成负控（EXACT低T应过 / UNKNOWN应失败 / 非exact低T失败 / 发布低T失败）===");
        final double t = 100;
        var p0 = new WorldDrainage.Point(0, 0);
        var p1 = new WorldDrainage.Point(4, 0);
        var p2 = new WorldDrainage.Point(4, 4);
        var exhale = new ArrayList<String>();

        // ① EXACT 低于 T：合法干格 ⇒ defects 必须为 0，且如实计入 exactBelow
        var dry = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        dry.put(p0, exact(37, null, ""));
        var dryOut = worldWater(fixed(dry), List.of(), t);
        chk(dryOut.defects() == 0 && dryOut.exactBelowThreshold() == 1 && dryOut.checked() == 1,
                "负控①：EXACT 低于 T 的干格必须通过（只计 waterExactBelowThreshold，不算缺陷）",
                "defects=" + dryOut.defects() + " exactBelow=" + dryOut.exactBelowThreshold()
                        + " checked=" + dryOut.checked());

        // ①b EXACT 达 T：也必须通过，计入 exactAtOrAbove
        var wet = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        wet.put(p0, exact(150, null, ""));
        var wetOut = worldWater(fixed(wet), List.of(), t);
        chk(wetOut.defects() == 0 && wetOut.exactAtOrAbove() == 1,
                "负控①b：EXACT 达 T 必须通过并计入 waterExactAtOrAbove",
                "defects=" + wetOut.defects() + " exactAbove=" + wetOut.exactAtOrAbove());

        // ② UNKNOWN 全量：即使不在任何发布边上，也必须失败
        var unknown = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        unknown.put(p0, exact(150, null, ""));
        unknown.put(p1, unknownWater("demand cells budget"));
        var unknownOut = worldWater(fixed(unknown), List.of(), t);
        chk(unknownOut.defects() >= 1 && unknownOut.unknown() == 1,
                "负控②：全量里出现 UNKNOWN（不在发布边上也一样）必须失败，不得跳过",
                "defects=" + unknownOut.defects() + " unknown=" + unknownOut.unknown());

        // ③ 非 EXACT 低于 T：需求本身就是 T ⇒ 必须失败
        var atLeastLow = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        atLeastLow.put(p0, atLeast(40));
        var atLeastLowOut = worldWater(fixed(atLeastLow), List.of(), t);
        chk(atLeastLowOut.defects() >= 1 && atLeastLowOut.atLeastBelowThreshold() == 1,
                "负控③：非 EXACT（AT_LEAST）低于需求 T 必须失败",
                "defects=" + atLeastLowOut.defects()
                        + " atLeastBelowT=" + atLeastLowOut.atLeastBelowThreshold());

        // ③b 非 EXACT 达 T：必须通过
        var atLeastHigh = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        atLeastHigh.put(p0, atLeast(400));
        var atLeastHighOut = worldWater(fixed(atLeastHigh), List.of(), t);
        chk(atLeastHighOut.defects() == 0 && atLeastHighOut.atLeast() == 1,
                "负控③b：非 EXACT 达 T 必须通过并计入 waterAtLeast",
                "defects=" + atLeastHighOut.defects() + " atLeast=" + atLeastHighOut.atLeast());

        // ④ 发布边 from 低于 T：EXACT 也不行 ⇒ 必须失败
        var publishedLow = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        publishedLow.put(p0, exact(37, null, ""));
        var publishedLowOut = worldWater(fixed(publishedLow), List.of(new WorldDrainage.Edge(p0, p1)), t);
        chk(publishedLowOut.defects() >= 1 && publishedLowOut.publishedBelowThreshold() == 1,
                "负控④：发布边 from 低于 T 必须失败（EXACT 也不例外）",
                "defects=" + publishedLowOut.defects()
                        + " publishedBelowT=" + publishedLowOut.publishedBelowThreshold());

        // ④b 发布边 from 缺条目 ⇒ 必须失败
        var publishedMissing = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        publishedMissing.put(p1, exact(150, null, ""));
        var publishedMissingOut = worldWater(fixed(publishedMissing),
                List.of(new WorldDrainage.Edge(p0, p1)), t);
        chk(publishedMissingOut.defects() >= 1 && publishedMissingOut.publishedMissing() == 1,
                "负控④b：发布边 from 没有水量条目必须失败",
                "defects=" + publishedMissingOut.defects()
                        + " publishedMissing=" + publishedMissingOut.publishedMissing());

        // ⑤ 湖身份：独立解析与 Water 自称不符 ⇒ 必须失败
        var bogusClaim = new WorldBasinRouting.Basin(WorldBasinRouting.State.PUBLISHED,
                WorldBasin.State.UNIQUE_EXIT, p0, 1, 2, List.of(p0), null, Map.of(), List.of(),
                p2, false, "");
        var claimedWater = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinFlow.Water>();
        claimedWater.put(p0, exact(150, bogusClaim, ""));
        var mismatchOut = worldWater(perCoordinate(claimedWater, p -> null), List.of(), t);
        chk(mismatchOut.defects() >= 1 && mismatchOut.identity() == 1,
                "负控⑤：湖身份与坐标上独立 routing.at() 不符（或未发布）必须失败 —— 不用 Water 自称当 oracle",
                "defects=" + mismatchOut.defects() + " identity=" + mismatchOut.identity());

        log("G31 WORLD_WATER_GAUGE cases=7 GAUGE_ONLY_NOT_PRODUCTION_EVIDENCE=true"
                + " EXACT_BELOW_T_ALLOWED=true AT_LEAST_MUST_REACH_T=true"
                + " PUBLISHED_FROM_MUST_REACH_T=true UNKNOWN_NEVER_SKIPPED=true");
    }

    /** {@code Water} 构造便捷（探针合成用）：只表达 {@code amount} 语义。 */
    private static WorldBasinFlow.Water exact(double q, WorldBasinRouting.Basin basin, String reason) {
        return new WorldBasinFlow.Water(new DemandFlow.Amount(DemandFlow.Kind.EXACT, q, ""),
                Double.NaN, basin, reason);
    }

    private static WorldBasinFlow.Water atLeast(double lower) {
        return new WorldBasinFlow.Water(new DemandFlow.Amount(DemandFlow.Kind.AT_LEAST, lower, ""),
                Double.NaN, null, "");
    }

    private static WorldBasinFlow.Water unknownWater(String reason) {
        return new WorldBasinFlow.Water(new DemandFlow.Amount(DemandFlow.Kind.UNKNOWN, Double.NaN, reason),
                Double.NaN, null, reason);
    }

    private static WaterSource fixed(Map<WorldDrainage.Point, WorldBasinFlow.Water> water) {
        return perCoordinate(water, p -> null);
    }

    /** 注入式 {@code WaterSource}：{@code basinAt} 由调用方决定（合成负控用）。 */
    private static WaterSource perCoordinate(Map<WorldDrainage.Point, WorldBasinFlow.Water> water,
                                             java.util.function.Function<WorldDrainage.Point,
                                                     WorldBasinRouting.Basin> resolver) {
        return new WaterSource() {
            @Override public Map<WorldDrainage.Point, WorldBasinFlow.Water> water() { return water; }
            @Override public WorldBasinRouting.Basin basinAt(WorldDrainage.Point point) {
                return resolver.apply(point);
            }
        };
    }

    /** 世界链一次审计用的三件套（同地形/同参数的独立重算 + 已登记成员盆）。 */
    private record WorldChain(WorldDrainage graph, WorldBasinRouting routing, WorldBasinFlow flow,
                              Map<WorldDrainage.Point, WorldBasinRouting.Basin> basins) { }

    /** 与生产同值的 canonical routing（新实例 ⇒ 与生产那份 Result 的后继缓存无关，独立重算）。 */
    private static WorldBasinRouting worldRouting(WorldDrainage graph, int step) {
        return new WorldBasinRouting(graph, worldTerrain(), step, WORLD_BASIN_LIMIT);
    }

    /** 与生产同值的需求求解器（只用于取 {@code geometricNext}，即发布边的规范后继）。 */
    private static WorldBasinFlow worldFlow(WorldDrainage graph, int step) {
        return new WorldBasinFlow(graph, worldRouting(graph, step), WORLD_CLIMATE, step, WORLD_FLOW_LIMIT);
    }

    /**
     * 用与生产同值的参数在同一地形上独立重建 canonical routing，并<b>只</b>登记被发布边触及到的盆
     * （不隐式扫全世界当审计成本）。
     */
    private static WorldChain worldChain(WorldDrainage graph, int step, WorldDrainage.Result world) {
        var routing = worldRouting(graph, step);
        var flow = new WorldBasinFlow(graph, routing, WORLD_CLIMATE, step, WORLD_FLOW_LIMIT);
        var basins = new java.util.LinkedHashMap<WorldDrainage.Point, WorldBasinRouting.Basin>();
        for (var e : world.edges()) for (var p : List.of(e.from(), e.to())) {
            try { collectBasins(routing, basins, p); }
            catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) { }
        }
        return new WorldChain(graph, routing, flow, basins);
    }

    /**
     * 世界链实际边独立复核（<b>盆内蓄水路由已批准</b> ⇒ 不再要求 raw D8 严格下降）：
     * ① 边↔reach 一一对应（按 {@code (cellA,cellB)} 多重集配对）；② 与生产同源
     * {@code WorldBasinFlow.geometricNext} 逐边一致（raw D8 只作参考）；③ from 无重复、发布图无环；
     * ④ 上坡只允许出现在已发布盆的盆内路由/lip 且该跳 ≤ sill（{@code unexplainedRising} 才是缺陷）；
     * ⑤ 成员盆 identity 唯一且 lip 齐备；⑥ 发布端必要 {@code Water} 皆 known 且达门限，
     * 且下界不被当精确流量（契约守卫）。
     * <p>⚠ 不证任何水面梯度、不证全局水位。
     * <p>★ {@code chain.basins()} 是<b>可变</b>登记表（本函数会往里写）⇒ 调用方若拿同一 chain 反复审计
     * 不同变异，必须各给一份 {@code basins} 副本（见 {@link #negativeControl}）。
     */
    private static WorldAudit auditWorldEdges(WorldChain chain, WorldDrainage.Result world,
                                              WorldBasinFlow.Region water, List<RiverCenterlines.Reach> reaches,
                                              WindowGrid grid, int rx, int rz, int tract) {
        var graph = chain.graph();
        var routing = chain.routing();
        var flow = chain.flow();
        var basins = chain.basins();
        var edges = world.edges();
        // ★ 用生产 WorldDrainage/WorldBasinRouting 的 STEP（= hp().step()），不是窗口的 cellBlocks：
        //   二者当前同值，但一旦某处改窗口步长，用错步长会让 canonical 重算与发布边系统性不一致。
        int step = hp().step();
        double threshold = worldThreshold(step);
        // ① 一一对应：多重集配对（无序号依赖，也不因发布顺序变化而误判）
        var expected = new java.util.HashMap<Long,Integer>();
        int unclaimed = 0;
        for (var r : reaches) {
            if (r.cell.length == 2) expected.merge(cellPair(r.cell[0], r.cell[1]), 1, Integer::sum);
            else unclaimed++;
        }
        int unmapped = 0;
        for (var e : edges) {
            long key = cellPair(grid.productionIndex(e.from()), grid.productionIndex(e.to()));
            Integer n = expected.get(key);
            if (n == null || n == 0) unmapped++; else expected.put(key, n - 1);
        }
        for (int left : expected.values()) unclaimed += left;
        // ② canonical routing：geometricNext 必须与发布边逐条一致；raw D8 仅作参考
        int duplicated = 0, rising = 0, flat = 0, terminated = 0;
        int routingMismatch = 0, routingUnresolved = 0, rawMismatch = 0, identityConflicts = 0;
        var froms = new java.util.HashSet<WorldDrainage.Point>();
        var risingEdges = new java.util.ArrayList<WorldDrainage.Edge>();
        // 只检查实际发布图；跨裁片出口保留 external，不能隐式采遍全世界当审计成本。
        var nextPublished = new java.util.HashMap<WorldDrainage.Point,WorldDrainage.Point>();
        for (var edge : edges) {
            var from = edge.from(); var to = edge.to();
            if (!froms.add(from)) duplicated++;
            nextPublished.put(from, to);
            double bedFrom = graph.at(from).bed(), bedTo = graph.at(to).bed();
            if (bedTo > bedFrom) { rising++; risingEdges.add(edge); }
            else if (bedTo == bedFrom) flat++;
            if (graph.at(to).state() == WorldDrainage.State.BELOW_SEA) terminated++;
            var raw = graph.at(from);
            if (raw.next() == null || !raw.next().equals(to)) rawMismatch++;
            try {
                var canonical = flow.geometricNext(from);
                if (canonical == null || !canonical.equals(to)) routingMismatch++;
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) {
                routingUnresolved++;
            }
            for (var point : List.of(from, to)) {
                try {
                    identityConflicts += collectBasins(routing, basins, point);
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) {
                    routingUnresolved++;
                }
            }
        }
        // ③ 上坡必须由【已发布盆】的盆内蓄水/溢出路由解释，且该跳 ≤ sill
        int explainedN = 0, unexplainedN = 0;
        for (var edge : risingEdges) {
            switch (basinExplains(basins, routing, edge.from(), edge.to())) {
                case RISE_EXPLAINED -> explainedN++;
                case RISE_UNDECIDABLE -> routingUnresolved++;
                default -> {
                    unexplainedN++;
                    log("G31 WORLD_UNEXPLAINED_RISE region=" + rx + "," + rz + " edge=" + edge.from()
                            + "->" + edge.to() + "（不属任何已发布盆的盆内路由 ⇒ 缺陷）");
                }
            }
        }
        // ⑤ 水量：覆盖 water() 的【每一格】；湖身份从坐标独立 routing.at() 核对并【必要登记】。
        //    必须在盆登记之后跑，且它的登记会再扩 basins ⇒ lip 自检要放在它之后重新算。
        var waterAudit = worldWater(regionWater(water, routing, basins), edges, threshold);
        // ⑥ 成员盆 lip / identity 自检（含 ⑤ 新登记的、只有水量条目而没有发布边的盆）
        int lipDefects = 0;
        for (var b : basins.values()) lipDefects += basinLipDefect(b);
        int cycles = 0; var color = new java.util.HashMap<WorldDrainage.Point,Integer>();
        for (var edge : edges) {
            var at = edge.from(); var path = new java.util.ArrayList<WorldDrainage.Point>();
            while (at != null && !color.containsKey(at)) { color.put(at, 1); path.add(at); at = nextPublished.get(at); }
            if (at != null && color.get(at) == 1) cycles++;      // 回到本次行走路径 ⇒ 成环
            for (var node : path) color.put(node, 2);
        }
        int pendingMismatch = 0;
        for (var q : world.pendingBasins()) {
            // 与 WorldDrainage.region 的 `inside()` 谓词一致：待决盆地必须落在【本区 core】，不能是窗缘残留。
            boolean inCore = Math.floorDiv(q.x(), tract) == rx && Math.floorDiv(q.z(), tract) == rz;
            if (graph.at(q).state() != WorldDrainage.State.BASIN_PENDING || !graph.admission(q).channel()
                    || !inCore) pendingMismatch++;
        }
        return new WorldAudit(edges.size(), reaches.size(), unmapped, unclaimed, duplicated, rising,
                explainedN, unexplainedN, flat, routingMismatch, routingUnresolved, rawMismatch, cycles,
                world.pendingBasins().size(), pendingMismatch, terminated, basins.size(), lipDefects,
                identityConflicts, waterAudit.defects(), waterAudit.checked(), waterAudit.unknown(),
                waterAudit.atLeast(), waterAudit.atLeastBelowThreshold(), waterAudit.exactAtOrAbove(),
                waterAudit.exactBelowThreshold(), waterAudit.belowSea(), waterAudit.identity(),
                waterAudit.identityUnresolved(), waterAudit.contractLeak(), waterAudit.publishedMissing(),
                waterAudit.publishedBelowThreshold());
    }

    /**
     * 世界链实际边全量落盘 + 判据（一一对应 / canonical routing / 无重复无环 / 盆内上坡可解释 /
     * 盆 identity 与 lip / 必要 Water known）。<b>不</b>以 raw D8 严格下降为判据。
     */
    private static void shapeAuditWorld(Path dir, int rx, int rz, int cx, int cz, WindowGrid grid,
                                        WorldDrainage.Result world, WorldBasinFlow.Region water) throws Exception {
        var graph = independentDrainage();
        var reaches = PROV.reachesForProbe(cx, cz);
        int step = hp().step();          // 生产 WorldDrainage/WorldBasinRouting 的 STEP
        var chain = worldChain(graph, step, world);
        var audit = auditWorldEdges(chain, world, water, reaches, grid, rx, rz, hp().tract());
        var routing = chain.routing();
        var flow = chain.flow();
        var basins = chain.basins();
        var rows = new StringBuilder("edge,fromX,fromZ,toX,toZ,bedFrom,bedTo,stateTo,cellA,cellB,"
                + "reachPair,canonicalNext,rawNext,rising,explainedRising,waterKnown,waterAmountKind,basinAtFrom\n");
        var reachPairs = new java.util.HashSet<Long>();
        for (var r : reaches) if (r.cell.length == 2) reachPairs.add(cellPair(r.cell[0], r.cell[1]));
        for (int i = 0; i < world.edges().size(); i++) {
            var from = world.edges().get(i).from(); var to = world.edges().get(i).to();
            int ka = grid.productionIndex(from), kb = grid.productionIndex(to);
            double bedFrom = graph.at(from).bed(), bedTo = graph.at(to).bed();
            String canonical = "-";
            try {
                var next = flow.geometricNext(from);
                canonical = next == null ? "-" : next.x() + "," + next.z();
            } catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) {
                canonical = "UNRESOLVED:" + pending.getMessage();
            }
            var rawNext = graph.at(from).next();
            var w = water == null ? null : water.water().get(from);
            var owned = w == null || w.basin() == null ? "-" : w.basin().identity().x() + "," + w.basin().identity().z();
            rows.append(i).append(',').append(from.x()).append(',').append(from.z()).append(',')
                    .append(to.x()).append(',').append(to.z()).append(',').append(bedFrom).append(',')
                    .append(bedTo).append(',').append(graph.at(to).state()).append(',')
                    .append(ka).append(',').append(kb).append(',')
                    .append(reachPairs.contains(cellPair(ka, kb))).append(',')
                    .append(canonical).append(',')
                    .append(rawNext == null ? "-" : rawNext.x() + "," + rawNext.z()).append(',')
                    .append(bedTo > bedFrom).append(',')
                    .append(bedTo > bedFrom && basinExplains(basins, routing, from, to) == RISE_EXPLAINED).append(',')
                    .append(w != null && w.known()).append(',')
                    .append(w == null ? "-" : w.amount().kind())
                    .append(',').append(owned).append('\n');
        }
        Files.writeString(dir.resolve("world-edges.csv"), rows);
        var basinRows = new StringBuilder("identityX,identityZ,membersX,membersZ,sill,floor,"
                + "lipInnerX,lipInnerZ,lipOuterX,lipOuterZ,lipCrest,nextSink,toSea,lipDefect,routeSize\n");
        for (var b : basins.values()) {
            var lip = b.outlet();
            basinRows.append(b.identity().x()).append(',').append(b.identity().z()).append(',')
                    .append(b.members().get(0).x()).append(',').append(b.members().get(0).z()).append(',')
                    .append(b.sill()).append(',').append(b.floor()).append(',')
                    .append(lip == null || lip.inner() == null ? "-" : lip.inner().x()).append(',')
                    .append(lip == null || lip.inner() == null ? "-" : lip.inner().z()).append(',')
                    .append(lip == null || lip.outer() == null ? "-" : lip.outer().x()).append(',')
                    .append(lip == null || lip.outer() == null ? "-" : lip.outer().z()).append(',')
                    .append(lip == null ? "-" : lip.crest()).append(',')
                    .append(b.nextSink() == null ? "-" : b.nextSink().x() + "," + b.nextSink().z()).append(',')
                    .append(b.toSea()).append(',').append(basinLipDefect(b) != 0)
                    .append(',').append(b.members().size()).append('\n');
        }
        Files.writeString(dir.resolve("world-basins.csv"), basinRows);
        var pending = new StringBuilder("x,z,state,channel,inCore\n");
        for (var q : world.pendingBasins()) pending.append(q.x()).append(',').append(q.z()).append(',')
                .append(graph.at(q).state()).append(',').append(graph.admission(q).channel()).append(',')
                .append(Math.floorDiv(q.x(), hp().tract()) == rx && Math.floorDiv(q.z(), hp().tract()) == rz).append('\n');
        Files.writeString(dir.resolve("world-pending.csv"), pending);
        log("G31 WORLD region=" + rx + "," + rz + " edges=" + audit.edges() + " reaches=" + audit.reaches()
                + " unmapped=" + audit.unmapped() + " unclaimed=" + audit.unclaimed()
                + " duplicated=" + audit.duplicated() + " cycles=" + audit.cycles()
                + " rising=" + audit.rising() + " explainedRising=" + audit.explainedRising()
                + " unexplainedRising=" + audit.unexplainedRising() + " flat=" + audit.flat()
                + " routingMismatch=" + audit.routingMismatch() + " routingUnresolved=" + audit.routingUnresolved()
                + " rawMismatch(refOnly)=" + audit.rawMismatch()
                + " basins=" + audit.basins() + " lipDefects=" + audit.lipDefects()
                + " identityConflicts=" + audit.identityConflicts() + " waterChecked=" + audit.waterChecked()
                + " waterDefects=" + audit.waterDefects() + " waterUnknown=" + audit.waterUnknown()
                + " waterAtLeast=" + audit.waterAtLeast()
                + " waterAtLeastBelowT=" + audit.waterAtLeastBelowThreshold()
                + " waterExactAtOrAboveT=" + audit.waterExactAtOrAbove()
                + " waterExactBelowT(allowed)=" + audit.waterExactBelowThreshold()
                + " waterBelowSea(stat)=" + audit.waterBelowSea()
                + " waterIdentity=" + audit.waterIdentity()
                + " waterIdentityUnresolved=" + audit.waterIdentityUnresolved()
                + " waterContractLeak=" + audit.waterContractLeak()
                + " waterPublishedMissing=" + audit.waterPublishedMissing()
                + " waterPublishedBelowT=" + audit.waterPublishedBelowThreshold()
                + " pending=" + audit.pending() + " pendingMismatch=" + audit.pendingMismatch()
                + " belowSea=" + audit.terminated()
                + " reachesAreEdgeEndpoints=true pendingNotTerminal=true"
                + " WATER_AUDIT_ALL_CELLS_KNOWN=true"
                + " WATER_DEMAND_ONLY_FOR_AT_LEAST_AND_PUBLISHED_FROM=true"
                + " WATER_SURFACE_GRADIENT_NOT_PROVEN=true");
        chk(audit.reaches() == audit.edges(),
                "世界链：每条实际边恰一条 reach（全量映射，不抽样）",
                "edges=" + audit.edges() + " reaches=" + audit.reaches());
        chk(audit.unmapped() == 0 && audit.unclaimed() == 0,
                "世界链：边↔reach 按 (cellA,cellB) 一一对应（无缺边、无多余 reach，不依赖发布顺序）",
                "unmapped=" + audit.unmapped() + " unclaimed=" + audit.unclaimed());
        chk(audit.duplicated() == 0, "实际边 from 端点互异（无重复边）", "duplicated=" + audit.duplicated());
        chk(audit.unexplainedRising() == 0,
                "世界链：上坡只出现在已发布盆的盆内蓄水/溢出路由且该跳【整数列 crest】≤ sill"
                        + "（全列采样，不是只看两端 bed）",
                "unexplainedRising=" + audit.unexplainedRising() + " rising=" + audit.rising()
                        + " explained=" + audit.explainedRising() + " flat=" + audit.flat());
        chk(audit.routingMismatch() == 0 && audit.routingUnresolved() == 0,
                "世界链：发布边逐条等于真实 canonical routing 后继（独立 WorldBasinFlow.geometricNext；raw D8 仅参考）",
                "mismatch=" + audit.routingMismatch() + " unresolved=" + audit.routingUnresolved()
                        + " rawMismatch(refOnly)=" + audit.rawMismatch());
        chk(audit.cycles() == 0, "实际边链不得成环", "cycles=" + audit.cycles());
        chk(audit.lipDefects() == 0 && audit.identityConflicts() == 0,
                "成员盆拥有唯一 identity 且 lip 齐备（inner 在内、outer 在外、crest=sill、inner→outer 即首步）",
                "basins=" + audit.basins() + " lipDefects=" + audit.lipDefects()
                        + " identityConflicts=" + audit.identityConflicts());
        chk(audit.waterChecked() > 0,
                "世界链：水量审计覆盖 water() 全部格且非空转（不只发布边 from）",
                "waterChecked=" + audit.waterChecked());
        chk(audit.waterDefects() == 0,
                "世界链：water() 每格皆 known；AT_LEAST 皆达本次需求 threshold；湖盆身份由坐标独立 routing.at()"
                        + " 核对同形；每条发布边 from 有条目且达门限"
                        + "（EXACT 低于 threshold 是合法干格/低流量，只统计不记缺陷）",
                "waterChecked=" + audit.waterChecked() + " waterDefects=" + audit.waterDefects()
                        + " waterUnknown=" + audit.waterUnknown()
                        + " waterAtLeastBelowT=" + audit.waterAtLeastBelowThreshold()
                        + " waterIdentity=" + audit.waterIdentity()
                        + " waterIdentityUnresolved=" + audit.waterIdentityUnresolved()
                        + " waterContractLeak=" + audit.waterContractLeak()
                        + " waterPublishedMissing=" + audit.waterPublishedMissing()
                        + " waterPublishedBelowT=" + audit.waterPublishedBelowThreshold());
        chk(audit.pendingMismatch() == 0,
                "待决盆地如实为 BASIN_PENDING、在 core 内且有准入（不完成生命周期、不制造湖终点）",
                "pendingMismatch=" + audit.pendingMismatch());
        if (audit.edges() == 0) log("G31 WORLD_VACUOUS region=" + rx + "," + rz
                + "（本区无准入边 ⇒ 上述映射等式为空真，不声称为非空转证据）");
        if (rx == -2 && rz == 1) chk(audit.edges() > 0,
                "梳齿区（-2,1）世界链实际准入边非空转（否则本次形状审计为空真）", "edges=0");
        if (rx == -2 && rz == 1) worldHistoricCheck(graph, grid, world);
        negativeControl(chain, world, water, reaches, grid, rx, rz, "G31 WORLD_NEG_CONTROL");
        Files.writeString(Path.of("..","debug-agent.log").toAbsolutePath(),LOG.toString(),java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * 负控：对<b>实际</b>边做两种最小篡改，同一审计必须失败 ⇒ 证明判据不是自证。
     * <p>① <b>删</b>一条实际边 ⇒ 边↔reach 多重集配对必须失败（删边的 {@code (cellA,cellB)} 恰好重复时
     * 仍会以 {@code unclaimed} 暴露）；② <b>改向</b>一条实际边的终点到另一个格点邻居
     * （不得等于原终点、且不得造成自环）⇒ 一一对应或 canonical 后继判据必须失败。
     * <p>只改内存副本、不碰生产产物；若本区没有边则如实记 SKIPPED（不假 PASS，也不制造 FAIL）。
     * <p>只在 shape-audit 跑（trace-loops 复用同一 {@code auditWorldEdges}，重复跑会把该段成本抬到 3 倍）。
     */
    private static void negativeControl(WorldChain chain, WorldDrainage.Result world,
                                        WorldBasinFlow.Region water, List<RiverCenterlines.Reach> reaches,
                                        WindowGrid grid, int rx, int rz, String tag) {
        if (world.edges().isEmpty()) {
            log(tag + " region=" + rx + "," + rz + " SKIPPED（本区无实际边，无可篡改）");
            chk(reaches.isEmpty(), "负控前提：无边区的 reach 也必须为空（否则一一对应本就该失败）",
                    "reaches=" + reaches.size());
            return;
        }
        var dropped = world.edges().get(0);
        var mutated = new ArrayList<>(world.edges());
        mutated.remove(0);
        var broken = new WorldDrainage.Result(mutated, world.pendingBasins(),
                world.sampledNodes(), world.terrainSamples());
        // 盆登记有状态（collectBasins 写 map）⇒ 突变审计用副本，免得污染主审计的 lip/identity 判定。
        var isolated = new WorldChain(chain.graph(), chain.routing(), chain.flow(),
                new java.util.LinkedHashMap<>(chain.basins()));
        var control = auditWorldEdges(isolated, broken, water, reaches, grid, rx, rz, hp().tract());
        boolean detected = control.unmapped() > 0 || control.unclaimed() > 0;
        chk(detected, tag + " 负控①：删掉一条实际边（" + dropped.from() + "->" + dropped.to()
                        + "）后，边↔reach 一一对应必须失败（证明判据非空真）",
                "unmapped=" + control.unmapped() + " unclaimed=" + control.unclaimed());
        log(tag + " region=" + rx + "," + rz + " dropped=" + dropped.from() + "->" + dropped.to()
                + " unmapped=" + control.unmapped() + " unclaimed=" + control.unclaimed()
                + " detected=" + detected + " PRODUCTION_UNTOUCHED=true");
        redirectNegativeControl(chain, world, water, reaches, grid, rx, rz, tag);
    }

    /**
     * 负控②：把一条实际边的终点改成另一个格点邻居（保持边数、from 互异、无自环）⇒
     * {@code unmapped/unclaimed} 或 {@code routingMismatch} 必须至少有一项失败。
     * <p>选点规则：优先取 {@code geometricNext} 已知且与非原终点不同的邻居；都不可得（全被预算挡住）
     * 则如实记 {@code SKIPPED_UNDECIDABLE}，不假 PASS。改向点必须是与原终点同 step 网络的合法格点，
     * 否则审计侧查询会越出同一个网络、变成另一种缺陷（那不算本负控的判据）。
     */
    private static void redirectNegativeControl(WorldChain chain, WorldDrainage.Result world,
                                                WorldBasinFlow.Region water, List<RiverCenterlines.Reach> reaches,
                                                WindowGrid grid, int rx, int rz, String tag) {
        int step = hp().step(), tract = hp().tract();
        var original = world.edges().get(0);
        var from = original.from();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var to = new WorldDrainage.Point(from.x() + dx * step, from.z() + dz * step);
            if (to.equals(original.to())) continue;
            try { chain.flow().geometricNext(from); }
            catch (DemandFlow.Unresolved | WorldBasinRouting.RoutingBudget pending) { continue; }
            catch (IllegalStateException budget) { continue; }
            var mutated = new ArrayList<>(world.edges());
            mutated.set(0, new WorldDrainage.Edge(from, to));
            var broken = new WorldDrainage.Result(mutated, world.pendingBasins(),
                    world.sampledNodes(), world.terrainSamples());
            // ★ 盆登记是【有状态】的（collectBasins 往 map 里写）⇒ 每次突变审计都给它一份副本，
            //   否则本次突变把新盆写进共享 map，会污染主审计与后续尝试的 lip/identity 判定。
            var isolated = new WorldChain(chain.graph(), chain.routing(), chain.flow(),
                    new java.util.LinkedHashMap<>(chain.basins()));
            WorldAudit control;
            try { control = auditWorldEdges(isolated, broken, water, reaches, grid, rx, rz, tract); }
            catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException unusable) {
                // 人为改向可能踩到审计侧硬前置（非本负控要证的判据）⇒ 换下一个邻居，不记 PASS 也不记 FAIL。
                log(tag + "_REDIRECT region=" + rx + "," + rz + " candidate " + to
                        + " UNUSABLE(" + unusable.getClass().getSimpleName() + ": " + unusable.getMessage() + ")");
                continue;
            }
            boolean detected = control.unmapped() > 0 || control.unclaimed() > 0
                    || control.routingMismatch() > 0;
            chk(detected, tag + " 负控②：把边 " + from + "->" + original.to() + " 改向 " + to
                            + " 后，一一对应或 canonical 后继判据必须失败（证明判据非自证）",
                    "unmapped=" + control.unmapped() + " unclaimed=" + control.unclaimed()
                            + " routingMismatch=" + control.routingMismatch());
            log(tag + "_REDIRECT region=" + rx + "," + rz + " from=" + from
                    + " original=" + original.to() + " redirected=" + to
                    + " unmapped=" + control.unmapped() + " unclaimed=" + control.unclaimed()
                    + " routingMismatch=" + control.routingMismatch()
                    + " detected=" + detected + " PRODUCTION_UNTOUCHED=true");
            return;
        }
        log(tag + "_REDIRECT region=" + rx + "," + rz
                + " SKIPPED_UNDECIDABLE（无可判定的改向目标 ⇒ 不假 PASS，也不制造 FAIL；"
                + "主审计的 routingUnresolved 会单独暴露这一情形）");
    }

    /** 旧历史自交源坐标（-680,668）：世界链下只如实记录是否仍被准入 + 独立 trace 终态，不强制。 */
    private static void worldHistoricCheck(WorldDrainage graph, WindowGrid grid, WorldDrainage.Result world) {
        var historic = new SharedBoundaryDrainage.WorldCell(-680, 668);
        var edge = world.edges().stream().filter(e -> e.from().equals(worldPoint(historic))).findFirst().orElse(null);
        String terminal = "NOT_AN_ADMITTED_ORIGIN", at = "-";
        if (edge != null) { var t = worldTrace(graph, worldPoint(historic)); terminal = t.state(); at = t.at(); }
        log("G31 WORLD_HISTORIC origin=" + historic + " originAdmitted=" + (edge != null)
                + " terminal=" + terminal + " at=" + at
                + " FORCED_ORIGIN_REMOVED=true gridIndex=" + grid.index(historic));
        chk(edge == null || terminal.equals(WorldDrainage.State.BELOW_SEA.name())
                        || terminal.equals(WorldDrainage.State.BASIN_PENDING.name())
                        || terminal.equals("TRACE_BUDGET_PENDING"),
                "若该历史原点在世界链被准入，其独立 trace 只能以海/待决盆地终止（不制造湖终点）",
                "terminal=" + terminal);
    }

    private static List<WindowEntry> windowEntries(WindowGrid grid, DeterministicSkeleton.Result sk, List<Integer> incoming, int tract) {
        var result = new LinkedHashSet<WindowEntry>(); var graph = windowGraph(sk);
        for (int ti = 0; ti < sk.traces.size(); ti++) {
            var trace = sk.traces.get(ti); var source = grid.world(trace.sourceCell());
            if (Math.floorDiv(source.x(), tract) != -2 || Math.floorDiv(source.z(), tract) != 0) continue;
            String kind = incoming.contains(trace.sourceCell()) ? "INCOMING" : trace.fromOutlet ? "LAKE_OUTLET" : "LOCAL_SELECTED_START";
            var next = graph.next().get(trace.sourceCell());
            chk(next != null && next.size() == 1, "owner起点须有唯一首边或明确报未决 source=" + source, "SOURCE_ENTRY_UNRESOLVED");
            var expected = trace.cell.length > 1 ? grid.world(trace.cell[1]) : source;
            var path = windowFollow(grid, graph, new WindowEntry(source, source, expected, kind, ti));
            for (int i = 1; i < path.cells().size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("window entries");
                var a = grid.world(path.cells().get(i - 1)); var b = grid.world(path.cells().get(i));
                if (Math.floorDiv(a.x(), tract) == Math.floorDiv(b.x(), tract)
                        && Math.floorDiv(a.z(), tract) == Math.floorDiv(b.z(), tract)) continue;
                result.add(new WindowEntry(source, a, b, kind, ti));
            }
            log("W0 source=" + source + " kind=" + kind + " joinedFullNodes=" + path.cells().size() + " terminal=" + path.terminal());
        }
        return List.copyOf(result);
    }

    private static WindowGraph windowGraph(DeterministicSkeleton.Result sk) {
        var edges = new HashMap<Integer, java.util.SortedSet<Integer>>(); var ends = new HashMap<Integer, String>();
        for (var trace : sk.traces) {
            for (int i = 1; i < trace.cell.length; i++) edges.computeIfAbsent(trace.cell[i - 1], ignored -> new java.util.TreeSet<>()).add(trace.cell[i]);
            ends.merge(trace.mouthCell(), trace.end.name(), (a, b) -> a.equals(b) ? a : "TERMINAL_CONFLICT");
        }
        var immutable = new HashMap<Integer, List<Integer>>(); edges.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        return new WindowGraph(Map.copyOf(immutable), Map.copyOf(ends));
    }

    private static WindowPath windowFollow(WindowGrid grid, WindowGraph graph, WindowEntry entry) {
        int k = grid.index(entry.from()), expected = grid.index(entry.to()); var path = new ArrayList<Integer>();
        if (k < 0 || expected < 0) return new WindowPath(entry, path, "ENTRY_OUTSIDE_OR_UNALIGNED", false);
        var seen = new java.util.BitSet(grid.pipe().parent().length); String terminal = "LIMIT"; boolean first = false;
        while (path.size() <= grid.pipe().parent().length) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("window follow");
            if (seen.get(k)) { terminal = "CYCLE"; break; } seen.set(k); path.add(k);
            var next = graph.next().get(k);
            if (next == null) {
                int x = k / grid.pipe().nz(), z = k % grid.pipe().nz();
                terminal = graph.ends().getOrDefault(k, "NOT_PUBLISHED");
                if (terminal.equals("DEAD_END")) terminal = x == 0 || z == 0 || x == grid.pipe().nx() - 1 || z == grid.pipe().nz() - 1
                        ? "UNKNOWN_FRAME" : "INTERNAL_DEAD_END";
                if (terminal.equals("MOUTH_LAKE")) terminal = "LOCAL_LAKE_BUDGET_PENDING";
                if (terminal.equals("MOUTH_SEA")) terminal = "LOCAL_SEA_NOT_GLOBAL_CERTIFICATE";
                if (terminal.equals("JOIN")) terminal = "BROKEN_JOIN";
                break;
            }
            if (next.size() != 1) { terminal = "MULTIPLE_DOWNSTREAM"; break; }
            if (path.size() == 1) first = next.get(0) == expected;
            k = next.get(0);
        }
        return new WindowPath(entry, path, terminal, first);
    }

    private static void secWindowExperiment(long seed, boolean compare, boolean acceptance) throws Exception {
        Path dir = OUT.resolve("window-contract-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var common = compare ? windowPipeline() : null;
        var owner = new WindowGrid(PROV.pipeForProbe(-768, 256), -1024 - hp().halo(), -hp().halo());
        var incoming = windowIncoming(-2, 0, owner); var actual = PROV.reachesForProbe(-768, 256);
        var sk = windowBuild(owner, incoming); boolean equal = actual.size() == sk.traces.size();
        for (int i = 0; equal && i < actual.size(); i++) equal = java.util.Arrays.equals(actual.get(i).cell, sk.traces.get(i).cell);
        chk(equal, "当前生产全部owner已发布trace按原参数重建逐位相同", "different");
        var entries = windowEntries(owner, sk, incoming, hp().tract());
        var receiver = new WindowGrid(ownerSourcePipe(GEN, -2, 1, true), owner.ox(), hp().tract() - hp().halo());
        windowRefresh(dir, owner, receiver, entries);
        if (Boolean.getBoolean("geogenesis.windowHandoffProbe") && common != null) windowHandoffs(dir, owner, receiver, common, entries);
        chk(!entries.isEmpty(), "owner全部跨缝源事件非空转，不仅三个固定坐标", "empty");
        if (compare) windowCompare(dir, owner, common, entries, acceptance);
        Files.writeString(dir.resolve("metrics.txt"), LOG);
        // #region agent log: W fixed-context and source-obligation comparison, never production input
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
        log("WINDOW_CONTRACT_DIR=" + dir.toAbsolutePath());
    }

    private static void windowRefresh(Path dir, WindowGrid owner, WindowGrid receiver, List<WindowEntry> entries) throws Exception {
        var rows = new StringBuilder("sourceX,sourceZ,kind,trace,fromX,fromZ,toX,toZ,axis,boundary,alongTile,targetReceiver,receiverCovered,bedEqual,parentEqual,ownerQ,receiverQ,receiverCandidate,coarseIncoming,sharedRawSuffix,reason\n");
        var recvCandidates = sourceCandidates(receiver.pipe(), windowParams(receiver.pipe()).minSourceAccum());
        var coarse = windowIncoming(-2, 1, receiver); int hit = 0;
        for (var e : entries) {
            boolean target = Math.floorDiv(e.to().x(), hp().tract()) == -2 && Math.floorDiv(e.to().z(), hp().tract()) == 1;
            int a = owner.index(e.to()), b = target ? receiver.index(e.to()) : -1; boolean covered = b >= 0;
            int ap = owner.pipe().parent()[a], bp = covered ? receiver.pipe().parent()[b] : -1;
            boolean bed = covered && Double.doubleToLongBits(owner.pipe().bed()[a]) == Double.doubleToLongBits(receiver.pipe().bed()[b]);
            boolean same = covered && ap >= 0 && bp >= 0 && owner.world(ap).equals(receiver.world(bp));
            int suffix = 0, k = a; var seen = new java.util.BitSet(owner.pipe().bed().length);
            while (covered && k >= 0 && !seen.get(k)) {
                seen.set(k); int other = receiver.index(owner.world(k)), next = owner.pipe().parent()[k];
                if (other < 0 || next < 0 || receiver.pipe().parent()[other] < 0
                        || !owner.world(next).equals(receiver.world(receiver.pipe().parent()[other]))) break;
                suffix++; k = next;
            }
            String reason = !target ? "NOT_TARGET_RECEIVER" : !covered ? "ENTRY_UNKNOWN" : !same ? "DIRECTION_DIVERGENCE"
                    : coarse.contains(b) ? "COARSE_ENTRY_PRESENT" : recvCandidates.contains(b) ? "CANDIDATE_ADMISSION_PENDING"
                    : receiver.pipe().discharge()[b] < windowParams(receiver.pipe()).minSourceAccum() ? "BELOW_LOCAL_SOURCE_THRESHOLD_NO_COARSE_ENTRY" : "NO_LOCAL_CANDIDATE_NO_COARSE_ENTRY";
            for (var crossing : windowEdgeKeys(e.from(), e.to(), hp().tract())) {
                rows.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.kind()).append(',').append(e.trace()).append(',')
                        .append(e.from().x()).append(',').append(e.from().z()).append(',').append(e.to().x()).append(',').append(e.to().z()).append(',')
                        .append(crossing.axis()).append(',').append(crossing.boundary()).append(',').append(crossing.alongTile()).append(',').append(target)
                        .append(',').append(covered).append(',').append(bed).append(',').append(same).append(',').append(owner.pipe().discharge()[a])
                        .append(',').append(covered ? receiver.pipe().discharge()[b] : Double.NaN).append(',').append(covered && recvCandidates.contains(b))
                        .append(',').append(covered && coarse.contains(b)).append(',').append(suffix).append(',').append(reason).append('\n');
                if (crossing.axis() == 1 && crossing.boundary() == 512) hit++;
            }
            log("W1 source=" + e.source() + " kind=" + e.kind() + " edge=" + e.from() + "->" + e.to()
                    + " targetReceiver=" + target + " covered=" + covered + " bedEqual=" + bed + " parentEqual=" + same
                    + " sharedRawSuffix=" + suffix + " reason=" + reason + " Q_NOT_TRANSFERRED");
        }
        Files.writeString(dir.resolve("refreshed-entries.csv"), rows);
        chk(hit > 0, "实际H512过界事件存在（其余缝完整登记，不偷换测试域）", "none");
    }

    private record WindowSuffix(List<SharedBoundaryDrainage.WorldCell> path, String terminal) {
        WindowSuffix { path = List.copyOf(path); }
    }

    private static WindowSuffix windowSuffix(WindowGrid grid, WindowGraph graph, SharedBoundaryDrainage.WorldCell start) {
        int k = grid.index(start);
        if (k < 0) return new WindowSuffix(List.of(), "ENTRY_OUTSIDE_OR_UNALIGNED");
        var next = graph.next().get(k);
        var expected = next != null && next.size() == 1 ? grid.world(next.get(0)) : start;
        var followed = windowFollow(grid, graph, new WindowEntry(start, start, expected, "SUFFIX", -1));
        return new WindowSuffix(followed.cells().stream().map(grid::world).toList(), followed.terminal());
    }

    private static void windowHandoffs(Path dir, WindowGrid owner, WindowGrid receiver, WindowGrid common,
                                       List<WindowEntry> entries) throws Exception {
        if (common == null) throw new IllegalArgumentException("handoff requires common window");
        var variants = List.of(owner, receiver, common);
        var raw = variants.stream().map(SharedLakeSeamProbe::freezeWindowSuccessor).toList();
        var processed = new ArrayList<WindowGraph>();
        for (var grid : variants) processed.add(windowGraph(windowBuild(grid, windowSeeds(grid, entries))));
        var rows = new StringBuilder("variant,sourceX,sourceZ,entryX,entryZ,left,right,leftNodes,rightNodes,leftTerminal,rightTerminal,commonPrefix,firstDifferenceX,firstDifferenceZ,state\n");
        int conflicts = 0, unknown = 0, compared = 0;
        for (int variant = 0; variant < 2; variant++) for (int side = 0; side < 2; side++) for (var entry : entries) {
            var graphs = variant == 0 ? raw : processed;
            var a = windowSuffix(variants.get(side), graphs.get(side), entry.from());
            var b = windowSuffix(common, graphs.get(2), entry.from());
            int prefix = 0;
            while (prefix < Math.min(a.path().size(), b.path().size()) && a.path().get(prefix).equals(b.path().get(prefix))) prefix++;
            boolean missing = a.path().isEmpty() || b.path().isEmpty();
            boolean divergence = prefix < Math.min(a.path().size(), b.path().size());
            boolean equal = a.equals(b);
            String state = missing ? "ENTRY_INPUT_PENDING" : divergence ? "SUCCESSOR_CONFLICT"
                    : equal ? "SAME_COMPLETE_LOCAL_SUFFIX" : "COVERAGE_OR_TERMINAL_PENDING";
            var at = divergence ? a.path().get(Math.max(0, prefix - 1)) : null;
            if (divergence) conflicts++;
            if (missing || !equal && !divergence) unknown++;
            compared++;
            rows.append(variant == 0 ? "RAW" : "PROCESSED").append(',').append(entry.source().x()).append(',').append(entry.source().z())
                    .append(',').append(entry.from().x()).append(',').append(entry.from().z()).append(',').append(side == 0 ? "OWNER" : "RECEIVER")
                    .append(",COMMON,").append(a.path().size()).append(',').append(b.path().size()).append(',').append(a.terminal()).append(',')
                    .append(b.terminal()).append(',').append(prefix).append(',').append(at == null ? "" : at.x()).append(',')
                    .append(at == null ? "" : at.z()).append(',').append(state).append('\n');
        }
        Files.writeString(dir.resolve("window-handoffs.csv"), rows);
        writeHandoffFields(dir, variants);
        log("G36 HANDOFF comparisons=" + compared + " successorConflicts=" + conflicts + " coverageOrTerminalPending=" + unknown
                + " EXTRA_PIPELINES=0 SAME_DOMAIN_ORDER_TEST_IS_NOT_CROSS_DOMAIN_PROOF=true");
        chk(compared > 0 && conflicts == 0,
                "不同固定窗的已知共同后缀不得分歧；框外延续单列待决，不要求有限窗包含完整全球后缀", "conflicts=" + conflicts + " pending=" + unknown);
    }

    private static void writeHandoffFields(Path dir, List<WindowGrid> grids) throws Exception {
        var rows = new StringBuilder("window,x,z,bed,level,parentX,parentZ,resolved,lake,sea,frame,q\n");
        String[] names = {"OWNER", "RECEIVER", "COMMON"};
        for (int side=0;side<grids.size();side++) {
            var grid=grids.get(side);var p=grid.pipe();
            for (int k=0;k<p.bed().length;k++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("handoff fields");
                var at=grid.world(k);int parent=p.parent()[k];var next=parent<0?null:grid.world(parent);
                boolean frame=k/p.nz()==0 || k%p.nz()==0 || k/p.nz()==p.nx()-1 || k%p.nz()==p.nz()-1;
                rows.append(names[side]).append(',').append(at.x()).append(',').append(at.z()).append(',').append(p.bed()[k]).append(',')
                        .append(p.level()[k]).append(',').append(next==null?"":next.x()).append(',').append(next==null?"":next.z()).append(',')
                        .append(p.resolved()[k]).append(',').append(p.lake()[k]).append(',').append(p.sea()[k]).append(',').append(frame).append(',')
                        .append(p.discharge()[k]).append('\n');
            }
            for (var at:List.of(new SharedBoundaryDrainage.WorldCell(-1016,828),new SharedBoundaryDrainage.WorldCell(-428,568),
                    new SharedBoundaryDrainage.WorldCell(-420,568))) {
                int k=grid.index(at);if(k<0)continue;int parent=p.parent()[k];
                log("G37 FIELD window="+names[side]+" at="+at+" bed="+p.bed()[k]+" level="+p.level()[k]
                        +" resolved="+p.resolved()[k]+" next="+(parent<0?null:grid.world(parent)));
            }
        }
        Files.writeString(dir.resolve("handoff-fields.csv"),rows);
        auditHandoffTransfers(dir,grids);
    }

    private static void auditHandoffTransfers(Path dir,List<WindowGrid> grids) throws Exception {
        var rows=new StringBuilder("window,entryX,entryZ,portX,portZ,barrier,portBed,portSea\n");
        var conditions=new StringBuilder("window,x,z,externalLevel,conditionedLevel\n");
        String[] names={"OWNER","RECEIVER","COMMON"};
        var transfers=new ArrayList<SharedBoundaryTransfer.GridTransfer>();
        for(int i=0;i<grids.size();i++) {
            var g=grids.get(i);var p=g.pipe();
            var transfer=SharedBoundaryTransfer.grid(g.ox(),g.oz(),p.nx(),p.nz(),p.cellBlocks(),p.bed());
            transfers.add(transfer);
            writeTransferConditions(names[i],g,transfer,grids.get(1),conditions);
            for(var at:List.of(new SharedBoundaryDrainage.WorldCell(-1016,828),new SharedBoundaryDrainage.WorldCell(-428,568),
                    new SharedBoundaryDrainage.WorldCell(-420,568))) {
                int k=g.index(at);if(k<0)continue;
                var costs=SharedBoundaryTransfer.portCosts(transfer,at);double least=Double.POSITIVE_INFINITY;
                for(var port:costs.entrySet()) {
                    int index=g.index(port.getKey());if(!p.sea()[index])least=Math.min(least,port.getValue());
                    rows.append(names[i]).append(',').append(at.x()).append(',').append(at.z()).append(',').append(port.getKey().x()).append(',')
                            .append(port.getKey().z()).append(',').append(port.getValue()).append(',').append(p.bed()[index]).append(',').append(p.sea()[index]).append('\n');
                }
                chk(costs.size()==2*p.nx()+2*p.nz()-4,"D8交接保留全部frame端口，不只选本窗父链 window="+names[i]+" at="+at,"lost ports");
                log("G37 TRANSFER window="+names[i]+" entry="+at+" minLandFrame="+least+" rawLevel="+p.level()[k]
                        +" sampledD8Only=true treeIsCapacityNotRiver=true EXTRA_PIPELINES=0");
            }
        }
        Files.writeString(dir.resolve("handoff-port-costs.csv"),rows);
        Files.writeString(dir.resolve("handoff-conditions.csv"),conditions);
        auditHandoffAssembly(dir,grids,transfers);
    }

    private static void auditHandoffAssembly(Path dir,List<WindowGrid> grids,List<SharedBoundaryTransfer.GridTransfer> inputs) throws Exception {
        long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get(),shared=HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var assembly=SharedBoundaryTransfer.assemble(inputs);
        var terminals=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var g:grids)for(int k=0;k<g.pipe().sea().length;k++)if(g.pipe().sea()[k])terminals.put(g.world(k),g.pipe().bed()[k]);
        var result=SharedBoundaryTransfer.capacities(assembly,terminals);
        var reverse=new ArrayList<>(inputs);java.util.Collections.reverse(reverse);
        chk(assembly.equals(SharedBoundaryTransfer.assemble(reverse)),"真实三窗已有bed组合逆序逐位相同，不读邻finalfine","different");
        var ports=new java.util.HashSet<Integer>(assembly.ports());int removed=0;
        var rows=new StringBuilder("x,z,bed,localSeaCandidate,unknownPort,terminalCost,unknownCost\n");
        for(int k=0;k<assembly.vertices().size();k++) {var v=assembly.vertices().get(k);var c=result.get(v.point());
            rows.append(v.point().x()).append(',').append(v.point().z()).append(',').append(v.bed()).append(',').append(terminals.containsKey(v.point()))
                    .append(',').append(ports.contains(k)).append(',').append(c.terminal()).append(',').append(c.unknown()).append('\n');}
        var ids=new HashMap<SharedBoundaryDrainage.WorldCell,Integer>();for(int k=0;k<assembly.vertices().size();k++)ids.put(assembly.vertices().get(k).point(),k);
        for(var input:inputs)for(int k:input.ports())if(!ports.contains(ids.get(input.vertices().get(k).point())))removed++;
        Files.writeString(dir.resolve("handoff-assembly.csv"),rows);
        var focus=new StringBuilder("x,z,terminalCost,unknownCost,status\n");
        for(var at:List.of(new SharedBoundaryDrainage.WorldCell(-1016,828),new SharedBoundaryDrainage.WorldCell(-428,568),new SharedBoundaryDrainage.WorldCell(-420,568))) {
            var c=result.get(at);String status=c.unknown()<=c.terminal()?"EXTERNAL_CONDITION_PENDING":"LOCAL_SEA_CANDIDATE_ONLY";
            focus.append(at.x()).append(',').append(at.z()).append(',').append(c.terminal()).append(',').append(c.unknown()).append(',').append(status).append('\n');
            log("G38 ASSEMBLY at="+at+" terminal="+c.terminal()+" unknown="+c.unknown()+" status="+status+" NOT_DIRECTION_OR_WATER=true");
        }
        Files.writeString(dir.resolve("handoff-assembly-focus.csv"),focus);
        chk(removed>0 && fine==HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared==HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "已有覆盖消除人工内框且无新增fine求解 removedPortOccurrences="+removed,"no effect/fanout");
        log("G38 ASSEMBLY vertices="+assembly.vertices().size()+" ports="+assembly.ports().size()+" removedInternalPortOccurrences="+removed
                +" LOCAL_SEA_CANDIDATES_NOT_GLOBAL=true SOURCES_UNCHANGED=true EXTRA_TERRAIN_SAMPLES=0 EXTRA_PIPELINES=0 DIAGNOSTIC_EXISTING_INPUTS_ONLY=true");
        auditCoarseExterior(dir,SharedBoundaryTransfer.assemble(List.of(inputs.get(2))),grids);
    }

    private static void auditCoarseExterior(Path dir,SharedBoundaryTransfer.GridAssembly assembly,List<WindowGrid> grids) throws Exception {
        var policy=new SharedBoundaryDrainage.WorldPolicy(runSeed,GeoGenesisConfig.configFingerprint(),1,hp().tract(),hp().step());
        var owners=new HashMap<ProductionDrainageAudit.Region,HierarchicalHydrology.Coarse>();
        for(var edge:SharedBoundaryTransfer.exteriorEdges(assembly)) {
            var at=edge.to();var owner=new ProductionDrainageAudit.Region(Math.floorDiv(at.x(),hp().tract()),Math.floorDiv(at.z(),hp().tract()));
            owners.computeIfAbsent(owner,o->PROV.coarseLayerFor(o.x(),o.z()));
        }
        long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get(),shared=HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var frame=SharedBoundaryTransfer.exterior(policy,assembly,at->coarseExteriorNode(at,owners),4096);
        var counts=new java.util.TreeMap<SharedBoundaryTransfer.ExteriorState,Integer>();
        var summary=new StringBuilder("fromX,fromZ,toX,toZ,state,pathNodes,firstBed,firstLevel,firstResolved\n");
        var paths=new StringBuilder("fromX,fromZ,toX,toZ,node,x,z,ownerX,ownerZ,bed,level,resolved,lake,sea,nextX,nextZ\n");
        for(var edge:frame.edges()) {var trace=frame.traces().get(edge);counts.merge(trace.state(),1,Integer::sum);
            var first=trace.path().isEmpty()?null:trace.path().get(0);
            summary.append(edge.from().x()).append(',').append(edge.from().z()).append(',').append(edge.to().x()).append(',').append(edge.to().z())
                    .append(',').append(trace.state()).append(',').append(trace.path().size()).append(',').append(first==null?"":first.bed())
                    .append(',').append(first==null?"":first.level()).append(',').append(first!=null && first.resolved()).append('\n');
            for(int i=0;i<trace.path().size();i++) {var node=trace.path().get(i);var next=node.next();
                paths.append(edge.from().x()).append(',').append(edge.from().z()).append(',').append(edge.to().x()).append(',').append(edge.to().z()).append(',').append(i)
                        .append(',').append(node.point().x()).append(',').append(node.point().z()).append(',').append(node.owner().x()).append(',').append(node.owner().z())
                        .append(',').append(node.bed()).append(',').append(node.level()).append(',').append(node.resolved()).append(',').append(node.lake()).append(',').append(node.sea())
                        .append(',').append(next==null?"":next.x()).append(',').append(next==null?"":next.z()).append('\n');}
        }
        Files.writeString(dir.resolve("coarse-exterior.csv"),summary);Files.writeString(dir.resolve("coarse-exterior-paths.csv"),paths);
        var local=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var v:assembly.vertices())if(v.bed()<TP.seaLevel())local.put(v.point(),v.bed());
        var cap=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,local,TP.seaLevel());
        var output=new StringBuilder("x,z,bed,localSea,candidateCost,unknownCost\n");
        for(var v:assembly.vertices()){var c=cap.get(v.point());output.append(v.point().x()).append(',').append(v.point().z()).append(',').append(v.bed())
                .append(',').append(local.containsKey(v.point())).append(',').append(c.terminal()).append(',').append(c.unknown()).append('\n');}
        Files.writeString(dir.resolve("coarse-exterior-capacities.csv"),output);
        for(var at:List.of(new SharedBoundaryDrainage.WorldCell(-1016,828),new SharedBoundaryDrainage.WorldCell(-428,568),new SharedBoundaryDrainage.WorldCell(-420,568)))
            log("G39 EXTERIOR focus="+at+" capacities="+cap.get(at)+" COARSE_ONLY_CONDITIONAL_NOT_WATER=true");
        chk(fine==HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared==HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),"外部接口只读固定owner粗层，零新增fine/sharedfine","fanout");
        log("G39 EXTERIOR edges="+frame.edges().size()+" states="+counts+" fixedCoarseOwners="+owners.size()+" FRAME_COMPLETE=true NO_NEW_FINE_PIPELINE=true");
        auditConditionalRouting(dir,assembly,frame,local);
        auditConditionalProducts(dir,SharedBoundaryTransfer.conditionalRouting(assembly,frame,local,TP.seaLevel()),grids);
        var outside=auditExteriorBeds(dir,assembly);
        auditExteriorBounds(dir,assembly,frame,local,outside);
    }

    private static Map<SharedBoundaryDrainage.WorldCell,Double> auditExteriorBeds(Path dir,SharedBoundaryTransfer.GridAssembly assembly) throws Exception {
        long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get(),shared=HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        int tiles=GEN.erosionTileCacheSize();long misses=GEN.tileCacheStats().misses();
        var points=new java.util.TreeSet<SharedBoundaryDrainage.WorldCell>(java.util.Comparator.comparingInt(SharedBoundaryDrainage.WorldCell::x)
                .thenComparingInt(SharedBoundaryDrainage.WorldCell::z));
        var inside=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(int k:assembly.ports()){var v=assembly.vertices().get(k);points.add(v.point());inside.put(v.point(),v.bed());}
        for(var edge:SharedBoundaryTransfer.exteriorEdges(assembly))points.add(edge.to());
        var corners=new HashMap<ProductionDrainageAudit.Point,Double>();
        WindowOutlets.Terrain terrain=(x,z)->corners.computeIfAbsent(new ProductionDrainageAudit.Point(x,z),
                at->GEN.erodedHeight(at.x()/TP.horizontalScale(),at.z()/TP.horizontalScale()));
        var rows=new StringBuilder("x,z,insidePort,bed\n");boolean equal=true;double half=assembly.step()*0.5;
        var outside=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var at:points) {
            double bed=WindowOutlets.VALLEY_MIN?Math.min(Math.min(terrain.height(at.x()-half,at.z()-half),terrain.height(at.x()+half,at.z()-half)),
                    Math.min(terrain.height(at.x()-half,at.z()+half),terrain.height(at.x()+half,at.z()+half))):terrain.height(at.x(),at.z());
            if(inside.containsKey(at))equal &= Double.doubleToRawLongBits(bed)==Double.doubleToRawLongBits(inside.get(at));
            else outside.put(at,bed);
            rows.append(at.x()).append(',').append(at.z()).append(',').append(inside.containsKey(at)).append(',').append(bed).append('\n');
        }
        Files.writeString(dir.resolve("exterior-real-beds.csv"),rows);
        chk(equal && fine==HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared==HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "外环真实床面采样与框内生产床面逐位同算子，无新增fine/sharedfine","bed mismatch/fanout");
        log("G41-A EXTERIOR_BED points="+points.size()+" insidePorts="+inside.size()+" uniqueTerrainSamples="+corners.size()
                +" erosionTilesDelta="+(GEN.erosionTileCacheSize()-tiles)+" tileQueryMissesNotUnique="+(GEN.tileCacheStats().misses()-misses)
                +" ONE_ADJACENT_RING_ONLY=true NO_NEW_PIPELINES=true NOT_GLOBAL_OUTLET_CERTIFICATE=true");
        return Map.copyOf(outside);
    }

    private static void auditExteriorBounds(Path dir,SharedBoundaryTransfer.GridAssembly assembly,SharedBoundaryTransfer.ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell,Double> local,Map<SharedBoundaryDrainage.WorldCell,Double> outside) throws Exception {
        var bounds=SharedBoundaryTransfer.exteriorBounds(assembly,outside);
        var old=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,local,TP.seaLevel());
        var cap=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,local,TP.seaLevel(),bounds);
        var previous=SharedBoundaryTransfer.conditionalRouting(assembly,frame,local,TP.seaLevel());
        var routing=SharedBoundaryTransfer.conditionalRouting(assembly,frame,local,TP.seaLevel(),bounds);
        var rows=new StringBuilder("x,z,bed,terminal,oldUnknown,unknown,oldState,state,nextX,nextZ\n");int raised=0,changed=0;boolean valid=true;
        for(var v:assembly.vertices()) {var c=cap.get(v.point());var before=old.get(v.point());var node=routing.nodes().get(v.point());
            if(c.unknown()>before.unknown())raised++;if(!node.equals(previous.nodes().get(v.point())))changed++;
            valid &= c.unknown()>=before.unknown() && c.terminal()==before.terminal();
            rows.append(v.point().x()).append(',').append(v.point().z()).append(',').append(v.bed()).append(',').append(c.terminal()).append(',').append(before.unknown())
                    .append(',').append(c.unknown()).append(',').append(previous.nodes().get(v.point()).state()).append(',').append(node.state())
                    .append(',').append(node.next()==null?"":node.next().x()).append(',').append(node.next()==null?"":node.next().z()).append('\n');
        }
        Files.writeString(dir.resolve("exterior-bounded-capacities.csv"),rows);
        chk(valid && raised>0,"真实外邻床面只收紧未知下界，不改条件终端或增发海", "invalid/no effect");
        log("G41 BOUNDS raisedUnknownNodes="+raised+" changedConditionalNodes="+changed+" SOURCES_AND_PRODUCTION_UNCHANGED=true");
        // #region agent log: G41-B 同算子紧邻外环只约束未知下界，不发布水
        Files.writeString(Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log"),
                "G41-B raisedUnknownNodes="+raised+" changedConditionalNodes="+changed+"\n",java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void auditConditionalRouting(Path dir,SharedBoundaryTransfer.GridAssembly assembly,SharedBoundaryTransfer.ExteriorFrame frame,
            Map<SharedBoundaryDrainage.WorldCell,Double> local) throws Exception {
        var routing=SharedBoundaryTransfer.conditionalRouting(assembly,frame,local,TP.seaLevel());
        var nodeRows=new StringBuilder("x,z,bed,level,nextX,nextZ,state\n");
        for(var n:routing.nodes().values())nodeRows.append(n.point().x()).append(',').append(n.point().z()).append(',').append(n.bed()).append(',').append(n.level())
                .append(',').append(n.next()==null?"":n.next().x()).append(',').append(n.next()==null?"":n.next().z()).append(',').append(n.state()).append('\n');
        Files.writeString(dir.resolve("conditional-nodes.csv"),nodeRows);
        var summary=new StringBuilder("event,sourceX,sourceZ,entryX,entryZ,nodes,state,suffixChecks\n");
        var points=new StringBuilder("event,sourceX,sourceZ,node,x,z,state\n");var states=new java.util.TreeMap<SharedBoundaryDrainage.WorldState,Integer>();
        int events=0,checks=0;boolean consistent=true;
        for(var row:interfaceCsv(dir.resolve("refreshed-entries.csv"))) {
            var source=new SharedBoundaryDrainage.WorldSource(new SharedBoundaryDrainage.WorldCell(Integer.parseInt(row.get("sourceX")),Integer.parseInt(row.get("sourceZ"))),
                    new SharedBoundaryDrainage.WorldCell(Integer.parseInt(row.get("fromX")),Integer.parseInt(row.get("fromZ"))));
            var result=SharedBoundaryDrainage.projectConditional(routing,List.of(source),4096);var route=result.routes().get(source);int count=0;
            for(int i=0;i<route.path().size();i++) {var at=route.path().get(i);
                points.append(events).append(',').append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(i).append(',').append(at.x()).append(',').append(at.z()).append(',').append(route.state()).append('\n');
                if(i==0 || Math.floorDiv(at.x(),hp().tract())!=Math.floorDiv(route.path().get(i-1).x(),hp().tract())
                        || Math.floorDiv(at.z(),hp().tract())!=Math.floorDiv(route.path().get(i-1).z(),hp().tract())) {
                    var receiver=new SharedBoundaryDrainage.WorldSource(source.identity(),at);
                    var suffix=SharedBoundaryDrainage.projectConditional(routing,List.of(receiver),4096).routes().get(receiver);
                    consistent &= suffix.path().equals(route.path().subList(i,route.path().size())) && suffix.state()==route.state();count++;
                }
            }
            consistent &= route.state()!=SharedBoundaryDrainage.WorldState.CYCLE && route.state()!=SharedBoundaryDrainage.WorldState.NONDESCENDING;
            states.merge(route.state(),1,Integer::sum);checks+=count;
            summary.append(events++).append(',').append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(source.entry().x()).append(',').append(source.entry().z())
                    .append(',').append(route.path().size()).append(',').append(route.state()).append(',').append(count).append('\n');
        }
        Files.writeString(dir.resolve("conditional-sources.csv"),summary);Files.writeString(dir.resolve("conditional-paths.csv"),points);
        chk(events>0 && checks>events && consistent,"完整外框消费为唯一候选后继，全部诊断入口及跨owner后缀同值 checks="+checks,"different/empty");
        log("G40 CONDITIONAL events="+events+" states="+states+" SOURCES_ARE_DIAGNOSTIC_OBLIGATIONS=true NO_Q_INJECTION=true NOT_PRODUCTION_OR_CROSS_DOMAIN_CERTIFICATE=true");
    }

    private static void auditConditionalProducts(Path dir,SharedBoundaryTransfer.ConditionalRouting routing,List<WindowGrid> grids) throws Exception {
        var sources=new ArrayList<SharedBoundaryDrainage.WorldSource>();var origins=new StringBuilder("sourceX,sourceZ,localQ,threshold\n");
        for(int rz=0;rz<=1;rz++) {var grid=grids.get(rz);var product=SharedBoundaryDrainage.worldProduct(routing.policy(),new ProductionDrainageAudit.Region(-2,rz),
                    grid.ox(),grid.oz(),grid.pipe(),windowParams(grid.pipe()));
            for(var origin:product.origins()) {sources.add(new SharedBoundaryDrainage.WorldSource(origin.identity(),origin.identity()));
                origins.append(origin.identity().x()).append(',').append(origin.identity().z()).append(',').append(origin.localDischarge()).append(',').append(origin.threshold()).append('\n');}
        }
        var result=SharedBoundaryDrainage.projectConditional(routing,sources,4096);var reverse=new ArrayList<>(sources);java.util.Collections.reverse(reverse);reverse.addAll(sources);
        chk(!sources.isEmpty() && result.equals(SharedBoundaryDrainage.projectConditional(routing,reverse,4096)),"固定owner准入源直接消费共同后继，反序重复源完整支持同值","different/empty");
        var summary=new StringBuilder("sourceX,sourceZ,nodes,state\n");var paths=new StringBuilder("sourceX,sourceZ,node,x,z,state\n");
        var support=new StringBuilder("fromX,fromZ,toX,toZ,sourceX,sourceZ\n");var states=new java.util.TreeMap<SharedBoundaryDrainage.WorldState,Integer>();int crosses=0;
        boolean same=true;
        for(var source:sources) {var route=result.routes().get(source);states.merge(route.state(),1,Integer::sum);
            summary.append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(route.path().size()).append(',').append(route.state()).append('\n');
            same &= route.equals(SharedBoundaryDrainage.projectConditional(routing,List.of(source),4096).routes().get(source));
            for(int i=0;i<route.path().size();i++) {var at=route.path().get(i);
                paths.append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(i).append(',').append(at.x()).append(',').append(at.z()).append(',').append(route.state()).append('\n');
                if(i>0 && !windowEdgeKeys(route.path().get(i-1),at,hp().tract()).isEmpty())crosses++;
            }
        }
        result.support().forEach((edge,set)->set.forEach(source->support.append(edge.from().x()).append(',').append(edge.from().z()).append(',').append(edge.to().x()).append(',').append(edge.to().z())
                .append(',').append(source.x()).append(',').append(source.z()).append('\n')));
        Files.writeString(dir.resolve("conditional-origins.csv"),origins);Files.writeString(dir.resolve("conditional-origin-summary.csv"),summary);
        Files.writeString(dir.resolve("conditional-origin-paths.csv"),paths);Files.writeString(dir.resolve("conditional-support.csv"),support);
        chk(same && result.routes().values().stream().noneMatch(r->r.state()==SharedBoundaryDrainage.WorldState.CYCLE || r.state()==SharedBoundaryDrainage.WorldState.NONDESCENDING),
                "固定准入源逐源后缀同值，无环/高程回升，不抬Q或重选源",states.toString());
        log("G40 ORIGINS sources="+sources.size()+" edges="+result.support().size()+" crossingEvents="+crosses+" states="+states
                +" EXISTING_DIAGNOSTIC_RAW_OWNER_INPUT_NOT_PROVIDER_DEPENDENCY=true");
    }

    private static SharedBoundaryTransfer.ExteriorObservation coarseExteriorNode(SharedBoundaryDrainage.WorldCell at,
            Map<ProductionDrainageAudit.Region,HierarchicalHydrology.Coarse> owners) {
        var owner=new ProductionDrainageAudit.Region(Math.floorDiv(at.x(),hp().tract()),Math.floorDiv(at.z(),hp().tract()));
        var c=owners.get(owner);if(c==null)return null;
        int x=at.x()-c.originX(),z=at.z()-c.originZ();
        if(x<0 || z<0 || x%hp().step()!=0 || z%hp().step()!=0 || x/hp().step()>=c.n() || z/hp().step()>=c.n())return null;
        int k=x/hp().step()*c.n()+z/hp().step();var p=c.pipe();int next=p.parent()[k];
        return new SharedBoundaryTransfer.ExteriorObservation(at,owner,p.bed()[k],p.level()[k],p.resolved()[k],p.lake()[k],p.sea()[k],
                next<0?null:new SharedBoundaryDrainage.WorldCell(c.originX()+next/c.n()*hp().step(),c.originZ()+next%c.n()*hp().step()));
    }

    private static void writeTransferConditions(String name,WindowGrid grid,SharedBoundaryTransfer.GridTransfer transfer,
                                                WindowGrid reference,StringBuilder rows) {
        var boundary=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(int k:transfer.ports()) {
            var at=transfer.vertices().get(k).point();int other=reference.index(at);
            boundary.put(at,other<0?Double.POSITIVE_INFINITY:reference.pipe().level()[other]);
        }
        var result=SharedBoundaryTransfer.condition(transfer,boundary);
        for(int k:transfer.ports()) {var at=transfer.vertices().get(k).point();
            rows.append(name).append(',').append(at.x()).append(',').append(at.z()).append(',').append(boundary.get(at)).append(',').append(result.get(at)).append('\n');}
        for(var at:List.of(new SharedBoundaryDrainage.WorldCell(-1016,828),new SharedBoundaryDrainage.WorldCell(-428,568),
                new SharedBoundaryDrainage.WorldCell(-420,568))) {
            if(grid.index(at)<0)continue;int other=reference.index(at);
            rows.append(name).append(',').append(at.x()).append(',').append(at.z()).append(",,").append(result.get(at)).append('\n');
            log("G37 CONDITION window="+name+" at="+at+" value="+result.get(at)+" receiverReference="+(other<0?null:reference.pipe().level()[other])
                    +" DIAGNOSTIC_REFERENCE_ONLY_NOT_PRODUCER_INPUT=true UNSUPPLIED_PORTS_EXPLICITLY_CLOSED_NOT_UNKNOWN=true");
        }
    }

    private static List<SharedLakeDomain.Key> windowEdgeKeys(SharedBoundaryDrainage.WorldCell a, SharedBoundaryDrainage.WorldCell b, int tract) {
        var keys = new java.util.TreeSet<SharedLakeDomain.Key>();
        for (int axis = 0; axis < 2; axis++) {
            int av = axis == 0 ? a.x() : a.z(), bv = axis == 0 ? b.x() : b.z();
            int at = Math.floorDiv(av, tract), bt = Math.floorDiv(bv, tract);
            if (at == bt) continue;
            if (Math.abs(at - bt) != 1) throw new IllegalArgumentException("nonlocal world edge");
            int boundary = Math.max(at, bt) * tract;
            double f = (boundary - (double) av) / (bv - (double) av);
            double along = axis == 0 ? a.z() + f * (b.z() - (double) a.z()) : a.x() + f * (b.x() - (double) a.x());
            keys.add(new SharedLakeDomain.Key(axis, boundary, (int) Math.floor(along / tract)));
        }
        return List.copyOf(keys);
    }

    private static List<Integer> windowSeeds(WindowGrid grid, List<WindowEntry> entries) {
        if (entries.size() > 4096) throw new IllegalArgumentException("bounded window obligations");
        var seeds = new java.util.TreeSet<Integer>();
        for (var entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("window seeds");
            int from = grid.index(entry.from()), to = grid.index(entry.to());
            if (from >= 0 && to >= 0) seeds.add(from);
        }
        return List.copyOf(seeds);
    }

    private static WindowGrid windowPipeline() {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), shared0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        int tiles0 = GEN.erosionTileCacheSize();
        long misses = GEN.tileCacheStats().misses(), coarse = HierarchicalHydrology.Diag.COARSE_SAMPLES.get();
        long memory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), start = System.nanoTime();
        var geom = SharedLakeDomain.geom(new SharedLakeDomain.Key(1, 512, -2), hp(), hp().step(), hp().fineRadius());
        final long[] samples = {0};
        WindowOutlets.Terrain terrain = (x, z) -> { samples[0]++; return GEN.erodedHeight(x / TP.horizontalScale(), z / TP.horizontalScale()); };
        WindowOutlets.Terrain boundary = (x, z) -> HierarchicalHydrology.owner((int) Math.floor(x), (int) Math.floor(z), hp(),
                PROV::coarseLayerFor).levelAt((int) Math.floor(x), (int) Math.floor(z));
        var req = new HydrologyPipeline.Request(geom.centerX(), geom.centerZ(), geom.halfSpan(), geom.step(),
                new LakeWaterBalance.Climate(0.004, 0.002, 0.3), 0, HydrologyPipeline.RiverModel.D8, 1, null);
        var p = HydrologyPipeline.run(GEN, TP, req, new HydrologyPipeline.Layer(terrain, boundary, true, "window-contract"));
        var grid = new WindowGrid(p, geom.originX(), geom.originZ());
        log("W2 fixedSharedPipelineCalls=1 nodes=" + p.bed().length + " actualTerrainSamples=" + samples[0]
                + " tileQueryMissesNotUnique=" + (GEN.tileCacheStats().misses() - misses)
                + " coarseSamples=" + (HierarchicalHydrology.Diag.COARSE_SAMPLES.get() - coarse)
                + " elapsedMs=" + (System.nanoTime() - start) / 1e6 + " liveHeapDeltaNotPeak="
                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - memory));
        log("W2 erosionTilesBefore=" + tiles0 + " after=" + GEN.erosionTileCacheSize()
                + " coldCommonFirst=" + (tiles0 == 0) + " coldHeapDeltaNotPeak=true rawReceiverCallsLater=1 diagnosticOwnerFinalNotProducerInput=true");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0 == HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "共同管线先独立构建，只读地形/owner粗层，零邻finalfine/共享fine依赖（仍有一次重管线）", "fanout");
        return grid;
    }

    private static void windowCompare(Path dir, WindowGrid owner, WindowGrid grid, List<WindowEntry> entries, boolean acceptance) throws Exception {
        var p = grid.pipe();
        var frozen = new HydrologyPipeline.Result(p.nx(), p.nz(), p.cellBlocks(), p.bed().clone(), p.sea().clone(), p.resolved().clone(),
                p.level().clone(), p.depth().clone(), p.lake().clone(), p.parent().clone(), p.discharge().clone(), p.channel().clone(),
                p.width().clone(), p.lakeResult(), p.riverResult(), p.deathMask(), p.waterSurface().clone(), p.couplingRounds(),
                p.couplingConverged(), p.couplingResidual());
        var a = windowBuild(grid, List.of()); var seeds = windowSeeds(grid, entries); var b = windowBuild(grid, seeds);
        chk(sameSourcePipe(frozen, p), "A/B选择不改bed/parent/level/Q/lake/sea/resolved/waterSurface", "mutated");
        windowConcurrency(grid, seeds, b);
        chk(sameSourcePipe(frozen, p), "并发及取消重试后共同管线输入逐位未修改", "mutated");
        var reverse = new ArrayList<>(entries); java.util.Collections.reverse(reverse); reverse.addAll(entries);
        var again = windowBuild(grid, windowSeeds(grid, reverse));
        chk(windowSame(b, again), "乱序重复入口同完整骨架，不按源注Q或重复起链", "different");
        var paths = windowCompareRows(dir, owner, grid, entries, a, b);
        windowProfiles(dir, grid, a, b);
        renderWindowExperiment(dir, grid, entries, a, b);
        long pending = paths.stream().filter(v -> !v.firstEdge() || !v.terminal().equals("LOCAL_SEA_NOT_GLOBAL_CERTIFICATE")).count();
        windowAttribution(dir, owner, grid, entries, a, b);
        if (Boolean.getBoolean("geogenesis.windowCauseProbe")) windowCause(dir, grid, entries, a, b);
        if (Boolean.getBoolean("geogenesis.windowSuccessorProbe")) windowSuccessor(dir, grid, entries, a, b);
        chk(sameSourcePipe(frozen, p), "全部消融/几何/重放完成后原管线输入仍逐位相同", "mutated");
        log("W4 A traces=" + a.traces.size() + " B traces=" + b.traces.size() + " uniqueIncoming=" + seeds.size()
                + " obligations=" + paths.size() + " localPendingOrDiverged=" + pending + " globalUncertified=" + paths.size()
                + " GLOBAL_SOURCE_AND_BASIN_AUTHORITY_PENDING");
        if (acceptance) chk(false, "诊断实际源不是独立源权威，未知盆/几何未过不得接生产", "GLOBAL_SOURCE_AND_BASIN_AUTHORITY_PENDING");
    }

    private record SuccessorEdge(int from, int to) { }
    private record SuccessorProjection(Map<WindowEntry, WindowPath> paths,
            Map<SuccessorEdge, Set<SharedBoundaryDrainage.WorldCell>> support) { }

    private static WindowGraph freezeWindowSuccessor(WindowGrid grid) {
        var p = grid.pipe(); var next = new HashMap<Integer, List<Integer>>(); var ends = new HashMap<Integer, String>();
        for (int k = 0; k < p.parent().length; k++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("freeze successor");
            int parent = p.parent()[k];
            if (p.sea()[k]) ends.put(k, "MOUTH_SEA");
            else if (p.lake()[k]) ends.put(k, "MOUTH_LAKE");
            else if (parent < 0) ends.put(k, "DEAD_END");
            else if (parent >= p.parent().length) ends.put(k, "INVALID_PARENT_UNKNOWN");
            else if (parent == k) ends.put(k, "SELF_PARENT_UNKNOWN");
            else next.put(k, List.of(parent));
        }
        return new WindowGraph(Map.copyOf(next), Map.copyOf(ends));
    }

    private static SuccessorProjection projectSuccessor(WindowGrid grid, WindowGraph frozen, List<WindowEntry> entries) {
        if (entries.size() > 4096) throw new IllegalArgumentException("bounded successor obligations");
        var paths = new HashMap<WindowEntry, WindowPath>(); var support = new HashMap<SuccessorEdge, Set<SharedBoundaryDrainage.WorldCell>>();
        for (var entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("project successor");
            var path = windowFollow(grid, frozen, entry); paths.putIfAbsent(entry, path);
            // A mismatched declared edge is evidence, never an implicit bridge into the raw graph.
            if (!path.firstEdge() || path.terminal().equals("CYCLE") || path.terminal().equals("INVALID_PARENT_UNKNOWN")
                    || path.terminal().equals("SELF_PARENT_UNKNOWN")) continue;
            for (int i = 1; i < path.cells().size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("support edge");
                var edge = new SuccessorEdge(path.cells().get(i - 1), path.cells().get(i));
                support.computeIfAbsent(edge, ignored -> new java.util.HashSet<>()).add(entry.source());
            }
        }
        var immutable = new HashMap<SuccessorEdge, Set<SharedBoundaryDrainage.WorldCell>>();
        support.forEach((e, sources) -> immutable.put(e, Set.copyOf(sources)));
        return new SuccessorProjection(Map.copyOf(paths), Map.copyOf(immutable));
    }

    private static void windowSuccessor(Path dir, WindowGrid grid, List<WindowEntry> entries,
                                        DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        long start = System.nanoTime(); var frozen = freezeWindowSuccessor(grid); var snapshot = windowGraph(b);
        var all = projectSuccessor(grid, frozen, entries); var reverse = new ArrayList<>(entries);
        java.util.Collections.reverse(reverse); reverse.addAll(entries);
        chk(all.equals(projectSuccessor(grid, frozen, reverse)), "冻结后继：乱序重复入口与完整支持账本同值", "different");
        boolean preserved = true;
        for (var entry : entries) {
            var single = projectSuccessor(grid, frozen, List.of(entry));
            preserved &= single.paths().get(entry).equals(all.paths().get(entry));
            for (var edge : single.support().keySet()) preserved &= all.support().get(edge).contains(entry.source());
        }
        chk(preserved, "源逐一增删不改任何已有完整后缀及终端，不只比较第一边", "changed");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<SuccessorProjection>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> projectSuccessor(grid, frozen, entries)));
            for (var job : jobs) chk(all.equals(job.get()), "冻结后继四消费者并发完整路径与支持同值", "different");
        } finally { pool.shutdownNow(); }
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); projectSuccessor(grid, frozen, entries); }
        catch (java.util.concurrent.CancellationException ex) { cancel = true; } finally { Thread.interrupted(); }
        chk(cancel && all.equals(projectSuccessor(grid, frozen, entries)), "支持投影预取消不发布部分产物，重试同值", "different");
        chk(snapshot.equals(windowGraph(b)), "投影不改B加工前骨架", "mutated");
        double elapsed = (System.nanoTime() - start) / 1e6;
        writeSuccessor(dir, grid, entries, all, a, b);
        renderSuccessor(dir, grid, all, "successor-support");
        auditSuccessorPlanes(dir, grid, all);
        windowDrySuccessor(dir, grid, entries, frozen);
        if (Boolean.getBoolean("geogenesis.windowGradientProbe")) auditWindowGradient(dir, grid, all);
        log("Y1 frozenGraphNodes=" + (frozen.next().size() + frozen.ends().size()) + " supportEdges=" + all.support().size()
                + " sourceEdgePairs=" + all.support().values().stream().mapToInt(Set::size).sum()
                + " graphProjectionAndRepeatedVerificationMs=" + elapsed
                + " pipelineCallsAdded=0 RAW_PARENT_ORACLE_NOT_PRODUCTION_AUTHORITY=true");
    }

    private static void writeSuccessor(Path dir, WindowGrid grid, List<WindowEntry> entries, SuccessorProjection projection,
                                        DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        var summary = new StringBuilder("sourceX,sourceZ,fromX,fromZ,toX,toZ,sourceInside,firstEdge,terminal,nodes,AFirst,BFirst,status\n");
        var paths = new StringBuilder("sourceX,sourceZ,entryX,entryZ,node,x,z,terminal,resolved,lake,sea\n");
        var ga = windowGraph(a); var gb = windowGraph(b); int matched = 0, unknown = 0, mismatch = 0;
        var terminals = new java.util.TreeMap<String, Integer>();
        for (var e : entries) {
            var p = projection.paths().get(e);
            boolean rejected = p.terminal().equals("CYCLE") || p.terminal().equals("INVALID_PARENT_UNKNOWN") || p.terminal().equals("SELF_PARENT_UNKNOWN");
            String status = rejected ? "INVALID_SUCCESSOR_NO_SUPPORT" : p.firstEdge() ? "RAW_EDGE_SUPPORTED_GLOBAL_PENDING"
                    : p.cells().isEmpty() ? "ENTRY_UNKNOWN" : "DECLARED_EDGE_NOT_RAW_SUCCESSOR";
            if (rejected) unknown++; else if (p.firstEdge()) matched++; else if (p.cells().isEmpty()) unknown++; else mismatch++;
            terminals.merge(p.terminal(), 1, Integer::sum);
            summary.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                    .append(',').append(e.to().x()).append(',').append(e.to().z()).append(',').append(grid.index(e.source()) >= 0)
                    .append(',').append(p.firstEdge()).append(',').append(p.terminal()).append(',').append(p.cells().size())
                    .append(',').append(windowFollow(grid, ga, e).firstEdge()).append(',').append(windowFollow(grid, gb, e).firstEdge())
                    .append(',').append(status).append('\n');
            for (int i = 0; i < p.cells().size(); i++) {
                int k = p.cells().get(i); var w = grid.world(k);
                paths.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                        .append(',').append(i).append(',').append(w.x()).append(',').append(w.z()).append(',').append(p.terminal())
                        .append(',').append(grid.pipe().resolved()[k]).append(',').append(grid.pipe().lake()[k]).append(',').append(grid.pipe().sea()[k]).append('\n');
            }
        }
        var ledger = new java.util.TreeSet<String>();
        projection.support().forEach((edge, sources) -> {
            var from = grid.world(edge.from()); var to = grid.world(edge.to());
            for (var source : sources) ledger.add(from.x() + "," + from.z() + "," + to.x() + "," + to.z() + "," + source.x() + "," + source.z());
        });
        Files.writeString(dir.resolve("successor-obligations.csv"), summary); Files.writeString(dir.resolve("successor-paths.csv"), paths);
        Files.writeString(dir.resolve("successor-support.csv"), "fromX,fromZ,toX,toZ,sourceX,sourceZ\n" + String.join("\n", ledger) + "\n");
        log("Y2 rawSupported=" + matched + " declaredShortcutMismatch=" + mismatch + " entryUnknown=" + unknown
                + " terminalCounts=" + terminals + " globalUncertified=" + entries.size()
                + " mismatchPathsAreDiagnosticOnlyNotSupport=true noQInjection=true");
    }

    private static void renderSuccessor(Path dir, WindowGrid grid, SuccessorProjection projection, String name) throws Exception {
        var base = javax.imageio.ImageIO.read(dir.resolve("production.png").toFile());
        int n = 2 * hp().fineRadius() + 1, header = base.getHeight() - n * 3;
        var image = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.drawImage(base, 0, 0, n, n, 0, header, base.getWidth(), base.getHeight(), null);
        var orderedEdges = new ArrayList<>(projection.support().entrySet());
        orderedEdges.sort(java.util.Comparator.<Map.Entry<SuccessorEdge, Set<SharedBoundaryDrainage.WorldCell>>>comparingInt(e -> e.getKey().from())
                .thenComparingInt(e -> e.getKey().to()));
        for (var entry : orderedEdges) {
            g.setColor(entry.getValue().size() > 1 ? java.awt.Color.ORANGE : java.awt.Color.YELLOW);
            var a = grid.world(entry.getKey().from()); var b = grid.world(entry.getKey().to());
            g.drawLine(a.x() - grid.ox(), a.z() - grid.oz(), b.x() - grid.ox(), b.z() - grid.oz());
        }
        var orderedPaths = new ArrayList<>(projection.paths().values());
        orderedPaths.sort(java.util.Comparator.comparingInt((WindowPath p) -> p.entry().from().x()).thenComparingInt(p -> p.entry().from().z())
                .thenComparing(p -> p.firstEdge() ? 0 : 1));
        for (var p : orderedPaths) {
            if (p.cells().isEmpty()) continue; var w = grid.world(p.cells().get(0));
            g.setColor(p.firstEdge() ? java.awt.Color.GREEN : java.awt.Color.MAGENTA);
            g.fillRect(w.x() - grid.ox() - 2, w.z() - grid.oz() - 2, 5, 5);
        }
        g.dispose();
        writeLocalImage(dir.resolve(name + ".png"), image, -768, 512, hp().fineRadius(),
                name + " / YELLOW ONE SOURCE / ORANGE MERGED / MAGENTA MISMATCH / NOT WATER");
        javax.imageio.ImageIO.write(image, "png", dir.resolve(name + "-raw.png").toFile());
    }

    private record DirectionVector(double x, double z) { }
    private record GradientEvidence(int cell, int parent, double centerHeight, double valleyHeight, double witnessX, double witnessZ,
            DirectionVector fine, DirectionVector broad, DirectionVector witness, DirectionVector centerD8, DirectionVector valleyD8,
            double parentAngle, double centerAngle, double valleyAngle, double scaleAngle, double witnessAngle, boolean dryResolved) { }

    private static DirectionVector downhillGradient(WindowOutlets.Terrain terrain, double x, double z, double epsilon) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("gradient samples");
        return new DirectionVector((terrain.height(x-epsilon,z)-terrain.height(x+epsilon,z))/(2*epsilon),
                (terrain.height(x,z-epsilon)-terrain.height(x,z+epsilon))/(2*epsilon));
    }

    private static DirectionVector terrainD8(WindowOutlets.Terrain terrain, double x, double z, double step) {
        double center = terrain.height(x,z), best = 0; DirectionVector result = new DirectionVector(0,0);
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            if (dx == 0 && dz == 0) continue;
            double slope = (center-terrain.height(x+dx*step,z+dz*step))/(step*Math.hypot(dx,dz));
            if (slope > best) { best = slope; result = new DirectionVector(dx,dz); }
        }
        return result;
    }

    private static double directionAngle(DirectionVector a, DirectionVector b) {
        double length = Math.hypot(a.x(),a.z())*Math.hypot(b.x(),b.z());
        if (!(length > 1e-16) || !Double.isFinite(length)) return Double.NaN;
        return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(a.x()*b.x()+a.z()*b.z())/length))));
    }

    private static GradientEvidence gradientEvidence(WindowGrid grid, SuccessorEdge edge, WindowOutlets.Terrain terrain) {
        var p = grid.pipe(); var w = grid.world(edge.from()); var to = grid.world(edge.to()); double half=p.cellBlocks()*0.5;
        var fine=downhillGradient(terrain,w.x(),w.z(),0.25); var broad=downhillGradient(terrain,w.x(),w.z(),2);
        double lowest=Double.POSITIVE_INFINITY, wx=0, wz=0;
        for (int dx : new int[]{-1,1}) for (int dz : new int[]{-1,1}) {
            double x=w.x()+dx*half,z=w.z()+dz*half,h=terrain.height(x,z);
            if (h < lowest) { lowest=h;wx=x;wz=z; }
        }
        var witness=downhillGradient(terrain,wx,wz,0.25);
        var center=terrainD8(terrain,w.x(),w.z(),p.cellBlocks());
        int ix=edge.from()/p.nz(),iz=edge.from()%p.nz(); DirectionVector valley=new DirectionVector(0,0);
        if (ix>0 && iz>0 && ix+1<p.nx() && iz+1<p.nz()) {
            var field = (WindowOutlets.Terrain)(x,z) -> p.bed()[grid.index(new SharedBoundaryDrainage.WorldCell((int)x,(int)z))];
            valley=terrainD8(field,w.x(),w.z(),p.cellBlocks());
        }
        var parent=new DirectionVector(to.x()-w.x(),to.z()-w.z());
        return new GradientEvidence(edge.from(),edge.to(),terrain.height(w.x(),w.z()),lowest,wx,wz,fine,broad,witness,center,valley,
                directionAngle(parent,fine),directionAngle(center,fine),directionAngle(valley,fine),directionAngle(fine,broad),
                directionAngle(fine,witness),p.resolved()[edge.from()] && p.resolved()[edge.to()]
                && Double.doubleToLongBits(p.level()[edge.from()])==Double.doubleToLongBits(p.bed()[edge.from()])
                && Double.doubleToLongBits(p.level()[edge.to()])==Double.doubleToLongBits(p.bed()[edge.to()]));
    }

    private static void auditWindowGradient(Path dir, WindowGrid grid, SuccessorProjection projection) throws Exception {
        long start=System.nanoTime(); long[] samples={0};
        WindowOutlets.Terrain terrain=(x,z)->{samples[0]++;return GEN.erodedHeight(x/TP.horizontalScale(),z/TP.horizontalScale());};
        var edges=new ArrayList<>(projection.support().keySet()); edges.sort(java.util.Comparator.comparingInt(SuccessorEdge::from).thenComparingInt(SuccessorEdge::to));
        var evidence=new ArrayList<GradientEvidence>();
        var csv=new StringBuilder("x,z,toX,toZ,dryResolved,centerHeight,valleyHeight,pipeBed,witnessX,witnessZ,gx025,gz025,gx2,gz2,centerDx,centerDz,valleyDx,valleyDz,parentAngle,centerD8Angle,valleyD8Angle,scaleAngle,witnessAngle\n");
        int bedMismatch=0,parentOff=0,centerOff=0,valleyOff=0,unstable=0,dry=0,flat=0;
        for (var edge:edges) {
            var v=gradientEvidence(grid,edge,terrain); evidence.add(v); var w=grid.world(edge.from()); var to=grid.world(edge.to());
            double pipeBed=grid.pipe().bed()[edge.from()];
            if (Double.doubleToLongBits(v.valleyHeight())!=Double.doubleToLongBits(pipeBed)) bedMismatch++;
            if (v.dryResolved()) { dry++;if(v.parentAngle()>30)parentOff++;if(v.centerAngle()>30)centerOff++;if(v.valleyAngle()>30)valleyOff++;
                if(v.scaleAngle()>15)unstable++;if(!Double.isFinite(v.parentAngle()))flat++; }
            csv.append(w.x()).append(',').append(w.z()).append(',').append(to.x()).append(',').append(to.z()).append(',').append(v.dryResolved())
                    .append(',').append(v.centerHeight()).append(',').append(v.valleyHeight()).append(',').append(pipeBed)
                    .append(',').append(v.witnessX()).append(',').append(v.witnessZ()).append(',').append(v.fine().x()).append(',').append(v.fine().z())
                    .append(',').append(v.broad().x()).append(',').append(v.broad().z()).append(',').append(v.centerD8().x()).append(',').append(v.centerD8().z())
                    .append(',').append(v.valleyD8().x()).append(',').append(v.valleyD8().z()).append(',').append(v.parentAngle()).append(',').append(v.centerAngle())
                    .append(',').append(v.valleyAngle()).append(',').append(v.scaleAngle()).append(',').append(v.witnessAngle()).append('\n');
        }
        Files.writeString(dir.resolve("gradient-directions.csv"),csv);
        chk(WindowOutlets.VALLEY_MIN && bedMismatch==0,"四角最小真实高度逐位重建生产bed，不把格心当谷底见证", "mismatch="+bedMismatch);
        log("G7 gradientEdges="+edges.size()+" dryResolved="+dry+" dryParentAngleGT30="+parentOff+" centerD8GT30="+centerOff+" valleyD8GT30="+valleyOff
                +" gradient025vs2GT15="+unstable+" flatOrInvalidGradient="+flat+" actualHeightCalls="+samples[0]+" elapsedMs="+(System.nanoTime()-start)/1e6
                +" FINITE_DIFFERENCE_NOT_CONTINUOUS_CERTIFICATE=true NO_ROUTING_CHANGE=true");
        renderGradientEvidence(dir,grid,evidence);
        auditContinuousDryPaths(dir,grid,projection.paths().keySet().stream().toList(),terrain);
    }

    private record DryPoint(double x, double z, double height) { }
    private record DryPath(SharedBoundaryDrainage.WorldCell source, List<DryPoint> points, String terminal) {
        DryPath { points=List.copyOf(points); }
    }
    private record DryCrossing(int axis, int boundary, double x, double z, int direction) { }

    private record RollingState(double x, double z, double height, double dx, double dz, int step) { }
    private record RollingMove(int node, double oldX, double oldZ, double newX, double newZ, RollingState state) { }
    private record RollingRun(SharedBoundaryDrainage.WorldCell source, List<RollingState> nodes,
                              String terminal, List<RollingMove> moves, long samples, int peakEntries) {
        RollingRun { nodes=List.copyOf(nodes); moves=List.copyOf(moves); }
    }
    private record RollingKey(double x, double z) { }
    private static final class RollingLimit extends RuntimeException { }

    /** Diagnostic exact-coordinate cache: no resampling/interpolation or window-local physics. */
    private static final class RollingSampler implements WindowOutlets.Terrain {
        final WindowOutlets.Terrain terrain;
        final double half, trigger;
        final boolean moving;
        final Map<RollingKey,Double> cache=new HashMap<>();
        final List<RollingMove> moves=new ArrayList<>();
        double cx,cz;
        long samples;
        int peak;
        RollingSampler(WindowOutlets.Terrain terrain,double x,double z,double half,double trigger,boolean moving) {
            this.terrain=terrain;this.cx=x;this.cz=z;this.half=half;this.trigger=trigger;this.moving=moving;
            if(!(half>=4 && trigger>0 && trigger<=half-2))throw new IllegalArgumentException("rolling geometry");
        }
        boolean prepare(RollingState state) {
            rollingCancelled();
            double distance=Math.max(Math.abs(state.x()-cx),Math.abs(state.z()-cz));
            if(moving && distance>trigger) {
                double ox=cx,oz=cz;cx=state.x();cz=state.z();
                moves.add(new RollingMove(state.step(),ox,oz,cx,cz,state));
                cache.keySet().removeIf(k->Math.abs(k.x()-cx)>half || Math.abs(k.z()-cz)>half);
            }
            return Math.abs(state.x()-cx)<=half-2 && Math.abs(state.z()-cz)<=half-2;
        }
        @Override public double height(double x,double z) {
            rollingCancelled();
            if(Math.abs(x-cx)>half || Math.abs(z-cz)>half)throw new RollingLimit();
            var key=new RollingKey(x,z);Double old=cache.get(key);if(old!=null)return old;
            double value=terrain.height(x,z);samples++;
            if(!Double.isFinite(value))throw new IllegalArgumentException("nonfinite rolling terrain");
            cache.put(key,value);peak=Math.max(peak,cache.size());return value;
        }
    }

    private static void rollingCancelled() {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("rolling diagnostic");
    }

    private static DirectionVector rollingDirection(WindowOutlets.Terrain terrain,double x,double z,RollingState state) {
        var g=downhillGradient(terrain,x,z,0.25);double n=Math.hypot(g.x(),g.z());
        if(!(n>1e-12))return new DirectionVector(0,0);
        // Same diagnostic inertia in FIXED, ROLLING and UNWINDOWED controls; not a production parameter.
        double dx=0.8*g.x()/n+0.2*state.dx(),dz=0.8*g.z()/n+0.2*state.dz(),length=Math.hypot(dx,dz);
        return new DirectionVector(dx/length,dz/length);
    }

    private static RollingState rollingAdvance(RollingSampler terrain,RollingState from,double step) {
        var v=rollingDirection(terrain,from.x(),from.z(),from);if(Math.hypot(v.x(),v.z())==0)return null;
        var m=rollingDirection(terrain,from.x()+0.5*step*v.x(),from.z()+0.5*step*v.z(),from);
        if(Math.hypot(m.x(),m.z())==0)return null;
        double x=from.x()+step*m.x(),z=from.z()+step*m.z(),previous=from.height();
        for(int i=1;i<=8;i++) {
            double h=terrain.height(from.x()+(x-from.x())*i/8,from.z()+(z-from.z())*i/8);
            if(h>previous+1e-10)return null;previous=h;
        }
        return previous<from.height()-1e-12?new RollingState(x,z,previous,m.x(),m.z(),from.step()+1):null;
    }

    private static RollingRun rollingTrace(SharedBoundaryDrainage.WorldCell source,WindowOutlets.Terrain raw,
            double half,double trigger,double offsetX,double offsetZ,boolean moving,double sea,int limit,boolean reset) {
        var sampler=new RollingSampler(raw,source.x()+offsetX,source.z()+offsetZ,half,trigger,moving);
        var state=new RollingState(source.x(),source.z(),sampler.height(source.x(),source.z()),0,0,0);
        return rollingContinue(source,sampler,state,sea,limit,reset);
    }

    private static RollingRun rollingResume(SharedBoundaryDrainage.WorldCell source,WindowOutlets.Terrain raw,RollingState state,
            double half,double trigger,double offsetX,double offsetZ,boolean moving,double sea,int limit,boolean reset) {
        var sampler=new RollingSampler(raw,state.x()+offsetX,state.z()+offsetZ,half,trigger,moving);
        if(Double.doubleToLongBits(sampler.height(state.x(),state.z()))!=Double.doubleToLongBits(state.height()))
            throw new IllegalArgumentException("resume terrain/state mismatch");
        return rollingContinue(source,sampler,state,sea,limit,reset);
    }

    private static RollingRun rollingContinue(SharedBoundaryDrainage.WorldCell source,RollingSampler sampler,RollingState state,
                                               double sea,int limit,boolean reset) {
        var nodes=new ArrayList<RollingState>();nodes.add(state);String terminal="STEP_BUDGET_PENDING";
        for(int i=0;i<limit;i++) {
            rollingCancelled();
            if(state.height()<=sea){terminal="BELOW_SEA_CANDIDATE_NOT_OCEAN_CERTIFICATE";break;}
            int before=sampler.moves.size();
            if(!sampler.prepare(state)){terminal="WORK_WINDOW_LIMIT";break;}
            var input=reset && sampler.moves.size()>before?new RollingState(state.x(),state.z(),state.height(),0,0,state.step()):state;
            RollingState next=null;
            for(int h=0;h<11 && next==null;h++)next=rollingAdvance(sampler,input,1.0/Math.scalb(1,h));
            if(next==null){terminal="SAMPLED_LOCAL_MINIMUM_OR_FLAT_PENDING";break;}
            nodes.add(next);state=next;
        }
        return new RollingRun(source,nodes,terminal,sampler.moves,sampler.samples,sampler.peak);
    }

    private static boolean rollingSame(RollingRun a,RollingRun b) {
        return a.source().equals(b.source()) && a.nodes().equals(b.nodes()) && a.terminal().equals(b.terminal());
    }

    private static boolean rollingPrefix(RollingRun shortRun,RollingRun full) {
        return shortRun.nodes().size()<=full.nodes().size()
                && shortRun.nodes().equals(full.nodes().subList(0,shortRun.nodes().size()));
    }

    private static void rollingSynthetic() throws Exception {
        log("\n=== [rolling-synth] same integrator, retained state, exact bounded working cache ===");
        var source=new SharedBoundaryDrainage.WorldCell(-40,-40);
        WindowOutlets.Terrain curve=(x,z)->1000-z+0.015*(x-0.003*z*z)*(x-0.003*z*z);
        var oracle=rollingTrace(source,curve,10000,9000,0,0,false,-10000,180,false);
        var roll=rollingTrace(source,curve,24,8,0,0,true,-10000,180,false);
        var fixed=rollingTrace(source,curve,24,8,0,0,false,-10000,180,false);
        chk(rollingSame(roll,oracle) && roll.moves().size()>3,"滚动确实越过多个工作窗且完整状态逐节点等于无窗对照","wrong");
        chk(fixed.terminal().equals("WORK_WINDOW_LIMIT") && fixed.nodes().size()<roll.nodes().size() && rollingPrefix(fixed,roll),
                "固定窗真实截断且为同源严格前缀，不换积分器制造收益","wrong");
        for(int offset:new int[]{-6,0,6})for(int trigger:new int[]{6,14})
            chk(rollingSame(roll,rollingTrace(source,curve,24,trigger,offset,-offset,true,-10000,180,false)),
                    "平移窗口/改变推进阈值不改完整轨迹 offset="+offset+" trigger="+trigger,"different");
        var reset=rollingTrace(source,curve,24,8,0,0,true,-10000,180,true);
        chk(!rollingSame(roll,reset),"故意在换窗清方向必须改变弯坡轨迹（保状态判据有牙齿）","vacuous");
        var flat=rollingTrace(source,(x,z)->100,24,8,0,0,true,0,180,false);
        var bowl=rollingTrace(new SharedBoundaryDrainage.WorldCell(-8,0),(x,z)->100+x*x+z*z,24,8,0,0,true,0,180,false);
        chk(flat.nodes().size()==1 && flat.terminal().endsWith("PENDING") && bowl.terminal().endsWith("PENDING"),
                "真平地/洼地不能靠移动窗口强造出口或海","wrong");
        var diagonal=rollingTrace(source,(x,z)->1000-x-z,24,8,0,0,true,-10000,160,false);
        var points=diagonal.nodes().stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList();
        var crosses=dryCrossings(points,32);
        chk(crosses.stream().anyMatch(a->crosses.stream().anyMatch(b->a.axis()!=b.axis() && a.x()==b.x() && a.z()==b.z()))
                && diagonal.moves().size()>3,"负坐标斜穿角点两轴均登记且多次滚动续算","no corner");
        boolean cancelled=false;
        try {Thread.currentThread().interrupt();rollingTrace(source,curve,24,8,0,0,true,0,180,false);}
        catch(java.util.concurrent.CancellationException e){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && rollingSame(roll,rollingTrace(source,curve,24,8,0,0,true,-10000,180,false)),
                "取消不返回部分产物，重试完整同值","wrong");
        var guard=new RollingSampler(curve,0,0,24,8,true);boolean blocked=false;
        try {guard.height(25,0);}catch(RollingLimit e){blocked=true;}
        chk(blocked && guard.samples==0,"窗口外采样被拒绝，不偷用无界地形调用","unguarded");
        boolean analytic=true;
        for(int angle=0;angle<12;angle++) {
            double ux=Math.cos(angle*Math.PI/6),uz=Math.sin(angle*Math.PI/6);
            var rotated=rollingTrace(source,(x,z)->1000-ux*x-uz*z,24,8,0,0,true,-10000,80,false);
            var end=rotated.nodes().get(rotated.nodes().size()-1);
            analytic &= rotated.nodes().size()==81 && Math.abs(end.x()-source.x()-80*ux)<1e-8
                    && Math.abs(end.z()-source.z()-80*uz)<1e-8 && rotated.moves().size()>3;
        }
        chk(analytic,"十二旋转平坡独立解析端点oracle，非仅同积分器对照","wrong");
        guard.height(0,0);guard.prepare(new RollingState(20,20,0,1,0,1));
        guard.prepare(new RollingState(40,40,0,1,0,2));
        chk(guard.cache.isEmpty(),"滚动确实淘汰已离开工作窗的采样，不积攒全路径缓存","retained");
    }

    private static List<SharedBoundaryDrainage.WorldCell> rollingSites() {
        return List.of(new SharedBoundaryDrainage.WorldCell(-776,376),new SharedBoundaryDrainage.WorldCell(-712,128),
                new SharedBoundaryDrainage.WorldCell(-676,400),new SharedBoundaryDrainage.WorldCell(-650,511),
                new SharedBoundaryDrainage.WorldCell(-582,511));
    }

    private static Map<SharedBoundaryDrainage.WorldCell,RollingRun> rollingRuns(CellGenerator gen,List<SharedBoundaryDrainage.WorldCell> sites,
                                                                              double trigger,double offset) {
        var result=new java.util.LinkedHashMap<SharedBoundaryDrainage.WorldCell,RollingRun>();double hs=TP.horizontalScale();
        WindowOutlets.Terrain terrain=(x,z)->gen.erodedHeight(x/hs,z/hs);
        for(var site:sites)result.put(site,rollingTrace(site,terrain,32,trigger,offset,-offset,true,TP.seaLevel(),2048,false));
        return result;
    }

    private static void secRollingWindow(long seed) throws Exception {
        log("\n=== [rolling-window] diagnostic trajectories NOT production rivers/water/basin authority ===");
        Path dir=OUT.resolve("rolling-window-"+System.currentTimeMillis());Files.createDirectories(dir);
        var sites=rollingSites();long fine0=HierarchicalHydrology.Diag.FINE_SOLVES.get();
        long started=System.nanoTime();var cold=newGen(TP,seed);var runs=rollingRuns(cold,sites,8,0);
        log("G42 COLD samplingCalls="+runs.values().stream().mapToLong(RollingRun::samples).sum()
                +" retainedErosionTiles="+cold.erosionTileCacheSize()+" tileQueryMissesNotUnique="+cold.tileCacheStats().misses()
                +" elapsedMs="+(System.nanoTime()-started)/1e6+" trajectoriesOnly=true NOT_WHOLE_PROVIDER_COST=true");
        chk(HierarchicalHydrology.Diag.FINE_SOLVES.get()==fine0,"滚动追踪仅生产侵蚀高度，零水文fine管线","fine added");
        var reverse=new ArrayList<>(sites);java.util.Collections.reverse(reverse);
        var warm=rollingRuns(cold,reverse,20,7);var independent=rollingRuns(newGen(TP,seed),reverse,12,-7);
        boolean same=true;for(var site:sites)same &= rollingSame(runs.get(site),warm.get(site)) && rollingSame(runs.get(site),independent.get(site));
        chk(same,"真实五起点冷/热/逆序/独立引擎/平移推进阈值完整路径逐位一致","different");
        rollingConcurrent(cold,sites,runs);
        var summary=new StringBuilder("kind,startX,startZ,variant,nodes,terminal,moves,heightCalls,peakEntries,crossings,strictPrefix,globalCertified\n");
        var points=new StringBuilder("kind,startX,startZ,variant,node,x,z,height,dirX,dirZ,terminal\n");
        var moves=new StringBuilder("startX,startZ,node,oldX,oldZ,newX,newZ,x,z,height,dirX,dirZ\n");
        double hs=TP.horizontalScale();WindowOutlets.Terrain terrain=(x,z)->cold.erodedHeight(x/hs,z/hs);
        for(int i=0;i<sites.size();i++) {
            var site=sites.get(i);var run=runs.get(site);
            var fixed=rollingTrace(site,terrain,32,8,0,0,false,TP.seaLevel(),2048,false);
            var oracle=rollingTrace(site,terrain,10000,9000,0,0,false,TP.seaLevel(),2048,false);
            chk(rollingSame(run,oracle) && rollingPrefix(fixed,run),"真实同积分器无窗对照/固定窗严格前缀 start="+site,"different");
            boolean stateKept=run.moves().stream().allMatch(m->run.nodes().get(m.node()).equals(m.state()));
            chk(stateKept,"真实换窗保留位置/方向/高度/累计步数 start="+site,"reset");
            chk(run.moves().size()>0 && run.nodes().size()>fixed.nodes().size(),"真实确有多窗续算收益（非缓存同值空转） start="+site,"no movement");
            String kind=i<3?"PUBLISHED_SOURCE_ID":"WET_BREAKPOINT_SUFFIX_NOT_NEW_SOURCE";
            writeRollingRows(summary,points,moves,kind,"ROLLING",run,true);
            writeRollingRows(summary,points,moves,kind,"FIXED",fixed,rollingPrefix(fixed,run));
            writeRollingRows(summary,points,moves,kind,"UNWINDOWED_CONTROL",oracle,true);
            log("G42 TRAJECTORY start="+site+" kind="+kind+" fixedNodes="+fixed.nodes().size()+" rollingNodes="+run.nodes().size()
                    +" terminal="+run.terminal()+" moves="+run.moves().size()+" addedAfterWorkFrame="+(run.nodes().size()-fixed.nodes().size())
                    +" last="+run.nodes().get(run.nodes().size()-1)+" NOT_WATER=true");
        }
        Files.writeString(dir.resolve("summary.csv"),summary);Files.writeString(dir.resolve("points.csv"),points);Files.writeString(dir.resolve("moves.csv"),moves);
        rollingProductionEvidence(dir,sites);
        rollingObservedStops(dir,runs);
        Files.writeString(dir.resolve("metrics.txt"),LOG);
        // #region agent log: G42 bounded rolling experiment; preserve prior evidence
        Files.writeString(Path.of("..","debug-agent.log").toAbsolutePath(),LOG.toString(),java.nio.file.StandardOpenOption.APPEND);
        // #endregion
        log("ROLLING_WINDOW_DIR="+dir.toAbsolutePath());
    }

    private static void rollingConcurrent(CellGenerator gen,List<SharedBoundaryDrainage.WorldCell> sites,
                                           Map<SharedBoundaryDrainage.WorldCell,RollingRun> expected) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Map<SharedBoundaryDrainage.WorldCell,RollingRun>>>();
            for(int i=0;i<3;i++)jobs.add(pool.submit(()->rollingRuns(gen,sites,16,3)));
            boolean same=true;for(var job:jobs) {var actual=job.get(180,java.util.concurrent.TimeUnit.SECONDS);
                for(var site:sites)same &= rollingSame(expected.get(site),actual.get(site));}
            chk(same,"三并发消费者不共享可变粒子状态，完整五轨迹逐位一致","different");
        } finally {pool.shutdownNow();}
    }

    private static void writeRollingRows(StringBuilder summary,StringBuilder points,StringBuilder moves,String kind,String variant,
                                         RollingRun run,boolean prefix) {
        var path=run.nodes().stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList();
        summary.append(kind).append(',').append(run.source().x()).append(',').append(run.source().z()).append(',').append(variant)
                .append(',').append(run.nodes().size()).append(',').append(run.terminal()).append(',').append(run.moves().size())
                .append(',').append(run.samples()).append(',').append(run.peakEntries()).append(',').append(dryCrossings(path,hp().tract()).size())
                .append(',').append(prefix).append(",false\n");
        for(var n:run.nodes())points.append(kind).append(',').append(run.source().x()).append(',').append(run.source().z()).append(',')
                .append(variant).append(',').append(n.step()).append(',').append(n.x()).append(',').append(n.z()).append(',').append(n.height())
                .append(',').append(n.dx()).append(',').append(n.dz()).append(',').append(run.terminal()).append('\n');
        if(!variant.equals("ROLLING"))return;
        for(var m:run.moves())moves.append(run.source().x()).append(',').append(run.source().z()).append(',').append(m.node()).append(',')
                .append(m.oldX()).append(',').append(m.oldZ()).append(',').append(m.newX()).append(',').append(m.newZ()).append(',')
                .append(m.state().x()).append(',').append(m.state().z()).append(',').append(m.state().height()).append(',')
                .append(m.state().dx()).append(',').append(m.state().dz()).append('\n');
    }

    private static void rollingProductionEvidence(Path dir,List<SharedBoundaryDrainage.WorldCell> sites) throws Exception {
        var world=PROV.worldForProbe(-2,0);
        if(world!=null){rollingProductionWorld(dir,sites,world);return;}
        var snapshot=PROV.drainageForProbe(-2,0);
        var rows=new StringBuilder("sourceX,sourceZ,segment,terminal,axis,boundary,crossX,crossZ\n");
        for(int i=0;i<3;i++) {
            var source=sites.get(i);var segment=snapshot.segments().stream()
                    .filter(s->s.source().x()==source.x() && s.source().z()==source.z()).findFirst().orElse(null);
            chk(segment!=null,"固定原源确实仍被生产发布，不以邻近源代替 "+source,"missing");
            if(segment==null)continue;
            var terminal=snapshot.chains().get(segment.id().ordinal()).terminal();
            for(var cross:snapshot.crossings())if(cross.segment().equals(segment.id()))
                rows.append(source.x()).append(',').append(source.z()).append(',').append(segment.id().ordinal()).append(',').append(terminal)
                        .append(',').append(cross.axis()).append(',').append(cross.boundary()).append(',').append(cross.point().x()).append(',')
                        .append(cross.point().z()).append('\n');
        }
        Files.writeString(dir.resolve("production-source-crossings.csv"),rows);
        rollingPublishedPaths(dir,sites);
        for(int i=3;i<sites.size();i++) {
            var site=sites.get(i);var connectivity=riverConnectivity(site.x(),site.z(),16,1);
            chk(connectivity.startWet(),"原湿断口仍实际有水，不悄悄换测点 "+site,connectivity.toString());
            log("G42 ORIGINAL_WET_BREAKPOINT start="+site+" connectivity="+connectivity+" productionUnchanged=true");
        }
    }

    /**
     * 世界链下的 rolling 生产证据：rolling 起点只是<b>诊断轨迹起点</b>，本不保证被世界链准入。
     * ⇒ 原「固定原源确实仍被生产发布」的强制 FAIL 是<b>假 FAIL</b>（源政策已允许变化）。
     * 改为<b>如实登记</b> {@code originAdmitted}（点坐标不变、不换测点、不强制存在）；
     * 有牙齿的部分 = 凡被准入者，其发布边必须等于<b>真实 canonical routing</b>
     * （{@code WorldBasinFlow.geometricNext}），且独立 raw 轨迹终点只能是海或待决盆地。
     * <p>⚠ raw 轨迹是否「严格下降」只如实登记（盆内蓄水路由下它合法地可以上坡），
     * <b>不再</b>作为判据；本函数也不声称任何水面梯度。
     */
    private static void rollingProductionWorld(Path dir,List<SharedBoundaryDrainage.WorldCell> sites,
                                               WorldDrainage.Result world) throws Exception {
        var graph=independentDrainage();
        int step=hp().step();      // = 生产 WorldDrainage/WorldBasinRouting 的 STEP（不再多解一次 pipe）
        // 只取 geometricNext ⇒ 不需要扫描盆足迹（worldFlow 不建 members 登记，省一次全边枚举）
        var flow=worldFlow(graph,step);
        var rows=new StringBuilder("sourceX,sourceZ,originAdmitted,terminal,state,rawDescending,canonicalNext\n");
        var admitted=new java.util.HashMap<WorldDrainage.Point,WorldDrainage.Point>();
        for(var edge:world.edges())admitted.put(edge.from(),edge.to());
        int matched=0;
        for(int i=0;i<3;i++) {
            var source=sites.get(i);var at=worldPoint(source);var edgeTo=admitted.get(at);boolean present=edgeTo!=null;
            if(present)matched++;
            String terminal="-",state="-",canonical="-";boolean descending=true,successor=true;
            boolean unresolved=false;
            if(present) {
                var trace=worldTrace(graph,at);state=trace.state();terminal=trace.at();descending=trace.descending();
                try {
                    var next=flow.geometricNext(at);
                    canonical=next==null?"NULL":next.x()+","+next.z();
                    successor=next!=null&&next.equals(edgeTo);
                } catch(DemandFlow.Unresolved|WorldBasinRouting.RoutingBudget pending) {
                    unresolved=true;canonical="UNRESOLVED:"+pending.getMessage();
                }
            }
            boolean asserted=present&&!state.equals("TRACE_BUDGET_PENDING");
            rows.append(source.x()).append(',').append(source.z()).append(',').append(present).append(',').append(terminal)
                    .append(',').append(state).append(',').append(descending).append(',').append(canonical).append('\n');
            log("G42 ORIGIN_ADMISSION start="+source+" originAdmitted="+present+" terminal="+terminal+" state="+state
                    +" rawDescending(notAsserted)="+descending+" canonicalNext="+canonical
                    +" successorMatchesPublishedEdge="+successor+" canonicalResolved="+(!unresolved)
                    +" PRODUCTION_SOURCE_MEMBERSHIP_NOT_FORCED=true WATER_SURFACE_GRADIENT_NOT_PROVEN=true");
            // 生产 solveHier 对 unresolved!=0 直接抛 ⇒ 「判不了」不是可接受状态，不得用来跳过断言。
            if(asserted)chk(!unresolved,
                    "被世界链准入的 rolling 起点：canonical routing 必须可判定（预算/未发布盆即缺陷）"+source,
                    "canonical="+canonical+" state="+state);
            if(asserted&&!unresolved)chk(successor,
                    "被世界链准入的 rolling 起点：发布边等于真实 canonical routing 后继 "+source,
                    "canonical="+canonical+" published="+edgeTo+" state="+state);
            if(asserted)chk(state.equals(WorldDrainage.State.BELOW_SEA.name())
                            ||state.equals(WorldDrainage.State.BASIN_PENDING.name()),
                    "被世界链准入的 rolling 起点：独立轨迹终点只能是入海或待决盆地，不制造湖终点 "+source,
                    "state="+state);
        }
        Files.writeString(dir.resolve("production-source-crossings.csv"),rows);
        rollingPublishedPaths(dir,sites);
        log("G42 ORIGIN_ADMISSION_SUMMARY sites="+sites.size()+" claimed="+3+" admitted="+matched
                +" unadmittedReportedHonestly=true SOURCE_POLICY_CHANGE_ALLOWED=true");
        chk(world.edges().size()>0,"世界链实际边非空转（原源登记有依据）","edges=0");
        for(int i=3;i<sites.size();i++) {
            var site=sites.get(i);var connectivity=riverConnectivity(site.x(),site.z(),16,1,false);
            log("G42 HISTORICAL_WET_BREAKPOINT start="+site+" connectivity="+connectivity
                    +" wetIsProductionColumns=true SOURCE_POLICY_CHANGE_ALLOWED=true NOT_A_CONTINUITY_PASS=true");
        }
    }

    private static void rollingPublishedPaths(Path dir,List<SharedBoundaryDrainage.WorldCell> sites) throws Exception {
        var p=PROV.pipeForProbe(-768,256);var grid=new WindowGrid(p,-1024-hp().halo(),-hp().halo());
        var world=PROV.worldForProbe(-2,0);
        if(world!=null) {
            var reaches=PROV.reachesForProbe(-768,256);
            var audit=auditWorldEdges(worldChain(independentDrainage(),hp().step(),world),
                    world,PROV.worldWaterForProbe(-2,0),reaches,grid,-2,0,hp().tract());
            var edgeRows=new StringBuilder("edge,fromX,fromZ,toX,toZ,cellA,cellB,reachPair\n");
            var reachPairs=new java.util.HashSet<Long>();
            for(var r:reaches)if(r.cell.length==2)reachPairs.add(cellPair(r.cell[0],r.cell[1]));
            for(int i=0;i<world.edges().size();i++) {
                var edge=world.edges().get(i);
                int ka=grid.productionIndex(edge.from()),kb=grid.productionIndex(edge.to());
                edgeRows.append(i).append(',').append(edge.from().x()).append(',').append(edge.from().z()).append(',')
                        .append(edge.to().x()).append(',').append(edge.to().z()).append(',')
                        .append(ka).append(',').append(kb).append(',')
                        .append(reachPairs.contains(cellPair(ka,kb))).append('\n');
            }
            Files.writeString(dir.resolve("production-shaped.csv"),edgeRows);
            log("G42 WORLD_PUBLISHED ownerEdges="+world.edges().size()+" reachEndpoints="+reaches.size()
                    +" unmapped="+audit.unmapped()+" unclaimed="+audit.unclaimed()
                    +" cycles="+audit.cycles()
                    +" rising="+audit.rising()+" explainedRising="+audit.explainedRising()
                    +" unexplainedRising="+audit.unexplainedRising()
                    +" routingMismatch="+audit.routingMismatch()
                    +" basins="+audit.basins()+" lipDefects="+audit.lipDefects()
                    +" identityConflicts="+audit.identityConflicts()
                    +" waterChecked="+audit.waterChecked()+" waterDefects="+audit.waterDefects()
                    +" waterUnknown="+audit.waterUnknown()
                    +" waterAtLeast="+audit.waterAtLeast()
                    +" waterAtLeastBelowT="+audit.waterAtLeastBelowThreshold()
                    +" waterExactAtOrAboveT="+audit.waterExactAtOrAbove()
                    +" waterExactBelowT(allowed)="+audit.waterExactBelowThreshold()
                    +" waterBelowSea(stat)="+audit.waterBelowSea()
                    +" waterIdentity="+audit.waterIdentity()
                    +" waterIdentityUnresolved="+audit.waterIdentityUnresolved()
                    +" waterContractLeak="+audit.waterContractLeak()
                    +" waterPublishedMissing="+audit.waterPublishedMissing()
                    +" waterPublishedBelowT="+audit.waterPublishedBelowThreshold()
                    +" reachesAreEdgeEndpoints=true tracedReconstructionRemoved=true"
                    +" WATER_AUDIT_ALL_CELLS_KNOWN=true"
                    +" WATER_DEMAND_ONLY_FOR_AT_LEAST_AND_PUBLISHED_FROM=true"
                    +" WATER_SURFACE_GRADIENT_NOT_PROVEN=true");
            chk(reaches.size()==world.edges().size()&&audit.unmapped()==0&&audit.unclaimed()==0,
                    "出图实际世界边↔reach 一一对应（按 cellA/cellB 配对，不再用 windowBuild 重建冒充 oracle）",
                    "edges="+world.edges().size()+" reaches="+reaches.size()
                            +" unmapped="+audit.unmapped()+" unclaimed="+audit.unclaimed());
            chk(audit.unexplainedRising()==0&&audit.routingMismatch()==0&&audit.routingUnresolved()==0,
                    "出图：上坡仅限盆内蓄水路由、发布边等于 canonical routing 后继",
                    "unexplainedRising="+audit.unexplainedRising()+" routingMismatch="+audit.routingMismatch()
                            +" routingUnresolved="+audit.routingUnresolved()
                            +" rising="+audit.rising()+" explained="+audit.explainedRising());
            chk(audit.cycles()==0&&audit.duplicated()==0,
                    "出图：发布图无环、无重复 from 端点",
                    "cycles="+audit.cycles()+" duplicated="+audit.duplicated());
            chk(audit.lipDefects()==0&&audit.identityConflicts()==0,
                    "出图：成员盆唯一 identity 且 lip 齐备（inner 在内、outer 在外、crest=sill、首步=inner→outer）",
                    "basins="+audit.basins()+" lipDefects="+audit.lipDefects()
                            +" identityConflicts="+audit.identityConflicts());
            chk(audit.waterChecked()>0&&audit.waterDefects()==0,
                    "出图：water() 每格 known、AT_LEAST 皆达 threshold、湖盆身份独立核对、发布边 from 达门限"
                            +"（EXACT 低于 threshold 是合法干格，只统计；非独立水量 oracle）",
                    "waterChecked="+audit.waterChecked()+" waterDefects="+audit.waterDefects()
                            +" waterUnknown="+audit.waterUnknown()
                            +" waterAtLeastBelowT="+audit.waterAtLeastBelowThreshold()
                            +" waterIdentity="+audit.waterIdentity()
                            +" waterIdentityUnresolved="+audit.waterIdentityUnresolved()
                            +" waterContractLeak="+audit.waterContractLeak()
                            +" waterPublishedMissing="+audit.waterPublishedMissing()
                            +" waterPublishedBelowT="+audit.waterPublishedBelowThreshold()
                            +" waterAtLeast="+audit.waterAtLeast());
            return;
        }
        var sk=windowBuild(grid,windowIncoming(-2,0,grid));var actual=PROV.reachesForProbe(-768,256);
        boolean same=sk.traces.size()==actual.size();
        for(int i=0;same && i<actual.size();i++)same=java.util.Arrays.equals(actual.get(i).cell,sk.traces.get(i).cell);
        chk(same,"出图原trace逐位复核实际provider，非另造原河","different");
        var shape=DeterministicSkeleton.shape(sk.traces,p.level(),grid.ox(),grid.oz(),p.cellBlocks(),p.nx(),p.nz(),windowParams(p),p.bed());
        var rows=new StringBuilder("sourceX,sourceZ,node,x,z,end\n");
        for(int t=0;t<sk.traces.size();t++) {
            var source=grid.world(sk.traces.get(t).sourceCell());if(!sites.subList(0,3).contains(source))continue;
            var line=shape.get(t);for(int i=0;i<line.size();i++)rows.append(source.x()).append(',').append(source.z()).append(',')
                    .append(i).append(',').append(line.x[i]).append(',').append(line.z[i]).append(',').append(sk.traces.get(t).end).append('\n');
        }
        Files.writeString(dir.resolve("production-shaped.csv"),rows);
    }

    private static void rollingObservedStops(Path dir,Map<SharedBoundaryDrainage.WorldCell,RollingRun> runs) throws Exception {
        var rows=new StringBuilder("startX,startZ,stopX,stopZ,height,terminal,gradientNorm,lowerRingSamples,minRingDelta,integerX,integerZ,providerLake,providerFill,providerWet,providerSurface,providerGround,globalCertified\n");
        var crossings=new StringBuilder("startX,startZ,axis,boundary,x,z,direction,globalCertified\n");
        double hs=TP.horizontalScale();WindowOutlets.Terrain terrain=(x,z)->GEN.erodedHeight(x/hs,z/hs);
        for(var run:runs.values()) {
            var end=run.nodes().get(run.nodes().size()-1);var g=downhillGradient(terrain,end.x(),end.z(),0.25);
            var ring=stopRing(terrain,new DryPoint(end.x(),end.z(),end.height()),1);
            int x=(int)Math.floor(end.x()),z=(int)Math.floor(end.z());var c=blendColumn(x,z);
            boolean wet=c.fillWater() && c.waterSurfaceY()>c.carvedGroundY();
            rows.append(run.source().x()).append(',').append(run.source().z()).append(',').append(end.x()).append(',').append(end.z())
                    .append(',').append(end.height()).append(',').append(run.terminal()).append(',').append(Math.hypot(g.x(),g.z()))
                    .append(',').append(ring.lower()).append(',').append(ring.minimumDelta()).append(',').append(x).append(',').append(z)
                    .append(',').append(c.lakePlan()).append(',').append(c.fillWater()).append(',').append(wet).append(',')
                    .append(c.waterSurfaceY()).append(',').append(c.carvedGroundY()).append(",false\n");
            var path=run.nodes().stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList();
            for(var cross:dryCrossings(path,hp().tract()))crossings.append(run.source().x()).append(',').append(run.source().z()).append(',')
                    .append(cross.axis()).append(',').append(cross.boundary()).append(',').append(cross.x()).append(',').append(cross.z())
                    .append(',').append(cross.direction()).append(",false\n");
            log("G42 STOP start="+run.source()+" stop="+end.x()+","+end.z()+" lowerRingSamples="+ring.lower()
                    +" minRingDelta="+ring.minimumDelta()+" providerIntegerLake="+c.lakePlan()+" providerIntegerWet="+wet
                    +" SUBBLOCK_STOP_NOT_LAKE_CERTIFICATE=true");
        }
        Files.writeString(dir.resolve("stops.csv"),rows);Files.writeString(dir.resolve("crossings.csv"),crossings);
    }

    private record RollingBasin(String id, SubcellPatch patch, int seed, RingFlood flood, double sill,
                                double frame, java.util.BitSet members, List<PlanePort> outlets, RingFlood outside) { }

    private static DryPoint basinPoint(SubcellPatch p,int k) {
        return new DryPoint(p.ox()+k/p.nz()*p.step(),p.oz()+k%p.nz()*p.step(),p.height()[k]);
    }

    private static boolean basinFrame(SubcellPatch p,int k) {
        int x=k/p.nz(),z=k%p.nz();return x==0 || z==0 || x==p.nx()-1 || z==p.nz()-1;
    }

    private static RollingBasin rollingBasin(String id,SubcellPatch p,double x,double z) {
        int seed=-1;
        for(int k=0;k<p.height().length;k++) {
            var a=basinPoint(p,k);
            if(Math.abs(a.x()-x)<=2 && Math.abs(a.z()-z)<=2 && Double.isFinite(a.height())
                    && (seed<0 || a.height()<p.height()[seed]))seed=k;
        }
        if(seed<0)throw new IllegalArgumentException("missing basin seed");
        int[] mask=new int[p.height().length];
        for(int k=0;k<mask.length;k++)if(!Double.isFinite(p.height()[k]))mask[k]=-1;
        var flood=ringFlood(p,mask,0,List.of(seed));double sill=Double.POSITIVE_INFINITY,frame=sill;
        var lower=new ArrayList<Integer>();
        for(int k=0;k<mask.length;k++) {
            if(basinFrame(p,k))frame=Math.min(frame,flood.cost()[k]);
            if(p.height()[k]<p.height()[seed]){lower.add(k);sill=Math.min(sill,flood.cost()[k]);}
            if(mask[k]<0)for(int n:basinNeighbours(p,k))if(n>=0)frame=Math.min(frame,flood.cost()[n]);
        }
        // Lower witnesses are absorbing: a frame reached only AFTER a known lower point is downstream, not a competing rim.
        int[] beforeLower=mask.clone();for(int k:lower)beforeLower[k]=-1;
        var unopened=ringFlood(p,beforeLower,0,List.of(seed));frame=Double.POSITIVE_INFINITY;
        for(int k=0;k<mask.length;k++) {
            if(basinFrame(p,k))frame=Math.min(frame,unopened.cost()[k]);
            if(mask[k]<0)for(int n:basinNeighbours(p,k))if(n>=0)frame=Math.min(frame,unopened.cost()[n]);
        }
        var pre=Double.isFinite(sill)?freeReachable(p,mask,seed,Math.nextDown(sill)):new java.util.BitSet();
        int[] outside=mask.clone();for(int k=pre.nextSetBit(0);k>=0;k=pre.nextSetBit(k+1))outside[k]=-1;
        var reverse=ringFlood(p,outside,0,lower);var ports=new ArrayList<PlanePort>();
        if(Double.isFinite(sill))for(var e:freeBoundary(p,pre))
            if(subcellEdge(p,e.from(),e.to())==sill && reverse.cost()[e.to()]<=sill)ports.add(e);
        return new RollingBasin(id,p,seed,flood,sill,frame,pre,List.copyOf(ports),reverse);
    }

    private static int[] basinNeighbours(SubcellPatch p,int k) {
        int x=k/p.nz(),z=k%p.nz();
        return new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1};
    }

    private static String basinStatus(RollingBasin b) {
        if(!Double.isFinite(b.sill()) || b.members().isEmpty())return "UNKNOWN_FRAME_PENDING";
        var cut=firstCut(b.patch(),b.seed(),b.sill());
        if(cut.state()==SharedBoundaryTransfer.SampledCutState.UNKNOWN_INCIDENT_INPUT)return "UNKNOWN_FRAME_PENDING";
        if(cut.state()!=SharedBoundaryTransfer.SampledCutState.UNIQUE_FIRST_EDGE)return "MULTIPLE_OUTLETS_PENDING";
        var edge=cut.firstEdges().get(0);
        if(b.outlets().size()!=1 || b.outlets().get(0).from()!=edge.from() || b.outlets().get(0).to()!=edge.to())
            return "FIRST_EXIT_MERGE_OR_DOWNSTREAM_PENDING";
        return "UNIQUE_SAMPLED_FIRST_EXIT_DOWNSTREAM_PENDING";
    }

    private static CrestLink basinContact(RollingBasin b,DryPoint exact,WindowOutlets.Terrain terrain) {
        int[] mask=new int[b.patch().height().length];
        for(int k=0;k<mask.length;k++)if(!b.members().get(k))mask[k]=-1;
        var inside=ringFlood(b.patch(),mask,0,List.of(b.seed()));
        return connectCrest(b.patch(),inside,exact,terrain);
    }

    private static boolean basinAccepts(RollingBasin b,DryPoint exact,WindowOutlets.Terrain terrain) {
        if(!(exact.height()<b.sill()))return false;
        return basinContact(b,exact,terrain).barrier()<b.sill();
    }

    private static List<DryPoint> basinOutletPath(RollingBasin b,PlanePort outlet) {
        var path=new ArrayList<>(crestGridPath(b.patch(),b.flood(),outlet.from()));
        for(int k=outlet.to();k>=0;k=b.outside().parent()[k])path.add(basinPoint(b.patch(),k));
        return List.copyOf(path);
    }

    private static RollingRun basinSuffix(RollingBasin b,WindowOutlets.Terrain terrain,double trigger,double offset) {
        // Conditional replay of a sole KNOWN candidate is allowed; frame/global/outflow authority stays pending.
        if(b.outlets().size()!=1)return null;
        var path=basinOutletPath(b,b.outlets().get(0));var end=path.get(path.size()-1);var prev=path.get(path.size()-2);
        double dx=end.x()-prev.x(),dz=end.z()-prev.z(),length=Math.hypot(dx,dz);
        var state=new RollingState(end.x(),end.z(),end.height(),dx/length,dz/length,0);
        return rollingResume(new SharedBoundaryDrainage.WorldCell((int)end.x(),(int)end.z()),terrain,state,
                32,trigger,offset,-offset,true,TP.seaLevel(),2048,false);
    }

    private static void basinRollingSynthetic() throws Exception {
        log("\n=== [basin-rolling-synth] strict component and complete outlet set, NOT water authorization ===");
        WindowOutlets.Terrain two=(x,z)->Math.min(x*x+z*z,0.25+(x-4)*(x-4)+z*z);
        var p=denseCrestPatch(samplePortPatch(two,-3,-3,7,3,.5,new DryPoint(0,0,0),new DryPoint(4,0,0)),two);
        var a=rollingBasin("A",p,0,0);var b=rollingBasin("B",p,4,0);
        chk(b.members().cardinality()>0 && !b.members().get(a.seed()) && b.sill()>p.height()[b.seed()],
                "相邻盆有真屏障不按距离合盆","wrong");
        chk(basinAccepts(b,new DryPoint(4.03,.02,two.height(4.03,.02)),two)
                && !basinAccepts(b,new DryPoint(0,0,0),two),"准确非格点入口须通过盆内边采样连接","wrong");
        WindowOutlets.Terrain flat=(x,z)->1;
        var f=rollingBasin("FLAT",denseCrestPatch(samplePortPatch(flat,-3,-3,3,3,1,new DryPoint(0,0,1),new DryPoint(0,0,1)),flat),0,0);
        chk(basinStatus(f).equals("UNKNOWN_FRAME_PENDING") && f.outlets().isEmpty(),"无更低点平地不能把无穷临界或外框变出口","wrong");
        WindowOutlets.Terrain twin=(x,z)->Math.min(x*x+z*z,Math.min(-1+(x-4)*(x-4)+z*z,-1+(x+4)*(x+4)+z*z));
        var t=rollingBasin("TWIN",denseCrestPatch(samplePortPatch(twin,-6,-4,6,4,.5,new DryPoint(0,0,0),new DryPoint(0,0,0)),twin),0,0);
        chk(t.outlets().size()>=2 && !basinStatus(t).startsWith("UNIQUE"),"同高双溢口全部保留不按坐标选一个","outlets="+t.outlets().size()+" status="+basinStatus(t));
        var clipped=rollingBasin("CLIP",denseCrestPatch(samplePortPatch(two,2,-1,6,1,.5,new DryPoint(4,0,0),new DryPoint(4,0,0)),two),4,0);
        chk(basinStatus(clipped).equals("UNKNOWN_FRAME_PENDING"),"外框代价更低优先待决，不假设封闭高墙","wrong");
        WindowOutlets.Terrain curve=(x,z)->1000-z+.003*x*x;
        var full=rollingTrace(new SharedBoundaryDrainage.WorldCell(8,0),curve,32,8,0,0,true,-1000,100,false);
        var state=full.nodes().get(40);var resumed=rollingResume(full.source(),curve,state,32,16,3,-3,true,-1000,60,false);
        chk(resumed.nodes().equals(full.nodes().subList(40,101)),"非整数状态续算逐节点等于原路径完整后缀","wrong");
        boolean cancel=false;try{Thread.currentThread().interrupt();rollingBasin("X",p,4,0);}
        catch(java.util.concurrent.CancellationException e){cancel=true;}finally{Thread.interrupted();}
        chk(cancel && rollingBasin("B",p,4,0).members().equals(b.members()),"盆分析取消无部分发布，重试同分量","wrong");
        exactCrestSynthetic();crestLinkSynthetic();firstCutSynthetic();
    }

    private static SubcellPatch firstCutFixture(boolean twin,boolean hole) {
        int n=7,total=n*n;double[] bed=new double[total],east=new double[total],south=new double[total];
        java.util.Arrays.fill(bed,9);java.util.Arrays.fill(east,Double.POSITIVE_INFINITY);java.util.Arrays.fill(south,Double.POSITIVE_INFINITY);
        bed[3*n+3]=0;bed[4*n+3]=5;bed[5*n+3]=-1;bed[6*n+3]=-2;
        if(twin){bed[2*n+3]=5;bed[n+3]=-1;bed[3]=-2;}
        if(hole)bed[3*n+2]=Double.NaN;
        for(int k=0;k<total;k++) {
            if(k/n+1<n)east[k]=Double.isFinite(bed[k]+bed[k+n])?Math.max(bed[k],bed[k+n]):Double.POSITIVE_INFINITY;
            if(k%n+1<n)south[k]=Double.isFinite(bed[k]+bed[k+1])?Math.max(bed[k],bed[k+1]):Double.POSITIVE_INFINITY;
        }
        return new SubcellPatch(-3,-3,1,n,n,bed,east,south,3*n+3,5*n+3);
    }

    private static SharedBoundaryTransfer.SampledCut firstCut(SubcellPatch p,int seed,double barrier) {
        return SharedBoundaryTransfer.sampledFirstCut(p.nx(),p.nz(),p.height(),p.east(),p.south(),seed,barrier);
    }

    private static void firstCutSynthetic() {
        var p=firstCutFixture(false,false);var flood=ringFlood(p,new int[p.height().length],0,List.of(p.start()));
        var members=freeReachable(p,new int[p.height().length],p.start(),Math.nextDown(5.0));int[] mask=new int[p.height().length];
        for(int k=members.nextSetBit(0);k>=0;k=members.nextSetBit(k+1))mask[k]=-1;
        var outside=ringFlood(p,mask,0,List.of(p.end()));
        var basin=new RollingBasin("CUT",p,p.start(),flood,5,5,members,List.of(new PlanePort(p.start(),p.start()+p.nz())),outside);
        var cut=firstCut(p,p.start(),5);
        chk(basin.frame()==basin.sill() && basinStatus(basin).equals("UNIQUE_SAMPLED_FIRST_EXIT_DOWNSTREAM_PENDING"),
                "同口下游可达外框不误判首出口未知（原分类真实反例）",basinStatus(basin));
        chk(cut.state()==SharedBoundaryTransfer.SampledCutState.UNIQUE_FIRST_EDGE && cut.rim().size()==4
                && cut.firstEdges().size()==1,"完整闭合采样割唯一最低边，不需要框代价更高",cut.toString());
        var noLower=new RollingBasin("MERGE",p,p.start(),flood,5,5,members,List.of(),outside);
        chk(basinStatus(noLower).equals("FIRST_EXIT_MERGE_OR_DOWNSTREAM_PENDING"),
                "唯一首割边不等于已知更低下游；无兼容口保留合并或下游待决",basinStatus(noLower));
        var twin=firstCutFixture(true,false);var t=firstCut(twin,twin.start(),5);
        chk(t.state()==SharedBoundaryTransfer.SampledCutState.MULTIPLE_FIRST_EDGES && t.firstEdges().size()==2,
                "同高双割边全部保留不按lower兼容性或坐标删除",t.toString());
        var hole=firstCutFixture(false,true);
        chk(firstCut(hole,hole.start(),5).state()==SharedBoundaryTransfer.SampledCutState.UNKNOWN_INCIDENT_INPUT,
                "内部未知孔洞邻接严格分量必须待决","wrong");
        chk(firstCut(p,3,10).state()==SharedBoundaryTransfer.SampledCutState.UNKNOWN_INCIDENT_INPUT,
                "严格分量触采样框必须待决，不把缺边当墙","wrong");
        var mutable=new ArrayList<>(cut.firstEdges());boolean frozen=false;
        try{cut.firstEdges().clear();}catch(UnsupportedOperationException ex){frozen=true;}
        boolean cancelled=false;try{Thread.currentThread().interrupt();firstCut(p,p.start(),5);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(frozen && cancelled && cut.equals(firstCut(p,p.start(),5)) && mutable.size()==1,
                "割产物深只读/取消无部分发布/重试同值","wrong");
    }

    private static void secBasinRolling(long seed) throws Exception {
        log("\n=== [basin-rolling] G43 fixed world graph; sampled geometry only, actual outflow UNKNOWN ===");
        Path dir=OUT.resolve("basin-rolling-"+System.currentTimeMillis());Files.createDirectories(dir);
        double hs=TP.horizontalScale();var gen=newGen(TP,seed);long fine=HierarchicalHydrology.Diag.FINE_SOLVES.get();
        WindowOutlets.Terrain terrain=(x,z)->gen.erodedHeight(x/hs,z/hs);
        var runs=rollingRuns(gen,rollingSites(),8,0);long started=System.nanoTime();
        for(double step:new double[]{1,.5}) {
            var corner=new DryPoint(-704,544,0);
            var p=denseCrestPatch(samplePortPatch(terrain,-704,544,-544,736,step,corner,corner),terrain);
            var west=rollingBasin("WEST",p,-655,653);var east=rollingBasin("EAST",p,-582,581);
            var basins=List.of(west,east);writeRollingBasins(dir,basins,terrain);
            for(var basin:basins) {
                verifyRollingBasin(basin);
                var replay=rollingBasin(basin.id(),p,basin.id().equals("WEST")?-655:-582,basin.id().equals("WEST")?653:581);
                chk(basin.members().equals(replay.members()) && basin.outlets().equals(replay.outlets()) && basin.sill()==replay.sill(),
                        "重复盆身份/完整出口集合一致 "+basin.id()+" step="+step,"different");
            }
            writeBasinContacts(dir,basins,runs,terrain);
            writeBasinSuffixes(dir,basins,terrain);
            verifyBasinConsumers(dir,basins,runs,terrain);
            writeBasinFirstCuts(dir,basins);
            writeSourceBasinChains(dir,basins,runs,terrain);
        }
        chk(fine==HierarchicalHydrology.Diag.FINE_SOLVES.get(),"盆接续只读生产侵蚀高度，未触发水文fine","fine added");
        log("G43 elapsedMs="+(System.nanoTime()-started)/1e6+" retainedErosionTiles="+gen.erosionTileCacheSize()
                +" samplingIncludesFullFixedPatch=true NOT_PRODUCTION_WATER=true BUDGET_UNKNOWN=true");
        Files.writeString(dir.resolve("metrics.txt"),LOG);
        // #region agent log: G43 sampled basin hypotheses, preserve history
        Files.writeString(Path.of("..","debug-agent.log").toAbsolutePath(),LOG.toString(),java.nio.file.StandardOpenOption.APPEND);
        // #endregion
        log("BASIN_ROLLING_DIR="+dir.toAbsolutePath());
    }

    private static void verifyRollingBasin(RollingBasin b) {
        var p=b.patch();int[] mask=new int[p.height().length];var at=freeReachable(p,mask,b.seed(),b.sill());
        boolean before=false,on=false;
        for(int k=0;k<mask.length;k++)if(p.height()[k]<p.height()[b.seed()]){before |= b.members().get(k);on |= at.get(k);}
        chk(Double.isFinite(b.sill()) && !before && on && !b.outlets().isEmpty(),
                "独立阈值BFS临界前不达更低点/临界可达/完整割有出口 "+b.id()+" step="+p.step(),"wrong");
        log("G43 BASIN id="+b.id()+" step="+p.step()+" bottom="+basinPoint(p,b.seed())+" sill="+b.sill()
                +" frame="+b.frame()+" members="+b.members().cardinality()+" outlets="+b.outlets().size()+" status="+basinStatus(b));
    }

    private static void writeRollingBasins(Path dir,List<RollingBasin> basins,WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("basin,step,seed,sill,frame,members,outlets,status,actualOutflow,globalCertified\n");
        var nodes=new StringBuilder("basin,node,x,z,height,east,south,cost,parent,member,outsideCost,outsideParent\n");
        var cuts=new StringBuilder("basin,from,to,edge,critical,compatible\n");
        var paths=new StringBuilder("basin,outlet,node,x,z,height\n");var dense=new StringBuilder("mode,kind,segment,sample,x,z,height\n");
        for(var b:basins) {
            var p=b.patch();rows.append(b.id()).append(',').append(p.step()).append(',').append(b.seed()).append(',').append(b.sill())
                    .append(',').append(b.frame()).append(',').append(b.members().cardinality()).append(',').append(b.outlets().size())
                    .append(',').append(basinStatus(b)).append(",UNKNOWN,false\n");
            for(int k=0;k<p.height().length;k++) {var a=basinPoint(p,k);
                nodes.append(b.id()).append(',').append(k).append(',').append(a.x()).append(',').append(a.z()).append(',').append(a.height())
                        .append(',').append(p.east()[k]).append(',').append(p.south()[k]).append(',').append(b.flood().cost()[k]).append(',')
                        .append(b.flood().parent()[k]).append(',').append(b.members().get(k)).append(',').append(b.outside().cost()[k]).append(',').append(b.outside().parent()[k]).append('\n');}
            for(var e:freeBoundary(p,b.members()))cuts.append(b.id()).append(',').append(e.from()).append(',').append(e.to()).append(',')
                    .append(subcellEdge(p,e.from(),e.to())).append(',').append(subcellEdge(p,e.from(),e.to())==b.sill()).append(',').append(b.outlets().contains(e)).append('\n');
            for(int i=0;i<b.outlets().size();i++) {
                var path=basinOutletPath(b,b.outlets().get(i));int j=0;
                for(var a:path)paths.append(b.id()).append(',').append(i).append(',').append(j++).append(',').append(a.x()).append(',').append(a.z()).append(',').append(a.height()).append('\n');
                double max=writeExactCrestPath(dense,b.id(),"OUTLET_"+i,path,terrain);
                log("G43 PROFILE basin="+b.id()+" step="+p.step()+" outlet="+i+" edge33="+b.sill()+" profile129="+max+" overshoot="+(max-b.sill())+" NOT_CONTINUOUS_CERTIFICATE=true");
            }
        }
        String tag="-"+basins.get(0).patch().step()+".csv";
        Files.writeString(dir.resolve("basins"+tag),rows);Files.writeString(dir.resolve("grid"+tag),nodes);
        Files.writeString(dir.resolve("cuts"+tag),cuts);Files.writeString(dir.resolve("transfer-paths"+tag),paths);Files.writeString(dir.resolve("profiles"+tag),dense);
    }

    private static void writeBasinContacts(Path dir,List<RollingBasin> basins,Map<SharedBoundaryDrainage.WorldCell,RollingRun> runs,
                                           WindowOutlets.Terrain terrain) throws Exception {
        var csv=new StringBuilder("sourceX,sourceZ,basin,x,z,height,connectionBarrier,sill,accepted,globalCertified\n");
        var reversed=new ArrayList<>(runs.values());java.util.Collections.reverse(reversed);
        for(var run:reversed) {
            var stop=run.nodes().get(run.nodes().size()-1);var exact=new DryPoint(stop.x(),stop.z(),stop.height());int accepted=0;
            for(var b:basins) {
                var link=basinContact(b,exact,terrain);boolean ok=link.barrier()<b.sill();if(ok)accepted++;
                csv.append(run.source().x()).append(',').append(run.source().z()).append(',').append(b.id()).append(',').append(exact.x()).append(',')
                        .append(exact.z()).append(',').append(exact.height()).append(',').append(link.barrier()).append(',').append(b.sill()).append(',').append(ok).append(",false\n");
            }
            chk(accepted==1,"准确停点仅属于一个临界前采样盆，不以接近判JOIN "+run.source()+" step="+basins.get(0).patch().step(),"accepted="+accepted);
        }
        Files.writeString(dir.resolve("contacts-"+basins.get(0).patch().step()+".csv"),csv);
    }

    private static void writeBasinSuffixes(Path dir,List<RollingBasin> basins,WindowOutlets.Terrain terrain) throws Exception {
        var csv=new StringBuilder("basin,node,x,z,height,dirX,dirZ,terminal\n");
        var relations=new StringBuilder("basin,terminal,nextBasin,nodes,sharedSuffix,actualOutflow,globalCertified\n");
        for(var b:basins) {
            var suffix=basinSuffix(b,terrain,8,0);if(suffix==null)continue;
            var other=basinSuffix(b,terrain,20,7);
            chk(rollingSame(suffix,other),"共同盆出口后继不依赖推进窗 "+b.id()+" step="+b.patch().step(),"different");
            String next="NONE_PROVEN";var stop=suffix.nodes().get(suffix.nodes().size()-1);var exact=new DryPoint(stop.x(),stop.z(),stop.height());
            for(var target:basins)if(basinAccepts(target,exact,terrain))next=target.id();
            for(var n:suffix.nodes())csv.append(b.id()).append(',').append(n.step()).append(',').append(n.x()).append(',').append(n.z()).append(',')
                    .append(n.height()).append(',').append(n.dx()).append(',').append(n.dz()).append(',').append(suffix.terminal()).append('\n');
            relations.append(b.id()).append(',').append(suffix.terminal()).append(',').append(next).append(',').append(suffix.nodes().size())
                    .append(",true,UNKNOWN,false\n");
            log("G43 SUFFIX basin="+b.id()+" step="+b.patch().step()+" nodes="+suffix.nodes().size()+" next="+next+" terminal="+suffix.terminal()+" end="+exact+" CONDITIONAL_GEOMETRY_NOT_RELEASE=true");
        }
        String tag="-"+basins.get(0).patch().step()+".csv";
        Files.writeString(dir.resolve("suffixes"+tag),csv);Files.writeString(dir.resolve("relations"+tag),relations);
    }

    private static void verifyBasinConsumers(Path dir,List<RollingBasin> basins,
            Map<SharedBoundaryDrainage.WorldCell,RollingRun> runs,WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("sourceX,sourceZ,basin,successorNodes,identicalStates,conditionalOnly,globalCertified\n");
        var canonical=new HashMap<String,RollingRun>();for(var b:basins)canonical.put(b.id(),basinSuffix(b,terrain,8,0));
        var order=new ArrayList<>(runs.values());java.util.Collections.reverse(order);
        for(var run:order) {
            var end=run.nodes().get(run.nodes().size()-1);var at=new DryPoint(end.x(),end.z(),end.height());
            for(var b:basins)if(basinAccepts(b,at,terrain) && canonical.get(b.id())!=null) {
                var actual=basinSuffix(b,terrain,12,-7);var expected=canonical.get(b.id());
                boolean same=rollingSame(actual,expected);
                chk(same,"不同入口经同盆出口后完整共同后缀一致 "+run.source()+" basin="+b.id()+" step="+b.patch().step(),"different");
                rows.append(run.source().x()).append(',').append(run.source().z()).append(',').append(b.id()).append(',')
                        .append(actual.nodes().size()).append(',').append(same).append(",true,false\n");
            }
        }
        var east=canonical.get("EAST");var west=canonical.get("WEST");
        if(east!=null && west!=null) {
            var e=east.nodes().get(east.nodes().size()-1);var exact=new DryPoint(e.x(),e.z(),e.height());
            var receiver=basins.stream().filter(b->b.id().equals("WEST")).findFirst().orElseThrow();
            boolean enters=basinAccepts(receiver,exact,terrain);
            chk(enters,"东盆出口实际续追进入西盆同一临界前分量 step="+receiver.patch().step(),"end="+exact);
            if(enters)chk(rollingSame(west,basinSuffix(receiver,terrain,16,3)),
                    "东盆来流经西盆后与西盆原三入口共用完整下游 step="+receiver.patch().step(),"different");
        }
        Files.writeString(dir.resolve("consumers-"+basins.get(0).patch().step()+".csv"),rows);
    }

    private static void writeBasinFirstCuts(Path dir,List<RollingBasin> basins) throws Exception {
        var csv=new StringBuilder("basin,state,members,allRimEdges,firstEdges,compatibleEdges,frame,sill,frameWithoutFirstEdges,externalTailwater,actualOutflow,continuousCertified\n");
        for(var b:basins) {
            var p=b.patch();var cut=firstCut(p,b.seed(),b.sill());
            double[] east=p.east().clone(),south=p.south().clone();
            for(var e:cut.firstEdges())(Math.abs(e.from()-e.to())==p.nz()?east:south)[Math.min(e.from(),e.to())]=Double.POSITIVE_INFINITY;
            var blocked=new SubcellPatch(p.ox(),p.oz(),p.step(),p.nx(),p.nz(),p.height(),east,south,p.start(),p.end());
            var flood=ringFlood(blocked,new int[p.height().length],0,List.of(b.seed()));double frame=Double.POSITIVE_INFINITY;
            for(int k=0;k<p.height().length;k++)if(basinFrame(p,k))frame=Math.min(frame,flood.cost()[k]);
            var members=new java.util.BitSet();cut.members().forEach(members::set);
            chk(members.equals(b.members()) && cut.rim().size()==freeBoundary(p,b.members()).size(),
                    "主代码割证书与独立探针BFS/完整rim一致 "+b.id()+" step="+p.step(),"different");
            chk(cut.state()==SharedBoundaryTransfer.SampledCutState.UNIQUE_FIRST_EDGE && frame>b.sill(),
                    "真实唯一首割边之外不存在同高独立外框路线 "+b.id()+" step="+p.step(),cut.state()+" frame="+frame);
            csv.append(b.id()).append(',').append(cut.state()).append(',').append(cut.members().size()).append(',')
                    .append(cut.rim().size()).append(',').append(cut.firstEdges().size()).append(',').append(b.outlets().size())
                    .append(',').append(b.frame()).append(',').append(b.sill()).append(',').append(frame).append(",UNKNOWN,UNKNOWN,false\n");
            log("G44 FIRST_CUT basin="+b.id()+" step="+p.step()+" state="+cut.state()+" frameWithoutFirstEdges="+frame
                    +" finiteSampleOnly=true tailwaterAndReleaseUnknown=true");
        }
        Files.writeString(dir.resolve("first-cuts-"+basins.get(0).patch().step()+".csv"),csv);
    }

    private static RollingBasin uniqueReceivingBasin(List<RollingBasin> basins,DryPoint at,WindowOutlets.Terrain terrain) {
        RollingBasin found=null;
        for(var b:basins)if(basinAccepts(b,at,terrain)) {if(found!=null)return null;found=b;}
        return found;
    }

    private static List<DryPoint> sourceBasinChain(RollingRun run,List<RollingBasin> basins,
                                                 WindowOutlets.Terrain terrain,StringBuilder stages) {
        var path=new ArrayList<>(run.nodes().stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList());
        var seen=new java.util.HashSet<String>();String terminal=run.terminal();
        for(int i=0;i<=basins.size();i++) {
            var contact=path.get(path.size()-1);var b=uniqueReceivingBasin(basins,contact,terrain);
            if(b==null)break;
            if(!seen.add(b.id())){terminal="BASIN_CYCLE_PENDING";break;}
            if(!basinStatus(b).startsWith("UNIQUE")){terminal=basinStatus(b);break;}
            var link=basinContact(b,contact,terrain);var attachment=new ArrayList<>(link.path());
            java.util.Collections.reverse(attachment);attachment.remove(0);path.addAll(attachment);
            var transfer=basinOutletPath(b,b.outlets().get(0));path.addAll(transfer.subList(1,transfer.size()));
            var suffix=basinSuffix(b,terrain,12,-7);terminal=suffix.terminal();
            path.addAll(suffix.nodes().subList(1,suffix.nodes().size()).stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList());
            stages.append(run.source().x()).append(',').append(run.source().z()).append(',').append(i).append(',')
                    .append(b.id()).append(',').append(b.sill()).append(',').append(link.barrier()).append(',')
                    .append(terminal).append(",CONDITIONAL_STORAGE_TRANSFER_NOT_DRY_RIVER,UNKNOWN,false\n");
        }
        stages.append(run.source().x()).append(',').append(run.source().z()).append(",-1,TERMINAL,NaN,NaN,")
                .append(terminal).append(",CONDITIONAL_GEOMETRY_ONLY,UNKNOWN,false\n");
        return List.copyOf(path);
    }

    private static void writeSourceBasinChains(Path dir,List<RollingBasin> basins,
            Map<SharedBoundaryDrainage.WorldCell,RollingRun> runs,WindowOutlets.Terrain terrain) throws Exception {
        var points=new StringBuilder("sourceX,sourceZ,node,x,z,height,actualOutflow,globalCertified\n");
        var stages=new StringBuilder("sourceX,sourceZ,stage,basin,sill,attachmentBarrier,terminal,kind,actualOutflow,globalCertified\n");
        List<DryPoint> shared=null;
        for(var run:runs.values()) {
            var path=sourceBasinChain(run,basins,terrain,stages);boolean continuous=true;
            for(int i=1;i<path.size();i++)continuous &= Math.hypot(path.get(i).x()-path.get(i-1).x(),path.get(i).z()-path.get(i-1).z())<=Math.max(1,basins.get(0).patch().step()*Math.sqrt(2))+1e-9;
            var west=basins.stream().filter(b->b.id().equals("WEST")).findFirst().orElseThrow();
            var tail=basinSuffix(west,terrain,8,0).nodes().stream().map(n->new DryPoint(n.x(),n.z(),n.height())).toList();
            var end=path.subList(path.size()-tail.size(),path.size());
            chk(continuous && end.equals(tail) && (shared==null || end.equals(shared)),
                    "原源经准确接触/蓄水段/两盆出口的整链无跳接且同最终后缀 "+run.source()+" step="+west.patch().step(),"gap/different");
            shared=List.copyOf(end);
            for(int i=0;i<path.size();i++) {var a=path.get(i);points.append(run.source().x()).append(',').append(run.source().z()).append(',')
                    .append(i).append(',').append(a.x()).append(',').append(a.z()).append(',').append(a.height()).append(",UNKNOWN,false\n");}
        }
        String tag="-"+basins.get(0).patch().step()+".csv";
        Files.writeString(dir.resolve("source-chains"+tag),points);Files.writeString(dir.resolve("source-stages"+tag),stages);
    }

    private static String dryContext(WindowGrid grid, double x, double z) {
        var p=grid.pipe(); double fx=(x-grid.ox())/p.cellBlocks(),fz=(z-grid.oz())/p.cellBlocks();
        if (!Double.isFinite(fx) || !Double.isFinite(fz) || fx<=0 || fz<=0 || fx>=p.nx()-1 || fz>=p.nz()-1) return "UNKNOWN_FRAME";
        int ix=(int)Math.floor(fx),iz=(int)Math.floor(fz);boolean sea=false,lake=false,filled=false,unknown=false;
        for(int dx=0;dx<=1;dx++) for(int dz=0;dz<=1;dz++) {
            int k=(ix+dx)*p.nz()+iz+dz; sea|=p.sea()[k];lake|=p.lake()[k];unknown|=!p.resolved()[k];
            filled|=Double.doubleToLongBits(p.level()[k])!=Double.doubleToLongBits(p.bed()[k]);
        }
        if(unknown)return "UNRESOLVED_STENCIL_PENDING";
        if(lake)return "LOCAL_LAKE_INTERFACE_PENDING";
        if(sea)return "LOCAL_SEA_INTERFACE_PENDING";
        return filled?"FILLED_STENCIL_BASIN_PENDING":"DRY";
    }

    private static DryPoint dryAdvance(WindowGrid grid, WindowOutlets.Terrain terrain, DryPoint from, double step) {
        var vector=downhillGradient(terrain,from.x(),from.z(),0.25);double length=Math.hypot(vector.x(),vector.z());
        if(!(length>1e-12))return null;
        double mx=from.x()+0.5*step*vector.x()/length,mz=from.z()+0.5*step*vector.z()/length;
        if(!dryContext(grid,mx,mz).equals("DRY"))return null;
        var middle=downhillGradient(terrain,mx,mz,0.25);double ml=Math.hypot(middle.x(),middle.z());
        if(!(ml>1e-12))return null;
        double x=from.x()+step*middle.x()/ml,z=from.z()+step*middle.z()/ml,previous=from.height();
        for(int i=1;i<=4;i++) {
            double sx=from.x()+(x-from.x())*i/4,sz=from.z()+(z-from.z())*i/4;
            if(!dryContext(grid,sx,sz).equals("DRY"))return null;
            double h=terrain.height(sx,sz);if(!Double.isFinite(h) || h>previous+1e-10)return null;previous=h;
        }
        return previous<from.height()-1e-12?new DryPoint(x,z,previous):null;
    }

    private static DryPath traceContinuousDry(WindowGrid grid, SharedBoundaryDrainage.WorldCell source,
                                               WindowOutlets.Terrain terrain, double maxStep) {
        var points=new ArrayList<DryPoint>();
        if(grid.index(source)<0)return new DryPath(source,points,"SOURCE_OUTSIDE_CONTEXT_PENDING_NO_ENTRY_FORCED");
        double x=source.x(),z=source.z();points.add(new DryPoint(x,z,terrain.height(x,z)));
        for(int iteration=0;iteration<4096;iteration++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("continuous dry trace");
            var current=points.get(points.size()-1);String context=dryContext(grid,current.x(),current.z());
            if(!context.equals("DRY"))return new DryPath(source,points,context);
            var v=downhillGradient(terrain,current.x(),current.z(),0.25);double norm=Math.hypot(v.x(),v.z());
            if(!(norm>1e-12))return new DryPath(source,points,"ZERO_GRADIENT_MICROBASIN_PENDING");
            String ahead=dryContext(grid,current.x()+maxStep*v.x()/norm,current.z()+maxStep*v.z()/norm);
            if(!ahead.equals("DRY"))return new DryPath(source,points,ahead);
            DryPoint next=null;
            for(int halve=0;halve<11 && next==null;halve++)next=dryAdvance(grid,terrain,current,maxStep/Math.scalb(1,halve));
            if(next==null)return new DryPath(source,points,"NO_SAMPLED_DESCENT_MICROBASIN_PENDING");
            points.add(next);
        }
        return new DryPath(source,points,"BOUNDED_STEP_LIMIT_PENDING");
    }

    private static List<DryCrossing> dryCrossings(List<DryPoint> points, int tract) {
        var result=new ArrayList<DryCrossing>();
        for(int i=1;i<points.size();i++) {
            var a=points.get(i-1);var b=points.get(i);
            for(int axis=0;axis<2;axis++) {
                double av=axis==0?a.x():a.z(),bv=axis==0?b.x():b.z();
                int at=(int)Math.floor(av/tract),bt=(int)Math.floor(bv/tract);
                if(at==bt)continue;
                if(Math.abs(at-bt)!=1)throw new IllegalArgumentException("nonlocal continuous segment");
                int boundary=Math.max(at,bt)*tract;double f=(boundary-av)/(bv-av);
                result.add(new DryCrossing(axis,boundary,a.x()+f*(b.x()-a.x()),a.z()+f*(b.z()-a.z()),Integer.compare(bt,at)));
            }
        }
        return List.copyOf(result);
    }

    private static DryPath rawSourceDryPath(WindowGrid grid, WindowGraph raw, SharedBoundaryDrainage.WorldCell source,
                                           WindowOutlets.Terrain terrain) {
        int k=grid.index(source);
        if(k<0)return new DryPath(source,List.of(),"SOURCE_OUTSIDE_CONTEXT_PENDING_NO_ENTRY_FORCED");
        var ns=raw.next().get(k);var expected=ns==null || ns.size()!=1?source:grid.world(ns.get(0));
        var path=windowFollow(grid,raw,new WindowEntry(source,source,expected,"SAME_SOURCE_RAW",-1));
        var points=new ArrayList<DryPoint>();
        for(int cell:path.cells()){var w=grid.world(cell);points.add(new DryPoint(w.x(),w.z(),terrain.height(w.x(),w.z())));}
        return new DryPath(source,points,path.terminal());
    }

    private static void auditContinuousDryPaths(Path dir, WindowGrid grid, List<WindowEntry> entries,
                                                 WindowOutlets.Terrain actualTerrain) throws Exception {
        long start=System.nanoTime();long[] calls={0};
        WindowOutlets.Terrain terrain=(x,z)->{calls[0]++;return actualTerrain.height(x,z);};
        var sources=new java.util.TreeSet<SharedBoundaryDrainage.WorldCell>(java.util.Comparator.comparingInt(SharedBoundaryDrainage.WorldCell::x)
                .thenComparingInt(SharedBoundaryDrainage.WorldCell::z));entries.forEach(e->sources.add(e.source()));
        var candidates=new ArrayList<DryPath>();var rawPaths=new ArrayList<DryPath>();var raw=freezeWindowSuccessor(grid);
        var csv=new StringBuilder("sourceX,sourceZ,sourceInside,rawNodes,rawTerminal,candidateNodes,candidateTerminal,rawCrossings,candidateCrossings,originalOwnerEvents,globalCertified\n");
        var points=new StringBuilder("sourceX,sourceZ,variant,node,x,z,actualHeight,terminal\n");
        var crossings=new StringBuilder("sourceX,sourceZ,variant,event,axis,boundary,x,z,direction\n");
        var stability=new StringBuilder("sourceX,sourceZ,step1Terminal,stepHalfTerminal,step1X,step1Z,stepHalfX,stepHalfZ,endDistance,step1Crossings,stepHalfCrossings,stopGradientNorm,stopHeight,globalCertified\n");
        int inside=0;var terminals=new java.util.TreeMap<String,Integer>();boolean deterministic=true,descending=true;
        for(var source:sources) {
            var candidate=traceContinuousDry(grid,source,terrain,1);var original=rawSourceDryPath(grid,raw,source,terrain);
            candidates.add(candidate);rawPaths.add(original);if(grid.index(source)>=0)inside++;
            deterministic &= candidate.equals(traceContinuousDry(grid,source,terrain,1));terminals.merge(candidate.terminal(),1,Integer::sum);
            for(int i=1;i<candidate.points().size();i++)descending &= candidate.points().get(i).height()<candidate.points().get(i-1).height();
            var cc=dryCrossings(candidate.points(),hp().tract());var rc=dryCrossings(original.points(),hp().tract());
            csv.append(source.x()).append(',').append(source.z()).append(',').append(grid.index(source)>=0).append(',').append(original.points().size())
                    .append(',').append(original.terminal()).append(',').append(candidate.points().size()).append(',').append(candidate.terminal())
                    .append(',').append(rc.size()).append(',').append(cc.size()).append(',').append(entries.stream().filter(e->e.source().equals(source)).count()).append(",false\n");
            writeDryPathRows(points,crossings,original,"RAW_SAME_SOURCE");writeDryPathRows(points,crossings,candidate,"CONTINUOUS_DRY_ONLY");
            var half=traceContinuousDry(grid,source,terrain,0.5);writeDryPathRows(points,crossings,half,"CONTINUOUS_HALF_STEP");
            writeDryStability(stability,candidate,half,terrain);
        }
        Files.writeString(dir.resolve("continuous-source-summary.csv"),csv);Files.writeString(dir.resolve("continuous-source-points.csv"),points);
        Files.writeString(dir.resolve("continuous-source-crossings.csv"),crossings);Files.writeString(dir.resolve("continuous-step-stability.csv"),stability);
        chk(deterministic && descending,"连续干坡逐同源重复同值、采样高度严格下降，不强塞旧入口","different or rising");
        renderContinuousDryPaths(dir,grid,rawPaths,candidates);
        auditDryStops(dir,grid,candidates,terrain);
        log("G8 sameSourceCount="+sources.size()+" sourceInside="+inside+" outsideNotDeleted="+(sources.size()-inside)+" terminals="+terminals
                +" actualHeightCalls="+calls[0]+" elapsedMs="+(System.nanoTime()-start)/1e6
                +" NO_OLD_ENTRY_FORCING=true NO_MERGE_OR_GLOBAL_BASIN_CERTIFICATE=true FINITE_SAMPLED_DESCENT_ONLY=true");
    }

    private record StopRing(double minimumDelta, double maximumDelta, int lower, int sampledDescent,
                            double bestX, double bestZ) { }

    private static StopRing stopRing(WindowOutlets.Terrain terrain, DryPoint stop, double radius) {
        double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY,bx=Double.NaN,bz=Double.NaN;
        int lower=0,descent=0;
        for(int i=0;i<64;i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("stop ring");
            double angle=i*Math.PI/32,dx=radius*Math.cos(angle),dz=radius*Math.sin(angle);
            double delta=terrain.height(stop.x()+dx,stop.z()+dz)-stop.height();
            if(delta<minimum){minimum=delta;bx=dx;bz=dz;}maximum=Math.max(maximum,delta);
            if(delta<-1e-10)lower++;double previous=stop.height();boolean monotone=true;
            for(int j=1;j<=8;j++) {
                double h=terrain.height(stop.x()+dx*j/8,stop.z()+dz*j/8);
                monotone &= h<=previous+1e-10;previous=h;
            }
            if(monotone && delta<-1e-10)descent++;
        }
        return new StopRing(minimum,maximum,lower,descent,bx,bz);
    }

    private static void auditDryStops(Path dir, WindowGrid grid, List<DryPath> paths, WindowOutlets.Terrain terrain) throws Exception {
        long start=System.nanoTime();
        var rows=new StringBuilder("sourceX,sourceZ,stopX,stopZ,terminal,epsilon,gx,gz,norm,angleVs025,ringMinDelta,ringMaxDelta,ringLower64,ringSampledDescent64,bestDx,bestDz,tileSeamDistanceBlocks\n");
        var profiles=new StringBuilder("sourceX,sourceZ,stopX,stopZ,axis,offset,actualHeight,heightDelta\n");
        int stops=0;
        for(var path:paths) {
            if(path.points().isEmpty())continue;stops++;var stop=path.points().get(path.points().size()-1);
            var old=downhillGradient(terrain,stop.x(),stop.z(),0.25);
            for(double epsilon:new double[]{2,1,0.25,0.0625,0.015625,0.00390625,0.0009765625,0.000244140625}) {
                var v=downhillGradient(terrain,stop.x(),stop.z(),epsilon);var ring=stopRing(terrain,stop,epsilon);
                double tile=48*TP.horizontalScale(),fx=stop.x()-Math.floor(stop.x()/tile)*tile,fz=stop.z()-Math.floor(stop.z()/tile)*tile;
                rows.append(path.source().x()).append(',').append(path.source().z()).append(',').append(stop.x()).append(',').append(stop.z())
                        .append(',').append(path.terminal()).append(',').append(epsilon).append(',').append(v.x()).append(',').append(v.z())
                        .append(',').append(Math.hypot(v.x(),v.z())).append(',').append(directionAngle(v,old)).append(',').append(ring.minimumDelta())
                        .append(',').append(ring.maximumDelta()).append(',').append(ring.lower()).append(',').append(ring.sampledDescent())
                        .append(',').append(ring.bestX()).append(',').append(ring.bestZ()).append(',').append(Math.min(Math.min(fx,tile-fx),Math.min(fz,tile-fz))).append('\n');
            }
            for(int axis=0;axis<3;axis++)for(int i=-512;i<=512;i++) {
                double t=i/256.0,length=Math.hypot(old.x(),old.z());
                double dx=axis==0?1:axis==1?0:old.x()/length,dz=axis==0?0:axis==1?1:old.z()/length;
                double h=terrain.height(stop.x()+t*dx,stop.z()+t*dz);
                profiles.append(path.source().x()).append(',').append(path.source().z()).append(',').append(stop.x()).append(',').append(stop.z())
                        .append(',').append(axis).append(',').append(t).append(',').append(h).append(',').append(h-stop.height()).append('\n');
            }
        }
        Files.writeString(dir.resolve("continuous-stop-scales.csv"),rows);Files.writeString(dir.resolve("continuous-stop-profiles.csv"),profiles);
        auditStopPlanes(dir,grid,paths,terrain);
        auditStopTerrainParts(dir,paths);
        auditBaseComponents(dir,paths);
        log("G9 stopAuditCount="+stops+" ringDirections=64 segmentSamples=8 epsRange=2..0.000244140625 elapsedMs="+(System.nanoTime()-start)/1e6
                +" SAMPLED_LOCAL_EVIDENCE_NOT_CLOSED_BASIN_CERTIFICATE=true NO_ROUTE_CHANGE=true");
    }

    private static void auditStopTerrainParts(Path dir, List<DryPath> paths) throws Exception {
        var compute=CellGenerator.class.getDeclaredMethod("terrainEQuickCompute",double.class,double.class);compute.setAccessible(true);
        var csv=new StringBuilder("sourceX,sourceZ,axis,offset,x,z,baseE,coldBaseE,erosionDeltaE,actualHeight,repeatBaseE,repeatHeight\n");
        int unequal=0;
        for(var path:paths) {
            if(!path.terminal().startsWith("NO_SAMPLED_DESCENT") || path.points().isEmpty())continue;
            var stop=path.points().get(path.points().size()-1);
            for(int axis=0;axis<2;axis++)for(int i=-512;i<=512;i++) {
                double offset=i/1024.0,x=stop.x()+(axis==0?offset:0),z=stop.z()+(axis==1?offset:0);
                double wx=x/TP.horizontalScale(),wz=z/TP.horizontalScale(),base=GEN.terrainEQuick(wx,wz);
                double delta=GEN.erosionDeltaE(wx,wz),height=GEN.erodedHeight(wx,wz),cold=(double)compute.invoke(GEN,wx,wz);
                double repeat=GEN.terrainEQuick(wx,wz),rh=GEN.erodedHeight(wx,wz);
                if(Double.doubleToLongBits(base)!=Double.doubleToLongBits(cold))unequal++;
                csv.append(path.source().x()).append(',').append(path.source().z()).append(',').append(axis).append(',').append(offset)
                        .append(',').append(x).append(',').append(z).append(',').append(base).append(',').append(cold).append(',').append(delta)
                        .append(',').append(height).append(',').append(repeat).append(',').append(rh).append('\n');
            }
        }
        Files.writeString(dir.resolve("continuous-stop-terrain-parts.csv"),csv);
        log("G10 terrainParts baseCachedVsDirectUnequal="+unequal+" DIRECT_READONLY_NO_CACHE_WRITE=true NO_FIX_OR_ROUTE_CHANGE=true");
    }

    private static Object baseAuditField(Object owner, String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }

    private static java.lang.reflect.Method baseAuditMethod(String name, Class<?>... types) throws Exception {
        var method=CellGenerator.class.getDeclaredMethod(name,types);method.setAccessible(true);return method;
    }

    private static double[] baseComponents(double x, double z) throws Exception {
        double wx=x/TP.horizontalScale(),wz=z/TP.horizontalScale();
        var continent=(ContinentField)baseAuditField(GEN,"continent");var curve=(HeightCurve)baseAuditField(GEN,"heightCurve");
        var shapes=(TypeLandShape)baseAuditField(GEN,"typeLandShape");var tect=(TectonicField)baseAuditField(GEN,"tectonic");
        double c=continent.sample(wx,wz)-(double)baseAuditField(GEN,"continentBias");
        double eBase=curve.eFromC(c),depthMod=0.6+(1-(double)baseAuditMethod("smoothstep",double.class,double.class,double.class).invoke(null,-0.6,-0.2,eBase))*1.2;
        double seabed=(double)baseAuditField(GEN,"seabedAmp")*depthMod*((SeaBedDetail)baseAuditField(GEN,"seaBed")).sample(wx,wz);
        double ocean=(eBase+seabed)*(double)baseAuditField(GEN,"oceanDepthFactor");var ts=tect.sample(wx,wz);
        var of=((OceanFeatures)baseAuditField(GEN,"oceanFeatures")).compute(wx,wz,Math.min(ocean,0),c,ts,tect.oceanProfile(ts));
        var blend=shapes.sampleBlend(wx,wz);double[] before=blend.typeWeights.clone();
        if((boolean)baseAuditField(GEN,"TECTONIC_ENABLED"))baseAuditMethod("applyTectonicWeights",double[].class,TectonicField.Sample.class,double.class,double.class,double.class)
                .invoke(GEN,blend.typeWeights,ts,wx,wz,c);
        double[] after=blend.typeWeights.clone();double warp=((CoastlineField)baseAuditField(GEN,"coastline")).warpDisplacement(wx,wz,c);
        double land=shapes.sample(blend,wx,wz,c+warp);
        double factor=(double)baseAuditMethod("smoothstep",double.class,double.class,double.class).invoke(null,-0.01,0.01,land);
        var lf=((LandFeatures)baseAuditField(GEN,"landFeatures")).compute(wx,wz,ts);
        double deform=(boolean)baseAuditField(GEN,"DEFORM_ENABLED")?((TectonicDeformation)baseAuditField(GEN,"deform")).offset(ts,wx,wz):0;
        double sum=land+lf.total;sum+=deform;double guard=of.total*(1-factor);
        if(guard>0){double cap=-sum-(double)baseAuditField(GEN,"SEABED_MARGIN");guard=cap<=0?0:TectonicField.smoothMin(guard,cap,(double)baseAuditField(GEN,"SEABED_SOFT_SAT"));}
        double result=(double)baseAuditMethod("softCapLandE",double.class).invoke(GEN,sum+guard);
        double[] values=new double[12+before.length*2+6];double[] head={c,warp,land,lf.total,deform,of.total,guard,result,GEN.terrainEQuick(wx,wz),ts.stress(),lf.single,lf.field};
        System.arraycopy(head,0,values,0,head.length);System.arraycopy(before,0,values,12,before.length);System.arraycopy(after,0,values,12+before.length,after.length);
        int offset=12+before.length*2;values[offset]=shapes.typeGenerators().computeSharedNoise(wx,wz);
        var typeNoise=(TypeNoiseProvider)baseAuditField(shapes,"typeNoise");
        for(int i=0;i<TypeNoiseProvider.LAND_TYPES.length;i++)values[offset+1+i]=typeNoise.computeNoise(TypeNoiseProvider.LAND_TYPES[i],wx,wz);
        return values;
    }

    private static void auditBaseComponents(Path dir, List<DryPath> paths) throws Exception {
        var csv=new StringBuilder("sourceX,sourceZ,axis,offset,x,z,continentBiased,coastWarp,typeLand,landFeature,deform,oceanFeature,oceanGuard,reconstructed,actual,stress,landSingle,landField");
        int count=0,mismatch=0;double[] initial=null;
        for(var path:historicalDryStops()) {
            var stop=path.points().get(0);
            for(int axis=0;axis<2;axis++)for(int i=-512;i<=512;i++) {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("base components");
                double offset=i/1024.0,x=stop.x()+(axis==0?offset:0),z=stop.z()+(axis==1?offset:0);var v=baseComponents(x,z);
                if(initial==null){initial=v;for(int j=12;j<v.length-6;j++)csv.append(",weight").append(j-12);
                    csv.append(",sharedNoise,noisePlain,noiseHills,noiseMountains,noisePlateau,noiseBasin\n");}
                if(Double.doubleToLongBits(v[7])!=Double.doubleToLongBits(v[8]))mismatch++;count++;
                csv.append(path.source().x()).append(',').append(path.source().z()).append(',').append(axis).append(',').append(offset).append(',').append(x).append(',').append(z);
                for(double value:v)csv.append(',').append(value);csv.append('\n');
            }
        }
        Files.writeString(dir.resolve("continuous-stop-base-components.csv"),csv);
        chk(count>0 && mismatch==0,"基底各生产分量同序重建与实际terrainEQuick逐位相同","samples="+count+" mismatch="+mismatch);
        log("G11 baseComponentsSamples="+count+" reconstructionMismatch="+mismatch+" COMPONENTS_READONLY=true NO_PRODUCTION_CHANGE=true");
        auditWeightThreshold(dir,paths);
    }

    private static void auditWeightThreshold(Path dir, List<DryPath> paths) throws Exception {
        var csv=new StringBuilder("sourceX,sourceZ,epsilon,belowWeight,aboveWeight,belowTypeLand,aboveTypeLand,jump\n");
        var shapes=(TypeLandShape)baseAuditField(GEN,"typeLandShape");boolean continuous=true;int cases=0;
        for(var path:historicalDryStops()) {
            cases++;var stop=path.points().get(0);double x=stop.x()/TP.horizontalScale(),z=stop.z()/TP.horizontalScale();
            var components=baseComponents(stop.x(),stop.z());double c=components[0]+components[1];
            for(double epsilon:new double[]{1e-6,1e-8,1e-10,1e-12}) {
                var lo=new com.geogenesis.worldgen.terrain.TerrainCharacterField.BlendResult();
                lo.typeWeights=new double[TerrainClass.COUNT];
                System.arraycopy(components,12+TerrainClass.COUNT,lo.typeWeights,0,TerrainClass.COUNT);
                var hi=new com.geogenesis.worldgen.terrain.TerrainCharacterField.BlendResult();hi.typeWeights=lo.typeWeights.clone();
                lo.typeWeights[TerrainClass.MOUNTAINS.ordinal()]=0.001-epsilon;hi.typeWeights[TerrainClass.MOUNTAINS.ordinal()]=0.001+epsilon;
                double a=shapes.sample(lo,x,z,c),b=shapes.sample(hi,x,z,c);if(epsilon==1e-12)continuous &= Math.abs(b-a)<1e-9;
                csv.append(path.source().x()).append(',').append(path.source().z()).append(',').append(epsilon)
                        .append(',').append(0.001-epsilon).append(',').append(0.001+epsilon).append(',').append(a).append(',').append(b).append(',').append(b-a).append('\n');
            }
        }
        Files.writeString(dir.resolve("continuous-stop-weight-threshold.csv"),csv);
        chk(cases==4 && continuous,"schema79四历史停止点固定坐标：山地权重跨0.001连续，不因当前路线改变丢弃旧病例","cases="+cases+" continuous="+continuous);
    }

    private static List<DryPath> historicalDryStops() {
        double[][] historical={{-980,360,-1037.962553821303,554.1746238335984},{-952,380,-1000.4616035391183,557.5813491801932},
                {-884,388,-913.0873074254575,563.6672661332092},{-848,500,-881.9543427010001,558.2013765658921}};
        var fixed=new ArrayList<DryPath>();
        for(var h:historical)fixed.add(new DryPath(new SharedBoundaryDrainage.WorldCell((int)h[0],(int)h[1]),
                List.of(new DryPoint(h[2],h[3],0)),"HISTORICAL_SCHEMA79_STOP"));
        return List.copyOf(fixed);
    }

    private static void weightContinuityRegression() {
        var shapes=new TypeLandShape(TP);shapes.seed(DEF_SEED);
        TerrainClass[] types={TerrainClass.PLAIN,TerrainClass.HILLS,TerrainClass.MOUNTAINS,TerrainClass.PLATEAU,TerrainClass.BASIN,TerrainClass.OCEAN,TerrainClass.DEEP_OCEAN};
        double maxLand=0,maxOcean=0;int count=0;boolean contracts=true;
        for(var type:types)for(int variant=0;variant<8;variant++) {
            double x=-540+variant*41.7,z=270+variant*19.3,c=-0.15+variant*.04;
            double[] weights=new double[TerrainClass.COUNT];int target=type.ordinal();
            weights[TerrainClass.PLAIN.ordinal()]=.45;weights[TerrainClass.HILLS.ordinal()]=.3;weights[TerrainClass.BASIN.ordinal()]=.25;
            // Tiny land support makes the ocean cutoff measurable without changing its exponent.
            if(type.isOcean()){java.util.Arrays.fill(weights,0);weights[TerrainClass.PLAIN.ordinal()]=.005;}
            weights[target]=.001-1e-12;double a=sampleAuditWeights(shapes,weights,x,z,c);
            weights[target]=.001+1e-12;double b=sampleAuditWeights(shapes,weights,x,z,c);double gap=Math.abs(b-a);count++;
            if(type.isOcean())maxOcean=Math.max(maxOcean,gap);else maxLand=Math.max(maxLand,gap);
            weights[target]=0;double zero=sampleAuditWeights(shapes,weights,x,z,c);weights[target]=-1e-9;
            contracts &= Double.doubleToLongBits(zero)==Double.doubleToLongBits(sampleAuditWeights(shapes,weights,x,z,c));
            java.util.Arrays.fill(weights,0);weights[target]=1;double pure=sampleAuditWeights(shapes,weights,x,z,c);
            contracts &= Double.isFinite(pure) && pure>=-.35 && pure<=1
                    && Double.doubleToLongBits(pure)==Double.doubleToLongBits(sampleAuditWeights(shapes,weights,x,z,c));
        }
        chk(count==56 && maxLand<1e-9,"五陆地类型×八坐标：0.001两侧连续","landGap="+maxLand);
        chk(maxOcean<1e-13,"两海洋类型×八坐标：0.001两侧连续（小权重非空负例）","oceanGap="+maxOcean);
        chk(contracts,"零/负权重同值、纯类型有限且确定，保留现有混合语义","wrong");
    }

    private static double sampleAuditWeights(TypeLandShape shapes, double[] weights, double x, double z, double c) {
        var blend=new com.geogenesis.worldgen.terrain.TerrainCharacterField.BlendResult();blend.typeWeights=weights.clone();
        return shapes.sample(blend,x,z,c);
    }

    private static List<Integer> stopPlaneComponent(WindowGrid grid, int seed) {
        var p=grid.pipe();var cells=new ArrayList<Integer>();var seen=new java.util.BitSet(p.bed().length);cells.add(seed);seen.set(seed);
        long level=Double.doubleToLongBits(p.level()[seed]);
        for(int i=0;i<cells.size();i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("stop plane");
            int k=cells.get(i),x=k/p.nz(),z=k%p.nz();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                int nx=x+dx,nz=z+dz;if(nx<0 || nz<0 || nx>=p.nx() || nz>=p.nz())continue;int next=nx*p.nz()+nz;
                if(!seen.get(next) && Double.doubleToLongBits(p.level()[next])==level){seen.set(next);cells.add(next);}
            }
        }
        return List.copyOf(cells);
    }

    private static void auditStopPlanes(Path dir, WindowGrid grid, List<DryPath> paths, WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("sourceX,sourceZ,stopX,stopZ,probeX,probeZ,stencilX,stencilZ,anchorX,anchorZ,level,members,filled,unresolved,framePorts,lowerPairs,globalCertified\n");
        var pixels=new java.util.TreeSet<String>();var visited=new HashMap<Integer,List<Integer>>();var p=grid.pipe();
        var components=new java.util.TreeMap<Integer,List<Integer>>();
        for(var path:paths) {
            if(path.points().isEmpty())continue;var stop=path.points().get(path.points().size()-1);
            var v=downhillGradient(terrain,stop.x(),stop.z(),0.25);double norm=Math.hypot(v.x(),v.z());if(!(norm>0))continue;
            double px=stop.x()+v.x()/norm,pz=stop.z()+v.z()/norm;
            int x=(int)Math.floor((px-grid.ox())/p.cellBlocks()),z=(int)Math.floor((pz-grid.oz())/p.cellBlocks());
            if(x<0 || z<0 || x+1>=p.nx() || z+1>=p.nz())continue;
            for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
                int seed=(x+dx)*p.nz()+z+dz;if(!(p.level()[seed]>p.bed()[seed]))continue;
                var cells=visited.get(seed);
                if(cells==null){cells=stopPlaneComponent(grid,seed);for(int k:cells)visited.put(k,cells);}
                int anchor=cells.stream().mapToInt(Integer::intValue).min().orElseThrow();var w=grid.world(anchor);var s=grid.world(seed);
                components.put(anchor,cells);
                int filled=0,unknown=0,frame=0,lower=0;
                for(int k:cells) {
                    int a=k/p.nz(),b=k%p.nz();if(p.level()[k]>p.bed()[k])filled++;if(!p.resolved()[k])unknown++;
                    if(a==0 || b==0 || a==p.nx()-1 || b==p.nz()-1)frame++;
                    var world=grid.world(k);pixels.add(w.x()+","+w.z()+","+world.x()+","+world.z());
                    for(int u=-1;u<=1;u++)for(int t=-1;t<=1;t++) {
                        if(a+u<0 || b+t<0 || a+u>=p.nx() || b+t>=p.nz())continue;
                        if(p.level()[(a+u)*p.nz()+b+t]<p.level()[k])lower++;
                    }
                }
                rows.append(path.source().x()).append(',').append(path.source().z()).append(',').append(stop.x()).append(',').append(stop.z())
                        .append(',').append(px).append(',').append(pz).append(',').append(s.x()).append(',').append(s.z())
                        .append(',').append(w.x()).append(',').append(w.z()).append(',').append(p.level()[seed]).append(',').append(cells.size())
                        .append(',').append(filled).append(',').append(unknown).append(',').append(frame).append(',').append(lower).append(",false\n");
            }
        }
        Files.writeString(dir.resolve("continuous-stop-planes.csv"),rows);
        Files.writeString(dir.resolve("continuous-stop-plane-cells.csv"),"anchorX,anchorZ,x,z\n"+String.join("\n",pixels)+"\n");
        auditPlanePorts(dir,grid,components,terrain);
        auditContactDescent(dir,grid,paths,components,terrain);
        auditInternalTransfers(dir,grid,terrain);
        auditLocalWaterAccounting(dir,grid,components);
    }

    private record ContactDomain(double ox, double oz, double ex, double ez, int step, int nx, int nz,
                                 boolean[] allowed, boolean[] unknown) {
        String context(double x, double z) {
            int a=(int)Math.floor((x-ox)/step),b=(int)Math.floor((z-oz)/step);
            if(x<ox || z<oz || x>=ex || z>=ez || a<0 || b<0 || a>=nx || b>=nz || !allowed[a*nz+b])return "FIXED_CONTACT_RING_LIMIT_PENDING";
            return unknown[a*nz+b]?"ACTUAL_POINT_UNKNOWN_OWNER_PENDING":"KNOWN";
        }
    }

    private static ContactDomain contactDomain(WindowGrid grid, List<Integer> component) {
        var p=grid.pipe();boolean[] allowed=new boolean[p.bed().length],unknown=new boolean[allowed.length];
        for(int k:component)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            int x=k/p.nz()+dx,z=k%p.nz()+dz;
            if(x>=0 && z>=0 && x<p.nx() && z<p.nz())allowed[x*p.nz()+z]=true;
        }
        for(int k=0;k<unknown.length;k++)unknown[k]=!p.resolved()[k];double half=p.cellBlocks()*0.5;
        return new ContactDomain(grid.ox()-half,grid.oz()-half,grid.ox()+(p.nx()-1)*p.cellBlocks(),grid.oz()+(p.nz()-1)*p.cellBlocks(),
                p.cellBlocks(),p.nx(),p.nz(),allowed,unknown);
    }

    private static DryPoint contactAdvance(ContactDomain domain, WindowOutlets.Terrain terrain, DryPoint from, double step) {
        var v=downhillGradient(terrain,from.x(),from.z(),0.25);double norm=Math.hypot(v.x(),v.z());if(!(norm>1e-12))return null;
        double mx=from.x()+step*0.5*v.x()/norm,mz=from.z()+step*0.5*v.z()/norm;
        if(!domain.context(mx,mz).equals("KNOWN"))return null;
        var middle=downhillGradient(terrain,mx,mz,0.25);double ml=Math.hypot(middle.x(),middle.z());if(!(ml>1e-12))return null;
        double x=from.x()+step*middle.x()/ml,z=from.z()+step*middle.z()/ml,previous=from.height();
        for(int i=1;i<=8;i++) {
            double sx=from.x()+(x-from.x())*i/8,sz=from.z()+(z-from.z())*i/8;
            if(!domain.context(sx,sz).equals("KNOWN"))return null;double h=terrain.height(sx,sz);
            if(!Double.isFinite(h) || h>previous+1e-10)return null;previous=h;
        }
        return previous<from.height()-1e-12?new DryPoint(x,z,previous):null;
    }

    private static DryPath traceContactDescent(ContactDomain domain, WindowOutlets.Terrain terrain,
                                               SharedBoundaryDrainage.WorldCell source, DryPoint start, double maxStep) {
        var points=new ArrayList<DryPoint>();points.add(start);
        for(int i=0;i<4096;i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("contact descent");
            var current=points.get(points.size()-1);String here=domain.context(current.x(),current.z());
            if(!here.equals("KNOWN"))return new DryPath(source,points,here);
            var v=downhillGradient(terrain,current.x(),current.z(),0.25);double norm=Math.hypot(v.x(),v.z());
            if(!(norm>1e-12))return new DryPath(source,points,"ACTUAL_ZERO_GRADIENT_PENDING");
            String ahead=domain.context(current.x()+maxStep*v.x()/norm,current.z()+maxStep*v.z()/norm);
            if(!ahead.equals("KNOWN"))return new DryPath(source,points,ahead);
            DryPoint next=null;for(int halve=0;halve<11 && next==null;halve++)next=contactAdvance(domain,terrain,current,maxStep/Math.scalb(1,halve));
            if(next==null)return new DryPath(source,points,"ACTUAL_NO_SAMPLED_DESCENT_PENDING");points.add(next);
        }
        return new DryPath(source,points,"ACTUAL_BOUNDED_STEP_LIMIT_PENDING");
    }

    private static java.util.SortedSet<Integer> contactAnchors(WindowGrid grid, DryPoint stop, Map<Integer,Integer> anchors,
                                                               WindowOutlets.Terrain terrain) {
        var result=new java.util.TreeSet<Integer>();var v=downhillGradient(terrain,stop.x(),stop.z(),0.25);double norm=Math.hypot(v.x(),v.z());
        if(!(norm>0))return result;var p=grid.pipe();
        int x=(int)Math.floor((stop.x()+v.x()/norm-grid.ox())/p.cellBlocks()),z=(int)Math.floor((stop.z()+v.z()/norm-grid.oz())/p.cellBlocks());
        for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
            int a=x+dx,b=z+dz;if(a<0 || b<0 || a>=p.nx() || b>=p.nz())continue;int k=a*p.nz()+b;
            if(p.level()[k]>p.bed()[k] && anchors.containsKey(k))result.add(anchors.get(k));
        }
        return result;
    }

    private static void auditContactDescent(Path dir, WindowGrid grid, List<DryPath> paths, Map<Integer,List<Integer>> components,
                                            WindowOutlets.Terrain terrain) throws Exception {
        var anchors=new HashMap<Integer,Integer>();components.forEach((anchor,cells)->cells.forEach(k->anchors.put(k,anchor)));
        var rows=new StringBuilder("sourceX,sourceZ,anchorX,anchorZ,startX,startZ,startHeight,escapeLevel,step,endX,endZ,endHeight,nodes,firstBelowNode,firstInsideNode,terminal,globalCertified\n");
        var points=new StringBuilder("sourceX,sourceZ,anchorX,anchorZ,step,node,x,z,height,relativePlane,insideOriginalFootprint,context\n");
        var profiles=new StringBuilder("sourceX,sourceZ,anchorX,anchorZ,step,segment,sample,x,z,height\n");
        var links=new StringBuilder("sourceX,sourceZ,anchorX,anchorZ,step,kind,pointX,pointZ,targetX,targetZ,insideFootprint,linkMax,escapeLevel,sampledAtPlane\n");int known=0,unknown=0,outside=0;boolean valid=true;
        for(var path:paths) {
            if(path.points().isEmpty()){rows.append(path.source().x()).append(',').append(path.source().z())
                    .append(",NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,0,-1,-1,SOURCE_OUTSIDE_CONTEXT_PENDING,false\n");outside++;continue;}
            var start=path.points().get(path.points().size()-1);var incident=contactAnchors(grid,start,anchors,terrain);
            if(incident.isEmpty()){rows.append(path.source().x()).append(',').append(path.source().z()).append(",NaN,NaN,").append(start.x()).append(',').append(start.z())
                    .append(',').append(start.height()).append(",NaN,NaN,NaN,NaN,NaN,0,-1,-1,NO_INCIDENT_PLANE_PENDING,false\n");continue;}
            for(int anchor:incident) {
                var w=grid.world(anchor);var component=components.get(anchor);var domain=contactDomain(grid,component);
                boolean pending=component.stream().anyMatch(k->!grid.pipe().resolved()[k]);
                if(pending){rows.append(path.source().x()).append(',').append(path.source().z()).append(',').append(w.x()).append(',').append(w.z())
                        .append(',').append(start.x()).append(',').append(start.z()).append(',').append(start.height()).append(',').append(grid.pipe().level()[anchor])
                        .append(",NaN,NaN,NaN,NaN,0,-1,-1,INCIDENT_UNKNOWN_PLANE_PENDING,false\n");unknown++;continue;}
                var members=new java.util.BitSet(grid.pipe().bed().length);component.forEach(members::set);double level=grid.pipe().level()[anchor];
                for(double step:new double[]{1,0.5}) {
                    var result=traceContactDescent(domain,terrain,path.source(),start,step);valid &= result.equals(traceContactDescent(domain,terrain,path.source(),start,step));
                    int firstBelow=-1,firstInside=-1;
                    for(int j=0;j<result.points().size();j++) {
                        var at=result.points().get(j);int x=(int)Math.floor((at.x()-domain.ox())/domain.step()),z=(int)Math.floor((at.z()-domain.oz())/domain.step());
                        boolean inside=x>=0 && z>=0 && x<domain.nx() && z<domain.nz() && members.get(x*domain.nz()+z);
                        if(firstInside<0 && inside)firstInside=j;if(firstBelow<0 && at.height()<=level)firstBelow=j;
                        points.append(path.source().x()).append(',').append(path.source().z()).append(',').append(w.x()).append(',').append(w.z()).append(',').append(step).append(',').append(j)
                                .append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append(',').append(at.height()-level).append(',').append(inside).append(',').append(domain.context(at.x(),at.z())).append('\n');
                        if(j>0){var prev=result.points().get(j-1);valid &= at.height()<prev.height();
                            for(int t=0;t<=16;t++){double sx=prev.x()+(at.x()-prev.x())*t/16,sz=prev.z()+(at.z()-prev.z())*t/16;
                                profiles.append(path.source().x()).append(',').append(path.source().z()).append(',').append(w.x()).append(',').append(w.z()).append(',').append(step)
                                        .append(',').append(j-1).append(',').append(t).append(',').append(sx).append(',').append(sz).append(',').append(terrain.height(sx,sz)).append('\n');}}
                    }
                    var last=result.points().get(result.points().size()-1);
                    writeContactLinks(links,grid,members,path.source(),w,step,"STOP",last,level,terrain);
                    if(firstBelow>=0)writeContactLinks(links,grid,members,path.source(),w,step,"FIRST_BELOW",result.points().get(firstBelow),level,terrain);
                    rows.append(path.source().x()).append(',').append(path.source().z()).append(',').append(w.x()).append(',').append(w.z()).append(',').append(start.x()).append(',').append(start.z())
                            .append(',').append(start.height()).append(',').append(level).append(',').append(step).append(',').append(last.x()).append(',').append(last.z()).append(',').append(last.height())
                            .append(',').append(result.points().size()).append(',').append(firstBelow).append(',').append(firstInside).append(',').append(result.terminal()).append(",false\n");known++;
                }
            }
        }
        Files.writeString(dir.resolve("continuous-contact-summary.csv"),rows);Files.writeString(dir.resolve("continuous-contact-points.csv"),points);
        Files.writeString(dir.resolve("continuous-contact-profiles.csv"),profiles);
        Files.writeString(dir.resolve("continuous-contact-links.csv"),links);
        chk(valid && known>0,"真实停止点准确续追：同源重复同值、严格采样下降；未知与窗外身份不删", "wrong");
        log("G16 contactPaths="+known+" unknownIncident="+unknown+" outsideSources="+outside+" NO_COARSE_FILLED_STOP_IN_KNOWN_RING=true NO_WATER_OR_MERGE_AUTHORITY=true");
    }

    private static void writeContactLinks(StringBuilder csv, WindowGrid grid, java.util.BitSet members,
                                           SharedBoundaryDrainage.WorldCell source, SharedBoundaryDrainage.WorldCell anchor,
                                           double step, String kind, DryPoint point, double level, WindowOutlets.Terrain terrain) {
        double ox=grid.world(members.nextSetBit(0)).x()-6,oz=Double.POSITIVE_INFINITY;
        for(int k=members.nextSetBit(0);k>=0;k=members.nextSetBit(k+1))oz=Math.min(oz,grid.world(k).z()-6);
        double x0=ox+Math.floor((point.x()-ox)/.25)*.25,z0=oz+Math.floor((point.z()-oz)/.25)*.25;
        for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
            double x=x0+dx*.25,z=z0+dz*.25,max=Double.NEGATIVE_INFINITY;
            for(int i=0;i<=32;i++)max=Math.max(max,terrain.height(point.x()+(x-point.x())*i/32,point.z()+(z-point.z())*i/32));
            int a=(int)Math.floor((x-grid.ox()+grid.pipe().cellBlocks()*.5)/grid.pipe().cellBlocks());
            int b=(int)Math.floor((z-grid.oz()+grid.pipe().cellBlocks()*.5)/grid.pipe().cellBlocks());
            boolean inside=a>=0 && b>=0 && a<grid.pipe().nx() && b<grid.pipe().nz() && members.get(a*grid.pipe().nz()+b);
            csv.append(source.x()).append(',').append(source.z()).append(',').append(anchor.x()).append(',').append(anchor.z()).append(',').append(step).append(',').append(kind)
                    .append(',').append(point.x()).append(',').append(point.z()).append(',').append(x).append(',').append(z).append(',').append(inside)
                    .append(',').append(max).append(',').append(level).append(',').append(inside && max<=level).append('\n');
        }
    }

    private static void contactSynthetic() {
        boolean[] allowed=new boolean[81],unknown=new boolean[81];java.util.Arrays.fill(allowed,true);
        var domain=new ContactDomain(-16,-16,20,20,4,9,9,allowed,unknown);var source=new SharedBoundaryDrainage.WorldCell(-12,-12);
        WindowOutlets.Terrain slope=(x,z)->100-z;var start=new DryPoint(-2.3,-1.7,slope.height(-2.3,-1.7));
        var line=traceContactDescent(domain,slope,source,start,1);
        chk(line.points().size()>10 && line.terminal().equals("FIXED_CONTACT_RING_LIMIT_PENDING") && line.points().get(0).equals(start),
                "准确非格点接触点续追，不从最近网格或最低点伪造来水", "wrong");
        WindowOutlets.Terrain bowl=(x,z)->(x-2.3)*(x-2.3)+(z-3.7)*(z-3.7);
        var trapped=traceContactDescent(domain,bowl,source,new DryPoint(-2,-1,bowl.height(-2,-1)),1);
        var end=trapped.points().get(trapped.points().size()-1);
        chk(trapped.terminal().equals("ACTUAL_NO_SAMPLED_DESCENT_PENDING") && Math.hypot(end.x()-2.3,end.z()-3.7)<.02,
                "没有粗filled守卫仍会停在真实浅盆，不补桥", "wrong");
        unknown[4*9+4]=true;var stopped=traceContactDescent(domain,slope,source,new DryPoint(1,1,99),1);
        chk(stopped.points().size()==1 && stopped.terminal().equals("ACTUAL_POINT_UNKNOWN_OWNER_PENDING"),"真实接触未知owner不继续探查", "wrong");
        boolean cancelled=false;
        try{Thread.currentThread().interrupt();traceContactDescent(domain,slope,source,start,1);}catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        unknown[4*9+4]=false;
        chk(cancelled && line.equals(traceContactDescent(domain,slope,source,start,1)),"接触续追取消重试保持准确世界起点与完整路径", "wrong");
    }

    private record PlanePort(int from, int to) { }
    private record PortSuffix(List<Integer> cells, String terminal) {
        PortSuffix { cells=List.copyOf(cells); }
    }

    private static List<PlanePort> lowerPlanePorts(WindowGrid grid, List<Integer> cells) {
        var p=grid.pipe();var ports=new ArrayList<PlanePort>();
        for(int k:cells) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("plane ports");
            int x=k/p.nz(),z=k%p.nz();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                int a=x+dx,b=z+dz;if(a<0 || b<0 || a>=p.nx() || b>=p.nz())continue;
                int next=a*p.nz()+b;if(p.level()[next]<p.level()[k])ports.add(new PlanePort(k,next));
            }
        }
        ports.sort(java.util.Comparator.comparingInt(PlanePort::from).thenComparingInt(PlanePort::to));
        return List.copyOf(ports);
    }

    private static PortSuffix planePortSuffix(WindowGrid grid, List<Integer> component, int start) {
        var p=grid.pipe();var members=new java.util.BitSet(p.bed().length);component.forEach(members::set);
        var seen=new java.util.BitSet(p.bed().length);var path=new ArrayList<Integer>();int k=start;
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("plane suffix");
            if(k<0 || k>=p.bed().length)return new PortSuffix(path,"INVALID_PARENT_PENDING");
            if(seen.get(k))return new PortSuffix(path,"PARENT_CYCLE_PENDING");seen.set(k);path.add(k);
            if(members.get(k))return new PortSuffix(path,"REENTER_ORIGINAL_PLANE_PENDING");
            if(!p.resolved()[k])return new PortSuffix(path,"UNRESOLVED_DOWNSTREAM_PENDING");
            int x=k/p.nz(),z=k%p.nz();
            if(x==0 || z==0 || x==p.nx()-1 || z==p.nz()-1)return new PortSuffix(path,"UNKNOWN_FRAME_PENDING");
            if(p.sea()[k])return new PortSuffix(path,"LOCAL_SEA_NOT_GLOBAL_CERTIFICATE");
            if(p.level()[k]>p.bed()[k])return new PortSuffix(path,"NEXT_FILLED_PLANE_PENDING");
            int next=p.parent()[k];
            if(next<0)return new PortSuffix(path,"INTERNAL_PARENT_END_PENDING");
            if(next>=p.bed().length)return new PortSuffix(path,"INVALID_PARENT_PENDING");
            if(p.level()[next]>p.level()[k] && !members.get(next))return new PortSuffix(path,"RISING_ESCAPE_LEVEL_PENDING");
            k=next;
        }
    }

    private static void writePlaneSnapshot(Path dir, WindowGrid grid) throws Exception {
        var p=grid.pipe();var csv=new StringBuilder("cell,x,z,bed,level,resolved,sea,lake,parent,basinId,discharge,waterSurface,overflowAt\n");
        for(int k=0;k<p.bed().length;k++) {
            var w=grid.world(k);csv.append(k).append(',').append(w.x()).append(',').append(w.z()).append(',').append(p.bed()[k])
                    .append(',').append(p.level()[k]).append(',').append(p.resolved()[k]).append(',').append(p.sea()[k]).append(',').append(p.lake()[k])
                    .append(',').append(p.parent()[k]).append(',').append(LakeWaterBalance.basinOf(p.lakeResult(),k)).append(',').append(p.discharge()[k])
                    .append(',').append(p.waterSurfaceAt(k)).append(',').append(p.lakeResult().overflowAt().getOrDefault(k,0.0)).append('\n');
        }
        Files.writeString(dir.resolve("continuous-plane-grid.csv"),csv);
        var basins=new StringBuilder("basinId,seedCell,outletCell,sill,level,areaCells,catchmentCells,inflow,evaporation,outflow,overflow,truncated\n");
        for(int i=0;i<p.lakeResult().basins().size();i++) {
            var b=p.lakeResult().basins().get(i);
            basins.append(i).append(',').append(b.seedCell()).append(',').append(LakeWaterBalance.outletOf(p.lakeResult(),i)).append(',').append(b.sillLevel())
                    .append(',').append(b.level()).append(',').append(b.areaCells()).append(',').append(b.catchmentCells()).append(',').append(b.inflow())
                    .append(',').append(b.evaporationOut()).append(',').append(b.outflow()).append(',').append(b.overflow()).append(',').append(b.catchmentTruncated()).append('\n');
        }
        Files.writeString(dir.resolve("continuous-plane-basins.csv"),basins);
    }

    private static void auditPlanePorts(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components,
                                        WindowOutlets.Terrain terrain) throws Exception {
        var p=grid.pipe();int count=0;var ends=new java.util.TreeMap<String,Integer>();
        var rows=new StringBuilder("anchorX,anchorZ,port,fromCell,toCell,fromX,fromZ,toX,toZ,fromBed,toBed,fromLevel,toLevel,isParent,fromResolved,toResolved,segmentMaxRise,suffixNodes,suffixTerminal,globalCertified\n");
        var suffixRows=new StringBuilder("anchorX,anchorZ,port,node,cell,x,z,bed,level,resolved,sea,lake,basinId,terminal\n");
        var profiles=new StringBuilder("anchorX,anchorZ,port,sample,x,z,height\n");boolean complete=true;
        for(var entry:components.entrySet()) {
            var anchor=grid.world(entry.getKey());var ports=lowerPlanePorts(grid,entry.getValue());int pi=0;
            for(var port:ports) {
                int a=port.from(),b=port.to();var wa=grid.world(a);var wb=grid.world(b);double rise=0,previous=Double.NaN;
                for(int i=0;i<=32;i++) {
                    double x=wa.x()+(wb.x()-wa.x())*i/32.0,z=wa.z()+(wb.z()-wa.z())*i/32.0,h=terrain.height(x,z);
                    if(i>0)rise=Math.max(rise,h-previous);previous=h;
                    profiles.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(i).append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
                }
                var suffix=planePortSuffix(grid,entry.getValue(),b);ends.merge(suffix.terminal(),1,Integer::sum);
                rows.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(a).append(',').append(b)
                        .append(',').append(wa.x()).append(',').append(wa.z()).append(',').append(wb.x()).append(',').append(wb.z())
                        .append(',').append(p.bed()[a]).append(',').append(p.bed()[b]).append(',').append(p.level()[a]).append(',').append(p.level()[b])
                        .append(',').append(p.parent()[a]==b).append(',').append(p.resolved()[a]).append(',').append(p.resolved()[b]).append(',').append(rise)
                        .append(',').append(suffix.cells().size()).append(',').append(suffix.terminal()).append(",false\n");
                for(int j=0;j<suffix.cells().size();j++) {
                    int k=suffix.cells().get(j);var w=grid.world(k);
                    suffixRows.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(j).append(',').append(k)
                            .append(',').append(w.x()).append(',').append(w.z()).append(',').append(p.bed()[k]).append(',').append(p.level()[k])
                            .append(',').append(p.resolved()[k]).append(',').append(p.sea()[k]).append(',').append(p.lake()[k])
                            .append(',').append(LakeWaterBalance.basinOf(p.lakeResult(),k)).append(',').append(suffix.terminal()).append('\n');
                }
                complete &= p.level()[b]<p.level()[a] && !suffix.cells().isEmpty();pi++;count++;
            }
        }
        Files.writeString(dir.resolve("continuous-plane-ports.csv"),rows);
        Files.writeString(dir.resolve("continuous-plane-port-profiles.csv"),profiles);
        Files.writeString(dir.resolve("continuous-plane-port-suffixes.csv"),suffixRows);writePlaneSnapshot(dir,grid);
        auditPortWitnesses(dir,grid,components,terrain);
        auditPortSubcells(dir,grid,components,terrain);
        auditPlaneRings(dir,grid,components,terrain);
        chk(complete,"全部局部分量下降端口逐条保留，原parent后缀不补线、不越过未知", "invalid");
        log("G12 planeComponents="+components.size()+" allLowerPorts="+count+" suffixTerminals="+ends
                +" SNAPSHOT_CELLS="+p.bed().length+" NO_ADDITIONAL_PIPELINE=true NO_PLANE_OR_OUTFLOW_CERTIFICATE=true");
    }

    private static DryPoint portCornerWitness(WindowGrid grid, int cell, WindowOutlets.Terrain terrain) {
        var w=grid.world(cell);double half=grid.pipe().cellBlocks()*0.5;DryPoint best=null;
        for(int dx:new int[]{-1,1})for(int dz:new int[]{-1,1}) {
            double x=w.x()+dx*half,z=w.z()+dz*half,h=terrain.height(x,z);
            if(best==null || h<best.height())best=new DryPoint(x,z,h);
        }
        return best;
    }

    private static void auditPortWitnesses(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components,
                                           WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("anchorX,anchorZ,port,fromX,fromZ,toX,toZ,fromHeight,toHeight,fromBed,toBed,maxHeight,escapeLevel,abovePlane,maxSampleRise,coincident,globalCertified\n");
        var samples=new StringBuilder("anchorX,anchorZ,port,sample,x,z,height\n");boolean equal=true;int count=0,barriers=0;
        for(var entry:components.entrySet()) {
            var anchor=grid.world(entry.getKey());int pi=0;
            for(var port:lowerPlanePorts(grid,entry.getValue())) {
                var a=portCornerWitness(grid,port.from(),terrain);var b=portCornerWitness(grid,port.to(),terrain);
                double max=Double.NEGATIVE_INFINITY,rise=0,previous=Double.NaN,level=grid.pipe().level()[port.from()];
                for(int i=0;i<=32;i++) {
                    double x=a.x()+(b.x()-a.x())*i/32.0,z=a.z()+(b.z()-a.z())*i/32.0,h=terrain.height(x,z);
                    if(i>0)rise=Math.max(rise,h-previous);previous=h;max=Math.max(max,h);
                    samples.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(i).append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
                }
                double ba=grid.pipe().bed()[port.from()],bb=grid.pipe().bed()[port.to()];
                equal &= Double.doubleToLongBits(a.height())==Double.doubleToLongBits(ba) && Double.doubleToLongBits(b.height())==Double.doubleToLongBits(bb);
                rows.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(a.x()).append(',').append(a.z())
                        .append(',').append(b.x()).append(',').append(b.z()).append(',').append(a.height()).append(',').append(b.height())
                        .append(',').append(ba).append(',').append(bb).append(',').append(max).append(',').append(level).append(',').append(max-level)
                        .append(',').append(rise).append(',').append(a.x()==b.x() && a.z()==b.z()).append(",false\n");
                if(max>level+1e-10)barriers++;count++;pi++;
            }
        }
        Files.writeString(dir.resolve("continuous-plane-port-witnesses.csv"),rows);Files.writeString(dir.resolve("continuous-plane-port-witness-profiles.csv"),samples);
        chk(count>0 && equal,"所有端口两端四角最低见证逐位重建生产bed；见证连线仅采样证据", "mismatch");
        log("G13 witnessPorts="+count+" sampledChordAbovePlane="+barriers+" SEGMENT_SAMPLES=33 NOT_SUBCELL_MINIMAX_OR_CHANNEL=true");
    }

    private record SubcellPatch(double ox, double oz, double step, int nx, int nz,
                                double[] height, double[] east, double[] south, int start, int end) { }
    private record SubcellRoute(double barrier, List<Integer> path) {
        SubcellRoute { path=List.copyOf(path); }
    }
    private record SubcellQueue(int cell, double cost) { }

    private static SubcellPatch samplePortPatch(WindowOutlets.Terrain terrain, double ox, double oz, double ex, double ez,
                                                double step, DryPoint start, DryPoint end) {
        int nx=(int)Math.round((ex-ox)/step)+1,nz=(int)Math.round((ez-oz)/step)+1;
        double[] heights=new double[nx*nz],east=new double[nx*nz],south=new double[nx*nz];
        java.util.Arrays.fill(east,Double.POSITIVE_INFINITY);java.util.Arrays.fill(south,Double.POSITIVE_INFINITY);
        for(int x=0;x<nx;x++)for(int z=0;z<nz;z++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("subcell sample");
            heights[x*nz+z]=terrain.height(ox+x*step,oz+z*step);
        }
        for(int x=0;x<nx;x++)for(int z=0;z<nz;z++)for(int axis=0;axis<2;axis++) {
            if(axis==0?x+1>=nx:z+1>=nz)continue;int k=x*nz+z,next=axis==0?k+nz:k+1;
            double barrier=Math.max(heights[k],heights[next]);
            for(int i=1;i<4;i++)barrier=Math.max(barrier,terrain.height(ox+(x+(axis==0?i/4.0:0))*step,oz+(z+(axis==1?i/4.0:0))*step));
            (axis==0?east:south)[k]=barrier;
        }
        int a=(int)Math.round((start.x()-ox)/step)*nz+(int)Math.round((start.z()-oz)/step);
        int b=(int)Math.round((end.x()-ox)/step)*nz+(int)Math.round((end.z()-oz)/step);
        return new SubcellPatch(ox,oz,step,nx,nz,heights,east,south,a,b);
    }

    private static double subcellEdge(SubcellPatch p, int a, int b) {
        return Math.abs(a-b)==p.nz()?p.east()[Math.min(a,b)]:p.south()[Math.min(a,b)];
    }

    private static SubcellRoute subcellMinimax(SubcellPatch p) {
        double[] cost=new double[p.height().length];java.util.Arrays.fill(cost,Double.POSITIVE_INFINITY);
        int[] parent=new int[cost.length];java.util.Arrays.fill(parent,-1);
        var queue=new java.util.PriorityQueue<SubcellQueue>(java.util.Comparator.comparingDouble(SubcellQueue::cost).thenComparingInt(SubcellQueue::cell));
        cost[p.start()]=p.height()[p.start()];queue.add(new SubcellQueue(p.start(),cost[p.start()]));
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("subcell minimax");
            var item=queue.remove();int k=item.cell();if(item.cost()!=cost[k])continue;if(k==p.end())break;
            int x=k/p.nz(),z=k%p.nz();
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1}) {
                if(next<0)continue;double candidate=Math.max(cost[k],subcellEdge(p,k,next));
                if(candidate<cost[next]){cost[next]=candidate;parent[next]=k;queue.add(new SubcellQueue(next,candidate));}
            }
        }
        var path=new ArrayList<Integer>();
        if(Double.isFinite(cost[p.end()]))for(int k=p.end();k>=0;k=parent[k]){path.add(k);if(k==p.start())break;}
        java.util.Collections.reverse(path);return new SubcellRoute(cost[p.end()],path);
    }

    private static boolean subcellReachable(SubcellPatch p, double limit) {
        if(p.height()[p.start()]>limit)return false;var seen=new java.util.BitSet(p.height().length);
        var queue=new java.util.ArrayDeque<Integer>();queue.add(p.start());seen.set(p.start());
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("subcell oracle");
            int k=queue.remove();if(k==p.end())return true;int x=k/p.nz(),z=k%p.nz();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(Math.abs(dx)+Math.abs(dz)!=1)continue;int a=x+dx,b=z+dz;
                if(a<0 || b<0 || a>=p.nx() || b>=p.nz())continue;int next=a*p.nz()+b;
                double edge=dx==1?p.east()[k]:dx==-1?p.east()[next]:dz==1?p.south()[k]:p.south()[next];
                if(!seen.get(next) && edge<=limit && p.height()[next]<=limit){seen.set(next);queue.add(next);}
            }
        }
        return false;
    }

    private static void auditPortSubcells(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components,
                                          WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("anchorX,anchorZ,port,step,ox,oz,ex,ez,nodes,barrier,escapeLevel,excess,atPlane,oracleAtBarrier,oracleBelowBarrier,pathNodes,frameInteriorNodes,scope,globalCertified\n");
        var points=new StringBuilder("anchorX,anchorZ,port,step,node,x,z,height,edgeBarrier\n");
        var cells=new StringBuilder("anchorX,anchorZ,port,step,x,z,height\n");
        var dense=new StringBuilder("anchorX,anchorZ,port,step,segment,sample,x,z,height\n");boolean valid=true;int cases=0,skipped=0;
        for(var entry:components.entrySet()) {
            var anchor=grid.world(entry.getKey());int pi=0;
            for(var port:lowerPlanePorts(grid,entry.getValue())) {
                var a=portCornerWitness(grid,port.from(),terrain);var b=portCornerWitness(grid,port.to(),terrain);
                var wa=grid.world(port.from());var wb=grid.world(port.to());double half=grid.pipe().cellBlocks()*0.5;
                double ox=Math.min(wa.x(),wb.x())-half,oz=Math.min(wa.z(),wb.z())-half;
                double ex=Math.max(wa.x(),wb.x())+half,ez=Math.max(wa.z(),wb.z())+half,level=grid.pipe().level()[port.from()];
                if(ox<grid.ox() || oz<grid.oz() || ex>grid.ox()+(grid.pipe().nx()-1)*grid.pipe().cellBlocks()
                        || ez>grid.oz()+(grid.pipe().nz()-1)*grid.pipe().cellBlocks()) {
                    rows.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi++)
                            .append(",NaN,").append(ox).append(',').append(oz).append(',').append(ex).append(',').append(ez)
                            .append(",0,NaN,").append(level).append(",NaN,false,false,false,0,0,OUTSIDE_FIXED_CONTEXT_PENDING,false\n");skipped++;continue;
                }
                for(double step:new double[]{1,0.5,0.25,0.125}) {
                    var patch=samplePortPatch(terrain,ox,oz,ex,ez,step,a,b);var route=subcellMinimax(patch);
                    writeDenseSubcellPath(dense,anchor,pi,patch,route,terrain);
                    boolean at=subcellReachable(patch,level),on=subcellReachable(patch,route.barrier()),below=subcellReachable(patch,Math.nextDown(route.barrier()));
                    valid &= on && !below && at==(route.barrier()<=level);int frame=0;
                    for(int j=0;j<route.path().size();j++) {
                        int k=route.path().get(j),x=k/patch.nz(),z=k%patch.nz();
                        if(j>0 && j+1<route.path().size() && (x==0 || z==0 || x+1==patch.nx() || z+1==patch.nz()))frame++;
                        points.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(step).append(',').append(j)
                                .append(',').append(ox+x*step).append(',').append(oz+z*step).append(',').append(patch.height()[k])
                                .append(',').append(j==0?patch.height()[k]:subcellEdge(patch,route.path().get(j-1),k)).append('\n');
                    }
                    if(step==0.125)for(int k=0;k<patch.height().length;k++)cells.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi)
                            .append(',').append(step).append(',').append(ox+k/patch.nz()*step).append(',').append(oz+k%patch.nz()*step).append(',').append(patch.height()[k]).append('\n');
                    rows.append(anchor.x()).append(',').append(anchor.z()).append(',').append(pi).append(',').append(step)
                            .append(',').append(ox).append(',').append(oz).append(',').append(ex).append(',').append(ez).append(',').append(patch.height().length)
                            .append(',').append(route.barrier()).append(',').append(level).append(',').append(route.barrier()-level).append(',').append(at)
                            .append(',').append(on).append(',').append(below).append(',').append(route.path().size()).append(',').append(frame)
                            .append(",FIXED_TWO_CELL_RECT_SAMPLED_ONLY,false\n");cases++;
                }
                pi++;
            }
        }
        Files.writeString(dir.resolve("continuous-port-subcell-summary.csv"),rows);Files.writeString(dir.resolve("continuous-port-subcell-paths.csv"),points);
        Files.writeString(dir.resolve("continuous-port-subcell-grid.csv"),cells);
        Files.writeString(dir.resolve("continuous-port-subcell-dense-paths.csv"),dense);
        chk(cases>0 && valid,"固定两格矩形子格minimax与独立阈值BFS对拍：临界可达、下一浮点不可达", "wrong");
        log("G14 subcellCases="+cases+" outsideFixedContextSkipped="+skipped+" FOUR_NEIGHBOR_EDGE_QUARTERS=true FIXED_RECT_NOT_FULL_BASIN=true NO_PRODUCTION_CHANGE=true");
    }

    private static void writeDenseSubcellPath(StringBuilder csv, SharedBoundaryDrainage.WorldCell anchor, int port,
                                              SubcellPatch patch, SubcellRoute route, WindowOutlets.Terrain terrain) {
        for(int j=1;j<route.path().size();j++) {
            int a=route.path().get(j-1),b=route.path().get(j);
            double ax=patch.ox()+a/patch.nz()*patch.step(),az=patch.oz()+a%patch.nz()*patch.step();
            double bx=patch.ox()+b/patch.nz()*patch.step(),bz=patch.oz()+b%patch.nz()*patch.step();
            for(int i=0;i<=16;i++) {
                double x=ax+(bx-ax)*i/16,z=az+(bz-az)*i/16;
                csv.append(anchor.x()).append(',').append(anchor.z()).append(',').append(port).append(',').append(patch.step())
                        .append(',').append(j-1).append(',').append(i).append(',').append(x).append(',').append(z).append(',').append(terrain.height(x,z)).append('\n');
            }
        }
    }

    private record RingFlood(double[] cost, int[] parent) { }
    private record RingPatch(SubcellPatch patch, int[] owner, int[] mask, int bottom) { }

    private static RingFlood ringFlood(SubcellPatch p, int[] mask, int side, List<Integer> seeds) {
        double[] costs=new double[p.height().length];java.util.Arrays.fill(costs,Double.POSITIVE_INFINITY);
        int[] parent=new int[costs.length];java.util.Arrays.fill(parent,-1);
        var queue=new java.util.PriorityQueue<SubcellQueue>(java.util.Comparator.comparingDouble(SubcellQueue::cost).thenComparingInt(SubcellQueue::cell));
        for(int k:seeds)if(mask[k]==side){costs[k]=p.height()[k];queue.add(new SubcellQueue(k,costs[k]));}
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("ring flood");
            var item=queue.remove();int k=item.cell();if(item.cost()!=costs[k])continue;int x=k/p.nz(),z=k%p.nz();
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1}) {
                if(next<0 || mask[next]!=side)continue;double cost=Math.max(costs[k],subcellEdge(p,k,next));
                if(cost<costs[next]){costs[next]=cost;parent[next]=k;queue.add(new SubcellQueue(next,cost));}
            }
        }
        return new RingFlood(costs,parent);
    }

    private static RingPatch samplePlaneRing(WindowGrid grid, List<Integer> component, double step, WindowOutlets.Terrain terrain) {
        var p=grid.pipe();var inside=new java.util.BitSet(p.bed().length);component.forEach(inside::set);
        var covered=(java.util.BitSet)inside.clone();int minX=Integer.MAX_VALUE,maxX=0,minZ=Integer.MAX_VALUE,maxZ=0;
        for(int k:component)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            int x=k/p.nz()+dx,z=k%p.nz()+dz;if(x>=0 && z>=0 && x<p.nx() && z<p.nz())covered.set(x*p.nz()+z);
        }
        for(int k=covered.nextSetBit(0);k>=0;k=covered.nextSetBit(k+1)) {
            minX=Math.min(minX,k/p.nz());maxX=Math.max(maxX,k/p.nz());minZ=Math.min(minZ,k%p.nz());maxZ=Math.max(maxZ,k%p.nz());
        }
        double half=p.cellBlocks()*0.5,ox=grid.ox()+minX*p.cellBlocks()-half,oz=grid.oz()+minZ*p.cellBlocks()-half;
        double ex=grid.ox()+(maxX+1)*p.cellBlocks()-half,ez=grid.oz()+(maxZ+1)*p.cellBlocks()-half;
        var patch=samplePortPatch(terrain,ox,oz,ex,ez,step,new DryPoint(ox,oz,0),new DryPoint(ex,ez,0));
        int[] owner=new int[patch.height().length],mask=new int[owner.length];int bottom=-1;
        for(int k=0;k<owner.length;k++) {
            double x=ox+k/patch.nz()*step,z=oz+k%patch.nz()*step;
            int a=(int)Math.floor((x-grid.ox()+half)/p.cellBlocks()),b=(int)Math.floor((z-grid.oz()+half)/p.cellBlocks());
            int cell=a>=0 && b>=0 && a<p.nx() && b<p.nz()?a*p.nz()+b:-1;owner[k]=cell;
            mask[k]=cell<0 || !covered.get(cell)?-1:inside.get(cell)?1:0;
            if(mask[k]==1 && (bottom<0 || patch.height()[k]<patch.height()[bottom]))bottom=k;
        }
        return new RingPatch(patch,owner,mask,bottom);
    }

    private static List<Integer> ringSeeds(WindowGrid grid, RingPatch r, double level, String kind) {
        var seeds=new ArrayList<Integer>();var p=r.patch();var coarse=grid.pipe();
        for(int k=0;k<r.mask().length;k++) {
            if(r.mask()[k]!=0)continue;int owner=r.owner()[k],x=k/p.nz(),z=k%p.nz();
            boolean unknown=!coarse.resolved()[owner];
            boolean frame=x==0 || z==0 || x+1==p.nx() || z+1==p.nz();
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1})frame |= next<0 || r.mask()[next]<0;
            if(kind.equals("UNKNOWN")?unknown:kind.equals("RING_LIMIT")?frame:!unknown && coarse.level()[owner]<level)seeds.add(k);
        }
        return List.copyOf(seeds);
    }

    private static List<PlanePort> ringCrossings(RingPatch r) {
        var result=new ArrayList<PlanePort>();var p=r.patch();
        for(int k=0;k<r.mask().length;k++) {
            if(r.mask()[k]!=1)continue;int x=k/p.nz(),z=k%p.nz();
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1})
                if(next>=0 && r.mask()[next]==0)result.add(new PlanePort(k,next));
        }
        return List.copyOf(result);
    }

    private static double ringPortCost(SubcellPatch p, PlanePort edge, RingFlood inside, RingFlood outside) {
        return Math.max(inside.cost()[edge.from()],Math.max(subcellEdge(p,edge.from(),edge.to()),outside.cost()[edge.to()]));
    }

    private static void auditPlaneRings(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components,
                                        WindowOutlets.Terrain terrain) throws Exception {
        var summary=new StringBuilder("anchorX,anchorZ,step,ox,oz,nx,nz,bottomX,bottomZ,bottomHeight,escapeLevel,insideNodes,insideAtPlane,crossEdges,atPlaneLowerEdges,lowerBarrier,unknownBarrier,ringLimitBarrier,scope,globalCertified\n");
        var ports=new StringBuilder("anchorX,anchorZ,step,from,to,x,z,toX,toZ,fromOwner,toOwner,edgeBarrier,insideCost,lowerCost,unknownCost,ringLimitCost\n");
        var nodes=new StringBuilder("anchorX,anchorZ,step,node,x,z,height,east,south,owner,mask,insideCost,lowerCost,unknownCost,ringLimitCost,insideParent,lowerParent\n");
        var paths=new StringBuilder("anchorX,anchorZ,step,kind,node,x,z,height\n");
        var free=new StringBuilder("anchorX,anchorZ,step,escapeLevel,barrier,excess,wetNodes,precriticalNodes,wetRingNodes,limitSeeds,wetLimitSeeds,criticalEdges,comparedTwoPhase,scope\n");
        int done=0,skipped=0;boolean valid=true;
        for(var entry:components.entrySet()) {
            var anchor=grid.world(entry.getKey());double level=grid.pipe().level()[entry.getKey()];boolean pending=false;
            for(int cell:entry.getValue()) {
                int x=cell/grid.pipe().nz(),z=cell%grid.pipe().nz();
                pending |= !grid.pipe().resolved()[cell] || x<=1 || z<=1 || x>=grid.pipe().nx()-2 || z>=grid.pipe().nz()-2;
            }
            if(pending){summary.append(anchor.x()).append(',').append(anchor.z()).append(",NaN,NaN,NaN,0,0,NaN,NaN,NaN,").append(level)
                    .append(",0,0,0,0,NaN,NaN,NaN,COMPONENT_UNKNOWN_OR_FRAME_PENDING,false\n");skipped++;continue;}
            for(double step:new double[]{0.5,0.25}) {
                var r=samplePlaneRing(grid,entry.getValue(),step,terrain);var p=r.patch();
                var in=ringFlood(p,r.mask(),1,List.of(r.bottom()));
                int[] lowerMask=r.mask().clone();
                for(int k=0;k<lowerMask.length;k++)if(lowerMask[k]==0 && !grid.pipe().resolved()[r.owner()[k]])lowerMask[k]=-1;
                var lower=ringFlood(p,lowerMask,0,ringSeeds(grid,r,level,"LOWER"));
                var unknown=ringFlood(p,r.mask(),0,ringSeeds(grid,r,level,"UNKNOWN"));
                var frame=ringFlood(p,r.mask(),0,ringSeeds(grid,r,level,"RING_LIMIT"));
                var crossings=ringCrossings(r);double best=Double.POSITIVE_INFINITY,bu=best,bf=best;int at=0,inside=0,wet=0;PlanePort display=null;
                for(var edge:crossings) {
                    double lo=ringPortCost(p,edge,in,lower),un=ringPortCost(p,edge,in,unknown),fr=ringPortCost(p,edge,in,frame);
                    if(lo<best){best=lo;display=edge;}bu=Math.min(bu,un);bf=Math.min(bf,fr);if(lo<=level)at++;
                    ports.append(anchor.x()).append(',').append(anchor.z()).append(',').append(step).append(',').append(edge.from()).append(',').append(edge.to())
                            .append(',').append(p.ox()+edge.from()/p.nz()*step).append(',').append(p.oz()+edge.from()%p.nz()*step)
                            .append(',').append(p.ox()+edge.to()/p.nz()*step).append(',').append(p.oz()+edge.to()%p.nz()*step)
                            .append(',').append(r.owner()[edge.from()]).append(',').append(r.owner()[edge.to()]).append(',').append(subcellEdge(p,edge.from(),edge.to()))
                            .append(',').append(in.cost()[edge.from()]).append(',').append(lo).append(',').append(un).append(',').append(fr).append('\n');
                }
                for(int k=0;k<r.mask().length;k++) {
                    if(r.mask()[k]==1){inside++;if(in.cost()[k]<=level)wet++;}
                    nodes.append(anchor.x()).append(',').append(anchor.z()).append(',').append(step).append(',').append(k)
                            .append(',').append(p.ox()+k/p.nz()*step).append(',').append(p.oz()+k%p.nz()*step).append(',').append(p.height()[k])
                            .append(',').append(p.east()[k]).append(',').append(p.south()[k]).append(',').append(r.owner()[k]).append(',').append(r.mask()[k])
                            .append(',').append(in.cost()[k]).append(',').append(lower.cost()[k]).append(',').append(unknown.cost()[k]).append(',').append(frame.cost()[k])
                            .append(',').append(in.parent()[k]).append(',').append(lower.parent()[k]).append('\n');
                }
                if(display!=null)writeRingPath(paths,anchor,r,in,lower,display);
                auditFreeRing(dir,free,grid,anchor,r,level,bf,terrain);
                if(step==.25)auditDenseBasin(dir,grid,anchor,r,level,terrain);
                valid &= !crossings.isEmpty() && r.mask()[r.bottom()]==1;
                summary.append(anchor.x()).append(',').append(anchor.z()).append(',').append(step).append(',').append(p.ox()).append(',').append(p.oz())
                        .append(',').append(p.nx()).append(',').append(p.nz()).append(',').append(p.ox()+r.bottom()/p.nz()*step).append(',').append(p.oz()+r.bottom()%p.nz()*step)
                        .append(',').append(p.height()[r.bottom()]).append(',').append(level).append(',').append(inside).append(',').append(wet).append(',').append(crossings.size())
                        .append(',').append(at).append(',').append(best).append(',').append(bu).append(',').append(bf).append(",FIXED_COMPONENT_PLUS_ONE_RING_SAMPLED,false\n");done++;
            }
        }
        Files.writeString(dir.resolve("continuous-ring-summary.csv"),summary);Files.writeString(dir.resolve("continuous-ring-ports.csv"),ports);
        Files.writeString(dir.resolve("continuous-ring-grid.csv"),nodes);Files.writeString(dir.resolve("continuous-ring-paths.csv"),paths);
        Files.writeString(dir.resolve("continuous-free-summary.csv"),free);
        auditInterfaceLedger(dir);
        chk(done>0 && valid,"完整局部分量整圈子格接口全枚举，内外路径禁止重新入盆；窗框/未知单列", "invalid");
        log("G15 ringCases="+done+" unknownComponentsSkipped="+skipped+" FIXED_ONE_COARSE_RING=true BOTTOM_REFERENCE_NOT_SOURCE_AUTHORITY=true NOT_GLOBAL_BASIN_OR_OUTFLOW=true");
    }

    private static void writeRingPath(StringBuilder csv, SharedBoundaryDrainage.WorldCell anchor, RingPatch r,
                                      RingFlood inside, RingFlood lower, PlanePort edge) {
        var path=new ArrayList<Integer>();for(int k=edge.from();k>=0;k=inside.parent()[k])path.add(k);
        java.util.Collections.reverse(path);for(int k=edge.to();k>=0;k=lower.parent()[k])path.add(k);
        var p=r.patch();
        for(int i=0;i<path.size();i++) {
            int k=path.get(i);csv.append(anchor.x()).append(',').append(anchor.z()).append(',').append(p.step()).append(",MIN_LOWER_DISPLAY_NOT_AUTHORITY,")
                    .append(i).append(',').append(p.ox()+k/p.nz()*p.step()).append(',').append(p.oz()+k%p.nz()*p.step()).append(',').append(p.height()[k]).append('\n');
        }
    }

    private static java.util.BitSet freeReachable(SubcellPatch p, int[] mask, int start, double limit) {
        var seen=new java.util.BitSet(mask.length);var queue=new java.util.ArrayDeque<Integer>();
        if(mask[start]<0 || p.height()[start]>limit)return seen;queue.add(start);seen.set(start);
        while(!queue.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("free ring oracle");
            int k=queue.remove(),x=k/p.nz(),z=k%p.nz();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(Math.abs(dx)+Math.abs(dz)!=1)continue;int a=x+dx,b=z+dz;
                if(a<0 || b<0 || a>=p.nx() || b>=p.nz())continue;int next=a*p.nz()+b;
                if(mask[next]<0 || seen.get(next))continue;
                double edge=dx==1?p.east()[k]:dx==-1?p.east()[next]:dz==1?p.south()[k]:p.south()[next];
                if(edge<=limit && p.height()[next]<=limit){seen.set(next);queue.add(next);}
            }
        }
        return seen;
    }

    private static List<PlanePort> freeBoundary(SubcellPatch p, java.util.BitSet wet) {
        var result=new ArrayList<PlanePort>();
        for(int k=wet.nextSetBit(0);k>=0;k=wet.nextSetBit(k+1)) {
            int x=k/p.nz(),z=k%p.nz();
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1})
                if(next>=0 && !wet.get(next))result.add(new PlanePort(k,next));
        }
        return List.copyOf(result);
    }

    private static void auditFreeRing(Path dir, StringBuilder summary, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor,
                                      RingPatch r, double level, double twoPhase, WindowOutlets.Terrain terrain) throws Exception {
        var p=r.patch();int[] mask=new int[r.mask().length];
        for(int k=0;k<mask.length;k++)mask[k]=r.mask()[k]>=0 && grid.pipe().resolved()[r.owner()[k]]?0:-1;
        var flood=ringFlood(p,mask,0,List.of(r.bottom()));
        var limits=ringSeeds(grid,r,level,"RING_LIMIT");double barrier=Double.POSITIVE_INFINITY;int end=-1;
        for(int k:limits)if(flood.cost()[k]<barrier){barrier=flood.cost()[k];end=k;}
        var wet=freeReachable(p,mask,r.bottom(),level);var pre=freeReachable(p,mask,r.bottom(),Math.nextDown(barrier));
        var on=freeReachable(p,mask,r.bottom(),barrier);int wetRing=0,wetLimit=0,critical=0;
        for(int k=wet.nextSetBit(0);k>=0;k=wet.nextSetBit(k+1))if(r.mask()[k]==0)wetRing++;
        for(int k:limits)if(wet.get(k))wetLimit++;
        String suffix=anchor.x()+"_"+anchor.z()+"_"+p.step()+".csv";
        var cuts=new StringBuilder("kind,from,to,fromX,fromZ,toX,toZ,edgeBarrier,targetKnown,critical\n");
        var crests=new ArrayList<PlanePort>();
        for(int mode=0;mode<2;mode++)for(var e:freeBoundary(p,mode==0?wet:pre)) {
            double edge=subcellEdge(p,e.from(),e.to());boolean isCritical=mode==1 && mask[e.to()]>=0 && edge==barrier;
            if(isCritical){critical++;crests.add(e);}
            cuts.append(mode==0?"ORIGINAL_PLANE_WET_RIM":"PRECRITICAL_CUT").append(',').append(e.from()).append(',').append(e.to())
                    .append(',').append(p.ox()+e.from()/p.nz()*p.step()).append(',').append(p.oz()+e.from()%p.nz()*p.step())
                    .append(',').append(p.ox()+e.to()/p.nz()*p.step()).append(',').append(p.oz()+e.to()%p.nz()*p.step())
                    .append(',').append(edge).append(',').append(mask[e.to()]>=0).append(',').append(isCritical).append('\n');
        }
        var nodes=new StringBuilder("node,known,freeCost,wet,precritical,parent\n");
        for(int k=0;k<mask.length;k++)nodes.append(k).append(',').append(mask[k]>=0).append(',').append(flood.cost()[k])
                .append(',').append(wet.get(k)).append(',').append(pre.get(k)).append(',').append(flood.parent()[k]).append('\n');
        Files.writeString(dir.resolve("free-rim-"+suffix),cuts);Files.writeString(dir.resolve("free-grid-"+suffix),nodes);
        var path=new ArrayList<Integer>();if(end>=0)for(int k=end;k>=0;k=flood.parent()[k])path.add(k);java.util.Collections.reverse(path);
        var rows=new StringBuilder("node,x,z,height,edgeBarrier\n");
        for(int j=0;j<path.size();j++) {
            int k=path.get(j);rows.append(j).append(',').append(p.ox()+k/p.nz()*p.step()).append(',').append(p.oz()+k%p.nz()*p.step())
                    .append(',').append(p.height()[k]).append(',').append(j==0?p.height()[k]:subcellEdge(p,path.get(j-1),k)).append('\n');
        }
        var dense=new StringBuilder("anchorX,anchorZ,port,step,segment,sample,x,z,height\n");
        writeDenseSubcellPath(dense,anchor,0,p,new SubcellRoute(barrier,path),terrain);
        Files.writeString(dir.resolve("free-path-"+suffix),rows);Files.writeString(dir.resolve("free-profile-"+suffix),dense);
        if(p.step()==0.25)auditCrestRays(dir,grid,anchor,r,crests,terrain);
        boolean before=false,at=false;for(int k:limits){before |= pre.get(k);at |= on.get(k);}
        chk(end>=0 && at && !before && barrier<=twoPhase && critical>0,"自由子格外缘minimax及临界割BFS anchor="+anchor+" step="+p.step(),"invalid");
        summary.append(anchor.x()).append(',').append(anchor.z()).append(',').append(p.step()).append(',').append(level).append(',').append(barrier).append(',').append(barrier-level)
                .append(',').append(wet.cardinality()).append(',').append(pre.cardinality()).append(',').append(wetRing).append(',').append(limits.size()).append(',').append(wetLimit)
                .append(',').append(critical).append(',').append(twoPhase).append(",FIXED_RING_LIMIT_UNKNOWN_NOT_OUTFLOW\n");
        log("G17 anchor="+anchor+" step="+p.step()+" barrier="+barrier+" wet="+wet.cardinality()+" wetRing="+wetRing+" criticalEdges="+critical
                +" FREE_COARSE_CROSSINGS=true SAME_SAMPLED_PATCH=true NO_PIPELINE_OR_WATER_CHANGE=true");
    }

    private static String crestContext(WindowGrid grid, double x, double z) {
        var p=grid.pipe();if(x<=grid.ox() || z<=grid.oz() || x>=grid.ox()+(p.nx()-1)*p.cellBlocks()
                || z>=grid.oz()+(p.nz()-1)*p.cellBlocks())return "ORIGINAL_CONTEXT_LIMIT_PENDING";
        int a=(int)Math.floor((x-grid.ox()+p.cellBlocks()*.5)/p.cellBlocks());
        int b=(int)Math.floor((z-grid.oz()+p.cellBlocks()*.5)/p.cellBlocks()),k=a*p.nz()+b;
        if(!p.resolved()[k])return "UNKNOWN_OWNER_PENDING";
        return p.sea()[k]?"LOCAL_SEA_MARKER_NOT_GLOBAL_OCEAN":"KNOWN";
    }

    private static DryPoint crestAdvance(WindowGrid grid, WindowOutlets.Terrain terrain, DryPoint from, double step, double epsilon) {
        var v=downhillGradient(terrain,from.x(),from.z(),epsilon);double norm=Math.hypot(v.x(),v.z());if(!(norm>1e-12))return null;
        double mx=from.x()+step*.5*v.x()/norm,mz=from.z()+step*.5*v.z()/norm;
        if(!crestContext(grid,mx,mz).equals("KNOWN"))return null;
        var middle=downhillGradient(terrain,mx,mz,epsilon);double ml=Math.hypot(middle.x(),middle.z());if(!(ml>1e-12))return null;
        double x=from.x()+step*middle.x()/ml,z=from.z()+step*middle.z()/ml,previous=from.height();
        for(int i=1;i<=16;i++) {
            double sx=from.x()+(x-from.x())*i/16,sz=from.z()+(z-from.z())*i/16;
            if(!crestContext(grid,sx,sz).equals("KNOWN"))return null;double h=terrain.height(sx,sz);
            if(!Double.isFinite(h) || h>previous+1e-10)return null;previous=h;
        }
        return previous<from.height()-1e-12?new DryPoint(x,z,previous):null;
    }

    private static DryPath traceCrestRay(WindowGrid grid, WindowOutlets.Terrain terrain, SharedBoundaryDrainage.WorldCell label,
                                         DryPoint start, double epsilon) {
        var points=new ArrayList<DryPoint>();points.add(start);
        for(int i=0;i<4096;i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("crest ray");
            var current=points.get(points.size()-1);String context=crestContext(grid,current.x(),current.z());
            if(!context.equals("KNOWN"))return new DryPath(label,points,context);
            var v=downhillGradient(terrain,current.x(),current.z(),epsilon);double norm=Math.hypot(v.x(),v.z());
            if(!(norm>1e-12))return new DryPath(label,points,"ZERO_GRADIENT_PENDING");
            String ahead=crestContext(grid,current.x()+.5*v.x()/norm,current.z()+.5*v.z()/norm);
            if(!ahead.equals("KNOWN"))return new DryPath(label,points,ahead);
            DryPoint next=null;for(int halve=0;halve<11 && next==null;halve++)next=crestAdvance(grid,terrain,current,.5/Math.scalb(1,halve),epsilon);
            if(next==null)return new DryPath(label,points,"NO_SAMPLED_DESCENT_PENDING");points.add(next);
        }
        return new DryPath(label,points,"BOUNDED_STEP_LIMIT_PENDING");
    }

    private static void auditCrestRays(Path dir, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor, RingPatch r,
                                       List<PlanePort> crests, WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("crest,ray,epsilon,crestX,crestZ,crestHeight,startX,startZ,startHeight,endX,endZ,endHeight,nodes,endOwnerX,endOwnerZ,terminal,realSource,globalCertified\n");
        var points=new StringBuilder("crest,ray,epsilon,node,x,z,height,context\n");
        var profiles=new StringBuilder("crest,ray,epsilon,segment,sample,x,z,height\n");
        var edgeRows=new StringBuilder("crest,fromX,fromZ,toX,toZ,crestX,crestZ,height\n");
        var links=new StringBuilder("crest,ray,epsilon,x,z,targetX,targetZ,linkMax,allKnown\n");boolean valid=true;int ci=0,paths=0;
        var p=r.patch();
        for(var edge:crests) {
            double ax=p.ox()+edge.from()/p.nz()*p.step(),az=p.oz()+edge.from()%p.nz()*p.step();
            double bx=p.ox()+edge.to()/p.nz()*p.step(),bz=p.oz()+edge.to()%p.nz()*p.step();DryPoint crest=null;
            for(int t=0;t<=32;t++){double x=ax+(bx-ax)*t/32,z=az+(bz-az)*t/32,h=terrain.height(x,z);
                if(crest==null || h>crest.height())crest=new DryPoint(x,z,h);}
            edgeRows.append(ci).append(',').append(ax).append(',').append(az).append(',').append(bx).append(',').append(bz)
                    .append(',').append(crest.x()).append(',').append(crest.z()).append(',').append(crest.height()).append('\n');
            auditCrestLinks(dir,grid,anchor,ci,crest,terrain);
            auditExactCrestEdge(dir,grid,anchor,ci,crest,new DryPoint(ax,az,terrain.height(ax,az)),
                    new DryPoint(bx,bz,terrain.height(bx,bz)),terrain);
            for(int ray=0;ray<8;ray++) {
                double angle=ray*Math.PI/4,x=crest.x()+Math.cos(angle),z=crest.z()+Math.sin(angle);
                var start=new DryPoint(x,z,terrain.height(x,z));
                for(double epsilon:new double[]{.25,.0625}) {
                    var path=traceCrestRay(grid,terrain,anchor,start,epsilon);valid &= path.equals(traceCrestRay(grid,terrain,anchor,start,epsilon));
                    for(int j=0;j<path.points().size();j++) {
                        var at=path.points().get(j);points.append(ci).append(',').append(ray).append(',').append(epsilon).append(',').append(j)
                                .append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append(',').append(crestContext(grid,at.x(),at.z())).append('\n');
                        if(j>0){var prev=path.points().get(j-1);valid &= at.height()<prev.height();
                            for(int t=0;t<=32;t++){double sx=prev.x()+(at.x()-prev.x())*t/32,sz=prev.z()+(at.z()-prev.z())*t/32;
                                profiles.append(ci).append(',').append(ray).append(',').append(epsilon).append(',').append(j-1).append(',').append(t)
                                        .append(',').append(sx).append(',').append(sz).append(',').append(terrain.height(sx,sz)).append('\n');}}
                    }
                    var end=path.points().get(path.points().size()-1);var cp=grid.pipe();
                    writeCrestLinks(links,grid,terrain,ci,ray,epsilon,end);
                    int a=(int)Math.floor((end.x()-grid.ox()+cp.cellBlocks()*.5)/cp.cellBlocks()),b=(int)Math.floor((end.z()-grid.oz()+cp.cellBlocks()*.5)/cp.cellBlocks());
                    rows.append(ci).append(',').append(ray).append(',').append(epsilon).append(',').append(crest.x()).append(',').append(crest.z()).append(',').append(crest.height())
                            .append(',').append(start.x()).append(',').append(start.z()).append(',').append(start.height()).append(',').append(end.x()).append(',').append(end.z()).append(',').append(end.height())
                            .append(',').append(path.points().size()).append(',').append(grid.ox()+a*cp.cellBlocks()).append(',').append(grid.oz()+b*cp.cellBlocks())
                            .append(',').append(path.terminal()).append(",false,false\n");paths++;
                }
            }
            ci++;
        }
        String tag=anchor.x()+"_"+anchor.z()+".csv";
        Files.writeString(dir.resolve("crest-rays-"+tag),rows);Files.writeString(dir.resolve("crest-points-"+tag),points);
        Files.writeString(dir.resolve("crest-profiles-"+tag),profiles);Files.writeString(dir.resolve("crest-edges-"+tag),edgeRows);
        Files.writeString(dir.resolve("crest-links-"+tag),links);
        chk(paths==crests.size()*16 && valid,"临界点固定八向两尺度射线重复同值/采样下降 anchor="+anchor,"wrong");
        log("G18 anchor="+anchor+" crestEdges="+crests.size()+" diagnosticRays="+paths+" ORIGINAL_CONTEXT_ONLY=true REAL_SOURCE_FALSE_NO_Q=true NO_WATER_PUBLICATION=true");
    }

    private static void writeCrestLinks(StringBuilder csv, WindowGrid grid, WindowOutlets.Terrain terrain,
                                        int crest, int ray, double epsilon, DryPoint end) {
        double x0=Math.floor(end.x()*4)/4,z0=Math.floor(end.z()*4)/4;
        for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
            double x=x0+dx*.25,z=z0+dz*.25,max=Double.NEGATIVE_INFINITY;boolean known=true;
            for(int t=0;t<=32;t++) {
                double sx=end.x()+(x-end.x())*t/32,sz=end.z()+(z-end.z())*t/32;
                max=Math.max(max,terrain.height(sx,sz));known &= crestContext(grid,sx,sz).equals("KNOWN");
            }
            csv.append(crest).append(',').append(ray).append(',').append(epsilon).append(',').append(end.x()).append(',').append(end.z())
                    .append(',').append(x).append(',').append(z).append(',').append(max).append(',').append(known).append('\n');
        }
    }

    private record CrestLink(double barrier, int target, List<DryPoint> path) {
        CrestLink { path=List.copyOf(path); }
    }

    private static double crestSegment(WindowOutlets.Terrain terrain, DryPoint a, DryPoint b) {
        double max=Double.NEGATIVE_INFINITY;
        for(int t=0;t<=32;t++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("crest segment");
            double h=terrain.height(a.x()+(b.x()-a.x())*t/32,a.z()+(b.z()-a.z())*t/32);
            if(!Double.isFinite(h))return Double.POSITIVE_INFINITY;max=Math.max(max,h);
        }
        return max;
    }

    private static CrestLink connectCrest(SubcellPatch p, RingFlood flood, DryPoint target, WindowOutlets.Terrain terrain) {
        int x=(int)Math.floor((target.x()-p.ox())/p.step()),z=(int)Math.floor((target.z()-p.oz())/p.step());
        double best=Double.POSITIVE_INFINITY;int chosen=-1;
        for(int dx=0;dx<=1;dx++)for(int dz=0;dz<=1;dz++) {
            int a=x+dx,b=z+dz;if(a<0 || b<0 || a>=p.nx() || b>=p.nz())continue;int k=a*p.nz()+b;
            var at=new DryPoint(p.ox()+a*p.step(),p.oz()+b*p.step(),p.height()[k]);
            double cost=Math.max(flood.cost()[k],crestSegment(terrain,at,target));
            if(cost<best){best=cost;chosen=k;}
        }
        var path=new ArrayList<DryPoint>();
        if(chosen>=0) {
            for(int k=chosen;k>=0;k=flood.parent()[k])path.add(new DryPoint(p.ox()+k/p.nz()*p.step(),p.oz()+k%p.nz()*p.step(),p.height()[k]));
            java.util.Collections.reverse(path);path.add(target);
        }
        return new CrestLink(best,chosen,path);
    }

    private static void auditCrestLinks(Path dir, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor,
                                        int crestId, DryPoint crest, WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("step,ray,crestX,crestZ,crestHeight,targetX,targetZ,targetHeight,barrier,excess,endpointLowerBound,interiorExcess,targetNode,pathNodes,denseMax,denseOvershoot,globalCertified\n");
        var cells=new StringBuilder("step,node,x,z,height,east,south,cost,parent,known\n");
        var points=new StringBuilder("step,ray,node,x,z,height\n");var dense=new StringBuilder("step,ray,segment,sample,x,z,height\n");
        WindowOutlets.Terrain known=(x,z)->crestContext(grid,x,z).equals("KNOWN")?terrain.height(x,z):Double.POSITIVE_INFINITY;
        boolean valid=true;int count=0;
        for(double step:new double[]{.125,.03125}) {
            var p=samplePortPatch(known,crest.x()-1,crest.z()-1,crest.x()+1,crest.z()+1,step,crest,crest);
            int[] mask=new int[p.height().length];for(int k=0;k<mask.length;k++)if(!Double.isFinite(p.height()[k]))mask[k]=-1;
            var flood=ringFlood(p,mask,0,List.of(p.start()));
            for(int k=0;k<mask.length;k++)cells.append(step).append(',').append(k).append(',').append(p.ox()+k/p.nz()*step).append(',').append(p.oz()+k%p.nz()*step)
                    .append(',').append(p.height()[k]).append(',').append(p.east()[k]).append(',').append(p.south()[k]).append(',').append(flood.cost()[k])
                    .append(',').append(flood.parent()[k]).append(',').append(mask[k]==0).append('\n');
            for(int ray=0;ray<8;ray++) {
                double angle=ray*Math.PI/4,x=crest.x()+Math.cos(angle),z=crest.z()+Math.sin(angle);
                var target=new DryPoint(x,z,known.height(x,z));var link=connectCrest(p,flood,target,known);
                valid &= link.equals(connectCrest(p,flood,target,known));double max=writeCrestLinkPath(points,dense,step,ray,link,known);
                double endpoint=Math.max(crest.height(),target.height());
                valid &= !Double.isFinite(link.barrier()) || link.barrier()>=endpoint;
                rows.append(step).append(',').append(ray).append(',').append(crest.x()).append(',').append(crest.z()).append(',').append(crest.height())
                        .append(',').append(x).append(',').append(z).append(',').append(target.height()).append(',').append(link.barrier())
                        .append(',').append(link.barrier()-crest.height()).append(',').append(endpoint).append(',').append(link.barrier()-endpoint)
                        .append(',').append(link.target()).append(',').append(link.path().size()).append(',').append(max).append(',').append(max-link.barrier()).append(",false\n");count++;
            }
        }
        String tag=anchor.x()+"_"+anchor.z()+"_"+crestId+".csv";
        Files.writeString(dir.resolve("crest-connect-summary-"+tag),rows);Files.writeString(dir.resolve("crest-connect-grid-"+tag),cells);
        Files.writeString(dir.resolve("crest-connect-paths-"+tag),points);Files.writeString(dir.resolve("crest-connect-dense-"+tag),dense);
        chk(count==16 && valid,"准确脊顶到原八射线起点固定邻域连接重复同值 anchor="+anchor+" crest="+crestId,"wrong");
        log("G19 anchor="+anchor+" crest="+crestId+" cases="+count+" FIXED_CREST_PLUS_MINUS_ONE=true SAMPLED_CONNECTIVITY_NOT_DESCENT=true NO_WATER_OR_PLANE_CHANGE=true");
    }

    private static double writeCrestLinkPath(StringBuilder points, StringBuilder dense, double step, int ray,
                                             CrestLink link, WindowOutlets.Terrain terrain) {
        double max=Double.NEGATIVE_INFINITY;
        for(int j=0;j<link.path().size();j++) {
            var at=link.path().get(j);points.append(step).append(',').append(ray).append(',').append(j).append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append('\n');
            max=Math.max(max,at.height());if(j==0)continue;var previous=link.path().get(j-1);
            for(int t=0;t<=32;t++) {
                double x=previous.x()+(at.x()-previous.x())*t/32,z=previous.z()+(at.z()-previous.z())*t/32,h=terrain.height(x,z);max=Math.max(max,h);
                dense.append(step).append(',').append(ray).append(',').append(j-1).append(',').append(t).append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
            }
        }
        return max;
    }

    private static void auditDenseBasin(Path dir, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor, RingPatch r,
                                        double level, WindowOutlets.Terrain terrain) throws Exception {
        var p=denseCrestPatch(r.patch(),terrain);int[] mask=new int[r.mask().length];
        for(int k=0;k<mask.length;k++)mask[k]=r.mask()[k]>=0 && grid.pipe().resolved()[r.owner()[k]]?0:-1;
        var old=ringFlood(r.patch(),mask,0,List.of(r.bottom()));var flood=ringFlood(p,mask,0,List.of(r.bottom()));
        var limits=ringSeeds(grid,r,level,"RING_LIMIT");
        double barrier=Double.POSITIVE_INFINITY,oldBarrier=barrier;int end=-1;
        for(int k:limits){oldBarrier=Math.min(oldBarrier,old.cost()[k]);if(flood.cost()[k]<barrier){barrier=flood.cost()[k];end=k;}}
        var wet=freeReachable(p,mask,r.bottom(),level);var pre=freeReachable(p,mask,r.bottom(),Math.nextDown(barrier));
        var on=freeReachable(p,mask,r.bottom(),barrier);var oldWet=freeReachable(r.patch(),mask,r.bottom(),level);
        var removed=(java.util.BitSet)oldWet.clone();removed.andNot(wet);var added=(java.util.BitSet)wet.clone();added.andNot(oldWet);
        int[] outside=mask.clone();for(int k=pre.nextSetBit(0);k>=0;k=pre.nextSetBit(k+1))outside[k]=-1;
        var reverse=ringFlood(p,outside,0,limits);
        var nodes=new StringBuilder("node,x,z,height,east,south,mask,known,oldCost,cost,reverseCost,parent,wet,precritical,atCritical\n");
        var cuts=new StringBuilder("kind,from,to,x,z,toX,toZ,oldEdge,edge,known,critical,routeCompatible\n");
        var terminals=new StringBuilder("kind,node,x,z,ownerX,ownerZ,resolved,sea,cost,oldCost,atPlane,atCritical\n");
        var interfaces=new ArrayList<PlanePort>();int critical=0,compatible=0;boolean valid=end>=0 && barrier>=oldBarrier && added.isEmpty();
        for(int k=0;k<mask.length;k++) {
            valid &= flood.cost()[k]>=old.cost()[k];
            nodes.append(k).append(',').append(p.ox()+k/p.nz()*p.step()).append(',').append(p.oz()+k%p.nz()*p.step())
                    .append(',').append(p.height()[k]).append(',').append(p.east()[k]).append(',').append(p.south()[k]).append(',').append(r.mask()[k])
                    .append(',').append(mask[k]>=0).append(',').append(old.cost()[k]).append(',').append(flood.cost()[k]).append(',').append(reverse.cost()[k])
                    .append(',').append(flood.parent()[k]).append(',').append(wet.get(k)).append(',').append(pre.get(k)).append(',').append(on.get(k)).append('\n');
        }
        for(int mode=0;mode<2;mode++)for(var e:freeBoundary(p,mode==0?wet:pre)) {
            double cost=subcellEdge(p,e.from(),e.to());boolean crit=mode==1 && mask[e.to()]>=0 && cost==barrier;
            boolean route=crit && reverse.cost()[e.to()]<=barrier;if(crit)critical++;if(route){compatible++;interfaces.add(e);}
            cuts.append(mode==0?"ORIGINAL_LEVEL_RIM":"PRECRITICAL_CUT").append(',').append(e.from()).append(',').append(e.to())
                    .append(',').append(p.ox()+e.from()/p.nz()*p.step()).append(',').append(p.oz()+e.from()%p.nz()*p.step())
                    .append(',').append(p.ox()+e.to()/p.nz()*p.step()).append(',').append(p.oz()+e.to()%p.nz()*p.step())
                    .append(',').append(subcellEdge(r.patch(),e.from(),e.to())).append(',').append(cost).append(',').append(mask[e.to()]>=0)
                    .append(',').append(crit).append(',').append(route).append('\n');
        }
        for(String kind:new String[]{"RING_LIMIT","LOWER","UNKNOWN"})for(int k:ringSeeds(grid,r,level,kind)) {
            var owner=grid.world(r.owner()[k]);terminals.append(kind).append(',').append(k).append(',').append(p.ox()+k/p.nz()*p.step())
                    .append(',').append(p.oz()+k%p.nz()*p.step()).append(',').append(owner.x()).append(',').append(owner.z())
                    .append(',').append(grid.pipe().resolved()[r.owner()[k]]).append(',').append(grid.pipe().sea()[r.owner()[k]])
                    .append(',').append(flood.cost()[k]).append(',').append(old.cost()[k]).append(',').append(wet.get(k)).append(',').append(on.get(k)).append('\n');
        }
        var path=crestGridPath(p,flood,end);var profiles=new StringBuilder("mode,kind,segment,sample,x,z,height\n");
        double actual=writeExactCrestPath(profiles,"BASIN_EDGE33","EXAMPLE_TO_FIXED_FRAME",path,terrain);
        var points=new StringBuilder("node,x,z,height\n");for(int j=0;j<path.size();j++){var at=path.get(j);
            points.append(j).append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append('\n');}
        boolean before=false,at=false;for(int k:limits){before |= pre.get(k);at |= on.get(k);}
        valid &= !before && at && critical>0 && compatible>0;
        var summary="anchorX,anchorZ,step,nx,nz,bottom,escapeLevel,oldBarrier,barrier,increase,oldWet,wet,removedWet,criticalEdges,compatibleEdges,end,pathNodes,denseMax,denseOvershoot,globalCertified\n"
                +anchor.x()+","+anchor.z()+","+p.step()+","+p.nx()+","+p.nz()+","+r.bottom()+","+level+","+oldBarrier+","+barrier+","+(barrier-oldBarrier)
                +","+oldWet.cardinality()+","+wet.cardinality()+","+removed.cardinality()+","+critical+","+compatible+","+end+","+path.size()+","+actual+","+(actual-barrier)+",false\n";
        String tag=anchor.x()+"_"+anchor.z()+".csv";
        Files.writeString(dir.resolve("basin-dense-summary-"+tag),summary);Files.writeString(dir.resolve("basin-dense-grid-"+tag),nodes);
        Files.writeString(dir.resolve("basin-dense-cuts-"+tag),cuts);Files.writeString(dir.resolve("basin-dense-terminals-"+tag),terminals);
        Files.writeString(dir.resolve("basin-dense-path-"+tag),points);Files.writeString(dir.resolve("basin-dense-profile-"+tag),profiles);
        auditInterfaceReceivers(dir,grid,anchor,p,interfaces,barrier,terrain);
        chk(valid,"整盆同范围33点边重算只增代价不增原平面可达集，临界完整割与外缘相容 anchor="+anchor,"wrong");
        log("G21 anchor="+anchor+" oldBarrier="+oldBarrier+" barrier="+barrier+" critical="+critical+" compatible="+compatible
                +" SAME_FOOTPRINT_PLUS_ONE_RING=true FRAME_IS_UNKNOWN_NOT_OUTFLOW=true NO_PRODUCTION_CHANGE=true");
    }

    private static void auditInterfaceReceivers(Path dir, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor,
                                                SubcellPatch p, List<PlanePort> ports, double barrier, WindowOutlets.Terrain terrain) throws Exception {
        var rows=new StringBuilder("port,side,epsilon,startX,startZ,startHeight,endX,endZ,endHeight,nodes,terminal,edgeBarrier,edgeMax129,edgeOvershoot,realSource,globalCertified\n");
        var points=new StringBuilder("port,side,epsilon,node,x,z,height,context\n");
        var profiles=new StringBuilder("port,side,epsilon,segment,sample,x,z,height\n");
        var edges=new StringBuilder("port,sample,x,z,height,context\n");
        var links=new StringBuilder("crest,ray,epsilon,x,z,targetX,targetZ,linkMax,allKnown\n");boolean valid=true;int count=0;
        for(int i=0;i<ports.size();i++) {
            var edge=ports.get(i);var a=new DryPoint(p.ox()+edge.from()/p.nz()*p.step(),p.oz()+edge.from()%p.nz()*p.step(),p.height()[edge.from()]);
            var b=new DryPoint(p.ox()+edge.to()/p.nz()*p.step(),p.oz()+edge.to()%p.nz()*p.step(),p.height()[edge.to()]);double max=Double.NEGATIVE_INFINITY;
            for(int t=0;t<=128;t++){double x=a.x()+(b.x()-a.x())*t/128,z=a.z()+(b.z()-a.z())*t/128,h=terrain.height(x,z);max=Math.max(max,h);
                edges.append(i).append(',').append(t).append(',').append(x).append(',').append(z).append(',').append(h).append(',').append(crestContext(grid,x,z)).append('\n');}
            for(int side=0;side<2;side++)for(double epsilon:new double[]{.25,.0625}) {
                var start=side==0?a:b;var path=traceCrestRay(grid,terrain,anchor,start,epsilon);valid &= path.equals(traceCrestRay(grid,terrain,anchor,start,epsilon));
                valid &= path.points().get(0).equals(start);
                for(int j=0;j<path.points().size();j++) {
                    var at=path.points().get(j);points.append(i).append(',').append(side).append(',').append(epsilon).append(',').append(j)
                            .append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append(',').append(crestContext(grid,at.x(),at.z())).append('\n');
                    if(j==0)continue;var previous=path.points().get(j-1);valid &= at.height()<previous.height();
                    for(int t=0;t<=32;t++){double x=previous.x()+(at.x()-previous.x())*t/32,z=previous.z()+(at.z()-previous.z())*t/32;
                        profiles.append(i).append(',').append(side).append(',').append(epsilon).append(',').append(j-1).append(',').append(t)
                                .append(',').append(x).append(',').append(z).append(',').append(terrain.height(x,z)).append('\n');}
                }
                var end=path.points().get(path.points().size()-1);writeCrestLinks(links,grid,terrain,i,side,epsilon,end);
                rows.append(i).append(',').append(side).append(',').append(epsilon).append(',').append(start.x()).append(',').append(start.z()).append(',').append(start.height())
                        .append(',').append(end.x()).append(',').append(end.z()).append(',').append(end.height()).append(',').append(path.points().size()).append(',').append(path.terminal())
                        .append(',').append(barrier).append(',').append(max).append(',').append(max-barrier).append(",false,false\n");count++;
            }
        }
        String tag=anchor.x()+"_"+anchor.z()+".csv";
        Files.writeString(dir.resolve("interface-receiver-summary-"+tag),rows);Files.writeString(dir.resolve("interface-receiver-points-"+tag),points);
        Files.writeString(dir.resolve("interface-receiver-profiles-"+tag),profiles);Files.writeString(dir.resolve("interface-receiver-edges-"+tag),edges);
        Files.writeString(dir.resolve("interface-receiver-links-"+tag),links);
        chk(valid && count==ports.size()*4,"新完整临界接口准确两端重复下降追踪、不造偏移源 anchor="+anchor,"wrong");
        log("G22 anchor="+anchor+" interfaces="+ports.size()+" paths="+count+" EXACT_INTERFACE_ENDPOINTS=true OLD_CONTEXT_ONLY=true NO_Q_OR_WATER_CHANGE=true");
    }

    private record InterfaceObservation(String component, String edge, int orientation, double epsilon, int side,
                                         List<String> recipients, String terminal, double barrier, Double denseRise) {
        InterfaceObservation { recipients=List.copyOf(recipients); }
    }

    private static List<Map<String,String>> interfaceCsv(Path path) throws Exception {
        var lines=Files.readAllLines(path);var names=lines.get(0).split(",",-1);var rows=new ArrayList<Map<String,String>>();
        for(int i=1;i<lines.size();i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("interface ledger");
            var values=lines.get(i).split(",",-1);if(values.length!=names.length)throw new IllegalStateException("CSV width "+path);
            var row=new java.util.LinkedHashMap<String,String>();for(int j=0;j<names.length;j++)row.put(names[j],values[j]);rows.add(row);
        }
        return rows;
    }

    private static String interfaceEdgeKey(double x, double z, double tx, double tz) {
        long a=(long)Math.rint(x*4),b=(long)Math.rint(z*4),c=(long)Math.rint(tx*4),d=(long)Math.rint(tz*4);
        if(a!=x*4 || b!=z*4 || c!=tx*4 || d!=tz*4 || Math.abs(a-c)+Math.abs(b-d)!=1)
            throw new IllegalArgumentException("quarter world edge required");
        boolean forward=a<c || a==c && b<d;
        return forward?a+":"+b+":"+c+":"+d:c+":"+d+":"+a+":"+b;
    }

    private static String interfaceVertex(double x, double z) { return Double.toString(x)+":"+Double.toString(z); }

    private static List<String> interfaceRecipients(List<Map<String,String>> links, Map<String,java.util.Set<String>> wet,
                                                     Map<String,Double> levels) {
        var matches=new java.util.TreeSet<String>();
        for(var link:links)if(link.get("allKnown").equals("true"))for(var entry:wet.entrySet()) {
            String at=interfaceVertex(Double.parseDouble(link.get("targetX")),Double.parseDouble(link.get("targetZ")));
            if(entry.getValue().contains(at) && Double.parseDouble(link.get("linkMax"))<=levels.get(entry.getKey()))matches.add(entry.getKey());
        }
        return List.copyOf(matches);
    }

    private static Double interfaceDenseRise(List<Map<String,String>> profile) {
        Double rise=null;Map<String,String> previous=null;
        for(var row:profile) {
            if(previous!=null && row.get("segment").equals(previous.get("segment"))) {
                double delta=Double.parseDouble(row.get("height"))-Double.parseDouble(previous.get("height"));
                rise=rise==null?delta:Math.max(rise,delta);
            }
            previous=row;
        }
        return rise;
    }

    private static String interfaceChain(String start, Map<String,String> next) {
        var seen=new java.util.HashSet<String>();var parts=new ArrayList<String>();String at=start;
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("interface composition");
            parts.add(at);if(!seen.add(at)){parts.add("CYCLE_PENDING");break;}
            String target=next.get(at);if(target==null){parts.add("MISSING_INTERFACE_PENDING");break;}
            if(!next.containsKey(target)){parts.add(target);break;}at=target;
        }
        return String.join("|",parts);
    }

    private static void auditInterfaceLedger(Path dir) throws Exception {
        var wet=new java.util.TreeMap<String,java.util.Set<String>>();var levels=new java.util.TreeMap<String,Double>();
        var barriers=new java.util.TreeMap<String,Double>();var tags=new java.util.TreeMap<String,String>();
        try(var files=Files.list(dir)) {
            for(var file:files.filter(f->f.getFileName().toString().startsWith("basin-dense-summary-")).sorted().toList()) {
                var summary=interfaceCsv(file).get(0);String tag=summary.get("anchorX")+"_"+summary.get("anchorZ")+".csv";
                String id="LOCAL_"+summary.get("anchorX")+"_"+summary.get("anchorZ");tags.put(id,tag);
                levels.put(id,Double.parseDouble(summary.get("escapeLevel")));barriers.put(id,Double.parseDouble(summary.get("barrier")));
                var vertices=new java.util.HashSet<String>();for(var row:interfaceCsv(dir.resolve("basin-dense-grid-"+tag)))
                    if(row.get("wet").equals("true"))vertices.add(interfaceVertex(Double.parseDouble(row.get("x")),Double.parseDouble(row.get("z"))));
                wet.put(id,vertices);
            }
        }
        var observations=new ArrayList<InterfaceObservation>();var rows=new StringBuilder("component,worldEdgeQuarter,orientation,epsilon,side,recipients,terminal,barrier,originalLevel,maxDenseRise,localIdentityOnly,globalCertified\n");
        boolean valid=!tags.isEmpty();int expected=0;
        for(var entry:tags.entrySet()) {
            String id=entry.getKey(),tag=entry.getValue();var seen=new java.util.HashSet<String>();
            var cuts=interfaceCsv(dir.resolve("basin-dense-cuts-"+tag)).stream().filter(r->r.get("routeCompatible").equals("true")).toList();expected+=cuts.size()*4;
            var links=interfaceCsv(dir.resolve("interface-receiver-links-"+tag));var profiles=interfaceCsv(dir.resolve("interface-receiver-profiles-"+tag));
            for(var s:interfaceCsv(dir.resolve("interface-receiver-summary-"+tag))) {
                int port=Integer.parseInt(s.get("port")),side=Integer.parseInt(s.get("side"));double epsilon=Double.parseDouble(s.get("epsilon"));var c=cuts.get(port);
                double x=Double.parseDouble(c.get("x")),z=Double.parseDouble(c.get("z")),tx=Double.parseDouble(c.get("toX")),tz=Double.parseDouble(c.get("toZ"));
                String edge=interfaceEdgeKey(x,z,tx,tz);int orientation=x<tx || x==tx && z<tz?1:-1;
                var matching=links.stream().filter(r->Integer.parseInt(r.get("crest"))==port && Integer.parseInt(r.get("ray"))==side && Double.parseDouble(r.get("epsilon"))==epsilon).toList();
                var profile=profiles.stream().filter(r->Integer.parseInt(r.get("port"))==port && Integer.parseInt(r.get("side"))==side && Double.parseDouble(r.get("epsilon"))==epsilon).toList();
                var recipients=interfaceRecipients(matching,wet,levels);Double rise=interfaceDenseRise(profile);
                var observation=new InterfaceObservation(id,edge,orientation,epsilon,side,recipients,s.get("terminal"),barriers.get(id),rise);observations.add(observation);
                valid &= matching.size()==4 && s.get("globalCertified").equals("false") && (side==0 || side==1)
                        && (epsilon==.25 || epsilon==.0625) && seen.add(port+":"+side+":"+epsilon);
                rows.append(id).append(',').append(edge).append(',').append(orientation).append(',').append(epsilon).append(',').append(side).append(',')
                        .append(String.join("|",recipients)).append(',').append(s.get("terminal")).append(',').append(barriers.get(id)).append(',').append(levels.get(id)).append(',')
                        .append(rise==null?"":rise).append(",true,false\n");
            }
        }
        Files.writeString(dir.resolve("interface-ledger.csv"),rows);writeInterfaceComposition(dir,observations,barriers);
        chk(valid && observations.size()==expected,"固定世界边账本保留两侧两尺度、全部接收候选及原终端；身份仅局部", "wrong");
        log("G23 interfaces="+observations.size()/4+" observations="+observations.size()+" CSV_ONLY_NO_NEW_TERRAIN_SAMPLES=true LOCAL_COMPONENT_IDS_NOT_CANONICAL=true");
    }

    private static String interfaceRelation(String id, List<InterfaceObservation> inside, List<InterfaceObservation> outside,
                                            Map<String,Double> barriers) {
        if(inside.size()!=1 || outside.size()!=1)return "MULTIPLE_PORTS_PENDING";
        var a=inside.get(0);var b=outside.get(0);
        if(!a.component().equals(id) || !b.component().equals(id) || !a.edge().equals(b.edge())
                || a.orientation()!=b.orientation() || Math.abs(a.orientation())!=1 || a.side()!=0 || b.side()!=1
                || a.epsilon()!=b.epsilon() || !Double.isFinite(a.epsilon()) || a.epsilon()<=0
                || !Double.isFinite(a.barrier()) || a.barrier()!=b.barrier()
                || !barriers.containsKey(id) || a.barrier()!=barriers.get(id))return "INTERFACE_PAIR_MISMATCH_PENDING";
        if(!a.recipients().equals(List.of(id)))return "INSIDE_ASSOCIATION_PENDING";
        if(b.recipients().size()>1)return "MULTIPLE_RECIPIENTS_PENDING";
        if(b.recipients().isEmpty())return b.terminal();
        String recipient=b.recipients().get(0);
        if(!barriers.containsKey(recipient) || !Double.isFinite(barriers.get(recipient)))return "RECIPIENT_INPUT_PENDING";
        return a.barrier()>barriers.get(recipient)?recipient:"NONDECREASING_INTERFACE_PENDING";
    }

    private static void writeInterfaceComposition(Path dir, List<InterfaceObservation> observations, Map<String,Double> barriers) throws Exception {
        var csv=new StringBuilder("component,epsilon,insideRecipients,outsideRecipients,relation,barrierDrop,chain,scaleStableRelation,localOnly,globalCertified\n");
        var relations=new java.util.TreeMap<Double,Map<String,String>>();var drops=new java.util.HashMap<String,Double>();
        for(double epsilon:new double[]{.25,.0625}) {
            var next=new java.util.TreeMap<String,String>();relations.put(epsilon,next);
            for(String id:barriers.keySet()) {
                var inside=observations.stream().filter(o->o.component().equals(id) && o.epsilon()==epsilon && o.side()==0).toList();
                var outside=observations.stream().filter(o->o.component().equals(id) && o.epsilon()==epsilon && o.side()==1).toList();String target;
                target=interfaceRelation(id,inside,outside,barriers);
                if(outside.size()==1 && outside.get(0).recipients().size()==1 && target.equals(outside.get(0).recipients().get(0)))
                    drops.put(id+epsilon,barriers.get(id)-barriers.get(target));
                next.put(id,target);
            }
        }
        for(var group:relations.entrySet())for(var entry:group.getValue().entrySet()) {
            String id=entry.getKey();double epsilon=group.getKey();var in=new java.util.TreeSet<String>();var out=new java.util.TreeSet<String>();
            for(var o:observations)if(o.component().equals(id) && o.epsilon()==epsilon)(o.side()==0?in:out).addAll(o.recipients());
            boolean stable=entry.getValue().equals(relations.get(epsilon==.25?.0625:.25).get(id));
            csv.append(id).append(',').append(epsilon).append(',').append(String.join("|",in)).append(',').append(String.join("|",out)).append(',')
                    .append(entry.getValue()).append(',').append(drops.containsKey(id+epsilon)?drops.get(id+epsilon):"").append(',')
                    .append(interfaceChain(id,group.getValue())).append(',').append(stable).append(",true,false\n");
        }
        Files.writeString(dir.resolve("interface-composition.csv"),csv);
    }

    private static void interfaceLedgerSynthetic() {
        String edge=interfaceEdgeKey(-.5,-.25,-.25,-.25);
        chk(edge.equals(interfaceEdgeKey(-.25,-.25,-.5,-.25)) && !edge.equals(interfaceEdgeKey(-.25,-.25,-.25,0)),
                "固定四分格世界边负坐标反向同键、角点邻边不同键", "wrong");
        boolean rejected=false;try{interfaceEdgeKey(-.3,0,-.05,0);}catch(IllegalArgumentException ex){rejected=true;}
        chk(rejected,"未对齐边不得四舍五入冒充规范接口", "wrong");
        var link=Map.of("allKnown","true","targetX","-0.5","targetZ","-0.25","linkMax","1.0");
        var wet=Map.of("A",java.util.Set.of(interfaceVertex(-.5,-.25)),"B",java.util.Set.of(interfaceVertex(-.5,-.25)));
        chk(interfaceRecipients(List.of(link),wet,Map.of("A",1.0,"B",2.0)).equals(List.of("A","B")),"多接收候选全保留不按最近或首项选盆", "wrong");
        chk(interfaceRecipients(List.of(link),wet,Map.of("A",.9,"B",.9)).isEmpty()
                && interfaceRecipients(List.of(Map.of("allKnown","false")),wet,Map.of("A",2.0,"B",2.0)).isEmpty(),
                "短连接超平面或未知不制造接收关系", "wrong");
        chk(interfaceChain("B",Map.of("B","A","A","LOCAL_SEA_MARKER_NOT_GLOBAL_OCEAN")).equals("B|A|LOCAL_SEA_MARKER_NOT_GLOBAL_OCEAN")
                && interfaceChain("C",Map.of("C","UNKNOWN_OWNER_PENDING")).equals("C|UNKNOWN_OWNER_PENDING"),"组合保留局部海与未知，不升格全球终端", "wrong");
        chk(interfaceChain("A",Map.of("A","B","B","A")).endsWith("CYCLE_PENDING"),"接口组合回环显式失败而非已入海", "wrong");
        var inside=new InterfaceObservation("B",edge,1,.25,0,List.of("B"),"STOP",2,null);
        var outside=new InterfaceObservation("B",edge,1,.25,1,List.of("A"),"STOP",2,null);
        chk(interfaceRelation("B",List.of(inside),List.of(outside),Map.of("A",1.0,"B",2.0)).equals("A")
                && interfaceRelation("B",List.of(inside),List.of(outside),Map.of("A",2.0,"B",2.0)).equals("NONDECREASING_INTERFACE_PENDING"),
                "组合仅标记严格递减接口代价，等高不伪造进展", "wrong");
        chk(interfaceRelation("B",List.of(),List.of(outside),Map.of("A",1.0,"B",2.0)).equals("MULTIPLE_PORTS_PENDING")
                && interfaceRelation("B",List.of(inside,inside),List.of(outside),Map.of("A",1.0,"B",2.0)).equals("MULTIPLE_PORTS_PENDING"),
                "缺侧或多接口不默选首项", "wrong");
        chk(interfaceDenseRise(List.of())==null,"零线段的加密回升保持缺测而非零", "wrong");
        boolean cancelled=false;try{Thread.currentThread().interrupt();interfaceChain("A",Map.of("A","B"));}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && interfaceChain("A",Map.of("A","UNKNOWN")).equals("A|UNKNOWN"),"接口组合预取消重试同值", "wrong");
        interfacePairSynthetic(inside,outside);
    }

    private static void interfacePairSynthetic(InterfaceObservation inside,InterfaceObservation outside) {
        var invalid=List.of(
                new InterfaceObservation("B",interfaceEdgeKey(-.25,-.25,-.25,0),1,.25,1,List.of("A"),"STOP",2,null),
                new InterfaceObservation("B",outside.edge(),-1,.25,1,List.of("A"),"STOP",2,null),
                new InterfaceObservation("B",outside.edge(),1,.0625,1,List.of("A"),"STOP",2,null),
                new InterfaceObservation("C",outside.edge(),1,.25,1,List.of("A"),"STOP",2,null),
                new InterfaceObservation("B",outside.edge(),1,.25,0,List.of("A"),"STOP",2,null),
                new InterfaceObservation("B",outside.edge(),1,.25,1,List.of("A"),"STOP",3,null));
        String[] cases={"different-world-edge","opposite-orientation","different-scale","foreign-component","same-side","different-barrier"};
        for(int i=0;i<invalid.size();i++)chk(interfaceRelation("B",List.of(inside),List.of(invalid.get(i)),Map.of("A",1.0,"B",2.0)).equals("INTERFACE_PAIR_MISMATCH_PENDING"),
                "接口两侧必须同世界边/方向/尺度/分量/代价 case="+cases[i],"mismatched pair accepted");
        var missing=new InterfaceObservation("B",outside.edge(),1,.25,1,List.of("MISSING"),"STOP",2,null);
        boolean pending=false;try {pending=interfaceRelation("B",List.of(inside),List.of(missing),Map.of("B",2.0)).equals("RECIPIENT_INPUT_PENDING");}
        catch(NullPointerException ex){/* A missing recipient must not crash or be treated as a zero barrier. */}
        chk(pending,"缺接收分量保持INPUT_PENDING，不空指针或假定零代价","missing recipient crashed/promoted");
    }

    private static int firstLocalCapture(int start, int[] parent, int[] component, boolean[] resolved, boolean[] sea) {
        var seen=new java.util.BitSet(parent.length);int k=start;
        while(k>=0 && k<parent.length) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("local water capture");
            if(!resolved[k])return -2;if(sea[k])return -3;
            if(component[k]>=0)return component[k];if(seen.get(k))return -4;seen.set(k);k=parent[k];
        }
        return -1;
    }

    private static void auditLocalWaterAccounting(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components) throws Exception {
        var p=grid.pipe();int n=p.bed().length;int[] owner=new int[n];java.util.Arrays.fill(owner,-1);
        components.forEach((anchor,cells)->cells.forEach(k->owner[k]=anchor));int[] captures=new int[n];
        double runoff=p.lakeResult().runoffPerCell(),cellArea=p.cellBlocks()*(double)p.cellBlocks();
        var climate=new LakeWaterBalance.Climate(.004,.002,.3);boolean valid=runoff==climate.precipitation()*climate.runoffCoeff()*cellArea;
        var census=new StringBuilder("cell,x,z,component,firstCapture,frame,resolved,sea,localRunoff,discharge\n");
        for(int k=0;k<n;k++) {
            captures[k]=firstLocalCapture(k,p.parent(),owner,p.resolved(),p.sea());var w=grid.world(k);
            boolean frame=k/p.nz()==0 || k%p.nz()==0 || k/p.nz()==p.nx()-1 || k%p.nz()==p.nz()-1;
            census.append(k).append(',').append(w.x()).append(',').append(w.z()).append(',').append(owner[k]).append(',').append(captures[k])
                    .append(',').append(frame).append(',').append(p.resolved()[k]).append(',').append(p.sea()[k]).append(',').append(runoff).append(',').append(p.discharge()[k]).append('\n');
        }
        var summary=new StringBuilder("anchorX,anchorZ,componentCells,unknownMembers,frameMembers,productionBasinIds,firstCaptureCells,frameFirstCaptureCells,localRunoffPerCell,ownRainRunoff,firstCaptureLocalRunoff,boundaryIn,boundaryOut,terminalFlux,conservationResidual,sumMemberDischarge,evaporationAtCoarseFootprint,rawSurplusProxy,precipitation,evaporation,runoffCoeff,globalCertified\n");
        var edges=new StringBuilder("anchorX,anchorZ,kind,from,to,fromX,fromZ,toX,toZ,discharge,fromComponent,toComponent,fromResolved,toResolved\n");
        for(var entry:components.entrySet()) {
            int anchor=entry.getKey(),unknown=0,frameMembers=0,count=0,frameCount=0;var ids=new java.util.TreeSet<Integer>();
            double in=0,out=0,terminal=0,sum=0;var w=grid.world(anchor);
            for(int k=0;k<n;k++) {
                int next=p.parent()[k];boolean member=owner[k]==anchor,downstream=next>=0 && next<n && owner[next]==anchor;
                if(captures[k]==anchor){count++;if(k/p.nz()==0 || k%p.nz()==0 || k/p.nz()==p.nx()-1 || k%p.nz()==p.nz()-1)frameCount++;}
                if(member){sum+=p.discharge()[k];ids.add(LakeWaterBalance.basinOf(p.lakeResult(),k));if(!p.resolved()[k])unknown++;
                    if(k/p.nz()==0 || k%p.nz()==0 || k/p.nz()==p.nx()-1 || k%p.nz()==p.nz()-1)frameMembers++;
                    if(next<0 || next>=n)terminal+=p.discharge()[k];}
                if(member==downstream || next<0 || next>=n)continue;
                String kind=member?"RAW_EXIT":"RAW_ENTRY";if(member)out+=p.discharge()[k];else in+=p.discharge()[k];
                var a=grid.world(k);var b=grid.world(next);edges.append(w.x()).append(',').append(w.z()).append(',').append(kind).append(',').append(k).append(',').append(next)
                        .append(',').append(a.x()).append(',').append(a.z()).append(',').append(b.x()).append(',').append(b.z()).append(',').append(p.discharge()[k])
                        .append(',').append(owner[k]).append(',').append(owner[next]).append(',').append(p.resolved()[k]).append(',').append(p.resolved()[next]).append('\n');
            }
            double own=entry.getValue().size()*runoff,residual=own+in-out-terminal,evap=entry.getValue().size()*cellArea*climate.evaporation();
            if(ids.equals(java.util.Set.of(-1)))valid &= Math.abs(residual)<1e-8;
            summary.append(w.x()).append(',').append(w.z()).append(',').append(entry.getValue().size()).append(',').append(unknown).append(',').append(frameMembers)
                    .append(',').append(ids.toString().replace(", ","|")).append(',').append(count).append(',').append(frameCount).append(',').append(runoff).append(',').append(own)
                    .append(',').append(count*runoff).append(',').append(in).append(',').append(out).append(',').append(terminal).append(',').append(residual).append(',').append(sum)
                    .append(',').append(evap).append(',').append(own+in-evap).append(',').append(climate.precipitation()).append(',').append(climate.evaporation()).append(',').append(climate.runoffCoeff()).append(",false\n");
        }
        Files.writeString(dir.resolve("local-water-cells.csv"),census);Files.writeString(dir.resolve("local-water-summary.csv"),summary);
        Files.writeString(dir.resolve("local-water-boundary.csv"),edges);writeSourceIdentityAccounting(dir,grid,captures);
        auditConditionalCascade(dir,grid,components,owner,captures);
        chk(valid,"原parent局部分量逐边通量守恒；无生产盆地身份不把过流当出流", "wrong");
        log("G25 cells="+n+" components="+components.size()+" FIXED_CLIMATE_AND_GRID=true RAW_D8_ACCOUNTING_NOT_CONTINUOUS_CATCHMENT=true NO_NEW_TERRAIN_OR_PIPELINE=true");
    }

    private static void writeSourceIdentityAccounting(Path dir, WindowGrid grid, int[] captures) throws Exception {
        var supported=new java.util.TreeMap<String,java.util.Set<String>>();var counts=new java.util.HashMap<String,Integer>();
        for(var r:interfaceCsv(dir.resolve("internal-transfer-summary.csv")))if(r.get("input").startsWith("CONTACT:") && r.get("status").startsWith("SAMPLED_TRANSFER")) {
            var parts=r.get("input").split(":");String source=parts[1]+":"+parts[2],key=r.get("component")+":"+source;
            supported.computeIfAbsent(source,k->new java.util.TreeSet<>()).add(r.get("component"));counts.merge(key,1,Integer::sum);
        }
        var csv=new StringBuilder("sourceX,sourceZ,sourceInside,sourceCell,sourceDischarge,rawFirstCapture,continuousRecipients,validShortLinkRows,originalOwnerEvents,candidateTerminal,globalCertified\n");
        for(var r:interfaceCsv(dir.resolve("continuous-source-summary.csv"))) {
            int x=Integer.parseInt(r.get("sourceX")),z=Integer.parseInt(r.get("sourceZ"));String id=x+":"+z;
            int k=grid.index(new SharedBoundaryDrainage.WorldCell(x,z));var recipients=supported.getOrDefault(id,java.util.Set.of());int rows=0;
            for(String recipient:recipients)rows+=counts.get(recipient+":"+id);
            csv.append(x).append(',').append(z).append(',').append(k>=0).append(',').append(k).append(',').append(k<0?"":grid.pipe().discharge()[k])
                    .append(',').append(k<0?"SOURCE_OUTSIDE_PENDING":captures[k]).append(',').append(String.join("|",recipients)).append(',').append(rows)
                    .append(',').append(r.get("originalOwnerEvents")).append(',').append(r.get("candidateTerminal")).append(",false\n");
        }
        Files.writeString(dir.resolve("local-water-source-identities.csv"),csv);
    }

    private static void localWaterSynthetic() {
        int[] parent={1,2,3,-1},owner={-1,7,7,-1};boolean[] resolved={true,true,true,true},sea=new boolean[4];
        var river=RiverNetwork.build(1,4,new double[]{3,2,1,0},parent,1,Map.of(),resolved,RiverNetwork.Params.defaults());
        double own=2,in=river.discharge()[0],out=river.discharge()[2],sum=river.discharge()[1]+river.discharge()[2];
        chk(own+in==out && sum==5 && out==3,"过流每格求和重复计水：两格分量应3而非5，逐边账本守恒", "wrong");
        chk(firstLocalCapture(0,parent,owner,resolved,sea)==7 && firstLocalCapture(3,parent,owner,resolved,sea)==-1,
                "每格本地雨源首次截获唯一归属，不按经过格数或多个接触点注水", "wrong");
        int[] chain={1,2,3,-1},groups={-1,7,9,-1};
        chk(firstLocalCapture(0,chain,groups,resolved,sea)==7 && firstLocalCapture(2,chain,groups,resolved,sea)==9,
                "串联分量的上游本地源不重复归入两个盆地", "wrong");
        resolved[1]=false;chk(firstLocalCapture(0,parent,owner,resolved,sea)==-2,"未知优先于分量标签，未采集不是零来水", "wrong");resolved[1]=true;
        chk(firstLocalCapture(0,new int[]{1,0,-1,-1},new int[]{-1,-1,-1,-1},resolved,sea)==-4,"原parent环显式未决不制造预算", "wrong");
        boolean cancel=false;try{Thread.currentThread().interrupt();firstLocalCapture(0,parent,owner,resolved,sea);}
        catch(java.util.concurrent.CancellationException ex){cancel=true;}finally{Thread.interrupted();}
        chk(cancel && firstLocalCapture(0,parent,owner,resolved,sea)==7,"首次截获预取消重试同值", "wrong");
    }

    private static SharedBlockDrainage.BasinRelations rawBudgetGraph(WindowGrid grid, Map<Integer,List<Integer>> components,
                                                                    int[] owner, int[] captures) {
        var basins=new ArrayList<SharedBlockDrainage.LocalBasin>();var relations=new ArrayList<SharedBlockDrainage.BasinRelation>();
        var p=grid.pipe();
        for(var entry:components.entrySet()) {
            int anchor=entry.getKey();var sink=grid.world(anchor);var catchment=new ArrayList<SharedBoundaryDrainage.WorldCell>();
            var storage=new ArrayList<SharedBoundaryDrainage.WorldCell>();boolean pending=false;
            for(int k=0;k<captures.length;k++)if(captures[k]==anchor)catchment.add(grid.world(k));
            for(int k:entry.getValue()){storage.add(grid.world(k));pending |= !p.resolved()[k]
                    || k/p.nz()==0 || k%p.nz()==0 || k/p.nz()==p.nx()-1 || k%p.nz()==p.nz()-1;}
            var targets=new java.util.TreeSet<Integer>();int exits=0;
            for(int k:entry.getValue()) {
                int next=p.parent()[k];if(next>=0 && next<owner.length && owner[next]==anchor)continue;exits++;
                targets.add(next<0 || next>=owner.length?-1:captures[next]);
            }
            SharedBlockDrainage.RelationEnd state;SharedBoundaryDrainage.WorldCell target=sink;
            if(pending)state=SharedBlockDrainage.RelationEnd.UNKNOWN_FRAME;
            else if(targets.size()!=1)state=SharedBlockDrainage.RelationEnd.AMBIGUOUS_SHORE;
            else if(targets.first()>=0) {
                target=grid.world(targets.first());state=targets.first()==anchor?SharedBlockDrainage.RelationEnd.SELF_RETURN
                        :p.level()[anchor]>p.level()[targets.first()]?SharedBlockDrainage.RelationEnd.NEXT_BASIN:SharedBlockDrainage.RelationEnd.NONDESCENDING;
            } else state=targets.first()==-3?SharedBlockDrainage.RelationEnd.OCEAN_CANDIDATE:SharedBlockDrainage.RelationEnd.OUTSIDE_SET;
            basins.add(new SharedBlockDrainage.LocalBasin(pending?SharedBlockDrainage.BasinState.UNKNOWN_FRAME:SharedBlockDrainage.BasinState.SHORE_CANDIDATE,
                    sink,catchment,storage,Double.NaN,List.of()));
            relations.add(new SharedBlockDrainage.BasinRelation(sink,target,state,p.level()[anchor],exits,catchment.size()));
        }
        return new SharedBlockDrainage.BasinRelations(basins,relations,(int)java.util.Arrays.stream(captures).filter(k->k>=0).count(),0);
    }

    private static void auditConditionalCascade(Path dir, WindowGrid grid, Map<Integer,List<Integer>> components,
                                                 int[] owner, int[] captures) throws Exception {
        var graph=rawBudgetGraph(grid,components,owner,captures);var p=grid.pipe();double area=p.cellBlocks()*(double)p.cellBlocks();
        var contracts=new StringBuilder("anchorX,anchorZ,ownRainCells,storageCells,relation,targetX,targetZ,exitCount,rawLevel,footprintPending,disjointSources,globalCertified\n");
        var unique=new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();boolean valid=true;
        for(int i=0;i<graph.basins().size();i++) {
            var b=graph.basins().get(i);var r=graph.relations().get(i);for(var source:b.catchment())valid &= unique.add(source);
            if(b.state()==SharedBlockDrainage.BasinState.SHORE_CANDIDATE)valid &= b.catchment().containsAll(b.storage());
            contracts.append(b.sink().x()).append(',').append(b.sink().z()).append(',').append(b.catchment().size()).append(',').append(b.storage().size())
                    .append(',').append(r.state()).append(',').append(r.target().x()).append(',').append(r.target().z()).append(',').append(r.shoreCount()).append(',').append(r.sill())
                    .append(',').append(b.state()!=SharedBlockDrainage.BasinState.SHORE_CANDIDATE).append(",true,false\n");
        }
        var rows=new StringBuilder("rainModel,externalMode,anchorX,anchorZ,routeState,externalLower,externalUpper,landRunoff,lakeRain,evaporation,upstreamLower,upstreamUpper,fullReleaseLower,fullReleaseUpper,rangeState,unreadyLower,unreadyUpper,globalCertified\n");
        for(String model:new String[]{"PRODUCTION_UNIFORM_RUNOFF","PROTOTYPE_LAKE_DIRECT_RAIN"}) {
            double coefficient=model.startsWith("PRODUCTION")?1:.3,precipitation=model.startsWith("PRODUCTION")?.004*.3:.004;
            var climate=new LakeWaterBalance.Climate(precipitation*area,.002*area,coefficient);
            for(String external:new String[]{"ZERO_BOUNDARY_COUNTERFACTUAL","NONNEGATIVE_UNKNOWN_INPUT"}) {
                var result=SharedBlockDrainage.fullBudgetRanges(graph,climate,sink->new SharedBlockDrainage.RateRange(0,external.startsWith("ZERO")?0:Double.POSITIVE_INFINITY));
                for(int i=0;i<result.basins().size();i++) {
                    var b=result.basins().get(i);var relation=graph.relations().get(i);
                    rows.append(model).append(',').append(external).append(',').append(b.sink().x()).append(',').append(b.sink().z()).append(',').append(relation.state())
                            .append(',').append(b.external().lower()).append(',').append(b.external().upper()).append(',').append(b.landRunoff()).append(',').append(b.lakeRain()).append(',').append(b.fullEvaporation())
                            .append(',').append(b.upstream().lower()).append(',').append(b.upstream().upper()).append(',').append(b.conditionalRelease().lower()).append(',').append(b.conditionalRelease().upper())
                            .append(',').append(b.state()).append(",0.0,Infinity,false\n");
                }
            }
        }
        Files.writeString(dir.resolve("conditional-cascade-contract.csv"),contracts);Files.writeString(dir.resolve("conditional-cascade-budget.csv"),rows);
        chk(valid && unique.size()==graph.assignedLocalCells(),"原图首触雨源跨盆去重，既有满盆区间预算只消费同图关系；未就绪下界为零", "wrong");
        log("G26 basins="+graph.basins().size()+" uniqueRainCells="+unique.size()+" EXISTING_BUDGET_RANGE_REUSED=true UNIT_CELL_AREA_SCALED=true FULL_CONDITIONAL_NOT_READY=true RAW_GRAPH_NOT_CONTINUOUS_BUDGET=true");
    }

    private static void conditionalCascadeSynthetic(WindowGrid grid) {
        var a=new SharedBoundaryDrainage.WorldCell(-4,0);var b=new SharedBoundaryDrainage.WorldCell(0,0);
        var first=new SharedBlockDrainage.LocalBasin(SharedBlockDrainage.BasinState.SHORE_CANDIDATE,a,List.of(a,new SharedBoundaryDrainage.WorldCell(-8,0)),List.of(a),0,List.of());
        var second=new SharedBlockDrainage.LocalBasin(SharedBlockDrainage.BasinState.SHORE_CANDIDATE,b,List.of(b),List.of(b),0,List.of());
        var graph=new SharedBlockDrainage.BasinRelations(List.of(first,second),List.of(
                new SharedBlockDrainage.BasinRelation(a,b,SharedBlockDrainage.RelationEnd.NEXT_BASIN,2,1,2),
                new SharedBlockDrainage.BasinRelation(b,b,SharedBlockDrainage.RelationEnd.OCEAN_CANDIDATE,1,1,1)),3,0);
        var result=SharedBlockDrainage.fullBudgetRanges(graph,new LakeWaterBalance.Climate(1,.25,1),sink->new SharedBlockDrainage.RateRange(0,0));
        chk(result.basins().get(0).conditionalRelease().lower()==1.75 && result.basins().get(1).upstream().lower()==1.75
                && result.basins().get(1).conditionalRelease().lower()==2.5,"复用区间预算：上游先扣损失后下游只接余量，不搬运原图总Q", "wrong");
        var unknown=SharedBlockDrainage.fullBudgetRanges(graph,new LakeWaterBalance.Climate(1,.25,1),sink->new SharedBlockDrainage.RateRange(0,Double.POSITIVE_INFINITY));
        chk(unknown.basins().get(1).conditionalRelease().upper()==Double.POSITIVE_INFINITY && unknown.basins().get(1).conditionalRelease().lower()==2.5,
                "未知边界保留无穷上界，正满盆下界不是已就绪下界", "wrong");
        var scaled=SharedBlockDrainage.fullBudgetRanges(graph,new LakeWaterBalance.Climate(16,4,1),sink->new SharedBlockDrainage.RateRange(0,0));
        chk(scaled.basins().get(1).conditionalRelease().lower()==40,"step4面积16只换一次单位，不把格数当方块面积", "wrong");
        var direct=SharedBlockDrainage.fullBudgetRanges(graph,new LakeWaterBalance.Climate(1,.25,.3),sink->new SharedBlockDrainage.RateRange(0,0));
        chk(direct.basins().get(0).landRunoff()==.3 && direct.basins().get(0).lakeRain()==1,"湖面直接降雨替换陆地径流，雨量口径必须分列", "wrong");
        int at=4*grid.pipe().nz()+4;int[] owner=new int[grid.pipe().bed().length],captures=new int[owner.length];
        java.util.Arrays.fill(owner,-1);java.util.Arrays.fill(captures,-1);owner[at]=at;captures[at]=at;
        var single=Map.of(at,List.of(at));var known=rawBudgetGraph(grid,single,owner,captures);
        chk(known.relations().get(0).state()==SharedBlockDrainage.RelationEnd.OUTSIDE_SET,"无接收盆地的原图出口不能升级海候选", "wrong");
        boolean saved=grid.pipe().resolved()[at];try{grid.pipe().resolved()[at]=false;
            chk(rawBudgetGraph(grid,single,owner,captures).basins().get(0).state()==SharedBlockDrainage.BasinState.UNKNOWN_FRAME,
                    "未解析存水足迹不计算负陆地面积预算", "wrong");
        }finally{grid.pipe().resolved()[at]=saved;}
        var reversed=new SharedBlockDrainage.BasinRelations(List.of(second,first),List.of(graph.relations().get(1),graph.relations().get(0)),3,0);
        var back=SharedBlockDrainage.fullBudgetRanges(reversed,new LakeWaterBalance.Climate(1,.25,1),sink->new SharedBlockDrainage.RateRange(0,0));
        chk(back.basins().get(0).equals(result.basins().get(1)) && back.basins().get(1).equals(result.basins().get(0)),"预算盆列表逆序不改上游扣损后组合", "wrong");
        double steady=2-.25,withStorageRelease=2-.25-(-3);
        chk(withStorageRelease>steady,"存量释放反例：满盆稳态余量不是未知初始存量下的动态流量上界", "wrong");
    }

    private record StorageMeasure(int nodes, double area, double volume, double maxDepth, int frameNodes, int excludedEdges) { }

    private static StorageMeasure storageMeasure(SubcellPatch p, int[] known, java.util.BitSet connected, double level) {
        double area=0,volume=0,deepest=0;int frame=0,unknown=0;
        for(int k=connected.nextSetBit(0);k>=0;k=connected.nextSetBit(k+1)) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("storage measure");
            int x=k/p.nz(),z=k%p.nz();boolean bx=x==0 || x==p.nx()-1,bz=z==0 || z==p.nz()-1;
            double weight=p.step()*p.step()*(bx?.5:1)*(bz?.5:1),depth=Math.max(0,level-p.height()[k]);
            area+=weight;volume+=weight*depth;deepest=Math.max(deepest,depth);if(bx || bz)frame++;
            for(int next:new int[]{x>0?k-p.nz():-1,x+1<p.nx()?k+p.nz():-1,z>0?k-1:-1,z+1<p.nz()?k+1:-1})
                if(next>=0 && known[next]<0)unknown++;
        }
        return new StorageMeasure(connected.cardinality(),area,volume,deepest,frame,unknown);
    }

    private static void replayStorage(Path evidence) throws Exception {
        evidence=evidence.toAbsolutePath().normalize();var dir=OUT.resolve("storage-replay-"+System.currentTimeMillis());Files.createDirectories(dir);
        Files.writeString(dir.resolve("evidence-path.txt"),evidence.toString()+"\nREAD_ONLY_EXPLICIT_G21_SNAPSHOT_NOT_A_FRESH_TERRAIN_RUN\n");
        var rows=new StringBuilder("anchorX,anchorZ,kind,level,originalLevel,barrier,connectedNodes,dualArea,dualVolume,maxDepth,frameNodes,excludedBoundaryEdges,areaDerivativeFromPrevious,volumeJumpResidual,globalCertified\n");
        var membership=new StringBuilder("anchorX,anchorZ,node,x,z,areaWeight,height,originalWet,precritical,atCritical\n");
        List<Path> files;try(var stream=Files.list(evidence)){files=stream.filter(f->f.getFileName().toString().startsWith("basin-dense-summary-")).sorted().toList();}
        boolean valid=files.size()>0;
        for(var file:files) {
            var s=interfaceCsv(file).get(0);String tag=s.get("anchorX")+"_"+s.get("anchorZ")+".csv";
            var g=readTransferGrid(evidence,tag);var p=g.patch();var gridRows=interfaceCsv(evidence.resolve("basin-dense-grid-"+tag));int[] known=new int[p.height().length];
            for(int k=0;k<known.length;k++)known[k]=gridRows.get(k).get("known").equals("true")?0:-1;
            double low=p.height()[p.start()],before=Math.nextDown(g.barrier());var levels=new java.util.TreeMap<Double,String>();
            for(int i=0;i<=24;i++)levels.put(low+(before-low)*i/24,"BOTTOM_TO_PRECRITICAL");
            levels.put(g.level(),"ORIGINAL_PLANE");levels.put(before,"PRECRITICAL_NEXT_DOWN");levels.put(g.barrier(),"AT_CRITICAL_LEAK");
            StorageMeasure previous=null;double previousLevel=Double.NaN;var fixed=freeReachable(p,known,p.start(),before);var original=freeReachable(p,known,p.start(),g.level());
            var critical=freeReachable(p,known,p.start(),g.barrier());
            for(int k=fixed.nextSetBit(0);k>=0;k=fixed.nextSetBit(k+1)) {
                int x=k/p.nz(),z=k%p.nz();double weight=p.step()*p.step()*(x==0 || x==p.nx()-1?.5:1)*(z==0 || z==p.nz()-1?.5:1);
                membership.append(s.get("anchorX")).append(',').append(s.get("anchorZ")).append(',').append(k).append(',').append(p.ox()+x*p.step()).append(',').append(p.oz()+z*p.step())
                        .append(',').append(weight).append(',').append(p.height()[k]).append(',').append(original.get(k)).append(",true,").append(critical.get(k)).append('\n');
            }
            for(var entry:levels.entrySet()) {
                double h=entry.getKey();var connected=freeReachable(p,known,p.start(),h);var measured=storageMeasure(p,known,connected,h);
                double derivative=previous==null?Double.NaN:(measured.volume()-previous.volume())/(h-previousLevel);
                double residual=previous==null?Double.NaN:measured.volume()-previous.volume()-previous.area()*(h-previousLevel);
                valid &= previous==null || measured.area()>=previous.area() && measured.volume()>=previous.volume();
                if(h<g.barrier())valid &= measured.frameNodes()==0 && measured.excludedEdges()==0;
                if(h==g.barrier())valid &= measured.frameNodes()>0 || measured.excludedEdges()>0;
                rows.append(s.get("anchorX")).append(',').append(s.get("anchorZ")).append(',').append(entry.getValue()).append(',').append(h).append(',').append(g.level()).append(',').append(g.barrier())
                        .append(',').append(measured.nodes()).append(',').append(measured.area()).append(',').append(measured.volume()).append(',').append(measured.maxDepth())
                        .append(',').append(measured.frameNodes()).append(',').append(measured.excludedEdges()).append(',').append(Double.isFinite(derivative)?derivative:"")
                        .append(',').append(Double.isFinite(residual)?residual:"").append(",false\n");previous=measured;previousLevel=h;
            }
            valid &= original.equals(g.wet());
        }
        Files.writeString(dir.resolve("storage-curves.csv"),rows);Files.writeString(dir.resolve("storage-precritical-members.csv"),membership);
        chk(valid,"同G21算子储水曲线：临界前不触矩形/掩码边，临界触计算外缘不得当封闭容量", "wrong");
        log("G27 graphs="+files.size()+" NO_NEW_TERRAIN_OR_PIPELINE=true DUAL_NODE_QUADRATURE_NOT_EXACT_PHYSICAL_VOLUME=true STORAGE_REPLAY_DIR="+dir.toAbsolutePath());
    }

    private static void storageSynthetic() {
        var p=samplePortPatch((x,z)->0,0,0,4,4,1,new DryPoint(0,0,0),new DryPoint(4,4,0));int[] known=new int[p.height().length];
        var all=new java.util.BitSet(known.length);all.set(0,known.length);var m=storageMeasure(p,known,all,2);
        chk(m.area()==16 && m.volume()==32 && m.frameNodes()==16,"双对偶节点权重矩形积分：边半角四分，25节点不误当25面积", "wrong");
        var plane=samplePortPatch((x,z)->x,0,0,4,4,1,new DryPoint(0,0,0),new DryPoint(4,4,0));
        chk(storageMeasure(plane,known,all,4).volume()==32,"平面梯形积分解析体积正确，面积因子只用一次", "wrong");
        var wall=samplePortPatch((x,z)->x==2?3:0,0,0,4,4,1,new DryPoint(0,2,0),new DryPoint(4,2,0));
        var pre=freeReachable(wall,known,wall.start(),Math.nextDown(3.0));var at=freeReachable(wall,known,wall.start(),3);
        chk(pre.cardinality()==10 && at.cardinality()==25 && storageMeasure(wall,known,at,3).volume()==36
                        && Math.abs(storageMeasure(wall,known,pre,Math.nextDown(3.0)).volume()-18)<1e-12,
                "临界连接外部低地会跳增容积，不能把泄漏后集合称湖容量", "wrong");
        boolean cancel=false;try{Thread.currentThread().interrupt();storageMeasure(p,known,all,2);}
        catch(java.util.concurrent.CancellationException ex){cancel=true;}finally{Thread.interrupted();}
        chk(cancel && storageMeasure(p,known,all,2).equals(m),"储水积分预取消重试完整同值", "wrong");
    }

    private record TransferGrid(SubcellPatch patch, int[] mask, java.util.BitSet wet, Map<String,Integer> vertices,
                                double level, double barrier, List<Map<String,String>> ports) { }

    private static TransferGrid readTransferGrid(Path dir, String tag) throws Exception {
        var s=interfaceCsv(dir.resolve("basin-dense-summary-"+tag)).get(0);var rows=interfaceCsv(dir.resolve("basin-dense-grid-"+tag));
        int nx=Integer.parseInt(s.get("nx")),nz=Integer.parseInt(s.get("nz")),n=nx*nz;double step=Double.parseDouble(s.get("step"));
        double[] height=new double[n],east=new double[n],south=new double[n];int[] mask=new int[n];var wet=new java.util.BitSet(n);
        var vertices=new java.util.HashMap<String,Integer>();double ox=Double.parseDouble(rows.get(0).get("x")),oz=Double.parseDouble(rows.get(0).get("z"));
        for(var row:rows) {
            int k=Integer.parseInt(row.get("node"));height[k]=Double.parseDouble(row.get("height"));east[k]=Double.parseDouble(row.get("east"));south[k]=Double.parseDouble(row.get("south"));
            mask[k]=row.get("known").equals("true") && row.get("precritical").equals("true")?0:-1;
            if(row.get("wet").equals("true"))wet.set(k);
            vertices.put(interfaceVertex(Double.parseDouble(row.get("x")),Double.parseDouble(row.get("z"))),k);
        }
        var p=new SubcellPatch(ox,oz,step,nx,nz,height,east,south,Integer.parseInt(s.get("bottom")),Integer.parseInt(s.get("end")));
        var ports=interfaceCsv(dir.resolve("basin-dense-cuts-"+tag)).stream().filter(r->r.get("routeCompatible").equals("true")).toList();
        return new TransferGrid(p,mask,wet,vertices,Double.parseDouble(s.get("escapeLevel")),Double.parseDouble(s.get("barrier")),ports);
    }

    private static List<Map<String,String>> transferAttachments(Path dir, WindowGrid grid) throws Exception {
        var result=new ArrayList<Map<String,String>>();
        for(var r:interfaceCsv(dir.resolve("continuous-contact-links.csv"))) {
            String id="CONTACT:"+r.get("sourceX")+":"+r.get("sourceZ")+":"+r.get("anchorX")+":"+r.get("anchorZ")+":"+r.get("step")+":"+r.get("kind");
            double x=Double.parseDouble(r.get("pointX")),z=Double.parseDouble(r.get("pointZ")),tx=Double.parseDouble(r.get("targetX")),tz=Double.parseDouble(r.get("targetZ"));
            boolean known=true;for(int t=0;t<=32;t++)known &= crestContext(grid,x+(tx-x)*t/32,z+(tz-z)*t/32).equals("KNOWN");
            result.add(Map.of("id",id,"x",r.get("pointX"),"z",r.get("pointZ"),"targetX",r.get("targetX"),"targetZ",r.get("targetZ"),
                    "linkMax",r.get("linkMax"),"known",Boolean.toString(known)));
        }
        try(var files=Files.list(dir)) {
            for(var file:files.filter(f->f.getFileName().toString().startsWith("interface-receiver-links-")).sorted().toList()) {
                String tag=file.getFileName().toString().substring("interface-receiver-links-".length());
                for(var r:interfaceCsv(file))result.add(Map.of("id","INTERFACE:"+tag+":"+r.get("crest")+":"+r.get("ray")+":"+r.get("epsilon"),
                        "x",r.get("x"),"z",r.get("z"),"targetX",r.get("targetX"),"targetZ",r.get("targetZ"),"linkMax",r.get("linkMax"),"known",r.get("allKnown")));
            }
        }
        return List.copyOf(result);
    }

    private static String transferStatus(TransferGrid g, Map<String,String> r, int node, double cost) {
        if(!r.get("known").equals("true"))return "SHORT_LINK_UNKNOWN";
        if(node<0 || !g.wet().get(node))return "NOT_ORIGINAL_WET_SET";
        if(Double.parseDouble(r.get("linkMax"))>g.level())return "SHORT_LINK_ABOVE_ORIGINAL_PLANE";
        if(!Double.isFinite(cost))return "NO_PRECRITICAL_TRANSFER";
        return "SAMPLED_TRANSFER_REQUIRES_STORAGE_NOT_FLOW_CERTIFICATE";
    }

    private static void auditInternalTransfers(Path dir, WindowGrid grid, WindowOutlets.Terrain terrain) throws Exception {
        var attachments=transferAttachments(dir,grid);var summary=new StringBuilder("caseId,component,input,pointX,pointZ,targetX,targetZ,targetNode,port,known,wet,linkMax,originalLevel,internalCost,composedBarrier,interfaceBarrier,atOriginalPlane,status,pathNodes,denseMax,denseRise,globalCertified\n");
        var points=new StringBuilder("caseId,node,x,z,height\n");var profile=new StringBuilder("caseId,segment,sample,x,z,height\n");int cases=0,accepted=0;boolean valid=true;
        List<Path> files;try(var stream=Files.list(dir)){files=stream.filter(f->f.getFileName().toString().startsWith("basin-dense-summary-")).sorted().toList();}
        for(var file:files) {
            String tag=file.getFileName().toString().substring("basin-dense-summary-".length()),component="LOCAL_"+tag.replace(".csv","");var g=readTransferGrid(dir,tag);var p=g.patch();
            int portId=0;for(var port:g.ports()) {
                int exit=Integer.parseInt(port.get("from"));var flood=ringFlood(p,g.mask(),0,List.of(exit));
                for(var r:attachments) {
                    int k=g.vertices().getOrDefault(interfaceVertex(Double.parseDouble(r.get("targetX")),Double.parseDouble(r.get("targetZ"))),-1);
                    double link=Double.parseDouble(r.get("linkMax")),internal=k<0?Double.POSITIVE_INFINITY:Math.max(link,flood.cost()[k]);
                    String status=transferStatus(g,r,k,internal);boolean eligible=status.startsWith("SAMPLED_TRANSFER");
                    var path=new ArrayList<DryPoint>();Double max=null,rise=null;
                    if(eligible) {
                        path.add(new DryPoint(Double.parseDouble(r.get("x")),Double.parseDouble(r.get("z")),terrain.height(Double.parseDouble(r.get("x")),Double.parseDouble(r.get("z")))));
                        for(int at=k;at>=0;at=flood.parent()[at])path.add(new DryPoint(p.ox()+at/p.nz()*p.step(),p.oz()+at%p.nz()*p.step(),p.height()[at]));
                        double[] stats=writeTransferPath(points,profile,cases,path,terrain);max=stats[0];rise=stats[1];accepted++;
                        valid &= path.get(path.size()-1).x()==Double.parseDouble(port.get("x")) && path.get(path.size()-1).z()==Double.parseDouble(port.get("z"));
                        valid &= Math.max(internal,Double.parseDouble(port.get("edge")))==g.barrier();
                    }
                    summary.append(cases).append(',').append(component).append(',').append(r.get("id")).append(',').append(r.get("x")).append(',').append(r.get("z"))
                            .append(',').append(r.get("targetX")).append(',').append(r.get("targetZ")).append(',').append(k).append(',').append(portId).append(',').append(r.get("known"))
                            .append(',').append(k>=0 && g.wet().get(k)).append(',').append(link).append(',').append(g.level()).append(',').append(internal)
                            .append(',').append(Math.max(internal,Double.parseDouble(port.get("edge")))).append(',').append(g.barrier()).append(',').append(internal<=g.level())
                            .append(',').append(status).append(',').append(path.size()).append(',').append(max==null?"":max).append(',').append(rise==null?"":rise).append(",false\n");cases++;
                }
                portId++;
            }
        }
        Files.writeString(dir.resolve("internal-transfer-summary.csv"),summary);Files.writeString(dir.resolve("internal-transfer-points.csv"),points);
        Files.writeString(dir.resolve("internal-transfer-profiles.csv"),profile);
        chk(valid && accepted>0,"盆内接触至全部兼容出接口同图precritical内路径，不抬平面、不作出流证书", "wrong");
        log("G24 candidates="+cases+" sampledTransfers="+accepted+" ALL_SHORT_LINKS_PRESERVED=true ACTUAL_PROFILE33_NOT_CONTINUOUS=true NO_NEW_PIPELINE=true");
    }

    private static double[] writeTransferPath(StringBuilder points, StringBuilder profile, int id, List<DryPoint> path, WindowOutlets.Terrain terrain) {
        double max=Double.NEGATIVE_INFINITY,rise=Double.NEGATIVE_INFINITY;
        for(int i=0;i<path.size();i++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("internal transfer profile");
            var b=path.get(i);points.append(id).append(',').append(i).append(',').append(b.x()).append(',').append(b.z()).append(',').append(b.height()).append('\n');
            max=Math.max(max,b.height());if(i==0)continue;var a=path.get(i-1);double previous=Double.NaN;
            for(int t=0;t<=32;t++) {
                double x=a.x()+(b.x()-a.x())*t/32,z=a.z()+(b.z()-a.z())*t/32,h=terrain.height(x,z);
                if(t>0)rise=Math.max(rise,h-previous);max=Math.max(max,h);previous=h;
                profile.append(id).append(',').append(i-1).append(',').append(t).append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
            }
        }
        return new double[]{max,rise};
    }

    private static void internalTransferSynthetic() {
        WindowOutlets.Terrain wall=(x,z)->x==2?3:0;
        var p=denseCrestPatch(samplePortPatch(wall,0,0,4,4,1,new DryPoint(0,2,0),new DryPoint(4,2,0)),wall);
        int[] mask=new int[p.height().length];var wet=new java.util.BitSet(mask.length);wet.set(0,mask.length);
        var g=new TransferGrid(p,mask,wet,Map.of(),1,3,List.of());var f=ringFlood(p,mask,0,List.of(p.end()));
        var link=Map.of("known","true","linkMax","0.0");
        chk(f.cost()[p.start()]==3 && !freeReachable(p,mask,p.start(),1).get(p.end()) && freeReachable(p,mask,p.start(),3).get(p.end()),
                "入点出点同域不代表原平面可通，实际边屏障必须保留", "wrong");
        chk(transferStatus(g,link,p.start(),3).startsWith("SAMPLED_TRANSFER") && !transferStatus(g,link,-1,3).startsWith("SAMPLED_TRANSFER"),
                "有局部连通仅作蓄水候选，域外不冒充盆内", "wrong");
        chk(transferStatus(g,Map.of("known","false"),p.start(),3).equals("SHORT_LINK_UNKNOWN")
                && transferStatus(g,Map.of("known","true","linkMax","2.0"),p.start(),3).equals("SHORT_LINK_ABOVE_ORIGINAL_PLANE"),
                "接触未知或短连接超原平面不能偷接盆内", "wrong");
        for(int k=0;k<mask.length;k++)if(k/p.nz()==2)mask[k]=-1;
        chk(Double.isInfinite(ringFlood(p,mask,0,List.of(p.end())).cost()[p.start()]),"盆内未知整墙不靠两端标签制造接续", "wrong");
        var a=new StringBuilder();var b=new StringBuilder();var path=List.of(new DryPoint(0,2,0),new DryPoint(4,2,0));
        double[] stats=writeTransferPath(a,b,1,path,wall);
        chk(stats[0]==3 && stats[1]>0,"盆内路径加密保留上坡和边内屏障，不用端点自证下降", "wrong");
        boolean cancelled=false;try{Thread.currentThread().interrupt();writeTransferPath(new StringBuilder(),new StringBuilder(),1,path,wall);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && java.util.Arrays.equals(stats,writeTransferPath(new StringBuilder(),new StringBuilder(),1,path,wall)),"盆内剖面预取消重试同值", "wrong");
    }

    private static SubcellPatch denseCrestPatch(SubcellPatch p, WindowOutlets.Terrain terrain) {
        double[] east=p.east().clone(),south=p.south().clone();
        for(int k=0;k<p.height().length;k++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("dense crest patch");
            int x=k/p.nz(),z=k%p.nz();var at=new DryPoint(p.ox()+x*p.step(),p.oz()+z*p.step(),p.height()[k]);
            if(x+1<p.nx())east[k]=Math.max(east[k],crestSegment(terrain,at,new DryPoint(at.x()+p.step(),at.z(),p.height()[k+p.nz()])));
            if(z+1<p.nz())south[k]=Math.max(south[k],crestSegment(terrain,at,new DryPoint(at.x(),at.z()+p.step(),p.height()[k+1])));
        }
        return new SubcellPatch(p.ox(),p.oz(),p.step(),p.nx(),p.nz(),p.height(),east,south,p.start(),p.end());
    }

    private static List<DryPoint> crestGridPath(SubcellPatch p, RingFlood flood, int target) {
        var path=new ArrayList<DryPoint>();
        if(Double.isFinite(flood.cost()[target]))for(int k=target;k>=0;k=flood.parent()[k])
            path.add(new DryPoint(p.ox()+k/p.nz()*p.step(),p.oz()+k%p.nz()*p.step(),p.height()[k]));
        java.util.Collections.reverse(path);return List.copyOf(path);
    }

    private static double writeExactCrestPath(StringBuilder csv, String mode, String kind, List<DryPoint> path,
                                             WindowOutlets.Terrain terrain) {
        double max=Double.NEGATIVE_INFINITY;
        for(int j=1;j<path.size();j++)for(int t=0;t<=128;t++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("exact crest profile");
            var a=path.get(j-1);var b=path.get(j);double x=a.x()+(b.x()-a.x())*t/128,z=a.z()+(b.z()-a.z())*t/128,h=terrain.height(x,z);
            max=Math.max(max,h);csv.append(mode).append(',').append(kind).append(',').append(j-1).append(',').append(t)
                    .append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
        }
        return max;
    }

    private static void auditExactCrestEdge(Path dir, WindowGrid grid, SharedBoundaryDrainage.WorldCell anchor, int id,
                                            DryPoint crest, DryPoint a, DryPoint b, WindowOutlets.Terrain terrain) throws Exception {
        WindowOutlets.Terrain known=(x,z)->crestContext(grid,x,z).equals("KNOWN")?terrain.height(x,z):Double.POSITIVE_INFINITY;
        var base=samplePortPatch(known,crest.x()-1,crest.z()-1,crest.x()+1,crest.z()+1,1.0/64,a,b);
        var dense=denseCrestPatch(base,known);int c=(base.nx()/2)*base.nz()+base.nz()/2;
        var summary=new StringBuilder("mode,step,fromX,fromZ,toX,toZ,crestX,crestZ,crestHeight,start,end,crestNode,directMax,freeBarrier,viaCrestBarrier,viaPenalty,freeDenseMax,freeOvershoot,pathNodes,globalCertified\n");
        var cells=new StringBuilder("mode,node,x,z,height,east,south,fromCost,toCost,fromParent,toParent,known\n");
        var points=new StringBuilder("mode,node,x,z,height\n");var profiles=new StringBuilder("mode,kind,segment,sample,x,z,height\n");
        boolean valid=true;double previous=Double.NEGATIVE_INFINITY;
        for(int mode=0;mode<2;mode++) {
            var p=mode==0?base:dense;String label=mode==0?"QUARTER":"EDGE33";int[] mask=new int[p.height().length];
            for(int k=0;k<mask.length;k++)if(!Double.isFinite(p.height()[k]))mask[k]=-1;
            var from=ringFlood(p,mask,0,List.of(p.start()));var to=ringFlood(p,mask,0,List.of(p.end()));
            double free=from.cost()[p.end()],via=Math.max(from.cost()[c],to.cost()[c]);
            var path=crestGridPath(p,from,p.end());double actual=writeExactCrestPath(profiles,label,"FREE_PATH",path,known);
            double direct=writeExactCrestPath(profiles,label,"ORIGINAL_EDGE",List.of(a,b),known);
            valid &= free>=previous && via>=free && free==to.cost()[p.start()] && free>=Math.max(a.height(),b.height());previous=free;
            valid &= path.get(0).x()==a.x() && path.get(0).z()==a.z() && path.get(path.size()-1).x()==b.x() && path.get(path.size()-1).z()==b.z();
            valid &= freeReachable(p,mask,p.start(),free).get(p.end()) && !freeReachable(p,mask,p.start(),Math.nextDown(free)).get(p.end());
            for(int k=0;k<mask.length;k++)cells.append(label).append(',').append(k).append(',').append(p.ox()+k/p.nz()*p.step()).append(',').append(p.oz()+k%p.nz()*p.step())
                    .append(',').append(p.height()[k]).append(',').append(p.east()[k]).append(',').append(p.south()[k]).append(',').append(from.cost()[k]).append(',').append(to.cost()[k])
                    .append(',').append(from.parent()[k]).append(',').append(to.parent()[k]).append(',').append(mask[k]==0).append('\n');
            for(int j=0;j<path.size();j++){var at=path.get(j);points.append(label).append(',').append(j).append(',').append(at.x()).append(',').append(at.z()).append(',').append(at.height()).append('\n');}
            summary.append(label).append(',').append(p.step()).append(',').append(a.x()).append(',').append(a.z()).append(',').append(b.x()).append(',').append(b.z())
                    .append(',').append(crest.x()).append(',').append(crest.z()).append(',').append(crest.height()).append(',').append(p.start()).append(',').append(p.end()).append(',').append(c)
                    .append(',').append(direct).append(',').append(free).append(',').append(via).append(',').append(via-free).append(',').append(actual).append(',').append(actual-free)
                    .append(',').append(path.size()).append(",false\n");
        }
        String tag=anchor.x()+"_"+anchor.z()+"_"+id+".csv";
        Files.writeString(dir.resolve("crest-exact-summary-"+tag),summary);Files.writeString(dir.resolve("crest-exact-grid-"+tag),cells);
        Files.writeString(dir.resolve("crest-exact-paths-"+tag),points);Files.writeString(dir.resolve("crest-exact-profiles-"+tag),profiles);
        chk(valid,"原临界边准确端点同网格quarter/33点连通与独立BFS anchor="+anchor+" edge="+id,"wrong");
        log("G20 anchor="+anchor+" edge="+id+" FIXED_SAME_SQUARE=true EXACT_OLD_EDGE_ENDPOINTS=true ALL_GRID_EDGES_33_SAMPLES=true PROFILE129_NOT_CONTINUOUS_CERTIFICATE=true NO_PRODUCTION_CHANGE=true");
    }

    private static void exactCrestSynthetic() {
        var a=new DryPoint(0,0,0);var b=new DryPoint(1,0,0);
        WindowOutlets.Terrain narrow=(x,z)->Math.exp(-Math.pow((x-.03125)/.004,2));
        var p=samplePortPatch(narrow,0,0,1,1,.25,a,b);var d=denseCrestPatch(p,narrow);
        var q=subcellMinimax(p);var r=subcellMinimax(d);
        chk(q.barrier()<1e-10 && r.barrier()>.99 && subcellReachable(d,r.barrier()) && !subcellReachable(d,Math.nextDown(r.barrier())),
                "全图33点边一致重算捕获四分点漏窄峰，不只重验已选路径","wrong");
        int[] all=new int[p.height().length];var oldWet=freeReachable(p,all,p.start(),.1);var newWet=freeReachable(d,all,d.start(),.1);
        var extra=(java.util.BitSet)newWet.clone();extra.andNot(oldWet);
        var pre=freeReachable(d,all,d.start(),Math.nextDown(r.barrier()));int[] outside=all.clone();
        for(int k=pre.nextSetBit(0);k>=0;k=pre.nextSetBit(k+1))outside[k]=-1;
        var reverse=ringFlood(d,outside,0,List.of(d.end()));int compatible=0;
        for(var edge:freeBoundary(d,pre))if(subcellEdge(d,edge.from(),edge.to())==r.barrier() && reverse.cost()[edge.to()]<=r.barrier())compatible++;
        chk(extra.isEmpty() && newWet.cardinality()<oldWet.cardinality() && compatible>0,"整盆加密不增加原平面可达集，全部临界割保留可接外缘端口","wrong");
        WindowOutlets.Terrain bump=(x,z)->x==.5 && z==0?4:0;
        var hump=samplePortPatch(bump,0,0,1,1,.25,a,b);var f=ringFlood(hump,new int[hump.height().length],0,List.of(hump.start()));
        var back=ringFlood(hump,new int[hump.height().length],0,List.of(hump.end()));int crest=2*hump.nz();
        chk(f.cost()[hump.end()]==0 && Math.max(f.cost()[crest],back.cost()[crest])==4,"强经旧脊顶可比准确端点自由绕行更高，不宣称旧脊顶唯一","wrong");
        WindowOutlets.Terrain unknown=(x,z)->x==.5?Double.POSITIVE_INFINITY:0;
        var u=denseCrestPatch(samplePortPatch(unknown,0,0,1,1,.25,a,b),unknown);
        chk(Double.isInfinite(subcellMinimax(u).barrier()),"一致密采样不穿未知整带","wrong");
        boolean cancelled=false;try{Thread.currentThread().interrupt();denseCrestPatch(p,narrow);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && r.equals(subcellMinimax(denseCrestPatch(p,narrow))),"一致边采样取消重试完整同值","wrong");
    }

    private static void crestLinkSynthetic() {
        var zero=new DryPoint(-16.375,.25,0);WindowOutlets.Terrain slope=(x,z)->x-zero.x();
        var p=samplePortPatch(slope,zero.x()-1,zero.z()-1,zero.x()+1,zero.z()+1,.125,zero,zero);
        var flood=ringFlood(p,new int[p.height().length],0,List.of(p.start()));
        var high=new DryPoint(zero.x()+1,zero.z(),1);var up=connectCrest(p,flood,high,slope);
        var low=new DryPoint(zero.x()-1,zero.z(),-1);var down=connectCrest(p,flood,low,slope);
        chk(up.barrier()==1 && down.barrier()==0 && up.path().get(0).equals(zero) && up.path().get(up.path().size()-1).equals(high),
                "高射线起点自身即屏障下界，准确非格点脊顶及目标不搬移","wrong");
        WindowOutlets.Terrain wall=(x,z)->Math.abs(x-zero.x()-.375)<.01?3:0;
        var w=samplePortPatch(wall,zero.x()-1,zero.z()-1,zero.x()+1,zero.z()+1,.25,zero,zero);
        var wf=ringFlood(w,new int[w.height().length],0,List.of(w.start()));
        chk(connectCrest(w,wf,new DryPoint(zero.x()+1,zero.z(),0),wall).barrier()==3,"端点低不能越过边内墙，连接代价保留真实采样屏障","wrong");
        WindowOutlets.Terrain unknown=(x,z)->x>zero.x()+.25?Double.POSITIVE_INFINITY:0;
        chk(Double.isInfinite(connectCrest(p,flood,high,unknown).barrier()),"未知精确目标不可由已知网格近点冒充连通","wrong");
        var u=samplePortPatch(unknown,zero.x()-1,zero.z()-1,zero.x()+1,zero.z()+1,.125,zero,zero);
        int[] mask=new int[u.height().length];for(int k=0;k<mask.length;k++)if(!Double.isFinite(u.height()[k]))mask[k]=-1;
        chk(Double.isInfinite(connectCrest(u,ringFlood(u,mask,0,List.of(u.start())),high,unknown).barrier()),"未知整带不产生部分网格接通路径","wrong");
        WindowOutlets.Terrain narrow=(x,z)->Math.exp(-Math.pow((x-zero.x()-.03125)/.004,2));
        var peak=samplePortPatch(narrow,zero.x()-1,zero.z()-1,zero.x()+1,zero.z()+1,.25,zero,zero);
        var missed=connectCrest(peak,ringFlood(peak,new int[peak.height().length],0,List.of(peak.start())),high,narrow);
        var pp=new StringBuilder();var dd=new StringBuilder();
        chk(missed.barrier()<1e-10 && writeCrestLinkPath(pp,dd,.25,0,missed,narrow)>.99,"窄脊反例：图边四分点可漏峰，密查超高必须保留","wrong");
        boolean cancelled=false;try{Thread.currentThread().interrupt();connectCrest(p,flood,high,slope);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && up.equals(connectCrest(p,flood,high,slope)),"脊顶连接预取消重试完整同值","wrong");
    }

    private static void crestSynthetic(WindowGrid original) {
        var p=original.pipe();var sea=new boolean[p.bed().length];
        var grid=new WindowGrid(new HydrologyPipeline.Result(p.nx(),p.nz(),p.cellBlocks(),p.bed(),sea,p.resolved(),p.level(),p.depth(),p.lake(),p.parent(),
                p.discharge(),p.channel(),p.width(),p.lakeResult(),p.riverResult(),p.deathMask(),p.waterSurface(),p.couplingRounds(),p.couplingConverged(),p.couplingResidual()),original.ox(),original.oz());
        var label=grid.world(4*p.nz()+4);var start=new DryPoint(label.x()+.3,label.z()+.2,100-label.z()-.2);
        var line=traceCrestRay(grid,(x,z)->100-z,label,start,.0625);
        chk(line.points().size()>5 && line.terminal().equals("ORIGINAL_CONTEXT_LIMIT_PENDING"),"脊线射线只在原上下文内下降，框不是入海", "wrong");
        int k=4*p.nz()+4;boolean old=p.resolved()[k];
        try{p.resolved()[k]=false;chk(traceCrestRay(grid,(x,z)->100-z,label,start,.25).terminal().equals("UNKNOWN_OWNER_PENDING"),"未知owner优先停下，不穿越未知求下游", "wrong");}
        finally{p.resolved()[k]=old;}
        sea[k]=true;chk(traceCrestRay(grid,(x,z)->100-z,label,start,.25).terminal().equals("LOCAL_SEA_MARKER_NOT_GLOBAL_OCEAN"),"局部海标识单列而非全局海证书", "wrong");sea[k]=false;
        boolean cancelled=false;try{Thread.currentThread().interrupt();traceCrestRay(grid,(x,z)->100-z,label,start,.0625);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && line.equals(traceCrestRay(grid,(x,z)->100-z,label,start,.0625)),"脊线射线取消重试完整同值", "wrong");
        WindowOutlets.Terrain ridge=(x,z)->100-(x-label.x())*(x-label.x());
        var left=new DryPoint(label.x()-.125,label.z(),ridge.height(label.x()-.125,label.z()));
        var right=new DryPoint(label.x()+.125,label.z(),ridge.height(label.x()+.125,label.z()));
        var l=traceCrestRay(grid,ridge,label,left,.0625);var rr=traceCrestRay(grid,ridge,label,right,.0625);
        chk(l.points().get(0).equals(left) && rr.points().get(0).equals(right)
                && l.points().get(l.points().size()-1).x()<left.x() && rr.points().get(rr.points().size()-1).x()>right.x(),
                "准确边两端分别向两侧下降，不把外端最近盆地当内端归属","wrong");
        var edgeProfile=new StringBuilder();double peak=writeExactCrestPath(edgeProfile,"SYNTH","EDGE",List.of(left,right),ridge);
        chk(peak==100 && peak>left.height() && peak>right.height(),"两端可下降不代表端点间无更高脊顶，完整边剖面必须保留","wrong");
    }

    private static void freeRingSynthetic() {
        var p=samplePortPatch((x,z)->x==2 && z>=1 && z<=3?5:0,0,0,4,4,1,new DryPoint(0,2,0),new DryPoint(4,2,0));
        int[] all=new int[p.height().length],restricted=new int[all.length];
        for(int k=0;k<all.length;k++)restricted[k]=k%p.nz()==2?0:-1;
        var free=ringFlood(p,all,0,List.of(p.start()));var old=ringFlood(p,restricted,0,List.of(p.start()));
        chk(free.cost()[p.end()]==0 && old.cost()[p.end()]==5,"粗footprint限制可制造假障碍，自由图允许绕行但不改范围", "wrong");
        var wall=samplePortPatch((x,z)->x==2?3:0,0,0,4,4,1,new DryPoint(0,2,0),new DryPoint(4,2,0));
        var f=ringFlood(wall,all,0,List.of(wall.start()));var pre=freeReachable(wall,all,wall.start(),Math.nextDown(f.cost()[wall.end()]));
        var on=freeReachable(wall,all,wall.start(),f.cost()[wall.end()]);
        chk(f.cost()[wall.end()]==3 && !pre.get(wall.end()) && on.get(wall.end()) && freeBoundary(wall,pre).size()==5,
                "完整真屏障的临界前连通集合与整圈割边独立oracle", "wrong");
        for(int k=0;k<all.length;k++)if(k/p.nz()==2)all[k]=-1;
        chk(Double.isInfinite(ringFlood(wall,all,0,List.of(wall.start())).cost()[wall.end()]),"未知带不可穿越，不靠另侧终端把未知当连通", "wrong");
    }

    private static void ringSynthetic() {
        var p=samplePortPatch((x,z)->Math.abs(x-2),0,0,4,4,1,new DryPoint(2,2,0),new DryPoint(4,2,0));
        int[] mask=new int[p.height().length];for(int k=0;k<mask.length;k++)mask[k]=k/p.nz()==2?1:0;
        var r=new RingPatch(p,new int[mask.length],mask,p.start());var in=ringFlood(p,mask,1,List.of(p.start()));
        var outside=ringFlood(p,mask,0,List.of(0,24));var edges=ringCrossings(r);
        chk(edges.size()==10 && edges.stream().allMatch(e->ringPortCost(p,e,in,outside)==2),"完整圈双侧出口全保留，不按选中首边限制候选", "wrong");
        var one=ringFlood(p,mask,0,List.of(0));
        chk(Double.isInfinite(one.cost()[24]),"外部通路禁止穿回内部到另侧，不能借盆内捷径自证出口", "wrong");
        var no=ringFlood(p,mask,0,List.of());chk(java.util.Arrays.stream(no.cost()).allMatch(Double::isInfinite),"无外部终端保留Infinity，不假定海或零高程", "wrong");
        boolean cancelled=false;
        try{Thread.currentThread().interrupt();ringFlood(p,mask,1,List.of(p.start()));}catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && java.util.Arrays.equals(in.cost(),ringFlood(p,mask,1,List.of(p.start())).cost()),"整圈计算取消重试不消费邻finalfine或部分产物", "wrong");
    }

    private static void subcellSynthetic() {
        var a=new DryPoint(0,2,0);var b=new DryPoint(4,2,0);
        WindowOutlets.Terrain hump=(x,z)->Math.exp(-8*((x-2)*(x-2)+(z-2)*(z-2)));
        var patch=samplePortPatch(hump,0,0,4,4,0.25,a,b);var route=subcellMinimax(patch);
        chk(route.barrier()<0.01 && hump.height(2,2)==1 && subcellReachable(patch,0.01),"孤立高点可绕行：直线有障碍不等于矩形无通路", "wrong");
        WindowOutlets.Terrain wall=(x,z)->x==1.125?3:0;
        var blocked=samplePortPatch(wall,0,0,4,4,0.5,a,b);var br=subcellMinimax(blocked);
        chk(br.barrier()==3 && !subcellReachable(blocked,2),"跨全矩形墙的边内采样必须阻断，不能只看端点高度", "wrong");
        var random=new java.util.Random(8145);boolean exact=true;
        for(int trial=0;trial<24;trial++) {
            double[] h=new double[25];for(int k=0;k<h.length;k++)h[k]=random.nextInt(9);
            var test=samplePortPatch((x,z)->h[(int)Math.round(x)*5+(int)Math.round(z)],0,0,4,4,1,new DryPoint(0,0,0),new DryPoint(4,4,0));
            var r=subcellMinimax(test);exact &= subcellReachable(test,r.barrier()) && !subcellReachable(test,Math.nextDown(r.barrier()));
        }
        chk(exact,"24随机高度场独立连通BFS证明离散minimax临界，不用同一最短路自证", "wrong");
        boolean cancelled=false;
        try{Thread.currentThread().interrupt();subcellMinimax(patch);}catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && route.equals(subcellMinimax(patch)),"子格计算取消重试同值，不发布部分路径", "wrong");
    }

    private static void stopAuditSynthetic() {
        var origin=new DryPoint(0,0,0);
        var slope=stopRing((x,z)->-z,origin,0.25);var bowl=stopRing((x,z)->x*x+z*z,origin,0.25);
        chk(slope.lower()>0 && slope.sampledDescent()==slope.lower() && Math.abs(slope.minimumDelta()+0.25)<1e-12,
                "停止点环采样独立平坡oracle存在可下降方向","wrong");
        chk(bowl.lower()==0 && bowl.sampledDescent()==0 && bowl.minimumDelta()>0,"碗底环采样不伪造下降","wrong");
        WindowOutlets.Terrain narrow=(x,z)->x*x+(z-0.01)*(z-0.01)+100*z*z*z;
        var broad=downhillGradient(narrow,0,0,0.25);var local=downhillGradient(narrow,0,0,0.000244140625);
        chk(broad.z()<0 && local.z()>0 && stopRing(narrow,new DryPoint(0,0,narrow.height(0,0)),0.000244140625).lower()>0,
                "真实解析反例：跨谷有限差分方向可与局部导数相反，非零梯度不能证明轨迹可继续","wrong");
        boolean cancelled=false;
        try{Thread.currentThread().interrupt();stopRing((x,z)->-z,origin,0.25);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && slope.equals(stopRing((x,z)->-z,origin,0.25)),"停止点审计预取消重试同值","wrong");
        planePortSynthetic();
        subcellSynthetic();
        ringSynthetic();
        contactSynthetic();
        freeRingSynthetic();
    }

    private static void planePortSynthetic() {
        int n=7,size=n*n,seed=3*n+3,east=4*n+3,west=2*n+3;double[] bed=new double[size],level=new double[size];
        boolean[] resolved=new boolean[size],sea=new boolean[size];int[] parent=new int[size];
        java.util.Arrays.fill(bed,30);java.util.Arrays.fill(level,30);java.util.Arrays.fill(resolved,true);java.util.Arrays.fill(parent,-1);
        bed[seed]=9.8;level[seed]=10;bed[east]=level[east]=9;bed[west]=level[west]=8;
        var lake=new LakeWaterBalance.Result(new double[size],new boolean[size],List.of(),0,1,Map.of(),Map.of());
        var p=new HydrologyPipeline.Result(n,n,4,bed,sea,resolved,level,new double[size],new boolean[size],parent,new double[size],
                new boolean[size],new double[size],lake,null,null,level,0,true,0);
        var grid=new WindowGrid(p,-24,-16);var ports=lowerPlanePorts(grid,List.of(seed));
        chk(ports.equals(List.of(new PlanePort(seed,west),new PlanePort(seed,east))),"分量所有下降边保留，不挑最低边冒充唯一出口", "wrong");
        parent[east]=seed;
        chk(planePortSuffix(grid,List.of(seed),east).terminal().equals("REENTER_ORIGINAL_PLANE_PENDING"),"下降端口原parent回盆必须显式冲突", "wrong");
        parent[east]=east;
        chk(planePortSuffix(grid,List.of(seed),east).terminal().equals("PARENT_CYCLE_PENDING"),"端口下游环不伪造出口", "wrong");
        parent[east]=5*n+3;bed[5*n+3]=7;level[5*n+3]=8;
        chk(planePortSuffix(grid,List.of(seed),east).terminal().equals("NEXT_FILLED_PLANE_PENDING"),"遇下一浅填充分量即停，不因没有可见湖忽略盆地", "wrong");
        resolved[east]=false;sea[east]=true;
        chk(planePortSuffix(grid,List.of(seed),east).terminal().equals("UNRESOLVED_DOWNSTREAM_PENDING"),"未决优先于局部海标识", "wrong");
        resolved[east]=true;sea[east]=false;parent[east]=3*n;
        bed[3*n]=level[3*n]=7;
        chk(planePortSuffix(grid,List.of(seed),east).terminal().equals("UNKNOWN_FRAME_PENDING"),"原parent到窗框不是已完成排水", "wrong");
        boolean cancelled=false;
        try{Thread.currentThread().interrupt();lowerPlanePorts(grid,List.of(seed));}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && lowerPlanePorts(grid,List.of(seed)).equals(ports),"端口枚举取消重试不丢边", "wrong");
        java.util.Arrays.fill(bed,20);java.util.Arrays.fill(level,20);java.util.Arrays.fill(parent,-1);
        bed[seed]=19.8;
        var shallow=new LakeWaterBalance(n,n,4,bed,new EquilibriumWater.Surface(level,resolved,parent,0,0,0)).dropTinyBasins(false)
                .solve(new LakeWaterBalance.Climate(1,0,1),new boolean[size],new boolean[size]);
        chk(level[seed]>bed[seed] && shallow.basins().isEmpty() && LakeWaterBalance.basinOf(shallow,seed)==-1,
                "实际水量平衡小于1块深过滤：无可见湖且无basinId，不等于无几何盆地", "wrong");
    }

    private static void writeDryStability(StringBuilder csv, DryPath full, DryPath half, WindowOutlets.Terrain terrain) {
        var a=full.points().isEmpty()?null:full.points().get(full.points().size()-1);
        var b=half.points().isEmpty()?null:half.points().get(half.points().size()-1);
        var gradient=a==null?new DirectionVector(Double.NaN,Double.NaN):downhillGradient(terrain,a.x(),a.z(),0.25);
        csv.append(full.source().x()).append(',').append(full.source().z()).append(',').append(full.terminal()).append(',').append(half.terminal())
                .append(',').append(a==null?Double.NaN:a.x()).append(',').append(a==null?Double.NaN:a.z())
                .append(',').append(b==null?Double.NaN:b.x()).append(',').append(b==null?Double.NaN:b.z())
                .append(',').append(a==null || b==null?Double.NaN:Math.hypot(a.x()-b.x(),a.z()-b.z()))
                .append(',').append(dryCrossings(full.points(),hp().tract()).size()).append(',').append(dryCrossings(half.points(),hp().tract()).size())
                .append(',').append(Math.hypot(gradient.x(),gradient.z())).append(',').append(a==null?Double.NaN:a.height()).append(",false\n");
    }

    private static void writeDryPathRows(StringBuilder points, StringBuilder crossings, DryPath path, String variant) {
        var source=path.source();
        for(int i=0;i<path.points().size();i++) {
            var p=path.points().get(i);points.append(source.x()).append(',').append(source.z()).append(',').append(variant).append(',').append(i)
                    .append(',').append(p.x()).append(',').append(p.z()).append(',').append(p.height()).append(',').append(path.terminal()).append('\n');
        }
        var events=dryCrossings(path.points(),hp().tract());
        for(int i=0;i<events.size();i++) {
            var c=events.get(i);crossings.append(source.x()).append(',').append(source.z()).append(',').append(variant).append(',').append(i)
                    .append(',').append(c.axis()).append(',').append(c.boundary()).append(',').append(c.x()).append(',').append(c.z())
                    .append(',').append(c.direction()).append('\n');
        }
    }

    private static void renderContinuousDryPaths(Path dir, WindowGrid grid, List<DryPath> raw, List<DryPath> candidates) throws Exception {
        var base=javax.imageio.ImageIO.read(dir.resolve("production.png").toFile());int size=705;
        var image=new java.awt.image.BufferedImage(2115,795,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        String[] titles={"ACTUAL PROVIDER / UNCHANGED WATER", "RAW PARENT / SAME SOURCE / NOT WATER", "CONTINUOUS DRY PREFIX / NOT WATER OR FULL RIVER"};
        for(int panel=0;panel<3;panel++) {
            int offset=panel*size;g.drawImage(base,offset,60,offset+size,765,0,58,base.getWidth(),base.getHeight(),null);
            g.setColor(java.awt.Color.WHITE);g.drawString(titles[panel],offset+8,20);
            if(panel==0)continue;
            var paths=panel==1?raw:candidates;g.setColor(panel==1?java.awt.Color.ORANGE:java.awt.Color.YELLOW);
            for(var path:paths)for(int i=1;i<path.points().size();i++) {
                var a=path.points().get(i-1);var b=path.points().get(i);
                g.drawLine(offset+(int)Math.round(a.x()-grid.ox()),60+(int)Math.round(a.z()-grid.oz()),
                        offset+(int)Math.round(b.x()-grid.ox()),60+(int)Math.round(b.z()-grid.oz()));
            }
            for(var path:paths)if(!path.points().isEmpty()) {
                var first=path.points().get(0);var last=path.points().get(path.points().size()-1);
                g.setColor(java.awt.Color.GREEN);g.fillRect(offset+(int)Math.round(first.x()-grid.ox())-2,60+(int)Math.round(first.z()-grid.oz())-2,5,5);
                g.setColor(java.awt.Color.MAGENTA);g.fillRect(offset+(int)Math.round(last.x()-grid.ox())-2,60+(int)Math.round(last.z()-grid.oz())-2,5,5);
            }
        }
        g.setColor(java.awt.Color.WHITE);g.drawString("Fixed r352 step4 context. Green same world source / magenta pending stop. Outside sources kept in CSV; NO forced entry, NO bridges, NO new water.",8,786);
        g.dispose();javax.imageio.ImageIO.write(image,"png",dir.resolve("continuous-source-overall.png").toFile());
    }

    private static void renderGradientEvidence(Path dir, WindowGrid grid, List<GradientEvidence> evidence) throws Exception {
        var base=javax.imageio.ImageIO.read(dir.resolve("production.png").toFile());int size=705;
        var panel=new java.awt.image.BufferedImage(size*3,755,java.awt.image.BufferedImage.TYPE_INT_RGB);var pg=panel.createGraphics();
        String[] names={"PARENT vs GRADIENT", "CENTER D8 vs GRADIENT", "VALLEY D8 vs GRADIENT"};
        for(int variant=0;variant<3;variant++) {
            int offset=variant*size;pg.drawImage(base,offset,40,offset+size,745,0,58,base.getWidth(),base.getHeight(),null);
            pg.setColor(java.awt.Color.WHITE);pg.drawString(names[variant]+" / SAME PARENT SEGMENTS COLORED / NOT ROUTES",offset+6,16);
            for(var v:evidence) {
                var a=grid.world(v.cell());var b=grid.world(v.parent());double angle=variant==0?v.parentAngle():variant==1?v.centerAngle():v.valleyAngle();
                pg.setColor(!v.dryResolved()?new java.awt.Color(145,85,210):!Double.isFinite(angle)?java.awt.Color.WHITE
                        :angle>30?java.awt.Color.RED:angle>15?java.awt.Color.ORANGE:java.awt.Color.GREEN);
                pg.drawLine(offset+a.x()-grid.ox(),40+a.z()-grid.oz(),offset+b.x()-grid.ox(),40+b.z()-grid.oz());
            }
        }
        pg.setColor(java.awt.Color.WHITE);pg.drawString("Same fixed r352. Green <=15deg / orange 15..30 / red >30 / purple filled-or-unresolved / white no gradient",8,752);
        pg.dispose();javax.imageio.ImageIO.write(panel,"png",dir.resolve("gradient-overall.png").toFile());
    }

    private static void gradientSynthetic() {
        boolean exact=true,quantized=true;double worst=0;
        for(int i=0;i<72;i++) {
            double angle=Math.toRadians(i*5+1),x=Math.cos(angle),z=Math.sin(angle);
            WindowOutlets.Terrain plane=(a,b)->100-x*a-z*b;
            var gradient=downhillGradient(plane,-12,17,0.25);var discrete=terrainD8(plane,-12,17,4);
            exact &= directionAngle(gradient,new DirectionVector(x,z))<1e-5;
            double error=directionAngle(discrete,gradient);worst=Math.max(worst,error);quantized &= error<=22.5+1e-8;
        }
        chk(exact && quantized && worst>20,"72旋转平坡：连续梯度随旋转，D8量化误差可达22度但不超22.5度", "worst="+worst);
        chk(Double.isNaN(directionAngle(new DirectionVector(0,0),new DirectionVector(1,0))),"零梯度不伪造方向或0度误差","wrong");
        int n=33;double[] bed=new double[n*n],exterior=new double[n*n];boolean[] drains=new boolean[n*n];
        java.util.Arrays.fill(exterior,Double.NaN);
        for(int x=0;x<n;x++)for(int z=0;z<n;z++)bed[x*n+z]=100-0.07*x-z;
        for(int x=0;x<n;x++)drains[x*n+n-1]=true;
        var flooded=EquilibriumWater.compute(bed,n,n,drains,exterior);int misleading=0,dry=0;
        for(int x=2;x<n-2;x++)for(int z=2;z<n-2;z++){
            int k=x*n+z,p=flooded.parent()[k];if(p<0)continue;
            if(Double.doubleToLongBits(flooded.level()[k])==Double.doubleToLongBits(bed[k]))dry++;
            if(directionAngle(new DirectionVector(p/n-x,p%n-z),new DirectionVector(0.07,1))>30)misleading++;
        }
        chk(dry==29*29 && misleading>0,"真实洪泛反例：无填平平坡也可产生偏离最陡坡的首次到达parent", "dry="+dry+" off30="+misleading);
    }

    private static void auditSuccessorPlanes(Path dir, WindowGrid grid, SuccessorProjection projection) throws Exception {
        var p = grid.pipe(); int[] component = new int[p.bed().length]; java.util.Arrays.fill(component, -1);
        var members = new ArrayList<List<Integer>>(); int[][] directions = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int seed = 0; seed < component.length; seed++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("plane census");
            if (component[seed] >= 0) continue;
            int id = members.size(); var cells = new ArrayList<Integer>(); cells.add(seed); component[seed] = id;
            for (int i = 0; i < cells.size(); i++) {
                int k = cells.get(i), x = k / p.nz(), z = k % p.nz();
                for (var d : directions) { int a = x + d[0], b = z + d[1];
                    if (a < 0 || b < 0 || a >= p.nx() || b >= p.nz()) continue;
                    int next = a * p.nz() + b;
                    if (component[next] < 0 && Double.doubleToLongBits(p.level()[next]) == Double.doubleToLongBits(p.level()[seed])) {
                        component[next] = id; cells.add(next);
                    }
                }
            }
            members.add(List.copyOf(cells));
        }
        var edgeCsv = new StringBuilder("fromX,fromZ,toX,toZ,component,equalLevel,bedRise,fromFilled,toFilled,fromResolved,toResolved,sources\n");
        var used = new java.util.TreeSet<Integer>(); int flat = 0, rise = 0, filled = 0;
        for (var e : projection.support().entrySet()) {
            int a = e.getKey().from(), b = e.getKey().to(); var wa = grid.world(a); var wb = grid.world(b); used.add(component[a]);
            boolean same = component[a] == component[b], fa = p.level()[a] > p.bed()[a] + 1e-9, fb = p.level()[b] > p.bed()[b] + 1e-9;
            if (same) flat++; if (p.bed()[b] > p.bed()[a] + 1e-9) rise++; if (fa || fb) filled++;
            edgeCsv.append(wa.x()).append(',').append(wa.z()).append(',').append(wb.x()).append(',').append(wb.z()).append(',').append(component[a])
                    .append(',').append(same).append(',').append(p.bed()[b] - p.bed()[a]).append(',').append(fa).append(',').append(fb)
                    .append(',').append(p.resolved()[a]).append(',').append(p.resolved()[b]).append(',').append(e.getValue().size()).append('\n');
        }
        var planes = new StringBuilder("id,anchorX,anchorZ,level,members,filled,unresolved,framePorts,lowerPairs,supportNodes\n");
        var supportNodes = new java.util.HashSet<Integer>(); projection.support().keySet().forEach(e -> { supportNodes.add(e.from()); supportNodes.add(e.to()); });
        for (int id : used) {
            var cells = members.get(id); int fill = 0, unknown = 0, frame = 0, lower = 0, support = 0;
            for (int k : cells) {
                int x = k / p.nz(), z = k % p.nz(); if (p.level()[k] > p.bed()[k] + 1e-9) fill++;
                if (!p.resolved()[k]) unknown++; if (x == 0 || z == 0 || x == p.nx()-1 || z == p.nz()-1) frame++;
                if (supportNodes.contains(k)) support++;
                for (var d : directions) { int a = x+d[0], b = z+d[1]; if (a < 0 || b < 0 || a >= p.nx() || b >= p.nz()) continue;
                    if (p.level()[a*p.nz()+b] < p.level()[k]) lower++; }
            }
            var anchor = grid.world(cells.get(0));
            planes.append(id).append(',').append(anchor.x()).append(',').append(anchor.z()).append(',').append(p.level()[cells.get(0)])
                    .append(',').append(cells.size()).append(',').append(fill).append(',').append(unknown).append(',').append(frame).append(',').append(lower).append(',').append(support).append('\n');
        }
        Files.writeString(dir.resolve("successor-edge-terrain.csv"), edgeCsv); Files.writeString(dir.resolve("successor-planes.csv"), planes);
        log("Z1 supportedEdges=" + projection.support().size() + " equalPlaneEdges=" + flat + " rawBedEndpointRise=" + rise
                + " filledIncident=" + filled + " usedLocalComponents=" + used.size() + " NO_PLANE_IS_GLOBAL_BASIN=true");
    }

    private static WindowGraph dryWindowSuccessor(WindowGrid grid, WindowGraph raw) {
        var p = grid.pipe(); var next = new HashMap<>(raw.next());
        for (int k : raw.next().keySet()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("dry successor");
            int x = k / p.nz(), z = k % p.nz();
            if (x == 0 || z == 0 || x == p.nx()-1 || z == p.nz()-1 || !p.resolved()[k]
                    || Double.doubleToLongBits(p.level()[k]) != Double.doubleToLongBits(p.bed()[k])) continue;
            var stencil = new ArrayList<SharedBoundaryDrainage.BedSample>();
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                int n = (x+dx)*p.nz()+z+dz; stencil.add(new SharedBoundaryDrainage.BedSample(grid.world(n), p.bed()[n]));
            }
            var slope = SharedBoundaryDrainage.drySlope(grid.world(k), p.cellBlocks(), stencil);
            int n = slope.next() == null ? -1 : grid.index(slope.next());
            if (n >= 0 && p.resolved()[n] && !p.lake()[n] && !p.sea()[n]
                    && Double.doubleToLongBits(p.level()[n]) == Double.doubleToLongBits(p.bed()[n])
                    && p.level()[n] < p.level()[k]) next.put(k, List.of(n));
        }
        return new WindowGraph(Map.copyOf(next), raw.ends());
    }

    private static void windowDrySuccessor(Path dir, WindowGrid grid, List<WindowEntry> entries, WindowGraph raw) throws Exception {
        var candidate = dryWindowSuccessor(grid, raw); int changed = 0;
        for (int k : raw.next().keySet()) if (!raw.next().get(k).equals(candidate.next().get(k))) changed++;
        var rows = new StringBuilder("sourceX,sourceZ,fromX,fromZ,expectedX,expectedZ,actualX,actualZ,firstEdge,terminal,nodes\n");
        var actualEntries = new ArrayList<WindowEntry>(); int supported = 0, mismatch = 0, cycle = 0;
        for (var e : entries) {
            var path = windowFollow(grid, candidate, e); int k = grid.index(e.from());
            var ns = candidate.next().get(k); var next = ns == null ? null : grid.world(ns.get(0));
            if (path.firstEdge()) supported++; else mismatch++; if (path.terminal().equals("CYCLE")) cycle++;
            rows.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                    .append(',').append(e.to().x()).append(',').append(e.to().z()).append(',').append(next == null ? "" : next.x())
                    .append(',').append(next == null ? "" : next.z()).append(',').append(path.firstEdge()).append(',').append(path.terminal())
                    .append(',').append(path.cells().size()).append('\n');
            if (next != null && grid.index(e.to()) >= 0) actualEntries.add(new WindowEntry(e.source(), e.from(), next, "CANDIDATE_ENTRY_NOT_DECLARED_EDGE", e.trace()));
        }
        // Alternate entries are plotted separately; mismatched declared obligations are never declared satisfied.
        var display = projectSuccessor(grid, candidate, actualEntries);
        var edges = new java.util.TreeSet<String>();
        display.support().forEach((edge, sources) -> {
            var a = grid.world(edge.from()); var b = grid.world(edge.to());
            edges.add(a.x() + "," + a.z() + "," + b.x() + "," + b.z() + "," + sources.size());
        });
        Files.writeString(dir.resolve("dry-successor-edges.csv"), "fromX,fromZ,toX,toZ,sources\n" + String.join("\n", edges) + "\n");
        Files.writeString(dir.resolve("dry-successor-obligations.csv"), rows);
        renderSuccessor(dir, grid, display, "dry-successor-candidate-entry");
        chk(cycle == 0, "真实干坡替换候选逐已知入口无环（严格level进展）", "cycles=" + cycle);
        log("Z2 dryResolvedStrictLevelONLY changedWholeGrid=" + changed + " exactDeclaredEdges=" + supported + " mismatchOrOutside=" + mismatch
                + " candidateEntryPaths=" + actualEntries.size() + " RAW_BASIN_AND_UNKNOWN_UNCHANGED=true NOT_NEW_WATER=true");
    }

    private static void windowCause(Path dir, WindowGrid grid, List<WindowEntry> entries,
                                    DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        var p = grid.pipe(); var original = windowParams(p);
        var noJoin = new DeterministicSkeleton.Params(original.minSourceSpacingCells(), original.minSourceAccum(), 0,
                original.meanderAmp(), original.meanderWavelength(), original.maxSources(), original.minNodes(),
                original.selfApproachRadius(), original.selfApproachExempt());
        var c = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(), p.discharge(),
                p.lakeResult().basinOutlet(), p.nx(), p.nz(), noJoin, List.of(), p.bed(), false);
        var d = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(), p.discharge(),
                p.lakeResult().basinOutlet(), p.nx(), p.nz(), noJoin, windowSeeds(grid, entries), p.bed(), false);
        var rows = new StringBuilder("sourceX,sourceZ,fromX,fromZ,toX,toZ,AFirst,BFirst,CNoJoinFirst,DNoJoinIncomingFirst,rawFirst\n");
        var replay = new StringBuilder("variant,trace,node,x,z,actualNextX,actualNextZ,parentX,parentZ,replayedJoinX,replayedJoinZ,matchActual,priorTraces,prefixNodes\n");
        var ga = windowGraph(a); var gb = windowGraph(b); var gc = windowGraph(c); var gd = windowGraph(d);
        int gained = 0, lost = 0, mismatches = 0; var replayed = new java.util.HashSet<Integer>();
        for (var e : entries) {
            boolean af = windowFollow(grid, ga, e).firstEdge(), bf = windowFollow(grid, gb, e).firstEdge();
            boolean cf = windowFollow(grid, gc, e).firstEdge(), df = windowFollow(grid, gd, e).firstEdge();
            int from = grid.index(e.from()), to = grid.index(e.to());
            rows.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                    .append(',').append(e.to().x()).append(',').append(e.to().z()).append(',').append(af).append(',').append(bf)
                    .append(',').append(cf).append(',').append(df).append(',').append(from >= 0 && p.parent()[from] == to).append('\n');
            if (!af && bf) gained++; if (af && !bf) lost++;
            if (af != bf && from >= 0 && to >= 0) {
                mismatches++;
                if (replayed.add(from)) { windowReplayJoin(grid, a, from, "A", replay); windowReplayJoin(grid, b, from, "B", replay); }
            }
        }
        Files.writeString(dir.resolve("join-ablation.csv"), rows); Files.writeString(dir.resolve("join-replay.csv"), replay);
        log("X1 samePipelineCallsAdded=0 variants=A/B originalJoin,C/D joinWindow0ONLY A=" + a.traces.size() + " B=" + b.traces.size()
                + " C=" + c.traces.size() + " D=" + d.traces.size() + " gain=" + gained + " loss=" + lost + " replayEvents=" + mismatches
                + " acceptedTracePrefixReplayNOTFullDiscardedState=true NO_JOIN_NOT_REPAIR=true");
        renderWindowCause(dir, grid, entries, new DeterministicSkeleton.Result[]{a, b, c, d}, noJoin);
    }

    private static void windowReplayJoin(WindowGrid grid, DeterministicSkeleton.Result sk, int node, String variant, StringBuilder csv) throws Exception {
        var method = DeterministicSkeleton.class.getDeclaredMethod("nearbyJoin", double[].class, int.class, int.class, int.class,
                boolean[].class, int.class, int[].class, int.class, List.class, double[].class);
        method.setAccessible(true); var p = grid.pipe(); boolean[] covered = new boolean[p.bed().length];
        int[] stamps = new int[covered.length]; var segments = new ArrayList<int[]>();
        for (int ti = 0; ti < sk.traces.size(); ti++) {
            var trace = sk.traces.get(ti);
            for (int i = 0; i + 1 < trace.cell.length; i++) if (trace.cell[i] == node) {
                var localCovered = covered.clone(); var localStamp = stamps.clone();
                for (int j = 0; j <= i; j++) { localCovered[trace.cell[j]] = true; localStamp[trace.cell[j]] = 1; }
                int join = (int) method.invoke(null, p.level(), node, p.nx(), p.nz(), localCovered,
                        windowParams(p).joinWindow(), localStamp, 1, segments, p.bed());
                var w = grid.world(node); var actual = grid.world(trace.cell[i + 1]);
                var raw = p.parent()[node] < 0 ? null : grid.world(p.parent()[node]); var target = join < 0 ? null : grid.world(join);
                csv.append(variant).append(',').append(ti).append(',').append(i).append(',').append(w.x()).append(',').append(w.z())
                        .append(',').append(actual.x()).append(',').append(actual.z()).append(',').append(raw == null ? "" : raw.x())
                        .append(',').append(raw == null ? "" : raw.z()).append(',').append(target == null ? "" : target.x())
                        .append(',').append(target == null ? "" : target.z()).append(',').append(join == trace.cell[i + 1])
                        .append(',').append(ti).append(',').append(i + 1).append('\n');
                log("X2 " + variant + " node=" + w + " trace=" + ti + " actual=" + actual + " raw=" + raw + " replayJoin=" + target
                        + " match=" + (join == trace.cell[i + 1]) + " terminalEdge=" + (i + 2 == trace.cell.length));
            }
            for (int k : trace.cell) covered[k] = true;
            for (int i = 1; i < trace.cell.length; i++) segments.add(new int[]{trace.cell[i - 1] / p.nz(), trace.cell[i - 1] % p.nz(),
                    trace.cell[i] / p.nz(), trace.cell[i] % p.nz()});
        }
    }

    private static void renderWindowCause(Path dir, WindowGrid grid, List<WindowEntry> entries,
                                          DeterministicSkeleton.Result[] variants, DeterministicSkeleton.Params noJoin) throws Exception {
        var base = javax.imageio.ImageIO.read(dir.resolve("production.png").toFile());
        int header = base.getHeight() - 3 * (2 * hp().fineRadius() + 1);
        String[] labels = {"A LOCAL", "B INCOMING", "C LOCAL NO NEARBY JOIN", "D INCOMING NO NEARBY JOIN"};
        var montage = new java.awt.image.BufferedImage(1410, 1480, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var mg = montage.createGraphics(); mg.setColor(java.awt.Color.BLACK); mg.fillRect(0, 0, 1410, 1480);
        for (int v = 0; v < variants.length; v++) {
            var image = new java.awt.image.BufferedImage(705, 705, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); g.drawImage(base, 0, 0, 705, 705, 0, header, base.getWidth(), base.getHeight(), null);
            g.setColor(v % 2 == 0 ? java.awt.Color.ORANGE : java.awt.Color.YELLOW);
            var shaped = DeterministicSkeleton.shape(variants[v].traces, grid.pipe().level(), grid.ox(), grid.oz(), grid.pipe().cellBlocks(),
                    grid.pipe().nx(), grid.pipe().nz(), v < 2 ? windowParams(grid.pipe()) : noJoin, grid.pipe().bed());
            for (var sh : shaped) for (int i = 1; i < sh.size(); i++) g.drawLine((int) Math.rint(sh.x[i - 1] - grid.ox()),
                    (int) Math.rint(sh.z[i - 1] - grid.oz()), (int) Math.rint(sh.x[i] - grid.ox()), (int) Math.rint(sh.z[i] - grid.oz()));
            g.setColor(java.awt.Color.RED); g.drawRect(-536 - grid.ox(), 344 - grid.oz(), 36, 36); g.dispose();
            javax.imageio.ImageIO.write(image, "png", dir.resolve("cause-" + v + ".png").toFile());
            int x = (v % 2) * 705, y = (v / 2) * 740;
            mg.setColor(java.awt.Color.WHITE); mg.drawString(labels[v] + " / CANDIDATE NOT WATER", x + 8, y + 15); mg.drawImage(image, x, y + 30, null);
        }
        mg.dispose(); javax.imageio.ImageIO.write(montage, "png", dir.resolve("cause-overall.png").toFile());
        log("X3 cause-overall1410x1480 raw705each contourProductionBase=true candidateNOTwater=true");
    }

    private static void windowConcurrency(WindowGrid grid, List<Integer> seeds, DeterministicSkeleton.Result expected) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<DeterministicSkeleton.Result>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> windowBuild(grid, seeds)));
            for (var job : jobs) chk(windowSame(expected, job.get()), "同一完整管线四消费者并发骨架同值（不是四次重管线）", "different");
        } finally { pool.shutdownNow(); }
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); windowBuild(grid, seeds); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && windowSame(expected, windowBuild(grid, seeds)), "入口预取消/重试同值；不证明骨架中途可取消", "different");
    }

    private static boolean windowSame(DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) {
        if (a.traces.size() != b.traces.size() || a.incomingStarts != b.incomingStarts || !a.exitSeeds.equals(b.exitSeeds)) return false;
        for (int i = 0; i < a.traces.size(); i++) if (a.traces.get(i).end != b.traces.get(i).end
                || a.traces.get(i).fromOutlet != b.traces.get(i).fromOutlet || !java.util.Arrays.equals(a.traces.get(i).cell, b.traces.get(i).cell)) return false;
        return true;
    }

    private static List<WindowPath> windowCompareRows(Path dir, WindowGrid owner, WindowGrid grid, List<WindowEntry> entries,
                                                     DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        var ga = windowGraph(a); var gb = windowGraph(b); var paths = new ArrayList<WindowPath>();
        var summary = new StringBuilder("sourceX,sourceZ,kind,fromX,fromZ,toX,toZ,sourceInside,entryCovered,AFirst,BFirst,ATerminal,BTerminal,firstParentMismatchX,firstParentMismatchZ,BNodes\n");
        var raw = new StringBuilder("sourceX,sourceZ,entryFromX,entryFromZ,x,z,parentX,parentZ,ownerParentEqual,resolved,lake,sea,level,localQ,ownerComparison\n");
        var edges = new java.util.TreeMap<String, java.util.SortedSet<String>>();
        for (var e : entries) {
            var ap = windowFollow(grid, ga, e); var bp = windowFollow(grid, gb, e); paths.add(bp);
            int k = grid.index(e.from()), mismatch = -1; var seen = new java.util.BitSet(grid.pipe().bed().length);
            while (k >= 0 && !seen.get(k)) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("window rows");
                seen.set(k); int next = grid.pipe().parent()[k]; var w = grid.world(k); int oi = owner.index(w);
                var n = next < 0 ? null : grid.world(next);
                boolean comparable = oi >= 0 && owner.pipe().parent()[oi] >= 0 && n != null;
                boolean same = comparable && owner.world(owner.pipe().parent()[oi]).equals(n);
                if (comparable && !same && mismatch < 0) mismatch = k;
                raw.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                        .append(',').append(w.x()).append(',').append(w.z()).append(',').append(n == null ? "" : n.x()).append(',').append(n == null ? "" : n.z())
                        .append(',').append(same).append(',').append(grid.pipe().resolved()[k]).append(',').append(grid.pipe().lake()[k])
                        .append(',').append(grid.pipe().sea()[k]).append(',').append(grid.pipe().level()[k]).append(',').append(grid.pipe().discharge()[k])
                        .append(',').append(oi < 0 ? "OWNER_OUTSIDE" : owner.pipe().parent()[oi] < 0 ? "OWNER_TERMINAL_OR_FRAME_UNKNOWN"
                                : n == null ? "COMMON_TERMINAL_OR_FRAME_UNKNOWN" : same ? "SAME" : "DIRECTION_DIVERGENCE").append('\n');
                if (grid.pipe().lake()[k] || grid.pipe().sea()[k]) break; k = next;
            }
            for (int i = 1; i < bp.cells().size(); i++) {
                var x = grid.world(bp.cells().get(i - 1)); var y = grid.world(bp.cells().get(i));
                String key = x.x() + "," + x.z() + "," + y.x() + "," + y.z();
                edges.computeIfAbsent(key, ignored -> new java.util.TreeSet<>()).add(e.source().x() + "," + e.source().z());
            }
            var diff = mismatch < 0 ? null : grid.world(mismatch);
            summary.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.kind()).append(',')
                    .append(e.from().x()).append(',').append(e.from().z()).append(',').append(e.to().x()).append(',').append(e.to().z()).append(',')
                    .append(grid.index(e.source()) >= 0).append(',').append(grid.index(e.from()) >= 0 && grid.index(e.to()) >= 0)
                    .append(',').append(ap.firstEdge()).append(',').append(bp.firstEdge()).append(',').append(ap.terminal()).append(',').append(bp.terminal())
                    .append(',').append(diff == null ? "" : diff.x()).append(',').append(diff == null ? "" : diff.z()).append(',').append(bp.cells().size()).append('\n');
            log("W3 source=" + e.source() + " entry=" + e.from() + "->" + e.to() + " sourceInside=" + (grid.index(e.source()) >= 0)
                    + " A=" + ap.firstEdge() + "/" + ap.terminal() + " B=" + bp.firstEdge() + "/" + bp.terminal() + " parentDivergence=" + diff);
        }
        var ledger = new StringBuilder("fromX,fromZ,toX,toZ,sourceX,sourceZ\n");
        edges.forEach((edge, sources) -> sources.forEach(source -> ledger.append(edge).append(',').append(source).append('\n')));
        Files.writeString(dir.resolve("obligations.csv"), summary); Files.writeString(dir.resolve("raw-parent-paths.csv"), raw);
        Files.writeString(dir.resolve("edge-source-ledger.csv"), ledger);
        windowPathRows(dir, grid, entries, ga, gb);
        chk(new LinkedHashSet<>(paths.stream().map(WindowPath::entry).toList()).equals(new LinkedHashSet<>(entries))
                        && paths.stream().allMatch(v -> grid.index(v.entry().from()) >= 0 && grid.index(v.entry().to()) >= 0
                        ? !v.cells().isEmpty() && v.cells().get(0) == grid.index(v.entry().from())
                        : v.cells().isEmpty() && v.terminal().equals("ENTRY_OUTSIDE_OR_UNALIGNED")),
                "源边身份及入口坐标逐项守恒；缺覆盖须明确未知且无假路径", "lost or malformed");
        return List.copyOf(paths);
    }

    private static void windowAttribution(Path dir, WindowGrid owner, WindowGrid grid, List<WindowEntry> entries,
                                          DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        var ga = windowGraph(a); var gb = windowGraph(b);
        var rows = new StringBuilder("sourceX,sourceZ,fromX,fromZ,toX,toZ,rawFirst,ownerRawFirst,AFirst,BFirst,localClassification,globalStatus\n");
        var counts = new java.util.TreeMap<String, Integer>();
        for (var e : entries) {
            int k = grid.index(e.from()), to = grid.index(e.to()), oi = owner.index(e.from());
            boolean covered = k >= 0 && to >= 0;
            boolean raw = covered && grid.pipe().parent()[k] == to;
            boolean ownerRaw = oi >= 0 && owner.pipe().parent()[oi] >= 0 && owner.world(owner.pipe().parent()[oi]).equals(e.to());
            var ap = windowFollow(grid, ga, e); var bp = windowFollow(grid, gb, e);
            String reason = !covered ? "ENTRY_UNKNOWN" : ap.firstEdge() && !bp.firstEdge() ? "B_ENTRY_COMPETITION_OR_TRACE_RULE_CHANGED_EDGE"
                    : !ap.firstEdge() && bp.firstEdge() ? "A_SELECTION_MISSING_B_SUPPORTS" : raw ? "RAW_FIRST_AND_TRACE_SUPPORTED"
                    : bp.firstEdge() ? "PROCESSED_EDGE_NOT_RAW_PARENT" : "RAW_OR_PROCESSED_DIRECTION_DIVERGENCE";
            counts.merge(reason, 1, Integer::sum);
            rows.append(e.source().x()).append(',').append(e.source().z()).append(',').append(e.from().x()).append(',').append(e.from().z())
                    .append(',').append(e.to().x()).append(',').append(e.to().z()).append(',').append(raw).append(',').append(ownerRaw)
                    .append(',').append(ap.firstEdge()).append(',').append(bp.firstEdge()).append(',').append(reason).append(",GLOBAL_UNCERTIFIED\n");
        }
        Files.writeString(dir.resolve("attribution.csv"), rows);
        log("W6 localAttribution=" + counts + " processedEdgesAreNotAlwaysParent=true globalAllPending=true");
    }

    private static void windowPathRows(Path dir, WindowGrid grid, List<WindowEntry> entries, WindowGraph a, WindowGraph b) throws Exception {
        var csv = new StringBuilder("variant,sourceX,sourceZ,entryX,entryZ,node,x,z,terminal,axis,boundary,alongTile\n");
        for (int v = 0; v < 2; v++) for (var e : entries) {
            var path = windowFollow(grid, v == 0 ? a : b, e);
            for (int i = 0; i < path.cells().size(); i++) {
                var w = grid.world(path.cells().get(i));
                var keys = i == 0 ? List.<SharedLakeDomain.Key>of() : windowEdgeKeys(grid.world(path.cells().get(i - 1)), w, hp().tract());
                String row = (v == 0 ? "A" : "B") + "," + e.source().x() + "," + e.source().z() + "," + e.from().x() + "," + e.from().z()
                        + "," + i + "," + w.x() + "," + w.z() + "," + path.terminal() + ",";
                if (keys.isEmpty()) csv.append(row).append(",,\n");
                else for (var key : keys) csv.append(row).append(key.axis()).append(',').append(key.boundary()).append(',').append(key.alongTile()).append('\n');
            }
        }
        Files.writeString(dir.resolve("published-paths.csv"), csv);
    }

    private static double windowSurface(WindowGrid grid, double x, double z) {
        double gx = (x - grid.ox()) / grid.pipe().cellBlocks(), gz = (z - grid.oz()) / grid.pipe().cellBlocks();
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz), n = grid.pipe().nz();
        if (ix < 0 || iz < 0 || ix + 1 >= grid.pipe().nx() || iz + 1 >= n) return Double.NaN;
        double fx = gx - ix, fz = gz - iz; double[] h = grid.pipe().waterSurface();
        return h[ix * n + iz] * (1 - fx) * (1 - fz) + h[(ix + 1) * n + iz] * fx * (1 - fz)
                + h[ix * n + iz + 1] * (1 - fx) * fz + h[(ix + 1) * n + iz + 1] * fx * fz;
    }

    private static void windowProfiles(Path dir, WindowGrid grid, DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        var csv = new StringBuilder("variant,trace,segment,x,z,toX,toZ,maxBedRise,maxSurfaceRise,bedFinite,surfaceFinite\n");
        int risingBed = 0, risingSurface = 0, invalid = 0;
        for (int v = 0; v < 2; v++) {
            var sk = v == 0 ? a : b;
            var shaped = DeterministicSkeleton.shape(sk.traces, grid.pipe().level(), grid.ox(), grid.oz(), grid.pipe().cellBlocks(),
                    grid.pipe().nx(), grid.pipe().nz(), windowParams(grid.pipe()), grid.pipe().bed());
            for (int t = 0; t < shaped.size(); t++) {
                var sh = shaped.get(t);
                for (int i = 1; i < sh.size(); i++) {
                    var bed = segmentProfile(sh.x[i - 1], sh.z[i - 1], sh.x[i], sh.z[i], 16,
                            (x, z) -> GEN.erodedHeight(x / TP.horizontalScale(), z / TP.horizontalScale()));
                    var water = segmentProfile(sh.x[i - 1], sh.z[i - 1], sh.x[i], sh.z[i], 16, (x, z) -> windowSurface(grid, x, z));
                    if (bed.maxSampleRise() > 1e-8) risingBed++; if (water.maxSampleRise() > 1e-8) risingSurface++;
                    if (!bed.finite() || !water.finite()) invalid++;
                    csv.append(v == 0 ? "A" : "B").append(',').append(t).append(',').append(i).append(',').append(sh.x[i - 1])
                            .append(',').append(sh.z[i - 1]).append(',').append(sh.x[i]).append(',').append(sh.z[i])
                            .append(',').append(bed.maxSampleRise()).append(',').append(water.maxSampleRise()).append(',')
                            .append(bed.finite()).append(',').append(water.finite()).append('\n');
                }
            }
        }
        Files.writeString(dir.resolve("shaped-profiles.csv"), csv);
        log("W5 finite16SamplesNotContinuousCertificate risingBedSegments=" + risingBed + " risingSurfaceSegments=" + risingSurface
                + " outsideOrNonfinite=" + invalid + " waterIsLocalPipelineNotCertifiedPublishedWater=true");
    }

    private static void renderWindowExperiment(Path dir, WindowGrid grid, List<WindowEntry> entries,
                                               DeterministicSkeleton.Result a, DeterministicSkeleton.Result b) throws Exception {
        int cx = -768, cz = 512, radius = hp().fineRadius();
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        var base = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), base, cx, cz, radius, "CURRENT PRODUCTION / WINDOW OBLIGATIONS NOT APPLIED");
        for (int v = 0; v < 2; v++) {
            var image = new java.awt.image.BufferedImage(base.getWidth(), base.getHeight(), base.getType());
            var graphics = image.createGraphics(); graphics.drawImage(base, 0, 0, null); graphics.setColor(v == 0 ? java.awt.Color.ORANGE : java.awt.Color.YELLOW);
            var sk = v == 0 ? a : b;
            var shapes = DeterministicSkeleton.shape(sk.traces, grid.pipe().level(), grid.ox(), grid.oz(), grid.pipe().cellBlocks(),
                    grid.pipe().nx(), grid.pipe().nz(), windowParams(grid.pipe()), grid.pipe().bed());
            for (var sh : shapes) for (int i = 1; i < sh.size(); i++) graphics.drawLine((int) Math.rint(sh.x[i - 1] - grid.ox()),
                    (int) Math.rint(sh.z[i - 1] - grid.oz()), (int) Math.rint(sh.x[i] - grid.ox()), (int) Math.rint(sh.z[i] - grid.oz()));
            graphics.setColor(java.awt.Color.GREEN);
            for (var e : entries) if (grid.index(e.from()) >= 0) graphics.fillRect(e.from().x() - grid.ox() - 2, e.from().z() - grid.oz() - 2, 5, 5);
            graphics.dispose(); writeLocalImage(dir.resolve(v == 0 ? "A-local.png" : "B-obligations.png"), image, cx, cz, radius,
                    v == 0 ? "ORANGE LOCAL WINDOW CANDIDATE / CYAN ACTUAL WATER / NOT NEW WATER" : "YELLOW SOURCE OBLIGATION CANDIDATE / GREEN ENTRY / CYAN WATER / NOT NEW WATER");
        }
        chk(mask.failCount == 0, "共同窗对照底图走实际provider无失败域", "failure=" + mask.failCount);
        Files.writeString(dir.resolve("manifest.txt"), "Fixed H512/t-2 r352 step4. One common raw pipeline; A and B share same arrays.\n"
                + "Actual published source obligations diagnostic ONLY, not independent headwater authority. No Q injection.\n"
                + "Frame/unknown/local sea/local lake are not global water certificates.\n" + LOG);
    }

    private static void windowSynthetic() throws Exception {
        int n = 9; double[] level = new double[n * n], bed = new double[n * n], q = new double[n * n];
        int[] parent = new int[n * n]; boolean[] resolved = new boolean[n * n], lake = new boolean[n * n], sea = new boolean[n * n];
        java.util.Arrays.fill(parent, -1); java.util.Arrays.fill(resolved, true);
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) { int k = x * n + z; level[k] = bed[k] = 100 - z;
            if (z + 1 < n) parent[k] = k + 1; sea[k] = z == n - 1; }
        var p = new HydrologyPipeline.Result(n, n, 4, bed, sea, resolved, level, q.clone(), lake, parent, q,
                new boolean[n * n], q.clone(), null, null, null, level.clone(), 0, true, 0);
        var g = new WindowGrid(p, -32, -16); int entry = 4 * n + 4;
        var source = new SharedBoundaryDrainage.WorldCell(-64, -64); var e = new WindowEntry(source, g.world(entry), g.world(entry + 1), "DIAGNOSTIC", 0);
        var entries = List.of(e, e); var params = new DeterministicSkeleton.Params(0, 2.88, 0, 0, 24, 0, 6, 0, 0);
        var a = DeterministicSkeleton.build(level, parent, resolved, lake, sea, q, null, n, n, params, List.of(), bed, false);
        var b = DeterministicSkeleton.build(level, parent, resolved, lake, sea, q, null, n, n, params, windowSeeds(g, entries), bed, false);
        var path = windowFollow(g, windowGraph(b), e);
        chk(g.index(source) < 0 && a.traces.isEmpty() && b.traces.size() == 1 && path.firstEdge()
                && path.terminal().equals("LOCAL_SEA_NOT_GLOBAL_CERTIFICATE"), "窗外源/窗内入口有效，低于独立门限不丢接力且不注Q", path.toString());
        chk(windowSeeds(g, entries).equals(List.of(entry)), "重复源边入口去重", "different");
        var reverse = new WindowEntry(source, g.world(entry + 1), g.world(entry), "DIAGNOSTIC", 0);
        chk(!windowFollow(g, windowGraph(b), reverse).firstEdge(), "相反方向不可冒充相同过界义务", "wrong");
        var outside = new WindowEntry(source, source, source, "DIAGNOSTIC", 0);
        chk(windowFollow(g, windowGraph(b), outside).terminal().equals("ENTRY_OUTSIDE_OR_UNALIGNED"), "缺入口保持未知，不判干/零流量", "wrong");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); windowSeeds(g, entries); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && windowSeeds(g, entries).equals(List.of(entry)), "取消无部分入口发布，重试同值", "missed");
        var id = new ProductionDrainageAudit.SegmentId(new ProductionDrainageAudit.Region(-1, -1), 0);
        var crossings = ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(-4, -4), new ProductionDrainageAudit.Point(4, 4), 16);
        chk(crossings.size() == 2 && crossings.stream().allMatch(c -> c.boundary() == 0), "负坐标角点两个轴事件，同世界边不是两次注入", "wrong");
        windowOwnershipSynthetic(g, params, entry, b);
        windowJoinSynthetic();
        successorSynthetic(g);
        gradientSynthetic();
        continuousDrySynthetic(g);
        stopAuditSynthetic();
        crestSynthetic(g);
        interfaceLedgerSynthetic();
        internalTransferSynthetic();
        localWaterSynthetic();
        conditionalCascadeSynthetic(g);
        storageSynthetic();
        crestLinkSynthetic();
        exactCrestSynthetic();
        chk(stopPlaneComponent(g,4*n+4).size()==n,"等水位分量遍历完整横行，不仅停止点四角","wrong");
    }

    private static void continuousDrySynthetic(WindowGrid original) {
        var p=original.pipe();
        var grid=new WindowGrid(new HydrologyPipeline.Result(p.nx(),p.nz(),p.cellBlocks(),p.bed(),new boolean[p.sea().length],
                p.resolved(),p.level(),p.depth(),p.lake(),p.parent(),p.discharge(),p.channel(),p.width(),p.lakeResult(),p.riverResult(),
                p.deathMask(),p.waterSurface(),p.couplingRounds(),p.couplingConverged(),p.couplingResidual()),original.ox(),original.oz());
        var source=grid.world(4*grid.pipe().nz()+4);boolean follows=true;
        for(int i=0;i<24;i++) {
            double angle=Math.toRadians(i*15+3),dx=Math.cos(angle),dz=Math.sin(angle);
            WindowOutlets.Terrain plane=(x,z)->100-dx*x-dz*z;
            var path=traceContinuousDry(grid,source,plane,1);follows &= path.points().size()>8;
            for(var point:path.points()) {
                double x=point.x()-source.x(),z=point.z()-source.z();follows &= Math.abs(x*dz-z*dx)<1e-7;
            }
            follows &= path.terminal().equals("UNKNOWN_FRAME");
        }
        chk(follows,"24旋转坡连续轨迹跟随解析下坡直线，不锁D8方向且窗框保持未知","wrong");
        WindowOutlets.Terrain bowl=(x,z)->(x-source.x()-2.3)*(x-source.x()-2.3)+(z-source.z()-3.7)*(z-source.z()-3.7);
        var trapped=traceContinuousDry(grid,source,bowl,1);
        var end=trapped.points().get(trapped.points().size()-1);
        chk(trapped.points().size()>2 && trapped.terminal().equals("NO_SAMPLED_DESCENT_MICROBASIN_PENDING")
                && Math.hypot(end.x()-source.x()-2.3,end.z()-source.z()-3.7)<0.02,
                "非网格浅盆非空轨迹保留未决，不删除盆地或跨脊补线",trapped.terminal());
        var flat=traceContinuousDry(grid,source,(x,z)->100,1);
        chk(flat.points().size()==1 && flat.terminal().equals("ZERO_GRADIENT_MICROBASIN_PENDING"),"零梯度无任意下游","wrong");
        int k=4*grid.pipe().nz()+4;double old=grid.pipe().level()[k];
        try {
            grid.pipe().level()[k]=grid.pipe().bed()[k]+1;
            var filled=traceContinuousDry(grid,source,(x,z)->100-z,1);
            chk(filled.points().size()==1 && filled.terminal().equals("FILLED_STENCIL_BASIN_PENDING"),"填平区不偷用地面梯度越过未认证盆地","wrong");
        } finally {grid.pipe().level()[k]=old;}
        boolean cancelled=false;
        try {Thread.currentThread().interrupt();traceContinuousDry(grid,source,(x,z)->100-z,1);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && trapped.equals(traceContinuousDry(grid,source,bowl,1)),"连续轨迹取消后重试完整同值，无部分发布","wrong");
        var outside=traceContinuousDry(grid,new SharedBoundaryDrainage.WorldCell(-999,-999),(x,z)->100-z,1);
        chk(outside.points().isEmpty() && outside.terminal().equals("SOURCE_OUTSIDE_CONTEXT_PENDING_NO_ENTRY_FORCED"),"窗外源保留身份未决，不从旧入口假装重算全源","wrong");
        var corner=dryCrossings(List.of(new DryPoint(-1,-1,10),new DryPoint(0,0,9),new DryPoint(1,1,8),new DryPoint(0,0,7),new DryPoint(-1,-1,6)),16);
        chk(corner.size()==4 && corner.get(0).direction()==1 && corner.get(2).direction()==-1,
                "连续过界负坐标角点双轴、端点和反向事件完整保留","wrong");
    }

    private static void successorSynthetic(WindowGrid grid) {
        var a = new SharedBoundaryDrainage.WorldCell(-100, -100); var b = new SharedBoundaryDrainage.WorldCell(-104, -100);
        var e = new WindowEntry(a, grid.world(1), grid.world(3), "SYNTH", 0);
        var f = new WindowEntry(b, grid.world(2), grid.world(3), "SYNTH", 1);
        var frozen = new WindowGraph(Map.of(1, List.of(3), 2, List.of(3), 3, List.of(4)), Map.of(4, "MOUTH_SEA"));
        var one = projectSuccessor(grid, frozen, List.of(e)); var both = projectSuccessor(grid, frozen, List.of(f, e, e));
        chk(one.paths().get(e).equals(both.paths().get(e)) && both.support().get(new SuccessorEdge(3, 4)).equals(Set.of(a, b))
                && both.equals(projectSuccessor(grid, frozen, List.of(e, f))), "冻结图：两源合流共边去重，加入另一源不改完整后缀", "different");
        var wrong = new WindowEntry(a, grid.world(1), grid.world(2), "SYNTH", 2);
        var mismatch = projectSuccessor(grid, frozen, List.of(wrong));
        chk(!mismatch.paths().get(wrong).firstEdge() && mismatch.support().isEmpty(), "旧捷径不匹配保持证据，不能强塞第一边或注入支持", "bridged");
        var cycle = new WindowGraph(Map.of(1, List.of(3), 3, List.of(1)), Map.of());
        var cyclic = projectSuccessor(grid, cycle, List.of(e));
        chk(cyclic.paths().get(e).terminal().equals("CYCLE") && cyclic.support().isEmpty(), "循环后继明确拒绝投影，不当成可出流支持", "wrong");
        boolean immutable = false;
        try { both.support().get(new SuccessorEdge(3, 4)).add(a); } catch (UnsupportedOperationException ex) { immutable = true; }
        chk(immutable, "后继支持源集合只读", "mutable");
        var graph = freezeWindowSuccessor(grid); int[] parents = grid.pipe().parent(); int old = parents[1];
        parents[1] = 7;
        chk(graph.next().get(1).equals(List.of(old)), "冻结图脱离输入数组，不读后续parent突变", "aliased");
        parents[1] = old;
        var r = new java.util.Random(0x51CC3550L); boolean oracle = true;
        for (int trial = 0; trial < 32; trial++) {
            var next = new HashMap<Integer, List<Integer>>();
            for (int k = 0; k < 63; k++) next.put(k, List.of(k + 1 + r.nextInt(63 - k)));
            var tree = new WindowGraph(Map.copyOf(next), Map.of(63, "MOUTH_SEA")); var requests = new ArrayList<WindowEntry>();
            for (int k = 0; k < 16; k++) { int from = r.nextInt(50);
                requests.add(new WindowEntry(new SharedBoundaryDrainage.WorldCell(-200 - k, -200), grid.world(from), grid.world(next.get(from).get(0)), "SYNTH", k)); }
            var projected = projectSuccessor(grid, tree, requests);
            for (var request : requests) {
                var expected = new ArrayList<Integer>(); int k = grid.index(request.from()); expected.add(k);
                while (k != 63) { k = next.get(k).get(0); expected.add(k); }
                oracle &= projected.paths().get(request).cells().equals(expected)
                        && projected.paths().get(request).terminal().equals("LOCAL_SEA_NOT_GLOBAL_CERTIFICATE");
            }
        }
        chk(oracle, "32随机汇流树×16源独立逐parent后缀oracle", "different");
        var raw = freezeWindowSuccessor(grid); var dry = dryWindowSuccessor(grid, raw); boolean descends = true;
        for (int k : dry.next().keySet()) if (!dry.next().get(k).equals(raw.next().get(k)))
            descends &= grid.pipe().level()[dry.next().get(k).get(0)] < grid.pipe().level()[k];
        chk(descends && dry.equals(dryWindowSuccessor(grid, raw)), "干坡候选仅严格level下降、重复构建同值且不消费源集合", "invalid");
        drySuccessorSynthetic(grid);
    }

    private static void drySuccessorSynthetic(WindowGrid grid) {
        var p = grid.pipe(); int k = 4*p.nz()+4, east = 5*p.nz()+4, south = k+1;
        double[] level = p.level().clone(), bed = p.bed().clone();
        try {
            p.level()[k] = p.bed()[k] = 10; p.level()[east] = p.bed()[east] = 9; p.level()[south] = p.bed()[south] = 8;
            var raw = freezeWindowSuccessor(grid); var next = new HashMap<>(raw.next()); next.put(k, List.of(east));
            var fixture = new WindowGraph(Map.copyOf(next), raw.ends()); var candidate = dryWindowSuccessor(grid, fixture);
            chk(candidate.next().get(k).equals(List.of(south)) && fixture.next().get(k).equals(List.of(east)),
                    "干坡非空反例：全图唯一后继由最大坡降选择，不改输入父图", "wrong");
            p.level()[south] = 9;
            chk(dryWindowSuccessor(grid, fixture).next().get(k).equals(List.of(east)), "最陡邻居为填平区时不偷换其路由", "changed");
            p.level()[south] = 8; boolean old = p.resolved()[k]; p.resolved()[k] = false;
            try { chk(dryWindowSuccessor(grid, fixture).next().get(k).equals(List.of(east)), "未知源格保持原后继，不以局部干坡冒充已解析", "changed"); }
            finally { p.resolved()[k] = old; }
            boolean cancelled = false;
            try { Thread.currentThread().interrupt(); dryWindowSuccessor(grid, fixture); }
            catch (java.util.concurrent.CancellationException ex) { cancelled = true; } finally { Thread.interrupted(); }
            chk(cancelled && candidate.equals(dryWindowSuccessor(grid, fixture)), "干坡图预取消后重试完整同值", "different");
        } finally { System.arraycopy(level, 0, p.level(), 0, level.length); System.arraycopy(bed, 0, p.bed(), 0, bed.length); }
    }

    private static void windowJoinSynthetic() throws Exception {
        int n = 7, k = 3 * n + 2, parent = 3 * n + 3, alternate = 2 * n + 4;
        double[] level = new double[n * n], bed = new double[n * n]; boolean[] covered = new boolean[n * n]; int[] stamps = new int[n * n];
        java.util.Arrays.fill(level, 20); java.util.Arrays.fill(bed, 20); level[k] = bed[k] = 10;
        level[parent] = bed[parent] = 9; level[alternate] = bed[alternate] = 8;
        var method = DeterministicSkeleton.class.getDeclaredMethod("nearbyJoin", double[].class, int.class, int.class, int.class,
                boolean[].class, int.class, int[].class, int.class, List.class, double[].class);
        method.setAccessible(true);
        int before = (int) method.invoke(null, level, k, n, n, covered, 2, stamps, 1, List.of(), bed);
        covered[alternate] = true;
        int after = (int) method.invoke(null, level, k, n, n, covered, 2, stamps, 1, List.of(), bed);
        int disabled = (int) method.invoke(null, level, k, n, n, covered, 0, stamps, 1, List.of(), bed);
        chk(before == -1 && after == alternate && disabled == -1 && parent != alternate,
                "真实nearbyJoin反例：只改covered即可抢在原parent前选择另一节点；关JOIN仅消融", "unexpected");
    }

    private static void windowOwnershipSynthetic(WindowGrid g, DeterministicSkeleton.Params params, int entry, DeterministicSkeleton.Result b) {
        var x = new SharedBoundaryDrainage.WorldCell(-4, -4); var y = new SharedBoundaryDrainage.WorldCell(4, 4);
        chk(windowEdgeKeys(x, y, 16).equals(windowEdgeKeys(y, x, 16)) && windowEdgeKeys(x, y, 16).size() == 2,
                "角点双轴键双侧同值，边方向单独保留", "different");
        var on = new SharedBoundaryDrainage.WorldCell(0, 4); var left = new SharedBoundaryDrainage.WorldCell(-4, 4);
        chk(windowEdgeKeys(on, left, 16).equals(windowEdgeKeys(left, on, 16)) && windowEdgeKeys(on, left, 16).size() == 1,
                "恰在边界起步反向离开仍登记，不因半开插值事件丢失", "different");
        var p = g.pipe(); double[] area = p.discharge().clone(); area[entry] = 10;
        var changed = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(), area, null,
                p.nx(), p.nz(), params, List.of(entry), p.bed(), false);
        chk(windowGraph(b).equals(windowGraph(changed)) && java.util.Arrays.stream(p.discharge()).allMatch(v -> v == 0),
                "同图同入口改变局部源准入不删义务，不给原图注Q", "different");
        var source = new SharedBoundaryDrainage.WorldCell(-32, 0);
        int[] cells = {g.index(source), g.index(new SharedBoundaryDrainage.WorldCell(-28, -4)),
                g.index(new SharedBoundaryDrainage.WorldCell(-24, 0)), g.index(new SharedBoundaryDrainage.WorldCell(-20, -4))};
        var multi = new DeterministicSkeleton.Result(List.of(new DeterministicSkeleton.Trace(cells, DeterministicSkeleton.End.DEAD_END, false)),
                1, 0, 0, 0, List.of(), false, 0);
        var events = windowEntries(g, multi, List.of(), 16);
        chk(events.size() == 3 && events.stream().allMatch(e -> e.source().equals(source)),
                "同源反向多次过缝保留三个有向世界边，不造插值节点", "events=" + events.size());
        var graph = new WindowGraph(Map.of(1, List.of(3), 2, List.of(3), 3, List.of(4)), Map.of(4, "MOUTH_SEA"));
        var sa = new SharedBoundaryDrainage.WorldCell(-80, -80); var sb = new SharedBoundaryDrainage.WorldCell(-84, -80);
        var pa = windowFollow(g, graph, new WindowEntry(sa, g.world(1), g.world(3), "DIAGNOSTIC", 0));
        var pb = windowFollow(g, graph, new WindowEntry(sb, g.world(2), g.world(3), "DIAGNOSTIC", 1));
        chk(pa.firstEdge() && pb.firstEdge() && pa.cells().subList(1, pa.cells().size()).equals(pb.cells().subList(1, pb.cells().size()))
                && graph.next().get(3).size() == 1, "合流两源共享唯一后缀边，源身份不变不重复注入", "different");
        var conflict = new WindowGraph(Map.of(1, List.of(2, 3)), Map.of());
        chk(windowFollow(g, conflict, new WindowEntry(sa, g.world(1), g.world(2), "DIAGNOSTIC", 0)).terminal().equals("MULTIPLE_DOWNSTREAM"),
                "多下游冲突明确未决，不用最近域优先级隐藏", "wrong");
        windowCrossingOracle();
        var key = new SharedLakeDomain.Key(1, 512, -2); var geom = SharedLakeDomain.geom(key, hp(), hp().step());
        chk(geom.originX() == -1120 && geom.originZ() == 160 && geom.halfSpan() == 352 && geom.step() == 4,
                "固定共同窗绝对归属，不随消费者与源位置移动", geom.toString());
    }

    private static void windowCrossingOracle() {
        var id = new ProductionDrainageAudit.SegmentId(new ProductionDrainageAudit.Region(-1, -1), 0);
        boolean equal = true; int tested = 0;
        for (int x = -36; x <= 36; x += 4) for (int z = -36; z <= 36; z += 4)
            for (int dx : new int[]{-4, 0, 4}) for (int dz : new int[]{-4, 0, 4}) {
                if (dx == 0 && dz == 0) continue;
                var a = new SharedBoundaryDrainage.WorldCell(x, z); var b = new SharedBoundaryDrainage.WorldCell(x + dx, z + dz);
                var keys = windowEdgeKeys(a, b, 16); var oracle = new java.util.TreeSet<SharedLakeDomain.Key>();
                for (int axis = 0; axis < 2; axis++) {
                    int av = axis == 0 ? x : z, bv = axis == 0 ? x + dx : z + dz;
                    for (int boundary = -48; boundary <= 48; boundary += 16) {
                        if ((av < boundary) == (bv < boundary)) continue;
                        double f = (boundary - (double) av) / (bv - (double) av);
                        double along = axis == 0 ? z + f * dz : x + f * dx;
                        oracle.add(new SharedLakeDomain.Key(axis, boundary, (int) Math.floor(along / 16)));
                    }
                }
                equal &= keys.equals(List.copyOf(oracle)); tested++;
                // Exact seam departures differ from the shaped-audit incoming-edge half-open convention.
                if (x % 16 != 0 && z % 16 != 0 && (x + dx) % 16 != 0 && (z + dz) % 16 != 0) {
                    var prod = new java.util.TreeSet<SharedLakeDomain.Key>();
                    for (var c : ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(x, z),
                            new ProductionDrainageAudit.Point(x + dx, z + dz), 16)) {
                        double along = c.axis() == 0 ? c.point().z() : c.point().x();
                        prod.add(new SharedLakeDomain.Key(c.axis(), c.boundary(), (int) Math.floor(along / 16)));
                    }
                    equal &= keys.equals(List.copyOf(prod));
                }
            }
        var crossA = new SharedBoundaryDrainage.WorldCell(-2, -2); var crossB = new SharedBoundaryDrainage.WorldCell(2, 2);
        var middle = ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(-2, -2), new ProductionDrainageAudit.Point(2, 2), 16);
        equal &= windowEdgeKeys(crossA, crossB, 16).equals(List.of(new SharedLakeDomain.Key(0, 0, 0), new SharedLakeDomain.Key(1, 0, 0)))
                && middle.size() == 2 && middle.stream().allMatch(c -> c.point().x() == 0 && c.point().z() == 0);
        chk(equal && tested == 2888, "负坐标多格独立边界侧谓词oracle及非缝端点production对拍", "tested=" + tested);
    }

    private static void worldContractSynthetic() throws Exception {
        log("\n=== [world-contract-synth] 唯一owner后继与源支持；候选不是水 ===");
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,16,4);
        var a=new SharedBoundaryDrainage.WorldCell(-4,-4);var b=new SharedBoundaryDrainage.WorldCell(0,0);
        var c=new SharedBoundaryDrainage.WorldCell(4,0);var other=new SharedBoundaryDrainage.WorldCell(0,-4);
        var west=new ProductionDrainageAudit.Region(-1,-1);var east=new ProductionDrainageAudit.Region(0,0);
        var nodeA=new SharedBoundaryDrainage.WorldNode(a,b,100,100,SharedBoundaryDrainage.WorldState.NEXT);
        var nodeB=new SharedBoundaryDrainage.WorldNode(b,c,99,99,SharedBoundaryDrainage.WorldState.NEXT);
        var nodeC=new SharedBoundaryDrainage.WorldNode(c,null,98,98,SharedBoundaryDrainage.WorldState.BASIN_PENDING);
        var mutable=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();mutable.put(b,nodeB);mutable.put(c,nodeC);
        var pa=new SharedBoundaryDrainage.WorldPiece(policy,west,Map.of(a,nodeA));
        var pb=new SharedBoundaryDrainage.WorldPiece(policy,east,mutable);mutable.clear();
        var src=new SharedBoundaryDrainage.WorldSource(new SharedBoundaryDrainage.WorldCell(-100,-100),a);
        var second=new SharedBoundaryDrainage.WorldSource(other,b);var pieces=List.of(pa,pb);
        var single=SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(src),64);
        var many=SharedBoundaryDrainage.projectWorld(policy,List.of(pb,pa,pa),List.of(second,src,src),64);
        chk(single.routes().get(src).path().equals(List.of(a,b,c)) && single.routes().get(src).equals(many.routes().get(src)),
                "角点跨双轴仍是一条世界边，窗外源保留；增删其他源不改后缀",single.toString());
        var merged=new SharedBoundaryDrainage.WorldEdge(b,c);
        chk(many.support().get(merged).equals(Set.of(src.identity(),second.identity())) && many.support().size()==2,
                "合流共边只记录去重源身份，不按来源重复创建边或注Q",many.toString());
        chk(single.equals(SharedBoundaryDrainage.projectWorld(policy,List.of(pb,pa),List.of(src),64)) && pb.nodes().size()==2,
                "片顺序不改路线且冻结输入Map，调用方后续清空无效","different");
        var absent=SharedBoundaryDrainage.projectWorld(policy,List.of(pa),List.of(src),64).routes().get(src);
        chk(absent.state()==SharedBoundaryDrainage.WorldState.OUTSIDE_INPUT && absent.path().equals(List.of(a)),
                "缺owner明确OUTSIDE_INPUT，不偷偷求邻fine或把框当海",absent.toString());
        var limited=SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(src),1).routes().get(src);
        chk(limited.state()==SharedBoundaryDrainage.WorldState.LIMIT && limited.path().size()==1,"路径预算耗尽不发布完整成功",limited.toString());
        boolean conflict=false,policyConflict=false;
        try { SharedBoundaryDrainage.projectWorld(policy,List.of(pa,new SharedBoundaryDrainage.WorldPiece(policy,west,Map.of())),List.of(src),64); }
        catch(IllegalArgumentException ex){conflict=true;}
        try { SharedBoundaryDrainage.projectWorld(new SharedBoundaryDrainage.WorldPolicy(9,2,1,16,4),pieces,List.of(src),64); }
        catch(IllegalArgumentException ex){policyConflict=true;}
        chk(conflict && policyConflict,"同owner不同内容及种子策略不一致均拒绝，不先到先得","missed");
        var cycleA=new SharedBoundaryDrainage.WorldPiece(policy,west,Map.of(a,new SharedBoundaryDrainage.WorldNode(a,b,100,100,SharedBoundaryDrainage.WorldState.NEXT)));
        var cycleB=new SharedBoundaryDrainage.WorldPiece(policy,east,Map.of(b,new SharedBoundaryDrainage.WorldNode(b,a,100,100,SharedBoundaryDrainage.WorldState.NEXT)));
        var cycle=SharedBoundaryDrainage.projectWorld(policy,List.of(cycleA,cycleB),List.of(src),64);
        chk(cycle.routes().get(src).state()==SharedBoundaryDrainage.WorldState.CYCLE && cycle.support().isEmpty(),"跨owner等水位回环不产生可用支持",cycle.toString());
        var uphill=new SharedBoundaryDrainage.WorldPiece(policy,east,Map.of(b,new SharedBoundaryDrainage.WorldNode(b,null,101,101,SharedBoundaryDrainage.WorldState.BASIN_PENDING)));
        var bad=SharedBoundaryDrainage.projectWorld(policy,List.of(pa,uphill),List.of(src),64);
        chk(bad.routes().get(src).state()==SharedBoundaryDrainage.WorldState.NONDESCENDING && bad.support().isEmpty(),"跨owner水位上升拒绝，不自动抬水",bad.toString());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {var jobs=new ArrayList<java.util.concurrent.Future<SharedBoundaryDrainage.WorldProjection>>();
            for(int i=0;i<4;i++)jobs.add(pool.submit(()->SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(src),64)));
            for(var job:jobs)chk(single.equals(job.get()),"世界后继投影四消费者同完整结果","different");
        } finally {pool.shutdownNow();}
        boolean cancelled=false;try {Thread.currentThread().interrupt();SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(src),64);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && single.equals(SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(src),64)),"取消无部分产物且重试同值","missed");
        worldContractLimits(policy,pa,pb,src,single);
        worldProductSynthetic(policy, pa, pb);
    }

    private static void worldProductSynthetic(SharedBoundaryDrainage.WorldPolicy policy,
                                               SharedBoundaryDrainage.WorldPiece pa, SharedBoundaryDrainage.WorldPiece pb) {
        var at = pa.nodes().keySet().iterator().next();
        var origin = new SharedBoundaryDrainage.WorldOrigin(at, 3, 2.88);
        var mutable = new ArrayList<>(List.of(origin));
        var admission = new SharedBoundaryDrainage.OriginPolicy(16,2.88,DeterministicSkeleton.VALLEY_TROUGH);
        var a = new SharedBoundaryDrainage.WorldProduct(pa, admission, mutable); mutable.clear();
        var b = new SharedBoundaryDrainage.WorldProduct(pb, admission, List.of());
        var result = SharedBoundaryDrainage.projectProducts(policy, List.of(a, b), 64);
        var source = new SharedBoundaryDrainage.WorldSource(at, at);
        chk(a.origins().equals(List.of(origin)) && result.routes().get(source).path().size() == 3
                        && result.equals(SharedBoundaryDrainage.projectProducts(policy, List.of(b, a, a), 64)),
                "源与后继同一owner产物冻结；接收片没有独立源也延续完整路径，重复反序同值", "different");
        int rejected = 0;
        for (var invalid : List.of(new SharedBoundaryDrainage.WorldOrigin(new SharedBoundaryDrainage.WorldCell(100,100),3,2.88),
                new SharedBoundaryDrainage.WorldOrigin(at,1,2.88), new SharedBoundaryDrainage.WorldOrigin(at,Double.NaN,2.88))) {
            try { new SharedBoundaryDrainage.WorldProduct(pa, admission, List.of(invalid)); }
            catch (IllegalArgumentException ex) { rejected++; }
        }
        try { SharedBoundaryDrainage.projectProducts(policy, List.of(a,new SharedBoundaryDrainage.WorldProduct(pa,admission,List.of())),64); }
        catch (IllegalArgumentException ex) { rejected++; }
        try { new SharedBoundaryDrainage.WorldProduct(pa,admission,List.of(origin,origin)); }
        catch (IllegalArgumentException ex) { rejected++; }
        for (var wrong : List.of(new SharedBoundaryDrainage.OriginPolicy(20,2.88,admission.valleyTrough()),
                new SharedBoundaryDrainage.OriginPolicy(16,2.88,!admission.valleyTrough()))) {
            try { SharedBoundaryDrainage.projectProducts(policy,List.of(a,new SharedBoundaryDrainage.WorldProduct(pb,wrong,List.of())),64); }
            catch (IllegalArgumentException ex) { rejected++; }
        }
        chk(rejected == 7, "外owner/不合准入/非有限/源集冲突/重复身份/尺度及谷槽政策均硬拒绝", "rejected=" + rejected);
        boolean immutable = false, cancelled = false;
        try { a.origins().clear(); } catch (UnsupportedOperationException ex) { immutable = true; }
        try { Thread.currentThread().interrupt(); SharedBoundaryDrainage.projectProducts(policy,List.of(a,b),64); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; } finally { Thread.interrupted(); }
        chk(immutable && cancelled && result.equals(SharedBoundaryDrainage.projectProducts(policy,List.of(a,b),64)),
                "源产物深只读、取消后不发布部分源，重试同值", "mutable/partial");
        worldProductAdmissionSynthetic();
    }

    private static void worldProductAdmissionSynthetic() {
        int n=9,cells=n*n;double[] h=new double[cells],q=new double[cells];int[] parent=new int[cells];
        boolean[] resolved=new boolean[cells],lake=new boolean[cells],sea=new boolean[cells];
        java.util.Arrays.fill(resolved,true);java.util.Arrays.fill(parent,-1);
        for(int x=0;x<n;x++)for(int z=0;z<n;z++){int k=x*n+z;h[k]=100-z;if(z+1<n)parent[k]=k+1;sea[k]=z==n-1;}
        q[4*n+4]=3;q[4*n+5]=3;
        var p=new HydrologyPipeline.Result(n,n,4,h,sea,resolved,h.clone(),new double[cells],lake,parent,q,
                new boolean[cells],new double[cells],null,null,null,h.clone(),0,true,0);
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,16,4);var owner=new ProductionDrainageAudit.Region(-1,-1);
        var params=new DeterministicSkeleton.Params(0,2.88,0,0,24,0,2,0,0);
        var product=SharedBoundaryDrainage.worldProduct(policy,owner,-24,-24,p,params);
        var id=new SharedBoundaryDrainage.WorldCell(-8,-8);
        chk(product.origins().equals(List.of(new SharedBoundaryDrainage.WorldOrigin(id,3,2.88))),
                "真实准入算法在固定owner上下文产一个源，已有上游格不另起源","origins="+product.origins());
        var selection=new DeterministicSkeleton.Params(100,2.88,8,5,24,1,100,20,30);
        chk(product.equals(SharedBoundaryDrainage.worldProduct(policy,owner,-24,-24,p,selection)),
                "spacing/JOIN/maxSources/minNodes等绘线旋钮不改变准入源产物","selection leaked");
        boolean shifted=false;try{SharedBoundaryDrainage.worldProduct(policy,owner,-20,-24,p,params);}
        catch(IllegalArgumentException ex){shifted=true;}
        q[4*n+4]=0;
        chk(shifted && product.origins().get(0).localDischarge()==3,
                "移动窗不得冒充固定owner源权威，输入Q后改也不污染已冻结源产物","shifted/aliased");
    }

    private static void worldContractLimits(SharedBoundaryDrainage.WorldPolicy policy,SharedBoundaryDrainage.WorldPiece pa,
            SharedBoundaryDrainage.WorldPiece pb,SharedBoundaryDrainage.WorldSource source,SharedBoundaryDrainage.WorldProjection result) {
        boolean sourceConflict=false,stateConflict=false;
        try {SharedBoundaryDrainage.projectWorld(policy,List.of(pa,pb),List.of(source,new SharedBoundaryDrainage.WorldSource(source.identity(),new SharedBoundaryDrainage.WorldCell(0,0))),64);}
        catch(IllegalArgumentException ex){sourceConflict=true;}
        try {new SharedBoundaryDrainage.WorldPiece(policy,pa.owner(),Map.of(source.entry(),new SharedBoundaryDrainage.WorldNode(source.entry(),null,100,100,SharedBoundaryDrainage.WorldState.CYCLE)));}
        catch(IllegalArgumentException ex){stateConflict=true;}
        chk(sourceConflict,"同一源身份不可在一次投影中声明不同入口","accepted contradictory source");
        chk(stateConflict,"输入片不能伪装遍历结果CYCLE/LIMIT等终态","accepted traversal state");
        int rejected=0;
        for(int bound:new int[]{0,4097})try {SharedBoundaryDrainage.projectWorld(policy,List.of(pa,pb),List.of(source),bound);}
        catch(IllegalArgumentException ex){rejected++;}
        try {SharedBoundaryDrainage.projectWorld(policy,java.util.Collections.nCopies(5,pa),List.of(source),64);}
        catch(IllegalArgumentException ex){rejected++;}
        try {SharedBoundaryDrainage.projectWorld(policy,List.of(pa),java.util.Collections.nCopies(4097,source),64);}
        catch(IllegalArgumentException ex){rejected++;}
        chk(rejected==4,"片数/源数/路径预算硬上限均拒绝，不依赖去重绕过","rejected="+rejected);
        int immutable=0;
        try {pa.nodes().clear();}catch(UnsupportedOperationException ex){immutable++;}
        try {result.routes().clear();}catch(UnsupportedOperationException ex){immutable++;}
        try {result.routes().get(source).path().clear();}catch(UnsupportedOperationException ex){immutable++;}
        try {result.support().clear();}catch(UnsupportedOperationException ex){immutable++;}
        try {result.support().values().iterator().next().clear();}catch(UnsupportedOperationException ex){immutable++;}
        chk(immutable==5,"片/路线/路径/边支持/源集合均深只读","immutable="+immutable);
        var at=new SharedBoundaryDrainage.WorldCell(0,0);var receiver=new SharedBoundaryDrainage.WorldSource(source.identity(),at);
        var suffix=SharedBoundaryDrainage.projectWorld(policy,List.of(pb),List.of(receiver),64).routes().get(receiver);
        chk(suffix.path().equals(result.routes().get(source).path().subList(1,3)) && suffix.state()==result.routes().get(source).state(),
                "接收方仅持自身片可从同世界入口重建相同后缀，不查源端Q","different");
        worldContractGridOracle();worldContractAggregateBudget();worldPlaneSynthetic();
    }

    private static void worldContractGridOracle() {
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,16,4);boolean valid=true;int tested=0;
        for(int tx:new int[]{-12,8})for(int tz:new int[]{-12,8}) {
            var maps=new HashMap<ProductionDrainageAudit.Region,Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>>();
            var sources=new ArrayList<SharedBoundaryDrainage.WorldSource>();var target=new SharedBoundaryDrainage.WorldCell(tx,tz);
            for(int x=-16;x<16;x+=4)for(int z=-16;z<16;z+=4) {
                var at=new SharedBoundaryDrainage.WorldCell(x,z);boolean end=at.equals(target);
                var next=end?null:new SharedBoundaryDrainage.WorldCell(x+4*Integer.signum(tx-x),z+4*Integer.signum(tz-z));
                double h=100+Math.abs(tx-x)+Math.abs(tz-z);
                var owner=new ProductionDrainageAudit.Region(Math.floorDiv(x,16),Math.floorDiv(z,16));
                maps.computeIfAbsent(owner,k->new HashMap<>()).put(at,new SharedBoundaryDrainage.WorldNode(at,next,h,h,
                        end?SharedBoundaryDrainage.WorldState.OCEAN_CANDIDATE:SharedBoundaryDrainage.WorldState.NEXT));
                sources.add(new SharedBoundaryDrainage.WorldSource(at,at));
            }
            var pieces=new ArrayList<SharedBoundaryDrainage.WorldPiece>();maps.forEach((owner,nodes)->pieces.add(new SharedBoundaryDrainage.WorldPiece(policy,owner,nodes)));
            var result=SharedBoundaryDrainage.projectWorld(policy,pieces,sources,64);
            for(var source:sources) {
                var route=result.routes().get(source);int length=Math.max(Math.abs(tx-source.entry().x()),Math.abs(tz-source.entry().z()))/4+1;
                valid &= route.path().size()==length && route.path().get(length-1).equals(target) && route.state()==SharedBoundaryDrainage.WorldState.OCEAN_CANDIDATE;tested++;
            }
            java.util.Collections.reverse(pieces);java.util.Collections.shuffle(sources,new java.util.Random(93));sources.addAll(List.copyOf(sources));
            valid &= result.equals(SharedBoundaryDrainage.projectWorld(policy,pieces,sources,64));
        }
        chk(valid && tested==256,"四象限64源×四个汇点解析步数/终点oracle，反序重复源不改结果","tested="+tested);
    }

    private static void worldContractAggregateBudget() {
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,512,1);var pieces=new ArrayList<SharedBoundaryDrainage.WorldPiece>();
        for(int rx=0;rx<2;rx++) {
            var nodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();
            for(int k=0;k<66000;k++) {var at=new SharedBoundaryDrainage.WorldCell(rx*512+k/512,k%512);
                nodes.put(at,new SharedBoundaryDrainage.WorldNode(at,null,100,100,SharedBoundaryDrainage.WorldState.INPUT_PENDING));}
            pieces.add(new SharedBoundaryDrainage.WorldPiece(policy,new ProductionDrainageAudit.Region(rx,0),nodes));
        }
        boolean nodeBudget=false,pairBudget=false;
        try {SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(),64);}catch(IllegalArgumentException ex){nodeBudget=true;}
        var longPolicy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,4096,1);
        var nodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();
        for(int x=0;x<4096;x++) {var at=new SharedBoundaryDrainage.WorldCell(x,0);boolean end=x==4095;
            nodes.put(at,new SharedBoundaryDrainage.WorldNode(at,end?null:new SharedBoundaryDrainage.WorldCell(x+1,0),5000-x,5000-x,
                    end?SharedBoundaryDrainage.WorldState.BASIN_PENDING:SharedBoundaryDrainage.WorldState.NEXT));}
        var sources=new ArrayList<SharedBoundaryDrainage.WorldSource>();
        for(int x=0;x<257;x++)sources.add(new SharedBoundaryDrainage.WorldSource(new SharedBoundaryDrainage.WorldCell(x,1),new SharedBoundaryDrainage.WorldCell(0,0)));
        try {SharedBoundaryDrainage.projectWorld(longPolicy,List.of(new SharedBoundaryDrainage.WorldPiece(longPolicy,new ProductionDrainageAudit.Region(0,0),nodes)),sources,4096);}
        catch(IllegalArgumentException ex){pairBudget=true;}
        chk(nodeBudget && pairBudget,"全片累计节点与源×边支持预算均硬拒绝，不返回部分发布","missed budget");
    }

    private static void secWorldContract(long seed) throws Exception {
        log("\n=== [world-contract] 固定owner原始后继、身份接续；不接生产 ===");
        long fine0=HierarchicalHydrology.Diag.FINE_SOLVES.get(),shared0=HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var policy=new SharedBoundaryDrainage.WorldPolicy(seed,GeoGenesisConfig.configFingerprint(),1,hp().tract(),hp().step());
        var pieces=new ArrayList<SharedBoundaryDrainage.WorldPiece>();var pipes=new ArrayList<HydrologyPipeline.Result>();
        var products=new ArrayList<SharedBoundaryDrainage.WorldProduct>();
        var sources=new ArrayList<SharedBoundaryDrainage.WorldSource>();var thresholdRows=new StringBuilder("sourceX,sourceZ,localQ,threshold\n");
        for(int rz=0;rz<=1;rz++) {
            var p=ownerSourcePipe(GEN,-2,rz,true);pipes.add(p);int ox=-2*hp().tract()-hp().halo(),oz=rz*hp().tract()-hp().halo();
            var owner=new ProductionDrainageAudit.Region(-2,rz);
            var product=SharedBoundaryDrainage.worldProduct(policy,owner,ox,oz,p,windowParams(p));products.add(product);pieces.add(product.piece());
            var oldIds=ownerCandidatePaths(p,-2,rz).stream().map(OwnerSourcePath::source).collect(java.util.stream.Collectors.toSet());
            chk(oldIds.equals(product.origins().stream().map(SharedBoundaryDrainage.WorldOrigin::identity).collect(java.util.stream.Collectors.toSet())),
                    "原准入算法与冻结源产物身份逐项相等，不受occupied/spacing/maxSources影响 rz="+rz,"different");
            for(var origin:product.origins()) {
                sources.add(new SharedBoundaryDrainage.WorldSource(origin.identity(),origin.identity()));
                thresholdRows.append(origin.identity().x()).append(',').append(origin.identity().z()).append(',').append(origin.localDischarge()).append(',').append(origin.threshold()).append('\n');
            }
        }
        chk(fine0==HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0==HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "两个显式固定owner只读地形/粗层，世界投影不消费邻finalfine/sharedfine","fanout");
        var result=SharedBoundaryDrainage.projectProducts(policy,products,4096);var shuffled=new ArrayList<>(sources);java.util.Collections.reverse(shuffled);shuffled.addAll(sources);
        chk(result.equals(SharedBoundaryDrainage.projectProducts(policy,List.of(products.get(1),products.get(0),products.get(0)),4096)),
                "真实调用方不再外拼源表，重复/反序原子产物完整路线同值","different");
        chk(result.equals(SharedBoundaryDrainage.projectWorld(policy,List.of(pieces.get(1),pieces.get(0)),shuffled,4096)),"真实源重复/反序和片反序不改完整后缀/支持","different");
        boolean same=true;for(var source:sources)same &= result.routes().get(source).equals(SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(source),4096).routes().get(source));
        chk(same && !sources.isEmpty(),"真实全部候选逐源投影同后缀，无covered竞争","different/empty");
        var fresh=newGen(TP,seed);var reversePieces=new ArrayList<SharedBoundaryDrainage.WorldPiece>();
        for(int rz=1;rz>=0;rz--) {
            var reverse=ownerSourcePipe(fresh,-2,rz,false);
            chk(sameSourcePipe(pipes.get(rz),reverse),"独立冷逆序无预热重建原始owner逐位一致 rz="+rz,"different");
            var reverseProduct=SharedBoundaryDrainage.worldProduct(policy,new ProductionDrainageAudit.Region(-2,rz),
                    -2*hp().tract()-hp().halo(),rz*hp().tract()-hp().halo(),reverse,windowParams(reverse));
            chk(products.get(rz).equals(reverseProduct),"冷逆序重建源/后继原子产物逐项相等 rz="+rz,"different");
            reversePieces.add(reverseProduct.piece());
        }
        chk(result.equals(SharedBoundaryDrainage.projectWorld(policy,reversePieces,shuffled,4096)),"独立冷逆序片的完整路线与源支持同值","different");
        var dir=OUT.resolve("world-contract-"+System.currentTimeMillis());Files.createDirectories(dir);
        auditWorldContinuations(policy,pieces,sources,result,pipes,dir);
        auditWorldPlanes(policy,pieces,reversePieces,result,dir);
        chk(fine0==HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0==HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "冷逆序/全部接收后缀验证后仍零finalfine/sharedfine求解","fanout");
        var nodes=new StringBuilder("ownerX,ownerZ,x,z,nextX,nextZ,bed,level,state\n");
        for(var piece:pieces)for(var node:piece.nodes().values())nodes.append(piece.owner().x()).append(',').append(piece.owner().z()).append(',')
                .append(node.point().x()).append(',').append(node.point().z()).append(',').append(node.next()==null?"":node.next().x()).append(',')
                .append(node.next()==null?"":node.next().z()).append(',').append(node.bed()).append(',').append(node.level()).append(',').append(node.state()).append('\n');
        var paths=new StringBuilder("sourceX,sourceZ,node,x,z,state\n");var ends=new java.util.TreeMap<SharedBoundaryDrainage.WorldState,Integer>();int crossing=0;
        for(var source:sources) {
            var route=result.routes().get(source);ends.merge(route.state(),1,Integer::sum);
            for(int i=0;i<route.path().size();i++){var at=route.path().get(i);paths.append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(i).append(',').append(at.x()).append(',').append(at.z()).append(',').append(route.state()).append('\n');}
            for(int i=1;i<route.path().size();i++)if(Math.floorDiv(route.path().get(i-1).z(),hp().tract())!=Math.floorDiv(route.path().get(i).z(),hp().tract()))crossing++;
        }
        Files.writeString(dir.resolve("nodes.csv"),nodes);Files.writeString(dir.resolve("paths.csv"),paths);Files.writeString(dir.resolve("sources.csv"),thresholdRows);
        var edgeRows=new StringBuilder("fromX,fromZ,toX,toZ,sources\n");
        result.support().forEach((edge,ids)->edgeRows.append(edge.from().x()).append(',').append(edge.from().z()).append(',').append(edge.to().x()).append(',').append(edge.to().z()).append(',').append(ids.size()).append('\n'));
        Files.writeString(dir.resolve("support.csv"),edgeRows);
        log("G33 WORLD sources="+sources.size()+" ownedNodes="+pieces.stream().mapToInt(p->p.nodes().size()).sum()+" supportEdges="+result.support().size()+" crossingEvents="+crossing+" terminals="+ends);
        for(var point:List.of(new SharedBoundaryDrainage.WorldCell(-712,512),new SharedBoundaryDrainage.WorldCell(-648,512),new SharedBoundaryDrainage.WorldCell(-580,512))) {
            var ids=new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();result.support().forEach((edge,set)->{if(edge.to().equals(point))ids.addAll(set);});
            log("G33 crossing="+point+" sourceSupport="+ids+" INDEPENDENT_RAW_CANDIDATE_NOT_WATER");
        }
        chk(result.routes().values().stream().noneMatch(r->r.state()==SharedBoundaryDrainage.WorldState.CYCLE || r.state()==SharedBoundaryDrainage.WorldState.NONDESCENDING),
                "真实已给两片无跨owner回环或水位上升（不是全球证书）",ends.toString());
        log("WORLD_CONTRACT_DIR="+dir.toAbsolutePath()+" EXPLICIT_RAW_PIPE_CALLS=4 GLOBAL_BASIN_AND_GEOMETRY_PENDING");
        // #region agent log: G33 世界身份接续阶段证据，生产默认不改
        Files.writeString(Path.of("..","debug-agent.log").toAbsolutePath(),LOG.toString(),java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void auditWorldContinuations(SharedBoundaryDrainage.WorldPolicy policy,List<SharedBoundaryDrainage.WorldPiece> pieces,
            List<SharedBoundaryDrainage.WorldSource> sources,SharedBoundaryDrainage.WorldProjection result,
            List<HydrologyPipeline.Result> pipes,Path dir) throws Exception {
        var rows=new StringBuilder("sourceX,sourceZ,fromX,fromZ,entryX,entryZ,suffixNodes,state,receiverQ,threshold\n");int tested=0;boolean same=true;
        for(var source:sources) {
            var route=result.routes().get(source);
            for(int i=1;i<route.path().size();i++) {
                var from=route.path().get(i-1);var at=route.path().get(i);
                int rx=Math.floorDiv(at.x(),policy.tract()),rz=Math.floorDiv(at.z(),policy.tract());
                if(Math.floorDiv(from.x(),policy.tract())==rx && Math.floorDiv(from.z(),policy.tract())==rz)continue;
                var receiver=new SharedBoundaryDrainage.WorldSource(source.identity(),at);
                var suffix=SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(receiver),4096).routes().get(receiver);
                same &= suffix.path().equals(route.path().subList(i,route.path().size())) && suffix.state()==route.state();tested++;
                var p=pipes.get(rz);int ox=rx*policy.tract()-hp().halo(),oz=rz*policy.tract()-hp().halo();
                int k=(at.x()-ox)/policy.step()*p.nz()+(at.z()-oz)/policy.step();
                rows.append(source.identity().x()).append(',').append(source.identity().z()).append(',').append(from.x()).append(',').append(from.z()).append(',')
                        .append(at.x()).append(',').append(at.z()).append(',').append(suffix.path().size()).append(',').append(suffix.state()).append(',')
                        .append(p.discharge()[k]).append(',').append(windowParams(p).minSourceAccum()).append('\n');
            }
        }
        Files.writeString(dir.resolve("continuations.csv"),rows);
        chk(same && tested>0,"真实全部跨owner入口重建相同完整后缀；续流不重套局部源门限 tested="+tested,"different/empty");
    }

    private static List<SharedBoundaryDrainage.WorldPiece> planeFixture(SharedBoundaryDrainage.WorldPolicy policy,
            Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode> nodes) {
        var groups=new HashMap<ProductionDrainageAudit.Region,Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>>();
        nodes.forEach((at,node)->groups.computeIfAbsent(new ProductionDrainageAudit.Region(Math.floorDiv(at.x(),policy.tract()),Math.floorDiv(at.z(),policy.tract())),k->new HashMap<>()).put(at,node));
        var pieces=new ArrayList<SharedBoundaryDrainage.WorldPiece>();groups.forEach((owner,map)->pieces.add(new SharedBoundaryDrainage.WorldPiece(policy,owner,map)));
        return pieces;
    }

    private static void worldPlaneSynthetic() {
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,16,4);var nodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();
        for(int x=-8;x<=8;x+=4)for(int z=-8;z<=8;z+=4) {
            var at=new SharedBoundaryDrainage.WorldCell(x,z);boolean inside=x>=-4 && x<=0 && z>=-4 && z<=0;double h=inside?100:110;
            nodes.put(at,new SharedBoundaryDrainage.WorldNode(at,null,inside?99:110,h,SharedBoundaryDrainage.WorldState.BASIN_PENDING));
        }
        var start=new SharedBoundaryDrainage.WorldCell(-4,-4);var east=new SharedBoundaryDrainage.WorldCell(0,0);
        var pieces=planeFixture(policy,nodes);var plane=SharedBoundaryDrainage.worldPlane(policy,pieces,start);
        chk(plane.members().size()==4 && plane.filledCells()==4 && plane.rim().size()==20 && plane.state()==SharedBoundaryDrainage.PlaneState.LOCAL_COMPLETE,
                "四owner角点等水位分量完整合并、20条有向rim全保留，不双计角点面积",plane.toString());
        java.util.Collections.reverse(pieces);
        chk(plane.equals(SharedBoundaryDrainage.worldPlane(policy,pieces,east)),"跨双轴从不同入口/片反序得到相同有界anchor与全部rim","different");
        var missing=pieces.stream().filter(p->p.owner().x()!=0 || p.owner().z()!=0).toList();
        var partial=SharedBoundaryDrainage.worldPlane(policy,missing,start);
        chk(partial.state()==SharedBoundaryDrainage.PlaneState.INPUT_PENDING && partial.rim().stream().anyMatch(r->r.state()==SharedBoundaryDrainage.WorldRimState.OUTSIDE_INPUT),
                "缺角片保留未知rim，临时anchor不可当全球盆地身份",partial.toString());
        var gate=new SharedBoundaryDrainage.WorldCell(4,0);
        nodes.put(gate,new SharedBoundaryDrainage.WorldNode(gate,null,90,90,SharedBoundaryDrainage.WorldState.INPUT_PENDING));
        var unknown=SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start);
        chk(unknown.state()==SharedBoundaryDrainage.PlaneState.INPUT_PENDING && unknown.rim().stream().noneMatch(r->r.edge().to().equals(gate) && r.state()==SharedBoundaryDrainage.WorldRimState.LOWER_CANDIDATE),
                "低数值但未解析的边优先未知，不伪装下降出口","promoted");
        nodes.put(gate,new SharedBoundaryDrainage.WorldNode(gate,null,90,90,SharedBoundaryDrainage.WorldState.BASIN_PENDING));
        var lower=SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start);
        chk(lower.rim().stream().filter(r->r.edge().to().equals(gate)).count()==2 && !lower.members().contains(gate),
                "不同水位盆地不误并，两条下降候选全保留但不选首口","merged/omitted");
        nodes.put(gate,new SharedBoundaryDrainage.WorldNode(gate,null,100,100,SharedBoundaryDrainage.WorldState.OCEAN_CANDIDATE));
        var sea=SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start);
        chk(!sea.members().contains(gate) && sea.rim().stream().anyMatch(r->r.state()==SharedBoundaryDrainage.WorldRimState.OCEAN_CANDIDATE),
                "等水位局部海候选单列，不当盆地成员或全球入海证书","merged");
        boolean cancelled=false;try {Thread.currentThread().interrupt();SharedBoundaryDrainage.worldPlane(policy,pieces,start);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        int immutable=0;try {plane.members().clear();}catch(UnsupportedOperationException ex){immutable++;}
        try {plane.rim().clear();}catch(UnsupportedOperationException ex){immutable++;}
        chk(cancelled && immutable==2 && plane.equals(SharedBoundaryDrainage.worldPlane(policy,pieces,start)),"分量取消无部分发布、重试同值、成员/rim只读","missed");
        nodes.put(gate,new SharedBoundaryDrainage.WorldNode(gate,null,100,Math.nextUp(100.0),SharedBoundaryDrainage.WorldState.BASIN_PENDING));
        chk(!SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start).members().contains(gate),
                "相邻浮点水位不按容差误并为同盆地","near-level merged");
        nodes.put(gate,new SharedBoundaryDrainage.WorldNode(gate,new SharedBoundaryDrainage.WorldCell(8,0),100,100,SharedBoundaryDrainage.WorldState.NEXT));
        var dry=SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start);
        chk(dry.members().contains(gate) && dry.members().size()==5 && dry.filledCells()==4,
                "同平面干岸仍纳入完整分量，不仅遍历可见淹没格","dry rim omitted");
        worldPlaneOracle(policy);
    }

    private static void worldPlaneOracle(SharedBoundaryDrainage.WorldPolicy policy) {
        var random=new java.util.Random(3401);boolean valid=true;
        for(int trial=0;trial<32;trial++) {
            var nodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();
            for(int x=-16;x<16;x+=4)for(int z=-16;z<16;z+=4) {var at=new SharedBoundaryDrainage.WorldCell(x,z);double h=100+random.nextInt(3);
                nodes.put(at,new SharedBoundaryDrainage.WorldNode(at,null,h-random.nextInt(2),h,SharedBoundaryDrainage.WorldState.BASIN_PENDING));}
            var start=new SharedBoundaryDrainage.WorldCell(-4,0);double level=nodes.get(start).level();var expected=new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();expected.add(start);
            boolean changed;do {changed=false;for(var at:nodes.keySet())if(nodes.get(at).level()==level && !expected.contains(at)) {
                for(var old:List.copyOf(expected))if(Math.abs(at.x()-old.x())<=4 && Math.abs(at.z()-old.z())<=4){expected.add(at);changed=true;break;}
            }}while(changed);
            var edges=new java.util.HashSet<SharedBoundaryDrainage.WorldRim>();
            for(var at:expected)for(int dx=-4;dx<=4;dx+=4)for(int dz=-4;dz<=4;dz+=4) {
                if(dx==0 && dz==0)continue;var to=new SharedBoundaryDrainage.WorldCell(at.x()+dx,at.z()+dz);if(expected.contains(to))continue;
                var other=nodes.get(to);var state=other==null?SharedBoundaryDrainage.WorldRimState.OUTSIDE_INPUT:
                        other.level()<level?SharedBoundaryDrainage.WorldRimState.LOWER_CANDIDATE:SharedBoundaryDrainage.WorldRimState.HIGHER;
                edges.add(new SharedBoundaryDrainage.WorldRim(new SharedBoundaryDrainage.WorldEdge(at,to),state));
            }
            var actual=SharedBoundaryDrainage.worldPlane(policy,planeFixture(policy,nodes),start);
            valid &= new java.util.HashSet<>(actual.members()).equals(expected) && new java.util.HashSet<>(actual.rim()).equals(edges)
                    && actual.filledCells()==expected.stream().filter(at->nodes.get(at).bed()<level).count();
            var wrong=new SharedBoundaryDrainage.WorldPolicy(2,2,1,16,4);boolean rejected=false;
            try {SharedBoundaryDrainage.worldPlane(wrong,planeFixture(policy,nodes),start);}catch(IllegalArgumentException ex){rejected=true;}
            valid &= rejected;
        }
        chk(valid,"32组四owner随机分量独立闭包/全部rim集合/填充计数oracle与策略隔离","different");
    }

    private static void auditWorldPlanes(SharedBoundaryDrainage.WorldPolicy policy,List<SharedBoundaryDrainage.WorldPiece> pieces,
            List<SharedBoundaryDrainage.WorldPiece> reverse,SharedBoundaryDrainage.WorldProjection projection,Path dir) throws Exception {
        var planes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldPlane>();
        var sourceRows=new StringBuilder("sourceX,sourceZ,entryX,entryZ,anchorX,anchorZ,state\n");boolean same=true;int attached=0;
        for(var entry:projection.routes().entrySet()) {
            var route=entry.getValue();if(route.state()!=SharedBoundaryDrainage.WorldState.BASIN_PENDING)continue;
            var stop=route.path().get(route.path().size()-1);var plane=SharedBoundaryDrainage.worldPlane(policy,pieces,stop);
            same &= plane.equals(SharedBoundaryDrainage.worldPlane(policy,reverse,stop));
            var old=planes.putIfAbsent(plane.anchor(),plane);same &= old==null || old.equals(plane);attached++;
            sourceRows.append(entry.getKey().identity().x()).append(',').append(entry.getKey().identity().z()).append(',').append(stop.x()).append(',').append(stop.z()).append(',')
                    .append(plane.anchor().x()).append(',').append(plane.anchor().z()).append(',').append(plane.state()).append('\n');
        }
        var summary=new StringBuilder("anchorX,anchorZ,level,state,members,filled,rim,lower,unknown\n");
        var members=new StringBuilder("anchorX,anchorZ,x,z\n");var rims=new StringBuilder("anchorX,anchorZ,fromX,fromZ,toX,toZ,state\n");
        for(var plane:planes.values()) {
            for(var at:plane.members())members.append(plane.anchor().x()).append(',').append(plane.anchor().z()).append(',').append(at.x()).append(',').append(at.z()).append('\n');
            for(var r:plane.rim())rims.append(plane.anchor().x()).append(',').append(plane.anchor().z()).append(',').append(r.edge().from().x()).append(',').append(r.edge().from().z()).append(',')
                    .append(r.edge().to().x()).append(',').append(r.edge().to().z()).append(',').append(r.state()).append('\n');
            long lower=plane.rim().stream().filter(r->r.state()==SharedBoundaryDrainage.WorldRimState.LOWER_CANDIDATE).count();
            long unknown=plane.rim().stream().filter(r->r.state()==SharedBoundaryDrainage.WorldRimState.OUTSIDE_INPUT || r.state()==SharedBoundaryDrainage.WorldRimState.INPUT_PENDING).count();
            summary.append(plane.anchor().x()).append(',').append(plane.anchor().z()).append(',').append(plane.level()).append(',').append(plane.state()).append(',')
                    .append(plane.members().size()).append(',').append(plane.filledCells()).append(',').append(plane.rim().size()).append(',').append(lower).append(',').append(unknown).append('\n');
            log("G34 PLANE anchor="+plane.anchor()+" level="+plane.level()+" state="+plane.state()+" cells="+plane.members().size()+" filled="+plane.filledCells()+" lowerEdges="+lower+" unknownEdges="+unknown);
        }
        Files.writeString(dir.resolve("planes.csv"),summary);Files.writeString(dir.resolve("plane-members.csv"),members);
        Files.writeString(dir.resolve("plane-rim.csv"),rims);Files.writeString(dir.resolve("plane-sources.csv"),sourceRows);
        chk(same && attached>0,"真实停盆所有源按等水位分量归并，冷逆序/不同入口完整rim同值 sources="+attached+" planes="+planes.size(),"different/empty");
        auditWorldRimReceivers(policy,pieces,planes,dir);
        auditWorldAttachments(policy,pieces,projection,planes,dir);
        replayInterfacePairs(dir);
    }

    private static void auditWorldAttachments(SharedBoundaryDrainage.WorldPolicy policy,List<SharedBoundaryDrainage.WorldPiece> pieces,
            SharedBoundaryDrainage.WorldProjection projection,Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldPlane> planes,Path dir) throws Exception {
        var rows=new StringBuilder("sourceX,sourceZ,entryX,entryZ,anchorX,anchorZ,level,actualHeight,cornerMin,cornerCost,state\n");
        var points=new StringBuilder("entryX,entryZ,node,x,z,height,east,south,cost,parent\n");
        var patches=new HashMap<SharedBoundaryDrainage.WorldCell,SubcellPatch>();var floods=new HashMap<SharedBoundaryDrainage.WorldCell,RingFlood>();
        var worldNodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldNode>();pieces.forEach(p->worldNodes.putAll(p.nodes()));
        WindowOutlets.Terrain terrain=(x,z)->{
            int cx=(int)Math.floor((x+policy.step()/2.0)/policy.step())*policy.step(),cz=(int)Math.floor((z+policy.step()/2.0)/policy.step())*policy.step();
            var owner=worldNodes.get(new SharedBoundaryDrainage.WorldCell(cx,cz));
            return owner==null || owner.state()==SharedBoundaryDrainage.WorldState.INPUT_PENDING?Double.NaN:GEN.erodedHeight(x/TP.horizontalScale(),z/TP.horizontalScale());
        };int above=0;
        for(var entry:projection.routes().entrySet()) {
            var route=entry.getValue();if(route.state()!=SharedBoundaryDrainage.WorldState.BASIN_PENDING)continue;
            var at=route.path().get(route.path().size()-1);var plane=planes.values().stream().filter(p->p.members().contains(at)).findFirst().orElseThrow();
            var patch=patches.get(at);
            if(patch==null) {
                var start=new DryPoint(at.x(),at.z(),terrain.height(at.x(),at.z()));double half=hp().step()/2.0;
                patch=denseCrestPatch(samplePortPatch(terrain,at.x()-half,at.z()-half,at.x()+half,at.z()+half,.25,start,start),terrain);
                int[] known=new int[patch.height().length];for(int k=0;k<known.length;k++)if(!Double.isFinite(patch.height()[k]))known[k]=-1;
                var flood=ringFlood(patch,known,0,List.of(patch.start()));patches.put(at,patch);floods.put(at,flood);
                for(int k=0;k<patch.height().length;k++)points.append(at.x()).append(',').append(at.z()).append(',').append(k).append(',')
                        .append(patch.ox()+k/patch.nz()*patch.step()).append(',').append(patch.oz()+k%patch.nz()*patch.step()).append(',').append(patch.height()[k]).append(',')
                        .append(patch.east()[k]).append(',').append(patch.south()[k]).append(',').append(flood.cost()[k]).append(',').append(flood.parent()[k]).append('\n');
            }
            var flood=floods.get(at);double min=Double.POSITIVE_INFINITY,cost=min;
            for(int k:new int[]{0,patch.nz()-1,(patch.nx()-1)*patch.nz(),patch.height().length-1})if(Double.isFinite(patch.height()[k])){min=Math.min(min,patch.height()[k]);cost=Math.min(cost,flood.cost()[k]);}
            double height=patch.height()[patch.start()];String state=height>plane.level()?"EXACT_ENTRY_ABOVE_PLANE":!Double.isFinite(cost)?"CELL_INPUT_PENDING":cost>plane.level()?"CELL_TRANSFER_ABOVE_PLANE":"SAMPLED_CELL_ATTACHMENT_ONLY";
            if(height>plane.level())above++;
            rows.append(entry.getKey().identity().x()).append(',').append(entry.getKey().identity().z()).append(',').append(at.x()).append(',').append(at.z()).append(',')
                    .append(plane.anchor().x()).append(',').append(plane.anchor().z()).append(',').append(plane.level()).append(',').append(height).append(',').append(min).append(',').append(cost).append(',').append(state).append('\n');
        }
        Files.writeString(dir.resolve("world-attachments.csv"),rows);Files.writeString(dir.resolve("world-attachment-grids.csv"),points);
        log("G35 ATTACH exactEntries="+patches.size()+" sourcesAbovePlane="+above+" EXACT_START_NOT_RELOCATED=true FIXED_CELL_PATCH_ONLY=true NOT_GLOBAL_OR_WATER_CERTIFICATE");
    }

    private static void replayInterfacePairs(Path dir) throws Exception {
        String input=System.getProperty("geogenesis.interfaceReplay");if(input==null)return;
        var observations=new ArrayList<InterfaceObservation>();var barriers=new HashMap<String,Double>();
        for(var row:interfaceCsv(Path.of(input))) {
            String id=row.get("component");double barrier=Double.parseDouble(row.get("barrier"));var old=barriers.putIfAbsent(id,barrier);
            if(old!=null && old!=barrier)throw new IllegalArgumentException("conflicting replay component barrier");
            String recipients=row.get("recipients");String rise=row.get("maxDenseRise");
            observations.add(new InterfaceObservation(id,row.get("worldEdgeQuarter"),Integer.parseInt(row.get("orientation")),Double.parseDouble(row.get("epsilon")),
                    Integer.parseInt(row.get("side")),recipients.isEmpty()?List.of():List.of(recipients.split("\\|")),row.get("terminal"),barrier,rise.isEmpty()?null:Double.parseDouble(rise)));
        }
        writeInterfaceComposition(dir,observations,barriers);
        log("G35 REPLAY observations="+observations.size()+" HISTORICAL_LOCAL_DIAGNOSTIC_ONLY_NOT_CURRENT_WORLD_SOURCE_SUPPORT=true");
    }

    private static void auditWorldRimReceivers(SharedBoundaryDrainage.WorldPolicy policy,List<SharedBoundaryDrainage.WorldPiece> pieces,
            Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryDrainage.WorldPlane> planes,Path dir) throws Exception {
        var summary=new StringBuilder("anchorX,anchorZ,fromX,fromZ,toX,toZ,level,maxTerrain,excess,state,terminalX,terminalZ,nodes\n");
        var profiles=new StringBuilder("anchorX,anchorZ,fromX,fromZ,toX,toZ,sample,x,z,terrain\n");
        var paths=new StringBuilder("anchorX,anchorZ,fromX,fromZ,toX,toZ,node,x,z,state\n");int count=0,blocked=0;
        for(var plane:planes.values())for(var rim:plane.rim()) {
            if(rim.state()!=SharedBoundaryDrainage.WorldRimState.LOWER_CANDIDATE)continue;
            var from=rim.edge().from();var to=rim.edge().to();double max=Double.NEGATIVE_INFINITY;
            String prefix=plane.anchor().x()+","+plane.anchor().z()+","+from.x()+","+from.z()+","+to.x()+","+to.z()+",";
            for(int i=0;i<=32;i++) {
                double x=from.x()+(to.x()-from.x())*i/32.0,z=from.z()+(to.z()-from.z())*i/32.0;
                double h=GEN.erodedHeight(x/TP.horizontalScale(),z/TP.horizontalScale());max=Math.max(max,h);
                profiles.append(prefix).append(i).append(',').append(x).append(',').append(z).append(',').append(h).append('\n');
            }
            var source=new SharedBoundaryDrainage.WorldSource(to,to);var route=SharedBoundaryDrainage.projectWorld(policy,pieces,List.of(source),4096).routes().get(source);
            var members=new java.util.HashSet<>(plane.members());String state=route.state().name();
            for(var at:route.path())if(members.contains(at)){state="SELF_RETURN";break;}
            var terminal=route.path().isEmpty()?to:route.path().get(route.path().size()-1);
            summary.append(prefix).append(plane.level()).append(',').append(max).append(',').append(max-plane.level()).append(',').append(state).append(',')
                    .append(terminal.x()).append(',').append(terminal.z()).append(',').append(route.path().size()).append('\n');
            for(int i=0;i<route.path().size();i++)paths.append(prefix).append(i).append(',').append(route.path().get(i).x()).append(',').append(route.path().get(i).z()).append(',').append(state).append('\n');
            count++;if(max>plane.level())blocked++;
        }
        Files.writeString(dir.resolve("plane-receivers.csv"),summary);Files.writeString(dir.resolve("plane-edge-profiles.csv"),profiles);Files.writeString(dir.resolve("plane-receiver-paths.csv"),paths);
        log("G34 RIM allLowerEdges="+count+" sampledTerrainAbovePlane="+blocked+" DENSE33_IS_NOT_CONTINUOUS_OR_GLOBAL_OUTFLOW_CERTIFICATE");
    }

    private static void secShapeAudit() throws Exception {
        // 水量口径的合成负控先行：它不依赖地形/world 分支，任何 seed 都必须绿。
        worldWaterGaugeSynthetic();
        var dir=OUT.resolve("shape-audit-"+System.currentTimeMillis());Files.createDirectories(dir);
        int rx=Integer.getInteger("geogenesis.shapeAuditRX",-2),rz=Integer.getInteger("geogenesis.shapeAuditRZ",1);
        int cx=rx*hp().tract()+hp().tract()/2,cz=rz*hp().tract()+hp().tract()/2;
        var p=PROV.pipeForProbe(cx,cz);var grid=new WindowGrid(p,cx-hp().fineRadius(),cz-hp().fineRadius());
        // #region agent log: G31 只读真实步进场，区别parent本身与flat改道的折返
        var world=PROV.worldForProbe(rx,rz);
        var field=new StringBuilder("cell,x,z,bed,level,parent,lake,sea,resolved\n");
        for(int k=0;k<p.parent().length;k++) {
            var w=grid.world(k);field.append(k).append(',').append(w.x()).append(',').append(w.z()).append(',').append(p.bed()[k])
                    .append(',').append(p.level()[k]).append(',').append(p.parent()[k]).append(',').append(p.lake()[k])
                    .append(',').append(p.sea()[k]).append(',').append(p.resolved()[k]).append('\n');
        }
        Files.writeString(dir.resolve("field.csv"),field);
        log("G31 SHAPE_FIELD region="+rx+","+rz+" nx="+p.nx()+" nz="+p.nz()+" directory="+dir.toAbsolutePath());
        // #endregion
        if (world != null) { shapeAuditWorld(dir, rx, rz, cx, cz, grid, world, PROV.worldWaterForProbe(rx, rz)); return; }
        var sk=windowBuild(grid,windowIncoming(rx,rz,grid));var actual=PROV.reachesForProbe(cx,cz);
        boolean same=actual.size()==sk.traces.size();for(int t=0;same && t<actual.size();t++)same=java.util.Arrays.equals(actual.get(t).cell,sk.traces.get(t).cell);
        chk(same,"梳齿区原始trace逐位等于实际provider，不另造河网","different");
        var shaped=DeterministicSkeleton.shape(sk.traces,p.level(),grid.ox(),grid.oz(),p.cellBlocks(),p.nx(),p.nz(),windowParams(p),p.bed());
        var rows=new StringBuilder("trace,node,cell,x,z,bed,level,parent,lake,sea,resolved,end\n");
        var points=new StringBuilder("trace,node,x,z,end\n");var joins=new StringBuilder("trace,mouthCell,x,z,ownerTrace,minDistanceToOwner,end\n");
        var owner=new java.util.HashMap<Integer,Integer>();for(int t=0;t<sk.traces.size();t++)for(int k:sk.traces.get(t).cell)owner.putIfAbsent(k,t);
        int turns=0,combTurns=0,joinCount=0,selfCrossings=0;double maxGap=0;
        for(int t=0;t<sk.traces.size();t++) {
            var tr=sk.traces.get(t);var sh=shaped.get(t);
            for(int i=1;i<tr.cell.length;i++)for(int j=i+2;j<tr.cell.length;j++) {
                var a=grid.world(tr.cell[i-1]);var b=grid.world(tr.cell[i]);var c=grid.world(tr.cell[j-1]);var d=grid.world(tr.cell[j]);
                double ux=b.x()-a.x(),uz=b.z()-a.z(),vx=d.x()-c.x(),vz=d.z()-c.z(),den=ux*vz-uz*vx;
                if(den==0)continue;double f=((c.x()-a.x())*vz-(c.z()-a.z())*vx)/den,g=((c.x()-a.x())*uz-(c.z()-a.z())*ux)/den;
                if(f>0 && f<1 && g>0 && g<1){selfCrossings++;log("G30 SELF_CROSS trace="+t+" edges="+i+":"+j+" source="+grid.world(tr.sourceCell()));}
            }
            for(int i=0;i<tr.cell.length;i++) {
                int k=tr.cell[i];var w=grid.world(k);rows.append(t).append(',').append(i).append(',').append(k).append(',').append(w.x()).append(',').append(w.z())
                        .append(',').append(p.bed()[k]).append(',').append(p.level()[k]).append(',').append(p.parent()[k]).append(',').append(p.lake()[k])
                        .append(',').append(p.sea()[k]).append(',').append(p.resolved()[k]).append(',').append(tr.end).append('\n');
                if(i>0 && i+1<tr.cell.length) {
                    var a=grid.world(tr.cell[i-1]);var b=grid.world(tr.cell[i+1]);
                    if((w.x()-a.x())*(b.x()-w.x())+(w.z()-a.z())*(b.z()-w.z())<0){turns++;if(w.x()>=-700 && w.x()<=-580 && w.z()>=617 && w.z()<=737)combTurns++;}
                }
            }
            for(int i=0;i<sh.size();i++)points.append(t).append(',').append(i).append(',').append(sh.x[i]).append(',').append(sh.z[i]).append(',').append(tr.end).append('\n');
            int own=owner.getOrDefault(tr.mouthCell(),-1);
            if(tr.end==DeterministicSkeleton.End.JOIN && own>=0 && own!=t && sh.size()>0) {
                var target=shaped.get(own);double gap=Double.POSITIVE_INFINITY;
                for(int i=1;i<target.size();i++)gap=Math.min(gap,shapeSegmentDistance(sh.x[sh.size()-1],sh.z[sh.size()-1],target.x[i-1],target.z[i-1],target.x[i],target.z[i]));
                var w=grid.world(tr.mouthCell());joins.append(t).append(',').append(tr.mouthCell()).append(',').append(w.x()).append(',').append(w.z()).append(',').append(own).append(',').append(gap).append(',').append(tr.end).append('\n');
                maxGap=Math.max(maxGap,gap);joinCount++;
            }
        }
        Files.writeString(dir.resolve("raw.csv"),rows);Files.writeString(dir.resolve("shaped.csv"),points);Files.writeString(dir.resolve("joins.csv"),joins);
        chk(selfCrossings==0,"真实梳齿区单链不得严格自交","crossings="+selfCrossings);
        if(rx==-2 && rz==1) {
            var historical=sk.traces.stream().filter(t->grid.world(t.sourceCell()).equals(new SharedBoundaryDrainage.WorldCell(-680,668))).findFirst().orElse(null);
            chk(historical!=null && historical.end==DeterministicSkeleton.End.MOUTH_SEA
                    && grid.world(historical.mouthCell()).equals(new SharedBoundaryDrainage.WorldCell(-620,760)),
                    "历史自交源保留且抵达原海口，不靠删源通过","missing or terminal changed");
        }
        log("G31 region="+rx+","+rz+" rawTraces="+sk.traces.size()+" rawObtuseTurns="+turns+" combBoxObtuseTurns="+combTurns+" selfCrossings="+selfCrossings+" joins="+joinCount+" maxJoinGeometryGap="+maxGap+" SHAPE_AUDIT_DIR="+dir.toAbsolutePath());
        Files.writeString(Path.of("..","debug-agent.log").toAbsolutePath(),LOG.toString(),java.nio.file.StandardOpenOption.APPEND);
    }

    private static double shapeSegmentDistance(double x,double z,double ax,double az,double bx,double bz) {
        double dx=bx-ax,dz=bz-az,len=dx*dx+dz*dz,t=len==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(z-az)*dz)/len));
        return Math.hypot(x-ax-t*dx,z-az-t*dz);
    }

    private static void secTraceLoops() throws Exception {
        log("\n=== [trace-loops] M本链改道回流/N真实JOIN/生产原参数重建 ===");
        var rows = new StringBuilder("rx,rz,trace,sourceX,sourceZ,mouthX,mouthZ,end,repeats,terminal\n");
        int worldEdgesTotal = 0, worldRegionsChecked = 0;
        for (int[] region : new int[][]{{-2, 0}, {-2, 1}, {-1, 0}, {-1, -1}, {0, -1}}) {
            int cx = region[0] * hp().tract() + hp().tract() / 2, cz = region[1] * hp().tract() + hp().tract() / 2;
            int ox = cx - hp().fineRadius(), oz = cz - hp().fineRadius();
            var world = PROV.worldForProbe(region[0], region[1]);
            if (world != null) {
                worldEdgesTotal += traceLoopsWorld(region, cx, cz, ox, oz, world, rows);
                worldRegionsChecked++;
                continue;
            }
            var p = PROV.pipeForProbe(cx, cz);
            var incomingMethod = ModelHydrologyProvider.class.getDeclaredMethod("hierIncomingSeeds", int.class, int.class, int.class, int.class, int.class);
            incomingMethod.setAccessible(true);
            @SuppressWarnings("unchecked") var incoming = (List<Integer>) incomingMethod.invoke(PROV, region[0], region[1], p.nx(), ox, oz);
            int spacing = Math.max(1, Math.round(Integer.getInteger("geogenesis.sourceSpacing", 24) / (float) hp().step()));
            int ratio = Math.max(1, 8 / hp().step());
            var params = new DeterministicSkeleton.Params(spacing,
                    Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150")) * p.lakeResult().runoffPerCell(),
                    2, Double.parseDouble(System.getProperty("geogenesis.skeletonMeanderAmp", "0")), 24,
                    Integer.getInteger("geogenesis.skeletonMaxRivers", 0), Integer.getInteger("geogenesis.skeletonMinNodes", 3 * ratio),
                    Integer.getInteger("geogenesis.skeletonSelfRadius", 0), Integer.getInteger("geogenesis.skeletonSelfExempt", 6 * ratio));
            var sk = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(), p.discharge(),
                    p.lakeResult().basinOutlet(), p.nx(), p.nz(), params, incoming, p.bed(), false);
            var actual = PROV.reachesForProbe(cx, cz);
            boolean same = actual.size() == sk.traces.size();
            for (int i = 0; same && i < actual.size(); i++) same = java.util.Arrays.equals(actual.get(i).cell, sk.traces.get(i).cell);
            chk(same, "真实参数重建逐trace同实际provider region=" + java.util.Arrays.toString(region), "different");
            var shaped = DeterministicSkeleton.shape(sk.traces, p.level(), ox, oz, hp().step(), p.nx(), p.nz(), params, p.bed());
            var audit = ProductionDrainageAudit.capture(p, sk, shaped, ox, oz, hp().tract());
            int loops = 0, repeats = 0;
            for (int t = 0; t < sk.traces.size(); t++) {
                var trace = sk.traces.get(t); var seen = new java.util.HashSet<Integer>(); int count = 0;
                for (int cell : trace.cell) if (!seen.add(cell)) count++;
                if (count > 0) loops++; repeats += count;
                var a = audit.segments().get(t); var chain = audit.chains().get(t);
                rows.append(region[0]).append(',').append(region[1]).append(',').append(t).append(',').append(a.source().x())
                        .append(',').append(a.source().z()).append(',').append(a.mouth().x()).append(',').append(a.mouth().z())
                        .append(',').append(trace.end).append(',').append(count).append(',').append(chain.terminal()).append('\n');
                if (count > 0) log("M1 SELF_TRACE region=" + java.util.Arrays.toString(region) + " trace=" + t
                        + " end=" + trace.end + " repeated=" + count + " mouth=" + a.mouth() + " chain=" + chain.terminal());
            }
            var terminals = new java.util.TreeMap<String, Integer>();
            for (var chain : audit.chains()) terminals.merge(chain.terminal().name(), 1, Integer::sum);
            log("M2 region=" + java.util.Arrays.toString(region) + " traces=" + sk.traces.size() + " selfTraces=" + loops
                    + " repeats=" + repeats + " terminals=" + terminals);
            chk(loops == 0, "已发布trace不得把自己当下游JOIN region=" + java.util.Arrays.toString(region), "loops=" + loops);
        }
        if (worldRegionsChecked > 0)
            chk(worldEdgesTotal > 0, "trace-loops：世界链实际边判据非空转（regions=" + worldRegionsChecked
                    + "）", "worldEdges=0");
        else log("M2 WORLD_CHAIN_ABSENT（worldForProbe 全 null ⇒ 走旧 window 判据，非世界链）");
        Files.writeString(OUT.resolve("trace-loops.csv"), rows);
        // #region agent log: M/N bounded production trace evidence, retain until user confirms
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    /**
     * 世界链的 trace-loops 分支：实际边就是生产发布单元，故「把自己当下游」只可能退化为
     * 【自环/重复边】。判据 = 无环、后继非自身、边↔reach 一一对应、canonical routing 一致、
     * 上坡仅限盆内蓄水路由；待决盆地产物如实登记（不制造湖终点）。
     * <p>⚠ 盆内蓄水路由已批准 ⇒ <b>不再</b>要求 raw D8 严格下降。
     */
    private static int traceLoopsWorld(int[] region, int cx, int cz, int ox, int oz,
                                        WorldDrainage.Result world, StringBuilder rows) {
        var grid = new WindowGrid(PROV.pipeForProbe(cx, cz), ox, oz);
        var reaches = PROV.reachesForProbe(cx, cz);
        var graph = independentDrainage();
        var chain = worldChain(graph, hp().step(), world);
        var water = PROV.worldWaterForProbe(region[0], region[1]);
        var audit = auditWorldEdges(chain, world, water,
                reaches, grid, region[0], region[1], hp().tract());
        int selfEdge = 0;
        for (int e = 0; e < world.edges().size(); e++) {
            var edge = world.edges().get(e);
            if (edge.from().equals(edge.to())) selfEdge++;
            rows.append(region[0]).append(',').append(region[1]).append(',').append(e).append(',')
                    .append(edge.from().x()).append(',').append(edge.from().z()).append(',')
                    .append(edge.to().x()).append(',').append(edge.to().z()).append(',')
                    .append(graph.at(edge.to()).state()).append(",0,")
                    .append(graph.at(edge.to()).state()).append('\n');
        }
        log("M2 WORLD region=" + java.util.Arrays.toString(region) + " edges=" + audit.edges()
                + " reaches=" + audit.reaches() + " mapped=" + (audit.edges() - audit.unmapped())
                + " selfEdge=" + selfEdge + " cycles=" + audit.cycles()
                + " rising=" + audit.rising() + " explainedRising=" + audit.explainedRising()
                + " unexplainedRising=" + audit.unexplainedRising() + " flat=" + audit.flat()
                + " routingMismatch=" + audit.routingMismatch() + " routingUnresolved=" + audit.routingUnresolved()
                + " rawMismatch(refOnly)=" + audit.rawMismatch()
                + " basins=" + audit.basins() + " lipDefects=" + audit.lipDefects()
                + " waterChecked=" + audit.waterChecked() + " waterDefects=" + audit.waterDefects()
                + " waterUnknown=" + audit.waterUnknown()
                + " waterAtLeast=" + audit.waterAtLeast()
                + " waterAtLeastBelowT=" + audit.waterAtLeastBelowThreshold()
                + " waterExactAtOrAboveT=" + audit.waterExactAtOrAbove()
                + " waterExactBelowT(allowed)=" + audit.waterExactBelowThreshold()
                + " waterBelowSea(stat)=" + audit.waterBelowSea()
                + " waterIdentity=" + audit.waterIdentity()
                + " waterIdentityUnresolved=" + audit.waterIdentityUnresolved()
                + " waterContractLeak=" + audit.waterContractLeak()
                + " waterPublishedMissing=" + audit.waterPublishedMissing()
                + " waterPublishedBelowT=" + audit.waterPublishedBelowThreshold()
                + " pending=" + audit.pending() + " pendingMismatch=" + audit.pendingMismatch()
                + " belowSea=" + audit.terminated()
                + " WATER_AUDIT_ALL_CELLS_KNOWN=true"
                + " WATER_DEMAND_ONLY_FOR_AT_LEAST_AND_PUBLISHED_FROM=true"
                + " WATER_SURFACE_GRADIENT_NOT_PROVEN=true");
        chk(audit.reaches() == audit.edges() && audit.unmapped() == 0 && audit.unclaimed() == 0,
                "世界链 region=" + java.util.Arrays.toString(region)
                        + " 实际边↔reach 一一对应（按 cellA/cellB 配对，不依赖发布顺序）",
                "edges=" + audit.edges() + " reaches=" + audit.reaches()
                        + " unmapped=" + audit.unmapped() + " unclaimed=" + audit.unclaimed());
        chk(selfEdge == 0 && audit.duplicated() == 0, "世界链：无自环边、无重复 from 端点"
                + " region=" + java.util.Arrays.toString(region), "selfEdge=" + selfEdge + " dup=" + audit.duplicated());
        chk(audit.routingMismatch() == 0 && audit.routingUnresolved() == 0,
                "世界链：发布边逐条等于真实 canonical routing 后继 region=" + java.util.Arrays.toString(region),
                "mismatch=" + audit.routingMismatch() + " unresolved=" + audit.routingUnresolved()
                        + " rawMismatch(refOnly)=" + audit.rawMismatch());
        chk(audit.cycles() == 0 && audit.unexplainedRising() == 0,
                "世界链：实际边独立遍历无环、上坡仅限盆内蓄水路由且该跳整数列 crest ≤ sill"
                        + " region=" + java.util.Arrays.toString(region),
                "cycles=" + audit.cycles() + " unexplainedRising=" + audit.unexplainedRising()
                        + " rising=" + audit.rising() + " flat=" + audit.flat());
        chk(audit.lipDefects() == 0 && audit.identityConflicts() == 0,
                "世界链：成员盆 identity 唯一且 lip 齐备 region=" + java.util.Arrays.toString(region),
                "lipDefects=" + audit.lipDefects() + " identityConflicts=" + audit.identityConflicts());
        chk(audit.waterChecked() > 0 && audit.waterDefects() == 0,
                "世界链：water() 每格 known、AT_LEAST 皆达 threshold、湖盆身份独立核对、发布边 from 达门限"
                        + "（EXACT 低于 threshold 合法，只统计）region=" + java.util.Arrays.toString(region),
                "waterChecked=" + audit.waterChecked() + " waterDefects=" + audit.waterDefects()
                        + " waterUnknown=" + audit.waterUnknown()
                        + " waterAtLeastBelowT=" + audit.waterAtLeastBelowThreshold()
                        + " waterIdentity=" + audit.waterIdentity()
                        + " waterIdentityUnresolved=" + audit.waterIdentityUnresolved()
                        + " waterContractLeak=" + audit.waterContractLeak()
                        + " waterPublishedMissing=" + audit.waterPublishedMissing()
                        + " waterPublishedBelowT=" + audit.waterPublishedBelowThreshold());
        chk(audit.pendingMismatch() == 0, "世界链：待决盆地如实为 BASIN_PENDING/有准入且在 core 内"
                + " region=" + java.util.Arrays.toString(region), "pendingMismatch=" + audit.pendingMismatch());
        if (audit.edges() == 0) log("M2 WORLD_VACUOUS region=" + java.util.Arrays.toString(region)
                + "（本区无准入跨区边 ⇒ 只记映射等式，不声称为非空转证据）");
        // 负控只在 shape-audit 跑一次（两分支共用 auditWorldEdges，重复跑会把本段成本抬到 3 倍）。
        return audit.edges();
    }

    private static HydrologyPipeline.Result ownerSourcePipe(CellGenerator gen, int rx, int rz, boolean prewarm) {
        int cx = rx * hp().tract() + hp().tract() / 2, cz = rz * hp().tract() + hp().tract() / 2;
        WindowOutlets.Terrain boundary = (x, z) -> HierarchicalHydrology.owner((int) Math.floor(x),
                (int) Math.floor(z), hp(), PROV::coarseLayerFor).levelAt((int) Math.floor(x), (int) Math.floor(z));
        var req = new HydrologyPipeline.Request(cx, cz, hp().fineRadius(), hp().step(),
                new LakeWaterBalance.Climate(0.004, 0.002, 0.3), 0, HydrologyPipeline.RiverModel.D8, 1, null);
        return HydrologyPipeline.run(gen, TP, req, new HydrologyPipeline.Layer(null, boundary, prewarm, "owner-source-probe"));
    }

    private static List<OwnerSourcePath> ownerCandidatePaths(HydrologyPipeline.Result p, int rx, int rz) throws Exception {
        int ox = rx * hp().tract() - hp().halo(), oz = rz * hp().tract() - hp().halo();
        double threshold = Double.parseDouble(System.getProperty("geogenesis.channelMinCells", "150"))
                * p.lakeResult().runoffPerCell();
        var result = new ArrayList<OwnerSourcePath>();
        for (int s : sourceCandidates(p, threshold)) {
            var world = new SharedBoundaryDrainage.WorldCell(ox + s / p.nz() * p.cellBlocks(), oz + s % p.nz() * p.cellBlocks());
            if (Math.floorDiv(world.x(), hp().tract()) != rx || Math.floorDiv(world.z(), hp().tract()) != rz) continue;
            var path = new ArrayList<Integer>(); int k = s; String end = "CYCLE_OR_LIMIT";
            var seen = new java.util.BitSet(p.nx() * p.nz());
            while (k >= 0 && k < p.parent().length && !seen.get(k)) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                seen.set(k); path.add(k);
                if (p.sea()[k]) { end = "LOCAL_SEA"; break; }
                if (p.lake()[k]) { end = "LOCAL_LAKE_BUDGET_PENDING"; break; }
                if (!p.resolved()[k]) { end = "UNRESOLVED"; break; }
                if (p.parent()[k] < 0) { end = "UNKNOWN_FRAME_OR_SINK"; break; }
                k = p.parent()[k];
            }
            result.add(new OwnerSourcePath(world, path, end));
        }
        return List.copyOf(result);
    }

    private static boolean sameSourcePipe(HydrologyPipeline.Result a, HydrologyPipeline.Result b) {
        return a.nx() == b.nx() && a.nz() == b.nz() && a.cellBlocks() == b.cellBlocks()
                && java.util.Arrays.equals(a.bed(), b.bed()) && java.util.Arrays.equals(a.parent(), b.parent())
                && java.util.Arrays.equals(a.level(), b.level()) && java.util.Arrays.equals(a.discharge(), b.discharge())
                && java.util.Arrays.equals(a.lake(), b.lake()) && java.util.Arrays.equals(a.sea(), b.sea())
                && java.util.Arrays.equals(a.resolved(), b.resolved()) && java.util.Arrays.equals(a.waterSurface(), b.waterSurface());
    }

    private static void secOwnerSources(long seed, boolean acceptance) throws Exception {
        log("\n=== [owner-sources] E侵蚀固定owner候选/F共享入口/G准入依赖；NOT WATER ===");
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), shared0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var source = ownerSourcePipe(GEN, -2, 0, true);
        var receiving = ownerSourcePipe(GEN, -2, 1, true);
        var paths = ownerCandidatePaths(source, -2, 0); var recipientPaths = ownerCandidatePaths(receiving, -2, 1);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0 == HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "两个显式owner候选求解只读地形/粗层，零finalfine/共享fine依赖", "fine increased");
        var fresh = newGen(TP, seed);
        var reverseReceiver = ownerSourcePipe(fresh, -2, 1, false);
        var reverseSource = ownerSourcePipe(fresh, -2, 0, false);
        chk(sameSourcePipe(source, reverseSource) && sameSourcePipe(receiving, reverseReceiver)
                        && paths.equals(ownerCandidatePaths(reverseSource, -2, 0)),
                "独立fresh逆owner顺序且关预热，候选/父树/来水/水面逐位同值", "different");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0 == HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "冷逆序重建仍零finalfine，不读取邻区最终结果", "fine increased");
        var published = PROV.pipeForProbe(-768, 256); var reaches = PROV.reachesForProbe(-768, 256);
        chk(sameSourcePipe(source, published), "独立固定owner基础pipe与实际生产owner逐位一致（不是新河网）", "different");
        int ox = -1024 - hp().halo(), oz = -hp().halo(), matched = 0, owned = 0;
        var candidateIds = paths.stream().map(OwnerSourcePath::source).collect(java.util.stream.Collectors.toSet());
        for (var reach : reaches) {
            int k = reach.sourceCell(); var w = new SharedBoundaryDrainage.WorldCell(ox + k / source.nz() * hp().step(), oz + k % source.nz() * hp().step());
            if (Math.floorDiv(w.x(), hp().tract()) == -2 && Math.floorDiv(w.z(), hp().tract()) == 0) {
                owned++; if (candidateIds.contains(w)) matched++;
            }
        }
        log("E1 OWNER candidates=" + paths.size() + " receiverCandidates=" + recipientPaths.size()
                + " publishedOwnedStarts=" + owned + " covered=" + matched + " thresholdSupport=LOCAL_Q_NOT_GLOBAL"
                + " spacingAndOccupiedNotApplied=true incomingAndOutletNotHeadwater=true");
        chk(owned > 0 && matched == owned, "侵蚀owner候选必须覆盖本区已发布起点，不用最近点或回删", "matched=" + matched + "/" + owned);
        auditOwnerOverlap(source, receiving, acceptance);
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); ownerCandidatePaths(source, -2, 0); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && paths.equals(ownerCandidatePaths(source, -2, 0)), "候选追踪取消不发布部分结果，重试同值", "missed");
        boolean immutable = false;
        try { paths.get(0).path().add(0); } catch (UnsupportedOperationException e) { immutable = true; }
        chk(immutable, "owner候选身份和路径列表发布只读", "mutable");
        var csv = auditOwnerEntries(source, receiving, paths, acceptance);
        renderOwnerSources(source, paths, csv);
        if (acceptance) chk(false, "固定候选尚非全局源准入/盆地出流/连续几何权威，禁止推广", "OWNER_SOURCE_AUTHORITY_PENDING");
        // #region agent log: E/F/G independent owner candidate evidence, no production mutation
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void auditOwnerOverlap(HydrologyPipeline.Result a, HydrologyPipeline.Result b, boolean acceptance) {
        int step = hp().step(), ox = -1024 - hp().halo(), az = -hp().halo(), bz = hp().tract() - hp().halo();
        int bedMismatch = 0, parentMismatch = 0, qMismatch = 0, levelMismatch = 0, count = 0, interiorMismatch = 0;
        var samples = new ArrayList<String>();
        for (int x = ox; x <= ox + (a.nx() - 1) * step; x += step)
            for (int z = bz + step; z <= az + (a.nz() - 2) * step; z += step) {
                int i = (x - ox) / step * a.nz() + (z - az) / step;
                int j = (x - ox) / step * b.nz() + (z - bz) / step;
                count++; if (Double.doubleToLongBits(a.bed()[i]) != Double.doubleToLongBits(b.bed()[j])) bedMismatch++;
                int ap = a.parent()[i], bp = b.parent()[j];
                boolean same = ap < 0 && bp < 0 || ap >= 0 && bp >= 0 && ap / a.nz() == bp / b.nz()
                        && az + ap % a.nz() * step == bz + bp % b.nz() * step;
                if (!same) {
                    parentMismatch++;
                    if (x > ox && x < ox + (a.nx() - 1) * step) {
                        interiorMismatch++; if (samples.size() < 8) samples.add(x + "," + z + " parents=" + ap + "/" + bp);
                    }
                }
                if (Double.doubleToLongBits(a.discharge()[i]) != Double.doubleToLongBits(b.discharge()[j])) qMismatch++;
                if (Double.doubleToLongBits(a.level()[i]) != Double.doubleToLongBits(b.level()[j])) levelMismatch++;
            }
        log("F3 OWNER_OVERLAP nodes=" + count + " bedMismatch=" + bedMismatch + " parentMismatch=" + parentMismatch
                + " interiorParentMismatch=" + interiorMismatch + " levelMismatch=" + levelMismatch
                + " localQMismatch=" + qMismatch + " samples=" + samples);
        chk(count > 0 && bedMismatch == 0, "两固定owner完整重叠带（排除z外框）真实床面一致", "count=" + count + " bed=" + bedMismatch);
        if (acceptance) chk(parentMismatch == 0, "共享入口推广需重叠方向一致，首步一致不能代替全带", "parents=" + parentMismatch);
    }

    private static String auditOwnerEntries(HydrologyPipeline.Result p, HydrologyPipeline.Result receiver,
            List<OwnerSourcePath> paths, boolean acceptance) {
        int ox = -1024 - hp().halo(), oz = -hp().halo(), rox = ox, roz = hp().tract() - hp().halo();
        int crossings = 0, bedMismatch = 0, downstreamMismatch = 0; var terminals = new java.util.TreeMap<String, Integer>();
        var csv = new StringBuilder("sourceX,sourceZ,fromX,fromZ,toX,toZ,receiverNextX,receiverNextZ,bedEqual,nextEqual,sourceQ,receiverQ\n");
        for (var trace : paths) {
            terminals.merge(trace.end(), 1, Integer::sum);
            for (int i = 1; i < trace.path().size(); i++) {
                int a = trace.path().get(i - 1), b = trace.path().get(i);
                int ax = ox + a / p.nz() * p.cellBlocks(), az = oz + a % p.nz() * p.cellBlocks();
                int bx = ox + b / p.nz() * p.cellBlocks(), bz = oz + b % p.nz() * p.cellBlocks();
                if (az >= hp().tract() || bz < hp().tract() || Math.floorDiv(bx, hp().tract()) != -2) continue;
                int rk = (bx - rox) / receiver.cellBlocks() * receiver.nz() + (bz - roz) / receiver.cellBlocks();
                int next = receiver.parent()[rk], original = p.parent()[b];
                boolean bed = Double.doubleToLongBits(p.bed()[b]) == Double.doubleToLongBits(receiver.bed()[rk]);
                int nx = next < 0 ? Integer.MIN_VALUE : rox + next / receiver.nz() * receiver.cellBlocks();
                int nz = next < 0 ? Integer.MIN_VALUE : roz + next % receiver.nz() * receiver.cellBlocks();
                boolean same = original >= 0 && nx == ox + original / p.nz() * p.cellBlocks()
                        && nz == oz + original % p.nz() * p.cellBlocks();
                int agreed = 0, cursor = b; String stop = "SOURCE_LOCAL_TERMINAL";
                var visited = new java.util.BitSet(p.parent().length);
                while (cursor >= 0 && !visited.get(cursor)) {
                    visited.set(cursor); int wx = ox + cursor / p.nz() * p.cellBlocks(), wz = oz + cursor % p.nz() * p.cellBlocks();
                    if (wx < rox || wx >= rox + receiver.nx() * receiver.cellBlocks() || wz < roz
                            || wz >= roz + receiver.nz() * receiver.cellBlocks()) { stop = "OUTSIDE_RECEIVER"; break; }
                    int recv = (wx - rox) / receiver.cellBlocks() * receiver.nz() + (wz - roz) / receiver.cellBlocks();
                    int sp = p.parent()[cursor], rp = receiver.parent()[recv];
                    if (sp < 0) { stop = "SOURCE_LOCAL_TERMINAL"; break; }
                    if (rp < 0 || ox + sp / p.nz() * p.cellBlocks() != rox + rp / receiver.nz() * receiver.cellBlocks()
                            || oz + sp % p.nz() * p.cellBlocks() != roz + rp % receiver.nz() * receiver.cellBlocks()) {
                        stop = "DIRECTION_CONFLICT"; break;
                    }
                    agreed++; cursor = sp;
                }
                String terminalEvidence = "";
                if (stop.equals("SOURCE_LOCAL_TERMINAL") && cursor >= 0) {
                    int wx = ox + cursor / p.nz() * p.cellBlocks(), wz = oz + cursor % p.nz() * p.cellBlocks();
                    int recv = (wx - rox) / receiver.cellBlocks() * receiver.nz() + (wz - roz) / receiver.cellBlocks();
                    terminalEvidence = " point=" + wx + "," + wz + " senderFrame="
                            + (cursor / p.nz() == 0 || cursor / p.nz() == p.nx() - 1
                            || cursor % p.nz() == 0 || cursor % p.nz() == p.nz() - 1)
                            + " receiverParentExists=" + (receiver.parent()[recv] >= 0) + " receiverResolved=" + receiver.resolved()[recv]
                            + " receiverLake=" + receiver.lake()[recv] + " receiverSea=" + receiver.sea()[recv];
                }
                log("F4 SHARED_SUFFIX source=" + trace.source() + " agreedEdges=" + agreed + " stop=" + stop
                        + terminalEvidence + " terminalNotCertified=true");
                if (acceptance) chk(!stop.equals("DIRECTION_CONFLICT"), "逐源共享后缀不得分叉 " + trace.source(), stop);
                crossings++; if (!bed) bedMismatch++; if (!same) downstreamMismatch++;
                csv.append(trace.source().x()).append(',').append(trace.source().z()).append(',').append(ax).append(',').append(az)
                        .append(',').append(bx).append(',').append(bz).append(',').append(nx).append(',').append(nz).append(',')
                        .append(bed).append(',').append(same).append(',').append(p.discharge()[b]).append(',').append(receiver.discharge()[rk]).append('\n');
                log("F1 OWNER_ENTRY source=" + trace.source() + " edge=" + ax + "," + az + "->" + bx + "," + bz
                        + " receiverNext=" + nx + "," + nz + " bedEqual=" + bed + " nextEqual=" + same
                        + " Q=" + p.discharge()[b] + "/" + receiver.discharge()[rk] + " Q_NOT_TRANSFERRED");
            }
        }
        log("F2 OWNER crossings=" + crossings + " bedMismatch=" + bedMismatch + " nextMismatch=" + downstreamMismatch
                + " terminals=" + terminals + " localCandidateParentsOnly=true");
        chk(crossings > 0 && bedMismatch == 0, "真实跨缝候选边非空且两owner相同坐标床面逐位相同", "crossings=" + crossings + " bedMismatch=" + bedMismatch);
        if (acceptance) chk(downstreamMismatch == 0, "共享入口不能只保源ID，接收下游方向必须同权威", "different=" + downstreamMismatch);
        return csv.toString();
    }

    private static void renderOwnerSources(HydrologyPipeline.Result p, List<OwnerSourcePath> paths, String csv) throws Exception {
        int cx = -768, cz = 256, radius = hp().fineRadius(), ox = cx - radius, oz = cz - radius;
        Path out = OUT.resolve("owner-sources-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "CURRENT PRODUCTION / SCHEMA77 / OWNER CANDIDATES NOT APPLIED");
        var g = raw.createGraphics(); g.setColor(java.awt.Color.YELLOW);
        for (var trace : paths) for (int i = 1; i < trace.path().size(); i++) {
            int a = trace.path().get(i - 1), b = trace.path().get(i);
            g.drawLine(a / p.nz() * p.cellBlocks(), a % p.nz() * p.cellBlocks(), b / p.nz() * p.cellBlocks(), b % p.nz() * p.cellBlocks());
        }
        for (var trace : paths) {
            g.setColor(java.awt.Color.GREEN); g.fillRect(trace.source().x() - ox - 2, trace.source().z() - oz - 2, 5, 5);
            int end = trace.path().get(trace.path().size() - 1); g.setColor(java.awt.Color.RED);
            g.drawRect(end / p.nz() * p.cellBlocks() - 2, end % p.nz() * p.cellBlocks() - 2, 4, 4);
        }
        g.dispose(); writeLocalImage(out.resolve("candidates.png"), raw, cx, cz, radius,
                "YELLOW OWNER CANDIDATE PARENT / GREEN CANDIDATE / RED LOCAL TERMINAL / NOT GENERATED WATER OR GLOBAL AUTHORITY");
        Files.writeString(out.resolve("entries.csv"), csv);
        Files.writeString(out.resolve("sources.txt"), paths.toString());
        Files.writeString(out.resolve("manifest.txt"), "Two explicit owner pipes reconstructed from erosion and coarse boundary, no finalfine read.\n"
                + "Candidates precede spacing/covered/incoming/outlet admission; LOCAL_Q only. No shared entry direction repair.\n" + LOG);
        chk(mask.failCount == 0, "侵蚀owner候选大范围底图走生产provider无失败", "fail=" + mask.failCount);
        log("OWNER_SOURCES_DIR=" + out.toAbsolutePath());
    }

    private static void claimsSynthetic() throws Exception {
        log("\n=== [claims-synth] two-owner declaration equality, NOT direction authority ===");
        var a = new ProductionDrainageAudit.Region(-2, 0); var b = new ProductionDrainageAudit.Region(-2, 1);
        var owners = List.of(a, b); var p = new SharedBoundaryDrainage.WorldCell(-4, -4);
        var q = new SharedBoundaryDrainage.WorldCell(0, -4); var r = new SharedBoundaryDrainage.WorldCell(-4, 0);
        var ca = new SharedBoundaryDrainage.DirectionClaim(a, p, q);
        var cb = new SharedBoundaryDrainage.DirectionClaim(b, p, q);
        var agreed = SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, cb, ca));
        chk(agreed.size() == 1 && agreed.get(0).next().equals(q) && agreed.get(0).claims().size() == 2,
                "同源双声明一致仅合并重复，不重复边", "different");
        var conflict = SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, new SharedBoundaryDrainage.DirectionClaim(b, p, r))).get(0);
        chk(conflict.state() == SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT && conflict.next() == null
                && conflict.claims().get(0).next().equals(q) && conflict.claims().get(1).next().equals(r), "分歧保留两条真实声明，绝不挑一侧", "lost");
        chk(SharedBoundaryDrainage.agreeClaims(owners, List.of(ca)).get(0).state() == SharedBoundaryDrainage.ClaimState.INPUT_PENDING
                && SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, new SharedBoundaryDrainage.DirectionClaim(b, p, null)))
                .get(0).state() == SharedBoundaryDrainage.ClaimState.INPUT_PENDING, "缺侧/任一无下游声明保持未知而非闭流", "wrong");
        boolean rejected = false;
        try { SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, new SharedBoundaryDrainage.DirectionClaim(a, p, r))); }
        catch (IllegalArgumentException e) { rejected = true; }
        chk(rejected, "同owner自相矛盾拒绝", "accepted"); rejected = false;
        try { SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, cb,
                new SharedBoundaryDrainage.DirectionClaim(a, q, p), new SharedBoundaryDrainage.DirectionClaim(b, q, p))); }
        catch (IllegalArgumentException e) { rejected = true; }
        chk(rejected, "双侧都同意但有环也必须拒绝", "accepted");
        var random = new java.util.Random(712041); boolean oracle = true;
        for (int trial = 0; trial < 16; trial++) {
            var claims = new ArrayList<SharedBoundaryDrainage.DirectionClaim>();
            var expected = new java.util.HashMap<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.ClaimState>();
            for (int i = 0; i < 128; i++) {
                var at = new SharedBoundaryDrainage.WorldCell(-512 + i * 4, -4);
                var next = new SharedBoundaryDrainage.WorldCell(at.x() + 4, -4); int mode = random.nextInt(4);
                claims.add(new SharedBoundaryDrainage.DirectionClaim(a, at, next));
                if (mode != 0) claims.add(new SharedBoundaryDrainage.DirectionClaim(b, at,
                        mode == 1 ? next : mode == 2 ? new SharedBoundaryDrainage.WorldCell(at.x() + 8, -4) : null));
                expected.put(at, mode == 1 ? SharedBoundaryDrainage.ClaimState.AGREED_EDGE
                        : mode == 2 ? SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT : SharedBoundaryDrainage.ClaimState.INPUT_PENDING);
            }
            var result = SharedBoundaryDrainage.agreeClaims(owners, claims); java.util.Collections.shuffle(claims, random);
            oracle &= result.equals(SharedBoundaryDrainage.agreeClaims(List.of(b, a), claims));
            for (var entry : result) oracle &= entry.state() == expected.get(entry.point());
        }
        chk(oracle, "16组负坐标独立状态oracle/随机声明与owner顺序同值", "different");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<List<SharedBoundaryDrainage.ClaimAgreement>>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> SharedBoundaryDrainage.agreeClaims(owners, List.of(cb, ca))));
            for (var job : jobs) chk(agreed.equals(job.get()), "四线程独立声明归并同值", "different");
        } finally { pool.shutdownNow(); }
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, cb)); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && agreed.equals(SharedBoundaryDrainage.agreeClaims(owners, List.of(ca, cb))), "取消无部分发布且重试同值", "missed");
        rejected = false;
        try { SharedBoundaryDrainage.agreeClaims(owners, java.util.Collections.nCopies(131073, ca)); }
        catch (IllegalArgumentException e) { rejected = true; }
        chk(rejected, "声明预算超限拒绝而非扩域", "accepted");
        drySlopeSynthetic();
        escapePlaneSynthetic();
        firstTerminalSynthetic();
    }

    private static void firstTerminalSynthetic() {
        var at = new SharedBoundaryDrainage.WorldCell(0, 0);
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, (x, z) -> x == 0 && z == 0 ? 2 : 1,
                (x, z) -> false, false);
        chk(SharedBlockDrainage.firstTerminals(d, List.of(at)).capacities().get(at).landUnknown() == 2,
                "无海首终端未知仍保留真实陆路", "different");
        var ring = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0,
                (x, z) -> Math.max(Math.abs(x), Math.abs(z)) == 2 ? -1 : Math.max(Math.abs(x), Math.abs(z)) < 2 ? 1 : 0.5,
                (x, z) -> Math.max(Math.abs(x), Math.abs(z)) == 2, false);
        var first = SharedBlockDrainage.firstTerminals(ring, List.of(at)).capacities().get(at);
        chk(ring.capacityAt(0, 0).sea() == ring.capacityAt(0, 0).unknown() && first.declaredSea() == 1
                && Double.isInfinite(first.landUnknown()), "海环之后外框不可冒充独立未知陆路", "different");
        var random = new java.util.Random(13279); boolean oracle = true;
        for (int trial = 0; trial < 12; trial++) {
            int ox = -6, oz = -6, n = 9; var beds = new double[n * n]; var ocean = new boolean[n * n];
            for (int k = 0; k < beds.length; k++) { ocean[k] = random.nextInt(7) == 0; beds[k] = ocean[k] ? -1 : random.nextInt(5) + 1; }
            var domain = SharedBlockDrainage.buildAt(0, 0, 4, 4, 0, (x, z) -> beds[((int) x - ox) * n + (int) z - oz],
                    (x, z) -> ocean[(x - ox) * n + z - oz], false);
            var actual = SharedBlockDrainage.firstTerminals(domain, List.of(at)).capacities().get(at);
            double sea = Double.POSITIVE_INFINITY, unknown = Double.POSITIVE_INFINITY;
            for (int threshold = 0; threshold <= 5; threshold++) {
                var seen = new java.util.BitSet(beds.length); var queue = new java.util.ArrayDeque<Integer>(); int start = 6 * n + 6;
                if (beds[start] > threshold) continue; seen.set(start); queue.add(start); boolean hitSea = false, hitFrame = false;
                while (!queue.isEmpty()) { int k = queue.remove();
                    if (ocean[k]) { hitSea = true; continue; }
                    if (k / n == 0 || k % n == 0 || k / n == n - 1 || k % n == n - 1) hitFrame = true;
                    for (int v = 0; v < beds.length; v++) if (Math.abs(k / n - v / n) + Math.abs(k % n - v % n) == 1
                            && beds[v] <= threshold && !seen.get(v)) { seen.set(v); queue.add(v); }
                }
                if (hitSea) sea = Math.min(sea, threshold); if (hitFrame) unknown = Math.min(unknown, threshold);
            }
            oracle &= actual.declaredSea() == sea && actual.landUnknown() == unknown;
        }
        chk(oracle, "12随机带海终端图独立阈值BFS，遇海即止且未知绕路不丢", "different");
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.firstTerminals(ring, List.of(at)); }
        catch (java.util.concurrent.CancellationException e) { cancel = true; } finally { Thread.interrupted(); }
        chk(cancel && first.equals(SharedBlockDrainage.firstTerminals(ring, List.of(at)).capacities().get(at)),
                "首终端求解取消不发布半结果，重试同值", "missed");
    }

    private static void escapePlaneSynthetic() {
        int n = 7; var bed = new double[n * n]; var level = new double[n * n]; var resolved = new boolean[n * n];
        java.util.Arrays.fill(bed, 20); java.util.Arrays.fill(level, 20); java.util.Arrays.fill(resolved, true);
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) { level[x * n + z] = 10; bed[x * n + z] = 8; }
        bed[3 * n + 4] = 10; bed[3 * n + 5] = 9; level[3 * n + 5] = 9;
        var plane = SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 3 * n + 3);
        chk(plane.members().size() == 9 && plane.filledCells() == 8 && plane.exits().size() == 3
                && plane.state() == SharedBoundaryDrainage.PlaneState.LOCAL_COMPLETE,
                "等逃逸面组件含干岸同面格，全部配对出口保留而非挑最低外床", "different");
        chk(plane.equals(SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 2 * n + 2)),
                "同组件任意起点身份/完整成员/配对出口同值", "different");
        resolved[3 * n + 3] = false;
        chk(SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 3 * n + 3).state()
                == SharedBoundaryDrainage.PlaneState.INPUT_PENDING, "任一未决格不可发布共同盆地", "promoted");
        resolved[3 * n + 3] = true; level[3 * n + 5] = 10; bed[3 * n + 6] = 8; level[3 * n + 6] = 10;
        chk(SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 3 * n + 3).touchesFrame(),
                "同面沿干岸触框，不能只看淹没格认闭合", "missed");
        var random = new java.util.Random(42419); boolean oracle = true;
        for (int trial = 0; trial < 32; trial++) {
            for (int k = 0; k < bed.length; k++) { level[k] = random.nextInt(3) + 10; bed[k] = level[k] - random.nextInt(2); }
            int start = random.nextInt(bed.length); var expected = new java.util.HashSet<Integer>(); expected.add(start);
            boolean changed;
            do { changed = false; for (int k = 0; k < bed.length; k++) {
                if (level[k] != level[start] || expected.contains(k)) continue;
                for (int e : List.copyOf(expected)) if (Math.abs(e / n - k / n) <= 1 && Math.abs(e % n - k % n) <= 1) {
                    expected.add(k); changed = true; break;
                }
            }} while (changed);
            var actual = SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, start);
            var members = new java.util.HashSet<Integer>(); for (var at : actual.members()) members.add((at.x() + 16) / 4 * n + (at.z() + 16) / 4);
            var exits = new java.util.HashSet<String>();
            for (int k : expected) for (int v = 0; v < bed.length; v++)
                if (Math.abs(k / n - v / n) <= 1 && Math.abs(k % n - v % n) <= 1 && level[v] < level[k]) exits.add(k + ":" + v);
            var got = new java.util.HashSet<String>(); for (var e : actual.exits()) got.add(((e.inner().x() + 16) / 4 * n + (e.inner().z() + 16) / 4)
                    + ":" + ((e.outer().x() + 16) / 4 * n + (e.outer().z() + 16) / 4));
            var expectedPorts = expected.stream().filter(k -> k / n == 0 || k % n == 0 || k / n == n - 1 || k % n == n - 1)
                    .collect(java.util.stream.Collectors.toSet());
            var ports = actual.framePorts().stream().map(at -> (at.x() + 16) / 4 * n + (at.z() + 16) / 4)
                    .collect(java.util.stream.Collectors.toSet());
            oracle &= members.equals(expected) && got.equals(exits) && ports.equals(expectedPorts);
        }
        chk(oracle, "32组等逃逸面完整成员/全部岸边/未知触框接口独立闭包oracle", "different");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 0); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && SharedBoundaryDrainage.escapePlane(-16, -16, 4, n, n, bed, level, resolved, 0) != null,
                "等逃逸面取消无半组件发布，重试可用", "missed");
    }

    private static void drySlopeSynthetic() {
        var at = new SharedBoundaryDrainage.WorldCell(-512, -4); var random = new java.util.Random(91334);
        boolean oracle = true;
        for (int step : new int[]{1, 2, 4, 8}) for (int trial = 0; trial < 64; trial++) {
            var samples = new ArrayList<SharedBoundaryDrainage.BedSample>(); var sorted = new ArrayList<SharedBoundaryDrainage.BedSample>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var sample = new SharedBoundaryDrainage.BedSample(new SharedBoundaryDrainage.WorldCell(at.x() + dx * step, at.z() + dz * step), random.nextInt(7));
                samples.add(sample); sorted.add(sample);
            }
            double bed = sorted.get(4).bed();
            sorted.remove(4); sorted.sort(java.util.Comparator.<SharedBoundaryDrainage.BedSample>comparingDouble(s ->
                    (s.bed() - bed) / Math.hypot((double) s.point().x() - at.x(), (double) s.point().z() - at.z()))
                    .thenComparingInt(s -> s.point().x()).thenComparingInt(s -> s.point().z()));
            var expected = sorted.get(0).bed() < bed ? sorted.get(0).point() : null;
            var result = SharedBoundaryDrainage.drySlope(at, step, samples); java.util.Collections.shuffle(samples, random);
            oracle &= java.util.Objects.equals(result.next(), expected) && result.equals(SharedBoundaryDrainage.drySlope(at, step, samples));
            samples.remove(0); oracle &= SharedBoundaryDrainage.drySlope(at, step, samples).state() == SharedBoundaryDrainage.SlopeState.INPUT_PENDING;
        }
        chk(oracle, "256组九点坡度独立排序oracle/四步长/负坐标/乱序/缺样本未知", "different");
        var flat = new ArrayList<SharedBoundaryDrainage.BedSample>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            flat.add(new SharedBoundaryDrainage.BedSample(new SharedBoundaryDrainage.WorldCell(at.x() + dx * 4, at.z() + dz * 4), 100));
        chk(SharedBoundaryDrainage.drySlope(at, 4, flat).state() == SharedBoundaryDrainage.SlopeState.BASIN_PENDING,
                "等床无出口不能按坐标造下游", "invented");
        var low = new ArrayList<>(flat); low.set(4, new SharedBoundaryDrainage.BedSample(at, -100));
        chk(SharedBoundaryDrainage.drySlope(at, 4, low).state() == SharedBoundaryDrainage.SlopeState.BASIN_PENDING,
                "低于海平面不自动认证为海（孤立深盆）", "invented sea");
        boolean rejected = false;
        var invalid = new ArrayList<>(flat); invalid.set(0, new SharedBoundaryDrainage.BedSample(at, Double.NaN));
        try { SharedBoundaryDrainage.drySlope(at, 4, invalid); } catch (IllegalArgumentException e) { rejected = true; }
        chk(rejected, "非有限床面拒绝", "accepted");
        invalid.set(0, new SharedBoundaryDrainage.BedSample(at, 99)); rejected = false;
        try { SharedBoundaryDrainage.drySlope(at, 4, invalid); } catch (IllegalArgumentException e) { rejected = true; }
        chk(rejected, "同世界点床面矛盾拒绝", "accepted");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBoundaryDrainage.drySlope(at, 4, flat); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; } finally { Thread.interrupted(); }
        chk(cancelled && SharedBoundaryDrainage.drySlope(at, 4, flat).state() == SharedBoundaryDrainage.SlopeState.BASIN_PENDING,
                "九点方向取消不发布部分选择，重试同值", "missed");
    }

    private static void collectOwnerClaims(HydrologyPipeline.Result p, int rx, int rz,
            List<SharedBoundaryDrainage.DirectionClaim> claims) {
        int ox = rx * hp().tract() - hp().halo(), oz = rz * hp().tract() - hp().halo();
        var owner = new ProductionDrainageAudit.Region(rx, rz);
        for (int i = 0; i < p.nx(); i++) for (int j = 0; j < p.nz(); j++) {
            int x = ox + i * p.cellBlocks(), z = oz + j * p.cellBlocks();
            if (z < hp().tract() - hp().halo() - p.cellBlocks() || z > hp().tract() + hp().halo() + p.cellBlocks()) continue;
            int parent = p.parent()[i * p.nz() + j];
            var next = parent < 0 ? null : new SharedBoundaryDrainage.WorldCell(ox + parent / p.nz() * p.cellBlocks(), oz + parent % p.nz() * p.cellBlocks());
            claims.add(new SharedBoundaryDrainage.DirectionClaim(owner, new SharedBoundaryDrainage.WorldCell(x, z), next));
        }
    }

    private static void secOwnerClaims(boolean acceptance) throws Exception {
        log("\n=== [owner-claims] overlap and corner declarations, NOT global authority ===");
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), shared0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var a = ownerSourcePipe(GEN, -2, 0, true); var b = ownerSourcePipe(GEN, -2, 1, true);
        var claims = new ArrayList<SharedBoundaryDrainage.DirectionClaim>();
        collectOwnerClaims(a, -2, 0, claims); collectOwnerClaims(b, -2, 1, claims);
        var owners = List.of(new ProductionDrainageAudit.Region(-2, 0), new ProductionDrainageAudit.Region(-2, 1));
        var result = SharedBoundaryDrainage.agreeClaims(owners, claims); java.util.Collections.reverse(claims);
        chk(result.equals(SharedBoundaryDrainage.agreeClaims(List.of(owners.get(1), owners.get(0)), claims)),
                "真实重叠/边角声明逆owner和声明顺序完全同值", "different");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0 == HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "两显式原始owner求解，无邻finalfine/共享fine读取（仍有重型pipe成本）", "fine increased");
        var counts = new java.util.EnumMap<SharedBoundaryDrainage.ClaimState, Integer>(SharedBoundaryDrainage.ClaimState.class);
        for (var entry : result) counts.merge(entry.state(), 1, Integer::sum);
        log("H1 CLAIMS input=" + claims.size() + " worldPoints=" + result.size() + " states=" + counts + " QTransferred=false");
        auditOwnerOverlap(a, b, false);
        auditConflictTerrain(a, b, result);
        var slopeCandidates = auditDrySlope(a, b, result);
        auditFilledPlanes(a, b, result);
        chk(counts.getOrDefault(SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT, 0) > 0
                && counts.getOrDefault(SharedBoundaryDrainage.ClaimState.INPUT_PENDING, 0) > 0,
                "真实分歧与缺侧/无下游证据均保留，非静默推广", "empty");
        renderOwnerClaims(result, slopeCandidates);
        if (acceptance) {
            chk(counts.getOrDefault(SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT, 0) == 0,
                    "发布共同方向前不得存在双owner分歧", "DIRECTION_CONFLICT remains");
            chk(false, "两声明一致仍非源准入/层级终端/盆地生命周期证书", "GLOBAL_AUTHORITY_PENDING");
        }
        // #region agent log: H bounded owner declaration evidence, no production mutation
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void auditConflictTerrain(HydrologyPipeline.Result a, HydrologyPipeline.Result b,
            List<SharedBoundaryDrainage.ClaimAgreement> agreements) throws Exception {
        int ox = -1024 - hp().halo(), az = -hp().halo(), bz = hp().tract() - hp().halo(), step = hp().step();
        int count = 0, dryBoth = 0, bothDown = 0, equalLevel = 0, sameBest = 0;
        var csv = new StringBuilder("x,z,bed,northLevel,southLevel,northParentBed,southParentBed,northResolved,southResolved,northLake,southLake,bestX,bestZ\n");
        for (var entry : agreements) {
            if (entry.state() != SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT) continue;
            int x = entry.point().x(), z = entry.point().z(), ai = (x - ox) / step * a.nz() + (z - az) / step;
            int bi = (x - ox) / step * b.nz() + (z - bz) / step; count++;
            if (a.level()[ai] == b.level()[bi]) equalLevel++;
            if (a.level()[ai] == a.bed()[ai] && b.level()[bi] == b.bed()[bi] && !a.lake()[ai] && !b.lake()[bi]) dryBoth++;
            if (a.bed()[a.parent()[ai]] < a.bed()[ai] && b.bed()[b.parent()[bi]] < b.bed()[bi]) bothDown++;
            int abest = steepestPipeNeighbor(a, ai), bbest = steepestPipeNeighbor(b, bi);
            var best = new SharedBoundaryDrainage.WorldCell(ox + abest / a.nz() * step, az + abest % a.nz() * step);
            if (abest != ai && bbest != bi && best.x() == ox + bbest / b.nz() * step && best.z() == bz + bbest % b.nz() * step) sameBest++;
            csv.append(x).append(',').append(z).append(',').append(a.bed()[ai]).append(',').append(a.level()[ai]).append(',')
                    .append(b.level()[bi]).append(',').append(a.bed()[a.parent()[ai]]).append(',').append(b.bed()[b.parent()[bi]]).append(',')
                    .append(a.resolved()[ai]).append(',').append(b.resolved()[bi]).append(',').append(a.lake()[ai]).append(',')
                    .append(b.lake()[bi]).append(',').append(best.x()).append(',').append(best.z()).append('\n');
        }
        log("I1 CONFLICT_TERRAIN count=" + count + " dryBoth=" + dryBoth + " bothParentsDown=" + bothDown
                + " equalEscapeLevel=" + equalLevel + " sameSteepest=" + sameBest + " endpointGeometryOnly=true");
        Files.writeString(OUT.resolve("conflict-terrain-" + System.currentTimeMillis() + ".csv"), csv.toString());
    }

    private record OwnerPlane(int ownerZ, SharedBoundaryDrainage.EscapePlane plane) { }

    private static void auditFilledPlanes(HydrologyPipeline.Result a, HydrologyPipeline.Result b,
            List<SharedBoundaryDrainage.ClaimAgreement> agreements) throws Exception {
        int ox = -1024 - hp().halo(), az = -hp().halo(), bz = hp().tract() - hp().halo(), step = hp().step();
        var planes = new ArrayList<OwnerPlane>();
        for (int ownerZ = 0; ownerZ <= 1; ownerZ++) {
            var p = ownerZ == 0 ? a : b; int oz = ownerZ == 0 ? az : bz; var covered = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
            for (var entry : agreements) {
                if (entry.state() != SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT || covered.contains(entry.point())) continue;
                var at = entry.point(); int k = (at.x() - ox) / step * p.nz() + (at.z() - oz) / step;
                if (p.level()[k] <= p.bed()[k]) continue;
                var plane = SharedBoundaryDrainage.escapePlane(ox, oz, step, p.nx(), p.nz(), p.bed(), p.level(), p.resolved(), k);
                covered.addAll(plane.members()); planes.add(new OwnerPlane(ownerZ, plane));
                log("J1 FILLED_PLANE ownerZ=" + ownerZ + " identity=" + plane.identity() + " level=" + plane.level()
                        + " members=" + plane.members().size() + " filled=" + plane.filledCells() + " unresolved=" + plane.unresolvedCells()
                        + " frame=" + plane.touchesFrame() + " framePorts=" + plane.framePorts().size()
                        + " lowerEdges=" + plane.exits().size() + " state=" + plane.state());
            }
        }
        chk(!planes.isEmpty(), "真实填充冲突等逃逸面组件非空，不用可见湖掩码过滤", "empty");
        if (System.getProperty("geogenesis.planeCapacityAudit", "false").equals("true")) auditPlaneCapacities(planes, a, b);
        renderFilledPlanes(planes);
    }

    private record PlanePortCapacity(int ownerZ, SharedBoundaryDrainage.WorldCell port,
            SharedBoundaryDrainage.WorldCell witness, double level, double bed, SharedBlockDrainage.Capacity capacity) { }

    private static SharedBoundaryDrainage.WorldCell cornerWitness(SharedBlockDrainage.Domain d,
            SharedBoundaryDrainage.WorldCell at) {
        if (!WindowOutlets.VALLEY_MIN) return at;
        SharedBoundaryDrainage.WorldCell best = null; double height = Double.POSITIVE_INFINITY;
        for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-2, 2}) {
            int x = at.x() + dx, z = at.z() + dz;
            if (x < d.originX() || z < d.originZ() || x >= d.originX() + d.sizeX() || z >= d.originZ() + d.sizeZ())
                throw new IllegalArgumentException("corner witness outside fixed capacity patch: " + at);
            double bed = d.bedAtIndex((x - d.originX()) * d.sizeZ() + z - d.originZ());
            if (bed < height) { height = bed; best = new SharedBoundaryDrainage.WorldCell(x, z); }
        }
        return best;
    }

    private static void auditPlaneCapacities(List<OwnerPlane> planes, HydrologyPipeline.Result north, HydrologyPipeline.Result south) throws Exception {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), shared0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        double hs = TP.horizontalScale();
        var domain = SharedBlockDrainage.buildAt(-256, 512, 256, 4, TP.seaLevel(),
                (x, z) -> GEN.erodedHeight(x / hs, z / hs), (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean(), false);
        var rows = new ArrayList<PlanePortCapacity>(); var keep = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>(); boolean bedEqual = true;
        for (var record : planes) for (var at : record.plane().framePorts()) {
            var witness = cornerWitness(domain, at); keep.add(witness);
            double bed = domain.bedAtIndex((witness.x() - domain.originX()) * domain.sizeZ() + witness.z() - domain.originZ());
            var p = record.ownerZ() == 0 ? north : south; int px = -1024 - hp().halo(), pz = record.ownerZ() * hp().tract() - hp().halo();
            int k = (at.x() - px) / p.cellBlocks() * p.nz() + (at.z() - pz) / p.cellBlocks();
            bedEqual &= Double.doubleToLongBits(bed) == Double.doubleToLongBits(p.bed()[k]);
            rows.add(new PlanePortCapacity(record.ownerZ(), at, witness, record.plane().level(), bed, domain.capacityAt(witness.x(), witness.z())));
        }
        var summary = SharedBoundaryTransfer.build(domain, (x, z) -> keep.contains(new SharedBoundaryDrainage.WorldCell(x, z)));
        var capacities = SharedBoundaryTransfer.solve(List.of(summary)); boolean same = true;
        for (var row : rows) same &= row.capacity().equals(capacities.get(row.witness()));
        chk(bedEqual, "65接口角点见证与实际owner pipe谷底床面逐位一致，不是中心偷换", "different bed");
        chk(same, "所有真实未知接口四角床见证，压缩摘要与全域容量逐值一致", "different");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get() && shared0 == HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get(),
                "容量复用单个既有固定patch，未新增邻finalfine/共享fine", "fine increased");
        log("K1 FRAME_CAPACITY rows=" + rows.size() + " uniqueWitness=" + keep.size() + " domain=" + domain.nodes()
                + " summaryVertices=" + summary.vertices().size() + " endpointMinimumOnly=true noDirectionOrFlux=true");
        for (int ownerZ = 0; ownerZ <= 1; ownerZ++) {
            int seaAtPlane = 0, unknownAtPlane = 0, strictSea = 0;
            for (var row : rows) if (row.ownerZ() == ownerZ) {
                if (row.capacity().sea() <= row.level()) seaAtPlane++;
                if (row.capacity().unknown() <= row.level()) unknownAtPlane++;
                if (row.capacity().sea() < row.capacity().unknown()) strictSea++;
            }
            log("K2 ownerZ=" + ownerZ + " knownOceanCapacityAtLocalPlane=" + seaAtPlane + " unknownCapacityAtLocalPlane=" + unknownAtPlane
                    + " strictCapacityDominance=" + strictSea + " globalOceanNotCertified=true");
        }
        for (var record : planes) for (var edge : record.plane().exits()) {
            var witness = cornerWitness(domain, edge.outer()); var capacity = domain.capacityAt(witness.x(), witness.z());
            log("K3 LOWER_EDGE outer=" + edge.outer() + " corner=" + witness + " capacity=" + capacity
                    + " localPlane=" + record.plane().level() + " notGlobalOutlet=true");
        }
        var first = SharedBlockDrainage.firstTerminals(domain, List.copyOf(keep));
        boolean invariant = true;
        for (var row : rows) {
            var value = first.capacities().get(row.witness());
            invariant &= Math.abs(value.declaredSea() - row.capacity().sea()) < 1e-10
                    && value.landUnknown() + 1e-10 >= row.capacity().unknown();
        }
        chk(invariant, "真实首终端海容量等价原图，移除海中继不得降低未知陆路瓶颈", "different");
        for (int ownerZ = 0; ownerZ <= 1; ownerZ++) {
            int dominated = 0; double minLand = Double.POSITIVE_INFINITY;
            for (var row : rows) if (row.ownerZ() == ownerZ) {
                var value = first.capacities().get(row.witness());
                if (value.declaredSea() < value.landUnknown()) dominated++;
                minLand = Math.min(minLand, value.landUnknown());
            }
            log("L1 FIRST_TERMINAL ownerZ=" + ownerZ + " declaredSeaDominates=" + dominated + " minLandUnknown=" + minLand
                    + " conditionalOceanDeclarationsOnly=true productionUnchanged=true");
        }
        renderPlaneCapacities(domain, rows, first);
    }

    private static void renderPlaneCapacities(SharedBlockDrainage.Domain domain, List<PlanePortCapacity> rows,
            SharedBlockDrainage.FirstTerminals first) throws Exception {
        int cx = -512, cz = 640, radius = hp().fineRadius(), ox = cx - radius, oz = cz - radius;
        Path out = OUT.resolve("plane-capacity-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius); var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "CURRENT PRODUCTION / CAPACITY NOT FLOW OR WATER");
        var csv = new StringBuilder("ownerZ,portX,portZ,witnessX,witnessZ,localPlane,bed,seaCapacity,unknownCapacity,firstSeaCapacity,landUnknownCapacity\n"); var g = raw.createGraphics();
        for (var row : rows) {
            var at = row.port(); var witness = row.witness(); var capacity = row.capacity();
            csv.append(row.ownerZ()).append(',').append(at.x()).append(',').append(at.z()).append(',').append(witness.x()).append(',')
                    .append(witness.z()).append(',').append(row.level()).append(',').append(row.bed()).append(',')
                    .append(capacity.sea()).append(',').append(capacity.unknown()).append(',')
                    .append(first.capacities().get(witness).declaredSea()).append(',').append(first.capacities().get(witness).landUnknown()).append('\n');
            g.setColor(capacity.sea() <= row.level() ? java.awt.Color.GREEN : java.awt.Color.RED);
            g.drawOval(at.x() - ox - 2, at.z() - oz - 2, 4, 4);
        }
        for (var row : rows) if (row.port().equals(new SharedBoundaryDrainage.WorldCell(-416, 608))) {
            var path = domain.seaWitness(row.witness().x(), row.witness().z()).path(); g.setColor(row.ownerZ() == 0 ? java.awt.Color.YELLOW : java.awt.Color.ORANGE);
            for (int i = 1; i < path.size(); i++) g.drawLine(path.get(i - 1).x() - ox, path.get(i - 1).z() - oz, path.get(i).x() - ox, path.get(i).z() - oz);
            log("K4 CORNER_CAPACITY ownerZ=" + row.ownerZ() + " witness=" + row.witness() + " pathNodes=" + path.size()
                    + " endpoint=" + (path.isEmpty() ? null : path.get(path.size() - 1)) + " notMonotoneRiver=true");
        }
        g.dispose(); writeLocalImage(out.resolve("capacity.png"), raw, cx, cz, radius,
                "PORT GREEN/RED OCEAN CAPACITY <=/> LOCAL PLANE / YELLOW CAPACITY WITNESS NOT RIVER / CYAN CURRENT WATER");
        Files.writeString(out.resolve("ports.csv"), csv.toString());
        Files.writeString(out.resolve("manifest.txt"), "One existing fixed block patch(-256,512)half256step4; no fine-domain expansion.\n"
                + "Corner minimum is geometric witness only; not a channel junction or certified direction/Q/global ocean.\n" + LOG);
        chk(mask.failCount == 0, "接口容量大范围图底图走实际provider无失败", "fail=" + mask.failCount);
        log("PLANE_CAPACITY_DIR=" + out.toAbsolutePath());
    }

    private static void renderFilledPlanes(List<OwnerPlane> planes) throws Exception {
        int cx = -512, cz = 640, radius = hp().fineRadius(), ox = cx - radius, oz = cz - radius;
        Path out = OUT.resolve("filled-planes-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        var base = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), base, cx, cz, radius, "CURRENT PRODUCTION / ESCAPE PLANES NOT WATER");
        var rows = new StringBuilder("ownerZ,identityX,identityZ,level,state,frame,x,z\n");
        var exits = new StringBuilder("ownerZ,identityX,identityZ,innerX,innerZ,outerX,outerZ,innerBed,outerBed,outerLevel\n");
        var ports = new StringBuilder("ownerZ,identityX,identityZ,level,x,z\n");
        for (int ownerZ = 0; ownerZ <= 1; ownerZ++) {
            var image = new java.awt.image.BufferedImage(base.getWidth(), base.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); g.drawImage(base, 0, 0, null);
            for (var record : planes) {
                if (record.ownerZ() != ownerZ) continue; var plane = record.plane(); g.setColor(ownerZ == 0 ? java.awt.Color.ORANGE : java.awt.Color.MAGENTA);
                for (var at : plane.members()) {
                    g.fillRect(at.x() - ox - 1, at.z() - oz - 1, 2, 2);
                    rows.append(ownerZ).append(',').append(plane.identity().x()).append(',').append(plane.identity().z()).append(',')
                            .append(plane.level()).append(',').append(plane.state()).append(',').append(plane.touchesFrame()).append(',')
                            .append(at.x()).append(',').append(at.z()).append('\n');
                }
                g.setColor(java.awt.Color.YELLOW);
                for (var edge : plane.exits()) {
                    g.drawLine(edge.inner().x() - ox, edge.inner().z() - oz, edge.outer().x() - ox, edge.outer().z() - oz);
                    exits.append(ownerZ).append(',').append(plane.identity().x()).append(',').append(plane.identity().z()).append(',')
                            .append(edge.inner().x()).append(',').append(edge.inner().z()).append(',').append(edge.outer().x()).append(',')
                            .append(edge.outer().z()).append(',').append(edge.innerBed()).append(',').append(edge.outerBed()).append(',').append(edge.outerLevel()).append('\n');
                }
                g.setColor(java.awt.Color.RED);
                for (var port : plane.framePorts()) {
                    g.fillRect(port.x() - ox - 1, port.z() - oz - 1, 3, 3);
                    ports.append(ownerZ).append(',').append(plane.identity().x()).append(',').append(plane.identity().z()).append(',')
                            .append(plane.level()).append(',').append(port.x()).append(',').append(port.z()).append('\n');
                }
            }
            g.dispose(); writeLocalImage(out.resolve(ownerZ == 0 ? "north.png" : "south.png"), image, cx, cz, radius,
                    "LOCAL ESCAPE-PLANE MEMBERS / RED UNKNOWN FRAME PORTS / YELLOW LOWER EDGES / CYAN CURRENT WATER");
        }
        Files.writeString(out.resolve("frame-ports.csv"), ports.toString());
        Files.writeString(out.resolve("members.csv"), rows.toString()); Files.writeString(out.resolve("exits.csv"), exits.toString());
        Files.writeString(out.resolve("manifest.txt"), "Exact equality components include dry rim; visible lake mask ignored.\n"
                + "Frame or unresolved -> INPUT_PENDING. No level/outlet/Q/river publication. Local exits need subcell witness.\n" + LOG);
        chk(mask.failCount == 0, "填充组件整体对照图走生产provider无失败", "fail=" + mask.failCount);
        log("FILLED_PLANES_DIR=" + out.toAbsolutePath());
    }

    private static int steepestPipeNeighbor(HydrologyPipeline.Result p, int k) {
        int best = k; double slope = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = k / p.nz() + dx, z = k % p.nz() + dz;
            if (dx == 0 && dz == 0 || x < 0 || z < 0 || x >= p.nx() || z >= p.nz()) continue;
            int next = x * p.nz() + z; double drop = (p.bed()[k] - p.bed()[next]) / Math.hypot(dx, dz);
            if (drop > slope) { slope = drop; best = next; }
        }
        return best;
    }

    private static List<SharedBoundaryDrainage.SlopeStep> auditDrySlope(HydrologyPipeline.Result a, HydrologyPipeline.Result b,
            List<SharedBoundaryDrainage.ClaimAgreement> agreements) throws Exception {
        int ox = -1024 - hp().halo(), az = -hp().halo(), bz = hp().tract() - hp().halo(), step = hp().step();
        var candidates = new ArrayList<SharedBoundaryDrainage.SlopeStep>(); int pending = 0, equal = 0;
        for (var entry : agreements) {
            if (entry.state() != SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT) continue;
            var at = entry.point(); int ai = (at.x() - ox) / step * a.nz() + (at.z() - az) / step;
            int bi = (at.x() - ox) / step * b.nz() + (at.z() - bz) / step;
            if (a.level()[ai] != a.bed()[ai] || b.level()[bi] != b.bed()[bi] || a.lake()[ai] || b.lake()[bi]) { pending++; continue; }
            var north = SharedBoundaryDrainage.drySlope(at, step, pipeStencil(a, at, ox, az));
            var south = SharedBoundaryDrainage.drySlope(at, step, pipeStencil(b, at, ox, bz));
            if (north.equals(south)) equal++;
            chk(north.equals(south) && north.state() == SharedBoundaryDrainage.SlopeState.DESCENT_CANDIDATE,
                    "真实干坡冲突完整九点产生同一下降候选 " + at, "different or pending");
            candidates.add(north);
        }
        log("I2 DRY_SLOPE compared=" + candidates.size() + " equal=" + equal + " filledBasinPending=" + pending
                + " noParentReplacement=true resolvedNotCertified=true");
        return List.copyOf(candidates);
    }

    private static List<SharedBoundaryDrainage.BedSample> pipeStencil(HydrologyPipeline.Result p,
            SharedBoundaryDrainage.WorldCell at, int ox, int oz) {
        int ix = (at.x() - ox) / p.cellBlocks(), iz = (at.z() - oz) / p.cellBlocks();
        var samples = new ArrayList<SharedBoundaryDrainage.BedSample>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = ix + dx, z = iz + dz;
            if (x < 0 || z < 0 || x >= p.nx() || z >= p.nz()) continue;
            samples.add(new SharedBoundaryDrainage.BedSample(new SharedBoundaryDrainage.WorldCell(ox + x * p.cellBlocks(),
                    oz + z * p.cellBlocks()), p.bed()[x * p.nz() + z]));
        }
        return samples;
    }

    private static void renderOwnerClaims(List<SharedBoundaryDrainage.ClaimAgreement> agreements,
            List<SharedBoundaryDrainage.SlopeStep> candidates) throws Exception {
        int cx = -768, cz = 512, radius = hp().fineRadius(), ox = cx - radius, oz = cz - radius;
        Path out = OUT.resolve("owner-claims-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "CURRENT PRODUCTION / SCHEMA77 / CLAIMS NOT APPLIED");
        var g = raw.createGraphics(); var csv = new StringBuilder("x,z,state,ownerX,ownerZ,nextX,nextZ\n");
        for (var entry : agreements) {
            for (var claim : entry.claims()) {
                var next = claim.next();
                csv.append(entry.point().x()).append(',').append(entry.point().z()).append(',').append(entry.state()).append(',')
                        .append(claim.owner().x()).append(',').append(claim.owner().z()).append(',')
                        .append(next == null ? "" : next.x()).append(',').append(next == null ? "" : next.z()).append('\n');
                if (entry.state() != SharedBoundaryDrainage.ClaimState.DIRECTION_CONFLICT || next == null) continue;
                g.setColor(claim.owner().z() == 0 ? java.awt.Color.RED : java.awt.Color.YELLOW);
                g.drawLine(entry.point().x() - ox, entry.point().z() - oz, next.x() - ox, next.z() - oz);
            }
            g.setColor(entry.state() == SharedBoundaryDrainage.ClaimState.AGREED_EDGE ? new java.awt.Color(50, 180, 80)
                    : entry.state() == SharedBoundaryDrainage.ClaimState.INPUT_PENDING ? java.awt.Color.ORANGE : java.awt.Color.MAGENTA);
            g.fillRect(entry.point().x() - ox - 1, entry.point().z() - oz - 1, 2, 2);
        }
        g.dispose(); writeLocalImage(out.resolve("claims.png"), raw, cx, cz, radius,
                "GREEN SAME EDGE / MAGENTA CONFLICT (RED NORTH, YELLOW SOUTH) / ORANGE UNKNOWN / NOT GENERATED WATER");
        Files.writeString(out.resolve("claims.csv"), csv.toString());
        var slopeCsv = new StringBuilder("x,z,nextX,nextZ,bed,nextBed,state\n");
        var sg = raw.createGraphics(); sg.setColor(java.awt.Color.WHITE);
        for (var candidate : candidates) {
            var at = candidate.point(); var next = candidate.next();
            slopeCsv.append(at.x()).append(',').append(at.z()).append(',').append(next == null ? "" : next.x()).append(',')
                    .append(next == null ? "" : next.z()).append(',').append(candidate.bed()).append(',')
                    .append(candidate.nextBed()).append(',').append(candidate.state()).append('\n');
            if (next != null) sg.drawLine(at.x() - ox, at.z() - oz, next.x() - ox, next.z() - oz);
        }
        sg.dispose(); writeLocalImage(out.resolve("dry-slope.png"), raw, cx, cz, radius,
                "WHITE DRY-SLOPE ENDPOINT CANDIDATE ONLY / FILLED CONFLICTS STILL PENDING / CYAN CURRENT WATER");
        var corner = raw.getSubimage(-500 - ox, 544 - oz, 81, 81);
        writeLocalImage(out.resolve("corner.png"), corner, -460, 584, 40,
                "CORNER DETAIL / WHITE DRY-SLOPE CANDIDATE / MAGENTA UNRESOLVED DIRECTIONS / NOT NEW WATER");
        Files.writeString(out.resolve("dry-slope.csv"), slopeCsv.toString());
        Files.writeString(out.resolve("manifest.txt"), "Actual provider columns base, 1 data pixel/block; two fixed raw owner pipes only.\n"
                + "Equality is NOT global direction; null/missing declarations remain unknown, Q is not transferred.\n" + LOG);
        chk(mask.failCount == 0, "共享带及边角大图来自生产provider，无失败像素", "fail=" + mask.failCount);
        log("OWNER_CLAIMS_DIR=" + out.toAbsolutePath());
    }

    private static void secChannelOrigin() throws Exception {
        log("\n=== [channel-origin] A位置错配/B旧父树与追踪不一致/C四邻分散 ===");
        double hs = TP.horizontalScale();
        var domain = SharedBlockDrainage.buildAt(-768, 512, 256, 4, TP.seaLevel(),
                (x, z) -> GEN.erodedHeight(x / hs, z / hs),
                (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean(), false);
        var direction = SharedBlockDrainage.direction(domain, false); var channels = direction.channels(2400);
        var pipe = PROV.pipeForProbe(-768, 256); var reaches = PROV.reachesForProbe(-768, 256);
        int ox = -768 - hp().fineRadius(), oz = 256 - hp().fineRadius(), matched = 0;
        var rows = new StringBuilder("reach,node,x,z,oldQ,oldParent,nextTrace,bed,level,localArea,inPatch\n");
        var selectedSources = new ArrayList<SharedBlockDrainage.ContinuationSource>();
        for (int r = 0; r < reaches.size(); r++) {
            var reach = reaches.get(r); boolean hit = false;
            for (int i = 1; i < reach.cell.length; i++) {
                int a = reach.cell[i - 1], b = reach.cell[i];
                int ax = ox + a / pipe.nz() * pipe.cellBlocks(), az = oz + a % pipe.nz() * pipe.cellBlocks();
                int bx = ox + b / pipe.nz() * pipe.cellBlocks(), bz = oz + b % pipe.nz() * pipe.cellBlocks();
                if (az < 512 && bz >= 512 && Math.max(ax, bx) >= -730 && Math.min(ax, bx) <= -550) hit = true;
            }
            if (!hit) continue;
            matched++; int source = reach.sourceCell(), changed = 0, rises = 0; double maxRise = 0, minQ = Double.POSITIVE_INFINITY;
            for (int i = 0; i < reach.cell.length; i++) {
                int k = reach.cell[i], next = i + 1 < reach.cell.length ? reach.cell[i + 1] : -1;
                int x = ox + k / pipe.nz() * pipe.cellBlocks(), z = oz + k % pipe.nz() * pipe.cellBlocks();
                boolean inside = x >= domain.originX() && z >= domain.originZ()
                        && x < domain.originX() + domain.sizeX() && z < domain.originZ() + domain.sizeZ();
                minQ = Math.min(minQ, pipe.discharge()[k]);
                if (next >= 0) { if (next != pipe.parent()[k]) changed++;
                    double rise = pipe.bed()[next] - pipe.bed()[k]; if (rise > 1e-9) rises++; maxRise = Math.max(maxRise, rise); }
                rows.append(r).append(',').append(i).append(',').append(x).append(',').append(z).append(',')
                        .append(pipe.discharge()[k]).append(',').append(pipe.parent()[k]).append(',').append(next).append(',')
                        .append(pipe.bed()[k]).append(',').append(pipe.level()[k]).append(',')
                        .append(inside ? channels.at(x, z).contributingBlocks() : -1).append(',').append(inside).append('\n');
                if (z >= 504 && z <= 520) log("A/B REACH_SCENE r=" + r + " node=" + i + " point=" + x + "," + z
                        + " oldQ=" + pipe.discharge()[k] + " next=" + next + " oldParent=" + pipe.parent()[k]
                        + " local=" + (inside ? channels.at(x, z) : "OUTSIDE_FIXED_PATCH"));
            }
            var sourcePoint = new SharedBoundaryDrainage.WorldCell(ox + source / pipe.nz() * pipe.cellBlocks(),
                    oz + source % pipe.nz() * pipe.cellBlocks());
            boolean sourceInside = sourcePoint.x() >= domain.originX() && sourcePoint.z() >= domain.originZ()
                    && sourcePoint.x() < domain.originX() + domain.sizeX() && sourcePoint.z() < domain.originZ() + domain.sizeZ();
            selectedSources.add(new SharedBlockDrainage.ContinuationSource(sourcePoint, sourceInside ? sourcePoint : null));
            log("B REACH_ORIGIN r=" + r + " source=" + (ox + source / pipe.nz() * pipe.cellBlocks()) + ","
                    + (oz + source % pipe.nz() * pipe.cellBlocks()) + " sourceQ=" + pipe.discharge()[source]
                    + " runoff=" + pipe.lakeResult().runoffPerCell() + " nodes=" + reach.cell.length
                    + " changedParent=" + changed + " bedRises=" + rises + " maxRise=" + maxRise + " minQ=" + minQ);
        }
        for (int x : new int[]{-712, -648, -580}) {
            long maxArea = 0; int maxX = x;
            for (int px = x - 8; px <= x + 8; px++) {
                long area = channels.at(px, 511).contributingBlocks(); if (area > maxArea) { maxArea = area; maxX = px; }
            }
            log("A/C SCENE_NEAR x=" + x + " maxAreaWithin8=" + maxArea + " atX=" + maxX
                    + " realBed=" + direction.at(x, 511).bed() + " actualNext=" + direction.at(x, 511).next());
        }
        chk(matched >= 3, "实际Reach跨缝病例非空转", "matched=" + matched);
        var moved = riverConnectivity(-713, 511, 16, 1);
        log("SOURCE_SUPPORTED_MOVED_SEAM original=-710,511 current=-713,511 source=-776,376 connected=" + moved.connected()
                + " (supplemental evidence; old fixed-start FAIL retained, no nearest-line bridge)");
        Files.writeString(OUT.resolve("channel-origin.csv"), rows.toString());
        auditDiagonalSelection(domain, direction, channels);
        auditSourceContinuations(domain, direction, selectedSources);
        // #region agent log: A/B/C fixed-domain versus published source evidence, never consumed by production
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
    }

    private static void auditSourceContinuations(SharedBlockDrainage.Domain domain, SharedBlockDrainage.Direction direction,
            List<SharedBlockDrainage.ContinuationSource> sources) throws Exception {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var ledger = direction.continueSources(sources, hp().tract());
        var reverse = new ArrayList<>(sources); java.util.Collections.reverse(reverse);
        chk(ledger.equals(direction.continueSources(reverse, hp().tract())), "实际已选源接续账本逆序同值，无Q/面积注入", "different");
        int known = 0, crossed = 0;
        for (var source : ledger.sources()) {
            boolean crossing = ledger.edges().stream().anyMatch(e -> e.sources().contains(source.source())
                    && e.id().from().z() == 511 && e.id().to().z() == 512);
            if (source.entry() != null) { known++; if (crossing) crossed++; }
            log("S1 SOURCE_OBLIGATION=" + source + " crossesZ512=" + crossing + " lifecycleCertified=false selectedByProductionOnly=true");
        }
        chk(known == 2 && crossed == 2 && ledger.sources().size() == 3,
                "实际两域内源的接续义务跨缝，第三域外源仍明确未知（非修复）", "known=" + known + " crossed=" + crossed);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "源接续不读取邻fine或改变原方向", "fine increased");
        Path out = OUT.resolve("source-continuation-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, -768, 512, 256);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, -768, 512, 256, "CURRENT PRODUCTION / SCHEMA77 / SOURCE OBLIGATION NOT APPLIED");
        var g = raw.createGraphics(); var csv = new StringBuilder("fromX,fromZ,toX,toZ,sourceX,sourceZ,localArea,inputPending\n");
        var areas = direction.channels(2400);
        for (var edge : ledger.edges()) {
            var a = edge.id().from(); var b = edge.id().to();
            g.setColor(edge.sources().size() > 1 ? java.awt.Color.ORANGE : java.awt.Color.YELLOW);
            g.drawLine(a.x() + 1024, a.z() - 256, b.x() + 1024, b.z() - 256);
            var area = areas.at(a.x(), a.z());
            for (var source : edge.sources()) csv.append(a.x()).append(',').append(a.z()).append(',').append(b.x()).append(',')
                    .append(b.z()).append(',').append(source.x()).append(',').append(source.z()).append(',')
                    .append(area.contributingBlocks()).append(',').append(area.inputPending()).append('\n');
        }
        for (var source : ledger.sources()) {
            if (source.entry() != null) { g.setColor(java.awt.Color.GREEN); g.fillRect(source.entry().x() + 1022, source.entry().z() - 258, 5, 5); }
            if (source.terminal() != null) { g.setColor(java.awt.Color.RED); g.fillRect(source.terminal().x() + 1022, source.terminal().z() - 258, 5, 5); }
        }
        g.dispose(); writeLocalImage(out.resolve("obligations.png"), raw, -768, 512, 256,
                "YELLOW SOURCE OBLIGATION / ORANGE MERGED IDs / GREEN ENTRY / RED PENDING / NOT WATER OR GLOBAL AUTHORITY");
        Files.writeString(out.resolve("edges.csv"), csv.toString());
        Files.writeString(out.resolve("manifest.txt"), "Explicit selected sources read from actual published Reach. Only in-domain source entry allowed.\n"
                + "No oldQ injection or nearest-line bridge. One existing direction graph; terminals pending.\n" + ledger.sources() + "\n" + LOG);
        chk(mask.failCount == 0, "源义务底图来自生产列且无采样失败", "fail=" + mask.failCount);
        if (Boolean.getBoolean("geogenesis.commonNodeAcceptance"))
            chk(false, "显式生产源只是诊断输入，全球源选择/共享入口与盆地出流仍不得推广", "SOURCE_AUTHORITY_AND_LIFECYCLE_PENDING");
        log("SOURCE_CONTINUATION_DIR=" + out.toAbsolutePath());
    }

    private static int diagonalMiddle(SharedBlockDrainage.Domain d, int from, int to) {
        int a = to / d.sizeZ() * d.sizeZ() + from % d.sizeZ();
        int b = from / d.sizeZ() * d.sizeZ() + to % d.sizeZ();
        for (int middle : new int[]{Math.min(a, b), Math.max(a, b)})
            if (d.bedAtIndex(from) >= d.bedAtIndex(middle) && d.bedAtIndex(middle) >= d.bedAtIndex(to)) return middle;
        return -1;
    }

    private static int[] diagonalParents(SharedBlockDrainage.Domain d, SharedBlockDrainage.Direction direction) {
        int[] parent = new int[d.nodes()];
        for (int k = 0; k < d.nodes(); k++) {
            var at = direction.at(d.originX() + k / d.sizeZ(), d.originZ() + k % d.sizeZ());
            parent[k] = at.next() == null ? -1 : (at.next().x() - d.originX()) * d.sizeZ() + at.next().z() - d.originZ();
            if (at.next() == null || at.state() != SharedBlockDrainage.DirectionEnd.DOWNHILL) continue;
            double bestSlope = at.bed() - at.nextBed();
            for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
                int i = k / d.sizeZ() + dx, j = k % d.sizeZ() + dz;
                if (i < 0 || j < 0 || i >= d.sizeX() || j >= d.sizeZ()) continue;
                int p = i * d.sizeZ() + j;
                double slope = (at.bed() - d.bedAtIndex(p)) / Math.sqrt(2);
                if (slope <= bestSlope || diagonalMiddle(d, k, p) < 0) continue;
                bestSlope = slope; parent[k] = p;
            }
        }
        return parent;
    }

    private static void diagonalGeometrySynthetic() {
        for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
            double[][] cases = {{12, 13}, {-2, -1}, {-2, 8}, {8, 9}, {10, 12}, {0, 12}};
            for (double[] heights : cases) {
                WindowOutlets.Terrain terrain = (x, z) -> x == 0 && z == 0 ? 10 : x == dx && z == dz ? 0
                        : x == dx && z == 0 ? heights[0] : x == 0 && z == dz ? heights[1] : 30;
                var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, terrain, (x, z) -> false, false);
                int ix = -d.originX(), iz = -d.originZ();
                int k = ix * d.sizeZ() + iz, p = (ix + dx) * d.sizeZ() + iz + dz;
                boolean feasible = heights[0] >= 0 && heights[0] <= 10 || heights[1] >= 0 && heights[1] <= 10;
                int middle = diagonalMiddle(d, k, p);
                chk((middle >= 0) == feasible, "斜角中间格须在两端床面之间 " + dx + "," + dz
                        + " beds=" + java.util.Arrays.toString(heights), "wrong witness");
                var direction = SharedBlockDrainage.direction(d, false); int[] parent = diagonalParents(d, direction);
                chk(feasible || parent[k] != p, "高岸隔开的低角/先降后升不得跳连", "crossed barrier or rose");
                if (heights[0] == 8 && heights[1] == 9)
                    chk(parent[k] == p, "可证两步下降的优势斜向保留", "valid diagonal lost");
            }
        }
        for (int seed = 0; seed < 12; seed++) {
            var random = new java.util.Random(seed); double[][] beds = new double[17][17];
            for (int x = 0; x < 17; x++) for (int z = 0; z < 17; z++) beds[x][z] = random.nextInt(12);
            var d = SharedBlockDrainage.buildAt(-16, -32, 8, 4, 0,
                    (x, z) -> beds[(int) x + 26][(int) z + 42], (x, z) -> false, false);
            var direction = SharedBlockDrainage.direction(d, false); int[] actual = diagonalParents(d, direction);
            boolean same = java.util.Arrays.equals(actual, diagonalParents(d, SharedBlockDrainage.direction(d, true)));
            for (int k = 0; k < d.nodes(); k++) {
                int x = k / d.sizeZ(), z = k % d.sizeZ(); var at = direction.at(d.originX() + x, d.originZ() + z);
                int expected = at.next() == null ? -1 : (at.next().x() - d.originX()) * d.sizeZ() + at.next().z() - d.originZ();
                if (at.state() == SharedBlockDrainage.DirectionEnd.DOWNHILL) {
                    double best = at.bed() - at.nextBed();
                    for (int i : new int[]{x - 1, x + 1}) for (int j : new int[]{z - 1, z + 1}) {
                        if (i < 0 || j < 0 || i >= 17 || j >= 17) continue;
                        boolean ok = beds[x][z] >= beds[i][z] && beds[i][z] >= beds[i][j]
                                || beds[x][z] >= beds[x][j] && beds[x][j] >= beds[i][j];
                        double slope = (beds[x][z] - beds[i][j]) / Math.sqrt(2);
                        if (ok && slope > best) { best = slope; expected = i * 17 + j; }
                    }
                }
                same &= actual[k] == expected;
            }
            chk(same, "负坐标随机含平地斜向候选等价独立两步oracle/逆序 seed=" + seed, "different");
        }
    }

    private static void lookaheadSynthetic() throws Exception {
        WindowOutlets.Terrain junction = (x, z) -> x == 0 && z == 0 ? 10 : x == 1 && z == 0 ? 9
                : x == 0 && z == 1 ? 11 : x == 1 && z == 1 ? 0 : x == 2 && z == 0 ? -5 : 30;
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, junction, (x, z) -> false, false);
        var route = SharedBlockDrainage.directionWithLookahead(d, false);
        var trace = route.trace(0, 0);
        chk(trace.path().subList(0, 3).equals(List.of(new SharedBoundaryDrainage.WorldCell(0, 0),
                        new SharedBoundaryDrainage.WorldCell(1, 0), new SharedBoundaryDrainage.WorldCell(2, 0))),
                "两步优势只选第一步，中间世界节点服从自身唯一下游（不跳向低角）", trace.toString());
        var channels = route.channels(1);
        chk(channels.at(2, 0).contributingBlocks() >= channels.at(1, 0).contributingBlocks()
                        && channels.at(1, 0).contributingBlocks() >= channels.at(0, 0).contributingBlocks(),
                "汇入中间格后集水沿同一父边归账，不跨节点漏汇", "lost contribution");
        WindowOutlets.Terrain flat = (x, z) -> x == 0 && z == 0 || x == 1 && z == 0 ? 10
                : x == 1 && z == 1 ? 0 : 30;
        var f = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, flat, (x, z) -> false, false);
        chk(SharedBlockDrainage.direction(f, false).sameDirection(SharedBlockDrainage.directionWithLookahead(f, true)),
                "等床中间格不得绕开完整平地距离规则", "flat changed");
        for (int seed = 0; seed < 12; seed++) lookaheadOracle(seed);
        for (int step : new int[]{2, 4, 8}) {
            WindowOutlets.Terrain slope = (x, z) -> 100 - 0.4 * x - z;
            var a = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, slope, (x, z) -> false, false);
            var b = SharedBlockDrainage.buildAt(step, step, 8, step, 0, slope, (x, z) -> false, true);
            var ra = SharedBlockDrainage.directionWithLookahead(a, false); var rb = SharedBlockDrainage.directionWithLookahead(b, true);
            var union = SharedBlockDrainage.combine(List.of(a, b));
            boolean same = SharedBlockDrainage.directionWithLookahead(union, false).sameDirection(
                    SharedBlockDrainage.directionWithLookahead(SharedBlockDrainage.combine(List.of(b, a)), true));
            for (int x = b.originX() + 2; x < a.originX() + a.sizeX() - 2; x++)
                for (int z = b.originZ() + 2; z < a.originZ() + a.sizeZ() - 2; z++) same &= ra.at(x, z).equals(rb.at(x, z));
            chk(same, "两步完整邻域重叠/缺角组合顺序同值 step=" + step, "scope-dependent interior");
            boolean transfersOk = true;
            for (var transfer : ra.transfers(4)) {
                var downstream = ra.trace(transfer.to().x(), transfer.to().z());
                transfersOk &= ra.at(transfer.from().x(), transfer.from().z()).next().equals(transfer.to())
                        && downstream.end() == transfer.end()
                        && downstream.path().get(downstream.path().size() - 1).equals(transfer.terminal());
            }
            chk(transfersOk && !ra.transfers(4).isEmpty(), "共同节点过界边等价逐点父边与终端 step=" + step, "transfer mismatch");
        }
        var shifted = SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> junction.height(x + 32, z + 16), (x, z) -> false, true);
        chk(trace.path().stream().map(c -> new SharedBoundaryDrainage.WorldCell(c.x() - 32, c.z() - 16)).toList()
                        .equals(SharedBlockDrainage.directionWithLookahead(shifted, true).trace(-32, -16).path()),
                "共同节点两步选择负坐标平移一致", "translated path differs");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.Direction>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> SharedBlockDrainage.directionWithLookahead(d, true)));
            boolean same = true; for (var job : jobs) same &= route.sameDirection(job.get(30, TimeUnit.SECONDS));
            chk(same, "四线程共同节点下游全数组同值", "different");
        } finally { pool.shutdownNow(); }
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.directionWithLookahead(d, true); }
        catch (java.util.concurrent.CancellationException ex) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && route.sameDirection(SharedBlockDrainage.directionWithLookahead(d, false)),
                "取消共同节点方向不发布半产物，重试同值", "cancel/retry");
    }

    private static void lookaheadOracle(int seed) {
        var random = new java.util.Random(seed); double[][] beds = new double[17][17];
        for (int x = 0; x < 17; x++) for (int z = 0; z < 17; z++) beds[x][z] = random.nextInt(12);
        var d = SharedBlockDrainage.buildAt(-16, -32, 8, 4, 3,
                (x, z) -> beds[(int) x + 26][(int) z + 42], (x, z) -> x > -16, false);
        var base = SharedBlockDrainage.direction(d, false); var actual = SharedBlockDrainage.directionWithLookahead(d, false);
        var channels = actual.channels(7); long[] counts = new long[d.nodes()]; boolean same = actual.sameDirection(
                SharedBlockDrainage.directionWithLookahead(d, true));
        for (int k = 0; k < d.nodes(); k++) {
            int x = k / d.sizeZ(), z = k % d.sizeZ(); var at = base.at(d.originX() + x, d.originZ() + z);
            var expected = at.next(); double best = at.bed() - at.nextBed();
            int bestMiddle = Integer.MAX_VALUE, bestTarget = Integer.MAX_VALUE;
            if (at.state() == SharedBlockDrainage.DirectionEnd.DOWNHILL)
                for (int mx = Math.max(0, x - 1); mx <= Math.min(16, x + 1); mx++)
                    for (int mz = Math.max(0, z - 1); mz <= Math.min(16, z + 1); mz++) {
                        if (Math.abs(mx - x) + Math.abs(mz - z) != 1 || beds[mx][mz] >= beds[x][z]) continue;
                        for (int tx : new int[]{x - 1, x + 1}) for (int tz : new int[]{z - 1, z + 1}) {
                            if (tx < 0 || tz < 0 || tx >= 17 || tz >= 17 || Math.abs(tx - mx) + Math.abs(tz - mz) != 1
                                    || beds[mx][mz] < beds[tx][tz]) continue;
                            double score = (beds[x][z] - beds[tx][tz]) / Math.sqrt(2);
                            int middle = mx * 17 + mz, target = tx * 17 + tz;
                            if (score > best || (score == best && bestMiddle != Integer.MAX_VALUE
                                    && (middle < bestMiddle || middle == bestMiddle && target < bestTarget))) {
                                best = score; bestMiddle = middle; bestTarget = target;
                                expected = new SharedBoundaryDrainage.WorldCell(d.originX() + mx, d.originZ() + mz);
                            }
                        }
                    }
            same &= java.util.Objects.equals(expected, actual.at(d.originX() + x, d.originZ() + z).next());
            var trace = actual.trace(d.originX() + x, d.originZ() + z); same &= trace.maxBedRise() == 0;
            if (!d.oceanAtIndex(k)) for (var cell : trace.path()) counts[(cell.x() - d.originX()) * 17 + cell.z() - d.originZ()]++;
        }
        for (int k = 0; k < counts.length; k++) same &= counts[k] == channels.at(d.originX() + k / 17, d.originZ() + k % 17).contributingBlocks();
        chk(same, "共同节点方向/集水逐源oracle，平地海候选负坐标/逆序 seed=" + seed, "different");
    }

    private record SegmentProfile(double maxSampleRise, double totalSampleRise, double peakAboveStart,
                                  int samples, boolean finite) { }

    private static SegmentProfile segmentProfile(double x, double z, double px, double pz, int intervals,
            WindowOutlets.Terrain terrain) {
        if (intervals < 1 || intervals > 64) throw new IllegalArgumentException("one to 64 fixed intervals");
        double start = terrain.height(x, z), previous = start, maxRise = 0, totalRise = 0, peak = 0;
        boolean finite = Double.isFinite(start);
        for (int i = 1; i <= intervals; i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("segment profile");
            double t = (double) i / intervals, bed = terrain.height(x + t * (px - x), z + t * (pz - z));
            finite &= Double.isFinite(bed); double rise = Math.max(0, bed - previous);
            maxRise = Math.max(maxRise, rise); totalRise += rise; peak = Math.max(peak, bed - start); previous = bed;
        }
        return new SegmentProfile(maxRise, totalRise, peak, intervals + 1, finite);
    }

    private static java.util.SortedMap<Long, int[]> diagonalCrossings(SharedBlockDrainage.Domain d, int[] parent, long[] area) {
        var squares = new java.util.TreeMap<Long, int[]>();
        for (int k = 0; k < parent.length; k++) {
            int p = parent[k]; if (p < 0 || area[k] < 2400) continue;
            int dx = p / d.sizeZ() - k / d.sizeZ(), dz = p % d.sizeZ() - k % d.sizeZ();
            if (dx == 0 || dz == 0) continue;
            if (Math.abs(dx) != 1 || Math.abs(dz) != 1) throw new IllegalArgumentException("unit diagonal required");
            int x = d.originX() + Math.min(k / d.sizeZ(), p / d.sizeZ());
            int z = d.originZ() + Math.min(k % d.sizeZ(), p % d.sizeZ());
            long key = ((long) x << 32) ^ (z & 0xffffffffL); int type = dx * dz > 0 ? 0 : 1;
            var pair = squares.computeIfAbsent(key, ignored -> new int[]{-1, -1});
            if (pair[type] >= 0 && pair[type] != k) throw new IllegalStateException("opposite directed same segment");
            pair[type] = k;
        }
        squares.values().removeIf(pair -> pair[0] < 0 || pair[1] < 0);
        return squares;
    }

    private static void segmentGeometrySynthetic() {
        WindowOutlets.Terrain downhill = (x, z) -> 10 - x - z;
        chk(segmentProfile(0, 0, 1, 1, 16, downhill).maxSampleRise() == 0,
                "真实线段采样下降正例", "rise");
        var hill = segmentProfile(0, 0, 1, 1, 16, (x, z) -> 10 - x - z + 12 * x * (1 - x));
        chk(hill.maxSampleRise() > 0 && hill.peakAboveStart() > 0,
                "端点下降不能掩盖线段中间高岸", hill.toString());
        var dip = segmentProfile(0, 0, 1, 1, 16, (x, z) -> 10 - x - z - 12 * x * (1 - x));
        chk(dip.maxSampleRise() > 0 && dip.peakAboveStart() == 0,
                "低于起点的先降后升也须检出", dip.toString());
        WindowOutlets.Terrain narrowHill = (x, z) -> 10 - x - z + 0.2 * Math.pow(Math.sin(16 * Math.PI * x), 2);
        var narrow = segmentProfile(0, 0, 1, 1, 16, narrowHill);
        chk(narrow.maxSampleRise() == 0 && segmentProfile(0, 0, 1, 1, 64, narrowHill).maxSampleRise() > 0,
                "有限采样确实漏窄峰，密采检出但仍不宣称连续证书", narrow.toString());
        var d = SharedBlockDrainage.buildAt(-16, -32, 8, 4, 0, downhill, (x, z) -> false, false);
        int[] parent = new int[d.nodes()]; java.util.Arrays.fill(parent, -1); long[] area = new long[d.nodes()]; java.util.Arrays.fill(area, 2400);
        int a = 8 * d.sizeZ() + 8, b = 9 * d.sizeZ() + 8;
        parent[a] = 9 * d.sizeZ() + 9; parent[b] = 8 * d.sizeZ() + 9;
        var cross = diagonalCrossings(d, parent, area);
        long expected = ((long) (d.originX() + 8) << 32) ^ ((d.originZ() + 8) & 0xffffffffL);
        chk(cross.size() == 1 && cross.containsKey(expected), "负坐标真X交点使用绝对单位方格身份", "wrong cross");
        parent[b] = 9 * d.sizeZ() + 9;
        chk(diagonalCrossings(d, parent, area).isEmpty(), "共同整数端点是汇流，不是X交点或两步中间格冲突", "false intersection");
        chk(!segmentProfile(0, 0, 1, 1, 16, (x, z) -> Double.NaN).finite(), "非有限剖面不得认证", "NaN accepted");
    }

    private static void auditDiagonalSelection(SharedBlockDrainage.Domain d, SharedBlockDrainage.Direction direction,
            SharedBlockDrainage.Channels four) throws Exception {
        int[] parent = diagonalParents(d, direction), indegree = new int[d.nodes()], queue = new int[d.nodes()];
        long[] area = new long[d.nodes()]; int changed = 0, size = 0, invalidWitness = 0;
        for (int k = 0; k < d.nodes(); k++) {
            int p = parent[k];
            if (p >= 0 && k / d.sizeZ() != p / d.sizeZ() && k % d.sizeZ() != p % d.sizeZ()) {
                changed++;
                int middle = diagonalMiddle(d, k, p);
                if (middle < 0 || d.bedAtIndex(k) < d.bedAtIndex(middle) || d.bedAtIndex(middle) < d.bedAtIndex(p)) invalidWitness++;
            }
            area[k] = d.oceanAtIndex(k) ? 0 : 1;
        }
        for (int p : parent) if (p >= 0) indegree[p]++;
        for (int k = 0; k < parent.length; k++) if (indegree[k] == 0) queue[size++] = k;
        for (int head = 0; head < size; head++) {
            int k = queue[head], p = parent[k]; if (p < 0) continue;
            area[p] += area[k]; if (--indegree[p] == 0) queue[size++] = p;
        }
        long conserved = 0; int crossings = 0;
        for (int k = 0; k < parent.length; k++) {
            int p = parent[k]; if (p < 0) { conserved += area[k]; continue; }
            if (area[k] >= 2400 && d.originZ() + k % d.sizeZ() < 512 && d.originZ() + p % d.sizeZ() >= 512) crossings++;
        }
        log("C DIAGONAL_FIXED changed=" + changed + " diagonal=" + changed + " processed=" + size
                + " terminalArea=" + conserved + " selectedZ512=" + crossings
                + " invalidTwoStepWitness=" + invalidWitness + " diagnosticOnly=true");
        long land = 0; for (int k = 0; k < d.nodes(); k++) if (!d.oceanAtIndex(k)) land++;
        chk(size == d.nodes() && conserved == land && invalidWitness == 0,
                "斜向对照两步真实床面非升/无环/面积守恒，非生产替换", "geometry/cycle/conservation");
        for (int x : new int[]{-712, -648, -580}) {
            int k = (x - d.originX()) * d.sizeZ() + 511 - d.originZ(); long max = 0; int maxX = x;
            for (int px = x - 8; px <= x + 8; px++) {
                long value = area[(px - d.originX()) * d.sizeZ() + 511 - d.originZ()]; if (value > max) { max = value; maxX = px; }
            }
            log("C DIAGONAL_SCENE x=" + x + " fourArea=" + four.at(x, 511).contributingBlocks()
                    + " diagonalArea=" + area[k] + " maxWithin8=" + max + " atX=" + maxX);
        }
        auditDiagonalSources(d, direction, parent, area);
        auditCommonNodes(d, parent, area);
        renderDiagonalSelection(d, parent, area, four);
    }

    private static void auditDiagonalSources(SharedBlockDrainage.Domain d, SharedBlockDrainage.Direction direction,
            int[] parent, long[] area) {
        for (int[] source : new int[][]{{-776, 376}, {-712, 128}, {-676, 404}}) {
            if (source[0] < d.originX() || source[1] < d.originZ() || source[0] >= d.originX() + d.sizeX() || source[1] >= d.originZ() + d.sizeZ()) {
                log("C DIAGONAL_SOURCE source=" + source[0] + "," + source[1] + " status=OUTSIDE_FIXED_PATCH (not zero flow)"); continue;
            }
            int k = (source[0] - d.originX()) * d.sizeZ() + source[1] - d.originZ(), nodes = 0;
            var cross = new ArrayList<String>();
            while (parent[k] >= 0 && nodes++ <= parent.length) {
                int p = parent[k], x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
                int px = d.originX() + p / d.sizeZ(), pz = d.originZ() + p % d.sizeZ();
                if (z < 512 && pz >= 512) cross.add(x + "," + z + "->" + px + "," + pz + " area=" + area[k]);
                k = p;
            }
            int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
            log("C DIAGONAL_SOURCE source=" + source[0] + "," + source[1] + " nodes=" + nodes + " crossings=" + cross
                    + " terminal=" + x + "," + z + " state=" + direction.at(x, z).state() + " terminalArea=" + area[k]);
            chk(nodes <= parent.length, "真实源头斜向对照终止，不以附近达标冒充原河续接", "cycle");
        }
    }

    private static void auditCommonNodes(SharedBlockDrainage.Domain d, int[] diagonal, long[] area) {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var geometry = new HashMap<Integer, java.util.Set<Integer>>();
        for (int k = 0; k < diagonal.length; k++) {
            int p = diagonal[k]; if (p < 0 || area[k] < 2400) continue;
            int m = k / d.sizeZ() != p / d.sizeZ() && k % d.sizeZ() != p % d.sizeZ() ? diagonalMiddle(d, k, p) : k;
            if (m != k) geometry.computeIfAbsent(k, key -> new java.util.TreeSet<>()).add(m);
            geometry.computeIfAbsent(m, key -> new java.util.TreeSet<>()).add(p);
        }
        long conflicts = geometry.values().stream().filter(next -> next.size() > 1).count();
        var route = SharedBlockDrainage.directionWithLookahead(d, false); var channels = route.channels(2400);
        int changed = 0; long terminalArea = 0, land = 0; boolean geometryOk = true;
        var original = SharedBlockDrainage.direction(d, false);
        for (int k = 0; k < d.nodes(); k++) {
            int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ(); var at = route.at(x, z);
            if (!java.util.Objects.equals(at.next(), original.at(x, z).next())) changed++;
            if (!d.oceanAtIndex(k)) land++;
            if (at.next() == null) terminalArea += channels.at(x, z).contributingBlocks();
            else geometryOk &= Math.abs(at.next().x() - x) + Math.abs(at.next().z() - z) == 1 && at.nextBed() <= at.bed();
        }
        log("J1 COMMON_NODE oldSelectedGeometryConflicts=" + conflicts + " changedCardinalParents=" + changed
                + " selectedEdges=" + channels.edges(hp().tract()).size() + " selectedTransfers=" + channels.transfers(hp().tract()).size()
                + " land=" + land + " terminalArea=" + terminalArea + " singleParent=true diagnosticOnly=true");
        chk(geometryOk && land == terminalArea && route.sameDirection(SharedBlockDrainage.directionWithLookahead(d, true)),
                "共同世界节点唯一四邻下游、非升、面积守恒及逆序同值", "geometry/conservation/order");
        for (int[] source : new int[][]{{-776, 376}, {-712, 128}, {-676, 404}}) {
            if (source[1] < d.originZ()) { log("J2 COMMON_SOURCE source=" + source[0] + "," + source[1] + " OUTSIDE_FIXED_PATCH UNKNOWN"); continue; }
            var trace = route.trace(source[0], source[1]); var crossings = new ArrayList<String>();
            for (int i = 1; i < trace.path().size(); i++) {
                var a = trace.path().get(i - 1); var b = trace.path().get(i);
                if (a.z() < 512 && b.z() >= 512) crossings.add(a + "->" + b + " " + channels.at(a.x(), a.z()));
            }
            var end = trace.path().get(trace.path().size() - 1);
            log("J2 COMMON_SOURCE source=" + source[0] + "," + source[1] + " nodes=" + trace.path().size()
                    + " crossings=" + crossings + " end=" + trace.end() + " terminal=" + end + " maxRise=" + trace.maxBedRise());
        }
        var relations = route.relations(List.of(new SharedBoundaryDrainage.WorldCell(-655, 653), new SharedBoundaryDrainage.WorldCell(-582, 581)));
        log("J3 COMMON_BASINS assigned=" + relations.assignedLocalCells() + " unassigned=" + relations.unassignedCoveredCells());
        for (var relation : relations.relations()) log("J3 COMMON_RELATION=" + relation);
        if (Boolean.getBoolean("geogenesis.commonNodeAcceptance")) {
            int selectedSources = 0;
            for (int[] source : new int[][]{{-776, 376}, {-676, 404}}) {
                var path = route.trace(source[0], source[1]).path();
                for (int i = 1; i < path.size(); i++) {
                    var a = path.get(i - 1); var b = path.get(i);
                    if (a.z() < 512 && b.z() >= 512 && channels.at(a.x(), a.z()).state() == SharedBlockDrainage.ChannelState.SELECTED_LOCAL)
                        selectedSources++;
                }
            }
            chk(selectedSources == 2, "共同节点推广保留域内已发布源的成河接续（不得以少河凑通过）", "selected=" + selectedSources + "/2");
            chk(relations.relations().stream().allMatch(r -> r.state() == SharedBlockDrainage.RelationEnd.NEXT_BASIN
                            || r.state() == SharedBlockDrainage.RelationEnd.OCEAN_CANDIDATE),
                    "共同节点下游不得停于固定域未知边界", "unknown terminal");
            chk(false, "域外原源与实际蓄水/出口共同权威尚未实现，不准推广", "GLOBAL_SOURCE_AND_BASIN_PENDING");
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "共同节点选择/源追踪/盆关系零邻fine", "fine increased");
    }

    private static void renderDiagonalSelection(SharedBlockDrainage.Domain d, int[] parent, long[] area,
            SharedBlockDrainage.Channels four) throws Exception {
        int cx = -768, cz = 512, radius = 256;
        Path out = OUT.resolve("channel-direction-ab-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "方向对照底图取生产列无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "CURRENT PRODUCTION / SYMMETRIC BLEND / SCHEMA77 / NOT LIFECYCLE ACCEPTANCE");
        renderChannels(out, raw, cx, cz, radius, four);
        Files.copy(OUT.resolve("channel-origin.csv"), out.resolve("channel-origin.csv"));
        var g = raw.createGraphics(); g.setColor(java.awt.Color.YELLOW);
        var rows = new StringBuilder("fromX,fromZ,middleX,middleZ,toX,toZ,fromBed,middleBed,toBed,localArea\n");
        for (int k = 0; k < parent.length; k++) {
            int p = parent[k]; if (p < 0 || area[k] < 2400) continue;
            int middle = k / d.sizeZ() != p / d.sizeZ() && k % d.sizeZ() != p % d.sizeZ() ? diagonalMiddle(d, k, p) : k;
            int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
            int mx = d.originX() + middle / d.sizeZ(), mz = d.originZ() + middle % d.sizeZ();
            int px = d.originX() + p / d.sizeZ(), pz = d.originZ() + p % d.sizeZ();
            g.drawLine(x - cx + radius, z - cz + radius, mx - cx + radius, mz - cz + radius);
            g.drawLine(mx - cx + radius, mz - cz + radius, px - cx + radius, pz - cz + radius);
            rows.append(x).append(',').append(z).append(',').append(mx).append(',').append(mz).append(',')
                    .append(px).append(',').append(pz).append(',').append(d.bedAtIndex(k)).append(',')
                    .append(d.bedAtIndex(middle)).append(',').append(d.bedAtIndex(p)).append(',').append(area[k]).append('\n');
        }
        Files.writeString(out.resolve("diagonal-witness-edges.csv"), rows.toString());
        g.dispose(); writeLocalImage(out.resolve("diagonal-channels.png"), raw, cx, cz, radius,
                "TWO-STEP NONRISING BED WITNESS / AREA >=2400 / NOT WATER / NO BASIN RELEASE / NOT ACCEPTED");
        var common = new java.awt.image.BufferedImage(mask.n, mask.n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var cg = common.createGraphics(); cg.drawImage(HierarchicalComparisonView.render(mask, GEN), 0, 0, null); cg.dispose();
        var commonDir = out.resolve("common-node"); Files.createDirectories(commonDir);
        renderChannels(commonDir, common, cx, cz, radius, SharedBlockDrainage.directionWithLookahead(d, false).channels(2400));
        auditDirectSegments(out, mask, d, parent, area);
        Files.writeString(out.resolve("manifest.txt"), "Fixed same patch. Cyan current provider water with symmetric erosion blend/schema77; yellow diagnostic area-selected two-step witness only.\n"
                + "Middle pixels certify local geometry, NOT shared graph joins or runoff injection. No continuous sub-block proof.\n"
                + "common-node/ shows one cardinal downstream edge per world block; two-step preference does not bypass middle node. NOT production.\n" + LOG);
        log("CHANNEL_DIRECTION_AB_DIR=" + out.toAbsolutePath());
    }

    private static void auditDirectSegments(Path out, HierarchicalComparisonView.Mask mask,
            SharedBlockDrainage.Domain d, int[] parent, long[] area) throws Exception {
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / TP.horizontalScale(), z / TP.horizontalScale());
        var image = HierarchicalComparisonView.render(mask, GEN); var g = image.createGraphics();
        var csv = new StringBuilder("fromX,fromZ,toX,toZ,area,maxRise16,totalRise16,peak16,maxRise32,totalRise32,peak32,finite\n");
        int selected = 0, rises16 = 0, rises32 = 0; double maxRise = 0, maxPeak = 0;
        var profiles = new HashMap<Integer, SegmentProfile>(); long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        for (int k = 0; k < parent.length; k++) {
            int p = parent[k]; if (p < 0 || area[k] < 2400) continue;
            int x = d.originX() + k / d.sizeZ(), z = d.originZ() + k % d.sizeZ();
            int px = d.originX() + p / d.sizeZ(), pz = d.originZ() + p % d.sizeZ();
            var a = segmentProfile(x, z, px, pz, 16, terrain); var b = segmentProfile(x, z, px, pz, 32, terrain);
            profiles.put(k, b); selected++;
            if (b.totalSampleRise() > 1e-9) auditSegmentRise(x, z, px, pz, terrain);
            if (a.totalSampleRise() > 1e-9) rises16++; if (b.totalSampleRise() > 1e-9) rises32++;
            maxRise = Math.max(maxRise, b.maxSampleRise()); maxPeak = Math.max(maxPeak, b.peakAboveStart());
            g.setColor(!b.finite() || b.totalSampleRise() > 1e-9 ? java.awt.Color.RED : java.awt.Color.YELLOW);
            g.drawLine(x + 1024, z - 256, px + 1024, pz - 256);
            csv.append(x).append(',').append(z).append(',').append(px).append(',').append(pz).append(',').append(area[k]).append(',')
                    .append(a.maxSampleRise()).append(',').append(a.totalSampleRise()).append(',').append(a.peakAboveStart()).append(',')
                    .append(b.maxSampleRise()).append(',').append(b.totalSampleRise()).append(',').append(b.peakAboveStart()).append(',')
                    .append(a.finite() && b.finite()).append('\n');
        }
        var crossings = diagonalCrossings(d, parent, area);
        for (long key : crossings.keySet()) {
            int x = (int) (key >> 32), z = (int) key;
            g.setColor(java.awt.Color.MAGENTA); g.fillRect(x + 1024, z - 256, 2, 2);
        }
        g.dispose(); writeLocalImage(out.resolve("direct-segment-profile.png"), image, -768, 512, 256,
                "DIRECT SEGMENTS / RED SAMPLED BED RISE / YELLOW NO SAMPLED RISE / MAGENTA TRUE X / NOT CONTINUOUS CERTIFICATE OR WATER");
        Files.writeString(out.resolve("direct-segment-profile.csv"), csv.toString());
        log("G1 DIRECT_SEGMENTS selected=" + selected + " rise16=" + rises16 + " rise32=" + rises32
                + " maxIncrement32=" + maxRise + " peakAboveStart=" + maxPeak + " properX=" + crossings.size()
                + " sampleOnly=true geometryNotTwoStep=true");
        for (int[] source : new int[][]{{-776, 376}, {-676, 404}}) {
            int k = (source[0] - d.originX()) * d.sizeZ() + source[1] - d.originZ(), bad = 0, edges = 0, selectedEdges = 0;
            while (parent[k] >= 0 && edges++ < parent.length) {
                int p = parent[k]; var profile = profiles.get(k);
                if (profile != null) selectedEdges++;
                else profile = segmentProfile(d.originX() + k / d.sizeZ(), d.originZ() + k % d.sizeZ(),
                        d.originX() + p / d.sizeZ(), d.originZ() + p % d.sizeZ(), 32, terrain);
                if (!profile.finite() || profile.totalSampleRise() > 1e-9) bad++;
                k = p;
            }
            log("G2 DIRECT_SOURCE source=" + source[0] + "," + source[1] + " edges=" + edges
                    + " selectedEdges=" + selectedEdges + " sampledRiseEdges=" + bad + " productionPromotable=false");
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "直接线段采样不新增邻fine或改变provider水柱", "fine increased");
        if (Boolean.getBoolean("geogenesis.commonNodeAcceptance"))
            chk(rises32 == 0 && crossings.isEmpty(), "直接中心线采样无逆坡且实际交点已闭合，不能用两步见证代替", "rises=" + rises32 + " X=" + crossings.size());
    }

    private static void auditSegmentRise(int x, int z, int px, int pz, WindowOutlets.Terrain terrain) {
        double previous = terrain.height(x, z), max = 0; int at = 0;
        for (int i = 1; i <= 32; i++) {
            double t = i / 32.0, bed = terrain.height(x + t * (px - x), z + t * (pz - z));
            if (bed - previous > max) { max = bed - previous; at = i; } previous = bed;
        }
        double t = at / 32.0, bx = x + t * (px - x), bz = z + t * (pz - z);
        double eps = 1e-6, left = terrain.height(bx - eps * (px - x), bz - eps * (pz - z));
        double right = terrain.height(bx + eps * (px - x), bz + eps * (pz - z));
        double hs = TP.horizontalScale(), baseLeft = GEN.terrainEQuick((bx - eps * (px - x)) / hs, (bz - eps * (pz - z)) / hs);
        double baseRight = GEN.terrainEQuick((bx + eps * (px - x)) / hs, (bz + eps * (pz - z)) / hs);
        double deltaLeft = GEN.erosionDeltaE((bx - eps * (px - x)) / hs, (bz - eps * (pz - z)) / hs);
        double deltaRight = GEN.erosionDeltaE((bx + eps * (px - x)) / hs, (bz + eps * (pz - z)) / hs);
        double beforeT = (at - 1) / 32.0, beforeX = x + beforeT * (px - x), beforeZ = z + beforeT * (pz - z);
        double quickBefore = GEN.terrainEQuick(beforeX / hs, beforeZ / hs), uncachedBefore = GEN.terrainE(beforeX / hs, beforeZ / hs);
        double quickAt = GEN.terrainEQuick(bx / hs, bz / hs), uncachedAt = GEN.terrainE(bx / hs, bz / hs);
        log("G4 HEIGHT_SOURCE from=" + x + "," + z + " at=" + bx + "," + bz
                + " beforeQuick=" + quickBefore + " beforeFull=" + uncachedBefore + " atQuick=" + quickAt + " atFull=" + uncachedAt
                + " beforeHeight=" + terrain.height(beforeX, beforeZ) + " beforeSampleWu=" + GEN.sampleWu(beforeX / hs, beforeZ / hs).height
                + " atHeight=" + terrain.height(bx, bz) + " atSampleWu=" + GEN.sampleWu(bx / hs, bz / hs).height);
        log("G3 RISE_DETAIL from=" + x + "," + z + " to=" + px + "," + pz + " maxRise32=" + max
                + " at=" + bx + "," + bz + " epsilonHeightDelta=" + (right - left)
                + " epsilonBaseEDelta=" + (baseRight - baseLeft) + " epsilonErosionDelta=" + (deltaRight - deltaLeft)
                + " totalRise64=" + segmentProfile(x, z, px, pz, 64, terrain).totalSampleRise());
    }

    private record BlendParts(double actual, double rebuilt, String weights) { }

    private static final class BlendInspector {
        final java.lang.reflect.Method tile, sample, blend;
        final java.lang.reflect.Field delta, ox, oz;
        final int center, width;
        BlendInspector() throws Exception {
            Class<?> result = Class.forName(CellGenerator.class.getName() + "$ErosionTileResult");
            tile = CellGenerator.class.getDeclaredMethod("getOrGenTileUnchecked", int.class, int.class);
            sample = CellGenerator.class.getDeclaredMethod("sampleTileField", float[][].class, int.class, int.class, double.class, double.class);
            blend = CellGenerator.class.getDeclaredMethod("blendTileDelta", result, double.class, double.class, int.class, int.class, boolean.class);
            for (var m : List.of(tile, sample, blend)) m.setAccessible(true);
            delta = result.getDeclaredField("delta"); ox = result.getDeclaredField("originX"); oz = result.getDeclaredField("originZ");
            for (var f : List.of(delta, ox, oz)) f.setAccessible(true);
            var c = CellGenerator.class.getDeclaredField("ERODE_TILE_CENTER"); c.setAccessible(true); center = c.getInt(null);
            var b = CellGenerator.class.getDeclaredField("BLEND_START"); b.setAccessible(true); width = 16 - b.getInt(null);
        }
        BlendParts inspect(CellGenerator gen, double x, double z) throws Exception {
            int cx = Math.floorDiv((int) Math.floor(x), center) * center, cz = Math.floorDiv((int) Math.floor(z), center) * center;
            double fx = factor(x - cx), fz = factor(z - cz);
            int nx = cx + (center - (x - cx) <= width ? center : x - cx < width ? -center : 0);
            int nz = cz + (center - (z - cz) <= width ? center : z - cz < width ? -center : 0);
            int[] xs = {cx, nx, cx, nx}, zs = {cz, cz, nz, nz};
            double[] weights = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz};
            double[] values = new double[4]; var text = new StringBuilder(); Object main = null;
            for (int i = 0; i < 4; i++) {
                if (i > 0 && weights[i] == 0) continue;
                Object r = tile.invoke(gen, xs[i], zs[i]); if (i == 0) main = r;
                double value = ((Number) sample.invoke(null, delta.get(r), ox.getInt(r), oz.getInt(r), x, z)).doubleValue();
                values[i] = value;
                text.append(xs[i]).append(':').append(zs[i]).append('@').append(weights[i]).append('=').append(value).append(';');
            }
            double rebuilt = values[0] * (1 - fx) * (1 - fz) + values[1] * fx * (1 - fz)
                    + values[2] * (1 - fx) * fz + values[3] * fx * fz;
            double actual = ((Number) blend.invoke(gen, main, x, z, cx, cz, true)).doubleValue();
            return new BlendParts(actual, rebuilt, text.toString());
        }
        private double factor(double local) {
            double b = center - local <= width ? 1 - (center - local) / width : local < width ? 1 - local / width : 0;
            return 0.5 * b * b * (3 - 2 * b);
        }
    }

    @SuppressWarnings("unchecked")
    private static void blendSynthetic(BlendInspector reader) throws Exception {
        var gen = newGen(TP, DEF_SEED);
        var cacheField = CellGenerator.class.getDeclaredField("erosionTileCache"); cacheField.setAccessible(true);
        var cache = (java.util.Map<Long, Object>) cacheField.get(gen);
        Class<?> type = reader.delta.getDeclaringClass(); var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var key = CellGenerator.class.getDeclaredMethod("tileKey", int.class, int.class); key.setAccessible(true);
        var sizeField = CellGenerator.class.getDeclaredField("ERODE_TILE_SIZE"); sizeField.setAccessible(true); int size = sizeField.getInt(null);
        var borderField = CellGenerator.class.getDeclaredField("ERODE_TILE_BORDER"); borderField.setAccessible(true); int border = borderField.getInt(null);
        chk(gen.peekErosionDeltaE(-48, 48).isEmpty() && gen.erosionTileCacheSize() == 0,
                "冷窥探不生成侵蚀tile，保持非阻塞契约", "generated");
        for (int boundary : new int[]{-48, 0, 48}) {
            cache.clear();
            for (int ix = -2; ix <= 1; ix++) for (int iz = -2; iz <= 1; iz++) {
                int cx = boundary + ix * reader.center, cz = boundary + iz * reader.center;
                Object tile = constructor.newInstance(); float[][] values = new float[size][size];
                for (float[] row : values) java.util.Arrays.fill(row, 10 + ix * 3 + iz * 5);
                reader.delta.set(tile, values); reader.ox.setInt(tile, cx - border); reader.oz.setInt(tile, cz - border);
                cache.put(((Number) key.invoke(null, cx, cz)).longValue(), tile);
            }
            double interior = boundary + reader.center / 2.0, expectedCorner = 6;
            boolean sides = true, corners = true, band = true;
            for (int axis = 0; axis < 2; axis++) {
                double expected = axis == 0 ? 8.5 : 7.5;
                for (int owner : new int[]{boundary - reader.center, boundary}) {
                    int cx = axis == 0 ? owner : boundary, cz = axis == 1 ? owner : boundary;
                    Object tile = cache.get(((Number) key.invoke(null, cx, cz)).longValue());
                    double value = ((Number) reader.blend.invoke(gen, tile, axis == 0 ? (double) boundary : interior,
                            axis == 1 ? (double) boundary : interior, cx, cz, false)).doubleValue();
                    sides &= value == expected;
                }
                for (double eps : new double[]{1e-3, 1e-6, 1e-8}) for (int side : new int[]{-1, 1}) {
                    double x = axis == 0 ? boundary + side * eps : interior, z = axis == 1 ? boundary + side * eps : interior;
                    sides &= Math.abs(reader.inspect(gen, x, z).actual() - expected) <= 10 * eps * eps + 1e-14;
                }
            }
            for (int cx : new int[]{boundary - reader.center, boundary}) for (int cz : new int[]{boundary - reader.center, boundary}) {
                Object tile = cache.get(((Number) key.invoke(null, cx, cz)).longValue());
                corners &= ((Number) reader.blend.invoke(gen, tile, (double) boundary, (double) boundary, cx, cz, false)).doubleValue() == expectedCorner;
            }
            for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
                double value = reader.inspect(gen, boundary + sx * 1e-6, boundary + sz * 1e-6).actual();
                corners &= Math.abs(value - expectedCorner) < 1e-12;
            }
            for (int axis = 0; axis < 2; axis++) for (double local : new double[]{reader.width, reader.center - reader.width, reader.center / 2.0}) {
                double x = boundary + (axis == 0 ? local : reader.center / 2.0);
                double z = boundary + (axis == 1 ? local : reader.center / 2.0);
                band &= reader.inspect(gen, x, z).actual() == 10;
            }
            int count = cache.size(); var p = gen.peekErosionDeltaE(boundary, boundary);
            chk(sides, "四向真实混合边界50/50且两侧收敛 boundary=" + boundary, "side mismatch");
            chk(corners, "四象限真实混合角点四tile各1/4 boundary=" + boundary, "corner mismatch");
            chk(band, "混合带外/带起点只取本tile（不移动边界） boundary=" + boundary, "band mismatch");
            chk(p.isPresent() && p.getAsDouble() == expectedCorner && cache.size() == count,
                    "热窥探复用同一角点公式且不生成tile boundary=" + boundary, "peek mismatch");
        }
    }

    private static void secErosionBlend(long seed) throws Exception {
        log("\n=== [erosion-blend] G5 actual tile fields/weights; G6 real carved columns; diagnostic only ===");
        var reader = new BlendInspector(); blendSynthetic(reader);
        var fresh = newGen(TP, seed);
        Path out = OUT.resolve("erosion-blend-" + System.currentTimeMillis()); Files.createDirectories(out);
        var csv = new StringBuilder("case,epsilonWu,side,wuX,wuZ,actualDelta,rebuiltDelta,height,weights\n");
        double hs = TP.horizontalScale();
        double[][] cases = {{-913 / hs, 480 / hs, 0, 1}, {-681 / hs, 480 / hs, 0, 1}, {-665 / hs, 480 / hs, 0, 1},
                {-240, -216.25, 1, 0}, {-240, 240, 1, 1}, {0, 0, 1, 1}};
        for (int id = cases.length - 1; id >= 0; id--) for (int side : new int[]{-1, 1})
            reader.inspect(fresh, cases[id][0] + side * 1e-8 * cases[id][2], cases[id][1] + side * 1e-8 * cases[id][3]);
        int observed = 0;
        for (int id = 0; id < cases.length; id++) {
            double[] point = cases[id]; double lastJump = 0;
            for (double eps : new double[]{1e-3, 1e-6, 1e-8}) {
                double[] values = new double[2];
                for (int side : new int[]{1, -1}) {
                    double x = point[0] + side * eps * point[2], z = point[1] + side * eps * point[3];
                    var p = reader.inspect(GEN, x, z); var q = reader.inspect(fresh, x, z);
                    chk(Math.abs(p.actual() - p.rebuilt()) < 1e-18, "两侧贡献重建 case=" + id + " eps=" + eps + " side=" + side,
                            "actual=" + p.actual() + " rebuilt=" + p.rebuilt());
                    chk(Double.doubleToRawLongBits(p.actual()) == Double.doubleToRawLongBits(q.actual())
                                    && Double.doubleToRawLongBits(p.actual()) == Double.doubleToRawLongBits(GEN.erosionDeltaE(x, z)),
                            "独立冷生成/直接生产blend逐位一致 case=" + id + " eps=" + eps + " side=" + side, "mismatch");
                    values[side == -1 ? 0 : 1] = GEN.erodedHeight(x, z);
                    csv.append(id).append(',').append(eps).append(',').append(side).append(',').append(x).append(',').append(z).append(',')
                            .append(p.actual()).append(',').append(p.rebuilt()).append(',').append(GEN.erodedHeight(x, z)).append(',').append(p.weights()).append('\n');
                    if (eps == 1e-8) log("G5 TILE_PARTS case=" + id + " side=" + side + " actual=" + p.actual() + " parts=" + p.weights());
                }
                lastJump = values[1] - values[0];
                log("G5 EPSILON_HEIGHT case=" + id + " epsWu=" + eps + " delta=" + lastJump);
            }
            chk(Math.abs(lastJump) < 1e-6, "真实边界 epsilon 缩小时不残留原阶跃 case=" + id, "jump=" + lastJump);
            if (Math.abs(lastJump) > 1e-4) observed++;
        }
        chk(observed == 0, "原现场/负坐标/角点无原尺度的混合阶跃（不代表完整水文通过）", "observed=" + observed);
        Files.writeString(out.resolve("tile-parts.csv"), csv.toString());
        renderBlendProfiles(out);
        Files.writeString(out.resolve("manifest.txt"), "Actual private tile/blend/sample methods read via reflection; symmetric half-weight production blend, schema77.\n"
                + "G5 weights reconstruction + independent generator bits. G6 integer columns only: no fractional water interpolation.\n" + LOG);
        // #region agent log: G5/G6 bounded tile seam reproduction
        Files.writeString(Path.of("..", "debug-agent.log").toAbsolutePath(), LOG.toString(), java.nio.file.StandardOpenOption.APPEND);
        // #endregion
        log("EROSION_BLEND_DIR=" + out.toAbsolutePath());
    }

    private static HydrologyBlockCarvedColumn blendColumn(int x, int z) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        return PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz)).stream()
                .filter(c -> c.blockX() == x && c.blockZ() == z).findFirst().orElseThrow();
    }

    private static void renderBlendProfiles(Path out) throws Exception {
        var image = new java.awt.image.BufferedImage(1200, 690, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 1200, 690);
        g.setColor(java.awt.Color.BLACK); g.drawString("G5 TILE SEAM / BLACK RAW ERODED BED / ORANGE CARVED BED / CYAN REAL WET SURFACE", 20, 20);
        g.drawString("Fractional black samples are diagnostics, NOT placed blocks. Water at integer columns only. SYMMETRIC BLEND / SCHEMA77.", 20, 40);
        var rows = new StringBuilder("x,z,erodedBed,carvedGround,waterSurface,fillWater,lakePlan,wet\n");
        int panel = 0;
        for (int x : new int[]{-913, -681, -665}) {
            int left = 60 + panel * 390, top = 100; double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            double[] heights = new double[769]; var columns = new ArrayList<HydrologyBlockCarvedColumn>();
            for (int i = 0; i < heights.length; i++) {
                heights[i] = GEN.erodedHeight(x / TP.horizontalScale(), (474 + i / 64.0) / TP.horizontalScale());
                lo = Math.min(lo, heights[i]); hi = Math.max(hi, heights[i]);
            }
            for (int z = 474; z <= 486; z++) {
                var c = blendColumn(x, z); columns.add(c); lo = Math.min(lo, c.carvedGroundY()); hi = Math.max(hi, c.carvedGroundY());
                boolean wet = c.fillWater() && c.waterSurfaceY() > c.carvedGroundY();
                if (wet) hi = Math.max(hi, c.waterSurfaceY());
                rows.append(x).append(',').append(z).append(',').append(bed(x, z)).append(',').append(c.carvedGroundY()).append(',')
                        .append(c.waterSurfaceY()).append(',').append(c.fillWater()).append(',').append(c.lakePlan()).append(',').append(wet).append('\n');
                log("G6 PRODUCTION_COLUMN x=" + x + " z=" + z + " raw=" + bed(x, z) + " carved=" + c.carvedGroundY()
                        + " surface=" + c.waterSurfaceY() + " fill=" + c.fillWater() + " lake=" + c.lakePlan() + " wet=" + wet);
            }
            double span = Math.max(0.1, hi - lo); lo -= span * 0.1; span *= 1.2;
            g.setColor(java.awt.Color.BLACK); g.drawString("x=" + x + " z474..486", left, 75);
            g.drawString(String.format(java.util.Locale.ROOT, "Y %.4f .. %.4f", lo, lo + span), left, 92);
            g.setColor(java.awt.Color.LIGHT_GRAY); g.drawRect(left, top, 330, 500); g.drawLine(left + 165, top, left + 165, top + 500);
            for (int i = 1; i < heights.length; i++) {
                g.setColor(java.awt.Color.BLACK); g.drawLine(left + (i - 1) * 330 / 768, top + 500 - (int) ((heights[i - 1] - lo) * 500 / span),
                        left + i * 330 / 768, top + 500 - (int) ((heights[i] - lo) * 500 / span));
            }
            for (int i = 0; i < columns.size(); i++) {
                var c = columns.get(i); int px = left + i * 330 / 12;
                g.setColor(java.awt.Color.ORANGE); g.fillOval(px - 3, top + 500 - (int) ((c.carvedGroundY() - lo) * 500 / span) - 3, 6, 6);
                if (c.fillWater() && c.waterSurfaceY() > c.carvedGroundY()) {
                    g.setColor(java.awt.Color.CYAN); g.fillRect(px - 4, top + 500 - (int) ((c.waterSurfaceY() - lo) * 500 / span) - 3, 8, 6);
                }
            }
            g.setColor(java.awt.Color.BLACK); g.drawString("474                       480 tile seam                   486", left - 15, 620); panel++;
        }
        g.dispose(); javax.imageio.ImageIO.write(image, "png", out.resolve("profiles.png").toFile());
        Files.writeString(out.resolve("production-columns.csv"), rows.toString());
    }

    private static void renderBasinOverview(SharedBlockDrainage.BasinRelations relations,
            SharedBlockDrainage.BasinBudgetRanges ranges, List<SharedBlockDrainage.DirectionTransfer> transfers,
            SharedBlockDrainage.Channels channels) throws Exception {
        int cx = -768, cz = 512, radius = 256;
        Path out = OUT.resolve("basin-overview-" + System.currentTimeMillis()); Files.createDirectories(out);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "盆地整域概览来自生产列且无采样失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        renderDirectionTransfers(out, raw, cx, cz, radius, relations, transfers);
        renderChannels(out, raw, cx, cz, radius, channels);
        var g = raw.createGraphics();
        for (int i = 0; i < relations.basins().size(); i++) {
            var basin = relations.basins().get(i);
            var members = new java.util.HashSet<>(basin.catchment());
            g.setColor(i == 0 ? java.awt.Color.GREEN : java.awt.Color.MAGENTA);
            for (var c : members) {
                boolean rim = false;
                for (int[] d : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}})
                    rim |= !members.contains(new SharedBoundaryDrainage.WorldCell(c.x() + d[0], c.z() + d[1]));
                if (rim) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            }
            g.setColor(java.awt.Color.ORANGE);
            for (var c : basin.storage()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            g.setColor(java.awt.Color.YELLOW);
            for (var shore : basin.lowestShores()) for (var c : shore.downstream().path())
                g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
        }
        g.dispose(); writeLocalImage(out.resolve("catchments.png"), raw, cx, cz, radius,
                "GREEN/MAGENTA = LOCAL CATCHMENT RIMS / ORANGE = STORAGE / YELLOW = DESCENT / NOT WATER");
        var labels = raw.createGraphics(); labels.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10));
        for (int i = 0; i < ranges.basins().size(); i++) {
            var budget = ranges.basins().get(i); int x = budget.sink().x() - cx + radius, z = budget.sink().z() - cz + radius;
            String label = String.format(java.util.Locale.ROOT, "B%d FULL Q=[%.3f,+INF] MODEL ONLY", i, budget.conditionalRelease().lower());
            int labelWidth = labels.getFontMetrics().stringWidth(label) + 3;
            int lx = Math.max(0, Math.min(raw.getWidth() - labelWidth, x - 100)), lz = Math.max(12, z - 10);
            labels.setColor(java.awt.Color.BLACK); labels.fillRect(lx, lz - 10, labelWidth, 13);
            labels.setColor(java.awt.Color.WHITE); labels.drawString(label, lx + 1, lz);
        }
        labels.dispose(); writeLocalImage(out.resolve("budget-ranges.png"), raw, cx, cz, radius,
                "FIXED FULL MODEL / NONNEGATIVE EXTERNAL INPUT / Q BOUNDS NOT ACTUAL WATER OR CERTIFIED FLOW");
        Files.writeString(out.resolve("manifest.txt"), "513x513 raw data area, displayed x3. Fixed diagnostic patch, not canonical radius700.\n"
                + "Current provider water unchanged. Rims are local membership, not global runoff authority.\n" + LOG);
        log("BASIN_OVERVIEW_DIR=" + out.toAbsolutePath());
    }

    private static void renderDirectionTransfers(Path out, java.awt.image.BufferedImage production,
            int cx, int cz, int radius, SharedBlockDrainage.BasinRelations relations,
            List<SharedBlockDrainage.DirectionTransfer> transfers) throws Exception {
        var image = new java.awt.image.BufferedImage(production.getWidth(), production.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.drawImage(production, 0, 0, null);
        g.setColor(java.awt.Color.GRAY);
        for (int x = Math.floorDiv(cx - radius, hp().tract()) * hp().tract(); x <= cx + radius; x += hp().tract())
            g.drawLine(x - cx + radius, 0, x - cx + radius, image.getHeight() - 1);
        for (int z = Math.floorDiv(cz - radius, hp().tract()) * hp().tract(); z <= cz + radius; z += hp().tract())
            g.drawLine(0, z - cz + radius, image.getWidth() - 1, z - cz + radius);
        var owner = new HashMap<SharedBoundaryDrainage.WorldCell, Integer>();
        for (int i = 0; i < relations.basins().size(); i++)
            for (var cell : relations.basins().get(i).catchment()) owner.put(cell, i);
        for (var t : transfers) {
            int basin = owner.getOrDefault(t.terminal(), -1);
            g.setColor(basin == 0 ? java.awt.Color.GREEN : basin == 1 ? java.awt.Color.MAGENTA
                    : t.end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE ? java.awt.Color.BLUE
                    : t.end() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME ? java.awt.Color.RED : java.awt.Color.ORANGE);
            g.drawLine(t.from().x() - cx + radius, t.from().z() - cz + radius,
                    t.to().x() - cx + radius, t.to().z() - cz + radius);
        }
        g.dispose(); writeLocalImage(out.resolve("direction-transfers.png"), image, cx, cz, radius,
                "SURFACE EDGES NOT RIVERS / GREEN B0 / MAGENTA B1 / BLUE SEA CANDIDATE / RED UNKNOWN / ORANGE OTHER SINK");
        Files.copy(OUT.resolve("direction-transfers.csv"), out.resolve("direction-transfers.csv"));
    }

    private static void renderChannels(Path out, java.awt.image.BufferedImage production,
            int cx, int cz, int radius, SharedBlockDrainage.Channels channels) throws Exception {
        var image = new java.awt.image.BufferedImage(production.getWidth(), production.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.drawImage(production, 0, 0, null);
        for (var edge : channels.edges(hp().tract())) {
            g.setColor(edge.inputPending() ? java.awt.Color.ORANGE : java.awt.Color.YELLOW);
            var a = edge.id().from(); var b = edge.id().to();
            g.drawLine(a.x() - cx + radius, a.z() - cz + radius, b.x() - cx + radius, b.z() - cz + radius);
        }
        g.dispose(); writeLocalImage(out.resolve("selected-channels.png"), image, cx, cz, radius,
                "FIXED GRAPH AREA SELECTION / YELLOW LOCAL / ORANGE INPUT PENDING / NOT GENERATED WATER / BASIN RELEASE NOT INJECTED");
        var csv = new StringBuilder("fromX,fromZ,toX,toZ,tract,localAreaBlocks,inputPending\n");
        for (var edge : channels.edges(hp().tract())) {
            var id = edge.id();
            csv.append(id.from().x()).append(',').append(id.from().z()).append(',').append(id.to().x()).append(',')
                    .append(id.to().z()).append(',').append(id.tract()).append(',').append(edge.contributingBlocks())
                    .append(',').append(edge.inputPending()).append('\n');
        }
        Files.writeString(out.resolve("selected-channel-edges.csv"), csv.toString());
    }

    private static void auditSinkProduction(SharedBlockDrainage.LocalBasin basin) {
        int x = basin.sink().x(), z = basin.sink().z();
        var pipe = PROV.pipeForProbe(x, z);
        int ox = Math.floorDiv(x, hp().tract()) * hp().tract() + hp().tract() / 2 - hp().fineRadius();
        int oz = Math.floorDiv(z, hp().tract()) * hp().tract() + hp().tract() / 2 - hp().fineRadius();
        int k = Math.floorDiv(x - ox, pipe.cellBlocks()) * pipe.nz() + Math.floorDiv(z - oz, pipe.cellBlocks());
        int id = LakeWaterBalance.basinOf(pipe.lakeResult(), k);
        var decision = PROV.sharedLakeDecision(x, z);
        double bed = GEN.erodedHeight(x / TP.horizontalScale(), z / TP.horizontalScale());
        double sill = basin.lowestShores().isEmpty() ? Double.NaN : basin.lowestShores().get(0).sill();
        log("SINK_DEPTH realBed=" + bed + " blockSill=" + sill + " blockDepth=" + (sill - bed)
                + " coarseDepth=" + (pipe.level()[k] - pipe.bed()[k]) + " minVisibleDepth=" + LakeWaterBalance.MIN_VISIBLE_DEPTH);
        auditCoarsePotential(pipe, k);
        log("SINK_PRODUCTION point=" + x + "," + z + " ownerBasin=" + id + " ownerLake=" + pipe.lake()[k]
                + " coarseBed=" + pipe.bed()[k] + " ownerSurface=" + pipe.waterSurfaceAt(k) + " shared=" + decision);
        if (id >= 0) log("SINK_OWNER_BALANCE=" + pipe.lakeResult().basins().get(id));
        if (decision.controlled()) {
            var shared = PROV.domainForProbe(decision.key()); var p = shared.pipeForProbe();
            int b = LakeWaterBalance.basinOf(p.lakeResult(), shared.cellAt(x, z));
            log("SINK_SHARED_BASIN=" + b + " balance=" + (b < 0 ? "none" : p.lakeResult().basins().get(b)));
        }
        Map<Long, List<HydrologyBlockCarvedColumn>> chunks = new HashMap<>();
        Map<Integer, Integer> ownerIds = new java.util.TreeMap<>(); int lakes = 0, wet = 0;
        for (var c : basin.storage()) {
            // All sampled storage must be in the same owner pipe before comparing identities.
            if (Math.floorDiv(c.x(), hp().tract()) == Math.floorDiv(x, hp().tract())
                    && Math.floorDiv(c.z(), hp().tract()) == Math.floorDiv(z, hp().tract())) {
                int cell = Math.floorDiv(c.x() - ox, pipe.cellBlocks()) * pipe.nz() + Math.floorDiv(c.z() - oz, pipe.cellBlocks());
                ownerIds.merge(LakeWaterBalance.basinOf(pipe.lakeResult(), cell), 1, Integer::sum);
            }
            int cx = Math.floorDiv(c.x(), 16), cz = Math.floorDiv(c.z(), 16);
            var cols = chunks.computeIfAbsent(((long) cx << 32) ^ (cz & 0xffffffffL),
                    key -> PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz)));
            for (var col : cols) if (col.blockX() == c.x() && col.blockZ() == c.z()) {
                if (col.lakePlan()) lakes++;
                if (col.fillWater() && col.waterSurfaceY() > col.carvedGroundY()) wet++;
            }
        }
        log("SINK_STORAGE_PRODUCTION ownerIds=" + ownerIds + " lakeCols=" + lakes + " wetCols=" + wet + "/" + basin.storage().size());
    }

    private static void auditCoarsePotential(HydrologyPipeline.Result pipe, int start) {
        var seen = new java.util.HashSet<Integer>(); var queue = new java.util.ArrayDeque<Integer>();
        boolean potential = !pipe.sea()[start] && pipe.resolved()[start]
                && pipe.level()[start] - pipe.bed()[start] > 1e-6;
        if (potential) { seen.add(start); queue.add(start); }
        double sill = Double.NEGATIVE_INFINITY, lowest = Double.POSITIVE_INFINITY;
        int published = 0;
        while (!queue.isEmpty()) {
            int k = queue.remove(); sill = Math.max(sill, pipe.level()[k]); lowest = Math.min(lowest, pipe.bed()[k]);
            if (LakeWaterBalance.basinOf(pipe.lakeResult(), k) >= 0) published++;
            for (int[] dir : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                int x = k / pipe.nz() + dir[0], z = k % pipe.nz() + dir[1];
                if (x < 0 || z < 0 || x >= pipe.nx() || z >= pipe.nz()) continue;
                int p = x * pipe.nz() + z;
                if (!pipe.sea()[p] && pipe.resolved()[p] && pipe.level()[p] - pipe.bed()[p] > 1e-6
                        && seen.add(p)) queue.add(p);
            }
        }
        double depth = sill - lowest;
        log("SINK_COARSE_POTENTIAL cells=" + seen.size() + " sill=" + sill + " lowest=" + lowest
                + " maxDepth=" + depth + " publishedCells=" + published + " resolved=" + pipe.resolved()[start]);
        chk(potential && depth > 0 && depth < LakeWaterBalance.MIN_VISIBLE_DEPTH && published == 0,
                "整粗格潜盆已识别但最深不足可见门槛（非粗格漏盆/已有湖）", "depth=" + depth + " published=" + published);
    }

    private static void auditSinkPair(List<SharedBlockDrainage.LocalBasin> basins) {
        var larger = basins.get(0); var smaller = basins.get(1);
        if (larger.lowestShores().size() != 1 || smaller.lowestShores().size() != 1) {
            chk(false, "两盆成对关系非空且出口单一（不强选平局）", "missing or ambiguous shores"); return;
        }
        var a = larger.lowestShores().get(0); var b = smaller.lowestShores().get(0);
        var end = b.downstream().path().get(b.downstream().path().size() - 1);
        var catchment = new java.util.HashSet<>(larger.catchment());
        int overlap = 0; for (var c : smaller.catchment()) if (catchment.contains(c)) overlap++;
        boolean returns = a.downstream().path().stream().anyMatch(smaller.catchment()::contains);
        log("SINK_PAIR smallerSill=" + b.sill() + " largerSill=" + a.sill() + " mapsToLarger=" + end.equals(larger.sink())
                + " overlapCatchment=" + overlap + " returnToSmaller=" + returns
                + " netOutflow=UNKNOWN (volume/catchment size is not a runoff or overflow certificate)");
        chk(end.equals(larger.sink()) && b.sill() > a.sill() && overlap == 0 && !returns,
                "真实小盆→大盆方向、鞍口下降与成员单归属（几何证据非水量）", "invalid pair");
        Path out = OUT.resolve("sink-chain-view-" + System.currentTimeMillis());
        try {
            Files.createDirectories(out);
            int cx = -620, cz = 630, radius = 112;
            var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
            chk(mask.failCount == 0, "两盆链图取生产列无失败", "fail=" + mask.failCount);
            var raw = HierarchicalComparisonView.render(mask, GEN);
            writeLocalImage(out.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
            var g = raw.createGraphics(); g.setColor(java.awt.Color.ORANGE);
            for (var basin : basins) for (var c : basin.storage()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            g.setColor(java.awt.Color.YELLOW);
            for (var basin : basins) for (var s : basin.lowestShores()) {
                for (var c : s.downstream().path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
                g.setColor(java.awt.Color.RED); g.drawOval(s.inner().x() - cx + radius - 2, s.inner().z() - cz + radius - 2, 4, 4);
                g.setColor(java.awt.Color.YELLOW);
            }
            g.dispose(); writeLocalImage(out.resolve("chain.png"), raw, cx, cz, radius, "ORANGE = STORAGE / YELLOW = DESCENT / NO OUTFLOW CLAIM");
            Files.writeString(out.resolve("manifest.txt"), "provider water unchanged; geometric small basin -> large basin -> ocean candidate; volume NOT flux\n" + LOG);
            log("SINK_CHAIN_VIEW_DIR=" + out.toAbsolutePath());
        } catch (Exception ex) { chk(false, "两盆链图写入", ex.toString()); }
        chk(false, "生产推广需要盆体水量/出口原子权威（当前未实现，不以几何链PASS替代）", "WATER_BUDGET_PENDING");
    }

    private static void renderSink(SharedBlockDrainage.LocalBasin basin) throws Exception {
        int cx = basin.sink().x(), cz = basin.sink().z(), radius = 48;
        Path dir = OUT.resolve("sink-view-" + cx + "-" + cz + "-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "洼地底图取生产列无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics(); g.setColor(java.awt.Color.ORANGE);
        for (var c : basin.storage()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
        g.setColor(java.awt.Color.YELLOW);
        for (var s : basin.lowestShores()) {
            for (var c : s.downstream().path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            g.setColor(java.awt.Color.RED); g.drawOval(s.inner().x() - cx + radius - 2, s.inner().z() - cz + radius - 2, 4, 4);
            g.setColor(java.awt.Color.YELLOW);
        }
        g.dispose(); writeLocalImage(dir.resolve("basin.png"), raw, cx, cz, radius, "ORANGE = STORAGE / YELLOW = SHORE DESCENT / NOT WATER");
        Files.writeString(dir.resolve("manifest.txt"), "provider columnsFor water unchanged; diagnostic local catchment/storage NOT published lake membership\n" + LOG);
        log("SINK_VIEW_DIR=" + dir.toAbsolutePath());
    }

    private static void secDirectionView() throws Exception {
        log("\n=== [direction-view] 真实方块方向层，生产水不变 ===");
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var a = SharedBlockDrainage.buildAt(-768, 512, 256, 4, TP.seaLevel(), terrain, ocean, false);
        var b = SharedBlockDrainage.buildAt(-256, 512, 256, 4, TP.seaLevel(), terrain, ocean, true);
        var da = SharedBlockDrainage.direction(a, false);
        var whole = SharedBlockDrainage.combine(List.of(a, b)); var direction = SharedBlockDrainage.direction(whole, false);
        chk(direction.sameDirection(SharedBlockDrainage.direction(whole, true)), "真实方向逆序全数组一致", "different");
        int checked = 0, mismatch = 0;
        for (int x = -1024; x <= -516; x += 4) for (int z = 260; z <= 764; z += 4) {
            var local = da.at(x, z); var combined = direction.at(x, z);
            if (local.state() == SharedBlockDrainage.DirectionEnd.UNKNOWN_FRAME) continue;
            checked++; if (!local.equals(combined)) mismatch++;
        }
        log("DIRECTION_SCOPE checked=" + checked + " mismatches=" + mismatch);
        chk(checked > 0 && mismatch == 0, "完整组件方向不随同覆盖扩展改变（待决不伪装一致）", "mismatch=" + mismatch);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "方向层零邻fine求解", "increased");
        var traces = new ArrayList<SharedBlockDrainage.DirectionTrace>();
        for (int x : new int[]{-712, -648, -580}) {
            var trace = direction.trace(x, 504); traces.add(trace);
            log("DIRECTION_SCENE x=" + x + " end=" + trace.end() + " nodes=" + trace.path().size()
                    + " flatEdges=" + trace.flatEdges() + " maxRise=" + trace.maxBedRise()
                    + " terminal=" + trace.path().get(trace.path().size() - 1));
            chk(continuous(trace.path()) && trace.maxBedRise() == 0, "真实方向通路四邻连续/床面非升 x=" + x, trace.toString());
        }
        renderDirection(traces);
        directionCoverage(terrain, ocean);
        chk(traces.stream().allMatch(t -> t.end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE),
                "三现场方向链抵达已知海候选（洼地/未知不宽松通过）", traces.stream().map(SharedBlockDrainage.DirectionTrace::end).toList().toString());
        log("NOT VERIFIED: 海候选不是全球海证书；BASIN_PENDING不是闭流湖；方向不等于水面或已生成河道。");
        Files.writeString(OUT.resolve("direction.txt"), LOG.toString());
    }

    private static void directionCoverage(WindowOutlets.Terrain terrain,
            java.util.function.BiPredicate<Integer, Integer> ocean) {
        Map<SharedBoundaryDrainage.Key, List<ProductionDrainageAudit.Crossing>> groups = new java.util.LinkedHashMap<>();
        for (int[] r : new int[][]{{-2, 0}, {-1, 0}, {-1, -1}, {0, -1}}) {
            var snapshot = PROV.drainageForProbe(r[0], r[1]);
            for (var event : snapshot.crossings()) {
                if (!event.upstream().equals(snapshot.region())) continue;
                double along = event.axis() == 0 ? event.point().z() : event.point().x();
                var key = new SharedBoundaryDrainage.Key(event.axis(), event.boundary(), (int) Math.floor(along / hp().tract()));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
            }
        }
        int events = 0, matched = 0, sea = 0;
        Map<SharedBlockDrainage.DirectionEnd, Integer> ends = new java.util.EnumMap<>(SharedBlockDrainage.DirectionEnd.class);
        for (var group : groups.entrySet()) {
            long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
            var d = SharedBlockDrainage.build(group.getKey(), hp().tract(), hp().fineRadius(), hp().step(), TP.seaLevel(), terrain, ocean);
            var direction = SharedBlockDrainage.direction(d, false);
            chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "全过界方向零邻fine " + group.getKey(), "increased");
            for (var event : group.getValue()) {
                int x = (int) Math.round(event.point().x() - 2 * hp().step() * event.tangent().x());
                int z = (int) Math.round(event.point().z() - 2 * hp().step() * event.tangent().z());
                var trace = direction.trace(x, z);
                boolean hit = blockPathCrossings(event.segment(), trace.path()).stream().anyMatch(c -> c.axis() == event.axis()
                        && c.boundary() == event.boundary() && c.upstream().equals(event.upstream()) && c.downstream().equals(event.downstream()));
                events++; if (hit) matched++; if (trace.end() == SharedBlockDrainage.DirectionEnd.OCEAN_CANDIDATE) sea++;
                ends.merge(trace.end(), 1, Integer::sum);
                chk(continuous(trace.path()) && trace.maxBedRise() == 0, "全过界真实下降连续", trace.end().toString());
                log("DIRECTION_EVENT=" + event + " matched=" + hit + " end=" + trace.end() + " nodes=" + trace.path().size()
                        + " terminal=" + trace.path().get(trace.path().size() - 1));
            }
        }
        log("DIRECTION_COVERAGE events=" + events + " matched=" + matched + " oceanCandidates=" + sea + " terminals=" + ends);
        chk(events > 0 && matched == events, "方向层覆盖全部旧实际过界（不删河凑PASS）", "matched=" + matched + "/" + events);
        chk(events > 0 && sea == events, "全部方向链有海候选接续（洼地/未知保留FAIL，仍非生命周期认证）", "sea=" + sea + "/" + events);
    }

    private static void renderDirection(List<SharedBlockDrainage.DirectionTrace> traces) throws Exception {
        int cx = -650, cz = 512, radius = 96;
        Path dir = OUT.resolve("direction-view-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "方向对照底图来自生产列且无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics();
        for (var trace : traces) {
            g.setColor(java.awt.Color.YELLOW);
            for (var c : trace.path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            var end = trace.path().get(trace.path().size() - 1);
            g.setColor(java.awt.Color.RED); g.drawOval(end.x() - cx + radius - 2, end.z() - cz + radius - 2, 4, 4);
        }
        g.dispose(); writeLocalImage(dir.resolve("direction.png"), raw, cx, cz, radius, "YELLOW = TERRAIN DIRECTION / RED = TERMINAL / NOT WATER");
        Files.writeString(dir.resolve("manifest.txt"), "provider columnsFor actual water unchanged; center(-650,512)radius96 1pixel/block x3\n"
                + "yellow=full-four-neighbour terrain direction, NOT generated water; red=pending or candidate terminal\n" + LOG);
        log("DIRECTION_VIEW_DIR=" + dir.toAbsolutePath());
    }

    private static void summarySynthetic() throws Exception {
        log("\n=== [summary-synth] 不带窗口parent的边界容量树 ===");
        for (int step : new int[]{2, 4, 8}) for (int seed = 0; seed < 4; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> Math.floorMod((int) x * 7 + (int) z * 13 + (int) x * (int) z + s * 3, 17) - 4;
            var a = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, (x, z) -> x == 8, false);
            var b = SharedBlockDrainage.buildAt(16, 0, 8, step, 0, terrain, (x, z) -> x == 8, true);
            var whole = SharedBlockDrainage.combine(List.of(a, b));
            java.util.function.BiPredicate<Integer, Integer> pa = (x, z) -> domainFrame(a, x, z) || (x == 0 && z == 0);
            java.util.function.BiPredicate<Integer, Integer> pb = (x, z) -> domainFrame(b, x, z);
            var sa = SharedBoundaryTransfer.build(a, pa); var sb = SharedBoundaryTransfer.build(b, pb);
            var result = SharedBoundaryTransfer.solve(List.of(sa, sb));
            int mismatch = 0;
            for (var e : result.entrySet()) if (!e.getValue().equals(whole.capacityAt(e.getKey().x(), e.getKey().z()))) mismatch++;
            chk(mismatch == 0 && !result.isEmpty(), "边界树双域与全方块组合容量一致 step=" + step + " seed=" + seed,
                    "mismatch=" + mismatch);
            chk(sa.vertices().size() <= a.nodes() && sa.edges().size() < a.treeEdges()
                            && result.equals(SharedBoundaryTransfer.solve(List.of(sb, sa))),
                    "压缩边数且两域交换不改容量", "vertices=" + sa.vertices().size() + " edges=" + sa.edges().size());
            chk(sa.equals(SharedBoundaryTransfer.build(a, pa)), "重复冷摘要逐位相同", "different");
            int lo = a.originX(), hi = lo + a.sizeX() - 1, pairs = 0, badPairs = 0;
            var boundary = sa.vertices().stream().map(SharedBoundaryTransfer.Vertex::point)
                    .filter(c -> domainFrame(a, c.x(), c.z())).toList();
            for (int i = 0; i < boundary.size(); i += 7) for (int j = i; j < boundary.size(); j += 11) {
                double expected = blockBarrierOracle(boundary.get(i), boundary.get(j), terrain, lo, hi);
                if (summaryPairBarrier(sa, boundary.get(i), boundary.get(j)) != expected) badPairs++;
                pairs++;
            }
            chk(badPairs == 0 && pairs > 0, "端口两两容量独立方块BFS step=" + step + " seed=" + seed + " pairs=" + pairs,
                    "badPairs=" + badPairs);
        }
        WindowOutlets.Terrain valley = (x, z) -> z == 0 ? 1 : 10;
        var d = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, valley, (x, z) -> false, false);
        java.util.function.BiPredicate<Integer, Integer> ports = (x, z) -> domainFrame(d, x, z) || (x == 0 && z == 0);
        var summary = SharedBoundaryTransfer.build(d, ports);
        var values = SharedBoundaryTransfer.solve(List.of(summary));
        chk(Double.isInfinite(values.get(new SharedBoundaryDrainage.WorldCell(0, 0)).sea()),
                "无海摘要不发明海终端", values.toString());
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); SharedBoundaryTransfer.build(d, ports); }
        catch (java.util.concurrent.CancellationException e) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(cancel && summary.equals(SharedBoundaryTransfer.build(d, ports)), "中断压缩不污染/重试同值", "missed");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBoundaryTransfer.Product>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> SharedBoundaryTransfer.build(d, ports)));
            boolean same = true;
            for (var f : futures) same &= summary.equals(f.get(30, TimeUnit.SECONDS));
            chk(same, "四线程冷摘要逐位同值", "different");
        } finally { pool.shutdownNow(); }
        summaryContractSynthetic();
        sampledTransferSynthetic();
    }

    private static void sampledTransferSynthetic() {
        var random=new java.util.Random(3701);boolean exact=true;int comparisons=0;
        for(int trial=0;trial<24;trial++) {
            int nx=5+trial%3,nz=6+trial%2,step=4;double[] h=new double[nx*nz];
            for(int k=0;k<h.length;k++)h[k]=random.nextInt(17)-8;
            var transfer=SharedBoundaryTransfer.grid(-32,-24,nx,nz,step,h);
            for(int start:new int[]{0,nz+2,h.length/2,h.length-1}) {
                var at=new SharedBoundaryDrainage.WorldCell(-32+start/nz*step,-24+start%nz*step);
                var actual=SharedBoundaryTransfer.portCosts(transfer,at);double[] costs=sampledTransferOracle(h,nx,nz,start);
                exact &= actual.size()==2*nx+2*nz-4 && transfer.edges().size()==h.length-1;
                for(var entry:actual.entrySet()) {int k=(entry.getKey().x()+32)/step*nz+(entry.getKey().z()+24)/step;
                    exact &= costs[k]==entry.getValue();comparisons++;}
            }
            double old=h[0];h[0]=999;exact &= transfer.vertices().get(0).bed()==old;
        }
        chk(exact && comparisons>0,"已采样D8容量树全部端口对独立Bellman松弛oracle，负坐标/矩形/等高/输入冻结 comparisons="+comparisons,"mismatch");
        double[] h={0,50,50,0};var t=SharedBoundaryTransfer.grid(-4,-4,2,2,4,h);
        chk(SharedBoundaryTransfer.portCosts(t,new SharedBoundaryDrainage.WorldCell(-4,-4)).get(new SharedBoundaryDrainage.WorldCell(0,0))==0,
                "D8对角连接不能偷换四邻域：正交墙不阻断已声明对角边","operator changed");
        boolean cancelled=false;try{Thread.currentThread().interrupt();SharedBoundaryTransfer.grid(-4,-4,2,2,4,h);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        chk(cancelled && t.equals(SharedBoundaryTransfer.grid(-4,-4,2,2,4,h)),"采样容量树取消后重试同值","partial");
        sampledConditionSynthetic();
        sampledAssemblySynthetic();
    }

    private static void sampledAssemblySynthetic() {
        var random=new java.util.Random(3801); boolean exact=true, order=true; int comparisons=0;
        for(int trial=0;trial<32;trial++) {
            int n=4,step=4;var inputs=new ArrayList<SharedBoundaryTransfer.GridTransfer>();
            var beds=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
            int[][] offsets=trial%4==0?new int[][]{{0,0},{4,0},{0,4},{4,4}}:trial%4==1?new int[][]{{0,0},{3,0},{0,3},{3,3}}
                    :trial%4==2?new int[][]{{0,0},{2,2},{5,0}}:new int[][]{{0,0},{4,4},{9,0}};
            for(var offset:offsets) {
                double[] h=new double[n*n];int ox=-32+offset[0]*step,oz=-24+offset[1]*step;
                for(int k=0;k<h.length;k++) {var at=new SharedBoundaryDrainage.WorldCell(ox+k/n*step,oz+k%n*step);
                    h[k]=beds.computeIfAbsent(at,ignored->(double)random.nextInt(13)-6);}
                inputs.add(SharedBoundaryTransfer.grid(ox,oz,n,n,step,h));
            }
            var assembly=SharedBoundaryTransfer.assemble(inputs);
            var terminals=Map.of(inputs.get(0).vertices().get(n+1).point(),-2.0);
            var actual=SharedBoundaryTransfer.capacities(assembly,terminals);
            var expected=sampledAssemblyOracle(beds,step,terminals);exact &= actual.equals(expected);comparisons+=actual.size();
            var reversed=new ArrayList<>(inputs);java.util.Collections.reverse(reversed);
            order &= assembly.equals(SharedBoundaryTransfer.assemble(reversed));
            var noTerminals=SharedBoundaryTransfer.capacities(assembly,Map.of());
            exact &= noTerminals.values().stream().allMatch(c->Double.isInfinite(c.terminal()));
        }
        chk(exact && comparisons>0,"32随机D8组合对独立全邻接Bellman oracle：相接/重叠/四角/缺片/不连通 comparisons="+comparisons,"different");
        chk(order,"输入逆序不改组合全部顶点、树边、未知端口，不依赖缓存或调用顺序","different");
        assemblyContractSynthetic();
    }

    private static Map<SharedBoundaryDrainage.WorldCell,SharedBoundaryTransfer.GridCapacity> sampledAssemblyOracle(
            Map<SharedBoundaryDrainage.WorldCell,Double> beds,int step,Map<SharedBoundaryDrainage.WorldCell,Double> terminals) {
        var known=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();var unknown=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var at:beds.keySet()) {
            boolean frame=false;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0 || dz!=0)
                frame |= !beds.containsKey(new SharedBoundaryDrainage.WorldCell(at.x()+dx*step,at.z()+dz*step));
            known.put(at,terminals.containsKey(at)?Math.max(beds.get(at),terminals.get(at)):Double.POSITIVE_INFINITY);
            unknown.put(at,frame?beds.get(at):Double.POSITIVE_INFINITY);
        }
        for(int pass=0;pass<beds.size();pass++) {boolean changed=false;
            for(var at:beds.keySet())for(var next:beds.keySet()) {
                long dx=Math.abs((long)at.x()-next.x()),dz=Math.abs((long)at.z()-next.z());
                if(at.equals(next) || dx>step || dz>step)continue;
                double a=Math.max(known.get(at),beds.get(next)),b=Math.max(unknown.get(at),beds.get(next));
                if(a<known.get(next)){known.put(next,a);changed=true;}if(b<unknown.get(next)){unknown.put(next,b);changed=true;}
            }
            if(!changed)break;
        }
        var result=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryTransfer.GridCapacity>();
        beds.keySet().forEach(at->result.put(at,new SharedBoundaryTransfer.GridCapacity(known.get(at),unknown.get(at))));return result;
    }

    private static void assemblyContractSynthetic() {
        double[] bed={9,9,9,9,0,9,9,9,9};
        var a=SharedBoundaryTransfer.grid(-4,-4,3,3,4,bed);
        var assembly=SharedBoundaryTransfer.assemble(List.of(a,a));
        var values=SharedBoundaryTransfer.capacities(assembly,Map.of(new SharedBoundaryDrainage.WorldCell(0,0),0.0));
        chk(values.get(new SharedBoundaryDrainage.WorldCell(0,0)).equals(new SharedBoundaryTransfer.GridCapacity(0,9))
                && assembly.ports().size()==8,"明确内部终端与未知逃逸屏障分列，重复片不增加源/终端或留下内框","different");
        double[] flat=new double[9];var b=SharedBoundaryTransfer.grid(8,-4,3,3,4,flat);
        boolean conflict=false,scale=false,limit=false,cancelled=false,immutable=false,invalid=false;
        try{SharedBoundaryTransfer.assemble(List.of(a,SharedBoundaryTransfer.grid(-4,-4,3,3,4,flat)));}catch(IllegalArgumentException ex){conflict=true;}
        try{SharedBoundaryTransfer.assemble(List.of(a,SharedBoundaryTransfer.grid(-4,-4,3,3,2,bed)));}catch(IllegalArgumentException ex){scale=true;}
        try{SharedBoundaryTransfer.assemble(List.of(a,a,a,a,a));}catch(IllegalArgumentException ex){limit=true;}
        try{Thread.currentThread().interrupt();SharedBoundaryTransfer.assemble(List.of(a,b));}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        try{assembly.ports().clear();}catch(UnsupportedOperationException ex){immutable=true;}
        try{SharedBoundaryTransfer.capacities(assembly,Map.of(new SharedBoundaryDrainage.WorldCell(0,0),Double.NaN));}
        catch(IllegalArgumentException ex){invalid=true;}
        chk(conflict && scale && limit && cancelled && immutable && invalid
                && assembly.equals(SharedBoundaryTransfer.assemble(List.of(a,a))),"组合床高冲突/尺度/预算/NaN拒绝，只读与取消重试同值","missed");
        var joined=SharedBoundaryTransfer.assemble(List.of(a,b));var ports=new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
        for(int k:joined.ports())ports.add(joined.vertices().get(k).point());
        chk(!ports.contains(new SharedBoundaryDrainage.WorldCell(4,0)) && !ports.contains(new SharedBoundaryDrainage.WorldCell(8,0)),
                "相邻已有D8覆盖消除两侧人工内框（不封闭真正缺片外框）","internal frame retained");
        exteriorSynthetic();
    }

    private static SharedBoundaryTransfer.ExteriorObservation exteriorFixture(SharedBoundaryDrainage.WorldCell at,
            SharedBoundaryDrainage.WorldCell next,double level,boolean resolved,boolean lake,boolean sea) {
        return new SharedBoundaryTransfer.ExteriorObservation(at,new ProductionDrainageAudit.Region(Math.floorDiv(at.x(),16),Math.floorDiv(at.z(),16)),
                level,level,resolved,lake,sea,next);
    }

    private static void exteriorSynthetic() {
        var policy=new SharedBoundaryDrainage.WorldPolicy(1,2,1,16,4);
        var assembly=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-4,-4,3,3,4,new double[9])));
        var edge=new SharedBoundaryDrainage.WorldEdge(new SharedBoundaryDrainage.WorldCell(4,0),new SharedBoundaryDrainage.WorldCell(8,0));
        var edges=SharedBoundaryTransfer.exteriorEdges(assembly);
        chk(edges.size()==32 && edges.stream().distinct().count()==32 && edges.contains(edge),"完整D8外框保留全部正交/斜穿边，3x3独立边数32","missing");
        var none=SharedBoundaryTransfer.exterior(policy,assembly,at->null,16);
        var cap=SharedBoundaryTransfer.exteriorCapacities(assembly,none,Map.of(),0);
        chk(none.traces().values().stream().allMatch(t->t.state()==SharedBoundaryTransfer.ExteriorState.OUTSIDE_INPUT)
                && cap.values().stream().allMatch(c->Double.isInfinite(c.terminal()) && c.unknown()==0),"缺外部输入不当干/海/闭墙，保持全部未知低界","wrong");
        var pending=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,null,-1,false,false,true),16);
        chk(pending.traces().values().stream().allMatch(t->t.state()==SharedBoundaryTransfer.ExteriorState.INPUT_PENDING),"粗层未决优先于海候选，有限level不自动认证","wrong");
        var back=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,new SharedBoundaryDrainage.WorldCell(4,0),0,true,false,false),16);
        chk(back.traces().get(edge).state()==SharedBoundaryTransfer.ExteriorState.REENTERS_DOMAIN,"外邻父链回到本计算域不是合法外部出口","wrong");
        var cycle=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,new SharedBoundaryDrainage.WorldCell(at.x()==8?12:8,0),0,true,false,false),16);
        chk(cycle.traces().get(edge).state()==SharedBoundaryTransfer.ExteriorState.CYCLE,"跨外部owner后继环不能认证下游","wrong");
        var rise=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,new SharedBoundaryDrainage.WorldCell(12,0),at.x()==8?0:1,true,false,at.x()==12),16);
        chk(rise.traces().get(edge).state()==SharedBoundaryTransfer.ExteriorState.INVALID_SUCCESSOR,"到海候选之前的反坡也必须拒绝，不让终端覆盖反坡","wrong");
        var ocean=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,null,-1,true,false,true),16);
        var oceanCap=SharedBoundaryTransfer.exteriorCapacities(assembly,ocean,Map.of(),0);
        chk(ocean.traces().values().stream().allMatch(t->t.state()==SharedBoundaryTransfer.ExteriorState.OCEAN_CANDIDATE)
                && oceanCap.values().stream().allMatch(c->c.terminal()==0 && c.unknown()==0),"粗海路径可形成条件候选但不消除真实侵蚀几何/更低外路未知","wrong");
        boolean owner=false,missing=false,cancel=false,immutable=false;
        try{SharedBoundaryTransfer.exterior(policy,assembly,at->new SharedBoundaryTransfer.ExteriorObservation(at,new ProductionDrainageAudit.Region(999,0),0,0,true,false,false,null),16);}
        catch(IllegalArgumentException ex){owner=true;}
        var dropped=new HashMap<>(ocean.traces());dropped.remove(edge);
        try{SharedBoundaryTransfer.exteriorCapacities(assembly,new SharedBoundaryTransfer.ExteriorFrame(policy,edges,dropped),Map.of(),0);}
        catch(IllegalArgumentException ex){missing=true;}
        try{Thread.currentThread().interrupt();SharedBoundaryTransfer.exterior(policy,assembly,at->null,16);}
        catch(java.util.concurrent.CancellationException ex){cancel=true;}finally{Thread.interrupted();}
        try{ocean.traces().get(edge).path().clear();}catch(UnsupportedOperationException ex){immutable=true;}
        chk(owner && missing && cancel && immutable && none.equals(SharedBoundaryTransfer.exterior(policy,assembly,at->null,16)),
                "外部owner错配/漏边拒绝，深只读/预取消重试同完整产物","missed");
        var basin=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,null,0,true,true,false),16);
        var limit=SharedBoundaryTransfer.exterior(policy,assembly,at->exteriorFixture(at,new SharedBoundaryDrainage.WorldCell(at.x()+4,at.z()),0,true,false,false),1);
        chk(basin.traces().get(edge).state()==SharedBoundaryTransfer.ExteriorState.BASIN_PENDING
                && limit.traces().get(edge).state()==SharedBoundaryTransfer.ExteriorState.LIMIT,"外盆及路径预算终态不伪装海候选，保留未决","wrong");
        boolean[] seen={false};var calls=new HashMap<SharedBoundaryDrainage.WorldCell,Integer>();
        var memo=SharedBoundaryTransfer.exterior(policy,assembly,at->{calls.merge(at,1,Integer::sum);return exteriorFixture(at,null,0,true,false,true);},16);
        seen[0]=calls.values().stream().allMatch(n->n==1);
        chk(seen[0] && memo.edges().size()>calls.size(),"多个外邻边共用同世界观察只读取一次，不因边顺序漂移","not frozen");
        exteriorCapacityOracle(assembly,policy);
        exteriorReturnSynthetic(policy);
    }

    private static void exteriorReturnSynthetic(SharedBoundaryDrainage.WorldPolicy policy) {
        var a=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-4,-4,3,3,4,new double[]{0,0,0,50,50,50,0,0,0})));
        var chain=List.of(new SharedBoundaryDrainage.WorldCell(8,4),new SharedBoundaryDrainage.WorldCell(4,8),
                new SharedBoundaryDrainage.WorldCell(0,8),new SharedBoundaryDrainage.WorldCell(-4,8),new SharedBoundaryDrainage.WorldCell(-4,4));
        var nodes=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryTransfer.ExteriorObservation>();
        for(int k=0;k+1<chain.size();k++)nodes.put(chain.get(k),exteriorFixture(chain.get(k),chain.get(k+1),0,true,false,false));
        var frame=SharedBoundaryTransfer.exterior(policy,a,nodes::get,16);var source=new SharedBoundaryDrainage.WorldCell(4,4);
        var terminal=Map.of(chain.get(chain.size()-1),0.0);
        var before=SharedBoundaryTransfer.capacities(a,terminal);var after=SharedBoundaryTransfer.exteriorCapacities(a,frame,terminal,0);
        chk(before.get(source).terminal()==50 && after.get(source).terminal()==0 && after.get(source).unknown()==0,
                "外部已知路径绕墙回入另一端口须组合关系，不能把回入一概当环或直接当海","lost transfer");
        var dry=SharedBoundaryTransfer.exteriorCapacities(a,frame,Map.of(),0);
        chk(dry.values().stream().allMatch(c->Double.isInfinite(c.terminal())),"回入关系本身不创造终端，无海仍无海","invented terminal");
    }

    private static void exteriorCapacityOracle(SharedBoundaryTransfer.GridAssembly assembly,SharedBoundaryDrainage.WorldPolicy policy) {
        var random=new java.util.Random(3901);boolean exact=true;
        var beds=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();assembly.vertices().forEach(v->beds.put(v.point(),v.bed()));
        for(int trial=0;trial<24;trial++) {
            var external=new HashMap<SharedBoundaryDrainage.WorldCell,SharedBoundaryTransfer.ExteriorObservation>();
            for(var e:SharedBoundaryTransfer.exteriorEdges(assembly))external.computeIfAbsent(e.to(),at->exteriorFixture(at,null,random.nextInt(12)-6,true,false,random.nextBoolean()));
            var frame=SharedBoundaryTransfer.exterior(policy,assembly,external::get,16);
            var expectedSeeds=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
            for(var e:frame.edges())if(external.get(e.to()).sea())expectedSeeds.merge(e.from(),Math.max(0,external.get(e.to()).level()),Math::min);
            var expected=sampledAssemblyOracle(beds,4,expectedSeeds);
            exact &= expected.equals(SharedBoundaryTransfer.exteriorCapacities(assembly,frame,Map.of(),0));
        }
        chk(exact,"24随机完整外部帧条件容量对独立全D8 Bellman重算，未知通道始终保留","different");
        conditionalRoutingSynthetic(policy);
    }

    private static void conditionalRoutingSynthetic(SharedBoundaryDrainage.WorldPolicy policy) {
        int n=9;double[] bed=new double[n*n];for(int k=0;k<bed.length;k++)bed[k]=100-k%n;
        var grid=SharedBoundaryTransfer.grid(-16,-16,n,n,4,bed);var assembly=SharedBoundaryTransfer.assemble(List.of(grid));
        var frame=SharedBoundaryTransfer.exterior(policy,assembly,at->null,16);
        var terminals=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var v:assembly.vertices())if(v.point().z()==16)terminals.put(v.point(),v.bed());
        var routing=SharedBoundaryTransfer.conditionalRouting(assembly,frame,terminals,0);
        var start=new SharedBoundaryDrainage.WorldCell(0,0);var foreign=new SharedBoundaryDrainage.WorldCell(-128,-128);
        var source=new SharedBoundaryDrainage.WorldSource(foreign,start);
        var result=SharedBoundaryDrainage.projectConditional(routing,List.of(source),4096);var route=result.routes().get(source);
        boolean local=true;for(var node:routing.nodes().values())if(node.next()!=null) {
            long dx=Math.abs((long)node.next().x()-node.point().x()),dz=Math.abs((long)node.next().z()-node.point().z());
            local &= dx<=4 && dz<=4 && (dx!=0 || dz!=0) && routing.nodes().get(node.next()).level()<=node.level();
        }
        var unknown=SharedBoundaryTransfer.conditionalRouting(assembly,frame,Map.of(),0);
        chk(local && route.path().size()>2 && route.state()==SharedBoundaryDrainage.WorldState.OCEAN_CANDIDATE
                && unknown.nodes().values().stream().allMatch(node->node.state()==SharedBoundaryDrainage.WorldState.INPUT_PENDING),
                "细D8候选有明确终端才追踪，缺条件全未决而非朝未知框排水",route.toString());
        boolean suffix=true;for(int i=0;i<route.path().size();i++) {
            var receiver=new SharedBoundaryDrainage.WorldSource(foreign,route.path().get(i));var other=SharedBoundaryDrainage.projectConditional(routing,List.of(receiver),4096).routes().get(receiver);
            suffix &= other.path().equals(route.path().subList(i,route.path().size())) && other.state()==route.state();
        }
        var second=new SharedBoundaryDrainage.WorldSource(new SharedBoundaryDrainage.WorldCell(-124,-128),start);
        var both=SharedBoundaryDrainage.projectConditional(routing,List.of(second,source,source),4096);
        chk(suffix && both.routes().get(source).equals(route) && both.support().values().stream().allMatch(s->s.size()==2),
                "同入口两源共边去重、接收每个入口完整后缀同值，不重套源门限","different");
        double[] bowl=new double[n*n];java.util.Arrays.fill(bowl,10);bowl[4*n+4]=0;
        var ba=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-16,-16,n,n,4,bowl)));
        var bowlTerminals=Map.of(new SharedBoundaryDrainage.WorldCell(-16,-16),10.0);
        var br=SharedBoundaryTransfer.conditionalRouting(ba,SharedBoundaryTransfer.exterior(policy,ba,at->null,16),bowlTerminals,0);
        chk(br.nodes().get(start).state()==SharedBoundaryDrainage.WorldState.BASIN_PENDING && br.nodes().get(start).next()==null,
                "条件填平浅盆仍停止，不能因为找到minimax就发布盆内河","promoted");
        boolean cancel=false,immutable=false;
        try{Thread.currentThread().interrupt();SharedBoundaryTransfer.conditionalRouting(assembly,frame,Map.of(),0);}
        catch(java.util.concurrent.CancellationException ex){cancel=true;}finally{Thread.interrupted();}
        try{routing.nodes().clear();}catch(UnsupportedOperationException ex){immutable=true;}
        chk(cancel && immutable && routing.equals(SharedBoundaryTransfer.conditionalRouting(assembly,frame,terminals,0)),
                "条件后继只读，取消重试同值且源集合不参与求后继","missed");
        chk(routing.equals(SharedBoundaryTransfer.conditionalRouting(SharedBoundaryTransfer.assemble(List.of(grid,grid)),frame,terminals,0)),
                "重复已有输入不改条件后继，零额外地形采样","different");
        conditionalBoundarySynthetic(policy);
        exteriorBoundsSynthetic(policy);
    }

    private static void exteriorBoundsSynthetic(SharedBoundaryDrainage.WorldPolicy policy) {
        var assembly=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-16,-16,9,9,4,new double[81])));
        var frame=SharedBoundaryTransfer.exterior(policy,assembly,p->null,16);var outside=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
        for(var edge:frame.edges())outside.put(edge.to(),20.0);
        var bounds=SharedBoundaryTransfer.exteriorBounds(assembly,outside);var start=new SharedBoundaryDrainage.WorldCell(0,0);
        var terminal=Map.of(new SharedBoundaryDrainage.WorldCell(-12,0),10.0);
        var old=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,terminal,0);
        var cap=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,terminal,0,bounds);
        var route=SharedBoundaryTransfer.conditionalRouting(assembly,frame,terminal,0,bounds);
        chk(old.get(start).unknown()==0 && cap.get(start).unknown()==20 && cap.get(start).terminal()==10
                && route.nodes().get(start).state()==SharedBoundaryDrainage.WorldState.BASIN_PENDING,
                "完整高外环排除更低未知路径，但填盆仍待决不冒充出流",cap.get(start).toString());
        outside.put(new SharedBoundaryDrainage.WorldCell(-20,-20),0.0);
        var low=SharedBoundaryTransfer.exteriorBounds(assembly,outside);
        chk(SharedBoundaryTransfer.exteriorCapacities(assembly,frame,terminal,0,low).get(start).unknown()==0
                && SharedBoundaryTransfer.conditionalRouting(assembly,frame,terminal,0,low).nodes().get(start).state()==SharedBoundaryDrainage.WorldState.INPUT_PENDING,
                "仅一个斜角外邻低口也保留未知，不能只采正交边","lost diagonal");
        chk(SharedBoundaryTransfer.exteriorCapacities(assembly,frame,Map.of(),0,bounds).values().stream().allMatch(c->Double.isInfinite(c.terminal()))
                && SharedBoundaryTransfer.conditionalRouting(assembly,frame,Map.of(),0,bounds).nodes().values().stream().allMatch(n->n.state()==SharedBoundaryDrainage.WorldState.INPUT_PENDING),
                "高外环不创造终端或把无海域认证成闭流盆","invented terminal");
        boolean missing=false,extra=false,nan=false,immutable=false,cancel=false;
        var broken=new HashMap<>(outside);broken.remove(frame.edges().get(0).to());
        try{SharedBoundaryTransfer.exteriorBounds(assembly,broken);}catch(IllegalArgumentException ex){missing=true;}
        broken=new HashMap<>(outside);broken.put(start,1.0);
        try{SharedBoundaryTransfer.exteriorBounds(assembly,broken);}catch(IllegalArgumentException ex){extra=true;}
        broken=new HashMap<>(outside);broken.put(frame.edges().get(0).to(),Double.NaN);
        try{SharedBoundaryTransfer.exteriorBounds(assembly,broken);}catch(IllegalArgumentException ex){nan=true;}
        try{bounds.barriers().clear();}catch(UnsupportedOperationException ex){immutable=true;}
        try{Thread.currentThread().interrupt();SharedBoundaryTransfer.exteriorBounds(assembly,outside);}
        catch(java.util.concurrent.CancellationException ex){cancel=true;}finally{Thread.interrupted();}
        chk(missing && extra && nan && immutable && cancel && low.equals(SharedBoundaryTransfer.exteriorBounds(assembly,outside)),
                "外环缺点/多点/NaN拒绝，深只读、取消重试同值","missing validation");
        var returns=SharedBoundaryTransfer.exterior(policy,assembly,p->new SharedBoundaryTransfer.ExteriorObservation(p,
                new ProductionDrainageAudit.Region(Math.floorDiv(p.x(),policy.tract()),Math.floorDiv(p.z(),policy.tract())),0,0,true,false,false,
                new SharedBoundaryDrainage.WorldCell(Math.max(-16,Math.min(16,p.x())),Math.max(-16,Math.min(16,p.z())))),16);
        chk(returns.traces().values().stream().allMatch(t->t.state()==SharedBoundaryTransfer.ExteriorState.REENTERS_DOMAIN)
                && SharedBoundaryTransfer.exteriorCapacities(assembly,returns,terminal,0,bounds).get(start).unknown()==20,
                "粗层低回入捷径不能绕过真实高外环降低未知下界","mixed operator shortcut");
        exteriorBoundsOracle(policy);
    }

    private static void exteriorBoundsOracle(SharedBoundaryDrainage.WorldPolicy policy) {
        var random=new java.util.Random(4101);boolean equal=true;int comparisons=0;
        for(int trial=0;trial<24;trial++) {
            int nx=5,nz=6;double[] bed=new double[nx*nz];for(int k=0;k<bed.length;k++)bed[k]=random.nextInt(20)-5;
            var assembly=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-16,-16,nx,nz,4,bed)));
            var frame=SharedBoundaryTransfer.exterior(policy,assembly,p->null,16);var outside=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
            for(var edge:frame.edges())outside.putIfAbsent(edge.to(),(double)random.nextInt(25)-5);
            var bounds=SharedBoundaryTransfer.exteriorBounds(assembly,outside);
            var actual=SharedBoundaryTransfer.exteriorCapacities(assembly,frame,Map.of(),0,bounds);
            double[] expected=new double[bed.length];java.util.Arrays.fill(expected,Double.POSITIVE_INFINITY);
            for(var edge:frame.edges()) {
                int k=(edge.from().x()+16)/4*nz+(edge.from().z()+16)/4;
                double barrier=Math.max(bed[k],outside.get(edge.to()));var path=sampledTransferOracle(bed,nx,nz,k);
                for(int j=0;j<expected.length;j++)expected[j]=Math.min(expected[j],Math.max(barrier,path[j]));
            }
            for(int k=0;k<bed.length;k++){equal &= actual.get(assembly.vertices().get(k).point()).unknown()==expected[k];comparisons++;}
        }
        chk(equal,"24随机矩形完整外环低界对独立全D8 Bellman oracle comparisons="+comparisons,"different");
    }

    private static void conditionalBoundarySynthetic(SharedBoundaryDrainage.WorldPolicy policy) {
        double[] flat=new double[81];var at=new SharedBoundaryDrainage.WorldCell(0,0);
        var west=new SharedBoundaryDrainage.WorldCell(-16,-16);var east=new SharedBoundaryDrainage.WorldCell(24,-16);
        var a=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-16,-16,9,9,4,flat)));
        var fa=SharedBoundaryTransfer.exterior(policy,a,p->null,16);
        var high=SharedBoundaryTransfer.conditionalRouting(a,fa,Map.of(west,10.0),0);
        var highCapacity=SharedBoundaryTransfer.exteriorCapacities(a,fa,Map.of(west,10.0),0);
        chk(highCapacity.get(at).unknown()==0 && highCapacity.get(at).terminal()==10
                && high.nodes().get(at).state()==SharedBoundaryDrainage.WorldState.INPUT_PENDING && high.nodes().get(at).next()==null,
                "未知外路下界更低时优先输入待决，不能升格浅盆或朝未知框选路",high.nodes().get(at).toString());
        double[] channel=new double[81];java.util.Arrays.fill(channel,100);for(int x=0;x<9;x++)channel[x*9+4]=0;
        west=new SharedBoundaryDrainage.WorldCell(-12,0);east=new SharedBoundaryDrainage.WorldCell(20,0);
        var ca=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-16,-16,9,9,4,channel)));
        var b=SharedBoundaryTransfer.assemble(List.of(SharedBoundaryTransfer.grid(-8,-16,9,9,4,channel)));
        var fb=SharedBoundaryTransfer.exterior(policy,b,p->null,16);
        var ra=SharedBoundaryTransfer.conditionalRouting(ca,SharedBoundaryTransfer.exterior(policy,ca,p->null,16),Map.of(west,0.0),0);
        var rb=SharedBoundaryTransfer.conditionalRouting(b,fb,Map.of(east,0.0),0);
        var source=new SharedBoundaryDrainage.WorldSource(at,at);
        var pa=SharedBoundaryDrainage.projectConditional(ra,List.of(source),4096).routes().get(source);
        var pb=SharedBoundaryDrainage.projectConditional(rb,List.of(source),4096).routes().get(source);
        chk(ra.nodes().get(at).state()==SharedBoundaryDrainage.WorldState.NEXT && rb.nodes().get(at).state()==SharedBoundaryDrainage.WorldState.NEXT
                && !ra.nodes().get(at).next().equals(rb.nodes().get(at).next()) && pa.path().size()>1 && pb.path().size()>1
                && pa.path().get(pa.path().size()-1).equals(west) && pb.path().get(pb.path().size()-1).equals(east),
                "同床面重叠固定域可因不同终端条件而异向：同域后缀同值不证明跨域权威","A="+pa+" B="+pb);
        // #region agent log: G40-A 真实终端与洪泛外框种子的来源丢失对照
        try {Files.writeString(Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log"),
                "G40-A terminals="+west+"/"+east+" A="+pa+" B="+pb+"\n",java.nio.file.StandardOpenOption.APPEND);}
        catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
        // #endregion
    }

    private static void sampledConditionSynthetic() {
        var random=new java.util.Random(3702);boolean same=true;
        for(int trial=0;trial<24;trial++) {
            int nx=7,nz=6,step=4;double[] bed=new double[nx*nz],expected=new double[bed.length];
            for(int k=0;k<bed.length;k++)bed[k]=random.nextInt(17)-8;
            java.util.Arrays.fill(expected,Double.POSITIVE_INFINITY);
            var transfer=SharedBoundaryTransfer.grid(-32,-24,nx,nz,step,bed);
            var boundary=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();
            for(int k:transfer.ports()) {
                double external=random.nextInt(4)==0?Double.POSITIVE_INFINITY:random.nextInt(25)-10;
                boundary.put(transfer.vertices().get(k).point(),external);
                if(Double.isFinite(external)) {double[] costs=sampledTransferOracle(bed,nx,nz,k);
                    for(int j=0;j<costs.length;j++)expected[j]=Math.min(expected[j],Math.max(external,costs[j]));}
            }
            var actual=SharedBoundaryTransfer.condition(transfer,boundary);
            for(int k=0;k<bed.length;k++)same &= expected[k]==actual.get(transfer.vertices().get(k).point());
            var missing=new HashMap<>(boundary);missing.remove(transfer.vertices().get(transfer.ports().get(0)).point());
            boolean rejected=false;try{SharedBoundaryTransfer.condition(transfer,missing);}catch(IllegalArgumentException ex){rejected=true;}
            same &= rejected;
        }
        chk(same,"24随机完整外框条件经容量树重解等于全D8独立oracle；缺端口硬拒绝，不把未知当封闭","different");
        var t=SharedBoundaryTransfer.grid(-4,-4,2,2,4,new double[]{1,2,3,4});
        var closed=new HashMap<SharedBoundaryDrainage.WorldCell,Double>();t.vertices().forEach(v->closed.put(v.point(),Double.POSITIVE_INFINITY));
        var result=SharedBoundaryTransfer.condition(t,closed);boolean cancelled=false,invalid=false,immutable=false;
        try{Thread.currentThread().interrupt();SharedBoundaryTransfer.condition(t,closed);}
        catch(java.util.concurrent.CancellationException ex){cancelled=true;}finally{Thread.interrupted();}
        var bad=new HashMap<>(closed);bad.put(t.vertices().get(0).point(),Double.NaN);
        try{SharedBoundaryTransfer.condition(t,bad);}catch(IllegalArgumentException ex){invalid=true;}
        try{result.clear();}catch(UnsupportedOperationException ex){immutable=true;}
        chk(result.values().stream().allMatch(value -> Double.isInfinite(value)) && cancelled && invalid && immutable
                && result.equals(SharedBoundaryTransfer.condition(t,closed)),"全封闭条件无虚构终端；NaN拒绝、结果只读、取消重试同值","invented/partial");
    }

    private static double[] sampledTransferOracle(double[] bed,int nx,int nz,int start) {
        double[] costs=new double[bed.length];java.util.Arrays.fill(costs,Double.POSITIVE_INFINITY);costs[start]=bed[start];
        for(int pass=0;pass<bed.length;pass++) {boolean changed=false;
            for(int k=0;k<bed.length;k++)for(int next=0;next<bed.length;next++) {
                if(k==next || Math.abs(k/nz-next/nz)>1 || Math.abs(k%nz-next%nz)>1)continue;
                double candidate=Math.max(costs[k],bed[next]);if(candidate<costs[next]){costs[next]=candidate;changed=true;}
            }
            if(!changed)break;
        }
        return costs;
    }

    private static void summaryContractSynthetic() {
        WindowOutlets.Terrain terrain = (x, z) -> z == 0 && x != 0 ? (Math.abs(x) == 4 ? -1 : 1) : 120;
        var a = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, terrain, (x, z) -> Math.abs(x) == 4 && z == 0, false);
        var summary = SharedBoundaryTransfer.build(a, (x, z) -> Math.abs(x) == 4 && z == 0);
        var left = new SharedBoundaryDrainage.WorldCell(-4, 0); var right = new SharedBoundaryDrainage.WorldCell(4, 0);
        chk(summaryPairBarrier(summary, left, right) == 120,
                "隔离海湾各自到海不等于海湾之间存在低瓶颈，不合并海超节点", "false bridge");
        var result = SharedBoundaryTransfer.solve(List.of(summary));
        chk(result.get(left).sea() == 0 && result.get(right).sea() == 0
                        && result.get(left).unknown() == 1 && result.get(right).unknown() == 1,
                "仅给查询端口仍自动保留全部外框和真实未知下界", result.toString());
        int frame = 0;
        for (var v : summary.vertices()) if (domainFrame(a, v.point().x(), v.point().z())) frame++;
        chk(frame == 4 * (a.sizeX() - 1), "完整边框整点不能被调用者漏选", "frame=" + frame);
        var shifted = SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> terrain.height(x + 32, z + 16), (x, z) -> Math.abs(x + 32) == 4 && z == -16, true);
        var translated = SharedBoundaryTransfer.build(shifted, (x, z) -> Math.abs(x + 32) == 4 && z == -16);
        var values = SharedBoundaryTransfer.solve(List.of(translated));
        boolean equal = result.size() == values.size();
        for (var e : result.entrySet()) equal &= e.getValue().equals(values.get(
                new SharedBoundaryDrainage.WorldCell(e.getKey().x() - 32, e.getKey().z() - 16)));
        chk(equal, "负坐标平移边界摘要容量同值", "different");
        var b = SharedBlockDrainage.buildAt(16, 0, 8, 4, 0, (x, z) -> terrain.height(x, z) + 1, (x, z) -> false, false);
        boolean conflict = false;
        try { SharedBoundaryTransfer.solve(List.of(summary, SharedBoundaryTransfer.build(b, (x, z) -> false))); }
        catch (IllegalArgumentException e) { conflict = true; }
        chk(conflict, "完整相接边床高冲突拒绝", "missed");
        var overlap = SharedBlockDrainage.buildAt(8, 0, 8, 4, 0, terrain, (x, z) -> false, false);
        boolean refused = false;
        try { SharedBoundaryTransfer.solve(List.of(summary, SharedBoundaryTransfer.build(overlap, (x, z) -> false))); }
        catch (IllegalArgumentException e) { refused = true; }
        chk(refused, "宽重叠域不能仅以边界摘要拼接", "missed");
        var vertical = SharedBlockDrainage.buildAt(0, 16, 8, 4, 0, terrain, (x, z) -> false, true);
        var union = SharedBlockDrainage.combine(List.of(a, vertical));
        var combined = SharedBoundaryTransfer.solve(List.of(summary, SharedBoundaryTransfer.build(vertical, (x, z) -> false)));
        boolean same = true;
        for (var e : combined.entrySet()) same &= e.getValue().equals(union.capacityAt(e.getKey().x(), e.getKey().z()));
        chk(same, "竖向相接摘要与同覆盖完整方块图等价", "different");
        boolean cancel = false;
        try { Thread.currentThread().interrupt(); SharedBoundaryTransfer.solve(List.of(summary)); }
        catch (java.util.concurrent.CancellationException e) { cancel = true; }
        finally { Thread.interrupted(); }
        chk(cancel && result.equals(SharedBoundaryTransfer.solve(List.of(summary))), "中断边界求解不发布部分值，重试同值", "missed");
        summaryPolicySynthetic(summary, terrain, a, vertical);
    }

    private static void summaryPolicySynthetic(SharedBoundaryTransfer.Product summary, WindowOutlets.Terrain terrain,
            SharedBlockDrainage.Domain a, SharedBlockDrainage.Domain vertical) {
        var wrongSea = SharedBlockDrainage.buildAt(16, 0, 8, 4, 5, terrain, (x, z) -> false, false);
        boolean policy = false;
        try { SharedBoundaryTransfer.solve(List.of(summary, SharedBoundaryTransfer.build(wrongSea, (x, z) -> false))); }
        catch (IllegalArgumentException e) { policy = true; }
        chk(policy, "海面策略不同不能组合", "missed");
        boolean budget = false;
        var two = SharedBoundaryTransfer.build(SharedBlockDrainage.combine(List.of(a, vertical)), (x, z) -> false);
        try { SharedBoundaryTransfer.solve(List.of(summary, two)); }
        catch (IllegalArgumentException e) { budget = true; }
        chk(budget, "已组合摘要计入原始域数，不能绕过两域上限", "missed");
        var disjoint = SharedBlockDrainage.buildAt(32, 0, 8, 4, 0, terrain, (x, z) -> false, false);
        boolean gap = false;
        try { SharedBoundaryTransfer.solve(List.of(summary, SharedBoundaryTransfer.build(disjoint, (x, z) -> false))); }
        catch (IllegalArgumentException e) { gap = true; }
        chk(gap, "未接触patch不得虚构连接", "missed");
    }

    private static double summaryPairBarrier(SharedBoundaryTransfer.Product p,
            SharedBoundaryDrainage.WorldCell from, SharedBoundaryDrainage.WorldCell to) {
        int start = -1, target = -1;
        for (int i = 0; i < p.vertices().size(); i++) {
            if (p.vertices().get(i).point().equals(from)) start = i;
            if (p.vertices().get(i).point().equals(to)) target = i;
        }
        if (start < 0 || target < 0) throw new IllegalArgumentException("missing query port");
        var levels = new TreeSet<Double>(); levels.add(p.vertices().get(start).bed());
        for (var e : p.edges()) levels.add(e.barrier());
        for (double level : levels) {
            if (p.vertices().get(start).bed() > level) continue;
            var queue = new java.util.ArrayDeque<Integer>(); var seen = new java.util.HashSet<Integer>();
            queue.add(start); seen.add(start);
            while (!queue.isEmpty()) {
                int k = queue.remove(); if (k == target) return level;
                for (var e : p.edges()) if (e.barrier() <= level && (e.a() == k || e.b() == k)) {
                    int next = e.a() == k ? e.b() : e.a(); if (seen.add(next)) queue.add(next);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void secSummaryView() throws Exception {
        log("\n=== [summary-view] 固定相接patch边界传递，非生产启用 ===");
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var a = SharedBlockDrainage.buildAt(-768, 512, 256, 4, TP.seaLevel(), terrain, ocean, false);
        var b = SharedBlockDrainage.buildAt(-256, 512, 256, 4, TP.seaLevel(), terrain, ocean, true);
        var whole = SharedBlockDrainage.combine(List.of(a, b));
        java.util.function.BiPredicate<Integer, Integer> query = (x, z) -> z == 504 && (x == -712 || x == -648 || x == -580);
        var sa = SharedBoundaryTransfer.build(a, (x, z) -> domainFrame(a, x, z) || query.test(x, z));
        var sb = SharedBoundaryTransfer.build(b, (x, z) -> domainFrame(b, x, z));
        var compressed = SharedBoundaryTransfer.solve(List.of(sa, sb));
        int mismatch = 0;
        for (var e : compressed.entrySet()) if (!e.getValue().equals(whole.capacityAt(e.getKey().x(), e.getKey().z()))) mismatch++;
        log("SUMMARY_REAL sourceNodes=" + a.nodes() + "/" + b.nodes() + " summaryVertices=" + sa.vertices().size() + "/" + sb.vertices().size()
                + " summaryEdges=" + sa.edges().size() + "/" + sb.edges().size() + " checked=" + compressed.size() + " mismatch=" + mismatch);
        chk(mismatch == 0 && compressed.size() > 0, "真实全部保留接口压缩前后瓶颈一致", "mismatch=" + mismatch);
        chk(compressed.equals(SharedBoundaryTransfer.solve(List.of(sb, sa))), "真实接口逆序同值", "different");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "边界摘要零邻fine依赖", "increased");
        var paths = new ArrayList<SharedBlockDrainage.Trace>();
        for (int x : new int[]{-712, -648, -580}) {
            var witness = whole.seaWitness(x, 504); paths.add(witness);
            log("SUMMARY_SCENE x=" + x + " capacity=" + compressed.get(new SharedBoundaryDrainage.WorldCell(x, 504))
                    + " witness=" + witness.end() + " nodes=" + witness.path().size());
            chk(!witness.path().isEmpty() && continuous(witness.path()), "现场存在完整海候选方块通路 x=" + x, witness.end().toString());
        }
        renderSummary(paths);
        chk(paths.stream().allMatch(t -> t.end() == SharedBlockDrainage.End.CERTIFIED_SEA),
                "摘要容量不冒充已认证下游（相等未知保留FAIL）", paths.stream().map(SharedBlockDrainage.Trace::end).toList().toString());
        Files.writeString(OUT.resolve("summary.txt"), LOG.toString());
    }

    private static void renderSummary(List<SharedBlockDrainage.Trace> paths) throws Exception {
        int cx = -650, cz = 512, radius = 96;
        Path dir = OUT.resolve("summary-view-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "摘要现场生产渲染无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics(); g.setColor(java.awt.Color.YELLOW);
        for (var trace : paths) for (var c : trace.path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
        g.dispose(); writeLocalImage(dir.resolve("sea-candidate.png"), raw, cx, cz, radius, "YELLOW = KNOWN SEA CAPACITY WITNESS / NOT WATER");
        Files.writeString(dir.resolve("manifest.txt"), "provider production unchanged; center(-650,512)radius96; 1pixel/block x3\n"
                + "two fixed adjacent half256 patches; yellow=known-ocean capacity witness, not canonical routing or new generated water\n" + LOG);
        log("SUMMARY_VIEW_DIR=" + dir.toAbsolutePath());
    }

    private static void transferSynthetic() throws Exception {
        log("\n=== [transfer-synth] 两固定域容量传递，不互接独立parent ===");
        WindowOutlets.Terrain channel = (x, z) -> z == 0 && x >= -4 && x <= 12 ? (x == 12 ? -1 : 1) : 10;
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> x == 12 && z == 0;
        var a = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, channel, ocean, false);
        var b = SharedBlockDrainage.buildAt(8, 0, 8, 4, 0, channel, ocean, true);
        var union = SharedBlockDrainage.combine(List.of(a, b));
        chk(a.trace(0, 0).end() == SharedBlockDrainage.End.UNKNOWN_FRAME
                        && union.trace(0, 0).end() == SharedBlockDrainage.End.CERTIFIED_SEA
                        && union.trace(0, 0).barrier() == 1 && union.trace(0, 0).unknownBound() == 10,
                "覆盖内框后继续到真海，未覆盖外框仍是10瓶颈", union.trace(0, 0).toString());
        chk(union.sameTopology(SharedBlockDrainage.combine(List.of(b, a))), "两域组合顺序不改任何拓扑数组", "different");
        chk(union.sameTopology(SharedBlockDrainage.combine(List.of(union))), "组合单产物不改已有权威", "different");
        boolean budget = false;
        try { SharedBlockDrainage.combine(List.of(union, a)); }
        catch (IllegalArgumentException e) { budget = true; }
        chk(budget, "递归第三域被拒绝，不能无限扩域凑海", "missed");
        var wrong = SharedBlockDrainage.buildAt(8, 0, 8, 4, 0, (x, z) -> channel.height(x, z) + 1, ocean, false);
        boolean conflict = false;
        try { SharedBlockDrainage.combine(List.of(a, wrong)); }
        catch (IllegalArgumentException e) { conflict = true; }
        chk(conflict, "重叠世界节点床高不一致必须拒绝", "missed");
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.combine(List.of(a, b)); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && union.sameTopology(SharedBlockDrainage.combine(List.of(a, b))), "取消组合不污染输入/重试", "missed");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.Domain>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> SharedBlockDrainage.combine(List.of(b, a))));
            boolean same = true;
            for (var f : futures) same &= union.sameTopology(f.get(30, TimeUnit.SECONDS));
            chk(same, "两域四线程组合全数组逐位同值", "different");
        } finally { pool.shutdownNow(); }
        transferOracle();
    }

    private static void transferOracle() {
        for (int step : new int[]{2, 4, 8}) for (boolean diagonal : new boolean[]{false, true}) {
            int lo = -8 - step / 2, hi = 8 - step / 2, end = hi + 8;
            WindowOutlets.Terrain terrain = (x, z) -> Math.floorMod((int) x * 7 + (int) z * 13 + (int) x * (int) z, 17) - 4;
            java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> x == 8;
            var a = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, ocean, false);
            var b = SharedBlockDrainage.buildAt(8, diagonal ? 8 : 0, 8, step, 0, terrain, ocean, true);
            var union = SharedBlockDrainage.combine(List.of(a, b));
            int zh = diagonal ? end : hi;
            java.util.function.BiPredicate<Integer, Integer> covered = (x, z) ->
                    (x >= lo && x <= hi && z >= lo && z <= hi)
                            || (x >= lo + 8 && x <= end && z >= lo + (diagonal ? 8 : 0) && z <= zh);
            java.util.function.BiPredicate<Integer, Integer> exposed = (x, z) -> !covered.test(x - 1, z)
                    || !covered.test(x + 1, z) || !covered.test(x, z - 1) || !covered.test(x, z + 1);
            int samples = 0, mismatch = 0;
            for (int x = lo; x <= end; x++) for (int z = lo; z <= zh; z++) {
                if (!covered.test(x, z)) continue;
                var point = new SharedBoundaryDrainage.WorldCell(x, z);
                var capacity = union.capacityAt(x, z); var trace = union.trace(x, z);
                double sea = maskedTerminalOracle(point, terrain, lo, end, lo, zh, covered,
                        (bx, bz) -> terrain.height(bx, bz) < 0 && ocean.test(bx, bz), 0);
                double unknown = maskedTerminalOracle(point, terrain, lo, end, lo, zh, covered, exposed, Double.NEGATIVE_INFINITY);
                if (capacity.sea() != sea || capacity.unknown() != unknown || !continuous(trace.path())
                        || trace.path().stream().anyMatch(c -> !covered.test(c.x(), c.z()))) mismatch++;
                samples++;
            }
            chk(mismatch == 0 && union.sameTopology(SharedBlockDrainage.combine(List.of(b, a))),
                    "组合全覆盖节点独立BFS step=" + step + " diagonal=" + diagonal + " samples=" + samples,
                    "mismatch=" + mismatch);
            if (diagonal) {
                boolean gap = false;
                try { union.trace(lo, zh); } catch (IllegalArgumentException e) { gap = true; }
                chk(gap, "斜交组合缺角不能被bbox补成地形/海", "missed");
            }
        }
    }

    private static double maskedTerminalOracle(SharedBoundaryDrainage.WorldCell from, WindowOutlets.Terrain terrain,
            int xl, int xh, int zl, int zh, java.util.function.BiPredicate<Integer, Integer> covered,
            java.util.function.BiPredicate<Integer, Integer> terminal, double floor) {
        var levels = new TreeSet<Double>();
        for (int x = xl; x <= xh; x++) for (int z = zl; z <= zh; z++)
            if (covered.test(x, z)) levels.add(Math.max(floor, terrain.height(x, z)));
        for (double level : levels) {
            if (terrain.height(from.x(), from.z()) > level) continue;
            var queue = new java.util.ArrayDeque<SharedBoundaryDrainage.WorldCell>();
            var seen = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>(); queue.add(from); seen.add(from);
            while (!queue.isEmpty()) {
                var c = queue.remove();
                if (terminal.test(c.x(), c.z())) return level;
                for (int[] d : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                    var next = new SharedBoundaryDrainage.WorldCell(c.x() + d[0], c.z() + d[1]);
                    if (covered.test(next.x(), next.z()) && terrain.height(next.x(), next.z()) <= level && seen.add(next)) queue.add(next);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void secTransferView() throws Exception {
        log("\n=== [transfer-view] 固定H512沿缝-2/-1双域对照，非生产修复 ===");
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var a = SharedBlockDrainage.build(new SharedBoundaryDrainage.Key(1, 512, -2), hp().tract(), hp().fineRadius(), hp().step(), TP.seaLevel(), terrain, ocean);
        var b = SharedBlockDrainage.build(new SharedBoundaryDrainage.Key(1, 512, -1), hp().tract(), hp().fineRadius(), hp().step(), TP.seaLevel(), terrain, ocean);
        var union = SharedBlockDrainage.combine(List.of(a, b));
        int mismatch = 0, parentMismatch = 0, samples = 0;
        for (int x = -608; x <= -420; x += 4) for (int z = 160; z <= 860; z += 4) {
            if (!a.capacityAt(x, z).equals(b.capacityAt(x, z))) mismatch++;
            if (!java.util.Objects.equals(a.nextAt(x, z), b.nextAt(x, z))) parentMismatch++;
            samples++;
        }
        log("TRANSFER_OVERLAP samples=" + samples + " capacityMismatch=" + mismatch + " parentMismatch=" + parentMismatch
                + " combinedNodes=" + union.nodes() + " edges=" + union.treeEdges());
        chk(union.sameTopology(SharedBlockDrainage.combine(List.of(b, a))), "真实双域逆序组合全数组同值", "different");
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "真实双域零邻fine依赖", "increased");
        var old = new ArrayList<SharedBlockDrainage.Trace>(); var next = new ArrayList<SharedBlockDrainage.Trace>();
        for (int x : new int[]{-712, -648, -580}) {
            var before = a.trace(x, 504); var after = union.trace(x, 504);
            var witness = union.seaWitness(x, 504);
            log("TRANSFER_SEA_WITNESS x=" + x + " end=" + witness.end() + " barrier=" + witness.barrier()
                    + " unknownBound=" + witness.unknownBound() + " nodes=" + witness.path().size()
                    + " terminal=" + (witness.path().isEmpty() ? "none" : witness.path().get(witness.path().size() - 1)));
            chk(witness.path().isEmpty() || continuous(witness.path()), "已知海候选四邻连续但不替代严格认证", witness.end().toString());
            old.add(before); next.add(after);
            log("TRANSFER_TRACE x=" + x + " local=" + before.end() + "/" + before.barrier() + "/" + before.path().size()
                    + " combined=" + after.end() + "/" + after.barrier() + "/" + after.path().size()
                    + " terminal=" + after.path().get(after.path().size() - 1));
            chk(continuous(after.path()) && union.capacityAt(x, 504).sea() <= a.capacityAt(x, 504).sea()
                            && union.capacityAt(x, 504).unknown() >= a.capacityAt(x, 504).unknown(),
                    "组合通路连续，海上界不升且未知下界不降 x=" + x, after.toString());
        }
        auditCoarseContinuation(next.get(0).path().get(next.get(0).path().size() - 1));
        renderTransfer(old, next);
        chk(next.stream().allMatch(t -> t.end() == SharedBlockDrainage.End.CERTIFIED_SEA),
                "三断点组合终端已证（未知保留FAIL）", next.stream().map(SharedBlockDrainage.Trace::end).toList().toString());
        chk(mismatch == 0 && parentMismatch == 0, "独立重叠域下游权威一致（不以组合顺序PASS替代）", "capacity=" + mismatch + " parent=" + parentMismatch);
        Files.writeString(OUT.resolve("transfer.txt"), LOG.toString());
    }

    private static void auditCoarseContinuation(SharedBoundaryDrainage.WorldCell start) {
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        for (int[] r : new int[][]{{-2, 1}, {-1, 1}}) {
            var coarse = PROV.coarseLayerFor(r[0], r[1]); var p = coarse.pipe();
            int k = coarse.cellAt(start.x(), start.z()), steps = 0;
            var seen = new java.util.HashSet<Integer>();
            double previous = Double.POSITIVE_INFINITY; int rises = 0;
            String end = "NO_PARENT";
            var route = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            while (k >= 0 && seen.add(k)) {
                var c = new SharedBoundaryDrainage.WorldCell(coarse.originX() + k / p.nz() * coarse.step(),
                        coarse.originZ() + k % p.nz() * coarse.step());
                route.add(c); steps++;
                if (p.level()[k] > previous) rises++; previous = p.level()[k];
                if (p.sea()[k]) { end = "GRID_SEA_CANDIDATE"; break; }
                if (k / p.nz() == 0 || k % p.nz() == 0 || k / p.nz() == p.nx() - 1 || k % p.nz() == p.nz() - 1) {
                    end = "UNKNOWN_FRAME"; break;
                }
                if (!p.resolved()[k]) { end = "UNRESOLVED"; break; }
                if (p.lake()[k]) { end = "LAKE_PENDING"; break; }
                k = p.parent()[k];
            }
            if (k >= 0 && route.size() > 0 && !end.equals("GRID_SEA_CANDIDATE") && !end.equals("UNKNOWN_FRAME")
                    && !end.equals("UNRESOLVED") && !end.equals("LAKE_PENDING")) end = "CYCLE";
            log("COARSE_CONTINUATION owner=" + r[0] + "," + r[1] + " start=" + start + " end=" + end
                    + " nodes=" + steps + " escapeRises=" + rises + " route=" + route);
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "粗层接续审计零邻fine依赖", "increased");
    }

    private static void renderTransfer(List<SharedBlockDrainage.Trace> before, List<SharedBlockDrainage.Trace> after) throws Exception {
        int cx = -650, cz = 512, radius = 96;
        Path dir = OUT.resolve("transfer-view-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "双域对照生产列渲染无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics(); g.setColor(java.awt.Color.ORANGE);
        for (var trace : before) for (var c : trace.path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
        g.dispose(); writeLocalImage(dir.resolve("single-domain.png"), raw, cx, cz, radius, "ORANGE = SINGLE DOMAIN CAPACITY / NOT WATER");
        raw = HierarchicalComparisonView.render(mask, GEN); g = raw.createGraphics(); g.setColor(java.awt.Color.YELLOW);
        for (var trace : after) for (var c : trace.path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
        g.dispose(); writeLocalImage(dir.resolve("two-domains.png"), raw, cx, cz, radius, "YELLOW = TWO DOMAIN CAPACITY / NOT WATER");
        Files.writeString(dir.resolve("manifest.txt"), "provider production unchanged; center(-650,512)radius96 1pixel/block displayedx3\n"
                + "orange/yellow=capacity only; union limited to two fixed domains, not global hierarchy; no new generated water\n" + LOG);
        log("TRANSFER_VIEW_DIR=" + dir.toAbsolutePath());
    }

    private static void blockSynthetic() throws Exception {
        log("\n=== [block-synth] 紧凑树组合固定域、海证书/未知竞争、无环父链 ===");
        WindowOutlets.Terrain pit = (x, z) -> z == 0 && x >= -4 && x <= 0 ? (x == -4 ? -1 : x == -2 ? 3 : 1) : 10;
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> x == -4 && z == 0;
        for (int step : new int[]{2, 4, 8}) {
            var a = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, pit, ocean, false);
            var b = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, pit, ocean, true);
            var t = a.trace(0, 0);
            chk(t.end() == SharedBlockDrainage.End.CERTIFIED_SEA && t.barrier() == 3
                            && t.unknownBound() == 10 && continuous(t.path()),
                    "step=" + step + " 已证海通路3严格优于未知边界10", t.toString());
            chk(a.sameTopology(b), "格元逆序组合全域父链/瓶颈逐位一致 step=" + step, "different");
            var noSea = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, pit, (x, z) -> false, false);
            chk(noSea.trace(0, 0).end() == SharedBlockDrainage.End.UNKNOWN_FRAME && noSea.seaWitness(0, 0).path().isEmpty(),
                    "低于海面的孤立点无海证书仍未知且无海路径 step=" + step, noSea.trace(0, 0).toString());
            chk(a.seaWitness(0, 0).equals(t), "严格已证海证书与海通路相同 step=" + step, "different");
            var equal = SharedBlockDrainage.buildAt(0, 0, 8, step, 0,
                    (x, z) -> x == -4 && z == 0 ? -1 : 1, ocean, false);
            chk(equal.trace(0, 0).end() == SharedBlockDrainage.End.UNKNOWN_FRAME
                            && equal.seaWitness(0, 0).end() == SharedBlockDrainage.End.SEA_CANDIDATE
                            && continuous(equal.seaWitness(0, 0).path()),
                    "同瓶颈可以证明海通路存在但不能认证唯一归属 step=" + step, equal.seaWitness(0, 0).toString());
            var flat = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, (x, z) -> -1, ocean, false);
            chk(flat.trace(0, 0).end() == SharedBlockDrainage.End.UNKNOWN_FRAME,
                    "更低未知边界可能改变终端，不能用已知海掩盖 step=" + step, flat.trace(0, 0).toString());
        }
        var reference = SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, pit, ocean, false);
        var shifted = SharedBlockDrainage.buildAt(-32, -16, 8, 4, 0,
                (x, z) -> pit.height(x + 32, z + 16), (x, z) -> ocean.test(x + 32, z + 16), true);
        var expected = reference.trace(0, 0); var translated = shifted.trace(-32, -16);
        chk(expected.barrier() == translated.barrier() && expected.end() == translated.end()
                        && expected.path().stream().map(c -> new SharedBoundaryDrainage.WorldCell(c.x() - 32, c.z() - 16)).toList()
                        .equals(translated.path()), "负坐标平移全路径同值", translated.toString());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBlockDrainage.Domain>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() ->
                    SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, pit, ocean, false)));
            boolean same = true;
            for (var f : futures) same &= reference.sameTopology(f.get(30, TimeUnit.SECONDS));
            chk(same, "固定域四线程独立冷产物逐位相同", "different");
        } finally { pool.shutdownNow(); }
        boolean cancelled = false;
        try { Thread.currentThread().interrupt(); SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, pit, ocean, false); }
        catch (java.util.concurrent.CancellationException e) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && reference.sameTopology(SharedBlockDrainage.buildAt(0, 0, 8, 4, 0, pit, ocean, false)),
                "中断不发布部分域，重试保持一致", "missed/different");
        blockDomainOracle();
    }

    private static void blockDomainOracle() {
        for (int step : new int[]{2, 4, 8}) for (int seed = 0; seed < 4; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> Math.floorMod((int) x * 17 + (int) z * 31
                    + (int) x * (int) z * 7 + s * 13, 19) - 5;
            java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> x == -2 || z == 3;
            var domain = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, ocean, false);
            var reverse = SharedBlockDrainage.buildAt(0, 0, 8, step, 0, terrain, ocean, true);
            int lo = -8 - step / 2, hi = 8 - step / 2, mismatches = 0, samples = 0;
            for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
                var start = new SharedBoundaryDrainage.WorldCell(x, z);
                double sea = thresholdTerminalOracle(start, terrain, lo, hi,
                        (bx, bz) -> terrain.height(bx, bz) < 0 && ocean.test(bx, bz), 0);
                double unknown = thresholdTerminalOracle(start, terrain, lo, hi,
                        (bx, bz) -> bx == lo || bz == lo || bx == hi || bz == hi, Double.NEGATIVE_INFINITY);
                var capacity = domain.capacityAt(x, z); var trace = domain.trace(x, z);
                if (capacity.sea() != sea || capacity.unknown() != unknown || !continuous(trace.path())
                        || trace.path().stream().distinct().count() != trace.path().size()) mismatches++;
                samples++;
            }
            chk(mismatches == 0 && domain.sameTopology(reverse),
                    "固定域全部节点两终端容量等价独立阈值BFS step=" + step + " seed=" + seed + " samples=" + samples,
                    "mismatches=" + mismatches);
        }
    }

    private static double thresholdTerminalOracle(SharedBoundaryDrainage.WorldCell from, WindowOutlets.Terrain terrain,
            int lo, int hi, java.util.function.BiPredicate<Integer, Integer> terminal, double floor) {
        var levels = new TreeSet<Double>();
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) levels.add(Math.max(floor, terrain.height(x, z)));
        for (double level : levels) {
            if (terrain.height(from.x(), from.z()) > level) continue;
            var seen = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
            var queue = new java.util.ArrayDeque<SharedBoundaryDrainage.WorldCell>(); queue.add(from); seen.add(from);
            while (!queue.isEmpty()) {
                var point = queue.remove();
                if (terminal.test(point.x(), point.z())) return level;
                for (int[] d : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                    var next = new SharedBoundaryDrainage.WorldCell(point.x() + d[0], point.z() + d[1]);
                    if (next.x() >= lo && next.z() >= lo && next.x() <= hi && next.z() <= hi
                            && terrain.height(next.x(), next.z()) <= level && seen.add(next)) queue.add(next);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void secBlockBoundary(boolean view) throws Exception {
        log("\n=== [block-boundary] 全真实发布过界对照；未知不是排海，未接生产 ===");
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        java.util.function.BiPredicate<Integer, Integer> ocean = (x, z) -> GEN.sample(x / hs, z / hs).terrainType.isOcean();
        Map<SharedBoundaryDrainage.Key, List<ProductionDrainageAudit.Crossing>> grouped = new java.util.LinkedHashMap<>();
        for (int[] r : new int[][]{{-2, 0}, {-1, 0}, {-1, -1}, {0, -1}}) {
            var snapshot = PROV.drainageForProbe(r[0], r[1]);
            for (var event : snapshot.crossings()) {
                if (!event.upstream().equals(snapshot.region())) continue;
                double along = event.axis() == 0 ? event.point().z() : event.point().x();
                var key = new SharedBoundaryDrainage.Key(event.axis(), event.boundary(), (int) Math.floor(along / hp().tract()));
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
            }
        }
        int events = 0, covered = 0, certified = 0;
        var scenes = new ArrayList<SharedBlockDrainage.Trace>();
        for (var group : grouped.entrySet()) {
            long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get(), start = System.nanoTime();
            var domain = SharedBlockDrainage.build(group.getKey(), hp().tract(), hp().fineRadius(), hp().step(), TP.seaLevel(), terrain, ocean);
            log("BLOCK_DOMAIN key=" + group.getKey() + " nodes=" + domain.nodes() + " edges=" + domain.treeEdges()
                    + " ms=" + (System.nanoTime() - start) / 1_000_000 + " fingerprint=" + domain.fingerprint());
            chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "紧凑域零邻fine依赖 " + group.getKey(), "increased");
            for (var event : group.getValue()) {
                int x = (int) Math.round(event.point().x() - 2 * hp().step() * event.tangent().x());
                int z = (int) Math.round(event.point().z() - 2 * hp().step() * event.tangent().z());
                var trace = domain.trace(x, z);
                var crossings = blockPathCrossings(event.segment(), trace.path());
                boolean matched = crossings.stream().anyMatch(c -> c.axis() == event.axis() && c.boundary() == event.boundary()
                        && c.upstream().equals(event.upstream()) && c.downstream().equals(event.downstream()));
                events++; if (matched) covered++; if (trace.end() == SharedBlockDrainage.End.CERTIFIED_SEA) certified++;
                log("BLOCK_EVENT=" + event + " matched=" + matched + " end=" + trace.end() + " barrier=" + trace.barrier()
                        + " unknownBound=" + trace.unknownBound() + " nodes=" + trace.path().size() + " crossings=" + crossings);
                chk(continuous(trace.path()), "紧凑域真实方块路径四邻连续", "discontinuous");
            }
            if (group.getKey().equals(new SharedBoundaryDrainage.Key(1, 512, -2))) {
                for (int x : new int[]{-712, -648, -580}) scenes.add(domain.trace(x, 504));
            }
        }
        log("BLOCK_COVERAGE events=" + events + " matched=" + covered + " certifiedSea=" + certified + " domains=" + grouped.size());
        chk(events > 0 && covered == events, "紧凑域覆盖全部旧实际过界（未放宽门槛）", "events=" + events + " matched=" + covered);
        chk(events > 0 && certified == events, "全部过界追至有证终端（未知不是PASS）", "certified=" + certified + "/" + events);
        if (view) renderBlockTraces(scenes);
        Files.writeString(OUT.resolve("block-boundary.txt"), LOG.toString());
    }

    private static List<ProductionDrainageAudit.Crossing> blockPathCrossings(ProductionDrainageAudit.SegmentId id,
            List<SharedBoundaryDrainage.WorldCell> path) {
        var result = new ArrayList<ProductionDrainageAudit.Crossing>();
        for (int i = 1; i < path.size(); i++) {
            var a = path.get(i - 1); var b = path.get(i);
            for (var c : ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(a.x(), a.z()),
                    new ProductionDrainageAudit.Point(b.x(), b.z()), hp().tract())) {
                int next = i;
                while (next < path.size() && (c.axis() == 0 ? path.get(next).x() : path.get(next).z()) == c.boundary()) next++;
                if (next == path.size()) continue;
                int after = c.axis() == 0 ? path.get(next).x() : path.get(next).z();
                double direction = c.axis() == 0 ? c.tangent().x() : c.tangent().z();
                if ((after - c.boundary()) * direction > 0) result.add(c);
            }
        }
        return List.copyOf(result);
    }

    private static void renderBlockTraces(List<SharedBlockDrainage.Trace> traces) throws Exception {
        int cx = -650, cz = 512, radius = 96;
        Path dir = OUT.resolve("block-view-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0 && traces.size() == 3, "三断点局部生产图非空且无求解失败", "fail=" + mask.failCount + " traces=" + traces.size());
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics();
        for (var trace : traces) {
            g.setColor(trace.end() == SharedBlockDrainage.End.CERTIFIED_SEA ? java.awt.Color.GREEN : java.awt.Color.ORANGE);
            for (var c : trace.path()) g.fillRect(c.x() - cx + radius, c.z() - cz + radius, 1, 1);
            log("THREE_SEAM_TRACE end=" + trace.end() + " barrier=" + trace.barrier() + " nodes=" + trace.path().size()
                    + " first=" + trace.path().get(0) + " last=" + trace.path().get(trace.path().size() - 1));
        }
        g.dispose();
        writeLocalImage(dir.resolve("capacity.png"), raw, cx, cz, radius, "ORANGE = UNKNOWN TERMINAL / CAPACITY ONLY");
        Files.writeString(dir.resolve("manifest.txt"), "center(-650,512) radius96 1 data pixel/block x3; production columns unchanged\n"
                + "orange=capacity route to unknown domain frame; NOT new generated water, NOT river geometry acceptance\n" + LOG);
        log("BLOCK_VIEW_DIR=" + dir.toAbsolutePath());
    }

    private static void compactCellOracle(int step) {
        int pairs = 0, mismatches = 0;
        for (int seed = 0; seed < 3; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> 80 + Math.floorMod((int) x * 13 + (int) z * 7
                    + (int) x * (int) z * 11 + s * 5, 17);
            var cell = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), step, terrain);
            for (int i = 0; i < cell.ports().size(); i++) for (int j = i; j < cell.ports().size(); j++) {
                var from = cell.ports().get(i); var to = cell.ports().get(j);
                var result = SharedBoundaryDrainage.composeCells(List.of(cell), from, to, 90);
                double expected = blockBarrierOracle(from, to, terrain, -step / 2, step / 2);
                pairs++;
                if (result.barrier() != expected || !continuous(result.path())) mismatches++;
            }
        }
        chk(mismatches == 0 && pairs > 0, "紧凑容量树全端口两两瓶颈等价 step=" + step + " pairs=" + pairs,
                "mismatches=" + mismatches);
    }

    private static void cellContractConcurrency() {
        WindowOutlets.Terrain terrain = (x, z) -> 100 + 0.2 * x + 0.03 * z * z;
        var center = new SharedBoundaryDrainage.WorldCell(-8, -4);
        var expected = SharedBoundaryDrainage.cellConnectivity(center, 4, terrain);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBoundaryDrainage.CellConnectivity>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> SharedBoundaryDrainage.cellConnectivity(center, 4, terrain)));
            boolean same = true;
            for (var future : futures) same &= expected.equals(future.get(30, TimeUnit.SECONDS));
            chk(same, "四线程冷格元边缘产物逐位相同", "different");
        } catch (Exception e) { chk(false, "四线程格元接口", e.toString()); }
        finally { pool.shutdownNow(); }
        boolean cancelled = false;
        try {
            Thread.currentThread().interrupt();
            SharedBoundaryDrainage.cellConnectivity(center, 4, terrain);
        } catch (java.util.concurrent.CancellationException e) { cancelled = true; }
        finally { Thread.interrupted(); }
        chk(cancelled && expected.equals(SharedBoundaryDrainage.cellConnectivity(center, 4, terrain)),
                "取消不发布部分格元，重试与冷产物一致", "cancel missed/different");
        var shifted = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), 4,
                (x, z) -> terrain.height(x - 8, z - 4));
        boolean translated = expected.routes().size() == shifted.routes().size();
        for (int i = 0; i < expected.routes().size(); i++) {
            var a = expected.routes().get(i); var b = shifted.routes().get(i);
            translated &= a.barrier() == b.barrier() && a.path().size() == b.path().size();
            for (int j = 0; j < a.path().size() && j < b.path().size(); j++)
                translated &= a.path().get(j).x() + 8 == b.path().get(j).x()
                        && a.path().get(j).z() + 4 == b.path().get(j).z();
        }
        chk(translated, "完整边缘通路负坐标平移同值（不靠坐标定流向）", "different");
    }

    private static void cellCompositionOracle() {
        int cases = 0;
        for (int seed = 0; seed < 8; seed++) {
            final int s = seed;
            WindowOutlets.Terrain terrain = (x, z) -> 90 + Math.floorMod((int) x * 17 + (int) z * 31
                    + (int) x * (int) z * 7 + s * 13, 23);
            var a = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 0), 4, terrain);
            var b = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(4, 0), 4, terrain);
            var c = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(0, 4), 4, terrain);
            var d = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(4, 4), 4, terrain);
            for (int t = 0; t < 4; t++) {
                var from = a.corners().get(t); var to = d.corners().get(3 - t);
                double expected = blockBarrierOracle(from, to, terrain, -2, 6);
                var result = SharedBoundaryDrainage.composeCells(List.of(a, b, c, d), from, to, 100);
                chk(result.barrier() == expected && continuous(result.path()),
                        "格元组合与同域直接方块minimax一致 seed=" + seed + " case=" + t,
                        "expected=" + expected + " actual=" + result.barrier());
                cases++;
            }
        }
        log("composition-oracle cases=" + cases);
    }

    private static double blockBarrierOracle(SharedBoundaryDrainage.WorldCell from,
            SharedBoundaryDrainage.WorldCell to, WindowOutlets.Terrain terrain, int lo, int hi) {
        var levels = new TreeSet<Double>();
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) levels.add(terrain.height(x, z));
        for (double level : levels) {
            if (terrain.height(from.x(), from.z()) > level) continue;
            var seen = new java.util.HashSet<SharedBoundaryDrainage.WorldCell>();
            var queue = new java.util.ArrayDeque<SharedBoundaryDrainage.WorldCell>();
            queue.add(from); seen.add(from);
            while (!queue.isEmpty()) {
                var point = queue.remove();
                if (point.equals(to)) return level;
                for (int[] dir : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
                    var next = new SharedBoundaryDrainage.WorldCell(point.x() + dir[0], point.z() + dir[1]);
                    if (next.x() < lo || next.z() < lo || next.x() > hi || next.z() > hi) continue;
                    if (terrain.height(next.x(), next.z()) <= level && seen.add(next)) queue.add(next);
                }
            }
        }
        throw new IllegalStateException("oracle disconnected");
    }

    private static boolean continuous(List<SharedBoundaryDrainage.WorldCell> path) {
        for (int i = 1; i < path.size(); i++) {
            var a = path.get(i - 1); var b = path.get(i);
            if (Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) != 1) return false;
        }
        return !path.isEmpty();
    }

    private static void secCellView() throws Exception {
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        var d = PROV.sharedLakeDecision(-344, -508);
        var domain = PROV.domainForProbe(d.key());
        var p = domain.pipeForProbe(); var geom = domain.geom();
        int id = LakeWaterBalance.basinOf(p.lakeResult(), domain.cellAt(-344, -508));
        var chain = SharedBoundaryDrainage.basinChain(p, id, geom.originX(), geom.originZ());
        var witnesses = new ArrayList<SharedBoundaryDrainage.ShoreWitness>();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        for (var edge : chain.edges()) {
            var cells = new ArrayList<SharedBoundaryDrainage.CellConnectivity>();
            for (int x = Math.min(edge.inner().x(), edge.outer().x()); x <= Math.max(edge.inner().x(), edge.outer().x()); x += p.cellBlocks())
                for (int z = Math.min(edge.inner().z(), edge.outer().z()); z <= Math.max(edge.inner().z(), edge.outer().z()); z += p.cellBlocks())
                    cells.add(SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(x, z), p.cellBlocks(), terrain));
            var first = cells.stream().filter(c -> c.center().equals(edge.inner())).findFirst().orElseThrow();
            var last = cells.stream().filter(c -> c.center().equals(edge.outer())).findFirst().orElseThrow();
            var from = first.corners().get(minimumCorner(first)); var to = last.corners().get(minimumCorner(last));
            int k = (edge.inner().x() - geom.originX()) / p.cellBlocks() * p.nz()
                    + (edge.inner().z() - geom.originZ()) / p.cellBlocks();
            double water = p.lakeResult().basins().get(LakeWaterBalance.basinOf(p.lakeResult(), k)).level();
            var result = SharedBoundaryDrainage.composeCells(cells, from, to, water);
            var direct = SharedBoundaryDrainage.refineShore(edge, p.cellBlocks(), water, terrain);
            chk(result.barrier() == direct.barrier(), "真实共享接口组合瓶颈等于同域直接搜索", direct.toString());
            var reverseOrder = new ArrayList<>(cells); java.util.Collections.reverse(reverseOrder);
            chk(result.equals(SharedBoundaryDrainage.composeCells(reverseOrder, from, to, water)), "真实格元组合逆序同值", result.toString());
            chk(continuous(result.path()), "真实格元组合是完整四邻方块路径", result.toString());
            chk(!result.passable() || result.path().stream().allMatch(c -> terrain.height(c.x(), c.z()) <= water + 1e-8),
                    "可通证书逐方块不超过现有湖面", result.toString());
            log("CELL_WITNESS inner=" + edge.inner() + " water=" + water + " " + result);
            witnesses.add(result);
        }
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "格元接口零邻fine依赖", "increased");
        renderCellWitnesses(witnesses);
        Files.writeString(OUT.resolve("cell-contract.txt"), LOG.toString());
    }

    private static int minimumCorner(SharedBoundaryDrainage.CellConnectivity cell) {
        int best = 0;
        for (int i = 1; i < cell.cornerBeds().size(); i++) if (cell.cornerBeds().get(i) < cell.cornerBeds().get(best)) best = i;
        return best;
    }

    private static void renderCellWitnesses(List<SharedBoundaryDrainage.ShoreWitness> witnesses) throws Exception {
        int cx = -164, cz = -542, radius = 96;
        Path dir = OUT.resolve("cell-view-" + System.currentTimeMillis()); Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "接口局部图生产列无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics();
        for (var result : witnesses) {
            g.setColor(result.passable() ? java.awt.Color.GREEN : java.awt.Color.RED);
            for (int i = 1; i < result.path().size(); i++) {
                var a = result.path().get(i - 1); var b = result.path().get(i);
                g.drawLine(a.x() - cx + radius, a.z() - cz + radius, b.x() - cx + radius, b.z() - cz + radius);
            }
        }
        g.dispose();
        writeLocalImage(dir.resolve("witness.png"), raw, cx, cz, radius, "RED = BLOCKED / GREEN = CAPACITY ONLY");
        log("CELL_VIEW_DIR=" + dir.toAbsolutePath());
        Files.writeString(dir.resolve("manifest.txt"), "actual production water unchanged; center(-164,-542) radius96; fixed boundary-port geometry diagnostics only\n"
                + "red=barrier above water in fixed corridor; green=capacity only, not new generated water\n" + LOG);
    }

    private static void secShore(boolean view) throws Exception {
        log("\n=== [shore] 实际共享湖成员的成对出口与终端，不发布水 ===");
        shoreSynthetic();
        var d = PROV.sharedLakeDecision(-344, -508);
        chk(d.controlled() && d.lake(), "现场共享湖受控且非空", d.toString());
        if (!d.controlled() || !d.lake()) return;
        var domain = PROV.domainForProbe(d.key());
        var p = domain.pipeForProbe();
        chk(p != null, "实际共享细解可审计", "missing");
        if (p == null) return;
        int id = LakeWaterBalance.basinOf(p.lakeResult(), domain.cellAt(-344, -508));
        var g = domain.geom();
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var audit = SharedBoundaryDrainage.auditBasin(p, id, g.originX(), g.originZ());
        Map<SharedBoundaryDrainage.BasinEnd, Integer> counts = new java.util.EnumMap<>(SharedBoundaryDrainage.BasinEnd.class);
        for (var edge : audit.shores()) counts.merge(edge.terminal(), 1, Integer::sum);
        double surface = p.lakeResult().basins().get(id).level();
        var viable = audit.shores().stream().filter(e -> (e.terminal() == SharedBoundaryDrainage.BasinEnd.SEA
                || e.terminal() == SharedBoundaryDrainage.BasinEnd.NEXT_LAKE) && e.pathBarrier() <= surface + 1e-8).toList();
        log("members=" + audit.members() + " wet=" + audit.wetMembers() + " escapeRange="
                + audit.minEscape() + ".." + audit.maxEscape() + " actualSurface=" + surface
                + " shores=" + audit.shores().size() + " terminals=" + counts + " viable=" + viable.size());
        for (var end : SharedBoundaryDrainage.BasinEnd.values()) audit.shores().stream()
                .filter(e -> e.terminal() == end).limit(2).forEach(e -> log("best-" + end + "=" + e));
        Files.writeString(OUT.resolve("shore-edges.txt"), audit.toString());
        // #region agent log: A wrong exterior-min vs B mixed equilibrium basin
        Files.writeString(Path.of("D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log"),
                "A/B shore basin=" + id + " escape=" + audit.minEscape() + ".." + audit.maxEscape()
                        + " surface=" + surface + " terminals=" + counts + " viable=" + viable.size() + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        // #endregion
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "岸线分析无邻fine扇出", "increased");
        chk(audit.members() > 0 && !audit.shores().isEmpty(), "实际成员/岸线候选非空转", "empty");
        chk(!viable.isEmpty(), "真实湖面下存在不自回且抵达另一湖/海的候选", "无可发布候选，未知不当通过");
        var chain = SharedBoundaryDrainage.basinChain(p, id, g.originX(), g.originZ());
        log("SHARED_BASIN_CHAIN=" + chain);
        for (int next : chain.basins()) log("chain-balance=" + next + " " + p.lakeResult().basins().get(next));
        int last = chain.basins().get(chain.basins().size() - 1);
        var lastAudit = SharedBoundaryDrainage.auditBasin(p, last, g.originX(), g.originZ());
        log("chain-last-selection=" + SharedBoundaryDrainage.selectOutlet(p, lastAudit));
        lastAudit.shores().stream().filter(e -> e.parentEdge()).forEach(e -> log("chain-last-parent=" + e));
        inspectShoreProfiles(p, g, chain);
        chk(chain.state() != SharedBoundaryDrainage.OutletState.CYCLE, "湖节点组合无回环", chain.toString());
        chk(chain.state() == SharedBoundaryDrainage.OutletState.CANDIDATE
                        || chain.state() == SharedBoundaryDrainage.OutletState.CLOSED_LAKE,
                "共享窗内湖链有已证终端（未知不当入海）", chain.toString());
        if (view) renderShore(domain, p, audit, surface);
        Files.writeString(OUT.resolve("shore.txt"), LOG.toString());
    }

    private static void inspectShoreProfiles(HydrologyPipeline.Result p, SharedLakeDomain.Geom g,
            SharedBoundaryDrainage.BasinChain chain) {
        double hs = TP.horizontalScale();
        for (var edge : chain.edges()) {
            int innerCell = (edge.inner().x() - g.originX()) / p.cellBlocks() * p.nz()
                    + (edge.inner().z() - g.originZ()) / p.cellBlocks();
            int id = LakeWaterBalance.basinOf(p.lakeResult(), innerCell);
            double surface = p.lakeResult().basins().get(id).level();
            var witness = SharedBoundaryDrainage.refineShore(edge, p.cellBlocks(), surface,
                    (x, z) -> GEN.erodedHeight(x / hs, z / hs));
            log("SHORE_BLOCK_WITNESS=" + witness + " inner=" + edge.inner() + " surface=" + surface);
            chk(witness.passable(), "有向穿岸候选存在不抬水的四邻方块走廊 " + edge.inner(), witness.toString());
            double maxRise = 0, maxCenter = Double.NEGATIVE_INFINITY, prev = Double.NaN;
            for (int i = 1; i < edge.path().size(); i++) {
                var a = edge.path().get(i - 1); var b = edge.path().get(i);
                int count = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
                for (int t = 0; t <= count; t++) {
                    double x = a.x() + (b.x() - a.x()) * t / (double) count;
                    double z = a.z() + (b.z() - a.z()) * t / (double) count;
                    double h = GEN.erodedHeight(x / hs, z / hs);
                    if (Double.isFinite(prev)) maxRise = Math.max(maxRise, h - prev);
                    maxCenter = Math.max(maxCenter, h); prev = h;
                }
            }
            log("SHORE_PROFILE inner=" + edge.inner() + " gridBarrier=" + edge.pathBarrier()
                    + " centerMax=" + maxCenter + " maxOneBlockBedRise=" + maxRise
                    + " (valley-min cells != validated continuous river bed/water)");
        }
    }

    private static void renderShore(SharedLakeDomain.Domain domain, HydrologyPipeline.Result p,
            SharedBoundaryDrainage.BasinAudit audit, double surface) throws Exception {
        int cx = -160, cz = -640, radius = 224;
        Path dir = OUT.resolve("shore-view-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "岸线对照图走生产列且无失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var g = raw.createGraphics();
        var geom = domain.geom();
        var chain = SharedBoundaryDrainage.basinChain(p, audit.basinId(), geom.originX(), geom.originZ());
        g.setColor(new java.awt.Color(255, 165, 0, 150));
        for (int k = 0; k < p.bed().length; k++) {
            if (p.lake()[k] && chain.basins().contains(LakeWaterBalance.basinOf(p.lakeResult(), k)))
                g.fillRect(geom.originX() + k / p.nz() * p.cellBlocks() - cx + radius - 1,
                        geom.originZ() + k % p.nz() * p.cellBlocks() - cz + radius - 1, 3, 3);
        }
        for (var edge : chain.edges()) {
            g.setColor(java.awt.Color.YELLOW);
            for (int i = 1; i < edge.path().size(); i++) {
                var a = edge.path().get(i - 1); var b = edge.path().get(i);
                g.drawLine(a.x() - cx + radius, a.z() - cz + radius, b.x() - cx + radius, b.z() - cz + radius);
            }
            log("DRAWN_DIAGNOSTIC_EDGE=" + edge + " surface=" + surface);
        }
        g.dispose();
        writeLocalImage(dir.resolve("shore.png"), raw, cx, cz, radius,
                "ORANGE = ACTUAL SHARED LAKE CELLS / YELLOW = DIAGNOSTIC ONLY");
        log("SHORE_VIEW_DIR=" + dir.toAbsolutePath());
        Files.writeString(dir.resolve("manifest.txt"), "actual provider columns unchanged; center(-160,-640) radius224 1px/block x3\n"
                + "orange=actual shared fine wet member samples; yellow=parent candidate not generated water\n"
                + "no proof of actual downstream water/sea; not canonical acceptance\n" + LOG);
    }

    private static void secOuter(boolean view) throws Exception {
        log("\n=== [outer] 整湖水位以下围合体与外围逃逸（未发布新水/出口） ===");
        outerSynthetic();
        var routing = localRouting();
        var start = new SharedBoundaryDrainage.WorldCell(-344, -508);
        var decision = PROV.sharedLakeDecision(start.x(), start.z());
        var pipe = PROV.pipeForProbe(start.x(), start.z());
        int ox = Math.floorDiv(start.x(), hp().tract()) * hp().tract() + hp().tract() / 2 - hp().fineRadius();
        int oz = Math.floorDiv(start.z(), hp().tract()) * hp().tract() + hp().tract() / 2 - hp().fineRadius();
        int cell = Math.floorDiv(start.x() - ox, hp().step()) * pipe.nz()
                + Math.floorDiv(start.z() - oz, hp().step());
        int id = LakeWaterBalance.basinOf(pipe.lakeResult(), cell);
        log("shared=" + decision + " localBasin=" + id + " localSurface=" + pipe.waterSurfaceAt(cell));
        if (id >= 0) log("local-balance=" + pipe.lakeResult().basins().get(id)
                + " outletCell=" + LakeWaterBalance.outletOf(pipe.lakeResult(), id));
        if (decision.controlled() && decision.lake()) auditSharedLake(decision, start);
        double level = decision.controlled() && decision.lake() ? decision.surface() : pipe.waterSurfaceAt(cell);
        chk((decision.controlled() && decision.lake()) || pipe.lake()[cell], "起点为实际发布湖体", "not lake");
        var basin = routing.enclosingAt(start, level, hp().fineRadius());
        long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
        var outlet = routing.enclosingOutlet(basin, hp().fineRadius());
        log("enclosing status=" + basin.status() + " owner=" + basin.owner() + " level=" + level
                + " members=" + basin.members().size() + " capacity=" + basin.capacity());
        log("outer-outlet=" + outlet);
        chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "整湖外围搜索无邻fine依赖", "fine increased");
        chk(basin.status() == SharedBoundaryDrainage.LocalStatus.SPILL_CANDIDATE,
                "整湖水位以下连通成员未被固定域截断", basin.status().toString());
        chk(outlet.status() == SharedBoundaryDrainage.LocalStatus.SPILL_CANDIDATE && outlet.downstream() != null,
                "整湖有未触框的已验证外侧候选（待决仍FAIL）", outlet.status().toString());
        if (basin.owner() != null && outlet.downstream() != null) {
            var downstream = routing.downstream(basin, outlet, hp().fineRadius());
            log("outer-downstream=" + downstream);
            chk(downstream.status() != SharedBoundaryDrainage.LocalStatus.SELF_RETURN,
                    "下游不返回任一湖成员（非仅sink）", downstream.toString());
            if (outlet.spill() != null) {
                var members = new java.util.HashSet<>(basin.members());
                chk(members.contains(outlet.spill().inner()) && !members.contains(outlet.spill().outer())
                                && outlet.path().subList(1, outlet.path().size()).stream().noneMatch(members::contains),
                        "候选从真正湖边出发且不穿回湖内部", outlet.toString());
            }
        }
        log("最低逃逸目标为低于整盆底的外床；仅充分的几何条件，非全局海可达/正出流证书。");
        if (view) renderOuter(routing, basin, outlet);
        Files.writeString(OUT.resolve("outer.txt"), LOG.toString());
    }

    private static void auditSharedLake(SharedLakeDomain.Decision decision,
            SharedBoundaryDrainage.WorldCell start) {
        var domain = PROV.domainForProbe(decision.key());
        var fine = domain.pipeForProbe();
        chk(fine != null, "共享湖保留实际细解审计（仅诊断开关）", "missing audit");
        if (fine == null) return;
        int cell = domain.cellAt(start.x(), start.z());
        int id = LakeWaterBalance.basinOf(fine.lakeResult(), cell);
        log("shared-fine-basin=" + id + " geom=" + domain.geom());
        chk(id >= 0, "共享受控湖有同源盆地身份", "missing basin");
        if (id < 0) return;
        var balance = fine.lakeResult().basins().get(id);
        int outlet = LakeWaterBalance.outletOf(fine.lakeResult(), id);
        log("shared-balance=" + balance + " outletCell=" + outlet);
        chk(balance.outflow() <= 0 || outlet >= 0, "正出流共享湖存在出口索引", "missing outlet");
        if (outlet < 0) return;
        chk(balance.outflow() <= 0 || fine.bed()[outlet] <= balance.level() + 1e-8,
                "正出流出口床面不高于真实湖面（不能抬水凑通）",
                "bed=" + fine.bed()[outlet] + " surface=" + balance.level());
        int n = fine.nz(), i = outlet / n, j = outlet % n;
        log("shared-local-outlet-world=" + (domain.geom().originX() + i * domain.geom().step())
                + "," + (domain.geom().originZ() + j * domain.geom().step())
                + " bed=" + fine.bed()[outlet] + " level=" + fine.level()[outlet]);
        Set<Integer> seen = new LinkedHashSet<>();
        int k = outlet;
        while (k >= 0 && seen.add(k)) {
            if (fine.sea()[k]) { log("shared-parent-end=SEA nodes=" + seen.size()); return; }
            if (fine.lake()[k] && LakeWaterBalance.basinOf(fine.lakeResult(), k) == id) {
                log("shared-parent-end=SELF_RETURN cell=" + k + " nodes=" + seen.size());
                chk(balance.outflow() <= 0, "正出流共享湖出口不能回流本湖", "SELF_RETURN cell=" + k);
                return;
            }
            if (k / n == 0 || k % n == 0 || k / n == fine.nx() - 1 || k % n == n - 1) {
                log("shared-parent-end=UNKNOWN_FRAME nodes=" + seen.size()); return;
            }
            k = fine.parent()[k];
        }
        log("shared-parent-end=" + (k < 0 ? "NO_PARENT" : "CYCLE") + " nodes=" + seen.size());
    }

    private static void outerSynthetic() {
        WindowOutlets.Terrain terrain = (x, z) -> {
            if (x < -2 || x > 30 || Math.abs(z) > 2) return 130;
            if (x < 3) return 100;
            if (x < 11) return 105;
            if (x < 19) return 90;
            if (x < 27) return 120;
            return 80;
        };
        var routing = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, terrain);
        var basin = routing.enclosingAt(new SharedBoundaryDrainage.WorldCell(0, 0), 110, 32);
        var other = routing.enclosingAt(new SharedBoundaryDrainage.WorldCell(12, 0), 110, 32);
        var outlet = routing.enclosingOutlet(basin, 32);
        chk(basin.equals(other) && basin.members().contains(new SharedBoundaryDrainage.WorldCell(0, 0))
                        && basin.members().contains(new SharedBoundaryDrainage.WorldCell(12, 0))
                        && basin.capacity() > 0 && outlet.equals(routing.enclosingOutlet(other, 32)),
                "嵌套小盆合为同一湖节点，查询起点不改变外围出口", basin + " / " + outlet);
        chk(outlet.sill() == 120 && outlet.downstream().x() == 28
                        && !basin.members().contains(outlet.spill().outer()),
                "整湖只认外围鞍口120，不认被淹内部鞍口105", outlet.toString());
        var limited = routing.enclosingAt(new SharedBoundaryDrainage.WorldCell(0, 0), 110, 8);
        chk(limited.status() == SharedBoundaryDrainage.LocalStatus.DOMAIN_LIMIT && limited.members().isEmpty(),
                "成员触框不发布部分湖体/假出口", limited.toString());
        var fake = new SharedBoundaryDrainage.BasinEscape(SharedBoundaryDrainage.LocalStatus.SPILL_CANDIDATE,
                new SharedBoundaryDrainage.WorldCell(0, 0), new SharedBoundaryDrainage.WorldCell(12, 0),
                120, null, List.of(), 0);
        chk(routing.downstream(basin, fake, 32).status() == SharedBoundaryDrainage.LocalStatus.SELF_RETURN,
                "返回任一成员而非原sink同样必须拒绝", "missed return");
    }

    private static void renderOuter(SharedBoundaryDrainage.TerrainRouting routing,
            SharedBoundaryDrainage.BasinFootprint basin, SharedBoundaryDrainage.BasinEscape outlet) throws Exception {
        int cx = -344, cz = -508, radius = 160;
        Path dir = OUT.resolve("outer-view-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "整湖局部生产渲染无求解失败", "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        writeLocalImage(dir.resolve("production.png"), raw, cx, cz, radius, "PRODUCTION WATER / UNCHANGED");
        var overlay = new java.awt.image.BufferedImage(raw.getWidth(), raw.getHeight(), raw.getType());
        var g = overlay.createGraphics(); g.drawImage(raw, 0, 0, null);
        g.setColor(new java.awt.Color(255, 165, 0, 150));
        for (var member : basin.members())
            g.fillRect(member.x() - cx + radius - 1, member.z() - cz + radius - 1, 3, 3);
        g.setColor(java.awt.Color.YELLOW);
        var path = outlet.path();
        for (int i = 1; i < path.size(); i++) {
            var a = path.get(i - 1); var b = path.get(i);
            g.drawLine(a.x() - cx + radius, a.z() - cz + radius,
                    b.x() - cx + radius, b.z() - cz + radius);
        }
        g.dispose();
        writeLocalImage(dir.resolve("geometry.png"), overlay, cx, cz, radius,
                "ORANGE = BELOW-PLANE MEMBERS / YELLOW = OUTLET CANDIDATE ONLY");
        log("OUTER_VIEW_DIR=" + dir.toAbsolutePath() + " lake=" + mask.lakeCols + " river=" + mask.riverCols);
        Files.writeString(dir.resolve("manifest.txt"), "provider production columns; 1px/block x3\n"
                + "orange=geometry connected below existing lake plane; not published membership\n"
                + "yellow=candidate only, not generated water; not canonical radius700 acceptance\n" + LOG);
    }

    private static void secClosure() throws Exception {
        log("\n=== [closure] 几何候选下游/完整成员验证，不冒充湖水收支 ===");
        var routing = localRouting();
        var sink = new SharedBoundaryDrainage.WorldCell(-344, -508);
        var escape = routing.escape(sink, hp().fineRadius());
        var footprint = routing.footprint(escape, hp().fineRadius());
        var downstream = routing.downstream(escape, hp().fineRadius());
        log("escape=" + escape + " footprint=" + footprint + " downstream=" + downstream);
        chk(escape.status() == SharedBoundaryDrainage.LocalStatus.FLAT_CANDIDATE
                        && footprint.members().isEmpty() && footprint.capacity() == 0,
                "本病例为零蓄水平地接续，不冒充湖泊溢流", footprint.toString());
        chk(downstream.status() != SharedBoundaryDrainage.LocalStatus.SELF_RETURN,
                "最低逃逸后不自回本sink", downstream.toString());
        if (downstream.status() == SharedBoundaryDrainage.LocalStatus.BASIN_PENDING) {
            var nextEscape = routing.escape(downstream.terminal(), hp().fineRadius());
            var nextFootprint = routing.footprint(nextEscape, hp().fineRadius());
            log("next-basin escape=" + nextEscape + " footprint=" + nextFootprint);
        }
        var chain = routing.candidateChain(sink, hp().fineRadius(), 16);
        log("candidate-chain=" + chain);
        int lakeNodes = 0, wetNodes = 0;
        Map<Long, List<HydrologyBlockCarvedColumn>> columns = new HashMap<>();
        for (var node : chain.path()) {
            int cx = Math.floorDiv(node.x(), 16), cz = Math.floorDiv(node.z(), 16);
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            var cols = columns.computeIfAbsent(key, k -> PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz)));
            for (var c : cols) if (c.blockX() == node.x() && c.blockZ() == node.z()) {
                if (c.lakePlan()) lakeNodes++;
                if (c.fillWater() && c.waterSurfaceY() > c.carvedGroundY()) wetNodes++;
            }
        }
        log("candidate nodes=" + chain.path().size() + " productionLakeNodes=" + lakeNodes
                + " productionWetNodes=" + wetNodes + " (lake interior must not publish separate river)");
        for (var transition : chain.transitions())
            log("chain-members=" + routing.footprint(transition, hp().fineRadius()));
        chk(chain.status() != SharedBoundaryDrainage.LocalStatus.SELF_RETURN,
                "连续候选链不自回（跨平地/洼地）", chain.status().toString());
        chk(chain.status() == SharedBoundaryDrainage.LocalStatus.SEA,
                "本病例候选全链闭合到海（待决不得当入海）", chain.status().toString());
        Files.writeString(OUT.resolve("closure.txt"), LOG.toString());
    }

    private static void secLocalView(long seed) throws Exception {
        log("\n=== [local-view] 生产水体与诊断候选分开，不是正典全图验收 ===");
        Path dir = OUT.resolve("local-view-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        var routing = localRouting();
        renderLocalCase(dir, routing, -650, 512, 96, "three-seam-breaks",
                List.of(new SharedBoundaryDrainage.WorldCell(-712, 504),
                        new SharedBoundaryDrainage.WorldCell(-648, 504),
                        new SharedBoundaryDrainage.WorldCell(-580, 504)), false);
        renderLocalCase(dir, routing, -344, -508, 96, "basin-spill",
                List.of(new SharedBoundaryDrainage.WorldCell(-344, -508)), true);
        log("LOCAL_VIEW_DIR=" + dir.toAbsolutePath() + " seed=" + seed);
        Files.writeString(dir.resolve("manifest.txt"), "seed=" + seed + "\nconfig=run/config/geogenesis-common.toml(copy)"
                + "\nhier=true shared=true skeleton=true\nprovider water only; yellow=candidate geometry NOT generated water"
                + "\nproduction image excludes overlays; localized not canonical radius700 acceptance\n" + LOG);
    }

    private static void renderLocalCase(Path dir, SharedBoundaryDrainage.TerrainRouting routing,
            int cx, int cz, int radius, String name, List<SharedBoundaryDrainage.WorldCell> starts,
            boolean spill) throws Exception {
        var mask = HierarchicalComparisonView.sampleAll(GEN, PROV, cx, cz, radius);
        chk(mask.failCount == 0, "局部生产渲染无求解失败 " + name, "fail=" + mask.failCount);
        var raw = HierarchicalComparisonView.render(mask, GEN);
        if (!javax.imageio.ImageIO.write(raw, "png", dir.resolve(name + "-raw.png").toFile()))
            throw new java.io.IOException("PNG writer");
        writeLocalImage(dir.resolve(name + "-production.png"), raw, cx, cz, radius,
                "PRODUCTION WATER / unchanged routing");
        var overlay = new java.awt.image.BufferedImage(raw.getWidth(), raw.getHeight(), raw.getType());
        var g = overlay.createGraphics();
        g.drawImage(raw, 0, 0, null);
        g.setColor(java.awt.Color.YELLOW);
        for (var start : starts) {
            var descent = routing.descend(start, hp().fineRadius());
            List<SharedBoundaryDrainage.WorldCell> path = descent.path();
            if (spill) {
                var chain = routing.candidateChain(start, hp().fineRadius(), 16);
                path = chain.path();
                log(name + " geometry-only chain=" + chain);
            } else log(name + " geometry-only descent=" + descent);
            for (int i = 1; i < path.size(); i++) {
                var a = path.get(i - 1); var b = path.get(i);
                g.drawLine(a.x() - cx + radius, a.z() - cz + radius,
                        b.x() - cx + radius, b.z() - cz + radius);
            }
        }
        g.dispose();
        writeLocalImage(dir.resolve(name + "-candidate.png"), overlay, cx, cz, radius,
                "YELLOW = GEOMETRY ONLY / NOT GENERATED WATER");
        log("render=" + name + " radius=" + radius + " waterLake=" + mask.lakeCols
                + " waterRiver=" + mask.riverCols + " chunks=" + mask.chunks + " ms=" + mask.ms);
    }

    private static void writeLocalImage(Path file, java.awt.image.BufferedImage raw,
            int cx, int cz, int radius, String label) throws Exception {
        int scale = 3, width = raw.getWidth() * scale, header = 58;
        var image = new java.awt.image.BufferedImage(width, raw.getHeight() * scale + header,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.BLACK); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
        g.drawString(label, 8, 18);
        g.drawString("center(" + cx + "," + cz + ") radius=" + radius + " 1 data pixel/block; displayed x3", 8, 36);
        g.drawImage(raw, 0, header, width, raw.getHeight() * scale, null);
        g.setColor(new java.awt.Color(255, 70, 180, 190));
        for (int x = Math.floorDiv(cx - radius, hp().tract()) * hp().tract(); x <= cx + radius; x += hp().tract()) {
            int px = (x - cx + radius) * scale;
            g.drawLine(px, header, px, image.getHeight() - 1);
        }
        for (int z = Math.floorDiv(cz - radius, hp().tract()) * hp().tract(); z <= cz + radius; z += hp().tract()) {
            int pz = header + (z - cz + radius) * scale;
            g.drawLine(0, pz, width - 1, pz);
        }
        g.dispose();
        if (!javax.imageio.ImageIO.write(image, "png", file.toFile())) throw new java.io.IOException("PNG writer");
    }

    private static void secBoundaryLocal() throws Exception {
        log("\n=== [boundary-local] 绝对地形共同下游（原型，未接生产） ===");
        localSynthetic();
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        var routing = new SharedBoundaryDrainage.TerrainRouting(hp().step(), hp().tract(), TP.seaLevel(), terrain);
        var other = new SharedBoundaryDrainage.TerrainRouting(hp().step(), hp().tract(), TP.seaLevel(), terrain);
        int samples = 0, mismatches = 0, uphill = 0;
        // Entire overlap of H(1,0,-1) and V(0,-512,0), including same-axis triple coverage.
        for (int x = -608; x <= -160; x += hp().step()) for (int z = -96; z <= 352; z += hp().step()) {
            var a = routing.at(x, z);
            var b = other.at(x, z);
            samples++;
            if (!a.equals(b)) mismatches++;
            if (a.next() != null && !(a.downstreamBed() < a.bed())) uphill++;
        }
        chk(samples > 0 && mismatches == 0 && uphill == 0,
                "全重叠区权威同值且每条边严格真实下降", "samples=" + samples + " mismatch=" + mismatches + " uphill=" + uphill);
        boolean reverseSame = true;
        for (int x = -160; x >= -608; x -= hp().step()) for (int z = 352; z >= -96; z -= hp().step())
            reverseSame &= routing.at(x, z).equals(other.at(x, z));
        chk(reverseSame, "逆序/缓存不改变角点下游", "different");
        int events = 0, covered = 0;
        Map<SharedBoundaryDrainage.LocalStatus, Integer> ends = new java.util.EnumMap<>(SharedBoundaryDrainage.LocalStatus.class);
        for (int[] r : new int[][]{{-2, 0}, {-1, 0}, {-1, -1}, {0, -1}}) {
            var snapshot = PROV.drainageForProbe(r[0], r[1]);
            for (var event : snapshot.crossings()) {
                if (!event.upstream().equals(snapshot.region())) continue;
                long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
                var mapping = routing.map(event, hp().fineRadius(), 257 * 257);
                chk(fine0 == HierarchicalHydrology.Diag.FINE_SOLVES.get(), "路由无邻fine依赖", "fine增加");
                events++;
                if (mapping.connection() != null) covered++;
                ends.merge(mapping.status(), 1, Integer::sum);
                log("local-event=" + event + " mapping=" + mapping);
                if (mapping.status() == SharedBoundaryDrainage.LocalStatus.BASIN_PENDING) {
                    var escape = routing.escape(mapping.terminal(), hp().fineRadius());
                    var repeat = other.escape(mapping.terminal(), hp().fineRadius());
                    chk(escape.equals(repeat), "真实洼地固定sink域溢口逆序同值", "different");
                    log("basin-escape=" + escape);
                }
                if (mapping.status() == SharedBoundaryDrainage.LocalStatus.DOMAIN_LIMIT) {
                    var terminal = routing.at(mapping.terminal().x(), mapping.terminal().z());
                    log("limit-downstream=" + terminal);
                    var actual = routing.firstCrossing(event, hp().fineRadius());
                    log("first-actual-crossing=" + actual);
                    chk(actual.connection() != null, "未走原缝病例仍有真实过界（不替换原覆盖判据）",
                            actual.toString());
                }
            }
        }
        log("local-routing events=" + events + " covered=" + covered + " terminals=" + ends
                + " sampledCells=" + routing.sampledCells() + " overlapSamples=" + samples
                + " overlapMismatch=" + mismatches + " uphill=" + uphill);
        chk(events > 0 && covered == events, "共同真实地形下游覆盖全部发布过界（含洼地）",
                "events=" + events + " covered=" + covered + "；BASIN_PENDING不是已连通");
        Files.writeString(OUT.resolve("boundary-local.txt"), LOG.toString());
    }

    private static void localSynthetic() {
        WindowOutlets.Terrain slope = (x, z) -> 100 - x + 0.01 * (z - 8) * (z - 8);
        var route = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, slope);
        var west = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, (x, z) -> 100 + x);
        chk(route.at(0, 8).next().x() > 0 && west.at(0, 8).next().x() < 0,
                "共同规则由坡面决定方向，不按坐标指定下游", "wrong direction");
        var flat = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, (x, z) -> 100);
        var pit = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, (x, z) -> x * x + z * z + 100);
        chk(flat.at(0, 0).status() == SharedBoundaryDrainage.LocalStatus.BASIN_PENDING
                        && pit.at(0, 0).status() == SharedBoundaryDrainage.LocalStatus.BASIN_PENDING,
                "真平地/洼地显式待决，不能造假坡度或当海", "wrong terminal");
        var id = new ProductionDrainageAudit.SegmentId(new ProductionDrainageAudit.Region(-1, 0), 0);
        var event = ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(-4, 8),
                new ProductionDrainageAudit.Point(4, 8), 16).get(0);
        var mapped = route.map(event, 16, 100);
        chk(mapped.connection() != null && mapped.point().x() == 0,
                "世界边连接ID不含调用方窗口，斜坡真实过缝", mapped.toString());
        WindowOutlets.Terrain bowl = (x, z) -> {
            if (x >= -2 && x <= 10 && Math.abs(z) <= 2)
                return x < 1 ? 100 : x <= 7 ? 105 : 90;
            return 120;
        };
        var basin = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, bowl);
        var spill = basin.escape(new SharedBoundaryDrainage.WorldCell(0, 0), 16);
        chk(spill.status() == SharedBoundaryDrainage.LocalStatus.SPILL_CANDIDATE && spill.sill() == 105
                        && spill.downstream().x() == 8,
                "最低可通鞍口内外路径瓶颈105（不是最低外床90）", spill.toString());
        chk(spill.spill() != null && Math.max(spill.spill().innerBed(), spill.spill().outerBed()) == spill.sill(),
                "溢口内外配对与同一路径瓶颈一致", spill.toString());
        var footprint = basin.footprint(spill, 16);
        chk(footprint.capacity() > 0 && footprint.owner() != null && !footprint.members().isEmpty(),
                "合成蓄水盆地发布完整成员与容量", footprint.toString());
        var translated = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0,
                (x, z) -> bowl.height(x + 100, z - 40));
        var translatedSpill = translated.escape(new SharedBoundaryDrainage.WorldCell(-100, 40), 16);
        chk(translatedSpill.sill() == spill.sill()
                        && translated.footprint(translatedSpill, 16).capacity() == footprint.capacity(),
                "盆体/瓶颈负坐标平移一致", translatedSpill.toString());
        var simpleChain = route.candidateChain(new SharedBoundaryDrainage.WorldCell(0, 8), 16, 2);
        chk(simpleChain.status() == SharedBoundaryDrainage.LocalStatus.DOMAIN_LIMIT,
                "出诊断域不是入海", simpleChain.toString());
        var ocean = new SharedBoundaryDrainage.TerrainRouting(4, 16, 98, (x, z) -> 100 - x);
        chk(ocean.candidateChain(new SharedBoundaryDrainage.WorldCell(0, 0), 16, 2).status()
                        == SharedBoundaryDrainage.LocalStatus.SEA,
                "合成下泄真实到海", "wrong terminal");
        nestedBasinSynthetic();
        outerSynthetic();
        shoreSynthetic();
        var corner = ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(-4, -4),
                new ProductionDrainageAudit.Point(4, 4), 16);
        var diagonal = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, (x, z) -> 100 - x - z);
        var c0 = diagonal.firstCrossing(corner.get(0), 16);
        var c1 = diagonal.firstCrossing(corner.get(1), 16);
        chk(c0.connection() != null && c0.connection().equals(c1.connection()),
                "角点两轴请求去重到同一世界过界边", c0 + " / " + c1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<SharedBoundaryDrainage.LocalStep>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> diagonal.at(-4, -4)));
            var first = futures.get(0).get(30, TimeUnit.SECONDS);
            boolean same = true;
            for (var f : futures) same &= first.equals(f.get(30, TimeUnit.SECONDS));
            chk(same, "四线程共同下游逐位同值", "different");
        } catch (Exception e) { chk(false, "四线程共同下游", e.toString()); }
        finally { pool.shutdownNow(); }
        chk(flat.escape(new SharedBoundaryDrainage.WorldCell(0, 0), 16).status()
                        == SharedBoundaryDrainage.LocalStatus.DOMAIN_LIMIT,
                "平地搜索触框拒绝，不能把窗边当溢口", "false spill");
        for (int spacing : new int[]{2, 4, 8}) {
            WindowOutlets.Terrain general = (x, z) -> 200 - x + 0.02 * z * z;
            var g = new SharedBoundaryDrainage.TerrainRouting(spacing, 32, 0, general);
            var r = new SharedBoundaryDrainage.TerrainRouting(spacing, 32, 0, general);
            boolean good = true;
            for (int x = -32; x <= 32; x += spacing) for (int z = -32; z <= 32; z += spacing) {
                var s = g.at(x, z);
                good &= s.equals(r.at(x, z)) && s.next() != null && s.downstreamBed() < s.bed();
            }
            chk(good, "多网格步长负坐标共同下降 step=" + spacing, "different/uphill");
        }
    }

    private static void nestedBasinSynthetic() {
        // Two sub-basins separated by a lower internal saddle, inside a higher enclosing saddle.
        WindowOutlets.Terrain terrain = (x, z) -> {
            if (x < -2 || x > 30 || Math.abs(z) > 2) return 130;
            if (x < 3) return 100;
            if (x < 11) return 105;
            if (x < 19) return 90;
            if (x < 27) return 120;
            return 80;
        };
        var routing = new SharedBoundaryDrainage.TerrainRouting(4, 16, 0, terrain);
        var start = new SharedBoundaryDrainage.WorldCell(0, 0);
        var chain = routing.candidateChain(start, 32, 8);
        chk(chain.status() == SharedBoundaryDrainage.LocalStatus.MERGE_PENDING,
                "高鞍口重淹前盆体必须合并待决，不能假装独立湖后河", chain.toString());
    }

    private static void secBoundary() throws Exception {
        log("\n=== [boundary] 固定共享域覆盖门（原型，未接生产路由） ===");
        boundarySynthetic();
        Map<SharedBoundaryDrainage.Key, SharedBoundaryDrainage.Domain> domains = new HashMap<>();
        double hs = TP.horizontalScale();
        WindowOutlets.Terrain terrain = (x, z) -> GEN.erodedHeight(x / hs, z / hs);
        WindowOutlets.Terrain outside = (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            return PROV.coarseLayerFor(Math.floorDiv(bx, hp().tract()), Math.floorDiv(bz, hp().tract()))
                    .levelAt(bx, bz);
        };
        int events = 0, covered = 0;
        for (int[] r : new int[][]{{-2, 0}, {-1, 0}, {-1, -1}, {0, -1}}) {
            var snapshot = PROV.drainageForProbe(r[0], r[1]);
            for (var event : snapshot.crossings()) {
                if (!event.upstream().equals(snapshot.region())) continue;
                double along = event.axis() == 0 ? event.point().z() : event.point().x();
                var key = new SharedBoundaryDrainage.Key(event.axis(), event.boundary(),
                        (int) Math.floor(along / hp().tract()));
                var domain = domains.get(key);
                if (domain == null) {
                    long fine0 = HierarchicalHydrology.Diag.FINE_SOLVES.get();
                    domain = SharedBoundaryDrainage.build(key, hp().tract(), hp().halo(), hp().step(),
                            TP.seaLevel(), terrain, outside);
                    domains.put(key, domain);
                    chk(HierarchicalHydrology.Diag.FINE_SOLVES.get() == fine0,
                            "共享域无邻fine依赖 " + key, "fine扇出");
                    chk(domain.cells() <= 257 * 257, "固定域有界 " + key, "超限");
                }
                events++;
                var mapping = domain.map(event);
                if (mapping.status() == SharedBoundaryDrainage.Status.CONNECTED) covered++;
                log("tract-event=" + event + " mapping=" + mapping);
            }
        }
        chk(events > 0 && covered == events, "所有真实tract过界边均映射合法共享走廊",
                "events=" + events + " covered=" + covered + " domains=" + domains.size());
        var horizontal = domains.get(new SharedBoundaryDrainage.Key(1, 0, -1));
        var vertical = domains.get(new SharedBoundaryDrainage.Key(0, -512, 0));
        if (horizontal != null && vertical != null) {
            int mismatches = 0;
            for (int x = -600; x <= -424; x += hp().step()) for (int z = -88; z <= 88; z += hp().step()) {
                var h = horizontal.downstreamAt(x, z);
                var v = vertical.downstreamAt(x, z);
                if (!java.util.Objects.equals(h, v)) {
                    mismatches++;
                    log("corner-conflict=" + x + "," + z + " horizontal=" + h + " vertical=" + v
                            + " escapeH=" + horizontal.escapeAt(x, z) + " escapeV=" + vertical.escapeAt(x, z));
                }
            }
            chk(mismatches == 0, "角点重叠域的下游裁决一致（推广前门槛）",
                    "mismatches=" + mismatches + "；不得按查询方挑域或忽略冲突");
            var corner = SharedBoundaryDrainage.buildAt(new SharedBoundaryDrainage.Key(0, -512, 0),
                    -512, 0, hp().fineRadius(), hp().tract(), hp().step(), TP.seaLevel(), terrain, outside);
            int crossings = 0, levelConflict = 0;
            for (int x = -608; x <= -416; x += hp().step()) for (int z = -96; z <= 96; z += hp().step()) {
                var domain = Math.abs(x + 512) < 96 && Math.abs(z) < 96 ? corner
                        : Math.abs(x + 512) <= Math.abs(z) ? vertical : horizontal;
                var next = domain.downstreamAt(x, z);
                if (next == null) continue;
                var receiving = Math.abs(next.x() + 512) < 96 && Math.abs(next.z()) < 96 ? corner
                        : Math.abs(next.x() + 512) <= Math.abs(next.z()) ? vertical : horizontal;
                if (receiving == domain) continue;
                crossings++;
                double sender = domain.escapeAt((int) next.x(), (int) next.z());
                double receiver = receiving.escapeAt((int) next.x(), (int) next.z());
                if (Double.doubleToLongBits(sender) != Double.doubleToLongBits(receiver)) levelConflict++;
            }
            log("corner-handoff crossings=" + crossings + " escapeConflicts=" + levelConflict);
            chk(crossings > 0 && levelConflict == 0,
                    "固定角点域换手高程同一语义（不能只换parent）", "escapeConflicts=" + levelConflict);
        } else chk(false, "角点域测试非空转", "missing domain");
        Files.writeString(OUT.resolve("boundary-coverage.txt"), LOG.toString());
    }

    private static void boundarySynthetic() {
        var key = new SharedBoundaryDrainage.Key(0, 0, 0);
        WindowOutlets.Terrain slope = (x, z) -> 100 - x + 0.01 * (z - 8) * (z - 8);
        var forward = SharedBoundaryDrainage.build(key, 16, 8, 4, 0, slope, slope);
        var reverse = SharedBoundaryDrainage.build(key, 16, 8, 4, 0, slope, slope);
        chk(!forward.connections().isEmpty() && forward.connections().equals(reverse.connections()),
                "合成斜坡共享连接身份/坐标/方向逐位同值", "无连接或不同值");
        chk(forward.connections().stream().allMatch(c -> c.tangent().x() > 0),
                "坡度决定下游而非坐标排序", forward.connections().toString());
        WindowOutlets.Terrain other = (x, z) -> 100 + x;
        var west = SharedBoundaryDrainage.build(key, 16, 8, 4, 0, other, other);
        chk(!west.connections().isEmpty() && west.connections().stream().allMatch(c -> c.tangent().x() < 0),
                "反坡连接向西", west.connections().toString());
        int[] parent = {-1, -1, -1, -1, 5, 4, -1, -1, -1};
        boolean refused = false;
        try { new SharedBoundaryDrainage.Domain(key, -4, 0, 3, 4, 16, parent, new double[9]); }
        catch (IllegalArgumentException e) { refused = e.getMessage().contains("cycle"); }
        chk(refused, "合成循环必须拒绝，不裁河伪装无环", "未检出");
    }

    private static void secProductionContract(boolean baseline) throws Exception {
        log("\n=== [production-contract] baseline=" + baseline + " ===");
        contractSynthetic();
        PROV.clear();
        for (int[] q : new int[][]{{-513, 194, 0}, {-1, -169, 0},
                {-710, 511, 1}, {-650, 511, 1}, {-582, 511, 1}}) {
            var connectivity = riverConnectivity(q[0], q[1], 16, q[2]);
            boolean expected = !baseline || q[2] == 0;
            chk(connectivity.connected() == expected,
                    "生产湿列8邻连通 " + q[0] + "," + q[1] + " expected=" + expected,
                    connectivity.toString());
        }
        for (int[] r : new int[][]{{-2, 0}, {-2, 1}, {-1, 0}, {-1, -1}, {0, -1}}) {
            var snapshot = PROV.drainageForProbe(r[0], r[1]);
            chk(snapshot != null && !snapshot.segments().isEmpty() && !snapshot.filtered(),
                    "生产发布骨架可追踪且未删簇 region=" + r[0] + "," + r[1], "空产物/过滤");
            if (snapshot == null) continue;
            Files.writeString(OUT.resolve("drainage-" + r[0] + "-" + r[1] + ".txt"), snapshot.toString());
            Map<ProductionDrainageAudit.Terminal, Integer> ends = new java.util.EnumMap<>(
                    ProductionDrainageAudit.Terminal.class);
            for (var chain : snapshot.chains()) ends.merge(chain.terminal(), 1, Integer::sum);
            log("region=" + snapshot.region() + " crossings=" + snapshot.crossings().size()
                    + " lakes=" + snapshot.lakes().size() + " terminals=" + ends);
            for (var lake : snapshot.lakes()) if (lake.outflow() > 0 && !lake.truncated()) {
                log("positive-outflow lake=" + lake.id() + " publishedOutlet=" + lake.outletSegment());
                if (!baseline) chk(lake.outletSegment() >= 0,
                        "正出流湖发布下游段 " + lake.id(), lake.toString());
            }
            if (!baseline) for (var chain : snapshot.chains()) chk(
                    chain.terminal() == ProductionDrainageAudit.Terminal.SEA
                            || chain.terminal() == ProductionDrainageAudit.Terminal.CLOSED_LAKE,
                    "生产生命周期闭合 " + chain.source(), chain.toString());
        }
        log("共享湖目前仅改写列平面，不发布统一出口；本区湖记录不能代替共享湖节点验收。");
    }

    private static void contractSynthetic() {
        var region = new ProductionDrainageAudit.Region(-1, 0);
        var id = new ProductionDrainageAudit.SegmentId(region, 0);
        var a = new ProductionDrainageAudit.Point(-2, 510);
        var b = new ProductionDrainageAudit.Point(2, 514);
        var crossings = ProductionDrainageAudit.crossings(id, a, b, 512);
        chk(crossings.size() == 2 && crossings.stream().allMatch(c -> c.point().x() == 0
                        && c.point().z() == 512 && !c.upstream().equals(c.downstream())),
                "斜穿角点记录两个轴的真实交点与owner", crossings.toString());
        var reverse = ProductionDrainageAudit.crossings(id, b, a, 512);
        chk(reverse.size() == 2 && reverse.get(0).upstream().equals(crossings.get(0).downstream()),
                "逆向过缝方向反转（负坐标）", reverse.toString());
        var seam = new ProductionDrainageAudit.Point(0, 10);
        chk(ProductionDrainageAudit.crossings(id, new ProductionDrainageAudit.Point(2, 10), seam, 512).size() == 1
                        && ProductionDrainageAudit.crossings(id, seam, new ProductionDrainageAudit.Point(-2, 10), 512).isEmpty(),
                "恰落缝线的折线节点只记一次", "重复或漏记");
        var source = segment(id, DeterministicSkeleton.End.MOUTH_LAKE, -1, 0);
        var second = segment(new ProductionDrainageAudit.SegmentId(region, 1),
                DeterministicSkeleton.End.MOUTH_LAKE, -1, 1);
        var sea = segment(new ProductionDrainageAudit.SegmentId(region, 2),
                DeterministicSkeleton.End.MOUTH_SEA, -1, -1);
        var lakes = List.of(lake(0, 1, 1), lake(1, 2, 1));
        var chain = ProductionDrainageAudit.follow(id, List.of(source, second, sea), lakes);
        chk(chain.terminal() == ProductionDrainageAudit.Terminal.SEA && chain.lakes() == 2,
                "源头→湖A→湖B→海追发布段（不用parent）", chain.toString());
        chk(ProductionDrainageAudit.follow(id, List.of(source), List.of(lake(0, -1, 0))).terminal()
                        == ProductionDrainageAudit.Terminal.CLOSED_LAKE,
                "蒸发平衡未满湖不强造出口", "终端错误");
        chk(ProductionDrainageAudit.follow(id, List.of(source), List.of(lake(0, -1, 1))).terminal()
                        == ProductionDrainageAudit.Terminal.MISSING_OUTLET,
                "正出流无发布河段不能冒充闭流湖", "漏检");
        chk(ProductionDrainageAudit.follow(id, List.of(source), List.of(lake(0, 0, 1))).terminal()
                        == ProductionDrainageAudit.Terminal.CYCLE,
                "湖出口自回流负例必须检出", "漏检");
        var join = segment(id, DeterministicSkeleton.End.JOIN, -1, -1);
        chk(ProductionDrainageAudit.follow(id, List.of(join), List.of()).terminal()
                        == ProductionDrainageAudit.Terminal.BROKEN_JOIN,
                "JOIN无下游段不能当汇流成功", "漏检");
    }

    private static ProductionDrainageAudit.Segment segment(ProductionDrainageAudit.SegmentId id,
            DeterministicSkeleton.End end, int downstream, int lake) {
        var point = new ProductionDrainageAudit.Point(0, 0);
        return new ProductionDrainageAudit.Segment(id, point, point, end, false, downstream, lake, false, 1);
    }

    private static ProductionDrainageAudit.Lake lake(int id, int downstream, double outflow) {
        var point = new ProductionDrainageAudit.Point(0, 0);
        return new ProductionDrainageAudit.Lake(id, point, point, 10, 10,
                1 + outflow, 1, outflow, false, downstream);
    }

    private static void secLazy() throws Exception {
        log("\n=== [lazy] 实际窗口之外零 fine、边界舍入一致、并发延迟求解单飞 ===");
        PROV.clear();
        var key = new SharedLakeDomain.Key(1, -512, -3);
        long fine0 = HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get();
        var d = PROV.preparedDomainForProbe(key);
        chk(d.status() == SharedLakeDomain.Status.PENDING, "真实候选完成粗准备", d.label());
        chk(HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get() == fine0,
                "粗准备不跑共享 fine", "fine 增加");
        chk(!d.intersectsBox(-864, -592, -849, -577)
                        && HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get() == fine0,
                "只落在最大候选范围但不在实际窗的 chunk 不触发 fine", "提前细解");
        var g = d.geom();
        int lo = g.originX() - g.step() / 2;
        int hi = g.originX() + g.n() * g.step() - g.step() / 2 - 1;
        chk(d.cellAt(lo, g.centerZ()) >= 0 && d.cellAt(lo - 1, g.centerZ()) < 0
                        && d.cellAt(hi, g.centerZ()) >= 0 && d.cellAt(hi + 1, g.centerZ()) < 0,
                "实际窗口相交判据与 cellAt 最近格舍入边界一致", "边界差一格");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<SharedLakeDomain.Domain>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> PROV.domainForProbe(key)));
            var first = futures.get(0).get(600, TimeUnit.SECONDS);
            boolean same = true;
            for (var f : futures) same &= f.get(600, TimeUnit.SECONDS) == first;
            chk(same && HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get() - fine0 == 1,
                    "冷共享域并发只解一次且复用同产物", "重复细解或不同产物");
        } finally { pool.shutdownNow(); }
    }

    // #region agent log: L1/L2 共享冷区 matched runs，实机确认后清理
    private static void secCost(long seed, boolean off, int cx, int cz) throws Exception {
        com.geogenesis.diagnostics.WorldGenProfiler.configure(true, 0);
        GEN = newGen(TP, seed);
        PROV = new ModelHydrologyProvider(GEN, seed);
        PROV.clear();
        HierarchicalHydrology.Diag.reset();
        ModelHydrologyProvider.setProbeSharedOff(off);
        String cold = null;
        for (String phase : List.of("cold", "hot")) {
            long t0 = System.nanoTime();
            String result = fp(PROV.columnsFor(cx, cz, sampleChunk(GEN, cx, cz)));
            String line = "shared=" + !off + " phase=" + phase + " chunk=" + cx + "," + cz
                    + " ms=" + (System.nanoTime() - t0) / 1e6
                    + " tiles=" + GEN.erosionTileCacheSize() + " misses=" + GEN.tileCacheStats().misses()
                    + " evictions=" + GEN.tileCacheStats().evictions()
                    + " localFine=" + HierarchicalHydrology.Diag.FINE_SOLVES.get()
                    + " sharedFine=" + HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.get()
                    + " domains=" + HierarchicalHydrology.Diag.SHARED_DOMAINS.get()
                    + " screens=" + HierarchicalHydrology.Diag.SHARED_COARSE_SCREENS.get();
            log(line);
            com.geogenesis.diagnostics.WorldGenProfiler.stutterLog("L1", "shared-cost-" + phase, line);
            if (cold == null) cold = result;
            else chk(cold.equals(result), "冷/热列指纹逐位相同", "有差异");
            Files.writeString(OUT.resolve("cost-" + (off ? "off" : "on") + "-" + cx + "-" + cz
                    + "-" + phase + "-columns.txt"), result);
        }
        com.geogenesis.diagnostics.WorldGenProfiler.reportNow("shared-cost");
        if (!off) {
            for (var key : SharedLakeDomain.keysForBox(cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15, hp())) {
                var d = PROV.preparedDomainForProbe(key);
                log("candidate " + d.label() + " geom=" + d.geom());
            }
        }
        log(HierarchicalHydrology.Diag.report());
        Files.writeString(OUT.resolve("cost-" + (off ? "off" : "on") + "-" + cx + "-" + cz
                + "-" + System.currentTimeMillis() + ".txt"), LOG.toString());
        ModelHydrologyProvider.setProbeSharedOff(false);
        com.geogenesis.diagnostics.WorldGenProfiler.configure(false, 0);
    }
    // #endregion

    // ---- 收尾 -------------------------------------------------------------------
    private static void notVerified() {
        log("\n=== NOT VERIFIED / 残余（明确登记，不声称为已证）===");
        log(" 1. 只证【缝两侧一致 + 缝带湿区连通】；不证全局唯一水位，不声称【整湖已修】——"
                + "受控范围只覆盖 canonical 跨缝盆地足迹，缝可能只是【移到】足迹边缘。");
        log(" 2. 未做全图像素对照 / 端到端实机确认（由 main 在生产落地后跑真实 + 渲染）。");
        log(" 3. 无 Forge 生命周期；配置取用户 toml 工作副本（原件只读，已 cfg.load）。");
        log(" 4. 探针只读缓存、收尾 clearSharedCaches；不改生产产出，不画掩码补桥。");
        log(" 5. 残余（若 [repro] 报『缝上切开行>0』或『足迹内残余分段』）：如实报告，不宽松为『全 passed』。");
        log(" 6. 世界链分支（shape-audit / trace-loops / rolling）只证【实际边↔reach 一一对应、发布边等于真实"
                + " canonical routing 后继、无重复/无环、上坡仅限已发布盆的盆内蓄水路由且该跳整数列 crest ≤ sill、"
                + "成员盆唯一 identity+lip、water() 全部格 known 且达门限且盆同形】，并用【独立重建】的 "
                + "WorldBasinRouting/WorldBasinFlow 复核；raw D8 后继只作参考。"
                + "★ 盆内蓄水路由已批准 ⇒ 原『raw D8 严格床面下降』复核已不适用，"
                + "但本探针**不**声称水面梯度已验证、**不**证全局唯一水位、**不**把 BASIN_PENDING 当完成；"
                + "rolling 起点仅【如实登记】originAdmitted，不强制仍被生产源政策准入。");
        log(" 7. windowBuild 重建仅在 worldForProbe==null 时仍是判据（旧链）；世界链下它**不再是生产 oracle**。");
        log(" 8. 负控（shape-audit）：删除一条实际边 / 把一条实际边改向到另一格点邻居，"
                + "边↔reach 一一对应或 canonical 后继判据必须失败（见 G31 WORLD_NEG_CONTROL[_REDIRECT]）；"
                + "无边区如实 SKIPPED，不可判定时如实 SKIPPED_UNDECIDABLE —— 都不假 PASS 也不制造 FAIL。");
        log(" 9. 水量的『自洽自检』不是独立水量 oracle：覆盖 water() 全部格（不只发布边 from）。"
                + "口径：每格必须 known（UNKNOWN 计缺陷、不跳过）；【AT_LEAST 必须达本次需求 threshold】；"
                + "【EXACT 低于 threshold 是合法的干格/低流量，只统计不算缺陷】；"
                + "湖的盆身份由坐标上独立 routing.at() 核对（不拿 Water 自称当 oracle）；"
                + "每条发布边 from 必须存在【且达 threshold，EXACT 也一样】。"
                + "「非 EXACT 不报有限 discharge()」恒真 ⇒ 只统计 waterContractLeak，不单独占 PASS。"
                + "不证水量守恒、不证水位正确。");
        log("10. crest 判据是【整数列采样】（worldCrest，同 STEP 全列取 max），与生产 crest 同式但也只是采样："
                + "step>1 时列间亚格地形未采样，故它不构成连续地形上的严格证明。");
        log("11. 水量口径本身另有【合成负控】（worldWaterGaugeSynthetic，见 G31 WORLD_WATER_GAUGE）："
                + "EXACT 低 T 应过 / UNKNOWN 全量应失败 / 非 EXACT 低 T 失败 / 发布低 T 失败 / 湖身份不符失败。"
                + "它只证「口径按预期判」，不是生产数据证据。");
    }

    private static void emit() throws java.io.IOException {
        log("\n判定：" + (failures == 0 ? "ALL PASS" : "★ FAIL（fail=" + failures + "）"));
        Files.createDirectories(OUT);
        Path out = OUT.resolve("metrics.txt");
        Files.writeString(out, LOG.toString());
        System.out.print(LOG);
        System.out.println("FILE=" + out.toAbsolutePath());
    }

    private static void chk(boolean ok, String name, String detail) {
        if (ok) { log("  [PASS] " + name); return; }
        failures++;
        log("  [FAIL] " + name + " —— " + detail);
    }

    private static void log(String s) { LOG.append(s).append('\n'); }
}
