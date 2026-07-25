package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * v7.5：使用真实 TypeLandShape（typeWeighted 公式）做断裂诊断 + 高度分布统计。
 * <p>
 * 新增：per-type 高度分布统计，诊断 PLATEAU-MOUNTAINS 高度差。
 */
public final class DiscontinuityProbe {

    // 图层名称 — 匹配 v7.5 typeWeighted 流水线
    private static final String[] LAYER_NAMES = {
        "blend.lo",                // 0: Voronoi lo
        "blend.hi",                // 1: Voronoi hi
        "blend.alpha",             // 2: 最近 2 cell 权重比
        "typeW_PLAIN",             // 3: PLAIN 连续权重
        "typeW_HILLS",             // 4
        "typeW_MOUNTAINS",         // 5
        "typeW_PLATEAU",           // 6
        "typeW_BASIN",             // 7
        "typeWeighted",            // 8: Sigma wx typeNoise/Sigma w (v7.5)
        "eLand_v7.5",              // 9: lo + range x typeWeighted
        "continent(c)",            // 10: 大陆性 [-1,1]
        "eOcean",                  // 11: c falls to eFromC
        "eFull(v8_twoStage)",      // 12: two-stage blend (eOcean fade + eLand ramp)
        "dominant_argmax",         // 13: argmax(PLAIN..BASIN weights) ordinal 5,6,8,7,10
    };

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        // 扫描区域 — 扩大以覆盖更多完整 cell
        int W = 800, H = 800;
        System.out.println("=== DiscontinuityProbe v7.5 (typeWeighted formula) ===");
        System.out.println("Region " + W + "x" + H + " blocks, step=1");
        System.out.println("Formula: linear interpolation between type centers (no cell grid)");
        System.out.println();

        // 创建真实地形引擎
        TypeLandShape landShape = new TypeLandShape(p);
        landShape.seed(seed);
        CoastlineField coastline = new CoastlineField(p);
        coastline.seed(seed);

        ContinentField continent = new ContinentField(p);
        continent.seed(seed);
        HeightCurve curve = new HeightCurve(p, -64, 320);
        double continentBias = p.continentBias();

        // 分配图层缓冲区
        final int L = LAYER_NAMES.length;
        double[][][] layers = new double[L][W][H];

        System.out.print("Sampling " + W + "x" + H + " ... ");
        long t0 = System.currentTimeMillis();

        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wx = x, wz = z;

                // 1. 连续类型混合
                TerrainCharacterField.BlendResult blend = landShape.sampleBlend(wx, wz);
                layers[0][x][z] = blend.lo;
                layers[1][x][z] = blend.hi;
                layers[2][x][z] = blend.alpha;

                // 2. 类型连续权重
                double[] tw = blend.typeWeights;
                layers[3][x][z] = tw[TerrainClass.PLAIN.ordinal()];
                layers[4][x][z] = tw[TerrainClass.HILLS.ordinal()];
                layers[5][x][z] = tw[TerrainClass.MOUNTAINS.ordinal()];
                layers[6][x][z] = tw[TerrainClass.PLATEAU.ordinal()];
                layers[7][x][z] = tw[TerrainClass.BASIN.ordinal()];

                // 3. 大陆性 c（需在 CoastlineField 之前计算）
                double c = continent.sample(wx, wz);
                double cBiased = c - continentBias;
                layers[10][x][z] = c;

                // 4. TypeLandShape.sample() — 使用海岸线位移后的有效 c（v8）
                double cEdge = cBiased + coastline.warpDisplacement(wx, wz, cBiased);
                double eLand = landShape.sample(blend, wx, wz, cEdge);
                layers[9][x][z] = eLand;

                // 5. 提取 typeWeighted（by inverting formula: tw = (eLand-lo)/range）
                double range = blend.hi - blend.lo;
                double typeWeighted = (range > 1e-10) ? (eLand - blend.lo) / range : 0.5;
                layers[8][x][z] = typeWeighted;

                // 6. eOcean
                double eOcean = curve.eFromC(cBiased);
                eOcean = Math.min(eOcean, 0.0);
                eOcean = eOcean < -1 ? -1 : eOcean;
                layers[11][x][z] = eOcean;

                // 7. eFull (v8 two-stage blend + coastline warp)
                double oceanFadeStart = p.oceanFadeStart();
                double coastLoc = p.coastLoc();
                double landRampEnd = p.landRampEnd();
                double t1 = smoothstep(oceanFadeStart, coastLoc, cEdge);
                double eOceanStage = eOcean * (1.0 - t1);
                double t2 = smoothstep(coastLoc, landRampEnd, cEdge);
                double eLandStage = eLand * t2;
                double eFull = eOceanStage + eLandStage;
                layers[12][x][z] = eFull;

