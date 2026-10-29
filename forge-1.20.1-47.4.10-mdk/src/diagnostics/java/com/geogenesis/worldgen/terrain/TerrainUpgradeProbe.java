package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基础地形升级观测探针（只读，零生产行为改动）。
 *
 * <h3>为什么存在</h3>
 * <p>{@code .openbitfun/plans/base-terrain-upgrade-research.plan.md} 的 P0/P1 要求先冻结观测口径：
 * 基础高度、quick 高度、完整 Cell、类型权重、侵蚀后高度、最终 chunk 必须<b>分别</b>可测且逐位可比。
 * 既有探针要么只画图（{@code TerrainStripePngProbe}），要么只测水文（{@code HydrologyPerfProbe}），
 * 没有一个能在<b>baseline 与 candidate 两套编译产物上跑同一份口径</b>。本探针即该口径。
 *
 * <h3>三种模式</h3>
 * <ul>
 *   <li><b>snapshot</b>：多 seed（含负/远/小数坐标）逐位比较
 *       {@link CellGenerator#terrainE}、{@link CellGenerator#terrainEQuick}、{@link CellGenerator#sample()}.e，
 *       输出基础 e 哈希 / 类型权重哈希 / 高度哈希供 A/B 直接对比；并做冷/暖/乱序/并发确定性检查。</li>
 *   <li><b>bench</b>：quick / heightOnly / typeField / fullCell 各自冷热两相，
 *       记录墙钟、本线程 CPU、线程分配（支持时）与 P50/P95/max/median。</li>
 *   <li><b>render</b>：同产线基础高度（侵蚀前）诊断图，见 {@link TerrainUpgradeRender}。</li>
 * </ul>
 *
 * <h3>口径铁律（写死在代码里，别靠记忆）</h3>
 * <ol>
 *   <li>坐标语义 = <b>wu</b>（{@code CellGenerator} 的公开 API 全是 wu）；本探针不做块→wu 换算。</li>
 *   <li>不另造高度算式：e 一律取 {@code terrainE/terrainEQuick/sample().e}，Y 一律走
 *       {@code generator.heightCurve().heightFromE(e)}。</li>
 *   <li>配置只从<b>输出目录副本</b>加载（{@code --toml=} → baseline 冻结件 → run/config），
 *       绝不回写用户配置文件。</li>
 *   <li>quick/full 不等时<b>如实报坐标与位模式</b>。{@code terrainEQuick} 是 131072 槽直接映射缓存，
 *       哈希槽碰撞会返回<b>别的坐标</b>的值（源码头注释记过这个风险）。本探针用
 *       「重新 seed 后单点重查」区分 <i>cache-collision</i>（既有缓存属性）与
 *       <i>compute-divergence</i>（真缺陷），不靠调阈值掩盖。</li>
 * </ol>
 *
 * <h3>退出码</h3>
 * <p>{@code 0}=完成（无非碰撞差异）；{@code 1}=观测到差异/断言失败；
 * {@code 2}=前提不成立（缺 TOML、参数非法）；{@code 3}=FATAL。</p>
 *
 * <p>用法见 {@link #USAGE}（{@code --help}）。</p>
 */
public final class TerrainUpgradeProbe {

    static final int PROBE_VERSION = 1;
    /** 与 {@code GeoGenesisGenerator.WORLD_MIN_Y / WORLD_MAX_Y} 同值（生产构造参数）。 */
    static final int WORLD_MIN_Y = -64;
    static final int WORLD_MAX_Y = 320;
    /** 用户已认可的 schema119 基线 seed（见 handoff/attachments/baseline-schema119-metadata-20261029.txt）。 */
    static final long CANONICAL_SEED = 9139912035078620160L;
    /** 基线臂冻结配置（主代理在 root 上产出；存在即优先于 run/config）。 */
    static final Path BASELINE_TOML = Path.of("build", "terrain-upgrade", "baseline", "config-input.toml");
    static final Path RUN_TOML = Path.of("run", "config", "geogenesis-common.toml");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    /** 每条差异最多落多少条明细（计数仍是全量）。 */
    static final int MAX_DETAIL = 8;

    /** 防 JIT 消除被测调用（值本身无意义）。 */
    private static volatile double sink;

    static final String USAGE = """
            TerrainUpgradeProbe v1 -- base-terrain observation probe (read-only, no production render)

            mode snapshot [--seeds=9139912035078620160,12345,-1] [--samples=2000]
                          [--chunks=0] [--chunk-x=0] [--chunk-z=0] [--threads=4]
            mode bench    [--seed=9139912035078620160] [--samples=1000] [--warmup=200]
                          [--erosion-samples=0] [--chunks=0] [--chunk-x=0] [--chunk-z=0]
            mode render   [--seed=...] [--cx=-140] [--cz=137] [--size=1401] [--step=1.0]
                          [--types=off] [--raw=both|float|double] [--hmin=-20] [--hmax=240]

            common        [--toml=<path>] [--out=<dir>] [--label=<text>] [--help] [--self-test]

            TOML resolution (first existing): --toml= > build/terrain-upgrade/baseline/config-input.toml
                          > run/config/geogenesis-common.toml. Forge correction is applied ONLY to the
                          copy inside the output dir; the user config file is never rewritten.
                          Missing TOML => exit 2.

            snapshot : bitwise compare terrainE vs terrainEQuick vs sample().e on multi-seed points
                       (negative / far / fractional / 400-wu grid lines), plus base-e hash, type-weight
                       hash and height hash; determinism passes = cold, warm, shuffled, cross-instance,
                       concurrent (barrier-started). Optional chunk stage via getChunkCells.
                       COST: --chunks>0 runs one production hydrology region solve per seed (~35 s on
                       the dev box). Use --chunks=0 for the fast determinism-only path.
            bench    : quick / heightOnly / typeField / fullCell, each with a cold (cache-hostile) and a
                       warm (repeat) phase; wall time, current-thread CPU, thread allocation (if the JVM
                       supports it), P50/P95/max/median. Erosion and chunk stages are opt-in (0 = skip).
            render   : pre-erosion base height diagnostic images + raw float/double + stats.
                       cx/cz/step are BLOCKS (canonical window: -140/137/size 1401/step 1 block),
                       converted once to wu with the frozen horizontalScale; the raw grid and the
                       hillshade therefore cover exactly the accepted production window.
                       --step accepts fractions (0.5 blocks = 1 wu at hs=2). Macro view example:
                       --size=1025 --step=32 --cx=0 --cz=0.
                       NOT final block placement (no erosion / hydrology / caves / decoration).

            exit codes 0 = done (no non-collision difference)  1 = difference / assertion failed
                       2 = precondition failed (TOML/args)     3 = FATAL
            """;

    private TerrainUpgradeProbe() { }

    // ==================================================================
    // 入口
    // ==================================================================

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int code;
        try {
            code = run(args);
        } catch (Exit e) {
            System.out.println(e.getMessage());
            code = e.code;
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace(System.out);
            code = 3;
        }
        System.out.println("EXIT=" + code);
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.print(USAGE);
            return 0;
        }
        if ("--self-test".equals(args[0])) return selfTest();
        if (args[0].startsWith("--")) throw new Exit(2, "missing mode (snapshot|bench|render)");
        Opts opts = Opts.parse(args);
        Path out = opts.path("out", Path.of("build", "terrain-upgrade", opts.mode));
        Files.createDirectories(out);
        return switch (opts.mode) {
            case "snapshot" -> snapshot(opts, out);
            case "bench" -> bench(opts, out);
            case "render" -> TerrainUpgradeRender.run(opts, out);
            default -> throw new Exit(2, "unknown mode: " + opts.mode + " (snapshot|bench|render)");
        };
    }

    // ==================================================================
    // 参数
    // ==================================================================

    /** 非零退出用的受检“抛出”（main 统一转成 exit code）。 */
    static final class Exit extends RuntimeException {
        final int code;
        Exit(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 极简 {@code --k=v} 参数表：未知键直接失败（防止拼错参数静默用默认值）。 */
    static final class Opts {
        private static final Set<String> KNOWN = Set.of(
                "toml", "out", "label", "seeds", "seed", "samples", "chunks", "threads", "warmup",
                "erosion-samples", "cx", "cz", "size", "step", "types", "raw", "hmin", "hmax",
                "chunk-x", "chunk-z");

        final String mode;
        private final Map<String, String> kv = new LinkedHashMap<>();

        private Opts(String mode) { this.mode = mode; }

        static Opts parse(String[] args) {
            Opts o = new Opts(args[0]);
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (!a.startsWith("--")) throw new Exit(2, "unexpected positional arg: " + a
                        + " (use --key=value)");
                int eq = a.indexOf('=');
                String k = eq < 0 ? a.substring(2) : a.substring(2, eq);
                String v = eq < 0 ? "true" : a.substring(eq + 1);
                if (!KNOWN.contains(k)) throw new Exit(2, "unknown option --" + k);
                if (o.kv.put(k, v) != null) throw new Exit(2, "duplicate option --" + k);
            }
            return o;
        }

        String str(String key, String fallback) { return kv.getOrDefault(key, fallback); }

        Path path(String key, Path fallback) {
            String v = kv.get(key);
            return v == null ? fallback : Path.of(v);
        }

        boolean flag(String key, boolean fallback) {
            String v = kv.get(key);
            if (v == null) return fallback;
            return switch (v.toLowerCase(Locale.ROOT)) {
                case "1", "true", "on", "yes" -> true;
                case "0", "false", "off", "no" -> false;
                default -> throw new Exit(2, "--" + key + " expects on|off, got: " + v);
            };
        }

        int i32(String key, int fallback, int min, int max) {
            String v = kv.get(key);
            if (v == null) return fallback;
            int n;
            try {
                n = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new Exit(2, "--" + key + " expects an integer, got: " + v);
            }
            if (n < min || n > max) throw new Exit(2, "--" + key + " out of range [" + min + "," + max + "]: " + n);
            return n;
        }

        long i64(String key, long fallback) {
            String v = kv.get(key);
            if (v == null) return fallback;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                throw new Exit(2, "--" + key + " expects a long, got: " + v);
            }
        }

        double f64(String key, double fallback) {
            String v = kv.get(key);
            if (v == null) return fallback;
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                throw new Exit(2, "--" + key + " expects a number, got: " + v);
            }
        }

        List<Long> seeds(String key, List<Long> fallback) {
            String v = kv.get(key);
            if (v == null) return fallback;
            List<Long> out = new ArrayList<>();
            for (String part : v.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try {
                    out.add(Long.parseLong(t));
                } catch (NumberFormatException e) {
                    throw new Exit(2, "--seeds expects comma-separated longs, got: " + t);
                }
            }
            if (out.isEmpty()) throw new Exit(2, "--seeds is empty");
            return out;
        }
    }

    // ==================================================================
    // 配置（只读：只在输出目录副本上做 Forge 校正）
    // ==================================================================

    record ConfigInput(TerrainParams params, Path toml, String sha256, boolean corrected) { }

    static Path resolveToml(Opts opts) {
        String given = opts.str("toml", null);
        if (given != null) {
            Path p = Path.of(given);
            if (!Files.isRegularFile(p)) throw new Exit(2, "TOML not found: " + p.toAbsolutePath());
            return p;
        }
        if (Files.isRegularFile(BASELINE_TOML)) return BASELINE_TOML;
        if (Files.isRegularFile(RUN_TOML)) return RUN_TOML;
        throw new Exit(2, "no TOML: pass --toml=<path>, or provide " + BASELINE_TOML + " / " + RUN_TOML);
    }

    /**
     * 加载显式 TOML：先复制到输出目录，再只对副本执行 Forge 校正。
     *
     * <p>为什么必须复制：{@code GeoGenesisConfig.SPEC.setConfig(cfg)} + {@code cfg.save()} 会把
     * 默认键补写回原文件 —— 直接指向 {@code run/config} 就会篡改用户配置。</p>
     */
    static ConfigInput loadConfig(Path out, Opts opts) throws IOException {
        Path toml = resolveToml(opts);
        String sha = sha256(Files.readAllBytes(toml));
        Path input = out.resolve("config-input.toml");
        Path effective = out.resolve("config-effective.toml");
        Files.copy(toml, input, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(toml, effective, StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(effective, TomlFormat.instance());
        cfg.load();
        boolean correct = GeoGenesisConfig.SPEC.isCorrect(cfg);
        GeoGenesisConfig.SPEC.setConfig(cfg);
        if (!GeoGenesisConfig.SPEC.isLoaded()) throw new Exit(2, "TOML not loaded: " + effective);
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        cfg.save();
        Files.writeString(out.resolve("terrain-params.txt"), params.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("provenance.txt"),
                provenance(toml, sha, !correct, params), StandardCharsets.UTF_8);
        System.out.println("config toml=" + toml.toAbsolutePath() + " sha256=" + sha
                + " correctedByForge=" + !correct);
        return new ConfigInput(params, toml, sha, !correct);
    }

    private static String provenance(Path toml, String sha, boolean corrected, TerrainParams params) {
        StringBuilder sb = new StringBuilder();
        sb.append("probe=TerrainUpgradeProbe version=").append(PROBE_VERSION)
                .append(" readOnly=true\n");
        sb.append("toml=").append(toml.toAbsolutePath()).append('\n');
        sb.append("sha256=").append(sha).append("\ncorrectedByForge=").append(corrected).append('\n');
        sb.append("configFingerprint=").append(GeoGenesisConfig.configFingerprint()).append('\n');
        sb.append("horizontalScale=").append(params.horizontalScale())
                .append(" seaLevel=").append(params.seaLevel())
                .append(" minY=").append(params.minY()).append(" maxY=").append(params.maxY()).append('\n');
        sb.append("worldBounds=").append(WORLD_MIN_Y).append("..").append(WORLD_MAX_Y)
                .append(" (mirrors GeoGenesisGenerator)\n");
        sb.append("java=").append(System.getProperty("java.vendor")).append(' ')
                .append(System.getProperty("java.version")).append(" os=")
                .append(System.getProperty("os.name")).append(" arch=")
                .append(System.getProperty("os.arch")).append('\n');
        for (String k : System.getProperties().stringPropertyNames().stream().sorted().toList()) {
            if (k.startsWith("geogenesis.") || k.startsWith("hydro.")) {
                sb.append("jvm.").append(k).append('=').append(System.getProperty(k)).append('\n');
            }
        }
        for (Class<?> type : new Class<?>[]{TerrainUpgradeProbe.class, TerrainUpgradeRender.class,
                CellGenerator.class, GeoGenesisTerrain.class, Cell.class}) {
            sb.append("class.").append(type.getSimpleName()).append(".sha256=").append(classSha(type)).append('\n');
        }
        return sb.toString();
    }

    static String classSha(Class<?> type) {
        try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (in == null) return "unavailable";
            return hex(sha().digest(in.readAllBytes()));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    // ==================================================================
    // snapshot
    // ==================================================================

    static int snapshot(Opts opts, Path out) throws Exception {
        List<Long> seeds = opts.seeds("seeds", List.of(CANONICAL_SEED, 12345L, -1L));
        int samples = opts.i32("samples", 2000, 8, 200_000);
        int chunks = opts.i32("chunks", 0, 0, 64);
        int chunkX = opts.i32("chunk-x", 0, -1_000_000, 1_000_000);
        int chunkZ = opts.i32("chunk-z", 0, -1_000_000, 1_000_000);
        int threads = opts.i32("threads", 4, 1, 16);
        ConfigInput cfg = loadConfig(out, opts);

        CellGenerator genA = new CellGenerator(cfg.params(), WORLD_MIN_Y, WORLD_MAX_Y);
        CellGenerator genB = new CellGenerator(cfg.params(), WORLD_MIN_Y, WORLD_MAX_Y);
        GeoGenesisTerrain terrain = chunks > 0 ? new GeoGenesisTerrain(genA) : null;

        List<Map<String, Object>> reports = new ArrayList<>();
        int nonCollisionDiff = 0;
        MessageDigest combE = sha();
        MessageDigest combQ = sha();
        MessageDigest combS = sha();
        MessageDigest combW = sha();
        MessageDigest combH = sha();
        try (BufferedWriter tsv = Files.newBufferedWriter(out.resolve("snapshot-samples.tsv"), StandardCharsets.UTF_8);
             BufferedWriter bad = Files.newBufferedWriter(out.resolve("snapshot-mismatches.tsv"), StandardCharsets.UTF_8)) {
            tsv.write("seed\tidx\tkind\tx\tz\tterrainE\tterrainE_hex\tterrainEQuick\tterrainEQuick_hex"
                    + "\tsampleE\tsampleE_hex\theight\tquick_eq_terrain\tsample_eq_terrain\n");
            bad.write("seed\tidx\tkind\tx\tz\tfield\tbase_hex\tother_hex\tulp\tabscode\tverdict\n");
            for (long seed : seeds) {
                Map<String, Object> report = snapshotSeed(genA, genB, terrain, cfg, seed, samples, threads,
                        chunks, chunkX, chunkZ, tsv, bad);
                reports.add(report);
                nonCollisionDiff += ((Number) report.get("nonCollisionDifferences")).intValue();
                Map<String, Object> hashes = asMap(report.get("hashes"));
                appendHash(combE, hashes.get("terrainE"));
                appendHash(combQ, hashes.get("terrainEQuick"));
                appendHash(combS, hashes.get("sampleE"));
                appendHash(combW, hashes.get("typeWeights"));
                appendHash(combH, hashes.get("sampleHeight"));
                System.out.println("snapshot seed=" + seed + " " + report.get("verdict")
                        + " nonCollisionDifferences=" + report.get("nonCollisionDifferences")
                        + " terrainVsQuick=" + asMap(report.get("terrainVsQuick")).get("mismatches")
                        + " terrainVsSample=" + asMap(report.get("terrainVsSample")).get("mismatches"));
                System.out.println("HASH seed=" + seed
                        + " terrainE=" + hashes.get("terrainE")
                        + " terrainEQuick=" + hashes.get("terrainEQuick")
                        + " sampleE=" + hashes.get("sampleE")
                        + " sampleHeight=" + hashes.get("sampleHeight")
                        + " typeWeights=" + hashes.get("typeWeights"));
            }
        }

        Map<String, Object> root = baseReport("snapshot", cfg, out);
        root.put("snapshot", map(
                "samplesPerSeed", samples,
                "seeds", seeds,
                "chunksPerSeed", chunks,
                "chunkOrigin", map("cx", chunkX, "cz", chunkZ),
                "threads", threads,
                "engineInstances", "exactly 2 CellGenerator instances, reused across seeds "
                        + "(seed() clears the eq/erosion/rock caches); GeoGenesisTerrain is created once "
                        + "and only when --chunks>0",
                "passes", List.of("cold", "warm", "shuffled", "cross-instance-cold", "concurrent-cold"),
                "pointKinds", List.of("near-origin-fractional", "negative-far", "far-positive",
                        "fractional-mixed", "grid400", "axis-edge"),
                "hashEncoding", "SHA-256 over IEEE-754 double bits (big-endian, index order)",
                "combinedHashes", map(
                        "terrainE", hex(combE.digest()),
                        "terrainEQuick", hex(combQ.digest()),
                        "sampleE", hex(combS.digest()),
                        "typeWeights", hex(combW.digest()),
                        "sampleHeight", hex(combH.digest()),
                        "rule", "SHA-256 over the per-seed hash strings in seed order "
                                + "(compare this line between arms first)"),
                "turboNote", "terrainEQuick is a 131072-slot direct-mapped cache; a hash-slot collision "
                        + "returns another coordinate's value. Mismatches are reported with coordinates and "
                        + "classified as cache-collision vs compute-divergence by a re-seeded single-point recall.",
                "reports", reports,
                "nonCollisionDifferences", nonCollisionDiff));
        root.put("status", nonCollisionDiff == 0 ? "ok" : "differences");
        writeJson(out.resolve("snapshot.json"), root);
        System.out.println("COMBINED " + asMap(root.get("snapshot")).get("combinedHashes"));
        System.out.println("wrote " + out.toAbsolutePath());
        System.out.println("STATUS=" + (nonCollisionDiff == 0 ? "ok" : "differences")
                + " nonCollisionDifferences=" + nonCollisionDiff);
        return nonCollisionDiff == 0 ? 0 : 1;
    }

    /** 单 seed：5 个确定性 pass + 位数比较 + 哈希 + 可选 chunk 阶段。 */
    static Map<String, Object> snapshotSeed(CellGenerator genA, CellGenerator genB, GeoGenesisTerrain terrain,
                                            ConfigInput cfg, long seed, int samples, int threads,
                                            int chunks, int chunkX, int chunkZ,
                                            BufferedWriter tsv, BufferedWriter bad) throws Exception {
        genA.seed(seed);
        genB.seed(seed);
        List<Point> pts = makePoints(seed, samples);
        int[] forward = new int[pts.size()];
        for (int i = 0; i < forward.length; i++) forward[i] = i;

        Capture base = capture(genA, pts, forward);
        Capture warm = capture(genA, pts, forward);
        Capture shuffled = capture(genA, pts, shuffledOrder(pts.size(), seed));
        genB.seed(seed);
        Capture crossInstance = capture(genB, pts, forward);
        genB.seed(seed);
        Capture concurrent = concurrentCapture(genB, pts, threads, seed);

        writeSampleRows(tsv, seed, pts, base);

        List<Map<String, Object>> passes = new ArrayList<>();
        passes.add(comparePass("warm", pts, base, warm, bad, seed));
        passes.add(comparePass("shuffled", pts, base, shuffled, bad, seed));
        passes.add(comparePass("cross-instance-cold", pts, base, crossInstance, bad, seed));
        passes.add(comparePass("concurrent-cold", pts, base, concurrent, bad, seed));

        List<Map<String, Object>> detail = new ArrayList<>();
        int nonCollision = 0;
        for (int i = 0; i < pts.size(); i++) {
            boolean eqSample = bits(base.e[i]) == bits(base.sampleE[i]);
            boolean eqQuick = bits(base.e[i]) == bits(base.quick[i]);
            if (!eqSample) {
                nonCollision++;
                if (detail.size() < MAX_DETAIL) detail.add(mismatch(seed, i, pts.get(i), "terrainE", "sampleE",
                        base.e[i], base.sampleE[i], "sample-divergence"));
            }
            if (!eqQuick) {
                Map<String, Object> d = diagnoseQuick(genB, seed, pts.get(i), base.e[i], base.quick[i], i);
                detail.add(d);
                if (!"cache-collision".equals(d.get("verdict"))) nonCollision++;
            }
        }
        for (Map<String, Object> d : detail) writeMismatchRow(bad, seed, d);
        for (Map<String, Object> pass : passes) {
            nonCollision += ((Number) pass.get("differences")).intValue();
            if (((Number) pass.get("differences")).intValue() > 0) {
                for (Map<String, Object> d : listOfMaps(pass.get("detail"))) writeMismatchRow(bad, seed, d);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("seed", seed);
        report.put("samples", pts.size());
        report.put("pointsByKind", countKinds(pts));
        report.put("hashes", map(
                "terrainE", hashDoubles(base.e),
                "terrainEQuick", hashDoubles(base.quick),
                "sampleE", hashDoubles(base.sampleE),
                "sampleHeight", hashDoubles(base.height),
                "typeWeights", hashWeights(base.weights)));
        report.put("eqCache", map("hitsDelta", base.hits, "missesDelta", base.misses));
        report.put("terrainVsQuick", map(
                "mismatches", countBits(base.e, base.quick),
                "maxUlp", maxUlp(base.e, base.quick),
                "maxAbsDiff", maxAbsDiff(base.e, base.quick)));
        report.put("terrainVsSample", map(
                "mismatches", countBits(base.e, base.sampleE),
                "maxUlp", maxUlp(base.e, base.sampleE),
                "maxAbsDiff", maxAbsDiff(base.e, base.sampleE)));
        report.put("passes", passes);
        report.put("quickVsTerrainDetail", detail);
        report.put("nonCollisionDifferences", nonCollision);
        report.put("verdict", nonCollision == 0 ? "ok" : "DIFF");
        if (chunks > 0) {
            terrain.seed(seed);
            report.put("chunks", chunkReport(terrain, chunkX, chunkZ, chunks));
        } else {
            report.put("chunks", map("skipped", true, "reason", "--chunks=0"));
        }
        return report;
    }

    /**
     * 单点 quick≠full 的归因：重新 seed 后只查这一个坐标。
     *
     * <p>无碰撞时 direct-mapped 缓存必然给出与 full 相同的位；若此时仍不同，
     * 说明两条计算链真的分叉（必须报告，不能当成"缓存特性"放掉）。</p>
     */
    static Map<String, Object> diagnoseQuick(CellGenerator genB, long seed, Point p,
                                             double fullE, double quickE, int idx) {
        genB.seed(seed);
        double recall = genB.terrainEQuick(p.x(), p.z());
        String verdict = bits(recall) == bits(fullE) ? "cache-collision" : "compute-divergence";
        Map<String, Object> d = mismatch(seed, idx, p, "terrainE", "terrainEQuick", fullE, quickE, verdict);
        d.put("quickAfterReseed", recall);
        d.put("quickAfterReseedHex", Long.toHexString(bits(recall)));
        return d;
    }

    // ----- 采样点 -----

    record Point(double x, double z, String kind) { }

    /**
     * 确定性点位：多 seed 下都覆盖近原点小数、负远坐标、正远坐标、小数混合、
     * 400wu 类型网格线（类型场 Voronoi 的轴对齐直线所在处）、轴/零边界。
     */
    static List<Point> makePoints(long seed, int count) {
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        Set<Long> seen = new HashSet<>();
        List<Point> pts = new ArrayList<>(count);
        int guard = 0;
        while (pts.size() < count && guard++ < count * 8) {
            int bucket = pts.size() % 6;
            double x;
            double z;
            String kind;
            switch (bucket) {
                case 0 -> {
                    x = -512.0 + rnd.nextDouble() * 1024.0;
                    z = -512.0 + rnd.nextDouble() * 1024.0;
                    kind = "near-origin-fractional";
                }
                case 1 -> {
                    x = -1_000_000.0 + rnd.nextDouble() * 200_000.0;
                    z = -1_000_000.0 + rnd.nextDouble() * 200_000.0;
                    kind = "negative-far";
                }
                case 2 -> {
                    x = 1_000_000.0 + rnd.nextDouble() * 3_000_000.0;
                    z = 1_000_000.0 + rnd.nextDouble() * 3_000_000.0;
                    kind = "far-positive";
                }
                case 3 -> {
                    x = Math.rint((rnd.nextDouble() * 4.0 - 2.0) * 1000.0) + 0.1234567890123;
                    z = Math.rint((rnd.nextDouble() * 4.0 - 2.0) * 1000.0) - 0.5;
                    kind = "fractional-mixed";
                }
                case 4 -> {
                    x = 400.0 * (rnd.nextInt(21) - 10);
                    z = 400.0 * (rnd.nextInt(21) - 10) + 0.5;
                    kind = "grid400";
                }
                default -> {
                    // 轴/零边界：-0.0 与 0.0 位模式不同（哈希键不同、数值相同），是缓存键的边界样本。
                    double[] edges = {-0.0, 0.0, 0.5, -0.5, 1e-9};
                    x = edges[rnd.nextInt(edges.length)];
                    z = Math.rint((rnd.nextDouble() * 2.0 - 1.0) * 1000.0) + (rnd.nextBoolean() ? 0.5 : -0.5);
                    kind = "axis-edge";
                }
            }
            long key = bits(x) ^ Long.rotateLeft(bits(z), 17);
            if (!seen.add(key)) continue;
            pts.add(new Point(x, z, kind));
        }
        if (pts.size() != count) throw new Exit(3, "point generation produced " + pts.size() + "/" + count);
        return pts;
    }

    static int[] shuffledOrder(int n, long seed) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Random rnd = new Random(seed ^ 0x2545F4914F6CDD1DL);
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order;
    }

    static Map<String, Object> countKinds(List<Point> pts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Point p : pts) counts.merge(p.kind(), 1, Integer::sum);
        return new LinkedHashMap<>(counts);
    }

    // ----- 采样与比较 -----

    /** 一次完整采样（按 point 索引存放 → 与遍历顺序无关）。 */
    static final class Capture {
        final double[] e;
        final double[] quick;
        final double[] sampleE;
        final double[] height;
        final double[][] weights;
        long hits;
        long misses;

        Capture(int n, int types) {
            e = new double[n];
            quick = new double[n];
            sampleE = new double[n];
            height = new double[n];
            weights = new double[n][types];
        }
    }

    static Capture capture(CellGenerator gen, List<Point> pts, int[] order) {
        Capture c = new Capture(pts.size(), TerrainClass.COUNT);
        long h0 = gen.eqCacheHits();
        long m0 = gen.eqCacheMisses();
        for (int idx : order) {
            Point p = pts.get(idx);
            double e = gen.terrainE(p.x(), p.z());
            double quick = gen.terrainEQuick(p.x(), p.z());
            Cell cell = gen.sample(p.x(), p.z());
            c.e[idx] = e;
            c.quick[idx] = quick;
            c.sampleE[idx] = cell.e;
            c.height[idx] = cell.height;
            if (cell.typeWeights != null) {
                System.arraycopy(cell.typeWeights, 0, c.weights[idx], 0,
                        Math.min(cell.typeWeights.length, c.weights[idx].length));
            }
        }
        c.hits = gen.eqCacheHits() - h0;
        c.misses = gen.eqCacheMisses() - m0;
        return c;
    }

    /** 并发 pass：barrier 同时起跑，每个线程走自己的轮转顺序，写入各自 point 索引。 */
    static Capture concurrentCapture(CellGenerator gen, List<Point> pts, int threads, long seed) throws Exception {
        Capture c = new Capture(pts.size(), TerrainClass.COUNT);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int offset = (int) ((seed ^ (t * 0x9E3779B9L)) & 0x7FFFFFFF) % Math.max(1, pts.size());
            Thread th = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int k = 0; k < pts.size(); k++) {
                        int idx = (k + offset) % pts.size();
                        Point p = pts.get(idx);
                        double e = gen.terrainE(p.x(), p.z());
                        double quick = gen.terrainEQuick(p.x(), p.z());
                        Cell cell = gen.sample(p.x(), p.z());
                        c.e[idx] = e;
                        c.quick[idx] = quick;
                        c.sampleE[idx] = cell.e;
                        c.height[idx] = cell.height;
                        if (cell.typeWeights != null) {
                            System.arraycopy(cell.typeWeights, 0, c.weights[idx], 0,
                                    Math.min(cell.typeWeights.length, c.weights[idx].length));
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "terrain-upgrade-pass-" + t);
            th.setDaemon(true);
            th.start();
        }
        ready.await();
        go.countDown();
        done.await();
        if (error.get() != null) throw new Exit(3, "concurrent pass failed: " + error.get());
        return c;
    }

    static Map<String, Object> comparePass(String label, List<Point> pts, Capture base, Capture other,
                                           BufferedWriter bad, long seed) {
        List<Map<String, Object>> detail = new ArrayList<>();
        int diffs = 0;
        diffs += diffField(label, "e", base.e, other.e, pts, detail);
        diffs += diffField(label, "quick", base.quick, other.quick, pts, detail);
        diffs += diffField(label, "sampleE", base.sampleE, other.sampleE, pts, detail);
        diffs += diffField(label, "height", base.height, other.height, pts, detail);
        if (!hashWeights(base.weights).equals(hashWeights(other.weights))) {
            diffs++;
            detail.add(map("pass", label, "field", "typeWeights", "note", "weights-hash differs"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("differences", diffs);
        m.put("detail", detail);
        return m;
    }

    static int diffField(String label, String field, double[] a, double[] b, List<Point> pts,
                         List<Map<String, Object>> detail) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (bits(a[i]) == bits(b[i])) continue;
            count++;
            if (detail.size() < MAX_DETAIL) {
                Map<String, Object> d = mismatch(0L, i, pts.get(i), field, "repeat", a[i], b[i], "nondeterministic");
                d.put("pass", label);
                detail.add(d);
            }
        }
        return count;
    }

    static Map<String, Object> mismatch(long seed, int idx, Point p, String field, String other,
                                        double baseValue, double otherValue, String verdict) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("seed", seed);
        d.put("idx", idx);
        d.put("kind", p.kind());
        d.put("x", p.x());
        d.put("z", p.z());
        d.put("field", field);
        d.put("other", other);
        d.put("baseHex", Long.toHexString(bits(baseValue)));
        d.put("otherHex", Long.toHexString(bits(otherValue)));
        d.put("baseValue", baseValue);
        d.put("otherValue", otherValue);
        d.put("ulp", ulp(baseValue, otherValue));
        d.put("absDiff", Math.abs(baseValue - otherValue));
        d.put("verdict", verdict);
        return d;
    }

    // ----- 哈希/位工具 -----

    static long bits(double v) { return Double.doubleToRawLongBits(v); }

    static long ulp(double a, double b) {
        long x = bits(a);
        long y = bits(b);
        long diff = x - y;
        return diff < 0 ? -diff : diff;
    }

    static int countBits(double[] a, double[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (bits(a[i]) != bits(b[i])) n++;
        return n;
    }

    static long maxUlp(double[] a, double[] b) {
        long max = 0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, ulp(a[i], b[i]));
        return max;
    }

    static double maxAbsDiff(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }

    static String hashDoubles(double[] values) {
        MessageDigest md = sha();
        ByteBuffer bb = ByteBuffer.allocate(8);
        for (double v : values) {
            bb.clear();
            bb.putDouble(v);
            md.update(bb.array());
        }
        return hex(md.digest());
    }

    static String hashWeights(double[][] weights) {
        MessageDigest md = sha();
        ByteBuffer bb = ByteBuffer.allocate(8);
        for (double[] row : weights) {
            for (double v : row) {
                bb.clear();
                bb.putDouble(v);
                md.update(bb.array());
            }
        }
        return hex(md.digest());
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把 per-seed 哈希串按 seed 顺序拌进组合哈希（A/B 先比这一行）。 */
    static void appendHash(MessageDigest md, Object perSeedHash) {
        md.update(String.valueOf(perSeedHash).getBytes(StandardCharsets.US_ASCII));
        md.update((byte) '\n');
    }

    static String sha256(byte[] data) { return hex(sha().digest(data)); }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        int idx = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    static double[] sortedCopy(long[] ns) {
        double[] v = new double[ns.length];
        for (int i = 0; i < ns.length; i++) v[i] = ns[i] / 1000.0;   // ns → µs
        Arrays.sort(v);
        return v;
    }

    // ----- chunk 阶段 -----

    static Map<String, Object> chunkReport(GeoGenesisTerrain terrain, int chunkX0, int chunkZ0, int n) {
        int total = n * n;
        double[][] cold = new double[total][];
        long t0 = System.nanoTime();
        int k = 0;
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) cold[k++] = chunkHeights(terrain, chunkX0 + dx, chunkZ0 + dz);
        }
        double coldMs = (System.nanoTime() - t0) / 1e6;
        double[][] warm = new double[total][];
        t0 = System.nanoTime();
        k = 0;
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) warm[k++] = chunkHeights(terrain, chunkX0 + dx, chunkZ0 + dz);
        }
        double warmMs = (System.nanoTime() - t0) / 1e6;
        int diff = 0;
        int nonFinite = 0;
        MessageDigest md = sha();
        ByteBuffer bb = ByteBuffer.allocate(8);
        for (int i = 0; i < total; i++) {
            for (int j = 0; j < 256; j++) {
                if (bits(cold[i][j]) != bits(warm[i][j])) diff++;
                if (!Double.isFinite(cold[i][j])) nonFinite++;
                bb.clear();
                bb.putDouble(cold[i][j]);
                md.update(bb.array());
            }
        }
        return map(
                "chunks", total,
                "origin", map("cx", chunkX0, "cz", chunkZ0),
                "coldMs", coldMs,
                "warmMs", warmMs,
                "coldWarmDifferences", diff,
                "nonFinite", nonFinite,
                "heightHash", hex(md.digest()),
                "note", "getChunkCells includes erosion tile + hydrology carving; cold call also schedules "
                        + "8 fire-and-forget neighbour preheats (no measurement impact beyond CPU contention)");
    }

    static double[] chunkHeights(GeoGenesisTerrain terrain, int cx, int cz) {
        Cell[] cells = terrain.getChunkCells(cx, cz);
        double[] h = new double[256];
        if (cells == null) {
            Arrays.fill(h, Double.NaN);
            return h;
        }
        for (int i = 0; i < 256; i++) h[i] = (i < cells.length && cells[i] != null) ? cells[i].height : Double.NaN;
        return h;
    }

    // ==================================================================
    // bench
    // ==================================================================

    interface Op { double apply(double x, double z); }

    static int bench(Opts opts, Path out) throws Exception {
        ConfigInput cfg = loadConfig(out, opts);
        long seed = opts.i64("seed", CANONICAL_SEED);
        int samples = opts.i32("samples", 1000, 8, 200_000);
        int warmup = opts.i32("warmup", 200, 0, 100_000);
        int erosionSamples = opts.i32("erosion-samples", 0, 0, 64);
        int chunks = opts.i32("chunks", 0, 0, 64);
        int chunkX = opts.i32("chunk-x", 0, -1_000_000, 1_000_000);
        int chunkZ = opts.i32("chunk-z", 0, -1_000_000, 1_000_000);
        CellGenerator gen = new CellGenerator(cfg.params(), WORLD_MIN_Y, WORLD_MAX_Y);
        gen.seed(seed);
        List<Point> pts = makePoints(seed, samples);
        List<Point> warmPts = makePoints(seed ^ 0xA5A5A5A5L, Math.max(1, Math.min(warmup, 400)));

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(benchStage("quick", gen, seed, gen::terrainEQuick, pts, warmPts, "call"));
        stages.add(benchStage("heightOnly", gen, seed, gen::terrainE, pts, warmPts, "call"));
        stages.add(benchStage("typeField", gen, seed,
                (x, z) -> sum(gen.typeWeightsAt(x, z)), pts, warmPts, "call"));
        stages.add(benchStage("fullCell", gen, seed, (x, z) -> gen.sample(x, z).height, pts, warmPts, "call"));

        Map<String, Object> root = baseReport("bench", cfg, out);
        root.put("bench", map(
                "seed", seed,
                "samples", samples,
                "warmup", warmup,
                "coordinateKind", "deterministic mixed buckets (near/negative-far/far/fractional/grid400/axis)",
                "coldDefinition", "unique coordinates, eq cache cleared by gen.seed(seed) right before the loop",
                "warmDefinition", "immediate repeat over the same coordinates (cache/stage hot)",
                "timingOverhead", "per-call System.nanoTime() included; subtract ~25ns for raw op cost",
                "cpuClock", cpuClockWorks() ? "ok (coarse: Windows thread timer tick ~15.6ms; short stages may read 0)"
                        : "unavailable (ThreadMXBean CPU clock returns 0 on this host)",
                "allocationSupport", allocSupported() ? "com.sun.management.ThreadMXBean" : "unavailable",
                "stages", stages,
                "erosion", erosionStage(gen, seed, pts, erosionSamples),
                "chunks", chunks > 0
                        ? chunkBench(gen, seed, chunkX, chunkZ, chunks)
                        : map("skipped", true, "reason", "--chunks=0 (avoids neighbour preheat storm)"),
                "sink", sink));
        root.put("status", "ok");
        writeJson(out.resolve("bench.json"), root);

        StringBuilder tsv = new StringBuilder(
                "stage\tphase\tn\ttotalWallMs\tmeanUs\tmedianUs\tp50Us\tp95Us\tmaxUs"
                        + "\tcpuTotalMs\tallocBytesPerOp\teqHitsDelta\neqMissesDelta\n");
        for (Map<String, Object> stage : stages) appendBenchTsv(tsv, stage);
        Map<String, Object> benchBody = asMap(root.get("bench"));
        for (String section : new String[]{"erosion", "chunks"}) {
            Map<String, Object> sub = benchBody == null ? null : asMap(benchBody.get(section));
            if (sub != null && !Boolean.TRUE.equals(sub.get("skipped"))) appendBenchTsv(tsv, sub);
        }
        Files.writeString(out.resolve("bench.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        printBenchTable(tsv.toString());
        System.out.println("bench seed=" + seed + " samples=" + samples + " stages=" + stages.size()
                + " -> " + out.toAbsolutePath());
        System.out.println("STATUS=ok");
        return 0;
    }

    /** 人读表格（与 bench.tsv 同数据；日志里就能直接比两个臂）。 */
    static void printBenchTable(String tsv) {
        for (String line : tsv.split("\n")) {
            String[] cells = line.split("\t");
            if (cells.length < 9) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%-12s %-5s %6s %10s %10s %10s %10s %10s",
                    cells[0], cells[1], cells[2], cells[3], cells[4], cells[6], cells[7], cells[8]));
            if (cells.length > 10) sb.append(String.format(Locale.ROOT, " %14s", cells[10]));
            if (cells.length > 11 && !cells[11].isEmpty()) {
                sb.append(String.format(Locale.ROOT, " %12s", cells[11]));
            }
            System.out.println(sb);
        }
    }

    static double sum(double[] v) {
        double s = 0;
        for (double d : v) s += d;
        return s;
    }

    /** 单阶段：热身（不同坐标）→ 清缓存 → cold 测量 → warm 测量。 */
    static Map<String, Object> benchStage(String name, CellGenerator gen, long seed, Op op,
                                          List<Point> pts, List<Point> warmPts, String unit) {
        for (Point p : warmPts) sink += op.apply(p.x(), p.z());
        gen.seed(seed);
        Map<String, Object> cold = measure(op, pts, gen, unit);
        Map<String, Object> warm = measure(op, pts, gen, unit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", name);
        m.put("unit", unit);
        m.put("cold", cold);
        m.put("warm", warm);
        return m;
    }

    static Map<String, Object> measure(Op op, List<Point> pts, CellGenerator gen, String unit) {
        ThreadMXBean tm = cpuBean();
        boolean cpuOk = tm != null;
        com.sun.management.ThreadMXBean sun = allocBean();
        long h0 = gen.eqCacheHits();
        long m0 = gen.eqCacheMisses();
        long cpu0 = cpuOk ? tm.getCurrentThreadCpuTime() : -1;
        long alloc0 = sun == null ? -1 : sun.getCurrentThreadAllocatedBytes();
        long[] lat = new long[pts.size()];
        double acc = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            long r0 = System.nanoTime();
            acc += op.apply(p.x(), p.z());
            lat[i] = System.nanoTime() - r0;
        }
        long wall = System.nanoTime() - t0;
        long cpu = cpuOk ? tm.getCurrentThreadCpuTime() - cpu0 : -1;
        long alloc = sun == null ? -1 : sun.getCurrentThreadAllocatedBytes() - alloc0;
        sink += acc;
        // ★ 诚实口径：部分 Windows JDK 即使 isThreadCpuTimeSupported()=true 也恒返回 0
        //   （实测 Temurin 21.0.11 / Win11 本机 cpuMs 恒 0）。此时报 null + 说明，
        //   绝不把"取不到"写成"耗时 0"。
        boolean cpuUsable = cpuOk && cpu > 0;

        double[] us = sortedCopy(lat);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", unit);
        m.put("n", pts.size());
        m.put("totalWallMs", wall / 1e6);
        m.put("meanUs", pts.isEmpty() ? Double.NaN : wall / 1000.0 / pts.size());
        m.put("medianUs", percentile(us, 0.50));
        m.put("p50Us", percentile(us, 0.50));
        m.put("p95Us", percentile(us, 0.95));
        m.put("maxUs", us.length == 0 ? Double.NaN : us[us.length - 1]);
        m.put("cpuTotalMs", cpuUsable ? cpu / 1e6 : null);
        m.put("cpuPerOpUs", cpuUsable && !pts.isEmpty() ? cpu / 1000.0 / pts.size() : null);
        m.put("cpuNote", !cpuOk
                ? "current-thread CPU clock unavailable (ThreadMXBean reports unsupported)"
                : (cpu <= 0
                        ? "0 = below OS thread-clock resolution (Windows thread timer tick ~15.6ms); "
                        + "use totalWallMs for sub-tick stages"
                        : null));
        m.put("allocSupported", alloc >= 0);
        m.put("allocBytesTotal", alloc >= 0 ? alloc : null);
        m.put("allocBytesPerOp", alloc >= 0 && !pts.isEmpty() ? (double) alloc / pts.size() : null);
        m.put("eqHitsDelta", gen.eqCacheHits() - h0);
        m.put("eqMissesDelta", gen.eqCacheMisses() - m0);
        return m;
    }

    static Map<String, Object> erosionStage(CellGenerator gen, long seed, List<Point> pts, int n) {
        if (n <= 0) {
            return map("skipped", true,
                    "reason", "--erosion-samples=0 (erodedHeight triggers erosion tile generation)");
        }
        List<Point> sub = pts.subList(0, Math.min(n, pts.size()));
        gen.seed(seed);
        Map<String, Object> cold = measure(gen::erodedHeight, sub, gen, "point");
        Map<String, Object> warm = measure(gen::erodedHeight, sub, gen, "point");
        return map("stage", "erodedHeight", "unit", "point", "cold", cold, "warm", warm,
                "note", "first call per 48wu tile runs the full droplet erosion; warm = tile cache hit");
    }

    static Map<String, Object> chunkBench(CellGenerator gen, long seed, int cx, int cz, int n) {
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);
        Map<String, Object> cold = measureChunks(terrain, cx, cz, n);
        Map<String, Object> warm = measureChunks(terrain, cx, cz, n);
        return map("stage", "chunkCells", "unit", "chunk", "cold", cold, "warm", warm,
                "note", "--chunks>0 also fires 8 neighbour preheats per cold tile (fire-and-forget)");
    }

    static Map<String, Object> measureChunks(GeoGenesisTerrain terrain, int cx, int cz, int n) {
        int total = n * n;
        long[] lat = new long[total];
        double hashAcc = 0;
        long t0 = System.nanoTime();
        int k = 0;
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                long r0 = System.nanoTime();
                double[] h = chunkHeights(terrain, cx + dx, cz + dz);
                lat[k++] = System.nanoTime() - r0;
                hashAcc += h[0] + h[255];
            }
        }
        long wall = System.nanoTime() - t0;
        sink += hashAcc;
        double[] us = sortedCopy(lat);
        return map("unit", "chunk", "n", total, "totalWallMs", wall / 1e6,
                "meanUs", wall / 1000.0 / total, "medianUs", percentile(us, 0.5),
                "p50Us", percentile(us, 0.5), "p95Us", percentile(us, 0.95),
                "maxUs", us[us.length - 1]);
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

    static boolean allocSupported() { return allocBean() != null; }

    /** 当前线程 CPU 计时 Bean；未开启则尝试开启（JVM 不支持时返回 null）。 */
    static ThreadMXBean cpuBean() {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        try {
            if (!tm.isCurrentThreadCpuTimeSupported()) return null;
            if (!tm.isThreadCpuTimeEnabled()) tm.setThreadCpuTimeEnabled(true);
            return tm.isThreadCpuTimeEnabled() ? tm : null;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * 本机线程 CPU 时钟是否<b>真的在走</b>。
     *
     * <p>必要性：Windows 的线程 CPU 计时按调度器 tick（约 15.6ms）更新 ⇒ 一个 2ms 的忙循环
     * 读数恒不变，看起来像"不支持"。故这里故意忙 ~250ms 再判定；判定失败时所有 CPU 字段报 null。
     * 判定成功也不代表亚 tick 的短阶段有读数 —— {@link #measure} 对 0 值单独说明。</p>
     */
    static boolean cpuClockWorks() {
        ThreadMXBean tm = cpuBean();
        if (tm == null) return false;
        long c0 = tm.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        double acc = 0;
        long i = 0;
        while (System.nanoTime() - start < 250_000_000L) {
            acc += (i * 31) % 7;
            i++;
        }
        sink += acc + i;
        return tm.getCurrentThreadCpuTime() > c0;
    }

    static void appendBenchTsv(StringBuilder sb, Map<String, Object> stage) {
        for (String phase : new String[]{"cold", "warm"}) {
            Object raw = stage.get(phase);
            if (!(raw instanceof Map<?, ?> map)) continue;
            sb.append(str(stage.get("stage"))).append('\t').append(phase);
            for (String key : new String[]{"n", "totalWallMs", "meanUs", "medianUs", "p50Us", "p95Us",
                    "maxUs", "cpuTotalMs", "allocBytesPerOp", "eqHitsDelta", "eqMissesDelta"}) {
                Object v = map.get(key);
                sb.append('\t').append(v == null ? "" : num(v));
            }
            sb.append('\n');
        }
    }

    // ==================================================================
    // 输出工具
    // ==================================================================

    static Map<String, Object> baseReport(String mode, ConfigInput cfg, Path out) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("probe", "TerrainUpgradeProbe");
        root.put("version", PROBE_VERSION);
        root.put("mode", mode);
        root.put("generatedAt", Instant.now().toString());
        root.put("diagnostic", true);
        root.put("outputDir", out.toAbsolutePath().toString());
        root.put("config", map(
                "toml", cfg.toml().toAbsolutePath().toString(),
                "sha256", cfg.sha256(),
                "correctedByForge", cfg.corrected(),
                "fingerprint", GeoGenesisConfig.configFingerprint(),
                "horizontalScale", cfg.params().horizontalScale(),
                "seaLevel", cfg.params().seaLevel(),
                "minY", cfg.params().minY(),
                "maxY", cfg.params().maxY()));
        root.put("world", map("minY", WORLD_MIN_Y, "maxY", WORLD_MAX_Y, "seedCanonical", CANONICAL_SEED));
        root.put("java", map(
                "version", System.getProperty("java.version"),
                "vendor", System.getProperty("java.vendor"),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory()));
        Map<String, String> props = new LinkedHashMap<>();
        for (String k : System.getProperties().stringPropertyNames().stream().sorted().toList()) {
            if (k.startsWith("geogenesis.") || k.startsWith("hydro.")) props.put(k, System.getProperty(k));
        }
        root.put("jvmProperties", props);
        return root;
    }

    static void writeJson(Path target, Object value) throws IOException {
        Files.writeString(target, GSON.toJson(sanitize(value)) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /**
     * Gson 默认拒写 NaN/Infinity（它们不是合法 JSON）⇒ 统一转 {@code null}。
     *
     * <p>为什么不让 Gson 写 {@code NaN}：那是非法 JSON，Python {@code json.load} 直接抛错。
     * 非有限值本身不被掩盖 —— 每个含它的统计量旁边都有独立的 {@code nonFinite} 计数。</p>
     */
    static Object sanitize(Object value) {
        if (value instanceof Double d) return Double.isFinite(d) ? d : null;
        if (value instanceof Float f) return Float.isFinite(f) ? f : null;
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, sanitize(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(sanitize(v));
            return out;
        }
        if (value instanceof double[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (double v : arr) out.add(sanitize(v));
            return out;
        }
        if (value instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object v : arr) out.add(sanitize(v));
            return out;
        }
        return value;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static String num(Object o) {
        if (o instanceof Double d) return String.format(Locale.ROOT, "%.6f", d);
        return String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    static void writeSampleRows(BufferedWriter tsv, long seed, List<Point> pts, Capture c) throws IOException {
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            tsv.write(seed + "\t" + i + "\t" + p.kind() + "\t" + fmt(p.x()) + "\t" + fmt(p.z())
                    + "\t" + fmt(c.e[i]) + "\t" + Long.toHexString(bits(c.e[i]))
                    + "\t" + fmt(c.quick[i]) + "\t" + Long.toHexString(bits(c.quick[i]))
                    + "\t" + fmt(c.sampleE[i]) + "\t" + Long.toHexString(bits(c.sampleE[i]))
                    + "\t" + fmt(c.height[i])
                    + "\t" + (bits(c.quick[i]) == bits(c.e[i]))
                    + "\t" + (bits(c.sampleE[i]) == bits(c.e[i])) + "\n");
        }
    }

    static void writeMismatchRow(BufferedWriter w, long seed, Map<String, Object> d) throws IOException {
        w.write(seed + "\t" + d.getOrDefault("idx", "") + "\t" + d.getOrDefault("kind", "")
                + "\t" + fmt(d.get("x")) + "\t" + fmt(d.get("z"))
                + "\t" + d.getOrDefault("field", "") + "\t" + d.getOrDefault("baseHex", "")
                + "\t" + d.getOrDefault("otherHex", "") + "\t" + d.getOrDefault("ulp", "")
                + "\t" + d.getOrDefault("absDiff", "") + "\t" + d.getOrDefault("verdict", "") + "\n");
    }

    static String fmt(Object o) {
        if (o instanceof Double d) return String.format(Locale.ROOT, "%.17g", d);
        return String.valueOf(o);
    }

    // ==================================================================
    // self-test（无需 TOML：只验证本探针的判据本身）
    // ==================================================================

    static int selfTest() {
        List<String> fails = new ArrayList<>();
        List<String> checks = new ArrayList<>();

        // 1) 分位数
        double[] s = {1, 2, 3, 4, 5};
        checks.add("percentile50=" + percentile(s, 0.5));
        if (percentile(s, 0.5) != 3.0) fails.add("percentile p50");
        if (percentile(s, 0.0) != 1.0) fails.add("percentile p0");
        if (percentile(s, 1.0) != 5.0) fails.add("percentile p100");

        // 2) 位比较能抓到 1 ulp（反向用例：判据不是 no-op）
        double a = 0.1;
        double b = Math.nextUp(0.1);
        checks.add("ulp(0.1,nextUp)= +" + ulp(a, b));
        if (bits(a) == bits(b)) fails.add("bit equality is a no-op");
        if (ulp(a, b) != 1) fails.add("ulp distance");
        if (!(Math.abs(a - b) > 0)) fails.add("abs diff");

        // 3) 哈希：同序稳定、异值不同、顺序敏感（口径说明）
        double[] h1 = {1.0, -0.0, 400.0};
        double[] h2 = {1.0, 0.0, 400.0};
        if (!hashDoubles(h1).equals(hashDoubles(h1.clone()))) fails.add("hash not stable");
        if (hashDoubles(h1).equals(hashDoubles(h2))) fails.add("hash does not separate -0.0 from 0.0");

        // 4) 点位生成：覆盖负/远/小数/400 网格，且无重复
        List<Point> pts = makePoints(12345L, 600);
        Set<String> kinds = new HashSet<>();
        boolean negative = false;
        boolean far = false;
        boolean fractional = false;
        Set<Long> keys = new HashSet<>();
        for (Point p : pts) {
            kinds.add(p.kind());
            negative |= p.x() < 0 && p.z() < 0;
            far |= Math.abs(p.x()) > 1e5;
            fractional |= p.x() != Math.rint(p.x());
            keys.add(bits(p.x()) ^ Long.rotateLeft(bits(p.z()), 17));
        }
        checks.add("points=" + pts.size() + " kinds=" + kinds.size() + " unique=" + keys.size());
        if (pts.size() != 600 || keys.size() != 600) fails.add("point dedupe");
        if (!negative || !far || !fractional) fails.add("point coverage (negative/far/fractional)");
        if (!kinds.contains("grid400")) fails.add("point coverage (grid400)");
        if (makePoints(600L, 40).size() != 40) fails.add("point generation for another seed");

        // 5) 乱序是置换
        int[] order = shuffledOrder(97, 7L);
        boolean[] seen = new boolean[97];
        int touched = 0;
        for (int v : order) {
            if (!seen[v]) {
                seen[v] = true;
                touched++;
            }
        }
        if (touched != 97) fails.add("shuffled order is not a permutation");
        checks.add("permutation=" + touched);

        // 6) 生产链自证：terrainE 与 sample().e 必须逐位相等（同走 sampleCore）
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, WORLD_MIN_Y, WORLD_MAX_Y);
        gen.seed(CANONICAL_SEED);
        int compared = 0;
        for (Point p : makePoints(CANONICAL_SEED, 12)) {
            double e = gen.terrainE(p.x(), p.z());
            double q = gen.terrainEQuick(p.x(), p.z());
            double sE = gen.sample(p.x(), p.z()).e;
            if (bits(e) != bits(sE)) fails.add("terrainE != sample().e at (" + p.x() + "," + p.z() + ")");
            if (bits(q) != bits(e)) {
                checks.add("quick!=terrainE at (" + p.x() + "," + p.z() + ") ulp=" + ulp(q, e)
                        + " (direct-mapped cache collision expected once other points were sampled)");
            }
            compared++;
        }
        checks.add("production-chain points=" + compared);
        if (compared != 12) fails.add("production-chain point count");

        // 7) 故障反控：扰动一个值后 diffField 必须报数
        double[] base = {0.0, 1.0, 2.0};
        double[] perturbed = {0.0, Math.nextUp(1.0), 2.0};
        List<Map<String, Object>> detail = new ArrayList<>();
        int d = diffField("selftest", "e", base, perturbed, pts, detail);
        if (d != 1) fails.add("diffField does not detect a 1-ulp perturbation");
        if (detail.size() != 1) fails.add("diffField detail");
        checks.add("faultReverseControl detected=" + d);

        // 8) 渲染侧共用判据（固定色阶 / 400 线跨越 / 原始图索引）
        TerrainUpgradeRender.selfChecks(fails, checks);

        for (String c : checks) System.out.println("[self-test] " + c);
        for (String f : fails) System.out.println("[self-test] FAIL " + f);
        System.out.println(fails.isEmpty()
                ? "PASS TerrainUpgradeProbe self-test checks=" + checks.size()
                : "FAIL TerrainUpgradeProbe self-test failures=" + fails.size());
        return fails.isEmpty() ? 0 : 1;
    }
}
