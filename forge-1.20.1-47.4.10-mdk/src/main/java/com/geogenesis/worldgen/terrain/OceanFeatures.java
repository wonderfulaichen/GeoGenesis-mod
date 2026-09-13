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

    // ===== 洋中脊：构造离散带门控 + Ridge(1/600)/Warp(200) 轴向细节 =====
    private final Noise ridgeNoise;

    // ===== 海山/海底火山：粗格点高斯鼓包 + 域扭曲去圆化 =====
    private final Noise seamountWarpX, seamountWarpZ; // 海山域扭曲 @1/200
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
        // 洋中脊脊线：Ridge(Simplex, 1/600, p=1.0) + Warp(200) 蜿蜒。
        // ★ 2026-09-13 Phase T6：**位置**（在哪）改由构造离散带门控（见 compute 的
        //   ridgeGate），本噪声只负责**轴向细节**（脊线蜿蜒 / 轴谷起伏）。
        //   ⇒ 原本的"低频掩码"（Simplex 1/2000）已被构造门控取代并删除：
        //     随机掩码会让洋中脊出现在任意海底，与"洋中脊 = 离散板块边界"的地质定义
        //     矛盾（参考项目 worldgen 的洋中脊正是由 DIVERGENT 边界驱动）。
        Noise simplex = new Simplex(701);
        Noise freq = new Frequency(simplex, 1.0 / 600.0);
        Noise ridge = new Ridge(freq, 1.0);
        // Warp 200 块使中脊蜿蜒
        Noise warpX = new Frequency(new Simplex(702), 1.0 / 300.0);
        Noise warpZ = new Frequency(new Simplex(703), 1.0 / 300.0);
        this.ridgeNoise = new Warp(ridge, warpX, warpZ, 200.0);
        // 海山域扭曲（去圆化）：低频 Simplex @1/200，幅度由每座 radius 缩放
        this.seamountWarpX = new Frequency(new Simplex(705), 1.0 / 200.0);
        this.seamountWarpZ = new Frequency(new Simplex(706), 1.0 / 200.0);
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
        public FeatureResult(double total, double ridge, double seamount, double baseE) {
            this(total, ridge, seamount, 0.0, baseE);
        }
        public FeatureResult(double total, double ridge, double seamount, double arc, double baseE) {
            this.total = total;
            this.ridge = ridge;
            this.seamount = seamount;
            this.arc = arc;
            this.baseE = baseE;
        }
    }

    /** 播种所有噪声节点 + 海山种子偏移 */
    public void seed(long worldSeed) {
        Noises.seedAll(ridgeNoise, worldSeed, 0);
        Noises.seedAll(seamountWarpX, worldSeed, 0);
        Noises.seedAll(seamountWarpZ, worldSeed, 0);
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
            double gate = 1.0;
            if (tect != null) {
                gate = TectonicField.smoothPos(-tect.stress(), TectonicField.STRESS_POS_EPS)
                     * Math.exp(-(tect.dist() * tect.dist())
                                / (2.0 * RIDGE_BAND_SIGMA * RIDGE_BAND_SIGMA));
            }
            if (gate > 0.0) {
                // 轴向细节：Ridge(1/600)+Warp(200)，提供脊线蜿蜒与轴谷起伏（[0,1]）
                double detail = ridgeNoise.compute(wx, wz);
                ridgeDelta = RIDGE_AMPLITUDE * gate * detail * ridgeFade;
            }
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
                                 arcDelta, eOcean);
    }

    /**
     * 洋中脊幅度（e 单位）。
     *
     * <p>原为硬编码 {@code 0.18}，语义上它正是"深海脊轴相对深海平原的抬升"
     * （真实大洋中脊轴部水深 ~2500m vs 深海平原 ~5000m ⇒ 约 +2500m ≈ +0.18 e）。
     * 提取为常量以便门控改造后仍保留原标定（本次<b>不改幅度</b>，只改位置来源）。</p>
     */
    private static final double RIDGE_AMPLITUDE = 0.18;
    /**
     * 洋中脊带的半宽（wu）：{@code gaussian(dist, σ)} 的 σ。
     *
     * <p>取 130wu ≈ 3.5 块 ≈ 中心带 ~20 块宽。参考项目用 35px（其世界 ~2048 宽 ⇒
     * 约 1.7% 世界宽）；本项目板块间距 2000wu ⇒ 同比例约 35~100wu。
     * 130 稍宽是因为本项目的 {@code dist} 已被 {@code blurDist} 平滑过
     * （折痕被抹圆 ⇒ 有效带宽略涨）。</p>
     */
    private static final double RIDGE_BAND_SIGMA = 130.0;

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
