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
    /**
     * ★★ 空腔（原版 {@code cave_cheese}）的噪声特征尺度。
     *
     * <p>原版 {@code Noises.CAVE_CHEESE} 的单元格约 64 格；这里取 55（略密），
     * 目的是产生<b>可以站进去走动</b>的大空间 —— 这是"玩家能在洞里走"的主要来源。</p>
     */
    private static final double CHAMBER_SCALE = 55.0;
    /**
     * ★★ 层调制（原版 {@code cave_layer}）的噪声 —— <b>防止空腔竖直贯穿的关键</b>。
     *
     * <p>原版：{@code caveLayer = 4 × noise(CAVE_LAYER, yScale=8)²}，把它<b>加到密度上</b>
     * （恒 ≥0）⇒ 在噪声绝对值大的 Y 层，密度被推回正值（实心）
     * ⇒ 把本会连通成大块的空间<b>切成一层层有限高度的空腔</b>。</p>
     *
     * <p><b>这正是我前两版失败的根因</b>：第一版空腔没有层调制 ⇒ 竖直贯穿
     * （长段占比 91.2%，世界被挖成竖井）；而我当时的"修复"是改用纯管道，
     * 结果只留下细管道、丢了大空间 ⇒ 用户实测"没法走"。</p>
     */
    private static final double LAYER_SCALE = 30.0;
    /** 层调制在 Y 方向的频率倍率（原版 yScale=8；越大 ⇒ 层越薄）。 */
    private static final double LAYER_Y_SCALE = 3.0;

    private static final int SALT_TUNNEL_A = 0x1F4A9C3B;
    private static final int SALT_TUNNEL_B = 0x7B2E5D18;
    private static final int SALT_CHAMBER = 0x4C8F21A7;
    private static final int SALT_LAYER = 0x2D63B9E4;

    private static final Noise3 TUNNEL_A = new Simplex3(SALT_TUNNEL_A);
    private static final Noise3 TUNNEL_B = new Simplex3(SALT_TUNNEL_B);
    private static final Noise3 CHAMBER = new Simplex3(SALT_CHAMBER);
    private static final Noise3 LAYER = new Simplex3(SALT_LAYER);

    private static volatile boolean seeded = false;

    // ===================== ★ 配置注入（2026-09-15 可开关配置）=====================

    /**
     * 当前生效的洞穴配置（默认拟真档）。
     *
     * <p>由 {@link #setConfig} 注入；{@link #configured()} 会解析到具体旋钮。
     * 生产路径读的是<b>解析后的字段</b>（{@link #cfgEnabled} 等）而非每次都解包
     * {@link CaveConfig} 对象 —— 这些字段在热路径（逐体素）上被读，
     * 必须是简单 volatile 读（与既有的 {@code dbg*} 倍率同款，零分配零分支成本）。</p>
     */
    private static volatile CaveConfig cfg = CaveConfig.DEFAULT;

    /** 解析后的启用开关（热路径读这个，避免逐体素访问对象字段链）。 */
    private static volatile boolean cfgEnabled = true;
    /** 解析后：隧道分量是否启用。 */
    private static volatile boolean cfgTunnel = true;
    /** 解析后：空腔分量是否启用。 */
    private static volatile boolean cfgChamber = true;
    /** 解析后：层调制是否启用。 */
    private static volatile boolean cfgLayer = true;
    /** 解析后：岩性门控是否启用。 */
    private static volatile boolean cfgLitho = true;
    /** 解析后：密度倍率（乘在分量阈值上）。 */
    private static volatile double cfgDensityMul = 1.0;
    /** 解析后：洞顶保护厚度（{@code public SURFACE_LID} 的运行时值）。 */
    private static volatile int cfgSurfaceLid = 6;
    /** 解析后：洞穴带最浅/最深深度。 */
    private static volatile int cfgDepthMin = 8;
    private static volatile int cfgDepthMax = 120;

    /**
     * 注入洞穴配置（与 {@link #setSeed} 同批调用，保证换世界/改配置后一致）。
     *
     * <p><b>为何"解析成字段"而不是直接读 {@code CaveConfig}</b>：{@link #components}
     * 是<b>逐体素</b>热路径（每 chunk 数万次调用）⇒ 每次沿
     * {@code cfg.preset.…} 访问对象字段链既慢又难保证并发可见性。
     * 这里一次性展开成扁平 volatile 字段，热路径只做简单读。</p>
     */
    public static synchronized void setConfig(CaveConfig config) {
        CaveConfig c = config != null ? config : CaveConfig.DEFAULT;
        cfg = c;
        cfgEnabled = c.enabled;
        cfgTunnel = c.tunnelEnabled;
        cfgChamber = c.chamberEnabled;
        cfgLayer = c.layerEnabled;
        cfgLitho = c.lithoGating;
        cfgDensityMul = c.densityMul;
        cfgSurfaceLid = c.surfaceLid;
        cfgDepthMin = c.depthMin;
        cfgDepthMax = c.depthMax;
    }

    /** 当前配置。 */
    public static CaveConfig config() {
        return cfg;
    }

    /**
     * 洞穴是否启用（<b>总开关</b>）。{@code false} ⇒ {@link CaveCarver} 直接返回，
     * 地下无任何洞穴（同 RTG 的 {@code useCaves=false} 语义）。
     */
    public static boolean isEnabled() {
        return cfgEnabled;
    }

    /** 换世界种子（与地形/河网同批失效）。 */
    public static synchronized void setSeed(long worldSeed) {
        seedOne(TUNNEL_A, worldSeed, 0);
        seedOne(TUNNEL_B, worldSeed, 1);
        seedOne(CHAMBER, worldSeed, 2);
        seedOne(LAYER, worldSeed, 3);
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
    /**
     * ★ 空腔判定阈值（原版 {@code 0.27} 偏置）。
     *
     * <p>原版：{@code density = 0.27 + (1-prob) + cheeseNoise + caveLayer + depthTerm}，
     * {@code density < 0} 即挖空。取 {@code prob=1.0}、忽略 depthTerm 后即
     * {@code cheeseNoise < -0.27 - layerTerm}。</p>
     *
     * <p>这里 {@code CHAMBER_T = 0.27}：噪声低于 {@code -0.27} 的区域成空腔。
     * 由于噪声分布近高斯，这大约是 25~30% 的体积 —— 但会被<b>层调制</b>大量抵消，
     * 最终只留下"层与层之间"的有限空腔（这正是原版的效果）。</p>
     */
    private static final double CHAMBER_T = 0.432;        // 0.27 × 1.6（扫描标定）
    /**
     * 层调制的强度（原版 {@code caveLayer = 4 × n²} 中的 4）。
     *
     * <p>该值直接决定"空腔被切成多薄"：越大 ⇒ 层越容易把密度推回实心 ⇒ 空腔越薄、
     * 越不会竖直贯穿。原版取 4（相对其噪声幅度），这里因噪声归一化不同取 0.55，
     * 由 {@code CaveShapeProbe} 扫描标定。</p>
     */
    private static final double LAYER_W = 1.925;          // 0.55 × 3.5（扫描标定）
    /** 空腔所需的最小埋深（避免贴近地表塌陷感）。 */
    private static final int CHAMBER_MIN_DEPTH = 14;

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
    // ⚠ 2026-09-15 核实：上面两个常量（CAVERN_Y_SCALE / CHEESE_Y_SCALE）
    //   【已声明但从未被 components() 使用】—— 空腔的 Y 各向异性硬编码为
    //   下面的 `* 1.5`。保留是为了不破坏历史注释的可追溯性；
    //   若日后要把"空腔 Y 缩放"做成配置项，应改用这两个常量而非字面量 1.5。

    /**
     * 洞穴带在 Y 上的范围（相对地表）：太浅会破地表，太深无意义。
     *
     * <p>★ 2026-09-15 改为 {@code public}：矿脉的"洞壁露头"联动需要知道
     * <b>洞穴可能存在的深度窗口</b> —— 超出此窗口即不可能有洞穴，
     * 该处的"紧邻洞穴"判定恒为 false。若探针硬编码该值会与生产<b>漂移</b>，
     * 故开放为公开常量，两边共用同一口径。</p>
     */
    public static final int DEPTH_MIN = 8;      // 距地表至少 8 块（配合 SURFACE_LID）
    public static final int DEPTH_MAX = 120;    // 最深挖到地表下 120 块

    // ===== ★ 运行时取值（配置可覆盖上面的默认常量）=====
    //   为何保留常量又新增访问器：常量被<b>探针与 BiomeSource</b> 多处引用
    //   （{@code CaveShapeProbe}/{@code CavePerfProbe}/{@code GeoGenesisBiomeSource}），
    //   改成访问器会牵动它们；而生产路径必须读<b>配置生效值</b>。
    //   故：常量 = 默认值（文档与探针口径），访问器 = 生效值（生产用）。

    /** 生效的洞顶保护厚度（配置可覆盖；0 ⇒ 允许洞穴破地表）。 */
    public static int surfaceLid() {
        return cfgSurfaceLid;
    }

    /** 生效的洞穴带最浅深度。 */
    public static int depthMin() {
        return cfgDepthMin;
    }

    /** 生效的洞穴带最深深度。 */
    public static int depthMax() {
        return cfgDepthMax;
    }

    /** 分量位掩码：隧道。 */
    public static final int F_TUNNEL = 1;
    /** 分量位掩码：洞室。 */
    public static final int F_CAVERN = 2;
    /** 分量位掩码：孔洞。 */
    public static final int F_CHEESE = 4;
    // ⚠ 2026-09-15 核实：F_CHEESE 【从未被 components() 置位】—— 该分量未实现。
    //   CaveShapeProbe 的 cheese 统计因此恒为 0（曾让人误以为"孔洞分量被关掉了"）。
    //   保留常量是为了不破坏探针编译；若日后实现 CHEESE 分量，
    //   CHEESE_Y_SCALE / 本常量即为它的接入点。

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
        // ★ 2026-09-15：总开关 —— 关闭时立即返回（零成本）。
        //   与 RTG 的 useCaves=false 同语义：地下无任何洞穴。
        if (!cfgEnabled) return 0;
        if (!seeded) return 0;
        // 不破地表：洞顶至少低于地表 cfgSurfaceLid（可由配置设为 0 ⇒ 允许破地表成入口）
        if (wy > surface - cfgSurfaceLid) return 0;
        if (wy < worldMinY + 1) return 0;
        // 限制在"地下带"内（贴近地表的浅层不挖 ⇒ 避免草原破洞；过深无意义）
        int depth = surface - wy;
        if (depth < cfgDepthMin || depth > cfgDepthMax) return 0;

        // 岩性门控可由配置关闭（VANILLA_LIKE 档 ⇒ 一视同仁，同原版不看岩性）
        double L = cfgLitho ? litho : 1.0;
        // 密度倍率：档位缩放（VANILLA_LIKE 档放大 ⇒ 洞更粗）
        double dens = cfgDensityMul;

        int mask = 0;
        double x = wx, y = wy, z = wz;

        // ---- 1) 隧道：两噪声等值面交线（1D 管道，负责"连接"）----
        //   岩性越大 ⇒ 阈值越大 ⇒ 管道越粗（石灰岩溶洞 vs 花岗岩）
        if (cfgTunnel && pair(TUNNEL_A, TUNNEL_SCALE_A, TUNNEL_B, TUNNEL_SCALE_B,
                TUNNEL_Y_SCALE, x, y, z,
                TUNNEL_T1 * L * dens, TUNNEL_T2 * L * dens)) {
            mask |= F_TUNNEL;
        }

        // ---- 2) ★★ 空腔（原版 cave_cheese + cave_layer，负责"能走的大空间"）----
        //   连续密度模型（与隧道的二值交集不同）：
        //       density = CHAMBER_T + chamberNoise + layerTerm
        //       density < 0  ⇒ 挖空
        //   其中 layerTerm = LAYER_W × layerNoise² 恒 ≥ 0 ⇒ 在"层"上把密度推回实心，
        //   把本会连通的大空间切成**一层层有限高度的空腔**（防竖直贯穿）。
        //
        //   ★ 层调制可关（VANILLA_LIKE 档）：关掉后大空腔更接近原版 cheese 洞
        //     （无层理），代价是可能出现竖直贯穿 —— 这是该档位的<b>有意取舍</b>。
        if (cfgChamber && depth >= CHAMBER_MIN_DEPTH) {
            double cT = CHAMBER_T * dbgChamberMul * dens / L;
            double lw = cfgLayer ? (LAYER_W * dbgLayerMul) : 0.0;
            double cn = CHAMBER.compute(x / CHAMBER_SCALE, y / CHAMBER_SCALE * 1.5,
                    z / CHAMBER_SCALE);
            if (lw <= 0.0) {
                // 无层调制：纯单噪声阈值（原版 cheese 式）
                if (cT + cn < 0) mask |= F_CAVERN;
            } else if (cn < -cT + lw) {
                // 只在"有可能是空腔"时才去算层（短路，省一次噪声）
                double ln = LAYER.compute(x / LAYER_SCALE, y / LAYER_SCALE * LAYER_Y_SCALE,
                        z / LAYER_SCALE);
                double layerTerm = lw * ln * ln;
                if (cT + cn + layerTerm < 0) mask |= F_CAVERN;
            }
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
    /** 诊断用空腔阈值倍率（越大空腔越稀疏）。 */
    static volatile double dbgChamberMul = 1.0;
    /** 诊断用层调制倍率（越大气腔越薄、越不竖直贯穿）。 */
    static volatile double dbgLayerMul = 1.0;

    /** 供探针扫描参数（仅诊断用）。 */
    public static void dbgSet(double yScaleMul, double thresholdMul) {
        dbgYScaleMul = yScaleMul;
        dbgThresholdMul = thresholdMul;
    }

    /** 供探针扫描参数（仅诊断用）：空腔阈值 × 层调制强度。 */
    public static void dbgSetChamber(double chamberMul, double layerMul) {
        dbgChamberMul = chamberMul;
        dbgLayerMul = layerMul;
    }

    /** 恢复生产默认参数。 */
    public static void dbgReset() {
        dbgYScaleMul = 1.0;
        dbgThresholdMul = 1.0;
        dbgChamberMul = 1.0;
        dbgLayerMul = 1.0;
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
