package com.geogenesis.client;

import com.geogenesis.GeoGenesisMod;
import com.geogenesis.client.GeoGenesisConfigScreen;
import com.geogenesis.client.preview.GeoGenesisColorReloadListener;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterPresetEditorsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端初始化：仅负责 MOD 总线事件（IModBusEvent）。
 * - FMLClientSetupEvent 注册 Mods→Config 入口。
 * - RegisterPresetEditorsEvent 为 geogenesis 世界预设注册"自定义"编辑器，
 *   原生"自定义"按钮会因此自动启用并打开 GeoGenesisConfigScreen。
 * - FORGE 总线事件（热键）见 GeoGenesisForgeEvents。
 * 仅 Dist.CLIENT 加载（专用服务器不会加载此类）。
 */
@Mod.EventBusSubscriber(modid = GeoGenesisMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GeoGenesisClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Mods → Config 按钮
        FMLJavaModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (Screen parent) -> new GeoGenesisConfigScreen(parent)));

        decodeWorldPresetDiag();
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Forge 推荐方式：在资源加载阶段就注册 reload listener
        event.registerReloadListener(new GeoGenesisColorReloadListener());
    }

    private static void decodeWorldPresetDiag() {
        Logger log = LogManager.getLogger(GeoGenesisMod.MODID);
        try {
            ResourceLocation genKey = ResourceLocation.tryParse("geogenesis:generator");
            ResourceLocation bsKey = ResourceLocation.tryParse("geogenesis:biomesource");
            log.info("[DIAG] CHUNK_GENERATOR contains geogenesis:generator = {}",
                    BuiltInRegistries.CHUNK_GENERATOR.containsKey(genKey));
            log.info("[DIAG] BIOME_SOURCE contains geogenesis:biomesource = {}",
                    BuiltInRegistries.BIOME_SOURCE.containsKey(bsKey));
            // 必须用 RegistryOps（持有注册表）才能解析派发 codec；裸 JsonOps 无法访问注册表。
            String genJson = "{\"type\":\"geogenesis:generator\",\"biome_source\":{\"type\":\"geogenesis:biomesource\"}}";
            var json = JsonParser.parseString(genJson);
            var ops = RegistryOps.create(JsonOps.INSTANCE,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            DataResult<net.minecraft.world.level.chunk.ChunkGenerator> result =
                net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, json);
            if (result.error().isPresent()) {
                log.error("[DIAG] Generator decode FAILED: {}", result.error().get().message());
            } else {
                log.info("[DIAG] Generator decode SUCCESS -> {}", result.result().orElse(null));
            }
        } catch (Exception e) {
            log.error("[DIAG] Generator decode exception", e);
        }
    }

    /**
     * 为 geogenesis 世界预设注册编辑器（MOD 总线，IModBusEvent）。
     * 注册后 Create World 界面选中 geogenesis 时，"自定义"按钮自动激活，
     * 点击即打开 GeoGenesisConfigScreen；无需任何 hack 强制按钮状态。
     */
    @SubscribeEvent
    public static void onRegisterPresetEditors(RegisterPresetEditorsEvent event) {
        ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> key =
                ResourceKey.create(Registries.WORLD_PRESET, new ResourceLocation(GeoGenesisMod.MODID + ":geogenesis"));
        event.register(key, (CreateWorldScreen screen, WorldCreationContext ctx) ->
                new GeoGenesisConfigScreen(screen, (long)(Math.random() * Long.MAX_VALUE)));
    }

    private GeoGenesisClient() {}
}
