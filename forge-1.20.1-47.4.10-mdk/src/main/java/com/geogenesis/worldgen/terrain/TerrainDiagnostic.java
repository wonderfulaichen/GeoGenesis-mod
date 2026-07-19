package com.geogenesis.worldgen.terrain;

/**
 * 全面地形诊断工具：扫描大区域，统计各地形类型的
 * 频率、高度分布、起伏幅度、聚类大小、海陆比。
 */
public final class TerrainDiagnostic {

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        // 扫描参数：4096×4096 中心在 (0,0)，步长 16 = 65536 采样点
        // 范围覆盖正负坐标，避免单一大陆片内偏差
        int W = 4096, H = 4096;
        int step = 16;
        int originX = -2048, originZ = -2048;
        int count = (W / step) * (H / step);
        System.out.println("=== TerrainDiagnostic ===");
        System.out.println("Seed: " + seed);
        System.out.println("Region: " + W + "x" + H + " blocks centered at (0,0), step=" + step
                + " (" + count + " samples)");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // ===== 统计变量 =====
        // 基础
        int totalOcean = 0, totalLand = 0;
        int seaLevelInt = 63;

        // 类型频率（land only）
        int[] typeCount = new int[TerrainClass.COUNT];
        double[] typeSumE = new double[TerrainClass.COUNT];
        double[] typeSumELand = new double[TerrainClass.COUNT];
        double[] typeSumY = new double[TerrainClass.COUNT];
        double[] typeSumE2 = new double[TerrainClass.COUNT]; // 方差
        double[] typeSumELand2 = new double[TerrainClass.COUNT];

        // 聚类大小追踪
        int clusterMinW = Integer.MAX_VALUE, clusterMaxW = 0;
        double clusterSumW = 0;
        int clusterCount = 0;

        // 山体坡度累积
        double mountSlopeSum = 0;
        int mountSlopeCount = 0;

        // 大陆性分布
        double contSum = 0, contSum2 = 0;

        // 高度带统计（整型 Y）
        int[] heightBands = new int[321]; // 0..320

        // 2-pass: 先扫一遍收集类型 + 高度数据
        // 用网格采样，每格记录类型以便后续聚类分析
        int cols = W / step, rows = H / step;
        TerrainClass[][] typeGrid = new TerrainClass[cols][rows];
        double[][] yGrid = new double[cols][rows];
        // 用于坡度计算：保留上一行
        double[] prevYRow = new double[cols];

        for (int cz = 0; cz < rows; cz++) {
            int wz = originZ + cz * step;
            for (int cx = 0; cx < cols; cx++) {
                int wx = originX + cx * step;
                Cell cell = gen.sample(wx, wz);
                double y = cell.height;
                double e = cell.e;
                double eLand = cell.eLand;
                TerrainClass t = cell.terrainType;

                typeGrid[cx][cz] = t;
                yGrid[cx][cz] = y;

                // 海陆
                if (e < 0) {
                    totalOcean++;
                } else {
                    totalLand++;
                    typeCount[t.ordinal()]++;
                    typeSumE[t.ordinal()] += e;
                    typeSumELand[t.ordinal()] += eLand;
                    typeSumY[t.ordinal()] += y;
                    typeSumE2[t.ordinal()] += e * e;
                    typeSumELand2[t.ordinal()] += eLand * eLand;
                }

                // 大陆性
                contSum += cell.continent;
                contSum2 += cell.continent * cell.continent;

                // 高度带
                int yi = (int) Math.round(y);
                if (yi >= 0 && yi < heightBands.length) heightBands[yi]++;

                // 山地坡度
                if (t == TerrainClass.MOUNTAINS || t == TerrainClass.PEAK) {
                    // 与上一列对比（水平坡度）
                    if (cx > 0 && cz > 0) {
                        double dy = Math.abs(yGrid[cx][cz] - yGrid[cx-1][cz]);
                        double dy2 = Math.abs(yGrid[cx][cz] - yGrid[cx][cz-1]);
                        mountSlopeSum += Math.max(dy, dy2) / step;
                        mountSlopeCount++;
                    }
                }
            }
        }

