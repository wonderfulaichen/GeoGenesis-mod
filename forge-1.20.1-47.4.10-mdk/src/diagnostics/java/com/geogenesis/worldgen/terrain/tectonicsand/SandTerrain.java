package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.List;

/**
 * 沙箱：<b>板块地形合成</b>——把 {@link SandPlateField} 的板块几何变成高度（e 单位）。
 *
 * <h2>2026-11-14 重写：改用参考项目（yuuranku/planet-simulation，`site/core/planet.js`）
 * 的合成结构</h2>
 * <p>此前用"√年龄律洋深 + 高斯山带"，观感始终不像地球板块。对照参考源码后，
 * 换成它的三层结构（思想借鉴，实现独立编写；该仓库无许可证，<b>代码一律不得复制</b>）：</p>
 *
 * <h3>① 地壳 → 基础高程：<b>分段线性映射</b>（planet.js "基础高程/地壳均衡"）</h3>
 * <pre>
 *   c &lt; 0.30   深海 −4500 ± 700 m          （海底有噪声起伏）
 *   0.30–0.45  陆坡 −4500 → −160 m
 *   0.45–0.52  陆架 −160 → +40 m
 *   c &gt; 0.52   陆地 +40 → +560 m
 * </pre>
 * <p>{@code c} 来自<b>独立的大陆层</b> {@link SandContinent} 的壳值，经
 * {@link #crust01} 映射（见 ④）。这个分段映射天然给出"窄陆架 + 陡陆坡 + 平深海"三段。</p>
 *
 * <h3>② 构造抬升：<b>四案例 bump 剖面</b>（按边界两侧壳类型选案例，单位米）</h3>
 * <pre>
 *   陆陆碰撞  +4000·bump(d,0.6°,3.0°) + 1500·bump(d,4°,6°)     近峰 + 远高原
 *   陆侧俯冲  +3900·bump(d,1.6°,2.2°) − 600·bump(d,0,0.6°)      山 + 前弧凹
 *   洋侧被俯冲 −3600·bump(d,0,1.1°)                              海沟
 *   洋洋      ±(3700·bump(d,1.5°,1.0°) − 1200·bump(d,0,0.5°))    岛弧 + 沟
 *   离散(陆)  −1000·bump(d,0,1.2°) + 450·bump(d,1.8°,1.0°)      裂谷 + 肩部
 *   离散(洋)  +1900·bump(d,0,4.5°)                               洋中脊（宽 bump）
 *   转换      shear·380·bump(d,0,1.0°)·(噪声定符号)
 * </pre>
 * <p>⚠ 这替换掉了 √ 年龄律：参考的洋中脊是<b>局地宽 bump</b>（1900 m、宽 4.5°），
 * 不是 √ 律的长翼——后者在我方渲染里读作"洋脊缓丘"，正是"不像"的来源之一。</p>
 *
 * <h3>③ 连续性：按<b>候选边界对加权混合</b>，而不是取最近一条</h3>
 * <p>案例由边界两侧壳类型决定（每对恒定），但最近对在中轴上会换人
 * ⇒ 若只取最近一条的案例，切换处会跳。把每个候选对的 bump 响应
 * 按 {@code exp(−d²/2σ²)} 加权平均（σ = {@link #BLEND_SIGMA}），
 * 权重连续 ⇒ 总量连续。这与我方 direct/shear 的加权混合同构。</p>
 *
 * <h3>④ 壳值 → c：<b>分位锚点映射</b>（参考"陆壳面积标定"的无限世界改法）</h3>
 * <p>参考在球面上算完整图再取分位平移。我们要求<b>无限生成</b>，运行期拿不到全图统计，
 * 所以把锚点<b>离线标定一次后冻结</b>（{@link #CRUST_ANCHOR_FIXED}），
 * 运行期只做逐点局部映射（四段 smoothstep 相加，段间 C¹）。</p>
 *
 * <h3>③b 离散分支必须改成连续加权（2026-11-14）</h3>
 * <p>参考用离散判断（{@code own/oth > 0.5}、{@code conv>0/&lt;0}），靠粗网格双线性抹平。
 * 我方是全分辨率连续采样，离散分支会沿等值线整体跳变——实测相邻 3.9wu 跳 <b>118 格</b>。
 * ⇒ 案例权重、边界类型权重全部改为<b>连续加权投票</b>（权重和 = 1）。</p>
 *
 * <h2>公式来源（2026-11-14 收敛）</h2>
 * <ul>
 *   <li><b>唯一借鉴对象：{@code 参考/sources/planet-simulation}（planet.js）</b>——
 *       分段地壳映射、四案例 bump、双分辨率网格、火山随机撒点、网格级后处理。
 *       ⚠ 该文件<b>无许可证</b> ⇒ 只借鉴结构思想，实现全部独立编写。</li>
 *   <li><b>已发表算法</b>：Cordonnier et al. (2016)（构造抬升 + 河流侵蚀）、
 *       Parsons &amp; Sclater (1977)（洋壳 √ 年龄律）、Braun &amp; Willett (2013)
 *       （隐式 SPL 侵蚀）——算法不受版权保护，实现独立编写。
 *       <b>这些是文献名，不是还在使用的参考项目</b>。</li>
 * </ul>
 */
public final class SandTerrain {

    // ============================================================ 尺度与换算

    /** 米 → e 的统一换算（= 生产链的洋深压缩口径：5500m ↔ 0.5e）。 */
    static final double METERS_PER_E = 11000.0;

    /**
     * 速度场平滑尺度 σ_v（wu）。板块"刚性"由它控制：越小 ⇒ 应变越集中在边界。
     */
    static final double VELOCITY_SIGMA = 200.0;
    /**
     * 应变归一化尺度。GosplManager/planet.js 的速率口径是
     * {@code S = min(1.6, |conv|/0.9)}，我方 {@code divN} 边界带 ≈ 1.2 ⇒ 取 8.5e-4。
     */
    static final double DIV_SCALE = 8.5e-4;
    /**
     * 速度差的归一化尺度（无量纲）。参考的 {@code S = min(1.6, conv/0.9)}：
     * 0.9 是"典型板块速度差"的标定。我方相干流场（FLOW_SCALE=2000、
     * 板块间距 700）的边界带 |conv| ≈ 0.45 ⇒ 取 0.45 作单位，边界带 convN ≈ 1。
     */
    static final double CONV_SCALE = 0.45;
    /** 邻域板块窗口半径（格）：3（尺寸悬殊后最近种子可能更远）。 */
    static final int PAIR_RADIUS = 3;

    // ---- 边界响应的混合 ----
    /** 候选边界响应的混合尺度 σ（wu）。 */
    static final double BLEND_SIGMA = 60.0;
    /** 度 → wu 换算（700wu 板块 ≈ 5000km ⇒ 1wu ≈ 7.14km ⇒ 1° ≈ 111km ≈ 15.5wu）。 */
    static final double DEG_WU = 15.5;
    /**
     * 构造响应的作用截止（wu）。参考：{@code if (d > 14) continue}（14° ≈ 217wu）。
     */
    static final double RESPONSE_CUT = 14.0 * DEG_WU;

    // ============================================================ ① 地壳→高程分段映射（e 单位）
    //
    // ★ 2026-11-14 逐字对齐参考 planet.js 第 4 段「基础高程（粗网格）」：
    //     if (c < 0.3)      e = -4500 + 700*fbm        // 深海平原
    //     else if (c < 0.45) e = -4500 + 4340*smooth(0.3,0.45,c)   // 大陆坡（占 0.15 壳值！）
    //     else if (c < 0.52) e = -160 + 200*(c-0.45)/0.07           // 陆架
    //     else               e = 40 + 520*(c-0.52)/0.48             // 陆地
    // 三处关键差异（此前写错，用户看到"大陆是平顶板块+黑圈海岸"）：
    //   ① 海陆阈值是 <b>0.52</b>，不是 0.46；
    //   ② 大陆坡带是 <b>0.30→0.45（宽 0.15）</b>，我之前用 0.30→0.38（宽 0.08）⇒ 坡陡一倍；
    //   ③ 大陆坡用 <b>smooth()</b>（smoothstep）而不是线性 ⇒ 两端 C¹，不产生等壳值线折角。

