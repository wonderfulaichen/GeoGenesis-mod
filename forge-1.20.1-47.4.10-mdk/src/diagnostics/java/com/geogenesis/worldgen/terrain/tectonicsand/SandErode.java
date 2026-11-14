package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱：<b>液滴水力侵蚀</b>——<b>1:1 复刻参考 planet.js 第 7 段</b>（数值全部照抄）。
 *
 * <h2>为什么上一版被判"无效"</h2>
 * <p>2026-11-13 我方写过一版液滴侵蚀，实测平均相邻高差只降 ×0.95，于是判"方法不行"并删除。
 * 2026-11-14 全文核对参考时发现漏了一步关键：参考在运算前把高度<b>归一化</b>
 * {@code h = elev·S, S = 1/40000}，所有阈值（{@code capF=5, minCap=0.002, eSpeed=0.3,
 * dSpeed=0.3, evap=0.02, grav=4}）都是按这个量纲定的。
 * 我方那版直接拿米（或 e）去套 ⇒ 阈值差 4 个数量级 ⇒ 侵蚀量近乎 0。
 * <b>结论：不是方法无效，是量纲用错。</b></p>
 *
 * <h2>参考的算法（逐行对应）</h2>
 * <ul>
 *   <li>笔刷：半径 2 的圆盘，权重 {@code max(0.01, 1 − d/2.5)} 归一化</li>
 *   <li>起点：随机撒点，最多试 6 次落在陆地（{@code h>0}）上</li>
 *   <li>每步：双线性取梯度 → {@code dir = 0.88·dir − 0.12·∇h} 归一（惯性 0.12）</li>
 *   <li>容量 {@code cap = max(−dh·sp·wat·5, 0.002)}</li>
 *   <li>{@code sed > cap || dh > 0} ⇒ 沉积；否则按笔刷侵蚀并把土带走</li>
 *   <li>入海（{@code nh < 0}）⇒ 泥沙沉在海里（不超过海平面），本滴结束</li>
 *   <li>{@code sp = √(sp² − dh·4)}、{@code wat ×= 0.98}，寿命 48 步</li>
 * </ul>
 *
 * <p>⚠ 沙箱适配：参考是球面（x 方向环绕），我们是平面窗口 ⇒ x/y 越界改为<b>钳位</b>。
 * 只影响窗口边缘几格，不影响内部形态。</p>
 */
final class SandErode {

    /** 高度归一化（参考的 {@code S = 1/40000}）。 */
    static final double S = 1.0 / 40000.0;
    /** e → 参考的无量纲单位：{@code e·METERS_PER_E·S}（= e·0.275）。 */
    static final double E_TO_SCALED = SandTerrain.METERS_PER_E * S;

    private SandErode() { }

