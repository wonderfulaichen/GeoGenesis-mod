package com.geogenesis.worldgen.terrain.candidate;

/**
 * <b>从旧海陆装配链移植过来的四个形态概念</b>（方案 {@code ocean-unified-terrain} 步 4）。
 *
 * <h3>★ 本类只有概念与参数，不含任何旧类的引用</h3>
 * <p>全部逻辑都是从 {@code OceanAssemblyCandidate} 与 {@code OceanFeatures} 的
 * <b>行为</b>重新表述的纯函数，<b>不 import / 不 new / 不反射</b>那两个类。
 * 移植的判据是<b>语义等价</b>（沿穿岸测线能读出同样的地理序列），
 * 不是数值等价 —— 结构变了，逐位相同做不到，也不该追求。</p>
 *
 * <h3>四个概念</h3>
 * <table border="1">
 *   <caption>移植清单</caption>
 *   <tr><th>#</th><th>概念</th><th>旧出处</th><th>本类对应的纯函数</th></tr>
 *   <tr><td>C1</td><td>cliffFactor：沙岸/崖岸连续渐变</td>
 *       <td>{@code cliffFactor()} + {@code shoreShape()}</td>
 *       <td>{@link #cliffFactor(double, double)} + {@link #shoreShape(double, double)}</td></tr>
 *   <tr><td>C2</td><td>岸坡宽度随陆高增长</td>
 *       <td>{@code shoreWidth(landB)}</td>
 *       <td>{@link #shoreWidthFactor(double)}</td></tr>
 *   <tr><td>C3</td><td>岸线位置不随 cliff 移动（端点条件）</td>
 *       <td>{@code H(0)=0}、{@code H(1)=landB}</td>
 *       <td>{@link #shoreProfile(double, double)} 保证同样端点</td></tr>
 *   <tr><td>C4</td><td>构造带门控（洋中脊/海山沿边界分布）</td>
 *       <td>{@code ridgeGate} = smoothPos(−stress)·gaussian(dist)</td>
 *       <td>{@link #tectonicGate(double, double, double)}</td></tr>
 * </table>
 *
 * <h3>为什么这些要"移植"而不是"留用"</h3>
 * <p>旧类的这几个函数绑定了 {@code edge}（c 空间的一维标量）作为主坐标，
 * 以及"先把陆地算完再套海侧剖面"的分段结构。新架构里海洋配方是票槽池的成员，
 * 拿到的是 {@code (x, z)} 世界坐标、没有任何 {@code edge}。
 * 所以必须重述成<b>不依赖 edge</b> 的形式：
 * 把 {@code edge} 换成"配方自己能算的局部场"，把"分段"换成"单一连续公式"。</p>
 *
 * <p>只读：不引用旧类、不改任何生产对象。所有常量都是<b>概念参数</b>，与旧类同名但独立。</p>
 */
final class OceanMorphConcepts {

    // ============================================================ C1/C3：cliffFactor 与岸型曲线

    /**
     * cliffFactor 的噪声窗口（作用在 {@code raw ∈ [−1,1]} 上）。
     *
     * <p><b>与旧类同值</b>：旧实现用对称窗口 {@code [−0.45, 0.45]}，
     * 因为非对称窗口 {@code [−0.2, 0.6]} 实测给出 57% 沙岸 / 29% 崖岸（崖岸偏少，
     * 而全球估计沙岸约 30–40%、岩岸 50–60%）。取对称使中值映射到 t=0.5。</p>
     */
    static final double CLIFF_LOW = -0.45;
    static final double CLIFF_HIGH = 0.45;

