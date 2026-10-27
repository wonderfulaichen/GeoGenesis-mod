package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** 只采生产getChunkCells；四处存档错层回归，FAIL非零。不修改存档或用户TOML。 */
public final class LakeColumnSeamProbe {
    private static int checks, failures;
    private record Point(int x, int z) { }
    private record Seam(String name, Point a, Point b) { }
    private static final Seam[] SEAMS = {
            new Seam("z-512", new Point(-300, -513), new Point(-300, -512)),
            new Seam("x-512-north", new Point(-513, -280), new Point(-512, -280)),
            new Seam("x-512-south", new Point(-513, -170), new Point(-512, -170)),
            new Seam("x-1024", new Point(-1025, -440), new Point(-1024, -440))
    };

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/lake-step-fix/after");
        boolean baseline = args.length > 1 && args[1].equals("--baseline");
        Files.createDirectories(out);
        Path copy = out.resolve("config.toml");
        Files.copy(Path.of("run/config/geogenesis-common.toml"), copy, StandardCopyOption.REPLACE_EXISTING);
        var cfg = CommentedFileConfig.of(copy, TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams tp = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(9139912035078620160L);
        Map<Long, Cell[]> cache = new HashMap<>();
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("seams.csv"))) {
            csv.write("seam,x,z,ground,surface,lip,lake,kind,waterBlocks\n");
            for (Seam seam : SEAMS) checkSeam(seam, terrain, cache, csv);
        }
        if (java.util.Arrays.asList(args).contains("--patches")) {
            writePatches(out, terrain, cache);
        }
        String result = "LakeColumnSeamProbe checks=" + checks + " FAIL=" + failures
                + " scope=production columns only; no Forge fluid tick\n";
        Files.writeString(out.resolve("summary.txt"), result);
        System.out.print(result);
        if (!baseline && failures != 0) System.exit(1);
    }

    private static void checkSeam(Seam seam, GeoGenesisTerrain terrain, Map<Long, Cell[]> cache,
                                  BufferedWriter csv) throws Exception {
        Cell a = cell(terrain, cache, seam.a()), b = cell(terrain, cache, seam.b());
        write(csv, seam.name(), seam.a(), a, terrain);
        write(csv, seam.name(), seam.b(), b, terrain);
        expect(seam.name() + " both genuine wet lake columns", a.isLake && b.isLake
                && ProductionViewProbe.waterKind(a, true, terrain.seaLevel()) == 2
                && ProductionViewProbe.waterKind(b, true, terrain.seaLevel()) == 2);
        expect(seam.name() + " same lake plane", Math.abs(a.riverSurfaceY - b.riverSurfaceY) < 1e-9);
        expect(seam.name() + " same integer water top",
                Math.floor(a.riverSurfaceY) == Math.floor(b.riverSurfaceY));
        System.out.printf(java.util.Locale.ROOT, "%s (%d,%d) S=%.12f vs (%d,%d) S=%.12f%n",
                seam.name(), seam.a().x(), seam.a().z(), a.riverSurfaceY,
                seam.b().x(), seam.b().z(), b.riverSurfaceY);
    }

    private static void writePatches(Path out, GeoGenesisTerrain terrain, Map<Long, Cell[]> cache)
            throws Exception {
        int[][] boxes = {{-380, -170, -570, -450}, {-580, -440, -330, -120}};
        try (BufferedWriter csv = Files.newBufferedWriter(out.resolve("patches.csv"))) {
            csv.write("patch,x,z,ground,surface,lip,lake,kind,waterBlocks\n");
            for (int p = 0; p < boxes.length; p++) {
                int[] b = boxes[p];
                for (int x = b[0]; x <= b[1]; x++) for (int z = b[2]; z <= b[3]; z++) {
                    Point at = new Point(x, z);
                    write(csv, "patch" + p, at, cell(terrain, cache, at), terrain);
                }
            }
        }
        try (BufferedWriter sink = new BufferedWriter(java.io.Writer.nullWriter())) {
            for (int x = -346; x <= -206; x++) {
                checkSeam(new Seam("z-line-" + x, new Point(x, -513), new Point(x, -512)),
                        terrain, cache, sink);
            }
            for (int z = -330; z <= -120; z++) {
                Point a = new Point(-513, z), b = new Point(-512, z);
                if (cell(terrain, cache, a).isLake && cell(terrain, cache, b).isLake) {
                    checkSeam(new Seam("x-line-" + z, a, b), terrain, cache, sink);
                }
            }
        }
        // 邻近的不同湖面不能被接缝修复合并成一个水位。
        double[][] controls = {{-558, -140, 167.35329416159294},
                {-445, -121, 170.96714450602482}, {-571, -129, 172.46784207434274},
                {-171, -484, 192.4810052548375}};
        for (double[] control : controls) {
            Cell c = cell(terrain, cache, new Point((int) control[0], (int) control[1]));
            expect("independent lake retains its own plane", c.isLake
                    && Math.abs(c.riverSurfaceY - control[2]) <= 1e-9);
        }
        Cell dry = cell(terrain, cache, new Point(-380, -570));
        expect("dry control remains dry", ProductionViewProbe.waterKind(dry, true, terrain.seaLevel()) == 0);
    }

    private static Cell cell(GeoGenesisTerrain t, Map<Long, Cell[]> cache, Point p) {
        int cx = Math.floorDiv(p.x(), 16), cz = Math.floorDiv(p.z(), 16);
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        Cell[] cells = cache.computeIfAbsent(key, k -> t.getChunkCells(cx, cz));
        return cells[Math.floorMod(p.x(), 16) * 16 + Math.floorMod(p.z(), 16)];
    }

    private static void write(BufferedWriter w, String seam, Point p, Cell c,
                              GeoGenesisTerrain terrain) throws Exception {
        w.write(seam + "," + p.x() + "," + p.z() + "," + c.height + "," + c.riverSurfaceY
                + "," + c.riverLipY + "," + c.isLake + ","
                + ProductionViewProbe.waterKind(c, true, terrain.seaLevel()) + ","
                + Math.max(0, Math.floor(c.riverSurfaceY) - Math.floor(c.height)) + "\n");
    }

    private static void expect(String label, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL " + label); }
    }
}
