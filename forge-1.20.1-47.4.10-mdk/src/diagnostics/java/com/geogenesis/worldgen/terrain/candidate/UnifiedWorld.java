package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>统一世界地形</b>：把内部类池 + 过渡类拼成一张完整的 1401² 图。
 *
 * <h3>★ 已定稿的结构（2026-11-01 用户多次确认）</h3>
 * <pre>
 *   池 = 内部类 + 过渡类
 *
 *   内部类
 *   ├── 陆地类型（8）：PLAIN/STEPPE/DALES/HILLS1/HILLS2/PLATEAU/BADLANDS/MOUNTAIN
 *   └── 海洋类型（5）：ABYSSAL_PLAIN/MID_OCEAN_RIDGE/SEAMOUNT_FIELD/CANYON/SHELF_RIDGE
 *
 *   过渡类
 *   ├── 陆地过渡：已由 CombineCandidate 的 45 票槽 + 240 块形态过渡实现（不重写）
 *   └── 海岸过渡：CoastRecipeCatalog 的 5 个海岸地貌
 * </pre>
 *
 * <h3>★ 三条铁律（全是用户已定稿的，不是我的选择）</h3>
 * <ol>
 *   <li><b>海岸是过渡类，不是内部类</b> —— 它的位置由"陆型与海型的交界带"决定，
 *       <b>不</b>占用自己的空间分区。</li>
 *   <li><b>海陆由高度涌现</b> —— 不预分区。{@code c} 只做类型选择，
 *       水边线 = 混合高度跨过 0 的位置。</li>
 *   <li><b>陆地过渡必须复用 {@link CombineCandidate}</b> —— 它自带 45 票槽 + 240 块形态过渡
 *       （上一轮交接记录的四层成果）。<b>不得</b>另造 edge 混合，
 *       否则会出现"边界带宽常数化"的斜直线伪影（实测过）。</li>
 * </ol>
 *
 * <h3>本类的算法（按上述定稿）</h3>
 * <pre>
 *   ① 陆地基底：eBase = CombineCandidate.height01(x,z) × E_SPAN + GROUND_E
 *      —— 这一层已经包含陆↔陆的全部 45 票槽过渡，是"内部类陆地"。
 *
 *   ② 海型门控：由 c 场（大陆性）决定"这片该是海还是陆"
 *      —— 与生产 CellGenerator 的 c→eOcean 同构。
 *      注意：这里用 c【只做类型选择】，不做分界线。
 *
 *   ③ 海洋配方：eSea = OceanRecipeCatalog.height01(recipe, x,z)（负值）
 *
 *   ④ 海岸过渡：只在 |d| < BAND 的带内生效
 *      —— d 是"到水边线"的有符号距离，水边线本身由 ② 的混合高度决定。
 *      —— 海岸剖面锚定 eLand（背后陆地越高、滩/崖越高），见 CoastRecipeCatalog。
 *
 *   ⑤ 合并：e = 陆海混合（由 ② 的 c 驱动权重）→ 再用 ④ 覆盖交界带
 * </pre>
 *
 * <p>只读：不引用旧装配链（{@code OceanAssemblyCandidate}），不写生产对象。</p>
 */
public final class UnifiedWorld {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    // ============================================================ 常量

    /** 陆地 e 到"陆地池 height01"的换算。与 {@link CombineCandidate#E_SPAN} 同源。 */
    static final double E_SPAN = CombineCandidate.E_SPAN;
    static final double GROUND_E = CombineCandidate.GROUND_E;

    /**
     * 海洋门控的 c 阈值：{@code c ≤ −OCEAN_C} 处完全由海洋配方接管。
     *
     * <h3>★ 取值必须对齐 c 场的<b>实测分布</b>（2026-11-01 修正）</h3>
     * <p>首版取 {@code 0.55}，理由是"与生产 ContinentField 的 P1 对齐"。
     * 实测 {@code c} 的分布（n=1681）：</p>
     * <pre>
     *   P1  = −0.556     ← 首版用它当"纯海"门槛
     *   P10 = −0.388
     *   P50 = +0.036
     *   P90 = +0.298
     * </pre>
     * <p>后果：只有最深 <b>1%</b> 的点能满足 {@code c ≤ −0.55} ⇒ 全图水面只有 25%、
     * 且最低点 Y=17（<b>在海平面以上</b>，完全没有海）。</p>
     * <p>改取 {@code 0.35}（≈ P10 与 P50 之间）：最深的 <b>~15%</b> 完全走海洋配方，
     * 中间带按 smoothstep 平滑过渡 —— 水边线由混合结果跨 0 决定，
     * 不是由这个阈值强行划分。</p>
     */
    static final double OCEAN_C = 0.35;

