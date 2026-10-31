package com.geogenesis.worldgen.terrain.candidate;

import java.util.Arrays;

/** 独立18配方节点求值器；固定阶段顺序，不混合多族最终高度，不接完整沙箱。 */
final class RecipeJunctionMorph {
    private final RecipeMorphCatalog catalog;
    private final RefNoise.Node direction, erosion, surface, badX, badZ, scaler;
    private final RefNoise.Node terrace = new RefNoise.Terrace((v, z) -> v,
            .9f, .15f, .35f, .4f, 4, 0, .75f * .6f + .3f + .15f, false);
    private final RefNoise.Node steps4 = new RefNoise.Steps((v, z) -> v, 4, .6f, .7f, false);
    private final RefNoise.Node steps10 = new RefNoise.Steps((v, z) -> v, 10, .6f, .7f, false);
    private final RefNoise.Node shapeSteps = new RefNoise.Steps((v, z) -> v, 4, .65f, .75f, true);

    RecipeJunctionMorph(RecipeMorphCatalog catalog, long seed) {
        this.catalog = catalog;
        int m = base(seed + 77), p = base(seed + 44), b = base(seed + 55);
        direction = RefNoise.Perlin.ofScale(m + 3, 10, 1);
        erosion = new RefNoise.Erosion((x, z) -> (float) catalog.entry(17).raw(x, z),
                m + 4, 2, 1, 128, .15f, 3.1f, .8f);
        surface = RefNoise.warpPerlin(new RefNoise.Mul(RefNoise.Perlin.ofScale(p + 6, 20, 3),
                new RefNoise.Constant(.05f)), p + 7, 40, 2, 20);
        badX = RefNoise.Perlin.ofScale(b + 4, 100, 1);
        badZ = RefNoise.Perlin.ofScale(b + 5, 100, 1);
        scaler = RefNoise.Perlin.ofScale(b + 5, 200, 3);
    }

    double sample(double x, double z, int[] ids, double[] weights) {
        if (ids.length != weights.length || ids.length == 0) throw new IllegalArgumentException("ids/weights");
        double[] merged = new double[18];
        for (int id = 0; id < 18; id++) {
            double[] parts = new double[ids.length];
            int n = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] < 0 || ids[i] >= 18 || !Double.isFinite(weights[i]) || weights[i] < 0 || weights[i] > 1)
                    throw new IllegalArgumentException("unsupported recipe or weight");
                if (ids[i] == id) parts[n++] = weights[i];
            }
            Arrays.sort(parts, 0, n);
            for (int i = 0; i < n; i++) merged[id] += parts[i];
        }
        return sample(x, z, merged);
    }

    /** 已归一、已ease的真实RecipeId权重；同一配方相邻格先归并。 */
    double sample(double x, double z, double[] weights) {
        validate(weights);
        float fx = (float) x, fz = (float) z;
        float source = preBad(fx, fz, weights);
        float bad = (float) weights[16];
        if (bad != 0) source = mix(source, badShape(fx, fz, source, weights), bad);
        float[] domain = domain(fx, fz, (float) weights[17]);
        double clipping = 0;
        for (int i = 0; i < 18; i++) if (catalog.entry(i).clamped()) clipping += weights[i];
        float amplitude = parameter(domain[0], domain[1], weights, 1);
        float bias = parameter(domain[0], domain[1], weights, 2);
        double value = source * amplitude + bias;
        return value + (Math.max(value, 0) - value) * clipping;
    }

    private float preBad(float x, float z, double[] weights) {
        float mountain = (float) weights[17], plateau = (float) weights[15];
        float[] point = domain(x, z, mountain);
        float wx = point[0], wz = point[1];
        float source = parameter(wx, wz, weights, 0);
        if (plateau != 0) source = mix(source, terrace.compute(source, 0), plateau) + surface.compute(wx, wz) * plateau;
        if (mountain != 0) source = RefNoise.lerp(erosion.compute(wx, wz), source, 1 - .65f * mountain);
        return source;
    }

    private float parameter(float x, float z, double[] weights, int stage) {
        int first = -1, second = -1, count = 0;
        double sum = 0;
        for (int i = 0; i < 18; i++) {
            if (weights[i] == 0) continue;
            if (first < 0) first = i;
            else if (second < 0) second = i;
            count++;
            sum += weights[i] * parameter(catalog.entry(i), x, z, stage);
        }
        if (count == 1) return (float) parameter(catalog.entry(first), x, z, stage);
        // 双权重用原双族路径的float运算顺序，三向以上直接对称求和。
        if (count == 2) {
            double a = parameter(catalog.entry(first), x, z, stage);
            double b = parameter(catalog.entry(second), x, z, stage);
            return mix((float) a, (float) b, (float) weights[second]);
        }
        return (float) sum;
    }

    private static double parameter(RecipeMorphCatalog.Entry entry, float x, float z, int stage) {
        return switch (stage) {
            case 0 -> entry.raw(x, z);
            case 1 -> entry.amplitude(x, z);
            default -> entry.bias(x, z);
        };
    }

    private float[] domain(float x, float z, float weight) {
        if (weight == 0) return new float[]{x, z};
        float angle = direction.compute(x, z) * 6.2831855f;
        return new float[]{x + (float) Math.sin(angle) * (2 * weight), z + (float) Math.cos(angle) * (2 * weight)};
    }

    private float badShape(float x, float z, float source, double[] weights) {
        float ox = (badX.compute(x, z) - .5f) * 50, oz = (badZ.compute(x, z) - .5f) * 50;
        float modulation = preBad(x + ox, z + oz, weights) * .4f;
        float low = steps4.compute(source, 0) * .6f + modulation;
        float high = steps10.compute(source, 0) * .6f + modulation;
        float detail = (low + high) * .5f + .5f;
        float mod2 = source * (scaler.compute(x, z) * .4f);
        float shape = (shapeSteps.compute(source, 0) * .6f + mod2) * .6f;
        return shape * detail;
    }

    private static void validate(double[] w) {
        if (w.length != 18) throw new IllegalArgumentException("18 recipe weights required");
        double sum = 0;
        for (double v : w) {
            if (!Double.isFinite(v) || v < 0 || v > 1) throw new IllegalArgumentException("invalid weight");
            sum += v;
        }
        if (Math.abs(sum - 1) > 1e-9) throw new IllegalArgumentException("weights sum=" + sum);
    }

    private static float mix(float a, float b, float t) { return t == 0 ? a : t == 1 ? b : a + (b - a) * t; }
    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
}
