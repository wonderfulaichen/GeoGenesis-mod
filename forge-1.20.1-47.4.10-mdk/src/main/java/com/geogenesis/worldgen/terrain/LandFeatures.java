package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 陆地火山特征 — 在陆地侧叠加火山地貌（与海洋海山共用 VolcanicShape 形状数学）。
 *
 * <p>两级布点：
 * <ul>
 *   <li>单体火山 single：粗格点 (~800 块) 低概率 (~18%) 散布高锥，含真实下凹火口
 *       （环峰高、中心洼），可形成火山口湖。</li>
 *   <li>火山群 field：低频掩码 (~1/4000) 圈出火山区，区内细格点 (~200 块) 高概率
 *       散布许多小火山锥，即现实中的火山群 / 火山区（volcanic field）。</li>
 * </ul>
 *
 * <p>海陆门控由 CellGenerator 的 landMask 统一施加（本类只产出未门控增量），
 * 使火山不长进海里、海岸带平滑过渡。
 */
public final class LandFeatures {
    private final Noise singleWarpX, singleWarpZ;  // 单体火山域扭曲 @1/200
    private final Noise fieldMask;                  // 火山群区域掩码 @1/4000
    private final Noise fieldWarpX, fieldWarpZ;     // 火山群小锥域扭曲 @1/400
    private long singleSeed, fieldSeed;

    // ===== 单体火山（地标级） =====
    private static final double SINGLE_GRID = 800.0;
    /**
     * 单体火山基线生成概率。
     *
     * <p>★ 2026-09-14 P5 上调 3% → <b>8%</b>（用户反馈"孤立火山确实是少了"）。
     * 配合 {@link #ARC_VOLCANO_BOOST} 由 0.55 下调到 0.35：<b>两者互补</b> ——
     * 提升基线让火山不再"只长在俯冲带"（板块内部也有孤立火山，如夏威夷式板内火山），
     * 下调 boost 则避免俯冲带再叠加总量后密到"连成墙"。
     * 网格 800wu ⇒ 期望密度约 1 座 / (800²/0.08) ≈ 1 座 / 8000wu²。</p>
     */
    private static final int SINGLE_CHANCE = (int) (0.08 * 65536);   // ~8%
    private static final double SINGLE_RADIUS_MIN = 140.0, SINGLE_RADIUS_RANGE = 160.0; // 140~300
    /**
     * 单体火山幅度（e 单位）。
     *
     * <p>★ 2026-09-13 上调 0.12~0.30 → <b>0.20~0.42</b>（用户反馈"火山还是矮"）。
     * 陆地 1 e ≈ 236 格 ⇒ 原幅度仅 28~71 格，在平原上不构成地标；
     * 现为 <b>47~99 格</b>（典型层状火山相对基底应有 1000~3000m 的规模，
     * 本项目世界仅 384 格高，故按比例取此值）。</p>
     */
    private static final double SINGLE_AMP_MIN = 0.20, SINGLE_AMP_RANGE = 0.22;          // 0.20~0.42
    private static final double SINGLE_CRATER_MIN = 0.02, SINGLE_CRATER_RANGE = 0.05;    // 0.02~0.07

    // ===== 火山群（极罕见） =====
    private static final double FIELD_MASK_FREQ = 1.0 / 4000.0;
    private static final double FIELD_MASK_THRESHOLD = 0.72; // 更高掩码 → 更小覆盖 (~1.4% 陆地)
    private static final double FIELD_GRID = 200.0;
    private static final int FIELD_CHANCE = (int) (0.12 * 65536);   // ~12% → 区内更稀疏
    /** 火山群小锥半径（wu）。★ 同步上调：幅度提高后若半径不变，坡度会陡到失真。 */
    private static final double FIELD_RADIUS_MIN = 70.0, FIELD_RADIUS_RANGE = 90.0;      // 70~160
    /**
     * 火山群小锥幅度（e 单位）。
     *
     * <p>★ 2026-09-13 上调 0.05~0.12 → <b>0.12~0.28</b>（用户反馈"火山区仍矮"：
     * 实测在平原上火山区只能高出周边 ~6 格）。原值仅 12~28 格且常被采到侧翼，
     * 现为 <b>28~66 格</b>；配合半径上调，坡度仍自然（≈40°）。</p>
     */
    private static final double FIELD_AMP_MIN = 0.12, FIELD_AMP_RANGE = 0.16;            // 0.12~0.28

