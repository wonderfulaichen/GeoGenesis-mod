package com.geogenesis.worldgen.river;

/**
 * D8 流向场（汇水分析驱动河网 · 阶段 A）。
 *
 * <p>在低分辨率 e 场网格（间距 {@code spacing}wu、tile 128wu）上计算每个格点的
 * 8 邻最陡下坡方向（D8）。每格只依赖 8 邻 e 值 = <b>局部算子</b>：tile 缓存安全、
 * 确定性、跨 tile 无缝（e 场是纯世界坐标函数 {@link ESampler}，越界邻点直接采样，
 * 无需读邻居 tile 快照）。这与 AGENTS.md 记载的历史教训（全局 flow-accumulation
 * 本质非局部 → 边界断裂不可根治）相反——流向场局部性天然免疫该问题，可安全用于
 * 跨 tile 流线追踪。</p>
 *
 * <p>格点语义：{@code dirAt(i,j) ∈ 0..7} = 8 邻方向（{@link #DX8}/{@link #DZ8}），
 * {@code -1} = 无更低的 8 邻（局部最低 = 洼地/盆地，追踪终止 → 阶段 B 填湖）。
 * 梯度 {@code gradAt(i,j) = (e[格点] − e[邻]) / spacing}（每 wu e 降幅），供缓坡
 * 蛇曲与密度判定。海洋格点一视同仁计算（陆地格点指向海床 = 河流入海的天然方向），
 * 入海终止（e≤0）由调用方判定。</p>
 */
final class FlowField {

    /** tile 边长（wu），与 {@link RiverNetwork#BASIN_SIZE} 对齐 */
    static final int SIZE = 128;

    /** 8 邻方向（k=0..7；与 RiverBuilder2.DX8 同序，追踪/探针共用） */
    static final int[] DX8 = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DZ8 = {0, 1, 1, 1, 0, -1, -1, -1};

    final int tileCX, tileCZ;
    final int spacing;
    final int n;                       // 格点边长 = SIZE / spacing
    private final byte[] dir;          // (n+1)²，0..7 / -1
    private final float[] grad;        // (n+1)²，梯度（无下坡 = 0）

    private FlowField(int tileCX, int tileCZ, int spacing, byte[] dir, float[] grad) {
        this.tileCX = tileCX;
        this.tileCZ = tileCZ;
        this.spacing = spacing;
        this.n = SIZE / spacing;
        this.dir = dir;
        this.grad = grad;
    }

    /** 格点流向（0..7；-1 = 局部最低/洼地）。ix, iz ∈ [0, n]。 */
    int dirAt(int ix, int iz) {
        return dir[iz * (n + 1) + ix];
    }

    /** 格点梯度（每 wu e 降幅；无下坡 = 0）。 */
    float gradAt(int ix, int iz) {
        return grad[iz * (n + 1) + ix];
    }

    /** 世界坐标 → 本 tile 内最近格点 X 索引（clamp 到 [0,n]）。 */
    int ixOf(double wx) {
        int ix = (int) Math.floor((wx - tileCX * SIZE) / (double) spacing);
        return Math.min(n, Math.max(0, ix));
    }

    /** 世界坐标 → 本 tile 内最近格点 Z 索引（clamp 到 [0,n]）。 */
    int izOf(double wz) {
        int iz = (int) Math.floor((wz - tileCZ * SIZE) / (double) spacing);
        return Math.min(n, Math.max(0, iz));
    }

    /**
     * 构建（纯函数）：只依赖世界坐标 + e 场 + spacing → 任意 tile/任意线程/任意
     * 时序构建结果一致。每格 8 邻最陡下坡（严格 {@code <}，无平局随机 → 确定性）。
     */
    static FlowField build(int tileCX, int tileCZ, int spacing, ESampler sampler) {
        int n = SIZE / spacing;
        int n1 = n + 1;
        byte[] dir = new byte[n1 * n1];
        float[] grad = new float[n1 * n1];
        double ox = tileCX * SIZE, oz = tileCZ * SIZE;
        for (int iz = 0; iz <= n; iz++) {
            double wz = oz + iz * spacing;
            int row = iz * n1;
            for (int ix = 0; ix <= n; ix++) {
                double wx = ox + ix * spacing;
                double ce = sampler.eAt(wx, wz);
                int best = -1;
                double bestE = ce;
                for (int k = 0; k < 8; k++) {
                    double ne = sampler.eAt(wx + DX8[k] * spacing, wz + DZ8[k] * spacing);
                    if (ne < bestE) {
                        bestE = ne;
                        best = k;
                    }
                }
                dir[row + ix] = (byte) best;
                grad[row + ix] = best >= 0 ? (float) ((ce - bestE) / spacing) : 0f;
            }
        }
        return new FlowField(tileCX, tileCZ, spacing, dir, grad);
    }
}
