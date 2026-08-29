package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨 region 连续河验证探针：量化"河流跨越 640wu 瓦片缝后是否连续"。
 *
 * <p>对每种配置（crossRegion 开/关）统计：本 region 中<b>下游端（tail）逼近某网格边</b>
 * （即跨缝河，fix 处理对象）的河，其尾节点在下游邻 region 中是否有匹配节点
 * （2D 距 ≤ {@code CROSS_TOL}）→ 连续（matched）；否则缺口（gap）。
 * 按尾节点地形 e 区分陆地/海洋缺口。</p>
 *
 * <p>判读：开 → 跨缝河尾在邻 region 有续流（matched≈candidates，陆地缺口≈0）；
 * 关 → 跨缝河到网格边被整条丢弃（candidates=0，断河"不可见"）。两者对照即证明 fix。</p>
 *
 * <p>用法：{@code gradlew runRiverLineContinuityProbe -PprobeArgs="12345 2"}</p>
 */
public final class RiverLineContinuityProbe {
    private RiverLineContinuityProbe() { }

    /** 下游端距瓦片缝（region body 边）多近算"跨缝河"（wu）。fix 的出口河尾在 borderDist 内。 */
    private static final double SEAM_BAND = 240.0;
    /** 跨缝尾节点到邻 region 续流节点 2D 距 ≤ 此值 → 连续。 */
    private static final double CROSS_TOL = 250.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(tp, tp.minY(), tp.maxY());
        terrain.seed(seed);
        double oceanE = RiverLineParams.defaults().oceanE();

        System.out.println("=== RiverLine cross-region continuity probe ===");
        System.out.println("seed=" + seed + " regionRadius=" + n
                + " seamBand=" + SEAM_BAND + " crossTol=" + CROSS_TOL + " oceanE=" + oceanE);

        RiverLineNetwork on = new RiverLineNetwork(terrain::terrainEQuick,
                (wx, wz) -> terrain.sampleWu(wx, wz).height, terrain.heightCurve(), seed,
                RiverLineParams.defaults());
        RiverLineNetwork off = new RiverLineNetwork(terrain::terrainEQuick,
                (wx, wz) -> terrain.sampleWu(wx, wz).height, terrain.heightCurve(), seed,
                RiverLineParams.defaults().withCrossRegion(false));

        System.out.println();
        System.out.println("-- crossRegion = ON (修复后) --");
        printStats(measure(on, terrain, oceanE, n));
        System.out.println();
        System.out.println("-- crossRegion = OFF (旧行为) --");
        printStats(measure(off, terrain, oceanE, n));

