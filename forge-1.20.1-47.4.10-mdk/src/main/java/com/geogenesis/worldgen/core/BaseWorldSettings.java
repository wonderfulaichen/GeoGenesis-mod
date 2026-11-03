package com.geogenesis.worldgen.core;

/** 基础世界计算参数快照；配置来源由应用端决定。 */
public record BaseWorldSettings(double temperatureInfluence, double humidityInfluence,
                                double continentInfluence) {
    public static BaseWorldSettings defaults() {
        return new BaseWorldSettings(1.0, 1.0, 1.0);
    }
}
