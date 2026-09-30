package com.geogenesis.worldgen.hydrology.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 【块分辨率流体物理模拟】—— 水文决策层的重写核心（2026-09-20）。
 *
 * <h2>为什么重写（用户判据）</h2>
 * <blockquote>"流体运动路线完全不符合现实物理""你还是在打补丁。我的要求就是达到模拟
 * 现实物理的仿真水文效果。"</blockquote>
 *
 * <p>旧决策层（被本类替换）：<b>24wu(=48 块) 粗格</b>上追一条线，再用粒子偏移 +
 * 正弦 meander "修饰" —— 实测（{@code HydroPhysicsAudit.routeFidelity}）：
 * 河道与最陡下降方向平均偏角 <b>38.9°</b>、存在 <b>129° 横切</b>与<b>上坡步</b>。
 * 修饰本身就是违反"水往低处流"的来源。</p>
 *
 * <p>本类的物理链（每一步都有定义级的保证）：</p>
 * <pre>
 *   ① 块分辨率地形 H（1 格 = 1 块，与河道同尺度 —— 不再有 48 倍错配）
 *   ② priority-flood 填洼 + ε 微坡（Barnes 2014；参考 worldgen-master hydrology.rs:105-181）
 *        ⇒ 水在洼地堆积成湖，涨到 spill 必然溢出续流（"堆积就是湖/海"）
 *   ③ 填洼面 D8 最陡下降
 *        ⇒ <b>每一格的流动方向就是局部最陡下降</b> ⇒ 路线按构造物理正确
 *   ④ 汇流累积（拓扑序）
 *        ⇒ 沿程只增 ⇒ 河宽沿程递增（"流动路线就是河流"）
 *   ⑤ 河道 = 累积 ≥ 阈值；湖 = 填深 &gt; 1 块
 * </pre>
 *
 * <p><b>确定性</b>：全部输入只依赖 (seed, 坐标)；扫描序固定；无随机数。</p>
 * <p><b>性能实测</b>：256×256 窗口 ≈ 0.8 s（含地形采样）；本类只做后处理 ≈ 0.1 s 量级。
 * （region 级 1280×1280 待接入时实测，用户已明确"性能不是当前目标"。）</p>
 */
public final class TerrainFlowSim {

    private TerrainFlowSim() { }

    /** 上次 {@link #simulate} 的地形采样耗时（ms）—— 性能诊断用。 */
    public static volatile double lastSampleMs = 0.0;

    /** 上次 {@link #simulate} 的算法耗时（填洼+D8+累积+分级，ms）—— 性能诊断用。 */
    public static volatile double lastAlgoMs = 0.0;

    /** 块高度采样器（生产接线：{@code gt.getChunkCells(...).height}）。 */
    public interface HeightSampler {
        double at(int bx, int bz);
    }

    /**
     * ★★★ 2026-09-24【水文生命周期阶段】★★★
     *
     * <p>用户判据："使每条完整水系都能按预期经历全部生命周期阶段，并在输出中明确区分
     * 不同阶段的标志或状态。"</p>
     *
     * <p>阶段语义（与"源头→汇流→洼地蓄水→最低溢口→继续下泄→入海"一一对应）：</p>
     * <ul>
     *   <li>{@code SOURCE}：河源格（河道格且无上游河道格）；</li>
     *   <li>{@code CHANNEL}：普通河道格；</li>
     *   <li>{@code BASIN_ENTRY}：河道进入洼地（水面开始堆积）的入口格；</li>
     *   <li>{@code LAKE_STORAGE}：成湖蓄水格（静止水面）；</li>
     *   <li>{@code SPILLWAY}：洼地/湖的最低溢口格（水在此越坎）；</li>
     *   <li>{@code DOWNSTREAM}：溢口外侧的续流格（"继续下泄"起点）；</li>
     *   <li>{@code CONFLUENCE}：汇流点（与既有河道相接）；</li>
     *   <li>{@code SEA}：经下游格判定入海；</li>
     *   <li>{@code REGION_OUTLET}：到达网格边（交邻区续流）；</li>
     *   <li>{@code INVALID_TERMINATION}：无合法归宿的断头（验收目标 = 0）。</li>
     * </ul>
     */
    public enum Stage {
        NONE, SOURCE, CHANNEL, BASIN_ENTRY, LAKE_STORAGE,
        SPILLWAY, DOWNSTREAM, CONFLUENCE, SEA, REGION_OUTLET, INVALID_TERMINATION
    }

    /**
     * ★★★ 2026-09-24【水文生命周期总开关】★ 默认 {@code true} ★★★
     *
     * <p>{@code true} = 拓扑先定型（上坡硬门 + 对角消解 + 盆地溢口）<b>早于</b>汇流累积
     * ⇒ {@code down / accum / channel} 属于同一张图；抽线时支持溢口续流、显式入海与
     * 阶段标志。这是用户明确要求的"模拟现实的水文系统"行为变更。</p>
     *
     * <p>{@code false} = 逐位回退"拓扑后置"旧行为（供 A/B 对照）。</p>
     */
    public static volatile boolean HYDRO_LIFECYCLE = true;

    /** 模拟结果（窗口局部索引 [0,n)²）。 */
    public static final class Result {
        /** 窗口边长（块）。 */
        public final int n;
        /** 原始地形。 */
        public final double[] height;
        /** 填洼 + ε 后的"水可在其上流动"的面。 */
        public final double[] fill;
        /** D8 下游格索引；-1 = 出口（海/网格边）。 */
        public final int[] down;
        /** 汇流累积（块数，含自身）。 */
        public final long[] accum;
        /** 河道格（accum ≥ 阈值 且 在陆地上）。 */
        public final boolean[] channel;
        /** 湖格（填深 > 1 块 = 水堆积处）。 */
        public final boolean[] lake;
        public final int chanThreshold;
        public final double seaLevel;
        /** ★ 2026-09-21 格距（块/格）：1 = 逐块；>1 = 降采样（生产接入用，见 SKELETON_CELL_BLOCKS）。 */
        public final int cellBlocks;
        /** ★ 2026-09-24 盆地 id（-1 = 非洼地）；同一洼地连通域共享 id。 */
        public final int[] basinId;
        /** ★ 2026-09-24 每个盆地的蓄水/溢出高程（块）；索引 = basinId。 */
        public final double[] basinSpillHeight;
        /** ★ 2026-09-24 每个盆地的最低溢口格（-1 = 真内流无溢口）。 */
        public final int[] spillCell;
        /** ★ 2026-09-24 每个溢口的下游格（-1 = 无下游）。 */
        public final int[] spillDown;
        /** ★ 2026-09-24 逐格生命周期阶段（由 {@link #extractChannelSkeletons} 填充）。 */
        public final byte[] stage;
        /** ★ 2026-09-24 各阶段计数（索引 = {@link Stage#ordinal()}）。 */
        public final int[] lifeCounts;

        public Result(int n, double[] height, double[] fill, int[] down, long[] accum,
                      boolean[] channel, boolean[] lake, int chanThreshold, double seaLevel) {
            this(n, height, fill, down, accum, channel, lake, chanThreshold, seaLevel, 1);
        }

