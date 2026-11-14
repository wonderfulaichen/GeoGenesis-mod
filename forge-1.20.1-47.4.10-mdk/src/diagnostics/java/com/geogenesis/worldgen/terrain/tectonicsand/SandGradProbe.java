package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.Arrays;
import java.util.Locale;

/**
 * 沙箱诊断：<b>逐分量梯度长尾比</b>——定位"折尖/折痕属于哪个场"。
 *
 * <p>这是项目既有的方法论（{@code AGENTS.md}「[F] 逐分量梯度排查」）：
 * 对每个输入分量单独求 {@code |∇|}，打印 {@code P99.9 / P50} 长尾比。
 * 光滑场约 3~8；含折痕线的场极大。</p>
 *
 * <p>为什么必须逐分量：俯视图上一条"尖"可能来自 10 个分量里的任意一个，
 * 只看总高度猜不出来。</p>
 *
 * <p>用法：{@code SandGradProbe [size=700] [radius=1750]}</p>
 */
public final class SandGradProbe {

    private static final long SEED = 9139912035078620160L;
    /** 与 {@link SandTerrain.Parts} 的四个分量一一对应（旧版还有 6 个恒 0 字段，已删）。 */
    private static final String[] NAMES = {"base", "orogen", "trench", "detail"};
    private static final int NC = 4;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 700;
        double radius = args.length > 1 ? Double.parseDouble(args[1]) : 1750.0;
        double step = 2 * radius / (n - 1);

        double[][][] comp = new double[NC][n][n];
        for (int j = 0; j < n; j++) {
            double wz = -radius + j * step;
            for (int i = 0; i < n; i++) {
                double wx = -radius + i * step;
                SandTerrain.Parts p = SandTerrain.parts(SEED, wx, wz);
                double[] v = {p.base(), p.orogen(), p.trench(), p.detail()};
                for (int k = 0; k < NC; k++) {
                    comp[k][j][i] = v[k];
                }
            }
        }

        System.out.printf(Locale.ROOT, "=== 逐分量梯度长尾比  size=%d step=%.2fwu ===%n", n, step);
        System.out.printf(Locale.ROOT, "%-8s %10s %10s %10s %8s   %s%n",
                "分量", "P50", "P99", "P99.9", "尾比", "最大梯度位置(wu)");
        for (int k = 0; k < NC; k++) {
            double[] g = new double[(n - 1) * (n - 1) * 2];
            int idx = 0;
            double mx = 0;
            int mi = 0, mj = 0;
            for (int j = 0; j < n - 1; j++) {
                for (int i = 0; i < n - 1; i++) {
                    double gx = Math.abs(comp[k][j][i + 1] - comp[k][j][i]);
                    double gy = Math.abs(comp[k][j + 1][i] - comp[k][j][i]);
                    g[idx++] = gx;
                    g[idx++] = gy;
                    double m = Math.max(gx, gy);
                    if (m > mx) {
                        mx = m;
                        mi = i;
                        mj = j;
                    }
                }
            }
            Arrays.sort(g);
            double p50 = g[g.length / 2];
            double p99 = g[(int) (g.length * 0.99)];
            double p999 = g[(int) (g.length * 0.999)];
            // ⚠ P50 可能是 0（该分量在大部分区域恒为 0）⇒ 比值无意义，别打成 4.5e28
            String ratio = p50 < 1e-12 ? "     n/a" : String.format(Locale.ROOT, "%8.1f", p999 / p50);
            System.out.printf(Locale.ROOT, "%-8s %10.3e %10.3e %10.3e %s   (%.0f, %.0f)%n",
                    NAMES[k], p50, p99, p999, ratio,
                    -radius + mi * step, -radius + mj * step);
        }
    }
}