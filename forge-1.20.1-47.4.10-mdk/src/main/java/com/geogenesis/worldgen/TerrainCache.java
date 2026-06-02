package com.geogenesis.worldgen;

import com.geogenesis.worldgen.climate.ClimateSystem;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.geology.GeologySystem;
import com.geogenesis.worldgen.hydrology.HydrologySystem;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TerrainCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerrainCache.class);

    private static final int CACHE_SIZE = 500;
    // TerraForged-style sliding window: 7×7 tile, output center 3×3 chunks (9 chunks/tile)
    // tileRadius=3 → tile covers [cx-3, cx+3] = 7 chunks = 112×112 blocks
    // outputRadius=1 → output [cx-1, cx+1] = 3×3 chunks, 9 chunks per tile (7× faster than 1)
    // margin = 2 chunks (32 blocks), particles rarely reach boundary from center zone
    private static final int TILE_HALF_CHUNKS = 3;
    private static final int TILE_CHUNKS = TILE_HALF_CHUNKS * 2 + 1; // 7 chunks
    private static final int TILE_BLOCKS = TILE_CHUNKS * 16;         // 112 块
    private static final int OUTPUT_RADIUS = 1;                       // 输出 [cx-1, cx+1]
    private static final int OUTPUT_DIM = OUTPUT_RADIUS * 2 + 1;      // 3 chunks
    private static final int CHUNK_BLOCKS = 16;

    private static final float OCEAN_THRESHOLD = 0.08f;
    private static final float LAND_THRESHOLD = 0.18f;

    // chunk 级侵蚀高度图缓存：key = chunk position, value = 16×16 侵蚀后高度图
    // 多级缓存：每级独立，同级边缘对接（14/28/56/112）
    // key = tile center chunk position
    private final Map<Long, float[][]> cacheL0 = new ConcurrentHashMap<>(); // 14×14
    private final Map<Long, float[][]> cacheL1 = new ConcurrentHashMap<>(); // 28×28
    private final Map<Long, float[][]> cacheL2 = new ConcurrentHashMap<>(); // 56×56
    private final Map<Long, float[][]> chunkErosionCache = new ConcurrentHashMap<>(); // 16×16 chunks
    // continent 预计算缓存（避免 compute 中重复 sampleContinentRaw）
    private final Map<Long, float[][]> continentCache = new ConcurrentHashMap<>(); // 16×16 chunks
    // 完整 TerrainData 缓存
    private final Map<Long, TerrainData> cache = new ConcurrentHashMap<>();
    private final NoiseEngine noiseEngine;
    private final ErosionEngine erosionEngine;
    private final ClimateSystem climateSystem;
    private final GeologySystem geologySystem;
    private final HydrologySystem hydrologySystem;
    private final int seaLevel;
    private final int minY;
    private final int maxY;
    private final float seaLevelNorm;

    public TerrainCache(NoiseEngine noiseEngine, ErosionEngine erosionEngine,
                         int seaLevel, int minY, int maxY, int baseHeight) {
        this.noiseEngine = noiseEngine;
        this.erosionEngine = erosionEngine;
        this.climateSystem = new ClimateSystem(noiseEngine.getSeed());
        this.geologySystem = new GeologySystem();
        this.hydrologySystem = new HydrologySystem(noiseEngine.getSeed(), noiseEngine);
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.maxY = maxY;
        this.seaLevelNorm = (float)(seaLevel - minY) / (maxY - minY);
    }

    public TerrainData get(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        TerrainData data = cache.get(key);
        if (data != null) return data;
        data = compute(chunkX, chunkZ);
        if (cache.size() >= CACHE_SIZE) {
            cache.entrySet().removeIf(entry -> cache.size() >= CACHE_SIZE * 0.8);
        }
        cache.putIfAbsent(key, data);
        return cache.get(key);
    }

    /**
     * TerraForged-style sliding window: 以 chunk (cx, cz) 为中心生成 7×7 tile（112×112 blocks），
     * 侵蚀后缓存中心 3×3 chunks（共 9 个）。
     *
     * 相邻 tile 间距 1 chunk，重叠约 86%（6/7 chunks），
     * 边界差异在大量重叠区域中被稀释。
     *
     * 批量输出 9 个 chunk 而不是 1 个，大幅减少侵蚀次数（~7× faster）。
     */
    /** 金字塔侵蚀+同级缓存边缘对接。
     *  14级锁14级缓存，28锁28，56锁56。112级提取16×16 chunks。
     *  相邻tile（偏移±1 chunk）同级输出边缘锁定。 */
    // ★DIAG开关: true=原始侵蚀管线, false=纯FBM无侵蚀
    private static final boolean ENABLE_EROSION = true;

    private void generateErosionTile(int centerChunkX, int centerChunkZ) {
        long tileKey = ChunkPos.asLong(centerChunkX, centerChunkZ);
        int tileStartX = (centerChunkX - TILE_HALF_CHUNKS) * CHUNK_BLOCKS;
        int tileStartZ = (centerChunkZ - TILE_HALF_CHUNKS) * CHUNK_BLOCKS;

        float[][] raw = new float[TILE_BLOCKS][TILE_BLOCKS];
        float[][] continentRaw = new float[TILE_BLOCKS][TILE_BLOCKS];
        for (int z = 0; z < TILE_BLOCKS; z++)
            for (int x = 0; x < TILE_BLOCKS; x++) {
                int wx = tileStartX + x, wz = tileStartZ + z;
                continentRaw[z][x] = noiseEngine.sampleContinentRaw(wx, wz);
                raw[z][x] = computeHeightWithContinent(continentRaw[z][x], wx, wz);
            }

        int[] res = {14, 28, 56, 112};
        float[][] h = null;

        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];
            int outStartPx = (TILE_HALF_CHUNKS - OUTPUT_RADIUS) * CHUNK_BLOCKS * curRes / TILE_BLOCKS;
            int outWidthPx = OUTPUT_DIM * CHUNK_BLOCKS * curRes / TILE_BLOCKS;

            if (li == 0) {
                int scale = TILE_BLOCKS / curRes;
                h = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++) {
                        float s = 0; int n = 0;
                        for (int dz = 0; dz < scale; dz++)
                            for (int dx = 0; dx < scale; dx++) { s += raw[z*scale+dz][x*scale+dx]; n++; }
                        h[z][x] = s / n;
                    }
            } else {
                h = erosionEngine.bilinearUpsample(h, res[li-1], curRes);
            }

            int[] brushPer = {3, 4, 4};
            int blendW = li < 3 ? brushPer[li] : 1;
            int adjChunks = 3;
            int delta = adjChunks * CHUNK_BLOCKS * curRes / TILE_BLOCKS;
            if (li < res.length - 1) {
                // FBM噪声适配：侵蚀强度减半(去掉*1.5倍增器)
                float[] strPer = {1.5f, 1.2f, 1.0f};
                float[] erodePer = {0.3f, 0.2f, 0.15f};
                float[] depositPer = {0.04f, 0.03f, 0.02f};
                int[] dropsPer = {5000, 6000, 4000};
                int brushR = brushPer[li];
                int pad = Math.max(brushR * 2, 4);
                float[][] p = erosionEngine.padMirror(h, curRes, pad);
                erosionEngine.pyramidErosion(p, curRes+pad*2, dropsPer[li], strPer[li],
                    brushR, 0.5f, 0.001f, 2.5f, erodePer[li], depositPer[li],
                    tileStartX, tileStartZ, pad, curRes, TILE_BLOCKS / curRes, null, 1);
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z+pad], pad, h[z], 0, curRes);
            }

            // 侵蚀后余弦加权混合
            if (li < res.length - 1) {
                Map<Long, float[][]> lvlCache = li == 0 ? cacheL0 : li == 1 ? cacheL1 : cacheL2;
                float[][] left = lvlCache.get(ChunkPos.asLong(centerChunkX - adjChunks, centerChunkZ));
                if (left != null) {
                    for (int col = 0; col < blendW; col++) {
                        int d = outStartPx + col, s = outStartPx + delta + col;
                        if (d >= curRes || s >= curRes) break;
                        float w = 0.5f*(1f+(float)Math.cos((1f-(float)col/blendW)*Math.PI));
                        for (int i = outStartPx; i < outStartPx+outWidthPx && i < curRes; i++)
                            h[i][d] = h[i][d]*(1-w) + left[i][s]*w;
                    }
                }
                float[][] right = lvlCache.get(ChunkPos.asLong(centerChunkX + adjChunks, centerChunkZ));
                if (right != null) {
                    for (int col = 0; col < blendW; col++) {
                        int d = outStartPx+outWidthPx-blendW+col, s = outStartPx+col;
                        if (d >= curRes || s >= curRes) break;
                        float w = 0.5f*(1f+(float)Math.cos((float)col/blendW*Math.PI));
                        for (int i = outStartPx; i < outStartPx+outWidthPx && i < curRes; i++)
                            h[i][d] = h[i][d]*(1-w) + right[i][s]*w;
                    }
                }
                float[][] up = lvlCache.get(ChunkPos.asLong(centerChunkX, centerChunkZ - adjChunks));
                if (up != null) {
                    for (int row = 0; row < blendW; row++) {
                        int d = outStartPx+row, s = outStartPx+delta+row;
                        if (d >= curRes || s >= curRes) break;
                        float w = 0.5f*(1f+(float)Math.cos((1f-(float)row/blendW)*Math.PI));
                        for (int i = outStartPx; i < outStartPx+outWidthPx && i < curRes; i++)
                            h[d][i] = h[d][i]*(1-w) + up[s][i]*w;
                    }
                }
                float[][] down = lvlCache.get(ChunkPos.asLong(centerChunkX, centerChunkZ + adjChunks));
                if (down != null) {
                    for (int row = 0; row < blendW; row++) {
                        int d = outStartPx+outWidthPx-blendW+row, s = outStartPx+row;
                        if (d >= curRes || s >= curRes) break;
                        float w = 0.5f*(1f+(float)Math.cos((float)row/blendW*Math.PI));
                        for (int i = outStartPx; i < outStartPx+outWidthPx && i < curRes; i++)
                            h[d][i] = h[d][i]*(1-w) + down[s][i]*w;
                    }
                }
            }

            if (li == 0) cacheL0.put(tileKey, copyGrid(h, curRes));
            else if (li == 1) cacheL1.put(tileKey, copyGrid(h, curRes));
            else if (li == 2) cacheL2.put(tileKey, copyGrid(h, curRes));
        }

        int outRes = res[res.length - 1];
        int outputStart = (TILE_HALF_CHUNKS - OUTPUT_RADIUS) * CHUNK_BLOCKS;
        for (int doz = 0; doz < OUTPUT_DIM; doz++)
            for (int dox = 0; dox < OUTPUT_DIM; dox++) {
                int cx = centerChunkX - OUTPUT_RADIUS + dox;
                int cz = centerChunkZ - OUTPUT_RADIUS + doz;
                long ck = ChunkPos.asLong(cx, cz);
                if (!chunkErosionCache.containsKey(ck)) {
                    float[][] chunk = new float[CHUNK_BLOCKS][CHUNK_BLOCKS];
                    float[][] cont = new float[CHUNK_BLOCKS][CHUNK_BLOCKS];
                    for (int lz = 0; lz < CHUNK_BLOCKS; lz++)
                        for (int lx = 0; lx < CHUNK_BLOCKS; lx++) {
                            chunk[lz][lx] = h[outputStart+doz*CHUNK_BLOCKS+lz][outputStart+dox*CHUNK_BLOCKS+lx];
                            cont[lz][lx] = continentRaw[outputStart+doz*CHUNK_BLOCKS+lz][outputStart+dox*CHUNK_BLOCKS+lx];
                        }
                    chunkErosionCache.putIfAbsent(ck, chunk);
                    continentCache.putIfAbsent(ck, cont);
                }
            }
    }

    float[][] copyGrid(float[][] src, int size) {
        float[][] dst = new float[size][size];
        for (int z = 0; z < size; z++) System.arraycopy(src[z], 0, dst[z], 0, size);
        return dst;
    }
    /**
     * 获取某 chunk 的侵蚀后高度图。固定网格：tile 中心 = 最近的 3 倍数。
     * 每 tile 输出 3×3，与相邻 tile 恰好无重叠拼接。每个 chunk 只属于 1 个 tile。
     */
    private float[][] getChunkErosionHeightmap(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        float[][] chunkMap = chunkErosionCache.get(key);
        if (chunkMap != null) return chunkMap;

        // 固定网格：tile 中心每 3 chunk 一个（有/无侵蚀都走tile系统保证连续性）
        // Math.floorDiv 确保负坐标正确处理，避免 Math.round 导致的 tile 边界偏移
        int tileCX = Math.floorDiv(chunkX, 3) * 3;
        int tileCZ = Math.floorDiv(chunkZ, 3) * 3;

        generateErosionTile(tileCX, tileCZ);

        chunkMap = chunkErosionCache.get(key);
        if (chunkMap == null) {
            // 安全检查：如果 generateErosionTile 后仍然没有该 chunk 的数据
            // 可能是因为 tile 边界计算有误。回退到直接生成。
            float[][] fallback = new float[CHUNK_BLOCKS][CHUNK_BLOCKS];
            int startX = chunkX << 4;
            int startZ = chunkZ << 4;
            for (int lz = 0; lz < CHUNK_BLOCKS; lz++) {
                for (int lx = 0; lx < CHUNK_BLOCKS; lx++) {
                    fallback[lz][lx] = computeHeight(startX + lx, startZ + lz);
                }
            }
            chunkErosionCache.putIfAbsent(key, fallback);
            return fallback;
        }
        return chunkMap;
    }

    /** 获取预计算的 continent 值（与侵蚀高度图缓存同步生成） */
    private float[][] getChunkContinent(int chunkX, int chunkZ) {
        return continentCache.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * 获取连续侵蚀后高度。
     * 每个 tile 为 7×7 chunks（112×112），批量输出中心 3×3 chunks。
     * 相邻 tiles 高度重叠，边界自然连续。
     */
    private float getHeightContinuous(int worldX, int worldZ) {
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        float[][] chunkMap = getChunkErosionHeightmap(cx, cz);
        int lx = worldX & 15;
        int lz = worldZ & 15;
        return chunkMap[lz][lx];
    }

    private TerrainData compute(int chunkX, int chunkZ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        // 触发侵蚀 tile 生成（7×7 tile，批量输出 3×3 chunks）
        getChunkErosionHeightmap(chunkX, chunkZ);

        TerrainData data = new TerrainData(16, 16);
        float[][] cachedContinent = getChunkContinent(chunkX, chunkZ);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int wx = startX + dx;
                int wz = startZ + dz;

                float h = getHeightContinuous(wx, wz);
                h = Math.max(0f, Math.min(1f, h));
                int th = minY + Math.round(h * (maxY - minY));
                if (th < minY) th = minY;
                if (th >= maxY) th = maxY - 1;
                data.heightMap[dz][dx] = th;

                data.continentNoise[dz][dx] = cachedContinent != null ? cachedContinent[dz][dx] : noiseEngine.sampleContinentRaw(wx, wz);
                data.temperatureNoise[dz][dx] = climateSystem.sampleTemperature(wx, wz, 0);
                data.moistureNoise[dz][dx] = climateSystem.sampleMoisture(wx, wz, 0.5f, 0, 0.5f);

                float elev = (float)(th - minY) / (maxY - minY);
                data.precipitation[dz][dx] = hydrologySystem.calculatePrecipitation(
                    data.temperatureNoise[dz][dx], data.moistureNoise[dz][dx], elev);

                float riverN = hydrologySystem.sampleRiverNoise(wx, wz);
                float slope = Math.abs(noiseEngine.sampleTerrainBase(wx + 1, wz)
                                     - noiseEngine.sampleTerrainBase(wx, wz));
                data.riverStrength[dz][dx] = hydrologySystem.calculateRiverStrength(
                    data.precipitation[dz][dx], elev, slope);

                data.erosionNoise[dz][dx] = hydrologySystem.calculateHydraulicErosion(
                    data.precipitation[dz][dx], data.riverStrength[dz][dx], slope);
                data.riverDepth[dz][dx] = sampleRiverDepth(wx, wz);

                // 使用侵蚀后高度计算slope（通过 chunk 级缓存，跨 chunk 正确）
                float hC = getHeightContinuous(wx, wz);
                float hR = getHeightContinuous(wx + 1, wz);
                float hD = getHeightContinuous(wx, wz + 1);
                data.slope[dz][dx] = Math.max(Math.abs(hR - hC), Math.abs(hD - hC));
            }
        }
        return data;
    }

    private float computeOceanDepth(float continent) {
        if (continent >= OCEAN_THRESHOLD) return 0f;
        float t = (continent + 1.0f) / (OCEAN_THRESHOLD + 1.0f);
        t = Math.max(0f, Math.min(1f, t));
        float maxOceanDepth = 32f;
        float maxDepthNorm = maxOceanDepth / (maxY - minY);
        return maxDepthNorm * (1f - smoothstep(t));
    }

    private float computeContinentLift(float continent) {
        if (continent <= OCEAN_THRESHOLD) return 0f;
        float t = (continent - OCEAN_THRESHOLD) / (1.0f - OCEAN_THRESHOLD);
        return Math.min(1f, t) * 0.05f;
    }

    private float computeHeight(int worldX, int worldZ) {
        float continent = noiseEngine.sampleContinentRaw(worldX, worldZ);
        return computeHeightWithContinent(continent, worldX, worldZ);
    }

    /** 使用预计算的 continent 值计算高度，避免重复 sampleContinentRaw 调用 */
    private float computeHeightWithContinent(float continent, int worldX, int worldZ) {
        float terrainShape = noiseEngine.sampleTerrainBase(worldX, worldZ);
        float shapeNorm = terrainShape;
        float continentLift = computeContinentLift(continent);
        float continentBase = Math.max(0f, (continent - OCEAN_THRESHOLD) / (1f - OCEAN_THRESHOLD));
        float baseLevel = seaLevelNorm + continentBase * (1.0f - seaLevelNorm) * 0.05f;
        float terrainVariation = shapeNorm * (1.0f - seaLevelNorm) * 0.8f;
        float landHeight = baseLevel + terrainVariation + continentLift;
        float oceanDepth = computeOceanDepth(continent);
        float oceanHeight = seaLevelNorm - oceanDepth;
        float landMask = continentToLandMask(continent);
        float height = oceanHeight * (1.0f - landMask) + landHeight * landMask;
        return Math.max(0f, Math.min(1f, height));
    }

    private float continentToLandMask(float continent) {
        if (continent <= OCEAN_THRESHOLD) return 0f;
        if (continent >= LAND_THRESHOLD) return 1f;
        float t = (continent - OCEAN_THRESHOLD) / (LAND_THRESHOLD - OCEAN_THRESHOLD);
        return smoothstep(t);
    }

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private float sampleRiverDepth(int worldX, int worldZ) {
        float riverNoise = hydrologySystem.sampleRiverNoise(worldX, worldZ);
        float height = noiseEngine.sampleTerrainBase(worldX, worldZ);
        if (height > seaLevelNorm && height < seaLevelNorm + 0.15f && riverNoise > 0.65f) {
            return (riverNoise - 0.65f) / 0.35f * 0.03f;
        }
        return 0f;
    }

    public static class TerrainData {
        public final int[][] heightMap;
        public final float[][] continentNoise, heightNoise, detailNoise, erosionNoise,
                              temperatureNoise, moistureNoise, riverStrength, precipitation,
                              riverDepth, slope;
        public final int[][] rockType;

        public TerrainData(int w, int d) {
            heightMap = new int[d][w];
            continentNoise = new float[d][w];
            heightNoise = new float[d][w];
            detailNoise = new float[d][w];
            erosionNoise = new float[d][w];
            temperatureNoise = new float[d][w];
            moistureNoise = new float[d][w];
            rockType = new int[d][w];
            riverStrength = new float[d][w];
            precipitation = new float[d][w];
            riverDepth = new float[d][w];
            slope = new float[d][w];
        }

        public int getHeight(int x, int z) {
            if (x < 0 || z < 0 || x >= heightMap[0].length || z >= heightMap.length) return 0;
            return heightMap[z][x];
        }

        public float getRiverDepth(int x, int z) {
            if (x < 0 || z < 0 || x >= (riverDepth.length > 0 ? riverDepth[0].length : 0)
                || z >= riverDepth.length) return 0;
            return riverDepth[z][x];
        }

        public float getSlope(int x, int z) {
            if (x < 0 || z < 0 || x >= slope[0].length || z >= slope.length) return 0;
            return slope[z][x];
        }
    }
}