    /** 深海基准（−4870 m）。参考 −4500；这里取地球实测的深海平原高度（4880 m）。 */
    static final double DEEP_BASE = -4870.0 / METERS_PER_E;
    /**
     * 深海噪声幅度（±300 m）——大尺度洋盆起伏。
     *
     * <p>⚠ 参考是 ±700 m；地球的深海平原是<b>平坦</b>的（p75 与 p90 都 ≈4880 m），
     * ±700 会把平原摊成 −3800~−5200 的起伏面，与地球对不上。</p>
     */
    static final double DEEP_NOISE = 300.0 / METERS_PER_E;
    /**
     * <b>深海丘陵</b>（abyssal hills）幅度与波长。
     *
     * <p>★ 2026-11-14 地质学补缺：原实现只有 700wu（≈5000 km）波长的起伏，
     * 实测洋底 25 km 尺度的坡度只有 <b>2.1 m/km</b>，而地球是 <b>30~120 m/km</b>
     * （深海丘陵：高 50~300 m、波长 10~100 km）。⇒ 补一档 45wu（≈320 km）
     * 基波、3 倍频（最细 11wu ≈ 80 km）、±160 m。</p>
     */
    static final double ABYSS_HILLS = 100.0 / METERS_PER_E;
    static final double ABYSS_HILL_SCALE = 45.0;
    private static final long SALT_ABYSS = 0x7C1D5E93A62B408FL;

    // ============================================================ 洋壳年龄—深度律（√t）

    /**
     * <b>洋壳年龄 → 深度</b>：<b>平台（plate）模型</b>
     * {@code d = D0 + (Dinf − D0)·(1 − e^{−t/τ})}（m，t 单位 Ma）。
     *
     * <h3>为什么不用 √t</h3>
     * <p>Parsons &amp; Sclater (1977) 的 √t 律 {@code d = 2500 + 350√t} 在 t→∞ 时<b>无界</b>：
     * t=150 Ma 给 6787 m，而真实洋底在 ~90 Ma 之后<b>趋于平台</b>
     * （冷却的热边界层达到平衡厚度，深度渐近约 5900 m）。
     * 实测用 √t 时洋深 <b>5~6 km 带占 43%</b>（地球 ~20%）—— 深度一路加深没有上限。</p>
     *
     * <h3>参数标定（2026-11-14，两轮实测）</h3>
     * <ul>
     *   <li>{@code D0 = 2500 m}：脊顶深度（地幔潜能温度决定，全球普遍 −2500 m）。</li>
     *   <li>{@code Dinf = 5900 m}：平台深度。
     *       ⚠ 第一版取 6400 ⇒ 实测"6~7 km"带占 <b>41.3%</b>（地球 2%）、5~6 km 只剩 9.9%
     *       —— 平台定得太深。地球的深海平原主带是 <b>−5000~−6000</b>（占洋面 ~20%），
     *       −6000 以下只有海沟。改 5900 后主带回到 5~6 km。</li>
     *   <li>{@code τ = 50 Ma}：标定使 t=20 Ma 时深度 ≈ 3604 m，与 √t 的 4065 m 同量级。</li>
     * </ul>
     *
     * <p>★ 这仍是"板块构造 → 洋底地形"的物理链：深度只是<b>到洋中脊的距离</b>的函数。
     * 只是把"无界冷却"换成"达到平衡厚度"。</p>
     */
    static final double AGE_D0 = 2500.0;
    static final double AGE_DINF = 5700.0;
    static final double AGE_TAU = 70.0;
    /**
     * 年龄律的<b>有效上限</b>（Ma）。
     *
     * <p>★ 2026-11-14 <b>三档实测</b>（1000² 窗口，洋面深度分带）：</p>
     * <table border="1">
     *   <caption>AGE_TMAX 对洋深分布的影响</caption>
     *   <tr><th>TMAX</th><th>4~5 km</th><th>5~6 km</th><th>6~7 km</th><th>p50</th></tr>
     *   <tr><td>90</td><td><b>45.3%</b></td><td>3.7%</td><td>0.1%</td><td>−3929</td></tr>
     *   <tr><td>140</td><td>13.6%</td><td>37.9%</td><td>0.4%</td><td>−4173</td></tr>
     *   <tr><td>200</td><td>9.5%</td><td>43.1%</td><td>1.0%</td><td>−4386</td></tr>
     *   <tr><td>地球</td><td>45%</td><td>20%</td><td>2%</td><td>−3880</td></tr>
     * </table>
     * <p>⇒ 取 <b>90 Ma</b>：4~5 km 主带与地球完全吻合（45.3% vs 45%）、p50 也最接近。
     * 代价是 5~6 km 带偏少（3.7% vs 20%）——地球那 20% 来自"最老洋底 + 海沟外坡"，
     * 我们的洋盆尺度（脊间距 700wu）里没有那么多最老洋底。</p>
     */
    static final double AGE_TMAX = 90.0;
    /**
     * 1 Ma 对应多少 wu（半扩张速率）。
     *
     * <p>标定：边界间距 700wu ⇒ 脊到脊中点最远 350wu；取 350wu ↔ 90 Ma
     * ⇒ 3.9 wu/Ma = 28 km/Ma = 2.8 cm/yr（半速率）——与真实洋中脊 1~5 cm/yr 同量级。</p>
     */
    static final double SPREAD_WU_PER_MA = 3.9;
    /** 附近没有离散边界时的"等效远距离"（≈ 最老洋壳）。 */
    static final double RIDGE_FAR = AGE_TMAX * SPREAD_WU_PER_MA;

    /** 由"到最近离散边界的距离"求洋底基准高度（e，负值）。 */
    static double abyssByAge(double ridgeDist) {
        double tMa = Math.min(AGE_TMAX, Math.max(0.0, ridgeDist / SPREAD_WU_PER_MA));
        return -(AGE_D0 + (AGE_DINF - AGE_D0) * (1.0 - Math.exp(-tMa / AGE_TAU)))
                / METERS_PER_E;
    }

    /**
     * <b>主动陆缘</b>参数：把陆架压成多深、影响范围多宽。
     *
     * <p>地质学：主动陆缘（安第斯型/日本型）没有宽陆架，海岸外 0~50 km 就掉到
     * 2~4 km 深。{@code ACTIVE_SHELF_DROP=2200 m} 把陆架带压到 −2 km 级。</p>
     *
     * <p>⚠ 2026-11-14 修正 {@code MARGIN_SCALE}：原来取 260wu（≈1800 km），
     * 而一个大陆半轴才 500~1250wu ⇒ 那个衰减尺度<b>覆盖了大半个大陆</b>，
     * 几乎<b>每一条</b>海岸都被当成主动陆缘削掉 2.2 km ⇒ 实测陆架宽度 P50 只有
     * 4 格（125 km）、P10 = 1 格（31 km），而地球被动陆缘是 50~300 km。
     * 真实的主动陆缘只出现在<b>紧邻俯冲带</b>的那一段海岸（陆壳宽度 30~150 km）。
     * 改为 90wu（≈640 km），约等于"离会聚边界一个陆壳宽度"。</p>
     */
    static final double ACTIVE_SHELF_DROP = 2200.0 / METERS_PER_E;
    static final double MARGIN_SCALE = 90.0;
    /** 陆坡顶（−160 m）。 */
    static final double SLOPE_TOP = -160.0 / METERS_PER_E;
    /** 陆架面（+40 m）。 */
    static final double SHELF_TOP = 40.0 / METERS_PER_E;
    /** 陆地顶（+520 m）。 */
    static final double LAND_TOP = 520.0 / METERS_PER_E;
    /** 地壳阈值：深海/陆坡（0.30）、陆坡/陆架（0.45）、陆架/陆地（0.52）。 */
    static final double C_DEEP = 0.30;
    static final double C_SLOPE = 0.45;
    static final double C_SHELF = 0.52;

