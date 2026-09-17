package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 水体物理审计（2026-09-17）—— 把"水位是否按短板（水桶效应）"变成可量化的表。
 *
 * <h2>为什么要它</h2>
 * <p>用户反复反馈"湖泊水位不对：悬空 / 淹到山腰 / 没有短板效益"，而此前 9 次归因
 * 全部被实测推翻（类型场、火山掩码、阈值等值线、台阶、48wu 量化、口径…）。
 * 根因之一是：<b>我从未量化过"这个水位应该是多少"</b>——只是看读数是否"看着怪"。</p>
 *
 * <h2>本探针的判据（物理定义，非经验阈值）</h2>
 * <p>对每个【已放置水体】（实际放置口径 {@code getChunkCells}，含侵蚀+水文雕刻）：</p>
 * <ol>
 *   <li>{@code level} = 该水体的水面高度（湖为常量，河沿程变化 → 见下"按水位分组"）；</li>
 *   <li>{@code required} = 从该水体的<b>最低格</b>出发、在【最终地形】上到窗口边界的
 *       <b>最小最大路径高度</b>（minimax escape）—— 即"水要流出去，路上最高要越过多少"。
 *       <b>物理上水位不可能高于 required</b>（否则水会流走）；</li>
 *   <li>{@code 违反 = level − required} ⇒ <b>&gt;0 即"水位过高、本该流走却停在原地"</b>；</li>
 *   <li>{@code 干墙} = 与该水体 4 邻、<b>高度低于水位</b>却是干的格数
 *       ⇒ &gt;0 即"水边缘有一堵没有地形支撑的水墙"（用户截图里最刺眼的东西）。</li>
 * </ol>
 *
 * <h2>与旧审计的区别（务必别混）</h2>
 * <p>此前的"紧邻最低旱地"审计只能发现<b>覆盖缺陷</b>（该淹没淹）；
 * 而"水位高于逃逸高度"发现的才是<b>水位缺陷</b>（水位本身算错）。
 * 用户抱怨的是<b>后者</b>（"没有短板效益"），所以必须用本判据。</p>
 *
 * <pre>{@code gradlew runWaterPhysicsProbe [-PprobeArgs="seed wuX wuZ halfWu"]}</pre>
 */
public final class WaterPhysicsProbe {

    private WaterPhysicsProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        double wuX = args.length > 1 ? Double.parseDouble(args[1]) : 12.0;
        double wuZ = args.length > 2 ? Double.parseDouble(args[2]) : 316.0;
        int halfWu = args.length > 3 ? Integer.parseInt(args[3]) : 96;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        int half = (int) Math.round(halfWu * hs);          // 半宽（块）
        int n = 2 * half + 1;
        int bx0 = (int) Math.floor(wuX * hs) - half;
        int bz0 = (int) Math.floor(wuZ * hs) - half;
        double[] h = new double[n * n];
        boolean[] wat = new boolean[n * n];
        double[] lvl = new double[n * n];
        Arrays.fill(lvl, Double.NaN);

