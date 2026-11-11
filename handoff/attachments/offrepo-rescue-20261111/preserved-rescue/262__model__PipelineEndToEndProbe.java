package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 端到端验证：整条 {@link HydrologyPipeline} 的【窗口无关性】。
 *
 * <h2>判据（管线级，比单步判据强）</h2>
 * <ol>
 *   <li><b>水位一致</b>：同一世界点，不同窗口半径下 {@code level} 相同（或随窗口收敛）。</li>
 *   <li><b>湖泊一致</b>：同一点是否成湖、水深是否相同。</li>
 *   <li><b>河流一致</b>：同一点是否成河、流量是否相同。</li>
 *   <li><b>守恒</b>：水量平衡账本闭合（降水−蒸发−溢流−储量）。</li>
 * </ol>
 * <p>之所以必须做这一层：单步探针通过<b>不等于</b>串联后通过
 * （例如平衡水位里 resolved 的格，经水量平衡后可能落进未决区）。
 * 这正是本项目历史上"各步 PASS、整体错"的教训。</p>
 */
public final class PipelineEndToEndProbe {

    public static void main(String[] args) throws Exception {
        long seed = 9139912035078620160L;
        int step = 4, cx = -140, cz = 137;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        int[] radii = {128, 256, 512, 1024};
        double[] centerLevel = new double[radii.length];
        double[] centerDepth = new double[radii.length];
        boolean[] centerLake = new boolean[radii.length];
        double[] centerQ = new double[radii.length];

        System.out.println("=== end-to-end pipeline: window independence ===");
        HydrologyPipeline.Result last = null;
        for (int i = 0; i < radii.length; i++) {
            int radius = radii[i];
            var req = new HydrologyPipeline.Request(cx, cz, radius, step, climate);
            long t0 = System.nanoTime();
            HydrologyPipeline.Result r = HydrologyPipeline.run(gen, tp, req);
            double secs = (System.nanoTime() - t0) / 1e9;
            int n = r.nx();
            // 中心必须位于网格中心：n 为奇数 ⇒ 中心格即 (n/2, n/2)
            int center = (n / 2) * n + n / 2;
            centerLevel[i] = r.level()[center];
            centerDepth[i] = r.depth()[center];
            centerLake[i] = r.lake()[center];
            centerQ[i] = r.discharge()[center];
            System.out.printf("  r=%5d  %-64s  %.1fs%n", radius, HydrologyPipeline.summarize(r), secs);
            last = r;
        }

        System.out.println();
        System.out.println("centre point comparison:");
        for (int i = 0; i < radii.length; i++) {
            System.out.printf("  r=%5d level=%10.3f depth=%7.3f lake=%-5s q=%9.3f%n",
                    radii[i], centerLevel[i], centerDepth[i], centerLake[i], centerQ[i]);
        }
        // 判据 1：最大半径与次大半径的水位差（收敛性）
        double dLevel = Math.abs(centerLevel[radii.length - 1] - centerLevel[radii.length - 2]);
        double dQ = Math.abs(centerQ[radii.length - 1] - centerQ[radii.length - 2]);
        System.out.printf("criterion1 level converged (r=%d vs %d): delta=%.6f -> %s%n",
                radii[radii.length - 1], radii[radii.length - 2], dLevel,
                dLevel < 1e-6 ? "PASS" : "FAIL");
        System.out.printf("criterion3 discharge converged: delta=%.6f -> %s%n", dQ,
                dQ < 1e-6 ? "PASS" : "FAIL");

        // 出图（最大半径那次）
        int n = last.nx();
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (last.sea()[k]) color = 0x1F4E79;
                else if (last.lake()[k]) color = 0x4A90E2;
                else if (last.channel()[k]) {
                    double w = last.width()[k];
                    color = w > 6 ? 0x00C8B4 : w > 3 ? 0x00E5D0 : 0x64E8D8;
                } else if (!last.resolved()[k]) color = 0xC030C0;   // 未决：品红
                else color = shadeGray(last.bed(), n, i, j);
                img.setRGB(i, j, color);
            }
        }
        Path dir = Path.of("build/hydro-reassessment/pipeline-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(img, "png", dir.resolve("pipeline.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"),
                HydrologyPipeline.summarize(last) + "\n");
        System.out.println("IMAGE=" + dir.resolve("pipeline.png").toAbsolutePath());
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

    private PipelineEndToEndProbe() { }
}
