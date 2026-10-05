package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>检验"有效水面棋盘格"假说</h1>
 *
 * <h2>已实测排除的两个假设</h2>
 * <ul>
 *   <li><b>"河道在真·平坦区流向欠定"</b> —— <b>证伪</b>。实测（r=300）
 *       河道格的床面梯度 P50=<b>0.42</b> 块/块、<b>14,304 格 ≥0.1</b>、<b>仅 7 格 &lt;0.01</b>。
 *       ⇒ 河基本本不在平地上，交接文档"改用真实地形坡度定方向"无从下手。</li>
 *   <li><b>"湖溢流注入量失控导致碎裂"</b> —— <b>证伪</b>。把单出口上限 50k 改成全局预算后，
 *       注入量 214,596 → 33,093，而连通分量 <b>1078 → 1076（几乎不变）</b>。
 *       ⇒ 那个 45 分钟的卡死是真 bug（已修），但<b>不是</b>碎裂的成因。</li>
 * </ul>
 *
 * <h2>本探针要检验的假说：{@code s = resolved ? level : bed} 在两域交界处制造棋盘格</h2>
 * <p>{@link ContinuousRiverNetwork#velocity} 用
 * {@code s[k] = resolved[k] ? level[k] : bed[k]} 作为过水坡面。
 * 而 minimax 恒有 {@code level ≥ bed} ⇒ 在<b>已决/未决交界</b>上，
 * {@code s} 会从 {@code bed} <b>跳到</b> {@code level}，跳变幅度 = 该格的<b>填洼深度</b>。</p>
 *
 * <p><b>推论（若成立则足以解释全部现象）</b>：</p>
 * <ol>
 *   <li>交界带两侧的 {@code ∇s} 方向<b>相反</b>（都指向已填平的低处）⇒ 流线在带内来回振荡；</li>
 *   <li>振荡 ⇒ 每 40 步净位移 &lt;2 格 ⇒ 触发 <b>stagnant</b> 救援（实测 stagnant=<b>1,225,770</b>，
 *       平均每条流线 <b>2 次</b>）；</li>
 *   <li>救援改走 parent ⇒ 流线不再沿速度场积分 ⇒ 汇流量不按上游面积累积
 *       ⇒ 门槛附近随机翻转 ⇒ 河道碎成多段（实测 <b>1076 分量 / P50=5 格</b>）。</li>
 * </ol>
 *
 * <h2>三条可判定的量（全部只读，不改任何产出）</h2>
 * <ol>
 *   <li><b>交界带宽度占比</b>：{@code resolved} 与未 {@code resolved} 邻居相邻的格占多少。
 *       若很大（如 &gt;10%），说明棋盘格遍布全域。</li>
 *   <li><b>跳变幅度</b>：交界处 {@code |Δs|} 的均值 vs 全域 {@code |∇s|} 的均值。
 *       若交界跳变远大于域内梯度 ⇒ 棋盘格"盖过"了真实地形。</li>
 *   <li><b>方向翻转率</b>：沿流线连续两步的 {@code -∇s/|∇s|} 点积为负的比例。
 *       这是"振荡"的<b>直接</b>度量，也是判据 5 里 {@code stagnant} 的成因解释。</li>
 * </ol>
 *
 * <p><b>NOT VERIFIED</b>：本探针只测诊断层；方块铺设与实机渲染未覆盖。
 */
public final class EffectiveSurfaceProbe {

    private EffectiveSurfaceProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 50_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        double[] bed = r.bed(), level = r.level();
        boolean[] res = r.resolved();

        // s = 过水坡面（与生产 velocity() 完全同口径）
        double[] s = new double[cells];
        for (int k = 0; k < cells; k++) s[k] = res[k] ? level[k] : bed[k];

        int boundaryCells = 0;
        double jumpSum = 0; int jumpCnt = 0;
        double interiorGradSum = 0; int interiorCnt = 0;
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                boolean mixed = false;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int kk = (i + d[0]) * n + (j + d[1]);
                    if (res[kk] != res[k]) { mixed = true; }
                    double ds = Math.abs(s[kk] - s[k]);
                    if (res[kk] != res[k]) { jumpSum += ds; jumpCnt++; }
                    else { interiorGradSum += ds; interiorCnt++; }
                }
                if (mixed) boundaryCells++;
            }
        }

        System.out.println("=== 有效水面 s = resolved ? level : bed 的棋盘格检验 ===");
        System.out.printf("[1] 交界带格=%d (%.2f%% of interior)%n",
                boundaryCells, 100.0 * boundaryCells / ((n - 2.0) * (n - 2)));
        System.out.printf("[2] 交界处 |Δs| 均值=%.4f 块 | 域内(同域) |Δs| 均值=%.4f 块  比值=%.2fx%n",
                jumpCnt > 0 ? jumpSum / jumpCnt : Double.NaN,
                interiorCnt > 0 ? interiorGradSum / interiorCnt : Double.NaN,
                (jumpCnt > 0 && interiorCnt > 0)
                        ? (jumpSum / jumpCnt) / (interiorGradSum / interiorCnt) : Double.NaN);

        // [3] 方向翻转率：沿流线连续两步的 -∇s 方向点积为负的比例
        double[] dir = new double[2];
        int flips = 0, steps = 0;
        int zeros = 0;
        long state = 0x5EEDL;
        for (int t = 0; t < samples; t++) {
            state = mix(state + 0x9E3779B97F4A7C15L);
            double x = unit(state) * (n - 2) + 1.0;
            state = mix(state + 0x9E3779B97F4A7C15L);
            double z = unit(state) * (n - 2) + 1.0;
            double px = 0, pz = 0; boolean havePrev = false;
            for (int st = 0; st < 4 * (n + n); st++) {
                int i = (int) x, j = (int) z;
                if (i < 1 || j < 1 || i >= n - 1 || j >= n - 1) break;
                int k = i * n + j;
                if (!gradS(s, n, i, j, dir)) { zeros++; break; }
                if (havePrev) {
                    steps++;
                    if (dir[0] * px + dir[1] * pz < 0) flips++;
                }
                px = dir[0]; pz = dir[1]; havePrev = true;
                double sp = Math.hypot(dir[0], dir[1]);
                x += 0.5 * dir[0] / sp;
                z += 0.5 * dir[1] / sp;
            }
        }
        System.out.printf("[3] 方向翻转率=%.2f%% (%d/%d 步) | 零梯度停步=%d(%.2f%%)%n",
                100.0 * flips / Math.max(1, steps), flips, steps, zeros,
                100.0 * zeros / Math.max(1, samples));

        // [4] 交界带与 stagnant 的关系： stagnant 集中在交界带的比例（用河道格近似）
        int chBoundary = 0, chTotal = 0;
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (!r.channel()[k]) continue;
                chTotal++;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    if (res[(i + d[0]) * n + (j + d[1])] != res[k]) { chBoundary++; break; }
                }
            }
        }
        System.out.printf("[4] 河道格中紧邻交界带=%d (%.1f%% of %d)%n",
                chBoundary, 100.0 * chBoundary / Math.max(1, chTotal), chTotal);
    }

    /** -∇s 方向；返回 false 表示零梯度。 */
    private static boolean gradS(double[] s, int n, int i, int j, double[] out) {
        double gx = (s[(i + 1) * n + j] - s[(i - 1) * n + j]) / 2.0;
        double gz = (s[i * n + (j + 1)] - s[i * n + (j - 1)]) / 2.0;
        double g = Math.hypot(gx, gz);
        if (g < 1e-12) return false;
        out[0] = -gx / g; out[1] = -gz / g;
        return true;
    }

    private static double unit(long bits) { return (bits >>> 11) * 0x1.0p-53; }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}