package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.climate.WhittakerType;
import com.geogenesis.worldgen.noise.Noise3;
import com.geogenesis.worldgen.noise.Simplex3;

/**
 * 洞穴群系选择（<b>零 Minecraft 依赖的纯函数</b>）。
 *
 * <h2>为何必须自研（原版洞穴群系是怎么来的）</h2>
 * <p>原版用 {@code MultiNoiseBiomeSource} + preset 的 <b>3D 噪声路由</b>
 * （{@code temperature}/{@code vegetation}/{@code depth} 等 7 维参数）在
 * <b>BIOMES 阶段</b>按 (x,y,z) 选出洞穴群系。本项目是自定义 {@code BiomeSource}
 * 且<b>群系是 2D 的</b>（按列分类，见 {@code GeoGenesisBiomeSource}）
 * ⇒ 原版 3D 群系路由用不了，必须自己决定"某洞穴体素属哪个洞穴群系"。</p>
 *
 * <h2>★ 装饰是"免费的岛"（本设计的关键依据）</h2>
 * <p>{@code GeoGenesisGenerator.applyBiomeDecoration} 已委托
 * {@code super.applyBiomeDecoration}（<b>原版实现</b>）⇒ 装饰<b>由群系驱动、
 * 原版负责放置</b>。因此本类<b>只需产出正确的群系</b>，钟乳石/石笋/洞穴藤蔓/
 * 发光浆果/苔藓就会由原版 {@code BiomeGenerationSettings} 自动落位
 * —— <b>零自研装饰代码</b>。</p>
 *
 * <h2>选择依据：气候（Whittaker 群区）—— 与既有系统同源，不用随机</h2>
 * <ul>
 *   <li><b>LUSH（繁茂洞穴）</b> —— 需<b>充足水分</b>（苔藓/藤蔓/发光浆果依赖水），
 *       故只在<b>成林气候</b>（Whittaker 的各类森林）且<b>非极寒</b>处出现。
 *       与真实岩溶洞分布一致：喀斯特溶洞发育于湿热地区。</li>
 *   <li><b>DRIPSTONE（滴水石洞）</b> —— <b>默认</b>类型（干燥环境，钟乳石/石笋
 *       靠渗滤水的碳酸钙沉积）。</li>
 * </ul>
 *
 * <h2>刻意不做 DEEP_DARK（范围决策，如实记录）</h2>
 * <p>深暗之域绑定<b>远古城市</b>与<b>监守者</b>（强玩法含义：潜行机制、可控恐怖）。
 * 擅自把所有深洞变成深暗之域会让玩家在毫无准备处遭遇监守者；且远古城市需结构生成
 * 配合（本项目未实现）。故<b>不选</b>该群系，深洞仍用 DRIPSTONE。</p>
 *
 * <h2>可验证性</h2>
 * <p>纯函数 ⇒ {@code CaveBiomeProbe} 可离线统计分布、气候耦合、深度带与边界连续性。
 * 与 {@code CaveShape}/{@code OreVeins} 同一范式（<b>复刻必然漂移</b>，本项目吃过亏）。</p>
 */
public final class CaveBiomeSelector {

    private CaveBiomeSelector() { }

    /** 洞穴群系类别（{@link #NONE} = 不在洞穴内，沿用地表群系）。 */
    public enum CaveBiome {
        /** 不在洞穴内 ⇒ 用该列的地表群系。 */
        NONE,
        /** 滴水石洞（默认洞穴类型）。 */
        DRIPSTONE,
        /** 繁茂洞穴（湿润/成林气候）。 */
        LUSH
    }

    /**
     * 洞穴群系的最小埋深（block）：更浅的洞穴<b>不换群系</b>。
     *
     * <h3>为何需要（原版也这么做）</h3>
     * <p>紧贴地表的洞穴（洞口、天坑）从上方可见，若换成滴水石洞群系，
     * 玩家会在地表看到"洞穴植被长到地表"的违和画面。原版同样只在<b>地下</b>
     * （{@code depth} 路由参数）启用洞穴群系。</p>
     *
     * <p>取 12 = {@link CaveShape#SURFACE_LID}(6) 的两倍，留出缓冲。</p>
     */
    public static final int MIN_DEPTH = 12;

    // ===================== 繁茂/滴水石的【交错】混合 =====================

    /**
     * 洞穴群系块的噪声特征尺度（block）。
     *
     * <h3>★ 为何需要（实测缺陷）</h3>
     * <p>首版是<b>硬二值切换</b>：成林气候 ⇒ 洞内<b>100%</b> 繁茂洞穴
     * （探头实测 {@code TEMPERATE_FOREST 滴水石=0 繁茂=21164}）。这是<b>错的</b>：
     * ① 原版里两种洞穴是<b>共存交错</b>的（繁茂洞穴更稀有，成片出现但周围仍是滴水石）；
     * ② 本项目已有明确教训 —— 用户反馈过"群系之间过渡不自然"，
     *    根因正是<b>阈值硬切换</b>（当时地表方块用逐格 hash 抖动 ⇒ "盐和胡椒"碎屑）。</p>
     *
     * <p>故这里用一张<b>3D 噪声</b>做交错：气候决定"这口洞允不允许繁茂"（气候是
     * 必要条件），噪声决定"这口洞的这一段是不是繁茂"。噪声在洞尺度（约 40 块）
     * 上变化 ⇒ 形成自然团块而非碎屑。</p>
     */
    private static final double LUSH_MIX_SCALE = 40.0;

