package com.geogenesis.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

public class GeoGenesisConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    static {
        Pair<CommonConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CommonConfig::new);
        COMMON = pair.getLeft();
        COMMON_SPEC = pair.getRight();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static class CommonConfig {

        public final ForgeConfigSpec.IntValue minY;
        public final ForgeConfigSpec.IntValue maxY;
        public final ForgeConfigSpec.IntValue baseHeight;
        public final ForgeConfigSpec.IntValue seaLevel;
        public final ForgeConfigSpec.IntValue oceanDepthMax;

        public final ForgeConfigSpec.DoubleValue ridgeWeight;
        public final ForgeConfigSpec.DoubleValue cellWeight;
        public final ForgeConfigSpec.DoubleValue gullyWeight;

        public final ForgeConfigSpec.DoubleValue erosionStrength;
        public final ForgeConfigSpec.DoubleValue erosionDropsMul;
        public final ForgeConfigSpec.DoubleValue erosionErodeMul;
        public final ForgeConfigSpec.DoubleValue erosionDepositMul;
        public final ForgeConfigSpec.DoubleValue erosionBrushMul;
        public final ForgeConfigSpec.BooleanValue enableErosion;
        public final ForgeConfigSpec.BooleanValue enableRivers;

        public final ForgeConfigSpec.DoubleValue temperatureScale;
        public final ForgeConfigSpec.DoubleValue moistureScale;
        public final ForgeConfigSpec.DoubleValue continentScale;

        public final ForgeConfigSpec.DoubleValue riverDepth;
        public final ForgeConfigSpec.DoubleValue riverWidth;
        public final ForgeConfigSpec.DoubleValue precipThreshold;

        public final ForgeConfigSpec.DoubleValue slopeFlat;
        public final ForgeConfigSpec.DoubleValue slopeGentle;
        public final ForgeConfigSpec.DoubleValue slopeModerate;
        public final ForgeConfigSpec.DoubleValue slopeSteep;
        public final ForgeConfigSpec.BooleanValue enableSlopeMaterials;
        public final ForgeConfigSpec.BooleanValue enableBiomeMapping;

        CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("world");

            minY = builder.comment("Minimum world height (min_y, world bottom)").defineInRange("minY", -64, -512, 0);
            maxY = builder.comment("Maximum terrain generation height").defineInRange("maxY", 256, 128, 1024);
            baseHeight = builder.comment("Base terrain height").defineInRange("baseHeight", 128, 32, 512);
            seaLevel = builder.comment("Sea level (water surface)").defineInRange("seaLevel", 63, 0, 128);
            oceanDepthMax = builder.comment("Ocean maximum depth (blocks below sea level)").defineInRange("oceanDepthMax", 32, 8, 64);

            builder.pop();

            builder.push("terrain");

            ridgeWeight = builder.comment("Ridge noise weight (sharp mountain peaks)").defineInRange("ridgeWeight", 0.42, 0.0, 0.8);
            cellWeight = builder.comment("Cell noise weight (networked ridge lines)").defineInRange("cellWeight", 0.28, 0.0, 0.6);
            gullyWeight = builder.comment("Gully erosion weight (slope grooves)").defineInRange("gullyWeight", 0.04, 0.0, 0.2);

            builder.pop();

            builder.push("erosion");

            enableErosion = builder.comment("Enable particle erosion simulation").define("enableErosion", true);
            erosionStrength = builder.comment("Erosion strength (0.0=off, 1.0=normal, 2.0=extreme)").defineInRange("erosionStrength", 0.5, 0.0, 3.0);
            erosionDropsMul = builder.comment("Erosion drops multiplier (more drops = more erosion detail)").defineInRange("erosionDropsMul", 1.0, 0.0, 3.0);
            erosionErodeMul = builder.comment("Erosion erode speed multiplier (higher = digs deeper)").defineInRange("erosionErodeMul", 1.0, 0.0, 3.0);
            erosionDepositMul = builder.comment("Erosion deposit speed multiplier (higher = fills faster)").defineInRange("erosionDepositMul", 1.0, 0.0, 3.0);
            erosionBrushMul = builder.comment("Erosion brush radius multiplier (larger = wider valleys)").defineInRange("erosionBrushMul", 1.0, 0.0, 3.0);

            enableRivers = builder.comment("Enable river path tracing").define("enableRivers", true);
            riverDepth = builder.comment("River carving depth multiplier").defineInRange("riverDepth", 0.3, 0.0, 1.0);
            riverWidth = builder.comment("River width multiplier").defineInRange("riverWidth", 1.0, 0.3, 3.0);
            precipThreshold = builder.comment("Precipitation threshold for river sources").defineInRange("precipThreshold", 0.55, 0.3, 0.8);

            builder.pop();

            builder.push("climate");

            temperatureScale = builder.comment("Temperature noise scale").defineInRange("temperatureScale", 1.0, 0.1, 5.0);
            moistureScale = builder.comment("Moisture noise scale").defineInRange("moistureScale", 1.0, 0.1, 5.0);
            continentScale = builder.comment("Continentalness noise scale").defineInRange("continentScale", 1.0, 0.1, 5.0);

            builder.pop();

            builder.push("materials");

            enableSlopeMaterials = builder.comment("Enable slope-aware surface materials").define("enableSlopeMaterials", true);
            slopeFlat = builder.comment("Flat terrain threshold (grass → dirt)").defineInRange("slopeFlat", 0.04, 0.01, 0.15);
            slopeGentle = builder.comment("Gentle slope threshold (dirt → coarse dirt)").defineInRange("slopeGentle", 0.12, 0.03, 0.25);
            slopeModerate = builder.comment("Moderate slope threshold (coarse dirt → stone)").defineInRange("slopeModerate", 0.25, 0.10, 0.40);
            slopeSteep = builder.comment("Steep slope threshold (stone → bare rock)").defineInRange("slopeSteep", 0.45, 0.20, 0.60);

            builder.pop();

            builder.push("biome");

            enableBiomeMapping = builder.comment("Enable climate-driven biome mapping").define("enableBiomeMapping", true);

            builder.pop();
        }
    }
}
