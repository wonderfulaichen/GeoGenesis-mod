package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 生产深度证据探针（只读）：把 {@code GeoGenesisTerrain.getChunkCells} 的<b>合成后真值</b>
 * 落成逐列 CSV + 生产俯视图 + 水深热力图 + 汇总，供「河流深度与横断面」方案 P0 基线取证。
 *
 * <h2>口径（逐项对齐生产，不自行发明）</h2>
 * <ul>
 *   <li><b>ground</b> = {@code cell.height}（<b>已含</b>侵蚀与水文雕刻）；</li>
 *   <li><b>surface</b> = 生产落块实际使用的水面：河列 {@code riverSurfaceY}，
 *       海洋取 {@code max(·,seaLevel)}，瀑布唇口 {@code riverLipY} 再抬高；<b>干列记 NaN</b>（不虚构水面）；</li>
 *   <li><b>waterBlocks</b> = 整块水柱数，逐字对齐 {@code GeoGenesisGenerator.fillTerrainColumn}
 *       的 {@code (floor(ground), floor(fillTopBlock)]} ∩ {@code [-63, 319]}；</li>
 *   <li><b>river / lake</b> = {@link ProductionViewProbe#waterKind} 的生产水类（<b>不是</b> {@code riverType}）；
 *       另单列 {@code riverType} 原值 ⇐ {@code riverType!=0} <b>不等于</b>有水；</li>
 *   <li><b>eroded</b> = 雕刻前侵蚀地形 = {@code CellGenerator.erodedHeight(wu)}（与
 *       {@code extractFromTile} 同源同式）。对 {@code riverType!=0} 及可选 A 基线
 *       {@code river==1 && 当前waterBlocks==0} 的列取样，其余 NaN（仍不涵盖所有未知 fill-false 命中）；</li>
 *   <li>下挖量 {@code cut = eroded − ground}；{@code cut>4} 列数 / {@code maxCut} 记入 summary。</li>
 * </ul>
 *
 * <h2>出图诚实性</h2>
 * <p>{@code production.png} 复用 {@link ProductionViewProbe#waterKind} + {@link ViewSpec}
 * 的同一套色标/等高线函数（同包直调，不复制色值含义），因此与
 * {@code runProductionViewProbe} 的 production.png <b>逐像素同源</b>；
 * 探针另记 PNG sha256 与 dry/sea/lake/river 计数，可与该探针 metrics.txt 对账。
 * {@code depth.png} 是<b>本探针新增</b>的水深热力图（算法逐条写在 summary，非正典色标）。</p>
 *
 * <h2>横断面样本（标签只认证据）</h2>
 * <ul>
 *   <li>有 {@code widths.csv}（{@code WidthContinuityProbe} 产出）：按其 {@code half} 选
 *       <b>最宽</b>与<b>中位宽</b>列；OUTLET_HEAD 限 fromOutlet 且 node0/头8格内；
 *       LAKE_MOUTH/BOUNDARY_EXIT/JOIN 限对应 end 且距真实尾点8格内。
 *       非 outlet 最窄 trace 头仅标 SOURCE_UNVERIFIED，不猜真源头或 JOIN 接收侧。</li>
 *   <li>无 widths.csv：退回 {@code AUTO_PCA} —— 取窗口内水柱最厚的河列，用 24 块邻域内河列协方差
 *       定主轴（流向）⇒ 法向。同时记 {@code lambdaMinOverMax}，该值大即方向不可信。</li>
 *   <li>所有标签都写明来源字段原值；中心列不湿时如实记 {@code centerDepth=NaN}。</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp &lt;诊断运行期类路径&gt; com.geogenesis.worldgen.hydrology.model.RiverDepthViewProbe \
 *      &lt;outDir&gt; [radius=700] [seed] [cx] [cz]
 * </pre>
 * <p>可选系统属性：{@code -Dgeogenesis.depth.toml=run/config/geogenesis-common.toml}、
 * {@code -Dgeogenesis.depth.baselineCsv=<A/columns.csv>}（严格 x,z 行序）、
 * {@code -Dgeogenesis.depth.widths=<widths.csv>}（A/B 固定同文件）、{@code -Dgeogenesis.depth.csv=false}、
 * {@code -Dgeogenesis.depth.png=false}、{@code -Dgeogenesis.depth.sections=false}、
 * {@code -Dgeogenesis.allowNonCanonicalRadius=true}（非 700 半径需显式放行）。</p>
 *
 * <h2>退出码</h2>
 * <ul>
 *   <li>{@code 0} —— 采集完成且内部一致性检查通过（<b>不是</b>全周期验收）；</li>
 *   <li>{@code 1} —— 内部契约违约（列重复 / 水柱口径与生产 waterKind 不一致）；</li>
 *   <li>{@code 2} —— 采集失败（窗口内有列未被覆盖 / 零块）；</li>
 *   <li>{@code 3} —— FATAL（TOML 缺失、写盘失败、非有限高度等）。</li>
 * </ul>
 */
public final class RiverDepthViewProbe {

    /** 与 {@code GeoGenesisGenerator.WORLD_MIN_Y / WORLD_MAX_Y / SEA_LEVEL} 同值。 */
    static final int MIN_Y = -64, MAX_Y = 320, BASE_WATER_Y = 63;
    /** 生产水类（与 ProductionViewProbe 的私常量同值，仅用于本探针内部分支）。 */
    static final int DRY = 0, SEA = 1, LAKE = 2, RIVER = 3;
    /** 旧下挖上限（方案 §3.2「Hmax=4 是水深标定，不等于旧 C≤4 的下挖上限」）——只作统计阈值。 */
    static final double CUT_LIMIT = 4.0;
    /** 水深热力图色带上界（块）——方案首轮候选 Hmax=4，非物理定律。 */
    static final double DEPTH_RAMP_MAX = 4.0;
    static final int C_DRY = 0x141414, C_SEA = 0x0E2233, C_LAKE = 0x24405E, C_DRY_RIVER = 0xFF00FF;
    static final double EPS = 1e-9;
    static final String DEF_OUT = "build/river-depth/latest";

    static int checksFailed, collectFailed;
    /** production.png 的 SHA-256（未出图时 "NOT_WRITTEN"），供与 runProductionViewProbe 对账。 */
    static String pngSha = "NOT_WRITTEN";

    private RiverDepthViewProbe() { }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            Locale.setDefault(Locale.ROOT);
            try {
                selfTest();
            } catch (Throwable t) {
                System.out.println("SELF-TEST FAIL " + t);
                t.printStackTrace(System.out);
                System.exit(1);
            }
            return;
        }
        int code;
        try {
            Locale.setDefault(Locale.ROOT);
            code = run(args);
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    // ---------------- 主流程 ----------------

    static int run(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0].trim() : DEF_OUT);
        int radius = args.length > 1 ? Integer.parseInt(args[1].trim()) : ViewSpec.RADIUS;
        ViewSpec.assertCanonicalRadius(radius, !Boolean.getBoolean("geogenesis.allowNonCanonicalRadius"));
        long seed = args.length > 2 ? Long.parseLong(args[2].trim()) : ViewSpec.SEED;
        int cx = args.length > 3 ? Integer.parseInt(args[3].trim()) : ViewSpec.CENTER_X;
        int cz = args.length > 4 ? Integer.parseInt(args[4].trim()) : ViewSpec.CENTER_Z;
        Files.createDirectories(out);
        long t0 = System.nanoTime();

        ConfigInput cfg = loadConfig(Path.of(System.getProperty("geogenesis.depth.toml",
                "run/config/geogenesis-common.toml")), out, seed, cx, cz, radius);
        CellGenerator gen = new CellGenerator(cfg.params(), MIN_Y, MAX_Y);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);

        Grid grid = sample(terrain, cx, cz, radius);
        double tSample = secs(t0);
        erodedPass(gen, grid, cfg.params());
        double tEroded = secs(t0);

        if (Boolean.parseBoolean(System.getProperty("geogenesis.depth.png", "true"))) {
            writeProductionPng(grid, out.resolve("production.png"));
            writeDepthPng(grid, out.resolve("depth.png"));
            pngSha = sha256(out.resolve("production.png"));
        }
        if (Boolean.parseBoolean(System.getProperty("geogenesis.depth.csv", "true"))) {
            writeCsv(grid, out.resolve("columns.csv"));
        }
        double tCsv = secs(t0);
        SectionReport sections = sections(grid, out);
        double tAll = secs(t0);

        String report = report(cfg, terrain, grid, sections, out, seed, cx, cz, radius,
                tSample, tEroded, tCsv, tAll);
        Files.writeString(out.resolve("summary.txt"), report, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("provenance.txt"), grid.baselineEvidence + sectionLine(sections)
                        + memoryLine("endOfCollection")
                        + "performanceScope=whole-process observational run; NOT isolated performance; "
                        + "includes config/terrain/baseline reader/eroded/PNG/CSV/sections and background work\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        System.out.print(report);
        if (collectFailed > 0) return 2;
        if (checksFailed > 0) return 1;
        return 0;
    }

    static double secs(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    // ---------------- 配置（TOML 副本） ----------------

    record ConfigInput(TerrainParams params, String sha, boolean corrected, String fingerprint) { }

    /**
     * 与 ProductionViewProbe 同一套「副本 + Forge 校正」口径：<b>只在输出目录里改副本</b>，
     * 绝不保存回用户配置。缺文件直接抛 ⇒ FATAL（不静默用默认值）。
     */
    static ConfigInput loadConfig(Path toml, Path out, long seed, int cx, int cz, int radius) throws Exception {
        if (!Files.isRegularFile(toml)) throw new IllegalStateException("缺少生产配置: " + toml.toAbsolutePath());
        String sha = sha256(toml);
        Path copy = out.resolve("config-effective.toml");
        Files.copy(toml, out.resolve("config-input.toml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(toml, copy, StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        boolean correct = GeoGenesisConfig.SPEC.isCorrect(cfg);
        GeoGenesisConfig.SPEC.setConfig(cfg);
        if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("TOML 未加载");
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        cfg.save();
        Files.writeString(out.resolve("terrain-params.txt"), params.toString(), StandardCharsets.UTF_8);
        StringBuilder prov = new StringBuilder();
        prov.append("probe=RiverDepthViewProbe\nreadOnly=true\n");
        prov.append("toml=").append(toml.toAbsolutePath()).append('\n');
        prov.append("sha256=").append(sha).append("\ncorrectedByForge=").append(!correct).append('\n');
        prov.append("fingerprint=").append(GeoGenesisConfig.configFingerprint()).append('\n');
        prov.append("seed=").append(seed).append(" center=(").append(cx).append(',').append(cz)
                .append(") radius=").append(radius).append('\n');
        prov.append("horizontalScale=").append(params.horizontalScale())
                .append(" seaLevel=").append(params.seaLevel())
                .append(" minY=").append(params.minY()).append(" maxY=").append(params.maxY()).append('\n');
        appendClassProvenance(prov);
        prov.append(memoryLine("configLoaded"));
        System.getProperties().stringPropertyNames().stream().sorted()
                .filter(k -> k.startsWith("geogenesis.") || k.startsWith("hydro."))
                .forEach(k -> prov.append(k).append('=').append(System.getProperty(k)).append('\n'));
        Files.writeString(out.resolve("provenance.txt"), prov, StandardCharsets.UTF_8);
        return new ConfigInput(params, sha, !correct,
                String.valueOf(GeoGenesisConfig.configFingerprint()));
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static String classSha(Class<?> type) {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) return "MISSING";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) {
            return "ERROR";
        }
    }

    /** 不初始化可选类；SHA 来自实际 class resource，source 来自运行时 ProtectionDomain。 */
    static void appendClassProvenance(StringBuilder prov) {
        for (Class<?> type : new Class<?>[]{RiverDepthViewProbe.class, ModelHydrologyProvider.class,
                GeoGenesisTerrain.class, CellGenerator.class, Cell.class, ProductionViewProbe.class, ViewSpec.class}) {
            prov.append(type.getName()).append(" sha256=").append(classSha(type))
                    .append(" codeSource=").append(codeSource(type)).append('\n');
        }
        for (String name : new String[]{"RiverDepthProfile", "RiverCrossSection"}) {
            String qualified = RiverDepthViewProbe.class.getPackageName() + "." + name;
            try {
                Class<?> type = Class.forName(qualified, false, RiverDepthViewProbe.class.getClassLoader());
                prov.append(qualified).append(" sha256=").append(classSha(type))
                        .append(" codeSource=").append(codeSource(type)).append('\n');
            } catch (ClassNotFoundException | LinkageError e) {
                prov.append(qualified).append(" sha256=MISSING codeSource=UNAVAILABLE reason=")
                        .append(e).append('\n');
            }
        }
    }

    static String codeSource(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            return source == null ? "UNAVAILABLE" : source.getLocation().toExternalForm();
        } catch (SecurityException e) {
            return "UNAVAILABLE:" + e;
        }
    }

    /** JVM 内存而非进程 RSS；各池 peak 非同时峰值，不能相加当作整堆峰值。 */
    static String memoryLine(String phase) {
        var memory = ManagementFactory.getMemoryMXBean();
        StringBuilder sb = new StringBuilder("memory phase=" + phase + " heapCurrentBytes="
                + memory.getHeapMemoryUsage().getUsed() + " heapCommittedBytes="
                + memory.getHeapMemoryUsage().getCommitted() + " nonHeapCurrentBytes="
                + memory.getNonHeapMemoryUsage().getUsed() + "\n");
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            var peak = pool.getPeakUsage();
            var current = pool.getUsage();
            sb.append("memoryPool name=").append(pool.getName()).append(" type=").append(pool.getType())
                    .append(" currentBytes=").append(current == null ? -1 : current.getUsed())
                    .append(" peakBytes=").append(peak == null ? -1 : peak.getUsed()).append('\n');
        }
        sb.append("memoryScope=JVM pools since process start; NOT process RSS; pool peaks are not simultaneous\n");
        return sb.toString();
    }

    // ---------------- 采样 ----------------

    /** 窗口逐列生产真值。像素布局与 ProductionViewProbe 一致：{@code k = px*n+pz}，x 为行。 */
    static final class Grid {
        final int n, cx, cz, radius;
        final double[] ground, surface, depth, eroded;
        final byte[] kind, riverType;
        final int[] blocks;
        int chunks;
        String sha = "";
        String baselineEvidence = "baselineCsv=NOT_REQUESTED\n";

        Grid(int n, int cx, int cz, int radius) {
            this.n = n;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            int m = n * n;
            ground = new double[m];
            surface = new double[m];
            depth = new double[m];
            eroded = new double[m];
            kind = new byte[m];
            riverType = new byte[m];
            blocks = new int[m];
            Arrays.fill(surface, Double.NaN);
            Arrays.fill(depth, Double.NaN);
            Arrays.fill(eroded, Double.NaN);
        }

        boolean inWindow(double x, double z) {
            return x >= cx - radius && x <= cx + radius && z >= cz - radius && z <= cz + radius;
        }
    }

    static Grid sample(GeoGenesisTerrain terrain, int cx, int cz, int radius) throws Exception {
        Grid g = new Grid(2 * radius + 1, cx, cz, radius);
        int n = g.n;
        boolean on = terrain.riversEnabled();
        double sea = terrain.seaLevel();
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        ByteBuffer values = ByteBuffer.allocate(40);
        boolean[] seen = new boolean[n * n];
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                Cell[] cells = terrain.getChunkCells(chX, chZ);
                g.chunks++;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int px = chX * 16 + lx - (cx - radius);
                        int pz = chZ * 16 + lz - (cz - radius);
                        if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                        putColumn(g, px * n + pz, cells[lx * 16 + lz], on, sea, seen, values, hash);
                    }
                }
            }
        }
        for (boolean covered : seen) if (!covered) collectFailed++;
        g.sha = HexFormat.of().formatHex(hash.digest());
        return g;
    }

    static void putColumn(Grid g, int k, Cell cell, boolean on, double sea, boolean[] seen,
                          ByteBuffer values, MessageDigest hash) {
        if (seen[k]) {
            checksFailed++;
            System.out.println("[CHECK] 列重复 k=" + k + "（px=" + k / g.n + ",pz=" + k % g.n + "）");
            return;
        }
        seen[k] = true;
        g.ground[k] = cell.height;
        g.riverType[k] = cell.riverType;
        int kind = ProductionViewProbe.waterKind(cell, on, sea);
        g.kind[k] = (byte) kind;
        double top = surfaceTop(cell, on, sea);
        int blocks = waterBlocks(cell, top, kind != DRY);
        g.blocks[k] = blocks;
        if ((blocks > 0) != (kind != DRY)) {
            checksFailed++;
            System.out.println("[CHECK] 水柱口径与生产 waterKind 不一致 k=" + k
                    + " kind=" + kind + " blocks=" + blocks);
        }
        if (kind != DRY) {
            g.surface[k] = top;
            g.depth[k] = top - cell.height;
        }
        values.clear();
        values.putDouble(cell.height).putDouble(cell.riverSurfaceY).putDouble(cell.riverLipY)
                .putInt(cell.riverType).putInt(kind).putInt(blocks);
        hash.update(values.array());
    }

    /**
     * 生产落块实际使用的水面（连续值）：逐项抄 {@code fillTerrainColumn} 的
     * {@code waterTop} / {@code seaLevel} 补灌 / {@code riverLipY} 瀑布抬高。
     * 干列返回的 63 <b>不代表有水</b> —— 调用方必须同时看 {@code waterKind}。
     */
    static double surfaceTop(Cell cell, boolean hydrologyOn, double seaLevel) {
        boolean river = hydrologyOn && cell.riverType != 0 && cell.riverSurfaceY > cell.height;
        boolean ocean = hydrologyOn && cell.height < seaLevel;
        double top = river ? cell.riverSurfaceY : BASE_WATER_Y;
        if (ocean) top = Math.max(top, seaLevel);
        if (river && cell.riverLipY > top) top = cell.riverLipY;
        return top;
    }

    /** 整块水柱数 = {@code |(floor(ground), floor(fillTopBlock)] ∩ [-63,319]|}。 */
    static int waterBlocks(Cell cell, double top, boolean wet) {
        if (!wet) return 0;
        double first = Math.max(MIN_Y + 1, Math.floor(cell.height) + 1);
        double last = Math.min(MAX_Y - 1, Math.floor(top));
        return first > last ? 0 : (int) (last - first + 1);
    }

    /**
     * 雕刻前侵蚀地形（{@code cut} 的基准）。
     *
     * <p>★ 坐标必须是 {@code block × (1/hs)}（{@code extractFromTile} 的写法），
     * <b>不是</b> {@code block/hs} —— 两者末位可差 1 ulp ⇒ 侵蚀 tile 边界 blend 取到的
     * 邻居不同 ⇒ 与生产 cell 对不上。另以 A 基线掩码补采当前已无水的旧河列。</p>
     */
    static void erodedPass(CellGenerator gen, Grid g, TerrainParams params) throws Exception {
        boolean[] baselineDry = baselineMask(g);
        double hs = params.horizontalScale();
        double invHs = (hs > 0.01 && hs != 1.0) ? 1.0 / hs : 1.0;
        int x0 = g.cx - g.radius, z0 = g.cz - g.radius;
        // 保持原 px→pz 查询顺序；reader 只加掩码，不重排原采样或 CSV 输出。
        for (int px = 0; px < g.n; px++) {
            for (int pz = 0; pz < g.n; pz++) {
                int k = px * g.n + pz;
                if (g.riverType[k] == 0 && (baselineDry == null || !baselineDry[k])) continue;
                g.eroded[k] = gen.erodedHeight((x0 + px) * invHs, (z0 + pz) * invHs);
            }
        }
    }

    static boolean[] baselineMask(Grid g) throws Exception {
        String value = System.getProperty("geogenesis.depth.baselineCsv");
        if (value == null || value.isBlank()) return null;
        Path csv = Path.of(value);
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            boolean[] mask = readBaselineMask(reader, g);
            g.baselineEvidence = "baselineCsv=" + csv.toAbsolutePath() + " sha256=" + sha256(csv)
                    + "\n" + g.baselineEvidence;
            return mask;
        }
    }

    /** 顺序流读 A，严格检查 x→z 行序；不使用 A 的 eroded/ground 值冒充当前生产采样。 */
    static boolean[] readBaselineMask(BufferedReader reader, Grid g) throws Exception {
        String header = reader.readLine();
        if (header == null) throw new IllegalArgumentException("Empty baseline CSV");
        List<String> names = Arrays.asList(header.replace("\uFEFF", "").split(",", -1));
        int xi = names.indexOf("x"), zi = names.indexOf("z"), ri = names.indexOf("river");
        if (xi < 0 || zi < 0 || ri < 0) throw new IllegalArgumentException("Baseline needs x,z,river");
        int required = Math.max(xi, Math.max(zi, ri));
        boolean[] mask = new boolean[g.n * g.n];
        long rows = 0, river = 0, dry = 0, extra = 0, outside = 0;
        int lastX = 0, lastZ = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = line.split(",", -1);
            if (f.length <= required) throw new IllegalArgumentException("Short baseline row " + (rows + 2));
            int x = Integer.parseInt(f[xi]), z = Integer.parseInt(f[zi]);
            if (rows > 0 && (x < lastX || x == lastX && z <= lastZ)) {
                throw new IllegalArgumentException("Baseline duplicate/out-of-order row " + (rows + 2));
            }
            rows++; lastX = x; lastZ = z;
            if (!g.inWindow(x, z)) { outside++; continue; }
            if (!"1".equals(f[ri].trim())) continue;
            river++;
            int k = (x - (g.cx - g.radius)) * g.n + z - (g.cz - g.radius);
            if (g.blocks[k] != 0) continue;
            mask[k] = true; dry++;
            if (g.riverType[k] == 0) extra++;
        }
        g.baselineEvidence = "baselineRows=" + rows + " outsideWindow=" + outside
                + " baselineRiverInWindow=" + river + " baselineRiverNowZeroBlocks=" + dry
                + " additionalErodedTypeZero=" + extra
                + " readerOrder=x,z(strict); erodedQueryOrder=px,pz(unchanged)\n"
                + "baselineScope=A river==1 AND current waterBlocks==0; not every fill-false hit is identifiable\n";
        return mask;
    }

    // ---------------- CSV ----------------

    static void writeCsv(Grid g, Path target) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write("x,z,ground,surface,waterBlocks,river,lake,eroded,riverType,riverFlag,ocean,depth,dryRiverFlag\n");
            int x0 = g.cx - g.radius, z0 = g.cz - g.radius;
            StringBuilder sb = new StringBuilder(160);
            for (int px = 0; px < g.n; px++) {
                for (int pz = 0; pz < g.n; pz++) {
                    int k = px * g.n + pz;
                    int kind = g.kind[k];
                    boolean ocean = kind == SEA;
                    boolean flag = g.riverType[k] != 0;
                    sb.setLength(0);
                    sb.append(x0 + px).append(',').append(z0 + pz).append(',')
                            .append(fmt(g.ground[k])).append(',').append(fmt(g.surface[k])).append(',')
                            .append(g.blocks[k]).append(',')
                            .append(kind == RIVER ? 1 : 0).append(',').append(kind == LAKE ? 1 : 0).append(',')
                            .append(fmt(g.eroded[k])).append(',')
                            .append(g.riverType[k]).append(',')
                            .append(flag ? 1 : 0).append(',').append(ocean ? 1 : 0).append(',')
                            .append(fmt(g.depth[k])).append(',')
                            .append(flag && g.blocks[k] == 0 ? 1 : 0).append('\n');
                    w.write(sb.toString());
                }
            }
        }
    }

    /** 4 位小数定长（逐行 {@code String.format} 在 1.9M 行上过慢）；NaN/Inf 如实写 {@code NaN}。 */
    static String fmt(double v) {
        if (!Double.isFinite(v)) return "NaN";
        long q = Math.round(v * 10000.0);
        StringBuilder sb = new StringBuilder(16);
        if (q < 0) { sb.append('-'); q = -q; }
        sb.append(q / 10000).append('.');
        long f = q % 10000;
        sb.append((char) ('0' + f / 1000)).append((char) ('0' + f / 100 % 10))
                .append((char) ('0' + f / 10 % 10)).append((char) ('0' + f % 10));
        return sb.toString();
    }

    // ---------------- 出图 ----------------

    /** 与 {@code ProductionViewProbe.writeImage} 同一套分支与色标（同包直调 ViewSpec）。 */
    static void writeProductionPng(Grid g, Path target) throws Exception {
        BufferedImage image = new BufferedImage(g.n, g.n, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < g.n; x++) {
            for (int z = 0; z < g.n; z++) {
                int k = x * g.n + z;
                int rgb = switch (g.kind[k]) {
                    case SEA -> ViewSpec.SEA_COLOR;
                    case LAKE -> ViewSpec.LAKE_COLOR;
                    case RIVER -> ViewSpec.RIVER_COLOR;
                    default -> ViewSpec.contourFixed(g.ground, g.n, x, z);
                };
                image.setRGB(x, z, rgb);
            }
        }
        write(image, target);
    }

    /**
     * 水深热力图（<b>本探针新增，非正典色标</b>）。
     *
     * <ul>
     *   <li>干列 {@code 0x141414}；海洋 {@code 0x0E2233}；湖泊 {@code 0x24405E}（都压暗，让河深显形）；</li>
     *   <li><b>河列有水</b>：按连续水深 {@code surface-ground} 在
     *       {@code [0,4]} 上线性插值 {@code 0x00E5D0→0x3CC878→0xC8C83C→0xF08228→0xDC2828}（浅→深）；</li>
     *   <li><b>河旗标但整块水柱为 0</b>：洋红 {@code 0xFF00FF} —— 专测「{@code riverType!=0} 不等于有水」。</li>
     * </ul>
     */
    static void writeDepthPng(Grid g, Path target) throws Exception {
        BufferedImage image = new BufferedImage(g.n, g.n, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < g.n; x++) {
            for (int z = 0; z < g.n; z++) {
                int k = x * g.n + z;
                int kind = g.kind[k];
                int rgb;
                if (kind == RIVER) rgb = depthColor(g.depth[k]);
                else if (kind == LAKE) rgb = C_LAKE;
                else if (kind == SEA) rgb = C_SEA;
                else rgb = (g.riverType[k] != 0 && g.blocks[k] == 0) ? C_DRY_RIVER : C_DRY;
                image.setRGB(x, z, rgb);
            }
        }
        write(image, target);
    }

    static int depthColor(double depth) {
        double t = Math.max(0, Math.min(DEPTH_RAMP_MAX, Double.isFinite(depth) ? depth : 0)) / DEPTH_RAMP_MAX;
        if (t < 0.25) return lerp(0x00E5D0, 0x3CC878, t / 0.25);
        if (t < 0.50) return lerp(0x3CC878, 0xC8C83C, (t - 0.25) / 0.25);
        if (t < 0.75) return lerp(0xC8C83C, 0xF08228, (t - 0.50) / 0.25);
        return lerp(0xF08228, 0xDC2828, (t - 0.75) / 0.25);
    }

    static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(bl);
    }

    static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static void write(BufferedImage image, Path target) throws Exception {
        if (!ImageIO.write(image, "png", target.toFile())) throw new IllegalStateException("PNG writer missing");
    }

    // ---------------- 横断面样本 ----------------

    /** {@code widths.csv} 的一个节点（字段全部来自该文件，不推断）。 */
    record WidthNode(int rx, int rz, int poly, int node, double x, double z,
                     double half, double arc, String end, boolean fromOutlet,
                     int succ, boolean succKnown) { }

    /** 一条待采样的法向剖面。 */
    record Sec(String label, int poly, int node, double x, double z, double half,
               double nx, double nz, int extent, String evidence) { }

    record SectionReport(String mode, String source, List<String> lines, int rows, int outOfWindow) { }

    static SectionReport sections(Grid g, Path out) throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("geogenesis.depth.sections", "true"))) {
            return new SectionReport("DISABLED", "-", List.of(), 0, 0);
        }
        Path widths = widthsPath();
        List<Sec> chosen;
        String mode, source;
        if (widths != null && Files.isRegularFile(widths)) {
            mode = "WIDTHS_CSV";
            source = widths.toAbsolutePath() + " sha256=" + sha256(widths)
                    + " (same widths CSV + window => same coordinates; selection ignores current wetness)";
            chosen = fromWidths(widths, g);
        } else {
            mode = "AUTO_PCA";
            source = "none(widths.csv 不存在)";
            chosen = autoPca(g);
        }
        List<String> lines = new ArrayList<>();
        int rows = 0, outOfWindow = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("section.csv"), StandardCharsets.UTF_8)) {
            w.write("sample,label,poly,node,offset,x,z,ground,surface,waterBlocks,river,lake,depth,"
                    + "eroded,riverType\n");
            for (int i = 0; i < chosen.size(); i++) {
                Sec s = chosen.get(i);
                int[] cnt = new int[2];
                String line = emitSection(w, g, s, i, cnt);
                if (line != null) lines.add(line);
                rows += cnt[0];
                outOfWindow += cnt[1];
            }
        }
        return new SectionReport(mode, source, lines, rows, outOfWindow);
    }

    static Path widthsPath() {
        String p = System.getProperty("geogenesis.depth.widths");
        if (p != null && !p.isBlank()) return Path.of(p);
        Path def = Path.of("build", "river-local-contract", "width-continuity", "widths.csv");
        return Files.isRegularFile(def) ? def : null;
    }

    /**
     * 读完整 {@code widths.csv} 保留真头尾；选样时才按本窗口裁切。
     *
     * <p>★ 兼容两种表头：现行 12 列（{@code ...limit,succTrace}）与 2026-10 以前的 10 列
     * （无 {@code limit/succTrace}）。缺 {@code succTrace} 时 succ 记 {@code -1}（= 无证据），
     * <b>不当成"有 JOIN"</b>，也不猜。</p>
     */
    static List<Sec> fromWidths(Path csv, Grid g) throws Exception {
        Map<String, List<WidthNode>> polys = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.startsWith("rx,")) continue;
                }
                String[] f = line.split(",");
                if (f.length < 10) continue;
                double x = Double.parseDouble(f[4]), z = Double.parseDouble(f[5]);
                // 保留窗外端点，不能把裁切后的末点误称湖口/边界端口。
                int succ = f.length >= 12 ? Integer.parseInt(f[11].trim()) : -1;
                WidthNode nd = new WidthNode(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), x, z,
                        Double.parseDouble(f[6]), Double.parseDouble(f[7]), f[8],
                        "true".equalsIgnoreCase(f[9]), succ, f.length >= 12);
                polys.computeIfAbsent(nd.rx() + ":" + nd.rz() + ":" + nd.poly(),
                        key -> new ArrayList<>()).add(nd);
            }
        }
        return chooseSections(polys, g);
    }

    /** 固定 CSV/窗口确定样本，不依赖 A/B 当前水深或湿列；全折线用于确认真头尾。 */
    static List<Sec> chooseSections(Map<String, List<WidthNode>> polys, Grid g) {
        List<WidthNode> all = new ArrayList<>(), heads = new ArrayList<>(), tails = new ArrayList<>();
        for (List<WidthNode> poly : polys.values()) {
            poly.sort((a, b) -> Integer.compare(a.node(), b.node()));
            double tailArc = poly.get(poly.size() - 1).arc();
            for (WidthNode n : poly) {
                if (!g.inWindow(n.x(), n.z()) || !Double.isFinite(n.half())) continue;
                all.add(n);
                if (nearHead(n)) heads.add(n);
                if (Double.isFinite(tailArc) && tailArc - n.arc() >= -EPS
                        && tailArc - n.arc() <= 8 + EPS) tails.add(n);
            }
        }
        List<Sec> out = new ArrayList<>();
        WidthNode first = widestClean(all);
        final WidthNode widest = first == null ? pickWidest(all, n -> true) : first;
        addSec(out, polys, widest, "WIDEST_REACH_SAMPLE");
        WidthNode mid = pickNearest(all, n -> far(n, widest), medianHalf(all));
        addSec(out, polys, mid, "MEDIAN_WIDTH_REACH_SAMPLE");
        WidthNode outlet = pickWidest(heads, WidthNode::fromOutlet);
        if (outlet != null) addSec(out, polys, outlet, "OUTLET_HEAD");
        else addSec(out, polys, pickWidest(all, WidthNode::fromOutlet), "OUTLET_REACH_SAMPLE");
        // 非 outlet 的 trace 头不自动等于真源头（可能串联/跨区来流）；标签明确证据边界。
        WidthNode source = null;
        for (WidthNode n : heads) {
            if (!n.fromOutlet() && (source == null || n.half() < source.half())) source = n;
        }
        addSec(out, polys, source, "MIN_WIDTH_TRACE_HEAD_SOURCE_UNVERIFIED");
        addSec(out, polys, pickWidest(tails, n -> "MOUTH_LAKE".equals(n.end())), "LAKE_MOUTH");
        addSec(out, polys, pickWidest(tails, n -> "BOUNDARY_EXIT".equals(n.end())), "BOUNDARY_EXIT_SAMPLE");
        addSec(out, polys, pickWidest(tails, n -> "JOIN".equals(n.end())), "JOIN_UPSTREAM_TAIL_SAMPLE");
        return out;
    }

    static boolean nearHead(WidthNode n) {
        return n.node() == 0 || Double.isFinite(n.arc()) && n.arc() >= 0 && n.arc() <= 8 + EPS;
    }

    /** 只是优先选 fromOutlet=false / succ<0，不据此声称远离全部接点。 */
    static WidthNode widestClean(List<WidthNode> all) {
        return pickWidest(all, n -> !n.fromOutlet() && n.succ() < 0);
    }

    static boolean far(WidthNode a, WidthNode b) {
        return b == null || Math.hypot(a.x() - b.x(), a.z() - b.z()) >= 32;
    }

    interface Filter { boolean test(WidthNode n); }

    /** 满足条件的<b>最宽</b>节点（half 必须有限）。 */
    static WidthNode pickWidest(List<WidthNode> all, Filter f) {
        WidthNode best = null;
        for (WidthNode n : all) {
            if (!Double.isFinite(n.half()) || !f.test(n)) continue;
            if (best == null || n.half() > best.half()) best = n;
        }
        return best;
    }

    /** 满足条件的、half <b>最接近 target</b> 的节点。 */
    static WidthNode pickNearest(List<WidthNode> all, Filter f, double target) {
        WidthNode best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (WidthNode n : all) {
            if (!Double.isFinite(n.half()) || !f.test(n)) continue;
            double d = Math.abs(n.half() - target);
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    static double medianHalf(List<WidthNode> all) {
        List<Double> h = new ArrayList<>();
        for (WidthNode n : all) if (Double.isFinite(n.half())) h.add(n.half());
        if (h.isEmpty()) return Double.NaN;
        double[] a = new double[h.size()];
        for (int i = 0; i < a.length; i++) a[i] = h.get(i);
        Arrays.sort(a);
        return a[(a.length - 1) / 2];
    }

    static void addSec(List<Sec> out, Map<String, List<WidthNode>> polys, WidthNode n, String label) {
        if (n == null) return;
        // 场景允许同坐标复用；不能因靠近 widest 样本就把湖口/河头标签静默丢掉。
        List<WidthNode> poly = polys.get(n.rx() + ":" + n.rz() + ":" + n.poly());
        double[] nor = normalOf(poly, n);
        if (nor == null) return;
        int extent = (int) Math.min(40, Math.max(6, Math.ceil(n.half() * 3) + 4));
        String ev = "rx=" + n.rx() + " rz=" + n.rz() + " poly=" + n.poly() + " node=" + n.node()
                + " half=" + fmt(n.half()) + " arc=" + fmt(n.arc()) + " end=" + n.end()
                + " fromOutlet=" + n.fromOutlet()
                + " succTrace=" + (n.succKnown() ? String.valueOf(n.succ()) : "NA(旧表头无此列)")
                + " tailDistance=" + fmt(poly.get(poly.size() - 1).arc() - n.arc());
        out.add(new Sec(label, n.poly(), n.node(), n.x(), n.z(), n.half(), nor[0], nor[1], extent, ev));
    }

    /** 折线上的法向：切线取相邻节点差（只剩一端时用单侧差分），法向 = 切线旋转 90°。 */
    static double[] normalOf(List<WidthNode> poly, WidthNode n) {
        if (poly == null || poly.size() < 2) return null;
        List<WidthNode> sorted = new ArrayList<>(poly);
        sorted.sort((a, b) -> Integer.compare(a.node(), b.node()));
        int i = -1;
        for (int j = 0; j < sorted.size(); j++) if (sorted.get(j).node() == n.node()) { i = j; break; }
        if (i < 0) return null;
        WidthNode a = i > 0 ? sorted.get(i - 1) : sorted.get(i);
        WidthNode b = i + 1 < sorted.size() ? sorted.get(i + 1) : sorted.get(i);
        double tx = b.x() - a.x(), tz = b.z() - a.z();
        double len = Math.hypot(tx, tz);
        if (!(len > EPS)) return null;
        return new double[]{-tz / len, tx / len};
    }

    /** 无 widths.csv 时：取水柱最厚的河列，用邻域河列协方差定主轴（流向）⇒ 法向。 */
    static List<Sec> autoPca(Grid g) {
        int best = -1, bestBlocks = 0;
        int m = g.n * g.n;
        for (int k = 0; k < m; k++) {
            if (g.kind[k] == RIVER && g.blocks[k] > bestBlocks) { bestBlocks = g.blocks[k]; best = k; }
        }
        if (best < 0) return List.of();
        int bx = best / g.n, bz = best % g.n;
        int r = 24;
        double sx = 0, sz = 0, sxx = 0, sxz = 0, szz = 0;
        int cnt = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int px = bx + dx, pz = bz + dz;
                if (px < 0 || pz < 0 || px >= g.n || pz >= g.n) continue;
                if (g.kind[px * g.n + pz] != RIVER) continue;
                sx += dx; sz += dz; sxx += dx * dx; sxz += dx * dz; szz += dz * dz; cnt++;
            }
        }
        if (cnt < 8) return List.of();
        double mx = sx / cnt, mz = sz / cnt;
        double cxx = sxx / cnt - mx * mx, cxz = sxz / cnt - mx * mz, czz = szz / cnt - mz * mz;
        // λ1,2 = (cxx+czz)/2 ± sqrt(((cxx−czz)/2)² + cxz²)；主轴角 = ½·atan2(2cxz, cxx−czz)
        double det = Math.sqrt(Math.pow((cxx - czz) / 2, 2) + cxz * cxz);
        double half = Math.sqrt(Math.max(0, (cxx + czz) / 2 + det));
        double half2 = Math.sqrt(Math.max(0, (cxx + czz) / 2 - det));
        double theta = 0.5 * Math.atan2(2 * cxz, cxx - czz);
        double nx = -Math.sin(theta), nz = Math.cos(theta);
        double lmin = half2 / Math.max(half, EPS);
        int extent = Math.min(40, Math.max(8, 3 * bestBlocks + 4));
        String ev = "PCA r=" + r + " cols=" + cnt + " lambdaMinOverMax=" + fmt(lmin)
                + " maxWaterBlocks=" + bestBlocks
                + "（lambdaMinOverMax 越小方向越可用；接近 1 表示邻域不像一条线，别当法向）";
        return List.of(new Sec("AUTO_PCA_MAXBLOCKS", -1, -1, g.cx - g.radius + bx, g.cz - g.radius + bz,
                half, nx, nz, extent, ev));
    }

    /** 写一条剖面并返回摘要行；中心列不湿时如实写 centerDepth=NaN。 */
    static String emitSection(BufferedWriter w, Grid g, Sec s, int idx, int[] cnt) throws Exception {
        StringBuilder sb = new StringBuilder(160);
        double centerDepth = Double.NaN;
        int wet = 0, maxBlocks = 0, outWin = 0, peakOffset = Integer.MIN_VALUE;
        double maxDepth = 0;
        for (int off = -s.extent(); off <= s.extent(); off++) {
            int wx = (int) Math.round(s.x() + s.nx() * off);
            int wz = (int) Math.round(s.z() + s.nz() * off);
            int px = wx - (g.cx - g.radius), pz = wz - (g.cz - g.radius);
            boolean inside = px >= 0 && pz >= 0 && px < g.n && pz < g.n;
            int k = inside ? px * g.n + pz : -1;
            int kind = inside ? g.kind[k] : DRY;
            if (!inside) { outWin++; cnt[1]++; }
            sb.setLength(0);
            sb.append(idx).append(',').append(s.label().replace(',', ';')).append(',')
                    .append(s.poly()).append(',').append(s.node()).append(',')
                    .append(off).append(',').append(wx).append(',').append(wz).append(',')
                    .append(inside ? fmt(g.ground[k]) : "NaN").append(',')
                    .append(inside ? fmt(g.surface[k]) : "NaN").append(',')
                    .append(inside ? g.blocks[k] : 0).append(',')
                    .append(kind == RIVER ? 1 : 0).append(',').append(kind == LAKE ? 1 : 0).append(',')
                    .append(inside ? fmt(g.depth[k]) : "NaN").append(',')
                    .append(inside ? fmt(g.eroded[k]) : "NaN").append(',')
                    .append(inside ? g.riverType[k] : 0).append('\n');
            w.write(sb.toString());
            cnt[0]++;
            if (inside && (kind == RIVER || kind == LAKE)) {
                wet++;
                maxBlocks = Math.max(maxBlocks, g.blocks[k]);
            }
            if (inside && Double.isFinite(g.depth[k]) && g.depth[k] > maxDepth) {
                maxDepth = g.depth[k];
                peakOffset = off;
            }
            if (off == 0 && inside && Double.isFinite(g.depth[k])) centerDepth = g.depth[k];
        }
        return "section " + idx + " label=" + s.label() + " at=(" + fmt(s.x()) + "," + fmt(s.z())
                + ") normal=(" + fmt(s.nx()) + "," + fmt(s.nz()) + ") extent=" + s.extent()
                + " half=" + fmt(s.half()) + " wetCols=" + wet + " maxBlocks=" + maxBlocks
                + " maxDepth=" + fmt(maxDepth) + " peakOffset="
                + (peakOffset == Integer.MIN_VALUE ? "NA" : String.valueOf(peakOffset))
                + " centerDepth=" + fmt(centerDepth)
                + " outOfWindow=" + outWin + " evidence=[" + s.evidence() + "]";
    }

    // ---------------- 汇总 ----------------

    /** 可增长 double 缓冲（统计用，避免为百万列预分配）。 */
    static final class Buf {
        double[] a = new double[1024];
        int n;

        void add(double v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        double[] sorted() {
            double[] c = Arrays.copyOf(a, n);
            Arrays.sort(c);
            return c;
        }
    }

    static String stats(String name, double[] sorted) {
        if (sorted.length == 0) return name + " n=0";
        double sum = 0;
        for (double v : sorted) sum += v;
        return String.format(Locale.ROOT, "%s n=%d min=%.4f p50=%.4f p95=%.4f max=%.4f mean=%.4f",
                name, sorted.length, sorted[0], pct(sorted, 0.50), pct(sorted, 0.95),
                sorted[sorted.length - 1], sum / sorted.length);
    }

    static double pct(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        int i = (int) Math.ceil(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static String report(ConfigInput cfg, GeoGenesisTerrain terrain, Grid g, SectionReport sections,
                         Path out, long seed, int cx, int cz, int radius,
                         double tSample, double tEroded, double tCsv, double tAll) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("=== 河流深度/横断面生产证据（离线合成，非 Forge 实机验收）===\n");
        sb.append(String.format(Locale.ROOT,
                "seed=%d centre=(%d,%d) radius=%d image=%dx%d imageStep=1 chunks=%d%n",
                seed, cx, cz, radius, g.n, g.n, g.chunks));
        sb.append("configLoaded=true tomlSHA256=").append(cfg.sha())
                .append(" correctedByForge=").append(cfg.corrected())
                .append(" fingerprint=").append(cfg.fingerprint()).append('\n');
        sb.append("sampling=GeoGenesisTerrain.getChunkCells（含侵蚀 + 水文雕刻）；"
                + "surface/fillTop 逐项对齐 GeoGenesisGenerator.fillTerrainColumn\n");
        sb.append("eroded=CellGenerator.erodedHeight(wu)，wu=block×(1/hs)（与 extractFromTile 同式）；"
                + "riverType!=0 或 baseline A river==1 且当前 waterBlocks==0 的列取样，其余 NaN\n");
        sb.append(g.baselineEvidence);
        sb.append("river/lake=生产水类（ProductionViewProbe.waterKind），riverType 原值单列；"
                + "riverType!=0 不等于有水\n");
        sb.append(String.format(Locale.ROOT,
                "timing cumulativeSample=%.1fs cumulativeEroded=%.1fs cumulativeCsv=%.1fs total=%.1fs%n",
                tSample, tEroded, tCsv, tAll));
        sb.append("performanceScope=observational whole-process wall time; NOT isolated performance; "
                + "includes baseline I/O, evidence collection and background terrain work\n");
        sb.append(memoryLine("summary"));
        sb.append("columnsSHA256=").append(g.sha)
                .append("（height/riverSurfaceY/riverLipY/riverType/水类/水柱 逐列 raw-bits 摘要，A/B 逐位对比用）\n");
        sb.append("productionPngSHA256=").append(pngSha)
                .append("（同 seed/中心/半径/TOML 下应与 runProductionViewProbe 的 production.png 一致）\n");
        sb.append(kindLine(g));
        sb.append(blockLine(g));
        sb.append(depthLine(g));
        sb.append(cutLine(g));
        sb.append(sectionLine(sections));
        sb.append(fileLine(out)).append('\n');
        sb.append("NOT_VERIFIED=Forge 方块放置/洞穴/装饰/流体 tick；离线整块水柱判据不替代实机验收；"
                + "本探针不给全周期 PASS\n");
        return sb.toString();
    }

    static String kindLine(Grid g) {
        int[] counts = new int[4];
        int riverFlag = 0, dryFlag = 0, ocean = 0;
        int m = g.n * g.n;
        for (int k = 0; k < m; k++) {
            counts[g.kind[k]]++;
            if (g.riverType[k] != 0) riverFlag++;
            if (g.riverType[k] != 0 && g.blocks[k] == 0) dryFlag++;
            if (g.kind[k] == SEA) ocean++;
        }
        return String.format(Locale.ROOT,
                "columns=%d uncovered=%d dry=%d sea=%d lake=%d river=%d | riverType!=0=%d "
                        + "riverFlagButZeroBlocks=%d ocean=%d%n",
                m, collectFailed, counts[DRY], counts[SEA], counts[LAKE], counts[RIVER],
                riverFlag, dryFlag, ocean);
    }

    static String blockLine(Grid g) {
        int[] hist = new int[9];
        long sum = 0;
        int m = g.n * g.n;
        for (int k = 0; k < m; k++) {
            int b = g.blocks[k];
            hist[Math.min(8, Math.max(0, b))]++;
            sum += b;
        }
        return "waterBlocksHistogram[0..7,8+]=[" + hist[0] + "," + hist[1] + "," + hist[2] + ","
                + hist[3] + "," + hist[4] + "," + hist[5] + "," + hist[6] + "," + hist[7] + ","
                + hist[8] + "] sumBlocks=" + sum + "\n";
    }

    static String depthLine(Grid g) {
        Buf riverDepth = new Buf(), lakeDepth = new Buf(), seaDepth = new Buf();
        int deepButNoBlock = 0;
        int m = g.n * g.n;
        for (int k = 0; k < m; k++) {
            int kind = g.kind[k];
            if (kind == RIVER) riverDepth.add(g.depth[k]);
            else if (kind == LAKE) lakeDepth.add(g.depth[k]);
            else if (kind == SEA) seaDepth.add(g.depth[k]);
            // 连续水深 > 0.5 却没有任何整数水块 —— 方案 §2 强调的「不能靠放宽 fill 掩盖」那一类
            if (Double.isFinite(g.depth[k]) && g.depth[k] > 0.5 && g.blocks[k] == 0) deepButNoBlock++;
        }
        return stats("continuousDepthRiver", riverDepth.sorted()) + "\n"
                + stats("continuousDepthLake", lakeDepth.sorted()) + "\n"
                + stats("continuousDepthSea", seaDepth.sorted()) + "\n"
                + "depthGt0.5ButZeroBlocks=" + deepButNoBlock + "（有连续水深但放不出整块水柱）\n";
    }

    static String cutLine(Grid g) {
        Buf cut = new Buf();
        int over4 = 0, negative = 0, sampled = 0;
        double maxCut = Double.NaN, sum = 0;
        int m = g.n * g.n;
        for (int k = 0; k < m; k++) {
            if (!Double.isFinite(g.eroded[k])) continue;
            sampled++;
            double c = g.eroded[k] - g.ground[k];
            cut.add(c);
            sum += c;
            if (c > CUT_LIMIT + EPS) over4++;
            if (c < -EPS) negative++;
            if (!Double.isFinite(maxCut) || c > maxCut) maxCut = c;
        }
        return stats("cut(=eroded-ground)", cut.sorted()) + "\n"
                + String.format(Locale.ROOT,
                "cutSampledColumns=%d cutOver%.0fColumns=%d maxCut=%.4f cutVolume=%.2f "
                        + "cutNegativeColumns=%d（>0 = 违反「只下挖」铁律，应为 0）%n",
                sampled, CUT_LIMIT, over4, maxCut, sum, negative);
    }

    static String sectionLine(SectionReport s) {
        StringBuilder sb = new StringBuilder();
        sb.append("sections mode=").append(s.mode()).append(" source=").append(s.source())
                .append(" rows=").append(s.rows()).append(" outOfWindow=").append(s.outOfWindow())
                .append('\n');
        for (String line : s.lines()) sb.append("  ").append(line).append('\n');
        if (s.lines().isEmpty()) {
            sb.append("  NO_SECTION_SAMPLE（无 widths.csv 且窗口内无河列，或方向退化）\n");
        }
        return sb.toString();
    }

    /** 只列<b>本次真的写了</b>的文件（被 {@code -Dgeogenesis.depth.*=false} 关掉的如实不列）。 */
    static String fileLine(Path out) {
        StringBuilder sb = new StringBuilder("files=");
        if (Boolean.parseBoolean(System.getProperty("geogenesis.depth.csv", "true"))) {
            sb.append("columns.csv,");
        }
        if (Boolean.parseBoolean(System.getProperty("geogenesis.depth.png", "true"))) {
            sb.append("production.png,depth.png,");
        }
        if (Boolean.parseBoolean(System.getProperty("geogenesis.depth.sections", "true"))) {
            sb.append("section.csv,");
        }
        return sb.append("summary.txt,provenance.txt,config-effective.toml -> ")
                .append(out.toAbsolutePath()).toString();
    }

    // ---------------- 自检 ----------------

    /**
     * 独立预期值自检：用<b>逐 y 枚举</b>的落块循环复算整块水柱数，与 {@link #waterBlocks}
     * 的区间公式对账；同时验证本探针的水类判定与 {@link ProductionViewProbe#waterKind} 一致。
     */
    private static void selfTest() {
        Cell c = new Cell();
        c.height = 80.2;
        c.riverSurfaceY = 81.2;
        expect(c, true, 63, 0, "no fill flag is dry");
        c.riverType = 1;
        expect(c, true, 63, 1, "one whole block");
        c.riverSurfaceY = 80.9;
        expect(c, true, 63, 0, "same floor has no block despite 0.7 depth");
        c.riverSurfaceY = c.height;
        expect(c, true, 63, 0, "equal surface is dry");
        c.isLake = true;
        c.riverSurfaceY = 81;
        expect(c, true, 63, 1, "wet lake");
        c.isLake = false;
        c.riverSurfaceY = 80.9;
        c.riverLipY = 83.4;
        expect(c, true, 63, 3, "waterfall lip raises the column");
        c.riverType = 0;
        c.height = 62.9;
        expect(c, true, 63, 1, "ocean column near sea level");
        c.height = 63;
        expect(c, true, 63, 0, "sea-level floor is dry");
        c.height = 62.9;
        c.e = 1;
        expect(c, false, 63, 0, "hydrology-off land is dry");
        c.e = -1;
        expect(c, false, 63, 1, "hydrology-off vanilla ocean uses sea level");
        c.height = 319;
        c.riverType = 1;
        c.riverSurfaceY = 321;
        expect(c, true, 63, 0, "above world ceiling");
        c.height = -64.2;
        c.riverSurfaceY = -64;
        c.riverLipY = 0;
        // 唇口拉高后水柱从「地面上一格(-63)」一直灌到 y=0 ⇒ 64 整块（基岩层本身不是水）。
        expect(c, true, -100, 64, "positive lip keeps the whole column above bedrock wet");
        c.riverLipY = -64;
        expect(c, true, -100, 0, "bedrock is not a water block");
        checkOracle();
        if (!"1.5000".equals(fmt(1.5)) || !"NaN".equals(fmt(Double.NaN))
                || !"-0.0001".equals(fmt(-0.00006)) || !"0.0000".equals(fmt(-1e-9))
                || !"123.4567".equals(fmt(123.4567)) || !"0.0000".equals(fmt(0))) {
            throw new AssertionError("fmt 不符合 4 位定长/NaN 约定");
        }
        try {
            checkBaselineReader();
            checkSectionLabels();
        } catch (Exception e) {
            throw new AssertionError("evidence self-test", e);
        }
        StringBuilder provenance = new StringBuilder();
        appendClassProvenance(provenance);
        if (!provenance.toString().contains("ModelHydrologyProvider sha256=")
                || !provenance.toString().contains("RiverCrossSection sha256=")
                || !provenance.toString().contains("codeSource=")) throw new AssertionError("class provenance");
        System.out.println("PASS RiverDepthViewProbe self-test checks=13 oracle=3000 fmt=6 baseline/sections/provenance");
    }

    static void checkBaselineReader() throws Exception {
        Grid g = new Grid(3, 0, 0, 1);
        g.blocks[1] = 1;
        String csv = "x,z,river\n-1,-1,1\n-1,0,1\n-1,1,0\n0,-1,1\n";
        boolean[] mask = readBaselineMask(new BufferedReader(new StringReader(csv)), g);
        if (!mask[0] || mask[1] || mask[2] || !mask[3]
                || !g.baselineEvidence.contains("additionalErodedTypeZero=2")) {
            throw new AssertionError("baseline type0 dry/wet/nonriver mask");
        }
        try {
            readBaselineMask(new BufferedReader(new StringReader("x,z,river\n0,0,1\n-1,0,1\n")), g);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("out-of-order baseline accepted");
    }

    static void checkSectionLabels() {
        Map<String, List<WidthNode>> polys = new LinkedHashMap<>();
        for (int p = 0; p < 4; p++) {
            String end = p == 1 ? "MOUTH_LAKE" : p == 2 ? "BOUNDARY_EXIT" : "JOIN";
            List<WidthNode> nodes = new ArrayList<>();
            nodes.add(new WidthNode(0, 0, p, 0, p * 10, 0, 0.5, 0, end, p == 0, -1, true));
            nodes.add(new WidthNode(0, 0, p, 289, p * 10, 20, 6, 20, end, p == 0, -1, true));
            nodes.add(new WidthNode(0, 0, p, 300, p * 10, 40, 2, 40, end, p == 0, -1, true));
            polys.put("0:0:" + p, nodes);
        }
        List<Sec> a = chooseSections(polys, new Grid(201, 0, 0, 100));
        for (String label : new String[]{"OUTLET_HEAD", "LAKE_MOUTH", "BOUNDARY_EXIT_SAMPLE",
                "MIN_WIDTH_TRACE_HEAD_SOURCE_UNVERIFIED"}) {
            Sec s = a.stream().filter(v -> label.equals(v.label())).findFirst().orElseThrow();
            if ("OUTLET_HEAD".equals(label) && s.node() != 0) throw new AssertionError("middle outlet is head");
            if ((label.equals("LAKE_MOUTH") || label.equals("BOUNDARY_EXIT_SAMPLE")) && s.node() != 300) {
                throw new AssertionError("non-tail terminal sample");
            }
        }
        Grid clipped = new Grid(21, 0, 20, 10);
        List<Sec> b = chooseSections(polys, clipped);
        if (b.stream().anyMatch(s -> s.label().equals("OUTLET_HEAD") || s.label().equals("LAKE_MOUTH"))) {
            throw new AssertionError("clipped middle mistaken for head/tail");
        }
        if (b.stream().noneMatch(s -> s.label().equals("OUTLET_REACH_SAMPLE"))) {
            throw new AssertionError("outlet reach fallback missing");
        }
        if (!a.equals(chooseSections(polys, new Grid(201, 0, 0, 100)))) {
            throw new AssertionError("section coordinates not deterministic");
        }
    }

    private static void checkOracle() {
        Random rnd = new Random(20261027L);
        for (int i = 0; i < 3000; i++) {
            Cell c = new Cell();
            c.height = rnd.nextDouble() * 450 - 100;
            c.riverSurfaceY = c.height + rnd.nextDouble() * 10 - 3;
            c.riverLipY = c.riverSurfaceY + rnd.nextDouble() * 8 - 4;
            c.riverType = (byte) rnd.nextInt(2);
            c.isLake = rnd.nextBoolean();
            c.e = rnd.nextBoolean() ? -1 : 1;
            boolean on = rnd.nextBoolean();
            double sea = rnd.nextDouble() * 160 - 80;
            boolean river = on && c.riverType != 0 && c.riverSurfaceY > c.height;
            boolean ocean = on && c.height < sea;
            boolean water = on ? (river || ocean) : c.isWater();
            double top = surfaceTop(c, on, sea);
            int fillTopBlock = (int) Math.floor(top);
            // 独立逐层枚举，不调用 waterBlocks 的区间公式
            int expected = 0;
            if (water) {
                for (int y = MIN_Y + 1; y < MAX_Y; y++) {
                    if (y > (int) Math.floor(c.height) && y <= fillTopBlock) expected++;
                }
            }
            int actual = waterBlocks(c, top, water);
            if (actual != expected) {
                throw new AssertionError("oracle case " + i + " expected=" + expected + " actual=" + actual
                        + " height=" + c.height + " top=" + top);
            }
        }
    }

    private static void expect(Cell cell, boolean on, double sea, int blocks, String message) {
        int kind = ProductionViewProbe.waterKind(cell, on, sea);
        double top = surfaceTop(cell, on, sea);
        int actual = waterBlocks(cell, top, kind != DRY);
        if (actual != blocks) {
            throw new AssertionError(message + " expected=" + blocks + " actual=" + actual);
        }
    }
}
