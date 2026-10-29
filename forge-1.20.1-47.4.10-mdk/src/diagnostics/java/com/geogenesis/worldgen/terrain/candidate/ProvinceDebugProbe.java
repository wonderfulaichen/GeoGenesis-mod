package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * {@link ProvinceContinentField} 中间量探针：定位"edge 恒 0"的具体环节。
 *
 * <p>逐项打印 {@code nearest / sizeMod / sqrt / normMap / clampEdge / shape / coastal}
 * 的分位数，看是哪一步塌掉。</p>
 */
public final class ProvinceDebugProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        ProvinceContinentField f = new ProvinceContinentField(9139912035078620160L);

        int n = 20000;
        double[] edge = new double[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = 20000.0 * Math.sqrt((i + 1.0) / n);
            edge[i] = f.sampleEdge(Math.cos(a) * r, Math.sin(a) * r);
        }
        java.util.Arrays.sort(edge);
        System.out.println("edge 分位：");
        for (double q : new double[]{0.10, 0.25, 0.50, 0.75, 0.90, 0.99, 1.0}) {
            System.out.printf(Locale.ROOT, "  P%-4.0f = %.6f%n", q * 100,
                    edge[Math.min(n - 1, (int) (q * n))]);
        }
        int nonzero = 0;
        for (double v : edge) if (v > 0.001) nonzero++;
        System.out.printf(Locale.ROOT, "  edge > 0.001 的比例 = %.1f%%%n", 100.0 * nonzero / n);

        // 手算几个点，逐步打印
        System.out.println("--- 逐步分解（前 5 个采样点）---");
        ProvinceContinentField.DEBUG = true;
        for (int i = 0; i < 5; i++) {
            double a = i * 2.399963229728653;
            double r = 20000.0 * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            System.out.printf(Locale.ROOT, "  点(%.0f,%.0f) edge=%.6f%n", x, z, f.sampleEdge(x, z));
        }
        ProvinceContinentField.DEBUG = false;
    }
}