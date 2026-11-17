package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;
import java.util.function.DoubleBinaryOperator;

/**
 * 海洋特征计算器 — 在深海区域叠加地质特征（洋中脊、海山等）。
 *
 * <p>在 CellGenerator.sample() 中 eOcean 计算后、eOcean+eLand 合并前调用。
 * 只返回 delta（≥0，仅抬升海床），由调用方叠加到 eOcean 并 clamp。
 * 仅在 eOcean < 0 时生效，不触及陆地。
 *
 * <p>海山中心水深检查（v2 新增）：每个潜在海山在生成前检查中心点的真实 eOcean
 * （不含海山增量）。若中心点水深不足（eOcean > -0.20），则跳过该海山。
 *
 * <p>海山形状（去圆化重构）：改用共享 VolcanicShape 库，含域扭曲 + 各向异性 +
 * 三种剖面（锥形 / 平顶 guyot / 环状 caldera）。修复旧 48 位哈希截断导致
 * radius 恒为 70、amp 与 chance 位重叠污染的 bug（形状 / 几何改用独立 64 位哈希）。
 */
public final class OceanFeatures {

    // ===== 海山/海底火山：粗格点高斯鼓包 + 域扭曲去圆化 =====
    private final Noise seamountWarpX, seamountWarpZ; // 海山域扭曲 @1/200

    // ===== ★ 阶段七 B（2026-11-17）：脊轴分段感——沿走向的蜿蜒 + 分段高度 =====
    /**
     * 轴向高度分段（波长 1100wu）：段间脊轴高度差 ±40%——真实 MOR 的分段性。
     *
     * <p>蜿蜒偏移<b>不在这里</b>：脊带与轴部裂谷必须用<b>同一个</b>偏移函数
     * 但<b>不能</b>用「给无符号距离加偏移」的方式平移脊轴（会消失/裂双谷）——
     * 详见 {@code compute} 的脊段注释。</p>
     */
    private final Noise axSegment;
    /** 世界种子（轴向分段/海山撒点用）。 */
    private long worldSeed;
    /** 粗格点间距（块）。每个格子约 20% 概率生成一座海山。
     *  2026-08-06 调稀：用户反馈海山太多。网格保持 500（相位/深度过滤不变），概率 40%→20%
     *  → 数量线性降为原来的 52%。曾试 800/650 网格（相位漂移+深度区重合不确定）与 15%
     *  （4096 区域期望 <1 座、波动到 0）——20% 为"少一半且可见"的平衡点。 */
    private static final double SEAMOUNT_GRID = 500.0;
    /** 海山生成概率（0~65535 阈值） */
    private static final int SEAMOUNT_CHANCE = (int) (0.20 * 65536);
    /** 海山半径范围 [min, max) */
    private static final double SEAMOUNT_RADIUS_MIN = 70.0;
    private static final double SEAMOUNT_RADIUS_RANGE = 80.0; // 70~150
    /** 海山抬升幅度范围 [min, max)（e 单位） */
    private static final double SEAMOUNT_AMP_MIN = 0.08;
    private static final double SEAMOUNT_AMP_RANGE = 0.08; // 0.08~0.16
    private long seamountSeed;

    /**
     * 海山中心水深检查器。
     * 接受 (centerX, centerZ) → 该点的 eOcean（不含海山增量）。
     * 若返回的 eOcean > -0.20，海山中心不够深 -> 跳过。
     */
    private DoubleBinaryOperator seamountCenterDepthCheck;

