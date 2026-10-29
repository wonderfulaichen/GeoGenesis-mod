package com.geogenesis.worldgen.terrain.candidate;

import com.geogenesis.worldgen.terrain.ContinentField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 海底分层标定探针：实测深海/浅海/海岸各占海底多少，并反推 {@code CP_SHELF}。
 *
 * <h3>要解决的问题</h3>
 * <p>岸线重锚到 {@code edge≈0.274} 后，旧的 {@code CP_SHELF=0.13} 是拿旧数据推的，
 * 与新的岸线位置不再匹配：
 * <ul>
 *   <li>若深海（{@code edge<CP_SHELF}）只占海底 8%，而浅海吃掉 60%+，
 *       读作"整片浅滩"，不符合真实海洋（深海平原应占 80%+）</li>
 *   <li>若海岸带（{@code CP_SHORE−CP_COAST_ANCHOR}）占比过大，说明那段太宽</li>
 * </ul>
 *
 * <h3>判据</h3>
 * <p>真实海洋：大陆架（浅海）~8%、大陆坡 ~5%、深海平原 ~85%。
 * 游戏可以放宽，但<b>深海应当是主体</b>（至少 55% 的海底）。</p>
 *
 * <p>本探针输出：<b>按当前阈值</b>的三层占比，以及<b>反推</b>的 "要让深海占 X% 时
 * {@code CP_SHELF} 应取多少"。</p>
 *
 * <p>用法：{@code <toml> <outDir> <samples> <spanBlocks> <cpShelf> <cpShore> <cpAnchor>}</p>
 */
public final class SeabedLayerProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path toml = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 80000;
        int span = args.length > 3 ? Integer.parseInt(args[3]) : 40000;
        double cpShelf = args.length > 4 ? Double.parseDouble(args[4]) : 0.13;
        double cpShore = args.length > 5 ? Double.parseDouble(args[5]) : 0.274;
        double cpAnchor = args.length > 6 ? Double.parseDouble(args[6]) : 0.302;
        Files.createDirectories(out);

        TerrainParams params = ProbeSupport.loadParams(toml, out);
        ContinentField continent = new ContinentField(params);
        continent.seed(SEED);

        // 收集所有海岸以下采样点的 edge 值（= 海底样本）
        int cap = n;
        double[] oceanEdges = new double[cap];
        int m = 0;
        int land = 0;
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = (span / 2.0) * Math.sqrt((i + 1.0) / n);
            double c = continent.sample(Math.cos(a) * r, Math.sin(a) * r);
            double edge = (c + 1.0) * 0.5;
            if (edge < cpAnchor) oceanEdges[m++] = edge;
            else land++;
        }
        java.util.Arrays.sort(oceanEdges, 0, m);

        System.out.printf(Locale.ROOT,
                "样本 %d，跨度 %d 块｜当前阈值 deep<%.3f, shore<%.3f, anchor<%.3f%n",
                n, span, cpShelf, cpShore, cpAnchor);
        System.out.printf(Locale.ROOT, "海底样本 %d（%.1f%%），陆地 %.1f%%%n",
                m, 100.0 * m / n, 100.0 * land / n);

        System.out.println("--- 当前分层（占海底）---");
        int deep = countBelow(oceanEdges, m, cpShelf);
        int shallow = countBelow(oceanEdges, m, cpShore) - deep;
        int coast = m - deep - shallow;
        printLayer("深海", deep, m, "真实 ~85%");
        printLayer("浅海", shallow, m, "真实 ~8%");
        printLayer("海岸", coast, m, "真实 ~5%");

        System.out.println("--- 反推：要让深海占目标比例，CP_SHELF 应取 ---");
        for (double target : new double[]{0.55, 0.65, 0.75, 0.85}) {
            double e = oceanEdges[Math.min(m - 1, (int) (target * m))];
            System.out.printf(Locale.ROOT, "  深海 %.0f%% ⇒ CP_SHELF = %.4f%n", target * 100, e);
        }
        System.out.println("--- 反推：要让浅海占目标比例（在深海之上再切一段），CP_SHORE 应取 ---");
        double shelfGoal = oceanEdges[Math.min(m - 1, (int) (0.75 * m))];
        for (double target : new double[]{0.80, 0.85, 0.90}) {
            double e = oceanEdges[Math.min(m - 1, (int) (target * m))];
            System.out.printf(Locale.ROOT,
                    "  （假设深海→%.2f）浅海累计到 %.0f%% ⇒ CP_SHORE = %.4f%n",
                    shelfGoal, target * 100, e);
        }

        System.out.println("--- 海底 edge 直方图（每 0.02）---");
        int BINS = 20;
        for (int b = 0; b < BINS; b++) {
            double lo = b * 0.02, hi = lo + 0.02;
            int c = countBelow(oceanEdges, m, hi) - countBelow(oceanEdges, m, lo);
            System.out.printf(Locale.ROOT, "  %.2f-%.2f %5.2f%% %s%n",
                    lo, hi, 100.0 * c / m, "#".repeat(Math.max(0, (int) Math.round(60.0 * c / m))));
        }
    }

    private static void printLayer(String name, int cnt, int total, String ref) {
        System.out.printf(Locale.ROOT, "  %-4s %5d  占海底 %5.1f%%   %s%n",
                name, cnt, 100.0 * cnt / Math.max(1, total), ref);
    }

    private static int countBelow(double[] sorted, int len, double v) {
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}