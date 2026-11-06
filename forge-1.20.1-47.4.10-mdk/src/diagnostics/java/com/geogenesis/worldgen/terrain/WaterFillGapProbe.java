package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 灌水缺口扫描（<b>只读</b>）：判断"水面台阶 / 水体内干洞"是否已经归零。
 *
 * <p>两组口径同时报：</p>
 * <ul>
 *   <li><b>raw</b>：只按 {@code Cell} 的原始计划（{@code riverType/riverSurfaceY/height}）
 *       算 —— 这是修复前的样子，用于对照。</li>
 *   <li><b>filled</b>：按落块层真正会用的水面算 —— 即
 *       {@link WaterSurfaceUnifier#unifiedTops} 的统一整数水面。判据：
 *       <ul>
 *         <li>台阶：相邻两个"会灌水"的列，统一水面差 &gt; 0；</li>
 *         <li>干洞：会灌水的列，但统一水面 {@code <= floor(地面)}
 *             ⇒ 落块循环 {@code y in (surfaceY, fillTopBlock]} 一次都不执行 ⇒ 没有水块。</li>
 *       </ul></li>
 * </ul>
 *
 * <p>验收口径：<b>filled 的台阶与干洞都必须为 0</b>，否则 {@code System.exit(1)}。
 * 目前两处都非零 —— 这是待修缺陷的度量，不是修复后的回归闸门。</p>
 *
 * <p>用法：{@code gradlew runWaterFillGapProbe [-PprobeArgs="0,-2 -3,-1"]}</p>
 * <p>逐位证据：{@code -PprobeArgs="--seed=686443174003246080 --timing-only
 * --snapshot=build/before-heavy 2,-2"}。补充低请求量样例：
 * {@code --seed=12345 --timing-only --snapshot=build/before-small 0,0}。
 * fresh path 为尚不存在的目录，父目录须存在；每 region 固定 5 chunks，
 * 冷启动仍可能触发生产邻区/侵蚀工作，非耗时上限保证。每臂用独立 JVM。
 * 对拍：{@code -PprobeArgs="--compare=build/before-heavy build/after-heavy"}；
 * 失败非零退出。源码/config provenance 单独保存，不混入几何 payload。
 * 不带 --snapshot 的原扫描与 --timing-only 行为不变。</p>
 */
public final class WaterFillGapProbe {

    private static final int REGION = 512;
    private static final long SEED = 9139912035078620160L;

    private WaterFillGapProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 1 && args[0].equals("--snapshot-self-test")) {
            snapshotSelfTest();
            return;
        }
        if (args.length == 2 && args[0].startsWith("--compare=")) {
            compareSnapshots(Path.of(args[0].substring(10)), Path.of(args[1]));
            return;
        }
        Path snapshot = null;
        for (String arg : args) {
            if (arg.startsWith("--snapshot=")) snapshot = Path.of(arg.substring(11)).toAbsolutePath();
        }
        // Fresh directory only: never overwrite baseline evidence, even after a failed run.
        if (snapshot != null) Files.createDirectory(snapshot);
        Path out = snapshot == null ? Path.of("build", "water-fill-gap") : snapshot;
        Files.createDirectories(out);

        if (args.length == 3 && args[0].equals("--compare")) {
            compareSnapshots(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        long seed = SEED;
        boolean timingOnly = java.util.Arrays.asList(args).contains("--timing-only");
        List<int[]> regions = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--seed=")) {
                seed = Long.parseLong(a.substring("--seed=".length()));
                continue;
            }
            if (a.startsWith("--")) {
                if (!a.equals("--timing-only") && !a.startsWith("--snapshot=")) {
                    throw new IllegalArgumentException("Unknown option: " + a);
                }
                continue;
            }
            String[] p = a.split(",");
            if (p.length == 2) {
                regions.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())});
            }
        }
        if (regions.isEmpty()) {
            regions.add(new int[]{0, -2});
            regions.add(new int[]{-3, -1});
        }

        TerrainParams tp = loadConfig(out);
        // 与生产 GeoGenesisGenerator.buildTerrain 同构：morphBase=true + terrain.seed()
        CellGenerator gen = new CellGenerator(tp, (int) tp.minY(), (int) tp.maxY(), true);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);

        System.out.printf("=== WaterFillGapProbe seed=%d regions=%d ===%n", seed, regions.size());
        if (snapshot != null) {
            try {
                snapshot(terrain, gen, tp, seed, regions, args, snapshot);
            } finally {
                gen.closeProcessErosion();
            }
            return; // Snapshot is bounded regardless of --timing-only; never scan 1024 chunks.
        }
        int rawSteps = 0, rawHoles = 0, effSteps = 0, effHoles = 0, lakeSteps = 0;
        for (int[] r : regions) {
            if (timingOnly) {
                terrain.getChunkCells(r[0] * 32 + 16, r[1] * 32 + 16);
                continue;
            }
            int[] c = scan(terrain, r[0], r[1]);
            rawSteps += c[0];
            rawHoles += c[1];
            effSteps += c[2];
            effHoles += c[3];
            lakeSteps += c[4];
        }
        if (timingOnly) {
            System.out.println("TIMING COLLECTED (water-gap assertions not run)");
            gen.closeProcessErosion();
            return;
        }
        System.out.println();
        System.out.println("汇总：");
        System.out.printf("  raw    （修复前口径）台阶=%d 干洞=%d%n", rawSteps, rawHoles);
        System.out.printf("  filled （落块实际口径）台阶=%d（其中湖内 %d）/ 干洞=%d%n",
                effSteps, lakeSteps, effHoles);
        // 只有【湖内台阶】与【干洞】算缺陷：河面逐段下降 1 块是正常坡降。
        boolean ok = lakeSteps == 0 && effHoles == 0;
        System.out.println("  判定: " + (ok
                ? "PASS（湖内台阶=0 且 干洞=0；河面台阶属正常坡降，不计）"
                : "FAIL（湖内台阶或干洞未归零）"));
        gen.closeProcessErosion();
        System.exit(ok ? 0 : 1);
    }

    /** 扫一个 region，返回 {raw 台阶, raw 干洞, filled 台阶, filled 干洞}。 */
    private static int[] scan(GeoGenesisTerrain terrain, int rx, int rz) {
        int n = REGION;
        int baseX = rx * n;
        int baseZ = rz * n;
        double seaLevel = terrain.seaLevel();

        boolean[] rawWet = new boolean[n * n];
        double[] rawTop = new double[n * n];
        boolean[] isLake = new boolean[n * n];
        boolean[] effWet = new boolean[n * n];
        int[] effTop = new int[n * n];
        double[] ground = new double[n * n];

        long t0 = System.nanoTime();
        for (int cx = 0; cx < n / 16; cx++) {
            for (int cz = 0; cz < n / 16; cz++) {
                Cell[] cells = terrain.getChunkCells(baseX / 16 + cx, baseZ / 16 + cz);
                int[] uni = WaterSurfaceUnifier.unifiedTops(cells, seaLevel, true);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Cell cell = cells[lx * 16 + lz];
                        if (cell == null) {
                            continue;
                        }
                        int idx = (cx * 16 + lx) * n + (cz * 16 + lz);
                        ground[idx] = cell.height;
                        boolean river = cell.riverType != 0 && cell.riverSurfaceY > cell.height;
                        boolean ocean = cell.height < seaLevel;
                        rawWet[idx] = river || ocean;
                        rawTop[idx] = ocean ? Math.max(cell.riverSurfaceY, seaLevel) : cell.riverSurfaceY;
                        // 落块口径：真正会用到的统一水面（与 GeoGenesisGenerator 同一个函数）
                        int u = uni[lx * 16 + lz];
                        isLake[idx] = cell.isLake;
                        effWet[idx] = u >= 0;
                        effTop[idx] = u;
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println();
        System.out.printf("--- region(%d,%d) 扫完 %d chunks，%d ms ---%n", rx, rz, (n / 16) * (n / 16), ms);

        int rSteps = 0, rHoles = 0, eSteps = 0, eHoles = 0;
        int lakeSteps = 0, riverSteps = 0;
        List<String> samples = new ArrayList<>();
        int wetEff = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int idx = i * n + j;
                int wx = baseX + i, wz = baseZ + j;

                // raw：相邻湿列水位差 > 0.5
                if (rawWet[idx]) {
                    if (i + 1 < n && rawWet[idx + n] && Math.abs(rawTop[idx + n] - rawTop[idx]) > 0.5) {
                        rSteps++;
                    }
                    if (j + 1 < n && rawWet[idx + 1] && Math.abs(rawTop[idx + 1] - rawTop[idx]) > 0.5) {
                        rSteps++;
                    }
                } else {
                    int wetN = 0;
                    double hi = Double.NEGATIVE_INFINITY;
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            if (di == 0 && dj == 0) continue;
                            int a = i + di, b = j + dj;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            int k = a * n + b;
                            if (rawWet[k]) { wetN++; hi = Math.max(hi, rawTop[k]); }
                        }
                    }
                    if (wetN >= 5 && hi > ground[idx] + 0.5) {
                        rHoles++;
                    }
                }

                if (!effWet[idx]) {
                    continue;
                }
                wetEff++;
                // filled：整数水面差 >= 1 才算台阶（整数落块只有整块差）
                if (i + 1 < n && effWet[idx + n] && effTop[idx + n] != effTop[idx]) {
                    eSteps++;
                    if (isLake[idx] && isLake[idx + n]) lakeSteps++; else riverSteps++;
                    if (samples.size() < 6) {
                        samples.add(String.format("台阶 (%d,%d)=%d | (%d,%d)=%d",
                                wx, wz, effTop[idx], wx + 1, wz, effTop[idx + n]));
                    }
                }
                if (j + 1 < n && effWet[idx + 1] && effTop[idx + 1] != effTop[idx]) {
                    eSteps++;
                    if (isLake[idx] && isLake[idx + 1]) lakeSteps++; else riverSteps++;
                    if (samples.size() < 6) {
                        samples.add(String.format("台阶 (%d,%d)=%d | (%d,%d)=%d",
                                wx, wz, effTop[idx], wx, wz + 1, effTop[idx + 1]));
                    }
                }
                // 干洞：会灌水，但水面不高于地面 ⇒ 一个水块都放不下
                if (effTop[idx] <= (int) Math.floor(ground[idx])) {
                    eHoles++;
                    if (samples.size() < 12) {
                        samples.add(String.format("干洞 (%d,%d) 水面=%d 地面=%.2f",
                                wx, wz, effTop[idx], ground[idx]));
                    }
                }
            }
        }
        System.out.printf("  湿列=%d/%d  raw(台阶=%d,干洞=%d)  filled(台阶=%d[湖内=%d,河面=%d],干洞=%d)%n",
                wetEff, n * n, rSteps, rHoles, eSteps, lakeSteps, riverSteps, eHoles);
        for (String s : samples) {
            System.out.println("    " + s);
        }
        return new int[]{rSteps, rHoles, eSteps, eHoles, lakeSteps};
    }

    /** Snapshot v1: big-endian DataOutputStream; typed values, raw IEEE bits, stable field names.
     * ForProbe methods are package-private in current production, accessed read-only by reflection.
     * No cache internals or independent provider: use the exact instance used by getChunkCells.
     */
    private static void snapshot(GeoGenesisTerrain terrain, CellGenerator gen, TerrainParams tp,
                                 long seed, List<int[]> regions, String[] args, Path out) throws Exception {
        provenance(out, args, seed);
        try (var data = binary(out.resolve("settings.bin"))) {
            exact(data, tp);
            data.writeLong(seed);
            exact(data, regions);
            data.writeLong(Double.doubleToRawLongBits(terrain.seaLevel()));
        }
        int regionSize = (int) staticField(
                com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider.class, "REGION");
        if (regionSize != REGION) throw new IllegalStateException("This fixture requires REGION=512");
        for (int[] r : regions) snapshotRegion(terrain, gen, r[0], r[1], out);
        // COMPLETE is last; incomplete evidence is never accepted by --compare.
        List<String> hashes = new ArrayList<>();
        try (var files = Files.list(out)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".bin")).sorted().toList()) {
                hashes.add(p.getFileName() + " " + sha256(p));
            }
        }
        Files.write(out.resolve("COMPLETE"), hashes, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("SNAPSHOT COMPLETE: " + out + " (no gap assertions; 5 chunks/region)");
    }

    private static void snapshotRegion(GeoGenesisTerrain terrain, CellGenerator gen,
                                       int rx, int rz, Path out) throws Exception {
        int cx = rx * 32 + 16, cz = rz * 32 + 16;
        // First request is identical to the pre-existing --timing-only target.
        Cell[] target = terrain.getChunkCells(cx, cz);
        Object engine = field(terrain, "hydrologyExperiment");
        Object provider = field(engine, "modelProvider");
        if (provider == null) throw new IllegalStateException("Production provider was not initialized");
        Path file = out.resolve("region_" + rx + "_" + rz + ".bin");
        try (var data = binary(file)) {
            data.writeUTF("WaterFillGapSnapshot");
            data.writeInt(1);
            data.writeInt(rx);
            data.writeInt(rz);
            int bx = rx * REGION + REGION / 2, bz = rz * REGION + REGION / 2;
            exact(data, probe(provider, "windowFrameForProbe", bx, bz));
            Object sk = probe(provider, "finalSkeletonForProbe", rx, rz);
            if (sk == null) throw new IllegalStateException("Missing production final skeleton");
            // Raw trace order and raw (unstamped) ports preserved, not sorted or reduced to counts.
            exact(data, field(sk, "traces"));
            exact(data, field(sk, "ports"));
            exact(data, field(sk, "pendingExits"));
            exact(data, field(sk, "anchorPins"));
            exact(data, probe(provider, "finalPortsForProbe", rx, rz));
            exact(data, probe(provider, "shapedNodesWorldForProbe", bx, bz));
            // Two adjacent chunks straddling east seam, two straddling south seam.
            int[][] chunks = {{cx, cz}, {rx * 32 + 31, cz}, {rx * 32 + 32, cz},
                    {cx, rz * 32 + 31}, {cx, rz * 32 + 32}};
            exact(data, chunks);
            for (int i = 0; i < chunks.length; i++) {
                int x = chunks[i][0], z = chunks[i][1];
                Cell[] cells = i == 0 ? target : terrain.getChunkCells(x, z);
                writeCells(data, cells);
                exact(data, WaterSurfaceUnifier.unifiedTops(cells, terrain.seaLevel(), true));
                // Read the public resampling entry, not a reconstruction from final carved heights.
                // This additional list is labelled separately from actual getChunkCells above.
                data.writeUTF("public-calculate-resampled-column-plan");
                exact(data, terrain.calculateHydrologyChunk(x, z).carvedColumns());
            }
        }
        System.out.println(file.getFileName() + " sha256=" + sha256(file));
    }

    private static void writeCells(java.io.DataOutputStream data, Cell[] cells) throws Exception {
        data.writeUTF("actual-getChunkCells-scalars-enums-typeWeights:index=lx*16+lz");
        var fields = java.util.Arrays.stream(Cell.class.getFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() || f.getType().isEnum()
                        || f.getType() == double[].class)
                .sorted(java.util.Comparator.comparing(java.lang.reflect.Field::getName)).toList();
        data.writeInt(cells.length);
        for (Cell cell : cells) {
            data.writeBoolean(cell != null);
            if (cell == null) continue;
            data.writeInt(fields.size());
            for (var f : fields) {
                data.writeUTF(f.getName());
                exact(data, f.get(cell));
            }
        }
    }

    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        var f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object probe(Object provider, String name, int x, int z) throws Exception {
        var m = provider.getClass().getDeclaredMethod(name, int.class, int.class);
        m.setAccessible(true);
        return m.invoke(provider, x, z);
    }

    private static java.io.DataOutputStream binary(Path path) throws Exception {
        return new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW)));
    }

    /** No toString fallback: unsupported payloads fail rather than silently lose bits. */
    private static void exact(java.io.DataOutputStream out, Object value) throws Exception {
        if (value == null) { out.writeByte(0); return; }
        if (value instanceof Double v) { out.writeByte(1); out.writeLong(Double.doubleToRawLongBits(v)); }
        else if (value instanceof Float v) { out.writeByte(2); out.writeInt(Float.floatToRawIntBits(v)); }
        else if (value instanceof Integer v) { out.writeByte(3); out.writeInt(v); }
        else if (value instanceof Long v) { out.writeByte(4); out.writeLong(v); }
        else if (value instanceof Boolean v) { out.writeByte(5); out.writeBoolean(v); }
        else if (value instanceof Byte v) { out.writeByte(6); out.writeByte(v); }
        else if (value instanceof String v) { out.writeByte(7); out.writeUTF(v); }
        else if (value instanceof Enum<?> v) {
            out.writeByte(8); out.writeUTF(v.getDeclaringClass().getName()); out.writeUTF(v.name());
        } else if (value.getClass().isArray()) {
            out.writeByte(9);
            int n = java.lang.reflect.Array.getLength(value);
            out.writeInt(n);
            for (int i = 0; i < n; i++) exact(out, java.lang.reflect.Array.get(value, i));
        } else if (value instanceof List<?> list) {
            out.writeByte(10); out.writeInt(list.size());
            for (Object item : list) exact(out, item);
        } else if (value instanceof java.util.Map<?, ?> map) {
            out.writeByte(11); out.writeInt(map.size());
            // anchorPins has Integer keys: canonical iteration, retain every key/value bit.
            var keys = new java.util.TreeSet<Integer>();
            for (Object key : map.keySet()) keys.add((Integer) key);
            for (Integer key : keys) { exact(out, key); exact(out, map.get(key)); }
        } else if (value.getClass().isRecord()) {
            out.writeByte(12); out.writeUTF(value.getClass().getName());
            var components = value.getClass().getRecordComponents();
            out.writeInt(components.length);
            for (var c : components) {
                out.writeUTF(c.getName());
                var accessor = c.getAccessor();
                accessor.setAccessible(true);
                exact(out, accessor.invoke(value));
            }
        } else if (value instanceof com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace) {
            out.writeByte(13);
            var fields = java.util.Arrays.stream(value.getClass().getFields())
                    .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .sorted(java.util.Comparator.comparing(java.lang.reflect.Field::getName)).toList();
            out.writeInt(fields.size());
            for (var f : fields) { out.writeUTF(f.getName()); exact(out, f.get(value)); }
        } else throw new IllegalArgumentException("Unsupported exact payload: " + value.getClass());
    }

    /** Cheap serializer test only: no terrain/provider solve, no benchmark. */
    private static void snapshotSelfTest() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(bytes);
        exact(data, -0.0d);
        long nanBits = 0x7ff8000000000042L;
        exact(data, Double.longBitsToDouble(nanBits));
        var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        if (input.readByte() != 1 || input.readLong() != Long.MIN_VALUE
                || input.readByte() != 1 || input.readLong() != nanBits) {
            throw new AssertionError("Raw signed zero/NaN bits lost");
        }
        exact(data, TerrainParams.defaults());
        exact(data, com.geogenesis.worldgen.hydrology.model.BoundaryPort.of(1, 2, 3, 4, -0.0, 0.5, 5, -1, 1));
        exact(data, new com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn(
                1, 2, 3.0, 2.0, 4.0, 4.0, 1.0, 0.0, true, false));
        exact(data, new double[][]{{-0.0, Double.longBitsToDouble(nanBits)}, {}});
        writeCells(data, new Cell[]{new Cell(), null});
        System.out.println("SNAPSHOT SERIALIZER PASS (no world generation)");
    }

    private static String sha256(Path path) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void provenance(Path out, String[] args, long seed) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("snapshot-format=1; big-endian; raw IEEE754; no timestamps in payload");
        lines.add("seed=" + seed);
        lines.add("args=" + String.join(" ", args));
        lines.add("cwd=" + Path.of("").toAbsolutePath());
        lines.add("path=GeoGenesisTerrain.getChunkCells(morphBase=true); production config");
        lines.add("cells=all primitive/enum fields + typeWeights; excludes climate/oceanFeat/landFeat objects");
        lines.add("plans=public calculateHydrologyChunk resampling; NOT intercepted calculateColumns invocation");
        lines.add("jvmArgs=" + String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()));
        for (String key : new java.util.TreeSet<>(System.getProperties().stringPropertyNames())) {
            lines.add("property." + key + "=" + System.getProperty(key));
        }
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            lines.add("env." + key + "=" + System.getenv(key));
        }
        Files.write(out.resolve("provenance.txt"), lines, java.nio.charset.StandardCharsets.UTF_8);
        Files.copy(Path.of("run/config/geogenesis-common.toml"), out.resolve("production-config.toml"));
        List<String> sources = new ArrayList<>();
        for (String root : List.of("src/main", "src/diagnostics", "build/classes/java/main",
                "build/classes/java/diagnostics", "build/resources/main")) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) throw new IllegalStateException("Missing provenance root: " + dir);
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                    sources.add(p + " " + sha256(p));
                }
            }
        }
        for (String name : List.of("build.gradle", "settings.gradle", "gradle.properties")) {
            sources.add(name + " " + sha256(Path.of(name)));
        }
        Files.write(out.resolve("source-classes-sha256.txt"), sources, java.nio.charset.StandardCharsets.UTF_8);
        runtimeClasspathEvidence(out);
        gitEvidence(out, "git-head.txt", "rev-parse", "HEAD");
        gitEvidence(out, "git-status.txt", "status", "--porcelain=v1", "--untracked-files=all");
        gitEvidence(out, "git-diff.patch", "diff", "--binary", "HEAD", "--", ".");
    }

    /** Ordered effective classpath, including overlays/frozen paths, not just workspace build/. */
    private static void runtimeClasspathEvidence(Path out) throws Exception {
        List<String> lines = new ArrayList<>();
        String cp = System.getProperty("java.class.path");
        lines.add("effective-classpath=" + cp);
        int index = 0;
        for (String entry : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path path = Path.of(entry).toAbsolutePath();
            String prefix = "entry[" + index++ + "]=" + path;
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                        lines.add(prefix + " file=" + path.relativize(file) + " sha256=" + sha256(file));
                }
            } else lines.add(prefix + (Files.isRegularFile(path) ? " sha256=" + sha256(path) : " MISSING"));
        }
        for (Class<?> type : List.of(WaterFillGapProbe.class,
                com.geogenesis.worldgen.process.ProcessRuntime.class,
                com.geogenesis.worldgen.core.BaseWorldSampler.class)) {
            lines.add("loaded-class=" + type.getName() + " codeSource="
                    + type.getProtectionDomain().getCodeSource().getLocation());
        }
        Files.write(out.resolve("effective-classpath-sha256.txt"), lines, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void gitEvidence(Path out, String file, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(out.resolve(file).toFile()).start();
        if (p.waitFor() != 0) throw new IllegalStateException("Failed provenance command: " + command);
    }

    private static void compareSnapshots(Path left, Path right) throws Exception {
        for (Path dir : List.of(left, right)) {
            if (!Files.isRegularFile(dir.resolve("COMPLETE"))) {
                throw new IllegalStateException("Incomplete snapshot: " + dir);
            }
            for (String line : Files.readAllLines(dir.resolve("COMPLETE"))) {
                String[] parts = line.split(" ");
                if (parts.length != 2 || !sha256(dir.resolve(parts[0])).equals(parts[1])) {
                    throw new IllegalStateException("Snapshot integrity failure: " + dir + " " + line);
                }
            }
        }
        List<String> names;
        try (var files = Files.list(left)) {
            names = files.filter(p -> p.toString().endsWith(".bin"))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
        try (var files = Files.list(right)) {
            if (!names.equals(files.filter(p -> p.toString().endsWith(".bin"))
                    .map(p -> p.getFileName().toString()).sorted().toList())) {
                throw new IllegalStateException("Snapshot payload file sets differ");
            }
        }
        boolean same = true;
        for (String name : names) {
            long mismatch = Files.mismatch(left.resolve(name), right.resolve(name));
            System.out.println(name + (mismatch < 0 ? " BITWISE PASS" : " FAIL byteOffset=" + mismatch));
            same &= mismatch < 0;
        }
        long configMismatch = Files.mismatch(left.resolve("production-config.toml"),
                right.resolve("production-config.toml"));
        same &= configMismatch < 0;
        System.out.println("production-config.toml" + (configMismatch < 0
                ? " BITWISE PASS" : " FAIL byteOffset=" + configMismatch));
        if (!same) throw new IllegalStateException("Snapshot bitwise mismatch (see byte offsets)");
        System.out.println("All payload/config bits equal; review JVM/source provenance differences separately.");
    }

    private static TerrainParams loadConfig(Path out) {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.isRegularFile(src)) {
            throw new IllegalStateException("缺少生产配置：" + src.toAbsolutePath());
        }
        try {
            Path work = out.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return GeoGenesisConfig.INSTANCE.buildParams();
        } catch (Exception e) {
            throw new IllegalStateException("生产配置加载失败", e);
        }
    }
}