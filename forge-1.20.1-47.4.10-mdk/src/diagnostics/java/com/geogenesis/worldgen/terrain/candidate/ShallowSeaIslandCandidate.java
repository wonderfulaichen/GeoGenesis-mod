package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.noise.Add;
import com.geogenesis.worldgen.noise.Constant;
import com.geogenesis.worldgen.noise.Frequency;
import com.geogenesis.worldgen.noise.Multiply;
import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.Noises;
import com.geogenesis.worldgen.noise.Simplex;
import com.geogenesis.worldgen.terrain.TerrainClass;

/**
 * 浅海群岛：三步法（用户 2026-10-30 指令）。
 *
 * <h3>步骤 1 —— 地基</h3>
 * <p>在浅海里再铺一层 FBM，原语与 {@link com.geogenesis.worldgen.terrain.ContinentField} 同款
 * （多倍频 Simplex + RMS 归一化），输出 {@code n ∈ [−1,1]}。<b>把 n 平方</b>：
 * {@code u = n²} 恒 ≥ 0，{@code n=0} 处增量为 0、{@code |n|=1} 处最大
 * ⇒ 抬起的是一圈圈<b>脊状环带</b>，中央（{@code n≈0}）自动成为潟湖/海峡。</p>
 *
 * <h3>步骤 2 —— 群岛类型地形</h3>
 * <p>按 {@code u} 的值域分档：低档 → 平原质地，中档 → 丘陵，高档 → 山地。
 * 档位既决定岛有多高（{@link #LIFT} 与 {@link #BODY} 都乘 {@code u}），
 * 也决定长哪种地貌族。族之间用 smoothstep 窗口<b>连续混合</b> —— 硬切换会在档位边界
 * 留下高度缝（本项目历史上"沿等值线的阶跃"同类问题）。</p>
 *
 * <h3>步骤 3 —— 加到基地上</h3>
 * <pre>h = h_base + u · (LIFT + BODY · family01)</pre>
 * <p>纯加法（同参考 {@code elevation.rs:189}），{@code u=0} 处严格等于原高度 ⇒
 * <b>浅海之外的世界一个像素都不变</b>。这是本方案的安全边界：海陆比、陆地地形、
 * 深海剖面都不受扰动，只有浅海里的抬升带变化。</p>
 *
 * <h3>流式约束</h3>
 * <p>全部 (x,z) 纯函数，无整图统计、无跨块状态。每点 1 次地基 FBM + 1~2 次地貌族求值。</p>
 *
 * <h3>与 2026-10-30 被否决路线的关系</h3>
 * <p>被否决的是 <b>Worley / 噪声自由撒岛</b>：孤立圆斑、读作台地。本方案不是撒点 ——
 * 抬升沿 {@code |n|} 的等值带分布，岛是<b>环带与链</b>，圆斑只出现在 {@code n} 的局部极值处。</p>
 */
public final class ShallowSeaIslandCandidate {

    // ==================== 步骤 1：地基 ====================

    /**
     * 地基 FBM 的<b>基频块尺度</b>（最大波长）。
     *
     * <h3>这个数必须与"岛有多大"对齐，不是与"大陆有多大"对齐</h3>
     * <p>用户 2026-10-30 两次纠正："注意与其他地形的尺寸比例"、"环状潟湖和延伸的脊，
     * 这个倍频太大了，比群岛地基还大"。踩过的两个坑：</p>
     * <ul>
     *   <li>基频 900 + 9 倍频 ⇒ 最高频波长 3.5 块 ⇒ <b>岛被自己的高频切碎</b>；</li>
     *   <li>基频 3600 ⇒ {@code n=0} 零线（正是环状潟湖与延伸的脊）跨度到 <b>1000+ 块</b>，
     *       比一座岛还大 ⇒ 读作"水道绕着岛转"。</li>
     * </ul>
     *
     * <h3>定档依据（{@code BaseGeomProbe} 实测，1401² 采样、5 块/像素）</h3>
     * <pre>
     *  scale  oct  per   P50边长  P90   max    岛块数
     *   900    5   0.60    32     224    556     857     ← 采用
     *  1600    5   0.60    48     337    777     318
     *  3600    4   0.60   302    1040   1397      39     ← 上一版（环脊过大）
     * </pre>
     * <p>取 900/5：中位岛 32 块、最大 556 块。对照基准 —— 陆地丘陵丘体 <b>200</b> 块、
     * 丘群包络 <b>650</b> 块、山地 <b>610</b> 块、大陆 <b>4000</b> 块 ⇒
     * 岛小于丘陵丘体、远小于山地与大陆，层级正确。</p>
     */
    public static final float BASE_SCALE = 900f;

