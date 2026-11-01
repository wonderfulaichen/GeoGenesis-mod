package com.geogenesis.worldgen.terrain.candidate;

/** M0只读前向查询：沿旧float warp和S3 double NATURAL赢家定义，不建边、不反解。 */
final class M0DistributionSampler {
    private final CoherentRegionDistribution distribution;
    private final int partitionSeed;
    private final float frequency;
    private final RefNoise.Node warpX, warpZ;

    M0DistributionSampler(long seed) { this(seed, 0); }

    M0DistributionSampler(long seed, double landThreshold) {
        distribution = new CoherentRegionDistribution(seed, landThreshold);
        var partition = CoherentRegionDistribution.microPartition(seed, CoherentRegionDistribution.MICRO_SCALE);
        partitionSeed = (Integer) RecipeMorphCatalog.field(partition, "seed");
        frequency = (Float) RecipeMorphCatalog.field(partition, "frequency");
        warpX = (RefNoise.Node) RecipeMorphCatalog.field(partition, "warpX");
        warpZ = (RefNoise.Node) RecipeMorphCatalog.field(partition, "warpZ");
    }

    double[] forward(double x, double z) {
        float fx = (float) x, fz = (float) z;
        float ox = (warpX.compute(fx, fz) - .5f) * LocalBoundaryGeometry.WARP_STRENGTH;
        float oz = (warpZ.compute(fx, fz) - .5f) * LocalBoundaryGeometry.WARP_STRENGTH;
        return new double[]{(fx + ox) * frequency, (fz + oz) * frequency};
    }

    Pick sample(double x, double z) { return sample(x, z, 0); }

    /** 同索引格给势上界F；任一赢家每轴差<=sqrt(F)，站点偏移在[0,.7]。extra仅用于穷举负控。 */
    Pick sample(double x, double z, int extra) {
        double[] uv = forward(x, z);
        int cx = (int) Math.floor(uv[0]), cz = (int) Math.floor(uv[1]);
        var center = cell(cx, cz);
        double f = LocalBoundaryGeometry.potential(center.vx(), center.vz(), uv[0], uv[1]);
        double reach = Math.sqrt(f) + 1e-9;
        int x0 = (int) Math.ceil(uv[0] - reach - .7) - extra;
        int x1 = (int) Math.floor(uv[0] + reach) + extra;
        int z0 = (int) Math.ceil(uv[1] - reach - .7) - extra;
        int z1 = (int) Math.floor(uv[1] + reach) + extra;
        LocalBoundaryGeometry.Cell winner = null;
        double best = Double.POSITIVE_INFINITY;
        int count = 0;
        for (int az = z0; az <= z1; az++) for (int ax = x0; ax <= x1; ax++) {
            var candidate = cell(ax, az);
            double p = LocalBoundaryGeometry.potential(candidate.vx(), candidate.vz(), uv[0], uv[1]);
            if (p < best || p == best && earlier(candidate, winner)) {
                winner = candidate; best = p;
            }
            count++;
        }
        if (winner == null || best > f + 1e-12) throw new AssertionError("M0 candidate bound");
        var choice = distribution.choose(winner);
        return new Pick(winner, choice.recipe(), choice.c(), choice.probability(), count);
    }

    private LocalBoundaryGeometry.Cell cell(int x, int z) {
        float[] v = RegionCellTable.CELL[RefNoise.hash2D(partitionSeed, x, z) & 255];
        // int+float的加法也在float，之后才提升double；严格沿用siteXZ而非改进其精度。
        double sx = x + v[0] * LocalBoundaryGeometry.CELL_JITTER;
        double sz = z + v[1] * LocalBoundaryGeometry.CELL_JITTER;
        return new LocalBoundaryGeometry.Cell(x, z, sx, sz, 0);
    }

    /** 与geometry.finalizeSites排序一致：vz/vx/cellX，而非整数cell枚举顺序。 */
    private static boolean earlier(LocalBoundaryGeometry.Cell a, LocalBoundaryGeometry.Cell b) {
        return b == null || a.vz() < b.vz() || a.vz() == b.vz()
                && (a.vx() < b.vx() || a.vx() == b.vx() && a.cellX() < b.cellX());
    }

    record Pick(LocalBoundaryGeometry.Cell cell, int recipe, double c, double probability, int candidates) { }
}
