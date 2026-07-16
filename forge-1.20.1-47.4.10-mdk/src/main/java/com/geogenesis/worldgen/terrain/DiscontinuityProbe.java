package com.geogenesis.worldgen.terrain;



/**
 * 逐层断裂诊断工具：扫描地形场每层的最大梯度，精确定位断裂源。
 * <p>
 * v6.0: 使用 per-type eLand 加权混合公式（与 TypeLandShape.sample 一致），
 * 反映真实 eLand 输出。
 */
public final class DiscontinuityProbe {

    // 图层名称（v6.0 调整：移除 voronoi_lo/hi（不再使用），新增 v6.0 per-type eLand）
    private static final String[] LAYER_NAMES = {
        "voronoi_alpha",       // 0: 主导类型 alpha
        "typeW_PLAIN",         // 1
        "typeW_HILLS",         // 2
        "typeW_MOUNTAINS",     // 3
        "typeW_PLATEAU",       // 4
        "typeW_BASIN",         // 5
        "plainNoise",          // 6: PLAIN 独立噪声
        "hillsNoise",          // 7: HILLS 独立噪声
        "mountNoise",          // 8: MOUNTAINS 独立噪声
        "platNoise",           // 9: PLATEAU 独立噪声
        "platShapeNoise",      // 10: PLATEAU shape mask
        "basinNoise",          // 11: BASIN 独立噪声
        "typeELand_PLAIN",     // 12: PLAIN 的 eLand
        "typeELand_HILLS",     // 13: HILLS 的 eLand
        "typeELand_MOUNTAINS", // 14: MOUNTAINS 的 eLand
        "typeELand_PLATEAU",   // 15: PLATEAU 的 eLand
        "typeELand_BASIN",     // 16: BASIN 的 eLand
        "eLand_v6",            // 17: v6.4 sharedNoise×0.7 + perTypeNoise×0.3, then × range + lo
        "dominantType_ord",    // 18: argmax(typeWeights) → 0..4
    };

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        // 扫描区域
        int W = 512, H = 512;
        System.out.println("=== DiscontinuityProbe v6.0 ===");
        System.out.println("Region " + W + "x" + H + " blocks, step=1");
        System.out.println("Params: continentBias=" + p.continentBias());
        System.out.println("Cell spacing=" + VoronoiRegionField.CELL_SPACING
                + ", WARP_AMP=250, SIGMA=200, per-type eLand blending");
        System.out.println();

        // 创建引擎（同包直接引用）
        VoronoiRegionField voronoi = new VoronoiRegionField();
        voronoi.seed(seed);
        TypeGenerators tg = new TypeGenerators();
        tg.seed(seed);
        TypeNoiseProvider tnp = new TypeNoiseProvider();
        tnp.seed(seed);



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
                layers[0][x][z] = blend.alpha;                 // voronoi_alpha

                // 2. 类型权重（按 ordinal 索引，TerrainClass 有 12 值）
                double[] tw = blend.typeWeights;
                layers[1][x][z] = tw[TerrainClass.PLAIN.ordinal()];
                layers[2][x][z] = tw[TerrainClass.HILLS.ordinal()];
                layers[3][x][z] = tw[TerrainClass.MOUNTAINS.ordinal()];
                layers[4][x][z] = tw[TerrainClass.PLATEAU.ordinal()];
                layers[5][x][z] = tw[TerrainClass.BASIN.ordinal()];

                // 3. 各类型独立噪声
                layers[6][x][z]  = tnp.computeNoise(TerrainClass.PLAIN, wx, wz);
                layers[7][x][z]  = tnp.computeNoise(TerrainClass.HILLS, wx, wz);
                layers[8][x][z]  = tnp.computeNoise(TerrainClass.MOUNTAINS, wx, wz);
                layers[9][x][z]  = tnp.computeNoise(TerrainClass.PLATEAU, wx, wz);
                layers[10][x][z] = tnp.computeNoise(TerrainClass.PLAIN, wx, wz); // 占位（platShape 在 computePlateau 内）
                layers[11][x][z] = tnp.computeNoise(TerrainClass.BASIN, wx, wz);

                // 4. 各类型独立 eLand
                double eLandP = TypeGenerators.getTypeCenter(TerrainClass.PLAIN)     + TypeGenerators.getTypeHalfRange(TerrainClass.PLAIN)     * (2 * layers[6][x][z]  - 1);
                double eLandH = TypeGenerators.getTypeCenter(TerrainClass.HILLS)     + TypeGenerators.getTypeHalfRange(TerrainClass.HILLS)     * (2 * layers[7][x][z]  - 1);
                double eLandM = TypeGenerators.getTypeCenter(TerrainClass.MOUNTAINS) + TypeGenerators.getTypeHalfRange(TerrainClass.MOUNTAINS) * (2 * layers[8][x][z]  - 1);
                double eLandT = TypeGenerators.getTypeCenter(TerrainClass.PLATEAU)   + TypeGenerators.getTypeHalfRange(TerrainClass.PLATEAU)   * (2 * layers[9][x][z]  - 1);
                double eLandB = TypeGenerators.getTypeCenter(TerrainClass.BASIN)     + TypeGenerators.getTypeHalfRange(TerrainClass.BASIN)     * (2 * layers[11][x][z] - 1);
                layers[12][x][z] = eLandP;
                layers[13][x][z] = eLandH;
                layers[14][x][z] = eLandM;
                layers[15][x][z] = eLandT;
                layers[16][x][z] = eLandB;

                // 5. v6.5 eLand = blend.lo + (blend.hi - blend.lo) × sharedNoise（v5.2 baseline）
                double sharedNoise = tg.computeSharedNoise(wx, wz);
                double eLand = blend.lo + (blend.hi - blend.lo) * sharedNoise;
                layers[17][x][z] = eLand < 0 ? 0 : (eLand > 1 ? 1 : eLand);

                // 6. 连续主导类型（argmax typeWeights）
                int dominantOrd = TerrainClass.PLAIN.ordinal();
                for (int t = dominantOrd; t < tw.length; t++) {
                    if (tw[t] > tw[dominantOrd]) dominantOrd = t;
                }
                layers[18][x][z] = dominantOrd;                 // dominantType_ord
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

            System.out.printf("%-20s | %8.5f | %8.5f | %8.5f | %5.1f | %6.5f%n",
                    LAYER_NAMES[li], maxGradX, maxGradZ, maxGrad, heightBlocks, meanGrad);
        }

        System.out.println();
        System.out.println("注: e=1.0 ≙ " + 384 + " 世界高度块。|maxΔe| > 0.01（~3.8 块）应关注，>0.05（~19 块）为严重断裂。");
        System.out.println("=== DiscontinuityProbe done ===");
    }
}
