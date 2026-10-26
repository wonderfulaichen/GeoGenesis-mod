package com.geogenesis.worldgen.hydrology.model;

/**
 * <h1>河湖接口的湖面横向尺度 —— 纯数据、有界采样、不求解</h1>
 *
 * <h2>它回答什么</h2>
 * <p>在<b>一个明确的河湖接口格</b>处，该湖的<b>横向湿宽</b>是多少 block，
 * 由此给出该接口允许的河道半宽上限：</p>
 * <pre>  半宽 ≤ 横向湿宽 / 16   （⇔ 河口全宽 ≤ 有效宽 / 8）
 *  再夹到 [0.5, 6.0] block</pre>
 * <p>比例（1/8）是待生产 A/B 评定的视觉标定，不是物理定律；
 * 本类只负责"量的定义与测量"，<b>不含任何宽度律、渐变或落块逻辑</b>。</p>
 *
 * <h2>输入契约（调用方必须遵守）</h2>
 * <ul>
 *   <li><b>只读复用，不新增求解</b>：只读 {@link LakeWaterBalance.Result#lake()} 与
 *       {@link LakeWaterBalance.Result#basinId()}，不触发任何湖泊/河网/地形计算。</li>
 *   <li><b>有效水面 = lake[] 且 basinId[] 匹配</b>：同格的其它盆地水体一律按"干岸"计，
 *       绝不计入本湖宽度（相邻湖防串盆）。<b>不使用</b> {@code depth}/{@code level}/{@code basins}。</li>
 *   <li><b>身份由调用方给出，不由最近湖猜</b>：
 *       {@code basin >= 0} 时以它为<b>唯一权威</b>，<b>允许它 ≠ basinId[cell]</b>
 *       （出湖源点的 {@code sourceBasinOf} 就可能与出口格 basinId 不同）；
 *       {@code basin < 0} 或 {@code basinId == null} ⇒ {@link #UNKNOWN_NO_IDENTITY}。</li>
 *   <li><b>方向由调用方给出</b>：{@code (inwardX, inwardZ)} 指向"湖内"。入湖 tail 取其前进方向；
 *       出湖取源点反向（或出口上一个湿格）。内部归一化；零长/非有限 ⇒
 *       {@link #UNKNOWN_DEGENERATE}（不抛异常，因为该方向来自路径几何，可能退化）。</li>
 * </ul>
 *
 * <h2>测量口径（固定<b>世界距离</b>，与 step 解耦）</h2>
 * <ol>
 *   <li>以接口格中心为原点，{@code perp = (-inwardZ, inwardX)} 为横向轴。</li>
 *   <li>对每个横向偏移 {@code t = 0, 1, 2, … ≤ }{@value #REACH_BLOCKS} block（两侧各一遍），
 *       沿 inward 方向<b>有界走廊</b>推进（≤ {@value #REACH_BLOCKS} block）：
 *       只要走廊里出现一次"本盆湖水"即为湿。</li>
 *   <li>该侧的横向尺度 = 从原点起<b>连续湿</b>的最大偏移（首个干偏移即封口）。
 *       ⇒ 岸线交点处不会有"湖宽 = 0"：接口格在干岸时，走廊沿 inward 走几格就命中真实湿湖。</li>
 *   <li>{@code transverseBlocks = 两侧尺度和}，{@code maxHalf = clamp(width/16, 0.5, 6)}。</li>
 * </ol>
 * <p><b>为什么不能用总面积等效直径</b>：狭长湖的等效直径由<b>长度</b>主导，
 * 会把宽 3 格的长湖当成 25 格宽 ⇒ 河口被放宽数倍。本测量只取接口处的横向湿宽。</p>
 *
 * <h2>status（未知必须显式，不得静默当小湖）</h2>
 * <ul>
 *   <li>{@link #OK} —— 量到，比例可满足；</li>
 *   <li>{@link #OK_SMALL_BODY} —— 湿宽 &lt; 8 block，比例上限低于最小可见半宽
 *       ⇒ 取 0.5（离散例外，见方案"小于 8 格的小水体最小 1 格优先"）；</li>
 *   <li>{@link #OK_SATURATED} —— 一侧仍湿已达 {@value #REACH_BLOCKS} block
 *       ⇒ 真实湿宽 ≥ 该下界（两侧都饱和即 ≥192），上限已饱和，<b>不是</b>未知；</li>
 *   <li>{@link #UNKNOWN_NO_IDENTITY} —— 无 lake 数组 / 无 basinId / basin&lt;0 / lakes==null；</li>
 *   <li>{@link #UNKNOWN_CLIPPED} —— 采样撞到窗口边框而当时仍可能继续有湿区
 *       ⇒ 上限 NaN，<b>不得</b>当小湖；</li>
 *   <li>{@link #UNKNOWN_DRY} —— 沿 inward 96 block 内都未遇到本盆湖水
 *       （方向错 / 接口并不连通该湖）⇒ NaN；</li>
 *   <li>{@link #UNKNOWN_DEGENERATE} —— inward 为零或非有限 ⇒ NaN。</li>
 * </ul>
 *
 * <h2>成本与边界（如实声明）</h2>
 * <ul>
 *   <li>只做数组读：每次调用上界 = 2 侧 × 97 个横向偏移 × 走廊样本数
 *       （走廊步长 = {@code max(0.5, step/2)} ⇒ step=1 时 193、step≥2 时 97）
 *       ⇒ 最坏约 3.7 万次数组比较（step=1，且窗口内两侧全干）。实测远低于此：
 *       湿区连续时每个偏移首个样本即命中；横向首个干偏移即封口。
 *       逐接口<b>预计算并复用</b>即可，切勿逐 chunk / 逐列重扫。</li>
 *   <li>{@code transverseBlocks} 是<b>下界</b>：每个湿偏移点只按自身计，
 *       不加半格补偿 ⇒ 由此算出的上限只会偏紧，不会越过"全宽 ≤ 有效宽/8"。</li>
 *   <li>走廊/横向都是<b>有界</b>采样：心形湾、岛、沙嘴会把连续湿带截断 ⇒ 结果偏小（保守）。
 *       斜向 inward 会让横向扇沿湖岸拉长（量到的是真实湿弦，不是严格法向切面）。</li>
 *   <li><b>NOT VERIFIED</b>：未在实机/生产大图上做过比例评价；本类不含任何验收结论。</li>
 * </ul>
 */
