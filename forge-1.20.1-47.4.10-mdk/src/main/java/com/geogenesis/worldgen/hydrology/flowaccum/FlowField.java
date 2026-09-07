package com.geogenesis.worldgen.hydrology.flowaccum;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement.ElevationSampler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * D8 流向 + 汇流累积场（region 级纯函数，确定性）。
 *
 * <p><b>物理范式（2026-08-28 河流重构）</b>：河网不再由几何线"画"出来，
 * 而是从地形汇流场中"流"出来——每个格点流向 8 邻最低 e（严格更低才连边），
 * 按 e 降序拓扑序累加汇流面积。累积量即真实水文意义上的 drainage area。</p>
 *
 * <p><b>确定性</b>：e 全部来自 {@link ElevationSampler}（terrainEQuick 纯噪声场，
 * 无侵蚀 tile 依赖 → 无递归、无 8-14 卡死风险）；同 (范围, cellSize, sampler) 同结果。</p>
 *
 * <p><b>性能</b>：O(n) 三遍（采样 / 流向 / 累积）。region 对角 ~905wu、cellSize=16wu
 * → 约 61×61≈3.7k 格，亚毫秒级；结果随 region 缓存，跨 chunk 共享。</p>
 */
public final class FlowField {

    /** 追踪结果：路径点（wu）+ 终止原因。 */
    public record TraceResult(List<double[]> points, boolean reachedTarget, boolean hitSink) { }

    private final int nx, nz;
    private final double cellSize;
    private final double originX, originZ;
    private final double[] e;
    private final int[] flowTo;      // 下游格索引；-1 = 洼地（无更低邻居）
    private final double[] accum;    // 汇流面积（wu²，含自身格）
    // ===== 填洼层（2026-09-07 湖泊）：基于【真实地形】而非选线用的 routingE =====
    //   eFill   = 真实地形高程（block）
    //   eFilled = priority-flood 后的"溢出高程"：eFilled > eFill 的格 = 洼地内被水填起的部分
    private double[] eFill;
    private double[] eFilled;

