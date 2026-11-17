package com.geogenesis.worldgen.terrain;

/**
 * ★ 2026-11-13（桥接 S2）：O(1) <b>构造地壳场</b>——有限模拟（SimpleTectonics 复刻，
 * 形态基准 SHA {@code f585313d…}）到确定性流式生成的解析 surrogate。
 *
 * <h3>物理图像（与复刻基线/S1 正统式同源）</h3>
 * <ol>
 *   <li><b>age</b>（年龄代理）：洋壳在<b>离散边界</b>（洋脊轴）诞生，随离脊距离冷却增厚。
 *       代理量 = {@code distDiv}（到最近离散边界的距离，TectonicField 桥接新增，
 *       单调映射，铁律 R5 合规）；跨边界的两侧值差由环形模糊（150wu）软化，
 *       汇聚边界处 κ 主导使 age 不敏感。</li>
 *   <li><b>κ</b>（碰撞/陆化）：连续应力 {@code stress}（+1 纯汇聚）的正叶瓣天然集中在
 *       汇聚边界，σ=1000wu 的投票尺度即造山带宽度。碰撞带增生<b>厚且轻</b>的陆壳
 *       （granitic accretion）——这是大陆从构造里长出来的唯一通道。</li>
 *   <li><b>均衡导出</b>：{@code buoyancy = thickness·(ρ_mantle − ρ_litho)}
 *       （S1 正统式，commit 5ff6270）。三条剖面自洽：
 *       脊轴薄热→沉降小→浅；洋盆老而密→沉降大→深；碰撞带厚而轻→强浮→陆/山。</li>
 * </ol>
 *
 * <h3>与复刻基线的关系（范式裁定：C = 真实均衡）</h3>
 * <p>复刻（area=1 截断怪癖）里"成熟=陆"；本类按 S1 已验证方向（5ff6270：
 * "洋底冷却应越老越沉"）取<b>真实均衡</b>：海 = 成熟洋盆，陆 = 碰撞造山带。
 * 复刻提供的是<b>形态目标</b>（脊浅/盆深/裂谷线状/碰撞链）与量级
 * （landFrac≈0.4、裂谷占比 ~20%），标定探针（CrustFieldProbe）出
 * age 主导 vs κ 主导两极端对比图，S3 陆侧档位停等门留给用户最终裁定。</p>
 *
 * <h3>连续性约束（项目铁律）</h3>
 * <ul>
 *   <li>只用连续输入：{@code distDiv}（单调）、{@code stress}（连续投票场）。</li>
 *   <li>所有映射两端一阶导为 0（smoothstep，铁律 ④）。</li>
 *   <li>输出是无量纲归一化量 + e 量纲的 eBase；换格数在消费层
 *       （MorphOceanAssembler 经 HeightCurve 派生比例，铁律 R7：不用 127 字面量）。</li>
 * </ul>
 */
public final class CrustField {

    // ============================================================ 常量（S1 复用 + S2 新增）

    /** 洋壳脊轴基准厚度（归一化）——复用 S1 {@code LithoPhysical.THICKNESS_OCEANIC}。 */
    static final double THICKNESS_OCEANIC = LithoPhysical.THICKNESS_OCEANIC;      // 0.20
    /** 年龄增厚幅度——复用 S1。 */
    static final double THICKNESS_AGE_GAIN = LithoPhysical.THICKNESS_AGE_GAIN;    // 0.35
    /** 脊轴半宽（wu）——复用 S1。 */
    static final double RIDGE_HALF_WU = LithoPhysical.RIDGE_HALF_WU;              // 120
    /** 年龄饱和距离尺度（wu）——复用 S1。 */
    static final double AGE_REACH_WU = LithoPhysical.AGE_REACH_WU;                // 900
    /** 脊轴洋壳密度（最热最轻）——复用 S1。 */
    static final double DENSITY_OCEANIC = LithoPhysical.DENSITY_OCEANIC;          // 0.62
    /** 年龄增密幅度——复用 S1。 */
    static final double DENSITY_AGE_GAIN = LithoPhysical.DENSITY_AGE_GAIN;        // 0.30
    /** 陆壳密度下限（碰撞陆化的终点）——复用 S1。 */
    static final double DENSITY_CONTINENTAL = LithoPhysical.DENSITY_CONTINENTAL;  // 0.40
    /** 地幔参考密度——复用 S1。 */
    static final double DENSITY_MANTLE = LithoPhysical.DENSITY_MANTLE;            // 0.95

