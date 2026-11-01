package com.geogenesis.worldgen.terrain.candidate;

import java.util.Arrays;

/** 唯一节点形态内核。陆地保留历史float路径；含海洋时线性外壳统一为e。 */
final class NodeMorphEngine {
    private final MorphRecipe[] recipes;
    private final RefNoise.Node direction, erosion, surface, badX, badZ, scaler;
    private final RefNoise.Node terrace = new RefNoise.Terrace((v, z) -> v,
            .9f, .15f, .35f, .4f, 4, 0, .75f * .6f + .3f + .15f, false);
    private final RefNoise.Node steps4 = new RefNoise.Steps((v, z) -> v, 4, .6f, .7f, false);
    private final RefNoise.Node steps10 = new RefNoise.Steps((v, z) -> v, 10, .6f, .7f, false);
    private final RefNoise.Node shapeSteps = new RefNoise.Steps((v, z) -> v, 4, .65f, .75f, true);

    NodeMorphEngine(MorphRecipe[] recipes, long seed) {
        this.recipes = recipes.clone();
        int m = base(seed + 77), p = base(seed + 44), b = base(seed + 55);
        direction = RefNoise.Perlin.ofScale(m + 3, 10, 1);
        MorphRecipe mountain = Arrays.stream(recipes).filter(r -> r.role() == MorphRecipe.Role.MOUNTAIN)
                .findFirst().orElse(null);
        erosion = new RefNoise.Erosion((x, z) -> mountain == null ? 0f : (float) mountain.raw(x, z),
                m + 4, 2, 1, 128, .15f, 3.1f, .8f);
        surface = RefNoise.warpPerlin(new RefNoise.Mul(RefNoise.Perlin.ofScale(p + 6, 20, 3),
                new RefNoise.Constant(.05f)), p + 7, 40, 2, 20);
        badX = RefNoise.Perlin.ofScale(b + 4, 100, 1);
        badZ = RefNoise.Perlin.ofScale(b + 5, 100, 1);
        scaler = RefNoise.Perlin.ofScale(b + 5, 200, 3);
    }

