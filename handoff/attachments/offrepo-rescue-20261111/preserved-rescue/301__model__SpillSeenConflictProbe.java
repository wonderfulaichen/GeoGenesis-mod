package com.geogenesis.worldgen.hydrology.model;

/**
 * 核实 {@code spillCell} 的 {@code !seen.contains(nxt)} 是否把真实出口排除掉。
 *
 * <p>对比两种判据（同一洪泛顺序）：</p>
 * <ul>
 *   <li>现状：{@code nxt >= 0 && !visited.contains(nxt) && !seen.contains(nxt)}；</li>
 *   <li>对照：{@code nxt >= 0 && !visited.contains(nxt)}（不排除 seen）。</li>
 * </ul>
 */
public final class SpillSeenConflictProbe {

    private static final int[][] D8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static void main(String[] args) {
        var tp = com.geogenesis.worldgen.terrain.TerrainParams.defaults();
        var gen = new com.geogenesis.worldgen.terrain.CellGenerator(tp, tp.minY(), tp.maxY());
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        gen.seed(seed);
        var provider = new ModelHydrologyProvider(gen, seed);
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -256;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        int n = provider.windowFrameForProbe(bx, bz)[3];
        double[] bed = provider.pipeForProbe(bx, bz).bed();

        int pits = 0, curOk = 0, looseOk = 0, curFailLooseOk = 0;
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                boolean min = true;
                for (int[] d : D8) {
                    if (bed[(i + d[0]) * n + (j + d[1])] < bed[k] - 1e-9) { min = false; break; }
                }
                if (!min) continue;
                pits++;
                int a = spill(k, bed, n, true);
                int b = spill(k, bed, n, false);
                if (a >= 0) curOk++;
                if (b >= 0) looseOk++;
                if (a < 0 && b >= 0) {
                    curFailLooseOk++;
                    if (rows.length() < 2000) {
                        rows.append(String.format("   pit=(%d,%d) bed=%.3f 现状=FAIL 放宽=OK%n",
                                i, j, bed[k]));
                    }
                }
            }
        }
        System.out.print(rows);
        System.out.printf("真实局部极小=%d%n", pits);
        System.out.printf("  现状判据找到出口 = %d (%.1f%%)%n", curOk, 100.0 * curOk / pits);
        System.out.printf("  不排除seen  找到出口 = %d (%.1f%%)%n", looseOk, 100.0 * looseOk / pits);
        System.out.printf("  仅因 seen 判据漏掉 = %d%n", curFailLooseOk);
    }

    private static int spill(int start, double[] bed, int n, boolean excludeSeen) {
        record Node(int cell, double bed) { }
        var pq = new java.util.PriorityQueue<Node>((x, y) -> Double.compare(x.bed(), y.bed()));
        var seen = new java.util.HashSet<Integer>();
        pq.add(new Node(start, bed[start]));
        seen.add(start);
        int budget = 4096;
        while (!pq.isEmpty() && budget-- > 0) {
            Node cur = pq.remove();
            if (cur.cell() != start) {
                int nxt = strictD8(cur.cell(), bed, n);
                boolean ok = nxt >= 0 && (excludeSeen ? !seen.contains(nxt) : true);
                if (ok) return cur.cell();
            }
            int ci = cur.cell() / n, cj = cur.cell() % n;
            for (int[] d : D8) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int c = a * n + b;
                if (seen.add(c)) pq.add(new Node(c, bed[c]));
            }
        }
        return -1;
    }

    private static int strictD8(int k, double[] bed, int n) {
        int bi = k / n, bj = k % n;
        int best = -1;
        double bestBed = bed[k];
        for (int[] d : D8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= n || b >= n) continue;
            int c = a * n + b;
            if (bed[c] < bestBed - 1e-9) { bestBed = bed[c]; best = c; }
        }
        return best;
    }
}
