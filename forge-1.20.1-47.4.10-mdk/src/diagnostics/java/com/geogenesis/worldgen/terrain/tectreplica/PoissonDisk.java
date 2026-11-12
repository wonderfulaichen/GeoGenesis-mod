package com.geogenesis.worldgen.terrain.tectreplica;

import java.util.ArrayList;
import java.util.List;

/**
 * 泊松圆盘采样 —— poisson.h:91-166 的忠实移植（Bridson 变体）。
 *
 * <p>产出 {@code K} 个段质心（实际可能略少：连续失败 {@code tries > set.size()}
 * 会提前终止，poisson.h:120-121）。</p>
 *
 * <p>与原版的差异只有随机源：原版 {@code mt19937(random_device())} 固定种子也不可复现
 * （poisson.h:6-8），改由 {@link TectRng} 统一供给。</p>
 */
final class PoissonDisk {

    private PoissonDisk() {
    }

    /**
     * @param k  目标点数（SimpleTectonics 用 K=8192）
     * @param a  域左下角（SimpleTectonics 用 (0,0)）
     * @param b  域右上角（SimpleTectonics 用 (256,256)）
     */
    static List<double[]> sample(TectRng rng, int k, double ax, double ay, double bx, double by) {
        List<double[]> set = new ArrayList<>();
        // 最小间距：r = sqrt(|b-a|² / (π·k))（poisson.h:93，注意用的是对角向量点积而非面积公式）
        double dx = bx - ax, dy = by - ay;
        double r = Math.sqrt((dx * dx + dy * dy) / (Math.PI * k));
        double cell = r / Math.sqrt(2);
        int nx = (int) Math.ceil(dx / cell);
        int ny = (int) Math.ceil(dy / cell);
        int[] grid = new int[nx * ny];

        // 空集先放域中心（poisson.h:107-111）
        set.add(new double[] {0.5 * (ax + bx), 0.5 * (ay + by)});
        addToGrid(grid, nx, ny, set.get(0), 1, ax, ay, bx, by);
        k--;

        int tries = 0;
        while (k > 0) {
            if (tries > set.size()) {
                break;
            }
            // 随机选一个已有点（poisson.h:124）；原版 int 截断，n==size 是 C++ UB，钳到 size-1
            int n = (int) rng.uniform(0, set.size());
            if (n >= set.size()) {
                n = set.size() - 1;
            }
            double nr = rng.uniform(r, 2 * r);
            double nt = rng.uniform(0, 2 * Math.PI);
            double nxp = set.get(n)[0] + nr * Math.cos(nt);
            double nyp = set.get(n)[1] + nr * Math.sin(nt);
            // 界外重试不计入 tries（poisson.h:132-133 的 continue 不走 tries++）
            if (nxp < ax || nxp >= bx || nyp < ay || nyp >= by) {
                continue;
            }
            int ix = (int) (nx * (nxp - ax) / dx);
            int iy = (int) (ny * (nyp - ay) / dy);
            boolean free = true;
            for (int i = ix - 2; i <= ix + 2 && free; i++) {
                for (int j = iy - 2; j <= iy + 2 && free; j++) {
                    if (i < 0 || i >= nx || j < 0 || j >= ny) {
                        continue;
                    }
                    int g = grid[i * ny + j];
                    if (g > 0 && dist(set.get(g - 1), nxp, nyp) < r) {
                        free = false;
                    }
                }
            }
            if (!free) {
                tries++;
                continue;
            }
            double[] p = new double[] {nxp, nyp};
            set.add(p);
            addToGrid(grid, nx, ny, p, set.size(), ax, ay, bx, by);
            tries = 0;
            k--;
        }
        return set;
    }

    private static double dist(double[] p, double x, double y) {
        double dx = p[0] - x, dy = p[1] - y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** grid[ind.x*NY+ind.y] = val（poisson.h:33-34）；ivec2 截断语义。 */
    private static void addToGrid(int[] grid, int nx, int ny, double[] p, int val,
                                  double ax, double ay, double bx, double by) {
        int ix = (int) (nx * (p[0] - ax) / (bx - ax));
        int iy = (int) (ny * (p[1] - ay) / (by - ay));
        if (ix >= 0 && ix < nx && iy >= 0 && iy < ny) {
            grid[ix * ny + iy] = val;
        }
    }
}
