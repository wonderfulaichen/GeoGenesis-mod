package com.geogenesis.worldgen.terrain;

/**
 * 构造形变场（★ 2026-09-12，地质系统 Phase T5）：<b>褶皱</b>与<b>断层</b>。
 *
 * <p>这是地质系统 T1~T5 的最后一块，为造山带/裂谷带来"被构造应力改造过"的
 * 线性地形特征（此前完全缺失）。
 *
 * <h3>核心几何洞察：两者都建立在"到边界距离"之上</h3>
 * <p>{@link TectonicField.Sample#dist()} 是点到板块边界的距离，
 * 其<b>等值线平行于边界</b>。因此：
 * <ul>
 *   <li><b>褶皱</b> = {@code sin(dist · 2π/λ)} —— 等值线处成脊/成谷
 *       → <b>背斜（anticline）/ 向斜（syncline）</b>，轴面平行于造山带走向 ✓</li>
 *   <li><b>断层</b> = {@code floor(dist / spacing)} 分块 —— 块边界即断层面
 *       → 相邻断块整体升降差 = <b>断层崖</b>；交替升降 = <b>地垒/地堑</b> ✓</li>
 * </ul>
 * 二者<b>零额外采样</b>（dist 已由 T1 算出，仅做数学映射）。
 *
 * <h3>与构造环境的关系（地质依据，非随机）</h3>
 * <table border="1">
 *   <caption>构造应力 → 形变类型</caption>
 *   <tr><th>边界类型</th><th>应力</th><th>形变</th></tr>
 *   <tr><td>汇聚 CONVERGENT</td><td>挤压</td><td><b>褶皱</b>（背斜/向斜）+ 逆断层</td></tr>
 *   <tr><td>离散 DIVERGENT</td><td>拉张</td><td><b>正断层</b> → 地垒/地堑</td></tr>
 *   <tr><td>走滑 TRANSFORM</td><td>剪切</td><td>无垂向形变（与 T1 一致）</td></tr>
 *   <tr><td>内部 INTERIOR</td><td>—</td><td>无形变</td></tr>
 * </table>
 *
 * <h3>作用范围</h3>
 * <p>褶皱/断层带在真实地质中可延伸至<b>前陆</b>（数十 km），故作用距离
 * 取比 T1 的边界宽度（{@code BOUNDARY_REACH=320wu}）更远的、独立的 reach，
 * 并以平滑函数衰减（非硬截断，避免出现环形硬边界）。
 *
 * <h3>约束</h3>
 * <ul>
 *   <li><b>零依赖纯函数</b>、<b>确定性</b></li>
 *   <li><b>廉价</b>：仅三角函数 + 1 次 value noise（纳秒级），dist/tangent 复用 T1 结果</li>
 *   <li><b>近零均值</b>：正弦天然零均值；断层分块用中心化哈希 → 不整体抬升/压低地形
 *       （T1 的教训：整体抬升会连锁改变降水与河宽）</li>
 * </ul>
 */
public final class TectonicDeformation {

    // ===== 褶皱（folds）：挤压环境 =====
    /** 褶皱振幅（e 单位）。 */
    static final double FOLD_AMP = 0.032;
    /** 褶皱波长（wu）：背斜轴间距。 */
    static final double FOLD_WAVELENGTH = 300.0;
    /** 褶皱带作用距离（wu）：延伸至前陆。 */
    static final double FOLD_REACH = 900.0;

    // ===== 断层（faults）：挤压（逆断层）/ 拉张（正断层）=====
    /** 断层垂直断距（e 单位）。 */
    static final double FAULT_AMP = 0.045;
    /** 断层间距（wu）：断块宽度。 */
    static final double FAULT_SPACING = 240.0;
    /** 断层带作用距离（wu）。 */
    static final double FAULT_REACH = 700.0;
    /** 相邻断块去相关速度（越大 → 相邻块高差越明显、崖线越陡）。 */
    static final double FAULT_BLOCK_FREQ = 2.5;
    /** 断块沿走向的相关长度（wu）：断层是分段活动的，故沿走向每约此长度才换一"段"。 */
    static final double FAULT_SEGMENT = 1400.0;

    private static final long SALT_FOLD = 0x3B91_D7C4_5E02_1A87L;
    private static final long SALT_FAULT = 0x6D2A_F813_B94C_70E5L;

    private long seed;

    public TectonicDeformation(long seed) {
        this.seed = seed;
    }

