package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主河链贴谷性对比探针（2026-08-15 R6 预研）。
 *
 * <p>问题：主河链宏观在 <b>128wu basin 中心网格</b>上 D8 选路（traceChain），段首尾
 * 锁死 basin 边界中点——每 128wu 被"网格拉直"一次，宏观动线像没看到地形。
 * 本探针从同一链头出发，对比当前主河链 vs <b>4wu 连续 D8 下坡流线</b>：
 * 平均 e（贴谷性）、路径长度、链点偏离流线的距离（锯齿度）。</p>
 *
 * <p>用法：gradlew runRiverChainProbe [seed]</p>
 */
public final class RiverChainProbe {

    private static final String SEP = "  ";

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        runProbe(seed);
    }

    public static void runProbe(long seed) {
        System.out.println("=== Main Chain vs 4wu Flowline Probe (R6) ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);

        // 1. 收集所有主河段（uid 去重）
        Map<Integer, RiverSegment> segsByUid = new HashMap<>();
        Map<Long, RiverSegment> byBasin = new HashMap<>();
        for (int tz = 0; tz < 8; tz++) {   // ← 临时缩小到 8×8 定位 OOM
            for (int tx = 0; tx < 8; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    if (s.type != RiverSegmentType.REACH && s.type != RiverSegmentType.MOUTH) continue;
                    segsByUid.put(s.uid, s);
                    byBasin.putIfAbsent(RiverNetwork.pack(s.basinX, s.basinZ), s);
                }
            }
        }

        // 2. 收集主河链（链头 = upstream == -1）
        List<List<RiverSegment>> chains = new ArrayList<>();
        for (RiverSegment s : segsByUid.values()) {
            if (s.upstreamBasinX != -1) continue;
            List<RiverSegment> chain = new ArrayList<>();
            RiverSegment cur = s;
            while (cur != null) {
                chain.add(cur);
                if (cur.downstreamBasinX == -1) break;
                cur = byBasin.get(RiverNetwork.pack(cur.downstreamBasinX, cur.downstreamBasinZ));
            }
            chains.add(chain);
        }

        // 3. 对每条链：4wu 流线对比
        double sumChainE = 0, sumFlowE = 0;
        long chainSteps = 0, flowSteps = 0;
        double sumDevAvg = 0, sumDevTail = 0;
        double maxDev = 0;
        int chainCount = 0;
        System.out.println(SEP + "chains found: " + chains.size());
        System.out.println();
        for (List<RiverSegment> chain : chains) {
            // 链头点
            RiverNode head = chain.get(0).path.get(0);
            List<double[]> flow = flowLine(net, head.x(), head.z());
            if (flow.size() < 4) continue;
            // 链全部点
            List<double[]> cpts = new ArrayList<>();
            for (RiverSegment seg : chain) {
                for (RiverNode nd : seg.path) cpts.add(new double[]{nd.x(), nd.z()});
            }
            // 平均 e
            double chainE = 0, flowE = 0;
            for (double[] pt : cpts) chainE += net.eAt(pt[0], pt[1]);
            for (double[] pt : flow) flowE += net.eAt(pt[0], pt[1]);
            chainE /= cpts.size();
            flowE /= flow.size();
            sumChainE += chainE;
            sumFlowE += flowE;
            chainSteps += cpts.size();
            flowSteps += flow.size();
            // 链点偏离流线（最近距离）
            double devSum = 0, devTailSum = 0;
            int tailCount = 0;
            for (int i = 0; i < cpts.size(); i++) {
                double d = minDistToLine(cpts.get(i), flow);
                devSum += d;
                if (d > maxDev) maxDev = d;
                // 段首尾点（每隔一段的端点）
                if (isSegmentEndpoint(chain, i)) {
                    devTailSum += d;
                    tailCount++;
                }
            }
            double devAvg = devSum / cpts.size();
            sumDevAvg += devAvg;
            sumDevTail += tailCount > 0 ? devTailSum / tailCount : devAvg;
            chainCount++;

            System.out.println(SEP + "chain len=" + chain.size() + " segs, pts=" + cpts.size()
                + ", flow=" + flow.size() + "pts"
                + " | avgE chain=" + String.format("%.4f", chainE)
                + " flow=" + String.format("%.4f", flowE)
                + " (Δ=" + String.format("%.4f", chainE - flowE) + ")"
                + " | dev avg=" + String.format("%.1f", devAvg)
                + " tail=" + String.format("%.1f", tailCount > 0 ? devTailSum / tailCount : devAvg)
                + " wu");
        }

        System.out.println();
        System.out.println("== Summary (per chain) ==");
        System.out.println(SEP + "chains compared: " + chainCount);
        System.out.println(SEP + "avg e chain: " + (chainSteps == 0 ? 0 : sumChainE / chainCount)
            + " / flow: " + (flowSteps == 0 ? 0 : sumFlowE / chainCount));
        System.out.println(SEP + "avg e delta (chain - flow): "
            + String.format("%.4f", sumChainE / chainCount - sumFlowE / chainCount));
        System.out.println(SEP + "avg dev (chain pts to flowline): "
            + String.format("%.1f", sumDevAvg / chainCount) + " wu");
        System.out.println(SEP + "avg dev at segment endpoints (basin 中点拉直): "
            + String.format("%.1f", sumDevTail / chainCount) + " wu");
        System.out.println(SEP + "max dev: " + String.format("%.1f", maxDev) + " wu");
        System.out.println();
        System.out.println("NOTE: dev tail > dev avg = 段首尾被 basin 中点拉直（网格锯齿）。"
            + "avg e delta > 0 = 链不如流线贴谷（越低越贴谷）。");
    }

    /** 4wu 连续 D8 下坡流线（入海 / 盆地 / 2000 步终止） */
    private static List<double[]> flowLine(RiverNetwork net, double x, double z) {
        final int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        final int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
        List<double[]> pts = new ArrayList<>();
        double cx = x, cz = z;
        for (int s = 0; s < 2000; s++) {
            pts.add(new double[]{cx, cz});
            double e = net.eAt(cx, cz);
            if (e < 0) break; // 入海
            double bestE = e;
            int bestK = -1;
            for (int k = 0; k < 8; k++) {
                double en = net.eAt(cx + dx[k] * 4.0, cz + dz[k] * 4.0);
                if (en < bestE - 1e-9) {
                    bestE = en;
                    bestK = k;
                }
            }
            if (bestK < 0) break; // 盆地
            cx += dx[bestK] * 4.0;
            cz += dz[bestK] * 4.0;
        }
        return pts;
    }

    /** 点到折线最近距离 */
    private static double minDistToLine(double[] pt, List<double[]> line) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < line.size() - 1; i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            double abx = b[0] - a[0], abz = b[1] - a[1];
            double l2 = abx * abx + abz * abz;
            double t = l2 < 1e-9 ? 0 : ((pt[0] - a[0]) * abx + (pt[1] - a[1]) * abz) / l2;
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + abx * t, pz = a[1] + abz * t;
            double d = Math.hypot(pt[0] - px, pt[1] - pz);
            if (d < best) best = d;
        }
        return best;
    }

    /** 判断链点 i 是否为段端点（段内第一个点 = 与上游段共享点） */
    private static boolean isSegmentEndpoint(List<RiverSegment> chain, int i) {
        int acc = 0;
        for (RiverSegment seg : chain) {
            int n = seg.path.size();
            if (i == acc || i == acc + n - 1) return true;
            acc += n;
        }
        return false;
    }
}
