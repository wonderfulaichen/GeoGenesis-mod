package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 边界缓冲的 A/B 对比：缓冲前 vs 缓冲后。
 *
 * <p><b>目的</b>：河网流量沿排水树累加 ⇒ 窗口边界截断外部上游 ⇒ 边界带判定不可靠。
 * 参考实现（FreeTerraForged /PL-RGA）都在边界带不放河。本探针验证该做法是否：
 * ① 消除边界带的不稳定河格；② <b>是否也消除了平原中部的平行直纹</b>
 * （后者是未决问题，事先不假定能解决）。</p>
 *
 * <p>同时按坡度分档统计河格数：若直纹属"均匀坡面必然结果"，则缓冲<b>不应</b>
 * 显著改变缓坡档的河格数。</p>
 */
public final class BufferCompareProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int step = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        int radius = args.length > 4 ? Integer.parseInt(args[4]) : 512;
        double precip = args.length > 5 ? Double.parseDouble(args[5]) : 0.004;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        int[] buffers = {0, 24, 64};
        Path dir = Path.of("build/hydro-reassessment/buffer-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        StringBuilder log = new StringBuilder();
        log.append("border buffer A/B; flat-band channel count is the key metric\n");
        log.append(String.format("seed=%d centre=(%d,%d) step=%d radius=%d precip=%.4f%n",
                seed, cx, cz, step, radius, precip));

        for (int buffer : buffers) {
            var r = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(cx, cz, radius, step, climate, buffer));
            int n = r.nx();
            // 坡度（block/block）
            int[] bandTotal = new int[4], bandChannel = new int[4];
            String[] bandName = {"slope<0.05", "0.05-0.15", "0.15-0.35", ">=0.35"};
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int k = i * n + j;
                    if (r.sea()[k] || r.lake()[k] || !r.resolved()[k]) continue;
                    int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                    int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
                    double dx = (r.bed()[i1 * n + j] - r.bed()[i0 * n + j]) / ((i1 - i0) * (double) step);
                    double dz = (r.bed()[i * n + j1] - r.bed()[i * n + j0]) / ((j1 - j0) * (double) step);
                    double s = Math.hypot(dx, dz);
                    int b = s < 0.05 ? 0 : s < 0.15 ? 1 : s < 0.35 ? 2 : 3;
                    bandTotal[b]++;
                    if (r.channel()[k]) bandChannel[b]++;
                }
            }
            log.append(String.format("buffer=%3d cells  channel=%7d suppressed=%6d%n",
                    buffer, r.riverResult().channelCells(), r.riverResult().suppressedByBorder()));
            for (int b = 0; b < 4; b++) {
                log.append(String.format("    %-11s landCells=%7d channel=%6d (%5.2f%%)%n",
                        bandName[b], bandTotal[b], bandChannel[b],
                        100.0 * bandChannel[b] / Math.max(1, bandTotal[b])));
            }
            System.out.printf("buffer=%d done%n", buffer);

            if (buffer == buffers[buffers.length - 1]) {
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
                ImageIO.write(img, "png", dir.resolve("buffered.png").toFile());
                log.append("IMAGE=").append(dir.resolve("buffered.png").toAbsolutePath()).append('\n');
            }
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

    private BufferCompareProbe() { }
}
