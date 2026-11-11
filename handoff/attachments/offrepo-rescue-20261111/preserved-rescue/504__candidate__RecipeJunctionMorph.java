package com.geogenesis.worldgen.terrain.candidate;

/** 原陆地入口；所有节点求值委托唯一可扩展内核，输出仍为历史h单位。 */
final class RecipeJunctionMorph {
    private final NodeMorphEngine engine;

    RecipeJunctionMorph(RecipeMorphCatalog catalog, long seed) {
        engine = new NodeMorphEngine(MorphCatalog.land(catalog), seed);
    }

    double sample(double x, double z, int[] ids, double[] weights) {
        return sample(x, z, engine.merge(ids, weights));
    }

    double sample(double x, double z, double[] weights) {
        return engine.sampleLand(x, z, weights);
    }
}
