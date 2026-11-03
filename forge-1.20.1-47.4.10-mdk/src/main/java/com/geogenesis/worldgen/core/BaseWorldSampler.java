package com.geogenesis.worldgen.core;

import com.geogenesis.worldgen.climate.*;
import com.geogenesis.worldgen.noise.*;
import com.geogenesis.worldgen.terrain.*;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

/** 当前基础世界核心：wu坐标，显式参数，无Forge配置、Cell、侵蚀或水文依赖。 */
public final class BaseWorldSampler {
    private final TerrainParams params;
    private final BaseWorldSettings settings;
    private final MorphTerrainSampler terrain;
    private final TectonicField tectonic;
    private final StratumField strata;
    private final Noise tempWarp, humidityNoise, regionWarp, oasisNoise;
    private final ClimateRegion regions;
    private final PrecipField precipitation;
    private final double tempFreq, regionSize;

    public BaseWorldSampler(long seed, TerrainParams params, int minY, int maxY, BaseWorldSettings settings) {
        this.params = params;
        this.settings = settings;
        terrain = new MorphTerrainSampler(seed, params, minY, maxY);
        tectonic = new TectonicField(seed);
        strata = new StratumField(seed);
        tempFreq = 1.0 / params.latitudeScale();
        regionSize = params.climateRegionSize();
        tempWarp = new Frequency(new Simplex(501), 1.0 / params.tempWarpScale());
        humidityNoise = new Frequency(new Simplex(502), 1.0 / params.humidityScale());
        regionWarp = new Frequency(new Simplex(503), 1.0 / (regionSize * 1.5));
        oasisNoise = new Frequency(new Simplex(504), 1.0 / (regionSize * 0.75));
        Noises.seedAll(tempWarp, seed, 0);
        Noises.seedAll(humidityNoise, seed, 0);
        Noises.seedAll(regionWarp, seed, 0);
        Noises.seedAll(oasisNoise, seed, 0);
        regions = new ClimateRegion(regionSize);
        regions.seed(seed);
        precipitation = new PrecipField(seed, terrain::height, params.latitudeScale(),
                PrecipField.Params.defaults(), WindField.Params.defaults(), terrain.heightCurve().seaLevelY());
    }

    public double sampleE(double x, double z) { return terrain.sampleE(x, z); }
    public double height(double x, double z) { return terrain.height(x, z); }

    /** 只生成地貌与地层元数据，不触发气候回扫。 */
    public BaseColumn sampleTerrain(double x, double z) {
        BaseColumn c = terrain.sampleColumn(x, z);
        c.variantTerrain = variant(c.typeWeights, x, z);
        TectonicField.Sample ts = tectonic.sample(x, z);
        c.rockLayer = strata.layerAt(x, z);
        c.rockTypeId = StratumField.rockTypeId(ts, c.e >= 0.0, c.rockLayer);
        c.rockSeqPacked = strata.packSequence(ts, c.e >= 0.0, x, z);
        c.rockTilt = strata.tiltAt(x, z);
        return c;
    }

    /** 完整基础列；所有计算仍只依赖基础地形，后处理留待独立阶段。 */
    public BaseColumn sample(double x, double z) {
        BaseColumn c = sampleTerrain(x, z);
        ClimateRegion.Sample reg = regions.sample(x + warpOffset(x, z), z + warpOffset(z, x));
        double moist01 = clamp(regionMoisture(reg) * 0.5 + 0.5, 0.0, 1.0);
        double regionPrecip = clamp(moist01 * LatPrecipProfile.at(Latitude.latitude01(z, params.latitudeScale()))
                / 0.70, 0.0, 1.0);
        c.biomeType = WhittakerType.classify(clamp(regionTemperature(reg) * 0.5 + 0.5, 0.0, 1.0), regionPrecip);
        double temp = temperatureAt(x, z);
        temp *= 0.85 + 0.15 * clamp(c.continent * 1.5, 0.0, 1.0);
        temp -= elevationTempDrop(c.e);
        PrecipField.Mod pm = precipitation.at(x, z);
        temp += pm.foehnWarm() / 40.0;
        temp = clamp(temp, -1.0, 1.0);
        double hum = humidityNoise.compute(x, z) * 0.75;
        hum = continentMoisture(hum, c.continent);
        hum -= elevationMoistureDrop(c.e);
        hum += pm.waterMoist();
        hum = clamp(hum, -1.0, 1.0);
        c.temperature = clamp(temp * settings.temperatureInfluence(), -1.0, 1.0);
        c.humidity = clamp(hum * settings.humidityInfluence(), -1.0, 1.0);
        c.climateContinentality = clamp(c.continent * settings.continentInfluence(), -1.0, 1.0);
        c.precipitation = precipitationFrom(c.humidity, c.temperature,
                Latitude.latitude01(z, params.latitudeScale()), pm);
        c.snowLineE = params.snowLine() + params.snowLatitudeInfluence() * c.temperature
                - params.snowHumidityInfluence() * c.humidity;
        c.snow = c.e > 0.0 && (c.e > c.snowLineE || c.temperature < -0.52);
        c.oasisNoise = oasisNoise.compute(x, z);
        return c;
    }

