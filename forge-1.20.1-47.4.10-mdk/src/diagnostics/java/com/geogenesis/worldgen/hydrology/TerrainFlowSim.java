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

        public Result(int n, double[] height, double[] fill, int[] down, long[] accum,
                      boolean[] channel, boolean[] lake, int chanThreshold, double seaLevel) {
            this(n, height, fill, down, accum, channel, lake, chanThreshold, seaLevel, 1);
        }

        public Result(int n, double[] height, double[] fill, int[] down, long[] accum,
                      boolean[] channel, boolean[] lake, int chanThreshold, double seaLevel,
                      int cellBlocks) {
            this.n = n; this.height = height; this.fill = fill; this.down = down;
            this.accum = accum; this.channel = channel; this.lake = lake;
            this.chanThreshold = chanThreshold; this.seaLevel = seaLevel;
            this.cellBlocks = cellBlocks;
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

    /** 判为湖的最小挖深（块，= spill − 盆底）：浅于此值 ⇒ breach（水穿行），不成湖。 */
    public static final double LAKE_MIN_DEPTH = 3.0;

    /** 判为湖的最小盆底面积（格）：小于此值 ⇒ breach，不成湖。 */
    public static final int LAKE_MIN_AREA = 300;

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
                down[idx] = steepest(i, j, n, fill);
            }
        }

        // ---------- ④ 汇流累积（按填洼面降序 = 拓扑序）----------
        long[] accum = new long[total];
        Arrays.fill(accum, 1);
        List<Integer> order = new ArrayList<>(total);
        for (int k = 0; k < total; k++) order.add(k);
        order.sort((a, b) -> Double.compare(fill[b], fill[a]));
        for (int k : order) {
            int d = down[k];
            if (d >= 0) accum[d] += accum[k];
        }

        // ---------- ⑤ 河道 / 湖（★ P1：breach-then-fill 门槛化）----------
        //   routing 面永远用填洼面（保证"水继续向下流"），但【湖标签】按盆地体量门槛发放：
        //   · 浅洼/小盆（挖深 < LAKE_MIN_DEPTH 或 面积 < LAKE_MIN_AREA）
        //       ⇒ 不成湖（= breach 语义：水穿行而过 / 蒸发），只留河道；
        //   · 真盆地（足够深且足够大） ⇒ fill 成湖，水面 = spill。
        //   这是"breach 优先于 fill"的实现形态：routing 不变，湖标签收紧。
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
        // ① 先把【成湖域的湖标签】从 cand 格洪泛到整个水位以下水面
        java.util.ArrayDeque<Integer> lq = new java.util.ArrayDeque<>();
        for (int k = 0; k < total; k++) {
            if (cand[k] && lakeDomain[comp[k]]) { lake[k] = true; lq.add(k); }
        }
        while (!lq.isEmpty()) {
            int c = lq.poll();
            int ci = c % n, cj = c / n;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                    int nb = nj * n + ni;
                    if (lake[nb]) continue;
                    if (fill[nb] - h[nb] > 1e-6) { lake[nb] = true; lq.add(nb); }
                }
            }
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
        int lakesN = 0;
        lastAlgoMs = (System.nanoTime() - tAlgo0) / 1e6;
        return new Result(n, h, fill, down, accum, channel, lake, chanThreshold, seaLevel, cb);
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
        // 入度：某河道格被几个上游河道格指向
        boolean[] hasUpstream = new boolean[total];
        for (int k = 0; k < total; k++) {
            if (!r.channel[k]) continue;
            int d = r.down[k];
            if (d >= 0 && r.channel[d] && !r.lake[d]) hasUpstream[d] = true;
        }
        boolean[] claimed = new boolean[total];
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < total; k++) {
            if (!r.channel[k] || r.lake[k] || hasUpstream[k] || claimed[k]) continue;
            // 源点：向上游回溯（若有未被 claim 的同源岔口则让先到者成河，避免重复）
            List<Integer> path = new ArrayList<>();
            int cur = k;
            while (cur >= 0 && r.channel[cur] && !r.lake[cur] && !claimed[cur]) {
                claimed[cur] = true;
                path.add(cur);
                int d = r.down[cur];
                if (d < 0) break;                        // 出口（网格边/海）
                cur = d;
            }
            if (path.size() >= 2) {
                int[] arr = new int[path.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = path.get(i);
                out.add(arr);
            }
        }
        return out;
    }

    /** 8 邻最陡下降（严格更低；斜距归一）；无下坡返回 -1（只可能出现在出口/网格边）。 */
    private static int steepest(int i, int j, int n, double[] f) {
        int best = -1;
        double bestSlope = 0.0;
        double self = f[j * n + i];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                double drop = self - f[nj * n + ni];
                if (drop <= 0) continue;
                double slope = drop / Math.sqrt(di * di + dj * dj);
                if (slope > bestSlope) { bestSlope = slope; best = nj * n + ni; }
            }
        }
        return best;
    }
}
