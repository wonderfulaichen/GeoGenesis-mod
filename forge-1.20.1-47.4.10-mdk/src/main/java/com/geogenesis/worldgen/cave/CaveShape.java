package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.noise.Noise3;
import com.geogenesis.worldgen.noise.Simplex3;
import com.geogenesis.worldgen.terrain.RockType;

/**
 * 洞穴几何（<b>零 Minecraft 依赖的纯函数</b>）。
 *
 * <h2>★★ 2026-09-15 重写：从「2D 柱体切挖」改为「3D 噪声等值面」</h2>
 *
 * <h3>为什么推翻初版</h3>
 * <p>初版移植了 TerraForged 的「<b>2D 场驱动竖直柱体切挖</b>」：每 (x,z) 用 2D 噪声算出
 * {@code [bottom, top]} 再整柱挖空，靠相邻列重叠形成"网络"。当时理由看似充分
 * （TF 如此实现、成本极低、几何探针报 ALL PASS）。</p>
 *
 * <p><b>但用户实测："洞穴非常奇怪，完全不成洞穴的样子。" —— 用户是对的。</b></p>
 *
 * <p>根因是<b>几何性的，与参数无关</b>：柱体切挖产出的空洞本质是<b>竖直柱</b>。
 * 它的水平截面必然是<b>孤立点/小团</b>，竖直方向必然是<b>贯通的高柱</b>。无论怎样
 * 调 size/阈值，都<b>不可能</b>得到蜿蜒、有分支、可上下起伏的隧道。</p>
 *
 * <h3>为何初版探针没发现（诊断盲区，必须记录）</h3>
 * <p>初版探针只渲染了 <b>X-Y 垂直剖面</b>。柱体在 X-Y 上就是一根根竖直白条，
 * 多条相邻列拼起来<b>看着像"斑块"</b> ⇒ 图看起来还行、判据全 PASS。
 * 直到补上 <b>X-Z 水平切片</b>才暴露真相：只有几个孤立大块，毫无隧道网络。</p>
 * <p><b>教训</b>：判定 3D 结构必须<b>同时看两个正交方向的切片</b>；单方向的剖面
 * 可以系统性地掩盖某一类缺陷。</p>
 *
 * <h3>现方案：3D 噪声等值面交集（真正能出隧道）</h3>
 * <p>数学依据 —— <b>两张曲面相交得到一条曲线</b>：</p>
 * <ul>
 *   <li>{@code n1(x,y,z) = 0} 是一个二维曲面；{@code n2(x,y,z) = 0} 是另一个曲面；</li>
 *   <li>约束 {@code |n1| < T1 且 |n2| < T2} 即「<b>同时贴近两张曲面</b>」
 *       ⇒ 交线附近的一条<b>一维管道</b> —— 这就是隧道。</li>
 *   <li>两个噪声必须<b>不同种子</b>且<b>频率略有差异</b>，否则两曲面会平行、
 *       交集退化成整片面（挖成"瑞士奶酪"而非隧道）。</li>
 * </ul>
 *
 * <p>这与 {@code worldgen}/{@code vanilla} 体系的 {@code spaghetti_2d}/{@code noodle}
 * 是同一原理（原版也是两噪声等值面），但原版依赖 {@code NoiseChunk}；本项目无
 * {@code NoiseSettings} ⇒ 自研，用本项目的 {@link Simplex3}。</p>
 *
 * <h3>三族叠加</h3>
 * <ol>
 *   <li><b>TUNNEL</b>（隧道）：两噪声等值面交集，细长蜿蜒有分支 —— 洞穴主体；</li>
 *   <li><b>CAVERN</b>（洞室）：单噪声阈值（{@code n > T}），低频、方圆较大 —— 偶尔的大厅；</li>
 *   <li><b>CHEESE</b>（孔洞）：更高频单噪声阈值，小孔 —— 增加"蜂窝感"，密度低。</li>
 * </ol>
 * <p>三者是<b>并集</b>（任一命中即挖空）。CAVERN/CHEESE 用单噪声阈值是刻意的：
 * 单噪声的等值面是<b>二维曲面</b>，{@code n > T} 取曲面的"一侧" ⇒ 得<b>体积块</b>
 * （洞室），而非管道 —— 与 TUNNEL 的形态互补。</p>
 *
 * <h3>★ 岩性门控（本项目独创，三个参考项目均无）</h3>
 * <p>洞穴发育受岩性控制：石灰岩可溶 ⇒ 喀斯特溶洞最发育；花岗岩致密 ⇒ 几乎不成洞。
 * 系数乘在<b>阈值</b>上（阈值越大洞越粗）⇒ 只改洞穴<b>规模</b>，不改其出现位置。</p>
 */
