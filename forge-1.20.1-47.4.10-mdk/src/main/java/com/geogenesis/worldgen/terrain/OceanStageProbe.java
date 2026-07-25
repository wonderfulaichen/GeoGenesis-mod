package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * 海洋多环节诊断探针 —— 追踪完整 eOcean 管线，验证修复隔离性。
 * 
 * <p>分阶段输出 eBase → depthMod → seabed → eOcean → ridgeFade → seamount → eFull，
 * 分别对陆地区域(eLand>0)和海洋区域(eLand<=0)统计最大梯度。
 * 验证 depthMod smoothstep 和 ridgeFade smoothstep 不会污染陆地。
 */
public final class OceanStageProbe {

    private static final String[] STAGE_NAMES = {
        "cBiased",           // 0: 偏置大陆性 [-1, 1]
        "eBase",             // 1: HeightCurve.eFromC
        "depthMod",          // 2: depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2
        "seabed",            // 3: seabedAmp * depthMod * seaBed.sample()
        "eOcean_preFeature", // 4: eBase + seabed (before OceanFeatures)
        "ridgeFade",         // 5: OceanFeatures ridgeFade
        "seamount",          // 6: OceanFeatures seamount
        "eOcean_postFeature",// 7: eOcean + features
        "eLand",             // 8: TypeLandShape.sample()
        "eFull(v8 twoStage)",// 9: two-stage blend (eOcean fade + eLand ramp)
    };

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        int W = 800, H = 800;
        System.out.println("=== OceanStageProbe v1 ===");
        System.out.println("Region " + W + "x" + H + " blocks, step=1");
        System.out.println("Verifying depthMod/ridgeFade isolation: land areas should have delta=0");
        System.out.println();

        // 创建引擎组件（与 CellGenerator.sample() 一致的流水线）
        ContinentField continent = new ContinentField(p);
        continent.seed(seed);
        HeightCurve curve = new HeightCurve(p, -64, 320);
        double continentBias = p.continentBias();
        SeaBedDetail seaBed = new SeaBedDetail(p);
        seaBed.seed(seed);
        double seabedAmp = p.seabedDetail();

        TypeLandShape landShape = new TypeLandShape(p);
        landShape.seed(seed);

        OceanFeatures oceanFeatures = new OceanFeatures();
        oceanFeatures.setSeamountDepthChecker((wx, wz) -> {
            double c = continent.sample(wx, wz);
            double cBiased = c - continentBias;
            double eBase = curve.eFromC(cBiased);
            double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
            double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
            double eOcean = eBase + seabed;
            return Math.min(eOcean, 0.0);
        });
        oceanFeatures.seed(seed);

        // 采样各阶段
        final int L = STAGE_NAMES.length;
        double[][][] stages = new double[L][W][H];

        System.out.print("Sampling " + W + "x" + H + " ... ");
        long t0 = System.currentTimeMillis();

        long landCount = 0, oceanCount = 0;

        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wx = x, wz = z;

                // 1. 大陆性
                double c = continent.sample(wx, wz);
                double cBiased = c - continentBias;
                stages[0][x][z] = cBiased;

                // 2. eBase
                double eBase = curve.eFromC(cBiased);
                stages[1][x][z] = eBase;

                // 3. depthMod (smoothstep 版本)
                double depthMod = 0.6 + smoothstep(-0.2, -0.6, eBase) * 1.2;
                stages[2][x][z] = depthMod;

                // 4. seabed
                double seabed = seabedAmp * depthMod * seaBed.sample(wx, wz);
                stages[3][x][z] = seabed;

                // 5. eOcean pre-feature
                double eOcean = eBase + seabed;
                eOcean = Math.min(eOcean, 0.0);
                eOcean = eOcean < -1.0 ? -1.0 : eOcean;
                stages[4][x][z] = eOcean;

                // 6. OceanFeatures
                OceanFeatures.FeatureResult feat = oceanFeatures.compute(wx, wz, eOcean, cBiased);
                stages[5][x][z] = feat.ridge;
                stages[6][x][z] = feat.seamount;

                // 7. eOcean post-feature
                double eOceanFinal = eOcean + feat.total;
                eOceanFinal = eOceanFinal < -1.0 ? -1.0 : (eOceanFinal > 0.0 ? 0.0 : eOceanFinal);
                stages[7][x][z] = eOceanFinal;

                // 8. eLand
                VoronoiRegionField.BlendResult blend = landShape.sampleBlend(wx, wz);
                double eLand = landShape.sample(blend, wx, wz);
                stages[8][x][z] = eLand;

                // 9. eFull (v8 two-stage blend)
                double oceanFadeStart = p.oceanFadeStart();
                double coastLoc = p.coastLoc();
                double landRampEnd = p.landRampEnd();
                double t1 = smoothstep(oceanFadeStart, coastLoc, cBiased);
                double eOceanStage = eOceanFinal * (1.0 - t1);
                double t2 = smoothstep(coastLoc, landRampEnd, cBiased);
                double eLandStage = eLand * t2;
                double eFull = eOceanStage + eLandStage;
                stages[9][x][z] = eFull;

