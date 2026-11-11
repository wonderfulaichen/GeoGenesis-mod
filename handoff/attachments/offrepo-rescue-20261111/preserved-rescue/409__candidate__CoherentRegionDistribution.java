package com.geogenesis.worldgen.terrain.candidate;

/** S1独立分布：600微细胞查询1200宏锚点，只返回类型，不读取高度或邻居最终结果。 */
final class CoherentRegionDistribution {
    static final float MICRO_SCALE = 600, MACRO_SCALE = 1200;
    private final long seed;
    private final RecipeMorphCatalog catalog;
    private final int anchorSeed, sideSeed, landSeed, oceanSeed;
    private final int[] oceanSlots;
    private final RefNoise.Node continentality;
    private final double landThreshold;

    CoherentRegionDistribution(long seed) { this(seed, 0); }

    /** 正偏移将海陆判断向原大陆场高值侧推进；旧入口恒0，不改变噪声/抽签/地貌。 */
    CoherentRegionDistribution(long seed, double landThreshold) {
        if (!Double.isFinite(landThreshold)) throw new IllegalArgumentException("land threshold");
        this.landThreshold = landThreshold;
        this.seed = seed;
        catalog = new RecipeMorphCatalog(seed);
        int b = base(seed);
        anchorSeed = b + 1_700_000;
        sideSeed = b + 1_710_000;
        landSeed = b + 1_720_000;
        oceanSeed = b + 1_730_000;
        oceanSlots = new UnifiedTerrainCatalog(seed).oceanSlotIds();
        continentality = RefNoise.Perlin.ofScale(b + 7000, 4000f, 6);
    }

    /** 与原CombineCandidate的WHITE分区游标相同：20复合游标+shuffle+warpX+warpZ。 */
    static RegionPartition microPartition(long seed, float scale) {
        int b = base(seed);
        return new RegionPartition(b + 23, RefNoise.Perlin.ofScale(b + 21, 400f, 1),
                RefNoise.Perlin.ofScale(b + 22, 400f, 1)).withScale(scale);
    }

    Choice choose(LocalBoundaryGeometry.Cell site) {
        Anchor a = anchor(site.vx() * MICRO_SCALE, site.vz() * MICRO_SCALE, 0);
        return choice(a);
    }

    Choice choice(Anchor a) {
        double c = continentality.compute((float) a.x(), (float) a.z()) * 2.0 - 1.0;
        double probability = RegionalMorphWorld.seaProbability(landThreshold == 0 ? c : c - landThreshold);
        double draw = unit(RefNoise.hash2D(sideSeed, a.cellX(), a.cellZ()));
        float id = legacyId(landSeed, a.cellX(), a.cellZ());
        int landSlot = RegionPartition.selectIndex(id, catalog.slotCount());
        int oceanSlot = (int) (unit(RefNoise.hash2D(oceanSeed, a.cellX(), a.cellZ())) * oceanSlots.length);
        int recipe = draw < probability ? RegionalMorphWorld.LAND_COUNT + oceanSlots[oceanSlot]
                : catalog.slotEntry(landSlot);
        return new Choice(a, c, probability, draw, recipe);
    }

    /** 中心格提供势上界F，任何赢家每轴距离<=sqrt(F)，再包含抖动[0,.7]。extra仅供穷举负控。 */
    Anchor anchor(double x, double z, int extra) {
        double u = x / MACRO_SCALE, v = z / MACRO_SCALE;
        int cx = (int) Math.floor(u), cz = (int) Math.floor(v);
        double[] center = site(anchorSeed, cx, cz);
        double f = LocalBoundaryGeometry.potential(center[0], center[1], u, v);
        double reach = Math.sqrt(f) + 1e-9;
        int x0 = (int) Math.ceil(u - reach - .7) - extra, x1 = (int) Math.floor(u + reach) + extra;
        int z0 = (int) Math.ceil(v - reach - .7) - extra, z1 = (int) Math.floor(v + reach) + extra;
        double best = Double.POSITIVE_INFINITY;
        int bx = 0, bz = 0, count = 0;
        double sx = 0, sz = 0;
        for (int az = z0; az <= z1; az++) for (int ax = x0; ax <= x1; ax++) {
            double[] p = site(anchorSeed, ax, az);
            double d = LocalBoundaryGeometry.potential(p[0], p[1], u, v);
            count++;
            if (d < best) { best = d; bx = ax; bz = az; sx = p[0]; sz = p[1]; }
        }
        if (!Double.isFinite(best) || best > f + 1e-10) throw new AssertionError("anchor bound");
        return new Anchor(bx, bz, sx * MACRO_SCALE, sz * MACRO_SCALE, count);
    }

    static double[] site(int seed, int x, int z) {
        float[] v = RegionCellTable.CELL[RefNoise.hash2D(seed, x, z) & 255];
        return new double[]{x + v[0] * .7f, z + v[1] * .7f};
    }

    private static float legacyId(int seed, int x, int z) {
        int n = seed ^ (1619 * x) ^ (31337 * z);
        return RefNoise.mapClamped(n * n * n * 60493 / 2.14748365E9F, -1, 1);
    }

    static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
    private static double unit(int hash) { return hash * 0x1.0p-31; }
    long seed() { return seed; }
    record Anchor(int cellX, int cellZ, double x, double z, int candidates) { }
    record Choice(Anchor anchor, double c, double probability, double draw, int recipe) { }
}