        // ★ 模式：默认 = 实际放置口径；"pure" = 纯噪声场（零 tile 依赖）。
        //   用途：【逃逸高度收敛性测试】—— 同一高度函数下把窗口从 ±96wu 放到 ±192wu，
        //   看"应有水位"是否收敛。若随窗口增大而上升 ⇒ 小的窗口读数是**边界假象**
        //   （水体的真实盆沿在窗口之外），不能据此判定"水位过高"。
        boolean pure = args.length > 4 && "pure".equalsIgnoreCase(args[4]);
        if (pure) {
            for (int gz = 0; gz < n; gz++) {
                for (int gx = 0; gx < n; gx++) {
                    Cell c = gen.sample((bx0 + gx) / hs, (bz0 + gz) / hs);
                    int i = gz * n + gx;
                    h[i] = c.height;
                    wat[i] = c.riverType != 0;
                    if (wat[i]) lvl[i] = c.riverSurfaceY;
                }
            }
            System.out.println("⚠ 模式 = pure（纯噪声 sample()）：仅用于逃逸收敛性测试，非放置口径。");
        }
        // 实际放置口径铺场（含侵蚀 + 水文雕刻）
        for (int cx = pure ? 1 : (bx0 >> 4); cx <= (pure ? 0 : (bx0 + n - 1) >> 4); cx++) {
            for (int cz = bz0 >> 4; cz <= (bz0 + n - 1) >> 4; cz++) {
                Cell[] cs = gt.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int gx = cx * 16 + lx - bx0, gz = cz * 16 + lz - bz0;
                        if (gx < 0 || gx >= n || gz < 0 || gz >= n) continue;
                        Cell c = cs[lx * 16 + lz];
                        int i = gz * n + gx;
                        h[i] = c.height;
                        wat[i] = c.riverType != 0;
                        if (wat[i]) lvl[i] = c.riverSurfaceY;
                    }
                }
            }
        }

        System.out.printf("=== WaterPhysicsProbe seed=%d 中心 wu(%.0f,%.0f) ±%dwu（%d×%d 块）===%n",
                seed, wuX, wuZ, halfWu, n, n);
        System.out.println("口径 = getChunkCells（含侵蚀 + 水文雕刻，= 玩家所见）");

        // 水体分组：同水位 + 4 邻连通
        int[] comp = new int[n * n];
        Arrays.fill(comp, -1);
        List<int[]> bodies = new ArrayList<>();   // {seedIdx}
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n * n; i++) {
            if (!wat[i] || comp[i] >= 0) continue;
            int id = bodies.size();
            bodies.add(new int[]{i});
            comp[i] = id;
            q.add(i);
            while (!q.isEmpty()) {
                int cur = q.poll();
                int cx2 = cur % n, cz2 = cur / n;
                int[] dx = {1, -1, 0, 0};
                int[] dz = {0, 0, 1, -1};
                for (int d = 0; d < 4; d++) {
                    int nx = cx2 + dx[d], nz = cz2 + dz[d];
                    if (nx < 0 || nx >= n || nz < 0 || nz >= n) continue;
                    int ni = nz * n + nx;
                    if (!wat[ni] || comp[ni] >= 0) continue;
                    if (Math.abs(lvl[ni] - lvl[cur]) > 0.05) continue;   // 水位不同 → 不同水体
                    comp[ni] = id;
                    q.add(ni);
                }
            }
        }

        // 逐水体：面积 / 水位 / 盆底 / 干墙 / 应有水位（minimax 逃逸）
        int shown = 0;
        System.out.println();
        System.out.printf("%-4s %-9s %7s %9s %9s %8s %9s %8s%n",
                "水体", "水位", "面积", "盆底最低", "应有水位", "违反", "干墙格数", "判定");
        List<String> rows = new ArrayList<>();
        int bad = 0, wallBodies = 0;
        for (int id = 0; id < bodies.size(); id++) {
            int cnt = 0;
            double level = Double.NaN, floorMin = Double.MAX_VALUE;
            for (int i = 0; i < n * n; i++) {
                if (comp[i] != id) continue;
                cnt++;
                if (Double.isNaN(level)) level = lvl[i];
                floorMin = Math.min(floorMin, h[i]);
            }
            if (cnt < 8) continue;
            // 干墙：与水体 4 邻、高度低于水位、却是干的
            int walls = 0;
            for (int i = 0; i < n * n; i++) {
                if (comp[i] != id) continue;
                int cx2 = i % n, cz2 = i / n;
                int[] dx = {1, -1, 0, 0};
                int[] dz = {0, 0, 1, -1};
                for (int d = 0; d < 4; d++) {
                    int nx = cx2 + dx[d], nz = cz2 + dz[d];
                    if (nx < 0 || nx >= n || nz < 0 || nz >= n) continue;
                    int ni = nz * n + nx;
                    if (wat[ni]) continue;
                    if (h[ni] < level - 0.5) walls++;
                }
            }
            double required = escape(h, n, floorMinIdx(h, comp, id, n));
            double viol = level - required;
            if (walls > 0) wallBodies++;
            boolean ok = viol <= 0.5 && walls == 0;
            if (!ok) bad++;
            if (walls > 0 || viol > 0.5 || cnt > 500) {
                rows.add(String.format("%-4d %9.3f %7d %9.3f %9.3f %+8.3f %9d %8s",
                        id, level, cnt, floorMin, required, viol, walls, ok ? "合格" : "违反"));
            }
        }
        for (String r : rows) System.out.println(r);
        System.out.println();
        System.out.printf("水体总数 %d；其中【违反】（水位过高 或 存在干墙）%d 个，有干墙的 %d 个%n",
                bodies.size(), bad, wallBodies);
        System.out.println();
        System.out.println("判读：");
        System.out.println("  · 违反 > 0 ⇒ 水位【高于逃逸高度】：水本该流走却停在原地 ⇒ 短板未生效；");
        System.out.println("  · 干墙 > 0 ⇒ 水边缘没有地形支撑（水墙）⇒ 覆盖缺陷或水位缺陷；");
        System.out.println("  · 两者都 0 ⇒ 该水体在【最终地形】上是水力自洽的。");

        // ★ 收敛性测试：逃逸高度对【窗口大小】的依赖（判定"水位是否真错"的决定性一步）
        int best = -1, bestCnt = 0;
        for (int i = 0; i < n * n; i++) {
            if (comp[i] < 0) continue;
            int c2 = 0;
            for (int j = 0; j < n * n; j++) if (comp[j] == comp[i]) c2++;
            if (c2 > bestCnt) { bestCnt = c2; best = i; }
        }
        if (best >= 0) {
            sectionConvergence(gen, bx0 + best % n, bz0 + best / n, hs, halfWu,
                    lvl[best]);
        }
    }

    /**
     * 逃逸高度随【搜索半径】的变化 —— 判定"应有水位"是真实盆沿还是窗口假象。
     *
     * <p>在【点态最终地形】（{@code carveColumnAt}，实测与实际放置差 0.005 块）上做
     * <b>一次</b> Dijkstra（成本 = 路径最大高度），记录"首次到达半径 R 的环"时的成本。
     * 成本对半径单调不减 ⇒ 若读数随 R 持续上升，说明小的窗口只是<b>切在水体内部</b>。</p>
     */
    private static void sectionConvergence(CellGenerator gen, int seedBx, int seedBz,
                                           double hs, int halfWu, double level) {
        int H = 192;                                   // wu：最大搜索半径
        int half = (int) Math.round(H * hs);
        int m = 2 * half + 1;
        System.out.println();
        System.out.printf("[收敛] 逃逸高度 vs 搜索半径（点态最终地形，%d×%d，中心块(%d,%d)）%n",
                m, m, seedBx, seedBz);
        if ((long) m * m > 3_000_000L) {
            System.out.println("    网格过大，跳过。");
            return;
        }
        double[] hh = new double[m * m];
        HydrologyExperimentEngine eng = new HydrologyExperimentEngine(gen, 0L);
        for (int gz = 0; gz < m; gz++) {
            for (int gx = 0; gx < m; gx++) {
                int bx = seedBx - half + gx, bz = seedBz - half + gz;
                double wuX = bx / hs, wuZ = bz / hs;
                double o = gen.sample(wuX, wuZ).height;
                HydrologyBlockCarvedColumn c =
                        HydrologyBlockCarver.carveColumnAt(eng, bx, bz, o, hs);
                if (c == null) {
                    hh[gz * m + gx] = gen.sampleWu(wuX, wuZ).height;
                } else {
                    double raw = gen.sampleWu(wuX, wuZ).height - c.originalGroundY();
                    hh[gz * m + gx] = c.carvedGroundY() + raw * c.erosionMask();
                }
            }
        }
        int si = half * m + half;
        double[] cost = new double[m * m];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        PriorityQueue<long[]> pq = new PriorityQueue<>(
                (a, b) -> Double.compare(Double.longBitsToDouble(a[1]),
                        Double.longBitsToDouble(b[1])));
        cost[si] = hh[si];
        pq.add(new long[]{si, Double.doubleToLongBits(hh[si])});
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        int[] ringsWu = {48, 96, 144, 192};
        int ri = 0;
        while (!pq.isEmpty() && ri < ringsWu.length) {
            long[] cur = pq.poll();
            int idx = (int) cur[0];
            double c = Double.longBitsToDouble(cur[1]);
            if (c > cost[idx]) continue;
            int cx = idx % m, cz = idx / m;
            double dWu = Math.hypot(cx - half, cz - half) / hs;
            while (ri < ringsWu.length && dWu >= ringsWu[ri]) {
                System.out.printf("    半径 %3d wu（%4d 块）：逃逸高度 = %.3f  ⇒ 相对水位 %.3f 为 %+.3f%n",
                        ringsWu[ri], (int) (ringsWu[ri] * hs), c, level, level - c);
                ri++;
            }
            for (int d = 0; d < 4; d++) {
                int nx = cx + dx[d], nz = cz + dz[d];
                if (nx < 0 || nx >= m || nz < 0 || nz >= m) continue;
                int ni = nz * m + nx;
                double nc = Math.max(c, hh[ni]);
                if (nc < cost[ni] - 1e-9) {
                    cost[ni] = nc;
                    pq.add(new long[]{ni, Double.doubleToLongBits(nc)});
                }
            }
        }
        System.out.println("    判读：读数随半径【持续上升】⇒ 小窗口的读数只是切在水体内部（假象）；");
        System.out.println("          读数【稳定】在某值 ⇒ 那才是该水体的真实盆沿（水位应 ≤ 它）。");
    }

    private static int floorMinIdx(double[] h, int[] comp, int id, int n) {
        int best = -1;
        double bv = Double.MAX_VALUE;
        for (int i = 0; i < n * n; i++) {
            if (comp[i] != id) continue;
            if (h[i] < bv) { bv = h[i]; best = i; }
        }
        return Math.max(0, best);
    }

    /**
     * 最小最大路径逃逸高度：从种子出发到窗口边界的 {@code min over paths of max(h)}。
     * 物理含义：水位若高于此值，水就能沿该路径流出 ⇒ 不可能被蓄住。
     */
    private static double escape(double[] h, int n, int seedIdx) {
        double[] cost = new double[n * n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        PriorityQueue<long[]> pq = new PriorityQueue<>(
                (a, b) -> Double.compare(Double.longBitsToDouble(a[1]),
                        Double.longBitsToDouble(b[1])));
        cost[seedIdx] = h[seedIdx];
        pq.add(new long[]{seedIdx, Double.doubleToLongBits(h[seedIdx])});
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            int idx = (int) cur[0];
            double c = Double.longBitsToDouble(cur[1]);
            if (c > cost[idx]) continue;
            int cx = idx % n, cz = idx / n;
            if (cx == 0 || cx == n - 1 || cz == 0 || cz == n - 1) return c;
            for (int d = 0; d < 4; d++) {
                int nx = cx + dx[d], nz = cz + dz[d];
                if (nx < 0 || nx >= n || nz < 0 || nz >= n) continue;
                int ni = nz * n + nx;
                double nc = Math.max(c, h[ni]);
                if (nc < cost[ni] - 1e-9) {
                    cost[ni] = nc;
                    pq.add(new long[]{ni, Double.doubleToLongBits(nc)});
                }
            }
        }
        return Double.NaN;
    }
}
