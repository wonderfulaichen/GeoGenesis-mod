package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * <b>内部类·海岸地形目录</b>。
 *
 * <h3>★ 为什么海岸是"内部类"而不是"陆海混合算子"</h3>
 * <p>用户 2026-11-01 纠正：</p>
 * <blockquote>
 * "过渡类型不应该是海岸地形+海洋地形（这个才有海有陆）。海岸地形还在陆地上，
 * 只是位置会生成在内部陆地与海洋之间。"
 * </blockquote>
 * <p>所以海岸地形是<b>一种有独立形态的完整地形</b>（沙滩、崖岸、礁滩…），
 * 它本身位于<b>陆地上</b>（高度高于海平面），只是在<b>空间位置</b>上
 * 出现在"内部陆地"与"内部海洋"之间。</p>
 * <p>拼图序列是：</p>
 * <pre>
 *   内部陆地 →【过渡】→ 海岸地形 →【过渡】→ 内部海洋
 * </pre>
 *
 * <h3>海岸配方的契约</h3>
 * <ul>
 *   <li><b>主体在陆地之上</b>，只有最外缘（前滨/潮滩/礁坑）允许略低于海平面</li>
 *   <li>每个配方有<b>独立的形态特征</b>，不是"高度不同的斜坡"</li>
 *   <li>形态由 {@link OceanMorphConcepts} 的 C1/C2/C3 驱动：
 *       cliffFactor（沙岸↔崖岸）、宽度因子、端点不变式</li>
 * </ul>
 *
 * <h3>高度区间（2026-11-01 第二次修正：抬到实质陆地高度）</h3>
 * <pre>
 *   id  名称             e 区间              ≈ 格             形态
 *   0   SANDY_BEACH     [-0.012, +0.070]   −3 .. +17       前滨→滩面→滩肩→海岸平原
 *   1   SEA_CLIFF       [+0.020, +0.300]   +5 .. +71       海蚀平台→崖面→崖顶台地
 *   2   REEF_FLAT       [-0.030, +0.080]   −7 .. +19       礁盘→礁脊→礁后洼地
 *   3   TIDAL_FLAT      [-0.025, +0.035]   −6 .. +8        潮滩→盐沼低台
 *   4   DELTA           [-0.015, +0.060]   −4 .. +14       河口→分流沙坝→三角洲平原
 * </pre>
 * <p>对照 {@code PLAIN} 陆地档 hi ≈ +7 格：沙滩/三角洲的滩肩与平原衔接自然，
 * 崖岸的崖顶（+71 格）明确高于平原 —— 这就是"高山海岸"的真实形态。</p>
 *
 * <h3>★ 三个必须记住的实现约束（都踩过坑）</h3>
 * <ol>
 *   <li><b>剖面位置 t 必须做对比度拉伸</b>（{@link #profileT}）：
 *       {@code Perlin} 输出集中在 0.5 附近，直接当 t 用会永远取不到分段的两端
 *       ⇒ 陆上段全部卡在分段边界，读作"贴着海平面的直线"。</li>
 *   <li><b>剖面驱动噪声必须是低频</b>（{@link #PROFILE_SPAN}）：
 *       波长 100 块会让 t 在 7000 块窗口里穿越剖面 70 次
 *       ⇒ 剖面上是密集尖峰（像针叶林），不是完整海岸剖面。</li>
 *   <li><b>不要用 {@code OceanMorphConcepts.shoreProfile}</b>：它返回的是
 *       {@code landBlocks · shape(t)} —— <b>已乘过目标高度</b>且减了沿岸下沉。
 *       要纯 0..1 形状请用 {@code shoreShape}。</li>
 * </ol>
 */
final class CoastRecipeCatalog {

    /** 海岸配方数。 */
    static final int ENTRY_COUNT = 5;

    /** 票槽数。 */
    static final int SLOT_COUNT = 10;

    /**
     * 海岸池的 seed 偏移。
     *
     * <p>与陆地（0/11/22/…）和海洋（900000）都不同，保证三方抽签互不干扰。</p>
     */
    static final int SEED_OFFSET = 1_800_000;

    /** 格/e 换算：海岸带用陆侧 236 格/e（海岸在陆地上）。 */
    static final double BLOCKS_PER_E = 236.0;

    /**
     * 海岸剖面的<b>横向</b>尺度（块）：从这个位置向内陆走完一整条剖面。
     *
     * <p>取 {@code 2200}：在 7005 块宽的 1401² 窗口里约穿越 3 次 ——
     * 图上能看到 2~3 条完整的海岸剖面，而不是 70 条碎片。</p>
     */
    static final float PROFILE_SPAN = 2200f;

    /** 剖面位置 t 的对比度拉伸窗口（实测 Perlin 2–3 oct 的 P10≈0.35 / P90≈0.65）。 */
    static final float T_LO = 0.30f;
    static final float T_HI = 0.70f;

    /** 海岸配方标识。 */
    enum Recipe {
        /** 缓沙滩：前滨→滩面→滩肩→海岸平原。 */
        SANDY_BEACH(0),
        /** 崖岸：海蚀平台→崖面→崖顶台地。 */
        SEA_CLIFF(1),
        /** 礁石平台：礁盘→礁脊→礁后洼地。 */
        REEF_FLAT(2),
        /** 潮滩：前潮滩→盐沼低台 + 潮沟。 */
        TIDAL_FLAT(3),
        /** 三角洲：河口→分流沙坝→三角洲平原。 */
        DELTA(4);

        final int id;

        Recipe(int id) { this.id = id; }

        static Recipe of(int id) {
            for (Recipe r : values()) {
                if (r.id == id) return r;
            }
            return SANDY_BEACH;
        }
    }

    // ============================================================ 状态

    private final long seed;
    private final DoubleBinaryOperator[] fns;

    CoastRecipeCatalog(long seed) {
        this.seed = seed;
        this.fns = new DoubleBinaryOperator[ENTRY_COUNT];
        int b = (int) (seed ^ (seed >>> 32));
        fns[Recipe.SANDY_BEACH.id] = buildSandyBeach(b);
        fns[Recipe.SEA_CLIFF.id] = buildSeaCliff(b);
        fns[Recipe.REEF_FLAT.id] = buildReefFlat(b);
        fns[Recipe.TIDAL_FLAT.id] = buildTidalFlat(b);
        fns[Recipe.DELTA.id] = buildDelta(b);
    }

    long seed() { return seed; }

    /** 求值：{@code height01}，主体为正（海岸在陆地上），最外缘允许略负。 */
    double height01(Recipe r, double x, double z) {
        return fns[r.id].applyAsDouble(x, z);
    }

    double height01(int id, double x, double z) {
        return height01(Recipe.of(id), x, z);
    }

    /**
     * <b>外部给剖面位置</b>的重载：用于"实际沿海组装"。
     *
     * <h3>★ 为什么需要这个重载（2026-11-01 用户指出的根本缺陷）</h3>
     * <p>用户原话：</p>
     * <blockquote>
     * "你现在是按自己噪声做的海岸，但实际拼成世界地形，你这个海岸地形的海洋部分
     * 可能不会面朝海洋。"
     * </blockquote>
     * <p>默认重载用<b>自身 Perlin 噪声</b>当剖面位置 {@code t} ⇒ 剖面方向是噪声梯度方向，
     * 与真实岸线无关。单独看那 9 张图成立，拼进世界就会出现"崖壁朝内陆"。</p>
     * <p>本重载让调用方传入由<b>真实离海距离</b>算出的 {@code t}
     * （见 {@link CoastalAssembler}）：</p>
     * <pre>
     *   t = 1 − d/BAND     d = 到海的有符号距离
     *   t=1 → 内陆侧，t=0 → 水边
     * </pre>
     *
     * <h3>哪些配方能用外部 t</h3>
     * <p>只有<b>剖面形状由 t 定义</b>的配方有意义（{@code SANDY_BEACH}、
     * {@code SEA_CLIFF}）。{@code REEF_FLAT}/{@code TIDAL_FLAT}/{@code DELTA}
     * 的形态由自身噪声场定义（礁脊走向、潮沟、分叉水道），
     * 外部 t 只能控制它们的大致高低，所以本实现仍叠加它们的自身噪声。</p>
     */
    /**
     * <b>剖面形状</b>（纯 0..1，与高度无关）。
     *
     * <h3>★ 形状 ≠ 高度：把两者分开是关键（2026-11-01 第三次重构）</h3>
     * <p>真实海岸剖面 = <b>「从海平面升到背后陆地高度」的过程</b>。
     * 形状（缓/陡、分几段）是海岸类型的特征；高度由背后的陆地决定。</p>
     * <p>所以本方法只返回 {@code 0..1}，实际高度由 {@link #evHeight} 乘 {@code eLand}。</p>
     *
     * <pre>
     *   SANDY_BEACH : 前滨                        t&lt;0.15 : 0    → 0.17   （水下坡最陡）
     *                 滩面                       0.15~0.45: 0.17 → 0.55   （缓升）
     *                 滩肩                       0.45~0.60: 0.55 → 0.78   （隆起沙脊）
     *                 海岸平原                   t&gt;0.60   : 0.78 → 1.00   （缓升到内陆）
     *   SEA_CLIFF   : 海蚀平台                   t&lt;0.35   : 0.07 → 0.22   （缓升岩台）
     *                 崖面                       0.35~0.55: 0.22 → 0.88   （陡升）
     *                 崖顶台地                   t&gt;0.55   : 0.88 → 1.00   （平缓）
     *   其余三型     : 区间内平滑 S 曲线（它们的特征形态在横向，不在剖面）
     * </pre>
     */
    private static double profileShape(Recipe r, double t) {
        double c = RefNoise.clamp((float) t, 0f, 1f);
        return switch (r) {
            case SANDY_BEACH -> {
                if (c < 0.15) {
                    yield 0.17 * (c / 0.15);
                } else if (c < 0.45) {
                    double u = (c - 0.15) / 0.30;
                    yield 0.17 + 0.38 * (u * u * (3 - 2 * u));
                } else if (c < 0.60) {
                    double u = (c - 0.45) / 0.15;
                    yield 0.55 + 0.23 * (u * u * (3 - 2 * u));
                }
                yield 0.78 + 0.22 * ((c - 0.60) / 0.40);
            }
            case SEA_CLIFF -> {
                if (c < 0.35) {
                    double u = c / 0.35;
                    yield 0.07 + 0.15 * (u * u * (3 - 2 * u));
                } else if (c < 0.55) {
                    double u = (c - 0.35) / 0.20;
                    yield 0.22 + 0.66 * Math.pow(u, 0.45);
                }
                yield 0.88 + 0.12 * ((c - 0.55) / 0.45);
            }
            // 礁盘/潮滩/三角洲：平滑 S 曲线（无分段特征，横向细节在别处）
            default -> {
                double s = c * c * (3 - 2 * c);
                yield s;
            }
        };
    }

    /**
     * 由 {@code eLand} 算出该类型的实际海岸高度（e）。
     *
     * <h3>★ 为什么必须乘 {@code eLand}（而不是用绝对数值）</h3>
     * <p>首版剖面用<b>绝对</b>高度（崖岸 +0.02..+0.30 e = 5..71 格）。
     * 实测矛盾：这片陆地的 {@code eLand} 只有 ≈0.05（12 格），
     * 而崖岸剖面在整个带内都比它高 ⇒ 整片被抬成陆地（实测水占 <b>0%</b>）；
     * 沙滩剖面又比它低 ⇒ 变成全海（实测水占 56%）。</p>
     * <p>真实地理是<b>「从海平面升到背后陆地的高度」</b>：
     * 崖岸背后是高山则崖顶高（崖面落差大）、背后是平原则崖顶低（只是道小坎）。
     * 所以剖面必须<b>锚定 {@code eLand}</b>，而不是给固定格数。</p>
     *
     * <h3>★ 沙滩要封顶（AGENTS.md 的既有教训）</h3>
     * <p>滩肩高度由<b>潮差与浪蚀基准面</b>决定，现实只有 2–5 m，
     * <b>与内陆山有多高完全无关</b>。所以沙滩的锚定值取
     * {@code min(eLand, BEACH_TOP)}，封顶 {@value #BEACH_TOP_E} e（≈+17 格）。
     * 崖岸没有这个约束 —— 崖顶就该跟着背后的山走。</p>
     *
     * @param eLand 该点的陆地高度（{@code CombineCandidate.e}）。
     *              传 {@code NaN} 时用 {@link #REF_LAND_E} 作参考（用于单点剖面分析）
     */
    private double evHeight(Recipe r, double t, double eLand) {
        double shape = profileShape(r, t);
        double anchor = Double.isNaN(eLand) ? REF_LAND_E : Math.max(0.0, eLand);
        // 沙滩封顶：滩肩与内陆山高无关
        if (r == Recipe.SANDY_BEACH) {
            anchor = Math.min(anchor, BEACH_TOP_E);
        }
        // 水边（t=0）不严格为 0：各类型有自己的"水边高程"
        double shoreE = waterEdgeE(r);
        return shoreE + (anchor - shoreE) * shape;
    }

    /** 沙滩滩肩的封顶高度（e）。+0.070 e ≈ +17 格 —— 与现实滩肩 2~5 m 同量级（偏宽以利于游戏性）。 */
    static final double BEACH_TOP_E = 0.070;

    /** 单点剖面分析时的参考陆地高度（e）。0.30 ≈ 71 格，接近丘陵档。 */
    static final double REF_LAND_E = 0.30;

    /**
     * 该类型的<b>水边高程</b>（{@code t=0} 处的 e）。
     *
     * <p>必须在海平面附近（±2 格），否则岸线处会有高度断差：</p>
     * <pre>
     *   SANDY_BEACH : −0.012  （前滨略低于海平面，−3 格）
     *   SEA_CLIFF   : +0.020  （海蚀平台高出海面 0.5~5 m，+5 格）
     *   REEF_FLAT   : −0.012  （礁盘低潮露出）
     *   TIDAL_FLAT  : −0.025  （前潮滩，−6 格）
     *   DELTA       : −0.015  （河口，−4 格）
     * </pre>
     */
    static double waterEdgeE(Recipe r) {
        return switch (r) {
            case SANDY_BEACH -> -0.012;
            case SEA_CLIFF -> 0.020;
            case REEF_FLAT -> -0.012;
            case TIDAL_FLAT -> -0.025;
            case DELTA -> -0.015;
        };
    }

    /**
     * <b>外部 t 版</b>：{@code t} 只控制剖面位置，剖面高度锚定到<b>真实陆地高度</b>。
     *
     * <h3>★ 三次重构的教训（都是同一个错误的变体）</h3>
     * <p><b>v1</b>：{@code sandyBeachAt(x,z,t)} 里 {@code x/z} 没用 ⇒ 等 t 线高度恒定
     * ⇒ 形态退化成同心等高线。</p>
     * <p><b>v2</b>：加自带 Perlin 做横向调制 ⇒ <b>噪声梯度方向与岸线无关</b>，
     * 重现"海岸朝向错误"（用户当场指出）。</p>
     * <p><b>v3</b>（当前）：横向变化<b>不引入任何新噪声</b>，而是让剖面<b>锚定 {@code eLand}</b>
     * ⇒ 横向差异自然来自真实陆地地形的空间变化（山地背后崖顶高、平原背后低），
     * 且方向性完全由 {@code d} 场给定，不含任何方向假设。</p>
     *
     * @param eLand 该点的陆地高度。传 {@code NaN} 时用 {@link #REF_LAND_E} 作参考
     */
    double height01(Recipe r, double x, double z, double externalT, double eLand) {
        return evHeight(r, RefNoise.clamp((float) externalT, 0f, 1f), eLand);
    }

    /** 票槽权重。 */
    static float weight(Recipe r) {
        return switch (r) {
            // 沙滩是最常见的海岸类型
            case SANDY_BEACH -> 4.0f;
            // 崖岸次之
            case SEA_CLIFF -> 3.0f;
            // 潮滩、礁滩各占一部分
            case TIDAL_FLAT -> 2.0f;
            case REEF_FLAT -> 2.0f;
            // 三角洲最局部
            case DELTA -> 1.0f;
        };
    }

    /** 设计高度区间（e）。 */
    static double[] heightRange(Recipe r) {
        return switch (r) {
            // 前滨 −3 格 → 海岸平原 +17 格
            case SANDY_BEACH -> new double[]{-0.012, 0.070};
            // 海蚀平台 +5 格 → 崖顶台地 +71 格（最高，对应高山海岸）
            case SEA_CLIFF -> new double[]{0.020, 0.300};
            // 礁盘 −1.5 格 → 礁脊/礁后 +20 格
            case REEF_FLAT -> new double[]{-0.012, 0.085};
            // 潮滩 −6 格 → 盐沼低台 +8 格（最平）
            case TIDAL_FLAT -> new double[]{-0.025, 0.035};
            // 河口 −4 格 → 三角洲平原 +14 格
            case DELTA -> new double[]{-0.015, 0.060};
        };
    }

    // ============================================================ 剖面位置

    /**
     * 把原始噪声映射成铺满 {@code [0,1]} 的剖面位置 {@code t}。
     *
     * <p>为什么必须拉伸：{@code RefNoise.Perlin} 的输出集中在 0.5 附近
     * （P10≈0.35 / P90≈0.65），直接当 t 用会永远取不到分段的两端，
     * 陆上部分全部卡在分段边界上，读作"贴着海平面的一条直线"。</p>
     * <p>用 {@code clamp} + 线性归一化两步（与过渡层的 {@code Clamped+Mapped} 等价）。</p>
     */
    private static double profileT(RefNoise.Node noise, double x, double z) {
        float raw = noise.compute((float) x, (float) z);
        float c = RefNoise.clamp(raw, T_LO, T_HI);
        return (c - T_LO) / (T_HI - T_LO);
    }

    // ============================================================ 各配方实现
    //
    // 全部是纯 (x,z) 函数。剖面驱动噪声一律用 PROFILE_SPAN（低频），
    // 高频噪声只用于表面细节，绝不用来驱动剖面位置 t。

    /**
     * 沙质海滩：前滨 → 滩面 → 滩肩 → 海岸平原。
     *
     * <p>C1 取 {@code cliff≈0}（沙岸）。四段剖面按真实沙滩分带：</p>
     * <pre>
     *   前滨   t &lt; 0.15 : −0.012 → 0        水下坡（最陡）
     *   滩面   0.15~0.45 : 0 → +0.022       缓升（湿沙带 + 干沙带）
     *   滩肩   0.45~0.60 : +0.022 → +0.038  隆起的沙脊
     *   平原   t &gt; 0.60 : +0.038 → +0.070  缓慢抬升
     * </pre>
     */
    private static DoubleBinaryOperator buildSandyBeach(int b) {
        // C1：cliffFactor 噪声（取低位 = 沙岸）
        RefNoise.Node cliffN = RefNoise.Perlin.ofScale(b + 1201, 900f, 3);
        // 滩肩横向起伏
        RefNoise.Node bermN = RefNoise.Perlin.ofScale(b + 1202, 600f, 2);
        // 剖面驱动（低频，见 PROFILE_SPAN）
        RefNoise.Node profile = RefNoise.Perlin.ofScale(b + 1203, PROFILE_SPAN, 2);
        return (x, z) -> {
            // cliff 强制偏低（这是沙滩配方，不是崖岸）
            double cliff = 0.25 * OceanMorphConcepts.cliffFactor(
                    cliffN.compute((float) x, (float) z) * 2.0 - 1.0);
            double t = profileT(profile, x, z);
            // cliff 影响滩面的陡缓：cliff 大时滩面更陡（但仍不是崖）
            double shape = OceanMorphConcepts.shoreShape(t, cliff);

            double h;
            if (t < 0.15) {
                double u = t / 0.15;
                h = -0.012 + 0.012 * u;
            } else if (t < 0.45) {
                double u = (t - 0.15) / 0.30;
                h = 0.022 * (u * u * (3 - 2 * u));
            } else if (t < 0.60) {
                double u = (t - 0.45) / 0.15;
                h = 0.022 + 0.016 * (u * u * (3 - 2 * u));
            } else {
                double u = (t - 0.60) / 0.40;
                h = 0.038 + 0.032 * u;
            }
            // 用 shape 轻微调制滩肩位置（让沙脊不是完全等距）
            h += 0.004 * (shape - t);

            // 滩肩处的横向起伏（沙脊不是完全直的）
            double berm = bermN.compute((float) x, (float) z) * 0.006
                    * Math.exp(-Math.pow((t - 0.52) / 0.12, 2.0));
            return h + berm;
        };
    }

    /**
     * 崖岸：海蚀平台 → 崖面 → 崖顶台地。
     *
     * <p>C1 取 {@code cliff≈1}（崖岸）、C2 用陆高控制崖脚后退。三段剖面：</p>
     * <pre>
     *   海蚀平台 t &lt; 0.35 : +0.020 → +0.045   缓升的岩台
     *   崖面     0.35~0.55 : +0.045 → +0.230  近垂直跃升（cliff 越大越陡）
     *   崖顶台地 t &gt; 0.55 : +0.230 → +0.300  平缓
     * </pre>
     */
    private static DoubleBinaryOperator buildSeaCliff(int b) {
        RefNoise.Node cliffN = RefNoise.Perlin.ofScale(b + 1301, 800f, 3);
        RefNoise.Node landN = RefNoise.Perlin.ofScale(b + 1302, 1400f, 2);
        // 剖面驱动（低频）
        RefNoise.Node profile = RefNoise.Perlin.ofScale(b + 1303, PROFILE_SPAN, 2);
        // 崖面竖向沟槽（高频细节，不驱动剖面）
        RefNoise.Node grooveN = RefNoise.Perlin.ofScale(b + 1304, 90f, 2);
        return (x, z) -> {
            // C1：cliff 取高位（崖岸）
            double cliff = 0.75 + 0.25 * OceanMorphConcepts.cliffFactor(
                    cliffN.compute((float) x, (float) z) * 2.0 - 1.0);
            // C2：崖后陆地高度 → 宽度因子（崖脚后退）
            double landH = landN.compute((float) x, (float) z) * 120.0;   // 0..120 格
            double widthFactor = OceanMorphConcepts.shoreWidthFactor(landH);

            double t = profileT(profile, x, z);
            // 宽度因子把 t 压向低端（崖面后移）
            double tShift = Math.pow(t, widthFactor);

            double h;
            if (tShift < 0.35) {
                double u = tShift / 0.35;
                h = 0.020 + 0.025 * (u * u * (3 - 2 * u));
            } else if (tShift < 0.55) {
                double u = (tShift - 0.35) / 0.20;
                // cliff 越大，崖面越陡（指数越小 ⇒ 越早跃起）
                double shaped = Math.pow(u, 1.0 - 0.55 * cliff);
                h = 0.045 + 0.185 * shaped;
            } else {
                double u = (tShift - 0.55) / 0.45;
                h = 0.230 + 0.070 * u;
            }
            // 崖面竖向沟槽
            double groove = grooveN.compute((float) x, (float) z) * 0.008
                    * Math.exp(-Math.pow((tShift - 0.45) / 0.08, 2.0));
            return h + groove;
        };
    }

    /**
     * 礁石平台：礁盘 → 礁脊 → 礁后洼地。
     *
     * <p><b>这是唯一允许主体在水下的海岸配方</b>：礁盘本质是潮间带，
     * 低潮露出、高潮淹没。但按用户 2026-11-01 的看图反馈
     * "礁盘那两条水太多、陆地被压得太薄"，本版<b>把陆上部分加厚</b>：</p>
     * <ul>
     *   <li>礁盘基准从 −0.030 抬到 <b>−0.012</b>（只露出水面以下约 1.5 格，
     *       而不是 7 格）；</li>
     *   <li>礁脊顶从 +0.055 抬到 <b>+0.085</b>（礁脊明显露出）；</li>
     *   <li>礁后沙洲从 +0.050 抬到 <b>+0.070</b>。</li>
     * </ul>
     * <p>目标是让礁盘的水占从 81% 降到接近其它海岸（~55–60%），
     * 同时保留"潮间带"的语义（仍有部分被淹没）。</p>
     */
    private static DoubleBinaryOperator buildReefFlat(int b) {
        // 礁脊横向尺度 600 块（礁盘比沙滩小一个量级）
        RefNoise.Node reef = RefNoise.PerlinRidge.ofScale(b + 1401, 600f, 3);
        // 礁区掩码
        RefNoise.Node patchMask = RefNoise.Perlin.ofScale(b + 1402, 1800f, 2);
        // 礁后沙洲（低频）
        RefNoise.Node backFlat = RefNoise.Perlin.ofScale(b + 1403, PROFILE_SPAN, 2);
        return (x, z) -> {
            double m = patchMask.compute((float) x, (float) z);
            double gate = RefNoise.clamp((float) ((m - 0.42) / 0.22), 0f, 1f);
            // 礁脊位置（拉伸到铺满 0..1）
            double r = profileT(reef, x, z);
            // 礁盘基准 −0.012（浅水），脊顶 +0.085（明显露出）
            double base = -0.012;
            double ridgeTop = 0.085;
            double v = base + (ridgeTop - base) * gate * r;
            // 礁后沙洲：抬到 +0.070
            double back = 0.070 * gate * profileT(backFlat, x, z);
            // 潮池：脊之间的低洼（只在礁盘内，且更浅）
            double pool = -0.010 * (1.0 - r) * gate;
            return v + back + pool;
        };
    }

    /**
     * 潮滩：前潮滩 → 盐沼低台 + 潮沟。
     *
     * <p>真实潮滩坡度常 &lt; 1:1000，几乎水平。
     * 最外缘（前潮滩）−0.025，内侧（盐沼）+0.035。</p>
     */
    private static DoubleBinaryOperator buildTidalFlat(int b) {
        // 剖面驱动（低频）
        RefNoise.Node flat = RefNoise.Perlin.ofScale(b + 1501, PROFILE_SPAN, 2);
        // 潮沟（较窄，但不驱动剖面）
        RefNoise.Node creek = RefNoise.PerlinRidge.ofScale(b + 1502, 300f, 2);
        // 盐沼掩码
        RefNoise.Node marsh = RefNoise.Perlin.ofScale(b + 1503, 1800f, 2);
        return (x, z) -> {
            // 前潮滩 → 盐沼：−0.010 → +0.010 的极缓倾斜
            double n = profileT(flat, x, z) * 2.0 - 1.0;   // [-1,1]
            double base = -0.0025 + 0.0125 * n;
            // 盐沼低台：内侧抬到 +0.035
            double m = marsh.compute((float) x, (float) z);
            double marshLift = 0.035 * RefNoise.clamp((float) ((m - 0.40) / 0.30), 0f, 1f);
            // 潮沟：细窄的负向刻痕
            double c = creek.compute((float) x, (float) z);
            double creekCut = -0.022 * Math.max(0.0, 1.0 - Math.abs(c - 0.5) / 0.12);
            return base + marshLift + creekCut;
        };
    }

    /**
     * 三角洲：河口 → 分流沙坝 → 三角洲平原。
     *
     * <p>形态：整体缓扇形（{@code f²} 使近河口处最平、向陆快速抬升），
     * 上面刻几条分叉水道。河口 −0.015 → 三角洲平原 +0.060。</p>
     */
    private static DoubleBinaryOperator buildDelta(int b) {
        // 剖面驱动（低频）
        RefNoise.Node fan = RefNoise.Perlin.ofScale(b + 1601, PROFILE_SPAN, 2);
        // 分叉水道
        RefNoise.Node channels = RefNoise.PerlinRidge.ofScale(b + 1602, 250f, 2);
        // 三角洲平原内侧缓升
        RefNoise.Node plain = RefNoise.Perlin.ofScale(b + 1603, 1800f, 2);
        return (x, z) -> {
            double f = profileT(fan, x, z);   // [0,1]，已铺满
            // 河口低平（−0.015）→ 三角洲平原（+0.060）
            double base = -0.015 + 0.075 * f * f;
            // 平原内侧缓升
            double p = plain.compute((float) x, (float) z);
            double plainLift = 0.012 * RefNoise.clamp((float) ((p - 0.45) / 0.30), 0f, 1f);
            // 分叉水道：的谷底
            double c = channels.compute((float) x, (float) z);
            double inChannel = Math.max(0.0, 1.0 - Math.abs(c - 0.5) / 0.18);
            double cut = -0.018 * inChannel * f;
            return base + plainLift + cut;
        };
    }

    // ============================================================ 诊断

    String summary() {
        StringBuilder sb = new StringBuilder("CoastRecipeCatalog seed=" + seed
                + " entries=" + ENTRY_COUNT + " slots=" + SLOT_COUNT + "\n");
        for (Recipe r : Recipe.values()) {
            double[] d = heightRange(r);
            sb.append(String.format(Locale.ROOT,
                    "  [%d] %-14s weight=%.1f  e=[%+.3f, %+.3f]  (blocks %+.1f..%+.1f)%n",
                    r.id, r, weight(r), d[0], d[1],
                    d[0] * BLOCKS_PER_E, d[1] * BLOCKS_PER_E));
        }
        return sb.toString();
    }

    private CoastRecipeCatalog() { throw new AssertionError(); }
}
