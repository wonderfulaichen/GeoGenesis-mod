package com.geogenesis.worldgen.terrain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * {@code TerrainUpgradeProbe render} 的实现：<b>侵蚀前基础高度</b>诊断图与统计。
 *
 * <h3>这是诊断，不是最终落块</h3>
 * <p>只走 {@code terrainEQuick} → {@link HeightCurve#heightFromE}（与生产同一函数，但不含侵蚀 tile、
 * 水文雕刻、洞穴、装饰、表层）。因此：<b>不得</b>用它证明"河流位置正确"或"落块正确"，
 * 它的用途是 A/B 对照地貌组织、坡度分布与跨网格线跳变。</p>
 *
 * <h3>为什么用 terrainEQuick 而不是 sample()</h3>
 * <p>{@code sample()} 每像素还要算气候（含上风向降水回采）与分类；1401² 下这部分是纯浪费。
 * 本模式因此只用最便宜的高度核；类型图是<b>可选</b>的（{@code --types=on}），因为它每像素
 * 要跑 7×7 类型场 Voronoi —— 那正是升级方案里"重复计算"的候选之一，不能默认加进大图。</p>
 *
 * <h3>固定色阶（A/B 必须可比）</h3>
 * <p>颜色只由 <b>世界 Y</b> 决定，取固定区间 {@code [hmin, hmax]}（默认 -20..240，CLI 可覆盖），
 * <b>绝不按每幅图自适应拉伸</b>。{@code stats.json} 会把该区间与色带写进 {@code colorScale}，
 * Python 对比脚本据此断言 A/B 同色阶。</p>
 */
final class TerrainUpgradeRender {

    /** 固定色带（低 → 高）：深海蓝 → 浅海青 → 低地绿 → 高地黄 → 雪白。 */
    static final int[][] RAMP = {
            {20, 60, 130}, {60, 140, 150}, {70, 150, 70}, {170, 180, 100}, {255, 255, 255}
    };
    /** 光照方向（左上），与 {@code TerrainStripePngProbe} 同侧，便于人工对照旧图。 */
    static final double[] LIGHT = {-0.5, -0.3, 1.0};
    static final double SHADE_BASE = 0.55;
    static final double SHADE_GAIN = 0.45;
    /** 跨网格线跳变使用的类型场格距（wu）；与 {@code TerrainCharacterField} 的 400wu 格一致。 */
    static final int TYPE_GRID = 400;

    private TerrainUpgradeRender() { }

    // ==================================================================
    // 入口
    // ==================================================================

    static int run(TerrainUpgradeProbe.Opts opts, Path out) throws Exception {
        TerrainUpgradeProbe.ConfigInput cfg = TerrainUpgradeProbe.loadConfig(out, opts);
        Spec spec = Spec.from(opts, cfg);
        CellGenerator gen = new CellGenerator(cfg.params(), TerrainUpgradeProbe.WORLD_MIN_Y,
                TerrainUpgradeProbe.WORLD_MAX_Y);
        gen.seed(spec.seed());

        long t0 = System.nanoTime();
        Grid grid = fill(gen, gen.heightCurve(), spec.wuCx(), spec.wuCz(), spec.size(), spec.wuStep(),
                spec.types());
        double fillSeconds = (System.nanoTime() - t0) / 1e9;

        Map<String, Object> root = reportMap(cfg, out, spec, grid, fillSeconds);
        writeArtifacts(out, grid, spec.size(), spec.hmin(), spec.hmax(), spec.types(), spec.rawMode());
        TerrainUpgradeProbe.writeJson(out.resolve("stats.json"), root);

        System.out.println("render seed=" + spec.seed() + " centerBlock=(" + spec.cx() + "," + spec.cz()
                + ") size=" + spec.size() + " stepBlock=" + spec.stepBlocks()
                + " stepWu=" + String.format(Locale.ROOT, "%.4f", spec.wuStep())
                + " types=" + spec.types()
                + " height=[" + fmt(root, "height", "min") + "," + fmt(root, "height", "max") + "]"
                + " nonFinite=" + grid.nonFinite()
                + " seconds=" + String.format(Locale.ROOT, "%.1f", fillSeconds));
        System.out.println("wrote " + out.toAbsolutePath());
        if (grid.nonFinite() > 0) {
            System.out.println("FAIL non-finite heights present: " + grid.nonFinite());
            System.out.println("STATUS=non-finite-height");
            return 1;
        }
        System.out.println("STATUS=ok");
        return 0;
    }

    /** 一次渲染的完整几何（块坐标 + 只换算一次的 wu 坐标）。 */
    record Spec(long seed, int cx, int cz, int size, double stepBlocks, double hs,
                double wuStep, double wuCx, double wuCz, boolean types, String rawMode,
                double hmin, double hmax) {

        static Spec from(TerrainUpgradeProbe.Opts opts, TerrainUpgradeProbe.ConfigInput cfg) {
            long seed = opts.i64("seed", TerrainUpgradeProbe.CANONICAL_SEED);
            int cx = opts.i32("cx", -140, -1_000_000, 1_000_000);
            int cz = opts.i32("cz", 137, -1_000_000, 1_000_000);
            int size = opts.i32("size", 1401, 3, 8193);
            double stepBlocks = opts.f64("step", 1.0);
            if (!(stepBlocks > 0.0)) {
                throw new TerrainUpgradeProbe.Exit(2, "--step must be > 0 (blocks per pixel)");
            }
            boolean types = opts.flag("types", false);
            String rawMode = opts.str("raw", "both").toLowerCase(Locale.ROOT);
            if (!List.of("both", "float", "double").contains(rawMode)) {
                throw new TerrainUpgradeProbe.Exit(2, "--raw expects both|float|double, got: " + rawMode);
            }
            double hmin = opts.f64("hmin", -20.0);
            double hmax = opts.f64("hmax", 240.0);
            if (!(hmax > hmin)) throw new TerrainUpgradeProbe.Exit(2, "require hmax > hmin");
            // ★ 块 → wu 只在这里换算一次（frozen horizontalScale），之后无块坐标进入引擎。
            double hs = cfg.params().horizontalScale();
            if (!(hs > 0.01)) {
                throw new TerrainUpgradeProbe.Exit(2, "horizontalScale must be > 0.01, got " + hs);
            }
            return new Spec(seed, cx, cz, size, stepBlocks, hs, stepBlocks / hs, cx / hs, cz / hs,
                    types, rawMode, hmin, hmax);
        }

        double blockLo(double center) { return center - (size / 2) * stepBlocks; }

        double blockHi(double center) { return center + (size - 1 - size / 2) * stepBlocks; }
    }

    static Map<String, Object> reportMap(TerrainUpgradeProbe.ConfigInput cfg, Path out, Spec s,
                                         Grid grid, double fillSeconds) {
        Map<String, Object> root = TerrainUpgradeProbe.baseReport("render", cfg, out);
        root.put("note", "pre-erosion base height (terrainEQuick -> HeightCurve). Diagnostic only: "
                + "no erosion tile, no hydrology carving, no caves, no surface, no decoration.");
        root.put("render", map(
                "coordinate", "blocks for CLI/bounds; converted once to wu via horizontalScale",
                "seed", s.seed(),
                "center", map("x", s.cx(), "z", s.cz()),
                "size", s.size(),
                "step", s.stepBlocks(),
                "horizontalScale", s.hs(),
                "stepWu", s.wuStep(),
                "centerWu", map("x", s.wuCx(), "z", s.wuCz()),
                "types", s.types(),
                "bounds", map(
                        "x0", s.blockLo(s.cx()), "x1", s.blockHi(s.cx()),
                        "z0", s.blockLo(s.cz()), "z1", s.blockHi(s.cz())),
                "boundsWu", map(
                        "x0", coordOf(s.size(), s.wuCx(), s.wuStep(), 0),
                        "x1", coordOf(s.size(), s.wuCx(), s.wuStep(), s.size() - 1),
                        "z0", coordOf(s.size(), s.wuCz(), s.wuStep(), 0),
                        "z1", coordOf(s.size(), s.wuCz(), s.wuStep(), s.size() - 1)),
                "centerPixel", map("ix", s.size() / 2, "iz", s.size() / 2),
                "raw", map(
                        "f32", "height.f32.raw",
                        "f64", "height.f64.raw",
                        "written", s.rawMode(),
                        "encoding", "little-endian IEEE-754",
                        "layout", "row-major index = iz*size + ix; row=z, col=x (blocks); "
                                + "x = cx + (ix-size/2)*step; z = cz + (iz-size/2)*step; same order on both arms")));
        root.put("colorScale", map(
                "fixed", true,
                "min", s.hmin(),
                "max", s.hmax(),
                "ramp", RAMP,
                "light", LIGHT,
                "shadeBase", SHADE_BASE,
                "shadeGain", SHADE_GAIN,
                "rule", "colour depends on world-Y only; never rescaled per image, so A/B share the scale"));
        root.put("height", heightStats(grid.h(), s.size()));
        root.put("e", map("min", grid.eMin(), "max", grid.eMax()));
        root.put("slope", slopeStats(grid.h(), s.size(), s.wuStep()));
        root.put("grid400", map("grid", TYPE_GRID,
                "vertical", axisJump(grid.h(), s.size(), s.wuCx(), s.wuStep(), true),
                "horizontal", axisJump(grid.h(), s.size(), s.wuCz(), s.wuStep(), false),
                "rule", "line median |dh| across a 400-wu grid line vs global median |dh|; "
                        + "ratio ~1 means no grid signature"));
        root.put("types", s.types()
                ? map("enabled", true, "coverage", grid.typeCoverage())
                : map("enabled", false, "reason", "--types=off (7x7 Voronoi per pixel; opt-in only)"));
        root.put("wallSeconds", fillSeconds);
        root.put("artifacts", map("hillshade", "hillshade.png", "type", s.types() ? "type.png" : null));
        root.put("status", grid.nonFinite() == 0 ? "ok" : "non-finite-height");
        return root;
    }

    private static String fmt(Map<String, Object> root, String section, String key) {
        Map<String, Object> s = TerrainUpgradeProbe.asMap(root.get(section));
        return s == null ? "?" : String.valueOf(s.get(key));
    }

    // ==================================================================
    // 采样
    // ==================================================================

    record Grid(double[][] h, double eMin, double eMax, int nonFinite, int[][] dom, Map<String, Integer> typeCoverage) { }

    /**
     * 逐像素采样高度（按行并行；每像素只调 terrainEQuick + heightFromE）。
     *
     * <p>类型图是第二遍独立并行：它每像素要走 7×7 Voronoi，默认关闭。</p>
     */
    static Grid fill(CellGenerator gen, HeightCurve curve, double cx, double cz, int size, double step,
                     boolean types) {
        double[][] h = new double[size][size];
        double[] eMinRows = new double[size];
        double[] eMaxRows = new double[size];
        int[] nonFiniteRows = new int[size];
        IntStream.range(0, size).parallel().forEach(iz -> {
            double z = coordOf(size, cz, step, iz);
            double mn = Double.POSITIVE_INFINITY;
            double mx = Double.NEGATIVE_INFINITY;
            int bad = 0;
            for (int ix = 0; ix < size; ix++) {
                double e = gen.terrainEQuick(coordOf(size, cx, step, ix), z);
                double y = curve.heightFromE(e);
                h[iz][ix] = y;
                if (e < mn) mn = e;
                if (e > mx) mx = e;
                if (!Double.isFinite(y)) bad++;
            }
            eMinRows[iz] = mn;
            eMaxRows[iz] = mx;
            nonFiniteRows[iz] = bad;
        });
        double eMin = Double.POSITIVE_INFINITY;
        double eMax = Double.NEGATIVE_INFINITY;
        int nonFinite = 0;
        for (int iz = 0; iz < size; iz++) {
            eMin = Math.min(eMin, eMinRows[iz]);
            eMax = Math.max(eMax, eMaxRows[iz]);
            nonFinite += nonFiniteRows[iz];
        }
        int[][] dom = null;
        Map<String, Integer> coverage = new LinkedHashMap<>();
        if (types) {
            dom = new int[size][size];
            for (int iz = 0; iz < size; iz++) {
                double z = coordOf(size, cz, step, iz);
                for (int ix = 0; ix < size; ix++) {
                    dom[iz][ix] = dominant(gen.typeWeightsAt(coordOf(size, cx, step, ix), z));
                }
            }
            for (int iz = 0; iz < size; iz++) {
                for (int ix = 0; ix < size; ix++) {
                    String name = TerrainClass.values()[dom[iz][ix]].name();
                    coverage.merge(name, 1, Integer::sum);
                }
            }
        }
        return new Grid(h, eMin, eMax, nonFinite, dom, coverage);
    }

    static int dominant(double[] weights) {
        if (weights == null || weights.length == 0) return -1;
        double best = Double.NEGATIVE_INFINITY;
        int bi = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > best) {
                best = weights[i];
                bi = i;
            }
        }
        return bi;
    }

    /**
     * 像素列/行索引 → 世界坐标（与 stats.json 的 raw.layout 严格一致）。
     *
     * <p>中心约定：{@code idx == size/2}（整数除）时返回 {@code center} 本身。
     * 传奇数 size（1401 / 1025）时中心像素正好落在 {@code (cx, cz)}，A/B 图因此可逐像素对拍。</p>
     */
    static double coordOf(int size, double center, double step, int idx) {
        return center + (idx - size / 2) * step;
    }

    // ==================================================================
    // 图像
    // ==================================================================

    static void writeArtifacts(Path out, Grid grid, int size, double hmin, double hmax,
                               boolean types, String rawMode) throws IOException {
        if (!"double".equalsIgnoreCase(rawMode)) writeRaw(out.resolve("height.f32.raw"), grid.h(), size, 4);
        if (!"float".equalsIgnoreCase(rawMode)) writeRaw(out.resolve("height.f64.raw"), grid.h(), size, 8);
        writePng(out.resolve("hillshade.png"), hillshade(grid.h(), size, hmin, hmax));
        if (types && grid.dom() != null) writePng(out.resolve("type.png"), typeImage(grid.dom(), size));
    }

    /** 固定色阶 + 坡度阴影；最接近"人眼所见"，是 A/B 并排图的唯一依据。 */
    static BufferedImage hillshade(double[][] h, int size, double hmin, double hmax) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                double shade = shadeAt(h, size, iz, ix);
                int[] rgb = rampColor(h[iz][ix], hmin, hmax);
                int r = clamp255(Math.round(rgb[0] * shade));
                int g = clamp255(Math.round(rgb[1] * shade));
                int b = clamp255(Math.round(rgb[2] * shade));
                img.setRGB(ix, iz, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    static double shadeAt(double[][] h, int size, int iz, int ix) {
        int x1 = Math.max(0, ix - 1);
        int x2 = Math.min(size - 1, ix + 1);
        int z1 = Math.max(0, iz - 1);
        int z2 = Math.min(size - 1, iz + 1);
        double gx = (h[iz][x2] - h[iz][x1]) / Math.max(1, x2 - x1);
        double gz = (h[z2][ix] - h[z1][ix]) / Math.max(1, z2 - z1);
        double nx = -gx;
        double nz = -gz;
        double nl = Math.sqrt(nx * nx + nz * nz + 1.0);
        double ll = Math.sqrt(LIGHT[0] * LIGHT[0] + LIGHT[1] * LIGHT[1] + LIGHT[2] * LIGHT[2]);
        double dot = (nx * LIGHT[0] + nz * LIGHT[1] + LIGHT[2]) / (nl * ll);
        return SHADE_BASE + SHADE_GAIN * Math.max(0.0, dot);
    }

    /** 世界 Y → 固定色带（越界钳制；绝不按图自适应）。 */
    static int[] rampColor(double y, double hmin, double hmax) {
        double span = hmax - hmin;
        double p = span <= 1e-12 ? 0.0 : (y - hmin) / span;
        p = Math.max(0.0, Math.min(1.0, p));
        double t = p * (RAMP.length - 1);
        int i = (int) Math.min(RAMP.length - 2, Math.floor(t));
        double f = t - i;
        return new int[]{
                (int) Math.round(RAMP[i][0] + (RAMP[i + 1][0] - RAMP[i][0]) * f),
                (int) Math.round(RAMP[i][1] + (RAMP[i + 1][1] - RAMP[i][1]) * f),
                (int) Math.round(RAMP[i][2] + (RAMP[i + 1][2] - RAMP[i][2]) * f)};
    }

    /** 类型主导着色（固定调色板；无关地形语义，仅用于定位类型边界）。 */
    static BufferedImage typeImage(int[][] dom, int size) {
        int[] colors = typeColors();
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                int id = dom[iz][ix];
                img.setRGB(ix, iz, id >= 0 && id < colors.length ? colors[id] : 0xFF00FF);
            }
        }
        return img;
    }

    static int[] typeColors() {
        int[] c = new int[TerrainClass.COUNT];
        Arrays.fill(c, 0x808080);
        c[TerrainClass.OCEAN.ordinal()] = 0x1E3A8A;
        c[TerrainClass.DEEP_OCEAN.ordinal()] = 0x0B1A3A;
        c[TerrainClass.CONTINENTAL_SHELF.ordinal()] = 0x2E86AB;
        c[TerrainClass.SUBMARINE_RIDGE.ordinal()] = 0x14532D;
        c[TerrainClass.SEAMOUNT.ordinal()] = 0x0EA5A5;
        c[TerrainClass.LAKE.ordinal()] = 0x2563EB;
        c[TerrainClass.RIVER.ordinal()] = 0x38BDF8;
        c[TerrainClass.BEACH.ordinal()] = 0xE8D8A0;
        c[TerrainClass.PLAIN.ordinal()] = 0x7CB342;
        c[TerrainClass.HILLS.ordinal()] = 0xA1887F;
        c[TerrainClass.PLATEAU.ordinal()] = 0xC0A020;
        c[TerrainClass.MOUNTAINS.ordinal()] = 0xD84315;
        c[TerrainClass.PEAK.ordinal()] = 0xF5F5F5;
        c[TerrainClass.BASIN.ordinal()] = 0x5C6BC0;
        c[TerrainClass.SNOW.ordinal()] = 0xE0F2FE;
        c[TerrainClass.VOLCANO.ordinal()] = 0x7F1D1D;
        c[TerrainClass.VOLCANIC_FIELD.ordinal()] = 0xA16207;
        return c;
    }

    static void writePng(Path target, BufferedImage img) throws IOException {
        if (!ImageIO.write(img, "png", target.toFile())) {
            throw new IllegalStateException("PNG writer unavailable for " + target);
        }
    }

    /** 原始高度（无压缩、无量化），供 Python 做逐像素差图与统计。 */
    static void writeRaw(Path target, double[][] h, int size, int bytesPerSample) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(size * size * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                if (bytesPerSample == 4) bb.putFloat((float) h[iz][ix]);
                else bb.putDouble(h[iz][ix]);
            }
        }
        Files.write(target, bb.array());
    }

    static int clamp255(long v) { return (int) Math.max(0L, Math.min(255L, v)); }

    // ==================================================================
    // 统计
    // ==================================================================

    static Map<String, Object> heightStats(double[][] h, int size) {
        double[] flat = flatten(h, size);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int nonFinite = 0;
        for (double v : flat) {
            if (!Double.isFinite(v)) {
                nonFinite++;
                continue;
            }
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double[] finite = finiteOnly(flat, nonFinite == 0);
        Arrays.sort(finite);
        return mapInOrder(
                "min", min, "max", max,
                "p50", percentile(finite, 0.50),
                "p95", percentile(finite, 0.95),
                "p99", percentile(finite, 0.99),
                "nonFinite", nonFinite,
                "samples", size * size);
    }

    static Map<String, Object> slopeStats(double[][] h, int size, double step) {
        double[] flat = flatten(h, size);
        int k = 0;
        for (int iz = 1; iz < size - 1; iz++) {
            for (int ix = 1; ix < size - 1; ix++) {
                double gx = (h[iz][ix + 1] - h[iz][ix - 1]) / (2.0 * step);
                double gz = (h[iz + 1][ix] - h[iz - 1][ix]) / (2.0 * step);
                flat[k++] = Math.hypot(gx, gz);
            }
        }
        double[] slopes = Arrays.copyOf(flat, k);
        Arrays.sort(slopes);
        return mapInOrder(
                "unit", "worldY blocks per wu",
                "p50", percentile(slopes, 0.50),
                "p90", percentile(slopes, 0.90),
                "p99", percentile(slopes, 0.99),
                "max", slopes.length == 0 ? Double.NaN : slopes[slopes.length - 1],
                "samples", k);
    }

    /**
     * 跨 400wu 类型网格线的跳变。
     *
     * <p>为什么查这条：{@code TerrainCharacterField} 的种子在规则网格上（{@code WARP_AMP=0}），
     * 其 Voronoi 边可能是<b>轴对齐直线</b>；若高度场沿这些线出现系统台阶，这里的中位比会明显 &gt;1。
     * 比值 ≈1 只说明"没有网格签名"，<b>不能</b>代替看图（历史教训：平滑脊两翼天然呈亮线）。</p>
     */
    static Map<String, Object> axisJump(double[][] h, int size, double center, double step, boolean alongX) {
        int pairs = size - 1;
        double[] all = new double[pairs * size];
        int k = 0;
        for (int fixed = 0; fixed < size; fixed++) {
            for (int varying = 1; varying < size; varying++) {
                all[k++] = alongX
                        ? Math.abs(h[fixed][varying] - h[fixed][varying - 1])
                        : Math.abs(h[varying][fixed] - h[varying - 1][fixed]);
            }
        }
        double[] sorted = Arrays.copyOf(all, k);
        Arrays.sort(sorted);
        double allMedian = percentile(sorted, 0.5);

        double[] line = new double[pairs * 2];
        int lines = 0;
        int m = 0;
        double worst = 0;
        double worstCoord = Double.NaN;
        for (int varying = 1; varying < size; varying++) {
            double a = coordOf(size, center, step, varying - 1);
            double b = coordOf(size, center, step, varying);
            if (!crossesGrid(a, b, TYPE_GRID)) continue;
            lines++;
            for (int fixed = 0; fixed < size; fixed++) {
                double d = alongX
                        ? Math.abs(h[fixed][varying] - h[fixed][varying - 1])
                        : Math.abs(h[varying][fixed] - h[varying - 1][fixed]);
                if (m < line.length) line[m++] = d;
                if (d > worst) {
                    worst = d;
                    worstCoord = alongX ? coordOf(size, center, step, fixed) : b;
                }
            }
        }
        double[] lineSorted = Arrays.copyOf(line, m);
        Arrays.sort(lineSorted);
        double lineMedian = percentile(lineSorted, 0.5);
        return mapInOrder(
                "lines", lines,
                "globalMedianAbsDelta", allMedian,
                "lineMedianAbsDelta", lineMedian,
                "lineMaxAbsDelta", m == 0 ? 0.0 : lineSorted[m - 1],
                "medianRatio", allMedian > 1e-12 ? lineMedian / allMedian : null,
                "maxRatio", allMedian > 1e-12 ? worst / allMedian : null,
                "worstCoord", worstCoord);
    }

    static boolean crossesGrid(double a, double b, int grid) {
        return Math.floor(a / grid) != Math.floor(b / grid);
    }

    static double[] flatten(double[][] h, int size) {
        double[] flat = new double[size * size];
        int k = 0;
        for (int iz = 0; iz < size; iz++) System.arraycopy(h[iz], 0, flat, k + iz * size, size);
        return flat;
    }

    static double[] finiteOnly(double[] values, boolean allFinite) {
        if (allFinite) return values;
        int n = 0;
        for (double v : values) if (Double.isFinite(v)) n++;
        double[] out = new double[n];
        int k = 0;
        for (double v : values) if (Double.isFinite(v)) out[k++] = v;
        return out;
    }

    static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        int idx = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    /** 与 {@link TerrainUpgradeProbe} 同一序列化风格（插入序，null 保留）。 */
    static Map<String, Object> map(Object... kv) { return mapInOrder(kv); }

    static Map<String, Object> mapInOrder(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================================================================
    // self-test（供 TerrainUpgradeProbe --self-test 调用）
    // ==================================================================

    static void selfChecks(List<String> fails, List<String> checks) {
        // 固定色阶：越界钳制，绝不自适应
        int[] low = rampColor(-1000, -20, 240);
        int[] high = rampColor(1000, -20, 240);
        if (!Arrays.equals(low, RAMP[0])) fails.add("rampColor below hmin must equal first stop");
        if (!Arrays.equals(high, RAMP[RAMP.length - 1])) fails.add("rampColor above hmax must equal last stop");
        if (!Arrays.equals(rampColor(-20, -20, 240), RAMP[0])) fails.add("rampColor at hmin");
        if (!Arrays.equals(rampColor(240, -20, 240), RAMP[RAMP.length - 1])) fails.add("rampColor at hmax");
        checks.add("rampColor fixed endpoints ok");

        // 负坐标轴：像素索引 ↔ 世界坐标必须双向自洽（含负中心；块→wu 换算只在 render 做一次）
        double hs = 2.0;
        double wuCx = -140 / hs;
        double wuStep = 1.0 / hs;
        double c0 = coordOf(1401, wuCx, wuStep, 0) * hs;
        double cMid = coordOf(1401, wuCx, wuStep, 700) * hs;
        double cLast = coordOf(1401, wuCx, wuStep, 1400) * hs;
        if (cMid != -140.0) fails.add("coordOf center pixel must map back to cx (blocks)");
        if (c0 != -840.0 || cLast != 560.0) fails.add("coordOf negative origin mapping");
        double macro = coordOf(1025, 0.0, 32.0 / hs, 0) * hs;
        if (macro != -16384.0) fails.add("coordOf macro step mapping");
        checks.add("coordOf negative/step mapping ok (" + c0 + ".." + cLast + " blocks)");

        // 跨格线判据（含负坐标轴）
        if (!crossesGrid(399.5, 400.5, 400)) fails.add("crossesGrid positive line");
        if (crossesGrid(400.5, 401.5, 400)) fails.add("crossesGrid false positive");
        if (!crossesGrid(-0.5, 0.5, 400)) fails.add("crossesGrid origin line");
        if (!crossesGrid(-400.5, -399.5, 400)) fails.add("crossesGrid negative line");
        checks.add("crossesGrid negative axis ok");

        // 故障反控：网格线台阶必须被 ratio 抓到（否则该判据是摆设）
        //   背景必须带正常纹理 —— 纯常数场的中位 |dh| = 0，比值无定义。
        int size = 41;
        double[][] stepped = new double[size][size];
        for (int iz = 0; iz < size; iz++) {
            for (int ix = 0; ix < size; ix++) {
                stepped[iz][ix] = 0.35 * Math.sin(ix * 1.7 + iz * 0.9) + (ix < size / 2 ? 0.0 : 50.0);
            }
        }
        Map<String, Object> jump = axisJump(stepped, size, 0, 1, true);
        Object ratioRaw = jump.get("medianRatio");
        double ratio = ratioRaw == null ? 0.0 : (double) ratioRaw;
        if (!(ratio > 2.0)) fails.add("axisJump does not detect a synthetic grid step (ratio=" + ratio + ")");
        checks.add("axisJump fault-reverse ratio=" + String.format(Locale.ROOT, "%.1f", ratio));

        // 原始图索引：写入的 float 必须按 iz*size+ix 顺序落盘
        double[][] probe = new double[2][3];
        probe[0][0] = 1.5;
        probe[1][2] = -7.25;
        ByteBuffer bb = ByteBuffer.allocate(2 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int iz = 0; iz < 2; iz++) for (int ix = 0; ix < 3; ix++) bb.putFloat((float) probe[iz][ix]);
        ByteBuffer back = ByteBuffer.wrap(bb.array()).order(ByteOrder.LITTLE_ENDIAN);
        if (back.getFloat(0) != 1.5f) fails.add("raw index (0,0)");
        if (back.getFloat((1 * 3 + 2) * 4) != -7.25f) fails.add("raw index (iz=1,ix=2)");
        checks.add("raw row-major index ok");

        // 阴影强度界限
        double shade = shadeAt(new double[][]{{0, 0}, {0, 0}}, 2, 0, 0);
        if (!(shade >= SHADE_BASE - 1e-9 && shade <= SHADE_BASE + SHADE_GAIN + 1e-9)) {
            fails.add("shade factor outside [base, base+gain]");
        }
        checks.add("shade factor in range (" + String.format(Locale.ROOT, "%.3f", shade) + ")");
    }
}
