package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 真实地形【平衡水面】出图：湖面 / 水深 / 溢口 / 入海通路。
 *
 * <p>与之前所有出图的关键区别：<b>不跑时间</b>。用的是已被
 * {@code WindowIndependenceProbe} 证明的窗口无关稳态解
 * （{@link EquilibriumWater}：到排水终端路径上最低的"最高点"）。</p>
 *
 * <p>输出三联：① 山体阴影 + 水面（湖=蓝、海=深蓝）② 水深分级
 * ③ 溢口标记（相邻格水面相同但床面更高的鞍点）+ 通海路径。</p>
 *
 * <p><b>声明的边界</b>：① 排水终端仅取"低于海平面"，未含内流盆地；
 * ② 窗口边框不算出口，由边框决定的格标未决且不着色；
 * ③ 无降水/蒸发水量平衡 ⇒ 这是"地形能蓄多少水"的几何稳态，不是降水后实际蓄水。</p>
 */
public final class EquilibriumLakeView {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 512;
        int step = 4;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (i % 32 == 0) {
                System.out.printf("  sampling %d/%d rows (%.0fs)%n", i, n, (System.nanoTime() - t0) / 1e9);
            }
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.erodedHeightForRouting(
                        (cx - radius + i * step) / hs, (cz - radius + j * step) / hs);
            }
        }
        System.out.printf("  sampled %d cells in %.1fs%n", n * n, (System.nanoTime() - t0) / 1e9);
        boolean[] drains = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        int seaCells = 0, exteriorExits = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (bed[k] < sea) { drains[k] = true; seaCells++; }
                // 边框格的"窗外"水位：由窗口外一格的【真实地形】给出（程序化已知，非猜测）。
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    double outside = gen.erodedHeightForRouting(
                            (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs);
                    exterior[k] = Math.max(outside, sea);
                    exteriorExits++;
                }
            }
        }
        long t1 = System.nanoTime();
        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, drains, exterior);
        System.out.printf("  equilibrium flood done in %.1fs (2 passes)%n", (System.nanoTime() - t1) / 1e9);
        double[] level = s.level();
        System.out.printf("  trueDrains=%d exteriorExits=%d unresolved=%d%n",
                s.trueDrains(), s.exteriorExits(), s.unresolvedCells());

        Path dir = Path.of("build/hydro-reassessment/equilibrium-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        BufferedImage over = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        BufferedImage depth = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        BufferedImage outlet = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int lakes = 0, lakeCells = 0, unresolved = 0, outlets = 0, dryCells = 0;
        double maxLakeDepth = 0, deepestX = 0, deepestZ = 0;
        StringBuilder outletCsv = new StringBuilder("xBlock,zBlock,sillLevel,basinDepth,outward\n");

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int shadeGray = shade(bed, n, i, j);
                boolean isSea = bed[k] < sea;
                double d = level[k] - bed[k];
                boolean resolved = s.resolved()[k];
                if (!resolved) unresolved++;

                int c1;
                if (isSea) { c1 = 0x1F4E79; }
                else if (d > 0.05) { c1 = 0x4A90E2; lakeCells++;
                    if (d > maxLakeDepth) { maxLakeDepth = d; deepestX = cx - radius + i * step; deepestZ = cz - radius + j * step; } }
                else { c1 = shadeGray; dryCells++; }
                over.setRGB(i, j, c1);

                // ★ 未决与干燥必须【视觉可区分】：未决=品红，干燥=地形灰。
                //   之前两者都用灰，导致"中心面板几乎全灰"无法解读。
                int c2;
                if (!resolved) c2 = 0xC030C0;
                else if (d <= 0.05) c2 = shadeGray;
                else if (d < 1) c2 = 0x7FD4E8;
                else if (d < 5) c2 = 0x2E8BC8;
                else if (d < 15) c2 = 0x1F5FA8;
                else c2 = 0x102F66;
                depth.setRGB(i, j, c2);

                int c3 = shadeGray;
                if (isSea) c3 = 0x1F4E79;
                else if (d > 0.05) c3 = 0x2E6FB0;
                outlet.setRGB(i, j, c3);
            }
        }

        // 溢口：水面高度相近但床面更高、且是"湖岸向外"的鞍点。
        var g = outlet.createGraphics();
        g.setColor(new Color(0xFF, 0xD0, 0x30));
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (!s.resolved()[k] || bed[k] < sea) continue;
                boolean wetHere = level[k] - bed[k] > 0.05;
                for (int[] dd : dirs) {
                    int ni = i + dd[0], nj = j + dd[1];
                    int nk = ni * n + nj;
                    if (!s.resolved()[nk] || level[nk] - bed[nk] > 0.05) continue;
                    if (level[nk] < level[k] - 0.05) continue;     // 下游侧
                    double sill = Math.max(bed[k], bed[nk]);
                    // 该格是被淹的，邻格干了 ⇒ 水位≈鞍口高程
                    if (wetHere && level[k] <= sill + 0.5) {
                        outlets++;
                        outletCsv.append(cx - radius + i * step).append(',')
                                .append(cz - radius + j * step).append(',')
                                .append(String.format("%.3f", level[k])).append(',')
                                .append(String.format("%.3f", level[k] - bed[k])).append(',')
                                .append(dd[0]).append(' ').append(dd[1]).append('\n');
                        g.drawOval(i - 3, j - 3, 6, 6);
                        break;
                    }
                }
            }
        }
        g.dispose();

        BufferedImage strip = new BufferedImage(n * 3 + 16, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                strip.setRGB(i, j, over.getRGB(i, j));
                strip.setRGB(n + 8 + i, j, depth.getRGB(i, j));
                strip.setRGB(2 * n + 16 + i, j, outlet.getRGB(i, j));
            }
        }
        ImageIO.write(strip, "png", dir.resolve("equilibrium.png").toFile());
        Files.writeString(dir.resolve("outlets.csv"), outletCsv);

        StringBuilder log = new StringBuilder();
        log.append("EQUILIBRIUM water surface from real bed; NO time stepping; NO rain/loss balance.\n");
        log.append("left: terrain+water(sea/lake) | middle: depth classes (magenta=unresolved) | right: +outlet markers\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d sea=%.1f hs=%.1f cells=%d%n",
                seed, cx, cz, radius, step, sea, hs, n * n));
        log.append(String.format("seaCells=%d dryCells=%d lakeCells=%d (%.2f%%) outlets=%d%n",
                seaCells, dryCells, lakeCells, 100.0 * lakeCells / (n * (double) n), outlets));
        // 未决比例用【收敛判据】统计：计入窗外真实地形后结果会变的格。
        log.append(String.format("unresolved(convergence criterion, WINDOW-DEPENDENT)=%d (%.1f%%)%n",
                unresolved, 100.0 * unresolved / (n * (double) n)));
        log.append(String.format("deepestLake=%.3f blocks at (%.0f,%.0f)%n", maxLakeDepth, deepestX, deepestZ));
        log.append("NOTE: lakes here = terrain can hold water to its lowest sill; actual lakes also need water balance.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("equilibrium.png").toAbsolutePath());
    }

    private static int shade(double[] bed, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (bed[i1 * n + j] - bed[i0 * n + j]) / (i1 - i0);
        double dz = (bed[i * n + j1] - bed[i * n + j0]) / (j1 - j0);
        double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * 1.0001);
        int gray = (int) (60 + 175 * Math.max(0, light));
        return (gray << 16) | (gray << 8) | gray;
    }

    private EquilibriumLakeView() { }
}
