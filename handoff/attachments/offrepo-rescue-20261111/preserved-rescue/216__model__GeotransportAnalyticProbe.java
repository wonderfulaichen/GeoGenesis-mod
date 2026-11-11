package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/**
 * 对 geotransport CUDA 源码（path.cu）的【算法级】常向场解析验证。
 *
 * <p><b>范围</b>：只验随机流线积分估计器能否复现其自身的闭式期望；
 * 不验 DEM 梯度、不验湖泊、不含水深反馈，不能称流体/水文模型。</p>
 *
 * <p>场为 2D 规则格，但速度仅 x 分量为 u>0，source 与 decay 均匀。
 * CUDA 算法对每个 uniform 起点，进入新 cell 时累计 {@code S·exp(-λ·travelTime)}，
 * 最后除以样本数及 {@code norm=|u·dy|}；同时每格加本地项 {@code source·dx·dy/norm}。
 * 可解析的蒙特卡洛期望（第 i 列右边界）为：</p>
 * <pre>
 * φ_i = source·dx/u + source/λ · (1 - exp(-λ·i·dx/u))
 * </pre>
 * <p>第一项即 CUDA 的 local-source normalization；第二项是均匀起点在上游长度积分的期望。
 * 该式是 estimator 的解析期望，不是任意地形上的水位/流量真解。</p>
 */
public final class GeotransportAnalyticProbe {
    private static final int NX = 20;
    private static final int NY = 12;
    private static final double DX = 1.0;
    private static final double DY = 1.0;
    private static final double U = 1.0;
    private static final double SOURCE = 1.0;
    private static final double DECAY = 0.1;
    private static final double EPSILON = 1e-12;

    public static void main(String[] args) {
        long[] seeds = {1, 42, 12345, 777};
        int[] sampleCounts = {12_000, 48_000, 192_000};
        double[] expected = expectedField();
        for (int count : sampleCounts) {
            double[] mean = new double[NX];
            double[] rmses = new double[seeds.length];
            double maxRelMean = 0;
            for (int s = 0; s < seeds.length; s++) {
                double[] estimate = solve(count, seeds[s]);
                double sumSq = 0;
                for (int i = 0; i < NX; i++) {
                    double rel = Math.abs(estimate[i] - expected[i]) / expected[i];
                    sumSq += rel * rel;
                    mean[i] += estimate[i] / seeds.length;
                }
                rmses[s] = Math.sqrt(sumSq / NX);
            }
            double meanSq = 0;
            for (int i = 0; i < NX; i++) {
                double rel = Math.abs(mean[i] - expected[i]) / expected[i];
                meanSq += rel * rel;
                maxRelMean = Math.max(maxRelMean, rel);
            }
            Arrays.sort(rmses);
            double rmse = Math.sqrt(meanSq / NX);
            System.out.printf("N=%7d  4-seed mean RMSE=%.5f  maxRel=%.5f  seed p50=%.5f pmax=%.5f%n",
                    count, rmse, maxRelMean, rmses[2], rmses[3]);
        }
        System.out.println("\nx   analytic-mean   MC-192k-seed42   relError");
        double[] example = solve(sampleCounts[sampleCounts.length - 1], 42);
        for (int i : new int[]{0, 4, 9, 14, 19}) {
            System.out.printf("%2d  %13.6f   %13.6f   %.5f%n", i,
                    expected[i], example[i], Math.abs(example[i] - expected[i]) / expected[i]);
        }
        System.out.println("PASS means estimator matches analytic expectation within its sampling error only.");
        System.out.println("NOT VERIFIED: continuous-flow physics, terrain feedback, water mass state, lakes, boundary closure.");
    }

    private static double[] solve(int count, long seed) {
        double[] flux = new double[NX * NY];
        double area = DX * DY;
        double probabilityDensity = 1.0 / (area * NX * NY);
        double sampledSource = SOURCE / probabilityDensity;       // CUDA: source[ind] / P
        long state = seed;
        for (int sample = 0; sample < count; sample++) {
            state = mix(state + 0x9E3779B97F4A7C15L);
            double x = unit(state) * NX;
            state = mix(state + 0x9E3779B97F4A7C15L);
            double y = unit(state) * NY;
            int current = (int) x;
            double attenuation = 1.0;
            int guard = 0;
            while (x >= 0 && x < NX && y >= 0 && y < NY && attenuation > EPSILON && guard++ < NX + 1) {
                // u=(1,0): exact distance to next x-cell boundary; CUDA samples the flow at each step.
                double nextBoundary = Math.floor(x) + 1.0;
                double travel = (nextBoundary - x) / U;
                double maxDiagonal = Math.sqrt(DX * DX + DY * DY) / U;
                travel = Math.min(travel, maxDiagonal);
                x += U * travel;
                attenuation *= Math.exp(-DECAY * travel);
                int entered = (int) x;
                if (entered != current && entered >= 0 && entered < NX) {
                    int row = Math.min(NY - 1, Math.max(0, (int) y));
                    flux[entered * NY + row] += sampledSource * attenuation;
                    current = entered;
                }
            }
        }
        double norm = Math.abs(U * DY);                            // CUDA normalize: |u.x*scale.y|
        double[] result = new double[NX];
        for (int i = 0; i < NX; i++) {
            double sum = 0;
            for (int j = 0; j < NY; j++) {
                double localSource = SOURCE * area;                 // CUDA local source term
                sum += (localSource + flux[i * NY + j] / count) / norm;
            }
            result[i] = sum / NY;                                   // average over identical rows
        }
        return result;
    }

    private static double[] expectedField() {
        double[] result = new double[NX];
        double local = SOURCE * DX * DY / Math.abs(U * DY);
        double transportScale = SOURCE / DECAY;
        for (int i = 0; i < NX; i++) {
            double upstreamLength = i * DX;
            result[i] = local + transportScale * (1.0 - Math.exp(-DECAY * upstreamLength / U));
        }
        return result;
    }

    private static double unit(long bits) { return (bits >>> 11) * 0x1.0p-53; }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private GeotransportAnalyticProbe() { }
}
