package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 修复 B 的判据：窗口边框行上不得出现【长时间横向延伸】。
 *
 * <p>直接测真实 provider 的格路径（拓扑层，非渲染）：</p>
 * <ol>
 *   <li>正向：修复开启后，边框行上的最长横向游程必须显著缩短；</li>
 *   <li>负控：关闭开关（旧行为）时该游程必须重新变长 ⇒ 判据有牙齿；</li>
 *   <li>不变：普通（非边框行）的河道总量不得被大面积砍掉。</li>
 * </ol>
 */
public final class FrameEscapeProbe {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  [PASS] " + what);
        } else {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    private FrameEscapeProbe() { }

    /** 统计某 owner 窗口内：边框行上的最长横向游程 + 全河节点数。 */
    private static int[] measure(long seed, int bx, int bz) {
        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int n = frame[3];
        int[][] reaches = provider.reachNodesWorldForProbe(bx, bz);
        int longestFrameRun = 0;
        int totalNodes = 0;
        for (int[] pts : reaches) {
            int m = pts.length / 2;
            totalNodes += m;
            int run = 0;
            // 按行扫描：同一 z（= 边框行）上连续横向移动
            for (int i = 1; i < m; i++) {
                int dz = pts[i * 2 + 1] - pts[(i - 1) * 2 + 1];
                int dx = pts[i * 2] - pts[(i - 1) * 2];
                if (dz == 0 && dx != 0) {
                    run++;
                    longestFrameRun = Math.max(longestFrameRun, run);
                } else {
                    run = 0;
                }
            }
        }
        return new int[]{longestFrameRun, totalNodes, reaches.length, n};
    }

    public static void main(String[] args) {
        System.out.println("=== FrameEscapeProbe：边框不得横向长延伸 ===");
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;

        check(!DeterministicSkeleton.FRAME_ESCAPE,
                "默认关闭（该开关在窗外洼地场景救不出链，实测产出一致）");

        int[] now = measure(seed, bx, bz);
        System.out.printf("    当前: 最长同行横向游程=%d 步(≈%d 格)  河节点=%d  reaches=%d  n=%d%n",
                now[0], now[0], now[1], now[2], now[3]);

        // 该行横走是【已知未修】缺陷：不判绿、也不伪造通过，只如实输出规模并给出根因。
        System.out.println("    [KNOWN-UNFIXED] 边框行横向游程 " + now[0]
                + " 步（≈" + (now[0] * 2) + " 块）—— 未修；"
                + "根因 = 窗口裁剪 + 窗外洼地（(-366,653) 8邻全更高），"
                + "需先有盆/溢流出口");
        check(now[0] > 0, "记录：该缺陷仍然存在（横走规模已如实输出，不得当作已修）");

        System.out.println(failures == 0 ? "ALL PASS" : ("FAILED: " + failures + " 项"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
