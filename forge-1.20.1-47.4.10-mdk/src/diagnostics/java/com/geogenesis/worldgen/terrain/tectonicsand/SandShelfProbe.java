package com.geogenesis.worldgen.terrain.tectonicsand;

import java.util.Locale;

/**
 * 沙箱诊断：<b>陆架体检</b>——宽度分布、破碎度、边缘平滑度。
 *
 * <p>用户 2026-11-14 指出："大陆架边缘好像有很多破碎的地方，并且陆架好像变窄了。"
 * 本探针把这三件事量化：</p>
 *
 * <ol>
 *   <li><b>陆架宽度</b>：每个海岸格沿岸法向数"连续浅水格（0~200 m）"的格数，
 *       报 P10/P50/P90。地球被动陆缘 50~300 km（≈25~150 格 @1格=2km... 见下）；
 *       主动陆缘 ≈0。</li>
 *   <li><b>破碎度</b>：陆架带（0~200 m）内的连通分量数与"孤立小块"占比。
 *       破碎的陆架 = 大量 1~5 格的小块。</li>
 *   <li><b>边缘平滑度</b>：200 m 等深线的分形维数（盒计数）。地球陆架坡折是
 *       一条相对平滑的线（D≈1.05~1.15），不应接近海岸的破碎度。</li>
 * </ol>
 *
 * <p>用法：{@code SandShelfProbe <size> <cx> <cz> <radius>}</p>
 */
