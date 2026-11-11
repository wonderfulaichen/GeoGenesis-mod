package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 海陆比与 {@code continentEdge} 分布探针。
 *
 * <h3>为什么必须量这个</h3>
 * <p>海陆衔接用的是参考 {@code Presets.java:24} 的 controlPoints
 * （deepOcean=0.1 / shallowOcean=0.25 / coast=0.448 / inland=0.502），
 * 但这些数值是<b>按参考自己的 {@code continentEdge} 分布标定的</b>。</p>
 *
 * <p>参考的 {@code continentEdge} 来自它自己的大陆几何（Voronoi 大陆 + 边缘距离场，
 * {@code ContinentType.UPLIFT}），而我们的 {@code c} 是 FBM
 * （{@code ContinentField}，6 octaves，4000wu）。两者分布形状不同 ⇒
 * <b>同一组 controlPoints 切出来的海陆比必然不同</b>。</p>
 *
 * <p>本探针给出：{@code edge=(c+1)/2} 的分位数，以及"按给定 controlPoints
 * 切出来的海陆比"，据此反推应该用哪组阈值。</p>
 *
 * <p>用法：{@code <toml> <outBase> <samples> <spanBlocks>}</p>
 */
public final class ContinentEdgeProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 400000;
        int span = args.length > 3 ? Integer.parseInt(args[3]) : 40000;
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);

        float[] edge = new float[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = (span / 2.0) * Math.sqrt((i + 1.0) / n);
            double c = continent.sample(Math.cos(a) * r, Math.sin(a) * r);
            edge[i] = (float) ((c + 1.0) * 0.5);
            sum += edge[i];
        }
        java.util.Arrays.sort(edge);

        System.out.printf(Locale.ROOT, "样本 %d，跨度 %d 块，mean=%.4f%n", n, span, sum / n);
        System.out.println("--- continentEdge 分位数 ---");
        for (double q : new double[]{0.01, 0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99}) {
            System.out.printf(Locale.ROOT, "  P%-3.0f = %.4f%n", q * 100,
                    edge[(int) Math.min(n - 1, q * n)]);
        }

        // 参考 controlPoints（Presets.java:24）
        double cpCoast = 0.448, cpInland = 0.502;
        System.out.println("--- 按参考 controlPoints 切出的海陆比 ---");
        report(edge, cpCoast, cpInland, "参考值 coast=0.448 inland=0.502");

        // 目标：陆地占比 ~70%（MC 常见的大陆型世界）
        System.out.println("--- 反推：要 70% 陆地，阈值该取在哪 ---");
        double q30 = edge[(int) (0.30 * n)];
        double q35 = edge[(int) (0.35 * n)];
        System.out.printf(Locale.ROOT,
                "  P30=%.4f ⇒ 陆地 70%%   P35=%.4f ⇒ 陆地 65%%%n", q30, q35);
        report(edge, q30, q30 + 0.02, String.format(Locale.ROOT,
                "P30 标定 coast=%.3f inland=%.3f", q30, q30 + 0.02));
        report(edge, q35, q35 + 0.02, String.format(Locale.ROOT,
                "P35 标定 coast=%.3f inland=%.3f", q35, q35 + 0.02));

        Files.writeString(out.resolve("continent-edge.txt"),
                "quantiles P1..P99; see console output\n");
    }

    private static void report(float[] sorted, double coast, double inland, String label) {
        int below = 0, mid = 0, above = 0;
        for (float v : sorted) {
            if (v < coast) below++;
            else if (v <= inland) mid++;
            else above++;
        }
        double n = sorted.length;
        System.out.printf(Locale.ROOT,
                "  %-34s 海 %.1f%%（含过渡带 %.1f%%）｜陆 %.1f%%%n",
                label, below * 100.0 / n, mid * 100.0 / n, above * 100.0 / n);
    }
}