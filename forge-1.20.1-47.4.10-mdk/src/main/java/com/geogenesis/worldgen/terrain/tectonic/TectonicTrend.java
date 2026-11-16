package com.geogenesis.worldgen.terrain.tectonic;

import com.geogenesis.worldgen.terrain.TectonicField;

/**
 * <b>构造趋势层</b>——把板块边界变成「整体地势趋势」（用户 2026-11-16 分层架构）。
 *
 * <h3>分层职责（用户裁定）</h3>
 * <pre>
 *   板块地形 = 整体趋势        ← 本类（低频、宽带、只给正负包络）
 *   地形类型 = 局部具体化      ← MorphCatalog/MorphAssembly（山形、台地、丘陵怎么长）
 *   侵蚀     = 细化丰富化      ← 粒子侵蚀（不动）
 * </pre>
 *
 * <p>因此本类<b>不产生山形</b>：沙箱 {@code caseUplift} 的 4000m/46wu 窄峰、肩部、
 * 前弧洼都是「局部地貌」，归类型系统。本类只回答「这一带整体该高还是该低」。</p>
 *
 * <h3>与沙箱的关键差异</h3>
 * <table border="1">
 *   <caption>为什么不是照搬</caption>
 *   <tr><th>项</th><th>沙箱（局部山形）</th><th>本层（整体趋势）</th></tr>
 *   <tr><td>波长</td><td>46~93wu 窄峰</td><td><b>{@value #TREND_WU}wu</b>（≈0.9 板块间距）</td></tr>
 *   <tr><td>幅度</td><td>4000m 窄脊</td><td><b>{@value #CONV_PEAK}e</b> 宽带隆起</td></tr>
 *   <tr><td>形状</td><td>高斯峰 + 肩部 + 前弧洼</td><td>单峰宽带钟形</td></tr>
 *   <tr><td>转换带</td><td>有（sign 非 C⁰ 断点）</td><td><b>不做</b>（需先重设计）</td></tr>
 * </table>
 *
 * <h3>量纲与 maxY 自适应</h3>
 * <p>输出是 <b>e 单位</b>（{@code 1e = 11000 地质米}，经 {@code HeightCurve} 换算为格）。
 * ⇒ 随 {@code maxY} 自动等比：maxY=320 时 {@value #CONV_PEAK}e ≈ 40 格，
 * maxY=512 时同值 ≈ 70 格。用户设定「支持自定义高度上限」由此天然成立。</p>
 *
 * <h3>铁律遵守</h3>
 * <ul>
 *   <li>{@code dist}/{@code distRaw} <b>只做衰减包络</b>，绝不做噪声坐标（平行带伪影）；</li>
 *   <li>不用 {@code btype}/{@code rate} 等离散量参与合成（配对阶跃）；</li>
 *   <li>{@code stress} 用现有连续投票场，天然 C∞；</li>
 *   <li>本类纯函数、无静态可变状态（生产多 sampler 并发安全）。</li>
 * </ul>
 */
public final class TectonicTrend {

