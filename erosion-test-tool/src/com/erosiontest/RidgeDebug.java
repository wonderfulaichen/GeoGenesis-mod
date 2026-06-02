package com.erosiontest;

public class RidgeDebug {
    public static void main(String[] args) {
        Noise n = new Noise(12345);
        System.out.println("=== sampleRidge 数值诊断 ===");
        int[][] points = {{0,0},{64,64},{128,128},{192,192},{-128,-128}};
        for (int[] p : points) {
            int wx = p[0] - 128, wz = p[1] - 128;
            float r = n.sampleRidge(wx, wz);
            System.out.println("(" + wx + "," + wz + ") -> ridge=" + String.format("%.6f", r));
        }
        float min = 1, max = 0, sum = 0;
        int cnt = 0;
        for (int z = 0; z < 256; z++)
            for (int x = 0; x < 256; x++) {
                float v = n.sampleRidge(x - 128, z - 128);
                if (v < min) min = v;
                if (v > max) max = v;
                sum += v;
                cnt++;
            }
        System.out.println("256x256统计: min=" + String.format("%.6f", min) + " max=" + String.format("%.6f", max) + " avg=" + String.format("%.6f", sum / cnt));
    }
}
