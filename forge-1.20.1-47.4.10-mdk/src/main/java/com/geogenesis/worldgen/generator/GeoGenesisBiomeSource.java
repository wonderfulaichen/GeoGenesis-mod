package com.geogenesis.worldgen.generator;

import com.geogenesis.worldgen.cave.CaveBiomeSelector;
import com.geogenesis.worldgen.cave.CaveShape;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.climate.WhittakerType;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * GeoGenesis 群系源。按 Cell 地形类型+气候映射原版群系。
 *
 * 关键修复（创建世界崩溃链）：
 *   1) 初版 getNoiseBiome 返回 null → null Holder 进 biome palette → 序列化 NPE（"Couldn't place player"）。
 *   2) 改用 ForgeRegistries.BIOMES 解析又崩溃：群系是 dynamic(datapack) 注册表，
 *      ForgeRegistries.BIOMES 在世界生成初期（createLevels/createBiomes）尚未同步 → "minecraft:plains not registered"。
 *   3) 最终方案：按 ResourceKey&lt;Biome&gt; 解析时优先用当前服务器的 RegistryAccess
 *      （Forge 在 MinecraftServer 构造期即 setCurrentServer，故 createBiomes 时已就绪，
 *      其 dynamic biome 注册表完整），解析为真实 Holder&lt;Biome&gt;；地形未就绪或解析失败回退 plains（非 null）。
 *   - 群系采样在 ChunkStatus.BIOMES 阶段，早于 fillFromNoise，故地形引擎可独立按需初始化。
 *
 * 注意：Codec 上不要调用 .stable()/withLifecycle()（破坏 BiomeSource.CODEC 派发解码）。
 */
public class GeoGenesisBiomeSource extends BiomeSource {

    public static final String CODEC_ID = "geogenesis:biomesource";
    private static final Logger LOGGER = LogManager.getLogger(CODEC_ID);
    public static final Codec<GeoGenesisBiomeSource> CODEC =
        RecordCodecBuilder.<GeoGenesisBiomeSource>create(instance ->
            instance.group(
                Codec.INT.optionalFieldOf("__seed").forGetter(b -> Optional.<Integer>empty())
            ).apply(instance, seed -> new GeoGenesisBiomeSource())
        );

    /**
     * 分类器可能产出的全部原版群系键（用于 possibleBiomes，结构定位等）。
     *
     * <p>★ 2026-09-10 补全：旧列表漏了暖/冻海洋、冰河、针叶林、恶地、雪坡、疏林丘陵等
     * 分类器实际会产出的键 —— possibleBiomes 缺项会让对应群系不被结构放置器识别
     * （村庄/神庙/掠夺者哨站等按群系标签定位时跳过）。此处按气候主导 v4 映射全量对齐。
     */
    private static final List<ResourceKey<Biome>> ALL_KEYS = List.of(
        // 海洋（按温度带：冻 / 冷 / 常温 / 温 / 暖）
        Biomes.DEEP_FROZEN_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.DEEP_OCEAN,
        Biomes.DEEP_LUKEWARM_OCEAN, Biomes.FROZEN_OCEAN, Biomes.COLD_OCEAN,
        Biomes.OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.WARM_OCEAN,
        // 河流 / 湖泊 / 海岸
        Biomes.RIVER, Biomes.FROZEN_RIVER, Biomes.SWAMP,
        Biomes.BEACH, Biomes.SNOWY_BEACH,
        // 温带（C）
        Biomes.PLAINS, Biomes.FOREST, Biomes.BIRCH_FOREST, Biomes.MEADOW,
        Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_FOREST,
        // 热带（雨季林/雨林/稀树草原）
        Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.SAVANNA,
        Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA,
        // 干旱（B）
        Biomes.DESERT, Biomes.BADLANDS,
        // 冷温带 / 极地（D、E）
        Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.SNOWY_PLAINS,
        Biomes.GROVE, Biomes.SNOWY_SLOPES,
        // 山峰
        Biomes.STONY_PEAKS, Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS,
        // ★ 2026-09-15：洞穴群系（3D 裁定会返回它们）。必须列入 possibleBiomes ——
        //   否则原版的结构定位/群系校验不认这两个群系（同上方注释的既有教训）。
        Biomes.DRIPSTONE_CAVES, Biomes.LUSH_CAVES
    );

