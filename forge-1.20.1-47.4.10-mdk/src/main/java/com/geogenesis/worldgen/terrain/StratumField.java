package com.geogenesis.worldgen.terrain;

/**
 * 地层场（★ 2026-09-12，地质系统 Phase T4）。
 *
 * <p>建立"<b>构造环境 → 地层序列 → 出露岩性</b>"的推理链，为 {@code ROCK_LAYER} /
 * {@code ROCK_TYPE} 预览图层提供此前完全缺失的数据源
 * （此前这两个图层在 {@code GeoPalette.discreteId} 落到 {@code default -> 0}，无数据）。
 *
 * <h3>核心设计：岩性由构造环境决定，而非随机分配</h3>
 * <p>真实地质中岩性受构造环境严格控制。本实现据此建表
 * （{@code LAYER_COUNT=4} 层，序列<b>循环</b>取用）：
 *
 * <table border="1">
 *   <caption>构造环境 → 地层序列（浅 → 深）</caption>
 *   <tr><th>构造环境</th><th>地层序列</th><th>地质依据</th></tr>
 *   <tr><td>板块内部 · 陆地（克拉通/地盾）</td><td>片麻岩 → 花岗岩 → 片岩</td><td>古老结晶基底</td></tr>
 *   <tr><td>板块内部 · 海洋（深海平原）</td><td>石灰岩 → 玄武岩</td><td>远洋沉积盖层覆于洋壳之上</td></tr>
 *   <tr><td>汇聚 · 陆地（造山带）</td><td>片岩 → 片麻岩 → 花岗岩</td><td>区域变质 + 深成侵入</td></tr>
 *   <tr><td>汇聚 · 海洋（俯冲带）</td><td>页岩 → 安山岩 → 玄武岩</td><td>海沟浊积 → 火山弧 → 洋壳</td></tr>
 *   <tr><td>离散 · 陆地（裂谷）</td><td>砂岩 → 页岩 → 石灰岩 → 玄武岩</td><td>裂谷沉积充填（底部见基性岩浆）</td></tr>
 *   <tr><td>离散 · 海洋（洋中脊）</td><td>玄武岩（无沉积盖层）</td><td>新生洋壳</td></tr>
 *   <tr><td>走滑</td><td>同"板块内部"</td><td>无垂向构造，不产生特征岩性</td></tr>
 * </table>
 *
 * <h3>出露层（rockLayer）</h3>
 * <p>由<b>低频噪声</b>决定（代表剥蚀深度）：剥蚀越深，出露越深部的岩层。
 * 同一构造单元内 {layer} 随空间变化 → 不同岩层交替出露，形成<b>地层感</b>。
 *
 * <h3>约束</h3>
 * <ul>
 *   <li><b>零依赖纯函数</b>（不 import Minecraft / Forge）</li>
 *   <li><b>确定性</b>：同 (seed,x,z) 恒得同结果</li>
 *   <li><b>廉价</b>：仅 1 次低频 value noise，纳秒级；且<b>复用</b>调用方已取得的
 *       {@link TectonicField.Sample}（不重复采样构造场）</li>
 * </ul>
 *
 * <p><b>Phase T4 范围</b>：建立数据层（岩性标签、供预览图层与后续地质系统消费）。
 *
 * <p><b>★ 2026-09-14 Phase T8(P3) 已完成"岩性 → 侵蚀耦合"</b>：
 * {@link #resistanceAt} 把 {@link RockType#resistance()} 暴露给侵蚀引擎
 * （经 {@code CellGenerator.rockResistanceAt} 注入 {@code ErosionEngine}），
 * 侵蚀量按抗蚀性调制 ⇒ <b>软岩成谷、硬岩成脊</b>。
 * 见 {@link #resistanceAt} 的 javadoc。
 */
public final class StratumField {

    /** 地层数（序列循环取用）。 */
    public static final int LAYER_COUNT = 4;

    /** 剥蚀深度噪声频率（1/wu）：低频 → 岩性成片，而非碎斑。 */
    private static final double DEPTH_FREQ = 1.0 / 900.0;
    private static final long DEPTH_SALT = 0x4C1D_7A3E_9B02_5F81L;

