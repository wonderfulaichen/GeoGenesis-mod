package com.geogenesis.worldgen.terrain;

/**
 * 地形生成参数记录（不可变，由 GeoGenesisConfig 注入）。
 *
 * 参数分组：
 * - 大陆场：continentScale、warp、threshold
 * - 海洋样条：c-space 控制点位置 + e-space 深度 + 导数
 * - 海床细节：seabedDetail
 * - 海洋深度缩放：oceanDepthFactor
 * - 海陆占比：continentBias
 * - 陆地形态：beltReliefAmp（造山带起伏）、basinBase（盆地基底）
 *
 * <p><b>2026-11-07 删键</b>：省系统与旧省形态 / 省场形态曲线的 32 个组件已移除
 * （provinceScale、craton|belt|plateau|basinWeight、plainBase/plainRough/hillsLow/hillsHigh、
 * beltRidgePower/beltFoothill/beltPeak、plateauBase/plateauTop/plateauSteps/plateauStepStrength、
 * cratonReliefAmp/plateauReliefAmp/basinReliefAmp、beltSharpness/beltWarpAmp/provMixSharpness、
 * mountainMaskScale/microDetailScale/microDetailAmp、continentProvinceWarp、
 * coastTerrainInfluence、archipelagoBand/Density/Scale/Height）。
 * 它们在生产路径上零读取，删除后地形产出逐位不变（见 build/configkey-cleanup-20261107/）。</p>
 *
 * <p><b>存档兼容</b>：本 record 由 {@link RecordSerializer} 按 <b>组件名</b> 读写 NBT
 * （写：每个组件一个同名 tag；读：按名取，缺失键返回类型默认零值）。因此
 * ① 新存档只写现存组件；② 旧存档里残留的被删组件 tag 会被<b>静默忽略</b>；
 * ③ 旧存档缺少现存组件时读出 0.0 —— 本项目当前无 legacy 存档（用户 2026-11-07 确认
 * "没有 legacy 存档，按全新架构来"），故无迁移动作；若将来需要读旧档，必须补默认值逻辑。</p>
 */
