package com.geogenesis.client.settings;

import com.geogenesis.client.preview.PreviewDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;

/**
 * 通用设置分页：显示控制提示、帧时间、玩家位置。
 */
public class GeneralTab extends GridLayoutTab {

    public GeneralTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.general.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(4).createRowHelper(1);
        row.addChild(new CenteredLabel(mc.font, 320, 20,
            Component.translatable("geogenesis.settings.general.head")));

        // 三个开关按钮：点击切换状态
        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.general.show_controls"),
            b -> preview.showControlsHint = !preview.showControlsHint
        ).tooltip(Tooltip.create(
            Component.translatable("geogenesis.settings.general.show_controls.tooltip")
        )).width(320).build());

        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.general.show_frametime"),
            b -> preview.showFrameTime = !preview.showFrameTime
        ).tooltip(Tooltip.create(
            Component.translatable("geogenesis.settings.general.show_frametime.tooltip")
        )).width(320).build());

        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.general.show_player"),
            b -> preview.showPlayerMarkers = !preview.showPlayerMarkers
        ).tooltip(Tooltip.create(
            Component.translatable("geogenesis.settings.general.show_player.tooltip")
        )).width(320).build());
    }
}