    /**
     * 地基倍频数。
     *
     * <p>5 倍频：最高频波长 {@code 900/2^4 = 56} 块。这一档让岛缘有数十块级的曲折，
     * 同时不把岛体本身切碎（9 倍频时最高频 3.5 块，是造成"太碎"的直接原因）。</p>
     */
    public static final int BASE_OCTAVES = 5;

    public static final double BASE_LACUNARITY = 2.0;

    /** 振幅衰减：与 {@code ContinentField} 默认口径一致（用户 2026-10-30 选定）。 */
    public static final double BASE_PERSISTENCE = 0.6;

    /** 种子偏移：与 {@code ContinentField} 的 7000+ 错开，避免两层噪声同源相关。 */
    private static final int SEED_OFFSET = 24000;

    // ==================== 步骤 3：抬升幅度 ====================

    /**
     * 岛高<b>完全由丘陵地貌决定</b>（用户 2026-10-30 第三条路）。
     *
     * <h3>为什么去掉常数 LIFT</h3>
     * <p>原设计是 {@code Δ = gate·(LIFT 0.128 + BODY 0.336·hills)}。常数 LIFT 让
     * <b>每个岛的边缘都硬生生抬 30 格</b>，而它只压在几十块宽的门槛带上 ⇒ 岛缘坡度
     * 0.53（陆地丘陵 0.29，陡 83%），剖面读作"平台 + 竖墙"。</p>
     *
     * <p>用户判据："只用岛身的丘陵抬升，岛屿边缘自然变缓；如果岛抬不出水面，
     * 再把 LIFT 加回来一点。这个和我之前让你把沿海地形下沉一点点，靠地形类型决定类似。"
     * ⇒ 高度不该来自一个与地形无关的常数，而该来自<b>地形类型本身</b>。
     * 丘陵在低洼处本来就接近 0，于是岛缘跟着地形自然收边。</p>
     *
     * <h3>★ 但丘陵输出没有零基线，必须减一个底</h3>
     * <p>{@code HillsAmpProbe} 实测 {@code HILLS_2.height01}：
     * P0 = 0.023、P25 = 0.142、P50 = 0.184、P100 = 0.431，<b>全为正值</b>。
     * 直接用它抬升 ⇒ 整片浅海都抬 0.18~1.0 e 量级 ⇒ 不是岛，是填海。</p>
     * <p>所以减掉 {@link #HILLS_FLOOR}（= P25）：{@code Δ = gate·offshore·window·BODY·max(0, hills − FLOOR)}。
     * 岛在丘陵低洼处（{@code hills ≤ FLOOR}）高度收敛到 0，与周围浅海无缝；
     * 丘顶拿到 (0.431 − 0.142) = 0.289 的相对量。</p>
     */
    private static final double HILLS_FLOOR = 0.142;

    /**
     * 岛身振幅（e）。
     *
     * <p>去掉 LIFT 后需要更大的增益才能出水。出水门槛是 {@code Δ ≥ 7/127 = 0.0551 e}
     * （浅海 −7 格）。{@code HillsAmpProbe} 对 HILLS_2 的实测：</p>
     * <pre>
     *  BODY=0.60 ⇒ 需 hills≥0.092 ⇒ 95.2% 的点高于门槛，丘顶峰高 61 格
     *  BODY=0.90 ⇒ 需 hills≥0.061 ⇒ 99.4%，丘顶峰高 92 格
     *  BODY=1.20 ⇒ 需 hills≥0.046 ⇒ 99.9%，丘顶峰高 122 格
     * </pre>
     * <p>取 1.0：约 98% 的点能出水，丘顶约 102 格 —— 与陆地丘陵（丘群包络 650 块、
     * 起伏数十格）同一量级，且丘体波长 128 块让岛的横向尺度不再被摊平。</p>
     */
    private static final double BODY = 1.8;

    /** 当前生效的岛身振幅（默认 = {@link #BODY}，可用 {@link #bodyScale} 缩放）。 */
    private static double body = BODY;