    /**
     * 本轮两处选型缺陷的 A/B 开关（<b>只用于诊断对照</b>；默认 {@link #BOTH}）。
     *
     * <h3>★ 为什么要有它</h3>
     * <p>2026-11-01 审计发现海侧两个独立缺陷：</p>
     * <ol>
     *   <li>{@code pickOcean} 的<b>映射方向反了</b> —— 枚举顺序是「深海 → 浅海」，
     *       而 {@code t=0} 表示"刚入海型（最浅）"，代码却直接 {@code round(t·4)}。</li>
     *   <li>{@code pickCoast} 的<b>分布被 Perlin 集中度压塌</b> —— 均分区间但噪声
     *       集中在 0.5 附近，实测 {@code REEF_FLAT} 占 58.5%、{@code SANDY_BEACH} 仅 0.07%；
     *       同时 {@link CoastRecipeCatalog#weight} 声明了票槽权重却从未被消费。</li>
     * </ol>
     * <p>两个缺陷互不影响，必须<b>单变量</b>出图才能判断各自贡献 ⇒ 四臂对照。</p>
     * <p>接生产时删掉本枚举、只保留修复路径（届时缓存 schema 必须升级）。</p>
     */
    enum Arm {
        /** 现状：两处都未修（= 用户此前看到的 30.1% 水面那张图）。 */
        BASELINE(false, false),
        /** 只修方向：{@code c} 越负越深。 */
        OCEAN_DIR(true, false),
        /** 只修分布：海岸按声明权重切分 + 对比度拉伸。 */
        COAST_DIST(false, true),
        /** 两处都修（默认）。 */
        BOTH(true, true);

        final boolean fixOceanDir;
        final boolean fixCoastDist;

        Arm(boolean fixOceanDir, boolean fixCoastDist) {
            this.fixOceanDir = fixOceanDir;
            this.fixCoastDist = fixCoastDist;
        }
    }

    /** 海岸带宽度（块）。与 {@link CoastalAssembler#BAND} 同值（按现实比例 80 m）。 */
    static final double BAND = CoastalAssembler.BAND;

    /** 距离场分辨率（块/格）。 */
    static final double DIST_CELL = CoastalAssembler.DIST_CELL;

    // ============================================================ 状态

    /** 陆地层：{@link FullMorphLand}（用户定稿的形态过渡）。 */
    private final FullMorphLand morph;
    private final CoastRecipeCatalog coast;
    private final OceanRecipeCatalog ocean;
    private final RefNoise.Node cNode;

    /** 海岸类型选择器：低频（1500 块 ≈ 一段海岸线），保证同段海岸类型一致。 */
    private final RefNoise.Node coastSel;

    /**
     * 陆/海类型的抽样选择器（{@code [0,1)}）。
     *
     * <p>与 {@link #seaTypeProbability} 比较决定该点是海型还是陆型。
     * 必须<b>相干</b>（低频），否则类型会变成椒盐斑点。</p>
     * <p>波长取 2600 块：比海岸选择器长，保证"成片的海、成片的陆"，
     * 而海岸类型可以在同一段岸线上切换。</p>
     */
    private final RefNoise.Node selectorNode;

