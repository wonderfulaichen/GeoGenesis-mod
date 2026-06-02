package com.geogenesis.worldgen.climate;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.util.RandomSource;

/**
 * 真实地理气候系统
 *
 * 核心设计：
 * 1. 洋流系统 - 影响沿海气候（暖流=湿润，寒流=干燥）
 * 2. 季风系统 - 季节性风向影响降水
 * 3. 地形雨 - 迎风坡湿润，背风坡干燥
 * 4. 海拔修正 - 海拔越高越冷
 * 5. 纬度效应 - 由噪声模拟，非固定纬度
 */
public class ClimateSystem {

    private final int seed;

    // 基础气候噪声
    private final ImprovedNoise temperatureNoise;
    private final ImprovedNoise moistureNoise;
    private final ImprovedNoise continentNoise;
    private final ImprovedNoise oceanCurrentNoise;  // 洋流
    private final ImprovedNoise monsoonNoise;       // 季风
    private final ImprovedNoise elevationNoise;

    // 域扭曲
    private final ImprovedNoise warpXNoise;
    private final ImprovedNoise warpZNoise;

    // Y轴偏移噪声（打破条纹）
    private final ImprovedNoise yOffsetNoise;

    public ClimateSystem(int seed) {
        this.seed = seed;

        this.temperatureNoise = createNoise(seed);
        this.moistureNoise = createNoise(seed + 1);
        this.continentNoise = createNoise(seed + 2);
        this.oceanCurrentNoise = createNoise(seed + 3);
        this.monsoonNoise = createNoise(seed + 4);
        this.elevationNoise = createNoise(seed + 5);

        this.warpXNoise = createNoise(seed + 10);
        this.warpZNoise = createNoise(seed + 11);

        this.yOffsetNoise = createNoise(seed + 99);
    }

    private ImprovedNoise createNoise(int seed) {
        return new ImprovedNoise(RandomSource.create(seed));
    }

    /**
     * 计算Y轴偏移值，打破XZ平面的条纹伪影
     */
    private float getYOffset(float x, float z) {
        return (float) yOffsetNoise.noise(x * 0.0001, 17.3, z * 0.0001) * 10f;
    }

    // ===== FBM采样（防条纹优化） =====

