package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 逐像素诊断：对比两个相邻 tile 的所有级别输出
 */
public class PixelDiagnostic {
    
    static final int TILE = 112;
    static final int CHUNK = 16;
    static final int HALF = 3;
    static final int OUT_R = 1;
    static final int OUT_DIM = 3;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 逐像素诊断 ===\n");
        
        long seed = 12345L;
        Noise noise = new Noise((int)seed);
        
        // 完全复刻 Mod 参数
        int[] res = {14, 28, 56, 112};
        int[] brushP = {3, 4, 4};
        float[] strP = {1.5f, 1.2f, 1.0f};
        float[] erodeP = {0.3f, 0.2f, 0.15f};
        float[] depP = {0.04f, 0.03f, 0.02f};
        int[] dropsP = {7500, 9000, 6000};
        
        // Tile A: 中心(0,0), world(-48,-48)
        System.out.println("Tile A (0,0) world(-48,-48)");
        float[][][] cacheA = new float[3][][];
        float[][] hA = processTile(noise, -48, -48, seed, null, res, brushP, strP, erodeP, depP, dropsP, cacheA);
        
        // Tile B: 中心(3,0), world(0,-48), 左锁A
        System.out.println("\nTile B (3,0) world(0,-48) 左锁A");
        float[][][] cacheB = new float[3][][];
        float[][] hB = processTile(noise, 0, -48, seed + 999L, cacheA, res, brushP, strP, erodeP, depP, dropsP, cacheB);
        
        // 诊断每一级
        for (int li = 0; li < 3; li++) {
            int curRes = res[li];
            int outStart = (HALF - OUT_R) * CHUNK * curRes / TILE;
            int outWidth = OUT_DIM * CHUNK * curRes / TILE;
            int delta = 3 * CHUNK * curRes / TILE;
            int lockW = brushP[li];
            
            System.out.printf("\n=== Level %d (%dx%d) ===%n", li, curRes, curRes);
            System.out.printf("outStart=%d outWidth=%d delta=%d lockW=%d%n", outStart, outWidth, delta, lockW);
            
            if (cacheA[li] == null || cacheB[li] == null) {
                System.out.println("  缓存为空，跳过");
                continue;
            }
            
            // A的右边缘 vs B的左边缘
            int aRight = outStart + outWidth - 1;
            int bLeft = outStart;
            
            System.out.printf("A右边缘(列%d) vs B左边缘(列%d):%n", aRight, bLeft);
            
            double maxDiff = 0;
            int badCount = 0;
            for (int row = outStart; row < outStart + outWidth && row < curRes; row++) {
                float va = cacheA[li][row][aRight];
                float vb = cacheB[li][row][bLeft];
                double diff = Math.abs(va - vb);
                maxDiff = Math.max(maxDiff, diff);
                if (diff > 0.0001) {
                    badCount++;
                    if (badCount <= 5) {
                        System.out.printf("  row=%d A=%.6f B=%.6f diff=%.6f%n", row, va, vb, diff);
                    }
                }
            }
            System.out.printf("  差异行: %d/%d, 最大: %.6f%n", badCount, outWidth, maxDiff);
            
            // 检查B的左锁是否正确应用
            System.out.printf("B左锁区(列%d~%d):%n", bLeft, bLeft + lockW - 1);
            for (int col = 0; col < lockW; col++) {
                int bCol = bLeft + col;
                int aCol = outStart + delta + col;
                if (bCol >= curRes || aCol >= curRes) break;
                
                double maxColDiff = 0;
                for (int row = outStart; row < outStart + outWidth && row < curRes; row++) {
                    float va = cacheA[li][row][aCol];
                    float vb = cacheB[li][row][bCol];
                    maxColDiff = Math.max(maxColDiff, Math.abs(va - vb));
                }
                System.out.printf("  列B[%d]=A[%d] maxDiff=%.6f %s%n", 
                    bCol, aCol, maxColDiff, maxColDiff < 0.0001 ? "✓" : "✗");
            }
            
            // ★ 关键测试：锁定区旁的第1个非锁定列
            // B非锁列 = bLeft + lockW, A对应列 = bLeft + lockW + delta
            int aChkCol = bLeft + lockW + delta;
            int bChkCol = bLeft + lockW;
            if (aChkCol < curRes && bChkCol < curRes) {
                double max = 0;
                for (int row = outStart; row < outStart + outWidth && row < curRes; row++) {
                    max = Math.max(max, Math.abs(cacheA[li][row][aChkCol] - cacheB[li][row][bChkCol]));
                }
                System.out.printf("  ★ 非锁列 A[%d] vs B[%d] maxDiff=%.6f %s%n",
                    aChkCol, bChkCol, max, max < 0.0001 ? "✓一致" : "✗不一致!");
            }
        }
        
        // 最终112级边界
        System.out.println("\n=== 最终 112x112 边界 ===");
        int outStart = (HALF - OUT_R) * CHUNK;
        int outWidth = OUT_DIM * CHUNK;
        int aRight = outStart + outWidth - 1;
        int bLeft = outStart;
        
        double maxDiff = 0;
        int bad = 0;
        for (int row = outStart; row < outStart + outWidth; row++) {
            double diff = Math.abs(hA[row][aRight] - hB[row][bLeft]);
            maxDiff = Math.max(maxDiff, diff);
            if (diff > 0.001) bad++;
        }
        System.out.printf("差异行: %d/%d, 最大: %.6f%n", bad, outWidth, maxDiff);
        System.out.println(maxDiff < 0.001 ? "✓ 112级无缝!" : "✗ 112级有断裂!");
        