                // 8. dominant argmax
                int best = TerrainClass.PLAIN.ordinal();
                int[] landTypes = {TerrainClass.PLAIN.ordinal(), TerrainClass.HILLS.ordinal(),
                    TerrainClass.MOUNTAINS.ordinal(), TerrainClass.PLATEAU.ordinal(), TerrainClass.BASIN.ordinal()};
                for (int t : landTypes) {
                    if (tw[t] > tw[best]) best = t;
                }
                layers[13][x][z] = best;
            }
            if ((x + 1) % 100 == 0) System.out.print(".");
        }
        long t1 = System.currentTimeMillis();
        System.out.println(" done in " + (t1 - t0) + "ms");

        // =========================================================
        // 1. 梯度统计（断裂诊断）
        // =========================================================
        System.out.println();
        System.out.println("=== 断裂诊断（每层最大梯度） ===");
        System.out.println("Layer                | maxDelta_X | maxDelta_Z | maxDelta | approxBlk | meanDelta | p99Delta | at(z)");
        System.out.println("---------------------+------------+------------+----------+-----------+----------+---------+--------");

        for (int li = 0; li < L; li++) {
            double maxGrad = 0, maxGradX = 0, maxGradZ = 0, sumGrad = 0;
            long count = 0;
            int maxX = 0, maxZ = 0;

            int totalPixels = (W - 1) * (H - 1);
            double[] allGrads = new double[totalPixels];
            int gi = 0;

            for (int x = 0; x < W; x++) {
                for (int z = 0; z < H; z++) {
                    double v = layers[li][x][z];
                    double gx = 0, gz = 0;
                    if (x > 0) {
                        gx = Math.abs(v - layers[li][x - 1][z]);
                        if (gx > maxGradX) maxGradX = gx;
                    }
                    if (z > 0) {
                        gz = Math.abs(v - layers[li][x][z - 1]);
                        if (gz > maxGradZ) maxGradZ = gz;
                    }
                    double g = Math.max(gx, gz);
                    if (g > maxGrad) {
                        maxGrad = g;
                        maxX = x;
                        maxZ = z;
                    }
                    sumGrad += g;
                    count++;
                    if (gi < totalPixels) allGrads[gi++] = g;
                }
            }

            double meanGrad = count > 0 ? sumGrad / count : 0;
            java.util.Arrays.sort(allGrads);
            double p99 = allGrads[(int)(totalPixels * 0.99)];
            double heightBlocks = maxGrad * 384;

            System.out.printf("%-20s | %10.6f | %10.6f | %8.6f | %9.1f | %9.6f | %7.6f | (%d,%d)%n",
                    LAYER_NAMES[li], maxGradX, maxGradZ, maxGrad, heightBlocks, meanGrad, p99, maxX, maxZ);
        }

        // dominant_argmax 跳变率
        {
            int jumps = 0, total = 0;
            for (int x = 1; x < W; x++) {
                for (int z = 1; z < H; z++) {
                    if (layers[13][x][z] != layers[13][x - 1][z]) jumps++;
                    if (layers[13][x][z] != layers[13][x][z - 1]) jumps++;
                    total += 2;
                }
            }
            double jumpRate = (double) jumps / total * 100;
            System.out.printf("%-20s   jump rate %.2f%% (argmax discrete jumps)%n", "dominant_argmax", jumpRate);
        }

        // =========================================================
        // 2. 高度分布统计（per dominant type）
        // =========================================================
        System.out.println();
        System.out.println("=== Height Distribution (grouped by dominantTerrain, eLand>0) ===");
        System.out.println("Type       | samples | eLand min | eLand max | eLand mean | Y min | Y max | Y mean | Y p50 | Y p10 | Y p90");
        System.out.println("-----------+---------+-----------+-----------+------------+-------+-------+--------+-------+-------+-------");

        java.util.Map<Integer, java.util.ArrayList<Double>> typeHeights = new java.util.HashMap<>();
        java.util.Map<Integer, java.util.ArrayList<Double>> typeELands = new java.util.HashMap<>();
        int[] landTypeOrdinals = {TerrainClass.PLAIN.ordinal(), TerrainClass.HILLS.ordinal(),
            TerrainClass.MOUNTAINS.ordinal(), TerrainClass.PLATEAU.ordinal(), TerrainClass.BASIN.ordinal()};
        for (int t : landTypeOrdinals) {
            typeHeights.put(t, new java.util.ArrayList<>());
            typeELands.put(t, new java.util.ArrayList<>());
        }

        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double eLand = layers[9][x][z];
                if (eLand <= 0) continue;
                int domType = (int) layers[13][x][z];
                double mcY = 63.0 + eLand * 257.0;
                typeHeights.get(domType).add(mcY);
                typeELands.get(domType).add(eLand);
            }
        }

        for (int t : landTypeOrdinals) {
            java.util.ArrayList<Double> elist = typeELands.get(t);
            java.util.ArrayList<Double> hlist = typeHeights.get(t);
            int n = elist.size();
            if (n == 0) {
                String name = TerrainClass.byId(t).name();
                System.out.printf("%-10s | %7d | %9s | %9s | %10s | %5s | %5s | %6s | %5s | %5s | %5s%n",
                        name, 0, "-", "-", "-", "-", "-", "-", "-", "-", "-");
                continue;
            }

            double[] eArr = new double[n];
            for (int i = 0; i < n; i++) eArr[i] = elist.get(i);
            double[] hArr = new double[n];
            for (int i = 0; i < n; i++) hArr[i] = hlist.get(i);
            java.util.Arrays.sort(eArr);
            java.util.Arrays.sort(hArr);

            double eMin = eArr[0], eMax = eArr[n-1], eMean = 0;
            double hMin = hArr[0], hMax = hArr[n-1], hMean = 0;
            double hP50 = hArr[n/2], hP10 = hArr[(int)(n*0.10)], hP90 = hArr[(int)(n*0.90)];
            for (double v : eArr) eMean += v;
            for (double v : hArr) hMean += v;
            eMean /= n;
            hMean /= n;

            String name = TerrainClass.byId(t).name();
            System.out.printf("%-10s | %7d | %9.4f | %9.4f | %10.4f | %5.0f | %5.0f | %6.0f | %5.0f | %5.0f | %5.0f%n",
                    name, n, eMin, eMax, eMean, hMin, hMax, hMean, hP50, hP10, hP90);
        }

        // =========================================================
        // 3. 过渡区分析（PLATEAU-MOUNTAINS 混合区）
        // =========================================================
        System.out.println();
        System.out.println("=== PLATEAU-MOUNTAINS transition zone ===");
        System.out.println("Condition: typeWeights[PLATEAU] > 0.10 && typeWeights[MOUNTAINS] > 0.10");

        java.util.ArrayList<Double> transHeights = new java.util.ArrayList<>();
        java.util.ArrayList<Double> transELands = new java.util.ArrayList<>();
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wP = layers[6][x][z];
                double wM = layers[5][x][z];
                if (wP > 0.10 && wM > 0.10) {
                    double eLand = layers[9][x][z];
                    if (eLand > 0) {
                        transELands.add(eLand);
                        transHeights.add(63.0 + eLand * 257.0);
                    }
                }
            }
        }
        int tn = transELands.size();
        if (tn > 0) {
            double[] teArr = new double[tn];
            double[] thArr = new double[tn];
            for (int i = 0; i < tn; i++) {
                teArr[i] = transELands.get(i);
                thArr[i] = transHeights.get(i);
            }
            java.util.Arrays.sort(teArr);
            java.util.Arrays.sort(thArr);
            double tMeanE = 0, tMeanH = 0;
            for (double v : teArr) tMeanE += v;
            for (double v : thArr) tMeanH += v;
            tMeanE /= tn; tMeanH /= tn;

            System.out.printf("Transition samples: %d%n", tn);
            System.out.printf("eLand  min/mean/max: %.4f / %.4f / %.4f%n", teArr[0], tMeanE, teArr[tn-1]);
            System.out.printf("Y      min/mean/max: %.0f / %.0f / %.0f%n", thArr[0], tMeanH, thArr[tn-1]);
            System.out.printf("Y      p10/p50/p90: %.0f / %.0f / %.0f%n",
                    thArr[(int)(tn*0.10)], thArr[tn/2], thArr[(int)(tn*0.90)]);
        } else {
            System.out.println("No transition samples (try larger region or lower threshold)");
        }

        System.out.println();
        System.out.println("Note: MC Y = 63 + eLand x 257. e=1.0 approx 320.");
        System.out.println("maxDelta > 0.01 (~3.8 blocks) needs watch, >0.05 (~19 blocks) severe.");
        System.out.println("=== DiscontinuityProbe v7.5 done ===");
    }

    // ===== 内联工具 =====
    private static double saturate(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = saturate((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }
}
