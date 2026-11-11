package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;

/**
 * Exactness control for the chunk-bbox index (sandbox only).
 *
 * Brute-force distance (no chunk cull: segmentOutsideBand on every segment, reflection into the
 * private arrays) vs the chunk-culled bandDistance over a large multi-tile sample; every site's
 * signed distance must be bit-identical, and boundary-band liveness (multi-entry points) is
 * reported so a wrongly-over-aggressive cull cannot hide behind all-onehot weights.
 */
public final class BandIndexControl {
    static final long SEED = 9139912035078620160L;
    static volatile double sink;

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

        int checked = 0, withinBand = 0;
        double maxDiff = 0;
        for (double[] p : pts) {
            DoubleFullMorphLand.Tile tile = land.tileAt(p[0], p[1]);
            int win = tile.winnerIndex(p[0], p[1]);
            for (int site = 0; site < tile.siteCount(); site++) {
                double fast = tile.signedDistance(site, p[0], p[1], win);
                double brute = bruteSignedDistance(tile, site, p[0], p[1], win);
                boolean nanFast = Double.isNaN(fast), nanBrute = Double.isNaN(brute);
                // Infinity handling: signedDistance is +/-Inf when the site's cell bbox is outside
                // the band. Two equal infinities ARE equal (subtracting them yields NaN, which
                // must not poison maxDiff); +Inf vs -Inf or Inf vs finite is a real mismatch.
                if (nanFast != nanBrute || (!nanFast && !nanBrute && !(fast == brute))) {
                    System.out.printf(Locale.ROOT, "MISMATCH site=%d at (%.3f,%.3f): fast=%.17g brute=%.17g%n",
                            site, p[0], p[1], fast, brute);
                    System.exit(2);
                }
                if (!nanFast && !Double.isInfinite(fast) && !Double.isInfinite(brute)) {
                    maxDiff = Math.max(maxDiff, Math.abs(fast - brute));
                }
                if (!nanBrute && !Double.isInfinite(brute) && Math.abs(brute) <= 120.0) withinBand++;
                checked++;
            }
        }
        System.out.printf(Locale.ROOT, "exactness points=%d siteChecks=%d withinBand=%d maxAbsDiff=%.17g maxFiniteDiff=%.17g%n",
                pts.size(), checked, withinBand, maxDiff, maxDiff);
        if (maxDiff != 0.0 || Double.isNaN(maxDiff)) { System.out.println("EXACTNESS FAIL"); System.exit(2); }

        int multi = 0;
        for (double[] p : pts) {
            DoubleFullMorphLand.Tile tile = land.tileAt(p[0], p[1]);
            double[] w = DoubleFullMorphLand.mergeToEntries(tile.entryOfSite(), tile.weights(p[0], p[1]));
            int act = 0;
            for (double v : w) if (v != 0.0) act++;
            if (act > 1) multi++;
        }
        System.out.printf(Locale.ROOT, "multiEntryPoints=%d/%d (boundary band is alive)%n", multi, pts.size());
        if (multi == 0) { System.out.println("NEGATIVE STRUCTURE FAIL: no multi-entry points"); System.exit(3); }
        System.out.println("BAND INDEX CONTROL PASS");
    }

    /** Brute-force: every segment through segmentOutsideBand + segmentDistance, no chunk cull. */
    static double bruteSignedDistance(DoubleFullMorphLand.Tile tile, int site, double x, double z, int win) {
        try {
            Class<?> tc = tile.getClass();
            java.lang.reflect.Field fSegs = tc.getDeclaredField("cellSegs"); fSegs.setAccessible(true);
            java.lang.reflect.Field fMinX = tc.getDeclaredField("segMinX"); fMinX.setAccessible(true);
            java.lang.reflect.Field fMaxX = tc.getDeclaredField("segMaxX"); fMaxX.setAccessible(true);
            java.lang.reflect.Field fMinZ = tc.getDeclaredField("segMinZ"); fMinZ.setAccessible(true);
            java.lang.reflect.Field fMaxZ = tc.getDeclaredField("segMaxZ"); fMaxZ.setAccessible(true);
            java.lang.reflect.Field fX0 = tc.getDeclaredField("segX0"); fX0.setAccessible(true);
            java.lang.reflect.Field fZ0 = tc.getDeclaredField("segZ0"); fZ0.setAccessible(true);
            java.lang.reflect.Field fX1 = tc.getDeclaredField("segX1"); fX1.setAccessible(true);
            java.lang.reflect.Field fZ1 = tc.getDeclaredField("segZ1"); fZ1.setAccessible(true);
            int[] segs = ((int[][]) fSegs.get(tile))[site];
            if (segs.length == 0) return Double.NaN;
            double[] minX = (double[]) fMinX.get(tile), maxX = (double[]) fMaxX.get(tile);
            double[] minZ = (double[]) fMinZ.get(tile), maxZ = (double[]) fMaxZ.get(tile);
            double[] x0 = (double[]) fX0.get(tile), z0 = (double[]) fZ0.get(tile);
            double[] x1 = (double[]) fX1.get(tile), z1 = (double[]) fZ1.get(tile);
            double best = Double.POSITIVE_INFINITY;
            for (int k : segs) {
                double dx = x < minX[k] ? minX[k] - x : (x > maxX[k] ? x - maxX[k] : 0.0);
                double dz = z < minZ[k] ? minZ[k] - z : (z > maxZ[k] ? z - maxZ[k] : 0.0);
                if (Math.sqrt(dx * dx + dz * dz) > 120.0) continue;
                double d = DoubleBoundaryGeometry.segmentDistance(x, z, x0[k], z0[k], x1[k], z1[k]);
                if (d < best) best = d;
                if (best <= 0) break;
            }
            return site == win ? best : -best;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private BandIndexControl() { }
}
