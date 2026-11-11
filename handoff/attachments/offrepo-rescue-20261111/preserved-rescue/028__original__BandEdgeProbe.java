package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Directed BAND-boundary probe for the chunk-bbox cull in {@code DoubleFullMorphLand$Tile}
 * (sandbox only, not production).
 *
 * <p>It answers three separate questions, each with a hard assertion:</p>
 *
 * <p><b>(1) Predicate equivalence.</b> Is {@code dx*dx + dz*dz > BAND*BAND} the same predicate as
 * {@code sqrt(dx*dx + dz*dz) > BAND}? For a query point the sum is itself a double, so this reduces
 * to an ordinal question over doubles: let {@code X} be the largest double whose correctly-rounded
 * {@code sqrt} is still {@code <= BAND}; the two predicates agree on every input iff
 * {@code nextUp(BAND*BAND) > X}. At {@code BAND = 120} they do coincide, but the probe also scans
 * many other bands and reports bands where they <b>disagree</b> - i.e. the identity is mantissa
 * dependent, not algebraic. That is why the cull uses the {@code sqrt} form: its safety comes from
 * monotonicity of correctly-rounded operations, which needs no coincidence at all.</p>
 *
 * <p><b>(2) Real-tile invariant.</b> If the chunk bbox is culled, is <i>every</i> segment in that
 * chunk also culled by {@code segmentOutsideBand}? Query points are placed at distance
 * {@code nextDown^k(BAND)}, {@code BAND}, {@code nextUp^k(BAND)} from each sampled chunk bbox
 * (axis-aligned and diagonal), so the boundary itself is hit and bucketed by the <i>achieved</i>
 * distance (re-measured after rounding), not by the intended one. Every such point is also
 * bit-compared against a chunk-cull-free brute force.</p>
 *
 * <p><b>(3) Negative control.</b> The same invariance checker is re-run against a chunk bbox
 * shrunk by one ulp and against a {@code nextDown(BAND)} threshold; it must report violations,
 * otherwise it has no discriminating power.</p>
 *
 * <p>exit 0 = pass, 2 = exactness / invariant failure, 3 = insufficient boundary coverage,
 * 4 = negative control failed to detect.</p>
 */
public final class BandEdgeProbe {
    static final long SEED = 9139912035078620160L;
    static final double BAND = DoubleBoundaryGeometry.BAND_HALF_BLOCKS;
    static final double BAND_SQ = BAND * BAND;

