package com.geogenesis.worldgen.ore;

import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.Noise3;
import com.geogenesis.worldgen.noise.Simplex;
import com.geogenesis.worldgen.noise.Simplex3;
import com.geogenesis.worldgen.terrain.RockType;

/**
 * 矿脉生成（<b>零 Minecraft 依赖的纯函数</b>）。
 *
 * <h2>为何必须自研（与原版 ore feature 的关系）</h2>
 * <p>本项目地形由自定义 {@code ChunkGenerator} 生成、<b>没有 {@code NoiseSettings}</b>
 * ⇒ 原版 {@code ConfiguredFeature}({@code ore_*}) 用不了：它们依赖
 * {@code OreConfiguration} 的 {targets → replaceable} 替换机制与 {@code FeatureSorter}
 * 放置阶段，两者都要求原版世界生成管线（同 {@code CaveShape} 的处境）。
 * 故自研，复用本项目既有的 {@link Simplex3}。</p>
 *
 * <h2>设计：三层门控（由便宜到昂贵）</h2>
 * <ol>
 *   <li><b>① 成矿带（2D 列级）</b>：{@link #columnProspective}。真实矿床<b>成区带/矿区
 *       分布</b>（ore district），不是均匀撒点。用一张低频 2D 噪声划出"成矿带"，带内才有脉。
 *       <b>这是性能主控</b>：一次求值就否掉约 95% 的列 ⇒ 后续逐块成本只落在带内。</li>
 *   <li><b>② 宿主岩 + 深度带（整数比较，零噪声）</b>：每种矿只在<b>特定岩性</b>与
 *       <b>特定深度</b>出现（见 {@link Ore}）。纯整数门控，最便宜。</li>
 *   <li><b>③ 脉体几何（3D 等值面）</b>：{@link #veinAt}。双噪声<b>等值面交集</b>
 *       —— 与 {@code CaveShape} 隧道同原理（两曲面相交得 1D 管道），产出<b>狭长脉体</b>
 *       而非圆球。</li>
 * </ol>
 *
 * <h2>★★ 地质学正确性（本项目独创，三个参考项目均无）</h2>
 * <p>矿种<b>不是随机撒</b>，而是<b>按岩性与深度成矿</b> —— 与既有的水平地层系统
 * （{@code StratumField}）和洞穴的岩性门控同源。各矿种的宿主岩与深度带见
 * {@link Ore} 枚举的 javadoc 表。</p>
 *
 * <h2>性能（必须量化，见 {@code OreVeinProbe}）</h2>
 * <p>逐块求值是热路径，故：① 列级门控先否掉 ~95% 列；② 逐块先比整数（宿主岩/深度），
 * 再求噪声；③ 脉体判定有<b>短路</b>（{@code |n1| ≥ t1} 时不再求 {@code n2}）。
 * 实测成本见探针，基准为洞穴的 {@code geo_per_chunk_ms=2.48}。</p>
 */
public final class OreVeins {

    private OreVeins() { }

    // ===================== 矿种定义 =====================

    /** 矿种（ordinal 即 {@link #veinAt} 的返回值，-1 = 无矿）。 */
    public enum Ore {
        /** 煤：沉积岩（有机质成煤）。 */
        COAL(6, 70, 1.00, RockType.SANDSTONE, RockType.SHALE),
        /** 铜：火山成因（斑岩铜矿）。 */
        COPPER(20, 90, 0.85, RockType.BASALT, RockType.ANDESITE, RockType.SANDSTONE),
        /** 铁：分布最广（沉积 + 变质 BIF）。 */
        IRON(15, 110, 0.75, RockType.values()),
        /** 金：造山型（变质 + 侵入）。 */
        GOLD(30, 130, 0.45, RockType.GRANITE, RockType.GNEISS, RockType.SCHIST),
        /** 红石：深部结晶基底。 */
        REDSTONE(60, 170, 0.50, RockType.GNEISS, RockType.SCHIST, RockType.GRANITE),
        /** 青金石：石灰岩接触交代（矽卡岩）。 */
        LAPIS(40, 130, 0.40, RockType.LIMESTONE),
        /** 绿宝石：变质岩产出（与 MC 山地一致）。 */
        EMERALD(70, 190, 0.30, RockType.SCHIST, RockType.GNEISS),
        /** 钻石：克拉通深部。 */
        DIAMOND(90, 220, 0.26, RockType.GNEISS, RockType.SCHIST);