    /**
     * 碰撞增生厚度（归一化）：κ=1 时陆壳总厚度 ≈ 0.20+0.35+1.0 = 1.55。
     *
     * <p>标定依据（复刻基线）：碰撞链高度显著高于洋盆——均衡式下要"强浮"
     * 就必须又厚又轻；1.0 使陆壳厚度达到洋壳成熟的 ~3 倍（真实陆壳:洋壳 ≈ 5:1，
     * 归一化后取 3:1 留给 morph 层地形起伏空间）。</p>
     */
    static final double THICKNESS_COLLISION = 1.0;

    /**
     * 碰撞陆化降密幅度：κ=1 时 ρ 从 0.92 降到 0.42 ≈ 陆壳密度。
     *
     * <p>为何必须降密：只增厚不降密时，碰撞带 buoyancy = 1.55×(0.95−0.92) ≈ 0.05
     * 与洋盆同量级 → 造山带不会浮出海面。真实地球碰撞造山是<b>陆壳叠置</b>
     * （轻的花岗岩质物质堆积）——"又厚又轻"才是陆。</p>
     */
    static final double DENSITY_COLLISION_DROP = 0.50;

    /**
     * κ 的应力整形下限：stress 低于此值视为无碰撞（走滑/离散区归零）。
     *
     * <p>取 0.15：应力投票场的噪声底约 ±0.1（5×5 高斯投票的残余），
     * 低于它不触发陆化，避免板块内部随机浮斑。</p>
     */
    static final double KAPPA_STRESS_MIN = 0.15;
    /** κ 的应力饱和点：stress ≥ 此值时陆化全量（应力叶瓣核心）。 */
    static final double KAPPA_STRESS_MAX = 0.55;

    // ★ 已否决记录（2026-11-13）：age 的两种失败形态（勿重走）——
    //   ① distDiv 几何距离直用：穿过汇聚/走滑边界时 c1 配对集合整体切换 ⇒ min 跳变；
    //     ±1 窗口漏检再叠格网线轴对齐直边（用户圈出 4 处）；±2 加宽无效（根源是配对切换）。
    //   ② stress 负叶瓣直接整形 age（AGE_RESET 窗口 0.03/0.15 与 0.05/0.45 两档）：
    //     stress 投票场负值覆盖远超预期（中位 ≤ −0.45），大半图二值化——
    //     该场语义是 T1/T5 调制（板块自身径向速度投影染色内部），不是年龄场。
    //   ⇒ 终案：dist 老化 × divProx（stress 负叶瓣只作【掩码】，见 {@link #of}）。
    //   两者共同教训：连续性必须来自【连续输入的组合】，而不是新造的 min/切换型几何量。

    /**
     * 年龄年轻化（divProx）的应力整形窗口：stress 负叶瓣 = 离散边界邻近度<b>掩码</b>。
     *
     * <p>MIN=0.03：板块内部应力平滑趋 0（虽有大片 −0.2~−0.5 的负叶瓣，但那里
     * base=1，divProx 不影响 age——见 {@link #of} 的组合式）；MAX=0.20：
     * 中等以上扩张的脊轴完全重置（age→0）。</p>
     */
    static final double AGE_RESET_MIN = 0.03;
    static final double AGE_RESET_MAX = 0.20;

