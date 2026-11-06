package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 实测局部一阶边界距离能否替代 warp 反解折线。
 * 对每点：|位势差| / |世界空间位势差梯度| 估出到各区域边界的距离，算权重后走真实高度链。
 */
public final class BoundaryApproxProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double JACOBIAN_STEP = 4.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        ForgeConfigSpec.loadFrozen(Path.of(args[0]));
        int step = Integer.parseInt(args[1]);

        RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
        FullMorphLand morph = new FullMorphLand(catalog);
        CombineCandidate original = catalog.original();
        LocalBoundaryGeometry warp = LocalBoundaryGeometry.of(original, 0.5, 0.5, 64, 2, 0.5);
        HeightCurve curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);

        long total = 0;
        int capacity = 8000000;
        double[] l1s = new double[capacity];
        double[] dys = new double[capacity];
        int band = 0;
        double worstY = 0, worstX = 0, worstZ = 0, worstL1 = 0;
        long exactNanos = 0, approxNanos = 0;
        for (double z = -3365.5; z <= 3634.5; z += step) {
            for (double x = -3642.5; x <= 3357.5; x += step) {
                total++;
                FullMorphLand.Tile tile = morph.tileAt(x, z);
                long t0 = System.nanoTime();
                double[] exact = tile.weights(x, z);
                long t1 = System.nanoTime();
                double[] approx = approxWeights(tile, warp, x, z);
                long t2 = System.nanoTime();
                exactNanos += t1 - t0;
                approxNanos += t2 - t1;
                double l1 = 0;
                for (int i = 0; i < exact.length; i++) l1 += Math.abs(exact[i] - approx[i]);
                boolean inBand = false;
                for (double w : exact) if (w > 0 && w < 1) inBand = true;
                for (double w : approx) if (w > 0 && w < 1) inBand = true;
                if (!inBand) continue;
                double dy = Math.abs(heightOf(morph, curve, tile, exact, x, z) - heightOf(morph, curve, tile, approx, x, z));
                l1s[band] = l1;
                dys[band] = dy;
                band++;
                if (dy > worstY) {
                    worstY = dy;
                    worstX = x;
                    worstZ = z;
                    worstL1 = l1;
                }
            }
        }
        double[] sortedL1 = Arrays.copyOf(l1s, band);
        double[] sortedDy = Arrays.copyOf(dys, band);
        Arrays.sort(sortedL1);
        Arrays.sort(sortedDy);
        System.out.printf("points=%d band=%d (%.2f%%)%n", total, band, 100.0 * band / total);
        System.out.printf("weight L1 p50=%.4f p99=%.4f max=%.4f%n", at(sortedL1, 0.5), at(sortedL1, 0.99), sortedL1[band - 1]);
        System.out.printf("height |dY| p50=%.4f p90=%.4f p99=%.4f p99.9=%.4f max=%.4f blocks%n",
                at(sortedDy, 0.5), at(sortedDy, 0.9), at(sortedDy, 0.99), at(sortedDy, 0.999), sortedDy[band - 1]);
        int over1 = 0, over3 = 0;
        for (double d : sortedDy) {
            if (d > 1) over1++;
            if (d > 3) over3++;
        }
        System.out.printf("over 1 block: %d (%.3f%% of all)  over 3 blocks: %d (%.3f%% of all)%n",
                over1, 100.0 * over1 / total, over3, 100.0 * over3 / total);
        System.out.printf("worst dY=%.3f at x=%.1f z=%.1f L1=%.4f%n", worstY, worstX, worstZ, worstL1);
        System.out.printf("weights time per point: exact=%.2f us approx=%.2f us (probe uses O(n^2) on all sites)%n",
                exactNanos / 1e3 / total, approxNanos / 1e3 / total);
    }

    private static double at(double[] sorted, double q) {
        return sorted[(int) (q * (sorted.length - 1))];
    }

    private static double heightOf(FullMorphLand morph, HeightCurve curve, FullMorphLand.Tile tile,
                                   double[] siteWeights, double x, double z) {
        double[] merged = FullMorphLand.mergeToEntries(tile.entryOfSite(), siteWeights);
        double region = morph.heightForMerged(x, z, merged);
        double e = Math.max(CombineCandidate.GROUND_E + region * CombineCandidate.E_SPAN, 0.0);
        return curve.heightFromE(e);
    }

    private static double[] approxWeights(FullMorphLand.Tile tile, LocalBoundaryGeometry warp, double x, double z) {
        double[] p = warp.warpForward(x, z);
        double[] xp = warp.warpForward(x + JACOBIAN_STEP, z), xm = warp.warpForward(x - JACOBIAN_STEP, z);
        double[] zp = warp.warpForward(x, z + JACOBIAN_STEP), zm = warp.warpForward(x, z - JACOBIAN_STEP);
        double ux = (xp[0] - xm[0]) / (2 * JACOBIAN_STEP), vx = (xp[1] - xm[1]) / (2 * JACOBIAN_STEP);
        double uz = (zp[0] - zm[0]) / (2 * JACOBIAN_STEP), vz = (zp[1] - zm[1]) / (2 * JACOBIAN_STEP);

        int n = tile.siteU.length;
        double[] pot = new double[n], gu = new double[n], gv = new double[n];
        int win = 0;
        for (int i = 0; i < n; i++) {
            double dx = tile.siteU[i] - p[0], dy = tile.siteV[i] - p[1];
            pot[i] = LocalBoundaryGeometry.potential(tile.siteU[i], tile.siteV[i], p[0], p[1]);
            gu[i] = -Math.signum(dx) - 2 * dx;
            gv[i] = -Math.signum(dy) - 2 * dy;
            if (pot[i] < pot[win]) win = i;
        }

        double[] wt = new double[n];
        double sum = 0;
        for (int j = 0; j < n; j++) {
            double sd;
            if (j == win) {
                double best = Double.POSITIVE_INFINITY;
                for (int k = 0; k < n; k++) {
                    if (k != win) best = Math.min(best, distance(pot, gu, gv, k, win, ux, uz, vx, vz));
                }
                sd = best;
            } else {
                double worst = 0;
                for (int k = 0; k < n; k++) {
                    if (k != j && pot[j] > pot[k]) worst = Math.max(worst, distance(pot, gu, gv, j, k, ux, uz, vx, vz));
                }
                sd = -worst;
            }
            wt[j] = LocalBoundaryGeometry.progress(sd);
            sum += wt[j];
        }
        if (sum > 0) for (int i = 0; i < n; i++) wt[i] /= sum;
        return wt;
    }

    /** 点到 a、b 分界的一阶距离：位势差除以世界空间梯度模长。 */
    private static double distance(double[] pot, double[] gu, double[] gv, int a, int b,
                                   double ux, double uz, double vx, double vz) {
        double dg0 = gu[a] - gu[b], dg1 = gv[a] - gv[b];
        double wx = dg0 * ux + dg1 * vx, wz = dg0 * uz + dg1 * vz;
        double len = Math.sqrt(wx * wx + wz * wz);
        return len > 0 ? (pot[a] - pot[b]) / len : Double.POSITIVE_INFINITY;
    }
}
