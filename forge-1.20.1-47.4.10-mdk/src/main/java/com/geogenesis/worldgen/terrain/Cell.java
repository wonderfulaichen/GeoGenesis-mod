package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.climate.Climate;

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
    /** 气候（温度+湿度） */
    public Climate climate = Climate.DEFAULT;

    // === 气候（兼容旧 API） ===
    /** 温度 */
    public double temperature;
    /** 湿度 */
    public double humidity;
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
