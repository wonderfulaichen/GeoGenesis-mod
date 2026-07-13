package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainClass;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界坐标确定性河网 + 河谷刻蚀（v1 Hydraulic 局部算子）。
 *
 * <p><b>范式</b>：在粗格点（{@code gridSize} 间距）上对统一 e 场（含海洋海床，连续）
 * 做下坡汇流，形成树枝状河网；河流顺地形自然汇入海洋，沿陆架海床继续「下坡」
 * 形成河口 / 水下河谷（海陆一体，无出口裁剪、无岸边阶梯断崖）。
 * 流量累积门控避免平坦区密集平行沟壑；仅「上游含陆地」的真河参与刻蚀，
 * 避免整片海床被误刻为峡谷。
 *
 * <p><b>确定性 / 无缝</b>：粗格点位置由整数 hash 抖动（±35% GRID）打散原始
 * 网格对齐伪影；河网按粗格 tile 缓存于 LongCache，跨 block 无 border 断点。
 *
 * <p><b>依赖</b>：仅通过 {@link HeightProvider} 读取高度场，与地形引擎解耦。
 * 该接口返回 REAL e（未经河流刻蚀），故本类在 Cell 计算之后调用，无递归。
 */
public final class RiverField {

    /** 每 tile 边节点数（tile = TILE_NODES × gridSize 块） */
    private static final int TILE_NODES = 8;
    /** 粗格点抖动比例（±35% GRID），打散网格对齐伪影 */
    private static final double JITTER = 0.7;
    /** 8 邻域方向偏移（E, SE, S, SW, W, NW, N, NE） */
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    /** 河床相对本地海床的最大下切深度（e 单位），封底跟随本地地形避免世界底穿帮 */
    private static final double CANYON_DEPTH = 0.12;
    /** tile 缓存上限（超过清一半） */
    private static final int CACHE_CAP = 1024;

    private final HeightProvider height;
    private final HeightCurve curve;
    private final RiverSettings s;
    private final double grid;

    private final Map<Long, Tile> cache = new ConcurrentHashMap<>();

    public RiverField(HeightProvider height, HeightCurve curve, RiverSettings settings) {
        this.height = height;
        this.curve = curve;
        this.s = settings;
        this.grid = settings.gridSize();
    }

    // ===== 查询：世界坐标 → 河流采样 =====

