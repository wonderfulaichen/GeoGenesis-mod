package com.geogenesis.worldgen.terrain.morph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>真实区域边界的局部 Voronoi 几何</b>：给出带世界距离的有符号距离与平滑权重。
 *
 * <h3>★ u 平面精确性（代数恒等式，不是近似）</h3>
 * <pre>
 *   f_i = |u-u_i| + |v-v_i| + (u-u_i)² + (v-v_i)²
 *       = (u²+v²) + A_i·u + B_i·v + C_i
 * </pre>
 * 二次项 {@code u²+v²} 对所有站点相同 ⇒ 在差值 {@code f_i - f_j} 中完全消去。
 * 把 u,v 平面按全部站点的 u 坐标 / v 坐标切成矩形网格后，矩形内每个 {@code |·|} 符号恒定，
 * {@code f_i - f_j} <b>严格仿射</b>，系数解析精确（{@code a = sgn(u-u_i) - sgn(u-u_j) - 2(u_i-u_j)}）
 * ⇒ 该矩形内 Voronoi 胞是<b>精确凸多边形</b>（半平面交）。此结论只依赖 NATURAL 的可分性，
 * <b>不需要</b>任何 Lipschitz 估计。
 *
 * <h3>候选站点的保守界</h3>
 * <p>世界查询盒外扩 120 块，再用已知 warp 位移上界扩张到 u 平面盒。
 * {@link #selectSites()} 对盒中心格的站点求精确分轴势上界 F；任何可能获胜的站点距盒的
 * 轴向距离不超过 √F，再计入站点抖动界确定有限枚举范围。枚举内以精确分轴势下界大于 F
 * 作排除；不依赖"截断后采样未见赢家"的推断。</p>
 *
 * <h3>★ 世界几何是<b>近似</b>（必须明说）</h3>
 * <p>u 平面的边是精确的；映回世界用<b>反 warp 阻尼牛顿</b>，只能做到残差 ε。本类：</p>
 * <ul>
 *   <li>强制 {@code ε ≤ invertTol}（默认 0.02 块），否则 {@link #build()} 抛异常并阻断；</li>
 *   <li>world 折线按弦偏差与最大段长双重加密（{@code MorphLandField} 把最大段长收到 2 块）；</li>
 *   <li>{@code signedDistance} 是到该折线的距离。反解残差和密度差不是严格的世界距离误差上界；
 *       warp 是否单射<b>尚未证明</b>，小残差也不能排除选到多根中的另一支。</li>
 * </ul>
 *
 * <h3>★ 只读</h3>
 * <p>分区/槽表由构造参数显式给出（{@link MorphRegionContext} 或 {@link MorphRegionPartition}），
 * 本类不反射任何外部对象、不写回、不改分区默认行为。</p>
 */
public final class MorphBoundaryGeometry {

    /** 抖动系数（{@code MorphRegionPartition} 的 {@code vec*0.7f}）。 */
    static final float CELL_JITTER = 0.7f;
    /**
     * {@code MorphCellTable.CELL} 的 256 项实测值域 {@code [0.050, 0.950]}（无负值）。
     * 故抖动 {@code = v·0.7 ∈ [0.035, 0.665]} ⊂ {@code [0, 0.7]}。
     * 下面用 {@code [0, 0.7]} 这个<b>更宽</b>的区间做枚举界 —— 宽区间必然覆盖窄区间，
     * 所以枚举不会漏；若将来表里出现负值（{@code [−0.7, 0.7]}），必须换成对称界。
     */
    static final double JITTER_MIN = 0.0;
    static final double JITTER_MAX = (double) CELL_JITTER;
    /** warp 幅度（{@code MorphRegionPartition:28-29}：{@code (n-0.5)*200}）。 */
    public static final float WARP_STRENGTH = 200f;
    /** |warp 位移| 上界（块）：{@code 0.5*200 = 100}。这是 {@code (n-0.5)*200} 与 {@code n∈[0,1]} 的直接推论。 */
    public static final double WARP_DISPLACEMENT_BLOCKS = 100.0;
    /** 定稿形态过渡总宽 / 单侧（块）。 */
    public static final double BAND_TOTAL_BLOCKS = 240.0;
    public static final double BAND_HALF_BLOCKS = 120.0;
    /** 世界查询盒在 {@code radius} 之外再放宽的余量（块）：覆盖 ±120 权重带到远端。 */
    public static final double HALO_BLOCKS = 120.0;
    /** world polyline 弦偏差默认值（块）。 */
    public static final double CHORD_DEFAULT = 0.5;
    /** world polyline 单段最大长度默认值（块）：防中点采样漏掉 S 形。 */
    public static final double MAXSEG_DEFAULT = 8.0;
    /**
     * 反解残差容限默认值（块）。超限即阻断。
     *
     * <p><b>残差口径</b> = double 前向 {@link MorphGeometryTransform#forward}，见
     * {@link #residualBlocks(double, double, double, double)}。
     * 早期实现用 {@link #warpForward}（world 先降 {@code float}）衡量，那条标尺的地板随
     * |坐标| 线性爬升（约 26 万块处即 0.0313 &gt; 0.02），远处必然误判失败且低估真实误差。
     * 判据值仍是 0.02 块，改的是「被测量的东西」。</p>
     */
    public static final double INVERT_TOL_DEFAULT = 0.02;

    private static final double U_EPS = 1e-12;

    // ---------------------------------------------------------------- 生产对象（只读）
    private final MorphRegionPartition partition;
    private final int partitionSeed;
    private final float frequency;
    /**
     * double 前向/解析 Jacobian 原语（复用 {@link MorphGeometryTransform}，不另写一份 warp 数学）。
     * <b>只用于反解的迭代与残差</b>；{@link #warpForward} 仍是 float 口径，生产对拍不受影响。
     */
    private final MorphGeometryTransform continuous;
    private final MorphNoise.Node warpX, warpZ;
    private final int slotCount;
    private final Object[] slotCats;
    private final long ownerSeed;

    // ---------------------------------------------------------------- 构造参数
    private final double centerX, centerZ, radius;
    private final double queryHalf;          // radius
    private final double boxHalfWorld;       // radius + HALO
    private final int requestedHalfWidth;    // reqHalf 下限（格）
    private double boxHalfU;                 // 由 boxHalfWorld 解析推出（格）
    private double chordTol = CHORD_DEFAULT;
    private double maxSeg = MAXSEG_DEFAULT;
    private double invertTol = INVERT_TOL_DEFAULT;

    // ---------------------------------------------------------------- 场景
    private final double cu, cv;             // 查询盒中心在 u 平面的坐标
    private final double boxU0, boxU1, boxV0, boxV1;

    private final List<Site> sites = new ArrayList<>();
    private final Map<Long, Site> cellIndex = new LinkedHashMap<>();
    private final List<WarpEdge> warpEdges = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<List<Integer>> edgesOfCell = new ArrayList<>();

    // ---------------------------------------------------------------- 证书结果
    //
    // 这些量<b>只在 build() 里用于自检与阻断</b>（枚举规模、排除数、残差），
    // 不参与任何产出。不对外暴露：它们的存在只会让人以为"调了有用"。
    private double maxPotentialInBox = 0;    // F：盒中心格站点的 fUpper
    private double maxSiteRadiusCells = 0;   // dmax = √F（格）
    private long enumCells = 0;              // 枚举的整数格数
    private double minExcludedLower = Double.POSITIVE_INFINITY;
    private int excludedSites = 0;
    private int includedSites = 0;
    private long exclusionTests = 0;
    private double maxResidual = 0;
    private int rectCount = 0;
    private long candidatePairs = 0;
    private int degeneratePairs = 0;

    private MorphBoundaryGeometry(MorphRegionPartition partition, int seed, float frequency,
                                  MorphNoise.Node warpX, MorphNoise.Node warpZ,
                                  int slots, Object[] cats, long ownerSeed,
                                  double cx, double cz, double r, int reqHalf) {
        this.partition = partition;
        this.partitionSeed = seed;
        this.frequency = frequency;
        this.continuous = MorphGeometryTransform.of(partition);
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.slotCount = slots;
        this.slotCats = cats;
        this.ownerSeed = ownerSeed;
        this.centerX = cx;
        this.centerZ = cz;
        this.radius = r;
        this.queryHalf = r;
        this.boxHalfWorld = r + HALO_BLOCKS;
        this.requestedHalfWidth = Math.max(2, reqHalf);

        double f = frequency;
                this.cu = cx * (double) f;      // 盒是<b>解析</b>给出的（x·f），不用 c 以免把 c 当盒心
        this.cv = cz * (double) f;
        // 世界半宽 → u 半宽：u = (x + o)·f，|o| ≤ 100 ⇒ |Δu| ≤ (|Δx| + 200)·f
        this.boxHalfU = (boxHalfWorld + 2.0 * WARP_DISPLACEMENT_BLOCKS) * f;
        this.boxU0 = cu - boxHalfU;
        this.boxU1 = cu + boxHalfU;
        this.boxV0 = cv - boxHalfU;
        this.boxV1 = cv + boxHalfU;
        build();
    }

    // ================================================================ 工厂

    /**
     * <b>主接口</b>：用一个现有分区构建局部几何，槽表由调用方直接给出。
     *
     * <p>完全由调用方给定中心与半径，不依赖任何"正典种子"常量 —— 每个种子走同一条路。</p>
     *
     * @param partition 分区（已由 {@code MorphRegionContext} 或调用方给定尺度）
     * @param cx        查询域中心世界 X（块）
     * @param cz        查询域中心世界 Z（块）
     * @param radius    查询域半径（块）；{@code query()} 只接受域内点
     * @param reqHalf   站点窗口半宽下限（格）；实际由闭式界自动放大，传小值即可
     * @param chord     world 折线弦偏差（块）；{@code <=0} 用默认 0.5
     * @param slots     票槽数（= 分区区域数，用于把格子映射回类别槽）
     * @param cats      每槽的类别名（只用于分类查询，可为 null）
     * @param ownerSeed 该几何所属世界的种子（只用于元信息/审计，不参与数值）
     */
    static MorphBoundaryGeometry of(MorphRegionPartition partition, double cx, double cz,
                                    double radius, int reqHalf, double chord,
                                    int slots, Object[] cats, long ownerSeed) {
        MorphBoundaryGeometry g = new MorphBoundaryGeometry(partition, partition.seed(),
                partition.frequency(), partition.warpX(), partition.warpZ(),
                slots, cats, ownerSeed, cx, cz, radius, reqHalf);
        if (chord > 0) g.setChordTol(chord);
        return g;
    }

    /** 同 {@link #of} 的尺度重载：分区先换成给定尺度（不修改原分区）。 */
    static MorphBoundaryGeometry of(MorphRegionPartition partition, float scale, double cx,
                                    double cz, double radius, int reqHalf, double chord,
                                    int slots, Object[] cats, long ownerSeed) {
        return of(partition.withScale(scale), cx, cz, radius, reqHalf, chord, slots, cats, ownerSeed);
    }

    /** 显式分区 + 完整槽表入口（{@link MorphRegionContext} 的推荐用法）。 */
    static MorphBoundaryGeometry of(MorphRegionContext context, double cx, double cz, double radius,
                                    int reqHalf, double chord) {
        Object[] cats = context.slotCategories();
        return of(context.partition(), cx, cz, radius, reqHalf, chord,
                cats.length, cats, context.seed());
    }

    // ================================================================ 元信息

    /** 该几何所属世界的种子（元信息/审计用，不参与数值）。 */
    public long ownerSeed() { return ownerSeed; }
    public int partitionSeed() { return partitionSeed; }
    public int slotCount() { return slotCount; }
    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }
    /** 有效查询半径（块）：{@code centerX/centerZ} 之外的点不在本场景覆盖范围内。 */
    public double queryRadius() { return queryHalf; }
    public List<Site> sites() { return sites; }
    public List<Edge> edges() { return edges; }
    /** 反解的最大残差（块）：{@link #build()} 已保证它 ≤ {@code invertTol}。 */
    public double maxWarpResidualBlocks() { return maxResidual; }

    public String[] slotCategories() {
        if (slotCats == null) return new String[0];
        String[] out = new String[slotCats.length];
        for (int i = 0; i < out.length; i++) out[i] = String.valueOf(slotCats[i]);
        return out;
    }

    public void setChordTol(double blocks) {
        this.chordTol = Math.max(1e-4, blocks);
        buildWorldEdges();
    }

    public void setMaxSeg(double blocks) {
        this.maxSeg = Math.max(1e-3, blocks);
        buildWorldEdges();
    }

    public void setInvertTol(double blocks) {
        this.invertTol = Math.max(1e-6, blocks);
        buildWorldEdges();
    }

    // ================================================================ 基础数学

    /** NATURAL 位势：{@code |dx|+|dy|+dx²+dy²}（{@code MorphRegionPartition:93}）。 */
    public static double potential(double su, double sv, double u, double v) {
        double dx = su - u, dy = sv - v;
        return Math.abs(dx) + Math.abs(dy) + dx * dx + dy * dy;
    }

    /** 点到线段距离（块）。 */
    public static double segmentDistance(double x, double z, double ax, double az,
                                         double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 > 0 ? ((x - ax) * dx + (z - az) * dz) / len2 : 0;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return Math.hypot(x - (ax + t * dx), z - (az + t * dz));
    }

    /** (cellX,cellZ) → 稳定 long 键。 */
    public static long pack(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    /** 生产 {@code edgeValue}（{@code MorphRegionPartition:121-130}）原样。 */
    static double edgeValue(double d1, double d2) {
        if (!(d2 > 0)) return 0;
        double v = d1 / d2 - 1;
        double edge = 1 - clampDouble(v, -1, 0);
        edge = Math.pow(edge, 1.5);
        if (edge < 0) return 0;
        if (edge > 0.5) return 1;
        return edge / 0.5;
    }

    /** 生产 {@code valCoord2D}（{@code MorphRegionPartition:133-138}）；<b>必须带 partition seed</b>。 */
    double valCoord2D(int x, int z) {
        int n = partitionSeed;
        n ^= 1619 * x;
        n ^= 31337 * z;
        return n * n * n * 60493 / 2147483648.0;
    }

    /** 站点（NATURAL 空间抖动格心）。 */
    public double[] siteXZ(int cellX, int cellZ) {
        float[] vec = MorphCellTable.CELL[MorphNoise.hash2D(partitionSeed, cellX, cellZ) & 0xFF];
        return new double[]{cellX + (double) vec[0] * CELL_JITTER, cellZ + (double) vec[1] * CELL_JITTER};
    }

    /**
     * 平滑权重：{@code smoothstep(clamp(0.5 + s/240))}；{@code NaN} ⇒ 0。
     *
     * <p>半宽 = {@link #BAND_HALF_BLOCKS}（120 块），总宽 240 ⇒ 与定稿的权重带一致。
     * {@code s} 是到该格 Voronoi 边界的<b>有符号距离</b>（块，赢家为正）。</p>
     */
    public static double progress(double s) {
        if (Double.isNaN(s)) return 0.0;
        double t = 0.5 + s / BAND_TOTAL_BLOCKS;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /**
     * 票槽下标：{@code round(regionId × (count−1))}，与 {@link MorphRegionPartition#selectIndex} 同式。
     *
     * <p>用 double 版 {@code regionId} 而不是先降 float —— 后者在 {@code regionId} 恰好落在
     * 两档中点时会因舍入方向不同而选到相邻槽位。</p>
     */
    static int selectIndexDouble(double regionId, int count) {
        int maxIndex = count - 1;
        return Math.max(0, Math.min(maxIndex, (int) Math.round(regionId * maxIndex)));
    }
    static double clampDouble(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ================================================================ warp 正/逆

    /** world → u（生产同式 float，再升 double）。 */
    public double[] warpForward(double wx, double wz) {
        return continuous.forward(wx, wz);
    }

    /**
     * u → world：反解 {@code (x+ox(x,z))·f = u}、{@code (z+oz(x,z))·f = v}，阻尼二维牛顿。
     *
     * <p><b>⚠ 已知不能保证的唯一性</b>：本方法找到的是<b>从初值出发的局部收敛根</b>。
     * 若 warp 在该尺度上折叠（多根），<b>每个根都可以有很小的残差</b>，因此
     * "残差 &lt; tol" <b>不能</b>证明"这是正确的那个原像"。本类<b>没有</b>证明
     * {@code (x,z) ↦ (u,v)} 是单射（那需要 {@code |∂o/∂x| &lt; 1} 之类的界，此处未证）。
     * 实际后果：折叠区附近的 world 折线可能落到错误的原像分支上而残差依旧很小。
     * 探针只声明"实测残差小"，不声明"原像唯一"。</p>
     *
     * @return {@code {x, z, residualBlocks}}
     */
    public double[] warpInverse(double u, double v, double guessX, double guessZ) {
        double x = Double.isFinite(guessX) ? guessX : u / frequency;
        double z = Double.isFinite(guessZ) ? guessZ : v / frequency;
        for (int it = 0; it < 100; it++) {
            double[] p = continuous.forwardAndJacobian(x, z);
            double fx = p[0] - u, fz = p[1] - v;
            if (Math.max(Math.abs(fx), Math.abs(fz)) / frequency < 1e-6) break;
            double det = p[2] * p[5] - p[3] * p[4];
            if (!(Math.abs(det) > 1e-14)) break;
            double dx = (-fx * p[5] + p[3] * fz) / det;
            double dz = (-p[2] * fz + fx * p[4]) / det;
            double len = Math.hypot(dx, dz);
            double cap = 150.0;
            if (len > cap) { dx *= cap / len; dz *= cap / len; }
            x += dx;
            z += dz;
        }
        return new double[]{x, z, residualBlocks(x, z, u, v)};
    }

    /**
     * 反解残差（块）。口径 = <b>double</b> 前向 {@link MorphGeometryTransform#forward}。
     *
     * <p><b>为什么不用 {@link #warpForward} 衡量</b>：它先把 world 降成 {@code float}，
     * 于是残差存在地板 {@code |∂u/∂x|·ulp/(2·f)}，随 |坐标| 线性上升
     * （65536→0.0059、131584→0.0156、262656→0.0313、524800→0.0625 块）。
     * 用该标尺判 {@code 0.02} 在远处必然失败，且还会<b>低估</b>真实误差
     * （实测同一批点标尺读 0.01831、真实 0.04419，1043 点超限被漏报）。
     * 这里改用 double 前向，判据与被判对象同口径。</p>
     */
    private double residualBlocks(double x, double z, double u, double v) {
        double[] p = continuous.forward(x, z);
        return Math.max(Math.abs(p[0] - u), Math.abs(p[1] - v)) / frequency;
    }

    // ================================================================ 生产口径对拍

    public double regionIdOfCell(int cellX, int cellZ) {
        return clampDouble((valCoord2D(cellX, cellZ) + 1) / 2, 0, 1);
    }

    /**
     * 格子 → 票槽下标（{@code round(regionId × (slotCount−1))}）。
     *
     * <p>{@link MorphLandField.Tile} 用它把每个 Voronoi 站点映射回 {@link MorphCatalog} 条目。
     * 口径与 {@link MorphRegionPartition#selectIndex} 同式，只是区域 id 由本类的
     * {@code valCoord2D}（double 版）给出。</p>
     */
    public int slotOfCell(int cellX, int cellZ) {
        return slotCount <= 0 ? -1 : selectIndexDouble(regionIdOfCell(cellX, cellZ), slotCount);
    }

    /** 该格所属票槽的类别名（只用于分类查询；无槽表时返回 {@code "?"}）。 */
    public String categoryOfCell(int cellX, int cellZ) {
        int idx = slotOfCell(cellX, cellZ);
        if (idx < 0 || slotCats == null || idx >= slotCats.length || slotCats[idx] == null) return "?";
        return String.valueOf(slotCats[idx]);
    }

    /** 全平面（非 3×3）最近的<b>场景内</b>站点；不在场景内返回 {@code null}。 */
    public Site exactWinner(double wx, double wz) {
        double[] p = warpForward(wx, wz);
        Site best = null;
        double bf = Double.POSITIVE_INFINITY;
        for (Site s : sites) {
            double f = potential(s.vx, s.vz, p[0], p[1]);
            if (f < bf) { bf = f; best = s; }
        }
        return best;
    }

    // ================================================================ 构建

    private void build() {
        selectSites();
        buildExactEdges();
        buildWorldEdges();
    }

    /**
     * 选站点：闭式确定"可能在某查询点获胜"的有限站点集合，外围无限站点被严格排除。
     *
     * <h3>单位（关键）</h3>
     * {@code u = (x + ox)·frequency}，而 {@code frequency = 1/1200} ⇒ <b>u 本身就是格子单位</b>
     * （1 格 ≈ 1200 块）。故站点格索引 = {@code floor(u)}，下面的 {@code F/dmax} 也全是格单位。
     * <b>绝不可再除以 frequency</b>——那会把 0.6 格放大成 720 个格步，枚举爆到千万级。
     *
     * <h3>严格界</h3>
     * <ol>
     *   <li>取盒中心格 {@code c0 = (floor(cu), floor(cv))} 的站点 {@code s0}，
     *       {@code F = fUpper(s0, box)}。盒内任意查询点 q 都有 {@code min_j f_j(q) ≤ f_s0(q) ≤ F}。</li>
     *   <li>{@code f = |Δx|+|Δy|+|Δx|²+|Δy|² ≥ |Δ|²} ⇒ 若某站点 {@code f_s(q) ≤ F}，
     *       则该轴位移 {@code |Δ_axis| ≤ |Δ| ≤ √F = dmax}（{@code √F} 是安全上取整）。</li>
     *   <li>站点坐标 {@code s = cell + jitter}。{@code MorphCellTable.CELL} 的 256 项实测值域为
     *       {@code [0.050, 0.950]}（<b>无负值</b>）⇒ {@code jitter = v·0.7 ∈ [0, 0.7]}，故
     *       {@code cell_axis ∈ [box_axis0 − dmax − 0.7, box_axis1 + dmax + 0.7]}。
     *       <b>该范围之外的整数格，其站点在任何查询点的势都 &gt; F，永不可能获胜</b>——
     *       包括无穷多个外部格，一次性闭式排除。</li>
     *   <li>范围内的站点再按 {@code fLower(s, box) > F} 剔除（盒内每点都输）。</li>
     * </ol>
     *
     * <p>⚠ 抖动界与表绑定：若 {@code MorphCellTable} 将来出现负值（jitter 变
     * {@code [−0.7, 0.7]}），下面两个边界项必须改成双侧 {@code ±0.7}。代码已用
     * {@link #CELL_JITTER} 常量表达，改常量即生效；此处记录"当前表无负值"这一实测事实。</p>
     */
    /**
     * 枚举窗口的格点范围守卫：所有参与 {@code floor/ceil → int} 的量必须先过这里。
     *
     * <p>为什么需要它：本类的盒坐标是 {@code u = (x + o)·f}，{@code f = 1/regionScale}。
     * 正常世界坐标（|x| ≤ 3×10⁷ 块、scale ≥ 数百）落在 ±10⁵ 格以内，离 int 边界极远；
     * 但只要 {@code scale} 被误设成极小值、或坐标本身非有限，{@code (int) Math.floor}
     * 就会<b>饱和</b>而不是抛错 —— 随后 {@code enumCells} 合理性检查会报"枚举太多"，
     * 把读者引向错误的方向。这里先按真实原因失败。</p>
     *
     * <p>判据与 {@link MorphNoiseEval#lattice} / {@link MorphGeometryTransform#lattice} 同义
     * （finite + 距 int 边界留余量），只是这几个量是<b>窗口边界</b>而非单个索引，
     * 所以一次性全部校验。</p>
     */
    private static void requireLatticeRange(double... values) {
        for (double v : values) {
            if (!Double.isFinite(v) || v <= Integer.MIN_VALUE + 2 || v >= Integer.MAX_VALUE - 2) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "site enumeration coordinate outside int range: %.6g "
                                + "(region scale too small, or non-finite input)", v));
            }
        }
    }

    private void selectSites() {
        // ★ 格点索引入口统一先验范围：这几处 floor/ceil → int 若遇到非有限值或越界值，
        //   裸转换会静默饱和，让枚举窗口变成一个荒谬的整数区间（随后被下面的
        //   enumCells 合理性检查偶然拦住，但报错信息会指向"枚举太多"而不是"坐标坏了"）。
        requireLatticeRange(cu, cv, boxU0, boxU1, boxV0, boxV1);
        // 中心格站点 → F
        int c0x = (int) Math.floor(cu);
        int c0z = (int) Math.floor(cv);
        double[] s0 = siteXZ(c0x, c0z);
        double F = fUpper(s0[0], s0[1], boxU0, boxU1, boxV0, boxV1);
        maxPotentialInBox = F;
        // √F 是严格上界（f ≥ |Δ|²），不需更紧的根
        double dMax = Math.sqrt(F) + 1e-9;
        maxSiteRadiusCells = dMax;

        // cell ∈ [box0 − dmax − jitterMax, box1 + dmax − jitterMin]
        // （站点 = cell + jitter，jitter ∈ [jitterMin, jitterMax]；当前取更宽的 [0, 0.7]）
        int axMin = (int) Math.ceil(boxU0 - dMax - JITTER_MAX);
        int axMax = (int) Math.floor(boxU1 + dMax - JITTER_MIN);
        int azMin = (int) Math.ceil(boxV0 - dMax - JITTER_MAX);
        int azMax = (int) Math.floor(boxV1 + dMax - JITTER_MIN);
        enumCells = (long) (axMax - axMin + 1) * (azMax - azMin + 1);
        if (enumCells <= 0 || enumCells > 4_000_000L) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "site enumeration implausible: %d cells (ax %d..%d, az %d..%d, dMax=%.4f)",
                    enumCells, axMin, axMax, azMin, azMax, dMax));
        }

        for (int az = azMin; az <= azMax; az++) {
            for (int ax = axMin; ax <= axMax; ax++) {
                exclusionTests++;
                double[] s = siteXZ(ax, az);
                double lo = fLower(s[0], s[1], boxU0, boxU1, boxV0, boxV1);
                if (lo > F) {
                    excludedSites++;
                    minExcludedLower = Math.min(minExcludedLower, lo);
                    continue;
                }
                sites.add(new Site(ax, az, s[0], s[1], sites.size()));
                includedSites++;
            }
        }
        if (!Double.isFinite(minExcludedLower)) minExcludedLower = Double.NaN;

        finalizeSites();
    }

    /** 排序 + 索引 + 建格索引表（{@link #selectSites} 的后半段）。 */
    private void finalizeSites() {
        // 固定顺序：先 v 后 u（与遍历无关）
        sites.sort((a, b) -> a.vz != b.vz ? Double.compare(a.vz, b.vz)
                : a.vx != b.vx ? Double.compare(a.vx, b.vx) : Integer.compare(a.cellX, b.cellX));
        for (int i = 0; i < sites.size(); i++) sites.get(i).index = i;
        cellIndex.clear();
        edgesOfCell.clear();
        for (Site s : sites) {
            cellIndex.put(s.key(), s);
            edgesOfCell.add(new ArrayList<>());
        }
    }

    /** u 网格分解 + 每矩形<b>精确</b>半平面交 → 有效 Voronoi 边。 */
    private void buildExactEdges() {
        if (sites.size() < 2) return;
        double[] us = new double[sites.size()];
        double[] vs = new double[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            us[i] = sites.get(i).vx;
            vs[i] = sites.get(i).vz;
        }
        Arrays.sort(us);
        Arrays.sort(vs);
        double[] uB = breaks(us, boxU0, boxU1);
        double[] vB = breaks(vs, boxV0, boxV1);
        rectCount = Math.max(0, uB.length - 1) * Math.max(0, vB.length - 1);

        Map<String, WarpEdge> found = new LinkedHashMap<>();
        for (int jz = 0; jz + 1 < vB.length; jz++) {
            for (int jx = 0; jx + 1 < uB.length; jx++) {
                rectEdges(uB[jx], uB[jx + 1], vB[jz], vB[jz + 1], found);
            }
        }
        warpEdges.addAll(found.values());
    }

    private static double[] breaks(double[] sorted, double lo, double hi) {
        List<Double> v = new ArrayList<>();
        v.add(lo);
        for (double x : sorted) {
            if (x > lo + U_EPS && x < hi - U_EPS && x - v.get(v.size() - 1) > U_EPS) v.add(x);
        }
        v.add(hi);
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    /**
     * 单个网格矩形：对每个可能获胜的站点求精确胞块。
     *
     * <p>剪枝用<b>该矩形内</b>的精确上下界（不是中心点的 Lipschitz 估计）：
     * NATURAL 可分 {@code f = g(u)+h(v)}，{@code |Δ|+Δ²} 在 {@code Δ=0} 处取 0，
     * 否则在离 0 最近/最远的端点取极值 ⇒
     * {@code fLower = axisLower(u_i)+axisLower(v_i)}、
     * {@code fUpper = axisUpper(u_i)+axisUpper(v_i)} 都是矩形上的<b>精确</b>极值。</p>
     */
    private void rectEdges(double u0, double u1, double v0, double v1, Map<String, WarpEdge> out) {
        int n = sites.size();
        double[] lo = new double[n];
        double minHi = Double.POSITIVE_INFINITY;
        for (int s = 0; s < n; s++) {
            Site c = sites.get(s);
            lo[s] = fLower(c.vx, c.vz, u0, u1, v0, v1);
            minHi = Math.min(minHi, fUpper(c.vx, c.vz, u0, u1, v0, v1));
        }
        double uc = 0.5 * (u0 + u1), vc = 0.5 * (v0 + v1);
        for (int i = 0; i < n; i++) {
            if (lo[i] > minHi) continue;                 // 精确下界已超过别人的精确上界 ⇒ 必不可能赢
            Site ci = sites.get(i);
            double[][] poly = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            for (int j = 0; j < n && poly != null; j++) {
                if (j == i) continue;
                candidatePairs++;
                Site cj = sites.get(j);
                if (Math.abs(ci.vx - cj.vx) < 1e-12 && Math.abs(ci.vz - cj.vz) < 1e-12) {
                    degeneratePairs++;
                    continue;
                }
                poly = clipCell(poly, ci, cj, uc, vc);
            }
            if (poly == null) continue;
            emitRectEdges(ci, poly, out);
        }
    }

    private static double axisLower(double s, double lo, double hi) {
        if (s >= lo && s <= hi) return 0.0;
        double d = s < lo ? lo - s : s - hi;
        return d + d * d;
    }

    private static double axisUpper(double s, double lo, double hi) {
        double d = Math.max(Math.abs(s - lo), Math.abs(s - hi));
        return d + d * d;
    }

    private static double fLower(double su, double sv, double u0, double u1, double v0, double v1) {
        return axisLower(su, u0, u1) + axisLower(sv, v0, v1);
    }

    private static double fUpper(double su, double sv, double u0, double u1, double v0, double v1) {
        return axisUpper(su, u0, u1) + axisUpper(sv, v0, v1);
    }

    /**
     * 裁剪 {@code f_i ≤ f_j}。矩形内符号恒定 ⇒ 系数解析精确：
     * {@code a = (sgn(u-u_i) - sgn(u-u_j)) - 2(u_i-u_j)}。
     */
    private static double[][] clipCell(double[][] poly, Site ci, Site cj, double uc, double vc) {
        double sui = uc >= ci.vx ? 1.0 : -1.0;
        double suj = uc >= cj.vx ? 1.0 : -1.0;
        double svi = vc >= ci.vz ? 1.0 : -1.0;
        double svj = vc >= cj.vz ? 1.0 : -1.0;
        double a = (sui - suj) - 2.0 * (ci.vx - cj.vx);
        double b = (svi - svj) - 2.0 * (ci.vz - cj.vz);
        if (Math.abs(a) < 1e-15 && Math.abs(b) < 1e-15) return poly;
        double d0 = potential(ci.vx, ci.vz, uc, vc) - potential(cj.vx, cj.vz, uc, vc);
        return clipLocal(poly, d0, a, b, uc, vc);
    }

    private static double[][] clipLocal(double[][] poly, double d0, double a, double b, double uc, double vc) {
        List<double[]> out = new ArrayList<>();
        int n = poly.length;
        for (int k = 0; k < n; k++) {
            double[] p = poly[k];
            double[] q = poly[(k + 1) % n];
            double sp = d0 + a * (p[0] - uc) + b * (p[1] - vc);
            double sq = d0 + a * (q[0] - uc) + b * (q[1] - vc);
            if (sp <= 0) out.add(p);
            if ((sp < 0 && sq > 0) || (sp > 0 && sq < 0)) {
                double t = sp / (sp - sq);
                out.add(new double[]{p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])});
            }
        }
        return out.size() >= 3 ? out.toArray(new double[0][]) : null;
    }

    /**
     * 登记真实 Voronoi 边：矩形盒边不满足 {@code f_i == f_j} ⇒ 自动丢弃。
     *
     * <p>配对站点取"除 {@code ci} 外距该段<b>中点</b>最近者"，并在<b>两个端点</b>上都核验
     * {@code f_ci == f_pair}；两端都过的段才是这条平分线的一段。</p>
     */
    private void emitRectEdges(Site ci, double[][] poly, Map<String, WarpEdge> out) {
        int n = poly.length;
        for (int k = 0; k < n; k++) {
            double[] p = poly[k], q = poly[(k + 1) % n];
            if (Math.hypot(q[0] - p[0], q[1] - p[1]) < 1e-12) continue;
            double mu = 0.5 * (p[0] + q[0]), mv = 0.5 * (p[1] + q[1]);
            Site cj = null;
            double bf = Double.POSITIVE_INFINITY;
            for (Site c : sites) {
                if (c == ci) continue;
                double f = potential(c.vx, c.vz, mu, mv);
                if (f < bf) { bf = f; cj = c; }
            }
            if (cj == null) continue;
            double scl = 1 + Math.abs(potential(ci.vx, ci.vz, mu, mv));
            if (Math.abs(potential(ci.vx, ci.vz, p[0], p[1])
                    - potential(cj.vx, cj.vz, p[0], p[1])) > 1e-11 * scl) continue;
            if (Math.abs(potential(ci.vx, ci.vz, q[0], q[1])
                    - potential(cj.vx, cj.vz, q[0], q[1])) > 1e-11 * scl) continue;

            long ka = ci.key(), kb = cj.key();
            long lo = Math.min(ka, kb), hi = Math.max(ka, kb);
            String key = String.format(Locale.ROOT, "%d|%d|%.9f|%.9f|%.9f|%.9f",
                    lo, hi, p[0], p[1], q[0], q[1]);
            String rev = String.format(Locale.ROOT, "%d|%d|%.9f|%.9f|%.9f|%.9f",
                    lo, hi, q[0], q[1], p[0], p[1]);
            if (out.containsKey(key) || out.containsKey(rev)) continue;
            out.put(key, new WarpEdge(ci, cj, p[0], p[1], q[0], q[1]));
        }
    }

    // ================================================================ world polyline

    private void buildWorldEdges() {
        edges.clear();
        for (List<Integer> l : edgesOfCell) l.clear();
        maxResidual = 0;
        for (WarpEdge we : mergeCollinearEdges()) {
            List<double[]> pts = densify(we);
            double len = 0;
            for (int k = 0; k + 1 < pts.size(); k++) {
                len += Math.hypot(pts.get(k + 1)[0] - pts.get(k)[0], pts.get(k + 1)[1] - pts.get(k)[1]);
            }
            int idx = edges.size();
            edges.add(new Edge(idx, we.a, we.b, pts, len, onBox(we)));
            edgesOfCell.get(we.a.index).add(idx);
            if (we.b.index != we.a.index) edgesOfCell.get(we.b.index).add(idx);
        }
    }

    // Merge only contiguous pieces of the same site-pair and affine line.
    // A real junction or a change of bisector slope remains an endpoint.
    private List<WarpEdge> mergeCollinearEdges() {
        List<WarpEdge> result = new ArrayList<>(warpEdges);
        boolean changed;
        do {
            changed = false;
            outer: for (int i = 0; i < result.size(); i++) {
                WarpEdge x = result.get(i);
                for (int j = i + 1; j < result.size(); j++) {
                    WarpEdge y = result.get(j);
                    if (!((x.a.key() == y.a.key() && x.b.key() == y.b.key())
                            || (x.a.key() == y.b.key() && x.b.key() == y.a.key()))) continue;
                    WarpEdge merged = mergeCollinear(x, y);
                    if (merged != null) {
                        result.set(i, merged);
                        result.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        } while (changed);
        return result;
    }

    private WarpEdge mergeCollinear(WarpEdge x, WarpEdge y) {
        double[][] xp = {{x.ax, x.az}, {x.bx, x.bz}};
        double[][] yp = {{y.ax, y.az}, {y.bx, y.bz}};
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            double[] m = xp[i], n = yp[j], a = xp[1-i], b = yp[1-j];
            if (Math.hypot(m[0]-n[0], m[1]-n[1]) > 1e-10) continue;
            if (isJunction(x, m[0], m[1])) continue;
            double dx = m[0]-a[0], dz = m[1]-a[1];
            double ex = b[0]-n[0], ez = b[1]-n[1];
            double scale = Math.hypot(dx,dz) * Math.hypot(ex,ez);
            if (scale == 0 || dx*ex+dz*ez <= 0 || Math.abs(dx*ez-dz*ex) > 1e-10*scale) continue;
            return new WarpEdge(x.a, x.b, a[0], a[1], b[0], b[1]);
        }
        return null;
    }

    private boolean isJunction(WarpEdge edge, double u, double v) {
        double f = potential(edge.a.vx, edge.a.vz, u, v);
        double tol = 1e-11 * (1 + Math.abs(f));
        for (Site site : sites) {
            if (site.key() != edge.a.key() && site.key() != edge.b.key()
                    && Math.abs(potential(site.vx, site.vz, u, v) - f) <= tol) return true;
        }
        return false;
    }

    private boolean onBox(WarpEdge we) {
        double eu = 1e-9 * Math.max(1.0, boxU1 - boxU0);
        double ev = 1e-9 * Math.max(1.0, boxV1 - boxV0);
        return near(we.ax, boxU0, eu) || near(we.ax, boxU1, eu)
                || near(we.bx, boxU0, eu) || near(we.bx, boxU1, eu)
                || near(we.az, boxV0, ev) || near(we.az, boxV1, ev)
                || near(we.bz, boxV0, ev) || near(we.bz, boxV1, ev)
                || near(we.az, boxU0, eu) || near(we.bz, boxU1, eu);
    }

    private static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    /**
     * 把 u 线段映成 world 折线。加密同时受 <b>弦偏差</b> 与 <b>最大段长</b> 约束：
     * 只有"世界长度已足够短"或"中点偏差已足够小"才停止二分，
     * 故不会因中点恰好落在 S 形拐点而漏加密。
     */
    private List<double[]> densify(WarpEdge we) {
        // The site-pair bisector is affine inside each sign rectangle. Its
        // coefficients and anchor depend on the sites, not the clipping box.
        double su = (we.a.vx + we.b.vx) * 0.5;
        double sv = (we.a.vz + we.b.vz) * 0.5;
        double midU = (we.ax + we.bx) * 0.5;
        double midV = (we.az + we.bz) * 0.5;
        double a = (midU >= we.a.vx ? 1 : -1) - (midU >= we.b.vx ? 1 : -1)
                - 2 * (we.a.vx - we.b.vx);
        double b = (midV >= we.a.vz ? 1 : -1) - (midV >= we.b.vz ? 1 : -1)
                - 2 * (we.a.vz - we.b.vz);
        double d = (midU >= we.a.vx ? 1 : -1) * (su - we.a.vx)
                - (midU >= we.b.vx ? 1 : -1) * (su - we.b.vx)
                + (midV >= we.a.vz ? 1 : -1) * (sv - we.a.vz)
                - (midV >= we.b.vz ? 1 : -1) * (sv - we.b.vz);
        boolean alongU = Math.abs(b) >= Math.abs(a);
        double start = alongU ? we.ax : we.az;
        double end = alongU ? we.bx : we.bz;
        double step = frequency * Math.min(maxSeg * 0.5, 1.0);
        double lo = Math.min(start, end), hi = Math.max(start, end);
        List<double[]> points = new ArrayList<>();
        // Actual endpoints remain the true clipped Voronoi endpoints; only
        // interior vertices use the common lattice. This does not yet merge
        // artificial rectangle cuts, whose short end chords can differ.
        points.add(new double[]{we.ax, we.az});
        long first = (long) Math.floor(lo / step) + 1;
        long last = (long) Math.ceil(hi / step) - 1;
        for (long k = first; k <= last; k++) {
            double t = k * step;
            double u = alongU ? t : su - (d + b * (t - sv)) / a;
            double v = alongU ? sv - (d + a * (t - su)) / b : t;
            points.add(new double[]{u, v});
        }
        if (start > end) java.util.Collections.reverse(points.subList(1, points.size()));
        points.add(new double[]{we.bx, we.bz});
        List<double[]> out = new ArrayList<>();
        double[] prev = points.get(0);
        double[] prevW = toWorld(prev[0], prev[1], Double.NaN, Double.NaN);
        out.add(prevW);
        for (int i = 1; i < points.size(); i++) {
            double[] next = points.get(i);
            double[] nextW = toWorld(next[0], next[1], Double.NaN, Double.NaN);
            split(prev, next, prevW, nextW, out, 0);
            out.add(nextW);
            prev = next;
            prevW = nextW;
        }
        return out;
    }

    private void split(double[] a, double[] b, double[] aw, double[] bw,
                       List<double[]> out, int depth) {
        double worldLen = Math.hypot(bw[0] - aw[0], bw[1] - aw[1]);
        if (worldLen <= maxSeg && depth > 0) {
            // 段已够短：仍核一次中点偏差，够小就停
            double[] mw0 = toWorld(0.5 * (a[0] + b[0]), 0.5 * (a[1] + b[1]),
                    0.5 * (aw[0] + bw[0]), 0.5 * (aw[1] + bw[1]));
            double dev0 = Math.hypot(mw0[0] - 0.5 * (aw[0] + bw[0]), mw0[1] - 0.5 * (aw[1] + bw[1]));
            if (dev0 <= chordTol) return;
        }
        if (depth >= 20) return;
        double mu = 0.5 * (a[0] + b[0]), mv = 0.5 * (a[1] + b[1]);
        double[] mw = toWorld(mu, mv, 0.5 * (aw[0] + bw[0]), 0.5 * (aw[1] + bw[1]));
        double dev = Math.hypot(mw[0] - 0.5 * (aw[0] + bw[0]), mw[1] - 0.5 * (aw[1] + bw[1]));
        boolean needByLen = worldLen > maxSeg;
        boolean needByDev = dev > chordTol;
        if (!needByLen && !needByDev) return;
        split(a, new double[]{mu, mv}, aw, mw, out, depth + 1);
        out.add(mw);
        split(new double[]{mu, mv}, b, mw, bw, out, depth + 1);
    }

    private double[] toWorld(double u, double v, double gx, double gz) {
        double[] inv = warpInverse(u, v, gx, gz);
        if (!Double.isFinite(inv[2])) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "warp inverse failed at u=(%.9f,%.9f): residual not finite", u, v));
        }
        if (inv[2] > invertTol) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "warp inverse residual %.4e blocks > tol %.4e at u=(%.9f,%.9f); "
                            + "world polyline would be untrustworthy", inv[2], invertTol, u, v));
        }
        maxResidual = Math.max(maxResidual, inv[2]);
        return new double[]{inv[0], inv[1]};
    }

    // ================================================================ 查询

    /**
     * 各格有符号距离（块）：赢家 {@code +d}，其余 {@code -d}，无场景内边界 ⇒ {@code NaN}。
     *
     * <p>{@link MorphLandField.Tile#weights} 直接按此判据算权重（同一 {@code progress}、
     * 同一归一顺序），只是额外用 bbox 跳过了"贡献恒为 0"的线段。</p>
     */
    public double[] signedDistances(double wx, double wz, int winnerIndex) {
        int n = sites.size();
        double[] sd = new double[n];
        for (int i = 0; i < n; i++) {
            List<Integer> ids = edgesOfCell.get(i);
            if (ids.isEmpty()) { sd[i] = Double.NaN; continue; }
            double best = Double.POSITIVE_INFINITY;
            for (int k : ids) {
                Edge e = edges.get(k);
                best = Math.min(best, polylineDistance(e.wx, e.wz, wx, wz));
                if (best <= 0) break;
            }
            sd[i] = (i == winnerIndex) ? best : -best;
        }
        return sd;
    }

    private static double polylineDistance(double[] xs, double[] zs, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < xs.length; i++) {
            double d = segmentDistance(x, z, xs[i], zs[i], xs[i + 1], zs[i + 1]);
            if (d < best) best = d;
        }
        return best;
    }

    // ================================================================ 数据载体

    /** 场景站点（格身份 + 抖动格心）。{@code index} = 数组下标。 */
    public static class Cell {
        public final int cellX, cellZ;
        public final double vx, vz;
        int index;

        Cell(int cellX, int cellZ, double vx, double vz, int index) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.vx = vx;
            this.vz = vz;
            this.index = index;
        }

        public int cellX() { return cellX; }
        public int cellZ() { return cellZ; }
        public double vx() { return vx; }
        public double vz() { return vz; }
        public int index() { return index; }
        public long key() { return pack(cellX, cellZ); }

        /** CSV 列名安全 id：{@code m12_n34}。 */
        public String id() { return "m" + cellX + "_n" + cellZ; }

        @Override public String toString() { return "(" + cellX + "," + cellZ + ")"; }
    }

    /** 站点视图（与 {@link Cell} 同对象）。 */
    public static final class Site extends Cell {
        Site(int cellX, int cellZ, double vx, double vz, int index) {
            super(cellX, cellZ, vx, vz, index);
        }
    }

    /** warp 平面上的有效 Voronoi 边片段（两端点，u 坐标）。 */
    public static final class WarpEdge {
        private final Cell a, b;
        public final double ax, az, bx, bz;

        WarpEdge(Cell a, Cell b, double ax, double az, double bx, double bz) {
            this.a = a;
            this.b = b;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
        }

        public Cell a() { return a; }
        public Cell b() { return b; }
        public double ax() { return ax; }
        public double az() { return az; }
        public double bx() { return bx; }
        public double bz() { return bz; }

        @Override public String toString() { return "(" + a + "|" + b + ")"; }
    }

    /** 世界折线片段（<b>近似</b>：反解残差 + 弦偏差，见类注释）。 */
    public static final class Edge {
        public final int index;
        public final Cell a, b;
        public final double[] wx, wz;
        public final double worldLength;
        public final boolean frameTruncated;

        Edge(int index, Cell a, Cell b, List<double[]> pts, double len, boolean trunc) {
            this.index = index;
            this.a = a;
            this.b = b;
            this.wx = new double[pts.size()];
            this.wz = new double[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                wx[i] = pts.get(i)[0];
                wz[i] = pts.get(i)[1];
            }
            this.worldLength = len;
            this.frameTruncated = trunc;
        }
    }
}
