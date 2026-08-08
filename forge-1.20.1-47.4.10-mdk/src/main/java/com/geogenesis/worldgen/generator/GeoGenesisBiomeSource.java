package com.geogenesis.worldgen.generator;

import com.geogenesis.worldgen.climate.BiomeClassifier;
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

    /** 分类器可能产出的全部原版群系键（用于 possibleBiomes，结构定位等）。 */
    private static final List<ResourceKey<Biome>> ALL_KEYS = List.of(
        Biomes.DEEP_COLD_OCEAN, Biomes.COLD_OCEAN, Biomes.OCEAN,
        Biomes.SWAMP, Biomes.RIVER,
        Biomes.SNOWY_BEACH, Biomes.BEACH,
        Biomes.SNOWY_PLAINS, Biomes.SAVANNA, Biomes.PLAINS,
        Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_SAVANNA, Biomes.FOREST,
        Biomes.SAVANNA_PLATEAU, Biomes.BIRCH_FOREST,
        Biomes.STONY_PEAKS, Biomes.JUNGLE,
        Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS,
        Biomes.DESERT, Biomes.MEADOW
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

    // ---- 直接哈希映射缓存（MC 出生点搜索查几千次 quart 位置，避免重复全管线采样） ----
    // ★ 2026-08-09：512 → 65536。出生点搜索/预生成范围可达 ±1000+ chunks（= 4096×4096 quart），
    //   512 条目命中率极低 → 每次 miss 都触发完整 sampleCell（含侵蚀 tile 800ms）→ 世界创建 7.5 分钟。
    //   65536 条目（~1MB）覆盖 256×256 quart = 1024×1024 块，命中率大幅提升。
    private static final int BIOME_CACHE_SIZE = 65536;
    private final long[] biomeCacheKeys = new long[BIOME_CACHE_SIZE];
    private final Holder<Biome>[] biomeCacheValues = new Holder[BIOME_CACHE_SIZE];

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

        // 4) 写入缓存
        biomeCacheKeys[slot] = ((long)x << 32 | (z & 0xFFFFFFFFL));
        biomeCacheValues[slot] = h;

        return h;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