    /**
     * 对高度网格（e 单位）跑液滴侵蚀。
     *
     * @param count 液滴数（参考 {@code min(560000, W·H·0.17)}）
     * @return 实际完成的液滴数
     */
    static int erode(double[][] elev, int n, int count, long seed) {
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] = elev[j][i] * E_TO_SCALED;
            }
        }

        // 笔刷（半径 2 的圆盘，权重 1 − d/2.5）
        int R = 2;
        java.util.List<double[]> brush = new java.util.ArrayList<>();
        double wsum = 0;
        for (int dy = -R; dy <= R; dy++) {
            for (int dx = -R; dx <= R; dx++) {
                double d = Math.hypot(dx, dy);
                if (d < R + 0.01) {
                    double w = Math.max(0.01, 1 - d / (R + 0.5));
                    brush.add(new double[]{dx, dy, w});
                    wsum += w;
                }
            }
        }
        for (double[] b : brush) {
            b[2] /= wsum;
        }

        final double inertia = 0.12, capF = 5, minCap = 0.002;
        final double eSpeed = 0.3, dSpeed = 0.3, evap = 0.02, grav = 4;
        final int life = 48;
        long rng = seed;
        int done = 0;
        for (int d = 0; d < count; d++) {
            // mulberry32 等价：每个液滴取 2 个 0..1
            rng += 0x6D2B79F5L;
            long t = rng;
            t = (t ^ (t >>> 15)) * (t | 1L);
            t ^= t + (t ^ (t >>> 7)) * (t | 61L);
            double r1 = ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
            rng += 0x6D2B79F5L;
            t = rng;
            t = (t ^ (t >>> 15)) * (t | 1L);
            t ^= t + (t ^ (t >>> 7)) * (t | 61L);
            double r2 = ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;

            double px = r1 * n;
            double py = 2 + r2 * (n - 4);
            int tries = 0;
            while (h[(int) py][(int) px] <= 0 && tries < 6) {
                rng += 0x6D2B79F5L;
                long t2 = rng;
                t2 = (t2 ^ (t2 >>> 15)) * (t2 | 1L);
                t2 ^= t2 + (t2 ^ (t2 >>> 7)) * (t2 | 61L);
                px = (((t2 ^ (t2 >>> 14)) & 0xFFFFFFFFL) / 4294967296.0) * n;
                py = 2 + (((t2 >>> 20) & 0xFFFFF) / 1048576.0) * (n - 4);
                tries++;
            }
            if (h[(int) py][(int) px] <= 0) {
                continue;
            }
            done++;
            double dx = 0, dy = 0, sp = 1, wat = 1, sed = 0;
            for (int l = 0; l < life; l++) {
                int xi = (int) px, yi = (int) py;
                if (xi < 0 || yi < 0 || xi >= n - 1 || yi >= n - 1) {
                    break;
                }
                double cx = px - xi, cy = py - yi;
                int x1 = xi + 1;
                double a = h[yi][xi], b = h[yi][x1], c = h[yi + 1][xi], e = h[yi + 1][x1];
                double gx = (b - a) * (1 - cy) + (e - c) * cy;
                double gy = (c - a) * (1 - cx) + (e - b) * cx;
                double ht = a * (1 - cx) * (1 - cy) + b * cx * (1 - cy)
                        + c * (1 - cx) * cy + e * cx * cy;
                dx = dx * inertia - gx * (1 - inertia);
                dy = dy * inertia - gy * (1 - inertia);
                double len = Math.hypot(dx, dy);
                if (len < 1e-12) {
                    break;
                }
                dx /= len;
                dy /= len;
                px += dx;
                py += dy;
                if (py < 1 || py >= n - 2) {
                    break;
                }
                if (px < 0 || px >= n - 1) {
                    px = Math.max(0, Math.min(n - 1.001, px));   // 平面：钳位（参考是环绕）
                }
                int nx = (int) px, ny = (int) py;
                double ncx = px - nx, ncy = py - ny;
                int nx1 = nx + 1;
                double nh = h[ny][nx] * (1 - ncx) * (1 - ncy) + h[ny][nx1] * ncx * (1 - ncy)
                        + h[ny + 1][nx] * (1 - ncx) * ncy + h[ny + 1][nx1] * ncx * ncy;
                double dh = nh - ht;
                if (nh < 0) {
                    // 入海：泥沙沉在海里（陆架/河口前缘），沉积不高于海平面
                    double dep = sed * 0.5;
                    double lim = -2 * S;
                    double[] ws = {(1 - ncx) * (1 - ncy), ncx * (1 - ncy),
                            (1 - ncx) * ncy, ncx * ncy};
                    int[] jy = {ny, ny, ny + 1, ny + 1};
                    int[] jx = {nx, nx1, nx, nx1};
                    for (int k = 0; k < 4; k++) {
                        if (h[jy[k]][jx[k]] < lim) {
                            h[jy[k]][jx[k]] = Math.min(lim, h[jy[k]][jx[k]] + dep * ws[k]);
                        }
                    }
                    break;
                }
                double cap = Math.max(-dh * sp * wat * capF, minCap);
                if (sed > cap || dh > 0) {
                    double amt = dh > 0 ? Math.min(dh, sed) : (sed - cap) * dSpeed;
                    sed -= amt;
                    h[yi][xi] += amt * (1 - cx) * (1 - cy);
                    h[yi][x1] += amt * cx * (1 - cy);
                    h[yi + 1][xi] += amt * (1 - cx) * cy;
                    h[yi + 1][x1] += amt * cx * cy;
                } else {
                    double amt = Math.min((cap - sed) * eSpeed, -dh);
                    for (double[] b2 : brush) {
                        int bx = xi + (int) b2[0];
                        int by = yi + (int) b2[1];
                        if (bx < 0 || by < 0 || bx >= n || by >= n) {
                            continue;
                        }
                        double v = amt * b2[2];
                        h[by][bx] -= v;
                        sed += v;
                    }
                }
                sp = Math.sqrt(Math.max(0, sp * sp - dh * grav));
                wat *= 1 - evap;
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                elev[j][i] = h[j][i] / E_TO_SCALED;
            }
        }
        return done;
    }
}