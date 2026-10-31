package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;

/** B2独立包装；宽度仍读原B1，偏移不改变世界噪声采样坐标。 */
final class OffsetTransitionCandidate {
    static final double MAX_OFFSET = 160, MAX_REACH = 520;
    private final AdaptiveWidthCandidate widthSource;
    private final FourPairMorphCandidate morph;
    private final HeightCurve curve;
    private final double center;
    record Guide(double width, double offset) { }

    OffsetTransitionCandidate(long seed, FourPairMorphCandidate.Pair pair, HeightCurve curve, double center) {
        this.widthSource = new AdaptiveWidthCandidate(seed, pair, curve, center);
        this.morph = new FourPairMorphCandidate(seed, pair);
        this.curve = curve;
        this.center = center;
    }

    Guide guide(double z) {
        double offset = 0;
        int[] weights = {1, 4, 6, 4, 1};
        for (int j = -2; j <= 2; j++) offset += weights[j + 2] * rawOffset(z + j * 64) / 16;
        return new Guide(widthSource.guide(z).width(), offset);
    }

    private double rawOffset(double z) {
        double sum = 0, position = 0;
        // 固定候选、无argmin切换。相容度越高权重越大；中心先验抑制整体漂移。
        for (int i = -4; i <= 4; i++) {
            double dx = i * 40;
            double cost = mismatch(center + dx, z);
            double prior = Math.exp(-0.5 * dx * dx / (120 * 120));
            double weight = prior * Math.exp(-cost / 32);
            sum += weight;
            position += weight * dx;
        }
        return position / sum;
    }

    private double mismatch(double x, double z) {
        double ma = 0, mb = 0, aa = 0, bb = 0;
        int[] weights = {1, 2, 1};
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                double w = weights[i + 1] * weights[j + 1] / 16.0;
                double a = y(morph.sample(x + i * 32, z + j * 32, 0));
                double b = y(morph.sample(x + i * 32, z + j * 32, 1));
                ma += w * a; mb += w * b; aa += w * a * a; bb += w * b * b;
            }
        }
        double gap = roundedAbs(ma - mb, 2);
        double ra = Math.sqrt(Math.max(0, aa - ma * ma) + 1);
        double rb = Math.sqrt(Math.max(0, bb - mb * mb) + 1);
        return gap + .75 * roundedAbs(ra - rb, 1);
    }

    double at(double x, double z, double width, double offset) {
        return morph.sample(x, z, position(x, width, offset));
    }

    double sample(double x, double z) {
        if (Math.abs(x - center) >= MAX_REACH) return at(x, z, 240, 0);
        Guide g = guide(z);
        return at(x, z, g.width(), g.offset());
    }

    double position(double x, double width, double offset) {
        return Math.max(0, Math.min(1, .5 + (x - center - offset) / width));
    }

    double y(double h) { return curve.heightFromE(Math.max(.02 + 1.5 * h, 0)); }
    private static double roundedAbs(double v, double r) { return Math.sqrt(v * v + r * r) - r; }
}
