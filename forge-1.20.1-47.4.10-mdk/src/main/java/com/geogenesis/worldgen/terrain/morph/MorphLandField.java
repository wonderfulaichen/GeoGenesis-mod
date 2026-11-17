package com.geogenesis.worldgen.terrain.morph;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>陆地 morph 场</b>：把 18 条目的配方过渡接到真实区域边界几何上，得到<b>世界坐标可查</b>的
 * 高度/权重函数。
 *
 * <h3>一条链</h3>
 * <pre>
 * site 权重  —— {@link MorphBoundaryGeometry} 的 Voronoi 权重（±120 块 smoothstep，块为单位的
 *               world 折线距离；<b>不用</b> edge 值乘常数冒充距离）
 *      ↓ 按 {@code catalog.slotEntry(geometry.slotOfCell(cell))} 归并同一 recipe
 * 条目权重   —— 和归一到 1，单条目时走内核的 onehot 重载
 *      ↓ {@code engine.sampleLand(x,z,w)}
 * regionHeight
 *      ↓ 覆盖层，<b>原样</b>复刻参考的 Blender.apply
 * coveredHeight = region + (oldPlateau − region)·alpha
 *      ↓
 * e = max(GROUND_E + coveredHeight·E_SPAN, 0)
 * </pre>
 *
 * <h3>★ 覆盖层必须用"共享台地"（1.05），不是修正台地（0.9）</h3>
 * <ul>
 *   <li>mask 节点 = 组装骨架里<b>同一个</b>{@link MorphAssembly#mask()} 对象，不重建；</li>
 *   <li>低/高阈值取自 {@link MorphAssembly#blendLow()} / {@link MorphAssembly#blendHigh()}；</li>
 *   <li>覆盖族 = 组装骨架的 {@link MorphAssembly#coverage()}（= 共享台地，Terrace 值域
 *       <b>1.05</b> 的本地历史口径）。<b>不是</b> M1_GEO，也不是参考修正的 0.9 台地；</li>
 *   <li>alpha 严格按原顺序：{@code (select − LOW) / (HIGH − LOW)}，之后
 *       {@code region + (old − region)·alpha}。</li>
 * </ul>
 *
 * <h3>★ 分窗：世界固定 tile + 局部几何，不做整幅大几何</h3>
 * <p>{@link MorphBoundaryGeometry} 的一次构建会枚举盒子内的站点、求精确 Voronoi 边并把边映回
 * world（反 warp 牛顿，折线密集到 2 块）。整幅 1401² 一个几何 ⇒ 站点/边/折线同时爆炸。
 * 本类改成<b>世界固定 1024 块 tile</b>：</p>
 * <pre>
 *   tile 中心 = (floor(x/1024)+0.5)·1024        ← 只由坐标决定，可世界无限平移
 *   查询圆半径 ≥ √2·512 = 724.0779             ← tile 内任一点都在圆内（含对角）
 *   几何盒 = 半径 + 120 halo                   ← query() 只接受圆内点，halo 只为把站点选全
 * </pre>
 * <p><b>为什么 halo 要 120</b>：权重带半宽就是 120。圆内点距盒边 ≥ (半径+120) − 724.08 &gt; 120，
 * 故"距查询点 120 以内"的折线全在盒内、不被盒边界裁掉 ⇒ 有符号距离与窗口无关。</p>
 *
 * <h3>★ 性能：只做必要缓存</h3>
 * <ul>
 *   <li>每个 tile 把全部 world 折线拆成<b>线段</b>，按格（site）索引，并给每条线段、每个格、
 *       每 16 段的分块各存 bbox；查询时 bbox 距离 &gt; 120 直接跳过 —— 该段对 {@code progress}
 *       的贡献<b>恒为精确 0</b>，所以跳过不改变产出（不是"用 edge 常数近似距离"）。</li>
 *   <li>同点复用：{@code sample(x,z)} 缓存最近一次 (x,z) 的完整结果。</li>
 *   <li>tile 几何按世界 tile 键做 LRU（{@value #CACHE_TILES} 个），不整幅常驻。</li>
 * </ul>
 *
 * <h3>⛔ 未证事项（不承诺无限世界正确）</h3>
 * <p>world 折线来自反 warp 牛顿解：残差小<b>不证明</b> {@code (x,z)↦(u,v)} 是单射
 * （多根时每个根残差都可以很小）。因此本类的 world 几何是<b>近似</b>，
 * 而且"世界单射"未被证明 ⇒ <b>不承诺</b>无限世界的正确性。</p>
 *
 * <h3>★ 线程模型</h3>
 * <p>所有状态（tile LRU、last-point 缓存、计数器）都由<b>本实例的监视器</b>保护：
 * {@code LinkedHashMap.get} 会改访问序，而 last-point 缓存与计数器必须与之原子地推进。
 * 并发调用者因此<b>互相阻塞（互斥）</b>，不是无锁。<b>没有取消 API</b>：
 * 无法中断进行中的 tile 构建。</p>
 */
public final class MorphLandField {

    // ============================================================ 常量

    /** 世界固定 tile 边长（块）。 */
    static final double TILE_BLOCKS = 1024.0;
    /**
     * 查询圆半径（块）。下界 = tile 半对角 {@code √2·512 = 724.0779...}；
     * 取 728 使 {@code (半径 + 120) − 半对角 ≈ 123.9 > 120}，即 ±120 权重带整体落在几何盒内。
     */
    static final double QUERY_RADIUS = 728.0;
    /** {@link MorphBoundaryGeometry#of} 的站点窗口半宽下限（格）；实际值由闭式界自动放大。 */
    static final int REQ_HALF = 4;
    /** world 折线弦偏差（块）。 */
    static final double CHORD_TOL = 0.02;
    /** world 折线单段最大长度（块），防中点采样漏掉 S 形。 */
    static final double MAX_SEG_BLOCKS = 2.0;
    /** tile 几何 LRU 容量。1401² 图跨 2×2 tile，留 8 个足够且不会爆内存。 */
    static final int CACHE_TILES = 8;
    static final int ENTRY_COUNT = MorphCatalog.ENTRY_COUNT;
    /** 覆盖层用的旧台地条目下标（= {@code unmixable[0]}）。 */
    /** 权重带半宽（块），直接取几何口径，不另写常数。 */
    private static final double BAND = MorphBoundaryGeometry.BAND_HALF_BLOCKS;
    /** 每 cell 有序线段的分块大小（连续段数）；分块 bbox 粗剔除用。 */
    private static final int CHUNK_SEGMENTS = 16;

    // ============================================================ 状态

    private final MorphCatalog catalog;
    private final float regionScale;
    private final MorphKernel engine;
    private final MorphAssembly assembly;
    private final MorphNoise.Node maskNode;
    /** 覆盖族（共享台地 height01）。 */
    private final MorphAssembly.Family oldCoverageFamily;
    /**
     * ★ 阶段六（2026-11-17）：火山覆盖族——类型层的独立几何覆盖（与台地同级）。
     * {@code null} = 关闭（逐位复现旧产出）。
     */
    private final VolcanoRecipe volcanoRecipe;
    /** 火山中心的陆上门控回调（sampler 注入，用 ContinentField 判）。 */
    private java.util.function.DoubleBinaryOperator volcanoLandProbe;
    /**
     * 火山中心的最低陆上度（ContinentField 原始 c，∈[−1,1]）。
     *
     * <p><b>0.004 = 全可见线</b>：生产陆海汇合在 {@code MorphOceanAssembler}，
     * {@code edge = (c+1)/2 > CP_INLAND(0.502)} 才完全取陆地层 ⇒ 中心过线，
     * 整座锥体（0.21e ≈ 49 格）才能完整出现在生产高度里。
     * 中心若落在混合带（edge 0.25~0.502），锥体被海岸剖面 lerp 压向海床
     * （门控缺位实测 ~90% 火山不可见——其中不少在深海里完全被吞）。
     * 探针：{@code VolcanoShapeProbe} 断言锥顶 {@code sampleE > 0}。</p>
     */
    private static final double VOLCANO_MIN_LAND = 0.004;
    private final double blendLow;
    private final double blendHigh;
    /**
     * 构造趋势回调（{@code null} = 关闭）。★ 阶段五（2026-11-16）：
     * 趋势与地形类型<b>共用同一份高度预算</b>，见 {@link #sample}。
     */
    private final TrendFn trendFn;
    // sample/tileAt/stats share this instance's monitor: LinkedHashMap.get
    // mutates access order, and the last-point cache and counters must move atomically with it.
    private final Map<Long, Tile> tiles;

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private Sample cached;
    private long tilesBuilt;
    private long tileEvictions;
    private long points;
    private long cachedPoints;

    // ============================================================ 构造

    /**
     * @param catalog 18 条目目录（族种子由它派生）
     */
    public MorphLandField(MorphCatalog catalog) {
        this(catalog, MorphAssembly.of(catalog.seed()), MorphAssembly.REGION_SCALE);
    }

    MorphLandField(MorphCatalog catalog, float regionScale) {
        this(catalog, MorphAssembly.of(java.util.Objects.requireNonNull(catalog).seed()), regionScale);
    }

    /**
     * @param catalog    18 条目目录（族种子由它派生）
     * @param assembly   同一种子的组装骨架（分区/票槽/遮罩/覆盖族）
     * @param regionScale 区域尺度（块）
     */
    MorphLandField(MorphCatalog catalog, MorphAssembly assembly, float regionScale) {
        this(catalog, assembly, regionScale, true);
    }

    /** 去算子候选与原入口共享几何、覆盖及种子，只切换山地纹理执行。 */
    MorphLandField(MorphCatalog catalog, MorphAssembly assembly, float regionScale, boolean mountainTexture) {
        this(catalog, assembly, regionScale, new MorphKernel(MorphLandRecipe.all(catalog), catalog.seed(), mountainTexture));
    }

    MorphLandField(MorphCatalog catalog, MorphAssembly assembly, float regionScale, MorphKernel kernel) {
        this(catalog, assembly, regionScale, kernel, null);
    }

    /**
     * ★ 2026-11-16（阶段五）带<b>构造趋势</b>的构造器。
     *
     * @param trendFn 构造趋势回调 {@code (x, z, crust01) → trend(e 单位)}；
     *                {@code null} = 关闭趋势 ⇒ 与 schema134 逐位相同。
     *                <p>为什么回调而非直接持有 {@code TectonicField}：本类在
     *                {@code morph} 包内，不应硬依赖 terrain 包的构造实现；
     *                回调由 {@code MorphTerrainSampler} 注入，保持包边界干净。</p>
     */
    MorphLandField(MorphCatalog catalog, MorphAssembly assembly, float regionScale,
                   MorphKernel kernel, TrendFn trendFn) {
        if (!(regionScale > 0) || !Float.isFinite(regionScale)) {
            throw new IllegalArgumentException("region scale");
        }
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.assembly = java.util.Objects.requireNonNull(assembly);
        if (assembly.seed() != catalog.seed()) {
            throw new IllegalArgumentException("assembly seed " + assembly.seed()
                    + " != catalog seed " + catalog.seed());
        }
        this.regionScale = regionScale;
        this.engine = java.util.Objects.requireNonNull(kernel);
        this.maskNode = assembly.mask();
        this.blendLow = assembly.blendLow();
        this.blendHigh = assembly.blendHigh();
        this.oldCoverageFamily = assembly.coverage();
        this.volcanoRecipe = assembly.volcano();
        this.trendFn = trendFn;
        this.tiles = tileCache();
    }

    /**
     * 构造趋势回调：返回该点的地势趋势（<b>e 单位</b>，会聚正 / 张裂负 / 无边界 0）。
     *
     * <p>见 {@code TectonicTrend} 类注释的分层说明。纯函数、无状态。</p>
     */
    @FunctionalInterface
    public interface TrendFn {
        double trend(double x, double z, double crust01);
    }

    private Map<Long, Tile> tileCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Tile> eldest) {
                boolean evict = size() > CACHE_TILES;
                if (evict) tileEvictions++;
                return evict;
            }
        };
    }

    /**
     * tile 构建锁 —— <b>只保护 tiles 这张 map，不包含几何构建</b>。
     *
     * <p>构建 {@link MorphBoundaryGeometry}（{@code buildWorldEdges} 的网格加密）是毫秒级慢操作；
     * 早先它被包在 {@code synchronized sample()} 里 ⇒ 全部地形采样串行化。
     * 实机 jstack 实测：19 个 MC worker 阻塞在同一把 {@code MorphLandField} 锁上，
     * 唯一持锁者正在 {@code MorphBoundaryGeometry.densify} ⇒ 进世界卡在结构定位阶段。</p>
     */
    private final Object tileLock = new Object();

    // ============================================================ 公开 API

    /**
     * 区域高度（覆盖层<b>之前</b>）：18 条目 morph 引擎在"{@code site 权重 → recipe 权重}"下的输出。
     *
     * <p>等价于旧 区域层高度 的<b>位置</b>，但类型来源换成 morph
     * 引擎（旧实现是"选一个族 + 边界回落到公共 border"）。</p>
     */
    public double regionHeight(double x, double z) {
        return sample(x, z).regionHeight();
    }

    /** 条目权重快照（长度 18，和 = 1；<b>返回副本</b>，调用方可以随意改）。 */
    public double[] weights(double x, double z) {
        return sample(x, z).weight().clone();
    }

    /** 权重最大的条目下标（并列取最小下标）。 */
    public int dominantEntry(double x, double z) {
        return sample(x, z).dominantEntry();
    }

    /** 覆盖层<b>之后</b>的高度：{@code region + (oldPlateau − region)·alpha}。 */
    public double coveredHeight(double x, double z) {
        return sample(x, z).coveredHeight();
    }

    /** {@code e = max(GROUND_E + coveredHeight·E_SPAN, 0)}（与 {@link MorphScale} 同式）。 */
    public double e(double x, double z) {
        return sample(x, z).e();
    }

    /** 原 Blender 的 mask 值 ∈ [0,1]（同一节点对象，不重建）。 */
    public double mask(double x, double z) {
        return maskValue(x, z);
    }

    /**
     * 一次算出全部量，供主循环复用（避免"e 一次 + 诊断一次"重复付几何遍历）。
     *
     * <p>同点连续调用命中 last-(x,z) 缓存，返回<b>同一个</b> {@link Sample} 实例。</p>
     */
    public Sample sample(double x, double z) {
        // ★ 2026-11-06【去掉整方法 synchronized】原先 {@code synchronized sample()} 把
        //   "缓存命中判断 + tile 几何构建 + 全部采样运算" 一起锁住 ⇒ 全局串行化。
        //   实机 jstack 实测 19 个 worker 阻塞在同一把锁上、唯一持锁者在建边界几何。
        //
        //   现在：① last-(x,z) 缓存与 tiles 各用窄锁；② 几何构建在锁外；
        //        ③ 采样算术（mergeToEntries/*ForMerged/maskValue/overlay）依赖的对象都是
        //           构造后只读的（catalog、tile.entryOfSite()、maskNode），本身无共享可写状态。
        //   产出与原先逐位一致（同样的输入走同样的纯计算），变化只在并发时序。
        synchronized (tileLock) {
            if (cached != null && x == lastX && z == lastZ) {
                cachedPoints++;
                return cached;
            }
        }
        Tile tile = tileFor(x, z);          // 慢路径已在内部移出锁
        double[] merged = mergeToEntries(tile.entryOfSite(), tile.weights(x, z));
        double region = heightForMerged(x, z, merged);
        double select = maskValue(x, z);
        double covered = overlay(region, select, select < blendLow ? Double.NaN : oldCoverageHeight(x, z));
        // ★ 阶段六修正（2026-11-17）：火山覆盖——类型层的独立几何覆盖，
        //   与台地 overlay 同机制（用户裁定：火山属于「地形类型 = 局部具体化」）。
        //
        //   · 增盈式：`covered + edifice`（火山是建在既有地形上的山体，加法物理正确；
        //     幅度 0.24e 只影响局部 240 块半径，不触发 trend 那样的预算问题）
        //   · 陆上门控：edifice 内部按<b>火山中心</b>用 `volcanoLandProbe` 判定
        //     （基线 `centerFactorOf` 的教训——逐点门控会让火山跨海岸线被斜切）
        //   · trend=0 且无火山 ⇒ 逐位不变（原式分支）
        double volcanoGate = 0.0;
        double volcanoEdifice = 0.0;
        if (volcanoRecipe != null) {
            volcanoEdifice = volcanoRecipe.edifice(x, z, VOLCANO_MIN_LAND, volcanoLandProbe);
            if (volcanoEdifice > 0.0) {
                // 覆盖强度 = 山体高度占比（供类型判定与 Sample 记录）
                volcanoGate = Math.min(1.0, volcanoEdifice / VolcanoRecipe.VOLCANO_TYPE_GATE);
            }
        }
        // ★ 阶段五（2026-11-16）：构造趋势与地形类型<b>共用同一份高度预算</b>。
        //
        //   为什么不是朴素加法：现有类型系统已把 e 用到 MAX_LAND_HI_E=0.95，
        //   而 heightFromE 硬钳在 e·verticalScale ≤ 1.0 ⇒ 纯加法会把山峰截平。
        //   所以趋势「重新分配」预算，总量锁死：
        //     e = GROUND_E + covered·E_SPAN·(1 − trend/MAX_LAND_HI_E) + trend
        //   · trend = 0 ⇒ 与无趋势逐位相同（乘数 1、加数 0）
        //   · trend > 0 ⇒ 类型起伏被压缩 (1−trend/0.95)，让出空间给趋势 → 峰顶仍 0.95
        //   · trend 是 e 单位 ⇒ 随 maxY 自动等比（用户「自定义高度上限」设定）
        double trend = 0.0;
        if (trendFn != null) {
            // crust01 由回调自行求（MorphTerrainSampler 注入的实现会读 ContinentField）。
            // 传 NaN 表示「请回调自负其责」——本类不持有大陆场，无法给出权威值。
            trend = trendFn.trend(x, z, Double.NaN);
            if (!Double.isFinite(trend)) {
                trend = 0.0;
            }
            // 票槽同源偏置：趋势高处更倾向山地/高原（Σw 仍=1，由 normalize 保证）
            if (trend != 0.0) {
                biasByTrend(merged, trend);
            }
        }
        // ★★ 关趋势必须<b>逐位</b>复现旧产出（P1 双 SHA 契约）。
        //   注意：即便 trend=0，若仍走新表达式，浮点乘/加顺序已与原式不同 ⇒
        //   必须走独立分支，完全绕开新式。
        double e;
        if (trend == 0.0 && volcanoEdifice == 0.0) {
            e = Math.max(MorphScale.GROUND_E + covered * MorphScale.E_SPAN, 0.0);
        } else {
            // ★ 2026-11-16 修正（用户验收：初版把山脊压平了 + 灌水成湖）。
            //
            //   初版用 `covered·E_SPAN·(1 − trend/0.95) + trend`（压缩类型起伏腾预算）
            //   ⇒ 山脊纹理被压糊、谷底相对抬升形成封闭洼地灌水。**错**：
            //   类型起伏是「局部具体化」，不该被趋势压扁。
            //
            //   正解：<b>从余量里挤，不压扁整体</b>——
            //     e = base + trend · (1 − base/LAND_E_CAP)
            //   · 低地（base≈0.1）→ 趋势几乎全量（×0.9）
            //   · 高山（base≈0.9）→ 趋势几乎为零（×0.1）
            //   · 峰顶不超 LAND_E_CAP ⇒ 不触发 heightFromE 的钳制
            //   · 类型起伏完全保留（山脊纹理不受影响）
            //
            //   ★ 火山（阶段六）同理用<b>余量注入</b>：`volcanoEdifice` 加在
            //     trend 之前，与 trend 共用同一个 headroom 缩放（高山处火山
            //     增量也被压缩，避免峰顶超 LAND_E_CAP）。
            double base = Math.max(MorphScale.GROUND_E + covered * MorphScale.E_SPAN, 0.0);
            double headroom = Math.max(0.0, 1.0 - base / MorphScale.LAND_E_CAP);
            e = Math.max(base + (trend + volcanoEdifice) * headroom, 0.0);
        }
        Sample s = new Sample(region, covered, e, select, alphaOf(select), dominant(merged),
                merged, activeCount(merged), trend, volcanoGate);
        synchronized (tileLock) {
            lastX = x;
            lastZ = z;
            cached = s;
            points++;
        }
        return s;
    }

    /**
     * ★ 阶段六：注入火山中心的陆上门控（sampler 持有 ContinentField）。
     * 必须在 {@code sample()} 首次调用前设置。
     */
    public void setVolcanoLandProbe(java.util.function.DoubleBinaryOperator probe) {
        this.volcanoLandProbe = probe;
    }

    /** 一次性计数的运行统计（只记账，不参与产出）。 */
    public Stats stats() {
        synchronized (tileLock) {
            return new Stats(tilesBuilt, tileEvictions, tiles.size(), points, cachedPoints,
                    oldCoverageFamily != null, blendLow, blendHigh);
        }
    }

    // ============================================================ 高度链

    /**
     * 条目权重 → 高度。单一 active 条目走 {@link MorphKernel} 的 <b>onehot</b> 重载
     * （{@code ids/weights} 数组），<b>不</b>新造"成品高度 lerp"层。
     */
    double heightForMerged(double x, double z, double[] w) {
        int only = singleActive(w);
        if (only >= 0 && w[only] == 1.0) {
            return engine.sampleLand(x, z, engine.merge(new int[]{only}, new double[]{1.0}));
        }
        return engine.sampleLand(x, z, w);
    }

    /** 覆盖层：逐行复刻 {@link MorphAssembly} 的覆盖层口径（含 float alpha 与分支顺序）。 */
    double overlay(double region, double select, double oldPlateau) {
        if (select < blendLow) {
            return region;
        }
        if (select > blendHigh) {
            return oldPlateau;
        }
        double alpha = (select - blendLow) / (blendHigh - blendLow);
        return region + (oldPlateau - region) * alpha;
    }

    /** alpha（[0,1]；mask 在两阈值之外时为 0 / 1，与 {@link #overlay} 同口径）。 */
    double alphaOf(double select) {
        if (select < blendLow) {
            return 0f;
        }
        if (select > blendHigh) {
            return 1f;
        }
        return (select - blendLow) / (blendHigh - blendLow);
    }

    /**
     * 旧台地高度：直接用组装骨架给出的覆盖族（= 共享台地 {@code height01}）。
     *
     * <p>⚠ 这里走的是<b>共享台地</b>（{@code Terrace} 值域 1.05），不是目录对外的
     * 修正值域 0.9 版 —— 覆盖层必须与参考 {@code Blender(unmixable[0])} 取同一个对象，
     * 否则覆盖后高度整体偏低。两个值都在 {@link MorphCatalog} 里显式命名。</p>
     */
    double oldCoverageHeight(double x, double z) {
        if (oldCoverageFamily == null) {
            throw new UnsupportedOperationException("missing old coverage family");
        }
        return oldCoverageFamily.height01(x, z);
    }

    /** 覆盖层 select 值（山形遮罩，{@code MorphNoiseEval} 求值）。 */
    double maskValue(double x, double z) {
        return MorphNoiseEval.eval(maskNode, x, z);
    }

    // ============================================================ 权重链

    /** site 权重 → 18 条目权重（同 recipe 归并、归一、残差落到最大项使和恰为 1）。 */
    static double[] mergeToEntries(int[] entryOfSite, double[] siteWeights) {
        if (entryOfSite.length != siteWeights.length) {
            throw new IllegalArgumentException("entryOfSite/siteWeights length mismatch: "
                    + entryOfSite.length + " vs " + siteWeights.length);
        }
        double[] merged = new double[ENTRY_COUNT];
        for (int i = 0; i < siteWeights.length; i++) {
            double w = siteWeights[i];
            if (w != 0.0) {
                int e = entryOfSite[i];
                if (e < 0 || e >= ENTRY_COUNT) {
                    throw new IllegalArgumentException("site " + i + " has entry " + e
                            + " outside [0," + ENTRY_COUNT + ")");
                }
                merged[e] += w;
            }
        }
        normalize(merged);
        return merged;
    }

    /**
     * 归一：先除总和，再把舍入残差补到最大项，使<b>按下标顺序</b>求和恰为 1。
     *
     * <p>为什么必须"恰为 1"：{@link MorphKernel} 的契约是 {@code |Σw−1| ≤ 1e-9}，
     * 而它在 0..17 顺序里求和。这里就用<b>同一顺序</b>复算并补齐残差，
     * 最多补 4 轮（补齐本身会改变中间部分和，理论上一轮就够，循环只是防呆）。</p>
     */
    static void normalize(double[] w) {
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            sum += w[i];
        }
        if (!(sum > 0)) {
            throw new IllegalStateException("site weights summed to " + sum + " (no active site)");
        }
        int argmax = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] /= sum;
            if (w[i] > w[argmax]) {
                argmax = i;
            }
        }
        for (int round = 0; round < 4; round++) {
            double s = 0;
            for (int i = 0; i < w.length; i++) {
                s += w[i];
            }
            if (s == 1.0) {
                return;
            }
            w[argmax] += 1.0 - s;
        }
        double s = 0;
        for (int i = 0; i < w.length; i++) {
            s += w[i];
        }
        if (Math.abs(s - 1.0) > 1e-12) {
            throw new IllegalStateException("normalize failed to reach sum=1: " + s);
        }
    }

    static int dominant(double[] w) {
        int best = 0;
        for (int i = 1; i < w.length; i++) {
            if (w[i] > w[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * ★ 阶段五（2026-11-16）：趋势对<b>票槽权重</b>的同源偏置。
     *
     * <p>用户分层要求「板块地形 = 整体趋势，地形类型 = 局部具体化」——
     * 趋势高（会聚带）时该带整体抬高，同时<b>类型上更倾向山地/高原</b>，
     * 这样「板块边界附近长得像山」是趋势与类型协同的结果，而不是两层各自加。</p>
     *
     * <p>做法：把趋势强度转成「向山地/高原迁移」的质量，从低地条目按比例扣除，
     * 再调用 {@link #normalize} 保证 Σw=1（{@code MorphKernel.Evaluation} 的硬契约）。
     * 纯函数、就地修改 {@code merged}。</p>
     *
     * @param merged 18 条目权重（Σ=1 的副本）
     * @param trend  趋势（e 单位，正=会聚隆起）
     */
    static void biasByTrend(double[] merged, double trend) {
        if (trend <= 0.0) {
            return;   // 张裂（负）不改类型偏好：谷底地貌由类型系统自行决定
        }
        // 迁移比例：trend=CONV_PEAK(0.17) ⇒ 约 22% 的质量迁向山地/高原
        double shift = Math.min(0.35, trend / MorphScale.TREND_PEAK * 0.22);
        if (shift <= 0.0) {
            return;
        }
        // 从「低地类」条目按当前权重的比例扣除 shift（重新分配，不是凭空增加）
        double lowMass = 0.0;
        for (int i = 0; i < merged.length; i++) {
            if (isLowland(i)) {
                lowMass += merged[i];
            }
        }
        if (lowMass <= 1e-12) {
            return;   // 已是纯山地/台地：无需偏置
        }
        double take = Math.min(shift, lowMass * 0.9);   // 最多扣 90%，避免整类消失
        for (int i = 0; i < merged.length; i++) {
            if (isLowland(i) && merged[i] > 0.0) {
                merged[i] -= merged[i] / lowMass * take;
            }
        }
        // 迁入：山地 70% / 台地 30%（山地带更符合会聚带的观感）
        merged[MorphCatalog.IDX_MOUNTAIN] += take * 0.7;
        merged[MorphCatalog.IDX_PLATEAU] += take * 0.3;
        normalize(merged);
    }

    /** 是否为「低地类」条目（平原/丘陵/阶地及其复合）。 */
    private static boolean isLowland(int entryIndex) {
        return entryIndex < MorphCatalog.IDX_PLATEAU;
    }

    static int activeCount(double[] w) {
        int n = 0;
        for (double v : w) {
            if (v != 0.0) {
                n++;
            }
        }
        return n;
    }

    /** 恰好一个非零分量时返回其下标，否则 -1。 */
    static int singleActive(double[] w) {
        int only = -1;
        for (int i = 0; i < w.length; i++) {
            if (w[i] == 0.0) {
                continue;
            }
            if (only >= 0) {
                return -1;
            }
            only = i;
        }
        return only;
    }

    // ============================================================ tile

    private Tile tileFor(double x, double z) {
        int tx = tileIndex(x);
        int tz = tileIndex(z);
        long key = MorphBoundaryGeometry.pack(tx, tz);
        // 快路径：只持锁读缓存（O(1)，无几何构建）。
        synchronized (tileLock) {
            Tile t = tiles.get(key);
            if (t != null) return t;
        }
        // 慢路径：几何构建在锁【外】完成，避免把构建耗时转嫁给所有采样线程。
        // 同一 tile 并发首访时可能重复构建一次 —— 结果确定性（纯函数），代价可接受；
        // 换来的是"绝大多数采样不必排队等别人建几何"。
        long t0 = System.nanoTime();
        MorphBoundaryGeometry g = MorphBoundaryGeometry.of(assembly.partition(), regionScale,
                center(tx), center(tz), QUERY_RADIUS, REQ_HALF, CHORD_TOL,
                assembly.slotCount(), assembly.slotCategories(), assembly.seed());
        g.setMaxSeg(MAX_SEG_BLOCKS);
        Tile built = new Tile(catalog, g, tx, tz, (System.nanoTime() - t0) / 1_000_000L);
        synchronized (tileLock) {
            Tile existing = tiles.get(key);
            if (existing != null) return existing;   // 别人先建好了 ⇒ 用他的，丢弃本次
            tiles.put(key, built);
            tilesBuilt++;
            return built;
        }
    }

    /**
     * 世界坐标 → tile 索引（{@code floor(v / 1024)}）。
     *
     * <p><b>越界必须抛错，不许饱和。</b>裸 {@code (int) Math.floor} 在 {@code v} 超出
     * {@code 1024·Integer.MAX_VALUE ≈ 2.2×10¹²} 时静默饱和到 {@code Integer.MAX_VALUE}
     * ⇒ 所有更远的坐标共享同一个 tile 键，几何被复用、地形变成一片重复 —— 而且
     * 不抛异常，只在图上看得出"远处不对"。正常 MC 世界（|x| ≤ 3×10⁷）离该界有 4 个数量级
     * 余量，所以这道守卫在合法输入上永不触发。</p>
     */
    static int tileIndex(double v) {
        double t = v / TILE_BLOCKS;
        if (!Double.isFinite(t) || t <= Integer.MIN_VALUE + 2 || t >= Integer.MAX_VALUE - 2) {
            throw new IllegalArgumentException("world coordinate outside tile-index range: " + v);
        }
        return (int) Math.floor(t);
    }

    static double center(int tileIndex) {
        return (tileIndex + 0.5) * TILE_BLOCKS;
    }

    // ============================================================ 数据载体

    /**
     * 一次查询的全部结果。
     *
     * @param weight 长度 18 的条目权重（<b>内部数组</b>；{@link MorphLandField#weights} 才返回副本）
     */
    public record Sample(double regionHeight, double coveredHeight, double e, double mask, double alpha,
                         int dominantEntry, double[] weight, int activeEntries, double trend,
                         double volcanoGate) {

        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "Sample(region=%.6f covered=%.6f e=%.6f mask=%.4f alpha=%.4f dom=%d active=%d"
                            + " trend=%+.4f volc=%.3f)",
                    regionHeight, coveredHeight, e, mask, alpha, dominantEntry, activeEntries,
                    trend, volcanoGate);
        }
    }

    /** 运行统计（只记账，不参与产出）。 */
    public record Stats(long tilesBuilt, long tileEvictions, int tilesCached, long points,
                        long cachedPoints, boolean oldCoverageFromReflection,
                        double blendLow, double blendHigh) { }

    /**
     * 一个 world 固定 tile 的局部几何 + 线段/格索引。
     *
     * <p>索引只用于<b>跳过必然贡献 0 的线段</b>：线段 bbox 到查询点的距离 &gt; 120 ⇒
     * 真实折线距离也 &gt; 120 ⇒ {@code progress} 恒为精确 0。故跳过不改变产出
     * （check 用 {@code geometry.query()} 逐位对拍，不是估算）。</p>
     *
     * <p><b>分块 bbox 粗剔除（本副本新增，仅索引，不改公式）</b>：每 cell 的有序线段表按
     * {@value #CHUNK_SEGMENTS} 连续段分块，每块预存并集 bbox。{@code bandDistance} 先用
     * <b>与 {@code segmentOutsideBand} 完全同式的 {@link #boxDistance} sqrt 判据</b>剔整块
     * —— 被剔块的每条线段在原 {@code segmentOutsideBand} 判据下也必被跳过，故逐位产出不变；
     * 通过粗剔除的段仍走原 {@code segmentOutsideBand + segmentDistance} 判据。</p>
     *
     * <p><b>⚠ 为什么不能用 {@code dx²+dz² > BAND²} 做粗筛</b>（本副本曾如此，已改）：
     * 线段侧判据是 {@code fl(sqrt(fl(dx²+dz²))) > 120}，块侧若是
     * {@code fl(dx²+dz²) > 14400}，两者在边界上<b>可以不同真</b>。反例路径：块被打到
     * {@code fl(dx²+dz²) = 14400 + 1ulp}（判为"剔除"），而块内某线段的
     * {@code sqrt(fl(...))} 落在 {@code (120, 120+ulp/2)} 区间 ⇒ 舍入回 <b>恰好 120.0</b>
     * ⇒ 该段在 {@code segmentOutsideBand} 下<b>不被</b>跳过，却被块级粗筛一起丢掉
     * （少一个真实距离）。平方比较不能当作 sqrt 比较的逐位等价物。</p>
     *
     * <p><b>同式判据为何逐位安全（单调性证明，不依赖“纯代数平方”）</b>：块 bbox 是块内线段
     * bbox 的并集 ⇒ 每个线段都有 {@code chunkMinX ≤ segMinX}、{@code segMaxX ≤ chunkMaxX}
     * （z 同理）。{@code boxDistance} 的 {@code dx}/{@code dz} 是
     * {@code (min − v)} / {@code (v − max)} / {@code 0.0} 三种情况，均为 IEEE 正确舍入，
     * 而正确舍入<b>对每个操作数单调</b>，故 {@code dx_chunk ≤ dx_seg}、{@code dz_chunk ≤ dz_seg}
     * 逐位成立。再逐级套同一单调性：{@code fl(dx²)}、{@code fl(a+b)}、{@code Math.sqrt}
     * （IEEE 正确舍入 ⇒ 单调），得 {@code D_chunk ≤ D_seg}（{@code D} = 该段自己算出的
     * {@code boxDistance} 值）。于是 {@code D_chunk > BAND ⇒ D_seg > BAND}，即被剔块的每条
     * 线段都会被原判据剔掉。<b>不需要 nextUp/余量</b>；改用平方式则必须额外证明上述反例不存在，
     * 而那是不成立的。</p>
     */
    static final class Tile {
        final int tileX;
        final int tileZ;
        final double centerX;
        final double centerZ;
        final long buildMillis;
        final int siteCount;
        final int edgeCount;
        final int segmentCount;
        final int[] slot;
        final int[] entry;
        final double[] siteU;
        final double[] siteV;
        private final double[] segX0;
        private final double[] segZ0;
        private final double[] segX1;
        private final double[] segZ1;
        private final double[] segMinX;
        private final double[] segMaxX;
        private final double[] segMinZ;
        private final double[] segMaxZ;
        private final int[][] cellSegs;
        private final double[] cellMinX;
        private final double[] cellMaxX;
        private final double[] cellMinZ;
        private final double[] cellMaxZ;
        /** 每 cell 的有序线段分块 bbox 粗剔除索引（bands 优化）；cell 无分块为长度 0。 */
        private final int[][] chunkStart;
        private final int[][] chunkEnd;
        private final double[][] chunkMinX;
        private final double[][] chunkMaxX;
        private final double[][] chunkMinZ;
        private final double[][] chunkMaxZ;
        private final MorphBoundaryGeometry geometry;

        Tile(MorphCatalog catalog, MorphBoundaryGeometry g, int tx, int tz, long millis) {
            this.geometry = g;
            this.tileX = tx;
            this.tileZ = tz;
            this.centerX = g.centerX();
            this.centerZ = g.centerZ();
            this.buildMillis = millis;
            List<MorphBoundaryGeometry.Site> sites = g.sites();
            this.siteCount = sites.size();
            this.slot = new int[siteCount];
            this.entry = new int[siteCount];
            this.siteU = new double[siteCount];
            this.siteV = new double[siteCount];
            for (int i = 0; i < siteCount; i++) {
                MorphBoundaryGeometry.Site s = sites.get(i);
                siteU[i] = s.vx();
                siteV[i] = s.vz();
                slot[i] = g.slotOfCell(s.cellX(), s.cellZ());
                entry[i] = slot[i] < 0 ? -1 : catalog.slotEntry(slot[i]);
                if (entry[i] < 0) {
                    throw new IllegalStateException("site " + s + " has slot " + slot[i]
                            + " outside the catalog table");
                }
            }
            List<MorphBoundaryGeometry.Edge> edges = g.edges();
            this.edgeCount = edges.size();
            this.segmentCount = countSegments(edges);
            this.segX0 = new double[segmentCount];
            this.segZ0 = new double[segmentCount];
            this.segX1 = new double[segmentCount];
            this.segZ1 = new double[segmentCount];
            this.segMinX = new double[segmentCount];
            this.segMaxX = new double[segmentCount];
            this.segMinZ = new double[segmentCount];
            this.segMaxZ = new double[segmentCount];
            this.cellSegs = new int[siteCount][];
            this.cellMinX = new double[siteCount];
            this.cellMaxX = new double[siteCount];
            this.cellMinZ = new double[siteCount];
            this.cellMaxZ = new double[siteCount];
            this.chunkStart = new int[siteCount][];
            this.chunkEnd = new int[siteCount][];
            this.chunkMinX = new double[siteCount][];
            this.chunkMaxX = new double[siteCount][];
            this.chunkMinZ = new double[siteCount][];
            this.chunkMaxZ = new double[siteCount][];
            fillIndex(edges);
        }

        private static int countSegments(List<MorphBoundaryGeometry.Edge> edges) {
            int n = 0;
            for (MorphBoundaryGeometry.Edge e : edges) {
                n += Math.max(0, e.wx.length - 1);
            }
            return n;
        }

        /** 拆线段 + 按格索引 + 每格并集 bbox。 */
        private void fillIndex(List<MorphBoundaryGeometry.Edge> edges) {
            int[] perCell = new int[siteCount];
            for (MorphBoundaryGeometry.Edge e : edges) {
                int len = Math.max(0, e.wx.length - 1);
                perCell[e.a.index()] += len;
                if (e.b.index() != e.a.index()) {
                    perCell[e.b.index()] += len;
                }
            }
            int[] fill = new int[siteCount];
            for (int i = 0; i < siteCount; i++) {
                cellSegs[i] = new int[perCell[i]];
            }
            int k = 0;
            for (MorphBoundaryGeometry.Edge e : edges) {
                for (int j = 0; j + 1 < e.wx.length; j++) {
                    segX0[k] = e.wx[j];
                    segZ0[k] = e.wz[j];
                    segX1[k] = e.wx[j + 1];
                    segZ1[k] = e.wz[j + 1];
                    segMinX[k] = Math.min(segX0[k], segX1[k]);
                    segMaxX[k] = Math.max(segX0[k], segX1[k]);
                    segMinZ[k] = Math.min(segZ0[k], segZ1[k]);
                    segMaxZ[k] = Math.max(segZ0[k], segZ1[k]);
                    int ia = e.a.index();
                    cellSegs[ia][fill[ia]++] = k;
                    int ib = e.b.index();
                    if (ib != ia) {
                        cellSegs[ib][fill[ib]++] = k;
                    }
                    k++;
                }
            }
            for (int i = 0; i < siteCount; i++) {
                double mnx = Double.POSITIVE_INFINITY;
                double mxx = Double.NEGATIVE_INFINITY;
                double mnz = Double.POSITIVE_INFINITY;
                double mxz = Double.NEGATIVE_INFINITY;
                for (int s : cellSegs[i]) {
                    mnx = Math.min(mnx, segMinX[s]);
                    mxx = Math.max(mxx, segMaxX[s]);
                    mnz = Math.min(mnz, segMinZ[s]);
                    mxz = Math.max(mxz, segMaxZ[s]);
                }
                cellMinX[i] = mnx;
                cellMaxX[i] = mxx;
                cellMinZ[i] = mnz;
                cellMaxZ[i] = mxz;
                buildChunks(i);
            }
        }

        /**
         * 把 cell 的有序线段表按 {@value #CHUNK_SEGMENTS} 连续段分块，每块存并集 bbox。
         *
         * <p>并集用 {@code Math.min/Math.max} 逐位取（无舍入）⇒ 块 bbox 严格包含块内每条线段
         * 的 bbox。粗剔除判据见 {@link Tile} 的类注释：块级与线段级<b>同一</b>
         * {@link #boxDistance} 式（含 {@code Math.sqrt}），靠正确舍入的单调性得到
         * {@code D_chunk ≤ D_seg}，故 {@code D_chunk > BAND} ⇒ 块内每段都被原判据剔掉，
         * 跳过整块不改变任何逐位产出。逐段循环内的判据与算式原样保留。</p>
         */
        private void buildChunks(int site) {
            int n = cellSegs[site].length;
            if (n == 0) {
                chunkStart[site] = new int[0];
                chunkEnd[site] = new int[0];
                chunkMinX[site] = new double[0];
                chunkMaxX[site] = new double[0];
                chunkMinZ[site] = new double[0];
                chunkMaxZ[site] = new double[0];
                return;
            }
            int chunkCount = (n + CHUNK_SEGMENTS - 1) / CHUNK_SEGMENTS;
            int[] starts = new int[chunkCount];
            int[] ends = new int[chunkCount];
            double[] mnx = new double[chunkCount];
            double[] mxx = new double[chunkCount];
            double[] mnz = new double[chunkCount];
            double[] mxz = new double[chunkCount];
            for (int c = 0; c < chunkCount; c++) {
                int from = c * CHUNK_SEGMENTS;
                int to = Math.min(n, from + CHUNK_SEGMENTS);
                starts[c] = from;
                ends[c] = to;
                mnx[c] = Double.POSITIVE_INFINITY;
                mxx[c] = Double.NEGATIVE_INFINITY;
                mnz[c] = Double.POSITIVE_INFINITY;
                mxz[c] = Double.NEGATIVE_INFINITY;
                for (int p = from; p < to; p++) {
                    int s = cellSegs[site][p];
                    mnx[c] = Math.min(mnx[c], segMinX[s]);
                    mxx[c] = Math.max(mxx[c], segMaxX[s]);
                    mnz[c] = Math.min(mnz[c], segMinZ[s]);
                    mxz[c] = Math.max(mxz[c], segMaxZ[s]);
                }
            }
            chunkStart[site] = starts;
            chunkEnd[site] = ends;
            chunkMinX[site] = mnx;
            chunkMaxX[site] = mxx;
            chunkMinZ[site] = mnz;
            chunkMaxZ[site] = mxz;
        }

        int entryOf(int site) {
            return entry[site];
        }

        /** 站点 → 条目 的整表（0..siteCount-1）；check 归并权重要用整表。 */
        int[] entryOfSite() {
            return entry.clone();
        }

        double centerX() {
            return centerX;
        }

        double centerZ() {
            return centerZ;
        }

        /** 站点在 u 平面的抖动格心（check 由它反解出纯权重点）。 */
        double siteU(int i) {
            return siteU[i];
        }

        double siteV(int i) {
            return siteV[i];
        }

        int siteCount() {
            return siteCount;
        }

        MorphBoundaryGeometry geometry() {
            return geometry;
        }

        /**
         * 每 site 权重：与 {@link MorphBoundaryGeometry#query} <b>逐位一致</b>
         * （同样的 winner、同样的有符号距离、同样的归一顺序），只多了 bbox 跳过。
         */
        double[] weights(double x, double z) {
            int win = winnerIndex(x, z);
            double[] wt = new double[siteCount];
            double sum = 0;
            double winnerSd = Double.NaN;
            for (int i = 0; i < siteCount; i++) {
                double sd = signedDistance(i, x, z, win);
                if (i == win) {
                    winnerSd = sd;
                }
                wt[i] = MorphBoundaryGeometry.progress(sd);
                sum += wt[i];
            }
            if (Double.isNaN(winnerSd)) {
                Arrays.fill(wt, 0.0);
                wt[win] = 1.0;
            } else if (sum > 0) {
                for (int i = 0; i < siteCount; i++) {
                    wt[i] /= sum;
                }
            }
            return wt;
        }

        /** 全平面（非 3×3）最近的场景内站点，与 {@code exactWinner} 同序同判据。 */
        int winnerIndex(double x, double z) {
            double[] p = geometry.warpForward(x, z);
            int best = -1;
            double bf = Double.POSITIVE_INFINITY;
            for (int i = 0; i < siteCount; i++) {
                double f = MorphBoundaryGeometry.potential(siteU[i], siteV[i], p[0], p[1]);
                if (f < bf) {
                    bf = f;
                    best = i;
                }
            }
            if (best < 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "no site wins at (%.3f,%.3f) in tile (%d,%d)", x, z, tileX, tileZ));
            }
            return best;
        }

        /** 有符号距离：赢家 {@code +best}，其余 {@code -best}；该格无折线 ⇒ {@code NaN}。 */
        double signedDistance(int site, double x, double z, int win) {
            int[] segs = cellSegs[site];
            if (segs.length == 0) {
                return Double.NaN;
            }
            double best = cellOutsideBand(site, x, z) ? Double.POSITIVE_INFINITY
                    : bandDistance(site, segs, x, z);
            return site == win ? best : -best;
        }

        private double bandDistance(int site, int[] segs, double x, double z) {
            double best = Double.POSITIVE_INFINITY;
            // 逐块 bbox 粗剔除：块级判据与 segmentOutsideBand 完全同式（boxDistance，含 sqrt）
            // ⇒ 依赖 sqrt 是正确舍入（IEEE 754 要求）带来的单调性，得 D_chunk <= D_seg。
            // 因此 D_chunk > BAND 时块内每段也必被原判据剔除，跳过整块产出不变。
            // ⚠ 不得改回 dx*dx+dz*dz > BAND*BAND：边界上可能与 sqrt 判据不同真（见类注释）。
            int[] starts = chunkStart[site];
            for (int c = 0; c < starts.length; c++) {
                if (chunkOutsideBand(site, c, x, z)) {
                    continue;
                }
                int from = starts[c];
                int to = chunkEnd[site][c];
                for (int p = from; p < to; p++) {
                    int k = segs[p];
                    if (segmentOutsideBand(k, x, z)) {
                        continue;
                    }
                    double d = MorphBoundaryGeometry.segmentDistance(x, z, segX0[k], segZ0[k],
                            segX1[k], segZ1[k]);
                    if (d < best) {
                        best = d;
                    }
                    if (best <= 0) {
                        return best;
                    }
                }
            }
            return best;
        }

        /**
         * 块 bbox 剔除判据。<b>与 {@link #segmentOutsideBand} 逐位同式</b>：调用同一个
         * {@link #boxDistance}（同样含 {@code Math.sqrt}，同样与 {@code BAND} 比较），
         * 只是喂进去的是块的并集 bbox。
         *
         * <p>这样做的理由是<b>正确舍入的单调性</b>，不是“平方与开方等价”：并集 bbox 使
         * {@code dx_chunk ≤ dx_seg}、{@code dz_chunk ≤ dz_seg} 逐位成立，{@code fl(·²)}、
         * {@code fl(a+b)}、{@code sqrt} 三级都单调 ⇒ 块的值 {@code ≤} 段自己算出的值。
         * 若改用 {@code dx²+dz² > BAND²}，则须另行证明“平方判真 ⇒ 开方判真”，
         * 而该命题在 {@code fl(x²+y²)} 恰好越过 14400 而 {@code sqrt} 舍回 120.0 时<b>为假</b>。</p>
         */
        private boolean chunkOutsideBand(int site, int chunk, double x, double z) {
            return boxDistance(x, z, chunkMinX[site][chunk], chunkMaxX[site][chunk],
                    chunkMinZ[site][chunk], chunkMaxZ[site][chunk]) > BAND;
        }

        private boolean cellOutsideBand(int site, double x, double z) {
            return boxDistance(x, z, cellMinX[site], cellMaxX[site], cellMinZ[site], cellMaxZ[site])
                    > BAND;
        }

        private boolean segmentOutsideBand(int k, double x, double z) {
            return boxDistance(x, z, segMinX[k], segMaxX[k], segMinZ[k], segMaxZ[k]) > BAND;
        }

        private static double boxDistance(double x, double z,
                                          double minX, double maxX, double minZ, double maxZ) {
            double dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0.0);
            double dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0.0);
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** 只读快照（check 的 geometry.csv）。 */
        String summary() {
            return String.format(Locale.ROOT, "%d,%d,%.1f,%.1f,%d,%d,%d,%d",
                    tileX, tileZ, centerX, centerZ, siteCount, edgeCount, segmentCount, buildMillis);
        }
    }
}
