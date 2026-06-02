package com.geogenesis.worldgen;

import com.geogenesis.worldgen.climate.ClimateSystem;
import com.geogenesis.worldgen.geology.PlateTectonics;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class HeightmapPreview {

    private static final int MIN_Y = -64;

    public static void main(String[] args) throws Exception {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 273651;
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int maxY = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int seaLevel = args.length > 3 ? Integer.parseInt(args[3]) : 63;
        int oceanDepthMax = args.length > 4 ? Integer.parseInt(args[4]) : 32;

        NoiseEngine noise = new NoiseEngine(seed);
        ErosionEngine erosion = new ErosionEngine(noise, seed);
        ClimateSystem climate = new ClimateSystem(seed);
        PlateTectonics plates = new PlateTectonics(seed);

        System.out.println("Generating preview (seed=" + seed + ", scale=" + scale
            + ", maxY=" + maxY + ", seaLevel=" + seaLevel + ")...");
        savePreview(noise, erosion, climate, plates, seed, scale, maxY, seaLevel, oceanDepthMax, new File("."));
    }

    public static void savePreview(NoiseEngine noiseEngine, ErosionEngine erosionEngine,
                                     ClimateSystem climateSystem, PlateTectonics plateSystem,
                                     int seed, int scale,
                                     int maxY, int seaLevel, int oceanDepthMax,
                                     File outputDir) throws Exception {
        int worldSize = 1024;
        float seaNorm = (float)(seaLevel - MIN_Y) / (maxY - MIN_Y);
        float odFactor = (float)oceanDepthMax * seaNorm / (seaLevel - (float)MIN_Y);
        int mapSize = worldSize / scale;

        long t0 = System.nanoTime();

        float[][] heightmap = new float[mapSize][mapSize];
        for (int z = 0; z < mapSize; z++) {
            for (int x = 0; x < mapSize; x++) {
                heightmap[z][x] = computeHeight(noiseEngine, climateSystem, plateSystem, x * scale, z * scale, maxY, seaLevel, oceanDepthMax);
            }
        }
        long t1 = System.nanoTime();

        erosionEngine.applyErosionNormalized(heightmap, mapSize, 0, 0, seaNorm, 1.5f);
        long t2 = System.nanoTime();

        BufferedImage img = new BufferedImage(mapSize * 4, mapSize + 30, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < 30; y++)
            for (int x = 0; x < mapSize * 4; x++)
                img.setRGB(x, y, 0x222222);

        drawLabel(img, "Before", 2, 2);
        drawLabel(img, "After", mapSize + 2, 2);
        drawLabel(img, "Erosion Delta", mapSize * 2 + 2, 2);
        drawLabel(img, "Color Key", mapSize * 3 + 2, 2);

        float minH = 1, maxH = 0;
        float[][] before = new float[mapSize][mapSize];
        for (int z = 0; z < mapSize; z++) {
            for (int x = 0; x < mapSize; x++) {
                before[z][x] = computeHeight(noiseEngine, climateSystem, plateSystem, x * scale, z * scale, maxY, seaLevel, oceanDepthMax);
                if (before[z][x] < minH) minH = before[z][x];
                if (before[z][x] > maxH) maxH = before[z][x];
                if (heightmap[z][x] < minH) minH = heightmap[z][x];
                if (heightmap[z][x] > maxH) maxH = heightmap[z][x];
            }
        }

        float range = Math.max(maxH - minH, 0.01f);
        float maxDelta = 0;
        for (int z = 0; z < mapSize; z++)
            for (int x = 0; x < mapSize; x++) {
                float delta = Math.abs(heightmap[z][x] - before[z][x]);
                if (delta > maxDelta) maxDelta = delta;
            }

        for (int z = 0; z < mapSize; z++) {
            for (int x = 0; x < mapSize; x++) {
                img.setRGB(x, z + 30, heightToColor(before[z][x], minH, range));
                img.setRGB(x + mapSize, z + 30, heightToColor(heightmap[z][x], minH, range));
                float delta = heightmap[z][x] - before[z][x];
                img.setRGB(x + mapSize * 2, z + 30, deltaToColor(delta, maxDelta));
                float keyH = minH + range * x / (float) mapSize;
                img.setRGB(x + mapSize * 3, z + 30, heightToColor(keyH, minH, range));
            }
        }

        File file = new File(outputDir, "preview_seed" + seed + "_s" + scale + ".png");
        ImageIO.write(img, "png", file);

        float ms1 = (t1 - t0) / 1e6f;
        float ms2 = (t2 - t1) / 1e6f;
        System.out.println("Preview saved: " + file.getAbsolutePath());
        System.out.println("  Base: " + String.format("%.0f", ms1) + "ms");
        System.out.println("  Erosion: " + String.format("%.0f", ms2) + "ms");
        System.out.println("  Map: " + mapSize + "x" + mapSize + " (scale=" + scale + ")");
        System.out.println("  Max erosion delta: " + String.format("%.4f", maxDelta));
        System.out.println("  Height range: " + String.format("%.3f", minH) + " ~ " + String.format("%.3f", maxH));
    }

    static float computeHeight(NoiseEngine noise, ClimateSystem climate, PlateTectonics plates,
                                 int wx, int wz, int maxY, int seaLevel, int oceanDepthMax) {
        float seaNorm = (float)(seaLevel - MIN_Y) / (maxY - MIN_Y);
        float odFactor = (float)oceanDepthMax * seaNorm / (seaLevel - (float)MIN_Y);
        PlateTectonics.PlateData plate = plates.sample(wx, wz);
        float continent = noise.sampleContinentRaw(wx, wz);
        float continent01 = (continent + 1f) * 0.5f;
        float terrain = noise.sampleTerrainBase(wx, wz);
        float relief = noise.sampleElevation(wx, wz);
        float plateauW = noise.samplePlateauWeight(wx, wz);
        float karstW = noise.sampleKarstWeight(wx, wz);
        float danxiaW = noise.sampleDanxiaWeight(wx, wz);
        float glacierW = noise.sampleGlacierWeight(wx, wz);

        float elevationEst = terrain * 0.5f;
        float temperature = climate.sampleTemperature(wx, wz, elevationEst);
        float moisture = climate.sampleMoisture(wx, wz, continent01, elevationEst, temperature);

        float rf = noise.sampleRidge(wx, wz);
        float cf = noise.sampleCellNoise(wx, wz);
        float hf = noise.sampleTerrainHills(wx, wz);
        float gf = noise.sampleGullyErosion(wx, wz);
        float detail = rf * 0.42f + cf * 0.33f + hf * 0.15f + gf * 0.06f;
        float baseType = terrain * 0.5f + detail * 0.5f;
        baseType = Math.min(1f, baseType);

        float pAmt = smoothstep(plateauW);
        float pThresh = 0.4f + relief * 0.2f;
        float pLift = 0f;
        if (baseType > pThresh && pAmt > 0.01f) {
            float excess = (baseType - pThresh) / (1f - pThresh);
            pLift = ((pThresh + excess * 0.3f) - baseType) * pAmt;
        }

        float kAmt = smoothstep(karstW) * (1f - smoothstep(continent / 0.5f))
                   * smoothstep(relief - 0.3f) * (1f - smoothstep((relief - 0.8f) / 0.2f));
        float kLift = 0f;
        if (kAmt > 0.01f) {
            float peak = Math.max(0f, noise.sampleTerrainDetail(wx, wz)) * terrain * 0.6f;
            kLift = peak * kAmt;
        }

        float dAmt = smoothstep(danxiaW) * smoothstep(temperature - 0.55f) * smoothstep(1f - moisture)
                   * smoothstep(baseType - 0.2f) * smoothstep(terrain * terrain);
        float dStep = 0f;
        if (dAmt > 0.01f) {
            float lh = 0.025f;
            float stepped = Math.round(baseType / lh) * lh;
            float slope = Math.abs(terrain - noise.sampleTerrainBase(wx + 2, wz));
            dStep = (stepped - baseType) * dAmt * smoothstep(slope * 10f);
        }

        float gAmt = smoothstep(glacierW) * smoothstep(1f - temperature) * smoothstep(relief - 0.6f);
        float gMod = 0f;
        if (gAmt > 0.01f) {
            float valley = noise.sampleValleyLarge(wx, wz);
            float vc = 1f - Math.abs(valley * 2f - 1f);
            gMod = (vc * 0.12f - Math.max(0f, baseType - 0.7f) * vc * 0.3f) * gAmt * 0.5f;
        }

        float shaped = baseType + pLift + kLift + dStep + gMod + plate.uplift();
        shaped = Math.max(0f, Math.min(1f, shaped));

        float crust = plate.crustalThickness();
        float transition = smoothstep(Math.max(0f, (crust - 0.3f) / 0.2f));

        float inlandFactor = smoothstep(Math.max(0f, crust - 0.25f) / 0.75f);
        shaped = Math.min(1f, shaped * (0.15f + inlandFactor * 1.15f));

        float worldHeight = (float)(maxY - MIN_Y);
        float terrainRange = 1f - seaNorm;
        float shapeHeight = shaped * terrainRange * 0.55f;
        float landHeight = seaNorm + shapeHeight;

        float oceanDepth = odFactor * (1f - transition);
        float oceanHeight = seaNorm - oceanDepth;

        return Math.max(0f, Math.min(1f, oceanHeight * (1f - transition) + landHeight * transition));
    }

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static void drawLabel(BufferedImage img, String text, int x, int y) {
        for (int i = 0; i < text.length(); i++)
            for (int dy = 0; dy < 8; dy++)
                for (int dx = 0; dx < 6; dx++) {
                    int px = x + i * 7 + dx;
                    int py = y + dy;
                    if (px < img.getWidth() && py < img.getHeight())
                        img.setRGB(px, py, 0xCCCCCC);
                }
    }

    static int heightToColor(float h, float min, float range) {
        float t = (h - min) / range;
        t = Math.max(0, Math.min(1, t));
        if (t < 0.15f) {
            int b = (int) (t / 0.15f * 80 + 40);
            return (0 << 16) | (0 << 8) | b;
        }
        if (t < 0.35f) {
            float tt = (t - 0.15f) / 0.20f;
            int g = (int) (tt * 160 + 40);
            int b = (int) (120 - tt * 80);
            return (0 << 16) | (g << 8) | b;
        }
        if (t < 0.45f) {
            int r = (int) ((t - 0.35f) / 0.10f * 80 + 20);
            int g = (int) ((t - 0.35f) / 0.10f * 60 + 140);
            return (r << 16) | (g << 8) | 10;
        }
        if (t < 0.70f) {
            float tt = (t - 0.45f) / 0.25f;
            int c = (int) (tt * 60 + 100);
            return (c << 16) | ((c - 10) << 8) | (c - 30);
        }
        int c = (int) Math.min(255, (t - 0.70f) / 0.30f * 100 + 160);
        return (c << 16) | (c << 8) | c;
    }

    static int deltaToColor(float delta, float maxDelta) {
        float t = maxDelta > 0 ? Math.abs(delta) / maxDelta : 0;
        t = Math.min(1, t);
        int v = (int) (t * 255);
        if (delta > 0) return (v << 16);
        if (delta < 0) return (v << 8);
        return 0x444444;
    }
}
