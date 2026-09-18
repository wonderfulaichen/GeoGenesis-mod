package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.List;

/**
 * 「判据统一」可行性探针（2026-09-19）—— <b>P0-2（C4 判水判据统一）的先量后改</b>。
 *
 * <h2>背景：我们有两套判水判据，Farseek/RTF/TF-0.3.x 都只有一套</h2>
 * <pre>
 *   湖分支（GeoGenesisTerrain）：flooded = cell.height &lt; spill − 0.5     ← 绝对等高线
 *   河分支（HydrologyBlockCarver）：fillWater()（dist ≤ width 等几何条件）  ← 几何
 * </pre>
 * <p>参考实现（Farseek {@code isStreamBed = maxFloorLevel < surfaceLevel}、
 * RTF {@code finalHeight < targetWaterLevel}、TF-0.3.x 两个 {@code Math.min}）
 * <b>都只用"绝对等高线"</b>。</p>
 *
 * <h2>本探针要回答</h2>
 * <p>把河分支也改成绝对等高线判水（{@code cell.height < riverSurfaceY − 0.5}），
 * <b>地形/水体面积会变多少</b>？—— 若变化小 ⇒ 可直接统一；若大 ⇒ 须先标定。</p>
 *
 * <h2>方法（零生产改动）</h2>
 * <ol>
 *   <li>用 {@code carveColumnAt}（生产入口）取该列的计划；
 *       <b>分支判别 = {@code lakeNode() != null}</b>（湖列与被拒湖列都带 node，河列不带）；</li>
 *   <li>{@code actualWet = cell.riverType != 0}（生产实际结果，权威口径）；</li>
 *   <li>{@code contourWet = cell.height < cell.riverSurfaceY − 0.5}（统一后的判据）；</li>
 *   <li>按分支统计：一致 / 几何湿等高线干（多灌）/ 几何干等高线湿（漏灌）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runUnifiedCriterionProbe [-PprobeArgs="seed blockX blockZ halfSize step"]}</pre>
 */
public final class UnifiedCriterionProbe {

    private UnifiedCriterionProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 64;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        System.out.printf("=== UnifiedCriterionProbe seed=%d 块(%d,%d)±%d 步长=%d ===%n",
                seed, bx0, bz0, half, step);

        // 分分支统计
        long riverTotal = 0, riverAgreeWet = 0, riverAgreeDry = 0;
        long riverOver = 0, riverUnder = 0;             // 河分支：几何 vs 等高线 的分歧
        long lakeTotal = 0, lakeAgreeWet = 0, lakeAgreeDry = 0;
        long lakeOver = 0, lakeUnder = 0;
        long noPlan = 0;

        double maxUnderDepth = 0.0, maxOverDepth = 0.0;
        int shownUnder = 0, shownOver = 0;

        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                Cell cell = gt.getChunkCells(bx >> 4, bz >> 4)
                        [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];

                double original = gen.sample(bx / hs, bz / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, bx, bz, original, hs);
                if (col == null) { noPlan++; continue; }

                boolean lakeBranch = col.lakeNode() != null;
                boolean actualWet = cell.riverType != 0;
                double surf = cell.riverSurfaceY;
                boolean hasLevel = surf > 0 && !Double.isNaN(surf);
                boolean contourWet = hasLevel && cell.height < surf - 0.5;