    /**
     * 海平面均衡基准 B0（buoyancy 量纲）：B &gt; B0 的地壳浮出海面。
     *
     * <p>标定逻辑：成熟洋盆 buoyancy ≈ 0.55×(0.95−0.92) ≈ 0.017（最深），
     * 脊轴 ≈ 0.20×(0.95−0.62) ≈ 0.066（浅），κ=1 碰撞带 ≈ 1.55×(0.95−0.42) ≈ 0.82（最高）。
     * B0 必须落在洋盆与碰撞带之间——取 0.12：洋盆全部在海下、脊轴也在海下
     * （真实洋脊在海面下 ~2000m）、碰撞带核心成陆。陆占比由 κ 叶瓣覆盖面积决定
     * （跟随板块格局），S3 标定探针出图核对 ~0.4 量级后可微调。</p>
     */
    static final double BUOYANCY_SEA_LEVEL = 0.12;

    // ============================================================ 阶段一：洋底基准——平台模型年龄律 + 洋底构造带
    //
    // ★ 2026-11-14（阶段一落地）：均衡式 eBase 换成<b>平台模型年龄律 + 三项洋底构造带</b>。
    //   沙箱标定全记录：audit/GeoGenesis-板块地形沙箱-20261113.md 第五/八/九轮
    //   （commit a686478 的 tectonicsand 包是设计与验收基准）。
    //
    //   标定目标（沙箱 1000² 实测 vs 地球）：
    //   · 洋深 p50 −3929 m（地球 −3880）✓
    //   · 4~5 km 主带 45.3%（地球 45%）✓
    //   · 深海丘陵：25 km 尺度坡度从 2.1 提到 3+ m/km（地球同尺度 2~4）✓
    //
    //   ⚠ 旧均衡式（thickness/density → buoyancy → eBase）在
    //     {@code geogenesis.oceanPlateauModel=false} 时完整保留（A/B 回退）。
    //     thickness/density/buoyancy 物理层<b>照常计算</b>——岩性层仍消费它们。

    /** 海平面（e=0）到陆架坡折的锚深：−160 m（参考阶梯的陆架顶）。 */
    static final double SHELF_ANCHOR_M = -160.0;
    /** 洋底"米 → e"换算：与沙箱同一口径（1 e = 11000 地质米）。 */
    static final double METERS_PER_E = 11000.0;

    /** 平台模型：脊顶深度 D0（m）。地幔潜能温度决定，全球普遍 −2500 m。 */
    static final double AGE_D0_M = 2500.0;
    /** 平台模型：平台深度 Dinf（m）。老年洋底渐近值。 */
    static final double AGE_DINF_M = 5700.0;
    /** 平台模型：时间常数 τ（Ma）。 */
    static final double AGE_TAU_MA = 70.0;
    /**
     * 年龄上限（Ma）。沙箱三档实测（90/140/200）：90 时 4~5 km 主带 45.3%（地球 45%）、
     * p50 −3929（地球 −3880）——唯一同时对上主带与中位数的档。
     */
    static final double AGE_TMAX_MA = 90.0;
    /** 离散边界处 age 的清零语义 ⇒ t = age·TMAX：age=0（脊轴）↔ t=0。 */
    static final double PLATEAU_T_SCALE = AGE_TMAX_MA;

    /**
     * 深海丘陵幅度（m）与基波波长（wu）。
     *
     * <p>⚠ 2026-11-17 尺度修正（用户："红圈处还有奇怪的折线"）：旧波长 45wu
     * （≈320km 标注、实际 45wu≈400m 尺度）在 900wu 俯视窗口下必然渲染成
     * <b>斜向平行条纹</b>——那是 value noise 的格子方向性伪影被放大，
     * 不是地质结构（分解图的 age-law 通道里同样有它）。
     * 现波长 900wu（基波）+ 429 + 205（3 倍频）⇒ 俯视图上是可辨的丘陵形态
     * 而非密纹。幅度不变（100m ≈ 1 格）。
     *
     * <p>★ 该改动影响<b>整个洋底</b>（不只洋中脊）⇒ P1 全图 SHA 必变，
     * 但陆侧必须逐位不变（P1 陆侧 SHA 守护）。
     */
    static final double ABYSS_HILLS_M = 100.0;
    static final double ABYSS_HILL_SCALE = 900.0;
    /** 轴部裂谷：深度（m）与宽度（wu）。慢速扩张脊标配（1~2 km 深、20~50 km 宽）。 */
    /** 海沟：深度（m）与宽度（wu）。洋-洋俯冲全球最深（−8000~−11000 m）。 */
    static final double TRENCH_M = 3600.0;
    static final double TRENCH_WU = 17.0;
    /** 海沟的汇聚门：stress 高于 0.35 开始成形、0.80 全量（连续投票场，无离散分支）。 */
    static final double TRENCH_STRESS_LO = 0.35;
    static final double TRENCH_STRESS_HI = 0.80;

