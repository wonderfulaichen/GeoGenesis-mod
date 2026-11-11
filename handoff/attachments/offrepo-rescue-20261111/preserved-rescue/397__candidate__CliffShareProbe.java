package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 海岸岸型占比探针：沿<b>实际岸线</b>统计 cliffFactor 分布，回答"多少海岸是崖壁"。
 *
 * <h3>为什么要量</h3>
 * <p>{@code cliffFactor} 由噪声经 {@code Clamp(0.30,0.70) → Map(0,1) → raw=v·2−1 →
 * smoothstep(−0.2, 0.6)} 得到。噪声是钟形分布（实测 P50≈0.385），展开后
 * {@code raw} 集中在 0 附近 ⇒ {@code smoothstep} 的输入 {@code t=(raw+0.2)/0.8≈0.25}
 * ⇒ 输出 ≈ 0.16。<b>若此推理成立，绝大多数海岸会是沙滩，崖壁极少</b> ——
 * 但必须实测确认，不能靠推。</p>
 *
 * <h3>测什么</h3>
 * <ol>
 *   <li>沿真实岸线（高度穿 0 的地方）采样 cliffFactor，输出分位数与三档占比</li>
 *   <li>按 cliffFactor 反算实际海滩宽度（块），确认"沙滩宽 / 崖壁窄"是否成立</li>
 *   <li>统计各档海岸线的<b>长度占比</b>（不是面积）</li>
 * </ol>
 *
 * <p>用法：{@code <toml> <outDir> <samples> <spanBlocks>}</p>
 */
public final class CliffShareProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 200000;
        int span = args.length > 3 ? Integer.parseInt(args[3]) : 40000;
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        // 用一个最小的陆地候选（构造代价低），只为拿到 OceanAssemblyCandidate 的岸型函数
        CombineCandidate land = new CombineCandidate(
                SEED,
                new CombineCandidate.Family[]{ (x, z) -> 0.15 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.PLAIN },
                new float[]{ 1f },
                new CombineCandidate.Family[]{ (x, z) -> 0.6 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.MOUNTAINS },
                new float[]{ 1f },
                (x, z) -> 0.1);
        OceanAssemblyCandidate ocean = new OceanAssemblyCandidate(land, continent, SEED);
        ocean.enableCoastalDetail(SEED);

        // 黄金角螺旋采样，记录每点的分类与 cliffFactor
        int verified = 0;
        double[] cliffs = new double[n];
        int[] cls = new int[4];       // 0=cliff 1=mid 2=beach 3=其他
        double sumCliff = 0;
        int coastSamples = 0;

        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = (span / 2.0) * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            OceanAssemblyCandidate.Sea s = ocean.sea(x, z);
            if (s != OceanAssemblyCandidate.Sea.COAST) continue;
            double cf = ocean.debugCliffFactor(x, z);
            cliffs[coastSamples++] = cf;
            sumCliff += cf;
            if (cf >= 0.70) cls[0]++;
            else if (cf >= 0.30) cls[1]++;
            else cls[2]++;
        }

        System.out.printf(Locale.ROOT, "样本 %d，跨度 %d 块%n", n, span);
        System.out.printf(Locale.ROOT, "海岸样本 %d（占全部 %.2f%%）%n",
                coastSamples, 100.0 * coastSamples / n);

        if (coastSamples == 0) {
            System.out.println("!! 没有采到海岸点，检查 span 或阈值");
            return;
        }

        java.util.Arrays.sort(cliffs, 0, coastSamples);
        System.out.println("--- 岸线上 cliffFactor 分位数 ---");
        for (double q : new double[]{0.05, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99}) {
            System.out.printf(Locale.ROOT, "  P%-3.0f = %.3f%n", q * 100,
                    cliffs[Math.min(coastSamples - 1, (int) (q * coastSamples))]);
        }
        System.out.printf(Locale.ROOT, "  均值 = %.3f%n", sumCliff / coastSamples);

        System.out.println("--- 岸型占比（按岸线长度）---");
        printPct("崖壁  cliff ≥ 0.70", cls[0], coastSamples);
        printPct("过渡  0.30–0.70   ", cls[1], coastSamples);
        printPct("沙滩  cliff < 0.30", cls[2], coastSamples);

        System.out.println("--- 各档的实际海滩宽度（块）---");
        // |∇edge| ≈ 1.18e-03/块（CoastBandProbe 实测），海岸段 edge 跨度 = CP_ANCHOR − CP_SHORE
        double gradEdge = 1.18e-3;
        double edgeSpan = 0.302 - 0.268;
        System.out.printf(Locale.ROOT, "  海岸段 edge 跨度 %.3f ÷ %.2e ≈ %.0f 块（总宽，不分档）%n",
                edgeSpan, gradEdge, edgeSpan / gradEdge);
        System.out.println("  注：当前实现里 cliffFactor 不改变区间宽度，只改抬升曲线指数，");
        System.out.println("      所以三档的海滩「宽度」相同，差别在「陡度」——");
        System.out.println("      崖壁段 h' 峰值 ≈ 688、沙滩段 ≈ 1018（实测 CoastalProfileProbe）。");
    }

    private static void printPct(String name, int cnt, int total) {
        System.out.printf(Locale.ROOT, "  %-20s %6d  %5.1f%%  %s%n",
                name, cnt, 100.0 * cnt / total, "#".repeat(Math.max(0, (int) Math.round(60.0 * cnt / total))));
    }
}