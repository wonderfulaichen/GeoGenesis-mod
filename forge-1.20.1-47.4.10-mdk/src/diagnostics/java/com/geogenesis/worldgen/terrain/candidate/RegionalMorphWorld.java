package com.geogenesis.worldgen.terrain.candidate;

import java.util.LinkedHashMap;
import java.util.Map;

/** P3沙箱：同一原区域几何→站点身份→23配方权重→共用节点内核。没有特殊海岸加工。 */
final class RegionalMorphWorld {
    static final int LAND_COUNT = RecipeMorphCatalog.ENTRY_COUNT;
    static final int COUNT = LAND_COUNT + OceanRecipeCatalog.ENTRY_COUNT;
    private final long seed;
    private final RecipeMorphCatalog catalog;
    private final FullMorphLand land;
    private final NodeMorphEngine engine;
    private final RefNoise.Node continentality;
    private final int[] oceanSlots;
    private final Map<Long, SiteChoice> choices = new LinkedHashMap<>();
    private final Map<Long, RegionTile> regions = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, RegionTile> eldest) {
            return size() > FullMorphLand.CACHE_TILES;
        }
    };
    private long sampled, selectionNanos, sampleNanos, activeTotal;
    private int maxActive;

    RegionalMorphWorld(RecipeMorphCatalog catalog) {
        this(catalog, RegionPartition.REGION_SCALE);
    }

    RegionalMorphWorld(RecipeMorphCatalog catalog, float regionScale) {
        this(catalog, new FullMorphLand(catalog, regionScale));
    }

    RegionalMorphWorld(LandRegionContext context) {
        this(context.catalog(), new FullMorphLand(context));
    }

    private RegionalMorphWorld(RecipeMorphCatalog catalog, FullMorphLand land) {
        this.catalog = catalog;
        seed = catalog.seed();
        this.land = land;
        continentality = RefNoise.Perlin.ofScale(base(seed) + 7000, 4000f, 6);
        oceanSlots = new UnifiedTerrainCatalog(seed).oceanSlotIds();
        engine = new NodeMorphEngine(MorphCatalog.world(catalog,
                new OceanRecipeCatalog(seed + OceanRecipeCatalog.SEED_OFFSET, true)), seed);
    }

    FullMorphLand originalLand() { return land; }

    /** 单个站点仅在稳定world坐标抽签；不使用查询点或局部tile编号参与身份计算。 */
    SiteChoice choose(LocalBoundaryGeometry geometry, LocalBoundaryGeometry.Cell site) {
        SiteChoice cached = choices.get(site.key());
        if (cached != null) return cached;
        long start = System.nanoTime();
        double[] world = geometry.warpInverse(site.vx(), site.vz(), Double.NaN, Double.NaN);
        if (!Double.isFinite(world[2]) || world[2] > geometry.invertTolBlocks())
            throw new IllegalStateException("site inverse failed: " + site + " residual=" + world[2]);
        double c = continentality.compute((float) world[0], (float) world[1]) * 2.0 - 1.0;
        double p = seaProbability(c);
        double draw = unit(RefNoise.hash2D(base(seed) + 7400, site.cellX(), site.cellZ()));
        int landSlot = geometry.slotOfCell(site.cellX(), site.cellZ());
        int oceanSlot = (int) (unit(RefNoise.hash2D(base(seed + OceanRecipeCatalog.SEED_OFFSET),
                site.cellX(), site.cellZ())) * oceanSlots.length);
        int recipe = draw < p ? LAND_COUNT + oceanSlots[oceanSlot] : catalog.slotEntry(landSlot);
        SiteChoice result = new SiteChoice(site.cellX(), site.cellZ(), world[0], world[1], c, p,
                draw, landSlot, oceanSlot, recipe);
        choices.put(site.key(), result);
        selectionNanos += System.nanoTime() - start;
        return result;
    }

    static double seaProbability(double c) {
        double t = Math.max(0, Math.min(1, -c / UnifiedWorld.OCEAN_C));
        return t * t * (3 - 2 * t);
    }

    RegionTile tileAt(double x, double z) {
        int tx = FullMorphLand.tileIndex(x), tz = FullMorphLand.tileIndex(z);
        long key = LocalBoundaryGeometry.pack(tx, tz);
        RegionTile tile = regions.get(key);
        if (tile == null) {
            FullMorphLand.Tile original = land.tileAt(x, z);
            int[] ids = new int[original.siteCount()];
            for (LocalBoundaryGeometry.Site site : original.geometry().sites())
                ids[site.index()] = choose(original.geometry(), site).recipe();
            tile = new RegionTile(original, ids);
            regions.put(key, tile);
        }
        return tile;
    }

    Sample sample(double x, double z) { return sample(tileAt(x, z), x, z); }

    Sample sample(RegionTile tile, double x, double z) {
        long start = System.nanoTime();
        double[] weights = merge(tile.recipes(), tile.original().weights(x, z));
        double seaWeight = 0;
        for (int i = LAND_COUNT; i < COUNT; i++) seaWeight += weights[i];
        int active = FullMorphLand.activeCount(weights);
        int winner = tile.original().winnerIndex(x, z);
        double e = evaluate(x, z, weights);
        sampled++;
        activeTotal += active;
        maxActive = Math.max(maxActive, active);
        sampleNanos += System.nanoTime() - start;
        return new Sample(e, FullMorphLand.dominant(weights), tile.recipes()[winner], seaWeight, active, weights);
    }

    double evaluate(double x, double z, double[] weights) {
        double landWeight = 0;
        boolean landOnly = true;
        for (int i = 0; i < LAND_COUNT; i++) landWeight += weights[i];
        for (int i = LAND_COUNT; i < COUNT; i++) if (weights[i] > 0) landOnly = false;
        // 极小海权重可能被double加法吞掉，不能用sumLand==1推断纯陆。
        if (landOnly) {
            double region = engine.sampleLand(x, z, weights);
            return landE(land.replayOverlay(region, x, z));
        }
        double e = engine.sampleE(x, z, weights);
        if (landWeight > 0 && land.alphaOf(land.maskValue(x, z)) > 0) {
            double[] landWeights = new double[COUNT];
            System.arraycopy(weights, 0, landWeights, 0, LAND_COUNT);
            FullMorphLand.normalize(landWeights);
            double region = engine.sampleLand(x, z, landWeights);
            double delta = landE(land.replayOverlay(region, x, z)) - landE(region);
            // 仅把既有陆地覆盖层的增量接回；海陆内部高度仍由同一次节点求值产生。
            e += landWeight * delta;
        }
        return e;
    }

    double hardE(double x, double z, int recipe) {
        double[] weights = new double[COUNT];
        weights[recipe] = 1;
        return evaluate(x, z, weights);
    }

    static double[] merge(int[] ids, double[] siteWeights) {
        if (ids.length != siteWeights.length) throw new IllegalArgumentException("site count");
        double[] weights = new double[COUNT];
        for (int i = 0; i < ids.length; i++) weights[ids[i]] += siteWeights[i];
        FullMorphLand.normalize(weights);
        return weights;
    }

    String stats() {
        return "samples=" + sampled + "\nactiveMean=" + ((double) activeTotal / sampled)
                + "\nactiveMax=" + maxActive + "\nsampleNanos=" + sampleNanos
                + "\nsiteSelectionNanos=" + selectionNanos + "\nuniqueSites=" + choices.size()
                + "\ngeometry=" + land.stats() + "\n";
    }

    private static double landE(double h) { return Math.max(CombineCandidate.GROUND_E + h * CombineCandidate.E_SPAN, 0); }
    // hash ^= hash >> 13使符号位恒0，输出是31位非负整数而非uint32。
    private static double unit(int hash) { return hash * 0x1.0p-31; }
    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
    record SiteChoice(int cellX, int cellZ, double x, double z, double c, double probability,
                      double draw, int landSlot, int oceanSlot, int recipe) { }
    record RegionTile(FullMorphLand.Tile original, int[] recipes) { }
    record Sample(double e, int dominant, int winnerRecipe, double seaWeight, int active, double[] weights) { }
}