    double[] merge(int[] ids, double[] weights) {
        if (ids.length != weights.length || ids.length == 0) throw new IllegalArgumentException("ids/weights");
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] >= recipes.length || !Double.isFinite(weights[i])
                    || weights[i] < 0 || weights[i] > 1) throw new IllegalArgumentException("recipe/weight");
        }
        double[] merged = new double[recipes.length];
        for (int id = 0; id < recipes.length; id++) {
            double[] parts = new double[ids.length];
            int n = 0;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) parts[n++] = weights[i];
            Arrays.sort(parts, 0, n);
            for (int i = 0; i < n; i++) merged[id] += parts[i];
        }
        return merged;
    }

    double sampleE(double x, double z, double[] weights) {
        Evaluation e = new Evaluation(weights);
        double value = e.sample(x, z);
        return e.legacy ? Math.max(CombineCandidate.GROUND_E + CombineCandidate.E_SPAN * value, 0) : value;
    }

    double sampleLand(double x, double z, double[] weights) {
        Evaluation e = new Evaluation(weights);
        if (!e.legacy) throw new IllegalArgumentException("land-only weights required");
        return e.sample(x, z);
    }

    private final class Evaluation {
        final double[] weights;
        final boolean legacy;
        Evaluation(double[] weights) {
            if (weights.length != recipes.length) throw new IllegalArgumentException("recipe count");
            double sum = 0;
            boolean landOnly = true;
            for (int i = 0; i < weights.length; i++) {
                double w = weights[i];
                if (!Double.isFinite(w) || w < 0 || w > 1) throw new IllegalArgumentException("weight");
                sum += w;
                if (w > 0 && !recipes[i].land()) landOnly = false;
            }
            if (Math.abs(sum - 1) > 1e-9) throw new IllegalArgumentException("weights sum=" + sum);
            this.weights = weights;
            legacy = landOnly;
        }

        double sample(double x, double z) {
            if (legacy) { x = (float) x; z = (float) z; }
            double source = preBad(x, z);
            float bad = role(MorphRecipe.Role.BADLANDS);
            if (bad != 0) source = blend(source, badShape((float) x, (float) z, (float) source), bad);
            double[] domain = domain(x, z, role(MorphRecipe.Role.MOUNTAIN));
            double amp = parameter(domain[0], domain[1], 1), bias = parameter(domain[0], domain[1], 2);
            double value = legacy ? (float) source * (float) amp + (float) bias : source * amp + bias;
            double clipping = strength(0);
            if (legacy) return value + (Math.max(value, 0) - value) * clipping;
            // 内部max(h,0)在e中对应GROUND_E；外层陆地max(e,0)与海洋上限分开。
            value += (Math.max(value, CombineCandidate.GROUND_E) - value) * clipping;
            value += (Math.max(value, 0) - value) * strength(1);
            return value + (Math.min(value, 0) - value) * strength(2);
        }

        double preBad(double x, double z) {
            float mountain = role(MorphRecipe.Role.MOUNTAIN), plateau = role(MorphRecipe.Role.PLATEAU);
            double[] point = domain(x, z, mountain);
            double source = parameter(point[0], point[1], 0);
            if (plateau != 0) source = rounded(blend(source, terrace.compute((float) source, 0), plateau)
                    + rounded(surface.compute((float) point[0], (float) point[1]) * plateau));
            if (mountain != 0) source = legacy
                    ? RefNoise.lerp(erosion.compute((float) point[0], (float) point[1]), (float) source, 1 - .65f * mountain)
                    : blend(erosion.compute((float) point[0], (float) point[1]), source, 1 - .65f * mountain);
            return source;
        }

        double parameter(double x, double z, int stage) {
            int first = -1, second = -1, count = 0;
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) {
                if (weights[i] == 0) continue;
                if (first < 0) first = i;
                else if (second < 0) second = i;
                count++;
                sum += weights[i] * parameter(recipes[i], x, z, stage);
            }
            if (count == 1) return rounded(parameter(recipes[first], x, z, stage));
            if (count == 2) return blend(parameter(recipes[first], x, z, stage),
                    parameter(recipes[second], x, z, stage), legacy ? (float) weights[second] : weights[second]);
            return rounded(sum);
        }

        double parameter(MorphRecipe recipe, double x, double z, int stage) {
            double value = switch (stage) {
                case 0 -> recipe.raw(x, z);
                case 1 -> recipe.amplitude(x, z);
                default -> recipe.bias(x, z);
            };
            if (!legacy && recipe.land() && stage > 0) {
                value *= CombineCandidate.E_SPAN;
                if (stage == 2) value += CombineCandidate.GROUND_E;
            }
            return value;
        }

        float role(MorphRecipe.Role role) {
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) if (recipes[i].role() == role) sum += weights[i];
            return (float) sum;
        }

        double strength(int kind) {
            double sum = 0;
            for (int i = 0; i < recipes.length; i++) {
                MorphRecipe r = recipes[i];
                if (kind == 0 ? r.clamped() : kind == 1 ? r.land() : r.ceiling()) sum += weights[i];
            }
            return sum;
        }

        double[] domain(double x, double z, float weight) {
            if (weight == 0) return new double[]{x, z};
            float fx = (float) x, fz = (float) z;
            float angle = direction.compute(fx, fz) * 6.2831855f;
            float dx = (float) Math.sin(angle) * (2 * weight), dz = (float) Math.cos(angle) * (2 * weight);
            if (legacy) return new double[]{fx + dx, fz + dz};
            // 原陆地端点保留float域；其坐标舍入残差随山地参与量归零，避免海洋端点跳位。
            float targetX = fx + (float) Math.sin(angle) * 2, targetZ = fz + (float) Math.cos(angle) * 2;
            return new double[]{blend(x, targetX, weight), blend(z, targetZ, weight)};
        }

        float badShape(float x, float z, float source) {
            float ox = (badX.compute(x, z) - .5f) * 50, oz = (badZ.compute(x, z) - .5f) * 50;
            float modulation = (float) preBad(x + ox, z + oz) * .4f;
            float low = steps4.compute(source, 0) * .6f + modulation;
            float high = steps10.compute(source, 0) * .6f + modulation;
            float detail = (low + high) * .5f + .5f;
            float mod2 = source * (scaler.compute(x, z) * .4f);
            float shape = (shapeSteps.compute(source, 0) * .6f + mod2) * .6f;
            return shape * detail;
        }

        double rounded(double value) { return legacy ? (float) value : value; }
        double blend(double a, double b, double t) {
            return legacy ? mix((float) a, (float) b, (float) t) : t == 0 ? a : t == 1 ? b : a + (b - a) * t;
        }
    }

    private static float mix(float a, float b, float t) { return t == 0 ? a : t == 1 ? b : a + (b - a) * t; }
    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }
}
