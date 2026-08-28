package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 河网宽深/拓扑诊断探针（2026-08-29 Step 0：先量化，再动行为）。
 *
 * <p>用于验证三个待证假设：</p>
 * <ol>
 *   <li><b>饱和假设（已推翻）</b>：原宽度由 log+smoothstep 驱动，怀疑中下游宽度长期顶在
 *       {@code maxWidth}。实测饱和仅 7.93%，假设不成立；真因是"成河门槛与宽度原点耦合、
 *       且门槛过高杀掉细流"（见 river supply 段），已由幂律 + 解耦 widthAreaRef 修复；</li>
 *   <li><b>切断假设</b>：河网单元（regionSize=640wu）远小于典型河长 →
 *       长河被边界反复切断、每个 region 重新发源 → 汇流面积归零、宽度重置；</li>
 *   <li><b>性能假设</b>：防交叉为全段线性扫描，单元放大后段数暴涨 → O(n²) 不可用，
 *       必须先做空间索引。</li>
 * </ol>
 *
 * <p>输出：河长分布、宽/深分布（含分位数与直方图）、饱和占比、region 切断占比、
 * 构建耗时与防交叉比较次数。</p>
 *
 * <p>用法：{@code gradlew runRiverLineWidthProbe -PprobeArgs="12345 2"}</p>
 */
