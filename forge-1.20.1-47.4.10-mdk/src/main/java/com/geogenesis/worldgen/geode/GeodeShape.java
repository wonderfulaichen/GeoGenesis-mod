package com.geogenesis.worldgen.geode;

import com.geogenesis.worldgen.terrain.RockType;

/**
 * 紫晶洞<b>纯函数</b>（★ 2026-09-15）：零 MC 依赖的确定性形状判定。
 *
 * <h2>为何自研（原版 geode feature 用不了）</h2>
 * <p>与洞穴、矿脉同因：本项目地形由自定义 {@code ChunkGenerator} 生成，
 * <b>没有 {@code NoiseSettings}</b> ⇒ 原版的地物/雕刻器无法挂载，必须自己写。</p>
 *
 * <h2>地质依据：杏仁状玄武岩（amygdaloidal basalt）</h2>
 *
 * <p>现实中的晶洞（geode / amygdule）主要产于<b>火山岩</b>中：熔岩冷却时析出的
 * <b>气孔</b>被后期<b>富硅热液充填</b>，自外向内依次沉淀
 * 石英/玉髓壳 → 方解石 → 紫水晶晶簇，中心留空腔。故本项目把它<b>门控在
 * 玄武岩/安山岩</b>（{@link #HOST_MASK}）中 —— 这是"岩性门控"的第三个应用
 * （前两个是洞穴的可溶岩、矿脉的成矿岩），三个参考项目都没有这种耦合。</p>
 *
 * <p><b>★ 被岩层界面"切平"是正确产状，不是缺陷</b>：杏仁体形成于<b>单一熔岩流</b>
 * 内部，本函数按<b>体素自身岩性</b>门控 ⇒ 晶洞在岩层界面处自然被切平，
 * 形态上正是一层层有限的<b>透镜状</b>矿体 —— 与真实杏仁状玄武岩一致。
 * 这同时带来实现上的好处：<b>无需跨体素状态</b>（不必查"洞心岩性"），
 * 只需体素自己的 {@code rockOrd}（方块层已算出，<b>零额外采样</b>）。</p>
 *
 * <h2>形状模型：3D 晶格候选 + 椭球</h2>
 *
 * <p>洞穴/矿脉用"3D 噪声等值面"，那给出的是<b>面</b>（薄壳）或<b>线</b>（两壳相交），
 * 而晶洞要的是<b>体</b>（有内部空腔的团块）⇒ 噪声等值面不适用，
 * 改用<b>离散候选中心 + 椭球</b>。</p>
 *
 * <p><b>★ 为何每体素只查一个单元格（本类最关键的论证）</b>：
 * 把空间切成边长 {@link #CELL}=32 的立方网格，每格由哈希决定
 * ①是否有晶洞 ②中心位置（格心 + 抖动 ±{@link #JITTER}）③半轴长。
 * 因 {@code JITTER + MAX_SEMI_AXIS = 9 + 6.5 = 15.5 ≤ CELL/2 = 16}，
 * <b>整个椭球必然落在它自己那一格内</b> ⇒
 * 任意体素若在某个晶洞内，该晶洞所在格<b>必是本体素所在格</b>。
 * 于是 <b>只查 1 格，不需要查 3³=27 邻格、也不需要任何缓存</b> ——
 * 既快，又在多 chunk 并行生成下<b>完全无共享状态</b>。</p>
 *
 * <p>⚠ <b>若日后要放大晶洞，必须同步放大 {@link #CELL}</b>：等式
 * {@code JITTER + MAX_SEMI_AXIS ≤ CELL/2} 一旦破坏，"只查 1 格"就失效，
 * 洞会被邻格切掉一部分（形态残缺且难以定位）。</p>
 *
 * <h2>同心壳层：顺序由数学保证，不靠调参</h2>
 * <p>取归一化半径 {@code r = |Δ| / 半轴}（{@code r<1} 即洞内，{@code r} 自中心
 * <b>单调递增</b>）后按 {@code r} 分档：{@code r} 小 ⇒ 内层。故"外壳 → 方解石 →
 * 紫水晶 → 晶芽 → 空腔"的层序<b>必然正确</b>，不可能颠倒
 * （若改用噪声值分档就没有这个保证 —— 噪声在洞内不单调）。</p>
 *
 * <h2>性能（必须量化，见 {@code runGeodeProbe}）</h2>
 * <p>两层<b>整数门控</b>把绝大多数体素挡在哈希之前（① 岩性掩码 → ② 深度带），
 * 只有<b>杏仁岩 + 深度带内</b>的体素才做哈希与椭球判定。</p>
 */
