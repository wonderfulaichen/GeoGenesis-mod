package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>洋中脊「点状折痕」梯度尖峰猎手（2026-11-18）</b>。
 *
 * <p>两种模式：</p>
 * <ol>
 *   <li><b>网格模式</b>（默认）：窗口全采样，找坡度尖峰（|∇e| 远超中位数），
 *       分解各分量（年龄律/脊/海山/弧）并渲染标记图；</li>
 *   <li><b>迹线模式</b>（args = cx cz "trace" x1 z1 x2 z2）：沿用户圈的
 *       虚线迹线逐 wu 采样全分量，看折痕处的数值结构。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runRidgeDotProbe} 或
 * {@code -PprobeArgs="<cx> <cz> trace <x1> <z1> <x2> <z2>"}。</p>
 */
public final class RidgeDotProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        // trace 模式必须在数字解析前拦截（args[3]="trace" 会被 parseDouble 拒绝）
        if (args.length >= 8 && args[3].equals("trace")) {
            traceMode(args);
            return;
        }
        gridMode(args);
    }

    // ============================================================ 网格模式

    private static void gridMode(String[] args) throws Exception {
        double cx = args.length > 0 ? Double.parseDouble(args[0]) : 100;
        double cz = args.length > 1 ? Double.parseDouble(args[1]) : 1750;
        double half = args.length > 2 ? Double.parseDouble(args[2]) : 500;
        double step = args.length > 3 ? Double.parseDouble(args[3]) : 2;

        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);
        TectonicField tectonic = new TectonicField(SEED);

        int n = (int) (2 * half / step) + 1;
        double[] e = new double[n * n];
        double eMin = 9, eMax = -9;
        for (int j = 0; j < n; j++) {
            double wz = cz - half + 2 * half * j / (n - 1);
            for (int i = 0; i < n; i++) {
                double wx = cx - half + 2 * half * i / (n - 1);
                double v = smp.sampleE(wx, wz);
                e[j * n + i] = v;
                eMin = Math.min(eMin, v);
                eMax = Math.max(eMax, v);
            }
        }
        System.out.printf(Locale.ROOT, "[grid] %dx%d step %.0fwu window +-%.0fwu  e [%.3f, %.3f]%n",
                n, n, step, half, eMin, eMax);

        // ---- 坡度（块/块）----
        int nSlope = n - 1;
        double[] g = new double[nSlope * nSlope];
        for (int j = 0; j < nSlope; j++) {
            for (int i = 0; i < nSlope; i++) {
                double gx = (e[j * n + i + 1] - e[j * n + i]) / step * 127;
                double gy = (e[(j + 1) * n + i] - e[j * n + i]) / step * 127;
                g[j * nSlope + i] = Math.hypot(gx, gy);
            }
        }
        double gMed = percentile(g, nSlope, 0.5);
        double p95 = percentile(g, nSlope, 0.95);
        double p99 = percentile(g, nSlope, 0.99);
        System.out.printf(Locale.ROOT, "[slope] median %.3f  p95 %.3f  p99 %.3f (blocks/block)%n",
                gMed, p95, p99);

        // ---- 找尖峰 ----
        double thresh = Math.max(p99, gMed * 6);
        List<int[]> spikes = new ArrayList<>();
        for (int j = 1; j < nSlope - 1; j++) {
            for (int i = 1; i < nSlope - 1; i++) {
                double v = g[j * nSlope + i];
                if (v > thresh && v >= g[j * nSlope + i - 1] && v >= g[j * nSlope + i + 1]
                        && v >= g[(j - 1) * nSlope + i] && v >= g[(j + 1) * nSlope + i]) {
                    spikes.add(new int[]{i, j});
                }
            }
        }
        System.out.println("[spikes] " + spikes.size() + " (threshold " + thresh + ")");

        System.out.println("  x       z      |grad|   e      age     ridge   seamt   arc    distRaw  stress");
        int shown = 0;
        for (int[] s : spikes) {
            if (shown++ >= 24) {
                break;
            }
            double wx = cx - half + (s[0] + 0.5) * step;
            double wz = cz - half + (s[1] + 0.5) * step;
            var c = smp.sampleColumn(wx, wz);
            var ts = tectonic.sample(wx, wz);
            var cs = CrustField.of(ts, wx, wz, SEED);
            var of = c.oceanFeat;
            System.out.printf(Locale.ROOT,
                    "%7.0f %7.0f  %6.3f  %6.3f  %6.3f  %6.3f  %5.3f  %5.3f  %7.1f  %6.3f%n",
                    wx, wz, g[s[1] * nSlope + s[0]], c.e, cs.eBase(),
                    of == null ? 0 : of.ridge, of == null ? 0 : of.seamount,
                    of == null ? 0 : of.arc, ts.distRaw(), ts.stress());
        }

        render(e, n, eMin, eMax, spikes, Path.of("build/ridge-dot/dot-map.png"));
        System.out.println("image -> " + Path.of("build/ridge-dot/dot-map.png").toAbsolutePath());

        // ---- ★ hillshade 复算（与 RidgeViewProbe 相同公式），找「点链」----
        //   已证：地形数据光滑（0 尖峰）。若点链只出现在 hillshade 里 ⇒ 渲染伪影。
        double cellWu = 2 * half / (n - 1);
        double blocksPerCell = cellWu * 2.0;
        double[] lum = new double[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
                double gx = (e[j * n + i1] - e[j * n + i0]) * 127.0 / blocksPerCell
                        * 12.0 / Math.max(1, i1 - i0);
                double gy = (e[j1 * n + i] - e[j0 * n + i]) * 127.0 / blocksPerCell
                        * 12.0 / Math.max(1, j1 - j0);
                double nx = -gx, ny = -gy, nz = 1;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double dd = (-nx * 0.6 - ny * 0.6 + nz * 0.53) / l;
                lum[j * n + i] = 0.45 + 0.9 * Math.max(0, Math.min(1, dd));
            }
        }
        // 暗点：亮度低于周围（半径 3px 环均值）15%
        List<int[]> shadeDots = new ArrayList<>();
        for (int j = 3; j < n - 3; j++) {
            for (int i = 3; i < n - 3; i++) {
                double v = lum[j * n + i];
                double ring = 0;
                int cnt = 0;
                for (int dj = -3; dj <= 3; dj += 2) {
                    for (int di = -3; di <= 3; di += 2) {
                        if (di == 0 && dj == 0) {
                            continue;
                        }
                        ring += lum[(j + dj) * n + (i + di)];
                        cnt++;
                    }
                }
                ring /= cnt;
                if (v < ring - 0.12 * ring) {
                    shadeDots.add(new int[]{i, j});
                }
            }
        }
        System.out.println("[shade-dots] " + shadeDots.size() + " 处 hillshade 暗点");
        System.out.println("  x       z     shade   e      age     ridge   seamt   arc    distRaw  stress  edge");
        shown = 0;
        for (int[] s : shadeDots) {
            if (shown++ >= 20) {
                break;
            }
            double wx = cx - half + s[0] * step;
            double wz = cz - half + s[1] * step;
            var c = smp.sampleColumn(wx, wz);
            var ts = tectonic.sample(wx, wz);
            var cs = CrustField.of(ts, wx, wz, SEED);
            var of = c.oceanFeat;
            double edge = (c.continent + 1.0) * 0.5;
            System.out.printf(Locale.ROOT,
                    "%7.0f %7.0f  %5.2f  %6.3f  %6.3f  %6.3f  %5.3f  %5.3f  %7.1f  %6.3f  %6.3f%n",
                    wx, wz, lum[s[1] * n + s[0]], c.e, cs.eBase(),
                    of == null ? 0 : of.ridge, of == null ? 0 : of.seamount,
                    of == null ? 0 : of.arc, ts.distRaw(), ts.stress(), edge);
        }
        // 渲染 hillshade 图（复算）
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double t = (e[j * n + i] - eMin) / Math.max(1e-9, eMax - eMin);
                int r = (int) (24 + 200 * t * t);
                int gg = (int) (50 + 170 * t);
                int b = (int) (110 + 130 * t);
                double sh = lum[j * n + i];
                r = Math.min(255, (int) (r * sh));
                gg = Math.min(255, (int) (gg * sh));
                b = Math.min(255, (int) (b * sh));
                img.setRGB(i, j, (r << 16) | (gg << 8) | b);
            }
        }
        Graphics2D gr = img.createGraphics();
        gr.setColor(Color.RED);
        for (int[] s : shadeDots) {
            gr.fillRect(s[0], s[1], 2, 2);
        }
        gr.dispose();
        Path hs = Path.of("build/ridge-dot/hillshade-marked.png");
        ImageIO.write(img, "png", hs.toFile());
        System.out.println("image -> " + hs.toAbsolutePath());
    }

    // ============================================================ 迹线模式

    /** 沿虚线迹线采样全分量（诊断模式）。 */
    private static void traceMode(String[] args) throws Exception {
        MorphTerrainSampler smp = new MorphTerrainSampler(SEED,
                TerrainParams.defaults(), -64, 320);
        TectonicField tectonic = new TectonicField(SEED);
        double x1 = Double.parseDouble(args[4]);
        double z1 = Double.parseDouble(args[5]);
        double x2 = Double.parseDouble(args[6]);
        double z2 = Double.parseDouble(args[7]);
        System.out.println("  t      e     age   ridge  seamt   arc   distRaw stress  edge");
        for (int k = 0; k <= 96; k++) {
            double t = k / 96.0;
            double wx = x1 + (x2 - x1) * t;
            double wz = z1 + (z2 - z1) * t;
            var c = smp.sampleColumn(wx, wz);
            var ts = tectonic.sample(wx, wz);
            var cs = CrustField.of(ts, wx, wz, SEED);
            var of = c.oceanFeat;
            double edge = (c.continent + 1.0) * 0.5;
            System.out.printf(Locale.ROOT,
                    "%5.2f %6.3f %6.3f %6.3f %5.3f %5.3f %7.1f %6.3f %6.3f%n",
                    t, c.e, cs.eBase(),
                    of == null ? 0 : of.ridge, of == null ? 0 : of.seamount,
                    of == null ? 0 : of.arc, ts.distRaw(), ts.stress(), edge);
        }
    }

    // ============================================================ 渲染

    private static void render(double[] e, int n, double lo, double hi,
                               List<int[]> spikes, Path out) throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double t = (e[j * n + i] - lo) / Math.max(1e-9, hi - lo);
                int r = (int) (24 + 200 * t * t);
                int g = (int) (50 + 170 * t);
                int b = (int) (110 + 130 * t);
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        Graphics2D gr = img.createGraphics();
        gr.setColor(Color.RED);
        for (int[] s : spikes) {
            gr.fillRect(s[0], s[1], 2, 2);
        }
        gr.dispose();
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "png", out.toFile());
    }

    private static double percentile(double[] g, int n, double q) {
        double[] copy = new double[n * n];
        int k = 0;
        for (int i = 0; i < n * n && k < copy.length; i++) {
            if (Double.isFinite(g[i])) {
                copy[k++] = g[i];
            }
        }
        if (k == 0) {
            return 0;
        }
        java.util.Arrays.sort(copy, 0, k);
        return copy[(int) (k * q)];
    }

    private RidgeDotProbe() { }
}