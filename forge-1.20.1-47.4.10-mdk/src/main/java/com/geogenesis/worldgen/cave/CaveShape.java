package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.noise.Frequency;
import com.geogenesis.worldgen.noise.Noise;
import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.noise.Noises;
import com.geogenesis.worldgen.noise.Ridge;
import com.geogenesis.worldgen.noise.Simplex;
import com.geogenesis.worldgen.terrain.RockType;

/**
 * 洞穴几何（<b>零 Minecraft 依赖的纯函数</b>）—— 决定"每个 (x,z) 列该挖哪一段"。
 *
 * <h3>职责边界（接口与实现分离）</h3>
 * <p>本类只回答<b>"该列是否存在洞穴、上下边界在哪"</b>（几何）；
 * 真正把方块挖成空气由 {@link CaveCarver}（MC 适配器）完成。
 * 这样切分的理由：<b>诊断源码集没有 MC 依赖</b>（全项目 30+ 探针均 0 个
 * {@code import net.minecraft}），而洞穴验证必须能离线跑 —— 纯几何抽出来后，
 * 探针可直接构造剖面图与统计，无需 MC 环境。</p>
 *
 * <h3>★ 核心机制：2D 场驱动的「竖直柱体切挖」</h3>
 * <p>移植自 {@code TerraForged-0.3.x} 的 {@code NoiseCaveCarver}：不用 3D 噪声，
 * 而是对每个 (x,z) 用三个 <b>2D 噪声</b>算出</p>
 * <ul>
 *   <li>{@code centerY}（洞穴中心高度，{@code ELEVATION} 场）</li>
 *   <li>{@code up}（向上扩展，{@code SHAPE} 脊线场 —— 只有部分列有值 ⇒ 成网络）</li>
 *   <li>{@code down}（向下扩展，{@code FLOOR} 场）</li>
 * </ul>
 * <p>再把 {@code [centerY−down, centerY+up]} 整柱标记为空洞。相邻列因 2D 场平滑而
 * 大量重叠 ⇒ 3D 上自然形成<b>网络状洞穴</b>。成本 ≈ 6 次 2D 求值/列（逐块 3D 需数百次）。</p>
 *
 * <h3>★ 岩性门控（本项目独创，三个参考项目均无）</h3>
 * <p>洞穴发育受岩性控制（真实现象）：石灰岩可溶 ⇒ 喀斯特溶洞最发育；花岗岩致密
 * ⇒ 几乎不成洞。系数乘在"上下扩展"上 ⇒ 只改洞穴<b>规模</b>，不改其出现位置。</p>
 */
public final class CaveShape {

    private CaveShape() { }

    /** 洞穴族数量（{@link #SYNAPSE} / {@link #MEGA}）。 */
    public static final int FAMILY_COUNT = 2;

    /** 无洞穴的哨兵值（{@link #span} 的返回）。 */
    public static final long NO_SPAN = Long.MIN_VALUE;

    /** 地表保护厚度（block）：洞顶至少低于地表此值 ⇒ 永不开口到地表。 */
    public static final int SURFACE_LID = 6;

    /** 洞穴最小净高（block）：低于此值不雕（避免碎裂的孤立空格）。 */
    public static final int MIN_HEIGHT = 3;

    // ===================== 噪声场尺度（先声明，供下方字段初始化引用）=====================

    /** 洞穴中心高度场的特征尺度（block）：越大洞穴越呈"层带"。 */
    private static final double ELEV_SCALE = 350.0;
    /** 向上扩展场（脊线）的特征尺度。 */
    private static final double SHAPE_SCALE = 180.0;
    /** 向下扩展场的特征尺度。 */
    private static final double FLOOR_SCALE = 30.0;
    /** mega 区域掩码的特征尺度。 */
    private static final double REGION_SCALE = 900.0;

    private static final long SALT_ELEV = 0x51A73C09D4E826B5L;
    private static final long SALT_SHAPE = 0x7E24B8F16A039C57L;
    private static final long SALT_FLOOR = 0x3C965D2BE74108AFL;
    private static final long SALT_REGION = 0x2B71F4E8903CD6A2L;

    /** 洞穴中心高度场（低频 ⇒ 洞穴成"层带"而非杂乱散布）。 */
    private static final Noise ELEVATION = new Frequency(new Simplex((int) SALT_ELEV), 1.0 / ELEV_SCALE);
    /** 洞穴向上扩展（脊线噪声 ⇒ 只有部分列成洞 ⇒ 形成网络而非整片挖空）。 */
    private static final Noise SHAPE = new Frequency(
            new Ridge(new Simplex((int) SALT_SHAPE), 1.0), 1.0 / SHAPE_SCALE);
    /** 洞穴向下扩展（很低频 ⇒ 洞底平滑）。 */
    private static final Noise FLOOR = new Frequency(new Simplex((int) SALT_FLOOR), 1.0 / FLOOR_SCALE);
    /** mega 洞穴的"区域掩码"（低频阈值 ⇒ 只有少数区域出大洞）。 */
    private static final Noise REGION = new Frequency(new Simplex((int) SALT_REGION), 1.0 / REGION_SCALE);

    private static volatile boolean seeded = false;

    /** 换世界种子（与地形/河网同批失效）。 */
    public static synchronized void setSeed(long worldSeed) {
        Noises.seedAll(ELEVATION, worldSeed, 0);
        Noises.seedAll(SHAPE, worldSeed, 0);
        Noises.seedAll(FLOOR, worldSeed, 0);
        Noises.seedAll(REGION, worldSeed, 0);
        seeded = true;
    }