    /**
     * 洋底模型开关（阶段一）：默认开，A/B 回退 = 旧均衡式。
     *
     * <p>单读取点（铁律 ①）；改动产出的缓存版本已随本提交升级
     * （PreviewDisplay.CACHE_SCHEMA_VERSION 130→131）。</p>
     */
    static final boolean OCEAN_PLATEAU_MODEL =
            !"false".equals(System.getProperty("geogenesis.oceanPlateauModel"));

    private static final long SALT_HILLS = 0x51B7C3A9E24D6081L;
    private static final long HILL_SEED_MIX = 0x9E3779B97F4A7C15L;


    // ============================================================ 采样结果

    /**
     * 地壳场采样结果（不可变）。
     *
     * @param age       年龄因子 0（洋脊轴年轻壳）~1（成熟壳），dist 老化 × 离散邻近掩码的组合
     * @param kappa     碰撞/陆化因子 0~1（stress 正叶瓣整形）
     * @param thickness 岩石圈厚度（归一化 0.20~1.55）
     * @param density   岩石圈密度（归一化 0.40~0.92）
     * @param buoyancy  均衡项 thickness·(ρ_mantle−ρ)，正=上浮
     * @param eBase     构造基准高度（<b>e 量纲</b>）：B&gt;B0 为正（陆，S3 才接线）、
     *                  B&lt;B0 为负（海床，S2 接线）；量程 ≈ [−0.5, +1]
     */
    public record Sample(double age, double kappa, double thickness, double density,
                         double buoyancy, double eBase) { }

    // ============================================================ 主查询