    /** 设置岛身振幅（出图对照用；1.0 = 基准）。返回自身便于链式调用。 */
    public ShallowSeaIslandCandidate bodyScale(double scale) {
        body = BODY * scale;
        return this;
    }

    // ==================== 离岸过渡（用户 2026-10-30 新增要求）====================

    /**
     * 离岸过渡：群岛必须<b>远离陆地海岸</b>，靠近陆地时抬升平滑收敛到 0，
     * 地形因此平滑过渡回原浅海剖面（用户原话"把地形平滑过渡到浅海地形"）。
     *
     * <h3>为什么按大陆性 c 判，而不是按基底高度</h3>
     * <p>上一版按 {@code baseE} 开窗，而它是<b>单调递增</b>的（深海 0 → 海平面附近 1）
     * ⇒ 抬升最强处<b>恰好贴着海岸</b>，群岛长在陆地边上把岸线吞掉。</p>
     *
     * <p>试过"基底高度斜率"也不行：实测仍有 17.4% 的新增陆地落在原陆地 10 块以内 ——
     * 浅海段本身就是常数面（斜率为 0），与"离岸远"不可区分。</p>
     *
     * <p>正解是读<b>大陆性 {@code c} 本身</b>：三段海岸剖面的控制点
     * （{@code cpShore}/{@code cpAnchor}）都是 c 的函数，所以 c 直接就是"离岸进度"。
     * {@code c} 由 {@link com.geogenesis.worldgen.terrain.ContinentField} 给出，
     * 是既有的、连续的、零额外成本的量 —— 不新建任何场，也不引入跨块状态。</p>
     *
     * <h3>阈值</h3>
     * <p>{@code edge = (c+1)/2}；岸线锚点 {@code cpAnchor()=0.5}（{@code OceanAssemblyCandidate}）。
     * 取 {@code edge ∈ [0.50, 0.36]} 作为过渡带：贴着岸线{@code (0.50)} 完全抑制，
     * 退到 {@code 0.36}（离岸约 0.14 in edge ≈ 数百块）后全额出力。
     * 两端 smoothstep 渐隐，满足"任何 reach 处硬切换都必须渐隐"的铁律。</p>
     */
    public interface OffshoreSampler {
        /** 大陆性 c ∈ [−1,1]（负 = 海洋、0 = 海岸、正 = 陆地）。 */
        double c(double x, double z);
    }

    /** 基底高度来源（海陆组装后的 e）。 */
    public interface Seabed {
        double at(double x, double z);
    }

    /** 离岸过渡带：贴岸端的 edge（陆地一侧）。 */
    private static final double OFFSHORE_EDGE_NEAR = 0.50;
    /** 离岸过渡带：完全出力端的 edge（外海一侧）。 */
    private static final double OFFSHORE_EDGE_FAR = 0.331;

    /**
     * 离岸权重 ∈ [0,1]：1 = 远离海岸（群岛可长），0 = 贴着海岸（完全让位）。
     *
     * <p>{@code edge ≤ 0.331} → 1；{@code edge ≥ 0.50} → 0；中间 smoothstep。</p>
     *
     * <h3>为什么取满整段可用浅海带</h3>
     * <p>{@code OceanAssemblyCandidate} 的三段海岸在 {@code PROVINCE} 标定下：
     * {@code SHELF = 0.331}（深海 → 浅海）、{@code SHORE = 0.444}（浅海 → 岸线）、
     * {@code ANCHOR = 0.5}（岸段终点）。所以<b>浅海带就是
     * {@code edge ∈ [0.331, 0.50]}，只有 0.169 宽</b>。</p>
     *
     * <p><b>2026-10-30 修正</b>：上一版取 {@code [0.47, 0.35]}（宽 0.12），
     * 过渡带只占浅海带的 71%，剩下的两端是硬边 —— 剖面里"岛与陆地之间那个陡降"
     * 就出现在这里（{@code gate} 也在同一段里从 0 爬到 1，两者叠加）。
     * 现在过渡带吃满 <b>整个</b> {@code [0.331, 0.50]}，任何 `edge` 位置上都有
     * 非零的过渡宽度，陡降被摊到整条浅海带上。</p>
     *
     * <p>副作用：离岸更远处（{@code edge < 0.44}）抬升权重不再满值，群岛整体略弱。
     * 这一点由 {@link #KNEE} 一并放宽来补偿（见其 javadoc）。</p>
     */
    static double offshoreWeight(OffshoreSampler c, double x, double z) {
        double edge = (c.c(x, z) + 1.0) * 0.5;
        double t = (OFFSHORE_EDGE_NEAR - edge) / (OFFSHORE_EDGE_NEAR - OFFSHORE_EDGE_FAR);
        if (t <= 0) return 0.0;
        if (t >= 1) return 1.0;
        return t * t * (3 - 2 * t);
    }