    /** 是否已播种（未播种时 {@link #span} 恒返回 {@link #NO_SPAN}）。 */
    public static boolean isSeeded() {
        return seeded;
    }

    // ===================== 洞穴族参数 =====================

    /** 细密遍布的洞穴族（对应 TF {@code synapse}）。 */
    public static final int SYNAPSE = 0;
    /** 粗大且受区域门控的洞穴族（对应 TF {@code mega}）。 */
    public static final int MEGA = 1;

    /**
     * 洞穴族：{@code size} 最大半径（block）；{@code minY/maxY} 中心高度的上下界；
     * {@code region} 区域门控阈值（&lt;0 = 不做区域门控，全世界可出）。
     *
     * <p>参数取自 TF {@code ModCaves}（synapse_mid / mega），并按本项目世界高度
     * （{@code WORLD_MIN_Y = −64}）调整。</p>
     */
    private record Family(double size, int minY, int maxY, double region) { }

    private static final Family[] FAMILIES = {
            new Family(9.0, -40, 140, -1.0),    // SYNAPSE
            new Family(18.0, -56, 48, 0.35)     // MEGA（区域门控）
    };

    /**
     * 向上扩展场的<b>生效下界</b>（ridge 值域）：只有 ridge ∈ [本值, 本值+0.40] 的列
     * 才归一成非零的 {@code up}。
     *
     * <p>★ 这是<b>洞穴密度</b>的主控旋钮。首版取 0.35 时实测洞穴体积占比达
     * <b>18.05%</b>（世界变瑞士奶酪）；抬高到 0.55 后只有约 45% 的列成洞，
     * 且每列净高更小，占比落到数个百分点的合理区间
     * （实测数据见 {@code CaveShapeProbe}）。</p>
     */
    private static final double SHAPE_FLOOR = 0.55;

    /**
     * 计算某列某洞穴族的空洞区间。
     *
     * @param family    族索引（{@link #SYNAPSE} / {@link #MEGA}）
     * @param wx        世界 X（block）
     * @param wz        世界 Z（block）
     * @param surface   地表高度（block）—— 洞顶被钳到 {@code surface − SURFACE_LID}
     * @param worldMinY 世界最低 Y
     * @param litho     岩性系数（{@link #lithoFactor}）
     * @return 打包的 {@code (bottom << 32) | (top & 0xFFFFFFFFL)}；无洞返回 {@link #NO_SPAN}
     */
    public static long span(int family, int wx, int wz, int surface, int worldMinY, double litho) {
        if (!seeded) return NO_SPAN;
        if (family < 0 || family >= FAMILIES.length) return NO_SPAN;
        Family f = FAMILIES[family];

        // mega 的区域门控（低频阈值 ⇒ 只有少数区域出大洞）
        double modifier = 1.0;
        if (f.region() >= 0.0) {
            modifier = regionMask(f.region(), wx, wz);
            if (modifier <= 0.0) return NO_SPAN;
        }

        // 1) 中心高度：elevation ∈ [-1,1] → [minY, maxY]
        double e01 = ELEVATION.compute(wx, wz) * 0.5 + 0.5;
        int centerY = f.minY() + (int) Math.floor(e01 * (f.maxY() - f.minY()));

        // 2) 向上扩展：ridge ∈ [0,1]，按 TF 口径压到 [SHAPE_FLOOR, SHAPE_FLOOR+0.40] 再归一
        //    （原样全用会"到处都挖"，网络形态消失 ⇒ 洞穴密度失控，实测 18% 体积占比）
        double s01 = NoiseUtil.clamp(SHAPE.compute(wx, wz), 0.0, 1.0);
        s01 = NoiseUtil.clamp((s01 - SHAPE_FLOOR) / 0.40, 0.0, 1.0);
        double up = s01 * f.size() * litho * modifier;

        // 3) 向下扩展：floor ∈ [0,1]
        double f01 = NoiseUtil.clamp(FLOOR.compute(wx, wz) * 0.5 + 0.5, 0.0, 1.0);
        double down = f01 * f.size() * 0.5 * litho * modifier;

        int top = centerY + (int) Math.floor(up);
        int bottom = centerY - (int) Math.floor(down);

        // 安全钳制：不破地表、不越世界底
        int lid = surface - SURFACE_LID;
        if (top > lid) top = lid;
        if (bottom < worldMinY + 1) bottom = worldMinY + 1;
        if (top - bottom < MIN_HEIGHT) return NO_SPAN;

        return ((long) bottom << 32) | (top & 0xFFFFFFFFL);
    }

    /** 解出 {@link #span} 的下界。 */
    public static int spanBottom(long span) {
        return (int) (span >> 32);
    }

    /** 解出 {@link #span} 的上界。 */
    public static int spanTop(long span) {
        return (int) span;
    }

    /**
     * 洞穴区域掩码 ∈ [0,1]（{@code 0} = 本列不出该族洞穴）。
     *
     * <p>低频噪声阈值化 ⇒ 只有约 30% 的区域能出大洞穴。TF 用 Worley 达到同一目的，
     * 本项目复用"阈值化低频噪声"范式（与 {@code TectonicDeformation.beltMask} 同款），
     * 更简单且边界同样 C¹ 有机（不引入直线网）。</p>
     */
    private static double regionMask(double threshold, int wx, int wz) {
        double n = REGION.compute(wx, wz);                 // [-1,1]
        double t = (n - threshold) / 0.35;
        return NoiseUtil.smooth(NoiseUtil.clamp(t, 0.0, 1.0));
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
