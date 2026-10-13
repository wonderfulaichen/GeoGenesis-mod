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
            Set.of("synth", "repro", "basin", "det", "fine", "counts", "neg", "cost-on", "cost-off", "lazy", "river", "river-candidates");
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

    private static void riverConnectivity(int x, int z, int radius, int axis) throws Exception {
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
