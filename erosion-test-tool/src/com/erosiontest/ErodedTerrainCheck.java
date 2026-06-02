package com.erosiontest;

/**
 * 检查侵蚀后的地形
 */
public class ErodedTerrainCheck {
    public static void main(String[] args) throws Exception {
        int seed = 99999;
        int mapSize = 512;
        
        SimpleHydrologyV4 sim = new SimpleHydrologyV4(seed, mapSize);
        sim.init();
        
        // 记录初始地形
        float[][] initialHeight = new float[mapSize][mapSize];
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                initialHeight[y][x] = sim.height[y][x];
            }
        }
        
        sim.simulate();
        
        // 比较侵蚀前后的地形
        System.out.println("Terrain change around (0,0):");
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                float diff = sim.height[y][x] - initialHeight[y][x];
                System.out.print(String.format("%+.3f ", diff));
            }
            System.out.println();
        }
        
        // 找出被侵蚀最多的位置
        float maxErosion = 0;
        int maxX = 0, maxY = 0;
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float diff = initialHeight[y][x] - sim.height[y][x];
                if (diff > maxErosion) {
                    maxErosion = diff;
                    maxX = x;
                    maxY = y;
                }
            }
        }
        System.out.println("\nMax erosion at (" + maxX + "," + maxY + "): " + String.format("%.3f", maxErosion));
        
        // 检查 (0,0) 的 discharge 来源
        System.out.println("\nDischarge path to (0,0):");
        System.out.println("(0,0) discharge: " + (int)sim.discharge[0][0]);
        
        // 检查 (0,0) 邻居的 discharge
        System.out.println("\nNeighbors discharge:");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = dx, y = dy;
                if (x < 0 || x >= mapSize || y < 0 || y >= mapSize) continue;
                System.out.println("  (" + x + "," + y + "): " + (int)sim.discharge[y][x]);
            }
        }
    }
}
