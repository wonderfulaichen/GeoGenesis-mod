package com.erosiontest;

public class DiagErosion {
    static int seed = 12345;

    public static void main(String[] args) {
        Noise noise = new Noise(seed);
        int size = 200;

        float[][] base = new float[size][size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                base[z][x] = computeHeight(noise, x - size/2, z - size/2);

        float[][] oldE = cloneGrid(base);
        new Erosion(noise, seed).applyErosion(oldE, size, 1.0f);

        float[][] v2e = cloneGrid(base);
        ErosionV2 v2 = new ErosionV2();
        v2.applyMultiLayer(v2e, size,
            new float[][]{{1.0f, 7, 0.3f, 0.3f}, {0.6f, 3, 0.3f, 0.3f}, {0.3f, 1, 0.3f, 0.3f}},
            60000);

        float oldMaxD=0, v2MaxD=0, oldSumD=0, v2SumD=0, oldCount=0, v2Count=0;
        for (int z=0;z<size;z++) for (int x=0;x<size;x++) {
            float od = base[z][x] - oldE[z][x];
            float vd = base[z][x] - v2e[z][x];
            if (od>oldMaxD) oldMaxD=od;
            if (vd>v2MaxD) v2MaxD=vd;
            oldSumD += od; v2SumD += vd;
            if (od>0.001f) oldCount++;
            if (vd>0.001f) v2Count++;
        }
        float oldAvg = oldSumD/(size*size);
        float v2Avg = v2SumD/(size*size);
        System.out.println("=== 侵蚀数值对比 ===");
        System.out.println("旧版Erosion:  maxΔ=" + String.format("%.4f", oldMaxD) + " avgΔ=" + String.format("%.4f", oldAvg) + " 侵蚀像素=" + (int)oldCount);
        System.out.println("ErosionV2:    maxΔ=" + String.format("%.4f", v2MaxD) + " avgΔ=" + String.format("%.4f", v2Avg) + " 侵蚀像素=" + (int)v2Count);

        System.out.println("\n特定点对比 (base / old / v2):");
        int[][] pts = {{50,50},{100,50},{150,50},{50,100},{100,100},{150,100}};
        for (int[] p : pts) {
            int x=p[0], z=p[1];
            System.out.println("("+x+","+z+"): base=" + String.format("%.4f", base[z][x]) + " old=" + String.format("%.4f", oldE[z][x]) + " v2=" + String.format("%.4f", v2e[z][x]));
        }
    }

    static float computeHeight(Noise noise, int wx, int wz) {
        float continent = noise.continentRaw(wx, wz);
        float terrain = noise.terrainBaseMod(wx, wz);
        float seaNorm = 197f / 384f;
        float lift = Math.max(0, continent) * 0.15f;
        float land = seaNorm + terrain * (1f - seaNorm) * 0.4f * (0.04f + terrain * terrain * 1.2f) + lift;
        float oceanDepth = 0;
        if (continent < 0f) {
            float t = (continent + 1f);
            t = Math.max(0, Math.min(1, t));
            oceanDepth = 0.12f * (1f - t * t * (3 - 2 * t));
        }
        float ocean = seaNorm - oceanDepth;
        float mask;
        if (continent <= 0f) mask = 0;
        else if (continent >= 0.3f) mask = 1;
        else { float t = continent / 0.3f; mask = t * t * (3 - 2 * t); }
        return Math.max(0, Math.min(1, ocean * (1 - mask) + land * mask));
    }

    static float[][] cloneGrid(float[][] src) {
        int n = src.length;
        float[][] d = new float[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, d[i], 0, n);
        return d;
    }
}
