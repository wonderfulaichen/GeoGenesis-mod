package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 湖泊水量平衡：把"地形能蓄多少水"变成"实际有多少水"。
 *
 * <h2>为什么必须做这一步</h2>
 * <p>{@link EquilibriumWater} 给出的是"水蓄到最低鞍口"的<b>几何</b>结果。
 * 若直接采用，沙漠里也会出现大湖——因为地形低洼不等于有水。
 * 真实湖泊的稳态由收支决定：</p>
 * <pre>  流域来水 = 湖面蒸发
 *  R · A_catch · runoffCoeff  =  E · A_lake(η)</pre>
 * <p>来水充足 ⇒ 蓄到鞍口并溢流（河由此产生）；来水不足 ⇒ 水面停在更低的 η，
 * 湖更小；若连最低洼处都留不住水 ⇒ <b>无湖</b>（干盐湖/季节性洼地）。</p>
 *
 * <h2>算法（不做时间步进）</h2>
 * <p>对每个湖盆，用其"水位—面积/容积"曲线，在 {@code [盆底, 鞍口]} 上求满足收支的水位。
 * 收支左边随盆内汇水面积固定，右边随湖面面积单调增 ⇒ 解唯一，二分即可。
 * 这正是"稳态"的含义：与下了多久雨无关，只与气候和地形有关。</p>
 *
 * <p><b>边界</b>：① 汇水面积由窗口内排水树给出，窗口外未知 ⇒ 靠近边框的湖盆应标未决；
 * ② 只有"有出流"的湖才会溢流成河，未溢出者不产生下游径流；③ 未含地下水/入渗随深度变化。</p>
 */
public final class LakeWaterBalance {

    /**
     * @param precipitation 降水率（block/时间，与蒸发同量纲）
     * @param evaporation   湖面蒸发率（block/时间）
     * @param runoffCoeff   降水转地表径流的比例（0..1）
     */
    public record Climate(double precipitation, double evaporation, double runoffCoeff) {
        public Climate {
            if (precipitation < 0 || evaporation < 0 || runoffCoeff < 0 || runoffCoeff > 1) {
                throw new IllegalArgumentException("invalid climate parameters");
            }
        }
    }

    /** 单个湖盆的结果。{@code outflow} 为溢流出流量（未溢流者为 0）。 */
    public record Basin(int seedCell, double bedAtSeed, double sillLevel, double level,
                        double areaCells, double catchmentCells, double inflow, double evaporationOut,
                        double outflow, boolean overflow, boolean catchmentTruncated) { }

    /**
     * @param runoffPerCell 每格的径流贡献（降水×径流系数×面积）——河网用它做源项
     * @param overflowAt    每个"溢流出口格"的溢流量（无溢流处为 0）
     * @param basinLevelAt  每个"盆地代表格"的最终水位（供河网/落块按格查询）
     */
    public record Result(double[] depth, boolean[] lake, List<Basin> basins, int dryBasins,
                         double runoffPerCell, java.util.Map<Integer, Double> overflowAt,
                         java.util.Map<Integer, Double> basinLevelAt,
                         int[] basinId, int[] basinOutlet) {
        /**
         * 兼容旧 7 参构造（探针 A/B 与小合成用例）。
         * 不带盆地身份时 basinId/basinOutlet 为 null ⇒ 调用方须回退旧语义。
         */
        public Result(double[] depth, boolean[] lake, List<Basin> basins, int dryBasins,
                      double runoffPerCell, java.util.Map<Integer, Double> overflowAt,
                      java.util.Map<Integer, Double> basinLevelAt) {
            this(depth, lake, basins, dryBasins, runoffPerCell, overflowAt, basinLevelAt,
                    null, null);
        }
    }

    /**
     * 该湖格的盆地编号；{@code -1} = 非湖或身份未知（旧构造 / 未判定）。
     *
     * <p><b>为什么必须显式给出</b>：此前下游探针（如 {@code LifecycleChainProbe}）
     * 靠"离哪个溢出口最近"猜盆地归属 —— 相邻两湖时必然猜错，
     * 于是"全周期通过"可能只是把 A 湖的源头接到了 B 湖的出口。
     * 盆地归属必须由产出方（本类）给出，而不是由消费方猜。</p>
     */
    public static int basinOf(Result r, int cell) {
        return r.basinId() == null || cell < 0 || cell >= r.basinId().length
                ? -1 : r.basinId()[cell];
    }

