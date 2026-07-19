package com.geogenesis.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * GeoGenesis Forge COMMON 配置。
 *
 * 包含所有地形参数（大陆场、海洋样条、海岸、海床、省权重、过程形态）。
 * 由 {@code buildParams()} 导出为 TerrainParams 注入引擎。
 *
 * 铁律：增删字段须同步 TerrainParams(defaults) + GeoGenesisGenerator(configParams/configRiverSettings) +
 * GeoGenesisConfigScreen.buildParams + run/config/geogenesis-common.toml。
 */
public final class GeoGenesisConfig {

    public static final ForgeConfigSpec SPEC;
    public static final GeoGenesisConfig INSTANCE;

    // ===== 大陆场 =====
    public final ForgeConfigSpec.DoubleValue continentScale;
    public final ForgeConfigSpec.DoubleValue continentWarp;
    public final ForgeConfigSpec.DoubleValue continentThreshold;
    public final ForgeConfigSpec.DoubleValue continentBias;
    public final ForgeConfigSpec.DoubleValue continentProvinceWarp;

    // ===== 海洋样条控制点 =====
    public final ForgeConfigSpec.DoubleValue deepOceanLoc;
    public final ForgeConfigSpec.DoubleValue shelfLoc;
    public final ForgeConfigSpec.DoubleValue shallowLoc;
    public final ForgeConfigSpec.DoubleValue coastLoc;
    public final ForgeConfigSpec.DoubleValue deepOceanDepth;
    public final ForgeConfigSpec.DoubleValue shelfDepth;
    public final ForgeConfigSpec.DoubleValue shallowDepth;
    public final ForgeConfigSpec.DoubleValue deepOceanDeriv;
    public final ForgeConfigSpec.DoubleValue shelfDeriv;
    public final ForgeConfigSpec.DoubleValue shallowDeriv;
    public final ForgeConfigSpec.DoubleValue coastDeriv;

    // ===== 海床 =====
    public final ForgeConfigSpec.DoubleValue seabedDetail;

    // ===== [已废弃] 省权重 =====
    // 省系统已被地形类型系统取代（TypeLandShape + TypeGenerators）。
    // 这些字段保留仅供旧 config 文件兼容，新引擎不再读取。
    public final ForgeConfigSpec.DoubleValue provinceScale;
    public final ForgeConfigSpec.DoubleValue cratonWeight;
    public final ForgeConfigSpec.DoubleValue beltWeight;
    public final ForgeConfigSpec.DoubleValue plateauWeight;
    public final ForgeConfigSpec.DoubleValue basinWeight;

    // ===== [已废弃] 陆地过程形态（旧省系统） =====
    // 这些字段被类型系统取代，不再读取。保留供旧 config 兼容。
    public final ForgeConfigSpec.DoubleValue plainBase;
    public final ForgeConfigSpec.DoubleValue plainRough;
    public final ForgeConfigSpec.DoubleValue hillsLow;
    public final ForgeConfigSpec.DoubleValue hillsHigh;
    public final ForgeConfigSpec.DoubleValue beltRidgePower;
    public final ForgeConfigSpec.DoubleValue beltFoothill;
    public final ForgeConfigSpec.DoubleValue beltPeak;
    public final ForgeConfigSpec.DoubleValue plateauBase;
    public final ForgeConfigSpec.DoubleValue plateauTop;
    public final ForgeConfigSpec.IntValue plateauSteps;
    public final ForgeConfigSpec.DoubleValue plateauStepStrength;
    public final ForgeConfigSpec.DoubleValue basinBase;

    // ===== 地形起伏振幅（每省固有） =====
    public final ForgeConfigSpec.DoubleValue cratonReliefAmp;
    public final ForgeConfigSpec.DoubleValue beltReliefAmp;
    public final ForgeConfigSpec.DoubleValue plateauReliefAmp;
    public final ForgeConfigSpec.DoubleValue basinReliefAmp;

    // ===== 省场形态曲线 =====
    public final ForgeConfigSpec.DoubleValue beltSharpness;
    public final ForgeConfigSpec.DoubleValue beltWarpAmp;
    public final ForgeConfigSpec.DoubleValue provMixSharpness;
    public final ForgeConfigSpec.DoubleValue mountainMaskScale;
    public final ForgeConfigSpec.DoubleValue microDetailScale;
    public final ForgeConfigSpec.DoubleValue microDetailAmp;

    // ===== 分类阈值 + 雪线 =====
    public final ForgeConfigSpec.DoubleValue elevHigh;
    public final ForgeConfigSpec.DoubleValue reliefHigh;
    public final ForgeConfigSpec.DoubleValue peakE;
    public final ForgeConfigSpec.DoubleValue snowLine;
    public final ForgeConfigSpec.DoubleValue snowLatitudeInfluence;

    // ===== 世界高度 =====
    public final ForgeConfigSpec.IntValue seaLevel;
    public final ForgeConfigSpec.IntValue minY;
    public final ForgeConfigSpec.IntValue maxY;

