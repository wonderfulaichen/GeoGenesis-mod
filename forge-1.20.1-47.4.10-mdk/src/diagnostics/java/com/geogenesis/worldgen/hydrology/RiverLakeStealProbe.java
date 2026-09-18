package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.List;

/**
 * 「湖抢河」探针（2026-09-19）—— <b>量化 2026-09-19 湖岸修复对河流的副作用</b>。
 *
 * <h2>要验证的机制（读 {@code HydrologyBlockCarver.carveColumn} 得出，须实测确认）</h2>
 * <p>{@code carveColumn} 的湖分支入口是"<b>samples 里是否存在湖命中</b>"：</p>
 * <pre>
 *   HydrologyBlockSample lakeSample = null;
 *   for (HydrologyBlockSample s : samples) if (s.isLake() && ...) lakeSample = s;
 *   if (lakeSample != null) { ...湖分支：carved = original（不雕刻）... }
 * </pre>
 * <p>而 2026-09-19 的湖岸修复把 {@code RiverLineNetwork.sampleRegion} 里湖命中的加入条件
 * 由 {@code inDomain && lakeDist <= bestRiverDist} 放宽为 {@code inDomain}
 * ⇒ <b>那些"离湖比离河远、但在湖域内"的列，samples 里【多出了湖命中】</b>
 * ⇒ 它们<b>从河分支被拉进湖分支</b> ⇒ {@code carved = original} ⇒ <b>河道不再下切</b>。</p>
 *
 * <h2>本探针输出</h2>
 * <ol>
 *   <li>含河命中的列数；</li>
 *   <li>其中 samples <b>同时含湖命中</b>的列数（= 会被湖分支抢走的列）；</li>
 *   <li>被抢列中，最近河命中 {@code dist ≤ width} 的列数（= <b>本来就在河道内</b>、本该被雕刻）；</li>
 *   <li>最大被抢河道宽度损失（width − dist）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runRiverLakeStealProbe [-PprobeArgs="seed blockX blockZ halfSize step"]}</pre>
 */
public final class RiverLakeStealProbe {

    private RiverLakeStealProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        System.out.printf("=== RiverLakeStealProbe seed=%d 区域 块(%d,%d)±%d 步长=%d ===%n",
                seed, bx0, bz0, half, step);

        long total = 0, withRiver = 0, mixed = 0;
        long stolenInChannel = 0, stolenOutside = 0;
        double maxLoss = 0.0;
        int shown = 0;

        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                total++;
                List<HydrologyBlockSample> samples =
                        engine.sampleBlockAll(bx, bz, hs);
                if (samples.isEmpty()) continue;

                boolean hasLake = false;
                double nearestRiverDist = Double.POSITIVE_INFINITY;
                double nearestRiverWidth = 0.0;
                boolean hasRiver = false;
                for (HydrologyBlockSample s : samples) {
                    if (s.isLake()) {
                        hasLake = true;
                    } else {
                        hasRiver = true;
                        if (s.distToCenter() < nearestRiverDist) {
                            nearestRiverDist = s.distToCenter();
                            nearestRiverWidth = s.width();
                        }
                    }
                }
                if (!hasRiver) continue;
                withRiver++;

                if (!hasLake) continue;              // 无湖命中 ⇒ 河分支，不受本次改动影响
                mixed++;                             // ★ 河 + 湖 同时命中 ⇒ 被拉进湖分支

                // 单位说明：sampleBlockAll 已把 width 换算成 block（hit.width() * scale）
                double loss = nearestRiverWidth - nearestRiverDist;
                if (loss > 0) {
                    stolenInChannel++;               // ★ 本来就在河道内
                    if (loss > maxLoss) maxLoss = loss;
                } else {
                    stolenOutside++;
                }
                if (loss > 0 && shown < 12) {
                    System.out.printf("    块(%d,%d)：最近河 dist=%.2f width=%.2f "
                                    + "⇒ ★河道内被抢（本应下切 %.2f 块深）%n",
                            bx, bz, nearestRiverDist, nearestRiverWidth, loss);
                    shown++;
                }
            }
        }

        System.out.printf("%n[统计]%n");
        System.out.printf("    采样列          = %d%n", total);
        System.out.printf("    含河命中        = %d%n", withRiver);
        System.out.printf("    ★ 河+湖 双命中  = %d（%.1f%% of 含河列）← 被拉进湖分支%n",
                mixed, 100.0 * mixed / Math.max(1, withRiver));
        System.out.printf("    ★ 其中【在河道内】(dist ≤ width) = %d ← 本该被雕刻，现 carved=original%n",
                stolenInChannel);
        System.out.printf("      在河道外      = %d%n", stolenOutside);
        System.out.printf("    最大河道宽度损失 = %.2f 块%n", maxLoss);

        // ---- ★ 雕刻层验证（真正被修的那一层）----
        //   上面统计的是【网络层】（samples 里是否有湖命中），与本次修复无关。
        //   修复在 HydrologyBlockCarver.carveColumn 的【分支决策】：
        //   河道内（dist ≤ width）时不得走湖分支。
        //   故这里直接调用生产入口 carveColumnAt，看这些列现在走哪条分支。
        long stillStolen = 0, nowCarved = 0;
        int shown2 = 0;
        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                double original = gen.sample(bx / hs, bz / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, bx, bz, original, hs);
                if (col == null) continue;
                // 只在"该列处于河道内、且网络层含湖命中"时判
                List<HydrologyBlockSample> samples = engine.sampleBlockAll(bx, bz, hs);
                boolean hasLake = false;
                double rd = Double.POSITIVE_INFINITY, rw = 1.0;
                for (HydrologyBlockSample s : samples) {
                    if (s.isLake()) hasLake = true;
                    else if (s.distToCenter() < rd) { rd = s.distToCenter(); rw = Math.max(s.width(), 1.0); }
                }
                if (!hasLake || rd > rw) continue;      // 只看"河道内 + 有湖命中"的列
                boolean carvedDown = col.carvedGroundY() < col.originalGroundY() - 1e-6;
                if (col.lakePlan() || !carvedDown) {
                    stillStolen++;
                    if (shown2 < 8) {
                        System.out.printf("    ★ 仍被抢 块(%d,%d)：lakePlan=%b carved=%.2f orig=%.2f%n",
                                bx, bz, col.lakePlan(), col.carvedGroundY(), col.originalGroundY());
                        shown2++;
                    }
                } else {
                    nowCarved++;
                }
            }
        }
        System.out.printf("%n[雕刻层验证]（carveColumnAt 生产入口）%n");
        System.out.printf("    ★ 仍被湖分支抢走（lakePlan 或未下切） = %d%n", stillStolen);
        System.out.printf("    ✅ 已回归河分支并下切                = %d%n", nowCarved);
        System.out.println();
        System.out.println("判读：『仍被抢』应为 0 ⇒ 河道内列已恢复下切。");
    }
}
