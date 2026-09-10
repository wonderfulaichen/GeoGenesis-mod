package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.climate.Climate;
import com.geogenesis.worldgen.climate.WhittakerType;

/**
 * 单格地形数据容器。
 * 由 CellGenerator 通过 {@code sample(wx,wz)} 填充，下游读取。
 *
 * 坐标语义：世界 XZ 坐标（非 chunk 坐标），高度 Y 为世界坐标。
 */
public class Cell {

    // === 地形 ===
    /** 世界高度 (Y) */
    public double height;

    /** 大陆性 c ∈ [-1,1]（负=海洋、正=陆地、0=海岸锚点，对齐 MC 原版 Continentalness） */
    public double continent;

    /** 归一化高度/深度 e ∈ [-1,1]（用于 HeightCurve 映射） */
    public double e;

    /** 纯海洋深度分量（不含 blend 前），诊断用 */
    public double eOcean;

    /** 平滑过渡权重 cont=[0,1]，诊断用 */
    public double blendCont;

    /** 连续 eLand ∈ [0,1]（纯陆地形态，不含海岸混合） */
    public double eLand;

    /**
     * <b>侵蚀前</b>的地形 e —— 气候/群系判定专用（{@link #e} 会被侵蚀增量改写）。
     *
     * <p>【2026-09-10】侵蚀增量按 48wu <b>轴对齐 tile</b> 施加，会沿 tile 缝改变 {@link #e}。
     * 群系若用 {@link #e} 判垂直带，就会出现两个问题：
     * ① 预览（走 getChunkCells，含侵蚀）与实机群系（走 sampleCellLight，不含侵蚀）不一致；
     * ② 垂直带边界贴着 tile 缝走 → 地图上出现长直线。
     * 故群系判定统一用本字段，两条路径结果一致。
     */
    public double eClimate;

    /**
     * <b>抖动后的</b>地形类型（仅用于群系变体选择，见 {@code BiomeClassifier.landVariant}）。
     *
     * <p>地形类型场是两个 Voronoi 站点高斯权重相等处 → 边界是<b>直线段</b>（站点上下排列时
     * 就是长水平线）。若群系变体直接按 {@link #terrainType} 切换，群系边界就会沿这条直线走
     * （用户实测的"长直线"）。此字段在权重上叠加噪声后取主导类型，使变体边界被打散。
     */
    public TerrainClass variantTerrain;

    /**
     * 有效雪线（e 单位）—— 由 CellGenerator 按配置计算：
     * {@code snowLine + snowLatitudeInfluence·温度 − snowHumidityInfluence·湿度}。
     * 群系垂直带与地表雪层共用此值，避免「两套雪线」。
     */
    public double snowLineE;

    /** 海洋特征计算结果（sampleCore 填充，classify 使用，避免重复 compute） */
    public OceanFeatures.FeatureResult oceanFeat;
    /** 陆地特征计算结果（sampleCore 填充，classify 使用，避免重复 compute） */
    public LandFeatures.FeatureResult landFeat;

    /** 地形类型（连续形态分类） */
    public TerrainClass terrainType = TerrainClass.OCEAN;

    /** 地形类型连续权重（5 类型：PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN，和=1），由 Voronoi 高斯加权混合产生 */
    public double[] typeWeights;

    /** 有效岸线坐标（cEdge = cBiased + warpDisplacement），供 classifyTerrain 做 BEACH 带约束 */
    public double coastCoord;

    // === 气候 ===
    /**
     * 气候（温度+湿度）—— <b>连续层</b>：逐格平滑，供预览图层与下游物理使用。
     */
    public Climate climate = Climate.DEFAULT;

    /**
     * Whittaker 群区 —— <b>区域层</b>：在气候区站点中心采样，区内恒定。
     * 群系选择用它（保证群系成片），与 {@link #climate} 的平滑值分离
     * （对齐 RTF：{@code cell.biome = BiomeType.get(regionTemp, regionMoist)}，
     * 而 {@code cell.temperature/moisture} 取查询位置的连续值）。
     */
    public WhittakerType biomeType = WhittakerType.GRASSLAND;

    // === 气候（兼容旧 API） ===
    /** 温度 */
    public double temperature;
    /** 湿度 */
    public double humidity;
    /** 归一化降水 [0,1.5]（★ 2026-09-11 Phase B；与 {@code Climate.precipitation} 同源） */
    public double precipitation;
    /** 大陆性噪声（旧 API，=continent） */
    public double continentNoise;

    // === 水文 ===
    /** 是否湖泊 */
    public boolean isLake;

    // === 水文（旧 API） ===
    /** 湖泊遮罩（旧 API） */
    public boolean lakeMask;
    /** 退化标识（旧 API） */
    public boolean erosionMask;
    /** 形态起伏（旧 API），∈[-1,1] */
    public double shape;
    /** 河网流量（粒子侵蚀 discharge 场采样；RIVER_NETWORK 流量图层用） */
    public double riverNetDischarge;
    /**
     * 到最近河线中心线的距离（wu）；{@link Double#POSITIVE_INFINITY} = 邻域内无河。
     *
     * <p>★ 只在<b>可能用到的格子</b>（干旱群区）计算：群系快速路径在出生点搜索等场景
     * 被高频调用，全域查询会无谓地实例化大量河网 region。判定规则只读它，且完整管线
     * 与快速路径用<b>同一个条件</b>计算，保证预览 = 游戏。
     */
    public double riverDistance = Double.POSITIVE_INFINITY;
    /** 绿洲斑块噪声 ∈[-1,1]：让河流绿洲成断续斑块而非连续绿带（RTG SurfaceRiverOasis 范式）。 */
    public double oasisNoise;
    /**
     * 坡度（无量纲：每世界块的抬升 = tan(坡角)）。0 = 平地，1 ≈ 45°。
     *
     * <p><b>仅在完整管线（{@code applyTileDelta}）中计算</b>：它取自侵蚀 tile 的高度网格
     * （自带 padding → 跨 tile 无接缝）。chunk 的 16×16 网格边缘只能 clamp，会产生
     * 16 块间距的接缝，故不用。
     *
     * <p>用途 = 陡坡裸岩（RTF {@code Steepness} tile filter + {@code ErodeFeature}
     * 岩层阈值范式）：陡崖不长植被、积不住沙，地表应出露岩石。
     */
    public float gradient;
    /** 河网段类型（RIVER_TYPE 图层用）：0 无 / 1 水文河流 */
    public byte riverType;
    /** 水文实验河流水面 Y；无河流时为海平面默认值。 */
    public double riverSurfaceY;
    /** 水文实验瀑布唇口水位 Y（= 潭面 + 落差）；普通河段等于 riverSurfaceY，无副作用。 */
    public double riverLipY;
    /** 是否积雪覆盖 */
    public boolean isSnow;

    // === 判据 ===
    /** 是否为陆地（非海洋） */
    public boolean isLand() {
        return !terrainType.isOcean() && terrainType != TerrainClass.LAKE;
    }

    /** 是否为水域（实测海平面 e<0） */
    public boolean isWater() {
        return e < 0.0;
    }

}
