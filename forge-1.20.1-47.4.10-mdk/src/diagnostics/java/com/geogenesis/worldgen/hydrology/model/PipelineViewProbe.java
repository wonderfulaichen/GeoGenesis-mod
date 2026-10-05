package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 管线出图（可选窗口中心与半径），并对【未决区】做空间量化。
 *
 * <h2>为什么要单独出图探针</h2>
 * <p>{@link PipelineEndToEndProbe} 用的是默认中心 (-140,137)，那里一半是海，
 * 不利于观察湖泊与河网。本探针允许指定中心与半径，并对未决区做统计：
 * <b>未决是否成片、是否随窗口放大而收缩</b>——这决定"未决"是真实的远出口依赖，
 * 还是判据阈值造成的假象。</p>
 */
public final class PipelineViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : 900;
        int step = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        Path dir = Path.of("build/hydro-reassessment/view-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        StringBuilder log = new StringBuilder();
        log.append("pipeline view; magenta = window-dependent (unresolved)\n");
        log.append(String.format("seed=%d centre=(%d,%d) step=%d precip=%.4f%n", seed, cx, cz, step, precip));

        // 放大窗口，看未决是否收缩（这是判定未决性质的关键）
        int[] radii = {256, 512, 1024};
        for (int radius : radii) {
            var r = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(cx, cz, radius, step, climate));
            int n = r.nx();
            int lake = 0, channel = 0, resolved = 0, seaCells = 0;
            for (int k = 0; k < n * n; k++) {
                if (r.lake()[k]) lake++;
                if (r.channel()[k]) channel++;
                if (r.resolved()[k]) resolved++;
                if (r.sea()[k]) seaCells++;
            }
            // 未决的"内部比例"：排除边界两圈后，内部还剩多少未决
            int band = 2;
            int innerTotal = 0, innerUnresolved = 0;
            for (int i = band; i < n - band; i++) {
                for (int j = band; j < n - band; j++) {
                    innerTotal++;
                    if (!r.resolved()[i * n + j]) innerUnresolved++;
                }
            }
            log.append(String.format("r=%5d cells=%8d sea=%.1f%% lake=%6d channel=%7d resolved=%.1f%%"
                            + "  innerUnresolved=%d/%d (%.2f%%)%n",
                    radius, n * n, 100.0 * seaCells / (n * (double) n), lake, channel,
                    100.0 * resolved / (n * (double) n), innerUnresolved, innerTotal,
                    100.0 * innerUnresolved / Math.max(1, innerTotal)));

            if (radius == radii[radii.length - 1]) {
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        int k = i * n + j;
                        int color;
                        if (r.sea()[k]) color = 0x1F4E79;
                        else if (r.lake()[k]) color = 0x4A90E2;
                        else if (r.channel()[k]) {
                            double w = r.width()[k];
                            color = w > 6 ? 0x00C8B4 : w > 3 ? 0x00E5D0 : 0x64E8D8;
                        } else if (!r.resolved()[k]) color = 0xC030C0;
                        else color = shadeGray(r.bed(), n, i, j);
                        img.setRGB(i, j, color);
                    }
                }
                ImageIO.write(img, "png", dir.resolve("view.png").toFile());
                log.append("IMAGE=").append(dir.resolve("view.png").toAbsolutePath()).append('\n');
            }
            System.out.printf("r=%d done%n", radius);
        }
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
    }

    private static int shadeGray(double[] bed, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (bed[i1 * n + j] - bed[i0 * n + j]) / (i1 - i0);
        double dz = (bed[i * n + j1] - bed[i * n + j0]) / (j1 - j0);
        double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * 1.0001);
        int g = (int) (60 + 175 * Math.max(0, light));
        return (g << 16) | (g << 8) | g;
    }

    private PipelineViewProbe() { }
}
