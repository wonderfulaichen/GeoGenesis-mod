package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

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
 * <p><b>NOT VERIFIED / 残余</b>：①只证缝两侧一致与缝带连通，<b>不</b>证全局唯一水位、<b>不</b>声称整湖已修
 * （受控范围只覆盖 canonical 跨缝盆地足迹，缝可能只是【移到】足迹边缘）；②未做全图像素对照/实机；③无 Forge
 * ⇒ 配置取用户 toml <b>工作副本</b>（原件只读）；④探针只读缓存且收尾清空，不改生产产出。</p>
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
            Set.of("synth", "repro", "basin", "det", "fine", "counts", "neg", "cost-on", "cost-off", "lazy", "river", "river-candidates", "contract", "contract-baseline", "boundary", "boundary-local", "local-synth", "closure", "local-view", "outer", "outer-view", "shore", "shore-view", "cell-contract", "cell-view", "block-synth", "block-boundary", "block-view", "transfer-synth", "transfer-view", "summary-synth", "summary-view", "direction-synth", "direction-view", "sink-synth", "sink-view", "channel-origin", "common-acceptance", "erosion-blend", "source-synth", "source-directory", "owner-sources", "owner-source-acceptance");
    static final Path OUT = Path.of("build", "shared-lake-seam");

    static int failures;
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
        Set<String> want = new LinkedHashSet<>();
        if (args.length > 1) for (String s : args[1].split(",")) if (!s.isBlank()) want.add(s.trim());
        boolean all = want.isEmpty();
        if (want.contains("contract") || want.contains("contract-baseline")
                || want.contains("boundary") || want.contains("boundary-local")
                || want.contains("outer") || want.contains("outer-view")
                || want.contains("shore") || want.contains("shore-view") || want.contains("cell-view")
                || want.contains("block-boundary") || want.contains("block-view") || want.contains("direction-view") || want.contains("sink-view"))
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
        if (want.contains("source-synth")) sourceDirectorySynthetic();
        if (want.contains("source-directory")) secSourceDirectory();
        if (want.contains("owner-sources") || want.contains("owner-source-acceptance"))
            secOwnerSources(seed, want.contains("owner-source-acceptance"));
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
        chk(wet.contains(start) && !seen.isEmpty(), "缝带连通诊断非空转 " + x + "," + z, "起点非河");
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
        var method = DeterministicSkeleton.class.getDeclaredMethod("collectSources", double[].class, int[].class,
                boolean[].class, boolean[].class, boolean[].class, double[].class, int.class, int.class,
                DeterministicSkeleton.Params.class);
        method.setAccessible(true);
        var params = new DeterministicSkeleton.Params(0, threshold, 0, 0, 24, 0, 2, 0, 0);
        @SuppressWarnings("unchecked")
        var cells = (List<Integer>) method.invoke(null, p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.nx(), p.nz(), params);
        return new TreeSet<>(cells);
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