    public LandFeatures() {
        singleWarpX = new Frequency(new Simplex(801), 1.0 / 200.0);
        singleWarpZ = new Frequency(new Simplex(802), 1.0 / 200.0);
        fieldMask = new Frequency(new Simplex(803), FIELD_MASK_FREQ);
        fieldWarpX = new Frequency(new Simplex(804), 1.0 / 400.0);
        fieldWarpZ = new Frequency(new Simplex(805), 1.0 / 400.0);
    }

    public void seed(long worldSeed) {
        singleSeed = worldSeed + 111111111L;
        fieldSeed = worldSeed + 222222222L;
        Noises.seedAll(singleWarpX, worldSeed, 0);
        Noises.seedAll(singleWarpZ, worldSeed, 0);
        Noises.seedAll(fieldMask, worldSeed, 0);
        Noises.seedAll(fieldWarpX, worldSeed, 0);
        Noises.seedAll(fieldWarpZ, worldSeed, 0);
    }

    /** 陆地火山计算结果（分离单体 / 群分量，供 CellGenerator 分类用） */
    public static final class FeatureResult {
        /** 叠加到地形的<b>净</b>抬升（已扣火口下凹）。 */
        public final double total, single, field;
        /**
         * ★ 2026-09-13 新增：<b>山体范围掩码</b>（<b>不扣</b>火口下凹），仅供类型判定。
         *
         * <p><b>为何需要</b>（用户反馈"红圈火山中间竟然是旁边的地形，不是火山了"）：
         * 类型判定原本用 {@code single > 0.05}（净高度），而火口在中心下凹
         * {@code crater ∈ [0.02, 0.07]} ⇒ 当 {@code amp} 较小时
         * {@code contrib(0) = amp − crater} 会<b>跌破 0.05</b>
         * （实测组合 {@code amp=0.12, crater=0.07} → 恰好 0.05，严格大于不成立）
         * ⇒ 中心被判成周围类型，只剩一圈"环形火山"。</p>
         *
         * <p>但地质上<b>火口内部仍属这座火山</b>（火山口湖也是火山的一部分）。
         * 故类型判定应基于"<b>山体是否覆盖此处</b>"（= 不扣火口的剖面包络），
         * 而非"此处比周围高多少"。地形仍用 {@link #single}/{@link #field}（含火口）。</p>
         */
        public final double singleEdifice, fieldEdifice;

        FeatureResult(double t, double s, double f, double se, double fe) {
            total = t; single = s; field = f; singleEdifice = se; fieldEdifice = fe;
        }
    }

    /**
     * ★ 2026-09-13 Phase T7(P4)：<b>俯冲带地形门控</b>（构造采样 → 弧轴强度）。
     *
     * <h3>为何需要</h3>
     * <p>原先陆上火山布点是<b>纯随机哈希</b>（与地质无关）。而真实地球的陆上火山
     * <b>几乎全部沿俯冲带成链</b>（安第斯 / 喀斯喀特 / 日本 / 爪哇），板内火山稀少。
     * 本门控把火山布点<b>概率</b>沿俯冲带弧轴提升 ⇒ 火山成链，其余区域保持基线稀有。</p>
     *
     * <h3>为何用"注入构造采样"而非直接持有 TectonicField</h3>
     * <p>与 {@link OceanFeatures#setSeamountDepthChecker} 同一模式：本类保持"不感知构造场
     * 内部结构"，且便于诊断探针独立替换（例如传入恒 null 以复现改造前行为）。</p>
     *
     * <p>由 {@code CellGenerator.seed} 注入 {@code tectonic::arcGate}；
     * 未注入（{@code null}）时退化为<b>改造前语义</b>（纯随机布点，便于回滚与对照）。</p>
     */
    private java.util.function.ToDoubleFunction<TectonicField.Sample> arcVolcanismProvider;

