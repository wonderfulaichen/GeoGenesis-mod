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

    /** 连续 eLand ∈ [0,1]（纯陆地形态，不含海岸混合） */
    public double eLand;

    /** 地形类型（连续形态分类） */
    public TerrainClass terrainType = TerrainClass.OCEAN;

    /** 地形类型连续权重（5 类型：PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN，和=1），由 Voronoi 高斯加权混合产生 */
    public double[] typeWeights;

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
    /** 是否河流 */
    public boolean isRiver;

    /** 河流湿度（连续值，0=无河，>0=河流影响） */
    public double riverWetness;

    /** 是否湖泊 */
    public boolean isLake;

    // === 河流/湖泊兼容字段（旧 API，Stage 2 重接） ===
    /** 河流遮罩（旧 API） */
    public boolean riverMask;
    /** 湖泊遮罩（旧 API） */
    public boolean lakeMask;
    /** 距河距离（旧 API），0=河心，1=谷缘 */
    public double riverDistance = 1.0;
    /** 是否瀑布段（旧 API） */
    public boolean riverIsWaterfall;
    /** 源头类型（旧 API）：1=溪源，2=山泉，3=源头湖 */
    public int riverSourceType;
    /** 河流刻蚀后的河床 Y（RiverField 写入，Generator.fillRiverColumn 灌水读取） */
    public double riverFloorY;
    /** 河流刻蚀后的水面 Y（谷壁/海平面较高者，Generator.fillRiverColumn 灌水读取） */
    public double riverSurfaceY;
    /** 退化标识（旧 API） */
    public boolean erosionMask;
    /** 河网距离（旧 API） */
    public double riverNetDist = 1.0;
    /** 河网流量（旧 API） */
    public double riverNetDischarge;
    /** 河网溢出（旧 API） */
    public boolean riverNetOverflow;
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
