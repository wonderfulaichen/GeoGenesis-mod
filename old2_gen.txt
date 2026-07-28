package com.geogenesis.worldgen;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.biome.ClimateBiomeMapper;
import com.geogenesis.worldgen.biome.GeoGenesisBiomeSource;
import com.geogenesis.worldgen.climate.ClimateSystem;
import com.geogenesis.worldgen.erosion.ErosionEngine;
import com.geogenesis.worldgen.geology.GeologySystem;
import com.geogenesis.worldgen.geology.PlateTectonics;
import com.geogenesis.worldgen.hydrology.HydrologySystem;
import com.geogenesis.worldgen.hydrology.SimpleHydrologyEngine;
import com.geogenesis.worldgen.MaterialMapper;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GeoGenesis 地形生成器
 * 
 * 继承 ChunkGenerator（与 TerraForged 一致）
 * 关键修复：从 RandomState 获取种子（而非 ServerLifecycleHooks）
 */
public class GeoGenesisGenerator extends ChunkGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeoGenesisGenerator.class);

    public static final Codec<GeoGenesisGenerator> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    GeoLevels.CODEC.fieldOf("levels").forGetter(g -> g.levels)
            ).apply(instance, instance.stable(GeoGenesisGenerator::new)));

    private final BiomeSource biomeSource;
    private final GeoLevels levels;

    private volatile NoiseEngine noiseEngine;
    private volatile ErosionEngine erosionEngine;
    private volatile SimpleHydrologyEngine simpleHydrologyEngine;
    private volatile MaterialMapper materialMapper;
    private volatile GeologySystem geologySystem;
    private volatile PlateTectonics plateSystem;
    private volatile ClimateSystem climateSystem;
    private volatile HydrologySystem hydrologySystem;
    private volatile long worldSeed = 0;
    private volatile boolean initialized = false;

    // ===== 共享 tile 缓存（借鉴 ReTerraForged TileCache）=====
    // 3×3 chunk 组共享一次侵蚀计算，彻底消除 chunk 边界断裂
    private static final int TILE_CHUNKS = 3; // 每个 tile 包含 3×3 chunk
    private static final int TILE_BORDER = 52; // 侵蚀粒子边界（增大以支持跨tile discharge混合）
    private static final int MAX_TILE_CACHE = 256; // 最大缓存条目数，防内存泄漏
    private final ConcurrentHashMap<Long, TileEntry> tileCache = new ConcurrentHashMap<>();

    /** 缓存条目：高度数据 + discharge 图 + p88阈值 + 河流线段 + 时间戳，支持 TTL 过期清理 */
    private static class TileEntry {
        final float[][] data;
        final float[][] dischargeMap; // SimpleHydrology 粒子汇聚流量图
        final float dischargeP88; // 动态百分位阈值（V30风格）
        final java.util.List<RiverSegment> riverSegments; // 距离场雕刻用的河流线段
        volatile long lastAccess;
        TileEntry(float[][] data, float[][] dischargeMap, float dischargeP88,
                  java.util.List<RiverSegment> riverSegments) {
            this.data = data;
            this.dischargeMap = dischargeMap;
            this.dischargeP88 = dischargeP88;
            this.riverSegments = riverSegments;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    public GeoGenesisGenerator(BiomeSource biomeSource, GeoLevels levels) {
        super(biomeSource);
        this.biomeSource = biomeSource;
        this.levels = levels;
    }

    /**
     * 核心修复：从 RandomState 获取种子（与 TerraForged 一致）
     * RandomState 在 chunk 生成时总是可用的，不像 ServerLifecycleHooks 可能为 null
     */
    private void ensureInit(long seed) {
        if (initialized && this.worldSeed == seed) return;
        synchronized (this) {
            if (initialized && this.worldSeed == seed) return;
            worldSeed = seed;
            tileCache.clear(); // 种子变化时清空缓存
            float worldHeight = levels.maxY() - levels.minY();
            noiseEngine = new NoiseEngine((int)worldSeed);
            noiseEngine.setTerrainScale(worldHeight, levels.baseHeight());
            erosionEngine = new ErosionEngine(noiseEngine, (int)worldSeed);
            simpleHydrologyEngine = new SimpleHydrologyEngine((int)worldSeed);
            simpleHydrologyEngine.setErosionEnabled(false); // 水文粒子不侵蚀地形，只记录 discharge
            climateSystem = new ClimateSystem((int)worldSeed);
            hydrologySystem = new HydrologySystem((int)worldSeed, noiseEngine);
            hydrologySystem.setRiverEnabled(false); // 河流模拟已关闭，禁用 RiverBrushSystem 避免性能开销
            geologySystem = new GeologySystem();
            plateSystem = new PlateTectonics((int)worldSeed);
            materialMapper = new MaterialMapper(noiseEngine, hydrologySystem, worldSeed);

            if (biomeSource instanceof GeoGenesisBiomeSource geoBiomeSource) {
                geoBiomeSource.wire(climateSystem, hydrologySystem, (int)worldSeed);
            }
            initialized = true;
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender,
            RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        long seed = getSeed(randomState);
        ensureInit(seed);

        chunk.setLightCorrect(false);
        int minY = levels.minY();
        int cfgMaxY = GeoGenesisConfig.COMMON.maxY.get();
        int cfgSea = GeoGenesisConfig.COMMON.seaLevel.get();
        int cfgOcean = GeoGenesisConfig.COMMON.oceanDepthMax.get();
        int maxY = cfgMaxY > 0 ? cfgMaxY : levels.maxY();
        int seaLevel = cfgSea > 0 ? cfgSea : levels.seaLevel();
        float seaNorm = (float)(seaLevel - minY) / (maxY - minY);
        float odFactor = (float)cfgOcean * seaNorm / (seaLevel - (float)minY);

        ChunkPos pos = chunk.getPos();

        hydrologySystem.setTerrainParams(seaNorm, odFactor, maxY, minY);

        // === 共享 tile 缓存地形生成 ===
        // 每个 3×3 chunk 组共享一次噪声+侵蚀计算
        // 彻底消除 chunk 边界断裂：同一 tile 内所有 chunk 使用完全相同的侵蚀结果
        int chunkX = pos.x, chunkZ = pos.z;

        // 计算此 chunk 所属的 tile 原点（chunk 坐标）
        int tileCX = Math.floorDiv(chunkX, TILE_CHUNKS) * TILE_CHUNKS;
        int tileCZ = Math.floorDiv(chunkZ, TILE_CHUNKS) * TILE_CHUNKS;
        long tileKey = (long)tileCX << 32 | (tileCZ & 0xFFFFFFFFL);

        // 性能监测
        long tStart = System.nanoTime();
        long tNoise, tErosion, tRiver, tBlocks;

        // 获取或生成 tile（线程安全：computeIfAbsent 保证同一 key 只计算一次）
        int tileWorldX = tileCX << 4; // tile 左上角世界坐标
        int tileWorldZ = tileCZ << 4;
        int tileSize = TILE_CHUNKS * 16 + TILE_BORDER * 2; // 48+80=128
        int border = TILE_BORDER;

        TileEntry entry = tileCache.computeIfAbsent(tileKey, k -> {
            var result = generateTileWithHydrology(tileWorldX, tileWorldZ, tileSize, border, seaNorm, odFactor, maxY);
            return new TileEntry(result.heights(), result.discharge(), result.dischargeP88(),
                result.riverSegments());
        });
        entry.lastAccess = System.currentTimeMillis();

        // ===== Cross-Tile Discharge Blending =====
        // 解决相邻 tile 水文模拟种子不同导致的边界断裂
        // 对 overlap 区域的 discharge 做跨 tile 双向加权平均
        blendDischargeWithNeighbors(tileKey, entry, tileCX, tileCZ);

        // ===== Cross-Tile Height Blending =====
        // 解决侵蚀导致的 tile 边界微断裂
        // 侵蚀是 per-tile 独立的，边界处结果不一致 → 微断裂
        // 对 overlap 区域的高度值做跨 tile 双向加权平均
        blendHeightWithNeighbors(tileKey, entry, tileCX, tileCZ);

        float[][] tileBuf = entry.data;

        // 定期清理过期缓存（防内存泄漏）
        if (tileCache.size() > MAX_TILE_CACHE) {
            long now = System.currentTimeMillis();
            tileCache.entrySet().removeIf(e -> now - e.getValue().lastAccess > 120_000);
        }
        tNoise = tErosion = tRiver = System.nanoTime(); // 生成时间已计入 tile 缓存

        // 从 tile 缓存中提取当前 chunk 的高度数据
        int offsetX = (chunkX - tileCX) * 16; // chunk 在 tile 中的偏移
        int offsetZ = (chunkZ - tileCZ) * 16;

        float riverDepthCfg = (float)GeoGenesisConfig.COMMON.riverDepth.get().doubleValue();
        float[][] dischargeMap = entry.dischargeMap;
        float dischargeP88 = entry.dischargeP88;

        // ===== 距离场雕刻：河流线段 =====
        java.util.List<RiverSegment> riverSegments = entry.riverSegments;

        int bedrockTop = minY + 4;
        int[] blockHeights = new int[256];
        float[] origNormHeights = new float[256]; // 原始噪声高度（非侵蚀后），用于海洋水判断
        float[] erodedNormHeights = new float[256]; // 侵蚀后高度（carving前），用于河水截断

        // ===== per-pixel 距离场数据（第一遍记录，第二遍用于水面判定）=====
        float[] pxMinNormDist = new float[256];   // 归一化距离
        float[] pxBestDist = new float[256];       // 绝对距离（px）
        int[] pxBestSegIdx = new int[256];         // 最近线段索引，-1=无
        float[] pxBestT = new float[256];           // 线段参数 t (0~1)
        java.util.Arrays.fill(pxMinNormDist, Float.MAX_VALUE);
        java.util.Arrays.fill(pxBestSegIdx, -1);

        // 第一遍：距离场雕刻
        // 对每个像素计算到最近 RiverSegment 的距离 → normDist → U型截面雕刻
        // 距离场天然连续，无锯齿、无孤立像素、无需 blend
        int carvedPixelCount = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // ★ 修复：用原始噪声高度，而非侵蚀后的 tileBuf
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                float origH = computeHeight(worldX, worldZ, seaNorm, odFactor, maxY);
                origH = Math.max(0f, Math.min(1f, origH));
                origNormHeights[z * 16 + x] = origH;

                // 侵蚀后高度（用于地形高度）
                float h = tileBuf[offsetZ + z + border][offsetX + x + border];
                erodedNormHeights[z * 16 + x] = h; // 存储 carving 前的侵蚀后高度
                int height = minY + Math.round(h * (maxY - minY));
                height = Math.max(height, bedrockTop);
                if (height >= maxY) height = maxY - 1;

                // ===== 距离场雕刻 =====
                if (riverSegments != null && !riverSegments.isEmpty() && riverDepthCfg > 0) {
                    // 像素在 tile 局部坐标系中的位置
                    float px = offsetX + x + border;
                    float pz = offsetZ + z + border;

                    // 一次性遍历所有 RiverSegment，收集所有需要的距离场数据
                    float minNormDist = Float.MAX_VALUE;
                    float bestDepth = 0;
                    float bestDist = Float.MAX_VALUE;
                    int bestSegIdx = -1;
                    float bestT = 0;

                    for (int si = 0; si < riverSegments.size(); si++) {
                        RiverSegment seg = riverSegments.get(si);
                        float dx = seg.bx - seg.ax, dz = seg.bz - seg.az;
                        float len2 = dx * dx + dz * dz;
                        if (len2 < 0.01f) continue;
                        float t = ((px - seg.ax) * dx + (pz - seg.az) * dz) / len2;
                        t = Math.max(0f, Math.min(1f, t));
                        float projX = seg.ax + t * dx;
                        float projZ = seg.az + t * dz;
                        float distX = px - projX, distZ = pz - projZ;
                        float dist = (float)Math.sqrt(distX * distX + distZ * distZ);
                        float width = seg.aWidth + t * (seg.bWidth - seg.aWidth);
                        if (width < 0.5f) continue;
                        float normDist = dist / width;
                        if (normDist < minNormDist) {
                            minNormDist = normDist;
                            bestDepth = seg.aDepth + t * (seg.bDepth - seg.aDepth);
                            bestDist = dist;
                            bestSegIdx = si;
                            bestT = t;
                        }
                    }

                    // 距离场雕刻
                    if (minNormDist <= 1.0f) {
                        float carvedH;
                        if (minNormDist < 0.15f) {
                            // bed 层（河床中心）：最深
                            carvedH = h - bestDepth * riverDepthCfg;
                        } else if (minNormDist < 0.55f) {
                            // bank 层（岸坡）：从 bedLevel smoothstep 到 baseLevel
                            float t = (minNormDist - 0.15f) / (0.55f - 0.15f);
                            t = t * t * (3f - 2f * t); // smoothstep
                            carvedH = (h - bestDepth * riverDepthCfg) + bestDepth * riverDepthCfg * t;
                        } else {
                            // valley 层（河谷过渡）：轻微下凹
                            float t = (minNormDist - 0.55f) / (1.0f - 0.55f);
                            carvedH = h - bestDepth * riverDepthCfg * 0.15f * (1f - t);
                        }

                        carvedH = Math.max(carvedH, seaNorm - 0.02f);
                        int carvedHeight = minY + Math.round(carvedH * (maxY - minY));
                        carvedHeight = Math.max(carvedHeight, bedrockTop);

                        if (carvedHeight < height) {
                            height = carvedHeight;
                            carvedPixelCount++;
                        }

                        // ===== 水面数据记录（第二遍使用）=====
                        // 存储距离场数据，第二遍用于判定是否放水及水面高度
                        pxMinNormDist[z * 16 + x] = minNormDist;
                        pxBestDist[z * 16 + x] = bestDist;
                        pxBestSegIdx[z * 16 + x] = bestSegIdx;
                        pxBestT[z * 16 + x] = bestT;
                    }
                }

                blockHeights[z * 16 + x] = height;
            }
        }

        tBlocks = System.nanoTime();

        // [DEBUG] 距离场雕刻诊断日志
        if (riverSegments != null && !riverSegments.isEmpty() && riverDepthCfg > 0) {
            LOGGER.info("[DistField-Carve] chunk({},{}) carvedPx={} segments={} cfg={}",
                chunkX, chunkZ, carvedPixelCount, riverSegments.size(), riverDepthCfg);
        }

        // 第二遍：放置方块 + 水方块放置
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int height = blockHeights[z * 16 + x];
                float origNormH = origNormHeights[z * 16 + x];
                int origHeight = minY + Math.round(origNormH * (maxY - minY));
                origHeight = Math.max(origHeight, bedrockTop);

                for (int y = bedrockTop + 1; y < height && y < maxY; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), false);
                }

                // ===== 河水放置：填满河床到自然地面-1 =====
                // 水面 = 侵蚀后自然地形 - 1 格（水填满雕刻凹陷，到河岸下方1格停止）
                // 天然不会漫出河道：水面永远低于自然地面
                boolean isRiverWater = false;
                int segIdx = pxBestSegIdx[z * 16 + x];
                if (segIdx >= 0 && riverSegments != null) {
                    RiverSegment seg = riverSegments.get(segIdx);
                    float t = pxBestT[z * 16 + x];
                    float dist = pxBestDist[z * 16 + x];

                    // 水宽由 accumulation 决定（源头窄、下游宽）
                    float interpAccum = seg.aAccum + t * (seg.bAccum - seg.aAccum);
                    float waterRadius = waterWidthFromAccum(interpAccum);

                    if (dist < waterRadius && pxMinNormDist[z * 16 + x] <= 1.0f) {
                        // 自然地面 = 侵蚀后高度（carving 前），即河岸高度
                        int groundY = minY + Math.round(erodedNormHeights[z * 16 + x] * (maxY - minY));
                        // 水面 = 自然地面 - 2（比河岸低1格，避免与地形持平）
                        int waterSurfaceY = groundY - 2;
                        // 只在雕刻深度 >= 1 时放水（避免浅雕刻处水溢出）
                        if (waterSurfaceY >= height) {
                            for (int y = height; y <= waterSurfaceY && y < maxY; y++) {
                                chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), false);
                            }
                            isRiverWater = true;
                        }
                    }
                }

                // 海洋/湖泊水：用原始噪声高度判断
                if (!isRiverWater && origHeight <= seaLevel) {
                    for (int y = height + 1; y <= seaLevel && y < maxY; y++) {
                        if (chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), false);
                        }
                    }
                }
            }
        }

        // 输出性能日志
        long total = System.nanoTime() - tStart;
        LOGGER.info("[Perf] Chunk({},{}) total={}ms",
            pos.x, pos.z, total / 1_000_000);

        BlockState solid = Blocks.STONE.defaultBlockState();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int floor = blockHeights[z * 16 + x];
                int surface = Math.max(seaLevel, floor);
                oceanFloor.update(x, floor, z, solid);
                worldSurface.update(x, surface, z,
                    surface > seaLevel ? solid : Blocks.WATER.defaultBlockState());
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState state) {
        long seed = getSeed(state);
        ensureInit(seed);
        int cfgSea = GeoGenesisConfig.COMMON.seaLevel.get();
        int cfgMaxY = GeoGenesisConfig.COMMON.maxY.get();
        int maxY = cfgMaxY > 0 ? cfgMaxY : levels.maxY();
        int seaLevel = cfgSea > 0 ? cfgSea : levels.seaLevel();
        float seaNorm = (float)(seaLevel - levels.minY()) / (maxY - levels.minY());
        float od = (float)GeoGenesisConfig.COMMON.oceanDepthMax.get() * seaNorm / (seaLevel - (float)levels.minY());
        float h = computeHeight(x, z, seaNorm, od, maxY);
        int blockH = levels.minY() + Math.round(h * (maxY - levels.minY()));
        return switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> Math.max(blockH, seaLevel);
            case WORLD_SURFACE, WORLD_SURFACE_WG, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES -> blockH + 1;
        };
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState state) {
        long seed = getSeed(state);
        ensureInit(seed);
        int cfgSea = GeoGenesisConfig.COMMON.seaLevel.get();
        int cfgMaxY = GeoGenesisConfig.COMMON.maxY.get();
        int maxY = cfgMaxY > 0 ? cfgMaxY : levels.maxY();
        int seaLevel = cfgSea > 0 ? cfgSea : levels.seaLevel();
        float seaNorm = (float)(seaLevel - levels.minY()) / (maxY - levels.minY());
        float od = (float)GeoGenesisConfig.COMMON.oceanDepthMax.get() * seaNorm / (seaLevel - (float)levels.minY());
        float h = computeHeight(x, z, seaNorm, od, maxY);
        int blockH = levels.minY() + Math.round(h * (maxY - levels.minY()));
        BlockState[] states = new BlockState[Math.max(blockH, seaLevel)];
        for (int i = 0; i < blockH && i < states.length; i++) {
            states[i] = Blocks.STONE.defaultBlockState();
        }
        if (seaLevel > blockH) {
            for (int i = blockH; i < seaLevel && i < states.length; i++) {
                states[i] = Blocks.WATER.defaultBlockState();
            }
        }
        return new NoiseColumn(Math.min(blockH, states.length), states);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState state, ChunkAccess chunk) {
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState state,
            net.minecraft.world.level.biome.BiomeManager biomes, StructureManager structures,
            ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving stage) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        ChunkPos chunkPos = region.getCenter();
        var biomeHolder = region.getBiome(chunkPos.getWorldPosition().atY(region.getMaxBuildHeight() - 1));
        var random = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(region.getSeed()));
        random.setDecorationSeed(region.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
        net.minecraft.world.level.NaturalSpawner.spawnMobsForChunkGeneration(region, biomeHolder, chunkPos, random);
    }

    // ===== 共享 tile 生成（噪声 + 超分辨率插值 + 侵蚀 + 水文模拟）=====

    /** tile 生成结果：高度图 + discharge 图 + p88 + 河流线段 */
    private record TileHydroResult(float[][] heights, float[][] discharge, float dischargeP88,
                                    java.util.List<RiverSegment> riverSegments) {}

    /**
     * 生成一个完整的 tile 高度图（含噪声、插值、侵蚀、水文模拟）。
     * 侵蚀使用双层反向（先小后大），水文使用 SimpleHydrology 风格粒子汇聚。
     */
    private TileHydroResult generateTileWithHydrology(int tileWorldX, int tileWorldZ, int tileSize, int border,
                                                       float seaNorm, float odFactor, int maxY) {
        long tStart = System.nanoTime();

        // Step 1: 超分辨率噪声计算（1/4 分辨率，全局对齐网格）
        int spacing = 4;
        int lowRes = (tileSize + spacing - 1) / spacing;
        int alignedStartX = Math.floorDiv(tileWorldX - border, spacing) * spacing;
        int alignedStartZ = Math.floorDiv(tileWorldZ - border, spacing) * spacing;
        int tileStartX = tileWorldX - border;
        int tileStartZ = tileWorldZ - border;

        float[][] lowResBuf = new float[lowRes][lowRes];
        for (int tz = 0; tz < lowRes; tz++) {
            for (int tx = 0; tx < lowRes; tx++) {
                int wx = alignedStartX + tx * spacing;
                int wz = alignedStartZ + tz * spacing;
                PlateTectonics.PlateData plate = plateSystem.sample(wx, wz);
                lowResBuf[tz][tx] = Math.max(0f, Math.min(1f,
                    computeHeightWithPlate(plate, wx, wz, seaNorm, odFactor, maxY)));
            }
        }

        // Step 2: 双三次插值升采样到全分辨率
        float[][] tileBuf = bicubicUpsampleAligned(lowResBuf, lowRes, spacing,
            alignedStartX, alignedStartZ, tileStartX, tileStartZ, tileSize);
        long tNoise = System.nanoTime();

        // Step 3: 侵蚀（全分辨率，双层反向）
        float erosionStrength = (float)GeoGenesisConfig.COMMON.erosionStrength.get().doubleValue();
        if (erosionStrength > 0) {
            erosionEngine.applyErosionNormalized(tileBuf, tileSize,
                tileStartX, tileStartZ, seaNorm, erosionStrength);
        }
        long tErosion = System.nanoTime();

        // Step 4: 水文模拟（SimpleHydrologyEngine — 粒子汇聚河流）
        // 使用已有的 SimpleHydrologyEngine，它有完整的 discharge + momentum + cascade + valley spawn
        float[][] dischargeMap = null;
        float riverDepthCfg = (float)GeoGenesisConfig.COMMON.riverDepth.get().doubleValue();
        if (riverDepthCfg > 0 && simpleHydrologyEngine != null) {
            int hydroIterations = 50;
            int hydroDropsPerIter = 3500;
            dischargeMap = simpleHydrologyEngine.applyHydrology(
                tileBuf, tileSize, hydroIterations, hydroDropsPerIter, seaNorm,
                tileStartX, tileStartZ);
        }
        long tHydro = System.nanoTime();

        LOGGER.info("[Perf-Tile] tile({},{}) noise={}ms erosion={}ms hydro={}ms total={}ms size={}",
            tileWorldX >> 4, tileWorldZ >> 4,
            (tNoise - tStart) / 1_000_000,
            (tErosion - tNoise) / 1_000_000,
            (tHydro - tErosion) / 1_000_000,
            (tHydro - tStart) / 1_000_000,
            tileSize);

        // [DEBUG] 河流诊断日志
        float dischargeP88 = 0;
        if (dischargeMap != null) {
            // 计算 p88 动态百分位阈值（V30 风格：只有排名前 12% 的像素才显示为河）
            java.util.List<Float> allD = new java.util.ArrayList<>();
            for (int z = 0; z < tileSize; z++) {
                for (int x = 0; x < tileSize; x++) {
                    float d = dischargeMap[z][x];
                    if (d > 0) allD.add(d);
                }
            }
            if (!allD.isEmpty()) {
                java.util.Collections.sort(allD);
                dischargeP88 = allD.get((int)(allD.size() * 0.88f));
            }

            float maxD = 0, sumD = 0;
            int aboveP88 = 0;
            for (int z = 0; z < tileSize; z++) {
                for (int x = 0; x < tileSize; x++) {
                    float d = dischargeMap[z][x];
                    if (d > maxD) maxD = d;
                    sumD += d;
                    if (d > dischargeP88) aboveP88++;
                }
            }
            LOGGER.info("[River-Debug] tile({},{}) maxD={} avgD={} p88={} aboveP88={} riverDepthCfg={} seaNorm={}",
                tileWorldX >> 4, tileWorldZ >> 4,
                String.format("%.2f", maxD), String.format("%.4f", sumD / (tileSize*tileSize)),
                String.format("%.2f", dischargeP88), aboveP88, riverDepthCfg, seaNorm);
        } else {
            LOGGER.info("[River-Debug] tile({},{}) dischargeMap=NULL riverDepthCfg={}",
                tileWorldX >> 4, tileWorldZ >> 4, riverDepthCfg);
        }

        // ===== Cell Corridor 河流线段构建 =====
        // 用确定性 cell 流向网络 + 路径追踪产生显式河流路径
        // 路径输出为 RiverSegment 列表（线段 + 宽度 + 深度），不再光栅化 corridor
        java.util.List<RiverSegment> riverSegments = null;
        if (riverDepthCfg > 0) {
            riverSegments = buildRiverCorridor(tileBuf, tileSize, seaNorm, tileWorldX, tileWorldZ, border);
        }

        return new TileHydroResult(tileBuf, dischargeMap, dischargeP88, riverSegments);
    }

    private float computeHeight(int wx, int wz, float seaNorm, float oceanDepthFactor, int genMaxY) {
        return computeHeightWithPlate(plateSystem.sample(wx, wz), wx, wz, seaNorm, oceanDepthFactor, genMaxY);
    }

    /** 使用预计算的 plate 数据计算高度，避免重复 plateSystem.sample 调用 */
    private float computeHeightWithPlate(PlateTectonics.PlateData plate, int wx, int wz,
                                          float seaNorm, float oceanDepthFactor, int genMaxY) {

        float continent = noiseEngine.sampleContinentRaw(wx, wz);
        continent += plate.continentBias() * 0.25f;
        continent = Math.max(-1f, Math.min(1f, continent));

        float terrain = noiseEngine.sampleTerrainBase(wx, wz);
        float relief = noiseEngine.sampleElevation(wx, wz);
        float plateauW = noiseEngine.samplePlateauWeight(wx, wz);
        float karstW = noiseEngine.sampleKarstWeight(wx, wz);
        float glacierW = noiseEngine.sampleGlacierWeight(wx, wz);

        float rf = noiseEngine.sampleRidge(wx, wz);
        float cf = noiseEngine.sampleCellNoise(wx, wz);
        float hf = noiseEngine.sampleTerrainHills(wx, wz);
        float gf = noiseEngine.sampleGullyErosion(wx, wz);

        float detail = rf * 0.50f + cf * 0.28f + hf * 0.14f + gf * 0.08f;
        float baseType = terrain * 0.5f + detail * 0.5f;
        baseType = Math.min(1f, baseType);

        float plateauAmount = smoothstep(plateauW);
        float plateauThreshold = 0.4f + relief * 0.2f;
        float plateauLift = 0f;
        if (baseType > plateauThreshold && plateauAmount > 0.01f) {
            float excess = (baseType - plateauThreshold) / (1f - plateauThreshold);
            float flatTop = plateauThreshold + excess * 0.3f;
            plateauLift = (flatTop - baseType) * plateauAmount;
        }

        float karstAmount = smoothstep(karstW) * (1f - smoothstep(continent / 0.5f))
                          * smoothstep(relief - 0.3f) * (1f - smoothstep((relief - 0.8f) / 0.2f));
        float karstLift = 0f;
        if (karstAmount > 0.01f) {
            float peak = Math.max(0f, noiseEngine.sampleTerrainDetail(wx, wz) * terrain * 0.6f);
            karstLift = peak * karstAmount;
        }

        float glacierAmount = smoothstep(glacierW) * smoothstep(1f - terrain) * smoothstep(relief - 0.6f);
        float glacierMod = 0f;
        if (glacierAmount > 0.01f) {
            float valley = noiseEngine.sampleValleyLarge(wx, wz);
            float valleyCenter = 1f - Math.abs(valley * 2f - 1f);
            float uFill = valleyCenter * 0.12f;
            float peakCut = Math.max(0f, baseType - 0.7f) * valleyCenter * 0.3f;
            glacierMod = (uFill - peakCut) * glacierAmount * 0.5f;
        }

        float shaped = baseType + plateauLift + karstLift + glacierMod + plate.uplift();

        // 火山地形：cellular noise 散布 + 锥形高度 + 火山口凹陷
        // 只在陆地上生成火山（continent > 0 表示陆地）
        float volcanoW = noiseEngine.sampleVolcanoWeight(wx, wz);
        float volcanoAmount = smoothstep(volcanoW) * smoothstep(relief - 0.4f) * smoothstep(continent / 0.3f);
        float volcanoLift = 0f;
        if (volcanoAmount > 0.01f) {
            float volcanoH = noiseEngine.sampleVolcanoHeight(wx, wz);
            volcanoLift = volcanoH * volcanoAmount * 0.35f;
        }

        shaped = shaped + volcanoLift;
        shaped = Math.max(0f, Math.min(1f, shaped));

        float crust = plate.crustalThickness();
        float transition = smoothstep(Math.max(0f, (crust - 0.3f) / 0.2f));

        float amplitudeFactor = 0.06f + relief * relief * 1.6f;
        amplitudeFactor = Math.min(amplitudeFactor, 2.0f);

        float terrainRange = 1f - seaNorm;
        float shapeHeight = shaped * terrainRange * amplitudeFactor;
        shapeHeight = Math.min(shapeHeight, 1f - seaNorm);

        float landHeight = seaNorm + shapeHeight;

        float oceanDepth = oceanDepthFactor * (1f - transition);
        float oceanHeight = seaNorm - oceanDepth;

        float height = oceanHeight * (1f - transition) + landHeight * transition;

        return height;
    }

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // ===== 超分辨率：双三次插值（Catmull-Rom，全局对齐版） =====

    /**
     * 全局对齐的双三次插值：确保相邻chunk在边界处使用相同的控制点。
     *
     * 关键设计：插值坐标基于世界坐标而非tile内局部坐标。
     * 对于世界坐标(wx,wz)，计算其在全局低Res网格中的位置，
     * 然后查找对应的4×4控制点邻域。由于全局网格对所有chunk一致，
     * 相邻chunk在边界处必然使用相同的控制点 → 插值结果连续。
     *
     * @param lowRes 低分辨率高度图 [lowRes×lowRes]
     * @param lowResSize 低分辨率网格边长
     * @param spacing 低Res网格间距（世界单位）
     * @param alignedStartX 全局对齐的低Res网格起始X（世界坐标）
     * @param alignedStartZ 全局对齐的低Res网格起始Z（世界坐标）
     * @param tileStartX 当前tile起始X（世界坐标）
     * @param tileStartZ 当前tile起始Z（世界坐标）
     * @param tileSize 当前tile边长
     */
    private static float[][] bicubicUpsampleAligned(float[][] lowRes, int lowResSize, int spacing,
                                                     int alignedStartX, int alignedStartZ,
                                                     int tileStartX, int tileStartZ, int tileSize) {
        float[][] out = new float[tileSize][tileSize];
        int lrLast = lowResSize - 1;

        for (int fz = 0; fz < tileSize; fz++) {
            int worldZ = tileStartZ + fz;
            // 世界坐标 → 全局低Res网格坐标
            float lz = (float)(worldZ - alignedStartZ) / spacing;
            int iz = (int) lz;
            float tz = lz - iz;
            int z0 = Math.max(0, iz - 1), z1 = Math.min(lrLast, iz),
                z2 = Math.min(lrLast, iz + 1), z3 = Math.min(lrLast, iz + 2);

            for (int fx = 0; fx < tileSize; fx++) {
                int worldX = tileStartX + fx;
                float lx = (float)(worldX - alignedStartX) / spacing;
                int ix = (int) lx;
                float tx = lx - ix;
                int x0 = Math.max(0, ix - 1), x1 = Math.min(lrLast, ix),
                    x2 = Math.min(lrLast, ix + 1), x3 = Math.min(lrLast, ix + 2);

                // Catmull-Rom 水平插值
                float r0 = catmullRom(lowRes[z0][x0], lowRes[z0][x1], lowRes[z0][x2], lowRes[z0][x3], tx);
                float r1 = catmullRom(lowRes[z1][x0], lowRes[z1][x1], lowRes[z1][x2], lowRes[z1][x3], tx);
                float r2 = catmullRom(lowRes[z2][x0], lowRes[z2][x1], lowRes[z2][x2], lowRes[z2][x3], tx);
                float r3 = catmullRom(lowRes[z3][x0], lowRes[z3][x1], lowRes[z3][x2], lowRes[z3][x3], tx);

                // Catmull-Rom 垂直插值
                out[fz][fx] = catmullRom(r0, r1, r2, r3, tz);
            }
        }
        return out;
    }

    /** Catmull-Rom 样条插值核：4个控制点 + 参数 t ∈ [0,1] */
    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * (
            (2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
        );
    }

    private static long getSeed(RandomState state) {
        try {
            var field = state.getClass().getDeclaredField("levelSeed");
            field.setAccessible(true);
            return ((Number)field.get(state)).longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public Codec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public int getMinY() { return levels.minY(); }

    @Override
    public int getSeaLevel() {
        int cfg = GeoGenesisConfig.COMMON.seaLevel.get();
        return cfg > 0 ? cfg : levels.seaLevel();
    }

    @Override
    public int getGenDepth() {
        int cfg = GeoGenesisConfig.COMMON.maxY.get();
        return cfg > 0 ? cfg : levels.maxY();
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState state, BlockPos pos) {
        long seed = getSeed(state);
        ensureInit(seed);
        int wx = pos.getX(), wz = pos.getZ();
        float c = noiseEngine.sampleContinentRaw(wx, wz);
        float t = noiseEngine.sampleTerrainBase(wx, wz);
        float r = noiseEngine.sampleElevation(wx, wz);
        PlateTectonics.PlateData plate = plateSystem.sample(wx, wz);
        float temp = climateSystem.sampleTemperature(wx, wz, t * 0.5f);
        float moist = climateSystem.sampleMoisture(wx, wz, (c+1f)*0.5f, t*0.5f, temp);
        String type = r < 0.35f ? "平原" : r < 0.65f ? "丘陵" : "山脉";
        String mods = "";
        if (noiseEngine.samplePlateauWeight(wx, wz) > 0.6f) mods += "高原 ";
        if (noiseEngine.sampleKarstWeight(wx, wz) > 0.6f && temp > 0.4f) mods += "喀斯特 ";
        if (noiseEngine.sampleDanxiaWeight(wx, wz) > 0.6f && temp > 0.55f && moist < 0.4f) mods += "丹霞 ";
        if (noiseEngine.sampleGlacierWeight(wx, wz) > 0.6f && temp < 0.4f && r > 0.5f) mods += "冰川 ";
        int minY0 = levels.minY();
        int maxY0 = getGenDepth();
        int seaL0 = getSeaLevel();
        float seaNorm = (float)(seaL0 - minY0) / (maxY0 - minY0);
        float odFactor = (float)GeoGenesisConfig.COMMON.oceanDepthMax.get() * seaNorm / (seaL0 - (float)minY0);
        float h = computeHeight(wx, wz, seaNorm, odFactor, maxY0);
        int bh = minY0 + Math.round(h * (maxY0 - minY0));
        lines.add("§e[GeoGenesis] §fT:" + String.format("%.2f", t) + " R:" + String.format("%.2f", r)
            + " H:" + bh + " §b" + type + " " + mods);
        lines.add("§e[Plate] §f地壳:" + String.format("%.2f", plate.crustalThickness())
            + " 边界:" + String.format("%.2f", plate.boundaryStrength())
            + " 抬升:" + String.format("%.2f", plate.uplift()));
    }

    public GeoLevels getLevels() { return levels; }

    /**
     * Cross-Tile Discharge Blending — 解决相邻 tile 水文模拟种子不同导致的边界断裂
     *
     * 根因：SimpleHydrologyEngine 使用 per-tile 随机种子 seed^(ox*31)^(oz*73)
     * 导致同一世界位置在不同 tile 中的 discharge 分布不一致 → 边界处 riverMask 突变 → 直边截断
     *
     * 方案：对 overlap 区域（距边缘 < BLEND_WIDTH px）做跨 tile 双向加权平均
     * 利用已有的 tile 缓存，如果邻居 tile 已存在则混合，不存在则跳过（不阻塞）
     */
    private static final int BLEND_WIDTH = 40; // 混合区域宽度（像素），增大以更强平滑

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private void blendDischargeWithNeighbors(long tileKey, TileEntry entry, int tileCX, int tileCZ) {
        float[][] discharge = entry.dischargeMap;
        if (discharge == null) return;

        int ts = TILE_CHUNKS * 16 + TILE_BORDER * 2; // tileSize (128)
        int border = TILE_BORDER; // 52
        int tileOffset = TILE_CHUNKS * 16; // 48 — 相邻 tile 在 heightMap 中的偏移量

        // ★ 修复：使用正确的世界坐标映射，而不是 ts-1-b / b
        long[] neighborKeys = {
            (long)tileCX << 32 | ((tileCZ - TILE_CHUNKS) & 0xFFFFFFFFL), // 北
            (long)tileCX << 32 | ((tileCZ + TILE_CHUNKS) & 0xFFFFFFFFL), // 南
            ((long)(tileCX - TILE_CHUNKS) << 32) | (tileCZ & 0xFFFFFFFFL), // 西
            ((long)(tileCX + TILE_CHUNKS) << 32) | (tileCZ & 0xFFFFFFFFL), // 东
        };

        for (int dir = 0; dir < 4; dir++) {
            TileEntry neighbor = tileCache.get(neighborKeys[dir]);
            if (neighbor == null || neighbor.dischargeMap == null) continue;

            float[][] nDischarge = neighbor.dischargeMap;

            switch (dir) {
                case 0: { // 北
                    int boundary = border;
                    int rStart = Math.max(0, boundary - BLEND_WIDTH);
                    int rEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int r = rStart; r <= rEnd; r++) {
                        int nr = r + tileOffset;
                        if (nr < 0 || nr >= ts) continue;
                        float dist = Math.abs(r - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int x = 0; x < ts; x++) {
                            float self = discharge[r][x];
                            float nb = nDischarge[nr][x];
                            float avg = (self + nb) * 0.5f;
                            discharge[r][x] = lerp(avg, self, w);
                            nDischarge[nr][x] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 1: { // 南
                    int boundary = border + tileOffset;
                    int rStart = Math.max(0, boundary - BLEND_WIDTH);
                    int rEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int r = rStart; r <= rEnd; r++) {
                        int nr = r - tileOffset;
                        if (nr < 0 || nr >= ts) continue;
                        float dist = Math.abs(r - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int x = 0; x < ts; x++) {
                            float self = discharge[r][x];
                            float nb = nDischarge[nr][x];
                            float avg = (self + nb) * 0.5f;
                            discharge[r][x] = lerp(avg, self, w);
                            nDischarge[nr][x] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 2: { // 西
                    int boundary = border;
                    int cStart = Math.max(0, boundary - BLEND_WIDTH);
                    int cEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int c = cStart; c <= cEnd; c++) {
                        int nc = c + tileOffset;
                        if (nc < 0 || nc >= ts) continue;
                        float dist = Math.abs(c - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int z = 0; z < ts; z++) {
                            float self = discharge[z][c];
                            float nb = nDischarge[z][nc];
                            float avg = (self + nb) * 0.5f;
                            discharge[z][c] = lerp(avg, self, w);
                            nDischarge[z][nc] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 3: { // 东
                    int boundary = border + tileOffset;
                    int cStart = Math.max(0, boundary - BLEND_WIDTH);
                    int cEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int c = cStart; c <= cEnd; c++) {
                        int nc = c - tileOffset;
                        if (nc < 0 || nc >= ts) continue;
                        float dist = Math.abs(c - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int z = 0; z < ts; z++) {
                            float self = discharge[z][c];
                            float nb = nDischarge[z][nc];
                            float avg = (self + nb) * 0.5f;
                            discharge[z][c] = lerp(avg, self, w);
                            nDischarge[z][nc] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * Cross-Tile Height Blending — 修复侵蚀导致的 tile 边界微断裂
     *
     * 根因：ErosionEngine 是 per-tile 独立运行的，侵蚀结果在 tile 边界处不一致
     * 之前的"回退到侵蚀前"方案无效：BLEND_WIDTH < border，chunk 区域不在过渡范围内
     *
     * 方案：对 heightMap 在 overlap 区域做跨 tile 双向加权平均
     * 与 blendDischargeWithNeighbors 逻辑完全一致，但操作的是高度值
     * 如果邻居未生成则跳过（不阻塞），等邻居生成后它自己会做反向混合
     */
    private void blendHeightWithNeighbors(long tileKey, TileEntry entry, int tileCX, int tileCZ) {
        float[][] height = entry.data;
        int ts = height.length; // tileSize (128)
        int tileOffset = TILE_CHUNKS * 16; // 48 — 相邻 tile 在 heightMap 中的偏移量

        // ★ 关键：相邻 tile 的 heightMap 偏移 = TILE_CHUNKS * 16 = 48 像素
        // 当前 tile 列 c → 邻居 tile 列 nc = c - 48（东）或 c + 48（西）
        // 之前的代码用 ts-1-b 和 b 做映射，混合了完全不相关的世界坐标！

        long[] neighborKeys = {
            (long)tileCX << 32 | ((tileCZ - TILE_CHUNKS) & 0xFFFFFFFFL), // 北
            (long)tileCX << 32 | ((tileCZ + TILE_CHUNKS) & 0xFFFFFFFFL), // 南
            ((long)(tileCX - TILE_CHUNKS) << 32) | (tileCZ & 0xFFFFFFFFL), // 西
            ((long)(tileCX + TILE_CHUNKS) << 32) | (tileCZ & 0xFFFFFFFFL), // 东
        };

        // tile 边界在 heightMap 中的位置
        int border = TILE_BORDER; // 52
        // 北/西边界: row/col = border (52)
        // 南/东边界: row/col = border + tileOffset (100)

        for (int dir = 0; dir < 4; dir++) {
            TileEntry neighbor = tileCache.get(neighborKeys[dir]);
            if (neighbor == null || neighbor.data == null) continue;

            float[][] nHeight = neighbor.data;

            switch (dir) {
                case 0: { // 北：当前 tile 的上部分 ↔ 邻居的下部分
                    int boundary = border; // 北边界行
                    int rStart = Math.max(0, boundary - BLEND_WIDTH);
                    int rEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int r = rStart; r <= rEnd; r++) {
                        int nr = r + tileOffset; // 邻居对应行
                        if (nr < 0 || nr >= ts) continue;
                        float dist = Math.abs(r - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t); // smoothstep
                        for (int x = 0; x < ts; x++) {
                            float self = height[r][x];
                            float nb = nHeight[nr][x];
                            float avg = (self + nb) * 0.5f;
                            height[r][x] = lerp(avg, self, w);
                            nHeight[nr][x] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 1: { // 南：当前 tile 的下部分 ↔ 邻居的上部分
                    int boundary = border + tileOffset; // 南边界行
                    int rStart = Math.max(0, boundary - BLEND_WIDTH);
                    int rEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int r = rStart; r <= rEnd; r++) {
                        int nr = r - tileOffset;
                        if (nr < 0 || nr >= ts) continue;
                        float dist = Math.abs(r - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int x = 0; x < ts; x++) {
                            float self = height[r][x];
                            float nb = nHeight[nr][x];
                            float avg = (self + nb) * 0.5f;
                            height[r][x] = lerp(avg, self, w);
                            nHeight[nr][x] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 2: { // 西：当前 tile 的左部分 ↔ 邻居的右部分
                    int boundary = border; // 西边界列
                    int cStart = Math.max(0, boundary - BLEND_WIDTH);
                    int cEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int c = cStart; c <= cEnd; c++) {
                        int nc = c + tileOffset;
                        if (nc < 0 || nc >= ts) continue;
                        float dist = Math.abs(c - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int z = 0; z < ts; z++) {
                            float self = height[z][c];
                            float nb = nHeight[z][nc];
                            float avg = (self + nb) * 0.5f;
                            height[z][c] = lerp(avg, self, w);
                            nHeight[z][nc] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
                case 3: { // 东：当前 tile 的右部分 ↔ 邻居的左部分
                    int boundary = border + tileOffset; // 东边界列
                    int cStart = Math.max(0, boundary - BLEND_WIDTH);
                    int cEnd = Math.min(ts - 1, boundary + BLEND_WIDTH);
                    for (int c = cStart; c <= cEnd; c++) {
                        int nc = c - tileOffset;
                        if (nc < 0 || nc >= ts) continue;
                        float dist = Math.abs(c - boundary);
                        float t = dist / BLEND_WIDTH;
                        float w = t * t * (3.0f - 2.0f * t);
                        for (int z = 0; z < ts; z++) {
                            float self = height[z][c];
                            float nb = nHeight[z][nc];
                            float avg = (self + nb) * 0.5f;
                            height[z][c] = lerp(avg, self, w);
                            nHeight[z][nc] = lerp(avg, nb, w);
                        }
                    }
                    break;
                }
            }
        }
    }

    // 8 方向偏移量 (dz, dx) — 用于 propagateDischargeTile
    private static final int[] PDZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] PDX = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * Tile 级别 discharge 传播补流 — 解决 chunk 边界断流
     *
     * 问题：chunk 级别的 16×16 补流无法跨越 chunk 边界（16格硬边界）
     * 导致河流在黄色 chunk 网格处被精确截断
     *
     * 方案：在 tile 级别（152×152）对 dischargeMap 做 3-pass 滑动窗口补流
     * 条件：邻居 discharge > p88（已有河）+ 地形下坡 → 当前格补上衰减值
     * 效果：补流自然覆盖所有 chunk 边界，无需跨 chunk 通信
     */
    private static final int TILE_PROPAGATE_PASSES = 2;       // 减少传播次数（Cell-RC 已有路径追踪）
    private static final float TILE_PROPAGATE_DECAY = 0.65f;  // 每格衰减35%

    private void propagateDischargeTile(float[][] dischargeMap, float[][] tileBuf,
                                         int tileSize, float p88, float seaNorm) {
        // 传播距离与河流强度成正比：
        // 大河(discharge=10): 10→6.5→4.2→2.7→1.8 → 传播3-4格仍>p88
        // 小河(discharge=3):  3→1.95→1.27→0.82 → 只传播1-2格
        for (int pass = 0; pass < TILE_PROPAGATE_PASSES; pass++) {
            float[][] next = new float[tileSize][tileSize];
            for (int z = 0; z < tileSize; z++) {
                System.arraycopy(dischargeMap[z], 0, next[z], 0, tileSize);
            }

            for (int z = 2; z < tileSize - 2; z++) {
                for (int x = 2; x < tileSize - 2; x++) {
                    float curD = dischargeMap[z][x];
                    if (curD > p88) continue; // 已是河流，不需要补

                    float bestNeighbor = 0;
                    float curH = tileBuf[z][x];

                    // 计算当前像素的局部坡度
                    float curSlope = 0;
                    if (z > 0 && z < tileSize - 1 && x > 0 && x < tileSize - 1) {
                        curSlope = Math.abs(tileBuf[z + 1][x] - tileBuf[z - 1][x])
                                 + Math.abs(tileBuf[z][x + 1] - tileBuf[z][x - 1]);
                    }
                    // 只在平缓地形补流（坡度 ≤ 0.06），陡坡绝不补
                    if (curSlope > 0.06f) continue;

                    for (int d = 0; d < 8; d++) {
                        int nz = z + PDZ[d], nx = x + PDX[d];
                        float nd = dischargeMap[nz][nx];
                        if (nd <= bestNeighbor) continue;
                        if (nd <= p88) continue; // 只从已确认河流传播

                        // 平缓或下坡都允许
                        float nh = tileBuf[nz][nx];
                        if (nh >= curH - 0.01f) {
                            bestNeighbor = nd;
                        }
                    }

                    if (bestNeighbor > p88) {
                        // 不设上限：大河流传播值可以超过 p88，自然传播更远
                        float boosted = bestNeighbor * TILE_PROPAGATE_DECAY;
                        next[z][x] = Math.max(curD, boosted);
                    }
                }
            }

            for (int z = 0; z < tileSize; z++) {
                System.arraycopy(next[z], 0, dischargeMap[z], 0, tileSize);
            }
        }
    }

    // ========================================================================
    //  Cell Corridor River System (Cell-RC)
    //  用确定性拓扑路径替代噪点性的 discharge 热力图判定河流位置
    //  参考: DualLayerRiverGen (erosion-test-tool) 的 corridor 机制
    // ========================================================================

    /** Cell 网格粒度：tile 128×128 → 16×16 = 256 个 cells */
    private static final int CORRIDOR_CELL_SIZE = 8;
    /** 最小 flow accumulation 才形成河流（过滤弱源头散乱） */
    private static final float CORRIDOR_MIN_ACCUM = 5f;
    /** 最短路径长度（cells） */
    private static final int CORRIDOR_MIN_PATH_LEN = 4;
    /** 走廊宽度缩放因子 */
    private static final float CORRIDOR_WIDTH_SCALE = 3f;
    /** 走廊最大宽度（px） */
    private static final float CORRIDOR_WIDTH_CAP = 14f;
    /** 走廊/河宽比（走廊比实际河宽大50%，给雕刻留余量） */
    private static final float CORRIDOR_RATIO = 1.5f;

    /** 8 方向偏移 (dz, dx) — 用于 cell 邻居遍历 */
    private static final int[] CDZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] CDX = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * 构建河流线段列表——距离场雕刻系统的核心入口。
     *
     * 算法流程：
     *   1. 建立 Cell 网格（CORRIDOR_CELL_SIZE × CORRIDOR_CELL_SIZE）
     *   2. 计算每个 Cell 的 Flow Direction（8方向最低邻居）
     *   3. 计算 Flow Accumulation（递归统计上游数量）
     *   4. 从源头追踪 River Path（沿 flowDir 到海边）
     *   5. 将路径转换为 RiverSegment 列表（线段 + 宽度 + 深度）
     *
     * @param heightMap tile 高度图 [tileSize][tileSize]
     * @param tileSize tile 边长（128）
     * @param seaNorm 归一化海平面
     * @return RiverSegment 列表（用于距离场雕刻）
     */
    private java.util.List<RiverSegment> buildRiverCorridor(float[][] heightMap, int tileSize, float seaNorm,
                                                int tileWorldX, int tileWorldZ, int border) {
        // ---- Phase 1: 建立 Cell 网格（世界坐标对齐）----
        // ★ 关键修复：Cell 高度从原始噪声采样（而非侵蚀后的 heightMap）
        // 侵蚀是 per-tile 独立的，不同 tile 在同一位置的侵蚀后高度不同
        // → Cell 流向不同 → 河流路径不连续 → 断裂
        // 改为从原始噪声采样 → 全局一致 → 流向一致 → 河流连续

        int hmOriginX = tileWorldX - border;
        int hmOriginZ = tileWorldZ - border;

        int startCI = (int)Math.floor((double)hmOriginX / CORRIDOR_CELL_SIZE);
        int startCJ = (int)Math.floor((double)hmOriginZ / CORRIDOR_CELL_SIZE);
        int endCI = (int)Math.floor((double)(hmOriginX + tileSize - 1) / CORRIDOR_CELL_SIZE);
        int endCJ = (int)Math.floor((double)(hmOriginZ + tileSize - 1) / CORRIDOR_CELL_SIZE);
        int gridW = endCI - startCI + 1;
        int gridH = endCJ - startCJ + 1;

        // ★ 需要原始噪声参数来计算 Cell 高度
        int maxY = getGenDepth();
        int minY = getMinY();
        int seaLevel = getSeaLevel();
        float fullSeaNorm = (float)(seaLevel - minY) / (maxY - minY);
        float fullOdFactor = (float)GeoGenesisConfig.COMMON.oceanDepthMax.get() * fullSeaNorm / (seaLevel - (float)minY);

        CellData[][] cells = new CellData[gridH][gridW];
        for (int cj = 0; cj < gridH; cj++) {
            for (int ci = 0; ci < gridW; ci++) {
                int wci = startCI + ci;
                int wcj = startCJ + cj;

                float worldCX = wci * CORRIDOR_CELL_SIZE + CORRIDOR_CELL_SIZE * 0.5f;
                float worldCZ = wcj * CORRIDOR_CELL_SIZE + CORRIDOR_CELL_SIZE * 0.5f;

                java.util.Random cellRng = new java.util.Random(
                    worldSeed ^ ((long)wci * 31L + (long)wcj * 73L));
                float jx = (cellRng.nextFloat() - 0.5f) * 4f;
                float jz = (cellRng.nextFloat() - 0.5f) * 4f;
                int sampleWX = (int)(worldCX + jx);
                int sampleWZ = (int)(worldCZ + jz);

                // ★ 从原始噪声采样（全局一致），而非从侵蚀后的 heightMap 采样
                float ch = computeHeight(sampleWX, sampleWZ, fullSeaNorm, fullOdFactor, maxY);
                ch = Math.max(0f, Math.min(1f, ch));

                // 局部坐标用于 rasterize
                float localCX = worldCX - hmOriginX;
                float localCZ = worldCZ - hmOriginZ;
                float cx = localCX + jx;
                float cz = localCZ + jz;

                cells[cj][ci] = new CellData(ci, cj, cx, cz, ch, -1);
            }
        }

        // ---- Phase 2: 计算 Flow Direction ----
        // 每个 cell 找到最低的 8-邻居作为流向
        for (int cj = 0; cj < gridH; cj++) {
            for (int ci = 0; ci < gridW; ci++) {
                cells[cj][ci].flowDir = computeCellFlowDir(cells, ci, cj, gridW, gridH);
            }
        }

        // ---- Phase 3: 计算 Flow Accumulation ----
        // 递归统计每个 cell 的上游汇流数量
        float[][] accum = new float[gridH][gridW];
        java.util.Set<Long> visited = new java.util.HashSet<>();
        for (int cj = 0; cj < gridH; cj++) {
            for (int ci = 0; ci < gridW; ci++) {
                if (cells[cj][ci].flowDir >= 0 && accum[cj][ci] == 0) {
                    accum[cj][ci] = computeCellAccum(cells, accum, ci, cj, gridW, gridH, visited);
                    visited.clear();
                }
            }
        }

        // ---- Phase 4: 追踪 River Path ----
        // 源头 = 有下游(flowDir>=0) + accum足够 + 无上游邻居指向自己
        java.util.List<RiverPath> paths = new java.util.ArrayList<>();
        java.util.Set<Long> tracedCells = new java.util.HashSet<>();

        for (int cj = 0; cj < gridH; cj++) {
            for (int ci = 0; ci < gridW; ci++) {
                CellData c = cells[cj][ci];
                if (c.flowDir < 0) continue;
                if (accum[cj][ci] < CORRIDOR_MIN_ACCUM) continue;
                if (c.height < seaNorm + 0.02f) continue; // 海面以下不生成河

                // 检查是否有上游邻居指向自己（有则不是源头）
                boolean hasUpstream = false;
                for (int d = 0; d < 8; d++) {
                    int ui = ci + CDX[d], uj = cj + CDZ[d];
                    if (ui < 0 || ui >= gridW || uj < 0 || uj >= gridH) continue;
                    CellData up = cells[uj][ui];
                    if (up.flowDir >= 0) {
                        int fi = ui + CDX[up.flowDir];
                        int fj = uj + CDZ[up.flowDir];
                        if (fi == ci && fj == cj && accum[uj][ui] >= CORRIDOR_MIN_ACCUM) {
                            hasUpstream = true;
                            break;
                        }
                    }
                }
                if (hasUpstream) continue;

                // 从源头追踪路径
                RiverPath path = traceCellRiverPath(cells, accum, ci, cj, gridW, gridH,
                    seaNorm, tracedCells);
                if (path != null && path.nodes.size() >= CORRIDOR_MIN_PATH_LEN) {
                    paths.add(path);
                }
            }
        }

        // 按 accumulation 排序（大河优先渲染，覆盖小河）
        paths.sort((a, b) -> Float.compare(
            b.nodes.get(0).accum, a.nodes.get(0).accum));

        // ---- Phase 5: 弯曲段生成 + 转换为 RiverSegment 列表 ----
        // 参考 BrushRiverGen 的中点垂向位移算法，让河道更自然
        java.util.List<RiverSegment> segments = new java.util.ArrayList<>();
        for (RiverPath path : paths) {
            java.util.List<RiverNode> nodes = path.nodes;
            if (nodes.size() < 2) continue;

            // 为每对相邻节点生成弯曲段
            for (int i = 0; i < nodes.size() - 1; i++) {
                RiverNode a = nodes.get(i);
                RiverNode b = nodes.get(i + 1);
                float aw = riverWidthFromAccum(a.accum);
                float bw = riverWidthFromAccum(b.accum);
                float ad = riverDepthFromAccum(a.accum);
                float bd = riverDepthFromAccum(b.accum);

                // 过滤太小的河流段（accum < 3 的源头不产生可见河道）
                if (a.accum < 3f && b.accum < 3f) continue;

                // 中点垂向位移
                float mx = (a.x + b.x) * 0.5f;
                float mz = (a.z + b.z) * 0.5f;
                float segDx = b.x - a.x, segDz = b.z - a.z;
                float segLen = (float)Math.sqrt(segDx * segDx + segDz * segDz);

                // 垂直于线段的方向
                float nx = -segDz, nz = segDx;
                if (segLen > 0.01f) { nx /= segLen; nz /= segLen; }

                // 随机位移方向和幅度
                int hash = (int)(worldSeed ^ ((long)i * 31L + (long)nodes.get(0).accum * 73L));
                java.util.Random segRng = new java.util.Random(hash);
                float dir = segRng.nextFloat() < 0.5f ? -1f : 1f;
                float amp = 0.3f + segRng.nextFloat() * 0.4f; // 位移幅度为段长的 30-70%
                float meander = Math.min(segLen * 0.3f, 6f) * dir * amp; // 最大偏移6px

                float cmx = mx + nx * meander;
                float cmz = mz + nz * meander;

                // 中点的高度和属性插值
                float cmH = (a.height + b.height) * 0.5f;
                float cmAccum = (a.accum + b.accum) * 0.5f;
                float cmW = (aw + bw) * 0.5f;
                float cmD = (ad + bd) * 0.5f;
                float cmWL = (a.waterLevel + b.waterLevel) * 0.5f;

                // 创建弯曲中点节点
                RiverNode mid = new RiverNode(cmx, cmz, cmH, cmAccum);
                mid.waterLevel = cmWL;

                // 生成两段：a→mid, mid→b
                segments.add(new RiverSegment(a.x, a.z, mid.x, mid.z, aw, cmW, ad, cmD,
                                              a.accum, cmAccum, a.waterLevel, mid.waterLevel));
                segments.add(new RiverSegment(mid.x, mid.z, b.x, b.z, cmW, bw, cmD, bd,
                                              cmAccum, b.accum, mid.waterLevel, b.waterLevel));
            }
        }

        LOGGER.info("[Cell-RC] cells={}x{} paths={} segments={}",
            gridW, gridH, paths.size(), segments.size());

        return segments;
    }

    /**
     * 计算 cell 的流向：找 8 方向中高度下降最大的邻居。
     * 如果自己是局部最低点（sink），返回 -1。
     */
    private int computeCellFlowDir(CellData[][] cells, int ci, int cj, int gw, int gh) {
        float h = cells[cj][ci].height;
        int best = -1;
        float bestDrop = 0f;
        for (int d = 0; d < 8; d++) {
            int ni = ci + CDX[d], nj = cj + CDZ[d];
            if (ni < 0 || ni >= gw || nj < 0 || nj >= gh) continue;
            float drop = h - cells[nj][ni].height;
            if (drop > bestDrop) {
                bestDrop = drop;
                best = d;
            }
        }
        return best; // -1 表示 sink（局部最低点）
    }

    /**
     * 递归计算 flow accumulation：accum = 1 + Σ(所有上游 neighbor 的 accum)
     */
    private float computeCellAccum(CellData[][] cells, float[][] accum,
                                     int ci, int cj, int gw, int gh,
                                     java.util.Set<Long> visited) {
        long key = ((long) ci << 16) | (cj & 0xFFFFL);
        if (visited.contains(key)) return 0; // 防止循环
        visited.add(key);

        if (accum[cj][ci] > 0) return accum[cj][ci]; // 已计算过

        float total = 1f; // 自己贡献 1
        for (int d = 0; d < 8; d++) {
            int ui = ci + CDX[d], uj = cj + CDZ[d];
            if (ui < 0 || ui >= gw || uj < 0 || uj >= gh) continue;
            CellData up = cells[uj][ui];
            if (up.flowDir < 0) continue;
            // 检查这个上游邻居是否流向当前 cell
            int fi = ui + CDX[up.flowDir];
            int fj = uj + CDZ[up.flowDir];
            if (fi == ci && fj == cj) {
                total += computeCellAccum(cells, accum, ui, uj, gw, gh, visited);
            }
        }
        accum[cj][ci] = total;
        return total;
    }

    /**
     * 从源头 cell 追踪河流路径，直到海边、sink 或已访问节点。
     * 同时计算每段的水面高度（确保单调下降）。
     */
    private RiverPath traceCellRiverPath(CellData[][] cells, float[][] accum,
                                          int startCI, int startCJ, int gw, int gh,
                                          float seaNorm,
                                          java.util.Set<Long> tracedCells) {
        java.util.List<RiverNode> nodes = new java.util.ArrayList<>();
        int curI = startCI, curJ = startCJ;

        for (int step = 0; step < 200; step++) {
            if (curI < 0 || curI >= gw || curJ < 0 || curJ >= gh) break;

            CellData cell = cells[curJ][curI];
            long key = ((long) curI << 16) | (curJ & 0xFFFFL);

            // 已被其他路径追踪过 → 合并点，停止
            if (tracedCells.contains(key)) {
                if (!nodes.isEmpty()) {
                    nodes.add(new RiverNode(cell.cx, cell.cz, cell.height, accum[curJ][curI]));
                }
                break;
            }

            // 到达海平面以下 → 终止
            if (cell.height < seaNorm) {
                nodes.add(new RiverNode(cell.cx, cell.cz, cell.height, accum[curJ][curI]));
                tracedCells.add(key);
                break;
            }

            nodes.add(new RiverNode(cell.cx, cell.cz, cell.height, accum[curJ][curI]));
            tracedCells.add(key);

            // 高度检查：不允许大幅上升（水不会倒流）
            if (nodes.size() >= 2) {
                RiverNode last = nodes.get(nodes.size() - 2);
                if (cell.height > last.height + 0.03f) {
                    // 上升太多，截断路径（移除最后加入的节点）
                    nodes.remove(nodes.size() - 1);
                    tracedCells.remove(key);
                    break;
                }
            }

            // sink 或无流向 → 终止
            if (cell.flowDir < 0) break;

            // 移动到下一个 cell
            curI = curI + CDX[cell.flowDir];
            curJ = curJ + CDZ[cell.flowDir];
        }

        // 链式水面高度传递（从下游向上游）
        // 水面从终点节点实际高度开始，向上游传递
        // 关键修复：不再 clamp 到 seaNorm，否则陆地河流水面永远在 seaLevel 附近无法放水
        float MAX_WATER_GRADIENT = 0.003f; // 最大水面梯度（允许跟上地形上升）
        int n = nodes.size();
        float[] wls = new float[n];
        // 下游终点：水面 = 节点实际高度（不限制到 seaNorm）
        wls[n-1] = nodes.get(n-1).height;
        // 从下游向上游传递
        for (int i = n-2; i >= 0; i--) {
            float dist = (float)Math.hypot(nodes.get(i).x - nodes.get(i+1).x,
                                           nodes.get(i).z - nodes.get(i+1).z);
            // 水面 = 下游水面 + 梯度上升，但不超过节点高度
            wls[i] = Math.min(wls[i+1] + MAX_WATER_GRADIENT * dist,
                              nodes.get(i).height);
            // 不低于海平面（海洋/河口区域）
            wls[i] = Math.max(wls[i], seaNorm);
        }
        // 前向平滑3遍
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < n; i++) {
                float dist = (float)Math.hypot(nodes.get(i).x - nodes.get(i-1).x,
                                               nodes.get(i).z - nodes.get(i-1).z);
                float maxDrop = MAX_WATER_GRADIENT * dist;
                if (wls[i-1] - wls[i] > maxDrop) {
                    wls[i] = Math.max(wls[i-1] - maxDrop, seaNorm);
                }
            }
        }
        // 写入节点
        for (int i = 0; i < n; i++) {
            nodes.get(i).waterLevel = wls[i];
        }

        if (nodes.size() < CORRIDOR_MIN_PATH_LEN) return null;
        return new RiverPath(nodes);
    }

    /** 根据 flow accumulation 计算河流宽度（px） */
    private static float riverWidthFromAccum(float accum) {
        // 源头 (accum=1): width ≈ 2.3px（~2格方块）
        // 小河 (accum=5): width ≈ 4.1px
        // 中河 (accum=15): width ≈ 6.6px
        // 大河 (accum=50): width ≈ 11.3px
        // 巨河 (accum=100): width ≈ 15.8px → cap 14
        return Math.min(0.8f + (float) Math.sqrt(Math.max(1, accum)) * 1.5f, CORRIDOR_WIDTH_CAP);
    }

    /** 根据 flow accumulation 计算河流深度（归一化） */
    private static float riverDepthFromAccum(float accum) {
        return Math.min(0.02f + (float)Math.pow(Math.max(1, accum), 0.3f) * 0.008f, 0.08f);
    }

    /** 根据 flow accumulation 计算水面宽度（px），源头窄、下游宽 */
    private static float waterWidthFromAccum(float accum) {
        // 源头 (accum=1):   ~1.7px (~1格方块)
        // 小河 (accum=5):    ~3.2px
        // 中河 (accum=20):   ~5.9px
        // 大河 (accum=50):   ~8.9px
        // 巨河 (accum=100): ~12.5px → cap 14
        return Math.min(0.5f + (float) Math.sqrt(Math.max(1, accum)) * 1.2f, 14f);
    }

    // ========================================================================
    //  距离场采样方法
    // ========================================================================

    /** 计算像素 (px, pz) 到最近 RiverSegment 的归一化距离 */
    private static float sampleDistanceField(float px, float pz, java.util.List<RiverSegment> segments) {
        float minNormDist = Float.MAX_VALUE;
        for (RiverSegment seg : segments) {
            float dx = seg.bx - seg.ax, dz = seg.bz - seg.az;
            float len2 = dx * dx + dz * dz;
            if (len2 < 0.01f) continue;
            float t = ((px - seg.ax) * dx + (pz - seg.az) * dz) / len2;
            t = Math.max(0f, Math.min(1f, t));
            float projX = seg.ax + t * dx;
            float projZ = seg.az + t * dz;
            float distX = px - projX, distZ = pz - projZ;
            float dist = (float)Math.sqrt(distX * distX + distZ * distZ);
            float width = seg.aWidth + t * (seg.bWidth - seg.aWidth);
            if (width < 0.5f) continue;
            float normDist = dist / width;
            if (normDist < minNormDist) minNormDist = normDist;
        }
        return minNormDist;
    }

    /** 计算像素 (px, pz) 处的最近 RiverSegment 的插值深度 */
    private static float sampleDepthField(float px, float pz, java.util.List<RiverSegment> segments) {
        float minNormDist = Float.MAX_VALUE;
        float bestDepth = 0;
        for (RiverSegment seg : segments) {
            float dx = seg.bx - seg.ax, dz = seg.bz - seg.az;
            float len2 = dx * dx + dz * dz;
            if (len2 < 0.01f) continue;
            float t = ((px - seg.ax) * dx + (pz - seg.az) * dz) / len2;
            t = Math.max(0f, Math.min(1f, t));
            float projX = seg.ax + t * dx;
            float projZ = seg.az + t * dz;
            float distX = px - projX, distZ = pz - projZ;
            float dist = (float)Math.sqrt(distX * distX + distZ * distZ);
            float width = seg.aWidth + t * (seg.bWidth - seg.aWidth);
            if (width < 0.5f) continue;
            float normDist = dist / width;
            if (normDist < minNormDist) {
                minNormDist = normDist;
                bestDepth = seg.aDepth + t * (seg.bDepth - seg.aDepth);
            }
        }
        return bestDepth;
    }

    /** 计算像素 (px, pz) 处的插值水面高度（归一化），返回 -1 表示无水 */
    private static float sampleWaterLevel(float px, float pz, java.util.List<RiverSegment> segments) {
        float minNormDist = Float.MAX_VALUE;
        float bestWaterLevel = -1;
        for (RiverSegment seg : segments) {
            float dx = seg.bx - seg.ax, dz = seg.bz - seg.az;
            float len2 = dx * dx + dz * dz;
            if (len2 < 0.01f) continue;
            float t = ((px - seg.ax) * dx + (pz - seg.az) * dz) / len2;
            t = Math.max(0f, Math.min(1f, t));
            float projX = seg.ax + t * dx;
            float projZ = seg.az + t * dz;
            float distX = px - projX, distZ = pz - projZ;
            float dist = (float)Math.sqrt(distX * distX + distZ * distZ);
            float width = seg.aWidth + t * (seg.bWidth - seg.aWidth);
            if (width < 0.5f) continue;
            float normDist = dist / width;
            if (normDist < minNormDist) {
                minNormDist = normDist;
                // 只在河水区域(normDist < 0.55)返回水面高度
                if (normDist < 0.55f) {
                    bestWaterLevel = seg.aWaterLevel + t * (seg.bWaterLevel - seg.aWaterLevel);
                } else {
                    bestWaterLevel = -1;
                }
            }
        }
        return bestWaterLevel;
    }

    // ========================================================================
    //  Cell Corridor 内部数据结构
    // ========================================================================

    /** Cell 数据：网格位置、世界坐标、高度、流向 */
    private static class CellData {
        final int ci, cj;      // 网格坐标
        final float cx, cz;    // 中心点坐标（tile 局部）
        final float height;    // 采样高度
        int flowDir;           // 流向（0-7 = 8方向, -1 = sink）

        CellData(int ci, int cj, float cx, float cz, float height, int flowDir) {
            this.ci = ci;
            this.cj = cj;
            this.cx = cx;
            this.cz = cz;
            this.height = height;
            this.flowDir = flowDir;
        }
    }

    /** 河流路径上的一个节点 */
    private static class RiverNode {
        final float x, z;      // 世界坐标（tile 局部）
        final float height;    // 高度
        final float accum;     // flow accumulation
        float waterLevel;      // 链式水面高度（traceCellRiverPath 中计算）

        RiverNode(float x, float z, float height, float accum) {
            this.x = x;
            this.z = z;
            this.height = height;
            this.accum = accum;
            this.waterLevel = height; // 默认值，后续会被覆盖
        }
    }

    /** 一条完整的河流路径（节点序列） */
    private static class RiverNodePath {
        final java.util.List<RiverNode> nodes;

        RiverNodePath(java.util.List<RiverNode> nodes) {
            this.nodes = nodes;
        }
    }

    /** 河流线段：两个相邻 RiverNode 之间的线段，带宽度/深度信息 */
    private static class RiverSegment {
        final float ax, az;           // 起点（tile 局部坐标）
        final float bx, bz;           // 终点
        final float aWidth, bWidth;   // 起终点宽度（像素）
        final float aDepth, bDepth;   // 起终点深度（归一化）
        final float aAccum, bAccum;   // 起终点 flow accumulation
        final float aWaterLevel, bWaterLevel; // 链式水面高度（归一化）

        RiverSegment(float ax, float az, float bx, float bz,
                     float aWidth, float bWidth, float aDepth, float bDepth,
                     float aAccum, float bAccum,
                     float aWaterLevel, float bWaterLevel) {
            this.ax = ax; this.az = az;
            this.bx = bx; this.bz = bz;
            this.aWidth = aWidth; this.bWidth = bWidth;
            this.aDepth = aDepth; this.bDepth = bDepth;
            this.aAccum = aAccum; this.bAccum = bAccum;
            this.aWaterLevel = aWaterLevel; this.bWaterLevel = bWaterLevel;
        }
    }

    /** 为简洁起见，用 RiverNodePath 的别名 */
    private static class RiverPath extends RiverNodePath {
        RiverPath(java.util.List<RiverNode> nodes) { super(nodes); }
    }
}
