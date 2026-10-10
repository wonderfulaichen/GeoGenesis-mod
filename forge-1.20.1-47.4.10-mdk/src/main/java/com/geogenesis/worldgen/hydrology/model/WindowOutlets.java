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
     * ★ 垂直精度单位（2026-10-10，用户判据驱动）——谷底取小采样（legacy 同构）。
     *
     * <h3>用户判据原文</h3>
     * <p>「那些多段反复缠绕我认为主要是地形垂直精度太高导致算法的抖动。
     * 我认为这精度应该对应我们独立单位。不可能水平走我们的单位，垂直走 MC 的块单位吧？」</p>
     *
     * <h3>机理</h3>
     * <p>水平采样在格点网（step=4 block）上，但每格的 {@code bed} 是<b>单点</b>值 ——
     * MC 地形在 block 尺度有垂直细节（侵蚀微沟/岩屑噪声），幅度可达数块。
     * D8 最陡下降对这种亚格噪声极度敏感：坡面上相邻两格的"谁更低"由
     * 【亚格噪声】而不是【宏观坡向】决定 ⇒ 流向逐格左右抖动 ⇒
     * 河在坡面上呈紧密锯齿/缠绕（用户截图的"多段反复缠绕"）。</p>
     *
     * <h3>legacy 的做法（正解）</h3>
     * <p>{@code RiverLineNetwork.buildSkeletonRivers}（2026-09-22「谷底取小采样」）：
     * 角点预采样 (gridN+1)²，每格高度 = 【自身 4 角最小值】，注释明写
     * 「每格取谷底而不是格心 ⇒ 河贴谷走，不翻山脊」。
     * 4 角最小值 = 该格元的【最低点】= 对亚格垂直噪声取 min ⇒
     * 噪声被吸收进"谷底"，宏观坡向重新主导 D8 方向。</p>
     *
     * <p>⚠ 这正是用户说的"垂直精度对应独立单位"：垂直分辨率与水平格元
     * （step² block）匹配，而不是让 block 级噪声泄漏进决策。</p>
     *
     * <p><b>开关</b>：{@code -Dgeogenesis.valleyMin=false} 关闭（= 旧单点采样，逐位不变）。
     * 默认开（用户判据直接驱动）。</p>
     */
    static final boolean VALLEY_MIN =
            !"false".equals(System.getProperty("geogenesis.valleyMin"));

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
        // 谷底取小采样：角点预采样（(nx+1)×(nz+1)，角点被相邻格共享 ⇒ 采样量 ≈ 单点版）
        //   坐标契约：格 (i,j) 的 4 角 = 格心 ± step/2（格元以格心为中心）。
        double half = step * 0.5;
        double bx0 = cx - radius, bz0 = cz - radius;
        double[] corner = null;
        if (VALLEY_MIN) {
            corner = new double[(nx + 1) * (nz + 1)];
            for (int i = 0; i <= nx; i++) {
                for (int j = 0; j <= nz; j++) {
                    double wx = bx0 + i * (double) step - half;
                    double wz = bz0 + j * (double) step - half;
                    corner[i * (nz + 1) + j] = terrain.height(wx, wz);
                }
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                double gx = bx0 + i * (double) step, gz = bz0 + j * (double) step;
                if (corner != null) {
                    // 每格 = 自身 4 角最小值（legacy buildSkeletonRivers 同构 ⇒ 河贴谷走）
                    double a = corner[i * (nz + 1) + j];
                    double b = corner[(i + 1) * (nz + 1) + j];
                    double c = corner[i * (nz + 1) + j + 1];
                    double d = corner[(i + 1) * (nz + 1) + j + 1];
                    bed[k] = Math.min(Math.min(a, b), Math.min(c, d));
                } else {
                    bed[k] = terrain.height(gx, gz);
                }
                if (bed[k] < seaLevel) drains[k] = true;          // 真实海格
                boolean frame = i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
                if (!frame) continue;
                int oi = i == 0 ? -1 : i == nx - 1 ? nx : i;
                int oj = j == 0 ? -1 : j == nz - 1 ? nz : j;
                double outside = terrain.height(bx0 + oi * (double) step,
                        bz0 + oj * (double) step);
                exterior[k] = Math.max(outside, seaLevel);
                borderCells++;
            }
        }
        return new Result(bed, drains, exterior, borderCells);
    }

    public record Result(double[] bed, boolean[] drains, double[] exterior, int borderCells) { }
}
