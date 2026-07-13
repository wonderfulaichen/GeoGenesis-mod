package com.geogenesis.worldgen.terrain;

/**
 * 地形生成参数记录（不可变，由 GeoGenesisConfig 注入）。
 *
 * 参数分组：
 * - 大陆场：continentScale、warp、threshold
 * - 海洋样条：c-space 控制点位置 + e-space 深度 + 导数
 * - 海床细节：seabedDetail
 * - 海陆占比：continentBias
 * - 陆地省权重：provinceScale、四省软权重
 * - 地形过程形态：平原/丘陵/山脉/高原/盆地参数
 */
public record TerrainParams(
    // ===== 大陆场 =====
    /** 大陆噪声尺度（块），频率 = 1/scale，默认 1500 */
    double continentScale,
    /** 域扭曲强度，默认 0.2 */
    double continentWarp,
    /** 海陆阈值 c 值，默认 0.5 */
    double continentThreshold,
    /** 海陆占比偏置（正=更多海、负=更多陆），默认 0.0 */
    double continentBias,

    // ===== 海洋样条控制点（offset.json 式 cubic Hermite） =====
    // c-space 位置（深海→海洋→浅海→海岸）
    double deepOceanLoc,   // 默认 0.10; 深海起点
    double shelfLoc,       // 默认 0.25; 大陆架
    double shallowLoc,     // 默认 0.42; 浅海
    double coastLoc,       // 默认 0.48; 海岸线

    // e-space 深度（控制点 value）
    double deepOceanDepth, // 默认 -0.85; 深海深度
    double shelfDepth,     // 默认 -0.25; 大陆架深度
    double shallowDepth,   // 默认 -0.06; 浅海深度

    // 各控制点导数（MC 会×段宽）
    double deepOceanDeriv,
    double shelfDeriv,
    double shallowDeriv,
    double coastDeriv,

    // ===== 海床细节 =====
    /** 海床细节振幅（e 单位），默认 0.03 */
    double seabedDetail,

    // ===== 陆地省权重 =====
    /** 省场噪声尺度（块），默认 2000 */
    double provinceScale,
    /** softmax 克拉通权重，默认 1.0 */
    double cratonWeight,
    /** softmax 造山带权重，默认 1.0 */
    double beltWeight,
    /** softmax 高原权重，默认 1.0 */
    double plateauWeight,
    /** softmax 盆地权重，默认 0.8 */
    double basinWeight,

    // ===== 陆地过程形态 =====
    /** 平原基底高度 eLand，默认 0.01 */
    double plainBase,
    /** 平原粗糙度，默认 0.04 */
    double plainRough,
    /** 丘陵低段 eLand，默认 0.10 */
    double hillsLow,
    /** 丘陵高段 eLand，默认 0.25 */
    double hillsHigh,
    /** 山脉脊线强度，默认 1.5。<p>
     *  <b>已废弃</b>：Ridge 算子已被否决（山脊由 Hydraulic 河流切割产生，阶段 2）。
     *  引擎不再读取此参数（LandShape 改用 FBM 中频滚动）。保留占位供旧 config/mixer 兼容，
     *  阶段 2 Hydraulic 接入后可安全删除。 */
    double beltRidgePower,
    /** 山麓过渡宽度 eLand，默认 0.15 */
    double beltFoothill,
    /** 山峰高度 eLand，默认 0.95 */
    double beltPeak,
    /** 高原基底 eLand，默认 0.55 */
    double plateauBase,
    /** 高原顶部 eLand，默认 0.72 */
    double plateauTop,
    /** 高原阶梯数，默认 4。
     *  <b>已废弃</b>：Terrace 算子已否决（环状台阶伪影），引擎不再读取。
     *  保留占位供旧 config 兼容，阶段 2 Hydraulic 接入后可安全删除。 */
    int plateauSteps,
    /** 高原阶梯强度，默认 0.7。
     *  <b>已废弃</b>：同上。 */
    double plateauStepStrength,
    /** 盆地基底 eLand，默认 0.02 */
    double basinBase,

    // ===== 预览/旧 API 兼容参数 =====
    /** 水平缩放（预览用），默认 1 */
    double horizontalScale,
    /** 海平面 Y（预览/旧 API），默认 63 */
    int seaLevel,
    /** 雪线温度阈值，默认 0.3 */
    double snowLine,
    /** 世界最小 Y，默认 -64 */
    int minY,
    /** 世界最大 Y，默认 320 */
    int maxY,
    /** 山峰高度上限 Y，默认 256 */
    int mountainCap,
    /** 海沟深度下限 Y，默认 -48 */
    int trenchDepth
) {
    /** 生产级默认值（校准于非对称映射 e=0 → Y=63） */
    public static TerrainParams defaults() {
        return new TerrainParams(
            // continent
            1500.0, 0.2, 0.5, 0.0,
            // ocean spline locations
            0.10, 0.25, 0.42, 0.48,
            // ocean spline values
            -0.85, -0.25, -0.06,
            // ocean spline derivatives
            0.0, 0.0, 0.0, 0.0,
            // seabed
            0.03,
            // province
            2000.0, 1.0, 1.0, 1.0, 0.8,
            // land（校准于新映射：e→[seaLevel,maxY] 线性）
            0.01, 0.03, 0.07, 0.20,   // plainBase,plainRough,hillsLow,hillsHigh
            1.5, 0.10, 0.60,           // ridgePower,foothill,peak
            0.20, 0.35, 4, 0.7,        // platBase,platTop,steps,stepStrength
            0.02,                       // basinBase
            // preview compat
            1.0, 63, 0.3, -64, 320, 256, -48
        );
    }

    // ===== 便捷方法 =====

    /** 海洋样条 c 坐标 */
    public double[] oceanLocations() {
        return new double[]{deepOceanLoc, shelfLoc, shallowLoc, coastLoc};
    }

    /** 海洋样条深度值 */
    public double[] oceanValues() {
        return new double[]{deepOceanDepth, shelfDepth, shallowDepth, 0.0};
    }

    /** 海洋样条导数 */
    public double[] oceanDerivatives() {
        return new double[]{deepOceanDeriv, shelfDeriv, shallowDeriv, coastDeriv};
    }

    /** softmax 省权重 */
    public double[] provinceSoftmaxWeights() {
        return new double[]{cratonWeight, beltWeight, plateauWeight, basinWeight};
    }
}