    // ===== 构造环境 → 地层序列（浅 → 深） =====
    private static final RockType[] CRATON = {
            RockType.GNEISS, RockType.GRANITE, RockType.SCHIST };
    private static final RockType[] OROGENIC = {
            RockType.SCHIST, RockType.GNEISS, RockType.GRANITE };
    private static final RockType[] RIFT = {
            RockType.SANDSTONE, RockType.SHALE, RockType.LIMESTONE, RockType.BASALT };

    // —— 海洋序列：真实海底 = 玄武岩基底 + 沉积盖层（盖层厚度随环境不同）——
    /** 深海平原：远洋软泥/灰岩覆盖在洋壳玄武岩之上。 */
    private static final RockType[] ABYSSAL = {
            RockType.LIMESTONE, RockType.BASALT };
    /** 洋中脊：新生洋壳，几乎无沉积盖层。 */
    private static final RockType[] RIDGE = {
            RockType.BASALT };
    /** 俯冲带（汇聚海洋）：海沟浊积 → 火山弧（安山岩）→ 洋壳。 */
    private static final RockType[] SUBDUCTION = {
            RockType.SHALE, RockType.ANDESITE, RockType.BASALT };

    private long seed;

    public StratumField(long seed) {
        this.seed = seed;
    }

    /** 换世界种子（地层深度分布随之改变）。 */
    public void setSeed(long worldSeed) {
        this.seed = worldSeed;
    }

    /**
     * 出露层号（0 = 地表/最新，{@code LAYER_COUNT-1} = 最深/最老）。
     * 由低频噪声决定，代表剥蚀深度。
     */
    public int layerAt(double wx, double wz) {
        double n = valueNoise(wx * DEPTH_FREQ, wz * DEPTH_FREQ, DEPTH_SALT);  // [-1,1]
        double t = n * 0.5 + 0.5;                                            // [0,1]
        int layer = (int) (t * LAYER_COUNT);
        return layer >= LAYER_COUNT ? LAYER_COUNT - 1 : Math.max(0, layer);
    }

    /**
     * 按构造环境与层号取岩性。
     *
     * @param s      构造采样（由 {@link TectonicField#sample} 提供；调用方通常已持有）
     * @param isLand 该点是否倾向陆地
     * @param layer  出露层号（{@link #layerAt}）
     * @return 岩性在 {@link RockType#ordinal()} 中的 id
     */
    public static int rockTypeId(TectonicField.Sample s, boolean isLand, int layer) {
        RockType[] seq = sequenceFor(s.btype(), isLand);
        return seq[Math.floorMod(layer, seq.length)].ordinal();
    }

    /**
     * ★ 2026-09-14 Phase T8(P3)：<b>该点出露岩性的抗蚀性</b> ∈ [0,1]（越大越难蚀）。
     *
     * <h3>用途：软岩成谷、硬岩成脊（岩性 → 侵蚀耦合）</h3>
     * <p>P3 之前所有岩石"一样硬"（侵蚀引擎完全不感知岩性）。本方法把
     * {@link RockType#resistance()}（地质学硬度序）暴露给侵蚀引擎，
     * 使侵蚀量按抗蚀性调制：页岩(0.30) 比花岗岩(0.90) 易蚀 3 倍
     * ⇒ 同坡度下软岩区被下切成谷、硬岩区残留成脊（真实地貌的核心机制）。</p>
     *
     * <p><b>为何合并为单一标量</b>：调用方（{@code ErosionEngine} 预构建的硬度网格）
     * 只需要"这一个数字"，不必知道岩性枚举/地层序列 —— 保持侵蚀引擎不依赖地质细节。</p>
     *
     * @param s      构造采样（由调用方持有，复用避免重复采样）
     * @param isLand 该点是否倾向陆地
     * @param layer  出露层号（{@link #layerAt}）
     * @return 抗蚀性 ∈ [0,1]（{@link RockType#resistance()} 的值）
     */
    public static double resistanceAt(TectonicField.Sample s, boolean isLand, int layer) {
        return RockType.values()[rockTypeId(s, isLand, layer)].resistance();
    }

