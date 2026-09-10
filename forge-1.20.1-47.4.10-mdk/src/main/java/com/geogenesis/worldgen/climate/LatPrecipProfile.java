package com.geogenesis.worldgen.climate;

/**
 * 纬度降水廓线（气候态，★ 2026-09-11 Phase B）。
 *
 * <p>降水不是纬度的单调函数：真实的纬向平均降水是<b>多峰</b>的 ——
 * <b>赤道辐合带(ITCZ) 多雨 → 副热带高压干旱 → 中纬西风带次峰 → 极地干冷</b>。
 * 这与 {@link WindField} 的气压带一一对应（多雨处 = 上升支/低压，干旱处 = 下沉支/高压）。</p>
 *
 * <p>与"降水只是湿度的别名"（改造前 `WhittakerType` 借用 humidity）不同：
 * 本廓线提供<b>纬度维度的独立结构</b>，再接湿度、地形雨/雨影，才构成完整降水。</p>
 */
public final class LatPrecipProfile {

    private LatPrecipProfile() {}

    /** 控制点：lat01 → 降水倍率（相对值，均值 ≈ 0.8）。 */
    private static final double[] LAT = {0.00, 0.30, 0.55, 0.80, 1.00};
    private static final double[] VAL = {1.40, 0.30, 1.00, 0.35, 0.25};

    /**
     * 采样降水倍率。
     *
     * <p>控制点之间用 smoothstep 插值（端点导数为 0）→ <b>C1 连续、无折线</b>，
     * 避免在控制点纬度出现"直线状降水边界"（本项目在温度/群系上反复踩过的坑）。</p>
     */
    public static double at(double lat01) {
        double t = lat01 < 0.0 ? 0.0 : (lat01 > 1.0 ? 1.0 : lat01);
        for (int i = 0; i < LAT.length - 1; i++) {
            if (t <= LAT[i + 1]) {
                double u = (t - LAT[i]) / (LAT[i + 1] - LAT[i]);
                u = u * u * (3.0 - 2.0 * u);
                return VAL[i] + (VAL[i + 1] - VAL[i]) * u;
            }
        }
        return VAL[VAL.length - 1];
    }
}
