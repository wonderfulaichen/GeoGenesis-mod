package com.geogenesis.worldgen.erosion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Read-only cost audit: what would it cost per erosion tile to evaluate an exact
 * polyline-network distance field (the L3 pre-erosion aid) with the real frozen
 * 8100-trace route set?
 *
 * <p>Mirrors production geometry: ERODE_TILE_SIZE=128, ERODE_TILE_BORDER=40,
 * RIDGE_SKELETON_SPACING=2 => the skeleton grid is 121x121 per tile.</p>
 *
 * <p>No engine, no config, no world: pure geometric cost, single process, median of reps.</p>
 */
public final class SkeletonDistanceCostBench {

    public static void main(String[] a) throws Exception {
        if (a.length < 4) throw new IllegalArgumentException(
                "<routes.csv> <tileCount> <reps> <bucketWu> [spacingWu=2]");
        Path csv = Path.of(a[0]);
        int tiles = Integer.parseInt(a[1]);
        int reps = Integer.parseInt(a[2]);
        int bucket = Integer.parseInt(a[3]);
        int spacing = a.length > 4 ? Integer.parseInt(a[4]) : 2;
        int skelSideArg = a.length > 5 ? Integer.parseInt(a[5]) : 121;

        long t0 = System.nanoTime();
        double[][] segs = loadSegments(csv);
        double loadMs = (System.nanoTime() - t0) / 1e6;
        int n = segs[0].length;
        System.out.printf("segments|%d|parseMs|%.1f%n", n, loadMs);

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double totalLen = 0;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, Math.min(segs[0][i], segs[2][i]));
            maxX = Math.max(maxX, Math.max(segs[0][i], segs[2][i]));
            minZ = Math.min(minZ, Math.min(segs[1][i], segs[3][i]));
            maxZ = Math.max(maxZ, Math.max(segs[1][i], segs[3][i]));
            totalLen += Math.hypot(segs[2][i] - segs[0][i], segs[3][i] - segs[1][i]);
        }
        double area = (maxX - minX) * (maxZ - minZ);
        System.out.printf("window|%.1f x %.1f = %.0f wu2|totalLengthWu|%.0f|segDensityPerWu2|%.4f%n",
                maxX - minX, maxZ - minZ, area, totalLen, n / area);

        // Bucket index over segment AABBs, built once per session (production would have to keep it too).
        long t1 = System.nanoTime();
        Map<Long, int[]> index = buildIndex(segs, bucket);
        long buildMs = (System.nanoTime() - t1) / 1000000L;
        long entries = 0;
        for (int[] v : index.values()) entries += v.length;
        System.out.printf("bucketWu|%d|buckets|%d|segmentRefs|%d|refsPerBucket|%.2f|buildMs|%d%n",
                bucket, index.size(), entries, entries / (double) index.size(), buildMs);

        int side = 128 / spacing;          // 64 x 64 core
        int skelSide = skelSideArg;        // 121 = production skeleton coverage (240wu); 64 = minimum 128wu tile
        int ring = (int) Math.ceil(14.0 / bucket);   // L3 R max = 14wu
        System.out.printf("grid|spacing=%d|corePoints=%d|skeletonGridPoints=%d|searchRing=%d|bucketsPerQuery=%d%n",
                spacing, side * side, skelSide * skelSide, ring, (2 * ring + 1) * (2 * ring + 1));

        Random rnd = new Random(20261104L);
        int[] tx = new int[tiles], tz = new int[tiles];
        for (int i = 0; i < tiles; i++) {
            tx[i] = (int) Math.round(minX) + (i % 8) * 128 + 64;
            tz[i] = (int) Math.round(minZ) + (i / 8) * 128 + 64;
        }
        double[] ms = new double[tiles];
        long candidates = 0, tests = 0;
        for (int rep = 0; rep < reps; rep++) {
            candidates = 0; tests = 0;
            for (int t = 0; t < tiles; t++) {
                long s0 = System.nanoTime();
                double[] out = field(segs, index, bucket, tx[t], tz[t], skelSide, spacing, ring, true);
                ms[t] = (System.nanoTime() - s0) / 1e6;
                candidates += (long) out[0];
                tests += (long) out[1];
            }
        }
        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double mean = Arrays.stream(ms).average().orElse(0);
        System.out.printf(Locale.ROOT, "perTile|medianMs|%.3f|meanMs|%.3f|minMs|%.3f|maxMs|%.3f%n",
                median, mean, sorted[0], sorted[sorted.length - 1]);
        System.out.printf(Locale.ROOT, "candidates|avgPerPoint|%.2f|distanceTestsPerTile|%d|testsPerPoint|%.2f%n",
                candidates / (double) (tiles * skelSide * skelSide), tests / tiles,
                tests / (double) (tiles * skelSide * skelSide));

        // Same work spread over 4 threads, like production's parallelRows(commonPool) stages.
        long t2 = System.nanoTime();
        for (int t = 0; t < tiles; t++) {
            final int idx = t;
            java.util.stream.IntStream.range(0, 4).parallel().forEach(k -> {
                int z0 = k * skelSide / 4, z1 = (k + 1) * skelSide / 4;
                fieldRows(segs, index, bucket, tx[idx], tz[idx], skelSide, spacing, ring, z0, z1);
            });
        }
        double parallelMs = (System.nanoTime() - t2) / 1e6 / tiles;
        System.out.printf(Locale.ROOT, "parallel4|perTileMs|%.3f%n", parallelMs);

        // Zero-network alternative for scale: pure per-cell arithmetic (RidgeValleyErosion-like O(1))
        long t3 = System.nanoTime();
        double acc = 0;
        for (int i = 0; i < skelSide * skelSide; i++) {
            double v = i * 1e-3;
            acc += Math.sqrt(v * v + 1e-6) * 0.5 + Math.sin(v);
        }
        double arithMs = (System.nanoTime() - t3) / 1e6;
        System.out.printf(Locale.ROOT, "localArithmeticOnly|perTileMs|%.4f|checksum|%.3f%n", arithMs, acc);
    }

    private static double[][] loadSegments(Path csv) throws IOException {
        List<double[]> rows = new ArrayList<>();
        Map<String, List<double[]>> byTrace = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                byTrace.computeIfAbsent(f[0], k -> new ArrayList<>())
                        .add(new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])});
            }
        }
        List<double[]> seg = new ArrayList<>();
        for (List<double[]> pts : byTrace.values()) {
            pts.sort(Comparator.comparingDouble(p -> p[0]));
            for (int i = 1; i < pts.size(); i++) {
                double[] p = pts.get(i - 1), q = pts.get(i);
                if (Math.abs(p[1] - q[1]) < 1e-12 && Math.abs(p[2] - q[2]) < 1e-12) continue;
                seg.add(new double[]{p[1], p[2], q[1], q[2]});
            }
        }
        double[][] out = new double[4][seg.size()];
        for (int i = 0; i < seg.size(); i++) for (int k = 0; k < 4; k++) out[k][i] = seg.get(i)[k];
        return out;
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    private static Map<Long, int[]> buildIndex(double[][] s, int bucket) {
        Map<Long, List<Integer>> tmp = new HashMap<>();
        int n = s[0].length;
        for (int i = 0; i < n; i++) {
            int x0 = (int) Math.floor(Math.min(s[0][i], s[2][i]) / bucket);
            int x1 = (int) Math.floor(Math.max(s[0][i], s[2][i]) / bucket);
            int z0 = (int) Math.floor(Math.min(s[1][i], s[3][i]) / bucket);
            int z1 = (int) Math.floor(Math.max(s[1][i], s[3][i]) / bucket);
            for (int bx = x0; bx <= x1; bx++)
                for (int bz = z0; bz <= z1; bz++)
                    tmp.computeIfAbsent(key(bx, bz), k -> new ArrayList<>()).add(i);
        }
        Map<Long, int[]> out = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
            int[] v = new int[e.getValue().size()];
            for (int i = 0; i < v.length; i++) v[i] = e.getValue().get(i);
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** @return {candidateCount, distanceTestCount} while writing nothing (mirrors a per-cell evaluation). */
    private static double[] field(double[][] s, Map<Long, int[]> index, int bucket,
                                  int cx, int cz, int side, int spacing, int ring, boolean measure) {
        return fieldRows(s, index, bucket, cx, cz, side, spacing, ring, 0, side);
    }

    private static double[] fieldRows(double[][] s, Map<Long, int[]> index, int bucket,
                                      int cx, int cz, int side, int spacing, int ring, int z0, int z1) {
        double sum = 0;
        long cand = 0;
        long tests = 0;
        double x0 = cx - (side - 1) * spacing / 2.0, zz0 = cz - (side - 1) * spacing / 2.0;
        for (int iz = z0; iz < z1; iz++) {
            double wz = zz0 + iz * spacing;
            int bzBase = (int) Math.floor(wz / bucket);
            for (int ix = 0; ix < side; ix++) {
                double wx = x0 + ix * spacing;
                int bxBase = (int) Math.floor(wx / bucket);
                double best = Double.MAX_VALUE;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        int[] v = index.get(key(bxBase + dx, bzBase + dz));
                        if (v == null) continue;
                        for (int i : v) {
                            cand++;
                            double ax = s[0][i], az = s[1][i], bx = s[2][i], bz = s[3][i];
                            double vx = bx - ax, vz = bz - az;
                            double len2 = vx * vx + vz * vz;
                            double t = len2 < 1e-12 ? 0 : ((wx - ax) * vx + (wz - az) * vz) / len2;
                            t = t < 0 ? 0 : (t > 1 ? 1 : t);
                            double dxp = wx - (ax + t * vx), dzp = wz - (az + t * vz);
                            double dd = dxp * dxp + dzp * dzp;
                            tests++;
                            if (dd < best) best = dd;
                        }
                    }
                }
                sum += Math.sqrt(best == Double.MAX_VALUE ? 1e9 : best);
            }
        }
        return new double[]{cand, tests, sum};
    }
}