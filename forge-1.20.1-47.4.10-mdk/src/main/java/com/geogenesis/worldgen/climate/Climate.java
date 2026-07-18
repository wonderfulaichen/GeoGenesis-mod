package com.geogenesis.worldgen.climate;

/**
 * 气候容器（零依赖，不持颜色）。
 * 重构后保留接口契约，内部重写。
 * temperature ∈ [-1,1]（冷→热）、humidity ∈ [-1,1]（干→湿）。
 */
public record Climate(double temperature, double humidity) {

    public static final Climate DEFAULT = new Climate(0.0, 0.0);

    /** 低温判断（用于雪线） */
    public boolean isCold() {
        return temperature < -0.2;
    }

    /** 严寒判断（冰冻海洋） */
    public boolean isFrozen() {
        return temperature < -0.6;
    }

    /** 高温判断（暖洋） */
    public boolean isHot() {
        return temperature > 0.5;
    }

    /** 干燥判断 */
    public boolean isDry() {
        return humidity < -0.3;
    }
}