    /** 该盆地的出口格；{@code -1} = 无出口（内流盆地 / 未判定）。 */
    public static int outletOf(Result r, int basinIndex) {
        if (r.basinOutlet() == null || basinIndex < 0 || basinIndex >= r.basinOutlet().length) {
            return -1;
        }
        return r.basinOutlet()[basinIndex];
    }

    private final int nx, nz;
    private final double[] bed, equilibrium;
    private final int[] parent;
    private final boolean[] resolved;
    private final double cellArea;

    /**
     * 最小可辨识水深（block）：Minecraft 水的最小单位是一方块，
     * 蓄满后仍浅于此的洼地在渲染与落块上都不存在可见水体。
     * 作为伪洼地过滤门槛，它来自显示单位的物理下限，而不是调参。
     */
    public static final double MIN_VISIBLE_DEPTH = 1.0;

    /**
     * 来水不可知时是否按"满湖（齐平鞍口）"处理。
     *
     * <p>默认 <b>true</b>，对齐生产链旧判据 {@code HydroTileBalance.findLakeLevel}
     * （{@code qin <= 0 || decay <= 0 → return spill}）。</p>
     *
     * <p>这是<b>显式取舍</b>：盆地存在 ⇒ 有被上游灌满的可能，故给确定水位而不留空洞；
     * 代价是干燥内流盆地也会显示为满湖。若将来要严格区分，把它置 false
     * （则无来水的湖会干涸，但会留下"未决"格）。</p>
     */
    private boolean assumeFullWhenUnfed = true;

    /**
     * ★★ 极小散点蓄水坑过滤（2026-10-05，A/B）★★
     *
     * <p><b>用户观察</b>：「湖泊还有一些极小的散点蓄水坑」。</p>
     *
     * <p><b>实测规模</b>（{@code runLakeSizeProbe}，r=700 / 600k）：
     * <pre>
     *   尺寸档        分量数   占比      ★体积占比
     *   1 格            13    4.8%      0.00%
     *   2-3 格          36   13.3%      0.02%
     *   4-10 格         88   32.6%      0.15%
     *   ...
     *   501+ 格         23    8.5%     98.34%
     *   ⇒ 散点坑(≤4格) 66 个(24.4%)，仅占水量 0.04%
     * </pre>
     * ⇒ <b>23 个大湖持 98.34% 水量，247 个小湖只占 1.66%</b>
     * ⇒ 过滤小湖几乎不损失水量（安全）。</p>
     *
     * <h3>★ 为什么判据必须用【几何面积】而非"来水/流量"</h3>
     * <p>实测（同一探针的盆地级诊断）：<b>所有盆地 inflow &gt; 0</b>
     * （66 个盆地全部有来水；因为每个盆地至少含自身格）⇒
     * 任何基于 inflow / fed / 出流的判据<b>原理上无法区分散点坑</b>。
     * 实测踩过两次：{@code !fed}（fed 恒真）、{@code inflow < oneCellEvap}（inflow 恒大）
     * —— 两次结果都<b>完全不变</b>（270 分量一个没少）。</p>
     * <p>⇒ 只能用<b>几何量</b>。这也正是参考项目 FTF 的做法：
     * {@code LakeConfig.sizeMin/sizeMax} 就是<b>几何尺寸</b>门槛。</p>
     *
     * <p><b>参考项目对照（三个项目没有一个保留无来水的洼地）</b>：</p>
     * <ul>
     *   <li><b>FreeTerraForged</b>：湖 = 河流一段加宽，且需<b>概率抽签</b>
     *       （{@code shouldWidenOnPlateau}）+ <b>几何尺寸范围</b>
     *       （{@code sizeMin/sizeMax}）⇒ 不会每个洼地都成湖；</li>
     *   <li><b>FastFlow</b>：水体由流量阈值判定（无流量即非水体）；</li>
     *   <li><b>SimpleHydrology</b>：无湖对象。</li>
     * </ul>
     *
     * <p>⚠ 用户明确「我又不是不允许内陆湖」⇒ 本开关<b>不针对"内陆湖"</b>
     * （面积达标的湖照常保留，无论有无出流），只过滤<b>面积过小的坑</b>。</p>
     */
    private boolean dropTinyBasins = DROP_TINY_BASINS;

