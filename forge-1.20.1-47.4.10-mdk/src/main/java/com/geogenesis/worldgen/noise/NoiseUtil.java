package com.geogenesis.worldgen.noise;

/** 噪声计算用的纯数学工具，无外部依赖。 */
public final class NoiseUtil {

    private NoiseUtil() {
    }

    public static double floor(double v) {
        return Math.floor(v);
    }

    public static double fract(double v) {
        return v - Math.floor(v);
    }

    /** 线性插值。 */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 平滑插值（Hermite / smoothstep）。 */
    public static double smooth(double v) {
        return v * v * (3.0 - 2.0 * v);
    }

    /** 平滑插值（quintic，更自然）。 */
    public static double smoother(double v) {
        return v * v * v * (v * (v * 6.0 - 15.0) + 10.0);
    }

    /**
     * 非均匀 Cubic Hermite / Catmull-Rom 样条插值。
     * 过所有控制点 (T[i], P[i])，内部节点导数非零，避免 smoothstep 在节点处的 flat spot。
     * 端点采用 clamped 切线（首/末段斜率），保证整体 C1 连续。
     */
    public static double spline(double x, double[] T, double[] P) {
        if (x <= T[0]) {
            return P[0];
        }
        int n = T.length - 1;
        if (x >= T[n]) {
            return P[n];
        }
        int i = 0;
        while (i < n && x > T[i + 1]) {
            i++;
        }
        double dt = T[i + 1] - T[i];
        double u = (x - T[i]) / dt;
        // 节点切线：内部用 chordal Catmull-Rom，端点用 clamped
        double m0 = (i == 0)
                ? (P[1] - P[0]) / (T[1] - T[0])
                : (P[i + 1] - P[i - 1]) / (T[i + 1] - T[i - 1]);
        double m1 = (i == n - 1)
                ? (P[n] - P[n - 1]) / (T[n] - T[n - 1])
                : (P[i + 2] - P[i]) / (T[i + 2] - T[i]);
        double dp0 = m0 * dt;
        double dp1 = m1 * dt;
        double u2 = u * u;
        double u3 = u2 * u;
        double h00 = 2.0 * u3 - 3.0 * u2 + 1.0;
        double h10 = u3 - 2.0 * u2 + u;
        double h01 = -2.0 * u3 + 3.0 * u2;
        double h11 = u3 - u2;
        return h00 * P[i] + h10 * dp0 + h01 * P[i + 1] + h11 * dp1;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static double saturate(double v) {
        return clamp(v, 0.0, 1.0);
    }

    /**
     * 32-bit 整数哈希，返回 [-1,1]。
     * 基于整数乘法与位混淆（Stefan Gustavson 风格），确定性、无分配。
     */
    public static double hash2(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        // 归一化到 [-1,1]
        return (h * (1.0 / 0x7fffffff)) ;
    }

    /** 32-bit 整数哈希，返回 [0,1)。 */
    public static double hash2_01(int x, int y) {
        return hash2(x, y) * 0.5 + 0.5;
    }

    /**
     * 基于长整型种子的整数哈希（用于置换表生成）。
     * 返回 [0, 1) 的 double。
     */
    public static int hashInt(long seed, int v) {
        long h = seed ^ ((long) v * 0x9e3779b97f4a7c15L);
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return (int) (h & 0xffffffffL);
    }
}
