package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.erosion.RuneErosionFilter;

/** 隔离候选：无旧线段纹理的连续区域载体，骨架一次，之后仍用原覆盖层。未接默认生成器。 */
final class MorphSingleShaper {
    private static final double DIFFERENCE_WU = 2.0;
    private final MorphLandField carrier, guide;
    private final RuneErosionFilter.Settings settings;
    private final long seed;
    private final double horizontalUnitWu, verticalUnitE, referenceE;

    MorphSingleShaper(MorphLandField carrier, RuneErosionFilter.Settings settings, long seed,
                      double horizontalUnitWu, double verticalUnitE, double referenceE) {
        this(carrier, carrier, settings, seed, horizontalUnitWu, verticalUnitE, referenceE);
    }

    MorphSingleShaper(MorphLandField carrier, MorphLandField guide, RuneErosionFilter.Settings settings, long seed,
                      double horizontalUnitWu, double verticalUnitE, double referenceE) {
        if (!(horizontalUnitWu > 0) || !Double.isFinite(horizontalUnitWu)
                || !(verticalUnitE > 0) || !Double.isFinite(verticalUnitE) || !Double.isFinite(referenceE)) {
            throw new IllegalArgumentException("invalid carrier units");
        }
        this.carrier = java.util.Objects.requireNonNull(carrier);
        this.guide = java.util.Objects.requireNonNull(guide);
        this.settings = java.util.Objects.requireNonNull(settings);
        this.seed = seed;
        this.horizontalUnitWu = horizontalUnitWu;
        this.verticalUnitE = verticalUnitE;
        this.referenceE = referenceE;
    }

    double e(double x, double z) { return sample(x, z).e(); }

    Sample sample(double x, double z) {
        MorphLandField.Sample s = carrier.sample(x, z);
        double mountain = s.weight()[MorphCatalog.IDX_MOUNTAIN];
        if (mountain == 0.0 || s.alpha() == 1.0) return new Sample(s.e(), 0.0, s);
        // R1 = Rune(G) + (R0-G)：只过滤宏观G，原细节残差原样加回，不再用锐边导向。
        double guideRegion = guide == carrier ? s.regionHeight() : guide.regionHeight(x, z);
        double delta = regionalDelta(x, z, guideRegion) * mountain;
        double region = s.regionHeight() + delta / MorphScale.E_SPAN;
        double coverage = s.mask() < carrier.stats().blendLow() ? Double.NaN : carrier.oldCoverageHeight(x, z);
        double covered = carrier.overlay(region, s.mask(), coverage);
        double e = Math.max(MorphScale.GROUND_E + covered * MorphScale.E_SPAN, 0.0);
        return new Sample(e, delta, s);
    }

    private double regionalDelta(double x, double z, double region) {
        // 每个邻点重新取真实世界权重；只查前级region，不回调本层或已覆盖的land.e。
        double dx = guide.regionHeight(x + DIFFERENCE_WU, z) - guide.regionHeight(x - DIFFERENCE_WU, z);
        double dz = guide.regionHeight(x, z + DIFFERENCE_WU) - guide.regionHeight(x, z - DIFFERENCE_WU);
        double gradientScale = MorphScale.E_SPAN * horizontalUnitWu / (2 * DIFFERENCE_WU * verticalUnitE);
        double n = (MorphScale.GROUND_E + region * MorphScale.E_SPAN - referenceE) / verticalUnitE;
        double fadeTarget = Math.max(-1.0, Math.min(1.0, n / 0.3));
        return verticalUnitE * RuneErosionFilter.delta(x / horizontalUnitWu, z / horizontalUnitWu,
                dx * gradientScale, dz * gradientScale, fadeTarget, settings, seed);
    }

    record Sample(double e, double regionalDeltaE, MorphLandField.Sample carrier) { }
}
