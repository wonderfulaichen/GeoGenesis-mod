package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>河道【断口性质】分析 —— 剩下的 327 个真实断裂长什么样</h1>
 *
 * <h2>背景（度量修正后的新起点）</h2>
 * <p>把连通性度量从 4 连通改为 8 连通后（commit 365e284），"点状假河"由 105 → 6。
 * 但仍有 <b>327 个真实分量</b>（不可用度量解释）⇒ 本探针查它们的性质。</p>
 *
 * <h2>三个必须区分的断口成因（修法完全不同）</h2>
 * <table border="1">
 *   <tr><th>类型</th><th>判据</th><th>修法方向</th></tr>
 *   <tr><td><b>A 门槛抖动</b></td>
 *       <td>断口格的 flux/smooth 落在门槛附近（如 0.3~1.6 ×门槛）</td>
 *       <td>调成河判定（传播/平滑/门槛），局部</td></tr>
 *   <tr><td><b>B 无墨水</b></td>
 *       <td>断口格 flux ≈ 0（没有流线经过）</td>
 *       <td>流线为何不走这里 —— 速度场/终止条件</td></tr>
 *   <tr><td><b>C 结构断裂</b></td>
 *       <td>断口格连 resolved / 通道都不存在</td>
 *       <td>上游数据问题，非成河判定</td></tr>
 * </table>
 *
 * <h2>度量</h2>
 * <p>对每个分量，找【最近的其他分量】，量两者之间的最短 gap（格数），
 * 并对 gap 上的格统计 flux/smooth。这样能直接看出：
 * 断口是"差一点点"（A）还是"完全没水"（B）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class ChannelGapProbe {

    private ChannelGapProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 150_000;
        double maxGap = args.length > 5 ? Double.parseDouble(args[5]) : 12.0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] ch = r.channel();
        double threshold = 1.0;   // Params.forRunoff 的 channelThreshold

        // 8 连通分量
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        int[] stack = new int[cells];
        int[][] D8 = {{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !ch[k0]) continue;
            int sp = 0; stack[sp++] = k0; comp[k0] = comps;
            while (sp > 0) {
                int k = stack[--sp];
                int i = k / n, j = k % n;
                for (int[] d : D8) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int nk = a * n + b;
                    if (comp[nk] < 0 && ch[nk]) { comp[nk] = comps; stack[sp++] = nk; }
                }
            }
            comps++;
        }

        // 分量成员列表（用于找最近邻分量）
        java.util.List<java.util.List<Integer>> members = new java.util.ArrayList<>();
        for (int c = 0; c < comps; c++) members.add(new java.util.ArrayList<>());
        for (int k = 0; k < cells; k++) if (comp[k] >= 0) members.get(comp[k]).add(k);

        // ★ 对每个分量：找最近的其他分量，量 gap，统计 gap 上的 flux
        int considered = 0, gapNear = 0, gapNoInk = 0, gapFar = 0;
        java.util.List<String> samplesOut = new java.util.ArrayList<>();
        for (int c = 0; c < comps; c++) {
            // 只对小分量分析（大分量之间多已连通）
            if (members.get(c).size() > 50) continue;
            considered++;
            // 对该分量的每个成员，向外 BFS 找最近的【其他分量】格
            int bestDist = Integer.MAX_VALUE, bestOther = -1, bestSelf = -1;
            for (int k : members.get(c)) {
                int i = k / n, j = k % n;
                for (int rad = 1; rad <= (int) maxGap && rad < bestDist; rad++) {
                    boolean found = false;
                    for (int a = i - rad; a <= i + rad && !found; a++) {
                        for (int b = j - rad; b <= j + rad && !found; b++) {
                            if (Math.max(Math.abs(a - i), Math.abs(b - j)) != rad) continue;
                            if (a < 0 || b < 0 || a >= n || b >= n) continue;
                            int nk = a * n + b;
                            if (comp[nk] >= 0 && comp[nk] != c) {
                                bestDist = rad; bestOther = comp[nk]; bestSelf = k; found = true;
                            }
                        }
                    }
                    if (found) break;
                }
            }
            if (bestOther < 0) { gapFar++; continue; }
            // gap 上的格（两分量之间那条直线上的中点区域）
            int si = bestSelf / n, sj = bestSelf % n;
            int oi = members.get(bestOther).get(0) / n, oj = members.get(bestOther).get(0) % n;
            // 取 self→other 方向上、距离 bestDist 内是否有墨水
            double maxFluxInGap = 0;
            int steps = Math.max(1, bestDist);
            for (int t = 1; t < steps; t++) {
                int gi = si + (oi - si) * t / steps;
                int gj = sj + (oj - sj) * t / steps;
                if (gi < 0 || gj < 0 || gi >= n || gj >= n) continue;
                int gk = gi * n + gj;
                maxFluxInGap = Math.max(maxFluxInGap, r.discharge()[gk]);
            }
            if (maxFluxInGap >= 0.3 * threshold && maxFluxInGap < threshold) gapNear++;
            else if (maxFluxInGap < 0.3 * threshold) gapNoInk++;
            else gapNear++;   // ≥门槛却没成河（异常，归入近门槛类观察）
            if (samplesOut.size() < 8) {
                samplesOut.add(String.format("   分量#%d(尺寸%d) → 最近#%d gap=%d格 gap内maxFlux=%.3f %s",
                        c, members.get(c).size(), bestOther, bestDist, maxFluxInGap,
                        maxFluxInGap < 0.3 * threshold ? "【无墨水】"
                                : (maxFluxInGap < threshold ? "【近门槛】" : "【≥门槛却没成河】")));
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 河道断口性质分析（8 连通口径）===\n");
        log.append(String.format("seed=%d r=%d 门槛=%.1f%n", seed, radius, threshold));
        log.append(String.format("河道格=%d  分量总数=%d  其中小分量(<=50格)被分析=%d%n%n",
                countTrue(ch), comps, considered));
        log.append(String.format("断口成因分类（仅小分量）：%n"));
        log.append(String.format("  A 近门槛（0.3~1.0×门槛有墨水，差一点就成河） = %d (%.1f%%)%n",
                gapNear, 100.0 * gapNear / Math.max(1, considered)));
        log.append(String.format("  B 无墨水（<0.3×门槛，流线根本没走这里）   = %d (%.1f%%)%n",
                gapNoInk, 100.0 * gapNoInk / Math.max(1, considered)));
        log.append(String.format("  C 超出 %.0f 格仍找不到邻居（孤立/遥远）      = %d (%.1f%%)%n",
                maxGap, gapFar, 100.0 * gapFar / Math.max(1, considered)));
        log.append(String.format("%n样例：%n"));
        for (String s : samplesOut) log.append(s).append('\n');
        log.append(String.format("%n判读：%s%n", gapNear > gapNoInk
                ? "A（近门槛）为主 ⇒ 调成河判定（传播/门槛）即可，属局部问题"
                : "B（无墨水）为主 ⇒ 流线不走这里，须查速度场/终止条件（结构层）"));

        java.nio.file.Path dir = java.nio.file.Path.of("build/gap");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("channel_gap.txt"), log);
        System.out.println(log);
    }

    private static int countTrue(boolean[] a) { int c = 0; for (boolean b : a) if (b) c++; return c; }
}