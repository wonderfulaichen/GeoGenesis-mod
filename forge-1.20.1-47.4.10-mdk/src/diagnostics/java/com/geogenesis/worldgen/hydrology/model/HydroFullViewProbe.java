package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 大图规格的湖—河—海出图：<b>1 像素 = 1 方块</b>，与项目既有预览（{@code preview.png}）同口径。
 *
 * <h2>为什么要单独写</h2>
 * <p>此前模型探针默认 {@code step=4}（4 方块/像素），导致：</p>
 * <ul>
 *   <li>视野只有旧图的 1/16，画面放大后发糊；</li>
 *   <li>河被画成 4px 粗块，视觉上像"凸起"，与旧图 1px 细线（嵌在谷中）完全不同。</li>
 * </ul>
 * <p>本探针回到 {@code step=1}，并用<b>1px 细线</b>画河道。</p>
 *
 * <h2>山体阴影口径</h2>
 * <p>与 {@code HydroPreviewProbe.shade} 完全一致：NW 光源、法线 {@code (-dx,1,-dz)}、
 * {@code 60+175·max(0,d)}。公式经逐项核对相同 —— 观感差异来自分辨率而非光照方向。</p>
 *
 * <h2>边界</h2>
 * <p>诊断出图，不落块、不接生产。未决格以品红标出；品红=水位由窗口切割决定，不可信。</p>
 */
public final class HydroFullViewProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 500;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;
        int step = 1;                       // ★ 1 像素 = 1 方块（与既有预览同口径）

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        long t0 = System.nanoTime();
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, step, climate));
        int n = r.nx();

        Path dir = Path.of("build/hydro-reassessment/full-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int lake = 0, channel = 0, sea = 0, unresolved = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (r.sea()[k]) { color = 0x1F4E79; sea++; }
                else if (r.lake()[k]) { color = 0x4A90E2; lake++; }
                else if (r.channel()[k]) {
                    // ★ 1px 细线：与既有预览同款，不再画成粗块（粗块会显得"凸起"）。
                    channel++;
                    double w = r.width()[k];
                    color = w > 6 ? 0x00C8B4 : w > 3 ? 0x00E5D0 : 0x64E8D8;
                }
                else if (!r.resolved()[k]) { color = 0xC030C0; unresolved++; }
                else color = shade(r.bed(), n, i, j);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve("full.png").toFile());

        StringBuilder log = new StringBuilder();
        log.append("1 pixel = 1 block; same shading formula as HydroPreviewProbe.shade\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d image=%dx%d precip=%.4f%n",
                seed, cx, cz, radius, step, n, n, precip));
        log.append(String.format("cells=%d sea=%d (%.1f%%) lake=%d (%.2f%%) channel=%d unresolved=%d (%.2f%%)%n",
                n * n, sea, 100.0 * sea / (n * (double) n), lake, 100.0 * lake / (n * (double) n),
                channel, unresolved, 100.0 * unresolved / (n * (double) n)));
        log.append(String.format("elapsed=%.1fs%n", (System.nanoTime() - t0) / 1e9));
        log.append("NOT VERIFIED: block placement, canyon incision, in-game render.\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("full.png").toAbsolutePath());
    }

    /** 与 HydroPreviewProbe.shade 逐项相同的 NW 山体阴影。 */
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

    private HydroFullViewProbe() { }
}
