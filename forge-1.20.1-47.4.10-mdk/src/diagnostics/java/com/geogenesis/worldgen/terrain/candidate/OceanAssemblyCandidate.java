package com.geogenesis.worldgen.terrain.candidate;

/**
 * 第 6 步：<b>海陆衔接</b> —— 把陆地层（{@link CombineCandidate}）与海洋层按参考的
 * {@code ContinentLerper3 + ContinentLerper2} 组装成完整世界。
 *
 * <h3>参考的分层（{@code Heightmap.java:168-182}）</h3>
 * <pre>
 * // 1) 三种海洋按 continentEdge 分配
 * oceans = ContinentLerper3(deepOcean, shallowOcean, coast,
 *                           controlPoints.deepOcean = 0.1,
 *                           controlPoints.shallowOcean = 0.25,
 *                           controlPoints.coast = 0.448)      // CURVE3 插值
 *
 * // 2) 海洋 ↔ 陆地
 * terrain = ContinentLerper2(oceans, land,
 *                            controlPoints.shallowOcean = 0.25,
 *                            controlPoints.inland = 0.502)     // LINEAR 插值
 *
 * // 3) 陆地层内部
 * land = Blender(山形遮罩, RegionLerper(border, RegionSelector(...)), mountains, 0.3, 0.8, 0.575)
 * </pre>
 *
 * <p>{@code controlPoints} 取自 {@code Presets.java:24}：
 * {@code new ControlPoints(0.0F, 0.074F, 0.1F, 0.25F, 0.327F, 0.448F, 0.502F)}。</p>
 *
 * <h3>海洋配方是<b>常量</b>（这一点很容易想复杂）</h3>
 * <ul>
 *   <li>{@code makeShallowOcean}（{@code Populators.java:65-69}）：{@code constant(levels.water(-oceanDepth/9))}
 *       —— 浅海是<b>一个常数深度</b>，没有噪声。</li>
 *   <li>{@code makeCoast}（{@code Populators.java:71-73}）：{@code constant(levels.water)} —— 海岸就是水位。</li>
 *   <li>只有 {@code makeDeepOcean}（{@code Populators.java:34-63}）有形态：
 *       {@code blend(selector, hills, canyons, 0.6, 0.65)} + warp + clamp，
 *       其中 {@code hills = map(perlin(150,3), lower, upper)}、
 *       {@code canyons = invert(powCurve(perlin(150,4), 0.2))}、{@code selector = perlin(500,1)}。</li>
 * </ul>
 * <p>所以完整的海陆过渡是"噪声海底 → 常数浅海 → 水位海岸 → 陆地"四段，靠两层级联 lerper 缝合。</p>
 *
 * <h3>本项目的坐标换算</h3>
 * <p>参考的 {@code continentEdge ∈ [0,1]}（0 = 最外海）。我们的 {@link com.geogenesis.worldgen.terrain.ContinentField}
 * 给 c ∈ [-1,1]（负 = 海洋）。这里按 {@code edge = (c + 1) / 2} 线性映射，
 * 使 {@code controlPoints} 的数值可以直接沿用。</p>
 *
 * <h3>高度单位</h3>
 * <p>参考的水位 {@code levels.water} 与地面 {@code levels.ground} 都是常量；
 * 本项目 {@code HeightCurve} 以 e=0 为海平面，所以海洋侧直接用负 e：
 * 深海 −{@code oceanDepth} 格、浅海 −{@code oceanDepth/9} 格、海岸 0。
 * 陆地层仍用 {@link CombineCandidate#GROUND_E} 起的相对高度。</p>
 */
public final class OceanAssemblyCandidate {

    /** {WorldSettings.Properties.DEFAULT_OCEAN_DEPTH}：默认 63 格。 */
    private static final int OCEAN_DEPTH = 63;

    /** {Presets.java:24} controlPoints（deepOcean, shallowOcean, coast, inland）。 */
    private static final float CP_DEEP_OCEAN = 0.1f;
    private static final float CP_SHALLOW_OCEAN = 0.25f;
    private static final float CP_COAST = 0.448f;
    private static final float CP_INLAND = 0.502f;

    /** 陆侧 e 到格：{@code HeightCurve} 陆侧斜率 ≈ 236 格/e。 */
    private static final double BLOCKS_PER_E_LAND = 236.0;
    /** 海侧 e 到格：海侧斜率 ≈ 127 格/e。 */
    private static final double BLOCKS_PER_E_SEA = 127.0;

