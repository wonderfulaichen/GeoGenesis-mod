package com.geogenesis.worldgen.core;

import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 只在独立核心classpath运行，不加载Forge、MC或旧Cell。 */
public final class BaseCoreCheck {
    public static void main(String[] args) {
        for (long seed : new long[]{9139912035078620160L, 12345L}) {
            BaseWorldSampler a = new BaseWorldSampler(seed, TerrainParams.defaults(), -64, 320,
                    BaseWorldSettings.defaults());
            BaseWorldSampler adjusted = new BaseWorldSampler(seed, TerrainParams.defaults(), -64, 320,
                    new BaseWorldSettings(0, 0, 0));
            for (double[] p : new double[][]{{0.5, -0.5}, {-3642.5, -3365.5}, {1657.5, 2289.5},
                    {1875000.5, 1875000.5}}) {
                BaseColumn c = a.sample(p[0], p[1]);
                BaseColumn d = adjusted.sample(p[0], p[1]);
                require(Double.isFinite(c.height) && c.height == a.height(p[0], p[1]), "height");
                require(c.typeWeights.length == TerrainClass.COUNT && c.biomeType != null, "metadata");
                require(c.height == d.height && d.temperature == 0 && d.humidity == 0
                        && d.climateContinentality == 0, "explicit settings");
                double weight = c.typeWeights[0];
                c.typeWeights[0] = 999;
                require(a.sample(p[0], p[1]).typeWeights[0] == weight, "result ownership");
            }
        }
        System.out.println("BASE CORE PASS: independent JVM, no Minecraft/Forge/Cell; 2 seeds / 8 points");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
