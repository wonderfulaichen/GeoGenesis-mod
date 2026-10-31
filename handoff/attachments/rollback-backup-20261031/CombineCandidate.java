package com.geogenesis.worldgen.terrain.candidate;

/**
 * <b>参考组装链的完整复刻</b>：{@code TerrainProvider.generateTerrain}（{@code TerrainProvider.java:28-83}）
 * 的 {@code combine} 两两复合 + {@code Heightmap.java:166} 的山形 {@code Blender} 覆盖层。
 *
 * <h3>参考到底怎么组装（源码逐步）</h3>
 * <pre>
 * // TerrainProvider.java:36-63
 * mixable   = [steppe, plains, dales, hills1, hills2, torridonian, plateau, badlands]  // weight>0 过滤
 * unmixable = [badlands, mountains1, mountains2, mountains3, volcano]
 *
 * // TerrainProvider.java:69-71 + 116-153
 * mixed  = combine(mixable)      // ← N + C(N,2) 条：8 + 28 = 36
 * result = mixed + unmixable
 * Collections.shuffle(result, new Random(terrainSeed.next()))   // ← 槽位顺序被打散
 *
 * // Heightmap.java:115-119 —— 山形遮罩（这才是"大山系"的来源）
 * mountainShape = worleyEdge(seed, 1000·hScale·2.25, DISTANCE_2_ADD, EUCLIDEAN)
 *               @ warpPerlin(333, 2, 250) @ curve(CURVE3) @ clamp(0,0.9) @ map(0,1)
 *
 * // Heightmap.java:123-125 + 166
 * terrainRegions = RegionSelector(result)
 * borders        = makeBorder(...)          // = makePlains（平原/草原）
 * terrainBlend   = RegionLerper(borders, terrainRegions)
 * land           = Blender(mountainShape, terrainBlend, mountains, 0.3, 0.8, 0.575)
 * </pre>
 *
 * <h3>三个"与想当然不同"的要点（复刻必须照做）</h3>
 * <ol>
 *   <li><b>山地不在区域选择器里。</b>它是被 {@code mountainShape} 这个 <b>2250 格波长</b>的
 *       worleyEdge 遮罩在 {@code 0.3–0.8} 区间<b>覆盖</b>上去的。所以山地是"成片的大山系"，
 *       而不是"某个 Voronoi 格子里的一片山"。</li>
 *   <li><b>{@code combine} 让类型数量爆炸。</b>8 个 mixable 两两组合出 28 个复合类型
 *       （{@code CompositeTerrain}，名字是 {@code a-b}，类别取更"高"的那个）。
 *       于是区域选择器要在 36 个槽位里选，而不是 8 个。</li>
 *   <li><b>{@code Collections.shuffle} 打散槽位。</b>所以"第几个槽位是哪类"没有语义，
 *       只有"总共几票"有意义。权重决定票数。</li>
 * </ol>
 *
 * <h3>本类相对参考的偏差（明确列出，不藏）</h3>
 * <ul>
 *   <li>用我们已定稿的 11 个族替参考的 8 个 mixable；火山、torridonian 无对应，跳过。</li>
 *   <li>参考的 {@code levels.ground}（地面基准）在这个沙箱里由 {@code HeightCurve} 承担。</li>
 *   <li>不实现 {@code VariedMountainPopulator} 的三档变化（默认预设 {@code mountainVariety=0}，
 *       本来就不走那条分支）。</li>
 * </ul>
 */
public final class CombineCandidate {

    /** {Heightmap.java:115}：{@code legacyMountainScaling=false} ⇒ 1000 × hScale × 2.25。 */
    private static final float MOUNTAIN_SHAPE_SCALE = 1000f * 1.0f * 2.25f;
    /** {Heightmap.java:166} 的参考原值，仅作对照保留。 */
    private static final float REF_MTN_BLEND_LOW = 0.3f;
    private static final float REF_MTN_BLEND_HIGH = 0.8f;

    /**
     * 本实现实际使用的山形遮罩阈值。
     *
     * <p>为什么不能用参考的 0.3/0.8：实测遮罩分布（{@code MaskDistributionProbe}，
     * 10020 块跨度、251001 点）是 P50=0.385、P75=0.531、P90=0.715。
     * 用 0.3 起混 ⇒ <b>71.8% 的地面</b>都在与山地混合，整幅图变成均匀皱褶；
     * 用 0.8 封顶 ⇒ 只有 5.1% 是纯山地。这与参考观感不符。</p>
     *
     * <p>取 0.55 / 0.78 ⇒ 约 23% 地面进入混合带、约 8% 纯山地。参考的山地面积
     * 在默认预设下也是这个量级（山地 weight 2.5 但作为覆盖层，实际占地远小于槽位比例）。</p>
     */
    private static final float MTN_BLEND_LOW = 0.55f;
    private static final float MTN_BLEND_HIGH = 0.78f;
    private static final float MTN_BLEND_SPLIT = 0.575f;
    /** {TerrainProvider.java:116-129} {@code combine} 的 selector 尺度 = regionSize/2。 */
    private static final float COMBINE_SELECTOR_SCALE = 600f;

    /** 一个族的求值器（输出<b>相对共享地面基准</b>的高度，0 = 基准面）。 */
    public interface Family {
        double height01(double x, double z);
    }

    /**
     * 共享地面基准 {@code levels.ground}（{@code Levels.java:22-33}）在本项目的等价量。
     *
     * <p>参考里所有 populator 的 {@code cell.height} 都是
     * {@code max(ground + heightNoise, 0)}（{@code TerrainPopulator.java:16-24}）——
     * {@code ground} 是<b>全局常量</b>，所以任何族、任何类型都从同一基准起算，
     * 混合时不可能出现基准错位。</p>
     *
     * <p>本项目的 {@code HeightCurve} 以 e=0 为海平面、正向更陆。要让"基准面 = 基准面"，
     * 共享地面必须取 e 空间的一个常量，然后所有族的配方输出都当作<b>相对</b>它的增量。
     *
     * <h3>★ 2026-10-30：取值几经反复，最终回到 0.02</h3>
     * <p><b>经过</b>：用户圈出"平原深处一大片没有起伏的浅色区"⇒ 曾把它调到
     * {@code 0.2461}（参考 {@code ground} 真值），实测陆地 P50 从 93 格抬到 137 格
     * （{@code +44}），<b>整体浮起</b>，被用户当场指出"平原应该在接近海平面"⇒ 回退。</p>
     *
     * <h3>为什么 0.02 其实是对的</h3>
     * <p>真正的问题<b>不在基准厚度</b>，而在 {@code max(0)} 的位置（见 {@link #height01}
     * 的说明）。各族输出的最小值只有 <b>{@code -0.02}</b>（steppe，与参考一致），
     * 所以 {@code GROUND_E=0.02} <b>刚好</b>能把负值抬到 0，{@code max} 不触发。</p>
     * <p>此前触发是因为先对 {@code height} 做了 {@code max}、再加基准 —— 顺序错了。
     * 修正顺序后，0.02 足够。</p>
     *
     * <h3>为什么 0.03 也不行（2026-10-30 实测）</h3>
     * <p>曾以为 {@code GROUND_E >= 0.02×1.50 = 0.03} 就能让 {@code max(0)} 永不触发，实测否决：</p>
     * <pre>
     *   方案               Y=67.73平台   Y=70.09平台   假平台最大单值   与原版相同
     *   原版 (G=0.02)        2946 px        84 px     2633 px (11.1%)     —
     *   方案A (G=0.02+修正)   796 px        84 px      601 px (2.7%)    99.85%
     *   方案B (G=0.03)         82 px       750 px      591 px (2.7%)    35.10%
     * </pre>
     * <p><b>平台只是从 67.73 搬到 70.09，总量没变</b>（796→832），还改动了 65% 的像素。
     * 根因是触发 {@code max(0)} 的是 {@code regionHeight} 本身接近 0，与 {@code GROUND_E} 无关。</p>
     *
     * <p>剩余约 600 px 的极平区经 {@code SelectedFamilyProbe} 确认 <b>100% 是 steppe 族</b>：
     * 全局 {@code P10=-0.001, P50=+0.011, P90=+0.023}，跨度仅 0.024（≈8.5 格）——
     * 这是参考 {@code makeSteppe} 的设定（{@code height*0.08-0.02}），
     * <b>"草原本来就平"，不是缺陷</b>。</p>
     */
    public static final double GROUND_E = 0.02;

