package com.geogenesis.worldgen.hydrology.model;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 真实地形上的【干地起报降雨蓄水】阶段图。
 *
 * <p><b>与之前实验的关键区别</b>：之前是"先把盆地预置到鞍口以上再让它流动"——
 * 那是假蓄水（用户已明确指出）。本探针从<b>全干</b>开始，只给降水，
 * 水必须自己汇集进洼地、抬升水面、再溢过鞍口。</p>
 *
 * <p><b>为什么现在才做</b>：{@code AnalyticFlowProbe}/{@code SteadyAchievabilityProbe} 已证明
 * 旧实验的时步过大（隐式阻尼把通量压掉）⇒ 当时的水位与溢流结论不可用。
 * 本探针按时步收敛准则取 dt=0.002·dx²（阻尼≈1）。</p>
 *
 * <p><b>必须声明的边界（勿当成世界解）</b>：
 * ① 窗口外边界为 CLOSED ⇒ 本该流出窗口的水被截留，窗口越大影响越小，但**不是**零；
 * ② 降水源项被放大以便在可接受时间内看到蓄水，故**只证明机理与几何，不证明真实水量**；
 * ③ 无入渗/蒸发；④ 鞍口由 {@link BasinStorage#lowestBoundaryFaces()} 在窗口内判定。</p>
 */
public final class RealLakeRainProbe {

    public static void main(String[] args) throws Exception {
        long seed = 9139912035078620160L;
        int radius = 320, step = 4, n = 2 * radius / step + 1;
        TerrainParamsLike p = terrain();
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            if (i % 40 == 0) System.out.printf("sample %d/%d%n", i, n);
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = p.height(-140 - radius + i * step, 137 - radius + j * step);
            }
        }
        double sea = p.sea();
        // ★ 边界按【真实外部地形】分类，而不是"四面墙"：
        //   对一个边框格，向外采样一格窗口外地形 hOut：
        //     · hOut 更低 ⇒ 真实排水口（水可离开并能感受到外部水位）
        //     · hOut 更高 ⇒ 缺口：外部地形更高，本窗口收不到那边的来水
        //   ⇒ 后者必须单独计数并声明，因为它是"缺来水"，不是"困住水"。
        //   同时把「低于海平面」的格也声明为排水口（海面为外部水位）。
        boolean[] drain = new boolean[n * n];
        double[] exterior = new double[n * n];
        int seaCells = 0, downhillEdges = 0, uphillEdges = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (bed[k] < sea) { drain[k] = true; exterior[k] = sea; seaCells++; continue; }
                if (!isFrame(i, j, n)) continue;
                double lowestOut = Double.POSITIVE_INFINITY;
                for (int[] d : dirs) {
                    int oi = i + d[0], oj = j + d[1];
                    if (oi >= 0 && oi < n && oj >= 0 && oj < n) continue;
                    lowestOut = Math.min(lowestOut, p.height(
                            -140 - radius + oi * step, 137 - radius + oj * step));
                }
                if (lowestOut < bed[k]) {
                    // 向外更低：真实出口，外部水位取窗外一格地形高。
                    drain[k] = true; exterior[k] = lowestOut; downhillEdges++;
                } else {
                    uphillEdges++;   // 缺口：外部更高，缺来水（不是困水）
                }
            }
        }
        if (seaCells == 0 && downhillEdges == 0) {
            throw new IllegalStateException("window has no drainage at all");
        }
        WaterGrid g = WaterGrid.draining(n, n, step, bed, drain, exterior);

        // ★ 降雨必须取【物理量级】：漫流水深 h≈(n·R·x/√S)^0.6。
        //   之前用 R=0.02（×260 时间 = 5.2 块降水量）远超排水能力，结果是"整个窗口
        //   100% 淹没、水深 46 块"——那是实验设定错，不是模型缺陷。
        //   取 R=0.002 ⇒ 窗口尺度漫流仅约 0.5 块（薄层），只有洼地才会积成可辨水深处。
        double rain = 0.002, manning = 0.03, dx = step;
        double dt = 0.002 * dx * dx;   // 阻尼≈1（见 AnalyticFlowProbe 时步收敛依据）
        double duration = 400;
        ModelParams params = new ModelParams(dx, manning, rain, 0, 0, 0, dt, 1e-7, 20_000_000);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, params);

        Path dir = Path.of("build/hydro-reassessment/real-rain-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        StringBuilder log = new StringBuilder();
        log.append("dry start; rain=").append(rain)
                .append(" (physical scale, not exaggerated)")
                .append("; DRAIN_MASK by real exterior terrain; dt=")
                .append(dt).append("; window=").append(2 * radius).append(" blocks @ ").append(step)
                .append(" blocks/cell; seaCells=").append(seaCells)
                .append(" downhillEdges=").append(downhillEdges)
                .append(" uphillEdges(missing inflow, NOT ponding)=").append(uphillEdges).append('\n');
        log.append("sea=").append(sea).append('\n');

        double[] marks = {0, duration / 3, duration};
        BufferedImage strip = new BufferedImage(n * 3 * 2, n * 2 + 34, BufferedImage.TYPE_INT_RGB);
        int panel = 0;
        for (double target : marks) {
            int stepCount = 0;
            while (m.time() < target - 1e-9) { m.step(); stepCount++; }
            // 分开统计：薄层漫流（几乎处处都有）与真正的积水（可辨识水深）。
            double wet = 0, ponded = 0, deep = 0, maxDepth = 0;
            for (int k = 0; k < g.cells(); k++) {
                double depth = m.depth(k / n, k % n);
                if (depth > 0.01) wet++;
                if (depth > 0.5) ponded++;
                if (depth > 2.0) deep++;
                maxDepth = Math.max(maxDepth, depth);
            }
            render(strip, panel, g, m, sea);
            log.append(String.format("t=%6.1f steps=%6d storage=%9.3f outflow=%9.3f"
                            + " wet=%6d(%.1f%%) ponded=%5d(%.1f%%) deep=%4d(%.2f%%) maxDepth=%.3f%n",
                    m.time(), stepCount, m.storage(), m.boundaryLossVolume(),
                    (int) wet, 100.0 * wet / g.cells(), (int) ponded, 100.0 * ponded / g.cells(),
                    (int) deep, 100.0 * deep / g.cells(), maxDepth));
            System.out.printf("panel %d done (t=%.1f, steps=%d)%n", panel, m.time(), stepCount);
            panel++;
        }
        double inflow = rain * g.cells() * g.cellArea() * m.time();
        double ledger = inflow - m.boundaryLossVolume() - m.storage();
        log.append(String.format("ledger: inflow=%.4f outflow=%.4f storage=%.4f residual=%.3e (rel %.2e)%n",
                inflow, m.boundaryLossVolume(), m.storage(), ledger, Math.abs(ledger) / Math.max(1e-12, inflow)));
        boolean converged = m.converged();
        log.append("converged=").append(converged).append(" (false = 仍在充水，属预期)\n");

        ImageIO.write(strip, "png", dir.resolve("stages.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("stages.png").toAbsolutePath());
    }

    /** 只保留本探针需要的两种地形读取，避免依赖主源码集。 */
    private record TerrainParamsLike(double seaLevel) {
        double height(double x, double z) { return Holder.GEN.erodedHeightForRouting(x / Holder.HS, z / Holder.HS); }
        double sea() { return seaLevel; }
    }

    private static final class Holder {
        static final double HS = 2.0;
        static final com.geogenesis.worldgen.terrain.CellGenerator GEN = create();
        private static com.geogenesis.worldgen.terrain.CellGenerator create() {
            var tp = com.geogenesis.worldgen.terrain.TerrainParams.defaults();
            var gen = new com.geogenesis.worldgen.terrain.CellGenerator(tp, tp.minY(), tp.maxY());
            gen.seed(9139912035078620160L);
            return gen;
        }
    }

    private static TerrainParamsLike terrain() {
        var tp = com.geogenesis.worldgen.terrain.TerrainParams.defaults();
        return new TerrainParamsLike(new com.geogenesis.worldgen.terrain.CellGenerator(tp, tp.minY(), tp.maxY()).seaLevel());
    }

    private static boolean isFrame(int i, int j, int n) {
        return i == 0 || j == 0 || i == n - 1 || j == n - 1;
    }

    private static void render(BufferedImage out, int panel, WaterGrid g, DiffusiveWaveModel m, double sea) {
        var gr = out.createGraphics();
        int ox = (panel % 3) * g.nx * 2, oy = (panel / 3) * (g.nz * 2 + 17) + 17;
        gr.setColor(Color.BLACK);
        gr.fillRect(ox, oy, g.nx * 2, g.nz * 2);
        for (int i = 0; i < g.nx; i++) {
            for (int j = 0; j < g.nz; j++) {
                int k = g.idx(i, j);
                double d = m.depth(i, j);
                int color;
                if (d > 0.01) {
                    // 深蓝=深水，浅青=薄水
                    color = d > 2 ? 0x123A80 : d > 0.5 ? 0x1F6FD0 : 0x39B7E0;
                } else if (g.bed[k] < sea) {
                    color = 0x1F4E79;
                } else {
                    color = shade(g, i, j);
                }
                gr.setColor(new Color(color));
                gr.fillRect(ox + i * 2, oy + j * 2, 2, 2);
            }
        }
        // 窗口边界：提醒这是裁剪而非世界边界
        gr.setColor(new Color(0xD0, 0x60, 0x30));
        gr.drawRect(ox, oy, g.nx * 2 - 1, g.nz * 2 - 1);
        gr.setColor(Color.WHITE);
        gr.drawString(String.format("t=%.0f  wet=%.1f%%  (orange frame = CLOSED crop, NOT world edge)",
                m.time(), 100.0 * wetFraction(m, g)), ox + 5, oy - 5);
        gr.dispose();
    }

    private static double wetFraction(DiffusiveWaveModel m, WaterGrid g) {
        int wet = 0;
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) if (m.depth(i, j) > 0.01) wet++;
        return wet / (double) g.cells();
    }

    private static int shade(WaterGrid g, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(g.nx - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(g.nz - 1, j + 1);
        double dxi = (g.bed[g.idx(i1, j)] - g.bed[g.idx(i0, j)]) / ((i1 - i0) * g.cellSize);
        double dzj = (g.bed[g.idx(i, j1)] - g.bed[g.idx(i, j0)]) / ((j1 - j0) * g.cellSize);
        double light = (0.5 * dxi + 0.7 - 0.51 * dzj) / Math.sqrt((dxi * dxi + 1 + dzj * dzj) * 1.0001);
        int gray = (int) (60 + 175 * Math.max(0, light));
        return (gray << 16) | (gray << 8) | gray;
    }

    private RealLakeRainProbe() { }
}