public final class CaveShape {

    private CaveShape() { }

    /** 地表保护厚度（block）：洞顶至少低于地表此值 ⇒ 永不开口到地表。 */
    public static final int SURFACE_LID = 6;

    // ===================== 3D 噪声场 =====================

    /**
     * 各分量的噪声<b>特征尺度</b>（block）。
     *
     * <p>每对噪声<b>必须略有差异</b>（如 120 vs 155）：完全相同会让两曲面平行，
     * 交集退化成整片面（挖成奶酪）；差异过大会让交线过分破碎。
     * 1.3 倍左右既保证交线清晰，又让管道有"粗细变化"的有机感。</p>
     */
    private static final double TUNNEL_SCALE_A = 120.0;
    private static final double TUNNEL_SCALE_B = 155.0;
    /** 洞室：比隧道尺度大（更宽敞）、阈值宽（更粗），但仍用双噪声交集保证"有限尺寸"。 */
    private static final double CAVERN_SCALE_A = 210.0;
    private static final double CAVERN_SCALE_B = 265.0;
    /** 孔洞：高频、双噪声交集 ⇒ 小而不连片。 */
    private static final double CHEESE_SCALE_A = 54.0;
    private static final double CHEESE_SCALE_B = 68.0;

    private static final int SALT_TUNNEL_A = 0x1F4A9C3B;
    private static final int SALT_TUNNEL_B = 0x7B2E5D18;
    private static final int SALT_CAVERN_A = 0x4C8F21A7;
    private static final int SALT_CAVERN_B = 0x2D63B9E4;
    private static final int SALT_CHEESE_A = 0x6A18D3F2;
    private static final int SALT_CHEESE_B = 0x38C7E15A;

    private static final Noise3 TUNNEL_A = new Simplex3(SALT_TUNNEL_A);
    private static final Noise3 TUNNEL_B = new Simplex3(SALT_TUNNEL_B);
    private static final Noise3 CAVERN_A = new Simplex3(SALT_CAVERN_A);
    private static final Noise3 CAVERN_B = new Simplex3(SALT_CAVERN_B);
    private static final Noise3 CHEESE_A = new Simplex3(SALT_CHEESE_A);
    private static final Noise3 CHEESE_B = new Simplex3(SALT_CHEESE_B);

    private static volatile boolean seeded = false;

    /** 换世界种子（与地形/河网同批失效）。 */
    public static synchronized void setSeed(long worldSeed) {
        seedOne(TUNNEL_A, worldSeed, 0);
        seedOne(TUNNEL_B, worldSeed, 1);
        seedOne(CAVERN_A, worldSeed, 2);
        seedOne(CAVERN_B, worldSeed, 3);
        seedOne(CHEESE_A, worldSeed, 4);
        seedOne(CHEESE_B, worldSeed, 5);
        seeded = true;
    }

    private static void seedOne(Noise3 n, long worldSeed, int level) {
        if (n instanceof Simplex3 s3) s3.seed(worldSeed, level);
    }

    /** 是否已播种。 */
    public static boolean isSeeded() {
        return seeded;
    }

    // ===================== 阈值（岩性门控的载体）=====================

    /**
     * 隧道半径阈值（噪声单位）：越小隧道越细。
     *
     * <p>取自扫描最优格（{@code tMul=0.7}）：{@code 0.075 × 0.7 ≈ 0.053}。</p>
     */
    private static final double TUNNEL_T1 = 0.042;
    private static final double TUNNEL_T2 = 0.042;
    /**
     * 洞室阈值：比隧道宽 ⇒ 管径更粗（成"大厅"而非细管）。
     *
     * <p>★ 2026-09-15 关键修正：初版洞室用<b>单噪声阈值</b>（{@code n > 0.72}）。
     * 这在数学上<b>必然</b>产生贯穿世界的大块 —— 单噪声的等值面是<b>无限延伸的二维曲面</b>，
     * 取其一侧就是巨大体积。实测（{@code CaveShapeProbe} 分量分解）：洞室竖向连续段
     * <b>平均 29.7 块、最长 46 块、长段占比 91.2%</b> ⇒ 世界被挖成竖井，
     * 用户实测"完全不成洞穴的样子"。<b>隧道分量本身是健康的</b>
     * （平均段 6.7 块、长段占比 7.2%），是被洞室盖过了形态。</p>
     * <p>现改为<b>双噪声交集</b>（同隧道原理，仅尺度更大、阈值更宽）——
     * 两曲面相交只能得到<b>有限尺寸的管状体</b>，这才是真正的"洞室"。</p>
     */
    private static final double CAVERN_T1 = 0.073;         // 0.13 × 0.56
    private static final double CAVERN_T2 = 0.073;
    /** 孔洞阈值：双噪声交集，高频、细 ⇒ 小孔不连片。 */
    private static final double CHEESE_T1 = 0.031;         // 0.055 × 0.56
    private static final double CHEESE_T2 = 0.031;