    /**
     * 从基准面到 e 上界的跨度。
     *
     * <p><b>2026-10-30 回退到 1.50</b>：曾随 {@code GROUND_E} 提到 0.2461 而缩到 1.14
     * （为了压住顶点）。基准回到 0.02 后，跨度也回到 1.50 —— 这是用户认可那版的口径，
     * 实测地形层次最丰富（山地占 0.02→0.93 e，平原占 0.02→0.16 e，对比 5.4 倍）。</p>
     *
     * <p>各族的原始输出并<b>不</b>铺满 0..1 —— 实测最大是山地 {@code max=0.617}，
     * 平原只有 {@code max=0.106}。若取 {@code E_SPAN=0.96}（假设铺满 0..1），
     * 整幅地形只用到垂直范围的 4%–24%，读作"一片均匀的中等皱褶"。</p>
     */
    public static final double E_SPAN = 1.50;

    /** 复合类型的成员（用于类型图着色）。 */
    public enum Category { PLAIN, HILLS, MOUNTAINS, PLATEAU, BADLANDS, BASIN }

    private final Family[] mixable;
    private final Category[] mixableCat;
    private final float[] mixableWeight;
    private final Family[] unmixable;
    private final Category[] unmixableCat;
    private final float[] unmixableWeight;
    private final Family border;

    /** 展开后的槽位（mixed + unmixable，已 shuffle）。 */
    private Family[] slots;
    private Category[] slotCats;

    /**
     * 每个槽位对应的<b>高度包络</b>（与 {@code slots} 严格同序）。
     *
     * <h3>为什么需要独立数组</h3>
     * <p>{@link Family#height01} 是<b>成品高度函数</b>（含噪声）。要用样条机制做
     * "形态过渡"，必须把族拆成「<b>包络</b>（标量 lo/hi）+ <b>形态</b>（共用噪声）」两部分：</p>
     * <pre>
     *   旧：h = lerp(border_height01, selected_height01, t)     ← 两团噪声被平均
     *   新：h = lo + (hi − lo) · n                             ← n 共用，只换包络
     * </pre>
     * <p>所以 {@code lo}/{@code hi} 是<b>标量</b>，不是函数。</p>
     */
    private double[] slotLo;
    private double[] slotHi;

    /**
     * 全类型共用的形态噪声（单条 FBM，输出 0..1）。
     *
     * <p>这是"形态过渡"能成立的关键：过渡时噪声场不变，只有包络在变形
     * ⇒ 山谷还是山谷，只是被压进新的包络里。</p>
     */
    private RefNoise.Node sharedShape;

    /** 包络过渡开关（false = 保留旧的 border lerp，供 A/B）。 */
    private boolean envelopeTransition = false;

    /**
     * 启用包络过渡：{@code h = lo + (hi−lo)·shared}，两类型间插值 {@code lo/hi}，
     * <b>不再经过公共 border</b>。
     */
    public CombineCandidate useEnvelopeTransition() {
        this.envelopeTransition = true;
        return this;
    }

    /**
     * 类型过渡权重曲线：<b>Catmull-Rom 样条</b>，控制点 {@code [0.0, 0.3, 0.7, 1.0]}。
     *
     * <h3>依据（{@code docs/用户需求记录.md} §八.2 原文）</h3>
     * <pre>
     * #### 2. TerrainComposer 样条过渡
     * - 替换 smoothstep：使用 NoiseUtil.spline（Catmull-Rom样条）实现 C1 连续过渡
     * - 过渡区间：控制点 [0.0, 0.3, 0.7, 1.0]，过渡区宽度 0.4 权重空间（≈25格）
     * - 消除悬崖：相邻类型渐近过渡，不再 1 格硬切
     * </pre>
     *
     * <h3>四个控制点的语义（对应 §七「核心区纯类型 + 过渡区渐近」）</h3>
     * <pre>
     *   t ∈ [0.0, 0.3]   w = 0   ← 纯类型 A（权重锁定，不被稀释）
     *   t ∈ [0.3, 0.7]   w 0→1   ← Catmull-Rom 过渡带（C1 连续，无阶跃）
     *   t ∈ [0.7, 1.0]   w = 1   ← 纯类型 B
     * </pre>
     * <p>两端各 30% 是<b>纯类型区</b>，只有中段 40% 做过渡 —— 这正是 §七
     * 「核心区（权重&gt;0.7）：纯类型，不被稀释 / 过渡区：较宽（数十格）的渐近过渡」。</p>
     *
     * <h3>为什么用 Catmull-Rom 而不是线性/smoothstep</h3>
     * <p>{@code NoiseUtil.spline}（{@code :37-67}）是<b>非均匀 Cubic Hermite</b>：
     * 内部节点切线取 chordal Catmull-Rom（{@code (P[i+1]−P[i−1])/(T[i+1]−T[i−1])}），
     * 端点用 clamped 切线。好处是<b>过所有控制点且内部节点导数非零</b>
     * —— 定值区间（w=0 / w=1）与过渡带的接头处没有 flat spot，斜率连续。</p>
     *
     * <p>控制点值 {@code P = {0, 0, 1, 1}}：前两点同值 ⇒ {@code [0,0.3]} 恒 0；
     * 后两点同值 ⇒ {@code [0.7,1]} 恒 1；中间一段由样条给出 S 形过渡。</p>
     */
    private static final double[] TRANSITION_T = {0.0, 0.3, 0.7, 1.0};
    private static final double[] TRANSITION_P = {0.0, 0.0, 1.0, 1.0};