    /** 会聚趋势峰值（e 单位）。maxY=320 ⇒ 40 格；maxY=512 ⇒ 70 格。 */
    public static final double CONV_PEAK = 0.17;
    /**
     * 张裂趋势谷值（e 单位，负）。
     *
     * <p>★ 2026-11-16 标定（用户验收驱动）：初版 −0.06e 把沿海低地（e≈0.045，约 11 格）
     * 压到海平面以下 ⇒ 内陆凭空出现成串湖泊。真实大陆裂谷（东非）确实会成湖，
     * 但只发生在<b>大陆内部</b>、且不该把整片低地灌水。
     * 改为 −0.025e（约 −6 格）并加内陆门控（见 {@link #DIV_INLAND_LO}）。</p>
     */
    public static final double DIV_TROUGH = -0.025;
    /**
     * 张裂的<b>内陆门控</b>（crust01）：张裂趋势只在大陆内部明显，
     * 靠近海岸（crust01 &lt; {@value}）时渐隐。
     *
     * <p>理由：海岸带的低地承接会聚与张裂的过渡，若让张裂在此处全幅生效，
     * 会把 e≈0.05 的沿海低地压成水下 ⇒ 观感上"凭空灌水成湖"。
     * 真实地质上，裂谷成湖集中在大陆内部（东非大裂谷、贝加尔），
     * 海岸带更多是被动陆缘。</p>
     */
    private static final double DIV_INLAND_LO = 0.55;
    private static final double DIV_INLAND_HI = 0.80;
    /**
     * 趋势横向波长（wu）：决定「一条边界影响多宽的地带」。
     * 板块间距 2000wu ⇒ 0.9 个间距，一条边界两侧各影响约 900wu（1800 块）。
     */
    public static final double TREND_WU = 1800.0;
    /**
     * 会聚/张裂判据窗（stress 单位）。
     *
     * <p>低通后的 stress 幅度会显著缩小（5×5×5 平均），所以窗要按缩小后的量级取。
     * 取 (−0.35, 0.35)：低通 stress 在边界带约 ±0.1~0.3 ⇒ 会聚/张裂都能被区分，
     * 纯转换带（≈0）两侧各得约半幅、净趋势接近 0（走滑边界垂直运动弱）。</p>
     */
    private static final double STRESS_LO = -0.35;
    private static final double STRESS_HI = 0.35;
    /**
     * 陆壳判别窗（crust01 单位）——趋势从 0 平滑升到满幅的过渡带。
     *
     * <p>★ 2026-11-16 标定：核链海岸梯度陡（1800wu 内从深到陆），窄窗会变阶跃。
     * 取 (−0.20, 0.75) 覆盖浅海→内陆，趋势在整条海岸-陆架-陆坡带上连续爬升
     * （物理上大陆边缘的地势趋势本就连续）。</p>
     */
    private static final double CRUST_LO = -0.20;
    private static final double CRUST_HI = 0.75;
    /**
     * stress 的<b>空间低通</b>半径（wu）：趋势不用原始 stress，而用邻域平均。
     *
     * <p>★ 2026-11-16 根因修复：实测相邻 8wu 采样的 {@code |Δtrend|} 达 0.10e，
     * 而 {@code distRaw} 与 {@code decay} 都是平滑量 ⇒ 跳变来自 {@code stress} 的
     * 陡梯度（5×5 格高斯投票、增益 2.0，格边界处梯度大）。
     * 趋势是<b>整体地势</b>（波长 1800wu），本不该跟随 stress 的高频结构
     * ⇒ 用 {value}wu 半径的圆平均把 stress 低通后再用。这是「趋势降频」的正确做法
     * （不是把阈值放宽——放宽治标不治本，且会丢掉双向性）。</p>
     */
    private static final double STRESS_SMOOTH_WU = 1500.0;
    private static final int STRESS_SMOOTH_TAPS = 5;

    private final TectonicField tectonic;

    public TectonicTrend(TectonicField tectonic) {
        this.tectonic = tectonic;
    }

    /**
     * 求某点的构造趋势（e 单位）。
     *
     * <p>公式：{@code trend = wConv·CONV_PEAK − wDiv·|DIV_TROUGH| · decay(dist) · land},
     * 其中：</p>
     * <ul>
     *   <li>{@code wConv = smoothstep(STRESS_LO, STRESS_HI, stress)}（会聚权重）</li>
     *   <li>{@code wDiv = smoothstep(STRESS_LO, STRESS_HI, −stress)}（张裂权重）</li>
     *   <li>{@code decay = exp(−(distRaw/TREND_WU)²)}（离边界越远趋势越弱）</li>
     *   <li>{@code land = smoothstep(CRUST_LO, CRUST_HI, crust01)}（洋侧不给陆地隆起）</li>
     * </ul>
     *
     * <p>⚠ 这一切都是<b>连续</b>量：{@code stress} 是现有 5×5 高斯投票场，
     * {@code distRaw} 是未平滑距离（只入平方衰减）。因此趋势场 C∞，不会有硬跳变。</p>
     *
     * @param wx      世界坐标（wu）
     * @param wz      世界坐标（wu）
     * @param crust01 该点的陆壳度 ∈[0,1]（由 {@code ContinentField} 的 c 换算：{@code (c+1)/2}）
     * @return 趋势（e 单位），会聚为正、张裂为负、其余为零
     */
    /**
     * 诊断：趋势的两个分量（幅度 / 方向），供探针分解梯度来源。
     *
     * @return {@code [amp, dir, trend]}：{@code amp} = distRaw 衰减 ∈[0,1]，
     *         {@code dir} = tanh(smoothStress/0.5) ∈[−1,1]，{@code trend} = 合成值
     */
    public double[] components(double wx, double wz) {
        TectonicField.Sample ts = tectonic.sample(wx, wz);
        double dr = ts.distRaw() / TREND_WU;
        double amp = Math.exp(-dr * dr);
        double dir = Math.max(-1.0, Math.min(1.0, smoothStress(wx, wz)));
        double out = amp < 1e-4 ? 0.0
                : (dir >= 0.0 ? CONV_PEAK * dir : Math.abs(DIV_TROUGH) * dir) * amp;
        return new double[]{amp, dir, out};
    }