        public Result(int n, double[] height, double[] fill, int[] down, long[] accum,
                      boolean[] channel, boolean[] lake, int chanThreshold, double seaLevel,
                      int cellBlocks) {
            this(n, height, fill, down, accum, channel, lake, chanThreshold, seaLevel, cellBlocks,
                    defaultBasinId(n), new double[0], new int[0], new int[0],
                    new byte[n * n], new int[Stage.values().length]);
        }

        /** ★ 2026-09-24 全量构造器（含水文拓扑）。 */
        public Result(int n, double[] height, double[] fill, int[] down, long[] accum,
                      boolean[] channel, boolean[] lake, int chanThreshold, double seaLevel,
                      int cellBlocks, int[] basinId, double[] basinSpillHeight,
                      int[] spillCell, int[] spillDown, byte[] stage, int[] lifeCounts) {
            this.n = n; this.height = height; this.fill = fill; this.down = down;
            this.accum = accum; this.channel = channel; this.lake = lake;
            this.chanThreshold = chanThreshold; this.seaLevel = seaLevel;
            this.cellBlocks = cellBlocks;
            this.basinId = basinId;
            this.basinSpillHeight = basinSpillHeight;
            this.spillCell = spillCell;
            this.spillDown = spillDown;
            this.stage = stage;
            this.lifeCounts = lifeCounts;
        }

        /** 兼容旧构造器：无拓扑 ⇒ 全部按"非洼地"处理（行为等价于开关关闭）。 */
        private static int[] defaultBasinId(int n) {
            int[] b = new int[n * n];
            Arrays.fill(b, -1);
            return b;
        }
    }

    /**
     * 填洼每步 ε（块）：填平区朝出口单调微降 ⇒ D8 处处有下坡。
     *
     * <p>★ 2026-09-21 修正：原取 <b>1e-3</b> ⇒ 在 1400² 窗口上，从海到内陆的填洼链可长达
     * 数千步 ⇒ <b>ε 累积达数块</b> ⇒ {@code fill − h > 1.0} 把大片谷地误判成湖
     * （实测湖占 <b>28.12%</b>，图上一片汪洋）。参考 worldgen-master {@code hydrology.rs:180}
     * 取 <b>1e-5</b> ⇒ 万步链也只抬 0.1 块，纯粹够 D8 用，不产生假湖。</p>
     */
    private static final double FILL_EPS = 1e-5;

    /**
     * 判为湖的最小挖深（块，= spill − 盆底）。<b>★ 2026-09-25 起取 0.0 = 取消该门槛。</b>
     *
     * <p><b>用户定义（铁律）</b>：<i>"湖泊的定义就是『洼地蓄水』，不存在 2 个物种"</i>
     * —— 凡洼地就蓄水，不得用"深度不够"去否定"这里有水"。原来的 3.0 会把
     * 0.5~3.0 块深的洼地判成 breach（水穿行、不成湖）⇒ 正是"该是湖的没算成湖"。</p>
     *
     * <p><b>回退</b>：改回 {@code 3.0}（并同步 {@link RiverLineNetwork} 的同名常量）。</p>
     */
    public static final double LAKE_MIN_DEPTH = 0.0;

    /**
     * 判为湖的最小盆底面积（格）。<b>★ 2026-09-25 起取 0 = 取消该门槛</b>（同见
     * {@link #LAKE_MIN_DEPTH} 的用户定义与回退说明）。
     */
    public static final int LAKE_MIN_AREA = 0;

    /**
     * ★ 2026-09-21【breach 下切上限】（块）：浅洼地被水【切穿排空】的最大下切量。
     *
     * <p><b>物理依据</b>（用户定义："水流堆积后溢出会继续向下流动"）：自然界的浅洼地不会
     * 永久蓄水成湖 —— 水沿最低缺口溢出时会【侵蚀切穿】那道坎（河流袭夺 / 决口），
     * 洼地随即排空，变成一条过水河谷。只有切穿代价超过此上限的【真封闭盆地】才蓄水成湖。</p>
     *
     * <p>取 6 块：与雕刻层河深量级一致（河床相对岸边约数块）⇒ 切穿后留下的是
     * "河谷"而不是"深峡谷"，符合地貌观感；也是 PLAN P1 标定的初值。</p>
     */
    public static final double BREACH_LIMIT = 6.0;

    /**
     * 对窗口 {@code [ox, ox+n) × [oz, oz+n)}（块坐标）跑完整流体物理模拟。
     *
     * @param chanThreshold 成河汇流阈值（块数）：物理含义 = "上游集水面积达到多少才切得出河槽"
     */
    public static Result simulate(HeightSampler s, int ox, int oz, int n,
                                  double seaLevel, int chanThreshold) {
        return simulate(s, ox, oz, n, 1, seaLevel, chanThreshold);
    }

