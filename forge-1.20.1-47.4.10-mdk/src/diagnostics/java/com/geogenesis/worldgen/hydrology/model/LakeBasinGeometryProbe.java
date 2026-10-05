package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class LakeBasinGeometryProbe {
    public static void main(String[] args) {
        int n = 17;
        double[] bed = new double[n * n];
        Arrays.fill(bed, 10);
        for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) bed[x * n + z] = 1;
        bed[3 * n + 8] = 3;
        bed[2 * n + 8] = -1;
        boolean[] terminals = new boolean[bed.length];
        terminals[2 * n + 8] = true;
        WaterGrid g = WaterGrid.of(n, n, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        LakeBasinGeometry geo = new LakeBasinGeometry(g, terminals, 0);
        int seed = g.idx(8, 8);
        require(geo.certified(seed) && geo.escapeLevel(seed) == 3, "internal outlet certified below unknown exterior");
        require(thresholdOracle(g, terminals, seed) == geo.escapeLevel(seed), "independent threshold connectivity oracle");
        int k = seed, maxSteps = g.cells();
        double max = bed[k];
        while (!terminals[k] && maxSteps-- > 0) {
            int next = geo.downstream(k);
            require(next >= 0 && Math.abs(g.ix(k) - g.ix(next)) + Math.abs(g.iz(k) - g.iz(next)) == 1,
                    "outlet path uses adjacent terrain cells");
            k = next; max = Math.max(max, bed[k]);
        }
        require(terminals[k] && max == 3, "path actually reaches explicit terminal through saddle");
        bed[g.idx(8, 0)] = 1;
        for (int z = 1; z <= 4; z++) bed[g.idx(8, z)] = 1;
        LakeBasinGeometry cut = new LakeBasinGeometry(g, terminals, 0);
        require(!cut.certified(seed) && cut.unknownExitLowerBound(seed) == 1,
                "cut basin cannot publish the internal outlet as globally final");
        Arrays.fill(terminals, false);
        LakeBasinGeometry noOutlet = new LakeBasinGeometry(g, terminals, 0);
        require(!noOutlet.certified(seed), "no supplied terminal remains unresolved");
        System.out.println("ALL PASS: static geometry only; potential depth is not actual water.");
    }

    private static double thresholdOracle(WaterGrid g, boolean[] terminals, int seed) {
        double[] levels = g.bed.clone(); Arrays.sort(levels);
        for (double h : levels) {
            if (h < Math.max(0, g.bed[seed])) continue;
            boolean[] seen = new boolean[g.cells()];
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(seed); seen[seed] = true;
            while (!queue.isEmpty()) {
                int k = queue.remove();
                if (terminals[k]) return h;
                for (int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                    int x = g.ix(k) + d[0], z = g.iz(k) + d[1];
                    if (!g.inside(x, z)) continue;
                    int next = g.idx(x, z);
                    if (!seen[next] && g.bed[next] <= h) { seen[next] = true; queue.add(next); }
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void require(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("[PASS] " + name);
    }
}
