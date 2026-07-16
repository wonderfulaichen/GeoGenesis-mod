package com.geogenesis.worldgen.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Simplified Space Colonization Algorithm (SCA) - mountain ridge network.
 * Uses k-NN graph with spatial index for fast distance queries.
 */
public final class MountainRidgeNetwork {

    private static final int GRID_N = 30;
    private static final int K = 3;
    private static final double WORLD_HALF_SIZE = 4000.0;
    private static final double SEG_SUBDIVIDE = 200.0;
    private static final double SPATIAL_CELL = 100.0;
    private static final double FALLOFF = 200.0;

    private final double[] attractorX;
    private final double[] attractorZ;
    private final double[] segData;
    private final int segCount;
    private final Map<Long, int[]> spatialGrid;

    public MountainRidgeNetwork(long worldSeed) {
        int N = GRID_N * (GRID_N * 2 / 3);
        attractorX = new double[N];
        attractorZ = new double[N];
        Random rng = new Random(worldSeed * 1009L + 17);

        double cellW = (2.0 * WORLD_HALF_SIZE) / GRID_N;
        double cellH = (2.0 * WORLD_HALF_SIZE) / (GRID_N * 2 / 3);
        int idx = 0;
        for (int gz = 0; gz < GRID_N * 2 / 3; gz++) {
            for (int gx = 0; gx < GRID_N; gx++) {
                double jx = rng.nextDouble();
                double jz = rng.nextDouble();
                attractorX[idx] = -WORLD_HALF_SIZE + (gx + jx) * cellW;
                attractorZ[idx] = -WORLD_HALF_SIZE + (gz + jz) * cellH;
                idx++;
            }
        }

        List<double[]> segList = new ArrayList<>(N * K);
        double[] kdists = new double[K];
        int[] kneigh = new int[K];

        for (int i = 0; i < N; i++) {
            Arrays.fill(kdists, Double.MAX_VALUE);
            Arrays.fill(kneigh, -1);
            for (int j = 0; j < N; j++) {
                if (i == j) continue;
                double dx = attractorX[i] - attractorX[j];
                double dz = attractorZ[i] - attractorZ[j];
                double d2 = dx * dx + dz * dz;
                // maintain K smallest using simple scan
                // find the position where d2 < kdists[k]
                int insertAt = -1;
                for (int k = 0; k < K; k++) {
                    if (d2 < kdists[k]) {
                        insertAt = k; break;
                    }
                }
                if (insertAt >= 0) {
                    // shift existing values down
                    for (int k = K - 1; k > insertAt; k--) {
                        kdists[k] = kdists[k - 1];
                        kneigh[k] = kneigh[k - 1];
                    }
                    kdists[insertAt] = d2;
                    kneigh[insertAt] = j;
                }
            }
            for (int k = 0; k < K; k++) {
                if (kneigh[k] < 0) continue;
                int j = kneigh[k];
                segList.add(new double[]{
                    attractorX[i], attractorZ[i],
                    attractorX[j], attractorZ[j]
                });
            }
        }

        List<double[]> refined = new ArrayList<>(segList.size() * 2);
        for (double[] seg : segList) {
            double dx = seg[2] - seg[0];
            double dz = seg[3] - seg[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > SEG_SUBDIVIDE) {
                int parts = (int) Math.ceil(len / SEG_SUBDIVIDE);
                for (int p = 0; p < parts; p++) {
                    double t1 = p / (double) parts;
                    double t2 = (p + 1) / (double) parts;
                    refined.add(new double[]{
                        seg[0] + dx * t1, seg[1] + dz * t1,
                        seg[0] + dx * t2 + dx * 0.05,
                        seg[1] + dz * t2 + dz * 0.05
                    });
                }
            } else {
                refined.add(seg);
            }
        }

        segCount = refined.size();
        segData = new double[segCount * 4];
        for (int i = 0; i < segCount; i++) {
            double[] s = refined.get(i);
            segData[i * 4]     = s[0];
            segData[i * 4 + 1] = s[1];
            segData[i * 4 + 2] = s[2];
            segData[i * 4 + 3] = s[3];
        }

        spatialGrid = new HashMap<>(segCount * 4);
        for (int i = 0; i < segCount; i++) {
            addToGrid(i);
        }
    }

    public double ridgeBoost(double wx, double wz) {
        int cx = (int) Math.floor(wx / SPATIAL_CELL);
        int cz = (int) Math.floor(wz / SPATIAL_CELL);

        double minDistSq = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = packKey(cx + dx, cz + dz);
                int[] segs = spatialGrid.get(key);
                if (segs == null) continue;
                for (int s : segs) {
                    int si = s * 4;
                    double d2 = pointToSegmentDistSq(
                        wx, wz,
                        segData[si], segData[si + 1],
                        segData[si + 2], segData[si + 3]
                    );
                    if (d2 < minDistSq) minDistSq = d2;
                }
            }
        }

        if (minDistSq == Double.MAX_VALUE) return 0;
        double dist = Math.sqrt(minDistSq);
        if (dist >= FALLOFF) return 0;
        return 1.0 - dist / FALLOFF;
    }

    public int segmentCount() { return segCount; }

    private static double pointToSegmentDistSq(double px, double pz,
                                                double x1, double z1,
                                                double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            double ex = px - x1;
            double ez = pz - z1;
            return ex * ex + ez * ez;
        }
        double t = ((px - x1) * dx + (pz - z1) * dz) / len2;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double projX = x1 + t * dx;
        double projZ = z1 + t * dz;
        double ex = px - projX;
        double ez = pz - projZ;
        return ex * ex + ez * ez;
    }

    private void addToGrid(int segIdx) {
        int si = segIdx * 4;
        double x1 = segData[si];
        double z1 = segData[si + 1];
        double x2 = segData[si + 2];
        double z2 = segData[si + 3];

        int cx1 = (int) Math.floor(x1 / SPATIAL_CELL);
        int cz1 = (int) Math.floor(z1 / SPATIAL_CELL);
        int cx2 = (int) Math.floor(x2 / SPATIAL_CELL);
        int cz2 = (int) Math.floor(z2 / SPATIAL_CELL);

        int minCx = Math.min(cx1, cx2) - 1;
        int maxCx = Math.max(cx1, cx2) + 1;
        int minCz = Math.min(cz1, cz2) - 1;
        int maxCz = Math.max(cz1, cz2) + 1;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = packKey(cx, cz);
                int[] existing = spatialGrid.get(key);
                if (existing == null) {
                    spatialGrid.put(key, new int[]{segIdx});
                } else {
                    int[] grown = new int[existing.length + 1];
                    System.arraycopy(existing, 0, grown, 0, existing.length);
                    grown[existing.length] = segIdx;
                    spatialGrid.put(key, grown);
                }
            }
        }
    }

    private static long packKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
