package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 河流交叉探针（2026-08-31）。
 *
 * <p>用户报告"一条河穿过另一条河并上下错层"。D8 汇流是树状、追踪阶段有 segmentCrossesAny
 * 防交叉，但那是在【原始格路径】上；Catmull-Rom 重采样 + meander 横向正弦偏移发生在
 * smoothPath 里，可能让两条河（或同一条河自交）在平滑后【几何交叉】。交叉处两条河水位
 * 不同 → 雕刻出 X 形叠槽 + 台阶（错层）。</p>
 *
 * <p>本探针在最终 RiverPolyline（平滑后）上，两两检测线段真交点，报告交点处两条河的
 * 水面差、是否邻近瀑布（fallDrop&gt;0）。crossings&gt;0 即证实"真交叉"。</p>
 */
public final class RiverCrossProbe {

    private RiverCrossProbe() { }

    /** 一条河 + 其 region，便于报告。 */
    private static final class Poly {
        final int rx, rz, id;
        final RiverLineRegion.RiverPolyline p;
        Poly(int rx, int rz, int id, RiverLineRegion.RiverPolyline p) {
            this.rx = rx; this.rz = rz; this.id = id; this.p = p;
        }
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);

        List<Poly> polys = new ArrayList<>();
        int id = 0;
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion r = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline p : r.rivers) {
                    polys.add(new Poly(rx, rz, id++, p));
                }
            }
        }

        int selfCross = 0, pairCross = 0;
        double maxDelta = 0.0;
        int nearFall = 0;
        System.out.println("=== RiverCrossProbe ===");
        System.out.println("seed=" + seed + " polylines=" + polys.size());

        for (int a = 0; a < polys.size(); a++) {
            Poly pa = polys.get(a);
            // 自交：同一条河的非相邻线段
            selfCross += countSelfCross(pa, nearFallHolder());
            for (int b = a + 1; b < polys.size(); b++) {
                Poly pb = polys.get(b);
                double[] nh = nearFallHolder();
                int c = countPairCross(pa, pb, nh);
                if (c > 0) {
                    pairCross += c;
                    double delta = Math.abs(nh[0]);
                    maxDelta = Math.max(maxDelta, delta);
                    if (nh[1] > 0.5) nearFall++;
                    if (c > 0) {
                        System.out.printf("  交叉 #%d(r%d,%d)×#%d(r%d,%d) @ (%.1f,%.1f) 水面差=%.1f %s%n",
                                (int) nh[2], (int) nh[6], (int) nh[7],
                                (int) nh[3], (int) nh[8], (int) nh[9],
                                nh[4], nh[5], delta,
                                nh[1] > 0.5 ? "邻近瀑布" : "");
                    }
                }
            }
        }

        System.out.println("selfCrossings=" + selfCross);
        System.out.println("pairCrossings=" + pairCross + " (两条河真交点数)");
        System.out.println("maxWaterLevelDelta@cross=" + String.format("%.1f", maxDelta)
                + "  nearFallCrossings=" + nearFall);
        System.out.println("status=" + ((selfCross == 0 && pairCross == 0) ? "PASS(无真交叉)" : "FAIL(存在几何交叉)"));
    }

    private static double[] nearFallHolder() {
        // [0]=最大水面差, [1]=邻近瀑布, [2]=paId, [3]=pbId, [4,5]=交点, [6,7]=pa region, [8,9]=pb region
        return new double[]{0.0, 0.0, -1, -1, 0, 0, 0, 0, 0, 0};
    }

    private static int countSelfCross(Poly pa, double[] nh) {
        RiverLineRegion.RiverPolyline p = pa.p;
        int n = p.nodes.length, cnt = 0;
        for (int i = 0; i + 1 < n; i++) {
            for (int j = i + 2; j + 1 < n; j++) {
                if (segIntersect(p.nodes[i], p.nodes[i + 1], p.nodes[j], p.nodes[j + 1])) {
                    cnt++;
                    double delta = Math.abs(lerpSurf(p, i, 0.5) - lerpSurf(p, j, 0.5));
                    nh[0] = Math.max(nh[0], delta);
                }
            }
        }
        return cnt;
    }

    private static int countPairCross(Poly pa, Poly pb, double[] nh) {
        RiverLineRegion.RiverPolyline A = pa.p, B = pb.p;
        int na = A.nodes.length, nb = B.nodes.length, cnt = 0;
        for (int i = 0; i + 1 < na; i++) {
            for (int j = 0; j + 1 < nb; j++) {
                double[] hit = new double[2];
                if (segIntersectHit(A.nodes[i], A.nodes[i + 1], B.nodes[j], B.nodes[j + 1], hit)) {
                    cnt++;
                    double ta = param(A.nodes[i], A.nodes[i + 1], hit[0], hit[1]);
                    double tb = param(B.nodes[j], B.nodes[j + 1], hit[0], hit[1]);
                    double sa = lerp(A.surfaceY[i], A.surfaceY[i + 1], ta);
                    double sb = lerp(B.surfaceY[j], B.surfaceY[j + 1], tb);
                    double delta = Math.abs(sa - sb);
                    if (delta >= nh[0]) {
                        nh[0] = delta;
                        nh[2] = pa.id; nh[3] = pb.id; nh[4] = hit[0]; nh[5] = hit[1];
                        nh[6] = pa.rx; nh[7] = pa.rz; nh[8] = pb.rx; nh[9] = pb.rz;
                        double fd = Math.max(
                                A.fallDrop[Math.min(i + 1, A.fallDrop.length - 1)],
                                B.fallDrop[Math.min(j + 1, B.fallDrop.length - 1)]);
                        nh[1] = fd > 0.0 ? 1 : 0;
                    }
                }
            }
        }
        return cnt;
    }

    private static double lerpSurf(RiverLineRegion.RiverPolyline p, int i, double t) {
        return lerp(p.surfaceY[i], p.surfaceY[Math.min(i + 1, p.surfaceY.length - 1)], t);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static double param(MidpointDisplacement.Node a, MidpointDisplacement.Node b,
                                double x, double z) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        return len2 < 1e-9 ? 0.0 : ((x - a.x()) * dx + (z - a.z()) * dz) / len2;
    }

    private static boolean segIntersect(MidpointDisplacement.Node p1, MidpointDisplacement.Node p2,
                                        MidpointDisplacement.Node p3, MidpointDisplacement.Node p4) {
        return segIntersectHit(p1, p2, p3, p4, null);
    }

    private static boolean segIntersectHit(MidpointDisplacement.Node p1, MidpointDisplacement.Node p2,
                                           MidpointDisplacement.Node p3, MidpointDisplacement.Node p4,
                                           double[] out) {
        double x1 = p1.x(), y1 = p1.z(), x2 = p2.x(), y2 = p2.z();
        double x3 = p3.x(), y3 = p3.z(), x4 = p4.x(), y4 = p4.z();
        double d = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3);
        if (Math.abs(d) < 1e-12) return false;      // 平行
        double t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / d;
        double u = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / d;
        // 真交点（严格内部，排除共端点的合法汇入）
        if (t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6) {
            if (out != null) { out[0] = x1 + t * (x2 - x1); out[1] = y1 + t * (y2 - y1); }
            return true;
        }
        return false;
    }
}