    /**
     * 浅海窗口：基底在 [−0.35, +0.02] e 之间出力，深海与既有陆地都为 0。
     *
     * <p>深海（−63 格）抬不成岛，既有陆地不该被抬高，两处都必须为 0；
     * 端点为 0 且一阶导连续 ⇒ 抬升带外缘与既有世界严格无缝。</p>
     */
    private static final double SEA_HI = 0.02;
    private static final double SEA_LO = -0.35;

    static double seaWindow(double baseE) {
        if (baseE >= SEA_HI || baseE <= SEA_LO) return 0.0;
        double t = (baseE - SEA_LO) / (SEA_HI - SEA_LO);
        return t * t * (3 - 2 * t);
    }

    // ==================== 成岛门槛（用户 2026-10-30 第 2 步「按一定值域」）====================

    /**
     * 抬升带门槛：把 {@code u = n²} 再压一次，只有 |n| 的<b>极值带</b>才抬成岛。
     *
     * <h3>为什么必须加这一层</h3>
     * <p>实测 {@code u} 的分布并不集中在两端（P50 = 0.12、P95 ≈ 0.82、均值 0.23），
     * 而 RMS 归一化让 {@code |n|} 能到 1.4。没有门槛时，浅海里几乎处处 {@code u ≥ 0.1}
     * ⇒ <b>整片浅海被整体抬高约 10 格</b>，25% 的水面直接变成陆地，读作"浅海变陆地"，
     * 不是群岛。</p>
     *
     * <p>加门槛后，只有 {@code |n| ≥ sqrt(KNEE)} 的极值带抬升，其余浅海保持原剖面不变。
     * 由于 {@code |n|} 的等值线在 FBM 里天然是<b>闭合的岛状轮廓与长条脊</b>，
     * 门槛一加就得到"一条条岛链 + 之间原样水面"。</p>
     */
    /**
     * 成岛门槛（作用在 {@code u=n²} 上）。{@code u ≤ KNEE} 完全不动，{@code u ≥ 1} 满额。
     *
     * <p>{@code u} 的实测分布：P50 = 0.12、P75 ≈ 0.35、P90 = 0.62、P95 = 0.82、均值 0.23。</p>
     *
     * <p><b>0.30 → 0.15（2026-10-30 用户要求"gate 过渡带加宽"）</b>。
     * 过渡区间的宽度是 {@code 1 − KNEE}：0.30 时只有 0.70，而 {@code u} 在
     * {@code |n|} 的过零带附近变化极快 ⇒ {@code gate} 在几十块内从 0 冲到 1。
     * 降到 0.15 后过渡宽度变 0.85（+21%），且起点更靠近 {@code u} 的中位数，
     * 抬升带边缘的坡度被摊薄。</p>
     *
     * <p>同时这也补偿了 {@link #offshoreWeight} 吃满整段浅海带造成的抬升减弱 ——
     * 更多面积进入抬升区。</p>
     */
    private static final double KNEE = 0.15;

    /** 诊断：丘陵族原值（不減底），供剖面探针比较各因子。 */
    public double hillsRaw(double x, double z) {
        return hills.compute((float) x, (float) z);
    }

    /** 诊断：离岸过渡带的贴岸端 edge。 */
    public static double offshoreEdgeNear() {
        return OFFSHORE_EDGE_NEAR;
    }

    /** 诊断：离岸过渡带的外海端 edge。 */
    public static double offshoreEdgeFar() {
        return OFFSHORE_EDGE_FAR;
    }

    /** 诊断：当前成岛门槛。 */
    public static double knee() {
        return KNEE;
    }

