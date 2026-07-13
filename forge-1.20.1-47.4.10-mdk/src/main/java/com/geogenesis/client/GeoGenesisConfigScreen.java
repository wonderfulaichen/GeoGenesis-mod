package com.geogenesis.client;

import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * GeoGenesis 配置屏（Stage 1 最小版 — mixer 重绑在 Stage 3）。
 * 显示标题 + 种子输入 + 预览，完整 mixer 将于 Stage 3 恢复。
 */
public class GeoGenesisConfigScreen extends Screen {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 260;

    private final Screen parent;

    public GeoGenesisConfigScreen(Screen parent) {
        super(Component.literal("GeoGenesis Configuration"));
        this.parent = parent;
    }

    public GeoGenesisConfigScreen() {
        this(null);
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int topY = this.height / 2 - HEIGHT / 2;

        // Done button
        this.addRenderableWidget(Button.builder(
            Component.literal("Done"),
            btn -> Minecraft.getInstance().setScreen(null)
        ).pos(centerX - WIDTH / 2 + WIDTH - 60, topY + HEIGHT - 30).size(50, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(gfx);
        int centerX = this.width / 2;
        int topY = this.height / 2 - HEIGHT / 2;

        // Title
        gfx.drawCenteredString(this.font, "GeoGenesis Mod",
            centerX, topY + 10, 0xFFFFFF);
        gfx.drawCenteredString(this.font, "Terrain Generator Configuration",
            centerX, topY + 28, 0xAAAAAA);

        // Status
        gfx.drawCenteredString(this.font,
            "Terrain engine: OK | Sea Level: " + GeoGenesisConfig.INSTANCE.seaLevel.get(),
            centerX, topY + 60, 0x88FF88);

        gfx.drawCenteredString(this.font,
            "Mixer panels will be restored in a future update.",
            centerX, topY + 100, 0x888888);

        super.render(gfx, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 用于 ConfigScreenHandler 注册的工厂 */
    public static Screen create(Minecraft mc, Screen parent) {
        return new GeoGenesisConfigScreen();
    }
}
