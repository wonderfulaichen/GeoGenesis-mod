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
    private static final int TILE_BORDER = 40; // 侵蚀粒子边界
    private final ConcurrentHashMap<Long, float[][]> tileCache = new ConcurrentHashMap<>();

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

        float[][] tileBuf = tileCache.computeIfAbsent(tileKey, k ->
            generateTile(tileWorldX, tileWorldZ, tileSize, border, seaNorm, odFactor, maxY));
        tNoise = tErosion = tRiver = System.nanoTime(); // 生成时间已计入 tile 缓存

        // 从 tile 缓存中提取当前 chunk 的高度数据
        int offsetX = (chunkX - tileCX) * 16; // chunk 在 tile 中的偏移
        int offsetZ = (chunkZ - tileCZ) * 16;

        int bedrockTop = minY + 4;
        int[] blockHeights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                float h = tileBuf[offsetZ + z + border][offsetX + x + border];
                int height = minY + Math.round(h * (maxY - minY));
                height = Math.max(height, bedrockTop);
                if (height >= maxY) height = maxY - 1;
                blockHeights[z * 16 + x] = height;

                for (int y = bedrockTop + 1; y <= height && y < maxY; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), false);
                }
                for (int y = height + 1; y <= seaLevel && y < maxY; y++) {
                    if (chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }
        tBlocks = System.nanoTime();

        // 输出性能日志
        long total = tBlocks - tStart;
        LOGGER.info("[Perf] Chunk({},{}) total={}ms cache_hit={}",
            pos.x, pos.z, total / 1_000_000, tileBuf != null);

        // hydrologySystem.setRiverEnabled(riverDepthCfg > 0);
        // 原有 RiverBrushSystem 逻辑已由 SimpleHydrologyEngine 替代
        /*
        if (riverDepthCfg > 0) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wx = wx0 + x, wz = wz0 + z;
                    var riverSample = hydrologySystem.sampleRiverAt(wx, wz);
                    if (riverSample == null || !riverSample.isRiver()) continue;

                    int naturalGroundY = blockHeights[z * 16 + x];
                    float naturalHeightNorm = (float)(naturalGroundY - minY) / (maxY - minY);

                    float carvedHeightNorm = riverSample.computeCarvedHeight(naturalHeightNorm);
                    int carvedY = minY + Math.round(carvedHeightNorm * (maxY - minY));
                    carvedY = Math.max(carvedY, minY + 1);
                    carvedY = Math.min(carvedY, naturalGroundY);

                    if (carvedY >= naturalGroundY) continue;

                    for (int y = carvedY + 1; y <= naturalGroundY && y < maxY; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), false);
                    }

                    if (riverSample.needsWater()) {
                        float waterLevelNorm = riverSample.waterLevel();
                        int waterSurfaceY = minY + Math.round(waterLevelNorm * (maxY - minY));
                        waterSurfaceY = Math.max(waterSurfaceY, carvedY + 1);
                        waterSurfaceY = Math.min(waterSurfaceY, maxY - 1);

                        int bedY = minY + Math.round(riverSample.bedLevel() * (maxY - minY));
                        bedY = Math.max(bedY, minY + 1);

                        for (int y = bedY + 1; y <= carvedY && y < maxY; y++) {
                            chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), false);
                        }

                        for (int y = Math.max(bedY + 1, carvedY + 1); y <= waterSurfaceY && y < maxY; y++) {
                            chunk.setBlockState(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), false);
                        }
                    }

                    blockHeights[z * 16 + x] = carvedY;
                }
            }
        }
        */

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

    // ===== 共享 tile 生成（噪声 + 超分辨率插值 + 侵蚀）=====

    /**
     * 生成一个完整的 tile 高度图（含噪声、插值、侵蚀）。
     * 此方法由 tileCache.computeIfAbsent 调用，保证同一 tile 只生成一次。
     * 
     * @param tileWorldX tile 左上角世界 X 坐标
     * @param tileWorldZ tile 左上角世界 Z 坐标
     * @param tileSize tile 边长（含 border）
     * @param border 侵蚀边界宽度
     * @param seaNorm 海平面归一化高度
     * @param odFactor 海洋深度因子
     * @param maxY 最大 Y 坐标
     */
    private float[][] generateTile(int tileWorldX, int tileWorldZ, int tileSize, int border,
                                    float seaNorm, float odFactor, int maxY) {
        long tStart = System.nanoTime();

        // Step 1: 超分辨率噪声计算（1/4 分辨率，全局对齐网格）
        int spacing = 4;
        int lowRes = (tileSize + spacing - 1) / spacing; // 128/4=32
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

        // Step 3: 侵蚀（全分辨率）
        float erosionStrength = (float)GeoGenesisConfig.COMMON.erosionStrength.get().doubleValue();
        if (erosionStrength > 0) {
            erosionEngine.applyErosionNormalized(tileBuf, tileSize,
                tileStartX, tileStartZ, seaNorm, erosionStrength);
        }
        long tErosion = System.nanoTime();

        LOGGER.info("[Perf-Tile] tile({},{}) noise={}ms erosion={}ms total={}ms size={}",
            tileWorldX >> 4, tileWorldZ >> 4,
            (tNoise - tStart) / 1_000_000,
            (tErosion - tNoise) / 1_000_000,
            (tErosion - tStart) / 1_000_000,
            tileSize);

        return tileBuf;
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
}
