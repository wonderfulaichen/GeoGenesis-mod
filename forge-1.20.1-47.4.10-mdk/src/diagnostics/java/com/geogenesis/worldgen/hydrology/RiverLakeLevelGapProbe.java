package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 「河湖水位差」探针（2026-09-19）—— <b>架构第二刀（水位来源收敛）的前置测量</b>。
 *
 * <h2>背景：我们有三套水位基准</h2>
 * <pre>
 *   海：seaLevel 常数
 *   湖：LakeNode.spill（每个湖一个，region 内局部求解）
 *   河：RiverPolyline 逐节点包络（PAVA 单调纵剖面）
 * </pre>
 * <p>参考实现（RTF {@code Levels.water} 单一基准 / Farseek {@code Segment.surfaceLevel}
 * 每段一个字段 + 递推 / PL-RGA 海平面）<b>都只有一套</b>。</p>
 *
 * <h2>要回答</h2>
 * <p>在【河湖同时命中】的列上，河的水面与湖的水位<b>差多少</b>？
 * 若差大 ⇒ 河湖交汇处会有落差/台阶（"三套基准"的直接可见代价）；
 * 若差小 ⇒ 三套基准实际一致，无需强行收敛。</p>
 *
 * <pre>{@code gradlew runRiverLakeLevelGapProbe [-PprobeArgs="seed bx bz half step"]}</pre>
 */
public final class RiverLakeLevelGapProbe {

    private RiverLakeLevelGapProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int half = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        System.out.printf("=== RiverLakeLevelGapProbe seed=%d 块(%d,%d)±%d 步长=%d ===%n",
                seed, bx0, bz0, half, step);

        List<Double> gaps = new ArrayList<>();      // 河水面 − 湖水位
        long doubleHit = 0, riverOnly = 0, lakeOnly = 0, none = 0;
        long bigGap = 0;                            // |差| > 1 块
        long hugeGap = 0;                           // |差| > 4 块
        int shown = 0;
        long aboveLakeCols = 0, suppressed = 0, carvedOk = 0, riverNoNeed = 0;   // 湖域抑制河雕刻

        for (int dz = -half; dz <= half; dz += step) {
            for (int dx = -half; dx <= half; dx += step) {
                int bx = bx0 + dx, bz = bz0 + dz;
                List<HydrologyBlockSample> samples = engine.sampleBlockAll(bx, bz, hs);
                if (samples == null || samples.isEmpty()) { none++; continue; }

                double riverSurf = Double.NaN, riverDist = Double.POSITIVE_INFINITY;
                double lakeSurf = Double.NaN, lakeDist = Double.POSITIVE_INFINITY;
                for (HydrologyBlockSample s : samples) {
                    if (s.isLake()) {
                        if (s.distToCenter() < lakeDist) {
                            lakeDist = s.distToCenter();
                            lakeSurf = s.surfaceY();
                        }
                    } else {
                        if (s.distToCenter() < riverDist) {
                            riverDist = s.distToCenter();
                            riverSurf = s.surfaceY();
                        }
                    }
                }
                boolean hasR = !Double.isNaN(riverSurf), hasL = !Double.isNaN(lakeSurf);
                if (hasR && hasL) {
                    double g = riverSurf - lakeSurf;
                    gaps.add(g);
                    doubleHit++;
                    if (Math.abs(g) > 1.0) bigGap++;
                    if (Math.abs(g) > 4.0) hugeGap++;
                    if (shown < 6 && Math.abs(g) > 4.0) {
                        System.out.printf("    块(%d,%d) 河面=%.3f(距%.1f) 湖位=%.3f(距%.1f) 差=%+.3f%n",
                                bx, bz, riverSurf, riverDist, lakeSurf, lakeDist, g);
                        shown++;
                    }
                    // ★★★ 决定性判据：该列是否【本该被河雕刻、却被湖分支抑制】？
                    //   条件：① 在河道外（dist > width，河道内已由 ⑤⑥ 统一处理）
                    //         ② 在河谷命中范围内（有河命中 ⇒ 已在 out 里）
                    //         ③ 地形【高于湖面】（不是水下 ⇒ 是陆地上该有的河谷）
                    //         ④ 实际 carved == original（一点没挖 ⇒ 被湖分支抑制）
                    double rivW = 1.0;
                    for (HydrologyBlockSample s : samples) {
                        if (!s.isLake()) { rivW = Math.max(s.width(), 1.0); break; }
                    }
                    if (rivDistOk(riverDist, rivW)) {
                        double original = gen.sample(bx / hs, bz / hs).height;
                        HydrologyBlockCarvedColumn col =
                                HydrologyBlockCarver.carveColumnAt(engine, bx, bz, original, hs);
                        if (col != null) {
                            boolean aboveLake = original > lakeSurf;
                            if (aboveLake) {
                                aboveLakeCols++;
                                // ★★★ 判据修正（勿重犯）：`carved == original` **不等于**"被湖抑制"——
                                //   河分支在 original ≤ bedTarget 时同样不挖。必须用【分支归属】判别：
                                //     lakeNode() != null ⇒ 走的是湖分支（湖不挖地）⇒ 真·被抑制
                                //     lakeNode() == null ⇒ 走河分支；此时再看是否挖了
                                boolean lakeTook = col.lakeNode() != null;
                                boolean carvedDown = col.carvedGroundY() < original - 1e-6;
                                if (lakeTook) {
                                    suppressed++;
                                } else if (carvedDown) {
                                    carvedOk++;
                                } else {
                                    riverNoNeed++;      // 河分支，但目标已在地形之下 ⇒ 无需下切
                                }
                            }
                        }
                    }
                } else if (hasR) riverOnly++;
                else if (hasL) lakeOnly++;
                else none++;
            }
        }

