package com.geogenesis.worldgen.hydrology;

import com.geogenesis.diagnostics.HydrologyMetrics;
import com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider;
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
     * 延迟创建：{@code GeoGenesisTerrain} 构造本引擎时 {@code generator.seed()} 尚未运行，
     * 此刻 {@code processRuntime()} 还是 null。若在构造期就决定 provider 类型，
     * 会永远绑不上新 B/P（实测会静默退化为旧 CellGenerator 路径）。
     * ⇒ 首次访问时按当时状态决定，并记录一条日志供实机确认。
     */
    private volatile ModelHydrologyProvider modelProvider;
    /**
     * 真实世界种子。构造期传进来的是 0L（terrain 构造时还没有种子），
     * {@link #setSeed(long)} 之后才是真实值。
     * ⚠ 构造 provider 必须用这里的真实种子：用 0L 会撞上
     * {@code ModelHydrologyProvider} 的 "model seed differs from frozen B/P seed" 断言
     * （实机在配置屏开预览时崩过，见 crash-2026-11-05_23.47.40-client.txt）。
     */
    private volatile long worldSeed;

    public HydrologyChunkEngine(CellGenerator generator, long seed) {
        this.generator = generator;
        this.worldSeed = seed;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        LOGGER.info("[HYDRO] model pipeline is the ONLY line (legacy removed 2026-10-20)");
    }

    /** model provider（首次访问时按 generator 当时是否启用新 P 层决定绑定）。 */
    private ModelHydrologyProvider model() {
        ModelHydrologyProvider p = modelProvider;
        if (p != null) return p;
        synchronized (this) {
            if (modelProvider != null) return modelProvider;
            long seed = worldSeed;
            com.geogenesis.worldgen.process.ProcessRuntime pr = generator.processRuntime();
            if (pr != null) {
                modelProvider = new ModelHydrologyProvider(
                        new com.geogenesis.worldgen.hydrology.model.ProcessModelTerrainInput(pr), seed);
                LOGGER.info("[HYDRO] provider 绑定新 B/P（ProcessModelTerrainInput）seed={}", seed);
            } else {
                modelProvider = new ModelHydrologyProvider(generator, seed);
            }
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

    public void setSeed(long seed) {
        // ★ 换种子 ⇒ 旧 region 内容绝不复用（纯函数铁律）。
        //   model provider 的缓存为 static 共享 + 按 seed 键控 ⇒ 多个地形实例共享同一份 region
        //   （实机实测：5 个地形实例各持实例级缓存 ⇒ 每个 region 被求解两次、
        //    单次 14~18 秒 ⇒ 一半算力白烧，见 ModelHydrologyProvider.SHARED_CACHE）。
        //
        // ★ 2026-11-05：先记种子，再处理已有 provider。
        //   若 provider 已按旧 runtime 建好，它绑死的 B/P 上下文（含 seed）就是旧的 ⇒
        //   换种子后必须整体重建，否则会一直读旧种子的地形（或在断言处崩溃）。
        worldSeed = seed;
        ModelHydrologyProvider existing = modelProvider;
        if (existing == null) return;
        if (generator.processRuntime() != null) {
            // 新 P 层：provider 与 runtime 绑定，种子变了只能重建。
            synchronized (this) {
                ModelHydrologyProvider old = modelProvider;
                if (old != null) old.setSeed(seed);
                modelProvider = null;   // 下次 model() 按新 runtime 重建
            }
        } else {
            existing.setSeed(seed);
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
