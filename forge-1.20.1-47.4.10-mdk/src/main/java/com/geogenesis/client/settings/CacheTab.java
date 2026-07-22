package com.geogenesis.client.settings;

import com.geogenesis.client.preview.PreviewDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;

/**
 * 缓存设置分页：缓存开关、清除缓存按钮。
 */
public class CacheTab extends GridLayoutTab {

    public CacheTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.cache.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(8).createRowHelper(1);

        row.addChild(new CenteredLabel(mc.font, 320, 20,
            Component.translatable("geogenesis.settings.cache.desc")));

        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.cache.enable"),
            b -> {
                preview.cacheEnabled = !preview.cacheEnabled;
                if (!preview.cacheEnabled) {
                    preview.cellCache.invalidateAll();
                }
            }
        ).width(320).build());

        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.cache.clear"),
            b -> {
                preview.cellCache.invalidateAll();
                preview.forceRefresh();
            }
        ).tooltip(Tooltip.create(
            Component.translatable("geogenesis.settings.cache.clear.tooltip")
        )).width(320).build());
    }
}
