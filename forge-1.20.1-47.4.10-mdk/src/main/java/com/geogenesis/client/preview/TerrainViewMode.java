package com.geogenesis.client.preview;

/**
 * <b>地形视图模式</b> —— ★ 2026-11-09 统一重构。
 *
 * <h2>设计来源</h2>
 * <p>参考 FreeTerraForged 的 {@code Preview2D} / {@code Preview3D}：
 * 两个视图<b>不是两个 Widget</b>，而是同一份数据上的两个 rasterizer。
 * 切换模式 = 换 rasterizer，<b>不重采、不重建数据</b>。</p>
 *
 * <h2>与旧实现的关系</h2>
 * <p>旧代码用 {@code PreviewDisplay.sandboxMode : boolean} + 散落各处的
 * {@code if (sandboxMode)} 分支来分派（2254 行 God Widget 的根源）。
 * 现在模式只存在于 {@code PreviewController.rasterizer} 这<b>一个字段</b>上。</p>
 *
 * <p>⚠ 该枚举本身零 MC 依赖，探针可引用。</p>
 */
public enum TerrainViewMode {

    /**
     * 俯视正射（平面地图）。
     *
     * <p>「预览 = 游戏」的唯一保证：≤1:16 时走完整管线（含侵蚀），
     * 与玩家进世界看到的方块逐位一致。</p>
     */
    PLANE("平面", "俯视地图 · 精确到方块"),

    /**
     * 斜视角方柱（三维沙盘）。
     *
     * <p>按 yardsite 的等轴测方柱呈现，1 柱 = 16 格方块。
     * 用于看<b>形态与格局</b>，不承诺与游戏逐块一致。</p>
     */
    SANDBOX("沙盘", "斜视方柱 · 看地形形态");

    private final String label;
    private final String hint;

    TerrainViewMode(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 按钮/页签上显示的名字。 */
    public String label() { return label; }

    /** 一行说明（悬停提示用）。 */
    public String hint() { return hint; }

    /** 是否承诺「预览 = 游戏」。 */
    public boolean exact() { return this == PLANE; }

    /** 切到下一个模式（按钮点击循环）。 */
    public TerrainViewMode next() {
        TerrainViewMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
