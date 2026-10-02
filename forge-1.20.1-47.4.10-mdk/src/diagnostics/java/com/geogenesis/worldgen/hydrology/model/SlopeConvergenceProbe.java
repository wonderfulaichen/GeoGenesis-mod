package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 判定"平行流线"是【均匀坡面的必然结果】还是【地形缺横向沟壑】。
 *
 * <p><b>为什么要做这一条</b>：我此前把图上那片区域称作"沿海平原"，是<b>看错了</b>
 * ——那是<b>山坡</b>。这个区别决定了归因方向：
 * 均匀倾斜的坡面上，每格沿最陡方向下泄 ⇒ 流向天然平行，<b>这不是伪影</b>；
 * 真实山坡之所以呈树枝状，是因为有<b>横向起伏</b>使水流汇聚成沟。</p>
 *
 * <p>本探针量化两件事：</p>
 * <ol>
 *   <li><b>坡度分层</b>：把每个格按局部坡度分档，报告各档占比 —— 确认那片区域确实是陡坡。</li>
 *   <li><b>汇聚度（入度）</b>：统计有多少格流向同一格。真实沟壑网络应有大量入度 ≥2 的
 *       汇聚点；若绝大多数格入度=1，说明流向是"各走各的"平行流，即坡面缺少横向汇流结构。</li>
 * </ol>
 *
 * <p>输出图：山体阴影为底，叠加流向着色（橙=平局+轴向，暗红=平局+斜向，
 * 亮绿=梯度+轴向，暗绿=梯度+斜向），并按坡度分层输出汇聚度。</p>
 */
public final class SlopeConvergenceProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        int step = 2, cx = -140, cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        boolean[] seaMask = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                bed[k] = gen.erodedHeightForRouting((cx - radius + i * step) / hs,
                        (cz - radius + j * step) / hs);
                seaMask[k] = bed[k] < sea;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    exterior[k] = Math.max(gen.erodedHeightForRouting(
                            (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs), sea);
                }
            }
        }
        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        int[] parent = s.parent();

        // 局部坡度（中心差分，单位 block/block）
        double[] slope = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
                double dx = (bed[i1 * n + j] - bed[i0 * n + j]) / ((i1 - i0) * step);
                double dz = (bed[i * n + j1] - bed[i * n + j0]) / ((j1 - j0) * step);
                slope[i * n + j] = Math.hypot(dx, dz);
            }
        }
        // 入度：有多少格流向它
        int[] indeg = new int[n * n];
        for (int k = 0; k < n * n; k++) {
            int p = parent[k];
            if (p >= 0) indeg[p]++;
        }

        // 坡度分档
        double[] edges = {0.0, 0.02, 0.05, 0.10, 0.20, 0.40, 1.0, Double.MAX_VALUE};
        String[] names = {"<0.02 平坦", "0.02-0.05", "0.05-0.10", "0.10-0.20", "0.20-0.40", "0.40-1.0", ">1.0 陡壁"};
        int[] land = new int[edges.length];
        int cut = 3;   // 0.10 以上算陡坡（约 5.7°）
        int[] slopeBand = new int[edges.length];       // 陆地上度=1 的格数
        int[] slopeBandTotal = new int[edges.length];
        int[] slopeBandConverge = new int[edges.length]; // 入度>=2 的格数
        for (int k = 0; k < n * n; k++) {
            if (seaMask[k] || !s.resolved()[k]) continue;
            int b = 0;
            while (b < edges.length - 1 && slope[k] >= edges[b + 1]) b++;
            slopeBandTotal[b]++;
            if (indeg[k] >= 2) slopeBandConverge[b]++;
            land[b]++;
        }

        System.out.println("=== slope bands and flow convergence (land, resolved only) ===");
        for (int b = 0; b < edges.length; b++) {
            if (slopeBandTotal[b] == 0) continue;
            System.out.printf("  %-12s cells=%7d (%5.1f%%)  converging(indeg>=2)=%7d (%5.1f%%)%n",
                    names[b], slopeBandTotal[b],
                    100.0 * slopeBandTotal[b] / Math.max(1, sum(slopeBandTotal)),
                    slopeBandConverge[b],
                    100.0 * slopeBandConverge[b] / Math.max(1, slopeBandTotal[b]));
        }
        int totalLandResolved = sum(slopeBandTotal);

        // 出图：阴影 + 流向
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (seaMask[k]) { img.setRGB(i, j, 0x1F4E79); continue; }
                int shade = shadeGray(bed, n, i, j);
                int p = parent[k];
                if (p < 0 || !s.resolved()[k]) { img.setRGB(i, j, shade); continue; }
                int di = p / n - i, dj = p % n - j;
                boolean axis = (di == 0) || (dj == 0);
                boolean tie = Math.abs(s.level()[p] - s.level()[k]) < 1e-9;
                int color;
                if (tie) color = axis ? 0xFF4828 : 0x7A2010;
                else color = axis ? 0x38F038 : 0x0E9A0E;
                img.setRGB(i, j, color);
            }
        }
        Path dir = Path.of("build/hydro-reassessment/slopeconv-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(img, "png", dir.resolve("slope_flow.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"),
                "landResolved=" + totalLandResolved + "\n");
        System.out.println("READ: 若陡坡档里 converging 比例很低 ⇒ 坡面缺横向沟壑（地形侧问题）；");
        System.out.println("      若陡坡档 converging 也高但图上线仍平行 ⇒ 是显示口径问题。");
        System.out.println("IMAGE=" + dir.resolve("slope_flow.png").toAbsolutePath());
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
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

    private SlopeConvergenceProbe() { }
}
