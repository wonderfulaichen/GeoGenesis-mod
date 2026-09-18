package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;

import java.util.Arrays;

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
 *
 * <h2>不变式（写入 AGENTS 的硬约束）</h2>
 * <ol>
 *   <li><b>单调</b>：沿 flowTo 下游，水位单调不增；</li>
 *   <li><b>短板</b>：水位 ≤ 该点紧邻最低旱地（不许悬空水柱）；</li>
 *   <li><b>湖 = 溢出口</b>：湖面 ≡ 其溢出口的水位；</li>
 *   <li><b>海是根</b>：海平面是解算边界条件，不需要独立规则。</li>
 * </ol>
 *
 * <h2>★★ 2026-09-19 实测修正：{@code filledAt} 数据源【同样违反短板】</h2>
 * <p>{@code runWaterGraphProbe}（seed 5436529513624899584，region(0,0)）实测：</p>
 * <pre>
 *   ② 短板违反格：filledAt 源 = 66（最坏 +42.879 块）；旧（河线包络）= 34（最坏 +15.469 块）
 * </pre>
 * <p>⇒ 与文档先前标注为"<b>前一版（已被证伪）</b>"规则的数字<b>完全一致</b>
 * ⇒ {@code filledAt} <b>并未修好短板</b>，它和前一版一样差。</p>
 * <p>根因（本轮已确认）：<b>{@code filledAt} 是【全局量】</b> ——
 * 水位取决于"水最终排到哪里"，那可能在天涯海角 ⇒
 * <b>逐区块复现不出来</b>（实测相邻 region 重叠区 99.6% 不一致、最大差 21.65 块），
 * 且其值可高于紧邻的旱地（短板违反）。</p>
 * <p>⇒ 正解见 {@link WaterField}：<b>水位 = 逐格闭式函数</b>
 * （{@code max(seaLevelY, 低通(地形, smoothWu) + offset)}）——
 * 它跨 region 逐位一致，且因为是局部平均，天然贴近"紧邻最低旱地"。</p>
 *
 * <h2>★ 2026-09-19 T1.8：数据源可插拔</h2>
 * <p>新增 {@link LevelSource} 与对应重载，使数据源可在
 * "priority-flood 溢流高程"与"闭式 {@code W}"之间切换；
 * 原 4 参 {@code solve} 保留为薄包装并委托（<b>逐位一致</b>）。</p>
 *
 * <h2>★ 2026-09-19：删除一段【永不执行】的向上 BFS</h2>
 * <p>原实现入口循环对<b>每个水格无条件赋值</b> {@code level[i]}，随后 BFS 体内的
 * {@code if (!Double.isNaN(level[u])) continue;} 必然命中 ⇒ <b>BFS 体从不执行</b>。
 * 它看起来像"上下游一致性传播"，实际是死代码，会误导后来人（本次重构即被其误导一次）。
 * ⇒ 随重写一并移除。</p>
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

    /**
     * ★ 2026-09-19（T1.8）：<b>水位数据源</b>。
     *
     * <p>实现方负责给出该格的"水位（块）"；返回 {@code NaN} 表示无水位
     * （解算器退化为海平面）。</p>
     *
     * <p>可用实现：{@code field::filledAt}（旧，全局量）、
     * {@code idx -> wf.waterYAt(field.cellCenterX(idx), field.cellCenterZ(idx))}
     * （新，闭式 {@link WaterField}）。</p>
     */
    @FunctionalInterface
    public interface LevelSource {
        double levelAt(int idx);
    }

    private WaterLevelSolver() { }

    // ===================== 主入口 =====================

    /**
     * 统一水位解算（<b>数据源可插拔</b>）。
     *
     * <p>唯一规则：{@code level(水格) = max(seaLevel, 数据源(格))} ——
     * "海是根"由内层 {@code max} 保证，不再需要按类型分规则。</p>
     *
     * @param waterMask 水格判定
     * @param source    水位数据源（见 {@link LevelSource}）
     * @param n         网格格数（{@code cols × rows}）
     * @param seaLevel  海平面（根节点水位）
     * @return 逐格水位（块）；非水格为 {@code NaN}
     */
    public static double[] solve(WaterMask waterMask, LevelSource source, int n, double seaLevel) {
        double[] level = new double[n];
        Arrays.fill(level, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (!waterMask.isWater(i)) continue;
            double w = source.levelAt(i);
            level[i] = Double.isNaN(w) ? seaLevel : Math.max(seaLevel, w);
        }
        return level;
    }

    /**
     * 原签名（数据源 = priority-flood 溢流高程 {@code filledAt}）。
     *
     * <p><b>保留为薄包装并委托给可插拔版</b>，逐位一致：</p>
     * <ul>
     *   <li>成功值：{@code max(seaLevel, max(seaLevel, spill))} ≡ {@code max(seaLevel, spill)}（幂等）；</li>
     *   <li>NaN 分支：{@code g ≤ seaLevel ? seaLevel : g} ≡ {@code max(seaLevel, g)}。</li>
     * </ul>
     *
     * <p>⚠ 实测该数据源<b>违反短板</b>（见类文档）；新代码请优先用 {@link #solve(WaterMask, LevelSource, int, double)}
     * 并传入 {@link WaterField} 驱动。</p>
     *
     * @param field     汇流场（提供 priority-flood 溢流高程）
     * @param ground    地面高程（最终地形口径）
     * @param waterMask 水格判定
     * @param seaLevel  海平面（根节点水位）
     */
    public static double[] solve(FlowField field, GroundSampler ground, WaterMask waterMask,
                                 double seaLevel) {
        int n = field.cols() * field.rows();
        return solve(waterMask, idx -> {
            double spill = field.filledAt(idx);
            if (!Double.isNaN(spill)) return spill;
            double g = ground.at(idx);
            return g <= seaLevel ? seaLevel : g;      // ≡ max(seaLevel, g)
        }, n, seaLevel);
    }
}
