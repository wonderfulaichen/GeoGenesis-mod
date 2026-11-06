package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Read-only bounded production evidence. Collection is not a quality PASS. */
public final class LakeFullAuditProbe {
    private static final Gson JSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private LakeFullAuditProbe() { }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams tp = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY(), true);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(args.length > 7 ? Long.parseLong(args[7]) : 9139912035078620160L);
        try {
            int bx = args.length > 3 ? Integer.parseInt(args[3]) : 180;
            int bz = args.length > 4 ? Integer.parseInt(args[4]) : -1253;
            terrain.getChunkCells(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
            Object provider = field(field(terrain, "hydrologyExperiment"), "modelProvider");
            Object pipe = invoke(provider, "pipeForProbe", bx, bz);
            Object rd = invoke(provider, "regionFor", bx, bz);
            dump(out, provider, rd, pipe);
            if (args[2].equals("neighbors")) {
                for (int[] pos : new int[][] {{-1, -3}, {0, -2}, {0, -4}, {1, -4}}) {
                    int x = pos[0] * 512 + 256, z = pos[1] * 512 + 256;
                    Path sub = out.resolve(pos[0] + "_" + pos[1]);
                    Files.createDirectories(sub);
                    Object nr = invoke(provider, "regionFor", x, z);
                    dump(sub, provider, nr, invoke(provider, "pipeForProbe", x, z));
                }
                return;
            }
            if (args[2].equals("model")) return;
            collect(out, terrain, gen, rd, pipe, args);
            if(args.length>8 && args[8].equals("repeat")) repeat(out,terrain,provider,args);
        } finally {
            gen.closeProcessErosion();
            cfg.close();
        }
    }

    private static void dump(Path out, Object provider, Object rd, Object pipe) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String name : List.of("originX", "originZ", "skel", "shapedPolys", "drainage"))
            m.put(name, invoke(rd, name));
        m.put("pipe", pipe);
        Object index = invoke(rd, "skeletonSeg");
        if (index != null) {
            Map<String, Object> geometry = new LinkedHashMap<>();
            for (String name : List.of("seg", "segmentPoly", "surface", "widthLimits", "nodeDepths"))
                geometry.put(name, field(index, name));
            geometry.put("provenance", "production internal index; replay is not internal carving or public resample");
            m.put("segmentIndexEvidence", geometry);
        }
        int rx = Math.floorDiv((int) invoke(rd, "originX") + 96, 512);
        int rz = Math.floorDiv((int) invoke(rd, "originZ") + 96, 512);
        for (String name : List.of("finalPortsForProbe", "pass1PortsForProbe", "incomingDiagForProbe",
                "wireAssemblyRejectsForProbe")) m.put(name, invoke(provider, name, rx, rz));
        m.put("jvm", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        m.put("classpath", System.getProperty("java.class.path"));
        m.put("providerSource", provider.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
        Files.writeString(out.resolve("model.json"), JSON.toJson(m));
        System.out.println("COLLECTED model " + m.get("originX") + "," + m.get("originZ"));
    }

    private static void collect(Path out, GeoGenesisTerrain t, CellGenerator gen, Object rd,
                                Object pipe, String[] args) throws Exception {
        int ox = (int) invoke(rd, "originX"), oz = (int) invoke(rd, "originZ");
        int n = (int) invoke(pipe, "nx"), step = (int) invoke(pipe, "cellBlocks");
        Object lr = invoke(pipe, "lakeResult");
        int[] ids = (int[]) invoke(lr, "basinId");
        int target = Integer.parseInt(args[5]);
        TreeSet<String> chunks = new TreeSet<>();
        for (int k = 0; k < ids.length; k++) if (target >= 0 && ids[k] == target) {
            int x = ox + k / n * step, z = oz + k % n * step;
            for (int a = Math.floorDiv(x - step - 3, 16); a <= Math.floorDiv(x + step + 3, 16); a++)
                for (int b = Math.floorDiv(z - step - 3, 16); b <= Math.floorDiv(z + step + 3, 16); b++)
                    chunks.add(a + "," + b);
        }
        if (args.length > 6 && !args[6].isBlank()) for (String line : Files.readAllLines(Path.of(args[6])))
            if (!line.isBlank()) chunks.add(line.trim());
        Files.write(out.resolve("requested-chunks.txt"), chunks);
        if (chunks.size() > 450) throw new IllegalStateException("bounded footprint budget exceeded: " + chunks.size());
        Method column = method(ScreenshotWaterGapProbe.class, "column", 5);
        try (var writer = Files.newBufferedWriter(out.resolve("columns.jsonl"))) {
            int count = 0;
            for (String chunk : chunks) {
                String[] a = chunk.split(",");
                int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]);
                Cell[] cells = t.getChunkCells(cx, cz);
                int[] tops = WaterSurfaceUnifier.unifiedTops(cells, t.seaLevel(), t.riversEnabled());
                Cell[] p = new Cell[256];
                double hs = gen.params().horizontalScale();
                for (int i = 0; i < 256; i++) {
                    int x = cx * 16 + i / 16, z = cz * 16 + i % 16;
                    p[i] = gen.sample(hs > 0.01 && hs != 1 ? x / hs : x, hs > 0.01 && hs != 1 ? z / hs : z);
                }
                gen.extractFromTile(p, cx, cz);
                for (int i = 0; i < 256; i++) {
                    Object row = column.invoke(null, cells[i], tops[i], t, cx * 16 + i / 16, cz * 16 + i % 16);
                    writeRow(writer, row, p[i].height);
                }
                writer.flush();
                System.out.println("COLLECTED " + (++count) + "/" + chunks.size() + " chunk " + chunk);
            }
        }
        Files.writeString(out.resolve("collection.json"), JSON.toJson(Map.of("status", "COLLECTED",
                "quality", "NOT_ASSESSED", "chunks", chunks.size(), "basinIdLocal", target)));
        Object provider = field(field(t, "hydrologyExperiment"), "modelProvider");
        try {
            Files.writeString(out.resolve("morphology.json"), JSON.toJson(field(provider, "inletPlans")));
        } catch (NoSuchFieldException baselineWithoutMorphology) {
            Files.writeString(out.resolve("morphology.json"), "{\"status\":\"BASELINE_NO_MORPHOLOGY\"}");
        }
    }

    private static void repeat(Path out, GeoGenesisTerrain t, Object provider, String[] args) throws Exception {
        List<String> chunks=new ArrayList<>(Files.readAllLines(out.resolve("requested-chunks.txt")));
        java.util.Collections.shuffle(chunks,new java.util.Random(61237));
        Map<String,String> before=new LinkedHashMap<>();
        Method column=method(ScreenshotWaterGapProbe.class,"column",5);
        for(String chunk:chunks) before.put(chunk,chunkJson(t,chunk,column));
        ((Map<?,?>)field(t,"cache")).clear();
        ((Map<?,?>)field(provider,"inletPlans")).clear();
        java.util.Collections.reverse(chunks);
        int checked=0;
        long start=System.nanoTime();
        for(String chunk:chunks) {
            if(!before.get(chunk).equals(chunkJson(t,chunk,column)))
                throw new AssertionError("rebuild/order mismatch "+chunk);
            checked++;
        }
        Files.writeString(out.resolve("repeat.json"),JSON.toJson(Map.of("chunks",checked,
                "columns",checked*256,"elapsedMs",(System.nanoTime()-start)/1e6,
                "scope","chunk cache + basin morphology cache cleared; provider regions retained; shuffled/reverse incl owner-region boundaries",
                "status","PASS")));
    }

    private static String chunkJson(GeoGenesisTerrain t,String chunk,Method column) throws Exception {
        String[] a=chunk.split(",");
        int cx=Integer.parseInt(a[0]),cz=Integer.parseInt(a[1]);
        Cell[] cells=t.getChunkCells(cx,cz);
        int[] tops=WaterSurfaceUnifier.unifiedTops(cells,t.seaLevel(),t.riversEnabled());
        List<Object> rows=new ArrayList<>();
        for(int i=0;i<256;i++) rows.add(column.invoke(null,cells[i],tops[i],t,cx*16+i/16,cz*16+i%16));
        return JSON.toJson(rows);
    }

    private static void writeRow(java.io.Writer writer, Object row, double p) throws Exception {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("column", row);
        wrapped.put("preHydrologyP", p);
        writer.write(JSON.toJson(wrapped));
        writer.write("\n");
    }

    private static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Method method(Class<?> type, String name, int count) throws NoSuchMethodException {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == count) {
            m.setAccessible(true);
            return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object invoke(Object o, String name, Object... args) throws Exception {
        return method(o.getClass(), name, args.length).invoke(o, args);
    }
}