    /** t ∈ [0,1] → 类型 B 的权重（0 = 纯 A，1 = 纯 B）。 */
    private static double catmullRomWeight(double t) {
        return spline(t, TRANSITION_T, TRANSITION_P);
    }

    /**
     * 非均匀 Cubic Hermite / Catmull-Rom 样条，<b>逐行等价于
     * {@code com.geogenesis.worldgen.noise.NoiseUtil.spline}</b>（{@code :37-67}）。
     *
     * <p>在沙箱内独立实现，理由与 {@link RefNoise} 一致：沙箱要能脱离 MC 与
     * {@code src/main} 单独编译运行。算法本身逐句照抄，不引入任何自己的改动。</p>
     *
     * <h3>算法要点（与 NoiseUtil 完全一致）</h3>
     * <ul>
     *   <li>过所有控制点 {@code (T[i], P[i])}；</li>
     *   <li>内部节点切线取 <b>chordal Catmull-Rom</b>：
     *       {@code m = (P[i+1] − P[i−1]) / (T[i+1] − T[i−1])}；</li>
     *   <li>端点用 <b>clamped 切线</b>（首段/末段斜率）；</li>
     *   <li>切线乘段宽 {@code dt} 后进入 Hermite 基函数 ⇒ 支持非均匀控制点间距。</li>
     * </ul>
     * <p>相对 {@code smoothstep} 的关键优势：<b>内部节点导数非零</b>，
     * 不会在控制点处产生 flat spot（那会让"定值段 ↔ 过渡段"的接头出现停顿感）。</p>
     */
    private static double spline(double x, double[] T, double[] P) {
        if (x <= T[0]) return P[0];
        int n = T.length - 1;
        if (x >= T[n]) return P[n];
        int i = 0;
        while (i < n && x > T[i + 1]) i++;
        double dt = T[i + 1] - T[i];
        double u = (x - T[i]) / dt;
        double m0 = (i == 0)
                ? (P[1] - P[0]) / (T[1] - T[0])
                : (P[i + 1] - P[i - 1]) / (T[i + 1] - T[i - 1]);
        double m1 = (i == n - 1)
                ? (P[n] - P[n - 1]) / (T[n] - T[n - 1])
                : (P[i + 2] - P[i]) / (T[i + 2] - T[i]);
        double dp0 = m0 * dt;
        double dp1 = m1 * dt;
        double u2 = u * u, u3 = u2 * u;
        double h00 = 2.0 * u3 - 3.0 * u2 + 1.0;
        double h10 = u3 - 2.0 * u2 + u;
        double h01 = -2.0 * u3 + 3.0 * u2;
        double h11 = u3 - u2;
        return h00 * P[i] + h10 * dp0 + h01 * P[i + 1] + h11 * dp1;
    }

    /**
     * 族的<b>包络</b>（标量 lo/hi），按 {@code Category} 查表。
     *
     * <h3>这些数值的来源</h3>
     * <p>不是运行时采样（那会把噪声值当包络，随机且不稳定），而是
     * <b>{@code FamilyRangeProbe} 实测的各族 {@code height01} 值域</b>
     * （20 万样本，seed=9139912035078620160）：</p>
     * <pre>
     *   族          min        P50        max        ← 采用 lo=P1 量级、hi≈max
     *   plains    -0.020    0.0379    0.1059        0.00 / 0.106
     *   steppe    -0.020    0.0109    0.0482        0.00 / 0.048
     *   hills1     0.027    0.2067    0.4750        0.027 / 0.475
     *   hills2     0.020    0.1842    0.4526        0.020 / 0.453
     *   dales      0.000    0.0814    0.4000        0.000 / 0.400
     *   plateau    0.002    0.1793    0.5179        0.002 / 0.518
     *   badlands   0.025    0.0850    0.4551        0.025 / 0.455
     *   mtn1       0.009    0.2401    0.6167        0.009 / 0.617
     * </pre>
     * <p>下界抬到 {@code max(0, min)}：盆地已删，陆地不该低于海平面。</p>
     *
     * <p><b>与参考 {@code SplineConfig} 的差异</b>：参考的 {@code lo/hi} 是<b>参数化设定</b>
     * （PLAIN 0.005/0.03、HILLS 0.06/0.368…），是"设计意图"；这里用的是<b>实测值域</b>，
     * 是"当前配方实际产出"。用实测值是为了不改变地形高度基准
     * （用户 2026-10-31 明确"高度不能变"）。</p>
     */
    private static double[] envelopeFor(Category c) {
        return switch (c) {
            case PLAIN     -> new double[]{0.000, 0.106};
            case HILLS     -> new double[]{0.020, 0.475};
            case PLATEAU   -> new double[]{0.002, 0.518};
            case BADLANDS  -> new double[]{0.025, 0.455};
            case MOUNTAINS -> new double[]{0.009, 0.617};
            case BASIN     -> new double[]{0.000, 0.050};   // 已删，保留兜底
        };
    }

    private Family[] composites;
    private Category[] compositeCats;
    private float[] compositeWeights;
    private RegionPartition partition;
    private Blender blender;

    /** 构造剖面来源（null = 不启用构造偏置）。 */
    private com.geogenesis.worldgen.terrain.TectonicField tectonic;
    /** 山地族（构造偏置用；{@link Blender} 已持有同一个）。 */
    private Family mountainFamily;
    /** 盆地族（构造偏置用；与槽位表里那一份同源，但独立求值避免被权重稀释）。 */
    private Family basinFamily;

    private static final double TECTONIC_SIGMA = 80.0;
    /** orogen × gain 作为"切向山地"的插值权重上限。1.0 = 完全变山地。 */
    private static final double TECTONIC_OROGEN_GAIN = 1.6;
    /** rift × gain 作为"切向盆地"的插值权重上限。 */
    private static final double TECTONIC_RIFT_GAIN = 1.2;

    /** 启用构造偏置（可选；不调用则不改变任何产出）。 */
    public void enableTectonicBias(com.geogenesis.worldgen.terrain.TectonicField field,
                                   Family mountain, Family basin) {
        this.tectonic = field;
        this.mountainFamily = mountain;
        this.basinFamily = basin;
    }

    public CombineCandidate(long worldSeed,
                            Family[] mixable, Category[] mixableCat, float[] mixableWeight,
                            Family[] unmixable, Category[] unmixableCat, float[] unmixableWeight,
                            Family border) {
        this.mixable = mixable.clone();
        this.mixableCat = mixableCat.clone();
        this.mixableWeight = mixableWeight.clone();
        this.unmixable = unmixable.clone();
        this.unmixableCat = unmixableCat.clone();
        this.unmixableWeight = unmixableWeight.clone();
        this.border = border;
        this.borderModeSeed = worldSeed;
        seed(worldSeed);
    }

