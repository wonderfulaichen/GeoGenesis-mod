package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 对比：原始地形 vs 旧版Erosion（图一） vs ErosionV2多层粒子
 */
public class TestErosionV2 {

    static int seed = 12345;
    static int size = 200;
    static float zoom = 3.5f;  // 放大倍数，只看 1~2 座山

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);

        Noise noise = new Noise(seed);
        Erosion oldErosion = new Erosion(noise, seed);
        ErosionV2 v2 = new ErosionV2();

        float[][] base = new float[size][size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                base[z][x] = computeHeight(noise, (int)((x - size/2) * zoom), (int)((z - size/2) * zoom));

        // 旧版Erosion（图一）: 80000滴, r=7
        float[][] oldE = cloneGrid(base);
        System.out.print("旧版Erosion (80000滴, r=7)...");
        oldErosion.applyErosion(oldE, size, 1.0f);
        System.out.println("完成");

        // ErosionV2 三层：大笔刷挖骨架 → 中笔刷细化 → 小笔刷纹理
        float[][] v2e = cloneGrid(base);
        System.out.print("ErosionV2 三层 (r=8大→r=3中→r=1细)...");
        v2.applyMultiLayer(v2e, size,
            new float[][]{
                {1.0f, 8, 0.3f, 0.1f},    // 大笔刷(r=8)：深挖河谷网络，少沉积
                {0.5f, 3, 0.15f, 0.4f},   // 中笔刷：沟壑边缘细化，多沉积
                {0.2f, 1, 0.08f, 0.5f},   // 小笔刷：表面纹理，轻触
            },
            80000);  // 基础滴数（总滴数≈80000+40000+16000=136000）
        System.out.println("完成");

        // 再跑一组老参数做对比
        float[][] v2h = cloneGrid(base);
        System.out.print("ErosionV2 参考版 (r=7满滴)...");
        v2.applyMultiLayer(v2h, size,
            new float[][]{
                {1.0f, 7, 0.3f, 0.3f},    // 接近旧版Erosion
            },
            80000);
        System.out.println("完成");

        renderAndSave(base, oldE, v2e, v2h);
    }

    static void renderAndSave(float[][] base, float[][] oldE, float[][] v2e, float[][] v2h) throws Exception {
        int s = base.length;
        float minH = 1, maxH = 0;
        for (int z = 0; z < s; z++)
            for (int x = 0; x < s; x++) {
                minH = Math.min(minH, Math.min(base[z][x], Math.min(oldE[z][x], Math.min(v2e[z][x], v2h[z][x]))));
                maxH = Math.max(maxH, Math.max(base[z][x], Math.max(oldE[z][x], Math.max(v2e[z][x], v2h[z][x]))));
            }
        float range = Math.max(maxH - minH, 0.001f);

        int gap = 6;
        int cols = 4;
        int w = s * cols + gap * (cols - 1);
        int h = s + s / 3 + 60;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        float[][][] grids = {base, oldE, v2e, v2h};
        String[] labels = {"原始", "旧版Erosion (图一)", "V2三层 大→中→细", "V2 r=7单层参考"};

        for (int col = 0; col < cols; col++) {
            int ox = col * (s + gap);
            float[][] g = grids[col];
            for (int z = 0; z < s; z++)
                for (int x = 0; x < s; x++)
                    img.setRGB(ox + x, z, toColor(g[z][x], minH, range));
            drawLabel(img, labels[col], ox + 10, 5, 0xFFFFFF);
        }

        // 差异图（相对于原始）
        int deltaY = s + 10;
        for (int col = 1; col < cols; col++) {
            int ox = col * (s + gap);
            float[][] g = grids[col];
            float maxDelta = 0;
            for (int z = 0; z < s; z++)
                for (int x = 0; x < s; x++)
                    maxDelta = Math.max(maxDelta, base[z][x] - g[z][x]);
            for (int z = 0; z < s / 3; z++)
                for (int x = 0; x < s; x++) {
                    int mz = z * 3;
                    if (mz < s)
                        img.setRGB(ox + x, deltaY + z, deltaColor(base[mz][x] - g[mz][x], maxDelta));
                }
        }
        drawLabel(img, "侵蚀差异 (红=侵蚀)", 10, deltaY - 3, 0xFF8888);

        // 断面图
        int profileY = deltaY + s / 3 + 10;
        int profileH = 40;
        int centerZ = s / 2;
        int[] profileColors = {0x4488FF, 0x44FF44, 0xFF8844, 0xFF44FF};
        for (int col = 0; col < cols; col++) {
            float[][] g = grids[col];
            int ox = col * (s + gap);
            for (int x = 0; x < s; x++) {
                float hv = g[centerZ][x];
                int py = profileY + profileH - (int)((hv - minH) / range * profileH);
                setPixel(img, ox + x, py, profileColors[col]);
                setPixel(img, ox + x, py + 1, profileColors[col]);
            }
        }
        drawLabel(img, "断面: 蓝=原始 绿=旧Erosion 橙=V2三层 粉=V2高强", 10, profileY + 6, 0xCCCCCC);

        File outDir = new File("output");
        outDir.mkdirs();
        File file = new File(outDir, "erosion_v2_s" + seed + ".png");
        ImageIO.write(img, "png", file);
        System.out.println("已保存: " + file.getAbsolutePath());
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
}
