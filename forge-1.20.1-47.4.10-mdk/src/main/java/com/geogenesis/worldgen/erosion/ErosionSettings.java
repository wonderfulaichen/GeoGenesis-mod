package com.geogenesis.worldgen.erosion;

/**
 * 多营力侵蚀参数记录（v1 局部算子强度 + 几何门控）。
 * 运行期由 GeoGenesisConfig「Erosion」段注入；暂用 {@link #defaults()}。
 */
public record ErosionSettings(
    /** 坡积软化（Thermal）强度，0=关闭 */
    double thermalStrength,
    /** 海岸冲刷（Coastal）强度，0=关闭 */
    double coastalStrength,
    /** 冰川侵蚀（Glacial）强度（v2，暂留桩） */
    double glacialStrength,
    /** 风蚀（Wind）强度（v2，暂留桩） */
    double windStrength,
    /** 安息角对应的单块最大坡度（e 单位/块） */
    double talusAngle,
    /** 海岸带半宽（e 单位），|e|<band 处做海蚀/海滩磨圆 */
    double coastBand
) {
    public static ErosionSettings defaults() {
        return new ErosionSettings(
            0.3,   // thermalStrength
            0.5,   // coastalStrength
            0.0,   // glacialStrength（v2）
            0.0,   // windStrength（v2）
            0.08,  // talusAngle
            0.06   // coastBand
        );
    }
}
