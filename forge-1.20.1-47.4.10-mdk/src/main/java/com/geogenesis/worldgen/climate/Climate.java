package com.geogenesis.worldgen.climate;

import com.geogenesis.config.GeoGenesisConfig;

/**
 * 气候容器（零依赖，不持颜色）。
 *
 * <p>temperature ∈ [-1,1]（冷→热）、humidity ∈ [-1,1]（干→湿）、
 * continentality ∈ [-1,1]（海洋→内陆，= MC 原版 Continentalness）。
 *
 * <p>v2 重构（2026-07-21）：阈值从离散 boolean 判断升级为样条控制点。
 * 每个条件维度（温度/湿度/大陆性）有一条 Cubic Hermite 样条，阈值作为控制点，
 * 输出连续区域权重（tent 函数），实现平滑过渡而非硬边界跳变。
 *
 * <p>v3（2026-07-22）：加入 continentality 字段，用于群系大陆性分支判断。
 */
public record Climate(double temperature, double humidity, double continentality) {

    public static final Climate DEFAULT = new Climate(0.0, 0.0, 0.0);

    /** 从 GeoGenesisConfig 加载阈值（在 mod 初始化时调用，兼容旧代码） */
    public static void loadFromConfig() {
        // 阈值现在直接从 config 读取，此方法保留兼容
    }

    // ===== 样条权重方法（新 API） =====

