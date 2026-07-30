package com.geogenesis.worldgen.terrain;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.erosion.RidgeValleyErosion;

/**
 * 粗侵蚀骨架（脊-谷条纹滤镜）A/B 验证探针。
 *
 * <p>目的是验证 {@link RidgeValleyErosion#computeCoarseDelta} 把「圆包状噪声」切成「多峰山脊」
 * （对比度↑、max|grad|↑、均值不显著漂移 = 非剥皮），而不是像流功率方案那样均匀下剥。
 *
 * <p>方法：在固定区域以 spacing=4 采样 terrainE 得到 rawLowRes（与侵蚀 tile 管线 step1 同构），
 * 分别统计 A=原始场 与 B=原始场+粗层 delta 的地形锐度指标（仅陆地子集，海洋侧被 land mask 抑制）。
 * 指标：均值高度 / stddev / mean|grad| / max|grad| / 严格局部峰数，以及 delta 自身分布。
 *
 * <p>判读：B.stdH↑、B.max|grad|↑、且 B.meanH 不显著低于 A → 「切多峰」成立、非剥皮。
 * 若 B.meanH 显著低于 A（均匀下剥）→ 剥皮；若 B.max|grad|≈A 但 mean 漂移 → 仅平移未造脊。
 */
public final class RidgeValleyABProbe {

    private static final int SPACING = 4;       // 与 ERODE_SAMPLING_SPACING 一致
    private static final int GRID = 200;        // 200×200 粗网格 = 800×800 block 区域
    private static final int REGION = GRID * SPACING;

    public static void main(String[] args) {
        long[] seeds;
        if (args.length > 0) {
            String[] parts = args[0].split("[,\\s]+");
            seeds = new long[parts.length];
            for (int i = 0; i < parts.length; i++) seeds[i] = Long.parseLong(parts[i].trim());
        } else {
            seeds = new long[]{98765L, 12345L, 555L, 4242L}; // 含陆地充足 + 海洋为主
        }

        TerrainParams p = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(p, -64, 320);
        double seaE = curve.seaE();
        RidgeValleyErosion.RidgeConfig rcfg = RidgeValleyErosion.RidgeConfig.fromConfig(GeoGenesisConfig.INSTANCE);

        System.out.println("=== RidgeValleyABProbe ===");
        System.out.println("GRID=" + GRID + "  SPACING=" + SPACING + "  REGION=" + REGION + "x" + REGION);
        System.out.println("seaE=" + String.format("%.3f", seaE)
                + "  ridge.enabled=" + rcfg.enabled + "  strength=" + rcfg.strength
                + "  scale=" + rcfg.cellWorldSize + "  cellScale=" + rcfg.stripeFreq
                + "  octaves=" + rcfg.octaves + "  gullyW=" + rcfg.gullyWeight);
        System.out.println("landMask = smoothstep(seaE, seaE+0.10, h); 仅陆地子集计入 A/B");
        System.out.println();

        String hdr = String.format("%-8s %7s | %8s %8s %7s | %8s %8s %7s | %8s %8s %7s | %7s %7s",
                "seed", "land%",
                "A.meanH", "B.meanH", "dMean",
                "A.stdH", "B.stdH", "stdR",
                "A.mGrad", "B.mGrad", "mgR",
                "A.maxG", "B.maxG",
                "A.pks", "B.pks");
        System.out.println(hdr);
        System.out.println("-".repeat(hdr.length()));

        int[] totPeaksA = {0}, totPeaksB = {0};
        int[] totLand = {0};
        double[] sumDeltaMean = {0}, sumDeltaRms = {0}, sumDeltaMin = {0}, sumDeltaMax = {0};

        for (long seed : seeds) {
            CellGenerator gen = new CellGenerator(p, -64, 320);
            gen.seed(seed);

            float[][] A = new float[GRID][GRID];
            boolean[][] land = new boolean[GRID][GRID];       // 全掩码陆地 (h>seaE+0.10)，用于 A/B 高度统计
            boolean[][] landAll = new boolean[GRID][GRID];    // 全部陆地 (h>seaE)，含近岸谷地，用于 delta 零均值验证
            for (int gz = 0; gz < GRID; gz++) {
                for (int gx = 0; gx < GRID; gx++) {
                    int wx = gx * SPACING, wz = gz * SPACING;
                    float h = (float) Math.max(gen.terrainE(wx, wz), -0.05);
                    A[gz][gx] = h;
                    land[gz][gx] = h > seaE + 0.10f;
                    landAll[gz][gx] = h > seaE;
                }
            }

            float[][] delta = RidgeValleyErosion.computeCoarseDelta(
                    A, GRID, SPACING, 0, 0, (float) seaE, rcfg);

            float[][] B = new float[GRID][GRID];
            for (int gz = 0; gz < GRID; gz++)
                for (int gx = 0; gx < GRID; gx++)
                    B[gz][gx] = A[gz][gx] + delta[gz][gx];

            Stats sa = stats(A, land, seaE);
            Stats sb = stats(B, land, seaE);
            DeltaStat ds = deltaStat(delta, land);
            DeltaStat dsAll = deltaStat(delta, landAll);

            int landCnt = 0;
            for (int gz = 0; gz < GRID; gz++)
                for (int gx = 0; gx < GRID; gx++)
                    if (land[gz][gx]) landCnt++;
            totLand[0] += landCnt;
            totPeaksA[0] += sa.nMaxima;
            totPeaksB[0] += sb.nMaxima;
        sumDeltaMean[0] += dsAll.mean; sumDeltaRms[0] += dsAll.rms;
        sumDeltaMin[0] += dsAll.min; sumDeltaMax[0] += dsAll.max;

            float landPct = 100f * landCnt / (GRID * GRID);
            System.out.printf("%-8d %6.1f%% | %8.3f %8.3f %+7.3f | %8.3f %8.3f %6.2fx | %8.4f %8.4f %6.2fx | %7.4f %7.4f | %7d %7d%n",
                    seed, landPct,
                    sa.meanH, sb.meanH, sb.meanH - sa.meanH,
                    sa.stdH, sb.stdH, safeDiv(sb.stdH, sa.stdH),
                    sa.meanGrad, sb.meanGrad, safeDiv(sb.meanGrad, sa.meanGrad),
                    sa.maxGrad, sb.maxGrad,
                    sa.nMaxima, sb.nMaxima);
            System.out.printf("   delta(full-mask land): mean=%+.4f rms=%+.4f min=%+.4f max=%+.4f%n",
                    ds.mean, ds.rms, ds.min, ds.max);
            System.out.printf("   delta(all land):       mean=%+.4f rms=%+.4f min=%+.4f max=%+.4f%n",
                    dsAll.mean, dsAll.rms, dsAll.min, dsAll.max);
        }

        System.out.println("-".repeat(hdr.length()));
        int n = seeds.length;
        System.out.println("TOTAL  land cells=" + totLand[0]
                + "  peaksA=" + totPeaksA[0] + "  peaksB=" + totPeaksB[0]
                + "  Δpeaks=" + (totPeaksB[0] - totPeaksA[0]));
        System.out.printf("AVG delta(all land): mean=%+.4f rms=%+.4f min=%+.4f max=%+.4f%n",
                sumDeltaMean[0] / n, sumDeltaRms[0] / n, sumDeltaMin[0] / n, sumDeltaMax[0] / n);
        System.out.println();
        System.out.println("判读：");
        System.out.println("  peaksB > peaksA 且 max|grad|↑ → 圆包被切出更多峰（multi-peak）✓");
        System.out.println("  delta.mean≈0 且 stdH↑ → 加性脊-谷条纹（零均值），非均匀下剥 ✓");
        System.out.println("  delta.mean 显著 <0 且 max|grad|≈ → 均匀下剥（剥皮）✗");
        System.out.println("  B.meanH 显著 > A 且 stdH 不变 → 仅整体抬升未造脊（需调参）");
    }

