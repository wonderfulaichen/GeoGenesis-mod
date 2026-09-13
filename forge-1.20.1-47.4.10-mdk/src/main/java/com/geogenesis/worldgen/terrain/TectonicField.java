package com.geogenesis.worldgen.terrain;

/**
 * 构造骨架场（★ 2026-09-12，地质系统 Phase T1）。
 *
 * <p>移植自参考项目 <b>worldgen</b>（vnovak404/worldgen，MIT）的板块构造思想，
 * 核心是"<b>高程由地质边界驱动，而非由噪声驱动</b>"：
 * <pre>
 *   汇聚边界 → 造山（陆-陆）/ 海沟+火山弧（洋-陆、洋-洋）
 *   离散边界 → 洋中脊（洋） / 裂谷（陆）
 *   走滑边界 → 无垂向贡献（仅水平错动）
 * </pre>
 *
 * <h3>与 worldgen 的差异（无限平面世界适配）</h3>
 * <p>worldgen 面向<b>球面/柱面</b>，用全局 Dijkstra 生长板块，且需要全图一次性生成。
 * 本项目是<b>无限平面世界</b>，无法做全局生长（也不该——会破坏分块与缓存）。
 * 因此改用与 {@link TerrainCharacterField} 同构的<b>确定性哈希 Voronoi</b>：
 * <ul>
 *   <li>板块种子 = 网格 cell 中心 + 哈希抖动（{@link #PLATE_SPACING}）</li>
 *   <li>板块速度 = 哈希生成的方向与大小</li>
 *   <li>边界由 F1/F2（最近/次近种子）的中垂线确定 —— 与 Dijkstra 生长在<b>拓扑上等价</b>
 *       （都是"每点归属于唯一板块，边界为板块间分界"），只是边界形状是折线而非噪声轮廓。
 *       后续可加域扭曲让边界变有机（见 {@link #WARP_AMP}）。</li>
 * </ul>
 *
 * <h3>约束遵循</h3>
 * <ul>
 *   <li><b>零依赖纯函数</b>：不 import 任何 Minecraft / Forge 类（与 climate 包一致）</li>
 *   <li><b>确定性</b>：同 (seed,x,z) 恒得同结果</li>
 *   <li><b>廉价</b>：每次采样仅 3×3=9 次哈希 + 距离（纳秒级），
 *       远低于 {@code terrainEQuick}（微秒级），<b>不触发侵蚀 tile</b></li>
 * </ul>
 */
public final class TectonicField {

    // ===== 边界类型（与 worldgen src/plates/boundary.rs 一致） =====
    public static final int INTERIOR = 0;
    public static final int CONVERGENT = 1;
    public static final int DIVERGENT = 2;
    public static final int TRANSFORM = 3;

    /** 板块种子网格间距（wu）。应远大于地形类型尺度（400），以形成大尺度构造单元。 */
    private static final double PLATE_SPACING = 2000.0;
    /** 种子在 cell 内的抖动比例（0=规则网格，1=完全随机）。打散规则感。 */
    private static final double SEED_JITTER = 0.7;
    /** 邻域搜索半径：1 → 3×3 窗口（足够确定 Voronoi 归属与 F1/F2）。 */
    private static final int SEARCH_RADIUS = 1;

    // ===== 域扭曲（让 Voronoi 直线边界变有机）=====
    /**
     * 域扭曲幅度（wu）。
     *
     * <p>Voronoi 边界本质是<b>中垂线（直线，长约 2000wu）</b>，直接用会让板块格子呈
     * <b>笔直多边形</b>（用户多次反馈"平直边界 / 不自然断裂线"）。对查询点做<b>连续</b>
     * 域扭曲后边界变为有机曲线，且不影响 dist 的连续性。</p>
     *
     * <p>★ 2026-09-12 第五次修复：<b>幅度与频率须配套</b>。原取
     * {@code AMP=320 / 波长1600wu}——波长与界线长度同量级（2000wu）⇒ 在局部
     * 400wu 视野内边界仍<b>几乎是直线</b>（曲率半径 ≫ 视野）⇒ 用户实机仍看到
     * "笔直断裂线"（实测 {@code contours_1block.png} 的直线与 {@code boundaries.png}
     * 中那条红色板块边界方向完全一致）。</p>
     *
     * <p>现改为<b>短波长、低幅度</b>：波长 ≈ 界线长度的 1/5 ⇒ 一条界线内有 ~5 个弯折，
     * 任何 100~400wu 的视野里都呈明显波浪 ⇒ 不再有"笔直"观感；
     * 幅度取 130wu（板块间距的 6.5%）⇒ 板块形状不被破坏。</p>
     */
    private static final double WARP_AMP = 130.0;
    /** 域扭曲频率（1/wu）：波长 ~400wu（约为板块间距的 1/5，保证界线在任一局部视野内已弯曲）。 */
    private static final double WARP_FREQ = 1.0 / 400.0;
    private static final long SALT_WARP = 0x2C6E_F1A3_84BD_9075L;

    /**
     * 法向差分步长（wu）。
     *
     * <p>取 45wu 而非小值：dist 场在 Voronoi 边界附近是<b>分段线性</b>的（±|t|），
     * 步长过小会因分段折角造成方向抖动；45wu 足以跨过折角、给出稳定方向。</p>
     */
    private static final double GRAD_EPS = 45.0;

    /**
     * stress 局部平滑的作用距离（wu）：超出则用原始值（不做 5 点平均）。
     * 取 ≈ BOUNDARY_REACH 的一半即可覆盖全部边界影响区。
     */
    private static final double STRESS_SMOOTH_REACH = 600.0;
    /** stress 平滑的十字采样步长（wu）：须跨越"配对不确定性"的尺度（约几十 wu）。 */
    private static final double STRESS_SMOOTH_EPS = 55.0;

    /**
     * <b>btype 标签</b>的影响宽度（wu）：超出此距离标为 {@link #INTERIOR}。
     *
     * <p>★ 2026-09-12 定案：此常量<b>只约束离散标签 btype/rate</b>，
     * <b>不再约束 dist 与 stress</b>（二者已改为全局连续可用）。
     * 这是解决"硬截断伪影"的正确方式：
     * <ul>
     *   <li>此前把本值由 320 扩到 1000 是为了让 T5 的 stress 在 900wu 内非零，
     *       但副作用是<b>板块间距仅 2000</b> → 几乎所有点 dist&lt;1000 →
     *       "内部"分类消失（探针实测 INTERIOR=0%）。</li>
     *   <li>现改为：dist/stress <b>不再截断</b>（T5 的 decay 自行平滑收敛），
     *       本值退回 320 —— 恰好覆盖 T1（σ=110 高斯，320wu 处已衰减到 1.4%）
     *       与岩性（近边界才需区分）的实际需求。</li>
     * </ul>
     */
    private static final double BOUNDARY_REACH = 320.0;
    /**
     * 构造对地形类型权重的调制强度（汇聚造山 / 海沟，离散裂谷 / 洋脊）。
     *
     * <p>★ 取值由实测标定：
     * <ul>
     *   <li>1.2：造山带比值仅 1.39×（未达 1.5× 判据），山脉成带不明显。</li>
     *   <li>关键实测：<b>水文稀释与 BOOST 几乎无关</b>——BOOST 2.0 与 1.2 下
     *       Phase C 的 head 最湿桶均为 ~1.047。说明稀释源于"构造系统存在"本身
     *       （改变了地形分布），而非调制强度 → <b>可以放心加大强度换取清晰造山带</b>。</li>
     *   <li>2.5 / 1.8：造山带比值达 ~1.9×，且水文影响与 1.2 时相当。</li>
     * </ul>
     */
    public static final double CONVERGENT_BOOST = 2.5;
    public static final double DIVERGENT_BOOST = 1.8;
    /** 高斯衰减 sigma（wu）：控制山脉/海沟的宽度。 */
    private static final double PROFILE_SIGMA = 110.0;

    // ===== Phase T2：Chain modulation（山链串珠化，沿走向打破均匀脊）=====
    /**
     * Chain 噪声的特征尺度（wu）。
     *
     * <p>★ 2026-09-12 第三次回归后定案（<b>硬约束</b>）：<b>禁止用 dist 作噪声坐标</b>。
     * 原实现第二坐标为 {@code dist/PLATE_SPACING*CHAIN_ACROSS_FREQ = dist/111}
     * → 噪声沿"跨边界"方向每 <b>111wu</b> 完成一个周期
     * → 每条边界两侧出现<b>平行于边界的同心波纹</b>（用户三次反馈的"平行带"，
     * 实测周期 111wu 与 screenshot 窄带内纹理完全一致）。
     * 这与项目早已否决的 Terrace「环状台阶伪影」同源，故 dist 仅允许用于 decay。</p>
     *
     * <p>取值：造山带宽度由 {@link #PROFILE_SIGMA}(110wu) 决定，故本尺度须远大于 110。
     * 取 900wu 使带内一次穿越仅覆盖 ~0.12 个噪声格 → 跨走向近似单调（无波纹），
     * 而沿走向仍可容纳多个山包 → 保留"串珠状独立山峰"的设计意图。</p>
     */
    private static final double CHAIN_SCALE = 900.0;
    /** Chain 调制下限（保留的最小强度比例）。 */
    private static final double CHAIN_MIN = 0.35;
    /** Chain 噪声盐。 */
    private static final long CHAIN_SEED = 0x51ED270B7A1C3E5FL;

