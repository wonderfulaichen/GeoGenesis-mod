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
            maxY.get(), (int)(maxY.get() * 0.8), (int)(minY.get() * 0.75)
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
            maxY.getDefault(), (int)(maxY.getDefault() * 0.8), (int)(minY.getDefault() * 0.75)
        );
    }
}
