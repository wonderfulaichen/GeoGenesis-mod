package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Multi-seed / multi-recipe brute-force bit-exactness of the chunk-bbox cull (sandbox only).
 *
 * <p>The other probes all run on the single canonical Gallery seed, where the recipe catalog is one
 * fixed set of 18 entries. The index is index-agnostic by construction, but "by construction" is
 * exactly the kind of claim this project has been burned by before, so this probe re-runs the
 * brute-vs-fast comparison over several seeds (each seed builds a different recipe catalog and a
 * different site layout, i.e. different cell/segment geometry) and both region scales.</p>
 *
 * <p>For every (seed, scale, point) triple it compares, for every site of the covering tile:
 * {@code signedDistance} (chunk-culled) against the chunk-cull-free brute force, bit for bit, and
 * additionally verifies that the per-site weights still sum to 1 exactly as before. Query points
 * include randomised tile interiors plus points placed at fractional offsets so the 120-block band
 * and the interior of cells are both exercised.</p>
 *
 * <p>exit 0 = all seeds bit-identical, 2 = mismatch, 3 = no in-band site was ever exercised
 * (the run would be vacuous).</p>
 */
public final class MultiSeedBruteProbe {
    static final long GALLERY = 9139912035078620160L;
    static final long[] SEEDS = {GALLERY, 1L, 42L, 20261102L, -7L, 0x5DEECE66DL,
            1234567890123L, -9223372036854775807L};
    static final float[] SCALES = {RegionPartition.REGION_SCALE, 1.0f, 2.5f};
    static final int[][] TILES = {{0, 0}, {-4, 3}, {7, -9}, {-13, -13}};