                if (lakeBranch) {
                    lakeTotal++;
                    if (actualWet && contourWet) lakeAgreeWet++;
                    else if (!actualWet && !contourWet) lakeAgreeDry++;
                    else if (actualWet) lakeOver++;
                    else lakeUnder++;
                } else {
                    riverTotal++;
                    if (actualWet && contourWet) riverAgreeWet++;
                    else if (!actualWet && !contourWet) riverAgreeDry++;
                    else if (actualWet) {
                        riverOver++;
                        if (hasLevel) maxOverDepth = Math.max(maxOverDepth, surf - cell.height);
                    } else {
                        riverUnder++;
                        if (hasLevel) {
                            double d = surf - cell.height;
                            if (d > maxUnderDepth) maxUnderDepth = d;
                            if (shownUnder < 6) {
                                System.out.printf("    漏灌 块(%d,%d) h=%.3f surf=%.3f 差=%.3f "
                                                + "fillWater=%b lakePlan=%b%n",
                                        bx, bz, cell.height, surf, d,
                                        col.fillWater(), col.lakePlan());
                                shownUnder++;
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("%n[1] 河分支（判据 = 几何 fillWater）  列数 = %d%n", riverTotal);
        System.out.printf("    一致：湿 %d / 干 %d%n", riverAgreeWet, riverAgreeDry);
        System.out.printf("    ★ 几何湿·等高线干（多灌）= %d（%.2f%%）最大超出 %.3f 块%n",
                riverOver, 100.0 * riverOver / Math.max(1, riverTotal), maxOverDepth);
        System.out.printf("    ★ 几何干·等高线湿（漏灌）= %d（%.2f%%）最大欠灌 %.3f 块%n",
                riverUnder, 100.0 * riverUnder / Math.max(1, riverTotal), maxUnderDepth);
        System.out.printf("    ⇒ 分歧合计 = %d（%.2f%%）%n",
                riverOver + riverUnder,
                100.0 * (riverOver + riverUnder) / Math.max(1, riverTotal));

        System.out.printf("%n[2] 湖分支（判据 = 等高线 spill）  列数 = %d%n", lakeTotal);
        System.out.printf("    一致：湿 %d / 干 %d%n", lakeAgreeWet, lakeAgreeDry);
        System.out.printf("    多灌 %d / 漏灌 %d（%.2f%%）%n",
                lakeOver, lakeUnder,
                100.0 * (lakeOver + lakeUnder) / Math.max(1, lakeTotal));

        System.out.printf("%n[3] 无任何计划（carveColumnAt = null） 列数 = %d%n", noPlan);

        long total = riverTotal + lakeTotal + noPlan;
        long waterNow = riverAgreeWet + riverOver + lakeAgreeWet + lakeOver;
        long waterUnified = riverAgreeWet + riverUnder + lakeAgreeWet + lakeUnder;
        System.out.printf("%n[4] ★ 水体面积对照（采样 %d 列）%n", total);
        System.out.printf("    现状实际有水      = %d（%.2f%%）%n",
                waterNow, 100.0 * waterNow / Math.max(1, total));
        System.out.printf("    统一判据后将有水  = %d（%.2f%%）  ← 河分支改判为 cell.height < surf−0.5%n",
                waterUnified, 100.0 * waterUnified / Math.max(1, total));
        System.out.printf("    净变化            = %+d 列（%+.2f 个百分点）%n",
                waterUnified - waterNow,
                100.0 * (waterUnified - waterNow) / Math.max(1, total));

        // ---------- [5] ★★★ C3 前置测量：河谷带抬升量分布 ----------
        //   C4（判据统一）的前提 = C3（河谷两侧高于水面）。
        //   本段量：若把"低于水面"的河谷带列抬到 surf + minBank，需要抬多少、影响多少列。
        double minBank = 1.0;                       // 与 RTF minBankHeight 同量级
        java.util.ArrayList<Double> lifts = new java.util.ArrayList<>();
        long liftCols = 0;
        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                Cell cell = gt.getChunkCells(bx >> 4, bz >> 4)
                        [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                if (cell.riverSurfaceY <= 0 || Double.isNaN(cell.riverSurfaceY)) continue;
                if (cell.riverType != 0) continue;              // 已出水的不算
                double target = cell.riverSurfaceY + minBank;
                if (cell.height >= target) continue;            // 已高于水面+岸高 ⇒ 无需抬
                lifts.add(target - cell.height);
                liftCols++;
            }
        }
        if (!lifts.isEmpty()) {
            lifts.sort(null);
            double[] la = lifts.stream().mapToDouble(Double::doubleValue).toArray();
            System.out.printf("%n[5] ★ C3 河谷抬升量（目标 = surf + %.1f）%n", minBank);
            System.out.printf("    需抬升列数 = %d%n", liftCols);
            System.out.printf("    抬升量分位：p10=%.2f p50=%.2f p90=%.2f max=%.2f 块%n",
                    pct(la, 0.10), pct(la, 0.50), pct(la, 0.90), la[la.length - 1]);
            System.out.println("    判读：p50 小（<2 块）⇒ 抬升温和，C3 可做；");
            System.out.println("          p90/max 大（>5 块）⇒ 会明显改变地形，须评估视觉影响。");

            // ---------- [6] ★★★ 大抬升列的性质判别（决定 C3 能否直接做） ----------
            //   判据：大抬升列若【沿河线连续成带】⇒ 是河谷带（该抬地形，C3 正确）；
            //         若【散布/孤立】⇒ 可能是河线水面求解错误（该降水面，先修水位）。
            //   方法：对大抬升列（>5 块），统计其【邻列是否也是大抬升】—— 成带 ⇒ 邻居也多。
            java.util.HashSet<Long> bigSet = new java.util.HashSet<>();
            int bigCount = 0;
            for (int dz = -half; dz <= half; dz += step) {
                for (int dx = -half; dx <= half; dx += step) {
                    int bx = bx0 + dx, bz = bz0 + dz;
                    Cell cell = gt.getChunkCells(bx >> 4, bz >> 4)
                            [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
                    if (cell.riverSurfaceY <= 0 || Double.isNaN(cell.riverSurfaceY)) continue;
                    if (cell.riverType != 0) continue;
                    if (cell.riverSurfaceY + minBank - cell.height > 5.0) {
                        bigSet.add(((long) bx << 32) ^ (bz & 0xffffffffL));
                        bigCount++;
                    }
                }
            }
            int withBigNeighbor = 0;
            for (long k : bigSet) {
                int bx = (int) (k >> 32), bz = (int) k;
                boolean nb = bigSet.contains((((long) (bx + step)) << 32) ^ (bz & 0xffffffffL))
                        || bigSet.contains((((long) (bx - step)) << 32) ^ (bz & 0xffffffffL))
                        || bigSet.contains(((long) bx << 32) ^ ((bz + step) & 0xffffffffL))
                        || bigSet.contains(((long) bx << 32) ^ ((bz - step) & 0xffffffffL));
                if (nb) withBigNeighbor++;
            }
            System.out.printf("%n[6] ★ 大抬升列（>5 块）性质判别%n");
            System.out.printf("    大抬升列数 = %d（占需抬升列的 %.1f%%）%n",
                    bigCount, 100.0 * bigCount / Math.max(1, liftCols));
            System.out.printf("    其中【邻列也是大抬升】= %d（%.1f%%）%n",
                    withBigNeighbor, 100.0 * withBigNeighbor / Math.max(1, bigCount));
            System.out.println("    判读：比例高（>60%）⇒ 成带 ⇒ 是【河谷带】⇒ C3 该做（抬地形）；");
            System.out.println("          比例低（<40%）⇒ 散布 ⇒ 疑【河线水位求解错误】⇒ 先修水位再谈 C3。");
        }

        System.out.println();
        System.out.println("判读：");
        System.out.println("  · 若【分歧合计】很小（<5%）⇒ 统一判据低风险，可直接实施 P0-2；");
        System.out.println("  · 若【多灌】为主 ⇒ 统一会让水变多（可能淹地），须配合水位/深度标定；");
        System.out.println("  · 若【漏灌】为主 ⇒ 统一会把该有水的地方补上（正是用户看到的干河床）。");
    }

    /** 分位数（输入须已升序）。 */
    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
