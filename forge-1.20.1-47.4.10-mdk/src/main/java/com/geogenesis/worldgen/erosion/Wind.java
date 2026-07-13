package com.geogenesis.worldgen.erosion;

/**
 * 风蚀（Wind）：留桩（v2 follow-up）。
 *
 * <p>设计：干旱（低湿度）门控的吹蚀/风积。当前强度默认 0，不修改高度场；
 * 待阶段 5 气候门控（湿度）接入后启用。
 */
public final class Wind implements ErosionAgent {

    @Override
    public void apply(double[][] e, int size, int pad, ErosionSettings s) {
        if (s.windStrength() <= 0.0) return;
        // v2：干旱区风蚀（留桩）
    }
}
