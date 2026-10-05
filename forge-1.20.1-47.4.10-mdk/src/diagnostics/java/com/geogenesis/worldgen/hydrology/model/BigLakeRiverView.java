package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 大图规格的湖—河—海出图（1 像素 = 1 方块），并量化"平地流向"在真实地形上的占比。
 *
 * <p><b>为什么单独写这个</b>：此前的模型探针用的是 step=4（4 方块/像素），
 * 世界范围相近但分辨率只有旧预览的 1/4，看起来"面积变小了"。
 * 本探针回到旧规格（{@code step=1}、{@code radius=700}），便于与既有图同尺度比对。</p>
 *
 * <p><b>同时回答一个未决问题</b>：{@link IsotropyProbe} 在<b>合成平地</b>上证明
 * 四邻域规则会把流向锁到轴向（对角方位计数为 0）。但真实地形几乎处处有起伏，
 * 关键未知量是：<b>真实地形上有多大比例的流向是被"水位相同"的平局规则决定的？</b>
 * 若占比很低，伪影影响有限；若占比可观，则必须在接生产前改掉。</p>
 */
public final class BigLakeRiverView {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 700;
        int step = 1;                       // ★ 1 像素 = 1 方块（旧规格）
        int cx = -140, cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (i % 128 == 0) System.out.printf("  sampling %d/%d (%.0fs)%n", i, n, (System.nanoTime() - t0) / 1e9);
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu(
                        (cx - radius + i * step) / hs, (cz - radius + j * step) / hs).height;
            }
        }
        System.out.printf("  sampled %d cells in %.1fs%n", n * n, (System.nanoTime() - t0) / 1e9);

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
        long t1 = System.nanoTime();
        EquilibriumWater.Surface surface = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        System.out.printf("  equilibrium done in %.1fs%n", (System.nanoTime() - t1) / 1e9);

        // ★ 量化"平地流向"占比：父格水位与自身相等（差 < 1e-9）者为平局决定。
        int[] parent = surface.parent();
        int tieFlow = 0, gradientFlow = 0, terminal = 0, unresolvedFlow = 0;
        for (int k = 0; k < n * n; k++) {
            int p = parent[k];
            if (p < 0) { terminal++; continue; }
            if (!surface.resolved()[k] || !surface.resolved()[p]) { unresolvedFlow++; continue; }
            if (Math.abs(surface.level()[p] - surface.level()[k]) < 1e-9) tieFlow++;
            else gradientFlow++;
        }
        int totalFlow = tieFlow + gradientFlow;

        LakeWaterBalance balance = new LakeWaterBalance(n, n, step, bed, surface);
        double[] cases = {0.001, 0.004};
        Path dir = Path.of("build/hydro-reassessment/big-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        StringBuilder log = new StringBuilder();
        log.append("BIG VIEW: 1 pixel = 1 block (旧规格).\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d image=%dx%d sea=%.1f%n",
                seed, cx, cz, radius, step, n, n, sea));
        log.append(String.format("flow classification: gradient-driven=%d (%.2f%%)  TIE-driven(flat)=%d (%.2f%%)"
                        + "  terminal=%d  unresolved=%d%n",
                gradientFlow, 100.0 * gradientFlow / Math.max(1, totalFlow),
                tieFlow, 100.0 * tieFlow / Math.max(1, totalFlow), terminal, unresolvedFlow));
        log.append("  ^ tie-driven flow is where the grid-axis artifact can appear.\n");

        for (double precip : cases) {
            var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);
            var lakeResult = balance.solve(climate, seaMask, border);
            var river = RiverNetwork.build(n, n, surface.level(), parent,
                    lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                    RiverNetwork.Params.defaults());
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
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
                    img.setRGB(i, j, color);
                }
            }
            String name = String.format("big_p%.4f.png", precip);
            ImageIO.write(img, "png", dir.resolve(name).toFile());
            log.append(String.format("  p=%.4f -> lake=%d channel=%d maxQ=%.2f  %s%n",
                    precip, count(lakeResult.lake()), river.channelCells(), river.maxDischarge(), name));
            System.out.printf("  wrote %s%n", name);
        }
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE_DIR=" + dir.toAbsolutePath());
    }

    private static int count(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
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

    private BigLakeRiverView() { }
}