final class LakeInterfaceScale {

    /** 走廊与单侧横向采样的世界距离上界（block）。两点都用同一常量，故两侧全饱和 ⇒ 湿宽 ≥ 2·96。 */
    static final double REACH_BLOCKS = 96.0;

    /** 横向偏移采样间距（block）。固定世界距离 ⇒ step=1/2/4 的横向分辨率不随网格退化。 */
    static final double LATERAL_PITCH_BLOCKS = 1.0;

    /** 半宽下限（block），与 {@link RiverCenterlines#MIN_HALF_BLOCKS} 同值：1 块全宽。 */
    static final double MIN_HALF = RiverCenterlines.MIN_HALF_BLOCKS;

    /** 半宽上限（block），与 {@code RiverCenterlines.Params.defaults().maxHalf()} 同值。 */
    static final double MAX_HALF = 6.0;

    /** 半宽 / 湿宽。1/16 半宽 ⇔ 全宽 1/8（方案 §3.B 首版候选比例）。 */
    static final double HALF_PER_WIDTH = 1.0 / 16.0;

    /** 比例下限与最小可见半宽冲突的湿宽门槛（block）：8 = 0.5 / (1/16)。 */
    static final double SMALL_BODY_WIDTH = MIN_HALF / HALF_PER_WIDTH;

    /**
     * 状态码（{@link Measurement#status()}）。
     *
     * <p>{@link #OK_SMALL_BODY} 的门槛正是 {@link #SMALL_BODY_WIDTH} = 8 block：
     * 它不是调参，而是 {@code 0.5 / (1/16)} —— 比例上限恰好落到最小可见半宽之下的临界点。
     * 低于它时"1 格可见"与"全宽 ≤ 有效宽/8"<b>原理上</b>无法同时满足，
     * 于是取最小可见宽并在此状态里如实标出（离散例外，不静默）。</p>
     */
    static final String OK = "OK";
    static final String OK_SMALL_BODY = "OK_SMALL_BODY";
    static final String OK_SATURATED = "OK_SATURATED";
    static final String UNKNOWN_NO_IDENTITY = "UNKNOWN_NO_IDENTITY";
    static final String UNKNOWN_CLIPPED = "UNKNOWN_CLIPPED";
    static final String UNKNOWN_DRY = "UNKNOWN_DRY";
    static final String UNKNOWN_DEGENERATE = "UNKNOWN_DEGENERATE";

    /** 采样分类。 */
    private static final int SAMPLE_DRY = 0;
    private static final int SAMPLE_WET = 1;
    private static final int SAMPLE_OUT = 2;

    private LakeInterfaceScale() { }

    /**
     * 一次测量的结果。
     *
     * @param maxHalf         允许的河道半宽上限（block）；未知时为 {@link Double#NaN}，
     *                        已知时必在 {@code [0.5, 6.0]}
     * @param status          见类注释的状态表；永不为 null
     * @param samples         本次测量实际做的数组采样次数（成本核对用，确定值）
     * @param transverseBlocks 量到的横向湿宽（block，下界）；未知时为 {@link Double#NaN}
     */
    record Measurement(double maxHalf, String status, int samples, double transverseBlocks) {
        Measurement {
            if (status == null || status.isEmpty()) status = UNKNOWN_NO_IDENTITY;
        }