    /**
     * 获取温度样条（从配置构建，每次调用重新构建以支持运行时配置变更）。
     * 性能优化：高频调用场景应缓存结果。
     */
    private static ClimateSpline tempSpline() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return ClimateSpline.temperature(
                cfg.tempFrozenThreshold.get(),
                cfg.tempColdThreshold.get(),
                cfg.tempWarmThreshold.get(),
                cfg.tempHotThreshold.get());
        }
        // 默认值
        return ClimateSpline.temperature(-0.6, -0.2, 0.2, 0.5);
    }

    /**
     * 获取湿度样条（从配置构建）。
     */
    private static ClimateSpline humSpline() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return ClimateSpline.humidity(
                cfg.humidityDryThreshold.get(),
                cfg.humiditySemiThreshold.get(),
                cfg.humidityWetThreshold.get());
        }
        return ClimateSpline.humidity(-0.3, 0.0, 0.3);
    }

    // ----- 温度连续权重 -----

    /** 极寒权重 [0,1]，温度越低越接近 1 */
    public double frozenWeight() {
        return tempSpline().zoneWeight(temperature, ClimateSpline.TEMP_FROZEN);
    }

    /** 寒冷权重 [0,1] */
    public double coldWeight() {
        return tempSpline().zoneWeight(temperature, ClimateSpline.TEMP_COLD);
    }

    /** 温和权重 [0,1] */
    public double mildWeight() {
        return tempSpline().zoneWeight(temperature, ClimateSpline.TEMP_MILD);
    }

    /** 温暖权重 [0,1] */
    public double warmWeight() {
        return tempSpline().zoneWeight(temperature, ClimateSpline.TEMP_WARM);
    }

    /** 炎热权重 [0,1] */
    public double hotWeight() {
        return tempSpline().zoneWeight(temperature, ClimateSpline.TEMP_HOT);
    }

    /**
     * 温度区域权重数组（长度 5）。
     * 索引对应：[极寒, 寒冷, 温和, 温暖, 炎热]。
     */
    public double[] tempWeights() {
        return tempSpline().zoneWeights(temperature);
    }

    // ----- 湿度连续权重 -----

    /** 干旱权重 [0,1] */
    public double dryWeight() {
        return humSpline().zoneWeight(humidity, ClimateSpline.HUM_DRY);
    }

    /** 半干旱权重 [0,1] */
    public double semiDryWeight() {
        return humSpline().zoneWeight(humidity, ClimateSpline.HUM_SEMI);
    }

    /** 湿润权重 [0,1] */
    public double wetWeight() {
        return humSpline().zoneWeight(humidity, ClimateSpline.HUM_WET);
    }

    /** 潮湿权重 [0,1] */
    public double humidWeight() {
        return humSpline().zoneWeight(humidity, ClimateSpline.HUM_HUMID);
    }

    /**
     * 湿度区域权重数组（长度 4）。
     * 索引对应：[干旱, 半干旱, 湿润, 潮湿]。
     */
    public double[] humWeights() {
        return humSpline().zoneWeights(humidity);
    }

    // ===== 大陆性样条方法 =====

    /**
     * 获取大陆性样条（从配置构建）。
     */
    private static ClimateSpline contSpline() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return ClimateSpline.continentality(
                cfg.continentDeepOceanThreshold.get(),
                cfg.continentNearOceanThreshold.get(),
                cfg.continentCoastThreshold.get(),
                cfg.continentTransitionalThreshold.get(),
                cfg.continentNearInlandThreshold.get(),
                cfg.continentInlandThreshold.get());
        }
        return ClimateSpline.continentality(-0.8, -0.4, -0.1, 0.15, 0.4, 0.7);
    }

    /** 深海区域权重 [0,1] */
    public double deepOceanWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_DEEP_OCEAN);
    }

    /** 近海区域权重 [0,1] */
    public double nearOceanWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_NEAR_OCEAN);
    }

    /** 沿海区域权重 [0,1] */
    public double coastalWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_COASTAL);
    }

    /** 过渡区域权重 [0,1] */
    public double transitionalWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_TRANSITIONAL);
    }

    /** 近内陆区域权重 [0,1] */
    public double nearInlandWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_NEAR_INLAND);
    }

    /** 内陆区域权重 [0,1] */
    public double inlandWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_INLAND);
    }

    /** 深内陆区域权重 [0,1] */
    public double deepInlandWeight() {
        return contSpline().zoneWeight(continentality, ClimateSpline.CONT_DEEP_INLAND);
    }

    /**
     * 大陆性区域权重数组（长度 7）。
     * 索引对应：[深海, 近海, 沿海, 过渡, 近内陆, 内陆, 深内陆]。
     */
    public double[] contWeights() {
        return contSpline().zoneWeights(continentality);
    }

    /** 沿海/过渡权重和 > 0.5 → 判断为沿海区域 */
    public boolean isCoastal() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return coastalWeight() + transitionalWeight() > 0.5;
        }
        return continentality > 0.0 && continentality < 0.4;
    }

    /** 近内陆/内陆/深内陆权重和 > 0.5 → 判断为内陆区域 */
    public boolean isInland() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return nearInlandWeight() + inlandWeight() + deepInlandWeight() > 0.5;
        }
        return continentality > 0.6;
    }

    // ===== 兼容旧 API（boolean 方法，委托给样条权重） =====

    /**
     * 严寒判断（冰冻海洋）。
     * 样条版本：frozenWeight + coldWeight > 0.5 视为"寒冷"。
     */
    public boolean isFrozen() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return frozenWeight() + coldWeight() > 0.5;
        }
        return temperature < -0.6;
    }

    /**
     * 低温判断（用于雪线）。
     * 样条版本：frozenWeight + coldWeight > 0.3 视为"冷"。
     */
    public boolean isCold() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return frozenWeight() + coldWeight() > 0.3;
        }
        return temperature < -0.2;
    }

    /**
     * 高温判断（暖洋）。
     * 样条版本：warmWeight + hotWeight > 0.5 视为"热"。
     */
    public boolean isHot() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return warmWeight() + hotWeight() > 0.5;
        }
        return temperature > 0.5;
    }

    /**
     * 干燥判断。
     * 样条版本：dryWeight + semiDryWeight > 0.5 视为"干"。
     */
    public boolean isDry() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return dryWeight() + semiDryWeight() > 0.5;
        }
        return humidity < -0.3;
    }

    /**
     * 湿润判断。
     * 样条版本：wetWeight + humidWeight > 0.5 视为"湿"。
     */
    public boolean isWet() {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            return wetWeight() + humidWeight() > 0.5;
        }
        return humidity > 0.3;
    }
}
