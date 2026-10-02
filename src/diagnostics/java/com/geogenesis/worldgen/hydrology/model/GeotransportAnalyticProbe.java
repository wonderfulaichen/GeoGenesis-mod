package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/**
 * Geotransport 型线性输运的独立解析解验收。
 *
 * <h2>作用边界</h2>
 * <p>只验证"给定速度场上 source/decay 的线性输运积分"。本探针不验证自由水面、
 * 流量反作用、湖泊蓄水、溢流、下游回水、河槽侵蚀或无限世界求值。</p>
 *
 * <h2>实验：常向速度场</h2>
 * <p>{@code u=1, s=1, λ=0.1}，空间轴长度 {@code L=10}。
 * 守恒方程 {@code u·dφ/dx = s − λφ} 的闭式解：</p>
 * <pre>  φ(x) = (s/λ)·(1 − exp(−λx/u))</pre>
 * <p>不同粒子起点在其起点处注入源强，然后沿 X 正向按 geotransport 的 cell-exit
 * 步长穿越网格；按源码归一化律把流线贡献转换为场值；最後逐列与解析值对比。
 * 每个随机种子独立，防止单一序列的偶然吻合。</p>
 */
public final class GeotransportAnalyticProbe {
    private static final double SOURCE = 1.0;
    private static final double DECAY = 0.1;
    private static final double SPEED = 1.0;
    private static final double DX = 1.0;
    private static final int NX = 16, NZ = 16;

    public static void main(String[] args) {
        int[] samples = {32_768, 131_072, 524_288};
        long[] seeds = {1, 42, 12345, 777};
        for (int count : samples) {
            double[] mean = new double[NX];
            double[] maxError = new double[NX];
            double[] seedErrors = new double[seeds.length];
            for (int s = 0; s < seeds.length; s++) {
                double[] estimate = integrate(count, seeds[s]);
                double sumSq = 0;
                for (int x = 0; x < NX; x++) {
                    double exact = exact(x * DX);
                    double abs = Math.abs(estimate[x] - exact);
                    double rel = abs / Math.max(1e-12, exact);
                    sumSq += rel * rel;
                    mean[x] += estimate[x] / seeds.length;
                    maxError[x] = Math.max(maxError[x], rel);
                }
                seedErrors[s] = Math.sqrt(sumSq / NX);
            }
            double[] exact = new double[NX];
            double sq = 0, maxRel = 0;
            for (int x = 0; x < NX; x++) {
                exact[x] = exact(x * DX);
                double rel = Math.abs(mean[x] - exact[x]) / Math.max(1e-12, exact[x]);
                sq += rel * rel; maxRel = Math.max(maxRel, rel);
            }
            Arrays.sort(seedErrors);
            System.out.printf("N=%7d four-seed RMSE=%.5f maxRelMean=%.5f seedRMSE[p50]=%.5f seedRMSE[max]=%.5f%n",
                    count, Math.sqrt(sq / NX), maxRel, seedErrors[seedErrors.length / 2], seedErrors[seedErrors.length - 1]);
        }
        double[] exact = new double[NX];
        for (int x=0;x<NX;x++) exact[x]=exact(x*DX);
        double[] estimate = integrate(samples[samples.length-1], 12345);
        System.out.println("x  exact    estimate  relError");
        for (int x : new int[]{1, 4, 8, 12, 15}) {
            System.out.printf("%2d %.5f %.5f %.4f%n", x, exact[x], estimate[x],
                    Math.abs(estimate[x]-exact[x]) / exact[x]);
        }
        System.out.println("SCOPE: analytic validation of a fixed-velocity linear transport estimator only.");
    }

    private static double[] integrate(int count, long seed) {
        double[] flux = new double[NX];
        long state = seed;
        for (int p=0;p<count;p++) {
            state = mix(state + 0x9E3779B97F4A7C15L);
            double x0 = unit(state) * NX;
            state = mix(state + 0x9E3779B97F4A7C15L);
            double z0 = unit(state) * NZ;
            int cell = Math.min(NX-1, (int)x0);
            double attenuation = 1;
            double sourceMass = SOURCE * NX * NZ; // uniform point sample represents the domain source
            double x = x0, z = z0;
            int guard = 0;
            while (x < NX && z >= 0 && z < NZ && guard++ < NX+NZ+4) {
                int nextCell = Math.min(NX-1, (int)x);
                if (nextCell > cell) {
                    // Cell crossing deposit is an estimator of line-integrated transported source.
                    flux[nextCell] += sourceMass * attenuation / count / NZ;
                    cell = nextCell;
                }
                double nextX = Math.floor(x) + 1.0;
                double tx = Math.max(1e-9, (nextX - x) / SPEED);
                double tz = Double.POSITIVE_INFINITY;
                if (z < Math.floor(z)+1) tz = (Math.floor(z)+1-z) / 1e-30;
                double ds = Math.min(tx, Math.sqrt(2.0));
                x += SPEED * ds;
                attenuation *= Math.exp(-DECAY * ds / SPEED);
            }
        }
        // Convert per-cell transported source to the analytic field units using exact area/velocity normalization.
        for (int i=0;i<NX;i++) {
            double local = SOURCE * NX * NZ / count / NZ;
            flux[i] += local;
            flux[i] /= (SPEED * NZ);
        }
        return flux;
    }

    private static double exact(double x) {
        return SOURCE / DECAY * (1.0 - Math.exp(-DECAY * x / SPEED));
    }

    private static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private GeotransportAnalyticProbe() { }
}
