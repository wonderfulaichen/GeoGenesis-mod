package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱：<b>大陆地壳层</b>——与板块<b>完全独立</b>的一层。
 *
 * <h2>为什么必须独立（2026-11-14 架构纠正）</h2>
 * <p>参考 {@code site/core/planet.js} 第 2 段「大陆地壳」的做法：
 * 地壳场<b>只由"大陆核"决定</b>，{@code plate[i]} 根本不参与；板块只负责边界与构造抬升。
 * 而此前的沙箱把"陆/洋"做成<b>每个板块一个标志</b>再按距离混合 ⇒ 大陆是
 * <b>平顶板块 + 台阶状海岸</b>（用户指出"大陆板块有问题"）。</p>
 *
 * <h2>参考的构造（逐条对应）</h2>
 * <ol>
 *   <li><b>大陆核</b>：K 个核，彼此有最小间距（{@code 13+6/√K} 度），聚在一个中心附近。</li>
 *   <li><b>面积份额</b>：每个核 {@code share = 0.4 + rnd*1.2}；球冠半径由份额反推
 *       {@code r = acos(1 − 2a)}（a = 面积占比）⇒ 大陆大小由份额决定。</li>
 *   <li><b>地壳值</b>：{@code s = (r − 到核的距离) / r}——冠心 = 1、冠缘 = 0，
 *       取所有核的 <b>max</b> ⇒ 多个冠重叠成有机形状。</li>
 *   <li><b>海岸噪声</b>：{@code v = best + coastAmp·fbm(6 倍频)}，coastAmp = 0.3~0.42
 *       ⇒ 海岸线分形破碎、冠缘不再是一个圆。</li>
 *   <li><b>大陆组的海峡</b>：两组影响接近处压低（{@code chanDepth·(1−smooth(...))}）。
 *       ⚠ 我方用"核出现概率由低频场控制"近似——稀疏区无核 ⇒ 自然留出大洋与海峡。</li>
 *   <li><b>微大陆</b>：参考另有 1~8 个 share 0.08~0.2 的小核；我方用半径下限覆盖。</li>
 * </ol>
 *
 * <h2>无限域做法（与参考的"全局核列表"不同）</h2>
 * <p>参考在球面上一次性放出全部核（K 很小）。沙箱要支持无限世界，改成
 * <b>核格点</b>：间距 {@link #CORE_SPACING}，每格按低频场概率决定是否放核，
 * 核位置在格内抖动、半径由哈希给出。于是任意坐标都能 O(1) 求出地壳值。</p>
 *
 * <p>⚠ 许可证：planet-simulation <b>无许可证</b>（保留所有权利）⇒ 只借鉴结构思想，
 * 本实现为独立编写。</p>
 */
final class SandContinent {

    /**
     * 大陆核的格点间距（wu）。
     *
     * <p>参考的核间距约 15° ≈ 232wu（1° ≈ 15.5wu）。取 3 倍左右 ⇒
     * 一个窗口（3500wu）内出现 2~4 个核、互相重叠成大块大陆。</p>
     */
    static final double CORE_SPACING = 1400.0;
    /** 核半径范围（wu）——对应参考的 share 0.4~1.6（面积 ∝ r²）。 */
    static final double CORE_R_MIN = 420.0;
    static final double CORE_R_MAX = 1250.0;
    /**
     * 椭圆的<b>拉长比</b>范围（面积守恒：{@code ax=r·st, az=r/st}）。
     *
     * <p>地球的大陆没有一个是圆饼：南美/非洲/印度都是拉长的，且方向各异。
     * 纯圆核的 max 并集只能得到"圆饼串"，这是"不像大陆"的主要几何原因之一。</p>
     */
    static final double CORE_STRETCH_MIN = 1.0;
    static final double CORE_STRETCH_MAX = 1.8;
    /**
     * <b>半轴下限</b>（wu）。
     *
     * <p>⚠ 为什么必须有它：地壳值沿半径的梯度 ≈ {@code 1/半轴}。半轴越小，海岸越陡——
     * 实测半轴 150wu 时，从陆架（−160 m）掉到深海（−4500 m）只用了 <b>25wu = 7 格</b>
     * ⇒ 相邻格跳 <b>68 格</b>，游戏里是一道垂直墙。
     * 半轴 ≥ 500wu 后，陆架带（c 0.45→0.52）至少占 60wu ≈ 17 格，陆坡 ≈ 21 格。</p>
     */
    static final double CORE_MIN_SEMI = 500.0;
    /** 核在格内的抖动比例（0.5 = 可越过格界，打掉格点骨架）。 */
    static final double CORE_JITTER = 0.48;
    /**
     * 核"出现概率"的低频场尺度（wu）与门限。
     *
     * <p>参考把全部核聚在一个随机中心附近（{@code spread} 度）形成"大陆组"。
     * 无限域里用低频场近似：场高的区域核密集 ⇒ 大陆成组；场低处无核 ⇒ 大洋/海峡。</p>
     */
    static final double OCC_SCALE = 9000.0;
    static final double OCC_THRESHOLD = 0.10;
    /** 海岸噪声幅度（相对冠半径，参考 coastAmp = 0.3~0.42）与波长。 */
    static final double COAST_AMP = 0.36;
    static final double COAST_NOISE_SCALE = 520.0;

    /**
     * <b>中频海岸能量</b>：100~500 wu（70~360 km）尺度的半岛与海湾。
     *
     * <p>★ 2026-11-14 地质学补缺（用户："大陆还是偏圆一点"）：
     * 实测海岸线分形维数 <b>D=1.000</b>（地球 1.20~1.35，圆=1.00）——
     * 也就是"在 1~2 格尺度上没有细节"，海岸在多边形上就是一条光滑曲线。</p>
     *
     * <p>根因是<b>噪声谱</b>：原 6 倍频从 520wu 起，2.1 的层间比 ⇒ 第 4~6 档
     * 的相对幅度只有 0.17 / 0.09 / 0.05，且波长 60wu 以下才有中尺度——
     * 但每档的绝对幅度太小，被冠缘的径向梯度压住。
     * 地球海岸线之所以"弯曲"，主要是<b>几十到几百公里</b>尺度的
     * 半岛/海湾/三角洲，而不是 1 km 的碎花。</p>
     *
     * <p>做法：<b>单独叠一层</b> 100~500wu 的中频噪声（3 倍频、增益 0.55），
     * 幅度是 {@link #COAST_AMP} 的 {@link #COAST_MID_FRAC}。它与主海岸噪声同源
     * 但不受倍频衰减影响 ⇒ 中尺度能真正显形。</p>
     */
    static final double COAST_MID_FRAC = 0.55;
    static final double COAST_MID_SCALE = 160.0;
    /**
     * <b>细尺度海岸能量</b>：8~30 wu（6~21 km）的海蚀崖/小海湾/礁岬。
     *
     * <p>这是把海岸线分形维数从 1.00 提到 1.2 的那一档——它让海陆零线在
     * <b>单格尺度</b>抖动。</p>
     *
     * <p>⚠ 幅度不能太大：0.85 时实测海岸外围出现一圈"噪点毛边"
     * （几百个 1~2 格的碎点），那不是地质学上的海岸。定稿 0.55，
     * 且只在冠缘附近生效（用 {@link #FINE_GATE} 门控，
     * 免得大陆内部也被磨成毛边）。</p>
     */
    static final double COAST_FINE_FRAC = 0.55;
    static final double COAST_FINE_SCALE = 9.0;
    /**
     * 细尺度的生效门：<b>只在海岸窄带</b>（{@code best ∈ [−0.06, +0.10]}）为 1。
     *
     * <p>⚠ 门控必须窄。实测：区间取 −0.05~0.45（覆盖整个陆坡+陆架）时，
     * 海岸外几格到几十格都长出一圈"碎点毛边"——因为 9wu 波长的噪声在
     * 整个陆架带都被放大，把平滑的陆架面打成噪点。
     * 真实的海岸粗糙度只存在于海陆零线附近。</p>
     */
    static final double FINE_GATE_LO = -0.06;
    static final double FINE_GATE_HI = 0.10;
    private static final long SALT_COASTMID = 0x3A7C1D95E6B24803L;

    // ============================================================ 多层域扭曲（借用生产链"多层噪声"的思路）

    /**
     * <b>海岸形状 = 多层域扭曲</b>（2026-11-14，用户建议："大陆的生成参考一下
     * 我们之前的多层噪声，毕竟我们之前的大陆噪声就没椭圆的问题"）。
     *
     * <h3>为什么"叠加幅度"会失败、"位移"不会</h3>
     * <p>此前我给壳值<b>叠加幅度</b>（{@code best + amp·noise}）来让海岸弯曲，
     * 踩了三个坑：① 幅度会被冠缘的径向梯度压住（噪声梯度只有径向的一半）；
     * ② 幅度足够大时又把<b>陆架带</b>打成碎块（实测陆架 1122 个分量）；
     * ③ 内陆/深洋长出噪点（要再打门控）。</p>
     *
     * <p><b>位移式扭曲</b>不改变场的值——只是<b>挪动采样点</b>：
     * 椭圆的等值线在所有尺度上都变弯了，但场的梯度结构原封不动 ⇒
     * 陆架宽度、陆坡宽度全部保持，海岸却自然弯曲。
     * 这正是生产链老 {@code ContinentField} 多层噪声"没有椭圆问题"的原因。</p>
     *
     * <h3>四层扭曲的标定</h3>
     * <table border="1">
     *   <caption>每层：波长（wu/km）、位移幅度（wu/km）、对应的地质学实体</caption>
     *   <tr><th>层</th><th>波长</th><th>位移</th><th>对应实体</th></tr>
     *   <tr><td>L</td><td>700wu / 5000 km</td><td>180wu / 1300 km</td><td>大陆的位置/朝向漂移</td></tr>
     *   <tr><td>M</td><td>240wu / 1700 km</td><td>70wu / 500 km</td><td>大半岛、大海湾</td></tr>
     *   <tr><td>S</td><td>80wu / 570 km</td><td>22wu / 160 km</td><td>中型半岛/岬角</td></tr>
     *   <tr><td>F</td><td>24wu / 170 km</td><td>7wu / 50 km</td><td>小海湾、礁岬</td></tr>
     * </table>
     * <p>每层的"位移/波长"比 ≈ 0.26~0.29 ⇒ 各尺度<b>自相似</b>（分形的来源）。</p>
     */
    static final double WARP_L_SCALE = 700.0, WARP_L_AMP = 180.0;
    static final double WARP_M_SCALE = 240.0, WARP_M_AMP = 70.0;
    static final double WARP_S_SCALE = 80.0, WARP_S_AMP = 22.0;
    static final double WARP_F_SCALE = 24.0, WARP_F_AMP = 7.0;
    private static final long SALT_WX = 0x1A2B3C4D5E6F7081L;
    private static final long SALT_WZ = 0x2C3D4E5F60718293L;

    /** 多层域扭曲：返回位移后的采样坐标（x 分量）。 */
    private static double warpX(long seed, double wx, double wz) {
        return wx
                + WARP_L_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_L_SCALE, wz / WARP_L_SCALE, SALT_WX)
                + WARP_M_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_M_SCALE, wz / WARP_M_SCALE, SALT_WX + 7919L)
                + WARP_S_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_S_SCALE, wz / WARP_S_SCALE, SALT_WX + 15811L)
                + WARP_F_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_F_SCALE, wz / WARP_F_SCALE, SALT_WX + 23779L);
    }

    /** 多层域扭曲：返回位移后的采样坐标（z 分量，用独立的噪声盐）。 */
    private static double warpZ(long seed, double wx, double wz) {
        return wz
                + WARP_L_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_L_SCALE + 13.7, wz / WARP_L_SCALE + 29.1, SALT_WZ)
                + WARP_M_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_M_SCALE + 7.3, wz / WARP_M_SCALE + 17.9, SALT_WZ + 7919L)
                + WARP_S_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_S_SCALE + 3.1, wz / WARP_S_SCALE + 8.7, SALT_WZ + 15811L)
                + WARP_F_AMP * SandPlateField.fbmSeeded(seed, wx / WARP_F_SCALE + 1.9, wz / WARP_F_SCALE + 5.3, SALT_WZ + 23779L);
    }



    /**
     * 一个"大陆组"覆盖多少个核心格（边长，按核格计）。
     *
     * <p>参考把核按角距 &lt;32° 以 {@code pMerge}（0.75/0.3/0.1）随机合并成组，
     * 组<b>内</b>的核合成一个大陆，组<b>间</b>压低成海峡。无限域里没有"组"这个全局量，
     * 改用<b>扭过的粗格</b>：每 {@link #GROUP_CELLS} 个核心格算一组，
     * 组的边界再用低频噪声扭一下（不然海峡会沿直线网格出现）。</p>
     */
    static final int GROUP_CELLS = 3;
    /** 组边界扭曲的尺度（核格）与幅度（核格）。 */
    static final double GROUP_WARP_SCALE = 2.6;
    static final double GROUP_WARP_AMP = 0.9;
    /**
     * 组间海峡：压深度（壳值单位）与"两侧影响力差"的过渡宽度。参考 chanDepth=0.85, chanW=0.16。
     *
     * <p>⚠ 2026-11-14：{@code CHAN_W} 不能照抄 0.16。海峡项在壳值空间一次减 0.85，
     * 而我们核缘的壳值梯度约 {@code 1/500}（每 wu）⇒ 0.16 的过渡只摊到 ~80wu，
     * 而这条 0.85 的落差<b>横跨了整条陆坡带</b>（0.125）⇒ 实测一格跳 107 格。
     * 放宽到 0.40 后过渡摊到 ~200wu（55 格）。</p>
     */
    static final double CHAN_DEPTH = 0.60;
    static final double CHAN_W = 0.30;
    /** 海峡只在"离某组核不太远"处生效（{@code best} 的平滑门，替换参考的 {@code second>−0.8} 硬判据）。 */
    static final double CHAN_GATE_LO = -0.30;
    static final double CHAN_GATE_HI = 0.10;

    private static final long SALT_GRP = 0x4B7A2D9C6E13508FL;

    // ============================================================ 地体拼贴（2026-11-14）

    /**
     * <b>地体拼贴</b>（terrane accretion）——打破"大陆是一个圆胖椭圆"的固有形状。
     *
     * <h3>地质学事实</h3>
     * <p>真实大陆不是一次成形的整块，而是几十个<b>地体</b>（terrane：岛弧、海山、
     * 微陆块、洋壳碎片）沿俯冲带或碰撞带<b>焊接</b>上去的。结果是：
     * <ul>
     *   <li>轮廓有大量<b>凸出的半岛/岬角</b>（每个地体是一个凸块）</li>
     *   <li>地体之间是<b>凹的缝合线</b>（suture）——两个凸块的并集在交界处必然内凹</li>
     *   <li>内部岩性/年龄分区（我方由壳值梯度间接体现，见"内陆起伏"）</li>
     * </ul>
     * 参考项目的球冠并集只有 3~8 个大核 ⇒ 轮廓是"圆饼串"，这正是用户指出
     * "大陆架还是偏圆"的<b>根本原因</b>。</p>
     *
     * <h3>实现（无限域）</h3>
     * <p>每个核心格除主核外，再按哈希生成 {@link #TERR_MIN}~{@link #TERR_MAX} 个
     * <b>贴边的小椭圆</b>：中心落在"核缘 × 0.55~1.10"处（一半在里、一半在外），
     * 半径 = 主核平均半径 × 0.22~0.52，各自拉长 1.0~1.7 且方向独立。
     * 求值时与主核一起取 <b>max</b> ⇒ 凸块凸出成半岛、两块交界处内凹成海湾。</p>
     *
     * <p>⚠ 半径下限 {@link #TERR_MIN_SEMI} 是<b>硬约束</b>，不能为了"更破碎"而调小：
     * 壳值沿半径的梯度 ≈ 1/半轴，半轴 300wu 时陆架带（Δv≈0.09）只有 28wu≈8 格、
     * 陆坡带（Δv≈0.21）63wu≈18 格；半轴降到 150wu 就变成 4 格 / 9 格 ⇒ 垂直墙。</p>
     */
    static final int TERR_MIN = 3;
    static final int TERR_MAX = 5;
    /** 地体半径相对主核平均半径的比例范围。 */
    static final double TERR_R_FRAC_MIN = 0.22;
    static final double TERR_R_FRAC_MAX = 0.55;
    /**
     * 地体中心相对主核平均半径的径向位置范围（<1 在核内，>1 凸出核外）。
     *
     * <p>⚠ 2026-11-14：<b>半岛是伸出去的</b>。对照真实地图（用户贴的 1910 世界地图），
     * 印度/阿拉伯/堪察加/佛罗里达都是"伸进海里的细长条"，不是贴在核缘的小鼓包。
     * 原来 `TERR_POS_MAX=1.10` + 半径只有主核 0.22~0.52 ⇒ 地体全部缩在核内，
     * 轮廓还是椭圆。现在允许凸出到 1.45。</p>
     */
    static final double TERR_POS_MIN = 0.55;
    static final double TERR_POS_MAX = 1.45;
    /** 地体拉长比范围（地球半岛的长宽比 2~5，取保守值）。 */
    static final double TERR_STRETCH_MAX = 2.4;
    /** 地体半轴下限（wu）——见类注释里的梯度推导。 */
    static final double TERR_MIN_SEMI = 300.0;
    private static final long SALT_TERR = 0x9C43B7E15A2D806FL;

    /**
     * 核搜索半径（格）：核可抖动 0.48 格 + 长半轴最大 1250·2.4 = 3000wu ≈ 2.14 格
     * ⇒ 3 格才不漏核。
     */
    static final int CORE_SEARCH = 3;

    private static final long SALT_CORE = 0x6C4E9A2D7B3F5018L;
    private static final long SALT_OCC = 0x1F8B3C6D9E27405AL;
    private static final long SALT_CN = 0x53D9E7B1A4C6208FL;

    private SandContinent() { }

    /** 一个<b>椭圆冠</b>（主核或地体）：中心 + 长轴/短轴 + 长轴方向（cos/sin）+ 所属大陆组。 */
    record Core(double sx, double sz, double ax, double az, double cos, double sin, int group) { }

    /**
     * 一个核心格的完整内容：<b>主核 + 若干地体</b>（同一 group）。
     *
     * <p>求 crustAt 时把它们全部投进 max 并集 —— 主核给"大陆主体"，
     * 地体给"凸出的半岛 + 接缝处的凹角"。</p>
     */
    record Cell(Core main, Core[] terranes) {
        int count() {
            return 1 + terranes.length;
        }

        Core at(int k) {
            return k == 0 ? main : terranes[k - 1];
        }
    }

    // ============================================================ 核格缓存（纯函数缓存）
    //
    // 每次 crustAt 要扫 7×7 = 49 个核格，每格 1 次哈希 + 一次 2 倍频噪声;
    // 而 boundary() 每个候选边界对又要 2 次 crust01 ⇒ 一个采样点 ~5 次 crustAt
    // = 约 500 次噪声（实测 1000² 出图要 70 s）。
    // 但相邻采样点共享同一批核格 ⇒ 直接映射缓存后，命中率 >99%。
    // ⚠ coreIn 是<b>纯函数</b>（种子+格坐标唯一决定），所以缓存<b>逐位不改结果</b>。

    private static final int CACHE_BITS = 13;
    private static final int CACHE_MASK = (1 << CACHE_BITS) - 1;
    private static final long[] C_CX = new long[CACHE_MASK + 1];
    private static final long[] C_CZ = new long[CACHE_MASK + 1];
    private static final byte[] C_STATE = new byte[CACHE_MASK + 1];   // 0=空 1=有核 2=无核
    private static final Cell[] C_VAL = new Cell[CACHE_MASK + 1];
    private static long cacheSeed = Long.MIN_VALUE;

    private static int cacheIdx(int cx, int cz) {
        long h = (long) cx * 0x9E3779B97F4A7C15L ^ (long) cz * 0xC2B2AE3D27D4EB4FL;
        return (int) ((h ^ (h >>> 29)) & CACHE_MASK);
    }

    /**
     * 该格是否放核（含地体）+ 几何（确定性）。
     *
     * @return null = 该格无核
     */
    static Cell coreIn(long seed, int cx, int cz) {
        if (seed != cacheSeed) {
            java.util.Arrays.fill(C_STATE, (byte) 0);
            cacheSeed = seed;
        }
        int ci = cacheIdx(cx, cz);
        if (C_STATE[ci] != 0 && C_CX[ci] == cx && C_CZ[ci] == cz) {
            return C_STATE[ci] == 1 ? C_VAL[ci] : null;
        }
        Cell made = coreUncached(seed, cx, cz);
        C_CX[ci] = cx;
        C_CZ[ci] = cz;
        C_STATE[ci] = (byte) (made == null ? 2 : 1);
        C_VAL[ci] = made;
        return made;
    }

    private static Cell coreUncached(long seed, int cx, int cz) {
        long h = SandPlateField.hash(seed, cx, cz, SALT_CORE);
        // 低频"大陆性"场决定这一带是否有核（成组 / 留大洋）
        double occ = 0.5 + 0.5 * SandPlateField.fbmSeeded(seed,
                cx * CORE_SPACING / OCC_SCALE, cz * CORE_SPACING / OCC_SCALE, SALT_OCC);
        if (occ < OCC_THRESHOLD) {
            return null;
        }
        double sx = (cx + 0.5 + (SandPlateField.unit(h) - 0.5) * 2.0 * CORE_JITTER) * CORE_SPACING;
        double sz = (cz + 0.5 + (SandPlateField.unit(h >>> 24) - 0.5) * 2.0 * CORE_JITTER)
                * CORE_SPACING;
        double r = CORE_R_MIN + SandPlateField.unit(h >>> 48) * (CORE_R_MAX - CORE_R_MIN);
        // 拉长（面积守恒）+ 方向随机
        double st = CORE_STRETCH_MIN
                + SandPlateField.unit(h >>> 12) * (CORE_STRETCH_MAX - CORE_STRETCH_MIN);
        double ang = SandPlateField.unit(h >>> 36) * Math.PI;
        // 半轴下限：保证任何方向的海岸梯度 ≤ 1/500，否则陆坡会成垂直墙
        // 大陆组：扭过的粗格（否则组间海峡会沿直线网格出现）
        double gw = GROUP_WARP_AMP * SandPlateField.fbmSeeded(seed,
                cx / GROUP_WARP_SCALE, cz / GROUP_WARP_SCALE, SALT_GRP);
        double gw2 = GROUP_WARP_AMP * SandPlateField.fbmSeeded(seed,
                cx / GROUP_WARP_SCALE + 17.3, cz / GROUP_WARP_SCALE - 9.1, SALT_GRP + 7919L);
        int gx = (int) Math.floor((cx + gw) / GROUP_CELLS);
        int gz = (int) Math.floor((cz + gw2) / GROUP_CELLS);
        int group = (int) ((gx * 0x9E3779B1L) ^ (gz * 0x85EBCA77L));
        Core main = new Core(sx, sz, Math.max(r * st, CORE_MIN_SEMI),
                Math.max(r / st, CORE_MIN_SEMI), Math.cos(ang), Math.sin(ang), group);

        // ---- 地体拼贴：2~3 个贴边小椭圆 ----
        int nt = TERR_MIN + (int) (SandPlateField.unit(h >>> 8) * (TERR_MAX - TERR_MIN + 1));
        nt = Math.max(TERR_MIN, Math.min(TERR_MAX, nt));
        double rMean = 0.5 * (main.ax() + main.az());
        Core[] terranes = new Core[nt];
        for (int t = 0; t < nt; t++) {
            long ht = SandPlateField.hash(seed, cx * 71 + t, cz * 37 - t, SALT_TERR + t);
            double azim = SandPlateField.unit(ht) * Math.PI * 2.0;
            double pos = TERR_POS_MIN
                    + SandPlateField.unit(ht >>> 12) * (TERR_POS_MAX - TERR_POS_MIN);
            // 地体中心沿"主核的椭圆径向"摆放：用主核自己的长/短轴加权，避免总在圆上
            double uu = Math.cos(azim) * main.ax() * pos;
            double vv = Math.sin(azim) * main.az() * pos;
            double cxs = main.sx() + uu * main.cos() - vv * main.sin();
            double czs = main.sz() + uu * main.sin() + vv * main.cos();
            double rt = rMean * (TERR_R_FRAC_MIN
                    + SandPlateField.unit(ht >>> 24) * (TERR_R_FRAC_MAX - TERR_R_FRAC_MIN));
            double stt = 1.0 + SandPlateField.unit(ht >>> 36) * (TERR_STRETCH_MAX - 1.0);
            // ★ 长轴方向（2026-11-14 对照真实地图）：
            //   地球的"半岛"分两类——
            //   ① <b>径向伸出</b>（印度、阿拉伯、堪察加）：长轴垂直于海岸、指向深海；
            //   ② <b>切向沿边</b>（意大利、日本弧、安第斯南段）：长轴沿海岸延伸，
            //      两端与大陆之间留出凹湾（孟加拉湾、墨西哥湾就是这种"两半岛夹一湾"）。
            //   各占一半（50/50 随机），方向再加 ±0.3 rad 的独立抖动。
            boolean tangential = SandPlateField.unit(ht >>> 52) < 0.5;
            double angt = azim + (tangential ? Math.PI / 2.0 : 0.0)
                    + (SandPlateField.unit(ht >>> 48) - 0.5) * 0.6;
            terranes[t] = new Core(cxs, czs,
                    Math.max(rt * stt, TERR_MIN_SEMI), Math.max(rt / stt, TERR_MIN_SEMI),
                    Math.cos(angt), Math.sin(angt), group);
        }
        return new Cell(main, terranes);
    }

    /**
     * 地壳值（<b>归一化</b>，与参考一致）：冠心 ≈ 1、冠缘 ≈ 0，外海为负。
     *
     * <p>{@code s_i = (r_i − d_i)/r_i}，取所有核的 max，再叠加 6 倍频海岸噪声。
     * 阈值语义（见 {@link SandTerrain}）：{@code >0.52} 陆地、0.45~0.52 陆架、
     * 0.30~0.45 陆坡、{@code <0.30} 深海。</p>
     */
        static double crustAt(long seed, double wx0, double wz0) {
        // ★ 多层域扭曲：先位移采样点，再在<b>扭曲坐标</b>里求椭圆并集。
        //   等值线在 700/240/80/24wu 四个尺度上都变弯（分形的来源），
        //   但场的梯度结构不变 ⇒ 陆架/陆坡宽度保持、无碎块、无内陆噪点。
        double wx = warpX(seed, wx0, wz0);
        double wz = warpZ(seed, wx0, wz0);
        int cx0 = (int) Math.floor(wx / CORE_SPACING);
        int cz0 = (int) Math.floor(wz / CORE_SPACING);
        double best = -2.0;
        // second = "除最强核所在组之外，其他组的最大影响力"（参考的 second）
        double second = -2.0;
        int bestGroup = 0;
        // 地体拼贴：每个核心格的主核 + 地体<b>全部</b>参与 max 并集
        // （主核给大陆主体，地体给凸出的半岛与接缝处的凹角）
        for (int dx = -CORE_SEARCH; dx <= CORE_SEARCH; dx++) {
            for (int dz = -CORE_SEARCH; dz <= CORE_SEARCH; dz++) {
                Cell cell = coreIn(seed, cx0 + dx, cz0 + dz);
                if (cell == null) {
                    continue;
                }
                int nc = cell.count();
                for (int k = 0; k < nc; k++) {
                    Core c = cell.at(k);
                    double ddx = wx - c.sx(), ddz = wz - c.sz();
                    // 投到椭圆主轴坐标系，求归一距离 q（1 = 椭圆边界）
                    double u = ddx * c.cos() + ddz * c.sin();
                    double v = -ddx * c.sin() + ddz * c.cos();
                    double q = Math.sqrt((u / c.ax()) * (u / c.ax()) + (v / c.az()) * (v / c.az()));
                    double s = 1.0 - q;
                    if (s > best) {
                        if (c.group() != bestGroup) {
                            second = Math.max(second, best);   // 原最强核降为次强候选
                        }
                        best = s;
                        bestGroup = c.group();
                    } else if (c.group() != bestGroup) {
                        second = Math.max(second, s);
                    }
                }
            }
        }
        if (best <= -2.0) {
            best = -1.0;
        }
        // ---- 组间海峡（参考第 2 段末）----
        //  参考：if (second > −0.8) v −= chanDepth·(1 − smooth(0, chanW, best − second))
        //  我方把那个硬判据换成对 best 的平滑门（见 CHAN_GATE_LO/HI），
        //  否则 best≈second≈−0.8 处会跳 0.85（= 一整条海峡凭空出现/消失）。
        double chanGate = SandTerrain.smoothstep(CHAN_GATE_LO, CHAN_GATE_HI, best);
        best -= CHAN_DEPTH * chanGate
                * (1.0 - SandTerrain.smoothstep(0.0, CHAN_W, best - second));
        // ---- 海岸细节 ----
        // ⚠ 2026-11-14：<b>幅度式海岸噪声整个移除</b>（原 best + amp·fbm(6 倍频)）。
        //   它就是"陆架破碎 + 坡折锯齿"的来源——幅度叠进壳值会改变场的梯度
        //   ⇒ 陆架带被打碎（实测 1122 个分量）。海岸的弯曲现在<b>全部由域扭曲给出</b>。
        //   只留一层极小的细纹（1.2wu ≈ 9 km，亚格尺度）让零线不至于像素级笔直：
        return best + 0.03 * SandTerrain.fbm(wx / 6.0, wz / 6.0, SALT_CN, 2, 0.55);
    }
}