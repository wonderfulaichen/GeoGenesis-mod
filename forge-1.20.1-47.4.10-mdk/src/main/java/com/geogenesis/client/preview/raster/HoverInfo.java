package com.geogenesis.client.preview.raster;

/**
 * 悬停信息（★ 2026-11-09）。
 *
 * <p>两个视图<b>共用同一套图例结构</b>（照 FreeTerraForged 的 4 行：
 * Area / Terrain / Biome / Spawn）。视图差异只在第二行的「值」——
 * 平面给方块坐标，沙盘给柱坐标 + 柱高。</p>
 *
 * <p>零 MC 依赖。</p>
 *
 * @param worldX   世界 X（方块）
 * @param worldZ   世界 Z（方块）
 * @param terrain 地形名（如 {@code plains} / {@code river}）
 * @param detail  图层相关的详细值（如 {@code Y=84} / {@code 湿度 0.62}）
 * @param surfaceY 该格地表 Y（方块）
 */
public record HoverInfo(int worldX, int worldZ, String terrain, String detail, double surfaceY) {

    /** 图例第 3 行显示用（「x:z」）。 */
    public String coords() {
        return worldX + ":" + worldZ;
    }

    /** 完整一行描述（左下角浮窗用）。 */
    public String line() {
        return coords() + "  " + terrain + "  " + detail;
    }
}
