package com.geogenesis.worldgen.terrain;

/**
 * 海岸线平滑度诊断：验证海岸线是否呈现"完美圆弧"问题。
 * 采样多条直线上的海岸线坐标，计算曲率变化和分形细节。
 */
public final class CoastlineSmoothnessProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        System.out.println("=== CoastlineSmoothnessProbe seed=" + seed + " ===");
        System.out.printf("continentScale=%.1f, coastlineWarpAmp=%.3f%n",
            p.continentScale(), p.coastlineWarpAmp());
        System.out.printf("oceanFadeStart=%.3f, landRampEnd=%.3f, coastLoc=%.3f%n",
            p.oceanFadeStart(), p.landRampEnd(), p.coastLoc());

        // 采样多条直线（不同方向）上的海岸线坐标
        final int NUM_LINES = 8;
        final int LINE_LENGTH = 2000; // 块
        final int STEP = 4; // 采样步长

        double[] angles = new double[NUM_LINES];
        for (int i = 0; i < NUM_LINES; i++) {
            angles[i] = i * Math.PI / NUM_LINES; // 0 到 π
        }

        System.out.printf("%n=== 海岸线采样（%d 条直线，步长 %d 块）===%n", NUM_LINES, STEP);

        for (int lineIdx = 0; lineIdx < NUM_LINES; lineIdx++) {
            double angle = angles[lineIdx];
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);

            System.out.printf("%n直线 %d (角度 %.1f°):%n", lineIdx + 1, Math.toDegrees(angle));

            // 沿直线采样，记录海岸线位置（cEdge ≈ 0 的位置）
            double prevCEdge = Double.NaN;
            double prevX = Double.NaN, prevZ = Double.NaN;
            int coastCount = 0;
            double totalCurvature = 0;
            int curvatureSamples = 0;

            for (int dist = -LINE_LENGTH / 2; dist <= LINE_LENGTH / 2; dist += STEP) {
                double x = dist * dx;
                double z = dist * dz;

                Cell cell = gen.sample(x, z);
                double cEdge = cell.coastCoord;

                // 检测海岸线穿越（cEdge 从负到正或从正到负）
                if (!Double.isNaN(prevCEdge) && prevCEdge < 0 && cEdge >= 0) {
                    coastCount++;
                    System.out.printf("  海岸线 %d: x=%.0f, z=%.0f, cEdge=%.4f, e=%.4f, h=%.1f, type=%s%n",
                        coastCount, x, z, cEdge, cell.e, cell.height, cell.terrainType);
                }

                // 计算曲率（二阶导数近似）
                if (!Double.isNaN(prevCEdge) && dist >= -LINE_LENGTH / 2 + STEP * 2) {
                    // 需要前两个点
                    double prevPrevX = (dist - STEP * 2) * dx;
                    double prevPrevZ = (dist - STEP * 2) * dz;
                    Cell prevPrevCell = gen.sample(prevPrevX, prevPrevZ);
                    double prevPrevCEdge = prevPrevCell.coastCoord;

                    if (prevPrevCEdge < 0 && cEdge >= 0) {
                        // 计算曲率（cEdge 的二阶导数）
                        double d1 = cEdge - prevCEdge;
                        double d2 = prevCEdge - prevPrevCEdge;
                        double curvature = Math.abs(d1 - d2);
                        totalCurvature += curvature;
                        curvatureSamples++;
                    }
                }

                prevCEdge = cEdge;
            }

            System.out.printf("  总计海岸线穿越: %d 次%n", coastCount);
            if (curvatureSamples > 0) {
                double avgCurvature = totalCurvature / curvatureSamples;
                System.out.printf("  平均曲率: %.6f (越高越弯曲)%n", avgCurvature);
            }
        }

        // 测试海岸线分形化是否生效
        System.out.printf("%n=== 海岸线分形化测试 ===%n");
        System.out.printf("coastlineWarpAmp = %.3f%n", p.coastlineWarpAmp());
        if (p.coastlineWarpAmp() == 0.0) {
            System.out.println("[WARN] coastlineWarpAmp=0, coastline fractal disabled. Coastline = c=0 iso-line only.");
            System.out.println("    This is the main cause of the perfect-arc coastline.");
            System.out.println("    Fix: set coastlineWarpAmp to 0.03~0.1 to enable coastline fractal warping.");
        } else {
            System.out.println("[OK] coastlineWarpAmp > 0, coastline fractal enabled.");
        }

        // 测试大陆性噪声的频率特性
        System.out.printf("%n=== 大陆性噪声频率特性 ===%n");
        System.out.printf("continentScale = %.1f 块 (基频 1/%.0f)%n",
            p.continentScale(), p.continentScale());
        System.out.printf("continentFbmOctaves = %d, lacunarity = %.1f, persistence = %.1f%n",
            p.continentFbmOctaves(), p.continentFbmLacunarity(), p.continentFbmPersistence());

        // 计算有效频率范围
        double baseFreq = 1.0 / p.continentScale();
        double highFreq = baseFreq * Math.pow(p.continentFbmLacunarity(), p.continentFbmOctaves() - 1);
        System.out.printf("频率范围: %.6f ~ %.6f (波长 %.0f ~ %.0f 块)%n",
            baseFreq, highFreq, 1.0 / highFreq, 1.0 / baseFreq);

        if (highFreq < 0.01) { // wavelength > 100 blocks
            System.out.println("[WARN] highest frequency wavelength > 100 blocks, coastline may be too smooth.");
            System.out.println("    Fix: increase continentFbmOctaves or reduce continentScale.");
        }

        System.out.println("\n=== 诊断完成 ===");
        System.out.println("如果海岸线呈现完美圆弧，主要原因是:");
        System.out.println("1. coastlineWarpAmp = 0（海岸线分形化禁用）");
        System.out.println("2. 大陆性噪声频率过低（continentScale 过大）");
        System.out.println("3. 过渡带 smoothstep 导致的平滑边界");
    }
}