    /**
     * 成岛权重 ∈ [0,1]：{@code u ≤ KNEE} 时为 0，{@code u ≥ 1} 时为 1，中间用 smoothstep 塑形。
     *
     * <p>端点两处导数为 0，因此岛缘与周围浅海平滑相接（不会沿 {@code u=KNEE} 等值线出现台阶）。</p>
     */
    static double islandWeight(double u) {
        double t = (u - KNEE) / (1.0 - KNEE);
        if (t <= 0) return 0.0;
        if (t >= 1) return 1.0;
        return t * t * (3 - 2 * t);
    }

    // ==================== 步骤 2：群岛类型地形 ====================

    /**
     * 群岛只用<b>一种</b>地貌族：{@code HILLS_1_GEO}（与陆地上的丘陵同一套配方与波长）。
     *
     * <h3>为什么单一族，而不是按值域分档换族</h3>
     * <p>曾经按成岛权重 {@code g} 分三档（低 = 平原 / 中 = 丘陵 / 高 = 山地）。实测两个硬伤：</p>
     * <ol>
     *   <li><b>尺度不匹配</b>：山地族 {@code M1} 的主体波长是 <b>610 块</b>（脊线 4 倍频），
     *       比陆地丘陵的 650 块包络下的 200 块丘体还要宽得多 —— 群岛本来就只有几百块长，
     *       套上山地族等于把一群小岛都变成"同一座大山的一段"，看上去是**大地形压在小岛上**。</li>
     *   <li><b>碎斑</b>：族选择是离散的（按 {@code g} 阈值），档位等值线在岛上切出一圈圈
     *       不同族的边界，读作"碎"而不是"一座岛"。</li>
     * </ol>
     *
     * <p>改用单族后：岛的<b>体量</b>由 {@link #LIFT} 与 {@link #BODY} 控制，
     * 岛的<b>质地</b>全部来自 {@code HILLS_1_GEO}（200 块丘体 × 650 块丘群包络），
     * 与陆地丘陵同尺度，尺寸比例正确。用户 2026-10-30：「搬丘陵的地形就合适了」。</p>
     */
    private enum IslandType {
        NONE, PLAIN, HILLS, MOUNTAINS
    }

    /**
     * 类型图着色：抬出水面即记丘陵（与岛身用的族一致）。
     *
     * <p>保留枚举的四个值是为了不破坏出图脚本的 5 色下标映射。</p>
     */
    public static TerrainClass terrainClassOf(IslandType t) {
        return switch (t) {
            case PLAIN -> TerrainClass.PLAIN;
            case HILLS -> TerrainClass.HILLS;
            case MOUNTAINS -> TerrainClass.MOUNTAINS;
            case NONE -> TerrainClass.OCEAN;
        };
    }

    // ==================== 状态 ====================

    private final Noise base;
    private final RefNoise.Node hills;
    private boolean seeded;

    public ShallowSeaIslandCandidate(long worldSeed) {
        this.base = buildBase();
        // 岛身用地貌族里的 HILLS_2（用户 2026-10-30 选定）。
        // 它的丘体波长 128 块（参考 cubic(128)），比 HILLS_1 的 200 块短 ——
        // 岛的横向尺度只有 250~550 块，用 200 块的宽缓包络会把岛摊成平盘。
        HillsCandidate h = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        h.seed(worldSeed + 511);
        this.hills = (x, z) -> (float) h.height01(x, z);
        seed(worldSeed);
    }

    /** 与 {@code ContinentField} 同构的 FBM：多倍频 Simplex 值叠加 + RMS 归一化。 */
    private static Noise buildBase() {
        Noise sum = new Constant(0.0);
        double q = 0.0;
        double freq = 1.0 / BASE_SCALE;
        double amp = 1.0;
        for (int i = 0; i < BASE_OCTAVES; i++) {
            Noise o = new Frequency(new Simplex(SEED_OFFSET + i * 97), freq);
            sum = new Add(sum, new Multiply(o, new Constant(amp)));
            q += amp * amp;
            freq *= BASE_LACUNARITY;
            amp *= BASE_PERSISTENCE;
        }
        return new Multiply(sum, new Constant(1.0 / Math.sqrt(q)));
    }

    public void seed(long worldSeed) {
        Noises.seedAll(base, worldSeed, 0);
        this.seeded = true;
    }

    // ==================== 采样 ====================

    /** 地基噪声原值 n ∈ [−1,1]。 */
    public double baseNoise(double x, double z) {
        if (!seeded) throw new IllegalStateException("seed() not called");
        return base.compute(x, z);
    }

