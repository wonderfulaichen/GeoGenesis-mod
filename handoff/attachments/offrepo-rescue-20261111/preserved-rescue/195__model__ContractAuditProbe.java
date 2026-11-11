package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>水文链【接口契约】审计 —— 逐条核对上游产出与下游预期是否自洽</h1>
 *
 * <h2>为什么要做这个（用户建议：「好好检查一遍水文系统的代码」）</h2>
 * <p>连续两次修复（溢口通道打通 54→0、最低鞍口）**在图上都无效**，
 * 说明问题不在被修的那个环节，而在【环节之间的契约】。</p>
 *
 * <p>静态阅读已发现 <b>3 处契约不自洽</b>，本探针逐条用数据验证：</p>
 *
 * <h3>[C1] {@code overflowAt} 的键语义 vs {@code outletExit} 的预期 —— 冲突</h3>
 * <ul>
 *   <li>{@code LakeWaterBalance} 的 javadoc 写：出口键 = {@code terminal[湖格]}
 *       = "沿 parent 回溯到的<b>入湖口</b>（下游第一个非湖格）"。</li>
 *   <li>但实现里 {@code terminal} 在 {@code parent < 0} 时被设为<b>该格自己</b>
 *       ⇒ 键可能是<b>湖内的死端格</b>，而非"湖外入湖口"。</li>
 *   <li>而 {@code outletExit} 期望键是"能沿 parent 继续走"的格。</li>
 * </ul>
 * <p><b>验证</b>：统计 {@code overflowAt} 的键中，有几成是【湖内格】、几成【parent&lt;0】。</p>
 *
 * <h3>[C2] {@code LakeWaterBalance} 用 {@code resolved} 过滤，河网不过滤 —— 口径不一</h3>
 * <ul>
 *   <li>湖：{@code isLakePot[k] = !sea && resolved[k] && ...}（只认已决格）</li>
 *   <li>河：{@code ContinuousRiverNetwork} <b>不接收 resolved 做闸门</b>（注释明写"未决格也输出 channel"）</li>
 * </ul>
 * <p><b>验证</b>：统计湖格中有多少比例 resolved=false（即湖与河的判定基础不同）。</p>
 *
 * <h3>[C3] 溢流注入的起点可能仍在湖内 —— 导致 85% 流线回到湖</h3>
 * <ul>
 *   <li>{@code outletExit} 返回"第一个非湖格"，但该格<b>可能仍是盆地内侧</b>（陆地上）。</li>
 *   <li>注入的流线从那里出发，速度场指向湖 ⇒ 立刻回到湖内终止。</li>
 * </ul>
 * <p><b>验证</b>：取若干溢口，看 {@code outletExit} 返回的起点格与湖的距离、
 * 以及该格的水面梯度是否指向湖。</p>
 *
 * <p><b>输出</b>：{@code build/audit/}：{@code contract_audit.txt} + {@code contract_map.png}</p>
 */
public final class ContractAuditProbe {

