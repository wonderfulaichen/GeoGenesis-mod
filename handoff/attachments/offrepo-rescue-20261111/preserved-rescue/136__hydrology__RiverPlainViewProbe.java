package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 【无阴影高程图 + 河流】渲染（2026-10）。
 *
 * <h2>为什么不用 shadeGray</h2>
 * <p>山体阴影有强方向性：朝光坡亮、背光坡暗 ⇒ <b>山脊与谷底的光影形状相似</b>，
 * 容易把"河在谷底"误读成"河贴山脊"。本图用<b>纯高程分级</b>（无光照），
 * 脊/谷一眼可辨，颜色分三色（海洋/湖泊/河流）。</p>
 *
 * <p>参数：seed bx bz radius [zoom]</p>
 */
public final class RiverPlainViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 240;
        int zoom = args.length > 4 ? Math.max(1, Integer.parseInt(args[4])) : 4;

        String sp = System.getProperty("geogenesis.skeletonRouting");
        if (sp != null) RiverLineNetwork.flowSkeletonRouting = Boolean.parseBoolean(sp);
        System.out.printf("=== RiverPlainViewProbe seed=%d centre=(%d,%d) r=%d zoom=%d "
                        + "skeleton=%s ===%n",
                seed, bx, bz, radius, zoom, RiverLineNetwork.flowSkeletonRouting);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                Cell c = cellAt(gt, bx - radius + i, bz - radius + j);
                h[j][i] = c.height;
                mn = Math.min(mn, c.height);
                mx = Math.max(mx, c.height);
            }
        }
        // 等高线式着色：每 8 块一档，高处更亮；无光照 ⇒ 脊/谷形状即真实形状
        int[][] map = new int[w][w];
        int riverN = 0, lakeN = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int band = (int) ((h[j][i] - mn) / 8.0) & 7;
                int g = 40 + band * 26;
                int rgb = (g << 16) | (g << 8) | g;
                Cell c = cellAt(gt, bx - radius + i, bz - radius + j);
                if (c.isWater()) { rgb = 0x1040A0; lakeN++; }
                else if (c.isLake) { rgb = 0x1E78D2; lakeN++; }
                else if (c.riverType != 0) { rgb = 0xE07010; riverN++; }
                map[j][i] = rgb;
            }
        }
        BufferedImage img = new BufferedImage(w * zoom, w * zoom, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < w * zoom; y++)
            for (int x = 0; x < w * zoom; x++)
                img.setRGB(x, y, map[y / zoom][x / zoom]);
        File dir = new File("build/river-plain");
        dir.mkdirs();
        File out = new File(dir, "plain.png");
        ImageIO.write(img, "png", out);
        System.out.printf("高程范围 [%.1f, %.1f]  河流列=%d  湖/海列=%d%n", mn, mx, riverN, lakeN);
        System.out.println("图例：橙=河流 亮蓝=湖泊 深蓝=海洋  灰=地形（等高线分级，无光照）");
        System.out.printf("IMAGE=%s (%d×%d)%n", out.getAbsolutePath(), w * zoom, w * zoom);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverPlainViewProbe() { }
}