package com.geogenesis.config;

import com.geogenesis.worldgen.core.BaseWorldSettings;

/** Forge配置到纯Java参数快照；核心不读取全局配置。 */
public final class BaseWorldConfigAdapter {
    private BaseWorldConfigAdapter() { }

    public static BaseWorldSettings snapshot() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        if (c == null) return BaseWorldSettings.defaults();
        return new BaseWorldSettings(ConfigSafe.dbl(c.tempInfluence, 1.0),
                ConfigSafe.dbl(c.humidityInfluence, 1.0),
                ConfigSafe.dbl(c.continentInfluence, 1.0));
    }
}
