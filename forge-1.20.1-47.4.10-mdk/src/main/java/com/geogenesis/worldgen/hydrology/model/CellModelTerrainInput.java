package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** Old application boundary; preserves sampling expressions and live config read timing. */
public final class CellModelTerrainInput implements ModelTerrainInput {
    private final CellGenerator gen;
    private final double invHs;

    public CellModelTerrainInput(CellGenerator gen) {
        this.gen = java.util.Objects.requireNonNull(gen);
        this.invHs = 1.0 / Math.max(0.01, gen.params().horizontalScale());
    }

    public CellGenerator generator() { return gen; }
    @Override public TerrainParams params() { return gen.params(); }
    @Override public double seaLevel() { return gen.seaLevel(); }
    @Override public double mappedSeaLevel() { return gen.heightCurve().seaLevelY(); }
    @Override public double baseHeightAtBlock(double bx, double bz) {
        return gen.sample(bx / gen.params().horizontalScale(), bz / gen.params().horizontalScale()).height;
    }
    @Override public double coarseBaseHeightAtBlock(double bx, double bz) {
        return gen.heightCurve().heightFromE(gen.terrainEQuick(bx * invHs, bz * invHs));
    }
    @Override public double processHeightAtBlock(double bx, double bz) {
        return gen.erodedHeight(bx * invHs, bz * invHs);
    }
    @Override public double pipelineHeightAtBlock(double bx, double bz, double hs, boolean eroded) {
        return eroded ? gen.erodedHeight(bx / hs, bz / hs) : gen.sample(bx / hs, bz / hs).height;
    }
    @Override public double marginHeightAtBlock(double bx, double bz, double hs) {
        return gen.erodedHeight(bx / hs, bz / hs);
    }
    @Override public void prewarm(double x, double z, double span, double hs) {
        gen.prewarmErosionTiles(x, z, span, hs);
    }
    @Override public int processCacheSize() { return gen.erosionTileCacheSize(); }
    @Override public long processCacheEvictions() { return gen.tileCacheStats().evictions(); }

    @Override public long strategyContribution(long h) {
        h = mix(h, gen.params() == null ? 0L : gen.params().hashCode());
        try {
            h = mix(h, com.geogenesis.config.GeoGenesisConfig.configFingerprint());
            var cfg = com.geogenesis.config.GeoGenesisConfig.INSTANCE;
            h = mix(h, cfg.hydrologySkeletonRouting.get() ? 1 : 0);
            h = mix(h, cfg.hydrologyDecayEnabled.get() ? 1 : 0);
            h = mix(h, cfg.riverEnabled.get() ? 1 : 0);
        } catch (RuntimeException | LinkageError ignored) {
            h = mix(h, 0L);
        }
        return h;
    }
    private static long mix(long h, long v) { return (h ^ v) * 0x100000001b3L; }
}
