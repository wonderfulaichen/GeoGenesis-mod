package com.geogenesis;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.generator.GeoGenesisBiomeSource;
import com.geogenesis.worldgen.generator.GeoGenesisGenerator;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@Mod(GeoGenesisMod.MODID)
public class GeoGenesisMod {
    public static final String MODID = "geogenesis";
    private static final Logger LOGGER = LogManager.getLogger(MODID);

    // Register ChunkGenerator and BiomeSource CODECs via DeferredRegister (Forge standard).
    @SuppressWarnings("unchecked")
    private static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CHUNK_GENERATOR, MODID);
    @SuppressWarnings("unchecked")
    private static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BIOME_SOURCE, MODID);

    public static final RegistryObject<Codec<? extends ChunkGenerator>> GENERATOR_CODEC =
            GENERATORS.register("generator", () -> GeoGenesisGenerator.CODEC);
    public static final RegistryObject<Codec<? extends BiomeSource>> BIOME_SOURCE_CODEC =
            BIOME_SOURCES.register("biomesource", () -> GeoGenesisBiomeSource.CODEC);

    public GeoGenesisMod() {
        LOGGER.info("GeoGenesis initializing...");

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GeoGenesisConfig.SPEC);

        // 加载气候阈值配置
        com.geogenesis.worldgen.climate.Climate.loadFromConfig();
        com.geogenesis.worldgen.climate.ClimateZone.loadFromConfig();

        var bus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);

        LOGGER.info("GeoGenesis codecs registered (generator={}, biomesource={})",
                GeoGenesisGenerator.CODEC_ID, GeoGenesisBiomeSource.CODEC_ID);
    }

    @SubscribeEvent
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        ResourceLocation genKey = ResourceLocation.tryParse("geogenesis:generator");
        ResourceLocation bsKey = ResourceLocation.tryParse("geogenesis:biomesource");
        LOGGER.info("[DIAG] CHUNK_GENERATOR contains geogenesis:generator = {}",
                BuiltInRegistries.CHUNK_GENERATOR.containsKey(genKey));
        LOGGER.info("[DIAG] BIOME_SOURCE contains geogenesis:biomesource = {}",
                BuiltInRegistries.BIOME_SOURCE.containsKey(bsKey));
        LOGGER.info("[DIAG] CHUNK_GENERATOR entries = {}", BuiltInRegistries.CHUNK_GENERATOR.keySet());
    }
}