    // ===== 高程偏置幅度（e 单位，[-1,1]；海平面 e=0） =====
    /**
     * 陆-陆汇聚造山幅度（e 单位）。
     *
     * <p>★ 取值教训：初版 0.13 过于激进——它抬升地形后<b>连锁增强地形雨</b>
     * （PrecipField 的 orographicGain 基于高度差），使降水全局均值 0.347→0.413，
     * 干旱区也随之变湿，稀释了 Phase C「干旱区河细」的区分度
     * （runPrecipRiverWidthProbe 的 head 最干桶 0.933→0.959，越过 0.95 阈值）。
     * 0.06 在"能看出造山带"与"不破坏既有水文/气候标定"之间取得平衡。
     * 实测：0.13 → head 最干桶 0.959（稀释 47%）；0.09 → 0.959；需 0.06 复测。</p>
     */
    private static final double MOUNTAIN_GAIN = 0.06;
    /** 洋侧俯冲海沟深度。 */
    private static final double TRENCH_DEPTH = 0.09;
    /** 陆内裂谷深度。 */
    private static final double RIFT_DEPTH = 0.05;
    /** 洋洋离散洋中脊高度。 */
    private static final double RIDGE_HEIGHT = 0.045;

    // ===== ★ 2026-09-13 Phase T6：海洋侧构造地形（海沟窄槽 + 火山弧）=====
    //
    //   【为何需要】T1 用【类型权重调制】表达海洋侧构造，只能给出<b>宽带深度趋势</b>
    //   （MOUNTAINS/DEEP_OCEAN 权重 × BOOST，宽度由 σ=110 高斯支配）。
    //   而参考项目 worldgen 的 boundary_profile 在海洋侧给出的是<b>中尺度特征形态</b>：
    //     · 俯冲侧海沟 = 窄而深的槽（gaussian(dist, 12)）
    //     · 洋-洋汇聚  = 离轴火山弧（gaussian(dist−35, 18)）
    //   两者<b>几何位置不同</b>（槽在线、弧在离轴 ~210wu），无法靠权重调制表达 ⇒ 需显式偏移。
    //
    //   【为何不会污染气候标定】T1 当初弃用高程偏移改权重调制，是因为<b>陆地</b>抬升
    //   会连锁增强地形雨（PrecipField.orographicGain 基于高度差），推高全局降水。
    //   而本函数的两个分量<b>全在水下</b>：海平面恒为 e=0，水下雨影无从产生
    //   （cell.e < 0 时 PrecipField 的迎风坡抬升无意义）⇒ 标定安全。
    //   陆侧造山/裂谷<b>仍归 T1 的权重调制</b>，本函数不碰（避免与 T1 重复）。
    /**
     * 海沟窄槽的宽度（wu）。须远窄于 PROFILE_SIGMA(110)，才能形成"槽"而非"坡"。
     *
     * <p>对齐真实地质：海沟宽 50~100 km，本项目尺度 1 wu ≈ 1.5 km
     * （PLATE_SPACING=2000wu 对应真实微板块 ~3000km）⇒ 约 33~67 wu。
     * 取 45（配合 dist 已被 blurDist 平滑）。参考项目 {@code gaussian(dist, 12px)}
     * 换算约 12~50wu，同量级。</p>
     */
    private static final double TRENCH_NOTCH_SIGMA = 45.0;
    /**
     * 海沟窄槽深度（e 单位，≈9.6 块）。
     *
     * <p><b>为何要有"槽"而不只有 T1 的宽趋势</b>：T1 把俯冲侧表达为「提升 DEEP_OCEAN 权重」，
     * 那是被 σ=110 高斯支配的<b>宽带加深</b>（缓坡）；而真实海沟是<b>紧贴边界线的窄槽</b>
     * （参考项目 {@code gaussian(dist, 12px)}）。两者叠加才构成「外隆—沟—坡」的俯冲带剖面。</p>
     *
     * <p>取值 0.05 e 而非参考项目的 -2500px（≈ -0.33 e）：本项目海域的 e 总动态范围比参考项目
     * 小（深海底约 -0.35 e），若照搬相对深度会把海床压穿世界底面。0.05 e 提供"槽"的锐利感，
     * 又不与 T1 的宽加深叠成双重深沟。</p>
     */
    private static final double TRENCH_NOTCH_DEPTH = 0.05;
    /** 火山弧的离轴距离（wu）：弧不在边界线上，而在海沟后方约 210wu 处。 */
    private static final double ARC_OFFSET = 210.0;
    /** 火山弧的半宽（wu）。 */
    private static final double ARC_SIGMA = 55.0;
    /**
     * 火山弧高度（e 单位，≈19 块）。
     *
     * <p><b>取值依据（对齐参考项目比例）</b>：参考项目洋-洋弧 = {@code 1000 × 1.5 × ms(0.6) × oro(1.5)}
     * ≈ +1350px，而其洋壳基面 -4000~-3000px ⇒ <b>弧高/深海深 ≈ 39%</b>。
     * 本项目深海底约 -0.35 e（{@code deepOceanDepth}）⇒ 同比例弧高 ≈ <b>0.14 e</b>。
     * 保守取 0.10 e（29%），仍远低于"露出水面"所需的 ~0.30 e。</p>
     *
     * <p><b>★ 弧不露头是刻意的（有证据）</b>：参考项目 {@code docs/map.png} 中海洋是连续的
     * 深浅蓝、<b>无任何裸露的岛弧链</b>；其弧顶绝对高度约 -1650m，<b>远在海平面之下</b>。
     * 故本项目的弧同样<b>只作海底隆起</b>（且在 {@code OceanFeatures} 里另用深海门控加强
     * 这一保证，见 {@code arcFade}）—— 绝不把弧写成能生成陆地的量。</p>
     */
    private static final double ARC_HEIGHT = 0.10;
    /** 海洋侧剖面的作用距离（wu）：超过则完全无贡献（远大于 ARC_OFFSET+3σ=375）。 */
    private static final double OCEAN_PROFILE_REACH = 420.0;

    // ===== 哈希盐（互不干扰） =====
    private static final long SALT_SEED = 0x9E3779B97F4A7C15L;
    private static final long SALT_VEL = 0xBF58476D1CE4E5B9L;

    private long seed;

    public TectonicField(long seed) {
        this.seed = seed;
    }

    /** 换世界种子（构造格局随种子改变；调用方在 {@code seed()} 时调用）。 */
    public void setSeed(long worldSeed) {
        this.seed = worldSeed;
    }

    // ===================== 公开工具 =====================

    /**
     * ★ 2026-09-12 第五次伪影修复：<b>平滑正部</b> {@code max(0,v)}。
     *
     * <p><b>为何必须用平滑版</b>：{@code Math.max(0, v)} 在 {@code v=0} 处<b>一阶不连续</b>
     * （斜率由 0 突变为 1）。而本项目把应力拆成「汇聚部 {@code cw}」与「离散部 {@code dw}」
     * 时到处用它 ⇒ 沿 {@code stress=0} 的等值线留下<b>折痕</b> ⇒ 等高线在折痕处挤成一条
     * <b>笔直细线</b> ⇒ 游戏里 {@code floor(height)} 后就是用户反复反馈的
     * "不自然断裂线 / 密集平行细线"（实测 {@code contours_1block.png} 的黑直线与
     * {@code comp_chain.png} 的 Voronoi 棱面完全对应）。</p>
     *
     * <p>本式 {@code f(v) = v · smoothstep(−eps, eps, v)}：
     * <ul>
     *   <li>{@code v ≥ eps} → {@code f = v}（保留全强度）</li>
     *   <li>{@code v ≤ −eps} → {@code f = 0}（完全归零）</li>
     *   <li>{@code |v| < eps} → 平滑过渡，<b>且 f(0)=0</b></li>
     * </ul>
     * <b>为何必须 f(0)=0</b>：本项目语义是"<b>纯走滑 = 无垂向形变</b>"
     * （见 {@code TectonicDeformProbe}[1]/[5]）。若用
     * {@code 0.5(v+√(v²+eps²))} 这类"只圆化不平移"的写法，{@code f(0)=eps/2≠0}
     * ⇒ 纯走滑区也会产生 ~14% 强度的形变 ⇒ 语义破坏、探针 [1] 泄漏 12 例。
     * 本式在 v=0 处恒为 0，故语义完全保留。</p>
     *
     * @param v   应力分量（−1..1）
     * @param eps 圆化半宽（应力单位）。取 {@link #STRESS_POS_EPS}
     */
    public static double smoothPos(double v, double eps) {
        double t = (v + eps) / (2.0 * eps);
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return v * (t * t * (3.0 - 2.0 * t));
    }