    // ===== 地形类型 eLand 高度范围 =====
    public final ForgeConfigSpec.DoubleValue plainCenter;
    public final ForgeConfigSpec.DoubleValue plainHalfRange;
    public final ForgeConfigSpec.DoubleValue hillsCenter;
    public final ForgeConfigSpec.DoubleValue hillsHalfRange;
    public final ForgeConfigSpec.DoubleValue mountainsCenter;
    public final ForgeConfigSpec.DoubleValue mountainsHalfRange;
    public final ForgeConfigSpec.DoubleValue plateauCenter;
    public final ForgeConfigSpec.DoubleValue plateauHalfRange;
    public final ForgeConfigSpec.DoubleValue basinCenter;
    public final ForgeConfigSpec.DoubleValue basinHalfRange;

    // ===== Phase 1: Unified Spline Config (74 fields) =====
    // --- Outer spline: continentality c control points (7 × 2 = 14) ---
    public final ForgeConfigSpec.DoubleValue splineOuterLoc0;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv0;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc1;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv1;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc2;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv2;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc3;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv3;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc4;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv4;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc5;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv5;
    public final ForgeConfigSpec.DoubleValue splineOuterLoc6;
    public final ForgeConfigSpec.DoubleValue splineOuterDeriv6;

    // --- Inner spline: PLAIN type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue plainLoLoc0;
    public final ForgeConfigSpec.DoubleValue plainLoVal0;
    public final ForgeConfigSpec.DoubleValue plainLoDeriv0;
    public final ForgeConfigSpec.DoubleValue plainLoLoc1;
    public final ForgeConfigSpec.DoubleValue plainLoVal1;
    public final ForgeConfigSpec.DoubleValue plainLoDeriv1;
    public final ForgeConfigSpec.DoubleValue plainHiLoc0;
    public final ForgeConfigSpec.DoubleValue plainHiVal0;
    public final ForgeConfigSpec.DoubleValue plainHiDeriv0;
    public final ForgeConfigSpec.DoubleValue plainHiLoc1;
    public final ForgeConfigSpec.DoubleValue plainHiVal1;
    public final ForgeConfigSpec.DoubleValue plainHiDeriv1;

    // --- Inner spline: HILLS type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue hillsLoLoc0;
    public final ForgeConfigSpec.DoubleValue hillsLoVal0;
    public final ForgeConfigSpec.DoubleValue hillsLoDeriv0;
    public final ForgeConfigSpec.DoubleValue hillsLoLoc1;
    public final ForgeConfigSpec.DoubleValue hillsLoVal1;
    public final ForgeConfigSpec.DoubleValue hillsLoDeriv1;
    public final ForgeConfigSpec.DoubleValue hillsHiLoc0;
    public final ForgeConfigSpec.DoubleValue hillsHiVal0;
    public final ForgeConfigSpec.DoubleValue hillsHiDeriv0;
    public final ForgeConfigSpec.DoubleValue hillsHiLoc1;
    public final ForgeConfigSpec.DoubleValue hillsHiVal1;
    public final ForgeConfigSpec.DoubleValue hillsHiDeriv1;

    // --- Inner spline: MOUNTAINS type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue mountLoLoc0;
    public final ForgeConfigSpec.DoubleValue mountLoVal0;
    public final ForgeConfigSpec.DoubleValue mountLoDeriv0;
    public final ForgeConfigSpec.DoubleValue mountLoLoc1;
    public final ForgeConfigSpec.DoubleValue mountLoVal1;
    public final ForgeConfigSpec.DoubleValue mountLoDeriv1;
    public final ForgeConfigSpec.DoubleValue mountHiLoc0;
    public final ForgeConfigSpec.DoubleValue mountHiVal0;
    public final ForgeConfigSpec.DoubleValue mountHiDeriv0;
    public final ForgeConfigSpec.DoubleValue mountHiLoc1;
    public final ForgeConfigSpec.DoubleValue mountHiVal1;
    public final ForgeConfigSpec.DoubleValue mountHiDeriv1;

    // --- Inner spline: PLATEAU type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue platLoLoc0;
    public final ForgeConfigSpec.DoubleValue platLoVal0;
    public final ForgeConfigSpec.DoubleValue platLoDeriv0;
    public final ForgeConfigSpec.DoubleValue platLoLoc1;
    public final ForgeConfigSpec.DoubleValue platLoVal1;
    public final ForgeConfigSpec.DoubleValue platLoDeriv1;
    public final ForgeConfigSpec.DoubleValue platHiLoc0;
    public final ForgeConfigSpec.DoubleValue platHiVal0;
    public final ForgeConfigSpec.DoubleValue platHiDeriv0;
    public final ForgeConfigSpec.DoubleValue platHiLoc1;
    public final ForgeConfigSpec.DoubleValue platHiVal1;
    public final ForgeConfigSpec.DoubleValue platHiDeriv1;

