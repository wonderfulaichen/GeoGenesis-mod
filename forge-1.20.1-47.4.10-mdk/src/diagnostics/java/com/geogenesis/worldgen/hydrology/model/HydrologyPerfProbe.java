package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.Climate;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkEngine;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
import com.geogenesis.worldgen.terrain.CacheStats;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TOML副本 + terrain.seed(seed)，经生产getChunkCells测cold/hot/cache-hit/concurrent/snapshot。
 * CPU/分配仅覆盖声明的请求线程；GC为JVM范围。可选HydrologyMetrics经反射读取，缺类明示unavailable。
 * snapshot每seed独立Built；每窗输出Cell/column的HPR2原始位证据及schema，compare-raw定位首差。
 * order每次独立JVM，仅验证固定跨区chunk集的全字段顺序一致性；rebuild仅清已完成chunk缓存，非性能测量。
 * trace、shape、段宽深、limits及pass1/final端口按长度和原序哈希。schemaVersion=2与旧摘要不兼容。
 * 冷样本每次新JVM；hot默认中心chunk(16,16)，全部被测chunk须同region且缓存miss。
 * far相距128chunk，校验3x3 pass1域不交；同chunk真实返回值在计时后逐字节比较。
 * CLI见--help。退出0完成/1差异/2前提不成立/3采集失败；不涵盖Forge实机/装饰/洞穴/流体tick。
 */
public final class HydrologyPerfProbe {

    /** 与 {@code GeoGenesisGenerator.WORLD_MIN_Y / WORLD_MAX_Y} 同值（生产构造参数）。 */
    static final int WORLD_MIN_Y = -64, WORLD_MAX_Y = 320;
    static final Path DEFAULT_TOML = Path.of("run", "config", "geogenesis-common.toml");
    static final Path DEFAULT_OUT_ROOT = Path.of("build", "hydrology-perf", "samples");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    static final String CELL_FIELD_ORDER =
            "height,continent,e,eOcean,blendCont,eLand,eClimate,snowLineE,coastCoord,"
            + "typeWeights[len+values],terrainType,variantTerrain,climate[present/t/h/cont/precip],"
            + "biomeType,temperature,humidity,precipitation,continentNoise,isLake,lakeMask,"
            + "erosionMask,shape,riverNetDischarge,riverDistance,oasisNoise,gradient,"
            + "rockTypeId,rockLayer,rockSeqPacked,rockTilt,riverType,riverSurfaceY,riverLipY,"
            + "isSnow,oceanFeat[present/total/ridge/seamount/arc/baseE],"
            + "landFeat[present/total/single/field/singleEdifice/fieldEdifice]";
    static final int RAW_MAGIC = 0x48505232; // HPR2
    static final String[] CELL_HEAD = "height,continent,e,eOcean,blendCont,eLand,eClimate,snowLineE,coastCoord".split(",");
    static final String[] CELL_TAIL = ("terrainType,variantTerrain,climate.present,climate.temperature,climate.humidity,"
            + "climate.continentality,climate.precipitation,biomeType,temperature,humidity,precipitation,continentNoise,"
            + "isLake,lakeMask,erosionMask,shape,riverNetDischarge,riverDistance,oasisNoise,gradient,rockTypeId,rockLayer,"
            + "rockSeqPacked,rockTilt,riverType,riverSurfaceY,riverLipY,isSnow").split(",");
    static final String[] OCEAN_FIELDS = "oceanFeat.total,oceanFeat.ridge,oceanFeat.seamount,oceanFeat.arc,oceanFeat.baseE".split(",");
    static final String[] LAND_FIELDS = "landFeat.total,landFeat.single,landFeat.field,landFeat.singleEdifice,landFeat.fieldEdifice".split(",");
    static final String[] COLUMN_FIELDS = ("blockX,blockZ,originalGroundY,carvedGroundY,waterSurfaceY,lipSurfaceY,"
            + "erosion,erosionMask,fillWater,lakePlan,lakeNodeIsNull,lakeLevelY").split(",");
    static final String USAGE = """
            HydrologyPerfProbe —— 水文生产性能取样（只读，独立样本 JSON）

            mode cold        <seed> <chunkX> <chunkZ>                      首 chunk，必须新 JVM
            mode hot         <seed> [chunkX=16 chunkZ=16] [preheatR=6] [requests=12]
            mode cache-hit   <seed> <chunkX> <chunkZ> [requests]
            mode concurrent  <seed> <threads> <same-chunk|neighbor|far> <requestsPerThread> [chunkX] [chunkZ]
            mode snapshot    [seed]   每窗输出 cells.raw.bin / columns.raw.bin / raw-schema.json
            mode order       <seed> <forward|reverse|downstream-first|rebuild>  各次必须独立 JVM，非性能
            compare-raw      <A.bin> <B.bin>   只读，输出首个坐标/字段/rawbits；相同0、不同1、读失败3
            far = 每worker相距128chunk(2048block)，运行时验证3x3 pass1支持域不交。
            order 固定8chunk：x=16..19,z=-33..-32（负z，跨z=-512 region边）；摘要采集后按坐标排序。
            downstream-first 先(17,-32)，再z=-32一侧；方向仅据默认seed现有河窗口raw，不动态推断其它seed。
            rebuild 原正序采集，等在途/预热排空，仅清terrain已完成chunk缓存再原序重采；不清provider/tile。
            不证明自然LRU、region/tile淘汰或Forge实机；不改生产预算、不关预热。前提不明/排空超时=exit2。

            options  --toml=<path>  --out=<dir>  --label=<text>
                     --windows="x,z,r;seed@x,z,r;..."   (snapshot only)
                     --expect-sha=<64hex>  (snapshot / order；order匹配orderSha256，不含请求次序文案)
                     --help    --self-test

            插桩（可选，反射读取）：被测 JVM 加 -Dgeogenesis.hydrologyMetrics=true 才有
                     instrumentation 的 stage./cache./sum. 差值；该类缺失（旧 baseline）时
                     sample.json 明示 available=false，不影响其它字段。
            缓存：terrain.chunkCacheStats() = chunkCell；gen.tileCacheStats() = erosionTile。

            退出码 0=样本完成 1=expect-sha 不符 2=前提不成立 3=FATAL
            """;

