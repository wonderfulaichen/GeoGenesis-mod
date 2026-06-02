package com.geogenesis;

import com.geogenesis.client.GeoGenesisConfigScreen;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.GeoGenesisGenerator;
import com.geogenesis.worldgen.biome.GeoGenesisBiomeSource;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterPresetEditorsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

@Mod("geogenesis")
public class GeoGenesisMod {
    public static final String MODID = "geogenesis";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceKey<WorldPreset> WORLD_PRESET_KEY =
            ResourceKey.create(Registries.WORLD_PRESET, new ResourceLocation(MODID, "normal"));

    public GeoGenesisMod() {
        LOGGER.info("GeoGenesis - 地形生成模组初始化");

        GeoGenesisConfig.register();

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);

        if (FMLLoader.getDist().isClient()) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onRegisterPresetEditors);
        }

        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
    }

    @SuppressWarnings("deprecation")
    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("GeoGenesis - 注册世界生成器Codec");
        event.enqueueWork(() -> {
            Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                    new ResourceLocation(MODID, "geogenesis"),
                    GeoGenesisGenerator.CODEC);
            Registry.register(BuiltInRegistries.BIOME_SOURCE,
                    new ResourceLocation(MODID, "geogenesis"),
                    GeoGenesisBiomeSource.CODEC);
            LOGGER.info("GeoGenesis - 世界生成器和生物群系源注册完成");
        });
    }

    private void onRegisterPresetEditors(RegisterPresetEditorsEvent event) {
        LOGGER.info("GeoGenesis - 注册世界预设编辑器");
        event.register(WORLD_PRESET_KEY, GeoGenesisConfigScreen::new);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        LOGGER.info("GeoGenesis - 世界种子: {}", seed);
    }
}