    // --- Inner spline: BASIN type (12 fields) ---
    public final ForgeConfigSpec.DoubleValue basinLoLoc0;
    public final ForgeConfigSpec.DoubleValue basinLoVal0;
    public final ForgeConfigSpec.DoubleValue basinLoDeriv0;
    public final ForgeConfigSpec.DoubleValue basinLoLoc1;
    public final ForgeConfigSpec.DoubleValue basinLoVal1;
    public final ForgeConfigSpec.DoubleValue basinLoDeriv1;
    public final ForgeConfigSpec.DoubleValue basinHiLoc0;
    public final ForgeConfigSpec.DoubleValue basinHiVal0;
    public final ForgeConfigSpec.DoubleValue basinHiDeriv0;
    public final ForgeConfigSpec.DoubleValue basinHiLoc1;
    public final ForgeConfigSpec.DoubleValue basinHiVal1;
    public final ForgeConfigSpec.DoubleValue basinHiDeriv1;

    // ===== Phase 2: Mid Spline Config (type distribution) =====
    // Note: MidSplineConfig has 105 fields (7 nodes × 5 types × 3 fields).
    // For TOML config, we use a single MidSplineConfig object rather than 105 individual fields.
    // The MidSplineConfig is built from defaults or loaded from a separate config file.
    private final com.geogenesis.worldgen.terrain.MidSplineConfig midSplineConfig;