                if (eLand > 0) landCount++;
                else oceanCount++;
            }
            if ((x + 1) % 100 == 0) System.out.print(".");
        }
        long t1 = System.currentTimeMillis();
        System.out.println(" done in " + (t1 - t0) + "ms");
        System.out.println("Land samples: " + landCount + ", Ocean samples: " + oceanCount);
        System.out.println();

        // =========================================================
        // 梯度统计（分陆地/海洋）
        // =========================================================
        for (String region : new String[]{"land", "ocean"}) {
            boolean isLand = region.equals("land");
            System.out.println("=== Stage Gradient (maxDelta per stage) — " + region + " ===");
            System.out.println("Stage                | maxDelta_X | maxDelta_Z | maxDelta | approxBlk | meanDelta | samples");
            System.out.println("---------------------+------------+------------+----------+-----------+----------+---------");

            for (int li = 0; li < L; li++) {
                double maxGrad = 0, maxGradX = 0, maxGradZ = 0, sumGrad = 0;
                long count = 0;

                for (int x = 0; x < W; x++) {
                    for (int z = 0; z < H; z++) {
                        // 按区域筛选
                        double eLand = stages[8][x][z];
                        boolean thisIsLand = eLand > 0;
                        if (thisIsLand != isLand) continue;

                        double v = stages[li][x][z];
                        double gx = 0, gz = 0;
                        if (x > 0 && isLand == (stages[8][x-1][z] > 0)) {
                            gx = Math.abs(v - stages[li][x-1][z]);
                            if (gx > maxGradX) maxGradX = gx;
                        }
                        if (z > 0 && isLand == (stages[8][x][z-1] > 0)) {
                            gz = Math.abs(v - stages[li][x][z-1]);
                            if (gz > maxGradZ) maxGradZ = gz;
                        }
                        double g = Math.max(gx, gz);
                        if (g > maxGrad) maxGrad = g;
                        sumGrad += g;
                        count++;
                    }
                }

                double meanGrad = count > 0 ? sumGrad / count : 0;
                double heightBlocks = maxGrad * 384;

                System.out.printf("%-20s | %10.6f | %10.6f | %8.6f | %9.1f | %9.6f | %8d%n",
                        STAGE_NAMES[li], maxGradX, maxGradZ, maxGrad, heightBlocks, meanGrad, count);
            }
            System.out.println();
        }

        // =========================================================
        // 海洋隔离验证：eLand > 0 区域各阶段 maxDelta
        // =========================================================
        System.out.println("=== Ocean Isolation Verification ===");
        System.out.println("If depthMod/ridgeFade correctly isolated, land stages should have delta=0.");
        System.out.println();

        // depthMod 陆地最大值（正常应为 0.6，纯陆地无一 > 0.6001）
        double maxDepthModLand = 0;
        double maxRidgeLand = 0;
        double maxSeamountLand = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double eLand = stages[8][x][z];
                if (eLand > 0) {
                    if (stages[2][x][z] > maxDepthModLand) maxDepthModLand = stages[2][x][z];
                    if (stages[5][x][z] > maxRidgeLand) maxRidgeLand = stages[5][x][z];
                    if (stages[6][x][z] > maxSeamountLand) maxSeamountLand = stages[6][x][z];
                }
            }
        }

        System.out.printf("Land depthMod max: %.6f (should be ≤ 0.6 + tiny)%n", maxDepthModLand);
        System.out.printf("Land ridgeFade max: %.6f (should be 0.0)%n", maxRidgeLand);
        System.out.printf("Land seamount max: %.6f (should be 0.0)%n", maxSeamountLand);

        boolean isolated = maxDepthModLand <= 0.601 && maxRidgeLand < 0.001 && maxSeamountLand < 0.001;
        System.out.println("Ocean isolation: " + (isolated ? "✅ PASS" : "❌ FAIL"));
        System.out.println();

        // =========================================================
        // depthMod / ridgeFade 分布统计
        // =========================================================
        System.out.println("=== depthMod value distribution (by eBase range) ===");
        System.out.println("eBase range      | count | depthMod min | depthMod max | depthMod mean");
        System.out.println("------------------+-------+-------------+-------------+--------------");
        String[] ranges = {"eBase < -0.6", "-0.6 <= eBase < -0.2", "-0.2 <= eBase < 0", "eBase >= 0"};
        for (int ri = 0; ri < 4; ri++) {
            long rcnt = 0; double rmin = 999, rmax = -999, rsum = 0;
            for (int x = 0; x < W; x++) {
                for (int z = 0; z < H; z++) {
                    double eb = stages[1][x][z];
                    boolean inRange = switch (ri) {
                        case 0 -> eb < -0.6;
                        case 1 -> eb >= -0.6 && eb < -0.2;
                        case 2 -> eb >= -0.2 && eb < 0;
                        case 3 -> eb >= 0;
                        default -> false;
                    };
                    if (inRange) {
                        double dm = stages[2][x][z];
                        rcnt++;
                        if (dm < rmin) rmin = dm;
                        if (dm > rmax) rmax = dm;
                        rsum += dm;
                    }
                }
            }
            double rmean = rcnt > 0 ? rsum / rcnt : 0;
            System.out.printf("%-17s | %5d | %11.4f | %11.4f | %12.4f%n", ranges[ri], rcnt, rmin, rmax, rmean);
        }

        System.out.println();
        System.out.println("=== OceanStageProbe v1 done ===");
        System.out.println("Threshold: maxDelta > 0.01 (~3.8 blocks) needs watch, >0.05 (~19 blocks) severe.");
    }

    // ===== 内联工具（与 CellGenerator 一致） =====
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }
}
