package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 整幅图一致性：两个不同半径窗口，在【重叠区域】逐格比较水位 / 湖 / 河。
 *
 * <h2>为什么要做（而不是只看中心点）</h2>
 * <p>此前 {@link PipelineEndToEndProbe} 只比了中心一个格。中心格一致<b>不能推出</b>
 * 整幅图一致——尤其在窗口边界附近，未决格与截断效应都会显现。
 * 这条判据是"按需查询可否分片"的最后一道数值关口。</p>
 *
 * <h2>对齐是前提（必须严格）</h2>
 * <p>两个窗口的网格索引必须映射到<b>同一世界坐标</b>再比较。做法：格子 k 的世界坐标
 * 由 {@code (center - radius + i*step, center - radius + j*step)} 给出，
 * 因此比较时按块坐标求交，而非按数组下标。
 * （此前 {@code WindowIndependenceProbe} 早期版本因采样框随半径变化而"比较列全空"，
 * 正是没做坐标对齐。）</p>
 *
 * <h2>判据</h2>
 * <ul>
 *   <li><b>两窗口都可信</b>的格：水位必须逐位相同（差 &lt; 1e-9）。</li>
 *   <li><b>只有一侧可信</b>：计入"未决"，不算失败，但必须报告比例。</li>
 *   <li>湖/河的布尔量一致率。</li>
 * </ul>
 */
public final class PipelineOverlapProbe {

    public static void main(String[] args) throws Exception {
        long seed = 9139912035078620160L;
        int step = 4;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        // 小窗口与大窗口同中心。重叠区 = 小窗口全域。
        int[][] pairs = {{128, 512}, {128, 1024}, {256, 1024}};
        System.out.println("=== overlap-region consistency of the whole pipeline ===");
        System.out.println("small/large | compared | both-resolved | level mismatch | lake mismatch | channel mismatch");
        for (int[] pair : pairs) {
            int rs = pair[0], rl = pair[1];
            var small = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(-140, 137, rs, step, climate));
            var large = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(-140, 137, rl, step, climate));

            int compared = 0, bothResolved = 0, levelMismatch = 0;
            int lakeMismatch = 0, channelMismatch = 0, oneSided = 0;
            double worstLevel = 0;
            // 遍历小窗口每格，映射到世界块坐标，再定位到大窗口索引。
            for (int i = 0; i < small.nx(); i++) {
                for (int j = 0; j < small.nz(); j++) {
                    int ks = i * small.nx() + j;
                    long wx = -140 - rs + i * (long) step;
                    long wz = 137 - rs + j * (long) step;
                    long li = (wx - (-140 - rl)) / step;
                    long lj = (wz - (137 - rl)) / step;
                    if (li < 0 || lj < 0 || li >= large.nx() || lj >= large.nz()) continue;
                    int kl = (int) (li * large.nx() + lj);
                    compared++;
                    boolean both = small.resolved()[ks] && large.resolved()[kl];
                    if (!both) { oneSided++; continue; }
                    bothResolved++;
                    double d = Math.abs(small.level()[ks] - large.level()[kl]);
                    if (d >= 1e-9) { levelMismatch++; worstLevel = Math.max(worstLevel, d); }
                    if (small.lake()[ks] != large.lake()[kl]) lakeMismatch++;
                    if (small.channel()[ks] != large.channel()[kl]) channelMismatch++;
                }
            }
            double pct = 100.0 * bothResolved / Math.max(1, compared);
            System.out.printf("r%4d/r%4d | %8d | %8d (%5.1f%%) | %13d | %12d | %14d  worstLevelDiff=%.3e%n",
                    rs, rl, compared, bothResolved, pct, levelMismatch, lakeMismatch,
                    channelMismatch, worstLevel);
        }
        System.out.println("READ: both-resolved 的格里 levelMismatch 应为 0 ⇒ 重叠区完全一致，可分片。");
        System.out.println("LIMIT: 仅测同中心、step=4、三组半径，且气候单点；其它配置未验证。");
    }

    private PipelineOverlapProbe() { }
}