    /**
     * <b>基于 baseE 的距离场</b>（米制，块）。
     *
     * <h3>★ 为什么必须预建，而不是每次局部搜索</h3>
     * <p>首版用 8 方向射线在 {@code e()} 内部搜索水边线，有两个问题：
     * ① 方向量化（8 方向 ⇒ 16 向星芒伪影，实测过）；
     * ② 每点 48 次 {@code baseE} 求值 ⇒ 1401² 全图跑 250 s。</p>
     * <p>预建一次欧氏距离场：O(n²) 建场、O(1) 查询，且标量场天然光滑。</p>
     *
     * <h3>★ 建场的"分类源"是 baseE，不是最终 e</h3>
     * <p>若用最终 {@code e}（含海岸覆盖）建场，会形成自引用（海岸依赖 d、d 依赖海岸）。
     * 所以先用 {@code baseE}（不含海岸）定水边线，再把海岸剖面套在它两侧。</p>
     */
    private final CoastalAssembler.DistField distField;
    private final CoastalAssembler.LandGrid baseGrid;

    /** 本轮 A/B 对照臂。默认 {@link Arm#BOTH}（两处缺陷都修）。 */
    private final Arm arm;

    /**
     * <b>陆地层：{@link FullMorphLand}</b>（用户 2026-10-31 定稿的形态过渡）。
     *
     * <h3>★ 为什么必须是它，而不是 {@link CombineCandidate}</h3>
     * <p>两者换算口径相同（{@code max(GROUND_E + h·E_SPAN, 0)}），但<b>机制不同</b>：</p>
     * <pre>
     *   CombineCandidate（旧层，90653f4 之前）
     *     · 45 票槽 → 选中【一个族】→ 边界回落到公共 border
     *     · 交界处是"族 ↔ 公共 border"的混合
     *
     *   FullMorphLand（新层，9b6e937 提交 = 用户定稿）
     *     · 18 条目权重 → RecipeJunctionMorph 的【节点参数渐变】
     *     · amplitude / bias / 域扭曲在交界带连续渐变（±120 块）
     *     · 四层实验成果（单条双族 → 十组 → 真实边界三向 → 18 配方完整沙箱）
     * </pre>
     * <p>用户明确要求"复用已定稿的形态过渡"。所以本类<b>只</b>用 {@code morph.e()}。</p>
     */
    UnifiedWorld(FullMorphLand morph, CoastRecipeCatalog coast, OceanRecipeCatalog ocean,
                 long seed) {
        this(morph, coast, ocean, seed, Arm.BOTH);
    }

    UnifiedWorld(FullMorphLand morph, CoastRecipeCatalog coast, OceanRecipeCatalog ocean,
                 long seed, Arm arm) {
        this.arm = arm;
        this.morph = morph;
        this.coast = coast;
        this.ocean = ocean;
        // c 场：6 octave FBM，与生产 ContinentField 同参数（scale 4000）
        this.cNode = RefNoise.Perlin.ofScale(
                (int) (seed ^ (seed >>> 32)) + 7000, 4000f, 6);
        this.coastSel = RefNoise.Perlin.ofScale(
                (int) (seed ^ (seed >>> 32)) + 7300, 1500f, 2);
        this.selectorNode = RefNoise.Perlin.ofScale(
                (int) (seed ^ (seed >>> 32)) + 7400, 2600f, 3);

        // 建 baseE 网格 → 距离场
        int gn = (int) Math.ceil(SIZE * STEP / DIST_CELL) + 4;
        double gx0 = CX - SIZE / 2.0 * STEP - 2 * DIST_CELL;
        double gz0 = CZ - SIZE / 2.0 * STEP - 2 * DIST_CELL;
        this.baseGrid = new CoastalAssembler.LandGrid(gn, gx0, gz0, DIST_CELL);
        for (int j = 0; j < gn; j++) {
            for (int i = 0; i < gn; i++) {
                double px = gx0 + i * DIST_CELL;
                double pz = gz0 + j * DIST_CELL;
                baseGrid.e[j * gn + i] = baseE(px, pz);
            }
        }
        this.distField = CoastalAssembler.buildDistField(baseGrid);
    }

    /** 大陆性 c ∈ [-1,1]（负 = 倾向海洋）。 */
    double c(double x, double z) {
        return cNode.compute((float) x, (float) z) * 2.0 - 1.0;
    }