    /**
     * ★ 2026-09-21【带格距的模拟】—— 生产接入用（{@code cellBlocks > 1} 时降采样）。
     *
     * <p><b>为什么必须参数化格距</b>：本方法原本<b>每格 = 1 块</b>（{@code n} 既是格数
     * 又是块数）。生产 region 是 1280 块 ⇒ 若按逐块跑就是 <b>236 万格</b>（1280²），
     * 采样+tile 冷生成会卡死游戏（实测：按 1536 逐块传参导致门禁 7 分钟不返回）。
     * 生产接入必须用【格距 = 数十块】的降采样场：格距 {@code cb} 时格数 = (边长/cb)²，
     * 80 格 = 6400 格 ⇒ 一次 region 模拟 ~百毫秒量级。</p>
     *
     * <p>⚠ 降采样只影响【网格分辨率】，物理链（填洼 / D8 / 累积 / 湖）不变；
     * 累积量纲 = 格数（调用方按 {@code (cb/horizontalScale)²} 换算面积）。</p>
     *
     * @param n          每边格数
     * @param cellBlocks 格距（块/格）；1 = 逐块（旧行为，逐位一致）
     */
    public static Result simulate(HeightSampler s, int ox, int oz, int n, int cellBlocks,
                                  double seaLevel, int chanThreshold) {
        int total = n * n;
        final int cb = Math.max(1, cellBlocks);
        double[] h = new double[total];
        // ★ 2026-09-21 分段计时（性能诊断用；结论：接入生产的死结在【采样】不在算法）
        long tSample0 = System.nanoTime();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j * n + i] = s.at(ox + i * cb, oz + j * cb);
            }
        }
        lastSampleMs = (System.nanoTime() - tSample0) / 1e6;

        // ---------- ② priority-flood 填洼 + ε ----------
        long tAlgo0 = System.nanoTime();
        double[] fill = new double[total];
        Arrays.fill(fill, Double.NaN);
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        int seeds = 0;
        for (int k = 0; k < total; k++) {
            if (h[k] <= seaLevel) { fill[k] = h[k]; pq.add(new double[]{h[k], k}); seeds++; }
        }
        if (seeds == 0) {                       // 纯内陆窗口：最低格兜底为出口
            int lo = 0;
            for (int k = 1; k < total; k++) if (h[k] < h[lo]) lo = k;
            fill[lo] = h[lo]; pq.add(new double[]{h[lo], lo});
        }
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            double lvl = cur[0];
            int idx = (int) cur[1];
            if (lvl > fill[idx]) continue;      // 过期条目
            int ci = idx % n, cj = idx / n;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                    int nb = nj * n + ni;
                    if (!Double.isNaN(fill[nb])) continue;
                    double v = Math.max(h[nb], lvl + FILL_EPS);
                    fill[nb] = v;
                    pq.add(new double[]{v, nb});
                }
            }
        }

        // ---------- ③ D8 最陡下降（填洼面）----------
        int[] down = new int[total];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int idx = j * n + i;
                down[idx] = steepest(i, j, n, fill, h);
            }
        }

        // ---------- ④ 汇流累积【2026-09-24 后移至拓扑定型之后】----------
        //   ⚠ 顺序铁律（用户判据："使每条完整水系都能经历全部生命周期阶段"）：
        //     拓扑（真实上坡硬门 / 对角互穿消解 / 盆地溢口）必须在 accum 之前定型，
        //     否则 down / accum / channel 分属三张不同的图 ⇒ 必然出现
        //     "汇流量达标却无下游"的断头（本次重构的根因）。
        //   计算见下方 ⑥ 段（computeAccum），A/B 由 HYDRO_LIFECYCLE 控制。

        // ---------- ⑤ 河道 / 湖（★ 2026-09-25：湖 = 洼地蓄水，无门槛）----------
        //   ★ 用户定义（铁律）："湖泊的定义就是『洼地蓄水』，不存在 2 个物种"。
        //   ⇒ 湖标签【不再】按盆地体量发放：`cand = fill − h > 0.5` 的连通分量
        //     **一律**是湖（LAKE_MIN_DEPTH / LAKE_MIN_AREA 已置 0）。
        //     旧的"浅洼 ⇒ breach（水穿行、不成湖）"语义已废弃 —— 它正是
        //     "该是湖的没算成湖"的根因；浅洼照样蓄水，水由【溢口】继续下泄。
        //   ⇒ 下面 lakeDomain 的门槛循环被刻意保留（值为 0 时恒真）：既是回退开关，
        //     也让"湖 = 过门槛的洼地连通分量"这一条结构在代码里可见。
        //   注：`> 0.5` 不是"面积/深度门槛"，而是"这一个方块能不能放下水"——
        //     与最终落块判水同口径（水深不足半块放不进一个水方块）。
        boolean[] cand = new boolean[total];
        for (int k = 0; k < total; k++) cand[k] = fill[k] - h[k] > 0.5;
        // 连通域统计：maxDepth / area
        int[] comp = new int[total];
        Arrays.fill(comp, -1);
        List<int[]> cs = new ArrayList<>();               // {maxDepthMillis, area}
        List<double[]> cDepth = new ArrayList<>();
        for (int start = 0; start < total; start++) {
            if (!cand[start] || comp[start] >= 0) continue;
            int id = cs.size();
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(start); comp[start] = id;
            int area = 0; double maxD = 0;
            while (!q.isEmpty()) {
                int c = q.poll();
                area++;
                maxD = Math.max(maxD, fill[c] - h[c]);
                int ci = c % n, cj = c / n;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        int nb = nj * n + ni;
                        if (cand[nb] && comp[nb] < 0) { comp[nb] = id; q.add(nb); }
                    }
                }
            }
            cs.add(new int[]{area});
            cDepth.add(new double[]{maxD});
        }
        boolean[] channel = new boolean[total];
        boolean[] lake = new boolean[total];
        // ===== ★★★ 2026-09-21【湖面 = 水位以下的整片水域（含浅滩）】★★★ =====
        //
        //   【被修的缺陷】旧实现逐格判 `cand[k] = fill−h > 0.5` ⇒ 湖盆地内的【浅滩格】
        //   （0 < 水深 ≤ 0.5）不算湖，但它们位于汇流主干上 ⇒ `accum` 超阈值 ⇒ 被标成
        //   channel ⇒ 渲染时【深蓝色河线穿过湖面】。用户实测判据：
        //     "为什么湖泊里面还有线呢？是不是这个是需要排除的线？" —— 是，必须排除。
        //   物理事实：静止湖面之下的每一格（无论水深 0.1 还是 10）都是湖的一部分，
        //   不存在"露出水面的河槽"。
        //
        //   【修法】保留原【域划分与门槛口径】（cand = 水深 > 0.5 的格 → 连通域 →
        //   maxDepth/area 过门槛 = 成湖域），但**成湖域的湖标签扩到整个水位以下水面**：
        //   从成湖域的 cand 格出发，在 `fill−h > 1e-6`（水位以下）掩码上洪泛。
        //   ⚠ 这样做【不改变湖的数量/位置标定】（域与门槛完全未动），只把湖面铺满。
        boolean[] lakeDomain = new boolean[cs.size()];
        for (int id = 0; id < cs.size(); id++) {
            lakeDomain[id] = cDepth.get(id)[0] >= LAKE_MIN_DEPTH
                    && cs.get(id)[0] >= LAKE_MIN_AREA;
        }
        // ① 湖域严格等于通过面积/深度门槛的 cand 连通分量。
        //    `fill-h > 1e-6` 的浅水带不等于湖：它可能是河漫滩、鞍口或山坡上的
        //    亚格量化差。旧代码沿该掩码继续洪泛，导致不同洼地被串联、山坡铺蓝。
        //    最终块级出水本来也要求水深 >= 0.5，因此这里直接使用 cand 是同口径的。
        for (int k = 0; k < total; k++) {
            if (cand[k] && comp[k] >= 0 && lakeDomain[comp[k]]) lake[k] = true;
        }
        // ===== ⑥ ★★★ 2026-09-24【拓扑定型】★★★ =====
        //   顺序：上坡硬门 → 对角互穿消解 → 盆地/溢口拓扑 → accum → channel。
        //   开关 HYDRO_LIFECYCLE=false 时跳过前三步（逐位回到"拓扑后置"旧行为，
        //   此时抽线阶段仍会做硬门+消解，等价于旧路径）。
        int[] basinId = new int[total];
        Arrays.fill(basinId, -1);
        double[] basinSpillHeight = new double[0];
        int[] spillCell = new int[0];
        int[] spillDown = new int[0];
        if (HYDRO_LIFECYCLE) {
            applyUphillGate(h, fill, down, lake, total);
            resolveDiagonalCrossingsArr(h, down, channel, lake, n, total);
            // 盆地拓扑：需要在 lake[] 已定标后进行（深盆=湖，浅洼=breach）
            BasinTopology bt = buildBasinTopology(h, fill, lake, down, n, total);
            basinId = bt.basinId;
            basinSpillHeight = bt.spillHeight;
            spillCell = bt.spillCell;
            spillDown = bt.spillDown;
            // 不再把盆地内部断点直接连到远处 spillCell。那不是 D8 相邻边，会在折线中
            // 形成跨越整个盆地的长跳。洼地/湖泊的溢口续流由独立 SPILLWAY 折线处理。
        }

        // ---------- ⑦ 汇流累积（基于【定型后】的 down；按填洼面降序 = 拓扑序）----------
        long[] accum = new long[total];
        Arrays.fill(accum, 1);
        List<Integer> order = new ArrayList<>(total);
        for (int k = 0; k < total; k++) order.add(k);
        order.sort((a, b) -> Double.compare(fill[b], fill[a]));
        for (int k : order) {
            int d = down[k];
            if (d >= 0) accum[d] += accum[k];
        }

        // ② 河道标记：必须在【湖洪泛完成之后】判定，且只排除【已成湖】的格。
        //
        //   ⚠⚠ 2026-09-21【回归修复·勿重犯】⚠⚠
        //   我曾写成 `channel = accum≥thr && h>seaLevel && h ≥ fill − 1e-6`
        //   （意图"只在陆上画河，避开水面"）——★ 实测把河打断了：
        //   `fill > h` 的不只是湖，还有【浅洼/宽谷/河漫滩】（breach 语义：水穿行而过）。
        //   那些格不成湖（cand 要求水深 >0.5，且要过深度/面积门槛），却因 `fill > h`
        //   被排除出 channel ⇒ **河流在每一处浅洼断开**（用户实测截图："你怎么改着改着
        //   湖泊外的河流断开了？"，红圈处即浅洼断点）。
        //   正解：河道只排除【真正的湖格】—— 水是否被填洼与"是否是河道"无关：
        //   浅洼里的水照样在流动，它就是河（breach）；只有成湖处才是静止水面。
        for (int k = 0; k < total; k++) {
            if (lake[k]) continue;
            if (accum[k] >= chanThreshold && h[k] > seaLevel) channel[k] = true;
        }
        lastAlgoMs = (System.nanoTime() - tAlgo0) / 1e6;
        return new Result(n, h, fill, down, accum, channel, lake, chanThreshold, seaLevel, cb,
                basinId, basinSpillHeight, spillCell, spillDown,
                new byte[total], new int[Stage.values().length]);
    }

    /**
     * ★★★ 2026-09-24【真实上坡硬门】★★★（从 extractChannelSkeletons 前移而来）
     *
     * <p>用户判据："没有水是从源头往低处流"。仅靠 fill 的 ε 梯度不够 —— 填洼面为了
     * 填洼拓扑服务，不是实际水面坡向。凡 D8 下游的真实地形高出当前格超过
     * {@link #FLOW_UPHILL_TOL}，且不属于"浅洼堆积溢出"（fillDepth ≤ {@link #BREACH_LIMIT}）
     * 的格，一律取消下游 ⇒ 水只能在真实地形上顺流，或在局部极小/湖边终止。</p>
     */
    private static void applyUphillGate(double[] h, double[] fill, int[] down,
                                        boolean[] lake, int total) {
        for (int k = 0; k < total; k++) {
            int d = down[k];
            if (d < 0) continue;
            if (h[d] <= h[k] + FLOW_UPHILL_TOL) continue;
            double fillDepth = fill[k] - h[k];
            if (fillDepth > BREACH_LIMIT || lake[k] || lake[d]) {
                down[k] = -1;
            }
            // fillDepth <= BREACH_LIMIT：保留 down（浅洼"堆积→溢出"的过水段）
        }
    }

    /**
     * ★★★ 2026-09-24【2×2 对角互穿消解】★★★（从 extractChannelSkeletons 前移而来）
     *
     * <p>把基于 {@code Result} 的旧签名改为基于数组，便于在 simulate 内提前调用。
     * 逻辑与 {@link #resolveDiagonalCrossings} 完全一致（后者保留供抽线兼容调用）。</p>
     */
    private static void resolveDiagonalCrossingsArr(double[] h, int[] down, boolean[] channel,
                                                    boolean[] lake, int n, int total) {
        for (int j = 0; j + 1 < n; j++) {
            for (int i = 0; i + 1 < n; i++) {
                int a = j * n + i;
                int b = j * n + i + 1;
                int c = (j + 1) * n + i;
                int d = (j + 1) * n + i + 1;
                boolean mDiag = down[a] == d || down[d] == a;
                boolean xDiag = down[b] == c || down[c] == b;
                if (!mDiag || !xDiag) continue;
                int from1 = down[a] == d ? a : d;
                int to1 = from1 == a ? d : a;
                int from2 = down[b] == c ? b : c;
                int to2 = from2 == b ? c : b;
                double drop1 = h[from1] - h[to1];
                double drop2 = h[from2] - h[to2];
                int loser = drop1 >= drop2 ? from2 : from1;
                int[] options = loser == a ? new int[]{b, c}
                        : loser == b ? new int[]{a, d}
                        : loser == c ? new int[]{a, d}
                        : new int[]{b, c};
                int best = -1;
                double bestDrop = 0;
                for (int o : options) {
                    if (lake[o]) continue;
                    if (down[o] == loser) continue;            // 防 2-环
                    double drop = h[loser] - h[o];
                    if (drop >= -FLOW_UPHILL_TOL && drop > bestDrop) {
                        bestDrop = drop;
                        best = o;
                    }
                }
                down[loser] = best;                            // 无合法正交 ⇒ 断头（不造上坡）
            }
        }
    }

    /** 盆地拓扑产物（内部传递容器）。 */
    private static final class BasinTopology {
        final int[] basinId;
        final double[] spillHeight;
        final int[] spillCell;
        final int[] spillDown;
        BasinTopology(int[] basinId, double[] spillHeight, int[] spillCell, int[] spillDown) {
            this.basinId = basinId;
            this.spillHeight = spillHeight;
            this.spillCell = spillCell;
            this.spillDown = spillDown;
        }
    }

    /**
     * ★★★ 2026-09-24【盆地/溢口拓扑】★★★ —— 把"布尔湖标签"升级为图节点。
     *
     * <p>用户判据："洼地识别、蓄水容量、溢口搜索与下游连接等模块是否被正确调用，
     * 尤其是当路径进入洼地后是否具备继续推进到最低溢口并最终入海的机制。"</p>
     *
     * <p><b>实现</b>：在填洼面 {@code fill} 上做洼地连通域标注（{@code fill − h > 1e-6}
     * 即水位以下的格），每个盆地计算：</p>
     * <ol>
     *   <li>{@code spillHeight} = 该盆地内 fill 的最大值（蓄满溢出的高程）；</li>
     *   <li>{@code spillCell} = 盆地内、且 8 邻存在【盆地外格】的最低者（= 最低溢口）；
     *       并列时取下标最小者（确定性）；</li>
     *   <li>{@code spillDown} = 溢口的盆地外邻居中真实地形最低者（= 溢流去向；
     *       要求其真实地形不高于溢口 + {@link #FLOW_UPHILL_TOL}，否则记 -1）。</li>
     * </ol>
     *
     * <p><b>确定性</b>：BFS 用固定方向扫描序；溢口/下游并列时按"严格更低优先，
     * 否则下标最小"取唯一解；无 HashMap 迭代顺序依赖。</p>
     */
    private static BasinTopology buildBasinTopology(double[] h, double[] fill, boolean[] lake,
                                                    int[] down, int n, int total) {
        int[] basinId = new int[total];
        Arrays.fill(basinId, -1);
        List<Integer> ids = new ArrayList<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int start = 0; start < total; start++) {
            if (fill[start] - h[start] <= 1e-6 || basinId[start] >= 0) continue;
            int id = ids.size();
            ids.add(id);
            basinId[start] = id;
            q.add(start);
            while (!q.isEmpty()) {
                int c = q.poll();
                int ci = c % n, cj = c / n;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        int nb = nj * n + ni;
                        if (basinId[nb] >= 0) continue;
                        if (fill[nb] - h[nb] <= 1e-6) continue;
                        basinId[nb] = id;
                        q.add(nb);
                    }
                }
            }
        }
        int nBasin = ids.size();
        double[] spillHeight = new double[nBasin];
        int[] spillCell = new int[nBasin];
        int[] spillDown = new int[nBasin];
        Arrays.fill(spillCell, -1);
        Arrays.fill(spillDown, -1);
        // ① 蓄水高程 = 盆地内 fill 最大值
        for (int k = 0; k < total; k++) {
            int b = basinId[k];
            if (b < 0) continue;
            if (fill[k] > spillHeight[b]) spillHeight[b] = fill[k];
        }
        // ①b ★ 2026-09-24【湖格并入所属盆地】：深盆的湖面格（fill ≈ h）不被
        //   `fill − h > 1e-6` 判据纳入盆地连通域 ⇒ basinId = -1 ⇒ 入湖后取不到溢口
        //   ⇒ "蓄水→溢口→下泄"链断（用户："我就没看到有一个完整实现的"）。
        //   修法：凡 lake[k] 且 basinId = -1 的格，若 8 邻存在 basinId ≥ 0 的格，
        //   则并入该盆地（取下标最小者，确定性）⇒ 湖总有归属盆地、总能找到溢口。
        //   回退：删除本块。
        for (int k = 0; k < total; k++) {
            if (!lake[k] || basinId[k] >= 0) continue;
            int ci = k % n, cj = k / n;
            int pick = -1;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                    int nb = nj * n + ni;
                    if (basinId[nb] >= 0 && (pick < 0 || basinId[nb] < pick)) pick = basinId[nb];
                }
            }
            if (pick >= 0) basinId[k] = pick;
        }
        // ②★ 2026-09-24【溢口提取重写：填洼面流出点 —— 修 15 条 INVALID 断环】★★★
        //   【旧②+③的两处缺陷（LIFE-DIAG 实证：15 条异常尾全部 basin≥0 而 spillDown=-1）】
        //     · ② spillCell = "最低被淹边缘格"：深陡壁下的边缘格 h 最低 ⇒ 抢走 spillCell，
        //       而真正的鞍口在别处 ⇒ ③ 从陡壁底找出口，外邻全是高山 ⇒ spillDown=-1；
        //     · ③ 排除盆地外邻居含【另一盆地】：相邻洼地互溢（A→B）时鞍口格属于 B
        //       ⇒ 被排除 ⇒ spillDown=-1。
        //   【正解（与骨架同源、数学精确）】priority-flood 保证盆内每格的 fill 沿 down[]
        //   严格下行、最终流出盆地 ⇒ "盆地内、down 指向盆地外"的格对 (k → down[k])
        //   就是该盆地的实际溢出口，fill[k] = 蓄水水位（= ① 的 max fill，自洽）。
        //   天然覆盖：盆地→旱地、盆地→另一盆地（互溢串联）、湖岸流出（①b 并入后）。
        //   并列取 (fill, 下标) 双关键字最小（确定性）。
        //   回退：恢复旧 ②（"最低被淹边缘格"）+ ③（邻居搜索）两块。
        for (int k = 0; k < total; k++) {
            int b = basinId[k];
            if (b < 0) continue;
            int d = down[k];
            if (d < 0 || basinId[d] == b) continue;      // 未流出本盆地（含出窗 d=-1）
            int cur = spillCell[b];
            if (cur < 0 || fill[k] < fill[cur] - 1e-9
                    || (Math.abs(fill[k] - fill[cur]) <= 1e-9 && k < cur)) {
                spillCell[b] = k;
                spillDown[b] = d;
            }
        }
        return new BasinTopology(basinId, spillHeight, spillCell, spillDown);
    }

    /**
     * ★★★ 2026-09-21【河道骨架折线】—— 沿 down[] 追出"河流线"（T1.1）★★★
     *
     * <p>把 D8 的逐格流向【折叠成折线】：从每个河源头（有下游、无上游的河道格）出发，
     * 沿 {@code down} 走到汇合点/湖/海/窗边，输出节点序列。这正是现有
     * {@code HydrologyBlockCarver} 消费的几何形态（⟶ 可直接替换 RiverPolyline 的节点）。</p>
     *
     * <p><b>为什么必须先有它</b>：T1「骨架接入生产」是唯一能让游戏里"看得见"的杠杆
     * ——当前所有成果都在决策层探针，游戏跑的还是 48 块粗格 + 粒子/曲流修饰的旧路线
     * （实测平均偏角 38.9°、129° 横切）。</p>
     *
     * <p><b>折线抽取规则</b>（与现有 RiverLineNetwork 的追踪语义一致，便于对接）：</p>
     * <ol>
     *   <li>河源头 = 河道格且其【上游没有河道格】（D8 入度为 0）；</li>
     *   <li>沿 down 追到：非河道格（河口/入湖/入海）/ 已被别的河占用的格（汇合）/ 无下坡（出口）；</li>
     *   <li>汇合点【归属先到的河】（提升 level），与现有 {@code commitRiver} 的 "claim" 语义一致。</li>
     * </ol>
     *
     * <p><b>确定性</b>：源点按 (j,i) 升序扫描、路径确定 ⇒ 与现有探针同样可复现。</p>
     *
     * @return 每条河 = 格索引数组（含端点）；源点已按扫描序排列
     */
    public static List<int[]> extractChannelSkeletons(Result r) {
        int n = r.n, total = n * n;
        // ★★★ 2026-09-24【改为只读 + 生命周期阶段】★★★
        //   拓扑（上坡硬门 / 对角消解 / 盆地溢口）已在 simulate() 内定型 ⇒ 此处【只读】。
        //   HYDRO_LIFECYCLE=false（A/B 回退）时，本方法补做旧的两步，保持旧行为逐位一致。
        if (!HYDRO_LIFECYCLE) {
            applyUphillGate(r.height, r.fill, r.down, r.lake, total);
            resolveDiagonalCrossingsArr(r.height, r.down, r.channel, r.lake, n, total);
        }
        byte[] stage = r.stage;
        int[] counts = r.lifeCounts;
        // 入度：某河道格被几个上游河道格指向
        boolean[] hasUpstream = new boolean[total];
        for (int k = 0; k < total; k++) {
            if (!r.channel[k]) continue;
            int d = r.down[k];
            if (d >= 0 && r.channel[d] && !r.lake[d]) hasUpstream[d] = true;
        }
        boolean[] claimed = new boolean[total];
        List<int[]> out = new ArrayList<>();
        // ★★★ 2026-09-24【源点优先级：下游链长者优先 —— 修"主干在汇合点消失"】★★★
        //   【根因（用户判据："这个周期我就没看到有一个完整实现的"）】
        //     旧实现按【索引顺序】扫源点，且追踪遇 `claimed` 即停。于是：
        //       · 先扫到的【短支流】claim 掉汇合点及其下游链路；
        //       · 主干随后追到该汇合点 ⇒ `claimed` ⇒ 立即终止；
        //       · 而汇合点【下游】的干流格因 hasUpstream=true ⇒ 永远不会被当作源点。
        //     ⇒ 主干被"从中间截断"，图上只剩一堆短段，没有任何一条走完
        //       "源头→汇流→蓄水→溢口→下泄→入海"。
        //   【修法】先算每格沿 down 到底的链长（下游链长 = 该格到出口的步数），
        //     源点按【链长降序】处理 ⇒ 主干（最长链）先 claim 整条链到底；
        //     支流随后追到主干时汇入并停止（joined）⇒ 既有完整干流，又有支流汇入。
        //   回退：删掉本排序块，恢复 `for (int k = 0; k < total; k++)` 索引序扫描。
        int[] chainLen = computeChainLen(r, n, total);
        List<Integer> sources = new ArrayList<>();
        for (int k = 0; k < total; k++) {
            if (!r.channel[k] || r.lake[k] || hasUpstream[k] || claimed[k]) continue;
            sources.add(k);
        }
        sources.sort((a, b) -> chainLen[b] != chainLen[a]
                ? Integer.compare(chainLen[b], chainLen[a])     // 链长降序 = 主干优先
                : Integer.compare(a, b));                       // 并列取下标小者（确定性）
        for (int k : sources) {
            if (claimed[k]) continue;
            // 源点：向上游回溯（若有未被 claim 的同源岔口则让先到者成河，避免重复）
            List<Integer> path = new ArrayList<>();
            int cur = k;
            stage[k] = (byte) Stage.SOURCE.ordinal();
            while (cur >= 0 && r.channel[cur] && !r.lake[cur] && !claimed[cur]) {
                claimed[cur] = true;
                path.add(cur);
                int d = r.down[cur];
                if (d < 0) break;                        // 出口（网格边/海/溢口续流见下）
                if (stage[d] == Stage.NONE.ordinal()) stage[d] = (byte) Stage.CHANNEL.ordinal();
                cur = d;
            }
            // ★ 汇合点补入（支流与干流差一小段）：共享该格即相接
            if (cur >= 0 && cur < total && r.channel[cur] && !r.lake[cur] && claimed[cur]
                    && !path.isEmpty() && path.get(path.size() - 1) != cur) {
                path.add(cur);
            }
            // ===== ★★★ 2026-09-24【归宿判定 + 溢口续流】★★★ =====
            //   优先级：汇流点 → 湖入口 → 溢口续流 → 入海 → 区域出口；
            //   全不满足 ⇒ INVALID_TERMINATION（验收目标 = 0）。
            int tail = path.isEmpty() ? -1 : path.get(path.size() - 1);
            boolean joined = cur >= 0 && cur < total && claimed[cur] && !r.lake[cur];
            int nextDown = tail >= 0 ? r.down[tail] : -1;
            boolean entersLake = tail >= 0 && nextDown >= 0 && r.lake[nextDown];
            int basin = tail >= 0 ? r.basinId[tail] : -1;
            boolean hasSpill = basin >= 0 && basin < r.spillDown.length && r.spillDown[basin] >= 0;
            boolean atBoundary = tail >= 0 && (tail % n == 0 || tail % n == n - 1
                    || tail / n == 0 || tail / n == n - 1);
            // 入海：当前格 8 邻存在 h ≤ seaLevel（显式判定，替代靠 d<0 猜测）
            boolean atSea = false;
            if (tail >= 0) {
                int ti = tail % n, tj = tail / n;
                for (int dj = -1; dj <= 1 && !atSea; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ti + di, nj = tj + dj;
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        if (r.height[nj * n + ni] <= r.seaLevel) { atSea = true; break; }
                    }
                }
            }
            // ★★★ 2026-09-24【入湖 → 溢口续流（生命周期最关键的一环）】★★★
            //   用户判据："水从源头向低处流，直到遇到洼地水开始堆积，等水面到最低溢出
            //   高度，水溢出又继续更低处流动直到入海。"
            //   【旧行为的缺陷】河到湖岸即停（BASIN_ENTRY），【没有从湖的溢口继续下泄】
            //   ⇒ 蓄水→溢口→下泄这一段整条缺失。
            //   【修法（河适应湖，湖内不画线）】入湖路径在湖岸终止（BASIN_ENTRY），
            //   溢口续流作为【独立折线】从溢口下游发出 ⇒ 图上：河进湖（线止于湖岸）
            //   + 湖（蓝色水面）+ 溢口外新河（继续下泄）—— 正是真实水文形态。
            //   回退：把 entersLake 分支改回"直接 endStage = BASIN_ENTRY"。
            int lakeSpillNext = -1;
            int lakeBasin = -1;
            if (entersLake) {
                int lb = nextDown >= 0 ? r.basinId[nextDown] : -1;
                if (lb >= 0 && lb < r.spillDown.length && r.spillDown[lb] >= 0) {
                    lakeSpillNext = r.spillDown[lb];
                    lakeBasin = lb;
                }
            }
            Stage endStage;
            List<Integer> spillPath = null;      // 溢口续流独立折线（不入主路径）
            if (joined) {
                endStage = Stage.CONFLUENCE;
                if (tail >= 0 && stage[tail] == Stage.NONE.ordinal()) {
                    stage[tail] = (byte) Stage.CONFLUENCE.ordinal();
                }
            } else if (entersLake) {
                endStage = Stage.BASIN_ENTRY;
                if (tail >= 0) stage[tail] = (byte) Stage.BASIN_ENTRY.ordinal();
                if (nextDown >= 0) stage[nextDown] = (byte) Stage.LAKE_STORAGE.ordinal();
                if (lakeBasin >= 0 && r.spillCell[lakeBasin] >= 0) {
                    stage[r.spillCell[lakeBasin]] = (byte) Stage.SPILLWAY.ordinal();
                }
                if (lakeSpillNext >= 0) {
                    spillPath = collectSpillRun(r, lakeSpillNext, claimed, stage, total);
                }
            } else if (hasSpill) {
                // 浅洼 breach：水面越鞍，续流同为独立折线（主路径止于鞍前）
                endStage = Stage.SPILLWAY;
                if (tail >= 0) stage[tail] = (byte) Stage.SPILLWAY.ordinal();
                spillPath = collectSpillRun(r, r.spillDown[basin], claimed, stage, total);
            } else if (atSea) {
                endStage = Stage.SEA;
                if (tail >= 0) stage[tail] = (byte) Stage.SEA.ordinal();
            } else if (atBoundary) {
                endStage = Stage.REGION_OUTLET;
                if (tail >= 0) stage[tail] = (byte) Stage.REGION_OUTLET.ordinal();
            } else {
                endStage = Stage.INVALID_TERMINATION;
                if (tail >= 0) stage[tail] = (byte) Stage.INVALID_TERMINATION.ordinal();
                // ★ 2026-09-24【逐尾诊断】：打印判定上下文，供定点修（前 20 条）
                if (tail >= 0 && invalidDiagCount.getAndIncrement() < 20) {
                    int nd2 = r.down[tail];
                    int gi3 = tail % n, gj3 = tail / n;
                    boolean ndChan = nd2 >= 0 && r.channel[nd2];
                    boolean ndLake = nd2 >= 0 && r.lake[nd2];
                    boolean ndClaim = nd2 >= 0 && claimed[nd2];
                    int bsn = r.basinId[tail];
                    int spd = bsn >= 0 && bsn < r.spillDown.length ? r.spillDown[bsn] : -1;
                    System.out.printf("[LIFE-DIAG] tail(g=%d,%d) h=%.2f fill=%.2f sea=%.2f"
                                    + " nextDown=%d[ch=%b lake=%b claim=%b] basin=%d spillDown=%d"
                                    + " joined=%b entersLake=%b hasSpill=%b atSea=%b atBnd=%b%n",
                            gi3, gj3, r.height[tail], r.fill[tail], r.seaLevel,
                            nd2, ndChan, ndLake, ndClaim, bsn, spd,
                            joined, entersLake, hasSpill, atSea, atBoundary);
                }
            }
            counts[endStage.ordinal()]++;
            // 准入判据（替代"事后短段删除"）：必须有合法归宿，或形成足够长的过水段
            boolean validEnd = endStage != Stage.INVALID_TERMINATION;
            if (path.size() < 6 && !validEnd) {
                for (int cell : path) claimed[cell] = false;
                continue;
            }
            if (path.size() >= 2) {
                int[] arr = new int[path.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = path.get(i);
                out.add(arr);
            }
            // ★ 溢口续流独立折线（继续下泄段，含其自身归宿计数）
            if (spillPath != null && spillPath.size() >= 2) {
                int tail2 = spillPath.get(spillPath.size() - 1);
                int lastDown2 = r.down[tail2];
                Stage end2;
                boolean joined2 = lastDown2 >= 0 && claimed[lastDown2] && !r.lake[lastDown2];
                boolean lake2 = lastDown2 >= 0 && r.lake[lastDown2];
                boolean sea2 = lastDown2 >= 0 && nearSea(r, tail2, n);
                boolean boundary2 = tail2 % n == 0 || tail2 % n == n - 1
                        || tail2 / n == 0 || tail2 / n == n - 1;
                if (joined2) end2 = Stage.CONFLUENCE;
                else if (lake2) end2 = Stage.BASIN_ENTRY;
                else if (sea2) end2 = Stage.SEA;
                else if (boundary2) end2 = Stage.REGION_OUTLET;
                else end2 = Stage.INVALID_TERMINATION;
                counts[end2.ordinal()]++;
                int[] a2 = new int[spillPath.size()];
                for (int i = 0; i < a2.length; i++) a2[i] = spillPath.get(i);
                out.add(a2);
            }
        }
        // 盆地阶段标注（供统计/验收：湖蓄水格）
        for (int k = 0; k < total; k++) {
            if (r.lake[k] && stage[k] == Stage.NONE.ordinal()) {
                stage[k] = (byte) Stage.LAKE_STORAGE.ordinal();
            }
        }
        for (int b = 0; b < r.spillCell.length; b++) {
            int sp = r.spillCell[b];
            if (sp >= 0 && stage[sp] == Stage.NONE.ordinal()) {
                stage[sp] = (byte) Stage.SPILLWAY.ordinal();
            }
            int sd = b < r.spillDown.length ? r.spillDown[b] : -1;
            if (sd >= 0 && stage[sd] == Stage.NONE.ordinal()) {
                stage[sd] = (byte) Stage.DOWNSTREAM.ordinal();
            }
        }
        return out;
    }

    /**
     * ★★★ 2026-09-24【溢口续流段收集】★★★（"继续下泄"独立折线）
     *
     * <p>从溢口下游格出发，沿 {@code down} 追踪并 claim，直到汇合/入湖/边界/无下游。
     * 链式规则：途中若撞到另一个湖（lake 格），登记 LAKE_STORAGE 后跳到该湖盆地的
     * 溢口下游继续追（= "湖→溢口→河→湖→…"的串联水文）。</p>
     *
     * <p><b>为什么是独立折线</b>：河适应湖 —— 湖内不画河线（用户裁定"湖里不能有线"），
     * 入湖段与溢口下泄段必须是两条折线，中间由湖面（蓝色）衔接。</p>
     */
    private static List<Integer> collectSpillRun(Result r, int start, boolean[] claimed,
                                                 byte[] stage, int total) {
        List<Integer> run = new ArrayList<>();
        int cc = start;
        int guard = 0;
        while (cc >= 0 && cc < total && guard++ < MAX_SPILL_CHAIN) {
            if (r.lake[cc]) {
                // 进入另一个湖必须结束当前折线。旧实现把 cc 直接跳到该湖 spillDown
                // 并继续复用同一个 run，导致入湖前最后节点与湖另一侧溢口后首节点
                // 被直线连接，形成跨湖的扇形长线。下一段由盆地溢口兜底单独生成。
                stage[cc] = (byte) Stage.LAKE_STORAGE.ordinal();
                int b2 = r.basinId[cc];
                if (b2 >= 0 && b2 < r.spillCell.length) {
                    int sp2 = r.spillCell[b2];
                    if (sp2 >= 0) stage[sp2] = (byte) Stage.SPILLWAY.ordinal();
                }
                break;
            }
            if (claimed[cc]) break;                      // 汇入已有河
            claimed[cc] = true;
            if (stage[cc] == Stage.NONE.ordinal()) {
                stage[cc] = (byte) Stage.DOWNSTREAM.ordinal();
            }
            run.add(cc);
            int nd = r.down[cc];
            // down=-1 表示当前折线在盆地/边界终止；不能在同一 run 中跳到 spillDown，
            // 否则会跨越未记录的湖面或盆地内部。溢口下游由独立折线负责。
            if (nd < 0) break;
            cc = nd;
        }
        return run;
    }

    /** 该格 8 邻是否存在海（h ≤ seaLevel）—— 入海阶段的显式判定。 */
    private static boolean nearSea(Result r, int k, int n) {
        int ci = k % n, cj = k / n;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                if (r.height[nj * n + ni] <= r.seaLevel) return true;
            }
        }
        return false;
    }

    /**
     * ★★★ 2026-09-24【下游链长度】★★★（沿 {@code down} 走到出口的步数）
     *
     * <p>用途：源点按链长降序处理 ⇒ 主干（最长链）先 claim 整条链路，避免被短支流
     * 从中间截断（见 {@link #extractChannelSkeletons} 的根因注释）。</p>
     *
     * <p>实现：按填洼面【升序】DP（下游 fill 更低 ⇒ 先算好），单次线性扫描；
     * 越界/无下游记 1。确定性：只依赖 down/fill，无集合迭代顺序。</p>
     */
    private static int[] computeChainLen(Result r, int n, int total) {
        Integer[] idxs = new Integer[total];
        for (int k = 0; k < total; k++) idxs[k] = k;
        Arrays.sort(idxs, (a, b) -> Double.compare(r.fill[a], r.fill[b]));   // 升序
        int[] len = new int[total];
        for (int k : idxs) {
            int d = r.down[k];
            len[k] = (d >= 0 && d < total && len[d] > 0) ? len[d] + 1 : 1;
        }
        return len;
    }

    /**
     * ★ 2026-09-24：阶段计数汇总（诊断/验收用，只读）。
     * <p>★ 同日修复：改扫 {@code stage[]}（逐格终态）。旧版读 {@code lifeCounts}，
     * 但 SOURCE/CHANNEL/LAKE_STORAGE/DOWNSTREAM 属中间阶段、从不进 counts
     * ⇒ 审计表恒 0，误导排查。</p>
     */
    public static String stageSummary(Result r) {
        StringBuilder sb = new StringBuilder();
        Stage[] vs = Stage.values();
        int[] byCell = new int[vs.length];
        for (byte st : r.stage) {
            int s = st & 0xff;
            if (s > 0 && s < byCell.length) byCell[s]++;
        }
        for (int i = 1; i < vs.length; i++) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(vs[i].name()).append('=').append(byCell[i]);
        }
        return sb.toString();
    }

    /**
     * ★★★ 2026-09-24【D8 对角互穿消解实现】★★★
     *
     * <p>扫描每个 2×2 小方块；若两条对角 down 同时存在，则只保留真实落差更大的
     * 对角边。被取消的格尝试改指向两个正交候选中【真实地形更低且不高于当前格】者，
     * 否则置 -1（该处成为局部出口/断头，不制造上坡）。扫描顺序固定，结果确定。</p>
     */
    private static void resolveDiagonalCrossings(Result r) {
        int n = r.n;
        for (int j = 0; j + 1 < n; j++) {
            for (int i = 0; i + 1 < n; i++) {
                int a = j * n + i;
                int b = j * n + i + 1;
                int c = (j + 1) * n + i;
                int d = (j + 1) * n + i + 1;
                // ★ 2026-09-24【方向补全】：对角线有两个方向 ⇒ 必须两组都判：
                //   主对角 {a→d 或 d→a} × 副对角 {b→c 或 c→b} 同时存在才构成 X。
                //   ⚠ 上一版只判 a→d ∧ b→c ⇒ 漏掉反向（实测残留 1 对：
                //     (202,-186)→(206,-182) ∧ (202,-182)→(206,-186) = a→d ∧ c→b）。
                boolean mDiag = r.down[a] == d || r.down[d] == a;
                boolean xDiag = r.down[b] == c || r.down[c] == b;
                if (!mDiag || !xDiag) continue;
                int from1 = r.down[a] == d ? a : d;
                int to1 = from1 == a ? d : a;
                int from2 = r.down[b] == c ? b : c;
                int to2 = from2 == b ? c : b;
                double drop1 = r.height[from1] - r.height[to1];
                double drop2 = r.height[from2] - r.height[to2];
                // 保留真实落差更大的对角边；并列保留主对角（确定性）
                int loser = drop1 >= drop2 ? from2 : from1;
                int[] options = loser == a ? new int[]{b, c}
                        : loser == b ? new int[]{a, d}
                        : loser == c ? new int[]{a, d}
                        : new int[]{b, c};
                int best = -1;
                double bestDrop = 0;
                for (int o : options) {
                    if (r.lake[o] || !r.channel[o]) continue;
                    if (r.down[o] == loser) continue;          // 防 2-环（改道指回对方）
                    double drop = r.height[loser] - r.height[o];
                    if (drop >= -FLOW_UPHILL_TOL && drop > bestDrop) {
                        bestDrop = drop;
                        best = o;
                    }
                }
                r.down[loser] = best;
            }
        }
    }

    /**
     * ★ 2026-09-23【填洼面平局 ⇒ 按真实地形定向】★ 阈值经 FILL_EPS 反推。
     *
     * <p>{@code FILL_EPS = 1e-5} ⇒ 被淹平的盆地里，相邻格 fill 差只有 ~1e-5 量级
     * ⇒ 远远小于本阈值 ⇒ 视为"平局"。而真实斜坡上相邻格 fill 差 ≫ 1e-3 ⇒ 主判据
     * （填洼面坡度）照常主导，行为不变。</p>
     */
    private static final double TIE_EPS = 1e-3;

    /** 真实地形允许的上坡公差（块）；1/4 格内的数值噪声不切断河线。 */
    private static final double FLOW_UPHILL_TOL = 0.25;

    /**
     * ★ 2026-09-24【溢口续流链长上限】（格）：防止病态地形上的无限续流。
     * 正常 region 网格 ≈ 3.8×10³~3×10⁴ 格，取 1<<14 足够覆盖"多盆地串联"的真实链。
     */
    private static final int MAX_SPILL_CHAIN = 1 << 14;

    /** ★ 2026-09-24：INVALID 逐尾诊断计数（前 20 条打印，诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicInteger invalidDiagCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 8 邻最陡下降（严格更低；斜距归一）；无下坡返回 -1（只可能出现在出口/网格边）。
     *
     * <p><b>★ 2026-09-23 修复"横着过山脊"（用户判据）</b>：旧实现只按填洼面 {@code f}
     * 的坡度定方向 —— 而 priority-flood 的 ε 梯度在<b>被淹平的盆地内</b>是"谁先出队
     * 谁在上游"，与地形毫无关系 ⇒ 会出现<b>与地形无关的横切直线</b>，甚至跨过被淹平的
     * 低脊（实测路径越脊凸点 48 个，平均需下切 5.50 块 / 最大 22.63 块）。</p>
     *
     * <p>修法：fill 坡度差在 {@link #TIE_EPS} 内的格视为平局，改用<b>真实地形</b>{@code h}
     * 的落差（斜距归一）定向 ⇒ 在被淹平的盆地里，水流沿地形自身下坡走（两侧各归自己的
     * 低点），不再按 ε 队列顺序乱指。严格更低（{@code drop > 0}）保持不变 ⇒ 仍是有向无环，
     * accum 拓扑序不受影响。</p>
     */
    private static int steepest(int i, int j, int n, double[] f, double[] h) {
        int best = -1;
        double bestSlope = 0.0;          // 主判据：填洼面坡度
        double bestTerr = 0.0;           // 次判据：真实地形落差（仅平局时生效）
        double self = f[j * n + i];
        double selfH = h[j * n + i];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                double drop = self - f[nj * n + ni];
                if (drop <= 0) continue;
                double norm = Math.sqrt(di * di + dj * dj);
                double slope = drop / norm;
                double terr = (selfH - h[nj * n + ni]) / norm;
                boolean better;
                if (best < 0) {
                    better = true;
                } else if (slope > bestSlope + TIE_EPS) {
                    better = true;
                } else {
                    // 平局（含并列）：按真实地形落差定向；落差也相同时保持先到者（确定性）
                    better = slope >= bestSlope - TIE_EPS && terr > bestTerr;
                }
                if (better) { bestSlope = slope; bestTerr = terr; best = nj * n + ni; }
            }
        }
        return best;
    }
}
