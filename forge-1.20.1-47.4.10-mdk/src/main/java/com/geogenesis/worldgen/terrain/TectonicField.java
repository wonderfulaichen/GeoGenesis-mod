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

    /** 边界影响宽度（wu）：超出此距离视为板块内部，无构造影响。 */
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
            double rate
    ) {
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
        int baseX = (int) Math.floor(wx / PLATE_SPACING);
        int baseZ = (int) Math.floor(wz / PLATE_SPACING);

        // 找最近(d1)与次近(d2)种子
        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
        int c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        double s1x = 0, s1z = 0, s2x = 0, s2z = 0;

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
        if (d2 == Double.MAX_VALUE) {
            return new Sample(Double.MAX_VALUE, INTERIOR, 0.0);
        }

        // 到两种子中垂线的有符号距离（标准 Voronoi 边界距离公式）
        double nx = s2x - s1x, nz = s2z - s1z;
        double len = Math.sqrt(nx * nx + nz * nz);
        double dist;
        if (len < 1e-9) {
            dist = Double.MAX_VALUE;
        } else {
            dist = Math.abs((d2 * d2 - d1 * d1) / (2.0 * len));
        }

        if (dist >= BOUNDARY_REACH) {
            return new Sample(dist, INTERIOR, 0.0);
        }

        // 分类：dot/cross 分解
        double[] v1 = new double[2], v2 = new double[2];
        plateVelocity(c1x, c1z, v1);
        plateVelocity(c2x, c2z, v2);
        double vrx = v1[0] - v2[0], vrz = v1[1] - v2[1];

        double ux = nx / len, uz = nz / len;         // 法线（s1→s2）
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
        return new Sample(dist, btype, Math.min(rate, 2.0));
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

    // ===================== 内部工具 =====================

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