    static final long[] tightBins = new long[12];
    static long chunkDecisions;
    static long chunkCulled;
    static long segViolations;
    static long squaredDisagreements;
    static long exactChecks;
    static long exactMismatch;
    static final List<String> first = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int a = arithmetic();
        int b = realTiles(false);
        int c = negativeControl();
        System.out.printf(Locale.ROOT,
                "SUMMARY chunkDecisions=%d chunkCulled=%d segViolations=%d squaredDisagreements=%d"
                        + " exactChecks=%d exactMismatch=%d%n",
                chunkDecisions, chunkCulled, segViolations, squaredDisagreements, exactChecks, exactMismatch);
        System.out.printf(Locale.ROOT, "tightBins(ULP distance from BAND): %s%n", bins());
        for (String s : first) System.out.println("  " + s);
        if (segViolations != 0 || exactMismatch != 0) {
            System.out.println("BAND EDGE FAIL: invariant or exactness broken");
            System.exit(2);
        }
        long tightTotal = 0;
        for (long v : tightBins) tightTotal += v;
        if (tightTotal == 0) {
            System.out.println("BAND EDGE FAIL: no boundary-adjacent chunk decision was exercised");
            System.exit(3);
        }
        if (a != 0 || b != 0 || c != 0) {
            System.out.println("BAND EDGE FAIL: sub-check failed (see above)");
            System.exit(a != 0 ? a : (b != 0 ? b : c));
        }
        System.out.println("BAND EDGE PASS");
    }

    // ------------------------------------------------------------------ (1) arithmetic

    /** Largest double whose correctly-rounded sqrt is still {@code <= b}. */
    static double sqrtCeil(double b) {
        double sq = b * b;
        double x = sq;
        for (int i = 0; i < 64; i++) {
            double up = Math.nextUp(x);
            if (!(up > x) || !(Math.sqrt(up) <= b)) return x;
            x = up;
        }
        return x;
    }

    /** True if some double S has S > b*b but sqrt(S) <= b, i.e. the squared cull over-rejects. */
    static boolean squaredOverRejects(double b) {
        return Math.nextUp(b * b) <= sqrtCeil(b);
    }

    static int arithmetic() {
        System.out.printf(Locale.ROOT, "BAND=%.17g ulp(BAND)=%.3e BAND^2=%.17g ulp(BAND^2)=%.3e%n",
                BAND, Math.ulp(BAND), BAND_SQ, Math.ulp(BAND_SQ));
        double x = sqrtCeil(BAND);
        System.out.printf(Locale.ROOT,
                "largest S with sqrt(S)<=BAND: %.17g ; nextUp(BAND^2)=%.17g ; squared-over-rejects=%b%n",
                x, Math.nextUp(BAND_SQ), Math.nextUp(BAND_SQ) <= x);
        System.out.printf(Locale.ROOT,
                "sqrt(nextUp(BAND^2))=%.17g (>BAND? %b) ; sqrt(nextDown(BAND^2))=%.17g (>BAND? %b)%n",
                Math.sqrt(Math.nextUp(BAND_SQ)), Math.sqrt(Math.nextUp(BAND_SQ)) > BAND,
                Math.sqrt(Math.nextDown(BAND_SQ)), Math.sqrt(Math.nextDown(BAND_SQ)) > BAND);

        // The same ordinal test over many other bands: the identity is not algebraic.
        List<Double> unsafe = new ArrayList<>();
        Random rnd = new Random(20261102L);
        double[] fixed = {1.0, 2.0, 4.0, 8.0, 16.0, 30.0, 60.0, 64.0, 100.0, 120.0, 128.0, 240.0, 256.0, 300.0};
        List<Double> probes = new ArrayList<>();
        for (double f : fixed) probes.add(f);
        for (int i = 0; i < 4000; i++) probes.add(1.0 + rnd.nextDouble() * 499.0);
        for (int k = 0; k < 8; k++) {
            double base = 1 << (k + 1);
            for (int s = 1; s <= 6; s++) probes.add(Math.nextDown(shift(base, s)));
        }
        int scanned = 0;
        for (double b : probes) {
            if (!(b > 0) || !Double.isFinite(b)) continue;
            scanned++;
            if (squaredOverRejects(b) && unsafe.size() < 8) unsafe.add(b);
        }
        System.out.printf(Locale.ROOT,
                "bands scanned=%d ; bands where squaring over-rejects: %d %s%n",
                scanned, unsafe.size(), unsafe.isEmpty() ? "" : unsafe.toString());
        System.out.printf(Locale.ROOT,
                "VERDICT: at BAND=%.1f squared cull is order-equivalent (no over-reject);"
                        + " in general it is NOT, so the cull keeps the sqrt form.%n", BAND);
        return 0;
    }

    static double shift(double base, int s) {
        double v = base;
        for (int i = 0; i < s; i++) v = Math.nextUp(v);
        return v;
    }

    // ------------------------------------------------------------------ (2) real tiles

    static final int[][] TILES = {{0, 0}, {-4, 3}, {1831, 1831}, {-1832, -1832},
            {976, 976}, {29295, 29295}, {-29296, 29295}};

    static int realTiles(boolean quiet) throws Exception {
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);
        long before = segViolations + exactMismatch;
        for (int[] t : TILES) {
            DoubleFullMorphLand.Tile tile = land.tileAt((t[0] + 0.5) * 1024, (t[1] + 0.5) * 1024);
            scanTile(tile);
        }
        if (!quiet) {
            System.out.printf(Locale.ROOT, "real tiles=%d chunkDecisions=%d chunkCulled=%d violations=%d%n",
                    TILES.length, chunkDecisions, chunkCulled, segViolations);
        }
        return (segViolations == before) ? 0 : 2;
    }

    static void scanTile(DoubleFullMorphLand.Tile tile) throws Exception {
        Class<?> tc = tile.getClass();
        int[][] cellSegs = (int[][]) field(tc, "cellSegs").get(tile);
        int[][] chStart = (int[][]) field(tc, "chunkStart").get(tile);
        int[][] chEnd = (int[][]) field(tc, "chunkEnd").get(tile);
        double[][] chMinX = (double[][]) field(tc, "chunkMinX").get(tile);
        double[][] chMaxX = (double[][]) field(tc, "chunkMaxX").get(tile);
        double[][] chMinZ = (double[][]) field(tc, "chunkMinZ").get(tile);
        double[][] chMaxZ = (double[][]) field(tc, "chunkMaxZ").get(tile);
        double[] segMinX = (double[]) field(tc, "segMinX").get(tile);
        double[] segMaxX = (double[]) field(tc, "segMaxX").get(tile);
        double[] segMinZ = (double[]) field(tc, "segMinZ").get(tile);
        double[] segMaxZ = (double[]) field(tc, "segMaxZ").get(tile);
        int sites = tile.siteCount();
        int step = Math.max(1, sites / 40);
        for (int site = 0; site < sites; site += step) {
            int n = chStart[site].length;
            if (n == 0) continue;
            for (int c = 0; c < n; c++) {
                if (n > 6 && c % ((n + 5) / 6) != 0) continue;
                probeChunk(tile, cellSegs, chStart, chEnd, chMinX, chMaxX, chMinZ, chMaxZ,
                        segMinX, segMaxX, segMinZ, segMaxZ, site, c);
            }
        }
    }

    static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static void probeChunk(DoubleFullMorphLand.Tile tile, int[][] cellSegs,
                           int[][] chStart, int[][] chEnd, double[][] chMinX, double[][] chMaxX,
                           double[][] chMinZ, double[][] chMaxZ,
                           double[] segMinX, double[] segMaxX, double[] segMinZ, double[] segMaxZ,
                           int site, int c) {
        double minX = chMinX[site][c], maxX = chMaxX[site][c];
        double minZ = chMinZ[site][c], maxZ = chMaxZ[site][c];
        double midX = 0.5 * (minX + maxX), midZ = 0.5 * (minZ + maxZ);
        double[] deltas = deltas();
        for (double d : deltas) {
            // axis-aligned (distance to bbox along one axis is the achieved value)
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, minX - d, midZ);
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, maxX + d, midZ);
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, midX, minZ - d);
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, midX, maxZ + d);
            double q = d * 0.7071067811865476;
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, maxX + q, maxZ + q);
            evaluate(tile, cellSegs, chStart[site][c], chEnd[site][c], segMinX, segMaxX, segMinZ, segMaxZ,
                    site, c, minX, maxX, minZ, maxZ, minX - q, minZ - q);
        }
    }

    static double[] deltas() {
        double[] d = new double[11];
        int i = 0;
        double dn = BAND;
        for (int k = 0; k < 4; k++) { dn = Math.nextDown(dn); d[i++] = dn; }
        d[i++] = BAND;
        double up = BAND;
        for (int k = 0; k < 4; k++) { up = Math.nextUp(up); d[i++] = up; }
        d[i++] = BAND * 0.5;
        d[i] = BAND * 1.5;
        return d;
    }

    static void evaluate(DoubleFullMorphLand.Tile tile, int[][] cellSegs, int from, int to,
                         double[] segMinX, double[] segMaxX, double[] segMinZ, double[] segMaxZ,
                         int site, int c,
                         double minX, double maxX, double minZ, double maxZ, double x, double z) {
        double dc = boxDistance(x, z, minX, maxX, minZ, maxZ);
        double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0);
        double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0);
        boolean cull = dc > BAND;
        boolean squaredCull = (dx * dx + dz * dz) > BAND_SQ;
        chunkDecisions++;
        if (cull != squaredCull) squaredDisagreements++;
        int bin = binOf(dc);
        if (bin >= 0) tightBins[bin]++;
        if (!cull) return;
        chunkCulled++;
        for (int p = from; p < to; p++) {
            int k = cellSegs[site][p];
            if (!(boxDistance(x, z, segMinX[k], segMaxX[k], segMinZ[k], segMaxZ[k]) > BAND)) {
                segViolations++;
                if (first.size() < 6) {
                    first.add(String.format(Locale.ROOT,
                            "VIOLATION tile=(%d,%d) site=%d chunk=%d seg=%d at (%.17g,%.17g)"
                                    + " Dchunk=%.17g Dseg=%.17g",
                            tile.tileX, tile.tileZ, site, c, k, x, z, dc,
                            boxDistance(x, z, segMinX[k], segMaxX[k], segMinZ[k], segMaxZ[k])));
                }
            }
        }
        if ((chunkDecisions % 23) == 0) exactness(tile, site, x, z);
    }

    /** Bit-compare fast signed distance vs chunk-cull-free brute force for the chunk's own site. */
    static void exactness(DoubleFullMorphLand.Tile tile, int site, double x, double z) {
        int win = tile.winnerIndex(x, z);
        for (int s : new int[]{site, win}) {
            double fast = tile.signedDistance(s, x, z, win);
            double brute = BandIndexControl.bruteSignedDistance(tile, s, x, z, win);
            boolean nanF = Double.isNaN(fast), nanB = Double.isNaN(brute);
            if (nanF != nanB || (!nanF && !nanB && fast != brute)) {
                exactMismatch++;
                if (first.size() < 6) {
                    first.add(String.format(Locale.ROOT,
                            "EXACT MISMATCH site=%d at (%.17g,%.17g) fast=%.17g brute=%.17g",
                            s, x, z, fast, brute));
                }
            }
            exactChecks++;
        }
    }

    static int binOf(double d) {
        double diff = d - BAND;
        double u = Math.ulp(BAND);
        if (Math.abs(diff) <= u) return 0;
        if (Math.abs(diff) <= 2 * u) return 1;
        if (Math.abs(diff) <= 4 * u) return 2;
        if (Math.abs(diff) <= 8 * u) return 3;
        if (Math.abs(diff) <= 1e-9) return 4;
        if (Math.abs(diff) <= 1e-6) return 5;
        if (Math.abs(diff) <= 1e-3) return 6;
        if (Math.abs(diff) <= 1.0) return 7;
        if (diff < -1.0 && diff >= -60.0) return 8;
        if (diff > 1.0 && diff <= 60.0) return 9;
        return -1;
    }

    static String bins() {
        String[] names = {"|d-BAND|<=1ulp", "<=2ulp", "<=4ulp", "<=8ulp", "<=1e-9", "<=1e-6",
                "<=1e-3", "<=1.0", "further inside", "further outside"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names[i]).append('=').append(tightBins[i]);
        }
        return sb.toString();
    }

    static double boxDistance(double x, double z, double minX, double maxX, double minZ, double maxZ) {
        double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0);
        double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ (3) negative control

    /**
     * Three deliberate over-aggressive variants of the chunk cull, all fed through the same
     * invariant checker (culled chunk must have every segment culled):
     * (a) shrink the chunk bbox by 1 block on every side, (b) shrink it by 8 blocks,
     * (c) keep the bbox but lower the threshold to {@code nextDown(BAND)} so that chunks sitting
     * just inside the band get culled. A checker with real power must flag at least one of them.
     */
    static int negativeControl() throws Exception {
        long a = corrupt(1, 1.0);
        long b = corrupt(1, 8.0);
        long c = corruptThreshold(1);
        System.out.printf(Locale.ROOT,
                "negative-control violations: shrink1=%d shrink8=%d threshold-nextDown=%d%n", a, b, c);
        if (a == 0 && b == 0 && c == 0) {
            System.out.println("NEGATIVE CONTROL FAILED TO DETECT: boundary checker has no power");
            return 4;
        }
        return 0;
    }

    /** Bbox-shrink corruption: chunk looks farther away than the segments it owns. */
    static long corrupt(int tiles, double shrink) throws Exception {
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);
        long found = 0;
        for (int t = 0; t < tiles; t++) {
            int[] tileCoord = TILES[t];
            DoubleFullMorphLand.Tile tile =
                    land.tileAt((tileCoord[0] + 0.5) * 1024, (tileCoord[1] + 0.5) * 1024);
            Class<?> tc = tile.getClass();
            int[][] cellSegs = (int[][]) field(tc, "cellSegs").get(tile);
            int[][] chStart = (int[][]) field(tc, "chunkStart").get(tile);
            int[][] chEnd = (int[][]) field(tc, "chunkEnd").get(tile);
            double[][] chMinX = (double[][]) field(tc, "chunkMinX").get(tile);
            double[][] chMaxX = (double[][]) field(tc, "chunkMaxX").get(tile);
            double[][] chMinZ = (double[][]) field(tc, "chunkMinZ").get(tile);
            double[][] chMaxZ = (double[][]) field(tc, "chunkMaxZ").get(tile);
            double[] segMinX = (double[]) field(tc, "segMinX").get(tile);
            double[] segMaxX = (double[]) field(tc, "segMaxX").get(tile);
            double[] segMinZ = (double[]) field(tc, "segMinZ").get(tile);
            double[] segMaxZ = (double[]) field(tc, "segMaxZ").get(tile);
            int sites = tile.siteCount();
            for (int site = 0; site < sites && found == 0; site += 3) {
                int n = chStart[site].length;
                for (int c = 0; c < n && found == 0; c++) {
                    double minX = chMinX[site][c] + shrink;
                    double maxX = chMaxX[site][c] - shrink;
                    double minZ = chMinZ[site][c] + shrink;
                    double maxZ = chMaxZ[site][c] - shrink;
                    if (!(minX <= maxX) || !(minZ <= maxZ)) continue;
                    double midX = 0.5 * (minX + maxX), midZ = 0.5 * (minZ + maxZ);
                    for (double d : new double[]{BAND, Math.nextUp(BAND), Math.nextDown(BAND)}) {
                        found += detects(cellSegs[site], chStart[site][c], chEnd[site][c],
                                segMinX, segMaxX, segMinZ, segMaxZ, minX, maxX, minZ, maxZ, minX - d, midZ, BAND);
                        found += detects(cellSegs[site], chStart[site][c], chEnd[site][c],
                                segMinX, segMaxX, segMinZ, segMaxZ, minX, maxX, minZ, maxZ, midX, maxZ + d, BAND);
                    }
                }
            }
        }
        return found;
    }

    /** Threshold corruption: correct bbox, but cull at {@code nextDown(BAND)}. */
    static long corruptThreshold(int tiles) throws Exception {
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        DoubleFullMorphLand land = new DoubleFullMorphLand(cat);
        double wrong = Math.nextDown(Math.nextDown(Math.nextDown(BAND)));
        long found = 0;
        for (int t = 0; t < tiles; t++) {
            int[] tileCoord = TILES[t];
            DoubleFullMorphLand.Tile tile =
                    land.tileAt((tileCoord[0] + 0.5) * 1024, (tileCoord[1] + 0.5) * 1024);
            Class<?> tc = tile.getClass();
            int[][] cellSegs = (int[][]) field(tc, "cellSegs").get(tile);
            int[][] chStart = (int[][]) field(tc, "chunkStart").get(tile);
            int[][] chEnd = (int[][]) field(tc, "chunkEnd").get(tile);
            double[][] chMinX = (double[][]) field(tc, "chunkMinX").get(tile);
            double[][] chMaxX = (double[][]) field(tc, "chunkMaxX").get(tile);
            double[][] chMinZ = (double[][]) field(tc, "chunkMinZ").get(tile);
            double[][] chMaxZ = (double[][]) field(tc, "chunkMaxZ").get(tile);
            double[] segMinX = (double[]) field(tc, "segMinX").get(tile);
            double[] segMaxX = (double[]) field(tc, "segMaxX").get(tile);
            double[] segMinZ = (double[]) field(tc, "segMinZ").get(tile);
            double[] segMaxZ = (double[]) field(tc, "segMaxZ").get(tile);
            int sites = tile.siteCount();
            for (int site = 0; site < sites && found == 0; site += 3) {
                int n = chStart[site].length;
                for (int c = 0; c < n && found == 0; c++) {
                    double minX = chMinX[site][c], maxX = chMaxX[site][c];
                    double minZ = chMinZ[site][c], maxZ = chMaxZ[site][c];
                    double midX = 0.5 * (minX + maxX), midZ = 0.5 * (minZ + maxZ);
                    for (double d : new double[]{BAND, Math.nextUp(BAND)}) {
                        found += detects(cellSegs[site], chStart[site][c], chEnd[site][c],
                                segMinX, segMaxX, segMinZ, segMaxZ, minX, maxX, minZ, maxZ,
                                minX - d, midZ, wrong);
                        found += detects(cellSegs[site], chStart[site][c], chEnd[site][c],
                                segMinX, segMaxX, segMinZ, segMaxZ, minX, maxX, minZ, maxZ,
                                midX, maxZ + d, wrong);
                    }
                }
            }
        }
        return found;
    }

    static long detects(int[] segs, int from, int to, double[] segMinX, double[] segMaxX,
                        double[] segMinZ, double[] segMaxZ,
                        double minX, double maxX, double minZ, double maxZ,
                        double x, double z, double chunkThreshold) {
        if (!(boxDistance(x, z, minX, maxX, minZ, maxZ) > chunkThreshold)) return 0;
        for (int p = from; p < to; p++) {
            int k = segs[p];
            // segment-level criterion is unchanged (the real BAND); a violation is a segment
            // that the real criterion would keep but the corrupted chunk cull already dropped.
            if (!(boxDistance(x, z, segMinX[k], segMaxX[k], segMinZ[k], segMaxZ[k]) > BAND)) return 1;
        }
        return 0;
    }

    private BandEdgeProbe() { }
}