public final class RiverLineWidthProbe {
    private RiverLineWidthProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        // 半径 N → (2N+1)² 个 region。默认 2 → 25 个（覆盖 3200×3200 wu）。
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(tp, tp.minY(), tp.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineNetwork net = engine.network();
        RiverLineParams P = RiverLineParams.defaults();

        System.out.println("=== RiverLine width/depth/topology diagnostics ===");
        System.out.println("seed=" + seed + "  regionRadius=" + n
                + "  regionSize=" + P.regionSize() + "wu  gridCell=" + P.gridCell()
                + "wu  borderDist=" + P.borderDist() + "wu");
        System.out.println("params: minWidth=" + P.minWidth() + " maxWidth=" + P.maxWidth()
                + " minDepth=" + P.minDepth() + " maxDepth=" + P.maxDepth()
                + " widthAreaRef=" + P.widthAreaRef()
                + " widthExp=" + P.widthExp() + " depthExp=" + P.depthExp()
                + " riverAccumThreshold=" + P.riverAccumThreshold()
                + " riverCount=" + P.riverCount());
        System.out.println("(width/depth are HALF-width in blocks; full width = 2x)");

        // ===== 构建 region 并计时 =====
        RiverLineNetwork.resetCrossComparisons();
        List<RiverLineRegion> regions = new ArrayList<>();
        long worst = 0L, total = 0L;
        for (int rz = -n; rz <= n; rz++) {
            for (int rx = -n; rx <= n; rx++) {
                long t0 = System.nanoTime();
                regions.add(net.region(rx, rz));
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                total += ms;
                worst = Math.max(worst, ms);
            }
        }

        // ===== 采集 =====
        List<Double> lens = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        List<Double> depths = new ArrayList<>();
        List<Double> headW = new ArrayList<>();
        List<Double> tailW = new ArrayList<>();
        int headOut = 0, tailOut = 0, riverCount = 0;
        double growCount = 0;   // 终点宽度 > 起点宽度的河数

        for (RiverLineRegion r : regions) {
            double R = P.regionSize();
            double loX = r.rx * R, hiX = loX + R;
            double loZ = r.rz * R, hiZ = loZ + R;
            for (RiverLineRegion.RiverPolyline pl : r.rivers) {
                MidpointDisplacement.Node[] nd = pl.nodes;
                int m = nd.length;
                if (m < 2) continue;
                riverCount++;

                double len = 0.0;
                for (int i = 1; i < m; i++) {
                    len += Math.hypot(nd[i].x() - nd[i - 1].x(), nd[i].z() - nd[i - 1].z());
                }
                lens.add(len);

                for (int i = 0; i < m; i++) {
                    widths.add(pl.width[i]);
                    depths.add(pl.depth[i]);
                }
                headW.add(pl.width[0]);
                tailW.add(pl.width[m - 1]);
                if (pl.width[m - 1] > pl.width[0] + 1e-9) growCount++;

                if (outOfBody(nd[0], loX, hiX, loZ, hiZ)) headOut++;
                if (outOfBody(nd[m - 1], loX, hiX, loZ, hiZ)) tailOut++;
            }
        }

        System.out.println();
        System.out.println("-- sample --");
        System.out.println("regions=" + regions.size() + "  rivers=" + riverCount
                + "  nodes=" + widths.size());

        System.out.println();
        System.out.println("-- river length (wu) --");
        Stats sl = new Stats(lens);
        System.out.println(sl.render());
        System.out.println("(典型河长应 >> regionSize=" + P.regionSize()
                + "wu；若普遍接近 regionSize，说明河被单元切断)");

        System.out.println();
        System.out.println("-- half-width (block) --");
        Stats sw = new Stats(widths);
        System.out.println(sw.render());
        double sat = ratioAtLeast(widths, P.maxWidth() * 0.95);
        double atMin = ratioAtMost(widths, P.minWidth() * 1.02);
        System.out.println("saturated (>=0.95*maxW=" + fmt(P.maxWidth() * 0.95) + ")  = "
                + pct(sat) + "   <- 饱和假设：占比越高，宽度越" + "像常数");
        System.out.println("atMinWidth (<=1.02*minW=" + fmt(P.minWidth() * 1.02) + ") = "
                + pct(atMin));
        System.out.println(histogram(widths, P.minWidth(), P.maxWidth(), 10));

        System.out.println();
        System.out.println("-- depth (block) --");
        Stats sd = new Stats(depths);
        System.out.println(sd.render());
        System.out.println("depth saturated (>=0.95*maxD) = "
                + pct(ratioAtLeast(depths, P.maxDepth() * 0.95)));

        System.out.println();
        System.out.println("-- per-river head->tail width --");
        Stats sh = new Stats(headW), st = new Stats(tailW);
        System.out.println("head mean=" + fmt(sh.mean) + "  median=" + fmt(sh.median));
        System.out.println("tail mean=" + fmt(st.mean) + "  median=" + fmt(st.median));
        System.out.println("rivers wider at tail = " + pct(growCount / Math.max(1, riverCount)));
        System.out.println("(若 head 普遍贴 minWidth 且 tail 明显更大 → 每条河都在单元内"
                + "『从零长起』，即汇流面积被边界重置)");

        System.out.println();
        System.out.println("-- river supply (why so few rivers?) --");
        int srcSum = 0, rbSum = 0, joinSum = 0;
        for (RiverLineRegion r : regions) {
            srcSum += r.sourceCount;
            rbSum += r.rolledBack;
            joinSum += r.joined;
        }
        System.out.println("candidate sources (e>sourceMinE, non-border) total = " + srcSum
                + "   avg/region = " + fmt(srcSum / (double) regions.size()));
        System.out.println("rolledBack total = " + rbSum
                + "   avg/region = " + fmt(rbSum / (double) regions.size()));
        System.out.println("joined (tree confluence) total = " + joinSum);
        System.out.println("accepted rivers = " + riverCount
                + "   (riverCount cap = " + P.riverCount() + " per region)");
        System.out.println("(若 candidate sources 很大而 accepted 很小 → 追踪回滚率过高；");
        System.out.println(" 若 candidate sources 本身就很小 → 源点门槛 sourceMinE 过严)");

        System.out.println();
        System.out.println("-- e-field distribution (routing source supply) --");
        System.out.println(eFieldHistogram(terrain, n, P));

        System.out.println();
        System.out.println("-- region truncation --");
        System.out.println("rivers with head outside region body = "
                + pct(headOut / Math.max(1.0, riverCount)));
        System.out.println("rivers with tail outside region body = "
                + pct(tailOut / Math.max(1.0, riverCount)));
        System.out.println("(tail 越界 = 河穿出单元被切断；该比例越高，切断假设越成立)");

        System.out.println();
        System.out.println("-- performance --");
        System.out.println("build avg ms/region = " + fmt(total / (double) regions.size())
                + "  worst = " + worst + " ms");
        long cc = RiverLineNetwork.crossComparisons();
        System.out.println("cross-comparisons total = " + cc
                + "  avg/region = " + (cc / Math.max(1, regions.size())));
        System.out.println("(防交叉为全段线性扫描 O(n²)；该值是 Step 1 空间索引的立项依据)");
    }