    public OceanFeatures() {
        // ★ 2026-11-18：ridgeNoise（Ridge 1/600 + Warp 200）与 blockNoise（100wu）已删除。
        //   它们是「脊带专属纹理」——只带内有值 ⇒ 与带外的 CrustField 丘陵场
        //   在 FOOT=200 处质感突变，读成人工分界线（用户截图圈出）。
        //   （更早它们承担的「脊线蜿蜒/轴向细节」职责，蜿蜒已证否——
        //     给无符号距离加偏移会让谷消失或裂成双谷，见 compute 脊段注释。）
        //   统一纹理方案：全洋底共用 CrustField 丘陵场（已加 150/75wu 细倍频），
        //   脊增量只提供光滑海拔剖面，不再自带纹理。
        // 海山域扭曲（去圆化）：低频 Simplex @1/200，幅度由每座 radius 缩放
        this.seamountWarpX = new Frequency(new Simplex(705), 1.0 / 200.0);
        this.seamountWarpZ = new Frequency(new Simplex(706), 1.0 / 200.0);
        // ★ 阶段七 B：轴向分段（1100wu）——沿轴高度变化（蜿蜒位置偏移已证否）
        this.axSegment = new Frequency(new Simplex(708), 1.0 / 1100.0);
        // 默认：无检查（向后兼容）
        this.seamountCenterDepthCheck = null;
    }

    /**
     * 设置海山中心水深检查器。由 CellGenerator 注入。
     * @param checker (centerX, centerZ) → 该点的预海山 eOcean 值
     */
    public void setSeamountDepthChecker(DoubleBinaryOperator checker) {
        this.seamountCenterDepthCheck = checker;
    }

    /** 海洋特征计算结果（分离各分量，供 CellGenerator 分类用） */
    public static final class FeatureResult {
        public final double total;     // 总增量
        public final double ridge;     // 洋中脊增量
        public final double seamount;  // 海山增量
        /**
         * ★ 2026-09-13 Phase T6：俯冲带剖面增量（{@link TectonicField#oceanProfile} 透传），
         * <b>带符号</b>：{@code >0} = 火山弧（离轴隆起，分类时可作 {@code SEAMOUNT}）；
         * {@code <0} = 海沟窄槽（紧贴边界的深槽）。已计入 {@link #total}。
         */
        public final double arc;
        public final double baseE;     // 预特征基面 e（分类深度判定用，避免特征抬升自相矛盾）
        /**
         * ★ 阶段七 B：洋中脊<b>带成员资格</b> ∈[0,1]（与高度增量解耦）。
         *
         * <p>为什么需要：脊剖面里地堑谷底的高度增量低于脊肩（谷是下沉的），
         * 若用 {@code ridge}（高度）做类型判定，地堑会被排除在 SUBMARINE_RIDGE
         * 之外——但地堑是洋中脊的一部分。本字段只表达「在不在脊带内」。</p>
         */
        public final double ridgeEnvelope;
        public FeatureResult(double total, double ridge, double seamount, double baseE) {
            this(total, ridge, seamount, 0.0, baseE, ridge > 0 ? 1 : 0);
        }
        public FeatureResult(double total, double ridge, double seamount, double arc, double baseE) {
            this(total, ridge, seamount, arc, baseE, ridge > 0 ? 1 : 0);
        }
        public FeatureResult(double total, double ridge, double seamount, double arc,
                             double baseE, double ridgeEnvelope) {
            this.ridgeEnvelope = ridgeEnvelope;
            this.total = total;
            this.ridge = ridge;
            this.seamount = seamount;
            this.arc = arc;
            this.baseE = baseE;
        }
    }

    /** 播种所有噪声节点 + 海山种子偏移 */
    public void seed(long worldSeed) {
        Noises.seedAll(seamountWarpX, worldSeed, 0);
        Noises.seedAll(seamountWarpZ, worldSeed, 0);
        Noises.seedAll(axSegment, worldSeed, 0);
        this.worldSeed = worldSeed;
        this.seamountSeed = worldSeed + 987654321L;
    }

