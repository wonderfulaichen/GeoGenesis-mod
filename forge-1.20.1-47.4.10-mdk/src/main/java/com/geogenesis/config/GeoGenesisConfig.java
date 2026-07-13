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

    // ===== 省权重 =====
    public final ForgeConfigSpec.DoubleValue provinceScale;
    public final ForgeConfigSpec.DoubleValue cratonWeight;
    public final ForgeConfigSpec.DoubleValue beltWeight;
    public final ForgeConfigSpec.DoubleValue plateauWeight;
    public final ForgeConfigSpec.DoubleValue basinWeight;

    // ===== 陆地过程形态 =====
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
            .defineInRange("continentScale", 1500.0, 200.0, 10000.0);
        continentWarp = builder.comment("Domain warp intensity.")
            .defineInRange("continentWarp", 0.2, 0.0, 1.0);
        continentThreshold = builder.comment("Reference continentality c value. NOT the coastline; coastline = e=0 natural crossing (see terrain-rebuild §1.4).")
            .defineInRange("continentThreshold", 0.5, 0.0, 1.0);
        continentBias = builder.comment("Land/ocean ratio bias. Positive = more ocean, negative = more land.")
            .defineInRange("continentBias", 0.0, -0.5, 0.5);
        builder.pop();

        builder.push("Ocean Spline Control Points");
        deepOceanLoc = builder.defineInRange("deepOceanLoc", 0.10, 0.0, 1.0);
        shelfLoc = builder.defineInRange("shelfLoc", 0.25, 0.0, 1.0);
        shallowLoc = builder.defineInRange("shallowLoc", 0.42, 0.0, 1.0);
        coastLoc = builder.defineInRange("coastLoc", 0.48, 0.0, 1.0);
        deepOceanDepth = builder.defineInRange("deepOceanDepth", -0.85, -1.0, 0.0);
        shelfDepth = builder.defineInRange("shelfDepth", -0.25, -1.0, 0.0);
        shallowDepth = builder.defineInRange("shallowDepth", -0.06, -1.0, 0.0);
        deepOceanDeriv = builder.defineInRange("deepOceanDeriv", 0.0, -10.0, 10.0);
        shelfDeriv = builder.defineInRange("shelfDeriv", 0.0, -10.0, 10.0);
        shallowDeriv = builder.defineInRange("shallowDeriv", 0.0, -10.0, 10.0);
        coastDeriv = builder.defineInRange("coastDeriv", 0.0, -10.0, 10.0);
        builder.pop();

        builder.push("Seabed");
        seabedDetail = builder.comment("Seabed detail amplitude (e units).")
            .defineInRange("seabedDetail", 0.03, 0.0, 0.2);
        builder.pop();

        builder.push("Province Weights");
        provinceScale = builder.defineInRange("provinceScale", 2000.0, 500.0, 10000.0);
        cratonWeight = builder.defineInRange("cratonWeight", 1.0, 0.0, 5.0);
        beltWeight = builder.defineInRange("beltWeight", 1.0, 0.0, 5.0);
        plateauWeight = builder.defineInRange("plateauWeight", 1.0, 0.0, 5.0);
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
            continentScale.get(), continentWarp.get(), continentThreshold.get(), continentBias.get(),
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
            // preview compat defaults
            1.0, seaLevel.get(), 0.3, minY.get(),
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
            continentScale.getDefault(), continentWarp.getDefault(), continentThreshold.getDefault(), continentBias.getDefault(),
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
            // preview compat defaults
            1.0, seaLevel.getDefault(), 0.3, minY.getDefault(),
            maxY.getDefault(), (int)(maxY.getDefault() * 0.8), (int)(minY.getDefault() * 0.75)
        );
    }
}
