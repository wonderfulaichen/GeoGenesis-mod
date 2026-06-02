package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成地形高度热力图，验证是否有径向偏向
 */
public class TerrainHeightMap {
    public static void main(String[] args) throws Exception {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 12345;
        int mapSize = 512;
        int viewSize = 6000;
        
        StandalonePreview terrain = new StandalonePreview(seed);
        float scale = (float)viewSize / mapSize;
        
        BufferedImage img = new BufferedImage(mapSize, mapSize, BufferedImage.TYPE_INT_RGB);
        
        float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
        float[][] heights = new float[mapSize][mapSize];
        
        // 采样高度
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                float h = terrain.computeHeight(wx, wz);
                heights[y][x] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
            }
        }
        
        System.out.println("Height range: " + minH + " to " + maxH);
        System.out.println("Sea level (0.35): " + ((0.35f - minH) / (maxH - minH)));
        
        // 渲染热力图
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float h = heights[y][x];
                int color = heatmapColor(h, minH, maxH);
                img.setRGB(x, y, color);
            }
        }
        
        ImageIO.write(img, "png", new File("../output/terrain_heightmap.png"));
        System.out.println("Saved: ../output/terrain_heightmap.png");
        
        // 分析径向分布
        System.out.println("\nRadial analysis (center to edge):");
        int center = mapSize / 2;
        for (int r = 0; r < center; r += 20) {
            float avgH = 0;
            int count = 0;
            // 采样圆周上的点
            for (int angle = 0; angle < 360; angle += 10) {
                float rad = (float)Math.toRadians(angle);
                int x = (int)(center + r * Math.cos(rad));
                int y = (int)(center + r * Math.sin(rad));
                if (x >= 0 && x < mapSize && y >= 0 && y < mapSize) {
                    avgH += heights[y][x];
                    count++;
                }
            }
            if (count > 0) {
                avgH /= count;
                System.out.println("  Radius " + r + ": avg height = " + String.format("%.3f", avgH));
            }
        }
    }
    
    static int heatmapColor(float h, float min, float max) {
        float t = (h - min) / (max - min);
        t = Math.max(0, Math.min(1, t));
        
        // 海洋 = 深蓝色，陆地 = 绿色到棕色到白色
        if (t < 0.35f) {
            // 海洋：深蓝到浅蓝
            float ocean = t / 0.35f;
            return rgb(0, (int)(50 + ocean * 100), (int)(100 + ocean * 155));
        } else {
            // 陆地：绿 -> 棕 -> 白
            float land = (t - 0.35f) / 0.65f;
            if (land < 0.3f) {
                return rgb((int)(34 + land/0.3f * 50), (int)(139 + land/0.3f * 30), (int)(34 + land/0.3f * 20));
            } else if (land < 0.7f) {
                float tt = (land - 0.3f) / 0.4f;
                return rgb((int)(84 + tt * 80), (int)(169 - tt * 60), (int)(54 + tt * 20));
            } else {
                float tt = (land - 0.7f) / 0.3f;
                int v = (int)(164 + tt * 91);
                return rgb(v, v, v);
            }
        }
    }
    
    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
}
