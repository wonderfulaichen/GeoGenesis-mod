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
 * 河道雕刻审计（2026-10）—— 按用户定义判定"河流是否真的在原地形上切出河道"。
 *
 * <h2>用户定义（本探针的判据来源）</h2>
 * <ul>
 *   <li><b>湖泊 = 填水</b>（不改地形）；</li>
 *   <li><b>河流 = 在原地形上雕刻切出河道</b>（只下挖，carved &lt; original）。</li>
 * </ul>
 *
 * <h2>核心判据：有水列必须"被挖过"</h2>
 * <pre>  riverType != 0  ⇒  carved &lt; original − ε      （河道 = 挖出来的）</pre>
 * <p>若某列有水（{@code riverSurfaceY > height}）却没被雕刻
 * （{@code carved == original}，即走了湖分支 lakePlan），而它又<b>不在真实湖域内</b>
 * ⇒ 就是"水悬在原地形之上"（用户截图的高架水渠）。</p>
 *
 * <h2>输出</h2>
 * <ol>
 *   <li>逐列审计：有水但未雕刻、且不在湖域 ⇒ 违规（含坐标/水面/地形/超高）；</li>
 *   <li>局部俯视图 PNG：原地面高度 + 水体 + 违规列标红。</li>
 * </ol>
 *
 * <pre>{@code gradlew runRiverCarveAuditProbe [-PprobeArgs="seed blockX blockZ radius [step]"]}</pre>
 */
public final class RiverCarveAuditProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -319;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -207;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 96;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        System.out.printf("=== RiverCarveAuditProbe seed=%d centre=(%d,%d) r=%d step=%d ===%n",
                seed, bx, bz, radius, step);
        System.out.printf("  skeletonRouting(生效) = %s%n", RiverLineNetwork.flowSkeletonRouting);
        System.out.println("  判据：有水 ⇒ 必须 carved < original（河=挖出来的）；否则水悬在地形上");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();

        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        int water = 0, notCarved = 0, notCarvedBig = 0, carvedOk = 0;
        double worst = 0, sum = 0;
        int shown = 0;
        int n = 2 * radius / step + 1;

        // 图：原地面高度(灰) + 有水(蓝) + 违规(红)
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        double[][] H = new double[n][n];
        int[][] KIND = new int[n][n];    // 0 干 / 1 有水且已雕刻 / 2 有水未雕刻(违规) / 3 湖
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int x = bx - radius + i * step, z = bz - radius + j * step;
                Cell c = cellAt(gt, x, z);
                double orig = gen.sample(x / hs, z / hs).height;
                H[j][i] = orig;
                if (orig < hMin) hMin = orig;
                if (orig > hMax) hMax = orig;
                if (c.riverType == 0 || !(c.riverSurfaceY > c.height)) continue;
                water++;
                // 用 carver 的原始计划判断"是否挖过"
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, x, z, orig, hs);
                boolean carved = col != null && col.carvedGroundY() < col.originalGroundY() - 1e-6;
                boolean lake = col != null && col.lakePlan();
                if (lake) {
                    KIND[j][i] = 3;
                    continue;                      // 湖列：填水，不雕刻是正常的
                }
                if (carved) { carvedOk++; KIND[j][i] = 1; continue; }
                // ★ 有水、不是湖、却没雕刻 ⇒ 违规
                notCarved++;
                KIND[j][i] = 2;
                double over = c.riverSurfaceY - orig;
                if (over > 1.0) notCarvedBig++;
                sum += over;
                if (over > worst) worst = over;
                if (shown < 14) {
                    System.out.printf("    (%d,%d) 水面=%.2f 原地形=%.2f 放置=%.2f ⇒ 超高=%.2f"
                                    + "  carved=%.2f orig=%.2f lakePlan=%b fillWater=%b%n",
                            x, z, c.riverSurfaceY, orig, c.height, over,
                            col == null ? Double.NaN : col.carvedGroundY(),
                            col == null ? Double.NaN : col.originalGroundY(),
                            lake, col != null && col.fillWater());
                    shown++;
                }
            }
        }

        System.out.printf("%n有水列 = %d%n", water);
        System.out.printf("  · 湖列（填水，不挖正常）      = %d%n", count(KIND, n, 3));
        System.out.printf("  ✅ 河道已雕刻（carved<orig）  = %d%n", carvedOk);
        System.out.printf("  ★ 有水、非湖、却未雕刻        = %d (%.1f%%)  ← 水悬在地形上%n",
                notCarved, 100.0 * notCarved / Math.max(1, water));
        System.out.printf("  ★ 其中超高 >1 块              = %d%n", notCarvedBig);
        System.out.printf("    平均超高 = %.2f 块，最大 = %.2f 块%n",
                notCarved == 0 ? 0 : sum / notCarved, worst);

        // 出图
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double f = (H[j][i] - hMin) / Math.max(1e-9, hMax - hMin);
                int g = (int) (40 + f * 150);
                int rgb = (g << 16) | (g << 8) | g;
                if (KIND[j][i] == 1) rgb = 0x2E6FD2;         // 河（已雕刻）蓝
                else if (KIND[j][i] == 3) rgb = 0x66CCCC;    // 湖 青
                else if (KIND[j][i] == 2) rgb = 0xFF2020;    // ★ 违规 红
                img.setRGB(i, j, rgb);
            }
        }
        Path dir = Path.of("build/carve-audit");
        Files.createDirectories(dir);
        Path out = dir.resolve("carve_audit.png");
        ImageIO.write(img, "png", out.toFile());
        System.out.printf("%n  图例：深灰=地形(明暗=高程) 蓝=已雕刻河道 青=湖 红=★水悬在地形上%n");
        System.out.printf("  IMAGE=%s%n", out.toAbsolutePath());
        System.out.printf("%nstatus=%s water=%d carvedOk=%d violated=%d(%.1f%%) worst=%.2f%n",
                notCarvedBig > 0 ? "DEFECT" : "OK", water, carvedOk, notCarved,
                100.0 * notCarved / Math.max(1, water), worst);
    }

    private static int count(int[][] k, int n, int v) {
        int c = 0;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) if (k[j][i] == v) c++;
        return c;
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverCarveAuditProbe() { }
}