        /** 深度带下界（距地表至少这么多块）。 */
        public final int minDepth;
        /** 深度带上界（距地表最多这么多块）。 */
        public final int maxDepth;
        /** 稀有度系（1.0 = 最富；越小脉体越细越稀）。 */
        public final double richness;
        /** 宿主岩位掩码。 */
        private final int hostMask;

        Ore(int minDepth, int maxDepth, double richness, RockType... hosts) {
            this.minDepth = minDepth;
            this.maxDepth = maxDepth;
            this.richness = richness;
            int m = 0;
            for (RockType r : hosts) m |= (1 << r.ordinal());
            this.hostMask = m;
        }

        /** 该岩性是否为宿主。 */
        public boolean hosts(int rockOrd) {
            return rockOrd >= 0 && rockOrd < 32 && (hostMask & (1 << rockOrd)) != 0;
        }
    }

    /** 全部矿种（缓存，避免每次 {@code values()} 复制数组）。 */
    public static final Ore[] ORES = Ore.values();

    /** 所有矿种的最大深度带（供方块层剪掉无矿的 Y 区间）。 */
    public static final int MAX_DEPTH = maxDepthOfAll();

    /**
     * 所有矿种的最小深度带（供方块层剪掉无矿的 Y 区间）。
     *
     * <p>方块层的 Y 循环从 {@code WORLD_MIN_Y}(-64) 起、最多可到地表下 380 格，
     * 而矿脉只在地表下 6~220 格内。若不做此剪枝，每列会多出 ~160 次必然失败的
     * {@link #veinAt} 调用（虽每次只是 8 个整数比较，但 256 列 × 160 次 = 4 万次/chunk
     * 属纯浪费）。故方块层用 {@code [surfaceY - MAX_DEPTH, surfaceY - MIN_DEPTH]}
     * 作为遍历窗口。</p>
     */
    public static final int MIN_DEPTH = minDepthOfAll();

    private static int maxDepthOfAll() {
        int m = 0;
        for (Ore o : ORES) m = Math.max(m, o.maxDepth);
        return m;
    }

    private static int minDepthOfAll() {
        int m = Integer.MAX_VALUE;
        for (Ore o : ORES) m = Math.min(m, o.minDepth);
        return m;
    }

    // ===================== ① 成矿带（2D 列级门控）=====================

    /** 成矿带的特征尺度（block）：越大矿区越大越少。 */
    private static final double PROSPECT_SCALE = 190.0;

    /**
     * 成矿带阈值：噪声值高于此即"成矿带内"。
     *
     * <p>这是性能主控旋钮（带内列才做逐块脉体判定），同时决定矿脉在视觉上是否
     * 聚成<b>矿区</b>（真实矿床的分布方式）而非均匀散布。</p>
     *
     * <h3>取值依据（{@code OreVeinProbe} 扫描，ND=512 大窗口）</h3>
     * <p>0.341 = 0.62 × 0.55（扫描出的 prosMul=0.55）。注意<b>必须用大窗口测量</b>：
     * 成矿带特征尺度 190 块，实测 N=96 时<b>带内列 0%</b>（不足一个特征 ⇒ 统计空转），
     * N=128 时仅 5.4%、N=512 时 27~36% —— 与洞穴密度那次踩的窗口坑<b>同型</b>。</p>
     */
    private static final double PROSPECT_T = 0.341;

