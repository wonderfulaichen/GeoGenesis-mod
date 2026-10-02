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
        // 无真实终端时，A 组无意义：用 B 组结果作输出，但把 resolved 全置 false
        //   （"未验证"），因为无法用"计入窗外地形后是否改变"来判定。
        double[] levelOut = anyTrueDrain ? levelA : levelB;
        int[] parentOut = anyTrueDrain ? parentA : parentWhenNoTrueDrain;

        boolean[] resolved = new boolean[cells];
        int unresolved = 0;
        for (int k = 0; k < cells; k++) {
            boolean same = anyTrueDrain && Double.isFinite(levelA[k]) && Double.isFinite(levelB[k])
                    && Math.abs(levelA[k] - levelB[k]) < 1e-9;
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

    private static double edgeCost(int dx, int dz) {
        return (dx == 0 || dz == 0) ? 1.0 : Math.sqrt(2.0);
    }

    private static double[] flood(double[] bed, int nx, int nz, boolean[] seeds,
                                  double[] seedLevel, int[] parentOut) {
        int cells = nx * nz;
        double[] level = new double[cells];
        Arrays.fill(level, Double.POSITIVE_INFINITY);
        if (parentOut != null) Arrays.fill(parentOut, -1);
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
                    queue.add(new Node(next, candidate));
                }
            }
        }
        if (parentOut != null) resolveFlowDirections(bed, nx, nz, level, seeds, parentOut);
        return level;
    }

    /**
     * 由【水面高程】导出【排水方向】，并处理平坦区的平局。
     *
     * <h3>为什么不能用"路径累计距离"破平局</h3>
     * <p>曾用 Dijkstra 累计步长（正交 1、对角 √2）破平局，结果在纯平地上
     * （{@link TieBreakDiagnosisProbe}，单一排水洞）出现系统性偏差：
     * <b>轴向射线上的格只有 43.1% 选对，而对角射线 100% 选对</b>，
     * 即 57% 的轴向格被拉向对角。</p>
     * <p>根因：八边形生长前沿下，轴向格发现"对角邻居的累计距离更小"（对角步更长，
     * 反而更接近洞心的斜向路径），于是被误导向对角。这是<b>度规与格点方向族不匹配</b>，
     * 不是采样效应（直方图仅 4 个角度桶有值，其余 32 桶恒为 0）。</p>
     *
     * <h3>正解：平局时按【到排水终端的直线距离】收敛</h3>
     * <p>平地上水的正确行为是<b>径向流向出口</b>。因此同水位时选择
     * <b>直线距离出口更近</b>的邻居，而不是"路径累计距离更短"的邻居。
     * 直线距离是各向同性的几何量：旋转地形时它随之旋转，不依赖数组索引，
     * 也不受八边形前沿影响。</p>
     *
     * <h3>★ 2026-10 修复：严格字典序，杜绝 parent 2-环</h3>
     * <p><b>实测（canonical 探针的环检测打样）</b>：旧写法的 ε 允许
     * {@code nl ≤ level[k] + 1e-12}（微小上坡）且平局带宽 {@code 1e-12}
     * ⇒ 相邻两格在"水位差 ≤1e-12 的糊带"里互选为下游 ⇒ <b>parent 出现 2-环</b>
     * （实测 (61,97)↔…、(450,71) 等，步 2~60 即回环）。后果：recover 演走预算耗尽
     * （failBudget=44,843）、D8 单遍累加在环上错序。</p>
     *
     * <p><b>修法</b>：严格无 ε 的字典序 {@code (level, EDT², cellIndex)} 逐跳下降：</p>
     * <ul>
     *   <li>候选必须 {@code nl ≤ level[k]}（严格不上坡，含等号）——最优路径后继
     *       的水位数学上必然 ≤ 自身，故候选永不为空；</li>
     *   <li>选最小 {@code (level, EDT², index)}：同水位跳 EDT 严格降、全等跳格号严格降
     *       ⇒ 任何环都要求该三元组绕环一圈后严格小于自身，<b>矛盾 ⇒ 无环</b>。</li>
     * </ul>
     * <p>格号只在"同水位且 EDT 完全相等"的极端平局介入（相邻等距环格），
     * 不会重现旧的"平坦区流向被数组索引锁死"伪影（那里 level/EDT 已先决定方向）。</p>
     */
    private static void resolveFlowDirections(double[] bed, int nx, int nz, double[] level,
                                              boolean[] seeds, int[] parentOut) {
        int cells = nx * nz;
        // ★ 到最近排水终端的【精确欧几里得距离平方】。
        //
        //   为什么不能用 Dijkstra（即便边长取 √2）：
        //   8 邻域 + √2 边长的路径距离是 **octile 度规**，其等距线是**八边形**，
        //   仍带方向族偏置。实测（TieBreakDiagnosisProbe）：平地上单一排水洞，
        //   轴向射线上的格只有 43.1% 选对（57% 被拉向对角），而对角射线 100% 选对；
        //   角度直方图仅 4 个桶有值、其余 32 桶恒为 0 ⇒ 不是采样效应，是度规与方向族不匹配。
        //
        //   正解：精确欧氏距离变换（Felzenszwalb & Huttenlocher 的抛物线包络法）。
        //   等距线是真正的圆 ⇒ 完全各向同性；两次一维扫描，O(n)。
        double[] edt = exactSquaredDistance(nx, nz, seeds, level);
        // 阶段A：逐格选择下游 —— 严格字典序 (level, EDT², cellIndex) 最小。
        for (int k = 0; k < cells; k++) {
            if (!Double.isFinite(level[k])) { parentOut[k] = -1; continue; }
            if (seeds[k]) { parentOut[k] = -1; continue; }
            parentOut[k] = lexMinNeighbor(k, nx, nz, level, edt, null);
        }
        // 阶段B：环修复。
        //
        //   ⚠ 为什么阶段A（局部严格下降）仍会有环（实测打样：(61,97)↔…、(450,71)↔…，
        //   步 2~60 回环）：环上逐跳水位不升 ⇒ 环内水位全等；而 A→B 要求 B 是 A 的
        //   字典序最小接受候选 —— 当 A 的"朝海一侧邻居"全部<b>水位更高被门槛拒绝</b>
        //   （未决高台边缘即此形态）时，A 可选不到 EDT 更小的邻居，互选成环。
        //   局部规则补不齐这个洞 ⇒ 必须全局断环。
        //
        //   断环算法（确定性、O(cells×迭代)）：沿 parent 走链，遇本链已访格 = 环；
        //   取环内 EDT² 最大者（离海最远、最该流向下游）为受害者，改指链外的次优
        //   接受候选（排除整条链，防前缀折返再造环）；无链外候选 ⇒ 置 -1（计数暴露）。
        //   新父链可能落入尚未处理的环 ⇒ 全流程迭代到无环（实测 1~2 轮内收敛）。
        byte[] state = new byte[cells];          // 0=未访 1=当前链 2=已证无环
        int[] chain = new int[cells];
        int iter = 0, repaired = 0;
        for (; iter < 10; iter++) {
            int found = 0;
            java.util.Arrays.fill(state, (byte) 0);
            for (int s = 0; s < cells; s++) {
                if (state[s] != 0 || parentOut[s] < 0) continue;
                int chainLen = 0;
                int cur = s;
                while (cur >= 0 && state[cur] == 0) {
                    state[cur] = 1;
                    chain[chainLen++] = cur;
                    cur = parentOut[cur];
                }
                if (cur >= 0 && state[cur] == 1) {
                    // 找到本链上的环：环成员 = chain[indexOf(cur) .. end]。
                    int start = 0;
                    while (chain[start] != cur) start++;
                    int victim = chain[start];
                    for (int t = start + 1; t < chainLen; t++) {
                        if (edt[chain[t]] > edt[victim]) victim = chain[t];
                    }
                    // 次优候选：同样接受门槛，但排除整条链（state==1）。
                    int repl = lexMinNeighbor(victim, nx, nz, level, edt, state);
                    if (repl >= 0) { parentOut[victim] = repl; }
                    else { parentOut[victim] = -1; }   // 链外无候选：终止该格（计数暴露）
                    found++;
                }
                for (int t = 0; t < chainLen; t++) state[chain[t]] = 2;
            }
            repaired += found;
            if (found == 0) break;
        }
        if (repaired > 0) {
            System.out.printf("[parent] cycle repair: broken=%d rounds=%d%s%n",
                    repaired, iter + 1, iter >= 10 ? " (WARNING: rounds exhausted)" : "");
        }
    }

    /**
     * 阶段A/B 共用的候选选择：接受 {@code nl ≤ level[k]}，取字典序
     * {@code (level, EDT², index)} 最小的邻居。
     *
     * @param excludeChain 非 null 时（阶段B），排除 {@code state==1} 的整条链
     *                     —— 防止新父指回链前缀再造环。
     * @return 最优邻居下标；-1 = 无接受候选（仅阶段B断环时出现）。
     */
    private static int lexMinNeighbor(int k, int nx, int nz, double[] level, double[] edt,
                                      byte[] excludeChain) {
        int x = k / nz, z = k % nz;
        int best = -1;
        double bestLevel = Double.POSITIVE_INFINITY, bestStraight = Double.POSITIVE_INFINITY;
        for (int[] d : DIRS8) {
            int a = x + d[0], b = z + d[1];
            if (a < 0 || a >= nx || b < 0 || b >= nz) continue;
            int next = a * nz + b;
            if (excludeChain != null && excludeChain[next] == 1) continue;
            double nl = level[next];
            if (!Double.isFinite(nl) || nl > level[k]) continue;   // 严格不上坡（无 ε）
            double nd = edt[next];
            // 严格字典序比较（无 ε 糊带 ⇒ 无 2-环；三元组逐跳严格下降 ⇒ 无任何环）。
            boolean better = nl < bestLevel
                    || (nl == bestLevel && nd < bestStraight)
                    || (nl == bestLevel && nd == bestStraight && next < best);
            if (best < 0 || better) { bestLevel = nl; bestStraight = nd; best = next; }
        }
        return best;
    }

    /**
     * 精确欧几里得距离平方变换（Felzenszwalb & Huttenlocher 2012 的抛物线包络下界法）。
     *
     * <p>输入种子（排水终端）位置，输出每格到最近种子的<b>真实欧氏距离平方</b>。
     * 分两趟一维变换（先列后行，或先行后列），每趟用下界包络线性求解 ⇒ 总 O(n)。</p>
     *
     * <p>为什么必须精确：任何"8 邻域步长累加"都是 octile 度规，其等距线是八边形，
     * 会让平地上的流向偏向对角族（实测轴向仅 43.1% 正确）。只有真正的圆等距线才各向同性。</p>
     */
    private static double[] exactSquaredDistance(int nx, int nz, boolean[] seeds, double[] level) {
        // ⚠ INF 必须【与格数相称】，不能用 1e20 这类极小/极大哨兵：
        //   一维变换要算 (f[q] + q²) − (f[v[k]] + v[k]²)。若 f = 1e20，
        //   加上 q²（最大约 10⁴）在 double 中**完全丢失** ⇒ 交点退化为 0、包络失效、输出全错。
        //   实测教训：用 1e20 时"换了算法结果却逐位不变"，正是这个精度陷阱。
        final double INF = (double) (nx + nz) * (nx + nz) * 4.0 + 1.0;
        double[] f = new double[nx * nz];
        for (int k = 0; k < nx * nz; k++) {
            f[k] = (seeds[k] && Double.isFinite(level[k])) ? 0 : INF;
        }
        double[] tmp = new double[nx * nz];
        // 第 1 趟：沿 z（列）做一维变换
        for (int x = 0; x < nx; x++) {
            double[] line = new double[nz];
            for (int z = 0; z < nz; z++) line[z] = f[x * nz + z];
            double[] out = distanceTransform1D(line);
            for (int z = 0; z < nz; z++) tmp[x * nz + z] = out[z];
        }
        // 第 2 趟：沿 x（行）做一维变换
        for (int z = 0; z < nz; z++) {
            double[] line = new double[nx];
            for (int x = 0; x < nx; x++) line[x] = tmp[x * nz + z];
            double[] out = distanceTransform1D(line);
            for (int x = 0; x < nx; x++) f[x * nz + z] = out[x];
        }
        return f;
    }

    /** 一维距离平方变换：{@code out[i] = min_j (f[j] + (i-j)^2)}，用抛物线下界包络 O(n)。 */
    private static double[] distanceTransform1D(double[] f) {
        int n = f.length;
        double[] out = new double[n];
        int[] v = new int[n];      // 各抛物线的位置
        double[] z = new double[n + 1];  // 各抛物线的分界
        int k = 0;
        v[0] = 0;
        z[0] = -Double.MAX_VALUE;
        z[1] = Double.MAX_VALUE;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + q * (double) q) - (f[v[k]] + v[k] * (double) v[k]))
                    / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + q * (double) q) - (f[v[k]] + v[k] * (double) v[k]))
                        / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.MAX_VALUE;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double d = q - v[k];
            out[q] = d * d + f[v[k]];
        }
        return out;
    }

    private EquilibriumWater() { }
}
