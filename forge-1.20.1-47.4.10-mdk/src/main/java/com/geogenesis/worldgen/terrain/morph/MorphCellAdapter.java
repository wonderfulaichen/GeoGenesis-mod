package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.Cell;

/** P1 metadata adapter. Classification never feeds back into the verified height field. */
public final class MorphCellAdapter {
    private MorphCellAdapter() { }

    public static Cell sample(MorphTerrainSampler sampler, double x, double z) {
        return from(sampler.sampleColumn(x, z));
    }

    /** 中立核心结果到现有MC管线Cell的单向桥接。 */
    public static Cell from(com.geogenesis.worldgen.core.BaseColumn c) {
        Cell cell = new Cell();
        cell.e = c.e; cell.eClimate = c.e; cell.height = c.height;
        cell.continent = c.continent; cell.continentNoise = c.continent;
        cell.eLand = c.landE; cell.coastCoord = c.coastCoord;
        cell.shape = c.shape; cell.eOcean = c.oceanE; cell.blendCont = c.oceanWeight;
        cell.typeWeights = c.typeWeights; cell.terrainType = c.terrainType;
        cell.variantTerrain = c.variantTerrain;
        cell.temperature = c.temperature; cell.humidity = c.humidity;
        cell.precipitation = c.precipitation;
        cell.climate = new com.geogenesis.worldgen.climate.Climate(
                c.temperature, c.humidity, c.climateContinentality, c.precipitation);
        if (c.biomeType != null) cell.biomeType = c.biomeType;
        cell.snowLineE = c.snowLineE; cell.isSnow = c.snow; cell.oasisNoise = c.oasisNoise;
        cell.rockLayer = c.rockLayer; cell.rockTypeId = c.rockTypeId;
        cell.rockSeqPacked = c.rockSeqPacked; cell.rockTilt = c.rockTilt;
        return cell;
    }

    /** Central differences in wu, converted to rise per MC block with the existing HS. */
    public static float gradient(MorphTerrainSampler sampler, double x, double z, double hs) {
        double dx = (sampler.height(x + 1.0, z) - sampler.height(x - 1.0, z)) / (2.0 * hs);
        double dz = (sampler.height(x, z + 1.0) - sampler.height(x, z - 1.0)) / (2.0 * hs);
        return (float) Math.hypot(dx, dz);
    }
}