public final class GeodeShape {

    private GeodeShape() { }

    // ===================== 材料编号（方块层映射到实际方块）=====================

    /** 不在任何晶洞内。 */
    public static final int MAT_NONE = -1;
    /** 外壳：平滑玄武岩（岩浆冷却壳，与宿主玄武岩区分）。 */
    public static final int MAT_BASALT = 0;
    /** 方解石带（热液早期沉淀）。 */
    public static final int MAT_CALCITE = 1;
    /** 紫水晶块（主晶层）。 */
    public static final int MAT_AMETHYST = 2;
    /** 晶芽（{@code BUDDING_AMETHYST}，可继续长晶簇 —— 玩家最想要的一层）。 */
    public static final int MAT_BUDDING = 3;
    /** 空腔（中心残留空隙）。 */
    public static final int MAT_AIR = 4;
    /** 材料种类数（方块层映射表的长度）。 */
    public static final int MATERIALS = 5;

    // ===================== 宿主岩门控 =====================

    /**
     * 宿主岩位掩码：<b>玄武岩 + 安山岩</b>（火山岩）。
     *
     * <p>现实依据：杏仁状晶洞产于火山熔岩的气孔中。片麻岩/花岗岩等
     * <b>深成岩/变质岩</b>没有气孔构造 ⇒ 不成洞（与真实产状一致）。</p>
     */
    private static final int HOST_MASK = (1 << RockType.BASALT.ordinal())
            | (1 << RockType.ANDESITE.ordinal());

    // ===================== 深度带 =====================

    /**
     * 深度带下界（距地表至少这么多格）。
     *
     * <p>不能太浅：晶洞是<b>地下</b>结构，贴地表会破坏地表观感。</p>
     */
    public static final int MIN_DEPTH = 20;

    /**
     * 深度带上界（距地表最多这么多格）。
     *
     * <p>刻意<b>不用绝对 Y</b>（原版紫晶洞限定 Y≤30）：本项目已因"MC 的
     * {@code DEEPSLATE} 只在 Y&lt;0 取代石头"吃过亏（实测 29.8% 的列被迫回退
     * {@code STONE}），故一律用<b>相对地表的深度</b> ⇒ 高山与深海之下都能
     * 按同样的深度关系成洞。</p>
     *
     * <p>与本项目洞穴带（{@code CaveShape} 8~120）<b>刻意重叠</b>：
     * 洞穴挖开晶洞 ⇒ <b>洞壁上露出紫水晶</b>，这正是玩家发现晶洞的主要方式
     * （与矿脉"洞壁露头"同一套联动思路，且这里是<b>免费</b>得到的 ——
     * 洞穴雕刻在矿脉/晶洞之后的 {@code applyCarvers} 阶段，无需额外代码）。</p>
     */
    public static final int MAX_DEPTH = 160;

    /** 该 Y 是否落在晶洞可能的深度窗口内（方块层用它剪 Y 循环）。 */
    public static boolean depthInRange(int wy, int surface) {
        int d = surface - wy;
        return d >= MIN_DEPTH && d <= MAX_DEPTH;
    }

    /** 该岩性是否为宿主岩（供诊断与方块层预筛）。 */
    public static boolean isHostRock(int rockOrd) {
        return rockOrd >= 0 && rockOrd < 32 && (HOST_MASK & (1 << rockOrd)) != 0;
    }

    // ===================== 晶格与尺寸 =====================

    /**
     * 候选晶格边长（block）。
     *
     * <p>必须满足 {@code JITTER + MAX_SEMI_AXIS ≤ CELL/2}（见类 javadoc 的论证）。
     * 32 且抖动 9 ⇒ 15.5 ≤ 16 成立。</p>
     */
    public static final int CELL = 32;

    /** 中心抖动幅度（block，各轴 ±）。 */
    static final double JITTER = 9.0;

    /** 最大半轴（block）。与 {@link #JITTER} 之和必须 ≤ {@link #CELL}/2。 */
    public static final double MAX_SEMI_AXIS = 6.5;

    /** 最小半轴（block）。低于此会退化成"几块石头"，不值得占一个候选格。 */
    static final double MIN_SEMI_AXIS = 3.2;