    private HydrologyPerfProbe() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 1 && ("--self-test".equals(args[0]) || "self-test".equals(args[0]))) {
            int code = 0;
            try {
                selfTest();
            } catch (Throwable t) {
                System.out.println("SELF-TEST FAIL " + t);
                t.printStackTrace(System.out);
                code = 1;
            }
            System.out.println("EXIT=" + code);
            System.exit(code);
            return;
        }
        int code;
        try {
            code = run(args);
        } catch (OrderPrecondition e) {
            System.out.println("PRECONDITION " + e.getMessage());
            code = 2;
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    static int run(String[] args) throws Exception {
        if (args.length > 0 && "compare-raw".equals(args[0])) return compareRaw(args);
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.print(USAGE);
            return 0;
        }
        Spec spec = parse(args);
        Files.createDirectories(spec.out());
        ConfigInput cfg = loadConfig(spec.toml(), spec.out(), spec);
        Built built = build(cfg, spec.seed());
        Map<String, Object> root = baseSample(spec, cfg, built);

        int code = 0;
        try {
            Map<String, Object> body = switch (spec.mode()) {
                case cold -> runCold(spec, built);
                case hot -> runHot(spec, built);
                case cacheHit -> runCacheHit(spec, built);
                case concurrent -> runConcurrent(spec, built);
                case snapshot -> runSnapshot(spec, cfg, built);
                case order -> runOrder(spec, cfg, built);
            };
            root.putAll(body);
            root.put("status", "ok");
            if (spec.mode() == Mode.order && Boolean.FALSE.equals(root.get("rebuildEqual"))) code = 1;
            if (spec.expectSha() != null) {
                String actual = String.valueOf(root.get(spec.mode() == Mode.order ? "orderSha256" : "snapshotSha256"));
                boolean match = spec.expectSha().equalsIgnoreCase(actual);
                Map<String, Object> expect = new LinkedHashMap<>();
                expect.put("sha256", spec.expectSha());
                expect.put("actual", actual);
                expect.put("match", match);
                root.put("expect", expect);
                if (!match) code = 1;
            }
            if (Boolean.FALSE.equals(root.get("valid"))) code = 2;
        } catch (OrderPrecondition e) {
            root.put("status", "precondition-failed");
            root.put("valid", false);
            root.put("performanceMeasurement", false);
            root.put("error", e.getMessage());
            code = 2;
        } catch (Throwable t) {
            root.put("status", "failed");
            root.put("error", String.valueOf(t));
            root.put("stack", stack(t, 12));
            code = 3;
        }
        writeJson(spec.out(), root);
        System.out.print(summary(spec, root));
        System.out.println("SAMPLE=" + spec.out().resolve("sample.json").toAbsolutePath());
        return code;
    }

    // ==================================================================
    // CLI
    // ==================================================================

    enum Mode {
        cold, hot, cacheHit, concurrent, snapshot, order;

        /** 目录/文件名用连字符小写（cache-hit）。 */
        String folder() {
            return this == cacheHit ? "cache-hit" : name();
        }

        static Mode parse(String text) {
            String t = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (t) {
                case "cold" -> cold;
                case "hot" -> hot;
                case "cache-hit", "cachehit" -> cacheHit;
                case "concurrent" -> concurrent;
                case "snapshot" -> snapshot;
                case "order" -> order;
                default -> throw new IllegalArgumentException("未知 mode: " + text + "（见 --help）");
            };
        }
    }

    record Win(long seed, int cx, int cz, int radius) {
        @Override
        public String toString() {
            return seed + "@" + cx + "," + cz + "," + radius;
        }
    }

    record Spec(Mode mode, long seed, int cx, int cz, int preheatRadius, int requests, int threads,
                String selection, List<Win> windows, Path toml, Path out, String label, String expectSha) { }

    static Spec parse(String[] args) {
        List<String> pos = new ArrayList<>();
        Map<String, String> opt = parseOptions(args, pos);
        if (pos.isEmpty()) throw new IllegalArgumentException("缺少 mode（见 --help）");
        Mode mode = Mode.parse(pos.get(0));
        if (mode == Mode.order) validateOrderCli(args, pos, opt);
        long seed = mode == Mode.order ? orderSeed(pos.get(1))
                : mode == Mode.snapshot ? optLong(pos, 1, ViewSpec.SEED) : Long.parseLong(at(pos, 1, "seed"));
        int cx = 0, cz = 0, preheat = 6, requests = 0, threads = 1;
        String selection = "same-chunk";
        switch (mode) {
            case cold -> {
                if (pos.size() < 4) throw new IllegalArgumentException("cold 需要 <seed> <chunkX> <chunkZ>");
                cx = Integer.parseInt(pos.get(2));
                cz = Integer.parseInt(pos.get(3));
            }
            case hot -> {
                require(pos.size() == 2 || pos.size() >= 4, "hot 需同时给 chunkX/chunkZ，或均省略（16,16）");
                cx = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : 16;
                cz = pos.size() > 3 ? Integer.parseInt(pos.get(3)) : 16;
                preheat = pos.size() > 4 ? Integer.parseInt(pos.get(4)) : 6;
                requests = pos.size() > 5 ? Integer.parseInt(pos.get(5)) : 12;
                require(preheat >= 1 && preheat <= 16, "preheatR 必须 1..16（须留在同一 region 内）");
                require(requests >= 1, "requests 必须 >= 1");
            }
            case cacheHit -> {
                if (pos.size() < 4) throw new IllegalArgumentException("cache-hit 需要 <seed> <chunkX> <chunkZ>");
                cx = Integer.parseInt(pos.get(2));
                cz = Integer.parseInt(pos.get(3));
                requests = pos.size() > 4 ? Integer.parseInt(pos.get(4)) : 200;
                require(requests >= 1, "requests 必须 >= 1");
            }
            case concurrent -> {
                if (pos.size() < 5) {
                    throw new IllegalArgumentException(
                            "concurrent 需要 <seed> <threads> <same-chunk|neighbor|far> <requestsPerThread>");
                }
                threads = Integer.parseInt(pos.get(2));
                selection = pos.get(3).trim().toLowerCase(Locale.ROOT);
                requests = Integer.parseInt(pos.get(4));
                cx = pos.size() > 5 ? Integer.parseInt(pos.get(5)) : 0;
                cz = pos.size() > 6 ? Integer.parseInt(pos.get(6)) : 0;
                require(threads >= 1 && threads <= 64, "threads 必须 1..64");
                require(requests >= 1, "requestsPerThread 必须 >= 1");
                require(selection.equals("same-chunk") || selection.equals("neighbor") || selection.equals("far"),
                        "selection 必须是 same-chunk / neighbor / far");
                if (!selection.equals("same-chunk")) {
                    // 默认512 block region：neighbor相距1 region；far相距4 region，3x3 pass1域不交。
                    require(requests <= 32, "neighbor/far 的 requestsPerThread 必须 <= 32（否则线程计划重叠）");
                }
            }
            case snapshot -> { /* 仅 seed + 窗口 */ }
            case order -> selection = pos.get(2);
        }
        Path toml = Path.of(opt.getOrDefault("toml", DEFAULT_TOML.toString()));
        String detail = switch (mode) {
            case cold -> "first";
            case concurrent -> threads + "-" + selection;
            case hot -> "preheat" + preheat + "-req" + requests;
            case order -> selection;
            default -> "req" + requests;
        };
        Path out = Path.of(opt.getOrDefault("out",
                DEFAULT_OUT_ROOT.resolve(mode.folder() + "-" + detail + "-" + seed + "-"
                        + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
                        .format(Instant.now())).toString()));
        String label = opt.getOrDefault("label", "-");
        String expect = opt.get("expect-sha");
        if (expect != null && mode != Mode.snapshot && mode != Mode.order) {
            throw new IllegalArgumentException("--expect-sha 只对 snapshot / order 有意义");
        }
        List<Win> windows = mode == Mode.snapshot
                ? (opt.containsKey("windows") ? parseWindows(opt.get("windows")) : defaultWindows())
                : List.of();
        if (mode == Mode.snapshot) require(!windows.isEmpty(), "窗口列表为空");
        return new Spec(mode, seed, cx, cz, preheat, requests, threads, selection, windows, toml, out, label, expect);
    }

    /** 提取旧CLI的同款位置/选项语义；order另行严校，旧mode不增加限制。 */
    static Map<String, String> parseOptions(String[] args, List<String> pos) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("选项必须写成 --key=value: " + a);
                opt.put(a.substring(2, eq), a.substring(eq + 1));
            } else {
                pos.add(a);
            }
        }
        return opt;
    }

    static void validateOrderCli(String[] args, List<String> pos, Map<String, String> opt) {
        orderRequire(pos.size() == 3, "order 需要且仅接受 <seed> <forward|reverse|downstream-first|rebuild>");
        orderRequire(Set.of("forward", "reverse", "downstream-first", "rebuild").contains(pos.get(2)),
                "未知order请求次序: " + pos.get(2));
        Set<String> seen = new LinkedHashSet<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            String key = a.substring(2, a.indexOf('='));
            orderRequire(Set.of("toml", "out", "label", "expect-sha").contains(key), "order 不支持 --" + key);
            orderRequire(seen.add(key), "order 不允许重复选项: --" + key);
            orderRequire(!opt.get(key).isBlank(), "order 选项值不能为空: --" + key);
        }
        String expect = opt.get("expect-sha");
        orderRequire(expect == null || expect.matches("[0-9a-fA-F]{64}"), "order --expect-sha 必须为64位hex");
    }

    static long orderSeed(String text) {
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new OrderPrecondition("order seed必须是long: " + text); }
    }

    static String at(List<String> pos, int i, String name) {
        if (i >= pos.size()) throw new IllegalArgumentException("缺少参数 " + name);
        return pos.get(i);
    }

    static long optLong(List<String> pos, int i, long def) {
        return i < pos.size() ? Long.parseLong(pos.get(i)) : def;
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    static List<Win> defaultWindows() {
        // 方案 §6「局部逐列」固定样本；最后一条是 seed 12345 的窗口（不同种子）。
        return List.of(
                new Win(ViewSpec.SEED, 276, -513, 32),
                new Win(ViewSpec.SEED, -955, -345, 48),
                new Win(ViewSpec.SEED, 230, 55, 48),
                new Win(ViewSpec.SEED, -623, 760, 32),
                new Win(12345L, 230, 95, 24));
    }

    static List<Win> parseWindows(String text) {
        List<Win> out = new ArrayList<>();
        for (String piece : text.split(";")) {
            String item = piece.trim();
            if (item.isEmpty()) continue;
            long seed = ViewSpec.SEED;
            int at = item.indexOf('@');
            if (at >= 0) {
                seed = Long.parseLong(item.substring(0, at).trim());
                item = item.substring(at + 1).trim();
            }
            String[] f = item.split(",");
            if (f.length != 3) throw new IllegalArgumentException("窗口格式应为 x,z,r 或 seed@x,z,r: " + piece);
            int cx = Integer.parseInt(f[0].trim()), cz = Integer.parseInt(f[1].trim());
            int r = Integer.parseInt(f[2].trim());
            require(r >= 1 && r <= 256, "窗口半径必须 1..256: " + piece);
            out.add(new Win(seed, cx, cz, r));
        }
        return out;
    }

    // ==================================================================
    // 配置（TOML 副本 + Forge 校正；绝不写回用户配置）
    // ==================================================================

    record ConfigInput(TerrainParams params, String sha, boolean corrected, String fingerprint) { }

    static ConfigInput loadConfig(Path toml, Path out, Spec spec) throws Exception {
        if (!Files.isRegularFile(toml)) {
            throw new IllegalStateException("缺少生产配置: " + toml.toAbsolutePath());
        }
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
        prov.append("probe=HydrologyPerfProbe\nreadOnly=true\n");
        prov.append("toml=").append(toml.toAbsolutePath()).append('\n');
        prov.append("sha256=").append(sha).append("\ncorrectedByForge=").append(!correct).append('\n');
        prov.append("fingerprint=").append(GeoGenesisConfig.configFingerprint()).append('\n');
        prov.append("seed=").append(spec.seed()).append(" mode=").append(spec.mode())
                .append(" chunk=(").append(spec.cx()).append(',').append(spec.cz()).append(")\n");
        if (spec.mode() == Mode.snapshot) prov.append("windows=").append(spec.windows()).append('\n');
        if (spec.mode() == Mode.order) prov.append("selection=").append(spec.selection()).append('\n');
        prov.append("seeding=terrain.seed(seed) -> generator.seed + chunkCache.clear + hydrology.setSeed\n");
        prov.append("terrain=CellGenerator(params,").append(WORLD_MIN_Y).append(',').append(WORLD_MAX_Y)
                .append(") + GeoGenesisTerrain（与 GeoGenesisGenerator.buildTerrain 同参数）\n");
        prov.append("horizontalScale=").append(params.horizontalScale())
                .append(" seaLevel=").append(params.seaLevel())
                .append(" minY=").append(params.minY()).append(" maxY=").append(params.maxY()).append('\n');
        for (String name : new String[]{"REGION", "HALO", "STEP", "SAMPLES", "HIER", "CROSS_PASS", "BOUNDARY_PORTS"}) {
            prov.append(name).append('=').append(staticField(ModelHydrologyProvider.class, name)).append('\n');
        }
        prov.append("MAX_SPILL_RISE=").append(DeterministicSkeleton.MAX_SPILL_RISE).append('\n');
        for (Class<?> type : new Class<?>[]{HydrologyPerfProbe.class, ModelHydrologyProvider.class,
                DeterministicSkeleton.class, GeoGenesisTerrain.class, CellGenerator.class, Cell.class,
                ProductionViewProbe.class, ViewSpec.class}) {
            prov.append(type.getName()).append(" sha256=").append(classSha(type))
                    .append(" codeSource=").append(codeSource(type)).append('\n');
        }
        appendJvmProvenance(prov);
        MetricsSnap metrics = metricsSnap();
        prov.append("hydrologyMetrics.class=").append(METRICS_CLASS).append('\n');
        prov.append("hydrologyMetrics.present=").append(metrics.available())
                .append(" enabled=").append(metrics.enabled())
                .append(" classSha256=").append(metrics.classSha()).append('\n');
        if (!metrics.available()) {
            prov.append("hydrologyMetrics.note=运行期 classpath 缺该类 ⇒ sample.instrumentation 明示 unavailable，"
                    + "其余样本字段不受影响\n");
        }
        prov.append("cacheStats=GeoGenesisTerrain.chunkCacheStats() + CellGenerator.tileCacheStats()（均为既有公开 API）\n");
        System.getProperties().stringPropertyNames().stream().sorted()
                .filter(k -> k.startsWith("geogenesis.") || k.startsWith("hydro."))
                .forEach(k -> prov.append(k).append('=').append(System.getProperty(k)).append('\n'));
        Files.writeString(out.resolve("provenance.txt"), prov, StandardCharsets.UTF_8);
        return new ConfigInput(params, sha, !correct, String.valueOf(GeoGenesisConfig.configFingerprint()));
    }

    static void appendJvmProvenance(StringBuilder prov) {
        var runtime = ManagementFactory.getRuntimeMXBean();
        prov.append("javaVersion=").append(System.getProperty("java.version"))
                .append(" javaVendor=").append(System.getProperty("java.vendor"))
                .append(" vm=").append(runtime.getVmName()).append(' ').append(runtime.getVmVersion()).append('\n');
        prov.append("jvmArgs=").append(runtime.getInputArguments()).append('\n');
        prov.append("processors=").append(Runtime.getRuntime().availableProcessors())
                .append(" maxHeapBytes=").append(Runtime.getRuntime().maxMemory())
                .append(" jvmUptimeMs=").append(runtime.getUptime()).append('\n');
        prov.append("jfr=external (-XX:StartFlightRecording=...); 本类不内嵌 JFR 生命周期\n");
    }

    // ==================================================================
    // 生产地形构建
    // ==================================================================

    record Built(CellGenerator gen, GeoGenesisTerrain terrain, ModelHydrologyProvider provider) { }

    static Built build(ConfigInput cfg, long seed) {
        CellGenerator gen = new CellGenerator(cfg.params(), WORLD_MIN_Y, WORLD_MAX_Y);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        // ★ 生产唯一正确播种入口（GeoGenesisGenerator.buildTerrain 同款）：内部 generator.seed
        //   + chunk 缓存清空 + hydrologyExperiment.setSeed。只 gen.seed 会漏水文种子。
        terrain.seed(seed);
        try {
            return new Built(gen, terrain, providerOf(terrain));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 terrain 持有的 ModelHydrologyProvider", e);
        }
    }

    /** 反射取 terrain 自己的 provider（不新建实例 ⇒ 不额外起 4 条 solve 线程、不产生第二份状态）。 */
    static ModelHydrologyProvider providerOf(GeoGenesisTerrain terrain) throws ReflectiveOperationException {
        Field engineField = GeoGenesisTerrain.class.getDeclaredField("hydrologyExperiment");
        engineField.setAccessible(true);
        Object engine = engineField.get(terrain);
        if (!(engine instanceof HydrologyChunkEngine)) {
            throw new IllegalStateException("hydrologyExperiment 不是 HydrologyChunkEngine: " + engine);
        }
        Field providerField = HydrologyChunkEngine.class.getDeclaredField("modelProvider");
        providerField.setAccessible(true);
        Object provider = providerField.get(engine);
        if (!(provider instanceof ModelHydrologyProvider p)) {
            throw new IllegalStateException("modelProvider 不是 ModelHydrologyProvider: " + provider);
        }
        return p;
    }

    // ==================================================================
    // 测量原语（wall / CPU / GC / 分配 / 缓存 / 延迟）
    // ==================================================================

    record GcSnap(Map<String, long[]> perCollector, long count, long timeMs) { }

    static GcSnap gcSnap() {
        Map<String, long[]> per = new LinkedHashMap<>();
        long count = 0, time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount(), t = bean.getCollectionTime();
            per.put(bean.getName(), new long[]{c, t});
            if (c > 0) count += c;
            if (t > 0) time += t;
        }
        return new GcSnap(per, count, time);
    }

    static Map<String, Object> gcDelta(GcSnap before, GcSnap after) {
        Map<String, Object> m = new LinkedHashMap<>();
        long count = 0, time = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : after.perCollector().entrySet()) {
            long[] b = before.perCollector().getOrDefault(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1]});
            long dc = e.getValue()[0] - b[0], dt = e.getValue()[1] - b[1];
            if (dc < 0 || dt < 0) continue;
            count += dc;
            time += dt;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("count", dc);
            row.put("timeMs", dt);
            rows.add(row);
        }
        m.put("count", count);
        m.put("timeMs", time);
        m.put("perCollector", rows);
        m.put("scope", "被测请求循环期间所有 GarbageCollectorMXBean 计数/耗时差值");
        return m;
    }

    static com.sun.management.ThreadMXBean allocBean() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean sun)) return null;
        try {
            if (!sun.isThreadAllocatedMemorySupported()) return null;
            if (!sun.isThreadAllocatedMemoryEnabled()) sun.setThreadAllocatedMemoryEnabled(true);
            return sun.isThreadAllocatedMemoryEnabled() ? sun : null;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    // ==================================================================
    // 可选插桩：HydrologyMetrics（冻结 baseline 里可能不存在 ⇒ 反射 + 明示 unavailable）
    // ==================================================================

    static final String METRICS_CLASS = "com.geogenesis.diagnostics.HydrologyMetrics";
    static final String METRICS_PROPERTY = "geogenesis.hydrologyMetrics";

    /**
     * 一次 {@code HydrologyMetrics.snapshot()} 的只读结果。
     *
     * @param available 运行期 classpath 是否有 {@code com.geogenesis.diagnostics.HydrologyMetrics}
     * @param enabled   {@code HydrologyMetrics.enabled()}（关闭时 snapshot 为空表）
     * @param values    原始 key→累计值（key 与生产 snapshot() 完全一致）
     * @param reason    unavailable / disabled 的原因（available && enabled 时为 null）
     * @param classSha  该类 class 资源 SHA-256（缺失 = "MISSING"）
     */
    record MetricsSnap(boolean available, boolean enabled, Map<String, Long> values,
                       String reason, String classSha) {
        static MetricsSnap unavailable(String reason) {
            return new MetricsSnap(false, false, Map.of(), reason, "MISSING");
        }
    }

    /**
     * 反射读取生产插桩（<b>不编译期依赖</b>）：旧 baseline / 未合入 HydrologyMetrics 的 classpath
     * 必须如实报 {@code available=false}，绝不猜、也绝不因此让样本失败。
     *
     * <p>⚠ 本调用会初始化该类（读一次 {@code -Dgeogenesis.hydrologyMetrics}）；因此一律在
     * <b>计时窗口之外</b>取快照，见 {@code instrumentation.readTiming}。</p>
     */
    static MetricsSnap metricsSnap() {
        Class<?> type;
        try {
            type = Class.forName(METRICS_CLASS, false, HydrologyPerfProbe.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return MetricsSnap.unavailable("class absent in runtime classpath: " + e);
        }
        try {
            Method enabledMethod = type.getMethod("enabled");
            boolean on = Boolean.TRUE.equals(enabledMethod.invoke(null));
            Map<String, Long> values = new TreeMap<>();
            if (on) {
                Method snapshotMethod = type.getMethod("snapshot");
                Object raw = snapshotMethod.invoke(null);
                if (!(raw instanceof Map<?, ?> map)) {
                    return MetricsSnap.unavailable("snapshot() returned " + (raw == null ? "null" : raw.getClass()));
                }
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null && e.getValue() instanceof Number n) {
                        values.put(String.valueOf(e.getKey()), n.longValue());
                    }
                }
            }
            return new MetricsSnap(true, on, on ? values : Map.of(),
                    on ? null : "HydrologyMetrics.enabled()=false（被测 JVM 需 -D" + METRICS_PROPERTY + "=true）",
                    classSha(type));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return MetricsSnap.unavailable("reflection failed: " + e);
        }
    }

    /** before/after 差值；不可用或未开启时 delta=null 并写明原因（不塞零冒充"没发生"）。 */
    static Map<String, Object> metricsDelta(MetricsSnap before, MetricsSnap after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", METRICS_CLASS);
        m.put("property", METRICS_PROPERTY);
        m.put("available", before.available() && after.available());
        m.put("enabled", before.enabled());
        m.put("classSha256", after.classSha());
        m.put("readTiming", "before/after快照调用位于计时边界外；后台工作与观测器开销未隔离");
        if (!before.available() || !after.available()) {
            m.put("reason", before.available() ? after.reason() : before.reason());
            m.put("values", null);
            m.put("delta", null);
            m.put("scope", "运行期缺该类（或反射失败）⇒ 明示 unavailable；本样本其余字段仍有效");
            return m;
        }
        if (!before.enabled()) {
            m.put("reason", before.reason());
            m.put("values", Map.of());
            m.put("delta", Map.of());
            m.put("scope", "插桩关闭：snapshot为空表；未据此断言运行耗时或开销");
            return m;
        }
        TreeSet<String> keys = new TreeSet<>(before.values().keySet());
        keys.addAll(after.values().keySet());
        Map<String, Long> delta = new TreeMap<>();
        for (String key : keys) {
            long b = before.values().getOrDefault(key, 0L);
            long a = after.values().getOrDefault(key, 0L);
            if (a != b) delta.put(key, a - b);
        }
        m.put("reason", null);
        m.put("values", after.values());
        m.put("delta", delta);
        m.put("zeroDeltaKeysOmitted", true);
        m.put("scope", "进程内累计快照差值，含并发后台任务；不是逐请求归因。stage.*.ns/count 为累计值，maxNs 为全局最大值差（非窗口最大值），value.* 为 last-wins；嵌套/跨线程阶段不能相加为 CPU 占比");
        return m;
    }

    record Measured(long wallNanos, long cpuNanos, boolean cpuSupported, long allocBytes, boolean allocSupported,
                    long[] latencyNanos, List<int[]> chunks, List<Cell[]> results,
                    Map<String, Object> gc, Map<String, Object> cache, Map<String, Object> instrumentation) { }

    /** 单线程测量：只把 getChunkCells 放进计时窗口；摘要/哈希/插桩快照都在窗口之外。 */
    static Measured measure(Built built, List<int[]> chunks) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sun = allocBean();
        CacheStats chunkStats = built.terrain().chunkCacheStats();
        CacheStats tileStats = built.gen().tileCacheStats();
        MetricsSnap metrics0 = metricsSnap();
        long h0 = chunkStats.hits(), m0 = chunkStats.misses(), e0 = chunkStats.evictions();
        long th0 = tileStats.hits(), tm0 = tileStats.misses(), te0 = tileStats.evictions();
        long[] lat = new long[chunks.size()];
        List<Cell[]> results = new ArrayList<>(chunks.size());
        boolean cpuOk = tm.isCurrentThreadCpuTimeSupported() && tm.isThreadCpuTimeEnabled();
        int region0 = regionCacheSize();
        GcSnap gc0 = gcSnap();
        long cpu0 = cpuOk ? tm.getCurrentThreadCpuTime() : -1;
        long alloc0 = sun == null ? -1 : sun.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int i = 0; i < chunks.size(); i++) {
            int[] c = chunks.get(i);
            long r0 = System.nanoTime();
            Cell[] cells = built.terrain().getChunkCells(c[0], c[1]);
            lat[i] = System.nanoTime() - r0;
            results.add(cells);
        }
        long t1 = System.nanoTime();
        long cpu1 = cpuOk ? tm.getCurrentThreadCpuTime() : -1;
        long alloc1 = sun == null ? -1 : sun.getCurrentThreadAllocatedBytes();
        GcSnap gc1 = gcSnap();
        MetricsSnap metrics1 = metricsSnap();
        Map<String, Object> cache = cacheDelta(chunkStats, h0, m0, e0);
        cache.put("erosionTile", cacheDeltaNamed("erosionTile", tileStats, th0, tm0, te0,
                "CellGenerator.tileCacheStats() 差值"));
        cache.put("regionCacheEntriesBefore", region0);
        cache.put("regionCacheEntriesAfter", regionCacheSize());
        cache.put("regionCacheScope", "ModelHydrologyProvider.SHARED_CACHE.size()（只读反射；跨 seed 已由 setSeed 隔离）");
        return new Measured(t1 - t0, cpuOk ? cpu1 - cpu0 : -1, cpuOk,
                sun == null ? -1 : alloc1 - alloc0, sun != null, lat, chunks, results,
                gcDelta(gc0, gc1), cache, metricsDelta(metrics0, metrics1));
    }

    static Map<String, Object> cacheDelta(CacheStats stats, long h0, long m0, long e0) {
        return cacheDeltaNamed("chunkCell", stats, h0, m0, e0, "GeoGenesisTerrain.chunkCacheStats() 差值");
    }

    /** 同一套 hit/miss/evict 差值；{@code name} 区分 chunkCell / erosionTile。 */
    static Map<String, Object> cacheDeltaNamed(String name, CacheStats stats, long h0, long m0, long e0,
                                               String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", stats.hits() - h0);
        m.put("misses", stats.misses() - m0);
        m.put("evictions", stats.evictions() - e0);
        m.put("hitRate", rate(stats.hits() - h0, stats.misses() - m0));
        m.put("name", name);
        m.put("source", source);
        return m;
    }

    static double rate(long hits, long misses) {
        long total = hits + misses;
        return total <= 0 ? 0.0 : (double) hits / total;
    }

    static Map<String, Object> measuredMap(Measured m, boolean hydrologyOn, double seaLevel) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("wallMs", ms(m.wallNanos()));
        timing.put("cpuMs", m.cpuSupported() ? ms(m.cpuNanos()) : null);
        timing.put("cpuSupported", m.cpuSupported());
        timing.put("cpuScope", "currentThread");
        timing.put("cpuMayExceedWall", false);
        timing.put("cpuNotProcessTotal", "不含 GC/JIT/其它线程；也不是进程总 CPU");
        if (m.cpuSupported() && m.wallNanos() > 0) timing.put("cpuToWallRatio", m.cpuNanos() / (double) m.wallNanos());
        out.put("timing", timing);
        out.put("gc", m.gc());
        out.put("allocation", allocMap(m.allocSupported(), m.allocBytes(), "currentThread",
                "只含执行被测请求的当前线程；不含主线程创建/摘要/写 JSON、不含 GC 线程"));
        out.put("latency", latencyMap(m.latencyNanos()));
        out.put("cache", m.cache());
        out.put("instrumentation", m.instrumentation());
        out.put("result", summarize(m.chunks(), m.results(), m.latencyNanos(), hydrologyOn, seaLevel));
        return out;
    }

    static Map<String, Object> allocMap(boolean supported, long bytes, String scope, String covers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supported", supported);
        m.put("bytes", supported ? bytes : null);
        m.put("scope", supported ? scope : "unsupported");
        m.put("covers", covers);
        m.put("source", "com.sun.management.ThreadMXBean thread allocated bytes 差值（非堆 RSS）");
        return m;
    }

    static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    // ==================================================================
    // 结果摘要 / 延迟统计
    // ==================================================================

    record CellSummary(String sha, int columns, int riverFlag, int lakeFlag, int[] kind,
                       double minHeight, double maxHeight) { }

    static CellSummary summarizeChunk(Cell[] cells, boolean hydrologyOn, double seaLevel) {
        Hasher h = new Hasher();
        int riverFlag = 0, lakeFlag = 0;
        int[] kind = new int[4];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        if (cells == null || cells.length != 256) {
            throw new IllegalStateException("生产 getChunkCells 返回长度 " + (cells == null ? "null" : cells.length));
        }
        for (Cell cell : cells) {
            hashCell(h, cell);
            int k = ProductionViewProbe.waterKind(cell, hydrologyOn, seaLevel);
            kind[k]++;
            if (cell.riverType != 0) riverFlag++;
            if (cell.isLake) lakeFlag++;
            if (cell.height < min) min = cell.height;
            if (cell.height > max) max = cell.height;
        }
        return new CellSummary(h.hex(), cells.length, riverFlag, lakeFlag, kind, min, max);
    }

    static Map<String, Object> summarize(List<int[]> chunks, List<Cell[]> results, long[] latency,
                                         boolean hydrologyOn, double seaLevel) {
        int columns = 0, riverFlag = 0, lakeFlag = 0;
        int[] kind = new int[4];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        List<Map<String, Object>> perChunk = new ArrayList<>();
        Hasher total = new Hasher();
        for (int i = 0; i < chunks.size(); i++) {
            CellSummary s = summarizeChunk(results.get(i), hydrologyOn, seaLevel);
            columns += s.columns();
            riverFlag += s.riverFlag();
            lakeFlag += s.lakeFlag();
            for (int k = 0; k < 4; k++) kind[k] += s.kind()[k];
            min = Math.min(min, s.minHeight());
            max = Math.max(max, s.maxHeight());
            total.str(s.sha());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cx", chunks.get(i)[0]);
            row.put("cz", chunks.get(i)[1]);
            row.put("cellsSha256", s.sha());
            row.put("latencyMs", ms(latency[i]));
            perChunk.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunks", chunks.size());
        m.put("columns", columns);
        m.put("riverTypeNonZero", riverFlag);
        m.put("isLakeColumns", lakeFlag);
        m.put("waterKind", waterKindMap(kind));
        m.put("waterKindSource", "ProductionViewProbe.waterKind（生产放水口径，同包复用）");
        m.put("minHeight", num(min));
        m.put("maxHeight", num(max));
        m.put("cellsSha256", total.hex());
        m.put("cellsSha256Scope", "逐 chunk 全字段 raw-bit 摘要按请求顺序串联（CELL_FIELD_ORDER）");
        m.put("perChunk", perChunk);
        return m;
    }

    static Map<String, Object> waterKindMap(int[] kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dry", kind[0]);
        m.put("sea", kind[1]);
        m.put("lake", kind[2]);
        m.put("river", kind[3]);
        return m;
    }

    static Map<String, Object> latencyMap(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (long v : sorted) sum += v;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", "ms");
        m.put("n", nanos.length);
        if (nanos.length == 0) return m;
        m.put("min", ms(sorted[0]));
        m.put("p50", ms(pct(sorted, 0.50)));
        m.put("p90", ms(pct(sorted, 0.90)));
        m.put("p95", ms(pct(sorted, 0.95)));
        m.put("max", ms(sorted[sorted.length - 1]));
        m.put("mean", ms(sum / (double) sorted.length));
        List<Double> samples = new ArrayList<>(nanos.length);
        for (long v : nanos) samples.add(ms(v));
        m.put("samplesMs", samples);
        m.put("smallSampleWarning", nanos.length < 20 ? "n<20：不要解释 P95/P99 尾分布" : "n>=20");
        return m;
    }

    static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static Object num(double v) {
        return Double.isFinite(v) ? (Object) v : (Object) String.valueOf(v);
    }

    // ==================================================================
    // mode: cold / hot / cache-hit
    // ==================================================================

    static Map<String, Object> runCold(Spec spec, Built built) {
        int regionEntries = regionCacheSize();
        CacheStats stats = built.terrain().chunkCacheStats();
        boolean fresh = regionEntries == 0 && stats.misses() == 0 && stats.hits() == 0;
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> pre = new LinkedHashMap<>();
        pre.put("regionCacheEntries", regionEntries);
        pre.put("chunkCacheHits", stats.hits());
        pre.put("chunkCacheMisses", stats.misses());
        pre.put("freshJvmRequired", true);
        pre.put("note", "首 chunk 必须每样本新 JVM；jvmUptimeMs 见 provenance，长时间运行的 JVM 不宜当冷样本");
        m.put("precondition", pre);
        Measured measured = measure(built, List.of(new int[]{spec.cx(), spec.cz()}));
        m.putAll(measuredMap(measured, built.terrain().riversEnabled(), built.terrain().seaLevel()));
        m.put("valid", fresh);
        m.put("validityNotes", fresh
                ? List.of("冷启动前提成立：本 seed 的 region 缓存为空、chunk 缓存计数为 0")
                : List.of("非冷启动：本 seed 已有 chunk/region 缓存条目 ⇒ 数字含热路径，请用新 JVM 重跑"));
        return m;
    }

    static Map<String, Object> runHot(Spec spec, Built built) {
        List<int[]> preheat = preheatChunks(spec.cx(), spec.cz(), spec.preheatRadius());
        long p0 = System.nanoTime();
        for (int[] c : preheat) built.terrain().getChunkCells(c[0], c[1]);
        long preheatMs = (System.nanoTime() - p0) / 1_000_000L;
        List<int[]> measuredChunks = ringChunks(spec.cx(), spec.cz(), spec.preheatRadius() + 1, spec.requests());
        Set<Long> preheated = new LinkedHashSet<>();
        for (int[] c : preheat) preheated.add(pack(c[0], c[1]));
        int overlap = 0;
        for (int[] c : measuredChunks) if (preheated.contains(pack(c[0], c[1]))) overlap++;
        Measured measured = measure(built, measuredChunks);
        int sameRegion = sameRegionCount(measuredChunks, spec.cx(), spec.cz());
        long misses = ((Number) measured.cache().get("misses")).longValue();
        long hits = ((Number) measured.cache().get("hits")).longValue();
        boolean ok = hotValid(overlap, hits, misses, sameRegion, measuredChunks.size());
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("preheatRadiusChunks", spec.preheatRadius());
        workload.put("preheatChunks", preheat.size());
        workload.put("preheatWallMs", preheatMs);
        workload.put("measuredChunks", measuredChunks.size());
        workload.put("ringRadiusChunks", spec.preheatRadius() + 1);
        workload.put("preheatedOverlap", overlap);
        workload.put("sameRegionAsCenter", sameRegion);
        workload.put("regionSizeBlocks", staticInt(ModelHydrologyProvider.class, "REGION"));
        m.put("workload", workload);
        m.putAll(measuredMap(measured, built.terrain().riversEnabled(), built.terrain().seaLevel()));
        m.put("valid", ok);
        m.put("validityNotes", ok
                ? List.of("热区前提成立：无预热重叠、全部 chunk miss、全部被测 chunk 与预热中心同 region；tile 就绪程度另见 erosionTile miss")
                : List.of("热区前提不成立：overlap=" + overlap + " hits=" + hits + " misses=" + misses
                        + " sameRegion=" + sameRegion + "/" + measuredChunks.size()));
        return m;
    }

    static boolean hotValid(int overlap, long hits, long misses, int sameRegion, int requests) {
        return overlap == 0 && hits == 0 && misses == requests && sameRegion == requests;
    }

    static Map<String, Object> runCacheHit(Spec spec, Built built) {
        built.terrain().getChunkCells(spec.cx(), spec.cz());       // 先真实生成一次（不计时）
        List<int[]> chunks = new ArrayList<>(spec.requests());
        for (int i = 0; i < spec.requests(); i++) chunks.add(new int[]{spec.cx(), spec.cz()});
        Measured measured = measure(built, chunks);
        long hits = ((Number) measured.cache().get("hits")).longValue();
        long misses = ((Number) measured.cache().get("misses")).longValue();
        boolean ok = hits == spec.requests() && misses == 0;
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("requests", spec.requests());
        workload.put("chunk", List.of(spec.cx(), spec.cz()));
        workload.put("preGenerated", true);
        m.put("workload", workload);
        m.putAll(measuredMap(measured, built.terrain().riversEnabled(), built.terrain().seaLevel()));
        m.put("valid", ok);
        m.put("validityNotes", ok
                ? List.of("整 chunk 缓存命中：hits=" + hits + " misses=0（这是 cache-hit 负载，不代表 columnsFor 成本）")
                : List.of("期望 " + spec.requests() + " 次命中，实测 hits=" + hits + " misses=" + misses));
        return m;
    }

    static List<int[]> preheatChunks(int cx, int cz, int radius) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) out.add(new int[]{cx + dx, cz + dz});
        }
        return out;
    }

    /** 预热方块外围的一圈 chunk（保证不重叠，且默认仍在同一 region 内）。 */
    static List<int[]> ringChunks(int cx, int cz, int radius, int count) {
        List<int[]> ring = new ArrayList<>();
        for (int d = -radius; d <= radius; d++) {
            ring.add(new int[]{cx + d, cz - radius});
            ring.add(new int[]{cx + d, cz + radius});
        }
        for (int d = -radius + 1; d <= radius - 1; d++) {
            ring.add(new int[]{cx - radius, cz + d});
            ring.add(new int[]{cx + radius, cz + d});
        }
        if (count > ring.size()) {
            throw new IllegalArgumentException("requests=" + count + " 超过外圈容量 " + ring.size()
                    + "（preheatR 太小）");
        }
        return new ArrayList<>(ring.subList(0, count));
    }

    static int sameRegionCount(List<int[]> chunks, int cx, int cz) {
        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        int wantX = Math.floorDiv(cx * 16 + 8, region), wantZ = Math.floorDiv(cz * 16 + 8, region);
        int same = 0;
        for (int[] c : chunks) {
            if (Math.floorDiv(c[0] * 16 + 8, region) == wantX
                    && Math.floorDiv(c[1] * 16 + 8, region) == wantZ) same++;
        }
        return same;
    }

    // ==================================================================
    // mode: concurrent
    // ==================================================================

    static Map<String, Object> runConcurrent(Spec spec, Built built) throws Exception {
        int threads = spec.threads(), perThread = spec.requests();
        List<List<int[]>> plan = concurrentPlan(spec);
        List<int[]> all = new ArrayList<>();
        for (List<int[]> l : plan) all.addAll(l);
        Set<Long> unique = new LinkedHashSet<>();
        for (int[] c : all) unique.add(pack(c[0], c[1]));
        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        Set<Long> regions = new LinkedHashSet<>();
        for (int[] c : all) regions.add(pack(Math.floorDiv(c[0] * 16 + 8, region),
                Math.floorDiv(c[1] * 16 + 8, region)));

        long[][] lat = new long[threads][perThread];
        Cell[][][] returned = new Cell[threads][perThread][];
        long[] cpu = new long[threads], alloc = new long[threads], ids = new long[threads];
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sun = allocBean();
        CacheStats stats = built.terrain().chunkCacheStats();
        CacheStats tileStats = built.gen().tileCacheStats();
        MetricsSnap metrics0 = metricsSnap();
        long h0 = stats.hits(), m0 = stats.misses(), e0 = stats.evictions();
        long th0 = tileStats.hits(), tm0 = tileStats.misses(), te0 = tileStats.evictions();
        int region0 = regionCacheSize();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            List<int[]> mine = plan.get(i);
            Thread t = new Thread(() -> {
                long id = Thread.currentThread().getId();
                ids[idx] = id;
                boolean cpuOk = tm.isCurrentThreadCpuTimeSupported() && tm.isThreadCpuTimeEnabled();
                ready.countDown();
                long c0 = -1, a0 = -1;
                try {
                    go.await();
                    // CPU/分配起点放在屏障之后：只覆盖被测请求循环，不含线程创建与等待。
                    c0 = cpuOk ? tm.getThreadCpuTime(id) : -1;
                    a0 = sun == null ? -1 : sun.getThreadAllocatedBytes(id);
                    for (int k = 0; k < mine.size(); k++) {
                        int[] c = mine.get(k);
                        long r0 = System.nanoTime();
                        Cell[] cells = built.terrain().getChunkCells(c[0], c[1]);
                        lat[idx][k] = System.nanoTime() - r0;
                        returned[idx][k] = cells;
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    cpu[idx] = cpuOk && c0 >= 0 ? tm.getThreadCpuTime(id) - c0 : -1;
                    alloc[idx] = sun == null || a0 < 0 ? -1 : sun.getThreadAllocatedBytes(id) - a0;
                    done.countDown();
                }
            }, "hydro-perf-" + i);
            t.setDaemon(true);
            t.start();
        }
        ready.await();
        GcSnap gc0 = gcSnap();
        long wallStart = System.nanoTime();
        go.countDown();
        done.await();
        long wallEnd = System.nanoTime();
        GcSnap gc1 = gcSnap();
        MetricsSnap metrics1 = metricsSnap();
        if (error.get() != null) throw new IllegalStateException("并发 worker 失败", error.get());
        long cpuSum = 0, allocSum = 0;
        boolean cpuSupported = tm.isCurrentThreadCpuTimeSupported() && tm.isThreadCpuTimeEnabled();
        List<Map<String, Object>> perThreadRows = new ArrayList<>();
        long[] flat = new long[threads * perThread];
        int n = 0;
        for (int i = 0; i < threads; i++) {
            cpuSum += Math.max(0, cpu[i]);
            allocSum += Math.max(0, alloc[i]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("threadId", ids[i]);
            row.put("requests", lat[i].length);
            row.put("cpuMs", cpuSupported ? ms(cpu[i]) : null);
            row.put("allocatedBytes", sun == null ? null : alloc[i]);
            row.put("latency", latencyMap(lat[i]));
            row.put("chunks", plan.get(i).stream().map(c -> c[0] + ":" + c[1]).toList());
            perThreadRows.add(row);
            for (long v : lat[i]) flat[n++] = v;
        }
        long wall = wallEnd - wallStart;
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("threads", threads);
        workload.put("selection", spec.selection());
        workload.put("requestsPerThread", perThread);
        workload.put("totalRequests", all.size());
        workload.put("uniqueChunks", unique.size());
        workload.put("uniqueRegions", regions.size());
        workload.put("centerChunk", List.of(spec.cx(), spec.cz()));
        workload.put("regionSizeBlocks", region);
        workload.put("chunkSpacingBlocks", spacingChunks(spec.selection()) * 16);
        workload.put("dependencyScope", spec.selection().equals("far")
                ? "2048 block 间距；默认512 block region的3x3 pass1支持域不相交；不承诺全进程静态缓存/后台预热无共享"
                : "same-chunk/neighbor 有意共享依赖");
        workload.put("sharedStartBarrier", true);
        m.put("workload", workload);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("wallMs", ms(wall));
        timing.put("cpuMs", cpuSupported ? ms(cpuSum) : null);
        timing.put("cpuSupported", cpuSupported);
        timing.put("cpuScope", "sumOfWorkerThreadCpuTime");
        timing.put("cpuMayExceedWall", true);
        timing.put("cpuNotWallSum", "各 worker 线程 CPU 时间之和；不是把线程 wall 相加，也不是进程总 CPU（含 GC/JIT/主线程）");
        if (cpuSupported && wall > 0) timing.put("cpuToWallRatio", cpuSum / (double) wall);
        timing.put("chunksPerSecond", wall <= 0 ? null : all.size() * 1e9 / wall);
        m.put("timing", timing);
        m.put("gc", gcDelta(gc0, gc1));
        m.put("allocation", allocMap(sun != null, allocSum, "sumOfWorkerThreads",
                "各 worker 线程自身被测循环的分配之和；不含主线程/线程创建/摘要/JSON，不含 GC 线程"));
        m.put("latency", latencyMap(flat));
        Map<String, Object> concurrentCache = cacheDelta(stats, h0, m0, e0);
        concurrentCache.put("erosionTile", cacheDeltaNamed("erosionTile", tileStats, th0, tm0, te0,
                "CellGenerator.tileCacheStats() 差值"));
        concurrentCache.put("regionCacheEntriesBefore", region0);
        concurrentCache.put("regionCacheEntriesAfter", regionCacheSize());
        concurrentCache.put("regionCacheScope",
                "ModelHydrologyProvider.SHARED_CACHE.size()（只读反射；跨 seed 已由 setSeed 隔离）");
        m.put("cache", concurrentCache);
        m.put("instrumentation", metricsDelta(metrics0, metrics1));
        m.put("perThread", perThreadRows);
        List<Cell[]> results = new ArrayList<>(all.size());
        for (Cell[][] worker : returned) results.addAll(Arrays.asList(worker));
        m.put("result", summarize(all, results, flat, built.terrain().riversEnabled(), built.terrain().seaLevel()));
        boolean sameChunk = spec.selection().equals("same-chunk");
        Map<String, Object> consistency = sameChunk ? sameChunkReturns(results) : Map.of("checked", false);
        boolean disjoint = !spec.selection().equals("far") || disjointPass1Supports(plan, region);
        workload.put("pass1SupportsDisjoint", spec.selection().equals("far") ? disjoint : null);
        m.put("returnedCellConsistency", consistency);
        m.put("resultSource", "worker保存的每次实际返回Cell[]；摘要和raw-bit一致性校验在计时之后，无事后缓存重取");
        boolean ok = sameChunk ? unique.size() == 1 && Boolean.TRUE.equals(consistency.get("equal"))
                : unique.size() == all.size() && regions.size() >= threads && disjoint;
        m.put("valid", ok);
        m.put("validityNotes", List.of(ok ? "请求布局与真实返回值检查通过；不是全周期确定性验收"
                : "请求布局/真实返回值不一致，见 workload 与 returnedCellConsistency"));
        return m;
    }

    static int spacingChunks(String selection) {
        return switch (selection) {
            case "neighbor" -> 32;
            case "far" -> 128;
            default -> 0;
        };
    }

    static boolean disjointPass1Supports(List<List<int[]>> plan, int region) {
        Set<Long> occupied = new LinkedHashSet<>();
        for (List<int[]> worker : plan) {
            Set<Long> mine = new LinkedHashSet<>();
            for (int[] c : worker) {
                int rx = Math.floorDiv(c[0] * 16 + 8, region), rz = Math.floorDiv(c[1] * 16 + 8, region);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    mine.add(pack(rx + dx, rz + dz));
                }
            }
            for (long key : mine) if (!occupied.add(key)) return false;
        }
        return true;
    }

    static Map<String, Object> sameChunkReturns(List<Cell[]> results) {
        byte[] reference = chunkRawBytes(results.get(0));
        int mismatch = -1;
        for (int i = 1; i < results.size(); i++) {
            if (!Arrays.equals(reference, chunkRawBytes(results.get(i)))) { mismatch = i; break; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checked", true);
        m.put("equal", mismatch < 0);
        m.put("requests", results.size());
        m.put("firstMismatchingRequest", mismatch < 0 ? null : mismatch);
        m.put("scope", "所有实际返回Cell字段序列逐字节比较，校验位于计时外；不使用identity/查询胜者");
        return m;
    }

    static List<List<int[]>> concurrentPlan(Spec spec) {
        int threads = spec.threads(), per = spec.requests();
        List<List<int[]>> plan = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            List<int[]> mine = new ArrayList<>(per);
            for (int k = 0; k < per; k++) {
                int cx = switch (spec.selection()) {
                    case "same-chunk" -> spec.cx();
                    case "neighbor" -> spec.cx() + i * 32 + k;
                    case "far" -> spec.cx() + i * 128 + k;
                    default -> throw new IllegalArgumentException("未知 selection " + spec.selection());
                };
                mine.add(new int[]{cx, spec.cz()});
            }
            plan.add(mine);
        }
        return plan;
    }

    // ==================================================================
    // mode: order（独立JVM、顺序观测；不输出任何性能数字）
    // ==================================================================

    static final class OrderPrecondition extends IllegalStateException {
        OrderPrecondition(String message) { super(message); }
    }

    record OrderChunk(int cx, int cz) implements Comparable<OrderChunk> {
        @Override public int compareTo(OrderChunk other) {
            int x = Integer.compare(cx, other.cx);
            return x != 0 ? x : Integer.compare(cz, other.cz);
        }
    }

    record OrderCapture(OrderChunk chunk, Cell[] cells, List<HydrologyBlockCarvedColumn> columns) { }
    record OrderBits(OrderChunk chunk, byte[] cells, byte[] columns, String cellsSha, String columnsSha) { }

    static List<OrderChunk> fixedOrderChunks() {
        List<OrderChunk> chunks = new ArrayList<>();
        // 默认seed的生产河窗口(276,-513)：负z，跨z=-512的region边；四列×两行连续8chunk。
        for (int cx = 16; cx <= 19; cx++) for (int cz = -33; cz <= -32; cz++) {
            chunks.add(new OrderChunk(cx, cz));
        }
        return List.copyOf(chunks);
    }

    static List<OrderChunk> orderPlan(String selection) {
        List<OrderChunk> plan = new ArrayList<>(fixedOrderChunks());
        switch (selection) {
            case "forward", "rebuild" -> { }
            case "reverse" -> Collections.reverse(plan);
            case "downstream-first" -> plan.sort(Comparator
                    .comparingInt((OrderChunk c) -> c.cz() == -32 ? 0 : 1)
                    .thenComparingInt(c -> Math.abs(c.cx() - 17)).thenComparingInt(OrderChunk::cx));
            default -> throw new OrderPrecondition("未知order请求次序: " + selection);
        }
        return plan;
    }

    static Map<String, Object> orderWorkload(Spec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selection", spec.selection());
        m.put("freshJvmRequired", true);
        m.put("fixedChunkSet", fixedOrderChunks());
        m.put("requestOrder", orderPlan(spec.selection()));
        m.put("requestCalls", "每chunk同步getChunkCells -> calculateHydrologyChunk；不插入诊断预查询/额外provider");
        m.put("canonicalOrder", "采集全部返回值后才排序：chunkX asc,chunkZ asc；chunk内blockX asc,blockZ asc");
        m.put("regionSizeBlocks", staticInt(ModelHydrologyProvider.class, "REGION"));
        m.put("downstreamReferenceSeed", ViewSpec.SEED);
        m.put("downstreamReference", "既有baseline-local-v2/default/window-00/columns.raw.bin："
                + "(277,-513)水位193.17286705636423 -> (277,-512)192.53280159058212；先(17,-32)侧");
        m.put("downstreamDirectionScope", "固定参考seed/TOML局部河段；其它seed/配置仍用同一排列，不声称自动识别实际流向");
        m.put("measurementScope", "有序调用但生产可异步预热；采集后才作摘要；非性能测量，无wall/CPU/分配数字");
        m.put("rebuildEvidenceLayer", "completed-terrain-chunk-cache-only；不清provider/erosionTile缓存，不重新播种");
        m.put("notCovered", List.of("自然LRU淘汰", "region/pass1/domain缓存重建", "erosion tile淘汰重建",
                "取消/失败重试", "任意seed的实际上下游识别", "Forge落块/装饰/洞穴/流体tick"));
        return m;
    }

    static Map<String, Object> runOrder(Spec spec, ConfigInput cfg, Built built) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("performanceMeasurement", false);
        m.put("workload", orderWorkload(spec));
        m.put("precondition", checkOrderPreconditions(spec, cfg, built));
        List<OrderChunk> plan = orderPlan(spec.selection());
        // 保存实际返回对象；全组采集完成后才序列化raw bits/计算SHA/按坐标排序，不事后重取缓存。
        List<OrderBits> first = orderBits(collectOrder(built, plan));
        m.put("perChunk", orderRows(first));
        m.put("orderSha256", orderSha(spec.seed(), first));
        m.put("orderSha256Scope", "order-v1 + seed + chunk数 + 按坐标排序的(cx,cz,cellsSHA,columnsSHA)；"
                + "Cells/columns用snapshot同款hashCell/hashColumn全字段raw bits；不含selection/请求次序/label");
        m.put("columnsFieldOrder", List.of(COLUMN_FIELDS));
        m.put("valid", true);
        m.put("validityNotes", List.of("采集完整；需在其它独立JVM用--expect-sha比对，单次不宣称顺序无关已通过"));
        if ("rebuild".equals(spec.selection())) {
            try {
                Map<String, Object> replay = rebuildOrder(built, spec.seed(), plan, first);
                m.put("rebuild", replay);
                m.put("rebuildEqual", replay.get("rawBitsEqual"));
                m.put("validityNotes", List.of("仅已完成chunk缓存受控清理/原序重建；逐chunk原始字节比对见rebuild，非自然LRU证据"));
            } catch (OrderPrecondition e) {
                m.put("valid", false);
                m.put("error", e.getMessage());
                m.put("validityNotes", List.of("重建前提未证实，exit2；不得据首次摘要宣布重建等价"));
            }
        }
        return m;
    }

    static void orderRequire(boolean ok, String message) {
        if (!ok) throw new OrderPrecondition(message);
    }

    static Map<String, Object> checkOrderPreconditions(Spec spec, ConfigInput cfg, Built built) {
        orderRequire(!cfg.corrected(), "order需有效真实TOML；Forge校正过配置，不能当原输入证据");
        orderRequire(built.terrain().riversEnabled() && built.terrain().usesModelPipeline(), "order需生产model河流已启用");
        orderRequire(!Thread.currentThread().isInterrupted(), "order采集线程已中断");
        orderRequire(((Number) orderField(CellGenerator.class, built.gen(), "worldSeed")).longValue() == spec.seed()
                && ((Number) orderField(ModelHydrologyProvider.class, built.provider(), "seed")).longValue() == spec.seed(),
                "terrain.seed未传播到generator/provider");
        Map<String, Object> m = new LinkedHashMap<>();
        for (String name : List.of("SHARED_CACHE", "P1_CACHE", "COARSE_CACHE", "DOMAIN_CACHE")) {
            int size = orderFieldSize(ModelHydrologyProvider.class, null, name);
            m.put(name, size);
            orderRequire(size == 0, "order需独立新JVM，初始" + name + "非空: " + size);
        }
        CacheStats stats = built.terrain().chunkCacheStats();
        orderRequire(orderFieldSize(GeoGenesisTerrain.class, built.terrain(), "cache") == 0
                && stats.hits() == 0 && stats.misses() == 0 && built.gen().erosionTileCacheSize() == 0,
                "order需尚未采样的独立Built/chunk/tile缓存");
        Map<String, Integer> busy = orderBusy(built);
        orderRequire(busy.values().stream().allMatch(n -> n == 0), "order初始仍有在途/预热: " + busy);
        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        orderRequire(region >= 16 && region % 16 == 0, "order region必须按chunk对齐");
        Set<Long> regions = new LinkedHashSet<>();
        for (OrderChunk c : fixedOrderChunks()) {
            orderRequire(c.cz() < 0, "order固定集必须包含负坐标");
            regions.add(pack(Math.floorDiv(c.cx() * 16 + 8, region), Math.floorDiv(c.cz() * 16 + 8, region)));
        }
        orderRequire(regions.size() >= 2, "当前region尺寸下固定8chunk未跨region边界");
        m.put("uniqueRegions", regions.size());
        m.put("initialInflightAndPreheat", busy);
        m.put("seeding", "build唯一调用terrain.seed(seed)，沿用terrain持有的provider，无第二实例");
        return m;
    }

    static List<OrderCapture> collectOrder(Built built, List<OrderChunk> plan) {
        List<OrderCapture> returned = new ArrayList<>();
        for (OrderChunk c : plan) {
            orderRequire(!Thread.currentThread().isInterrupted(), "order采集线程已中断");
            Cell[] cells = built.terrain().getChunkCells(c.cx(), c.cz());
            HydrologyChunkResult columns = built.terrain().calculateHydrologyChunk(c.cx(), c.cz());
            returned.add(new OrderCapture(c, cells, columns.carvedColumns()));
        }
        orderRequire(!Thread.currentThread().isInterrupted(), "order采集期间线程被中断");
        return returned;
    }

    static List<OrderBits> orderBits(List<OrderCapture> captures) {
        List<OrderBits> bits = new ArrayList<>();
        Set<OrderChunk> seen = new LinkedHashSet<>();
        for (OrderCapture capture : captures) {
            orderRequire(seen.add(capture.chunk()), "order重复chunk: " + capture.chunk());
            byte[] cells = chunkRawBytes(capture.cells());
            byte[] columns = orderColumnBytes(capture.chunk(), capture.columns());
            bits.add(new OrderBits(capture.chunk(), cells, columns,
                    HexFormat.of().formatHex(newSha().digest(cells)), HexFormat.of().formatHex(newSha().digest(columns))));
        }
        bits.sort(Comparator.comparing(OrderBits::chunk));
        return bits;
    }

    static byte[] orderColumnBytes(OrderChunk chunk, List<HydrologyBlockCarvedColumn> columns) {
        orderRequire(columns != null && columns.size() == 256, "order每chunk必须完整256列: " + chunk);
        List<HydrologyBlockCarvedColumn> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparingInt(HydrologyBlockCarvedColumn::blockX)
                .thenComparingInt(HydrologyBlockCarvedColumn::blockZ));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hasher hash = new Hasher(bytes);
        for (int i = 0; i < 256; i++) {
            HydrologyBlockCarvedColumn col = sorted.get(i);
            orderRequire(col.blockX() == chunk.cx() * 16 + i / 16 && col.blockZ() == chunk.cz() * 16 + i % 16,
                    "order列缺失/重复/越界: " + chunk + " index=" + i);
            hashColumn(hash, col);
        }
        hash.flush();
        return bytes.toByteArray();
    }

    static String orderSha(long seed, List<OrderBits> chunks) {
        List<OrderBits> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparing(OrderBits::chunk));
        Hasher hash = new Hasher();
        hash.str("HydrologyPerfProbe.order-v1");
        hash.l(seed);
        hash.i(sorted.size());
        for (OrderBits c : sorted) {
            hash.i(c.chunk().cx()); hash.i(c.chunk().cz());
            hash.str(c.cellsSha()); hash.str(c.columnsSha());
        }
        return hash.hex();
    }

    static List<Map<String, Object>> orderRows(List<OrderBits> chunks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrderBits c : chunks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cx", c.chunk().cx()); row.put("cz", c.chunk().cz());
            row.put("cells", 256); row.put("columns", 256);
            row.put("cellsSha256", c.cellsSha()); row.put("columnsSha256", c.columnsSha());
            rows.add(row);
        }
        return rows;
    }

    static Map<String, Object> rebuildOrder(Built built, long seed, List<OrderChunk> plan, List<OrderBits> first) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidenceLayer", "completed-terrain-chunk-cache-only; provider/tile未清、seed未变；非自然LRU");
        m.put("requestOrder", plan);
        m.put("quiescentCountsBeforeClear", awaitOrderIdle(built));
        m.put("quiescentObservationsRequired", 3);
        m.put("clearedCompletedChunkEntries", clearCompletedOrderChunks(built));
        m.put("chunkEntriesImmediatelyAfterClear", orderFieldSize(GeoGenesisTerrain.class, built.terrain(), "cache"));
        CacheStats stats = built.terrain().chunkCacheStats();
        long hits = stats.hits(), misses = stats.misses();
        List<OrderBits> replay = orderBits(collectOrder(built, plan));
        long dh = stats.hits() - hits, dm = stats.misses() - misses;
        orderRequire(dh == 0 && dm == plan.size(), "chunk重建未确认全miss: hits=" + dh + " misses=" + dm);
        m.put("chunkHitsDuringReplay", dh); m.put("chunkMissesDuringReplay", dm);
        m.put("perChunk", orderRows(replay));
        m.put("orderSha256", orderSha(seed, replay));
        m.putAll(compareOrderBits(first, replay));
        return m;
    }

    static Map<String, Object> compareOrderBits(List<OrderBits> first, List<OrderBits> replay) {
        orderRequire(first.size() == replay.size(), "chunk重建数量不同");
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean equal = true;
        for (int i = 0; i < first.size(); i++) {
            OrderBits a = first.get(i), b = replay.get(i);
            orderRequire(a.chunk().equals(b.chunk()), "chunk重建坐标集合不同");
            boolean cells = Arrays.equals(a.cells(), b.cells()), columns = Arrays.equals(a.columns(), b.columns());
            rows.add(Map.of("cx", a.chunk().cx(), "cz", a.chunk().cz(),
                    "cellsRawBitsEqual", cells, "columnsRawBitsEqual", columns));
            equal &= cells && columns;
        }
        return Map.of("rawBitsEqual", equal, "perChunkRawBits", rows,
                "comparisonScope", "第一次保存的全字段字节与重建实际返回字节Arrays.equals，非identity/仅SHA比较");
    }

    // 只读观察在途与预热；绝不shutdown/cancel/清future，缺少任一观察点即exit2。
    static Object orderField(Class<?> cls, Object owner, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new OrderPrecondition("order前提字段不可读: " + cls.getSimpleName() + "." + name + ": " + e);
        }
    }

    static int orderFieldSize(Class<?> cls, Object owner, String name) {
        Object value = orderField(cls, owner, name);
        if (value instanceof Map<?, ?> map) { synchronized (map) { return map.size(); } }
        if (value instanceof Set<?> set) return set.size();
        throw new OrderPrecondition("order前提字段不是Map/Set: " + name);
    }

    static Map<String, Integer> orderBusy(Built built) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String name : List.of("P1_INFLIGHT", "SHARED_INFLIGHT", "COARSE_INFLIGHT",
                "DOMAIN_INFLIGHT", "DOMAIN_FINE_INFLIGHT")) {
            m.put(name, orderFieldSize(ModelHydrologyProvider.class, null, name));
        }
        Object queue = orderField(ModelHydrologyProvider.class, built.provider(), "preheatQueue");
        orderRequire(queue instanceof Number, "provider.preheatQueue不可观测");
        m.put("providerPreheatOutstanding", ((Number) queue).intValue());
        orderPoolCounts(m, "providerPreheat", orderField(ModelHydrologyProvider.class, built.provider(), "preheat"));
        m.put("erosionTilesInFlight", orderFieldSize(CellGenerator.class, built.gen(), "erosionTilesInFlight"));
        m.put("queuedTilePreheats", orderFieldSize(CellGenerator.class, built.gen(), "queuedTilePreheats"));
        orderPoolCounts(m, "tilePreheater", orderField(CellGenerator.class, null, "TILE_PREHEATER"));
        orderPoolCounts(m, "tileSampler", CellGenerator.TILE_SAMPLER);
        Object spawn = orderField(GeoGenesisTerrain.class, built.terrain(), "preloadSpawnScheduled");
        orderRequire(spawn instanceof java.util.concurrent.atomic.AtomicBoolean, "spawn预热状态不可观测");
        m.put("spawnPreloadScheduled", ((java.util.concurrent.atomic.AtomicBoolean) spawn).get() ? 1 : 0);
        return m;
    }

    static void orderPoolCounts(Map<String, Integer> counts, String name, Object value) {
        orderRequire(value instanceof ThreadPoolExecutor, name + "不是可观测ThreadPoolExecutor");
        ThreadPoolExecutor pool = (ThreadPoolExecutor) value;
        orderRequire(!pool.isShutdown(), name + "已停止，不能代表生产预热策略");
        counts.put(name + "Active", pool.getActiveCount());
        counts.put(name + "Queued", pool.getQueue().size());
    }

    static Map<String, Integer> awaitOrderIdle(Built built) {
        long deadline = System.nanoTime() + 120_000_000_000L; // 仅等待上限，绝非性能样本/生产预算。
        int stable = 0;
        while (true) {
            Map<String, Integer> counts = orderBusy(built);
            stable = counts.values().stream().allMatch(n -> n == 0) ? stable + 1 : 0;
            if (stable >= 3) return counts;
            orderRequire(System.nanoTime() < deadline, "120s内未证实在途/预热排空；未清缓存: " + counts);
            try { Thread.sleep(100); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrderPrecondition("等待排空被中断；未清缓存");
            }
        }
    }

    static int clearCompletedOrderChunks(Built built) {
        Object value = orderField(GeoGenesisTerrain.class, built.terrain(), "cache");
        orderRequire(value instanceof Map<?, ?>, "terrain.cache不是已完成chunk Map");
        Map<?, ?> cache = (Map<?, ?>) value;
        synchronized (cache) {
            Map<String, Integer> counts = orderBusy(built);
            orderRequire(counts.values().stream().allMatch(n -> n == 0), "清前又有在途/预热；未清缓存: " + counts);
            Set<Long> expected = new LinkedHashSet<>();
            for (OrderChunk c : fixedOrderChunks()) expected.add(pack(c.cx(), c.cz()));
            orderRequire(cache.keySet().equals(expected), "已完成chunk缓存集合不等于固定8chunk；未清缓存");
            for (Object cells : cache.values()) {
                orderRequire(cells instanceof Cell[] array && array.length == 256, "缓存中有非完成Cell[]；未清缓存");
            }
            int removed = cache.size();
            cache.clear(); // 唯一受控失效点；不触碰provider.clear、侵蚀tile或任何在途表。
            orderRequire(cache.isEmpty(), "已完成chunk缓存未清空");
            return removed;
        }
    }

    // ==================================================================
    // mode: snapshot（五局部窗口全字段 raw-bit）
    // ==================================================================

    static Map<String, Object> runSnapshot(Spec spec, ConfigInput cfg, Built built) throws Exception {
        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        List<Map<String, Object>> windows = new ArrayList<>();
        Map<Long, Built> bySeed = new LinkedHashMap<>();
        bySeed.put(spec.seed(), built);
        boolean complete = true;
        List<String> shas = new ArrayList<>();
        MetricsSnap metrics0 = metricsSnap();
        long t0 = System.nanoTime();
        int windowIndex = 0;
        for (Win w : spec.windows()) {
            Built seeded = bySeed.computeIfAbsent(w.seed(), s -> build(cfg, s));
            Path evidenceDir = spec.out().resolve(String.format(Locale.ROOT, "window-%02d", windowIndex++));
            Map<String, Object> row = snapshotWindow(seeded, region, w, evidenceDir);
            windows.add(row);
            complete &= Boolean.TRUE.equals(row.get("complete"));
            shas.add(String.valueOf(row.get("windowSha256")));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("windows", spec.windows().size());
        workload.put("defaultWindows", spec.windows().equals(defaultWindows()));
        workload.put("seedIsolation", "每seed独立Built，生成器播种后不再变更；严隔离建议每窗独立JVM");
        workload.put("regionSizeBlocks", region);
        workload.put("step", staticInt(ModelHydrologyProvider.class, "STEP"));
        workload.put("windowOrder", spec.windows().stream().map(Win::toString).toList());
        m.put("workload", workload);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("totalWallMs", ms(System.nanoTime() - t0));
        timing.put("scope", "snapshot 含一次额外 columnsFor/chunk 与摘要，不是性能测量");
        m.put("timing", timing);
        m.put("windows", windows);
        m.put("instrumentation", metricsDelta(metrics0, metricsSnap()));
        m.put("snapshotSha256", combine(shas.toArray(new String[0])));
        m.put("snapshotScope", "五窗口 windowSha256 按 workload.windowOrder 顺序串联；A/B 用它做一次比较");
        m.put("fieldOrder", CELL_FIELD_ORDER);
        m.put("valid", complete);
        m.put("validityNotes", complete
                ? List.of("五窗口采集完成（每 chunk 256 列；region 证据可读）")
                : List.of("存在缺证据窗口（见 windows[].notes）；不得当作全字段等价已证"));
        return m;
    }

    static Map<String, Object> snapshotWindow(Built built, int region, Win w, Path evidenceDir) throws Exception {
        MetricsSnap metrics0 = metricsSnap();
        long t0 = System.nanoTime();
        int x0 = w.cx() - w.radius(), x1 = w.cx() + w.radius();
        int z0 = w.cz() - w.radius(), z1 = w.cz() + w.radius();
        int cx0 = Math.floorDiv(x0, 16), cx1 = Math.floorDiv(x1, 16);
        int cz0 = Math.floorDiv(z0, 16), cz1 = Math.floorDiv(z1, 16);
        Map<Long, Cell[]> chunks = new LinkedHashMap<>();
        CacheStats stats = built.terrain().chunkCacheStats();
        CacheStats tileStats = built.gen().tileCacheStats();
        long h0 = stats.hits(), m0 = stats.misses(), e0 = stats.evictions();
        long th0 = tileStats.hits(), tm0 = tileStats.misses(), te0 = tileStats.evictions();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Cell[] cells = built.terrain().getChunkCells(cx, cz);
                if (cells == null || cells.length != 256) {
                    throw new IllegalStateException("chunk(" + cx + "," + cz + ") 返回非 256 Cell");
                }
                chunks.put(pack(cx, cz), cells);
            }
        }
        boolean hydrologyOn = built.terrain().riversEnabled();
        double sea = built.terrain().seaLevel();
        writeRawSchema(evidenceDir);
        int columns = 0, riverFlag = 0, lakeFlag = 0;
        int[] kind = new int[4];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        String cellsSha;
        RawHeader cellHeader = new RawHeader("cells", w.seed(), x0, z0, x1, z1,
                (x1 - x0 + 1) * (z1 - z0 + 1));
        try (RawEvidence raw = new RawEvidence(evidenceDir.resolve("cells.raw.bin"), cellHeader)) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                Cell[] cells = chunks.get(pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
                Cell cell = cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                raw.coordinate(x, z);
                hashCell(raw.hash, cell);
                kind[ProductionViewProbe.waterKind(cell, hydrologyOn, sea)]++;
                if (cell.riverType != 0) riverFlag++;
                if (cell.isLake) lakeFlag++;
                min = Math.min(min, cell.height);
                max = Math.max(max, cell.height);
                columns++;
            }
            cellsSha = raw.finishHash();
        }
        List<HydrologyBlockCarvedColumn> allColumns = new ArrayList<>();
        int duplicateBlocks = 0;
        String orderedSha;
        RawHeader colHeader = new RawHeader("columns", w.seed(), x0, z0, x1, z1, chunks.size() * 256);
        try (RawEvidence raw = new RawEvidence(evidenceDir.resolve("columns.raw.bin"), colHeader)) {
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
                HydrologyChunkResult result = built.terrain().calculateHydrologyChunk(cx, cz);
                List<HydrologyBlockCarvedColumn> cols = result.carvedColumns();
                if (cols.size() != 256) throw new IllegalStateException("chunk columns != 256");
                for (HydrologyBlockCarvedColumn col : cols) {
                    if (Math.floorDiv(col.blockX(), 16) != cx || Math.floorDiv(col.blockZ(), 16) != cz) {
                        throw new IllegalStateException("column outside requested chunk");
                    }
                    raw.coordinate(col.blockX(), col.blockZ());
                    hashColumn(raw.hash, col);
                }
                allColumns.addAll(cols);
            }
            orderedSha = raw.finishHash();
        }
        List<HydrologyBlockCarvedColumn> sortedCols = new ArrayList<>(allColumns);
        sortedCols.sort(Comparator.comparingInt(HydrologyBlockCarvedColumn::blockX)
                .thenComparingInt(HydrologyBlockCarvedColumn::blockZ));
        Hasher sortedHasher = new Hasher();
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (HydrologyBlockCarvedColumn col : sortedCols) {
            if (col.blockX() == lastX && col.blockZ() == lastZ) duplicateBlocks++;
            lastX = col.blockX();
            lastZ = col.blockZ();
            hashColumn(sortedHasher, col);
        }
        String sortedSha = sortedHasher.hex();
        List<Map<String, Object>> regions = new ArrayList<>();
        Set<Long> regionKeys = new LinkedHashSet<>();
        // 只取生产真正消费过的 region：columnsFor 用 chunk 中心 blockX0+8 入区（regionFor 内 floorDiv）。
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                int rx = Math.floorDiv(cx * 16 + 8, region);
                int rz = Math.floorDiv(cz * 16 + 8, region);
                if (regionKeys.add(pack(rx, rz))) regions.add(regionEvidence(built, region, rx, rz));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", w.seed());
        m.put("center", List.of(w.cx(), w.cz()));
        m.put("radiusBlocks", w.radius());
        m.put("bounds", List.of(x0, z0, x1, z1));
        m.put("rawEvidenceDirectory", evidenceDir.toAbsolutePath().toString());
        m.put("rawEvidenceFormat", "HPR2; cells.raw.bin / columns.raw.bin / raw-schema.json; compare-raw定位首差");
        m.put("cellsRawFileSha256", sha256(evidenceDir.resolve("cells.raw.bin")));
        m.put("columnsRawFileSha256", sha256(evidenceDir.resolve("columns.raw.bin")));
        m.put("chunks", chunks.size());
        m.put("chunkHits", stats.hits() - h0);
        m.put("chunkMisses", stats.misses() - m0);
        m.put("chunkEvictions", stats.evictions() - e0);
        m.put("erosionTileHits", tileStats.hits() - th0);
        m.put("erosionTileMisses", tileStats.misses() - tm0);
        m.put("erosionTileEvictions", tileStats.evictions() - te0);
        m.put("cells", columns);
        m.put("columns", allColumns.size());
        m.put("duplicateBlocks", duplicateBlocks);
        m.put("riverTypeNonZero", riverFlag);
        m.put("isLakeColumns", lakeFlag);
        m.put("waterKind", waterKindMap(kind));
        m.put("minHeight", num(min));
        m.put("maxHeight", num(max));
        m.put("cellsSha256", cellsSha);
        m.put("cellsOrder", "窗口行列序 x asc -> z asc；每格按 CELL_FIELD_ORDER 全字段 raw bits");
        m.put("columnsSha256Ordered", orderedSha);
        m.put("columnsSha256Sorted", sortedSha);
        m.put("columnsScope", "生产 calculateHydrologyChunk->columnsFor；original/carved/surface/lip/erosion/mask/"
                + "fillWater/lakePlan/lakeNode/lakeLevelY raw bits；ordered=生产返回顺序，sorted=按 blockX,blockZ");
        m.put("regions", regions);
        List<String> notes = new ArrayList<>();
        if (duplicateBlocks != 0) notes.add("存在重复 block 列: " + duplicateBlocks);
        boolean regionsComplete = true;
        for (Map<String, Object> r : regions) {
            if (!Boolean.TRUE.equals(r.get("complete"))) regionsComplete = false;
        }
        if (!regionsComplete) notes.add("有 region 缺骨架/段索引证据（见 regions[].notes）");
        m.put("notes", notes);
        m.put("complete", duplicateBlocks == 0 && regionsComplete);
        m.put("windowSha256", combine(new String[]{w.toString(), String.valueOf(chunks.size()),
                cellsSha, orderedSha, sortedSha,
                regions.stream().map(r -> String.valueOf(r.get("regionSha256"))).reduce("", String::concat)}));
        m.put("wallMs", ms(System.nanoTime() - t0));
        m.put("instrumentation", metricsDelta(metrics0, metricsSnap()));
        return m;
    }

    record Evidence(double[] seg, double[] limits, List<LakeWidthEnvelope.Interface> interfaces,
                    List<double[]> nodeDepths) { }

    /** 唯一反射点：private regionFor → RegionData.skeletonSeg → SegmentIndex.seg/widthLimits/lakeInterfaces/nodeDepths。 */
    static Evidence evidenceOf(ModelHydrologyProvider prov, int rx, int rz) throws ReflectiveOperationException {
        int region = staticInt(ModelHydrologyProvider.class, "REGION");
        Method regionFor = ModelHydrologyProvider.class.getDeclaredMethod("regionFor", int.class, int.class);
        regionFor.setAccessible(true);
        // 与 finalSkeletonForProbe / WidthContinuityProbe 同一点法：region 中心入区（regionFor 内 floorDiv）。
        Object rd = regionFor.invoke(prov, rx * region + region / 2, rz * region + region / 2);
        Method acc = rd.getClass().getDeclaredMethod("skeletonSeg");
        acc.setAccessible(true);
        Object si = acc.invoke(rd);
        if (si == null) return new Evidence(null, null, null, null);
        Field segF = si.getClass().getDeclaredField("seg");
        segF.setAccessible(true);
        Field limF = si.getClass().getDeclaredField("widthLimits");
        limF.setAccessible(true);
        Field ifF = si.getClass().getDeclaredField("lakeInterfaces");
        ifF.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<LakeWidthEnvelope.Interface> ifaces = (List<LakeWidthEnvelope.Interface>) ifF.get(si);
        return new Evidence((double[]) segF.get(si), (double[]) limF.get(si),
                ifaces == null ? List.of() : List.copyOf(ifaces), nodeDepths(si));
    }

    static List<double[]> nodeDepths(Object si) throws ReflectiveOperationException {
        Field field;
        try {
            field = si.getClass().getDeclaredField("nodeDepths");
        } catch (NoSuchFieldException absent) {
            return null;
        }
        field.setAccessible(true);
        Object value = field.get(si);
        if (value == null) return null;
        if (!(value instanceof List<?> rows)) throw new IllegalStateException("nodeDepths is not a List");
        List<double[]> copy = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row != null && !(row instanceof double[])) throw new IllegalStateException("nodeDepths row");
            copy.add(row == null ? null : ((double[]) row).clone());
        }
        return copy;
    }

    static Map<String, Object> regionEvidence(Built built, int region, int rx, int rz) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rx", rx);
        m.put("rz", rz);
        ModelHydrologyProvider prov = built.provider();
        DeterministicSkeleton.Result sk = prov.finalSkeletonForProbe(rx, rz);
        if (sk == null) {
            m.put("complete", false);
            m.put("notes", List.of("finalSkeletonForProbe 返回 null（分层路径或未求解）"));
            m.put("regionSha256", "MISSING");
            return m;
        }
        int centerX = rx * region + region / 2, centerZ = rz * region + region / 2;
        double[][] nodes = prov.shapedNodesWorldForProbe(centerX, centerZ);
        String shapeSha = shaShape(nodes);
        Evidence ev = evidenceOf(prov, rx, rz);
        List<BoundaryPort> pass1Ports = prov.pass1PortsForProbe(rx, rz);
        List<BoundaryPort> pass1Pending = prov.pass1PendingForProbe(rx, rz);
        List<String> notes = new ArrayList<>();
        if (ev.seg() == null) notes.add("skeletonSeg 为 null（无段索引证据）");
        if (ev.limits() == null) notes.add("widthLimits 为 null（无上限证据）");
        if (!prov.shapeAlignedForProbe(rx, rz)) notes.add("shape 与 traces 下标不对齐");
        m.put("traces", sk.traces.size());
        m.put("polys", nodes.length);
        m.put("segRecords", ev.seg() == null ? null : ev.seg().length / 8);
        m.put("widthLimitEntries", ev.limits() == null ? null : ev.limits().length);
        m.put("lakeInterfaces", ev.interfaces() == null ? null : ev.interfaces().size());
        m.put("portCount", sk.ports.size());
        m.put("pendingExits", sk.pendingExits.size());
        m.put("exitSeeds", sk.exitSeeds.size());
        m.put("rivers", sk.rivers.size());
        m.put("pass1PortCount", pass1Ports.size());
        m.put("pass1PendingCount", pass1Pending.size());
        m.put("pass1PortsSha256Ordered", shaPorts(pass1Ports));
        m.put("pass1PendingSha256Ordered", shaPorts(pass1Pending));
        m.put("shapeAligned", prov.shapeAlignedForProbe(rx, rz));
        m.put("shapeSha256", shapeSha);
        m.put("segSha256", ev.seg() == null ? "MISSING" : shaDoubles(ev.seg()));
        m.put("widthLimitsSha256", ev.limits() == null ? "MISSING" : shaDoubles(ev.limits()));
        m.put("lakeInterfacesSha256", shaInterfaces(ev.interfaces()));
        m.put("portsSha256Ordered", shaPorts(sk.ports));
        m.put("pendingSha256Ordered", shaPorts(sk.pendingExits));
        m.put("tracesSha256", shaTraces(sk.traces));
        m.put("nodeDepthsSha256", shaNodeDepths(ev.nodeDepths()));
        m.put("regionSha256", combine(new String[]{rx + ":" + rz, shapeSha,
                m.get("segSha256").toString(), m.get("widthLimitsSha256").toString(),
                m.get("lakeInterfacesSha256").toString(), m.get("portsSha256Ordered").toString(),
                m.get("pendingSha256Ordered").toString(), m.get("tracesSha256").toString(),
                m.get("nodeDepthsSha256").toString(),
                m.get("pass1PortsSha256Ordered").toString(), m.get("pass1PendingSha256Ordered").toString()}));
        m.put("complete", ev.seg() != null && ev.limits() != null && notes.isEmpty());
        m.put("notes", notes);
        return m;
    }

    static String shaShape(double[][] nodes) {
        Hasher h = new Hasher();
        h.i(nodes.length);
        for (double[] poly : nodes) {
            h.i(poly.length);
            for (double v : poly) h.d(v);
        }
        return h.hex();
    }

    static String shaDoubles(double[] values) {
        Hasher h = new Hasher();
        hashDoubles(h, values);
        return h.hex();
    }

    static void hashDoubles(Hasher h, double[] values) {
        h.i(values == null ? -1 : values.length);
        if (values != null) for (double v : values) h.d(v);
    }

    static String shaPorts(List<BoundaryPort> ports) {
        Hasher h = new Hasher();
        h.i(ports.size());
        for (BoundaryPort p : ports) hashPort(h, p);
        return h.hex();
    }

    static void hashPort(Hasher h, BoundaryPort p) {
        h.b(p != null);
        if (p == null) return;
        h.i(p.senderRegionX());
        h.i(p.senderRegionZ());
        h.i(p.anchorX());
        h.i(p.anchorZ());
        h.i(p.nextX());
        h.i(p.nextZ());
        h.i(p.receiverRegionX());
        h.i(p.receiverRegionZ());
        h.d(p.dirX());
        h.d(p.dirZ());
        h.i(p.cell());
        h.i(p.dRX());
        h.i(p.dRZ());
    }

    static String shaInterfaces(List<LakeWidthEnvelope.Interface> interfaces) {
        Hasher h = new Hasher();
        if (interfaces == null) {
            h.i(-1);
            return h.hex();
        }
        h.i(interfaces.size());
        for (LakeWidthEnvelope.Interface i : interfaces) {
            h.str(i.kind());
            h.i(i.poly());
            h.i(i.cell());
            h.i(i.basin());
            h.str(i.status());
            h.d(i.maxHalf());
            h.d(i.transverse());
            h.i(i.samples());
        }
        return h.hex();
    }

    static String shaTraces(List<DeterministicSkeleton.Trace> traces) {
        Hasher h = new Hasher();
        h.i(traces.size());
        for (DeterministicSkeleton.Trace t : traces) {
            h.i(t.cell == null ? -1 : t.cell.length);
            if (t.cell != null) for (int cell : t.cell) h.i(cell);
            hashDoubles(h, t.contPath);
            hashDoubles(h, t.outsideTail);
            h.i(t.geometryPrefixNodes);
            h.b(t.fromOutlet);
            h.str(t.end == null ? null : t.end.name());
            hashPort(h, t.port);
        }
        return h.hex();
    }

    static String shaNodeDepths(List<double[]> rows) {
        Hasher h = new Hasher();
        if (rows == null) {
            h.i(-1);
            return h.hex();
        }
        h.i(rows.size());
        for (double[] row : rows) {
            if (row == null) {
                h.i(-1);
                continue;
            }
            h.i(row.length);
            for (double v : row) h.d(v);
        }
        return h.hex();
    }

    static String combine(String... parts) {
        Hasher h = new Hasher();
        for (String p : parts) h.str(p);
        return h.hex();
    }

    // ==================================================================
    // Cell / 列 raw-bit
    // ==================================================================

    static void hashCell(Hasher h, Cell c) {
        h.d(c.height);
        h.d(c.continent);
        h.d(c.e);
        h.d(c.eOcean);
        h.d(c.blendCont);
        h.d(c.eLand);
        h.d(c.eClimate);
        h.d(c.snowLineE);
        h.d(c.coastCoord);
        if (c.typeWeights == null) {
            h.i(-1);
        } else {
            h.i(c.typeWeights.length);
            for (double v : c.typeWeights) h.d(v);
        }
        h.i(c.terrainType == null ? -1 : c.terrainType.ordinal());
        h.i(c.variantTerrain == null ? -1 : c.variantTerrain.ordinal());
        h.b(c.climate != null);
        Climate cl = c.climate == null ? Climate.DEFAULT : c.climate;
        h.d(cl.temperature());
        h.d(cl.humidity());
        h.d(cl.continentality());
        h.d(cl.precipitation());
        h.i(c.biomeType == null ? -1 : c.biomeType.ordinal());
        h.d(c.temperature);
        h.d(c.humidity);
        h.d(c.precipitation);
        h.d(c.continentNoise);
        h.b(c.isLake);
        h.b(c.lakeMask);
        h.b(c.erosionMask);
        h.d(c.shape);
        h.d(c.riverNetDischarge);
        h.d(c.riverDistance);
        h.d(c.oasisNoise);
        h.f(c.gradient);
        h.i(c.rockTypeId);
        h.i(c.rockLayer);
        h.i(c.rockSeqPacked);
        h.d(c.rockTilt);
        h.i(c.riverType);
        h.d(c.riverSurfaceY);
        h.d(c.riverLipY);
        h.b(c.isSnow);
        if (c.oceanFeat == null) {
            h.b(false);
        } else {
            h.b(true);
            h.d(c.oceanFeat.total);
            h.d(c.oceanFeat.ridge);
            h.d(c.oceanFeat.seamount);
            h.d(c.oceanFeat.arc);
            h.d(c.oceanFeat.baseE);
        }
        if (c.landFeat == null) {
            h.b(false);
        } else {
            h.b(true);
            h.d(c.landFeat.total);
            h.d(c.landFeat.single);
            h.d(c.landFeat.field);
            h.d(c.landFeat.singleEdifice);
            h.d(c.landFeat.fieldEdifice);
        }
    }

    static void hashColumn(Hasher h, HydrologyBlockCarvedColumn col) {
        if (col.lakeNode() != null) throw new IllegalStateException("non-null legacy lakeNode has no raw schema");
        h.i(col.blockX());
        h.i(col.blockZ());
        h.d(col.originalGroundY());
        h.d(col.carvedGroundY());
        h.d(col.waterSurfaceY());
        h.d(col.lipSurfaceY());
        h.d(col.erosion());
        h.d(col.erosionMask());
        h.b(col.fillWater());
        h.b(col.lakePlan());
        h.b(col.lakeNode() == null);
        h.d(col.lakeLevelY());
    }

    /** 固定顺序、定长缓冲的 raw-bit 哈希器（不分配 per-value 数组）。 */
    static final class Hasher {
        private final MessageDigest md;
        private final byte[] buf = new byte[512];
        private final OutputStream tap;
        private int n;

        Hasher() { this(null); }

        Hasher(OutputStream tap) {
            md = newSha();
            this.tap = tap;
        }

        void l(long v) {
            if (n + 8 > buf.length) flush();
            for (int i = 0; i < 8; i++) buf[n++] = (byte) (v >>> (56 - 8 * i));
        }

        void d(double v) {
            l(Double.doubleToRawLongBits(v));
        }

        void f(float v) {
            l(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
        }

        void i(int v) {
            l(v);
        }

        void b(boolean v) {
            l(v ? 1L : 0L);
        }

        void str(String v) {
            if (v == null) {
                i(-1);
                return;
            }
            byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
            i(bytes.length);
            flush();
            md.update(bytes);
            tap(bytes, bytes.length);
        }

        void tap(byte[] bytes, int length) {
            if (tap == null) return;
            try { tap.write(bytes, 0, length); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }

        void flush() {
            if (n > 0) {
                md.update(buf, 0, n);
                tap(buf, n);
                n = 0;
            }
        }

        String hex() {
            flush();
            return HexFormat.of().formatHex(md.digest());
        }
    }

    static byte[] chunkRawBytes(Cell[] cells) {
        if (cells == null || cells.length != 256) throw new IllegalStateException("returned chunk length != 256");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hasher h = new Hasher(bytes);
        for (Cell cell : cells) hashCell(h, cell);
        h.flush();
        return bytes.toByteArray();
    }

    record RawHeader(String kind, long seed, int x0, int z0, int x1, int z1, int records) { }

    /** HPR2: big-endian header + (int x,int z,按hashCell/hashColumn序的64位slots)；浮点保留raw bits。 */
    static final class RawEvidence implements AutoCloseable {
        final DataOutputStream out;
        final Hasher hash;

        RawEvidence(Path file, RawHeader header) throws IOException {
            out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
            writeRawHeader(out, header);
            hash = new Hasher(out);
        }

        void coordinate(int x, int z) throws IOException {
            hash.flush();
            out.writeInt(x);
            out.writeInt(z);
        }

        String finishHash() { return hash.hex(); }

        @Override public void close() throws IOException {
            try { hash.flush(); } finally { out.close(); }
        }
    }

    static void writeRawHeader(DataOutputStream out, RawHeader h) throws IOException {
        out.writeInt(RAW_MAGIC);
        out.writeUTF(h.kind());
        out.writeLong(h.seed());
        out.writeInt(h.x0()); out.writeInt(h.z0()); out.writeInt(h.x1()); out.writeInt(h.z1());
        out.writeInt(h.records());
    }

    static RawHeader readRawHeader(DataInputStream in) throws IOException {
        if (in.readInt() != RAW_MAGIC) throw new IOException("not HPR2 raw evidence");
        RawHeader h = new RawHeader(in.readUTF(), in.readLong(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt());
        if (!h.kind().equals("cells") && !h.kind().equals("columns")) throw new IOException("unknown kind");
        if (h.records() < 0) throw new IOException("negative record count");
        return h;
    }

    static void writeRawSchema(Path dir) throws IOException {
        Files.createDirectories(dir);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("format", "HPR2; DataOutputStream big-endian; int magic,writeUTF(kind),long seed,int x0,z0,x1,z1,recordCount");
        schema.put("record", "int x,int z,then 64-bit slots; double=rawLongBits,float=unsigned rawIntBits in long,int=sign-extended long,bool=0/1,enum=ordinal or -1");
        schema.put("cellHead", CELL_HEAD);
        schema.put("typeWeights", "long length (-1=null), followed by length double-rawbits slots");
        schema.put("cellTail", CELL_TAIL);
        schema.put("cellOptional", "long oceanFeat.present then 5 slots if present; long landFeat.present then 5 slots if present");
        schema.put("oceanFields", OCEAN_FIELDS);
        schema.put("landFields", LAND_FIELDS);
        schema.put("columnFields", COLUMN_FIELDS);
        schema.put("orders", "cells=x,z asc inside window; columns=production request/returned order for all intersecting chunks");
        Files.writeString(dir.resolve("raw-schema.json"), GSON.toJson(schema) + "\n", StandardCharsets.UTF_8);
    }

    static void readSlots(DataInputStream in, Map<String, Long> values, String[] fields) throws IOException {
        for (String field : fields) values.put(field, in.readLong());
    }

    static Map<String, Long> readRawValues(DataInputStream in, String kind) throws IOException {
        Map<String, Long> values = new LinkedHashMap<>();
        if (kind.equals("columns")) { readSlots(in, values, COLUMN_FIELDS); return values; }
        readSlots(in, values, CELL_HEAD);
        long length = in.readLong();
        if (length < -1 || length > 1_000_000) throw new IOException("invalid typeWeights length: " + length);
        values.put("typeWeights.length", length);
        for (int i = 0; i < length; i++) values.put("typeWeights[" + i + "]", in.readLong());
        readSlots(in, values, CELL_TAIL);
        long ocean = in.readLong();
        values.put("oceanFeat.present", ocean);
        if (ocean != 0) readSlots(in, values, OCEAN_FIELDS);
        long land = in.readLong();
        values.put("landFeat.present", land);
        if (land != 0) readSlots(in, values, LAND_FIELDS);
        return values;
    }

    static Map<String, Object> firstRawDiff(DataInputStream a, DataInputStream b) throws IOException {
        RawHeader ah = readRawHeader(a), bh = readRawHeader(b);
        if (!ah.equals(bh)) return Map.of("equal", false, "field", "header", "a", ah.toString(), "b", bh.toString());
        for (int record = 0; record < ah.records(); record++) {
            int ax = a.readInt(), az = a.readInt(), bx = b.readInt(), bz = b.readInt();
            if (ax != bx || az != bz) return Map.of("equal", false, "record", record,
                    "field", "coordinate/order", "a", ax + "," + az, "b", bx + "," + bz);
            Map<String, Long> av = readRawValues(a, ah.kind()), bv = readRawValues(b, bh.kind());
            Set<String> fields = new LinkedHashSet<>(av.keySet());
            fields.addAll(bv.keySet());
            for (String field : fields) {
                if (!java.util.Objects.equals(av.get(field), bv.get(field))) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("equal", false); diff.put("seed", ah.seed()); diff.put("kind", ah.kind());
                    diff.put("record", record); diff.put("x", ax); diff.put("z", az); diff.put("field", field);
                    diff.put("aBits", rawHex(av.get(field))); diff.put("bBits", rawHex(bv.get(field)));
                    return diff;
                }
            }
        }
        if (a.read() != -1 || b.read() != -1) throw new IOException("trailing raw evidence bytes");
        return Map.of("equal", true, "records", ah.records(), "kind", ah.kind());
    }

    static String rawHex(Long bits) {
        return bits == null ? "ABSENT" : HexFormat.of().toHexDigits(bits);
    }

    static int compareRaw(String[] args) throws IOException {
        require(args.length == 3, "compare-raw <A.bin> <B.bin>");
        try (DataInputStream a = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[1]))));
             DataInputStream b = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[2]))))) {
            Map<String, Object> diff = firstRawDiff(a, b);
            System.out.println(GSON.toJson(diff));
            return Boolean.TRUE.equals(diff.get("equal")) ? 0 : 1;
        }
    }

    static MessageDigest newSha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ==================================================================
    // 样本骨架 / 输出
    // ==================================================================

    static Map<String, Object> baseSample(Spec spec, ConfigInput cfg, Built built) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("probe", "HydrologyPerfProbe");
        root.put("readOnly", true);
        root.put("schemaVersion", 2);
        root.put("mode", spec.mode().name());
        root.put("label", spec.label());
        root.put("generatedAtUtc", Instant.now().toString());
        root.put("seed", spec.seed());
        // 摘要对「字段集 + 顺序」敏感 ⇒ 自描述同一 class 才可比（provenance 另有全类来源）。
        root.put("probeClassSha256", classSha(HydrologyPerfProbe.class));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("toml", spec.toml().toAbsolutePath().toString());
        config.put("sha256", cfg.sha());
        config.put("correctedByForge", cfg.corrected());
        config.put("fingerprint", cfg.fingerprint());
        config.put("horizontalScale", cfg.params().horizontalScale());
        config.put("seaLevel", cfg.params().seaLevel());
        config.put("minY", cfg.params().minY());
        config.put("maxY", cfg.params().maxY());
        config.put("terrainParams", cfg.params().toString());
        root.put("config", config);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("vm", ManagementFactory.getRuntimeMXBean().getVmName()
                + " " + ManagementFactory.getRuntimeMXBean().getVmVersion());
        env.put("processors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        env.put("jvmUptimeMsAtStart", ManagementFactory.getRuntimeMXBean().getUptime());
        env.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        env.put("threadCpuSupported", tm.isCurrentThreadCpuTimeSupported());
        env.put("threadAllocSupported", allocBean() != null);
        env.put("riversEnabled", built.terrain().riversEnabled());
        env.put("terrainSeaLevel", built.terrain().seaLevel());
        env.put("usesModelPipeline", built.terrain().usesModelPipeline());
        env.put("terrain", "CellGenerator(params,-64,320)+GeoGenesisTerrain; terrain.seed(seed)");
        env.put("waterKindSource", "ProductionViewProbe.waterKind");
        env.put("jfr", "external only (-XX:StartFlightRecording=...); no in-process JFR lifecycle");
        root.put("environment", env);
        root.put("cellFieldOrder", CELL_FIELD_ORDER);
        root.put("notes", List.of(
                "本样本是性能观测，不是质量验收；未覆盖 Forge 方块放置/装饰/流体 tick/洞穴/实机 WGP",
                "并发各线程 CPU 不得相加后除以总 wall 当占用率；见 timing.cpuScope",
                "allocated bytes 只覆盖声明的线程范围；不是进程 RSS",
                "改地形/水文产出必须升 PreviewDisplay.CACHE_SCHEMA_VERSION（本探针不升）"));
        return root;
    }

    static void writeJson(Path out, Map<String, Object> root) throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("sample.json"), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    static String summary(Spec spec, Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== HydrologyPerfProbe ===\n");
        sb.append("mode=").append(spec.mode()).append(" seed=").append(spec.seed());
        if (spec.mode() == Mode.snapshot) {
            sb.append(" windows=").append(spec.windows().size()).append(' ').append(spec.windows());
        } else if (spec.mode() == Mode.order) {
            sb.append(" order=").append(spec.selection());
        } else {
            sb.append(" chunk=(").append(spec.cx()).append(',').append(spec.cz()).append(')');
        }
        sb.append('\n');
        sb.append("status=").append(root.get("status")).append(" valid=").append(root.get("valid")).append('\n');
        Object timing = root.get("timing");
        if (timing instanceof Map<?, ?> t) {
            sb.append("timing=").append(t).append('\n');
        }
        if (root.get("cache") instanceof Map<?, ?> c) sb.append("cache=").append(c).append('\n');
        if (root.get("instrumentation") instanceof Map<?, ?> inst) {
            sb.append("instrumentation available=").append(inst.get("available"))
                    .append(" enabled=").append(inst.get("enabled"));
            if (inst.get("reason") != null) sb.append(" reason=").append(inst.get("reason"));
            sb.append('\n');
        }
        if (root.get("result") instanceof Map<?, ?> r) {
            sb.append("result chunks=").append(r.get("chunks")).append(" columns=").append(r.get("columns"))
                    .append(" waterKind=").append(r.get("waterKind"))
                    .append(" cellsSha256=").append(r.get("cellsSha256")).append('\n');
        }
        if (root.get("snapshotSha256") != null) {
            sb.append("snapshotSha256=").append(root.get("snapshotSha256")).append('\n');
        }
        if (root.get("orderSha256") != null) {
            sb.append("orderSha256=").append(root.get("orderSha256")).append('\n');
        }
        if (root.get("rebuildEqual") != null) {
            sb.append("rebuildEqual=").append(root.get("rebuildEqual")).append('\n');
        }
        if (root.get("validityNotes") != null) sb.append("validityNotes=").append(root.get("validityNotes")).append('\n');
        if (root.get("error") != null) sb.append("error=").append(root.get("error")).append('\n');
        return sb.toString();
    }

    static List<String> stack(Throwable t, int frames) {
        StackTraceElement[] all = t.getStackTrace();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(frames, all.length); i++) out.add(all[i].toString());
        return out;
    }

    // ==================================================================
    // 反射薄层 / 工具
    // ==================================================================

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

    static Map<?, ?> sharedCache() {
        try {
            Field f = ModelHydrologyProvider.class.getDeclaredField("SHARED_CACHE");
            f.setAccessible(true);
            Object map = f.get(null);
            if (!(map instanceof Map<?, ?> m)) throw new IllegalStateException("SHARED_CACHE 类型意外: " + map);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SHARED_CACHE 不可读", e);
        }
    }

    static int regionCacheSize() {
        Map<?, ?> map = sharedCache();
        synchronized (map) {
            return map.size();
        }
    }

    static String classSha(Class<?> type) {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) return "MISSING";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Exception e) {
            return "ERROR";
        }
    }

    static String codeSource(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source == null ? "UNAVAILABLE" : source.getLocation().toExternalForm();
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    // ==================================================================
    // 自检（不建地形、不读 TOML ⇒ 可安全快速跑）
    // ==================================================================

    static void selfTest() {
        selfTestChecks = 0;
        // 1) raw-bit 语义：同值稳定、1 ulp / -0.0 / NaN 载荷 / 布尔变化必须变
        Cell a = new Cell();
        a.height = 80.25;
        a.riverType = 1;
        a.riverSurfaceY = 81.5;
        a.riverLipY = 82.5;
        a.erosionMask = true;
        a.isLake = true;
        a.typeWeights = new double[]{0.2, 0.3, 0.5, 0.0, 0.0};
        a.gradient = 0.5f;
        a.rockSeqPacked = 12345;
        Cell b = new Cell();
        b.height = 80.25;
        b.riverType = 1;
        b.riverSurfaceY = 81.5;
        b.riverLipY = 82.5;
        b.erosionMask = true;
        b.isLake = true;
        b.typeWeights = new double[]{0.2, 0.3, 0.5, 0.0, 0.0};
        b.gradient = 0.5f;
        b.rockSeqPacked = 12345;
        String base = cellSha(a);
        check(base.equals(cellSha(b)), "相同 Cell 必须同 SHA");
        b.height = Math.nextUp(b.height);
        check(!base.equals(cellSha(b)), "1 ulp 高度差必须改变 SHA");
        b.height = 80.25;
        b.isLake = false;
        check(!base.equals(cellSha(b)), "isLake 翻转必须改变 SHA");
        b.isLake = true;
        b.riverLipY = -0.0;
        a.riverLipY = 0.0;
        check(!cellSha(a).equals(cellSha(b)), "±0.0 必须按 raw bits 区分");
        a.riverLipY = 82.5;
        b.riverLipY = 82.5;
        a.riverSurfaceY = Double.longBitsToDouble(0x7ff8000000000001L);
        b.riverSurfaceY = Double.longBitsToDouble(0x7ff8000000000002L);
        check(!cellSha(a).equals(cellSha(b)), "NaN 载荷必须按 raw bits 区分");

        // 2) 列字段（original/carved/surface/lip/erosion/mask/river/lake）
        HydrologyBlockCarvedColumn c1 = new HydrologyBlockCarvedColumn(1, 2, 70.5, 69.25, 70.0,
                70.75, 1.25, 0.5, true, true, null, 70.0);
        HydrologyBlockCarvedColumn c2 = new HydrologyBlockCarvedColumn(1, 2, 70.5, 69.25, 70.0,
                70.75, 1.25, 0.5, true, true, null, 70.0);
        check(columnSha(c1).equals(columnSha(c2)), "相同列必须同 SHA");
        HydrologyBlockCarvedColumn c3 = new HydrologyBlockCarvedColumn(1, 2, 70.5, 69.25, 70.0,
                70.75000000000001, 1.25, 0.5, true, true, null, 70.0);
        check(!columnSha(c1).equals(columnSha(c3)), "lip 1 ulp 差必须改变列 SHA");

        // 3) 延迟统计
        long[] nanos = {1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L};
        Map<String, Object> lat = latencyMap(nanos);
        check(((Number) lat.get("n")).intValue() == 5, "n=5");
        check(Math.abs(((Number) lat.get("min")).doubleValue() - 1.0) < 1e-12, "min=1ms");
        check(Math.abs(((Number) lat.get("max")).doubleValue() - 5.0) < 1e-12, "max=5ms");
        check(Math.abs(((Number) lat.get("p50")).doubleValue() - 3.0) < 1e-12, "p50=3ms");

        // 4) 窗口解析与默认集
        List<Win> def = defaultWindows();
        check(def.size() == 5, "默认五局部窗口");
        check(def.get(4).seed() == 12345L, "第五窗口 seed=12345");
        List<Win> parsed = parseWindows("1,2,3; 12345@230,95,24");
        check(parsed.size() == 2 && parsed.get(0).radius() == 3
                && parsed.get(1).seed() == 12345L && parsed.get(1).cx() == 230, "窗口解析");
        checkThrows(() -> parseWindows("1,2"), "缺半径必须报错");

        // 5) 外圈不重叠 + 并发计划
        List<int[]> preheat = preheatChunks(0, 0, 2);
        List<int[]> ring = ringChunks(0, 0, 3, 8);
        Set<Long> pre = new LinkedHashSet<>();
        for (int[] c : preheat) pre.add(pack(c[0], c[1]));
        for (int[] c : ring) check(!pre.contains(pack(c[0], c[1])), "外圈不得与预热重叠");
        checkThrows(() -> ringChunks(0, 0, 3, 100), "超外圈容量必须报错");
        Spec same = new Spec(Mode.concurrent, ViewSpec.SEED, 0, 0, 6, 2, 4, "same-chunk",
                List.of(), DEFAULT_TOML, Path.of("build/x"), "-", null);
        List<List<int[]>> planSame = concurrentPlan(same);
        check(planSame.size() == 4 && planSame.get(3).size() == 2, "same-chunk 计划");
        Spec far = new Spec(Mode.concurrent, ViewSpec.SEED, 0, 0, 6, 2, 4, "far", List.of(),
                DEFAULT_TOML, Path.of("build/x"), "-", null);
        Set<Long> farChunks = new LinkedHashSet<>();
        for (List<int[]> l : concurrentPlan(far)) for (int[] c : l) farChunks.add(pack(c[0], c[1]));
        check(farChunks.size() == 8, "far 计划必须互不重复");

        // 6) CLI 解析
        Spec cold = parse(new String[]{"cold", "123", "4", "-5"});
        check(cold.mode() == Mode.cold && cold.seed() == 123 && cold.cx() == 4 && cold.cz() == -5, "cold CLI");
        Spec hot = parse(new String[]{"hot", "123", "0", "0", "3", "7", "--label=x"});
        check(hot.preheatRadius() == 3 && hot.requests() == 7 && hot.label().equals("x"), "hot CLI");
        Spec cc = parse(new String[]{"concurrent", "123", "8", "neighbor", "2"});
        check(cc.threads() == 8 && cc.selection().equals("neighbor"), "concurrent CLI");
        checkThrows(() -> parse(new String[]{"cold", "123", "0", "0", "--expect-sha=aa"}), "非 snapshot 禁 expect-sha");
        checkThrows(() -> parse(new String[]{"nope", "1"}), "未知 mode 必须报错");

        // 7) 结果摘要 + JSON 序列化（合成 Cell，不建地形）
        Cell[] fake = new Cell[256];
        java.util.Random rnd = new java.util.Random(20261028L);
        for (int i = 0; i < fake.length; i++) {
            Cell c = new Cell();
            c.height = 60 + rnd.nextDouble() * 40;
            c.riverType = (byte) (i % 17 == 0 ? 1 : 0);
            c.riverSurfaceY = c.height + (i % 17 == 0 ? 1.0 : 0.0);
            c.isLake = i % 97 == 0;
            fake[i] = c;
        }
        CellSummary summary = summarizeChunk(fake, true, 63);
        check(summary.columns() == 256 && summary.riverFlag() > 0, "合成 chunk 摘要计数");
        check(summary.sha().length() == 64, "cells SHA-256 长度");
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("result", summarize(List.<int[]>of(new int[]{0, 0}), List.<Cell[]>of(fake),
                new long[]{1_000_000L}, true, 63));
        sample.put("latency", latencyMap(new long[]{1L, 2L, 3L}));
        sample.put("waterKind", waterKindMap(new int[]{1, 2, 3, 4}));
        String json = GSON.toJson(sample);
        check(json.contains("cellsSha256") && json.contains("waterKind") && json.contains("p95"), "JSON 字段");
        check(!json.contains("NaN") && !json.contains("Infinity"), "JSON 不得含非有限字面量");

        // 8) 插桩反射：缺类必须明示 unavailable（旧 baseline）而不是抛；差值只对同键相减
        MetricsSnap snap = metricsSnap();
        check(snap.available() || snap.reason() != null, "metrics unavailable 必须带 reason");
        check(snap.values() != null, "metrics values 非 null");
        MetricsSnap absent = MetricsSnap.unavailable("class absent (self-test)");
        Map<String, Object> unavailableDelta = metricsDelta(absent, absent);
        check(Boolean.FALSE.equals(unavailableDelta.get("available"))
                && unavailableDelta.get("delta") == null
                && unavailableDelta.get("reason") != null, "unavailable 差值必须明示原因且 delta=null");
        MetricsSnap mBefore = new MetricsSnap(true, true,
                Map.of("cache.REGION.HIT", 5L, "sum.rounds", 1L), null, "deadbeef");
        MetricsSnap mAfter = new MetricsSnap(true, true,
                Map.of("cache.REGION.HIT", 9L, "sum.rounds", 1L, "stage.hydro.outlets.count", 2L), null, "deadbeef");
        Map<String, Object> delta = metricsDelta(mBefore, mAfter);
        @SuppressWarnings("unchecked")
        Map<String, Long> deltaValues = (Map<String, Long>) delta.get("delta");
        check(deltaValues.size() == 2
                && deltaValues.get("cache.REGION.HIT") == 4L
                && deltaValues.get("stage.hydro.outlets.count") == 2L
                && !deltaValues.containsKey("sum.rounds"), "instrumentation 差值只保留变化键");
        Map<String, Object> disabled = metricsDelta(new MetricsSnap(true, false, Map.of(), "off", "x"),
                new MetricsSnap(true, false, Map.of(), "off", "x"));
        check(Boolean.FALSE.equals(disabled.get("enabled")) && disabled.get("reason") != null,
                "instrumentation 关闭必须明示");

        checkTraceAndLayout();
        try { checkRawEvidence(); }
        catch (IOException e) { throw new AssertionError("raw evidence round-trip", e); }

        // 9) order（纯合成字节；不建地形、不读 TOML、不重求解任何 chunk）
        checkOrderCli();
        checkOrderPlan();
        checkOrderDigest();

        // 10) 旧 snapshot(schema2) hash 语义：编码原语/字段序/HPR2 头/组合 —— 逐字节固定，不改旧摘要
        try { checkSnapshotHashSemantics(); }
        catch (IOException e) { throw new AssertionError("snapshot hash semantics", e); }

        // 11) GC / 分配能力探测（只读，不断言平台必须支持）
        GcSnap gc = gcSnap();
        check(gc.count() >= 0 && gc.timeMs() >= 0, "GC 计数非负");
        System.out.println("INFO threadCpuSupported=" + ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported()
                + " threadAllocSupported=" + (allocBean() != null)
                + " gcBeans=" + gc.perCollector().size()
                + " hydrologyMetricsAvailable=" + snap.available()
                + " enabled=" + snap.enabled());
        System.out.println("PASS HydrologyPerfProbe self-test checks=" + selfTestChecks + " (no terrain, no TOML)");
    }

    static void checkTraceAndLayout() {
        BoundaryPort p = new BoundaryPort(0, 1, 2, 3, 4, 5, 6, 7, -0.0, 1.0, 8, 1, 0);
        BoundaryPort q = new BoundaryPort(0, 1, 2, 3, 4, 5, 6, 7, 0.0, 1.0, 8, 1, 0);
        var t = new DeterministicSkeleton.Trace(new int[]{1, 2}, DeterministicSkeleton.End.JOIN, false,
                new double[]{3, 4}, new double[]{5, 6}, 2, p);
        String sha = shaTraces(List.of(t));
        t.cell[1] = 3;
        check(!sha.equals(shaTraces(List.of(t))), "trace cell值而非仅长度入SHA"); t.cell[1] = 2;
        t.contPath[0] = Math.nextUp(5.0);
        check(!sha.equals(shaTraces(List.of(t))), "trace contPath rawbits入SHA"); t.contPath[0] = 5;
        t.outsideTail[1] = Math.nextUp(4.0);
        check(!sha.equals(shaTraces(List.of(t))), "trace outsideTail rawbits入SHA"); t.outsideTail[1] = 4;
        var u = new DeterministicSkeleton.Trace(t.cell, t.end, false, t.outsideTail, t.contPath, 2, q);
        check(!sha.equals(shaTraces(List.of(u))), "trace完整port入SHA");
        check(!shaPorts(List.of(p, q)).equals(shaPorts(List.of(q, p))), "pass1/final端口保留顺序");
        check(hotValid(0, 0, 12, 12, 12), "hot同区miss有效");
        check(!hotValid(0, 0, 12, 11, 12), "hot跨区必须无效");
        Spec hot = parse(new String[]{"hot", "123"});
        check(hot.cx() == 16 && hot.cz() == 16 && hot.preheatRadius() == 6, "hot默认中心16,16 r6");
        Spec far = parse(new String[]{"concurrent", "123", "8", "far", "32", "16", "16"});
        List<List<int[]>> plan = concurrentPlan(far);
        check(plan.get(1).get(0)[0] - plan.get(0).get(0)[0] == 128, "far间距128chunk");
        check(disjointPass1Supports(plan, 512), "far 3x3 pass1域不交");
        check(!disjointPass1Supports(List.of(List.of(new int[]{0, 0}), List.of(new int[]{64, 0})), 512),
                "旧far64chunk的pass1域会重叠");
        Cell[] a = new Cell[256], b = new Cell[256];
        for (int i = 0; i < 256; i++) { a[i] = new Cell(); b[i] = new Cell(); }
        check(Boolean.TRUE.equals(sameChunkReturns(List.of(a, b)).get("equal")), "same-chunk等值独立数组");
        b[10].gradient = -0.0f;
        check(Boolean.FALSE.equals(sameChunkReturns(List.of(a, b)).get("equal")), "same-chunk真实rawbit差异");
    }

    static byte[] fakeRaw(Cell cell, HydrologyBlockCarvedColumn column, int x, int z) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeRawHeader(out, new RawHeader(cell == null ? "columns" : "cells", 12345, -10, -10, 10, 10, 1));
        out.writeInt(x); out.writeInt(z);
        Hasher hash = new Hasher(out);
        if (cell != null) hashCell(hash, cell); else hashColumn(hash, column);
        hash.flush();
        return bytes.toByteArray();
    }

    static Map<String, Object> fakeDiff(byte[] a, byte[] b) throws IOException {
        return firstRawDiff(new DataInputStream(new ByteArrayInputStream(a)),
                new DataInputStream(new ByteArrayInputStream(b)));
    }

    static void checkRawEvidence() throws IOException {
        Cell cell = new Cell();
        cell.typeWeights = new double[]{0.25, 0.75};
        cell.oceanFeat = new com.geogenesis.worldgen.terrain.OceanFeatures.FeatureResult(1, 2, 3, 4, 5);
        byte[] original = fakeRaw(cell, null, -2, 3);
        check(Boolean.TRUE.equals(fakeDiff(original, original).get("equal")), "Cell raw证据往返含可选字段");
        cell.height = Math.nextUp(0.0);
        Map<String, Object> diff = fakeDiff(original, fakeRaw(cell, null, -2, 3));
        check("height".equals(diff.get("field")) && Integer.valueOf(-2).equals(diff.get("x"))
                && Integer.valueOf(3).equals(diff.get("z")), "Cell首差坐标+字段");
        cell.height = 0; cell.typeWeights[1] = Math.nextUp(0.75);
        check("typeWeights[1]".equals(fakeDiff(original, fakeRaw(cell, null, -2, 3)).get("field")),
                "Cell变长数组字段首差");
        cell.typeWeights = null;
        byte[] nullWeights = fakeRaw(cell, null, -2, 3);
        check(Boolean.TRUE.equals(fakeDiff(nullWeights, nullWeights).get("equal")), "null weights可读");
        cell.oceanFeat = null;
        check("oceanFeat.present".equals(fakeDiff(nullWeights, fakeRaw(cell, null, -2, 3)).get("field")),
                "可选对象缺失首差");
        Cell nullClimate = new Cell(); nullClimate.climate = null;
        check(!cellSha(nullClimate).equals(cellSha(new Cell())), "climate null与DEFAULT区分");
        var a = new HydrologyBlockCarvedColumn(-2, 3, 80, 79, 81, 82, 1, 0.5, true, false);
        var b = new HydrologyBlockCarvedColumn(-2, 3, 80, 79, 81, Math.nextUp(82.0), 1, 0.5, true, false);
        byte[] ca = fakeRaw(null, a, -2, 3);
        check(Boolean.TRUE.equals(fakeDiff(ca, ca).get("equal")), "column raw往返");
        check("lipSurfaceY".equals(fakeDiff(ca, fakeRaw(null, b, -2, 3)).get("field")), "column原始字段首差");
        check("coordinate/order".equals(fakeDiff(ca, fakeRaw(null, a, -1, 3)).get("field")), "列坐标/顺序首差");
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher hash = new Hasher(tap); hashCell(hash, cell);
        String sha = hash.hex();
        check(sha.equals(HexFormat.of().formatHex(newSha().digest(tap.toByteArray()))), "tap与哈希字节同源");
        checkThrows(() -> { try { fakeDiff(ca, Arrays.copyOf(ca, ca.length - 1)); }
                            catch (IOException e) { throw new UncheckedIOException(e); } }, "截断证据必须失败");
    }

    // ==================================================================
    // order 自检（合成字节；不建地形、不读 TOML、不重求解）
    // ==================================================================

    /** order CLI：4种请求次序、选项白名单、--expect-sha 形态、summary 打印 orderSha256。 */
    static void checkOrderCli() {
        Spec fwd = parse(new String[]{"order", "-7", "forward"});
        check(fwd.mode() == Mode.order && fwd.seed() == -7 && fwd.selection().equals("forward")
                && fwd.windows().isEmpty(), "order CLI forward/负seed");
        Spec rv = parse(new String[]{"order", "12", "reverse", "--label=rv", "--out=build/o"});
        check(rv.selection().equals("reverse") && rv.label().equals("rv"), "order CLI reverse+选项");
        String hex = "0123456789abcdef".repeat(4);
        Spec exp = parse(new String[]{"order", "12", "rebuild", "--expect-sha=" + hex});
        check(exp.expectSha().equals(hex), "order CLI --expect-sha 透传");
        checkThrows(() -> parse(new String[]{"order", "12"}), "order 缺请求次序必须报错");
        checkThrows(() -> parse(new String[]{"order", "12", "forward", "extra"}), "order 多余位置参数必须报错");
        checkThrows(() -> parse(new String[]{"order", "12", "sideways"}), "order 未知请求次序必须报错");
        checkThrows(() -> parse(new String[]{"order", "12", "forward", "--windows=1,2,3"}), "order 禁 snapshot 选项");
        checkThrows(() -> parse(new String[]{"order", "12", "forward", "--label=a", "--label=b"}), "order 禁重复选项");
        checkThrows(() -> parse(new String[]{"order", "12", "forward", "--label="}), "order 选项值不能空");
        checkThrows(() -> parse(new String[]{"order", "12", "forward", "--expect-sha=abc"}), "order expect-sha 必须64hex");
        checkThrows(() -> orderSeed("not-a-long"), "order seed 必须 long");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", "ok");
        root.put("orderSha256", hex);
        String text = summary(fwd, root);
        check(text.contains("order=forward") && text.contains("orderSha256=" + hex), "summary 必须打印 order 次序与摘要");
    }

    /** 固定集合与四种请求次序：坐标范围、负z、跨region、downstream-first 起点。 */
    static void checkOrderPlan() {
        List<OrderChunk> fixed = fixedOrderChunks();
        check(fixed.size() == 8 && new LinkedHashSet<>(fixed).size() == 8, "order 固定8个唯一chunk");
        boolean negativeZ = true, ascending = true;
        for (int i = 0; i < fixed.size(); i++) {
            OrderChunk c = fixed.get(i);
            negativeZ &= c.cz() < 0;
            if (i > 0) ascending &= fixed.get(i - 1).compareTo(c) < 0;
            check(c.cx() >= 16 && c.cx() <= 19 && c.cz() >= -33 && c.cz() <= -32, "order 固定集坐标范围: " + c);
        }
        check(negativeZ, "order 固定集必须全为负z");
        check(ascending, "order 固定集基准序按(cx,cz)升序");
        // 默认 REGION=512：z=-33 → rz=-2，z=-32 → rz=-1 ⇒ 必跨 region 边；实跑按运行时 REGION 判（checkOrderPreconditions）。
        Set<Long> regions = new LinkedHashSet<>();
        for (OrderChunk c : fixed) {
            regions.add(pack(Math.floorDiv(c.cx() * 16 + 8, 512), Math.floorDiv(c.cz() * 16 + 8, 512)));
        }
        check(regions.size() >= 2, "order 固定集必须跨region（默认512）");
        List<OrderChunk> forward = orderPlan("forward"), rebuild = orderPlan("rebuild");
        List<OrderChunk> reverse = orderPlan("reverse"), downstream = orderPlan("downstream-first");
        List<OrderChunk> expectReverse = new ArrayList<>(fixed);
        Collections.reverse(expectReverse);
        check(forward.equals(fixed) && rebuild.equals(fixed), "forward/rebuild 与固定集同序");
        check(reverse.equals(expectReverse) && reverse.get(0).equals(fixed.get(7)), "reverse 为固定集逆序");
        check(downstream.get(0).equals(new OrderChunk(17, -32)), "downstream-first 先(17,-32)");
        check(downstream.subList(0, 4).stream().allMatch(c -> c.cz() == -32), "downstream-first 先z=-32一侧");
        check(new LinkedHashSet<>(downstream).equals(new LinkedHashSet<>(fixed)), "downstream-first 仍是同一固定集");
        checkThrows(() -> orderPlan("sideways"), "orderPlan 未知次序必须报错");
    }

    /** 摘要反控：合成 raw 字节 ⇒ 字节1位改变必变、请求/list 次序不变；全程无地形。 */
    static void checkOrderDigest() {
        OrderChunk c0 = new OrderChunk(16, -33), c1 = new OrderChunk(17, -32);
        List<OrderBits> a = new ArrayList<>(List.of(orderBitsStub(c0, 0), orderBitsStub(c1, 1)));
        List<OrderBits> reversedList = new ArrayList<>(List.of(a.get(1), a.get(0)));
        long seed = 20261028L;
        check(orderSha(seed, a).equals(orderSha(seed, reversedList)), "摘要必须与请求/列表次序无关");
        check(!orderSha(seed, a).equals(orderSha(seed + 1, a)), "换seed必须改变摘要");
        OrderBits first = a.get(0);
        byte[] flipped = first.cells().clone();
        flipped[0] ^= 0x01;
        OrderBits mutated = new OrderBits(first.chunk(), flipped, first.columns(),
                digestHex(flipped), first.columnsSha());
        check(!first.cellsSha().equals(mutated.cellsSha()), "Cell字节1位改变必须改变cells摘要");
        check(!orderSha(seed, a).equals(orderSha(seed, List.of(mutated, a.get(1)))), "Cell字节1位改变必须改变order摘要");
        List<HydrologyBlockCarvedColumn> cols = orderColumnsStub(c0, 0);
        byte[] canonical = orderColumnBytes(c0, cols);
        List<HydrologyBlockCarvedColumn> shuffled = new ArrayList<>(cols);
        Collections.shuffle(shuffled, new java.util.Random(7L));
        check(Arrays.equals(canonical, orderColumnBytes(c0, shuffled)), "列摘要必须先按blockX/blockZ排序再取字节");
        List<HydrologyBlockCarvedColumn> tampered = new ArrayList<>(cols);
        HydrologyBlockCarvedColumn head = tampered.get(0);
        tampered.set(0, new HydrologyBlockCarvedColumn(head.blockX(), head.blockZ(), head.originalGroundY(),
                head.carvedGroundY(), head.waterSurfaceY(), Math.nextUp(head.lipSurfaceY()), head.erosion(),
                head.erosionMask(), head.fillWater(), head.lakePlan()));
        check(!Arrays.equals(canonical, orderColumnBytes(c0, tampered)), "单列lip 1ulp必须改变列字节");
        checkThrows(() -> orderColumnBytes(c0, cols.subList(0, 255)), "列数不足256必须拒绝");
        checkThrows(() -> orderColumnBytes(c1, cols), "列坐标不属本chunk必须拒绝");
        check(Boolean.TRUE.equals(compareOrderBits(a, a).get("rawBitsEqual")), "同字节重建必须判等价");
        check(Boolean.FALSE.equals(compareOrderBits(a, List.of(mutated, a.get(1))).get("rawBitsEqual")),
                "1位差重建必须判不等价");
        List<Map<String, Object>> rows = orderRows(a);
        check(rows.size() == 2 && Integer.valueOf(16).equals(rows.get(0).get("cx"))
                && Integer.valueOf(-33).equals(rows.get(0).get("cz")), "orderRows 按坐标升序逐chunk一行");
    }

    /** 合成 chunk 的 256 列（blockX asc,blockZ asc），坐标与生产 columnsFor 同构。 */
    static List<HydrologyBlockCarvedColumn> orderColumnsStub(OrderChunk chunk, int salt) {
        List<HydrologyBlockCarvedColumn> cols = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            double base = 70.0 + i * 0.25 + salt;
            cols.add(new HydrologyBlockCarvedColumn(chunk.cx() * 16 + i / 16, chunk.cz() * 16 + i % 16,
                    base, base - 1.0, base + 0.25, base + 0.5, 1.0, 0.5, i % 3 != 0, i % 97 == 0));
        }
        return cols;
    }

    /** 合成 chunk 的 raw 证据（256 Cell 全字段 + 256 列），不触发任何地形/水文求解。 */
    static OrderBits orderBitsStub(OrderChunk chunk, int salt) {
        Cell[] cells = new Cell[256];
        for (int i = 0; i < cells.length; i++) {
            Cell c = new Cell();
            c.height = 70.0 + i * 0.25 + salt;
            c.riverType = (byte) (i % 19 == 0 ? 1 : 0);
            cells[i] = c;
        }
        byte[] cellBytes = chunkRawBytes(cells);
        byte[] columnBytes = orderColumnBytes(chunk, orderColumnsStub(chunk, salt));
        return new OrderBits(chunk, cellBytes, columnBytes, digestHex(cellBytes), digestHex(columnBytes));
    }

    static String digestHex(byte[] bytes) {
        return HexFormat.of().formatHex(newSha().digest(bytes));
    }

    // ==================================================================
    // 旧 snapshot(schema2) hash 语义自检（无地形、无 TOML、无重求解；不改旧摘要定义）
    // ==================================================================

    /** 逐字节固定：Hasher 编码、字段序、HPR2 头、combine 组合、窗口头串格式。 */
    static void checkSnapshotHashSemantics() throws IOException {
        checkHasherSlots();
        checkSnapshotFieldOrder();
        checkCellRawLayout();
        checkCellValueMapping();
        checkColumnAndHeaderLayout();
        checkSnapshotComposition();
    }

    /** 逐字段唯一 sentinel：任一槽位互换/漏写都会让解码值不符（双精度/float/整数）。 */
    static void checkCellValueMapping() throws IOException {
        Cell cell = new Cell();
        cell.height = 1; cell.continent = 2; cell.e = 3; cell.eOcean = 4; cell.blendCont = 5;
        cell.eLand = 6; cell.eClimate = 7; cell.snowLineE = 8; cell.coastCoord = 9;
        cell.typeWeights = new double[]{10, 11};
        cell.temperature = 12; cell.humidity = 13; cell.precipitation = 14; cell.continentNoise = 15;
        cell.shape = 16; cell.riverNetDischarge = 17; cell.riverDistance = 18; cell.oasisNoise = 19;
        cell.gradient = 20; cell.rockTypeId = 21; cell.rockLayer = 22; cell.rockSeqPacked = 23;
        cell.rockTilt = 24; cell.riverType = 25; cell.riverSurfaceY = 26; cell.riverLipY = 27;
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher h = new Hasher(tap);
        hashCell(h, cell);
        h.flush();
        Map<String, Long> v = readRawValues(new DataInputStream(new ByteArrayInputStream(tap.toByteArray())), "cells");
        check(v.get("height") == bits(1) && v.get("continent") == bits(2) && v.get("e") == bits(3)
                && v.get("eOcean") == bits(4) && v.get("blendCont") == bits(5) && v.get("eLand") == bits(6)
                && v.get("eClimate") == bits(7) && v.get("snowLineE") == bits(8) && v.get("coastCoord") == bits(9)
                && v.get("typeWeights[0]") == bits(10) && v.get("typeWeights[1]") == bits(11)
                && v.get("temperature") == bits(12) && v.get("humidity") == bits(13)
                && v.get("precipitation") == bits(14) && v.get("continentNoise") == bits(15)
                && v.get("shape") == bits(16) && v.get("riverNetDischarge") == bits(17)
                && v.get("riverDistance") == bits(18) && v.get("oasisNoise") == bits(19)
                && v.get("gradient") == (Float.floatToRawIntBits(20f) & 0xFFFFFFFFL)
                && v.get("rockTypeId") == 21L && v.get("rockLayer") == 22L && v.get("rockSeqPacked") == 23L
                && v.get("rockTilt") == bits(24) && v.get("riverType") == 25L
                && v.get("riverSurfaceY") == bits(26) && v.get("riverLipY") == bits(27),
                "Cell 双精度/float/整数槽位映射未变");
    }

    static long bits(double v) {
        return Double.doubleToRawLongBits(v);
    }

    /** 每个 slot 的字节编码（long 大端 / double raw / float 无符号 rawInt / int 符号扩展 / bool 0|1 / str 长度前缀）。 */
    static void checkHasherSlots() {
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher h = new Hasher(tap);
        h.l(0x0102030405060708L);
        h.i(-1);
        h.b(true);
        h.b(false);
        h.d(1.0);
        h.f(1.0f);
        h.str("AB");
        h.flush();
        byte[] got = tap.toByteArray();
        String expect = "0102030405060708" + "ffffffffffffffff" + "0000000000000001" + "0000000000000000"
                + "3ff0000000000000" + "000000003f800000" + "0000000000000002" + "4142";
        check(got.length == 58, "slot 编码总长=6*8+长度前缀+UTF8字节");
        check(HexFormat.of().formatHex(got).equals(expect), "Hasher slot 逐字节编码未变（float 低32位零扩展）");
    }

    /** 字段名与次序：常量即旧 raw 契约，改动必然改变旧 snapshot 摘要 ⇒ 在此锁定字面值。 */
    static void checkSnapshotFieldOrder() {
        check(Arrays.asList(CELL_HEAD).equals(List.of("height", "continent", "e", "eOcean", "blendCont",
                "eLand", "eClimate", "snowLineE", "coastCoord")), "Cell head 9字段序未变");
        check(Arrays.asList(CELL_TAIL).equals(List.of("terrainType", "variantTerrain", "climate.present",
                "climate.temperature", "climate.humidity", "climate.continentality", "climate.precipitation",
                "biomeType", "temperature", "humidity", "precipitation", "continentNoise", "isLake", "lakeMask",
                "erosionMask", "shape", "riverNetDischarge", "riverDistance", "oasisNoise", "gradient",
                "rockTypeId", "rockLayer", "rockSeqPacked", "rockTilt", "riverType", "riverSurfaceY",
                "riverLipY", "isSnow")), "Cell tail 28字段序未变");
        check(Arrays.asList(COLUMN_FIELDS).equals(List.of("blockX", "blockZ", "originalGroundY",
                "carvedGroundY", "waterSurfaceY", "lipSurfaceY", "erosion", "erosionMask", "fillWater",
                "lakePlan", "lakeNodeIsNull", "lakeLevelY")), "列12字段序未变");
        check(Arrays.asList(OCEAN_FIELDS).equals(List.of("oceanFeat.total", "oceanFeat.ridge",
                "oceanFeat.seamount", "oceanFeat.arc", "oceanFeat.baseE")), "oceanFeat 5字段序未变");
        check(Arrays.asList(LAND_FIELDS).equals(List.of("landFeat.total", "landFeat.single", "landFeat.field",
                "landFeat.singleEdifice", "landFeat.fieldEdifice")), "landFeat 5字段序未变");
        check(RAW_MAGIC == 0x48505232, "HPR2 magic 未变");
    }

    /** 单 Cell 的 slot 数 = 9 + (1+len) + 28 + 6 + 6；解码后字段顺序与取值必须自洽。 */
    static void checkCellRawLayout() throws IOException {
        Cell cell = new Cell();
        cell.height = 80.25;
        cell.gradient = 0.5f;
        cell.typeWeights = new double[]{0.25, 0.75};
        cell.oceanFeat = new com.geogenesis.worldgen.terrain.OceanFeatures.FeatureResult(1, 2, 3, 4, 5);
        cell.landFeat = (com.geogenesis.worldgen.terrain.LandFeatures.FeatureResult) landFeatStub(6, 7, 8, 9, 10);
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher h = new Hasher(tap);
        hashCell(h, cell);
        h.flush();
        byte[] bytes = tap.toByteArray();
        check(bytes.length == 8 * (50 + 2), "Cell raw slot 数 = 50 + typeWeights长度（2）");
        Map<String, Long> v = readRawValues(new DataInputStream(new ByteArrayInputStream(bytes)), "cells");
        check(new ArrayList<>(v.keySet()).equals(expectedCellKeys(2)), "Cell raw 解码字段/次序未变");
        check(v.get("height") == Double.doubleToRawLongBits(80.25), "height 占首槽且为 double raw");
        check(v.get("gradient") == (Float.floatToRawIntBits(0.5f) & 0xFFFFFFFFL), "gradient 为无符号 float raw");
        check(v.get("typeWeights.length") == 2L
                && v.get("typeWeights[1]") == Double.doubleToRawLongBits(0.75), "typeWeights 变长块未变");
        check(v.get("oceanFeat.present") == 1L
                && v.get("oceanFeat.baseE") == Double.doubleToRawLongBits(5), "oceanFeat 块未变");
        check(v.get("landFeat.present") == 1L
                && v.get("landFeat.fieldEdifice") == Double.doubleToRawLongBits(10), "landFeat 块未变");
    }

    /** 列 12 slot + HPR2 头字节 + 头差异必须报 header（不是字段差异）。 */
    static void checkColumnAndHeaderLayout() throws IOException {
        HydrologyBlockCarvedColumn col = new HydrologyBlockCarvedColumn(1, 2, 70.5, 69.25, 70.0, 70.75,
                1.25, 0.5, true, true);
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher h = new Hasher(tap);
        hashColumn(h, col);
        h.flush();
        byte[] bytes = tap.toByteArray();
        check(bytes.length == 8 * 12, "列 raw slot 数=12");
        Map<String, Long> v = readRawValues(new DataInputStream(new ByteArrayInputStream(bytes)), "columns");
        check(new ArrayList<>(v.keySet()).equals(Arrays.asList(COLUMN_FIELDS)), "列 raw 解码字段/次序未变");
        check(v.get("blockX") == 1L && v.get("blockZ") == 2L, "列坐标为符号扩展 long");
        check(v.get("lakeNodeIsNull") == 1L, "lakeNode 恒 null 入摘要");
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        writeRawHeader(new DataOutputStream(head), new RawHeader("cells", 12345L, -10, -20, 30, 40, 256));
        byte[] hb = head.toByteArray();
        check(hb.length == 4 + 2 + 5 + 8 + 4 * 4 + 4, "HPR2 头长度未变");
        check(HexFormat.of().formatHex(Arrays.copyOf(hb, 4)).equals("48505232")
                && HexFormat.of().formatHex(Arrays.copyOfRange(hb, 4, 11)).equals("000563656c6c73"),
                "HPR2 magic + writeUTF(kind) 字节未变");
        check(readRawHeader(new DataInputStream(new ByteArrayInputStream(hb)))
                .equals(new RawHeader("cells", 12345L, -10, -20, 30, 40, 256)), "HPR2 头往返未变");
        ByteArrayOutputStream h1 = new ByteArrayOutputStream(), h2 = new ByteArrayOutputStream();
        writeRawHeader(new DataOutputStream(h1), new RawHeader("cells", 1L, 0, 0, 0, 0, 0));
        writeRawHeader(new DataOutputStream(h2), new RawHeader("cells", 2L, 0, 0, 0, 0, 0));
        check("header".equals(firstRawDiff(new DataInputStream(new ByteArrayInputStream(h1.toByteArray())),
                new DataInputStream(new ByteArrayInputStream(h2.toByteArray()))).get("field")), "仅头差异必须报 header");
    }

    /** snapshotSha256=combine(各 windowSha256 按 windowOrder)；窗口头串参与摘要 ⇒ 一并锁定。 */
    static void checkSnapshotComposition() {
        check(combine("a", "bc").equals(combine("a", "bc")), "同输入同摘要");
        check(!combine("a", "bc").equals(combine("ab", "c")), "长度前缀消除拼接歧义");
        check(!combine("x", "y").equals(combine("y", "x")), "摘要对分量次序敏感");
        check(!combine("a").equals(combine("a", "b")), "分量个数入摘要");
        String sha = combine("aa", "bb");
        check(sha.length() == 64 && sha.matches("[0-9a-f]{64}"), "combine 输出 64位小写hex");
        check(new Win(12345L, -10, 20, 32).toString().equals("12345@-10,20,32"), "窗口头串格式未变");
        ByteArrayOutputStream tap = new ByteArrayOutputStream();
        Hasher h = new Hasher(tap);
        h.str("a");
        h.str("bc");
        h.flush();
        check(HexFormat.of().formatHex(tap.toByteArray())
                .equals("0000000000000001" + "61" + "0000000000000002" + "6263"), "combine 的 UTF-8 长度前缀编码未变");
    }

    /** readRawValues 的预期键序（与 CELL_HEAD/typeWeights/CELL_TAIL/可选块一致）。 */
    static List<String> expectedCellKeys(int weights) {
        List<String> keys = new ArrayList<>(Arrays.asList(CELL_HEAD));
        keys.add("typeWeights.length");
        for (int i = 0; i < weights; i++) keys.add("typeWeights[" + i + "]");
        keys.addAll(Arrays.asList(CELL_TAIL));
        keys.add("oceanFeat.present");
        keys.addAll(Arrays.asList(OCEAN_FIELDS));
        keys.add("landFeat.present");
        keys.addAll(Arrays.asList(LAND_FIELDS));
        return keys;
    }

    /** LandFeatures.FeatureResult 构造器为包私有 ⇒ 仅自检反射构造，不新增生产入口。 */
    static Object landFeatStub(double total, double single, double field, double singleEdifice, double fieldEdifice) {
        try {
            Class<?> type = Class.forName("com.geogenesis.worldgen.terrain.LandFeatures$FeatureResult");
            var ctor = type.getDeclaredConstructor(double.class, double.class, double.class, double.class, double.class);
            ctor.setAccessible(true);
            return ctor.newInstance(total, single, field, singleEdifice, fieldEdifice);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("LandFeatures.FeatureResult 5参构造器不可用", e);
        }
    }

    /** 自检断言计数（由 {@link #check} / {@link #checkThrows} 递增，避免手写数字与实际不符）。 */
    static int selfTestChecks;

    static String cellSha(Cell c) {
        Hasher h = new Hasher();
        hashCell(h, c);
        return h.hex();
    }

    static String columnSha(HydrologyBlockCarvedColumn c) {
        Hasher h = new Hasher();
        hashColumn(h, c);
        return h.hex();
    }

    static void check(boolean ok, String message) {
        selfTestChecks++;
        if (!ok) throw new AssertionError(message);
    }

    static void checkThrows(Runnable r, String message) {
        selfTestChecks++;
        try {
            r.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