    /**
     * 成林气候下，繁茂洞穴的体积占比（由噪声阈值决定）。
     *
     * <h3>取值依据（探针实测标定，非拍脑袋）</h3>
     * <p>Simplex3 的近似高斯分布下，阈值 t 对应体积占比 P(t)。实测
     * （{@code runCaveBiomeProbe}，种子 12345/7/42）：</p>
     * <table border="1">
     *   <caption>阈值 → 实测繁茂占比（成林气候内）</caption>
     *   <tr><th>阈值</th><th>实测占比</th></tr>
     *   <tr><td>0.62</td><td>15.4 / 15.1 / 14.6 %</td></tr>
     *   <tr><td><b>0.44</b></td><td><b>25.8 / 30.6 / 29.7 %</b></td></tr>
     * </table>
     * <p>取 <b>0.44</b> ⇒ 成林气候的洞穴里约 <b>三成</b>是繁茂、七成是滴水石。
     * 这既保留"繁茂洞穴较稀有、值得探索"的观感，又保证玩家<b>真的能遇到</b>
     * （15% 略嫌难找）—— 两种洞穴在<b>同一洞里交错出现</b>，而非各占一片。</p>
     */
    private static final double LUSH_MIX_T = 0.44;

    private static final int SALT_LUSH = 0x4B7E2A19;
    private static final Noise3 LUSH_MIX = new Simplex3(SALT_LUSH);

    private static volatile boolean seeded = false;

    /** 换世界种子（与地形/洞穴/矿脉同批失效，避免跨存档串扰）。 */
    public static synchronized void setSeed(long worldSeed) {
        if (LUSH_MIX instanceof Simplex3 s3) s3.seed(worldSeed, 0);
        seeded = true;
    }

    /** 是否已播种。 */
    public static boolean isSeeded() {
        return seeded;
    }

    /**
     * 该体素应属的洞穴群系。
     *
     * @param wx,wz      世界水平坐标
     * @param wy         世界 Y
     * @param surface    该列地表高（block）
     * @param worldMinY  世界最低 Y
     * @param litho      岩性系数（{@link CaveShape#lithoFactor}）
     * @param biomeType  该列气候群区（{@link WhittakerType}，来自 {@code Cell.biomeType}）
     * @return 洞穴群系；不在洞穴内或太浅 ⇒ {@link CaveBiome#NONE}
     */
    public static CaveBiome select(int wx, int wy, int wz, int surface, int worldMinY,
                                   double litho, WhittakerType biomeType) {
        // ① 太浅不换群系（洞口/天坑从地表可见）—— <b>放在最前</b>，避免无谓的洞穴求值。
        if (surface - wy < MIN_DEPTH) return CaveBiome.NONE;

        // ② 必须真在洞穴里。直接复用洞穴几何的【同一份】纯函数判定
        //    ⇒ 群系与洞穴形状<b>严格一致</b>，不会"群系漂到岩石里"。
        if (CaveShape.components(wx, wy, wz, surface, worldMinY, litho) == 0) {
            return CaveBiome.NONE;
        }

        // ③ 气候是<b>必要条件</b>：只有成林气候（湿润）才可能出繁茂洞穴。
        //    用 Whittaker 群区而非裸阈值 ⇒ 与地表群系选择<b>同源</b>，
        //    不会出现"地表是森林、洞里却是滴水石"的割裂。
        if (!isLushClimate(biomeType)) return CaveBiome.DRIPSTONE;

        // ④ ★ 成林气候下再用【噪声】决定"这一段是不是繁茂" —— 避免硬二值切换
        //    （首版成林 ⇒ 100% 繁茂，与"两种洞穴共存交错"的观感不符，见 LUSH_MIX_SCALE）。
        //    ⚠ 未播种时退化：仍按气候给繁茂（保持可用的"最合理默认"）。
        if (!seeded) return CaveBiome.LUSH;
        double n = LUSH_MIX.compute(wx / LUSH_MIX_SCALE, wy / LUSH_MIX_SCALE,
                wz / LUSH_MIX_SCALE);
        return n > LUSH_MIX_T ? CaveBiome.LUSH : CaveBiome.DRIPSTONE;
    }

    /**
     * 该气候群区是否足以支撑<b>繁茂洞穴</b>。
     *
     * <p>取"成林"档：温度带里只要湿度成林（各类森林），洞穴就有足够水分长植被。
     * 极寒（{@link WhittakerType#isCold()} = ICE/TUNDRA/TAIGA）与干旱
     * （DESERT/GRASSLAND/SAVANNA）都<b>不</b>算 —— 冻土与干旱洞没有茂密洞穴植被。</p>
     *
     * <p>⚠ 苔原/冰原本就极干（Whittaker 里 ICE/TUNDRA 的湿度上限很低），
     * 这里显式排除是<b>双保险</b>（与 {@code WhittakerType.classify} 的封顶逻辑一致）。</p>
     */
    public static boolean isLushClimate(WhittakerType biomeType) {
        if (biomeType == null) return false;
        if (biomeType.isCold()) return false;
        return switch (biomeType) {
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST,
                 SEASONAL_FOREST, TROPICAL_RAINFOREST -> true;
            // 干旱/草原/荒漠/苔原/冰原 ⇒ 湿润度不足，长不出茂密洞穴植被
            case DESERT, GRASSLAND, SAVANNA, ICE, TUNDRA, TAIGA -> false;
        };
    }
}
