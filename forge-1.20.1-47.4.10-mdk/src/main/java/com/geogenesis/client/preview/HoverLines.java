package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.climate.Latitude;
import com.geogenesis.worldgen.terrain.Cell;
import net.minecraft.client.resources.language.I18n;

/**
 * <b>悬停信息文案 —— 平面与沙盘的唯一来源</b>（★ v68）。
 *
 * <h2>为什么需要它（用户 2026-11-11）</h2>
 * <blockquote>
 *   「我只是想看看有什么还可以统一的内容，这样就减少重复造轮子
 *    也避免重复计算采样等内容。」
 * </blockquote>
 *
 * <p>同一格子在两个视图里读到的信息原来不一样：</p>
 * <ul>
 *   <li>平面 tooltip（{@code PlanePainter.drawHoverInfo}）：六行
 *       —— 坐标/高度、群系+地形、温湿大陆性、纬度+起伏、水体、操作提示；</li>
 *   <li>沙盘常驻浮层（{@code SandboxHud.drawHover}）：<b>两行</b>
 *       —— 只有方块坐标与高度。</li>
 * </ul>
 * <p>那不是"排布不同"，是<b>信息量不同</b> —— 沙盘里根本看不到群系与气候。</p>
 *
 * <h2>本类的边界</h2>
 * <p>只负责"给定坐标与 Cell，该显示哪几行"。<b>不画</b>、不管排布、
 * 不管跟随光标还是常驻 —— 那些是各视图自己的事（平面跟随光标、沙盘常驻左下）。
 * 于是两个视图共用同一份文案实现，排版各自保留。</p>
 */
public final class HoverLines {

    private HoverLines() { }

    /**
     * 生成悬停信息的所有行（顺序即显示顺序）。
     *
     * @param worldX/Z 世界坐标
     * @param heightY  该格地表/顶面高度（Y，方块）
     * @param c        该格的 Cell；null 时退化为"只有坐标"的版本
     * @return 至少一行、永不为 null
     */
    public static String[] forWorld(int worldX, int worldZ, double heightY, Cell c) {
        if (c == null) {
            return new String[]{coordLine(worldX, worldZ, heightY)};
        }
        return new String[]{
                coordLine(worldX, worldZ, heightY),
                biomeLine(c) + "  " + typeLine(c),
                String.format(java.util.Locale.ROOT,
                        "temp=%.2f  hum=%.2f  cont=%.2f",
                        c.temperature, c.humidity, c.continentNoise),
                String.format(java.util.Locale.ROOT,
                        "lat=%.2f  relief=%.2f",
                        Latitude.latitude01(worldZ), (c.shape + 1) * 0.5),
                c.lakeMask ? I18n.get("geogenesis.preview.lake") : "—"
        };
    }

    /** 最后一行（操作提示）—— 两种视图触发方式相同，用同一文案。 */
    public static String hint(boolean discreteLayer) {
        return discreteLayer
                ? "左键选中色块 · 右键复制坐标"
                : "右键复制坐标";
    }

    private static String coordLine(int worldX, int worldZ, double heightY) {
        return String.format(java.util.Locale.ROOT, "X=%d  Z=%d  Y=%d",
                worldX, worldZ, (int) Math.round(heightY));
    }

    /** 群系名（有本地化用本地化，否则用枚举名）。 */
    public static String biomeLine(Cell c) {
        BiomeClassifier.BiomeClass biome = BiomeClassifier.classify(c);
        if (biome == null) return "—";
        String key = "geogenesis.biome." + biome.name();
        String localized = I18n.get(key);
        return localized.equals(key) ? biome.name() : localized;
    }

    /** 地形类型名。 */
    public static String typeLine(Cell c) {
        return switch (c.terrainType) {
            case OCEAN -> I18n.get("geogenesis.preview.type.ocean");
            case DEEP_OCEAN -> I18n.get("geogenesis.preview.type.deep_ocean");
            case CONTINENTAL_SHELF -> I18n.get("geogenesis.preview.type.continental_shelf");
            case SUBMARINE_RIDGE -> I18n.get("geogenesis.preview.type.submarine_ridge");
            case SEAMOUNT -> I18n.get("geogenesis.preview.type.seamount");
            case BEACH -> I18n.get("geogenesis.preview.type.beach");
            case PLAIN -> I18n.get("geogenesis.preview.type.plain");
            case HILLS -> I18n.get("geogenesis.preview.type.hills");
            case PLATEAU -> I18n.get("geogenesis.preview.type.plateau");
            case MOUNTAINS -> I18n.get("geogenesis.preview.type.mountains");
            case PEAK -> I18n.get("geogenesis.preview.type.peak");
            case BASIN -> I18n.get("geogenesis.preview.type.basin");
            case RIVER -> I18n.get("geogenesis.preview.type.river");
            case LAKE -> I18n.get("geogenesis.preview.type.lake");
            case SNOW -> I18n.get("geogenesis.preview.type.snow");
            default -> I18n.get("geogenesis.preview.type.land");
        };
    }
}