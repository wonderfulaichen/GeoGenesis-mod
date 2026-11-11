package com.geogenesis.worldgen.terrain.candidate;
import java.util.*;

/**
 * Distance-banded fuzz (sandbox only): the exactness control is point-sampled, so it cannot
 * distinguish "distance missing from the band" from "a different but close distance". This fuzz
 * bins every checked site by its brute |signed distance| (0..140 in 10-block bins) and asserts
 * every bin contains at least one point sample. If a cull bug pushed real in-band distances out
 * of the &lt;=120 live band, the 100..120 bin would go empty while the point check still passed.
 */
public final class BandIndexFuzz {
    static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);

        List<double[]> pts = new ArrayList<>();
        int[][] tiles = {{0,0},{-4,3},{1831,1831},{-1832,-1832},{976,976},{-977,-977},{29295,29295},{-29296,-29296},{976,-977},{-977,976},{29295,-29296},{-29296,29295}};
        Random rnd = new Random(90210);
        for (int[] t : tiles) {
            double cx = (t[0] + 0.5) * 1024, cz = (t[1] + 0.5) * 1024;
            for (int i = 0; i < 150; i++) {
                pts.add(new double[]{cx + (rnd.nextDouble() - 0.5) * 1500, cz + (rnd.nextDouble() - 0.5) * 1500});
            }
        }
        // Regular 5-block lattice over one full tile, matching the production gallery stride.
        for (int j = 0; j < 160; j += 5) {
            for (int i = 0; i < 160; i += 5) {
                pts.add(new double[]{(0 + 0.5) * 1024 - 400 + i * 5.0, (0 + 0.5) * 1024 - 400 + j * 5.0});
            }
        }

        int checked = 0, inBandChecks = 0;
        double maxDiff = 0;
        int[] binCount = new int[14]; // 0..10,10..20,...,130..140
        long[] binExact = new long[14];
        for (double[] p : pts) {
            DoubleFullMorphLand.Tile tile = land.tileAt(p[0], p[1]);
            int win = tile.winnerIndex(p[0], p[1]);
            for (int site = 0; site < tile.siteCount(); site++) {
                double fast = tile.signedDistance(site, p[0], p[1], win);
                double brute = BandIndexControl.bruteSignedDistance(tile, site, p[0], p[1], win);
                boolean nanF = Double.isNaN(fast), nanB = Double.isNaN(brute);
                if (nanF != nanB || (!nanF && !nanB && !(fast == brute))) {
                    System.out.printf(Locale.ROOT, "MISMATCH site=%d at (%.3f,%.3f): fast=%.17g brute=%.17g%n",
                            site, p[0], p[1], fast, brute);
                    System.exit(2);
                }
                checked++;
                if (nanB || Double.isInfinite(brute)) continue;
                double a = Math.abs(brute);
                inBandChecks++;
                maxDiff = Math.max(maxDiff, Math.abs(fast - brute));
                int bin = (int) Math.min(13, a / 10.0);
                binCount[bin]++;
                if (fast == brute) binExact[bin]++;
            }
        }
        System.out.printf(Locale.ROOT, "fuzz points=%d siteChecks=%d finiteChecks=%d maxFiniteDiff=%.17g%n",
                pts.size(), checked, inBandChecks, maxDiff);
        boolean alive = true;
        for (int b = 0; b < 14; b++) {
            System.out.printf(Locale.ROOT, "  bin %3d..%3d blocks: checked=%6d exact=%6d%n",
                    b * 10, b * 10 + 10, binCount[b], binExact[b]);
            if (b <= 11 && binCount[b] == 0) alive = false; // 0..120 must all be exercised
        }
        System.out.printf(Locale.ROOT, "multiEntryPoints=%d%n", countMulti(land, pts));
        if (!alive) { System.out.println("BAND COVERAGE FAIL: an in-band distance bin is unexercised"); System.exit(3); }
        if (maxDiff != 0.0) { System.out.println("EXACTNESS FAIL"); System.exit(2); }
        System.out.println("BAND INDEX FUZZ PASS");
    }

    static int countMulti(DoubleFullMorphLand land, List<double[]> pts) {
        int multi = 0;
        for (double[] p : pts) {
            DoubleFullMorphLand.Tile tile = land.tileAt(p[0], p[1]);
            double[] w = DoubleFullMorphLand.mergeToEntries(tile.entryOfSite(), tile.weights(p[0], p[1]));
            int act = 0;
            for (double v : w) if (v != 0.0) act++;
            if (act > 1) multi++;
        }
        return multi;
    }

    private BandIndexFuzz() { }
}
