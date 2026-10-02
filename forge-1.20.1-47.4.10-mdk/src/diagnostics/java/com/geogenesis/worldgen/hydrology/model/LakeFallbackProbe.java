package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 湖泊兜底语义 A/B：<b>无来水的盆地</b>该"满湖"还是"干涸"？
 *
 * <h2>背景（用户提问驱动的结论）</h2>
 * <p>"水文如果没河流，怎么知道是不是真正的湖泊水位？" —— 答案是：<b>无法确定</b>。
 * 湖的真实水位依赖上游来水，而来水就是河网汇流量。几何（minimax 鞍口）只给上限。</p>
 *
 * <p>生产链旧判据（{@code HydroTileBalance.findLakeLevel}）的处置是
 * {@code qin <= 0 || decay <= 0 → return spill}：<b>无来水信息时直接当作满湖</b>。
 * 好处是处处有确定水位、图上绝不留空洞；代价是干燥内流盆地也会显示为湖。</p>
 *
 * <p>本探针在同一生产地形、同一气候下只切换这一个开关，量化它的影响：
 * 有多少湖格、多少水位差、以及未决区是否被填满。</p>
 *
 * <h2>边界</h2>
 * <p>诊断出图；不含落块；不含河网叠加。</p>
 */
public final class LakeFallbackProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double sea = gt.seaLevel();
        int n = 2 * radius + 1;

        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int bx = cx - radius + i, bz = cz - radius + j;
                Cell[] cells = gt.getChunkCells(bx >> 4, bz >> 4);
                Cell cell = cells[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                bed[i * n + j] = cell != null ? cell.height : sea;
            }
        }
        boolean[] seaMask = new boolean[n * n], border = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                seaMask[k] = bed[k] < sea;
                border[k] = i == 0 || j == 0 || i == n - 1 || j == n - 1;
                if (border[k]) {
                    int oi = Math.max(0, Math.min(n - 1, i + (i == 0 ? -1 : i == n - 1 ? 1 : 0)));
                    int oj = Math.max(0, Math.min(n - 1, j + (j == 0 ? -1 : j == n - 1 ? 1 : 0)));
                    exterior[k] = Math.max(bed[oi * n + oj], sea);
                }
            }
        }
        var surface = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        Path dir = Path.of("build/hydro-reassessment/fallback-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        BufferedImage[] imgs = new BufferedImage[2];
        String[] labels = {"fallback ON (unfed basin = full, matches production)",
                           "fallback OFF (unfed basin = dry)"};
        int[] lakeCounts = new int[2];
        double[] lakeArea = new double[2];

        for (int mode = 0; mode < 2; mode++) {
            var balance = new LakeWaterBalance(n, n, 1.0, bed, surface)
                    .assumeFullWhenUnfed(mode == 0);
            var res = balance.solve(climate, seaMask, border);
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int k = i * n + j;
                    int color;
                    if (seaMask[k]) color = 0x1F4E79;
                    else if (res.lake()[k]) { color = 0x4A90E2; lakeCounts[mode]++; lakeArea[mode] += 1; }
                    else if (!surface.resolved()[k]) color = 0xC030C0;
                    else color = shade(bed, n, i, j);
                    img.setRGB(i, j, color);
                }
            }
            imgs[mode] = img;
        }

        BufferedImage strip = new BufferedImage(n * 2 + 8, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                strip.setRGB(i, j, imgs[0].getRGB(i, j));
                strip.setRGB(n + 8 + i, j, imgs[1].getRGB(i, j));
            }
        }
        ImageIO.write(strip, "png", dir.resolve("fallback_ab.png").toFile());

        StringBuilder log = new StringBuilder();
        log.append("same production terrain & climate; only the 'unfed basin' rule differs\n");
        for (int m = 0; m < 2; m++) {
            log.append(String.format("%-62s lake cells=%7d (%.2f%%)%n",
                    labels[m], lakeCounts[m], 100.0 * lakeCounts[m] / (n * (double) n)));
        }
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d image=%dx%d precip=%.4f%n",
                seed, cx, cz, radius, n, n, precip));
        log.append("NOT VERIFIED: block placement, in-game render, river overlay.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("fallback_ab.png").toAbsolutePath());
    }

    private static int shade(double[] h, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (h[i1 * n + j] - h[i0 * n + j]) / (i1 - i0);
        double dz = (h[i * n + j1] - h[i * n + j0]) / (j1 - j0);
        double nx = -dx, ny = 1, nz = -dz;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lx = -0.5, ly = 0.7, lz = 0.51;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double d = (nx * (lx / ll) + ny * (ly / ll) + nz * (lz / ll)) / nl;
        int g = (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d)));
        return (g << 16) | (g << 8) | g;
    }

    private LakeFallbackProbe() { }
}