        // 聚类分析：扫描连通区域
        boolean[][] visited = new boolean[cols][rows];
        for (int cz = 0; cz < rows; cz++) {
            for (int cx = 0; cx < cols; cx++) {
                if (!visited[cx][cz]) {
                    TerrainClass tc = typeGrid[cx][cz];
                    // 跳过海洋和海滩（不统计聚类大小）
                    if (tc == TerrainClass.OCEAN || tc == TerrainClass.DEEP_OCEAN
                            || tc == TerrainClass.BEACH) continue;
                    // BFS 找连通区域
                    int area = floodFillSize(typeGrid, visited, cx, cz, tc, cols, rows);
                    if (area > 0) {
                        clusterCount++;
                        clusterSumW += area;
                        if (area < clusterMinW) clusterMinW = area;
                        if (area > clusterMaxW) clusterMaxW = area;
                    }
                }
            }
        }

        long t1 = System.currentTimeMillis();

        // ===== 输出报告 =====
        System.out.println("Sampling time: " + (t1 - t0) + "ms");
        System.out.println();

        // 海陆比
        int total = totalOcean + totalLand;
        double oceanPct = 100.0 * totalOcean / total;
        double landPct = 100.0 * totalLand / total;
        System.out.println("=== 海陆比 ===");
        System.out.printf("  海洋: %d (%.1f%%)\n", totalOcean, oceanPct);
        System.out.printf("  陆地: %d (%.1f%%)\n", totalLand, landPct);
        System.out.printf("  参考地球: 海洋 ~71%%, 陆地 ~29%%\n");
        System.out.println();

        // 大陆性分布
        double contMean = contSum / total;
        double contVar = contSum2 / total - contMean * contMean;
        System.out.println("=== 大陆性 c 分布 ===");
        System.out.printf("  均值: %.3f, 标准差: %.3f\n", contMean, Math.sqrt(contVar));
        System.out.println();

        // 类型频率 + 高度
        System.out.println("=== 地形类型统计 ===");
        System.out.printf("%-14s %-8s %-10s %-10s %-10s %-10s %-10s\n",
                "类型", "占比(%)", "平均Y", "平均e", "平均eLand", "eLand波动", "样本数");
        System.out.println("-".repeat(80));

        for (TerrainClass tc : TerrainClass.values()) {
            int ci = tc.ordinal();
            int n = typeCount[ci];
            if (n == 0) continue;
            double pct = 100.0 * n / totalLand;
            double meanY = typeSumY[ci] / n;
            double meanE = typeSumE[ci] / n;
            double meanELand = typeSumELand[ci] / n;
            double varELand = typeSumELand2[ci] / n - meanELand * meanELand;
            double stdELand = Math.sqrt(Math.max(0, varELand));
            System.out.printf("%-14s %-8.1f %-10.1f %-10.3f %-10.3f %-10.3f %-10d\n",
                    tc.name(), pct, meanY, meanE, meanELand, stdELand, n);
        }
        System.out.println();

        // 高度带分布
        System.out.println("=== 高度带分布（Y 柱状图）===");
        System.out.println("(陆地采样点在不同 Y 区间的百分比)");
        // 分组显示
        int[] bandGroups = {0, 63, 80, 100, 130, 160, 200, 250, 300, 321};
        int prevCut = 0;
        for (int cut : bandGroups) {
            if (cut == 0) continue;
            int bandSum = 0;
            for (int y = prevCut; y < cut && y < heightBands.length; y++) {
                bandSum += heightBands[y];
            }
            double pct = 100.0 * bandSum / totalLand;
            int barLen = (int)(pct * 0.5);
            System.out.printf("%3d~%-3dY: %5.1f%% %s\n", prevCut, cut-1, pct,
                    "|".repeat(Math.min(barLen, 50)));
            prevCut = cut;
        }
        System.out.println();

