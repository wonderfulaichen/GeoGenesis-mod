package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 层级脊线分支效果对比测试
 * 对比新旧脊线生成：
 *   旧：独立2层ridge叠加
 *   新：4层层级域扭曲ridge（粗脊→支脊→细脊）
 *
 * 编译: javac -d bin src/com/erosiontest/*.java
 * 运行: java -cp bin com.erosiontest.RidgeVisualTest [种子]
 *
 * 输出: output/ridge_s<种子>.png
 */
public class RidgeVisualTest {

    static int seed = 12345;
    static int size = 256;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);

        System.out.println("=== 层级脊线分支效果对比 ===");
        System.out.println("种子: " + seed + "  输出: " + size + "×" + size);

        Noise noise = new Noise(seed);

        float[][] oldTerrain = new float[size][size];
        float[][] newTerrain = new float[size][size];

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int wx = (x - size / 2) * 20, wz = (z - size / 2) * 20;

                float continent = noise.continentRaw(wx, wz);

                float baseT = noise.terrainBaseMod(wx, wz);

                // 旧式脊线：独立叠加，每层无关联
                float oldDetail = 0f;
                for (int i = 0; i < 2; i++) {
                    float f = (float)Math.pow(2.1, i);
                    float yOff = i * 3.7f + 10;
                    float n = noise.terrainBaseMod(wx * f * 0.008f, wz * f * 0.008f);
                    float r = 1f - Math.abs(n * 2f - 1f);
                    oldDetail += Math.max(0, r) * (float)Math.pow(0.5, i);
                }
                oldDetail /= 1.5f;
                float oldH = computeHeight(continent, baseT, oldDetail);
                oldTerrain[z][x] = oldH;

                // 新脊线：层级域扭曲分支脊线
                float ridge = noise.sampleRidge(wx, wz);
                float newDetail = ridge * 0.50f + baseT * 0.30f;
                float newH = computeHeight(continent, baseT, newDetail);
                newTerrain[z][x] = newH;
            }
        }

        float minH = 1, maxH = 0;
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                minH = Math.min(minH, Math.min(oldTerrain[z][x], newTerrain[z][x]));
                maxH = Math.max(maxH, Math.max(oldTerrain[z][x], newTerrain[z][x]));
            }
        float range = Math.max(maxH - minH, 0.001f);

        int gap = 6;
        int w = size * 2 + gap;
        int h = size + size / 2 + 70;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, z, toColor(oldTerrain[z][x], minH, range));
                img.setRGB(x + size + gap, z, toColor(newTerrain[z][x], minH, range));
            }
        }

        int diffY = size + 10;
        int diffH = size / 3;
        for (int z = 0; z < diffH; z++) {
            for (int x = 0; x < size; x++) {
                int sz = z * 3;
                if (sz < size) {
                    float diff = newTerrain[sz][x] - oldTerrain[sz][x];
                    int c;
                    float t = Math.abs(diff) * 3000;
                    t = Math.min(1, t);
                    int v = (int)(t * 255);
                    if (diff > 0.001f) c = (v << 16) | (v << 8);
                    else if (diff < -0.001f) c = (v << 8);
                    else c = 0x444444;
                    img.setRGB(x, diffY + z, c);
                }
            }
        }

        // Ridge-only comparison: sampleRidge output as grayscale
        int ridgeY = diffY + diffH + 8;
        int ridgeH = 50;
        for (int z = 0; z < ridgeH; z++) {
            for (int x = 0; x < size; x++) {
                int wx = (x - size / 2) * 20, wz = ((int)((float)z / ridgeH * size) - size / 2) * 20;
                float r = noise.sampleRidge(wx, wz);
                int v = (int)(r * 255);
                img.setRGB(x, ridgeY + z, (v << 16) | (v << 8) | v);
                // Old simple ridge
                float oldR = 0;
                for (int i = 0; i < 2; i++) {
                    float f = (float)Math.pow(2.1, i);
                    float n = noise.terrainBaseMod(wx * f * 0.008f, wz * f * 0.008f);
                    float rv = 1f - Math.abs(n * 2f - 1f);
                    oldR += Math.max(0, rv) * (float)Math.pow(0.5, i);
                }
                oldR /= 1.5f;
                int ov = (int)(oldR * 255);
                img.setRGB(x + size + gap, ridgeY + z, (ov << 16) | (ov << 8) | ov);
            }
        }

        // Profile: center row cross-section
        int profileY = ridgeY + ridgeH + 6;
        int profileH = 35;
        int centerZ = size / 2;
        for (int x = 0; x < size; x++) {
            float ho = oldTerrain[centerZ][x];
            float hn = newTerrain[centerZ][x];
            int pyOld = profileY + profileH - (int)((ho - minH) / range * profileH);
            int pyNew = profileY + profileH - (int)((hn - minH) / range * profileH);
            for (int dy = -1; dy <= 1; dy++) {
                setPixel(img, x, pyOld + dy, 0x4488FF);
                setPixel(img, x, pyNew + dy, 0x44FF44);
            }
        }

        drawLabel(img, "旧脊线地形", 10, 5, 0xFFFFFF);
        drawLabel(img, "新层级分支脊线地形", size + gap + 10, 5, 0xFFFFFF);
        drawLabel(img, "差异 (黄=升高,绿=降低)", 10, diffY - 3, 0xFFFF88);
        drawLabel(img, "旧脊线值(灰度)", 10, ridgeY - 3, 0xFFFFFF);
        drawLabel(img, "新层级分支脊线值(灰度)", size + gap + 10, ridgeY - 3, 0xFFFFFF);
        drawLabel(img, "断面: 蓝=旧脊线 绿=新层级分支脊线", 10, profileY + 6, 0xCCCCCC);

        File outDir = new File("output");
        outDir.mkdirs();
        File file = new File(outDir, "ridge_s" + seed + ".png");
        ImageIO.write(img, "png", file);
        System.out.println("已保存: " + file.getAbsolutePath());
        System.out.println("布局: 左=旧脊线  右=新层级分支脊线");
        System.out.println("下半区: 差异图 + 脊线灰度 + 断面曲线");
    }

    static float computeHeight(float continent, float terrain, float detail) {
        float seaNorm = 197f / 384f;
        float lift = Math.max(0, continent) * 0.15f;
        float land = seaNorm + detail * (1f - seaNorm) * 0.4f * (0.04f + terrain * terrain * 1.2f) + lift;
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
        if (t < 0.06f) return lerp(0x001040, 0x003080, t / 0.06f);
        if (t < 0.12f) return lerp(0x003080, 0x0068A0, (t - 0.06f) / 0.06f);
        if (t < 0.20f) return lerp(0x0068A0, 0x208060, (t - 0.12f) / 0.08f);
        if (t < 0.35f) return lerp(0x208060, 0x60A040, (t - 0.20f) / 0.15f);
        if (t < 0.50f) return lerp(0x60A040, 0xA09830, (t - 0.35f) / 0.15f);
        if (t < 0.65f) return lerp(0xA09830, 0xB07030, (t - 0.50f) / 0.15f);
        if (t < 0.80f) return lerp(0xB07030, 0x906030, (t - 0.65f) / 0.15f);
        if (t < 0.92f) return lerp(0x906030, 0xB0A890, (t - 0.80f) / 0.12f);
        return lerp(0xB0A890, 0xE0E8F0, (t - 0.92f) / 0.08f);
    }

    static int lerp(int c1, int c2, float t) {
        t = clamp(t, 0f, 1f);
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return ((int)(r1 + (r2 - r1) * t) << 16) | ((int)(g1 + (g2 - g1) * t) << 8) | (int)(b1 + (b2 - b1) * t);
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
