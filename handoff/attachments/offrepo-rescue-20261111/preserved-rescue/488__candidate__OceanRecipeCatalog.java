package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;
import java.util.function.DoubleBinaryOperator;

/**
 * <b>海洋配方目录</b>（方案 {@code ocean-unified-terrain} 步 1）。
 *
 * <p>与 {@link RecipeMorphCatalog}（陆地 18 条目）<b>并列</b>的独立池：
 * 6 个海洋配方，每个都是纯 {@code (x,z) → height01}，供票槽池统一调度。</p>
 *
 * <h3>★ 核心契约：height01 ∈ [-1, 0]</h3>
 * <p>陆地配方的 {@code height01} 恒 ≥ 0（相对共享地面基准抬升），
 * 海洋配方反过来：<b>0 = 海平面，-1 = 世界底</b>，与
 * {@link com.geogenesis.worldgen.terrain.HeightCurve#heightFromE} 的海侧映射一致
 * （{@code e ∈ [-1,0] → [minY, seaLevel]}）。</p>
 *
 * <h3>★ 量纲：必须按海侧 127 格/e 标定，不得沿用陆地 E_SPAN</h3>
 * <p>实测（{@code OceanAssemblyCandidate} 的常量）：</p>
 * <pre>
 *   BLOCKS_PER_E_LAND = 236    陆侧
 *   BLOCKS_PER_E_SEA  = 127    海侧
 * </pre>
 * <p>差 1.86 倍。若海洋配方沿用陆地 {@code E_SPAN=1.50}，同样的 1 单位 e
 * 会变成 236 格而非 127 格 ⇒ <b>海会深 1.86 倍</b>。</p>
 * <p>本文所有"格"的标定一律按 127 格/e 换算。</p>
 *
 * <h3>为什么不复用 {@code OceanFeatures}</h3>
 * <p>{@code OceanFeatures} 的契约是 {@code → delta ≥ 0}（只能抬升海床），
 * 且作用在 {@code eOcean} 上做加法。本目录要的是<b>可替换的完整形态</b>，
 * 允许负值（{@link #CANYON} 就靠负值向下切）。两者语义不同，不互相替代：
 * 移植时取其"位置门控"思路，不取其"只加不减"的约束。</p>
 *
 * <p>只读：不引用 {@code OceanAssemblyCandidate}、不改任何生产对象、不加 {@code -D} 开关。</p>
 */
final class OceanRecipeCatalog {

    // ============================================================ 常量

    /** 内部类·海洋配方数（5）。过渡（陆坡等）由 {@link OceanTransitionCatalog} 复合生成。 */
    static final int ENTRY_COUNT = 5;

    /** 票槽数（按权重展开后的槽位数）。 */
    static final int SLOT_COUNT = 12;

    /**
     * 票槽池的 seed 偏移。
     *
     * <p><b>★ 必须与陆地池完全隔离</b>：陆地的 {@code CombineCandidate} 用一个
     * {@code IntSupplier} 游标顺序取随机数（selector / shuffle / 各族 seed）。
     * 海洋池若共用同一游标，一旦海洋条目数变化，陆地的抽签序列会整体位移
     * ⇒ 用户已认可的陆地基准（7 臂 SHA）立刻作废。</p>
     * <p>取一个大且与陆地各处偏移都不同的常数（陆地用 0/11/22/33/44/55/77 等小偏移）。</p>
     */
    static final int SEED_OFFSET = 900_000;

    /** 海侧格/e 换算：{@code e = blocks / 127}。见类注释的量纲说明。 */
    static final double BLOCKS_PER_E = 127.0;

    /** 一个配方的标识与语义（供 {@code recipe.u8} 导出与类型图上色）。 */
    enum Recipe {
        /** 深海平原：极平缓，接近常数深度。 */
        ABYSSAL_PLAIN(0),
        /** 洋中脊：沿构造离散带隆起。 */
        MID_OCEAN_RIDGE(1),
        /** 海山群：密集海底锥。 */
        SEAMOUNT_FIELD(2),
        /** 海底峡谷：<b>向下切</b>，唯一能比周围更深的配方。 */
        CANYON(3),
        /** 陆架沙脊：浅海带的起伏沙洲（内部类，位置近岸但不属过渡）。 */
        SHELF_RIDGE(4);