    private static boolean outOfBody(MidpointDisplacement.Node p,
                                     double loX, double hiX, double loZ, double hiZ) {
        return p.x() < loX || p.x() > hiX || p.z() < loZ || p.z() > hiZ;
    }

    private static double ratioAtLeast(List<Double> v, double t) {
        if (v.isEmpty()) return 0.0;
        int c = 0;
        for (double d : v) if (d >= t) c++;
        return c / (double) v.size();
    }

    private static double ratioAtMost(List<Double> v, double t) {
        if (v.isEmpty()) return 0.0;
        int c = 0;
        for (double d : v) if (d <= t) c++;
        return c / (double) v.size();
    }

    /** 分桶直方图（桶区间 [lo,hi]，越界归入首尾桶）。 */
    private static String histogram(List<Double> v, double lo, double hi, int buckets) {
        if (v.isEmpty()) return "  (no samples)";
        int[] h = new int[buckets];
        for (double d : v) {
            int b = (int) Math.floor((d - lo) / (hi - lo) * buckets);
            b = Math.max(0, Math.min(buckets - 1, b));
            h[b]++;
        }
        int max = 1;
        for (int c : h) max = Math.max(max, c);
        StringBuilder sb = new StringBuilder("  histogram (half-width block, full=2x):\n");
        for (int b = 0; b < buckets; b++) {
            double a = lo + (hi - lo) * b / buckets;
            double c = lo + (hi - lo) * (b + 1) / buckets;
            int bars = (int) Math.round(40.0 * h[b] / max);
            sb.append(String.format("   [%5.2f,%5.2f) %6d |", a, c, h[b]));
            for (int i = 0; i < bars; i++) sb.append('#');
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 在 region 覆盖范围内网格采样地形 e，输出直方图与 sourceMinE 以上占比。
     * 用于判断"河太少"是源点供给不足（e 普遍低于门槛）还是追踪回滚过多。
     */
    private static String eFieldHistogram(CellGenerator terrain, int n, RiverLineParams P) {
        double R = P.regionSize();
        double lo = -n * R, hi = (n + 1) * R;
        double step = 40.0;
        int buckets = 10;
        int[] h = new int[buckets];
        int total = 0, above = 0;
        double sum = 0.0;
        for (double z = lo; z <= hi; z += step) {
            for (double x = lo; x <= hi; x += step) {
                double e = terrain.terrainEQuick(x, z);
                total++;
                sum += e;
                if (e > P.sourceMinE()) above++;
                int b = (int) Math.floor(e * buckets);
                b = Math.max(0, Math.min(buckets - 1, b));
                h[b]++;
            }
        }
        int max = 1;
        for (int c : h) max = Math.max(max, c);
        StringBuilder sb = new StringBuilder();
        sb.append("  samples=").append(total)
                .append("  meanE=").append(fmt(sum / total))
                .append("  e>sourceMinE(").append(P.sourceMinE()).append(") = ")
                .append(pct(above / (double) total)).append('\n');
        for (int b = 0; b < buckets; b++) {
            double a = b / (double) buckets, c = (b + 1) / (double) buckets;
            int bars = (int) Math.round(40.0 * h[b] / max);
            sb.append(String.format("   [%4.2f,%4.2f) %6d |", a, c, h[b]));
            for (int i = 0; i < bars; i++) sb.append('#');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pct(double r) {
        return String.format("%6.2f%%", r * 100.0);
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    /** 简单描述统计（min/mean/median/max/标准差）。 */
    private static final class Stats {
        final double min, max, mean, median, sd;
        final int n;

        Stats(List<Double> v) {
            if (v.isEmpty()) {
                min = max = mean = median = sd = Double.NaN;
                n = 0;
                return;
            }
            List<Double> s = new ArrayList<>(v);
            Collections.sort(s);
            n = s.size();
            min = s.get(0);
            max = s.get(n - 1);
            double sum = 0.0;
            for (double d : s) sum += d;
            mean = sum / n;
            median = s.get(n / 2);
            double var = 0.0;
            for (double d : s) var += (d - mean) * (d - mean);
            sd = Math.sqrt(var / n);
        }

        String render() {
            if (n == 0) return "(empty)";
            return "min=" + fmt(min) + "  mean=" + fmt(mean) + "  median=" + fmt(median)
                    + "  max=" + fmt(max) + "  sd=" + fmt(sd) + "  n=" + n;
        }
    }
}
