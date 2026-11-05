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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 当前 TOML 新建世界口径的离线水体图：getChunkCells 合成后按 fillTerrainColumn 放水条件上色。
 * 不读取旧存档冻结参数，不运行 Forge 方块放置、装饰和流体 tick，不能作为实机最终验收。
 * 前四个参数保留 seed/cx/cz/radius；第五个参数可指定 TOML。配置缺失或加载失败直接报错。
 */
public final class ProductionViewProbe {
    private static final int DRY = 0, SEA = 1, LAKE = 2, RIVER = 3;
    // 镜像 GeoGenesisGenerator 的世界范围及初始水面；自检覆盖整块水柱边界。
    private static final int MIN_Y = -64, MAX_Y = 320, BASE_WATER_Y = 63;

    private ProductionViewProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        ViewSpec.assertCanonicalRadius(radius, !Boolean.getBoolean("hydro.allowNonCanonicalRadius"));
        Path toml = Path.of(args.length > 4 ? args[4] : "run/config/geogenesis-common.toml");
        Path dir = Path.of("build/hydro-production/prod-" + System.currentTimeMillis());
        Files.createDirectories(dir.getParent());
        Files.createDirectory(dir);
        ConfigInput config = loadConfig(toml, dir);
        CellGenerator gen = new CellGenerator(config.params(), MIN_Y, MAX_Y);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);
        long t0 = System.nanoTime();
        Sample sample = sample(terrain, cx, cz, radius);
        writeImage(sample, dir.resolve("production.png"));
        String report = report(seed, cx, cz, radius, config, terrain, sample, dir,
                (System.nanoTime() - t0) / 1e9);
        Files.writeString(dir.resolve("metrics.txt"), report);
        System.out.print(report);
    }

    private record ConfigInput(TerrainParams params, String sha, boolean corrected) { }

    private static ConfigInput loadConfig(Path toml, Path dir) throws Exception {
        // 仅对输出目录中的副本执行 Forge 校正，绝不保存到用户配置。
        String sha = HierarchicalComparisonView.sha256(toml);
        Path copy = dir.resolve("config-effective.toml");
        Files.copy(toml, dir.resolve("config-input.toml"));
        Files.copy(toml, copy);
        CommentedFileConfig cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        boolean correct = GeoGenesisConfig.SPEC.isCorrect(cfg);
        GeoGenesisConfig.SPEC.setConfig(cfg);
        if (!GeoGenesisConfig.SPEC.isLoaded()) throw new IllegalStateException("TOML not loaded");
        TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
        cfg.save();
        Files.writeString(dir.resolve("terrain-params.txt"), params.toString());
        StringBuilder provenance = new StringBuilder("toml=" + toml.toAbsolutePath() + "\nsha256=" + sha
                + "\nloaded=true\ncorrected=" + !correct + "\nfingerprint="
                + GeoGenesisConfig.configFingerprint() + "\ncascade=" + GeoGenesisConfig.INSTANCE.erosionCascadeStrength.get()
                + "\ngully=" + GeoGenesisConfig.INSTANCE.erosionRidgeGullyWeight.get() + "\n");
        for (String name : new String[]{"REGION", "HALO", "STEP", "SAMPLES", "HIER", "CROSS_PASS"}) {
            var field = ModelHydrologyProvider.class.getDeclaredField(name);
            field.setAccessible(true);
            provenance.append(name).append('=').append(field.get(null)).append('\n');
        }
        provenance.append("MAX_SPILL_RISE=").append(DeterministicSkeleton.MAX_SPILL_RISE).append('\n');
        for (Class<?> type : new Class<?>[]{ProductionViewProbe.class, ModelHydrologyProvider.class,
                DeterministicSkeleton.class, GeoGenesisTerrain.class, CellGenerator.class}) {
            try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                provenance.append(type.getName()).append(" sha256=")
                        .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())))
                        .append(" source=").append(type.getProtectionDomain().getCodeSource().getLocation()).append('\n');
            }
        }
        System.getProperties().stringPropertyNames().stream().sorted()
                .filter(k -> k.startsWith("geogenesis.") || k.startsWith("hydro."))
                .forEach(k -> provenance.append(k).append('=').append(System.getProperty(k)).append('\n'));
        Files.writeString(dir.resolve("provenance.txt"), provenance);
        System.out.println(provenance);
        return new ConfigInput(params, sha, !correct);
    }

    private record Sample(int n, double[] ground, byte[] kind, int chunks, String columnsSha) { }

    private static Sample sample(GeoGenesisTerrain terrain, int cx, int cz, int radius) throws Exception {
        int n = radius * 2 + 1;
        double[] ground = new double[n * n];
        byte[] kind = new byte[n * n];
        boolean[] seen = new boolean[n * n];
        int chunks = 0;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        ByteBuffer values = ByteBuffer.allocate(32);
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                Cell[] cells = terrain.getChunkCells(chX, chZ);
                chunks++;
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int px = chX * 16 + lx - (cx - radius), pz = chZ * 16 + lz - (cz - radius);
                    if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                    int k = px * n + pz;
                    if (seen[k]) throw new AssertionError("duplicate column " + px + "," + pz);
                    Cell cell = cells[lx * 16 + lz];
                    ground[k] = cell.height;
                    kind[k] = (byte) waterKind(cell, terrain.riversEnabled(), terrain.seaLevel());
                    seen[k] = true;
                    values.clear();
                    values.putDouble(cell.height).putDouble(cell.riverSurfaceY).putDouble(cell.riverLipY)
                            .putInt(cell.riverType).putInt(kind[k]);
                    hash.update(values.array());
                }
            }
        }
        for (boolean covered : seen) if (!covered) throw new AssertionError("uncovered column");
        return new Sample(n, ground, kind, chunks, HexFormat.of().formatHex(hash.digest()));
    }

    static int waterKind(Cell cell, boolean hydrologyOn, double seaLevel) {
        if (!Double.isFinite(cell.height)) throw new IllegalArgumentException("non-finite ground");
        // 与 GeoGenesisGenerator.fillTerrainColumn 的 riverWater/ocean/waterTop/lipBlock 顺序一致。
        boolean river = hydrologyOn && cell.riverType != 0 && cell.riverSurfaceY > cell.height;
        boolean ocean = hydrologyOn && cell.height < seaLevel;
        boolean water = hydrologyOn ? (river || ocean) : cell.isWater();
        if (!water) return DRY;
        double top = river ? cell.riverSurfaceY : BASE_WATER_Y;
        if (ocean) top = Math.max(top, seaLevel);
        if (river && cell.riverLipY > top) top = cell.riverLipY;
        if (!Double.isFinite(top)) throw new IllegalArgumentException("non-finite water top");
        // 地面所在整块是石/土；最底层恒基岩，最高可写层 MAX_Y-1。
        double first = Math.max(MIN_Y + 1, Math.floor(cell.height) + 1);
        double last = Math.min(MAX_Y - 1, Math.floor(top));
        if (first > last) return DRY;
        return river ? (cell.isLake ? LAKE : RIVER) : SEA;
    }

    private static void writeImage(Sample sample, Path target) throws Exception {
        BufferedImage image = new BufferedImage(sample.n(), sample.n(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < sample.n(); x++) for (int z = 0; z < sample.n(); z++) {
            int k = x * sample.n() + z;
            int rgb = switch (sample.kind()[k]) {
                case SEA -> ViewSpec.SEA_COLOR;
                case LAKE -> ViewSpec.LAKE_COLOR;
                case RIVER -> ViewSpec.RIVER_COLOR;
                default -> ViewSpec.contourFixed(sample.ground(), sample.n(), x, z);
            };
            image.setRGB(x, z, rgb);
        }
        if (!ImageIO.write(image, "png", target.toFile())) throw new IllegalStateException("PNG writer missing");
    }

    private static String report(long seed, int cx, int cz, int radius, ConfigInput config,
                                 GeoGenesisTerrain terrain, Sample sample, Path dir, double seconds) {
        int[] counts = new int[4];
        for (byte kind : sample.kind()) counts[kind]++;
        StringBuilder sb = new StringBuilder("=== 当前TOML新建世界：生产地形合成离线图（非Forge实机验收）===\n");
        sb.append(String.format("seed=%d centre=(%d,%d) radius=%d image=%dx%d imageStep=1%n",
                seed, cx, cz, radius, sample.n(), sample.n()));
        sb.append("configLoaded=true tomlSHA256=").append(config.sha())
                .append(" correctedByForge=").append(config.corrected()).append('\n');
        sb.append("params=buildParams(); 未读取旧存档冻结参数；有效配置及类指纹见provenance.txt\n");
        sb.append("sampling=GeoGenesisTerrain.getChunkCells; ground=合成后地面; water=整块放水区间非空\n");
        sb.append("bottom=ViewSpec.contourFixed min=0 band=8; 不再按每张图自适应灰阶\n");
        sb.append(String.format("chunks=%d seconds=%.1f uncovered=0 dry=%d sea=%d lake=%d river=%d%n",
                sample.chunks(), seconds, counts[DRY], counts[SEA], counts[LAKE], counts[RIVER]));
        sb.append("riverEnabled=").append(terrain.riversEnabled()).append(" horizontalScale=")
                .append(config.params().horizontalScale()).append(" seaLevel=").append(terrain.seaLevel()).append('\n');
        sb.append("columnsSHA256=").append(sample.columnsSha()).append('\n');
        sb.append("NOT_VERIFIED=Forge方块放置/洞穴/装饰/流体tick；离线整块水柱判据不替代运行后验收\n");
        sb.append(riverStats(sample));
        sb.append("FILE=").append(dir.resolve("production.png").toAbsolutePath()).append('\n');
        return sb.toString();
    }

    private static String riverStats(Sample sample) {
        int n = sample.n(), components = 0;
        int[] degrees = new int[5];
        boolean[] visited = new boolean[n * n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int k = 0; k < visited.length; k++) {
            if (sample.kind()[k] != RIVER) continue;
            int x = k / n, z = k % n;
            int[] neighbours = {x > 0 ? k - n : -1, x < n - 1 ? k + n : -1,
                    z > 0 ? k - 1 : -1, z < n - 1 ? k + 1 : -1};
            int degree = 0;
            for (int p : neighbours) if (p >= 0 && sample.kind()[p] == RIVER) degree++;
            degrees[degree]++;
            if (visited[k]) continue;
            visited[k] = true; components++; queue.add(k);
            while (!queue.isEmpty()) {
                int p = queue.removeFirst(), i = p / n, j = p % n;
                int[] next = {i > 0 ? p - n : -1, i < n - 1 ? p + n : -1,
                        j > 0 ? p - 1 : -1, j < n - 1 ? p + 1 : -1};
                for (int q : next) if (q >= 0 && !visited[q] && sample.kind()[q] == RIVER) {
                    visited[q] = true; queue.add(q);
                }
            }
        }
        return "river4Connected=" + components + " degree[0..4]=" + java.util.Arrays.toString(degrees)
                + "（像素统计，不等于河网拓扑验收）\n";
    }

    private static void selfTest() {
        Cell c = new Cell();
        c.height = 80.2; c.riverSurfaceY = 81.2;
        expect(c, true, 63, DRY, "no fill flag is dry despite available water level");
        c.riverType = 1;
        expect(c, true, 63, RIVER, "whole river water block");
        c.riverSurfaceY = 80.9;
        expect(c, true, 63, DRY, "same floor has no water block");
        c.riverSurfaceY = c.height;
        expect(c, true, 63, DRY, "equal surface is dry");
        c.isLake = true;
        expect(c, true, 63, DRY, "lake flag alone cannot create water");
        c.riverSurfaceY = 81;
        expect(c, true, 63, LAKE, "wet lake");
        c.isLake = false; c.riverSurfaceY = 80.9; c.riverLipY = 82;
        expect(c, true, 63, RIVER, "waterfall lip above ordinary surface");
        c.riverType = 0; c.height = 62.9;
        expect(c, true, 63, SEA, "ocean independent of river flag");
        c.height = 63;
        expect(c, true, 63, DRY, "sea-level floor is dry");
        c.height = 62.9; c.e = 1;
        expect(c, false, 63, DRY, "hydrology-off ocean uses isWater");
        c.e = -1;
        expect(c, false, 63, SEA, "hydrology-off vanilla ocean");
        c.height = 319; c.riverType = 1; c.riverSurfaceY = 321;
        expect(c, true, 63, DRY, "above world ceiling is not water");
        c.height = -64.2; c.riverSurfaceY = -64; c.riverLipY = 0;
        expect(c, true, -100, RIVER, "positive lip retains waterfall water");
        c.riverLipY = -64;
        expect(c, true, -100, DRY, "bedrock is not a water block");
        checkPlacementOracle();
        System.out.println("PASS ProductionViewProbe self-test checks=14 oracle=2000");
    }

    private static void checkPlacementOracle() {
        var random = new java.util.Random(741238);
        for (int i = 0; i < 2000; i++) {
            Cell c = new Cell();
            c.height = random.nextDouble() * 450 - 100;
            c.riverSurfaceY = c.height + random.nextDouble() * 10 - 3;
            c.riverLipY = c.riverSurfaceY + random.nextDouble() * 8 - 4;
            c.riverType = (byte) random.nextInt(2);
            c.isLake = random.nextBoolean(); c.e = random.nextBoolean() ? -1 : 1;
            boolean enabled = random.nextBoolean();
            double sea = random.nextDouble() * 160 - 80;
            boolean river = enabled && c.riverType != 0 && c.riverSurfaceY > c.height;
            boolean ocean = enabled && c.height < sea;
            double top = river ? c.riverSurfaceY : 63;
            if (ocean) top = Math.max(top, sea);
            int lip = river && c.riverLipY > top ? (int) Math.floor(c.riverLipY) : (int) Math.floor(top);
            int surface = (int) Math.floor(c.height);
            boolean water = enabled ? river || ocean : c.isWater();
            boolean placed = false;
            // 独立逐层执行 Generator 的放置分支，不调用 waterKind 的区间公式。
            for (int y = -64; y < 320; y++) {
                if (y == -64 || y <= surface) continue;
                if (water && y <= Math.max((int) Math.floor(top), lip)) placed = true;
                else break;
            }
            int expected = !placed ? DRY : river ? (c.isLake ? LAKE : RIVER) : SEA;
            expect(c, enabled, sea, expected, "placement loop oracle case " + i);
        }
    }

    private static void expect(Cell cell, boolean enabled, double sea, int expected, String message) {
        int actual = waterKind(cell, enabled, sea);
        if (actual != expected) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
