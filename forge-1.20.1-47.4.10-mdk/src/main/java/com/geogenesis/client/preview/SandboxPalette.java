package com.geogenesis.client.preview;

/**
 * <b>沙盘上色</b>—— 复用既有 {@link GeoPalette}，只补沙盘独有的两件事。
 *
 * <h2>★ 为什么不再是"自己一套调色板"（2026-11-08 修正）</h2>
 * <p>上一版新建了 10 项的 {@code SandboxPalette} 色表 —— 这是<b>重复造轮子</b>：
 * 我们本来就有 <b>35 项群系本色</b>（{@link GeoPalette#colorForBiome}
 * 背后的 {@code T_BIOME}，与 {@code BiomeClassifier.BiomeClass} 逐项对齐、
 * 直接绑定真实 MC 群系）。实测两者同语义色差极小：</p>
 * <table border="1">
 *   <caption>T_BIOME vs 参考项目 BiomePalette</caption>
 *   <tr><th>群系</th><th>我们 T_BIOME</th><th>参考项目</th><th>最大分量差</th></tr>
 *   <tr><td>PLAINS</td><td>#7CB342</td><td>#6FAE4A</td><td>13</td></tr>
 *   <tr><td>FOREST</td><td>#3F7A2E</td><td>#3D6B32</td><td>15</td></tr>
 *   <tr><td>DESERT</td><td>#D9C072</td><td>#D9C08A</td><td>24</td></tr>
 *   <tr><td>JUNGLE</td><td>#1E7A1E</td><td>#2E7A35</td><td>23</td></tr>
 * </table>
 * <p>⇒ 保留这 35 项（它是"我们的优势"），本类只负责沙盘需要的补充：</p>
 * <ol>
 *   <li><b>水面</b>：按深度渐变（浅滩亮蓝 → 深海墨蓝）——
 *       参考项目 {@code BiomePalette.colorFor()} 第 91-95 行同策；</li>
 *   <li><b>jitter</b>：同色群系边界可读化（参考项目第 157-161 行同策）。
 *       我们 35 项里也有同色对（如 SNOWY_PLAINS / FROZEN_PEAKS 都接近白），
 *       相邻时必须靠微小明暗差分开。</li>
 * </ol>
 * <p>其余（坡度光照）由 {@link SandboxShade} 负责，不在这里。</p>
 */
public final class SandboxPalette {

    /** 水面浅滩色（参考项目 {@code WATER_SHALLOW}）。 */
    public static final int WATER_SHALLOW = 0x6FA8D6;
    /** 水面深水色（参考项目 {@code WATER_DEEP}）。 */
    public static final int WATER_DEEP = 0x1C3F6B;
    /** 深度渐变分母（参考项目 {@code colorFor} 第 92 行：30 格到全深）。 */
    public static final float WATER_DEPTH_SCALE = 30.0f;

    private SandboxPalette() { }

    /**
     * <b>按图层给一配上色</b>（不含坡度光照，由调用方最后乘）。
     *
     * <h2>分流规则</h2>
     * <ul>
     *   <li><b>水面</b>（任何图层）：按深度渐变（参考项目 {@code colorFor} 91-95 行）——</li>
     *   <li><b>BIOME / CLIMATE_ZONE</b>：用<b>既有的 35 项群系本色</b>
     *       （{@link GeoPalette#colorForBiome}）+ jitter（边界可读）；</li>
     *   <li><b>其余离散图层</b>（地形类型/河流类型/岩性/地层）：走 {@link GeoPalette}
     *       自己的离散色表 —— 它们的类别是 ID 不是群系；</li>
     *   <li><b>连续图层</b>（高程/温度/湿度/降水/…）：走 {@link GeoPalette} 色带，
     *       与平面窗口和图例完全一致。</li>
     * </ul>
     *
     * @param majority 该柱的多数 {@code BiomeClass.ordinal()}
     */
    public static int colorForLayer(GeoPalette.PreviewLayer layer,
                                    com.geogenesis.worldgen.terrain.Cell cell,
                                    int majority, double light, double surface, double seaLevel,
                                    boolean snow, int minY, int maxY) {
        if (cell == null) return 0x505050;

        // 水面：任何图层下都按深度渐变（可读出"能不能过"）
        if (surface <= seaLevel) {
            return waterColor(surface, seaLevel);
        }

        if (layer.kind == GeoPalette.Kind.DISCRETE) {
            if (layer == GeoPalette.PreviewLayer.BIOME
                    || layer == GeoPalette.PreviewLayer.CLIMATE_ZONE) {
                // ★ 复用既有 35 项群系本色（不是另造色表）
                //   雪线以上仍用雪白覆盖（山项观感）
                if (snow) return 0xE7F0F5;
                return jitter(landColor(majority), majority);
            }
            return GeoPalette.color(layer, cell, 0, 0, minY, maxY) & 0xFFFFFF;
        }

        // 连续图层：GeoPalette 色带（与平面窗口完全一致，图例对得上）
        return GeoPalette.color(layer, cell, 0, 0, minY, maxY, false) & 0xFFFFFF;
    }