    private static final class Stats {
        double meanH, stdH, meanGrad, maxGrad;
        int nMaxima;
    }

    private static Stats stats(float[][] g, boolean[][] land, double seaE) {
        Stats s = new Stats();
        int n = 0;
        double sum = 0;
        for (int z = 0; z < GRID; z++)
            for (int x = 0; x < GRID; x++)
                if (land[z][x]) { sum += g[z][x]; n++; }
        if (n == 0) return s;
        s.meanH = sum / n;
        double vsum = 0;
        for (int z = 0; z < GRID; z++)
            for (int x = 0; x < GRID; x++)
                if (land[z][x]) { double d = g[z][x] - s.meanH; vsum += d * d; }
        s.stdH = Math.sqrt(vsum / n);

        double gsum = 0;
        s.maxGrad = 0;
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                if (!land[z][x]) continue;
                double gx = grad(g, x, z, 1, 0);
                double gz = grad(g, x, z, 0, 1);
                double mag = Math.hypot(gx, gz);
                gsum += mag;
                if (mag > s.maxGrad) s.maxGrad = mag;
            }
        }
        s.meanGrad = gsum / n;

        // 严格局部峰（4 邻域），仅统计明显山体
        double peakE = seaE + 0.25;
        for (int z = 1; z < GRID - 1; z++) {
            for (int x = 1; x < GRID - 1; x++) {
                if (!land[z][x] || g[z][x] < peakE) continue;
                float v = g[z][x];
                if (v > g[z - 1][x] && v > g[z + 1][x] && v > g[z][x - 1] && v > g[z][x + 1])
                    s.nMaxima++;
            }
        }
        return s;
    }

    private static double grad(float[][] g, int x, int z, int dx, int dz) {
        int xm = Math.max(0, x - dx), xp = Math.min(GRID - 1, x + dx);
        int zm = Math.max(0, z - dz), zp = Math.min(GRID - 1, z + dz);
        double denom = (xp - xm + zp - zm) * SPACING;
        if (denom <= 0) return 0;
        return (g[zp][xp] - g[zm][xm]) / denom;
    }

    private static final class DeltaStat {
        double mean, rms, min, max;
    }

    private static DeltaStat deltaStat(float[][] d, boolean[][] land) {
        DeltaStat s = new DeltaStat();
        int n = 0;
        double sum = 0, sq = 0;
        s.min = Double.POSITIVE_INFINITY;
        s.max = Double.NEGATIVE_INFINITY;
        for (int z = 0; z < GRID; z++)
            for (int x = 0; x < GRID; x++)
                if (land[z][x]) {
                    float v = d[z][x];
                    sum += v; sq += v * v; n++;
                    if (v < s.min) s.min = v;
                    if (v > s.max) s.max = v;
                }
        if (n == 0) return s;
        s.mean = sum / n;
        s.rms = Math.sqrt(sq / n);
        return s;
    }

    private static double safeDiv(double a, double b) {
        return b == 0 ? 0 : a / b;
    }
}
