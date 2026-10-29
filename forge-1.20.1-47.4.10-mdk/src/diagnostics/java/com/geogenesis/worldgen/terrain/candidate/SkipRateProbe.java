package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 统计 {@link ProvinceContinentField} 的板块跳过率与"被跳过板块"的空间占比。 */
public final class SkipRateProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        ProvinceContinentField f = new ProvinceContinentField(9139912035078620160L);

        int n = 20000;
        int skipped = 0, land = 0;
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = 20000.0 * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double e = f.sampleEdge(x, z);
            if (e <= 1e-9) skipped++;      // 被跳过的板块返回 0
            if (e >= 0.0734) land++;
        }
        System.out.printf(Locale.ROOT, "采样 %d%n", n);
        System.out.printf(Locale.ROOT, "  edge==0（被跳过或深海）: %.1f%%%n", 100.0 * skipped / n);
        System.out.printf(Locale.ROOT, "  edge≥0.0734（陆地）:      %.1f%%%n", 100.0 * land / n);
        System.out.printf(Locale.ROOT, "  edge>0 但 <0.0734（海）:  %.1f%%%n",
                100.0 * (n - skipped - land) / n);
    }
}