        /**
         * ⚠ {@code CONTINENTAL_SLOPE} 已移出内部类。
         *
         * <p>它是<b>海↔海过渡</b>（陆架 ↔ 深海平原之间的斜坡），
         * 已由 {@link OceanTransitionCatalog} 的复合族自动生成
         * （{@code ABYSSAL_PLAIN × SHELF_RIDGE}）。
         * 保留下面的注释只为让旧 id 4 的语义可追溯。</p>
         */

        final int id;

        Recipe(int id) { this.id = id; }

        static Recipe of(int id) {
            for (Recipe r : values()) {
                if (r.id == id) return r;
            }
            return ABYSSAL_PLAIN;
        }
    }

    // ============================================================ 状态

    private final long seed;
    /** 是否使用概念移植版（步 4）。见 {@link #OceanRecipeCatalog(long, boolean)}。 */
    private final boolean conceptMorph;
    /**
     * 每个配方的形态描述；下标 = {@link Recipe#id}。
     *
     * <p>★ 每个 {@link Shape} <b>同时</b>持有两套<b>独立书写</b>的定义：
     * 原公式（{@code heightFn}）与可过渡端点（{@code rawFn/ampFn/biasFn}）。
     * 因此"用端点重建出的高度 == 原公式"是一条<b>真判据</b>，不是同源自证。</p>
     */
    private final Shape[] shapes;

    /**
     * @param seed 世界种子（调用方应传入 {@code base + SEED_OFFSET}，见 {@link #SEED_OFFSET}）
     */
    OceanRecipeCatalog(long seed) {
        this(seed, false);
    }

    /**
     * @param conceptMorph {@code true} ⇒ {@code MID_OCEAN_RIDGE} 使用
     *                     {@link OceanMorphConcepts} 的构造门控（C4）；
     *                     {@code false} ⇒ 使用步 1 的纯带状版。
     *                     <p>两者都在同一个 {@link Recipe} id 下，所以票槽展开、权重、
     *                     深度区间完全一致 —— <b>这是单变量对照</b>，
     *                     差异只能来自"有没有移植概念"。</p>
     */
    OceanRecipeCatalog(long seed, boolean conceptMorph) {
        this.seed = seed;
        this.conceptMorph = conceptMorph;
        this.shapes = new Shape[ENTRY_COUNT];
        int b = (int) (seed ^ (seed >>> 32));
        shapes[Recipe.ABYSSAL_PLAIN.id] = buildAbyssalPlain(b);
        shapes[Recipe.SEAMOUNT_FIELD.id] = buildSeamountField(b);
        shapes[Recipe.CANYON.id] = buildCanyon(b);
        shapes[Recipe.SHELF_RIDGE.id] = buildShelfRidge(b);
        shapes[Recipe.MID_OCEAN_RIDGE.id] = conceptMorph
                ? buildMidOceanRidgeConcept(b, -0.36, -0.20)
                : buildMidOceanRidge(b);
    }

    long seed() { return seed; }

    /** 是否使用概念移植版（步 4）。 */
    boolean conceptMorph() { return conceptMorph; }

    /**
     * 求值：{@code height01 ∈ [-1, 0]}。
     *
     * <p>0 = 海平面，-1 = 世界底。越负越深。</p>
     */
    double height01(Recipe r, double x, double z) {
        return shapes[r.id].height(x, z);
    }

    /** 按 id 求值（票槽池用）。 */
    double height01(int id, double x, double z) {
        return height01(Recipe.of(id), x, z);
    }