    /** 运行时切换（探针 A/B 用）。 */
    public LakeWaterBalance dropTinyBasins(boolean value) {
        this.dropTinyBasins = value;
        return this;
    }

    /** 全局默认（**默认 true = 已启用**，用户 2026-10-05 批准）。
     *  回退：{@code -Dhydro.dropTinyBasins=false}。 */
    public static volatile boolean DROP_TINY_BASINS =
            !"false".equals(System.getProperty("hydro.dropTinyBasins"));

    /**
     * 最小可见湖【物理面积】（block²）。**默认 11**（用户 2026-10-05 批准）。
     *
     * <p>依据（{@code runLakeSizeProbe} 尺寸谱 + 阈值扫描实测，r=400）：
     * <pre>
     *   阈值  分量  湖泊格        体积
     *   无    61   20859      67214
     *   5     52   20826(-0.16%) 67180
     *   11    41   20745(-0.55%) 67072   ← 选中
     *   21    28   20550(-1.48%) 66836
     *   31    24   20445(-1.99%) 66725
     * </pre>
     * 取 11 block² 消掉图上肉眼可见的散点（step=1 时约 11 格），
     * 代价仅 <b>0.55% 湖面积 / 0.21% 水量</b>。阈值按物理面积计算，
     * 不随诊断网格 step 改变。</p>
     *
     * <p>语义边界（勿误改）：判据是【几何面积】⇒ <b>不针对内陆湖</b>
     * —— 面积达标的湖无论有无出流都保留（用户明确"我又不是不允许内陆湖"）。</p>
     *
     * <p>对照项目既有规则 {@link #MIN_VISIBLE_DEPTH}（最小可见【深度】= 1 块）
     * —— 本条是其【面积】对应物，同属"显示单位的物理下限"而非调参。</p>
     */
    public static volatile int MIN_VISIBLE_AREA =
            Integer.getInteger("hydro.minVisibleArea", 11);

    /** 运行时切换兜底语义（探针 A/B 用）。 */
    public LakeWaterBalance assumeFullWhenUnfed(boolean value) {
        this.assumeFullWhenUnfed = value;
        return this;
    }

    public LakeWaterBalance(int nx, int nz, double cellSize, double[] bed,
                            EquilibriumWater.Surface surface) {
        if (bed.length != nx * nz) throw new IllegalArgumentException("bed length");
        this.nx = nx; this.nz = nz;
        this.cellArea = cellSize * cellSize;
        this.bed = bed;
        this.equilibrium = surface.level();
        this.parent = surface.parent();
        this.resolved = surface.resolved();
    }

    /** 无外部来水信息时的求解（来水按汇水格数估）。 */
    public Result solve(Climate climate, boolean[] sea, boolean[] borderMask) {
        return solve(climate, sea, borderMask, null);
    }

