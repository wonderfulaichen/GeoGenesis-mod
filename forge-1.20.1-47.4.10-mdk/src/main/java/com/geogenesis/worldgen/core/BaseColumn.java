package com.geogenesis.worldgen.core;

import com.geogenesis.worldgen.climate.WhittakerType;
import com.geogenesis.worldgen.terrain.OceanFeatures;
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
    /**
     * ★ 阶段六（2026-11-17）：构造特征结果——火山/熔岩台地（陆侧）与
     * 海山/洋中脊/弧沟（洋侧）。{@code null} = 该点无特征或功能关闭。
     * <p>下游 {@code Cell.landFeat}/{@code oceanFeat} 由 {@code MorphCellAdapter} 拷贝，
     * 供 {@code CellGenerator.classifyTerrain} 的火山/海洋亚型分支使用。</p>
     */
    public OceanFeatures.FeatureResult oceanFeat;
}
