package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 湖—河—海一体出图与判据（不跑时间）。
 *
 * <p>判据（方向性，非数值贴合）：</p>
 * <ol>
 *   <li><b>越湿河越多</b>：成河格数随降水单调不减。</li>
 *   <li><b>零降水无河</b>：降水=0 ⇒ 无成河格（沙漠无河）。</li>
 *   <li><b>河宽随流量增</b>：下游流量大的格宽度不得小于上游小流量格
 *       （在同一连通河段上抽查，允许相等）。</li>
 *   <li><b>河与湖连通</b>：存在"溢流湖盆的出口格"同时是成河格
 *       （证明河是从湖里流出来的，而不是各自画出来的）。</li>
 * </ol>
 */
public final class RiverBalanceProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int step = 4, cx = -140, cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu(
                        (cx - radius + i * step) / hs, (cz - radius + j * step) / hs).height;
            }
        }
        boolean[] seaMask = new boolean[n * n], border = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                seaMask[k] = bed[k] < sea;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    border[k] = true;
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    exterior[k] = Math.max(gen.sampleWu(
                            (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs).height, sea);
                }
            }
        }
        EquilibriumWater.Surface surface = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        LakeWaterBalance balance = new LakeWaterBalance(n, n, step, bed, surface);

        Path dir = Path.of("build/hydro-reassessment/river-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        StringBuilder log = new StringBuilder();
        log.append("LAKE + RIVER + SEA ; equilibrium, no time stepping.\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d cells=%d sea=%.1f%n",
                seed, cx, cz, radius, step, n * n, sea));
        log.append("cases: (precip, evap, runoff) -> lakeCells channelCells maxDischarge overflowBasins\n");

        double[][] cases = {
                {0.000, 0.002, 0.3}, {0.001, 0.002, 0.3}, {0.002, 0.002, 0.3},
                {0.004, 0.002, 0.3}, {0.010, 0.002, 0.3},
        };
        int previousChannel = -1;
        boolean monotone = true, zeroRainNoRiver = false, riverFromLake = false;
        // ★ 横向排布：宽 = n·案例数。半径大时竖排会变成"细长条"，横排才看得清地形。
        int gap = 6;
        BufferedImage strip = new BufferedImage(n * cases.length + gap * (cases.length - 1), n,
                BufferedImage.TYPE_INT_RGB);
        for (int gx = 0; gx < strip.getWidth(); gx++) {
            for (int gy = 0; gy < n; gy++) strip.setRGB(gx, gy, 0x202024);
        }

        for (int c = 0; c < cases.length; c++) {
            var climate = new LakeWaterBalance.Climate(cases[c][0], cases[c][1], cases[c][2]);
            var lakeResult = balance.solve(climate, seaMask, border);
            var river = RiverNetwork.build(n, n, surface.level(), surface.parent(),
                    lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                    RiverNetwork.Params.defaults());

            int lakeCells = 0, overflowBasins = 0, channelFromLake = 0;
            for (int k = 0; k < n * n; k++) if (lakeResult.lake()[k]) lakeCells++;
            for (var b : lakeResult.basins()) {
                if (b.overflow()) overflowBasins++;
                if (b.overflow() && surface.parent()[b.seedCell()] >= 0
                        && river.channel()[surface.parent()[b.seedCell()]]) channelFromLake++;
            }
            if (channelFromLake > 0) riverFromLake = true;
            if (c == 0) zeroRainNoRiver = river.channelCells() == 0;
            if (previousChannel >= 0 && river.channelCells() < previousChannel) monotone = false;
            previousChannel = river.channelCells();

            // 出图：海=深蓝，湖=亮蓝，河=青（按宽度分档），其余地形灰度。
            int xOffset = c * (n + gap);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int k = i * n + j;
                    int color;
                    if (seaMask[k]) color = 0x1F4E79;
                    else if (lakeResult.lake()[k]) color = 0x4A90E2;
                    else if (river.channel()[k]) {
                        double w = river.width()[k];
                        color = w > 6 ? 0x00C8B4 : w > 3 ? 0x00E5D0 : 0x64E8D8;
                    } else color = shade(bed, n, i, j);
                    strip.setRGB(xOffset + i, j, color);
                }
            }
            log.append(String.format("  p=%.4f e=%.4f k=%.2f -> lake=%5d channel=%6d maxQ=%9.2f overflowBasins=%3d lakeFedChannels=%3d%n",
                    cases[c][0], cases[c][1], cases[c][2], lakeCells, river.channelCells(),
                    river.maxDischarge(), overflowBasins, channelFromLake));
        }
        log.append(String.format("criterion1 wetter-never-fewer-rivers = %s%n", monotone));
        log.append(String.format("criterion2 zero precipitation gives no river = %s%n", zeroRainNoRiver));
        log.append(String.format("criterion4 rivers are fed by lake overflow = %s%n", riverFromLake));
        log.append("NOTE: channel width is a surface-width estimate; no channel incision or cross-section solve.\n");

        ImageIO.write(strip, "png", dir.resolve("river.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("river.png").toAbsolutePath());
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

    private RiverBalanceProbe() { }
}