    // ============================================================ ② 内陆起伏（参考 cfg.interior 三档）

    /**
     * 内陆起伏档位：0 = 平缓、1 = 中等（默认，地球量级）、2 = 崎岖。
     *
     * <p>对应参考 {@code cfg.interior} 的三条分支，公式见 {@code partsOf} 的 ②。</p>
     */
    static int INTERIOR_STYLE = 1;

    /**
     * 构造抬升的整体缩放（参考 {@code mScale}），随 {@link #INTERIOR_STYLE} 变化。
     *
     * <p>参考：{@code mScale = (6400/radius)^0.4 · [0.85,1,1.25][interior] · (0.75+0.25·activity)}。
     * 我方没有"星球半径 / 活动度"配置 ⇒ 取半径 6400 km、activity = 1，
     * 只保留随档位变化的因子。</p>
     */
    static double MSCALE = mScale(INTERIOR_STYLE);
    static final double PLANET_RADIUS_KM = 6400.0;
    static final double ACTIVITY = 1.0;

    /** mScale 的公式（唯一来源；改档位走 {@link #setInteriorStyle}）。 */
    static double mScale(int interiorStyle) {
        double[] byStyle = {0.85, 1.0, 1.25};
        int k = Math.max(0, Math.min(2, interiorStyle));
        return Math.pow(6400.0 / PLANET_RADIUS_KM, 0.4) * byStyle[k] * (0.75 + 0.25 * ACTIVITY);
    }

    /** 切换内陆起伏档位（同时刷新 {@link #MSCALE}）。 */
    static void setInteriorStyle(int k) {
        INTERIOR_STYLE = Math.max(0, Math.min(2, k));
        MSCALE = mScale(INTERIOR_STYLE);
    }

    /** 内陆高地（+1150 m）。 */
    static final double INTERIOR_HIGH = 1150.0 / METERS_PER_E;
    /** 内陆盆地（−300 m）。 */
    static final double INTERIOR_BASIN = 300.0 / METERS_PER_E;
    /** 内陆起伏的噪声波长（wu）。参考 p*2.4（球面单位）≈ 0.4 圈 ⇒ 取 600wu。 */
    static final double INTERIOR_SCALE = 320.0;
    private static final long SALT_INTERIOR = 0x5B2A6E4F19C3DL;
    private static final long SALT_INTERIOR2 = 0x3D8F7C21A5B940E6L;

    // ============================================================ ② 构造 bump 常量（e 单位；宽度：度×DEG_WU）

    // ---- 山脉细节（参考 planet.js：mtn=|u|/1800、rug 脊状噪声、e+=u·(0.45+1.05·rug)）----
    /** 山脉掩码的参考幅度（e）：|u| 达到它算"满山"。参考 1800 m。 */
    static final double MTN_REF = 1800.0 / METERS_PER_E;
    /**
     * 脊状噪声的波长（wu）。参考 ridged3 用 px*7（球面单位）⇒ 约 88wu。
     * ⚠ 不能用 55wu + 4 倍频：最细倍频 7wu ≈ 2 格 ⇒ 山带变成锯齿状尖刺（无法分辨）。
     * 取 110wu + 3 倍频 ⇒ 最细 27wu ≈ 8 格，山脊能连成线。
     */
    static final double MTN_RUG_SCALE = 110.0;
    /** 山脊纹理的倍频数。 */
    static final int MTN_RUG_OCT = 3;
    private static final long SALT_RUG = 0x4C9F1E7A3B5D2C68L;
    /**
     * 近海细节的门控尺度（e）：参考 {@code exp(−|e|/500m)}。
     *
     * <p>⚠ 我方取 2000 m（不是 500 m）：参考在<b>粗网格</b>上算，|e| 每格变化很小；
     * 我方全分辨率下海岸处 |e| 每格变化约 0.04e（≈440 m），500 m 的门控
     * 会在一格内从 0.4 掉到 0.07 ⇒ 把 120 m 的细节纹理变成一道 2 格阶跃。
     * 放宽到 2000 m 后门控跨 5 格渐变。</p>
     */
    static final double COAST_NEAR_E = 2000.0 / METERS_PER_E;
    /** 近海细节幅度（e）：参考 coastDetail = 60/120/230 m，取中档。 */
    static final double COAST_DETAIL = 120.0 / METERS_PER_E;
    /** 近海细节的波长（wu）：参考 px*40（细于板块格）。 */
    static final double COAST_DETAIL_SCALE = 22.0;
    private static final long SALT_COASTDET = 0x7E3B1D9A6C402F85L;

