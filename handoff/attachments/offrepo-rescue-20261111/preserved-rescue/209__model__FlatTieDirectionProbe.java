package com.geogenesis.worldgen.hydrology.model;

/**
 * 检验：平坦区域的<i>排水方向</i>是否由【格索引顺序】决定。
 *
 * <p><b>动机</b>：半径 640 的湖河图上，沿海平原出现大量<b>平行等距的竖直河纹</b>。
 * 真实平原不可能长出这种图案 ⇒ 属伪影。最可疑的机制是
 * {@link EquilibriumWater} 在<b>水位相同</b>时用 {@code Integer.compare(cell)} 打破平局，
 * 而 cell = i*nz + j ⇒ 平局时优先流向"索引更大"的格，即固定方向
 * ⇒ 在平地上印出与坐标轴对齐的平行线。</p>
 *
 * <p>本探针不做判据阈值，只回答一个是非题：<b>把平地整体旋转 90°，
 * 排水方向场是否跟着旋转（自然）还是保持与数组索引对齐（伪影）？</b></p>
 *
 * <p>自然各向同性场的答案是"跟着转"；若结论是"不跟着转"，则平行纹来自平局规则，
 * 必须改为<b>几何无关</b>的平局打破方式。</p>
 */
public final class FlatTieDirectionProbe {

    public static void main(String[] args) {
        int n = 64;
        // 完全平坦的床面：所有格水位相同 ⇒ 平局规则主导一切。
        double[] flat = new double[n * n];
        boolean[] drains = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        // 在中心放一个洼地作为唯一排水终端，其余全平。
        flat[(n / 2) * n + n / 2] = -1.0;
        drains[(n / 2) * n + n / 2] = true;

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        System.out.println("=== flat-bed drainage direction: index-driven or geometry-driven? ===");
        report("unrotated", flat, n, drains, exterior, dirs);

        // 旋转 90°：把 (i,j) -> (j, n-1-i)。几何等价（方阵、同一洼地位置对应旋转）。
        double[] rot = new double[n * n];
        boolean[] drainsRot = new boolean[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // 目标 (i,j) 来自源 (n-1-j, i)
                rot[i * n + j] = flat[(n - 1 - j) * n + i];
                drainsRot[i * n + j] = drains[(n - 1 - j) * n + i];
            }
        }
        report("rotated90", rot, n, drainsRot, exterior, dirs);
        System.out.println("READ: 两次统计若【总量相同但方向不同】⇒ 方向由几何决定（自然）。");
        System.out.println("      若两次【方向直方图完全相同】⇒ 方向被数组索引锁死（伪影）。");
    }

    private static void report(String label, double[] bed, int n, boolean[] drains,
                               double[] exterior, int[][] dirs) {
        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, drains, exterior);
        int[] parent = s.parent();
        int east = 0, west = 0, north = 0, south = 0;
        for (int k = 0; k < n * n; k++) {
            int p = parent[k];
            if (p < 0) continue;
            int di = p / n - k / n, dj = p % n - k % n;
            if (di > 0) east++;
            else if (di < 0) west++;
            else if (dj > 0) south++;
            else if (dj < 0) north++;
        }
        System.out.printf("  %-11s east=%4d west=%4d north=%4d south=%4d  (total=%d)%n",
                label, east, west, north, south, east + west + north + south);
    }

    private FlatTieDirectionProbe() { }
}