    private float sampleFBM(ImprovedNoise noise, float x, float z, int octaves, float gain, float lacunarity) {
        float total = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue = 0;

        // 使用固定的种子偏移，而不是依赖 hashCode()
        float baseY = seed * 0.001f + (float) noise.noise(seed, 0, 0) * 0.01f;

        for (int i = 0; i < octaves; i++) {
            // Y轴使用动态偏移，避免所有八度在Y=0平面采样
            float y = baseY + getYOffset(x * frequency, z * frequency) + i * 3.7f;
            total += (float) noise.noise(x * frequency, y, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }

        return total / maxValue;
    }

    // ===== 域扭曲 =====

    private float[] warpCoordinates(float x, float z) {
        float warpX = (float) warpXNoise.noise(x * 0.001f, 13.7, z * 0.001f) * 1000f;
        float warpZ = (float) warpZNoise.noise(x * 0.001f + 100, 29.3, z * 0.001f + 100) * 1000f;
        return new float[]{x + warpX, z + warpZ};
    }

    // ===== 大陆性采样 =====

    /**
     * 采样大陆性 [0, 1]
     * 0 = 深海, 1 = 内陆
     */
    public float sampleContinentality(float worldX, float worldZ) {
        float[] warped = warpCoordinates(worldX, worldZ);
        // 提高频率：0.0005→0.001，大陆尺寸减半（~1000格周期），更容易找到陆地
        float c = sampleFBM(continentNoise, warped[0] * 0.001f, warped[1] * 0.001f, 2, 0.5f, 2.1f);
        return (c + 1.0f) * 0.5f;
    }

    // ===== 温度采样 =====

    /**
     * 采样温度 [0, 1]
     * 受海拔、洋流、纬度（噪声模拟）影响
     */
    public float sampleTemperature(float worldX, float worldZ, float elevation) {
        // 基础温度（噪声模拟"纬度"）
        float[] warped = warpCoordinates(worldX, worldZ);
        float baseTemp = sampleFBM(temperatureNoise, warped[0] * 0.0005f, warped[1] * 0.0005f, 2, 0.5f, 2.1f);
        baseTemp = (baseTemp + 1.0f) * 0.5f;

        // 海拔修正（每100格降0.6°C）
        float elevationFactor = -elevation / 100f * 0.06f;

        // 洋流影响
        float oceanEffect = sampleOceanCurrentEffect(worldX, worldZ);

        // 局部变化
        float localVariance = sampleFBM(temperatureNoise, worldX * 0.02f, worldZ * 0.02f, 1, 0.5f, 2.1f) * 0.05f;

        return Math.max(0.0f, Math.min(1.0f, baseTemp + elevationFactor + oceanEffect + localVariance));
    }

    // ===== 湿度采样 =====

    /**
     * 采样湿度 [0, 1]
     * 受海陆位置、地形雨、季风影响
     */
    public float sampleMoisture(float worldX, float worldZ, float continentality, float elevation, float temperature) {
        // 基础湿度
        float[] warped = warpCoordinates(worldX, worldZ);
        float baseMoisture = sampleFBM(moistureNoise, warped[0] * 0.0006f, warped[1] * 0.0006f, 2, 0.5f, 2.15f);
        baseMoisture = (baseMoisture + 1.0f) * 0.5f;

        // 海洋影响：沿海更湿润
        float oceanEffect = (1.0f - continentality) * 0.4f;

        // 季风影响
        float monsoonEffect = sampleMonsoonEffect(worldX, worldZ, continentality);

        // 地形雨：高海拔迎风坡更湿润（简化模拟）
        float orographicEffect = elevation * 0.2f;

        // 温度影响：热带蒸发强，可能更湿润
        float tempEffect = temperature * 0.1f;

        return Math.max(0.0f, Math.min(1.0f, baseMoisture + oceanEffect + monsoonEffect + orographicEffect + tempEffect));
    }

    // ===== 洋流影响 =====

    /**
     * 洋流对温度的影响
     * 暖流 = +温度, 寒流 = -温度
     */
    private float sampleOceanCurrentEffect(float worldX, float worldZ) {
        // 只在沿海地区生效
        float continentality = sampleContinentality(worldX, worldZ);
        if (continentality > 0.6f) return 0.0f;  // 内陆无影响

        float current = sampleFBM(oceanCurrentNoise, worldX * 0.002f, worldZ * 0.002f, 2, 0.5f, 2.1f);
        // 洋流强度随距离海岸衰减
        float coastalFactor = 1.0f - continentality / 0.6f;

        return current * 0.15f * coastalFactor;
    }

    // ===== 季风影响 =====

    /**
     * 季风对湿度的影响
     * 季节性风向带来降水
     */
    private float sampleMonsoonEffect(float worldX, float worldZ, float continentality) {
        // 季风主要影响大陆东岸
        if (continentality < 0.3f || continentality > 0.8f) return 0.0f;

        float monsoon = sampleFBM(monsoonNoise, worldX * 0.002f, worldZ * 0.002f, 2, 0.5f, 2.1f);
        return monsoon * 0.2f;
    }

    // ===== 海拔采样 =====

    public float sampleElevation(float worldX, float worldZ) {
        float e = sampleFBM(elevationNoise, worldX * 0.004f, worldZ * 0.004f, 4, 0.5f, 2.1f);
        return (e + 1.0f) * 0.5f;
    }

    // ===== 混合侵蚀系统（权重归一化）=====

    /**
     * 侵蚀类型枚举
     */
    public enum ErosionType {
        HYDRAULIC,      // 水力侵蚀
        WIND,           // 风蚀
        GLACIAL,        // 冰川侵蚀
        THERMAL,        // 热侵蚀
        COASTAL,        // 海岸侵蚀
        NONE            // 无侵蚀
    }

    /**
     * 侵蚀结果
     * weights: 各侵蚀类型权重（之和=1.0，表示侵蚀类型占比）
     * totalStrength: 总侵蚀强度 [0,1]，表示侵蚀有多强
     */
    public static class ErosionResult {
        public final float hydraulicWeight;    // 水力侵蚀权重（类型占比）
        public final float windWeight;         // 风蚀权重
        public final float glacialWeight;      // 冰川侵蚀权重
        public final float thermalWeight;      // 热侵蚀权重
        public final float coastalWeight;      // 海岸侵蚀权重
        public final float totalStrength;      // 总侵蚀强度 [0,1]

        public ErosionResult(float hydraulicWeight, float windWeight, float glacialWeight,
                             float thermalWeight, float coastalWeight, float totalStrength) {
            this.hydraulicWeight = hydraulicWeight;
            this.windWeight = windWeight;
            this.glacialWeight = glacialWeight;
            this.thermalWeight = thermalWeight;
            this.coastalWeight = coastalWeight;
            this.totalStrength = totalStrength;
        }

        public ErosionType getDominantType() {
            float max = hydraulicWeight;
            ErosionType dominant = ErosionType.HYDRAULIC;
            if (windWeight > max) { max = windWeight; dominant = ErosionType.WIND; }
            if (glacialWeight > max) { max = glacialWeight; dominant = ErosionType.GLACIAL; }
            if (thermalWeight > max) { max = thermalWeight; dominant = ErosionType.THERMAL; }
            if (coastalWeight > max) { max = coastalWeight; dominant = ErosionType.COASTAL; }
            return dominant;
        }
    }

    /**
     * 计算混合侵蚀（权重归一化）
     * 所有侵蚀权重之和 = 1.0
     */
    public ErosionResult calculateErosion(float temperature, float moisture, float elevation, float continentality) {
        // 计算各种侵蚀的原始强度
        float hydraulicStrength = calculateHydraulicErosion(temperature, moisture, elevation);
        float windStrength = calculateWindErosion(temperature, moisture);
        float glacialStrength = calculateGlacialErosion(temperature, elevation);
        float thermalStrength = calculateThermalErosion(temperature, elevation);
        float coastalStrength = calculateCoastalErosion(continentality, elevation);

        // 总侵蚀强度（不归一化，保留实际强度）
        float totalStrength = Math.min(1.0f, hydraulicStrength + windStrength + glacialStrength + thermalStrength + coastalStrength);

        // 归一化权重（权重之和 = 1.0，表示侵蚀类型占比）
        float weightSum = hydraulicStrength + windStrength + glacialStrength + thermalStrength + coastalStrength;
        float hWeight = 0, wWeight = 0, gWeight = 0, tWeight = 0, cWeight = 0;
        if (weightSum > 0) {
            hWeight = hydraulicStrength / weightSum;
            wWeight = windStrength / weightSum;
            gWeight = glacialStrength / weightSum;
            tWeight = thermalStrength / weightSum;
            cWeight = coastalStrength / weightSum;
        }

        return new ErosionResult(hWeight, wWeight, gWeight, tWeight, cWeight, totalStrength);
    }

    // ===== 各种侵蚀的计算 =====

    /**
     * 水力侵蚀强度 [0, 1]
     * 高温+高湿+高海拔 = 强侵蚀
     */
    private float calculateHydraulicErosion(float temperature, float moisture, float elevation) {
        float waterFactor = moisture;
        float tempFactor = temperature;
        float elevationFactor = elevation * 0.3f;
        return Math.min(1.0f, waterFactor * tempFactor * 0.7f + elevationFactor * 0.3f);
    }

    /**
     * 风蚀强度 [0, 1]
     * 高温+干旱 = 强风蚀
     */
    private float calculateWindErosion(float temperature, float moisture) {
        if (temperature < 0.5f || moisture > 0.3f) return 0.0f;
        float aridity = (1.0f - moisture) * temperature;
        return aridity * 0.5f;
    }

    /**
     * 冰川侵蚀强度 [0, 1]
     * 低温+高海拔 = 冰川侵蚀
     */
    private float calculateGlacialErosion(float temperature, float elevation) {
        if (temperature > 0.3f || elevation < 0.6f) return 0.0f;
        float coldFactor = 1.0f - temperature;
        float heightFactor = elevation;
        return coldFactor * heightFactor * 0.4f;
    }

    /**
     * 热侵蚀强度 [0, 1]
     * 昼夜温差大的地区（沙漠、高原）
     */
    private float calculateThermalErosion(float temperature, float elevation) {
        // 热侵蚀在昼夜温差大的地区更强
        float dayNightDiff = temperature * (1.0f - temperature);  // 温差最大在中等温度
        float elevationFactor = elevation * 0.2f;
        return dayNightDiff * 0.3f + elevationFactor * 0.1f;
    }

    /**
     * 海岸侵蚀强度 [0, 1]
     * 沿海地区受海浪侵蚀
     */
    private float calculateCoastalErosion(float continentality, float elevation) {
        // 只在沿海地区
        if (continentality > 0.5f) return 0.0f;
        float coastalFactor = 1.0f - continentality / 0.5f;
        // 低海拔海岸侵蚀更强
        float elevationFactor = 1.0f - elevation;
        return coastalFactor * elevationFactor * 0.4f;
    }

    // ===== 地形类型判断 =====

    /**
     * 根据气候判断地形类型
     */
    public TerrainType getTerrainType(float temperature, float moisture, float elevation, float erosion) {
        // 高山
        if (elevation > 0.8f) {
            if (temperature < 0.2f) return TerrainType.GLACIER;
            return TerrainType.ALPINE;
        }

        // 丘陵/山地
        if (elevation > 0.5f) {
            if (moisture > 0.6f) return TerrainType.FOREST_HILL;
            if (moisture < 0.3f) return TerrainType.DRY_HILL;
            return TerrainType.HILL;
        }

        // 平原
        if (elevation > 0.2f) {
            if (moisture > 0.7f) return TerrainType.WETLAND;
            if (moisture < 0.2f) return TerrainType.DESERT;
            if (temperature > 0.7f && moisture > 0.5f) return TerrainType.RAINFOREST;
            if (temperature < 0.3f) return TerrainType.TUNDRA;
            return TerrainType.PLAINS;
        }

        // 低地/海岸
        if (moisture > 0.5f) return TerrainType.COASTAL;
        return TerrainType.BEACH;
    }

    public enum TerrainType {
        GLACIER, ALPINE, FOREST_HILL, DRY_HILL, HILL,
        WETLAND, DESERT, RAINFOREST, TUNDRA, PLAINS,
        COASTAL, BEACH, DEEP_OCEAN, SHALLOW_OCEAN
    }

    // ===== 特殊地形特征权重（用于群系选择）=====

    /**
     * 冰川特征权重 [0,1]
     * 高值 → 适合 FROZEN_PEAKS / JAGGED_PEAKS / SNOWY_SLOPES 群系
     */
    public float sampleGlacierFeature(float worldX, float worldZ) {
        float elevation = sampleElevation(worldX, worldZ);
        float temp = sampleTemperature(worldX, worldZ, 0);
        if (elevation < 0.5f || temp > 0.4f) return 0f;
        float glacierNoise = sampleFBM(monsoonNoise, worldX * 0.003f, worldZ * 0.003f, 2, 0.5f, 2.1f);
        float weight = (glacierNoise + 1f) * 0.5f;
        weight *= (1f - temp) * elevation;
        return Math.min(1f, Math.max(0f, weight * 2f));
    }

    /**
     * 喀斯特特征权重 [0,1]
     * 高值 → 适合 STONY_PEAKS / 针叶林 群系
     */
    public float sampleKarstFeature(float worldX, float worldZ) {
        float continentality = sampleContinentality(worldX, worldZ);
        float elevation = sampleElevation(worldX, worldZ);
        float temp = sampleTemperature(worldX, worldZ, 0);
        if (continentality < 0.3f) return 0f;
        if (elevation < 0.3f) return 0f;
        float karstNoise = sampleFBM(elevationNoise, worldX * 0.008f, worldZ * 0.008f, 2, 0.5f, 2.5f);
        float weight = (karstNoise + 1f) * 0.5f;
        weight *= elevation * (1f - continentality * 0.3f);
        return Math.min(1f, Math.max(0f, weight * 2f));
    }

    /**
     * 丹霞特征权重 [0,1]
     * 高值 → 适合 BADLANDS / ERODED_BADLANDS / WOODED_BADLANDS 群系
     * 丹霞 = 温暖/炎热 + 干旱 + 彩色沉积岩层
     */
    public float sampleDanxiaFeature(float worldX, float worldZ) {
        float temp = sampleTemperature(worldX, worldZ, 0);
        float moisture = sampleMoisture(worldX, worldZ, 0.5f, 0, temp);
        float elevation = sampleElevation(worldX, worldZ);
        if (temp < 0.45f || moisture > 0.45f) return 0f;
        float danxiaNoise = sampleFBM(continentNoise, worldX * 0.005f, worldZ * 0.005f, 2, 0.5f, 2.3f);
        float weight = (danxiaNoise + 1f) * 0.5f;
        float tempFactor = (temp - 0.45f) / 0.3f;
        float aridFactor = (0.45f - moisture) / 0.45f;
        weight *= tempFactor * aridFactor * (1f - elevation * 0.3f);
        return Math.min(1f, Math.max(0f, weight * 3f));
    }
}
