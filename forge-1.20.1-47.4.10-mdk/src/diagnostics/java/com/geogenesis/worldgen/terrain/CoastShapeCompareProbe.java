package com.geogenesis.worldgen.terrain;

import java.util.Locale;

/**
 * <b>大陆形状定量对比</b>：新方案（核链 + FBM 塑形）vs 旧纯 FBM 的海岸复杂度。
 *
 * <h3>为什么需要它</h3>
 * <p>用户 2026-11-16 反馈「新大陆形状比旧 FBM 差非常多」。目检不足以定位差距，
 * 本探针给出三个可比的客观量（同窗口、同分辨率）：</p>
 * <ol>
 *   <li><b>海岸分形维数 D</b>（盒计数法；真实大陆 1.20~1.35，光滑椭圆 → 1.00）</li>
 *   <li><b>海岸线长度</b>（陆/海边界格数 ÷ 陆地格数 = 岸线密度）</li>
 *   <li><b>陆地占比</b>（确认两者可比）</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runCoastShapeCompareProbe [-PprobeArgs="&lt;size&gt; &lt;step&gt;"]}<br>
 * 默认 size=512, step=2（= 1024 块窗口）。窗口自动搜索到陆地占比 ≈0.45。</p>
 */
public final class CoastShapeCompareProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 512;
        double step = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;

        TerrainParams p = TerrainParams.defaults();
        // 新方案：生产路径（读 geogenesis.continentCores 开关，默认开 = 核链）
        ContinentField modern = new ContinentField(p);
        modern.seed(SEED);
        // 旧方案：显式 legacy（纯 FBM）
        ContinentField legacy = new ContinentField(p, true);
        legacy.seed(SEED);

        // 窗口：以新方案的陆地占比为准自动搜索（两边同一窗口才可比）
        double cx = args.length > 2 ? Double.parseDouble(args[2]) : 2000;
        double cz = args.length > 3 ? Double.parseDouble(args[3]) : -16000;
        if (args.length <= 2) {
            double[] w = findWindow(modern, n, step);
            cx = w[0]; cz = w[1];
        }
        System.out.printf(Locale.ROOT, "=== 海岸形状对比 size=%d step=%.0f centre=(%.0f,%.0f) ===%n",
                n, step, cx, cz);

        Stats a = measure(modern, n, step, cx, cz);
        Stats b = measure(legacy, n, step, cx, cz);
        System.out.println();
        System.out.printf(Locale.ROOT, "%-28s %10s %10s %10s%n", "指标", "新(核+FBM)", "旧(纯FBM)", "差");
        System.out.printf(Locale.ROOT, "%-28s %10.3f %10.3f %+10.3f%n",
                "海岸分形 D", a.fractalD, b.fractalD, a.fractalD - b.fractalD);
        System.out.printf(Locale.ROOT, "%-28s %10.4f %10.4f %+10.4f%n",
                "岸线密度(边界格/陆格)", a.coastDensity, b.coastDensity,
                a.coastDensity - b.coastDensity);
        System.out.printf(Locale.ROOT, "%-28s %10.3f %10.3f %+10.3f%n",
                "陆地占比", a.landFrac, b.landFrac, a.landFrac - b.landFrac);
        System.out.printf(Locale.ROOT, "%-28s %10d %10d%n", "小陆块(<8格)", a.tinyBlobs, b.tinyBlobs);
        System.out.printf(Locale.ROOT, "%-28s %10d %10d%n", "陆块总数", a.blobs, b.blobs);
        System.out.println();
        System.out.println("判定参考：真实大陆海岸 D≈1.20~1.35；旧纯 FBM 为本项目的形状基准。");
    }

    private record Stats(double fractalD, double coastDensity, double landFrac,
                         int blobs, int tinyBlobs) { }

    private static Stats measure(ContinentField field, int n, double step, double cx, double cz) {
        double[][] h = new double[n][n];
        double half = (n - 1) * step / 2.0;
        for (int j = 0; j < n; j++) {
            double wz = cz - half + j * step;
            for (int i = 0; i < n; i++) {
                h[j][i] = field.sample(cx - half + i * step, wz);
            }
        }
        int land = 0;
        int coastEdges = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                boolean isLand = h[j][i] > 0;
                if (isLand) {
                    land++;
                    if ((i + 1 < n && h[j][i + 1] <= 0) || (i > 0 && h[j][i - 1] <= 0)
                            || (j + 1 < n && h[j + 1][i] <= 0) || (j > 0 && h[j - 1][i] <= 0)) {
                        coastEdges++;
                    }
                }
            }
        }
        int[] bc = connectedComponents(h, n);
        return new Stats(fractalD(h, n), land > 0 ? coastEdges / (double) land : 0,
                land / (double) (n * n), bc[0], bc[1]);
    }

    /** 盒计数分形维数（ε=2,4,8,16,32 中取前四档最小二乘）。 */
    private static double fractalD(double[][] h, int n) {
        int[] boxSizes = {2, 4, 8, 16, 32};
        int[] boxCount = new int[boxSizes.length];
        for (int b = 0; b < boxSizes.length; b++) {
            int e = boxSizes[b];
            int c = 0;
            for (int j = 0; j + e <= n; j += e) {
                for (int i = 0; i + e <= n; i += e) {
                    boolean hasLand = false, hasSea = false;
                    for (int jj = j; jj < j + e && !(hasLand && hasSea); jj++) {
                        for (int ii = i; ii < i + e; ii++) {
                            if (h[jj][ii] > 0) {
                                hasLand = true;
                            } else {
                                hasSea = true;
                            }
                            if (hasLand && hasSea) {
                                break;
                            }
                        }
                    }
                    if (hasLand && hasSea) {
                        c++;
                    }
                }
            }
            boxCount[b] = c;
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int m = 0;
        for (int b = 0; b < boxSizes.length - 1; b++) {
            if (boxCount[b] <= 0) {
                continue;
            }
            double lx = Math.log(boxSizes[b]);
            double ly = Math.log(boxCount[b]);
            sx += lx;
            sy += ly;
            sxx += lx * lx;
            sxy += lx * ly;
            m++;
        }
        if (m < 2) {
            return 1.0;
        }
        return -(m * sxy - sx * sy) / Math.max(1e-12, m * sxx - sx * sx);
    }

    /** 陆块连通分量：{总数, 小于 8 格的碎片数}。 */
    private static int[] connectedComponents(double[][] h, int n) {
        boolean[][] seen = new boolean[n][n];
        int total = 0, tiny = 0;
        int[] stack = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= 0 || seen[j][i]) {
                    continue;
                }
                int sp = 0;
                stack[sp++] = j * n + i;
                seen[j][i] = true;
                int size = 0;
                while (sp > 0) {
                    int k = stack[--sp];
                    int y = k / n, x = k % n;
                    size++;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0], ny = y + d[1];
                        if (nx >= 0 && nx < n && ny >= 0 && ny < n && !seen[ny][nx]
                                && h[ny][nx] > 0) {
                            seen[ny][nx] = true;
                            stack[sp++] = ny * n + nx;
                        }
                    }
                }
                total++;
                if (size < 8) {
                    tiny++;
                }
            }
        }
        return new int[]{total, tiny};
    }

    /** 搜索陆地占比最接近 0.45 的窗口（以新方案为准）。 */
    private static double[] findWindow(ContinentField field, int n, double step) {
        final int m = 40;
        double half = (n - 1) * step / 2.0;
        double best = Double.MAX_VALUE;
        double[] out = {0, 0};
        for (double cz = -24000; cz <= 24000; cz += 4000) {
            for (double cx = -24000; cx <= 24000; cx += 4000) {
                int land = 0;
                for (int j = 0; j < m; j++) {
                    double wz = cz - half + j * (2 * half / (m - 1));
                    for (int i = 0; i < m; i++) {
                        if (field.sample(cx - half + i * (2 * half / (m - 1)), wz) > 0) {
                            land++;
                        }
                    }
                }
                double score = Math.abs(land / (double) (m * m) - 0.45);
                if (score < best) {
                    best = score;
                    out = new double[]{cx, cz};
                }
            }
        }
        return out;
    }

    private CoastShapeCompareProbe() { }
}