    // 回退群系（plains）惰性解析，运行时一旦解析即永久缓存；确保永远非 null。
    private static volatile Holder<Biome> fallback;

    /**
     * 按 ResourceKey&lt;Biome&gt; 解析 Holder&lt;Biome&gt;，每次从当前服务器的 RegistryAccess 实时查找。
     * <p>
     * <b>修复（2026-07-14）</b>：旧版静态缓存 {@code biomeRegistry} 在两次世界加载间
     * 引用已关闭服务器的死 Registry → getHolder() 返空 → fallback 为 null →
     * getNoiseBiome 返 null → biome palette 序列化 id=-1 → 客户端解码崩溃。
     * 新方案：每次实时解析（server.registryAccess() 是 O(1) thread-local 访问，极轻量）。
     * </p>
     */
    private static Holder<Biome> resolveBiome(ResourceKey<Biome> key) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolder(key).orElse(null);
        }
        return null;
    }

    /** 回退群系 — 必须永远非 null。第一次成功解析后永久缓存。 */
    private static Holder<Biome> fallbackBiome() {
        Holder<Biome> h = fallback;
        if (h != null) return h;
        h = resolveBiome(Biomes.PLAINS);
        if (h != null) {
            fallback = h;
            return h;
        }
        // 终极兜底（理论上只在 server 未启动时走到，但此时 getNoiseBiome 不会被调用）
        throw new IllegalStateException(
            "Cannot resolve PLAINS biome — server not available?");
    }

    /** 地形引擎注入点（由 GeoGenesisGenerator.ensureEngine 调用，供群系按 Cell 数据分类）。 */
    private GeoGenesisTerrain terrain;

    /**
     * ★★ 2026-09-15：洞穴群系开关（默认开）。
     *
     * <p>关闭后 {@code getNoiseBiome} 完全不做 3D 裁定 ⇒ 退回旧的纯 2D 行为
     * （供 A/B 对比与故障排查）。</p>
     */
    private static final boolean CAVE_BIOMES_ENABLED = true;
    /** 实例字段（便于探针/调试期关闭；生产恒为上面的常量）。 */
    private boolean caveBiomesEnabled = CAVE_BIOMES_ENABLED;

    /** 供诊断关闭/开启洞穴群系（仅诊断用）。 */
    public void setCaveBiomesEnabled(boolean v) {
        this.caveBiomesEnabled = v;
    }

    // ---- 直接哈希映射缓存（MC 出生点搜索查几千次 quart 位置，避免重复全管线采样） ----
    // ★ 2026-08-09：512 → 65536。出生点搜索/预生成范围可达 ±1000+ chunks（= 4096×4096 quart），
    //   512 条目命中率极低 → 每次 miss 都触发完整 sampleCell（含侵蚀 tile 800ms）→ 世界创建 7.5 分钟。
    //   65536 条目（~1MB）覆盖 256×256 quart = 1024×1024 块，命中率大幅提升。
    private static final int BIOME_CACHE_SIZE = 65536;
    private final long[] biomeCacheKeys = new long[BIOME_CACHE_SIZE];
    private final Holder<Biome>[] biomeCacheValues = new Holder[BIOME_CACHE_SIZE];

    /**
     * ★ 2026-09-15：<b>洞穴群系</b>所需的<b>列级信息</b>缓存（与地表群系缓存同槽位）。
     *
     * <h3>为何缓存"列级信息"而不是"洞穴群系"（设计要点）</h3>
     * <p>洞穴群系是 <b>3D</b> 的（同列不同 Y 可能是滴水石洞/繁茂洞穴/地表群系）。
     * 若把 <b>y 相关结果</b>存进 2D 缓存，同列的不同 y 会<b>互相覆盖</b>
     * （初版就这样写错过，务必勿重犯）。</p>
     *
     * <p>而洞穴判定所需的输入里，{@code surfaceY}/{@code rockTypeId}/{@code biomeType}
     * <b>全都是列级（与 y 无关）</b> ⇒ 把它们打包成一个 {@code int} 缓存，
     * 每次调用再用<b>当前 y</b> 做常数时间裁定。这样：</p>
     * <ul>
     *   <li>缓存语义仍是 2D ⇒ <b>不稀释容量</b>（该容量曾是血泪教训：
     *       512→65536 才解决"世界创建 7.5 分钟"）；</li>
     *   <li>结果可以是 3D 的；</li>
     *   <li>不需要在缓存命中时重新采样 Cell（那会让缓存失去意义）。</li>
     * </ul>
     *
     * <p>打包布局（与 {@code biomeCacheKeys} 同槽位，key 命中即有效）：</p>
     * <pre>
     *   bit  0..15 : surfaceY + 128 + 1   （范围 -64..320 ⇒ 65..449，恒 &gt; 0）
     *   bit 16..19 : rockTypeId（0..7）
     *   bit 20..24 : WhittakerType.ordinal()（0..9）
     *   0 = 未初始化（出生点早退路径不填此表）
     * </pre>
     */
    private final int[] caveColInfo = new int[BIOME_CACHE_SIZE];

    /** 打包列级信息（{@code surfaceY} 偏置 +1 保证非 0 ⇒ 0 可作"未初始化"哨兵）。 */
    private static int packColInfo(int surfaceY, int rockOrd, int biomeOrd) {
        return ((surfaceY + 128 + 1) & 0xFFFF)
                | ((rockOrd & 0xF) << 16)
                | ((biomeOrd & 0x1F) << 20);
    }

    private static int biomeCacheSlot(int qx, int qz) {
        return (qx * 66883 + qz * 51749) & (BIOME_CACHE_SIZE - 1);
    }

    public GeoGenesisBiomeSource() {
    }

    public void setTerrain(GeoGenesisTerrain terrain) {
        this.terrain = terrain;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return ALL_KEYS.stream()
            .map(GeoGenesisBiomeSource::resolveBiome)
            .filter(Objects::nonNull);
    }

    @Override
    public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius,
                                               Climate.Sampler sampler) {
        return possibleBiomes();
    }

    private static boolean biomeInitLogged = false;

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z,
                                        Climate.Sampler sampler) {
        // 1) 查缓存
        int slot = biomeCacheSlot(x, z);
        if (biomeCacheKeys[slot] == ((long)x << 32 | (z & 0xFFFFFFFFL)) && biomeCacheValues[slot] != null) {
            return biomeCacheValues[slot];
        }

        // 2) 地形未初始化时：出生点搜索阶段，快速返回（避免 172ms 全管线初始化阻塞数千次查询）
        if (terrain == null) {
            // quart 坐标 256×256 = 块坐标 1024×1024 = 64 chunk × 64 chunk 出生区域
            if (x >= -256 && x <= 256 && z >= -256 && z <= 256) {
                Holder<Biome> plains = resolveBiome(Biomes.PLAINS);
                if (plains != null) {
                    biomeCacheKeys[slot] = ((long)x << 32 | (z & 0xFFFFFFFFL));
                    biomeCacheValues[slot] = plains;
                    return plains;
                }
            }
            // 超出出生区域 → 正常初始化
            long t0 = System.nanoTime();
            terrain = GeoGenesisGenerator.buildTerrain(GeoGenesisGenerator.resolveParams());
            if (!biomeInitLogged) {
                biomeInitLogged = true;
                long dt = (System.nanoTime() - t0) / 1000000;
                LOGGER.info("[PERF] BiomeSource.getNoiseBiome first call: buildTerrain={}ms at quart({},{})",
                    dt, x, z);
            }
        }
        if (terrain == null) {
            return fallbackBiome();
        }

        // 3) 采样 Cell + 分类
        // ★ 2026-08-09 无伤优化：sampleCellLight（纯 e 场+气候+分类，无侵蚀）替代 sampleCell。
        //   群系分类只用 terrainType/climate（sample() 内已算），侵蚀只改高度细节不影响分类。
        //   根治：出生点搜索/BIOMES stage 零 tile 生成（800ms/tile → ~10μs/次）。
        Cell cell = terrain.sampleCellLight(QuartPos.toBlock(x), QuartPos.toBlock(z));
        ResourceKey<Biome> keyB = cell != null ? BiomeClassifier.pickKey(cell) : null;
        Holder<Biome> h = keyB != null ? resolveBiome(keyB) : null;
        if (h == null) h = fallbackBiome();

        // 4) 写入缓存（地表群系 + 洞穴所需的【列级信息】）
        //   洞穴判定需要 surfaceY/rockTypeId/biomeType —— 三者<b>都与 y 无关</b>，
        //   故可安全地按 2D key 缓存（不稀释容量），每次调用再用当前 y 裁定。
        biomeCacheKeys[slot] = ((long)x << 32 | (z & 0xFFFFFFFFL));
        biomeCacheValues[slot] = h;
        caveColInfo[slot] = cell != null
                ? packColInfo((int) Math.floor(cell.height), cell.rockTypeId, cell.biomeType.ordinal())
                : 0;

        // 5) 本列的洞穴群系裁定（用 y —— 命中缓存时复用列级信息，不重新采样）
        Holder<Biome> cave = caveBiomeAt(x, y, z, slot);
        return cave != null ? cave : h;
    }

    /**
     * ★★ 2026-09-15：洞穴群系裁定（<b>3D</b>，但<b>零采样</b>）。
     *
     * <p>命中列级缓存 ⇒ 只用整数解包 + {@link CaveBiomeSelector} 的纯函数判定；
     * 未初始化（出生点早退路径）或未启用 ⇒ 返回 {@code null}（沿用地表群系）。</p>
     *
     * <p>早退顺序刻意<b>由便宜到昂贵</b>：① 未启用；② 深度窗口（纯整数）；
     * ③ 洞穴几何（噪声）。绝大多数调用在 ② 就返回 —— 这是热路径不退化的关键。</p>
     */
    private Holder<Biome> caveBiomeAt(int x, int y, int z, int slot) {
        if (!caveBiomesEnabled) return null;
        int info = caveColInfo[slot];
        if (info == 0) return null;                       // 未初始化

        int surfaceY = ((info & 0xFFFF) - 128 - 1);
        int rockOrd = (info >>> 16) & 0xF;
        int biomeOrd = (info >>> 20) & 0x1F;
        int wy = QuartPos.toBlock(y);

        // ② 便宜剪枝：地表下 MIN_DEPTH 以内的层不必进洞穴判定
        //    （CaveBiomeSelector 内部也会判，但那要先进噪声求值 ⇒ 这里先拦）
        if (surfaceY - wy < CaveBiomeSelector.MIN_DEPTH) return null;
        // ②b 更深于洞穴深度上限 ⇒ 必然不是洞穴
        if (surfaceY - wy > CaveShape.DEPTH_MAX) return null;

        CaveBiomeSelector.CaveBiome cb = CaveBiomeSelector.select(
                QuartPos.toBlock(x), wy, QuartPos.toBlock(z), surfaceY,
                GeoGenesisGenerator.WORLD_MIN_Y,
                CaveShape.lithoFactor(rockOrd),
                biomeOrd >= 0 && biomeOrd < WhittakerType.values().length
                        ? WhittakerType.values()[biomeOrd] : null);
        return switch (cb) {
            case DRIPSTONE -> caveHolder(Biomes.DRIPSTONE_CAVES);
            case LUSH -> caveHolder(Biomes.LUSH_CAVES);
            case NONE -> null;
        };
    }

    /** 解析洞穴群系（失败回退地表群系 ⇒ 返回 null 让调用方用 {@code h}）。 */
    private Holder<Biome> caveHolder(ResourceKey<Biome> key) {
        return resolveBiome(key);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
