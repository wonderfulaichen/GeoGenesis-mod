package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱自检：{@link SandLandscape} 在<b>合成地形</b>上是否真的会演化。
 *
 * <p>为什么要单独自检：它在真实地形上跑了 30 步，平均 |∇h| 变化 ×1.00 ——
 * 要么侵蚀没跑，要么量级太小。合成场（斜面 + 一个高斯山包）能一眼分辨：
 * 斜面会被切成平行沟谷、山包会被放射状沟谷切开。</p>
 */
public final class SandLandscapeCheck {

    public static void main(String[] args) {
        int n = 200;
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = i / (double) (n - 1), z = j / (double) (n - 1);
                // 斜面（东低）+ 一个高斯山包 + 微量噪声打破完美对称
                h[j][i] = 1.0 - 0.8 * x
                        + 0.6 * Math.exp(-((x - 0.5) * (x - 0.5) + (z - 0.5) * (z - 0.5)) / 0.02)
                        + 0.01 * SandPlateField.valueNoise(i * 0.7, j * 0.7, 12345L);
            }
        }
        stats("演化前", h);
        long t0 = System.currentTimeMillis();
        SandLandscape.evolve(h, 40, -10.0, SandLandscape.Params.defaults());
        long ms = System.currentTimeMillis() - t0;
        stats("演化后", h);
        System.out.println("耗时 " + ms + " ms");
    }

    private static void stats(String tag, double[][] h) {
        int n = h.length;
        double s = 0, s2 = 0, grad = 0;
        long c = 0;
        double lo = 1e18, hi = -1e18;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                s += h[j][i];
                s2 += h[j][i] * h[j][i];
                c++;
                lo = Math.min(lo, h[j][i]);
                hi = Math.max(hi, h[j][i]);
                if (i + 1 < n) {
                    grad += Math.abs(h[j][i + 1] - h[j][i]);
                }
                if (j + 1 < n) {
                    grad += Math.abs(h[j + 1][i] - h[j][i]);
                }
            }
        }
        double mean = s / c;
        double std = Math.sqrt(Math.max(0, s2 / c - mean * mean));
        System.out.printf(java.util.Locale.ROOT,
                "%s  mean=%.4f std=%.4f 范围=[%.3f, %.3f]  平均|∇h|=%.5f%n",
                tag, mean, std, lo, hi, grad / c);
    }
}