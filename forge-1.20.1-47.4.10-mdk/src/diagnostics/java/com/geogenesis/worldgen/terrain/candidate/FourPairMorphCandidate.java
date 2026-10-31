package com.geogenesis.worldgen.terrain.candidate;

/** 独立配方节点对应实验；不被 Gallery、Combine 或生产链引用。 */
final class FourPairMorphCandidate {
    enum Pair {
        PLAIN_HILLS, HILLS_MOUNTAINS, PLATEAU_MOUNTAINS, PLATEAU_BADLANDS,
        PLAIN_PLATEAU, PLAIN_MOUNTAINS, PLAIN_BADLANDS, HILLS_BADLANDS, MOUNTAINS_BADLANDS
    }
    private final Pair pair;
    private final RefNoise.Node plains, hills, mountain, direction, erosion;
    private final RefNoise.Node plateauBase, surface, badlands, badWarpX, badWarpZ, badScaler;
    private final RefNoise.Node terrace = new RefNoise.Terrace((v, z) -> v,
            0.9f, 0.15f, 0.35f, 0.4f, 4, 0f, 0.75f * 0.6f + 0.3f + 0.15f, false);
    private final RefNoise.Node steps4 = new RefNoise.Steps((v, z) -> v, 4, 0.6f, 0.7f, false);
    private final RefNoise.Node steps10 = new RefNoise.Steps((v, z) -> v, 10, 0.6f, 0.7f, false);
    private final RefNoise.Node shapeSteps = new RefNoise.Steps((v, z) -> v, 4, 0.65f, 0.75f, true);

    FourPairMorphCandidate(long seed, Pair pair) {
        this.pair = pair;
        int p = base(seed), h = base(seed + 11), m = base(seed + 77), b = base(seed + 55);
        RefNoise.Node plain = new RefNoise.Mul(RefNoise.Perlin.ofScale(p + 3, 250f, 1),
                new RefNoise.AlphaMix(RefNoise.Perlin.ofScale(p, 500f, 3, 3.75f), 0.45f));
        plain = RefNoise.warp(plain, RefNoise.Perlin.ofScale(p + 1, 62f, 1, 3.5f),
                RefNoise.Perlin.ofScale(p + 2, 62f, 1, 3.5f), 62.5f);
        plains = RefNoise.warpPerlin(plain, p + 4, 256f, 1, 64f);
        RefNoise.Node hill = new RefNoise.Mul(RefNoise.Perlin.ofScale(h, 200f, 3),
                new RefNoise.AlphaMix(RefNoise.Billow.ofScale(h + 1, 650f, 3, 2f, 0.5f), 0.5f));
        hill = RefNoise.warpPerlin(hill, h + 2, 30f, 3, 20f);
        hills = RefNoise.warpPerlin(hill, h + 3, 400f, 3, 200f);
        RefNoise.Node ridge = new RefNoise.PerlinRidge(m, 1f / 610f, 4, 2.35f, 1.15f);
        ridge = new RefNoise.Mul(ridge,
                new RefNoise.AlphaMix(RefNoise.Perlin.ofScale(m + 1, 180f, 4), 0.30f));
        mountain = RefNoise.warpPerlin(ridge, m + 2, 350f, 1, 150f);
        direction = RefNoise.Perlin.ofScale(m + 3, 10f, 1);
        // 路由只消费固定山地源，不随过渡参数重选最低邻点。
        erosion = new RefNoise.Erosion(mountain, m + 4, 2, 1f, 128f, 0.15f, 3.1f, 0.8f);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        plateau.seed(seed + 44);
        plateauBase = (x, z) -> (float) plateau.debugBase(x, z);
        int q = base(seed + 44);
        RefNoise.Node detail = new RefNoise.Mul(RefNoise.Perlin.ofScale(q + 6, 20f, 3),
                new RefNoise.Constant(0.05f));
        surface = RefNoise.warpPerlin(detail, q + 7, 40f, 2, 20f);
        RefNoise.Node mask = new RefNoise.Mapped(new RefNoise.Clamped(
                RefNoise.Perlin.ofScale(b, 270f, 3), 0.35f, 0.65f), 0.35f, 0.65f, 0f, 1f);
        RefNoise.Node bad = RefNoise.PerlinRidge.ofScale(b + 1, 275f, 4);
        bad = RefNoise.warpPerlin(bad, b + 2, 400f, 2, 100f);
        badlands = new RefNoise.Mul(RefNoise.warpPerlin(bad, b + 3, 18f, 1, 20f), mask);
        badWarpX = RefNoise.Perlin.ofScale(b + 4, 100f, 1);
        badWarpZ = RefNoise.Perlin.ofScale(b + 5, 100f, 1);
        badScaler = RefNoise.Perlin.ofScale(b + 5, 200f, 3);
    }