    /**
     * 计算海洋特征增量 delta。
     *
     * <p>★ 2026-09-13 Phase T6：洋中脊的<b>位置</b>改由构造场驱动（{@code tect}），
     * 火山弧由 {@link TectonicField#oceanProfile} 的<b>正部</b>透传。详见各段注释。</p>
     *
     * @param wx      世界 X 坐标
     * @param wz      世界 Z 坐标
     * @param eOcean  当前海洋基面 e ∈ [-1, 0]
     * @param cBiased 偏置大陆性（仅保留签名兼容，未用）
     * @param tect    构造采样结果（{@code null} 时退回"无构造版"：洋中脊无门控、无火山弧，
     *                与 T6 接入前等价，便于回滚与对照）
     * @param arcProf {@link TectonicField#oceanProfile} 的剖面值（e 单位，负=海沟窄槽 /
     *                正=火山弧），由调用方算出传入。本方法取<b>正部</b>作火山弧、
     *                <b>负部</b>作海沟<b>窄槽</b>（叠加在 T1 的宽带加深之上）—— 二者语义不同，
     *                但都只在<b>深海</b>生效，不会把海床抬出海面
     * @return FeatureResult 包含 total / ridge / seamount / arc 分量
     */
    public FeatureResult compute(double wx, double wz, double eOcean, double cBiased,
                                 TectonicField.Sample tect, double arcProf) {
        // 修复：原版 `if (eOcean >= 0) return 0` 是硬阈值，在 eOcean=0 的海岸线产生跳变
        // （火山贡献最高 0.16 e ≈ 41 blocks → 大断裂面）。改用 smoothstep 淡入，
        // 保证海陆过渡带地形连续。海岸线上 eOcean=0 → fade=0，仍无海山贡献。
        // 2026-08-06 修复：fade 语义 = "深海 1 / 近岸 0"（x 越低值越大）→ 必须 1-smoothstep(low,high,x)。
        // 原 smoothstep(0,-0.05,...) 区间反转与平滑版（无 1-）都会使 fade 恒 0，海山/洋中脊从未生成
        // （探针实测 maxSeamountAmp=0）。
        double fade = eOcean < 0 ? 1.0 - smoothstep(-0.05, 0.0, eOcean) : 0.0;
        if (fade <= 0.0) return new FeatureResult(0, 0, 0, 0, eOcean);

        double ridgeDelta = 0.0;
        double seamountDelta = 0.0;
        double arcDelta = 0.0;
        double ridgeEnvelope = 0.0;

        // 1. 洋中脊：smoothstep 平滑淡入（eOcean ≥ -0.08 无脊，≤ -0.25 全幅）
        // 2026-08-06 修复：ridgeFade 语义 = "深海 1 / 浅海 0" → 1-smoothstep(-0.25,-0.08,eOcean)。
        // 原实现（区间反转 / 无 1-）ridgeFade 恒 0，洋中脊从未生效。
        double ridgeFade = eOcean < -0.08 ? 1.0 - smoothstep(-0.25, -0.08, eOcean) : 0.0;
        if (ridgeFade > 0) {
            // ★★★ 2026-09-13 Phase T6：洋中脊门控由【随机低频掩码】改为【构造离散带】★★★
            //
            //   原实现用 ridgeMask = Simplex(1/2000) 决定洋中脊出现在哪 —— 即
            //   **随机位置**，与"洋中脊 = 离散板块边界"的地质定义无关
            //   （参考项目 worldgen 的洋中脊正是由 DIVERGENT 边界 + gaussian(dist,35) 驱动）。
            //   本次对齐：位置由构造离散强度给出，噪声退居"轴向细节"。
            //
            //   门控 = 【离散强度】× 【到边界距离的高斯包络】，二者缺一不可：
            //     · smoothPos(−stress)：· stress<0（离散）→ 1；stress>0（汇聚）→ 0
            //       （汇聚处是海沟/弧，不该有洋中脊）；f(0)=0 且一阶导连续 ⇒ 无折痕。
            //       ★ 它只是**区域尺度**（stressField 的 σ=1000）的"这片海域是离散体制"，
            //         本身不给出"脊在哪"，必须再乘距离包络。
            //     · gaussian(dist, RIDGE_BAND_SIGMA)：洋中脊是**沿边界线的带**
            //       （参考项目 worldgen 用 gaussian(dist, 35px)，本项目按 wu 标定）。
            //       ★ 教训：只乘 stress 会让"洋中脊"铺满整个离散海盆（实测 RIDGE 占比
            //         11.29%，远超 P∩D 边界带面积）—— 因为应力场是区域量、不是边界量。
            //       注：此处用 dist 做**单调包络**是安全的；被否决的是用 dist 当**噪声坐标**
            //       （会产生同心波纹，见 TectonicField.CHAIN_SCALE 的硬约束）。
            // ★ 阶段七 A（2026-11-17）：body = 构造资格 × 确定性脊翼剖面。
            //   constitution：这片海域是否离散体制（区域应力场，σ=1000wu，连续）。
            //   shape：离轴距离的高斯剖面（对称、轴部最高、两翼热沉降）。
            //   ⇒ 等深线天然平行脊轴（磁条带几何），不会是噪声闭合圈。
            // ★★ 阶段七 B 落地（2026-11-17）：脊体形改用确定性横剖面
            //    {@link RidgeProfile}（沙箱 {@code RidgeFormProbe} 目检定稿）。
            //
            //   旧实现：shape = gaussian(dist+wander, σ=200) ⇒ 单峰鼓包，
            //   且与 CrustField 的独立窄槽叠加 ⇒ 分解图实测 ridge 通道是
            //   「噪声斑块」、最终地形像「被破坏的海沟」（用户判断）。
            //
            //   新实现：RidgeProfile.height(dist+wander) 给出完整横剖面
            //   （中央 U 形地堑 + 脊肩峰 + 翼部余弦衰减），
            //   CrustField 的独立裂谷项<b>已删除</b>（剖面内含，避免双重开挖）。
            // ★★ 阶段七 B 落地（2026-11-17）：脊体形改用确定性横剖面 RidgeProfile
            //   （沙箱 RidgeFormProbe 定稿：中央 U 形地堑 + 脊肩峰 + 翼部余弦衰减）。
            //   取代旧的 gaussian 鼓包；CrustField 的独立裂谷项已删除（剖面内含）。
            double ridgeOut = 0.0;
            if (tect != null) {
                // ★★ 2026-11-17 关键 bug 修复（两次）：
                //   ① smoothPos(v, eps) 的定义是 `v × smoothstep(...)` —— 它返回的是
                //      <b>应力值本身</b>（典型 0.1~0.5），不是 0~1 的资格系数！
                //      之前把它当门控乘进脊高 ⇒ 整条脊被乘了 0.1~0.5，
                //      实测脊肩只有 0.078e（设计 0.19e）。
                //   ② 门槛从 0.12 收到 <b>0.30</b>（RidgeStressProbe 实测：边界上
                //      stress 是连续分布——p25=−0.36、p50=−0.04，只有 28% 的边界
                //      是明确离散型 stress<−0.30）。旧门槛 0.12 会把 ~55% 的边界
                //      当成离散 ⇒ <b>汇聚边界上也在长「洋中脊」</b>（用户："像海沟"）。
                //
                //   地质分工：离散边界 = 造山（洋中脊）；汇聚边界 = 挖沟（海沟/火山弧）；
                //   中间 = 走滑（转换断层）。这里只管离散侧。
                double constitution = smoothstep(0.18, 0.42, -tect.stress());
                if (constitution > 0.0) {
                    double along = tect.alongCoord();
                    // ★★ 必须用 distRaw（未平滑）：
                    //   生产 dist 经 blurDist(R=150wu) 环形平均；地堑半宽仅 80wu
                    //   ⇒ 用 dist 会把谷底完全抹平（实测剖面谷深只剩 0.029e）。
                    //
                    // ★★ 2026-11-17 关键修正：<b>不再给 distRaw 加位置偏移</b>。
                    //   distRaw = max(0, (d2−d1)/2) 是<b>无符号</b>距离（两侧都是
                    //   d2≥d1），在它上面加 wander 在数学上不是「平移零线」：
                    //     · wander>0 ⇒ d ≥ 90 > RIFT_HALF ⇒ 地堑整段<b>消失</b>
                    //     · wander<0 ⇒ d=0 出现在离边界 90wu 处、且在两侧对称
                    //       ⇒ 地堑<b>裂成两条</b>
                    //   实测特写图上表现为轴部成片的暗斑/断口（「被破坏」感）。
                    //   要真正平移脊轴需要<b>有符号</b>距离，TectonicField 不提供。
                    //   故此处只用真实距离；沿轴变化交给 segment（±35%）/串珠/谷深分段。
                    double d = tect.distRaw();
                    // ★ 地堑深度沿轴分段（2026-11-18）：部分段谷深趋零（无谷段）
                    //   ——真实 MOR 的轴谷深度沿轴强烈变化，有的段几乎无谷。
                    //   没有它，中央暗线就是一条贯通的直线（用户圈出的「平滑曲线」）。
                    double valleyScale = 0.55 + 0.45 * smoothstep(-0.6, 0.6,
                            axSegment.compute(along * 1.4 + 21.4, 0.0));
                    double profile = RidgeProfile.height(d, valleyScale);   // e，相对深海平原
                    if (profile > 0.0) {
                        // 轴向高度分段：段间 ±35%（真实 MOR segment 尺度 800~1500wu）
                        double segment = 0.78 + 0.45 * axSegment.compute(along, 0.0);
                        // ★ 轴向串珠（2026-11-18 v2）：500wu 波长肩高调制 ±15%——
                        //   沿轴<b>连续</b>缓起伏（不断成碎段）。
                        double beads = 0.925 + 0.15 * axSegment.compute(along * 2.2 + 9.7, 0.0);
                        // ★★ 2026-11-18 统一纹理（用户："山脊与其他地形的衔接"仍见分界线）：
                        //   fabric/breakUp/rough 与 blockTex <b>全部删除</b>。它们是
                        //   「脊带专属纹理」——只带内有值、带外恒 0，与 CrustField
                        //   丘陵场在 FOOT=200 处质感突变 ⇒ 俯视图上脊带边缘是
                        //   一条人工分界线（海拔 C¹ 也救不了质感跳变）。
                        //   现在脊增量 = 纯光滑海拔剖面（profile×segment×beads），
                        //   纹理全部由 CrustField 丘陵场统一提供（全洋底同一套）。
                        // ★★ 2026-11-17 修「点状虚线折痕」（用户截图圈出）：
                        //   旧实现 scale = min(1, maxAdd/add) 是<b>硬钳制</b>——
                        //   add == maxAdd 的等值线是一条绕脊肩的曲线，线上下
                        //   坡度突变（一侧削平台地、一侧正常斜坡）⇒ hillshade
                        //   放大后成一圈圈<b>点状虚线</b>，且被噪声调制碎成点链。
                        //   这违反项目铁律（本类弧光项注释原话：「用 smoothMin
                        //   而非 Math.min：后者一阶不连续会留折痕」——同一段代码
                        //   里海沟项早就用了 smoothMin，我却给脊项用了 Math.min）。
                        //   现改为 C¹ 软钳制 smoothMin（宽 0.04e ≈ 5 格）。
                        double add = profile * segment * beads;
                        double maxAdd = Math.max(0.0, -eOcean - RIDGE_SEA_MARGIN);
                        double capped = add > 0
                                ? TectonicField.smoothMin(add, maxAdd, 0.04) : add;
                        ridgeOut = capped * constitution;
                        // 类型包络：脊带成员资格（谷底也算——地堑是洋中脊的一部分）。
                        //   用 distRaw 判定：d < FOOT 全带。
                        ridgeEnvelope = constitution * (1.0 - smoothstep(
                                RidgeProfile.FOOT * 0.75, RidgeProfile.FOOT, d));
                    }
                }
            }
            ridgeDelta = ridgeOut * ridgeFade;
        }

        // 2. 海山/海底火山（域扭曲去圆化 + 共享 VolcanicShape 形状）
        seamountDelta = seamountCompute(wx, wz);

        // 3. ★ Phase T6：俯冲带剖面（火山弧 + 海沟窄槽）
        //   剖面值 arcProf 由 TectonicField.oceanProfile 给出：
        //     正部 = 离轴火山弧（dist≈210）  负部 = 紧贴边界的海沟窄槽（dist≈0）
        //   二者语义不同，但共用同一"深海门控" arcFade，理由见下。
        //
        //   ★ 为何用【独立的 arcFade】而非上面那个 fade：
        //     fade = 1−smoothstep(−0.05, 0, eOcean) 的语义是"只要 eOcean<0 就几乎满值"
        //     —— 浅海（eOcean≈−0.05）也拿 ~0.6。这对**海山**是设计意图（浅海也有海底丘），
        //     但把 +0.10 e 的弧放在浅海上会**抬出水面造岛**（本项目的海陆边界由类型权重
        //     竞争决定，一旦弧把某个 OCEAN 权重点的 e 推过 0，就会长出一块**非预期小岛**）。
        //     故弧/槽用"深海才满值"的 arcFade。
        //
        //   ★ 门槛取值有实测依据（初版设 −0.30~−0.12 是错的）：
        //     剖线实测（OceanTypeProbe 的 [PROFILE]）显示【汇聚边界处的 eOcean 典型值 ≈ −0.20】，
        //     而初版渐变带中心恰是 −0.21 ⇒ 典型汇聚点被压到 **0.39 倍**
        //     （实测弧峰仅 0.0075 e ≈ 1.4 块、海沟仅 −0.0058 e）—— 门控把该服务的位置压死了。
        //     现值 −0.16~−0.10：−0.20 处 arcFade=1（满值），−0.15 处 ≈0.93，−0.10 以上为 0。
        //
        //   ★ 安全性证明（为何仍绝不露头）：满值区 eOcean ≤ −0.16，弧峰 0.10
        //     ⇒ 最高 −0.06 < 0；渐变区 arcFade=k 时 eOcean ∈[−0.16,−0.10]，
        //     叠加 ≤ k×0.10 ⇒ 最高 ≤ −0.16+k×0.16−… 恒 < 0。故**数学上不可能抬出海平面**。
        double arcFade = eOcean < -0.10 ? 1.0 - smoothstep(-0.16, -0.10, eOcean) : 0.0;
        if (arcFade > 0.0) {
            double v = arcProf * arcFade;
            // ★ 软天花板：限制弧相对【基面 eOcean】的抬升量（防浅海露头）
            //     arc ≤ cap = (−eOcean) − MARGIN
            //   深海（|eOcean|≈0.35）cap ≫ 弧幅(0.10) ⇒ **完全不生效**，弧形态不变；
            //   浅海/近岸（cap 小）才压缩 —— 那里 arcFade 本就≈0，此限是**双保险**。
            //   用 smoothMin 而非 Math.min：后者一阶不连续会留折痕（项目铁律）。
            //
            //   ★ 诚实记录（实测，勿误信为"已杜绝造岛"）：本限**只约束弧相对基面的量**，
            //     无法约束 eLand。探针差分归因实测仍有 **1 例**（602 弧作用点中）出现
            //     "无弧为海、有弧为陆" —— 该点 eOcean=−0.250（深海，门控通过）但
            //     **eLand=−0.0033**（类型场与 c 场不一致，陆形场几乎恰在海平面），
            //     此时任何正值海床特征（含既有的**洋中脊**，同类 21 例）都会把它抬出水面。
            //     ⇒ 这是**本架构的既有特性**（正值特征 × oceanW 软加权于 eLand≈0 处），
            //       **非 T6 引入**；弧（1 例）实际比洋中脊（21 例）还轻。
            //     彻底消除需改"海陆判定由类型场与 c 场双场决定"这一用户既定架构，超出本次范围。
            if (v > 0.0) {
                double cap = -eOcean - ARC_SEA_MARGIN;
                arcDelta = cap <= 0.0 ? 0.0 : TectonicField.smoothMin(v, cap, ARC_SOFT_SAT);
            } else {
                arcDelta = v;      // 负部 = 海沟窄槽，向下不会露头，无需限制
            }
        }

        double total = (ridgeDelta + seamountDelta) * fade + arcDelta;
        return new FeatureResult(total, ridgeDelta * fade, seamountDelta * fade,
                                 arcDelta, eOcean, ridgeEnvelope * fade);
    }

