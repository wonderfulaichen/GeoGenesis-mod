package com.geogenesis.worldgen.terrain.candidate;

/**
 * 山形遮罩值分布探针：为 {@code Blender} 的 {@code MTN_BLEND_LOW/HIGH} 定阈值提供实测依据。
 *
 * <p>参考用的是 {@code Blender(mask, region, mountains, 0.3F, 0.8F, 0.575F)}，
 * 但参考的 {@code mask} 值域与我们的 {@link RefNoise.WorleyEdgeAdd} 实现是否一致，
 * 只能实测。这里打印分位数与"超过各阈值的地面占比"。</p>
 *
 * <p>用法：{@code <toml> <size> <step>}</p>
 */
public final class MaskDistributionProbe {

    public static void main(String[] args) {
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 501;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        long seed = 9139912035078620160L;

        CombineCandidate c = new CombineCandidate(
                seed,
                new CombineCandidate.Family[]{ (x, z) -> 0.2 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.PLAIN },
                new float[]{ 1f },
                new CombineCandidate.Family[]{ (x, z) -> 0.6 },
                new CombineCandidate.Category[]{ CombineCandidate.Category.MOUNTAINS },
                new float[]{ 1f },
                (x, z) -> 0.1);

        float[] v = new float[size * size];
        int i = 0;
        for (int z = 0; z < size; z++) {
            double wz = 137 + (z - size / 2.0) * step;
            for (int x = 0; x < size; x++) {
                double wx = -140 + (x - size / 2.0) * step;
                v[i++] = c.mountainMask(wx, wz);
            }
        }
        java.util.Arrays.sort(v);

        System.out.printf("n=%d  step=%d  span=%.0f blocks%n", v.length, step, (double) size * step);
        double[] qs = {0.05, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99};
        for (double q : qs) {
            System.out.printf("  P%-4.0f = %.4f%n", q * 100, v[(int) Math.min(v.length - 1, q * v.length)]);
        }
        System.out.println("  --- fraction of ground above threshold ---");
        for (double t : new double[]{0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80}) {
            int above = 0;
            for (float x : v) if (x >= t) above++;
            System.out.printf("  mask >= %.2f : %5.1f%%%n", t, 100.0 * above / v.length);
        }
    }
}