        /** 上限可用（有限）。false ⇒ 调用方必须自行决定待定策略，不得当作小湖。 */
        boolean usable() {
            return Double.isFinite(maxHalf);
        }
    }

    /**
     * 隐式身份：{@code basin = basinId[cell]}。
     *
     * <p>接口格本身可以<b>不是</b>湖格（河口在干岸），只要它的 {@code basinId} 指向该湖盆即可。
     * 身份缺失（basinId 为 null / -1）⇒ {@link #UNKNOWN_NO_IDENTITY}。</p>
     */
    static Measurement measure(LakeWaterBalance.Result lakes, int nx, int nz, int step,
                               int cell, double inwardX, double inwardZ) {
        if (lakes == null) return unknown(UNKNOWN_NO_IDENTITY, 0);
        return measure(lakes, nx, nz, step, cell, LakeWaterBalance.basinOf(lakes, cell),
                inwardX, inwardZ);
    }

    /**
     * 显式身份：{@code basin} 为唯一权威，<b>允许它不等于 basinId[cell]</b>。
     *
     * <p>典型用法（出湖）：{@code sourceBasinOf} 与"出口格 basinId"可以不同，
     * 此时必须把真正的源盆传进来，不得回退到最近湖猜测。</p>
     *
     * @throws IllegalArgumentException {@code lakes/lake[] == null} 之外的<b>结构性误用</b>：
     *         {@code nx/nz/step <= 0}、{@code lake.length != nx*nz}、{@code cell} 越界。
     *         （数据缺失走 status，程序错误抛异常 —— 不静默掩盖。）
     */
    static Measurement measure(LakeWaterBalance.Result lakes, int nx, int nz, int step,
                               int cell, int basin, double inwardX, double inwardZ) {
        if (lakes == null || lakes.lake() == null) return unknown(UNKNOWN_NO_IDENTITY, 0);
        requireGrid(lakes.lake().length, nx, nz, step, cell);
        if (lakes.basinId() == null || basin < 0) return unknown(UNKNOWN_NO_IDENTITY, 0);
        return measure(lakes.lake(), lakes.basinId(), nx, nz, step, cell, basin, inwardX, inwardZ);
    }

    /**
     * ★ 主入口（生产用）：直接给两个数组，<b>不需要 {@code LakeWaterBalance.Result}</b>。
     *
     * <p>为什么需要它：生产侧的 {@code build}(widths/caps 生成阶段) 手里只有
     * {@code pipe.lakeResult()} 的<b>字段视图</b>，而把数组重新包成
     * {@code new LakeWaterBalance.Result(...)} 等于伪造账本字段（depth/level/basins），
     * 那正是"用别的数据顶替"的错误方向。故本重载接受最小只读输入。</p>
     *
     * @param lake     有效水面掩码（只读）
     * @param basinId  逐格盆地身份（只读）；{@code null} ⇒ {@link #UNKNOWN_NO_IDENTITY}
     * @param basin    唯一权威身份；{@code <0} ⇒ {@link #UNKNOWN_NO_IDENTITY}
     * @throws IllegalArgumentException 结构性误用（尺寸/长度/cell）与 {@code lake == null}
     */
    static Measurement measure(boolean[] lake, int[] basinId, int nx, int nz, int step,
                               int cell, int basin, double inwardX, double inwardZ) {
        if (lake == null) return unknown(UNKNOWN_NO_IDENTITY, 0);
        requireGrid(lake.length, nx, nz, step, cell);
        if (basinId == null || basin < 0) return unknown(UNKNOWN_NO_IDENTITY, 0);
        double len = Math.hypot(inwardX, inwardZ);
        if (!Double.isFinite(len) || len < 1e-9) return unknown(UNKNOWN_DEGENERATE, 0);
        Frame f = new Frame(lake, basinId, nx, nz, step, cell, basin,
                inwardX / len, inwardZ / len);
        return combine(f, scan(f, 1), scan(f, -1));
    }

    private static void requireGrid(int cells, int nx, int nz, int step, int cell) {
        if (nx <= 0 || nz <= 0 || step <= 0) {
            throw new IllegalArgumentException("grid must be positive: nx=" + nx + " nz=" + nz + " step=" + step);
        }
        if (cells != nx * nz) {
            throw new IllegalArgumentException("lake.length=" + cells + " != nx*nz=" + (nx * nz));
        }
        if (cell < 0 || cell >= nx * nz) {
            throw new IllegalArgumentException("cell=" + cell + " outside [0," + (nx * nz) + ")");
        }
    }

