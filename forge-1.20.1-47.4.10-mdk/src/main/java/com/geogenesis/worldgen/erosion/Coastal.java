package com.geogenesis.worldgen.erosion;

/**
 * 海岸冲刷（Coastal）：近岸带 |e| 小的陆/海边界做海蚀与海滩磨圆。
 *
 * <p>局部算子：对 |e| < coastBand 的格点，朝岸线（e=0）轻微位移——
 * 陆地侧（e>0）略降形成海滩磨圆，海洋侧（e<0）略升平滑近岸海床，
 * 消除海岸硬切与垂直崖壁。强度由 {@link ErosionSettings#coastalStrength()} 控制。
 */
public final class Coastal implements ErosionAgent {

    @Override
    public void apply(double[][] e, int size, int pad, ErosionSettings s) {
        int lo = pad, hi = size - pad;
        double band = s.coastBand();
        double k = s.coastalStrength();
        if (k <= 0.0) return;
        for (int i = lo; i < hi; i++) {
            for (int j = lo; j < hi; j++) {
                double h = e[i][j];
                double a = Math.abs(h);
                if (a < band) {
                    // 朝 0 位移：陆地略降、海洋略升
                    double move = (1.0 - a / band) * k * band;
                    e[i][j] = h - Math.signum(h) * move;
                }
            }
        }
    }
}
