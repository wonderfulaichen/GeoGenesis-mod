package com.geogenesis.worldgen.core;

import com.geogenesis.worldgen.climate.WhittakerType;
import com.geogenesis.worldgen.terrain.TerrainClass;

/** 基础阶段的中立列数据；每次查询独立所有，不含MC对象和区域后处理结果。 */
public final class BaseColumn {
    public double height, e, continent, landE, coastCoord, oceanE, oceanWeight, shape;
    public double[] typeWeights;
    public TerrainClass terrainType, variantTerrain;
    public double temperature, humidity, climateContinentality, precipitation, snowLineE, oasisNoise;
    public WhittakerType biomeType;
    public boolean snow;
    public int rockLayer, rockTypeId, rockSeqPacked;
    public double rockTilt;
}