    /**
     * ★ 2026-09-14 Phase T9：<b>该列的地层序列</b>（{@link RockType#ordinal()}，浅 → 深）。
     *
     * <h3>用途：让岩性在游戏里【看得见】（垂直岩层）</h3>
     * <p>P3 之前岩性只是"地形形成机制的输入"（影响侵蚀抗性），玩家挖下去看到的
     * 仍全是 {@code STONE}。本方法把整条地层序列交给方块层（{@code GeoGenesisGenerator}），
     * 使其能按<b>深度</b>逐层取用 ⇒ 同一列从上到下依次是
     * {@code seq[layer], seq[layer+1], seq[layer+2] …}（序列循环）——
     * 即真实地质的<b>层序叠置</b>（老在下、新在上）。</p>
     *
     * <p><b>为何返回整条序列而非单一岩性</b>：{@link #rockTypeId} 只给"地表出露的那一种"，
     * 不足以表达垂直分层。方块层需要"往下是什么"，故必须给出完整序列
     * （深度 → 序列下标由方块层按层厚换算）。</p>
     *
     * <p><b>与侵蚀的关系</b>：侵蚀是<b>地表过程</b>，抗蚀性取最表层岩性
     * （{@code seq[layer]} = {@link #rockTypeId}）—— 与 {@link #resistanceAt} 一致。</p>
     *
     * @param s      构造采样
     * @param isLand 该点是否倾向陆地
     * @return 地层序列的 ordinal 数组（浅 → 深）；调用方按深度循环取用
     */
    public static byte[] sequenceIds(TectonicField.Sample s, boolean isLand) {
        RockType[] seq = sequenceFor(s.btype(), isLand);
        byte[] ids = new byte[seq.length];
        for (int i = 0; i < seq.length; i++) ids[i] = (byte) seq[i].ordinal();
        return ids;
    }

    /** 每层占用的位数（8 种岩性 → 3 bit；{@code RockType.values().length ≤ 8} 必须成立）。 */
    private static final int SEQ_BITS = 3;
    private static final int SEQ_MASK = (1 << SEQ_BITS) - 1;

    /**
     * ★ 2026-09-14 Phase T9：把地层序列打包进一个 {@code int}（零分配，供 {@link Cell}）。
     *
     * <p>见 {@link Cell#rockSeqPacked} 的注释（为何不直接存 {@code byte[]}）。</p>
     */
    public static int packSequence(TectonicField.Sample s, boolean isLand) {
        byte[] seq = sequenceIds(s, isLand);
        int packed = 0;
        // ★ 必须【循环填充到 LAYER_COUNT】—— 序列长度不一（洋中脊仅 1 项、克拉通 3 项、
        //   裂谷 4 项）。若只写实际长度，{@link #seqAt} 按 LAYER_COUNT 取模会读到未写入的
        //   零位 ⇒ 第 2~3 层恒为 ordinal 0（片麻岩），产生错误岩层。
        for (int i = 0; i < LAYER_COUNT && i * SEQ_BITS + SEQ_BITS <= 32; i++) {
            packed |= (seq[i % seq.length] & SEQ_MASK) << (i * SEQ_BITS);
        }
        return packed;
    }

    /** 从打包序列取第 {@code index} 层岩性 ordinal（自动按序列长度循环）。 */
    public static int seqAt(int packed, int index) {
        int i = Math.floorMod(index, LAYER_COUNT);
        return (packed >>> (i * SEQ_BITS)) & SEQ_MASK;
    }

    /** 按构造环境选地层序列。走滑/内部归入"板块内部"。 */
    private static RockType[] sequenceFor(int btype, boolean isLand) {
        return switch (btype) {
            // 汇聚：陆地→造山带（区域变质）；海洋→俯冲带（海沟沉积+火山弧+洋壳）
            case TectonicField.CONVERGENT -> isLand ? OROGENIC : SUBDUCTION;
            // 离散：陆地→裂谷沉积充填；海洋→洋中脊（新生洋壳，无沉积盖层）
            case TectonicField.DIVERGENT -> isLand ? RIFT : RIDGE;
            // 内部与走滑（走滑无垂向构造，不产生特征岩性 → 按内部处理）：
            //   陆地→克拉通结晶基底；海洋→深海平原（远洋沉积盖层 + 洋壳）
            default -> isLand ? CRATON : ABYSSAL;
        };
    }

    // ===================== 零依赖极简 value noise =====================

    private double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double v00 = cell(ix, iz, salt),     v10 = cell(ix + 1, iz, salt);
        double v01 = cell(ix, iz + 1, salt), v11 = cell(ix + 1, iz + 1, salt);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    private double cell(int ix, int iz, long salt) {
        long h = (long) ix * 374761393L + (long) iz * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        h ^= (h >>> 16);
        return ((h & 0xFFFFFFL) / (double) 0x1000000L) * 2.0 - 1.0;
    }
}
