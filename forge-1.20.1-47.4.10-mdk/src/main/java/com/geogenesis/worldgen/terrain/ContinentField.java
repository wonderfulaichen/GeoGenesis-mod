package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 大陆场生成器：大陆性 c ∈ [-1,1]。
 *
 * <h3>范式（2026-11-15 阶段三）：FBM → 结构核</h3>
 * <p>旧实现是纯 FBM 值叠加，海岸线 = 噪声过零线，天然圆胖（用户 2026-11-14 实测反馈
 * 「大陆偏圆」）。新实现把形状交给<b>几何结构</b>，借鉴参考项目 planet-simulation
 * 的「大陆 = 球冠并集」思想并做无限世界改造（<b>只借鉴算法思想，不复制代码</b>）：</p>
 * <ol>
 *   <li><b>椭圆核晶格</b>：间距 {@value #CORE_SPACING}wu（@scale4000）一格一核，
 *       半径均匀、拉伸 1.0~1.8（面积守恒 ax=r·st, az=r/st）、朝向均匀——
 *       没有真实大陆是圆的；纯圆核取 max 并集只会得到「煎饼串」。</li>
 *   <li><b>地体增置</b>：每核 3~5 个凸出小椭圆（径向/切向 50/50、拉伸 ≤2.4、
 *       中心 0.55~1.45 半轴）——印度/阿拉伯/堪察加（径向长条）、日本弧/意大利
 *       （切向长条，两条切向半岛之间夹出孟加拉湾式凹湾）。</li>
 *   <li><b>大陆群 + 群间海道</b>：3×3 核格成群，异群核交接处切出海道
 *       （直布罗陀/马六甲式），群边界扭曲防止海道沿直线网格出现。</li>
 *   <li><b>四层域扭曲（位移式）</b>：层 1050/360/120/36wu，位移/波长 ≈0.26~0.29
 *       自相似。<b>关键发现（沙箱第九/十轮）：位移式扭曲保梯度结构，叠加式
 *       噪声会把陆架打碎成上千组件</b>——只移动采样点，不往场里加值。</li>
 * </ol>
 *
 * <h3>壳值 → c 的锚映射</h3>
 * <p>核并集产出「壳值」（椭圆冠：中心 1、边缘 0、外负）。用全域标定的 5 锚
 * {@code {-2.001, -0.171, -0.107, 0.014, 0.965}}（分位 0/0.566/0.601/0.661/0.999）
 * 做 4 段 smoothstep 映射到 [0,1]（权重 0.331/0.069/0.044/0.556），再线性映射 c=2·v−1。
 * 分位语义：0.566=深/坡界、0.601=坡/架界、0.661=岸线。权重端点对齐
 * {@code MorphOceanAssembler} 三段剖面控制点：深/坡界 → edge 0.331
 * （PROVINCE_SHELF）、岸线 → edge 0.444（PROVINCE_SHORE，= 水面）
 * ⇒ 水面分位 1−0.661 ⇒ 全球陆地占比 0.339（地球口径）。</p>
 *
 * <p><b>锚沿用依据</b>：全部绝对 wu 常量按同一因子 k=continentScale/4000 缩放
 * （基准值 = 沙箱 ×1.5，大陆体量与旧 FBM 相当），比值型常量不动 ⇒ 核半径/间距/
 * 扭曲层/占据场同比例 ⇒ 壳值分布与沙箱标定态一致。{@code ContinentAnchorProbe}
 * 对拍分位验证（偏差 &gt;0.01 才需离线重标）。</p>
 *
 * <h3>开关</h3>
 * <p>{@code geogenesis.continentCores}（默认开）：false = 旧 FBM 路径（A/B 与回退，
 * 可精确复现 schema132 基准）。自查（开关四铁律）：① 全仓仅本一处读取；② 单属性
 * 单语义；③ 注释与代码同源；④ 产出变更已升 CACHE_SCHEMA_VERSION（132→133）。</p>
 *
 * <h3>线程模型与缓存</h3>
 * <p>核参数提取 {@code cellOf} 是 (seed,cx,cz) 的纯函数 ⇒ 直接映射并发缓存
 * （{@link ConcurrentHashMap}，容量上限清理——纯函数可安全重算）。扭曲/椭圆投影
 * 逐点纯计算。每 sampler 一份实例 ⇒ 缓存天然隔离（CellGenerator 与
 * MorphTerrainSampler 各持一份，同构造路径同种子逐位一致）。</p>
 *
 * <p>坐标单位 = wu（与 {@link MorphOceanAssembler} 消费端同口径）。</p>
 */
public final class ContinentField {

    // ---- 开关（阶段三；false = 旧 FBM 回退）----
    private static final boolean CONTINENT_CORES =
            !"false".equals(System.getProperty("geogenesis.continentCores"));

    // ---- 形状常量（基准值 @ continentScale=4000；运行时 × k = scale/4000）----
    /** 核晶格间距（wu）。@scale4000： occ 波长 5000 内放 2~3 核成大陆块。 */
    private static final double CORE_SPACING = 2100.0;
    /**
     * 主核半径范围（wu）。★ 2026-11-15 用户裁定 ×1.5（原 630~1875）：核/间距比
     * 0.596 时相邻核陆区不连绵（jitter ±0.48 格拉开）⇒「均匀小岛网」（已否决）。
     * 0.895 ≈ 参考项目球冠并集的高重叠结构 ⇒ 多核叠合成连绵大陆块。
     */
    private static final double CORE_R_MIN = 945.0;
    private static final double CORE_R_MAX = 2810.0;
    /** 主核拉伸上限：st = 1 + u·0.8（面积守恒）。 */
    private static final double CORE_STRETCH_SPAN = 0.8;
    /** 主核半轴下限（wu）：陆架带 Δv≈0.09 ⇒ 带宽 ≈ 0.09×半轴（同 ×1.5 缩放）。 */
    private static final double CORE_MIN_SEMI = 1125.0;
    /** 核心格内抖动比例（0.5 会越格破坏晶格骨架）。 */
    private static final double CORE_JITTER = 0.48;
    /**
     * 占据场波长（wu）：低频「大陆性」控制核的存在 ⇒ 成群大陆与洋区。
     * <p>★ 2026-11-15 用户裁定修正：大陆块尺度要对齐旧 FBM（2000~4000wu 连绵），
     * 沙箱比例（OCC/间距=6.43 ⇒ 波长 13500、聚集区同尺度）出的「均匀小岛网」
     * 太小太散。生产改为 OCC 波长 5000 ⇒ 大陆块 = occ 高值区内的核连绵块
     * （2500~5000wu），洋区 = occ 低值缺核区（连绵成片）。</p>
     */
    private static final double OCC_SCALE = 5000.0;
    /**
     * 占据门限：低于此的格无核（洋区）。★ 0.54（P(缺核)≈55%）：陆块内覆盖率
     * ≈0.339/(1−0.55)=0.75 ⇒ occ 高值区内核连绵成块（FBM 式大陆）、低值区成片大洋。
     * 演进：沙箱 0.10（只切 0.24% 深尾 ⇒ 均匀小岛网，用户否决）→ 0.42（31.7%，
     * 陆块内覆盖 0.50 仍偏碎）→ 0.54。
     */
    private static final double OCC_THRESHOLD = 0.54;
    /** 大陆群边长（核格）：3×3 格 = 一群。 */
    private static final int GROUP_CELLS = 3;
    /** 群边界扭曲：波长 2.6 格 / 幅度 0.9 格（无扭曲则海道沿直线网格出现）。 */
    private static final double GROUP_WARP_SCALE = 2.6;
    private static final double GROUP_WARP_AMP = 0.9;
    /** 每核地体数 3 + floor(u·3) → 3~5。 */
    private static final int TERR_BASE = 3;
    private static final int TERR_SPAN = 3;
    /** 地体半径 = 主核均值半径 × [0.22, 0.55]。 */
    private static final double TERR_R_FRAC_MIN = 0.22;
    private static final double TERR_R_FRAC_SPAN = 0.33;
    /** 地体中心沿主核椭圆半径的位置 × [0.55, 1.45]（&gt;1 = 凸出成半岛）。 */
    private static final double TERR_POS_MIN = 0.55;
    private static final double TERR_POS_SPAN = 0.90;
    /** 地体拉伸：stt = 1 + u·1.4（≤2.4；地球半岛 2~5，保守取值）。 */
    private static final double TERR_STRETCH_SPAN = 1.4;
    /** 地体半轴下限（wu）：同主核梯度论证（随核半径 ×1.5 缩放）。 */
    private static final double TERR_MIN_SEMI = 675.0;
    /** 地体长轴方向抖动 ±0.3rad。 */
    private static final double TERR_ANG_JITTER = 0.30;
    /** 核扫描半径（格）：抖动 0.48 + 最大长半轴 2810×2.4=6744wu ≈ 3.2 格 ⇒ 4。 */
    private static final int CORE_SEARCH = 4;
    // 群间海道（壳值单位）：参考 chanDepth=0.85 覆盖整个坡带 ⇒ 0.60；
    // chanW 参考 0.16 只铺 ~80wu ⇒ 0.30 铺 ~200wu。
    private static final double CHAN_DEPTH = 0.60;
    private static final double CHAN_W = 0.30;
    /** 海道门控（对 best 的 smoothstep 窗）：替代参考的硬阈值（防整条海道跳变）。 */
    private static final double CHAN_GATE_LO = -0.30;
    private static final double CHAN_GATE_HI = 0.10;
    // 四层域扭曲：层波长/幅度（位移/波长 ≈0.26~0.29 自相似 ⇒ 海岸分形）。
    private static final double[] WARP_SCALE = {1050.0, 360.0, 120.0, 36.0};
    private static final double[] WARP_AMP = {270.0, 105.0, 33.0, 10.5};
    /** Z 层输入偏移（与 X 去相关）。 */
    private static final double[][] WARP_Z_OFFSET = {
            {13.7, 29.1}, {7.3, 17.9}, {3.1, 8.7}, {1.9, 5.3}};
    /** 岸线微细线：幅度 0.03 / 波长 9wu（壳值上极小的亚格起伏）。 */
    private static final double FINE_AMP = 0.03;
    private static final double FINE_SCALE = 9.0;
    // 壳值锚（2026-11-15 第四次全域重标：门限 0.54 ⇒ 缺核 55%，核稀疏化分布左移）：
    // 分位语义 0/0.566/0.601/0.661/0.999 = 深/坡/架/岸线/极值，标定工具
    // ContinentAnchorProbe（A3 仍需按陆占比校准微调）。
    private static final double A0 = -2.018, A1 = -0.240, A2 = -0.149, A3 = -0.041, A4 = 0.966;
    // 权重 = crust01 在锚端点的目标值之差（对齐 MorphOceanAssembler 三段剖面）：
    //   A1（深/坡界）→ 0.331 = PROVINCE_SHELF（深海过渡起点）
    //   A3（岸线）  → 0.444 = PROVINCE_SHORE（水面 = 岸段起点）
    //   理想水面分位 = 1−0.661（A3 的壳分位），但剖面叠加 ocean/shallowDetail
    //   噪声后实际水面偏深 ~2% ⇒ A3 在 0.661 分位锚（0.014）基础上左移校准
    //   （实测 0.3184@A3=0.014 → 校准后 ≈0.339；ContinentAnchorProbe 守护）。
    //   中段端点 0.40 把 A2（坡/架界）放在陆架带内。Σ权重 = 1.0。
    private static final double W_DEEP = 0.331, W_SLOPE = 0.069, W_SHELF = 0.044, W_LAND = 0.556;

    // ---- 种子盐（避开 FBM 7000+i·97、warp 7100-7103、气候 501-504）----
    private static final long SALT_CORE = 0x5A17B3C6D9E24F08L;
    private static final long SALT_OCC = 0x2E9C41A7B6D35F82L;
    private static final long SALT_GRP = 0x7C3D9E25A481B6F0L;
    private static final long SALT_TERR = 0x91B6F24D8C3A57E0L;
    private static final long SALT_WX = 0x63A8D15E9B27C4F3L;
    private static final long SALT_WZ = 0x48D2B79A5E13C6F8L;
    private static final long SALT_FINE = 0x35E8C1D94A76B2F5L;

    /** 核缓存上限（条目）；超出即清空——纯函数，重算零风险。 */
    private static final int CACHE_MAX = 65536;
    /** 占据格哨兵（缓存负结果——占据判断本身也要缓存才省 hash）。 */
    private static final Cell EMPTY_CELL = new Cell(null, new Core[0]);

    /** 旧 FBM 路径（开关关闭时的回退；null = 核链生效）。 */
    private final Noise fbmRoot;
    /** 尺度因子 k = continentScale/4000（配置联动；P1 冻结 scale=4000 ⇒ k=1）。 */
    private final double k;
    private volatile long worldSeed;
    private final ConcurrentHashMap<Long, Cell> cellCache = new ConcurrentHashMap<>();

    /** 单个椭圆（主核或地体）：中心 + 半轴 + 长轴方向 + 所属大陆群。 */
    private record Core(double sx, double sz, double ax, double az, double cos, double sin,
                        int group) { }

    /** 一个晶格格的产物：主核（可空 = 未占据）+ 地体数组。 */
    private record Cell(Core main, Core[] terranes) { }

    /** 构建核链参数（核链与 FBM 回退共用此构造器；形状常量随 scale 联动缩放）。 */
    public ContinentField(TerrainParams p) {
        this(p, !CONTINENT_CORES);
    }

    /**
     * 诊断专用显式路径构造器：{@code legacyFbm=true} 强制旧 FBM（A/B 对比图用）。
     * 生产代码必须走单参构造器（读 {@code geogenesis.continentCores} 开关，
     * 保持单读取点）；本构造器不是第二个开关读取点——它绕过开关，仅供探针。
     */
    ContinentField(TerrainParams p, boolean legacyFbm) {
        this.k = p.continentScale() / 4000.0;
        this.fbmRoot = legacyFbm ? buildFbmRoot(p) : null;
    }

    /** 旧 FBM 路径（阶段二及之前的实现，逐位保留——回退基准可复现）。 */
    private static Noise buildFbmRoot(TerrainParams p) {
        double scale = p.continentScale();
        int octaves = Math.max(1, Math.min(10, p.continentFbmOctaves()));
        double lac = p.continentFbmLacunarity();
        double per = p.continentFbmPersistence();
        double warpAmt = p.continentWarp() * scale;

        Noise sum = new Constant(0.0);
        double q = 0.0;
        double freq = 1.0 / scale;
        double amp = 1.0;
        for (int i = 0; i < octaves; i++) {
            Noise o = new Frequency(new Simplex(7000 + i * 97), freq);
            sum = new Add(sum, new Multiply(o, new Constant(amp)));
            q += amp * amp;
            freq *= lac;
            amp *= per;
        }
        Noise fbm = new Multiply(sum, new Constant(1.0 / Math.sqrt(q)));
        if (warpAmt > 1e-6) {
            double wf = 1.0 / scale;
            Noise wx = new Frequency(new Simplex(7100), wf);
            Noise wz = new Frequency(new Simplex(7101), wf);
            Noise wx2 = new Frequency(new Simplex(7102), wf * 2.0);
            Noise wz2 = new Frequency(new Simplex(7103), wf * 2.0);
            Noise warpX = new Add(wx, new Multiply(wx2, new Constant(0.5)));
            Noise warpZ = new Add(wz, new Multiply(wz2, new Constant(0.5)));
            return new Warp(fbm, warpX, warpZ, warpAmt);
        }
        return fbm;
    }

    /** 一次性播种（核链记录世界种子并清缓存；FBM 路径播噪声图）。 */
    public void seed(long worldSeed) {
        this.worldSeed = worldSeed;
        if (fbmRoot != null) {
            Noises.seedAll(fbmRoot, worldSeed, 0);
        }
        cellCache.clear();
    }

    /**
     * 采样大陆性 c ∈ [-1,1]（负=海洋、正=陆地、0=海岸锚点附近）。
     *
     * <p>核链路径：壳值（椭圆冠并集，经四层域扭曲）→ 5 锚 4 段映射 → c。
     * 回退路径：旧 FBM 直接输出。分位语义见类注释。</p>
     */
    public double sample(double wx, double wz) {
        if (fbmRoot != null) {
            return fbmRoot.compute(wx, wz);
        }
        return 2.0 * crust01(shellValue(wx, wz)) - 1.0;
    }

    /**
     * 诊断/标定入口：晶格格的占据值（[0,1] 归一后）。
     * {@code ContinentAnchorProbe} 用它实测占据门限的真实触发率。
     */
    double occAt(int cx, int cz) {
        return 0.5 + 0.5 * fbm2(cx * CORE_SPACING / OCC_SCALE,
                cz * CORE_SPACING / OCC_SCALE, SALT_OCC);
    }

    /** 诊断入口：占据门限（{@code ContinentAnchorProbe} 统计未占据率用）。 */
    double occThreshold() {
        return OCC_THRESHOLD;
    }

    /**
     * 核并集壳值（诊断/标定入口）：椭圆冠 max-union + 群间海道 + 微细线，
     * 未做锚映射。锚 {@code {A0..A4}} 的分位对拍由 {@code ContinentAnchorProbe} 做。
     */
    public double shellValue(double wx0, double wz0) {
        // 位移式四层域扭曲：只移动采样点，不往场里加值（叠加式会打碎陆架）
        double wx = wx0 + warpDX(wx0, wz0);
        double wz = wz0 + warpDZ(wx0, wz0);
        long seed = worldSeed;
        double spacing = CORE_SPACING * k;
        int cx = (int) Math.floor(wx / spacing);
        int cz = (int) Math.floor(wz / spacing);

        Core best = null;
        double bestS = -2.0;
        Core second = null;
        double secondS = -2.0;
        for (int dz = -CORE_SEARCH; dz <= CORE_SEARCH; dz++) {
            for (int dx = -CORE_SEARCH; dx <= CORE_SEARCH; dx++) {
                Cell cell = cellOf(seed, cx + dx, cz + dz);
                if (cell.main() == null) {
                    continue;
                }
                for (Core core : cell.terranes()) {
                    double s = 1.0 - ellipseQ(wx, wz, core);
                    if (s > bestS) {
                        if (best == null || core.group() != best.group()) {
                            second = best;
                            secondS = bestS;
                        }
                        best = core;
                        bestS = s;
                    } else if (s > secondS && (best == null || core.group() != best.group())) {
                        second = core;
                        secondS = s;
                    }
                }
            }
        }
        double shell = bestS <= -2.0 ? -1.0 : bestS;
        // 群间海道：异群核交接处下切（gate 防整条海道在 best≈second≈深值处跳变）；
        // 差值用钳位后的 best（与沙箱执行顺序一致）
        shell -= CHAN_DEPTH * smoothstep(CHAN_GATE_LO, CHAN_GATE_HI, shell)
                * (1.0 - smoothstep(0.0, CHAN_W, shell - secondS));
        return shell + FINE_AMP * fbm2(wx / (FINE_SCALE * k), wz / (FINE_SCALE * k), SALT_FINE);
    }

    /** 壳值 → [0,1]：5 锚 4 段 smoothstep（权重和 = 1.0，段间 C¹）。 */
    private static double crust01(double shell) {
        return W_DEEP * smoothstep(A0, A1, shell)
                + W_SLOPE * smoothstep(A1, A2, shell)
                + W_SHELF * smoothstep(A2, A3, shell)
                + W_LAND * smoothstep(A3, A4, shell);
    }

    /** 椭圆冠归一距离：q&lt;1 内部、=1 边缘（长轴系投影）。 */
    private static double ellipseQ(double wx, double wz, Core c) {
        double dx = wx - c.sx();
        double dz = wz - c.sz();
        double u = dx * c.cos() + dz * c.sin();
        double v = -dx * c.sin() + dz * c.cos();
        double uq = u / c.ax();
        double vq = v / c.az();
        return Math.sqrt(uq * uq + vq * vq);
    }

    // ============================================================ 核晶格（缓存）

    /** 取晶格格产物（并发缓存；占据与否同缓存——负结果同样命中）。 */
    private Cell cellOf(long seed, int cx, int cz) {
        long key = mix(seed, cx, cz);
        Cell cell = cellCache.get(key);
        if (cell != null) {
            return cell;
        }
        if (cellCache.size() > CACHE_MAX) {
            cellCache.clear();
        }
        Cell built = buildCell(seed, cx, cz);
        cellCache.put(key, built == null ? EMPTY_CELL : built);
        return built == null ? EMPTY_CELL : built;
    }

    /** 晶格格纯函数：占据门控 → 主核 → 地体。null = 未占据。 */
    private Cell buildCell(long seed, int cx, int cz) {
        double spacing = CORE_SPACING * k;
        // 占据场归一到 [0,1] 再比门限：洋区只来自低频深负尾（fbm < −0.8 的区域）
        double occ = 0.5 + 0.5 * fbm2(cx * spacing / (OCC_SCALE * k),
                cz * spacing / (OCC_SCALE * k), SALT_OCC);
        if (occ < OCC_THRESHOLD) {
            return null;
        }
        long h = hash(seed, cx, cz, SALT_CORE);
        double sx = (cx + 0.5 + (unit(h) - 0.5) * 2.0 * CORE_JITTER) * spacing;
        double sz = (cz + 0.5 + (unit(h >>> 24) - 0.5) * 2.0 * CORE_JITTER) * spacing;
        double r = CORE_R_MIN + unit(h >>> 48) * (CORE_R_MAX - CORE_R_MIN);
        double st = 1.0 + unit(h >>> 12) * CORE_STRETCH_SPAN;
        double ang = unit(h >>> 36) * Math.PI;
        double ax = Math.max(r * st, CORE_MIN_SEMI * k);
        double az = Math.max(r / st, CORE_MIN_SEMI * k);
        int group = groupOf(cx, cz);
        Core main = new Core(sx, sz, ax, az, Math.cos(ang), Math.sin(ang), group);
        return new Cell(main, terranesOf(seed, cx, cz, main, r));
    }

    /** 地体 3~5 个：径向/切向 50/50、凸出至 1.45 半轴、方向抖动 ±0.3rad。 */
    private Core[] terranesOf(long seed, int cx, int cz, Core main, double r) {
        long hn = hash(seed, cx * 71 + 13, cz * 37 - 7, SALT_TERR);
        int n = TERR_BASE + (int) (unit(hn) * TERR_SPAN);   // u·3 ∈ [0,3) ⇒ 3~5
        double cosM = main.cos();
        double sinM = main.sin();
        double rMean = (main.ax() + main.az()) * 0.5;
        Core[] out = new Core[n + 1];
        out[0] = main;
        for (int t = 0; t < n; t++) {
            long ht = hash(seed, cx * 71 + t, cz * 37 - t, SALT_TERR + t);
            double azim = unit(ht) * 2.0 * Math.PI;
            double pos = TERR_POS_MIN + unit(ht >>> 12) * TERR_POS_SPAN;
            // 中心沿主核椭圆半径放置（主轴系 → 世界系旋转）
            double ex = Math.cos(azim) * main.ax() * pos;
            double ez = Math.sin(azim) * main.az() * pos;
            double tsx = main.sx() + ex * cosM - ez * sinM;
            double tsz = main.sz() + ex * sinM + ez * cosM;
            double rt = rMean * (TERR_R_FRAC_MIN + unit(ht >>> 24) * TERR_R_FRAC_SPAN);
            double stt = 1.0 + unit(ht >>> 36) * TERR_STRETCH_SPAN;
            // 径向（指向外海：印度/堪察加）或切向（沿海岸：日本弧）50/50，再抖动。
            // ⚠ 与沙箱一致：长轴角直接用 azim（世界系），不加主核朝向——
            //   标定分位基于此行为（加旋转会改变核边缘环带分布，实测分位漂移 ~8%）
            double wa = azim + (unit(ht >>> 52) < 0.5 ? 0.0 : Math.PI / 2.0)
                    + (unit(ht >>> 48) - 0.5) * 2.0 * TERR_ANG_JITTER;
            double tax = Math.max(rt * stt, TERR_MIN_SEMI * k);
            double taz = Math.max(rt / stt, TERR_MIN_SEMI * k);
            out[t + 1] = new Core(tsx, tsz, tax, taz, Math.cos(wa), Math.sin(wa), main.group());
        }
        return out;
    }

    /** 大陆群 id：3×3 核格 + 群边界扭曲（无扭曲则海道沿直线网格出现）。 */
    private int groupOf(int cx, int cz) {
        double gw = GROUP_WARP_AMP * fbm2(cx / GROUP_WARP_SCALE, cz / GROUP_WARP_SCALE, SALT_GRP);
        double gw2 = GROUP_WARP_AMP
                * fbm2(cx / GROUP_WARP_SCALE + 17.3, cz / GROUP_WARP_SCALE - 9.1, SALT_GRP + 7919);
        long gx = (long) Math.floor((cx + gw) / GROUP_CELLS);
        long gz = (long) Math.floor((cz + gw2) / GROUP_CELLS);
        return (int) (gx * 0x9E3779B1L ^ gz * 0x85EBCA77L);
    }

    // ============================================================ 四层域扭曲

    /** X 位移：四层同构值噪声求和（层盐递增 7919 去相关）。 */
    private double warpDX(double px, double pz) {
        double d = 0.0;
        for (int i = 0; i < WARP_SCALE.length; i++) {
            d += WARP_AMP[i] * k
                    * fbm2(px / (WARP_SCALE[i] * k), pz / (WARP_SCALE[i] * k), SALT_WX + i * 7919L);
        }
        return d;
    }

    /** Z 位移：同 X 但换盐基 + 每层输入偏移（X/Z 去相关）。 */
    private double warpDZ(double px, double pz) {
        double d = 0.0;
        for (int i = 0; i < WARP_SCALE.length; i++) {
            d += WARP_AMP[i] * k * fbm2(
                    px / (WARP_SCALE[i] * k) + WARP_Z_OFFSET[i][0],
                    pz / (WARP_SCALE[i] * k) + WARP_Z_OFFSET[i][1],
                    SALT_WZ + i * 7919L);
        }
        return d;
    }

    // ============================================================ 噪声原语（自研）

    /**
     * 双线性 + C¹ smoothstep 插值的种子值噪声（格点值 ∈ [-1,1]）。
     * 与 {@link com.geogenesis.worldgen.terrain.morph} 的 MorphNoise 分属不同实现：
     * 这里是核链专用的轻量原语，2 倍频叠加见 {@link #fbm2}。
     */
    private double valueNoise(double x, double z, long salt) {
        double fx = Math.floor(x);
        double fz = Math.floor(z);
        long ix = (long) fx;
        long iz = (long) fz;
        double tx = x - fx;
        double tz = z - fz;
        tx = tx * tx * (3.0 - 2.0 * tx);
        tz = tz * tz * (3.0 - 2.0 * tz);
        long seed = worldSeed;
        double n00 = unit(hash(seed, ix, iz, salt)) * 2.0 - 1.0;
        double n10 = unit(hash(seed, ix + 1, iz, salt)) * 2.0 - 1.0;
        double n01 = unit(hash(seed, ix, iz + 1, salt)) * 2.0 - 1.0;
        double n11 = unit(hash(seed, ix + 1, iz + 1, salt)) * 2.0 - 1.0;
        return (n00 * (1.0 - tx) + n10 * tx) * (1.0 - tz)
                + (n01 * (1.0 - tx) + n11 * tx) * tz;
    }

    /**
     * 2 倍频值噪声：octave2 = ×2.3 频率 + 0.5 增益 + 输入偏移（去相关），
     * 合成后 /1.5 归一（输出 ∈ [-1,1]）。与占据场/群扭曲/海道共用。
     */
    private double fbm2(double x, double z, long salt) {
        double n = valueNoise(x, z, salt)
                + 0.5 * valueNoise(x * 2.3 + 11.7, z * 2.3 + 5.9, salt + 7919L);
        return n / 1.5;
    }

    /** 整数格 hash（64 位混合；seed/x/z/salt 四路独立）。 */
    private static long hash(long seed, long x, long z, long salt) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31);
        h *= 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 27);
        h *= 0x165667B19E3779F9L;
        h ^= salt;
        h ^= h >>> 29;
        h *= 0x2545F4914F6CDD1DL;
        h ^= h >>> 32;
        return h;
    }

    /** hash 输出 → [0,1)。 */
    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 缓存 key 混合（seed 折进 key ⇒ seed 变化无需逐条失效）。 */
    private static long mix(long seed, int cx, int cz) {
        long h = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
        return h ^ (h >>> 29);
    }

    /** Hermite smoothstep（本地副本，与 CrustField/MorphTerrainSampler 同式）。 */
    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / Math.max(1e-9, e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3.0 - 2.0 * t);
    }
}
