package com.geogenesis.worldgen.climate;

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
            // 构建样条
            ClimateSpline tempSpl = ClimateSpline.temperature(
                cfg.tempFrozenThreshold.get(),
                cfg.tempColdThreshold.get(),
                cfg.tempWarmThreshold.get(),
                cfg.tempHotThreshold.get());
            ClimateSpline humSpl = ClimateSpline.humidity(
                cfg.humidityDryThreshold.get(),
                cfg.humiditySemiThreshold.get(),
                cfg.humidityWetThreshold.get());

            // 温度权重
            double frozenW = tempSpl.zoneWeight(temperature, ClimateSpline.TEMP_FROZEN);
            double coldW = tempSpl.zoneWeight(temperature, ClimateSpline.TEMP_COLD);
            double mildW = tempSpl.zoneWeight(temperature, ClimateSpline.TEMP_MILD);
            double warmW = tempSpl.zoneWeight(temperature, ClimateSpline.TEMP_WARM);
            double hotW = tempSpl.zoneWeight(temperature, ClimateSpline.TEMP_HOT);

            // 湿度权重
            double dryW = humSpl.zoneWeight(humidity, ClimateSpline.HUM_DRY)
                        + humSpl.zoneWeight(humidity, ClimateSpline.HUM_SEMI);
            double wetW = humSpl.zoneWeight(humidity, ClimateSpline.HUM_WET)
                        + humSpl.zoneWeight(humidity, ClimateSpline.HUM_HUMID);

            // 分类：干旱优先，然后按温度
            if (dryW > wetW) return Zone.B;
            if (frozenW + coldW > mildW + warmW + hotW) {
                return frozenW > coldW ? Zone.E : Zone.D;
            }
            if (warmW + hotW > mildW) return Zone.A;
            return Zone.C;
        }
        // fallback
        if (temperature > 0.5) return Zone.A;
        if (temperature < -0.5) return Zone.E;
        if (humidity < -0.3) return Zone.B;
        if (temperature > 0.0) return Zone.C;
        return Zone.D;
    }

    /** Cell→气候带（旧 API） */
    public static Zone classify(Cell cell) {
        return classify(cell.temperature, cell.humidity);
    }
}