        // 可视化
        saveDetailed(hA, hB, outStart, outWidth, "diag_detail.png");
        System.out.println("\n详细可视化: output/diag_detail.png");
    }
    
    static float[][] processTile(Noise n, int wx, int wz, long seed,
                                  float[][][] leftCache,
                                  int[] res, int[] brushP, float[] strP,
                                  float[] erodeP, float[] depP, int[] dropsP,
                                  float[][][] outCache) {
        // 生成噪声
        float[][] raw = new float[TILE][TILE];
        float min = 1, max = 0;
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                raw[z][x] = n.terrainBaseMod(wx + x, wz + z);
                if (raw[z][x] < min) min = raw[z][x];
                if (raw[z][x] > max) max = raw[z][x];
            }
        }
        float range = max - min;
        for (int z = 0; z < TILE; z++)
            for (int x = 0; x < TILE; x++)
                raw[z][x] = (raw[z][x] - min) / (range > 0 ? range : 1);
        
        float[][] h = null;
        
        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];
            int outStart = (HALF - OUT_R) * CHUNK * curRes / TILE;
            int outWidth = OUT_DIM * CHUNK * curRes / TILE;
            int delta = 3 * CHUNK * curRes / TILE;
            int lockW = li < 3 ? brushP[li] : 1;
            
            // 上采样/降采样
            if (li == 0) {
                h = new float[curRes][curRes];
                int scale = TILE / curRes;
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++) {
                        float s = 0;
                        int nn = 0;
                        for (int dz = 0; dz < scale; dz++)
                            for (int dx = 0; dx < scale; dx++) {
                                s += raw[z * scale + dz][x * scale + dx];
                                nn++;
                            }
                        h[z][x] = s / nn;
                    }
            } else {
                h = ErosionPipeline.bicubicUpsampleGrid(h, res[li - 1], curRes);
            }
            
            // 边缘锁定
            boolean[][] locked = new boolean[curRes][curRes];
            if (li < res.length - 1 && leftCache != null && leftCache[li] != null) {
                for (int col = 0; col < lockW; col++) {
                    int dstCol = outStart + col;
                    int srcCol = outStart + delta + col;
                    if (dstCol >= curRes || srcCol >= curRes) break;
                    for (int row = outStart; row < outStart + outWidth && row < curRes; row++) {
                        h[row][dstCol] = leftCache[li][row][srcCol];
                        locked[row][dstCol] = true;
                    }
                }
            }
            
            // 侵蚀
            if (li < res.length - 1) {
                int pad = Math.max(brushP[li] * 2, 4);
                float[][] p = ErosionPipeline.padGridMirror(h, curRes, pad);
                
                // 构建pLocked
                boolean[][] pLocked = new boolean[curRes + pad * 2][curRes + pad * 2];
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++)
                        if (locked[z][x]) pLocked[z + pad][x + pad] = true;
                
                ErosionPipeline.terraforgedErosion(p, curRes + pad * 2, dropsP[li],
                    strP[li], brushP[li], 0.5f, 0.001f, 2.5f,
                    erodeP[li], depP[li], seed + li * 10000L);
                
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z + pad], pad, h[z], 0, curRes);
                
                // 恢复锁定（直接从缓存取，正确！）
                if (leftCache != null && leftCache[li] != null) {
                    for (int col = 0; col < lockW; col++) {
                        int dstCol = outStart + col;
                        int srcCol = outStart + delta + col;
                        if (dstCol >= curRes || srcCol >= curRes) break;
                        for (int row = outStart; row < outStart + outWidth && row < curRes; row++)
                            h[row][dstCol] = leftCache[li][row][srcCol];
                    }
                }
            }
            
            // 缓存
            if (li < 3 && outCache != null) {
                outCache[li] = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(h[z], 0, outCache[li][z], 0, curRes);
            }
        }
        
        return h;
    }
    
    static void saveDetailed(float[][] a, float[][] b, int os, int ow, String fn) throws Exception {
        int sz = 128;
        int aR = os + ow - 1, bL = os;
        BufferedImage img = new BufferedImage(sz * 2, sz * 2, BufferedImage.TYPE_INT_RGB);
        
        // 上半：A右边缘 vs B左边缘
        for (int py = 0; py < sz; py++) {
            int row = os + py * ow / sz;
            if (row >= TILE) row = TILE - 1;
            int ga = (int) (ErosionPipeline.clamp(a[row][aR], 0, 1) * 255);
            int gb = (int) (ErosionPipeline.clamp(b[row][bL], 0, 1) * 255);
            for (int px = 0; px < sz; px++) img.setRGB(px, py, ga << 16 | ga << 8 | ga);
            for (int px = 0; px < sz; px++) img.setRGB(sz + px, py, gb);
            if (Math.abs(a[row][aR] - b[row][bL]) > 0.001)
                for (int x = sz - 2; x < sz + 2; x++) if (x >= 0 && x < sz * 2) img.setRGB(x, py, 0xFF0000);
        }
        
        // 下半：差异热力图
        for (int py = 0; py < sz; py++) {
            int row = os + py * ow / sz;
            if (row >= TILE) row = TILE - 1;
            double diff = Math.abs(a[row][aR] - b[row][bL]);
            int heat = (int) Math.min(255, diff * 1000);
            int color = heat << 16 | (255 - heat) << 8; // 红=差异大，绿=差异小
            for (int px = 0; px < sz * 2; px++) img.setRGB(px, sz + py, color);
        }
        
        new File("output").mkdirs();
        ImageIO.write(img, "png", new File("output/" + fn));
    }
}
