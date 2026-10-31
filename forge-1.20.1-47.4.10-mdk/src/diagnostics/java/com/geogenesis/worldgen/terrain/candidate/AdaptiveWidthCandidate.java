package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;

/** B1 独立包装：只改变空间进度，不改配方、中心或随机序列；无跨采样缓存。 */
final class AdaptiveWidthCandidate {
    static final double MIN_WIDTH = 240, MAX_WIDTH = 720;
    private final FourPairMorphCandidate morph;
    private final HeightCurve curve;
    private final double center;
    private long endpointCalls;
    record Guide(double width, double heightGap, double roughnessGap) { }

    AdaptiveWidthCandidate(long seed, FourPairMorphCandidate.Pair pair, HeightCurve curve, double center) {
        morph = new FourPairMorphCandidate(seed, pair);
        this.curve = curve;
        this.center = center;
    }

    Guide guide(double z) {
        double ma = 0, mb = 0, aa = 0, bb = 0;
        int[] normalWeights = {1, 2, 1}, tangentWeights = {1, 4, 6, 4, 1};
        for (int j = -2; j <= 2; j++) {
            for (int i = -1; i <= 1; i++) {
                double w = normalWeights[i + 1] * tangentWeights[j + 2] / 64.0;
                double a = y(morph.sample(center + i * 64, z + j * 64, 0));
                double b = y(morph.sample(center + i * 64, z + j * 64, 1));
                endpointCalls += 2;
                ma += w * a;
                mb += w * b;
                aa += w * a * a;
                bb += w * b * b;
            }
        }
        double gap = roundedAbs(ma - mb, 2);
        double ra = Math.sqrt(Math.max(0, aa - ma * ma) + 1);
        double rb = Math.sqrt(Math.max(0, bb - mb * mb) + 1);
        double roughGap = roundedAbs(ra - rb, 1);
        double score = gap + 0.75 * roughGap;
        double alpha = score * score / (score * score + 80 * 80);
        return new Guide(MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * alpha, gap, roughGap);
    }

    double sample(double x, double z) {
        // 最大包络外无需引导采样，保持原族核心。
        if (x <= center - MAX_WIDTH / 2) return atWidth(x, z, MIN_WIDTH);
        if (x >= center + MAX_WIDTH / 2) return atWidth(x, z, MIN_WIDTH);
        return atWidth(x, z, guide(z).width());
    }

    double atWidth(double x, double z, double width) {
        return morph.sample(x, z, position(x, width));
    }

    double position(double x, double width) {
        return Math.max(0, Math.min(1, 0.5 + (x - center) / width));
    }

    double y(double h) { return curve.heightFromE(Math.max(0.02 + 1.5 * h, 0)); }
    long endpointCalls() { return endpointCalls; }
    private static double roundedAbs(double v, double radius) { return Math.sqrt(v * v + radius * radius) - radius; }
}