    /** 陆陆碰撞：山（4000 m @ 0.6°，宽 3°）。 */
    static final double COL_COL_PEAK = 4000.0 / METERS_PER_E;
    static final double COL_COL_PK_C = 0.6 * DEG_WU;
    static final double COL_COL_PK_W = 3.0 * DEG_WU;
    /** 陆陆碰撞：远高原（1500 m @ 4°，宽 6°）。 */
    static final double COL_COL_PLAT = 1500.0 / METERS_PER_E;
    static final double COL_COL_PL_C = 4.0 * DEG_WU;
    static final double COL_COL_PL_W = 6.0 * DEG_WU;
    /** 陆侧俯冲：山（3900 m @ 1.6°，宽 2.2°）。 */
    static final double SUB_CONT_PEAK = 3900.0 / METERS_PER_E;
    static final double SUB_CONT_C = 1.6 * DEG_WU;
    static final double SUB_CONT_W = 2.2 * DEG_WU;
    /**
     * 陆侧俯冲：<b>前弧凹陷</b>（−600 m @ 0，宽 0.6°）。
     *
     * <p>⚠ 2026-11-14 核对恢复：参考的 `own&&!oth` 案例是
     * {@code +3900·bump(d,1.6,2.2) − 600·bump(d,0,0.6)}——陆侧只有 600 m 的凹陷。
     * 我曾为了"两侧在边界处同值"把它并进共用海沟（−3600 m），
     * 结果大陆边缘被挖出比参考深 6 倍的槽（也是海岸黑边的一部分来源）。
     * 现在按<b>查询点壳值</b>在"深海沟 3600 / 陆侧凹陷 600"之间连续混合。</p>
     */
    static final double SUB_CONT_DIP = 600.0 / METERS_PER_E;
    static final double SUB_CONT_DIP_W = 0.6 * DEG_WU;
    /** 洋侧被俯冲：海沟（−3600 m @ 0，宽 1.1°）。 */
    static final double TRENCH_DEEP = 3600.0 / METERS_PER_E;
    static final double TRENCH_W = 1.1 * DEG_WU;
    /** 洋洋：岛弧（3700 m @ 1.5°，宽 1.0°）。 */
    static final double ARC_PEAK = 3700.0 / METERS_PER_E;
    static final double ARC_C = 1.5 * DEG_WU;
    static final double ARC_W = 1.0 * DEG_WU;
    /**
     * 洋洋俯冲：<b>海沟</b>（−3200 m @ 0，宽 0.5°）。
     *
     * <p>⚠ 2026-11-14 地质学修正：参考取 −1200 m，理由是"年轻洋内弧的海沟较浅"。
     * 但地质学上洋-洋俯冲（汤加、马里亚纳、日本海沟）是全球<b>最深</b>的海沟
     * （−8000~−11000 m），比周围深海平原低 <b>3000~5000 m</b>。
     * 实测我方"6~7 km"深度带占比 <b>0.0%</b>（地球 ~2%）⇒ 加深到 3200 m。</p>
     */
    static final double ARC_DIP = 3200.0 / METERS_PER_E;
    static final double ARC_DIP_W = 0.5 * DEG_WU;
    /**
     * <b>弧后盆地</b>（back-arc basin）：−1000 m @ 3.2°，宽 1.4°。
     *
     * <p>地质学实有（日本海、菲律宾海、斯科舍海）：弧后因俯冲板片后撤而拉张成盆，
     * 深度介于岛弧与深海平原之间。参考没有这一项。</p>
     */
    static final double BACKARC_DEPTH = 1000.0 / METERS_PER_E;
    static final double BACKARC_C = 3.2 * DEG_WU;
    static final double BACKARC_W = 1.4 * DEG_WU;
    /**
     * <b>火山</b>：弧轴上离散的锥体（+1500 m）。
     *
     * <p>参考只在 8% 的弧格记录"火山标记点"、不产生地形。地质学上火山弧是
     * 实打实的地形（富士山、圣海伦斯、喀拉喀托）⇒ 这里用高频掩码把弧脊切成
     * 一座座锥体：{@code mask = smoothstep(0.55, 0.80, fbm(26wu))}。</p>
     */
    static final double VOLC_PEAK = 1500.0 / METERS_PER_E;
    static final double VOLC_W = 0.55 * DEG_WU;
    static final double VOLC_MASK_SCALE = 26.0;
    private static final long SALT_VOLC = 0x2E9B4D17C6A385F0L;
    /** 离散（陆）：裂谷（−1000 m @ 0，宽 1.2°）与肩部（+450 m @ 1.8°，宽 1.0°）。 */
    static final double RIFT_VALLEY = 1000.0 / METERS_PER_E;
    static final double RIFT_VALLEY_W = 1.2 * DEG_WU;
    static final double RIFT_SHOULDER = 450.0 / METERS_PER_E;
    static final double RIFT_SH_C = 1.8 * DEG_WU;
    static final double RIFT_SH_W = 1.0 * DEG_WU;
    /**
     * 离散（洋）：<b>洋中脊</b>（+2400 m，翼宽 16°）。
     *
     * <p>⚠ 2026-11-14 地质学修正（两处）：
     * ① <b>翼宽</b> 参考 4.5°（≈500 km），但地球上洋脊两翼要延伸到 ~2000 km 才落到深海平原
     *    （洋壳年龄 &lt;60 Ma 的地段普遍高于 −4000 m）。实测我方洋深 2~3 km 带只占
     *    <b>2.8%</b>（地球 ~12%）——缺的正是<b>洋脊翼</b>，不是大陆隆起。改 16°（≈250wu≈1780 km）。
     * ② <b>脊顶高度不随扩张速率变</b>：脊顶深度由地幔潜能温度决定（全球普遍 −2500 m 上下），
     *    速率影响的是两翼坡度。参考把整个脊按 {@code sRate} 缩放 ⇒ 慢速边界脊高只有几百米。
     *    所以这一项<b>不乘 sRate</b>（裂谷/岛弧仍乘）。</p>
     */
    static final double RIDGE_SWELL = 2400.0 / METERS_PER_E;
    static final double RIDGE_SWELL_W = 11.0 * DEG_WU;
    /**
     * <b>轴部裂谷</b>（axial rift valley）：−1400 m @ 0，宽 0.55°（≈8.5wu ≈ 61 km）。
     *
     * <p>慢速扩张脊（大西洋中脊、西南印度洋脊）的标配形态：轴部一条 1~2 km 深、
     * 20~50 km 宽的裂谷，两侧各一道脊顶。参考只有一个光滑的宽 bump，没有这条裂谷
     * ——这是"洋中脊看着不像洋中脊"的直接原因。</p>
     *
     * <p>⚠ 宽度取 0.55°（不是地质学的 20~40 km = 3~6wu）：我们的格是 3.5wu，
     * 3~6wu 的谷只有 1~2 格宽，渲染/游玩都读不出来。0.55° ≈ 2.4 格是可分辨的下限。</p>
     */
    static final double RIFT_AXIAL = 1400.0 / METERS_PER_E;
    static final double RIFT_AXIAL_W = 0.55 * DEG_WU;
    /** 转换（±380 m @ 0，宽 1.0°）。 */
    static final double TRANSFORM_LIFT = 380.0 / METERS_PER_E;
    static final double TRANSFORM_W = 1.0 * DEG_WU;
    private static final long SALT_TRANS = 0x11C6B6L;

    // ============================================================ 板块内部细节

    /**
     * 细节纹理波长（wu）——参考 {@code fbm3(nS, p*16, 3)}（球面单位，约 0.4 圈/周期）。
     *
     * <p>幅度不是常量：参考 {@code (b>0 ? 160+260·mtn : 260)} 米，山处更大。</p>
     */
    static final double DETAIL_SCALE = 95.0;
    private static final long SALT_DETAIL = 0x7F4A7C159E3779B9L;
    private static final long SALT_MID = 0x1B8E27C4D5F60A31L;

    /**
     * 壳值 → c 的<b>分位锚点</b>：{@code v0<v1<v2<v3<v4} ↔ {@code c = 0, 0.30, 0.45, 0.52, 1}。
     *
     * <p>为什么不能只用一条 {@code smooth(-0.3,0.3,v−shift)}：
     * 那会把陆架带（c 0.45→0.52）压到壳值空间的 0.026 宽，实测只占洋面 2.6%
     * ⇒ 陆架窄成一条亮边、陆坡反而过宽。海陆的<b>面积</b>配比是地形观感的骨架，
     * 必须按分位给，不能按壳值差给。</p>
     *
     * <p>构造：四段 smoothstep 相加。每段在两端导数都为 0 ⇒ 段间接缝 C¹，
     * 不会出现"等壳值线折角"（铁律 ④）。</p>
     */
    /**
     * <b>冻结的锚点</b>（2026-11-14 离线标定一次，之后不再改）。
     *
     * <p>★ 为什么必须冻结：用户裁定"<b>要做无限生成</b>，地球是有限地形"。
     * 参考 planet.js 在球面上一次算完整图再做分位标定——那是<b>有限地图</b>的做法。
     * 无限世界没有"全图"，任何运行期分位/最大值都不可得，也不该得
     * （否则走到地图边缘时统计量会漂移 ⇒ 同一坐标在不同窗口出不同高度）。</p>
     *
     * <p>好在我们的地壳场是<b>统计平稳</b>的（同一套噪声 + 同一套核格点），
     * 所以"某个壳值以上占多少面积"是<b>生成函数的固有性质</b>，与窗口无关。
     * ⇒ 在 1000² 窗口上标一次，写成常量，运行期只做逐点局部映射。</p>
     *
     * <p>标定来源：SEED=9139912035078620160、radius=1750wu、步长 2 格的 250k 抽样；
     * 锚点 = 分位 0 / 0.566 / 0.601 / 0.661 / 0.999 处的壳值
     * （对应 深海/陆坡/陆架/陆地 的<b>面积</b>分界，按地球量级定）。</p>
     */
    static final double[] CRUST_ANCHOR_FIXED = {-2.153, -0.182, 0.073, 0.180, 0.975};

    /**
     * <b>标定后的壳值</b> c ∈ [0,1]。
     *
     * <p>⚠ 这一步不能省：无论陆/洋判据、内部分带、还是裂谷-洋脊混合，都必须用<b>同一个</b>
     * 标定后的 c，否则会出现"深处大陆内部却被判成 58% 洋壳"（这曾让洋中脊隆起
     * 盖满大陆 ⇒ 整块大陆变成平滑圆包）。</p>
     */
    static double crust01(long seed, double wx, double wz) {
        double v = SandContinent.crustAt(seed, wx, wz);
        double[] a = CRUST_ANCHOR_FIXED;
        return 0.30 * smoothstep(a[0], a[1], v)
                + 0.15 * smoothstep(a[1], a[2], v)
                + 0.07 * smoothstep(a[2], a[3], v)
                + 0.48 * smoothstep(a[3], a[4], v);
    }