    private static final int SALT_PROSPECT = 0x6A1D93F7;
    private static final Noise PROSPECT = new Simplex(SALT_PROSPECT);

    // ===================== ③ 脉体几何（3D 等值面）=====================

    /**
     * 脉体噪声的特征尺度（block）。
     *
     * <p>比洞穴隧道（{@code CaveShape} 的 120/155）<b>小得多</b> —— 矿脉是窄脉，
     * 不应挖出可走动的通道。两尺度仍需<b>略有差异</b>（1.32 倍）：完全相同会让两张
     * 曲面平行、交集退化成整片面（"撒满矿"，实测教训见 {@code CaveShape}）。</p>
     */
    private static final double VEIN_SCALE_A = 34.0;
    private static final double VEIN_SCALE_B = 45.0;

    /**
     * Y 各向异性缩放（{@code y} 采样频率倍率）。
     *
     * <p>矿脉在真实地质中沿<b>层理/裂隙</b>发育 ⇒ 略趋水平；但也要有<b>穿层的陡脉</b>
     * （热液沿断裂上升）⇒ 取中等各向异性，而非洞穴那种强压扁。</p>
     */
    private static final double VEIN_Y_SCALE = 1.6;

    private static final int SALT_VEIN_A = 0x3E7B14C9;
    private static final int SALT_VEIN_B = 0x58D2A6E1;
    private static final Noise3 VEIN_A = new Simplex3(SALT_VEIN_A);
    private static final Noise3 VEIN_B = new Simplex3(SALT_VEIN_B);

    /**
     * 脉体基准阈值（噪声单位）：越小脉越细。
     *
     * <h3>取值依据（{@code OreVeinProbe} 扫描）</h3>
     * <p>0.104 = 0.026 × 4.0（扫描出的 veinMul=4.0）。阈值的作用是体积 ∝ t²，
     * 故从 0.026 提到 0.104（4 倍）实际把脉体体积放大约 <b>16 倍</b> —— 这正是
     * 首版实测"脉体碎片化（平均 1.7 块、孤立点 76.5%）"所需的量级修正。</p>
     *
     * <p>对照：洞穴隧道阈值为 0.042，本值约其 <b>2.5 倍</b>。矿脉比洞穴粗是刻意的 ——
     * 洞穴是"能走的通道"（细长即可），矿脉是"可采的矿体"（需要成团才值得挖）。</p>
     *
     * <p>实测（ND=512，种子 12345/7/42）：密度 <b>0.41% / 0.54% / 0.43%</b>、
     * 平均连通分量 <b>26.2 / 26.4 / 27.5 块</b>、孤立点 20% 左右 —— 三种子稳健。</p>
     */
    private static final double VEIN_T = 0.104;

    /**
     * 每种矿的坐标<b>偏移</b>：避免为 8 种矿各建一对噪声实例。
     *
     * <p>Simplex 非周期 ⇒ 把采样点平移到差异极大的坐标即得<b>相互独立</b>的场。
     * 于是 8 种矿共用同一对 {@link Noise3} 实例（只有 2 个实例、零额外内存）。</p>
     */
    private static double offX(int i) { return 1370.0 * (i + 1); }
    private static double offY(int i) { return 2410.0 * (i + 1); }
    private static double offZ(int i) { return 3180.0 * (i + 1); }

    // ===================== 播种 =====================

    private static volatile boolean seeded = false;

    /** 换世界种子（与地形/河网/洞穴同批失效，避免跨存档串扰）。 */
    public static synchronized void setSeed(long worldSeed) {
        if (PROSPECT instanceof Simplex s) s.seed(worldSeed, 0);
        seedOne(VEIN_A, worldSeed, 1);
        seedOne(VEIN_B, worldSeed, 2);
        seeded = true;
    }

