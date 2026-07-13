package com.geogenesis.worldgen.erosion;

/**
 * 冰川侵蚀（Glacial）：留桩（v2 follow-up）。
 *
 * <p>设计：高海拔（e 高）+ 低温门控的 U 形谷冰川侵蚀。当前强度默认 0，
 * 不修改高度场；待阶段 5 气候门控（温度/纬度）接入后启用。
 */
public final class Glacial implements ErosionAgent {

    @Override
    public void apply(double[][] e, int size, int pad, ErosionSettings s) {
        if (s.glacialStrength() <= 0.0) return;
        // v2：高海拔 U 形谷冰川侵蚀（留桩）
    }
}
