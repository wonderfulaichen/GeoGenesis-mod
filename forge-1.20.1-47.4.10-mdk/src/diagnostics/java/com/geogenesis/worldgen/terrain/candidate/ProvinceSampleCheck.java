package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 对照 {@link ProvinceContinentField#sample} 与 {@link ProvinceContinentField#sampleEdge}。 */
public final class ProvinceSampleCheck {

    public static void main(String[] args) {
        ProvinceContinentField f = new ProvinceContinentField(9139912035078620160L);

        System.out.println("点(x,z)              sampleEdge   sample(c)   (c+1)/2");
        for (int i = 0; i < 8; i++) {
            double a = i * 2.399963229728653;
            double r = 20000.0 * Math.sqrt((i + 1.0) / 20000.0);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double e = f.sampleEdge(x, z);
            double c = f.sample(x, z);
            System.out.printf(Locale.ROOT, "(%8.0f,%8.0f)  %10.6f  %10.6f  %10.6f%n",
                    x, z, e, c, (c + 1.0) * 0.5);
        }
    }
}