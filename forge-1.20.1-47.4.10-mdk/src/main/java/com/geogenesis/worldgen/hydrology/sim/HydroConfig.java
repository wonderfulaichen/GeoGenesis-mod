package com.geogenesis.worldgen.hydrology.sim;

/**
 * 水文核心的配置快照（不可变）。参与缓存键 {@link #hash()}。
 *
 * <p>刻意与 Forge 配置解耦：核心只认这份不可变快照，生产接线负责把 TOML 值映射进来。
 * 这样探针可以用同一份配置直接复现生产行为，不需要解析 {@code config/*.toml}。</p>
 *
 * <h2>★ 成河阈值与宽度参考必须解耦</h2>
 * <p>两者曾经共用 {@code channelThreshold}，导致"调河网密度"会连带缩放<b>全部河宽</b>：
 * 阈值 ×7 ⇒ 半宽 ×(1/7^0.42) ≈ ×0.44。旧链 {@code RiverLineParams.riverAccumThreshold}
 * 与 {@code widthAreaRef} 分离正是为此（其注释记录了同一次教训）。
 * 故此处独立出 {@link #widthAreaRef}：改河网密度不再动宽度标定。</p>
 *
 * @param seaLevelY        海平面（方块 Y）
 * @param cellBlocks       水文格边长（方块）
 * @param coreCells        tile 核格数
 * @param haloCells        halo 格数
 * @param channelThreshold 成河阈值（输运量，block³/单位时间）；越小河越密
 * @param widthAreaRef     宽度标定参考量：{@code Q = widthAreaRef} 处半宽 = {@code W0}
 * @param decayGain        全局衰减倍率（0 = 关闭，湿润；1 = 按 sampler.decay 全额）
 * @param maxRings         端口递归的上游环数上限；超过则标 BOUNDARY_PENDING
 * @param levelEps         同水面判定容差（方块）
 */
public record HydroConfig(double seaLevelY, int cellBlocks, int coreCells, int haloCells,
                          double channelThreshold, double widthAreaRef, double decayGain,
                          int maxRings, double levelEps) {

    /**
     * 生产默认（2026-09-30 二轮标定；上一版 852 经用户实机否决）。
     *
     * <p><b>成河阈值 = 120</b>。五档 A/B（全格口径，`core_view_scan.png` 五联图，
     * seed 9139912035078620160 @(-140,137) r=400）：</p>
     * <pre>
     *   30 → 19.11%  平原/海岸大面积青色污染（低流量饱和成"面"）
     *   60 → 12.16%  污染仍在
     *  120 →  7.99%  ★ 山区树枝状密网 + 支流到湖 + 平原干净
     *  300 →  5.63%  细支流开始丢失
     *  852 →  2.84%  ★ 用户实机否决："河网完全不成河道的样子"
     *                 （且小溪入湖段不达标 ⇒ "河到湖差一点"）
     * </pre>
     *
     * <p><b>⚠ 一轮标定的教训（勿回退扫描口径）</b>：首轮扫描曾给统计加
     * {@code isFlowing()} 过滤 —— 而 isFlowing 的前提就是"已过当前阈值成河"
     * ⇒ 扫描恒等于当前河道占比、对阈值**假性不敏感**（30~852 档全显示 ~2.9%），
     * 占 95%+ 的 NONE 格真实流量从未参与。全格口径才还原真实的阈值效应。</p>
     *
     * <p><b>宽度参考 = 120</b>：= 阈值时半宽 = W0（河头约 3 块宽）。</p>
     */
    public static HydroConfig defaults() {
        return new HydroConfig(HydroContract.seaLevelY, HydroContract.CELL_BLOCKS,
                HydroContract.TILE_CORE_CELLS, HydroContract.TILE_HALO_CELLS,
                3000.0, 3000.0, 1.0, HydroContract.DEFAULT_MAX_RINGS, HydroContract.LEVEL_EPS);
    }

    public HydroConfig withSeaLevel(double y) {
        return new HydroConfig(y, cellBlocks, coreCells, haloCells, channelThreshold,
                widthAreaRef, decayGain, maxRings, levelEps);
    }

    public HydroConfig withChannelThreshold(double t) {
        return new HydroConfig(seaLevelY, cellBlocks, coreCells, haloCells, t,
                widthAreaRef, decayGain, maxRings, levelEps);
    }

    public HydroConfig withWidthAreaRef(double ref) {
        return new HydroConfig(seaLevelY, cellBlocks, coreCells, haloCells, channelThreshold,
                ref, decayGain, maxRings, levelEps);
    }

    public HydroConfig withDecayGain(double g) {
        return new HydroConfig(seaLevelY, cellBlocks, coreCells, haloCells, channelThreshold,
                widthAreaRef, g, maxRings, levelEps);
    }

    public HydroConfig withMaxRings(int r) {
        return new HydroConfig(seaLevelY, cellBlocks, coreCells, haloCells, channelThreshold,
                widthAreaRef, decayGain, r, levelEps);
    }

    /** 窗口边长（格）。 */
    public int windowCells() {
        return coreCells + 2 * haloCells;
    }

    /** 单格面积（block²）。 */
    public double cellArea() {
        return (double) cellBlocks * cellBlocks;
    }

    /** 缓存键用的稳定哈希。 */
    public int hash() {
        int h = 17;
        h = h * 31 + Double.hashCode(seaLevelY);
        h = h * 31 + cellBlocks;
        h = h * 31 + coreCells;
        h = h * 31 + haloCells;
        h = h * 31 + Double.hashCode(channelThreshold);
        h = h * 31 + Double.hashCode(widthAreaRef);
        h = h * 31 + Double.hashCode(decayGain);
        h = h * 31 + maxRings;
        h = h * 31 + Double.hashCode(levelEps);
        return h;
    }
}
