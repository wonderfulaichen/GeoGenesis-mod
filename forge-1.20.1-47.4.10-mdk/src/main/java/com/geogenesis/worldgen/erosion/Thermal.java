package com.geogenesis.worldgen.erosion;

/**
 * 坡积软化（Thermal / Talus）：超安息角的陡坡向低处扩散高度。
 *
 * <p>局部算子：每格与其 4 邻居比较，若坡度（e 差）超过安息角，则按强度
 * 把超额高度转移给低处邻居。仅作用于陆地（e>0），不与海洋交换——
 * 海陆边界由 {@link Coastal} 处理，避免双重侵蚀海岸。运行 2 趟趋近稳态。
 */
public final class Thermal implements ErosionAgent {

    @Override
    public void apply(double[][] e, int size, int pad, ErosionSettings s) {
        int lo = pad, hi = size - pad;
        double maxSlope = s.talusAngle();
        double k = s.thermalStrength();
        if (k <= 0.0) return;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = lo; i < hi; i++) {
                for (int j = lo; j < hi; j++) {
                    talusStep(e, i, j, i + 1, j, maxSlope, k);
                    talusStep(e, i, j, i - 1, j, maxSlope, k);
                    talusStep(e, i, j, i, j + 1, maxSlope, k);
                    talusStep(e, i, j, i, j - 1, maxSlope, k);
                }
            }
        }
    }

    /** 若 (i,j) 比邻居高过多（且二者皆陆地），转移超额高度给邻居。 */
    private void talusStep(double[][] e, int i, int j, int ni, int nj,
                           double maxSlope, double k) {
        double h = e[i][j], nh = e[ni][nj];
        if (h <= 0.0 || nh <= 0.0) return;          // 仅陆地内部
        double diff = h - nh;
        if (diff > maxSlope) {
            double m = (diff - maxSlope) * 0.5 * k;
            e[i][j] -= m;
            e[ni][nj] += m;
        }
    }
}