    static {
        Pair<GeoGenesisConfig, ForgeConfigSpec> pair =
            new ForgeConfigSpec.Builder().configure(GeoGenesisConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    private GeoGenesisConfig(ForgeConfigSpec.Builder builder) {
        builder.push("GeoGenesis Terrain Generation");

        builder.push("Continent Field");
        continentScale = builder.comment("Continent noise scale (blocks). Frequency = 1/scale.")
            .defineInRange("continentScale", 4000.0, 200.0, 10000.0);
        continentWarp = builder.comment("Domain warp intensity.")
            .defineInRange("continentWarp", 0.2, 0.0, 1.0);
        continentThreshold = builder.comment("Reference continentality c value ∈ [-1,1]. NOT the coastline; coastline = e=0 natural crossing (see terrain-rebuild §1.4). 0=coast anchor, negative=ocean, positive=land — aligned with vanilla Continentalness.")
            .defineInRange("continentThreshold", 0.0, -1.0, 1.0);
        continentBias = builder.comment("Land/ocean ratio bias. Positive = more ocean, negative = more land.")
            .defineInRange("continentBias", 0.4, -0.6, 0.6);
        continentProvinceWarp = builder.comment("Continent field warp amplitude for province sampling (blocks). EXPERIMENTAL: warping by c biases mountain ridge direction toward the c gradient (see GeoGenesisMod discussion). Safe default 0 = disable. Enable only if you understand the directional bias.")
            .defineInRange("continentProvinceWarp", 0.0, 0.0, 8000.0);
        builder.pop();

        builder.push("Ocean Spline Control Points");
        deepOceanLoc = builder.defineInRange("deepOceanLoc", -0.80, -1.0, 1.0);
        shelfLoc = builder.defineInRange("shelfLoc", -0.50, -1.0, 1.0);
        shallowLoc = builder.defineInRange("shallowLoc", -0.16, -1.0, 1.0);
        coastLoc = builder.defineInRange("coastLoc", -0.04, -1.0, 1.0);
        deepOceanDepth = builder.comment("Deep ocean depth (e units). -0.35 ≈ y=19 floor, adjust for gameplay depth taste.")
            .defineInRange("deepOceanDepth", -0.35, -1.0, 0.0);
        shelfDepth = builder.defineInRange("shelfDepth", -0.25, -1.0, 0.0);
        shallowDepth = builder.defineInRange("shallowDepth", -0.06, -1.0, 0.0);
        deepOceanDeriv = builder.defineInRange("deepOceanDeriv", 0.0, -10.0, 10.0);
        shelfDeriv = builder.comment("Shelf derivative steepness: higher = steeper continental slope. Default 0.8.")
            .defineInRange("shelfDeriv", 0.8, -10.0, 10.0);
        shallowDeriv = builder.defineInRange("shallowDeriv", 0.0, -10.0, 10.0);
        coastDeriv = builder.defineInRange("coastDeriv", 0.0, -10.0, 10.0);
        builder.pop();

        builder.push("Seabed");
        seabedDetail = builder.comment("Seabed detail amplitude (e units).")
            .defineInRange("seabedDetail", 0.03, 0.0, 0.2);
        builder.pop();

        builder.push("Province Weights");
        provinceScale = builder.defineInRange("provinceScale", 4000.0, 500.0, 10000.0);
        cratonWeight = builder.defineInRange("cratonWeight", 1.5, 0.0, 5.0);
        beltWeight = builder.defineInRange("beltWeight", 3.5, 0.0, 5.0);
        plateauWeight = builder.defineInRange("plateauWeight", 2.0, 0.0, 5.0);
        basinWeight = builder.defineInRange("basinWeight", 0.8, 0.0, 5.0);
        builder.pop();

        builder.push("Land Process Parameters");
        plainBase = builder.defineInRange("plainBase", 0.01, 0.0, 0.3);
        plainRough = builder.defineInRange("plainRough", 0.04, 0.0, 0.3);
        hillsLow = builder.defineInRange("hillsLow", 0.10, 0.0, 0.5);
        hillsHigh = builder.defineInRange("hillsHigh", 0.25, 0.0, 0.7);
        beltRidgePower = builder.defineInRange("beltRidgePower", 1.5, 0.0, 5.0);
        beltFoothill = builder.defineInRange("beltFoothill", 0.15, 0.0, 0.5);
        beltPeak = builder.defineInRange("beltPeak", 0.95, 0.0, 1.0);
        plateauBase = builder.defineInRange("plateauBase", 0.55, 0.0, 1.0);
        plateauTop = builder.defineInRange("plateauTop", 0.72, 0.0, 1.0);
        plateauSteps = builder.comment("Number of plateau terraces.")
            .defineInRange("plateauSteps", 3, 1, 10);
        plateauStepStrength = builder.defineInRange("plateauStepStrength", 0.6, 0.0, 2.0);
        basinBase = builder.defineInRange("basinBase", 0.02, 0.0, 0.3);
        builder.pop();

        builder.push("Relief Amplitudes");
        cratonReliefAmp = builder.comment("Craton local relief amplitude (e units). Plains/low hills. Kept moderate so HILLS class still forms; denseness is fixed via LandShape frequency, not by killing amplitude.")
            .defineInRange("cratonReliefAmp", 0.10, 0.0, 0.6);
        beltReliefAmp = builder.comment("Orogenic belt relief amplitude (e units). Rugged mountains. Moderate value preserves MOUNTAINS class; fingerprint look fixed by larger detail frequency in LandShape.")
            .defineInRange("beltReliefAmp", 0.22, 0.0, 0.6);
        plateauReliefAmp = builder.comment("Plateau relief amplitude (e units). Flat-topped, low relief.")
            .defineInRange("plateauReliefAmp", 0.03, 0.0, 0.6);
        basinReliefAmp = builder.comment("Basin relief amplitude (e units).")
            .defineInRange("basinReliefAmp", 0.05, 0.0, 0.6);
        builder.pop();

        builder.push("Terrain Shape");
        beltSharpness = builder.comment("Belt (orogenic) peak sharpness: pow(n, sharpness). >1 = pointed peaks, =1 = round, <1 = broad. Default 1.5.")
            .defineInRange("beltSharpness", 1.5, 0.5, 5.0);
        beltWarpAmp = builder.comment("Belt domain warp displacement amplitude (world-space blocks). Warp elongates massifs into natural ranges; 0 = disable warp. Default 120.")
            .defineInRange("beltWarpAmp", 120.0, 0.0, 500.0);
        provMixSharpness = builder.comment("Province mix sharpening exponent (softmax weight power). >1 lets dominant province own its region and express full |r|=1 peak / r=0 valley; boundaries stay smooth. Default 2.5.")
            .defineInRange("provMixSharpness", 2.5, 1.0, 5.0);
        mountainMaskScale = builder.comment("Mountain mask noise scale (world-space blocks). Low-frequency blobs limit mountains to belts/clusters (realism key, ref TerraForged). Default 2200.")
            .defineInRange("mountainMaskScale", 2200.0, 400.0, 8000.0);
        microDetailScale = builder.comment("Surface micro-detail noise scale (blocks). Large period, tiny amplitude (<0.03 e) for texture only, not terrain. Default 450.")
            .defineInRange("microDetailScale", 450.0, 200.0, 2000.0);
        microDetailAmp = builder.comment("Surface micro-detail amplitude (e units). Must stay tiny (<0.03) to avoid competing with relief. Default 0.025.")
            .defineInRange("microDetailAmp", 0.025, 0.0, 0.08);
        builder.pop();

        builder.push("Classification & Snow");
        elevHigh = builder.comment("Elevation×Relief classification: high-elevation threshold (elevation e). Low=more mountains/plateau.")
            .defineInRange("elevHigh", 0.25, 0.0, 1.0);
        reliefHigh = builder.comment("Elevation×Relief classification: high-relief threshold (|relief| e). Low=plain only truly flat.")
            .defineInRange("reliefHigh", 0.04, 0.0, 0.6);
        peakE = builder.comment("Elevation×Relief classification: peak threshold (elevation e); PEAK = MOUNTAINS sub-type.")
            .defineInRange("peakE", 0.82, 0.0, 1.0);
        snowLine = builder.comment("Snow line elevation (e units). High-elevation terrain above this (modulated by latitude) gets snow cover.")
            .defineInRange("snowLine", 0.70, 0.0, 1.0);
        snowLatitudeInfluence = builder.comment("Snow line latitude coupling: snow line rises toward the warm end (e units per half-range).")
            .defineInRange("snowLatitudeInfluence", 0.25, 0.0, 0.6);
        builder.pop();



        builder.push("World Height");
        seaLevel = builder.comment("Sea level (world Y).")
            .defineInRange("seaLevel", 63, -200, 400);
        minY = builder.comment("Minimum world Y.")
            .defineInRange("minY", -64, -512, 512);
        maxY = builder.comment("Maximum world Y.")
            .defineInRange("maxY", 320, -512, 1024);
        builder.pop();

        builder.push("Type Elevation Ranges");
        plainCenter = builder.comment("PLAIN center eLand value.")
            .defineInRange("plainCenter", 0.0375, 0.0, 1.0);
        plainHalfRange = builder.comment("PLAIN half-range eLand (output = center ± halfRange).")
            .defineInRange("plainHalfRange", 0.0225, 0.0, 0.5);
        hillsCenter = builder.comment("HILLS center eLand value.")
            .defineInRange("hillsCenter", 0.22, 0.0, 1.0);
        hillsHalfRange = builder.comment("HILLS half-range eLand.")
            .defineInRange("hillsHalfRange", 0.15, 0.0, 0.5);
        mountainsCenter = builder.comment("MOUNTAINS center eLand value.")
            .defineInRange("mountainsCenter", 0.60, 0.0, 1.0);
        mountainsHalfRange = builder.comment("MOUNTAINS half-range eLand.")
            .defineInRange("mountainsHalfRange", 0.35, 0.0, 0.5);
        plateauCenter = builder.comment("PLATEAU center eLand value.")
            .defineInRange("plateauCenter", 0.33, 0.0, 1.0);
        plateauHalfRange = builder.comment("PLATEAU half-range eLand.")
            .defineInRange("plateauHalfRange", 0.15, 0.0, 0.5);
        basinCenter = builder.comment("BASIN center eLand value.")
            .defineInRange("basinCenter", 0.0475, 0.0, 1.0);
        basinHalfRange = builder.comment("BASIN half-range eLand.")
            .defineInRange("basinHalfRange", 0.0325, 0.0, 0.5);
        builder.pop();

        builder.push("Phase 1 Unified Spline");
        // Outer spline control points (7 × 2 = 14 fields)
        splineOuterLoc0 = builder.comment("Outer spline point 0 location (deep ocean). Default -0.80.")
            .defineInRange("splineOuterLoc0", -0.80, -1.0, 1.0);
        splineOuterDeriv0 = builder.comment("Outer spline point 0 derivative.")
            .defineInRange("splineOuterDeriv0", 0.0, -10.0, 10.0);
        splineOuterLoc1 = builder.comment("Outer spline point 1 location (continental shelf). Default -0.50.")
            .defineInRange("splineOuterLoc1", -0.50, -1.0, 1.0);
        splineOuterDeriv1 = builder.comment("Outer spline point 1 derivative.")
            .defineInRange("splineOuterDeriv1", 0.0, -10.0, 10.0);
        splineOuterLoc2 = builder.comment("Outer spline point 2 location (shallow sea). Default -0.16.")
            .defineInRange("splineOuterLoc2", -0.16, -1.0, 1.0);
        splineOuterDeriv2 = builder.comment("Outer spline point 2 derivative.")
            .defineInRange("splineOuterDeriv2", 0.0, -10.0, 10.0);
        splineOuterLoc3 = builder.comment("Outer spline point 3 location (coastline). Default -0.04.")
            .defineInRange("splineOuterLoc3", -0.04, -1.0, 1.0);
        splineOuterDeriv3 = builder.comment("Outer spline point 3 derivative.")
            .defineInRange("splineOuterDeriv3", 0.0, -10.0, 10.0);
        splineOuterLoc4 = builder.comment("Outer spline point 4 location (near-shore land). Default 0.20.")
            .defineInRange("splineOuterLoc4", 0.20, -1.0, 1.0);
        splineOuterDeriv4 = builder.comment("Outer spline point 4 derivative.")
            .defineInRange("splineOuterDeriv4", 0.0, -10.0, 10.0);
        splineOuterLoc5 = builder.comment("Outer spline point 5 location (inland). Default 0.50.")
            .defineInRange("splineOuterLoc5", 0.50, -1.0, 1.0);
        splineOuterDeriv5 = builder.comment("Outer spline point 5 derivative.")
            .defineInRange("splineOuterDeriv5", 0.0, -10.0, 10.0);
        splineOuterLoc6 = builder.comment("Outer spline point 6 location (deep inland). Default 0.80.")
            .defineInRange("splineOuterLoc6", 0.80, -1.0, 1.0);
        splineOuterDeriv6 = builder.comment("Outer spline point 6 derivative.")
            .defineInRange("splineOuterDeriv6", 0.0, -10.0, 10.0);

        // PLAIN inner spline (12 fields)
        builder.push("PLAIN");
        plainLoLoc0 = builder.defineInRange("plainLoLoc0", 0.0, 0.0, 1.0);
        plainLoVal0 = builder.defineInRange("plainLoVal0", 0.015, -1.0, 1.0);
        plainLoDeriv0 = builder.defineInRange("plainLoDeriv0", 0.0, -10.0, 10.0);
        plainLoLoc1 = builder.defineInRange("plainLoLoc1", 1.0, 0.0, 1.0);
        plainLoVal1 = builder.defineInRange("plainLoVal1", 0.015, -1.0, 1.0);
        plainLoDeriv1 = builder.defineInRange("plainLoDeriv1", 0.0, -10.0, 10.0);
        plainHiLoc0 = builder.defineInRange("plainHiLoc0", 0.0, 0.0, 1.0);
        plainHiVal0 = builder.defineInRange("plainHiVal0", 0.06, -1.0, 1.0);
        plainHiDeriv0 = builder.defineInRange("plainHiDeriv0", 0.0, -10.0, 10.0);
        plainHiLoc1 = builder.defineInRange("plainHiLoc1", 1.0, 0.0, 1.0);
        plainHiVal1 = builder.defineInRange("plainHiVal1", 0.06, -1.0, 1.0);
        plainHiDeriv1 = builder.defineInRange("plainHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // HILLS inner spline (12 fields)
        builder.push("HILLS");
        hillsLoLoc0 = builder.defineInRange("hillsLoLoc0", 0.0, 0.0, 1.0);
        hillsLoVal0 = builder.defineInRange("hillsLoVal0", 0.06, -1.0, 1.0);
        hillsLoDeriv0 = builder.defineInRange("hillsLoDeriv0", 0.0, -10.0, 10.0);
        hillsLoLoc1 = builder.defineInRange("hillsLoLoc1", 1.0, 0.0, 1.0);
        hillsLoVal1 = builder.defineInRange("hillsLoVal1", 0.06, -1.0, 1.0);
        hillsLoDeriv1 = builder.defineInRange("hillsLoDeriv1", 0.0, -10.0, 10.0);
        hillsHiLoc0 = builder.defineInRange("hillsHiLoc0", 0.0, 0.0, 1.0);
        hillsHiVal0 = builder.defineInRange("hillsHiVal0", 0.25, -1.0, 1.0);
        hillsHiDeriv0 = builder.defineInRange("hillsHiDeriv0", 0.0, -10.0, 10.0);
        hillsHiLoc1 = builder.defineInRange("hillsHiLoc1", 1.0, 0.0, 1.0);
        hillsHiVal1 = builder.defineInRange("hillsHiVal1", 0.25, -1.0, 1.0);
        hillsHiDeriv1 = builder.defineInRange("hillsHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // MOUNTAINS inner spline (12 fields)
        builder.push("MOUNTAINS");
        mountLoLoc0 = builder.defineInRange("mountLoLoc0", 0.0, 0.0, 1.0);
        mountLoVal0 = builder.defineInRange("mountLoVal0", 0.45, -1.0, 1.0);
        mountLoDeriv0 = builder.defineInRange("mountLoDeriv0", 0.0, -10.0, 10.0);
        mountLoLoc1 = builder.defineInRange("mountLoLoc1", 1.0, 0.0, 1.0);
        mountLoVal1 = builder.defineInRange("mountLoVal1", 0.45, -1.0, 1.0);
        mountLoDeriv1 = builder.defineInRange("mountLoDeriv1", 0.0, -10.0, 10.0);
        mountHiLoc0 = builder.defineInRange("mountHiLoc0", 0.0, 0.0, 1.0);
        mountHiVal0 = builder.defineInRange("mountHiVal0", 0.95, -1.0, 1.0);
        mountHiDeriv0 = builder.defineInRange("mountHiDeriv0", 0.0, -10.0, 10.0);
        mountHiLoc1 = builder.defineInRange("mountHiLoc1", 1.0, 0.0, 1.0);
        mountHiVal1 = builder.defineInRange("mountHiVal1", 0.95, -1.0, 1.0);
        mountHiDeriv1 = builder.defineInRange("mountHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // PLATEAU inner spline (12 fields)
        builder.push("PLATEAU");
        platLoLoc0 = builder.defineInRange("platLoLoc0", 0.0, 0.0, 1.0);
        platLoVal0 = builder.defineInRange("platLoVal0", 0.20, -1.0, 1.0);
        platLoDeriv0 = builder.defineInRange("platLoDeriv0", 0.0, -10.0, 10.0);
        platLoLoc1 = builder.defineInRange("platLoLoc1", 1.0, 0.0, 1.0);
        platLoVal1 = builder.defineInRange("platLoVal1", 0.20, -1.0, 1.0);
        platLoDeriv1 = builder.defineInRange("platLoDeriv1", 0.0, -10.0, 10.0);
        platHiLoc0 = builder.defineInRange("platHiLoc0", 0.0, 0.0, 1.0);
        platHiVal0 = builder.defineInRange("platHiVal0", 0.45, -1.0, 1.0);
        platHiDeriv0 = builder.defineInRange("platHiDeriv0", 0.0, -10.0, 10.0);
        platHiLoc1 = builder.defineInRange("platHiLoc1", 1.0, 0.0, 1.0);
        platHiVal1 = builder.defineInRange("platHiVal1", 0.45, -1.0, 1.0);
        platHiDeriv1 = builder.defineInRange("platHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        // BASIN inner spline (12 fields)
        builder.push("BASIN");
        basinLoLoc0 = builder.defineInRange("basinLoLoc0", 0.0, 0.0, 1.0);
        basinLoVal0 = builder.defineInRange("basinLoVal0", 0.015, -1.0, 1.0);
        basinLoDeriv0 = builder.defineInRange("basinLoDeriv0", 0.0, -10.0, 10.0);
        basinLoLoc1 = builder.defineInRange("basinLoLoc1", 1.0, 0.0, 1.0);
        basinLoVal1 = builder.defineInRange("basinLoVal1", 0.015, -1.0, 1.0);
        basinLoDeriv1 = builder.defineInRange("basinLoDeriv1", 0.0, -10.0, 10.0);
        basinHiLoc0 = builder.defineInRange("basinHiLoc0", 0.0, 0.0, 1.0);
        basinHiVal0 = builder.defineInRange("basinHiVal0", 0.08, -1.0, 1.0);
        basinHiDeriv0 = builder.defineInRange("basinHiDeriv0", 0.0, -10.0, 10.0);
        basinHiLoc1 = builder.defineInRange("basinHiLoc1", 1.0, 0.0, 1.0);
        basinHiVal1 = builder.defineInRange("basinHiVal1", 0.08, -1.0, 1.0);
        basinHiDeriv1 = builder.defineInRange("basinHiDeriv1", 0.0, -10.0, 10.0);
        builder.pop();

        builder.pop(); // Phase 1 Unified Spline

        // Phase 2: Initialize MidSplineConfig from defaults
        // Note: MidSplineConfig has 105 fields (7 nodes × 5 types × 3 fields).
        // For TOML config, we use defaults rather than 105 individual fields.
        // Users can override via a separate config file or API.
        this.midSplineConfig = com.geogenesis.worldgen.terrain.MidSplineConfig.defaults();

        builder.pop(); // GeoGenesis Terrain Generation
    }

    /**
     * 导出 TerrainParams（底层引擎所需）。
     *
     * 仅供游戏运行时调用：依赖 Forge 配置已 load()，通过 {@code ConfigValue.get()} 取实时值。
     */
    public com.geogenesis.worldgen.terrain.TerrainParams buildParams() {
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.get(), continentWarp.get(), continentThreshold.get(), continentBias.get(), continentProvinceWarp.get(),
            deepOceanLoc.get(), shelfLoc.get(), shallowLoc.get(), coastLoc.get(),
            deepOceanDepth.get(), shelfDepth.get(), shallowDepth.get(),
            deepOceanDeriv.get(), shelfDeriv.get(), shallowDeriv.get(), coastDeriv.get(),
            seabedDetail.get(),
            provinceScale.get(), cratonWeight.get(), beltWeight.get(),
            plateauWeight.get(), basinWeight.get(),
            plainBase.get(), plainRough.get(), hillsLow.get(), hillsHigh.get(),
            beltRidgePower.get(), beltFoothill.get(), beltPeak.get(),
            plateauBase.get(), plateauTop.get(), plateauSteps.get(), plateauStepStrength.get(),
            basinBase.get(),
            cratonReliefAmp.get(), beltReliefAmp.get(), plateauReliefAmp.get(), basinReliefAmp.get(),
            beltSharpness.get(), beltWarpAmp.get(), provMixSharpness.get(),
            mountainMaskScale.get(), microDetailScale.get(), microDetailAmp.get(),
            elevHigh.get(), reliefHigh.get(), peakE.get(), snowLatitudeInfluence.get(),

            // preview compat defaults
            1.0, seaLevel.get(), snowLine.get(), minY.get(),
            maxY.get(), (int)(maxY.get() * 0.8), (int)(minY.get() * 0.75),
            // type elevation ranges
            plainCenter.get(), plainHalfRange.get(),
            hillsCenter.get(), hillsHalfRange.get(),
            mountainsCenter.get(), mountainsHalfRange.get(),
            plateauCenter.get(), plateauHalfRange.get(),
            basinCenter.get(), basinHalfRange.get(),

            // Phase 1: unified spline config (built from individual fields)
            buildSplineConfig()
        );
    }

    /** 从独立配置字段构建 SplineConfig（Phase 3：含海洋/水域类型） */
    private com.geogenesis.worldgen.terrain.SplineConfig buildSplineConfig() {
        return new com.geogenesis.worldgen.terrain.SplineConfig(
            splineOuterLoc0.get(), splineOuterDeriv0.get(),
            splineOuterLoc1.get(), splineOuterDeriv1.get(),
            splineOuterLoc2.get(), splineOuterDeriv2.get(),
            splineOuterLoc3.get(), splineOuterDeriv3.get(),
            splineOuterLoc4.get(), splineOuterDeriv4.get(),
            splineOuterLoc5.get(), splineOuterDeriv5.get(),
            splineOuterLoc6.get(), splineOuterDeriv6.get(),
            plainLoLoc0.get(), plainLoVal0.get(), plainLoDeriv0.get(),
            plainLoLoc1.get(), plainLoVal1.get(), plainLoDeriv1.get(),
            plainHiLoc0.get(), plainHiVal0.get(), plainHiDeriv0.get(),
            plainHiLoc1.get(), plainHiVal1.get(), plainHiDeriv1.get(),
            hillsLoLoc0.get(), hillsLoVal0.get(), hillsLoDeriv0.get(),
            hillsLoLoc1.get(), hillsLoVal1.get(), hillsLoDeriv1.get(),
            hillsHiLoc0.get(), hillsHiVal0.get(), hillsHiDeriv0.get(),
            hillsHiLoc1.get(), hillsHiVal1.get(), hillsHiDeriv1.get(),
            mountLoLoc0.get(), mountLoVal0.get(), mountLoDeriv0.get(),
            mountLoLoc1.get(), mountLoVal1.get(), mountLoDeriv1.get(),
            mountHiLoc0.get(), mountHiVal0.get(), mountHiDeriv0.get(),
            mountHiLoc1.get(), mountHiVal1.get(), mountHiDeriv1.get(),
            platLoLoc0.get(), platLoVal0.get(), platLoDeriv0.get(),
            platLoLoc1.get(), platLoVal1.get(), platLoDeriv1.get(),
            platHiLoc0.get(), platHiVal0.get(), platHiDeriv0.get(),
            platHiLoc1.get(), platHiVal1.get(), platHiDeriv1.get(),
            basinLoLoc0.get(), basinLoVal0.get(), basinLoDeriv0.get(),
            basinLoLoc1.get(), basinLoVal1.get(), basinLoDeriv1.get(),
            basinHiLoc0.get(), basinHiVal0.get(), basinHiDeriv0.get(),
            basinHiLoc1.get(), basinHiVal1.get(), basinHiDeriv1.get(),
            // Phase 3: ocean/water type inner splines (use defaults)
            com.geogenesis.worldgen.terrain.OceanSplineConfig.defaults(),
            // Phase 2: mid spline config
            midSplineConfig
        );
    }

    /**
     * 导出 TerrainParams 的默认值版本，供独立预览（{@code runPreview}）使用。
     *
     * <p>独立预览脱离 Minecraft/Forge 运行时，Forge 配置从未 load()，调用 {@link #buildParams()}
     * 的 {@code ConfigValue.get()} 会抛 {@code IllegalStateException: Cannot get config value before config is loaded}。
     * 此处读取 spec 的默认值（{@code getDefault()}），与游戏内默认地形保持一致，且无需加载配置。
     *
     * <p>若日后 spec 默认值变更，此方法随 {@link #buildParams()} 同步更新即可，避免与
     * {@code TerrainParams.defaults()} 那样的硬编码副本再次漂移。
     */
    public com.geogenesis.worldgen.terrain.TerrainParams defaultParams() {
        return new com.geogenesis.worldgen.terrain.TerrainParams(
            continentScale.getDefault(), continentWarp.getDefault(), continentThreshold.getDefault(), continentBias.getDefault(), continentProvinceWarp.getDefault(),
            deepOceanLoc.getDefault(), shelfLoc.getDefault(), shallowLoc.getDefault(), coastLoc.getDefault(),
            deepOceanDepth.getDefault(), shelfDepth.getDefault(), shallowDepth.getDefault(),
            deepOceanDeriv.getDefault(), shelfDeriv.getDefault(), shallowDeriv.getDefault(), coastDeriv.getDefault(),
            seabedDetail.getDefault(),
            provinceScale.getDefault(), cratonWeight.getDefault(), beltWeight.getDefault(),
            plateauWeight.getDefault(), basinWeight.getDefault(),
            plainBase.getDefault(), plainRough.getDefault(), hillsLow.getDefault(), hillsHigh.getDefault(),
            beltRidgePower.getDefault(), beltFoothill.getDefault(), beltPeak.getDefault(),
            plateauBase.getDefault(), plateauTop.getDefault(), plateauSteps.getDefault(), plateauStepStrength.getDefault(),
            basinBase.getDefault(),
            cratonReliefAmp.getDefault(), beltReliefAmp.getDefault(), plateauReliefAmp.getDefault(), basinReliefAmp.getDefault(),
            beltSharpness.getDefault(), beltWarpAmp.getDefault(), provMixSharpness.getDefault(),
            mountainMaskScale.getDefault(), microDetailScale.getDefault(), microDetailAmp.getDefault(),
            elevHigh.getDefault(), reliefHigh.getDefault(), peakE.getDefault(), snowLatitudeInfluence.getDefault(),

            // preview compat defaults
            1.0, seaLevel.getDefault(), snowLine.getDefault(), minY.getDefault(),
            maxY.getDefault(), (int)(maxY.getDefault() * 0.8), (int)(minY.getDefault() * 0.75),
            // type elevation ranges
            plainCenter.getDefault(), plainHalfRange.getDefault(),
            hillsCenter.getDefault(), hillsHalfRange.getDefault(),
            mountainsCenter.getDefault(), mountainsHalfRange.getDefault(),
            plateauCenter.getDefault(), plateauHalfRange.getDefault(),
            basinCenter.getDefault(), basinHalfRange.getDefault(),

            // Phase 1: unified spline config (built from default values)
            buildDefaultSplineConfig()
        );
    }

    /** 从默认值构建 SplineConfig（Phase 3：含海洋/水域类型） */
    private com.geogenesis.worldgen.terrain.SplineConfig buildDefaultSplineConfig() {
        // 直接使用 SplineConfig.defaults()，因为所有字段都是默认值
        return com.geogenesis.worldgen.terrain.SplineConfig.defaults();
    }
}
