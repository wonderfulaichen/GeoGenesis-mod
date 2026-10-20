package com.geogenesis.worldgen.hydrology;

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
    private final ModelHydrologyProvider modelProvider;

    public HydrologyChunkEngine(CellGenerator generator, long seed) {
        this.generator = generator;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        this.modelProvider = new ModelHydrologyProvider(generator, seed);
        LOGGER.info("[HYDRO] model pipeline is the ONLY line (legacy removed 2026-10-20)");
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
        modelProvider.setSeed(seed);
    }

    public HydrologyChunkResult calculate(int chunkX, int chunkZ) {
        Cell[] cells = sampleOriginal(chunkX, chunkZ);
        List<HydrologyBlockCarvedColumn> columns = modelProvider.columnsFor(chunkX, chunkZ, cells);
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
}
