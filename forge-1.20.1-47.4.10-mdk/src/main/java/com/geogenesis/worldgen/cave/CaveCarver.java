package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.terrain.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 洞穴雕刻的 <b>MC 适配器</b>（★ 2026-09-15 新增；此前 {@code applyCarvers} 一直是空实现）。
 *
 * <h3>职责边界</h3>
 * <p>本类<b>只做"把方块挖成空气"</b>这一件 MC 相关的事；<b>洞穴几何</b>
 * （某列该不该挖、上下边界在哪、岩性如何调制）全部在 {@link CaveShape} ——
 * 那是<b>零 MC 依赖的纯函数</b>，因此可在无 MC 的诊断源码集中直接验证。</p>
 *
 * <h3>为何自研而非用原版 WorldCarver</h3>
 * <p>本项目是自定义 {@code ChunkGenerator}，<b>没有</b> {@code NoiseSettings} /
 * {@code NoiseChunk}；而原版 {@code cave/canyon} carver 依赖 {@code NoiseChunk}，
 * 且 {@code ChunkGenerator.applyCarvers} 基类是<b>空实现</b>
 * （真正实现在 {@code NoiseBasedChunkGenerator}）⇒ {@code super.applyCarvers} 无效。
 * 故采用自研的"2D 场驱动柱体切挖"（见 {@link CaveShape} 的机制说明）。</p>
 *
 * <h3>安全边界（刻意保守）</h3>
 * <ul>
 *   <li><b>不破地表</b>：洞顶钳到 {@code surface − CaveShape.SURFACE_LID}；</li>
 *   <li><b>不挖海底</b>：地表低于海平面的列整体跳过 —— 本项目无 aquifer，
 *       挖海底会留下干空腔（原版靠 aquifer 灌水）；</li>
 *   <li><b>不动流体</b>：遇水/熔岩方块跳过（与 TF {@code NoiseCaveCarver} 一致）。</li>
 * </ul>
 *
 * <h3>性能</h3>
 * <p>每 chunk ≈ 256 列 × 2 族 × 3 次 2D 噪声 ≈ 1500 次求值；方块写入只发生在命中列。
 * 岩性/地表高度取自 {@code terrain.getChunkCells()} 的 <b>LRU 命中</b>
 * （同 chunk 刚在 {@code fillFromNoise} 生成过）⇒ <b>零额外地形采样</b>，
 * 不会触发侵蚀 tile 冷生成（项目性能红线）。</p>
 */
public final class CaveCarver {

    private CaveCarver() { }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * 雕刻整个 chunk 的洞穴。
     *
     * @param chunk     目标 chunk（已由 {@code fillFromNoise} 填好地形）
     * @param cells     本 chunk 的 {@link Cell}（来自 {@code terrain.getChunkCells}，LRU 命中）
     * @param worldMinY 世界最低 Y
     * @param seaLevel  海平面 Y（地表低于此的列跳过，见类注释）
     */
    public static void carve(ChunkAccess chunk, Cell[] cells, int worldMinY, int seaLevel) {
        if (!CaveShape.isSeeded()) return;
        if (cells == null || cells.length != 256) return;

        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                if (cell == null) continue;

                int wx = baseX + lx;
                int wz = baseZ + lz;

                // 地表高取自 Cell —— 不读 chunk 高度图，绕开 TF 警告的
                // "heightmap 未 prime（OCEAN_FLOOR_WG 在 applyCarvers 阶段未必已算）" 陷阱。
                int surface = (int) Math.floor(cell.height);
                // 海底列跳过（无 aquifer ⇒ 会留下干空腔）
                if (surface < seaLevel) continue;

                double litho = CaveShape.lithoFactor(cell.rockTypeId);

                for (int fam = 0; fam < CaveShape.FAMILY_COUNT; fam++) {
                    long span = CaveShape.span(fam, wx, wz, surface, worldMinY, litho);
                    if (span == CaveShape.NO_SPAN) continue;
                    carveSpan(chunk, pos, wx, wz,
                            CaveShape.spanBottom(span), CaveShape.spanTop(span));
                }
            }
        }
    }

    /** 把 [bottom, top] 整柱的非流体方块挖成空气。 */
    private static void carveSpan(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                  int wx, int wz, int bottom, int top) {
        for (int y = bottom; y <= top; y++) {
            pos.set(wx, y, wz);
            BlockState st = chunk.getBlockState(pos);
            if (st.isAir()) continue;
            // 不动流体（水/熔岩）—— 与 TF NoiseCaveCarver 一致
            if (!st.getFluidState().isEmpty()) continue;
            chunk.setBlockState(pos, AIR, false);
        }
    }
}
