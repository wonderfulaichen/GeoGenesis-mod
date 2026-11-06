package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.HeightCurve;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 实测栅格方案：逐格只做正向 warp 取区域标签，再用精确欧氏距离变换求到各区域边界的世界距离。
 * 全程没有 warp 反解，也就没有反解失败这条路径。
 */
public final class RasterBoundaryProbe {
    private static final long SEED = 9139912035078620160L;
    private static final double ORIGIN_X = -3640, ORIGIN_Z = -3364;
    private static final int EVAL_STRIDE_BLOCKS = 20;

    private static final class Label {
        int x0, z0, w, h, entry;
        float[] progress;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        ForgeConfigSpec.loadFrozen(Path.of(args[0]));
        int texel = Integer.parseInt(args[1]);
        int size = 7000 / texel;
        int halo = 130 / texel + 2;

        RecipeMorphCatalog catalog = new RecipeMorphCatalog(SEED);
        FullMorphLand morph = new FullMorphLand(catalog);
        CombineCandidate original = catalog.original();
        LocalBoundaryGeometry warp = LocalBoundaryGeometry.of(original, 0.5, 0.5, 64, 2, 0.5);
        HeightCurve curve = new HeightCurve(GeoGenesisConfig.INSTANCE.buildParams(), -64, 320);

        long t0 = System.nanoTime();
        int[] labels = new int[size * size];
        Map<Long, Integer> index = new HashMap<>();
        List<long[]> cells = new ArrayList<>();
        for (int j = 0; j < size; j++) {
            double z = ORIGIN_Z + j * (double) texel;
            for (int i = 0; i < size; i++) {
                double x = ORIGIN_X + i * (double) texel;
                LocalBoundaryGeometry.Pick pick = warp.winnerCell(x, z);
                long key = LocalBoundaryGeometry.pack(pick.cellX(), pick.cellZ());
                Integer id = index.get(key);
                if (id == null) {
                    id = cells.size();
                    index.put(key, id);
                    cells.add(new long[]{pick.cellX(), pick.cellZ(), i, j, i, j});
                }
                labels[j * size + i] = id;
                long[] c = cells.get(id);
                c[2] = Math.min(c[2], i);
                c[3] = Math.min(c[3], j);
                c[4] = Math.max(c[4], i);
                c[5] = Math.max(c[5], j);
            }
        }
        long t1 = System.nanoTime();

        Label[] table = new Label[cells.size()];
        for (int id = 0; id < table.length; id++) {
            long[] c = cells.get(id);
            Label label = new Label();
            label.x0 = (int) Math.max(0, c[2] - halo);
            label.z0 = (int) Math.max(0, c[3] - halo);
            label.w = (int) Math.min(size - 1, c[4] + halo) - label.x0 + 1;
            label.h = (int) Math.min(size - 1, c[5] + halo) - label.z0 + 1;
            label.entry = catalog.slotEntry(warp.slotOfCell((int) c[0], (int) c[1]));
            label.progress = signedProgress(labels, size, id, label, texel);
            table[id] = label;
        }
        long t2 = System.nanoTime();

