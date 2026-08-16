package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇水分析驱动河网探针（阶段 A 验证）。
 *
 * <p>验证四件事（对应用户四个残留问题的根治）：
 * <ol>
 *   <li><b>主河长度</b>——REACH 长度分布：应从分水岭制高点 D8 追踪到海，
 *       长度数百 wu（旧实现"主河长度极短"的根治指标）。</li>
 *   <li><b>树状度</b>——TRIBUTARY 末端终止类型：汇入已有河道（树状汇流）/
 *       入海 / 洼地；<b>汇入率</b>应显著（旧实现"支流不与主河连接"根治指标）。</li>
 *   <li><b>单调下降</b>——路径每节点 e 单调不升的比例（水往低处流；
 *       "动线不物理"根治指标）。</li>
 *   <li><b>贴谷偏差</b>——节点移动方向与 D8 最陡下坡方向一致的比例。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runRiverProbe [seed]} 或直接 java 运行本类。</p>
 */
public final class FlowRiverProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        runProbe(seed);
    }

    public static void runProbe(long seed) {
        System.out.println("=== Flow River Probe (Stage A: source-driven tracing) ===");
        System.out.println("Seed: " + seed);
        System.out.println();

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);
        double seaLevel = net.seaLevelY();

        // ---- 0. Collect all segments (24×24 tiles = 3072×3072 wu) ----
        final int tiles = 24;
        long t0 = System.nanoTime();
        Map<Integer, RiverSegment> all = new HashMap<>();
        for (int tz = 0; tz < tiles; tz++) {
            for (int tx = 0; tx < tiles; tx++) {
                for (RiverSegment s : net.plateForTile(tx, tz).segments()) {
                    all.put(s.uid, s);
                }
            }
        }
        long t1 = System.nanoTime();
        System.out.println("Build time (24x24 tiles): " + String.format("%.1f", (t1 - t0) / 1e6) + " ms, "
            + "segments: " + all.size());

        // ---- 1. Topology + length distribution ----
        int reach = 0, trib = 0, shortMains = 0;
        double mMin = Double.MAX_VALUE, mMax = 0, mSum = 0;
        double tMin = Double.MAX_VALUE, tMax = 0, tSum = 0;
        for (RiverSegment s : all.values()) {
            double len = s.length();
            if (s.type == RiverSegmentType.REACH) {
                reach++;
                mMin = Math.min(mMin, len); mMax = Math.max(mMax, len); mSum += len;
                if (len < 100) shortMains++;
            } else if (s.type == RiverSegmentType.TRIBUTARY) {
                trib++;
                tMin = Math.min(tMin, len); tMax = Math.max(tMax, len); tSum += len;
            }
        }
        System.out.println("== 1. Topology & length (wu) ==");
        System.out.println("  REACH (main): " + reach + ", TRIBUTARY: " + trib);
        System.out.println("  main len: min=" + fmt(mMin) + " avg=" + fmt(mSum / Math.max(1, reach))
            + " max=" + fmt(mMax));
        System.out.println("  trib len: min=" + fmt(tMin) + " avg=" + fmt(tSum / Math.max(1, trib))
            + " max=" + fmt(tMax));
        System.out.println("  main len < 100 wu: " + shortMains + "/" + reach);
        System.out.println();

        // ---- 2. Tributary termination type (tree-ness) ----
        int join = 0, sea = 0, sink = 0, maxStep = 0, longTrib = 0;
        int sinkTrue = 0, sinkFlat = 0; // 真洼地（8 邻全更高）vs 平坦漫流不足
        int sinkNearSea = 0;            // 近海断头（endE<SAIL_E，差一点入海）
        StringBuilder flatSample = new StringBuilder(); // flat 断头位置样本
        int monoGood = 0, monoTotal = 0;
        int waterMonoGood = 0, waterMonoTotal = 0; // 水面单调（用户视觉标准）
        int valleyGood = 0, valleyTotal = 0;
        for (RiverSegment s : all.values()) {
            List<RiverNode> path = s.path;
            if (s.type != RiverSegmentType.TRIBUTARY) continue;
            // 终止类型：末端贴其他段节点 = 汇入；末端 e≤0 = 入海；D8 局部最低 = 洼地
            RiverNode end = path.get(path.size() - 1);
            double endE = net.eAt(end.x(), end.z());
            if (endE <= 0) {
                sea++;
            } else if (net.flowDirAt(end.x(), end.z()) < 0) {
                sink++;
                if (endE < FlowRiverBuilder.SAIL_E) sinkNearSea++; // 近海断头
                // 性质：8 邻是否有 ≤ 自身 e 的格点（有 = 平坦漫流不足，无 = 真洼地）
                boolean hasFlat = false;
                for (int k = 0; k < 8; k++) {
                    double ne = net.eAt(end.x() + FlowField.DX8[k] * 4.0,
                        end.z() + FlowField.DZ8[k] * 4.0);
                    if (ne <= endE + 1e-6) { hasFlat = true; break; }
                }
                if (hasFlat) {
                    sinkFlat++;
                    if (flatSample.length() < 300) {
                        flatSample.append(String.format("  (%.0f, %.0f) e=%.4f\n",
                            end.x(), end.z(), endE));
                        // 远尺度地形诊断：各 scale 8 方向最低 e（确认是否真无更低下坡）
                        for (double sc : new double[]{100, 200, 400, 640, 800}) {
                            double mn = Double.MAX_VALUE;
                            for (int k = 0; k < 8; k++) {
                                mn = Math.min(mn, net.eAt(end.x() + FlowField.DX8[k] * sc,
                                    end.z() + FlowField.DZ8[k] * sc));
                            }
                            flatSample.append(String.format("    %4.0fwu minE=%.4f\n", sc, mn));
                        }
                    }
                } else sinkTrue++;
            } else {
                // ★ 汇入 = 末端距任何河道 < JOIN_DIST 且目标水面 ≤ 末端水面 + 2
                //  （与引擎 joinTarget 同口径：高度条件防"上升折线跳河"）
                double joinSq = FlowRiverBuilder.JOIN_DIST * FlowRiverBuilder.JOIN_DIST;
                boolean joined = false;
                for (RiverSegment o : all.values()) {
                    if (o == s) continue;
                    for (RiverNode n : o.path) {
                        double dx = n.x() - end.x(), dz = n.z() - end.z();
                        if (dx * dx + dz * dz <= joinSq
                            && n.waterSurfaceY() <= end.waterSurfaceY() + 2.0) {
                            joined = true;
                            break;
                        }
                    }
                    if (joined) break;
                }
                if (joined) join++;
                else maxStep++;
            }
            if (s.length() > 150) longTrib++;
            // 单调下降（地形 e，反映河道沿谷性）：相邻节点 e 不升（容差 1e-4）
            for (int i = 1; i < path.size(); i++) {
                double e0 = net.eAt(path.get(i - 1).x(), path.get(i - 1).z());
                double e1 = net.eAt(path.get(i).x(), path.get(i).z());
                monoTotal++;
                if (e1 <= e0 + 1e-4) monoGood++;
                // ★ 水面单调（用户视觉标准，PL-RGA 线性插值后应 ~100%）：
                //   水面 Y 不升（严格；容差 1e-6）
                double w0 = path.get(i - 1).waterSurfaceY();
                double w1 = path.get(i).waterSurfaceY();
                waterMonoTotal++;
                if (w1 <= w0 + 1e-6) waterMonoGood++;
            }
            // 贴谷：节点方向 = D8 流向（±1 方向宽容）
            for (int i = 0; i < path.size(); i++) {
                RiverNode n = path.get(i);
                int d = net.flowDirAt(n.x(), n.z());
                if (d < 0) continue; // 洼地终点不算
                valleyTotal++;
                if (i == path.size() - 1) { valleyGood++; continue; } // 终点不判
                RiverNode nn = path.get(i + 1);
                double ang = Math.atan2(nn.z() - n.z(), nn.x() - n.x());
                double best = Double.MAX_VALUE;
                for (int k = 0; k < 8; k++) {
                    double a = Math.atan2(FlowField.DZ8[k], FlowField.DX8[k]);
                    double diff = Math.abs(a - ang);
                    best = Math.min(best, diff);
                }
                if (best <= Math.PI / 4.0 + 0.3) valleyGood++;
            }
        }
        int totalTrib = Math.max(1, trib);
        System.out.println("== 2. Tributary termination (tree-ness) ==");
        System.out.println("  join river: " + join + " (" + pct(join, totalTrib) + ")");
        System.out.println("  to sea:     " + sea + " (" + pct(sea, totalTrib) + ")");
        System.out.println("  sink/dep:   " + sink + " (" + pct(sink, totalTrib) + ")"
            + " [trueDep=" + sinkTrue + ", flat=" + sinkFlat
            + ", nearSea=" + sinkNearSea + "]");
        if (sinkFlat > 0) System.out.println("  flat sample (first 8):\n" + flatSample);
        System.out.println("  max steps:  " + maxStep + " (" + pct(maxStep, totalTrib) + ")");
        System.out.println("  trib > 150wu: " + longTrib + " (" + pct(longTrib, totalTrib) + ")");
        System.out.println();

        // ---- 3. Physical flow ----
        System.out.println("== 3. Physical flow ==");
        System.out.println("  monotonic descent (terrain e): " + monoGood + "/" + monoTotal
            + " (" + pct(monoGood, Math.max(1, monoTotal)) + ")");
        System.out.println("  water monotonic (surface Y): " + waterMonoGood + "/" + waterMonoTotal
            + " (" + pct(waterMonoGood, Math.max(1, waterMonoTotal)) + ")");
        System.out.println("  valley fit (dir==D8): " + valleyGood + "/" + valleyTotal
            + " (" + pct(valleyGood, Math.max(1, valleyTotal)) + ")");
        System.out.println("  sea level Y: " + seaLevel);

        // ---- 3.5 Surface elevation drop (落差诊断) ----
        double dropSum = 0; int dropCount = 0;
        double dropMin = Double.MAX_VALUE, dropMax = 0;
        for (RiverSegment s : all.values()) {
            if (s.path.size() < 2) continue;
            double src = s.path.get(0).waterSurfaceY();
            double dst = s.path.get(s.path.size() - 1).waterSurfaceY();
            double drop = src - dst;
            if (drop > 0) {
                dropSum += drop; dropCount++;
                dropMin = Math.min(dropMin, drop);
                dropMax = Math.max(dropMax, drop);
            }
        }
        System.out.println("== 3.5 Surface drop (落差) ==");
        System.out.println("  segments with drop>0: " + dropCount + "/" + all.size()
            + ", drop: min=" + fmt(dropMin) + " avg=" + fmt(dropSum / Math.max(1, dropCount))
            + " max=" + fmt(dropMax) + " blocks");

        // ---- 3.6 Sampling continuity（修复 #1/#2/#3/#4 验证，2026-08-16）----
        // 沿段路径每 8wu 调 sampleRiver（走真实 plate 采样层）——inChannel 比例 =
        // MC 实际河道连续性。拓扑探针（join/sink/单调）测不到"采样层是否有这段"：
        // plate 漏段（#1 5×5）/ 水面虚高雕刻 NONE（#2 min）/ 陆地低洼断头（#3 浅槽）
        // 只会让 MC 里河消失而拓扑数字依旧好看。暗河段（喀斯特下潜）inChannel=false
        // 属预期隐藏，单独归因，不计入 miss。
        int contTotal = 0, contChannel = 0, contSub = 0;
        for (RiverSegment s : all.values()) {
            List<RiverNode> path = s.path;
            for (int i = 0; i < path.size(); i += 2) { // 每 8wu（节点间距 4wu）
                RiverNode n = path.get(i);
                RiverSample rs = net.sampleRiver(n.x(), n.z());
                contTotal++;
                if (rs.inChannel()) contChannel++;
                else if (net.subsurfaceAt(n.x(), n.z())) contSub++;
            }
        }
        int contMiss = contTotal - contChannel - contSub;
        System.out.println("== 3.6 Sampling continuity (plate 5x5 + water min + trough + sail) ==");
        System.out.println("  inChannel: " + contChannel + "/" + contTotal
            + " (" + pct(contChannel, Math.max(1, contTotal)) + ")");
        System.out.println("  subsurface-hidden: " + contSub + " (" + pct(contSub, Math.max(1, contTotal))
            + "), miss(plate leak/carve NONE): " + contMiss
            + " (" + pct(contMiss, Math.max(1, contTotal)) + ")");

        // ---- 3.7 Join gradient（逆梯度汇入，修复验证 2026-08-17）----
        // 支流终点若是 join 点（t.add 直接跳到命中节点），该跳不受"移动必降"
        // 约束——唯一可能"下降中转折向上"的位置。主河线性水面在坡地虚低会
        // 把谷底支流吸上坡（旧 joinTarget 只比水面 Y）。检查终点地形 e 显著
        // 高于前一节点（>0.03e ≈ 2 块）即逆梯度 join；修复后应 ≈ 0。
        int upJoin = 0;
        for (RiverSegment s : all.values()) {
            if (s.type != RiverSegmentType.TRIBUTARY) continue;
            List<RiverNode> pp = s.path;
            if (pp.size() < 2) continue;
            RiverNode na = pp.get(pp.size() - 2), nb = pp.get(pp.size() - 1);
            if (net.eAt(nb.x(), nb.z()) > net.eAt(na.x(), na.z()) + 0.03) upJoin++;
        }
        System.out.println("== 3.7 Join gradient (upslope join check) ==");
        System.out.println("  trib ends with e rising > 0.03: " + upJoin + "/" + trib
            + " (" + pct(upJoin, Math.max(1, trib)) + ")");

        // ---- 3.8 Subsurface断河量化（2026-08-17 截图断河定位）----
        // 沿路径每 4wu 逐块检查 sampleRiver：区分"段路径间 miss" vs "段路径上
        // subsurface"——前者 = plate 覆盖缺陷，后者 = 地下水判定过激（截图断河）。
        // 统计最长连续 subsurface/run（连续断河长度，MC 观感直接相关）。
        int subRun = 0, maxSubRun = 0, totalSubRun = 0, subRunCount = 0;
        int missRun = 0, maxMissRun = 0;
        for (RiverSegment s : all.values()) {
            List<RiverNode> pp = s.path;
            for (int i = 0; i < pp.size() - 1; i++) {
                RiverNode na = pp.get(i), nb = pp.get(i + 1);
                double dx = nb.x() - na.x(), dz = nb.z() - na.z();
                double segLen = Math.sqrt(dx * dx + dz * dz);
                int steps = Math.max(1, (int) Math.ceil(segLen / 4.0));
                for (int si = 0; si < steps; si++) {
                    double f = steps > 1 ? (double) si / steps : 0.0;
                    double wx = na.x() + dx * f, wz = na.z() + dz * f;
                    RiverSample rs = net.sampleRiver(wx, wz);
                    boolean hit = rs.inChannel();
                    boolean sub = !hit && net.subsurfaceAt(wx, wz);
                    boolean miss = !hit && !sub;
                    if (sub) { subRun++; missRun = 0; }
                    else { if (subRun > 0) { totalSubRun += subRun; subRunCount++; maxSubRun = Math.max(maxSubRun, subRun); } subRun = 0; }
                    if (miss) { missRun++; subRun = 0; }
                    else { maxMissRun = Math.max(maxMissRun, missRun); missRun = 0; }
                }
            }
            if (subRun > 0) { totalSubRun += subRun; subRunCount++; maxSubRun = Math.max(maxSubRun, subRun); subRun = 0; }
            if (missRun > 0) { maxMissRun = Math.max(maxMissRun, missRun); missRun = 0; }
        }
        double avgSubRun = subRunCount > 0 ? (double) totalSubRun / subRunCount : 0;
        System.out.println("== 3.8 Subsurface断河 (groundwater.isSubsurface masking river) ==");
        System.out.println("  subsurface runs: " + subRunCount + ", avg len=" + fmt(avgSubRun)
            + " max=" + maxSubRun + " blocks (连续水消失长度)");
        System.out.println("  miss(plate) max run: " + maxMissRun + " blocks");

        // ---- 4. Lakes (Stage B) ----
        int lakeCount = 0;
        double rSum = 0, rMin = Double.MAX_VALUE, rMax = 0;
        for (int tz = 0; tz < tiles; tz++) {
            for (int tx = 0; tx < tiles; tx++) {
                for (LakeBuilder.Basin b : net.lakesFor(tx, tz)) {
                    lakeCount++;
                    rSum += b.radius();
                    rMin = Math.min(rMin, b.radius());
                    rMax = Math.max(rMax, b.radius());
                }
            }
        }
        System.out.println("== 4. Lakes (Stage B: sink basins) ==");
        System.out.println("  lakes: " + lakeCount
            + ", radius: min=" + fmt(rMin) + " avg=" + fmt(rSum / Math.max(1, lakeCount))
            + " max=" + fmt(rMax));

        // 洼地深度分布（诊断：溢出口 − 盆底 的 e 差；决定湖门槛）
        int[] dh = new int[5];
        for (int tz = 0; tz < 8; tz++) {
            for (int tx = 0; tx < 8; tx++) {
                for (int iz = 0; iz <= 32; iz++) {
                    for (int ix = 0; ix <= 32; ix++) {
                        double wx = tx * 128.0 + ix * 4.0, wz = tz * 128.0 + iz * 4.0;
                        if (net.flowDirAt(wx, wz) >= 0 || net.eAt(wx, wz) <= 0) continue;
                        double e = net.eAt(wx, wz);
                        double minN = Double.MAX_VALUE;
                        for (int k = 0; k < 8; k++) {
                            minN = Math.min(minN, net.eAt(wx + FlowField.DX8[k] * 4.0,
                                wz + FlowField.DZ8[k] * 4.0));
                        }
                        double d = minN - e;
                        if (d < 0.002) dh[0]++;
                        else if (d < 0.005) dh[1]++;
                        else if (d < 0.01) dh[2]++;
                        else if (d < 0.05) dh[3]++;
                        else dh[4]++;
                    }
                }
            }
        }
        System.out.println("  sink depth hist (<0.002/<0.005/<0.01/<0.05/>=0.05): "
            + dh[0] + "/" + dh[1] + "/" + dh[2] + "/" + dh[3] + "/" + dh[4]);

        // ---- 5. Groundwater (Stage C: springs / subsurface reaches) ----
        int spring = 0, springTotal = 0;
        int sub = 0, subTotal = 0;
        for (int tz = 0; tz < 8; tz++) {
            for (int tx = 0; tx < 8; tx++) {
                for (double wz = tz * 128.0 + 8.0; wz < (tz + 1) * 128.0; wz += 16.0) {
                    for (double wx = tx * 128.0 + 8.0; wx < (tx + 1) * 128.0; wx += 16.0) {
                        if (net.eAt(wx, wz) <= 0) continue; // 只统计陆地
                        springTotal++;
                        if (net.springAt(wx, wz)) spring++;
                    }
                }
            }
        }
        // ★ 暗河统计改**段节点**（采样层统计有自选择偏差：被隐藏段不再命中）
        for (RiverSegment s : all.values()) {
            for (RiverNode n : s.path) {
                subTotal++;
                if (net.subsurfaceAt(n.x(), n.z())) sub++;
            }
        }
        System.out.println("== 5. Groundwater (Stage C) ==");
        System.out.println("  springs (land): " + spring + "/" + springTotal
            + " (" + pct(spring, Math.max(1, springTotal)) + ")");
        System.out.println("  subsurface channel nodes: " + sub + "/" + subTotal
            + " (" + pct(sub, Math.max(1, subTotal)) + ")");

        // ---- 6. Water balance (Stage D: humidity → density) ----
        // ★ 采样区与河网同范围（24×24）：8×8 恰好落在湿度正值区 → 无干带样本
        int dryTrib = 0, midTrib = 0, wetTrib = 0;
        int dryLand = 0, midLand = 0, wetLand = 0;
        for (int tz = 0; tz < tiles; tz++) {
            for (int tx = 0; tx < tiles; tx++) {
                for (double wz = tz * 128.0 + 8.0; wz < (tz + 1) * 128.0; wz += 32.0) {
                    for (double wx = tx * 128.0 + 8.0; wx < (tx + 1) * 128.0; wx += 32.0) {
                        double h = FlowRiverBuilder.humidityAt(net, wx, wz);
                        if (net.eAt(wx, wz) <= 0) continue;
                        if (h < 0.35) dryLand++;
                        else if (h < 0.65) midLand++;
                        else wetLand++;
                    }
                }
            }
        }
        for (RiverSegment s : all.values()) {
            if (s.type != RiverSegmentType.TRIBUTARY || s.path.isEmpty()) continue;
            RiverNode src = s.path.get(0);
            double h = FlowRiverBuilder.humidityAt(net, src.x(), src.z());
            if (h < 0.35) dryTrib++;
            else if (h < 0.65) midTrib++;
            else wetTrib++;
        }
        System.out.println("== 6. Water balance (Stage D: humidity→density) ==");
        System.out.println("  land samples dry/mid/wet: " + dryLand + "/" + midLand + "/" + wetLand);
        System.out.println("  trib sources dry/mid/wet: " + dryTrib + "/" + midTrib + "/" + wetTrib
            + " (density dry=" + pct(dryTrib, Math.max(1, dryLand))
            + " mid=" + pct(midTrib, Math.max(1, midLand))
            + " wet=" + pct(wetTrib, Math.max(1, wetLand)) + ")");
        System.out.println();
    }

    private static String fmt(double v) {
        return v >= Double.MAX_VALUE / 2 ? "-" : String.format("%.0f", v);
    }

    private static String pct(int a, int b) {
        return String.format("%.1f%%", 100.0 * a / b);
    }
}
