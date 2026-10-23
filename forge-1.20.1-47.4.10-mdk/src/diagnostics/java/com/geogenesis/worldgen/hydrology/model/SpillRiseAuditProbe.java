package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 洼地鞍部抬升量分布审计：判断 {@code MAX_SPILL_RISE=12} 是否过严。
 *
 * <p>对每个真实局部极小（DEAD_END 终点），用优先队列做无上限洪泛，
 * 找到"第一个存在严格更低外邻"的鞍部，记录：</p>
 * <ul>
 *   <li>抬升量（鞍部 bed − 洼底 bed）—— 与 12 比较；</li>
 *   <li>越过鞍部后能否继续下降（若能，说明是阈值误杀，不是真内流）。</li>
 * </ul>
 *
 * <p>同时对照 legacy {@code RiverLineNetwork.spillCell}：它<b>没有抬升上限</b>，
 * 只用"填洼面 ≤ spill + 0.05"判出口。</p>
 */
public final class SpillRiseAuditProbe {

    private static final int[][] D8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        var tp = TerrainParams.defaults();
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int bx = rx * 512 + 256, bz = rz * 512 + 256;
        int[] frame = provider.windowFrameForProbe(bx, bz);
        int n = frame[3];
        double[] bed = provider.pipeForProbe(bx, bz).bed();
        System.out.printf("owner=(%d,%d) n=%d budget=4096 MAX_SPILL_RISE=%.1f%n",
                rx, rz, n, 12.0);

        // 用 deterministic 顺序扫描全部"真实局部极小"格（8 邻全不更低）
        int pits = 0, within12 = 0, beyond12 = 0, noEscape = 0;
        double maxRise = 0;
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                boolean localMin = true;
                for (int[] d : D8) {
                    if (bed[(i + d[0]) * n + (j + d[1])] < bed[k] - 1e-9) {
                        localMin = false;
                        break;
                    }
                }
                if (!localMin) continue;
                pits++;
                double[] res = escapeRise(k, bed, n);
                if (res == null) {
                    noEscape++;
                } else {
                    double rise = res[0];
                    maxRise = Math.max(maxRise, rise);
                    if (rise <= 12.0) within12++; else beyond12++;
                    if (rows.length() < 3000 && rise > 6) {
                        rows.append(String.format("   pit=(%d,%d) bed=%.3f rise=%.3f -> %s%n",
                                frame[0] + i * frame[2], frame[1] + j * frame[2], bed[k], rise,
                                rise <= 12.0 ? "WITHIN_12" : "BEYOND_12"));
                    }
                }
            }
        }
        System.out.print(rows);
        System.out.printf("%n真实局部极小总数=%d%n", pits);
        System.out.printf("  鞍部抬升 <=12 = %d%n", within12);
        System.out.printf("  鞍部抬升  >12 = %d   (当前 MAX_SPILL_RISE 会判死)%n", beyond12);
        System.out.printf("  洪泛内无出口 = %d%n", noEscape);
        System.out.printf("  最大抬升 = %.3f%n", maxRise);
    }

    /**
     * 从洼底无上限洪泛（仅受窗口与 4096 预算约束），
     * 找第一个存在严格更低外邻的鞍部；返回 {抬升量, 外邻bed}。
     */
    private static double[] escapeRise(int start, double[] bed, int n) {
        record Node(int cell, double bed) { }
        var pq = new PriorityQueue<Node>((a, b) -> Double.compare(a.bed(), b.bed()));
        var seen = new HashSet<Integer>();
        pq.add(new Node(start, bed[start]));
        seen.add(start);
        int budget = 4096;
        while (!pq.isEmpty() && budget-- > 0) {
            Node cur = pq.remove();
            int ci = cur.cell() / n, cj = cur.cell() % n;
            for (int[] d : D8) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int c = a * n + b;
                if (bed[c] < bed[start] - 1e-9) {
                    // 存在严格比洼底低的外邻 ⇒ 从当前节点（可能是鞍部）可溢出
                    return new double[]{cur.bed() - bed[start], bed[c]};
                }
                if (seen.add(c)) pq.add(new Node(c, bed[c]));
            }
        }
        return null;
    }
}
