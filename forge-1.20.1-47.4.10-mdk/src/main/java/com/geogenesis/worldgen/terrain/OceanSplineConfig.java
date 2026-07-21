package com.geogenesis.worldgen.terrain;

/**
 * Phase 3：海洋/水域类型的内层样条配置。
 * <p>
 * 包含 7 个海洋/水域类型的 lo/hi 样条，每类型 12 字段，共 84 字段。
 */
public record OceanSplineConfig(
    // OCEAN (12)
    double oceanLoLoc0, double oceanLoVal0, double oceanLoDeriv0,
    double oceanLoLoc1, double oceanLoVal1, double oceanLoDeriv1,
    double oceanHiLoc0, double oceanHiVal0, double oceanHiDeriv0,
    double oceanHiLoc1, double oceanHiVal1, double oceanHiDeriv1,

    // DEEP_OCEAN (12)
    double deepOceanLoLoc0, double deepOceanLoVal0, double deepOceanLoDeriv0,
    double deepOceanLoLoc1, double deepOceanLoVal1, double deepOceanLoDeriv1,
    double deepOceanHiLoc0, double deepOceanHiVal0, double deepOceanHiDeriv0,
    double deepOceanHiLoc1, double deepOceanHiVal1, double deepOceanHiDeriv1,

    // CONTINENTAL_SHELF (12)
    double shelfLoLoc0, double shelfLoVal0, double shelfLoDeriv0,
    double shelfLoLoc1, double shelfLoVal1, double shelfLoDeriv1,
    double shelfHiLoc0, double shelfHiVal0, double shelfHiDeriv0,
    double shelfHiLoc1, double shelfHiVal1, double shelfHiDeriv1,

    // SUBMARINE_RIDGE (12)
    double subRidgeLoLoc0, double subRidgeLoVal0, double subRidgeLoDeriv0,
    double subRidgeLoLoc1, double subRidgeLoVal1, double subRidgeLoDeriv1,
    double subRidgeHiLoc0, double subRidgeHiVal0, double subRidgeHiDeriv0,
    double subRidgeHiLoc1, double subRidgeHiVal1, double subRidgeHiDeriv1,

    // SEAMOUNT (12)
    double seamountLoLoc0, double seamountLoVal0, double seamountLoDeriv0,
    double seamountLoLoc1, double seamountLoVal1, double seamountLoDeriv1,
    double seamountHiLoc0, double seamountHiVal0, double seamountHiDeriv0,
    double seamountHiLoc1, double seamountHiVal1, double seamountHiDeriv1,

    // LAKE (12)
    double lakeLoLoc0, double lakeLoVal0, double lakeLoDeriv0,
    double lakeLoLoc1, double lakeLoVal1, double lakeLoDeriv1,
    double lakeHiLoc0, double lakeHiVal0, double lakeHiDeriv0,
    double lakeHiLoc1, double lakeHiVal1, double lakeHiDeriv1,

    // RIVER (12)
    double riverLoLoc0, double riverLoVal0, double riverLoDeriv0,
    double riverLoLoc1, double riverLoVal1, double riverLoDeriv1,
    double riverHiLoc0, double riverHiVal0, double riverHiDeriv0,
    double riverHiLoc1, double riverHiVal1, double riverHiDeriv1
) {
    /** 生产级默认值 */
    public static OceanSplineConfig defaults() {
        return new OceanSplineConfig(
            // OCEAN: lo=[-0.35,-0.35], hi=[-0.06,-0.06]
            0.0, -0.35, 0.0, 1.0, -0.35, 0.0,
            0.0, -0.06, 0.0, 1.0, -0.06, 0.0,
            // DEEP_OCEAN: lo=[-0.50,-0.50], hi=[-0.35,-0.35]
            0.0, -0.50, 0.0, 1.0, -0.50, 0.0,
            0.0, -0.35, 0.0, 1.0, -0.35, 0.0,
            // CONTINENTAL_SHELF: lo=[-0.25,-0.25], hi=[-0.08,-0.08]
            0.0, -0.25, 0.0, 1.0, -0.25, 0.0,
            0.0, -0.08, 0.0, 1.0, -0.08, 0.0,
            // SUBMARINE_RIDGE: lo=[-0.20,-0.20], hi=[-0.05,-0.05]
            0.0, -0.20, 0.0, 1.0, -0.20, 0.0,
            0.0, -0.05, 0.0, 1.0, -0.05, 0.0,
            // SEAMOUNT: lo=[-0.15,-0.15], hi=[-0.02,-0.02]
            0.0, -0.15, 0.0, 1.0, -0.15, 0.0,
            0.0, -0.02, 0.0, 1.0, -0.02, 0.0,
            // LAKE: lo=[-0.02,-0.02], hi=[0.01,0.01]
            0.0, -0.02, 0.0, 1.0, -0.02, 0.0,
            0.0,  0.01, 0.0, 1.0,  0.01, 0.0,
            // RIVER: lo=[-0.03,-0.03], hi=[0.00,0.00]
            0.0, -0.03, 0.0, 1.0, -0.03, 0.0,
            0.0,  0.00, 0.0, 1.0,  0.00, 0.0
        );
    }

    /**
     * 从 TerrainParams 构建 OceanSplineConfig（使用独立的 lo/hi 值）。
     * <p>
     * 每个海洋类型使用独立的 lo/hi 配置，与陆地类型一致。
     */
    public static OceanSplineConfig fromTerrainParams(TerrainParams p) {
        // 为每个类型构建简单的2 点样条（lo 和 hi 各自恒定）
        // 格式：loc0, val0, deriv0, loc1, val1, deriv1
        return new OceanSplineConfig(
            // OCEAN
            0.0, p.oceanLo(), 0.0, 1.0, p.oceanLo(), 0.0,
            0.0, p.oceanHi(), 0.0, 1.0, p.oceanHi(), 0.0,
            // DEEP_OCEAN
            0.0, p.deepOceanLo(), 0.0, 1.0, p.deepOceanLo(), 0.0,
            0.0, p.deepOceanHi(), 0.0, 1.0, p.deepOceanHi(), 0.0,
            // CONTINENTAL_SHELF
            0.0, p.shelfLo(), 0.0, 1.0, p.shelfLo(), 0.0,
            0.0, p.shelfHi(), 0.0, 1.0, p.shelfHi(), 0.0,
            // SUBMARINE_RIDGE
            0.0, p.subRidgeLo(), 0.0, 1.0, p.subRidgeLo(), 0.0,
            0.0, p.subRidgeHi(), 0.0, 1.0, p.subRidgeHi(), 0.0,
            // SEAMOUNT
            0.0, p.seamountLo(), 0.0, 1.0, p.seamountLo(), 0.0,
            0.0, p.seamountHi(), 0.0, 1.0, p.seamountHi(), 0.0,
            // LAKE
            0.0, p.lakeLo(), 0.0, 1.0, p.lakeLo(), 0.0,
            0.0, p.lakeHi(), 0.0, 1.0, p.lakeHi(), 0.0,
            // RIVER
            0.0, p.riverLo(), 0.0, 1.0, p.riverLo(), 0.0,
            0.0, p.riverHi(), 0.0, 1.0, p.riverHi(), 0.0
        );
    }

    /** 构建 OCEAN 内层样条 */
    public UnifiedSpline.InnerSpline buildOceanInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{oceanLoLoc0, oceanLoLoc1}, new double[]{oceanLoVal0, oceanLoVal1}, new double[]{oceanLoDeriv0, oceanLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{oceanHiLoc0, oceanHiLoc1}, new double[]{oceanHiVal0, oceanHiVal1}, new double[]{oceanHiDeriv0, oceanHiDeriv1})
        );
    }

    /** 构建 DEEP_OCEAN 内层样条 */
    public UnifiedSpline.InnerSpline buildDeepOceanInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{deepOceanLoLoc0, deepOceanLoLoc1}, new double[]{deepOceanLoVal0, deepOceanLoVal1}, new double[]{deepOceanLoDeriv0, deepOceanLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{deepOceanHiLoc0, deepOceanHiLoc1}, new double[]{deepOceanHiVal0, deepOceanHiVal1}, new double[]{deepOceanHiDeriv0, deepOceanHiDeriv1})
        );
    }

    /** 构建 CONTINENTAL_SHELF 内层样条 */
    public UnifiedSpline.InnerSpline buildShelfInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{shelfLoLoc0, shelfLoLoc1}, new double[]{shelfLoVal0, shelfLoVal1}, new double[]{shelfLoDeriv0, shelfLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{shelfHiLoc0, shelfHiLoc1}, new double[]{shelfHiVal0, shelfHiVal1}, new double[]{shelfHiDeriv0, shelfHiDeriv1})
        );
    }

    /** 构建 SUBMARINE_RIDGE 内层样条 */
    public UnifiedSpline.InnerSpline buildSubRidgeInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{subRidgeLoLoc0, subRidgeLoLoc1}, new double[]{subRidgeLoVal0, subRidgeLoVal1}, new double[]{subRidgeLoDeriv0, subRidgeLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{subRidgeHiLoc0, subRidgeHiLoc1}, new double[]{subRidgeHiVal0, subRidgeHiVal1}, new double[]{subRidgeHiDeriv0, subRidgeHiDeriv1})
        );
    }

    /** 构建 SEAMOUNT 内层样条 */
    public UnifiedSpline.InnerSpline buildSeamountInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{seamountLoLoc0, seamountLoLoc1}, new double[]{seamountLoVal0, seamountLoVal1}, new double[]{seamountLoDeriv0, seamountLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{seamountHiLoc0, seamountHiLoc1}, new double[]{seamountHiVal0, seamountHiVal1}, new double[]{seamountHiDeriv0, seamountHiDeriv1})
        );
    }

    /** 构建 LAKE 内层样条 */
    public UnifiedSpline.InnerSpline buildLakeInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{lakeLoLoc0, lakeLoLoc1}, new double[]{lakeLoVal0, lakeLoVal1}, new double[]{lakeLoDeriv0, lakeLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{lakeHiLoc0, lakeHiLoc1}, new double[]{lakeHiVal0, lakeHiVal1}, new double[]{lakeHiDeriv0, lakeHiDeriv1})
        );
    }

    /** 构建 RIVER 内层样条 */
    public UnifiedSpline.InnerSpline buildRiverInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(new double[]{riverLoLoc0, riverLoLoc1}, new double[]{riverLoVal0, riverLoVal1}, new double[]{riverLoDeriv0, riverLoDeriv1}),
            new UnifiedSpline.Spline(new double[]{riverHiLoc0, riverHiLoc1}, new double[]{riverHiVal0, riverHiVal1}, new double[]{riverHiDeriv0, riverHiDeriv1})
        );
    }

    /** 获取所有海洋/水域类型的内层样条数组（7 个） */
    public UnifiedSpline.InnerSpline[] buildAllInners() {
        return new UnifiedSpline.InnerSpline[]{
            buildOceanInner(), buildDeepOceanInner(), buildShelfInner(),
            buildSubRidgeInner(), buildSeamountInner(), buildLakeInner(), buildRiverInner()
        };
    }
}