    /**
     * @param upstreamFlow 逐格【真实来水量】（体积/时间），通常来自河网汇流计算；
     *                     为 {@code null} 时按"汇水格数 × 每格径流"估算。
     *
     * <p><b>为什么需要它</b>（用户提问驱动）：湖的真实水位取决于来水，而来水就是
     * 上游汇流。仅靠几何（汇水面积）无法反映<b>沿程损失</b>（入渗/蒸发/湖内耗水）
     * ⇒ 干旱上游的水量在到达该盆地前已被消耗，几何面积却仍按满算。
     * 传入真实流量后，水位才与"实际到达的水"一致。</p>
     */
    public Result solve(Climate climate, boolean[] sea, boolean[] borderMask,
                        double[] upstreamFlow) {
        int cells = nx * nz;
        // 找湖盆种子：平衡态下水位高于床面的格（潜在湖），且非海。
        //    只在"盆地最低点"处建立湖盆，避免同一湖被多次建立。
        boolean[] isLakePot = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            isLakePot[k] = !sea[k] && resolved[k] && equilibrium[k] - bed[k] > 1e-6;
        }
        // 每个潜湖格沿 parent 回溯到"入湖口"（即其下游第一个非湖格），该非湖格代表这个湖盆的出口。
        // 湖盆身份 = 出口格（唯一），这保证同一湖只有一个 ID。
        //
        // ★★ 性能修复（本轮实测：1401² 窗口 + 8 轮耦合迭代跑 >60 分钟不收敛）★★
        //   旧写法对<b>每个格</b>都沿 parent 链走到底，且守卫是 `guard++ < cells`（196 万）
        //   ⇒ 复杂度 O(n·链长)，最坏情况天文数字。改为<b>记忆化 + 路径压缩</b>的链解析：
        //   每个格的"终端"只算一次并被其全部上游复用 ⇒ 摊还 O(n α(n))。
        //
        //   规则（与旧语义逐条对应）：
        //     ① parent < 0            ⇒ 终端 = 自身（链端）；
        //     ② 本格是湖 且 父格非湖  ⇒ 终端 = 父格（入湖口，即湖盆出口）；
        //     ③ 其余                  ⇒ 终端 = 父格的终端。
        int[] terminal = new int[cells];
        Arrays.fill(terminal, -1);
        // 链缓冲与"本格是否在本轮链上"标记：⚠ 必须有环保护。
        //   实测：不加保护时 chain[len++] 会越界（ArrayIndexOutOfBounds）——
        //   因为 parent 图在**平坦区**可能出现互相指向（A→B 且 B→A），旧实现的
        //   `guard++ < cells` 正是为此而设。这里改用 visiting 标记，遇环即把
        //   整条链的终端定为链上最小索引（确定性，且不依赖遍历顺序）。
        int[] chain = new int[cells];
        int[] visitStamp = new int[cells];
        int stamp = 0;
        for (int k = 0; k < cells; k++) {
            if (terminal[k] >= 0 || sea[k] || !resolved[k]) continue;
            stamp++;
            int len = 0;
            int c = k;
            int result = -1;
            while (c >= 0 && terminal[c] < 0) {
                if (visitStamp[c] == stamp) {           // 成环：就地收敛为环上最小格
                    int min = c;
                    for (int t = 0; t < len; t++) min = Math.min(min, chain[t]);
                    result = min;
                    break;
                }
                visitStamp[c] = stamp;
                // ★★ 2026-10-05 修正：湖格的出口判定必须【先于】parent<0 兜底 ★★
                //
                //   旧顺序的缺陷（实测 SpillPathDumpProbe 决定性检验）：
                //     minimax 洪泛在湖盆内部把水位抬平 ⇒ 湖内格 parent 大量为 -1
                //     ⇒ 第 159 行 `if (p < 0) { result = c; }` 先命中 ⇒
                //        terminal = 湖内死端格【自身】。
                //     而真正的"入湖口"规则（湖→非湖交界）排在后面 ⇒ 永远轮不到。
                //     实测后果：overflowAt 的键 83% 在湖内（54/65）、86% parent=-1，
                //     且【全部】距盆地边缘仅 1 格（54/54 可达）—— 出口就在旁边，
                //     却被错误地写成死端自身 ⇒ outletExit 沿 parent 走必然失败
                //     ⇒ 「溢口→下泄→入海」整体断裂（54/64 注入失败）。
                //
                //   修正：湖格（isLakePot）的终端 = 【邻域最近的非湖格】（入湖口）；
                //         只有非湖格的 parent<0 才取自身。
                if (isLakePot[c]) {
                    int edge = nearestNonLake(c, isLakePot, sea, nx, nz);
                    if (edge >= 0) { result = edge; break; }
                    // 邻域无非湖格（单格封闭湖）→ 落回 parent 逻辑
                }
                int p = parent[c];
                if (p < 0) { result = c; break; }
                if (isLakePot[c] && !isLakePot[p]) { result = p; break; }
                chain[len++] = c;
                c = p;
            }
            if (result < 0) result = (c >= 0 && terminal[c] >= 0) ? terminal[c] : k;
            terminal[k] = result;
            for (int t = 0; t < len; t++) terminal[chain[t]] = result;   // 路径压缩
        }
        // 2) 汇水面积：每个非海格计入其终端（= 该湖盆的出口 / 链端）。
        double[] inflowCells = new double[cells];
        for (int k = 0; k < cells; k++) {
            if (sea[k] || !resolved[k]) continue;
            int t = terminal[k];
            if (t >= 0) inflowCells[t] += 1;
        }