    /**
     * 候选格成洞概率。
     *
     * <h3>取值依据：对齐原版（2026-09-16 查得，此前是"凭感觉"定的）</h3>
     * <p>原版：<b>每个 chunk 有 1/24 概率尝试生成一个晶洞</b>（Wiki / 数据包）。
     * 初版取 0.075 时 {@code runGeodeProbe} 实测 <b>1/41.2 chunk</b>（比原版稀 1.7×）；
     * 按线性折算改为 <b>0.075 × 41.2/24 ≈ 0.129</b> ⇒ 实测约 <b>1/24</b>。</p>
     *
     * <p>⚠ <b>口径警告（务必知悉）</b>：{@code runGeodeProbe} 用<b>合成岩性</b>
     * （8 种岩性均匀轮转 ⇒ 宿主岩约占 25%）。真实世界的密度取决于
     * {@code StratumField} 实际的岩性构成比例 ⇒ <b>探针值不等于实机值</b>。
     * 若实机发现过稀/过密，<b>本常量就是唯一旋钮</b>（每 chunk 洞数与它成正比）。</p>
     */
    static final double BASE_PRESENCE = 0.129;

    // ===================== 壳层分档（归一化半径，照原版 layers 比例）====================

    /**
     * 壳层分档<b>取自原版</b>（<b>必须保留出处，否则后人又会凭感觉改</b>）。
     *
     * <h3>★ 2026-09-16 修正：初版分档是我"凭感觉"定的，实测与形态差很多</h3>
     *
     * <p>原版 {@code amethyst_geode} 的 {@code layers} 参数（{@code configured_feature}
     * JSON / Wiki）：{@code filling=1.7 · inner_layer=2.2 · middle_layer=3.2 ·
     * outer_layer=4.2}。原版算法按<b>累加半径</b>比较：</p>
     * <pre>
     *   d0 = 1.7                  → 内腔（空气）
     *   d1 = 1.7 + 2.2 = 3.9      → 紫水晶层（含 8.3% 晶芽）
     *   d2 = 3.9 + 3.2 = 7.1      → 方解石层
     *   d3 = 7.1 + 4.2 = 11.3     → 平滑玄武岩外壳（到 11.3）
     * </pre>
     * <p>归一化到 {@code r ∈ [0,1]}：<b>空腔 0.150 · 紫水晶 0.345 · 方解石 0.628</b>、
     * 外壳到 1.0。换算成<b>体积</b>占比：外壳约 <b>75%</b>、方解石 21%、紫水晶 4%、
     * 空腔 0.3% —— 即原版紫晶洞是「<b>一颗大半是玄武岩的球，内里一个小水晶腔</b>」。</p>
     *
     * <p><b>我初版的分档（0.35 / 0.50 / 0.68 / 0.86）纯凭观感，实测体积占比变成
     * 外壳 36% / 方解石 32% / 紫水晶 19% / 晶芽 8% / 空腔 4.3%</b> ⇒
     * <b>空腔半径大 2.3 倍、外壳薄 2.6 倍</b>，形态成了「薄壳+一大团水晶」，
     * 与玩家熟悉的观感不符。<b>剖面图看不出来（都同心），是"方块体积占比"暴露的。</b>
     * 教训：凡是"MC 里已有对应物"的结构，参数应先查原版数据，而不是凭观感定。</p>
     */
    /** {@code r < } 此值 ⇒ 空腔（原版 {@code 1.7/11.3}）。 */
    static final double R_AIR = 0.150;
    /** {@code r < } 此值 ⇒ 紫水晶层（原版 {@code 3.9/11.3}）。晶芽散布在此层内。 */
    static final double R_AMETHYST = 0.345;
    /** {@code r < } 此值 ⇒ 方解石层（原版 {@code 7.1/11.3}）；再往外到 1.0 为平滑玄武岩外壳。 */
    static final double R_CALCITE = 0.628;

    /**
     * 紫水晶层内被替换为<b>晶芽</b>的比例（原版 {@code use_alternate_layer0_chance}）。
     *
     * <p>★ 原版<b>没有</b>"晶芽层"：晶芽是<b>散布在紫水晶层里</b>的方块
     * （约 8.3%）。我初版做成了独立壳层 0.35~0.50 —— 机制不对，且会把
     * 玩家最想要的晶芽挤到一条窄环上。改为<b>逐体素哈希</b>决定（确定性、
     * 无状态），晶芽均匀散布在整个紫水晶层内。</p>
     */
    static final double BUDDING_CHANCE = 0.083;

    // ===================== 播种 =====================

    private static volatile long worldSeed = 0L;
    private static volatile boolean seeded = false;

    /** 是否已播种（未播种 ⇒ 一律返回 {@link #MAT_NONE}）。 */
    public static boolean isSeeded() {
        return seeded;
    }