    /**
     * cliffFactor：{@code 0 = 沙滩，1 = 崖壁}，由低频噪声给出，<b>连续无跳变</b>。
     *
     * @param rawNoise 已归一化到 {@code [-1,1]} 的低频噪声（波长约 900 块 = 一段岸线的尺度）
     */
    static double cliffFactor(double rawNoise) {
        double t = (rawNoise - CLIFF_LOW) / (CLIFF_HIGH - CLIFF_LOW);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);          // smoothstep：端点一阶导为 0
    }

    /**
     * 岸型形状曲线 {@code shape(t)}：{@code t ∈ [0,1] → [0,1]}，端点条件
     * {@code shape(0)=0}、{@code shape(1)=1}。
     *
     * <h3>两种岸的物理差别（移植的语义）</h3>
     * <pre>
     * 沙岸：浅水 → 滩面（5–10°，2–3 m）→ 滩肩（高 2–5 m）→ 海岸平原（缓升）
     * 崖岸：浅水 → 海蚀平台（高出海面 0.5–5 m）→ 崖面（近垂直）→ 崖顶台地
     * </pre>
     * <p>关键区别是<b>抬升的集中程度</b>：沙岸分散在整个区间，崖岸集中在末段。
     * 所以用 {@code shape = slope·smoothstep(t) + (1−slope)·t²}，
     * {@code slope = lerp(1, 0.55, cliff)}：</p>
     * <ul>
     *   <li>{@code cliff=0} ⇒ 纯 smoothstep：岸边缓起、中段最陡、近内陆再放缓；</li>
     *   <li>{@code cliff=1} ⇒ 更多权重给 {@code t²}：前段更平（海蚀平台），抬升集中在中后段（崖面）。</li>
     * </ul>
     * <p><b>C3 的保证</b>：两条曲线在 t=0/1 处都满足端点条件 ⇒
     * 改变 cliffFactor <b>不会移动岸线</b>，只改崖/滩形态。这是旧类最重要的不变式，必须保留。</p>
     */
    static double shoreShape(double t, double cliff) {
        double c = t < 0 ? 0 : (t > 1 ? 1 : t);
        double s = c * c * (3 - 2 * c);
        double slope = 1.0 + (0.55 - 1.0) * cliff;      // lerp(1, 0.55, cliff)
        return slope * s + (1 - slope) * c * c;
    }

    // ============================================================ C2：岸坡宽度随陆高增长

    /** 岸段基础宽度（无量纲，与旧类 {@code BASE_SHORE_W} 同值）。 */
    static final double BASE_SHORE_W = 0.085;
    /** 陆地高度达到这个值（格）时宽度倍数吃满。 */
    static final double RETREAT_REF_BLOCKS = 110.0;
    /**
     * 宽度最多放大的倍数。
     *
     * <p><b>与旧类同值 0.45</b>（旧类记录：曾取 0.90，实测 4.2% 像素被压低超过 20 格、
     * 最大压低 159 格，故收到 0.45）。</p>
     */
    static final double RETREAT_GAIN = 0.45;

    /**
     * 岸坡宽度因子：{@code 1 .. 1+RETREAT_GAIN}，随陆地高度饱和增长。
     *
     * <h3>为什么"越高过渡越不能像刀切"要靠这个</h3>
     * <p>山越高、岸坡越长，但<b>坡度的增长慢于高度</b>：
     * 300 格的山只比 120 格的陡 1.6 倍，而不是 2.5 倍。
     * 地理上这就是"高山海岸后退、由麓坡消化高差"。</p>
     *
     * <p>注意本函数只返回<b>倍数</b>，不含 {@link #BASE_SHORE_W}——
     * 因为新架构里"岸段宽度"要由配方自己的空间尺度决定（没有 edge 可用），
     * 调用方负责把倍数乘到自己的宽度基准上。</p>
     */
    static double shoreWidthFactor(double landBlocks) {
        double sat = Math.min(1.0, Math.max(0.0, landBlocks) / RETREAT_REF_BLOCKS);
        return 1.0 + RETREAT_GAIN * sat;
    }

    // ============================================================ C3：完整岸段剖面

    /** 沿岸下沉（绝对格数，中点峰值、两端为 0）。与旧类同值。 */
    static final double COAST_SINK_BLOCKS = 1.0;

    /**
     * 完整岸段剖面：{@code t ∈ [0,1] → [0, landBlocks]}，含沿岸下沉。
     *
     * <pre>
     *   H(t) = landBlocks · shape(t) − COAST_SINK_BLOCKS · 4t(1−t)
     * </pre>
     * <p>端点：{@code H(0)=0}（岸线高度 0）、{@code H(1)=landBlocks}（与内陆数值相等）。
     * 下沉项 {@code 4t(1−t)} 在两端为 0，所以<b>不破坏端点条件</b>，
     * 只在岸段中点附近压出 1 格浅沟。</p>
     *
     * <h3>为什么是"单一连续公式"而不是"封顶 + 回填"</h3>
     * <p>旧类记录：中间试过三种封顶/回填方案全部失败，根因是它们都有<b>两个高度来源</b>
     * 在拼接，只要两处对"岸段在哪结束"的理解差 1 个像素就产生尖峰（实测 P99 梯度 29、
     * 峰顶被顶到 299 格）。单一公式<b>不可能错位</b>。</p>
     *
     * @param t          归一化岸段位置（0=岸线，1=内陆起点）
     * @param landBlocks 内陆高度（格），必须 ≥ 0
     * @param cliff      {@link #cliffFactor(double)} 的输出
     */
    static double shoreProfile(double t, double landBlocks, double cliff) {
        double c = t < 0 ? 0 : (t > 1 ? 1 : t);
        double h = Math.max(0.0, landBlocks) * shoreShape(c, cliff);
        double sink = COAST_SINK_BLOCKS * 4.0 * c * (1.0 - c);
        return h - sink;
    }

    // ============================================================ C4：构造带门控

    /**
     * 构造带门控：{@code 离散强度 × 到边界距离的高斯包络}。
     *
     * <h3>移植的语义（旧 {@code OceanFeatures.ridgeGate}）</h3>
     * <p>旧类用两个量相乘，缺一不可：</p>
     * <ul>
     *   <li><b>离散强度</b> {@code smoothPos(−stress)}：{@code stress<0}（离散）→ 1，
     *       {@code >0}（汇聚）→ 0。<b>区域尺度</b>的量，只说"这片海域是离散体制"，
     *       本身不给出"脊在哪"。</li>
     *   <li><b>距离包络</b> {@code gaussian(dist, σ)}：洋中脊是<b>沿边界线的带</b>。</li>
     * </ul>
     * <p>旧类记录的关键教训：<b>只乘 stress 会让"洋中脊"铺满整个离散海盆</b>
     * （实测 RIDGE 占比 11.29%，远超边界带面积）—— 因为应力场是区域量、不是边界量。</p>
     *
     * <h3>★ 为什么离散强度要"归一化到饱和"而不是直接 smoothstep</h3>
     * <p>首版写成 {@code t = pos/(pos+0.15); discrete = smoothstep(t)}，实测
     * {@code stress=−1} 时只得 {@code 0.9534} —— 因为 {@code smoothstep} 在
     * {@code t=0.87} 处本来就只有 0.953。虽然连续，但意味着<b>强离散区永远拿不到满值</b>，
     * 门控整体被压 4.7%，与旧语义（{@code smoothPos} 在强离散时饱和到 1）不一致。</p>
     * <p>改为：先按 {@code SAT_STRESS} 归一化，再钳到 1，最后 smoothstep 只负责<b>零附近的软化</b>。
     * 于是 {@code |stress| ≥ SAT_STRESS} 时严格为 1（与旧语义一致），
     * 且 {@code stress=0} 处值与一阶导都连续。</p>
     *
     * <h3>为什么用 {@code dist} 做单调包络是安全的</h3>
     * <p>本项目硬约束：{@code dist}（到板块边界距离）<b>只能用于衰减，绝不能作噪声坐标</b>
     * —— 其等值线平行于 Voronoi 边界且绕板闭合成同心环，必然产生平行波纹带。
     * 本函数只把它喂给 {@code exp(−d²/2σ²)}（单调衰减），符合约束。</p>
     *
     * @param stress  构造应力（负 = 离散，正 = 汇聚）
     * @param dist    到板块边界的距离（wu）
     * @param sigma   高斯半宽（wu）
     */
    static double tectonicGate(double stress, double dist, double sigma) {
        double discrete = discreteStrength(stress);
        if (discrete <= 0.0) return 0.0;
        double g = Math.exp(-(dist * dist) / (2.0 * sigma * sigma));
        return discrete * g;
    }

    /**
     * 应力在多大强度时视为"完全离散"。取 0.15 与旧类 {@code STRESS_POS_EPS} 同量级。
     */
    static final double SAT_STRESS = 0.15;

    /**
     * 离散强度 {@code ∈ [0,1]}：{@code stress ≤ −SAT_STRESS} → 1，{@code stress ≥ 0} → 0。
     *
     * <p>在 {@code [−SAT_STRESS, 0]} 内用 smoothstep 软化，保证
     * {@code f(0)=0}、{@code f'(0)=0}、{@code f(饱和点)=1}、{@code f'(饱和点)=0}。</p>
     */
    static double discreteStrength(double stress) {
        double d = -stress;                       // 正值 = 离散
        if (d <= 0.0) return 0.0;                 // 汇聚或中性
        double t = d / SAT_STRESS;                // 0..1+
        if (t >= 1.0) return 1.0;                 // 饱和：强离散严格为 1
        return t * t * (3 - 2 * t);               // 零附近软化
    }

    /** 洋中脊带的半宽（wu）。与旧类同值 130（旧类说明：参考项目 35px ≈ 1.7% 世界宽，本项目按 wu 标定）。 */
    static final double RIDGE_BAND_SIGMA = 130.0;

    // ============================================================ 诊断

    private OceanMorphConcepts() { }
}
