package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 量 {@link ProvinceContinentField} 两级域扭曲的实际位移幅度。 */
public final class WarpMagnitudeProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        // 与 ProvinceContinentField 相同的噪声构造
        int base = (int) (9139912035078620160L ^ (9139912035078620160L >>> 32));
        RefNoise.Node w1x = RefNoise.Perlin.ofScale(base + 2141, 20f, 2);
        RefNoise.Node w2x = RefNoise.Perlin.ofScale(base + 2143, ProvinceContinentField.CONTINENT_SCALE, 3);

        int n = 50000;
        double[] o1 = new double[n], o2 = new double[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = 20000.0 * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            o1[i] = (w1x.compute((float) x, (float) z) - 0.5f) * 20.0;
            o2[i] = (w2x.compute((float) x, (float) z) - 0.5f) * 3000.0;
        }
        report("warp1 ×20", o1);
        report("warp2 ×3000", o2);
    }

    private static void report(String name, double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        System.out.printf(Locale.ROOT,
                "%-14s min=%9.1f P10=%8.1f P50=%8.1f P90=%8.1f max=%9.1f  标准差=%7.1f%n",
                name, s[0], s[(int) (0.10 * s.length)], s[s.length / 2],
                s[(int) (0.90 * s.length)], s[s.length - 1], std(v));
    }

    private static double std(double[] v) {
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / v.length);
    }
}