public record TerrainParams(
    // ===== 大陆场 =====
    /** 大陆噪声尺度（块），频率 = 1/scale，默认 1500 */
    double continentScale,
    /** 域扭曲强度（占大陆尺度的比例系数），默认 0.25 */
    double continentWarp,
    /** 大陆 FBM 倍频数（octaves）：大陆性 c 场由多倍频 FBM 值叠加，每倍频频率×lacunarity、振幅×persistence。海岸线 = c=0 等值线，FBM 多尺度细节直接刻进海岸线 → 犬牙交错。默认 6（严格对齐参考项目 procedural-island-generator） */
    int continentFbmOctaves,
    /** 大陆 FBM 频率倍增（lacunarity），每倍频频率×该值，默认 2.0 */
    double continentFbmLacunarity,
    /** 大陆 FBM 振幅衰减（persistence），每倍频振幅×该值，越大高频细节越强、海岸越破碎，默认 0.6 */
    double continentFbmPersistence,
    /** 海陆参考阈值 c 值（[-1,1] 区间），默认 0.0 */
    double continentThreshold,
    /** 海陆占比偏置（正=更多海、负=更多陆），默认 0.31（RMS 归一化 FBM 使 c 集中近 0，故偏置远低于旧单 simplex 的 0.82；经 TerrainAreaProbe 用真实 CellGenerator.sample() 管线标定≈35% 陆地/65% 海洋；旧简化公式 c+eFromC(c-bias) 标定出的 0.64 因未计入两阶段 blend+eLand+seabed 而偏置过大） */
    double continentBias,

    // ===== 海洋样条控制点（offset.json 式 cubic Hermite） =====
    // c-space 位置（c∈[-1,1]：深海→大陆架→浅海→海岸）
    double deepOceanLoc,   // 默认 -0.80; 深海起点（最深洋盆）
    double shelfLoc,       // 默认 -0.50; 大陆架外缘
    double shallowLoc,     // 默认 -0.16; 浅海
    double coastLoc,       // 默认 -0.04; 海岸线（样条锚定 e=0）

    // e-space 深度（控制点 value）
    double deepOceanDepth, // 默认 -0.35; 深海深度（适配 MC 游戏性，≈ y=19 海床）
    double shelfDepth,     // 默认 -0.25; 大陆架深度
    double shallowDepth,   // 默认 -0.06; 浅海深度

    // 各控制点导数（MC 会×段宽）
    double deepOceanDeriv,
    double shelfDeriv,
    double shallowDeriv,
    double coastDeriv,

    // ===== 海岸线过渡区（两阶段 blend，参考 TerraForged） =====
    /** 海洋淡出起点（cBiased 空间）：大陆性在此值以上，海洋深度开始衰减到 0，默认 -0.15 */
    double oceanFadeStart,
    /** 陆地高度终点（cBiased 空间）：大陆性达到此值时全量陆地高度，默认 0.08 */
    double landRampEnd,

    // ===== 海洋类型独立 lo/hi（与陆地类型一致的独立控制） =====
    double oceanLo,      // OCEAN lo
    double oceanHi,      // OCEAN hi
    double deepOceanLo,  // DEEP_OCEAN lo
    double deepOceanHi,  // DEEP_OCEAN hi
    double shelfLo,      // CONTINENTAL_SHELF lo
    double shelfHi,      // CONTINENTAL_SHELF hi
    double subRidgeLo,   // SUBMARINE_RIDGE lo
    double subRidgeHi,   // SUBMARINE_RIDGE hi
    double seamountLo,   // SEAMOUNT lo
    double seamountHi,   // SEAMOUNT hi
    double lakeLo,       // LAKE lo
    double lakeHi,       // LAKE hi
    double riverLo,      // RIVER lo
    double riverHi,      // RIVER hi

    // ===== 海床细节 =====
    /** 海床细节振幅（e 单位），默认 0.03 */
    double seabedDetail,
    /** 海洋深度缩放因子（e 单位乘数），>1=更深(海洋面积扩大)，<1=更浅(陆地扩大)，默认 1.0 */
    double oceanDepthFactor,

    // ===== 盆地基底（legacy 地形合成；morph-base-p1 不读） =====
    /** 盆地基准（默认 0.02）—— 碗形盆底的下限，只被 TypeNoiseProvider.computeBasin 读取。 */
    double basinBase,

    // ===== 地形起伏振幅 =====
    /** 造山带起伏振幅，默认 0.22（保 MOUNTAINS 分类；原 0.30，适度下调，指纹感靠 LandShape 频率放大解决）。
     *  只被 legacy TypeLandShape/TypeNoiseProvider 读取；morph-base-p1 走 MorphLandField。 */
    double beltReliefAmp,

    // ===== 分类阈值 + 雪线 =====
    /** 高海拔阈值（elevation），默认 0.25。不再用于 Voronoi 类型分配（气候色板替代），保留占位。 */
    double elevHigh,
    /** 高起伏阈值（|relief|），默认 0.04。不再用于 Voronoi 类型分配，保留占位。 */
    double reliefHigh,
    /** 雪线温度耦合强度（与旧 snowLatitudeInfluence 同名，但用于温度调制），默认 0.25 */
    double snowLatitudeInfluence,
    /** 雪线湿度耦合强度（干燥→雪线升高，湿润→雪线降低），默认 0.15 */
    double snowHumidityInfluence,

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
    /** 实际峰顶占 maxY 的比例（余量避免触顶世界构建上限），默认 0.92 */
    double peakHeightFraction,
    /** 理论峰顶高度 Y（= HeightCurve.heightFromE(maxLandHi) = seaLevel + maxLandHi*(maxY-seaLevel)*peakHeightFraction，默认 288）。
     *  实际地形经 CellGenerator.softCapLandE 软钳制后恒低于此值（默认最高 ≈281），永不触 clamp 被切平。同时作为预览高程色阶 Y 上限。 */
    int mountainCap,
    /** 海沟深度下限 Y，默认 -48 */
    int trenchDepth,
    /** 垂直缩放比例（1-8），预览用，默认 1.0 */
    double verticalScale,

    // ===== 大陆性语义亲和度（注入 TypeLandShape） =====
    /** 大陆性语义亲和度强度（β），调制 c 对各类型空间权重的偏置幅度，默认 0.0（禁用）。
     * 2026-08-06 用户决策：factor=1+β(aff-0.2) 跨 c 变化 100~200 倍 + BLEND_SHARPEN 放大 →
     * 类型过渡带突变（弧形硬边）。禁用恢复纯 Voronoi 自然过渡。0=无 c 效应。 */
    double cAffinityStrength,

    // ===== 海岸线多样化（CoastlineField 输入） =====
    /** 多尺度分形 warp 总振幅（c 空间单位）。海岸线在 c 上的位移幅度，默认 0.03 */
    double coastlineWarpAmp,
    /** 多尺度分形 warp 基频世界坐标尺度（块），默认 300。越大海岸线变化越平缓 */
    double coastlineWarpScale,
    /** 多尺度分形 warp 倍频数（octaves），控制海岸线细节尺度层级数，默认 5。越大细节越丰富（自相似分形犬牙交错） */
    int coastlineWarpOctaves,
    /** FBM 频率倍增系数（lacunarity），每倍频频率×该值，默认 2.0 */
    double coastlineWarpLacunarity,
    /** FBM 振幅衰减系数（persistence），每倍频振幅×该值，默认 0.5 */
    double coastlineWarpPersistence,
    // coastTerrainInfluence / archipelago{Band,Density,Scale,Height} 已于 2026-11-07 随配置键删除
    // （CoastlineField 不读它们，只有预览面板展示 ⇒ 假旋钮）。

    // ===== 气候/纬度 xz 缩放（注入 CellGenerator 气候模型）=====
    /** 纬度缩放（温度随 z 变化的周期，块）。越大→温度带越宽（纬度变化越慢），默认 6000 */
    double latitudeScale,
    /** 温度噪声 xz 缩放（块），温度扰动场频率 = 1/tempWarpScale，默认 1500 */
    double tempWarpScale,
    /** 湿度噪声 xz 缩放（块），湿度场频率 = 1/humidityScale，默认 800 */
    double humidityScale,
    /**
     * 气候区尺寸（wu）：抖动 Voronoi 网格边长，区内温湿恒定。
     * 越大 → 气候斑块越大、群系越成片；越小 → 越碎。默认 256。
     */
    double climateRegionSize,

    // ===== Phase 1：统一样条配置（独立 record，避免参数过多）=====
    SplineConfig splineConfig
) {
    /** 生产级默认值（校准于非对称映射 e=0 → Y=63） */
    public static TerrainParams defaults() {
        return new TerrainParams(
            // continent（continentProvinceWarp 已于 2026-11-07 删除）
            // 大陆 FBM：scale=4000, warp=0（纯 FBM，去域扭曲避免平行条带）, octaves=6（严格对齐参考项目）, lac=2.0, per=0.6；bias=0.31（真实 CellGenerator.sample() 管线标定≈35% 陆地/65% 海洋；旧简化公式标定的 0.64 实际只产出 ~13% 陆地）
            4000.0, 0.0, 6, 2.0, 0.6, 0.0, 0.31,
            // ocean spline locations (c∈[-1,1]; 负=深海、0=海岸锚点)
            -0.80, -0.50, -0.16, -0.04,
            // ocean spline values（deepOcean depth 从 -0.85 减至 -0.35，适配 MC 游戏性不宜太深）
            // 2026-08-06: shallowDepth -0.06→-0.09→-0.14（海岸带 eOcean 加深 → e=0 穿越点 cont 提升，
            // eLand 参与海岸线定位；配合 oceanFadeStart -0.07→-0.13 过渡带向海侧扩展）
            -0.35, -0.25, -0.14,
            // ocean spline derivatives（shelfDeriv 0.8；shallowDeriv 0.5 保持浅水带单调递减斜率，消除平涂浅蓝平台）
            0.0, 0.8, 0.5, 0.0,
            // coastline transition (two-stage blend after TerraForged)
            // 2026-08-06 二次修正：过渡带向海侧扩展（-0.07→-0.13），使 e=0 穿越点（eOcean 归 0 处）
            // 从紧贴 oceanFadeStart（cont<0.1，eLand 参与<10% → 海岸线=c 低频等值线=完美圆弧）
            // 移到过渡带中部（cont≈0.25~0.4）→ eLand 真正参与海岸线定位（用户铁律：海岸线由
            // 地形斜率自然决定，与地形类型之间过渡一致）。配套 shallowDepth -0.06→-0.09 控制
            // 大陆架冒岛量。landRampEnd 保持 0.05。
            -0.13, 0.05,
            // 海洋类型独立 lo/hi（默认值）
            -0.35, -0.06,  // OCEAN
            -0.50, -0.35,  // DEEP_OCEAN
            -0.25, -0.08,  // CONTINENTAL_SHELF
            -0.20, -0.05,  // SUBMARINE_RIDGE
            -0.15, -0.02,  // SEAMOUNT
            -0.02,  0.01,  // LAKE
            -0.03,  0.00,  // RIVER
            // seabed（必须在海洋 lo/hi 之后，对齐 record 字段顺序）
            0.03,
            // oceanDepthFactor（record 第 37 字段）
            1.0,
            // basinBase（唯一保留的旧「Land Process Parameters」项）
            0.02,
            // relief amplitude（仅 beltReliefAmp 保留；craton/plateau/basin 振幅键 2026-11-07 删除）
            0.35,
            // classification thresholds + snow latitude coupling
            0.25, 0.04, 0.25, 0.15,  // elevHigh↑0.25,reliefHigh↑0.04,snowLatitudeInfluence,snowHumidityInfluence（peakE 已移除 2026-08-05）

            // preview compat（HS=2.0 默认：2026-08-02 整体倍频变宽 ×2 修复地形过陡；
            // 坡度探针 A/B：平均坡度 30.9°→16.8°、>45° 37%→3.7%）
            2.0, 63, 0.70, -64, 320, 0.92, (int) Math.round(63 + (320 - 63) * 0.92 * 0.95), -48,
            1.0,  // verticalScale
            0.0,              // cAffinityStrength（2026-08-06 默认 0 禁用：防类型过渡带硬边）
            // coastline diversification
            // 2026-08-06: warp 振幅 0.05→0.15、基频 300→1200、octaves 5→6、per 0.5→0.6
            // 破圆弧实锤：穿越点 cont<0.1（eLand 参与<10%）→ 海岸线=c 等值线（4000 块波长大弧）；
            // 旧 warp 位移仅 ~57 块@300 波长 → 大俯视图不可见。1200 块基频 → 半岛/海湾级弯曲
            // （位移 ~120 块），octave 覆盖 600/300/150/75/37 多尺度锯齿。
            0.15, 1200.0, 6, 2.0, 0.6,  // coastlineWarpAmp, coastlineWarpScale, octaves, lacunarity, persistence

            // climate / latitude xz scales (injected into CellGenerator)
            6000.0, 1500.0, 800.0,   // latitudeScale, tempWarpScale, humidityScale
            256.0,                   // climateRegionSize

            // Phase 1: unified spline config
            SplineConfig.defaults()
        );
    }

    // ===== 便捷方法 =====

    /** 海洋样条 c 坐标 */
    public double[] oceanLocations() {
        return new double[]{deepOceanLoc, shelfLoc, shallowLoc, coastLoc};
    }

    /** 海洋样条深度值（使用真正的海洋深度控制点：深海/大陆架/浅海/海岸锚点=0） */
    public double[] oceanValues() {
        return new double[]{deepOceanDepth, shelfDepth, shallowDepth, 0.0};
    }

    /** 海洋样条导数 */
    public double[] oceanDerivatives() {
        return new double[]{deepOceanDeriv, shelfDeriv, shallowDeriv, coastDeriv};
    }

    /**
     * 地形实际可达的归一化高程 e 区间 [min, max]，供预览色阶跟随地形真实高度渲染。
     * 注意 e 只是 HeightCurve 的坐标：实际高度由曲线/生成的控制点值决定，而非地形类型分类阈值。
     *  - 海洋最深处 = HeightCurve 海洋样条最深的「深度控制点值」deepOceanDepth（≈-0.35，含海床噪声），
     *    不是海洋类型分类阈值 deepOceanLo（≈-0.50，仅分类用、地形实际达不到那么深）。
     *  - 陆地最高处 = 各地形类型内层样条 e 上限最大者（SplineConfig.maxLandHi()，MOUNTAINS hi = 0.95），
     *    再加大陆斜升（≤0.15）逼近 clamp 上限 1.0。统一从 SplineConfig 派生，消除与 TerrainParams 冗余字段的不一致。
     */
    public double[] elevationERange() {
        // 陆地 e 上限统一从 SplineConfig 陆地内层样条 hi 最大值派生（替代已删除的 *Center/*HalfRange）。
        double eMax = splineConfig.maxLandHi();
        // 海洋最深取样条深度控制点值（实际海床），而非分类阈值
        double eMin = deepOceanDepth;
        return new double[]{eMin, eMax};
    }

    // `provinceSoftmaxWeights()` 已删除（2026-11-07）：它是省系统（provinceScale /
    // craton|belt|plateau|basinWeight）的最后一个消费者，而那批键在生产路径零读取。
    // 该方法是全仓零调用的便捷访问器，随键一并删除，不留恒常占位实现。

    // ===== Phase 1：统一样条构建方法 =====

    /**
     * 构建统一样条（Phase 1：2 层嵌套）。
     */
    public UnifiedSpline buildUnifiedSpline() {
        return splineConfig.build();
    }
}
