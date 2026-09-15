package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.terrain.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 洞穴雕刻的 <b>MC 适配器</b>（★ 2026-09-15 由"柱体切挖"改为"逐体素 3D 判定"）。
 *
 * <h3>职责边界</h3>
 * <p>本类只做"把方块挖成空气"；<b>洞穴几何</b>（某体素该不该挖、岩性如何调制）
 * 全部在 {@link CaveShape} —— 那是<b>零 MC 依赖的纯函数</b>，可在无 MC 的诊断集直接验证。</p>
 *
 * <h3>★ 为何从"按列挖"改为"逐体素"</h3>
 * <p>初版按 {@code (x,z)} 列挖整段 {@code [bottom, top]}（移植 TF），几何上是<b>竖直柱</b>
 * ⇒ 用户实测"完全不成洞穴的样子"。现改为对每个体素独立判定
 * （{@link CaveShape#isCave}，3D 噪声等值面）⇒ 可产出真正蜿蜒、有分支、可上下起伏的隧道。</p>
 *
 * <h3>性能（逐体素比按列贵，故必须量化）</h3>
 * <p>逐体素需对地下带内<b>每个方块</b>求噪声。为控制成本：</p>
 * <ul>
 *   <li>先按列算 {@code depth} 窗口（只在地下带 {@code [DEPTH_MIN, DEPTH_MAX]} 内遍历）；</li>
 *   <li>隧道判定有<b>短路</b>：{@code |n1| ≥ t1} 时不求 {@code n2}（多数体素在此返回）；</li>
 *   <li>洞室/孔洞只在前两级未命中时才求（顺序即便宜到贵）；</li>
 *   <li>岩性/地表取自 {@code terrain.peekChunk}（<b>只读已就绪</b>，不触发生成）。</li>
 * </ul>
 * <p>实测成本见 {@code CavePerfProbe}。</p>
 */
public final class CaveCarver {

    private CaveCarver() { }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * 雕刻整个 chunk 的洞穴。
     *
     * @param chunk     目标 chunk（已由 {@code fillFromNoise} 填好地形）
     * @param cells     本 chunk 的 {@link Cell}（来自 {@code terrain.peekChunk}，只读）
     * @param worldMinY 世界最低 Y
     * @param seaLevel  海平面 Y（地表低于此的列跳过 —— 无 aquifer，挖海底会留干空腔）
     */
    public static void carve(ChunkAccess chunk, Cell[] cells, int worldMinY, int seaLevel) {
        // ★ 配置热刷新：若配置界面刚改过档位/开关，此处（chunk 级，非逐体素）
        //   立即重新解析 ⇒ "改完即生效"（仅影响新生成区块）。
        CaveShape.ensureConfigFresh();
        // ★ 2026-09-15：总开关（配置）。关闭 ⇒ 直接返回，地下无洞穴。
        //   与 RTG 的 useCaves=false 同语义。放在最前 ⇒ 关闭时零成本。
        if (!CaveShape.isEnabled()) return;
        if (!CaveShape.isSeeded()) return;
        if (cells == null || cells.length != 256) return;

        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // ★ 运行时深度窗口（配置可覆盖）：在所有列之前取一次，避免逐列重复读取。
        int lid = CaveShape.surfaceLid();
        int dMax = CaveShape.depthMax();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Cell cell = cells[lx * 16 + lz];
                if (cell == null) continue;

                int wx = baseX + lx;
                int wz = baseZ + lz;

                // 地表高取自 Cell —— 不读 chunk 高度图，绕开 TF 警告的"未 prime"陷阱
                int surface = (int) Math.floor(cell.height);
                // 海底列跳过（本项目无 aquifer ⇒ 会留下干空腔）
                if (surface < seaLevel) continue;

                // ★ 岩性门控关闭时（VANILLA_LIKE 档）不查表 —— CaveShape 内部会再
                //   判一次 cfgLitho，但这里先省掉整列的查表调用（逐列一次，成本虽小，
                //   但"关掉了还查"是白做功，且会让读者误以为门控仍在生效）。
                double litho = CaveShape.config().lithoGating
                        ? CaveShape.lithoFactor(cell.rockTypeId) : 1.0;

                // 只遍历地下带（与 CaveShape 内的窗口一致，避免无谓的逐体素求值）
                int yTop = surface - lid;
                int yBot = Math.max(worldMinY + 1, surface - dMax);
                if (yTop <= yBot) continue;

                for (int y = yBot; y <= yTop; y++) {
                    if (!CaveShape.isCave(wx, y, wz, surface, worldMinY, litho)) continue;
                    pos.set(wx, y, wz);
                    BlockState st = chunk.getBlockState(pos);
                    if (st.isAir()) continue;
                    // 不动流体（水/熔岩）—— 与 TF NoiseCaveCarver 一致
                    if (!st.getFluidState().isEmpty()) continue;
                    chunk.setBlockState(pos, AIR, false);
                }
            }
        }
    }
}