    /** 海洋类型（供类型图着色）。 */
    public enum Sea { DEEP_OCEAN, SHALLOW_OCEAN, COAST, LAND }

    private final CombineCandidate land;
    /** 大陆性采样器：可切换 FBM（生产）与 Voronoi 领地（候选）。 */
    private final EdgeSource edgeSource;
    private final RefNoise.Node deepOcean;

    /** 大陆性来源抽象：{@code c ∈ [−1,1]}。 */
    public interface EdgeSource {
        double sample(double x, double z);
    }

    public OceanAssemblyCandidate(CombineCandidate land,
                                  com.geogenesis.worldgen.terrain.ContinentField continent,
                                  long worldSeed) {
        this(land, continent::sample, worldSeed);
    }

    public OceanAssemblyCandidate(CombineCandidate land, ProvinceContinentField province,
                                  long worldSeed) {
        this(land, province::sample, worldSeed);
    }

    public OceanAssemblyCandidate(CombineCandidate land, EdgeSource source, long worldSeed) {
        this.land = land;
        this.edgeSource = source;
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        this.deepOcean = buildDeepOcean(base);
    }

    /**
     * {@code makeDeepOcean}（{@code Populators.java:34-63}）的 e 空间等价物。
     *
     * <pre>
     * lower      = -oceanDepth            （-63 格）
     * upper      = -max(8, oceanDepth/3)  （-21 格）
     * canyonUp   = -(minDepth + (oceanDepth-minDepth)/2)
     * hills      = map(perlin(150,3), lower, upper)
     * canyons    = map(invert(powCurve(perlin(150,4), 0.2)), lower, canyonUp)
     * selector   = perlin(500,1)
     * height     = blend(selector, hills, canyons, 0.6, 0.65) → warp(50,2,50) → clamp(lower,upper)
     * </pre>
     *
     * <p>返回的是<b>格</b>（负数），外面再除以 {@code BLOCKS_PER_E_SEA} 换 e。</p>
     */
    private static RefNoise.Node buildDeepOcean(int seed) {
        int minDepth = Math.max(8, OCEAN_DEPTH / 3);
        int canyonMinDepth = minDepth + Math.max(1, (OCEAN_DEPTH - minDepth) / 2);
        float lower = -OCEAN_DEPTH;
        float upper = -minDepth;
        float canyonUpper = -canyonMinDepth;

        RefNoise.Node hills = RefNoise.Perlin.ofScale(seed + 1, 150f, 3);
        hills = new RefNoise.Mapped(hills, 0f, 1f, lower, upper);

        RefNoise.Node canyons = RefNoise.Perlin.ofScale(seed + 2, 150f, 4);
        canyons = RefNoise.PowerCurve.of(canyons, 0.2f, 0f, 1f);
        canyons = new RefNoise.Inverted(canyons, 0f, 1f);
        canyons = new RefNoise.Mapped(canyons, 0f, 1f, lower, canyonUpper);

        RefNoise.Node selector = RefNoise.Perlin.ofScale(seed + 3, 500f, 1);
        RefNoise.Node h = new RefNoise.Blend(selector, hills, canyons, 0.6f, 0.65f, 0f, 1f);
        h = RefNoise.warpPerlin(h, seed + 4, 50f, 2, 50f);
        return new RefNoise.Clamped(h, lower, upper);
    }

    /** 供外部（出图脚本）读取的常量。 */
    public static double deepBlocks() { return -OCEAN_DEPTH; }
    public static double shallowBlocks() { return -Math.max(7, OCEAN_DEPTH / 9); }

    // ==================== 海岸三段 + cliffFactor ====================
    //
    // 参考 ArchipelagoPopulator.java:211-264 的分段（但我们把它接到 continentEdge 上）：
    //   shelfEnd  = 陆架终点（深海 → 浅陆架）
    //   coastEnd  = 海岸线（浅陆架 → 海滩高度 0）
    //   beachEnd  = 海滩终点（海滩 → 内陆起始）
    //   三段各用不同曲线：陆架 pow(a,1.2) 非线性、海岸线性、内陆 smoothstep
    //
    // ★ cliffFactor 是本步的核心（ArchipelagoPopulator.java:213-219）：
    //     cliffFactor      = smoothstep(-0.2, 0.6, rawVariance)     // 0=沙滩 1=崖壁
    //     activeBeachWidth = lerp(baseWidth, 0.02, cliffFactor)     // 崖壁处收到 2%
    //   于是同一段海岸线上，沙滩段海滩宽、崖壁段海滩窄到几乎为零 ——
    //   这才是"海岸地形有细节变化"的来源，而不是一条等宽的斜滩。

