package com.geogenesis.worldgen.terrain.candidate;

import java.util.LinkedHashMap;
import java.util.Map;

/** S3独立沙箱：S2最终身份不变，两种权重都调用既有统一evaluate，不复制高度配方。 */
final class CoherentMorphWorld {
    private final RegionalMorphWorld evaluator;
    private final ShallowRegionMatcher matcher;
    private final Map<Long, Integer> identities = new LinkedHashMap<>(2048, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Integer> e) { return size() > 2048; }
    };
    private final Map<Long, Tile> tiles = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Tile> e) { return size() > 8; }
    };
    private long built, buildNanos, oldWeightNanos, newWeightNanos, oldEvalNanos, newEvalNanos;
    private long keptSegments, removedSegments, samples, distanceTests;

    CoherentMorphWorld() {
        evaluator = new RegionalMorphWorld(new RecipeMorphCatalog(RegionalMorphGallery.SEED), 600);
        matcher = new ShallowRegionMatcher(RegionalMorphGallery.SEED);
    }

    CoherentMorphWorld(long seed) {
        this(new LandRegionContext(seed, CoherentRegionDistribution.MICRO_SCALE));
    }

    CoherentMorphWorld(LandRegionContext context) {
        if (context.scale() != CoherentRegionDistribution.MICRO_SCALE)
            throw new IllegalArgumentException("S3 requires the frozen 600 micro scale");
        evaluator = new RegionalMorphWorld(context);
        matcher = new ShallowRegionMatcher(context.seed());
    }

    int recipe(LocalBoundaryGeometry.Cell cell) {
        Integer result = identities.get(cell.key());
        if (result == null) { result = matcher.match(cell).after(); identities.put(cell.key(), result); }
        return result;
    }

    Tile tileAt(double x, double z) {
        long key = LocalBoundaryGeometry.pack(FullMorphLand.tileIndex(x), FullMorphLand.tileIndex(z));
        Tile tile = tiles.get(key);
        if (tile == null) {
            long start = System.nanoTime();
            var original = evaluator.originalLand().tileAt(x, z);
            int[] recipes = new int[original.siteCount()];
            for (var site : original.geometry().sites()) recipes[site.index()] = recipe(site);
            var boundary = new RecipeBoundaryWeights(original.geometry(), recipes);
            tile = new Tile(original, recipes, boundary); tiles.put(key, tile);
            built++; buildNanos += System.nanoTime() - start;
            keptSegments += boundary.boundaries().size(); removedSegments += boundary.internalSegments();
        }
        return tile;
    }

    Pair sample(double x, double z) { return sample(tileAt(x, z), x, z); }

    Pair sample(Tile tile, double x, double z) {
        int winner = tile.recipes()[tile.original().winnerIndex(x, z)];
        long start = System.nanoTime();
        double[] a = RegionalMorphWorld.merge(tile.recipes(), tile.original().weights(x, z));
        oldWeightNanos += System.nanoTime() - start;
        start = System.nanoTime(); long tests = tile.boundary().distanceTests();
        double[] b = tile.boundary().weights(x, z, winner);
        distanceTests += tile.boundary().distanceTests() - tests;
        newWeightNanos += System.nanoTime() - start;
        start = System.nanoTime(); double ea = evaluator.evaluate(x, z, a);
        oldEvalNanos += System.nanoTime() - start;
        start = System.nanoTime(); double eb = evaluator.evaluate(x, z, b);
        newEvalNanos += System.nanoTime() - start; samples++;
        return new Pair(ea, eb, winner, a, b);
    }

    RegionalMorphWorld evaluator() { return evaluator; }
    String stats() {
        return "samples=" + samples + "\ntilesBuilt=" + built + "\nbuildNanos=" + buildNanos
                + "\noldWeightNanos=" + oldWeightNanos + "\nnewWeightNanos=" + newWeightNanos
                + "\noldEvalNanos=" + oldEvalNanos + "\nnewEvalNanos=" + newEvalNanos
                + "\nkeptSegmentsBuilt=" + keptSegments + "\nremovedSegmentsBuilt=" + removedSegments
                + "\nnewDistanceTests=" + distanceTests + "\nlabelCacheSize=" + identities.size()
                + "\npatchBuilds=" + matcher.builds() + "\npatchCacheSize=" + matcher.cachedPatches() + "\n";
    }
    record Tile(FullMorphLand.Tile original, int[] recipes, RecipeBoundaryWeights boundary) { }
    record Pair(double beforeE, double afterE, int winner, double[] before, double[] after) { }
}
