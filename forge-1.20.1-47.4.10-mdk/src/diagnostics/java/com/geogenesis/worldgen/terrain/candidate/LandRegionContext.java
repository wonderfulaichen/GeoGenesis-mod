package com.geogenesis.worldgen.terrain.candidate;

/** N1显式seed上下文；保留旧float分区、mask和1.05台地覆盖，不调用Gallery工厂取上下文。 */
final class LandRegionContext {
    private final RecipeMorphCatalog catalog;
    private final float scale;
    private final RegionPartition partition;
    private final CombineCandidate.Category[] slotCats;
    private final RefNoise.Node mask;
    private final CombineCandidate.Family oldCoverage;
    private final float blendLow, blendHigh;

    LandRegionContext(long seed, float scale) {
        if (!(scale > 0) || !Float.isFinite(scale)) throw new IllegalArgumentException("region scale");
        this.scale = scale;
        catalog = new RecipeMorphCatalog(seed);
        partition = CoherentRegionDistribution.microPartition(seed, scale);
        slotCats = new CombineCandidate.Category[catalog.slotCount()];
        for (int i = 0; i < slotCats.length; i++) slotCats[i] = category(catalog.entry(catalog.slotEntry(i)));
        int base = (int) (seed ^ (seed >>> 32));
        RefNoise.Node mc = new RefNoise.WorleyEdgeAdd(base + 4241,
                1f / FullMorphLand.combineConstant("MOUNTAIN_SHAPE_SCALE"));
        mc = RefNoise.warpPerlin(mc, base + 24, 333f, 2, 250f);
        mc = new RefNoise.Curve3(mc);
        mc = new RefNoise.Clamped(mc, 0f, 0.9f);
        mask = new RefNoise.Mapped(mc, 0f, 1f, 0f, 1f);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        plateau.seed(seed + 44);
        oldCoverage = plateau::height01;
        blendLow = FullMorphLand.combineConstant("MTN_BLEND_LOW");
        blendHigh = FullMorphLand.combineConstant("MTN_BLEND_HIGH");
    }

    private static CombineCandidate.Category category(RecipeMorphCatalog.Entry e) {
        if (e.isComposite()) {
            var a = category(e.left()); var b = category(e.right());
            return a.ordinal() > b.ordinal() ? a : b;
        }
        return switch (e.kind()) {
            case LOW -> e.index() < 2 ? CombineCandidate.Category.PLAIN : CombineCandidate.Category.HILLS;
            case PLATEAU -> CombineCandidate.Category.PLATEAU;
            case BADLANDS -> CombineCandidate.Category.BADLANDS;
            case MOUNTAIN -> CombineCandidate.Category.MOUNTAINS;
        };
    }

    long seed() { return catalog.seed(); }
    RecipeMorphCatalog catalog() { return catalog; }
    float scale() { return scale; }
    RegionPartition partition() { return partition; }
    CombineCandidate.Category[] slotCategories() { return slotCats.clone(); }
    RefNoise.Node mask() { return mask; }
    CombineCandidate.Family oldCoverage() { return oldCoverage; }
    float blendLow() { return blendLow; }
    float blendHigh() { return blendHigh; }
}
