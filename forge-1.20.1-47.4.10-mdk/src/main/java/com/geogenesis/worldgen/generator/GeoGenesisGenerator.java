package com.geogenesis.worldgen.generator;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GeoGenesis 地形生成器（Forge 1.20.1 ChunkGenerator）。
 *
 * 大地形管线：GeoGenesisTerrain（缓存 Cell 网格）→ fillFromNoise（逐格填方块）。
 * CODEC 为 biome_source + settings，settings 由 configSpec.generateHoldingCodec 或自定义。
 */
public class GeoGenesisGenerator extends ChunkGenerator {

    public static final String CODEC_ID = "geogenesis:generator";
    private static final Logger LOGGER = LogManager.getLogger(CODEC_ID);

    // CODEC: RecordCodecBuilder with biome_source only (settings come from global config)
    public static final Codec<GeoGenesisGenerator> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
        ).apply(instance, GeoGenesisGenerator::new)
    );

    // 硬编码世界高度（可后续改为配置驱动）
    static final int WORLD_MIN_Y = -64;
    static final int WORLD_MAX_Y = 320;
    static final int SEA_LEVEL = 63;

    // 方块状态缓存
    private static final BlockState AIR       = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE     = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState WATER     = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK   = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState DIRT      = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS     = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND      = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL    = Blocks.GRAVEL.defaultBlockState();

    // 地形引擎（全局共享）
    private GeoGenesisTerrain terrain;

    // 跨 BiomeSource / Generator 共享的地形引擎：
    // ChunkStatus.BIOMES 会在 fillFromNoise 之前调用 BiomeSource.getNoiseBiome，
    // 因此地形引擎必须能在 biome 采样时按需初始化（而非等到 fillFromNoise）。
    private static volatile GeoGenesisTerrain sharedTerrain;

    public GeoGenesisGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    // ===== 初始化（在 first call 或 seed-aware 调用时初始化 terrain） =====

    /**
     * 惰性初始化（或返回）共享地形引擎。BiomeSource 与 Generator 共用同一实例，
     * 保证 biome 采样与方块填充基于同一确定性地形场。使用静态 {@code worldSeed} 播种。
     */
    static GeoGenesisTerrain getOrInitTerrain() {
        if (sharedTerrain == null) {
            synchronized (GeoGenesisGenerator.class) {
                if (sharedTerrain == null) {
                    long t0 = System.nanoTime();
                    TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
                    long t1 = System.nanoTime();
                    CellGenerator gen = new CellGenerator(params, WORLD_MIN_Y, WORLD_MAX_Y);
                    long t2 = System.nanoTime();
                    gen.seed(worldSeed);
                    long t3 = System.nanoTime();
                    sharedTerrain = new GeoGenesisTerrain(gen);
                    long t4 = System.nanoTime();
                    LOGGER.info("GeoGenesis terrain engine initialized (seed={}) [buildParams={}ms ctor={}ms seed={}ms terrain={}ms]",
                        worldSeed,
                        (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000, (t4-t3)/1000000);
                }
            }
        }
        return sharedTerrain;
    }

    /**
     * 创建或重新初始化地形引擎（供 fillFromNoise 调用），并把引擎注入 BiomeSource。
     */
    private void ensureEngine(long seed) {
        if (terrain == null) {
            terrain = getOrInitTerrain();
            // inject terrain into BiomeSource so it can classify biomes by Cell data
            if (biomeSource instanceof GeoGenesisBiomeSource gbs) {
                gbs.setTerrain(terrain);
                LOGGER.info("GeoGenesis terrain injected into BiomeSource");
            }
        }
    }

    // ===== 核心：fillFromNoise =====

    // TODO: get actual world seed (need world-load event hook)
    private static volatile long worldSeed = 12345L;

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
        // 种子变更：下次访问重建共享引擎（BiomeSource / Generator 共用）
        sharedTerrain = null;
        LOGGER.info("GeoGenesis world seed set to {}", seed);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            java.util.concurrent.Executor executor,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        long t0 = System.nanoTime();
        ensureEngine(worldSeed);
        long t1 = System.nanoTime();

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        long t2 = System.nanoTime();
        Cell[] cells = terrain.getChunkCells(pos.x, pos.z);
        long t3 = System.nanoTime();

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];

                if (cell.riverMask && cell.terrainType ==
                        com.geogenesis.worldgen.terrain.TerrainClass.RIVER) {
                    fillRiverColumn(chunk, mPos, baseX + lx, baseZ + lz, cell);
                    continue;
                }

                fillTerrainColumn(chunk, mPos, baseX + lx, baseZ + lz, cell);
            }
        }
        long t4 = System.nanoTime();

        if ((t3 - t2) > 100000000L || (t4 - t3) > 100000000L) {
            LOGGER.info("[PERF] fillFromNoise chunk({},{}): ensure={}ms cells={}ms place={}ms total={}ms",
                pos.x, pos.z,
                (t1-t0)/1000000, (t3-t2)/1000000, (t4-t3)/1000000, (t4-t0)/1000000);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * 普通地形列填充：按 cell.height 铺 基岩/深板岩/石/土/表层/水柱。
     *
     * 关键修复（旧版 bug）：
     * - 地表用 {@code y == surfaceY}（surfaceY = floor(height)），不再用 {@code y == (int)height}
     *   整等——小数高度下草层永远铺不上（大面积裸石）。
     * - 海洋列补齐水柱（surfaceY+1 .. SEA_LEVEL 填水），旧版 else-if 链使海洋无水。
     * - 表层方块随 terrainType/水陆决定：陆地 GRASS+DIRT，海滩/浅水 SAND，深水 GRAVEL。
     */
    private void fillTerrainColumn(ChunkAccess chunk, BlockPos.MutableBlockPos mPos,
                                   int wx, int wz, Cell cell) {
        int surfaceY = (int) Math.floor(cell.height);
        boolean water = cell.isWater();                       // 实测海平面 e<0
        boolean beach = cell.terrainType ==
                com.geogenesis.worldgen.terrain.TerrainClass.BEACH;

        // 表层与填充块选择
        BlockState top, fill;
        if (water) {
            top  = (cell.height <= SEA_LEVEL - 3) ? GRAVEL : SAND;  // 深水砾石 / 浅水沙
            fill = top;
        } else if (beach) {
            top  = SAND;
            fill = SAND;
        } else {
            top  = GRASS;
            fill = DIRT;
        }

        for (int y = WORLD_MIN_Y; y < WORLD_MAX_Y; y++) {
            mPos.set(wx, y, wz);
            BlockState state;
            if (y == WORLD_MIN_Y) {
                state = BEDROCK;
            } else if (y < surfaceY - 3) {
                state = (y < 0) ? DEEPSLATE : STONE;             // 深层
            } else if (y < surfaceY) {
                state = fill;                                     // 表层下 3 格（土/沙）
            } else if (y == surfaceY) {
                state = top;                                      // 地表最顶块
            } else if (water && y <= SEA_LEVEL) {
                state = WATER;                                    // 水柱（海平面以下）
            } else {
                state = AIR;
            }
            chunk.setBlockState(mPos, state, false);
        }
    }

    /**
     * 河流列填充：按河谷刻蚀后的河床 / 水面高度灌水。
     * 水面≈谷壁高度（riverSurfaceY），河床为刻蚀底（riverFloorY），
     * 二者之间填水，使河流在 MC 中可见（含水岸高于海平面处的高山河谷）。
     */
    private void fillRiverColumn(ChunkAccess chunk, BlockPos.MutableBlockPos mPos,
                                 int wx, int wz, Cell cell) {
        int floorY = (int) Math.round(cell.riverFloorY);
        int surfY = (int) Math.round(cell.riverSurfaceY);
        int maxDepth = 24;
        if (surfY - floorY > maxDepth) surfY = floorY + maxDepth;
        if (surfY < floorY) surfY = floorY;

        for (int y = WORLD_MIN_Y; y < WORLD_MAX_Y; y++) {
            mPos.set(wx, y, wz);
            if (y == WORLD_MIN_Y) {
                chunk.setBlockState(mPos, BEDROCK, false);
            } else if (y < floorY - 5) {
                chunk.setBlockState(mPos, y < 0 ? DEEPSLATE : STONE, false);
            } else if (y < floorY) {
                chunk.setBlockState(mPos, STONE, false);
            } else if (y == floorY) {
                chunk.setBlockState(mPos, (floorY > SEA_LEVEL - 3) ? SAND : GRAVEL, false);
            } else if (y <= surfY) {
                chunk.setBlockState(mPos, WATER, false);
            } else {
                chunk.setBlockState(mPos, AIR, false);
            }
        }
    }

    // ===== 基础覆写 =====

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getGenDepth() {
        return WORLD_MAX_Y - WORLD_MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return WORLD_MIN_Y;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {
        // 暂不注入生物群系装饰（树/高草/甘蔗等），仅保留 fillFromNoise 基础方块。
        // 待地形定稿后再逐步恢复装饰。
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                              BiomeManager biomeManager, StructureManager structures,
                              ChunkAccess chunk, GenerationStep.Carving carving) {
        // 暂不实现洞穴雕刻
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures,
                              RandomState random, ChunkAccess chunk) {
        // fillFromNoise 已直接设置顶层方块
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // 暂不自定义生物生成
    }

    // ===== 可选覆写（世界预设显示） =====

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        // 调试屏幕信息（可选）
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                              LevelHeightAccessor levelHeightAccessor,
                              RandomState randomState) {
        Cell cell = terrain != null ? terrain.sampleCell(x, z) : null;
        double h = cell != null ? cell.height : SEA_LEVEL;
        return (int) Math.round(h);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                      LevelHeightAccessor levelHeightAccessor,
                                      RandomState randomState) {
        Cell cell = terrain != null ? terrain.sampleCell(x, z) : null;
        double h = cell != null ? cell.height : SEA_LEVEL;
        int iy = (int) Math.round(h);
        BlockState[] states = new BlockState[getGenDepth()];
        for (int i = 0; i < getGenDepth(); i++) {
            int y = getMinY() + i;
            if (y <= iy - 1) states[i] = y < 0 ? DEEPSLATE : STONE;
            else if (y <= SEA_LEVEL) states[i] = WATER;
            else states[i] = AIR;
        }
        return new NoiseColumn(getMinY(), states);
    }
}
