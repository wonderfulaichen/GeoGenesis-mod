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
            Set.of("synth", "repro", "basin", "det", "fine", "counts", "neg", "cost-on", "cost-off", "lazy", "river", "river-candidates", "contract", "contract-baseline", "boundary", "boundary-local", "local-synth", "closure", "local-view", "outer", "outer-view", "shore", "shore-view", "cell-contract", "cell-view", "block-synth", "block-boundary", "block-view", "transfer-synth", "transfer-view", "summary-synth", "summary-view");
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
                || want.contains("block-boundary") || want.contains("block-view"))
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