        // 3) 对每个湖盆求水量平衡水位。湖盆分组：按"连通湖格集合"分组。
        boolean[] visited = new boolean[cells];
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        double[] depth = new double[cells];
        boolean[] lake = new boolean[cells];
        List<Basin> basins = new ArrayList<>();
        java.util.Map<Integer, Double> overflowAt = new java.util.HashMap<>();
        java.util.Map<Integer, Double> basinLevelAt = new java.util.HashMap<>();
        // ★ 盆地身份（本轮新增）：逐格 basinId + 逐盆地 outlet。
        //   下游不再需要"猜最近出口"；同时使"每个正出流湖必须接自己的下游"可被验证。
        int[] basinId = new int[cells];
        java.util.Arrays.fill(basinId, -1);
        java.util.List<Integer> basinOutletList = new ArrayList<>();
        double runoffPerCell = climate.precipitation() * climate.runoffCoeff() * cellArea;
        int dry = 0;

        for (int start = 0; start < cells; start++) {
            if (visited[start] || !isLakePot[start]) continue;
            // 收集连通湖格
            ArrayList<Integer> group = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start); visited[start] = true;
            boolean truncated = false;
            while (!queue.isEmpty()) {
                int k = queue.remove(); group.add(k);
                if (borderMask[k]) truncated = true;
                int x = k / nz, z = k % nz;
                for (int[] d : dirs) {
                    int a = x + d[0], b = z + d[1];
                    if (a < 0 || a >= nx || b < 0 || b >= nz) continue;
                    int next = a * nz + b;
                    if (!visited[next] && isLakePot[next]) { visited[next] = true; queue.add(next); }
                }
            }
            // 鞍口 = 该组内平衡水位最大值（平衡态本身已给出到出口的高度）
            double sill = Double.NEGATIVE_INFINITY, lowest = Double.POSITIVE_INFINITY;
            int seed = group.get(0), outletKey = -1;
            for (int k : group) {
                sill = Math.max(sill, equilibrium[k]);
                if (bed[k] < lowest) { lowest = bed[k]; seed = k; }
            }
            // ★★ 2026-10-05：出口 = 盆地【边界上 bed 最低】的非湖格（真·最低鞍口）★★
            //
            //   【为什么必须这样】（实测证据链）
            //   此前用 nearestNonLake（最近的边界格）—— 错的：
            //     键(702,139) 湖面 level=182.99，最近的边界格 bed=183.77
            //     ⇒ 那格比湖面【高 0.78 块】⇒ 水根本流不过去。
            //   而盆地真正的鞍口是该组 equilibrium 的最大值（= sill），
            //   边界上必有一格 bed ≈ sill。找它，水才流得出去。
            //
            //   ⚠ 注意必须扫【整条边界】而非"最近格"：最近 ≠ 最低。
            //   性能：只扫 group 的边界（O(周长)），不是 O(面积)。
            double bestRim = Double.POSITIVE_INFINITY;
            int rimKey = -1;
            for (int k : group) {
                int x = k / nz, z = k % nz;
                for (int[] d : dirs) {
                    int a = x + d[0], b = z + d[1];
                    if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                    int nb = a * nz + b;
                    if (isLakePot[nb] || sea[nb]) continue;      // 只认非湖非海的边界格
                    if (bed[nb] < bestRim) { bestRim = bed[nb]; rimKey = nb; }
                }
            }
            // 盆地边缘被窗口截断时可能找不到边界格 ⇒ 退回 terminal（原语义）
            outletKey = rimKey >= 0 ? rimKey : -1;
            if (outletKey < 0) {
                for (int k : group) if (terminal[k] >= 0) outletKey = terminal[k];
            }
            // ★ 伪洼地过滤：蓄满后【最深处不足一个 Minecraft 方块】的洼地，游戏里放不下任何水块。
            //   依据是显示单位的物理下限（1 方块），不是可调阈值：
            //   这种"湖"在渲染与落块上都不存在，留着只会把它们算进湖泊统计。
            //   实测背景：本窗口 64 个洼地中有 62 个只有 1~2 格，全是坡度上的噪声级微凹点。
            if (sill - lowest < MIN_VISIBLE_DEPTH) {
                dry++;
                liquidate(depth, lake, group);
                continue;
            }
            double catchment = outletKey >= 0 ? inflowCells[outletKey]
                    : inflowCells[seed];
            // ★ 兜底语义（沿用生产链旧判据 {@code HydroTileBalance.findLakeLevel}）：
            //   旧代码在 `qin <= 0 || decay <= 0` 时 <b>直接 `return spill`</b>（当作满湖），
            //   而不是让湖干涸。理由：
            //   ① 没有来水信息 ≠ 没有水；盆地既然存在，就有被上游灌满的可能；
            //   ② 处处给出确定水位 ⇒ 绝不留空洞、绝不因"算不出来"而在图上留斑。
            //   代价：干燥内流盆地也会被当成满湖（"沙漠有湖"的来源）。
            //
            //   ⚠ "是否被喂到"的判据见下方（随 inflow 一起计算）。
            //     早先用 `catchment > 0` 是错的 —— 每个盆地至少含自身格 ⇒ 恒为真 ⇒ 分支永不触发
            //     （A/B 实测两档结果完全相同 6093 vs 6093，即兜底是死代码）。
            // ★ 来水量来源（优先级）：
            //   ① 若提供 upstreamFlow（来自河网汇流）→ 用【真实到达水量】；
            //   ② 否则退化为"汇水格数 × 每格径流"（几何估计，不含沿程损失）。
            //
            //   ⚠ 取哪个格是关键：河网 discharge 是"**经过**该格的上游累积量"（含本地源）。
            //     若只取"盆地出口格"的流量，会把<b>盆地自身集水</b>算漏（出口在湖外）。
            //     正确做法：取【盆地各格入流之和】= 该盆地真正截获的水量。
            //     实测教训：只取出口格时 fed 仍为真但水量极小 ⇒ solveLevel 给出
            //     "低于盆底"的水位 ⇒ 37/37 个盆地全被判干、湖格数 0。
            double inflow;
            if (upstreamFlow != null) {
                double sum = 0;
                for (int k : group) sum += Math.max(0, upstreamFlow[k]);
                // 盆地被截获的水 = 全部成员格的过流量上限（不重复计入下游）
                inflow = sum;
            } else {
                inflow = climate.precipitation() * climate.runoffCoeff() * catchment * cellArea;
            }
            boolean fed = upstreamFlow != null ? inflow > 1e-12 : catchment > group.size();

