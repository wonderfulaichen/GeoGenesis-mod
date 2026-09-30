package com.geogenesis.worldgen.hydrology.sim;

/**
 * 一个 tile 的 window 字段（不可变）：高度 / 侧向补给 / 衰减。
 *
 * <p>window = core + 2·halo，坐标是<b>全球对齐</b>的水文格；halo 直接由 {@link HydroSampler}
 * 采样同一全球格点阵得到，<b>不是</b>邻居 tile 结果的副本。因此本对象是
 * {@code (seed, tileKey, config)} 的纯函数，与"谁先被查询"无关。</p>
 *
 * <p>索引：{@code idx = lx * w + lz}（x 主序）；{@code lx = gx - winMinX}。</p>
 */
public final class HydroTileField {

    public final HydroTileKey key;
    public final HydroConfig cfg;
    /** window 边长（格）。 */
    public final int w;
    /** window 起点（全球格，含）。 */
    public final int winMinX, winMinZ;

    public final double[] height;
    public final double[] source;
    public final double[] decay;

    private HydroTileField(HydroTileKey key, HydroConfig cfg, int w, int minX, int minZ,
                           double[] height, double[] source, double[] decay) {
        this.key = key;
        this.cfg = cfg;
        this.w = w;
        this.winMinX = minX;
        this.winMinZ = minZ;
        this.height = height;
        this.source = source;
        this.decay = decay;
    }

    /** 采样一个 tile 的 window。纯函数：只依赖 (sampler, key, cfg)。 */
    public static HydroTileField sample(HydroTileKey key, HydroConfig cfg, HydroSampler sampler) {
        int w = cfg.windowCells();
        int minX = key.winMinX();
        int minZ = key.winMinZ();
        double[] h = new double[w * w];
        double[] s = new double[w * w];
        double[] d = new double[w * w];
        double cell = cfg.cellBlocks();
        for (int lx = 0; lx < w; lx++) {
            double bx = (minX + lx + 0.5) * cell;
            for (int lz = 0; lz < w; lz++) {
                double bz = (minZ + lz + 0.5) * cell;
                int idx = lx * w + lz;
                h[idx] = sampler.height(bx, bz);
                double sv = sampler.source(bx, bz);
                s[idx] = sv > 0 ? sv : 0;
                double dv = sampler.decay(bx, bz) * cfg.decayGain();
                d[idx] = dv > 0 ? dv : 0;
            }
        }
        return new HydroTileField(key, cfg, w, minX, minZ, h, s, d);
    }

    public int idx(int lx, int lz) {
        return lx * w + lz;
    }

    public int lx(int idx) {
        return idx / w;
    }

    public int lz(int idx) {
        return idx % w;
    }

    /** 本地索引 → 全球格 X。 */
    public int globalX(int idx) {
        return winMinX + lx(idx);
    }

    /** 本地索引 → 全球格 Z。 */
    public int globalZ(int idx) {
        return winMinZ + lz(idx);
    }

    public boolean inside(int lx, int lz) {
        return lx >= 0 && lx < w && lz >= 0 && lz < w;
    }

    /** 该本地格是否位于 window 边界（无外侧邻居）。 */
    public boolean isBorder(int idx) {
        int x = lx(idx), z = lz(idx);
        return x == 0 || z == 0 || x == w - 1 || z == w - 1;
    }
}