    /**
     * <b>按图层上色（不依赖 MC 的 {@code Cell}）</b> —— ★ 2026-11-09 B1。
     *
     * <p>为什么需要这个重载：{@code SandboxGeometry} 是<b>纯函数层</b>
     * （用户要求「抽离 MC 做完全纯 Java 核心，可以在外部计算后直接复用」），
     * 不能依赖 {@code Cell}。</p>
     *
     * <p><b>分工</b>：本方法只处理<b>与 Cell 无关</b>的两类——</p>
     * <ol>
     *   <li>水面（任何图层都按深度渐变）；</li>
     *   <li>BIOME / CLIMATE_ZONE（35 项群系本色 + jitter + 雪线）。</li>
     * </ol>
     * <p>其余图层（离散 ID / 连续色带）由调用方用 {@code GeoPalette.color}
     * 算好后通过 {@code fallbackRgb} 传入 —— 避免在几何层复制一整套色带逻辑
     * （那会造成"第二套调色板"，正是之前被批评的重复实现）。</p>
     *
     * @param fallbackRgb 调用方已算好的底色（非水面/非 BIOME 图层时使用）
     * @return 最终 0xRRGGBB（不含坡度光照）
     */
    public static int colorForCellView(GeoPalette.PreviewLayer layer,
                                       int majority, double surface, double seaLevel,
                                       boolean snow, int fallbackRgb) {
        // 水面：任何图层下都按深度渐变（可读出"能不能过"）
        if (surface <= seaLevel) {
            return waterColor(surface, seaLevel);
        }
        if (layer == GeoPalette.PreviewLayer.BIOME
                || layer == GeoPalette.PreviewLayer.CLIMATE_ZONE) {
            if (snow) return 0xE7F0F5;
            return jitter(landColor(majority), majority);
        }
        return fallbackRgb & 0xFFFFFF;
    }

    /**
     * 陆地本色：<b>直接用既有的 35 项群系色</b>。
     *
     * @param biomeId {@code BiomeClass.ordinal()}
     */
    public static int landColor(int biomeId) {
        return GeoPalette.colorForBiome(biomeId);
    }

    /**
     * 水面颜色（浅滩 → 深海按深度插值，参考项目 {@code colorFor()} 第 92-95 行）。
     *
     * @param surfaceY 水面 Y
     * @param seaLevel 海平面
     */
    public static int waterColor(double surfaceY, double seaLevel) {
        float depth = (float) ((seaLevel - surfaceY) / WATER_DEPTH_SCALE);
        if (depth < 0f) depth = 0f;
        else if (depth > 1f) depth = 1f;
        return lerp(WATER_SHALLOW, WATER_DEEP, depth);
    }

    /**
     * 同色群系边界可读化：按 id 哈希 ±6% 亮度差
     * （<b>照抄</b>参考项目 {@code BiomePalette.jitter()} 第 157-161 行）。
     *
     * <p>为什么需要：我们 35 项里 SNOWY_PLAINS(#DDE6EC) / FROZEN_PEAKS(#FFFFFF) /
     * SNOWY_SLOPES(#C9D6DE) 三者相邻时几乎分不出边界；
     * 参考项目的 7 个 forest 系同为 #3D6B32 更是必须靠它区分。</p>
     *
     * <p>★ 上一版有这个函数但<b>从未被调用</b>（D5 缺陷），现已接入
     * {@code SandboxRenderer.collect()}。</p>
     */
    public static int jitter(int rgb, int biomeId) {
        // 参考项目用 ResourceLocation.hashCode；这里用群系 id（同一群系恒定、不同群系分散）
        int hash = biomeId * 0x9E3779B1;
        hash ^= hash >>> 16;
        float factor = 0.94f + ((hash & 0xFF) / 255.0f) * 0.12f;
        return scale(rgb, factor);
    }

    /** 亮度缩放。 */
    public static int scale(int rgb, double factor) {
        int r = clamp255((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp255((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp255((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /** 线性插值两色。 */
    public static int lerp(int from, int to, float t) {
        int r = (int) ((1 - t) * ((from >> 16) & 0xFF) + t * ((to >> 16) & 0xFF));
        int g = (int) ((1 - t) * ((from >> 8) & 0xFF) + t * ((to >> 8) & 0xFF));
        int b = (int) ((1 - t) * (from & 0xFF) + t * (to & 0xFF));
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
