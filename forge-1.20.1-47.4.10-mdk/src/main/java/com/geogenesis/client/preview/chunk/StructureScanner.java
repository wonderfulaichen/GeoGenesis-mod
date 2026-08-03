package com.geogenesis.client.preview.chunk;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构自动检测（轻量版，对齐参考模组 StructStartWorkUnit 的方案）。
 * <p>
 * 参考模组用虚拟 ServerLevel + chunkGenerator.createStructures 全量生成（重型）。
 * 我们更轻：直接用 {@link ChunkGeneratorStructureState#getStructureStarts(ChunkPos)}——
 * 它内部按 StructurePlacement 计算 + biomeSource 验证，返回该 chunk 真实生成的结构起点，
 * 纯计算、无需 ServerLevel，且**含群系验证**（比 placement 粗判更准确）。
 *
 * <p>用法：Worker 线程 {@link #scan(int, int)}，结果缓存（一次检测永久复用，会话内）。
 * 由 {@link ChunkWorkUnit} 采样时顺带调用。
 */
public final class StructureScanner {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    /** 一个结构命中：结构 key + 所在 chunk。 */
    public record Hit(ResourceLocation id, int chunkX, int chunkZ) {}

    /** 已检测的 chunk → 命中列表（空列表 = 已检测但无结构）。 */
    private final Map<Long, List<Hit>> cache = new ConcurrentHashMap<>();

    @Nullable private final ChunkGeneratorStructureState structureState;
    @Nullable private final Registry<Structure> structureRegistry;

    /**
     * 创建扫描器。环境不完整（registryAccess/generator 为 null）时返回 null（预览自动降级为无结构）。
     */
    @Nullable
    public static StructureScanner create(@Nullable RegistryAccess registryAccess,
                                          @Nullable ChunkGenerator generator, long seed) {
        try {
            if (registryAccess == null || generator == null) return null;
            Registry<Structure> reg = registryAccess.registryOrThrow(Registries.STRUCTURE);
            NoiseGeneratorSettings settings = (generator instanceof NoiseBasedChunkGenerator n)
                    ? n.generatorSettings().value() : NoiseGeneratorSettings.dummy();
            RandomState rs = RandomState.create(settings,
                    registryAccess.lookupOrThrow(Registries.NOISE), seed);
            ChunkGeneratorStructureState sstate = generator.createState(
                    registryAccess.lookupOrThrow(Registries.STRUCTURE_SET), rs, seed);
            sstate.ensureStructuresGenerated();  // 预生成要塞 ring 等（参考模组同款）
            return new StructureScanner(sstate, reg);
        } catch (Throwable t) {
            LOGGER.debug("StructureScanner unavailable: {}", t.toString());
            return null;
        }
    }

    private StructureScanner(ChunkGeneratorStructureState structureState,
                             Registry<Structure> structureRegistry) {
        this.structureState = structureState;
        this.structureRegistry = structureRegistry;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** 该 chunk 是否已检测过（含无命中）。 */
    public boolean isScanned(int chunkX, int chunkZ) {
        return cache.containsKey(key(chunkX, chunkZ));
    }

    /** 检测一个 chunk：返回命中的结构列表（可能为空）。线程安全。 */
    public List<Hit> scan(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        List<Hit> existing = cache.get(k);
        if (existing != null) return existing;
        if (structureState == null || structureRegistry == null) return List.of();

        // 1.20.1：StructurePlacement.isStructureChunk(ChunkGeneratorStructureState, int, int)
        // 纯哈希判定"该格是否可能放置该结构"（含间隔/偏移/盐，不含群系验证——粗判）。
        List<Hit> hits = new ArrayList<>(2);
        structureRegistry.holders().forEach(holder -> {
            try {
                Structure structure = holder.value();
                net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement =
                        placementOf(structure);
                if (placement != null && placement.isStructureChunk(structureState, chunkX, chunkZ)) {
                    ResourceLocation id = holder.unwrapKey().map(rk -> rk.location()).orElse(null);
                    if (id != null) hits.add(new Hit(id, chunkX, chunkZ));
                }
            } catch (Throwable ignore) {
                // 个别结构的 placement 校验可能因环境不完整抛异常，跳过
            }
        });
        cache.put(k, hits);
        return hits;
    }

    private static java.lang.reflect.Field PLACEMENT_FIELD;
    private static net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placementOf(Structure structure) {
        try {
            if (PLACEMENT_FIELD == null) {
                java.lang.reflect.Field f = Structure.class.getDeclaredField("placement");
                f.setAccessible(true);
                PLACEMENT_FIELD = f;
            }
            return (net.minecraft.world.level.levelgen.structure.placement.StructurePlacement) PLACEMENT_FIELD.get(structure);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取已缓存结果（主线程渲染用；未检测返回 null）。 */
    @Nullable
    public List<Hit> getHits(int chunkX, int chunkZ) {
        return cache.get(key(chunkX, chunkZ));
    }

    /** 全部命中（渲染遍历用）。 */
    public Iterable<Map.Entry<Long, List<Hit>>> allHits() {
        return cache.entrySet();
    }

    public int scannedChunks() { return cache.size(); }
}