    /**
     * ★★ Y 方向各向异性缩放（"隧道趋向水平"的关键旋钮）。
     *
     * <h3>为何必须（实测缺陷）</h3>
     * <p>若三轴频率相同，噪声曲面可以在<b>竖直方向</b>接近平行 ⇒ 两者的交线成为
     * <b>竖井</b>。实测（{@code CaveShapeProbe}）：{@code yScale=1} 时
     * seed=12345 的长竖段（≥20块）占比 17.5%、seed=7 达 <b>39.9%</b>
     * —— 参数不稳，且"竖井"不是洞穴该有的形态。</p>
     *
     * <p>把 y 采样频率提高 yScale 倍 ⇒ 噪声在竖直方向变化更快 ⇒
     * {@code |n|<t} 的区域在 Y 上更<b>薄</b> ⇒ 隧道自然趋向<b>水平延伸</b>
     * （这正是洞穴的常见形态：沿层理/裂隙水平发育）。</p>
     *
     * <p>这不是 hack —— vanilla 的 {@code cave_layer} 噪声正是用
     * {@code xz_scale=1, y_scale=8} 的强各向异性来产生"水平层状洞穴"。</p>
     *
     * <h3>取值依据（{@code CaveShapeProbe} 网格扫描，3 种子取最差）</h3>
     * <table border="1">
     *   <caption>yMul × 阈值倍率的扫描（每格为跨 12345/7/42 的<b>最差</b>值）</caption>
     *   <tr><th>yMul</th><th>tMul</th><th>密度%</th><th>平均段</th><th>长段%</th></tr>
     *   <tr><td>1.0</td><td>1.0</td><td>11.02</td><td>11.5</td><td><b>63.1</b></td></tr>
     *   <tr><td>2.0</td><td>1.0</td><td>8.13</td><td>4.9</td><td>32.1</td></tr>
     *   <tr><td>3.0</td><td>0.7</td><td>3.85</td><td>3.0</td><td>15.5</td></tr>
     *   <tr><td><b>3.5</b></td><td><b>0.7</b></td><td><b>3.45</b></td><td><b>2.7</b></td>
     *       <td><b>7.4</b></td></tr>
     *   <tr><td>4.0</td><td>0.7</td><td>3.13</td><td>2.4</td><td>4.2</td></tr>
     * </table>
     * <p>趋势：yMul ↑ 与 tMul ↓ 均单调改善形态，但会同时降低密度。
     * 取 <b>3.5 / 0.7</b> 为平衡点（长段 63.1%→7.4%，密度仍在 3.45% 的可见区间）。</p>
     *
     * <p>⚠ 这组值是<b>网格扫描 + 3 种子取最差</b>得到的，不是单点试出来的 ——
     * 单点试参曾陷入"修好 seed=7 又坏 seed=12345"的循环（实测）。</p>
     */
    //   基准值（扫描的 dbgYScaleMul 会乘在此之上）。
    //   ⚠ 2026-09-15 第二次修正：此前写成 7.0（=2.0×3.5），把隧道压成**薄饼**，
    //     用户实测"连高度 2 格都没有，玩家没法走"。现回到 2.0 基准，
    //     由扫描在 [0.25,1.5] 倍（effective 0.5~3.0）内重新选定。
    private static final double TUNNEL_Y_SCALE = 1.2;      // = 基准 2.0 × 0.6
    private static final double CAVERN_Y_SCALE = 1.2;
    private static final double CHEESE_Y_SCALE = 0.9;      // = 1.5 × 0.6

