package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 海岸剖面切线探针：打印 {@code height(edge)} 及其一二阶导数，定位折角。
 *
 * <p>判据：折角 = 一阶导数跳变；尖角 = 二阶导数爆掉。两者的视觉表现分别是"棱"与"尖"。</p>
 *
 * <p>剖面按地貌学分沙岸 / 崖岸两种（见 {@code OceanAssemblyCandidate.shoreShape}）：
 * <pre>
 * 沙岸 cliff=0：t&lt;0.25 抬到 12%（滩面+滩肩），之后缓升（海岸平原）
 * 崖岸 cliff=1：t&lt;0.65 只抬到 2%（海蚀平台），0.65–0.85 猛升（崖面），之后台地
 * </pre>
 */
public final class CoastalProfileProbe {

    private static final float CP_SHELF = 0.20f;
    private static final float CP_SHORE = 0.268f;
    private static final float CP_ANCHOR = 0.302f;
    private static final double SHALLOW = -7.0;
    private static final double LAND_B = 28.0;

    // 与 OceanAssemblyCandidate 同步
    private static final float FLAT_END_BEACH = 0.25f;
    private static final float FLAT_END_CLIFF = 0.65f;
    private static final double FLAT_BLOCKS_BEACH = 3.5;
    private static final double FLAT_BLOCKS_CLIFF = 3.0;
    private static final float STEEP_END_BEACH = 1.00f;
    private static final float STEEP_END_CLIFF = 0.85f;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int n = 6000;
        double lo = 0.0, hi = 0.32;
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = lo + (hi - lo) * i / (n - 1);

        for (double cliffLevel : new double[]{0.0, 0.5, 1.0}) {
            System.out.printf(Locale.ROOT,
                    "%n===== cliffFactor = %.1f（%s）=====%n", cliffLevel,
                    cliffLevel < 0.25 ? "沙岸" : (cliffLevel > 0.75 ? "崖岸" : "过渡"));
            double maxJump = 0, jumpAt = 0, maxAbsD2 = 0, d2At = 0;
            double prevD = Double.NaN;
            for (int i = 1; i < n - 2; i++) {
                double h0 = profile(e[i - 1], cliffLevel);
                double h1 = profile(e[i], cliffLevel);
                double h2 = profile(e[i + 1], cliffLevel);
                double h3 = profile(e[i + 2], cliffLevel);
                double d = (h2 - h0) / (e[2] - e[0]);
                double d2 = (h3 - 2 * h2 + h1) / Math.pow(e[1] - e[0], 2);
                if (!Double.isNaN(prevD)) {
                    double jump = Math.abs(d - prevD);
                    if (jump > maxJump) { maxJump = jump; jumpAt = e[i]; }
                }
                if (Math.abs(d2) > maxAbsD2) { maxAbsD2 = Math.abs(d2); d2At = e[i]; }
                prevD = d;

                boolean key = Math.abs(e[i] - CP_SHELF) < 0.0004
                        || Math.abs(e[i] - CP_SHORE) < 0.0004
                        || Math.abs(e[i] - CP_ANCHOR) < 0.0004
                        || Math.abs(e[i] % 0.02) < 0.0002;
                if (key) {
                    System.out.printf(Locale.ROOT, "  edge=%.4f  h=%8.3f  h'=%9.2f  h''=%12.1f%n",
                            e[i], h1, d, d2);
                }
            }
            System.out.printf(Locale.ROOT, "  ★ 最大 |Δh'| = %.2f @ edge=%.4f%n", maxJump, jumpAt);
            System.out.printf(Locale.ROOT, "  ★ 最大 |h''| = %.1f @ edge=%.4f%n", maxAbsD2, d2At);
        }
    }

    private static double profile(double edge, double cliff) {
        double deep = -63.0;
        if (edge < CP_SHELF) {
            double t = clamp01((0.0 - edge) / (0.0 - CP_SHELF));
            return lerp(deep, SHALLOW, Math.pow(smooth(t), 1.2));
        }
        if (edge < CP_SHORE) {
            double t = (edge - CP_SHELF) / (CP_SHORE - CP_SHELF);
            return lerp(SHALLOW, 0.0, smooth(t));
        }
        double t = clamp01((edge - CP_SHORE) / (CP_ANCHOR - CP_SHORE));
        return shoreShape(t, cliff, LAND_B);
    }

    private static double shoreShape(double t, double cliff, double landB) {
        double flatEnd = lerp(FLAT_END_BEACH, FLAT_END_CLIFF, cliff);
        double steepEnd = lerp(STEEP_END_BEACH, STEEP_END_CLIFF, cliff);
        double flatBlocks = Math.min(lerp(FLAT_BLOCKS_BEACH, FLAT_BLOCKS_CLIFF, cliff), landB * 0.5);
        if (t <= flatEnd) {
            double u = flatEnd > 1e-6 ? t / flatEnd : 1.0;
            return flatBlocks * smooth(u);
        }
        if (t >= steepEnd) return landB;
        double u = (t - flatEnd) / Math.max(1e-6, steepEnd - flatEnd);
        return flatBlocks + (landB - flatBlocks) * smooth(u);
    }

    private static double smooth(double t) {
        double c = t < 0 ? 0 : (t > 1 ? 1 : t);
        return c * c * (3 - 2 * c);
    }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}