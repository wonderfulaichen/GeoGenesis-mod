package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;

/** 生产落块层的水类判定，供仍活跃的水柱/视图诊断共用。 */
public final class ProductionWaterKind {
    public static final int DRY = 0, SEA = 1, LAKE = 2, RIVER = 3;
    private static final int MIN_Y = -64, MAX_Y = 320, BASE_WATER_Y = 63;

    private ProductionWaterKind() { }

    public static int waterKind(Cell cell, boolean hydrologyOn, double seaLevel) {
        if (!Double.isFinite(cell.height)) throw new IllegalArgumentException("non-finite ground");
        // 与 GeoGenesisGenerator.fillTerrainColumn 的 riverWater/ocean/waterTop/lipBlock 顺序一致。
        boolean river = hydrologyOn && cell.riverType != 0 && cell.riverSurfaceY > cell.height;
        boolean ocean = hydrologyOn && cell.height < seaLevel;
        boolean water = hydrologyOn ? (river || ocean) : cell.isWater();
        if (!water) return DRY;
        double top = river ? cell.riverSurfaceY : BASE_WATER_Y;
        if (ocean) top = Math.max(top, seaLevel);
        if (river && cell.riverLipY > top) top = cell.riverLipY;
        if (!Double.isFinite(top)) throw new IllegalArgumentException("non-finite water top");
        double first = Math.max(MIN_Y + 1, Math.floor(cell.height) + 1);
        double last = Math.min(MAX_Y - 1, Math.floor(top));
        if (first > last) return DRY;
        return river ? (cell.isLake ? LAKE : RIVER) : SEA;
    }
}