    /**
     * 三个控制点<b>随大陆场实现切换</b>（FBM / Voronoi 领地）。
     *
     * <h3>为什么必须换</h3>
     * <p>这套阈值是"edge 的单位"，而 edge 的分布形状由大陆场决定。
     * {@code ShoreAnchorProbe} 实测两套分布的锚点完全不同：</p>
     *
     * <pre>
     * 目标陆地 65%      FBM         Voronoi 领地
     *   anchor         0.3243        0.0445      ← 差 7 倍
     *   edge≥0.03 陆地  99.0%         66.9%
     * </pre>
     *
     * <p>Voronoi 的 edge 在领地中部才高、边缘大片低值 ⇒ 同样的陆地占比，锚点要往左挪很多。</p>
     *
     * <h3>岸段宽度保持不变</h3>
     * <p>FBM 版的三个点：{@code SHELF=0.20, SHORE=0.268, ANCHOR=0.302}
     * ⇒ 深海段 0.20、海岸段 0.034、岸线到锚点 0.034。</p>
     * <p>Voronoi 版按<b>同样的绝对宽度</b>平移（锚点 0.0445）：</p>
     * <pre>
     *   ANCHOR = 0.0445            （实测 anchor，目标陆地 65%）
     *   SHORE  = 0.0445 − 0.034 = 0.0105
     *   SHELF  = 0.0105 − 0.034 = −0.0235  ← 负数，不行
     * </pre>
     *
     * <p>压缩到 0 会毁掉海底分层，所以<b>不能简单平移</b>。改为按比例缩放：
     * Voronoi 的可用 edge 跨度只有 FBM 的 ~1/7（锚点 0.0445 vs 0.302），
     * 三段按 <b>0.0445/0.302 ≈ 0.147</b> 等比缩 ⇒ 各段宽度按"占锚点的比例"保持。</p>
     */
    private static final boolean USE_PROVINCE_CONTROLS = true;

    /** FBM 版（现行）：锚点 0.302，三段 0.20 / 0.268 / 0.302。 */
    private static final float FBM_SHELF = 0.20f;
    private static final float FBM_SHORE = 0.268f;
    private static final float FBM_ANCHOR = 0.302f;

    /**
     * Voronoi 领地版：锚点 <b>0.5</b>（c = 0，海岸线）。
     *
     * <p><b>2026-10-30 重标</b>：板块场现在直接产出 c ∈ [−1,1]，
     * 陆壳 {@code c = +0.125..+1.0}（edge 0.5625..1.0）、洋壳 {@code c = −1.0..−0.125}
     * （edge 0..0.4375），两者在 <b>edge = 0.5</b>（c = 0）两侧连续相接。
     * 锚点因此取 0.5，正好是海陆分界。</p>
     *
     * <p>三段按 {@code 0.5 / 0.302 = 1.656} 放大（Voronoi 的可用 edge 跨度比 FBM 大）：
     * {@code SHELF = 0.20×1.656 = 0.331}，{@code SHORE = 0.268×1.656 = 0.444}。
     * 深海/浅海/海岸的相对比例与 FBM 版一致。</p>
     */
    private static final float PROVINCE_ANCHOR = 0.5f;
    private static final float PROVINCE_SCALE = PROVINCE_ANCHOR / FBM_ANCHOR;   // ≈ 1.656
    private static final float PROVINCE_SHORE = FBM_SHORE * PROVINCE_SCALE;     // ≈ 0.444
    private static final float PROVINCE_SHELF = FBM_SHELF * PROVINCE_SCALE;     // ≈ 0.331

    private static float cpShelf() { return USE_PROVINCE_CONTROLS ? PROVINCE_SHELF : FBM_SHELF; }
    private static float cpShore() { return USE_PROVINCE_CONTROLS ? PROVINCE_SHORE : FBM_SHORE; }
    private static float cpAnchor() { return USE_PROVINCE_CONTROLS ? PROVINCE_ANCHOR : FBM_ANCHOR; }

