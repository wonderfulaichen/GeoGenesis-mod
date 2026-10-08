package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 平衡水面：地形排水分析（零 MC 依赖，纯函数）。
 *
 * <h2>为什么世界生成用平衡态而不是时间模拟</h2>
 * <p>世界无界 ⇒ 没有外部边界、没有初始时刻，时间演化模型无法直接定义。而世界生成需要的
 * 是一个确定函数 {@code (seed, 坐标) → 水状态}。对给定地形，"水最终停在哪里"就是
 * 扩散波/浅水系统的<b>稳态</b>：每个洼地蓄到最低溢流点后溢出，继续向下直到入海。
 * 该稳态是地形的纯函数，与遍历顺序、模型时间都无关。</p>
 *
 * <h2>与旧 priority-flood 实现的关键区别</h2>
 * <ul>
 *   <li><b>不加 ε 人工坡度</b>：只求水面高度，不制造人为流向。</li>
 *   <li><b>出口是显式集合</b>：种子必须是真实排水终端（海），窗口边框不得当出口。</li>
 *   <li><b>未决由【收敛判据】决定</b>：算两遍（只含真实海出口 vs 再加窗外地形出口），
 *       结果相同的格才与窗口切割无关。</li>
 * </ul>
 *
 * <p>本类还输出<b>排水父指针</b>（{@code parent}），供水量平衡计算汇水面积使用。</p>
 */
public final class EquilibriumWater {

    /**
     * @param level      水面高程
     * @param resolved   是否与窗口切割无关（收敛判据）
     * @param parent     排水父指针：{@code parent[k]} 是 k 的水流向的下游格，-1 = 排水终端
     */
    public record Surface(double[] level, boolean[] resolved, int[] parent,
                          int trueDrains, int exteriorExits, int unresolvedCells) { }

    /**
     * 内陆窗口的可信判据开关：`-Dgeogenesis.inlandResolved=true`。**默认 false（未验收）**。
     *
     * <h3>它解决什么</h3>
     * <p>窗口内无海（纯内陆）时，原实现把所有格标为 {@code resolved=false}
     * （因为收敛判据要求"只含真海"与"含窗外出口"两组结果相同，而 A 组不存在）
     * ⇒ 下游 {@code LakeWaterBalance.isLakePot} 要求 {@code resolved}
     * ⇒ **一格湖都建不起来**。</p>
     * <p>实测（游戏配置 REGION=512/step=4，7 个 region）：
     * <b>4 个为纯内陆、{@code resolved=0/31329}、basins=0、lake=0</b>；
     * 含海的 3 个正常（basins 1/12/46）。⇒ 这是"游戏里湖没了"的直接机理。</p>
     *
     * <h3>★ 为什么默认关闭（它的正解是"窗口够大"，不是"放宽判据"）</h3>
     * <p>启用后无海窗口改用「B 组有有限水位」判可信。实测效果：</p>
     * <table border="1">
     *   <tr><th>HALO</th><th>开关</th><th>湖（r=300 小窗）</th></tr>
     *   <tr><td>96</td><td>关</td><td>0.53%</td></tr>
     *   <tr><td>96</td><td><b>开</b></td><td><b>3.72%</b>（补回内陆湖）</td></tr>
     *   <tr><td><b>512</b></td><td>关/开</td><td><b>4.98%（两者逐位相同）</b></td></tr>
     * </table>
     * <p>⇒ <b>HALO 够大时该开关完全无效</b>（窗口自然含海）——
     * 说明"扩大窗口"才是结构性正解，放宽判据只是权宜之计。</p>
     *
     * <h3>已知偏差（未解决）</h3>
     * <p>启用后湖偏多：正典规格实测 **5.58%** vs 参照图 **3.63%**（多 54%）。
     * 位置一致度 93%，多出的 4.4 万格全部落在"参照判为陆地"处
     * ⇒ 判据仍偏宽。曾试过"窗外出口水位 +1 块后结果是否改变"的
     * 收敛式判据，使湖从 8.50% 降到 5.58%，但未收敛到 3.63%。</p>
     *
     * <p>⇒ 保留为诊断开关；**要用时须配合 `-Dgeogenesis.modelHalo=512`**（那时它已无必要）。</p>
     */
    public static final boolean INLAND_RESOLVED =
            Boolean.getBoolean("geogenesis.inlandResolved");

