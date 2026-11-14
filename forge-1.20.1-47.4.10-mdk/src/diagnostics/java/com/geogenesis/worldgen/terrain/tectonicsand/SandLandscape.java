package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱：<b>地貌演化（Landscape Evolution Model）</b>——水流功率侵蚀 + 坡面扩散。
 *
 * <h2>为什么要有这个（而不是液滴侵蚀）</h2>
 * <p>只靠"构造抬升 + 噪声"的地形，俯视图上是一团光滑的"软泥巴"：构造只给出
 * <b>抬升量</b>，真实地貌是<b>抬升 × 侵蚀</b>竞争的结果。成熟流程都把侵蚀
 * 当核心一环（隐式 SPL 求解，或液滴法）。</p>
 *
 * <p>沙箱先试过液滴法（{@link SandErosion}）：<b>实测几乎无效</b>（平均相邻高差
 * ×0.95~0.96）。原因很实在——单次液滴把移除量摊到 7×7 笔刷上（稀释 49 倍），
 * 而 1M 格只跑 3M 液滴（每格 3 次通过），累积下切量不到高程标准差的 1%。
 * 要出效果得 100 倍液滴（约 30 分钟）。</p>
 *
 * <p>液滴法的另一个问题是：<b>随机、不可设计、不产生水系</b>。所以换成
 * 地貌学里标准的 LEM（GOSPL / Fastscape 同一族）：</p>
 * <pre>
 *   ∂h/∂t = U − K·A^m·S^n + D·∇²h
 *           抬升   水流功率侵蚀    坡面扩散（蠕动）
 * </pre>
 * <p>其中 {@code A} = 汇水面积（格数）、{@code S} = 局部坡度。这会产生<b>树枝状水系</b>
 * 和现实的山脊线——正是参考项目那种观感。</p>
 *
 * <h2>实现要点</h2>
 * <ol>
 *   <li><b>填洼</b>（Planchon–Darboux）：不填洼则封闭洼地不产流 ⇒ 洼地里没有河谷，
 *       留下光滑斑块。</li>
 *   <li><b>D8 汇流 + 计数排序</b>：按高度降序累加汇水面积，O(N)，确定性。</li>
 *   <li><b>抬升用"拉回构造面"的松弛</b>：{@code h += relax·(h₀ − h)}。
 *       等价于恒定抬升率 U（构造面 h₀ 由板块场给出），使系统趋于
 *       "侵蚀量 = 抬升量"的稳态 ⇒ 地形被切成沟谷但<b>不会整体削平</b>。</li>
 *   <li><b>归一化</b>：先按高程标准差归一化再演化，参数与 e 量级无关。</li>
 * </ol>
 *
 * <p>⚠ <b>本类只用于方案验收</b>。生产侵蚀是 {@code ErosionEngine} + 分 tile 的
 * {@code ParticleTile}（唯一生产路径），不接这个。</p>
 */
final class SandLandscape {