    /** 返回查询点河流信息（不修改 cell）。 */
    public RiverSample sample(double wx, double wz) {
        int ci = (int) Math.floor(wx / grid);
        int cj = (int) Math.floor(wz / grid);
        double bestDist = 1e9, bestWidth = 0;
        int bestFlow = 0;
        boolean bestFall = false;
        int bestSrc = 0;

        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int a = ci + di, b = cj + dj;
                Tile t = tileOf(a, b);
                int li = a - t.baseCi, lj = b - t.baseCj;
                if (li < 0 || li >= TILE_NODES || lj < 0 || lj >= TILE_NODES) continue;
                int d = t.down[li][lj];
                if (d < 0) continue;                       // 源 / 内流，无下游段
                if (!t.landUp[li][lj]) continue;           // 非陆地源，不显式为河（避免刻蚀海床）
                double flow = t.flow[li][lj];
                double gate = flowGate(flow);
                if (gate <= 0) continue;                   // 流量不足，不成河
                double[] pa = nodeWorld(a, b);
                double[] pb = nodeWorld(a + DX[d], b + DZ[d]);
                double dist = pointSegDist(wx, wz, pa[0], pa[1], pb[0], pb[1]);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestWidth = riverWidth(gate);
                    bestFlow = (int) flow;
                    bestFall = t.waterfall[li][lj];
                    bestSrc = t.srcType[li][lj];
                }
            }
        }

        RiverSample r = RiverSample.empty();
        if (bestWidth > 0 && bestDist <= bestWidth) {
            r.riverMask = true;
            r.riverDistance = NoiseUtil.clamp(bestDist / bestWidth, 0.0, 1.0);
            r.riverWetness = NoiseUtil.smoother(1.0 - r.riverDistance);
            r.flowCount = bestFlow;
            r.isWaterfall = bestFall;
            r.sourceType = bestSrc;
        } else {
            r.flowCount = bestFlow;
        }
        return r;
    }

    /**
     * 将河流信息 + 河谷刻蚀应用到 cell（修改 e / height / 河流字段）。
     *
     * <p><b>海陆一体</b>：刻蚀门控基于统一 e（含海洋海床）与 {@code riverMinE}
     * （陆架下限，默认 -0.35）而非「陆地/海洋硬切」。河流从陆地经海岸连续刻入
     * 陆架（河口 / 水下河谷），深海盆（e ≤ riverMinE）不刻，避免岸边阶梯断崖。
     * 仅陆地河道（refE ≥ 0）标 {@code RIVER} 群系；水下峡谷保持 OCEAN 群系、地形连续下切。
     */
    public void apply(Cell cell, double wx, double wz) {
        RiverSample r = sample(wx, wz);
        double origH = cell.height;

        cell.riverMask = r.riverMask;
        cell.riverWetness = r.riverWetness;
        cell.riverDistance = r.riverDistance;
        cell.riverNetDist = r.riverDistance;
        cell.riverNetDischarge = r.flowCount;
        cell.riverIsWaterfall = r.isWaterfall;
        cell.riverSourceType = r.sourceType;

        double refE = cell.e;                          // 刻蚀前统一 e（含海洋海床）
        if (r.riverMask && refE > s.riverMinE()) {
            double gate = flowGate(r.flowCount);
            double centerDepth = s.bedDepth() * gate;
            double edgeDepth = s.bankDepth() * gate;
            double depth = NoiseUtil.lerp(centerDepth, edgeDepth, r.riverDistance);
            double carved = refE - depth;
            double floor = refE - CANYON_DEPTH;         // 河床封底跟随本地海床
            if (carved < floor) carved = floor;
            if (carved < -0.98) carved = -0.98;         // 世界底余量
            cell.e = carved;
            cell.height = curve.heightFromE(carved);
            cell.riverFloorY = cell.height;
            cell.riverSurfaceY = Math.max(origH, curve.seaLevelY());
            if (refE >= 0.0) {                          // 仅陆地河道标 RIVER；水下峡谷保持 OCEAN
                cell.terrainType = TerrainClass.RIVER;
                cell.isRiver = true;
            }
        }
    }

    // ===== 内部：tile 构建 =====

    /** 单 tile 河网数据（仅存下游/流量/标记，高度环构建后即弃）。 */
    private static final class Tile {
        int baseCi, baseCj;
        int[][] down;            // TILE_NODES×TILE_NODES，-1=无下游
        int[][] flow;            // 流量累积（陆地源节点初值 1，海洋非源节点不累积）
        boolean[][] waterfall;
        int[][] srcType;         // 0 无 / 1 溪源 / 2 山泉 / 3 源头湖
        boolean[][] landUp;      // 上游含陆地（真河标记，由陆地沿 down 正向传递）
    }

    private Tile tileOf(int ci, int cj) {
        int tci = Math.floorDiv(ci, TILE_NODES);
        int tcj = Math.floorDiv(cj, TILE_NODES);
        long key = ((long) tci << 32) | (tcj & 0xffffffffL);
        Tile t = cache.get(key);
        if (t == null) {
            t = buildTile(tci, tcj);
            cache.put(key, t);
            if (cache.size() > CACHE_CAP) {
                var it = cache.keySet().iterator();
                int n = cache.size() / 2;
                for (int i = 0; i < n && it.hasNext(); i++) it.next();
            }
        }
        return t;
    }

    private Tile buildTile(int tci, int tcj) {
        Tile t = new Tile();
        t.baseCi = tci * TILE_NODES;
        t.baseCj = tcj * TILE_NODES;
        int n = TILE_NODES;
        t.down = new int[n][n];
        t.flow = new int[n][n];
        t.waterfall = new boolean[n][n];
        t.srcType = new int[n][n];
        t.landUp = new boolean[n][n];

        // 高度环 (n+2)×(n+2)，含 1 圈边框（跨 tile 邻居）：统一 e（含海洋海床）
        int m = n + 2;
        double[][] h = new double[m][m];
        for (int lj = 0; lj < m; lj++) {
            for (int li = 0; li < m; li++) {
                int a = t.baseCi + li - 1;
                int b = t.baseCj + lj - 1;
                double[] w = nodeWorld(a, b);
                h[li][lj] = height.terrainE((int) Math.floor(w[0]), (int) Math.floor(w[1]));
            }
        }

        route(t, h);
        t.landUp = computeLandUp(t, h);
        accumulate(t, h, t.landUp);
        classifySources(t, h);
        return t;
    }

    /**
     * 下坡路由：每内点在 8 邻居中选严格最低者。
     * 统一 e 场（含海洋海床，连续）下，河流顺地形自然汇入海洋，
     * 河网沿陆架海床继续「下坡」→ 水下河谷 / 陆架峡谷（海陆一体，无出口裁剪）。
     */
    private void route(Tile t, double[][] h) {
        int n = TILE_NODES;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double hc = h[i + 1][j + 1];
                int best = -1;
                double bestH = hc;
                for (int d = 0; d < 8; d++) {
                    double hn = h[i + 1 + DX[d]][j + 1 + DZ[d]];
                    if (hn < bestH) { bestH = hn; best = d; }
                }
                t.down[i][j] = best;
                if (best >= 0 && hc - h[i + 1 + DX[best]][j + 1 + DZ[best]] > s.waterfallDrop()) {
                    t.waterfall[i][j] = true;
                }
            }
        }
    }

    /**
     * 标记「上游含陆地」节点：自所有陆地节点沿 down 正向 BFS，标记其下游链。
     * 仅这些节点参与流量累积与刻蚀，避免整片海床被误刻为峡谷（真河及其水下延续才刻）。
     */
    private boolean[][] computeLandUp(Tile t, double[][] h) {
        int n = TILE_NODES;
        boolean[][] up = new boolean[n][n];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[i + 1][j + 1] >= 0.0) { up[i][j] = true; q.add(new int[]{i, j}); }
            }
        }
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int i = cur[0], j = cur[1];
            int d = t.down[i][j];
            if (d < 0) continue;
            int ti = i + DX[d], tj = j + DZ[d];
            if (ti >= 0 && ti < n && tj >= 0 && tj < n && !up[ti][tj]) {
                up[ti][tj] = true;
                q.add(new int[]{ti, tj});
            }
        }
        return up;
    }

    /** 流量累积：按高度降序处理，下游 += 上游；仅陆地源节点累积（河口流量大→刻得深）。 */
    private void accumulate(Tile t, double[][] h, boolean[][] landUp) {
        int n = TILE_NODES;
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++)
                t.flow[i][j] = 1;   // 全部节点初值 1

        int[][] order = new int[n * n][2];
        int cnt = 0;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) order[cnt++] = new int[]{i, j};
        Arrays.sort(order, 0, cnt, (p, q) ->
            Double.compare(h[q[0] + 1][q[1] + 1], h[p[0] + 1][p[1] + 1])); // 降序 e

        for (int k = 0; k < cnt; k++) {
            int i = order[k][0], j = order[k][1];
            if (!landUp[i][j]) continue;            // 非陆地源，不累积（不刻蚀海床）
            int d = t.down[i][j];
            if (d < 0) continue;
            int ti = i + DX[d], tj = j + DZ[d];
            if (ti >= 0 && ti < n && tj >= 0 && tj < n) {
                t.flow[ti][tj] += t.flow[i][j] + 1;
            }
        }
    }

    /** 源头分类：入度 0 且陆地 → 溪源/山泉；局部汇水洼地 → 源头湖。海洋（e<0）跳过，避免误标海床洼地。 */
    private void classifySources(Tile t, double[][] h) {
        int n = TILE_NODES;
        int[][] indeg = new int[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int d = t.down[i][j];
                if (d < 0) continue;
                int ti = i + DX[d], tj = j + DZ[d];
                if (ti >= 0 && ti < n && tj >= 0 && tj < n) indeg[ti][tj]++;
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[i + 1][j + 1] < 0.0) continue;           // 海洋（含陆架）不标源头
                if (t.down[i][j] == -1) {
                    t.srcType[i][j] = 3;                       // 内流洼地 → 源头湖
                } else if (indeg[i][j] == 0) {
                    t.srcType[i][j] = h[i + 1][j + 1] > 0.6 ? 2 : 1;  // 山泉 / 溪源
                }
            }
        }
    }

    // ===== 内部：几何工具 =====

    /** 粗格点 (ci, cj) 的世界坐标（确定性 hash 抖动）。 */
    private double[] nodeWorld(int ci, int cj) {
        double jx = (NoiseUtil.hash2_01(ci * 2 + 1, cj * 2) - 0.5) * JITTER * grid;
        double jz = (NoiseUtil.hash2_01(ci * 2, cj * 2 + 1) - 0.5) * JITTER * grid;
        return new double[]{ci * grid + jx, cj * grid + jz};
    }

    /** 点到线段距离。 */
    private static double pointSegDist(double px, double pz,
                                       double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) return Math.hypot(px - x1, pz - z1);
        double tt = ((px - x1) * dx + (pz - z1) * dz) / len2;
        tt = NoiseUtil.clamp(tt, 0.0, 1.0);
        return Math.hypot(px - (x1 + tt * dx), pz - (z1 + tt * dz));
    }

    /** 流量门控：flowMin 以下 0，flowFull 以上 1，中间平滑。 */
    private double flowGate(double flow) {
        double g = (flow - s.flowMin()) / (s.flowFull() - s.flowMin());
        return NoiseUtil.clamp(g, 0.0, 1.0);
    }

    /** 河宽：随流量门控从 minWidth → maxWidth 线性插值。 */
    private double riverWidth(double gate) {
        return NoiseUtil.lerp(s.minWidth(), s.maxWidth(), gate);
    }
}