    private SandTerrain() { }

    // ============================================================ 主入口

    /** 该点的地形高度（e 单位，0 = 海平面）。 */
    public static double height(long seed, double wx, double wz) {
        return partsOf(SandPlateField.sample(seed, wx, wz)).sum();
    }

    /** 由已采样的板块场计算高度（<b>不含</b>板块内部细节噪声）。 */
    public static double heightOf(SandPlateField.Sample s) {
        Parts p = partsOf(s);
        return p.sum() - p.detail();
    }

    /**
     * 高度分量（<b>供剖面/连续性探针逐项核对</b>）。
     *
     * <p>语义（与参考 planet.js 第 4~5 段一一对应）：</p>
     * <ul>
     *   <li>{@code base} —— 分段地壳映射（深海平原 / 陆坡 / 陆架 / 陆地）+ 内陆起伏</li>
     *   <li>{@code orogen} —— 构造响应的<b>正部</b>（山 / 高原 / 洋脊 / 岛弧）</li>
     *   <li>{@code trench} —— 构造响应的<b>负部</b>（海沟 / 裂谷 / 弧前凹）</li>
     *   <li>{@code detail} —— 细节纹理 + 近海细节</li>
     * </ul>
     *
     * <p>⚠ 2026-11-14 清理：旧版另有 {@code oceanDepth/arc/backArc/rift/ridgeAxial/transform}
     * 六个字段，它们自四案例 bump 改写后<b>恒为 0</b>，却让每个探针都打出整列 0。
     * 已删除——四案例结构本来就是把"弧/裂谷/洋脊"当成俯冲/离散两个案例的剖面形状。</p>
     */
    public record Parts(double base, double orogen, double trench, double detail, double islet) {
        public double sum() {
            return base + orogen + trench + detail + islet;
        }
    }

    /** 任意点的分量（含内部细节）。 */
    public static Parts parts(long seed, double wx, double wz) {
        SandPlateField.Sample s = SandPlateField.sample(seed, wx, wz);
        return partsOf(s);
    }

    /** 由已采样的板块场求分量（含内部细节）。 */
    static Parts partsOf(SandPlateField.Sample s) {
        double qx = s.warpedX(), qz = s.warpedZ();
        // ★ 2026-11-14 架构纠正：陆壳值来自<b>独立的大陆层</b>（{@link SandContinent}），
        //   <b>不是</b>由各板块的"陆/洋标志"加权平均。
        //   原因（参考 planet.js 第 2 段）：地壳场只由"大陆核"决定，与板块无关；
        //   把陆/洋做成"每板一个标志"再混合 ⇒ 大陆是<b>平顶板块 + 台阶状海岸</b>
        //   （用户指出"大陆板块有问题"）。参考的大陆是以核为中心的球冠：
        //   冠心厚、冠缘薄 ⇒ 大陆内部有厚度梯度、海岸自然收窄。
        //
        // ★ 2026-11-14 第二次纠正：必须用<b>标定+平滑后</b>的 c（{@link #crust01}），
        //   与参考的 {@code crust = smooth(-0.3,0.3,v-shift)} 同源。
        //   用未标定的原始值会让"大陆深处"的 c 只有 0.6 ⇒ 被判成部分洋壳。
        double c = crust01(s.seed(), qx, qz);
        Boundary bd = boundary(s, c);

        // ---- ① 地壳 → 基础高程 ----
        //  ★ 2026-11-14：深海那一端的"锚"不再是常数 DEEP_BASE，而是
        //    <b>洋壳年龄律</b>给出的当地深度（见 {@link #abyssByAge}）。
        //    c<0.30 的深海与 c 无关，深度只由"到洋中脊的距离"决定
        //    ⇒ 洋中脊的宽脊翼、随年龄加深的洋盆、平坦的老洋底<b>同一条机制</b>产出。
        double deepBase = abyssByAge(bd.ridgeDist())
                + DEEP_NOISE * fbm(qx / 700.0, qz / 700.0, SALT_MID, 3)
                + ABYSS_HILLS * fbm(qx / ABYSS_HILL_SCALE, qz / ABYSS_HILL_SCALE,
                SALT_ABYSS, 3);
        double base;
        if (c < C_DEEP) {
            base = deepBase;
        } else if (c < C_SLOPE) {
            // 大陆坡：smoothstep 而不是线性 ⇒ 与深海平原、陆架两个接缝都 C¹
            base = deepBase + (SLOPE_TOP - deepBase) * smoothstep(C_DEEP, C_SLOPE, c);
        } else if (c < C_SHELF) {
            base = SLOPE_TOP + (SHELF_TOP - SLOPE_TOP) * (c - C_SLOPE) / (C_SHELF - C_SLOPE);
        } else {
            base = SHELF_TOP + LAND_TOP * (c - C_SHELF) / (1.0 - C_SHELF);
        }
        // ---- ①b <b>主动陆缘：陆架被削掉</b>（2026-11-14 地质学补缺）----
        //   地质学：被动陆缘（大西洋型）有 50~300 km 宽陆架；
        //   <b>主动陆缘</b>（太平洋型，俯冲带）陆架几乎不存在——海岸直接临 3~5 km 深水，
        //   因为陆壳边缘被俯冲侵蚀、沉积物被海沟吞掉。
        //   此前每条海岸都套同一圈陆架 ⇒ 大陆看起来"圆胖、围一圈浅水环"。
        //   现在按"到会聚边界的距离"把陆架压成斜坡（连续：shelfW 在 c 方向是平滑鼓包）。
        double activeMargin = smoothstep(0.25, 0.85, bd.convMean())
                * Math.exp(-bd.dist() / MARGIN_SCALE);
        if (activeMargin > 1e-4) {
            double shelfW = smoothstep(C_SLOPE, C_SLOPE + 0.05, c)
                    * (1.0 - smoothstep(C_SHELF, C_SHELF + 0.08, c));
            base -= ACTIVE_SHELF_DROP * activeMargin * shelfW;
        }
        // ---- ② 内陆起伏（参考 cfg.interior <b>三档</b>）----
        //      style 0: e = 40 + (e−40)·0.55 − 120·land·smooth(0.10,0.50, fbm(p*2.2,3))
        //      style 1: e += 1150·land·smooth(0.22,0.42, fbm(p*2.4,4))
        //               e −=  300·land·smooth(0.25,0.50, −fbm(p*2.8,3))
        //      style 2: e += 900·land·ridged(p*3.2, 4)
        double landGate = smoothstep(0.50, 0.65, c);
        if (landGate > 1e-4) {
            if (INTERIOR_STYLE == 0) {
                // 平缓：把陆地压向 +40 m 一层，再叠一层宽缓洼地
                double hg0 = fbm(qx / INTERIOR_SCALE, qz / INTERIOR_SCALE, SALT_INTERIOR, 3);
                base = SHELF_TOP + (base - SHELF_TOP) * 0.55
                        - 120.0 / METERS_PER_E * landGate * smoothstep(0.10, 0.50, hg0);
            } else if (INTERIOR_STYLE == 2) {
                // 崎岖：脊状噪声直接给陆地加山脊（参考 wavelength ≈ p*3.2，很宽）
                base += 900.0 / METERS_PER_E * landGate
                        * ridged(qx / (INTERIOR_SCALE * 2.0), qz / (INTERIOR_SCALE * 2.0),
                        SALT_INTERIOR, 4);
            } else {
                double hg = fbm(qx / INTERIOR_SCALE, qz / INTERIOR_SCALE, SALT_INTERIOR, 4);
                double bg = fbm(qx / (INTERIOR_SCALE * 0.86), qz / (INTERIOR_SCALE * 0.86),
                        SALT_INTERIOR2, 3);
                base += INTERIOR_HIGH * landGate * smoothstep(0.22, 0.42, hg);
                // ⚠ 盆地只能挖掉陆地高程的一部分，不能把低地挖到海平面以下：
                //   参考的 −300 m 是<b>绝对</b>幅度，在近岸低地（+40~100 m）上会把地面按进海里
                //   ⇒ 大陆内部长出一片片"噪点湖"。取 min(basin, 0.8·base) ⇒ 永不沉没。
                double basin = INTERIOR_BASIN * landGate * smoothstep(0.25, 0.50, -bg);
                base -= Math.min(basin, Math.max(0.0, base) * 0.8);
            }
        }

        // ---- ③ 构造抬升（四案例 bump，按候选边界对加权混合）----
        //   ★ 2026-11-14 架构补齐（参考 planet.js 第 5 段"全分辨率合成"）：
        //     抬升乘以 <b>0.45 + 1.05·rug</b>，rug 是由<b>脊状噪声</b>驱动的纹理，
        //     且只在山脉处打开（mtn = |u|/1800 平滑门）。
        //     这一步是"山"与"缓坡土包"的分水岭：抬升本身只给包络，
        //     脊谷纹理来自 rug ⇒ 山脊成线、山谷成槽。
        double uRaw = bd.uplift() * MSCALE;
        double mtnMask = Math.min(1.0, Math.abs(uRaw) / MTN_REF);
        double rug = 0.5 + (ridged(qx / MTN_RUG_SCALE, qz / MTN_RUG_SCALE, SALT_RUG, MTN_RUG_OCT) - 0.5)
                * smoothstep(0.0, 0.08, mtnMask);
        double uMod = uRaw * (0.45 + 1.05 * rug);
        double orogen = Math.max(0.0, uMod);
        double trench = Math.min(0.0, uMod);

        // ---- ④ 细节：陆地纹理 + <b>近海细节放大</b>（参考同段，逐行对齐）----
        //   det = fbm3(nS, p*16, 3)          ; 幅度 (b>0 ? 160+260·mtn : 260) 米
        //   near = exp(−|e|/500m)            ; e += near·coastDetail·fbm(2 倍频)
        //   参考原文注释：海陆零线落在两格之间，不贴着陆地格边走出台阶。
        double ePre = base + uMod;
        double near = Math.exp(-Math.abs(ePre) / COAST_NEAR_E);
        // ⚠ 参考原文是三元 {@code b>0 ? 160+260·mtn : 260}——那是<b>硬切换</b>。
//   但这里连"用 smoothstep 把海陆接起来"都不行：海岸处 base 的梯度约 0.04e/格，
//   任何对 base 的幅度调制都会在 1 格里把幅度摆完 ⇒ 仍是一道阶跃
//   （实测 only-detail 从 2.5 格降到 2.0 格就下不去了）。
//   ⇒ 干脆去掉"海陆不同幅度"，只保留与 {@code mtnMask}（连续）相关的部分。
double ampM = 260.0 + 260.0 * mtnMask;
        double detail = ampM / METERS_PER_E
                * fbm(qx / DETAIL_SCALE, qz / DETAIL_SCALE, SALT_DETAIL, 3);
        detail += near * COAST_DETAIL * fbm(qx / COAST_DETAIL_SCALE, qz / COAST_DETAIL_SCALE,
                SALT_COASTDET, 2);

        // ---- ⑤ 岛链 / 群岛（参考第 5 段；{@link SandIslet} 里有与参考的两处有意偏离）----
        //   加性项、只加不减 ⇒ 从当地地表平滑升起，不产生参考 max() 的圆柱壁。
        double islet = SandIslet.add(s.seed(), qx, qz, ePre);
        return new Parts(base, orogen, trench, detail, islet);
    }

