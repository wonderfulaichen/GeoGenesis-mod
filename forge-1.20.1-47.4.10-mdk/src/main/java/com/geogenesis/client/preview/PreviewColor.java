package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;

/**
 * 着色外观（MC 侧）：委托零依赖 {@link GeoPalette} 输出各图层颜色，
 * 统一转成 NativeImage 的 ABGR(0xAABBGGRR)。
 */
public final class PreviewColor {

    private PreviewColor() {}

    /** 按图层枚举输出 ABGR。 */
    public static int color(GeoPalette.PreviewLayer layer, Cell c, int worldX, int worldZ,
                            int minY, int maxY, boolean hydrology) {
        int rgb = GeoPalette.color(layer, c, worldX, worldZ, minY, maxY, hydrology);
        return GeoPalette.toABGR(rgb);
    }

    /** 按图层 index 输出 ABGR（0..10）。 */
    public static int color(int layerIndex, Cell c, int worldX, int worldZ,
                            int minY, int maxY, boolean hydrology) {
        return color(GeoPalette.PreviewLayer.values()[layerIndex], c, worldX, worldZ, minY, maxY, hydrology);
    }
}
