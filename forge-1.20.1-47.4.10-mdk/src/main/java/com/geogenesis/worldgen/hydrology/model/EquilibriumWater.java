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
