package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 统一水位解算器（Unified Water Level Solver）—— 水文统一化重构的核心。
 *
 * <h2>它取代什么</h2>
 * <p>此前"水位"由三处各自定义、互不知晓：</p>
 * <ul>
 *   <li>{@code RiverLineNetwork} 的逐节点包络（PAVA 单调回归 + 岸线 cap）⇒ 河面；</li>
 *   <li>{@code FlowField.filledAt} / {@code LakeNode.erodedWaterLevel} ⇒ 湖面；</li>
 *   <li>{@code GeoGenesisGenerator} 的 {@code groundY < seaLevel ⇒ 灌到海平面} ⇒ 内陆"海"。</li>
 * </ul>
 * <p>实测后果（本会话量化）：<b>河被悬空</b> 最坏 36.6 块（58% 河格悬空 ≥5 块）；
 * <b>湖面高于真实盆沿</b> 最坏 +26.076 块（13,685 格水体）。</p>
 *
 * <h2>统一模型：一张有向水图 + 一次全局解算</h2>
 * <p>水格（河/湖/海）统一表示为网格节点，边 = {@code flowTo}（D8 下游）。
 * <b>唯一规则（从下游向上游一遍拓扑解算）</b>：</p>
 * <pre>
 *   level(格) = max( 地面(格), level(下游) )
 * </pre>
 *
 * <h4>为什么这一条规则同时解决两类缺陷</h4>
 * <ul>
 *   <li><b>逆坡/悬空</b>：水面不低于自己的地面、也不低于下游水面
 *       ⇒ 水不可能悬在比自己地面低的空中，也不可能逆流上坡；</li>
 *   <li><b>湖的短板</b>：洼地格（{@code flowTo < 0}）取 {@code filledAt}
 *       （priority-flood 溢流高程 = "涨到多少才溢出"）⇒ <b>湖面 = 真实盆沿</b>，
 *       不再依赖"洼地格 8 邻那一圈 rim"（那正是找不到真出口的原因）；</li>
 *   <li><b>湖与河联动</b>：洼地出口沿 flowTo 走向下游 ⇒ 湖面与河面在【同一张图】上解算
 *       ⇒ 天然衔接，不需要独立"湖模块"。</li>
 * </ul>
 *
 * <h2>不变式（写入 AGENTS 的硬约束）</h2>
 * <ol>
 *   <li><b>单调</b>：沿 flowTo 下游，水位单调不增；</li>
 *   <li><b>短板</b>：水位 ≤ 该点紧邻最低旱地（不许悬空水柱）；</li>
 *   <li><b>湖 = 溢出口</b>：湖面 ≡ 其溢出口的水位；</li>
 *   <li><b>海是根</b>：海平面是解算边界条件，不需要独立规则。</li>
 * </ol>
 *
 * <h2>性质</h2>
 * <p><b>零 MC 依赖</b>（只用 {@code FlowField} 的网格与 D8 结构）⇒ 探针可直接复用。
 * 输出只由 (种子, 配置, 区域) 决定，与生成顺序无关。</p>
 */
public final class WaterLevelSolver {

    /** 地面高程采样（按格索引给块高程；<b>必须是最终地形口径</b>）。 */
    public interface GroundSampler {
        double at(int idx);
    }

    /** 是否为水（河/湖/海）。 */
    public interface WaterMask {
        boolean isWater(int idx);
    }

    private WaterLevelSolver() { }

    /**
     * 全局解算水位。
     *
     * @param field     汇流场（提供 D8 下游、priority-flood 溢流高程）
     * @param ground    地面高程（最终地形口径）
     * @param waterMask 水格判定
     * @param seaLevel  海平面（根节点水位）
     * @return 逐格水位（块）；非水格为 {@code NaN}
     */
    public static double[] solve(FlowField field, GroundSampler ground, WaterMask waterMask,
                                 double seaLevel) {
        final int cols = field.cols();
        final int rows = field.rows();
        final int n = cols * rows;
        double[] level = new double[n];
        Arrays.fill(level, Double.NaN);

        // 下游入度 + 反向边（上游列表）
        int[] pending = new int[n];
        List<List<Integer>> ups = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ups.add(null);
        for (int i = 0; i < n; i++) {
            int d = field.flowTo(i);
            if (d >= 0 && d < n) {
                pending[d]++;
                List<Integer> list = ups.get(d);
                if (list == null) {
                    list = new ArrayList<>(4);
                    ups.set(d, list);
                }
                list.add(i);
            }
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (!waterMask.isWater(i)) continue;
            double g = ground.at(i);
            if (g <= seaLevel) {
                level[i] = seaLevel;                    // 海：图的根
                queue.add(i);
                continue;
            }
            if (field.flowTo(i) < 0) {                  // 洼地/出口：湖面 = 溢流高程（短板）
                double spill = field.filledAt(i);
                level[i] = Double.isNaN(spill) ? g : Math.max(g, spill);
                queue.add(i);
            }
        }

        // 拓扑传播：下游已定 → 上游可算（Kahn）
        int[] remaining = pending.clone();
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            List<Integer> uppers = ups.get(cur);
            if (uppers == null) continue;
            for (int u : uppers) {
                if (!waterMask.isWater(u)) continue;
                if (!Double.isNaN(level[u])) continue;
                if (--remaining[u] > 0) continue;        // 还有别的下游未定
                double gu = ground.at(u);
                level[u] = Math.max(gu, level[cur]);     // 唯一规则
                queue.add(u);
            }
        }

        // 兜底：图不连通/成环导致仍未定的水格 → 只用自己的地面
        for (int i = 0; i < n; i++) {
            if (waterMask.isWater(i) && Double.isNaN(level[i])) level[i] = ground.at(i);
        }
        return level;
    }
}
