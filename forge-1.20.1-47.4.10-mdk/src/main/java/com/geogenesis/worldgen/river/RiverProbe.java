package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * River network topology diagnostic probe (Phase 1).
 *
 * <p>Verifies:</p>
 * <ol>
 *   <li><b>Topology</b> — REACH/MOUTH/TRIBUTARY counts, avg segment length</li>
 *   <li><b>Connectivity</b> — main-chain segment endpoint sharing (next.path[0] == cur.path[last])</li>
 *   <li><b>Chain integrity</b> — every chain from head to tail (MOUTH to sea / REACH basin end), no dangling break</li>
 *   <li><b>Water contract</b> — main surface == global sea level; tributary surface &gt;= sea level</li>
 *   <li><b>Performance</b> — build time for 16x16 tiles (2048x2048 wu)</li>
 * </ol>
 *
 * <p>Usage: {@code gradlew runRiverProbe [seed]}</p>
 */
public final class RiverProbe {

    private static final String SEP = "  ";

    /** 点到折线路径的最近距离（视觉连续性语义：尾点落在下游段路径上 = 连接）。 */
    private static double distToPath(RiverSegment seg, double wx, double wz) {
        List<RiverNode> p = seg.path;
        double best = Double.MAX_VALUE;
        for (RiverNode nd : p) {
            double dx = nd.x() - wx, dz = nd.z() - wz;
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return best;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        runProbe(seed);
    }

    public static void runProbe(long seed) {
        System.out.println("=== River Network Topology Probe (Phase 1) ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);
        double seaLevel = net.seaLevelY();
        System.out.println("Sea level Y: " + seaLevel);
        System.out.println();

        // ---- 0. Main-row basin center e diagnostic ----
        int landMain = 0, oceanMain = 0;
        for (int bz = 0; bz < 16; bz += 4) {
            for (int bx = 0; bx < 16; bx++) {
                double e = net.eAt((bx + 0.5) * RiverNetwork.BASIN_SIZE, (bz + 0.5) * RiverNetwork.BASIN_SIZE);
                if (e < 0) oceanMain++; else landMain++;
            }
        }
        System.out.println("== MainRow e diag (z=0/4/8/12, x=0..15) ==");
        System.out.println(SEP + "land/ocean main-row basins: " + landMain + "/" + oceanMain);
        System.out.println();

        final int tiles = 32; // 32x32 tiles = 4096x4096 wu（16x16 在 seed 12345 下 94% 海洋，密度无统计意义）
        long t0 = System.nanoTime();

        // Build all plates, collect dedup segments
        // ★ 2026-08-15 P0 密度：按 uid 去重（同 basin 可有多段：一级+二级支流；basin key 会漏）
        Map<Integer, RiverSegment> allSegs = new HashMap<>();
        Map<Long, RiverSegment> byBasin = new HashMap<>();
        // ★ R11e：双 pass 收集——懒触发链时 plate 缓存可能缺段（远处链头建段后
        //   已 invalidate），第二遍命中重建后的 plate = 运行时最终一致性
        for (int pass = 0; pass < 2; pass++) {
        for (int tz = 0; tz < tiles; tz++) {
            for (int tx = 0; tx < tiles; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    allSegs.put(s.uid, s);
                    long k = RiverNetwork.pack(s.basinX, s.basinZ);
                    RiverSegment existing = byBasin.get(k);
                    // 同一 basin 可能同时有主河段与支流段（链经过非主河行 basin）：
                    // 主河段优先（连通性遍历走主河链；支流单独统计）
                    if (existing == null
                        || (existing.type == RiverSegmentType.TRIBUTARY
                            && s.type != RiverSegmentType.TRIBUTARY)) {
                        byBasin.put(k, s);
                    }
                }
            }
        }
        }
        long t1 = System.nanoTime();

        // ---- 1. Topology ----
        int reach = 0, mouth = 0, trib = 0, secondary = 0;
        double lenSum = 0;
        for (RiverSegment s : allSegs.values()) {
            lenSum += s.length();
            switch (s.type) {
                case REACH -> reach++;
                case MOUTH -> mouth++;
                case TRIBUTARY -> {
                    trib++;
                    // 二级分支：downstream == 自身 basin（父一级段所在 basin）
                    if (s.downstreamBasinX == s.basinX && s.downstreamBasinZ == s.basinZ) secondary++;
                }
            }
        }
        int primary = trib - secondary;
        System.out.println("== Topology ==");
        System.out.println(SEP + "REACH: " + reach);
        System.out.println(SEP + "MOUTH: " + mouth);
        System.out.println(SEP + "TRIBUTARY: " + trib + " (primary " + primary + " + secondary " + secondary + ")");
        System.out.println(SEP + "total segments: " + allSegs.size());
        System.out.println(SEP + "avg seg length: " + (allSegs.isEmpty() ? 0 : lenSum / allSegs.size()) + " wu");
        System.out.println(SEP + "density: " + (allSegs.size() / (double) (tiles * tiles * RiverNetwork.BASIN_SIZE * RiverNetwork.BASIN_SIZE) * 1_000_000.0)
            + " segs / 1e6 wu^2");
        System.out.println();

        // ---- 1.4 Monotonic descent（用户核心要求 2026-08-15：河流必须沿流向单调下降）----
        // 沿路径逐点 e 差；up = 上升步。主河/支流分开统计。注：e 单调 ↔ height 单调
        // （heightFromE 单调），故 e 检查即高度检查。
        System.out.println("== Monotonic descent (e along path) ==");
        int mUp = 0, mTot = 0, tUp = 0, tTot = 0;
        double mWorstUp = 0, tWorstUp = 0;
        String tWorstWhere = "";
        for (RiverSegment s : allSegs.values()) {
            boolean main = s.type == RiverSegmentType.REACH || s.type == RiverSegmentType.MOUTH;
            List<RiverNode> rp = s.path;
            for (int i = 1; i < rp.size(); i++) {
                double e0 = net.eAt(rp.get(i - 1).x(), rp.get(i - 1).z());
                double e1 = net.eAt(rp.get(i).x(), rp.get(i).z());
                double d = e1 - e0;
                if (main) {
                    mTot++;
                    if (d > 1e-9) { mUp++; mWorstUp = Math.max(mWorstUp, d); }
                } else {
                    tTot++;
                    if (d > 1e-9) {
                        tUp++;
                        if (d > tWorstUp) {
                            tWorstUp = d;
                            tWorstWhere = "seg(" + s.basinX + "," + s.basinZ + ")@" + i + "/" + rp.size()
                                + " up=" + s.upstreamBasinX + "," + s.upstreamBasinZ;
                        }
                    }
                }
            }
        }
        System.out.println(SEP + "main up-steps: " + mUp + " / " + mTot
            + " (" + (mTot == 0 ? 0 : 100.0 * mUp / mTot) + "%)  worst up: " + mWorstUp);
        System.out.println(SEP + "trib up-steps: " + tUp + " / " + tTot
            + " (" + (tTot == 0 ? 0 : 100.0 * tUp / tTot) + "%)  worst up: " + tWorstUp
            + (tWorstWhere.isEmpty() ? "" : "  @ " + tWorstWhere));
        System.out.println();

        // ---- 1.5 Valley fit（R2 贴谷性：主河段路径点 vs 垂直流向两侧邻）----
        // 物理语义：流线上的点在流向方向必然下坡（非局部最低）；贴谷 = 路径点在
        // **垂直流向**的两侧（谷壁）中更低 → 谷底。统计"比两侧邻低"的占比。
        System.out.println("== Valley fit (main reach pts vs cross-section) ==");
        int valleyPts = 0, totalPts = 0, flatCross = 0;
        double worstCross = -Double.MAX_VALUE, sumCross = 0;
        for (RiverSegment s : allSegs.values()) {
            if (s.type != RiverSegmentType.REACH && s.type != RiverSegmentType.MOUTH) continue;
            List<RiverNode> rp = s.path;
            for (int i = 0; i < rp.size(); i++) {
                RiverNode nd = rp.get(i);
                // 流向 = 当前 → 下一个点（末点用上一个方向）
                double fx, fz;
                if (i < rp.size() - 1) {
                    fx = rp.get(i + 1).x() - nd.x();
                    fz = rp.get(i + 1).z() - nd.z();
                } else {
                    fx = nd.x() - rp.get(i - 1).x();
                    fz = nd.z() - rp.get(i - 1).z();
                }
                double fl = Math.hypot(fx, fz);
                if (fl < 1e-9) continue;
                // 垂直单位向量（左右两侧 4wu）
                double nx = -fz / fl, nz = fx / fl;
                double e0 = net.eAt(nd.x(), nd.z());
                double eL = net.eAt(nd.x() + nx * 4.0, nd.z() + nz * 4.0);
                double eR = net.eAt(nd.x() - nx * 4.0, nd.z() - nz * 4.0);
                double lower = Math.min(eL, eR);
                totalPts++;
                double cross = lower - e0; // >0 = 谷底（两侧更高）
                sumCross += cross;
                if (cross > -1e-4) {
                    valleyPts++;
                    if (cross < 1e-4) flatCross++;
                } else {
                    worstCross = Math.max(worstCross, -cross);
                }
            }
        }
        System.out.println(SEP + "pts below cross-section: " + valleyPts + " / " + totalPts
            + " (" + (totalPts == 0 ? 0 : 100.0 * valleyPts / totalPts) + "%)"
            + " (flat " + flatCross + ")");
        System.out.println(SEP + "avg cross-elevation (lowerSide - pt): "
            + (totalPts == 0 ? 0 : sumCross / totalPts));
        System.out.println(SEP + "worst above-side: " + worstCross + " e"
            + " (note: worst = 段首尾连接点 = 链级路由 basin 中点/鞍部，非谷线；段内贴谷)");
        System.out.println();

        // ---- 1.6 Tributary valley fit（R7 支流贴谷性 + 长度）----
        // 注：山坡溪流**不适用**贴谷率（缓坡无深谷，横截面检查天然不成立）。
        // 真实指标 = 单调性（水面由 buildTributarySegment 缓抬保证，探针 bad rise=0）
        // + 不横穿山脊（mountainPath 峰值钳制保证）。贴谷率仅作参考展示。
        System.out.println("== Tributary valley fit (mountain stream, informational) ==");
        int tValley = 0, tTotal = 0, tSegs = 0;
        double tLenSum = 0, tSumCross = 0;
        for (RiverSegment s : allSegs.values()) {
            if (s.type != RiverSegmentType.TRIBUTARY) continue;
            tSegs++;
            tLenSum += s.length();
            List<RiverNode> rp = s.path;
            for (int i = 0; i < rp.size(); i++) {
                RiverNode nd = rp.get(i);
                double fx, fz;
                if (i < rp.size() - 1) {
                    fx = rp.get(i + 1).x() - nd.x();
                    fz = rp.get(i + 1).z() - nd.z();
                } else {
                    fx = nd.x() - rp.get(i - 1).x();
                    fz = nd.z() - rp.get(i - 1).z();
                }
                double fl = Math.hypot(fx, fz);
                if (fl < 1e-9) continue;
                double nx = -fz / fl, nz = fx / fl;
                double e0 = net.eAt(nd.x(), nd.z());
                double eL = net.eAt(nd.x() + nx * 4.0, nd.z() + nz * 4.0);
                double eR = net.eAt(nd.x() - nx * 4.0, nd.z() - nz * 4.0);
                double lower = Math.min(eL, eR);
                double cross = lower - e0; // >0 = 谷底（两侧更高）
                tTotal++;
                tSumCross += cross;
                if (cross > -1e-4) tValley++;
            }
        }
        System.out.println(SEP + "tributary segs: " + tSegs);
        System.out.println(SEP + "avg tributary length: " + (tSegs == 0 ? 0 : tLenSum / tSegs) + " wu");
        System.out.println(SEP + "pts below cross-section: " + tValley + " / " + tTotal
            + " (" + (tTotal == 0 ? 0 : 100.0 * tValley / tTotal) + "%)");
        System.out.println(SEP + "avg cross-elevation (lowerSide - pt): "
            + (tTotal == 0 ? 0 : tSumCross / tTotal) + " e");
        System.out.println();

        // ---- 2/3. Main-chain connectivity ----
        System.out.println("== Main-chain connectivity ==");
        int chains = 0, broken = 0, basinEnds = 0;
        double maxGap = 0;
        Set<Long> visited = new HashSet<>();
        for (RiverSegment s : allSegs.values()) {
            if (s.type == RiverSegmentType.TRIBUTARY || s.type == RiverSegmentType.MOUTH) continue;
            if (s.upstreamBasinX != -1) continue; // not a chain head
            chains++;
            RiverSegment cur = s;
            while (cur != null) {
                long ck = RiverNetwork.pack(cur.basinX, cur.basinZ);
                if (!visited.add(ck)) break; // shared segment (another chain head covered it) -> stop, not broken
                if (cur.downstreamBasinX == -1) {
                    if (cur.type == RiverSegmentType.REACH) basinEnds++; // basin end (legal)
                    break;
                }
                RiverSegment next = byBasin.get(RiverNetwork.pack(cur.downstreamBasinX, cur.downstreamBasinZ));
                if (next == null) {
                    // 下游 basin 在探针区域外 = 边界截断（非河网缺陷）；区域内 = 真断链
                    boolean inRegion = cur.downstreamBasinX >= 0 && cur.downstreamBasinX < tiles
                        && cur.downstreamBasinZ >= 0 && cur.downstreamBasinZ < tiles;
                    if (inRegion) broken++;
                    break;
                }
                // ★ 2026-08-15 R11：gap 判定 = 尾点到下游段路径最近距离（不是端点相等）。
                //   汇合 graft 会把尾点吸附到下游段路径**中间**点（视觉连续 = 投影距离 0），
                //   端点相等判定会误报（seed 9999 maxGap 5.66wu 实锤）。
                RiverNode a = cur.path.get(cur.path.size() - 1);
                double gap = distToPath(next, a.x(), a.z());
                maxGap = Math.max(maxGap, gap);
                if (gap > 0.001) broken++;
                cur = next;
            }
        }
        System.out.println(SEP + "main chains: " + chains);
        System.out.println(SEP + "basin-end tails: " + basinEnds);
        System.out.println(SEP + "broken/loop points: " + broken);
        System.out.println(SEP + "seg endpoint maxGap: " + maxGap + " wu (expect < 0.001)");
        System.out.println();
        // ---- 2b. 断裂详情（前 5 处） ----
        System.out.println("== Gap details (first 5) ==");
        int shown = 0;
        for (RiverSegment s : allSegs.values()) {
            if (shown >= 5) break;
            if (s.type == RiverSegmentType.TRIBUTARY || s.upstreamBasinX != -1) continue;
            RiverSegment cur = s;
            RiverSegment prev = null;
            while (cur != null && shown < 5) {
                if (cur.downstreamBasinX == -1) break;
                RiverSegment next = byBasin.get(RiverNetwork.pack(cur.downstreamBasinX, cur.downstreamBasinZ));
                if (next == null) {
                    System.out.println(SEP + "NULL next: " + cur.basinX + "," + cur.basinZ
                        + " -> " + cur.downstreamBasinX + "," + cur.downstreamBasinZ);
                    shown++;
                    break;
                }
                RiverNode a = cur.path.get(cur.path.size() - 1);
                RiverNode b = next.path.get(0);
                double gap = distToPath(next, a.x(), a.z());
                if (gap > 0.001) {
                    System.out.println(SEP + "GAP " + gap + " (endpoint d="
                        + Math.hypot(a.x() - b.x(), a.z() - b.z()) + ") : seg("
                        + cur.basinX + "," + cur.basinZ + ")["
                        + cur.type + "] end=(" + a.x() + "," + a.z() + ") -> seg("
                        + next.basinX + "," + next.basinZ + ")[" + next.type + "] start=("
                        + b.x() + "," + b.z() + ")  from="
                        + (prev == null ? "HEAD" : "prev(" + prev.basinX + "," + prev.basinZ + ")[" + prev.type + "]"));
                    shown++;
                }
                prev = cur;
                cur = next;
            }
        }
        System.out.println();

        // ---- 4. Water surface contract ----
        // ★ 2026-08-14 主河水面已改链级缓抬（跟随地形，源头高于海平面）——断言从
        //   "== 海平面" 改为 "≥ 海平面−ε"（缓抬合法；< 海平面 = 逆流异常）
        System.out.println("== Water surface contract ==");
        int mainLow = 0, tribLow = 0;
        double mainSurfMin = Double.MAX_VALUE, mainSurfMax = -Double.MAX_VALUE;
        for (RiverSegment s : allSegs.values()) {
            if (s.type == RiverSegmentType.REACH || s.type == RiverSegmentType.MOUTH) {
                if (s.surfaceLevel + 1e-6 < seaLevel) mainLow++;
                mainSurfMin = Math.min(mainSurfMin, s.surfaceLevel);
                mainSurfMax = Math.max(mainSurfMax, s.surfaceLevel);
            } else if (s.type == RiverSegmentType.TRIBUTARY) {
                if (s.surfaceLevel + 1e-6 < seaLevel) tribLow++;
            }
        }
        System.out.println(SEP + "main < seaLevel: " + mainLow);
        System.out.println(SEP + "main surf range: [" + mainSurfMin + ", " + mainSurfMax + "]");
        System.out.println(SEP + "trib < seaLevel: " + tribLow);
        System.out.println();

        // ---- 4b. Tributary source position（curY 修复验证：源头水面/贴地性） ----
        // 修复前 curY=ySea → 支流水面从海平面起步、寻路被吸向低地等高线（"位置不合理"
        // 根因）；修复后 = 分叉点水面（母河 segY），沿等高线蜿蜒爬升。指标：
        //   source surf range：源头水面范围（修复前 ≈[62,64]，修复后应明显抬升）
        //   source gap：源头地形 − 源头水面（贴地爬升 → 小正数；修复前支流在低地，
        //               源头水面 62 而地形 40-60 → gap 可为负 = 水比地面高）
        System.out.println("== Tributary source position (curY fix check) ==");
        double tsMin = Double.MAX_VALUE, tsMax = -Double.MAX_VALUE;
        double gapSum = 0;
        int tSourceN = 0, tSourceNegGap = 0;
        for (RiverSegment s : allSegs.values()) {
            if (s.type != RiverSegmentType.TRIBUTARY || s.path.isEmpty()) continue;
            RiverNode src = s.path.get(0); // path = 上游→下游，源头在前
            tsMin = Math.min(tsMin, src.waterSurfaceY());
            tsMax = Math.max(tsMax, src.waterSurfaceY());
            double terr = RiverBuilder2.groundYAt(net, src.x(), src.z());
            double gap = terr - src.waterSurfaceY();
            gapSum += gap;
            if (gap < 0) tSourceNegGap++;
            tSourceN++;
        }
        System.out.println(SEP + "trib source surf range: [" + (tSourceN == 0 ? "-" : tsMin)
            + ", " + (tSourceN == 0 ? "-" : tsMax) + "] (seaLevel=" + seaLevel + ")");
        System.out.println(SEP + "trib source terrain-water gap: avg="
            + (tSourceN == 0 ? 0 : gapSum / tSourceN) + "  neg=" + tSourceNegGap + "/" + tSourceN);
        System.out.println();

        // ---- 5. Performance ----
        long buildMs = (t1 - t0) / 1000000;
        System.out.println("== Performance ==");
        System.out.println(SEP + tiles + "x" + tiles + " tiles build: " + buildMs + " ms");
        System.out.println(SEP + "grid cache: " + net.gridCacheSize() + " tiles");
        System.out.println();
        System.out.println("=== RiverProbe done ===");
    }
}
