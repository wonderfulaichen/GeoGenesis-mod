package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 量大陆倾向噪声（展宽后）的分布，反推"54% 陆地"应有的阈值。 */
public final class ContinentNoiseDistributionProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        // 与 ProvinceContinentField 相同的构造
        int base = (int) (9139912035078620160L ^ (9139912035078620160L >>> 32));
        int seedOf = (base + 5519) & 0x7FFFFFFF;
        RefNoise.Node raw = RefNoise.Perlin.ofScale(seedOf,
                ProvinceContinentField.TECTONIC_SCALE * 3.5f, 3);
        RefNoise.Node widened = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);

        // 在"板块中心"上采样：格距 = TECTONIC_SCALE
        int n = 20000;
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            // 用格点坐标 × 格距，模拟"板块中心"
            int gx = (i % 400) - 200;
            int gy = (i / 400) - 25;
            double wx = (gx + 0.5) * ProvinceContinentField.TECTONIC_SCALE;
            double wz = (gy + 0.5) * ProvinceContinentField.TECTONIC_SCALE;
            vals[i] = widened.compute((float) wx, (float) wz);
        }
        java.util.Arrays.sort(vals);

        System.out.printf(Locale.ROOT, "板块中心样本 %d（格距 %d 块）%n", n,
                ProvinceContinentField.TECTONIC_SCALE);
        System.out.println("--- 展宽后的噪声分布 ---");
        for (double q : new double[]{0.05, 0.10, 0.25, 0.46, 0.50, 0.54, 0.75, 0.90, 0.95}) {
            System.out.printf(Locale.ROOT, "  P%-4.0f = %.4f%n", q * 100,
                    vals[Math.min(n - 1, (int) (q * n))]);
        }
        System.out.println("--- 反推：目标陆地占比 ⇒ 阈值 ---");
        for (double land : new double[]{0.30, 0.40, 0.50, 0.54, 0.60, 0.70}) {
            // 要 land 比例的样本 ≥ 阈值 ⇒ 阈值 = P(1−land)
            double thr = vals[Math.min(n - 1, (int) ((1 - land) * n))];
            System.out.printf(Locale.ROOT, "  陆地 %2.0f%% ⇒ 阈值 = %.4f%n", land * 100, thr);
        }
    }
}