    /** 注入俯冲带火山活动强度（{@code CellGenerator.seed} 调用；传 {@code null} 即退回旧行为）。 */
    public void setArcVolcanismProvider(
            java.util.function.ToDoubleFunction<TectonicField.Sample> provider) {
        this.arcVolcanismProvider = provider;
    }

    /**
     * ★ 2026-09-14 P5：<b>中心点海陆判定</b>（由 {@code CellGenerator.seed} 注入
     * {@code CellGenerator::landFactorAt}）。
     *
     * <h3>为何必须按中心点（而不是按采样点乘 landFactor）</h3>
     * <p>用户反馈"火山山体靠海就被海陆过渡机制影响，火山口被强行降低"。
     * 原实现 {@code eLand += landFeat.total * landFactor(x,z)} 是<b>逐点缩放</b>：
     * 过渡带仅 0.02e（≈4.7 格）⇒ 横跨海岸的火山被"斜切"，且火口下凹同样被压浅。</p>
     *
     * <p>本字段改为在<b>火山中心</b>调用一次：{@code landFactor(center) > 0} 才生成整座火山
     * （含完整火口），否则整座不生成。这与 {@link OceanFeatures} 的
     * {@code seamountCenterDepthCheck} <b>完全同构</b>（同一问题、同一解法）。</p>
     *
     * <p>{@code null} → 不做海陆过滤（退化为"处处生成"，等价于旧行为在纯陆地处的效果）。</p>
     */
    private java.util.function.DoubleBinaryOperator centerLandFactor;

    /** 注入中心点海陆判定（入参 (centerX, centerZ) → 陆地门控 ∈ [0,1]）。 */
    public void setLandFactorProvider(java.util.function.DoubleBinaryOperator provider) {
        this.centerLandFactor = provider;
    }

    /**
     * ★★★ 2026-09-14 P6：俯冲带火山的<b>幅度</b>增强系数上限（取代旧的"概率增强"）★★★
     *
     * <h3>被修复的缺陷（用户反馈"火山地形出现被切掉一些的不自然情况"）</h3>
     * <p>原实现把门控 {@code boost}（连续）加在<b>格点存在性阈值</b>上：
     * <pre>{@code
     *   if ((h & 0xFFFF) >= SINGLE_CHANCE + (int) Math.round(boost * 65536)) continue;
     * }</pre>
     * 但 {@code h & 0xFFFF} 是<b>离散随机值</b>，而 {@code boost} 沿空间<b>连续</b>变化
     * ⇒ 阈值扫过某个格点的哈希值时，该格点的"有锥/无锥"状态<b>离散翻转</b>
     * ⇒ 一整座锥<b>突然出现/消失</b>（实测：某点 4wu 内 {@code fieldEdifice} 从 0 跳到
     * <b>0.111</b> ≈ 一整个锥的最小幅度，{@code e} 跳 26 格）⇒ 视觉上就是"被切掉一块"。
     *
     * <p><b>为何实测确认与"中心海陆门控"无关</b>：A/B 实验（同一位置，只切
     * {@code setLandFactorProvider}）显示"无门控"与"有门控"的 {@code field} <b>完全相同</b>
     * ⇒ 该缺陷在 P5 之前就存在（P4 引入 boost 时即已埋下）。</p>
     *
     * <h3>正解：门控只调【幅度】，绝不调【存在性】</h3>
     * <ul>
     *   <li><b>位置/数量</b>由哈希决定 ⇒ 与门控无关 ⇒ <b>不存在翻转</b>（连续地貌）。</li>
     *   <li><b>高度</b>随门控连续增强 ⇒ 地质表达保留（俯冲带火山更高大，
     *       这正是火山链与板内火山的真实差别：安第斯型锥体规模远大于板内小火山）。</li>
     * </ul>
     * <p>这与本项目既有铁律一致：<b>任何连续量都可用于"幅度"，但"存在性/开关"
     * 必须由稳定哈希决定</b>（否则沿该连续量的等值线会出现整片突变）。</p>
     */
    private static final double ARC_AMP_BOOST = 0.9;   // 门控满值时幅度 ×1.9