    /**
     * 演化参数（归一化单位）。
     *
     * @param k         侵蚀系数 K（越大下切越强）
     * @param m         面积指数（0.4~0.5 常见；越大主干河道越突出）
     * @param n         坡度指数（通常 1）
     * @param diffusion 坡面扩散系数 D（把陡崖磨成凸形坡）
     * @param relax     每步向构造面回拉的松弛比例（等价于抬升率）
     * @param eps       填洼时的微坡度（保证处处有下游）
     * @param aCrit    <b>河道启动阈值</b>（格）：汇水面积小于它的格只受扩散、不切沟
     * @param sedDeposit 沉积系数 0~1：上游来沙超过容量时，每格落袋的比例
     * @param diffusionKa 坡面扩散常数项（对应 GOSPL hillslopeKa）
     * @param diffusionKm 坡面扩散面积项系数（对应 GOSPL hillslopeKm；× 汇水面积）
     * @param diffusionCap 扩散率上限（显式格式稳定性要求 &lt; 0.25）
     */
    record Params(double k, double m, double n,
                  double diffusionKa, double diffusionKm, double diffusionCap,
                  double relax, double eps, double aCrit, double sedDeposit) {

        /**
         * 默认参数。
         *
         * <p>★ 2026-11-14 对照参考源码标定：GOSPL 的 `spl.d = 0.42` 就是面积指数
         * （GosplManager.py:63），故 {@code m} 取 0.42（此前 0.45）。
         * `hillslopeKa=0.02 / hillslopeKm=0.2` 是面积依赖扩散
         * ——沙箱换算成 Ka=0.04、Km=1.6e-4（A=500 时合计 0.12，即原标定总量）。
         * `sedDeposit` 对应 GOSPL 的 `sedK/sedKf/sedKw` 沉积机制——
         * 之前沙箱<b>只侵蚀不沉积</b>，山谷永远没有被沉积物回填的冲积平地。</p>
         *
         * <p>★ 两个关键搭配：</p>
         * <ul>
         *   <li>扩散必须与 {@code K} <b>同调</b>：太小 ⇒ 沟间距趋于 1 格
         *       ⇒ 图上出现"毛发状均匀平行细沟"（实测）；大到能抹平 1~2 格的
         *       微沟、抹不平大沟 ⇒ 沟间距自然拉开。</li>
         *   <li>{@code aCrit} 是<b>河道启动阈值</b>：没有它，光滑斜面上每格都在侵蚀
         *       ⇒ 平行水系（经典 D8 不稳定）。加阈值后只有汇水达到一定规模的格才切沟
         *       ⇒ 出现主干—支流的树枝状水系。</li>
         * </ul>
         */
        static Params defaults() {
            return new Params(0.06, 0.42, 1.0, 0.04, 1.6e-4, 0.12,
                    0.05, 1e-4, 30.0, 0.5);
        }
    }

    private SandLandscape() { }

    /**
     * 计算填洼面（= 每个洼地被填到溢出点后的"水面"）。
     *
     * <p>这是"湖/水位"的实现基础：{@code waterDepth = max(0, w − h)}。
     * GOSPL 的 {@code wfill/sfill} 也是同一用途（填洼后才能算流向）。</p>
     *
     * @param h   高度（e 单位，不会被修改）
     * @param eps 填洼微坡度（e 单位）
     */
    public static double[][] filledSurface(double[][] h, double eps) {
        int n = h.length;
        double[][] w = new double[n][n];
        fillSinks(h, w, n, eps);
        return w;
    }

    /**
     * 原地演化高度场。
     *
     * @param h        高度（e 单位），会被修改
     * @param iters    迭代步数（≈ 地质时间步）
     * @param seaLevelE 海平面（e），水下不侵蚀
     */
    /**
     * 无加权版（每格抬升率相同）。
     *
     * <p>⚠ 均匀抬升 ⇒ 山带与平原的下切深度一样 ⇒ 山带仍是"一条白带"。
     * 验收观感时请用 {@link #evolve(double[][], int, double, Params, double[][])}
     * 传入造山带加权。</p>
     */
    static void evolve(double[][] h, int iters, double seaLevelE, Params p) {
        evolve(h, iters, seaLevelE, p, null);
    }