    /**
     * 两侧结论合成上限与状态。<b>裁切优先</b>：宁可未知，也不把被窗口切开的湖当小湖。
     *
     * <p>{@link #OK_SATURATED} 表示"湿宽至少这么宽"：任一侧仍在 96 block 处为湿，
     * 则真实横向湿宽 ≥ 该侧下界（两侧皆饱和 ⇒ ≥192），比例上限已到顶；
     * 这是<b>有界采样的结论</b>，不是未知。</p>
     */
    private static Measurement combine(Frame f, Side pos, Side neg) {
        if (pos.clipped() || neg.clipped()) return unknown(UNKNOWN_CLIPPED, f.probes);
        if (!pos.wet() && !neg.wet()) return unknown(UNKNOWN_DRY, f.probes);
        double width = Math.max(0, pos.extent()) + Math.max(0, neg.extent());
        boolean saturated = pos.saturated() || neg.saturated();
        String status = saturated ? OK_SATURATED : width < SMALL_BODY_WIDTH ? OK_SMALL_BODY : OK;
        return new Measurement(clampHalf(width * HALF_PER_WIDTH), status, f.probes, width);
    }

    /**
     * 单侧横向扫描：偏移从 0 起按 {@value #LATERAL_PITCH_BLOCKS} block 递增，首个干偏移封口。
     *
     * <p>提前封口是有意的有界语义：湖岸出现断口（岛/沙嘴/另一盆地）即视为本侧湿区结束。</p>
     */
    private static Side scan(Frame f, double side) {
        double extent = -1;
        for (int k = 0; k * LATERAL_PITCH_BLOCKS <= REACH_BLOCKS + 1e-9; k++) {
            double off = k * LATERAL_PITCH_BLOCKS;
            int c = corridor(f, f.perpX * side * off, f.perpZ * side * off);
            if (c == SAMPLE_WET) { extent = off; continue; }
            return new Side(extent, extent >= 0, false, c == SAMPLE_OUT);
        }
        return new Side(extent, extent >= 0, extent >= REACH_BLOCKS - 1e-9, false);
    }

    /**
     * 有界走廊：从"横向偏移点"沿 inward 推进 ≤ {@value #REACH_BLOCKS} block。
     *
     * <p>步长 {@code max(0.5, step/2)} ⇒ 单步在任一坐标轴上的位移 &lt; step
     * ⇒ <b>不会跳过整格</b>（step=1/2/4 都成立）。这是"避免样本稀疏错过"的机制。</p>
     */
    private static int corridor(Frame f, double ox, double oz) {
        double pitch = Math.max(0.5, f.step * 0.5);
        for (int k = 0; k * pitch <= REACH_BLOCKS + 1e-9; k++) {
            double d = k * pitch;
            int s = sample(f, f.originX + f.inX * d + ox, f.originZ + f.inZ * d + oz);
            if (s != SAMPLE_DRY) return s;
        }
        return SAMPLE_DRY;
    }

    /** 最近格取样：本盆湖格 ⇒ WET，其余（含其它盆地水体）⇒ DRY，出窗 ⇒ OUT。 */
    private static int sample(Frame f, double bx, double bz) {
        f.probes++;
        int i = (int) Math.round(bx / f.step);
        int j = (int) Math.round(bz / f.step);
        if (i < 0 || j < 0 || i >= f.nx || j >= f.nz) return SAMPLE_OUT;
        int k = i * f.nz + j;
        if (!f.lake[k]) return SAMPLE_DRY;
        return f.basinId[k] == f.basin ? SAMPLE_WET : SAMPLE_DRY;
    }

    private static double clampHalf(double half) {
        if (!Double.isFinite(half)) return MIN_HALF;
        return Math.max(MIN_HALF, Math.min(MAX_HALF, half));
    }

    private static Measurement unknown(String status, int samples) {
        return new Measurement(Double.NaN, status, samples, Double.NaN);
    }

    /** 单侧结论：{@code extent} = 连续湿的最大横向偏移（-1 = 本侧无湿）。 */
    private record Side(double extent, boolean wet, boolean saturated, boolean clipped) { }

    /** 只读视图 + 采样计数。block 坐标以窗口原点（cell 0）为 0，world 距离全部相对该原点。 */
    private static final class Frame {
        final boolean[] lake;
        final int[] basinId;
        final int nx;
        final int nz;
        final int step;
        final int basin;
        final double originX;
        final double originZ;
        final double inX;
        final double inZ;
        final double perpX;
        final double perpZ;
        int probes;

        Frame(boolean[] lake, int[] basinId, int nx, int nz, int step, int cell, int basin,
              double inX, double inZ) {
            this.lake = lake;
            this.basinId = basinId;
            this.nx = nx;
            this.nz = nz;
            this.step = step;
            this.basin = basin;
            this.originX = (cell / nz) * (double) step;
            this.originZ = (cell % nz) * (double) step;
            this.inX = inX;
            this.inZ = inZ;
            this.perpX = -inZ;
            this.perpZ = inX;
        }
    }
}
