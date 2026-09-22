package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 【河尾终止诊断】（2026-09-20，零生产改动）—— 用户实机反馈：<b>"河流直接以河结束"</b>
 * （下游终点既不是湖泊也不是海洋）。
 *
 * <h2>要回答的两个问题</h2>
 * <ol>
 *   <li>给定两个 block 坐标，命中的是<b>同一条河</b>还是两条不同的河？（含河头/河尾/层级/节点数）</li>
 *   <li>窗口内所有河的<b>河尾终止类型分布</b>：入海 / 入湖 / 汇入其它河 / 边界出口 /
 *       <b>★悬空陆地终止</b>（= 用户看到的那一条）。</li>
 * </ol>
 *
 * <h2>★ 为什么必须专门写这个探针</h2>
 * <p>项目里<b>早就有</b> {@code RiverOutlet.Type.LAND_SINK}（注释：<i>"异常陆地终止"</i>），
 * 但 grep 全仓确认它<b>零引用</b> —— 河尾终止类型<b>从未被记录</b>，所以这类缺陷一直不可观测。
 * 本探针用<b>几何独立口径</b>重算分类，不改生产代码。</p>
 *
 * <h2>口径（务必带上）</h2>
 * <ul>
 *   <li>wu ↔ block：{@code block = wu × horizontalScale}（hs = 2.0）。</li>
 *   <li>地形高度走 {@link RiverLineNetwork#groundYAt}（public）。本探针用无 terrainY 的构造器
 *       ⇒ 它是 {@code heightFromE(eAt)}（纯噪声地形、<b>不含侵蚀 tile</b>）——
 *       因此"入海"判据偏保守，但"悬空"结论不受影响（悬空列通常远高于海平面）。</li>
 *   <li>单变量对照：同一窗口分别用【分叉开(默认)】与【分叉关】建网 ⇒ 判定缺陷是否与 2026-09-20
 *       的支流分叉改动相关。</li>
 * </ul>
 *
 * <pre>{@code
 *   gradlew runRiverEndProbe -PprobeArgs="seed rx0 rz0 rn [blockX1 blockZ1 blockX2 blockZ2]"
 * }</pre>
 */
public final class RiverEndProbe {

    private RiverEndProbe() { }

    private static final double WU_PER_BLOCK = 2.0;   // = horizontalScale
    private static final double JOIN_DIST_WU = 32.0;  // 河尾距它河节点的"汇入"判定距离
    /** 汇合断口样本（block）：JOIN 河尾 → 它河最近节点的距离。 */
    private static final List<Double> joinGapBlocks = new ArrayList<>();
    private static final List<String> joinGapDetail = new ArrayList<>();
    private static int joinGapBig = 0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : -2;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : -2;
        int rn = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        boolean hasPts = args.length >= 8;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double seaLevel = gen.heightCurve().seaLevelY();

        System.out.printf("=== RiverEndProbe seed=%d region[%d,%d]..[%d,%d] 海平面Y=%.2f ===%n",
                seed, rx0, rz0, rx0 + rn - 1, rz0 + rn - 1, seaLevel);

        // ---------- 分叉【开】（= 生产默认） ----------
        RiverLineParams onParams = RiverLineParams.defaults();
        RiverLineNetwork.tailDiag.reset();
        List<RiverLineRegion> on = build(gen, seed, onParams, rx0, rz0, rn);
        System.out.printf("[A] 分叉开（生产默认 enabled=%s）%n", onParams.fork().enabled());
        System.out.printf("    [终止原因·生成器自记] %s%n", RiverLineNetwork.tailDiag);
        report(on, seaLevel);

        // ---------- 分叉【关】（单变量对照） ----------
        RiverLineParams offParams = RiverLineParams.defaults()
                .withFork(RiverLineParams.ForkParams.defaults().withEnabled(false));
        RiverLineNetwork.tailDiag.reset();
        List<RiverLineRegion> off = build(gen, seed, offParams, rx0, rz0, rn);
        System.out.printf("%n[B] 分叉关（对照）%n");
        System.out.printf("    [终止原因·生成器自记] %s%n", RiverLineNetwork.tailDiag);
        report(off, seaLevel);

        // ---------- 定点：两个 block 坐标属于哪条河 ----------
        if (hasPts) {
            double bx1 = Double.parseDouble(args[4]), bz1 = Double.parseDouble(args[5]);
            double bx2 = Double.parseDouble(args[6]), bz2 = Double.parseDouble(args[7]);
            System.out.printf("%n[定点] 分叉开状态下查这两点属于哪条河%n");
            Hit h1 = locate(on, bx1, bz1);
            Hit h2 = locate(on, bx2, bz2);
            printHit("点1", bx1, bz1, h1);
            printHit("点2", bx2, bz2, h2);
            if (h1 != null && h2 != null) {
                boolean same = h1.poly == h2.poly;
                System.out.printf("    ⇒ 两点命中【%s】%n", same ? "同一条河" : "两条不同的河");
                if (!same) {
                    double d = Math.hypot(h1.nodeX - h2.nodeX, h1.nodeZ - h2.nodeZ);
                    System.out.printf("       两点所在河节点相距 %.1f block%n", d);
                }
            }
        }
    }

    // ==================== 建网 ====================

    private static List<RiverLineRegion> build(CellGenerator gen, long seed, RiverLineParams p,
                                               int rx0, int rz0, int rn) {
        RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, null,
                gen.heightCurve(), seed, p);
        for (int dx = 0; dx < rn; dx++) {
            for (int dz = 0; dz < rn; dz++) net.region(rx0 + dx, rz0 + dz);
        }
        return net.cachedList();
    }

    // ==================== 河尾分类 ====================

    /**
     * 河尾终止类型。
     *
     * <p>★ 2026-09-20 口径修正（两处，都是"假信号"源）：<br>
     * ① {@code CROSS}：河尾落在本 region 盒<b>外</b>（margin 区）⇒ 属【跨区交接】情形，
     *    本探针不下判断（权威是 {@code runHandoffPickupProbe}）。把 margin 里的尾巴
     *    算成"悬空"会产出假缺陷。<br>
     * ② {@code BOUNDARY}：只认【本 region 确实发出了出口种子且就在尾巴附近】，
     *    不用几何带宽（borderDist=96wu 占 region 30% 面积，会把内陆断尾误判成正常出口）。</p>
     */
    private enum End { OCEAN, LAKE, JOIN, BOUNDARY, CROSS, DANGLING }

    private static void report(List<RiverLineRegion> regions, double seaLevel) {
        int[] cnt = new int[End.values().length];
        int rivers = 0;
        List<String> dangling = new ArrayList<>();
        joinGapBlocks.clear();
        joinGapDetail.clear();
        joinGapBig = 0;

        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                RiverLineRegion.RiverPolyline p = r.rivers.get(i);
                int n = p.nodes.length;
                if (n < 2) continue;
                rivers++;
                double tx = p.nodes[n - 1].x(), tz = p.nodes[n - 1].z();
                End e = classify(regions, r, i, p, tx, tz, seaLevel);
                cnt[e.ordinal()]++;
                // ★ 2026-09-20：汇合【断口】量化 —— JOIN 情形下，河尾到它河最近节点的距离。
                //   正解应 ≈0（交汇点共享同一个格）；实测有 21 块（≈2.6 个节点间距）的：
                //   肉眼就是"河断了 20 多块"，且两侧水面高程几乎相同 ⇒ 明显不合理。
                if (e == End.JOIN) {
                    double[] nn = nearestOther(regions, r, i, tx, tz);
                    if (!Double.isNaN(nn[0])) {
                        double gapB = nn[0] * WU_PER_BLOCK;
                        joinGapBlocks.add(gapB);
                        if (gapB > 12.0) {
                            joinGapBig++;
                            // ★ 大断口明细：对方那个节点是"河头 / 河中 / 河尾"，水位差多少
                            joinGapDetail.add(String.format(
                                    "       ★断口 %.0f block：本河 r(%d,%d)#%d 尾block(%.0f,%.0f) 尾水面=%.1f"
                                            + "  →  对方 level=%d 的 %s 在 block(%.0f,%.0f) 水面=%.1f（差 %.1f）",
                                    gapB, r.rx, r.rz, i,
                                    tx * WU_PER_BLOCK, tz * WU_PER_BLOCK, p.surfaceY[n - 1],
                                    (int) nn[2], nn[3] == 0 ? "河头" : (nn[3] == 2 ? "河尾" : "河中段"),
                                    nn[4] * WU_PER_BLOCK, nn[5] * WU_PER_BLOCK, nn[1],
                                    Math.abs(nn[1] - p.surfaceY[n - 1])));
                        }
                    }
                }
                if (e == End.DANGLING) {
                    // ★ 追加：河尾到【其它河最近节点】的距离与水位差 —— 用来区分
                    //   "真悬空"（谁都够不着）与"我的 join 判据太严"（几何上贴着却判不出）。
                    double[] near = nearestOther(regions, r, i, tx, tz);
                    String extra = Double.isNaN(near[0]) ? "  它河：窗口内无其它河"
                            : String.format("  它河最近节点：距 %.1f block，水位差 %.2f block",
                            near[0] * WU_PER_BLOCK, Math.abs(near[1] - p.surfaceY[n - 1]));
                    // 距本 region 边界：区分【内陆洼地终止】与【边界交接受阻】
                    double size = RiverLineParams.defaults().regionSize();
                    double eD = Math.min(Math.min(tx - r.rx * size, r.rx * size + size - tx),
                            Math.min(tz - r.rz * size, r.rz * size + size - tz));
                    extra += String.format("  距region边界 %.1f block（本区出口种子 %d 个）",
                            eD * WU_PER_BLOCK, r.outlets.size());
                    dangling.add(String.format("       河 r(%d,%d)#%d level=%d 节点=%d  "
                                    + "河头block(%.0f,%.0f) → ★河尾block(%.0f,%.0f)  尾水面=%.1f%n%s",
                            r.rx, r.rz, i, p.level, n,
                            p.nodes[0].x() * WU_PER_BLOCK, p.nodes[0].z() * WU_PER_BLOCK,
                            tx * WU_PER_BLOCK, tz * WU_PER_BLOCK,
                            p.surfaceY[n - 1], extra));
                }
            }
        }
        System.out.printf("    河数=%d  入海=%d  入湖=%d  汇入它河=%d  边界出口=%d  跨区(margin)=%d  "
                        + "★内陆悬空=%d（%.1f%%）%n",
                rivers, cnt[End.OCEAN.ordinal()], cnt[End.LAKE.ordinal()], cnt[End.JOIN.ordinal()],
                cnt[End.BOUNDARY.ordinal()], cnt[End.CROSS.ordinal()], cnt[End.DANGLING.ordinal()],
                100.0 * cnt[End.DANGLING.ordinal()] / Math.max(1, rivers));
        // ★ 2026-09-20：水位单调性 —— 河水不得沿程【抬升】（"水上坡"= 明显不合理）。
        //   为什么必须与"洼地续流"一起量：续流是从【盆底】跳到【盆外溢出口】，
        //   而溢出口地形（≈ spill）高于盆底 ⇒ 可能把"河断"换成"水上坡"。
        int rising = 0, riseRivers = 0;
        double worstRise = 0;
        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.RiverPolyline p : r.rivers) {
                boolean anyRise = false;
                for (int k = 1; k < p.surfaceY.length; k++) {
                    double rise = p.surfaceY[k] - p.surfaceY[k - 1];
                    if (rise > 0.01) {
                        rising++;
                        anyRise = true;
                        if (rise > worstRise) worstRise = rise;
                    }
                }
                if (anyRise) riseRivers++;
            }
        }
        // ★ 2026-09-20：闭环（马蹄形）检测 —— 用户实机截图：河流绕一个大圈自己绕回来，
        //   与自己并排、首尾相接 ⇒ 两条河道围出闭环。判据：河的【尾节点】到它自己
        //   【中前段节点】的距离很近（正常河只会离源头越来越远）。
        int loops = 0;
        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.RiverPolyline p : r.rivers) {
                int n = p.nodes.length;
                if (n < 8) continue;
                double tx = p.nodes[n - 1].x(), tz = p.nodes[n - 1].z();
                double best = Double.MAX_VALUE;
                int bestK = -1;
                for (int k = 1; k < n - 4; k++) {
                    double d = Math.hypot(p.nodes[k].x() - tx, p.nodes[k].z() - tz);
                    if (d < best) { best = d; bestK = k; }
                }
                if (best * WU_PER_BLOCK <= 48.0) {      // 尾部回到 48 block 内 ⇒ 闭环
                    loops++;
                    System.out.printf("       ★闭环：r(%d,%d) level=%d 节点=%d  尾block(%.0f,%.0f) "
                                    + "回到自身第 %d/%d 节点 block(%.0f,%.0f) 附近（距 %.0f block）%n",
                            r.rx, r.rz, p.level, n,
                            tx * WU_PER_BLOCK, tz * WU_PER_BLOCK,
                            bestK, n - 1,
                            p.nodes[bestK].x() * WU_PER_BLOCK, p.nodes[bestK].z() * WU_PER_BLOCK,
                            best * WU_PER_BLOCK);
                }
            }
        }
        System.out.printf("    ★闭环（马蹄形）河 = %d 条%n", loops);
        System.out.printf("    ★水位抬升（水上坡）：抬升节点数 = %d，涉及河 = %d 条，最大抬升 = %.2f block%n",
                rising, riseRivers, worstRise);
        System.out.printf("    ★汇合断口：JOIN %d 条中，断口 >12 block 的 = %d（%.1f%%）；"
                        + "断口 p50=%.0f  p90=%.0f  max=%.0f block%n",
                joinGapBlocks.size(), joinGapBig, pct(joinGapBig, joinGapBlocks.size()),
                q(joinGapBlocks, 0.5), q(joinGapBlocks, 0.9), max(joinGapBlocks));
        for (String s : joinGapDetail) System.out.println(s);
        for (String s : dangling) System.out.println(s);
    }

    /**
     * 河尾终止类型（几何独立口径）：
     *
     * <ol>
     *   <li>河尾地形 ≤ 海平面 ⇒ <b>入海</b>；</li>
     *   <li>河尾落在任何湖（本 region 或邻 region）的洼地轮廓附近 ⇒ <b>入湖</b>；</li>
     *   <li>河尾 32wu 内有<b>别的河</b>的节点且水面相当 ⇒ <b>汇入它河</b>；</li>
     *   <li>河尾距 region 边界 &lt; borderDist ⇒ <b>边界出口</b>（应交邻区续流）；</li>
     *   <li>以上都不是 ⇒ <b>★悬空</b>（用户看到的那一条）。</li>
     * </ol>
     */
    private static End classify(List<RiverLineRegion> regions,
                                RiverLineRegion own, int ownIdx,
                                RiverLineRegion.RiverPolyline p,
                                double tx, double tz, double seaLevel) {
        // ★ 入海判据（2026-09-20 修正）：必须看【水面】（commitRiver 里
        //   `outletSurf = curve.seaLevelY()` 的两条路径：reachedOcean / 交汇点地形低于海平面）
        //   —— 只看 build 时刻"地形"会把【河口向海延伸段】（地形仍在水面之上）误判成悬空。
        double tailGround = (p.terrainY != null && p.terrainY.length == p.nodes.length)
                ? p.terrainY[p.nodes.length - 1] : Double.NaN;
        double tailSurf0 = p.surfaceY[p.nodes.length - 1];
        if (tailSurf0 <= seaLevel + 0.5) return End.OCEAN;
        if (!Double.isNaN(tailGround) && tailGround <= seaLevel + 0.5) return End.OCEAN;

        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.LakeNode lk : r.lakes) {
                if (lk.cellX != null) {
                    for (int k = 0; k < lk.cellX.length; k++) {
                        if (Math.hypot(lk.cellX[k] - tx, lk.cellZ[k] - tz) <= 24.0) return End.LAKE;
                    }
                } else if (Math.hypot(lk.x - tx, lk.z - tz) <= lk.radius + 24.0) {
                    return End.LAKE;
                }
            }
        }

        // ★ 汇入判据（2026-09-20 修正）：河尾【落在它河中心线上】即判定为汇入 —— 不再要求
        //   水位差 ≤2 block。原因：节点间距 SMOOTH_SPACING=4wu=8 block，且陡段/跌水处水面
        //   梯度很大 ⇒ "最近节点"与"实际交汇点"水位天然可差 3~5 block（实测 3.57 / 4.75），
        //   旧判据会把真汇入误判成悬空。
        double tailSurf = p.surfaceY[p.nodes.length - 1];
        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                if (r == own && i == ownIdx) continue;
                RiverLineRegion.RiverPolyline q = r.rivers.get(i);
                for (int k = 0; k < q.nodes.length; k++) {
                    double d = Math.hypot(q.nodes[k].x() - tx, q.nodes[k].z() - tz);
                    if (d <= 16.0 / WU_PER_BLOCK) return End.JOIN;               // ≈2 个节点间距内
                    if (d <= JOIN_DIST_WU && Math.abs(q.surfaceY[k] - tailSurf) <= 2.0) return End.JOIN;
                }
            }
        }

        // ④ 边界出口：★ 用【生成器自己的出口种子表】判定，而不是几何带宽。
        //    理由：几何带宽（borderDist=96wu=192 block）占 region 30% 面积，会把"本该
        //    交接给邻区、但实际没接上"的断尾误判成正常出口 —— 那正是用户看到的症状之一。
        //    只有"本 region 确实发出了一个就在河尾附近的出口种子"才算正常交接。
        if (own != null) {
            for (RiverLineRegion.OutletSeed s : own.outlets) {
                if (Math.hypot(s.wx - tx, s.wz - tz) <= 48.0) return End.BOUNDARY;
            }
        }
        // ⑤ 尾巴在 region 盒外（margin 区）⇒ 跨区交接情形，本探针不判。
        double ownSize = RiverLineParams.defaults().regionSize();
        double eD = Math.min(Math.min(tx - own.rx * ownSize, own.rx * ownSize + ownSize - tx),
                Math.min(tz - own.rz * ownSize, own.rz * ownSize + ownSize - tz));
        if (eD < 0.0) return End.CROSS;
        return End.DANGLING;
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0.0 : 100.0 * a / b;
    }

    /** 分位数（拷贝排序，不改原表）。 */
    private static double q(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<>(v);
        java.util.Collections.sort(c);
        int i = (int) Math.min(c.size() - 1, Math.max(0, Math.floor(p * (c.size() - 1))));
        return c.get(i);
    }

    private static double max(List<Double> v) {
        double m = Double.NaN;
        for (double d : v) if (Double.isNaN(m) || d > m) m = d;
        return m;
    }

    /**
     * 河尾到【其它河】最近节点：{距离(wu), 该节点水面Y, 对方 level, 对方节点位置(0=头/1=中/2=尾),
     * 对方节点 x(wu), z(wu)}；没有其它河时 dist=NaN。
     */
    private static double[] nearestOther(List<RiverLineRegion> regions,
                                         RiverLineRegion own, int ownIdx,
                                         double tx, double tz) {
        double bestD = Double.MAX_VALUE, bestSurf = Double.NaN, bestX = 0, bestZ = 0;
        int bestLevel = -1, bestKind = 1;
        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                if (r == own && i == ownIdx) continue;
                RiverLineRegion.RiverPolyline q = r.rivers.get(i);
                int m = q.nodes.length;
                for (int k = 0; k < m; k++) {
                    double d = Math.hypot(q.nodes[k].x() - tx, q.nodes[k].z() - tz);
                    if (d < bestD) {
                        bestD = d; bestSurf = q.surfaceY[k]; bestLevel = q.level;
                        bestKind = (k == 0) ? 0 : (k == m - 1 ? 2 : 1);
                        bestX = q.nodes[k].x(); bestZ = q.nodes[k].z();
                    }
                }
            }
        }
        return new double[]{bestD == Double.MAX_VALUE ? Double.NaN : bestD, bestSurf,
                bestLevel, bestKind, bestX, bestZ};
    }

    // ==================== 定点查找 ====================

    private static final class Hit {
        RiverLineRegion.RiverPolyline poly;
        RiverLineRegion region;
        int index, nodeIndex;
        double dist, nodeX, nodeZ;
    }

    private static Hit locate(List<RiverLineRegion> regions, double bx, double bz) {
        double wx = bx / WU_PER_BLOCK, wz = bz / WU_PER_BLOCK;
        Hit best = null;
        for (RiverLineRegion r : regions) {
            for (int i = 0; i < r.rivers.size(); i++) {
                RiverLineRegion.RiverPolyline p = r.rivers.get(i);
                for (int k = 0; k < p.nodes.length; k++) {
                    double d = Math.hypot(p.nodes[k].x() - wx, p.nodes[k].z() - wz);
                    if (best == null || d < best.dist) {
                        best = new Hit();
                        best.poly = p; best.region = r; best.index = i;
                        best.nodeIndex = k; best.dist = d;
                        best.nodeX = p.nodes[k].x(); best.nodeZ = p.nodes[k].z();
                    }
                }
            }
        }
        return best;
    }

    private static void printHit(String label, double bx, double bz, Hit h) {
        if (h == null) {
            System.out.printf("    %s block(%.0f,%.0f)：窗口内无河%n", label, bx, bz);
            return;
        }
        RiverLineRegion.RiverPolyline p = h.poly;
        int n = p.nodes.length;
        System.out.printf("    %s block(%.0f,%.0f)：r(%d,%d)#%d 节点idx=%d 距 %.1f block；"
                        + "该河 level=%d 节点=%d 半宽=%.2f%n",
                label, bx, bz, h.region.rx, h.region.rz, h.index, h.nodeIndex,
                h.dist * WU_PER_BLOCK, p.level, n, p.width[h.nodeIndex]);
        System.out.printf("        河头block(%.0f,%.0f)  河尾block(%.0f,%.0f)  "
                        + "本节点在 [头..尾] 的第 %d/%d 个%n",
                p.nodes[0].x() * WU_PER_BLOCK, p.nodes[0].z() * WU_PER_BLOCK,
                p.nodes[n - 1].x() * WU_PER_BLOCK, p.nodes[n - 1].z() * WU_PER_BLOCK,
                h.nodeIndex, n - 1);
    }
}