    /**
     * 选型概率：{@code c} 只决定"这片有多大概率落到海型配方"，<b>不参与高度计算</b>。
     *
     * <h3>★ 与"用 c 当分界线"的区别（关键）</h3>
     * <pre>
     * ❌ 禁止（首版）：
     *      e = landH·(1−w(c)) + seaH·w(c)
     *    ⇒ 高度本身是 c 的函数 ⇒ 水边线 = c 的等值线
     *
     * ✅ 本实现：
     *      按概率 p(c) 选出【离散类型】→ 高度 = 该配方自己的输出
     *    ⇒ c 只影响"选中谁"，高度由配方决定 ⇒ 水边线 = 配方高度跨 0 的位置
     * </pre>
     * <p>与 {@code TerrainCharacterField.getCellType} 的做法同构：
     * 那里也是用 {@code oceanCellProbability(c)} 做<b>类型抽样</b>，
     * 之后高度完全由类型决定。</p>
     */
    double seaTypeProbability(double c) {
        double t = (-c) / OCEAN_C;
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3 - 2 * t);
    }

    /**
     * 类型选择器：低频相干场，{@code [0,1)}。
     *
     * <p>它决定"这一片在地图上落在哪个位置"，与 c 配合完成类型抽样：
     * {@code selector < p(c)} ⇒ 海型，否则陆型。</p>
     * <p>必须<b>相干</b>（低频）而非逐点随机，否则类型会变成椒盐斑点。</p>
     */
    private double selector(double x, double z) {
        return selectorNode.compute((float) x, (float) z);
    }

    /**
     * 世界高度 {@code e}。
     *
     * <h3>四层，顺序不能换</h3>
     * <ol>
     *   <li><b>陆地基底</b>：{@code CombineCandidate.height01} —— 已含 45 票槽 + 240 块过渡。</li>
     *   <li><b>海洋配方</b>：负高度（水下）。</li>
     *   <li><b>陆海混合</b>：按 {@code c} 的权重线性混合。</li>
     *   <li><b>海岸覆盖</b>：只在 {@code |d| < BAND} 的带内生效，其中
     *       {@code d} 由混合高度 {@code <0} 的区域算出。</li>
     * </ol>
     */
    double e(double x, double z) {
        // ★ 类型抽样：c 只决定概率，选出的离散类型决定高度（c 不参与高度计算）
        boolean isSea = selector(x, z) < seaProbability(x, z);

        // ① 该点的类型高度
        double typeE = isSea ? seaHeight(x, z) : landHeight(x, z);

        // ② 交界带：|d| < band 时由海岸剖面接管（陆侧与水下侧都有）
        //    ★ band 随背后的陆地高度增长（见 bandAt 的说明）
        double band = bandAt(x, z);
        double d = distField.dSigned(x, z, baseGrid.at(x, z));
        if (Math.abs(d) >= band) {
            return typeE;                      // 远离交界：纯类型高度
        }
        return coastBand(x, z, d, band, landHeight(x, z), seaHeight(x, z));
    }

    /**
     * 该点的海岸带宽度（块）。
     *
     * <h3>★ 为什么要随陆地高度增长（2026-11-01 第七次修正）</h3>
     * <p>首版用固定 {@code BAND=80} 块。实测剖面图上，滨海陆地高 0.6 e（141 格）时，
     * 海岸带要在 80 块内从 0 升到 141 格 ⇒ 坡度 <b>1.76:1（约 60°）</b>
     * ⇒ 渲染成色带后形成一圈"白色悬崖"环（用户指出的那个问题）。</p>
     *
     * <h3>物理依据（沿用已移植的 C2 概念）</h3>
     * <p>{@link OceanMorphConcepts#shoreWidthFactor} 原本就是为此设计的：
     * <b>"山越高、岸坡越长，但坡度的增长慢于高度"</b>
     * （300 格的山只比 120 格的陡 1.6 倍，而不是 2.5 倍）——
     * 地理上就是"高山海岸后退、由麓坡消化高差"。</p>
     * <pre>
     *   landB =  20 格 ⇒ band ≈  80 块（原始宽度）
     *   landB = 110 格 ⇒ band ≈ 116 块（1.45 倍，封顶）
     *   landB = 300 格 ⇒ band ≈ 116 块（不再增长）
     * </pre>
     * <p>封顶在 {@code RETREAT_GAIN=0.45} 是旧链的既有标定：曾取 0.90，
     * 实测"高地海岸后退太远、从内陆搬走太多高度"（4.2% 像素被压低超 20 格），
     * 收到 0.45 后收敛。此处沿用，不重新调参。</p>
     */
    private double bandAt(double x, double z) {
        // 用"该点是否为海"决定取哪一侧的锚定高度
        boolean isSea = selector(x, z) < seaProbability(x, z);
        // 陆侧看陆高（e → 格：陆侧 e∈[0,1] 映射到 [63, 320]，即 257 格/e）
        // 海侧没有"背后陆地"，用 0 ⇒ 宽度保持基础值
        double anchorBlocks = isSea ? 0.0 : landHeight(x, z) * BLOCKS_PER_E_LAND;
        double factor = OceanMorphConcepts.shoreWidthFactor(anchorBlocks);
        return BAND * factor;
    }

    /** 陆侧 e → 格的换算（{@code HeightCurve}: e∈[0,1] → [seaLevel, maxY] = 257 格/e）。 */
    static final double BLOCKS_PER_E_LAND = 320.0 - 63.0;

    /** 陆型高度（= {@link FullMorphLand#e}，已含 18 条目的节点参数渐变与 ±120 块过渡）。 */
    private double landHeight(double x, double z) {
        return morph.e(x, z);
    }

    /** 诊断：该点是否被抽样为海型。 */
    boolean isSea(double x, double z) {
        return selector(x, z) < seaTypeProbability(c(x, z));
    }

    /**
     * 该点的"海型概率"（诊断用；等于 {@code seaTypeProbability(c(x,z))}）。
     *
     * <p>抽出来是为了让 {@link #e} 与 {@link #bandAt} 用<b>同一次</b>判定，
     * 避免两处各调一次 {@code c()} 造成潜在不一致。</p>
     */
    double seaProbability(double x, double z) {
        return seaTypeProbability(c(x, z));
    }

    /** 诊断：{@link #baseE} 的公开包装（供探针读取，不影响产出）。 */
    double baseEof(double x, double z) {
        return baseE(x, z);
    }

    /** 海型高度（负值，来自 {@link OceanRecipeCatalog}）。 */
    private double seaHeight(double x, double z) {
        var or = pickOcean(c(x, z));
        return CombineCandidate.seaE(ocean.height01(or, x, z));
    }

    /**
     * 交界带内的海岸剖面（陆侧 + 水下侧）。
     *
     * <h3>陆侧 {@code d ∈ [0, BAND)}</h3>
     * <pre>
     *   t = d / BAND   （0 = 水边、1 = 内陆）
     *   高度 = 海岸剖面(t)，锚定 eLand
     *   t=1 处严格等于 landH ⇒ 与带外连续
     * </pre>
     *
     * <h3>水下侧 {@code d ∈ (−BAND, 0)}</h3>
     * <pre>
     *   t = |d| / BAND  （0 = 水边、1 = 远海）
     *   高度 = 水边高程 → 岸坡 → 海型高度，锚定 eSea
     *   t=1 处严格等于 seaH ⇒ 与带外连续
     * </pre>
     * <p>两侧在 {@code d=0} 处都取同一个"水边高程" ⇒ 严格连续，无断崖。</p>
     */
    private double coastBand(double x, double z, double d, double band,
                             double landH, double seaH) {
        var cr = pickCoast(x, z);
        double t = Math.abs(d) / band;
        double shore = CoastRecipeCatalog.waterEdgeE(cr);

        if (d >= 0) {
            // 陆侧：水边 → 内陆
            double h = coast.height01(cr, x, z, t, landH);
            double k = smoothstep(0.85, 1.0, t);
            return h * (1.0 - k) + landH * k;
        }
        // 水下侧：水边 → 岸坡 → 海型（shoreface 形态：贴水边较陡、之后变缓）
        double s = smoothstep(0.06, 1.0, t);
        double h = shore * (1.0 - s) + seaH * s;
        double k = smoothstep(0.85, 1.0, t);
        return h * (1.0 - k) + seaH * k;
    }

    /**
     * <b>类型高度</b>（不含海岸覆盖）—— 距离场的"分类源"。
     *
     * <h3>★ 为什么距离场建在它上面</h3>
     * <p>海岸剖面依赖 {@code d}，若 {@code d} 又依赖海岸剖面，就形成自引用。
     * 所以先用"纯类型高度"定水边线（{@code e=0} 的位置），再把海岸剖面
     * 套在它两侧。水边线的定义因此是<b>类型高度跨 0</b>，
     * 与用户定稿的"海陆看地形高度变化"一致。</p>
     *
     * <h3>★ c 在这里只做类型抽样，不参与高度</h3>
     * <p>{@link #seaTypeProbability} 决定"这片有多大概率是海型"，
     * 具体选中谁由 {@link #selector} 决定；选中后高度<b>完全</b>由该类型配方给出。
     * 所以水边线是"配方高度跨 0 的位置"，<b>不是 c 的等值线</b>。</p>
     */
    private double baseE(double x, double z) {
        boolean isSea = selector(x, z) < seaTypeProbability(c(x, z));
        return isSea ? seaHeight(x, z) : landHeight(x, z);
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    // ============================================================ 类型选择

    /**
     * 由 c 选海洋配方。
     *
     * <h3>★ 映射区间必须覆盖<b>整个 c&lt;0 区间</b>（2026-11-01 第二次修正）</h3>
     * <p>首版：{@code t = (−c − 0.55)/0.45} ⇒ 实测 c 的 P1=−0.556 ⇒ t 最大 0.013
     * ⇒ 恒选 ABYSSAL_PLAIN（eSea 的 P1..P99 只有 0.03 跨度）。</p>
     * <p>第二版：{@code t = (−c − 0.35)/0.35} ⇒ 区间对应 c ∈ [−0.35, −0.70]。
     * 但实测 <b>c 的中位数是 +0.036</b>、P10=−0.388 ⇒ 绝大多数"海型"点的 c
     * 落在 {@code (−0.35, 0)} 这一段（t 被 clamp 到 0）⇒ <b>仍然恒选第 0 个</b>。</p>
     * <p>第三版：{@code t = (−c) / OCEAN_C}，把 {@code c ∈ [0, −OCEAN_C]} 完整映射到 {@code [0,1]}。</p>
     *
     * <h3>★ 但方向反了（2026-11-01 审计发现，{@link Arm#BASELINE} 复现）</h3>
     * <p>{@link OceanRecipeCatalog.Recipe} 的枚举顺序是<b>从深到浅</b>：</p>
     * <pre>
     *   [0] ABYSSAL_PLAIN     depth [−0.42, −0.30]   最深
     *   [1] MID_OCEAN_RIDGE   depth [−0.42, −0.20]
     *   [2] SEAMOUNT_FIELD    depth [−0.42, −0.10]
     *   [3] CANYON            depth [−0.55, −0.30]
     *   [4] SHELF_RIDGE       depth [−0.10,  0.00]   最浅
     * </pre>
     * <p>而 {@code t=0} 的含义是"c 刚变负 ⇒ 刚从陆地入海 ⇒ 应当是<b>浅</b>海"。
     * 直接 {@code round(t·4)} 会把 {@code t=0} 映射到 index 0 = <b>深海平原</b>，
     * {@code t=1}（最深）映射到 index 4 = <b>陆架沙脊</b> —— <b>正好倒过来</b>：
     * 浅水处被灌成 40 格深的深海平原、深海处被抬成贴着海面的沙脊。</p>
     * <p>修法：{@code t} 表示"深度比例"，索引取 {@code (1−t)} 方向。</p>
     */
    OceanRecipeCatalog.Recipe pickOcean(double cc) {
        OceanRecipeCatalog.Recipe[] all = OceanRecipeCatalog.Recipe.values();
        // c ∈ [0, −OCEAN_C] → t ∈ [0,1]；t = 0 最浅、t = 1 最深
        double t = RefNoise.clamp((float) ((-cc) / OCEAN_C), 0f, 1f);
        // ★ BASELINE 复刻旧行为（round(t·n) ⇒ t=0 选到最深那条），修复后按 (1−t) 取索引
        double pick = arm.fixOceanDir ? (1.0 - t) : t;
        int idx = (int) Math.round(pick * (all.length - 1));
        return all[Math.max(0, Math.min(all.length - 1, idx))];
    }

    /**
     * 由位置选海岸配方（成片，不椒盐）。
     *
     * <h3>★ 均分区间不等于均分面积（2026-11-01 审计发现）</h3>
     * <p>旧实现 {@code idx = floor(n · 5)}，看着是"五等分"，但 {@code RefNoise.Perlin}
     * 的输出<b>集中在 0.5 附近</b>（P10≈0.35 / P90≈0.65）⇒ 中间那一档吃掉大半。
     * 同一扇 1401² 窗口实测占比（{@code SeaMorphAudit} 复现）：</p>
     * <pre>
     *   REEF_FLAT    58.49%     ← 中间档
     *   SEA_CLIFF    22.72%
     *   TIDAL_FLAT   18.58%
     *   DELTA         0.15%
     *   SANDY_BEACH   0.07%     ← 几乎不出现
     * </pre>
     * <p>这就是"礁盘水太多、陆地被压薄"的来源：<b>不是礁盘配方本身的问题</b>，
     * 而是选型分布把 58% 的海岸给了它。</p>
     *
     * <h3>修法：先做对比度拉伸，再按声明权重切分</h3>
     * <p>{@link CoastRecipeCatalog#weight} 早就声明了票槽权重
     * （沙滩 4 / 崖岸 3 / 潮滩 2 / 礁盘 2 / 三角洲 1），但<b>从未被消费</b>
     * —— 这正是这次要接上的东西。权重总和 12，按累积比例切分
     * {@code [0,1]} 后，各类型占比 ≈ 声明权重占比。</p>
     * <p>拉伸窗口取 {@code [0.30, 0.70]}（与 {@code OceanTransitionCatalog} 的
     * {@code ALPHA_LO/HI} 同口径实测值），把中间 80% 铺满值域，
     * 两端自然饱和成"大片连续的同类海岸"，正好符合"同一段岸线类型一致"的要求。</p>
     */
    CoastRecipeCatalog.Recipe pickCoast(double x, double z) {
        CoastRecipeCatalog.Recipe[] all = CoastRecipeCatalog.Recipe.values();
        double n = coastSel.compute((float) x, (float) z);   // [0,1]，集中在中部
        if (!arm.fixCoastDist) {
            int idx = (int) Math.floor(n * all.length);
            return all[Math.max(0, Math.min(all.length - 1, idx))];
        }
        // ① 对比度拉伸：[0.30, 0.70] → [0, 1]
        double u = RefNoise.clamp((float) ((n - COAST_SEL_LO) / (COAST_SEL_HI - COAST_SEL_LO)),
                0f, 1f);
        // ② 按声明权重累积切分
        double total = 0;
        for (CoastRecipeCatalog.Recipe r : all) total += CoastRecipeCatalog.weight(r);
        double acc = 0;
        double target = u * total;
        for (CoastRecipeCatalog.Recipe r : all) {
            acc += CoastRecipeCatalog.weight(r);
            if (target < acc) return r;
        }
        return all[all.length - 1];
    }

    /** 海岸选择器的对比度拉伸窗口（实测 Perlin 2 oct 的 P10≈0.35 / P90≈0.65）。 */
    static final double COAST_SEL_LO = 0.30;
    static final double COAST_SEL_HI = 0.70;

    // ============================================================ 出图

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/unified-world");
        Arm arm = args.length > 1 ? Arm.valueOf(args[1]) : Arm.BOTH;
        Files.createDirectories(out);

        TerrainParams tp = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(tp, -64, 320);

        // ★ 陆地层用 FullMorphLand（用户 2026-10-31 定稿的 18 条目节点参数渐变）
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        FullMorphLand morph = new FullMorphLand(cat);
        CoastRecipeCatalog coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, true);
        UnifiedWorld world = new UnifiedWorld(morph, coast, ocean, SEED, arm);

        Path p = out.resolve("Y-world.f64be");
        Path po = out.resolve("ocean-recipe.u8");
        Path pc = out.resolve("coast-recipe.u8");
        int water = 0, n = 0;
        double minE = 1, maxE = -1;
        int[] oceanCount = new int[OceanRecipeCatalog.Recipe.values().length];
        int[] oceanCountSea = new int[OceanRecipeCatalog.Recipe.values().length];
        int[] coastCount = new int[CoastRecipeCatalog.Recipe.values().length];
        int seaN = 0;
        try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(p), 1 << 16));
             DataOutputStream oo = new DataOutputStream(new BufferedOutputStream(
                     Files.newOutputStream(po), 1 << 16));
             DataOutputStream oc = new DataOutputStream(new BufferedOutputStream(
                     Files.newOutputStream(pc), 1 << 16))) {
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = CX + (i - SIZE / 2.0) * STEP;
                    double z = CZ + (j - SIZE / 2.0) * STEP;
                    double ev = world.e(x, z);
                    n++;
                    if (ev < 0) water++;
                    minE = Math.min(minE, ev);
                    maxE = Math.max(maxE, ev);
                    os.writeDouble(curve.heightFromE(ev));

                    // 类型索引（诊断用：判断选型分布，不参与高度）
                    // ⚠ 海型配方只在【真的是海型】的点上才有意义 —— 全窗口占比会被陆地面积稀释
                    boolean isSea = ev < 0;
                    var or = world.pickOcean(world.c(x, z));
                    var cr = world.pickCoast(x, z);
                    oo.writeByte(or.id);
                    oc.writeByte(cr.id);
                    if (isSea) {
                        oceanCountSea[or.id]++;
                        seaN++;
                    }
                    oceanCount[or.id]++;
                    coastCount[cr.id]++;
                }
                if (j % 100 == 0) System.out.println("row=" + j);
            }
        }
        System.out.printf(Locale.ROOT,
                "UNIFIED WORLD PASS arm=%s water=%.1f%% e[%.3f,%.3f] Y[%.0f,%.0f]%n",
                arm, 100.0 * water / n, minE, maxE,
                curve.heightFromE(minE), curve.heightFromE(maxE));

        // 选型分布（诊断；与 SeaMorphAudit 的统计口径一致）
        OceanRecipeCatalog.Recipe[] allO = OceanRecipeCatalog.Recipe.values();
        CoastRecipeCatalog.Recipe[] allC = CoastRecipeCatalog.Recipe.values();
        System.out.println("  --- ocean recipe share ---");
        System.out.printf(Locale.ROOT,
                "    %-18s %10s %12s %12s%n", "recipe", "全窗口%", "海域内%", "该型中位Y");
        for (int k = 0; k < allO.length; k++) {
            System.out.printf(Locale.ROOT, "    %-18s %9.2f%% %11.2f%%%n",
                    allO[k], 100.0 * oceanCount[k] / n,
                    seaN == 0 ? 0.0 : 100.0 * oceanCountSea[k] / seaN);
        }
        System.out.println("  --- coast recipe share ---");
        for (int k = 0; k < allC.length; k++) {
            System.out.printf(Locale.ROOT, "    %-18s %9.2f%%%n",
                    allC[k], 100.0 * coastCount[k] / n);
        }

        Files.writeString(out.resolve("meta.json"), String.format(Locale.ROOT,
                "{\"seed\":\"%d\",\"size\":%d,\"step\":%d,\"center\":[%.0f,%.0f],"
                        + "\"sampleOrigin\":[%.1f,%.1f],\"band\":%.0f,\"arm\":\"%s\","
                        + "\"jvmProperties\":[\"file.encoding=UTF-8\"]}%n",
                SEED, SIZE, STEP, CX, CZ, CX - SIZE / 2.0 * STEP, CZ - SIZE / 2.0 * STEP,
                BAND, arm));
    }

    private UnifiedWorld() { throw new AssertionError(); }
}
