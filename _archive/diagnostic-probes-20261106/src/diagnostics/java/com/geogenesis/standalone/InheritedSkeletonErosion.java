package com.geogenesis.standalone;

import com.geogenesis.standalone.AdaptiveSkeletonErosion.HeightField;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Sample;
import com.geogenesis.standalone.AdaptiveSkeletonErosion.Settings;

import java.util.Objects;

/**
 * Isolated stage B: the unchanged stage-A kernels read the actual, weighted coarse displacement.
 * For four layers, F_j = raw + beta * amplitude / 1.875 * sum_{k<j}(0.5^k * E_k).
 * sample returns unscaled E with A's gain/normalization; the caller still applies amplitude once.
 * The approved comparison uses amplitude=0.25 and beta=1, without threshold or RMS recalibration.
 *
 * <p>Every core retains the full settings and original seed/layer hash. Its cache is separate, and
 * descriptor construction reads only strictly lower cores: no recursive update of the same map,
 * no cycles and no per-pixel rebuilding of descriptor trees. The raw field must be immutable,
 * deterministic and thread-safe. Inheritance alone does not establish connected drainage.</p>
 */
public final class InheritedSkeletonErosion {
    private final HeightField raw;
    private final double amplitude;
    private final double beta;
    private final double normalization;
    private final double[] wavelengths;
    private final AdaptiveSkeletonErosion[] cores;

    public InheritedSkeletonErosion(long seed, HeightField raw, Settings settings,
                                    double amplitude, double beta) {
        this.raw = Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(settings, "settings");
        finite(amplitude, "amplitude");
        finite(beta, "beta");
        this.amplitude = amplitude;
        this.beta = beta;
        this.wavelengths = settings.wavelengths();
        double weight = 0.0, gain = 1.0;
        for (int j = 0; j < wavelengths.length; j++) {
            weight += gain;
            gain *= 0.5;
        }
        this.normalization = weight;
        this.cores = new AdaptiveSkeletonErosion[wavelengths.length];
        for (int j = 0; j < cores.length; j++) {
            final int layer = j;
            cores[j] = new AdaptiveSkeletonErosion(seed, (x, z) -> guideHeight(layer, x, z), settings);
        }
    }

    /** Same fixed accumulation order, channels, bounds and gain normalization as stage A. */
    public Sample sample(double x, double z) {
        finite(x, "x");
        finite(z, "z");
        double value = 0.0, activity = 0.0, confidence = 0.0, weight = 0.0;
        double gain = 1.0, denominatorMin = Double.POSITIVE_INFINITY;
        for (int j = 0; j < cores.length; j++) {
            Sample s = sampleLayer(j, x, z);
            value += gain * s.value();
            activity += gain * s.activity();
            confidence += gain * s.confidence();
            denominatorMin = StrictMath.min(denominatorMin, s.denominatorMin());
            weight += gain;
            gain *= 0.5;
        }
        return new Sample(bounded(value / weight, -1.0, 1.0),
                bounded(activity / weight, 0.0, 1.0),
                bounded(confidence / weight, 0.0, 1.0), denominatorMin);
    }

    /** Never uses core.sample(): keeping the original layer index preserves centres and hashes. */
    public Sample sampleLayer(int layer, double x, double z) {
        checkLayer(layer);
        return cores[layer].sampleLayer(layer, x, z);
    }

    /** Actual coarse contribution to the carrier, not an unweighted or renormalized prefix. */
    public double guideHeight(int layer, double x, double z) {
        checkLayer(layer);
        finite(x, "x");
        finite(z, "z");
        double base = raw.height(x, z);
        if (beta == 0.0 || layer == 0) return base;
        double sum = 0.0, gain = 1.0;
        for (int k = 0; k < layer; k++) {
            sum += gain * cores[k].sampleLayer(k, x, z).value();
            gain *= 0.5;
        }
        return base + beta * amplitude / normalization * sum;
    }

    /**
     * Prebuild coarse-to-fine. A layer-j centre can be lambda[j] outside its query bbox, and its
     * 5x5 guide stencil reaches another lambda[j]/2; propagate that entire halo to every k<j.
     * A tiny per-edge ULP guard affects only prebuild coverage, never descriptor values or queries.
     */
    public void prepare(double minX, double minZ, double maxX, double maxZ) {
        finite(minX, "minX");
        finite(minZ, "minZ");
        finite(maxX, "maxX");
        finite(maxZ, "maxZ");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("reversed bbox");
        double[] req = new double[cores.length];
        if (beta != 0.0) {
            for (int j = cores.length - 1; j > 0; j--) {
                double reach = req[j] + 1.5 * wavelengths[j];
                finite(reach, "dependency halo");
                for (int k = 0; k < j; k++) req[k] = StrictMath.max(req[k], reach);
            }
        }
        double scale = StrictMath.max(StrictMath.max(StrictMath.abs(minX), StrictMath.abs(maxX)),
                StrictMath.max(StrictMath.abs(minZ), StrictMath.abs(maxZ)));
        for (int j = 0; j < cores.length; j++) {
            scale = StrictMath.max(scale, StrictMath.max(wavelengths[j], req[j]));
        }
        double guard = 16.0 * StrictMath.ulp(scale);
        for (int j = 0; j < cores.length; j++) {
            double margin = req[j];
            if (beta != 0.0) margin += (cores.length - 1 - j) * guard;
            cores[j].prepareLayer(j, minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }
    }

    /** Actual geometric sum of this layer's activity/confidence coefficients. */
    public double layerCapacity(int layer, double x, double z) {
        checkLayer(layer);
        return cores[layer].layerCapacity(layer, x, z);
    }

    /** Counts immutable descriptors, not sample results; concurrent observations are not a barrier. */
    public long descriptorCount() {
        long count = 0;
        for (AdaptiveSkeletonErosion core : cores) count += core.descriptorCount();
        return count;
    }

    public long descriptorCount(int layer) {
        checkLayer(layer);
        return cores[layer].descriptorCount();
    }

    /** Retains input/seed/settings identity; like A, this is not a concurrent-query barrier. */
    public void clearCache() {
        for (AdaptiveSkeletonErosion core : cores) core.clearCache();
    }

    private void checkLayer(int layer) {
        if (layer < 0 || layer >= cores.length) {
            throw new IllegalArgumentException("layer out of range: " + layer);
        }
    }

    private static double bounded(double value, double lo, double hi) {
        return StrictMath.max(lo, StrictMath.min(hi, value));
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
