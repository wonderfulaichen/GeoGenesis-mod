package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨 region 续流（handoff）拾取安全网。
 *
 * <p><b>要回答的问题</b>：A 区每条流到缝口的河都会发出一个 {@link RiverLineRegion.OutletSeed}；
 * 邻区 B 是否真的接上了它？若 B 因 {@code claimed} / {@code tooClose} / 追踪失败 /
 * 长度不足而把种子丢弃，那条河就会在 region 缝上<b>凭空截断</b>——这是比"源头贴近邻河"
 * 严重得多的缺陷（用户可见：河流画到一半消失）。</p>
 *
 * <p>本探针专门量化该风险，作为改动 handoff 逻辑前的回归安全网。它刻意与
 * {@code RiverLineBoundaryProbe} 分工不同：那个测【渲染层跨 chunk 一致性】
 * （雕刻台阶、水面台阶），本探针测【拓扑层是否接上】。</p>
 *
 * <p>两个独立指标，避免用单一"距离阈值"造成误判：</p>
 * <ul>
 *   <li><b>headPickup</b>：B 区存在某条河，其<b>上游端</b>落在种子附近 → 正常续流；</li>
 *   <li><b>anyPickup</b>：B 区存在某条河<b>沿途经过</b>种子附近 → 干流仍在，但缝上
 *       可能出现"两条线头对不上"的错位，需单独统计；</li>
 *   <li>两者皆无 = <b>lost</b>：真正的跨缝断流。</li>
 * </ul>
 *
 * <p>水面错层：取 B 区那条河上【距种子最近的节点】的水面 Y，与种子携带的
 * {@code surfaceY} 比较。续流设计上应精确继承（差 0），任何显著差值都说明交接
 * 数据没被用上。</p>
 *
 * <p>用法：{@code gradlew runHandoffPickupProbe -PprobeArgs="12345"}</p>
 */
public final class HandoffPickupProbe {

    private HandoffPickupProbe() { }

    /** 拾取判定半径（wu）：一个 gridCell 24 + bestHandoffStart 允许的 1 格偏移。 */
    private static final double PICK_TOL = 60.0;
    /** 水面错层告警阈值（block）。 */
    private static final double SURFACE_WARN = 0.5;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int radius = args.length > 1 ? Math.max(1, Integer.parseInt(args[1])) : 1;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineParams P = RiverLineParams.defaults();

        int seeds = 0, headPick = 0, anyOnly = 0, lost = 0, misaimed = 0;
        double worstHeadDist = 0.0, worstSurface = 0.0;
        int surfaceBad = 0;
        double sumHeadDist = 0.0;
        List<String> lostList = new ArrayList<>();
        List<String> surfaceList = new ArrayList<>();

