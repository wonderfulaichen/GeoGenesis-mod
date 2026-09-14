package com.geogenesis.worldgen.noise;

/**
 * 三维 Simplex 噪声（★ 2026-09-15 新增）—— <b>Stefan Gustavson 经典实现</b>，返回约 [-1,1]。
 *
 * <h3>为何新增（洞穴需要真 3D 隧道）</h3>
 * <p>见 {@link Noise3} 的说明：2D 柱体切挖在几何上做不出隧道；真正的隧道来自
 * <b>两个 3D 噪声等值面的交线</b>（两面相交 ⇒ 一维曲线）。本类是那套方案的基础构件。</p>
 *
 * <h3>为何自己写而不移植参考项目</h3>
 * <p>参考项目（RTG 的 {@code OpenSimplexNoise}）用的是 OpenSimplex，其 3D 部分依赖
 * 两张巨大的预计算表（{@code GRADIENTS_3D} 与 <b>2048 项的 {@code LOOKUP_3D}</b>），
 * 全类 552 行、可读性与可验证性都差，且与本项目的噪声体系（{@link Seed} 置换表 +
 * {@link NoiseUtil}）不通用。</p>
 *
 * <p>本实现改用<b>经典 Gustavson 3D Simplex</b>（约 70 行），与本项目既有的
 * {@link Simplex}（2D）<b>同一算法族、同一 {@link Seed} 置换表</b> ⇒
 * 风格一致、可读、可逐点与参考实现对拍验证。</p>
 *
 * <h3>算法要点</h3>
 * <ol>
 *   <li>用 {@code F3}/{@code G3} 在正四面体晶格上做偏斜/反偏斜（非立方晶格 ⇒ 无方向性伪影）；</li>
 *   <li>把点定位到四面体，按 {@code x0≥y0≥z0} 的排名确定 <b>4 个顶点</b>的偏移；</li>
 *   <li>各顶点贡献核 {@code t = 0.6 - x²-y²-z²}，{@code t<0} 时为 0，否则 {@code t⁴·(grad·d)}；</li>
 *   <li>取 12 个指向立方体棱中点的梯度，总和乘 32 归一化到约 [-1,1]。</li>
 * </ol>
 */
public final class Simplex3 implements Noise3 {

    /** 3D 偏斜因子：(√4-1)/3。 */
    private static final double F3 = 1.0 / 3.0;
    /** 3D 反偏斜因子：(4-√4)/... 经典值 1/6。 */
    private static final double G3 = 1.0 / 6.0;

    /**
     * 12 个梯度向量（指向立方体<b>棱的中点</b>）。
     * 经典 Gustavson 用这 12 个方向，在四面体晶格上分布对称、无偏。
     */
    private static final int[][] GRAD3 = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };

    private final int seedOffset;
    private Seed seed;

    public Simplex3(int seedOffset) {
        this.seedOffset = seedOffset;
    }

    public int seedOffset() {
        return seedOffset;
    }

    /**
     * 播种（与 {@link Simplex#seed} 同口径：worldSeed ⊕ seedOffset ⊕ level）。
     */
    public void seed(long worldSeed, int level) {
        long s = worldSeed ^ ((long) seedOffset << 16) ^ ((long) level * 0x9e3779b9L);
        this.seed = new Seed(s);
    }

    public boolean isSeeded() {
        return seed != null;
    }

    @Override
    public double compute(double x, double y, double z) {
        // 加固定偏移：避免整数坐标恰好落在晶格顶点上输出 0（与 2D Simplex 同理由）
        x += 0.5;
        y += 0.5;
        z += 0.5;

        double s = (x + y + z) * F3;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);
        int k = fastFloor(z + s);

        double t = (i + j + k) * G3;
        double x0 = x - (i - t);
        double y0 = y - (j - t);
        double z0 = z - (k - t);

        // 确定四面体的 4 个顶点偏移（按坐标大小排名）
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            }
        }

        double x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3;
        double x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;

        int ii = i & 255, jj = j & 255, kk = k & 255;

        double n = 0;
        n += corner(seed.gradIndex3(ii, jj, kk), x0, y0, z0);
        n += corner(seed.gradIndex3(ii + i1, jj + j1, kk + k1), x1, y1, z1);
        n += corner(seed.gradIndex3(ii + i2, jj + j2, kk + k2), x2, y2, z2);
        n += corner(seed.gradIndex3(ii + 1, jj + 1, kk + 1), x3, y3, z3);

        // 缩放至约 [-1,1]（经典 3D 用 32）
        return 32.0 * n;
    }

    private static double corner(int hash, double x, double y, double z) {
        double t = 0.6 - x * x - y * y - z * z;
        if (t < 0.0) return 0.0;
        t *= t;
        int[] g = GRAD3[hash % 12];
        return t * t * (g[0] * x + g[1] * y + g[2] * z);
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