    /**
     * 平滑最小值 {@code smoothMin(a, b, k)}：{@code min} 的 C¹ 版本（公共工具）。
     *
     * <p>直接 {@code Math.min} 在 {@code a=b} 处一阶不连续 ⇒ 沿该等值线留下<b>折痕</b>
     * （本项目已多次踩坑，见 {@link #smoothPos}）。本式是标准多项式 smooth-min：
     * {@code |a−b| ≥ k} 时精确等于 {@code min}，只在过渡带圆化
     * ⇒ <b>不改变标定，只消除折痕</b>。</p>
     *
     * <p>用途：地形护栏（如"海洋特征不得抬出海平面"的软天花板）。
     * 放在本类是因为它已是本项目"地形数学工具"的宿主（{@code smoothPos} /
     * {@code shellFromC} / {@code boundaryStrength}），供各处复用而不重复实现。</p>
     *
     * @param k 过渡宽度（&gt;0；a,b 相差 ≫k 时退化为精确 min）
     */
    public static double smoothMin(double a, double b, double k) {
        if (a - b >= k) return b;
        if (b - a >= k) return a;
        double h = (k - Math.abs(a - b)) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }

    /**
     * 正部圆化半宽（应力单位）。
     *
     * <p>应力范围约 [−1,1]，取 0.18：过渡带约占值域 18%——足以消除折痕，
     * 又不明显改变"纯汇聚/纯离散"区的强度（{@code |stress|>0.5} 处误差 &lt;4%）。</p>
     */
    public static final double STRESS_POS_EPS = 0.18;

    // ===================== 公开 API =====================

    /** 构造采样结果。 */
    public record Sample(
            /** 到最近板块边界的距离（wu）；内部点为一个大值。 */
            double dist,
            /** 边界类型：INTERIOR / CONVERGENT / DIVERGENT / TRANSFORM。 */
            int btype,
            /** 相对速度大小（构造活动强度，无量纲，约 0~2）。 */
            double rate,
            /**
             * 边界<b>切向</b>（沿走向）单位向量的 x 分量。
             * ★ Phase T2：供 Chain modulation 把均匀脊按走向切成独立山峰。
             * 内部点为 (1,0) 占位。
             */
            double tangentX,
            /** 边界切向单位向量的 z 分量（见 {@link #tangentX}）。 */
            double tangentZ,
            /**
             * ★ 2026-09-12 新增：<b>连续应力值</b> ∈ [-1,1]（+1 纯汇聚 / -1 纯离散 / 0 走滑）。
             *
             * <p><b>为何需要</b>：{@link #btype} 是<b>离散枚举</b>，在边界类型切换处
             * <b>跳变</b>；而 T1 的权重调制与 T5 的形变都按 btype 分支（{@code switch}）
             * → 公式骤变 → 偏移跳变 → 线状疤痕（探针实测：btype 0→1 时偏移从 0 跳到 0.005e）。
             * 本字段由 {@code dot/|(dot,cross)|} 连续给出，切换处平滑过渡，
             * 调用方应<b>优先用它</b>做加权，而非按 btype 硬分支。</p>
             */
            double stress,
            /**
             * ★ 2026-09-12 新增：<b>沿走向坐标的连续代理</b> = {@code (d1+d2)/2}。
             *
             * <p><b>为何需要</b>：T2(山链串珠) / T5(褶皱相位) 需要"沿边界方向的坐标"，
             * 原实现用切向投影 {@code wx·tx + wz·tz}。但 {@code tx,tz} 在最近邻配对切换处
             * 存在微小不连续（dTan&lt;1e-4），而投影用<b>绝对世界坐标</b>（|p|~1e4）
             * → 把微小角度误差放大成 ~100wu 的坐标跳变 → 噪声值跳变 → 线状疤痕 / 伪台阶。</p>
             *
             * <p>本字段基于 Voronoi 的<b>椭圆坐标</b>：对边界上的点 d1=d2，沿中垂线移动 t 后
             * {@code d1=d2=√(dist²+t²)}，故 {@code (d1+d2)/2 = √(dist²+t²)} —— 随 t <b>单调</b>，
             * 且由<b>连续的</b> d1/d2 直接得出 → <b>处处连续</b>，且<b>零额外采样成本</b>。</p>
             */
            double alongCoord
    ) {
        /**
         * 紧凑构造器（便捷/测试用）：由 {@code btype} 推导名义应力
         * （汇聚→+1、离散→−1、走滑/内部→0）。
         *
         * <p>生产路径请用 {@link TectonicField#sample}（它给出真实的连续 stress）。</p>
         */
        public Sample(double dist, int btype, double rate, double tangentX, double tangentZ) {
            this(dist, btype, rate, tangentX, tangentZ,
                 btype == CONVERGENT ? 1.0 : (btype == DIVERGENT ? -1.0 : 0.0),
                 dist);   // 名义样本：alongCoord 取 dist（无真实配对信息）
        }

        /** 是否处于板块边界影响范围内。 */
        public boolean onBoundary() {
            return btype != INTERIOR && dist < BOUNDARY_REACH;
        }
    }