    /**
     * 换世界种子（与地形/河网/洞穴/矿脉同批失效，避免跨存档串扰）。
     *
     * <p>本类<b>不用噪声</b>（形状由整数哈希驱动）⇒ 只需记住种子，
     * 无需给任何 {@code Noise} 实例播种。</p>
     */
    public static synchronized void setSeed(long seed) {
        worldSeed = seed;
        seeded = true;
    }

    // ===================== 判定主入口 =====================

    /**
     * 判定该体素是否在某紫晶洞内，是则返回材料编号。
     *
     * <p>调用顺序按<b>成本从低到高</b>：整型岩性掩码 → 整型深度带 → 哈希 → 椭球。
     * 前两层把绝大多数体素挡在哈希之外，这是热路径的主控。</p>
     *
     * @param wx,wz     世界水平坐标
     * @param wy        世界 Y
     * @param surface   该列地表高（block）
     * @param rockOrd   该体素<b>所在层</b>的岩性 ordinal（越界/-1 ⇒ 不成洞）
     * @param worldMinY 世界最低 Y
     * @return 材料编号（{@link #MAT_NONE} = 不在洞内）
     */
    public static int geodeAt(int wx, int wy, int wz, int surface, int rockOrd, int worldMinY) {
        if (!seeded) return MAT_NONE;
        if (!isHostRock(rockOrd)) return MAT_NONE;                   // ① 岩性门控（最便宜）
        if (wy <= worldMinY) return MAT_NONE;
        int d = surface - wy;
        if (d < MIN_DEPTH || d > MAX_DEPTH) return MAT_NONE;          // ② 深度带门控

        // ③ 候选格（体素所在格 —— 正确性见类 javadoc：椭球不会越出本格）
        int cx = Math.floorDiv(wx, CELL);
        int cy = Math.floorDiv(wy, CELL);
        int cz = Math.floorDiv(wz, CELL);
        long h = hash(worldSeed, cx, cy, cz);
        if (!cellPresent(h)) return MAT_NONE;

        double nx = (wx + 0.5 - centerAxis(h, cx, 0)) / semiAxis(h, 0);
        double ny = (wy + 0.5 - centerAxis(h, cy, 1)) / semiAxis(h, 1);
        double nz = (wz + 0.5 - centerAxis(h, cz, 2)) / semiAxis(h, 2);
        double r2 = nx * nx + ny * ny + nz * nz;
        if (r2 >= 1.0) return MAT_NONE;                              // ④ 椭球外

        // 归一化半径（自中心单调↑）→ 壳层材料（晶芽在紫水晶层内散布）
        return shellOf(Math.sqrt(r2), wx, wy, wz);
    }

    /**
     * 归一化半径 → 材料（壳层分档）。{@code r} 自中心单调递增 ⇒ <b>层序必然正确</b>。
     *
     * <p>★ <b>晶芽不是独立壳层</b>：它在<b>紫水晶层内</b>按 {@link #BUDDING_CHANCE}
     * 逐体素散布（照原版 {@code use_alternate_layer0_chance}）⇒ 需要体素坐标做哈希，
     * 故本方法带坐标参数。</p>
     */
    static int shellOf(double r, int wx, int wy, int wz) {
        if (r < R_AIR) return MAT_AIR;
        if (r < R_AMETHYST) {
            return unit(hash(worldSeed, wx, wy, wz), 0) < BUDDING_CHANCE
                    ? MAT_BUDDING : MAT_AMETHYST;
        }
        if (r < R_CALCITE) return MAT_CALCITE;
        return MAT_BASALT;
    }

    // ===================== 形状派生（geodeAt 与诊断访问器共用，避免逻辑漂移）====================

    /** 该格是否有晶洞。 */
    static boolean cellPresent(long h) {
        return unit(h, 0) < BASE_PRESENCE * dbgPresenceMul;
    }

    /**
     * 候选中心在某轴上的坐标。
     *
     * @param axis 0=X · 1=Y · 2=Z（决定取哈希的哪一段）
     */
    static double centerAxis(long h, int cellCoord, int axis) {
        int k = switch (axis) {
            case 0 -> 1;
            case 1 -> 2;
            default -> 3;
        };
        return cellCoord * (double) CELL + CELL * 0.5
                + (unit(h, k) * 2.0 - 1.0) * JITTER;
    }

