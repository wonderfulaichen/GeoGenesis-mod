package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>独立沙箱实验臂：18 条目配方过渡接入 1401 大图</b>（生产不动、基准保留）。
 *
 * <p>把 {@link RecipeJunctionMorph}（18 条目的类型过渡引擎）接到
 * {@link DoubleBoundaryGeometry}（真实区域边界的局部 Voronoi 几何）上，得到<b>世界坐标可查</b>的
 * 高度/权重函数，供渲染器出 1401² 大图。</p>
 *
 * <h3>一条链（每一段都是既有生产口径，本类不新造地形规则）</h3>
 * <pre>
 * site 权重  —— {@link DoubleBoundaryGeometry} 的 Voronoi 权重（±120 块 smoothstep，块为单位的
 *               world 折线距离；<b>不用</b> edge 值乘常数冒充距离）
 *      ↓ 按 {@code catalog.slotEntry(geometry.slotOfCell(cell))} 归并同一 recipe
 * 条目权重   —— 和归一到 1，单条目时走 {@link RecipeJunctionMorph} 的 onehot 重载
 *      ↓ {@code engine.sample(x,z,w)}
 * regionHeight（= 旧 {@code CombineCandidate.debugRegionHeight} 的等价量，但类型来源换成 morph）
 *      ↓ 覆盖层，<b>原样</b>复刻 {@code CombineCandidate.Blender.apply}
 * coveredHeight = region + (oldPlateau − region)·alpha
 *      ↓
 * e = max(GROUND_E + coveredHeight·E_SPAN, 0)
 * </pre>
 *
 * <h3>★ 覆盖层：沿用原 Blender，不许替换成 M1</h3>
 * <ul>
 *   <li>mask 节点 = <b>原</b>{@code CombineCandidate.Blender.mask}（反射同一对象，不重建）；</li>
 *   <li>低/高阈值 = 反射 {@code CombineCandidate.MTN_BLEND_LOW/MTN_BLEND_HIGH}（不复制字面量）；</li>
 *   <li>覆盖族 = <b>实际的</b>{@code unmixable[0]}（= 共享 {@code PlateauCandidate}，Terrace 值域
 *       <b>1.05</b> 的本地历史口径）。反射不可用时退回
 *       {@code catalog.entry(15).oldReference}（同一物、同一 float 算式，check 里对拍）；
 *       <b>不是</b> M1_GEO，也不是参考修正的 0.9 台地；</li>
 *   <li>alpha 严格按原 float 顺序：{@code (select − LOW) / (HIGH − LOW)}，之后
 *       {@code region + (old − region)·alpha} 在 double 里做。</li>
 * </ul>
 *
 * <h3>★ 分窗：世界固定 tile + 局部几何，不做整幅大几何</h3>
 * <p>{@link DoubleBoundaryGeometry} 的一次构建会枚举盒子内的站点、求精确 Voronoi 边并把边映回
 * world（反 warp 牛顿，折线密集到 2 块）。1401² 整幅一个几何 ⇒ 站点/边/折线同时爆炸。
 * 本类改成<b>世界固定 1024 块 tile</b>：</p>
 * <pre>
 *   tile 中心 = (floor(x/1024)+0.5)·1024        ← 只由坐标决定，可世界无限平移
 *   查询圆半径 ≥ √2·512 = 724.0779             ← tile 内任一点都在圆内（含对角）
 *   几何盒 = 半径 + 120 halo                   ← query() 只接受圆内点，halo 只为把站点选全
 * </pre>
 * <p><b>为什么 halo 要 120</b>：权重带半宽就是 120。圆内点距盒边 ≥ (半径+120) − 724.08 &gt; 120，
 * 故"距查询点 120 以内"的折线全在盒内、不被盒边界裁掉 ⇒ 有符号距离与窗口无关
 * （{@code check} 用换窗回归实测这一点，不是靠推理）。</p>
 *
 * <h3>★ 性能：只做必要缓存，不立优化项目</h3>
 * <ul>
 *   <li>每个 tile 把全部 world 折线拆成<b>线段</b>，按格（site）索引，并给每条线段与每个格
 *       存 bbox；查询时 bbox 距离 &gt; 120 直接跳过 —— 该段对 {@code progress} 的贡献<b>恒为
 *       精确 0</b>，所以跳过不改变产出（不是"用 edge 常数近似距离"）。</li>
 *   <li>同点复用：{@code sample(x,z)} 缓存最近一次 (x,z) 的完整结果，
 *       故 {@code e()} 与 {@code weights()} 连续调用同点时只算一次。</li>
 *   <li>tile 几何按世界 tile 键做 LRU（{@value #CACHE_TILES} 个），不整幅常驻。</li>
 * </ul>
 *
 * <h3>⛔ 继承的未证事项（不承诺无限世界正确）</h3>
 * <p>{@code DoubleBoundaryGeometry} 的 world 折线来自反 warp 牛顿解：残差小<b>不证明</b>
 * {@code (x,z)↦(u,v)} 是单射（多根时每个根残差都可以很小）。因此本类的 world 几何是<b>近似</b>，
 * 而且"世界单射"未被证明 ⇒ <b>不承诺</b>无限世界的正确性，只承诺"在本机实测点上与
 * {@code geometry.query()} 逐位一致"。详见 check 的 {@code limits.txt}。</p>
 *
 * <p>只读：不修改任何既有类、不写回生产对象、不加 {@code -D} 开关、不插新 seed。</p>
 */
public final class DoubleFullMorphLand {

    // ============================================================ 常量

    /** 世界固定 tile 边长（块）。 */
    static final double TILE_BLOCKS = 1024.0;
    /**
     * 查询圆半径（块）。下界 = tile 半对角 {@code √2·512 = 724.0779...}；
     * 取 728 使 {@code (半径 + 120) − 半对角 ≈ 123.9 > 120}，即 ±120 权重带整体落在几何盒内。
     */
    static final double QUERY_RADIUS = 728.0;
    /** {@link DoubleBoundaryGeometry#of} 的站点窗口半宽下限（格）；实际值由闭式界自动放大。 */
    static final int REQ_HALF = 4;
    /** world 折线弦偏差（块）。 */
    static final double CHORD_TOL = 0.02;
    /** world 折线单段最大长度（块），防中点采样漏掉 S 形。 */
    static final double MAX_SEG_BLOCKS = 2.0;
    /** tile 几何 LRU 容量。1401² 图跨 2×2 tile，留 8 个足够且不会爆内存。 */
    static final int CACHE_TILES = 8;
    static final int ENTRY_COUNT = RecipeMorphCatalog.ENTRY_COUNT;
    /** 覆盖层用的旧台地条目下标（= {@code unmixable[0]}）。 */
    static final int OLD_COVERAGE_ENTRY = RecipeMorphCatalog.IDX_PLATEAU;
    /** 权重带半宽（块），直接取几何口径，不另写常数。 */
    private static final double BAND = DoubleBoundaryGeometry.BAND_HALF_BLOCKS;
    /** 每 cell 有序线段的分块大小（连续段数）；分块 bbox 粗剔除用。 */
    private static final int CHUNK_SEGMENTS = 16;

    // ============================================================ 状态

    private final RecipeMorphCatalog catalog;
    private final float regionScale;
    private final DoubleMorphEngine engine;
    private final CombineCandidate original;
    private final LandRegionContext context;
    private final RefNoise.Node maskNode;
    /** 实际覆盖族 {@code unmixable[0]}；反射失败为 null（退回 catalog 的 oldReference）。 */
    private final CombineCandidate.Family oldCoverageFamily;
    private final double blendLow;
    private final double blendHigh;
    // sample/tileAt/stats/dominantHistogram share this instance's monitor: LinkedHashMap.get
    // mutates access order, and the last-point cache and counters must move atomically with it.
    private final Map<Long, Tile> tiles;
    private final int[] dominantHistogram = new int[ENTRY_COUNT];

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private Sample cached;
    private long tilesBuilt;
    private long tileEvictions;
    private long points;
    private long cachedPoints;

    // ============================================================ 构造

    /**
     * @param catalog 18 条目目录；正典种子保留 Gallery 路径，其他种子使用显式种子上下文
     */
    public DoubleFullMorphLand(RecipeMorphCatalog catalog) {
        this(catalog, RegionPartition.REGION_SCALE);
    }

    DoubleFullMorphLand(RecipeMorphCatalog catalog, float regionScale) {
        if (!(regionScale > 0) || !Float.isFinite(regionScale)) throw new IllegalArgumentException("region scale");
        this.regionScale = regionScale;
        if (catalog == null) {
            throw new IllegalArgumentException("catalog is required");
        }
        if (catalog.seed() != RecipeMorphCatalog.GALLERY_SEED) {
            throw new UnsupportedOperationException("isolated double candidate mirrors the frozen Gallery arm only:"
                    + " seed=" + catalog.seed());
        }
        this.catalog = catalog;
        this.engine = new DoubleMorphEngine(DoubleLandRecipe.all(catalog), catalog.seed());
        this.original = catalog.original();
        this.context = null;
        if (this.original == null) {
            throw new IllegalStateException("original() returned null on the Gallery seed");
        }
        int slots = slotCountOf(original);
        if (slots != catalog.slotCount()) {
            throw new IllegalStateException("geometry slot count " + slots
                    + " != catalog slot count " + catalog.slotCount());
        }
        this.maskNode = asNode(read(original, "blender"), "mask");
        this.blendLow = combineConstant("MTN_BLEND_LOW");
        this.blendHigh = combineConstant("MTN_BLEND_HIGH");
        this.oldCoverageFamily = oldCoverageFamilyOf(original);
        this.tiles = tileCache();
    }

    DoubleFullMorphLand(LandRegionContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.catalog = context.catalog();
        this.regionScale = context.scale();
        this.engine = new DoubleMorphEngine(DoubleLandRecipe.all(catalog), catalog.seed());
        this.original = null;
        this.maskNode = context.mask();
        this.blendLow = context.blendLow();
        this.blendHigh = context.blendHigh();
        this.oldCoverageFamily = context.oldCoverage();
        this.tiles = tileCache();
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

    // ============================================================ 公开 API

    /**
     * 区域高度（覆盖层<b>之前</b>）：18 条目 morph 引擎在"{@code site 权重 → recipe 权重}"下的输出。
     *
     * <p>等价于旧 {@code CombineCandidate.debugRegionHeight} 的<b>位置</b>，但类型来源换成 morph
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

    /** {@code e = max(GROUND_E + coveredHeight·E_SPAN, 0)}（与 {@code CombineCandidate.e} 同式）。 */
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
    public synchronized Sample sample(double x, double z) {
        if (cached != null && x == lastX && z == lastZ) {
            cachedPoints++;
            return cached;
        }
        Tile tile = tileFor(x, z);
        double[] merged = mergeToEntries(tile.entryOfSite(), tile.weights(x, z));
        double region = heightForMerged(x, z, merged);
        double select = maskValue(x, z);
        double covered = overlay(region, select, select < blendLow ? Double.NaN : oldCoverageHeight(x, z));
        double e = Math.max(CombineCandidate.GROUND_E + covered * CombineCandidate.E_SPAN, 0.0);
        int dom = dominant(merged);
        Sample s = new Sample(region, covered, e, select, alphaOf(select), dom, merged,
                activeCount(merged));
        lastX = x;
        lastZ = z;
        cached = s;
        points++;
        dominantHistogram[dom]++;
        return s;
    }

    /** 一次性计数的运行统计（minimal；只给 check / renderer 记账用）。 */
    public synchronized Stats stats() {
        return new Stats(tilesBuilt, tileEvictions, tiles.size(), points, cachedPoints,
                context == null && oldCoverageFamily != null, blendLow, blendHigh);
    }

    /** 取世界点所属 tile 的索引（建或用缓存）；{@code check} 用它逐点种纯权重点。 */
    synchronized Tile tileAt(double x, double z) {
        return tileFor(x, z);
    }

    /** 主导条目直方图副本（诊断；不影响产出）。 */
    public synchronized int[] dominantHistogram() {
        return dominantHistogram.clone();
    }

    // ============================================================ 高度链

    /**
     * 条目权重 → 高度。单一 active 条目走 {@link RecipeJunctionMorph} 的 <b>onehot</b> 重载
     * （{@code ids/weights} 数组），<b>不</b>新造"成品高度 lerp"层。
     */
    double heightForMerged(double x, double z, double[] w) {
        int only = singleActive(w);
        if (only >= 0 && w[only] == 1.0) {
            return engine.sampleLand(x, z, engine.merge(new int[]{only}, new double[]{1.0}));
        }
        return engine.sampleLand(x, z, w);
    }

    /** 覆盖层：逐行复刻 {@code CombineCandidate.Blender.apply}（含 float alpha 与分支顺序）。 */
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

    /** 旧台地高度：优先用真实覆盖族（{@code unmixable[0]}），否则退回 catalog oldReference。 */
    double oldCoverageHeight(double x, double z) {
        if (oldCoverageFamily == null) throw new UnsupportedOperationException("missing old coverage family node");
        Object family = oldCoverageFamily;
        if (family.getClass().isSynthetic()) {
            java.lang.reflect.Field[] fields = family.getClass().getDeclaredFields();
            if (fields.length != 1) throw new UnsupportedOperationException("coverage lambda captures=" + fields.length);
            try { fields[0].setAccessible(true); family = fields[0].get(family); }
            catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
        if (!(family instanceof PlateauCandidate)) throw new UnsupportedOperationException("coverage family: " + family.getClass().getName());
        return DualCoordinateCall.precise((RefNoise.Node) RecipeMorphCatalog.field(family, "height"), x, z);
    }

    /** 覆盖层重放：给一个"区域高度"，按原 Blender 口径返回覆盖后高度（check 对拍用）。 */
    double replayOverlay(double regionHeight, double x, double z) {
        double select = maskValue(x, z);
        return overlay(regionHeight, select, select < blendLow ? Double.NaN : oldCoverageHeight(x, z));
    }

    double maskValue(double x, double z) {
        return DualCoordinateCall.precise(maskNode, x, z);
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
     * <p>为什么必须"恰为 1"：{@link RecipeJunctionMorph} 的契约是 {@code |Σw−1| ≤ 1e-9}，
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
        long key = DoubleBoundaryGeometry.pack(tx, tz);
        Tile t = tiles.get(key);
        if (t == null) {
            long t0 = System.nanoTime();
            DoubleBoundaryGeometry g = context != null
                    ? DoubleBoundaryGeometry.of(context, center(tx), center(tz), QUERY_RADIUS, REQ_HALF, CHORD_TOL)
                    : regionScale == RegionPartition.REGION_SCALE
                        ? DoubleBoundaryGeometry.of(original, center(tx), center(tz), QUERY_RADIUS, REQ_HALF, CHORD_TOL)
                        : DoubleBoundaryGeometry.of(original, center(tx), center(tz), QUERY_RADIUS, REQ_HALF, CHORD_TOL, regionScale);
            g.setMaxSeg(MAX_SEG_BLOCKS);
            t = new Tile(catalog, g, tx, tz, (System.nanoTime() - t0) / 1_000_000L);
            tiles.put(key, t);
            tilesBuilt++;
        }
        return t;
    }

    static int tileIndex(double v) {
        return (int) Math.floor(v / TILE_BLOCKS);
    }

    static double center(int tileIndex) {
        return (tileIndex + 0.5) * TILE_BLOCKS;
    }

    /** 用<b>外部给</b>的局部几何包一个 tile（check 的"换窗口"回归用；不改任何生产对象）。 */
    static Tile wrap(RecipeMorphCatalog catalog, DoubleBoundaryGeometry g) {
        return new Tile(catalog, g, tileIndex(g.centerX()), tileIndex(g.centerZ()), 0L);
    }

    // ============================================================ 反射（只读）

    private static Object read(Object target, String name) {
        if (target == null) {
            throw new IllegalStateException("cannot read '" + name + "' of null");
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // 继续向父类找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("reflect " + name + " on " + c.getName(), e);
            }
        }
        throw new IllegalStateException("no field '" + name + "' on " + target.getClass().getName());
    }

    private static RefNoise.Node asNode(Object owner, String name) {
        Object o = read(owner, name);
        if (!(o instanceof RefNoise.Node n)) {
            throw new IllegalStateException("field '" + name + "' is not a RefNoise.Node: " + o);
        }
        return n;
    }

    /** 反射读 {@code CombineCandidate} 的私有 static final float 常量（不复制字面量）。 */
    static double combineConstant(String name) {
        try {
            Field f = CombineCandidate.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read CombineCandidate." + name, e);
        }
    }

    private static int slotCountOf(CombineCandidate combine) {
        Object slots = read(combine, "slots");
        return slots instanceof Object[] arr ? arr.length : 0;
    }

    /** 实际覆盖族 {@code unmixable[0]}（Blender 手里那个同一对象）；读不到返回 null。 */
    private static CombineCandidate.Family oldCoverageFamilyOf(CombineCandidate combine) {
        try {
            Object fams = read(combine, "unmixable");
            if (fams instanceof CombineCandidate.Family[] arr && arr.length > 0) {
                return arr[0];
            }
            return null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // ============================================================ 数据载体

    /**
     * 一次查询的全部结果。
     *
     * @param weight 长度 18 的条目权重（<b>内部数组</b>；{@link DoubleFullMorphLand#weights} 才返回副本）
     */
    public record Sample(double regionHeight, double coveredHeight, double e, double mask, double alpha,
                         int dominantEntry, double[] weight, int activeEntries) {

        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "Sample(region=%.6f covered=%.6f e=%.6f mask=%.4f alpha=%.4f dom=%d active=%d)",
                    regionHeight, coveredHeight, e, mask, alpha, dominantEntry, activeEntries);
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
        private final DoubleBoundaryGeometry geometry;

        Tile(RecipeMorphCatalog catalog, DoubleBoundaryGeometry g, int tx, int tz, long millis) {
            this.geometry = g;
            this.tileX = tx;
            this.tileZ = tz;
            this.centerX = g.centerX();
            this.centerZ = g.centerZ();
            this.buildMillis = millis;
            List<DoubleBoundaryGeometry.Site> sites = g.sites();
            this.siteCount = sites.size();
            this.slot = new int[siteCount];
            this.entry = new int[siteCount];
            this.siteU = new double[siteCount];
            this.siteV = new double[siteCount];
            for (int i = 0; i < siteCount; i++) {
                DoubleBoundaryGeometry.Site s = sites.get(i);
                siteU[i] = s.vx();
                siteV[i] = s.vz();
                slot[i] = g.slotOfCell(s.cellX(), s.cellZ());
                entry[i] = slot[i] < 0 ? -1 : catalog.slotEntry(slot[i]);
                if (entry[i] < 0) {
                    throw new IllegalStateException("site " + s + " has slot " + slot[i]
                            + " outside the catalog table");
                }
            }
            List<DoubleBoundaryGeometry.Edge> edges = g.edges();
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

        private static int countSegments(List<DoubleBoundaryGeometry.Edge> edges) {
            int n = 0;
            for (DoubleBoundaryGeometry.Edge e : edges) {
                n += Math.max(0, e.wx.length - 1);
            }
            return n;
        }

        /** 拆线段 + 按格索引 + 每格并集 bbox。 */
        private void fillIndex(List<DoubleBoundaryGeometry.Edge> edges) {
            int[] perCell = new int[siteCount];
            for (DoubleBoundaryGeometry.Edge e : edges) {
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
            for (DoubleBoundaryGeometry.Edge e : edges) {
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

        DoubleBoundaryGeometry geometry() {
            return geometry;
        }

        /**
         * 每 site 权重：与 {@link DoubleBoundaryGeometry#query} <b>逐位一致</b>
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
                wt[i] = DoubleBoundaryGeometry.progress(sd);
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
                double f = DoubleBoundaryGeometry.potential(siteU[i], siteV[i], p[0], p[1]);
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
                    double d = DoubleBoundaryGeometry.segmentDistance(x, z, segX0[k], segZ0[k],
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
