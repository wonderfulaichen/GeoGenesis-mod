package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 模组金字塔侵蚀的可视化测试
 * 使用独立噪声模块生成地形，运行模组 ErosionEngine 的 pyramidErosion
 *
 * 编译: javac -d bin src/com/erosiontest/*.java
 * 运行: java -cp bin com.erosiontest.RunModErosion [种子] [强度]
 *
 * 输出: output/mod_erosion_s<种子>_str<强度>.png
 */
public class RunModErosion {

    static int seed = 12345;
    static float strength = 1.0f;
    static int size = 200;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);
        if (args.length > 1) strength = Float.parseFloat(args[1]);

        System.out.println("=== 模组金字塔侵蚀测试 ===");
        System.out.println("种子: " + seed + "  强度: " + strength + "  分辨率: " + size + "×" + size);

        Noise noise = new Noise(seed);
        ModErosionEngine modErosion = new ModErosionEngine();

        long t0 = System.currentTimeMillis();
        float[][] base = new float[size][size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                base[z][x] = computeHeight(noise, x - size/2, z - size/2);
        long t1 = System.currentTimeMillis();
        System.out.println("地形生成: " + (t1 - t0) + "ms");

        float[][] eroded = cloneGrid(base);

        System.out.print("运行 pyramidErosion (r=4, 12000滴, 世界坐标采样)...");
        long t2 = System.currentTimeMillis();
        int pad = 4;
        float[][] p = padGridMirror(eroded, size, pad);
        modErosion.pyramidErosion(p, size + pad*2, (int)(12000 * strength),
            strength, 4, 0.5f, 0.001f, 2.5f, 0.2f, 0.03f,
            -size/2, -size/2, pad, size, 1, null);
        for (int z = 0; z < size; z++)
            System.arraycopy(p[z+pad], pad, eroded[z], 0, size);
        long t3 = System.currentTimeMillis();
        System.out.println("完成 (" + (t3 - t2) + "ms)");

        // 用旧 Erosion 做对比
        Erosion oldErosion = new Erosion(noise, seed);
        float[][] oldEroded = cloneGrid(base);
        System.out.print("运行旧版 Erosion (80000滴, r=7)...");
        long t4 = System.currentTimeMillis();
        oldErosion.applyErosion(oldEroded, size, strength);
        long t5 = System.currentTimeMillis();
        System.out.println("完成 (" + (t5 - t4) + "ms)");

        renderAndSave(base, eroded, oldEroded, noise);
        System.out.println("总耗时: " + (t5 - t0) + "ms");
    }

    static float computeHeight(Noise noise, int wx, int wz) {
        float continent = noise.continentRaw(wx, wz);
        float terrain = noise.terrainBaseMod(wx, wz);
        float shapeNorm = terrain;
        float seaNorm = 197f / 384f;
        float continentBase = Math.max(0f, (continent + 1f) * 0.5f);
        float lift = Math.max(0, continent) * 0.15f;
        float land = seaNorm + shapeNorm * (1f - seaNorm) * 0.4f * (0.04f + terrain * terrain * 1.2f) + lift;
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

    static void renderAndSave(float[][] base, float[][] eroded, float[][] oldEroded, Noise noise) throws Exception {
        int s = base.length;
        float minH = 1, maxH = 0;
        for (int z = 0; z < s; z++)
            for (int x = 0; x < s; x++) {
                minH = Math.min(minH, Math.min(base[z][x], Math.min(eroded[z][x], oldEroded[z][x])));
                maxH = Math.max(maxH, Math.max(base[z][x], Math.max(eroded[z][x], oldEroded[z][x])));
            }
        float range = Math.max(maxH - minH, 0.001f);

        int gap = 6;
        int w = s * 3 + gap * 2;
        int h = s + s / 3 + 60;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int z = 0; z < s; z++) {
            for (int x = 0; x < s; x++) {
                img.setRGB(x, z, toColor(base[z][x], minH, range));
                img.setRGB(x + s + gap, z, toColor(eroded[z][x], minH, range));
                img.setRGB(x + (s + gap) * 2, z, toColor(oldEroded[z][x], minH, range));
            }
        }

        float maxModDelta = 0, maxOldDelta = 0;
        float[][] modDelta = new float[s][s];
        float[][] oldDelta = new float[s][s];
        for (int z = 0; z < s; z++)
            for (int x = 0; x < s; x++) {
                modDelta[z][x] = base[z][x] - eroded[z][x];
                oldDelta[z][x] = base[z][x] - oldEroded[z][x];
                if (modDelta[z][x] > maxModDelta) maxModDelta = modDelta[z][x];
                if (oldDelta[z][x] > maxOldDelta) maxOldDelta = oldDelta[z][x];
            }

        int deltaY = s + 10;
        for (int z = 0; z < s / 3; z++) {
            for (int x = 0; x < s; x++) {
                int mz = z * 3, mx = x;
                if (mz < s && mx < s) {
                    img.setRGB(x, deltaY + z, deltaColor(modDelta[mz][mx], maxModDelta));
                    img.setRGB(x + s + gap, deltaY + z, deltaColor(oldDelta[mz][mx], maxOldDelta));
                }
            }
        }

        int profileY = deltaY + s / 3 + 10;
        int profileH = 40;
        int profileCenterZ = s / 2;
        for (int x = 0; x < s; x++) {
            float hBefore = base[profileCenterZ][x];
            float hMod = eroded[profileCenterZ][x];
            float hOld = oldEroded[profileCenterZ][x];
            int pyBase = profileY + profileH - (int)((hBefore - minH) / range * profileH);
            int pyMod  = profileY + profileH - (int)((hMod - minH) / range * profileH);
            int pyOld  = profileY + profileH - (int)((hOld - minH) / range * profileH);
            for (int dy = -1; dy <= 1; dy++) {
                setPixel(img, x, pyBase + dy, 0x4488FF);
                setPixel(img, x, pyMod + dy, 0x44FF44);
                setPixel(img, x, pyOld + dy, 0xFF8844);
            }
        }

        drawLabel(img, "原始地形", 10, 5, 0xFFFFFF);
        drawLabel(img, "Mod Pyramid", s + gap + 10, 5, 0xFFFFFF);
        drawLabel(img, "Old Erosion", (s + gap) * 2 + 10, 5, 0xFFFFFF);
        drawLabel(img, "Mod Δ (红=侵蚀)", 10, deltaY - 3, 0xFF8888);
        drawLabel(img, "Old Δ (红=侵蚀)", s + gap + 10, deltaY - 3, 0xFF8888);
        drawLabel(img, "断面: 蓝=原始 绿=Mod 橙=Old", 10, profileY + 6, 0xCCCCCC);

        File outDir = new File("output");
        outDir.mkdirs();
        String name = "mod_erosion_s" + seed + "_str" + String.format("%.1f", strength).replace(',', '.');
        int runNum = 0;
        File file;
        do {
            String suffix = runNum == 0 ? "" : "_v" + runNum;
            file = new File(outDir, name + suffix + ".png");
            runNum++;
        } while (file.exists());
        ImageIO.write(img, "png", file);
        System.out.println("已保存: " + file.getAbsolutePath());
        System.out.println("布局: 左=原始  中=Mod金字塔  右=旧版Erosion");
        System.out.println("Mod 最大变化: " + String.format("%.4f", maxModDelta) +
                         "  旧版: " + String.format("%.4f", maxOldDelta));
    }

    static void setPixel(BufferedImage img, int x, int y, int color) {
        if (x >= 0 && x < img.getWidth() && y >= 0 && y < img.getHeight())
            img.setRGB(x, y, color);
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

    static int toColor(float h, float min, float range) {
        if (Float.isNaN(h) || Float.isInfinite(h)) return 0x444466;
        float t = range > 0 ? (h - min) / range : 0.5f;
        t = clamp(t, 0f, 1f);
        float[][] stops = {
            {0.00f, 0, 10, 60}, {0.06f, 0, 40, 110}, {0.12f, 0, 80, 140},
            {0.18f, 30, 120, 120}, {0.25f, 60, 140, 80}, {0.35f, 100, 160, 50},
            {0.50f, 150, 150, 40}, {0.65f, 170, 130, 60}, {0.78f, 190, 160, 130},
            {0.90f, 210, 200, 190}, {1.00f, 235, 240, 250},
        };
        for (int i = 0; i < stops.length - 1; i++) {
            if (t >= stops[i][0] && t <= stops[i + 1][0]) {
                float l = (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0]);
                l = l * l * (3 - 2 * l);
                int r = (int)(stops[i][1] + (stops[i + 1][1] - stops[i][1]) * l);
                int g = (int)(stops[i][2] + (stops[i + 1][2] - stops[i][2]) * l);
                int b = (int)(stops[i][3] + (stops[i + 1][3] - stops[i][3]) * l);
                return (r << 16) | (g << 8) | b;
            }
        }
        return 0;
    }

    static int deltaColor(float delta, float maxDelta) {
        if (Float.isNaN(delta) || Float.isInfinite(delta)) return 0x222222;
        float t = maxDelta > 0.0001f ? Math.abs(delta) / maxDelta : 0;
        t = clamp(t, 0f, 1f);
        int v = 60 + (int)(t * 195);
        if (delta > 0.001f) return (v << 16);
        if (delta < -0.001f) return (v << 8);
        int gray = 40 + (int)(t * 60);
        return (gray << 16) | (gray << 8) | gray;
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    static float[][] cloneGrid(float[][] src) {
        int n = src.length;
        float[][] d = new float[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, d[i], 0, n);
        return d;
    }

    static float[][] padGridMirror(float[][] src, int size, int pad) {
        int newSize = size + pad * 2;
        float[][] dst = new float[newSize][newSize];
        for (int z = 0; z < newSize; z++) {
            for (int x = 0; x < newSize; x++) {
                int sz = clamp(z - pad, 0, size - 1);
                int sx = clamp(x - pad, 0, size - 1);
                dst[z][x] = src[sz][sx];
            }
        }
        return dst;
    }
}
