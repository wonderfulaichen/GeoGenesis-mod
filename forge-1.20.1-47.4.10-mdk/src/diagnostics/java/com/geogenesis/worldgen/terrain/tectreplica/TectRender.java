package com.geogenesis.worldgen.terrain.tectreplica;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * 出图 —— 配色与混色规则照抄 scene.h（256² 顶视 2D 化，替代原 3D 网格 + 光照）。
 *
 * <p>板块视图：色 = mix(magma, collide, clamp(thickness,0,1))（scene.h:261-282），
 * 背景 = magma；地形视图：h&le;sealevel 水面、缓坡绿地、陡坡灰岩（scene.h:283-293）。
 * 另出 hillshade 变体：高度场用 NW 光乘亮度，把原 3D 光照下的山链形态在平面图上显出来
 * （口径借 diagnostics 的 ViewSpec.shade：法线 (-dx,1,-dz)，NW 光 (-0.5,·,0.7)）。</p>
 */
final class TectRender {

    // scene.h:28-34
    private static final double[] MAGMA = {0.84, 0.17, 0.05};
    private static final double[] COLLIDE = {0.7, 0.64, 0.52};
    private static final double[] WATER = {0.15, 0.227, 0.364};
    private static final double[] EARTH = {0.20, 0.33, 0.17};
    private static final double[] STONE = {0.77, 0.72, 0.71};
    private static final double SEALEVEL = 0.25;   // scene.h:12
    private static final double STEEPNESS = 0.75;  // scene.h:13

    private TectRender() {
    }

    /** 板块视图（viewplates）：厚度混色；shaded=true 时按段高度场加 NW 山影。 */
    static void plateView(TectWorld w, String path, boolean shaded) throws IOException {
        double[] height = new double[TectWorld.SIZE * TectWorld.SIZE];
        int[] pixels = new int[TectWorld.SIZE * TectWorld.SIZE];
        for (int x = 0; x < TectWorld.SIZE; x++) {
            for (int y = 0; y < TectWorld.SIZE; y++) {
                int p = y * TectWorld.SIZE + x;
                int idx = w.cluster.indexmap[p];
                double[] c;
                if (idx < 0) {
                    c = MAGMA;
                } else {
                    TectSegment s = w.cluster.segs.get(idx);
                    double t = Math.min(1.0, Math.max(0.0, s.thickness));
                    c = mix(MAGMA, COLLIDE, t);
                    height[p] = TectWorld.SCALE * s.height;
                }
                pixels[p] = rgb(c);
            }
        }
        writeImage(pixels, height, path, shaded);
    }

    /** 地形视图（viewsurface）：heightmap + 海平面 + 坡度配色。 */
    static void terrainView(TectWorld w, String path, boolean shaded) throws IOException {
        double[] height = new double[TectWorld.SIZE * TectWorld.SIZE];
        int[] pixels = new int[TectWorld.SIZE * TectWorld.SIZE];
        for (int x = 0; x < TectWorld.SIZE; x++) {
            for (int y = 0; y < TectWorld.SIZE; y++) {
                int p = y * TectWorld.SIZE + x;
                double h = w.heightmap[p];
                height[p] = TectWorld.SCALE * h;
                double[] c;
                if (h <= SEALEVEL) {
                    c = WATER;
                } else {
                    // 网格法线 y 分量（scene.h:285 n1.y > steepness）：逐像素斜率 = tscale·Δh
                    double sx = SCALE_STEP * diff(w.heightmap, x, y, true);
                    double sy = SCALE_STEP * diff(w.heightmap, x, y, false);
                    double ny = 1.0 / Math.sqrt(1.0 + sx * sx + sy * sy);
                    c = ny > STEEPNESS ? EARTH : STONE;
                }
                pixels[p] = rgb(c);
            }
        }
        writeImage(pixels, height, path, shaded);
    }