        int stride = EVAL_STRIDE_BLOCKS / texel;
        int count = 0;
        double[] dys = new double[size * size / (stride * stride) + 16];
        double[] l1s = new double[dys.length];
        double[] merged = new double[catalog.slotCount() > 0 ? 18 : 18];
        int total = 0;
        double worst = 0, worstX = 0, worstZ = 0;
        for (int j = halo; j < size - halo; j += stride) {
            double z = ORIGIN_Z + j * (double) texel;
            for (int i = halo; i < size - halo; i += stride) {
                double x = ORIGIN_X + i * (double) texel;
                total++;
                FullMorphLand.Tile tile = morph.tileAt(x, z);
                double[] exact = FullMorphLand.mergeToEntries(tile.entryOfSite(), tile.weights(x, z));
                Arrays.fill(merged, 0);
                double sum = 0;
                for (Label label : table) {
                    int lx = i - label.x0, lz = j - label.z0;
                    if (lx < 0 || lz < 0 || lx >= label.w || lz >= label.h) continue;
                    double p = label.progress[lz * label.w + lx];
                    merged[label.entry] += p;
                    sum += p;
                }
                for (int k = 0; k < merged.length; k++) merged[k] /= sum;
                boolean band = false;
                double l1 = 0;
                for (int k = 0; k < merged.length; k++) {
                    l1 += Math.abs(exact[k] - merged[k]);
                    if ((exact[k] > 0 && exact[k] < 1) || (merged[k] > 0 && merged[k] < 1)) band = true;
                }
                if (!band) continue;
                double dy = Math.abs(height(morph, curve, exact, x, z) - height(morph, curve, merged, x, z));
                dys[count] = dy;
                l1s[count] = l1;
                count++;
                if (dy > worst) {
                    worst = dy;
                    worstX = x;
                    worstZ = z;
                }
            }
        }
        double[] sortedDy = Arrays.copyOf(dys, count);
        double[] sortedL1 = Arrays.copyOf(l1s, count);
        Arrays.sort(sortedDy);
        Arrays.sort(sortedL1);
        int over1 = 0, over3 = 0;
        for (double d : sortedDy) {
            if (d > 1) over1++;
            if (d > 3) over3++;
        }
        System.out.printf("texel=%d size=%d labels=%d points=%d band=%d%n", texel, size, table.length, total, count);
        System.out.printf("label raster %.3f us/texel, distance transform %.3f us/texel%n",
                (t1 - t0) / 1e3 / (size * (double) size), (t2 - t1) / 1e3 / (size * (double) size));
        System.out.printf("weight L1 p50=%.4f p99=%.4f max=%.4f%n", q(sortedL1, 0.5), q(sortedL1, 0.99), sortedL1[count - 1]);
        System.out.printf("height |dY| p50=%.4f p90=%.4f p99=%.4f p99.9=%.4f max=%.4f blocks%n",
                q(sortedDy, 0.5), q(sortedDy, 0.9), q(sortedDy, 0.99), q(sortedDy, 0.999), sortedDy[count - 1]);
        System.out.printf("over 1 block: %d (%.3f%% of all)  over 3 blocks: %d (%.3f%% of all)%n",
                over1, 100.0 * over1 / total, over3, 100.0 * over3 / total);
        System.out.printf("worst dY=%.3f at x=%.1f z=%.1f%n", worst, worstX, worstZ);
    }

    private static double q(double[] sorted, double fraction) {
        return sorted[(int) (fraction * (sorted.length - 1))];
    }

    private static double height(FullMorphLand morph, HeightCurve curve, double[] merged, double x, double z) {
        double region = morph.heightForMerged(x, z, merged);
        return curve.heightFromE(Math.max(CombineCandidate.GROUND_E + region * CombineCandidate.E_SPAN, 0.0));
    }

    /** 标签内外各做一次距离变换，得到带符号世界距离后映射成过渡权重。 */
    private static float[] signedProgress(int[] labels, int size, int id, Label l, int texel) {
        double[] toOutside = new double[l.w * l.h];
        double[] toInside = new double[l.w * l.h];
        for (int z = 0; z < l.h; z++) {
            for (int x = 0; x < l.w; x++) {
                boolean inside = labels[(z + l.z0) * size + x + l.x0] == id;
                toOutside[z * l.w + x] = inside ? Double.POSITIVE_INFINITY : 0;
                toInside[z * l.w + x] = inside ? 0 : Double.POSITIVE_INFINITY;
            }
        }
        distanceTransform(toOutside, l.w, l.h);
        distanceTransform(toInside, l.w, l.h);
        float[] out = new float[l.w * l.h];
        double half = texel / 2.0;
        for (int i = 0; i < out.length; i++) {
            double sd = toInside[i] > 0
                    ? -(Math.sqrt(toInside[i]) * texel - half)
                    : Math.sqrt(toOutside[i]) * texel - half;
            out[i] = (float) LocalBoundaryGeometry.progress(sd);
        }
        return out;
    }

    private static void distanceTransform(double[] grid, int w, int h) {
        int span = Math.max(w, h);
        double[] line = new double[span], result = new double[span], z = new double[span + 1];
        int[] v = new int[span];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) line[y] = grid[y * w + x];
            transformLine(line, h, result, v, z);
            for (int y = 0; y < h; y++) grid[y * w + x] = result[y];
        }
        for (int y = 0; y < h; y++) {
            System.arraycopy(grid, y * w, line, 0, w);
            transformLine(line, w, result, v, z);
            System.arraycopy(result, 0, grid, y * w, w);
        }
    }

    /** Felzenszwalb 一维平方距离变换。 */
    private static void transformLine(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] == Double.POSITIVE_INFINITY) continue;
            double s = Double.NEGATIVE_INFINITY;
            while (k >= 0) {
                s = ((f[q] + q * (double) q) - (f[v[k]] + v[k] * (double) v[k])) / (2.0 * (q - v[k]));
                if (s > z[k]) break;
                k--;
            }
            k++;
            v[k] = q;
            z[k] = k == 0 ? Double.NEGATIVE_INFINITY : s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        if (k < 0) {
            Arrays.fill(d, 0, n, Double.POSITIVE_INFINITY);
            return;
        }
        int j = 0;
        for (int q = 0; q < n; q++) {
            while (z[j + 1] < q) j++;
            double dx = q - v[j];
            d[q] = dx * dx + f[v[j]];
        }
    }
}