    /**
     * <b>真实节点描述</b>（供统一内核 {@code MorphRecipe} 消费）：第 {@code id} 个海洋配方。
     *
     * <h3>★ 为什么必须新增这一层</h3>
     * <p>{@link MorphRecipe} 的契约是「{@code raw/amplitude/bias} 三件可分别过渡的部件」，
     * 而本目录的 {@link #height01} 只给<b>成品高度</b>。若内核拿成品高度当 raw
     * （或反推 {@code (height−bias)/amp}），过渡会把形态抹平、端点处又与原配方不等
     * —— 这正是 {@link RecipeMorphCatalog} 类注释里记录的失败模式。</p>
     *
     * <h3>★ 独立性：raw/amp/bias 不是从成品高度反解出来的</h3>
     * <p>每个 {@link Shape} 里<b>原公式</b>（{@code heightFn}）与<b>三件端点</b>
     * （{@code rawFn/ampFn/biasFn}）是<b>两套各自写出的表达式</b>，只共享底层噪声节点
     * （同一棵树，避免重复建构/复制全公式）。因此
     * "用端点重建出的高度 == 原公式"是一条<b>真判据</b>，不是同源自证：
     * 端点确实独立描述了形态，且恒等式在 double 代数下成立（浮点容差见 {@code OceanRecipeCheck}）。</p>
     *
     * <p>坐标口径：{@code x/z} 保持 {@code double} 入参（海山的格点 hash 需要 double 精度），
     * 只有喂给噪声节点时才收窄成 {@code float}（与原公式一致，不改变产出）。</p>
     *
     * @param id 配方 id（0..{@link #ENTRY_COUNT}-1）；越界按 {@link Recipe#of} 回落，与
     *           {@link #height01(int, double, double)} 同口径
     */
    MorphRecipe morphEntry(int id) {
        return shapes[Recipe.of(id).id];
    }

    /** 该配方在票槽池里的权重（决定被抽中的概率）。 */
    static float weight(Recipe r) {
        return switch (r) {
            // 深海平原是主体地表，权重最大
            case ABYSSAL_PLAIN -> 5.0f;
            // 陆架沙脊紧贴海岸，出现频率次高
            case SHELF_RIDGE -> 3.0f;
            // 洋中脊是线性特征，占地小但显眼
            case MID_OCEAN_RIDGE -> 2.0f;
            case SEAMOUNT_FIELD -> 2.0f;
            // 峡谷是局部特征，最稀
            case CANYON -> 1.0f;
        };
    }

    /** 该配方的设计深度区间（e 单位，负值；{@code [lo, hi]} 且 lo &lt; hi &lt;= 0）。 */
    static double[] depthRange(Recipe r) {
        return switch (r) {
            // 深海平原：3000~5000 m 量级 → e ≈ -0.30 ~ -0.42（按 127 格/e 折算）
            case ABYSSAL_PLAIN -> new double[]{-0.42, -0.30};
            // 洋中脊轴部抬升到约 -0.20（相对深海平原 +0.18 e，与既有 RIDGE_AMPLITUDE 同量级）
            case MID_OCEAN_RIDGE -> new double[]{-0.42, -0.20};
            // 海山：从平原抬到接近陆架深度
            case SEAMOUNT_FIELD -> new double[]{-0.42, -0.10};
            // 峡谷：比周围更深（这是唯一"向下超基准"的配方）
            case CANYON -> new double[]{-0.55, -0.30};
            // 陆架：浅海带 0 ~ -0.10
            case SHELF_RIDGE -> new double[]{-0.10, 0.0};
        };
    }

    // ============================================================ 各配方实现
    //
    // 全部是纯 (x,z) 函数，无整图统计、无跨块状态。
    // 形态定义一律落在 depthRange 之内（OceanRecipeCheck 逐配方验证）。