    private ContractAuditProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 300_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] lake = r.lake(), sea = r.sea(), ch = r.channel(), resolved = r.resolved();
        int[] parent = r.parent();
        double[] bed = r.bed(), level = r.level(), depth = r.depth();
        var lr = r.lakeResult();

        StringBuilder log = new StringBuilder();
        log.append("=== 水文链契约审计 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d  %dx%d%n%n", seed, cx, cz, radius, n, n));

        // ---------- [C1] overflowAt 键的语义 ----------
        int oTot = 0, oInLake = 0, oParentNeg = 0, oParentPos = 0, oIsChannel = 0;
        int oDepthPos = 0;
        java.util.List<Integer> oSamples = new java.util.ArrayList<>();
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            oTot++;
            if (lake[o]) oInLake++;
            if (parent[o] < 0) oParentNeg++; else oParentPos++;
            if (ch[o]) oIsChannel++;
            if (depth[o] > 0) oDepthPos++;
            if (oSamples.size() < 8) oSamples.add(o);
        }
        log.append("[C1] overflowAt 键的语义（javadoc 说=入湖口/湖外第一格）\n");
        log.append(String.format("     键总数=%d%n", oTot));
        log.append(String.format("     其中【在湖内】=%d (%.1f%%)  ← javadoc 说应为【湖外】%n",
                oInLake, 100.0 * oInLake / Math.max(1, oTot)));
        log.append(String.format("     parent<0（死端）=%d (%.1f%%)  parent>=0=%d%n",
                oParentNeg, 100.0 * oParentNeg / Math.max(1, oTot), oParentPos));
        log.append(String.format("     是河道格=%d   depth>0（在湖水面下）=%d%n", oIsChannel, oDepthPos));
        for (int o : oSamples) {
            log.append(String.format("       键(%d,%d): lake=%b parent=%d depth=%.2f level=%.1f bed=%.1f%n",
                    o / n, o % n, lake[o], parent[o], depth[o], level[o], bed[o]));
        }

        // ---------- [C2] resolved 口径 ----------
        int lakeResolvedNo = 0, lakeTot = 0, chResolvedNo = 0, chTot = 0;
        for (int k = 0; k < cells; k++) {
            if (lake[k]) { lakeTot++; if (!resolved[k]) lakeResolvedNo++; }
            if (ch[k]) { chTot++; if (!resolved[k]) chResolvedNo++; }
        }
        log.append(String.format("%n[C2] resolved 口径%n"));
        log.append(String.format("     湖格中 resolved=false = %d / %d (%.1f%%)%n",
                lakeResolvedNo, lakeTot, 100.0 * lakeResolvedNo / Math.max(1, lakeTot)));
        log.append(String.format("     河道格中 resolved=false = %d / %d (%.1f%%)%n",
                chResolvedNo, chTot, 100.0 * chResolvedNo / Math.max(1, chTot)));
        log.append("     ⇒ 湖用 resolved 过滤（只认已决格）；河不过滤 ⇒ 判定基础不同。\n");

        // ---------- [C3] 溢口起点与湖的关系 ----------
        log.append(String.format("%n[C3] 溢口起点（outletExit 返回格）与湖的关系%n"));
        int startInLake = 0, startDist1 = 0, startGradToLake = 0, startOk = 0, startFail = 0;
        java.util.List<String> s3 = new java.util.ArrayList<>();
        for (Integer o : lr.overflowAt().keySet()) {
            if (o == null || o < 0 || o >= cells) continue;
            int st = outletExitProbe(o, lake, parent, sea, n, level, bed, n);
            if (st < 0) { startFail++; continue; }
            if (lake[st]) startInLake++;
            // 起点与最近湖格的距离（4 邻域 BFS 1 层）
            int dist = nearestLakeDist(st, lake, n, 3);
            if (dist == 1) startDist1++;
            // 起点处速度指向是否朝湖
            boolean toLake = gradPointsToLake(st, lake, level, bed, resolved, n);
            if (toLake) startGradToLake++;
            else startOk++;
            if (s3.size() < 8) {
                s3.add(String.format("       (%d,%d)→起点(%d,%d) 在湖=%b 到湖距离=%d 梯度朝湖=%b",
                        o / n, o % n, st / n, st % n, lake[st], dist, toLake));
            }
        }
        log.append(String.format("     出口数=%d  起点失败=%d%n", oTot, startFail));
        log.append(String.format("     起点【仍在湖内】=%d%n", startInLake));
        log.append(String.format("     起点紧邻湖(距离1)=%d  ← 这些起点下一步必然被速度场推回湖里%n", startDist1));
        log.append(String.format("     起点处【速度指向湖】=%d  指向离开湖=%d%n", startGradToLake, startOk));
        log.append("     ⇒ 若【指向湖】占多数 ⇒ 溢流注入从湖岸出发但立刻回到湖（85% lakeStill 的机制）。\n");
        for (String s : s3) log.append(s).append('\n');

        java.nio.file.Path dir = java.nio.file.Path.of("build/audit");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("contract_audit.txt"), log);
        System.out.println(log);
    }

    /** 复刻 ContinuousRiverNetwork.outletExit（含本轮的 descendByLevel 修复）。 */
    private static int outletExitProbe(int outlet, boolean[] lake, int[] parent, boolean[] sea,
                                       int nz, double[] level, double[] bed, int nx) {
        int cur = outlet, guard = 0;
        while (guard++ < 4096) {
            if (cur < 0) return -1;
            if (sea[cur]) return -1;
            if (!lake[cur]) return cur;
            int nxt = parent[cur];
            if (nxt >= 0) { cur = nxt; continue; }
            int ci = cur / nz, cj = cur % nz;
            double bestLevel = level[cur];
            int best = -1;
            double bestExitH = Double.POSITIVE_INFINITY;
            int bestExit = -1;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int k = a * nz + b;
                if (sea[k]) return k;
                if (!Double.isFinite(level[k])) continue;
                if (!lake[k]) {
                    if (bed[k] < bestExitH) { bestExitH = bed[k]; bestExit = k; }
                    continue;
                }
                if (level[k] < bestLevel - 1e-9) { bestLevel = level[k]; best = k; }
            }
            int step = bestExit >= 0 ? bestExit : best;
            if (step < 0) return -1;
            cur = step;
        }
        return -1;
    }

    /** 到最近湖格的 4 邻域 BFS 距离（上限 cap）。 */
    private static int nearestLakeDist(int k, boolean[] lake, int n, int cap) {
        if (lake[k]) return 0;
        int ci = k / n, cj = k % n;
        for (int d = 1; d <= cap; d++) {
            for (int a = ci - d; a <= ci + d; a++) {
                for (int b = cj - d; b <= cj + d; b++) {
                    if (Math.max(Math.abs(a - ci), Math.abs(b - cj)) != d) continue;
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    if (lake[a * n + b]) return d;
                }
            }
        }
        return cap + 1;
    }

    /** 该格的速度方向是否指向湖（用与生产同口径的 s=resolved?level:bed 中心差分）。 */
    private static boolean gradPointsToLake(int k, boolean[] lake, double[] level, double[] bed,
                                            boolean[] resolved, int n) {
        int i = k / n, j = k % n;
        if (i < 1 || j < 1 || i >= n - 1 || j >= n - 1) return false;
        double sC = resolved[k] ? level[k] : bed[k];
        double sW = resolved[(i - 1) * n + j] ? level[(i - 1) * n + j] : bed[(i - 1) * n + j];
        double sE = resolved[(i + 1) * n + j] ? level[(i + 1) * n + j] : bed[(i + 1) * n + j];
        double sN = resolved[i * n + (j - 1)] ? level[i * n + (j - 1)] : bed[i * n + (j - 1)];
        double sS = resolved[i * n + (j + 1)] ? level[i * n + (j + 1)] : bed[i * n + (j + 1)];
        double gx = (sE - sW) / 2.0, gz = (sS - sN) / 2.0;   // 水面梯度
        // 速度 = -∇s/|∇s| ⇒ 移动方向
        double dx = -gx, dz = -gz;
        // 移动到邻格中心，看该邻格是不是湖
        int ni = i + (dx > 0.3 ? 1 : dx < -0.3 ? -1 : 0);
        int nj = j + (dz > 0.3 ? 1 : dz < -0.3 ? -1 : 0);
        if (ni < 0 || nj < 0 || ni >= n || nj >= n) return false;
        return lake[ni * n + nj];
    }
}