package com.erosiontest;

import java.io.File;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * 梯田诊断：检测侵蚀输出中相邻像素的高度一致性（量化阶梯检测）
 * 
 * 在 112×112 tile 上运行完整侵蚀管线后：
 * 1. 统计相邻像素高度差为0的比例（平原占比 → 梯田度量）
 * 2. 统计高度差分布直方图
 * 3. 输出可视化：将高度差标注在原图上
 */
public class TerraceDiagnostic {
    static final int TILE = 112;
    static final int CHUNK = 16;
    
    public static void main(String[] args) throws Exception {
        long seed = 12345L;
        Noise noise = new Noise((int)seed);
        
        System.out.println("=== 梯田量化诊断 ===\n");
        
        // 生成 112×112 噪声
        System.out.println("1. 生成噪声 (terrainBaseMod)...");
        float[][] raw = new float[TILE][TILE];
        float mn = 1, mx = 0;
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                raw[z][x] = noise.terrainBaseMod(x - 48, z - 48);
                if (raw[z][x] < mn) mn = raw[z][x];
                if (raw[z][x] > mx) mx = raw[z][x];
            }
        }
        // 归一化
        float rng = mx - mn;
        if (rng > 0)
            for (int z = 0; z < TILE; z++)
                for (int x = 0; x < TILE; x++)
                    raw[z][x] = (raw[z][x] - mn) / rng;
        
        System.out.printf("   噪声范围: %.4f ~ %.4f%n", mn, mx);
        
        // 2. 运行金字塔侵蚀（精确复刻 Mod 算法）
        System.out.println("2. 运行金字塔侵蚀...");
        float[][] h = erodeModAlgo(noise, raw, seed);
        
        // 3. 梯田度量：相邻高度差分布
        System.out.println("\n3. 梯田量化分析:");
        
        int centerZ = TILE / 2;
        int centerX = TILE / 2;
        
        // 全图统计
        double[] bins = new double[10]; // 0, 0-0.001, ... 0.008+
        int totalAdj = 0;
        double maxSlope = 0;
        double sumSlope = 0;
        
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                if (x < TILE - 1) {
                    double dx = Math.abs(h[z][x+1] - h[z][x]);
                    totalAdj++;
                    maxSlope = Math.max(maxSlope, dx);
                    sumSlope += dx;
                    if (dx < 0.00001) bins[0]++;
                    else if (dx < 0.0005) bins[1]++;
                    else if (dx < 0.001) bins[2]++;
                    else if (dx < 0.002) bins[3]++;
                    else if (dx < 0.004) bins[4]++;
                    else if (dx < 0.006) bins[5]++;
                    else if (dx < 0.008) bins[6]++;
                    else if (dx < 0.01) bins[7]++;
                    else if (dx < 0.015) bins[8]++;
                    else bins[9]++;
                }
                if (z < TILE - 1) {
                    double dz = Math.abs(h[z+1][x] - h[z][x]);
                    totalAdj++;
                    maxSlope = Math.max(maxSlope, dz);
                    sumSlope += dz;
                    if (dz < 0.00001) bins[0]++;
                    else if (dz < 0.0005) bins[1]++;
                    else if (dz < 0.001) bins[2]++;
                    else if (dz < 0.002) bins[3]++;
                    else if (dz < 0.004) bins[4]++;
                    else if (dz < 0.006) bins[5]++;
                    else if (dz < 0.008) bins[6]++;
                    else if (dz < 0.01) bins[7]++;
                    else if (dz < 0.015) bins[8]++;
                    else bins[9]++;
                }
            }
        }
        
        double avgSlope = sumSlope / totalAdj;
        double flatPct = bins[0] / totalAdj * 100;
        
        System.out.printf("   总相邻对数: %d%n", totalAdj);
        System.out.printf("   平均坡度:   %.6f%n", avgSlope);
        System.out.printf("   最大坡度:   %.6f%n", maxSlope);
        System.out.printf("   完全平坦:   %.1f%% (差=0)%n", flatPct);
        
        System.out.println("\n   坡度分布直方图:");
        System.out.printf("     差=0       : %6.1f%% (%6.0f)%n", bins[0]/totalAdj*100, bins[0]);
        System.out.printf("     差<0.0005  : %6.1f%% (%6.0f)%n", bins[1]/totalAdj*100, bins[1]);
        System.out.printf("     差<0.001   : %6.1f%% (%6.0f)%n", bins[2]/totalAdj*100, bins[2]);
        System.out.printf("     差<0.002   : %6.1f%% (%6.0f)%n", bins[3]/totalAdj*100, bins[3]);
        System.out.printf("     差<0.004   : %6.1f%% (%6.0f)%n", bins[4]/totalAdj*100, bins[4]);
        System.out.printf("     差<0.006   : %6.1f%% (%6.0f)%n", bins[5]/totalAdj*100, bins[5]);
        System.out.printf("     差<0.008   : %6.1f%% (%6.0f)%n", bins[6]/totalAdj*100, bins[6]);
        System.out.printf("     差<0.01    : %6.1f%% (%6.0f)%n", bins[7]/totalAdj*100, bins[7]);
        System.out.printf("     差<0.015   : %6.1f%% (%6.0f)%n", bins[8]/totalAdj*100, bins[8]);
        System.out.printf("     差>=0.015  : %6.1f%% (%6.0f)%n", bins[9]/totalAdj*100, bins[9]);
        
        // 等效 Minecraft 块高差（1像素=1格，高度归一化在[0,1]，世界高度384格）
        double blockScale = 384.0;
        System.out.printf("\n   等效 Minecraft 块高坡度: %.2f 格/格  最大: %.2f 格%n",
            avgSlope * blockScale, maxSlope * blockScale);
        
        String verdict;
        if (flatPct > 10) {
            verdict = "✗ 严重梯田: 超过10%像素平坦";
        } else if (flatPct > 5) {
            verdict = "△ 轻度梯田";
        } else {
            verdict = "✓ 无明显梯田";
        }
        System.out.printf("   诊断: %s%n", verdict);
        
        // 4. 可视化：高度差热力图
        System.out.println("\n4. 生成可视化...");
        saveTerraceHeatmap(h, bins, totalAdj, flatPct, "terrace_diag.png");
        System.out.println("   → output/terrace_diag.png");
    }
    
    static float[][] erodeModAlgo(Noise noise, float[][] raw, long seed) {
        int[] res = {14, 28, 56, 112};
        int[] brushP = {3, 4, 4};
        float[] strP = {1.5f, 1.2f, 1.0f};
        float[] erodeP = {0.3f, 0.2f, 0.15f};
        float[] depP = {0.04f, 0.03f, 0.02f};
        int[] dropsP = {7500, 9000, 6000};
        
        float[][] h = null;
        
        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];
            
            if (li == 0) {
                int scale = TILE / curRes;
                h = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++) {
                        float s = 0; int n = 0;
                        for (int dz = 0; dz < scale; dz++)
                            for (int dx = 0; dx < scale; dx++) {
                                s += raw[z*scale+dz][x*scale+dx]; n++;
                            }
                        h[z][x] = s / n;
                    }
            } else {
                h = ErosionPipeline.bicubicUpsampleGrid(h, res[li-1], curRes);
            }
            
            if (li < res.length - 1) {
                int brushR = brushP[li];
                int pad = Math.max(brushR * 2, 4);
                float[][] p = ErosionPipeline.padGridMirror(h, curRes, pad);
                ErosionPipeline.terraforgedErosion(p, curRes+pad*2,
                    dropsP[li], strP[li], brushP[li], 0.5f, 0.001f, 2.5f,
                    erodeP[li], depP[li], seed + li * 10000L);
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z+pad], pad, h[z], 0, curRes);
            }
        }
        return h;
    }
    
    static void saveTerraceHeatmap(float[][] h, double[] bins, int total, double flatPct, String fn) throws Exception {
        int sz = TILE * 4;
        int infoH = 60;
        BufferedImage img = new BufferedImage(sz, sz + infoH, BufferedImage.TYPE_INT_RGB);
        
        // 背景
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                img.setRGB(x, y, 0x111111);
        
        // 高度图
        float minH = 1, maxH = 0;
        for (int z = 0; z < TILE; z++)
            for (int x = 0; x < TILE; x++) {
                if (h[z][x] < minH) minH = h[z][x];
                if (h[z][x] > maxH) maxH = h[z][x];
            }
        float range = Math.max(maxH - minH, 0.001f);
        
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                // 基础颜色 → 高度
                int c = ErosionPipeline.toColor(h[z][x], minH, range);
                
                // 叠加高差 → 红色标注
                if (x < TILE - 1) {
                    double dx = Math.abs(h[z][x+1] - h[z][x]);
                    if (dx < 0.00001) c = 0xFF0000; // 完全平坦 → 红色
                    else if (dx < 0.0005) c = 0xFF6600; // 极平坦 → 橙色
                }
                if (z < TILE - 1) {
                    double dz = Math.abs(h[z+1][x] - h[z][x]);
                    if (dz < 0.00001) c = 0xFF0000;
                    else if (dz < 0.0005) c = 0xFF6600;
                }
                
                for (int dy = 0; dy < 4; dy++)
                    for (int dx = 0; dx < 4; dx++)
                        img.setRGB(x*4+dx, z*4+dy, c);
            }
        }
        
        // 色标
        int[] colors = {0x111111, 0xFF0000, 0xFF6600, 0xFFFF00, 0x00FF00};
        String[] labels = {"梯田(差=0)", "极平(<0.0005)", "平(<0.002)", "中等", "陡峭"};
        for (int i = 0; i < colors.length; i++) {
            int bx = 5 + i * 60;
            for (int dy = 0; dy < 8; dy++)
                for (int dx = 0; dx < 20; dx++)
                    img.setRGB(bx+dx, sz+22+dy, colors[i]);
            drawLabel(img, labels[i], bx, sz+30, 0xCCCCCC);
        }
        
        // 统计信息
        drawLabel(img, String.format("Terrace: %.1f%% flat  AvgSlope: %.6f  MaxSlope: %.6f",
            flatPct, bins[1]/total, bins[9]/total), 5, sz+8, 0xFFAA33);
        drawLabel(img, "Red=dead-flat  Orange=near-flat  Green=normal  Yellow=mild",
            5, sz+44, 0x888888);
        
        new File("output").mkdirs();
        ImageIO.write(img, "png", new File("output/" + fn));
    }
    
    static void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        for (int i = 0; i < text.length(); i++)
            for (int dy = 0; dy < 9; dy++)
                for (int dx = 0; dx < 7; dx++) {
                    int px = x + i * 8 + dx, py = y + dy;
                    if (px < img.getWidth() && py < img.getHeight())
                        img.setRGB(px, py, color);
                }
    }
}