        long total = doubleHit + riverOnly + lakeOnly + none;
        System.out.printf("%n[1] 命中构成（采样 %d 列）%n", total);
        System.out.printf("    ★ 河湖双命中 = %d（%.1f%%）%n", doubleHit, 100.0 * doubleHit / Math.max(1, total));
        System.out.printf("    仅河 = %d；仅湖 = %d；无命中 = %d%n", riverOnly, lakeOnly, none);

        if (gaps.isEmpty()) {
            System.out.println("    ⚠ 无双命中列 —— 换区域");
            return;
        }
        gaps.sort(null);
        double[] g = gaps.stream().mapToDouble(Double::doubleValue).toArray();
        System.out.printf("%n[2] 河水面 − 湖水位（n=%d）%n", g.length);
        System.out.printf("    p10=%+.3f p50=%+.3f p90=%+.3f  最小=%+.3f 最大=%+.3f%n",
                pct(g, 0.10), pct(g, 0.50), pct(g, 0.90), g[0], g[g.length - 1]);
        System.out.printf("    ★ |差|>1 块 = %d（%.1f%%）；|差|>4 块 = %d（%.1f%%）%n",
                bigGap, 100.0 * bigGap / g.length, hugeGap, 100.0 * hugeGap / g.length);

        // ---------- [3] ★★★★ 决定性：湖域是否抑制了【河】的雕刻 ----------
        System.out.printf("%n[3] ★★ 湖域抑制河雕刻（河道外 + 地形高于湖面 = 陆地上的河谷）%n");
        System.out.printf("    符合条件列数 = %d%n", aboveLakeCols);
        System.out.printf("    ★ 被湖分支接管（lakeNode != null ⇒ 湖不挖地） = %d（%.1f%%）%n",
                suppressed, 100.0 * suppressed / Math.max(1, aboveLakeCols));
        System.out.printf("    ✅ 走河分支且已下切（carved < original）        = %d（%.1f%%）%n",
                carvedOk, 100.0 * carvedOk / Math.max(1, aboveLakeCols));
        System.out.printf("    ○ 走河分支但无需下切（original ≤ bedTarget）  = %d（%.1f%%）%n",
                riverNoNeed, 100.0 * riverNoNeed / Math.max(1, aboveLakeCols));
        System.out.println("    判读：若『未雕刻』占比高 ⇒ 该区域本应有河谷却被湖域抑制 ⇒ 湖域过大是【形貌缺陷】；");
        System.out.println("          若极低 ⇒ 湖域与河谷带几乎不重叠 ⇒ 湖域大小不构成缺陷。");

        System.out.println();
        System.out.println("判读：");
        System.out.println("  · |差| p50 小（<1 块）⇒ 三套基准实际一致，无需强行收敛（第二刀降级）；");
        System.out.println("  · |差| 大 ⇒ 河湖交汇处存在水位跃变（可见落差/台阶）⇒ 第二刀（收敛）有价值；");
        System.out.println("  · 方向一致（多为同号）⇒ 存在【系统性偏移】⇒ 调基准即可对齐；");
        System.out.println("    方向混杂（正负各半）⇒ 语义不同（河=纵向剖面、湖=盆地出口）⇒ 须按枢纽处取小/取大。");
    }

    /** 该列是否在【河道外】（两量同源于同一 sample，单位一致）。 */
    private static boolean rivDistOk(double riverDist, double riverWidth) {
        return riverDist > riverWidth;
    }

    private static double pct(double[] sorted, double q) {
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
