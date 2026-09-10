package com.geogenesis.worldgen.generator;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.hydrology.HydrologyChunkResult;
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
    /**
     * 陡坡裸岩的坡度阈值（无量纲 tan 坡角，按 2wu≈4 块中心差分测得）：>0.40 地表出露岩石。
     *
     * <p>RTF 范式：{@code Steepness} tile filter 算 gradient，{@code ErodeFeature} 用
     * 0.3 起、0.65+ 为重岩分档。此处取单档 0.40 —— 介于 RTF 起始 0.3 与保守值之间：
     * 实测（384 wu、含海陆）>0.30 覆盖 6.3%、>0.45 覆盖 2.7%，0.40 落在真实陡坡上，
     * 平缓丘陵不会误判成裸岩。
     *
     * <p>注意：山地群系本身已由 {@code surfaceOf} 返回 STONE，本阈值主要让
     * <b>森林/草原里的陡崖与台地边缘</b>也出露岩石。
     */
    static final float ROCK_GRADIENT = 0.40f;

    // 方块状态缓存
    private static final BlockState AIR       = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE     = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    private static final BlockState WATER     = Blocks.WATER.defaultBlockState();
    // 表层流动水观感（方案 B）：WATER 的 LEVEL=1 即流动态（本版无 Blocks.FLOWING_WATER 映射）
    private static final BlockState FLOWING_WATER = Blocks.WATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL, 1);
    private static final BlockState BEDROCK   = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState DIRT      = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS     = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND      = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL    = Blocks.GRAVEL.defaultBlockState();
    /** 雪层（1/8 层，原版 Blocks.SNOW）—— 雪线以上地表覆盖 */
    private static final BlockState SNOW      = Blocks.SNOW.defaultBlockState();
    private static final BlockState PODZOL    = Blocks.PODZOL.defaultBlockState();

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
                    GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
                    // ★ 2026-08-26 修复：必须走 terrain.seed() —— 它内部调 generator.seed +
                    //   rivers.setWorldSeed。只 gen.seed 会漏掉河网种子（所有世界同一河网）。
                    terrain.seed(worldSeed);
                    sharedTerrain = terrain;
                    t = terrain;
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
        // ★ 2026-08-29：旧 RTF 河网已下线，水文模型是唯一的河流实现，故河流开关
        //   统一为配置面板 riverEnabled（原 HydrologyExperimentSwitch 的 LEGACY_RTF
        //   模式已无对应实现，保留会导致"地形被水文雕刻却不灌水"的干河谷）。
        // ★ 2026-09-08 侵蚀修复终版（管线顺序：河流建网在前、侵蚀在后）：
        //   统一走 getChunkCells（sample + extractFromTile 侵蚀 + applyHydrologyValley
        //   雕刻减法 + delta 移位）。旧 applyHydrologyChunk 的 carvedGroundY 直接覆盖
        //   会把侵蚀整条丢掉（用户实测："侵蚀只在预览工作，游戏里不工作"）；
        //   而给水文采样改 sampleWu 的首修又让河网构建期触发侵蚀 tile 冷生成
        //   （预览开窗即卡）。终版：建网无侵蚀（快），侵蚀在落块合成时叠加（生效）。
        boolean hydrologyOn = terrain.riversEnabled();
        Cell[] cells = terrain.getChunkCells(pos.x, pos.z);
        long t3 = System.nanoTime();

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                fillTerrainColumn(chunk, mPos, baseX + lx, baseZ + lz, cell, hydrologyOn);
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
     * 地形列填充：按 cell.height 铺 基岩/深板岩/石/土/表层/水柱。
     * 河谷雕刻与水柱判定均由水文雕刻计划预先写入 cell（cell.height 已含雕刻量，
     * riverType/riverSurfaceY 给出灌水判据），本方法只负责落块，不再二次采样河网。
     *
     * 关键修复（旧版 bug）：
     * - 地表用 {@code y == surfaceY}（surfaceY = floor(height)），不再用 {@code y == (int)height}
     *   整等——小数高度下草层永远铺不上（大面积裸石）。
     * - 海洋列补齐水柱（surfaceY+1 .. SEA_LEVEL 填水），旧版 else-if 链使海洋无水。
     * - 表层方块随 terrainType/水陆决定：陆地 GRASS+DIRT，海滩/浅水 SAND，深水 GRAVEL。
     */
    private void fillTerrainColumn(ChunkAccess chunk, BlockPos.MutableBlockPos mPos,
                                   int wx, int wz, Cell cell,
                                   boolean hydrologyOn) {
        // ★ RTF 范式（2026-08-26）：河谷已由 generateChunk 雕刻回写 cell.height（Zone1-4
        //   平滑谷），此处不再二次雕刻，仅按 waterTable 水面做灌水判定（Streams isStreamBed：
        //   雕刻后地面 < 水面 − 0.5 才灌水；岸坡/漫滩只塑形不上水）。
        double groundY = cell.height;
        boolean riverWater = false;
        boolean riverWall = false;
        double waterTop = SEA_LEVEL;
        if (hydrologyOn) {
            // ★ 水文模型：灌水判定完全由水文雕刻计划决定（fillWater 已含河道中心门控）
            if (cell.riverType != 0 && cell.riverSurfaceY > groundY) {
                riverWater = true;
                waterTop = cell.riverSurfaceY;
            }
        }
        int surfaceY = (int) Math.floor(groundY);
        // 海洋补灌必须与 riverType 正交：入海河列仍是海洋列，不能因被河计划命中就失去
        // sea-level 水柱。只依据原始地形分类识别海洋，避免把内陆雕刻河床抬到海平面。
        // 海洋补灌：地面低于海平面的列即为海域，不再要求 cell.isWater()（e<0），
        // 否则入海口两岸 e≥0 但 groundY<seaLevel 的列既无河水也无海水→出现缺角。
        double seaLevel = terrain.seaLevel();
        boolean ocean = hydrologyOn && groundY < seaLevel;
        if (ocean) waterTop = Math.max(waterTop, seaLevel);
        // 水文模式：灌水 = 河计划 ∪ 海洋；入海重叠列取两者较高水面（即海平面）。
        boolean water = hydrologyOn ? (riverWater || ocean) : (cell.isWater() || riverWater);
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
        } else if (cell.gradient > ROCK_GRADIENT) {
            // 陡坡裸岩（RTF Steepness + ErodeFeature 范式）：陡崖不长植被、积不住沙，
            // 地表直接出露岩石 —— 与群系/地表材质无关（沙漠里的陡崖同样是裸岩）。
            top  = STONE;
            fill = STONE;
        } else {
            // 地表方块由【群系】决定（BiomeClassifier.surfaceOf）——此前只看地形类型，
            // 导致沙漠群系也铺草方块。BEACH 已在上面单独处理。
            switch (BiomeClassifier.surfaceOf(cell)) {
                case SAND   -> { top = SAND;   fill = SAND; }
                case STONE  -> { top = STONE;  fill = STONE; }
                case GRAVEL -> { top = GRAVEL; fill = GRAVEL; }
                case PODZOL -> { top = PODZOL; fill = DIRT; }
                default     -> { top = GRASS;  fill = DIRT; }
            }
        }

        // R9 落块（DW 语义）：地表按 groundY 铺、水柱灌到 waterTop；墙区地面已被
        // carve 抬到水面 → 落块自然形成堤岸（groundY=水面 → 水柱 1 块 + 墙顶草皮）。
        int waterTopBlock = (int) Math.floor(waterTop);
        // ★ 瀑布水幕（2026-08-30）：跌水列在水面之上再挂一段垂直流动水（潭面 → 唇口），
        //   即旧 Streams fillRiver 的 `yDownstreamSurface+1 .. ySurface` 语义——
        //   瀑布不是几何特例，只是"本列两个水位之间的垂直水体"。
        //   普通列 riverLipY == riverSurfaceY → lipBlock == waterTopBlock，无副作用。
        int lipBlock = (riverWater && cell.riverLipY > waterTop)
                ? (int) Math.floor(cell.riverLipY) : waterTopBlock;
        int fillTopBlock = Math.max(waterTopBlock, lipBlock);
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
            } else if (water && y <= fillTopBlock) {
                if (y > waterTopBlock) {
                    state = FLOWING_WATER;          // 瀑布水幕：垂直悬挂的流动水
                } else {
                    // 表层用流动水（方案 B 流动观感）；其余静水预填，确定性、永不断裂
                    state = (y == waterTopBlock) ? FLOWING_WATER : WATER; // 水柱（水面以下）
                }
            } else {
                break;                                            // 地表/水面以上：默认 AIR，跳过
            }
            chunk.setBlockState(mPos, state, false);
        }

        // 雪线以上铺雪层：isSnow 由 CellGenerator 按 snowLine/纬度/湿度配置统一计算，
        // 与 BiomeClassifier 的垂直带共用同一阈值（消除此前"两套雪线"）。
        if (!water && cell.isSnow) {
            int snowY = surfaceY + 1;
            if (snowY < WORLD_MAX_Y) {
                mPos.set(wx, snowY, wz);
                chunk.setBlockState(mPos, SNOW, false);
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
        // 重新启用原版群系装饰：委托基类 ChunkGenerator.applyBiomeDecoration。
        // 基类按 FEATURES 状态遍历群系，用 biomeSource 返回的群系（原版 biome Holder）
        // 按其 BiomeGenerationSettings 放置树/草/花/甘蔗等特征。
        // 地表已由 fillFromNoise 在 NOISE 阶段铺好（草/沙/砾石顶块），
        // 装饰在 FEATURES 阶段叠加，顺序正确，无需自研放置逻辑。
        super.applyBiomeDecoration(level, chunk, structureManager);
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
        // ★ 2026-09-11 P0-1 止血：原走 terrain.sampleCell → getChunkCells → 冷侵蚀 tile
        //   （实测 400~719 ms/次）。而 STRUCTURE_STARTS 早于 NOISE → 此处必然是未生成 chunk，
        //   等于把全管线最贵的操作接到最热的调用点 → 世界生成卡死。
        //   改走【非阻塞】两级降级：
        //     ① 已生成 chunk → 取缓存 Cell，与落块完全一致（零额外成本）
        //     ② 未生成 chunk → 廉价重算（基础场 + 已缓存侵蚀增量），绝不触发侵蚀 tile
        double h = terrain != null ? terrain.sampleHeightNonBlocking(x, z) : SEA_LEVEL;
        return (int) Math.round(h);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                      LevelHeightAccessor levelHeightAccessor,
                                      RandomState randomState) {
        // 同 getBaseHeight：非阻塞两级降级，绝不触发侵蚀 tile（详见上方注释）
        double h = terrain != null ? terrain.sampleHeightNonBlocking(x, z) : SEA_LEVEL;
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
