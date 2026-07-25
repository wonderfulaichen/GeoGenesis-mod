package com.geogenesis.worldgen.terrain;

/**
 * 火山形态共享形状库 — 海洋海山与陆地火山共用的纯数学剖面 / 几何工具。
 *
 * <p>设计（去圆化 + 地理真实）：
 * <ul>
 *   <li>三种剖面：CONE（锥形，strato/shield 基础）、GUYOT（平顶）、CALDERA（环状破火山口）。</li>
 *   <li>各向异性旋转：每座火山随机朝向 ang + 椭圆比 (asx,asz)，打破完美圆。</li>
 *   <li>域扭曲由调用方在采样前对坐标做（VolcanicShape 只吃已扭曲的局部坐标）。</li>
 *   <li>火口下凹 crater()：单体火山顶部挖下凹火口（中心最深），可形成火山口湖洼地。</li>
 * </ul>
 * 零 MC 依赖、纯数学，便于单元测试与海洋 / 陆地复用。
 */
public final class VolcanicShape {
    public static final int CONE = 0;
    public static final int GUYOT = 1;
    public static final int CALDERA = 2;

    private VolcanicShape() {}

    /**
     * 各向异性旋转：把 (dx,dz) 绕原点旋转 ang，再按椭圆比 (asx,asz) 缩放 → 归一化局部坐标。
     */
    public static double[] anisoRotate(double dx, double dz, double ang, double asx, double asz) {
        double cos = Math.cos(ang), sin = Math.sin(ang);
        double rx = dx * cos - dz * sin;
        double rz = dx * sin + dz * cos;
        double sx = asx <= 0 ? 1.0 : asx;
        double sz = asz <= 0 ? 1.0 : asz;
        return new double[]{rx / sx, rz / sz};
    }

    /**
     * 单座火山在归一化距离 d (= 局部坐标模 / radius) 处的剖面增量（含边界软包络，无断裂）。
     *
     * @param shapeType CONE / GUYOT / CALDERA
     * @param d        归一化径向距离（0=中心，1=边缘）
     * @param amp      中心最大抬升（e 单位）
     * @return ≥0 的抬升增量（边界平滑至 0）
     */
    public static double profile(int shapeType, double d, double amp) {
        if (d >= 1.0) return 0.0;
        double base;
        switch (shapeType) {
            case GUYOT: // 平顶：中心平、边缘 smoothstep 陡降（shield volcano / guyot）
                base = amp * (1.0 - smoothstep(0.45, 1.0, d));
                break;
            case CALDERA: { // 环状破火山口：环峰高、中心 caldera 坑低
                double ring = Math.exp(-((d - 0.55) * (d - 0.55)) / (2 * 0.22 * 0.22));
                double pit = 0.45 * Math.exp(-(d * d) / (2 * 0.3 * 0.3));
                double c = ring - pit;
                base = c > 0 ? amp * c : 0.0;
                break;
            }
            case CONE:
            default: // 锥形：高斯径向衰减（stratovolcano 陡、broad shield 用低 asx 近似）
                base = amp * Math.exp(-(d * d) / (2 * 0.35 * 0.35));
                break;
        }
        // 边界软包络：d∈[0.85,1.0] 平滑衰减到 0，杜绝硬边界断裂（尤其 CALDERA 外圈）
        double env = 1.0 - smoothstep(0.85, 1.0, d);
        return base * env;
    }

    /**
     * 火口下凹：在 d < craterRadiusFrac 范围内从中心向下挖 craterDepth（中心最深、边缘 0）。
     * 调用方从 profile() 结果中减去返回值，形成顶部下凹火口（可成火山口湖洼地）。
     */
    public static double crater(double d, double craterRadiusFrac, double craterDepth) {
        if (craterDepth <= 0.0 || d >= craterRadiusFrac) return 0.0;
        // 中心 1 → 边缘 0（在 [0.4R, R] 区间平滑）
        double t = 1.0 - smoothstep(craterRadiusFrac * 0.4, craterRadiusFrac, d);
        return craterDepth * t;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = x <= e0 ? 0.0 : (x >= e1 ? 1.0 : (x - e0) / (e1 - e0));
        return t * t * (3.0 - 2.0 * t);
    }
}
