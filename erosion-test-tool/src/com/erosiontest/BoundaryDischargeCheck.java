package com.erosiontest;

/**
 * 检查边界 discharge 分布
 */
public class BoundaryDischargeCheck {
    public static void main(String[] args) throws Exception {
        int seed = 99999;
        int mapSize = 512;
        
        SimpleHydrologyV4 sim = new SimpleHydrologyV4(seed, mapSize);
        sim.init();
        sim.simulate();
        
        // 检查边界 discharge
        System.out.println("Boundary discharge (top 20):");
        
        // 收集所有边界像素
        java.util.List<int[]> boundaryPixels = new java.util.ArrayList<>();
        
        // 上边界 y=0
        for (int x = 0; x < mapSize; x++) {
            boundaryPixels.add(new int[]{x, 0, (int)sim.discharge[0][x]});
        }
        // 下边界 y=mapSize-1
        for (int x = 0; x < mapSize; x++) {
            boundaryPixels.add(new int[]{x, mapSize-1, (int)sim.discharge[mapSize-1][x]});
        }
        // 左边界 x=0
        for (int y = 1; y < mapSize-1; y++) {
            boundaryPixels.add(new int[]{0, y, (int)sim.discharge[y][0]});
        }
        // 右边界 x=mapSize-1
        for (int y = 1; y < mapSize-1; y++) {
            boundaryPixels.add(new int[]{mapSize-1, y, (int)sim.discharge[y][mapSize-1]});
        }
        
        boundaryPixels.sort((a, b) -> Integer.compare(b[2], a[2]));
        
        for (int i = 0; i < Math.min(20, boundaryPixels.size()); i++) {
            int[] p = boundaryPixels.get(i);
            System.out.println("  (" + p[0] + "," + p[1] + "): discharge=" + p[2]);
        }
        
        // 检查 (0,0) 附近的 discharge
        System.out.println("\nDischarge around (0,0):");
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                System.out.print(String.format("%6.0f ", sim.discharge[y][x]));
            }
            System.out.println();
        }
        
        // 统计边界 vs 内部的 discharge
        float boundaryD = 0, interiorD = 0;
        int boundaryCount = 0, interiorCount = 0;
        
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                boolean isBoundary = x == 0 || x == mapSize-1 || y == 0 || y == mapSize-1;
                if (isBoundary) {
                    boundaryD += sim.discharge[y][x];
                    boundaryCount++;
                } else {
                    interiorD += sim.discharge[y][x];
                    interiorCount++;
                }
            }
        }
        
        System.out.println("\nBoundary avg discharge: " + String.format("%.2f", boundaryD/boundaryCount));
        System.out.println("Interior avg discharge: " + String.format("%.2f", interiorD/interiorCount));
    }
}