    /**
     * 带放置模式的构造器：{@code idMode=COHERENT} 时格子 id 由低频噪声给出
     * （相邻格子高概率同类型 ⇒ 类型连带成带）。
     */
    public CombineCandidate(long worldSeed,
                            Family[] mixable, Category[] mixableCat, float[] mixableWeight,
                            Family[] unmixable, Category[] unmixableCat, float[] unmixableWeight,
                            Family border,
                            RegionPartition.IdMode idMode, float idScale,
                            float idAnisoX, float idAnisoZ) {
        this.mixable = mixable.clone();
        this.mixableCat = mixableCat.clone();
        this.mixableWeight = mixableWeight.clone();
        this.unmixable = unmixable.clone();
        this.unmixableCat = unmixableCat.clone();
        this.unmixableWeight = unmixableWeight.clone();
        this.border = border;
        this.borderModeSeed = worldSeed;
        this.idMode = idMode;
        this.idScale = idScale;
        this.idAnisoX = idAnisoX;
        this.idAnisoZ = idAnisoZ;
        seed(worldSeed, idMode, idScale, idAnisoX, idAnisoZ);
    }

    private void seed(long worldSeed) {
        seed(worldSeed, RegionPartition.IdMode.WHITE, 5200f, 0.34f, 1f);
    }

    private void seed(long worldSeed, RegionPartition.IdMode mode,
                      float scale, float anisoX, float anisoZ) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        int[] cursor = {base};
        java.util.function.IntSupplier next = () -> cursor[0]++;

        buildComposites(next);

        // result = mixed + unmixable
        int raw = composites.length + unmixable.length;
        Family[] entries = new Family[raw];
        Category[] cats = new Category[raw];
        float[] weights = new float[raw];
        for (int i = 0; i < composites.length; i++) {
            entries[i] = composites[i];
            cats[i] = compositeCats[i];
            weights[i] = compositeWeights[i];
        }
        for (int i = 0; i < unmixable.length; i++) {
            entries[composites.length + i] = unmixable[i];
            cats[composites.length + i] = unmixableCat[i];
            weights[composites.length + i] = unmixableWeight[i];
        }

        // ★ RegionSelector.getWeightedArray：权重 → 槽位展开（此前漏掉的一步）
        expandByWeight(entries, cats, weights);