    private record Node(int cell, double level) implements Comparable<Node> {
        public int compareTo(Node o) {
            int c = Double.compare(level, o.level);
            return c != 0 ? c : Integer.compare(cell, o.cell);
        }
    }

    public static Surface compute(double[] bed, int nx, int nz,
                                  boolean[] drains, double[] exteriorLevel) {
        int cells = nx * nz;
        if (bed.length != cells || drains.length != cells || exteriorLevel.length != cells) {
            throw new IllegalArgumentException("array length != nx*nz");
        }
        // A 组：只以【真实排水终端】为出口。若窗口内一个都没有（纯内陆域），
        //   则 A 组无定义 ⇒ 不用它做收敛比较，全部格按"未验证"处理。
        //   ⚠ 不要在此抛异常：{@link WindowOutlets} 的边界出口正是为这种窗口设计的。
        boolean anyTrueDrain = false;
        for (boolean d : drains) if (d) { anyTrueDrain = true; break; }

        int[] parentA = new int[cells];
        double[] levelA = anyTrueDrain ? flood(bed, nx, nz, drains, null, parentA)
                : new double[cells];
        if (!anyTrueDrain) Arrays.fill(levelA, Double.POSITIVE_INFINITY);

        int exteriorExits = 0;
        boolean[] drainsB = drains.clone();
        for (int k = 0; k < cells; k++) {
            if (Double.isFinite(exteriorLevel[k])) { drainsB[k] = true; exteriorExits++; }
        }
        if (!anyTrueDrain && exteriorExits == 0) {
            throw new IllegalArgumentException("no drainage terminal supplied at all");
        }
        int[] parentWhenNoTrueDrain = anyTrueDrain ? null : new int[cells];
        double[] levelB = flood(bed, nx, nz, drainsB, exteriorLevel, parentWhenNoTrueDrain);

        // ★★★ 无海窗口的【第二组】：把窗外出口水位整体抬高 1 块再洪泛 ★★★
        //
        //   这是原 A/B 收敛判据（"只含真海 vs 再加窗外地形"）在【无海窗口】下的
        //   等价形式：原判据测"关掉/打开窗外出口是否改变结果"，此处测
        //   "窗外出口的水位变化是否传导到本格"。
        //   ⇒ 若传导 ⇒ 该格结果依赖窗口边界 ⇒ **不可信**（窗口一变就变）。
        //   ⇒ 若不传导 ⇒ 该格由窗内地形自身决定 ⇒ 可信。
        //
        // 【为什么必须这样（第一版教训）】我曾简单写成"B 组有有限水位即可信"
        //   ⇒ 湖 8.50% vs 参照 3.63%（**多 2.3 倍**），因为几乎每格都有水位。
        double[] levelC = null;
        if (!anyTrueDrain && INLAND_RESOLVED) {
            boolean[] drainsC = drains.clone();
            double[] exteriorRaised = new double[cells];
            System.arraycopy(exteriorLevel, 0, exteriorRaised, 0, cells);
            for (int k = 0; k < cells; k++) {
                if (Double.isFinite(exteriorRaised[k])) {
                    drainsC[k] = true;
                    exteriorRaised[k] += 1.0;              // ★ 抬高 1 块
                }
            }
            levelC = flood(bed, nx, nz, drainsC, exteriorRaised, null);
        }
        // 无真实终端时，A 组无意义：用 B 组结果作输出，但把 resolved 全置 false
        //   （"未验证"），因为无法用"计入窗外地形后是否改变"来判定。
        double[] levelOut = anyTrueDrain ? levelA : levelB;
        int[] parentOut = anyTrueDrain ? parentA : parentWhenNoTrueDrain;

        boolean[] resolved = new boolean[cells];
        int unresolved = 0;
        for (int k = 0; k < cells; k++) {
            // ★★★ 2026-10-09【B 方案：内陆窗口也必须能产湖 —— 实测根因修复】★★★
            //
            // 【缺陷】原判据写成 `anyTrueDrain && ...`：
            //   窗口内**没有海**（纯内陆）⇒ anyTrueDrain=false ⇒ **所有格 resolved=false**
            //   ⇒ `LakeWaterBalance.isLakePot` 要求 resolved（第 262 行）⇒ **一格湖都建不起来**。
            //
            // 【实测证据】（游戏配置 REGION=512/step=4，7 个 region）：
            //   region (0,-1)  resolved=0/31329   basins=0   lake=0   ← 完全内陆
            //   region (-2,-1) resolved=0/31329   basins=0   lake=0
            //   region (-1,-2) resolved=0/31329   basins=0   lake=0
            //   region (-2,0)  resolved=0/31329   basins=0   lake=0
            //   region (0,0)   resolved=31199/31329 basins=1 lake=2   ← 含海 ⇒ 正常
            //   region (1,-1)  resolved=29919/31329 basins=12 lake=84
            //   region (0,-2)  resolved=28394/31329 basins=46 lake=376
            //   ⇒ **7 个里 4 个因"窗口无海"而全域失效** ⇒ 这就是"游戏里湖没了"的直接机理。
            //
            // 【为什么正典大窗口没暴露】r=700 窗口跨越海岸线 ⇒ 总能找到海 ⇒ 从不触发。
            //
            // 【正解（局部量路线）】无海窗口时，**窗口边界就是水流出本域的地方** ——
            //   `WindowOutlets.exterior` 给的正是"窗外一格的真实地形高"，
            //   水位低于它时边界通量为零（水出不去，正确）；高于它则成为出口。
            //   ⇒ B 组（含窗外出口）的结果**在本窗口内是自洽且局部的**，应当信任。
            //   ⇒ 无海时 `resolved` 取 true（不再要求"与 A 组相同"——A 组本就不存在）。
            //
            // 【开关】`-Dgeogenesis.intlandResolved=true` 启用；默认 false = 原行为（零变更）。
            boolean same;
            if (anyTrueDrain) {
                same = Double.isFinite(levelA[k]) && Double.isFinite(levelB[k])
                        && Math.abs(levelA[k] - levelB[k]) < 1e-9;
            } else {
                // 无海窗口：以【窗外出口水位 +1 块后结果是否改变】为可信判据
                //   （原 A/B 收敛判据的等价局部化形式，见上方 levelC 的 javadoc）。
                //   不改变 ⇒ 该格由窗内地形自身决定 ⇒ 可信；
                //   改变   ⇒ 结果依赖窗口边界 ⇒ 不可信（窗口一变就变）。
                same = INLAND_RESOLVED && levelC != null
                        && Double.isFinite(levelB[k]) && Double.isFinite(levelC[k])
                        && Math.abs(levelB[k] - levelC[k]) < 1e-9;
            }
            resolved[k] = same;
            if (!same) unresolved++;
        }
        int trueDrains = 0;
        for (boolean d : drains) if (d) trueDrains++;
        return new Surface(levelOut, resolved, parentOut, trueDrains, exteriorExits, unresolved);
    }

