package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>溢口下潜【路径逐步 dump】—— 取代"猜机制"</h1>
 *
 * <p>连续两次修复（出口弃置 54→0、最低鞍口）**图上均无效**，
 * 说明对机制的推断有误。本探针把 {@code outletExit} 的每一步都打出来：
 * 每格的 lake/level/bed/resolved，以及该格的速度方向指向哪。</p>
 *
 * <p>目的：看清"水出溢口后又回湖"到底发生在哪一步、为什么。
 * <b>不猜，看数据。</b></p>
 */
public final class SpillPathDumpProbe {

    private SpillPathDumpProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 100_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] lake = r.lake(), sea = r.sea(), resolved = r.resolved();
        int[] parent = r.parent();
        double[] bed = r.bed(), level = r.level();
        var lr = r.lakeResult();

        System.out.println("=== 溢口下潜路径 dump ===");
        System.out.printf("seed=%d r=%d n=%d  溢口数=%d%n%n", seed, radius, n, lr.overflowAt().size());

        int shown = 0;
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            if (shown++ >= 5) break;
            System.out.printf("── 溢口键(%d,%d): lake=%b parent=%d level=%.2f bed=%.2f depth=%.2f%n",
                    o / n, o % n, lake[o], parent[o], level[o], bed[o], r.depth()[o]);
            // 逐步走 outletExit，打印每步
            int cur = o;
            for (int stepN = 0; stepN < 12; stepN++) {
                int i = cur / n, j = cur % n;
                System.out.printf("   [%2d] (%4d,%4d) lake=%-5b resolved=%-5b level=%7.2f bed=%7.2f",
                        stepN, i, j, lake[cur], resolved[cur], level[cur], bed[cur]);
                // 该格速度方向（与生产同口径）
                if (i >= 1 && j >= 1 && i < n - 1 && j < n - 1) {
                    double sC = resolved[cur] ? level[cur] : bed[cur];
                    int km = (i - 1) * n + j, kp = (i + 1) * n + j;
                    int jm = i * n + (j - 1), jp = i * n + (j + 1);
                    double sW = resolved[km] ? level[km] : bed[km];
                    double sE = resolved[kp] ? level[kp] : bed[kp];
                    double sN = resolved[jm] ? level[jm] : bed[jm];
                    double sS = resolved[jp] ? level[jp] : bed[jp];
                    double gx = (sE - sW) / 2.0, gz = (sS - sN) / 2.0;
                    double g = Math.hypot(gx, gz);
                    if (g < 1e-12) System.out.printf("  速度=静水");
                    else {
                        double vx = -gx / g, vz = -gz / g;
                        int ti = i + (vx > 0.4 ? 1 : vx < -0.4 ? -1 : 0);
                        int tj = j + (vz > 0.4 ? 1 : vz < -0.4 ? -1 : 0);
                        boolean tgtLake = (ti >= 0 && tj >= 0 && ti < n && tj < n) && lake[ti * n + tj];
                        System.out.printf("  速度→(%+d,%+d) 该邻格湖=%b", ti - i, tj - j, tgtLake);
                    }
                }
                System.out.println();

                if (sea[cur]) { System.out.println("   → 入海，终止"); break; }
                if (!lake[cur]) { System.out.println("   → 已出湖域（这就是注入起点）"); break; }
                int nxt = parent[cur];
                if (nxt >= 0) {
                    System.out.printf("   → 沿 parent 走 → (%d,%d)%n", nxt / n, nxt % n);
                    cur = nxt;
                    continue;
                }
                // parent 断：用 descendByLevel 找最低床面非湖邻格
                int ci = cur / n, cj = cur % n;
                double bestExitH = Double.POSITIVE_INFINITY;
                int bestExit = -1;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                    int a = ci + d[0], b = cj + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int k = a * n + b;
                    if (!lake[k] && bed[k] < bestExitH) { bestExitH = bed[k]; bestExit = k; }
                }
                if (bestExit < 0) {
                    System.out.println("   → parent 断且无非湖邻格 ⇒ 失败（真封闭）");
                    break;
                }
                System.out.printf("   → parent 断 ⇒ 跳到最低床面非湖邻格 (%d,%d) bed=%.2f%n",
                        bestExit / n, bestExit % n, bed[bestExit]);
                cur = bestExit;
            }
            System.out.println();
        }

        // ★★ 关键对照：溢口格的 8 邻域中，level 最低者 vs bed 最低者 是否同一格 ★★
        //   假设：minimax 已把"溢出口"编码在 level 场里 ⇒ 应该按 level 找出口，
        //         而不是按 bed（我上一次修的就是按 bed 找 ⇒ 可能找到湖岸干地）。
        System.out.println("=== 对照：按 level 找出口 vs 按 bed 找出口 ===");
        int same = 0, differ = 0, lvIsLake = 0, bedIsLake = 0, lvNone = 0;
        int shown2 = 0;
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            if (!lake[o]) continue;                    // 只对湖内键检查
            int ci = o / n, cj = o % n;
            double lvMin = Double.POSITIVE_INFINITY, bedMin = Double.POSITIVE_INFINITY;
            int lvPick = -1, bedPick = -1;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int k = a * n + b;
                if (level[k] < lvMin) { lvMin = level[k]; lvPick = k; }
                if (bed[k] < bedMin) { bedMin = bed[k]; bedPick = k; }
            }
            if (lvPick < 0) { lvNone++; continue; }
            if (lvPick == bedPick) same++; else differ++;
            if (lake[lvPick]) lvIsLake++;
            if (bedPick >= 0 && lake[bedPick]) bedIsLake++;
            if (shown2++ < 6) {
                System.out.printf("  键(%d,%d) level=%.2f | 按level选(%d,%d) lv=%.2f 湖=%b | 按bed选(%d,%d) bed=%.2f 湖=%b%n",
                        o / n, o % n, level[o],
                        lvPick / n, lvPick % n, level[lvPick], lake[lvPick],
                        bedPick / n, bedPick % n, bed[bedPick], bed[bedPick] >= 0 && lake[bedPick]);
            }
        }
        System.out.printf("%n  level选与bed选【相同】=%d  【不同】=%d%n", same, differ);
        System.out.printf("  按 level 选出的格【是湖】=%d  ← 若高，说明 level 最低方向仍在湖内%n", lvIsLake);
        System.out.printf("  按 bed   选出的格【是湖】=%d%n", bedIsLake);
        System.out.println("  ⇒ 判读：若两者大量不同 ⇒ 我上次按 bed 找出口的修法方向错了；");
        System.out.println("     正确出口应由 level（minimax 水面）决定。");

        // ★★ 决定性检验：湖内键能否经【湖内 BFS】到达盆地边缘（非湖格）★★
        //   若可达 ⇒ 修法成立：用 BFS 找最近的非湖格作为出口。
        //   若不可达 ⇒ 该"湖"与外界完全不连通，问题更根本。
        System.out.println("\n=== 决定性检验：湖内键经湖内 BFS 能否到达非湖格 ===");
        int reachable = 0, unreachable = 0, total2 = 0;
        int[] dist = new int[cells];
        int[] queue = new int[cells];
        int shown3 = 0;
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells || !lake[o]) continue;
            total2++;
            java.util.Arrays.fill(dist, -1);
            int qh = 0, qt = 0;
            queue[qt++] = o; dist[o] = 0;
            int foundDist = -1, foundCell = -1;
            while (qh < qt && foundDist < 0) {
                int c = queue[qh++];
                if (dist[c] > 40) break;                 // 限深，防大湖跑太久
                int ci = c / n, cj = c % n;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int a = ci + d[0], b = cj + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int k = a * n + b;
                    if (!lake[k] && !sea[k]) { foundDist = dist[c] + 1; foundCell = k; break; }
                    if (dist[k] < 0 && lake[k]) { dist[k] = dist[c] + 1; queue[qt++] = k; }
                }
            }
            if (foundCell >= 0) {
                reachable++;
                if (shown3++ < 6) {
                    System.out.printf("  键(%d,%d) → 最近非湖格(%d,%d) 湖内距离=%d bed=%.2f level=%.2f%n",
                            o / n, o % n, foundCell / n, foundCell % n, foundDist,
                            bed[foundCell], level[foundCell]);
                }
            } else unreachable++;
        }
        System.out.printf("%n  湖内键总数=%d  可达盆地边缘=%d  不可达=%d%n",
                total2, reachable, unreachable);
        System.out.println("  ⇒ 若【可达】占多数 ⇒ 修法明确：overflowAt 键应改为 BFS 找到的边缘格；");
        System.out.println("     若【不可达】多 ⇒ 湖与外界不连通，是更根本的问题。");

        // ★★ 下泄路径追踪：从每个溢口注入点，沿【真实流线机制】走 200 格，逐段打印
        //    成河判定（flux vs 门槛）—— 直接看"下泄河在哪一步失去成河资格"。
        System.out.println("\n=== 下泄路径逐格追踪（溢口起点 → 200 格内每 10 格采样）===");
        double[] flux = r.discharge();
        double threshold = 1.0;   // Params.forRunoff 的 channelThreshold
        int traced = 0;
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            int st = outletExitLocal(o, lake, parent, sea, n, level, bed);
            if (st < 0) continue;
            if (traced++ >= 4) break;
            System.out.printf("── 溢口(%d,%d) 注入起点(%d,%d) flux=%.3f%n",
                    o / n, o % n, st / n, st % n, flux[st]);
            int cur = st;
            int steps = 0, toSea = 0, dead = 0;
            StringBuilder path = new StringBuilder();
            while (steps++ < 200 && cur >= 0) {
                if (sea[cur]) { toSea++; break; }
                int p = parent[cur];
                if (p < 0 || p == cur) { dead++; break; }
                if (steps % 10 == 0) {
                    path.append(String.format("    [%3d] (%4d,%4d) flux=%6.3f %s%n",
                            steps, cur / n, cur % n, flux[cur],
                            flux[cur] >= threshold ? "成河" : "★断"));
                }
                cur = p;
            }
            System.out.print(path);
            System.out.printf("    ⇒ 终态: %s（走了 %d 步）%n%n",
                    toSea > 0 ? "入海 ✓" : (dead > 0 ? "parent 断（流线死亡）" : "步数耗尽"), steps);
        }
    }

    /** outletExit 的本地副本（含 descendByLevel 修复），供路径追踪用。 */
    private static int outletExitLocal(int outlet, boolean[] lake, int[] parent, boolean[] sea,
                                       int nz, double[] level, double[] bed) {
        int cur = outlet, guard = 0;
        while (guard++ < 4096) {
            if (cur < 0) return -1;
            if (sea[cur]) return -1;
            if (!lake[cur]) return cur;
            int nxt = parent[cur];
            if (nxt >= 0) { cur = nxt; continue; }
            int ci = cur / nz, cj = cur % nz;
            double bestExitH = Double.POSITIVE_INFINITY;
            int bestExit = -1;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= nz || b >= nz) continue;
                int k = a * nz + b;
                if (sea[k]) return k;
                if (!lake[k] && bed[k] < bestExitH) { bestExitH = bed[k]; bestExit = k; }
            }
            if (bestExit < 0) return -1;
            cur = bestExit;
        }
        return -1;
    }
}