package com.geogenesis.worldgen.hydrology.model;

/**
 * <h1>河流深度与横断面（纯函数，零 MC 依赖）</h1>
 *
 * <p>本类只定义三组数值关系：<b>最终全宽 → 目标中心水深</b>、<b>横向距离 → 目标水深</b>、
 * <b>目标水深 → 河床 / 整数水柱</b>。不读世界、不写世界、不消费随机数，
 * 也不改骨架坐标与宽度剖面。</p>
 *
 * <h2>符号（方案 §3 的命名）</h2>
 * <ul>
 *   <li>{@code E} = 侵蚀后地形（列生成入口拿到的地面高度）；</li>
 *   <li>{@code S} = 现有权威水面；</li>
 *   <li>{@code W} = {@link RiverWidthProfile} 定稿的<b>名义全宽</b>（不是 bank jitter 后的逐列宽度）；</li>
 *   <li>{@code Hc} = 目标中心水深，{@code H(r)} = 横向目标水深，{@code B} = 最终河床，
 *       {@code C = E − B} = 下挖量（{@code C} 不是水深，二者不可互相替代）。</li>
 * </ul>
 *
 * <h2>★ 这不是水力学定律</h2>
 * <p>本类的常量与曲线是<b>游戏尺度的候选标定</b>（方案 §3.1 原话：「这些是游戏尺度的候选标定，
 * 不是已验证的水力学定律；先出断面图再定」）。它不求流量、不引入 Manning / PDE，
 * 也不声称与真实河流一致；唯一判据是生产断面图与实机目检。
 * <b>任何「已物理正确」的表述都是错的。</b></p>
 *
 * <h2>规则（几何约定，非物理）</h2>
 * <ol>
 *   <li><b>中心水深</b>：{@code u = clamp((W−1)/11, 0, 1)}，{@code Hc = 1 + (Hmax−1)·u^0.75}
 *       ⇒ {@code W=1 → 1}、{@code W=12 → 4}，中间平缓展宽（{@code W=4 ≈ 2.13}、{@code W=6 ≈ 2.66}）。</li>
 *   <li><b>横断面</b>：{@code r = clamp(|d|/effectiveHalf, 0, 1)}，
 *       {@code H(r) = Hedge + (Hc−Hedge)·(1−r²)²}，{@code Hc=max(0,centerDepth)}，
 *       {@code Hedge = min(1,Hc)}；浅接口不反向加深，{@code Hc=0} 明确表示无水。
 *       ⇒ 中心最深、两岸平滑变浅；{@code r=0} 与 {@code r=1} 处导数都为 0
 *       ⇒ 中心无 V 型折痕，岸缘无阶跃（对齐 AGENTS.md 硬约束 2/4）。</li>
 *   <li><b>只下挖</b>：{@code B = min(E, S − H)}，{@code C = E − B ≥ 0}。
 *       {@code E} 已低于目标床面时<b>保留原地形，不填高</b>
 *       ⇒ {@code Hmax} 只限「新增的目标中心水深」，<b>不保证</b>天然深槽实际水深不超过 {@code Hmax}。</li>
 *   <li><b>整数水柱</b>：{@code blocks = max(0, floor(S) − floor(B))}。
 *       连续水深 0.55 但 {@code floor(S) == floor(B)} ⇒ 实际 <b>0</b> 块，不放宽门槛去掩盖。</li>
 * </ol>
 *
 * <h2>边界契约（违约不静默掩盖）</h2>
 * <table border="1">
 *   <caption>非有限 / 退化输入的处理</caption>
 *   <tr><th>输入</th><th>行为</th></tr>
 *   <tr><td>{@code nominalFullWidth} 非有限（NaN/±∞）</td><td>退回 {@link #EDGE_DEPTH}（最小值，保守不放深）</td></tr>
 *   <tr><td>{@code nominalFullWidth} 超出 {@code [1,12]}</td><td>按 {@code clamp} 饱和到端点</td></tr>
 *   <tr><td>{@code distance} 为负</td><td>取绝对值（距离是度量 ⇒ 左右对称必须严格成立）</td></tr>
 *   <tr><td>{@code effectiveHalf ≤ 0} 或非有限</td><td>{@code r = 1}（按岸缘处理：最浅，不挖深沟）</td></tr>
 *   <tr><td>{@code distance > effectiveHalf}（穿块补点）</td><td>同上 {@code r = 1} ⇒ 仍是浅岸水深</td></tr>
 *   <tr><td>{@code centerDepth} 在 (0,1) 内</td><td>保留浅接口深度，岸缘不比中心更深</td></tr>
 *   <tr><td>{@code centerDepth ≤ 0}</td><td>返回 0，明确无水；消费者必须据此跳过放水</td></tr>
 *   <tr><td>{@code centerDepth} 非有限</td><td>退回 {@link #EDGE_DEPTH}</td></tr>
 *   <tr><td>{@link #bed} 任一参数非有限</td><td>原样传播非有限值（{@code NaN} / {@code ±∞}）：
 *       既不夹回地面 {@code E}，也不改成 0 ⇒ 让调用方先暴露问题（实测
 *       {@code bed(70, −∞, 2) == −∞}，不是 {@code NaN}）</td></tr>
 *   <tr><td>{@link #waterBlocks} 任一参数非有限</td><td>0 块</td></tr>
 * </table>
 *
 * <h2>不在本轮范围</h2>
 * <p>接点过渡（串联 / 汇流 / 湖口 / 跨区）所需的混合曲线<b>本轮不实现</b>；
 * 生产接入另立任务。本类不生成任何方块，也不决定群系。</p>
 */
