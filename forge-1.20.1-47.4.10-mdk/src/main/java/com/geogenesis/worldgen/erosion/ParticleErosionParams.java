package com.geogenesis.worldgen.erosion;

/** Six values captured once per particle run; XS remains a constructor input. */
public record ParticleErosionParams(double dropsMul, double erodeMul, double cascadeStrength,
                                    double momentumTransfer, int iterations, double lrate) {
    /** Historical unloaded-config fallbacks, deliberately not the Forge spec defaults. */
    public static ParticleErosionParams unloadedDefaults() {
        return new ParticleErosionParams(1.0, 1.0, 0.8, 1.0, 2, 0.1);
    }
}