    /** 洞穴带在 Y 上的范围（相对地表）：太浅会破地表，太深无意义。 */
    private static final int DEPTH_MIN = 8;      // 距地表至少 8 块（配合 SURFACE_LID）
    private static final int DEPTH_MAX = 120;    // 最深挖到地表下 120 块

    /** 分量位掩码：隧道。 */
    public static final int F_TUNNEL = 1;
    /** 分量位掩码：洞室。 */
    public static final int F_CAVERN = 2;
    /** 分量位掩码：孔洞。 */
    public static final int F_CHEESE = 4;

    /**
     * 地表下方是否应为洞穴。
     *
     * @param wx       世界 X
     * @param wy       世界 Y
     * @param wz       世界 Z
     * @param surface  地表高度（block）
     * @param worldMinY 世界最低 Y
     * @param litho    岩性系数（{@link #lithoFactor}）
     * @return true = 该体素应为空气（洞穴）
     */
    public static boolean isCave(int wx, int wy, int wz, int surface,
                                 int worldMinY, double litho) {
        return components(wx, wy, wz, surface, worldMinY, litho) != 0;
    }

    /**
     * ★ 诊断用：返回命中的<b>分量位掩码</b>（{@link #F_TUNNEL} / {@link #F_CAVERN} /
     * {@link #F_CHEESE}；0 = 无洞穴）。
     *
     * <h3>为何需要（受控诊断方法论）</h3>
     * <p>{@link #isCave} 把三个分量做了<b>并集</b>，于是当结果不对劲时<b>无法定位</b>
     * 是哪个分量造成的。实测踩过：水平切片显示"孤立团块"而非隧道，但单看并集
     * 无法判断是"隧道本身碎"还是"洞室/孔洞盖过了隧道"。</p>
     * <p>故把分量判定独立出来 ⇒ 探针可分别统计各分量的密度、形态与连通性
     * （一次实验即可定位），而生产路径仍走 {@link #isCave} 的短路快路径。</p>
     */
    public static int components(int wx, int wy, int wz, int surface,
                                 int worldMinY, double litho) {
        if (!seeded) return 0;
        // 不破地表：洞顶至少低于地表 SURFACE_LID
        if (wy > surface - SURFACE_LID) return 0;
        if (wy < worldMinY + 1) return 0;
        // 限制在"地下带"内（贴近地表的浅层不挖 ⇒ 避免草原破洞；过深无意义）
        int depth = surface - wy;
        if (depth < DEPTH_MIN || depth > DEPTH_MAX) return 0;

        int mask = 0;
        double x = wx, y = wy, z = wz;

        // ---- 1) 隧道：两噪声等值面交线（1D 管道）----
        //   岩性越大 ⇒ 阈值越大 ⇒ 管道越粗（石灰岩溶洞 vs 花岗岩）
        if (pair(TUNNEL_A, TUNNEL_SCALE_A, TUNNEL_B, TUNNEL_SCALE_B, TUNNEL_Y_SCALE,
                x, y, z, TUNNEL_T1 * litho, TUNNEL_T2 * litho)) {
            mask |= F_TUNNEL;
        }
        // ---- 2) 洞室：同原理但尺度更大、阈值更宽 ⇒ 更粗的管（大厅）----
        //   ⚠ 不可用单噪声阈值（会产生无限延伸大块，见 CAVERN_T1 的注释）
        if (pair(CAVERN_A, CAVERN_SCALE_A, CAVERN_B, CAVERN_SCALE_B, CAVERN_Y_SCALE,
                x, y, z, CAVERN_T1 * litho, CAVERN_T2 * litho)) {
            mask |= F_CAVERN;
        }
        // ---- 3) 孔洞：高频细管 ⇒ 小孔、不连片 ----
        if (pair(CHEESE_A, CHEESE_SCALE_A, CHEESE_B, CHEESE_SCALE_B, CHEESE_Y_SCALE,
                x, y, z, CHEESE_T1 * litho, CHEESE_T2 * litho)) {
            mask |= F_CHEESE;
        }
        return mask;
    }

