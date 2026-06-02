package com.erosiontest;

import java.util.*;

/**
 * 分析 discharge 在陆地和海洋上的分布
 */
public class DischargeAnalyzer {
    public static void main(String[] args) throws Exception {
        int seed = 99999;
        int mapSize = 512;
        int viewSize = 6000;
        
        StandalonePreview terrain = new StandalonePreview(seed);
        SimpleHydrologyV4 sim = new SimpleHydrologyV4(seed, mapSize);
        sim.init();
        sim.simulate();
        
        float scale = (float)viewSize / mapSize;
        float seaLevel = 0.35f;
        
        // 统计陆地和海洋上的 discharge
        List<Float> landDischarge = new ArrayList<>();
        List<Float> oceanDischarge = new ArrayList<>();
        
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                float d = sim.discharge[y][x];
                
                if (h >= seaLevel) {
                    landDischarge.add(d);
                } else {
                    oceanDischarge.add(d);
                }
            }
        }
        
        Collections.sort(landDischarge);
        Collections.sort(oceanDischarge);
        
        System.out.println("=== Discharge Analysis ===");
        System.out.println("Land pixels: " + landDischarge.size());
        System.out.println("Ocean pixels: " + oceanDischarge.size());
        
        if (landDischarge.size() > 0) {
            int n = landDischarge.size();
            System.out.println("\nLand discharge:");
            System.out.println("  min=" + fmt(landDischarge.get(0)));
            System.out.println("  max=" + fmt(landDischarge.get(n-1)));
            System.out.println("  p50=" + fmt(landDischarge.get(n/2)));
            System.out.println("  p90=" + fmt(landDischarge.get((int)(n*0.9))));
            System.out.println("  p95=" + fmt(landDischarge.get((int)(n*0.95))));
            System.out.println("  p99=" + fmt(landDischarge.get((int)(n*0.99))));
        }
        
        if (oceanDischarge.size() > 0) {
            int n = oceanDischarge.size();
            System.out.println("\nOcean discharge:");
            System.out.println("  min=" + fmt(oceanDischarge.get(0)));
            System.out.println("  max=" + fmt(oceanDischarge.get(n-1)));
            System.out.println("  p50=" + fmt(oceanDischarge.get(n/2)));
            System.out.println("  p90=" + fmt(oceanDischarge.get((int)(n*0.9))));
            System.out.println("  p95=" + fmt(oceanDischarge.get((int)(n*0.95))));
            System.out.println("  p99=" + fmt(oceanDischarge.get((int)(n*0.99))));
        }
        
        // 找出陆地上 discharge 最高的位置
        System.out.println("\nTop 10 land discharge locations:");
        List<int[]> landPixels = new ArrayList<>();
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                if (h >= seaLevel) {
                    landPixels.add(new int[]{x, y, (int)sim.discharge[y][x]});
                }
            }
        }
        landPixels.sort((a, b) -> Integer.compare(b[2], a[2]));
        for (int i = 0; i < Math.min(10, landPixels.size()); i++) {
            int[] p = landPixels.get(i);
            System.out.println("  (" + p[0] + "," + p[1] + "): discharge=" + p[2]);
        }
    }
    
    static String fmt(float v) {
        return String.format("%.2f", v);
    }
}