    /** 平方后的地基权重 {@code u = n² ∈ [0,1]} —— 两翼抬起、中央不动。 */
    public double u(double x, double z) {
        double n = baseNoise(x, z);
        double v = n * n;
        return v > 1.0 ? 1.0 : v;
    }

    /**
     * 类型（仅供类型图着色 + 诊断）。
     *
     * <p>只区分"是否成岛"：抬升权重过 {@link #KNEE} 判为丘陵，其余记 NONE。
     * 不再按 {@code g} 分级 —— 岛身只有一种族，分级已无意义。</p>
     */
    public IslandType type(double x, double z) {
        if (gate(x, z) <= 0) return IslandType.NONE;
        return IslandType.HILLS;
    }

    /**
     * 岛的质地：丘陵族高度减去自身底部基线。
     *
     * <p>与陆地丘陵同一条配方（200 块丘体 × 650 块丘群包络），因此群岛的细节尺度
     * 与陆地丘陵一致，不会出现"岛上的山比大陆的山还宽"。</p>
     *
     * <p>减去 {@link #HILLS_FLOOR} 后输出约 {@code [0, 0.33]}（丘陵最大值 0.496 − 0.17）。
     * 这个"底"不是调参，而是必需的：丘陵输出全为正值，不减底则整片浅海都被抬起。</p>
     */
    private double family01(double x, double z) {
        double v = hills.compute((float) x, (float) z) - HILLS_FLOOR;
        return v > 0 ? v : 0;
    }

    /**
     * 第 3 步：高度增量（e，恒 ≥ 0）。
     *
     * <pre>Δ = seaWindow(base) · offshoreWeight(c) · islandWeight(u) · body · family01</pre>
     *
     * <p>因子分工：</p>
     * <ul>
     *   <li>{@code seaWindow} —— 深海与既有陆地为 0，抬升只发生在近海平面带；</li>
     *   <li>{@code offshoreWeight} —— <b>离岸过渡</b>：按大陆性 c 在岸线附近渐隐到 0，
     *       让地形平滑回到原浅海剖面，群岛不与陆地相连；</li>
     *   <li>{@code islandWeight(u)} —— <b>成岛门槛</b>：只让 |n| 的极值带出水，
     *       其余浅海保持原剖面；</li>
     *   <li>{@code body · family01} —— 岛高<b>完全由丘陵地形决定</b>（无常数抬升）。</li>
     * </ul>
     * <p>各因子在边界都收敛到 0 且一阶导连续 ⇒ 抬升带外缘与既有世界严格无缝。</p>
     */
    public double apply(Seabed base, OffshoreSampler c, double x, double z) {
        return base.at(x, z) + delta(base, c, x, z);
    }

    /**
     * 相对某一基底高度的增量。
     *
     * <pre>Δ = seaWindow · offshoreWeight · islandWeight(u) · body · family01</pre>
     *
     * <p>四个因子都在边界收敛到 0，所以岛缘平滑。岛缘之所以比"常数 LIFT"版更缓，
     * 是因为 {@code family01}（丘陵高度）<b>本身</b>在岛的边缘接近 0 ——
     * 高度来自地形类型，不是外加常数。</p>
     */
    public double delta(Seabed base, OffshoreSampler c, double x, double z) {
        double baseE = base.at(x, z);
        double w = seaWindow(baseE);
        if (w <= 0) return 0.0;
        w *= offshoreWeight(c, x, z);
        if (w <= 0) return 0.0;
        double g = gate(x, z);
        if (g <= 0) return 0.0;
        double f = family01(x, z);
        if (f <= 0) return 0.0;
        return w * g * body * f;
    }

    /** 成岛权重（= 门槛后的平方权重 {@code n²}）。 */
    private double gate(double x, double z) {
        return islandWeight(u(x, z));
    }

    /**
     * 地基抬升是否真的把这里变成陆地（{@code h_base + Δ ≥ 0}）。
     *
     * <p>只有抬出水面才算岛。类型图/ {@code TerrainClass} 必须按<b>实际高度</b>判，
     * 不能只看 {@code u} 的档位 —— 否则深海或深埋处的高 {@code u} 点会被错标成山地。</p>
     */
    public boolean emerges(Seabed base, OffshoreSampler c, double x, double z) {
        return base.at(x, z) + delta(base, c, x, z) >= 0.0;
    }
}
