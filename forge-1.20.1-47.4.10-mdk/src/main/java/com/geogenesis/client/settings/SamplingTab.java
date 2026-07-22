package com.geogenesis.client.settings;

import com.geogenesis.client.preview.PreviewDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 采样设置分页：blockStride 选择（4/8/16/32）。
 * <p>
 * blockStride 控制每 chunk 的采样步长。
 * 16 = 每 chunk 1 次采样（最快），4 = 每 chunk 16 次采样（最精细）。
 */
public class SamplingTab extends GridLayoutTab {

    private static final Component HEAD = Component.translatable("geogenesis.settings.sampling.head");
    private static final int[] STRIDE_OPTIONS = {4, 8, 16, 32};
    private static final Component[] STRIDE_LABELS = {
        Component.translatable("geogenesis.settings.sampling.stride_4"),
        Component.translatable("geogenesis.settings.sampling.stride_8"),
        Component.translatable("geogenesis.settings.sampling.stride_16"),
        Component.translatable("geogenesis.settings.sampling.stride_32")
    };
    private static final Component[] STRIDE_TOOLTIPS = {
        Component.translatable("geogenesis.settings.sampling.stride_4.tooltip"),
        Component.translatable("geogenesis.settings.sampling.stride_8.tooltip"),
        Component.translatable("geogenesis.settings.sampling.stride_16.tooltip"),
        Component.translatable("geogenesis.settings.sampling.stride_32.tooltip")
    };

    public SamplingTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.sampling.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(6).createRowHelper(2);

        row.addChild(new CenteredLabel(mc.font, 320, 20, HEAD), 2);

        for (int i = 0; i < STRIDE_OPTIONS.length; i++) {
            final int stride = STRIDE_OPTIONS[i];
            Button btn = Button.builder(STRIDE_LABELS[i], b -> {
                preview.blockStride = stride;
                // 重建 TerrainQueue 以使用新步长
                preview.resetBlockStride(stride);
            })
                .tooltip(Tooltip.create(STRIDE_TOOLTIPS[i]))
                .width(150)
                .build();
            // 高亮当前选中
            if (stride == preview.blockStride) {
                btn.active = false;
            }
            row.addChild(btn);
        }
    }
}