    /** 热场：mix(black, red, heat)（tectonics.h:384-386 的 heatA 贴图）。 */
    static void heatView(TectWorld w, String path) throws IOException {
        BufferedImage img = new BufferedImage(TectWorld.SIZE, TectWorld.SIZE, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < TectWorld.SIZE; x++) {
            for (int y = 0; y < TectWorld.SIZE; y++) {
                double v = Math.min(1.0, Math.max(0.0, w.heatmap[x * TectWorld.SIZE + y]));
                img.setRGB(x, y, (255 << 24) | ((int) (v * 255) << 16));
            }
        }
        write(img, path);
    }

    /** Voronoi 分段图：每段一个确定性伪随机色，背景白（= cluster.target 贴图）。 */
    static void voronoiView(TectWorld w, String path) throws IOException {
        BufferedImage img = new BufferedImage(TectWorld.SIZE, TectWorld.SIZE, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < TectWorld.SIZE; x++) {
            for (int y = 0; y < TectWorld.SIZE; y++) {
                int idx = w.cluster.indexmap[y * TectWorld.SIZE + x];
                if (idx < 0) {
                    img.setRGB(x, y, 0xFFFFFF);
                    continue;
                }
                long h = idx * 0x9E3779B97F4A7C15L;
                h ^= h >>> 30;
                h *= 0xBF58476D1CE4E5B9L;
                h ^= h >>> 27;
                int r = (int) ((h >>> 24) & 0x7F) + 0x40;
                int g = (int) ((h >>> 40) & 0x7F) + 0x40;
                int b = (int) ((h >>> 56) & 0x7F) + 0x40;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        write(img, path);
    }

    // ===== 工具 =====

    /** 逐像素高度单位（高度已经过 SCALE 放大）。 */
    private static final double SCALE_STEP = TectWorld.SCALE;

    private static double[] mix(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static int rgb(double[] c) {
        int r = (int) (Math.min(1, Math.max(0, c[0])) * 255);
        int g = (int) (Math.min(1, Math.max(0, c[1])) * 255);
        int b = (int) (Math.min(1, Math.max(0, c[2])) * 255);
        return (r << 16) | (g << 8) | b;
    }

    /** 高度场的中央差分（每像素，钳边界；横向 true / 纵向 false）。 */
    private static double diff(double[] field, int x, int y, boolean horizontal) {
        if (horizontal) {
            int xm = Math.max(0, x - 1), xp = Math.min(TectWorld.SIZE - 1, x + 1);
            return (field[y * TectWorld.SIZE + xp] - field[y * TectWorld.SIZE + xm]) / (xp - xm);
        }
        int ym = Math.max(0, y - 1), yp = Math.min(TectWorld.SIZE - 1, y + 1);
        return (field[yp * TectWorld.SIZE + x] - field[ym * TectWorld.SIZE + x]) / (yp - ym);
    }

    /** NW 光山影（亮度乘子 ~0.35~1.4，法线 (-dx,1,-dy)·NW 光），直接产出最终像素。 */
    private static void writeImage(int[] pixels, double[] height, String path, boolean shaded) throws IOException {
        BufferedImage img = new BufferedImage(TectWorld.SIZE, TectWorld.SIZE, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < TectWorld.SIZE; x++) {
            for (int y = 0; y < TectWorld.SIZE; y++) {
                int p = y * TectWorld.SIZE + x;
                int c = pixels[p];
                if (shaded) {
                    double hx = diff(height, x, y, true);
                    double hy = diff(height, x, y, false);
                    double len = Math.sqrt(1.0 + hx * hx + hy * hy);
                    // 光源 NW（图左上）：法线 (-hx,1,-hy)·光 (-0.5,1,-0.7)，朝 SE 抬升的坡被照亮
                    double d = Math.max(0.0, (0.5 * hx + 0.7 * hy + 1.0) / (len * 1.22));
                    double shade = 0.35 + 1.05 * Math.min(1.0, d);
                    c = (clamp255((int) (((c >>> 16) & 0xFF) * shade)) << 16)
                            | (clamp255((int) (((c >>> 8) & 0xFF) * shade)) << 8)
                            | clamp255((int) ((c & 0xFF) * shade));
                }
                img.setRGB(x, y, c);
            }
        }
        write(img, path);
    }

    private static int clamp255(int v) {
        return Math.min(255, Math.max(0, v));
    }

    static void write(BufferedImage img, String path) throws IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", f);
    }
}
