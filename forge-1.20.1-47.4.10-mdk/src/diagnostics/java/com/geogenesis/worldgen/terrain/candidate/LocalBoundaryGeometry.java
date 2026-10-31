package com.geogenesis.worldgen.terrain.candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>真实区域边界的局部 Voronoi 几何</b>：给出带世界距离的有符号距离、权重与三域交汇点。
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
 * 世界查询盒外扩120块，再用已知warp位移上界扩张到u平面盒。
 * {@link #selectSites()} 对盒中心格的站点求精确分轴势上界F；
 * 任何可能获胜的站点距盒的轴向距离不超过sqrt(F)，再计入站点抖动界确定有限枚举范围。
 * 枚举内以精确分轴势下界大于F作排除；不依赖截断后采样未见赢家的推断。
 *
 * <h3>★ 世界几何是<b>近似</b>（必须明说）</h3>
 * u 平面的边是精确的；映回世界用<b>反 warp 阻尼牛顿</b>，只能做到残差 ε。本类：
 * <ul>
 *   <li>强制 {@code ε ≤ tolInvert}（默认 0.02 块），否则 {@link #build()} 抛异常并阻断；</li>
 *   <li>world折线按弦偏差与最大段长双重加密；交付探针采用最大段长2块，并做密度对拍。</li>
 *   <li>{@code signedDistance}是到该折线的距离。反解残差和密度差不是严格的世界距离误差上界；
 *       warp是否单射尚未证明，小残差也不能排除选到多根中的另一支。本类仅供局部实验。</li>
 * </ul>
 *
 * <p>只读生产对象（反射读 {@code seed/frequency/warpX/warpZ}），不写回、不改默认行为。</p>
 */
public final class LocalBoundaryGeometry {

    /** 抖动系数（{@code RegionPartition:89-90} 的 {@code vec*0.7f}）。 */
    static final float CELL_JITTER = 0.7f;
    /**
     * {@code RegionCellTable.CELL} 的 256 项实测值域 {@code [0.050, 0.950]}（无负值）。
     * 故抖动 {@code = v·0.7 ∈ [0.035, 0.665]} ⊂ {@code [0, 0.7]}。
     * 下面用 {@code [0, 0.7]} 这个<b>更宽</b>的区间做枚举界 —— 宽区间必然覆盖窄区间，
     * 所以枚举不会漏；若将来表里出现负值（{@code [−0.7, 0.7]}），必须换成对称界。
     */
    static final double JITTER_MIN = 0.0;
    static final double JITTER_MAX = (double) CELL_JITTER;
    /** warp 幅度（{@code RegionPartition:28-29}：{@code (n-0.5)*200}）。 */
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
    /** 反解残差容限默认值（块）。超限即阻断。 */
    public static final double INVERT_TOL_DEFAULT = 0.02;

    private static final double U_EPS = 1e-12;

    // ---------------------------------------------------------------- 生产对象（只读）
    private final RegionPartition partition;
    private final int partitionSeed;
    private final float frequency;
    private final RefNoise.Node warpX, warpZ;
    private final int slotCount;
    private final Object[] slotCats;
    private final long gallerySeed;

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

    private String[] regionIds = new String[0];
    private int[] regionSlotsArr = new int[0];
    private String[] regionCats = new String[0];

    // ---------------------------------------------------------------- 证书结果
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

    private LocalBoundaryGeometry(RegionPartition partition, int seed, float frequency,
                                  RefNoise.Node warpX, RefNoise.Node warpZ,
                                  int slots, Object[] cats, long gallerySeed,
                                  double cx, double cz, double r, int reqHalf) {
        this.partition = partition;
        this.partitionSeed = seed;
        this.frequency = frequency;
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.slotCount = slots;
        this.slotCats = cats;
        this.gallerySeed = gallerySeed;
        this.centerX = cx;
        this.centerZ = cz;
        this.radius = r;
        this.queryHalf = r;
        this.boxHalfWorld = r + HALO_BLOCKS;
        this.requestedHalfWidth = Math.max(2, reqHalf);

        double f = frequency;
        double[] c = warpForward(cx, cz);
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

    /** 供探针/沙箱取到 Gallery 同源构建的 owner 对象（只用于再喂给 {@link #of}）。 */
    public static Object galleryOwner() {
        return buildGalleryCombine(readGallerySeed());
    }

    /**
     * <b>主接口</b>：从持有 {@code partition}/{@code slots}/{@code slotCats} 的对象（如
     * {@link CombineCandidate}）构建局部几何。<b>只读</b>这三个字段，不写回、不调 seed。
     *
     * <p>完全由调用方给定中心与半径，不依赖 Gallery 的 SEED —— 供受控沙箱臂使用。</p>
     *
     * @param owner   持有 {@code partition} 字段的对象；若 owner 本身就是 {@code RegionPartition} 亦可
     * @param cx      查询域中心世界 X（块）
     * @param cz      查询域中心世界 Z（块）
     * @param radius  查询域半径（块）；{@code query()} 只接受域内点
     * @param reqHalf 站点窗口半宽下限（格）；实际由闭式界自动放大，传小值即可
     * @param chord   world 折线弦偏差（块）；{@code <=0} 用默认 0.5。下游建议另调 setMaxSeg(2)
     */
    public static LocalBoundaryGeometry of(Object owner, double cx, double cz, double radius,
                                           int reqHalf, double chord) {
        long gs = readGallerySeedQuiet();
        LocalBoundaryGeometry g = fromOwner(owner, gs, cx, cz, radius, reqHalf);
        if (chord > 0) g.setChordTol(chord);
        return g;
    }

    private static long readGallerySeedQuiet() {
        try {
            return readGallerySeed();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** 按 <b>Gallery 固定 SEED</b> 与 {@code buildCombineRegional} 同路径取分区（只读反射）。 */
    public static LocalBoundaryGeometry atGallery(double cx, double cz, double radius, int reqHalf) {
        long seed = readGallerySeed();
        Object combine = buildGalleryCombine(seed);
        return fromOwner(combine, seed, cx, cz, radius, reqHalf);
    }

    /** 同上，可指定弦偏差。 */
    public static LocalBoundaryGeometry atGallery(double cx, double cz, double radius, int reqHalf,
                                                  double chord) {
        LocalBoundaryGeometry g = atGallery(cx, cz, radius, reqHalf);
        if (chord > 0) g.setChordTol(chord);
        return g;
    }

    private static long readGallerySeed() {
        try {
            Field f = LandCompositionGallery.class.getDeclaredField("SEED");
            f.setAccessible(true);
            return f.getLong(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read LandCompositionGallery.SEED", e);
        }
    }

    private static Object buildGalleryCombine(long seed) {
        try {
            Method m = LandCompositionGallery.class
                    .getDeclaredMethod("buildCombineRegional", long.class, float.class);
            m.setAccessible(true);
            return m.invoke(null, seed, 3600f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot call LandCompositionGallery.buildCombineRegional", e);
        }
    }

    private static LocalBoundaryGeometry fromOwner(Object owner, long gallerySeedVal,
                                                   double cx, double cz, double r, int reqHalf) {
        Object part = owner instanceof RegionPartition rp ? rp : tryReadField(owner, "partition");
        if (!(part instanceof RegionPartition rp)) {
            throw new IllegalArgumentException("no RegionPartition on "
                    + (owner == null ? "null" : owner.getClass().getName()) + " (seed() not called?)");
        }
        int slots = 0;
        Object[] cats = null;
        if (tryReadField(owner, "slots") instanceof Object[] arr) slots = arr.length;
        if (tryReadField(owner, "slotCats") instanceof Object[] c) cats = c;
        return new LocalBoundaryGeometry(rp,
                (Integer) readField(rp, "seed"),
                (Float) readField(rp, "frequency"),
                (RefNoise.Node) readField(rp, "warpX"),
                (RefNoise.Node) readField(rp, "warpZ"),
                slots, cats, gallerySeedVal, cx, cz, r, reqHalf);
    }

    private static Object readField(Object o, String name) {
        Object v = tryReadField(o, name);
        if (v == null) {
            throw new IllegalArgumentException("no readable field '" + name + "' on "
                    + (o == null ? "null" : o.getClass().getName()));
        }
        return v;
    }

    private static Object tryReadField(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // 继续向父类找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("reflect field " + name, e);
            }
        }
        return null;
    }

    // ================================================================ 元信息

    public long gallerySeed() { return gallerySeed; }
    public int partitionSeed() { return partitionSeed; }
    public int slotCount() { return slotCount; }
    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }
    /** {@code query(x,z)} 的有效半径（块）。域外调用抛异常。 */
    public double queryRadius() { return queryHalf; }
    /** 几何盒的世界半宽（{@code radius + HALO}）。 */
    public double boxHalfWorld() { return boxHalfWorld; }
    public double[] warpBox() { return new double[]{boxU0, boxU1, boxV0, boxV1}; }
    public List<Site> sites() { return sites; }
    public List<Cell> regions() { return new ArrayList<>(sites); }
    public List<WarpEdge> warpEdges() { return warpEdges; }
    public List<Edge> edges() { return edges; }
    public double maxWarpResidualBlocks() { return maxResidual; }
    public double chordTol() { return chordTol; }
    public double maxSegBlocks() { return maxSeg; }
    public double invertTolBlocks() { return invertTol; }
    public int rectCount() { return rectCount; }
    public long candidatePairs() { return candidatePairs; }
    public int degeneratePairs() { return degeneratePairs; }

    /** 盒中心格站点给出的势上界 F（格单位，NATURAL）。 */
    public double maxPotentialInBox() { return maxPotentialInBox; }
    /** 由 √F 得到的站点最大轴向位移上界（格）。 */
    public double maxSiteRadiusCells() { return maxSiteRadiusCells; }
    /** 枚举的整数格数。 */
    public long enumeratedCells() { return enumCells; }
    /** 被闭式排除的站点数。 */
    public int excludedSiteCount() { return excludedSites; }
    /** 进入网格剖分的站点数。 */
    public int includedSiteCount() { return includedSites; }
    /** 被排除站点的最小下界（&gt; F 才有意义）。 */
    public double minExcludedLowerBound() { return minExcludedLower; }
    public long exclusionTests() { return exclusionTests; }

    public String[] slotCategories() {
        if (slotCats == null) return new String[0];
        String[] out = new String[slotCats.length];
        for (int i = 0; i < out.length; i++) out[i] = String.valueOf(slotCats[i]);
        return out;
    }

    /** 场景中心格（{@code "cellX,cellZ"}）；查不到返回 {@code null}。 */
    public String centerCell() {
        Site s = exactWinner(centerX, centerZ);
        return s == null ? null : s.cellX + "," + s.cellZ;
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

    /** NATURAL 位势：{@code |dx|+|dy|+dx²+dy²}（{@code RegionPartition:93}）。 */
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

    /** 生产 {@code edgeValue}（{@code RegionPartition:121-130}）原样。 */
    static float edgeValue(float d1, float d2) {
        if (!(d2 > 0f)) return 0f;
        float v = d1 / d2 - 1f;
        float edge = 1f - RefNoise.mapClamped(v, -1f, 0f);
        edge = (float) Math.pow(edge, 1.5);
        if (edge < 0f) return 0f;
        if (edge > 0.5f) return 1f;
        return edge / 0.5f;
    }

    /** 生产 {@code valCoord2D}（{@code RegionPartition:133-138}）；<b>必须带 partition seed</b>。 */
    float valCoord2D(int x, int z) {
        int n = partitionSeed;
        n ^= 1619 * x;
        n ^= 31337 * z;
        return n * n * n * 60493 / 2.14748365E9F;
    }

    /** 站点（NATURAL 空间抖动格心）。 */
    public double[] siteXZ(int cellX, int cellZ) {
        float[] vec = RegionCellTable.CELL[RefNoise.hash2D(partitionSeed, cellX, cellZ) & 0xFF];
        return new double[]{cellX + vec[0] * CELL_JITTER, cellZ + vec[1] * CELL_JITTER};
    }

    /** 某站点在某 u 点的 NATURAL 势。 */
    public double potentialAt(Cell c, double u, double v) {
        return potential(c.vx(), c.vz(), u, v);
    }

    /** 平滑权重：{@code smoothstep(clamp(0.5 + s/240))}；{@code NaN} ⇒ 0。 */
    public static double progress(double s) {
        if (Double.isNaN(s)) return 0.0;
        double t = 0.5 + s / BAND_TOTAL_BLOCKS;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    // ================================================================ warp 正/逆

    /** world → u（生产同式 float，再升 double）。 */
    public double[] warpForward(double wx, double wz) {
        float xf = (float) wx, zf = (float) wz;
        float ox = (warpX.compute(xf, zf) - 0.5f) * WARP_STRENGTH;
        float oz = (warpZ.compute(xf, zf) - 0.5f) * WARP_STRENGTH;
        return new double[]{(xf + ox) * frequency, (zf + oz) * frequency};
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
        double res = Double.POSITIVE_INFINITY;
        for (int it = 0; it < 100; it++) {
            double[] p0 = warpForward(x, z);
            double fx = p0[0] - u, fz = p0[1] - v;
            res = Math.max(Math.abs(fx), Math.abs(fz)) / frequency;
            if (res < 1e-6) break;
            double h = 0.05;
            double[] pa = warpForward(x + h, z), pb = warpForward(x, z + h);
            double j11 = (pa[0] - p0[0]) / h, j21 = (pa[1] - p0[1]) / h;
            double j12 = (pb[0] - p0[0]) / h, j22 = (pb[1] - p0[1]) / h;
            double det = j11 * j22 - j12 * j21;
            if (!(Math.abs(det) > 1e-14)) break;
            double dx = (-fx * j22 + j12 * fz) / det;
            double dz = (-j11 * fz + fx * j21) / det;
            double len = Math.hypot(dx, dz);
            double cap = 150.0;
            if (len > cap) { dx *= cap / len; dz *= cap / len; }
            x += dx;
            z += dz;
        }
        return new double[]{x, z, res};
    }

    // ================================================================ 生产口径对拍

    public RegionPartition.Sample referenceSample(double wx, double wz) {
        return partition.sample((float) wx, (float) wz);
    }

    public float regionIdOfCell(int cellX, int cellZ) {
        return RefNoise.mapClamped(valCoord2D(cellX, cellZ), -1f, 1f);
    }

    public int slotOfCell(int cellX, int cellZ) {
        return slotCount <= 0 ? -1 : RegionPartition.selectIndex(regionIdOfCell(cellX, cellZ), slotCount);
    }

    public String categoryOfCell(int cellX, int cellZ) {
        int idx = slotOfCell(cellX, cellZ);
        if (idx < 0 || slotCats == null || idx >= slotCats.length || slotCats[idx] == null) return "?";
        return String.valueOf(slotCats[idx]);
    }

    /** 该格在场景内的有效片段数（0 = 边界在场景外）。 */
    public int edgeCountOf(Cell c) {
        Site s = cellIndex.get(c.key());
        return s == null ? 0 : edgesOfCell.get(s.index).size();
    }

    /** 生产 3×3 口径的赢家（逐位复刻），附 d1/d2。 */
    public Pick winnerCell(double wx, double wz) {
        float xf = (float) wx, zf = (float) wz;
        float ox = (warpX.compute(xf, zf) - 0.5f) * WARP_STRENGTH;
        float oz = (warpZ.compute(xf, zf) - 0.5f) * WARP_STRENGTH;
        float px = (xf + ox) * frequency;
        float pz = (zf + oz) * frequency;
        int xi = RefNoise.floor(px), yi = RefNoise.floor(pz);
        float nearest = Float.MAX_VALUE, nearest2 = Float.MAX_VALUE;
        int cx = 0, cz = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ax = xi + dx, az = yi + dy;
                float[] vec = RegionCellTable.CELL[RefNoise.hash2D(partitionSeed, ax, az) & 0xFF];
                float vx = ax + vec[0] * CELL_JITTER;
                float vy = az + vec[1] * CELL_JITTER;
                float ddx = vx - px, ddy = vy - pz;
                float dist = Math.abs(ddx) + Math.abs(ddy) + (ddx * ddx + ddy * ddy);
                if (dist < nearest) {
                    nearest2 = nearest;
                    nearest = dist;
                    cx = ax;
                    cz = az;
                } else if (dist < nearest2) {
                    nearest2 = dist;
                }
            }
        }
        return new Pick(cx, cz, nearest, nearest2);
    }

    public float edgeOfPick(Pick p) { return edgeValue(p.d1, p.d2); }

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
     *   <li>站点坐标 {@code s = cell + jitter}。{@code RegionCellTable.CELL} 的 256 项实测值域为
     *       {@code [0.050, 0.950]}（<b>无负值</b>）⇒ {@code jitter = v·0.7 ∈ [0, 0.7]}，故
     *       {@code cell_axis ∈ [box_axis0 − dmax − 0.7, box_axis1 + dmax + 0.7]}。
     *       <b>该范围之外的整数格，其站点在任何查询点的势都 &gt; F，永不可能获胜</b>——
     *       包括无穷多个外部格，一次性闭式排除。</li>
     *   <li>范围内的站点再按 {@code fLower(s, box) > F} 剔除（盒内每点都输）。</li>
     * </ol>
     *
     * <p>⚠ 抖动界与表绑定：若 {@code RegionCellTable} 将来出现负值（jitter 变
     * {@code [−0.7, 0.7]}），下面两个边界项必须改成双侧 {@code ±0.7}。代码已用
     * {@link #CELL_JITTER} 常量表达，改常量即生效；此处记录"当前表无负值"这一实测事实。</p>
     */
    private void selectSites() {
        // 中心格站点 → F
        int c0x = RefNoise.floor((float) cu);
        int c0z = RefNoise.floor((float) cv);
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

        regionIds = new String[sites.size()];
        regionSlotsArr = new int[sites.size()];
        regionCats = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            Site s = sites.get(i);
            regionIds[i] = s.id();
            regionSlotsArr[i] = slotOfCell(s.cellX, s.cellZ);
            regionCats[i] = categoryOfCell(s.cellX, s.cellZ);
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
        double c = d0 - a * uc - b * vc;
        return clip(poly, -c, a, b);
    }

    private static double[][] clip(double[][] poly, double c, double a, double b) {
        List<double[]> out = new ArrayList<>();
        int n = poly.length;
        for (int k = 0; k < n; k++) {
            double[] p = poly[k];
            double[] q = poly[(k + 1) % n];
            double sp = a * p[0] + b * p[1] - c;
            double sq = a * q[0] + b * q[1] - c;
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
        for (WarpEdge we : warpEdges) {
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
        List<double[]> out = new ArrayList<>();
        double[] a = {we.ax, we.az}, b = {we.bx, we.bz};
        double[] aw = toWorld(a[0], a[1], Double.NaN, Double.NaN);
        double[] bw = toWorld(b[0], b[1], aw[0], aw[1]);
        out.add(aw);
        split(a, b, aw, bw, out, 0);
        out.add(bw);
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

    /** 边中点的两格位势差（判据用）。 */
    public double midpointPotentialGap(WarpEdge we) {
        double mx = 0.5 * (we.ax + we.bx), mz = 0.5 * (we.az + we.bz);
        return Math.abs(potential(we.a.vx, we.a.vz, mx, mz) - potential(we.b.vx, we.b.vz, mx, mz));
    }

    /**
     * 点查询。有效域 = 以 {@code (centerX,centerZ)} 为心、半径 {@link #queryRadius()} 的圆。
     *
     * @throws IllegalArgumentException 点在有效域外，或赢家站点不在场景内（不静默兜底）
     */
    public Sample query(double wx, double wz) {
        double dx = wx - centerX, dz = wz - centerZ;
        double d = Math.hypot(dx, dz);
        if (!(d <= queryHalf)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "query (%.3f,%.3f) is %.3f blocks from center, outside declared radius %.3f",
                    wx, wz, d, queryHalf));
        }
        Site win = exactWinner(wx, wz);
        if (win == null) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "no site wins at (%.3f,%.3f): winner cell not in scene (candidate set bug)", wx, wz));
        }
        int n = sites.size();
        double[] sd = signedDistances(wx, wz, win.index);
        double[] wt = new double[n];

        double sum = 0;
        for (int i = 0; i < n; i++) {
            wt[i] = progress(sd[i]);
            sum += wt[i];
        }
        if (Double.isNaN(sd[win.index])) {
            Arrays.fill(wt, 0.0);
            wt[win.index] = 1.0;
        } else if (sum > 0) {
            for (int i = 0; i < n; i++) wt[i] /= sum;
        }

        int active = 0;
        for (int i = 0; i < n; i++) if (wt[i] > 0) active++;

        return new Sample(sd, wt, win.index, win.cellX, win.cellZ,
                slotOfCell(win.cellX, win.cellZ), categoryOfCell(win.cellX, win.cellZ),
                active, winnerCell(wx, wz), regionIds, regionSlotsArr, regionCats);
    }

    /** 各格有符号距离（块）：赢家 {@code +d}，其余 {@code -d}，无场景内边界 ⇒ {@code NaN}。 */
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

    // ================================================================ 导出

    public void writeSegmentsCsv(Path p) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("segId,cellAX,cellAZ,cellBX,cellBZ,vertexIndex,x,z");
        for (Edge e : edges) {
            for (int i = 0; i < e.wx.length; i++) {
                lines.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%.4f,%.4f",
                        e.index, e.a.cellX, e.a.cellZ, e.b.cellX, e.b.cellZ, i, e.wx[i], e.wz[i]));
            }
        }
        Files.writeString(p, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * <b>法向剖面</b>：沿一条真实弯曲边某点的外法向做 1D 扫描，输出两侧权重与有符号距离。
     *
     * <p>用途：主代理画带宽曲线图（验证 ±120 块的 smoothstep 过渡是否与定稿一致）。
     * 输出列固定：{@code x,z,signedDistances(每格一列),weights(每格一列),distanceAlongNormal}。</p>
     *
     * @param segId      边 {@code Edge.index}
     * @param alongIndex 该边 world 折线上的顶点下标（用它 + 邻点求法向）
     * @param halfSpan   法向半跨度（块），如 160 / 180
     * @param step       采样步长（块），如 0.5
     * @return CSV 文本
     */
    public String normalProfileCsv(int segId, int alongIndex, double halfSpan, double step) {
        Edge e = null;
        for (Edge x : edges) if (x.index == segId) { e = x; break; }
        if (e == null) throw new IllegalArgumentException("no such segId " + segId);
        if (alongIndex <= 0 || alongIndex >= e.wx.length - 1) {
            throw new IllegalArgumentException("alongIndex must be interior: 1.." + (e.wx.length - 2));
        }
        double mx = e.wx[alongIndex], mz = e.wz[alongIndex];
        double nx = -(e.wz[alongIndex + 1] - e.wz[alongIndex - 1]);
        double nz = (e.wx[alongIndex + 1] - e.wx[alongIndex - 1]);
        double len = Math.hypot(nx, nz);
        if (!(len > 0)) throw new IllegalStateException("degenerate tangent at vertex " + alongIndex);
        nx /= len;
        nz /= len;
        return normalProfileCsv(mx, mz, nx, nz, halfSpan, step, e.a, e.b);
    }

    /** 见 {@link #normalProfileCsv(int, int, double, double)}；也可由外部直接给点与法向。 */
    public String normalProfileCsv(double px, double pz, double nx, double nz,
                                   double halfSpan, double step, Cell cellA, Cell cellB) {
        if (!(step > 0)) throw new IllegalArgumentException("step must be > 0");
        StringBuilder head = new StringBuilder("x,z,distanceAlongNormal");
        for (Site s : sites) head.append(",s_").append(s.id());
        for (Site s : sites) head.append(",w_").append(s.id());
        head.append(",weightA,weightB,activeCount");
        List<String> lines = new ArrayList<>();
        lines.add(head.toString());

        int ia = cellIndex.containsKey(cellA.key()) ? cellIndex.get(cellA.key()).index : -1;
        int ib = cellIndex.containsKey(cellB.key()) ? cellIndex.get(cellB.key()).index : -1;
        if (ia < 0 || ib < 0) throw new IllegalArgumentException("cellA/cellB not in this scene");

        int nSteps = (int) Math.round(halfSpan / step);   // k ∈ [-nSteps, +nSteps] ⇒ 覆盖 ±halfSpan
        for (int k = -nSteps; k <= nSteps; k++) {
            double t = k * step;
            double x = px + nx * t, z = pz + nz * t;
            double d = Math.hypot(x - centerX, z - centerZ);
            if (d > queryHalf) continue;              // 出域样本直接跳过（不写假值）
            Sample s = query(x, z);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.3f", x, z, t));
            for (int i = 0; i < sites.size(); i++) {
                sb.append(',').append(Double.isNaN(s.signedDistance[i]) ? "NaN"
                        : String.format(Locale.ROOT, "%.4f", s.signedDistance[i]));
            }
            for (int i = 0; i < sites.size(); i++) {
                sb.append(',').append(String.format(Locale.ROOT, "%.6f", s.weight[i]));
            }
            sb.append(',').append(String.format(Locale.ROOT, "%.6f", s.weight[ia]));
            sb.append(',').append(String.format(Locale.ROOT, "%.6f", s.weight[ib]));
            sb.append(',').append(s.activeCount);
            lines.add(sb.toString());
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * 常规（未截断）真实弯边的候选清单，按以下顺序筛选并按 {@code minJunctionGap} 降序排列：
     *
     * <ol>
     *   <li>边未截断、world 折线足够密；</li>
     *   <li>中点连同 ±{@code halfSpan} 的法向跨度都必须落在 {@link #queryRadius()} 声明域内
     *       （否则剖面会被域边界截断）；</li>
     *   <li>中点到<b>所有</b>三交汇点都 ≥ {@code minJunctionGap}；</li>
     *   <li><b>实际扫一遍 ±{@code halfSpan}</b>：整条剖面必须<b>始终只有 2 个正权重候选</b>
     *       且就是 {@code cellA}/{@code cellB}，并且 A→B 权重单调。
     *       不满足的边直接不是候选 —— 这样主代理拿到的 CSV 一定能画干净的带宽曲线。</li>
     * </ol>
     */
    public List<ProfileCandidate> profileCandidates(List<double[]> junctionWorld,
                                                    double minJunctionGap, double halfSpan) {
        List<ProfileCandidate> out = new ArrayList<>();
        for (Edge e : edges) {
            if (e.frameTruncated || e.wx.length < 5) continue;
            int mid = e.wx.length / 2;
            double mx = e.wx[mid], mz = e.wz[mid];
            if (Math.hypot(mx - centerX, mz - centerZ) + halfSpan > queryHalf) continue;
            double gap = Double.POSITIVE_INFINITY;
            for (double[] j : junctionWorld) gap = Math.min(gap, Math.hypot(mx - j[0], mz - j[1]));
            if (gap < minJunctionGap) continue;
            double nx = -(e.wz[mid + 1] - e.wz[mid - 1]);
            double nz = (e.wx[mid + 1] - e.wx[mid - 1]);
            double len = Math.hypot(nx, nz);
            if (!(len > 0)) continue;
            nx /= len;
            nz /= len;
            if (!twoCandidateAcross(mx, mz, nx, nz, halfSpan, e.a, e.b)) continue;
            out.add(new ProfileCandidate(e.index, mid, mx, mz, nx, nz,
                    gap, e.a, e.b, e.worldLength));
        }
        out.sort((a, b) -> Double.compare(b.minJunctionGap, a.minJunctionGap));
        return out;
    }

    /**
     * 沿法向扫一遍：整条剖面必须<b>恰好</b> {@code (cellA, cellB)} 两个正权重格。
     * 这是"保证全线 2 候选"的实测判据，不是估计。
     */
    private boolean twoCandidateAcross(double px, double pz, double nx, double nz,
                                       double halfSpan, Cell cellA, Cell cellB) {
        Integer ia = cellIndex.containsKey(cellA.key()) ? cellIndex.get(cellA.key()).index : null;
        Integer ib = cellIndex.containsKey(cellB.key()) ? cellIndex.get(cellB.key()).index : null;
        if (ia == null || ib == null) return false;
        int steps = 64;
        for (int k = -steps; k <= steps; k++) {
            double t = halfSpan * k / steps;
            double x = px + nx * t, z = pz + nz * t;
            if (Math.hypot(x - centerX, z - centerZ) > queryHalf) return false;
            Site w = exactWinner(x, z);
            if (w == null) return false;
            if (w.index != ia && w.index != ib) return false;
            // 其它格必须权重为 0：用有符号距离判据（|s| < BAND 才可能有权重）
            double[] sd = signedDistances(x, z, w.index);
            for (int i = 0; i < sd.length; i++) {
                if (i == ia || i == ib) continue;
                if (Double.isNaN(sd[i])) continue;
                if (progress(sd[i]) > 0) return false;
            }
        }
        return true;
    }

    /** 一条可选的法向剖面采样点。 */
    public record ProfileCandidate(int segId, int alongIndex, double x, double z,
                                   double nx, double nz, double minJunctionGap,
                                   Cell cellA, Cell cellB, double worldLength) { }

    public void writeRegionsCsv(Path p) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("id,cellX,cellZ,siteU,siteV,regionId,slot,category,worldEdgeCount,frameTruncated");
        for (Site c : sites) {
            int trunc = 0;
            for (int k : edgesOfCell.get(c.index)) if (edges.get(k).frameTruncated) trunc++;
            lines.add(String.format(Locale.ROOT, "%s,%d,%d,%.6f,%.6f,%.6f,%d,%s,%d,%d",
                    c.id(), c.cellX, c.cellZ, c.vx, c.vz, regionIdOfCell(c.cellX, c.cellZ),
                    slotOfCell(c.cellX, c.cellZ), categoryOfCell(c.cellX, c.cellZ),
                    edgesOfCell.get(c.index).size(), trunc));
        }
        Files.writeString(p, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    public String sceneJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(String.format(Locale.ROOT, "  \"gallerySeed\": %d,\n", gallerySeed));
        sb.append(String.format(Locale.ROOT, "  \"partitionSeed\": %d,\n", partitionSeed));
        sb.append(String.format(Locale.ROOT, "  \"frequency\": %.12f,\n", frequency));
        sb.append(String.format(Locale.ROOT, "  \"slotCount\": %d,\n", slotCount));
        sb.append("  \"slots\": [");
        String[] cats = slotCategories();
        for (int i = 0; i < cats.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(cats[i]).append('"');
        }
        sb.append("],\n");
        sb.append(String.format(Locale.ROOT, "  \"centerBlocks\": [%.4f, %.4f],\n", centerX, centerZ));
        sb.append(String.format(Locale.ROOT, "  \"queryRadiusBlocks\": %.4f,\n", queryHalf));
        sb.append(String.format(Locale.ROOT, "  \"haloBlocks\": %.1f,\n", HALO_BLOCKS));
        sb.append(String.format(Locale.ROOT, "  \"boxHalfWorldBlocks\": %.1f,\n", boxHalfWorld));
        sb.append(String.format(Locale.ROOT, "  \"warpDisplacementBoundBlocks\": %.1f,\n",
                WARP_DISPLACEMENT_BLOCKS));
        sb.append(String.format(Locale.ROOT, "  \"bandHalfBlocks\": %.1f,\n", BAND_HALF_BLOCKS));
        sb.append(String.format(Locale.ROOT, "  \"bandTotalBlocks\": %.1f,\n", BAND_TOTAL_BLOCKS));
        sb.append(String.format(Locale.ROOT, "  \"siteCount\": %d,\n", sites.size()));
        sb.append(String.format(Locale.ROOT,
                "  \"warpPlaneBox\": [%.9f, %.9f, %.9f, %.9f],\n", boxU0, boxU1, boxV0, boxV1));
        sb.append(String.format(Locale.ROOT, "  \"maxPotentialInBox\": %.9f,\n", maxPotentialInBox));
        sb.append(String.format(Locale.ROOT, "  \"maxSiteRadiusCells\": %.9f,\n", maxSiteRadiusCells));
        sb.append(String.format(Locale.ROOT, "  \"enumeratedCells\": %d,\n", enumCells));
        sb.append(String.format(Locale.ROOT, "  \"includedSites\": %d,\n", includedSites));
        sb.append(String.format(Locale.ROOT, "  \"excludedSites\": %d,\n", excludedSites));
        sb.append(String.format(Locale.ROOT, "  \"minExcludedLowerBound\": %.9g,\n", minExcludedLower));
        sb.append(String.format(Locale.ROOT, "  \"exclusionTests\": %d,\n", exclusionTests));
        sb.append(String.format(Locale.ROOT, "  \"warpEdgeCount\": %d,\n", warpEdges.size()));
        sb.append(String.format(Locale.ROOT, "  \"worldSegmentCount\": %d,\n", edges.size()));
        sb.append(String.format(Locale.ROOT, "  \"maxWarpResidualBlocks\": %.3e,\n", maxResidual));
        sb.append(String.format(Locale.ROOT, "  \"invertTolBlocks\": %.3e,\n", invertTol));
        sb.append(String.format(Locale.ROOT, "  \"chordTolBlocks\": %.4f,\n", chordTol));
        sb.append(String.format(Locale.ROOT, "  \"maxSegBlocks\": %.4f,\n", maxSeg));
        sb.append(String.format(Locale.ROOT, "  \"rectCount\": %d,\n", rectCount));
        sb.append(String.format(Locale.ROOT, "  \"candidatePairs\": %d,\n", candidatePairs));
        sb.append(String.format(Locale.ROOT, "  \"degeneratePairs\": %d,\n", degeneratePairs));
        sb.append("  \"sites\": [\n");
        for (int i = 0; i < sites.size(); i++) {
            Site c = sites.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    {\"index\": %d, \"cellX\": %d, \"cellZ\": %d, \"siteU\": %.9f, \"siteV\": %.9f,"
                            + " \"regionId\": %.9f, \"slot\": %d, \"category\": \"%s\", \"worldEdges\": %d}%s\n",
                    i, c.cellX, c.cellZ, c.vx, c.vz, regionIdOfCell(c.cellX, c.cellZ),
                    slotOfCell(c.cellX, c.cellZ), categoryOfCell(c.cellX, c.cellZ),
                    edgesOfCell.get(i).size(), i + 1 < sites.size() ? "," : ""));
        }
        sb.append("  ],\n");
        sb.append("  \"edges\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    {\"segId\": %d, \"cellA\": [%d, %d], \"cellB\": [%d, %d],"
                            + " \"vertices\": %d, \"worldLength\": %.4f, \"frameTruncated\": %s}%s\n",
                    e.index, e.a.cellX, e.a.cellZ, e.b.cellX, e.b.cellZ, e.wx.length,
                    e.worldLength, e.frameTruncated, i + 1 < edges.size() ? "," : ""));
        }
        sb.append("  ]\n}\n");
        return sb.toString();
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

    /** 生产 3×3 赢家结果。 */
    public record Pick(int cellX, int cellZ, float d1, float d2) { }

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

    /** 点查询结果。数组下标与 {@link #sites()} 一致（稳定顺序）。 */
    public static final class Sample {
        /** 块：赢家 {@code +d}，其余 {@code -d}；该格无场景内边界 ⇒ {@code NaN}。 */
        public final double[] signedDistance;
        /** {@code progress(s)} 归一化。 */
        public final double[] weight;
        public final String[] regions;
        public final int[] regionSlots;
        public final String[] regionCategories;
        public final int cellX, cellZ, slot;
        public final String category;
        /** <b>实际</b>权重 &gt; 0 的格数（与 {@code activeCount} 语义一致）。 */
        public final int activeCount;
        private final int winnerIndex;
        private final Pick pick;

        Sample(double[] sd, double[] w, int winIdx, int cellX, int cellZ, int slot,
               String category, int active, Pick pick,
               String[] regions, int[] regionSlots, String[] regionCats) {
            this.signedDistance = sd;
            this.weight = w;
            this.regions = regions;
            this.regionSlots = regionSlots;
            this.regionCategories = regionCats;
            this.winnerIndex = winIdx;
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.slot = slot;
            this.category = category;
            this.activeCount = active;
            this.pick = pick;
        }

        public int winnerIndex() { return winnerIndex; }

        public double productionEdge() { return edgeValue(pick.d1(), pick.d2()); }
    }
}