    /**
     * 深海平原：极低频噪声给出±0.03 e 的缓起伏，其余是常数深度。
     *
     * <p>参考项目的 {@code makeShallowOcean} 就是纯常量；真实的深海平原也确实平
     * （坡度常 &lt; 1:1000）。这里留一点低频起伏只为了不出现"绝对平面"的塑料感。</p>
     */
    private static Shape buildAbyssalPlain(int b) {
        // 波长 3000 块（大陆尺度）、2 octave
        RefNoise.Node relief = RefNoise.Perlin.ofScale(b + 101, 3000f, 2);
        DoubleBinaryOperator height = (x, z) -> {
            double base = -0.36;                                  // 平原基准深度
            double amp = 0.03;                                    // ±0.03 e ≈ ±3.8 格
            double n = (relief.compute((float) x, (float) z) * 2.0 - 1.0);
            return base + amp * n;
        };
        // 端点：raw = 原始起伏 2·noise−1（<b>不是</b>成品高度 −0.36 那层）；amp 恒 0.03；bias 恒 −0.36
        DoubleBinaryOperator raw = (x, z) -> relief.compute((float) x, (float) z) * 2.0 - 1.0;
        DoubleBinaryOperator amp = (x, z) -> 0.03;
        DoubleBinaryOperator bias = (x, z) -> -0.36;
        return new Shape(height, raw, amp, bias, false);
    }

    /**
     * 洋中脊：沿一条蜿蜒轴线隆起，轴线外平滑衰减。
     *
     * <h3>为什么不直接用构造场</h3>
     * <p>生产侧的 {@code OceanFeatures.ridgeGate} 用 {@code TectonicField} 的
     * {@code stress} + {@code dist} 定位。但那个类在 {@code src/main}，沙箱引用它会
     * 把新架构绑回生产。本配方改用<b>自身的低频脊线噪声</b>定位：
     * 取 {@code |n|} 接近 0 的地方为轴线（与 {@code ShallowSeaIslandCandidate} 用
     * {@code n²} 造环带相反，这里要的是<b>线</b>而非环）。</p>
     *
     * <p>移植概念（不移植代码）：脊是"带状、沿轴起伏、轴外衰减"——
     * 这三条语义与 {@code ridgeGate} 一致。</p>
     */
    private static Shape buildMidOceanRidge(int b) {
        // 脊线场：波长 2400 块，域扭曲让它蜿蜒
        RefNoise.Node axis = RefNoise.warpPerlin(
                RefNoise.Perlin.ofScale(b + 201, 2400f, 2), b + 202, 600f, 2, 300f);
        // 轴部细节
        RefNoise.Node detail = RefNoise.Perlin.ofScale(b + 203, 400f, 2);
        // 门控：高斯带，半宽 ~0.25（约 600 块），数学上永不硬切（原式与端点<b>共用</b>这个真实门控）
        DoubleBinaryOperator band = (x, z) -> {
            // [-1,1] → 距轴距离的近似；|axis| 小 = 在轴上
            double a = Math.abs(axis.compute((float) x, (float) z) * 2.0 - 1.0);
            double g = Math.exp(-(a * a) / (2.0 * 0.25 * 0.25));
            return g < 1e-4 ? 0.0 : g;
        };
        DoubleBinaryOperator height = (x, z) -> {
            // 原式：平原 -0.36 抬升最多 +0.16 e（≈20 格）到 -0.20
            double d = (detail.compute((float) x, (float) z) * 2.0 - 1.0);
            return -0.36 + 0.16 * band.applyAsDouble(x, z) * (0.7 + 0.3 * d);
        };
        // 端点：raw = 轴向细节 0.7+0.3·detail；amp = 0.16·band（即 (peak−plain)·gate）；bias = −0.36
        DoubleBinaryOperator raw = (x, z) ->
                0.7 + 0.3 * (detail.compute((float) x, (float) z) * 2.0 - 1.0);
        DoubleBinaryOperator amp = (x, z) -> 0.16 * band.applyAsDouble(x, z);
        DoubleBinaryOperator bias = (x, z) -> -0.36;
        return new Shape(height, raw, amp, bias, false);
    }

