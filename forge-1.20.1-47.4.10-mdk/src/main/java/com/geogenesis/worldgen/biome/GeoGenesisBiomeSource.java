package com.geogenesis.worldgen.biome;

import com.geogenesis.worldgen.climate.ClimateSystem;
import com.geogenesis.worldgen.hydrology.HydrologySystem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * 气候驱动的自定义 BiomeSource
 *
 * 包裹原版 BiomeSource，在 getNoiseBiome 时使用 ClimateSystem 的气候数据选择群系。
 * 不依赖 TerrainCache，兼容原版地形生成。
 */
public class GeoGenesisBiomeSource extends BiomeSource {

    public static final Codec<GeoGenesisBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("fallback").forGetter(s -> s.fallback)
    ).apply(instance, GeoGenesisBiomeSource::new));

    private final BiomeSource fallback;
    private volatile ClimateSystem climateSystem;
    private volatile HydrologySystem hydrologySystem;
    private volatile int seed = 0;

    public GeoGenesisBiomeSource(BiomeSource fallback) {
        this.fallback = fallback;
    }

    public void wire(ClimateSystem climateSystem, HydrologySystem hydrologySystem, int seed) {
        this.climateSystem = climateSystem;
        this.hydrologySystem = hydrologySystem;
        this.seed = seed;
    }

    private ClimateSystem getOrCreateClimateSystem() {
        if (climateSystem == null) {
            climateSystem = new ClimateSystem(seed);
        }
        return climateSystem;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return fallback.possibleBiomes().stream();
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        ClimateSystem cs = getOrCreateClimateSystem();
        if (cs == null) {
            return fallback.getNoiseBiome(x, y, z, sampler);
        }

        int worldX = (x << 2) + 2;
        int worldZ = (z << 2) + 2;

        try {
            float continentality = cs.sampleContinentality(worldX, worldZ);
            float continent = continentality * 2f - 1f;
            float temperature = cs.sampleTemperature(worldX, worldZ, 0);
            float moisture = cs.sampleMoisture(worldX, worldZ, continentality, 0, temperature);
            float elevation = cs.sampleElevation(worldX, worldZ);

            float glacierWeight = cs.sampleGlacierFeature(worldX, worldZ);
            float karstWeight = cs.sampleKarstFeature(worldX, worldZ);
            float danxiaWeight = cs.sampleDanxiaFeature(worldX, worldZ);

            float riverDepth = 0;
            if (hydrologySystem != null) {
                riverDepth = hydrologySystem.getRiverDepthAt(worldX, worldZ);
            }

            ResourceKey<Biome> biomeKey = ClimateBiomeMapper.selectBiome(
                temperature, moisture, elevation, continent, riverDepth,
                glacierWeight, karstWeight, danxiaWeight);
            return ClimateBiomeMapper.resolveBiome(biomeKey, fallback);
        } catch (Exception e) {
            return fallback.getNoiseBiome(x, y, z, sampler);
        }
    }
}