    public FlowField(double minWuX, double minWuZ, double maxWuX, double maxWuZ,
                     double cellSize, ElevationSampler sampler) {
        this.cellSize = Math.max(1.0, cellSize);
        this.originX = minWuX;
        this.originZ = minWuZ;
        this.nx = Math.max(2, (int) Math.ceil((maxWuX - minWuX) / this.cellSize) + 1);
        this.nz = Math.max(2, (int) Math.ceil((maxWuZ - minWuZ) / this.cellSize) + 1);
        int n = nx * nz;
        this.e = new double[n];
        this.flowTo = new int[n];
        this.accum = new double[n];
        Arrays.fill(this.accum, this.cellSize * this.cellSize);
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                e[j * nx + i] = sampler.eAt(originX + i * this.cellSize,
                                           originZ + j * this.cellSize);
            }
        }
        buildFlow();
        buildAccum();
    }

    /** D8 流向：8 邻最低 e，严格更低才连边（平地/洼地 = 终点）。 */
    private void buildFlow() {
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                flowTo[idx] = lowestNeighbor(i, j);
            }
        }
    }

    /** 8 邻中 e 最低者（须严格低于自身）；返回 -1 表示洼地。固定扫描序保证确定性。 */
    private int lowestNeighbor(int ci, int cj) {
        int best = -1;
        double bestE = e[cj * nx + ci];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                double ne = e[nj * nx + ni];
                if (ne < bestE) { bestE = ne; best = nj * nx + ni; }
            }
        }
        return best;
    }

    // ===== 填洼（2026-09-07 湖泊功能）=====

    /** priority-flood 判为"被水填起"的最小水深（block）：滤掉浮点噪声。 */
    private static final double FILL_EPS = 0.05;

    /**
     * 用【真实地形】采样器建填洼层：湖泊必须按真实高程判定，不能按选线用的
     * routingE（山压低后的 e）——否则"湖"会落在被人为压低的坡面上。
     *
     * <p>算法（Barnes 2014 priority-flood）：以网格边界格为种子（水可流出网格），
     * 每次取当前最低格向外扩，邻格溢出高程 = max(自身高程, 当前格溢出高程)。
     * 结果 {@code eFilled} 即"若在此蓄水、水位涨到多少才会溢出"。</p>
     *
     * <p><b>同时覆盖闭合洼地与开口洼地</b>：真湖多是有出口、但出口坎（sill）高于
     * 盆地的【开口洼地】——D8 的 flowTo=-1 只认闭合洼地，漏掉这一类（实测
     * 本地形闭合洼地极少 → 湖数 0）。</p>
     */
    public void computeFill(ElevationSampler fillSampler) {
        int n = nx * nz;
        this.eFill = new double[n];
        this.eFilled = new double[n];
        Arrays.fill(eFilled, Double.NaN);
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                eFill[j * nx + i] = fillSampler.eAt(originX + i * cellSize,
                                                   originZ + j * cellSize);
            }
        }
        PriorityQueue<double[]> pq =
                new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                if (i != 0 && i != nx - 1 && j != 0 && j != nz - 1) continue;   // 仅边界格
                int idx = j * nx + i;
                eFilled[idx] = eFill[idx];
                pq.add(new double[]{eFill[idx], idx});
            }
        }
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            double h = cur[0];
            int idx = (int) cur[1];
            if (h > eFilled[idx]) continue;         // 过期条目（已被更低的溢出高程覆盖）
            int ci = idx % nx, cj = idx / nx;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                    int nIdx = nj * nx + ni;
                    if (!Double.isNaN(eFilled[nIdx])) continue;
                    double f = Math.max(eFill[nIdx], h);
                    eFilled[nIdx] = f;
                    pq.add(new double[]{f, nIdx});
                }
            }
        }
    }

    /** 是否已建填洼层。 */
    public boolean hasFill() { return eFilled != null; }

    /** 真实地形高程（block）；未建填洼层返回 {@link Double#NaN}。 */
    public double fillEAt(int idx) { return eFill == null ? Double.NaN : eFill[idx]; }

    /** 溢出高程（block）：该格蓄水后涨到多少才溢出。 */
    public double filledAt(int idx) { return eFilled == null ? Double.NaN : eFilled[idx]; }

    /** 该格的水深（= 溢出高程 − 真实地形）；非洼地格为 0。 */
    public double basinDepthAt(int idx) {
        if (eFilled == null) return 0.0;
        double d = eFilled[idx] - eFill[idx];
        return d > FILL_EPS ? d : 0.0;
    }

    /** 该格是否在洼地内（会被水填起）。 */
    public boolean isBasinCell(int idx) { return basinDepthAt(idx) > 0.0; }

    /** 汇流累积：按 e 降序处理（上游必先于下游），accum[down] += accum[cur]。 */
    private void buildAccum() {
        int n = nx * nz;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(e[b], e[a]));
        for (int k = 0; k < n; k++) {
            int cur = order[k];
            int down = flowTo[cur];
            if (down >= 0) accum[down] += accum[cur];
        }
    }

    /** 世界坐标 → 格索引（钳制到网格内）。 */
    public int indexOf(double wx, double wz) {
        int i = (int) Math.round((wx - originX) / cellSize);
        int j = (int) Math.round((wz - originZ) / cellSize);
        i = Math.max(0, Math.min(nx - 1, i));
        j = Math.max(0, Math.min(nz - 1, j));
        return j * nx + i;
    }

    /** 指定格的汇流面积（wu²）。 */
    public double accumAt(int idx) {
        return accum[idx];
    }

    public int cols() { return nx; }
    public int rows() { return nz; }
    public double eAt(int idx) { return e[idx]; }
    public int flowTo(int idx) { return flowTo[idx]; }
    public boolean inBounds(int i, int j) { return i >= 0 && i < nx && j >= 0 && j < nz; }

    /**
     * 是否存在"网格内"的更低邻居（严格低于 curE − minDrop）。
     * 用于终止判定：有则安全下坡（继续追踪），无则需进一步判断是湖还是越界回滚。
     */
    /**
     * {@code idx} 的【上游】邻格：8 邻中 D8 流向恰为 {@code idx} 的格，即直接汇水入该格的格。
     *
     * <p>flowTo 是"每格 → 下游"的正向映射，本方法做局部反向查询（扫 8 邻即可，无需
     * 建全局逆邻接表）。用于河源扇形散流：找补给河头的细流分支。</p>
     *
     * <p>固定扫描序（与 {@link #lowestNeighbor} 一致）保证跨版本确定性。</p>
     */
    public List<Integer> upstreamOf(int idx) {
        List<Integer> ups = new ArrayList<>(4);
        int ci = idx % nx, cj = idx / nx;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int n = nj * nx + ni;
                if (flowTo[n] == idx) ups.add(n);
            }
        }
        return ups;
    }

    public boolean hasInBoundsDownhill(int idx, double minDrop) {
        int ci = idx % nx, cj = idx / nx;
        double curE = e[idx];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                if (e[nj * nx + ni] < curE - minDrop) return true;
            }
        }
        return false;
    }

    /**
     * 该格是否紧邻网格边界（8 邻存在越界位置）。
     * 无网格内更低邻居却紧邻边界 → 河流想"流出网格"但不可见 → 不安全（应回滚，对齐 PL-RGA _hasUnsafeDownhillNeighbor）。
     */
    public boolean touchesGridEdge(int idx) {
        int ci = idx % nx, cj = idx / nx;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) return true;
            }
        }
        return false;
    }
    public double cellCenterX(int idx) { return originX + (idx % nx) * cellSize; }
    public double cellCenterZ(int idx) { return originZ + (idx / nx) * cellSize; }

    /**
     * 沿 D8 流向追踪路径：从 start 格逐格走向下游，收集格中心坐标（wu）。
     * 终止：到达 target 格 / 洼地（hitSink）/ 步数耗尽。
     */
    public TraceResult tracePath(int start, int target, int maxSteps) {
        List<double[]> pts = new ArrayList<>();
        int cur = start;
        boolean reached = false, sink = false;
        for (int step = 0; step <= maxSteps; step++) {
            pts.add(new double[]{cellCenterX(cur), cellCenterZ(cur)});
            if (cur == target) { reached = true; break; }
            int down = flowTo[cur];
            if (down < 0) { sink = true; break; }
            cur = down;
        }
        return new TraceResult(pts, reached, sink);
    }
}
