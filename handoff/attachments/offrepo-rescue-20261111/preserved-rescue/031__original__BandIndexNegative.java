package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;

/**
 * NEGATIVE CONTROL (sandbox only): same harness as BandIndexControl, but the "fast" arm is a
 * deliberately corrupted chunk cull (threshold 0.5*BAND instead of BAND, i.e. over-aggressive:
 * it drops segments whose chunk bbox is 60..120 blocks away, some of which genuinely contribute
 * to the weight band). The harness MUST detect the corruption and exit non-zero; if this run
 * exits 0, the exactness control has no discriminating power.
 */
public final class BandIndexNegative {
    static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);

        List<double[]> pts = new ArrayList<>();
        int[][] tiles = {{0,0},{-4,3},{1831,1831},{-1832,-1832},{976,976},{-977,-977},{29295,29295},{-29296,-29296},{976,-977},{-977,976},{29295,-29296},{-29296,29295}};
        Random rnd = new Random(4242);
        for (int[] t : tiles) {
            double cx = (t[0] + 0.5) * 1024, cz = (t[1] + 0.5) * 1024;
            for (int i = 0; i < 40; i++) {
                pts.add(new double[]{cx + (rnd.nextDouble() - 0.5) * 1400, cz + (rnd.nextDouble() - 0.5) * 1400});
            }
        }

        int checked = 0, corrupted = 0;
        for (double[] p : pts) {
            DoubleFullMorphLand.Tile tile = land.tileAt(p[0], p[1]);
            int win = tile.winnerIndex(p[0], p[1]);
            for (int site = 0; site < tile.siteCount(); site++) {
                double fast = corruptedSignedDistance(tile, site, p[0], p[1]);
                double brute = BandIndexControl.bruteSignedDistance(tile, site, p[0], p[1], win);
                boolean nanFast = Double.isNaN(fast), nanBrute = Double.isNaN(brute);
                if (nanFast != nanBrute || (!nanFast && !nanBrute && fast != brute)) {
                    corrupted++;
                    if (corrupted <= 5) {
                        System.out.printf(Locale.ROOT, "DETECTED corruption site=%d at (%.3f,%.3f): fast=%.17g brute=%.17g%n",
                                site, p[0], p[1], fast, brute);
                    }
                }
                checked++;
            }
        }
        System.out.printf(Locale.ROOT, "negative-control points=%d siteChecks=%d corruptedComparisons=%d%n",
                pts.size(), checked, corrupted);
        if (corrupted == 0) {
            System.out.println("NEGATIVE CONTROL FAILED TO DETECT: harness has no discriminating power");
            System.exit(4);
        }
        System.out.println("NEGATIVE CONTROL OK: corrupted index was detected (exit 2 follows)");
        System.exit(2);
    }

    /** Corrupted fast path: chunk cull with 0.5*BAND threshold over the same chunk arrays. */
    static double corruptedSignedDistance(DoubleFullMorphLand.Tile tile, int site, double x, double z) {
        try {
            Class<?> tc = tile.getClass();
            java.lang.reflect.Field fSegs = tc.getDeclaredField("cellSegs"); fSegs.setAccessible(true);
            java.lang.reflect.Field fCs = tc.getDeclaredField("chunkStart"); fCs.setAccessible(true);
            java.lang.reflect.Field fCe = tc.getDeclaredField("chunkEnd"); fCe.setAccessible(true);
            java.lang.reflect.Field fCx0 = tc.getDeclaredField("chunkMinX"); fCx0.setAccessible(true);
            java.lang.reflect.Field fCx1 = tc.getDeclaredField("chunkMaxX"); fCx1.setAccessible(true);
            java.lang.reflect.Field fCz0 = tc.getDeclaredField("chunkMinZ"); fCz0.setAccessible(true);
            java.lang.reflect.Field fCz1 = tc.getDeclaredField("chunkMaxZ"); fCz1.setAccessible(true);
            java.lang.reflect.Field fX0 = tc.getDeclaredField("segX0"); fX0.setAccessible(true);
            java.lang.reflect.Field fZ0 = tc.getDeclaredField("segZ0"); fZ0.setAccessible(true);
            java.lang.reflect.Field fX1 = tc.getDeclaredField("segX1"); fX1.setAccessible(true);
            java.lang.reflect.Field fZ1 = tc.getDeclaredField("segZ1"); fZ1.setAccessible(true);
            int[] segs = ((int[][]) fSegs.get(tile))[site];
            if (segs.length == 0) return Double.NaN;
            int[] cs = ((int[][]) fCs.get(tile))[site], ce = ((int[][]) fCe.get(tile))[site];
            double[] cx0 = ((double[][]) fCx0.get(tile))[site], cx1 = ((double[][]) fCx1.get(tile))[site];
            double[] cz0 = ((double[][]) fCz0.get(tile))[site], cz1 = ((double[][]) fCz1.get(tile))[site];
            double[] x0 = (double[]) fX0.get(tile), z0 = (double[]) fZ0.get(tile);
            double[] x1 = (double[]) fX1.get(tile), z1 = (double[]) fZ1.get(tile);
            double wrongBand = 60.0; // deliberately over-aggressive: real band is 120
            double best = Double.POSITIVE_INFINITY;
            for (int c = 0; c < cs.length; c++) {
                double dx = x < cx0[c] ? cx0[c] - x : (x > cx1[c] ? x - cx1[c] : 0.0);
                if (dx > wrongBand) continue;
                double dz = z < cz0[c] ? cz0[c] - z : (z > cz1[c] ? z - cz1[c] : 0.0);
                if (dz > wrongBand || dx * dx + dz * dz > wrongBand * wrongBand) continue;
                for (int p = cs[c]; p < ce[c]; p++) {
                    int k = segs[p];
                    double d = DoubleBoundaryGeometry.segmentDistance(x, z, x0[k], z0[k], x1[k], z1[k]);
                    if (d < best) best = d;
                    if (best <= 0) return best;
                }
            }
            return best;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private BandIndexNegative() { }
}
