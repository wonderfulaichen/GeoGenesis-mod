package com.geogenesis.worldgen.terrain;

/**
 * Phase 3：统一样条配置（独立 record，避免 TerrainParams 参数过多）。
 * <p>
 * 包含外层样条（7 个大陆性 c 控制点）+ 中层样条（类型分布）+ 所有类型的内层样条（lo/hi）。
 */
public record SplineConfig(
    // ===== 外层样条：大陆性 c 控制点（7 × 2 = 14 字段）=====
    double outerLoc0, double outerDeriv0,   // 深海 c=-0.80
    double outerLoc1, double outerDeriv1,   // 大陆架 c=-0.50
    double outerLoc2, double outerDeriv2,   // 浅海 c=-0.16
    double outerLoc3, double outerDeriv3,   // 海岸 c=-0.04
    double outerLoc4, double outerDeriv4,   // 近岸陆地 c=0.20
    double outerLoc5, double outerDeriv5,   // 内陆 c=0.50
    double outerLoc6, double outerDeriv6,   // 深远内陆 c=0.80

    // ===== 内层样条：PLAIN（lo/hi 各 2 点 × 3 字段 = 12）=====
    double plainLoLoc0, double plainLoVal0, double plainLoDeriv0,
    double plainLoLoc1, double plainLoVal1, double plainLoDeriv1,
    double plainHiLoc0, double plainHiVal0, double plainHiDeriv0,
    double plainHiLoc1, double plainHiVal1, double plainHiDeriv1,

    // ===== HILLS =====
    double hillsLoLoc0, double hillsLoVal0, double hillsLoDeriv0,
    double hillsLoLoc1, double hillsLoVal1, double hillsLoDeriv1,
    double hillsHiLoc0, double hillsHiVal0, double hillsHiDeriv0,
    double hillsHiLoc1, double hillsHiVal1, double hillsHiDeriv1,

    // ===== MOUNTAINS =====
    double mountLoLoc0, double mountLoVal0, double mountLoDeriv0,
    double mountLoLoc1, double mountLoVal1, double mountLoDeriv1,
    double mountHiLoc0, double mountHiVal0, double mountHiDeriv0,
    double mountHiLoc1, double mountHiVal1, double mountHiDeriv1,

    // ===== PLATEAU =====
    double platLoLoc0, double platLoVal0, double platLoDeriv0,
    double platLoLoc1, double platLoVal1, double platLoDeriv1,
    double platHiLoc0, double platHiVal0, double platHiDeriv0,
    double platHiLoc1, double platHiVal1, double platHiDeriv1,

    // ===== BASIN =====
    double basinLoLoc0, double basinLoVal0, double basinLoDeriv0,
    double basinLoLoc1, double basinLoVal1, double basinLoDeriv1,
    double basinHiLoc0, double basinHiVal0, double basinHiDeriv0,
    double basinHiLoc1, double basinHiVal1, double basinHiDeriv1,

    // ===== Phase 3：海洋/水域类型的内层样条配置（独立 record）=====
    OceanSplineConfig oceanSplineConfig,

    // ===== Phase 2：中层样条配置（独立 record）=====
    MidSplineConfig midSplineConfig
) {
    /** 生产级默认值 */
    public static SplineConfig defaults() {
        return new SplineConfig(
            // outer spline locations + derivatives (14)
            -0.80, 0.0, -0.50, 0.0, -0.16, 0.0, -0.04, 0.0,
             0.20, 0.0,  0.50, 0.0,  0.80, 0.0,
            // PLAIN inner spline (12): lo=[0.005,0.005], hi=[0.03,0.03]
            0.0, 0.005, 0.0, 1.0, 0.005, 0.0,
            0.0, 0.03,  0.0, 1.0, 0.03,  0.0,
            // HILLS inner spline (12): lo=[0.06,0.06], hi=[0.18,0.18]
            // （2026-08-01 两次下调后区间 0.02~0.12 过窄：内部落差仅 ~10 格 → 丘陵"山"不明显（用户反馈）。
            //  放宽到 [0.06,0.18]：核心区（权重 0.7，混入 30% 其他类型）blend≈[0.12,0.28] → Y[91,128]
            //  仍 ≤130（符合用户"最高不超过 130"要求），落差 ~38 格，山丘明显）
            0.0, 0.06,  0.0, 1.0, 0.06,  0.0,
            0.0, 0.18,  0.0, 1.0, 0.18,  0.0,
            // MOUNTAINS inner spline (12): lo=[0.45,0.45], hi=[0.95,0.95]
            0.0, 0.45,  0.0, 1.0, 0.45,  0.0,
            0.0, 0.95,  0.0, 1.0, 0.95,  0.0,
            // PLATEAU inner spline (12): lo=[0.60,0.60], hi=[0.75,0.75]（明显高于 MOUNTAINS 谷底 0.45，高台平顶）
            0.0, 0.60,  0.0, 1.0, 0.60,  0.0,
            0.0, 0.75,  0.0, 1.0, 0.75,  0.0,
            // BASIN inner spline (12): lo=[-0.08,-0.08], hi=[0.02,0.02]（真凹陷洼地：低于平原、可入水成湖）
            0.0, -0.08, 0.0, 1.0, -0.08, 0.0,
            0.0, 0.02,  0.0, 1.0, 0.02,  0.0,

            // Phase 3: ocean/water type inner splines
            OceanSplineConfig.defaults(),

            // Phase 2: mid spline config
            MidSplineConfig.defaults()
        );
    }

    // ===== 便捷方法 =====

    /** 外层样条控制点 locations */
    public double[] outerLocations() {
        return new double[]{outerLoc0, outerLoc1, outerLoc2, outerLoc3,
                            outerLoc4, outerLoc5, outerLoc6};
    }

    /** 外层样条控制点 derivatives */
    public double[] outerDerivatives() {
        return new double[]{outerDeriv0, outerDeriv1, outerDeriv2, outerDeriv3,
                            outerDeriv4, outerDeriv5, outerDeriv6};
    }

    /** 构建 PLAIN 内层样条 */
    public UnifiedSpline.InnerSpline buildPlainInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(
                new double[]{plainLoLoc0, plainLoLoc1},
                new double[]{plainLoVal0, plainLoVal1},
                new double[]{plainLoDeriv0, plainLoDeriv1}),
            new UnifiedSpline.Spline(
                new double[]{plainHiLoc0, plainHiLoc1},
                new double[]{plainHiVal0, plainHiVal1},
                new double[]{plainHiDeriv0, plainHiDeriv1})
        );
    }

    /** 构建 HILLS 内层样条 */
    public UnifiedSpline.InnerSpline buildHillsInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(
                new double[]{hillsLoLoc0, hillsLoLoc1},
                new double[]{hillsLoVal0, hillsLoVal1},
                new double[]{hillsLoDeriv0, hillsLoDeriv1}),
            new UnifiedSpline.Spline(
                new double[]{hillsHiLoc0, hillsHiLoc1},
                new double[]{hillsHiVal0, hillsHiVal1},
                new double[]{hillsHiDeriv0, hillsHiDeriv1})
        );
    }

    /** 构建 MOUNTAINS 内层样条 */
    public UnifiedSpline.InnerSpline buildMountainsInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(
                new double[]{mountLoLoc0, mountLoLoc1},
                new double[]{mountLoVal0, mountLoVal1},
                new double[]{mountLoDeriv0, mountLoDeriv1}),
            new UnifiedSpline.Spline(
                new double[]{mountHiLoc0, mountHiLoc1},
                new double[]{mountHiVal0, mountHiVal1},
                new double[]{mountHiDeriv0, mountHiDeriv1})
        );
    }

    /** 构建 PLATEAU 内层样条 */
    public UnifiedSpline.InnerSpline buildPlateauInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(
                new double[]{platLoLoc0, platLoLoc1},
                new double[]{platLoVal0, platLoVal1},
                new double[]{platLoDeriv0, platLoDeriv1}),
            new UnifiedSpline.Spline(
                new double[]{platHiLoc0, platHiLoc1},
                new double[]{platHiVal0, platHiVal1},
                new double[]{platHiDeriv0, platHiDeriv1})
        );
    }

    /** 陆地类型内层样条 e 上限最大值（供预览色阶 eMax）。顺序取 5 陆地类型 hiVal0。 */
    public double maxLandHi() {
        return Math.max(
            Math.max(plainHiVal0, hillsHiVal0),
            Math.max(mountHiVal0, Math.max(platHiVal0, basinHiVal0)));
    }

    /**
     * 5 陆地类型 [lo, hi]，顺序与 {@code TypeNoiseProvider.LAND_TYPES}
     * （PLAIN / HILLS / MOUNTAINS / PLATEAU / BASIN）严格一致。
     * 用于配置屏只读展示类型真实高度范围。
     */
    public double[][] landTypeBounds() {
        return new double[][] {
            {plainLoVal0, plainHiVal0},   // PLAIN
            {hillsLoVal0, hillsHiVal0},   // HILLS
            {mountLoVal0, mountHiVal0},   // MOUNTAINS
            {platLoVal0,  platHiVal0},    // PLATEAU
            {basinLoVal0, basinHiVal0}    // BASIN
        };
    }

    /** 构建 BASIN 内层样条 */
    public UnifiedSpline.InnerSpline buildBasinInner() {
        return new UnifiedSpline.InnerSpline(
            new UnifiedSpline.Spline(
                new double[]{basinLoLoc0, basinLoLoc1},
                new double[]{basinLoVal0, basinLoVal1},
                new double[]{basinLoDeriv0, basinLoDeriv1}),
            new UnifiedSpline.Spline(
                new double[]{basinHiLoc0, basinHiLoc1},
                new double[]{basinHiVal0, basinHiVal1},
                new double[]{basinHiDeriv0, basinHiDeriv1})
        );
    }

    /**
     * 构建 UnifiedSpline 实例（Phase 3：3 层嵌套 + 海洋/水域类型）。
     */
    public UnifiedSpline build() {
        double[] locs = outerLocations();
        double[] derivs = outerDerivatives();
        
        // 构建所有类型的内层样条（5 陆地 + 7 海洋/水域 = 12）
        UnifiedSpline.InnerSpline[] landInners = {
            buildPlainInner(),
            buildHillsInner(),
            buildMountainsInner(),
            buildPlateauInner(),
            buildBasinInner()
        };
        UnifiedSpline.InnerSpline[] oceanInners = oceanSplineConfig.buildAllInners();
        
        // 合并陆地和海洋/水域的内层样条
        UnifiedSpline.InnerSpline[] allInners = new UnifiedSpline.InnerSpline[landInners.length + oceanInners.length];
        java.lang.System.arraycopy(landInners, 0, allInners, 0, landInners.length);
        java.lang.System.arraycopy(oceanInners, 0, allInners, landInners.length, oceanInners.length);
        
        // 构建 7 个外层节点，每个节点包含中层样条
        UnifiedSpline.OuterNode[] nodes = new UnifiedSpline.OuterNode[7];
        for (int i = 0; i < 7; i++) {
            // Phase 2：构建中层样条（类型分布）
            UnifiedSpline.MidSpline midSpline = midSplineConfig.buildMidSpline(i, allInners);
            nodes[i] = new UnifiedSpline.OuterNode(locs[i], midSpline, derivs[i]);
        }
        
        return new UnifiedSpline(nodes);
    }
}