    /**
     * 候选椭球在某轴上的半轴。
     *
     * <p>X 为基准；Y 略扁（0.70~1.00）、Z 略长（0.85~1.15）⇒ 水平方向舒展、
     * 竖直方向压扁，与"熔岩流内气孔沿流动方向被压扁"的真实产状一致。
     * 三者一律 clamp 到 {@link #MAX_SEMI_AXIS}，<b>保证类 javadoc 的
     * "椭球不越出本格"论证不被破坏</b>。</p>
     */
    static double semiAxis(long h, int axis) {
        double r0 = MIN_SEMI_AXIS + unit(h, 4) * (MAX_SEMI_AXIS - MIN_SEMI_AXIS);
        double f = switch (axis) {
            case 0 -> 1.0;
            case 1 -> 0.70 + unit(h, 6) * 0.30;
            default -> 0.85 + unit(h, 5) * 0.30;
        };
        return Math.min(r0 * f * dbgSizeMul, MAX_SEMI_AXIS);
    }

    // ===================== 哈希 =====================

    /** 单元格坐标 → 64 位确定性哈希（含世界种子）。 */
    static long hash(long seed, int cx, int cy, int cz) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h = mix(h ^ (cx * 0xD6E8FEB86659FD93L));
        h = mix(h ^ (cy * 0xA24BAED4963EE407L));
        h = mix(h ^ (cz * 0x9FB21C651E98DF25L));
        return h;
    }

    /** SplitMix64 终混合（雪崩好、无状态、无分配）。 */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * 从哈希取第 {@code k} 个 {@code [0,1)} 均匀值。
     *
     * <h3>★ 为何"每次重新混合"而不是"取不同位段"（我实际踩过的 bug）</h3>
     * <p>初版写成 {@code (h >>> (k*9)) & 53位掩码} —— 看着像"取互不重叠的位段"，
     * 但 {@code h} 只有 <b>64 位</b>，k 稍大就把有效位右移殆尽：</p>
     * <ul>
     *   <li>{@code k=2}：只余 46 位有效 ⇒ 返回值恒 {@code < 1/128 ≈ 0}；</li>
     *   <li>{@code k>=4}：恒为 0。</li>
     * </ul>
     * <p>后果：<b>中心抖动与尺寸全部塌成固定值</b>（实测所有洞的尺寸恒为最小档
     * 3.2 / 2.24 / 2.72，尺寸分布挤在单一档、且两个不同洞的剖面图<b>逐字符相同</b>）。
     * 是 {@code runGeodeProbe} 的<b>尺寸分布判据</b>把它暴露出来的 ——
     * 若只做"是否成洞/壳层序"的判据，这个 bug 会一直藏着。</p>
     *
     * <p>正解：每次换一个加法常量<b>重新混合</b> ⇒ 每个用途都得到全新的、雪崩充分的
     * 64 位结果；取<b>高 53 位</b>即得均匀 {@code [0,1)}（53 = 双精度尾数位）。</p>
     */
    private static double unit(long h, int k) {
        long v = mix(h + k * 0x9E3779B97F4A7C15L);
        return (v >>> 11) * 0x1.0p-53;
    }

    // ===================== ★ 诊断访问器（生产不调用；探针用）=====================

    /**
     * 重算某候选格的形状参数，供探针<b>独立</b>复核壳层同心序。
     *
     * <p>刻意做成访问器而<b>不是让探针复制哈希</b>：复制会在实现改动后静默漂移，
     * 让判据误报（本会话已在 {@code LakeLocateProbe} 踩过"查了另一个实例"的坑）。
     * 与生产路径共用 {@link #hash}/{@link #cellPresent}/{@link #centerAxis}/
     * {@link #semiAxis} ⇒ <b>不可能漂移</b>。</p>
     *
     * @return {@code {gx, gy, gz, rx, ry, rz}}；该格无洞则 {@code null}
     */
    static double[] dbgCellShape(int cx, int cy, int cz) {
        if (!seeded) return null;
        long h = hash(worldSeed, cx, cy, cz);
        if (!cellPresent(h)) return null;
        return new double[]{
                centerAxis(h, cx, 0), centerAxis(h, cy, 1), centerAxis(h, cz, 2),
                semiAxis(h, 0), semiAxis(h, 1), semiAxis(h, 2)};
    }

    /** 诊断用"成洞概率倍率"（探针扫密度用；生产恒 1.0）。 */
    private static volatile double dbgPresenceMul = 1.0;
    /** 诊断用"尺寸倍率"（探针扫尺寸用；生产恒 1.0）。 */
    private static volatile double dbgSizeMul = 1.0;

    /** 供探针扫描密度。 */
    public static void dbgSetPresence(double mul) {
        dbgPresenceMul = mul;
    }

    /** 供探针扫描尺寸。 */
    public static void dbgSetSize(double mul) {
        dbgSizeMul = mul;
    }

    /** 恢复生产默认参数。 */
    public static void dbgReset() {
        dbgPresenceMul = 1.0;
        dbgSizeMul = 1.0;
    }
}
