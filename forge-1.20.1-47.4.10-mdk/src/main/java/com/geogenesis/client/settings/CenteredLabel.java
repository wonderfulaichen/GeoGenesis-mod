package com.geogenesis.client.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 居中文本标签，用于设置屏各分页的标题和说明行。
 */
public class CenteredLabel extends AbstractWidget {

    private final Font font;
    private final int color;

    public CenteredLabel(Font font, int width, int height, Component message) {
        this(font, width, height, message, 0xFFCCCCCC);
    }

    public CenteredLabel(Font font, int width, int height, Component message, int color) {
        super(0, 0, width, height, message);
        this.font = font;
        this.color = color;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int x = getX(), y = getY(), w = getWidth();
        Component msg = getMessage();
        if (msg.getString().isEmpty()) return;
        int textW = font.width(msg);
        g.drawString(font, msg, x + (w - textW) / 2, y + (height - 9) / 2, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
    }
}
