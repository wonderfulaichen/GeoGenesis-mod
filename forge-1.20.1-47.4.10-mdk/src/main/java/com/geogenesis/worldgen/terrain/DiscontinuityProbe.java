package com.geogenesis.worldgen.terrain;



/**
 * 逐层断裂诊断工具：扫描地形场每层的最大梯度，精确定位断裂源。
 * <p>
 * 用法：{@code java com.geogenesis.worldgen.terrain.DiscontinuityProbe}
 * <p>
 * 在 terrain 包内直接访问 VoronoiRegionField + TypeGenerators，
 * 对 9 个图层独立计算 max |相邻像素 e 差异|，并换算为等效世界高度块数。
 */
public final class DiscontinuityProbe {

    // 图层名称
    private static final String[] LAYER_NAMES = {
        "voronoi_lo",          // 0
        "voronoi_hi",          // 1
        "voronoi_alpha",       // 2
        "typeW_PLAIN",         // 3
        "typeW_HILLS",         // 4
        "typeW_MOUNTAINS",     // 5
        "typeW_PLATEAU",       // 6
        "typeW_BASIN",         // 7
        "sharedNoise",         // 8
        "eLand_preBasin",      // 9: 不含 BASIN 调制的 eLand
        "eLand",               // 10: 完整 eLand（含 BASIN lerp）
        "dominantType_ord",    // 11: argmax(typeWeights) → 0..4
        "cellType_hash_ord",   // 12: 旧 hash 最近 cell → 0..4（对比用）
    };

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        // 扫描区域
        int W = 512, H = 512;
        System.out.println("=== DiscontinuityProbe ===");
        System.out.println("Region " + W + "x" + H + " blocks, step=1");
        System.out.println("Params: continentBias=" + p.continentBias());
        System.out.println("Cell spacing=" + VoronoiRegionField.CELL_SPACING
                + ", WARP_AMP=250, SIGMA=200, sharedNoise warpAmp=300");
        System.out.println();

        // 创建引擎（同包直接引用）
        VoronoiRegionField voronoi = new VoronoiRegionField();
        voronoi.seed(seed);
        TypeGenerators tg = new TypeGenerators();
        tg.seed(seed);

        // 分配图层缓冲区：LAYERS x W x H
        final int L = LAYER_NAMES.length;
        double[][][] layers = new double[L][W][H];

        System.out.print("Sampling " + W + "x" + H + " ... ");
        long t0 = System.currentTimeMillis();

        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                double wx = x, wz = z;

                // 1. Voronoi 混合
                VoronoiRegionField.BlendResult blend = voronoi.sampleBlend(wx, wz);
                layers[0][x][z] = blend.lo;                    // voronoi_lo
                layers[1][x][z] = blend.hi;                    // voronoi_hi
                layers[2][x][z] = blend.alpha;                 // voronoi_alpha

                // 2. 类型权重（按 ordinal 索引，TerrainClass 有 12 值）
                double[] tw = blend.typeWeights;
                layers[3][x][z] = tw[TerrainClass.PLAIN.ordinal()];     // 5
                layers[4][x][z] = tw[TerrainClass.HILLS.ordinal()];     // 6
                layers[5][x][z] = tw[TerrainClass.MOUNTAINS.ordinal()]; // 8
                layers[6][x][z] = tw[TerrainClass.PLATEAU.ordinal()];   // 7
                layers[7][x][z] = tw[TerrainClass.BASIN.ordinal()];     // 10

                // 3. 共享噪声
                double noise = tg.computeSharedNoise(wx, wz);
                layers[8][x][z] = noise;                       // sharedNoise

                // 4. eLand 前（不含 BASIN 调制）
                double eLandPre = blend.lo + (blend.hi - blend.lo) * noise;
                layers[9][x][z] = eLandPre;                    // eLand_preBasin

                // 5. eLand 后（含 BASIN 连续 lerp，用 TypeLandShape 逻辑）
                double basinW = tw[TerrainClass.BASIN.ordinal()];
                double basinBase = TypeGenerators.basinModulate(noise);
                double baseMod = noise * (1.0 - basinW) + basinBase * basinW;
                double eLandPost = blend.lo + (blend.hi - blend.lo) * baseMod;
                layers[10][x][z] = eLandPost < 0 ? 0 : (eLandPost > 1 ? 1 : eLandPost);

                // 6. 连续主导类型（argmax typeWeights，忽略 OCEAN/DEEP_OCEAN/LAKE/RIVER/BEACH/PEAK/SNOW）
                //    从 TerrainClass.PLAIN 开始扫描确保只选 5 陆地类型
                int dominantOrd = TerrainClass.PLAIN.ordinal();
                for (int t = dominantOrd; t < tw.length; t++) {
                    if (tw[t] > tw[dominantOrd]) dominantOrd = t;
                }
                layers[11][x][z] = dominantOrd;                 // dominantType_ord

                // 7. 旧 hash 最近 cell 类型（对比用）
                TerrainClass hashType = voronoi.dominantType(wx, wz);
                layers[12][x][z] = hashType.ordinal();          // cellType_hash_ord
            }
            if ((x + 1) % 64 == 0) System.out.print(".");
        }
        long t1 = System.currentTimeMillis();
        System.out.println(" done in " + (t1 - t0) + "ms");

        // 梯度统计：max x 梯度 + max z 梯度 + mean 全梯度
        System.out.println();
        System.out.println("Layer                | maxΔe_X  | maxΔe_Z  | maxΔe    | ≙高度块 | meanΔe ");
        System.out.println("---------------------+----------+----------+----------+---------+--------");

        // 如果 eLand 偏离格点扫描，取真正最大
        for (int li = 0; li < L; li++) {
            double maxGrad = 0, maxGradX = 0, maxGradZ = 0, sumGrad = 0;
            long count = 0;
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
                    if (g > maxGrad) maxGrad = g;
                    sumGrad += g;
                    count++;
                }
            }

            double meanGrad = count > 0 ? sumGrad / count : 0;
            // e 空间 1.0 ≙ (320 - (-64)) = 384 块（MC 世界高度）
            double heightBlocks = maxGrad * 384;

            // 离散型图层（dominantType_ord / cellType_hash_ord）：max=4 换为 NaN
            String maxStr;
            if (li == 11 || li == 12) {
                // 离散枚举梯度跳 1 个单位（如类型从 2→3）是正常的，用 ord 差异
                maxStr = String.format("ord%+d", (int)(maxGrad * 384));
            } else {
                maxStr = String.format("%.5f", maxGrad);
            }

            System.out.printf("%-20s | %8.5f | %8.5f | %8.5f | %5.1f | %6.5f%n",
                    LAYER_NAMES[li], maxGradX, maxGradZ, maxGrad, heightBlocks, meanGrad);
        }

        System.out.println();
        System.out.println("注: e=1.0 ≙ " + 384 + " 世界高度块。|maxΔe| > 0.01（~3.8 块）应关注，>0.05（~19 块）为严重断裂。");
        System.out.println("=== DiscontinuityProbe done ===");
    }
}
