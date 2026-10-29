package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 直接量："被判定为陆壳的板块"占多少。
 *
 * <p>绕过 {@code sample()} 的全部后续处理，只用与 {@link ProvinceContinentField}
 * 完全相同的「板块中心 → 低频噪声 → 阈值」逻辑，避免被 relief/taper 干扰。</p>
 */
public final class ContinentFlagProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int base = (int) (9139912035078620160L ^ (9139912035078620160L >>> 32));
        int seedOf = (base + 5519) & 0x7FFFFFFF;
        RefNoise.Node raw = RefNoise.Perlin.ofScale(seedOf,
                ProvinceContinentField.TECTONIC_SCALE * 3.5f, 3);
        RefNoise.Node widened = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);

        System.out.println("阈值    陆壳板块占比（大范围格点采样）");
        for (double thr : new double[]{0.30, 0.35, 0.40, 0.46, 0.50, 0.55, 0.60}) {
            int total = 0, cont = 0;
            // 覆盖 200×200 个板块（= 2400×2400 万块），远离窗口中心偏差
            for (int gy = -100; gy < 100; gy++) {
                for (int gx = -100; gx < 100; gx++) {
                    double wx = (gx + 0.5) * ProvinceContinentField.TECTONIC_SCALE;
                    double wz = (gy + 0.5) * ProvinceContinentField.TECTONIC_SCALE;
                    double v = widened.compute((float) wx, (float) wz);
                    total++;
                    if (v > thr) cont++;
                }
            }
            System.out.printf(Locale.ROOT, "  %.2f   %5.1f%%  (%d/%d)%n",
                    thr, 100.0 * cont / total, cont, total);
        }
    }
}