    /**
     * 岸型参数（按地貌学）。
     *
     * <h3>两种岸的真实剖面（从海向陆）</h3>
     * <pre>
     * 沙岸：浅水 → 滩面（5–10°，2–3 m）→ 滩肩/后滨（高 2–5 m，近平）→ 海岸平原（缓升）
     * 崖岸：浅水 → 海蚀平台（高出平均海面 0.5–5 m，宽 10–100 m）→ 崖面（近垂直）→ 崖顶台地
     * </pre>
     *
     * <p>关键区别有两处：</p>
     * <ol>
     *   <li><b>前段的平台高度</b>：两者都在水面<b>之上数米</b>，差别是滩肩（2–5 m）略高于
     *       海蚀平台（0.5–5 m，受潮差控制）。</li>
     *   <li><b>抬升的集中程度</b>：沙岸的抬升分散在整个区间（海岸平原）；
     *       崖岸的抬升集中在末段（崖面）。</li>
     * </ol>
     *
     * <p>所以宽度分级不是靠"改区间边界"，而是靠<b>前段占多长 + 后段多陡</b>——
     * 区间总宽固定，岸线因此不动。</p>
     *
     * <h3>★ 高度必须用<b>绝对格数</b>，不能用陆高比例</h3>
     * <p>首版写成 {@code 0.02 × landB}（landB=28 ⇒ 0.56 格），比一格还低 ——
     * 平台上站不住人，读作"崖壁直接插进水里"。而现实中的滩肩与海蚀平台高度由
     * <b>潮差与浪蚀基准面</b>决定，<b>与内陆山有多高完全无关</b>：即使背后是 3000 m 的
     * 海岸山脉，滩肩也还是 2–5 m。故此处取绝对格。</p>
     */
    private static final float FLAT_END_BEACH = 0.25f;   // 沙岸：滩面占前 25%
    private static final float FLAT_END_CLIFF = 0.65f;   // 崖岸：海蚀平台占前 65%
    private static final float STEEP_END_BEACH = 1.00f;  // 沙岸：缓升铺到区间末端
    private static final float STEEP_END_CLIFF = 0.85f;  // 崖岸：崖面在 85% 处结束，之后是台地

    /** 滩肩高度（格，海面以上）。现实 2–5 m；取 3.5 格让玩家能站。 */
    private static final double FLAT_BLOCKS_BEACH = 3.5;
    /** 海蚀平台高度（格，海面以上）。现实高出平均海面 0.5–5 m；取 3.0。 */
    private static final double FLAT_BLOCKS_CLIFF = 3.0;

    /**
     * 崖壁判定噪声窗口（作用在 raw ∈ [−1,1] 上）。
     *
     * <p>取对称窗口 {@code [−0.45, 0.45]}：噪声中值 0 映射到 t=0.5 ⇒ cliffFactor 中值 ≈0.5，
     * 沙岸与崖岸大致各占一半。旧值 {@code [−0.2, 0.6]} 非对称，实测 57% 沙岸 / 29% 崖岸，
     * 崖岸偏少（全球估计：沙岸约 30–40%、岩岸 50–60%）。</p>
     */
    private static final float CLIFF_LOW = -0.45f;
    private static final float CLIFF_HIGH = 0.45f;

    /** 水下细节带：只在陆架中段生效（参考 onsetFade × shoreFade）。 */
    private static final double OCEAN_DETAIL_AMP = 4.0;   // 格
    private static final float OCEAN_DETAIL_SCALE = 42f;  // wu

