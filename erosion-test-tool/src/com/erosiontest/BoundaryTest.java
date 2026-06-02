package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 测试两个相邻 tile 的边界一致性（3-chunk间距固定网格）
 * Tile A 中心(0,0), Tile B 中心(3,0)
 */
public class BoundaryTest {

    static final int TILE = 112;
    static final int CHUNK = 16;
    static final int HALF_CHUNKS = 3;
    static final int OUT_RADIUS = 1;
    static final int OUT_DIM = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 边界一致性测试: 3-chunk 固定网格 ===\n");

        long seed = 12345L;
        Noise noise = new Noise((int)seed);

        // Tile A: 中心(0,0), world(-48,-48)
        float[][] rawA = genNoise(noise, -48, -48);
        // Tile B: 中心(3,0), world(0,-48)
        float[][] rawB = genNoise(noise, 0, -48);

        // 金字塔侵蚀
        int[] res = {14, 28, 56, 112};
        int[] brushP = {3, 4, 4};
        float[] strP = {1.5f, 1.2f, 1.0f};
        float[] erodeP = {0.3f, 0.2f, 0.15f};
        float[] depP = {0.04f, 0.03f, 0.02f};
        int[] dropsP = {7500, 9000, 6000};

        // === Tile A: 完全自由侵蚀 ===
        System.out.println("Tile A (完全自由侵蚀)...");
        float[][] hA = null;
        float[][][] cacheL = new float[3][][]; // 缓存 L0/L1/L2
        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];
            if (li == 0) hA = downsample(rawA, TILE, curRes);
            else hA = ErosionPipeline.bicubicUpsampleGrid(hA, res[li-1], curRes);

            if (li < res.length - 1) {
                int pad = Math.max(brushP[li]*2, 4);
                float[][] p = ErosionPipeline.padGridMirror(hA, curRes, pad);
                ErosionPipeline.terraforgedErosion(p, curRes+pad*2,
                    dropsP[li], strP[li], brushP[li], 0.5f, 0.001f, 2.5f,
                    erodeP[li], depP[li], seed + li * 10000L);
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z+pad], pad, hA[z], 0, curRes);
            }
            if (li < 3) cacheL[li] = hA;
        }
        System.out.println("  done");

        // === Tile B: 左边缘锁定到 Tile A 的 L0/L1/L2 ===
        System.out.println("Tile B (左边缘锁定)...");
        float[][] hB = null;
        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];
            int outStart = (HALF_CHUNKS - OUT_RADIUS) * CHUNK * curRes / TILE;
            int outWidth = OUT_DIM * CHUNK * curRes / TILE;
            int delta = 3 * CHUNK * curRes / TILE; // 48*curRes/112
            int lockW = li < 3 ? brushP[li] : 1;

            if (li == 0) hB = downsample(rawB, TILE, curRes);
            else hB = ErosionPipeline.bicubicUpsampleGrid(hB, res[li-1], curRes);

            boolean[][] locked = new boolean[curRes][curRes];
            float[][] saved = null;
            if (li < res.length - 1 && cacheL[li] != null) {
                saved = new float[curRes][curRes];
                // 左边缘锁 lockW 列（saved存Tile A值，侵蚀后恢复）
                for (int col = 0; col < lockW; col++) {
                    int dstCol = outStart + col;
                    int srcCol = outStart + delta + col;
                    if (dstCol >= curRes || srcCol >= curRes) break;
                    for (int i = outStart; i < outStart + outWidth && i < curRes; i++) {
                        saved[i][dstCol] = cacheL[li][i][srcCol]; // ← 保存Tile A值
                        hB[i][dstCol] = cacheL[li][i][srcCol];
                        locked[i][dstCol] = true;
                    }
                }
            }

            if (li < res.length - 1) {
                int pad = Math.max(brushP[li]*2, 4);
                float[][] p = ErosionPipeline.padGridMirror(hB, curRes, pad);
                ErosionPipeline.terraforgedErosion(p, curRes+pad*2,
                    dropsP[li], strP[li], brushP[li], 0.5f, 0.001f, 2.5f,
                    erodeP[li], depP[li], seed + li * 10000L + 999L);
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z+pad], pad, hB[z], 0, curRes);

                // 恢复锁定像素
                if (saved != null) {
                    for (int z = 0; z < curRes; z++)
                        for (int x = 0; x < curRes; x++)
                            if (locked[z][x]) hB[z][x] = saved[z][x];
                }
            }
            if (li < 3) cacheL[li] = hB; // B 也缓存
        }
        System.out.println("  done");

        // === 边界对比 ===
        int outStart = (HALF_CHUNKS - OUT_RADIUS) * CHUNK;
        int outWidth = OUT_DIM * CHUNK;
        int aRight = outStart + outWidth - 1;
        int bLeft = outStart;

        System.out.printf("\nTile A 右边缘(列%d) vs Tile B 左边缘(列%d):%n", aRight, bLeft);

        double maxDiff = 0, sumDiff = 0;
        int count = 0, bad = 0;
        for (int row = outStart; row < outStart + outWidth; row++) {
            double diff = Math.abs(hA[row][aRight] - hB[row][bLeft]);
            maxDiff = Math.max(maxDiff, diff);
            sumDiff += diff;
            count++;
            if (diff > 0.001) bad++;
        }

        System.out.printf("差异行: %d/%d, 最大: %.6f, 平均: %.6f%n",
            bad, count, maxDiff, sumDiff/count);
        System.out.println(maxDiff < 0.001 ? "✓ 无缝!" : "✗ 存在断裂!");

        // 可视化
        saveVisual(hA, hB, outStart, outWidth, "boundary_test.png");
        System.out.println("可视化: output/boundary_test.png");
    }

    static float[][] genNoise(Noise n, int ox, int oz) {
        float[][] m = new float[TILE][TILE];
        float min=1,max=0;
        for (int z=0;z<TILE;z++)for(int x=0;x<TILE;x++){
            m[z][x]=n.terrainBaseMod(ox+x,oz+z);
            if(m[z][x]<min)min=m[z][x]; if(m[z][x]>max)max=m[z][x];
        }
        float r=max-min;
        for (int z=0;z<TILE;z++)for(int x=0;x<TILE;x++)
            m[z][x]=(m[z][x]-min)/(r>0?r:1);
        return m;
    }

    static float[][] downsample(float[][] s, int ss, int ds) {
        float[][] d = new float[ds][ds];
        int scale = ss / ds;
        for (int z = 0; z < ds; z++)
            for (int x = 0; x < ds; x++) {
                float su = 0; int n = 0;
                for (int dz = 0; dz < scale; dz++)
                    for (int dx = 0; dx < scale; dx++)
                        { su += s[z*scale+dz][x*scale+dx]; n++; }
                d[z][x] = su / n;
            }
        return d;
    }

    static void saveVisual(float[][] a, float[][] b, int os, int ow, String fn) throws Exception {
        int sz = 256;
        int ar = os + ow - 1, bl = os;
        BufferedImage img = new BufferedImage(sz*2, sz, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < sz; py++) {
            int row = os + py * ow / sz;
            if (row >= TILE) row = TILE - 1;
            int ga = (int)(ErosionPipeline.clamp(a[row][ar],0,1)*255);
            int gb = (int)(ErosionPipeline.clamp(b[row][bl],0,1)*255);
            for (int px = 0; px < sz; px++) img.setRGB(px, py, ga<<16|ga<<8|ga);
            for (int px = 0; px < sz; px++) img.setRGB(sz+px, py, gb);
            if (Math.abs(a[row][ar]-b[row][bl]) > 0.005)
                for (int x = sz-1; x < sz+1; x++) img.setRGB(x, py, 0xFF0000);
        }
        new File("output").mkdirs();
        ImageIO.write(img, "png", new File("output/"+fn));
    }
}
