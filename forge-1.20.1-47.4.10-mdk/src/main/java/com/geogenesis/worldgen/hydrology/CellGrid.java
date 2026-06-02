package com.geogenesis.worldgen.hydrology;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class CellGrid {

    public static final int CELL_SPACING = 200;
    private static final float JITTER = 0.75f;
    private static final float THRESHOLD = 0.525f;
    private static final int CACHE_SIZE = 2048;

    private static final int[] DIR_X = {1, 0, -1, 0};
    private static final int[] DIR_Z = {0, 1, 0, -1};

    private final int seed;
    private final float jitter;
    private final ImprovedNoise noiseGen;
    private final int sampleSeed;

    private final Map<Long, CellPoint> cache = new ConcurrentHashMap<>();

    public CellGrid(int baseSeed) {
        RandomSource rng = RandomSource.create(baseSeed + 8657);
        this.seed = baseSeed + 12345;
        this.sampleSeed = baseSeed + 6569;
        this.jitter = JITTER;
        this.noiseGen = new ImprovedNoise(RandomSource.create(rng.nextLong()));
    }

    public CellPoint getCell(int cx, int cy) {
        long key = pack(cx, cy);
        return cache.computeIfAbsent(key, k -> computeCell(key));
    }

    public long getNearestCell(float wx, float wy) {
        int minX = floor(wx / CELL_SPACING) - 1;
        int minY = floor(wy / CELL_SPACING) - 1;
        int maxX = minX + 2;
        int maxY = minY + 2;

        long nearest = 0;
        float bestDist = Float.MAX_VALUE;

        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                var cell = getCell(cx, cy);
                float dx = wx - cell.px;
                float dy = wy - cell.py;
                float d2 = dx * dx + dy * dy;
                if (d2 < bestDist) {
                    bestDist = d2;
                    nearest = pack(cx, cy);
                }
            }
        }
        return nearest;
    }

    public float getThresholdValue(CellPoint cell) {
        return cell.noise < THRESHOLD ? 0f : 1f;
    }

    public boolean isLand(CellPoint cell) {
        return getThresholdValue(cell) > 0;
    }

    public float toCellCoordX(float x) { return x / CELL_SPACING; }
    public float toCellCoordZ(float z) { return z / CELL_SPACING; }

    public int[] dirsX() { return DIR_X; }
    public int[] dirsZ() { return DIR_Z; }

    private CellPoint computeCell(long key) {
        int cx = unpackLeft(key);
        int cz = unpackRight(key);

        int hash = hash(seed, cx, cz);
        float px = getCellX(hash, cx, jitter);
        float py = getCellY(hash, cz, jitter);

        float freq = CELL_SPACING / 4000f;
        float sampleX = px * freq;
        float sampleY = py * freq;

        float sum = 0, sumAmp = 0, amp = 1;
        float baseNoiseY = sampleSeed * 0.001f;

        for (int i = 0; i < 2; i++) {
            float yOff = baseNoiseY + i * 7.3f;
            sum += (float) noiseGen.noise(sampleX, yOff, sampleY) * amp;
            sumAmp += amp;
            amp *= 0.3f;
            sampleX *= 2.75f;
            sampleY *= 2.75f;
        }

        float noise = sum / sumAmp;
        return new CellPoint(px, py, noise);
    }

    private static float getCellX(int hash, int cx, float jitter) {
        return cx + (rand(hash) - 0.5f) * 2f * jitter;
    }

    private static float getCellY(int hash, int cy, float jitter) {
        return cy + (rand(hash * 7 + 31) - 0.5f) * 2f * jitter;
    }

    public static int hash(int seed, int x, int z) {
        int h = seed;
        h ^= x * 374761393;
        h ^= z * 668265263;
        h = h * 1274126177;
        return h;
    }

    public static float rand(int n) {
        n ^= 1619;
        n ^= 31337;
        float value = (n * n * n * 0xec4d) / 2.14748365E9f;
        return value < 0 ? value + 2 : value;
    }

    private static int floor(float f) {
        return f < 0 ? (int) f - 1 : (int) f;
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackLeft(long key) {
        return (int) (key >> 32);
    }

    public static int unpackRight(long key) {
        return (int) key;
    }

    public static class CellPoint {
        public final float px, py;
        public final float noise;

        CellPoint(float px, float py, float noise) {
            this.px = px;
            this.py = py;
            this.noise = noise;
        }

        public float noise() { return noise; }
    }
}
