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
    /** 海陆参考阈值 c 值（[-1,1] 区间），默认 0.0 */
    double continentThreshold,
    /** 海陆占比偏置（正=更多海、负=更多陆），默认 0.0 */
    double continentBias,
    /** 大陆场 warp 省噪声坐标幅度（块）。用大陆性 c 偏移省噪声采样坐标，使每大陆获得独特且连贯的地质组合（确定性、无随机），默认 2000.0 */
    double continentProvinceWarp,

    // ===== 海洋样条控制点（offset.json 式 cubic Hermite） =====
    // c-space 位置（c∈[-1,1]：深海→大陆架→浅海→海岸）
    double deepOceanLoc,   // 默认 -0.80; 深海起点（最深洋盆）
    double shelfLoc,       // 默认 -0.50; 大陆架外缘
    double shallowLoc,     // 默认 -0.16; 浅海
    double coastLoc,       // 默认 -0.04; 海岸线（样条锚定 e=0）

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

    // ===== 地形起伏振幅（每省固有属性，侵蚀前基础地形的局部起伏场） =====
    /** 克拉通起伏振幅（e 单位），默认 0.10（保 HILLS 分类；密集感靠频率放大消除，非砍振幅） */
    double cratonReliefAmp,
    /** 造山带起伏振幅，默认 0.22（保 MOUNTAINS 分类；原 0.30，适度下调，指纹感靠 LandShape 频率放大解决） */
    double beltReliefAmp,
    /** 高原起伏振幅，默认 0.03（平顶台地，低起伏） */
    double plateauReliefAmp,
    /** 盆地起伏振幅，默认 0.05 */
    double basinReliefAmp,

    // ===== 省场形态曲线（每省独立 shapeᵢ(rᵢ)，零中心噪声 rᵢ∈[-1,1]；shapeᵢ(0)=0 谷底=噪声真零 rᵢ=0，shapeᵢ(1)=1 峰顶=|rᵢ|=1；禁用零交叉脊） =====
    /** 造山带尖钝度（pow exponent on zero-centered noise |r|^k），默认 1.3（>1 尖峰、=1 圆顶、<1 宽缓）。用于 belt pow(|r|,beltSharpness) + warp。 */
    double beltSharpness,
    /** 造山带域扭曲幅度（世界坐标空间位移，块），默认 120.0。warp 产生蜿蜒山岭，禁用零交叉脊函数。 */
    double beltWarpAmp,
    /** 省混合锐化指数（softmax 权重幂次），默认 2.5。>1 让主导省占更大权重、完整表达其 |r|=1 峰 / r=0 谷形态，省边界仍平滑过渡。 */
    double provMixSharpness,

    // ===== 分层叠加新增（对标 TerraForged） =====
    /** 山脉遮罩噪声尺度（块），maskNoise 频率 = 1/mountainMaskScale，默认 2200。低频团块限定山脉区域（带状/团块分布，真实感关键）。 */
    double mountainMaskScale,
    /** 表面纹理噪声尺度（块），microDetail 频率 = 1/microDetailScale，默认 450。大周期极小振幅，表面舒展非密纹。 */
    double microDetailScale,
    /** 表面纹理振幅（e 单位），默认 0.025。微小值（<0.03），纹理级而非地形级。 */
    double microDetailAmp,

    // ===== 分类阈值 + 雪线 =====
    /** 高海拔阈值（elevation），默认 0.25。不再用于 Voronoi 类型分配（气候色板替代），保留占位。 */
    double elevHigh,
    /** 高起伏阈值（|relief|），默认 0.04。不再用于 Voronoi 类型分配，保留占位。 */
    double reliefHigh,
    /** 峰阈值（elevation），默认 0.82。不再用于 Voronoi 类型分配，保留占位。 */
    double peakE,
    /** 雪线纬度耦合强度，默认 0.25 */
    double snowLatitudeInfluence,

    // ===== 预览/旧 API 兼容参数 =====
    /** 水平缩放（预览用），默认 1 */
    double horizontalScale,
    /** 海平面 Y（预览/旧 API），默认 63 */
    int seaLevel,
    /** 雪线海拔（e 单位，高海拔侧积雪覆盖），默认 0.70 */
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
            // continent（continentProvinceWarp 默认 0=禁用，域扭曲实验结论见 CellGenerator）
            4000.0, 0.2, 0.0, 0.0, 0.0,
            // ocean spline locations (c∈[-1,1]; 负=深海、0=海岸锚点)
            -0.80, -0.50, -0.16, -0.04,
            // ocean spline values
            -0.85, -0.25, -0.06,
            // ocean spline derivatives
            0.0, 0.0, 0.0, 0.0,
            // seabed
            0.03,
            // province
            4000.0, 1.0, 1.0, 1.0, 0.8,
            // land（校准于新映射：e→[seaLevel,maxY] 线性）
            0.01, 0.03, 0.10, 0.25,   // plainBase,plainRough,hillsLow,hillsHigh
            1.5, 0.15, 0.95,           // ridgePower,foothill,peak
            0.55, 0.72, 4, 0.7,        // platBase,platTop,steps,stepStrength
            0.02,                       // basinBase
            // relief amplitudes（每省固有起伏，侵蚀前基础地形）
            // 振幅只适度下调（保 MOUNTAINS/HILLS 分类需 relief≥0.1）；"噪声太小"主要靠 LandShape 频率放大周期消除
            0.10, 0.22, 0.03, 0.05,    // cratonReliefAmp,beltReliefAmp,plateauReliefAmp,basinReliefAmp
            // province shape curves（每省独立 shapeᵢ(rᵢ)：零中心 r，谷在 r=0、峰在 |r|=1）
            1.5, 120.0, 2.5,            // beltSharpness,beltWarpAmp,provMixSharpness
            // layered overlay params（分层叠加新增，对标 TerraForged）
            2200.0, 450.0, 0.025,       // mountainMaskScale,microDetailScale,microDetailAmp
            // classification thresholds + snow latitude coupling
            0.25, 0.04, 0.82, 0.25,    // elevHigh↑0.25,reliefHigh↑0.04,peakE,snowLatitudeInfluence

            // preview compat（加大 horizontalScale 减少 preview 计算量）
            4.0, 63, 0.70, -64, 320, 256, -48
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
