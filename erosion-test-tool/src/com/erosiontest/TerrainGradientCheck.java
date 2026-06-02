package com.erosiontest;

/**
 * 检查地形梯度，看是否有系统性偏向
 */
public class TerrainGradientCheck {
    public static void main(String[] args) {
        int seed = 99999;
        int mapSize = 512;
        int viewSize = 6000;
        
        StandalonePreview terrain = new StandalonePreview(seed);
        float scale = (float)viewSize / mapSize;
        
        // 检查四个角落和中心的高度
        System.out.println("Height at key positions:");
        int[][] positions = {
            {0, 0},           // 左上角
            {mapSize-1, 0},   // 右上角
            {0, mapSize-1},   // 左下角
            {mapSize-1, mapSize-1}, // 右下角
            {mapSize/2, mapSize/2}, // 中心
            {mapSize/4, mapSize/4}, // 左上偏中心
            {mapSize*3/4, mapSize/4}, // 右上偏中心
        };
        
        for (int[] pos : positions) {
            float wx = (pos[0] - mapSize/2f) * scale;
            float wz = (pos[1] - mapSize/2f) * scale;
            float h = terrain.computeHeight(wx, wz);
            System.out.println("  (" + pos[0] + "," + pos[1] + "): height=" + String.format("%.3f", h));
        }
        
        // 检查平均高度分布（按象限）
        System.out.println("\nAverage height by quadrant:");
        float[][] quadrantHeights = new float[2][2];
        int[][] quadrantCounts = new int[2][2];
        
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                int qx = x < mapSize/2 ? 0 : 1;
                int qy = y < mapSize/2 ? 0 : 1;
                quadrantHeights[qy][qx] += h;
                quadrantCounts[qy][qx]++;
            }
        }
        
        String[] qNames = {"左上", "右上", "左下", "右下"};
        for (int qy = 0; qy < 2; qy++) {
            for (int qx = 0; qx < 2; qx++) {
                float avg = quadrantHeights[qy][qx] / quadrantCounts[qy][qx];
                System.out.println("  " + qNames[qy*2+qx] + ": " + String.format("%.3f", avg));
            }
        }
        
        // 检查边缘 vs 中心
        System.out.println("\nEdge vs Center:");
        float edgeH = 0, centerH = 0;
        int edgeCount = 0, centerCount = 0;
        int margin = 50;
        
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                
                boolean isEdge = x < margin || x >= mapSize-margin || y < margin || y >= mapSize-margin;
                if (isEdge) {
                    edgeH += h;
                    edgeCount++;
                } else {
                    centerH += h;
                    centerCount++;
                }
            }
        }
        
        System.out.println("  Edge avg: " + String.format("%.3f", edgeH/edgeCount));
        System.out.println("  Center avg: " + String.format("%.3f", centerH/centerCount));
    }
}
