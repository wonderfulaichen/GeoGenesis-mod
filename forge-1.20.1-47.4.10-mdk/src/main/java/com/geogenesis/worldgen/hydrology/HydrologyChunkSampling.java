package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

/** Shared chunk sampling helper used by production hydrology entry points. */
final class HydrologyChunkSampling {
    private HydrologyChunkSampling() {
    }

    static Cell[] sample(CellGenerator generator, double horizontalScale, int chunkX, int chunkZ) {
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                // ★ 管线顺序铁律（2026-09-08，用户："管线里河流在地形侵蚀前面"）：
                //   水文建网/雕刻基线必须用【无侵蚀】sample()——若用 sampleWu，河网构建期
                //   （含谷槽裕度、剖面锚定等数千次采样）会触发侵蚀 tile 冷生成，把整个
                //   region 范围的 tile 提前铺满（预览开窗即卡实锤）。侵蚀在【落块合成】
                //   时叠加：原始含侵蚀高度 − 同量雕刻深度（见 GeoGenesisGenerator
                //   .applyHydrologyChunk 的 delta 移位），床面-水面关系与无侵蚀基线严格一致。
                cells[lx * 16 + lz] = generator.sample(
                        (chunkX * 16 + lx) / horizontalScale,
                        (chunkZ * 16 + lz) / horizontalScale);
            }
        }
        return cells;
    }
}