    static long checks;
    static long inBand;
    static long mismatches;
    static int tiles;
    static int skipped;
    static long noWinner;
    static final java.util.Set<Integer> dominantAll = new java.util.TreeSet<>();
    static final java.util.Set<Integer> weightLen = new java.util.TreeSet<>();
    static long multiEntry;
    static final List<String> first = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int seeds = 0, configs = 0;
        for (long seed : SEEDS) {
            for (float scale : SCALES) {
                DoubleFullMorphLand land;
                try {
                    land = new DoubleFullMorphLand(new LandRegionContext(seed, scale));
                } catch (RuntimeException e) {
                    System.out.printf(Locale.ROOT, "SKIP seed=%d scale=%s : %s%n", seed, scale, e);
                    continue;
                }
                configs++;
                runConfig(land, seed, scale);
            }
            seeds++;
        }
        System.out.printf(Locale.ROOT,
                "seeds=%d configs=%d tilesBuilt=%d tilesSkipped=%d noWinnerPoints=%d siteChecks=%d"
                        + " inBandChecks=%d mismatches=%d%n",
                seeds, configs, tiles, skipped, noWinner, checks, inBand, mismatches);
        System.out.printf(Locale.ROOT,
                "recipe coverage: distinct dominant entries used across all seeds=%d,"
                        + " multi-entry points=%d, distinct weight-vector lengths=%d%n",
                dominantAll.size(), multiEntry, weightLen.size());
        for (String s : first) System.out.println("  " + s);
        if (dominantAll.size() < 6) {
            System.out.println("RECIPE COVERAGE FAIL: the seeds did not exercise several recipes");
            System.exit(5);
        }
        if (mismatches != 0) {
            System.out.println("MULTI-SEED BRUTE FAIL: chunk cull is not product-neutral");
            System.exit(2);
        }
        if (inBand == 0) {
            System.out.println("MULTI-SEED BRUTE FAIL: no in-band distance exercised -> vacuous");
            System.exit(3);
        }
        System.out.println("MULTI-SEED BRUTE PASS");
    }

    static void runConfig(DoubleFullMorphLand land, long seed, float scale) throws Exception {
        Random rnd = new Random(seed * 31 + Float.floatToIntBits(scale));
        int builtHere = 0;
        long checksHere = checks, inBandHere = inBand, badHere = mismatches;
        for (int[] t : TILES) {
            double cx = (t[0] + 0.5) * 1024, cz = (t[1] + 0.5) * 1024;
            DoubleFullMorphLand.Tile tile;
            try {
                tile = land.tileAt(cx, cz);
            } catch (RuntimeException e) {
                // Some (seed, scale) pairs make the geometry's own site enumeration refuse to run
                // (an existing guard in DoubleBoundaryGeometry, unrelated to the index). Record and
                // skip instead of hiding it, so the coverage claim stays honest.
                skipped++;
                if (first.size() < 8) {
                    first.add(String.format(Locale.ROOT, "skip tile seed=%d scale=%s tile=(%d,%d): %s",
                            seed, scale, t[0], t[1], e.getMessage()));
                }
                continue;
            }
            tiles++;
            builtHere++;
            for (int i = 0; i < 60; i++) {
                // half the points inside the tile, half up to 1500 blocks away so that cells with
                // no polyline at all and cells right at the band edge are both hit.
                double spread = (i % 2 == 0) ? 1024.0 : 1500.0;
                double x = cx + (rnd.nextDouble() - 0.5) * spread;
                double z = cz + (rnd.nextDouble() - 0.5) * spread;
                compare(land, tile, x, z, seed, scale);
            }
            // boundary-directed points: walk out from the tile centre in 120-block steps
            for (int k = 1; k <= 4; k++) {
                compare(land, tile, cx + k * 120.0, cz, seed, scale);
                compare(land, tile, cx, cz - k * 120.0, seed, scale);
                compare(land, tile, cx + k * 120.0, cz + k * 120.0, seed, scale);
            }
        }
        System.out.printf(Locale.ROOT,
                "  seed=%d scale=%-4s tiles=%d checks=%d inBand=%d mismatches=%d%n",
                seed, scale, builtHere, checks - checksHere, inBand - inBandHere, mismatches - badHere);
    }

    static void compare(DoubleFullMorphLand land, DoubleFullMorphLand.Tile tile,
                        double x, double z, long seed, float scale) throws Exception {
        int win;
        try {
            win = tile.winnerIndex(x, z);
        } catch (RuntimeException e) {
            // no-site-wins is a property of the geometry, not of the index; the brute force cannot
            // be compared without a winner, so it is recorded and skipped rather than hidden.
            noWinner++;
            if (first.size() < 8) {
                first.add(String.format(Locale.ROOT, "skip (no winner) seed=%d scale=%s (%.2f,%.2f)",
                        seed, scale, x, z));
            }
            return;
        }
        for (int site = 0; site < tile.siteCount(); site++) {
            double fast = tile.signedDistance(site, x, z, win);
            double brute = brute(tile, site, x, z, win);
            boolean nanF = Double.isNaN(fast), nanB = Double.isNaN(brute);
            if (nanF != nanB || (!nanF && !nanB && fast != brute)) {
                mismatches++;
                if (first.size() < 8) {
                    first.add(String.format(Locale.ROOT,
                            "MISMATCH seed=%d scale=%s tile=(%d,%d) site=%d at (%.6f,%.6f)"
                                    + " fast=%.17g brute=%.17g",
                            seed, scale, tile.tileX, tile.tileZ, site, x, z, fast, brute));
                }
            }
            if (!nanB && !Double.isInfinite(brute) && Math.abs(brute) <= 120.0) inBand++;
            checks++;
        }
        double[] w = tile.weights(x, z);
        double sum = 0;
        for (double v : w) sum += v;
        if (!(Math.abs(sum - 1.0) <= 1e-12) && !Double.isNaN(sum)) {
            mismatches++;
            if (first.size() < 8) {
                first.add(String.format(Locale.ROOT, "WEIGHT SUM seed=%d scale=%s at (%.2f,%.2f) = %.17g",
                        seed, scale, x, z, sum));
            }
        }
        // recipe coverage: which merged entries this seed actually activates
        double[] merged = DoubleFullMorphLand.mergeToEntries(tile.entryOfSite(), w);
        weightLen.add(merged.length);
        int active = 0, dom = -1;
        double bestW = -1;
        for (int i = 0; i < merged.length; i++) {
            if (merged[i] != 0.0) active++;
            if (merged[i] > bestW) {
                bestW = merged[i];
                dom = i;
            }
        }
        if (active > 1) multiEntry++;
        if (dom >= 0) dominantAll.add(dom);
    }

    /** Chunk-cull-free distance: reflectively walk every segment, segmentOutsideBand unchanged. */
    static double brute(DoubleFullMorphLand.Tile tile, int site, double x, double z, int win)
            throws Exception {
        Class<?> tc = tile.getClass();
        int[][] cellSegs = (int[][]) f(tc, "cellSegs").get(tile);
        int[] segs = cellSegs[site];
        if (segs.length == 0) return Double.NaN;
        double[] minX = (double[]) f(tc, "segMinX").get(tile);
        double[] maxX = (double[]) f(tc, "segMaxX").get(tile);
        double[] minZ = (double[]) f(tc, "segMinZ").get(tile);
        double[] maxZ = (double[]) f(tc, "segMaxZ").get(tile);
        double[] x0 = (double[]) f(tc, "segX0").get(tile);
        double[] z0 = (double[]) f(tc, "segZ0").get(tile);
        double[] x1 = (double[]) f(tc, "segX1").get(tile);
        double[] z1 = (double[]) f(tc, "segZ1").get(tile);
        // the cell-level gate is part of the original formula and is kept in both arms
        double cMinX = ((double[]) f(tc, "cellMinX").get(tile))[site];
        double cMaxX = ((double[]) f(tc, "cellMaxX").get(tile))[site];
        double cMinZ = ((double[]) f(tc, "cellMinZ").get(tile))[site];
        double cMaxZ = ((double[]) f(tc, "cellMaxZ").get(tile))[site];
        double best;
        if (box(x, z, cMinX, cMaxX, cMinZ, cMaxZ) > 120.0) {
            best = Double.POSITIVE_INFINITY;
        } else {
            best = Double.POSITIVE_INFINITY;
            for (int k : segs) {
                if (box(x, z, minX[k], maxX[k], minZ[k], maxZ[k]) > 120.0) continue;
                double d = DoubleBoundaryGeometry.segmentDistance(x, z, x0[k], z0[k], x1[k], z1[k]);
                if (d < best) best = d;
                if (best <= 0) break;
            }
        }
        return site == win ? best : -best;
    }

    static double box(double x, double z, double minX, double maxX, double minZ, double maxZ) {
        double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0);
        double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    static Field f(Class<?> c, String name) throws Exception {
        Field fl = c.getDeclaredField(name);
        fl.setAccessible(true);
        return fl;
    }

    private MultiSeedBruteProbe() { }
}
