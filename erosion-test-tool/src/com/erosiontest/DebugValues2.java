package com.erosiontest;

public class DebugValues2 {
    public static void main(String[] args) {
        Noise noise = new Noise(12345);
        System.out.println("=== 修复后：scale=0.008 (float) ===");
        for (int wx : new int[]{-128, -64, 0, 64, 127}) {
            for (int wz : new int[]{-128, 0, 127}) {
                float oldR = 0;
                for (int i = 0; i < 2; i++) {
                    float f = (float)Math.pow(2.1, i);
                    float n = noise.terrainBaseMod(wx * f * 0.008f, wz * f * 0.008f);
                    float rv = 1f - Math.abs(n * 2f - 1f);
                    oldR += Math.max(0, rv) * (float)Math.pow(0.5, i);
                }
                oldR /= 1.5f;
                float newR = noise.sampleRidge(wx, wz);
                System.out.println("(" + wx + "," + wz + ") oldR=" + String.format("%.4f", oldR) + " newR=" + String.format("%.4f", newR));
            }
        }
    }
}
