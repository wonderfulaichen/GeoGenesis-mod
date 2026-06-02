package com.geogenesis.worldgen.geology;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.util.RandomSource;

public class PlateTectonics {

    private final int seed;
    private final ImprovedNoise boundaryNoise;
    private final ImprovedNoise crustNoise;
    private final ImprovedNoise convergenceNoise;
    private final ImprovedNoise continentNoise;

    public PlateTectonics(int seed) {
        this.seed = seed;
        RandomSource rng = RandomSource.create(seed);
        rng.nextLong();
        this.boundaryNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.crustNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.convergenceNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.continentNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
    }

    public PlateData sample(float worldX, float worldZ) {
        float boundaryStrength = sampleBoundary(worldX, worldZ);
        float crustalThickness = sampleCrustalThickness(worldX, worldZ);
        float convergence = sampleConvergence(worldX, worldZ);
        float uplift = sampleUplift(worldX, worldZ, boundaryStrength, convergence, crustalThickness);
        float continentBias = sampleContinentBias(worldX, worldZ, boundaryStrength, convergence, crustalThickness);

        return new PlateData(boundaryStrength, crustalThickness, uplift, continentBias, convergence);
    }

    private float sampleBoundary(float wx, float wz) {
        float baseScale = 0.0006f;

        float ridgeSum = 0f;
        float maxAmp = 0f;
        float amp = 1f;
        float freq = 1f;

        for (int i = 0; i < 4; i++) {
            float yOff = seed * 0.0001f + i * 11.3f;
            float nx = wx * baseScale * freq;
            float nz = wz * baseScale * freq;
            float n = (float) boundaryNoise.noise(nx, yOff, nz);
            float ridge = 1f - Math.abs(n);
            ridgeSum += ridge * ridge * amp;
            maxAmp += amp;
            amp *= 0.45f;
            freq *= 2.2f;
        }

        float raw = ridgeSum / maxAmp;
        return Math.max(0f, Math.min(1f, raw));
    }

    private float sampleCrustalThickness(float wx, float wz) {
        float n1 = (float) crustNoise.noise(wx * 0.0005f + seed * 0.001f, 7.3f, wz * 0.0005f + seed * 0.002f);
        float n2 = (float) crustNoise.noise(wx * 0.0012f + 100, 13.7f, wz * 0.0012f + 200) * 0.5f;
        float n3 = (float) crustNoise.noise(wx * 0.0003f + 300, 23.1f, wz * 0.0003f + 400) * 0.3f;
        float combined = n1 + n2 + n3;
        combined = 1f / (1f + (float) Math.exp(-combined * 2.8f));
        return Math.max(0.08f, Math.min(1f, combined));
    }

    private float sampleConvergence(float wx, float wz) {
        float eps = 2f;
        float nCenter = (float) convergenceNoise.noise(wx * 0.0008f + seed * 0.01f, 31.7f, wz * 0.0008f);
        float nXp = (float) convergenceNoise.noise((wx + eps) * 0.0008f + seed * 0.01f, 31.7f, wz * 0.0008f);
        float nXm = (float) convergenceNoise.noise((wx - eps) * 0.0008f + seed * 0.01f, 31.7f, wz * 0.0008f);
        float nZp = (float) convergenceNoise.noise(wx * 0.0008f + seed * 0.01f, 31.7f, (wz + eps) * 0.0008f);
        float nZm = (float) convergenceNoise.noise(wx * 0.0008f + seed * 0.01f, 31.7f, (wz - eps) * 0.0008f);

        float gradX = (nXp - nXm) / (eps * 2f);
        float gradZ = (nZp - nZm) / (eps * 2f);
        float gradMag = (float) Math.sqrt(gradX * gradX + gradZ * gradZ);

        float convBase = Math.min(1f, gradMag * 15f);

        float directional = (float) convergenceNoise.noise(wx * 0.0015f + 500, 47.3f, wz * 0.0015f + 600);
        directional = (directional + 1f) * 0.5f;

        return Math.max(0f, Math.min(1f, convBase * 0.6f + directional * 0.35f));
    }

    private float sampleUplift(float wx, float wz, float boundaryStrength, float convergence, float crustalThickness) {
        if (boundaryStrength < 0.25f || convergence < 0.12f) return 0f;

        float bf = (boundaryStrength - 0.25f) / 0.75f;
        bf = bf * bf * (3f - 2f * bf);

        float cf = (convergence - 0.12f) / 0.88f;
        cf = cf * cf * (3f - 2f * cf);

        float upliftVar = ((float) boundaryNoise.noise(wx * 0.003f + 777, 91.3f, wz * 0.003f + 888) + 1f) * 0.5f;

        return bf * cf * crustalThickness * 0.55f * (0.15f + 0.85f * upliftVar);
    }

    private float sampleContinentBias(float wx, float wz, float boundaryStrength,
                                       float convergence, float crustalThickness) {
        float largeScale = (float) continentNoise.noise(wx * 0.0003f + seed * 0.05f, 0, wz * 0.0003f + seed * 0.07f);
        float mediumScale = (float) continentNoise.noise(wx * 0.0008f + 200, 0, wz * 0.0008f + 300) * 0.4f;
        float noisePart = (largeScale + mediumScale) * 0.25f;

        float boundaryPart = 0f;
        if (boundaryStrength > 0.28f && convergence > 0.12f) {
            float bf = (boundaryStrength - 0.28f) / 0.72f;
            boundaryPart = bf * convergence * 0.5f;
        }

        float crustPart = (crustalThickness - 0.35f) * 0.4f;
        crustPart = Math.max(-0.25f, Math.min(0.25f, crustPart));

        return noisePart + boundaryPart + crustPart;
    }

    public record PlateData(float boundaryStrength, float crustalThickness, float uplift,
                            float continentBias, float convergence) {}
}