    /**
     * 由构造采样计算地壳物理量与 eBase。
     *
     * @param ts   构造场采样（用连续量 {@code dist}/{@code distRaw}/{@code stress}；
     *             禁用离散 {@code btype}/{@code rate}——铁律）
     * @param wx   世界坐标 x（深海丘陵噪声用）
     * @param wz   世界坐标 z
     * @param seed 世界种子（丘陵噪声键控，随世界变化）
     */
    public static Sample of(TectonicField.Sample ts, double wx, double wz, long seed) {
        double stress = ts.stress();

        // ---- 1) 年龄 = "到任意边界距离的老化" × "离散邻近度掩码" ----
        //   base：dist（既有连续场，含 blur）的单调饱和映射——在【所有】边界处清零。
        //   divProx：stress 负叶瓣（离散边界邻近度，σ=1000wu 连续）。
        //   age = 1 − (1−base)·divProx：
        //     · 洋脊轴（base→0, divProx→1）→ age→0（新薄壳，浅）✓
        //     · 转换边界（base→0, divProx→0）→ age→1 ✓ 两侧 crust 各保留年龄——
        //       若无此掩码，dist 在走滑边界清零会印出年轻浅水的线状疤痕
        //       （★ distDiv 路线彻底废弃的根因之一，见下方否决记录）
        //     · 汇聚边界（base→0, divProx→0）→ age→1 ✓ 碰撞处是老 crust；
        //       且 κ→1 主导 buoyancy，age 在此无关紧要
        //     · 板块内部（base→1）→ age→1（divProx 任意值都不影响）✓
        //   ⚠ 否决记录（2026-11-13，勿重走）：
        //   ① distDiv（到最近离散边界的几何距离）——穿过汇聚/走滑边界时 c1 配对
        //     集合整体切换 ⇒ min 值跳变；±1 窗口漏检再加格网线轴对齐直边
        //     （用户圈出 4 处）；±2 加宽无效（跳变根源是配对切换不是窗口）。
        //   ② stress 负叶瓣直接整形 age——实测中位 stress ≤ −0.45，大半图二值化。
        //   两者共同教训：连续性必须来自【连续输入的组合】，而不是新造的
        //   min/切换型几何量。
        double base = smoothstep(RIDGE_HALF_WU, RIDGE_HALF_WU + AGE_REACH_WU,
                Math.max(0.0, ts.dist()));
        double divProx = smoothstep(AGE_RESET_MIN, AGE_RESET_MAX, -stress);
        double age = 1.0 - (1.0 - base) * divProx;

        // ---- 2) 碰撞/陆化：stress 正叶瓣整形（两端导数为 0，无硬截断折痕）----
        double kappa = smoothstep(KAPPA_STRESS_MIN, KAPPA_STRESS_MAX, stress);

        // ---- 3) 厚度：洋壳随龄增厚 + 碰撞增生 ----
        double thickness = THICKNESS_OCEANIC + THICKNESS_AGE_GAIN * age
                + THICKNESS_COLLISION * kappa;

        // ---- 4) 密度：随龄冷却增密（越老越沉，S1 方向）—— 碰撞陆化降密（又厚又轻才是陆）----
        double density = DENSITY_OCEANIC + DENSITY_AGE_GAIN * age
                - DENSITY_COLLISION_DROP * kappa;
        if (density < DENSITY_CONTINENTAL) {
            density = DENSITY_CONTINENTAL;
        }

        // ---- 5) 均衡（S1 正统式）——物理层照常算，岩性层仍消费 ----
        double buoyancy = thickness * (DENSITY_MANTLE - density);
        double buoyE = (buoyancy - BUOYANCY_SEA_LEVEL) * E_PER_BUOYANCY_SEA;

        // ---- 6) eBase 导出：平台模型（默认）/ 旧均衡式（A/B 回退）----
        double eBase;
        if (OCEAN_PLATEAU_MODEL) {
            // 平台模型年龄律：t = age·TMAX，深度随年龄加深并封顶（见常量注释）
            double tMa = Math.max(0.0, age) * PLATEAU_T_SCALE;
            double deepM = AGE_D0_M + (AGE_DINF_M - AGE_D0_M)
                    * (1.0 - Math.exp(-tMa / AGE_TAU_MA));
            double e = -(deepM / METERS_PER_E);
            // 碰撞陆化：κ→1 时把洋底基准抬到陆架锚（海岸线由 morph 层决定）
            e = e + (SHELF_ANCHOR_M / METERS_PER_E - e) * kappa;

            // 深海丘陵：10~100 km 尺度的实有洋底地形（世界种子键控）
            double hs = valueNoise(wx / ABYSS_HILL_SCALE, wz / ABYSS_HILL_SCALE,
                    seed ^ SALT_HILLS)
                    + 0.5 * valueNoise(wx / (ABYSS_HILL_SCALE * 2.1),
                            wz / (ABYSS_HILL_SCALE * 2.1), seed ^ (SALT_HILLS + 7919L))
                    + 0.25 * valueNoise(wx / (ABYSS_HILL_SCALE * 4.4),
                            wz / (ABYSS_HILL_SCALE * 4.4), seed ^ (SALT_HILLS + 15811L));
            // ★ 统一纹理（2026-11-18，用户："山脊与其他地形的衔接"仍见分界线）：
            //   150/75wu 细倍频（零均值）并入同一丘陵场——<b>全洋底同一套纹理</b>
            //   （含洋中脊带内部）。此前脊带纹理由 OceanFeatures 的 blockTex/fabric
            //   提供（只带内有值）⇒ 与带外质感在 FOOT=200 处突变 = 人工分界线。
            //   振幅 ±26m（0.35×100m×±0.75）：与脊带旧 blockTex ±38m 同量级略柔。
            //   真实洋底中尺度（1.5~7.5km）崎岖本就遍布全洋底，不只是脊带。
            double fine = (valueNoise(wx / 150.0, wz / 150.0, seed ^ (SALT_HILLS + 21401L)) - 0.5)
                    + 0.5 * (valueNoise(wx / 75.0, wz / 75.0, seed ^ (SALT_HILLS + 25219L)) - 0.5);
            // ★ 阶段七 C1（2026-11-17，用户："没有被冲刷过的样子"）：
            //   沉积平滑——粗糙度随洋壳年龄衰减。真实洋底：脊轴附近（新洋壳）
            //   崎岖（火山/断裂），深海平原（老洋壳）被沉积物抹平；旧实现
            //   全洋底同振幅 ⇒ 哪里都一样糙的「塑料感」。
            //   age→0（脊轴）：全幅；age→1（深海平原）：30%。
            double sediment = 1.0 - 0.7 * smoothstep(0.15, 0.75, age);
            e += (ABYSS_HILLS_M / METERS_PER_E) * ((hs / 1.75) + 0.35 * fine) * sediment;

            // ★ 阶段七 B（2026-11-17）：<b>轴部裂谷已从本类删除</b>。
            //
            //   为什么删：地堑现在是 {@link RidgeProfile} 横剖面的<b>一部分</b>
            //   （中央 U 形谷，深 0.07e、半宽 80wu）。两处各挖一次是「双重开挖」，
            //   且旧实现用 {@code distRaw}（未平滑）配 {@code max(0,·)} 折点，
            //   被无符号距离偏移推成负值后截平 ⇒ 谷底出现平台、左右不对称
            //   （分解探针实测 ±60wu 镜像差 0.038e，超限近 2 倍）。
            //   现在单一来源：脊剖面（OceanFeatures）负责全部地形起伏，
            //   本类只负责年龄律热沉降 + 丘陵 + 海沟。

            // 海沟：汇聚边界上的深槽（双侧共用，无需俯冲极性；stress 门控成形度）
            e -= (TRENCH_M / METERS_PER_E)
                    * smoothstep(TRENCH_STRESS_LO, TRENCH_STRESS_HI, stress)
                    * Math.exp(-(ts.distRaw() * ts.distRaw()) / (TRENCH_WU * TRENCH_WU));

            eBase = e;
        } else {
            eBase = buoyE;
        }

        return new Sample(age, kappa, thickness, density, buoyancy, eBase);
    }

