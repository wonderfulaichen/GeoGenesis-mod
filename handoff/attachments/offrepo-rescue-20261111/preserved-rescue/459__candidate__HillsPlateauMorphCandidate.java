package com.geogenesis.worldgen.terrain.candidate;

/**
 * 独立双族原型，不被 Gallery/Combine/生产链引用。
 * 两个固定信号在 Terrace 之前对应；台面算子强度、细节强度、振幅随参数改变。
 * 不是两份成品高度的 lerp，但仍包含原始信号层混合，不能声称没有相消。
 */
final class HillsPlateauMorphCandidate {
    static final double HALF_WIDTH = 120.0;
    private final RefNoise.Node hillsBody;
    private final RefNoise.Node plateauBase;
    private final RefNoise.Node surface;
    private final RefNoise.Node terrace;

    HillsPlateauMorphCandidate(long seed) {
        long hillSeed = seed + 11;
        int h = (int) (hillSeed ^ (hillSeed >>> 32));
        RefNoise.Node body = RefNoise.Perlin.ofScale(h, 200f, 3);
        RefNoise.Node envelope = new RefNoise.AlphaMix(
                RefNoise.Billow.ofScale(h + 1, 650f, 3, 2f, 0.5f), 0.5f);
        body = new RefNoise.Mul(body, envelope);
        body = RefNoise.warpPerlin(body, h + 2, 30f, 3, 20f);
        hillsBody = RefNoise.warpPerlin(body, h + 3, 400f, 3, 200f);

        long plateauSeed = seed + 44;
        int p = (int) (plateauSeed ^ (plateauSeed >>> 32));
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        plateau.seed(plateauSeed);
        plateauBase = (x, z) -> (float) plateau.debugBase(x, z);
        RefNoise.Node detail = new RefNoise.Mul(
                RefNoise.Perlin.ofScale(p + 6, 20f, 3), new RefNoise.Constant(0.05f));
        surface = RefNoise.warpPerlin(detail, p + 7, 40f, 2, 20f);
        // Terrace 是值域算子；用 (x=value,z=0) 传值，复用原实现而不修改共享类。
        // 参考 Cubic 的声明上界为 0.75；值域元数据沿配方传递到 base 上界 0.9。
        terrace = new RefNoise.Terrace((value, unused) -> value,
                0.9f, 0.15f, 0.35f, 0.4f, 4, 0f, 0.75f * 0.6f + 0.3f + 0.15f, false);
    }

    double sample(double x, double z, double position) {
        float t = (float) ease(position);
        float a = hillsBody.compute((float) x, (float) z);
        if (t == 0f) return a * 0.6f;
        float b = plateauBase.compute((float) x, (float) z);
        float source = mix(a, b, t);
        float shaped = mix(source, terraceValue(source), t);
        float detail = surface.compute((float) x, (float) z) * t;
        return (shaped + detail) * mix(0.6f, 0.475f, t);
    }

    float terraceValue(float source) {
        return terrace.compute(source, 0f);
    }

    /** 世界坐标中人工设置的直线边界：[-120,+120] 是唯一过渡带。 */
    double spatial(double x, double z, double centerX) {
        return sample(x, z, (x - centerX + HALF_WIDTH) / (2.0 * HALF_WIDTH));
    }

    static double ease(double p) {
        if (p <= 0) return 0;
        if (p >= 1) return 1;
        return p * p * (3.0 - 2.0 * p);
    }

    private static float mix(float a, float b, float t) {
        if (t == 0f) return a;
        if (t == 1f) return b;
        return a + (b - a) * t;
    }
}
