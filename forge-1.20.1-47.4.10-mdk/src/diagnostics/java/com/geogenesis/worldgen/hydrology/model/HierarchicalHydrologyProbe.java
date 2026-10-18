package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.ConfigSafe;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分层水文（{@link HierarchicalHydrology}）受控验证探针 —— 第一版评估（仅核心契约/确定性）。
 * <pre>{@code gradlew runHierarchicalHydrologyProbe [-PprobeArgs="<mode> [seed] [regions]"]}</pre>
 * mode: {@code synthetic}（默认，廉价：合成负例 + 审计 + 小 tract 粗层 + provider 冷热/逆序/并发/指纹/扇出）；
 * {@code real}（固定 regions 走生产入口 {@code columnsFor}；<b>显式</b>放宽严格契约以取<b>完整</b> metrics，
 * 但结论仍由计数判 FAIL —— 放宽 ≠ 通过；冲突类异常仍被捕获）；{@code default-off}（只跑与开关无关的段）。
 * <p>三条判据线绝不互相掩盖：<b>runtime</b>／<b>contract</b>／<b>unclassified</b>（归因未验证的确定性失败）。
 * 汇总区分 {@code runtime checks PASSED} 与 {@code ACCEPTANCE FAIL}。开关仅本进程注入；引擎构造前把用户 toml
 * <b>复制为工作副本</b>（{@code build/hierarchical-evaluation/config-probe.toml}）并 {@code cfg.load()} 后
 * {@code setConfig(工作副本)}（<b>不碰用户原件</b>），且 {@code newGen} 的 base 取
 * {@code GeoGenesisConfig.INSTANCE.buildParams()}；缺失或 load 失败一律 <b>runtime FAIL</b>，绝不回退后
 * 还声称"与用户一致"。</p>
 * <p>{@code structural} 模式 = synthetic 但<b>跳过侵蚀确定性测试</b>（仅结构性 gate，不制造"总通过"）。</p>
 */
