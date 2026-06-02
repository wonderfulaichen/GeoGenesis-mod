package com.geogenesis.worldgen;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.util.RandomSource;

public class NoiseEngine {

    private final int seed;
    
    private float terrainScale = 1.0f;
    private float noiseFrequencyScale = 1.0f;

    private final ImprovedNoise climateNoise;
    private final ImprovedNoise moistureNoise;
    private final ImprovedNoise continentNoise;
    private final ImprovedNoise warpLargeNoise;
    private final ImprovedNoise warpMediumNoise;
    private final ImprovedNoise fractalLarge;
    private final ImprovedNoise fractalMedium;
    private final ImprovedNoise fractalSmall;
    private final ImprovedNoise valleyLarge;
    private final ImprovedNoise valleySmall;
    private final ImprovedNoise ridgeNoise;
    private final ImprovedNoise ridgeNoise2;
    private final ImprovedNoise detailNoise;
    private final ImprovedNoise riverNoise;
    private final ImprovedNoise yOffsetNoise;

    public NoiseEngine(int seed) {
        this.seed = seed;
        RandomSource rng = RandomSource.create(seed);

        this.climateNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.moistureNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.continentNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.warpLargeNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.warpMediumNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.fractalLarge = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.fractalMedium = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.fractalSmall = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.valleyLarge = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.valleySmall = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.ridgeNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.ridgeNoise2 = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.detailNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.riverNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
        this.yOffsetNoise = new ImprovedNoise(RandomSource.create(rng.nextLong()));
    }
    
    public void setTerrainScale(float worldHeight, float baseHeight) {
        this.terrainScale = baseHeight / worldHeight;
        float targetBaseHeight = 256f;
        this.noiseFrequencyScale = (float) Math.sqrt(targetBaseHeight / baseHeight);
    }

    private float getYOffset(float x, float z) {
        // 使用三级动态偏移充分破坏噪声方向性相关，消除阶梯/条纹伪影
        float y1 = (float) yOffsetNoise.noise(x * 0.0001, 17.3, z * 0.0001) * 50f;
        float y2 = (float) yOffsetNoise.noise(x * 0.003 + 100, 31.7, z * 0.003 + 200) * 15f;
        float y3 = (float) yOffsetNoise.noise(x * 0.01 + 300, 53.9, z * 0.01 + 400) * 5f;
        return y1 + y2 + y3;
    }

