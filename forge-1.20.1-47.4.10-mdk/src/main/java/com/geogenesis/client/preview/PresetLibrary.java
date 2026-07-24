package com.geogenesis.client.preview;

import com.geogenesis.config.GeoGenesisConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置地形配置预设库。
 *
 * <p>每个预设仅声明需要覆盖的「语义字段」覆盖表，基于 {@link GeoGenesisConfig} 的真实默认值设计，
 * 确保覆盖值落在其合法范围内。应用预设 = resetToDefault() + 写入覆盖表（见 GeoGenesisConfigScreen.applyPreset）。
 */
public final class PresetLibrary {

    private PresetLibrary() {}

    /** 把成对参数（字段, 值）收集为覆盖表 */
    private static Map<ForgeConfigSpec.DoubleValue, Double> map(Object... kv) {
        Map<ForgeConfigSpec.DoubleValue, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((ForgeConfigSpec.DoubleValue) kv[i], (Double) kv[i + 1]);
        }
        return m;
    }

    public static List<Preset> all() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        return List.of(
            // 群岛世界：大量海洋、破碎海岸、低矮丘陵为主
            new Preset("islands", "群岛世界",
                "广阔海洋与破碎海岸，陆地低矮、丘陵为主，少有高山。",
                map(c.continentBias, 0.8, c.oceanDepthFactor, 1.4,
                    c.cratonReliefAmp, 0.06, c.beltReliefAmp, 0.15,
                    c.elevHigh, 0.30, c.reliefHigh, 0.03, c.snowLine, 0.75)),

            // 辽阔大陆：低海洋占比、广阔平原、中等山脉
            new Preset("continent", "辽阔大陆",
                "陆地广袤、平原辽阔，海岸平缓，山脉中等起伏。",
                map(c.continentBias, -0.1, c.oceanDepthFactor, 0.7,
                    c.beltReliefAmp, 0.20,
                    c.elevHigh, 0.22, c.reliefHigh, 0.04, c.snowLine, 0.70)),

            // 高山峻岭：高山脉起伏、深谷、少平原、雪线偏低
            new Preset("mountains", "高山峻岭",
                "连绵高山与深谷，山脉起伏强烈、雪线偏低，平原稀少。",
                map(c.continentBias, 0.3, c.oceanDepthFactor, 1.1,
                    c.beltReliefAmp, 0.45, c.cratonReliefAmp, 0.12,
                    c.plateauReliefAmp, 0.04,
                    c.elevHigh, 0.20, c.reliefHigh, 0.06, c.snowLine, 0.60, c.peakE, 0.75)),

            // 温和丘陵：整体低起伏、圆润丘陵、温和气候
            new Preset("hills", "温和丘陵",
                "整体低起伏、圆润丘陵为主，地形平缓宜居。",
                map(c.continentBias, 0.3, c.oceanDepthFactor, 1.0,
                    c.cratonReliefAmp, 0.05, c.beltReliefAmp, 0.12,
                    c.plateauReliefAmp, 0.02, c.basinReliefAmp, 0.03,
                    c.elevHigh, 0.30, c.reliefHigh, 0.03, c.snowLine, 0.75)),

            // 原始荒野：极端多样化地形与群系
            new Preset("wilderness", "原始荒野",
                "极端多样化的地形：高山深谷并存、海岸破碎、气候对比强烈。",
                map(c.continentBias, 0.5, c.oceanDepthFactor, 1.3,
                    c.beltReliefAmp, 0.50, c.cratonReliefAmp, 0.15,
                    c.plateauReliefAmp, 0.05,
                    c.basinReliefAmp, 0.08,
                    c.elevHigh, 0.18, c.reliefHigh, 0.08, c.snowLine, 0.65, c.peakE, 0.70))
        );
    }
}
