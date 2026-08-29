package com.geogenesis.worldgen.terrain;

/**
 * 山地剖面诊断工具：沿一条直线采样高度，输出高度变化率（坡度）。
 * 用于诊断山地形态问题：是否是"陡升然后缓顶"。
 */
public final class MountainProfileProbe {

    public static void main(String[] args) {
        TerrainParams p = TerrainParams.defaults();
        long seed = 12345L;

        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        // 1. 先扫描小区域找 MOUNTAINS 主导的 cell
        int scanW = 256, scanH = 256;
        double bestMountW = 0;
        int bestX = 0, bestZ = 0;
        for (int x = 0; x < scanW; x += 4) {
            for (int z = 0; z < scanH; z += 4) {
                Cell cell = gen.sample(x, z);
                double mw = cell.typeWeights != null ? cell.typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0;
                if (mw > bestMountW) {
                    bestMountW = mw;
                    bestX = x;
                    bestZ = z;
                }
            }
        }

        // 2. 从山脉外到山脉中心走一条剖面
        int startX = bestX - 100;
        int startZ = bestZ;
        int endX = bestX + 100;
        int endZ = bestZ;
        int steps = 100;

        System.out.println("=== MountainProfileProbe ===");
        System.out.println("Seed: " + seed);
        System.out.println("Mountain center found at: (" + bestX + ", " + bestZ + "), max mountW=" + String.format("%.3f", bestMountW));
        System.out.println("Transect: (" + startX + "," + startZ + ") → (" + endX + "," + endZ + ")");
        System.out.println();
        System.out.printf("%-6s %-6s %-8s %-8s %-6s %-10s %s%n",
                "step", "x", "z", "Y", "eLand", "type", "slope(Y/block)");
        System.out.println("-".repeat(65));

        double prevY = Double.NaN;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int wx = (int) Math.round(startX + t * (endX - startX));
            int wz = (int) Math.round(startZ + t * (endZ - startZ));

            Cell cell = gen.sample(wx, wz);
            double y = cell.height;
            TerrainClass type = cell.terrainType;
            String typeName = type.name();

            double slope = Double.NaN;
            if (!Double.isNaN(prevY)) {
                slope = y - prevY; // 每步 = 2 blocks
            }
            prevY = y;

            System.out.printf("%-6d %-6d %-6d %-8.1f %-8.4f %-10s %s%n",
                    i, wx, wz, y, cell.eLand, typeName,
                    Double.isNaN(slope) ? "-" : String.format("%+.2f", slope));
        }

        // 3. 分析上升阶段
        System.out.println();
        System.out.println("=== 坡段分析 ===");
        System.out.println("起点→80Y：从第1步到高度首次超过80");
        System.out.println("80→130Y：到超过130");
        System.out.println("130→200Y：到超过200");
        System.out.println("200→山顶段：到超过230");

        // 沿着上面同一条线重新测量分段
        prevY = Double.NaN;
        double lastLowY = Double.NaN;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int wx = (int) Math.round(startX + t * (endX - startX));
            int wz = (int) Math.round(startZ + t * (endZ - startZ));
            Cell cell = gen.sample(wx, wz);
            double y = cell.height;
            double eLand = cell.eLand;
            double mountW = cell.typeWeights != null ? cell.typeWeights[TerrainClass.MOUNTAINS.ordinal()] : 0;

            if (!Double.isNaN(prevY) && !Double.isNaN(lastLowY)) {
                double deltaY = y - lastLowY;
                double deltaSteps = 1;
                if (deltaY > 0.5 && lastLowY > 60) {
                    System.out.printf("  at step %3d (wx=%d,eLand=%.3f,mountW=%.2f): Y=%.0f, gain=%.1f/seg%n",
                            i, wx, eLand, mountW, y, deltaY);
                }
            }
            if (!Double.isNaN(prevY)) lastLowY = prevY;
            prevY = y;
        }
    }
}