    /**
     * 洋中脊幅度上限（e 单位）——<b>轴部额外凸起</b>，≈19 格（0.15e）。
     *
     * <p>⚠ 2026-11-17 三次定标：0.05（6 格）→「山脊没一点痕迹」；0.09（11 格）
     * 在特写图上仍看不出（自动选点探针实测：全图<b>没有一段</b>「两侧深海夹一脊」
     * ——洋中脊全部落在大陆坡斜坡上，脊梁被斜坡掩盖 ⇒ 剖面是单调上升的斜坡）。
     * 0.15e 使脊梁在斜坡上也明显；真实 MOR 轴部 vs 深海平原 ≈ 2500m ≈ 0.25e
     * （其中 0.08e 由年龄律提供，ridge 项负责剩余部分）。
     * 实际幅度还受<b>浅水余量</b>自适应压缩（见 compute 的 amp 计算），
     * 不会把轴部顶穿水面。</p>
     *
     * <p>⚠ 2026-11-17 二次定标：先取 0.05（"轴部凸起 500m"）——实测特写图上
     * 脊轴与两侧只有 ~6 格落差、8 级色带不换色、光照算不出坡 ⇒ 用户反馈
     * 「明显看不到，山脊都没一点痕迹」。回 0.09 + 收窄 σ（320→200）做陡坡，
     * 配合探针光照的视觉放大，脊梁在图上与实机都能看出来。</p>
     *
     * <p>⚠ 定标修正（2026-11-17 阶段七 A）：旧值 0.18 是「脊轴相对深海平原的
     * <b>总</b>抬升」，但<b>年龄律已经提供这部分</b>（CrustField 平台模型
     * D0=2500m → D∞=5700m：age=0 的洋壳本身就是最浅的）。归位到洋底层后
     * 再叠 0.18 等于重复叠加：实测轴部 eBase(−0.225) + ridge(0.19) = −0.034，
     * 直接顶穿浅水锚（−7 格）并叠上海床细节 ⇒ 轴部离水面只剩 3.4 格。
     * 现在只保留「轴部高地/脊肩」的额外起伏（~500m），总脊带落差由年龄律
     * 提供（深海 −0.40 → 轴部 −0.175 ≈ 24 格水下，视觉上仍是明显的宽脊）。</p>
     */
    private static final double RIDGE_AMPLITUDE = 0.15;
    /**
     * 洋中脊带的半宽（wu）：{@code gaussian(dist, σ)} 的 σ。
     *
     * <p>取 130wu ≈ 3.5 块 ≈ 中心带 ~20 块宽。参考项目用 35px（其世界 ~2048 宽 ⇒
     * 约 1.7% 世界宽）；本项目板块间距 2000wu ⇒ 同比例约 35~100wu。
     * 130 稍宽是因为本项目的 {@code dist} 已被 {@code blurDist} 平滑过
     * （折痕被抹圆 ⇒ 有效带宽略涨）。</p>
     */
    private static final double RIDGE_BAND_SIGMA = 130.0;

