package com.geogenesis.client.preview;

/**
 * <b>沙盘调色板</b>（★ 2026-11-08，P0-1）—— yardsite {@code BiomePalette} 的本项目移植。
 *
 * <h2>为什么不用 {@code GeoPalette}</h2>
 * <p>{@code GeoPalette} 是<b>平面地图</b>的图层色带（高程连续带 / 类型离散带），
 * 为「在 2D 图上区分海拔与类型」设计。直接把它画到 3D 沙盘上，
 * 整幅图会是一片均匀的绿棕 —— 这正是「效果完全不像参考项目」的最大成因：
 * 参考项目画的是<b>群系本色</b>（雪白、沙漠黄、雨林深绿、草原亮绿），
 * 一眼读出气候格局。</p>
 *
 * <p>⇒ 沙盘用自己的色表（本类），平面模式不动（{@code GeoPalette} 保留）。
 * 颜色语义对齐 yardsite {@code BiomePalette.baseColor()} 第 109-154 行。</p>
 *
 * <h2>颜色语义（对照 yardsite）</h2>
 * <table border="1">
 *   <caption>色表</caption>
 *   <tr><th>类型</th><th>yardsite（原版群系）</th><th>本项目（Whittaker/TerrainClass）</th></tr>
 *   <tr><td>雪</td><td>0xE7F0F5（snowy_plains 等）</td><td>ICE / SNOW / PEAK(雪线以上)</td></tr>
 *   <tr><td>苔原/针叶</td><td>0x3D6B32（taiga）/ HSV 冷绿</td><td>TUNDRA / TAIGA</td></tr>
 *   <tr><td>沙漠</td><td>0xD9C08A（desert/badlands）</td><td>DESERT</td></tr>
 *   <tr><td>草原</td><td>0x6FAE4A（plains）</td><td>GRASSLAND</td></tr>
 *   <tr><td>稀树草原</td><td>0xB39B4C（savanna）</td><td>SAVANNA</td></tr>
 *   <tr><td>落叶林</td><td>0x3D6B32（forest）</td><td>TEMPERATE_FOREST / SEASONAL_FOREST</td></tr>
 *   <tr><td>雨林</td><td>0x2E7A35（jungle）</td><td>TROPICAL_RAINFOREST / TEMPERATE_RAINFOREST</td></tr>
 *   <tr><td>石山</td><td>0x8A8A85（windswept/stony）</td><td>MOUNTAINS / HILLS(裸岩)</td></tr>
 *   <tr><td>海滩</td><td>0xE2D3A0（beach）</td><td>BEACH</td></tr>
 *   <tr><td>水</td><td>浅 0x6FA8D6 → 深 0x1C3F6B，按 (seaLevel−surface)/30</td><td>同式</td></tr>
 * </table>
 *
 * <p><b>jitter</b>（yardsite 第 157-161 行）：同色类型靠 ordinal 哈希做 ±6% 亮度差，
 * 边界才读得出来。</p>
 *
 * <p><b>水面例外</b>（yardsite {@code colorFor()} 第 91-95 行）：水面按深度渐变，
 * 不走群系色 —— 浅滩亮蓝、深海墨蓝，可读出「这块水能不能过」。</p>
 */
public final class SandboxPalette {

    /** 水面浅滩色（yardsite {@code WATER_SHALLOW}）。 */
    public static final int WATER_SHALLOW = 0x6FA8D6;
    /** 水面深水色（yardsite {@code WATER_DEEP}）。 */
    public static final int WATER_DEEP = 0x1C3F6B;
    /** 无数据兜底色。 */
    public static final int UNKNOWN_RGB = 0x505050;
    /** 深度渐变分母（yardsite {@code colorFor} 第 92 行：30 格到全深）。 */
    public static final float WATER_DEPTH_SCALE = 30.0f;

    private SandboxPalette() { }