final class RiverCrossSection {

    /** 中心水深上限（格）= 全图最大全宽 12 格时的目标中心水深。 */
    static final double CENTER_DEPTH_MAX = 4.0;

    /** 普通河段岸缘目标水深；浅接口以其非负中心深度为岸深，不是全局水深下限。 */
    static final double EDGE_DEPTH = 1.0;

    /** 参与标定的全宽上限（格）：与 {@link RiverCenterlines} 的 12 格上限一致。 */
    static final double MAX_FULL_WIDTH = 12.0;

    /** 标定的最小全宽（格）：真源头。 */
    static final double MIN_FULL_WIDTH = 1.0;

    /** 宽度展宽指数（{@code u^0.75}：前段涨得快、后段趋缓）。 */
    static final double WIDTH_EXPONENT = 0.75;

    private RiverCrossSection() { }

    /**
     * 名义全宽（格）→ 目标中心水深（格）。
     *
     * <pre>{@code
     *   u  = clamp((W − 1) / 11, 0, 1)
     *   Hc = 1 + (CENTER_DEPTH_MAX − 1) · u^0.75
     * }</pre>
     *
     * <p>输入是 {@link RiverWidthProfile} 定稿的<b>名义全宽</b>，不是 bank jitter 后的逐列宽度
     * ⇒ 中心深度不跟着岸宽噪声跳动。</p>
     *
     * @param nominalFullWidth 名义全宽（格）；非有限 ⇒ {@link #EDGE_DEPTH}
     * @return {@code [1, CENTER_DEPTH_MAX]} 内的目标中心水深
     */
    static double centerDepth(double nominalFullWidth) {
        if (!Double.isFinite(nominalFullWidth)) return EDGE_DEPTH;
        double u = clamp01((nominalFullWidth - MIN_FULL_WIDTH) / (MAX_FULL_WIDTH - MIN_FULL_WIDTH));
        return EDGE_DEPTH + (CENTER_DEPTH_MAX - EDGE_DEPTH) * Math.pow(u, WIDTH_EXPONENT);
    }

    /**
     * 横向距离 → 目标水深（圆滑 U 形断面，岸浅心深）。
     *
     * <pre>{@code
     *   Hc   = max(0, centerDepth)
     *   edge = min(1, Hc)
     *   r    = clamp(|distance| / effectiveHalf, 0, 1)
     *   H(r) = edge + (Hc − edge) · (1 − r²)²
     * }</pre>
     *
     * <p>{@code r=0} 最深、{@code r=1} 为 {@code min(1,Hc)}；两端导数均为 0。
     * 浅湖/海接口可传入小于 1 的中心深度，此时整条断面保留该浅深度。
     * {@code distance > effectiveHalf}（穿块补连通点）取 {@code r=1}，不因补点挖深沟。</p>
     *
     * <p>返回 0 是明确的无水标记：消费者必须用 {@code targetDepth > 0} 判定可放水，
     * 不能仅由 {@link #bed} 和 {@link #waterBlocks} 推断湿列（天然深槽仍会保留）。</p>
     *
     * @param centerDepth   目标中心水深；允许浅接口值；非正数归零，非有限值退回 {@link #EDGE_DEPTH}
     * @param distance      到骨架中心线的横向距离（格，带符号亦可，内部取绝对值）
     * @param effectiveHalf 有效半宽（格）；{@code ≤0} 或非有限 ⇒ 按岸缘
     * @return 该列的非负目标水深（格），0 明确表示无水
     */
    static double targetDepth(double centerDepth, double distance, double effectiveHalf) {
        double center = Double.isFinite(centerDepth) ? Math.max(0.0, centerDepth) : EDGE_DEPTH;
        double edge = Math.min(EDGE_DEPTH, center);
        double r = radiusFraction(distance, effectiveHalf);
        double fall = 1.0 - r * r;                 // 岸缘 0、中心 1
        return edge + (center - edge) * fall * fall;
    }