public final class HierarchicalHydrologyProbe {
    private static int rtF, ctF, cdF;
    private static boolean strictOk, realBoundary, synthBoundaryProven, blockedByContract, configResolved;
    private static final StringBuilder LOG = new StringBuilder();
    private static final long T0 = System.nanoTime();
    private static final LakeWaterBalance.Climate CLIMATE = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
    private static final int AUDIT_MS = 5_000;
    private static final long SEED = 9139912035078620160L;
    private static final Path OUT_DIR = Path.of("build", "hierarchical-evaluation");
    private HierarchicalHydrologyProbe() { }
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "synthetic";
        Long seedArg = null;
        String regionArg = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i].trim();
            if (a.matches("\\d+")) seedArg = Long.parseLong(a);
            else if (a.contains(":") || a.matches("[\\d,;:()\\-\\s]+")) regionArg = a;
        }
        long seed = seedArg != null ? seedArg : SEED;
        List<int[]> regions = parseRegions(regionArg != null ? regionArg : "-1:-1,-1:0,0:0,0:-1");
        loadRealConfig();                 // ★ 构造引擎前把用户 toml 复制为工作副本并 load
        injectProbeProperties(mode);      // 开关仅本进程注入
        header(mode, seed, regions);
        secOwner();
        secValidate();
        secAudit();
        secCoarse(seed);
        boolean structural = "structural".equals(mode);
        if (structural) log("\n=== [5] 侵蚀确定性：structural 模式跳过（仅结构性 gate，不制造总通过）===");
        else secErosion(seed);
        boolean off = "default-off".equals(mode);
        chk('R', ModelHydrologyProvider.hierarchical() == !off, "分层开关状态符合模式（本进程注入，未改默认）", "hierarchical=" + ModelHydrologyProvider.hierarchical());
        if (off) log("  （default-off：跳过 provider 分层段与真实 region 段）");
        else {
            secProvider(seed);
            if ("real".equals(mode)) secReal(seed, regions);
            else log("\n=== [7] 真实 region：本模式跳过（不跑大型全图）===");
        }
        notVerified(mode);
        emit(mode);
        System.exit(rtF + ctF + cdF == 0 ? 0 : 1);
    }
    /**
     * 把用户 toml 复制为工作副本并加载到 {@code GeoGenesisConfig.SPEC}（引擎才能读到真值）。
     * <b>不修改用户原件</b>（NightConfig 关闭时可能回写源文件，故只对副本操作）。
     * 缺失 / 复制失败 / load 失败 ⇒ {@code configResolved=false}，由 {@link #header} 判 runtime FAIL。
     */
    private static void loadRealConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) { log("[cfg] " + src + " 不存在 ⇒ 无法复现用户配置"); return; }
        Path work = OUT_DIR.resolve("config-probe.toml");
        try {
            Files.createDirectories(OUT_DIR);
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();                                   // ★ 必须显式 load 才有真值
            GeoGenesisConfig.SPEC.setConfig(cfg);
            configResolved = true;
        } catch (Throwable e) {
            log("[cfg] 工作副本加载失败（不 fallback 声称一致）: " + e);
        }
    }
    private static void injectProbeProperties(String mode) {
        System.setProperty("geogenesis.hierarchicalHydrology", "default-off".equals(mode) ? "false" : "true");
        System.setProperty("geogenesis.skeletonTrace", "true");
        System.setProperty("geogenesis.hierStrictContract", "real".equals(mode) ? "false" : "true");
    }
    private static void log(String s) { LOG.append(s).append('\n'); }
    /** kind: 'R' runtime / 'C' contract / 'D' unclassified（归因未验证的确定性失败，单列，绝不混桶）。 */
    private static void chk(char kind, boolean ok, String name, String detail) {
        if (ok) { log("  [PASS] " + name); return; }
        if (kind == 'R') { rtF++; log("  ★[RUNTIME FAIL] " + name + " —— " + detail); }
        else if (kind == 'C') { ctF++; log("  ★[CONTRACT FAIL] " + name + " —— " + detail); }
        else { cdF++; log("  ⚠[UNCLASSIFIED DETERMINISM FAILURE] " + name + " —— " + detail); }
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static boolean bitsEq(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static CellGenerator newGen(long seed) { return newGen(seed, params().horizontalScale()); }
    /**
     * 引擎构造：base = {@code GeoGenesisConfig.INSTANCE.buildParams()}（已加载工作副本则=用户配置），
     * 仅覆盖 horizontalScale（指纹对照用），其余字段一律取自 buildParams，<b>不使用 TerrainParams.defaults()</b>。
     */
    private static CellGenerator newGen(long seed, double hs) {
        try {
            TerrainParams base = params();
            var comps = TerrainParams.class.getRecordComponents();
            Object[] vals = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) vals[i] = comps[i].getName().equals("horizontalScale") ? hs : comps[i].getAccessor().invoke(base);
            TerrainParams tp = (TerrainParams) TerrainParams.class.getConstructors()[0].newInstance(vals);
            CellGenerator g = new CellGenerator(tp, tp.minY(), tp.maxY());
            g.seed(seed);
            return g;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static boolean bpFallbackLogged;
    /**
     * {@code buildParams()} 的安全包装：配置未解析时 {@code ConfigValue.get()} 会抛
     * IllegalStateException。此处仅在【已记录 runtime FAIL（configResolved=false）】时退回 defaults 兜底
     * 以免探针崩溃 —— <b>绝不</b>据此声称"已复现用户配置"。
     */
    private static TerrainParams params() {
        try { return GeoGenesisConfig.INSTANCE.buildParams(); }
        catch (Throwable e) {
            if (!bpFallbackLogged) {
                bpFallbackLogged = true;
                log("[cfg] buildParams 失败（配置未解析）⇒ 结构性段用 defaults 兜底；已计 runtime FAIL，不声称一致: " + e);
            }
            return TerrainParams.defaults();
        }
    }
    private static List<int[]> parseRegions(String csv) {
        List<int[]> out = new ArrayList<>();
        for (String one : csv.split("[,;]")) {
            String[] xy = one.trim().split(":");
            if (xy.length != 2) continue;
            try { out.add(new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())}); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }
    private static void header(String mode, long seed, List<int[]> regions) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : regions) sb.append('(').append(r[0]).append(',').append(r[1]).append(')');
        log("=== HierarchicalHydrologyProbe ===");
        log("mode=" + mode + " seed=" + seed + " regions=" + sb);
        log(String.format("开关: hierarchical=%b skeletonTrace=%b strict(real=false/%b) networkFilter生产默认=%b（不改默认）", ModelHydrologyProvider.hierarchical(), ModelHydrologyProvider.SKELETON_TRACE, "synthetic".equals(mode), DeterministicSkeleton.NETWORK_FILTER));
        HierarchicalHydrology.Params hp = ModelHydrologyProvider.hierParams();
        log(String.format("分层参数: tract=%d halo=%d step=%d coarseHalo=%d fineRadius=%d coarseRadius=%d", hp.tract(), hp.halo(), hp.step(), hp.coarseHalo(), hp.fineRadius(), hp.coarseRadius()));
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        String crash = "（configResolved=" + configResolved + "）";
        // ★ 配置未解析 ⇒ runtime FAIL：禁止"回退默认"还声称与用户一致（未复现即未复现）。
        chk('R', configResolved, "用户 toml 已复制为工作副本并 cfg.load() 生效（不回退却声称一致）", crash);
        TerrainParams bp = params();
        log(String.format("config 生效(base=buildParams,toml@%s): horizontalScale=%.3f seaLevel=%d erosionXS=%b ridge=%b", configResolved ? "工作副本" : "未解析", bp.horizontalScale(), bp.seaLevel(), ConfigSafe.bool(c.erosionXSEnabled, false), ConfigSafe.bool(c.erosionRidgeEnabled, false)));
    }
    // [1] owner 算术（负坐标 / floorDiv 牙齿）
    private static void secOwner() {
        log("\n=== [1] owner 算术（负坐标 / floorDiv）===");
        int t = 512;
        chk('R', owner(0, t) == 0 && owner(511, t) == 0, "owner(0..511)=0", "越界");
        chk('R', owner(512, t) == 1 && owner(1023, t) == 1, "owner(512..1023)=1", "越界");
        chk('R', owner(-1, t) == -1 && owner(-512, t) == -1 && owner(-513, t) == -2, "owner(-1)=-1 / (-512)=-1 / (-513)=-2", "得 " + owner(-1, t) + "/" + owner(-512, t) + "/" + owner(-513, t));
        chk('R', owner(-620, t) == -2 && owner(40, t) == 0, "owner(-620,40)=(-2,0)（混合符号 floorDiv）", "得 (" + owner(-620, t) + "," + owner(40, t) + ")");
        chk('R', (-1 / t) == 0 && (-620 / t) == -1 && owner(-620, t) == -2, "反证：截断除与 floorDiv 不同（选错即 FAIL）", "截断(-1)=" + (-1 / t) + " 截断(-620)=" + (-620 / t));
        HierarchicalHydrology.Coarse c = new HierarchicalHydrology.Coarse(0, 0, 5, 4, -8, -8, fake(5, 4, 10.0, 25, neg(25), new int[]{-1}), List.of(), List.of(), List.of(), 0L);
        chk('R', c.contains(-8, -8) && c.contains(8, 8), "contains 闭开区间：(-8,-8)/(8,8) 在内", "越界");
        chk('R', !c.contains(-9, -8) && !c.contains(12, 8), "contains 越界：(-9,-8)/(12,8) 不在内", "越界");
        chk('R', c.cellAt(-8, -8) == 0 && c.cellAt(8, 8) == 24 && c.cellAt(-9, -8) == -1, "cellAt：原点=0 / 末格心=24 / 域外=-1", "得 " + c.cellAt(-8, -8) + "/" + c.cellAt(8, 8) + "/" + c.cellAt(-9, -8));
        chk('R', Double.isNaN(c.levelAt(999, 999)), "域外水位=NaN（不伪装）", "得 " + c.levelAt(999, 999));
    }
    private static int owner(int block, int tract) { return Math.floorDiv(block, tract); }
    // [2] 合成 MorphologyComparison：形态差异不 hardfail；仅【非有限 bed/level / outlet 越域索引】才真实失败
    private record Outcome(boolean threw, HierarchicalHydrology.ContractViolation ex,
                           HierarchicalHydrology.MorphologyComparison v, String counts, long moved) {
        boolean bad() { return threw || (v != null && !v.contractSatisfied()); }
        String detail() { return "threw=" + threw + (ex == null ? "" : " ex=" + ex.getMessage()) + " counts=" + counts; }
    }
    private static void secValidate() {
        log("\n=== [2] 合成 validate（形态=诊断；非有限/越域 才硬失败）===");
        HierarchicalHydrology.Params p = HierarchicalHydrology.Params.defaults();
        // 形态差异（MOVED / LEVEL / TRUNCATED / MISSING）必须【不】判失败、不抛，并如实计数。
        Outcome trunc = runCase(p, ba("8,8,8,8,100,false,true"));
        strictOk = !trunc.threw();
        log("  严格模式=默认 true，但形态差异（含截断/移动/水位差/候选消失）在严格模式下【同样不抛】；strictOk=" + strictOk);
        chk('C', !trunc.bad() && trunc.v() != null && trunc.v().truncated() == 1, "TRUNCATED(采样上下文) ⇒ 形态诊断记 1，不 hardfail", trunc.detail());
        chk('C', trunc.counts().contains("truncatedCtx="), "TRUNCATED 计数键 truncatedCtx 如实出现", trunc.counts());
        chk('C', HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get() == 0, "形态差异【不】计入 CONTRACT_UNSATISFIED", "n=" + HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get());
        chk('C', trunc.v() != null && trunc.v().contractValid(), "TRUNCATED 的 contractValid 仍 true（形态≠契约）", trunc.detail());
        for (String[] b : new String[][]{{"8,8,20,20,150,false,false", "levelDiff=", "LEVEL(水位差 50)"}, {"400,400,8,8,100,false,false", "evolvedOut=", "MISSING/EVOLVED(种子在窗外)"}}) {
            Outcome o = runCase(p, ba(b[0]));
            chk('C', !o.bad() && o.v() != null && o.counts().contains(b[1]), b[2] + " ⇒ 形态诊断，不 hardfail", o.detail());
        }
        // 出口移动（含边界环候选）⇒ 只记 OUTLET_MOVED，绝不 fail。
        Outcome moved = runCase(p, ba("8,8,40,40,100,true,false"));
        chk('C', !moved.bad() && moved.v() != null && moved.v().movedExterior() == 1, "MOVED(含边界环) ⇒ 认账且不 fail（不得误杀）", moved.detail());
        chk('C', moved.moved() == 1 && HierarchicalHydrology.Diag.OUTLET_MOVED.get() == 1, "MOVED 计入 OUTLET_MOVED=1（不得 fail 它）", "diag=" + moved.moved());
        Outcome match = runCase(p, ba("8,8,8,8,100,false,false"));
        chk('C', !match.bad() && match.v() != null && match.v().matched() == 1, "MATCHED(出口一致) ⇒ 通过", match.detail());
        // 别区 halo 盆地：owner 非本 tract ⇒ FOREIGN 跳过，不对比不失败。
        Outcome foreign = runCase(p, ba("600,600,604,604,100,false,false"));
        chk('C', !foreign.bad() && foreign.v() != null && foreign.v().foreign() == 1 && foreign.v().compared() == 0, "别区 halo 盆地 ⇒ FOREIGN 计数且 compared=0（跳过不判失败）", foreign.detail());
        // ★ 唯一真实负例：细层水位非有限 ⇒ contractValid=false 且严格模式抛带哨兵坐标异常。
        Outcome nonfinite = runCaseNonFinite(p);
        boolean strict = !"false".equalsIgnoreCase(System.getProperty("geogenesis.hierStrictContract", "true"));
        chk('C', nonfinite.bad() && (!strict || (nonfinite.threw() && nonfinite.ex().blockX == Integer.MIN_VALUE)), "细层水位非有限 ⇒ 必须判失败，严格模式必须抛异常", nonfinite.detail());
        chk('C', HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get() >= 1, "真实冲突计入 CONTRACT_UNSATISFIED", "n=" + HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get());
        // 唯一另一类真实负例：细层 outlet 单元格索引越域 ⇒ contractValid=false。
        Outcome ood = runCaseOutOfDomain(p);
        chk('C', ood.bad() && (ood.threw() || !ood.v().contractValid()), "细层 outlet 越域索引 ⇒ 真实冲突（contractValid=false）", ood.detail());
    }
    /** "seedX,seedZ,outX,outZ,level,outletOnRing,truncated" → BasinAuthority。 */
    private static HierarchicalHydrology.BasinAuthority ba(String spec) {
        String[] a = spec.split(",");
        return new HierarchicalHydrology.BasinAuthority(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Double.parseDouble(a[4]), a[5].equals("true"), a[6].equals("true"));
    }
    /** 5×5 格、step=4、原点 (0,0)、盆地 0 的种子格在 (2,2)；细层出口恒为格(2,2)→(8,8)。 */
    private static Outcome runCase(HierarchicalHydrology.Params p, HierarchicalHydrology.BasinAuthority ba) {
        int[] basinId = neg(25);
        basinId[12] = 0;
        HydrologyPipeline.Result fine = fake(5, 4, 100.0, 25, basinId, new int[]{12});
        return run(p, ba, fine);
    }
    /** 负例：细层 bed 非有限 ⇒ contractValid=false（严格模式抛）。 */
    private static Outcome runCaseNonFinite(HierarchicalHydrology.Params p) {
        int[] basinId = neg(25);
        basinId[12] = 0;
        HydrologyPipeline.Result fine = fake(5, 4, Double.NaN, 25, basinId, new int[]{12});
        return run(p, ba("8,8,8,8,100,false,false"), fine);
    }
    /** 负例：细层 outlet 单元格索引越域（>= n*n）⇒ contractValid=false。 */
    private static Outcome runCaseOutOfDomain(HierarchicalHydrology.Params p) {
        int[] basinId = neg(25);
        basinId[12] = 0;
        HydrologyPipeline.Result fine = fake(5, 4, 100.0, 25, basinId, new int[]{9999});
        return run(p, ba("8,8,8,8,100,false,false"), fine);
    }
    private static Outcome run(HierarchicalHydrology.Params p, HierarchicalHydrology.BasinAuthority ba, HydrologyPipeline.Result fine) {
        HierarchicalHydrology.Coarse ct = new HierarchicalHydrology.Coarse(0, 0, 5, 4, 0, 0, fine, List.of(), List.of(ba), List.of(), 0L);
        HierarchicalHydrology.Diag.reset();
        boolean threw = false;
        HierarchicalHydrology.ContractViolation ex = null;
        HierarchicalHydrology.MorphologyComparison v = null;
        try { v = HierarchicalHydrology.validate(p, ct, fine, 0, 0, 0, 0); }
        catch (HierarchicalHydrology.ContractViolation e) { threw = true; ex = e; }
        List<String> s = HierarchicalHydrology.Diag.VALIDATION_SAMPLES;
        String counts;
        synchronized (s) { counts = s.isEmpty() ? "-" : s.get(s.size() - 1); }
        return new Outcome(threw, ex, v, counts, HierarchicalHydrology.Diag.OUTLET_MOVED.get());
    }
    // [3] 合成连通性审计（环 / 孤立 / 边框未验证 + 有界终止）
    private interface Audit { void accept(HierarchicalHydrology.Connectivity c); }
    private static void secAudit() {
        log("\n=== [3] 合成连通性审计（环/孤立/边框未验证 + 有界终止）===");
        int n = 7;
        audit("chain→sea", chain(n, 0, n - 1, 0), c -> {
            chk('R', !c.hardFail() && c.fullyVerified() && c.channelCells() == n, "chain→sea：无孤立无环无边框未验证 ⇒ fullyVerified", dump(c));
            chk('R', c.orphanCells() == 0 && c.loopCells() == 0 && c.boundaryUnverifiedCells() == 0, "chain→sea：三项计数全 0（判据非空转）", dump(c));
        });
        audit("interior-orphan", chain(n, 1, 3, 1), c -> {
            chk('C', c.orphanCells() >= 1 && c.hardFail() && !c.fullyVerified(), "内陆 parent=-1 ⇒ 孤立【硬失败】", dump(c));
            chk('R', !c.samples().isEmpty(), "孤立根坐标如实列出", dump(c));
        });
        audit("frame-unverified", chain(n, 2, n - 1, 2), c -> {
            synthBoundaryProven = c.boundaryUnverifiedCells() > 0;
            chk('R', !c.hardFail() && !c.fullyVerified() && c.boundaryUnverifiedCells() >= 1, "走出窗口 ⇒ 边框未验证：非硬失败，但【不得】算连通通过", dump(c));
        });
        audit("self-loop", loop(n, new int[]{3 * n + 3, 3 * n + 3}, 0), c -> chk('C', c.loopCells() >= 1 && c.hardFail(), "自环 ⇒ 成环【硬失败】", dump(c)));
        audit("multi-cycle", loop(n, new int[]{2 * n + 2, 3 * n + 3, 4 * n + 2}, 0), c -> chk('C', c.loopCells() >= 1 && c.hardFail(), "多格互指环 ⇒ 成环【硬失败】", dump(c)));
        audit("big-ring", loop(30, null, 200), c -> chk('C', c.loopCells() >= 1 && c.hardFail() && c.channelCells() == 200, "200 格长环 ⇒ 有界判定为成环（不无限）", dump(c)));
        HydrologyPipeline.Result nullCh = new HydrologyPipeline.Result(n, n, 1, filled(n * n, 0), new boolean[n * n], tf(n * n), filled(n * n, 0), new double[n * n], new boolean[n * n], neg(n * n), new double[n * n], null, new double[n * n], null, null, null, filled(n * n, 0), 0, true, 0.0);
        audit("null-channel", nullCh, c -> chk('R', c.channelCells() == 0 && !c.hardFail(), "channel=null ⇒ 空结果（防御，不抛）", dump(c)));
    }
    /** 行 3 上 [startCol, tailCol] 的链；end 0=尾格入海 1=尾格内陆死端 2=尾格在右边框。 */
    private static HydrologyPipeline.Result chain(int n, int startCol, int tailCol, int end) {
        boolean[] ch = new boolean[n * n];
        int[] par = neg(n * n);
        boolean[] sea = new boolean[n * n];
        for (int j = startCol; j <= tailCol; j++) { ch[3 * n + j] = true; par[3 * n + j] = j < tailCol ? 3 * n + j + 1 : -1; }
        if (end == 0) sea[3 * n + tailCol] = true;
        return fabricate(n, ch, par, sea);
    }
    /** 一条闭合环（cells 互指成环）；cells=null ⇒ 取 [0, len) 连续格。 */
    private static HydrologyPipeline.Result loop(int n, int[] cells, int len) {
        boolean[] ch = new boolean[n * n];
        int[] par = neg(n * n);
        int m = cells != null ? cells.length : len;
        for (int i = 0; i < m; i++) {
            int c = cells != null ? cells[i] : i;
            ch[c] = true;
            par[c] = cells != null ? cells[i == m - 1 ? 0 : i + 1] : (i == m - 1 ? 0 : i + 1);
        }
        return fabricate(n, ch, par, new boolean[n * n]);
    }
    /** 审计用合成 Result（均匀 bed、给定 channel/parent/sea、无 basin）。 */
    private static HydrologyPipeline.Result fabricate(int n, boolean[] ch, int[] par, boolean[] sea) {
        double[] bed = filled(n * n, 10.0);
        return new HydrologyPipeline.Result(n, n, 1, bed, sea, tf(n * n), bed.clone(), new double[n * n], new boolean[n * n], par, new double[n * n], ch, new double[n * n], null, null, null, bed.clone(), 0, true, 0.0);
    }
    /** 在【有界线程】里跑审计：超时未返回即 runtime FAIL（"不可无限"的牙齿）。 */
    private static void audit(String name, HydrologyPipeline.Result fine, Audit check) {
        AtomicReference<HierarchicalHydrology.Connectivity> box = new AtomicReference<>();
        Thread t = new Thread(() -> box.set(HierarchicalHydrology.auditConnectivity(fine)), "probe-audit");
        t.setDaemon(true);
        long t0 = System.nanoTime();
        t.start();
        try { t.join(AUDIT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        double ms = (System.nanoTime() - t0) / 1e6;
        if (box.get() == null) { chk('R', false, name + " ⇒ 有界终止", "超时 " + AUDIT_MS + "ms 未返回"); return; }
        chk('R', ms < AUDIT_MS, name + " ⇒ 有界终止（" + String.format("%.1f", ms) + "ms）", "超时");
        check.accept(box.get());
    }
    private static String dump(HierarchicalHydrology.Connectivity c) {
        return String.format("河格=%d 孤立=%d 链=%d 边框未验证=%d 成环=%d hardFail=%b fullyVerified=%b", c.channelCells(), c.orphanCells(), c.orphanChains(), c.boundaryUnverifiedCells(), c.loopCells(), c.hardFail(), c.fullyVerified());
    }
    /** 均匀 bed 的合成 Result（channel/parent/sea 空；basinId/outlet 由调用方给）。 */
    private static HydrologyPipeline.Result fake(int n, int step, double bedV, int cells, int[] basinId, int[] outlet) {
        double[] bed = filled(cells, bedV);
        LakeWaterBalance.Result lr = new LakeWaterBalance.Result(new double[cells], new boolean[cells], List.of(), 0, 0.0, Map.of(), Map.of(), basinId, outlet);
        return new HydrologyPipeline.Result(n, n, step, bed, new boolean[cells], tf(cells), bed.clone(), new double[cells], new boolean[cells], neg(cells), new double[cells], new boolean[cells], new double[cells], lr, null, null, bed.clone(), 0, true, 0.0);
    }
    private static double[] filled(int n, double v) { double[] a = new double[n]; Arrays.fill(a, v); return a; }
    private static boolean[] tf(int n) { boolean[] a = new boolean[n]; Arrays.fill(a, true); return a; }
    private static int[] neg(int n) { int[] a = new int[n]; Arrays.fill(a, -1); return a; }
    // [4] 小 tract 粗层：零 tile + 逐位确定 + 顺序无关 + 同种子两引擎 + 端口合法
    private static void secCoarse(long seed) {
        log("\n=== [4] 小 tract 粗层（零 tile / 逐位确定 / 顺序无关 / 端口合法）===");
        HierarchicalHydrology.Params sp = HierarchicalHydrology.Params.of(64, 8, 2, 12);
        CellGenerator gen = newGen(seed);
        awaitPreheatIdle(10_000);
        int cache0 = gen.erosionTileCacheSize();
        long miss0 = gen.tileCacheStats().misses(), hit0 = gen.tileCacheStats().hits();
        HierarchicalHydrology.Diag.reset();
        HierarchicalHydrology.Coarse a = HierarchicalHydrology.buildCoarse(gen, sp, 3, -2, CLIMATE);
        chk('R', gen.tileCacheStats().misses() == miss0, "粗层零侵蚀 tile 生成（misses 不变）", miss0 + "→" + gen.tileCacheStats().misses());
        chk('R', gen.erosionTileCacheSize() == cache0, "粗层零侵蚀 tile 入缓存", cache0 + "→" + gen.erosionTileCacheSize());
        chk('R', gen.tileCacheStats().hits() == hit0, "粗层未走探测式读 tile（hits 不变）", hit0 + "→" + gen.tileCacheStats().hits());
        chk('R', HierarchicalHydrology.Diag.COARSE_SAMPLES.get() > 0, "粗层走 terrainEQuick→heightFromE（采样>0）", "samples=" + HierarchicalHydrology.Diag.COARSE_SAMPLES.get());
        chk('R', readLayer("coarse/prewarmSpan", -1L) == 0L && HydrologyPipeline.lastRunStats().prewarmSpanBlocks == 0, "粗层预热跨度=0（零 tile 铁律）", "LAYER=" + readLayer("coarse/prewarmSpan", -1L));
        chk('R', "coarse".equals(HydrologyPipeline.lastRunStats().layerTag), "粗层标记 layerTag=coarse", "tag=" + HydrologyPipeline.lastRunStats().layerTag);
        chk('R', a.solveMillis() >= 0 && HierarchicalHydrology.Diag.COARSE_SOLVES.get() == 1, "粗层解计数=1 且耗时可用", "solves=" + HierarchicalHydrology.Diag.COARSE_SOLVES.get());
        log("  采样/耗时: coarse 采样=" + HierarchicalHydrology.Diag.COARSE_SAMPLES.get() + " 网格格=" + a.n() * a.n() + " solveMs=" + a.solveMillis() + " ports=" + a.ports().size() + " trunkSeeds=" + a.trunkSeeds().size() + " tile查询未命中(非唯一生成数)=" + gen.tileCacheStats().misses());
        portsValid(a, sp);
        acrossOrders(gen, sp);
        acrossEngines(seed, sp);
    }
    /** 端口只来自有效主干：有限水位、区内、指向区外、非湖海、channel=true。 */
    private static void portsValid(HierarchicalHydrology.Coarse a, HierarchicalHydrology.Params sp) {
        int loX = a.rx() * sp.tract(), loZ = a.rz() * sp.tract();
        int hiX = loX + sp.tract(), hiZ = loZ + sp.tract();
        int bad = 0;
        HydrologyPipeline.Result pi = a.pipe();
        for (HierarchicalHydrology.Port p : a.ports()) {
            boolean inside = p.blockX() >= loX && p.blockX() < hiX && p.blockZ() >= loZ && p.blockZ() < hiZ;
            int k = a.cellAt(p.blockX(), p.blockZ());
            boolean water = k >= 0 && ((pi.lake() != null && pi.lake()[k]) || (pi.sea() != null && pi.sea()[k]));
            boolean chan = k >= 0 && pi.channel() != null && pi.channel()[k];
            if (!Double.isFinite(p.level()) || !inside || k < 0 || (p.dRX() == 0 && p.dRZ() == 0) || water || !chan) bad++;
        }
        chk('R', bad == 0, "端口仅来自有效主干（有限水位/区内/指向区外/非湖海/channel）", "ports=" + a.ports().size() + " 违规=" + bad);
    }
    private static void acrossOrders(CellGenerator gen, HierarchicalHydrology.Params sp) {
        int[][] fr = {{4, 4}, {4, 5}, {5, 4}};
        HierarchicalHydrology.Coarse[] fwd = new HierarchicalHydrology.Coarse[fr.length];
        for (int i = 0; i < fr.length; i++) fwd[i] = HierarchicalHydrology.buildCoarse(gen, sp, fr[i][0], fr[i][1], CLIMATE);
        boolean same = true;
        StringBuilder why = new StringBuilder();
        for (int i = fr.length - 1; i >= 0; i--) {
            if (!pipeEq(fwd[i].pipe(), HierarchicalHydrology.buildCoarse(gen, sp, fr[i][0], fr[i][1], CLIMATE).pipe())) {
                same = false;
                why.append('(').append(fr[i][0]).append(',').append(fr[i][1]).append(") ");
            }
        }
        chk('R', same, "顺序 vs 逆序：粗层产出逐位相同（含 9 数组）", "不一致 tract: " + why);
    }
    private static void acrossEngines(long seed, HierarchicalHydrology.Params sp) {
        chk('R', pipeEq(HierarchicalHydrology.buildCoarse(newGen(seed), sp, -1, -3, CLIMATE).pipe(), HierarchicalHydrology.buildCoarse(newGen(seed), sp, -1, -3, CLIMATE).pipe()), "同种子同参数、两个引擎实例 ⇒ 粗层逐位相同（冷对冷）", "有数组不同");
    }
    private static boolean pipeEq(HydrologyPipeline.Result x, HydrologyPipeline.Result y) {
        return Arrays.equals(x.bed(), y.bed()) && Arrays.equals(x.level(), y.level()) && Arrays.equals(x.parent(), y.parent()) && Arrays.equals(x.discharge(), y.discharge()) && Arrays.equals(x.channel(), y.channel()) && Arrays.equals(x.lake(), y.lake()) && Arrays.equals(x.sea(), y.sea()) && Arrays.equals(x.resolved(), y.resolved()) && Arrays.equals(x.waterSurface(), y.waterSurface());
    }
    private static long readLayer(String key, long dflt) {
        Long v = HierarchicalHydrology.Diag.LAYER.get(key);
        return v == null ? dflt : v;
    }
    // [5] 侵蚀路径确定性：真比较（顺序相关的确定性失败 → 未基线归因 ⇒ 单独 ERROR 桶，不宽松断言）
    private static void secErosion(long seed) {
        log("\n=== [5] 侵蚀路径确定性（真比较，采样级）===");
        int n = 6, rounds = 3, baseBad = 0, wuBad = 0;
        double worst = 0;
        double[] fa = null, fb = null;
        for (int r = 0; r < rounds; r++) {
            if (maxAbs(sweep(newGen(seed), n, false, false), sweep(newGen(seed), n, true, false)) > 0) baseBad++;
            double[] a = sweep(newGen(seed), n, false, true), b = sweep(newGen(seed), n, true, true);
            double d = maxAbs(a, b);
            if (d > 0) { wuBad++; if (fa == null) { fa = a; fb = b; } worst = Math.max(worst, d); }
        }
        chk('R', baseBad == 0, "[5a] base 地形 sample().e 顺序无关（噪声核干净）", baseBad + "/" + rounds + " 轮异序不一致");
        log("  采样点=" + (n * n) + " 轮数=" + rounds + " 异序不一致轮=" + wuBad + " worst=" + String.format("%.3e", worst) + " 块");
        if (fa != null) { int k = firstDiff(fa, fb); log("  首个分歧: k=" + k + " a=" + fa[k] + " b=" + fb[k]); }
        chk('U', wuBad == 0, "[5b] 侵蚀 sampleWu().height 顺序无关（确定性铁律）",
                wuBad + "/" + rounds + " 轮异序不一致，worst=" + String.format("%.3e", worst) + " 块（seed=" + seed + "）；"
                        + "归因【未验证】⇒ 仅登记为 UNCLASSIFIED DETERMINISM FAILURE，不作任何上下游归因。");
    }
    /** 采样扫描：eroded=false 用 sample().e，true 用 sampleWu().height；行主序或逆序。 */
    private static double[] sweep(CellGenerator g, int n, boolean reverse, boolean eroded) {
        double hs = Math.max(0.01, g.params().horizontalScale());
        double[] o = new double[n * n];
        for (int k = 0; k < n * n; k++) {
            int idx = reverse ? n * n - 1 - k : k;
            double x = (100 + (idx / n) * 4) / hs, z = (100 + (idx % n) * 4) / hs;
            o[idx] = eroded ? g.sampleWu(x, z).height : g.sample(x, z).e;
        }
        return o;
    }
    private static int firstDiff(double[] a, double[] b) {
        if (a == null || b == null) return -1;
        for (int k = 0; k < a.length; k++) if (!bitsEq(a[k], b[k])) return k;
        return -1;
    }
    private static double maxAbs(double[] a, double[] b) {
        double m = 0;
        for (int k = 0; k < a.length; k++) if (!bitsEq(a[k], b[k])) m = Math.max(m, Math.abs(a[k] - b[k]));
        return m;
    }
    // [6] 生产 provider：owner 负坐标 / 并发单飞 / 冷热 / 指纹 / 扇出
    private static void secProvider(long seed) {
        log("\n=== [6] 生产 provider：owner 负坐标 / 并发单飞 / 冷热 / 指纹 / 扇出 ===");
        ModelHydrologyProvider p = new ModelHydrologyProvider(newGen(seed), seed);
        provOwner(p);
        provSingleFlight(p);
        provColdHot(p);
        provFingerprint(p);
        provFanout(p);
    }
    private static void provOwner(ModelHydrologyProvider p) {
        p.clear();
        HierarchicalHydrology.Diag.reset();
        HierarchicalHydrology.Coarse neg = p.coarseLayerFor(-1, -1);
        HierarchicalHydrology.Coarse org = p.coarseLayerFor(0, 0);
        chk('R', neg.contains(-1, -1) && Double.isFinite(neg.levelAt(-1, -1)), "负坐标 tract(-1,-1) 覆盖并给出有限水位", "level=" + neg.levelAt(-1, -1));
        double v1 = neg.levelAt(-1, -1), b1 = neg.bedAt(-1, -1);
        p.coarseLayerFor(-1, 0); p.coarseLayerFor(0, -1); neg.levelAt(-2, -3); org.levelAt(-1, -1);
        chk('R', bitsEq(v1, neg.levelAt(-1, -1)) && bitsEq(b1, neg.bedAt(-1, -1)), "同点 owner 权威读值与查询顺序无关（逐位）", v1 + " vs " + neg.levelAt(-1, -1));
        chk('R', org.contains(-1, -1), "tract 域重叠已登记：相邻 tract 都覆盖该点 ⇒ owner 由 floorDiv 裁决（非 contains）", "org.contains(-1,-1)=" + org.contains(-1, -1));
    }
    private static void provSingleFlight(ModelHydrologyProvider p) {
        p.clear();
        HierarchicalHydrology.Diag.reset();
        final int threads = 8;
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(threads);
        AtomicReference<HierarchicalHydrology.Coarse>[] got = new AtomicReference[threads];
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            Thread th = new Thread(() -> {
                try { go.await(); got[idx] = new AtomicReference<>(p.coarseLayerFor(6, 6)); }
                catch (Throwable e) { got[idx] = null; } finally { done.countDown(); }
            }, "probe-coarse-" + t);
            th.setDaemon(true);
            th.start();
        }
        go.countDown();
        boolean joined = false;
        try { joined = done.await(300, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        chk('R', joined, "并发取粗层：全部线程返回（无死锁）", "300s 超时");
        chk('R', HierarchicalHydrology.Diag.COARSE_SOLVES.get() == 1, "并发单飞：同一 tract 恰好粗解 1 次", "solves=" + HierarchicalHydrology.Diag.COARSE_SOLVES.get());
        boolean same = true;
        for (AtomicReference<HierarchicalHydrology.Coarse> r : got) if (r == null || r.get() == null || !pipeEq(r.get().pipe(), got[0].get().pipe())) same = false;
        chk('R', same, "并发各线程取到逐位相同的粗层内容", "有线程内容不同/为空");
    }
    private static void provColdHot(ModelHydrologyProvider p) {
        p.clear();
        HierarchicalHydrology.Diag.reset();
        HierarchicalHydrology.Coarse cold = p.coarseLayerFor(7, 7);
        long s1 = HierarchicalHydrology.Diag.COARSE_SOLVES.get();
        HierarchicalHydrology.Coarse hot = p.coarseLayerFor(7, 7);
        long s2 = HierarchicalHydrology.Diag.COARSE_SOLVES.get();
        chk('R', s1 == 1 && s2 == 1, "热缓存命中不重解（solves 保持 1）", "cold=" + s1 + " hot=" + s2);
        chk('R', cold == hot || pipeEq(cold.pipe(), hot.pipe()), "热命中返回同内容", "内容不同");
        p.clear();
        chk('R', pipeEq(cold.pipe(), p.coarseLayerFor(7, 7).pipe()), "清空缓存后重解 ⇒ 与冷缓存逐位相同（缓存只改时序不改内容）", "两次冷解不同");
    }
    private static void provFingerprint(ModelHydrologyProvider p) {
        long f1 = p.strategyFingerprintValue();
        chk('R', f1 == new ModelHydrologyProvider(newGen(SEED), SEED).strategyFingerprintValue(), "同参数同配置 ⇒ 策略指纹相同", "不同");
        long f3 = new ModelHydrologyProvider(newGen(SEED, 3.0), SEED).strategyFingerprintValue();
        chk('R', f3 != f1, "地形参数变化（hs 2.0→3.0）⇒ 指纹变化（缓存不混用）", "f1=" + f1 + " f3=" + f3);
        chk('R', new ModelHydrologyProvider(newGen(4242424242L), 4242424242L).strategyFingerprintValue() == f1, "换世界种子不影响策略指纹（种子另在 CacheKey keyed）", "f1=" + f1);
    }
    /** pass-1 扇出恒 1：HIER 下只跑粗层、零细层、不因取邻区多解 tract。 */
    private static void provFanout(ModelHydrologyProvider p) {
        p.clear();
        HierarchicalHydrology.Diag.reset();
        Method m;
        try { m = ModelHydrologyProvider.class.getDeclaredMethod("regionPass1", int.class, int.class); m.setAccessible(true); }
        catch (NoSuchMethodException e) { chk('R', false, "反射 regionPass1", String.valueOf(e)); return; }
        chk('R', invoke(m, p, 5, 5) == null, "pass-1 调用成功（生产私有入口）", "异常");
        chk('R', HierarchicalHydrology.Diag.FINE_SOLVES.get() == 0 && HierarchicalHydrology.Diag.COARSE_SOLVES.get() == 1, "pass-1 只跑粗层（细解 0、粗解 1）⇒ 零重管线", "fine=" + HierarchicalHydrology.Diag.FINE_SOLVES.get() + " coarse=" + HierarchicalHydrology.Diag.COARSE_SOLVES.get());
        String err = invoke(m, p, 5, 6);
        chk('R', err == null && HierarchicalHydrology.Diag.COARSE_SOLVES.get() == 2 && HierarchicalHydrology.Diag.FINE_SOLVES.get() == 0, "pass-1 不取邻区：扇出恒 1（再求一 tract ⇒ 粗解 2、细解仍 0）", err != null ? err : ("coarse=" + HierarchicalHydrology.Diag.COARSE_SOLVES.get()));
    }
    /** @return null = 成功；否则异常字符串 */
    private static String invoke(Method m, Object target, int a, int b) {
        try { m.invoke(target, a, b); return null; }
        catch (Exception e) { return String.valueOf(e.getCause() == null ? e : e.getCause()); }
    }
    // [7] 真实 region（走生产入口 columnsFor）
    private static void secReal(long seed, List<int[]> regions) {
        log("\n=== [7] 真实 region（固定 regions，走生产入口 columnsFor）===");
        if (!ModelHydrologyProvider.hierarchical() || !ModelHydrologyProvider.SKELETON_TRACE) {
            chk('R', false, "real 前置：hierarchical=true 且 skeletonTrace=true", "开关未开");
            return;
        }
        log("  探索口径：strictContract=false（取得完整 metrics）；冲突类异常仍捕获并计入 contract FAIL。");
        HierarchicalHydrology.Params hp = ModelHydrologyProvider.hierParams();
        CellGenerator gen = newGen(seed);
        double hs = Math.max(0.01, gen.params().horizontalScale());
        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen, seed);
        provider.clear();
        HierarchicalHydrology.Diag.reset();
        columnDeterminism(provider, gen);
        provider.clear();
        HierarchicalHydrology.Diag.reset();
        int solved = 0, aborts = 0;
        for (int[] rg : regions) {
            int rx = rg[0], rz = rg[1];
            int chX = Math.floorDiv(rx * hp.tract() + hp.tract() / 2, 16);
            int chZ = Math.floorDiv(rz * hp.tract() + hp.tract() / 2, 16);
            long before = HierarchicalHydrology.Diag.FINE_SOLVES.get();
            int outcome = solveOne(provider, gen, hs, rx, rz, chX, chZ);
            if (outcome == 1) {
                solved++;
                chk('R', HierarchicalHydrology.Diag.FINE_SOLVES.get() - before == 1, "region(" + rx + "," + rz + ") 细解恰好 1 次（无邻区细解）", "Δ=" + (HierarchicalHydrology.Diag.FINE_SOLVES.get() - before));
                reportRegion(rx, rz, ModelHydrologyProvider.lastFineValidation(), ModelHydrologyProvider.lastFineConnectivity());
                var world = provider.worldForProbe(rx, rz);
                if (world != null) {
                    var reaches = provider.reachesForProbe(rx * hp.tract() + hp.tract() / 2, rz * hp.tract() + hp.tract() / 2);
                    chk('C', !world.edges().isEmpty() && reaches.size() == world.edges().size(),
                            "世界骨架候选边与实际发布段一一对应（不拿旧过滤统计冒充）", "edges=" + world.edges().size() + " published=" + reaches.size());
                }
            } else if (outcome == 2) aborts++;
        }
        if (aborts == 0) chk('R', solved == regions.size(), "全部给定 region 完成求解", solved + "/" + regions.size());
        else log("  ⚠ " + aborts + "/" + regions.size() + " 个 region 因契约冲突中止（已计 contract FAIL，不重复计 runtime）");
        realCrossChecks();
        log("\n----- 分层诊断全量报告（不截断）-----\n" + HierarchicalHydrology.Diag.report());
    }
    /** 逐 region 打印形态对比/连通性并判定（形态差异=诊断；仅 contractValid=false 才 FAIL）。 */
    private static void reportRegion(int rx, int rz, HierarchicalHydrology.MorphologyComparison v, HierarchicalHydrology.Connectivity cx) {
        if (v == null) chk('C', false, "region(" + rx + "," + rz + ") lastFineValidation 非空", "null");
        else {
            log(String.format("  region(%d,%d) 形态对比: compared=%d matched=%d moved=%d evolvedOut=%d truncated=%d levelDiff=%d foreign=%d worst=%.2f contractValid=%b",
                    rx, rz, v.compared(), v.matched(), v.movedExterior(), v.missing(), v.truncated(), v.levelMismatch(), v.foreign(), v.worstLevelDelta(), v.contractValid()));
            for (String s : v.samples()) log("    · " + s);
            chk('C', v.contractSatisfied(), "region(" + rx + "," + rz + ") contractValid（形态差异不参与；仅非有限/越域才 FAIL）", "contractValid=" + v.contractValid());
        }
        if (cx == null) { chk('C', false, "region(" + rx + "," + rz + ") lastFineConnectivity 非空", "null"); return; }
        log("  region(" + rx + "," + rz + ") 连通性: " + dump(cx));
        chk('C', !cx.hardFail(), "region(" + rx + "," + rz + ") 无孤立/成环（硬失败项）", dump(cx));
        if (!cx.fullyVerified()) {
            realBoundary |= cx.boundaryUnverifiedCells() > 0;
            log("  ⚠ region(" + rx + "," + rz + ") 未完全验证：边框未验证格=" + cx.boundaryUnverifiedCells() + " ⇒ 接口未验证，不得当连通 PASS");
        }
    }
    /** 反射 regionFor 抽 256 列冷/冷两次逐位比较（避开遍历 8000+ chunk）。 */
    private static void columnDeterminism(ModelHydrologyProvider provider, CellGenerator gen) {
        Method rf;
        try { rf = ModelHydrologyProvider.class.getDeclaredMethod("regionFor", int.class, int.class); rf.setAccessible(true); }
        catch (NoSuchMethodException e) { chk('R', false, "反射 regionFor", String.valueOf(e)); return; }
        int[] p1 = columnsFp(provider, gen, rf, -256, -256);
        if (p1 == null && blockedByContract) {
            log("  ⚠ column 逐位比较【未运行】：冷解即被同一 contract 冲突中止（该失败已计 contract）");
            return;
        }
        provider.clear();
        int[] p2 = columnsFp(provider, gen, rf, -256, -256);
        if (p1 == null || p2 == null) { chk('R', false, "column 逐位比较：两次取列成功", "取列失败"); return; }
        chk('R', Arrays.equals(p1, p2), "column 逐位：同 region 冷/冷两次 ⇒ 列计划逐位相同", "有 " + countDiff(p1, p2) + " 项不同");
    }
    private static int[] columnsFp(ModelHydrologyProvider provider, CellGenerator gen, Method regionFor, int cx, int cz) {
        try {
            regionFor.invoke(provider, cx, cz);
            blockedByContract = false;
            double hs = Math.max(0.01, gen.params().horizontalScale());
            int chX = Math.floorDiv(cx, 16), chZ = Math.floorDiv(cz, 16);
            List<HydrologyBlockCarvedColumn> cols = provider.columnsFor(chX, chZ, sampleChunk(gen, hs, chX, chZ));
            int[] out = new int[cols.size() * 5];
            int n = 0;
            for (HydrologyBlockCarvedColumn c : cols) {
                out[n++] = c.lakePlan() ? 1 : 0;
                out[n++] = c.fillWater() ? 1 : 0;
                out[n++] = Double.hashCode(c.waterSurfaceY());
                out[n++] = Double.hashCode(c.carvedGroundY());
                out[n++] = Double.hashCode(c.erosion());
            }
            return Arrays.copyOf(out, n);
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            blockedByContract = cause instanceof HierarchicalHydrology.ContractViolation;
            log("    （column 取列异常: " + cause + "）");
            return null;
        }
    }
    private static int countDiff(int[] a, int[] b) {
        int d = Math.abs(a.length - b.length);
        for (int k = 0; k < Math.min(a.length, b.length); k++) if (a[k] != b[k]) d++;
        return d;
    }
    /** @return 1 成功；2 契约冲突中止；0 其它异常 */
    private static int solveOne(ModelHydrologyProvider provider, CellGenerator gen, double hs, int rx, int rz, int chX, int chZ) {
        try { provider.columnsFor(chX, chZ, sampleChunk(gen, hs, chX, chZ)); return 1; }
        catch (HierarchicalHydrology.ContractViolation e) {
            chk('C', false, "region(" + rx + "," + rz + ") 契约冲突异常（带坐标）", "block=(" + e.blockX + "," + e.blockZ + ") tract=(" + e.rx + "," + e.rz + ") " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            chk('R', false, "region(" + rx + "," + rz + ") 求解异常", String.valueOf(e));
            return 0;
        }
    }
    private static void realCrossChecks() {
        chk('C', HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get() == 0, "聚合：CONTRACT_UNSATISFIED=0（真实冲突；形态差异不计入）", "n=" + HierarchicalHydrology.Diag.CONTRACT_UNSATISFIED.get());
        chk('C', HierarchicalHydrology.Diag.CONTRACT_GAPS.get() == 0, "聚合：契约缺口=0（粗域覆盖近边界点）", "gaps=" + HierarchicalHydrology.Diag.CONTRACT_GAPS.get());
        chk('C', HierarchicalHydrology.Diag.EXTERIOR_MISSES.get() == 0, "聚合：窗外权威缺失=0", "misses=" + HierarchicalHydrology.Diag.EXTERIOR_MISSES.get());
        chk('R', HierarchicalHydrology.Diag.EXTERIOR_OVERRIDES.get() > 0, "聚合：窗外权威确实被使用（>0）⇒ 细层边界来自粗层接口", "overrides=" + HierarchicalHydrology.Diag.EXTERIOR_OVERRIDES.get());
        if (ModelHydrologyProvider.SKELETON_TRACE && ModelHydrologyProvider.CROSS_PASS)
            log("  世界骨架不调用旧窗口网络过滤；已逐region核对实际发布边，旧过滤统计 NOT APPLICABLE");
        else if (HierarchicalHydrology.Diag.LAST_SKELETON_FILTER_DROPPED < 0) log("  ⚠ 骨架网络过滤状态【未观测】：有 region 在骨架构建前即中止 ⇒ NOT VERIFIED");
        else {
            chk('C', !HierarchicalHydrology.Diag.LAST_SKELETON_FILTER_ENABLED, "聚合：分层构建显式关闭骨架网络过滤（不用删除掩盖失败）", "enabled=" + HierarchicalHydrology.Diag.LAST_SKELETON_FILTER_ENABLED);
            chk('C', HierarchicalHydrology.Diag.LAST_SKELETON_FILTER_DROPPED == 0, "聚合：网络过滤删链数=0（几何完整）", "dropped=" + HierarchicalHydrology.Diag.LAST_SKELETON_FILTER_DROPPED);
        }
        // ★ 形态差异只【记录】（OUTLET_MOVED 等），绝不当契约失败；仅打印供人工核对。
        log("  形态(诊断，非失败): OUTLET_MOVED=" + HierarchicalHydrology.Diag.OUTLET_MOVED.get()
                + " BASIN_EVOLVED_OUT=" + HierarchicalHydrology.Diag.BASIN_EVOLVED_OUT.get()
                + " TRUNCATED_CONTEXT=" + HierarchicalHydrology.Diag.TRUNCATED_CONTEXT.get()
                + " LEVEL_MISMATCH=" + HierarchicalHydrology.Diag.LEVEL_MISMATCH.get()
                + " FOREIGN_HALO_BASINS=" + HierarchicalHydrology.Diag.FOREIGN_HALO_BASINS.get());
        log("  聚合: 粗解=" + HierarchicalHydrology.Diag.COARSE_SOLVES.get() + " 细解=" + HierarchicalHydrology.Diag.FINE_SOLVES.get() + " 粗层湖候选一致=" + HierarchicalHydrology.Diag.COARSE_LAKE_CANDIDATE_AGREE.get() + " 不一致(侵蚀漏水)=" + HierarchicalHydrology.Diag.COARSE_LAKE_CANDIDATE_DISAGREE.get() + " 区内无命中=" + HierarchicalHydrology.Diag.IN_TRACT_NO_HIT.get());
        if (!HierarchicalHydrology.Diag.DISAGREE_SAMPLES.isEmpty()) log("  粗层湖候选不一致样本: " + HierarchicalHydrology.Diag.DISAGREE_SAMPLES);
    }
    private static Cell[] sampleChunk(CellGenerator gen, double hs, int chunkX, int chunkZ) {
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) cells[lx * 16 + lz] = gen.sample((chunkX * 16 + lx) / hs, (chunkZ * 16 + lz) / hs);
        return cells;
    }
    // 预热等待（避免残留污染"零 tile"快照；用单 tile 直取的探针专用读取，不走会生成 8 邻居的常规入口）
    private static final java.lang.reflect.Field TILE_PREHEATER = findPreheater();
    private static java.lang.reflect.Field findPreheater() {
        try { java.lang.reflect.Field f = CellGenerator.class.getDeclaredField("TILE_PREHEATER"); f.setAccessible(true); return f; }
        catch (NoSuchFieldException e) { return null; }
    }
    private static void awaitPreheatIdle(int maxMs) {
        long t0 = System.nanoTime();
        int quiet = 0;
        while ((System.nanoTime() - t0) / 1_000_000L < maxMs) {
            if (preheatBusy() == 0) { if (++quiet >= 4) return; } else quiet = 0;
            sleep(50);
        }
    }
    private static int preheatBusy() {
        if (TILE_PREHEATER == null) return 0;
        try {
            ThreadPoolExecutor ex = (ThreadPoolExecutor) TILE_PREHEATER.get(null);
            return ex == null ? 0 : ex.getActiveCount() + ex.getQueue().size();
        } catch (Exception e) { return 0; }
    }
    // NOT VERIFIED / 局限
    private static void notVerified(String mode) {
        log("\n=== NOT VERIFIED / 局限（明确登记，不声称为已证）===");
        log(" 1. 湖身份变化：coarse↔fine 盆地只按 seed 格比对；分裂/合并/改名不可由本探针判定。");
        log(" 2. 接口【无来水账本】：跨 tract 只传水位（Port.level / 窗外环水位）⇒ 跨区守恒未证。");
        log(" 3. 侵蚀确定性仅采样级；config 解析=" + (configResolved ? "是（工作副本 build/hierarchical-evaluation/config-probe.toml）" : "否（本次为 runtime FAIL）") + "；无 Forge 生命周期 ⇒ 与实机仍可能有别。");
        log(" 4. 边框未验证格：走出窗口只证窗内未断，邻 tract 是否接住未证 ⇒ 不算连通 PASS。合成负例证明语义生效=" + synthBoundaryProven + "；真实 region 出现=" + realBoundary + "。");
        log(" 5. 未做全图像素对照（正典出图/覆盖率），也未做端到端实机确认；未做全图像素验收。");
        log(" 6. 严格契约：形态差异（MOVED/LEVEL/TRUNCATED/候选消失）在严格模式下<b>同样不抛</b>，只作诊断；唯一真实冲突=非有限水位/越域索引。real 探索口径仅关真实冲突抛异常；结论仍按计数判 FAIL。模式=" + mode + "：默认关闭路径行为只在 mode=default-off 覆盖。");
        log(" 7. 分层域参数（tract/halo/step/coarseHalo）是生产常量，非物理推导 —— 只复核行为一致，不为其数值背书。");
        if (cdF > 0) {
            log("\n ★★ [UNCLASSIFIED DETERMINISM FAILURE] 未归因的确定性失败（本节【未】宽松化）★★");
            log("  现象：[5b] 侵蚀后 sampleWu().height 在同 seed/同参数下依赖【采样访问顺序】（两台独立引擎、同批点、其一逆序 ⇒ 逐位不等）。");
            log("  [5a] base 地形 sample().e 顺序无关 ⇒ 已定位到【侵蚀 tile 读取/blend】路径内。");
            log("  归因【未验证】：未对照无本项目改动的基线运行 ⇒ 本探针不作任何上下游归因（既不说由本次引入，也不说非本次引入）。");
            log("  影响：runHydrologyDeterminismProbe(TOL=0) 长序列下会不稳定。本探针不替 core 修复，也不放宽该判据。");
        }
    }
    // 收尾
    private static void emit(String mode) throws java.io.IOException {
        log(String.format("%n总耗时 %.1f s（mode=%s）", (System.nanoTime() - T0) / 1e9, mode));
        log("—— 汇总（三类判据保留区别，均计入 exit code）——");
        log("  runtime checks:  " + (rtF == 0 ? "PASSED" : "FAIL") + "（fail=" + rtF + "）");
        log("  ACCEPTANCE(contract): " + (ctF == 0 ? "PASSED" : "FAIL") + "（fail=" + ctF + "）");
        boolean structural = "structural".equals(mode);
        log("  UNCLASSIFIED DETERMINISM FAILURE（归因未验证）: "
                + (structural ? "NOT RUN（structural 模式已跳过侵蚀确定性测试 —— 不制造总通过）" : String.valueOf(cdF)));
        String v = "runtime checks " + (rtF == 0 ? "PASSED" : "FAIL") + " + ACCEPTANCE " + (ctF == 0 ? "PASSED" : "FAIL")
                + (structural ? " + UNCLASSIFIED NOT RUN" : " + UNCLASSIFIED " + (cdF == 0 ? "PASSED" : "FAIL"));
        if (structural) v += "（仅结构性 gate，未覆盖效果/确定性 —— 非总通过）";
        if (rtF == 0 && ctF > 0) v += "（基础通过、效果契约失败 —— 不混为全 PASS；放宽 ≠ 通过）";
        if (!structural && cdF > 0) v += "；另有 UNCLASSIFIED DETERMINISM FAILURE " + cdF + " 项（见 NOT VERIFIED 末段，均计入 exit≠0）";
        if (realBoundary) v += "；真实 region 出现边框未验证格（不得当连通 PASS）";
        log("判定: " + v);
        Path out = OUT_DIR.resolve("metrics.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, LOG.toString());
        System.out.print(LOG);
        System.out.println("FILE=" + out.toAbsolutePath());
    }
}
