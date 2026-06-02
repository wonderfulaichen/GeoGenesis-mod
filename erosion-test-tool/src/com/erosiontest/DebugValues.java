package com.erosiontest;

public class DebugValues {
    public static void main(String[] args) {
        Noise noise = new Noise(12345);
        System.out.println("=== terrainBaseMod 诊断 ===");
        int[][] pts = {{0,0},{1,0},{0,1},{1,1},{5,5},{10,10},{50,50}};
        for (int[] p : pts) {
            float v = noise.terrainBaseMod(p[0], p[1]);
            System.out.println("terrainBaseMod(" + p[0] + "," + p[1] + ")=" + String.format("%.4f", v));
        }
        System.out.println("\n=== 旧脊线公式（RidgeVisualTest中的）===");
        for (int[] p : pts) {
            float r = 0;
            for (int i = 0; i < 2; i++) {
                float f = (float)Math.pow(2.1, i);
                int sx = (int)(p[0] * f * 0.004f);
                int sz = (int)(p[1] * f * 0.004f);
                float n = noise.terrainBaseMod(sx, sz);
                float rv = 1f - Math.abs(n * 2f - 1f);
                r += Math.max(0, rv) * (float)Math.pow(0.5, i);
            }
            r /= 1.5f;
            System.out.println("oldRidge(" + p[0] + "," + p[1] + ") [scale 0.004*int]=" + String.format("%.4f", r));
        }
        System.out.println("\n=== 新sampleRidge ===");
        for (int[] p : pts) {
            float r = noise.sampleRidge(p[0], p[1]);
            System.out.println("sampleRidge(" + p[0] + "," + p[1] + ")=" + String.format("%.4f", r));
        }
        System.out.println("\n=== 检查坐标范围影响 ===");
        for (int wx : new int[]{-128, -64, 0, 64, 127}) {
            int sx = (int)(wx * 0.004f);
            System.out.println("wx=" + wx + " -> (int)(wx*0.004)=" + sx);
        }
    }
}
