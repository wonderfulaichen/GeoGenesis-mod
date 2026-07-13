package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;

/**
 * 着色外观（MC 侧）：委托零依赖 {@link GeoPalette} 输出各图层颜色，
 * 统一转成 NativeImage 的 ABGR(0xAABBGGRR)。保留 heightmap/landWater 作为兼容入口。
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

    // —— 兼容入口（仍被旧调用点使用） ——

    public static int heightmap(Cell c, int seaLevel, int snowLine, int maxY, int minY) {
        int y = (int) Math.round(c.height);
        if (y < seaLevel) {
            double d = Math.min(1.0, (double) (seaLevel - y) / Math.max(1, seaLevel - minY));
            int b = 190 - (int) (d * 130);
            int g = 100 - (int) (d * 70);
            return pack(8, Math.max(0, g), Math.max(40, b));
        }
        if (y >= snowLine) return pack(235, 240, 245);
        double t = (double) (y - seaLevel) / Math.max(1, snowLine - seaLevel);
        if (t < 0.15) {
            int r = 60 + (int) (t * 500);
            int g = 140 + (int) (t * 260);
            return pack(Math.min(255, r), Math.min(255, g), 60);
        }
        double tt = (t - 0.15) / 0.85;
        int r = 110 + (int) (tt * 90);
        int g = 120 - (int) (tt * 70);
        int b = 70 - (int) (tt * 45);
        return pack(Math.min(255, r), Math.max(0, g), Math.max(0, b));
    }

    public static int landWater(Cell c, int seaLevel, int snowLine, int maxY, int minY) {
        int base = heightmap(c, seaLevel, snowLine, maxY, minY);
        if (c.lakeMask) return blend(base, pack(0, 180, 220), 0.6);
        if (c.riverMask) return blend(base, pack(30, 100, 220), 0.7);
        if (c.riverWetness > 0.01) {
            return blend(base, pack(60, 130, 230), c.riverWetness * 0.4); // 平滑河湖蓝边
        }
        if (c.riverDistance < 0.1) {
            double s = 1.0 - c.riverDistance / 0.1;
            return blend(base, pack(100, 160, 230), s * 0.3);
        }
        return base;
    }

    private static int pack(int r, int g, int b) {
        return (0xFF << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
    }

    private static int blend(int base, int overlay, double s) {
        int br = base & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = (base >> 16) & 0xFF;
        int or = overlay & 0xFF;
        int og = (overlay >> 8) & 0xFF;
        int ob = (overlay >> 16) & 0xFF;
        int r = (int) (br * (1 - s) + or * s);
        int g = (int) (bg * (1 - s) + og * s);
        int b = (int) (bb * (1 - s) + ob * s);
        return pack(r, g, b);
    }
}