    double sample(double x, double z, double position) {
        float t = (float) HillsPlateauMorphCandidate.ease(position);
        float wx = (float) x, wz = (float) z;
        if (pair == Pair.PLAIN_PLATEAU) {
            float source = mix(plains.compute(wx, wz), plateauBase.compute(wx, wz), t);
            float shaped = mix(source, terrace.compute(source, 0), t) + surface.compute(wx, wz) * t;
            return shaped * mix(0.15f, 0.475f, t) + mix(-0.02f, 0f, t);
        }
        if (pair == Pair.PLAIN_MOUNTAINS) {
            float angle = direction.compute(wx, wz) * 6.2831855f;
            wx += (float) Math.sin(angle) * (2f * t);
            wz += (float) Math.cos(angle) * (2f * t);
            float source = mix(plains.compute(wx, wz), mountain.compute(wx, wz), t);
            if (t != 0f) source = RefNoise.lerp(erosion.compute(wx, wz), source, 1f - 0.65f * t);
            return source * mix(0.15f, 0.7f, t) + mix(-0.02f, 0f, t);
        }
        if (pair == Pair.PLAIN_BADLANDS || pair == Pair.HILLS_BADLANDS || pair == Pair.MOUNTAINS_BADLANDS) {
            return otherBadlands(wx, wz, t);
        }
        if (pair == Pair.PLAIN_HILLS) {
            float source = mix(plains.compute(wx, wz), hills.compute(wx, wz), t);
            return source * mix(0.15f, 0.6f, t) + mix(-0.02f, 0f, t);
        }
        if (pair == Pair.PLATEAU_BADLANDS) {
            float source = plateauBadSource(wx, wz, t);
            float shaped = mix(source, badShape(wx, wz, source, t), t);
            return shaped * mix(0.475f, 0.55f, t) + 0.025f * t;
        }
        if (t != 0f) {
            float angle = direction.compute(wx, wz) * 6.2831855f;
            wx += (float) Math.sin(angle) * (2f * t);
            wz += (float) Math.cos(angle) * (2f * t);
        }
        float source = pair == Pair.HILLS_MOUNTAINS
                ? mix(hills.compute(wx, wz), mountain.compute(wx, wz), t)
                : mix(plateauBase.compute(wx, wz), mountain.compute(wx, wz), t);
        if (pair == Pair.PLATEAU_MOUNTAINS) {
            source = mix(source, terrace.compute(source, 0), 1f - t)
                    + surface.compute(wx, wz) * (1f - t);
        }
        // 与参考 fancy 同顺序：固定侵蚀纹理渐入，再乘原配方振幅。
        if (t != 0f) source = RefNoise.lerp(erosion.compute(wx, wz), source, 1f - 0.65f * t);
        return source * mix(pair == Pair.HILLS_MOUNTAINS ? 0.6f : 0.475f, 0.7f, t);
    }

    private float otherBadlands(float x, float z, float t) {
        float source = otherBadSource(x, z, t);
        float shaped = mix(source, badShape(x, z, source, t), t);
        float amplitude = switch (pair) {
            case PLAIN_BADLANDS -> 0.15f;
            case HILLS_BADLANDS -> 0.6f;
            default -> 0.7f;
        };
        return shaped * mix(amplitude, 0.55f, t)
                + mix(pair == Pair.PLAIN_BADLANDS ? -0.02f : 0f, 0.025f, t);
    }

    private float otherBadSource(float x, float z, float t) {
        if (pair == Pair.PLAIN_BADLANDS) return mix(plains.compute(x, z), badlands.compute(x, z), t);
        if (pair == Pair.HILLS_BADLANDS) return mix(hills.compute(x, z), badlands.compute(x, z), t);
        // 固定山地路由与方向扰动随山地权重淡出；不对混合信号重新选择侵蚀邻居。
        float strength = 1f - t;
        float angle = direction.compute(x, z) * 6.2831855f;
        float wx = x + (float) Math.sin(angle) * (2f * strength);
        float wz = z + (float) Math.cos(angle) * (2f * strength);
        float source = mix(mountain.compute(wx, wz), badlands.compute(wx, wz), t);
        if (strength != 0f) source = RefNoise.lerp(erosion.compute(wx, wz), source, 1f - 0.65f * strength);
        return source;
    }

    private float plateauBadSource(float x, float z, float t) {
        float source = mix(plateauBase.compute(x, z), badlands.compute(x, z), t);
        return mix(source, terrace.compute(source, 0), 1f - t) + surface.compute(x, z) * (1f - t);
    }

    private float badShape(float x, float z, float source, float t) {
        float ox = (badWarpX.compute(x, z) - 0.5f) * 50f;
        float oz = (badWarpZ.compute(x, z) - 0.5f) * 50f;
        float mod1 = (pair == Pair.PLATEAU_BADLANDS ? plateauBadSource(x + ox, z + oz, t)
                : otherBadSource(x + ox, z + oz, t)) * 0.4f;
        float low = steps4.compute(source, 0) * 0.6f + mod1;
        float high = steps10.compute(source, 0) * 0.6f + mod1;
        float detail = (low + high) * 0.5f + 0.5f;
        float mod2 = source * (badScaler.compute(x, z) * 0.4f);
        float shape = (shapeSteps.compute(source, 0) * 0.6f + mod2) * 0.6f;
        return shape * detail;
    }

    double spatial(double x, double z, double center) {
        return sample(x, z, (x - center + 120) / 240);
    }

    private static int base(long seed) { return (int) (seed ^ (seed >>> 32)); }

    private static float mix(float a, float b, float t) {
        if (t == 0f) return a;
        if (t == 1f) return b;
        return a + (b - a) * t;
    }
}