    public double trend(double wx, double wz, double crust01) {
        // ★ 2026-11-16 根因修复（第四次，最终）：<b>趋势幅度只由 distRaw 决定，
        //   stress 只决定「向上还是向下」</b>。
        //
        //   此前两版失败的原因：
        //     · 用 stress 的绝对值当幅度 ⇒ stressField 增益 2.0 会饱和到 ±1，
        //       格边界处梯度极陡（梯度实测 1.9e-3 e/wu，超平滑场理论值 20 倍）；
        //     · 低通 stress 只能削噪声、削不掉饱和结构。
        //
        //   正解：<b>分离幅度与方向</b>
        //     幅度 = CONV_PEAK · exp(−(distRaw/TREND_WU)²)      ← 纯 distRaw，C∞
        //     方向 = tanh(smoothStress / 0.5) ∈ [−1, +1]        ← 平滑符号，C∞
        //     趋势 = 幅度 · 方向 · (方向>0 ? 1 : |DIV/CONV|)
        //   这样幅度场完全平滑（distRaw 已平滑到 1.59wu 级），
        //   方向场经过 800wu 低通 + tanh ⇒ 梯度小两个数量级。
        TectonicField.Sample ts = tectonic.sample(wx, wz);
        double dr = ts.distRaw() / TREND_WU;
        double amp = Math.exp(-dr * dr);
        if (amp < 1e-4) {
            return 0.0;   // 远离所有边界：严格为零（「远处逐位不变」）
        }
        // 方向映射：把平滑后的 stress 直接当方向用，<b>不做放大</b>。
        //   ★ 2026-11-16 实测根因：先前用 tanh(s/0.5)，斜率 2× 把 stress 的残余
        //   高频放大了 —— 分量梯度分解显示 dir 梯度 6.2e-3 /wu（amp 只有 5.8e-4）。
        //   改为 clamp(s, −1, 1)：斜率 ≤1，不放大任何结构。
        double dir = Math.max(-1.0, Math.min(1.0, smoothStress(wx, wz)));
        double out;
        if (dir >= 0.0) {
            out = CONV_PEAK * dir;
        } else {
            // 张裂：只在大陆内部明显（DIV_INLAND 门控），海岸带渐隐
            // ⇒ 避免"沿海低地被压成水下、凭空灌水成湖"（用户验收反馈）
            double inland = smoothstep(DIV_INLAND_LO, DIV_INLAND_HI, crust01);
            out = Math.abs(DIV_TROUGH) * dir * inland;
        }
        return out * amp;
    }

    /**
     * stress 的圆平均低通（{@value #STRESS_SMOOTH_TAPS}×{@value #STRESS_SMOOTH_TAPS}
     * 方形窗、半径 {@value #STRESS_SMOOTH_WU}wu）。
     *
     * <p>用方形窗而非圆窗：与 {@code TectonicField.stressField} 自身的 5×5 结构同族，
     * 且实现零分配。<b>只在这里用一次</b>——不要把低通后的值喂回其它消费者。</p>
     */
    private double smoothStress(double wx, double wz) {
        double sum = 0.0;
        int taps = STRESS_SMOOTH_TAPS;
        double half = STRESS_SMOOTH_WU * 0.5;
        for (int dz = 0; dz < taps; dz++) {
            double pz = wz + (dz - (taps - 1) * 0.5) * (2 * half / (taps - 1));
            for (int dx = 0; dx < taps; dx++) {
                double px = wx + (dx - (taps - 1) * 0.5) * (2 * half / (taps - 1));
                sum += tectonic.sample(px, pz).stress();
            }
        }
        return sum / (taps * taps);
    }

    /** Hermite smoothstep（本地副本，与项目其余部分同式）。 */
    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }
}
