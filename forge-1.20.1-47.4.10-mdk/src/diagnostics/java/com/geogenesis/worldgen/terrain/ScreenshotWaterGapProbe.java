package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded screenshot evidence; no gap-quality PASS, no Minecraft or save access. */
public final class ScreenshotWaterGapProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
    private ScreenshotWaterGapProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path config = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectory(out);
        CommentedFileConfig cfg = CommentedFileConfig.of(config, TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams tp = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY(), true);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(9139912035078620160L);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> provenance = new ArrayList<>();
        provenance.add("jvm=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        provenance.add("cp=" + System.getProperty("java.class.path"));
        provenance.add("params=" + tp);
        provenance.add("plan=read-only re-evaluation via production foreignCtx/decideColumn, NOT intercepted invocation");
        provenance.add("publicPlan=calculateHydrologyChunk resampling, NOT actual internal carving list");
        for (Class<?> c : List.of(ScreenshotWaterGapProbe.class, CellGenerator.class, GeoGenesisTerrain.class,
                WaterSurfaceUnifier.class, com.geogenesis.worldgen.process.ProcessRuntime.class,
                com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider.class)) {
            provenance.add(c.getName() + "=" + c.getProtectionDomain().getCodeSource().getLocation());
        }
        Files.write(out.resolve("provenance.txt"), provenance);
        int mismatch = 0;
        try {
            int minCx = args.length > 2 ? Integer.parseInt(args[2]) : 8;
            int maxCx = args.length > 3 ? Integer.parseInt(args[3]) : 11;
            int minCz = args.length > 4 ? Integer.parseInt(args[4]) : -80;
            int maxCz = args.length > 5 ? Integer.parseInt(args[5]) : -78;
            for (int cx = minCx; cx <= maxCx; cx++) for (int cz = minCz; cz <= maxCz; cz++) {
                Cell[] cells = terrain.getChunkCells(cx, cz);
                int[] unified = WaterSurfaceUnifier.unifiedTops(cells, terrain.seaLevel(), terrain.riversEnabled());
                Object provider = field(field(terrain, "hydrologyExperiment"), "modelProvider");
                Object ctx = invoke(provider, "foreignCtx", cx, cz);
                Object input = field(provider, "input");
                boolean connector = ((com.geogenesis.worldgen.hydrology.model.ModelTerrainInput) input).integerWaterConnector();
                Object plans = plans(provider, ctx, cx, cz, connector);
                captureGeometry(out, ctx);
                var publicColumns = terrain.calculateHydrologyChunk(cx, cz).carvedColumns();
                Map<Long, Object> publicByPos = new LinkedHashMap<>();
                for (var col : publicColumns) publicByPos.put(key(col.blockX(), col.blockZ()), col);
                Cell[] pCells = new Cell[256];
                double[] b = new double[256];
                double hs = tp.horizontalScale();
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int i = lx * 16 + lz, x = cx * 16 + lx, z = cz * 16 + lz;
                    double wx = hs > 0.01 && hs != 1 ? x / hs : x;
                    double wz = hs > 0.01 && hs != 1 ? z / hs : z;
                    pCells[i] = gen.sample(wx, wz);
                    b[i] = pCells[i].height;
                }
                gen.extractFromTile(pCells, cx, cz);
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int i = lx * 16 + lz, x = cx * 16 + lx, z = cz * 16 + lz;
                    if (args.length <= 2 && z > -1233) continue;
                    Map<String, Object> row = column(cells[i], unified[i], terrain, x, z);
                    row.put("baseB", b[i]); row.put("preHydrologyP", pCells[i].height);
                    Object plan = Array.get(plans, i);
                    Map<String, Object> pm = record(plan);
                    pm.forEach((k, v) -> row.put("replay_" + k, v));
                    record(publicByPos.get(key(x, z))).forEach((k, v) -> row.put("public_" + k, v));
                    coarse(row, ctx, x, z);
                    certification(row, provider, ctx, x, z);
                    double expected = predicted(pm, pCells[i].height, b[i], cells[i], terrain.seaLevel());
                    row.put("replayExpectedHeight", expected);
                    boolean equal = Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(cells[i].height);
                    row.put("replayHeightBitsEqual", equal);
                    if (!equal) mismatch++;
                    rows.add(row);
                }
                System.out.printf("COLLECTED chunk=(%d,%d) rows=%d%n", cx, cz, rows.size());
                Files.writeString(out.resolve("columns.json"), JSON.toJson(rows));
            }
            csv(rows, out.resolve("columns.csv"));
            Files.writeString(out.resolve("collection.json"), JSON.toJson(Map.of(
                    "status", "COLLECTED", "chunks", (maxCx - minCx + 1) * (maxCz - minCz + 1), "rows", rows.size(),
                    "replayHeightBitMismatches", mismatch, "noBlockOrFluidTickVerification", true)));
            System.out.println("COLLECTED; replayHeightBitMismatches=" + mismatch + "; not gap-quality PASS");
        } finally {
            gen.closeProcessErosion();
            cfg.close();
        }
        if ((args.length <= 2 && rows.size() != 3072) || rows.isEmpty() || mismatch != 0) System.exit(2);
    }

    private static Object plans(Object provider, Object ctx, int cx, int cz, boolean connector) throws Exception {
        Method decide = method(provider.getClass(), "decideColumn", 3);
        Object plans = Array.newInstance(decide.getReturnType(), 256);
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++)
            Array.set(plans, lx * 16 + lz, decide.invoke(provider, ctx, cx * 16 + lx, cz * 16 + lz));
        if (connector) invoke(provider, "applyIntegerWaterConnector", ctx, plans);
        return plans;
    }

    private static Map<String, Object> column(Cell c, int unified, GeoGenesisTerrain t, int x, int z) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("x", x); r.put("z", z); r.put("chunkX", Math.floorDiv(x, 16));
        r.put("chunkZ", Math.floorDiv(z, 16)); r.put("localX", Math.floorMod(x, 16));
        r.put("localZ", Math.floorMod(z, 16)); r.put("height", c.height);
        r.put("terrainClass", c.terrainType.name()); r.put("riverType", c.riverType);
        r.put("isLake", c.isLake); r.put("lakeMask", c.lakeMask);
        r.put("surface", c.riverSurfaceY); r.put("lip", c.riverLipY);
        boolean on = t.riversEnabled(), river = on && c.riverType != 0 && c.riverSurfaceY > c.height;
        boolean ocean = on && c.height < t.seaLevel();
        double top = river ? c.riverSurfaceY : 63.0;
        if (ocean) top = Math.max(top, t.seaLevel());
        boolean water = on ? river || ocean : c.isWater() || river;
        if (on && unified >= 0) water = true;
        int raw = (int) Math.floor(top), waterTop = raw;
        int lip = river && c.riverLipY > top ? (int) Math.floor(c.riverLipY) : waterTop;
        if (on && unified >= 0) {
            waterTop = unified;
            lip = river && c.riverLipY > unified ? (int) Math.floor(c.riverLipY) : waterTop;
        }
        int fillTop = Math.max(waterTop, lip), ground = (int) Math.floor(c.height);
        int low = Math.max(-63, ground + 1), high = Math.min(319, fillTop);
        r.put("riverWater", river); r.put("ocean", ocean); r.put("waterFlag", water);
        r.put("floorGround", ground); r.put("rawWaterTop", raw); r.put("unifiedTop", unified);
        r.put("waterTopBlock", waterTop); r.put("lipBlock", lip); r.put("fillTopBlock", fillTop);
        r.put("waterLow", low); r.put("waterHigh", high);
        r.put("waterBlocks", water ? Math.max(0, high - low + 1) : 0);
        return r;
    }

    private static double predicted(Map<String, Object> p, double h, double b, Cell c, double sea) {
        double surface = ((Number) p.get("surface")).doubleValue();
        if (Boolean.TRUE.equals(p.get("lake"))) {
            h -= Math.max(0.0, ((Number) p.get("depth")).doubleValue());
            if (h <= surface + 1e-9 && h >= Math.floor(surface) - 1e-9)
                return Math.min(surface - 0.75, Math.floor(surface) - 1e-9);
            return h;
        }
        double depth = ((Number) p.get("depth")).doubleValue();
        // Published model erosionMask=1.0; keep the exact production operation order.
        double rawDelta = h - b, delta = rawDelta * 1.0;
        double cut = depth + (rawDelta - delta);
        return h - Math.max(0.0, cut);
    }

    private static void coarse(Map<String, Object> r, Object ctx, int x, int z) throws Exception {
        Object rd = field(ctx, "rd"), pipe = field(ctx, "p");
        int ox = ((Number) invoke(rd, "originX")).intValue();
        int oz = ((Number) invoke(rd, "originZ")).intValue();
        int n = ((Number) invoke(pipe, "nx")).intValue();
        int step = ((Number) invoke(pipe, "cellBlocks")).intValue();
        int i = Math.max(0, Math.min(n - 1, (int) Math.round((x - ox) / (double) step)));
        int j = Math.max(0, Math.min(n - 1, (int) Math.round((z - oz) / (double) step)));
        r.put("coarseX", ox + i * step); r.put("coarseZ", oz + j * step);
        r.put("coarseStep", step); r.put("coarseLake", Array.get(invoke(pipe, "lake"), i * n + j));
        r.put("coarseSurface", Array.get(invoke(pipe, "waterSurface"), i * n + j));
        r.put("coarseBed", Array.get(invoke(pipe, "bed"), i * n + j));
        // Model depth is a coarse water-balance result, not block-scale carved bathymetry.
        r.put("coarseLakeDepth", Array.get(invoke(pipe, "depth"), i * n + j));
        Object hit = invoke(field(ctx, "segIndex"), "nearest", x, z);
        if (hit != null) for (String name : List.of("half", "depth", "dist", "surface"))
            r.put("localHit_" + name, field(hit, name));
    }

    /** Optional build-only interception of a replay; never infer certification from coarseLake. */
    private static void certification(Map<String, Object> row, Object provider, Object ctx, int x, int z)
            throws Exception {
        try {
            double[] c = (double[]) invoke(provider, "certificationForGap", ctx, x, z);
            row.put("certifiedLake", c[0] == 1.0);
            row.put("authoritativeLakeSurface", c[1]);
            row.put("internalSnapshotP", c[2]);
        } catch (NoSuchMethodException absent) {
            row.put("certificationAvailable", false);
        }
    }

    /** Internal immutable pass2 payloads, not public resampling or reconstructed centerlines. */
    private static void captureGeometry(Path out, Object ctx) throws Exception {
        Object rd = field(ctx, "rd"), index = field(ctx, "segIndex");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("raw", field(rd, "skel"));
        payload.put("shape", field(rd, "shapedPolys"));
        for (String name : List.of("seg", "surface", "widthLimits", "segmentPoly"))
            payload.put(name, field(index, name));
        Files.writeString(out.resolve("geometry.json"), JSON.toJson(payload));
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static Method method(Class<?> type, String name, int count) throws NoSuchMethodException {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == count) {
            m.setAccessible(true); return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Object invoke(Object o, String name, Object... args) throws Exception {
        return method(o.getClass(), name, args.length).invoke(o, args);
    }
    private static Map<String, Object> record(Object value) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var c : value.getClass().getRecordComponents()) {
            Method m = c.getAccessor(); m.setAccessible(true); result.put(c.getName(), m.invoke(value));
        }
        return result;
    }
    private static void csv(List<Map<String, Object>> rows, Path path) throws Exception {
        var keys = new java.util.LinkedHashSet<String>(); rows.forEach(r -> keys.addAll(r.keySet()));
        List<String> lines = new ArrayList<>(); lines.add(String.join(",", keys));
        for (var row : rows) {
            List<String> values = new ArrayList<>();
            for (String k : keys) values.add(String.valueOf(row.getOrDefault(k, "")));
            lines.add(String.join(",", values));
        }
        Files.write(path, lines);
    }
}
