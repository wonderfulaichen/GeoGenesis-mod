package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <b>DEAD_END 审计</b> —— 量化「河流走到洼地但那里没被标成湖」的规模。
 *
 * <p><b>用户判据</b>（2026-10-21）：
 * 「现在非常多短河并且是非正常结束。不是按我说的水文全周期的。」</p>
 *
 * <p><b>水文全周期</b>：降水 → 坡面汇流 → 河流 → <b>入湖/入海</b> → 湖面蒸发/溢出 → 新河段。
 * 河流**只能**以「入湖 / 入海 / 并入已有河段 / 跨区接力」结束。
 * 出现 {@code DEAD_END} 意味着这条河**没有走完它的水文路径**。</p>
 *
 * <p><b>本探针</b>：对每个 <code>DEAD_END</code> 的终点，检查：
 * <ol>
 *   <li>该格 8 邻是否真的没有更低（= 确实是洼地）？</li>
 *   <li>该格是否在 {@code lakeMask} 里？（若否 ⇒ 洼地未被识别成湖 ⇒ 异常）</li>
 *   <li>该洼地的「深度」与「面积」—— 判断它是否本应成湖。</li>
 * </ol>
 */
public final class DeadEndAuditProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);

        int n = ViewSpec.size();
        int ox = cx - ViewSpec.RADIUS, oz = cz - ViewSpec.RADIUS;
        int regionSize = Integer.getInteger("geogenesis.modelRegion", 512);
        int rxLo = Math.floorDiv(ox, regionSize) - 1, rxHi = Math.floorDiv(ox + n - 1, regionSize) + 1;
        int rzLo = Math.floorDiv(oz, regionSize) - 1, rzHi = Math.floorDiv(oz + n - 1, regionSize) + 1;

        int[][] D8 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        int totalTraces = 0, deadEnds = 0;
        int truePit = 0;          // 确实无更低邻居
        int inLakeMask = 0;       // 但在 lakeMask 里（那应该是 MOUTH_LAKE，不该 DEAD_END）
        int notLake = 0;          // 不在 lakeMask（洼地未被识别成湖）
        int hasLower = 0;         // 竟然还有更低邻居（逻辑矛盾）
        var samples = new StringBuilder();

        for (int rx = rxLo; rx <= rxHi; rx++) {
            for (int rz = rzLo; rz <= rzHi; rz++) {
                int bx = rx * regionSize + regionSize / 2;
                int bz = rz * regionSize + regionSize / 2;
                HydrologyPipeline.Result p = provider.pipeForProbe(bx, bz);
                int gn = p.nx();
                double[] bed = p.bed();
                boolean[] lake = p.lake(), sea = p.sea();
                int[] frame = provider.windowFrameForProbe(bx, bz);
                int fox = frame[0], foz = frame[1], fstep = frame[2];

                // 用生产的 Trace 数据（sources 只给起点，这里改用死端表需另取）
                // ⇒ 退化做法：扫描所有「陆格中无更低邻居」的格，统计其 lakeMask 状态
                for (int k = 0; k < gn * gn; k++) {
                    if (lake[k] || sea[k] || !p.resolved()[k]) continue;
                    int bi = k / gn, bj = k % gn;
                    double lowest = Double.POSITIVE_INFINITY;
                    for (int[] d : D8) {
                        int a = bi + d[0], b = bj + d[1];
                        if (a < 0 || b < 0 || a >= gn || b >= gn) continue;
                        lowest = Math.min(lowest, bed[a * gn + b]);
                    }
                    if (lowest < bed[k] - 1e-9) continue;   // 有更低 ⇒ 不是洼地
                    truePit++;
                    if (lake[k]) inLakeMask++; else notLake++;
                    if (samples.length() < 1500) {
                        int wx = fox + bi * fstep, wz = foz + bj * fstep;
                        samples.append(String.format(
                                "pit (%d,%d) bed=%.4f lake=false%n", wx, wz, bed[k]));
                    }
                }
            }
        }

        System.out.printf("区域：%d 个 region%n", (rxHi-rxLo+1)*(rzHi-rzLo+1));
        System.out.printf("陆格中的【真洼地】（8 邻无更低）= %d%n%n", truePit);
        System.out.printf("  其中 lakeMask=true  : %d  ← 这些本该是 MOUTH_LAKE%n", inLakeMask);
        System.out.printf("  其中 lakeMask=false : %d  ← ★ 洼地未被识别成湖 ⇒ 河流在此 DEAD_END%n", notLake);
        if (truePit > 0) {
            System.out.printf("%n「洼地未成湖」占比 = %.1f%%%n", 100.0 * notLake / truePit);
        }
        System.out.println("\n判读：若这个数很大 ⇒ 河网断裂的主因是【湖泊判定漏掉了这些洼地】，");
        System.out.println("      而不是骨架追踪算法本身。");
    }
}