    /** 换世界种子（形变相位/断块分布随之改变）。 */
    public void setSeed(long worldSeed) {
        this.seed = worldSeed;
    }

    /**
     * 构造形变引起的高程偏置（e 单位，近零均值）。
     *
     * @param s  构造采样（由 {@link TectonicField#sample} 提供，调用方通常已持有）
     * @param wx 世界 X（wu）
     * @param wz 世界 Z（wu）
     * @return 高程偏置；内部/走滑或超出作用距离时返回 0
     */
    public double offset(TectonicField.Sample s, double wx, double wz) {
        return switch (s.btype()) {
            // 挤压 → 褶皱 + 逆断层
            case TectonicField.CONVERGENT ->
                    foldOffset(s, wx, wz) + faultOffset(s, wx, wz, 0.6);   // 逆断层断距小于正断层
            // 拉张 → 正断层（地垒/地堑），无褶皱
            case TectonicField.DIVERGENT ->
                    faultOffset(s, wx, wz, 1.0);
            // 走滑无垂向形变（与 T1 的判定一致）；内部无形变
            default -> 0.0;
        };
    }

    /**
     * 褶皱位移：{@code sin(dist·2π/λ)} —— 平行于边界的周期性脊谷。
     *
     * <p>相位沿走向扰动（{@link #alongFaultCoord}），使褶皱轴<b>非严格平行</b>
     * （真实褶皱轴有起伏、呈波状），避免出现"人工平行线"的观感。
     */
    private double foldOffset(TectonicField.Sample s, double wx, double wz) {
        double reach = FOLD_REACH;
        double d = s.dist();
        if (d >= reach) return 0.0;

        double decay = decay(d, reach);
        // 相位扰动：沿走向低频 → 褶皱轴波状起伏
        double along = alongFaultCoord(s, wx, wz);
        double phase = valueNoise(along / 1600.0, d / 2600.0, SALT_FOLD) * Math.PI;
        double wave = Math.sin(d / FOLD_WAVELENGTH * 2.0 * Math.PI + phase);

        // 幅度沿走向也做调制：褶皱不是处处等强（真实褶皱带强弱相间）
        double ampMod = 0.55 + 0.45 * valueNoise(along / 900.0, 3.7, SALT_FOLD + 1);
        return FOLD_AMP * wave * decay * ampMod;
    }

    /**
     * 断层位移：把 {@code dist} 量化成断块，每块有中心化的随机垂直断距。
     *
     * <p><b>崖线从何而来</b>：块索引 {@code floor(dist/spacing)} 在块边界处<b>跳变 1</b>，
     * 而块断距取 {@code valueNoise(..., blockIndex·FREQ, ...)} —— 在块索引坐标上高频，
     * 故跨过块边界时断距<b>骤变</b> → 形成陡立的<b>断层崖</b>；块内则沿走向平滑变化。
     *
     * <p><b>分段性</b>：断距的沿走向坐标以 {@link #FAULT_SEGMENT} 为尺度，
     * 使断层"分段活动"（真实断层由多段组成），而非一整条等强。
     *
     * @param scale 断距缩放（逆断层弱于正断层）
     */
    private double faultOffset(TectonicField.Sample s, double wx, double wz, double scale) {
        double reach = FAULT_REACH;
        double d = s.dist();
        if (d >= reach) return 0.0;

        double decay = decay(d, reach);
        double block = Math.floor(d / FAULT_SPACING);
        double along = alongFaultCoord(s, wx, wz) / FAULT_SEGMENT;
        // 中心化（valueNoise 输出 [-1,1]）→ 断块有升有降 → 近零均值，不整体平移地形
        double slip = valueNoise(along, block * FAULT_BLOCK_FREQ, SALT_FAULT);
        return FAULT_AMP * scale * slip * decay;
    }

    /** 平滑衰减：边界处 1，reach 处 0（一阶导为 0，无硬边界）。 */
    private static double decay(double d, double reach) {
        double t = 1.0 - d / reach;
        if (t <= 0.0) return 0.0;
        return t * t * (3.0 - 2.0 * t);   // smoothstep
    }

    /**
     * 沿断层走向的坐标（wu）：把世界点投影到边界切向。
     * 用于相位/分段——保证同一断层的不同位置共享一致的沿走向参数。
     */
    private static double alongFaultCoord(TectonicField.Sample s, double wx, double wz) {
        return wx * s.tangentX() + wz * s.tangentZ();
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