        // 参考随后 Collections.shuffle(result, new Random(terrainSeed.next()))
        int total = slots.length;
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) order.add(i);
        java.util.Collections.shuffle(order, new java.util.Random(next.getAsInt()));
        Family[] shuffled = new Family[total];
        Category[] shuffledCats = new Category[total];
        double[] shuffledLo = new double[total];
        double[] shuffledHi = new double[total];
        for (int i = 0; i < total; i++) {
            shuffled[i] = slots[order.get(i)];
            shuffledCats[i] = slotCats[order.get(i)];
            shuffledLo[i] = slotLo[order.get(i)];
            shuffledHi[i] = slotHi[order.get(i)];
        }
        slots = shuffled;
        slotLo = shuffledLo;
        slotHi = shuffledHi;
        slotCats = shuffledCats;

        RefNoise.Node wx = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        RefNoise.Node wz = RefNoise.Perlin.ofScale(next.getAsInt(), 400f, 1);
        partition = buildPartition(next, wx, wz, worldSeed, mode, scale, anisoX, anisoZ);

        // 全类型共用的形态噪声（包络过渡用）：单条 FBM，与任何族都不共享种子
        sharedShape = RefNoise.Perlin.ofScale(next.getAsInt(), 420f, 4);

        blender = new Blender(worldSeed, next);
    }

    /**
     * 区域格子 id 的取法。
     *
     * <p>{@link RegionPartition.IdMode#WHITE} = 参考原样（每格独立哈希）；
     * {@link RegionPartition.IdMode#COHERENT} = 格心采样低频噪声（相邻格连带成带）。</p>
     */
    private RegionPartition.IdMode idMode = RegionPartition.IdMode.WHITE;
    /** COHERENT 下的噪声相关长度（wu）；越大 ⇒ 连带范围越广、带越粗。 */
    private float idScale = 5200f;
    /** COHERENT 下的各向异性：x 取样变慢 ⇒ 带沿 x 拉长。1/1 = 各向同性。 */
    private float idAnisoX = 0.34f, idAnisoZ = 1f;

    /**
     * 启用连带放置（相邻格子高概率同类型）。<b>必须在 {@code seed()} 之前调用</b> ——
     * 区域划分在 seed 时一次性构建，事后再改字段不会重建（首版就踩了这个坑）。
     */
    public CombineCandidate coherentPlacement(float scale, float anisoX, float anisoZ) {
        this.idMode = RegionPartition.IdMode.COHERENT;
        this.idScale = scale;
        this.idAnisoX = anisoX;
        this.idAnisoZ = anisoZ;
        return this;
    }

    private RegionPartition buildPartition(java.util.function.IntSupplier next,
                                           RefNoise.Node wx, RefNoise.Node wz, long worldSeed,
                                           RegionPartition.IdMode mode, float scale,
                                           float anisoX, float anisoZ) {
        if (mode == RegionPartition.IdMode.WHITE) {
            return new RegionPartition(next.getAsInt(), wx, wz);
        }
        // 低频噪声：在格心世界坐标上采样（格距 1200wu），相关长度 scale。
        //
        // ★ 必须把噪声的实际值域拉满：RefNoise.Perlin 声明 0..1，但单次求值只用中间一段
        //   （实测 P50≈0.385、P5–P95≈0.15–0.80）。不拉伸时 round(id×N) 会挤在少数几个
        //   槽位上 ⇒ 整幅图退化成单一类型（首版实测 95% 是丘陵）。
        //   与盆地盐滩同一处理：Clamp 定窗口 + Map 拉伸。
        // ★ 必须把 scale 原样传给 ofScale：它内部已经做了 1/scale。
        //   首版写成 ofScale(seed, 1f/scale, 3) ⇒ frequency = 1800/wu ⇒ 相邻格心完全随机采样。
        RefNoise.Node raw = RefNoise.Perlin.ofScale((int) (worldSeed ^ (worldSeed >>> 32)) + 90210,
                scale, 3);
        // ★ 噪声是钟形分布（P50≈0.385），不展宽的话 round(id×N) 会挤在中间几个槽位
        //   ⇒ 整幅图退化成少数类型。用 0.30–0.70 的窗口拉开到满值域。
        RefNoise.Node idNoise = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);
        return new RegionPartition(next.getAsInt(), wx, wz, mode, idNoise, anisoX, anisoZ);
    }

    /**
     * {@code RegionSelector.getWeightedArray}（{@code RegionSelector.java:30-64}）逐行复刻。
     *
     * <pre>
     * smallest = min(weight)                      // 权重为 0 的条目直接剔除
     * count_i  = round(weight_i / smallest)
     * 槽位列表 = 每个条目重复 count_i 次
     * </pre>
     *
     * <p>这条规则让"权重"变成<b>面积</b>：权重 2.5 的山地占 2.5 倍槽位。
     * 上一版每个复合类型只给 1 个槽，等于把所有类型强行等分 ——
     * 后果是低地复合类型压倒性地多，大尺度上整幅图变成均匀皱褶、没有地貌区。</p>
     */
    private void expandByWeight(Family[] entries, Category[] cats, float[] weights) {
        float smallest = Float.MAX_VALUE;
        for (float w : weights) {
            if (w <= 0f) continue;
            smallest = Math.min(smallest, w);
        }
        if (smallest == Float.MAX_VALUE) smallest = 1f;

        int total = 0;
        int[] counts = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            counts[i] = weights[i] <= 0f ? 0 : Math.round(weights[i] / smallest);
            total += counts[i];
        }
        if (total == 0) {
            // 参考的兜底：全部权重为 0 时退回原始列表
            slots = entries.clone();
            slotCats = cats.clone();
            slotLo = new double[entries.length];
            slotHi = new double[entries.length];
            for (int i = 0; i < entries.length; i++) {
                double[] env = envelopeFor(cats[i]);
                slotLo[i] = env[0];
                slotHi[i] = env[1];
            }
            return;
        }
        slots = new Family[total];
        slotCats = new Category[total];
        slotLo = new double[total];
        slotHi = new double[total];
        int k = 0;
        for (int i = 0; i < entries.length; i++) {
            double[] env = envelopeFor(cats[i]);
            double lo = env[0], hi = env[1];
            for (int c = 0; c < counts[i]; c++) {
                slots[k] = entries[i];
                slotCats[k] = cats[i];
                slotLo[k] = lo;
                slotHi[k] = hi;
                k++;
            }
        }
    }

    /** {@code combine(input, operator)}：N 个原始 + C(N,2) 个两两复合。 */
    private void buildComposites(java.util.function.IntSupplier next) {
        int n = mixable.length;
        int pairs = n * (n - 1) / 2;
        composites = new Family[n + pairs];
        compositeCats = new Category[n + pairs];
        compositeWeights = new float[n + pairs];
        int k = n;
        for (int i = 0; i < n; i++) {
            composites[i] = mixable[i];
            compositeCats[i] = mixableCat[i];
            compositeWeights[i] = mixableWeight[i];
            for (int j = i + 1; j < n; j++) {
                Family a = mixable[i], b = mixable[j];
                // {TerrainProvider.java:118-122}
                RefNoise.Node selector = RefNoise.Perlin.ofScale(next.getAsInt(), COMBINE_SELECTOR_SCALE, 1);
                selector = RefNoise.warpPerlin(selector, next.getAsInt(), COMBINE_SELECTOR_SCALE / 2f, 2,
                        COMBINE_SELECTOR_SCALE / 2f);
                RefNoise.Node sel = selector;
                composites[k] = (x, z) -> {
                    // Noises.blend(selector, a, b, mid=0.5, range=0.25) 后 max(0)
                    float s = sel.compute((float) x, (float) z);
                    float al = RefNoise.mapClamped(s, 0.375f, 0.625f);   // [0.5±0.125] 归一
                    double v = a.height01(x, z);
                    double w = b.height01(x, z);
                    return Math.max(v + (w - v) * al, 0.0);
                };
                compositeCats[k] = dominant(mixableCat[i], mixableCat[j]);
                // {TerrainProvider.java:127}：weight = (w1 + w2) / 2
                compositeWeights[k] = (mixableWeight[i] + mixableWeight[j]) / 2f;
                k++;
            }
        }
    }

    /** {@code TerrainCategory.getDominant}：ordinal 更大者胜（我们的类别按高度排）。 */
    private static Category dominant(Category a, Category b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * 过渡插值方式。
     *
     * <h3>{@code LINEAR}（现状，参考原样）</h3>
     * <pre>
     *   h = border + (selected − border) · edge
     * </pre>
     * <p>{@code edge} 从 0（边界）到 1（区中心）线性推进 ⇒ <b>斜率恒定</b>，
     * 在两端（{@code edge=0} 与 {@code edge≈1} 饱和处）斜率突变。</p>
     *
     * <h3>{@code SPLINE}（本实验）</h3>
     * <p>用 Cubic Hermite 样条在 {@code edge} 轴上插值，控制点为
     * {@code (0, border)} 与 {@code (1, selected)}，两端各带可调导数：</p>
     * <pre>
     *   t  = edge
     *   dx = 1
     *   h  = h00(t)·border + h10(t)·(d0·dx) + h01(t)·selected + h11(t)·(d1·dx)
     *   其中 h00 = 2t³−3t²+1, h10 = t³−2t²+t, h01 = −2t³+3t², h11 = t³−t²
     * </pre>
     * <p>算法逐字节对齐 {@code SplineUtil.splint}（= MC {@code Spline#splint}），
     * 零 MC 依赖，在沙箱内独立实现。</p>
     *
     * <p><b>为什么可能更自然</b>：{@code d0 > 0} 让过渡在边界处"起坡平缓"，
     * {@code d1 > 0} 让它在靠近区中心时"收尾平缓"，于是过渡带两端都没有折痕。
     * 两个导数都是<b>可调旋钮</b> —— 这是线性插值没有的。</p>
     */
    enum Transition { LINEAR, SPLINE }

    /** 当前使用的过渡方式（A/B 用；默认 LINEAR = 参考原样）。 */
    private Transition transition = Transition.LINEAR;

    /** 切换到样条过渡（返回 this 便于链式调用）。 */
    public CombineCandidate useSplineTransition(double derivAtBorder, double derivAtSelected) {
        this.transition = Transition.SPLINE;
        this.splineD0 = derivAtBorder;
        this.splineD1 = derivAtSelected;
        return this;
    }

    /** 样条两端的导数（MC 语义：会乘以段宽 dx=1）。 */
    private double splineD0 = 0.5;
    private double splineD1 = 0.5;

    /** 区域层：{@code RegionLerper(borders, RegionSelector(slots))}。 */
    private double regionHeight(double x, double z) {
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        if (envelopeTransition) {
            return envelopeHeight(s, x, z);
        }
        int idx = RegionPartition.selectIndex(s.regionId(), slots.length);
        double selected = slots[idx].height01(x, z);
        double edge = s.edge();
        if (edge >= 1.0) return selected;
        double b = borderAt(x, z);
        if (transition == Transition.LINEAR) {
            return b + (selected - b) * edge;
        }
        return hermiteLerp(b, selected, edge, splineD0, splineD1);
    }

    /**
     * <b>包络过渡</b>：两类型之间插值 {@code lo/hi}，共用噪声，<b>平原 border 不参与</b>。
     *
     * <h3>与传统 {@code lerp(border, selected, edge)} 的根本区别</h3>
     * <pre>
     *   旧：h = lerp(border的成品高度, 选中类型的成品高度, edge)
     *        ↑ 两端是【两团不同的噪声】，插值把形态平均掉
     *        ↑ 且一端永远是公共 border（平原）⇒ 所有类型交界都退化到平原
     *
     *   新：lo = lerp(lo_A, lo_B, t)      ← 插值【包络标量】
     *        hi = lerp(hi_A, hi_B, t)
     *        h  = lo + (hi − lo) · n       ← n 是【共用噪声】
     *        ↑ 过渡时 n 不变，只有包络变形 ⇒ 山谷还是山谷，只是换了个"高度带"
     *        ↑ A/B 就是边界两侧那两个<b>真实类型</b>，不经过任何公共地形
     * </pre>
     *
     * <h3>为什么用 {@code t = d1/(d1+d2)} 而不是 {@code edge}</h3>
     * <p>{@code edge} 由 {@code d1/d2} 经 {@code pow(·,1.5)} 与 {@code [0,0.5]} 归一化得出，
     * {@code d1/d2 ≤ 0.37} 后<b>恒为 1</b>（覆盖面仅 cell 的约 30%）。
     * 而 {@code t = d1/(d1+d2)} 在整格内单调，{@code t=0.5} 恰在边界、
     * {@code t→1} 深入本格 ⇒ <b>全空间连续</b>，过渡覆盖整个 cell。</p>
     *
     * <h3>噪声共用带来的守恒</h3>
     * <p>{@code n} 是同一个场，所以 {@code t} 变化时输出连续。
     * 跨界处（{@code t=0.5}）两侧分别以 A/B 和 B/A 计算，
     * {@code lo/hi} 插值结果<b>相同</b>（对称）⇒ C0 连续，不产生接缝。</p>
     */
    /**
     * <b>Catmull-Rom 类型过渡</b>：两类型之间按 {@code [0, 0.3, 0.7, 1] } 样条混合高度。
     *
     * <h3>依据 {@code docs/用户需求记录.md} §八.2 原文</h3>
     * <pre>
     *   替换 smoothstep：使用 NoiseUtil.spline（Catmull-Rom样条）实现 C1 连续过渡
     *   过渡区间：控制点 [0.0, 0.3, 0.7, 1.0]，过渡区宽度 0.4 权重空间（≈25格）
     *   消除悬崖：相邻类型渐近过渡，不再 1 格硬切
     * </pre>
     * <p>以及 §七：</p>
     * <pre>
     *   核心区（权重&gt;0.7）：纯类型，不被稀释
     *   过渡区：较宽（数十格）的 lerp 渐近过渡
     * </pre>
     *
     * <h3>三段的语义</h3>
     * <pre>
     *   t ∈ [0.0, 0.3]  w=0    纯类型 A（A 高度原样输出，B 完全不参与）
     *   t ∈ [0.3, 0.7]  w 0→1  Catmull-Rom 过渡带（C1 连续，无悬崖）
     *   t ∈ [0.7, 1.0]  w=1    纯类型 B
     * </pre>
     *
     * <h3>为什么两端要"纯"</h3>
     * <p>若全程插值（如 {@code w = t}），则<b>任何位置都在混合</b>，
     * 类型内部形态被持续稀释 ⇒ §七 说的"山脉不像山脉、高原不像高原"。
     * 锁定两端后，每个 cell 的绝大部分面积是<b>该类型自己的完整配方</b>，
     * 只有贴边界的 40% 带做过渡。</p>
     *
     * <h3>与旧实现的区别</h3>
     * <table border="1">
     *   <tr><th></th><th>旧（{@code lerp(border, selected, edge)}）</th><th>新（本方法）</th></tr>
     *   <tr><td>端点 A</td><td>公共 border（平原）</td><td><b>边界另一侧的真实类型</b></td></tr>
     *   <tr><td>端点 B</td><td>选中类型</td><td>本 cell 的类型</td></tr>
     *   <tr><td>过渡曲线</td><td>线性</td><td><b>Catmull-Rom（4 控制点）</b></td></tr>
     *   <tr><td>纯类型区</td><td>无（全程插值）</td><td><b>两端各 30%</b></td></tr>
     * </table>
     */
    private double envelopeHeight(RegionPartition.Sample s, double x, double z) {
        int ia = RegionPartition.selectIndex(s.regionId(), slots.length);
        int ib = RegionPartition.selectIndex(s.regionId2(), slots.length);

        // t: 1 = 深入本格（纯 A），0.5 = 恰在边界
        double t = s.weightNear();
        // 转成"类型轴位置"：边界处 t=0.5 → axis=0.5；深入本格 → axis→1；
        // 深入邻格（理论上 t<0.5）→ axis<0.5。这里把 [0.5,1] 映射到 [0,1]，
        // 即 axis = 2t-1，再夹到 [0,1]。
        double axis = clamp01(2.0 * t - 1.0);
        double w = catmullRomWeight(axis);            // 0 = 纯 A，1 = 纯 B

        if (w <= 0.0) return slots[ia].height01(x, z);   // 纯 A，零开销短路
        if (w >= 1.0) return slots[ib].height01(x, z);   // 纯 B

        double hA = slots[ia].height01(x, z);
        double hB = slots[ib].height01(x, z);
        return hA + (hB - hA) * w;
    }

    /**
     * Cubic Hermite 插值（逐字节对齐 {@code SplineUtil.splint}，零 MC 依赖）。
     *
     * <p>两个控制点 {@code (0, v0)} 与 {@code (1, v1)}，段宽 {@code dx = 1}：</p>
     * <pre>
     *   h00 = 2t³−3t²+1   h10 = t³−2t²+t
     *   h01 = −2t³+3t²    h11 = t³−t²
     *   h   = h00·v0 + h10·(d0·dx) + h01·v1 + h11·(d1·dx)
     * </pre>
     *
     * <p>MC 特性：导数<b>乘以段宽</b>后才参与插值（{@code SplineUtil:73-74}）。</p>
     */
    private static double hermiteLerp(double v0, double v1, double t,
                                      double d0, double d1) {
        if (t <= 0) return v0;
        if (t >= 1) return v1;
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1;
        double h10 = t3 - 2 * t2 + t;
        double h01 = -2 * t3 + 3 * t2;
        double h11 = t3 - t2;
        return h00 * v0 + h10 * (d0 * 1.0) + h01 * v1 + h11 * (d1 * 1.0);
    }

    /**
     * 边界地形：按<b>连续位置场</b>在"平原 border"与"山地 border"之间插值。
     *
     * <h3>为什么模式不能按 Voronoi 单元逐格查表</h3>
     * <p>{@code RegionLerper} 之所以无缝，是因为<b>两侧在 {@code edge=0} 时收敛到同一个
     * border</b>。若 border 按"当前所属单元"取值，跨单元分界线时最近单元切换 ⇒ 模式切换
     * ⇒ 两侧 border 不同 ⇒ {@code edge=0} 处直接跳变 ⇒ 沿 Voronoi 边界印出直线疤痕
     * （本项目历史上"平行带/直线网"的同型问题）。</p>
     *
     * <p>改用<b>位置函数</b> {@code m(x,z)} 后，分界线两侧读到同一个 {@code m}
     * ⇒ 处处连续。而 {@code m} 的相关长度远大于格距（1200wu），所以
     * <b>成片的单元共享同一模式</b> ⇒ 视觉上就是"一部分 Voronoi 单元边界是山地"。</p>
     *
     * @return 边界地形高度（0..1 归一化）
     */
    private double borderAt(double x, double z) {
        double plain = border.height01(x, z);
        if (mountainBorder == null) return plain;
        double m = mountainMode(x, z);
        if (m <= 1e-4) return plain;
        double mtn = mountainBorder.height01(x, z);
        return plain + (mtn - plain) * m;
    }

    /**
     * 边界模式权重 ∈ [0,1]：0 = 边界取平原（参考原样），1 = 边界取山地（worldgen 语义）。
     *
     * <p>用 smoothstep 塑形使大部分区域落在两端的饱和区（明确的"平原模式"或"山地模式"），
     * 只有中间一段是渐变 —— 既得到成片的带状山系，又不会在过渡处产生阶跃。</p>
     */
    private double mountainMode(double x, double z) {
        if (modeNoise == null) return 0.0;
        float v = modeNoise.compute((float) x, (float) z);   // 已展宽到 [0,1]
        float t = (v - MODE_LOW) / (MODE_HIGH - MODE_LOW);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    /** smoothstep 的两个端点（作用在已展宽的 [0,1] 模式噪声上）。 */
    private static final float MODE_LOW = 0.52f;
    private static final float MODE_HIGH = 0.78f;

    /** 启用"按区域概率选择边界模式"。{@code scale} 为模式场的相关长度（wu）。 */
    public CombineCandidate regionalBorder(Family mountainBorderRecipe, float scale) {
        this.mountainBorder = mountainBorderRecipe;
        int s = (int) (borderModeSeed ^ (borderModeSeed >>> 32)) + 60613;
        RefNoise.Node raw = RefNoise.Perlin.ofScale(s, scale, 3);
        this.modeNoise = new RefNoise.Mapped(
                new RefNoise.Clamped(raw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);
        return this;
    }

    /**
     * <b>边界反转</b>：把 {@code RegionLerper} 的"边界地形"从平原换成山地。
     *
     * <p>参考的 {@code RegionLerper.java:16-41} 是 {@code lerp(border, selected, edge)}，
     * 而 {@code border = makeBorder()} = <b>平原</b>（{@code Populators.java:419-421}）。
     * 即 {@code edge=0}（恰在 Voronoi 边界上）取平原 = <b>最低</b> ⇒ 每个类型单元被
     * 一圈低地包住 ⇒ 视觉上必然是"一个个圆斑/多边形块"。</p>
     *
     * <p>worldgen-master 的做法正好相反（{@code elevation.rs:279}）：
     * {@code offset = peak * gaussian(dist, mw)}，{@code dist=0}（边界上）<b>最高</b>
     * ⇒ 山脊沿边界延伸 ⇒ 视觉上是<b>带</b>。</p>
     *
     * <p>本开关把边界地形换成山地，即采用 worldgen 的语义。<b>不改 Voronoi</b>
     * —— 参考和我们的 Voronoi 都是各向同性圆形格，band 从来不是 Voronoi 给的。</p>
     */
    private boolean invertBorder = false;
    private Family borderRecipe;

    /** "按区域概率选择边界模式"用：山地 border 与模式场。 */
    private Family mountainBorder;
    private RefNoise.Node modeNoise;
    private long borderModeSeed;

    /** 启用边界反转（边界处 = 山地，单元内部 = 选中类型）。 */
    public CombineCandidate invertBorder(Family mountainRidge) {
        this.invertBorder = true;
        this.borderRecipe = mountainRidge;
        return this;
    }

    // ==================== 构造偏置（σ=80 剖面） ====================

    /**
     * 构造剖面 → 类型偏置。σ 取 80（探针实测：伸长比 3.01、分量 7、长尾比 5.8 最干净）。
     *
     * <p><b>为什么用"按强度插值高度"而不是"切换类型"</b>：类型切换是离散的，
     * 在 {@code orogen} 跨越阈值的等值线上会产生阶跃 —— 正是本项目反复踩的坑
     * （{@code btype} 硬分支、{@code rate} 分片常数都是同类问题）。
     * 改为 {@code lerp(区域高度, 构造类型高度, w)}，{@code w} 是连续的构造强度，
     * 效果等价于"构造强的地方类型偏向山地"，但处处连续。</p>
     *
     * <p>{@code orogen}/{@code rift} 只用连续量 {@code stress} + {@code dist}，
     * <b>不用</b> {@code btype}/{@code rate}（项目已明令禁止）。</p>
     */
    private double tectonicBias(double x, double z) {
        if (tectonic == null) return regionHeight(x, z);
        com.geogenesis.worldgen.terrain.TectonicField.Sample s = tectonic.sample(x, z);
        double d = s.dist();
        double g = Math.exp(-(d * d) / (2.0 * TECTONIC_SIGMA * TECTONIC_SIGMA));
        if (g <= 1e-6) return regionHeight(x, z);

        double orogen = com.geogenesis.worldgen.terrain.TectonicField.smoothPos(
                s.stress(), com.geogenesis.worldgen.terrain.TectonicField.STRESS_POS_EPS) * g;
        double rift = com.geogenesis.worldgen.terrain.TectonicField.smoothPos(
                -s.stress(), com.geogenesis.worldgen.terrain.TectonicField.STRESS_POS_EPS) * g;
        double base = regionHeight(x, z);

        double h = base;
        if (orogen > 0) {
            double mtn = mountainFamily.height01(x, z);
            h = h + (mtn - h) * clamp01(orogen * TECTONIC_OROGEN_GAIN);
        }
        if (rift > 0) {
            double bsn = basinFamily.height01(x, z);
            h = h + (bsn - h) * clamp01(rift * TECTONIC_RIFT_GAIN);
        }
        return h;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /**
     * 最终陆地高度的等价量（<b>未</b>加 {@code GROUND_E}，返回 e 空间增量）。
     *
     * <h3>★ 2026-10-30 修正：{@code max(0)} 的位置错了</h3>
     * <p>参考 {@code TerrainPopulator.apply}（{@code Populators.java:21}）是：</p>
     * <pre>
     *   cell.height = Math.max(base + height, 0.0F);   // ← max 包住 base+height
     * </pre>
     * <p>而本类此前写成：</p>
     * <pre>
     *   height01 = Math.max(blender.apply(x,z), 0)     // ← max 只包 height
     *   e        = GROUND_E + height01 * E_SPAN        // ← 基准在削平【之后】才加
     * </pre>
     * <p><b>后果</b>：{@code steppe} 族输出 {@code [-0.02, +0.06]}（与参考一致），
     * 负值部分被 {@code max(0)} 先削平，之后再加 {@code GROUND_E} 已经救不回来
     * ⇒ 图上出现大片 {@code Y=GROUND_E 对应值}的平顶。
     * 调 {@code GROUND_E} 只会让平台<b>换位置</b>（实测 67.73 → 121.19），平台本身不消失。</p>
     *
     * <p>现在改为返回<b>未截断</b>的原始增量，由 {@link #e} 在加完基准后再截断，
     * 与参考同构。负值深度最多 {@code -0.02}（steppe），
     * 相对 {@code GROUND_E=0.2461} 有 12 倍余量 ⇒ {@code max} 实际不触发。</p>
     */
    public double height01(double x, double z) {
        return blender.apply(x, z);
    }

    /**
     * 换算到 e：{@code max(GROUND_E + h·E_SPAN, 0)}。
     *
     * <p>与参考 {@code Math.max(base + height, 0)} <b>同构</b>：基准与增量<b>先相加再截断</b>，
     * 而不是先截断增量。这是 2026-10-30 修正的核心 —— 见 {@link #height01} 的说明。</p>
     */
    public double e(double x, double z) {
        return Math.max(GROUND_E + height01(x, z) * E_SPAN, 0.0);
    }

    /** 未加共享基准的直译口径（{@code h × 1.08}），仅用于与上一版对照。 */
    public double absoluteE(double x, double z) {
        return height01(x, z) * 1.08;
    }

    /** 主导类别（类型图着色用）。 */
    public Category category(double x, double z) {
        return blender.category(x, z);
    }

    // ==================== 诊断访问器（供 FlatSpotCauseProbe 归因）====================

    /** 区域层原始高度（{@code RegionLerper} 输出，<b>未经</b> {@code max(...,0)} 截断）。 */
    public double debugRegionHeight(double x, double z) {
        return regionHeight(x, z);
    }

    /** {@code Blender.apply} 原始输出（− 山形遮罩混合，同样未截断）。 */
    public double debugBlenderApply(double x, double z) {
        return blender.apply(x, z);
    }

    /** 山形遮罩值 [0,1]。 */
    public float debugMountainMask(double x, double z) {
        return blender.mask(x, z);
    }

    /** 区域边缘程度 [0,1]（1 = 纯族区）。 */
    public float debugEdge(double x, double z) {
        return partition.sample((float) x, (float) z).edge();
    }

    /** 边界地形高度（{@code borderAt}）。 */
    public double debugBorderAt(double x, double z) {
        return borderAt(x, z);
    }

    /** 选中族的原始高度（未经 {@code edge} 混合）。 */
    public double debugSelected(double x, double z) {
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        int idx = RegionPartition.selectIndex(s.regionId(), slots.length);
        return slots[idx].height01(x, z);
    }

    /**
     * 类型图的构造带宽判据。
     *
     * <p>{@link Blender#category} 只看山形遮罩与槽位投票，<b>不知道 border 模式</b>——
     * 于是"按区域选择边界模式"改了高度却改了类型图（上一版实测：地形出带、类型图不变）。
     * 本方法补上这条判据：<b>border 模式接近山地 + 靠近 Voronoi 边界</b>时判为山地带。</p>
     *
     * <p>判据里用 {@code 1 − edge}（edge=1 格心、0 边界），与 height 的 border 权重同源，
     * 所以类型图的位置与地形上的山脊位置一致。</p>
     */
    public Category categoryWithBorders(double x, double z) {
        Category base = blender.category(x, z);
        if (mountainBorder == null) return base;
        // 山形遮罩已经判为山地 → 保持
        if (base == Category.MOUNTAINS) return base;
        double m = mountainMode(x, z);
        if (m < BORDER_CAT_MIN_MODE) return base;
        RegionPartition.Sample s = partition.sample((float) x, (float) z);
        // 靠近边界（edge 小）且模式偏山地 ⇒ 判为构造带
        double nearEdge = 1.0 - clamp01(s.edge() / BORDER_CAT_EDGE_SPAN);
        return (m * nearEdge) >= BORDER_CAT_THRESHOLD ? Category.MOUNTAINS : base;
    }

    /** 类型图判据：模式权重下限、边界影响跨度、综合阈值。 */
    private static final double BORDER_CAT_MIN_MODE = 0.35;
    private static final double BORDER_CAT_EDGE_SPAN = 0.55;
    private static final double BORDER_CAT_THRESHOLD = 0.22;

    /** 诊断：山形遮罩值（0 = 纯区域，1 = 纯山地）。 */
    public float mountainMask(double x, double z) {
        return blender.mask(x, z);
    }

    /**
     * {Heightmap.java:115-119 + 166}：山形遮罩 + Blender 覆盖。
     *
     * <pre>
     * mask = worleyEdge(2250, DISTANCE_2_ADD, EUCLIDEAN)
     *      @ warpPerlin(seed, 333, 2, 250)
     *      @ curve(CURVE3) @ clamp(0, 0.9) @ map(0, 1)
     * </pre>
     */
    private final class Blender {
        private final RefNoise.Node mask;
        private final Family mountain;

        Blender(long worldSeed, java.util.function.IntSupplier next) {
            int base = (int) (worldSeed ^ (worldSeed >>> 32));
            RefNoise.Node mc = new RefNoise.WorleyEdgeAdd(base + 4241, 1f / MOUNTAIN_SHAPE_SCALE);
            mc = RefNoise.warpPerlin(mc, next.getAsInt(), 333f, 2, 250f);
            // Noises.curve(CURVE3) = 对已归一化到 0..1 的值套 hermite
            mc = new RefNoise.Curve3(mc);
            mc = new RefNoise.Clamped(mc, 0f, 0.9f);
            mc = new RefNoise.Mapped(mc, 0f, 1f, 0f, 1f);
            this.mask = mc;
            // 山地"族"：本沙箱用单一山地变体代表参考的三档（默认预设 mountainVariety=0，
            // 参考此时是三条独立 unmixable 条目，族形态一致，只差种子）
            this.mountain = unmixable.length > 0 ? unmixable[0] : border;
        }

        double apply(double x, double z) {
            float select = mask.compute((float) x, (float) z);
            double regionH = tectonicBias(x, z);
            if (select < MTN_BLEND_LOW) return regionH;
            double mtnH = mountain.height01(x, z);
            if (select > MTN_BLEND_HIGH) return mtnH;
            float alpha = (select - MTN_BLEND_LOW) / (MTN_BLEND_HIGH - MTN_BLEND_LOW);
            return regionH + (mtnH - regionH) * alpha;
        }

        Category category(double x, double z) {
            float select = mask.compute((float) x, (float) z);
            float midpoint = MTN_BLEND_LOW + (MTN_BLEND_HIGH - MTN_BLEND_LOW) * MTN_BLEND_SPLIT;
            if (select >= midpoint) return Category.MOUNTAINS;
            RegionPartition.Sample s = partition.sample((float) x, (float) z);
            return slotCats[RegionPartition.selectIndex(s.regionId(), slots.length)];
        }

        float mask(double x, double z) {
            return mask.compute((float) x, (float) z);
        }
    }
}