package com.geogenesis.worldgen.climate;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 气候 / 群系诊断（2026-09-10 v2）。
 *
 * <p>验证四件事：
 * <ol>
 *   <li><b>Whittaker 群区图</b>：打印 ASCII 图，人工确认生态分区与过渡顺序正确。</li>
 *   <li><b>温度纬向梯度</b>：wz=0（出生点）最热，随 |wz| 单调递减。</li>
 *   <li><b>群区分异</b>：Whittaker 类型随纬度带推进（雨林 → 温带林 → 针叶林 → 苔原/冰原）。</li>
 *   <li><b>邻接合法性</b>：世界上相邻采样点的群区，必须在 Whittaker 图里也是相邻的。
 *       违例 = 「丛林紧挨热带草原」这类生态上不可能的碎斑，必须为 0。</li>
 * </ol>
 *
 * <p>说明：本探针刻意<b>不触碰 MC 注册表</b>（Biomes 静态初始化需要 Forge 的 {@code Bootstrap}，
 * 独立 JavaExec 下无法完成），只统计零依赖的 Whittaker 群区与地形。
 *
 * <p>用法：{@code gradlew runClimateBiomeProbe -PprobeArgs=12345}
 */
public final class ClimateBiomeProbe {
    private ClimateBiomeProbe() {}

    /** 采样步长（wu）与范围：z 覆盖 2 倍 latitudeScale（赤道 → 极地 → 极地外） */
    private static final int STEP = 60;
    private static final int X_HALF = 3000;
    private static final int Z_MAX = 12000;