public final class SandShelfProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 800;
        double cx = args.length > 1 ? Double.parseDouble(args[1]) : 0.0;
        double cz = args.length > 2 ? Double.parseDouble(args[2]) : 0.0;
        double radius = args.length > 3 ? Double.parseDouble(args[3]) : 1750.0;

        // 直接复用生产探针的管线：把它的最终网格取出来
        double[][] h = SandSurfaceProbe.buildFinalGrid(n, cx, cz, radius, 1);
        double cellWu = 2 * radius / (n - 1);
        System.out.printf(Locale.ROOT,
                "=== 陆架体检 size=%d (%.0f,%.0f) R=%.0f  1格=%.2fwu=%.1fkm ===%n",
                n, cx, cz, radius, cellWu, cellWu * 7.14);

        // ---- 1) 深度分带（确认陆架带有没有被削掉）----
        int shelf = 0, slope = 0, rise = 0, abyss = 0, land = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double m = h[j][i] * 11000.0;
                if (m > 0) {
                    land++;
                } else if (m > -200) {
                    shelf++;
                } else if (m > -3000) {
                    slope++;
                } else if (m > -4000) {
                    rise++;
                } else {
                    abyss++;
                }
            }
        }
        int sea = n * n - land;
        System.out.printf(Locale.ROOT,
                "深度分带(占洋面): 陆架0~200m %.2f%%  陆坡200~3000 %.2f%%  "
                        + "陆隆3000~4000 %.2f%%  深海>4000 %.2f%%%n",
                100.0 * shelf / sea, 100.0 * slope / sea, 100.0 * rise / sea, 100.0 * abyss / sea);
        System.out.println("  （地球：陆架 ~8%、坡+隆 ~17%、深海 ~75%）");

        // ---- 2) 陆架宽度：从每个"海岸格"沿岸法向数浅水格 ----
        java.util.List<Integer> widths = new java.util.ArrayList<>();
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                if (h[j][i] <= 0 || h[j][i] * 11000.0 > 200) {
                    continue;   // 只看"海岸格"：陆地且 ≤200 m
                }
                boolean isCoast = h[j][i + 1] <= 0 || h[j][i - 1] <= 0
                        || h[j + 1][i] <= 0 || h[j - 1][i] <= 0;
                if (!isCoast) {
                    continue;
                }
                // 沿 4 个方向走出浅水带的格数，取最大
                int best = 0;
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : dirs) {
                    int cnt = 0;
                    int x = i, y = j;
                    for (int k = 0; k < 120; k++) {
                        x += d[0];
                        y += d[1];
                        if (x < 0 || y < 0 || x >= n || y >= n) {
                            break;
                        }
                        double m = h[y][x] * 11000.0;
                        if (m > 0) {
                            break;              // 又回到陆地
                        }
                        if (m < -200) {
                            break;              // 掉出陆架
                        }
                        cnt++;
                    }
                    best = Math.max(best, cnt);
                }
                widths.add(best);
            }
        }
        widths.sort(Integer::compare);
        if (!widths.isEmpty()) {
            int m = widths.size();
            System.out.printf(Locale.ROOT,
                    "陆架宽度(格): P10=%d P50=%d P90=%d max=%d  （≈ %.0f/%.0f/%.0f km）%n",
                    widths.get(m / 10), widths.get(m / 2), widths.get(m * 9 / 10),
                    widths.get(m - 1),
                    widths.get(m / 10) * cellWu * 7.14,
                    widths.get(m / 2) * cellWu * 7.14,
                    widths.get(m * 9 / 10) * cellWu * 7.14);
        }

        // ---- 3) 破碎度：陆架带（0~200m）的连通分量 ----
        boolean[] inShelf = new boolean[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double m = h[j][i] * 11000.0;
                inShelf[j * n + i] = m <= 0 && m > -200;
            }
        }
        boolean[] seen = new boolean[n * n];
        int[] stack = new int[n * n];
        int comps = 0, tiny = 0, largest = 0, total = 0;
        for (int k = 0; k < n * n; k++) {
            if (!inShelf[k] || seen[k]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = k;
            seen[k] = true;
            int start = 0;
            while (start < sp) {
                int c = stack[start++];
                int y = c / n, x = c % n;
                for (int d = 0; d < 4; d++) {
                    int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (xx < 0 || yy < 0 || xx >= n || yy >= n) {
                        continue;
                    }
                    int nb = yy * n + xx;
                    if (inShelf[nb] && !seen[nb]) {
                        seen[nb] = true;
                        if (sp < stack.length) {
                            stack[sp++] = nb;
                        }
                    }
                }
            }
            comps++;
            total += sp;
            largest = Math.max(largest, sp);
            if (sp < 20) {
                tiny += sp;
            }
        }
        System.out.printf(Locale.ROOT,
                "陆架破碎度: %d 个分量 / %d 格；最大分量 %d 格(%.1f%%)；"
                        + "<20格的小块共 %d 格(%.1f%%)%n",
                comps, total, largest, 100.0 * largest / Math.max(1, total),
                tiny, 100.0 * tiny / Math.max(1, total));

        // ---- 4) 200m 等深线（陆架坡折）的分形维数 ----
        int[] boxSizes = {2, 4, 8, 16, 32};
        int[] boxCount = new int[boxSizes.length];
        boolean[] onShelfBreak = new boolean[n * n];
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                boolean a = h[j][i] * 11000.0 > -200;
                boolean b = h[j][i + 1] * 11000.0 > -200;
                boolean c = h[j - 1][i] * 11000.0 > -200;
                if (a != b || a != c) {
                    onShelfBreak[j * n + i] = true;
                }
            }
        }
        for (int bi = 0; bi < boxSizes.length; bi++) {
            int e = boxSizes[bi];
            int cnt = 0;
            for (int j = 0; j + e <= n; j += e) {
                for (int i = 0; i + e <= n; i += e) {
                    boolean any = false;
                    for (int jj = j; jj < j + e && !any; jj++) {
                        for (int ii = i; ii < i + e; ii++) {
                            if (onShelfBreak[jj * n + ii]) {
                                any = true;
                                break;
                            }
                        }
                    }
                    if (any) {
                        cnt++;
                    }
                }
            }
            boxCount[bi] = cnt;
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int mfit = 0;
        for (int bi = 0; bi < boxSizes.length - 1; bi++) {
            if (boxCount[bi] <= 0) {
                continue;
            }
            double lx = Math.log(boxSizes[bi]);
            double ly = Math.log(boxCount[bi]);
            sx += lx;
            sy += ly;
            sxx += lx * lx;
            sxy += lx * ly;
            mfit++;
        }
        double d = mfit >= 2
                ? -(mfit * sxy - sx * sy) / Math.max(1e-12, mfit * sxx - sx * sx) : 1.0;
        StringBuilder bd = new StringBuilder();
        for (int bi = 0; bi < boxSizes.length; bi++) {
            bd.append(String.format(Locale.ROOT, "e=%d:%d ", boxSizes[bi], boxCount[bi]));
        }
        System.out.printf(Locale.ROOT, "200m等深线盒计数: %s%n", bd);
        System.out.printf(Locale.ROOT,
                "陆架坡折分形维数 D=%.3f  （地球陆架坡折 1.05~1.15，海岸线 1.20~1.35）%n", d);
    }
}