    private float sampleFBM(ImprovedNoise noise, float x, float z, int octaves, float gain, float lacunarity) {
        float total = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue = 0;
        // 使用固定的种子偏移，而不是依赖 hashCode()
        float baseY = seed * 0.001f + (float) noise.noise(seed, 0, 0) * 0.01f;

        for (int i = 0; i < octaves; i++) {
            float y = baseY + getYOffset(x * frequency, z * frequency) + i * 1.5f;
            total += (float) noise.noise(x * frequency, y, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total / maxValue;
    }

    private float sampleFBMNonNorm(ImprovedNoise noise, float x, float z, int octaves, float gain, float lacunarity, float baseAmp) {
        float total = 0;
        float amplitude = baseAmp;
        float frequency = 1;
        // 使用固定的种子偏移，而不是依赖 hashCode()
        float baseY = seed * 0.001f + (float) noise.noise(seed, 0, 0) * 0.01f;

        for (int i = 0; i < octaves; i++) {
            float y = baseY + getYOffset(x * frequency, z * frequency) + i * 3.7f;
            total += (float) noise.noise(x * frequency, y, z * frequency) * amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total;
    }

    private float sampleOctaveNoise(ImprovedNoise noise, float x, float z, float xzScale, int firstOctave, float[] amplitudes) {
        float total = 0;
        // 使用固定的种子偏移，而不是依赖 hashCode()
        float baseY = seed * 0.001f + (float) noise.noise(seed, 0, 0) * 0.01f;
        for (int i = 0; i < amplitudes.length; i++) {
            float freq = (float) Math.pow(2, firstOctave + i) * xzScale;
            float y = baseY + getYOffset(x * freq, z * freq) + i * 3.7f;
            total += (float) noise.noise(x * freq, y, z * freq) * amplitudes[i];
        }
        return total;
    }

    private float[] domainWarp(float x, float z, float strength) {
        float wx = (float) warpLargeNoise.noise(x * 0.0005, 31.7, z * 0.0005) * strength;
        float wz = (float) warpLargeNoise.noise(x * 0.0005 + 500, 97.3, z * 0.0005 + 500) * strength;
        wx += (float) warpMediumNoise.noise(x * 0.002, 13.7, z * 0.002) * strength * 0.4f;
        wz += (float) warpMediumNoise.noise(x * 0.002 + 300, 57.9, z * 0.002 + 300) * strength * 0.4f;
        return new float[]{x + wx, z + wz};
    }

    public float sampleFractalLarge(float worldX, float worldZ) {
        float[] warped = domainWarp(worldX, worldZ, 60f);
        return sampleFBM(fractalLarge, warped[0], warped[1], 3, 0.5f, 2.15f);
    }

    public float sampleFractalMedium(float worldX, float worldZ) {
        float[] warped = domainWarp(worldX, worldZ, 30f);
        return sampleFBM(fractalMedium, warped[0], warped[1], 3, 0.6f, 2.2f);
    }

    public float sampleFractalSmall(float worldX, float worldZ) {
        return sampleFBM(fractalSmall, worldX, worldZ, 3, 0.7f, 2.25f);
    }

    public float sampleValleyLarge(float worldX, float worldZ) {
        float[] warped = domainWarp(worldX, worldZ, 20f);
        float n = sampleFBM(valleyLarge, warped[0] * 0.08f, warped[1] * 0.08f, 2, 0.5f, 2.0f);
        return Math.abs(n);
    }

    public float sampleValleySmall(float worldX, float worldZ) {
        float n = sampleFBM(valleySmall, worldX * 0.15f, worldZ * 0.15f, 2, 0.5f, 2.2f);
        return Math.abs(n);
    }

    private float sampleRidgeFBM(ImprovedNoise noise, float x, float z, int octaves, float gain, float lacunarity) {
        float total = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue = 0;
        // 使用固定的种子偏移，而不是依赖 hashCode()
        float baseY = seed * 0.001f + (float) noise.noise(seed, 0, 0) * 0.01f;

        for (int i = 0; i < octaves; i++) {
            float y = baseY + getYOffset(x * frequency, z * frequency) + i * 3.7f;
            float n = (float) noise.noise(x * frequency, y, z * frequency);
            float ridge = 1f - Math.abs(n);
            total += ridge * ridge * amplitude;
            maxValue += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total / maxValue;
    }

    // 放大尺度：firstOctave=-7(128→64格) + -6(64格)，避免地形过细
    private static final float[] AMP_LARGE  = {1f, 0.5f};                // firstOctave=-7, 2 oct
    private static final float[] AMP_MEDIUM = {1f};                       // firstOctave=-6, 1 oct
    private static final float AMP_LARGE_SUM  = 1f + 0.5f;                   // =1.5
    private static final float AMP_MEDIUM_SUM = 1f;                           // =1.0

    public float sampleTerrainBase(float worldX, float worldZ) {
        float[] warped = domainWarp(worldX, worldZ, 80f);
        float wx = warped[0], wz = warped[1];
        float large  = sampleOctaveNoise(fractalLarge,  wx, wz, 1f, -7, AMP_LARGE)  / AMP_LARGE_SUM;
        float medium = sampleOctaveNoise(fractalMedium, wx, wz, 1f, -6, AMP_MEDIUM) / AMP_MEDIUM_SUM;
        return ((large * 0.6f + medium * 0.4f) + 1f) * 0.5f;
    }
    
    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    public float sampleErosionEnhanced(float worldX, float worldZ) {
        float largeValley = sampleValleyLarge(worldX, worldZ);
        float smallValley = sampleValleySmall(worldX, worldZ);
        return largeValley * 0.3f + smallValley * 0.15f;
    }

    public float sampleRidge(float worldX, float worldZ) {
        float[] w1 = domainWarp(worldX, worldZ, 35f);
        float px = w1[0], pz = w1[1];

        float baseScale = 0.005f;
        float sx = px * baseScale, sz = pz * baseScale;

        return sampleRidgeFBM(ridgeNoise, sx, sz, 4, 0.5f, 2.15f);
    }

    public float sampleRidgeRaw(float worldX, float worldZ) {
        return sampleFractalMedium(worldX, worldZ);
    }

    public float sampleTerrainHills(float worldX, float worldZ) {
        float n = sampleFBM(fractalMedium, worldX * 0.004f, worldZ * 0.004f, 3, 0.55f, 2.1f);
        return (n + 1f) * 0.5f;
    }

    public float sampleTemperature(float worldX, float worldZ, float elevation) {
        float baseTemp = sampleFBM(climateNoise, worldX * 0.0012f, worldZ * 0.0012f, 2, 0.5f, 2.1f);
        baseTemp = (baseTemp + 1.0f) * 0.5f;
        float warpX = (float) warpLargeNoise.noise(worldX * 0.0008f, 13.7, worldZ * 0.0008f) * 800f;
        float warpZ = (float) warpLargeNoise.noise(worldX * 0.0008f + 100, 29.3, worldZ * 0.0008f + 100) * 800f;
        float warpedTemp = sampleFBM(climateNoise, (worldX + warpX) * 0.0012f, (worldZ + warpZ) * 0.0012f, 2, 0.5f, 2.1f);
        warpedTemp = (warpedTemp + 1.0f) * 0.5f;
        float elevationFactor = -elevation / 100f * 0.06f;
        float localVariance = sampleFBM(detailNoise, worldX * 0.02f, worldZ * 0.02f, 1, 0.5f, 2.2f) * 0.1f;
        return Math.max(0.0f, Math.min(1.0f, warpedTemp + elevationFactor + localVariance));
    }

    public float sampleMoisture(float worldX, float worldZ, float continentality, float temperature) {
        float baseMoisture = sampleFBM(moistureNoise, worldX * 0.0012f, worldZ * 0.0012f, 2, 0.5f, 2.1f);
        baseMoisture = (baseMoisture + 1.0f) * 0.5f;
        float oceanEffect = (1.0f - continentality) * 0.30f;
        float inlandDrying = continentality * -0.15f;
        float warmEffect = Math.max(0, temperature - 0.6f) * 0.10f;
        float coldDrying = Math.max(0, 0.5f - temperature) * -0.15f;
        return Math.max(0.0f, Math.min(1.0f, 0.40f + baseMoisture * 0.60f + oceanEffect + inlandDrying + warmEffect + coldDrying));
    }

    public float sampleContinentality(float worldX, float worldZ) {
        return (sampleContinentRaw(worldX, worldZ) + 1f) * 0.5f;
    }

    public float sampleContinentRaw(float worldX, float worldZ) {
        float fx = worldX * 0.0015f;
        float fz = worldZ * 0.0015f;

        // 第1层：fractalLarge 细调，strength=120
        float s1x = (float) fractalLarge.noise(fx * 0.8f + 100, 0, fz * 0.8f + 200) * 120f;
        float s1z = (float) fractalLarge.noise(fx * 0.8f + 300, 0, fz * 0.8f + 400) * 120f;

        // 第2层：strength=300
        float s2x = fx + s1x * 0.0015f;
        float s2z = fz + s1z * 0.0015f;
        float w2x = (float) fractalMedium.noise(s2x + 500, 0, s2z + 600) * 300f;
        float w2z = (float) fractalMedium.noise(s2x + 700, 0, s2z + 800) * 300f;

        // 第3层：strength=500
        float s3x = fx + w2x * 0.001f;
        float s3z = fz + w2z * 0.001f;
        float w3x = (float) warpLargeNoise.noise(s3x + 900, 0, s3z + 1000) * 500f;
        float w3z = (float) warpLargeNoise.noise(s3x + 1100, 0, s3z + 1200) * 500f;

        // 最终扭曲坐标
        float finalX = fx + w3x * 0.0008f;
        float finalZ = fz + w3z * 0.0008f;

        // 3 层 FBM：分形海岸线
        float baseY = seed * 0.0003f;
        float continent = 0f, maxAmp = 0f;
        float amp = 1f, freq = 1f;
        for (int i = 0; i < 3; i++) {
            float yOff = baseY + i * 7.3f;
            continent += (float) continentNoise.noise(finalX * freq, yOff, finalZ * freq) * amp;
            maxAmp += amp;
            amp *= 0.45f;
            freq *= 2.3f;
        }
        continent /= maxAmp;

        // 海岸线脊状细节（只在 -0.3 < continent < 0.3 的过渡带显著）
        float coastWeight = 1f - Math.abs(continent) * 4f;
        coastWeight = Math.max(0f, coastWeight);
        if (coastWeight > 0.01f) {
            float ridgeSum = 0f, rMax = 0f;
            float rAmp = 1f, rFreq = 3f;
            for (int i = 0; i < 3; i++) {
                float rn = (float) fractalSmall.noise(finalX * rFreq + i * 50, 13.7f + i * 3, finalZ * rFreq + i * 100);
                float ridge = 1f - Math.abs(rn);
                ridgeSum += ridge * ridge * rAmp;
                rMax += rAmp;
                rAmp *= 0.5f;
                rFreq *= 2.1f;
            }
            float ridgeVal = ridgeSum / rMax;
            continent += (ridgeVal - 0.5f) * coastWeight * Math.signum(continent) * 0.12f;
        }

        // 适度陡峭的 Sigmoid（比之前略陡产生清晰海岸线，但保留陆架过渡带）
        float sigmoid = 1f / (1f + (float) Math.exp(-continent * 3.0f));
        return Math.max(-1f, Math.min(1f, sigmoid * 2f - 1f));
    }

    public float sampleErosionRaw(float worldX, float worldZ) {
        return sampleFBM(detailNoise, worldX * 0.001f, worldZ * 0.001f, 3, 0.5f, 2.1f);
    }

    public float sampleRiver(float worldX, float worldZ) {
        float r = sampleFBM(riverNoise, worldX * 0.002f, worldZ * 0.002f, 2, 0.5f, 2.1f);
        return (r + 1.0f) * 0.5f;
    }

    public float sampleHydraulicErosion(float worldX, float worldZ, float temperature, float moisture) {
        float baseErosion = sampleFBM(detailNoise, worldX * 0.002f, worldZ * 0.002f, 2, 0.5f, 2.2f);
        baseErosion = (baseErosion + 1.0f) * 0.5f;
        float climateFactor = temperature * moisture;
        return Math.max(0.0f, Math.min(1.0f, baseErosion * 0.3f + climateFactor * 0.7f));
    }

    public float sampleWindErosion(float worldX, float worldZ, float temperature, float moisture) {
        if (temperature < 0.5f || moisture > 0.3f) return 0.0f;
        float windStrength = sampleFBM(detailNoise, worldX * 0.01f + 1000, worldZ * 0.01f + 1000, 1, 0.5f, 2.2f);
        windStrength = (windStrength + 1.0f) * 0.5f;
        float aridity = (1.0f - moisture) * temperature;
        return windStrength * aridity;
    }

    public float sampleElevation(float worldX, float worldZ) {
        float e = sampleFBM(detailNoise, worldX * 0.004f, worldZ * 0.004f, 3, 0.5f, 2.1f);
        return (e + 1.0f) * 0.5f;
    }

    public float sampleTerrainBaseGradient(float worldX, float worldZ) {
        float step = 4f;
        float hE = sampleTerrainBase(worldX + step, worldZ);
        float hW = sampleTerrainBase(worldX - step, worldZ);
        float hN = sampleTerrainBase(worldX, worldZ + step);
        float hS = sampleTerrainBase(worldX, worldZ - step);
        float dx = Math.abs((hE - hW) / (step * 2));
        float dz = Math.abs((hN - hS) / (step * 2));
        return Math.min(1f, (dx + dz) * 2f);
    }

    public float sampleTerrainDetail(float worldX, float worldZ) {
        return sampleFBM(detailNoise, worldX * 0.015f, worldZ * 0.015f, 2, 0.5f, 2.2f);
    }

    /** 高原权重 [0,1]：广域噪声识别高原区域（周期~500格） */
    public float samplePlateauWeight(float worldX, float worldZ) {
        float n = sampleFBM(fractalLarge, worldX * 0.002f, worldZ * 0.002f, 2, 0.5f, 2.0f);
        return (n + 1f) * 0.5f;
    }

    /** 喀斯特权重 [0,1]：热带湿润区的峰林/石柱分布 */
    public float sampleKarstWeight(float worldX, float worldZ) {
        float n = sampleFBM(fractalMedium, worldX * 0.003f + 777, worldZ * 0.003f + 777, 2, 0.5f, 2.0f);
        return (n + 1f) * 0.5f;
    }

    /** 丹霞权重 [0,1]：干旱区红色阶梯崖壁分布 */
    public float sampleDanxiaWeight(float worldX, float worldZ) {
        float n = sampleFBM(fractalSmall, worldX * 0.004f + 111, worldZ * 0.004f + 222, 2, 0.5f, 2.0f);
        return (n + 1f) * 0.5f;
    }

    /** 冰川权重 [0,1]：高海拔寒冷区U形谷分布 */
    public float sampleGlacierWeight(float worldX, float worldZ) {
        float n = sampleFBM(fractalMedium, worldX * 0.003f + 333, worldZ * 0.003f + 444, 2, 0.5f, 2.0f);
        return (n + 1f) * 0.5f;
    }

    public float sampleErosionDetail(float worldX, float worldZ) {
        float erosionA = sampleFBM(detailNoise, worldX * 0.006f + 100, worldZ * 0.006f + 100, 2, 0.5f, 2.2f);
        float erosionB = sampleFBM(fractalMedium, worldX * 0.002f + 200, worldZ * 0.002f + 200, 2, 0.5f, 2.1f);
        float erosionC = sampleFBM(fractalSmall, worldX * 0.001f + 300, worldZ * 0.001f + 300, 2, 0.5f, 2.1f);
        return erosionA * 0.5f + erosionB * 0.35f + erosionC * 0.15f;
    }

    public float temperatureNoise(float worldX, float worldZ) { return sampleTemperature(worldX, worldZ, 0); }
    public float moistureNoise(float worldX, float worldZ) { return sampleMoisture(worldX, worldZ, 0.5f, 0.5f); }
    public float erosionNoise(float worldX, float worldZ) { return sampleHydraulicErosion(worldX, worldZ, 0.5f, 0.5f); }
    public float continentNoise(float worldX, float worldZ) { return sampleContinentality(worldX, worldZ); }
    public float heightNoise(float worldX, float worldZ) { return sampleElevation(worldX, worldZ); }
    public float detailNoise(float worldX, float worldZ) { float d = sampleFBM(detailNoise, worldX * 0.04f, worldZ * 0.04f, 2, 0.5f, 2.2f); return d * 0.3f; }
    public float ridgeNoise(float worldX, float worldZ) { return sampleRidge(worldX, worldZ); }
    public int getSeed() { return seed; }
    public float noise(float x, float z) { return sampleFBM(fractalMedium, x * 0.002f, z * 0.002f, 2, 0.5f, 2.1f); }

    public float sampleTerrainBaseClean(float worldX, float worldZ) { return sampleTerrainBase(worldX, worldZ); }
    public float sampleTerrainBaseLowAmp(float worldX, float worldZ) { return sampleTerrainBase(worldX, worldZ); }
    public float sampleTerrainBaseGradientAbs(float worldX, float worldZ) { return sampleErosionEnhanced(worldX, worldZ); }
    public float sampleCellNoise(float worldX, float worldZ) {
        float[] warped = domainWarp(worldX, worldZ, 30f);
        float wx = warped[0] * 0.02f;
        float wz = warped[1] * 0.02f;
        float total = 0f, maxValue = 0f;
        float amp = 1f, freq = 1f;
        float baseY = seed * 0.001f + (float) ridgeNoise.noise(seed, 0, 0) * 0.01f;
        for (int i = 0; i < 3; i++) {
            float y = baseY + getYOffset(wx * freq, wz * freq) + i * 3.7f;
            float n1 = (float) ridgeNoise.noise(wx * freq, y, wz * freq);
            float n2 = (float) ridgeNoise2.noise(wx * freq + 500, y + 100, wz * freq + 500);
            float cell = (Math.abs(n1) + Math.abs(n2)) * 0.5f;
            total += (1f - cell) * amp;
            maxValue += amp;
            amp *= 0.5f;
            freq *= 2.1f;
        }
        return Math.max(0f, Math.min(1f, total / maxValue));
    }

    private float[] sampleGullies(float x, float z, float slopeX, float slopeZ) {
        float sqrLen = slopeX * slopeX + slopeZ * slopeZ;
        if (sqrLen < 0.000001f) return new float[]{0, 0, 0};
        float sideX = -slopeZ * 6.28318f;
        float sideZ = slopeX * 6.28318f;

        float heightSum = 0, weightSum = 0;
        float slopeXSum = 0, slopeZSum = 0;

        float cellSize = 1.0f;
        int ix = (int) Math.floor(x / cellSize), iz = (int) Math.floor(z / cellSize);
        float fx = (x - ix * cellSize) / cellSize, fz = (z - iz * cellSize) / cellSize;

        for (int i = -1; i <= 2; i++) {
            for (int j = -1; j <= 2; j++) {
                int cx = ix + i, cz = iz + j;
                float rx = (sampleHashNoise(cx, cz, 0) - 0.5f) * 0.8f;
                float rz = (sampleHashNoise(cx, cz, 1) - 0.5f) * 0.8f;
                float dx = fx - i - rx, dz = fz - j - rz;
                float sqrDist = dx * dx + dz * dz;
                float weight = Math.max(0f, (float) Math.exp(-sqrDist * 2.0) - 0.01111f);
                weightSum += weight;
                float wave = dx * sideX + dz * sideZ;
                heightSum += (float) Math.cos(wave) * weight;
                slopeXSum += (float) -Math.sin(wave) * sideX * weight;
                slopeZSum += (float) -Math.sin(wave) * sideZ * weight;
            }
        }
        if (weightSum <= 0) return new float[]{0, 0, 0};
        return new float[]{heightSum / weightSum, slopeXSum / weightSum, slopeZSum / weightSum};
    }

    private float sampleHashNoise(int x, int z, int index) {
        float nx = x * 0.137f + index * 0.731f + seed * 0.001f;
        float nz = z * 0.149f + index * 0.557f + seed * 0.001f;
        float n = (float) detailNoise.noise(nx, 0, nz);
        return (n + 1f) * 0.5f;
    }

    public float sampleGullyErosion(float worldX, float worldZ) {
        float n1 = sampleFBM(detailNoise, worldX * 0.04f, worldZ * 0.04f, 2, 0.5f, 2.2f);
        float n2 = sampleFBM(detailNoise, worldX * 0.08f + 500, worldZ * 0.08f + 500, 2, 0.5f, 2.2f);
        float gully = (Math.abs(n1) + Math.abs(n2)) * 0.5f;
        return (1f - gully) * 0.3f;
    }

    /** 连续噪声侵蚀：多尺度噪声叠加 + 地形斜率加权，完全无断裂 */
    public float sampleErosionNoise(float worldX, float worldZ) {
        // 大尺度（~100格）：决定侵蚀区域分布
        float large = sampleFBM(detailNoise, worldX * 0.003f + 50, worldZ * 0.003f + 50, 2, 0.5f, 2.1f);
        // 中尺度（~40格）：河谷侵蚀
        float medium = sampleFBM(fractalMedium, worldX * 0.008f + 100, worldZ * 0.008f + 100, 2, 0.5f, 2.2f);
        // 小尺度（~15格）：细沟侵蚀
        float small = sampleFBM(detailNoise, worldX * 0.02f + 200, worldZ * 0.02f + 200, 2, 0.5f, 2.2f);

        float largeN = (large + 1f) * 0.5f;
        float mediumN = Math.abs(medium);
        float smallN = Math.abs(small);

        // 河谷图案：medium的abs形成V形谷
        float valley = mediumN * 0.5f + largeN * 0.3f;
        // 细沟侵蚀：small的abs形成坡面冲沟
        float gully = smallN * 0.5f;

        // 山坡斜率加权侵蚀
        float step = 6f;
        float hE = sampleTerrainBase(worldX + step, worldZ);
        float hW = sampleTerrainBase(worldX - step, worldZ);
        float hN = sampleTerrainBase(worldX, worldZ + step);
        float hS = sampleTerrainBase(worldX, worldZ - step);
        float slope = (Math.abs(hE - hW) + Math.abs(hN - hS)) * 0.5f;
        float slopeWeight = Math.min(1f, slope * 4f);

        // 侵蚀量 = 河谷侵蚀 + 坡面加权冲沟侵蚀
        float erosion = valley * 0.12f + gully * 0.08f * slopeWeight;
        return Math.max(0f, Math.min(0.3f, erosion));
    }

    /**
     * Clean Terrain Erosion Filter — analytical gully generation along slope direction.
     * Fully deterministic, no boundary seams. Based on Rune Skovbo Johansen's refactored shader.
     *
     * @param heights      heightmap [size][size], normalized [0,1], modified in place
     * @param worldStartX  world block X of pixel (0,0)
     * @param worldStartZ  world block Z of pixel (0,0)
     * @param pxToWorld    world blocks per pixel (1.0 = native res, 0.5 = 2x upsample)
     * @param scale        erosionScale in world blocks (mountain width / 5~10)
     * @param strength     gully depth, reference uses 0.16
     * @param slopePower   ridge sharpness (0.5=sharp, 1.0=round), reference uses 0.6
     * @param cellScale    cell size relative to scale, reference uses 1.0
     * @param octaves      iteration count, reference uses 5
     * @param gain         amplitude decay per octave, reference uses 0.5
     * @param lacunarity   frequency increase per octave, reference uses 2.0
     * @param heightOffset overall height adjustment (-1=lower only, reference uses -0.5)
     */
    public void applyErosionFilter(float[][] heights, int worldStartX, int worldStartZ,
                                    float pxToWorld,
                                    float scale, float strength,
                                    float slopePower, float cellScale, int octaves,
                                    float gain, float lacunarity, float heightOffset) {
        int size = heights.length;
        // freq = 1/(erosionScale * cellScale) — matching reference implementation
        float freq0 = 1f / (scale * cellScale);
        float amp = strength * scale * (float) Math.pow(gain, 0);
        for (int o = 0; o < octaves; o++) {
            float freq = freq0 * (float) Math.pow(lacunarity, o);
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    float sx = heights[Math.min(z + 1, size - 1)][x] - heights[Math.max(z - 1, 0)][x];
                    float sz = heights[z][Math.min(x + 1, size - 1)] - heights[z][Math.max(x - 1, 0)];
                    float sqrLen = sx * sx + sz * sz;
                    if (sqrLen > 0.000001f) {
                        float len = (float) Math.sqrt(sqrLen);
                        float newLen = (float) Math.pow(len, slopePower);
                        sx *= newLen / len; sz *= newLen / len;
                    }
                    // World-space position: pixel center in block coordinates
                    float wx = worldStartX + x * pxToWorld;
                    float wz = worldStartZ + z * pxToWorld;
                    // Call Gullies: pos * freq, slope * cellScale — matching reference
                    float[] gully = sampleGullies(wx * freq, wz * freq,
                                                   sx * cellScale, sz * cellScale);
                    heights[z][x] += gully[0] * amp;
                }
            }
            amp *= gain;
        }
        float erosionMagnitude = strength * scale * magnitudeSum(octaves, gain);
        float offset = erosionMagnitude * heightOffset;
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                heights[z][x] = (float) Math.max(0f, Math.min(1f, heights[z][x] + offset));
    }

    private float sampleSlopeX(float[][] h, int size, int x, int z) {
        float dx;
        if (x <= 0) dx = h[z][1] - h[z][0];
        else if (x >= size - 1) dx = h[z][size - 1] - h[z][size - 2];
        else dx = (h[z][x + 1] - h[z][x - 1]) * 0.5f;
        return dx * (size - 1);
    }

    private float sampleSlopeZ(float[][] h, int size, int x, int z) {
        float dz;
        if (z <= 0) dz = h[1][x] - h[0][x];
        else if (z >= size - 1) dz = h[size - 1][x] - h[size - 2][x];
        else dz = (h[z + 1][x] - h[z - 1][x]) * 0.5f;
        return dz * (size - 1);
    }

    private static float magnitudeSum(int octaves, float gain) {
        return (1.0f - (float) Math.pow(gain, octaves)) / (1.0f - gain);
    }

    public ImprovedNoise climateNoise() { return climateNoise; }
    public ImprovedNoise ridgeNoise() { return ridgeNoise; }
    public ImprovedNoise continentNoise() { return continentNoise; }
    public ImprovedNoise detailNoise() { return detailNoise; }

    // ===== 火山地形采样（借鉴 TerraForged VolcanoPopulator） =====

    /**
     * 采样火山高度修正值。
     * 使用 cellular noise 散布火山中心点，每个点产生锥形高度。
     * 返回 [0,1]：0=无火山影响，>0=火山锥高度贡献。
     * 
     * 算法：
     * 1. 将世界坐标映射到网格（scale~800格/格）
     * 2. 对周围 3×3 网格点，用 hash 生成 jitter 偏移（cellular noise）
     * 3. 计算到最近火山中心的距离
     * 4. 距离→锥形高度：外坡(alpha²) + 火山口凹陷(alpha>inversionPoint)
     */
    public float sampleVolcanoHeight(float worldX, float worldZ) {
        float scale = 800f; // 火山间距（世界单位）
        float density = 0.15f; // 火山密度（0-1，越小越稀疏）
        float inversionPoint = 0.85f; // 火山口开始凹陷的位置
        float craterDepth = 0.2f; // 火山口凹陷深度系数

        float fx = worldX / scale;
        float fz = worldZ / scale;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);

        float minDist = Float.MAX_VALUE;
        float secondDist = Float.MAX_VALUE;

        // 遍历 3×3 邻域寻找最近的火山中心
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long hash = mixHash(seed, cx, cz);

                // 密度过滤：只有部分网格点产生火山
                if (randFromHash(hash, 6869) > density) continue;

                // Cellular noise：jitter 偏移
                float jx = cx + (float) randFromHash(hash, 12343);
                float jz = cz + (float) randFromHash(hash, 16477);

                float distSq = (fx - jx) * (fx - jx) + (fz - jz) * (fz - jz);
                if (distSq < minDist) {
                    secondDist = minDist;
                    minDist = distSq;
                } else if (distSq < secondDist) {
                    secondDist = distSq;
                }
            }
        }

