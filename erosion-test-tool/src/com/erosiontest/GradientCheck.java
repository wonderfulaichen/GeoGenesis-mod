package com.erosiontest;

/**
 * 检查 (0,0) 附近的地形梯度
 */
public class GradientCheck {
    public static void main(String[] args) {
        int seed = 99999;
        int mapSize = 512;
        int viewSize = 6000;
        
        StandalonePreview terrain = new StandalonePreview(seed);
        float scale = (float)viewSize / mapSize;
        
        // 检查 (0,0) 附近的 10x10 区域
        System.out.println("Height around (0,0):");
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                System.out.print(String.format("%.2f ", h));
            }
            System.out.println();
        }
        
        // 检查全图最低点和最高点
        float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
        int minX = 0, minY = 0, maxX = 0, maxY = 0;
        
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                if (h < minH) { minH = h; minX = x; minY = y; }
                if (h > maxH) { maxH = h; maxX = x; maxY = y; }
            }
        }
        
        System.out.println("\nGlobal min: (" + minX + "," + minY + ") = " + String.format("%.3f", minH));
        System.out.println("Global max: (" + maxX + "," + maxY + ") = " + String.format("%.3f", maxH));
        
        // 检查 (0,0) 的邻居
        System.out.println("\nNeighbors of (0,0):");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = dx, y = dy;
                if (x < 0 || x >= mapSize || y < 0 || y >= mapSize) continue;
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                System.out.println("  (" + x + "," + y + "): " + String.format("%.3f", h));
            }
        }
    }
}
