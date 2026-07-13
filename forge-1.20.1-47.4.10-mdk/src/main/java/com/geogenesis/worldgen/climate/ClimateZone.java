package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.Cell;

/**
 * Koppen简版气候带（零依赖，纯分类）。
 */
public final class ClimateZone {

    private ClimateZone() {}

    public enum Zone {
        A, // 热带
        B, // 干旱
        C, // 温带
        D, // 冷温带
        E  // 极地
    }

    /** 温度+湿度→气候带 */
    public static Zone classify(double temperature, double humidity) {
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