    /**
     * 一格的最终颜色（<b>画的时候现算</b>，改色不用重采样 —— yardsite 同策）。
     *
     * @param biomeType 气候群系（Whittaker），陆地上色主输入
     * @param terrain   地形类型（TerrainClass），用于 BEACH 等覆盖
     * @param surface   地表 Y（真实高度）
     * @param seaLevel  海平面
     * @param snow      是否雪线以上（山项雪盖）
     * @param light     坡度光照（{@code SandboxShade.lightFor} 输出，0.55~1.30）
     */
    public static int colorFor(com.geogenesis.worldgen.climate.WhittakerType biomeType,
                               com.geogenesis.worldgen.terrain.TerrainClass terrain,
                               double surface, double seaLevel, boolean snow, double light) {
        int rgb;
        boolean wet = surface <= seaLevel;
        if (wet) {
            // yardsite colorFor() 第 92-95 行：浅 → 深按 (seaLevel - surface)/30 插值
            float depth = (float) ((seaLevel - surface) / WATER_DEPTH_SCALE);
            if (depth < 0f) depth = 0f;
            else if (depth > 1f) depth = 1f;
            rgb = lerp(WATER_SHALLOW, WATER_DEEP, depth);
        } else {
            rgb = landColor(biomeType, terrain, snow, surface, seaLevel);
        }
        return scale(rgb, light);
    }

    /** 陆地本色（对照 yardsite {@code baseColor()} 第 109-154 行的语义）。 */
    private static int landColor(com.geogenesis.worldgen.climate.WhittakerType biome,
                                 com.geogenesis.worldgen.terrain.TerrainClass terrain,
                                 boolean snow, double surface, double seaLevel) {
        // 雪盖优先（山项雪线 / 冰原 / 雪原 —— yardsite snowy 系 0xE7F0F5）
        if (snow || biome == com.geogenesis.worldgen.climate.WhittakerType.ICE) return 0xE7F0F5;
        // 地形覆盖：海滩（yardsite beach 0xE2D3A0）
        if (terrain == com.geogenesis.worldgen.terrain.TerrainClass.BEACH) return 0xE2D3A0;
        return switch (biome) {
            case ICE, TUNDRA -> 0xB8C8C0;                       // 苔原：灰绿（介于雪与针叶间）
            case TAIGA -> 0x3D6B32;                             // 针叶林（yardsite taiga）
            case GRASSLAND -> 0x6FAE4A;                         // 草原（yardsite plains）
            case TEMPERATE_FOREST, SEASONAL_FOREST -> 0x3D6B32; // 落叶/季风林（yardsite forest）
            case TEMPERATE_RAINFOREST -> 0x2E7A35;              // 温带雨林偏深（yardsite jungle 系）
            case DESERT -> 0xD9C08A;                            // 沙漠（yardsite desert/badlands）
            case SAVANNA -> 0xB39B4C;                           // 稀树草原（yardsite savanna）
            case TROPICAL_RAINFOREST -> 0x2E7A35;               // 雨林（yardsite jungle）
        };
    }

    /**
     * 同色类型边界可读化：按 ordinal 哈希 ±6% 亮度差（yardsite {@code jitter()} 第 157-161 行）。
     * 直接在 {@link #colorFor} 里做会导致水面也抖（水面必须平），
     * 所以只对陆地调用它。
     */
    public static int jitter(int rgb, int ordinal) {
        int hash = ordinal * 0x9E3779B1;
        hash ^= hash >>> 16;
        float factor = 0.94f + ((hash & 0xFF) / 255.0f) * 0.12f;
        return scale(rgb, factor);
    }

    /** 线性插值两色（yardsite {@code lerp()}）。 */
    public static int lerp(int from, int to, float t) {
        int r = (int) java.lang.Math.fma(t, (to >> 16) & 0xFF, (1 - t) * ((from >> 16) & 0xFF));
        int g = (int) java.lang.Math.fma(t, (to >> 8) & 0xFF, (1 - t) * ((from >> 8) & 0xFF));
        int b = (int) java.lang.Math.fma(t, to & 0xFF, (1 - t) * (from & 0xFF));
        return (r << 16) | (g << 8) | b;
    }

    /** 亮度缩放（yardsite {@code scale()}）。 */
    public static int scale(int rgb, double factor) {
        int r = clamp255((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp255((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp255((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
