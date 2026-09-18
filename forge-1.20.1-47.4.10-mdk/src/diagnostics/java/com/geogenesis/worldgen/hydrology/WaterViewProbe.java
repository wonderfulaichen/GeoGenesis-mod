package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 水景出图探针（★ 2026-09-19）—— 山体阴影 + 水体染色，用于【人眼目检】湖岸/河岸形态。
 *
 * <h2>为什么补这个</h2>
 * <p>用户按 F3 给出坐标后要求『跑图片』。但全仓 10 个出 PNG 的探针里，
 * <b>没有任何一个画蓝色水体</b>（{@code CanyonProfileProbe} 的 water.png 是灰度的）
 * ⇒ 重构期间那张『山体阴影 + 蓝色水体』的出图程序已不存在。
 * 本探针把它补回来，并保持<b>只用生产路径</b>（{@code getChunkCells}）。</p>
 *
 * <h2>口径（必须与游戏一致）</h2>
 * <ul>
 *   <li>高度：{@code cell.height}（生产路径，含侵蚀 + 水文雕刻回写）；</li>
 *   <li>水体：{@code cell.isLake}（湖泊，水文雕刻写入）与 {@code cell.isWater()}（海洋，e<0）；</li>
 *   <li>像素 ↔ 块：<b>1 像素 = 1 块</b>。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <pre>
 *   build/waterview/hillshade.png   纯地形山体阴影（NW 光）
 *   build/waterview/water_view.png  山体阴影 + 湖泊蓝 + 海洋深蓝   ← 目检用
 * </pre>
 *
 * <pre>{@code gradlew runWaterViewProbe [-PprobeArgs="seed blockX blockZ radiusBlocks"]}</pre>
 */
public final class WaterViewProbe {

    private WaterViewProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -426;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -347;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 128;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        boolean[][] lake = new boolean[w][w];
        boolean[][] ocean = new boolean[w][w];

        int nLake = 0, nOcean = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
                // X 主序（与 generateChunk / 预览层一致）：cells[lx*16 + lz]
                Cell c = cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                h[j][i] = c.height;
                lake[j][i] = c.isLake;
                ocean[j][i] = c.isWater();
                if (c.isLake) nLake++;
                if (c.isWater()) nOcean++;
            }
        }

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] row : h) {
            for (double v : row) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        }

        try {
            File dir = new File("build/waterview");
            dir.mkdirs();

            ImageIO.write(shade(h, mn, mx), "png", new File(dir, "hillshade.png"));

            BufferedImage wv = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            double[][] hs = shadeGray(h, mn, mx);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    int g = (int) Math.max(0, Math.min(255, hs[y][x]));
                    int rgb = (g << 16) | (g << 8) | g;
                    if (ocean[y][x])      rgb = 0x1040A0;   // 海洋：深蓝
                    else if (lake[y][x])  rgb = 0x1E78D2;   // 湖泊：亮蓝
                    wv.setRGB(x, y, rgb);
                }
            }
            ImageIO.write(wv, "png", new File(dir, "water_view.png"));

            System.out.printf("=== WaterViewProbe seed=%d ===%n", seed);
            System.out.printf("block=(%d,%d) radius=%d  窗口=%d×%d 块（1 像素 = 1 块）%n",
                    bx, bz, radius, w, w);
            System.out.printf("height 范围 = [%.1f, %.1f]%n", mn, mx);
            System.out.printf("湖泊块 = %d (%.2f%%)   海洋块 = %d (%.2f%%)%n",
                    nLake, 100.0 * nLake / (w * (double) w),
                    nOcean, 100.0 * nOcean / (w * (double) w));
            System.out.printf("输出目录 = %s%n", dir.getAbsolutePath());
            System.out.println("  hillshade.png  纯地形山体阴影");
            System.out.println("  water_view.png 山体阴影 + 湖泊蓝(#1E78D2) + 海洋深蓝(#1040A0)");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** 山体阴影灰度（NW 光，真实比例）。 */
    private static double[][] shadeGray(double[][] h, double mn, double mx) {
        int w = h.length;
        double[][] out = new double[w][w];
        final double lx = -0.5, ly = 0.7, lz = 0.51;
        for (int y = 1; y < w - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double dhx = (h[y][x + 1] - h[y][x - 1]) / 2.0;
                double dhz = (h[y + 1][x] - h[y - 1][x]) / 2.0;
                double nx = -dhx, ny = 1.0, nz = -dhz;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double d = (nx * lx + ny * ly + nz * lz) / Math.max(1e-9, len);
                out[y][x] = 60 + 175 * Math.max(0, d);
            }
        }
        // 边框用邻值填充，避免黑边
        for (int i = 0; i < w; i++) {
            out[0][i] = out[1][i];
            out[w - 1][i] = out[w - 2][i];
            out[i][0] = out[i][1];
            out[i][w - 1] = out[i][w - 2];
        }
        return out;
    }

    private static BufferedImage shade(double[][] h, double mn, double mx) {
        int w = h.length;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        double[][] g = shadeGray(h, mn, mx);
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) Math.max(0, Math.min(255, g[y][x]));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }
}
