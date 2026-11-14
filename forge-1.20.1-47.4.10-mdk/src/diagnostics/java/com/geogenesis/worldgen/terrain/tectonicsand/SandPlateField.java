package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱：<b>板块骨架</b>——Voronoi 板块 + 每板块运动/壳属性（纯 Java、零依赖、无限域）。
 *
 * <p><b>唯一借鉴对象：`参考/sources/planet-simulation/planet.js`</b>
 * （用户 2026-11-14 裁定：其余参考项目全部删除——"只有 planet-simulation 能带来明显改善"）。
 * 对应其第 1 段「板块」：随机点定板块 → 每板块一个速度（欧拉极刚性旋转）
 * → 边界按相对速度分级。⚠ 该文件<b>无许可证</b>，只借鉴结构思想，实现独立编写。</p>
 *
 * <p>已删除的旧参考（`civ-atlas` / `procgenesis` / `initialisingearth`）不再引用；
 * 代码里若仍出现 GOSPL / Fastscape 等名字，那是<b>已发表算法/软件的名称</b>，
 * 用于说明公式出处，不代表还在用那些项目。</p>
 *
 * <h3>设计原则（与生产链完全隔离）</h3>
 * <ul>
 *   <li><b>纯函数 + 哈希</b>：无全局状态、无随机数发生器状态 ⇒ 同坐标永远同结果，
 *       无限域 O(1)（板块密度 = 网格间距，3×3 邻域足够）。</li>
 *   <li><b>板块尺度</b>：{@code PLATE_SPACING} 取世界尺度量级，使"一个板块"能容纳
 *       完整的洋脊—洋盆—海沟序列。</li>
 *   <li><b>无限域</b>：不用有限线段（端点判据会把"配对"变成离散维度 ⇒ 阶跃），
 *       一律用<b>垂直平分线距离</b> + 连续权重（见 {@code SandTerrain#boundary}）。</li>
 * </ul>
 *
 * <h3>与既有生产实现的区别（为什么不能用旧的）</h3>
 * <p>旧 {@code TectonicField} 把板块压成"到边界的距离"这一个标量，输出必然是
 * 等值线/斑块。本沙箱保留<b>板块身份</b>与<b>边界类型</b>两个维度，
 * 地形由"边界类型 × 该类型的剖面"决定，而不是由单标量决定。</p>
 */
public final class SandPlateField {

    /**
     * 板块间距（wu）。★ <b>按"从高空看全球板块图"的尺度定</b>。
     *
     * <p>换算：{@code horizontalScale=2.0} ⇒ <b>1 wu = 2 格</b>。</p>
     *
     * <p>用户裁定（2026-11-13）：一个 7000 格（3500wu）的预览窗口里应能看到
     * <b>5×5 个左右的小板块</b>——像地球全球板块图那样，多个板块拼成大陆 + 洋盆。
     * ⇒ 板块间距取 <b>700wu（1400 格）</b>：玩家横穿一块约 5 分钟，
     * 一个窗口看到 5×5 块，既有"板块感"又不失可玩性。</p>
     *
     * <p>每个板块内部再叠加地形量（见 {@code SandTerrain}）——板块只提供
     * "这块是陆还是洋、往哪个方向动"的骨架，细节由边界剖面 + 内部噪声给。</p>
     *
     * <p>⚠ 历史教训：曾设 2600/6000/18000wu，分别只有 1.3/0.6/0.2 个板块在窗口内，
     * 输出是"大块纯色"，完全不像板块构造。用户原话：「我需要拟真但你不要 1:1
     * 全尺寸模拟啊，这样能玩吗？」</p>
     */
    static final double PLATE_SPACING = 700.0;
    /**
     * 种子抖动比例（0=规则网格，1=完全随机）。
     *
     * <p>★ 2026-11-13 沙箱实测：0.62 仍呈<b>规则六边形蜂巢</b>（图 `plates.png`）——
     * 因为抖动幅度上限是 ±0.5 格，网格骨架仍在。取 <b>0.95</b>（接近完全随机）
     * 才打掉骨架。上限 1.0 会与相邻格重叠导致拓扑退化，故留 5% 余量。</p>
     */
    static final double SEED_JITTER = 0.95;
    /** 邻域搜索半径（格）：抖动 0.95 时最近种子可能来自 2 格外 ⇒ 取 2（5×5）。 */
    static final int SEARCH_RADIUS = 2;

    /**
     * 边界线段半长（相对两种子间距）。
     *
     * <p>对应参考 {@code PlateBoundaries.getDistsToLinesSeg} 的"线段"概念：
     * 边界不是无限直线，而是<b>有限长线段</b>。取 0.55 ⇒ 线段长度 ≈ 1.1 个板块间距。
     * 段两端之外距离按"到端点"计算并快速增大 ⇒ 边界效应自然衰减
     * ⇒ 出现<b>段间空隙</b>，那正是<b>转换断层</b>的位置。</p>
     *
     * <p>⚠ 这个常数是"分段感"的唯一来源。之前为了做出分段，额外发明了
     * "沿走向用世界噪声切段"（参考里根本没有），属于自造机制，已删除。</p>
     */
    static final double EDGE_HALF = 0.55;

    /**
     * "近似并列"判定尺度 τ（wu）。
     *
     * <p>{@code dist} 若只取 min，则两线段等距的中轴线上<b>梯度翻转</b>
     * ⇒ 等值线折角 ⇒ 造山带端部渲染成尖角/喙状。这里对
     * {@code d_i − dmin < 3τ} 的候选做加权平均（权重 {@code exp(−Δ/τ)}），
     * 把折角抹圆。τ 取 30wu（60 格）——比造山带宽度（840 格）小得多，
     * 不改变整体形态。</p>
     */
    static final double DIST_TIE_TAU = 30.0;

    /**
     * 边界域扭曲（用 Perlin 型噪声扰动查询点 ⇒ Voronoi 直边变有机曲线）。
     *
     * <p>Voronoi 边是<b>直线</b>，必须扭曲成有机曲线，否则地形上一眼看出是
     * 多边形拼贴。幅度取间距的 36%（0.36×700≈250wu），波长约 1.5 个板块间距。</p>
     *
     * <p>★ 2026-11-13：单个倍频（126wu / 460wu 波长）不足以打断 Voronoi 的
     * <b>六边形骨架</b>——图上一眼是规则蜂巢。改两倍频：大尺度摆动 + 小尺度锯齿。</p>
     */
    static final double WARP_AMP = 250.0;
    static final double WARP_FREQ = 1.0 / 1050.0;
    /** 高频倍频（打断六边形的"直边"）。 */
    static final double WARP_AMP2 = 70.0;
    static final double WARP_FREQ2 = 1.0 / 300.0;
    private static final long SALT_WARP = 0x51AF3C7B19E5D20FL;

    /** 种子哈希盐（互不干扰）。 */
    private static final long SALT_SEED = 0x9E3779B97F4A7C15L;
    private static final long SALT_VEL = 0xBF58476D1CE4E5B9L;

    // ============================================================ 地球化改造（2026-11-14）

    /**
     * 地幔对流场的空间尺度（wu）——板块运动矢量场的波长。
     *
     * <p>取板块间距的 ~6 倍：相邻板块速度相近、沿空间渐进变化
     * ⇒ 汇聚/离散沿长弧段组织。过小 ⇒ 退化回每板随机的碎段。</p>
     */
    static final double FLOW_SCALE = 2000.0;
    private static final long SALT_POLE = 0x3C6EF372FE94F82BL;
    private static final long SALT_FLOWX = 0x1F123BB5L;
    private static final long SALT_FLOWZ = 0x54C1A2E7L;

    private SandPlateField() { }

    // ============================================================ 板块常量

    /**
     * 一个板块的常量属性（不可变）。
     *
     * <p>★ 2026-11-14（地球化改造 ③，地球板块的两个事实：大小悬殊、绕<b>欧拉轴</b>运动）：
     * 板块运动从"每板一个平移速度"改为<b>绕欧拉极的刚性旋转</b>。</p>
     *
     * <p>为什么必须这样：刚性旋转下，<b>板内</b>速度随位置线性变化而<b>应变恒为零</b>
     * （散度=0，只有涡量），但同一条边界两侧的<b>相对速度会沿走向变化</b>
     * ⇒ 边界类型可以"一端汇聚、中段走滑、另一端离散"——这正是地球板块边界的真实样子
     * （如智利型俯冲与走滑段相接）。旧写法每板一个常矢量 ⇒ 一条边界整条一个类型，
     * 且相邻板块的相对速度与几何无关 ⇒ 类型分布看起来随机。</p>
     *
     * @param cx,cz    板块网格坐标（= 种子格）
     * @param sx,sz    种子世界坐标（wu）
     * @param poleX,poleZ 欧拉极位置（wu）
     * @param omega    角速度（rad/单位时间，符号定转向）
     */
    record Plate(int cx, int cz, double sx, double sz,
                 double poleX, double poleZ, double omega) {

        /** 板心速度 x（= 刚性旋转在种子处的速度）。 */
        double vx() {
            return -omega * (sz - poleZ);
        }

        /** 板心速度 z。 */
        double vz() {
            return omega * (sx - poleX);
        }

        /** 该板在任意点的速度 x（刚性旋转：v = ω × r）。 */
        double vxAt(double qx, double qz) {
            return -omega * (qz - poleZ);
        }

        /** 该板在任意点的速度 z。 */
        double vzAt(double qx, double qz) {
            return omega * (qx - poleX);
        }
    }

    // ============================================================ 板块缓存（纯函数缓存）
    //
    // plate() 是纯函数（seed + 格坐标唯一决定），但很贵：
    // siteCell() 最多要扫 7×7 个格找种子，每个 isSite() 又是 2 倍频噪声。
    // 而 platesNear() 一次要 49 个 plate() ⇒ 单次采样可到 ~5e3 次噪声
    // （实测 1000² 出图 70 s）。相邻采样点共享绝大多数格 ⇒ 直接映射缓存后命中率 >95%。

    private static final int CACHE_BITS = 12;
    private static final int CACHE_MASK = (1 << CACHE_BITS) - 1;
    private static final long[] P_CX = new long[CACHE_MASK + 1];
    private static final long[] P_CZ = new long[CACHE_MASK + 1];
    private static final Plate[] P_VAL = new Plate[CACHE_MASK + 1];
    private static long plateCacheSeed = Long.MIN_VALUE;

    private static int cacheIdx(int cx, int cz) {
        long h = (long) cx * 0x9E3779B97F4A7C15L ^ (long) cz * 0xC2B2AE3D27D4EB4FL;
        return (int) ((h ^ (h >>> 29)) & CACHE_MASK);
    }

    static Plate plate(long seed, int cx, int cz) {
        if (seed != plateCacheSeed) {
            java.util.Arrays.fill(P_VAL, null);
            plateCacheSeed = seed;
        }
        int ci = cacheIdx(cx, cz);
        if (P_VAL[ci] != null && P_CX[ci] == cx && P_CZ[ci] == cz) {
            return P_VAL[ci];
        }
        Plate made = plateUncached(seed, cx, cz);
        P_CX[ci] = cx;
        P_CZ[ci] = cz;
        P_VAL[ci] = made;
        return made;
    }

    private static Plate plateUncached(long seed, int cx, int cz) {
        // ★ 尺寸悬殊：无种子格代理到最近的种子格（见 SITE_SEARCH 的说明）。
        //   代理后返回的 Plate 记录带的是<b>种子格</b>的 cx/cz ⇒ 上层用
        //   (cx,cz) 判重即可去重。
        int[] sc = siteCell(seed, cx, cz);
        cx = sc[0];
        cz = sc[1];

        long h = hash(seed, cx, cz, SALT_SEED);
        double sx = (cx + 0.5 + (unit(h) - 0.5) * SEED_JITTER) * PLATE_SPACING;
        double sz = (cz + 0.5 + (unit(h >>> 24) - 0.5) * SEED_JITTER) * PLATE_SPACING;

        long hv = hash(seed, cx, cz, SALT_VEL);
        double target = 0.35 + unit(hv >>> 24) * 0.65;
        // ★ 2026-11-14（地球化改造 ③）：<b>绕欧拉极刚性旋转</b>。旧写法是每板一个常矢量 ⇒ 一条边界整条一个类型。
        //   现在：板绕远处一个极点旋转 ⇒ 板内速度随位置线性变化（应变仍为 0）、
        //   同一边界两侧的相对速度沿走向变化 ⇒ 边界类型可沿走向改变（地球真实特征）。
        //
        //   欧拉极取在离板心 1.5~4 个板间距处（方向随机）；角速度使板心速度 = target。
        long hp = hash(seed, cx, cz, SALT_POLE);
        double poleAng = unit(hp) * Math.PI * 2.0;
        double poleDist = PLATE_SPACING * (1.5 + unit(hp >>> 24) * 2.5);
        double poleX = sx + Math.cos(poleAng) * poleDist;
        double poleZ = sz + Math.sin(poleAng) * poleDist;
        // 转向：由"地幔对流尺度的有序场"给出（相邻板块转向相关 ⇒ 汇聚带成弧）
        double fx = 1.7 * fbmSeeded(seed, sx / FLOW_SCALE, sz / FLOW_SCALE, SALT_FLOWX);
        double fz = 1.7 * fbmSeeded(seed, sx / FLOW_SCALE, sz / FLOW_SCALE, SALT_FLOWZ);
        // 板心速度方向 = (fx,fz) 归一化。用叉积判转向符号。
        // ⚠ 2026-11-14 修正：原式 {@code cross = rx*fz − rz*fx} 的符号与
        //   {@code vx()/vz()} 的叉积约定相反 ⇒ 每个板块的运动方向都<b>与地幔流场反向</b>，
        //   于是全球的汇聚/离散被整体翻转（该造山的地方在裂开，该裂开的地方在造山）。
        //   实测：相邻板块 convRaw 一律 ≈ −0.50（系统性离散）。
        //   正确做法：直接算出 sign=+1 时的板心速度方向，与 f 做点积定号。
        double cvx = Math.sin(poleAng), cvz = -Math.cos(poleAng);   // sign=+1 时的方向
        double sign = (cvx * fx + cvz * fz) >= 0 ? 1.0 : -1.0;
        double omega = sign * target / poleDist;       // 使 |v(板心)| = target

        // ⚠ 2026-11-14 删除：每板一个"陆/洋标志 + 基准海拔"的旧模型。
        //   那是"板块当大陆"的做法，产出的是<b>平顶板块 + 台阶状海岸</b>；
        //   现在陆/洋完全由独立的大陆层 {@code SandContinent} 决定（与板块无关），
        //   板块只负责边界与构造响应。字段留着只会让人再读错地方。
        return new Plate(cx, cz, sx, sz, poleX, poleZ, omega);
    }

    /**
     * 有种子版的双倍频值噪声（σ≈±1）——供"地幔对流场"/"陆壳聚集场"用。
     *
     * <p>⚠ {@link #valueNoise} 不带种子（沙箱地形自用），世界与世界之间
     * 板块运动图案会一模一样，故这里单独带 seed。</p>
     */
    static double fbmSeeded(long seed, double x, double z, long salt) {
        double v = valueNoiseSeeded(seed, x, z, salt)
                + 0.5 * valueNoiseSeeded(seed, x * 2.3 + 11.7, z * 2.3 + 5.9, salt + 7919L);
        return v / 1.5;
    }

    /** 有种子版的双线性值噪声（C¹，σ≈±1）。 */
    private static double valueNoiseSeeded(long seed, double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double tx = fx * fx * (3 - 2 * fx);
        double tz = fz * fz * (3 - 2 * fz);
        double v00 = unit(hash(seed, ix, iz, salt)) * 2.0 - 1.0;
        double v10 = unit(hash(seed, ix + 1, iz, salt)) * 2.0 - 1.0;
        double v01 = unit(hash(seed, ix, iz + 1, salt)) * 2.0 - 1.0;
        double v11 = unit(hash(seed, ix + 1, iz + 1, salt)) * 2.0 - 1.0;
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    // ============================================================ 板块尺寸悬殊（2026-11-14）
    //   地球的板块大小悬殊：太平洋 1.03 亿 km² vs Juan de Fuca 25 万 km²，差 400 倍。
    //   均匀 Voronoi 的板块全都一样大 ⇒ 大陆/大洋没有尺度层次。
    //
    //   实现：<b>占位率场</b>——每个网格格是否撒种子由低频噪声决定。
    //   稀疏区（占位率低）没有种子 ⇒ 邻格的 Voronoi 区被邻近种子吞并 ⇒ <b>大板块</b>；
    //   密集区 ⇒ 小板块。占位率 0.10~1.0 ⇒ 面积差可达 ~10 倍。
    //
    //   ⚠ 与参考的机制不同（2026-11-14 核对）：参考给每个种子一个<b>权重</b>
    //   {@code wts=0.72+0.6·rnd}，归属判据取 {@code argmin |q−s|/w} ⇒ 面积差
    //   (1.32/0.72)² ≈ 3.4 倍。两种做法<b>效果同类</b>（我方甚至更悬殊），
    //   但权重法会改写"边界几何"的全部推导：平分线距离 {@code (d²−d1²)/2L}、
    //   端部渐隐 {@code segFade}、沿走向坐标 {@code along} 都建立在<b>等权</b>
    //   Voronoi 上，换成加权 Voronoi（Apollonius 圆）要整体重推。
    //   收益（同样是"大小悬殊"）远小于风险 ⇒ 保留占位率场。
    //   （此前这里写的理由是"无限域不好枚举种子"——那是错的：{@code |q−s|/w}
    //     就是逐点比较，O(1) 完全可行。真实理由如上。）

    /** 占位率场的波长（格）。6 ⇒ 一片稀疏区覆盖约 6×6 格。 */
    static final double OCC_SCALE = 6.0;
    /** 占位率下限（最稀疏处的撒种概率）。越小大板块越大。 */
    static final double OCC_MIN = 0.10;
    /** 占位率上限。 */
    static final double OCC_MAX = 1.0;
    /** 无种子格向外找最近种子格的搜索半径（格）。 */
    static final int SITE_SEARCH = 3;
    private static final long SALT_OCC = 0x632BE59BD4B5850FL;
    private static final long SALT_OCCJ = 0x2B7E151628AED2A6L;

    /** 该网格格是否<b>撒种子</b>（由低频占位率场 + 每格抖动决定）。 */
    static boolean isSite(long seed, int cx, int cz) {
        double occ = OCC_MIN
                + (OCC_MAX - OCC_MIN) * (0.5 + 0.5 * fbmSeeded(seed,
                        cx / OCC_SCALE, cz / OCC_SCALE, SALT_OCC));
        return unit(hash(seed, cx, cz, SALT_OCCJ)) < occ;
    }

    /**
     * 该网格格对应的<b>有效种子格</b>：自己有种子就用自己；
     * 没有则向外（螺旋，半径 ≤ {@link #SITE_SEARCH}）找最近的种子格。
     *
     * <p>没有种子的格不产生板块 ⇒ 它的区域被邻近种子格的 Voronoi 区吞并
     * ⇒ 那些板块变大。找不到（极端情形）就强制本格，保证处处有板块。</p>
     */
    static int[] siteCell(long seed, int cx, int cz) {
        if (isSite(seed, cx, cz)) {
            return new int[]{cx, cz};
        }
        int bx = cx, bz = cz;
        double bd = Double.MAX_VALUE;
        for (int r = 1; r <= SITE_SEARCH; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;   // 只扫当前环
                    }
                    int nx = cx + dx, nz = cz + dz;
                    if (!isSite(seed, nx, nz)) {
                        continue;
                    }
                    double d = (double) dx * dx + (double) dz * dz;
                    if (d < bd) {
                        bd = d;
                        bx = nx;
                        bz = nz;
                    }
                }
            }
            if (bd < Double.MAX_VALUE) {
                return new int[]{bx, bz};
            }
        }
        return new int[]{cx, cz};   // 兜底：强制本格（保证处处有板块）
    }

    static Plate plate(long seed, double wx, double wz) {
        int cx = (int) Math.floor(wx / PLATE_SPACING);
        int cz = (int) Math.floor(wz / PLATE_SPACING);
        return plate(seed, cx, cz);
    }

    // ============================================================ 采样结果

    /**
     * 板块场采样结果。
     *
     * @param plate      所在板块（最近种子）
     * @param dist       到<b>最近板块边界</b>的距离（wu，≥0）
     * @param neighbor   边界对面的板块（次近种子）；{@code dist} 很大时可忽略
     * @param nx,nz      边界法向单位向量（由本板块指向邻居，连续）
     * @param tx,tz      边界切向单位向量（= 法向旋转 90°）
     * @param along      沿边坐标（wu，相对两种子中点投影；决定"在哪一段"）
     * @param pairHash   配对哈希（本板块 ⊕ 邻居板块；派生段属性用）
     */
    record Sample(Plate plate, Plate neighbor, double dist,
                  double nx, double nz, double tx, double tz,
                  double along, long pairHash,
                  /** 查询点原始世界坐标（未扭曲）。 */
                  double queryX, double queryZ,
                  /**
                   * 查询点在<b>扭曲域</b>中的坐标（= 板块几何真正使用的坐标）。
                   *
                   * <p>★ 2026-11-13 血泪：{@link #sample} 在扭曲域里找最近板块，
                   * 而 {@link SandTerrain#aggregate} / {@link SandTerrain#boundary}
                   * 曾用<b>未扭曲</b>坐标做窗口索引与距离 ⇒ 两者可能差一格，
                   * 真正相邻的那一对<b>根本不在枚举里</b>，导致 {@code direct}
                   * 在最近板块换人时翻符号（实测 −0.222 → +0.052 ⇒ 洋深 62 格阶跃）。
                   * 一切板块几何量必须用这一对坐标。</p>
                   */
                  double warpedX, double warpedZ,
                  /** 世界种子（供邻域查询用）。 */
                  long seed,
                  /**
                   * 端部渐隐乘数 0~1：沿走向离开本段中心越远，值越小。
                   *
                   * <p>这是"边界有限长"的唯一实现方式——<b>乘在效应幅度上</b>，
                   * 而不是截断距离场（截断会在真实 Voronoi 边上留洞 ⇒ 跳变）。</p>
                   */
                  double segFade) {
    }

    /**
     * 主采样：最近板块 + 边界几何。
     *
     * <p>边界 = 两种子连线的<b>垂直平分线</b>（Voronoi 边）。我们只算
     * "到这条平分线的距离"（{@code (d_i² − d1²)/(2·|s_i − s1|)}，对最近邻居取 min），
     * 以及"沿这条平分线的位置"（{@code along}，相对两种子中点）。</p>
     */
    public static Sample sample(long seed, double wx0, double wz0) {
        // 域扭曲：对查询点做连续映射 ⇒ 边界变有机曲线（连续映射，不引入离散维度）
        //   两倍频：低频大摆动 + 高频锯齿（打断 Voronoi 的直边/六边形骨架）
        double wx = wx0 + WARP_AMP * valueNoise(wx0 * WARP_FREQ, wz0 * WARP_FREQ, SALT_WARP)
                + WARP_AMP2 * valueNoise(wx0 * WARP_FREQ2, wz0 * WARP_FREQ2, SALT_WARP + 2);
        double wz = wz0 + WARP_AMP * valueNoise(wx0 * WARP_FREQ + 13.7, wz0 * WARP_FREQ + 29.1,
                SALT_WARP + 1)
                + WARP_AMP2 * valueNoise(wx0 * WARP_FREQ2 + 7.3, wz0 * WARP_FREQ2 + 17.9,
                SALT_WARP + 3);
        int cx0 = (int) Math.floor(wx / PLATE_SPACING);
        int cz0 = (int) Math.floor(wz / PLATE_SPACING);

        Plate p1 = null;
        double d1 = Double.MAX_VALUE;
        // ★ 尺寸悬殊后：无种子格会代理到别处的种子格 ⇒ 邻近种子可能比 SEARCH_RADIUS 更远。
        //   搜索半径加大到 3，且每个格取其<b>代理后</b>的板块记录。
        for (int dx = -SITE_SEARCH; dx <= SITE_SEARCH; dx++) {
            for (int dz = -SITE_SEARCH; dz <= SITE_SEARCH; dz++) {
                Plate p = plate(seed, cx0 + dx, cz0 + dz);
                double ddx = wx - p.sx(), ddz = wz - p.sz();
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < d1) {
                    d1 = d;
                    p1 = p;
                }
            }
        }
        if (p1 == null) {
            throw new IllegalStateException("no plate found");
        }

        // 次近 = 边界对面
        //
        // ★ 2026-11-13 修复（真 bug）：原写法 `if (p == p1) continue;` 用**对象引用**比较，
        //   而 {@link #plate} 每次都 new 一个 record ⇒ 永不相等 ⇒ 次近板块算成"自己"
        //   ⇒ len=0 ⇒ dist=Double.MAX_VALUE ⇒ <b>所有边界效应（造山/裂谷/海沟）全部失效</b>。
        //   实测症状：全图 dist 恒为 1.8e308。改为按<b>格坐标</b>比较。
        //
        // ★ 2026-11-13 二次修复：原来取"<b>种子距离</b>次近"的板块算
        //   {@code dist = (d2²−d1²)/(2·len)}。但"最近边界"不一定是次近种子给的
        //   ——到某条平分线的距离是 {@code (d_i²−d1²)/(2·len_1i)}，最小者才对应最近边界。
        //   实测：相邻两采样点 dist 从 38.4 跳到 208.1（170wu）⇒ 地形 88 格阶跃。
        //   改为<b>对全部邻居取下式的 min</b>（min 连续）：
        //   {@code dist_i = (d_i² − d1²)/(2·|s_i − s_1|)}，并把取到 min 的板块当 neighbor
        //   ⇒ neighbor 是真正的相邻板块，法向 n 也对应真正的边界。
        // ★ 2026-11-14（对照参考项目原文 + 两次踩坑后的终解）：
        //   参考 {@code PlateBoundaries.getDistsToLinesSeg} 用的是<b>真实板块边界的
        //   折线线段</b>（pygplates 给出，天然把球面完整分割）；我们只有 Voronoi 图。
        //
        //   坑 1：直接用"点到无限平分线" ⇒ 边界铺满全图、永不"断" ⇒ 没有
        //         有限长的脊段/山带，也没有段间空隙（转换断层位）。
        //   坑 2：把平分线按 {@code EDGE_HALF·len} 截断再取点到<b>线段</b>距离
        //         ⇒ 真实 Voronoi 边可能比截断长度更长 ⇒ 中午出现<b>空洞</b>。
        //         实测：同一 Voronoi 边两侧，A 侧量到 9.1wu（因为该边在此处"已结束"），
        //         B 侧量到 0.0（它有另一条边经过）⇒ <b>18.1 格台阶 + 图上的折尖/喙状</b>。
        //
        //   终解：<b>距离用完整平分线</b>（无洞 ⇒ 连续），
        //         "边界有限长"改成<b>乘性的端部渐隐</b> {@link #segFade}：
        //         沿走向超过段长后，边界效应的<b>幅度</b>平滑趋于 0。
        //         幅度乘数不会移动等距面 ⇒ 不会造成跳变；段两端自然衰减
        //         ⇒ 有限长脊段 + 段间空隙（= 转换断层）。
        Plate p2 = null;
        double dmin = Double.MAX_VALUE;
        double[] cand = new double[64];
        double candAlong = 0;
        double candHalf = EDGE_HALF;
        int nc = 0;
        // ★ 尺寸悬殊后：无种子格会代理 ⇒ 同一个种子格可能被多个格枚举到
        //   ⇒ 必须按<b>种子格坐标</b>去重（否则加权平均里同一板块被计多次）。
        long[] seenKey = new long[64];
        int nSeen = 0;
        for (int dx = -SITE_SEARCH; dx <= SITE_SEARCH; dx++) {
            for (int dz = -SITE_SEARCH; dz <= SITE_SEARCH; dz++) {
                int cx = p1.cx() + dx, cz = p1.cz() + dz;
                if (dx == 0 && dz == 0) {
                    continue;   // p1 自己
                }
                Plate p = plate(seed, cx, cz);
                long key = (long) p.cx() * 1000003L + p.cz();
                boolean dup = false;
                for (int s = 0; s < nSeen; s++) {
                    if (seenKey[s] == key) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                if (nSeen < seenKey.length) {
                    seenKey[nSeen++] = key;
                }
                double ddx = wx - p.sx(), ddz = wz - p.sz();
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                double sx0 = p.sx() - p1.sx(), sz0 = p.sz() - p1.sz();
                double len0 = Math.sqrt(sx0 * sx0 + sz0 * sz0);
                if (len0 < 1e-9) {
                    continue;
                }
                double ux = sx0 / len0, uz = sz0 / len0;
                // 到平分线的距离（查询点在 p1 一侧 ⇒ ≥0）
                double perp = Math.max(0.0, (d * d - d1 * d1) / (2.0 * len0));
                double midX = (p1.sx() + p.sx()) * 0.5;
                double midZ = (p1.sz() + p.sz()) * 0.5;
                double along = (wx - midX) * (-uz) + (wz - midZ) * ux;
                double half = EDGE_HALF * len0;
                if (nc < cand.length) {
                    cand[nc] = perp;
                    nc++;
                }
                if (perp < dmin) {
                    dmin = perp;
                    p2 = p;
                    candAlong = along;
                    candHalf = half;
                }
            }
        }
        if (p2 == null) {
            return new Sample(p1, p1, Double.MAX_VALUE, 1.0, 0.0, 0.0, 1.0, 0.0, 0L,
                    wx0, wz0, wx, wz, seed, 0.0);
        }
        // 近似并列者的加权平均（抹掉 min 的梯度折点）
        double wSum = 0, dSum = 0;
        for (int k = 0; k < nc; k++) {
            double diff = cand[k] - dmin;
            if (diff > 3.0 * DIST_TIE_TAU) {
                continue;
            }
            double w = Math.exp(-diff / DIST_TIE_TAU);
            wSum += w;
            dSum += w * cand[k];
        }
        double dist = wSum > 0 ? dSum / wSum : dmin;
        // 端部渐隐：|along| 从 0.75·half 到 1.5·half 平滑降到 0
        double a = Math.abs(candAlong);
        double t = (a - 0.75 * candHalf) / Math.max(1e-9, 0.75 * candHalf);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double segFade = 1.0 - t * t * (3.0 - 2.0 * t);

        double sx = p2.sx() - p1.sx();
        double sz = p2.sz() - p1.sz();
        double len = Math.sqrt(sx * sx + sz * sz);
        double nx = len > 1e-9 ? sx / len : 1.0;
        double nz = len > 1e-9 ? sz / len : 0.0;
        double tx = -nz;
        double tz = nx;

        // 沿边坐标（相对两种子中点投影；见 TectonicField.Sample.along 的"为何必须相对"）
        double midX = (p1.sx() + p2.sx()) * 0.5;
        double midZ = (p1.sz() + p2.sz()) * 0.5;
        double along = (wx - midX) * tx + (wz - midZ) * tz;

        long pairHash = hash(seed, p1.cx() * 73856093 ^ p2.cx(), p1.cz() * 19349663 ^ p2.cz(),
                SALT_SEED ^ 0x5DEECE66DL);

        return new Sample(p1, p2, Math.max(0.0, dist), nx, nz, tx, tz, along, pairHash,
                wx0, wz0, wx, wz, seed, segFade);
    }

    // ============================================================ 哈希工具

    /**
     * 双线性值噪声（输出约 ±1，C¹ 连续）——沙箱自包含，不依赖生产 noise 包。
     */
    static double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        // smoothstep 插值（C¹）
        double tx = fx * fx * (3 - 2 * fx);
        double tz = fz * fz * (3 - 2 * fz);
        double v00 = unit(hash(0L, ix, iz, salt)) * 2.0 - 1.0;
        double v10 = unit(hash(0L, ix + 1, iz, salt)) * 2.0 - 1.0;
        double v01 = unit(hash(0L, ix, iz + 1, salt)) * 2.0 - 1.0;
        double v11 = unit(hash(0L, ix + 1, iz + 1, salt)) * 2.0 - 1.0;
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    static long hash(long seed, int x, int z, long salt) {
        long h = (long) x * 374761393L + (long) z * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        h = (h ^ (h >>> 16));
        h = h * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 29);
    }

    /** {@code h} → [0,1)。 */
    static double unit(long h) {
        return ((h >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) 0x20000000000000L;
    }

    /** 邻域板块（供连续加权平均用；默认 3 格半径，覆盖 3.5σ）。 */
    static List<Plate> platesNear(long seed, double wx, double wz) {
        return platesNear(seed, wx, wz, 3);
    }

    /**
     * 邻域板块（去重后）。
     *
     * <p>★ 尺寸悬殊后：无种子格会<b>代理</b>到别处的种子格 ⇒ 不同格可能给出
     * 同一个板块记录。加权平均里同一板块被计多次会把权重算歪
     * （实测： strain 被少数大板块主导），必须按<b>种子格坐标</b>去重。</p>
     */
    static List<Plate> platesNear(long seed, double wx, double wz, int radius) {
        int cx0 = (int) Math.floor(wx / PLATE_SPACING);
        int cz0 = (int) Math.floor(wz / PLATE_SPACING);
        List<Plate> out = new ArrayList<>();
        long[] seen = new long[(2 * radius + 1) * (2 * radius + 1)];
        int nSeen = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Plate p = plate(seed, cx0 + dx, cz0 + dz);
                long key = (long) p.cx() * 1000003L + p.cz();
                boolean dup = false;
                for (int s = 0; s < nSeen; s++) {
                    if (seen[s] == key) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                if (nSeen < seen.length) {
                    seen[nSeen++] = key;
                }
                out.add(p);
            }
        }
        return out;
    }
}