    /**
     * 邻域：<b>8 邻域 + 欧几里得边长</b>（正交 1、对角 √2）。
     *
     * <h3>为什么必须含对角</h3>
     * <p>四邻域规则在<b>无水位差</b>的平地上只能产生轴向流向；合成平地实测
     * （{@link IsotropyProbe}，单一排水洞、理想应径向收敛）：
     * 东 4608 / 西 4512 / 南 48 / 北 47，<b>对角方位全为 0</b>，最大比 4608:1。
     * 真实地形上的后果是平原出现<b>平行等距的轴向直纹</b>（与项目历史"轴对齐直边"同源）。
     * 加入对角邻域后，流向可以指向任意方向。</p>
     *
     * <p>⚠ 这是<b>会改变水位结果</b>的模型改动（洪泛邻居集改变），
     * 因此窗口无关性、水量平衡判据与各向同性检验都必须<b>全部重跑</b>。</p>
     */
    private static final int[][] DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static double[] flood(double[] bed, int nx, int nz, boolean[] seeds,
                                  double[] seedLevel, int[] parentOut) {
        int cells = nx * nz;
        double[] level = new double[cells];
        Arrays.fill(level, Double.POSITIVE_INFINITY);
        if (parentOut != null) Arrays.fill(parentOut, -1);
        // ★ filler[next] = 最后一次【严格降低】 level[next] 的弹出格 ⇒
        //   level[next] = max(level[filler], bed[next]) ⇒ filler 是 next 的
        //   【最小极大路径】下一跳（通往种子的最经济路径）。水沿 parent 流动
        //   = 沿 filler 反向跳 ⇒ 平地/洼地/鞍口的流向全部由填充顺序天然给出。
        int[] filler = new int[cells];
        Arrays.fill(filler, -1);
        PriorityQueue<Node> queue = new PriorityQueue<>();
        for (int k = 0; k < cells; k++) {
            if (!seeds[k]) continue;
            double start = seedLevel != null && Double.isFinite(seedLevel[k])
                    ? Math.max(bed[k], seedLevel[k]) : bed[k];
            level[k] = start;
            queue.add(new Node(k, start));
        }
        if (queue.isEmpty()) throw new IllegalArgumentException("no drainage terminal supplied");
        boolean[] settled = new boolean[cells];
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            if (settled[node.cell] || node.level != level[node.cell]) continue;
            settled[node.cell] = true;
            int x = node.cell / nz, z = node.cell % nz;
            for (int[] d : DIRS8) {
                int nx2 = x + d[0], nz2 = z + d[1];
                if (nx2 < 0 || nx2 >= nx || nz2 < 0 || nz2 >= nz) continue;
                int next = nx2 * nz + nz2;
                double candidate = Math.max(node.level, bed[next]);
                if (candidate < level[next]) {
                    level[next] = candidate;
                    filler[next] = node.cell;
                    queue.add(new Node(next, candidate));
                }
            }
        }
        if (parentOut != null) {
            // ★ 由 filler 直接导出 parent（取代旧的 EDT 字典序选向 + 断环补丁）：
            //   · 无环性：若环 A→B→…→A，则 B 先于 A 弹出（B 填 A）、A 先于 B 弹出，
            //     绕环一圈弹出时间严格递增又回到自身 ⇒ 矛盾（任意环长同理）。
            //   · 无死端：非种子格只有两种获得 level 的途径（种子直赋 / relaxation 同时赋
            //     filler）⇒ 有限 level 的非种子格 filler 必 ≥0 ⇒ 链必终止于种子。
            //   · 平地质量：同水位格的 parent = 首个弹出它的邻居 ≈ 距种子更近者
            //     ⇒ 单排水洞平地呈径向收敛（IsotropyProbe 的理想形态）。
            //   这正是 FastFlow Algorithm 4（depression carving：recipient 沿 carve
            //   路径反向）的语义 —— 洼地内的水天然经【最低溢口】流向下游。
            int badParent = 0;
            for (int k = 0; k < cells; k++) {
                if (!Double.isFinite(level[k])) { parentOut[k] = -1; continue; }
                if (seeds[k]) { parentOut[k] = -1; continue; }
                parentOut[k] = filler[k];
                if (parentOut[k] < 0) badParent++;     // 理论上恒 0（见上）⇒ 计数暴露
            }
            if (badParent > 0) {
                System.out.printf("[parent] INVARIANT BROKEN: 有限水位非种子格 parent=-1 n=%d%n",
                        badParent);
            }
        }
        return level;
    }

    private EquilibriumWater() { }
}
