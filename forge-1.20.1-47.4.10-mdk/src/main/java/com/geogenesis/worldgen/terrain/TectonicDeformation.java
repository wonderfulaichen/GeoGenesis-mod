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
    /**
     * 褶皱特征波长（wu）：背斜轴（脊/谷）的间距。
     *
     * <p>★ 2026-09-12 第三次回归后定案（<b>硬约束</b>）：<b>禁止用 dist 作噪声坐标</b>。
     * 原实现为 {@code sin(dist·2π/λ)} → 等值线平行于 Voronoi 边界且<b>绕板块闭合成同心环</b>
     * → 与 T2 chain 的波纹叠加，形成用户三次反馈的"若干条平行带"
     * （偏离 dist 一个波长即一次脊谷，实测 offset 周期 ≈320wu）。</p>
     *
     * <p><b>历史</b>：087698c 已修过（"改为沿走向波：褶皱 sin(dist)→sin(along)"），
     * 8e51a08 回退为 dist 基准 → 波纹回归。本轮改为<b>纯世界坐标噪声</b>，
     * 从根上不可能产生"平行于边界"的几何（噪声与边界法向无关）。</p>
     *
     * <p>取值 600wu：略大于旧波长（300wu），使褶皱更舒缓；但相位与幅度改为
     * 世界坐标噪声后，脊谷不再平行成带，而是形成"波状起伏"的褶皱群
     * （真实褶皱轴本就蜿蜒起伏，非严格平行）。</p>
     */
    static final double FOLD_WAVELENGTH = 600.0;
    /**
     * 褶皱带作用距离（wu）：延伸至前陆。
     *
     * <p>★ 2026-09-12：900 → <b>2600</b>。原值使 {@code decay(dist)} 在近场有显著梯度，
     * 而 {@code dist} 的等值线是多边形（直边+折角）⇒ 多边形被印到地形上。
     * 放宽后 decay 在带内≈常数（<b>常数没有形状</b> ⇒ 不再印多边形），
     * 真正的定位交给世界坐标的 {@link #beltMask}；
     * 同时探针「超出 reach 必须为 0」的判据依然成立（更远才归零）。</p>
     */
    static final double FOLD_REACH = 2600.0;

    // ===== 断层（faults）：挤压（逆断层）/ 拉张（正断层）=====
    /** 断层垂直断距（e 单位）。 */
    static final double FAULT_AMP = 0.045;
    /** 断层间距（wu）：断块宽度。 */
    static final double FAULT_SPACING = 240.0;
    /** 断层带作用距离（wu）。★ 2026-09-12：700 → 2600（理由同 {@link #FOLD_REACH}）。 */
    static final double FAULT_REACH = 2600.0;
    /** 相邻断块去相关速度（越大 → 相邻块高差越明显、崖线越陡）。 */
    static final double FAULT_BLOCK_FREQ = 2.5;
    /**
     * 断块沿走向的相关长度（wu）：断层是分段活动的，故沿走向每约此长度才换一"段"。
     *
     * <p>★ 2026-09-12 由 1400 调大（配合下面的相位尺度）：{@code along} 用<b>绝对世界坐标</b>
     * 投影（{@code wx·tx + wz·tz}），在远离原点处量级可达 1e4；而切向存在微小不连续
     * （配对切换，dTan&lt;1e-4），经此量级放大后 along 可跳 ~100wu
     * → 噪声值跳变 → 伪台阶。加长尺度后同样的 along 跳变引起的相对变化降低数倍，
     * 同时让断层沿走向更连续（此前"串珠感"过强，也不够自然）。</p>
     */
    static final double FAULT_SEGMENT = 4000.0;

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
        // ★ 2026-09-12 修复（用户反馈"地形不像一个整体、每块各自独立生成"）：
        //   **根本改动：不再使用 per-cell 的 alongCoord 作为噪声输入。**
        //
        //   原实现用 alongCoord（每个 Voronoi 单元各自定义的相对坐标）作噪声坐标
        //   → 相邻板块的"沿走向方向"不同 → 同一世界位置的纹理走向不同
        //   → 边界处纹理错位，视觉上"每块独立生成"。
        //
        //   对照 worldgen（参考项目）的正确做法：
        //     · along/across 只用于算 **标量幅度**；
        //     · 随后 blur_grid 高斯模糊（源码注释："Smooth profiles to eliminate
        //       Voronoi ridge discontinuities"）；
        //     · 山脊噪声用 **世界坐标** 采样。
        //   本类此前两步都没做 —— 这是实现偏离，不是参考项目的问题。
        //
        //   现改为：dist（全局连续）决定"平行于边界"的几何，
        //   碎片化改用 **世界坐标噪声**（全局连续）→ 处处无缝、纹理方向一致。
        // ★ 2026-09-12 第五次伪影修复：改用【平滑正部】（见 TectonicField.smoothPos）。
        //   Math.max(0,·) 在 stress=0 处斜率跳变 → 沿该等值线形成折痕 →
        //   等高线挤成笔直细线（用户"不自然断裂线"的直接根因之一）。
        double cw = TectonicField.smoothPos(s.stress(), TectonicField.STRESS_POS_EPS);
        double dw = TectonicField.smoothPos(-s.stress(), TectonicField.STRESS_POS_EPS);

        // 世界坐标分段遮罩：把"平行边界的环状带"打断成**弧段**（真实褶皱/断层带是分段的），
        // 且因用世界坐标，跨板块边界完全连续。
        double mask = segmentMask(wx, wz);

        double fold = cw * foldOffset(s, wx, wz, mask);                          // 褶皱仅挤压环境
        double fault = (cw * 0.6 + dw * 1.0) * faultOffsetUnit(s, wx, wz, mask); // 逆断层弱于正断层
        return fold + fault;
    }

    /** 分段遮罩尺度（wu）：沿边界把长带切成若干弧段。 */
    static final double MASK_SCALE = 1500.0;
    private static final long SALT_MASK = 0x8B41_0C7E_2D93_5AF6L;

    /**
     * 世界坐标分段遮罩 ∈ [0.25, 1]（<b>全局连续</b>，与板块无关）。
     *
     * <p>作用：把"平行于板块边界"的环状带打断成弧段，避免整圈闭环；
     * 同时因为只依赖世界坐标，<b>跨板块边界连续</b> —— 这是修复
     * "每块各自独立生成"的关键（不再引入 per-cell 坐标）。
     */
    private double segmentMask(double wx, double wz) {
        double n = valueNoise(wx / MASK_SCALE, wz / MASK_SCALE, SALT_MASK);   // [-1,1]
        return 0.25 + 0.75 * (n * 0.5 + 0.5);
    }

    /**
     * 褶皱位移：{@code sin(dist·2π/λ)} —— 平行于边界的周期性脊谷。
     *
     * <p>相位沿走向扰动（{@link #alongFaultCoord}），使褶皱轴<b>非严格平行</b>
     * （真实褶皱轴有起伏、呈波状），避免出现"人工平行线"的观感。
     */
    private double foldOffset(TectonicField.Sample s, double wx, double wz, double mask) {
        double reach = FOLD_REACH;
        double d = s.dist();
        if (d >= reach) return 0.0;

        // ★ 世界坐标构造带掩码 × 大幅放宽的 decay（后者在带内≈常数 → 不印多边形）
        double decay = decay(d, reach) * beltMask(wx, wz);
        // ★★★ 2026-09-12 第三次回归修复（用户反馈"若干条平行带"，且"更明显了"）★★★
        //
        //   原实现：wave = sin(dist·2π/λ) —— 相位是 dist 的函数
        //   → 脊谷等值线【平行于 Voronoi 边界】且【绕板块闭合成同心环】
        //   → 叠加 T2 的同类波纹，形成截图所见的"若干条平行带"。
        //   这与项目早已否决的 Terrace「环状台阶伪影」同源（087698c 已注明），
        //   而 8e51a08 为修"每块独立生成"把相位改回 dist 基准 → 波纹回归。
        //
        //   现改为：【纯世界坐标噪声】直接给出起伏。
        //   · 与边界法向无关 → 数学上不可能产生"平行于边界"的带；
        //   · 全局连续、无 per-cell 依赖 → 不会"每块各自生成"；
        //   · dist 仅经 decay 控制作用范围（不参与噪声坐标）。
        //   ridged 形式（1-|n|）使背斜成脊、向斜成谷，符合褶皱的地貌表现。
        double n = valueNoise(wx / FOLD_WAVELENGTH, wz / FOLD_WAVELENGTH, SALT_FOLD);
        double n2 = valueNoise(wx / (FOLD_WAVELENGTH * 2.1) + 13.7,
                               wz / (FOLD_WAVELENGTH * 2.1) - 7.3, SALT_FOLD + 1);
        // ★ 2026-09-12：{@code 1-|x|} 的 ridged 形式在 x=0 处是【V 型折痕】
        //   （斜率由 −1 突变为 +1）→ 沿该零等值线形成锐利细脊，
        //   在去趋势(高通)图上表现为【树状细线网络】（同一类伪影）。
        //   改用圆化绝对值 {@code sqrt(x²+r²)−r}：谷/脊底曲率有限，处处 C^∞。
        double x = 0.7 * n + 0.3 * n2;
        double roundAbs = (Math.sqrt(x * x + FOLD_RIDGE_ROUND * FOLD_RIDGE_ROUND)
                           - FOLD_RIDGE_ROUND) / FOLD_RIDGE_NORM;   // ∈[0,1]，归一化保值域
        double ridge = 1.0 - roundAbs;                       // ∈[0,1]，折痕已圆化
        double wave = ridge * 2.0 - 1.0;                     // 中心化 → 近零均值
        return FOLD_AMP * wave * decay * mask;
    }

    /**
     * 褶皱脊线圆化半径（噪声值域单位）。
     *
     * <p>{@code 1−|x|} 在 x=0 处斜率跳变 → 锐利细脊；圆化后脊底为曲率有限的
     * U 形，折痕消失。取 0.15：圆化区约占值域 30%，远小于褶皱波长(600wu)，
     * 不糊掉褶皱形态。</p>
     */
    private static final double FOLD_RIDGE_ROUND = 0.15;

    /** 圆化绝对值归一化因子 = {@code sqrt(1+r²)−r}（使 |x|=1 映射到 1，值域保持 [0,1]）。 */
    private static final double FOLD_RIDGE_NORM =
            Math.sqrt(1.0 + FOLD_RIDGE_ROUND * FOLD_RIDGE_ROUND) - FOLD_RIDGE_ROUND;

    /**
     * 断层位移：<b>跨越断层线的平滑落差</b>，两侧断块整体错开。
     *
     * <p><b>落差从何而来</b>：取世界坐标噪声的<b>零等值线</b>作为断层线，
     * 在其两侧各做一次 smoothstep 过渡（{@link #SCARP_HALF_WIDTH} 控制崖的陡缓）
     * → 一侧 0、一侧 1，形成"断块整体升降"的落差。
     *
     * <p><b>分段性</b>：断距的沿走向分量以 {@link #FAULT_SEGMENT} 为尺度，
     * 使断层"分段活动"（真实断层由多段组成），而非一整条等强。
     *
     * <p>返回<b>未缩放</b>的单位断距（缩放由调用方按应力加权）。
     *
     * <p>⚠️ <b>禁止改回值域量化</b>（{@code Math.floor(...)}）：量化会把连续噪声切成
     * 阶梯，阶梯的等值线是一族<b>嵌套闭合波浪曲线</b>，在地形上表现为
     * "密集波浪状平行细线"（用户第四次反馈的直接根因，实测 {@code comp_deform.png}
     * 可见同心环）。这与本项目两次否决 Terrace 的「环状台阶伪影」同源。</p>
     */
    private double faultOffsetUnit(TectonicField.Sample s, double wx, double wz, double mask) {
        double reach = FAULT_REACH;
        double d = s.dist();
        if (d >= reach) return 0.0;

        // ★ 同上：世界坐标构造带掩码取代对 dist 的形状依赖
        double decay = decay(d, reach) * beltMask(wx, wz);
        // ★ 2026-09-12 修复（"每块独立生成"）：断块判据改用【世界坐标】噪声，
        //   不再用 per-cell 的 alongCoord。
        //   原用 floor(along/spacing) → 相邻板块 block 基准不同 → 边界两侧断块错位
        //   → 地形"像各自生成"。现用世界坐标噪声取整 → 全局一致、跨边界连续。
        //   距离 d 仍只通过 decay 决定作用范围；mask 把长带切成弧段。
        double slip = valueNoise(wx / FAULT_SEGMENT, wz / FAULT_SEGMENT, SALT_FAULT);
        // ★★★ 2026-09-12 第四次伪影修复（同心嵌套波浪环）★★★
        //
        //   【根因】原式 {@code slipQ = Math.floor(blockN * 3.0) / 3.0} 是【值域量化】。
        //   量化把连续噪声切成阶梯，而<b>阶梯的等值线</b>正是一族
        //   <b>嵌套闭合波浪曲线</b> ⇒ 叠加 decay/mask 后在地形上就是用户截图里的
        //   "密集波浪状平行细线"（实测 {@code comp_deform.png} 直接可见同心环）。
        //   这与本项目<b>两次否决 Terrace</b> 的「环状台阶伪影」<b>完全同源</b>
        //   （证据链见 {@code TerrainParams.plateauSteps} 注释）。
        //   （同图里那些<b>笔直多边形边</b>则来自 {@code decay} 的硬截断与 mask 边界。）
        //
        //   【正解】不做值域量化，改为<b>跨越断层线的单条平滑 sigmoid</b>：
        //   取噪声的<b>零等值线</b>作为断层线（{@code fN=0}），只在它两侧做一次
        //   smoothstep 过渡 → 一侧 0、一侧 1。
        //     · 仍是"断块落差"（相邻块整体错开）——地质语义保留；
        //     · 但每个断层线<b>只有一条</b>过渡带（而非 6~7 级嵌套环）；
        //     · smoothstep 一阶导在两端为 0 ⇒ 处处 C¹，不产生锐利细线/台阶。
        double q = FAULT_BLOCK_FREQ;
        double fN = valueNoise(wx / FAULT_SPACING * q, wz / FAULT_SPACING * q, SALT_FAULT + 7);
        double t = (fN + SCARP_HALF_WIDTH) / (2.0 * SCARP_HALF_WIDTH);
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        double scarp = t * t * (3.0 - 2.0 * t);      // smoothstep：0 → 1（C¹）
        double slipBlk = scarp * 2.0 - 1.0;          // 中心化 → [-1,1]，近零均值
        return FAULT_AMP * (slip * 0.5 + slipBlk * 0.5) * decay * mask;
    }

    /**
     * 断层崖过渡带半宽（噪声值域单位）。
     *
     * <p>控制断层崖的陡缓：越小崖越陡（但仍有 C¹ 斜坡，不会变成台阶）；
     * 越大越平缓。取 0.30 → 过渡带占噪声值域的 60%，落在
     * {@code FAULT_SPACING}(240wu) × {@code FAULT_BLOCK_FREQ}(2.5) ≈ 96wu 的
     * 空间尺度上，崖线宽约 20~40wu，视觉上是"陡坡"而非"台阶"。</p>
     */
    private static final double SCARP_HALF_WIDTH = 0.30;

    // ===== 构造带掩码（世界坐标，取代 dist 定位）=====
    /**
     * 构造带掩码尺度（wu）：带体的世界尺度。
     *
     * <p>取 1100wu（与 {@link #MASK_SCALE} 同量级）——带体是"成片的地质活动区"，
     * 而非贴合多边形的窄条。</p>
     */
    private static final double BELT_SCALE = 1100.0;
    private static final long SALT_BELT = 0x4E2B_9C17_53AF_D081L;

    /**
     * 构造带掩码 ∈[0,1]（<b>纯世界坐标</b>，与 Voronoi 几何无关）。
     *
     * <p>★★★ 2026-09-12 第六次伪影修复（扇形/多边形射线）★★★</p>
     *
     * <p><b>为何必须替换 {@code decay(dist)}</b>：{@code dist = (d2−d1)/2} 的
     * <b>等值线是"平行于 Voronoi 边的多边形"</b>——由<b>直边 + 折角</b>构成。
     * 于是任何 {@code dist} 的函数（{@code decay}、高斯 {@code boundaryStrength}）
     * 都会把这套多边形"印"到地形上，表现为用户反复反馈的<b>直线射线 / 竖带</b>。</p>
     *
     * <p><b>为何模糊解决不了</b>（已实测）：高斯/环形平均能把直边<b>软化</b>，
     * 但<b>直边软化后仍是直边</b>——无法把直线变成曲线。
     * 实测：dist 环形模糊（R=150）+ valueNoise 升级 Catmull-Rom(C¹) 后，
     * {@code comp_deform.png} 的直线射线<b>原样存在</b>。要彻底消除，只能<b>不使用 dist</b>。</p>
     *
     * <p>故改为世界坐标带体掩码：阈值化低频噪声 ⇒ 约 30~40% 面积成为<b>有机弯曲的构造带</b>，
     * 地质语义（"形变集中在活动带内"）保留，但不再与板块多边形绑定。</p>
     */
    private double beltMask(double wx, double wz) {
        double n = valueNoise(wx / BELT_SCALE, wz / BELT_SCALE, SALT_BELT);   // ~[-1,1]
        double t = (n - 0.10) / 0.40;                                         // 阈值 → 约 30~40% 成带
        t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return t * t * (3.0 - 2.0 * t);                                       // C¹ 平滑边界
    }

    /** 平滑衰减：边界处 1，reach 处 0（一阶导为 0，无硬边界）。 */
    private static double decay(double d, double reach) {
        double t = 1.0 - d / reach;
        if (t <= 0.0) return 0.0;
        return t * t * (3.0 - 2.0 * t);   // smoothstep
    }

    // ★ 2026-09-12：已移除 alongFaultCoord（曾用 per-cell 的 alongCoord）。
    //   它是"地形每块各自独立生成"的根因——per-cell 相对坐标使相邻板块的
    //   纹理基准不同，边界处错位。现全部改用世界坐标噪声，见 foldOffset/faultOffsetUnit。

    // ===================== 零依赖极简 value noise =====================

    /**
     * 2D value noise（<b>Catmull-Rom 双三次</b>，C¹），返回约 [-1,1]。
     *
     * <p>★ 2026-09-12 第六次伪影修复：原为<b>双线性 + smoothstep</b>，在 2D 只有 C⁰——
     * {@code ∂f/∂x} 跨 z 格线时跳变（上下两行 {@code (v10−v00)} 不同）⇒ 等值线在格线处
     * 折角 ⇒ 经 {@code faultOffsetUnit} 的 smoothstep 陡坎放大后成为可见"<b>扇形射线</b>"。
     * 详见 {@code TectonicField.valueNoise} 的同类修复说明。</p>
     */
    private double valueNoise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double fx2 = fx * fx, fx3 = fx2 * fx;
        double wx0 = -0.5 * fx3 + fx2 - 0.5 * fx;
        double wx1 = 1.5 * fx3 - 2.5 * fx2 + 1.0;
        double wx2 = -1.5 * fx3 + 2.0 * fx2 + 0.5 * fx;
        double wx3 = 0.5 * fx3 - 0.5 * fx2;
        double fz2 = fz * fz, fz3 = fz2 * fz;
        double wz0 = -0.5 * fz3 + fz2 - 0.5 * fz;
        double wz1 = 1.5 * fz3 - 2.5 * fz2 + 1.0;
        double wz2 = -1.5 * fz3 + 2.0 * fz2 + 0.5 * fz;
        double wz3 = 0.5 * fz3 - 0.5 * fz2;
        double r0 = cell(ix - 1, iz - 1, salt) * wz0 + cell(ix - 1, iz, salt) * wz1
                  + cell(ix - 1, iz + 1, salt) * wz2 + cell(ix - 1, iz + 2, salt) * wz3;
        double r1 = cell(ix, iz - 1, salt) * wz0 + cell(ix, iz, salt) * wz1
                  + cell(ix, iz + 1, salt) * wz2 + cell(ix, iz + 2, salt) * wz3;
        double r2 = cell(ix + 1, iz - 1, salt) * wz0 + cell(ix + 1, iz, salt) * wz1
                  + cell(ix + 1, iz + 1, salt) * wz2 + cell(ix + 1, iz + 2, salt) * wz3;
        double r3 = cell(ix + 2, iz - 1, salt) * wz0 + cell(ix + 2, iz, salt) * wz1
                  + cell(ix + 2, iz + 1, salt) * wz2 + cell(ix + 2, iz + 2, salt) * wz3;
        return r0 * wx0 + r1 * wx1 + r2 * wx2 + r3 * wx3;
    }

    private double cell(int ix, int iz, long salt) {
        long h = (long) ix * 374761393L + (long) iz * 668265263L + salt + seed * 0x9E3779B9L;
        h = (h ^ (h >>> 16)) * 1274126177L;
        h ^= (h >>> 16);
        return ((h & 0xFFFFFFL) / (double) 0x1000000L) * 2.0 - 1.0;
    }
}
