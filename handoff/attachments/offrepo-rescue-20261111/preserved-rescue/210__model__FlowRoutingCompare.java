package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 汇流路由 A/B：<b>D8（单流向连通图）vs MFD（多流向）</b>。
 *
 * <h2>为什么要做这个</h2>
 * <p>用户质疑「97% 流向由真实梯度决定」本身不符合流体物理。核实后确认：
 * D8 是<b>连通图</b>（每格唯一下游），不是流体模型。参考项目
 * {@code geotransport/example/accumulate_dem.py} 第 14–20 行明确区分
 * fastflow（=连通图）与 geotransport（=连续速度场），并指出二者"略有不同"。</p>
 *
 * <p>D8 的结构性后果：均匀坡面上相邻格各挑最陡方向 ⇒ <b>流向必然平行</b>，
 * 无法产生横向展宽与沟壑发育。这正是图上"平行直纹"的来源之一。</p>
 *
 * <h2>MFD（Freeman 1991 多流向）</h2>
 * <p>把每格出流按 {@code w_i ∝ (Δh_i / d_i)^p} 分配给<b>全部</b>更低的邻居
 * （d 为按格距归一化的距离，p 为散度指数）。效果：</p>
 * <ul>
 *   <li>均匀坡面上相邻格的流线会<b>侧向扩散并相互重叠</b> ⇒ 不再严格平行；</li>
 *   <li>在真实地形上自然形成平滑的汇流带（数字上更接近连续速度场）。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <p>不比"哪个更好"（无真值），只回答一个是非题：
 * <b>换成 MFD 后，缓坡区的平行细纹是否消失、变成平滑汇聚场？</b>
 * 若是 ⇒ 确证平行线主因是 D8 的路由结构，而非地形本身。</p>
 */
