package com.geogenesis.config;

import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import net.minecraftforge.common.ForgeConfigSpec;

/** Forge boundary for particle runs; no cached snapshot or static injection registry. */
public final class ParticleErosionConfigAdapter {
    private ParticleErosionConfigAdapter() {}

    /** Keep the six original reads, defaults and IllegalStateException-only fallback. */
    public static ParticleErosionParams snapshot() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        return new ParticleErosionParams(
                cfgDbl(c.erosionDropsMul, 1.0),
                cfgDbl(c.erosionErodeMul, 1.0),
                cfgDbl(c.erosionCascadeStrength, 0.8),
                cfgDbl(c.erosionMomentumTransfer, 1.0),
                cfgInt(c.erosionIterations, 2),
                cfgDbl(c.erosionLrate, 0.1));
    }

    /** Explicit XS constructor input; six numeric values are captured afresh at each run. */
    public static ErosionEngine create(boolean xsEnabled, int smoothingParallelism) {
        return new ErosionEngine(xsEnabled, ParticleErosionConfigAdapter::snapshot, smoothingParallelism);
    }

    /** Cell's historical constructor reads retain ConfigSafe's null/fallback semantics. */
    public static ErosionEngine createForCell(int smoothingParallelism) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        double unusedDropsMul = cfg != null ? ConfigSafe.dbl(cfg.erosionDropsMul, 1.0) : 1.0;
        boolean xsEnabled = cfg != null && ConfigSafe.bool(cfg.erosionXSEnabled, false);
        return create(xsEnabled, smoothingParallelism);
    }

    private static double cfgDbl(ForgeConfigSpec.DoubleValue v, double fallback) {
        try { return v.get(); } catch (IllegalStateException e) { return fallback; }
    }

    private static int cfgInt(ForgeConfigSpec.IntValue v, int fallback) {
        try { return v.get(); } catch (IllegalStateException e) { return fallback; }
    }

}