    /**
     * 海山群：粗格点确定性鼓包（网格 hash + 半径 + 高斯包络）。
     *
     * <p>移植 {@code OceanFeatures.seamountCompute} 的<b>概念</b>：
     * 500 块格点、每格按 hash 决定是否生成、高斯去圆化。
     * 不改用它的代码（那是 {@code src/main} 的生产类）。</p>
     */
    private static Shape buildSeamountField(int b) {
        final double grid = 500.0;
        final int chance = (int) (0.35 * 65536);   // 比生产 0.20 密，配方本身应更"显眼"
        // 纯 relief：格点内所有鼓包的最大抬升（e），<b>不含</b>平原基准。
        // ★ 它是真实源节点（原式的 {best}）；原式与端点 raw 共用它，不要各建一棵噪声。
        DoubleBinaryOperator relief = (x, z) -> seamountSignal(b, x, z, grid, chance, false);
        DoubleBinaryOperator raw = (x, z) -> seamountSignal(b, x, z, grid, chance, true);
        DoubleBinaryOperator height = (x, z) -> -0.36 + relief.applyAsDouble(x, z);
        // 用原有幅度上界归一每个鼓包，避免把1.0外壳幅度带入其他海型。
        return new Shape(height, raw, (x, z) -> 0.05 + 0.08, (x, z) -> -0.36, false);
    }

