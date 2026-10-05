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
 * 把【已验证的新水文管线】的水，画在【生产链实际地形】上 —— 同底对照。
 *
 * <h2>为什么必须这样做</h2>
 * <p>此前并排图看起来"地形不同"，实测已排除输入场问题：
 * {@link TerrainAgreementProbe} 显示 {@code erodedHeightForRouting} 与生产
 * {@code cell.height} 的 <b>平均差仅 0.01 块</b>（maxAbs 2.91 仅出现在河谷列）。
 * 真正的差别是：生产链在侵蚀之后还会跑 {@code applyHydrologyValley} <b>雕刻河谷</b>，
 * 图上那些细密沟谷正是<b>雕刻产物</b>，而新管线尚未雕刻。</p>
 *
 * <p>因此本探针固定使用<b>生产链的 cell.height</b> 作地形底图与水文输入，
 * 只把新管线的湖泊/河流结果叠加上去，使对比只剩下"水文结果"这一个变量。</p>
 *
 * <h2>边界</h2>
 * <p>诊断出图，不落块、不接生产。未决格以品红标出。</p>
 */
public final class OverProductionTerrainProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 700;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double sea = gt.seaLevel();
        int n = 2 * radius + 1;

        // ① 生产地形（含侵蚀 + 河谷雕刻）
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int bx = cx - radius + i, bz = cz - radius + j;
                Cell[] cells = gt.getChunkCells(bx >> 4, bz >> 4);
                Cell cell = cells[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                bed[i * n + j] = cell != null ? cell.height : sea;
            }
        }

        // ② 新管线：以【同一生产地形】为输入（不是 erodedHeightForRouting）
        boolean[] seaMask = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                seaMask[k] = bed[k] < sea;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    int oi = Math.max(0, Math.min(n - 1, i + (i == 0 ? -1 : i == n - 1 ? 1 : 0)));
                    int oj = Math.max(0, Math.min(n - 1, j + (j == 0 ? -1 : j == n - 1 ? 1 : 0)));
                    exterior[k] = Math.max(bed[oi * n + oj], sea);
                }
            }
        }
        var surface = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);
        boolean[] border = new boolean[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                border[i * n + j] = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            }
        }
        var balance = new LakeWaterBalance(n, n, 1.0, bed, surface);
        var lakeResult = balance.solve(climate, seaMask, border);

        // ③ 出图：地形底（生产）+ 新管线水
        Path dir = Path.of("build/hydro-reassessment/overprod-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int lake = 0, unresolved = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (seaMask[k]) color = 0x1F4E79;
                else if (lakeResult.lake()[k]) { color = 0x4A90E2; lake++; }
                else if (!surface.resolved()[k]) { color = 0xC030C0; unresolved++; }
                else color = shade(bed, n, i, j);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve("over.png").toFile());

        StringBuilder log = new StringBuilder();
        log.append("same base terrain as production (cell.height incl. river carving); overlay = NEW pipeline water\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=1 image=%dx%d precip=%.4f sea=%.1f%n",
                seed, cx, cz, radius, n, n, precip, sea));
        log.append(String.format("new-pipeline lake=%d (%.2f%%) unresolved=%d (%.2f%%)%n",
                lake, 100.0 * lake / (n * (double) n),
                unresolved, 100.0 * unresolved / (n * (double) n)));
        log.append("NOT VERIFIED: block placement, in-game render, river network in this figure.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("over.png").toAbsolutePath());
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

    private OverProductionTerrainProbe() { }
}
