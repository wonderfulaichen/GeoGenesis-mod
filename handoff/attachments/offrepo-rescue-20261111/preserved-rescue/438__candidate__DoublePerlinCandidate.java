package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;

/** Isolated coordinate precision experiment, NOT wired to a recipe. */
final class DoublePerlinCandidate {
    private final int seed, octaves;
    private final double frequency, lacunarity, gain, min, max;
    DoublePerlinCandidate(RefNoise.Perlin p) {
        seed = (Integer) field(p, "seed"); octaves = (Integer) field(p, "octaves");
        frequency = (Float) field(p, "frequency"); lacunarity = (Float) field(p, "lacunarity");
        gain = (Float) field(p, "gain"); min = (Float) field(p, "min"); max = (Float) field(p, "max");
    }
    double compute(double x, double z) {
        x *= frequency; z *= frequency;
        double sum = 0, amp = gain;
        for (int i = 0; i < octaves; i++) {
            if (x <= Integer.MIN_VALUE || x >= Integer.MAX_VALUE - 1.0 ||
                z <= Integer.MIN_VALUE || z >= Integer.MAX_VALUE - 1.0)
                throw new IllegalArgumentException("noise lattice outside int range");
            sum += GeometryTransform.sampleValueAndGradient(seed + i, x, z)[0] * amp;
            x *= lacunarity; z *= lacunarity; amp *= gain;
        }
        return Math.max(0, Math.min(1, (sum - min) / (max - min)));
    }
    private static Object field(Object o, String n) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static void main(String[] args) {
        for (int octaves : new int[]{1, 3, 6}) {
            RefNoise.Perlin f = RefNoise.Perlin.ofScale(42, 400, octaves);
            DoublePerlinCandidate d = new DoublePerlinCandidate(f);
            double max = 0; int distinct = 0;
            for (int i = 0; i < 10000; i++) {
                float x = (i * 0.713f) - 3000, z = (i * 0.377f) - 1500;
                max = Math.max(max, Math.abs(f.compute(x,z) - d.compute(x,z)));
            }
            for (int i = 0; i < 64; i++) if (d.compute(30000000 + i, 30000000) != d.compute(30000001 + i, 30000000)) distinct++;
            System.out.printf("octaves=%d near_max_abs=%.9g far_adjacent_distinct=%d/64%n", octaves, max, distinct);
            if (distinct != 64 || max > 0.0001) throw new AssertionError();
        }
    }
}