        for (int rz = -radius; rz <= radius; rz++) {
            for (int rx = -radius; rx <= radius; rx++) {
                // 发方取 pass-1：出口种子是 pass-1 产物（pass-2 是消费方）
                RiverLineRegion from = engine.network().regionPass1(rx, rz);
                for (RiverLineRegion.OutletSeed s : from.outlets) {
                    int nrx = rx + s.dRX, nrz = rz + s.dRZ;
                    // 只看落在采集窗口内的邻区，否则无从判定
                    if (Math.abs(nrx) > radius || Math.abs(nrz) > radius) continue;
                    RiverLineRegion to = engine.network().region(nrx, nrz);
                    seeds++;

                    double bestHead = Double.POSITIVE_INFINITY;
                    double bestAny = Double.POSITIVE_INFINITY;
                    double anySurfaceGap = 0.0;
                    int anyAt = -1;
                    for (int j = 0; j < to.rivers.size(); j++) {
                        RiverLineRegion.RiverPolyline r = to.rivers.get(j);
                        if (r.nodes.length == 0) continue;
                        double dh = Math.hypot(r.nodes[0].x() - s.wx, r.nodes[0].z() - s.wz);
                        bestHead = Math.min(bestHead, dh);
                        for (int k = 0; k < r.nodes.length; k++) {
                            double d = Math.hypot(r.nodes[k].x() - s.wx, r.nodes[k].z() - s.wz);
                            if (d < bestAny) {
                                bestAny = d;
                                anyAt = k;
                                anySurfaceGap = Math.abs(r.surfaceY[k] - s.surfaceY);
                            }
                        }
                    }
                    if (bestHead <= PICK_TOL) {
                        headPick++;
                        sumHeadDist += bestHead;
                        worstHeadDist = Math.max(worstHeadDist, bestHead);
                    } else if (bestAny <= PICK_TOL) {
                        anyOnly++;                      // 干流经过但缝头未对齐
                    } else {
                        // ★ 不做"目标区"猜测。按声明目标判 lost 会误报：collectOutlet 的
                        //   dRX/dRZ 由"尾节点落在本区中心的哪一侧"决定（恒为 ±1，从不为 0），
                        //   并非"跨了哪条边"——实测有种子 wx=-336 其实仍在发方区内却被判成
                        //   向西出境。真正该问的是视觉问题：**种子附近全局有没有任何河的
                        //   上游端**，与它被派给谁无关。
                        double alt = Double.POSITIVE_INFINITY;
                        for (int qz = -radius - 1; qz <= radius + 1; qz++) {
                            for (int qx = -radius - 1; qx <= radius + 1; qx++) {
                                alt = Math.min(alt, nearestHeadDist(engine, qx, qz, s));
                            }
                        }
                        if (alt <= PICK_TOL) {
                            misaimed++;                      // 河接上了，只是"声明目标区"不准
                        } else {
                            lost++;
                            if (lostList.size() < 8) {
                                lostList.add(String.format(
                                        "  lost: src=(%d,%d) dir=(%d,%d) seed wu(%.0f,%.0f) "
                                                + "declaredHead=%.0fwu altHead=%.0fwu",
                                        rx, rz, s.dRX, s.dRZ, s.wx, s.wz,
                                        bestHead == Double.POSITIVE_INFINITY ? -1 : bestHead,
                                        alt == Double.POSITIVE_INFINITY ? -1 : alt));
                            }
                        }
                    }
                    // 水面错层只在确实接上时考核
                    if (bestAny <= PICK_TOL && anyAt >= 0 && anySurfaceGap > SURFACE_WARN) {
                        surfaceBad++;
                        worstSurface = Math.max(worstSurface, anySurfaceGap);
                        if (surfaceList.size() < 8) {
                            surfaceList.add(String.format(
                                    "  surfaceGap=%.2f blocks: src=(%d,%d)->(%d,%d) wu(%.0f,%.0f)",
                                    anySurfaceGap, rx, rz, nrx, nrz, s.wx, s.wz));
                        }
                    }
                }
            }
        }

        System.out.println("=== HandoffPickupProbe ===");
        System.out.printf("seed=%d 窗口=(2%d+1)²  gridCell=%.0fwu  容差=%.0fwu%n",
                seed, radius, P.gridCell(), PICK_TOL);
        System.out.println("出口种子 seeds=" + seeds);
        System.out.println("headPickup=" + headPick
                + "  (上游端对齐接上 = 正常续流)");
        System.out.println("anyOnly=" + anyOnly
                + "  (干流经过该点但缝头未对齐)");
        System.out.println("misaimed=" + misaimed
                + "  (出口被派给错误邻区【象限 vs 跨边】，但几何正确的邻区接上了→河未断)");
        System.out.println("lost=" + lost + "  (跨缝断流：河在 region 边界凭空截断)");
        if (headPick > 0) {
            System.out.printf("接上点偏差: 平均=%.1fwu 最大=%.1fwu%n",
                    sumHeadDist / headPick, worstHeadDist);
        }
        System.out.println("surfaceMismatch(>" + SURFACE_WARN + "格)=" + surfaceBad
                + "  最大=" + worstSurface);
        lostList.forEach(System.out::println);
        surfaceList.forEach(System.out::println);
        boolean pass = seeds > 0 && lost == 0 && surfaceBad == 0;
        System.out.println("status=" + (pass ? "PASS" : "FAIL"));
    }

    /**
     * 该 region 内所有河到种子的最近距离（wu）；无河返回 +∞。
     *
     * <p>★ 取"河头"与"沿途任意节点"两者的较小值：只要有任何水体【经过】种子附近，
     *   河在视觉上就是连续的（可能以另一条 polyline 的身份继续，或该点本就被别条河
     *   占据而无需续流）。只有两者都远，才是真正的"河画到 region 边界就断了"。</p>
     */
    private static double nearestHeadDist(HydrologyExperimentEngine engine,
                                          int rx, int rz, RiverLineRegion.OutletSeed s) {
        if (Math.abs(rx) > 4 || Math.abs(rz) > 4) return Double.POSITIVE_INFINITY;
        double best = Double.POSITIVE_INFINITY;
        for (RiverLineRegion.RiverPolyline r : engine.network().region(rx, rz).rivers) {
            for (int k = 0; k < r.nodes.length; k++) {
                best = Math.min(best, Math.hypot(r.nodes[k].x() - s.wx, r.nodes[k].z() - s.wz));
            }
        }
        return best;
    }
}