    private static double seamountSignal(int b, double x, double z, double grid, int chance, boolean normalized) {
            long cx = (long) Math.floor(x / grid);
            long cz = (long) Math.floor(z / grid);
            double best = 0.0;                      // 最大抬升（e）
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    int h = RefNoise.hash2D(b + 301, (int) (cx + dx), (int) (cz + dz));
                    if ((h & 0xFFFF) >= chance) continue;
                    // 格内抖动位置
                    double px = (cx + dx) * grid + ((h >>> 8) & 0xFF) / 255.0 * grid;
                    double pz = (cz + dz) * grid + ((h >>> 16) & 0xFF) / 255.0 * grid;
                    double radius = 70.0 + ((h >>> 24) & 0xFF) / 255.0 * 80.0;   // 70~150
                    double ddx = x - px, ddz = z - pz;
                    double d2 = ddx * ddx + ddz * ddz;
                    double r2 = radius * radius;
                    if (d2 >= 4.0 * r2) continue;   // 4σ 外忽略
                    // 高斯鼓包：中心满值、边缘平滑到 0（无非 C¹ 断点）
                    double bump = Math.exp(-d2 / (2.0 * r2 * 0.25));
                    // 幅度 0.05~0.13 e（6~17 格）
                    double amp = 0.05 + ((h >>> 4) & 0x3F) / 63.0 * 0.08;
                    best = Math.max(best, normalized ? (amp / (0.05 + 0.08)) * bump : amp * bump);
                }
            }
            return best;
    }

    /**
     * <b>海底峡谷</b>：唯一「比周围更深」的配方 —— 负值能力的验证项。
     *
     * <h3>为什么它是整个方案的关键验证</h3>
     * <p>其余 5 个配方都可视为"在平原基准上抬升"，形式上与既有
     * {@code OceanFeatures}（只能加）同类。只有本配方要求
     * {@code height01} 显著<b>低于</b>周围基准 —— 若票槽池 / e 公式仍带着
     * 陆地那套"只能抬升"的假设，这里就会暴露。</p>
     *
     * <h3>形态</h3>
     * <p>用脊线噪声取 {@code |n|} 的<b>另一端</b>（n 接近整数边界处）作为谷轴，
     * 与 {@link #buildMidOceanRidge} 共用"带状"思路但极性相反。
     * 谷底比平原基准深 0.10~0.19 e（≈13~24 格），边缘高斯平滑收口。</p>
     */
    private static Shape buildCanyon(int b) {
        // 谷轴场：波长 1600 块（峡谷比洋中脊更密），域扭曲让走向蜿蜒
        RefNoise.Node axis = RefNoise.warpPerlin(
                RefNoise.Perlin.ofScale(b + 401, 1600f, 2), b + 402, 500f, 2, 260f);
        RefNoise.Node detail = RefNoise.Perlin.ofScale(b + 403, 300f, 2);
        // 门控：谷带高斯（半宽 0.18）
        DoubleBinaryOperator band = (x, z) -> {
            double a = Math.abs(axis.compute((float) x, (float) z) * 2.0 - 1.0);
            double g = Math.exp(-(a * a) / (2.0 * 0.18 * 0.18));
            return g < 1e-4 ? 0.0 : g;
        };
        DoubleBinaryOperator height = (x, z) -> {
            // 原式 ★ 向下切：-0.36 再减最多 0.19 e（≈24 格），落到 -0.55
            double d = (detail.compute((float) x, (float) z) * 2.0 - 1.0);
            return -0.36 - 0.19 * band.applyAsDouble(x, z) * (0.7 + 0.3 * d);
        };
        // 端点：raw = 0.7+0.3·detail；amp = −0.19·band（负号在 amp 里，故 raw 与洋中脊同式）；
        //        bias = −0.36。恒等式 raw·amp+bias == −0.36 − 0.19·band·raw。
        DoubleBinaryOperator raw = (x, z) ->
                0.7 + 0.3 * (detail.compute((float) x, (float) z) * 2.0 - 1.0);
        DoubleBinaryOperator amp = (x, z) -> -0.19 * band.applyAsDouble(x, z);
        DoubleBinaryOperator bias = (x, z) -> -0.36;
        return new Shape(height, raw, amp, bias, false);
    }

    /**
     * 大陆坡：从浅海过渡到深海平原的斜坡。
     *
     * <p>移植 {@code OceanAssemblyCandidate.coastalProfile} 中「shelf → shore」段的概念：
     * 用一条低频场当"离岸程度"，在 {@code [-0.06, -0.35]} 之间做 smoothstep 下降。
     * 曲线的<b>形状</b>用平滑函数而非分段常数，避免折痕。</p>
     */
    private static DoubleBinaryOperator buildContinentalSlope(int b) {
        // 离岸程度：波长 1200 块
        RefNoise.Node offshore = RefNoise.Perlin.ofScale(b + 501, 1200f, 2);
        return (x, z) -> {
            double n = RefNoise.clamp((float) (offshore.compute((float) x, (float) z) * 2.0 - 1.0),
                    -1f, 1f);
            // [-1,1] → [0,1]，再 smoothstep 使两端导数为 0
            double t = (n + 1.0) * 0.5;
            t = t * t * (3.0 - 2.0 * t);
            // 浅端 -0.06（陆架边缘）→ 深端 -0.35（深海平原附近）
            return -0.06 + (-0.35 - -0.06) * t;
        };
    }

    // ============================================================ 概念移植（步 4）
    //
    // 下面两个配方使用 {@link OceanMorphConcepts} 的四个概念。
    // ★ 关键差别：旧链以 edge（c 空间一维标量）为主坐标；这里没有 edge，
    //   所以把 edge 换成"配方自己能算的局部场"，把三段拼接换成单一连续公式。

    /**
     * 大陆坡：<b>已由复合机制取代，不再作为独立配方</b>。
     *
     * <h3>★ 为什么删掉了概念移植版</h3>
     * <p>步 4 曾把它实现成 {@code buildContinentalSlopeConcept}（含 C1 cliffFactor、
     * C2 宽度因子、C3 端点不变式）。按"内部类 / 过渡类"的分层，这是<b>放错了位置</b>：</p>
     * <ul>
     *   <li>它是<b>过渡</b>（海↔海）：陆坡 = 陆架 ↔ 深海平原之间的斜坡；</li>
     *   <li>而 C1/C2/C3 三个概念全都来自旧链的 <b>岸段</b>逻辑，
     *       本质属于「陆↔海」海岸过渡，不属于海底内部形态。</li>
     * </ul>
     * <p>实测验证了"复合可以替代手写陆坡"：
     * {@code ABYSSAL_PLAIN × SHELF_RIDGE} 在 α=0.5 处混合得 {@code −0.2121}，
     * 而手写 {@code CONTINENTAL_SLOPE} 的中位是 {@code −0.2056} ——
     * <b>差 0.0065 e ≈ 0.8 格</b>，且复合的坡度还随 α 的空间梯度自然变化。</p>
     *
     * <p>因此本配方<b>不再出现在内部类里</b>；陆坡由
     * {@link OceanTransitionCatalog} 的「海↔海」复合族自动生成。</p>
     */

    /**
     * 洋中脊（概念移植版）：{@code MID_OCEAN_RIDGE} 的构造门控实现。
     *
     * <h3>移植了 C4</h3>
     * <p>旧 {@code OceanFeatures.ridgeGate} 的门控是
     * {@code smoothPos(−stress) × gaussian(dist, 130)}。新版
     * {@link OceanMorphConcepts#tectonicGate} 复刻同一语义，
     * 但<b>不引用 {@code TectonicField}</b>：调用方把 {@code stress} 与 {@code dist}
     * 作为<b>参数</b>传进来（本配方内部用自身噪声合成一对代理量，
     * 接生产时换成真正的构造场）。</p>
     *
     * <h3>为什么必须两个量相乘</h3>
     * <p>只乘 stress ⇒ 脊铺满整个离散海盆（旧类实测 RIDGE 占比 11.29%，远超边界带面积）。
     * 只乘 dist ⇒ 脊也出现在汇聚边界（那里应该是海沟/弧）。
     * 本配方保持"两者缺一不可"。</p>
     */
    private static Shape buildMidOceanRidgeConcept(int b, double plainE, double peakE) {
        // 代理 stress：低频场，负=离散 / 正=汇聚
        RefNoise.Node stressP = RefNoise.Perlin.ofScale(b + 521, 3200f, 2);
        // 代理 dist：低频场的"到零线距离"
        RefNoise.Node distP = RefNoise.Perlin.ofScale(b + 522, 2000f, 2);
        RefNoise.Node detail = RefNoise.Perlin.ofScale(b + 523, 400f, 2);
        // 构造门控（C4）：stress × dist 缺一不可（原式与端点<b>共用</b>这个真实门控）
        DoubleBinaryOperator gate = (x, z) -> {
            double stress = stressP.compute((float) x, (float) z) * 2.0 - 1.0;
            // dist：|n| 小 = 接近"边界线"，映射到 0..600 wu 量级
            double dn = Math.abs(distP.compute((float) x, (float) z) * 2.0 - 1.0);
            double dist = dn * 600.0;
            return OceanMorphConcepts.tectonicGate(stress, dist,
                    OceanMorphConcepts.RIDGE_BAND_SIGMA);
        };
        DoubleBinaryOperator height = (x, z) -> {
            double g = gate.applyAsDouble(x, z);
            if (g <= 0.0) return plainE;
            // 原式：轴向细节 0.7 ± 0.3，抬升 (peak−plain)·gate·detail
            double d = detail.compute((float) x, (float) z) * 2.0 - 1.0;
            return plainE + (peakE - plainE) * g * (0.7 + 0.3 * d);
        };
        // 端点：raw = 0.7+0.3·detail；amp = (peak−plain)·gate；bias = plainE（常数 −0.36）
        DoubleBinaryOperator raw = (x, z) ->
                0.7 + 0.3 * (detail.compute((float) x, (float) z) * 2.0 - 1.0);
        DoubleBinaryOperator amp = (x, z) -> (peakE - plainE) * gate.applyAsDouble(x, z);
        DoubleBinaryOperator bias = (x, z) -> plainE;
        return new Shape(height, raw, amp, bias, false);
    }

    /**
    /**
     * 陆架沙脊：浅海带内的起伏沙洲。
     *
     * <p>移植已完成的 {@code ShallowSeaIslandCandidate} 与 {@code shallowDetail} 思路：
     * 在 {@code [-0.10, 0]} 内做小幅起伏。<b>不抬过 0</b>（不造岛）——
     * 造岛是 {@code ShallowSeaIslandCandidate} 的职责，两者正交。</p>
     */
    private static Shape buildShelfRidge(int b) {
        RefNoise.Node ridge = RefNoise.PerlinRidge.ofScale(b + 601, 260f, 3);
        RefNoise.Node mask = RefNoise.Perlin.ofScale(b + 602, 900f, 2);
        // 成脊门控：只在部分海域成脊（避免整条浅海带都有沙脊）
        DoubleBinaryOperator gate = (x, z) -> {
            double m = mask.compute((float) x, (float) z);
            return RefNoise.clamp((float) ((m - 0.45) / 0.25), 0f, 1f);
        };
        // 原始脊信号 [0,1]
        DoubleBinaryOperator raw = (x, z) -> ridge.compute((float) x, (float) z);
        DoubleBinaryOperator height = (x, z) -> {
            // 原式：基准 -0.07，抬升最多 +0.06 e（≈7.6 格），上限封在 0（不露出海面）
            double r = ridge.compute((float) x, (float) z);
            double v = -0.07 + 0.06 * gate.applyAsDouble(x, z) * r;
            return Math.min(v, 0.0);
        };
        // 端点：raw = ridge；amp = 0.06·gate；bias = −0.07；ceiling = true（min(·,0) 由 ceiling 表达）
        DoubleBinaryOperator amp = (x, z) -> 0.06 * gate.applyAsDouble(x, z);
        DoubleBinaryOperator bias = (x, z) -> -0.07;
        return new Shape(height, raw, amp, bias, true);
    }

    // ============================================================ 形态描述
    //
    // 每个配方一个 Shape：原公式 + 三件端点（raw/amp/bias）+ ceiling。
    // ★ 两套表达式各自书写、只共享底层噪声节点 ⇒ "端点重建 == 原公式"是真判据。

    /**
     * 一个配方的双口径描述。
     *
     * @param heightFn 原公式（{@link #height01} 走这里，产出逐位不变）
     * @param rawFn    信号层：剥掉线性外壳后的内部形态（<b>不是</b>成品高度）
     * @param ampFn    线性外壳振幅（允许随位置连续变化）
     * @param biasFn   线性外壳偏置
     * @param ceiling  外壳之后是否还有 {@code min(·, 0)}（只有 {@link Recipe#SHELF_RIDGE} 有）
     */
    private record Shape(DoubleBinaryOperator heightFn, DoubleBinaryOperator rawFn,
                         DoubleBinaryOperator ampFn, DoubleBinaryOperator biasFn,
                         boolean ceiling) implements MorphRecipe {

        /** 原公式求值（{@code height01} 的唯一入口）。 */
        double height(double x, double z) {
            return heightFn.applyAsDouble(x, z);
        }

        @Override public double raw(double x, double z) { return rawFn.applyAsDouble(x, z); }

        @Override public double amplitude(double x, double z) { return ampFn.applyAsDouble(x, z); }

        @Override public double bias(double x, double z) { return biasFn.applyAsDouble(x, z); }

        /** 海洋配方无台地/劣地/山地的非线性阶段，role 一律取 {@link MorphRecipe.Role#LOW}。 */
        @Override public Role role() { return Role.LOW; }

        /** 海洋节点：{@code land()=false}。 */
        @Override public boolean land() { return false; }

        /** 外壳之后没有 {@code max(·,0)}（那是陆地复合条目的性质）。 */
        @Override public boolean clamped() { return false; }

        @Override public boolean ceiling() { return ceiling; }
    }

    // ============================================================ 诊断

    /** 一行摘要（探针打印用）。 */
    String summary() {
        StringBuilder sb = new StringBuilder("OceanRecipeCatalog seed=" + seed + " entries=" + ENTRY_COUNT
                + " slots=" + SLOT_COUNT + "\n");
        for (Recipe r : Recipe.values()) {
            double[] d = depthRange(r);
            sb.append(String.format(Locale.ROOT,
                    "  [%d] %-18s weight=%.1f  depth=[%+.2f, %+.2f] e  (blocks %+.0f..%+.0f)%n",
                    r.id, r, weight(r), d[0], d[1], d[0] * BLOCKS_PER_E, d[1] * BLOCKS_PER_E));
        }
        return sb.toString();
    }
}
