package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * <b>海陆分区判据取证</b>（方案 {@code ocean-unified-terrain} 步 5 的阻塞点）。
 *
 * <h3>要回答的问题</h3>
 * <p>步 5 的 S4 失败：{@code ocean-points = 0}，即没有任何点被海洋配方接管。
 * 根因已定位：{@code CombineCandidate.e()} 里的 {@code Math.max(..., 0.0)}
 * 让陆地池<b>永远不输出负值</b>，而我的 {@code oceanWeight} 用 {@code eLand < 0}
 * 作门槛 —— 这个门槛永远不成立。</p>
 *
 * <h3>本探针测什么</h3>
 * <p>要换成独立场（{@code c}）作门槛，必须知道：<b>{@code c} 与 {@code eLand} 的关系</b>。
 * 如果 {@code c < 0} 的区域里 {@code eLand} 已经很高（比如 0.3 e = 38 格），
 * 那用 {@code c} 分区就会把大片"陆地"画成海 ⇒ 假岛。</p>
 * <pre>
 *   P1 c-vs-eland   c 的分位 与 对应 eLand 的分位（判断两者是否同向）
 *   P2 c-negative   在 c<0 的区域里，eLand 的分布（有多少已经 >0.1、>0.3）
 *   P3 coastal-band 在 |c| < 0.1 的过渡带里，eLand 的分布（这是分区最敏感的地方）
 *   P4 crossover    求"eLand 达到某高度时 c 是多少"，用于定分区阈值
 * </pre>
 */
public final class PartitionProbe {

    private static final long SEED = 9139912035078620160L;
    private static final int SIZE = 1401, STEP = 5;
    private static final double CX = -140, CZ = 137;

    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        RecipeMorphCatalog cat = new RecipeMorphCatalog(SEED);
        CombineCandidate land = cat.original();
        if (land == null) {
            System.out.println("original() null");
            return;
        }
        // 与 UnifiedTerrainFullCheck 用同一个 c 代理
        RefNoise.Node cNode = RefNoise.Perlin.ofScale(
                (int) (SEED ^ (SEED >>> 32)) + 7000, 4000f, 6);

        int n = SIZE * SIZE;
        double[] cs = new double[n];
        double[] es = new double[n];
        int k = 0;
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double x = CX + (i - SIZE / 2.0) * STEP;
                double z = CZ + (j - SIZE / 2.0) * STEP;
                cs[k] = cNode.compute((float) x, (float) z) * 2.0 - 1.0;
                es[k] = land.e(x, z);
                k++;
            }
        }
        System.out.printf(Locale.ROOT, "samples=%d%n", n);
        System.out.printf(Locale.ROOT, "c     range: %+.4f .. %+.4f  (P1 %+.4f / P50 %+.4f / P99 %+.4f)%n",
                min(cs), max(cs), q(cs, 0.01), q(cs, 0.50), q(cs, 0.99));
        System.out.printf(Locale.ROOT, "eLand range: %+.4f .. %+.4f  (P1 %+.4f / P50 %+.4f / P99 %+.4f)%n",
                min(es), max(es), q(es, 0.01), q(es, 0.50), q(es, 0.99));

        // P1：c 的分箱 → eLand 的中位
        System.out.println("--- P1: c bucket -> eLand median/n ---");
        for (double lo = -1.0; lo < 1.0; lo += 0.1) {
            double hi = lo + 0.1;
            int cnt = 0;
            double sum = 0;
            java.util.ArrayList<Double> vals = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (cs[i] >= lo && cs[i] < hi) { cnt++; vals.add(es[i]); sum += es[i]; }
            }
            if (cnt == 0) continue;
            double med = median(vals);
            System.out.printf(Locale.ROOT, "  c[%+.1f,%+.1f)  n=%6d  eLand mean=%+.4f  median=%+.4f  max=%+.4f%n",
                    lo, hi, cnt, sum / cnt, med, max(vals));
        }

        // P2：c<0 的区域里 eLand 有多高
        System.out.println("--- P2: eLand distribution where c < 0 ---");
        for (double thr : new double[]{0.0, 0.02, 0.05, 0.10, 0.20, 0.30}) {
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                if (cs[i] < 0 && es[i] > thr) cnt++;
            }
            int totalNeg = 0;
            for (int i = 0; i < n; i++) if (cs[i] < 0) totalNeg++;
            System.out.printf(Locale.ROOT, "  c<0 total=%6d  eLand>%.2f: %6d (%.2f%%)%n",
                    totalNeg, thr, cnt, totalNeg == 0 ? 0 : 100.0 * cnt / totalNeg);
        }

        // P3：过渡带
        System.out.println("--- P3: transition band |c|<0.1 ---");
        int bandCnt = 0;
        java.util.ArrayList<Double> band = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (Math.abs(cs[i]) < 0.1) { bandCnt++; band.add(es[i]); }
        }
        System.out.printf(Locale.ROOT, "  n=%d  eLand P10=%+.4f P50=%+.4f P90=%+.4f%n",
                bandCnt, q(band, 0.10), q(band, 0.50), q(band, 0.90));

        // P4：eLand 达到某高度时 c 的分位（定阈值用）
        System.out.println("--- P4: c distribution where eLand < threshold ---");
        for (double thr : new double[]{0.02, 0.05, 0.10, 0.20}) {
            java.util.ArrayList<Double> v = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) if (es[i] < thr) v.add(cs[i]);
            if (v.isEmpty()) { System.out.println("  thr=" + thr + " none"); continue; }
            System.out.printf(Locale.ROOT, "  eLand<%.2f  n=%6d  c P10=%+.4f P50=%+.4f P90=%+.4f%n",
                    thr, v.size(), q(v, 0.10), q(v, 0.50), q(v, 0.90));
        }
    }

    private static String ROOT() { return ""; }

    private static double min(double[] a) { double m = Double.POSITIVE_INFINITY; for (double v : a) m = Math.min(m, v); return m; }
    private static double max(double[] a) { double m = Double.NEGATIVE_INFINITY; for (double v : a) m = Math.max(m, v); return m; }
    private static double max(java.util.List<Double> a) { double m = Double.NEGATIVE_INFINITY; for (double v : a) m = Math.max(m, v); return m; }
    private static double q(double[] a, double p) {
        double[] b = a.clone(); java.util.Arrays.sort(b);
        return b[Math.max(0, Math.min(b.length - 1, (int) Math.round(p * (b.length - 1))))];
    }
    private static double q(java.util.List<Double> a, double p) {
        double[] b = a.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return b[Math.max(0, Math.min(b.length - 1, (int) Math.round(p * (b.length - 1))))];
    }
    private static double median(java.util.List<Double> a) { return q(a, 0.50); }

    private PartitionProbe() { }
}
