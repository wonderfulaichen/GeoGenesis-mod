package com.geogenesis.worldgen.terrain.candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** S3配方并集外边权重：同配方内部边不参与求距，所有异配方支持均保留。 */
final class RecipeBoundaryWeights {
    static final double HALF = LocalBoundaryGeometry.BAND_HALF_BLOCKS;
    private final List<Boundary> boundaries;
    private final Map<Long, List<Boundary>> bins = new HashMap<>();
    private final long internalSegments;
    private long queries, distanceTests;

    RecipeBoundaryWeights(LocalBoundaryGeometry geometry, int[] recipes) {
        List<Boundary> kept = new ArrayList<>();
        long removed = 0;
        for (var edge : geometry.edges()) {
            int a = recipes[edge.a.index()], b = recipes[edge.b.index()];
            if (a == b) { removed += Math.max(0, edge.wx.length - 1); continue; }
            for (int i = 1; i < edge.wx.length; i++)
                kept.add(new Boundary(a, b, edge.wx[i - 1], edge.wz[i - 1], edge.wx[i], edge.wz[i]));
        }
        boundaries = List.copyOf(kept); internalSegments = removed; index();
    }

    /** 独立几何夹具入口；不含站点归一或高度逻辑。 */
    RecipeBoundaryWeights(List<Boundary> boundaries) {
        this.boundaries = List.copyOf(boundaries); internalSegments = 0; index();
    }

    private void index() {
        for (Boundary b : boundaries)
            for (int z = bin(Math.min(b.az(), b.bz()) - HALF); z <= bin(Math.max(b.az(), b.bz()) + HALF); z++)
                for (int x = bin(Math.min(b.ax(), b.bx()) - HALF); x <= bin(Math.max(b.ax(), b.bx()) + HALF); x++)
                    bins.computeIfAbsent(LocalBoundaryGeometry.pack(x, z), k -> new ArrayList<>()).add(b);
    }

    double[] weights(double x, double z, int winnerRecipe) {
        double[] distance = new double[RegionalMorphWorld.COUNT];
        java.util.Arrays.fill(distance, Double.POSITIVE_INFINITY);
        queries++;
        for (Boundary b : bins.getOrDefault(LocalBoundaryGeometry.pack(bin(x), bin(z)), List.of())) {
            if (x < Math.min(b.ax(), b.bx()) - HALF || x > Math.max(b.ax(), b.bx()) + HALF
                    || z < Math.min(b.az(), b.bz()) - HALF || z > Math.max(b.az(), b.bz()) + HALF) continue;
            distanceTests++;
            double d = LocalBoundaryGeometry.segmentDistance(x, z, b.ax(), b.az(), b.bx(), b.bz());
            distance[b.a()] = Math.min(distance[b.a()], d);
            distance[b.b()] = Math.min(distance[b.b()], d);
        }
        double[] result = new double[RegionalMorphWorld.COUNT];
        for (int i = 0; i < result.length; i++)
            result[i] = LocalBoundaryGeometry.progress(i == winnerRecipe ? distance[i] : -distance[i]);
        FullMorphLand.normalize(result);
        return result;
    }

    List<Boundary> boundaries() { return boundaries; }
    long internalSegments() { return internalSegments; }
    long distanceTests() { return distanceTests; }
    long queries() { return queries; }
    private static int bin(double v) { return (int) Math.floor(v / HALF); }
    record Boundary(int a, int b, double ax, double az, double bx, double bz) { }
}
