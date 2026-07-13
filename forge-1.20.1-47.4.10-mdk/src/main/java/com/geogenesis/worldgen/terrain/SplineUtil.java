package com.geogenesis.worldgen.terrain;

import java.util.Arrays;

/**
 * 零依赖 Cubic Hermite 样条工具。
 * 逐字节对齐 MC {@code net.minecraft.world.level.levelgen.synth.Spline#splint}。
 *
 * 关键特性（MC 已知行为）：
 * - 导数 {@code d} 会乘以段宽 {@code dx} 后再参与 Hermite 插值。
 * - 控制点按 location 升序排列（必须单调递增）。
 * - 外推：x < 首点 → 用首段首点值；x > 末点 → 用末段末点值。
 */
public final class SplineUtil {

    private SplineUtil() {}

    /**
     * Cubic Hermite 基函数。
     * t ∈ [0,1]，h00→出发点值权重，h10→出发点导数权重（已含×dx），
     * h01→终点值权重，h11→终点导数权重。
     */
    private static double h00(double t) { double t2 = t * t; return 2 * t2 * t - 3 * t2 + 1; }
    private static double h10(double t) { double t2 = t * t; return t2 * t - 2 * t2 + t; }
    private static double h01(double t) { double t2 = t * t; return -2 * t2 * t + 3 * t2; }
    private static double h11(double t) { double t2 = t * t; return t2 * t - t2; }

    /**
     * 在一组控制点之间做 cubic Hermite 插值。
     *
     * @param locations   控制点 x 坐标（必须升序）
     * @param values      控制点 y 值
     * @param derivatives 控制点导数（MC 会乘以段宽）
     * @param x           待求值的 x 坐标
     * @return 插值结果
     */
    public static double splint(double[] locations, double[] values,
                                double[] derivatives, double x) {
        if (locations.length < 2)
            return locations.length == 1 ? values[0] : 0.0;

        // 外推：x <= 首点
        if (x <= locations[0]) {
            double dx = locations[1] - locations[0];
            return dx > 0
                ? h00(0) * values[0] + h10(0) * derivatives[0] * dx
                  + h01(0) * values[1] + h11(0) * derivatives[1] * dx
                : values[0];
        }

        // 外推：x >= 末点
        int n = locations.length;
        if (x >= locations[n - 1]) {
            double dx = locations[n - 1] - locations[n - 2];
            return dx > 0
                ? h00(1) * values[n - 2] + h10(1) * derivatives[n - 2] * dx
                  + h01(1) * values[n - 1] + h11(1) * derivatives[n - 1] * dx
                : values[n - 1];
        }

        // 二分定位段 [i-1, i]
        int i = Arrays.binarySearch(locations, x);
        if (i < 0) i = -(i + 1);
        if (i <= 0) i = 1;

        double x0 = locations[i - 1];
        double x1 = locations[i];
        double dx = x1 - x0;
        if (dx <= 0) return values[i - 1]; // 退化为常量

        double t = (x - x0) / dx;
        // MC 特性：导数乘以段宽
        double d0 = derivatives[i - 1] * dx;
        double d1 = derivatives[i] * dx;

        return h00(t) * values[i - 1] + h10(t) * d0
             + h01(t) * values[i]     + h11(t) * d1;
    }
}
