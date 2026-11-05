package com.geogenesis.worldgen.hydrology;

import com.geogenesis.diagnostics.HydrologyMetrics;
import com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider;
import com.geogenesis.worldgen.hydrology.model.ProcessModelTerrainInput;
import com.geogenesis.worldgen.process.ProcessContext;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.List;

/**
 * 水文 chunk 管线：原始 Cell → 雕刻计划 → 水体统计。
 *
 * <p>★★ 2026-10-20【legacy 链移除（用户决定）】—— model 链是唯一水文实现 ★★</p>
 *
 * <p>历史教训：本类曾是 "config → 系统属性 → false" 的三级开关（{@code useModelPipeline}），
 * 默认 false ⇒ 实机自始至终走 legacy {@code HydrologyBlockCarver}，而大图/探针跑的是
 * model 链 —— "图上有的、游戏里没有"的开关层根因。legacy 线与开关已删（git 留档），
 * 本类现为 model 链的薄壳。</p>
 */
public final class HydrologyChunkEngine {
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    private final CellGenerator generator;
    private final double horizontalScale;
    /**
     * 唯一 B/P 会话水文 provider，首次访问时延迟创建。
     *
     * <p>构造本引擎时 {@code generator.seed()} 尚未运行，此刻既没有
     * {@link ProcessModelTerrainInput#context()} 所需的冻结上下文，也没有真实世界种子。
     * 因此必须等到 {@link #setSeed(long)} 之后、首次请求列计划时才建 provider；
     * 其种子从 {@link ProcessContext.Definition#seed()} 读取，与 B/P 会话逐位同源。</p>
     */
    private volatile ModelHydrologyProvider modelProvider;

    public HydrologyChunkEngine(CellGenerator generator, long ignoredSeed) {
        this.generator = generator;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        LOGGER.info("[HYDRO] model pipeline is the ONLY line (legacy removed 2026-10-20)");
    }

    /** model provider（唯一 B/P 分支；延迟到有种子的冻结上下文出现时创建）。 */
    private ModelHydrologyProvider model() {
        ModelHydrologyProvider p = modelProvider;
        if (p != null) return p;
        synchronized (this) {
            if (modelProvider != null) return modelProvider;
            com.geogenesis.worldgen.process.ProcessRuntime pr = generator.processRuntime();
            if (pr == null) {
                throw new IllegalStateException("B/P runtime is not seeded; call CellGenerator.seed() first");
            }
            long seed = pr.context().definition().seed();
            modelProvider = new ModelHydrologyProvider(new ProcessModelTerrainInput(pr), seed);
            LOGGER.info("[HYDRO] provider 绑定新 B/P（ProcessModelTerrainInput）seed={}", seed);
            return modelProvider;
        }
    }

    /**
     * model 模式恒真（legacy 链已删）。保留方法：落块层的水面语义门
     * （{@code GeoGenesisGenerator.stableWaterSurface}）与绿洲旁路仍消费它。
     */
    public boolean usesModelPipeline() {
        return true;
    }

    public void setSeed(long ignoredSeed) {
        // ★ 换种子 ⇒ 旧 region 内容绝不复用（纯函数铁律）。
        //   model provider 的缓存为 static 共享 + 按 seed 键控 ⇒ 多个地形实例共享同一份 region
        //   （实机实测：5 个地形实例各持实例级缓存 ⇒ 每个 region 被求解两次、
        //    单次 14~18 秒 ⇒ 一半算力白烧，见 ModelHydrologyProvider.SHARED_CACHE）。
        //
        // ★ 2026-11-06：B/P 会话是冻结上下文，种子变了只能整体重建 provider。
        //   CellGenerator.seed() 已经先重建了 processRuntime；这里丢弃旧 provider，
        //   下次 model() 从新上下文的 definition().seed() 读取唯一权威种子。
        ModelHydrologyProvider existing = modelProvider;
        if (existing == null) return;
        synchronized (this) {
            ModelHydrologyProvider old = modelProvider;
            if (old != null) {
                old.clear();
                modelProvider = null;
            }
        }
    }

    public HydrologyChunkResult calculate(int chunkX, int chunkZ) {
        // 【测量】三种粒度分开：完整请求 / 原始重采样 / provider 列计划。
        //   默认关（-Dgeogenesis.hydrologyMetrics=true 才开）⇒ 全部 begin/end 立即返回。
        var scope = HydrologyMetrics.begin("hydro.chunk.calculate", chunkX, chunkZ);
        try {
            Cell[] cells = sampleOriginal(chunkX, chunkZ);
            List<HydrologyBlockCarvedColumn> columns;
            var columnsScope = HydrologyMetrics.begin("hydro.chunk.providerColumns", chunkX, chunkZ);
            try {
                columns = model().columnsFor(chunkX, chunkZ, cells);
            } finally {
                HydrologyMetrics.end(columnsScope);
            }
            int water = 0;
            double maxErosion = 0.0;
            long hash = 0xcbf29ce484222325L;
            for (HydrologyBlockCarvedColumn column : columns) {
                if (column.fillWater()) water++;
                maxErosion = Math.max(maxErosion, column.erosion());
                hash ^= Double.doubleToLongBits(column.carvedGroundY());
                hash *= 0x100000001b3L;
            }
            return new HydrologyChunkResult(cells, columns, water, maxErosion, hash);
        } catch (RuntimeException | Error e) {
            HydrologyMetrics.add("chunk.calculate.failed", 1L);
            throw e;
        } finally {
            HydrologyMetrics.end(scope);
        }
    }

    /** 仅供地形合成复用侵蚀前高度；公开 calculate 仍返回完整、独立的 originalCells。 */
    public List<HydrologyBlockCarvedColumn> calculateColumns(int chunkX, int chunkZ, double[] originalHeights) {
        var scope = HydrologyMetrics.begin("hydro.chunk.calculate", chunkX, chunkZ);
        var columnsScope = HydrologyMetrics.begin("hydro.chunk.providerColumns", chunkX, chunkZ);
        try {
            return model().columnsForHeights(chunkX, chunkZ, originalHeights);
        } catch (RuntimeException | Error e) {
            HydrologyMetrics.add("chunk.calculate.failed", 1L);
            throw e;
        } finally {
            HydrologyMetrics.end(columnsScope);
            HydrologyMetrics.end(scope);
        }
    }

    private Cell[] sampleOriginal(int chunkX, int chunkZ) {
        // 无侵蚀的原始 Cell 重采样，与 provider 内的侵蚀高度查询分开计时。
        var scope = HydrologyMetrics.begin("hydro.chunk.sampleOriginal", chunkX, chunkZ);
        try {
            return HydrologyChunkSampling.sample(generator, horizontalScale, chunkX, chunkZ);
        } finally {
            HydrologyMetrics.end(scope);
        }
    }
}