        float dist = (float) Math.sqrt(minDist);
        float edge = (float) Math.sqrt(secondDist) - dist;
        if (edge < 0.001f) return 0f; // 边界外

        // 锥形高度：alpha = 1 - dist（中心=1，边缘=0）
        float alpha = Math.max(0f, 1f - dist * 1.5f); // 1.5 控制锥体半径
        if (alpha <= 0f) return 0f;

        float height;
        if (alpha > inversionPoint) {
            // 火山口凹陷：从锥顶向下凹陷
            float craterAlpha = (alpha - inversionPoint) / (1f - inversionPoint);
            float peakHeight = inversionPoint * inversionPoint; // 外坡曲线在 inversionPoint 处的高度
            height = peakHeight - craterDepth * craterAlpha * peakHeight;
        } else {
            // 外坡：alpha² 曲线（陡峭的火山锥）
            height = alpha * alpha;
        }

        // 边缘衰减：避免火山之间硬边
        float edgeFade = Math.min(1f, edge * 5f);
        return height * edgeFade;
    }

    /** 火山权重 [0,1]：识别火山区域（与 samplePlateauWeight 类似的广域噪声） */
    public float sampleVolcanoWeight(float worldX, float worldZ) {
        float n = sampleFBM(fractalLarge, worldX * 0.001f + 555, worldZ * 0.001f + 666, 2, 0.5f, 2.0f);
        return (n + 1f) * 0.5f;
    }

    // ===== Hash 工具方法（用于 cellular noise 火山散布） =====

    private static long mixHash(long seed, int x, int z) {
        long h = seed + x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    private static double randFromHash(long hash, int offset) {
        long h = mixHash(hash + offset, 0, 0);
        return (h & 0x1fffffffffffffL) * 0x1.0p-53; // [0, 1)
    }
}
