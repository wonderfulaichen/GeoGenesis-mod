package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.NoiseEngine;

public class HydrologySystem {

    private final RiverBrushSystem riverBrush;
    private boolean riverEnabled = true;

    public HydrologySystem(int baseSeed, NoiseEngine noise) {
        this.riverBrush = new RiverBrushSystem(baseSeed, noise);
    }

    public void setRiverEnabled(boolean enabled) {
        this.riverEnabled = enabled;
    }

    public void setTerrainParams(float seaNorm, float odFactor, int maxY, int minY) {
        riverBrush.setTerrainParams(seaNorm, odFactor, maxY, minY);
    }

    public float getRiverDepthAt(float wx, float wz) {
        if (!riverEnabled) return 0f;
        return riverBrush.getRiverDepthAt(wx, wz);
    }

    public RiverBrushSystem.Sample sampleRiverAt(float wx, float wz) {
        if (!riverEnabled) return null;
        return riverBrush.sampleAt(wx, wz);
    }

    public float sampleRiverNoise(float wx, float wz) {
        if (!riverEnabled) return 0f;
        return riverBrush.sampleRiverNoise(wx, wz);
    }

    public float calculatePrecipitation(float temperature, float moisture, float elevation) {
        return Math.min(1.0f, moisture * 0.6f + elevation * 0.3f * 0.2f + temperature * 0.2f);
    }

    public float calculateRiverStrength(float precipitation, float elevation, float slope) {
        return Math.min(1.0f, precipitation * 0.5f + slope * 0.5f * 0.3f + (1.0f - elevation) * 0.3f * 0.2f);
    }

    public float calculateHydraulicErosion(float precipitation, float riverStrength, float slope) {
        return Math.min(1.0f, precipitation * 0.4f + riverStrength * 0.5f * 0.4f + slope * 0.3f * 0.2f);
    }
}