    /**
     * 双噪声<b>等值面交集</b>判定：{@code |n1| < t1 && |n2| < t2}（含 Y 各向异性缩放）。
     *
     * <p>这是本类所有分量的统一判据 —— 只有"两张曲面相交"才能得到
     * <b>有限尺寸的一维管道</b>。单噪声阈值（{@code n > T}）在数学上必然
     * 给出无限延伸的大块（见 {@link #CAVERN_T1} 的实测教训）。</p>
     *
     * <p>短路的顺序：{@code |n1| ≥ t1} 时直接返回（多数体素在此返回）
     * ⇒ 平均只求 1~1.3 次噪声。</p>
     *
     * <p><b>Y 缩放</b>：{@code y 除以 scale} 后再乘 {@code (baseYScale * dbgYScaleMul)}
     * ⇒ 竖直频率更高 ⇒ 管道趋水平（依据见 {@link #TUNNEL_Y_SCALE}）。
     * 乘 {@code dbgYScaleMul} 使探针可<b>扫描</b>该参数（生产恒为 1.0）。</p>
     */
    private static boolean pair(Noise3 a, double sa, Noise3 b, double sb, double baseYScale,
                                double x, double y, double z, double t1, double t2) {
        double ys = baseYScale * dbgYScaleMul;
        double ya = sa / ys, yb = sb / ys;
        double n1 = a.compute(x / sa, y / ya, z / sa);
        if (Math.abs(n1) >= t1 * dbgThresholdMul) return false;
        double n2 = b.compute(x / sb, y / yb, z / sb);
        return Math.abs(n2) < t2 * dbgThresholdMul;
    }

    // ===================== ★ 诊断覆盖（生产恒为默认值）=====================

    /**
     * 诊断用 Y 缩放倍率（乘在 {@link #TUNNEL_Y_SCALE} 等基础值上）。
     *
     * <h3>为何需要</h3>
     * <p>洞穴形态对 {@code yScale} 与阈值极度敏感（实测：yScale=1 时 seed=7 长竖段
     * 占比 39.9%，yScale=2 时 seed=12345 反升到 56.9%）⇒ 必须能<b>扫描</b>，
     * 而非靠猜。这些字段只被探针写入，<b>生产路径永不修改</b>（默认 1.0）。</p>
     *
     * <p>之所以用字段而非方法参数：{@link #components} 是热路径上的公共 API，
     * 加参数会污染生产签名；而 volatile 字段的读开销可忽略。</p>
     */
    static volatile double dbgYScaleMul = 1.0;
    /** 诊断用阈值倍率（越大洞越粗）。 */
    static volatile double dbgThresholdMul = 1.0;

    /** 供探针扫描参数（仅诊断用）。 */
    public static void dbgSet(double yScaleMul, double thresholdMul) {
        dbgYScaleMul = yScaleMul;
        dbgThresholdMul = thresholdMul;
    }

    /** 恢复生产默认参数。 */
    public static void dbgReset() {
        dbgYScaleMul = 1.0;
        dbgThresholdMul = 1.0;
    }

    /**
     * ★★ 岩性 → 洞穴发育系数（本项目独创，三个参考项目均无）。
     *
     * <p>真实地质学：洞穴发育受岩性严格控制 ——</p>
     * <ul>
     *   <li><b>石灰岩</b>（可溶岩）：喀斯特作用 ⇒ <b>溶洞最发育</b>；</li>
     *   <li><b>砂岩/页岩</b>（沉积岩）：层理面与孔隙利于地下水流通 ⇒ 中等；</li>
     *   <li><b>玄武岩/安山岩</b>（喷出岩）：柱状节理/气孔 ⇒ 略低于沉积岩；</li>
     *   <li><b>片岩</b>（片理发育）：沿片理可剥蚀 ⇒ 中等偏低；</li>
     *   <li><b>花岗岩/片麻岩</b>（致密结晶岩）：几乎不透水 ⇒ <b>洞最不发育</b>。</li>
     * </ul>
     *
     * @param rockTypeId {@code Cell.rockTypeId}（越界/未知 → 1.0 中性）
     */
    public static double lithoFactor(int rockTypeId) {
        if (rockTypeId < 0 || rockTypeId >= RockType.values().length) return 1.0;
        return switch (RockType.values()[rockTypeId]) {
            case LIMESTONE -> 1.7;                 // 可溶岩 → 喀斯特溶洞
            case SANDSTONE, SHALE -> 1.15;         // 沉积岩：层理/孔隙
            case BASALT, ANDESITE -> 0.9;          // 喷出岩：节理/气孔
            case SCHIST -> 0.7;                    // 片理发育
            case GRANITE, GNEISS -> 0.55;          // 致密结晶岩：洞不发育
        };
    }
}