    /** {@link Noise3} 接口无 {@code seed} ⇒ 按具体实现下探（同 {@code CaveShape} 范式）。 */
    private static void seedOne(Noise3 n, long worldSeed, int level) {
        if (n instanceof Simplex3 s3) s3.seed(worldSeed, level);
    }

    /** 是否已播种。 */
    public static boolean isSeeded() {
        return seeded;
    }

    // ===================== 门控 ①②③ =====================

    /**
     * ① 该 (x,z) 列是否落在<b>成矿带</b>内（<b>只读，不缓存</b>）。
     *
     * <p>供<b>诊断</b>与"想先看整列是否值得遍历"的场景使用。生产方块层用
     * {@link #beginColumn} + {@link #veinAt}，无需直接调用本方法。</p>
     */
    public static boolean columnProspective(int wx, int wz) {
        if (!seeded) return false;
        double v = PROSPECT.compute(wx / PROSPECT_SCALE, wz / PROSPECT_SCALE);
        return v > PROSPECT_T * dbgProspectMul;
    }

    // ===== ★ 列级成矿带缓存（避免遗漏门控 + 避免重复求值）=====

    /**
     * 缓存的那一列的坐标与结果。方块层按列（lx 外层、lz 内层或反之）遍历 ⇒
     * 相邻调用<b>绝大多数落在同一列</b>，一次缓存即足够（命中率 ≈ 100%）。
     */
    private static int cachedColX = Integer.MIN_VALUE;
    private static int cachedColZ = Integer.MIN_VALUE;
    private static boolean cachedProspective = false;

    /**
     * ★ 进入一列：计算并缓存"本列是否在成矿带内"。<b>方块层每列调用一次</b>。
     *
     * <h3>为何要这个入口（实测缺陷）</h3>
     * <p>初版把"成矿带门控"与"脉体判定"做成两个<b>分离</b>的公开方法，并注明
     * "调用前应先用 columnProspective 过滤"。但这条约定<b>太容易漏</b> ——
     * {@code OreVeinProbe} 的扫描模式就直接调了 {@code veinAt} 而漏掉门控，
     * 实测后果是<b>成矿带完全失效</b>（{@code prosMul} 三档扫描结果完全相同）、
     * 矿脉均匀散布于整个世界（违背"矿床成区带分布"的设计）。</p>
     * <p>故改为：{@link #veinAt} <b>内部自行查这个缓存</b> ⇒ 门控无法被绕过。
     * 缓存按坐标判定，即使调用方乱序遍历也正确（只是命中率降低）。</p>
     *
     * @return 本列在成矿带内 ⇒ true（此时才值得遍历本列的 Y）
     */
    public static boolean beginColumn(int wx, int wz) {
        if (wx == cachedColX && wz == cachedColZ) return cachedProspective;
        boolean p = columnProspective(wx, wz);
        cachedColX = wx;
        cachedColZ = wz;
        cachedProspective = p;
        return p;
    }

    /**
     * ②③ 该体素是否为矿脉，是则返回矿种 ordinal（-1 = 无）。
     *
     * <p>★ <b>成矿带门控已内建</b>（走 {@link #beginColumn} 的缓存）⇒ 调用方
     * <b>不可能漏用</b>。为省成本，方块层仍建议先调 {@link #beginColumn} 并跳过
     * 整列 Y 循环（避免 200+ 次无谓的深度/岩性比较）。</p>
     *
     * @param wx,wz     世界水平坐标
     * @param wy        世界 Y
     * @param surface   该列地表高（block）
     * @param rockOrd   该体素<b>所在层</b>的岩性 ordinal（越界/-1 = 无数据 ⇒ 不成矿）
     * @param worldMinY 世界最低 Y
     * @return 矿种 ordinal，或 -1
     */
    /**
     * 该 Y 是否落在<b>任何矿种</b>的可能深度窗口内（方块层用它剪 Y 循环）。
     *
     * <p>{@code [surface−MAX_DEPTH, surface−MIN_DEPTH]}。超出即不可能成矿 ⇒ 方块层
     * 可整段跳过（省掉每列上百次无谓比较）。</p>
     */
    public static boolean depthInRange(int wy, int surface) {
        int d = surface - wy;
        return d >= MIN_DEPTH && d <= MAX_DEPTH;
    }

