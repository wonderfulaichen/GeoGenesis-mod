package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.generator.BiomeColumnCache;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.cave.CaveBiomeSelector;
import com.geogenesis.worldgen.cave.CaveShape;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import java.nio.file.*;
import java.util.*;

/** MC-dependent, no server/save/game. Fixed sequence uses production exact-P terrain and
 * classifiers with default cave config (not run/config TOML). Does NOT exercise
 * getNoiseBiome, lifecycle wiring, Holder resolution or a real server registry.
 * Does not invoke Bootstrap. On the current mapped Forge jar, Biomes initializes
 * Registries/BuiltInRegistries transitively and plain java -cp fails "Not bootstrapped".
 * Run in an already bootstrapped validation runtime; do not fake registry initialization. */
public final class BiomeExactPSequenceProbe {
    private record Point(int x, int y, int z) { }
    private static ResourceKey<Biome> cave(Cell c, Point p, ResourceKey<Biome> surface) {
        if (!CaveShape.config().caveBiomes || !CaveShape.isEnabled()) return surface;
        int sy = (int)Math.floor(c.height), wy = QuartPos.toBlock(p.y);
        if (sy - wy < Math.max(CaveBiomeSelector.MIN_DEPTH, CaveShape.surfaceLid())
                || sy - wy > CaveShape.depthMax()) return surface;
        return switch (CaveBiomeSelector.select(QuartPos.toBlock(p.x), wy, QuartPos.toBlock(p.z),
                sy, -64, CaveShape.lithoFactor(c.rockTypeId), c.biomeType)) {
            case DRIPSTONE -> Biomes.DRIPSTONE_CAVES;
            case LUSH -> Biomes.LUSH_CAVES;
            case NONE -> surface;
        };
    }
    private static BiomeColumnCache.Column<ResourceKey<Biome>> sample(GeoGenesisTerrain t, Point p) {
        Cell c = t.sampleCellLight(QuartPos.toBlock(p.x), QuartPos.toBlock(p.z));
        ResourceKey<Biome> surface = BiomeClassifier.pickKey(c);
        return new BiomeColumnCache.Column<>(surface, 0, cave(c, p, surface));
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); // No Forge/registry bootstrap: test ResourceKey-only initialization.
        Path out = Path.of(args.length == 0 ? "build/biome-concurrency-test/exact-p.tsv" : args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String> lines = new ArrayList<>(); lines.add("seed\tx\ty\tz\told\tnew");
        long[] seeds = args.length > 1
                ? Arrays.stream(Arrays.copyOfRange(args, 1, args.length)).mapToLong(Long::parseLong).toArray()
                : new long[]{9139912035078620160L, 0};
        for (long seed : seeds) sequence(seed, lines);
        Files.write(out, lines);
        System.out.println("PASS: exact-P serial ResourceKey sequence " + out + " rows=" + (lines.size()-1));
    }
    private static void sequence(long seed, List<String> lines) {
        GeoGenesisTerrain t = new GeoGenesisTerrain(new CellGenerator(TerrainParams.defaults(), -64, 320, true));
        t.seed(seed);
        CaveShape.setSeed(seed);
        CaveBiomeSelector.setSeed(seed);
        long[] keys = new long[65536];
        @SuppressWarnings("unchecked") ResourceKey<Biome>[] values = new ResourceKey[65536];
        var cache = new BiomeColumnCache<ResourceKey<Biome>>();
        List<Point> points = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            points.add(new Point(x, -8, z)); points.add(new Point(x, 24, z));
            points.add(new Point(x, -8, z));
        }
        long start = System.nanoTime(); int misses = 0;
        for (Point p : points) {
            int slot = BiomeColumnCache.slot(p.x, p.z); long key = (long)p.x << 32 | (p.z & 0xffffffffL);
            ResourceKey<Biome> expected;
            if (keys[slot] == key && values[slot] != null) expected = values[slot];
            else {
                var col = sample(t, p); misses++; expected = col.result();
                keys[slot] = key; values[slot] = col.surface();
            }
            ResourceKey<Biome> actual = cache.query(p.x, p.z, () -> true, () -> sample(t, p), null);
            if (!Objects.equals(expected, actual)) throw new AssertionError("seed=" + seed + " point=" + p);
            lines.add(seed + "\t" + p.x + "\t" + p.y + "\t" + p.z + "\t" + expected.location() + "\t" + actual.location());
        }
        System.out.printf("seed=%d queries=%d coldColumns=%d pairedElapsedMs=%.1f%n",
                seed, points.size(), misses, (System.nanoTime()-start)/1e6);
        // Engine is private to this seed; no global Generator or world lifecycle is initialized.
    }
}
