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
 * 采样设置分页：纹理超采样倍率选择（1×/2×/3×/4×）。
 * <p>
 * 渲染分辨率 = 预览窗口显示分辨率 × 倍率（显示器模型：屏幕按自身分辨率渲染，GPU 负责 DPI 上采样）。
 * 1× = 最快（纹理=窗口逻辑像素）；2/3/4× = 更锐利但更慢。缩放（1:1…1:16）不影响采样总数。
 */
public class SamplingTab extends GridLayoutTab {

    private static final Component HEAD = Component.translatable("geogenesis.settings.sampling.head");
    private static final int[] SCALE_OPTIONS = {1, 2, 3, 4};
    private static final Component[] SCALE_LABELS = {
        Component.translatable("geogenesis.settings.sampling.scale_1"),
        Component.translatable("geogenesis.settings.sampling.scale_2"),
        Component.translatable("geogenesis.settings.sampling.scale_3"),
        Component.translatable("geogenesis.settings.sampling.scale_4")
    };
    private static final Component[] SCALE_TOOLTIPS = {
        Component.translatable("geogenesis.settings.sampling.scale_1.tooltip"),
        Component.translatable("geogenesis.settings.sampling.scale_2.tooltip"),
        Component.translatable("geogenesis.settings.sampling.scale_3.tooltip"),
        Component.translatable("geogenesis.settings.sampling.scale_4.tooltip")
    };

    public SamplingTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.sampling.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(6).createRowHelper(2);

        row.addChild(new CenteredLabel(mc.font, 320, 20, HEAD), 2);

        for (int i = 0; i < SCALE_OPTIONS.length; i++) {
            final int scale = SCALE_OPTIONS[i];
            Button btn = Button.builder(SCALE_LABELS[i], b -> {
                // 设置纹理超采样倍率并重建纹理 + 队列
                preview.setRenderScale(scale);
            })
                .tooltip(Tooltip.create(SCALE_TOOLTIPS[i]))
                .width(150)
                .build();
            // 高亮当前选中
            if (scale == preview.renderScale) {
                btn.active = false;
            }
            row.addChild(btn);
        }
    }
}