    public static int veinAt(int wx, int wy, int wz, int surface, int rockOrd, int worldMinY) {
        if (!seeded) return -1;
        if (!beginColumn(wx, wz)) return -1;        // ★ 成矿带门控（内建，不可绕过）
        if (rockOrd < 0) return -1;
        if (wy <= worldMinY) return -1;

        int depth = surface - wy;
        if (depth < 0 || depth > MAX_DEPTH) return -1;

        // 按声明序（富矿在前 ⇒ 富集处不会被贫矿"抢占"），且只在
        // 【宿主岩 + 深度带】都满足时才求噪声（整数比较在前，噪声在后）。
        for (int i = 0; i < ORES.length; i++) {
            Ore o = ORES[i];
            if (depth < o.minDepth || depth > o.maxDepth) continue;
            if (!o.hosts(rockOrd)) continue;
            if (veinHit(i, wx, wy, wz, o.richness)) return i;
        }
        return -1;
    }

    /**
     * 双噪声<b>等值面交集</b>：{@code |n1| < t && |n2| < t}。
     *
     * <p>只有"两张曲面相交"才得到<b>有限尺寸的一维脉体</b>；单噪声阈值会给出
     * 无限延伸的大块（数学必然，实测教训见 {@code CaveShape}）。</p>
     *
     * <p><b>短路</b>：{@code |n1| ≥ t} 时立即返回（多数体素在此返回）
     * ⇒ 平均只求 1~1.3 次噪声（而非 2 次）。</p>
     *
     * @param i       矿种序号（决定坐标偏移 ⇒ 各矿的场相互独立）
     * @param richness 稀有度系（越小脉越细）
     */
    private static boolean veinHit(int i, int wx, int wy, int wz, double richness) {
        double t = VEIN_T * richness * dbgVeinMul;
        double ox = offX(i), oy = offY(i), oz = offZ(i);
        double x = wx + ox, y = wy + oy, z = wz + oz;
        double ys = VEIN_Y_SCALE;

        double n1 = VEIN_A.compute(x / VEIN_SCALE_A, y / (VEIN_SCALE_A * ys), z / VEIN_SCALE_A);
        if (Math.abs(n1) >= t) return false;
        double n2 = VEIN_B.compute(x / VEIN_SCALE_B, y / (VEIN_SCALE_B * ys), z / VEIN_SCALE_B);
        return Math.abs(n2) < t;
    }

    // ===================== ★ 诊断覆盖（生产恒为默认值）=====================

    /**
     * 诊断用阈值倍率（{@code veinHit} 的 {@code t} 乘此值）。
     *
     * <p>脉体密度对阈值极度敏感（{@code |n|<t} 的体积 ∝ t²）⇒ 必须能<b>扫描</b>
     * 而非靠猜。该字段只被探针写入，<b>生产路径永不修改</b>（默认 1.0）。</p>
     */
    static volatile double dbgVeinMul = 1.0;
    /** 诊断用成矿带阈值倍率（越小带越大、矿越多）。 */
    static volatile double dbgProspectMul = 1.0;

    /** 供探针扫描脉体阈值。 */
    public static void dbgSetVein(double veinMul) {
        dbgVeinMul = veinMul;
    }

    /** 供探针扫描成矿带阈值。 */
    public static void dbgSetProspect(double prospectMul) {
        dbgProspectMul = prospectMul;
    }

    /** 恢复生产默认参数。 */
    public static void dbgReset() {
        dbgVeinMul = 1.0;
        dbgProspectMul = 1.0;
    }
}
