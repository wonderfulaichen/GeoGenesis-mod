package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.List;

/** 完整水文 chunk 实验管线：原始 Cell → 雕刻计划 → 水体统计。 */
public final class HydrologyChunkEngine {
    /** 日志（诊断用）：确认 model 链开关【实际生效值】（同 skeletonRouting 的三态排查）。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    private final CellGenerator generator;
    private final HydrologyExperimentEngine hydrology;
    private final double horizontalScale;
    /** ★ model 链区域缓存提供者（恒创建：开关关时零成本，仅持一个小空缓存）。 */
    private final ModelHydrologyProvider modelProvider;
    /** ★ 是否用 model 链产出雕刻计划（配置 + 系统属性覆盖）。 */
    private final boolean useModelPipeline;

    public HydrologyChunkEngine(CellGenerator generator, long seed) {
        this.generator = generator;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        this.hydrology = new HydrologyExperimentEngine(generator, seed);
        this.modelProvider = new ModelHydrologyProvider(generator);
        boolean model = false;
        try {
            model = com.geogenesis.config.GeoGenesisConfig.INSTANCE.hydrologyModelPipelineEnabled.get();
        } catch (RuntimeException e) {
            // 探针/预览进程：Forge 配置未加载 ⇒ 回落系统属性（默认 false = legacy）
            String v = System.getProperty("geogenesis.modelPipeline");
            if (v != null) model = Boolean.parseBoolean(v);
        }
        this.useModelPipeline = model;
        // ★ 必打日志（用户判据："我在游戏里面看到的还是旧版？"）：
        //   配置文件旧值 / 模型静默失败回退，两种情况由本行的【实际生效值】区分。
        LOGGER.info("[HYDRO] modelPipeline config={} => effective={}", model, useModelPipeline);
    }

    /**
     * 只读暴露河线网络 —— 供"离水多远"类群系规则（河流绿洲等）在<b>完整管线与群系
     * 快速路径</b>两条采样路径上取到同一份数据，避免规则只在预览生效。
     * <b>不改变任何水文生产逻辑。</b>
     */
    public RiverLineNetwork riverNetwork() {
        return hydrology.network();
    }

    public void setSeed(long seed) {
        hydrology.setSeed(seed);
        modelProvider.clear();          // ★ 换种子 ⇒ 旧 region 内容绝不复用（纯函数铁律）
    }

    public HydrologyChunkResult calculate(int chunkX, int chunkZ) {
        Cell[] cells = sampleOriginal(chunkX, chunkZ);
        double[] heights = heights(cells);
        List<HydrologyBlockCarvedColumn> columns;
        if (useModelPipeline) {
            // ★ model 链：区域缓存 + 中心线距离场（列语义见 ModelHydrologyProvider javadoc）。
            //   耗时记账由 provider 的 region 求解日志承载（缓存命中 ~µs，不单独记阶段）。
            columns = modelProvider.columnsFor(chunkX, chunkZ, cells);
        } else {
            columns = HydrologyBlockCarver.carveChunk(hydrology, chunkX, chunkZ,
                    horizontalScale, heights);
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
    }

    private Cell[] sampleOriginal(int chunkX, int chunkZ) {
        return HydrologyChunkSampling.sample(generator, horizontalScale, chunkX, chunkZ);
    }

    private static double[] heights(Cell[] cells) {
        double[] heights = new double[cells.length];
        for (int i = 0; i < cells.length; i++) heights[i] = cells[i].height;
        return heights;
    }
}
