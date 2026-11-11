package com.geogenesis.worldgen.hydrology.model;

/**
 * 连续速度场河网的【解析自检】：均匀坡面上汇流量必须与上游面积成正比。
 *
 * <h2>为什么这条检验有效</h2>
 * <p>床面为恒定坡度的斜面上覆均匀水深，则 ∇η 处处相同、方向恒定
 * ⇒ 所有流线平行且沿 +x 前进。此时"第 i 列的上游面积"有闭式：
 * 起点在 [1, nx-2) 均匀，落进第 i 列的流线数期望 = {@code N·i/(nx-3)}。</p>
 * <p>因此可以把蒙特卡洛估计与解析值逐列比较，并检查<b>样本增加时误差下降</b>。
 * 这验证的是估计器与其闭式期望一致，<b>不是</b>真实地形上的水文真解。</p>
 *
 * <h2>不能证明的事</h2>
 * <p>不含水深反馈、湖面静止、河槽下切、动量、跨窗上游。真值只在"平行流线"这一
 * 特定构型下成立。</p>
 */
public final class ContinuousRiverAnalyticProbe {

    public static void main(String[] args) {
        int nx = 64, nz = 32;
        double cellSize = 1.0, slope = 0.01, depth = 1.0;
        double[] bed = new double[nx * nz];
        double[] level = new double[nx * nz];
        boolean[] resolved = new boolean[nx * nz];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                bed[k] = -slope * i * cellSize;
                level[k] = bed[k] + depth;
                resolved[k] = true;
            }
        }
        var params = ContinuousRiverNetwork.Params.forRunoff(1.0);
        int startColumns = nx - 3;              // floor(x0) 可取 1..nx-3
        int startRows = nz - 3;                 // floor(z0) 可取 1..nz-3（流线不下横向移动）
        System.out.println("=== continuous-velocity river network: analytic check on a uniform plane ===");
        System.out.printf("grid=%dx%d cellSize=%.1f slope=%.3f depth=%.2f startColumns=%d startRows=%d%n",
                nx, nz, cellSize, slope, depth, startColumns, startRows);
        System.out.println("samples   meanRelErr   maxRelErr   crossings(4)/crossings(16)  analytic=4/16=0.2500");

        int[] counts = {50_000, 200_000, 800_000};
        for (int samples : counts) {
            var r = ContinuousRiverNetwork.build(bed, level, resolved, nx, nz, cellSize,
                    params, samples, 12345L, 0);
            double worst = 0, sumRel = 0;
            int compared = 0;
            double perCrossing = params.runoffPerCell() * (nx * (double) nz) / samples;
            int row = nz / 2;
            for (int i = 1; i <= startColumns; i++) {
                // ★ 期望必须含【行分布】：起点在 nz-3 行上均匀，流线只在自身行内前进
                //   ⇒ 单行的过线数 = 总数 × (i/startColumns) × (1/startRows)。
                //   （此前漏掉 1/startRows，得到约 96.7% 的假误差 —— 又是判据口径错。）
                double expectedCrossings = (double) samples * i / startColumns / startRows;
                double gotCrossings = r.flux()[i * nz + row] / perCrossing;
                if (expectedCrossings < 3) continue;
                double rel = Math.abs(gotCrossings - expectedCrossings) / expectedCrossings;
                worst = Math.max(worst, rel);
                sumRel += rel;
                compared++;
            }
            double c4 = r.flux()[4 * nz + row] / perCrossing;
            double c16 = r.flux()[16 * nz + row] / perCrossing;
            System.out.printf("%7d   %.5f      %.5f     %.4f%n",
                    samples, sumRel / Math.max(1, compared), worst, c4 / c16);
        }
        System.out.println("READ: 平均/最大相对误差应随样本增加而下降；比值应趋近 0.2500。");
        System.out.println("SCOPE: estimator vs its closed-form expectation on parallel streamlines only.");
        System.out.println("NOT VERIFIED: depth feedback, lake backwater, channel incision, momentum, cross-window upstream.");
    }

    private ContinuousRiverAnalyticProbe() { }
}