    /**
     * <b>脊状噪声</b>（ridged）：{@code 1 − √(n² + r²) + r}，{@code n} 为双极性噪声。
     *
     * <p>输出约 [0,1]，在 {@code n≈0} 处取峰 ⇒ 等值线成<b>脊线</b>（山脉的脊谷纹理来源）。</p>
     *
     * <p>⚠ 不能直接用 {@code 1−|n|}（本项目铁律 6 禁止裸绝对值）：那在 {@code n=0} 处是
     * V 型折痕（梯度突变）。这里用<b>圆化绝对值</b> {@code √(n²+r²)−r}，r=0.06 ⇒
     * 脊顶平滑、无折痕。参考 planet.js 用的是裸 ridged3（它的网格粗、后续侵蚀会抹平）。</p>
     */
    static double ridged(double x, double z, long salt, int octaves) {
        double v = 0.0, amp = 1.0, norm = 0.0, f = 1.0;
        for (int i = 0; i < octaves; i++) {
            double n = 2.0 * SandPlateField.valueNoise(x * f, z * f, salt + i * 7919L);
            double r = 0.06;
            v += amp * (1.0 - Math.sqrt(n * n + r * r) + r);
            norm += amp;
            amp *= 0.5;
            f *= 2.0;
        }
        return v / Math.max(1e-9, norm);
    }

    /** 多倍频值噪声（σ≈±1，倍增 0.5）。 */
    static double fbm(double x, double z, long salt, int octaves) {
        return fbm(x, z, salt, octaves, 0.5);
    }

    /**
     * 多倍频值噪声，<b>可控倍频增益</b>。
     *
     * <p>为什么需要 gain：海岸噪声若用 6 倍频、增益 0.5，最细倍频（13wu）
     * 的梯度会盖过冠缘的径向梯度 ⇒ 大陆坡被压成"一刀切的悬崖"，
     * 渲染出来是环绕大陆的一圈黑边。取 4 倍频 + 增益 0.38 后，
     * 最细倍频 52wu / 幅度 0.035 ⇒ 海岸仍是分形的，但大陆坡与陆架有 15~20 格宽度。</p>
     */
    static double fbm(double x, double z, long salt, int octaves, double gain) {
        return fbm(x, z, salt, octaves, gain, 2.0);
    }

