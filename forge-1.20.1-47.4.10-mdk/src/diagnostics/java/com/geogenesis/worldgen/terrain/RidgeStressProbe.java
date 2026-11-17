package com.geogenesis.worldgen.terrain;

import java.util.Locale;

/**
 * 边界应力分布诊断（2026-11-17）：量化「哪些边界是离散的」。
 *
 * <p>问题：洋中脊只出现在离散边界，但 stress 是<b>连续加权投票的区域场</b>，
 * 不是逐边界分类器 ⇒ 用一个门槛挑「离散边界」必然有错判。
 * 本探针沿边界采样 stress，看真实分布该怎么切。</p>
 */
public final class RidgeStressProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TectonicField tf = new TectonicField(seed);
        int[] bins = new int[21];          // stress ∈ [-1,1] 分 21 桶
        int onBoundary = 0;
        int[] binsOn = new int[21];
        java.util.List<double[]> samples = new java.util.ArrayList<>();
        for (double z = -8000; z <= 8000; z += 60) {
            for (double x = -8000; x <= 8000; x += 60) {
                var t = tf.sample(x, z);
                if (t.distRaw() > 30) {
                    continue;
                }
                onBoundary++;
                double s = t.stress();
                int b = (int) Math.round((s + 1) * 10);
                b = Math.max(0, Math.min(20, b));
                binsOn[b]++;
                if (samples.size() < 4000) {
                    samples.add(new double[]{s, t.distRaw(), x, z});
                }
            }
        }
        System.out.println("boundary samples = " + onBoundary);
        System.out.println("stress histogram on boundaries (-1..1, step 0.1):");
        for (int i = 0; i <= 20; i++) {
            System.out.printf(Locale.ROOT, "  %+.1f : %5d %s%n",
                    -1 + i * 0.1, binsOn[i], "#".repeat(Math.min(60, binsOn[i] / (onBoundary / 60 + 1))));
        }
        // 分位数
        double[] s = new double[onBoundary];
        int k = 0;
        for (double z = -8000; z <= 8000; z += 60) {
            for (double x = -8000; x <= 8000; x += 60) {
                var t = tf.sample(x, z);
                if (t.distRaw() <= 30 && k < s.length) {
                    s[k++] = t.stress();
                }
            }
        }
        java.util.Arrays.sort(s);
        System.out.printf(Locale.ROOT, "quantiles: p1=%.3f p5=%.3f p10=%.3f p25=%.3f p50=%.3f p75=%.3f%n",
                s[(int) (s.length * 0.01)], s[(int) (s.length * 0.05)], s[(int) (s.length * 0.10)],
                s[(int) (s.length * 0.25)], s[(int) (s.length * 0.50)], s[(int) (s.length * 0.75)]);
        System.out.printf(Locale.ROOT, "fraction with stress < -0.30: %.3f%n",
                countBelow(s, -0.30) / (double) s.length);
        System.out.printf(Locale.ROOT, "fraction with stress < -0.50: %.3f%n",
                countBelow(s, -0.50) / (double) s.length);
    }

    private static int countBelow(double[] a, double v) {
        int c = 0;
        for (double x : a) {
            if (x < v) {
                c++;
            }
        }
        return c;
    }

    private RidgeStressProbe() { }
}