    /**
     * buoyancy → e 的海侧换算比例（e/浮力单位）。
     *
     * <p>标定：成熟洋盆（B≈0.017）应落在 ≈−0.40e（≈−51 格，深海平原），
     * 脊轴（B≈0.066）≈−0.21e（≈−27 格，洋脊高地）——
     * {@code (0.12−0.017)·k ≈ 0.40 ⇒ k ≈ 3.9}。与海侧 127 格/e 相乘即格数换算，
     * 不用字面量（铁律 R7）。</p>
     */
    static final double E_PER_BUOYANCY_SEA = 3.9;

    // ============================================================ 数值工具

    /** Hermite smoothstep（两端一阶导为 0，铁律 ④）——S1 同款。 */
    static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 双线性值噪声（C¹，双极性 ±1）——深海丘陵用。
     *
     * <p>实现模式与 {@link TectonicField}{@code valueNoise} 同款（生产链自有的
     * 确定性原语）；世界种子经 {@code seed ^ salt} 混入哈希键。</p>
     */
    private static double valueNoise(double x, double z, long key) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double tx = fx * fx * (3 - 2 * fx);
        double tz = fz * fz * (3 - 2 * fz);
        double v00 = unit(hash(key, ix, iz));
        double v10 = unit(hash(key, ix + 1, iz));
        double v01 = unit(hash(key, ix, iz + 1));
        double v11 = unit(hash(key, ix + 1, iz + 1));
        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return (a + (b - a) * tz) * 2.0 - 1.0;
    }

    private static long hash(long key, int x, int z) {
        long h = key ^ (Math.abs((long) x) * HILL_SEED_MIX + 0x9E37L)
                ^ (Math.abs((long) z) * 0xC2B2AE3D27D4EB4FL + 0x165667B1L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private CrustField() { }
}
