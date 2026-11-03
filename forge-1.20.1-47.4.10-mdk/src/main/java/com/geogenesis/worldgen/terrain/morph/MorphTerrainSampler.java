package com.geogenesis.worldgen.terrain.morph;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <b>morph 地形的唯一入口</b>：{@code sampleE(wuX, wuZ)} 给出 e，{@code height} 给出世界 Y。
 *
 * <h3>用法</h3>
 * <pre>
 *   TerrainParams params = ...;                       // 复用项目现有的参数记录
 *   MorphTerrainSampler s = new MorphTerrainSampler(seed, params, -64, 320);
 *   double e = s.sampleE(wuX, wuZ);                   // e ∈ [−1, +1]，0 = 海平面
 *   double y = s.height(wuX, wuZ);                    // = curve.heightFromE(sampleE(wuX, wuZ))
 * </pre>
 *
 * <h3>★ 坐标单位是 <b>wu</b>，不是块</h3>
 * <p>两个方法的入参都是<b>世界单位（wu）</b>，与 {@link ContinentField#sample} 同一口径。
 * 本项目把"块 → wu"的除法收在门面层（{@code GeoGenesisTerrain.toWu}：{@code block / horizontalScale}，
 * {@code HS=1} 时恒等），本类<b>不</b>做这个换算，也不知道 {@code horizontalScale} 是多少。
 * 调用方必须<b>先</b>换算再进来 —— 直接把块坐标传进来不会报错，只会让地形整体缩小
 * {@code horizontalScale} 倍，而且看起来仍然"像正常地形"。</p>
 *
 * <h3>★ {@code height} 不做额外换算</h3>
 * <p>{@code height(x,z)} <b>就是</b> {@code HeightCurve.heightFromE(sampleE(x,z))}：
 * 曲线由本类持有（{@code params} + {@code minY/maxY} 构造），调用方不需要（也不应该）
 * 再乘一次 {@code verticalScale}、再加一次 {@code seaLevel}。重复换算会让地形整体错位，
 * 而且错得很"像"正常地形，很难发现。</p>
 *
 * <h3>★ 一条链（与正典图同源）</h3>
 * <pre>
 *   ContinentField.seed(seed)                  ← 大陆性 c ∈ [−1,1]
 *   MorphCatalog(seed)                          ← 18 条目的族种子 = seed + 固定偏移
 *   MorphAssembly.of(seed)                      ← 分区 / 45 票槽 / 山形遮罩 / 台地覆盖
 *   MorphLandField(catalog, assembly, scale)    ← 陆地 morph 场（tile 局部 Voronoi 几何）
 *   MorphOceanAssembler(land::e, continent::sample, seed + 3).enableCoastalDetail(seed)
 *   HeightCurve(params, minY, maxY)
 * </pre>
 * <p>顺序与种子偏移必须与正典一致：{@code +3} 是深海噪声、{@code enableCoastalDetail(seed)}
 * 用<b>原</b>种子（不是 +3）。任何一处偏移改动都会换图。</p>
 *
 * <h3>★ 海岸线位置<b>不是</b> {@code c = 0}</h3>
 * <p>这是最容易误读的一点。{@code c = 0} 只是大陆场的零点，<b>不等于</b>实际海岸线。
 * {@link MorphOceanAssembler} 会把 {@code c} 线性映射成
 * {@code edge = (c + 1) / 2}，再按三条控制点把 {@code edge} 分成"深海 → 浅陆架 → 岸段"，
 * 岸段内部还叠了随陆高增长的岸宽与 cliff 因子。于是：</p>
 * <ul>
 *   <li><b>水面位置</b>（{@code e = 0}）由<b>岸段剖面的零点</b>决定，与 {@code c = 0} 有一处
 *       与陆高相关的偏移；</li>
 *   <li>要判"这里是不是海"，请读 {@link #sea}（它读<b>实际高度</b>，不是阈值）；</li>
 *   <li>要判陆地/海洋的粗分，{@code sampleE > 0} 才是权威判据。</li>
 * </ul>
 *
 * <h3>★ 线程模型</h3>
 * <p>{@link MorphLandField} 内部有 tile LRU 与 last-point 缓存，全部由实例监视器保护，
 * 并发调用者<b>互相阻塞（互斥）</b>。{@link ContinentField} 与
 * {@link MorphOceanAssembler} 的噪声节点是纯函数，可安全并发读。
 * <b>本类不提供取消 API</b>：无法中断进行中的 tile 构建。</p>
 *
 * <h3>★ 未证事项（必须知道）</h3>
 * <ul>
 *   <li>world 折线来自反 warp 牛顿解，残差小<b>不证明</b> {@code (x,z)↦(u,v)} 是单射
 *       ⇒ 世界几何是<b>近似</b>，"无限世界"未被证明正确。</li>
 *   <li>本类只做地形 <b>e/Y</b>：不含洞穴、矿脉、含水层、群系、装饰、结构与水文。
 *       把它接进 {@code CellGenerator} 之前，那些系统都还悬空。</li>
 * </ul>
 */
public final class MorphTerrainSampler {

    /** 陆地场默认区域尺度（块），与参考预设 {@code terrainRegionSize} 同值。 */
    static final float REGION_SCALE = 1200f;
    /** 深海噪声的种子偏移（与正典口径一致）。 */
    static final long OCEAN_SEED_OFFSET = 3L;

    private final long seed;
    private final MorphCatalog catalog;
    private final MorphAssembly assembly;
    private final MorphLandField land;
    private final ContinentField continent;
    private final MorphOceanAssembler ocean;
    private final HeightCurve curve;

    /**
     * @param seed  世界种子
     * @param params 地形参数（由 {@code GeoGenesisConfig.buildParams()} 或
     *               {@link TerrainParams#defaults()} 提供；本类不读配置、不持有静态状态）
     * @param minY  世界最小 Y（仅用于 {@code height} 的映射）
     * @param maxY  世界最大 Y（同上）
     */
    public MorphTerrainSampler(long seed, TerrainParams params, int minY, int maxY) {
        this(seed, params, minY, maxY, REGION_SCALE);
    }

    /**
     * 可指定区域尺度的构造器：尺度越小 ⇒ Voronoi 类型格越密、地貌斑块越碎。
     *
     * @param regionScale 区域尺度（块），必须 &gt; 0
     */
    public MorphTerrainSampler(long seed, TerrainParams params, int minY, int maxY, float regionScale) {
        if (params == null) throw new IllegalArgumentException("params is required");
        if (!(regionScale > 0) || !Float.isFinite(regionScale)) {
            throw new IllegalArgumentException("region scale must be finite and > 0: " + regionScale);
        }
        this.seed = seed;

        this.catalog = new MorphCatalog(seed);
        this.assembly = MorphAssembly.of(seed);
        this.land = new MorphLandField(catalog, assembly, regionScale);

        // 大陆性与正典同源：同一个 ContinentField 实现、同一个 seed、同一个 params。
        this.continent = new ContinentField(params);
        this.continent.seed(seed);

        // 深海噪声用 seed+3（正典口径）；近岸细节噪声用原 seed。
        this.ocean = new MorphOceanAssembler(land::e, continent::sample, seed + OCEAN_SEED_OFFSET)
                .enableCoastalDetail(seed);

        this.curve = new HeightCurve(params, minY, maxY);
    }

    // ============================================================ 主查询

    /**
     * 世界点的高度场值 {@code e ∈ [−1, +1]}（0 = 海平面，负 = 水下）。
     *
     * <p><b>入参单位 = wu</b>（与 {@link ContinentField#sample} 同一口径），<b>不是块</b>。
     * 块 → wu 的除法在门面层（{@code GeoGenesisTerrain.toWu}），必须在<b>调用本方法之前</b>
     * 完成 —— 本类不知道 {@code horizontalScale}，也不该知道。</p>
     *
     * <p>⚠ {@code e > 0} 才是"陆地"的权威判据。{@code c = 0} <b>不是</b>海岸线：
     * 装配器把 {@code c} 映射成 {@code edge = (c+1)/2} 后再走三段剖面，
     * 水面位置与 {@code c = 0} 之间存在随陆高变化的偏移（见类注释）。</p>
     */
    public double sampleE(double wuX, double wuZ) {
        return ocean.e(wuX, wuZ);
    }

    /**
     * 世界点的高度 Y：{@code curve.heightFromE(sampleE(wuX, wuZ))}，<b>不做任何额外换算</b>。
     *
     * <p>入参单位同样是 <b>wu</b>。</p>
     */
    public double height(double wuX, double wuZ) {
        return curve.heightFromE(sampleE(wuX, wuZ));
    }

    // ============================================================ 分段查询

    /**
     * 陆地侧 e（<b>未经海洋装配</b>）：与 {@link #sampleE} 的区别只在
     * 海岸带被换成了陆地层的原始值。用于区分"陆地配方输出"与"海陆缝合结果"。
     */
    public double landE(double wuX, double wuZ) {
        return land.e(wuX, wuZ);
    }

    /**
     * 大陆性 {@code c ∈ [−1,1]}（负 = 偏海洋侧，正 = 偏陆地侧）。
     *
     * <p>⚠ 这只是大陆场的零点约定，<b>不是</b>海岸线：实际水面由
     * {@link MorphOceanAssembler} 的岸段剖面决定（见类注释）。判"是否水面以上"请用
     * {@link #sampleE} 的符号，判分段请用 {@link #sea}。</p>
     */
    public double continentality(double wuX, double wuZ) {
        return continent.sample(wuX, wuZ);
    }

    /** 海洋分段分类（读<b>实际高度</b>，不是固定 edge 阈值 —— 见装配器的类注释）。 */
    public MorphOceanAssembler.Sea sea(double wuX, double wuZ) {
        return ocean.sea(wuX, wuZ);
    }

    /** 覆盖层 select 值 ∈ [0,1]（0 = 纯区域地貌、1 = 纯山地）。 */
    public double mountainMask(double wuX, double wuZ) {
        return land.mask(wuX, wuZ);
    }

    /** 基础地貌列，分类只提供语义，绝不反馈高度。坐标仍是wu。 */
    public com.geogenesis.worldgen.core.BaseColumn sampleColumn(double x, double z) {
        var c = new com.geogenesis.worldgen.core.BaseColumn();
        c.e = sampleE(x, z);
        c.height = curve.heightFromE(c.e);
        c.continent = continentality(x, z);
        c.landE = landE(x, z);
        c.coastCoord = (c.continent + 1.0) * 0.5;
        c.shape = c.landE * 2.0 - 1.0;
        c.typeWeights = new double[com.geogenesis.worldgen.terrain.TerrainClass.COUNT];
        if (c.e < 0.0) {
            c.terrainType = c.e > -0.08 ? com.geogenesis.worldgen.terrain.TerrainClass.CONTINENTAL_SHELF
                    : c.e < -0.18 ? com.geogenesis.worldgen.terrain.TerrainClass.DEEP_OCEAN
                    : com.geogenesis.worldgen.terrain.TerrainClass.OCEAN;
            c.typeWeights[c.terrainType.ordinal()] = 1.0;
            c.oceanWeight = 1.0;
            c.oceanE = c.e;
        } else {
            MorphLandField.Sample s = land.sample(x, z);
            for (int i = 0; i < s.weight().length; i++) {
                addType(c.typeWeights, catalog.entry(i), x, z, s.weight()[i] * (1.0 - s.alpha()));
            }
            c.typeWeights[com.geogenesis.worldgen.terrain.TerrainClass.PLATEAU.ordinal()] += s.alpha();
            int best = com.geogenesis.worldgen.terrain.TerrainClass.PLAIN.ordinal();
            for (int i = 0; i < c.typeWeights.length; i++) {
                if (c.typeWeights[i] > c.typeWeights[best]) best = i;
            }
            c.terrainType = com.geogenesis.worldgen.terrain.TerrainClass.byId(best);
        }
        c.variantTerrain = c.terrainType;
        return c;
    }

    private static void addType(double[] out, MorphCatalog.Entry entry, double x, double z, double w) {
        if (w == 0.0) return;
        if (entry.isComposite()) {
            double alpha = entry.alpha(x, z);
            addType(out, entry.left(), x, z, w * (1.0 - alpha));
            addType(out, entry.right(), x, z, w * alpha);
            return;
        }
        var type = switch (entry.kind()) {
            case PLATEAU, BADLANDS -> com.geogenesis.worldgen.terrain.TerrainClass.PLATEAU;
            case MOUNTAIN -> com.geogenesis.worldgen.terrain.TerrainClass.MOUNTAINS;
            case LOW -> entry.index() <= 1 ? com.geogenesis.worldgen.terrain.TerrainClass.PLAIN
                    : com.geogenesis.worldgen.terrain.TerrainClass.HILLS;
        };
        out[type.ordinal()] += w;
    }

    // ============================================================ 元信息

    public long seed() { return seed; }

    /** 高度曲线（{@code e ↔ Y} 的唯一换算点）。 */
    public HeightCurve heightCurve() { return curve; }

    /** 陆地 morph 场（tile 缓存/权重查询/统计）。 */
    public MorphLandField landField() { return land; }

    /** 海陆装配器（深海噪声/近岸剖面/分类）。 */
    public MorphOceanAssembler oceanAssembler() { return ocean; }

    /** 大陆场（海岸线来源）。 */
    public ContinentField continentField() { return continent; }

    /** 18 条目目录（条目/票槽查询）。 */
    public MorphCatalog catalog() { return catalog; }
}