    /**
     * 目标水深 → 最终河床（<b>只下挖，不填高</b>）。
     *
     * <pre>{@code
     *   B = min(E, S − targetDepth)
     *   C = E − B ≥ 0
     * }</pre>
     *
     * <p>{@code E} 已低于 {@code S − targetDepth}（天然深槽）⇒ 原样返回 {@code E}，
     * 不做任何抬高。因此目标中心水深只约束<b>新增下挖</b>，不是实际水深的硬上限。</p>
     *
     * @param eroded      侵蚀后地形高度 {@code E}
     * @param surface     权威水面 {@code S}
     * @param targetDepth 该列目标水深 {@code H(r)}
     * @return 最终河床高度 {@code B ≤ E}；任一参数非有限 ⇒ 原样传播非有限值（不静默回退成 {@code E}）
     */
    static double bed(double eroded, double surface, double targetDepth) {
        return Math.min(eroded, surface - targetDepth);
    }

    /**
     * 已覆盖候选河列的湿床：在几何床面之下保留至少一块整数水柱的空间。
     *
     * <p>仅供候选开关已启用且 covered 的河列使用；输入应为有限的游戏高度。
     * 使用实际列水面而非 coarse 水面：{@code B=min(bed(E,S,H),floor(S)-1e-6)}。
     * 余量避免 {@code E-(E-B)} 写回时舍入到整数边界；天然深槽仍保留，不填高。
     * 这是整数铺水的几何保证，不是放水许可：调用方仍须保留原有
     * {@code S>B+0.5} 门槛及覆盖/河湖身份判定，不得扩湿未覆盖列。</p>
     *
     * @param eroded 侵蚀后地形 E
     * @param surface 实际列权威水面 S
     * @param targetDepth 横向目标水深 H；不满足 H&gt;0（含 NaN）直接返回 E，不雕刻
     * @return 只下挖的湿床高度；不替代数学几何函数 {@link #bed}
     */
    static double wetBed(double eroded, double surface, double targetDepth) {
        if (!(targetDepth > 0)) return eroded;
        return Math.min(bed(eroded, surface, targetDepth), Math.floor(surface) - 1e-6);
    }

    /**
     * 水面与河床 → 实际整数水柱（格）。
     *
     * <pre>{@code
     *   blocks = max(0, floor(S) − floor(B))
     * }</pre>
     *
     * <p>与生产放水口径一致，<b>不放宽</b> {@code S > B + 0.5} 之类的门槛：
     * {@code S=69.90, B=69.35}（连续水深 0.55）时二者 {@code floor} 都是 69 ⇒ 实际 0 块。</p>
     *
     * @param surface 水面高度 {@code S}
     * @param bed     河床高度 {@code B}
     * @return 水柱块数（{@code ≥ 0}）；任一参数非有限 ⇒ 0
     */
    static int waterBlocks(double surface, double bed) {
        if (!Double.isFinite(surface) || !Double.isFinite(bed)) return 0;
        double span = Math.floor(surface) - Math.floor(bed);
        if (span <= 0) return 0;
        return span >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) span;
    }

    /** {@code clamp(|distance| / effectiveHalf, 0, 1)}；退化半宽按岸缘处理。 */
    private static double radiusFraction(double distance, double effectiveHalf) {
        if (!Double.isFinite(distance) || !Double.isFinite(effectiveHalf) || effectiveHalf <= 0) return 1.0;
        double d = Math.abs(distance);
        if (d <= 0) return 0.0;
        double r = d / effectiveHalf;
        return r >= 1.0 ? 1.0 : r;
    }

    /** {@code clamp(v, 0, 1)}；NaN 由 {@code v <= 0 → 0} 之外的分支保不住，调用方须先筛非有限。 */
    private static double clamp01(double v) {
        if (!(v > 0)) return 0.0;
        return v >= 1.0 ? 1.0 : v;
    }
}
