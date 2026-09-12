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
     * 域扭曲幅度（wu）。Voronoi 边界本质是<b>中垂线（直线）</b>，直接用会让
     * 2000wu 的板块格子呈<b>笔直多边形</b>（用户反馈"岩石类型出现明显平直边界"）。
     * 对查询点做<b>连续</b>域扭曲后边界变为有机曲线，且不影响 dist/alongCoord 的连续性。
     * 取板块间距的 16%（太大会使板块形状失控，太小仍显直）。
     */
    private static final double WARP_AMP = 320.0;
    /** 域扭曲频率（1/wu）：波长 ~1600wu（约为板块间距的 0.8 倍）。 */
    private static final double WARP_FREQ = 1.0 / 1600.0;
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
     * 边界影响宽度（wu）：超出此距离返回 {@link #INTERIOR}（stress=0）。
     *
     * <p>★ 2026-09-12 由 320 扩到 1000：原值造成<b>硬截断伪影</b>——
     * T5 形变的作用距离是 700/900wu（其 decay 平滑衰减到 0），
     * 但本场在 320wu 就返回 INTERIOR → stress 从有值<b>骤降为 0</b>
     * → 形变在 dist=320 处被硬切（探针实测：dist 319.9→320.6 时偏移从
     * −0.0071 跳到 0，成线状）。扩到 1000 后覆盖 T5 reach，让其自身 decay 平滑收尾。
     *
     * <p>对 T1/T2 无副作用：其 {@code boundaryStrength} 是 σ=110 的高斯，
     * 在 320wu 处已衰减到 1.4%，扩范围不改变实际权重。</p>
     */
    private static final double BOUNDARY_REACH = 1000.0;
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
    /** 沿走向频率：低频 = 山链长。 */
    private static final double CHAIN_ALONG_FREQ = 6.0;
    /** 垂直走向频率：高频 = 山链窄。 */
    private static final double CHAIN_ACROSS_FREQ = 18.0;
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
            dist = Math.max(0.0, (d2 - d1) * 0.5);

            // ★ 2026-09-12 结论（经四次尝试后定位到真正的根因）：
            //   法向换用哪种公式**都无法**消除跳变——因为跳变的根源不是法向，
            //   而是 **vrel 绑定于"一对板块"**：在 Voronoi 边界线上 d1≈d2，
            //   "最近/次近"的判定由浮点噪声决定 → 配对 (c1,c2) 不确定 → vrel 换人 → stress 乱跳。
            //
            //   演进记录（保留以免后人重走）：
            //   ① 配对法向 (s2−s1)/|s2−s1|     ② ∇(d2−d1) 解析梯度
            //   ③ 中心差分（V 形 → 对称抵消 → 劣化 4.7×）  ④ dist 单侧差分 —— 均无效。
            //   → 正解见 {@link #sample}：保留便宜的配对法向，改为对 **stress 做局部平均**
            //     （等价 worldgen `elevation.rs` 的 `blur_grid`，其注释明确写道
            //      "Smooth profiles to eliminate Voronoi ridge discontinuities"）。
            if (len > 1e-9) {
                ux = nx / len; uz = nz / len;            // 配对法向（便宜）
            } else {
                ux = 1.0; uz = 0.0;
            }
            tx = -uz; tz = ux;                       // ★ 切向 = 法线旋转 90°（沿走向）
        }

        if (dist >= BOUNDARY_REACH) {
            return new Sample(dist, INTERIOR, 0.0, tx, tz, 0.0, d1);
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
        // ★ 连续应力：+1 纯汇聚 / -1 纯离散 / 0 走滑
        double stress = dotCrossToStress(dot, cross);

        // ★ 2026-09-12 修复（用户反馈"岩石类型交界处地形不自然"）——**关键修复**：
        //   stress 对 Voronoi 边界线/顶点做【局部平均】，等价 worldgen `elevation.rs`
        //   的 `blur_grid`（其注释："Smooth profiles to eliminate Voronoi ridge
        //   discontinuities"）。这是我一直没做的第二步。
        //
        //   为何必须做：在边界线附近 d1≈d2，"最近/次近"由浮点噪声决定
        //   → 配对 (c1,c2) 在边界线/顶点处【不确定】→ vrel 换人 → stress 骤变
        //   （实测单步 dStress 高达 1.93，导致 eLand 跳 0.1e ≈ 20 块）。
        //   这不是法向公式能解决的（已试 4 种法向公式均失败），必须做空间平滑。
        //
        //   成本控制：只在边界附近（dist < STRESS_SMOOTH_REACH）做 5 点平均
        //   （中心 + 十字 4 点），远处用原始值 → 影响范围有界。
        if (dist < STRESS_SMOOTH_REACH) {
            double e = STRESS_SMOOTH_EPS;
            stress = (stress
                    + stressAt(wxw + e, wzw) + stressAt(wxw - e, wzw)
                    + stressAt(wxw, wzw + e) + stressAt(wxw, wzw - e)) * 0.2;
        }
        return new Sample(dist, btype, Math.min(rate, 2.0), tx, tz, stress, (d1 + d2) * 0.5);
    }

    /**
     * 边界 profile → 高程偏置（e 单位）。移植自 worldgen elevation.rs 的 boundary_profile。
     *
     * <p><b>关键差异</b>：worldgen 区分陆-陆/陆-洋/洋-洋三种汇聚（因它有显式的
     * {@code is_continental} 板块属性）。本类暂<b>不维护板块陆/洋属性</b>（Phase T1 简化），
     * 改为由调用方传入当前点的海陆倾向 {@code isLand} 决定取造山还是海沟分支。
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
     * 边界影响强度（0~1）：边界线上最大，{@code BOUNDARY_REACH} 处衰减到 ~0。
     * 供调用方按"距边界多近"平滑地施加构造影响（地形类型权重调制等）。
     */
    public static double boundaryStrength(Sample s) {
        if (s.btype() == INTERIOR) return 0.0;
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
        //   而 BOUNDARY_REACH 扩到 1000 后，"非 INTERIOR" 的区域面积增大约 9.8×
        //   （∝d²）→ 若不早退，chainModulation（2×ridgedNoise ≈ 8+ 哈希 + 三角函数）
        //   会在近 10 倍大的区域被白算。此处 `g` 已算出，直接用阈值早退。
        if (g <= 0.01) return 0.0;
        // ★ 连续应力，避免 btype 跳变造成系数骤变
        double cw = Math.max(0.0, s.stress());
        double dw = Math.max(0.0, -s.stress());
        if (cw <= 0.0 && dw <= 0.0) return 0.0;     // 纯走滑：无形变
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
        // ★ 2026-09-12 修复（"地形每块各自独立生成"）：
        //   **不再使用 per-cell 的 alongCoord 作为噪声坐标。**
        //   原实现 (alongCoord, dist) 是"每个 Voronoi 单元各自定义"的坐标系 ——
        //   相邻板块的沿走向基准不同 → 同一世界位置的噪声值不同
        //   → 山链在板块边界处错位，山体纹理方向各自为政（用户截图所见的"各自生成"）。
        //
        //   对照 worldgen：它把 along/across 只用于**标量幅度**，随后 blur_grid
        //   高斯模糊 + 山脊噪声用**世界坐标**。本项目此前两步都没做。
        //
        //   现改为：dist 决定"平行于边界"的几何（全局连续），
        //   碎片化用 **世界坐标噪声**（全局连续）→ 跨板块边界完全无缝。
        double inv = 1.0 / PLATE_SPACING;
        double a = s.dist() * inv;
        double t = (wx + wz) * inv * 0.5;   // 世界坐标的斜向投影：全局一致，无 per-cell 依赖

        // 沿走向低频、垂直走向高频——与 worldgen 的 (along×6, across×18) 同构
        double n = ridgedNoise(t * CHAIN_ALONG_FREQ, a * CHAIN_ACROSS_FREQ, CHAIN_SEED);
        // 映射到 [0.25, 1.0]：保留大部分强度，只在"谷"处压低 → 山峰分明
        return CHAIN_MIN + (1.0 - CHAIN_MIN) * clamp01(n);
    }

    /**
     * 极简 ridged 噪声（零依赖）：{@code 1 - |value noise|}，两层叠加。
     * 不依赖 noise 包（后者引 mojang Codec，会破坏零依赖约定）。
     */
    private double ridgedNoise(double x, double z, long salt) {
        double v = 1.0 - Math.abs(valueNoise(x, z, salt));
        double v2 = 1.0 - Math.abs(valueNoise(x * 2.3 + 5.1, z * 2.3 + 7.7, salt + 1));
        return clamp01(0.65 * v + 0.35 * v2);
    }

    /** 极简 2D value noise（双线性 + smootherstep），返回 [-1,1]。 */
    private double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double sx = fx * fx * fx * (fx * (fx * 6.0 - 15.0) + 10.0);   // smootherstep（5 次）
        double sz = fz * fz * fz * (fz * (fz * 6.0 - 15.0) + 10.0);
        double v00 = cellNoise(ix, iz, salt),     v10 = cellNoise(ix + 1, iz, salt);
        double v01 = cellNoise(ix, iz + 1, salt), v11 = cellNoise(ix + 1, iz + 1, salt);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    /** 格点随机值 [-1,1]。 */
    private double cellNoise(int ix, int iz, long salt) {
        return unit(hash(ix, iz, salt)) * 2.0 - 1.0;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    // ===================== 内部工具 =====================

    /**
     * 只算某点的 stress（<b>不做平滑</b>），供 {@link #sample} 的局部平均调用。
     *
     * <p>与 {@code sample()} 相比省去切向/alongCoord 计算，且<b>不再递归平滑</b>
     * （否则会爆炸）。</p>
     */
    private double stressAt(double wx, double wz) {
        int baseX = (int) Math.floor(wx / PLATE_SPACING);
        int baseZ = (int) Math.floor(wz / PLATE_SPACING);
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        double s1x = 0, s1z = 0, s2x = 0, s2z = 0;
        int c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        double[] sp = new double[2];
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int cx = baseX + dx, cz = baseZ + dz;
                plateSeed(cx, cz, sp);
                double ddx = wx - sp[0], ddz = wz - sp[1];
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) {
                    d2 = d1; c2x = c1x; c2z = c1z; s2x = s1x; s2z = s1z;
                    d1 = d; c1x = cx; c1z = cz; s1x = sp[0]; s1z = sp[1];
                } else if (d < d2) {
                    d2 = d; c2x = cx; c2z = cz; s2x = sp[0]; s2z = sp[1];
                }
            }
        }
        if (d2 == Double.MAX_VALUE) return 0.0;
        double nx = s2x - s1x, nz = s2z - s1z;
        double len = Math.sqrt(nx * nx + nz * nz);
        if (len < 1e-9) return 0.0;
        double ux = nx / len, uz = nz / len;
        double[] v1 = new double[2], v2 = new double[2];
        plateVelocity(c1x, c1z, v1);
        plateVelocity(c2x, c2z, v2);
        double vrx = v1[0] - v2[0], vrz = v1[1] - v2[1];
        return dotCrossToStress(vrx * ux + vrz * uz, Math.abs(vrx * uz - vrz * ux));
    }

    /** dot/cross → 连续应力 ∈[-1,1]（+1 纯汇聚 / −1 纯离散 / 0 走滑）。 */
    private static double dotCrossToStress(double dot, double cross) {
        double mag = Math.sqrt(dot * dot + cross * cross);
        return mag < 1e-12 ? 0.0 : dot / mag;
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
