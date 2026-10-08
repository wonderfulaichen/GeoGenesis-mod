package com.geogenesis.worldgen.hydrology.model;

/**
 * <b>平坦区流向的破平局规则（A/B 开关，生产默认不参与）</b>。
 *
 * <h2>要解决的问题</h2>
 * <p>{@link EquilibriumWater} 用 minimax 洪泛求平衡水面。在<b>平坦高原</b>上，
 * 一大片格子的 {@code level} 完全相等 ⇒ {@code ∇η = 0} ⇒
 * {@link ContinuousRiverNetwork} 的 Manning 速度场归零 ⇒ 流线要么原地打转、
 * 要么只能靠"到最近排水终端的距离"（EDT）硬定方向 ⇒
 * 图上出现<b>放射状喷泉</b>与<b>孤立点</b>。用户判据："河流流动一点就断开了"。</p>
 *
 * <h2>三条候选路线</h2>
 * <ol>
 *   <li><b>{@code edgepsilon}</b>（默认推荐，待实测确认）：
 *       沿 parent 图给水面加一个<b>极小单调增量</b>，
 *       {@code w[k] ≥ w[parent[k]] + ε}，从而处处严格单调 ⇒ 方向有定义。
 *       <br>依据：FastFlow (CGF 43(7), PG2024) Algorithm 6
 *       {@code w[c] ← max(z[c], w[recipient of c] + 2^(i-1)·ϵ)}。
 *       论文原话：<i>"we optionally allow a small slope ϵ on the water surface as
 *       this is sometimes required in hydrology applications"</i>。
 *       <br><b>关键区别</b>：FastFlow 的 ε 加在<b>受主图</b>上，是"沿流向的极小坡"，
 *       而不是给地形加噪声 —— 后者会改变地形，后者只影响<b>水面</b>。</li>
 *   <li><b>{@code noise}</b>：给床面加种子化的确定性微噪声，打破平局。
 *       依据：FastFlow §"stochastic selection"（按高差加权随机选受主）。
 *       <br>⚠ 代价：噪声进入 {@code bed} 会影响<b>所有</b>下游量（湖/侵蚀判定），
 *       故幅度必须极小，且要验证不改变分档结果。</li>
 *   <li><b>{@code baseline}</b>：现状（未决格用 bed、有坡度就用坡度）。</li>
 * </ol>
 *
 * <h2>★ 契约：默认 {@code baseline} ⇒ 与改动前【逐位一致】</h2>
 * <p>本类只提供<b>机制</b>与<b>开关</b>，是否采纳由
 * {@link FlatFlowDiagnosisProbe} 的同参 A/B 实测决定 ——
 * 在实测之前不得默认开启（本项目铁律：先量，再改）。</p>
 */
public final class DirectionRule {

    /**
     * 当前破平局模式。
     *
     * <p>★ 2026-10-09【加系统属性入口】：原为 {@code volatile String mode = "baseline"}，
     * 且**只有探针**调 {@code setMode} ⇒ 生产路径永远是 {@code baseline}
     * ⇒ {@code epsilonSlope()} 恒 false ⇒ "实测无效"的结论**从未在生产口径上验证过**。</p>
     *
     * <p>现读 {@code -Dhydro.directionRule=baseline|edgepsilon|noise}，
     * 使生产/探针可在同一入口 A/B。默认 {@code baseline} = 原行为（零变更）。</p>
     */
    private static volatile String mode =
            System.getProperty("hydro.directionRule", "baseline");

    /**
     * ε 坡度增量（<b>水面单位 = 块</b>）。
     *
     * <p>取值依据：必须<b>大于</b>浮点噪声（否则等值面仍是平的），
     * 又必须<b>远小于</b>一个方块（否则改变湖面/水深）。
     * 1e-6 块 = 0.001 毫米，两者相差 6 个数量级 ⇒ 只破平局、不改量。</p>
     */
    public static final double EPSILON = 1e-6;

    private DirectionRule() { }

    public static void setMode(String m) {
        mode = (m == null) ? "baseline" : m.toLowerCase();
    }

    public static String mode() { return mode; }

    /** 是否启用 ε 坡度（唯一改变水面数值的模式）。 */
    public static boolean epsilonSlope() { return "edgepsilon".equals(mode); }

    /** 是否给床面加确定性微噪声。 */
    public static boolean noise() { return "noise".equals(mode); }

    /**
     * 破平局后处理：把 {@code level} 沿 {@code parent} 施加 ε 单调增量。
     *
     * <p><b>只改水面，不改床面</b>（依据 FastFlow：ε 是水面性质，不是地形性质）。
     * 对每格取 {@code w[k] = max(level[k], w[parent[k]] + ε)}，
     * 沿 parent 链自链端（海/湖，parent=-1）向源迭代。
     *
     * <p><b>为什么可能安全</b>：只有当 {@code level[k] ≥ level[parent] + ε} 时数值才变，
     * 而 minimax 保证 {@code level[k] ≥ level[parent]}（沿链不降）⇒
     * 受影响<b>仅限</b>水位恰等于（或极接近）parent 的平坦格 —— 正是要修的那批。
     * 湖面（level 远高于 parent）不受影响。</p>
     *
     * <p><b>为什么不可能成环</b>：parent 图已由 {@link EquilibriumWater} 保证无环
     * （严格字典序势函数），故按链迭代是良定义的；实现上用"迭代松弛 + 轮数上限"
     * 兜底，即使 parent 意外成环也只会收敛到有限轮而不死循环。</p>
     */
    public static void applyEpsilonSlope(double[] level, int[] parent, boolean[] seeds, int cells) {
        int maxRounds = 64;
        for (int round = 0; round < maxRounds; round++) {
            boolean changed = false;
            for (int k = 0; k < cells; k++) {
                if (seeds[k]) continue;
                int p = parent[k];
                if (p < 0 || p == k) continue;
                if (!Double.isFinite(level[p])) continue;
                double want = level[p] + EPSILON;
                if (level[k] < want) {
                    level[k] = want;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }
}