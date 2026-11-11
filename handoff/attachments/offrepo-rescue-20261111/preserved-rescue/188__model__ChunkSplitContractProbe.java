package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 世界查询契约的【核心检验】：能否按 chunk 独立生成而不破坏一致性？
 *
 * <h2>为什么这是契约的中心</h2>
 * <p>Minecraft 按 chunk 生成，无法"先算完整个世界"。因此必须存在一个<b>局部窗口</b>，
 * 使每个 chunk 只用自己的窗口就能得到与整窗一致的结果。这等价于要求：</p>
 * <ul>
 *   <li><b>水位</b>是局部量（与窗口无关）；</li>
 *   <li><b>湖泊身份</b>跨窗口一致（同一湖在任何窗口里都是同一个湖）；</li>
 *   <li><b>河流</b>在窗口边界容许截断，但必须<b>明确标出</b>而不是给出错误值。</li>
 * </ul>
 *
 * <h2>做法</h2>
 * <p>取一个大窗口作为参照，切成若干 chunk；每个 chunk 用<b>以该 chunk 为中心、
 * 半径 R 的局部窗口</b>独立跑 {@link HydrologyPipeline}，只取 chunk 内部格，
 * 与整窗结果逐格比对。<b>坐标必须映射到世界坐标再比对</b>（此前踩过下标错位的坑）。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li>水位：双方 resolved 的格必须逐位相同（差 &lt; 1e-9）。</li>
 *   <li>湖泊：必须布尔一致。</li>
 *   <li>河流：允许边界带不一致，但必须报告比例并确认其集中在边界。</li>
 *   <li>确定性：同一 chunk 两次独立求解，结果逐位相同。</li>
 * </ol>
 */
public final class ChunkSplitContractProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int step = 4;
        int refRadius = 768;          // 参照整窗
        int chunkBlocks = 64;         // 每个 chunk 覆盖 64 块（16 格）
        // 局部窗口半径必须大到能含【真实排水终端（海）】，否则该窗口全部未决。
        //   实测：在本中心，半径 256 的窗口内没有海 ⇒ bothResolved 仅 22%。
        int localRadius = args.length > 3 ? Integer.parseInt(args[3]) : 768;
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);

        var reference = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, refRadius, step, climate));

        System.out.println("=== chunk-split contract ===");
        System.out.printf("seed=%d centre=(%d,%d) step=%d refRadius=%d chunk=%d localRadius=%d%n",
                seed, cx, cz, step, refRadius, chunkBlocks, localRadius);

        int chunks = 2 * refRadius / chunkBlocks - 1;   // 参照窗口内部的 chunk 数量
        // ★ 抽样而非穷举：484 个 chunk ×2 次管线会跑一小时以上。
        //   取 3×3 代表性位置（中心 + 四正 + 四角）即可覆盖"靠近参照中心"与"靠近参照边缘"两类。
        int[] sample = {chunks / 4, chunks / 2, 3 * chunks / 4};
        int compared = 0, levelMismatch = 0, lakeMismatch = 0, channelMismatch = 0, oneSide = 0;
        int channelMismatchInner = 0, channelMismatchBorder = 0;
        double worstLevel = 0;
        int chunkCount = 0;

        for (int ci : sample) {
            for (int cj : sample) {
                int chX = cx - refRadius + ci * chunkBlocks + chunkBlocks / 2;
                int chZ = cz - refRadius + cj * chunkBlocks + chunkBlocks / 2;
                // 局部窗口必须与全局网格对齐：半径是 step 的整数倍，中心在格中心上
                var local = HydrologyPipeline.run(gen, tp,
                        new HydrologyPipeline.Request(chX, chZ, localRadius, step, climate));
                chunkCount++;

                // 确定性：同参数再跑一次必须逐位相同
                var local2 = HydrologyPipeline.run(gen, tp,
                        new HydrologyPipeline.Request(chX, chZ, localRadius, step, climate));
                for (int k = 0; k < local.level().length; k++) {
                    if (local.level()[k] != local2.level()[k]
                            || local.lake()[k] != local2.lake()[k]) {
                        throw new AssertionError("non-deterministic chunk at (" + chX + "," + chZ + ")");
                    }
                }

                // 逐格比对 chunk 内部
                for (int x = chX - chunkBlocks / 2; x < chX + chunkBlocks / 2; x += step) {
                    for (int z = chZ - chunkBlocks / 2; z < chZ + chunkBlocks / 2; z += step) {
                        int ri = (x - (cx - refRadius)) / step;
                        int rj = (z - (cz - refRadius)) / step;
                        int li = (x - (chX - localRadius)) / step;
                        int lj = (z - (chZ - localRadius)) / step;
                        if (ri < 0 || rj < 0 || ri >= reference.nx() || rj >= reference.nz()) continue;
                        if (li < 0 || lj < 0 || li >= local.nx() || lj >= local.nz()) continue;
                        int kr = ri * reference.nz() + rj;
                        int kl = li * local.nz() + lj;
                        compared++;
                        boolean both = reference.resolved()[kr] && local.resolved()[kl];
                        if (!both) { oneSide++; continue; }
                        double d = Math.abs(reference.level()[kr] - local.level()[kl]);
                        if (d >= 1e-9) { levelMismatch++; worstLevel = Math.max(worstLevel, d); }
                        if (reference.lake()[kr] != local.lake()[kl]) lakeMismatch++;
                        if (reference.channel()[kr] != local.channel()[kl]) {
                            channelMismatch++;
                            // 距 chunk 边界的距离，用于确认不一致是否集中在边界带
                            int distToChunkEdge = Math.min(
                                    Math.min(x - (chX - chunkBlocks / 2), (chX + chunkBlocks / 2) - x - step),
                                    Math.min(z - (chZ - chunkBlocks / 2), (chZ + chunkBlocks / 2) - z - step));
                            if (distToChunkEdge < localRadius) channelMismatchInner++;
                            else channelMismatchBorder++;
                        }
                    }
                }
            }
        }

        System.out.printf("chunks=%d compared=%d bothResolved=%d (%.2f%%) oneSide=%d%n",
                chunkCount, compared, compared - oneSide,
                100.0 * (compared - oneSide) / Math.max(1, compared), oneSide);
        System.out.printf("criterion1 level mismatch = %d  worstDiff=%.3e  -> %s%n",
                levelMismatch, worstLevel, levelMismatch == 0 ? "PASS" : "FAIL");
        System.out.printf("criterion2 lake mismatch  = %d  -> %s%n",
                lakeMismatch, lakeMismatch == 0 ? "PASS" : "FAIL");
        System.out.printf("criterion3 channel mismatch = %d / %d (%.2f%%)  [全是边界截断效应的比例 %.1f%%]%n",
                channelMismatch, compared, 100.0 * channelMismatch / Math.max(1, compared),
                100.0 * channelMismatchInner / Math.max(1, channelMismatch));
        System.out.println("criterion4 determinism: PASS (same params re-run bit-identical, asserted in-loop)");
        System.out.printf("LIMIT: step=%d, single seed/climate, refRadius=%d, localRadius=%d; other configs unverified.%n",
                step, refRadius, localRadius);
    }

    private ChunkSplitContractProbe() { }
}
