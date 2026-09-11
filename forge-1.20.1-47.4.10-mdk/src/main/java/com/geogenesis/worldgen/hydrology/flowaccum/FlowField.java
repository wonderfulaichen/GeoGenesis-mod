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

    /** 降水取样器（归一化相对降水，见 {@code CellGenerator.precipitationAt}）。 */
    @FunctionalInterface
    public interface PrecipSampler {
        double precipitationAt(double wx, double wz);
    }

    /**
     * 降水 → 汇流权重（★ 2026-09-11 Phase C）。
     *
     * <p><b>消费端归一化</b>（设计文档 §4.3.4 修正）：气候侧只产出"物理相对降水"
     * （实测全局均值 ≈ 0.27），本类用<b>显式参考值</b> {@code ref} 折算成权重原点。
     * 拆开的好处：改降水标定<b>不会</b>悄悄平移全部河宽（与 {@code widthAreaRef} 解耦同一条教训）。</p>
     *
     * <pre>
     * weight = clamp(precip / ref, floor, +inf) ^ exponent
     * </pre>
     *
     * @param ref      参考降水（取实测全局均值 → 权重均值 ≈ 1，河宽不整体平移）
     * @param floor    权重下限（防极端干旱把汇流压到 0 → 沙漠彻底断流）
     * @param exponent 软化指数（&lt;1 收敛差异；1.0 = 线性）
     */
    public record PrecipWeights(double ref, double floor, double exponent) {
        /**
         * 默认参数。<b>ref 经实测标定</b>（2026-09-11）：
         *
         * <p>指数 0.6 为凹函数（Jensen 效应）→ 直接取 {@code ref = 降水均值} 时权重均值会偏离 1，
         * 汇流被系统性放大/缩小 → 河网被门槛多裁或少裁。按
         * {@code ref' = ref · mean^(1/exp)} 反解，使<b>权重均值回到 ≈1.0</b>（河宽不整体平移）。</p>
         *
         * <p>标定历史（每次降水分布变化都需重标）：
         * <ul>
         *   <li>初期（降水均值 0.272）→ <b>0.17</b>（权重均值 0.951）；</li>
         *   <li>★ 纬度改余弦后（2026-09-11，降水均值升到 <b>0.347</b>，热带/温带增湿）
         *       → <b>0.212</b>。旧 0.17 会让权重均值漂到 <b>1.143</b>（探针 [7] 实测 FAIL），
         *       即所有河普遍偏粗。</li>
         * </ul>
         */
        public static PrecipWeights defaults() { return new PrecipWeights(0.212, 0.10, 0.60); }
        public static PrecipWeights disabled() { return new PrecipWeights(1.0, 1.0, 0.0); }

        /** 降水 → 累积权重（均值 ≈ 1）。 */
        public double weight(double precip) {
            double r = precip / ref;
            if (r < floor) r = floor;
            return Math.pow(r, exponent);
        }
    }

    /**
     * 降水粗格点间距（<b>wu</b>，非 flow 格数）。
     *
     * <p><b>为什么必须粗采</b>（★ 2026-09-11 实测教训）：{@code PrecipSampler} 走
     * {@code CellGenerator.sample()}，实测单次约 <b>0.5 ms</b>（远贵于 flow 自身的
     * {@code terrainEQuick}）。若逐 flow 格采样（实测 region 内上万格），单 region 涨
     * <b>~215 ms</b>，{@code runFlowAccumProbe} 的 coldMs 从基线 1849 ms 飙到 17355 ms（9.4×）。</p>
     *
     * <p><b>为什么间距用固定 wu 且全球对齐</b>：格点取 {@code k·PRECIP_STEP_WU}（世界坐标），
     * 与 region 无关 → 同一点在任何 region 都取到<b>同一个值</b>，杜绝"region 相关伪影"；
     * 且相邻 region 的格点集合大量重叠 → 天然可复用。</p>
     *
     * <p>代价（如实说明）：跨度 1280wu 的 region 只有 ~6×6 格点 → 水文只吃到降水的
     * <b>大尺度分量</b>（气候带/大陆尺度），<b>地形雨的谷坡尺度细节不进入汇流加权</b>
     * （它仍完整存在于气候层：预览图层 / Cell.precipitation）。这是性能与保真度的取舍。</p>
     */
    private static final double PRECIP_STEP_WU = 320.0;

    /** 本次构建用到的降水格点值（局部缓存，避免同一格点重复调 sample()）。 */
    private java.util.HashMap<Long, Double> precipNodes;
    /** 降水取样器（构造时注入；null = 不启用降水加权）。 */
    private PrecipSampler precipSampler;
    /** 降水权重参数。 */
    private PrecipWeights precipWeights;

    public FlowField(double minWuX, double minWuZ, double maxWuX, double maxWuZ,
                     double cellSize, ElevationSampler sampler) {
        this(minWuX, minWuZ, maxWuX, maxWuZ, cellSize, sampler, null, PrecipWeights.disabled());
    }

    /**
     * @param precip  降水取样器；{@code null} = 纯面积累积（与旧路径逐位一致）
     * @param weights 降水权重参数（仅 {@code precip != null} 时生效）
     */
    public FlowField(double minWuX, double minWuZ, double maxWuX, double maxWuZ,
                     double cellSize, ElevationSampler sampler,
                     PrecipSampler precip, PrecipWeights weights) {
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
        // ★ Phase C：降水加权初始累积（每格产流量 = 面积 × 降水权重）
        if (precip != null && weights != null) {
            this.precipSampler = precip;
            this.precipWeights = weights;
            this.precipNodes = new java.util.HashMap<>();
            for (int j = 0; j < nz; j++) {
                double wz = originZ + j * this.cellSize;
                for (int i = 0; i < nx; i++) {
                    double wx = originX + i * this.cellSize;
                    accum[j * nx + i] = this.cellSize * this.cellSize
                                      * weights.weight(precipAtWu(wx, wz));
                }
            }
        }
        buildFlow();
        buildAccum();
    }

    /** 世界坐标处的降水（由全球对齐的粗格点双线性插值）。 */
    private double precipAtWu(double wx, double wz) {
        double gx = wx / PRECIP_STEP_WU, gz = wz / PRECIP_STEP_WU;
        int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gz);
        double fi = gx - i0, fj = gz - j0;
        double a0 = precipNode(i0, j0),     a1 = precipNode(i0 + 1, j0);
        double b0 = precipNode(i0, j0 + 1), b1 = precipNode(i0 + 1, j0 + 1);
        double a = a0 + (a1 - a0) * fi;
        double b = b0 + (b1 - b0) * fi;
        return a + (b - a) * fj;
    }

    /** 降水格点值（<b>全球对齐</b>：坐标 = k·PRECIP_STEP_WU，与 region 无关 → 跨 region 一致）。 */
    private double precipNode(int ix, int iz) {
        long k = ((long) ix << 32) | (iz & 0xFFFFFFFFL);
        Double v = precipNodes.get(k);
        if (v != null) return v;
        double val = precipSampler.precipitationAt(ix * PRECIP_STEP_WU, iz * PRECIP_STEP_WU);
        precipNodes.put(k, val);
        return val;
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
