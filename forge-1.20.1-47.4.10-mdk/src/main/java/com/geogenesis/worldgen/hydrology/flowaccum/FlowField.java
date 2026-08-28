package com.geogenesis.worldgen.hydrology.flowaccum;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement.ElevationSampler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
