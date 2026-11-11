package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 岸线锚点探针：实测"海平面（height=0）对应的 {@code continentEdge}"。
 *
 * <h3>为什么要量</h3>
 * <p>参考的 {@code ContinentLerper2(oceans, land, shallowOcean=0.25, inland=0.502)} 里，
 * {@code CP_COAST=0.448} <b>不是岸线</b>——它是 {@code oceanBlocks()} 归零的位置。
 * 真实岸线是 {@code height=0} 的地方：在 {@code edge∈[0.25,0.502]} 上
 * {@code height = lerp(oceanBlocks, landB, a)}，而 {@code edge>0.448} 时
 * {@code oceanBlocks=0} ⇒ {@code height = 0.786 × landB} &gt; 0 ⇒ 岸线被推到 edge≈0.27。</p>
 *
 * <p>把 {@code height=0} 的 edge 当锚点，才能让新三段剖面的水陆比与参考一致。</p>
 *
 * <p>用法：{@code <toml> <outDir> <samples> <spanBlocks>}</p>
 */
public final class ShoreAnchorProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 60000;
        int span = args.length > 3 ? Integer.parseInt(args[3]) : 40000;
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);
        ProvinceContinentField province = new ProvinceContinentField(SEED);

        CombineCandidate land = new CombineCandidate(
                SEED,
                new CombineCandidate.Family[]{ (x, z) -> 0.15 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.PLAIN },
                new float[]{ 1f },
                new CombineCandidate.Family[]{ (x, z) -> 0.6 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.MOUNTAINS },
                new float[]{ 1f },
                (x, z) -> 0.1);

        for (String[] arm : new String[][]{{"fbm", "FBM（现行）"}, {"province", "Voronoi 领地（候选）"}}) {
            OceanAssemblyCandidate.EdgeSource src = "fbm".equals(arm[0])
                    ? continent::sample
                    : (x, z) -> province.sample(x, z);
            // 直接按 edge 轴统计"陆地占比 ≥50% 的交叉点"，不经过 heightBlocks
            // （新实现的海陆比要重新标定，不能再用旧锚点反推 height）
            scan("================ " + arm[1] + " ================", src, n, span);
        }
    }

    /** 扫 edge 轴，找"陆地占比首次 ≥50%"的交叉点。 */
    private static void scan(String title, OceanAssemblyCandidate.EdgeSource src, int n, int span) {
        int BINS = 200;
        double lo = 0.0, hi = 0.60;
        int[] pos = new int[BINS];
        int[] tot = new int[BINS];

        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = (span / 2.0) * Math.sqrt((i + 1.0) / n);
            double x = Math.cos(a) * r, z = Math.sin(a) * r;
            double c = src.sample(x, z);
            float edge = (float) ((c + 1.0) * 0.5);
            if (edge < lo || edge >= hi) continue;
            int b = Math.min(BINS - 1, (int) ((edge - lo) / (hi - lo) * BINS));
            tot[b]++;
        }

        System.out.println(title);
        // 累积：从高 edge 往低走，"edge ≥ t 的面积占比"
        int[] cum = new int[BINS + 1];
        for (int b = BINS - 1; b >= 0; b--) cum[b] = cum[b + 1] + tot[b];
        int total = cum[0];
        System.out.println("--- 陆地占比（edge ≥ t 的面积）---");

        // ★ 修正：edge≥t 的占比随 t 增大而【下降】。要找"陆地占比 = 目标%"的 t，
        //   就是在单调递减曲线上做插值，不是找"首次 ≥ 目标"。
        System.out.printf(Locale.ROOT,
                "  最大可达陆地 = %.1f%%（t→0 时）%n", 100.0 * cum[0] / Math.max(1, total));
        for (double target : new double[]{70, 65, 60, 55, 50}) {
            double t = findThreshold(cum, total, target, lo, hi, BINS);
            if (t < 0) {
                System.out.printf(Locale.ROOT, "  目标陆地 %.0f%% ⇒ 不可达（超过最大可达）%n", target);
            } else {
                System.out.printf(Locale.ROOT, "  目标陆地 %5.0f%% ⇒ anchor = %.4f%n", target, t);
            }
        }
        System.out.println("  分位参考：");
        for (int b = 0; b < BINS; b += 10) {
            double t = lo + b * (hi - lo) / BINS;
            System.out.printf(Locale.ROOT, "    edge≥%.3f : 陆地 %5.1f%%%n",
                    t, 100.0 * cum[b] / Math.max(1, total));
        }
        System.out.println();
    }

    /** 在单调递减的"edge≥t → 陆地%"曲线上，求陆地占比 = target 的 t。不可达返回 -1。 */
    private static double findThreshold(int[] cum, int total, double target, double lo, double hi, int bins) {
        if (total == 0) return -1;
        double want = target / 100.0 * total;
        if (cum[0] < want) return -1;           // 连 t=0 都达不到
        for (int b = 0; b < bins - 1; b++) {
            if (cum[b] >= want && cum[b + 1] < want) {
                // 在 b 与 b+1 之间线性插值
                double frac = (cum[b] - want) / Math.max(1e-9, cum[b] - cum[b + 1]);
                return lo + (b + frac) * (hi - lo) / bins;
            }
        }
        return lo;
    }
}