    /** 纬度带边界（wu） */
    private static final int[] BAND_EDGES = {0, 2000, 4000, 6000, Z_MAX};
    private static final String[] BAND_NAMES = {"z[0,2k)", "z[2k,4k)", "z[4k,6k)", "z[6k,12k)"};

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        printDiagram();
        printTemperatureGradient(gen);
        printScalarMap(gen, true);
        printScalarMap(gen, false);
        printLabelMap(gen);
        printFineMap(gen);
        scanWorld(gen);
        checkStraightnessAndSnow(gen);
    }

    /** 精细群区地图（4 wu/字符，覆盖 256 wu ≈ 一个气候区）——直接看边界形状。 */
    private static void printFineMap(CellGenerator gen) {
        final int n = 64, wuPerChar = 4;
        System.out.println();
        System.out.printf("=== 精细群区地图（每字符 %d wu，覆盖 %d wu）===%n", wuPerChar, n * wuPerChar);
        StringBuilder sb = new StringBuilder(n);
        for (int cz = 0; cz < n; cz++) {
            sb.setLength(0);
            for (int cx = 0; cx < n; cx++) {
                int wx = -n * wuPerChar / 2 + cx * wuPerChar;
                int wz = -n * wuPerChar / 2 + cz * wuPerChar;
                sb.append(symbol(gen.sample(wx, wz).biomeType));
            }
            System.out.printf("  %s%n", sb);
        }
    }

    /**
     * 温度/湿度分位地图（0..9 = 从低到高十分位）。用于判断气候场是否呈"分区常量"的
     * 多边形块状，还是平滑渐变。
     */
    private static void printScalarMap(CellGenerator gen, boolean temperature) {
        final int n = 96, wuPerChar = 16;
        System.out.println();
        System.out.printf("=== %s 分位地图（0=最低 9=最高，每字符 %d wu）===%n",
            temperature ? "温度" : "湿度", wuPerChar);
        StringBuilder sb = new StringBuilder(n);
        for (int cz = 0; cz < n; cz++) {
            sb.setLength(0);
            for (int cx = 0; cx < n; cx++) {
                int wx = -n * wuPerChar / 2 + cx * wuPerChar;
                int wz = -n * wuPerChar / 2 + cz * wuPerChar;
                Cell c = gen.sample(wx, wz);
                double v = temperature ? c.temperature : c.humidity;
                int q = (int) Math.floor((v + 1.0) * 5.0);
                if (q < 0) q = 0;
                if (q > 9) q = 9;
                sb.append((char) ('0' + q));
            }
            System.out.printf("  %s%n", sb);
        }
    }

    // ===== 0) 标签地图：直接看形状（边界是直线/多边形还是自然蜿蜒）=====

    /**
     * 打印 Whittaker 群区 × 垂直带 的 ASCII 地图（每字符 16 wu）。
     * 字符：群区符号；高山带用大写、亚高山用 +、峰顶用 #。
     */
    private static void printLabelMap(CellGenerator gen) {
        final int n = 96;          // 96×96 字符
        final int wuPerChar = 16;  // 每字符 16 wu → 覆盖 1536 wu（约 6×6 个气候区）
        System.out.println();
        System.out.printf("=== 标签地图（每字符 %d wu，覆盖 %d wu；. = 低地 %s）===%n",
            wuPerChar, n * wuPerChar, "群区符号");
        StringBuilder sb = new StringBuilder(n);
        for (int cz = 0; cz < n; cz++) {
            sb.setLength(0);
            for (int cx = 0; cx < n; cx++) {
                int wx = -n * wuPerChar / 2 + cx * wuPerChar;
                int wz = -n * wuPerChar / 2 + cz * wuPerChar;
                Cell c = gen.sample(wx, wz);
                char ch = symbol(c.biomeType);
                switch (BiomeClassifier.bandOf(c)) {
                    case LOWLAND -> { }
                    case SUBALPINE -> ch = '+';
                    case ALPINE -> ch = Character.toUpperCase(ch);
                    case PEAK -> ch = '#';
                }
                sb.append(ch);
            }
            System.out.printf("  %s%n", sb);
        }
    }

    // ===== 5) 轴对齐直线检测 + 雪线统计 =====

    /**
     * 最终群系的近似标签：Whittaker 群区（区域层）× 垂直带 × <b>变体类</b>。
     * 变体类只区分 {@code landVariant} 真正会改群系的地形（PLATEAU/HILLS/BASIN），
     * 其余地形（MOUNTAINS/PLAIN/OCEAN…）不影响群系键，不能计入——
     * 否则会把"地形边界"误判成"群系直线"（实测 BASIN→MOUNTAINS 有 424 wu 水平直边）。
     */
    private static int labelOf(Cell c) {
        TerrainClass t = c.variantTerrain != null ? c.variantTerrain : c.terrainType;
        int variant;
        switch (t) {
            case PLATEAU -> variant = 1;
            case HILLS   -> variant = 2;
            case BASIN   -> variant = 3;
            default      -> variant = 0;
        }
        return ((c.biomeType.ordinal() * 4 + BiomeClassifier.bandOf(c).ordinal()) * 4) + variant;
    }

    /**
     * 轴对齐直线检测：若某条竖直/水平分界线贯穿整个采样区，说明群系边界被
     * 某个只随单一坐标变化的量锁死（典型：只随 z 变化的温度、或按网格对齐的侵蚀增量）。
     * 指标 = 最长连续分界线长度 / 采样区长度，越接近 1 越"直"。
     */
    private static void checkStraightnessAndSnow(CellGenerator gen) {
        final int step = 8;
        final int xHalf = 800, zMax = 1600;
        int xn = (xHalf * 2) / step + 1;
        int zn = zMax / step + 1;

        int[][] lab = new int[xn][zn];
        int land = 0, snowy = 0;
        int[] bandCount = new int[4];

        for (int ix = 0; ix < xn; ix++) {
            for (int iz = 0; iz < zn; iz++) {
                Cell c = gen.sample(-xHalf + ix * step, iz * step);
                lab[ix][iz] = labelOf(c);
                if (c.eClimate > 0.0) {
                    land++;
                    if (c.isSnow) snowy++;
                    bandCount[BiomeClassifier.bandOf(c).ordinal()]++;
                }
            }
        }

        // 最长竖直直线段：固定 x，找"连续多少行在 x-1↔x 处都发生标签变化"
        // （直线边界的定义：边界位置在一段连续 z 上保持同一 x）
        int vBest = 0;
        for (int ix = 1; ix < xn; ix++) {
            int run = 0;
            for (int iz = 0; iz < zn; iz++) {
                if (lab[ix][iz] != lab[ix - 1][iz]) {
                    run++;
                    if (run > vBest) vBest = run;
                } else {
                    run = 0;
                }
            }
        }
        // 最长水平直线段（对称），并记录其位置以便定位
        int hBest = 0, hBestIz = -1, hBestIxEnd = -1;
        for (int iz = 1; iz < zn; iz++) {
            int run = 0;
            for (int ix = 0; ix < xn; ix++) {
                if (lab[ix][iz] != lab[ix][iz - 1]) {
                    run++;
                    if (run > hBest) {
                        hBest = run;
                        hBestIz = iz;
                        hBestIxEnd = ix;
                    }
                } else {
                    run = 0;
                }
            }
        }

        // 边界方向各向异性：沿 z 变化=水平边界，沿 x 变化=竖直边界。
        // 温度只随纬度(z)变 → 群系边界会偏水平；比值 >> 1 说明存在"纬度横条带"。
        int hEdge = 0, vEdge = 0;
        for (int ix = 0; ix < xn; ix++) {
            for (int iz = 0; iz < zn; iz++) {
                if (iz > 0 && lab[ix][iz] != lab[ix][iz - 1]) hEdge++;
                if (ix > 0 && lab[ix][iz] != lab[ix - 1][iz]) vEdge++;
            }
        }
        System.out.println();
        System.out.println("=== 边界方向各向异性（1.0 = 各向同性）===");
        System.out.printf("  水平边界点 %d，竖直边界点 %d，比值 %.2f%n",
            hEdge, vEdge, vEdge == 0 ? 0.0 : hEdge / (double) vEdge);
        System.out.println("  比值接近 1 = 自然（边界各方向均匀）；远大于 1 = 纬度横条带。");

        System.out.println();
        System.out.println("=== 轴对齐直线检测（8 wu 采样，指标=最长直线段/采样区长度）===");
        System.out.printf("  最长竖直直线段：%d 个采样点 = %d wu（占采样区高 %.2f）%n",
            vBest, vBest * step, vBest / (double) zn);
        System.out.printf("  最长水平直线段：%d 个采样点 = %d wu（占采样区宽 %.2f）%n",
            hBest, hBest * step, hBest / (double) xn);
        System.out.println("  （接近 1.00 = 一整条贯穿直线，异常；正常应远小于 1）");
        if (hBestIz >= 0) {
            int wz = hBestIz * step;
            int wxEnd = -xHalf + hBestIxEnd * step;
            int wxStart = wxEnd - hBest * step;
            System.out.printf("  最长水平线段位置：z=%d，x∈[%d, %d]%n", wz, wxStart, wxEnd);
            dumpLine(gen, wxStart, wxEnd, wz, step);
        }

        System.out.println();
        System.out.println("=== 雪线与垂直带 ===");
        System.out.printf("  陆地采样 %d，积雪 %d（%.1f%%）%n",
            land, snowy, land == 0 ? 0.0 : snowy * 100.0 / land);
        BiomeClassifier.ElevationBand[] bands = BiomeClassifier.ElevationBand.values();
        for (int i = 0; i < bands.length; i++) {
            System.out.printf("  %-10s %7d  %5.1f%%%n",
                bands[i], bandCount[i], land == 0 ? 0.0 : bandCount[i] * 100.0 / land);
        }
    }

    /** 打印某条水平线上的标签分量，定位直线来源（群区/带/地形）。 */
    private static void dumpLine(CellGenerator gen, int wxStart, int wxEnd, int wz, int step) {
        StringBuilder a = new StringBuilder(), b = new StringBuilder(), c = new StringBuilder();
        for (int wx = wxStart; wx <= wxEnd; wx += step) {
            Cell cell = gen.sample(wx, wz);
            a.append(symbol(cell.biomeType));
            b.append(BiomeClassifier.bandOf(cell).ordinal());
            c.append(cell.terrainType.name().charAt(0));
        }
        System.out.println("    群区: " + a);
        System.out.println("    带  : " + b);
        System.out.println("    地形: " + c);
    }

    // ===== 1) Whittaker 群区图 =====

    private static void printDiagram() {
        int n = 24;
        System.out.println("=== Whittaker 群区图（列 = 温度 冷→热，行 = 降水 干→湿）===");
        for (int r = n - 1; r >= 0; r--) {
            double m = (r + 0.5) / n;
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < n; c++) {
                sb.append(symbol(WhittakerType.classify((c + 0.5) / n, m))).append(' ');
            }
            System.out.printf("  m=%4.2f | %s%n", m, sb);
        }
        System.out.print("         ");
        for (int c = 0; c < n; c += 4) System.out.printf("^t=%.2f ", (c + 0.5) / n);
        System.out.println();
        for (WhittakerType t : WhittakerType.values()) {
            System.out.printf("  %c = %-20s", symbol(t), t.name());
        }
        System.out.println();
    }

    private static char symbol(WhittakerType t) {
        return switch (t) {
            case ICE -> '*';
            case TUNDRA -> 't';
            case TAIGA -> 'T';
            case GRASSLAND -> ',';
            case TEMPERATE_FOREST -> 'f';
            case TEMPERATE_RAINFOREST -> 'F';
            case DESERT -> 'D';
            case SAVANNA -> 's';
            case SEASONAL_FOREST -> 'j';
            case TROPICAL_RAINFOREST -> 'J';
        };
    }

    // ===== 2) 温度纬向梯度 =====

    private static void printTemperatureGradient(CellGenerator gen) {
        System.out.println();
        System.out.println("=== 温度纬向梯度（z 剖面，x 平均；赤道 → 极地）===");
        double prev = Double.MAX_VALUE;
        for (int wz = 0; wz <= Z_MAX; wz += 1000) {
            double sum = 0;
            int n = 0;
            for (int wx = -X_HALF; wx < X_HALF; wx += 250) {
                sum += gen.sample(wx, wz).temperature;
                n++;
            }
            double avg = sum / n;
            System.out.printf("  z=%6d  temp=%+6.3f%s%n", wz, avg, avg > prev ? "  <-- 非单调" : "");
            prev = avg;
        }
    }

    // ===== 3) 群区分异 + 4) 邻接合法性 =====

    private static void scanWorld(CellGenerator gen) {
        int bandCount = BAND_NAMES.length;
        int[][] typeByBand = new int[bandCount][WhittakerType.values().length];
        Map<TerrainClass, Integer> terrain = new HashMap<>();

        // 邻接统计容器
        Map<Long, Integer> violations = new HashMap<>();
        Map<Long, double[]> ex = new HashMap<>();   // 违例样本（两侧温湿），用于定位根因
        int pairs = 0;
        Set<Long> allowed = diagramAdjacency(48);

        // 先缓存整张网格，避免重复采样
        int xn = (X_HALF * 2) / STEP;
        int zn = Z_MAX / STEP;
        WhittakerType[][] grid = new WhittakerType[zn][xn];
        double[][] tGrid = new double[zn][xn];
        double[][] mGrid = new double[zn][xn];
        for (int iz = 0; iz < zn; iz++) {
            for (int ix = 0; ix < xn; ix++) {
                Cell c = gen.sample(-X_HALF + ix * STEP, iz * STEP);
                double t01 = c.temperature * 0.5 + 0.5;
                double m01 = c.humidity * 0.5 + 0.5;
                grid[iz][ix] = WhittakerType.classify(t01, m01);
                tGrid[iz][ix] = t01;
                mGrid[iz][ix] = m01;
                if (!c.terrainType.isOcean()) terrain.merge(c.terrainType, 1, Integer::sum);
            }
        }

        for (int iz = 0; iz < zn; iz++) {
            int band = bandOf(iz * STEP);
            for (int ix = 0; ix < xn; ix++) {
                typeByBand[band][grid[iz][ix].ordinal()]++;
            }
        }

        // 雪线与垂直带：跨全部纬度带统计（赤道带本就无雪，必须在寒带验证）
        int[] landByBand = new int[bandCount];
        int[] snowByBand = new int[bandCount];
        int[][] elevByBand = new int[bandCount][BiomeClassifier.ElevationBand.values().length];
        for (int iz = 0; iz < zn; iz++) {
            int band = bandOf(iz * STEP);
            for (int ix = 0; ix < xn; ix++) {
                Cell c = gen.sample(-X_HALF + ix * STEP, iz * STEP);
                if (c.eClimate <= 0.0) continue;
                landByBand[band]++;
                if (c.isSnow) snowByBand[band]++;
                elevByBand[band][BiomeClassifier.bandOf(c).ordinal()]++;
            }
        }

        printTypeBands(typeByBand);
        printTerrain(terrain);
        printSnowAndBands(landByBand, snowByBand, elevByBand);

        // 邻接检查必须用**细密测线**：气候场虽已连续，但过渡带可能只有 ~40 wu 宽，
        // 用 STEP=60 的粗网格采样会直接跨过中间带，把连续过渡误报成违例。
        checkAdjacencyFine(gen, allowed);
    }

    /** 沿细密测线（8 wu）检查真实相邻关系的群区跳变 */
    private static void checkAdjacencyFine(CellGenerator gen, Set<Long> allowed) {
        Map<Long, Integer> violations = new HashMap<>();
        Map<Long, double[]> ex = new HashMap<>();
        int pairs = 0;

        for (int wz = 0; wz < Z_MAX; wz += 150) {
            WhittakerType prev = null;
            double pt = 0, pm = 0;
            for (int wx = -1000; wx <= 1000; wx += 8) {
                Cell c = gen.sample(wx, wz);
                double t01 = c.temperature * 0.5 + 0.5;
                double m01 = c.humidity * 0.5 + 0.5;
                WhittakerType cur = WhittakerType.classify(t01, m01);
                if (prev != null) {
                    pairs++;
                    checkPair(prev, cur, allowed, violations, pt, pm, t01, m01, ex);
                }
                prev = cur;
                pt = t01;
                pm = m01;
            }
        }
        System.out.println();
        System.out.printf("=== 群区邻接合法性（细密测线 %d wu 步长，违例应为 0）===%n", 8);
        printAdjacency(pairs, violations, ex);
    }

    /**
     * Whittaker 图中「允许相邻」的群区对：在细网格上取所有 4-邻域接触对。
     * 世界上若出现图里不接触的相邻对，即为生态上不可能的跳变。
     */
    private static Set<Long> diagramAdjacency(int n) {
        WhittakerType[][] g = new WhittakerType[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                g[r][c] = WhittakerType.classify((c + 0.5) / n, (r + 0.5) / n);
            }
        }
        Set<Long> allowed = new HashSet<>();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (c + 1 < n) allowed.add(pair(g[r][c], g[r][c + 1]));
                if (r + 1 < n) allowed.add(pair(g[r][c], g[r + 1][c]));
            }
        }
        return allowed;
    }

    private static void checkPair(WhittakerType a, WhittakerType b, Set<Long> allowed,
                                  Map<Long, Integer> violations,
                                  double t1, double m1, double t2, double m2,
                                  Map<Long, double[]> ex) {
        if (a == b) return;
        long k = pair(a, b);
        if (allowed.contains(k)) return;
        violations.merge(k, 1, Integer::sum);
        ex.computeIfAbsent(k, kk -> new double[]{t1, m1, t2, m2});
    }

    /** 无序类型对编码（ordinal 小的放高位） */
    private static long pair(WhittakerType a, WhittakerType b) {
        int x = a.ordinal(), y = b.ordinal();
        return x < y ? ((long) x << 32) | y : ((long) y << 32) | x;
    }

    private static void unpair(long k, StringBuilder sb) {
        int x = (int) (k >>> 32);
        int y = (int) (k & 0xFFFFFFFFL);
        sb.append(WhittakerType.values()[x].name()).append(" <-> ").append(WhittakerType.values()[y].name());
    }

    private static int bandOf(int wz) {
        for (int i = 0; i < BAND_NAMES.length; i++) {
            if (wz >= BAND_EDGES[i] && wz < BAND_EDGES[i + 1]) return i;
        }
        return BAND_NAMES.length - 1;
    }

    private static void printTypeBands(int[][] typeByBand) {
        System.out.println();
        System.out.println("=== Whittaker 群区分布 × 纬度带 ===");
        WhittakerType[] ts = WhittakerType.values();
        System.out.print("    带         ");
        for (WhittakerType t : ts) System.out.printf("%4s ", symbol(t));
        System.out.println();
        for (int b = 0; b < BAND_NAMES.length; b++) {
            int total = 0;
            for (int v : typeByBand[b]) total += v;
            if (total == 0) continue;
            System.out.printf("  %-10s", BAND_NAMES[b]);
            for (WhittakerType t : ts) {
                System.out.printf("%4.0f%%", typeByBand[b][t.ordinal()] * 100.0 / total);
            }
            System.out.println();
        }
    }

    private static void printSnowAndBands(int[] landByBand, int[] snowByBand, int[][] elevByBand) {
        System.out.println();
        System.out.println("=== 雪线 / 垂直带 × 纬度带 ===");
        BiomeClassifier.ElevationBand[] bands = BiomeClassifier.ElevationBand.values();
        System.out.print("    带        陆地数   积雪%  ");
        for (BiomeClassifier.ElevationBand b : bands) System.out.printf("%10s ", b);
        System.out.println();
        for (int i = 0; i < BAND_NAMES.length; i++) {
            if (landByBand[i] == 0) continue;
            System.out.printf("  %-10s %6d  %5.1f%% ", BAND_NAMES[i], landByBand[i],
                snowByBand[i] * 100.0 / landByBand[i]);
            for (BiomeClassifier.ElevationBand b : bands) {
                System.out.printf("%9.1f%% ", elevByBand[i][b.ordinal()] * 100.0 / landByBand[i]);
            }
            System.out.println();
        }
    }

    private static void printTerrain(Map<TerrainClass, Integer> terrain) {
        System.out.println();
        System.out.println("=== 陆地地形形态分布（决定垂直带谱与变体）===");
        int total = terrain.values().stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<TerrainClass, Integer>> list = new ArrayList<>(terrain.entrySet());
        list.sort(Comparator.<Map.Entry<TerrainClass, Integer>>comparingInt(Map.Entry::getValue).reversed());
        System.out.printf("  陆地采样 %d 点，形态 %d 种%n", total, list.size());
        for (Map.Entry<TerrainClass, Integer> e : list) {
            System.out.printf("  %-20s %7d  %5.1f%%%n", e.getKey(), e.getValue(), e.getValue() * 100.0 / total);
        }
    }

    private static void printAdjacency(int pairs, Map<Long, Integer> violations,
                                       Map<Long, double[]> ex) {
        System.out.println();
        System.out.println("=== 群区邻接合法性（违例必须为 0）===");
        int bad = violations.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf("  相邻采样对 %d，非法邻接 %d（%.4f%%）%n",
            pairs, bad, pairs == 0 ? 0.0 : bad * 100.0 / pairs);
        if (bad == 0) {
            System.out.println("  OK：世界上相邻的所有群区在 Whittaker 图里也相邻，无生态不可能跳变。");
            return;
        }
        List<Map.Entry<Long, Integer>> list = new ArrayList<>(violations.entrySet());
        list.sort(Comparator.<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : list) {
            sb.setLength(0);
            unpair(e.getKey(), sb);
            double[] v = ex.get(e.getKey());
            System.out.printf("    %-46s %4d   样例 (t=%.2f,m=%.2f) -> (t=%.2f,m=%.2f)  dt=%+.2f dm=%+.2f%n",
                sb, e.getValue(), v[0], v[1], v[2], v[3], v[2] - v[0], v[3] - v[1]);
        }
    }
}
