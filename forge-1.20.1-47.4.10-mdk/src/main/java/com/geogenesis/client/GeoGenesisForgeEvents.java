package com.geogenesis.client;

import com.geogenesis.GeoGenesisMod;
import com.geogenesis.client.GeoGenesisConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * FORGE 总线事件：游戏内快捷键。
 *
 * <p>Create World 界面的"自定义"按钮不再由本类 hack 处理——
 * 改为在 GeoGenesisClient 中通过 RegisterPresetEditorsEvent 为
 * geogenesis 世界预设注册原生编辑器，按钮会自动启用并打开配置屏。
 */
@Mod.EventBusSubscriber(modid = GeoGenesisMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeoGenesisForgeEvents {

    /** 按 G 键在游戏内外打开地形配置/预览屏。 */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_G) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new GeoGenesisConfigScreen(mc.screen));
            }
        }
    }

    private GeoGenesisForgeEvents() {}
}
