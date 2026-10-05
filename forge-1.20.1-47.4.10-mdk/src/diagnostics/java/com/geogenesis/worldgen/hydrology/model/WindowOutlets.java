package com.geogenesis.worldgen.hydrology.model;

/**
 * 由窗口边界自动构造排水出口：解决平衡水位方法在【内陆窗口无海】时无定义的问题。
 *
 * <h2>问题</h2>
 * <p>{@link EquilibriumWater} 必须以"真实排水终端"为种子。若查询点深处内陆、
 * 窗口内一格海都没有，洪泛没有种子 ⇒ 方法无定义（实测 {@code NO-SEA}）。
 * 这不是数值误差，是方法性缺口。</p>
 *
 * <h2>做法</h2>
 * <p>把窗口<b>每个边框格</b>都登记一个"外部水位"：</p>
 * <pre>  exterior[k] = max(窗口外一格的真实地形高, 海平面)</pre>
 * <ul>
 *   <li><b>不会凭空放水</b>：外部水位取真实窗外地形。若窗外更高，水位低于它，
 *       边界通量为零（水出不去，正确）；只有窗外确实更低时才成为出口。</li>
 *   <li><b>不制造伪出口</b>：它反映的是"这一步走出去之后地形有多高"，
 *       而不是把窗口边框当作人为排水口（那是旧 priority-flood 的做法，会产生假排水）。</li>
 *   <li>取 max(..., 海平面) 保证：即使窗外低于海面，外部水位也不低于海，
 *       否则会把内陆洼地误排到"虚构的海"。</li>
 * </ul>
 *
 * <h2>与"收敛判据"的分工</h2>
 * <p>出口只是让求解有定义；某格的结果是否<b>可信</b>仍由
 * {@link EquilibriumWater} 的收敛判据决定（计入窗外地形后结果是否改变）。
 * 两者不可混为一谈：<b>有出口 ≠ 结果与窗口无关</b>。</p>
 */
public final class WindowOutlets {

    /** 地形采样回调（块坐标 → 高程）。 */
    public interface Terrain { double height(double blockX, double blockZ); }

    private WindowOutlets() { }

    /**
     * 构造边界出口的三个数组。
     *
     * @param radius    {@code window} 半径（块）；此处避开 Java 关键字命名
     * @param terrain   地形采样器
     */
    public static Result build(int nx, int nz, int cx, int cz, int radius, int step,
                               double seaLevel, Terrain terrain) {
        double[] bed = new double[nx * nz];
        boolean[] drains = new boolean[nx * nz];
        double[] exterior = new double[nx * nz];
        java.util.Arrays.fill(exterior, Double.NaN);
        int borderCells = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                bed[k] = terrain.height(cx - radius + i * (double) step,
                        cz - radius + j * (double) step);
                if (bed[k] < seaLevel) drains[k] = true;          // 真实海格
                boolean frame = i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
                if (!frame) continue;
                int oi = i == 0 ? -1 : i == nx - 1 ? nx : i;
                int oj = j == 0 ? -1 : j == nz - 1 ? nz : j;
                double outside = terrain.height(cx - radius + oi * (double) step,
                        cz - radius + oj * (double) step);
                exterior[k] = Math.max(outside, seaLevel);
                borderCells++;
            }
        }
        return new Result(bed, drains, exterior, borderCells);
    }

    public record Result(double[] bed, boolean[] drains, double[] exterior, int borderCells) { }
}