    /**
     * ★ 阶段七 A（2026-11-17）：洋中脊脊翼半宽（wu）——高斯剖面的 σ。
     *
     * <p>用户观察：「你这个洋山脊好像是单噪声山脊？」——旧实现把体形交给
     * {@code Ridge(1/600)} 单频噪声（等值线是噪声网络：闭合圈、分叉），
     * 位置虽是构造的，形态却是「噪声鼓包」。真实洋中脊是<b>离轴距离的
     * 确定性剖面</b>：轴部最高、两翼对称热沉降（等深线天然平行脊轴），
     * 噪声只应负责表面粗糙度。</p>
     *
     * <p>尺度：320wu 半宽 ⇒ 脊翼总宽 ~1280wu（板块间距 2000+wu 的 64%），
     * 轴部 0.09e≈11 格、200wu 处 61%、400wu 处 14%、远处并入热沉降。
     * σ 从 320 收到 200：更陡的轴部高地（真实 MOR 的 axial high 比整个
     * 热沉降翼窄得多）。</p>
     */
    private static final double RIDGE_WING_SIGMA = 200.0;
    /** 脊轴到海平面的最小余量（e）：自适应幅度的下限保护（≈7.6 格）。 */
    private static final double RIDGE_SEA_MARGIN = 0.06;

