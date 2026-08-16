package com.geogenesis.worldgen.river;

/**
 * 低分辨率 e 场网格（确定性几何河网 · Phase 1）。
 *
 * <p>每 tile（128wu）一个：网格间距 {@code spacing}（默认 4wu），采样区
 * [origin, origin+128) 内含右/上边界共 (n+1)² 个点。边界点与邻居 tile 的
 * 0 号点世界坐标相同、e 场确定性 → 跨 tile 数值一致，无需共享/扩展。</p>
 *
 * <p>仅供 {@link RiverNetwork#eAt} 查询（D8 流域追踪 / 支流 Dijkstra 的成本场），
 * 非线程安全（构建后只读，由 RiverNetwork 缓存管理）。</p>
 */
final class RiverGrid {

    static final int SIZE = 128;   // wu，= 8 chunk，与侵蚀 tile 网格对齐

    final int tileCX, tileCZ;
    final int n;                    // 网格边长 = SIZE / spacing
    private final double[] e;       // (n+1)×(n+1)，X 主序，含右/上边界

    RiverGrid(int tileCX, int tileCZ, int spacing, ESampler sampler) {
        this.tileCX = tileCX;
        this.tileCZ = tileCZ;
        this.n = SIZE / spacing;
        this.e = new double[(n + 1) * (n + 1)];
        int ox = tileCX * SIZE, oz = tileCZ * SIZE;
        for (int iz = 0; iz <= n; iz++) {
            int row = iz * (n + 1);
            for (int ix = 0; ix <= n; ix++) {
                e[row + ix] = sampler.eAt(ox + ix * spacing, oz + iz * spacing);
            }
        }
    }

    /** 网格点直接读（ix,iz ∈ [0,n]） */
    double atNode(int ix, int iz) {
        return e[iz * (n + 1) + ix];
    }

    /**
     * 双线性插值（调用方保证 (wx,wz) 位于本 tile 覆盖区
     * [tileCX*SIZE, (tileCX+1)*SIZE) × [tileCZ*SIZE, (tileCZ+1)*SIZE)）。
     */
    double at(double wx, double wz) {
        double gx = (wx - tileCX * SIZE) / (double) (SIZE / n);
        double gz = (wz - tileCZ * SIZE) / (double) (SIZE / n);
        int ix = Math.min(n - 1, Math.max(0, (int) Math.floor(gx)));
        int iz = Math.min(n - 1, Math.max(0, (int) Math.floor(gz)));
        double fx = gx - ix, fz = gz - iz;
        int row = iz * (n + 1);
        double e00 = e[row + ix], e10 = e[row + ix + 1];
        double e01 = e[row + n + 1 + ix], e11 = e[row + n + 1 + ix + 1];
        return e00 + fx * (e10 - e00) + fz * (e01 - e00) + fx * fz * (e00 - e10 - e01 + e11);
    }
}