    /**
     * 多倍频值噪声，增益与<b>层间比</b>都可控。
     *
     * <p>参考的 {@code fbm3(n, x, octaves, 2.1, 0.55)} = 层间比 2.1、增益 0.55。
     * 我方默认层间比 2.0；海岸噪声按参考取 2.1。</p>
     */
    static double fbm(double x, double z, long salt, int octaves, double gain, double lacunarity) {
        double sum = 0.0, amp = 1.0, norm = 0.0, f = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * SandPlateField.valueNoise(x * f, z * f, salt + i * 7919L);
            norm += amp;
            amp *= gain;
            f *= lacunarity;
        }
        return sum / Math.max(1e-9, norm);
    }

    // ============================================================ 边界层（应变 + 四案例构造响应）

    /**
     * 边界层的连续量。
     *
     * @param div    速度场<b>散度</b>（归一化）：正 = 拉张、负 = 挤压（诊断用）
     * @param shear  速度场<b>剪应变率</b>（归一化，诊断用）
     * @param dist   到最近 Voronoi 边的距离（wu）——min 连续
     * @param uplift 构造响应抬升（e，<b>带符号</b>）：四案例 bump 按<b>候选边界对加权混合</b>
     * @param ridgeDist 到最近<b>离散边界</b>的加权距离（wu）——洋壳年龄代理
     * @param convMean  候选对的加权平均法向相对速度（正=汇聚）
     */
    record Boundary(double div, double shear, double dist, double uplift,
                    double ridgeDist, double convMean) { }

    /**
     * 边界层：速度场应变（诊断）+ <b>四案例构造响应</b>（地质）。
     *
     * <p>★ 案例由<b>边界两侧壳类型</b>选（每对恒定），响应是距离的 bump 剖面；
     * 多个候选对按 {@code exp(−d²/2σ²)} 加权平均 ⇒ 权重连续 ⇒ 总量连续。
     * 这替代了旧的 convergent/divergent 布尔分支（在边界上翻转 ⇒ 接缝）。</p>
     *
     * <p>⚠ 两次失败的替代方案（勿重走）：
     * ① 带符号距离（正=上覆板侧）——侧符号在中轴换人 ⇒ u 折叠 ⇒ 爪形薄脊（20.5 格）；
     * ② 把平分线截断成"线段"取点到线段距离——真实 Voronoi 边可能更长 ⇒ 留洞 ⇒ 88 格阶跃。</p>
     */
    static Boundary boundary(SandPlateField.Sample s) {
        return boundary(s, crust01(s.seed(), s.warpedX(), s.warpedZ()));
    }

    /**
     * @param cq 查询点的标定壳值——调用方通常已经算过，传进来省一次 crustAt
     */
    static Boundary boundary(SandPlateField.Sample s, double cq) {
        double qx = s.warpedX(), qz = s.warpedZ();
        double inv2v2 = 1.0 / (2.0 * VELOCITY_SIGMA * VELOCITY_SIGMA);
        double inv2b2 = 1.0 / (2.0 * BLEND_SIGMA * BLEND_SIGMA);
        List<SandPlateField.Plate> near =
                SandPlateField.platesNear(s.seed(), qx, qz, PAIR_RADIUS);

        // ★ 2026-11-14 核对：<b>我方板块必须是"到种子位置欧氏最近"的板块</b>。
        //   {@code s.plate()} 走的是 siteCell()——按<b>格</b>距离找最近种子格；
        //   种子抖动 ±0.475 格，理论上相邻格的种子可能更近，此时对真正最近的板块
        //   di < d1 ⇒ {@code dEff = max(0, ½(di−d1))} 会被截成 0，
        //   它就被当成"正好落在边界上"给满强度（凭空多一条山带）。
        //   实测当前 seed 下 s.plate() 与欧氏最近一致（改前后产出逐项相同），
        //   但这里显式保证该不变量——不然换个 seed 就可能踩到。
        SandPlateField.Plate me = s.plate();
        double d1 = Math.hypot(qx - me.sx(), qz - me.sz());
        for (SandPlateField.Plate p : near) {
            double d = Math.hypot(qx - p.sx(), qz - p.sz());
            if (d < d1) {
                d1 = d;
                me = p;
            }
        }

        // ---- 应变场（诊断用）：加权平均速度 + 解析梯度 ----
        double wSum = 0.0, vxSum = 0.0, vzSum = 0.0;
        for (SandPlateField.Plate p : near) {
            double dx = qx - p.sx(), dz = qz - p.sz();
            double w = Math.exp(-(dx * dx + dz * dz) * inv2v2);
            wSum += w;
            vxSum += w * p.vxAt(qx, qz);
            vzSum += w * p.vzAt(qx, qz);
        }
        if (wSum <= 1e-300) {
            return new Boundary(0.0, 0.0, s.dist(), 0.0, RIDGE_FAR, 0.0);
        }
        double vx = vxSum / wSum, vz = vzSum / wSum;
        double divSum = 0.0, shrSum = 0.0;
        for (SandPlateField.Plate p : near) {
            double dx = qx - p.sx(), dz = qz - p.sz();
            double w = Math.exp(-(dx * dx + dz * dz) * inv2v2);
            double ex = p.vxAt(qx, qz) - vx, ez = p.vzAt(qx, qz) - vz;
            divSum += w * (dx * ex + dz * ez);
            shrSum += w * (dz * ex + dx * ez);
        }
        double norm = -1.0 / (VELOCITY_SIGMA * VELOCITY_SIGMA) / wSum;

        // ---- 四案例构造响应：对每个候选边界对，按其壳类型与相对速度取案例 ----
        double wSum2 = 0.0, upSum = 0.0;
        // 洋壳"年龄"代理：到最近<b>离散边界</b>（洋中脊）的加权距离
        double rw = 0.0, rd = 0.0, convW = 0.0;
        long[] seen = new long[64];
        int nSeen = 0;
        for (SandPlateField.Plate other : near) {
            if (other.cx() == me.cx() && other.cz() == me.cz()) {
                continue;
            }
            long key = (long) other.cx() * 1000003L + other.cz();
            boolean dup = false;
            for (int t = 0; t < nSeen; t++) {
                if (seen[t] == key) {
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
            double sx0 = other.sx() - me.sx(), sz0 = other.sz() - me.sz();
            double len = Math.sqrt(sx0 * sx0 + sz0 * sz0);
            if (len < 1e-9) {
                continue;
            }
            double nx = sx0 / len, nz = sz0 / len;
            double dxo = qx - other.sx(), dzo = qz - other.sz();
            double di = Math.sqrt(dxo * dxo + dzo * dzo);
            double dEff = Math.max(0.0, 0.5 * (di - d1));
            if (dEff > RESPONSE_CUT) {
                continue;
            }
            // 相对速度（欧拉旋转在查询点取值）的法向/剪切分量
            double vax = me.vxAt(qx, qz), vaz = me.vzAt(qx, qz);
            double vbx = other.vxAt(qx, qz), vbz = other.vzAt(qx, qz);
            double rx = vax - vbx, rz = vaz - vbz;
            double conv = rx * nx + rz * nz;
            double shear = Math.abs(rx * nz - rz * nx);
            // ★ 归一化：conv 是<b>速度差</b>（无量纲 ~0.3-0.7，相干流场使相邻板块速度相近），
            //   不是应变率（~1e-3，那是 div 的量纲）——两者量纲不同，不能用同一个尺度。
            //   参考的 S = min(1.6, conv/0.9)：0.9 是"典型板块速度差"的标定值。
            //   我方相干流场的典型 |conv| ≈ 0.45 ⇒ 取 CONV_SCALE = 0.45，
            //   使边界带 convN ≈ 1（与地球板块速度差的量级一致）。
            conv /= CONV_SCALE;
            shear /= CONV_SCALE;

            double w = Math.exp(-dEff * dEff * inv2b2);
            // 洋洋俯冲的极性：按板块格坐标决定（确定性，且每对恒定）
            boolean meSubducts = ((long) me.cx() * 1000003L + me.cz())
                    > ((long) other.cx() * 1000003L + other.cz());
            // ★ 2026-11-14 纠正（参考 planet.js 第 3 段的取点）：
            //   参考是 {@code own = crust[b] > 0.5, oth = crust[o] > 0.5}，
            //   其中 b = 该格的<b>最近边界格</b>、o = b 的<b>跨边界邻格</b>。
            //   即：判据取在<b>边界上</b>，不是"查询点"和"对方板块的种子"。
            //   原先取对方种子会跑出几百 wu（种子可能落在洋里）⇒ 大陆内部的碰撞
            //   被误判成俯冲，山带变窄甚至消失。
            //   连续场等价做法：沿法向从查询点走到平分线的垂足，再在两侧各取 ε。
            double bx = qx + nx * dEff, bz = qz + nz * dEff;
            double ownShell = crust01(s.seed(), bx - nx * EDGE_EPS, bz - nz * EDGE_EPS);
            double othShell = crust01(s.seed(), bx + nx * EDGE_EPS, bz + nz * EDGE_EPS);
            double u = caseUplift(dEff, conv, shear, ownShell, othShell, meSubducts, s, cq);
            wSum2 += w;
            upSum += w * u;
            // 到"离散边界"的加权距离（洋壳年龄代理）：只统计 conv<0 的对
            double dw = smoothstep(0.02, 0.20, -conv);
            rw += w * dw;
            rd += w * dw * dEff;
            convW += w * conv;
        }
        double uplift = wSum2 > 1e-12 ? upSum / wSum2 : 0.0;
        // 平滑融合：rw→0（附近没有离散边界）时趋近 RIDGE_FAR，而不是硬切换
        double ridgeDist = (rd + 0.02 * RIDGE_FAR) / (rw + 0.02);
        double convMean = wSum2 > 1e-12 ? convW / wSum2 : 0.0;
        return new Boundary(divSum * norm, Math.abs(shrSum * norm), s.dist(), uplift,
                ridgeDist, convMean);
    }

    /** 判壳类型的取点偏移（wu）：从平分线垂足向两侧各取 ε。 */
    static final double EDGE_EPS = 12.0;

    /**
     * 四案例构造响应（e 单位，<b>带符号</b>）——剖面形状照参考 planet.js，
     * 数值换算 /11000（米→e），距离/宽度按 1° ≈ {@link #DEG_WU} wu 换算。
     *
     * <p>★ 2026-11-14 连续性重写：参考用<b>离散</b> {@code own/oth} 分支（在粗网格上做，
     * 靠双线性+域扭曲抹平）。我方是全分辨率连续采样，离散分支会沿"壳值 = 0.5"的等值线
     * 整体跳变——实测相邻 3.9wu 跳 118 格（p99.9 = 0.11e）。
     * 改为<b>四案例连续加权投票</b>：案例权重由两侧壳值的 smoothstep 给出，
     * 权重和为 1 ⇒ 只要各案例剖面连续，u 就连续（与"吃水线"式硬切换同理，见铁律 ④）。</p>
     *
     * @param d    到该边界（垂直平分线）的距离（wu，≥0）
     * @param conv 该边界的法向相对速度（正=汇聚）
     * @param own  查询侧<b>边界处</b>的壳值（0..1，连续）
     * @param oth  对面侧<b>边界处</b>的壳值（0..1，连续）
     * @param cq   <b>查询点</b>的壳值（0..1）——只用于把海沟深度在"深海沟/陆侧凹陷"间连续插值
     */
    static double caseUplift(double d, double conv, double shear, double own, double oth,
                             boolean meSubducts, SandPlateField.Sample s, double cq) {
        double u;
        // ---- 三类边界类型也<b>连续加权</b>（conv 正/负的硬分支同样会跳）----
        //   wC = 明确汇聚、wD = 明确离散、wT = 过零点附近的转换带（权重和 = 1）。
        double wC = smoothstep(0.06, 0.18, conv);
        double wD = smoothstep(0.06, 0.18, -conv);
        double wT = Math.max(0.0, 1.0 - wC - wD);

        // ---- 汇聚：S = min(1.6, conv/0.9) 的连续版 ----
        double sRateC = smoothRate(Math.max(0.0, conv), 1.6, 0.05);
        // 海沟幅度按<b>查询点壳值</b>连续混合：深海一侧 3600 m，陆侧只有 600 m 的前弧凹陷。
        // （参考对两个案例分别用 3600 / 600；我方必须连续 ⇒ 用查询点位置做插值变量）
        double wLandQ = smoothstep(0.30, 0.62, cq);
        double trenchAmp = TRENCH_DEEP * (1.0 - wLandQ) + SUB_CONT_DIP * wLandQ;
        // 海沟宽度也一起插值：陆侧的凹陷比深海沟窄（参考 0.6° vs 1.1°）
        double trenchW = TRENCH_W * (1.0 - wLandQ) + SUB_CONT_DIP_W * wLandQ;
        double trench = trenchAmp * bump(d, 0, trenchW);
        // 弧上<b>离散火山</b>（掩码把弧脊切成一座座锥体）+ 弧后盆地（两处火山共用同一掩码）
        double volcMask = smoothstep(0.55, 0.80, fbm(s.queryX() / VOLC_MASK_SCALE,
                s.queryZ() / VOLC_MASK_SCALE, SALT_VOLC, 2));
        double volcano = VOLC_PEAK * volcMask * bump(d, ARC_C, VOLC_W);
        double backArc = -BACKARC_DEPTH * bump(d, BACKARC_C, BACKARC_W);
        // 陆陆碰撞：对称山 + 远高原（|d| 偶函数 ⇒ 天然连续）
        double uColl = sRateC * (COL_COL_PEAK * bump(d, COL_COL_PK_C, COL_COL_PK_W)
                + COL_COL_PLAT * bump(d, COL_COL_PL_C, COL_COL_PL_W));
        // 陆(查询侧)-洋：接口=海沟底，山向陆内升起（从 0 起坡）；弧上叠离散火山
        double uSubd = sRateC * (-trench
                + SUB_CONT_PEAK * smoothstep(0.0, SUB_CONT_C * 0.8, d)
                * bump(d, SUB_CONT_C, SUB_CONT_W)
                + VOLC_PEAK * 0.8 * volcMask * bump(d, SUB_CONT_C, VOLC_W));
        // 洋(查询侧)-陆：查询侧只有海沟（向海回到深海平原）
        double uOver = sRateC * (-trench);
        // 洋洋：接口=海沟底，岛弧在非俯冲侧升起；再叠弧后盆地 + 弧上离散火山
        double arc = meSubducts ? 0.0
                : ARC_PEAK * smoothstep(0.0, ARC_C * 0.8, d) * bump(d, ARC_C, ARC_W) + volcano;
        double uOcOc = sRateC * (-ARC_DIP * bump(d, 0, ARC_DIP_W) + arc + backArc);
        // 案例权重由两侧壳值给出（连续，和为 1）
        double wc = smoothstep(0.40, 0.64, own), wo = smoothstep(0.40, 0.64, oth);
        double uConv = wc * wo * uColl + wc * (1.0 - wo) * uSubd
                + (1.0 - wc) * wo * uOver + (1.0 - wc) * (1.0 - wo) * uOcOc;

        // ---- 离散：裂谷/洋脊按<b>标定后的连续壳值</b>混合 ----
        //   ⚠ 2026-11-14 修正：此处曾用未标定的 crustAt(≈0.6) 与 0.5 比较，
        //   于是大陆深处被判成"58% 洋壳" ⇒ 洋中脊隆起（1900 m、宽 4.5°）盖满大陆，
        //   整个大陆变成<b>平滑圆包</b>（用户看到的"平顶板块"）。
        double sRateD = smoothRate(Math.max(0.0, -conv), 1.6, 0.05);
        double cL = smoothstep(0.46, 0.60, own);
        double riftValley = -RIFT_VALLEY * bump(d, 0, RIFT_VALLEY_W)
                + RIFT_SHOULDER * bump(d, RIFT_SH_C, RIFT_SH_W);
        // 洋中脊：<b>脊翼已由年龄律给出</b>（{@link #abyssByAge}，不在这里重复叠高），
        // 这里只补轴部裂谷——"两道脊顶夹一条槽"的形态。
        double ridge = -RIFT_AXIAL * bump(d, 0, RIFT_AXIAL_W);
        double uDiv = sRateD * (cL * riftValley) + (1.0 - cL) * ridge;

        // ---- 转换：小幅、噪声定符号（参考 planet.js 转换案例）----
        double sn = fbm(s.queryX() / 260.0, s.queryZ() / 260.0, SALT_TRANS, 2);
        double sign = sn > 0 ? 1.0 : -0.6;
        double uTrans = sign * shear * TRANSFORM_LIFT * bump(d, 0, TRANSFORM_W);

        u = wC * uConv + wD * uDiv + wT * uTrans;
        return u;
    }

    /** 高斯型 bump：{@code exp(−((x−center)/width)²)}（参考 bump(x, center, width) 的形状）。 */
    static double bump(double x, double center, double width) {
        double t = (x - center) / Math.max(1e-9, width);
        return Math.exp(-t * t);
    }

    /**
     * 软饱和速率：参考 {@code S = min(1.6, |conv|/0.9)} 的 C^∞ 版。
     * {@code floor + (max−floor)·v/((max−floor)+v)}——从 floor 起、渐近 max，
     * 不产生 {@code min} 硬钳位的 C⁰ 折点（折点沿边界带一整条 ⇒ 渲染成尖线）。
     */
    static double smoothRate(double v, double max, double floor) {
        double x = Math.max(0.0, v);
        return floor + (max - floor) * x / ((max - floor) + x);
    }

    /** Hermite smoothstep（两端一阶导为 0）。 */
    static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }
}