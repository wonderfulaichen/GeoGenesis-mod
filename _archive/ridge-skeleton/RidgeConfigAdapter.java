package com.geogenesis.config;

import com.geogenesis.worldgen.erosion.RidgeValleyErosion.RidgeConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/** Application-side factory for the existing mutable skeleton parameters. */
public final class RidgeConfigAdapter {
    private RidgeConfigAdapter() {}

    /** Preserves direct-new defaults for null, and the historical unloaded Forge fallbacks. */
    public static RidgeConfig fromConfig(GeoGenesisConfig cfg) {
        RidgeConfig c = new RidgeConfig();
        if (cfg != null) {
            c.enabled = cfgBool(cfg.erosionRidgeEnabled, true);
            c.landRef = (float) cfgDbl(cfg.erosionRidgeLandRef, 0.15);
            c.strength = (float) cfgDbl(cfg.erosionRidgeStrength, 2.0);
            c.cellWorldSize = (float) cfgDbl(cfg.erosionRidgeScale, 100.0);
            c.stripeFreq = (float) cfgDbl(cfg.erosionRidgeCellScale, 1.2);
            c.octaves = cfgInt(cfg.erosionRidgeOctaves, 4);
            c.gullyWeight = (float) cfgDbl(cfg.erosionRidgeGullyWeight, 0.7);
            // 脊线软硬（0=尖锐 V 形，0.5=默认，1=圆滑 U 形）
            double softness = cfgDbl(cfg.erosionRidgeRounding, 0.5);
            c.rounding[0] = (float) (0.02 + softness * 0.18);
            c.rounding[1] = (float) (softness * 0.24);
            c.detail = (float) cfgDbl(cfg.erosionRidgeDetail, 1.0);
            c.horizontalScale = (float) cfgDbl(cfg.horizontalScale, 1.0);
        }
        return c;
    }

    private static boolean cfgBool(ForgeConfigSpec.BooleanValue v, boolean fb) {
        try { return v != null && v.get(); } catch (Exception e) { return fb; }
    }

    private static double cfgDbl(ForgeConfigSpec.DoubleValue v, double fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }

    private static int cfgInt(ForgeConfigSpec.IntValue v, int fb) {
        try { return v != null ? v.get() : fb; } catch (Exception e) { return fb; }
    }
}