    /**
     * 采样构造骨架：确定所属板块、次近板块、到边界距离、边界类型与强度。
     *
     * <p><b>dot/cross 分解</b>（移植自 worldgen boundary.rs）：
     * 设两板块速度 {@code v1,v2}，相对速度 {@code vrel = v1-v2}，
     * 边界法线 {@code n = normalize(s2-s1)}（由板块1指向板块2），则
     * <ul>
     *   <li>{@code dot = vrel·n} —— 汇聚(dot>0) / 离散(dot<0)</li>
     *   <li>{@code cross = |vrel×n|} —— 走滑</li>
     *   <li>{@code |dot| > cross ? 汇聚/离散 : 走滑}</li>
     * </ul>
     */
    public Sample sample(double wx, double wz) {
        // ★ 2026-09-12 修复（用户反馈"岩石类型出现明显平直边界"）：
        //   Voronoi 边界本是【中垂线 = 直线】，2000wu 的板块格子被直接暴露
        //   → 岩性/构造单元的边界呈笔直多边形（方案 §3.6 早已注明"后续可加域扭曲让边界变有机"，
        //   本次补上）。对<b>查询点</b>做域扭曲（连续映射）→ 边界变有机曲线，
        //   且 dist/alongCoord 的连续性不受影响（扭曲是连续变换）。
        double wxw = wx, wzw = wz;
        if (WARP_AMP > 0.0) {
            wxw = wx + WARP_AMP * valueNoise(wx * WARP_FREQ, wz * WARP_FREQ, SALT_WARP);
            wzw = wz + WARP_AMP * valueNoise(wx * WARP_FREQ + 17.3, wz * WARP_FREQ + 31.7, SALT_WARP + 1);
        }
        int baseX = (int) Math.floor(wxw / PLATE_SPACING);
        int baseZ = (int) Math.floor(wzw / PLATE_SPACING);

        // 找最近(d1)与次近(d2)种子
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        int c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        double s1x = 0, s1z = 0, s2x = 0, s2z = 0;

        double[] sp = new double[2];
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int cx = baseX + dx, cz = baseZ + dz;
                plateSeed(cx, cz, sp);
                double ddx = wxw - sp[0], ddz = wzw - sp[1];
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) {
                    d2 = d1; c2x = c1x; c2z = c1z; s2x = s1x; s2z = s1z;
                    d1 = d; c1x = cx; c1z = cz; s1x = sp[0]; s1z = sp[1];
                } else if (d < d2) {
                    d2 = d; c2x = cx; c2z = cz; s2x = sp[0]; s2z = sp[1];
                }
            }
        }
        if (d2 == Double.MAX_VALUE) {
            return new Sample(Double.MAX_VALUE, INTERIOR, 0.0, 1.0, 0.0, 0.0, Double.MAX_VALUE);
        }

        // 到 Voronoi 边界的距离 = (d2 - d1) / 2
        //
        // ★ 2026-09-12 修复（用户反馈"明显不自然的线性疤痕"）：
        //   原式 dist = |d2²−d1²| / (2·|s1−s2|) 依赖【最近邻配对 (c1,c2)】——
        //   当配对在 Voronoi 边界的延长线上切换时，分母 |s1−s2| 骤变
        //   → dist 跳变（探针实测最大 668wu ≈ 7000 块！62% 的点受影响）。
        //   跳变轨迹是线状的 → 表现为【笔直线性疤痕】+ T5 的 floor(dist) 伪断层崖。
        //
        //   改用 (d2−d1)/2：这是"到 Voronoi 边界距离"的标准定义，且
        //   d1=min 与 d2=second-min 都是【连续函数】（min 连续；second-min
        //   在两函数交叉处取值相等故亦连续）→ 距离场处处连续。
        //   实测最大跳变 668.72wu → 1.00wu（小 600 倍，≈ 0.6 块，不可见）。
        double nx = s2x - s1x, nz = s2z - s1z;
        double len = Math.sqrt(nx * nx + nz * nz);
        double dist;
        double ux, uz, tx, tz;
        if (d2 == Double.MAX_VALUE) {
            dist = Double.MAX_VALUE;
            ux = 1.0; uz = 0.0; tx = 0.0; tz = 1.0;
        } else {
            // ★★★ 2026-09-12 第六次伪影修复（Voronoi 顶点折痕/扇形射线）★★★
            //   对齐参考项目 worldgen src/elevation.rs 的 Phase 2：
            //     // Smooth profiles to eliminate Voronoi ridge discontinuities.
            //     blur_grid(&mut profile_off, blur_sigma);  blur_grid(&mut mt_amp, blur_sigma);
            //   它同样使用 dist，但**对推导出的边界量做高斯模糊**以消除折痕。
            //
            //   本项目按需采样（无全图网格），故等价实现为【局部环形平均】。
            //   仅对 dist 做模糊即可：所有地形消费者（boundaryStrength 高斯 /
            //   T5 的 decay）都是 dist 的函数 ⇒ dist 平滑则全链路平滑。
            //   ★ 2026-09-13：该环形平均的 reach 处硬切换已修（改 smoothstep 渐隐，
            //     否则在 dist=420 等值线产生阶跃 → 又一条直线网）。
            //
            //   为何必要：dist = (d2−d1)/2 在 Voronoi 顶点处是"到三条边取最小"，
            //   沿三条角平分线有折痕（C¹ 断裂）⇒ 被 T1/T5 原样印成"扇形射线/竖带"。
            //   注意：传入【未扭曲】坐标 —— distAt 内部会自行做同一域扭曲
            //   （若传扭曲后坐标会二次扭曲，导致邻居采样点偏移、模糊失效）。
            dist = blurDist(wx, wz, Math.max(0.0, (d2 - d1) * 0.5));

            // ★ 2026-09-12 结论（经四次尝试后定位到真正的根因）：
            //   法向换用哪种公式**都无法**消除跳变——因为跳变的根源不是法向，
            //   而是 **vrel 绑定于"一对板块"**：在 Voronoi 边界线上 d1≈d2，
            //   "最近/次近"的判定由浮点噪声决定 → 配对 (c1,c2) 不确定 → vrel 换人 → stress 乱跳。
            //
            //   演进记录（保留以免后人重走）：
            //   ① 配对法向 (s2−s1)/|s2−s1|     ② ∇(d2−d1) 解析梯度
            //   ③ 中心差分（V 形 → 对称抵消 → 劣化 4.7×）  ④ dist 单侧差分
            //   ⑤ 对 stress 做局部平均（旧 smoothStress，只能压制不能拓扑消除）
            //   → **最终正解见 {@link #stressField}**：应力改为【连续加权投票构造】，
            //     不再依赖"配对"，从构造上消除配对切换 ⇒ 无需任何事后平滑。
            //     此处配对法向仅保留给 btype/rate 这个【离散标签】使用。
            if (len > 1e-9) {
                ux = nx / len; uz = nz / len;            // 配对法向（便宜）
            } else {
                ux = 1.0; uz = 0.0;
            }
            tx = -uz; tz = ux;                       // ★ 切向 = 法线旋转 90°（沿走向）
        }

        // ★ 2026-09-12：dist 与 stress 改为【全局可用】（都连续），
        //   只有 btype/rate 这个【离散标签】才按 BOUNDARY_REACH 截断。
        //   原因：T5 形变作用距离达 900wu > 本截断值，若在 320 处把 stress 归零
        //   就会出现新的硬截断（此前已踩过一次）。现在：
        //     · 近场（<reach）：btype 有效 → 岩性/分类可用；
        //     · 远场：btype=INTERIOR，但 dist/stress 仍连续 → T5 的 decay 自然收敛。
        // ★ 2026-09-13 第七次伪影修复：应力改为【连续加权投票构造】（见 stressField）。
        //   旧的「最近板块对归一化速度差」是分片常数场，配对切换处沿 Voronoi 直线网阶跃。
        double stressCont = stressField(wxw, wzw);
        if (dist >= BOUNDARY_REACH) {
            return new Sample(dist, INTERIOR, 0.0, tx, tz, stressCont, d1);
        }

        // 分类：dot/cross 分解
        double[] v1 = new double[2], v2 = new double[2];
        plateVelocity(c1x, c1z, v1);
        plateVelocity(c2x, c2z, v2);
        double vrx = v1[0] - v2[0], vrz = v1[1] - v2[1];

        double dot = vrx * ux + vrz * uz;
        double cross = Math.abs(vrx * uz - vrz * ux);

        int btype;
        double rate;
        if (Math.abs(dot) > cross) {
            btype = dot > 0 ? CONVERGENT : DIVERGENT;
            rate = Math.abs(dot);
        } else {
            btype = TRANSFORM;
            rate = cross;
        }
        // ★ 2026-09-13 第七次伪影修复：stress 不再由「逐点板块对速度差」产生
        //   （旧式 dot/|vrel| 在配对不变的区域是**常数** ⇒ 分片常数场 ⇒ 配对切换处
        //    沿 Voronoi 直线网阶跃，事后平滑只能压制不能拓扑消除，见 stressField 注释）。
        //   现直接取连续加权投票场 stressCont（上方已算）。
        //   注：dot/cross 仍用于 btype/rate —— 那是**离散标签**（供岩性等用途），
        //   已证实不参与地形合成。
        return new Sample(dist, btype, Math.min(rate, 2.0), tx, tz, stressCont, (d1 + d2) * 0.5);
    }

    /**
     * 边界 profile → 高程偏置（e 单位）。移植自 worldgen elevation.rs 的 boundary_profile。
     *
     * <p><b>关键差异</b>：worldgen 区分陆-陆/陆-洋/洋-洋三种汇聚（因它有显式的
     * {@code is_continental} 板块属性）。本类暂<b>不维护板块陆/洋属性</b>（Phase T1 简化），
     * 改为由调用方传入当前点的海陆倾向 {@code isLand} 决定取造山还是海沟分支。</p>
     *
     * <p><b>★ 2026-09-13 Phase T6 现状说明（避免误用）</b>：本方法在<b>生产路径中已不再调用</b>，
     * 原因是它与 T1 的职责重叠且方式相冲突：</p>
     * <ul>
     *   <li><b>陆侧</b>（造山 {@code MOUNTAIN_GAIN} / 裂谷 {@code -RIFT_DEPTH}）—— 与 T1 的
     *       {@code applyTectonicWeights} 重复。T1 之所以改用<b>权重调制</b>而非直接加偏置，
     *       是因为直接抬升 e 会连锁增强地形雨（{@code PrecipField.orographicGain} 基于高度差）
     *       → 推高全局降水、稀释干旱区区分度（实测 head 最干桶 0.922→0.959）。</li>
     *   <li><b>海洋侧</b>——由 {@link #oceanProfile} 以<b>中尺度形态</b>（窄槽 + 离轴弧）
     *       取代；直接用本方法的宽高斯 {@code TRENCH_DEPTH/ RIDGE_HEIGHT} 会与 T1 的
     *       宽带趋势叠成"双重加深"。</li>
     * </ul>
     * <p>保留此方法的唯一用途：<b>符号语义自检</b>（{@code TectonicProbe[2]} 用它断言
     * "造山+/海沟−/裂谷−/洋脊+/走滑0" 这一与参考项目一致的<b>参考语义</b>）。
     * 若日后要恢复"显式偏移"路线，须先解决上述地形雨连锁问题。</p>
     *
     * @param isLand 当前采样点是否倾向陆地（决定造山 vs 海沟 / 裂谷 vs 洋中脊）
     * @return 高程偏置（e 单位），内部点与走滑边界返回 0
     */
    public double elevationOffset(Sample s, boolean isLand) {
        if (s.btype() == INTERIOR || s.btype() == TRANSFORM) {
            return 0.0;   // 走滑无垂向贡献（worldgen 亦为 0）
        }
        double g = Math.exp(-(s.dist() * s.dist()) / (2.0 * PROFILE_SIGMA * PROFILE_SIGMA));
        double rateFactor = Math.min(s.rate(), 2.0);
        // rate 归一化到 [0.5, 1.5]：构造活动越强，幅度越大
        double strength = 0.5 + rateFactor * 0.5;

        return switch (s.btype()) {
            case CONVERGENT -> isLand
                    ? MOUNTAIN_GAIN * strength * g
                    : -TRENCH_DEPTH * strength * g;
            case DIVERGENT -> isLand
                    ? -RIFT_DEPTH * strength * g
                    : RIDGE_HEIGHT * strength * g;
            default -> 0.0;
        };
    }

    /**
     * ★ 2026-09-13 Phase T6：<b>海洋侧构造剖面</b>（俯冲带「海沟窄槽 + 火山弧」）。
     *
     * <p>只对<b>汇聚边界</b>产出（用连续量 {@code cw = smoothPos(stress)} 加权），返回
     * <b>纯剖面值</b>（e 单位：负 = 海沟槽 / 正 = 火山弧）。海陆淡入由调用方按基面
     * {@code eOcean} 施加（与 {@code OceanFeatures} 的 {@code fade} 同款，保证连续）。</p>
     *
     * <h3>为何不能靠 T1 的权重调制</h3>
     * <p>T1 把汇聚边界表达为「提升 DEEP_OCEAN / MOUNTAINS 权重」，其空间尺度由
     * {@link #boundaryStrength} 的 σ=110 高斯支配 → 只能给出<b>宽带趋势</b>。
     * 而参考项目 {@code elevation.rs:boundary_profile} 在海洋侧给出的是<b>中尺度形态</b>：</p>
     * <pre>{@code
     *   (陆,洋) 俯冲：trench * gaussian(dist, 12.0)                       // 窄槽紧贴边界线
     *   (洋,洋) 汇聚：dist<15 → gaussian(dist, 8.0)                        // 极窄海沟
     *                 否则   → arc * gaussian(dist − 35.0, 18.0)           // ★ 离轴弧
     * }</pre>
     * <p>关键差异是「<b>离轴</b>」—— 弧不在边界线上，而在海沟<b>后方</b>
     * （俯冲板片达 ~100km 深部脱水熔融 → 弧后 ~200km 发育）。这种<b>位置偏移</b>
     * 无法用"权重调制"表达：权重只在"线性叠加"意义下抬升/压低，<b>不含空间平移</b>。</p>
     *
     * <h3>为何不需要区分「对面板块是陆是洋」</h3>
     * <p>参考项目靠 {@code is_continental[pid]} 分 (陆,洋)/(洋,洋)，因为它的
     * {@code base_elevation} 等属性绑在板块上。但<b>「海沟 + 火山弧」是俯冲带的普遍特征</b>：
     * 马里亚纳型（洋-洋）与安第斯型（洋-陆）都有（差别仅在弧发育于洋壳还是陆壳，
     * 属<b>岩性/生物群系层</b>的事，不属高程形态层）。故本项目<b>不新增板块陆/洋属性</b> ——
     * 既省一次 continent 采样，又避免与既有 {@link ContinentField} 的全球海陆场语义冲突。</p>
     *
     * <h3>连续性（不引入伪影）—— 三条硬约束，均已实测验证</h3>
     * <ol>
     *   <li><b>不用离散 {@code btype} 分支</b>：它在 {@code dist = BOUNDARY_REACH(320)} 处
     *       跳变为 INTERIOR，若据此开关本函数，会在弧高斯仍有 ~1% 幅值处<b>砍出 1 块台阶</b>。
     *       改用连续 {@code cw = smoothPos(stress)}（已保证 {@code f(0)=0} 且一阶导连续）。</li>
     *   <li><b>不用 {@code rate}（最重要的教训）</b>：{@code rate = |v1−v2|} 只依赖「最近板块对」
     *       ⇒ 是<b>分片常数</b>，在三板块交汇（<b>Voronoi 顶点</b>）处跳变。若用它做幅度因子，
     *       跳变轨迹正是顶点处发散的 <b>Y 形细直线</b>（实测 {@code arc_gradmag} max=7.4e-3，
     *       与总场 7.7e-3 同量级 ⇒ 该分量贡献了<b>全部</b>残余细线；而 {@code dist_gradmag}
     *       仅 1.40 ≈ √2，属正常顶点折痕而非跳变 ⇒ 排除 dist）。
     *       这与项目既有铁律一致：{@code btype/rate} 是<b>离散标签</b>（供岩性等用途），
     *       <b>不得参与地形合成</b>（{@code boundaryStrength} 的注释已记同一教训）。
     *       参考项目之所以能用 {@code rate}，是因为它对整张 profile 网格先算后
     *       {@code blur_grid} 模糊（{@code elevation.rs} Phase 2）—— 本项目按需采样、
     *       无全图模糊步骤，故直接弃用。</li>
     *   <li>{@link #OCEAN_PROFILE_REACH} 处早退：该处弧高斯值 &lt; 1e-6 e
     *       （1 块 = 0.0079 e）⇒ 截断不可见。</li>
     * </ol>
     *
     * <h3>尺度标定（本项目 wu 体系，非参考项目的像素体系）</h3>
     * <pre>
     *   PROFILE_SIGMA = 110（既有，boundaryStrength 的高斯）→ T1 的宽带趋势
     *   本函数：海沟 σ=45 窄槽（紧贴边界）、火山弧 = ARC_OFFSET(210) 处 σ=55 的隆起
     * </pre>
     *
     * @param s 构造采样（<b>只用连续量</b> {@code dist} / {@code stress}）
     * @return 海洋侧高程偏置（e 单位）；内部点 / 走滑 / 非汇聚 / 超出作用距离均返回 0
     */
    public double oceanProfile(Sample s) {
        double d = s.dist();
        if (d >= OCEAN_PROFILE_REACH) return 0.0;      // 早退：此处弧高斯 < 1e-6 e（不可见）
        double cw = smoothPos(s.stress(), STRESS_POS_EPS);
        if (cw <= 0.0) return 0.0;                     // 非汇聚（连续量，无跳变）
        double trench = -TRENCH_NOTCH_DEPTH * gaussian(d, TRENCH_NOTCH_SIGMA);
        double arc = ARC_HEIGHT * gaussian(d - ARC_OFFSET, ARC_SIGMA);
        return (trench + arc) * cw;
    }

    /** 高斯核 {@code exp(−d²/2σ²)}（移植自 worldgen {@code elevation.rs} 的同名函数）。 */
    private static double gaussian(double d, double sigma) {
        return Math.exp(-(d * d) / (2.0 * sigma * sigma));
    }

    /**
     * ★ 2026-09-13 Phase T7(P4)：<b>俯冲带火山活动强度</b> ∈ [0,1]（<b>区域场</b>）。
     *
     * <h3>用途：让陆地火山"沿俯冲带富集"而非随机散布</h3>
     * <p>{@link LandFeatures} 原先把陆上火山布点做成<b>纯随机哈希</b>（800wu 格 3% / 掩码区 12%），
     * 与任何地质过程无关 —— 而真实地球的陆上火山<b>几乎全部发育在俯冲带</b>
     * （安第斯、喀斯喀特、日本、爪哇），其余（板内火山）稀少。</p>
     *
     * <h3>★ 为何【不含】Voronoi 距离 {@code dist}（实测教训，务必保留）</h3>
     * <p>初版实现为 {@code cw × gaussian(dist − ARC_OFFSET, ARC_SIGMA)}
     * （想复用海中火山弧的"离轴带"几何）。但它有<b>致命副作用</b>：
     * {@code dist = (d2−d1)/2} 在远离 Voronoi 顶点处是<b>线性函数</b>
     * ⇒ 其等值线是<b>直线</b>（平行于板块边界）⇒ {@code boost} 沿直线变化
     * ⇒ 火山格点的"激活阈值"与火山区掩码阈值都<b>沿直线翻转</b>
     * ⇒ 用户实机看到"火山区域边界有一条笔直斜线"（实测截图，坐标 1077,-833）。</p>
     * <p>这是本项目反复出现的同一类坑（参见 {@code CHAIN_SCALE} 的<b>硬约束</b>
     * "禁止用 dist 作噪声/门控坐标"、以及 {@code oceanProfile} 弃用 {@code rate}）。
     * <b>结论：陆地火山门控只能用区域连续量。</b></p>
     *
     * <p>地质上这也更准确：<b>弧火山作用是一条"带"（宽 100~300km）而非一条"线"</b>，
     * 用区域尺度的应力场（{@code stressField} 的 σ=1000）表达正合适；
     * 线状的弧轴几何只保留给<b>水下</b>地形剖面（{@code oceanProfile}，海里的直线不明显）。</p>
     *
     * @return 0 = 无俯冲带火山活动 / 1 = 最强（俯冲带核心区）
     */
    public double arcVolcanism(Sample s) {
        return smoothPos(s.stress(), STRESS_POS_EPS);
    }

    // ===== ★ 2026-09-13 Phase T7(P0)：板块壳属性（陆壳 / 洋壳）=====
    /**
     * 壳属性过渡半宽（{@code c} 值域单位）。
     *
     * <p>{@code c} 是 {@link ContinentField} 的多倍频 FBM：{@code c≤−w} → 洋壳 0、
     * {@code c≥+w} → 陆壳 1、中间 smoothstep 过渡。</p>
     *
     * <p>取 0.15（{@code c} 值域 ±1 的 15%）：使"海岸附近"（|c| 小）有一个
     * 平滑的过渡带，而大陆内部（c≈+0.5）恒为 1、深海（c≈−0.5）恒为 0。
     * 该过渡带无需刻意很窄 —— 它所在的位置本来就处于海陆过渡，
     * 造山/海沟的切换在那里发生是<b>地质上应然</b>的（大陆坡）。</p>
     */
    private static final double SHELL_BLEND = 0.15;

    /**
     * ★ 2026-09-13 Phase T7(P0)：<b>壳属性</b> ∈ [0,1]（0 = 洋壳 / 1 = 陆壳）。
     *
     * <h3>为何需要（对照参考项目 worldgen）</h3>
     * <p>参考项目 {@code elevation.rs:boundary_profile} 决定"造山还是海沟"的关键一行是：
     * <pre>{@code
     *   if plates.is_continental[current_pid] {   // ← 用【当前点】的壳属性
     *       offset = peak * gaussian(dist, mw);   // 陆侧 → 造山
     *   } else {
     *       let trench = -2500.0 * ...;           // 洋侧 → 海沟
     *   }
     * }</pre>
     * 注意用的是 {@code current_pid}（<b>当前点</b>），而非配对字段 {@code pa/pb} ——
     * 配对属性只用于<b>选择分支类型</b>（陆-陆/陆-洋/洋-洋）。</p>
     * <p>本项目此前用 {@code landW/oceanW}（来自<b>形态学</b>类型场）代替该判据，
     * 等于<b>用形态冒充地质</b>：类型场与 {@code c} 场可互相矛盾
     * （实测出现"火山弧把 eLand≈0 的点抬出水面"—— 因为该点 c 说海、类型场说陆）。</p>
     *
     * <h3>★ 为何用 {@code c} 而不是"板块常数壳属性"（实测否决）</h3>
     * <p>曾尝试"壳属性 = 所属板块的常数"（更贴近参考项目的 {@code is_continental[pid]}），
     * 但 {@code TectonicProbe[8]} <b>实测否决</b>：本项目 {@code PLATE_SPACING=2000wu}
     * 而 {@code c} 基频波长 ≈4000wu ⇒ <b>一个大陆仅约 2 个板块</b>
     * （参考项目是 ~10 个微板块/大陆）⇒ 相邻板块壳属性差
     * {@code P50=0.41 / max=1.0}、<b>48.4% 的相邻板块"一陆一洋"</b>
     * ⇒ 等效高程阶跃达 <b>96 块</b>，必然印出 Voronoi 直线网
     * （正是本项目历史上反复出现的"边界线伪影"）。</p>
     * <p>改用 {@code c} 则有三个决定性优点：</p>
     * <ol>
     *   <li><b>与海陆严格同源</b>：海陆本来就由 {@code c} 决定 ⇒ 判据与结果不可能矛盾，
     *       从构造上消除"形态与地质打架"（含那个"弧造陆"）。</li>
     *   <li><b>绝对连续</b>：{@code c} 是多倍频 FBM，处处 C¹ ⇒ <b>不可能产生阶跃/直线网</b>。</li>
     *   <li>与参考项目的 {@code current_pid} 语义<b>等价</b>（都是"当前点在海侧还是陆侧"），
     *       只是用连续场表达而非板块常量 —— 在其自身尺度下二者近似一致。</li>
     * </ol>
     *
     * <h3>与 {@code oceanW} 的区别（为何必须换掉旧的）</h3>
     * <p>{@code oceanW} 与 {@code c} 都是连续量、都不产生阶跃，差别只在<b>是否与海陆同源</b>。
     * 旧实现在"两场矛盾"的点上把海沟/造山放错侧；用 {@code c} 后<b>从定义上</b>不可能放错。</p>
     *
     * @param c 大陆性（{@code ContinentField.sample}）
     * @return 0 = 洋壳 / 1 = 陆壳（连续过渡）
     */
    public static double shellFromC(double c) {
        if (c <= -SHELL_BLEND) return 0.0;
        if (c >= SHELL_BLEND) return 1.0;
        double t = (c + SHELL_BLEND) / (2.0 * SHELL_BLEND);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 边界影响强度（0~1）：边界线上最大，{@code BOUNDARY_REACH} 处衰减到 ~0。
     * 供调用方按"距边界多近"平滑地施加构造影响（地形类型权重调制等）。
     */
    public static double boundaryStrength(Sample s) {
        // ★★★ 2026-09-12 第五次伪影修复（残留细线 / 串珠状虚线）★★★
        //
        //   原式带 {@code if (s.btype() == INTERIOR) return 0.0;} —— 这是**硬截断**：
        //   {@code btype} 在 {@code dist >= BOUNDARY_REACH(320)} 处跳变为 INTERIOR，
        //   使本函数沿 {@code dist≈320} 的**环**由 exp(−320²/2σ²)=0.0146 <b>骤降为 0</b>。
        //   该跳变量再乘上沿环变化的 chain 噪声 ⇒ 地形上一条<b>亮度随位置起伏的环状线</b>
        //   ⇒ 用户看到的"<b>串珠状虚线</b>"（实测 {@code gradmag.png} 三条孤立细线）。
        //
        //   正解：<b>只依赖连续量 dist</b>。高斯本身在 320wu 处已衰减到 1.5%，
        //   内部区本就自然≈0，无需再用离散标签去"截断"（截断反而制造了伪影）。
        //   （`btype` 仅用于岩性等**离散**用途，不参与地形合成。）
        return Math.exp(-(s.dist() * s.dist()) / (2.0 * PROFILE_SIGMA * PROFILE_SIGMA));
    }

    /**
     * ★ Phase T2：带 Chain modulation 的边界强度（仅汇聚边界生效）。
     *
     * <p>汇聚造山带在沿走向方向被切成"串珠状"独立山峰（{@link #chainModulation}），
     * 而离散（裂谷/洋中脊）保持连续——真实裂谷系统是连续线状的，不应串珠化。
     *
     * <p>这是<b>实例方法</b>（chain 采样需要世界坐标，且用实例种子）。
     */
    public double boundaryStrengthChained(Sample s, double wx, double wz) {
        double g = boundaryStrength(s);
        // ★ 2026-09-12 性能修复：**先判衰减再算 chain**。
        //   boundaryStrength 是 σ=110 的高斯，在 330wu 处已降到 1%；
        //   而"非 INTERIOR" 的区域面积随 d² 增长 → 若不早退，chainModulation
        //   （含 1 次 valueNoise ≈ 4 哈希）会在远大于实际影响区的范围被白算。
        //   此处 `g` 已算出，直接用阈值早退。
        //   ★ 阈值降到 1e-6：任何"提前 return"都是跳变源，故阈值必须低到**不可见**。
        //     1e-6 × combined(≤2) × BOOST(2.5) ≈ 5e-6 → e 变化约 1.6e-7（远小于 1 块的 1/192≈0.005e）。
        if (g <= 1e-6) return 0.0;
        // ★ 连续应力 + **平滑正部**（消除 stress=0 等值线上的折痕 → 笔直断裂线根因）
        double cw = smoothPos(s.stress(), STRESS_POS_EPS);
        double dw = smoothPos(-s.stress(), STRESS_POS_EPS);
        // 汇聚部分串珠化（chain），离散部分保持连续（真实裂谷系统是线状）
        double combined = cw * chainModulation(wx, wz, s) + dw;
        return g * combined;
    }

    /**
     * ★ 2026-09-12 Phase T2：<b>Chain modulation</b>（移植自 worldgen elevation.rs）。
     *
     * <p>把沿边界均匀延伸的山脊<b>打破成一个个独立山峰</b>：
     * 在<b>沿走向</b>方向用低频（山链长）、<b>垂直走向</b>方向用高频（山链窄）
     * 采样 ridged 噪声 → 形成"串珠状"山峰，而非一条均匀的墙。
     *
     * <p><b>为何要沿走向旋转坐标</b>：若在固定世界轴上采样，山链会被切成
     * 与走向无关的斑块，"走向"就白做了。旋转后噪声随走向对齐，
     * 山峰才真正沿造山带排列。
     *
     * @return 调制系数，约 [0.25, 1.0]（不改变符号，只压弱部分区段）
     */
    private double chainModulation(double wx, double wz, Sample s) {
        // ★★★ 2026-09-12 第三次回归修复（用户反馈"若干条平行带"，且"更明显了"）★★★
        //
        //   【根因】原实现第二坐标为 s.dist()：`a*CHAIN_ACROSS_FREQ = dist/2000*18 = dist/111`
        //   → 噪声沿"跨边界"方向每 111wu 一个周期（实测 d=19 谷 0.3131 / d=60 峰 0.5328）。
        //   dist 的等值线平行于 Voronoi 边界且绕板块闭合 → 波纹即【平行于边界的同心环带】。
        //
        //   【历史】此问题在 087698c 明确修过（commit 原文："高程密集同心波纹 …
        //   等值线绕板块格子闭合成同心环 == 项目当初否决 Terrace 的『环状台阶伪影』同源。
        //   改为沿走向波：褶皱 sin(dist)→sin(along)"），但 8e51a08 为修"每块独立生成"
        //   又改回 dist 基准 → 波纹回归；60e182f 平滑 stress 后由"断续疤痕"变"规整波纹"
        //   → 视觉上更明显。本轮彻底消除，并写下硬约束（见 CHAIN_SCALE javadoc）。
        //
        //   【正解】跨走向<b>不允许</b>任何周期性：
        //     · "平行于边界成带"这一几何由 boundaryStrength 的 σ=110 高斯承担；
        //     · chain 的职责只是把带内强度沿走向切成独立山峰 → 只需<b>单变量</b>调制。
        //   故此处只用【世界坐标】做低频起伏（全局连续、无 per-cell 依赖、
        //   与边界法向无关 → 不会形成平行带），dist 完全不参与噪声坐标。
        //
        //   性能：由 2 次 ridgedNoise（8+ 哈希）降为 1 次 valueNoise（4 哈希）。
        double n = valueNoise(wx / CHAIN_SCALE, wz / CHAIN_SCALE, CHAIN_SEED);   // [-1,1]
        // 映射到 [0.25, 1.0]：保留大部分强度，只在"谷"处压低 → 山峰分明
        return CHAIN_MIN + (1.0 - CHAIN_MIN) * clamp01(n * 0.5 + 0.5);
    }

    /**
     * 2D value noise，返回约 [-1,1]。
     *
     * <p>★★★ 2026-09-12 第六次伪影修复（Voronoi 顶点扇形射线）★★★</p>
     *
     * <p><b>旧实现（双线性 + smootherstep）为何产生扇形直线</b>：双线性插值
     * 在 2D 只有 <b>C⁰</b>——
     * <pre>
     *   ∂f/∂x = lerp((v10−v00)·sx′, (v11−v01)·sx′, sz)
     * </pre>
     * 跨 <b>z 格线</b>时上下两行的 {@code (v10−v00)} 不同 ⇒ {@code ∂f/∂x} <b>跳变</b>
     * ⇒ 等值线在格线处出现折角 ⇒ 在格点附近聚成一族<b>扇形直线段</b>；
     * 而 {@code faultOffsetUnit} 又对它做 smoothstep 陡坎 ⇒ 折角被放大成可见"射线"。</p>
     *
     * <p><b>参考项目做法</b>：{@code worldgen} 全程使用 fBm/ridged_fbm（基于连续噪声），
     * 不存在格点折角；本项目手写噪声必须自行保证阶数。</p>
     *
     * <p><b>本实现：Catmull-Rom 双三次插值</b>（4×4 邻域）——全局 <b>C¹</b>，
     * 等值线处处切线连续 ⇒ 折角/扇形从构造上消失。
     * 权重和恒为 1（不引入整体偏移）。代价：每点 16 次哈希（原 4 次）。</p>
     */
    private double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        // Catmull-Rom 基函数权重（和一为 1）
        double fx2 = fx * fx, fx3 = fx2 * fx;
        double wx0 = -0.5 * fx3 + fx2 - 0.5 * fx;
        double wx1 = 1.5 * fx3 - 2.5 * fx2 + 1.0;
        double wx2 = -1.5 * fx3 + 2.0 * fx2 + 0.5 * fx;
        double wx3 = 0.5 * fx3 - 0.5 * fx2;
        double fz2 = fz * fz, fz3 = fz2 * fz;
        double wz0 = -0.5 * fz3 + fz2 - 0.5 * fz;
        double wz1 = 1.5 * fz3 - 2.5 * fz2 + 1.0;
        double wz2 = -1.5 * fz3 + 2.0 * fz2 + 0.5 * fz;
        double wz3 = 0.5 * fz3 - 0.5 * fz2;
        // 先沿 z 对 4 个 x 列各插值一次，再沿 x 合成（4×4=16 次哈希，无分配）
        double r0 = cellNoise(ix - 1, iz - 1, salt) * wz0 + cellNoise(ix - 1, iz, salt) * wz1
                  + cellNoise(ix - 1, iz + 1, salt) * wz2 + cellNoise(ix - 1, iz + 2, salt) * wz3;
        double r1 = cellNoise(ix, iz - 1, salt) * wz0 + cellNoise(ix, iz, salt) * wz1
                  + cellNoise(ix, iz + 1, salt) * wz2 + cellNoise(ix, iz + 2, salt) * wz3;
        double r2 = cellNoise(ix + 1, iz - 1, salt) * wz0 + cellNoise(ix + 1, iz, salt) * wz1
                  + cellNoise(ix + 1, iz + 1, salt) * wz2 + cellNoise(ix + 1, iz + 2, salt) * wz3;
        double r3 = cellNoise(ix + 2, iz - 1, salt) * wz0 + cellNoise(ix + 2, iz, salt) * wz1
                  + cellNoise(ix + 2, iz + 1, salt) * wz2 + cellNoise(ix + 2, iz + 2, salt) * wz3;
        return r0 * wx0 + r1 * wx1 + r2 * wx2 + r3 * wx3;
    }

    /** 格点随机值 [-1,1]。 */
    private double cellNoise(int ix, int iz, long salt) {
        return unit(hash(ix, iz, salt)) * 2.0 - 1.0;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    // ===================== 内部工具 =====================

    // ===== 连续应力场（★ 2026-09-13 第七次伪影修复：加权投票取代"配对速度差"）=====
    /**
     * 投票权重的高斯 σ（wu）。
     *
     * <p>取 1000（= 板块间距 2000 的一半）：近邻板块权重显著、次近邻仍有参与
     * ⇒ 应力随位置平滑变化；远场自然衰减（3000wu 处 w≈0.011）。</p>
     */
    private static final double STRESS_VOTE_SIGMA = 1000.0;
    /**
     * 投票窗口半径（格）。
     *
     * <p><b>必须 ≥ 2</b>：半径 1（3×3）时最近被排除的种子仅 ~1300wu 远
     * → 权重 {@code e^{−0.845}≈0.43}，窗口平移会造成可见跳变（又生一条直线网）。
     * 半径 2（5×5）→ 最近被排除种子 ≥ 3300wu → 权重 ≈0.0045，不可见。</p>
     */
    private static final int STRESS_VOTE_RADIUS = 2;
    /**
     * 幅度增益：把"两板块极限下的 {@code (v1−v2)·n/2}"还原到旧式 {@code dot/|vrel|} 的量级
     * （常见 {@code |vrel|≈1}），以<b>保持既有标定</b>（CONVERGENT_BOOST / STRESS_POS_EPS 等）。
     */
    private static final double STRESS_VOTE_GAIN = 2.0;

    /**
     * ★ 2026-09-13 第七次伪影修复：<b>连续加权投票构造的应力场</b>（处处 C^∞）。
     *
     * <h3>为何必须换掉旧定义</h3>
     * <p>旧式 {@code stress = dot/|vrel|}（{@code dot = (v1−v2)·n}）由「最近 + 次近板块」
     * 这一<b>对</b>决定。而在配对不变的空间区域里，它<b>只依赖板块对、与位置无关</b>
     * ⇒ 应力是<b>分片常数场</b>——探针实测 {@code P50|∇stress| = 7.9e-17}，
     * 即<b>中位梯度精确为 0</b>，这是"分片常数"的铁证。</p>
     * <p>配对在 Voronoi 边界与 order-2 边上切换 ⇒ 应力沿这些<b>直线网</b>阶跃（幅度可达 2.0）
     * ⇒ 经 {@code boundaryStrength}(σ=110) × {@code CONVERGENT_BOOST}(2.5) 进入 eLand
     * ⇒ 用户截图的「<b>笔直长线段 + Y 形交汇</b>」（实测 eLand {@code max|grad|=0.0026} e/wu，
     * 与阶跃传导的理论量级吻合）。</p>
     * <p><b>事后平滑解决不了</b>：旧 {@code smoothStress} 的环形平均只在边界带内生效
     * （reach 处渐隐），带缘仍有 {@code (1−t)} 权重的原始阶跃漏出；
     * 且项目注释早已写明「<i>平均只能压制、不能拓扑消除</i>」。
     * 参考项目 worldgen 的板块属性是「每板块一个常量」、<b>从不做逐点速度差</b>
     * ⇒ 它天然没有这个问题。本次对齐该语义。</p>
     *
     * <h3>新定义（无"配对"概念，故无配对切换）</h3>
     * <pre>
     *   w_i    = exp(−d_i² / 2σ²)                 // 邻块高斯权重（σ = STRESS_VOTE_SIGMA）
     *   û_i    = (p − s_i) / d_i                   // 由种子 i 指向采样点
     *   stress = GAIN · Σ w_i·(v_i · û_i) / Σ w_i  // 加权平均，钳到 [−1,1]
     * </pre>
     * <p>地质语义与旧式<b>同号</b>（两板块极限下 = {@code (v1−v2)·n/2}）：</p>
     * <ul>
     *   <li>邻块朝采样点靠近（{@code v·û > 0}）→ <b>汇聚 → 正</b></li>
     *   <li>邻块远离（{@code v·û < 0}）→ <b>离散 → 负</b></li>
     *   <li>邻块切向掠过（{@code v ⊥ û}）→ <b>走滑 → ≈0</b>，
     *       与「纯走滑无垂向形变」铁律一致（无需再靠 {@code max(0,·)} 事后修正）</li>
     * </ul>
     *
     * <h3>为何不再需要平滑</h3>
     * <p>投票对每个参量连续可导；被排除的远场种子权重 &lt; 0.5%（半径 2）⇒ 窗口平移无可见跳变。
     * 故 {@code smoothStress} 及其 12 点环形采样<b>整体删除</b>，
     * 每次采样反而<b>少 100+ 次哈希</b>（12 点 × 11 次哈希 ≈ 132 → 25 格 × 2 次 ≈ 50），
     * 是净性能收益。同时<b>不使用任何配对法向</b>（法向在配对切换处不连续，用它会重新引入断续）。</p>
     *
     * <p><b>唯一奇点</b>：恰好落在种子点上（{@code d<1e-9}）返回 0 —— 单点、无面积，不可见。</p>
     */
    private double stressField(double wx, double wz) {
        int baseX = (int) Math.floor(wx / PLATE_SPACING);
        int baseZ = (int) Math.floor(wz / PLATE_SPACING);
        final double inv2s2 = 1.0 / (2.0 * STRESS_VOTE_SIGMA * STRESS_VOTE_SIGMA);
        double num = 0.0, den = 0.0;
        for (int dx = -STRESS_VOTE_RADIUS; dx <= STRESS_VOTE_RADIUS; dx++) {
            for (int dz = -STRESS_VOTE_RADIUS; dz <= STRESS_VOTE_RADIUS; dz++) {
                int cx = baseX + dx, cz = baseZ + dz;
                // 种子位置（等价 plateSeed，内联以免热路径分配数组）
                long hs = hash(cx, cz, SALT_SEED);
                double sx = (cx + 0.5 + (unit(hs) - 0.5) * SEED_JITTER) * PLATE_SPACING;
                double sz = (cz + 0.5 + (unit(hs >>> 24) - 0.5) * SEED_JITTER) * PLATE_SPACING;
                double ux = wx - sx, uz = wz - sz;
                double d = Math.sqrt(ux * ux + uz * uz);
                if (d < 1e-9) return 0.0;
                double w = Math.exp(-d * d * inv2s2);
                // 速度（等价 plateVelocity，内联）
                long hv = hash(cx, cz, SALT_VEL);
                double ang = unit(hv) * Math.PI * 2.0;
                double mag = 0.3 + unit(hv >>> 24) * 0.7;
                // 投影 = v·û（û = (p−s)/d）
                num += w * mag * (Math.cos(ang) * ux + Math.sin(ang) * uz) / d;
                den += w;
            }
        }
        if (den <= 1e-12) return 0.0;
        double v = STRESS_VOTE_GAIN * num / den;
        return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
    }

    // ===== dist 空间模糊（对齐 worldgen elevation.rs 的 blur_grid）=====
    /**
     * dist 模糊的环形采样半径（wu）。
     *
     * <p>须覆盖"Voronoi 顶点折痕"的尺度：折痕从顶点沿角平分线延伸，
     * 在 ~150wu 内曲率最大。取 150 可有效抹平。</p>
     */
    private static final double DIST_BLUR_RADIUS = 150.0;
    /** dist 模糊环形采样点数（等角分布）。4 点已足以打散三条角平分线折痕。 */
    private static final int DIST_BLUR_SAMPLES = 4;
    /**
     * 模糊作用距离（wu）：dist 超过此值直接返回原值（该处已远离折痕，且省性能）。
     *
     * <p>取 3× 高斯影响宽度（σ=110）——更远处 boundaryStrength 已 &lt; 0.01。</p>
     */
    private static final double DIST_BLUR_REACH = 420.0;

    /**
     * 对 {@code dist} 做局部环形平均（= 参考项目 {@code blur_grid} 的按需等价实现）。
     *
     * <p>数学性质：对<b>线性</b>场，环形平均恒等于中心值 ⇒ 远离折痕处<b>结果不变</b>；
     * 只在折痕（C¹ 断裂）处把尖角抹圆 ⇒ <b>精确消除伪影而不改变标定</b>。</p>
     *
     * <p>★★★ 2026-09-12 第七次伪影修复（笔直线段 + Y 形交汇网）★★★</p>
     *
     * <p><b>根因</b>：原实现 {@code if (center >= DIST_BLUR_REACH) return center;}
     * 是<b>硬切换</b> —— {@code dist<420} 走 5 点环形平均、{@code dist≥420} 走原值，
     * 两分支<b>数值不相等</b>（环形平均可偏离中心上百 wu）⇒ {@code dist} 沿
     * <b>{@code dist=420} 等值线产生阶跃</b>。而 {@code dist} 的等值线是
     * <b>平行于 Voronoi 边的多边形偏移线</b>（直边 + 顶点交汇）⇒ 这套多边形网经
     * {@code boundaryStrength}(σ=110) → T1 类型权重（BOOST 2.5）被印进 eLand，
     * 形成用户截图的「笔直长线段 + Y 形交汇」（实测 eLand max|grad|=0.00245 e/wu，
     * 与阶跃传导的理论值 ~0.002 精确吻合；{@code tect_dist} 的 max|∇|=37.7 ——
     * 距离场物理上不可能超过 ~2.4 —— 即阶跃的直接证据）。</p>
     *
     * <p><b>正解</b>：与 {@link #smoothStress} 同款手法 —— 混合权重 {@code t}
     * 用 smoothstep 在 reach 处<b>渐隐到 0</b>（一阶导也为 0）⇒ 两分支 C¹ 衔接，
     * 阶跃从构造上消失；近边界（center→0）仍是全量模糊，抹平折痕的能力不变。</p>
     */
    private double blurDist(double wxw, double wzw, double center) {
        if (center >= DIST_BLUR_REACH) return center;
        double t = 1.0 - center / DIST_BLUR_REACH;
        t = t * t * (3.0 - 2.0 * t);            // center→reach 时 t→0（C¹），无硬切换
        double sum = center;
        for (int k = 0; k < DIST_BLUR_SAMPLES; k++) {
            double a = k * (2.0 * Math.PI / DIST_BLUR_SAMPLES);
            sum += distAt(wxw + DIST_BLUR_RADIUS * Math.cos(a),
                          wzw + DIST_BLUR_RADIUS * Math.sin(a));
        }
        double blurred = sum / (DIST_BLUR_SAMPLES + 1);
        return center + (blurred - center) * t;
    }

    /**
     * 轻量采样：只求"到 Voronoi 边界的距离"（<b>含域扭曲</b>，与 {@link #sample} 口径一致）。
     *
     * <p>与 {@link #sample} 的区别：不求种子坐标、不求速度、不建 Sample 对象 ⇒ 更廉价，
     * 专供 {@link #blurDist} 的邻居采样使用。</p>
     */
    private double distAt(double wx, double wz) {
        double wxw = wx, wzw = wz;
        if (WARP_AMP > 0.0) {
            wxw = wx + WARP_AMP * valueNoise(wx * WARP_FREQ, wz * WARP_FREQ, SALT_WARP);
            wzw = wz + WARP_AMP * valueNoise(wx * WARP_FREQ + 17.3, wz * WARP_FREQ + 31.7, SALT_WARP + 1);
        }
        int baseX = (int) Math.floor(wxw / PLATE_SPACING);
        int baseZ = (int) Math.floor(wzw / PLATE_SPACING);
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        double[] sp = new double[2];
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                plateSeed(baseX + dx, baseZ + dz, sp);
                double ddx = wxw - sp[0], ddz = wzw - sp[1];
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
            }
        }
        return d2 == Double.MAX_VALUE ? Double.MAX_VALUE : Math.max(0.0, (d2 - d1) * 0.5);
    }

    /**
     * 仅求"到 Voronoi 边界距离" = {@code (d2−d1)/2}（<b>不含域扭曲</b>）。
     *
     * <p>与 {@link #sample} 的内联循环相比，本方法只求最近/次近距离，
     * 不求种子坐标与速度，因而更轻。</p>
     */
    private double edgeDistRaw(double wx, double wz) {
        int baseX = (int) Math.floor(wx / PLATE_SPACING);
        int baseZ = (int) Math.floor(wz / PLATE_SPACING);
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        double[] sp = new double[2];
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                plateSeed(baseX + dx, baseZ + dz, sp);
                double ddx = wx - sp[0], ddz = wz - sp[1];
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
            }
        }
        return d2 == Double.MAX_VALUE ? Double.MAX_VALUE : Math.max(0.0, (d2 - d1) * 0.5);
    }

    /** 确定性哈希：cell 坐标 → 抖动后的种子世界坐标。 */
    private void plateSeed(int cx, int cz, double[] out) {
        long h = hash(cx, cz, SALT_SEED);
        double jx = unit(h);
        double jz = unit(h >>> 24);
        out[0] = (cx + 0.5 + (jx - 0.5) * SEED_JITTER) * PLATE_SPACING;
        out[1] = (cz + 0.5 + (jz - 0.5) * SEED_JITTER) * PLATE_SPACING;
    }

    /** 确定性哈希：板块速度（随机方向 + 0.3~1.0 大小）。 */
    private void plateVelocity(int cx, int cz, double[] out) {
        long h = hash(cx, cz, SALT_VEL);
        double ang = unit(h) * Math.PI * 2.0;
        double mag = 0.3 + unit(h >>> 24) * 0.7;
        out[0] = Math.cos(ang) * mag;
        out[1] = Math.sin(ang) * mag;
    }

    private long hash(int x, int z, long salt) {
        long h = (long) x * 374761393L + (long) z * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        return h ^ (h >>> 16);
    }

    /** 哈希 → [0,1)。 */
    private static double unit(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }
}
