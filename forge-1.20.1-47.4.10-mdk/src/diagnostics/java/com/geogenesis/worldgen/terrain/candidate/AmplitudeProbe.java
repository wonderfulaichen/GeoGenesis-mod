package com.geogenesis.worldgen.terrain.candidate;

/**
 * 各类型族的实际高度分布探针。
 *
 * <p>用来回答一个问题：<b>各族的振幅是否已经按参考的 verticalScale 差异化？</b>
 * 参考的配方内部已经乘了自己的系数（平原 ×0.15、台地 ×0.475、山地 ×0.7），
 * 如果再在外面乘一次就会重复缩放。必须先量再改。</p>
 */
public final class AmplitudeProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        int n = 4000;
        StringBuilder out = new StringBuilder();

        out.append(String.format("%-10s %8s %8s %8s %8s%n", "族", "min", "P50", "max", "跨度"));
        recordOne(out, "plains", n, seed, PlainsCandidate.Variant.PLAINS_GEO);
        recordOne(out, "steppe", n, seed, PlainsCandidate.Variant.STEPPE);
        recordHills(out, n, seed, HillsCandidate.Variant.HILLS_1_GEO);
        recordHills(out, n, seed, HillsCandidate.Variant.DALES_GEO);
        recordMtn(out, n, seed, MountainsCandidate.Variant.M1_GEO);
        recordPlateau(out, n, seed, PlateauCandidate.Variant.REF);
        recordBadlands(out, n, seed);
        recordBasin(out, n, seed);
        System.out.print(out);
    }

    private interface Fn { double at(double x, double z); }

    private static void stats(StringBuilder sb, String name, Fn f, int n) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963229728653;
            double r = 55.0 * Math.sqrt(i + 1);
            double h = f.at(Math.cos(a) * r, Math.sin(a) * r);
            v[i] = h;
            if (h < lo) lo = h;
            if (h > hi) hi = h;
        }
        java.util.Arrays.sort(v);
        sb.append(String.format("%-10s %8.3f %8.3f %8.3f %8.3f%n",
                name, lo, v[n / 2], hi, hi - lo));
    }

    private static void recordOne(StringBuilder sb, String n, int cnt, long seed, PlainsCandidate.Variant v) {
        PlainsCandidate c = new PlainsCandidate(v);
        c.seed(seed);
        stats(sb, n, c::height01, cnt);
    }

    private static void recordHills(StringBuilder sb, int cnt, long seed, HillsCandidate.Variant v) {
        HillsCandidate c = new HillsCandidate(v);
        c.seed(seed);
        stats(sb, v.id(), c::height01, cnt);
    }

    private static void recordMtn(StringBuilder sb, int cnt, long seed, MountainsCandidate.Variant v) {
        MountainsCandidate c = new MountainsCandidate(v);
        c.seed(seed);
        stats(sb, v.id(), c::height01, cnt);
    }

    private static void recordPlateau(StringBuilder sb, int cnt, long seed, PlateauCandidate.Variant v) {
        PlateauCandidate c = new PlateauCandidate(v);
        c.seed(seed);
        stats(sb, v.id(), c::height01, cnt);
    }

    private static void recordBadlands(StringBuilder sb, int cnt, long seed) {
        BadlandsCandidate c = new BadlandsCandidate();
        c.seed(seed);
        stats(sb, "badlands", c::height01, cnt);
    }

    private static void recordBasin(StringBuilder sb, int cnt, long seed) {
        BasinCandidate c = new BasinCandidate(BasinCandidate.Variant.B1);
        c.seed(seed);
        // 盆地直接给 e，换回 0..1 便于对比
        stats(sb, "basin", (x, z) -> (c.e(x, z) + 0.06) / 0.08, cnt);
    }
}