    /** cliffFactor（0 = 沙滩 / 1 = 崖壁），由低频噪声给出，连续无跳变。 */
    private float cliffFactor(float edge, double x, double z) {
        if (cliffNoise == null) return 0.0f;
        float v = cliffNoise.compute((float) x, (float) z);   // 已展宽到 [0,1]
        float raw = v * 2f - 1f;                              // → [-1,1]
        float t = (raw - CLIFF_LOW) / (CLIFF_HIGH - CLIFF_LOW);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    /** 诊断：暴露 cliffFactor（探针用；不影响产出）。 */
    public float debugCliffFactor(double x, double z) {
        return cliffFactor(continentEdge(x, z), x, z);
    }

    /**
     * 三段高度（格）：{@code [shelfEnd → coastEnd → beachEnd → inlandStart]}。
     *
     * <p>返回海侧到陆侧起始的连续高度；{@code edge ≥ beachEnd} 时返回 {@code null}
     * 由调用方接陆地层（保持"海陆分界在 edge=coastAnchor"的 1:1 比）。</p>
     */
    /**
     * 三段高度（格）：{@code [shelfEnd → coastEnd → coastAnchor]}。
     *
     * <h3>cliffFactor 怎么起作用</h3>
     * <p>早先把 {@code beachEnd = coastEnd + beachW} 直接改边界 —— 但 {@code beachW} 最大
     * 0.030 而 {@code coastAnchor − coastEnd} 只有 0.028，边界会越过锚点，海陆比随之漂移。</p>
     *
     * <p>现在区间<b>固定</b>在 {@code [coastEnd, coastAnchor]}，由 {@code cliffFactor} 控制
     * 区间内的<b>抬升曲线</b>：</p>
     * <pre>
     *   cliff=0（沙滩）：alpha = pow(t, 1.6)   → 起点平缓、末端陡 ⇒ 读作"宽沙滩"
     *   cliff=1（崖壁）：alpha = pow(t, 0.35)  → 起点极陡 ⇒ 读作"窄滩紧贴崖脚"
     * </pre>
     * <p>两条曲线在 {@code t=0}（高度 0）与 {@code t=1}（高度 = 海滩顶）处都吻合，
     * 因此改变 {@code cliffFactor} <b>不会移动岸线</b>，只改变崖/滩的形态。</p>
     */
    private double coastalProfile(float edge, double x, double z) {
        double deep = deepBlocksRaw(x, z);
        double shallow = shallowBlocks();

        // 1) 深海 → 浅陆架：pow(a, 1.2) 非线性（参考 shelfFalloff）
        if (edge < cpShelf()) {
            double t = clamp01((CP_DEEP_OCEAN_EDGE_LO - edge) / (CP_DEEP_OCEAN_EDGE_LO - cpShelf()));
            double a = Math.pow(smooth(t), 1.2);
            return lerp(deep, shallow, a) + oceanDetail(edge, x, z, cpShelf());
        }
        // 2) 浅陆架 → 海岸线：浅海常数面 → 0
        if (edge < cpShore()) {
            double t = (edge - cpShelf()) / Math.max(1e-6, cpShore() - cpShelf());
            return lerp(shallow, 0.0, smooth(t)) + oceanDetail(edge, x, z, cpShelf());
        }
        // 3) 岸段：0 → 陆地基准。形态由 cliffFactor 在"沙岸剖面"与"崖岸剖面"间插值。
        //
        //    沙岸：t<0.25 抬到 12%（滩面+滩肩），0.25–1.0 缓升到 100%（海岸平原）
        //    崖岸：t<0.65 只抬到 2%（海蚀平台，几乎齐平水面），0.65–0.85 猛升到 100%（崖面），
        //          0.85–1.0 持平（崖顶台地）
        //
        //    ★ 两条剖面都用 smoothstep 塑形，且各段衔接处端点导数一致 ⇒ 全程 C¹。
        //      首版用 pow(t,p)，在 t=0 或 t=1 会导数发散/不归零，实测 h'' 达 1.9e7（尖角）。
        double t = clamp01((edge - cpShore()) / Math.max(1e-6, cpAnchor() - cpShore()));
        double landB = landE(x, z) * BLOCKS_PER_E_LAND;
        float cliff = cliffFactor(edge, x, z);
        // shoreShape 直接返回格数（含绝对高度的滩肩/海蚀平台），不再乘 landB
        return shoreShape(t, cliff, landB);
    }

    /**
     * 岸段抬升曲线（0..1 → 0..1）：沙岸与崖岸两种剖面的连续插值。
     *
     * <pre>
     * 沙岸（cliff=0）                       崖岸（cliff=1）
     *   t∈[0, 0.25]  0 → 3.5 格              t∈[0, 0.65]  0 → 3.0 格   海蚀平台
     *   t∈[0.25, 1]  3.5 → landB 格           t∈[0.65,0.85] 3.0 → landB 崖面
     *                                        t∈[0.85, 1]  landB         崖顶台地
     * </pre>
     *
     * <p><b>高度用绝对格</b>（{@link #FLAT_BLOCKS_BEACH} / {@link #FLAT_BLOCKS_CLIFF}），
     * 不按陆高比例 —— 滩肩与海蚀平台由潮差与浪蚀基准面控制，与内陆山高无关。</p>
     */
    private static double shoreShape(double t, float cliff, double landB) {
        double flatEnd = lerp(FLAT_END_BEACH, FLAT_END_CLIFF, cliff);
        double steepEnd = lerp(STEEP_END_BEACH, STEEP_END_CLIFF, cliff);
        // 平台绝对高度：但不得超过陆高（否则"平台"比身后的陆地还高）
        double flatBlocks = Math.min(lerp(FLAT_BLOCKS_BEACH, FLAT_BLOCKS_CLIFF, cliff),
                landB * 0.5);

        if (t <= flatEnd) {
            // 前段：水面 → 滩肩 / 海蚀平台（绝对格）
            double u = flatEnd > 1e-6 ? t / flatEnd : 1.0;
            return flatBlocks * smooth(u);   // 返回格
        }
        if (t >= steepEnd) {
            // 崖顶台地（沙岸 steepEnd=1 时此支不生效）
            return landB;
        }
        // 中段：平台 → 陆地基准（格空间插值）
        double u = (t - flatEnd) / Math.max(1e-6, steepEnd - flatEnd);
        return flatBlocks + (landB - flatBlocks) * smooth(u);
    }

    /** 深海起点（edge 最小值，用于把深海噪声铺到 [0, shelfEnd]）。 */
    private static final float CP_DEEP_OCEAN_EDGE_LO = 0.0f;
    /** 海滩顶高度（格）：比海平面高一点，随后由陆地层接管。 */
    private static final double BEACH_TOP_BLOCKS = 3.0;

    /**
     * 水下细节带：只在陆架中段非零（参考 {@code onsetFade × shoreFade}）。
     *
     * <p>靠岸处与远海处都淡出到 0 —— 保证与两侧平滑相接，不在带的端点产生台阶。</p>
     */
    private double oceanDetail(float edge, double x, double z, float shelfEnd) {
        if (detailNoise == null) return 0.0;
        float onsetWidth = shelfEnd * 0.30f;
        float shoreFadeWidth = shelfEnd * 0.20f;
        float shoreFadeStart = shelfEnd - shoreFadeWidth;
        double onsetFade = smooth(clamp01(edge / Math.max(1e-6, onsetWidth)));
        double shoreFade = 1.0 - smooth(clamp01((edge - shoreFadeStart)
                / Math.max(1e-6, shelfEnd - shoreFadeStart)));
        double fade = onsetFade * shoreFade;
        if (fade <= 1e-4) return 0.0;
        return detailNoise.compute((float) x, (float) z) * OCEAN_DETAIL_AMP * fade;
    }

    /** 深海/浅海噪声（edge 用）。 */
    private RefNoise.Node cliffNoise;
    private RefNoise.Node detailNoise;
    /** 是否启用"三段海岸 + cliffFactor + 水下细节"。false ⇒ 走参考原样的两层线性插值。 */
    private boolean coastalDetail = false;

    /** 启用三段海岸 + cliffFactor。{@code seed} 用于派生噪声。 */
    public OceanAssemblyCandidate enableCoastalDetail(long seed) {
        this.coastalDetail = true;
        int base = (int) (seed ^ (seed >>> 32));
        // cliff 噪声：波长 900wu（一段岸线的尺度），展宽到 [0,1]
        RefNoise.Node cRaw = RefNoise.Perlin.ofScale(base + 5150, 900f, 3);
        this.cliffNoise = new RefNoise.Mapped(
                new RefNoise.Clamped(cRaw, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);
        // 水下细节噪声：ridge 形态，波长 420wu
        this.detailNoise = RefNoise.PerlinRidge.ofScale(base + 6260, 420f, 2);
        return this;
    }

    private static double smooth(double t) {
        double c = t < 0 ? 0 : (t > 1 ? 1 : t);
        return c * c * (3 - 2 * c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** {@code continentEdge = (c + 1) / 2}。 */
    private float continentEdge(double x, double z) {
        double c = edgeSource.sample(x, z);
        return (float) ((c + 1.0) * 0.5);
    }

    /** 深海段高度（保留参考原样，供 edge < shelfEnd 使用）。 */
    private double oceanBlocks(float edge, double x, double z) {
        double shallow = shallowBlocks();
        double coast = 0.0;
        if (edge < CP_DEEP_OCEAN) return deepBlocksRaw(x, z);
        if (edge > cpAnchor()) return coast;
        if (edge < CP_SHALLOW_OCEAN) {
            float a = hermite((edge - CP_DEEP_OCEAN) / (CP_SHALLOW_OCEAN - CP_DEEP_OCEAN));
            return lerp(deepBlocksRaw(x, z), shallow, a);
        }
        float a = hermite((edge - CP_SHALLOW_OCEAN) / Math.max(1e-6f, cpAnchor() - CP_SHALLOW_OCEAN));
        return lerp(shallow, coast, a);
    }

    private double deepBlocksRaw(double x, double z) {
        return deepOcean.compute((float) x, (float) z);
    }

    /** 陆地段高度（e）。 */
    private double landE(double x, double z) {
        return land.e(x, z);
    }

    /**
     * 完整世界高度（格）。
     *
     * <p>海侧走三段海岸剖面（含 cliffFactor 与水下细节带），陆侧从
     * {@code beachEnd} 起由 {@link CombineCandidate} 接管，保证海陆分界仍在
     * {@code edge = coastAnchor} 附近（维持 1:1）。</p>
     */
    public double heightBlocks(double x, double z) {
        float edge = continentEdge(x, z);
        double landB = landE(x, z) * BLOCKS_PER_E_LAND;

        if (!coastalDetail) {
            // 参考原样：ContinentLerper2(oceans, land, 0.25, 0.502) 的线性插值
            if (edge < CP_SHALLOW_OCEAN) return oceanBlocks(edge, x, z);
            if (edge > CP_INLAND) return landB;
            float a = (edge - CP_SHALLOW_OCEAN) / (CP_INLAND - CP_SHALLOW_OCEAN);
            return lerp(oceanBlocks(edge, x, z), landB, a);
        }

        if (edge >= cpAnchor()) return landB;
        return coastalProfile(edge, x, z);
    }

    /** 完整世界 e（供 HeightCurve 用；海侧按 127 格/e、陆侧 236 格/e 反算）。 */
    public double e(double x, double z) {
        double blocks = heightBlocks(x, z);
        return blocks >= 0 ? blocks / BLOCKS_PER_E_LAND : blocks / BLOCKS_PER_E_SEA;
    }

    private static double smoothstep01(double t) {
        double c = t < 0 ? 0 : (t > 1 ? 1 : t);
        return c * c * (3 - 2 * c);
    }

    /**
     * 分段类型（类型图着色）。
     *
     * <h3>2026-10-29 修正：改读<b>高度</b>而非固定 edge 阈值</h3>
     * <p>原来按 {@code edge < CP_SHELF / CP_SHORE / CP_COAST} 分类。但三段海岸重锚后
     * 岸线位置会随 {@code landB}（陆地基准高度）变化 —— 固定阈值与地形脱节，
     * 类型图上蓝色的位置和地形里的水面位置对不上（实测已出现：三臂蓝色区大小不同但位置相同）。</p>
     *
     * <p>改为<b>读实际高度</b>：与 {@code HeightCurve} 的海平面判据同源，
     * 从定义上不可能矛盾。阈值直接用格数：
     * <ul>
     *   <li>{@code blocks >= 0} → 陆地</li>
     *   <li>{@code blocks >= -BEACH_DEPTH_BLOCKS(2)} → 海岸（浅水 + 出水沙滩带）</li>
     *   <li>{@code blocks >= -SHALLOW_DEPTH_BLOCKS(20)} → 浅海（大陆架）</li>
     *   <li>更深 → 深海</li>
     * </ul>
     */
    public Sea sea(double x, double z) {
        double blocks = heightBlocks(x, z);
        if (blocks >= 0) return Sea.LAND;
        if (blocks >= -BEACH_DEPTH_BLOCKS) return Sea.COAST;
        if (blocks >= -SHALLOW_DEPTH_BLOCKS) return Sea.SHALLOW_OCEAN;
        return Sea.DEEP_OCEAN;
    }

    /** 分类阈值（格，负值 = 水面以下深度）。 */
    private static final double BEACH_DEPTH_BLOCKS = 2.0;
    /**
     * 浅海（大陆架）的最大深度。
     *
     * <p>与 {@code CP_SHELF=0.20} 标定配套：那里浅海高度是 {@code shallowBlocks()} = −7 格。
     * 取 9 格让 {@code sea()} 的分类与高度剖面一致（分类读高度，所以阈值必须与剖面
     * 在该层能达到的最深处对齐，否则会漏判）。</p>
     */
    private static final double SHALLOW_DEPTH_BLOCKS = 9.0;

    private static float hermite(float t) {
        float c = t < 0f ? 0f : (t > 1f ? 1f : t);
        return c * c * (3f - 2f * c);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
