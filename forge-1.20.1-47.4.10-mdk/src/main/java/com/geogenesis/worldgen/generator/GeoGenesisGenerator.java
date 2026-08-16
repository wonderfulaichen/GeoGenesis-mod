package com.geogenesis.worldgen.generator;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.river.RiverCarver;
import com.geogenesis.worldgen.river.RiverSample;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainClass;
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
 * CODEC 仅序列化 biome_source；地形参数走按存档级 {@code GeoGenesisWorldData}（不进 level.dat 生成器 Codec），
 * 运行时由 resolveParams() 解析（存档优先，缺失回退全局 toml 默认模板）。
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

    // 地形引擎（每生成器实例一份）。参数来自当前世界存档，种子来自 LevelEvent.Load。
    private GeoGenesisTerrain terrain;

    /**
     * 当前世界的地形参数（按存档级）。由 {@link com.geogenesis.GeoGenesisServerEvents} 在主世界加载时注入；
     * 为 null 时运行时回退到全局 toml 默认模板（{@link GeoGenesisConfig#INSTANCE}）。
     * <p>
     * 用静态持有：单 JVM 同一时刻只有一个主世界，与现有 {@code worldSeed} 静态方案一致。
     */
    private static volatile TerrainParams currentWorldParams = null;

    /** 注入当前世界参数（来自 WorldSavedData；null = 用全局 toml 默认）。 */
    public static void setCurrentWorldParams(TerrainParams p) {
        currentWorldParams = p;
    }

    /** 解析当前世界参数：存档优先，缺失回退全局 toml。 */
    public static TerrainParams resolveParams() {
        return currentWorldParams != null ? currentWorldParams : GeoGenesisConfig.INSTANCE.buildParams();
    }

    /**
     * 用给定参数 + 当前世界种子构建（已播种）地形引擎。
     *
     * ★ 2026-08-14 卡死修复：**共享单例**——河网构建（RiverBuilder.mountainPath）触发
     *   discharge 采样 → 侵蚀 tile 同步生成（670ms/个），20 个 Worker 线程各 new 一套
     *   = 并发重复构建 + 重复生成 → 世界生成卡死（日志 37% 后无输出）。单例后只构建
     *   一次，跨线程共享（内部 ConcurrentHashMap 线程安全）。
     */
    private static volatile GeoGenesisTerrain sharedTerrain;

    public static GeoGenesisTerrain buildTerrain(TerrainParams params) {
        GeoGenesisTerrain t = sharedTerrain;
        if (t == null) {
            synchronized (GeoGenesisGenerator.class) {
                t = sharedTerrain;
                if (t == null) {
                    CellGenerator gen = new CellGenerator(params, WORLD_MIN_Y, WORLD_MAX_Y);
                    gen.seed(worldSeed);
                    sharedTerrain = new GeoGenesisTerrain(gen);
                    t = sharedTerrain;
                }
            }
        }
        return t;
    }

    public GeoGenesisGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    // ===== 初始化 =====

    /**
     * 惰性初始化地形引擎并注入 BiomeSource，保证 biome 采样与方块填充基于同一地形场。
     */
    private void ensureEngine(long seed) {
        if (terrain == null) {
            terrain = buildTerrain(resolveParams());
            // inject terrain into BiomeSource so it can classify biomes by Cell data
            if (biomeSource instanceof GeoGenesisBiomeSource gbs) {
                gbs.setTerrain(terrain);
                LOGGER.info("GeoGenesis terrain injected into BiomeSource (seed={})", seed);
            }
        }
    }

    // ===== 核心：fillFromNoise =====

    // 当前世界种子（由 GeoGenesisServerEvents.LevelEvent.Load 注入）
    private static volatile long worldSeed = 12345L;

    public static void setWorldSeed(long seed) {
        worldSeed = seed;
        // ★ 2026-08-14 单例失效：新世界 seed 变化 → 下次 buildTerrain 重建河网/地形
        sharedTerrain = null;
        LOGGER.info("GeoGenesis world seed set to {} (terrain singleton invalidated)", seed);
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
        // ★ 2026-08-09 优化：出生点周边异步预热（首次 fillFromNoise 触发一次，后台池）
        terrain.preloadSpawnAsync();
        long t1 = System.nanoTime();

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        long t2 = System.nanoTime();
        Cell[] cells = terrain.getChunkCells(pos.x, pos.z);
        long t3 = System.nanoTime();

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        boolean riversOn = terrain.riversEnabled();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];

                // Phase 2：确定性河网纯函数采样（开关关闭 → NONE，零开销）
                // ★ 2026-08-16 R18：海洋列也采样（仅主河 REACH/MOUTH 生效——
                //   RiverCarver 内支流入海被拒、主河雕贴海床浅槽，水下河谷连续
                //   延伸入海，河口不"凭空消失"；DW getRiverCarve 同款）。
                //   历史注释"海洋列跳过采样"作废（曾导致海底起点主河无引导）。
                RiverSample rs = riversOn
                        ? terrain.sampleRiverAtBlock(baseX + lx, baseZ + lz)
                        : RiverSample.NONE;
                fillTerrainColumn(chunk, mPos, baseX + lx, baseZ + lz, cell, rs);
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
     * 地形列填充：按 cell.height 铺 基岩/深板岩/石/土/表层/水柱；
     * 河流列（RiverSample.inChannel）先做河谷雕刻（河心切到河床、岸坡过渡，只切不抬）。
     *
     * 关键修复（旧版 bug）：
     * - 地表用 {@code y == surfaceY}（surfaceY = floor(height)），不再用 {@code y == (int)height}
     *   整等——小数高度下草层永远铺不上（大面积裸石）。
     * - 海洋列补齐水柱（surfaceY+1 .. SEA_LEVEL 填水），旧版 else-if 链使海洋无水。
     * - 表层方块随 terrainType/水陆决定：陆地 GRASS+DIRT，海滩/浅水 SAND，深水 GRAVEL。
     */
    private void fillTerrainColumn(ChunkAccess chunk, BlockPos.MutableBlockPos mPos,
                                   int wx, int wz, Cell cell,
                                   com.geogenesis.worldgen.river.RiverSample rs) {
        // 河谷雕刻（纯函数计划，R9 DW 完整模型）：地面高/水面/墙区标志
        double groundY = cell.height;
        boolean riverWater = false;
        boolean riverWall = false;      // 墙区（地形低于水面 → 抬填 + 顶草皮）
        double waterTop = SEA_LEVEL;
        if (rs.inChannel()) {
            RiverCarver.CarvedColumn cc = RiverCarver.carve(cell.height, rs, wx, wz, SEA_LEVEL);
            // ★ 2026-08-15 R16b 修复（用户"实测无效"实锤）：carve 可能返回 NONE
            //   （skip 条件：地形 < 海平面−4 → 不雕刻）。NONE.groundY=0、
            //   waterTop=-Inf——无条件取用 → 海上/低地河整列塌陷到 Y=0 黑洞！
            //   必须检查 cc.inChannel() 才应用雕刻结果，否则保留原地形。
            if (cc.inChannel()) {
                groundY = cc.groundY();
                riverWater = true;
                riverWall = cc.isWall();
                waterTop = cc.waterTopY();
            }
        }
        int surfaceY = (int) Math.floor(groundY);
        boolean water = cell.isWater() || riverWater;         // 实测海平面 e<0
        boolean beach = cell.terrainType == TerrainClass.BEACH;

        // 表层与填充块选择（R9：墙顶草皮、墙壁土、河心砾石——DW top/filler/base 语义）
        BlockState top, fill;
        if (riverWall) {
            top  = GRASS;  // 墙顶 = 岸顶草皮（DW：y==repairTopY && originalY<=top+2 → 草）
            fill = DIRT;
        } else if (riverWater) {
            if (groundY < waterTop - 3) { top = GRAVEL; fill = GRAVEL; }   // 河床
            else if (groundY < waterTop) { top = SAND; fill = SAND; }      // 浅岸
            else { top = DIRT; fill = DIRT; }                              // 露出河岸裸土
        } else if (water) {
            top  = (groundY <= SEA_LEVEL - 3) ? GRAVEL : SAND;  // 深水砾石 / 浅水沙
            fill = top;
        } else if (beach) {
            top  = SAND;
            fill = SAND;
        } else {
            top  = GRASS;
            fill = DIRT;
        }

        // R9 落块（DW 语义）：地表按 groundY 铺、水柱灌到 waterTop；墙区地面已被
        // carve 抬到水面 → 落块自然形成堤岸（groundY=水面 → 水柱 1 块 + 墙顶草皮）。
        int waterTopBlock = (int) Math.floor(waterTop);
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
            } else if (water && y <= waterTopBlock) {
                state = WATER;                                    // 水柱（水面以下）
            } else {
                break;                                            // 地表/水面以上：默认 AIR，跳过
            }
            chunk.setBlockState(mPos, state, false);
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