    /**
     * 带<b>逐格抬升加权</b>的演化。
     *
     * @param upliftW 每格抬升权重 0~1（null = 全 1）。物理含义：构造抬升集中在造山带
     */
    static void evolve(double[][] h, int iters, double seaLevelE, Params p, double[][] upliftW) {
        int n = h.length;
        double std = std(h);
        if (std <= 1e-12) {
            return;
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] /= std;
            }
        }
        double sea = seaLevelE / std;

        // 构造面（演化前的原始地形）= 抬升的"目标面"。
        // 每步 h += relax·(h0 − h) 等价于恒定抬升率：侵蚀挖掉的被缓慢补回，
        // 系统趋于"侵蚀量 = 抬升量"的稳态 ⇒ 地形被切成沟谷但不会整体削平。
        double[][] h0 = new double[n][n];
        for (int j = 0; j < n; j++) {
            System.arraycopy(h[j], 0, h0[j], 0, n);
        }

        double[][] w = new double[n][n];
        double[][] acc = new double[n][n];
        int[] recv = new int[n * n];
        int[] order = new int[n * n];
        int[] bucket = new int[n * n];

        for (int t = 0; t < iters; t++) {
            // ---- 构造抬升：向构造面松弛（见上）----
            //   ★ 2026-11-14：抬升率可<b>按格加权</b>（upliftWeight，0~1）。
            //     物理上构造抬升集中在造山带 ⇒ 山带内抬升快、被深切；
            //     平原/洋底抬升慢 ⇒ 保持平缓。没有这个加权，整个图被均匀侵蚀，
            //     山带和平原的下切深度一样 ⇒ 山带仍是"一条白带"。
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double wt = upliftW == null ? 1.0 : upliftW[j][i];
                    h[j][i] += p.relax() * wt * (h0[j][i] - h[j][i]);
                }
            }
            fillSinks(h, w, n, p.eps());
            receivers(w, recv, n);
            sortDesc(w, order, bucket, n);
            accumulate(acc, recv, order, n);
            // ★ 坡面扩散放在汇水面积之后：GOSPL 的 diffusion 是 Ka + Km·A
            //   （GosplManager.py:66-67），扩散率随汇水面积增长 ⇒ 沟谷宽缓、山脊尖锐。
            hillslope(h, n, acc, sea, p);
            double[] load = new double[n * n];
            erode(h, w, acc, recv, order, n, sea, p, load);
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] *= std;
            }
        }
    }

    // ============================================================ 各步

    /**
     * 坡面扩散（显式 5 点拉普拉斯，仅陆上）。
     *
     * <p>★ 2026-11-14 对照 GOSPL（GosplManager.py:66-67）补上<b>面积依赖</b>：
     * {@code diffusion = hillslopeKa + hillslopeKm·A}，其中 A 是汇水面积。
     * 物理含义：搬运量随水流增大 ⇒ 沟谷（A 大）扩散快、被磨宽缓；
     * 山脊（A 小）扩散慢、保持尖锐。</p>
     *
     * <p>稳定性：显式扩散要求每格 D &lt; 0.25，故对 D 做上限钳制
     * （钳制值 = 本沙箱标定过的总扩散率 {@link Params#diffusionCap}）。</p>
     */
    private static void hillslope(double[][] h, int n, double[][] acc, double sea, Params p) {
        double[][] src = new double[n][n];
        for (int j = 0; j < n; j++) {
            System.arraycopy(h[j], 0, src[j], 0, n);
        }
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (src[j][i] <= sea) {
                    continue;
                }
                double d = Math.min(p.diffusionCap(),
                        p.diffusionKa() + p.diffusionKm() * acc[j][i]);
                double lap = src[j][i - 1] + src[j][i + 1] + src[j - 1][i] + src[j + 1][i]
                        - 4.0 * src[j][i];
                h[j][i] = src[j][i] + d * lap;
            }
        }
    }

    /**
     * <b>优先队列洪泛填洼</b>（priority-flood）：求"处处有下游"的等效面 {@code w ≥ h}。
     *
     * <p>做法：边界格入最小堆（键 = h），弹出最低者，把未访问邻居的
     * {@code w} 设为 {@code max(h, w_当前 + eps)} 并入堆 —— 单遍 O(N log N)，精确。</p>
     *
     * <p>⚠ 曾用 Planchon–Darboux（逐遍松弛 {@code w = max(h, min 邻居 w + eps)}）：
     * 信息每遍只传播一格，1000² 网格上跑 80 遍<b>远未收敛</b>，
     * 绝大多数格的 {@code w} 仍停在 {@code 1e18} ⇒ {@link #receivers} 找不到下游
     * ⇒ <b>侵蚀整段被静默跳过</b>（实测平均 |∇h| 变化 ×1.00）。</p>
     */
    private static void fillSinks(double[][] h, double[][] w, int n, double eps) {
        boolean[] done = new boolean[n * n];
        int[] heap = new int[n * n];
        int size = 0;

        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                boolean border = i == 0 || j == 0 || i == n - 1 || j == n - 1;
                if (border) {
                    w[j][i] = h[j][i];
                    done[j * n + i] = true;
                    heap[size] = j * n + i;
                    size = siftUp(heap, size, w);
                } else {
                    w[j][i] = Double.MAX_VALUE;
                }
            }
        }
        while (size > 0) {
            int c = heap[0];
            heap[0] = heap[--size];
            siftDown(heap, size, w);
            int cj = c / n, ci = c % n;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) {
                        continue;
                    }
                    int jj = cj + dj, ii = ci + di;
                    if (ii < 0 || jj < 0 || ii >= n || jj >= n) {
                        continue;
                    }
                    int k = jj * n + ii;
                    if (done[k]) {
                        continue;
                    }
                    done[k] = true;
                    w[jj][ii] = Math.max(h[jj][ii], w[cj][ci] + eps);
                    heap[size] = k;
                    size = siftUp(heap, size, w);
                }
            }
        }
    }

    /** 以 w 为键的最小堆 sift-up；返回新 size。 */
    private static int siftUp(int[] heap, int size, double[][] key) {
        int i = size;
        size++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (key[heap[p] / key.length][heap[p] % key.length] <= key[heap[i] / key.length][heap[i] % key.length]) {
                break;
            }
            int t = heap[p];
            heap[p] = heap[i];
            heap[i] = t;
            i = p;
        }
        return size;
    }

    /** 最小堆 sift-down。 */
    private static void siftDown(int[] heap, int size, double[][] key) {
        int i = 0;
        int n = key.length;
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            double vm = key[heap[m] / n][heap[m] % n];
            if (l < size && key[heap[l] / n][heap[l] % n] < vm) {
                m = l;
                vm = key[heap[m] / n][heap[m] % n];
            }
            if (r < size && key[heap[r] / n][heap[r] % n] < vm) {
                m = r;
            }
            if (m == i) {
                return;
            }
            int t = heap[i];
            heap[i] = heap[m];
            heap[m] = t;
            i = m;
        }
    }

    /** D8：每个格指向最陡下游（在填洼面 w 上）；无下游指自身。 */
    private static void receivers(double[][] w, int[] recv, int n) {
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int best = j * n + i;
                double bestDrop = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) {
                            continue;
                        }
                        int jj = j + dj, ii = i + di;
                        if (ii < 0 || jj < 0 || ii >= n || jj >= n) {
                            continue;
                        }
                        double drop = (w[j][i] - w[jj][ii]) / Math.hypot(di, dj);
                        if (drop > bestDrop) {
                            bestDrop = drop;
                            best = jj * n + ii;
                        }
                    }
                }
                recv[j * n + i] = best;
            }
        }
    }

    /** 按 w <b>降序</b>的计数排序（4096 桶，O(N)，确定性）。 */
    private static void sortDesc(double[][] w, int[] order, int[] bucket, int n) {
        int cells = n * n;
        final int bins = 4096;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double v = w[j][i];
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        double span = Math.max(1e-12, hi - lo);
        int[] cnt = new int[bins];
        for (int k = 0; k < cells; k++) {
            int b = (int) ((w[k / n][k % n] - lo) / span * (bins - 1));
            bucket[k] = b;
            cnt[b]++;
        }
        // 降序起点：桶 b 的起始位置 = 比它更高的桶的计数之和
        int[] start = new int[bins];
        int run = 0;
        for (int b = bins - 1; b >= 0; b--) {
            start[b] = run;
            run += cnt[b];
        }
        int[] cur = new int[bins];
        for (int k = 0; k < cells; k++) {
            int b = bucket[k];
            order[start[b] + cur[b]++] = k;
        }
    }

    /** 汇水面积（格数，初始 1），按降序累加给下游。 */
    private static void accumulate(double[][] acc, int[] recv, int[] order, int n) {
        int cells = n * n;
        for (int k = 0; k < cells; k++) {
            acc[k / n][k % n] = 1.0;
        }
        for (int k = 0; k < cells; k++) {
            int c = order[k];
            int r = recv[c];
            if (r != c) {
                acc[r / n][r % n] += acc[c / n][c % n];
            }
        }
    }

    /** 水流功率侵蚀：{@code E = K·A^m·S^n}，不得切到下游面以下。 */
    /**
     * 水流功率侵蚀 + <b>沉积搬运</b>（GOSPL 的 spl+sediment 简化实现）。
     *
     * <p>★ 2026-11-14 对照参考源码补上：此前只侵蚀不沉积——被挖掉的物质凭空消失，
     * 山谷永远不会有冲积平地。GOSPL 用 `sedK/sedKf/sedKw` 做搬运+沉积。</p>
     *
     * <p>实现（容量模型）：沿<b>下游方向</b>逐格处理，每格携带上游来沙 {@code load}：</p>
     * <pre>
     *   容量 cap = K·A^m·S^n
     *   load &lt; cap → 侵蚀 (cap − load)，并全部交给下游
     *   load ≥ cap → 沉积 (load − cap)·sedDeposit，其余交给下游
     * </pre>
     * <p>这样物质守恒：上游挖的会被下游坡度缓处落袋，形成冲积平地。
     * 必须按<b>高度升序</b>（= 下游方向）遍历，{@code order} 已按降序排好，反向取即可。</p>
     */
    private static void erode(double[][] h, double[][] w, double[][] acc, int[] recv,
                              int[] order, int n, double sea, Params p, double[] load) {
        int cells = n * n;
        for (int k = cells - 1; k >= 0; k--) {   // 升序 = 从上游到下游
            int c = order[k];
            int j = c / n, i = c % n;
            if (j <= 0 || i <= 0 || j >= n - 1 || i >= n - 1) {
                continue;
            }
            int r = recv[c];
            double in = load[c];

            if (h[j][i] <= sea) {
                // 水下：不侵蚀；上游来沙在此落袋，但<b>最多填到海平面</b>。
                //   ⚠ 不设上限的话，60 轮迭代会把整个洋盆填到海面以上
                //     （实测：整片洋被填成"陆地"色）。多余的沙随河流排入海洋消失
                //     ——这正是真实的物质收支：海是沉积的最终汇。
                double room = Math.max(0.0, sea - h[j][i]);
                double dep = Math.min(in * p.sedDeposit(), room);
                h[j][i] += dep;
                load[c] = 0;
                continue;
            }
            // 陆上：先算容量；沉积最多填到<b>溢出点</b>（填洼面 w），填满后随溢流走
            double capHere = 0;
            if (r != c) {
                int rj = r / n, ri = r % n;
                double dd = Math.hypot(ri - i, rj - j);
                double slope = Math.max(0.0, (w[j][i] - w[rj][ri]) / dd);
                double area = acc[j][i];
                if (area >= p.aCrit()) {
                    capHere = p.k() * Math.pow(area, p.m()) * Math.pow(slope, p.n());
                }
            }
            if (in < capHere) {
                double limit = (r == c) ? 0 : Math.max(0.0, h[j][i] - h[r / n][r % n]);
                double e = Math.min(capHere - in, limit);
                h[j][i] -= e;
                if (r != c) {
                    load[r] += e;   // 侵蚀量全部交给下游（简化：不随坡衰减）
                }
            } else {
                double room = Math.max(0.0, w[j][i] - h[j][i]);
                double dep = Math.min((in - capHere) * p.sedDeposit(), room);
                h[j][i] += dep;
                if (r != c) {
                    load[r] += in - capHere - dep;   // 填满后随溢流走
                }
            }
            load[c] = 0;
        }
    }

    private static double std(double[][] h) {
        int n = 0;
        double s = 0, s2 = 0;
        for (double[] row : h) {
            for (double v : row) {
                s += v;
                s2 += v * v;
                n++;
            }
        }
        double mean = s / Math.max(1, n);
        return Math.sqrt(Math.max(1e-24, s2 / Math.max(1, n) - mean * mean));
    }
}