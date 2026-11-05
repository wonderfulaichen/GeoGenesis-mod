package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>生产路径三图对照渲染器（7a1a45a / a70e87b / 分层实验）</h1>
 *
 * <p><b>本类是同一条渲染管线，注入到三棵源码树（历史基线 7a1a45a、已提交基线 a70e87b、
 * 当前工作区实验）后逐树各出一张面板 PNG，再由 {@code montage} 模式拼成 3 联对照图。</b>
 * 只引用三棵树<b>公共</b>的 API，绝不用某一棵独有的类型（如 {@code HierarchicalHydrology}）。</p>
 *
 * <h2>口径（三棵树必须逐字一致）</h2>
 * <ul>
 *   <li>尺寸 / 中心 / 种子：{@link ViewSpec}（radius=700 ⇒ 1401×1401，seed/中心同正典）；</li>
 *   <li>底图：{@link ViewSpec#contourFixed}（<b>固定</b>色标，跨图可比；不用按图自适应的档距）；</li>
 *   <li>底图数据源：{@code gen.sampleWu(...)}（与生产 model 链同源）；<b>不跑缓存 peek</b>；</li>
 *   <li>水域判据（与游戏落块层一致，<b>不</b>复用已判错的 {@code ProductionViewProbe.erosion>0}）：
 *       湖 = {@code lakePlan}；河 = {@code fillWater && waterSurfaceY > carvedGroundY}；海 = {@code bed < seaLevel}；</li>
 *   <li>采样：逐 chunk 调<b>生产</b> {@code ModelHydrologyProvider.columnsFor}，
 *       传入与游戏同一来源的 {@code gen.sample(...)} 列（{@code HydrologyChunkSampling} 口径）。</li>
 * </ul>
 *
 * <h2>配置（真实 toml）</h2>
 * <p>用 NightConfig 的 {@code TomlParser} 解析 {@code run/config/geogenesis-common.toml} 后
 * 交给 {@code GeoGenesisConfig.SPEC.setConfig(...)}，再经 {@code INSTANCE.buildParams()} 取
 * {@link TerrainParams}。若该机制在本进程不可用（无 Forge 运行时 ⇒ LinkageError / 未加载），
 * <b>如实记录偏离并回退 {@code TerrainParams.defaults()}，绝不声称与实机一致</b>。</p>
 *
 * <p><b>NOT VERIFIED</b>：离线探针（不起 MC）；不含方块铺设 / 实机端到端；骨架折线自交需内部几何，
 * 未取得 ⇒ 明确标 NOT VERIFIED（见 metrics）。</p>
 */
public final class HierarchicalComparisonView {

    private HierarchicalComparisonView() { }

    public static void main(String[] args) {
        try {
            Map<String, String> o = parseArgs(args);
            String mode = o.getOrDefault("mode", "panel");
            switch (mode) {
                case "montage" -> montage(o);
                case "verify-config" -> verifyConfig(o);
                case "panel" -> runPanel(o);
                // ★ 未知 mode 必须【响亮失败】—— 否则路径被空格拆开后残片会静默当成 mode，
                //   把 montage 退化成 panel（实测：写出的是单面板而非 3 联）。
                default -> throw new IllegalArgumentException(
                        "unknown mode '" + mode + "'（参数含空格？请用 --panelsFile/--labelsFile）");
            }
        } catch (Throwable t) {
            System.out.println("[HierarchicalComparisonView] FATAL: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
        }
    }

    /** 参数：{@code --k=v}；无 {@code --} 的前缀按 mode 处理（默认 panel）。 */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            String s = a.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("--")) {
                int eq = s.indexOf('=');
                if (eq < 0) m.put(s.substring(2), "true");
                else m.put(s.substring(2, eq), s.substring(eq + 1));
            } else {
                m.put("mode", s);
            }
        }
        return m;
    }

    // ======================================================================
    // 单面板
    // ======================================================================

    static void runPanel(Map<String, String> o) throws Exception {
        String label = o.getOrDefault("label", "panel");
        Path toml = Path.of(o.getOrDefault("toml", "run/config/geogenesis-common.toml"));
        long seed = Long.parseLong(o.getOrDefault("seed", Long.toString(ViewSpec.SEED)));
        int cx = Integer.parseInt(o.getOrDefault("cx", Integer.toString(ViewSpec.CENTER_X)));
        int cz = Integer.parseInt(o.getOrDefault("cz", Integer.toString(ViewSpec.CENTER_Z)));
        int radius = Integer.parseInt(o.getOrDefault("radius", Integer.toString(ViewSpec.RADIUS)));
        ViewSpec.assertCanonicalRadius(radius, !o.containsKey("allow-nonradius"));
        Path out = Path.of(o.get("out"));
        Cfg cfg = loadConfig(toml);
        CellGenerator gen = buildGen(seed, cfg);
        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen, seed);
        Mask m = sampleAll(gen, provider, cx, cz, radius);
        BufferedImage img = render(m, gen);
        // ★ 失败域必须【在图上可见】，不能只在文本里 —— 面板自身打横幅
        banner(img, m, label);
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(img, "png", out.toFile());
        String report = buildReport(o, cfg, label, seed, cx, cz, radius, gen, m, out);
        Path mp = out.resolveSibling(stem(out.getFileName().toString()) + ".metrics.txt");
        Files.writeString(mp, report, StandardCharsets.UTF_8);
        System.out.print(report);
    }

    /** 在面板左上打结果横幅（OK / FAILED + 失败域占比），失败时整图更醒目。 */
    static void banner(BufferedImage img, Mask m, String label) {
        int w = img.getWidth();
        int barH = Math.max(48, w / 28);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean fail = m.failCount > 0;
        g.setColor(fail ? new Color(0x000000) : new Color(0x101010));
        g.fillRect(0, 0, w, barH);
        g.setColor(fail ? new Color(0xFF2FD0) : new Color(0x00FF88));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (barH * 0.55)));
        String txt;
        if (fail) {
            int fp = count(m.failed);
            txt = "TRUE-FAILURE  non-finite/out-of-domain: " + m.failedTracts.size()
                    + " tract(s), " + fp + " px (" + String.format("%.1f", 100.0 * fp / (m.n * m.n))
                    + "%) — magenta hatch = failure domain (NOT water)";
        } else {
            // ★ 探索性标签：不预设成功。此图是【探索图】，只表示"无真实冲突"，不代表效果达标。
            txt = "EXPLORATORY (no true conflict; NOT an acceptance)  " + (label == null ? "" : label);
        }
        g.drawString(txt, 12, (int) (barH * 0.72));
        g.dispose();
    }

    /** 应用真实 toml 到 Forge spec；不可用则如实登记并回退默认参数。 */
    static Cfg loadConfig(Path toml) {
        Cfg c = new Cfg();
        c.applied = false;
        c.detail = "(未尝试)";
        c.fingerprint = 0L;
        c.tomlPresent = Files.isRegularFile(toml);
        c.tomlPath = toml.toString();
        c.tomlSha = c.tomlPresent ? sha256(toml) : "(缺)";
        if (!c.tomlPresent) {
            c.detail = "toml 不存在 ⇒ 回退 TerrainParams.defaults()（与实机不一致）";
            c.params = TerrainParams.defaults();
            return c;
        }
        try {
            // ★ 主 agent 指定的可行路径：CommentedFileConfig.of(path, TomlFormat.instance())
            //   → SPEC.setConfig(cfg)。此前用 TomlParser 也可行，但此处按指定口径。
            Path work = Path.of("build", "hierarchical-evaluation", "config-render.toml");
            Files.createDirectories(work.getParent());
            Files.copy(toml, work, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfc =
                    CommentedFileConfig.of(work, TomlFormat.instance());
            cfc.load();
            boolean wasCorrect = GeoGenesisConfig.SPEC.isCorrect(cfc);
            GeoGenesisConfig.SPEC.setConfig(cfc);                // 必须早于任何 .get()
            boolean loaded = GeoGenesisConfig.SPEC.isLoaded();
            boolean river = GeoGenesisConfig.INSTANCE.riverEnabled.get();  // 触达 FMLEnvironment 路径
            c.fingerprint = GeoGenesisConfig.configFingerprint();
            c.params = GeoGenesisConfig.INSTANCE.buildParams();
            c.corrected = !wasCorrect;
            c.applied = loaded && c.fingerprint != 0L;
            c.detail = "CommentedFileConfig.of+setConfig ok isLoaded=" + loaded
                    + " riverEnabled=" + river + " fingerprint=" + c.fingerprint
                    + " params=buildParams()"
                    + (c.corrected ? " ★ toml 含越界/类型不符键 ⇒ 被 Forge 静默修正（见日志），"
                    + "应用值 ≠ toml 原值 ⇒ 配置口径与实机【不完全一致】" : " toml 键全部合法");
        } catch (Throwable t) {
            c.applied = false;
            c.params = TerrainParams.defaults();
            c.detail = "CommentedFileConfig/setConfig/buildParams 不可用 ⇒ 回退 defaults()："
                    + t.getClass().getName() + ": " + t.getMessage();
        }
        return c;
    }

    static CellGenerator buildGen(long seed, Cfg cfg) {
        TerrainParams tp = cfg.params != null ? cfg.params : TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        return gen;
    }

    // ======================================================================
    // 生产采样（与游戏同一 provider 入口）
    // ======================================================================

    static Mask sampleAll(CellGenerator gen, ModelHydrologyProvider provider,
                          int cx, int cz, int radius) {
        int n = 2 * radius + 1;
        Mask m = new Mask();
        m.n = n;
        m.lake = new boolean[n * n];
        m.river = new boolean[n * n];
        m.sea = new boolean[n * n];
        m.failed = new boolean[n * n];
        m.bed = new double[n * n];
        m.surf = new double[n * n];
        m.seen = new int[n * n];
        Arrays.fill(m.surf, Double.NaN);
        double hs = Math.max(0.01, gen.params().horizontalScale());
        // ★ 逐 chunk 记录耗时：本轮要【完整所有 chunk 的性能记录】，不能只记总量。
        //   冷/热分桶：该 chunk 是否命中 provider 的 region 缓存（misses 增量 = 冷）。
        List<double[]> perChunk = new ArrayList<>();
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        int total = (c1 - c0 + 1) * (d1 - d0 + 1);
        long tAll0 = System.nanoTime();
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                int tractX = Math.floorDiv(chX * 16 + 8, REGION);
                int tractZ = Math.floorDiv(chZ * 16 + 8, REGION);
                long tk = (((long) tractX) << 32) ^ (tractZ & 0xffffffffL);
                long misses0 = gen.tileCacheStats().misses();
                long t0 = System.nanoTime();
                boolean failed = false;
                if (m.failedTracts.contains(tk)) {
                    failed = true;              // 该 tract 已判失败：整域已标记，不再重解
                } else {
                    List<HydrologyBlockCarvedColumn> cols;
                    try {
                        cols = provider.columnsFor(chX, chZ, sampleChunk(gen, hs, chX, chZ));
                    } catch (RuntimeException e) {
                        recordFailure(m, e, tractX, tractZ, cx, cz, radius);
                        failed = true;
                        cols = null;
                    }
                    if (cols != null) {
                        m.chunks++;
                        for (HydrologyBlockCarvedColumn col : cols) {
                            int px = col.blockX() - (cx - radius);
                            int pz = col.blockZ() - (cz - radius);
                            if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                            int k = px * n + pz;
                            m.seen[k]++;
                            if (col.lakePlan()) {
                                m.lake[k] = true;
                                m.lakeCols++;
                                m.surf[k] = col.waterSurfaceY();
                            } else if (col.fillWater() && col.waterSurfaceY() > col.carvedGroundY()) {
                                m.river[k] = true;
                                m.riverCols++;
                                m.surf[k] = col.waterSurfaceY();
                            }
                        }
                    }
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                // {chX, chZ, ms, coldFlag(=misses 增量>0 或失败), failedFlag}
                perChunk.add(new double[]{chX, chZ, ms,
                        gen.tileCacheStats().misses() > misses0 ? 1 : 0, failed ? 1 : 0});
            }
        }
        m.ms = (System.nanoTime() - tAll0) / 1e6;
        m.perChunk = perChunk;
        m.chunkGridTotal = total;
        fillBed(m, gen, cx, cz, radius);
        double seaLevel = gen.seaLevel();
        for (int k = 0; k < n * n; k++) {
            m.sea[k] = !m.failed[k] && !m.lake[k] && !m.river[k] && m.bed[k] < seaLevel;
        }
        return m;
    }

    /** region 边长（与 ModelHydrologyProvider.REGION 同值；失败域整域矩形边界用）。 */
    static final int REGION = Integer.getInteger("geogenesis.modelRegion", 512);

    /**
     * 记录一次【真实】契约失败：把该 tract 整域标为失败（斜纹），并累计坐标/类型样本。
     *
     * <p><b>只有真实冲突才算失败</b>（核心已取消"粗细湖同出口"的刚性约束）：
     * 只有 {@code HierarchicalHydrology.ContractViolation}（非有限水位 / 越域索引）会走到这里。
     * 粗细层盆地形态差异（出口移动 / 候选消失）现已<b>不抛异常</b> ⇒ 不会进入本方法。</p>
     *
     * <p><b>为什么按类名识别而非类型</b>：{@code HierarchicalHydrology.ContractViolation} 只存在于
     * 当前实验树，两棵基线树没有该类 ⇒ 直接引用会导致基线树编译失败（三树共用本渲染器）。
     * 故按简单类名匹配，并用反射取 {@code rx}/{@code rz}/{@code blockX}/{@code blockZ}。</p>
     */
    static void recordFailure(Mask m, RuntimeException e, int tractX, int tractZ,
                              int cx, int cz, int radius) {
        Throwable cause = e;
        while (cause != null && !"ContractViolation".equals(cause.getClass().getSimpleName())) {
            cause = cause.getCause();
        }
        boolean isContract = cause != null;
        int rx = tractX, rz = tractZ;
        if (isContract) {
            Integer a = fieldInt(cause, "rx"), b = fieldInt(cause, "rz");
            if (a != null) rx = a;
            if (b != null) rz = b;
        }
        long tk = (((long) rx) << 32) ^ (rz & 0xffffffffL);
        m.failedTracts.add(tk);
        m.failCount++;
        if (!isContract) m.otherFailCount++;
        if (m.failSamples.size() < 12) {
            int bx = fieldInt(cause, "blockX") != null ? fieldInt(cause, "blockX") : Integer.MIN_VALUE;
            int bz = fieldInt(cause, "blockZ") != null ? fieldInt(cause, "blockZ") : Integer.MIN_VALUE;
            m.failSamples.add(String.format("%s tract=(%d,%d) attr=(%d,%d) %s",
                    cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName(),
                    rx, rz, bx, bz, String.valueOf(cause == null ? e.getMessage() : cause.getMessage())));
        }
        // 整域标记：tract 矩形 [rx*REGION, rx*REGION+REGION) × ……，裁剪到面板
        int loX = rx * REGION, loZ = rz * REGION;
        for (int i = 0; i < m.n; i++) {
            int wx = cx - radius + i;
            if (wx < loX || wx >= loX + REGION) continue;
            for (int j = 0; j < m.n; j++) {
                int wz = cz - radius + j;
                if (wz < loZ || wz >= loZ + REGION) continue;
                m.failed[i * m.n + j] = true;
            }
        }
    }

    /** 反射取 int 字段（失败时返回 null，绝不抛）。 */
    static Integer fieldInt(Object o, String name) {
        if (o == null) return null;
        try {
            java.lang.reflect.Field f = o.getClass().getField(name);
            return f.getInt(o);
        } catch (Exception e) {
            return null;
        }
    }

    static Cell[] sampleChunk(CellGenerator gen, double hs, int chX, int chZ) {
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = gen.sample((chX * 16 + lx) / hs, (chZ * 16 + lz) / hs);
            }
        }
        return cells;
    }

    /** 底图高程：{@code sampleWu}（与生产 model 链同源；不跑缓存 peek）。 */
    static void fillBed(Mask m, CellGenerator gen, int cx, int cz, int radius) {
        int n = m.n;
        double hs = Math.max(0.01, gen.params().horizontalScale());
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                m.bed[i * n + j] = gen.sampleWu((cx - radius + i) / hs, (cz - radius + j) / hs).height;
            }
        }
    }

    /** 失败域底色/斜纹（品红系）。 */
    static final int FAIL_BASE = 0x3A0F2E;
    static final int FAIL_STRIPE = 0xFF2FD0;

    static BufferedImage render(Mask m, CellGenerator gen) {
        int n = m.n;
        double seaLevel = gen.seaLevel();
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (m.failed[k]) {
                    // 契约失败域：整域斜纹 + 品红（绝不画成水；与正常水区明显区分）
                    color = ((i + j) % 12 < 4) ? FAIL_STRIPE : FAIL_BASE;
                } else if (m.lake[k]) color = ViewSpec.LAKE_COLOR;
                else if (m.river[k]) color = ViewSpec.RIVER_COLOR;
                else if (m.bed[k] < seaLevel) color = ViewSpec.SEA_COLOR;
                else color = ViewSpec.contourFixed(m.bed, n, i, j);
                img.setRGB(i, j, color);
            }
        }
        return img;
    }

    // ======================================================================
    // 统计（分量 / 接缝 / 水位跳变）
    // ======================================================================

    /** 河格 8 邻连通分量：完整分量统计 + 不接湖/海/边框的孤立分量。 */
    static Comp compStats(boolean[] river, boolean[] water, int n) {
        int[] label = new int[n * n];
        Arrays.fill(label, -1);
        Comp c = new Comp();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int k = 0; k < n * n; k++) {
            if (!river[k] || label[k] >= 0) continue;
            label[k] = c.count;
            q.clear();
            q.add(k);
            int size = 0;
            boolean touchWater = false, touchFrame = false;
            while (!q.isEmpty()) {
                int p = q.poll();
                size++;
                int ci = p / n, cj = p % n;
                if (ci == 0 || cj == 0 || ci == n - 1 || cj == n - 1) touchFrame = true;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                        int nk = ni * n + nj;
                        if (water[nk]) touchWater = true;
                        if (river[nk] && label[nk] < 0) {
                            label[nk] = c.count;
                            q.add(nk);
                        }
                    }
                }
            }
            c.count++;
            c.cells += size;
            c.max = Math.max(c.max, size);
            if (size <= SMALL_COMP) { c.smallCount++; c.smallCells += size; }
            if (!touchWater && !touchFrame) { c.orphanCount++; c.orphanCells += size; }
            c.sizes.add(size);
        }
        c.sizes.sort(null);
        return c;
    }

    /** 分量/接缝/水位跳变汇总文本（供 metrics）。 */
    static String statLines(Mask m, int cx, int cz, int radius) {
        int n = m.n;
        boolean[] water = new boolean[n * n];
        for (int k = 0; k < n * n; k++) water[k] = m.lake[k] || m.river[k] || m.sea[k];
        Comp c = compStats(m.river, water, n);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("河格 8 邻分量：count=%d cells=%d max=%d p50=%d p90=%d "
                        + "小分量(<=%d)=%d/%d 不接湖海边框=%d/%d%n",
                c.count, c.cells, c.max, pct(c.sizes, 50), pct(c.sizes, 90),
                SMALL_COMP, c.smallCount, c.smallCells, c.orphanCount, c.orphanCells));
        int[] seam = seamBreaks(water, n, cx, cz, radius);
        sb.append(String.format("region(512) 接缝（逐列）：跨缝对=%d 单侧断=%d (%.2f%%)%n",
                seam[0], seam[1], 100.0 * seam[1] / Math.max(1, seam[0])));
        int[] jmp = levelJumps(m.surf, n);
        sb.append(String.format("相邻水位跳变：对=%d >1.0块=%d >2.0块=%d max=%.2f 块%n",
                jmp[0], jmp[1], jmp[2], jmp[3] == 0 ? 0.0 : jmp[3] / 100.0));
        int[] cr = crossings(m.river, n);
        sb.append(String.format("河掩码自交（像素级 X 交叉）：像素=%d X型=%d （代理：骨架折线几何未取得）%n",
                cr[0], cr[1]));
        return sb.toString();
    }

    /**
     * 像素级 X 交叉计数（对河掩码逐格检查）：某河格的两个对角都被河格占据、
     * 但相邻的 4 邻不全为河 ⇒ 两条细线在此"斜穿"（X 型）。这是<b>真实的逐格检查</b>，
     * 但只是骨架自交的<b>代理</b>（骨架折线几何未在公共 API 暴露）。
     * @return {河格数, X 型交叉格数}
     */
    static int[] crossings(boolean[] river, int n) {
        int cells = 0, x = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (!river[k]) continue;
                cells++;
                boolean up = i > 0, dn = i < n - 1, lf = j > 0, rt = j < n - 1;
                boolean ul = up && lf && river[k - n - 1], ur = up && rt && river[k - n + 1];
                boolean dl = dn && lf && river[k + n - 1], dr = dn && rt && river[k + n + 1];
                int diag = (ul ? 1 : 0) + (ur ? 1 : 0) + (dl ? 1 : 0) + (dr ? 1 : 0);
                boolean cross = (ul && dr) || (ur && dl);
                if (cross && diag == 2) x++;
            }
        }
        return new int[]{cells, x};
    }

    /** 逐列检查 region(512) 接缝：跨缝相邻两列水体分类不一致即"单侧断"。 */
    static int[] seamBreaks(boolean[] water, int n, int cx, int cz, int radius) {
        int pairs = 0, breaks = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int wx = cx - radius + i, wz = cz - radius + j;
                if (i + 1 < n && Math.floorDiv(wx, 512) != Math.floorDiv(wx + 1, 512)) {
                    pairs++;
                    if (water[k] != water[k + n]) breaks++;
                }
                if (j + 1 < n && Math.floorDiv(wz, 512) != Math.floorDiv(wz + 1, 512)) {
                    pairs++;
                    if (water[k] != water[k + 1]) breaks++;
                }
            }
        }
        return new int[]{pairs, breaks};
    }

    /** 相邻水位跳变统计（return {pairs, >1.0, >2.0, max×100}）。 */
    static int[] levelJumps(double[] surf, int n) {
        int pairs = 0, o1 = 0, o2 = 0;
        double max = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (Double.isNaN(surf[k])) continue;
                for (int step : new int[]{i + 1 < n ? n : 0, j + 1 < n ? 1 : 0}) {
                    if (step == 0) continue;
                    int nb = k + step;
                    if (Double.isNaN(surf[nb])) continue;
                    double d = Math.abs(surf[k] - surf[nb]);
                    pairs++;
                    if (d > 1.0) o1++;
                    if (d > 2.0) o2++;
                    if (d > max) max = d;
                }
            }
        }
        return new int[]{pairs, o1, o2, (int) Math.round(max * 100)};
    }

    // ======================================================================
    // 报告
    // ======================================================================

    static String buildReport(Map<String, String> o, Cfg cfg, String label, long seed,
                              int cx, int cz, int radius, CellGenerator gen, Mask m, Path out) {
        int n = m.n;
        int riverCells = 0, lakeCells = 0, uncovered = 0;
        for (int k = 0; k < n * n; k++) {
            if (m.lake[k]) lakeCells++;
            else if (m.river[k]) riverCells++;
            if (m.seen[k] == 0) uncovered++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== HierarchicalComparisonView 面板 ===\n");
        sb.append("label=").append(label).append('\n');
        sb.append("git=").append(o.getOrDefault("git", "(未提供)")).append('\n');
        sb.append("toml=").append(cfg.tomlPath).append(" sha256=").append(cfg.tomlSha).append('\n');
        sb.append("config: applied=").append(cfg.applied)
                .append(" tomlCorrected=").append(cfg.corrected)
                .append("  ").append(cfg.detail).append('\n');
        sb.append("hier: -Dgeogenesis.hierarchicalHydrology=")
                .append(System.getProperty("geogenesis.hierarchicalHydrology", "(未设置=false)"))
                .append(" hierStrictContract=")
                .append(System.getProperty("geogenesis.hierStrictContract", "(未设置=true)")).append('\n');
        sb.append("skeleton: -Dgeogenesis.skeletonTrace=")
                .append(System.getProperty("geogenesis.skeletonTrace", "(未设置)"))
                .append(" （toml hydrologyDeterministicSkeleton 见 config 段；-D 覆盖 toml）").append('\n');
        sb.append(String.format("seed=%d centre=(%d,%d) radius=%d n=%d 底图=contourFixed(min=%.0f band=%.0f)"
                        + " 数据源=sampleWu%n",
                seed, cx, cz, radius, n, ViewSpec.BAND_BASE, ViewSpec.BAND_BLOCKS));
        sb.append(String.format("elapsed=%.1fs chunks=%d%n", m.ms / 1000.0, m.chunks));
        sb.append(String.format("湖格=%d (%.2f%%) 河格=%d (%.2f%%) 未覆盖=%d%n",
                lakeCells, 100.0 * lakeCells / (n * n),
                riverCells, 100.0 * riverCells / (n * n), uncovered));
        sb.append(String.format("result=%s%n",
                m.failCount > 0 ? "TRUE_FAILURE(non-finite/out-of-domain)" : "NO_TRUE_FAILURE(exploratory)"));
        sb.append(String.format("真实冲突：失败 tract=%d 已跑 chunk=%d 其它(非ContractViolation) tract=%d 失败域像素=%d (%.2f%%)%n",
                m.failedTracts.size(), m.chunks, m.otherFailCount, count(m.failed),
                100.0 * count(m.failed) / (n * n)));
        sb.append("（核心已取消粗细层【同出口】刚性约束 ⇒ 盆地形态差异不再计失败；"
                + "只有非有限水位/越域索引 = 真实冲突）\n");
        if (!m.failSamples.isEmpty()) {
            sb.append("失败样本：\n");
            for (String s : m.failSamples) sb.append("  · ").append(s).append('\n');
        }
        sb.append(String.format("provider 列：湖列=%d 河列=%d （chunks×256=%d）%n",
                m.lakeCols, m.riverCols, (long) m.chunks * 256));
        sb.append(String.format("侵蚀 tile：misses(查询未命中，非唯一生成数)=%d hits=%d evictions=%d cacheSize=%d%n",
                gen.tileCacheStats().misses(), gen.tileCacheStats().hits(),
                gen.tileCacheStats().evictions(), gen.erosionTileCacheSize()));
        sb.append(String.format("provider region 网格湖格累计（含重叠，仅量级对照）=%d%n",
                ModelHydrologyProvider.lastRegionLakeCells()));
        sb.append(statLines(m, cx, cz, radius));
        sb.append(perfLines(m));
        sb.append("provenance: imgSha256=").append(sha256(out)).append('\n');
        sb.append("provenance: configFingerprint=").append(cfg.fingerprint).append('\n');
        sb.append("provenance: rendererSourceSha256=").append(sha256(rendererSourcePath())).append('\n');
        sb.append("NOT VERIFIED：\n");
        sb.append("  - 骨架折线自交：NOT VERIFIED（骨架几何未在公共 API 暴露 ⇒ 只有像素级 X 交叉代理）；\n");
        sb.append("  - 接缝只按【水体掩码逐列】判定，未覆盖湖/河/海类别内的错分；\n");
        sb.append("  - 离线探针：未做方块铺设与实机端到端确认。\n");
        sb.append("img=").append(out.toAbsolutePath()).append('\n');
        return sb.toString();
    }

    /** 统计布尔网格中 true 的数量。 */
    static int count(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /**
     * 逐 chunk 性能汇总（【完整所有 chunk】口径，不是抽样）：
     * 总/均值/P50/P95/max，冷（region 未命中）与热分桶，并给出慢块 Top8。
     * <p>失败域的 chunk 不计入耗时统计（它们根本没跑细解），单独计数。</p>
     */
    static String perfLines(Mask m) {
        List<Double> warm = new ArrayList<>();
        List<Double> cold = new ArrayList<>();
        double sum = 0;
        int fail = 0, ok = 0;
        for (double[] e : m.perChunk) {
            if (e[4] > 0) { fail++; continue; }
            ok++;
            sum += e[2];
            (e[3] > 0 ? cold : warm).add(e[2]);
        }
        List<double[]> byMs = new ArrayList<>(m.perChunk);
        byMs.sort((a, b) -> Double.compare(b[2], a[2]));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【逐 chunk 性能（完整 %d chunk；成功 %d / 失败域 %d）】%n",
                m.perChunk.size(), ok, fail));
        sb.append(String.format("  合计=%.1fs 均值=%.1fms  P50=%.1fms P95=%.1fms max=%.1fms%n",
                sum / 1000.0, ok == 0 ? 0 : sum / ok, pctl(warm, cold, 50), pctl(warm, cold, 95),
                byMs.isEmpty() ? 0 : byMs.get(0)[2]));
        sb.append(String.format("  冷（region 未命中）n=%d 合计=%.1fs  ; 热 n=%d 合计=%.1fs%n",
                cold.size(), cold.stream().mapToDouble(Double::doubleValue).sum() / 1000.0,
                warm.size(), warm.stream().mapToDouble(Double::doubleValue).sum() / 1000.0));
        sb.append("  最慢 Top8 (chX,chZ,ms,cold):");
        for (int i = 0; i < Math.min(8, byMs.size()); i++) {
            double[] e = byMs.get(i);
            sb.append(String.format(" (%d,%d,%.0fms,%s)", (int) e[0], (int) e[1], e[2],
                    e[3] > 0 ? "cold" : "hot"));
        }
        sb.append('\n');
        return sb.toString();
    }

    /** 合并冷+热的 P 分位（失败/未跑的不在其中）。 */
    static double pctl(List<Double> warm, List<Double> cold, int p) {
        List<Double> all = new ArrayList<>(warm.size() + cold.size());
        all.addAll(warm);
        all.addAll(cold);
        if (all.isEmpty()) return 0;
        all.sort(null);
        int idx = (int) Math.round((p / 100.0) * (all.size() - 1));
        return all.get(Math.max(0, Math.min(all.size() - 1, idx)));
    }

    /** 本类源文件路径（供 provenance 记录；找不到时返回占位）。 */
    static Path rendererSourcePath() {
        return Path.of("src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/"
                + "HierarchicalComparisonView.java");
    }

    static void verifyConfig(Map<String, String> o) {
        Path toml = Path.of(o.getOrDefault("toml", "run/config/geogenesis-common.toml"));
        Cfg cfg = loadConfig(toml);
        StringBuilder sb = new StringBuilder();
        sb.append("=== verify-config ===\n");
        sb.append("toml=").append(cfg.tomlPath).append(" sha256=").append(cfg.tomlSha).append('\n');
        sb.append("applied=").append(cfg.applied).append(" tomlCorrected=").append(cfg.corrected).append('\n');
        sb.append("detail=").append(cfg.detail).append('\n');
        sb.append("fingerprint=").append(cfg.fingerprint).append('\n');
        sb.append("params=").append(cfg.applied ? "buildParams()（真实 toml）" : "defaults()（回退）").append('\n');
        if (cfg.params != null) {
            sb.append(String.format("params.horizontalScale=%.4f minY=%d maxY=%d%n",
                    cfg.params.horizontalScale(), cfg.params.minY(), cfg.params.maxY()));
        }
        if (!cfg.applied) {
            sb.append("结论：真实 toml 未能应用 ⇒ 配置与实机【不一致】，不得声称实机一致。\n");
        } else if (cfg.corrected) {
            sb.append("结论：toml 已应用，但含越界/类型不符键被 Forge 静默修正 ⇒ "
                    + "配置口径与实机【不完全一致】，不得声称逐位一致（仍为离线、无方块铺设）。\n");
        } else {
            sb.append("结论：真实 toml 已应用且键全部合法 ⇒ 配置口径与实机一致（仍为离线、无方块铺设）。\n");
        }
        System.out.print(sb);
    }

    // ======================================================================
    // 3 联拼图
    // ======================================================================

    static void montage(Map<String, String> o) throws Exception {
        String[] panels = resolvePanels(o);
        String[] labels = resolveLabels(o);
        Path out = Path.of(o.get("out"));
        List<BufferedImage> imgs = new ArrayList<>();
        for (String p : panels) if (!p.isBlank()) imgs.add(ImageIO.read(Path.of(p.trim()).toFile()));
        if (imgs.isEmpty()) throw new IllegalArgumentException("montage: no panels resolved");
        int pad = Integer.parseInt(o.getOrDefault("pad", "24"));
        int gap = Integer.parseInt(o.getOrDefault("gap", "24"));
        int labelH = Integer.parseInt(o.getOrDefault("labelH", "72"));
        int wid = 0, hei = 0;
        for (BufferedImage im : imgs) {
            wid += im.getWidth();
            hei = Math.max(hei, im.getHeight());
        }
        int W = pad * 2 + gap * Math.max(0, imgs.size() - 1) + wid;
        int H = labelH + hei + pad;
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        int x = pad;
        for (int t = 0; t < imgs.size(); t++) {
            BufferedImage im = imgs.get(t);
            String lab = t < labels.length ? labels[t].trim() : ("panel" + t);
            g.setColor(Color.WHITE);
            g.drawString(lab, x + 4, labelH - 20);
            g.setColor(new Color(0x808080));
            g.drawRect(x - 1, labelH - 1, im.getWidth() + 1, im.getHeight() + 1);
            g.drawImage(im, x, labelH, null);
            x += im.getWidth() + gap;
        }
        g.dispose();
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(canvas, "png", out.toFile());
        System.out.printf("montage=%s W=%d H=%d panels=%d%n", out.toAbsolutePath(), W, H, imgs.size());
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    static final int SMALL_COMP = 4;

    /**
     * 面板路径来源：{@code --panels=a;b;c} 或 {@code --panelsFile=<path>}（每行一个路径）。
     * <p>{@code --panelsFile} 用于路径含空格时（仓库路径含空格，命令行/argfile 都不好传）。</p>
     */
    static String[] resolvePanels(Map<String, String> o) throws Exception {
        String f = o.get("panelsFile");
        if (f != null && !f.isBlank()) {
            List<String> ls = Files.readAllLines(Path.of(f), StandardCharsets.UTF_8);
            return ls.stream().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        return o.getOrDefault("panels", "").split(";");
    }

    /**
     * 标签来源：{@code --labels=A;B;C} 或 {@code --labelsFile=<path>}（每行一个标签，
     * 允许含空格，如 "EXPERIMENT NOT ACCEPTED"）。
     */
    static String[] resolveLabels(Map<String, String> o) throws Exception {
        String f = o.get("labelsFile");
        if (f != null && !f.isBlank()) {
            List<String> ls = Files.readAllLines(Path.of(f), StandardCharsets.UTF_8);
            return ls.stream().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        return o.getOrDefault("labels", "").split(";");
    }

    static int pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.round((p / 100.0) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    static String stem(String fileName) {
        int d = fileName.lastIndexOf('.');
        return d < 0 ? fileName : fileName.substring(0, d);
    }

    static String sha256(Path p) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 配置解析结果（含"是否真应用"的如实登记）。 */
    static final class Cfg {
        boolean applied;
        boolean corrected;
        boolean tomlPresent;
        String detail;
        String tomlPath = "(未提供)";
        String tomlSha = "(未提供)";
        long fingerprint;
        TerrainParams params;
    }

    /** 一次面板采样的全部网格（row-major，索引 k = i*n + j，i=x/row）。 */
    static final class Mask {
        int n;
        boolean[] lake;
        boolean[] river;
        boolean[] sea;
        /** 契约失败域（tract 矩形）：渲染为斜纹/品红，<b>不计入任何水体</b>。 */
        boolean[] failed;
        double[] bed;
        double[] surf;
        int[] seen;
        int chunks;
        int lakeCols;
        int riverCols;
        double ms;
        /** 逐 chunk 性能：每项 {chX, chZ, ms, coldFlag, failedFlag}。 */
        List<double[]> perChunk = new ArrayList<>();
        int chunkGridTotal;
        final java.util.Set<Long> failedTracts = new java.util.HashSet<>();
        int failCount;
        int otherFailCount;
        final List<String> failSamples = new ArrayList<>();
    }

    /** 河格分量统计。 */
    static final class Comp {
        int count;
        int cells;
        int max;
        int smallCount;
        int smallCells;
        int orphanCount;
        int orphanCells;
        final List<Integer> sizes = new ArrayList<>();
    }
}