        // 山地坡度
        System.out.println("=== 山地坡度 ===");
        if (mountSlopeCount > 0) {
            double meanSlope = mountSlopeSum / mountSlopeCount;
            System.out.printf("  平均地表坡度: %.2f (块/步)\n", meanSlope);
            System.out.printf("  参考: 喜马拉雅 ~0.5-1.0 块/步, 阿尔卑斯 ~0.3-0.6\n");
        }
        System.out.println();

        // 聚类大小
        System.out.println("=== 地形类型聚类（连通区域大小）===");
        System.out.printf("  (网格采样步长=%d, 区域面积为采样点计数)\n", step);
        if (clusterCount > 0) {
            double meanCluster = clusterSumW / clusterCount;
            System.out.printf("  聚类数: %d\n", clusterCount);
            System.out.printf("  最小: %d sample, 最大: %d sample, 平均: %.1f sample\n",
                    clusterMinW, clusterMaxW, meanCluster);
            // 换算为 MC chunk
            // 每个 sample 代表 step×step 块 = 8×8 = 64 blocks
            double minBlocks = clusterMinW * step * step;
            double maxBlocks = clusterMaxW * step * step;
            double meanBlocks = meanCluster * step * step;
            double minChunks = minBlocks / 256;
            double maxChunks = maxBlocks / 256;
            double meanChunks = meanBlocks / 256;
            System.out.printf("  换算块: 最小=%.0f, 最大=%.0f, 平均=%.0f\n",
                    minBlocks, maxBlocks, meanBlocks);
            System.out.printf("  换算chunks: 最小=%.1f, 最大=%.1f, 平均=%.1f\n",
                    minChunks, maxChunks, meanChunks);
        }
        System.out.println();

        // 山脉与高原高度重叠检查
        System.out.println("=== 山脉 vs 高原 高度重叠检查 ===");
        double mountMeanY = typeCount[TerrainClass.MOUNTAINS.ordinal()] > 0
            ? typeSumY[TerrainClass.MOUNTAINS.ordinal()] / typeCount[TerrainClass.MOUNTAINS.ordinal()] : 0;
        double platMeanY = typeCount[TerrainClass.PLATEAU.ordinal()] > 0
            ? typeSumY[TerrainClass.PLATEAU.ordinal()] / typeCount[TerrainClass.PLATEAU.ordinal()] : 0;
        double peakMeanY = typeCount[TerrainClass.PEAK.ordinal()] > 0
            ? typeSumY[TerrainClass.PEAK.ordinal()] / typeCount[TerrainClass.PEAK.ordinal()] : 0;
        System.out.printf("  山地平均Y: %.1f\n", mountMeanY);
        System.out.printf("  高原平均Y: %.1f\n", platMeanY);
        System.out.printf("  山峰平均Y: %.1f\n", peakMeanY);
        System.out.printf("  山地-高原高度差: %.1f\n", mountMeanY - platMeanY);
        System.out.println();
        System.out.println("=== TerrainDiagnostic done ===");
    }

    /** BFS 扫描连通同类型区域（8方向邻域） */
    private static int floodFillSize(TerrainClass[][] grid, boolean[][] visited,
                                     int sx, int sz, TerrainClass target,
                                     int cols, int rows) {
        // 使用简单栈
        int[] stackX = new int[cols * rows];
        int[] stackZ = new int[cols * rows];
        int head = 0, tail = 0;
        stackX[tail] = sx; stackZ[tail] = sz; tail++;

        int area = 0;
        while (head < tail) {
            int cx = stackX[head];
            int cz = stackZ[head];
            head++;
            if (cx < 0 || cx >= cols || cz < 0 || cz >= rows) continue;
            if (visited[cx][cz]) continue;
            if (grid[cx][cz] != target) continue;

            visited[cx][cz] = true;
            area++;

            // 8 方向
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (nx >= 0 && nx < cols && nz >= 0 && nz < rows
                            && !visited[nx][nz]
                            && grid[nx][nz] == target) {
                        if (tail < stackX.length) {
                            stackX[tail] = nx;
                            stackZ[tail] = nz;
                            tail++;
                        }
                    }
                }
            }
        }
        return area;
    }
}
