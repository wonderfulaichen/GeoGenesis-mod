package com.geogenesis.worldgen.geology;

/**
 * 地质系统
 *
 * 核心设计：
 * 根据气候条件推断地质特性（硬度、粘度、抗侵蚀性）
 *
 * 地质推断规则：
 * - 高温+高湿 → 软但粘（如粘土、页岩）
 * - 高温+干旱 → 硬但松（如砂岩、花岗岩风化层）
 * - 低温+高湿 → 硬且密（如花岗岩、玄武岩）
 * - 低温+干旱 → 极硬（如石英岩、冻土）
 */
public class GeologySystem {

    /**
     * 地质类型
     */
    public enum RockType {
        GRANITE,        // 花岗岩 - 硬且密
        BASALT,         // 玄武岩 - 硬且密
        SANDSTONE,      // 砂岩 - 硬但松
        SHALE,          // 页岩 - 软但粘
        LIMESTONE,      // 石灰岩 - 中等硬度
        CLAY,           // 粘土 - 软且粘
        QUARTZITE,      // 石英岩 - 极硬
        PERMAFROST,     // 冻土 - 极硬（冰冻）
        VOLCANIC        // 火山岩 - 硬且多孔
    }

    /**
     * 地质特性
     */
    public static class GeologyProperties {
        public final RockType rockType;         // 岩石类型
        public final float hardness;            // 硬度 [0, 1] - 越高越难侵蚀
        public final float viscosity;           // 粘度 [0, 1] - 越高越粘（不易被水带走）
        public final float porosity;            // 孔隙度 [0, 1] - 越高越松
        public final float weatheringRate;      // 风化速率 [0, 1] - 越高风化越快

        public GeologyProperties(RockType rockType, float hardness, float viscosity,
                                  float porosity, float weatheringRate) {
            this.rockType = rockType;
            this.hardness = hardness;
            this.viscosity = viscosity;
            this.porosity = porosity;
            this.weatheringRate = weatheringRate;
        }
    }

    /**
     * 根据气候推断地质
     *
     * 推断规则：
     * - 高温+高湿 → 化学风化强 → 软但粘（页岩、粘土）
     * - 高温+干旱 → 物理风化为主 → 硬但松（砂岩）
     * - 低温+高湿 → 物理风化+冻融 → 硬且密（花岗岩）
     * - 低温+干旱 → 极寒 → 极硬（石英岩、冻土）
     */
    public GeologyProperties inferGeology(float temperature, float moisture, float elevation) {
        // 化学风化强度（高温高湿促进化学风化）
        float chemicalWeathering = temperature * moisture;

        // 物理风化强度（温差大促进物理风化）
        float physicalWeathering = (1.0f - temperature) * temperature * 4.0f; // 温差最大在0.5

        // 冻融作用（低温+高湿）
        float freezeThaw = (temperature < 0.3f) ? moisture * (1.0f - temperature) : 0.0f;

        // 根据风化和气候推断岩石类型
        RockType rockType;
        float hardness, viscosity, porosity, weatheringRate;

        if (temperature > 0.6f && moisture > 0.6f) {
            // 高温高湿：化学风化强 → 页岩/粘土
            if (chemicalWeathering > 0.7f) {
                rockType = RockType.CLAY;
                hardness = 0.2f;
                viscosity = 0.9f;
                porosity = 0.3f;
                weatheringRate = 0.8f;
            } else {
                rockType = RockType.SHALE;
                hardness = 0.3f;
                viscosity = 0.7f;
                porosity = 0.4f;
                weatheringRate = 0.7f;
            }
        } else if (temperature > 0.6f && moisture < 0.3f) {
            // 高温干旱：物理风化 → 砂岩
            rockType = RockType.SANDSTONE;
            hardness = 0.6f;
            viscosity = 0.2f;
            porosity = 0.8f;
            weatheringRate = 0.5f;
        } else if (temperature < 0.3f && moisture > 0.5f) {
            // 低温高湿：冻融作用 → 花岗岩/冻土
            if (elevation > 0.7f) {
                rockType = RockType.PERMAFROST;
                hardness = 0.9f;
                viscosity = 0.1f;
                porosity = 0.2f;
                weatheringRate = 0.2f;
            } else {
                rockType = RockType.GRANITE;
                hardness = 0.85f;
                viscosity = 0.3f;
                porosity = 0.2f;
                weatheringRate = 0.3f;
            }
        } else if (temperature < 0.3f && moisture < 0.3f) {
            // 低温干旱：极寒 → 石英岩
            rockType = RockType.QUARTZITE;
            hardness = 0.95f;
            viscosity = 0.1f;
            porosity = 0.1f;
            weatheringRate = 0.1f;
        } else {
            // 中等气候：石灰岩
            rockType = RockType.LIMESTONE;
            hardness = 0.5f;
            viscosity = 0.4f;
            porosity = 0.5f;
            weatheringRate = 0.6f;
        }

        // 海拔修正：高海拔更硬（更原始）
        if (elevation > 0.7f) {
            hardness = Math.min(1.0f, hardness + 0.1f);
            weatheringRate = Math.max(0.0f, weatheringRate - 0.1f);
        }

        return new GeologyProperties(rockType, hardness, viscosity, porosity, weatheringRate);
    }

    /**
     * 计算抗侵蚀性
     * 硬度高+粘度高 = 抗侵蚀
     */
    public float calculateErosionResistance(GeologyProperties geology) {
        // 硬度抵抗物理侵蚀
        float hardnessResistance = geology.hardness * 0.5f;

        // 粘度抵抗水力侵蚀
        float viscosityResistance = geology.viscosity * 0.3f;

        // 孔隙度降低抗侵蚀性（松散的更容易被侵蚀）
        float porosityPenalty = geology.porosity * 0.2f;

        return Math.max(0.0f, Math.min(1.0f, hardnessResistance + viscosityResistance - porosityPenalty));
    }

    /**
     * 计算风化后的地质
     * 根据侵蚀强度和风化速率更新地质
     */
    public GeologyProperties weatherGeology(GeologyProperties geology, float erosionStrength, float time) {
        // 风化降低硬度
        float newHardness = Math.max(0.0f, geology.hardness - geology.weatheringRate * erosionStrength * time);

        // 风化增加孔隙度
        float newPorosity = Math.min(1.0f, geology.porosity + geology.weatheringRate * erosionStrength * time * 0.5f);

        // 粘度可能增加（风化产物更粘）
        float newViscosity = Math.min(1.0f, geology.viscosity + geology.weatheringRate * erosionStrength * time * 0.2f);

        return new GeologyProperties(geology.rockType, newHardness, newViscosity, newPorosity, geology.weatheringRate);
    }
}