    /**
     * 门控 → 幅度倍率（<b>连续</b>，∈ [1, 1+{@link #ARC_AMP_BOOST}]）。
     *
     * <p>见 {@link #ARC_AMP_BOOST}：门控只调幅度、不调存在性，故不会产生"整锥突现"的切割感。</p>
     */
    private static double ampBoost(double boost) {
        return 1.0 + ARC_AMP_BOOST * boost;
    }

    /**
     * 火山中心处的陆地门控 ∈ [0,1]（整座火山按此因子<b>整体</b>缩放）。
     *
     * <h3>为何是"整体缩放"而不是"逐点缩放"（核心区别）</h3>
     * <ul>
     *   <li><b>逐点缩放（原实现，错）</b>：{@code total(x,z) × landFactor(x,z)}。
     *       因 {@code landFactor} 沿空间变化 ⇒ 山体<b>靠海一侧被压缩得更多</b> = 斜切；
     *       火口下凹（0.02~0.07e）在 {@code eLandBase≈0} 处几乎被抹平。</li>
     *   <li><b>整体缩放（现实现，对）</b>：{@code total(x,z) × landFactor(center)}。
     *       因子在整座火山范围内是<b>常数</b> ⇒ 山体形状不变（只是整体矮一点），
     *       <b>火口深度与山体坡度完全保持</b>；同时中心在深海时因子为 0
     *       ⇒ 该火山整体不生成（<b>杜绝"陆地火山造陆"</b>，与海山同构）。</li>
     * </ul>
     *
     * <p>这同时解决了"海上为 0"的约束：原实现靠逐点 {@code landFactor} 保证
     * 海上无贡献，但它以斜切为代价；整体因子在保持同一约束的同时不斜切。</p>
     *
     * @param cx 火山中心 X（wu）
     * @param cz 火山中心 Z（wu）
     * @return 陆地门控 ∈ [0,1]；未注入提供者时返回 1（不过滤，向后兼容）
     */
    private double centerFactorOf(double cx, double cz) {
        if (centerLandFactor == null) return 1.0;    // 未注入 → 不过滤（向后兼容）
        double f = centerLandFactor.applyAsDouble(cx, cz);
        return f < 0.0 ? 0.0 : (f > 1.0 ? 1.0 : f);
    }

    /**
     * 火山沿弧轴的概率提升上限。
     *
     * <p><b>为何不把概率直接乘到 1.0</b>：那样"弧轴上处处有火山"，会形成一条
     * <b>连成一堵墙</b>的火山带（真实火山链是"串珠"而非"墙"—— 弧轴上仍有间隙）。
     * 取 0.35：弧轴上新火山概率 = 基线 + 0.35×(1−基线)，即"显著更密但仍有间隙"。</p>
     *
     * <p>★ 2026-09-14 P5 由 0.55 下调到 <b>0.35</b>（与 {@link #SINGLE_CHANCE} 上调互补）：
     * 用户反馈"孤立火山少了"—— 原 0.55 使 58% 格点都在俯冲带生成火山，火山几乎全部
     * 依附于构造带；下调后配合基线翻倍，既保留"沿俯冲带成链"的地质特征，
     * 又恢复板内孤立火山的可见度。</p>
     */
    private static final double ARC_VOLCANO_BOOST = 0.35;

