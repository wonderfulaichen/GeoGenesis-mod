package com.geogenesis.worldgen.terrain;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.GeoGenesisWorldData;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** P1 production Cell path; no candidate/Gallery classes and no Minecraft world writes. */
public final class P1CellRenderProbe {
    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401;
    private static final String EXPECTED =
            "bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load();
            require(GeoGenesisConfig.SPEC.isCorrect(cfg), "frozen config invalid");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
            require(p.horizontalScale() == 2.0, "HS must remain 2");
            System.out.println("frozenParams=" + p);
            CellGenerator gen = new CellGenerator(p, -64, 320, true);
            GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
            terrain.seed(SEED);
            require(terrain.usesMorphBase() && !terrain.riversEnabled(), "P1 water gate");
            render(terrain, p, out);
            checkChunks(terrain, gen, p, out);
            checkWorldMarkers();
            CellGenerator old = new CellGenerator(p, -64, 320);
            require(!old.usesMorphBase(), "legacy constructor changed");
            old.seed(12345L);
            require(Double.isFinite(old.sample(0.5, 0.5).height), "legacy sampling");
            CellGenerator other = new CellGenerator(p, -64, 320, true);
            other.seed(12345L);
            require(Double.isFinite(other.sample(10.5, -11.5).height), "noncanonical Cell");
            System.out.println("P1 CELL PASS: HS=2, no external erosion/hydrology, markers roundtrip");
        }
    }

    private static void render(GeoGenesisTerrain terrain, TerrainParams p, Path out) throws Exception {
        Path file = out.resolve("p1-cell-heights.f64be");
        try (DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = -3642.5 + i * 5.0, z = -3365.5 + j * 5.0;
                    Cell c = terrain.sampleCellCoarse(x * p.horizontalScale(), z * p.horizontalScale());
                    require(Double.isFinite(c.height), "nonfinite height");
                    require(c.eClimate == c.e && c.typeWeights.length == TerrainClass.COUNT, "Cell metadata");
                    require(c.terrainType.isOcean() == (c.e < 0), "sea classification");
                    double sum = 0;
                    for (double w : c.typeWeights) { require(w >= 0 && Double.isFinite(w), "weight"); sum += w; }
                    require(Math.abs(sum - 1) < 1e-6, "weight sum");
                    s.writeDouble(c.height);
                }
                if (j % 200 == 0) System.out.println("P1 row=" + j);
            }
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        System.out.println("productionCellSha=" + sha);
        require(sha.equals(EXPECTED), "P1 height drift from verified P0");
    }

    private static void checkChunks(GeoGenesisTerrain terrain, CellGenerator gen, TerrainParams p, Path out) throws Exception {
        MessageDigest metadata = MessageDigest.getInstance("SHA-256");
        MorphTerrainSampler reference = new MorphTerrainSampler(SEED, p, -64, 320);
        int count = 0;
        float maxSlope = 0;
        for (int[] pos : new int[][]{{0, 0}, {-401, -233}, {207, 286}, {420, 33}}) {
            Cell[] cells = terrain.getChunkCells(pos[0], pos[1]);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                double bx = pos[0] * 16 + x, bz = pos[1] * 16 + z;
                double wx = bx / p.horizontalScale(), wz = bz / p.horizontalScale();
                Cell c = cells[x * 16 + z];
                require(c.height == reference.height(wx, wz), "complete chunk height drift");
                require(c.height == terrain.sampleHeightNonBlocking(bx, bz), "nonblocking height drift");
                require(gen.terrainEQuick(wx, wz) == c.e && c.eClimate == c.e, "quick/eClimate drift");
                require(gen.erosionDeltaE(wx, wz) == 0 && gen.erodedHeight(wx, wz) == c.height, "external delta");
                require(c.riverType == 0 && !c.isLake && !c.lakeMask, "hydrology leaked");
                require(Float.isFinite(c.gradient) && c.gradient >= 0, "gradient");
                maxSlope = Math.max(maxSlope, c.gradient);
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(256);
                for (double v : new double[]{c.height, c.e, c.eClimate, c.eLand, c.continent,
                        c.temperature, c.humidity, c.precipitation, c.snowLineE, c.rockTilt, c.oasisNoise}) b.putDouble(v);
                b.putFloat(c.gradient);
                for (int v : new int[]{c.terrainType.ordinal(), c.variantTerrain.ordinal(), c.biomeType.ordinal(),
                        c.rockLayer, c.rockTypeId, c.rockSeqPacked, c.isSnow ? 1 : 0}) b.putInt(v);
                metadata.update(b.array());
                for (double w : c.typeWeights) metadata.update(java.nio.ByteBuffer.allocate(8).putDouble(w).array());
                count++;
            }
        }
        require(maxSlope > 0, "missing slope");
        String hash = HexFormat.of().formatHex(metadata.digest());
        Files.writeString(out.resolve("cell-metadata.sha256"), hash + "\n");
        System.out.println("completeChunkCells=" + count + " bitDifferent=0 maxSlope=" + maxSlope);
        System.out.println("cellMetadataSha=" + hash);
    }

    private static void checkWorldMarkers() {
        GeoGenesisWorldData fresh = new GeoGenesisWorldData();
        fresh.initializeAlgorithm(true);
        require(fresh.usesMorphBase(), "new world not morph");
        GeoGenesisWorldData reopened = GeoGenesisWorldData.load(fresh.save(new net.minecraft.nbt.CompoundTag()));
        reopened.initializeAlgorithm(false);
        require(reopened.usesMorphBase(), "new world reopen switched algorithm");
        GeoGenesisWorldData legacy = GeoGenesisWorldData.load(new net.minecraft.nbt.CompoundTag());
        legacy.initializeAlgorithm(true);
        require(!legacy.usesMorphBase(), "unmarked save upgraded");
        GeoGenesisWorldData oldNoData = new GeoGenesisWorldData();
        oldNoData.initializeAlgorithm(false);
        require(!oldNoData.usesMorphBase(), "existing world without saved data upgraded");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