            // 水位—面积/容积曲线：把组内按床面排序，逐步加水求收支平衡。
            group.sort((a, b) -> Double.compare(bed[a], bed[b]));
            // ★★ 极小散点蓄水坑过滤（判据 = 几何面积，详见 dropTinyBasins 的 javadoc）★★
            //
            //   ⚠ 判据必须用【几何面积】，不能用 inflow/fed/出流：
            //     实测所有盆地 inflow>0（每盆地至少含自身格 ⇒ 恒有来水）
            //     ⇒ 基于流量的判据原理上无法区分（已踩两次：!fed、inflow<oneCellEvap
            //     结果均【完全不变】）。
            //   本条是既有规则 MIN_VISIBLE_DEPTH（最小可见【深度】）的【面积】对应物，
            //   同属"显示单位的物理下限"。
            double physicalArea = group.size() * cellArea;
            if (dropTinyBasins && physicalArea < MIN_VISIBLE_AREA) {
                dry++;
                liquidate(depth, lake, group);
                continue;
            }
            double level = fed || !assumeFullWhenUnfed
                    ? solveLevel(group, inflow, climate.evaporation(), sill)
                    : sill - 1e-9;                            // 无来水信息 ⇒ 回落 sill（满湖）
            boolean overflow = level >= sill - 1e-9;
            double areaCells = 0;
            // ★ 淹没判据必须【含等号】：平坦湖底时所有格床面相同，
            //   用 `bed < level` 会一格都判不湿 ⇒ 整个湖盆凭空消失。
            //   （本轮实测：极湿工况湖面积 41 而几何上限 105，正是此因。）
            for (int k : group) {
                if (bed[k] <= level) {
                    depth[k] = Math.max(0, level - bed[k]);
                    lake[k] = true;
                    areaCells += 1;
                }
            }
            double evapOut = climate.evaporation() * areaCells * cellArea;
            if (areaCells <= 0) { dry++; liquidate(depth, lake, group); continue; }
            // ★ 溢流量 = 来水 − 蒸发。只有【蓄满】的湖才有净出流：
            //   未蓄满 ⇒ 水面低于鞍口 ⇒ 全部来水被蒸发消耗 ⇒ 下游无径流。
            //   这正是"沙漠无河"的物理来源，不需要额外开关。
            double outflow = overflow ? Math.max(0, inflow - evapOut) : 0;
            if (outflow > 0 && outletKey >= 0) {
                overflowAt.merge(outletKey, outflow, Double::sum);
            }
            basins.add(new Basin(seed, bed[seed], sill, level, areaCells, catchment,
                    inflow, evapOut, outflow, overflow, truncated));
            int basinIndex = basins.size() - 1;
            // ★ 记录盆地身份：逐格 basinId（供下游精确定位，取代"最近出口"猜测）
            //   与逐盆地 outlet（-1 = 内流/无出口）。
            basinOutletList.add(outletKey);
            for (int k : group) basinId[k] = basinIndex;
            if (lake[seed]) basinLevelAt.put(seed, level);
        }
        int[] basinOutlet = new int[basinOutletList.size()];
        for (int i = 0; i < basinOutlet.length; i++) basinOutlet[i] = basinOutletList.get(i);
        return new Result(depth, lake, basins, dry, runoffPerCell, overflowAt, basinLevelAt,
                basinId, basinOutlet);
    }

    /**
     * 解 {@code inflow = E · A(η)}：A(η) 是床面低于 η 的格数×格面积。
     *
     * <p>⚠ <b>水位上限是【鞍口】{@code sill}，不是【组内最高床面】</b>。
     * 几何组只包含"平衡水位以下的湖格"，其最高床面低于鞍口。
     * 若拿组内最高床面当上限，湖就永远填不到岸边，
     * 表现为"每个湖都恰好一格深、绝大多数盆地只有 1~2 格"（本轮实测）。
     * 正确上限：鞍口减去一个极小量（水面不得越过鞍口本身）。</p>
     */
    private double solveLevel(List<Integer> group, double inflow, double evaporation, double sill) {
        double lowest = Double.POSITIVE_INFINITY;
        for (int k : group) lowest = Math.min(lowest, bed[k]);
        double highest = sill - 1e-9;      // 蓄满 = 齐平鞍口
        if (!(highest > lowest)) highest = lowest + 1e-9;
        if (evaporation <= 0) return highest;                      // 无蒸发 ⇒ 蓄满
        double requiredArea = inflow / (evaporation * cellArea);    // 需要的湖面格数
        // 无来水（或需要的面积不足一格）⇒ 干：水位取【盆底之下】，确保"含等号"判据也一格不湿。
        if (requiredArea < 1) return lowest - 1e-9;
        // ★ 面积按【绝对水位】算，而不是按"组内第 k 个格"：
        //   组内格数少于真实可淹格数（组由几何平衡态界定）⇒ 需要用 bed[k] <= η 重新计数。
        //   这里改为直接解：找最小 η 使 count(bed <= η) >= requiredArea。
        //   由于 group 是该洼地可淹范围的采样，用组内计数即为该湖的面积—水位曲线。
        int n = group.size();
        double[] sorted = group.stream().mapToDouble(k -> bed[k]).sorted().toArray();
        if (requiredArea >= n) return highest;                      // 来水极多 ⇒ 蓄满（溢出）
        int index = (int) Math.ceil(requiredArea) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        double eta = sorted[index] + 1e-9;
        return Math.min(eta, highest);
    }

    private void liquidate(double[] depth, boolean[] lake, List<Integer> group) {
        for (int k : group) { depth[k] = 0; lake[k] = false; }
    }

    /**
     * 从 {@code c} 出发找最近的【非湖、非海】格（4 邻域 BFS，限深 2 层）。
     *
     * <p>依据（SpillPathDumpProbe 决定性检验）：54/54 个湖内溢口键
     * 距盆地边缘全部为 1 格 ⇒ 浅层 BFS 足够，无需全湖搜索。</p>
     *
     * @return 边缘格下标；-1 = 2 层内无非湖格（单格封闭湖，理论边界情形）。
     */
    private int nearestNonLake(int c, boolean[] isLakePot, boolean[] sea, int nx2, int nz2) {
        int ci = c / nz2, cj = c % nz2;
        for (int layer = 1; layer <= 2; layer++) {
            for (int di = -layer; di <= layer; di++) {
                for (int dj = -layer; dj <= layer; dj++) {
                    if (Math.max(Math.abs(di), Math.abs(dj)) != layer) continue;
                    int a = ci + di, b = cj + dj;
                    if (a < 0 || b < 0 || a >= nx2 || b >= nz2) continue;
                    int k = a * nz2 + b;
                    if (!isLakePot[k] && !sea[k]) return k;
                }
            }
        }
        return -1;
    }
}
