package com.geogenesis.client.settings;

import com.geogenesis.client.preview.GeoPalette;
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

        // 开关按钮：点击切换状态，按钮文案实时反映开/关
        row.addChild(makeToggle(
            Component.translatable("geogenesis.settings.general.show_frametime"),
            Component.translatable("geogenesis.settings.general.show_frametime.tooltip"),
            () -> preview.showFrameTime,
            on -> preview.showFrameTime = on));

        row.addChild(makeToggle(
            Component.translatable("geogenesis.settings.general.show_player"),
            Component.translatable("geogenesis.settings.general.show_player.tooltip"),
            () -> preview.showPlayerMarkers,
            on -> preview.showPlayerMarkers = on));

        // 气候图层地形底图模式：关闭 / 染色底图 / 地形阴影（点击循环切换）
        row.addChild(Button.builder(
            Component.literal("气候地形底图: " + GeoPalette.terrainUnderlayLabel()),
            b -> {
                GeoPalette.cycleTerrainUnderlay();
                b.setMessage(Component.literal("气候地形底图: " + GeoPalette.terrainUnderlayLabel()));
            }
        ).tooltip(Tooltip.create(
            Component.literal("气候/纬度等图层的数据披在地形之上的表现方式：关闭=纯数据；染色底图=混入高程彩色；地形阴影=仅按海拔调亮度")
        )).width(320).build());
    }

    /** 构建带开/关状态显示的开关按钮。点击时翻转 getter 当前状态并写回 setter。 */
    private static Button makeToggle(Component label, Component tooltip,
                                     java.util.function.Supplier<Boolean> getter,
                                     java.util.function.Consumer<Boolean> setter) {
        String on = net.minecraft.network.chat.Component.translatable(
            "geogenesis.settings.general.on").getString();
        String off = net.minecraft.network.chat.Component.translatable(
            "geogenesis.settings.general.off").getString();
        Button btn = Button.builder(
            Component.literal(label.getString() + "：" + (getter.get() ? on : off)),
            b -> {
                boolean next = !getter.get();
                setter.accept(next);
                b.setMessage(Component.literal(label.getString() + "：" + (next ? on : off)));
            }
        ).tooltip(Tooltip.create(tooltip)).width(320).build();
        return btn;
    }
}