public final class FlowRoutingCompare {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -500;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -1000;
        int step = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int radius = args.length > 4 ? Integer.parseInt(args[4]) : 400;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu(
                        (cx - radius + i * step) / hs, (cz - radius + j * step) / hs).height;
            }
        }

        double[] d8 = accumulateD8(bed, n);
        double[] mfd = accumulateMFD(bed, n, 1.1);

        Path dir = Path.of("build/hydro-reassessment/routing-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n * 3 + 16, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                img.setRGB(i, j, shadeGray(bed, n, i, j));
                img.setRGB(n + 8 + i, j, dischargeColor(d8, k));
                img.setRGB(2 * n + 16 + i, j, dischargeColor(mfd, k));
            }
        }
        ImageIO.write(img, "png", dir.resolve("routing.png").toFile());

        // 量化：平行度。统计"流向严格平行于邻居"的比例（D8 应高、MFD 应低）。
        double d8Parallel = parallelFraction(bed, n, false);
        double mfdParallel = parallelFraction(bed, n, true);
        StringBuilder log = new StringBuilder();
        log.append("flow routing A/B; left=terrain, middle=D8 accumulation, right=MFD accumulation\n");
        log.append(String.format("seed=%d centre=(%d,%d) step=%d radius=%d cells=%d%n",
                seed, cx, cz, step, radius, n * n));
        log.append(String.format("D8  maxAcc=%12.1f%n", max(d8)));
        log.append(String.format("MFD maxAcc=%12.1f  (MFD 峰值通常低于 D8：能量被分散而非集中到单线)%n", max(mfd)));
        log.append(String.format("parallel-flow fraction: D8=%.3f   MFD=%.3f%n", d8Parallel, mfdParallel));
        log.append("READ: MFD 的 parallel 比例应显著低于 D8；图上中/右两幅的纹理差异即直观证据。\n");
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("routing.png").toAbsolutePath());
    }

    /** D8：每格全部出流交给最陡的【唯一】下游邻居。 */
    private static double[] accumulateD8(double[] bed, int n) {
        int cells = n * n;
        double[] acc = new double[cells];
        java.util.Arrays.fill(acc, 1.0);
        Integer[] order = orderByHeightDesc(bed, cells);
        int[][] dirs = DIRS8;
        for (int idx = 0; idx < cells; idx++) {
            int k = order[idx];
            int i = k / n, j = k % n;
            int best = -1;
            double bestDrop = 0;
            for (int[] d : dirs) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || a >= n || b < 0 || b >= n) continue;
                int nb = a * n + b;
                double drop = (bed[k] - bed[nb]) / edge(d[0], d[1]);
                if (drop > bestDrop) { bestDrop = drop; best = nb; }
            }
            if (best >= 0) acc[best] += acc[k];
        }
        return acc;
    }

    /** MFD（Freeman 1991）：按 (drop/distance)^p 权重分配给全部更低邻居。 */
    private static double[] accumulateMFD(double[] bed, int n, double p) {
        int cells = n * n;
        double[] acc = new double[cells];
        java.util.Arrays.fill(acc, 1.0);
        Integer[] order = orderByHeightDesc(bed, cells);
        for (int idx = 0; idx < cells; idx++) {
            int k = order[idx];
            int i = k / n, j = k % n;
            double wsum = 0;
            double[] w = new double[DIRS8.length];
            int[] nb = new int[DIRS8.length];
            for (int t = 0; t < DIRS8.length; t++) {
                int[] d = DIRS8[t];
                int a = i + d[0], b = j + d[1];
                if (a < 0 || a >= n || b < 0 || b >= n) { nb[t] = -1; continue; }
                int m = a * n + b;
                double drop = (bed[k] - bed[m]) / edge(d[0], d[1]);
                if (drop <= 0) { nb[t] = -1; continue; }
                nb[t] = m;
                w[t] = Math.pow(drop, p);
                wsum += w[t];
            }
            if (wsum <= 0) continue;                      // 洼地：无下游
            for (int t = 0; t < DIRS8.length; t++) {
                if (nb[t] < 0) continue;
                acc[nb[t]] += acc[k] * (w[t] / wsum);
            }
        }
        return acc;
    }

    /**
     * "流向与某邻居严格平行"的比例：对每个格，看它的下游方向是否与 8 邻域中
     * 某个其他格的下游方向完全相同（D8 在均匀坡面上会造成成片相同方向）。
     */
    private static double parallelFraction(double[] bed, int n, boolean mfd) {
        int[][] dirs = DIRS8;
        int[] dirOf = new int[n * n];
        java.util.Arrays.fill(dirOf, -1);
        for (int k = 0; k < n * n; k++) {
            int i = k / n, j = k % n;
            if (mfd) {
                // MFD 无单一方向 ⇒ 以"加权平均方向"近似，量化后仍为格向
                double sx = 0, sy = 0;
                for (int[] d : dirs) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || a >= n || b < 0 || b >= n) continue;
                    double drop = (bed[k] - bed[a * n + b]) / edge(d[0], d[1]);
                    if (drop <= 0) continue;
                    double w = Math.pow(drop, 1.1);
                    sx += w * d[0]; sy += w * d[1];
                }
                if (Math.hypot(sx, sy) < 1e-9) continue;
                dirOf[k] = (int) (Math.round(Math.atan2(sy, sx) / (Math.PI / 4)) + 8) % 8;
                continue;
            }
            int best = -1; double bestDrop = 0; int bestD = -1;
            for (int t = 0; t < dirs.length; t++) {
                int[] d = dirs[t];
                int a = i + d[0], b = j + d[1];
                if (a < 0 || a >= n || b < 0 || b >= n) continue;
                double drop = (bed[k] - bed[a * n + b]) / edge(d[0], d[1]);
                if (drop > bestDrop) { bestDrop = drop; best = a * n + b; bestD = t; }
            }
            if (best >= 0) dirOf[k] = bestD;
        }
        // 统计：有多少格与其右邻居方向相同（平行成片）
        int same = 0, total = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j + 1 < n; j++) {
                int a = i * n + j, b = i * n + j + 1;
                if (dirOf[a] < 0 || dirOf[b] < 0) continue;
                total++;
                if (dirOf[a] == dirOf[b]) same++;
            }
        }
        return total == 0 ? 0 : same / (double) total;
    }

    private static Integer[] orderByHeightDesc(double[] bed, int cells) {
        Integer[] order = new Integer[cells];
        for (int k = 0; k < cells; k++) order[k] = k;
        java.util.Arrays.sort(order, (a, b) -> {
            int c = Double.compare(bed[b], bed[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        return order;
    }

    private static final int[][] DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static double edge(int dx, int dz) {
        return (dx == 0 || dz == 0) ? 1.0 : Math.sqrt(2.0);
    }

    private static double max(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    private static int dischargeColor(double[] acc, int k) {
        double v = acc[k];
        if (v <= 1.5) return 0x909090;
        if (v < 4) return 0x64E8D8;
        if (v < 16) return 0x00E5D0;
        if (v < 64) return 0x00C8B4;
        if (v < 256) return 0x2E8BC8;
        return 0x1F5FA8;
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

    private FlowRoutingCompare() { }
}