    /** 跨2wu的高度差；应用端按采样跨度和坐标比例换算坡度，不提前舍入。 */
    public double heightDifferenceX(double x, double z) { return height(x + 1.0, z) - height(x - 1.0, z); }
    public double heightDifferenceZ(double x, double z) { return height(x, z + 1.0) - height(x, z - 1.0); }

    private TerrainClass variant(double[] weights, double x, double z) {
        TerrainClass[] all = TerrainClass.values();
        int best = -1;
        double bestW = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < weights.length && i < all.length; i++) {
            double dith = regionWarp.compute(x * 3.0 + i * 733.0, z * 3.0 - i * 1499.0) * 0.12;
            double w = weights[i] + dith;
            if (w > bestW) { bestW = w; best = i; }
        }
        return best >= 0 ? all[best] : null;
    }

    private double warpOffset(double x, double z) {
        double o = regionWarp.compute(x, z) * 0.25
                + regionWarp.compute(x * 2.0, z * 2.0) * 0.12
                + regionWarp.compute(x * 4.0, z * 4.0) * 0.06
                + regionWarp.compute(x * 8.0, z * 8.0) * 0.04
                + regionWarp.compute(x * 16.0, z * 16.0) * 0.02;
        return o * regionSize;
    }

    private double regionTemperature(ClimateRegion.Sample r) {
        double t1 = temperatureAt(r.centerX(), r.centerZ());
        if (r.blend() <= 0.0) return t1;
        double t2 = temperatureAt(r.centerX2(), r.centerZ2());
        return t1 + (t2 - t1) * r.blend();
    }

    private double temperatureAt(double x, double z) {
        double lat = Latitude.latitude01(z, 1.0 / tempFreq);
        return (1.0 - 2.0 * lat) + tempWarp.compute(x, z) * 0.15;
    }

    private double regionMoisture(ClimateRegion.Sample r) {
        double m1 = humidityNoise.compute(r.centerX(), r.centerZ());
        if (r.blend() <= 0.0) return m1;
        double m2 = humidityNoise.compute(r.centerX2(), r.centerZ2());
        return m1 + (m2 - m1) * r.blend();
    }

    private static double elevationTempDrop(double e) {
        if (e <= 0.35) return 0.0;
        if (e >= 0.65) return 0.16;
        return 0.16 * (e - 0.35) / 0.30;
    }

    private static double elevationMoistureDrop(double e) {
        if (e <= 0.25) return 0.0;
        if (e >= 0.60) return 0.20;
        return 0.20 * (e - 0.25) / 0.35;
    }

    private static double continentMoisture(double hum, double continent) {
        double c01 = clamp(continent * 0.5 + 0.5, 0.0, 1.0);
        double m01 = hum * 0.5 + 0.5;
        double limit = 0.5;
        if (c01 < limit) {
            double k = (limit - c01) / limit * 0.55;
            m01 += (1.0 - m01) * k;
        } else {
            double k = (c01 - limit) / (1.0 - limit) * 0.55;
            m01 -= m01 * k;
        }
        return clamp(m01 * 2.0 - 1.0, -1.0, 1.0);
    }

    private static double precipitationFrom(double hum, double temp, double lat, PrecipField.Mod pm) {
        double humidity01 = clamp(hum * 0.5 + 0.5, 0.0, 1.0);
        double base = humidity01 * LatPrecipProfile.at(lat);
        double tempGate = 0.55 + 0.45 * ((temp + 1.0) * 0.5);
        double v = base * (1.0 + pm.orographicGain()) * (1.0 - pm.shadowLoss()) * tempGate;
        return clamp(v, 0.0, 1.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
