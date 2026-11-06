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

    // Resolve fallback from the live registry; never retain a previous world's Holder.

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

    /** Resolve against the current server only; never cache pre-binding fallback. */
    private static Holder<Biome> fallbackBiome() {
        Holder<Biome> h = resolveBiome(Biomes.PLAINS);
        if (h != null) return h;
        // 终极兜底（理论上只在 server 未启动时走到，但此时 getNoiseBiome 不会被调用）
        throw new IllegalStateException(
            "Cannot resolve PLAINS biome — server not available?");
    }

    /** 地形引擎注入点（由 GeoGenesisGenerator.ensureEngine 调用，供群系按 Cell 数据分类）。 */
    private volatile CacheGeneration generation;

    /** Immutable identity; mutable slot contents live exclusively inside this generation. */
    private static final class CacheGeneration {
        final GeoGenesisTerrain terrain;
        final long version;
        final long seed;
        final BiomeColumnCache<Holder<Biome>> cache = new BiomeColumnCache<>();

        CacheGeneration(GeoGenesisTerrain terrain, long version, long seed) {
            this.terrain = terrain;
            this.version = version;
            this.seed = seed;
        }
    }

    /**
     * ★★ 2026-09-15：洞穴群系开关。
     *
     * <p><b>单一来源</b>：直接读 {@link CaveShape#config()}（由
     * {@code GeoGenesisGenerator.setWorldSeed} 注入的配置）——
     * <b>不再自带一份字段</b>，避免"两套开关不一致"（B 源开、A 源关 ⇒ 群系漂移）。
     * 洞穴关闭时群系也自动关闭（没有洞就没有洞穴群系）。</p>
     */
    private boolean caveBiomesEnabled() {
        return CaveShape.config().caveBiomes && CaveShape.isEnabled();
    }

    // The shared direct-map key/value/info arrays are owned by CacheGeneration.cache.
    // Preserve the existing miss-only cave override; hits return the surface biome.

    /** 打包列级信息（{@code surfaceY} 偏置 +1 保证非 0 ⇒ 0 可作"未初始化"哨兵）。 */
    private static int packColInfo(int surfaceY, int rockOrd, int biomeOrd) {
        return ((surfaceY + 128 + 1) & 0xFFFF)
                | ((rockOrd & 0xF) << 16)
                | ((biomeOrd & 0x1F) << 20);
    }

    public GeoGenesisBiomeSource() {
    }

    public void setTerrain(GeoGenesisTerrain terrain) {
        synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE) {
            var lifecycle = GeoGenesisGenerator.WORLD_LIFECYCLE;
            if (terrain == null || !lifecycle.isBound() || lifecycle.current() != terrain) {
                generation = null;
                return;
            }
            CacheGeneration old = generation;
            if (old == null || old.terrain != terrain || old.version != lifecycle.version()) {
                generation = new CacheGeneration(terrain, lifecycle.version(), lifecycle.seed());
            }
        }
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

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z,
                                        Climate.Sampler sampler) {
        try (var diag = com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()
                ? com.geogenesis.diagnostics.FrontendAttribution.begin("getNoiseBiome") : null) {
            for (;;) {
                CacheGeneration captured;
                if (diag != null) diag.waitingForLifecycle();
                synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE) {
                    if (diag != null) diag.acquired();
                    if (!GeoGenesisGenerator.WORLD_LIFECYCLE.isBound()) {
                        if (diag != null) diag.outcome("preBindingFallback");
                        setTerrain(null);
                        return fallbackBiome(); // Never cache pre-Load fallback, at any coordinate.
                    }
                    setTerrain(GeoGenesisGenerator.buildTerrain(GeoGenesisGenerator.resolveParams()));
                    captured = generation;
                }
                if (diag != null) diag.runtime("terrain=" + System.identityHashCode(captured.terrain)
                        + " seed=" + captured.seed);
                Holder<Biome> result;
                try {
                    result = sampleBoundBiome(captured, x, y, z, diag);
                } catch (RuntimeException failure) {
                    // Registry teardown or global cave reseeding can interrupt an old sample.
                    // Never mask a failure belonging to the still-current session.
                    synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE) {
                        if (generation == captured && GeoGenesisGenerator.WORLD_LIFECYCLE.matches(
                                captured.version, captured.terrain)) throw failure;
                    }
                    continue;
                }
                // No stripe is held here. The final check is the return linearization point.
                synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE) {
                    if (result != null && generation == captured
                            && GeoGenesisGenerator.WORLD_LIFECYCLE.matches(
                                    captured.version, captured.terrain)) return result;
                }
            }
        }
    }

    private Holder<Biome> sampleBoundBiome(CacheGeneration captured, int x, int y, int z,
            com.geogenesis.diagnostics.FrontendAttribution.Scope diag) {
        return captured.cache.query(x, z,
                () -> generation == captured
                        && GeoGenesisGenerator.WORLD_LIFECYCLE.version() == captured.version,
                () -> sampleColumn(captured.terrain, x, y, z, diag),
                () -> { if (diag != null) diag.outcome("cacheHit"); });
    }

    private BiomeColumnCache.Column<Holder<Biome>> sampleColumn(GeoGenesisTerrain terrain,
            int x, int y, int z, com.geogenesis.diagnostics.FrontendAttribution.Scope diag) {
        if (diag != null) diag.outcome("cacheMiss");
        // 3) 采样 Cell + 分类
        // sampleCellLight 当前包含精确 P 请求：冷查询可生成并等待侵蚀 tile。
        // P 会改变洞穴分类的 surfaceY，也可能通过 terrainType 重分类影响地表群系。
        // Sampling is outside WORLD_LIFECYCLE; do not bypass P or alter classification fields.
        Cell cell;
        if (diag != null) diag.sampleBegin();
        try {
            cell = terrain.sampleCellLight(QuartPos.toBlock(x), QuartPos.toBlock(z));
        } finally {
            if (diag != null) diag.sampleEnd();
        }
        ResourceKey<Biome> keyB = cell != null ? BiomeClassifier.pickKey(cell) : null;
        Holder<Biome> h = keyB != null ? resolveBiome(keyB) : null;
        if (h == null) h = fallbackBiome();

        int info = cell != null
                ? packColInfo((int) Math.floor(cell.height), cell.rockTypeId, cell.biomeType.ordinal())
                : 0;
        // CaveShape/selector may be reseeded concurrently. A lifecycle version change
        // discards the entire result before publication and retries in the new session.
        Holder<Biome> cave = caveBiomeAt(x, y, z, info);
        return new BiomeColumnCache.Column<>(h, info, cave != null ? cave : h);
    }

    /**
     * ★★ 2026-09-15：洞穴群系裁定（<b>3D</b>，但<b>零采样</b>）。
     *
     * <p>Only called on a cache miss, using freshly sampled column information.
     * Cache hits intentionally retain the existing surface-only return semantics.
     * 未初始化或未启用 ⇒ 返回 {@code null}（沿用地表群系）。</p>
     *
     * <p>早退顺序刻意<b>由便宜到昂贵</b>：① 未启用；② 深度窗口（纯整数）；
     * ③ 洞穴几何（噪声）。绝大多数调用在 ② 就返回 —— 这是热路径不退化的关键。</p>
     */
    private Holder<Biome> caveBiomeAt(int x, int y, int z, int info) {
        if (!caveBiomesEnabled()) return null;
        if (info == 0) return null;                       // 未初始化

        int surfaceY = ((info & 0xFFFF) - 128 - 1);
        int rockOrd = (info >>> 16) & 0xF;
        int biomeOrd = (info >>> 20) & 0x1F;
        int wy = QuartPos.toBlock(y);

        // ② 便宜剪枝：地表下 minDepth 以内的层不必进洞穴判定
        //    （CaveBiomeSelector 内部也会判，但那要先进噪声求值 ⇒ 这里先拦）
        //    minDepth 用与 CaveBiomeSelector 相同的口径（含洞顶保护）
        if (surfaceY - wy < Math.max(CaveBiomeSelector.MIN_DEPTH, CaveShape.surfaceLid())) {
            return null;
        }
        // ②b 更深于洞穴深度上限 ⇒ 必然不是洞穴（用配置生效值）
        if (surfaceY - wy > CaveShape.depthMax()) return null;

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