        System.out.println();
        System.out.println("-- precise handoff check (outlet seed → target neighbor) --");
        System.out.println("[ON ] " + measureOutlets(on, terrain, oceanE, n));
        System.out.println("[OFF] " + measureOutlets(off, terrain, oceanE, n));
    }

    /** 精确校验：每个出口种子（fix 发出）在其目标邻 region 是否有续流节点（≈种子位置）。 */
    private static String measureOutlets(RiverLineNetwork net, CellGenerator terrain,
                                         double oceanE, int n) {
        int seeds = 0, matched = 0, matchedLand = 0, gap = 0, gapLand = 0;
        for (int rz = -n; rz <= n; rz++) {
            for (int rx = -n; rx <= n; rx++) {
                RiverLineRegion r = net.region(rx, rz);
                for (RiverLineRegion.OutletSeed o : r.outlets) {
                    int nrx = rx + o.dRX, nrz = rz + o.dRZ;
                    if (nrx < -n || nrx > n || nrz < -n || nrz > n) continue;
                    seeds++;
                    boolean land = terrain.terrainEQuick(o.wx, o.wz) > oceanE;
                    boolean ok = neighborHasNodeNear(net.region(nrx, nrz), o.wx, o.wz);
                    if (ok) { matched++; if (land) matchedLand++; }
                    else { gap++; if (land) gapLand++; }
                }
            }
        }
        double pct = seeds == 0 ? 0 : 100.0 * matched / seeds;
        return String.format("outlet seeds=%d  handed-off(matched)=%d [land=%d]  unhanded(gap)=%d [land=%d]  handoff=%.1f%%",
                seeds, matched, matchedLand, gap, gapLand, pct);
    }

    private static final class Stat {
        int candidates, matched, gap, matchedLand, gapLand, matchedOcean, gapOcean;
    }

    private static Stat measure(RiverLineNetwork net, CellGenerator terrain,
                                 double oceanE, int n) {
        Stat s = new Stat();
        for (int rz = -n; rz <= n; rz++) {
            for (int rx = -n; rx <= n; rx++) {
                RiverLineRegion r = net.region(rx, rz);
                double R = net.regionSize();
                // 4 条瓦片缝（body 边）及对应下游邻 region
                // seamX = rx*R（邻 rx-1） / (rx+1)*R（邻 rx+1）；seamZ 同理
                double[] seamX = { rx * R, (rx + 1) * R };
                double[] seamZ = { rz * R, (rz + 1) * R };
                int[][] nbr = {
                        { rx - 1, rz }, { rx + 1, rz },   // 对应 seamX[0], seamX[1]
                        { rx, rz - 1 }, { rx, rz + 1 }    // 对应 seamZ[0], seamZ[1]
                };
                for (RiverLineRegion.RiverPolyline pl : r.rivers) {
                    if (pl.nodes.length == 0) continue;
                    MidpointDisplacement.Node tail = pl.nodes[pl.nodes.length - 1];
                    // 找最近瓦片缝（body 边）
                    int bestSeam = -1; double bestD = Double.MAX_VALUE;
                    for (int k = 0; k < 2; k++) {
                        double d = Math.abs(tail.x() - seamX[k]);
                        if (d < bestD) { bestD = d; bestSeam = k; }
                        d = Math.abs(tail.z() - seamZ[k]);
                        if (d < bestD) { bestD = d; bestSeam = 2 + k; }
                    }
                    if (bestD > SEAM_BAND) continue;        // 不逼近任何缝 → 非跨缝河
                    int[] nbCoord = nbr[bestSeam];
                    if (nbCoord[0] < -n || nbCoord[0] > n || nbCoord[1] < -n || nbCoord[1] > n) continue;
                    RiverLineRegion nb = net.region(nbCoord[0], nbCoord[1]);
                    s.candidates++;
                    boolean land = terrain.terrainEQuick(tail.x(), tail.z()) > oceanE;
                    boolean matched = neighborHasNodeNear(nb, tail.x(), tail.z());
                    if (matched) {
                        s.matched++;
                        if (land) s.matchedLand++; else s.matchedOcean++;
                    } else {
                        s.gap++;
                        if (land) s.gapLand++; else s.gapOcean++;
                    }
                }
            }
        }
        return s;
    }

    private static boolean neighborHasNodeNear(RiverLineRegion nb, double x, double z) {
        for (RiverLineRegion.RiverPolyline pl : nb.rivers) {
            for (MidpointDisplacement.Node nd : pl.nodes) {
                if (Math.hypot(nd.x() - x, nd.z() - z) <= CROSS_TOL) return true;
            }
        }
        return false;
    }

    private static void printStats(Stat s) {
        System.out.println("cross-seam candidate rivers (tail at grid edge) = " + s.candidates);
        System.out.println("  matched (continuous into neighbor) = " + s.matched
                + "  [land=" + s.matchedLand + " ocean=" + s.matchedOcean + "]");
        System.out.println("  gap (broken at seam)               = " + s.gap
                + "  [land=" + s.gapLand + " ocean=" + s.gapOcean + "]");
        if (s.candidates > 0) {
            double contPct = 100.0 * s.matched / s.candidates;
            System.out.println("  continuity = " + String.format("%5.1f%%", contPct));
        }
    }
}