    // ===== ★ 2026-09-13 Phase T6：火山弧"永不露头"的软天花板 =====
    /** 弧顶与海平面之间的最小余量（e 单位，≈3.8 块）。 */
    private static final double ARC_SEA_MARGIN = 0.02;
    /** 软饱和的过渡宽度（e 单位）：在此宽度内平滑逼近天花板（一阶连续，无折痕）。 */
    private static final double ARC_SOFT_SAT = 0.03;

    // （smoothMin 已上移到 TectonicField 作为公共工具，供地面护栏等复用，
    //   避免两处各写一份 —— 见 TectonicField.smoothMin 的注释。）

    /**
     * 海山场：粗格点确定性鼓包，域扭曲去圆化 + 各向异性 + 三剖面。
     * 搜索邻域 3×3 格子叠加贡献；海山中心深水检查（仅深水中心才生成）。
     */
    private double seamountCompute(double wx, double wz) {
        long cx = (long) Math.floor(wx / SEAMOUNT_GRID);
        long cz = (long) Math.floor(wz / SEAMOUNT_GRID);

        double total = 0.0;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dz = -1; dz <= 1; dz++) {
                long h = hashCell(cx + dx, cz + dz);
                if ((h & 0xFFFF) >= SEAMOUNT_CHANCE) continue;
                double centerX = cellCenter(cx + dx, (h >>> 16) & 0xFFFF);
                double centerZ = cellCenter(cz + dz, (h >>> 32) & 0xFFFF);

                // 海山中心水深检查：只有深水中心才允许生成
                if (seamountCenterDepthCheck != null) {
                    double eOceanAtCenter = seamountCenterDepthCheck.applyAsDouble(centerX, centerZ);
                    if (eOceanAtCenter > -0.20) continue;
                }

                // 形状 / 几何：独立 64 位哈希（修复旧 48 位截断 radius 恒 70 的 bug）
                long hg = hashCell64(cx + dx, cz + dz, seamountSeed + 9001L);
                int shapeType = (int) (hg & 0x3) % 3; // 0 CONE, 1 GUYOT, 2 CALDERA
                double ang = ((hg >>> 2) & 0x3FF) / 1024.0 * Math.PI;
                double asx = 0.6 + ((hg >>> 12) & 0xFF) / 255.0 * 0.8;
                double asz = 0.6 + ((hg >>> 20) & 0xFF) / 255.0 * 0.8;
                double radius = SEAMOUNT_RADIUS_MIN + ((hg >>> 28) & 0xFFFF) / 65536.0 * SEAMOUNT_RADIUS_RANGE;
                double amp = SEAMOUNT_AMP_MIN + ((hg >>> 44) & 0xFF) / 255.0 * SEAMOUNT_AMP_RANGE;

                // 域扭曲（幅度正比 radius，频率固定 1/200 防 Jacobian 折叠）
                double wamp = radius * 0.4;
                double wx2 = wx + seamountWarpX.compute(wx, wz) * wamp;
                double wz2 = wz + seamountWarpZ.compute(wx, wz) * wamp;
                double[] local = VolcanicShape.anisoRotate(wx2 - centerX, wz2 - centerZ, ang, asx, asz);
                double d = Math.hypot(local[0], local[1]) / radius;
                if (d >= 1.0) continue;
                double contrib = VolcanicShape.profile(shapeType, d, amp); // caldera 已 clamp≥0
                if (contrib > 0.0) total += contrib;
            }
        }
        return total;
    }

    /** 格子内中心位置抖动（±30% 格子范围） */
    private static double cellCenter(long cell, long bits) {
        return cell * SEAMOUNT_GRID + (bits / 65536.0 - 0.5) * SEAMOUNT_GRID * 0.6;
    }

    /** smoothstep 平滑过渡：x 在 [edge0, edge1] 间做 Hermite 插值 */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = x <= edge0 ? 0.0 : (x >= edge1 ? 1.0 : (x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    /** 确定性哈希（48 位，混合世界种子；仅用于 chance/center，行为保持旧分布） */
    private long hashCell(long cx, long cz) {
        long h = seamountSeed + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h & 0x0000FFFFFFFFFFFFL;
    }

    /** 确定性 64 位哈希（独立 salt，供形状 / 几何，无截断） */
    private static long hashCell64(long cx, long cz, long salt) {
        long h = salt + cx * 374761393L + cz * 668265263L;
        h = h ^ (h >>> 13);
        h = h * 1274126177L;
        h = h ^ (h >>> 16);
        return h;
    }
}
