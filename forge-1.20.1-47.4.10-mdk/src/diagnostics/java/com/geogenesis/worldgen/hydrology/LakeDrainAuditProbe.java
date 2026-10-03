package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 湖「漏水」审计（2026-10）—— 按用户定义判"水是否盖在不该有水的地形上"。
 *
 * <h2>用户定义</h2>
 * <ul>
 *   <li><b>湖泊 = 洼地填水</b>：水面必须被地形围住（岸外干地 ≥ 水面），否则水会流走；</li>
 *   <li><b>河流 = 原地形上雕刻出河道</b>（只下挖）。</li>
 * </ul>
 *
 * <h2>判据（湖岸不得漏水）</h2>
 * <pre>  湖列水面 S，其任一【非水】邻居的高度 &lt; S − 0.5  ⇒  水会从那里流走
 *                                                        ⇒  该湖面过高（水盖在地形上）</pre>
 *
 * <h2>输出</h2>
 * <ol>
 *   <li>违规列统计 + 样例（水面/地形/缺口）；</li>
 *   <li><b>垂直剖面 PNG</b>（X-Y 侧视）：地形灰、水蓝 —— 直接看水是否高于地形；</li>
 *   <li>俯视 PNG：违规列标红。</li>
 * </ol>
 *
 * <pre>{@code gradlew runLakeDrainAuditProbe [-PprobeArgs="seed blockX blockZ radius [profZ]"]}</pre>
 */
public final class LakeDrainAuditProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -313;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -165;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        int profZ = args.length > 4 ? Integer.parseInt(args[4]) : bz;

        System.out.printf("=== LakeDrainAuditProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        System.out.printf("  skeletonRouting(生效) = %s%n", RiverLineNetwork.flowSkeletonRouting);
        System.out.println("  判据：湖列水面 S，若有【非水】邻居 < S−0.5 ⇒ 水会流走 ⇒ 水面过高");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        int water = 0, leak = 0, leak2 = 0;
        double worst = 0, sum = 0;
        int shown = 0;
        int n = 2 * radius + 1;
        int[][] kind = new int[n][n];       // 0 干 1 水正常 2 漏水
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || !(c.riverSurfaceY > c.height)) continue;
                water++;
                double s = c.riverSurfaceY;
                double lowestDry = Double.MAX_VALUE;
                boolean anyDry = false;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Cell nb = cellAt(gt, x + dx, z + dz);
                        if (nb.riverType != 0 && nb.riverSurfaceY > nb.height) continue;
                        anyDry = true;
                        if (nb.height < lowestDry) lowestDry = nb.height;
                    }
                }
                if (!anyDry) { kind[j][i] = 1; continue; }
                double gap = s - lowestDry;
                if (gap > 0.5) {
                    leak++;
                    kind[j][i] = 2;
                    if (gap > 2.0) leak2++;
                    sum += gap;
                    if (gap > worst) worst = gap;
                    if (shown < 14) {
                        System.out.printf("    (%d,%d) 水面=%.2f 本列地形=%.2f 最低干邻=%.2f"
                                        + " ⇒ 漏水=%.2f  isLake=%b%n",
                                x, z, s, c.height, lowestDry, gap, c.isLake);
                        shown++;
                    }
                } else {
                    kind[j][i] = 1;
                }
            }
        }

        System.out.printf("%n有水列 = %d%n", water);
        System.out.printf("  ★ 漏水列（岸外干地低于水面）= %d (%.1f%%)%n",
                leak, 100.0 * leak / Math.max(1, water));
        System.out.printf("  ★ 其中缺口 >2 块            = %d (%.1f%%)%n",
                leak2, 100.0 * leak2 / Math.max(1, water));
        System.out.printf("    平均缺口 = %.2f 块，最大 = %.2f 块%n",
                leak == 0 ? 0 : sum / leak, worst);

        Path dir = Path.of("build/lake-drain");
        Files.createDirectories(dir);

        // 俯视图
        BufferedImage top = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int rgb = switch (kind[j][i]) {
                    case 2 -> 0xFF2020;
                    case 1 -> 0x2E6FD2;
                    default -> 0x505050;
                };
                top.setRGB(i, j, rgb);
            }
        }
        ImageIO.write(top, "png", dir.resolve("top.png").toFile());

        // 垂直剖面（X-Y 侧视）：沿 z=profZ 一条线
        int y0 = 9999, y1 = -9999;
        double[] hs = new double[n];
        double[] ws = new double[n];
        boolean[] wet = new boolean[n];
        for (int i = 0; i < n; i++) {
            int x = bx - radius + i;
            Cell c = cellAt(gt, x, profZ);
            hs[i] = c.height;
            ws[i] = c.riverSurfaceY;
            wet[i] = c.riverType != 0 && c.riverSurfaceY > c.height;
            y0 = Math.min(y0, (int) Math.floor(hs[i]));
            y1 = Math.max(y1, (int) Math.ceil(wet[i] ? ws[i] : hs[i]));
        }
        int rows = y1 - y0 + 1;
        BufferedImage sec = new BufferedImage(n, rows, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int r = 0; r < rows; r++) {
                int y = y1 - r;
                int rgb = 0x202020;
                if (y <= (int) Math.floor(hs[i])) rgb = 0x808080;             // 地形
                if (wet[i] && y > (int) Math.floor(hs[i]) && y <= (int) Math.floor(ws[i]))
                    rgb = 0x2E6FD2;                                            // 水柱
                sec.setRGB(i, r, rgb);
            }
        }
        ImageIO.write(sec, "png", dir.resolve("section.png").toFile());

        System.out.printf("%n  IMAGE top=%s%n", dir.resolve("top.png").toAbsolutePath());
        System.out.printf("  IMAGE section(x-y @ z=%d) = %s%n", profZ,
                dir.resolve("section.png").toAbsolutePath());
        System.out.printf("  图例：灰=地形 蓝=水 红=★漏水列（岸外干地低于水面）%n");
        System.out.printf("%nstatus=%s water=%d leak=%d(%.1f%%) leakBig=%d worst=%.2f%n",
                leak2 > 0 ? "DEFECT" : "OK", water, leak,
                100.0 * leak / Math.max(1, water), leak2, worst);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private LakeDrainAuditProbe() { }
}
