package com.geogenesis.worldgen.climate;

import com.geogenesis.config.ConfigSafe;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;

/**
 * Koppen简版气候带（零依赖，纯分类）。
 *
 * <p>v2 重构（2026-07-21）：使用 ClimateSpline 样条权重替代硬阈值判断。
 * 气候带分类基于温度和湿度的连续区域权重，实现平滑过渡。
 */
public final class ClimateZone {

    private ClimateZone() {}

    public enum Zone {
        A, // 热带（温暖+炎热）
        B, // 干旱（干旱+半干旱）
        C, // 温带（温和）
        D, // 冷温带（寒冷）
        E  // 极地（极寒）
    }

    /** 从 GeoGenesisConfig 加载阈值（在 mod 初始化时调用，兼容旧代码） */
    public static void loadFromConfig() {
        // 阈值现在直接从 config 读取，此方法保留兼容
    }

    /**
     * 温度+湿度→气候带（使用样条权重）。
     *
     * <p>分类逻辑：
     * <ul>
     *   <li>A（热带）：warmWeight + hotWeight 最大</li>
     *   <li>E（极地）：frozenWeight + coldWeight 最大</li>
     *   <li>B（干旱）：dryWeight + semiDryWeight > wetWeight + humidWeight</li>
     *   <li>C（温带）：mildWeight 最大（且不干旱）</li>
     *   <li>D（冷温带）：coldWeight 最大（且不干旱）</li>
     * </ul>
     */
    public static Zone classify(double temperature, double humidity) {
        GeoGenesisConfig cfg = GeoGenesisConfig.INSTANCE;
        if (cfg != null) {
            // 构建样条（ConfigSafe：预览/探针进程配置未加载时 get() 抛异常 → 回退默认值）
            ClimateSpline tempSpl = ClimateSpline.temperature(
                ConfigSafe.dbl(cfg.tempFrozenThreshold, -0.6),
                ConfigSafe.dbl(cfg.tempColdThreshold, -0.2),
                ConfigSafe.dbl(cfg.tempWarmThreshold, 0.2),
                ConfigSafe.dbl(cfg.tempHotThreshold, 0.5));
            ClimateSpline humSpl = ClimateSpline.humidity(
                ConfigSafe.dbl(cfg.humidityDryThreshold, -0.3),
                ConfigSafe.dbl(cfg.humiditySemiThreshold, 0.0),
                ConfigSafe.dbl(cfg.humidityWetThreshold, 0.3));

            return classifyFromWeights(tempSpl.zoneWeights(temperature),
                                       humSpl.zoneWeights(humidity));
        }
        // fallback
        if (temperature > 0.5) return Zone.A;
        if (temperature < -0.5) return Zone.E;
        if (humidity < -0.3) return Zone.B;
        if (temperature > 0.0) return Zone.C;
        return Zone.D;
    }

    /**
     * 从已算好的样条权重分类（供调用方复用同一次权重计算，避免重复构造 ClimateSpline）。
     *
     * @param tempWeights 温度权重数组（长度 5，见 {@link Climate#tempWeights()}：[极寒, 寒冷, 温和, 温暖, 炎热]）
     * @param humWeights  湿度权重数组（长度 4，见 {@link Climate#humWeights()}：[干旱, 半干旱, 湿润, 潮湿]）
     */
    public static Zone classifyFromWeights(double[] tempWeights, double[] humWeights) {
        double frozenW = tempWeights[0];
        double coldW   = tempWeights[1];
        double mildW   = tempWeights[2];
        double warmW   = tempWeights[3];
        double hotW    = tempWeights[4];

        // 干旱（干旱 + 半干旱）优先于温度带
        double dryW = humWeights[0] + humWeights[1];
        double wetW = humWeights[2] + humWeights[3];
        if (dryW > wetW) return Zone.B;
        if (frozenW + coldW > mildW + warmW + hotW) {
            return frozenW > coldW ? Zone.E : Zone.D;
        }
        if (warmW + hotW > mildW) return Zone.A;
        return Zone.C;
    }

    /** Cell→气候带（旧 API） */
    public static Zone classify(Cell cell) {
        return classify(cell.temperature, cell.humidity);
    }
}