    /**
     * 计算陆地火山增量。
     *
     * @param wx      世界 X
     * @param wz      世界 Z
     * @param tect    构造采样（{@code null} → 退回纯随机布点，与改造前等价）
     */
    public FeatureResult compute(double wx, double wz, TectonicField.Sample tect) {
        // 弧轴门控（0~1）：由注入的提供者算出，未注入则为 0（= 退回旧行为）
        double gate = (arcVolcanismProvider != null && tect != null)
                ? arcVolcanismProvider.applyAsDouble(tect) : 0.0;
        double boost = ARC_VOLCANO_BOOST * clamp01(gate);
        // out[0] = 净抬升（含火口） / out[1] = 山体掩码（不扣火口）
        double[] so = new double[2], fo = new double[2];
        singleCompute(wx, wz, boost, so);
        fieldCompute(wx, wz, boost, fo);
        return new FeatureResult(so[0] + fo[0], so[0], fo[0], so[1], fo[1]);
    }

    /** 兼容旧调用（无构造信息 → 纯随机布点）。 */
    public FeatureResult compute(double wx, double wz) {
        return compute(wx, wz, null);
    }

    private static double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

    /**
     * 单体火山累加。
     *
     * @param out {@code out[0]} = 净抬升（含火口下凹，供地形）；
     *            {@code out[1]} = 山体范围掩码（<b>不扣</b>火口，供类型判定 —— 见
     *            {@link FeatureResult#singleEdifice} 的注释）
     */
    private void singleCompute(double wx, double wz, double boost, double[] out) {
        long cx = (long) Math.floor(wx / SINGLE_GRID);
        long cz = (long) Math.floor(wz / SINGLE_GRID);
        double total = 0.0;
        double edifice = 0.0;
        // ★★★ 2026-09-14 P6：门控改调【幅度】，不再调【存在性】（见 ARC_AMP_BOOST）★★★
        //   旧写法把连续的 boost 加到离散哈希阈值上（SINGLE_CHANCE + boost×65536）
        //   ⇒ 阈值扫过某格点哈希值时该锥"突现/突消" ⇒ 用户看到的"被切掉一块"。
        //   现在：存在性只看固定哈希（位置/数量与门控无关），boost 只缩放高度。
        final double ampB = ampBoost(boost);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz, singleSeed);
                if ((h & 0xFFFF) >= SINGLE_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF, SINGLE_GRID);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF, SINGLE_GRID);
                // ★ 2026-09-14 P5：按【中心点】取陆地门控（整座火山<b>整体</b>缩放）。
                //   中心在深海 → 因子 0 → 整座不生成（杜绝陆地火山造陆）；
                //   中心在海岸线 → 因子 ∈ (0,1) → 整体矮一点，但山体形状与火口<b>不被斜切</b>。
                double cf = centerFactorOf(centerX, centerZ);
                if (cf <= 0.0) continue;
                // 形状 / 几何：独立 salt 的 64 位哈希，避免与 chance/center 位重叠
                long hg = hashCell(cx + dx, cz + dz, singleSeed ^ 0x9E3779B1L);
                int shapeType = (int) (hg & 0x3) % 2; // 0 CONE(strato), 1 GUYOT(shield)
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.6 + ((hg >>> 12) & 0xFF) / 255.0 * 0.8;
                double asz = 0.6 + ((hg >>> 20) & 0xFF) / 255.0 * 0.8;
                double radius = SINGLE_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * SINGLE_RADIUS_RANGE;
                double amp = (SINGLE_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * SINGLE_AMP_RANGE) * ampB;
                double crater = (SINGLE_CRATER_MIN + ((hg >>> 52) & 0xFF) / 255.0 * SINGLE_CRATER_RANGE) * ampB;
                // 域扭曲（幅度正比 radius，频率固定 1/200 防 Jacobian 折叠）
                double wamp = radius * 0.4;
                double wx2 = wx + singleWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + singleWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double body = VolcanicShape.profile(shapeType, d, amp) * cf;
                edifice += body;                                  // ★ 山体（不扣火口）
                double contrib = body - VolcanicShape.crater(d, 0.25, crater) * cf; // 顶部下凹火口
                if (contrib > 0.0) total += contrib;
            }
        }
        out[0] = total;
        out[1] = edifice;
    }

    /**
     * 火山群累加（与 {@link #singleCompute} 同构）。
     *
     * @param out {@code out[0]} = 净抬升 / {@code out[1]} = 山体掩码（不扣火口）
     */
    private void fieldCompute(double wx, double wz, double boost, double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        // 先判断是否落在火山区内（低频掩码圈域）。
        // 修复：原版 `if (mask < THRESHOLD) return 0` 是硬阈值，在 mask=0.72 的等值线上产生跳变，
        // 幅值最高 0.12 e ≈ 30 blocks → 大断裂面。改用 smoothstep 平滑淡入，保证地形连续。
        // ★★★ 2026-09-14 P6：阈值【不再】随弧轴门控下移 ★★★
        //   旧实现 `thr = FIELD_MASK_THRESHOLD − boost×0.35` 把连续门控放到【区域存在性阈值】上：
        //   阈值扫过掩码等值线时，整片"火山区/非火山区"在 4wu 内整体翻转
        //   （实测该处 Δfield=0.111 且 Δgate=0），与"格点存在性翻转"是同一种缺陷的【区域版】。
        //   现在阈值固定（火山区范围与地质门控解耦 —— 区域掩码本就表达"这片地方是火山区"），
        //   门控只调【幅度】（见 ampB）与【下限】（见 ampFloor，仍是连续量）。
        double mask = fieldMask.compute(wx, wz) * 0.5 + 0.5; // [-1,1] → [0,1]
        double thr = FIELD_MASK_THRESHOLD;                   // 固定阈值（连续淡入）
        double fade = smoothstep(thr - 0.05, thr + 0.05, mask);
        if (fade <= 0.0) return;
        long cx = (long) Math.floor(wx / FIELD_GRID);
        long cz = (long) Math.floor(wz / FIELD_GRID);
        double total = 0.0;
        double edifice = 0.0;
        // ★ P6：门控改调幅度（同 singleCompute），存在性只看固定哈希
        final double ampB = ampBoost(boost);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz, fieldSeed);
                if ((h & 0xFFFF) >= FIELD_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF, FIELD_GRID);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF, FIELD_GRID);
                // ★ 2026-09-14 P5：小锥同样按【中心点】取门控（整体缩放，与 single 一致）
                double cf = centerFactorOf(centerX, centerZ);
                if (cf <= 0.0) continue;
                long hg = hashCell(cx + dx, cz + dz, fieldSeed ^ 0x9E3779B1L);
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.7 + ((hg >>> 12) & 0xFF) / 255.0 * 0.6;
                double asz = 0.7 + ((hg >>> 20) & 0xFF) / 255.0 * 0.6;
                double radius = FIELD_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * FIELD_RADIUS_RANGE;
                double amp = (FIELD_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * FIELD_AMP_RANGE) * ampB;
                double wamp = radius * 0.4;
                double wx2 = wx + fieldWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + fieldWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double body = VolcanicShape.profile(VolcanicShape.CONE, d, amp) * cf;
                edifice += body;                                   // ★ 山体（不扣火口）
                double contrib = body - VolcanicShape.crater(d, 0.3, amp * 0.4) * cf; // 小火口
                if (contrib > 0.0) total += contrib;
            }
        }
        // ★ fade 只施加于【地形净抬升】；类型判定用的山体掩码也需同源淡入，
        //   否则火山区之外的孤立锥也会被判成 VOLCANIC_FIELD（区域语义被破坏）。
        out[0] = total * fade;
        out[1] = edifice * fade;
    }

    /** smoothstep 平滑过渡：x 在 [edge0, edge1] 间做 Hermite 插值 */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = x <= edge0 ? 0.0 : (x >= edge1 ? 1.0 : (x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /** 格子内中心位置抖动（±30% 格子范围） */
    private static double cellCenter(long cell, long bits, double grid) {
        return cell * grid + (bits / 65536.0 - 0.5) * grid * 0.6;
    }

    /** 确定性 64 位哈希（混合 salt，不同 salt 取不同随机维度，避免位重叠） */
    private static long hashCell(long cx, long cz, long salt) {
        long h = salt + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h;
    }
}
