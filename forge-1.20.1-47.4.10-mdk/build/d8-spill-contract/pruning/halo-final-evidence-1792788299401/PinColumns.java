package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class PinColumns {
    private static final int[] CENTERS = {-318, -382, -220, -708, -740};

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        var cfg = new com.electronwill.nightconfig.toml.TomlParser().parse(Files.readString(Path.of(args[1])));
        GeoGenesisConfig.SPEC.setConfig(cfg);
        var params = GeoGenesisConfig.INSTANCE.buildParams();
        var terrain = new GeoGenesisTerrain(new CellGenerator(params, -64, 320));
        terrain.seed(9139912035078620160L);
        Files.writeString(out.resolve("params.txt"), params.toString());
        StringBuilder sources = new StringBuilder();
        for (Class<?> c : new Class<?>[]{DeterministicSkeleton.class, ModelHydrologyProvider.class,
                GeoGenesisTerrain.class, OrderedSpillSearch.class}) {
            try (var in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
                sources.append(c.getName()).append(' ').append(c.getProtectionDomain().getCodeSource().getLocation())
                        .append(' ').append(java.util.HexFormat.of().formatHex(java.security.MessageDigest
                        .getInstance("SHA-256").digest(in.readAllBytes()))).append('\n');
            }
        }
        Files.writeString(out.resolve("sources.txt"), sources.toString());
        Map<String, Cell[]> cache = new HashMap<>();
        try (var w = new PrintWriter(Files.newBufferedWriter(out.resolve("columns.csv")))) {
            w.println("patch,x,z,ground,surface,lip,type,lake,kind,blocks");
            for (int center : CENTERS) for (int x = center - 12; x <= center + 12; x++) {
                for (int z = 84; z <= 108; z++) {
                    int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                    Cell[] cells = cache.computeIfAbsent(cx + ":" + cz, key -> terrain.getChunkCells(cx, cz));
                    Cell c = cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                    int kind = ProductionViewProbe.waterKind(c, terrain.riversEnabled(), terrain.seaLevel());
                    boolean river = terrain.riversEnabled() && c.riverType != 0 && c.riverSurfaceY > c.height;
                    boolean ocean = terrain.riversEnabled() && c.height < terrain.seaLevel();
                    double top = river ? Math.max(c.riverSurfaceY, c.riverLipY) : 63;
                    if (ocean) top = Math.max(top, terrain.seaLevel());
                    int blocks = 0;
                    for (int y = -63; y < 320; y++) if ((river || ocean)
                            && y > Math.floor(c.height) && y <= Math.floor(top)) blocks++;
                    if ((kind != 0) != (blocks > 0)) throw new AssertionError("water oracle");
                    w.printf("%d,%d,%d,%.17g,%.17g,%.17g,%d,%b,%d,%d%n",
                            center, x, z, c.height, c.riverSurfaceY, c.riverLipY, c.riverType, c.isLake, kind, blocks);
                }
                w.flush();
            }
        }
        Files.writeString(out.resolve("summary.txt"), "patches=5 columns=3125 chunks=" + cache.size()
                + " fingerprint=" + GeoGenesisConfig.configFingerprint() + "\n");
        System.out.println("PIN_COLUMNS PASS patches=5 columns=3125 chunks=" + cache.size());
        System.exit(0);
    }
}
