package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 源头谷地归属探针（2026-09-01）：用户的两条判据。
 *
 * <p>① 源头应在【山谷】里：沿垂直于流向的方向看，两侧地形都应比源点高（汇流槽）。
 * 现有成河判据只看 D8 汇流面积，而汇流面积在【开阔凸坡】上同样会随下坡累积达标
 * ——坡面无谷但依然"汇流"，这正是截图里源头切在坡面上的原因。</p>
 *
 * <p>② 源头不应落在【另一条河的过渡区】里：到最近另一条河中心线的距离（block）
 * 若小于该河的谷壁影响半径 valley=3.5×半宽，则新河是在别人的谷壁上再开一条槽。</p>
 */
public final class SourceValleyProbe {

    private SourceValleyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        double hs = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        double off = args.length > 2 ? Double.parseDouble(args[2]) : 24.0;   // 横距(wu)
        // ★ 多种子扫描（第 4 个参数）：源头类缺陷是概率性的，单 region 只有 ~20 条河，
        //   单种子根本不够。gradlew runSourceValleyProbe -PprobeArgs="12345 2.0 24 30"
        int sweep = args.length > 3 ? Math.max(1, Integer.parseInt(args[3])) : 1;
        int rr = args.length > 4 ? Math.max(0, Integer.parseInt(args[4])) : 1;
        if (sweep > 1) {
            System.out.println("=== SourceValleyProbe SWEEP base=" + seed + " count=" + sweep
                    + " rr=" + rr + " ===");
            for (int i = 0; i < sweep; i++) {
                long sd = seed + i * 7919L;
                System.out.println("---- seed=" + sd + " ----");
                probeOne(sd, hs, off, rr);
            }
            return;
        }
        probeOne(seed, hs, off, rr);
    }

    private static void probeOne(long seed, double hs, double off, int rr) {
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineParams P = RiverLineParams.defaults();
        double regionSize = P.regionSize();
        double seamTol = 48.0;

        // 收集全部河（默认跨 3×3 region），用于"是否落在别的河谷里"的判定。
        // ★ rr 参数（第 5 个）可收窄到 1×1：用于区分 offender 是【同 region 内】还是
        //   【跨 region】——resolveInvadedHeads 是 region 级的，跨 region 案例它按定义
        //   看不见，两者的处置方式完全不同（前者是本地 bug，后者要动架构）。
        List<RiverLineRegion.RiverPolyline> all = new ArrayList<>();
        List<RiverLineRegion.LakeNode> allLakes = new ArrayList<>();
        int lakeTotal = 0;
        for (int rz = -rr; rz <= rr; rz++) {
            for (int rx = -rr; rx <= rr; rx++) {
                RiverLineRegion reg = engine.network().region(rx, rz);
                all.addAll(reg.rivers);
                allLakes.addAll(reg.lakes);
                if (!reg.lakes.isEmpty()) {
                    lakeTotal += reg.lakes.size();
                    System.out.printf("lake region(%d,%d) 湖数=%d%n", rx, rz, reg.lakes.size());
                }
            }
        }
        // 河成湖统计：有湖段（lakeLevel 有限值）的河数 + 湖段总长（wu → block）
        int riverLakes = 0;
        double lakeReachLen = 0.0;
        double sea = 63.0;
        for (RiverLineRegion.RiverPolyline r : all) {
            if (r.lakeLevel == null) continue;
            boolean has = false;
            // 逐 reach 报海拔（连续有限值段取末端=湖面）
            for (int i = 0; i < r.lakeLevel.length; i++) {
                if (Double.isNaN(r.lakeLevel[i])) continue;
                if (!has) { riverLakes++; has = true; }
                lakeReachLen++;
                if (i + 1 >= r.lakeLevel.length || Double.isNaN(r.lakeLevel[i + 1])) {
                    System.out.printf("  riverLake reach 湖面=%.2f 高出海平面=%.2f%n",
                            r.lakeLevel[i], r.lakeLevel[i] - sea);
                }
            }
        }
        System.out.println("[LAKES] 洼地湖 " + lakeTotal + " 个；河成湖："
                + riverLakes + " 条河共 " + (int) (lakeReachLen * 4.0 * 2.0)
                + " block 湖段（节点距 4wu × hs2）");

        int n = 0, seam = 0;
        int inValley = 0, onShoulder = 0, onRidge = 0;
        int insideOther = 0;
        int feedersExcluded = 0;
        double sumMargin = 0;
        List<String> badValley = new ArrayList<>();
        List<String> badOther = new ArrayList<>();

        for (RiverLineRegion.RiverPolyline r : all) {
            if (r.nodes.length < 2) continue;
            // ★ 细流剖面（level>=2 = 源前细流/支流）：回答"扇形细流在游戏里长什么样"
            if (r.level >= 2) {
                StringBuilder sb = new StringBuilder("  rillProfile L" + r.level
                        + " (" + r.nodes.length + "节点):");
                for (int i = 0; i < r.nodes.length; i++) {
                    sb.append(String.format(" [w=%.2f d=%.2f]", r.width[i], r.depth[i]));
                }
                System.out.println(sb);
            }
            // ★ 细流（feeder）不参与源头质量考核（2026-09-07）：细流头已归零淡出
            //   （宽 1 格、深 0），落在凸坡上也切不出可见断面——把它当主河考核会
            //   把"装饰"计成"缺陷"。细流特征：末节点 = 某条河的首节点（汇入主河头）。
            //   容差取 3 wu：细流汇入端节点被 meander 横移（meanderAmp=2.5wu，参数
            //   注释写"block"与实测不符——实测横移 2.35~2.50 wu），衰减只作用在细流
            //   自己的源头端，不会与主河头精确重合。
            boolean isFeeder = false;
            boolean tailJoinsRiver = false;
            boolean headAtLakeOutlet = false;
            for (RiverLineRegion.LakeNode lk : allLakes) {
                double dh = Math.hypot(r.nodes[0].x() - lk.x, r.nodes[0].z() - lk.z);
                if (dh <= lk.radius + P.gridCell()) { headAtLakeOutlet = true; break; }
            }
            for (RiverLineRegion.RiverPolyline o : all) {
                if (o == r) continue;
                var tail = r.nodes[r.nodes.length - 1];
                if (Math.hypot(tail.x() - o.nodes[0].x(), tail.z() - o.nodes[0].z()) < 3.0) {
                    isFeeder = true;
                    break;
                }
                // ★ 尾接任何河的河道（含"贴邻河续流并入"产物）= 支流：汇入处贴近
                //   被并入河是 Y 形汇流的定义，不是缺陷（②不考核）。正则支流的源头
                //   有本 region 谷壁守卫、本就不会侵入，排除不损失覆盖。
                if (!tailJoinsRiver) {
                    // 同格判据：合并/汇入河的尾节点 = 目标【格心】，而目标格是从邻区
                    // 折线点的所在格反查的——邻区栅格相对本区偏移最多 16 wu，格心到
                    // 邻区节点天然可达 ~17 wu；再加 meander 横移 ≤2.5 wu。容差取
                    // gridCell(24 wu) = "尾节点落在邻河同一格"。
                    for (int k = 0; k < o.nodes.length; k++) {
                        if (Math.hypot(tail.x() - o.nodes[k].x(),
                                tail.z() - o.nodes[k].z()) < P.gridCell()) {
                            tailJoinsRiver = true;
                            break;
                        }
                    }
                }
            }
            if (isFeeder) {
                feedersExcluded++;
                continue;
            }
            // ★ 合并连接（"缝口→就近汇入"产物）：2~3 节点 + 尾接他河河道。其"头"是
            //   跨区缝点而非真源头，①② 皆无意义（① 的源头谷槽考核只对真源头成立）。
            //   正则支流从真泉眼追踪而来、节点数远多于此，不受影响。
            if (tailJoinsRiver && r.nodes.length <= 3) {
                feedersExcluded++;
                continue;
            }
            // ★ 湖出口河（2026-09-07）：河头在湖的溢出口上，是"湖满溢成溪"的合法
            //   起点，不是泉眼——按源头谷槽考核它等于把出水口判成缺陷（实测
            //   放开压力测试时 marginAvg 2.09→0.79 全是这类假缺陷）。
            if (headAtLakeOutlet) {
                feedersExcluded++;
                continue;
            }
            double hx = r.nodes[0].x(), hz = r.nodes[0].z();
            // 缝头（跨 region 续流端）不考核
            // ★ 用 floorMod：Java 的 % 对负坐标返回负值，会把所有河误判成"贴边界"
            // Math.floorMod 无 double 重载，手写正余数
            double fx = hx - regionSize * Math.floor(hx / regionSize);
            double fz = hz - regionSize * Math.floor(hz / regionSize);
            double toBorder = Math.min(Math.min(fx, regionSize - fx), Math.min(fz, regionSize - fz));
            if (toBorder < seamTol) { seam++; continue; }

            // ① 汇流槽判据：沿垂直流向两侧采样
            double dx = r.nodes[1].x() - hx, dz = r.nodes[1].z() - hz;
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            double px = -dz / len, pz = dx / len;          // 垂直流向（横向）
            // ★ 必须与生产同源：advanceToValleyHead 的汇流槽判据走 groundYAt →
            //   terrainY.yAt → CellGenerator.sampleWu（含侵蚀 tile delta）。原先用
            //   sample()（不含侵蚀）复核，等于拿另一份地形去考核判据——本项目已两次
            //   栽在同一类问题上（WaterfallCornerProbe 的 platform、WaterfallProbe 的
            //   angleFails 都是探针与生产数据不一致造成的假指标）。
            double h0 = terrain.sampleWu(hx, hz).height;
            double hL = terrain.sampleWu(hx - px * off, hz - pz * off).height;
            double hR = terrain.sampleWu(hx + px * off, hz + pz * off).height;
            double margin = Math.min(hL, hR) - h0;         // >0 = 两侧都更高 = 槽内
            n++;
            sumMargin += margin;
            if (margin >= 1.0) inValley++;
            else if (margin >= -1.0) onShoulder++;
            else {
                onRidge++;
                if (badValley.size() < 10) {
                    badValley.add(String.format("    源点 wu(%.0f,%.0f) 块(%d,%d) 高=%.1f "
                                    + "两侧=%.1f/%.1f 槽深裕度=%.1f",
                            hx, hz, (int) Math.floor(hx * hs), (int) Math.floor(hz * hs),
                            h0, hL, hR, margin));
                }
            }

            // ② 是否落在另一条河的过渡区内（支流不考核：汇入处贴近被并入河是 Y 形
            //    汇流的定义；tailJoinsRiver 判据见上方）
            // ★ 半径取【最近点处的局部半宽】，与生产 insideExistingValley 同式
            //   （RiverLineNetwork:1012 逐节点 max(width[i],1)×3.5）。原先整条河只用
            //   o.width[1]——那是源头淡出后的最窄处，量中下游河段时半径普遍偏小，
            //   等于拿一把更短的尺子去量，只会漏报。本项目已四次栽在"探针与生产取
            //   的数据不同源"，这次是【公式不同】。
            double bestExcess = tailJoinsRiver ? 0.0 : Double.POSITIVE_INFINITY;   // <0 = 在别人谷里
            String worst = "";                              // ★ 诊断：被谁侵入
            for (RiverLineRegion.RiverPolyline o : all) {
                if (o == r || tailJoinsRiver) continue;
                double bestD = Double.POSITIVE_INFINITY;
                int bestI = 0;
                for (int i = 0; i + 1 < o.nodes.length; i++) {
                    double ax = o.nodes[i].x(), az = o.nodes[i].z();
                    double bx = o.nodes[i + 1].x(), bz = o.nodes[i + 1].z();
                    double abx = bx - ax, abz = bz - az;
                    double l2 = abx * abx + abz * abz;
                    double t = l2 < 1e-9 ? 0.0
                            : Math.max(0.0, Math.min(1.0, ((hx - ax) * abx + (hz - az) * abz) / l2));
                    double d = Math.hypot(hx - (ax + abx * t), hz - (az + abz * t));
                    if (d < bestD) { bestD = d; bestI = i; }
                }
                // ★ 合法汇流豁免：邻河的【出口】就在本河河头处 → 这是支流汇入，不是
                //   侵入谷壁。正则支流（trace 就近汇入）的尾节点落在目标河的河道/河头
                //   附近，不豁免会把每一条被汇入的主河误判成缺陷。
                var mouth = o.nodes[o.nodes.length - 1];
                if (Math.hypot(hx - mouth.x(), hz - mouth.z()) <= 1.5 * P.gridCell()) continue;
                double wLocal = Math.max(o.width[Math.min(bestI, o.width.length - 1)], 1.0);
                double oValleyBlocks = wLocal * 3.5 * hs;    // valley=3.5×半宽，wu→block
                double ex = bestD * hs - oValleyBlocks;
                if (ex < bestExcess) {
                    bestExcess = ex;
                    // ★ 诊断信息：本河头数 / 对方头数（<10 判为细流）、本河头距对方
                    //   河头与河口的距离、最近点落在对方第几段（0 段=扎进对方源头区）
                    double dToOtherHead = Math.hypot(hx - o.nodes[0].x(), hz - o.nodes[0].z());
                    // ★ oHeadSeam：对方河的河头是否贴着 region 缝（handoff 续流的特征
                    //   ——续流河头被强制放在缝口、且豁免全部谷壁守卫）。用于判定侵入者
                    //   是不是"跨区续流段"。
                    double oFx = o.nodes[0].x() - regionSize * Math.floor(o.nodes[0].x() / regionSize);
                    double oFz = o.nodes[0].z() - regionSize * Math.floor(o.nodes[0].z() / regionSize);
                    double oToBorder = Math.min(Math.min(oFx, regionSize - oFx),
                            Math.min(oFz, regionSize - oFz));
                    // ★ oHeadReg/oMouthReg：对方河两端各落在哪个 region（floor 坐标/640）。
                    //   若两端 region 不同 → 该河横跨缝；若 o 的两端都在 r 所在 region 之外
                    //   却又能贴近 r 的河头 → 证明 region 网格带 margin 重叠、重叠区两边
                    //   各画各的（这才是"跨区互看不见"的几何实体）。
                    int ohrx = (int) Math.floor(o.nodes[0].x() / regionSize);
                    int ohrz = (int) Math.floor(o.nodes[0].z() / regionSize);
                    int omrx = (int) Math.floor(o.nodes[o.nodes.length - 1].x() / regionSize);
                    int omrz = (int) Math.floor(o.nodes[o.nodes.length - 1].z() / regionSize);
                    worst = String.format(
                            "rLen=%d oLen=%d bestSeg=%d/%d d=%.1fwu oHead=%.1fwu "
                                    + "oMouth=%.1fwu wLocal=%.1f oHeadSeam=%b(%.0fwu) "
                                    + "oReg=(%d,%d)->(%d,%d)",
                            r.nodes.length, o.nodes.length, bestI, o.nodes.length - 1,
                            bestD, dToOtherHead,
                            Math.hypot(hx - o.nodes[o.nodes.length - 1].x(),
                                    hz - o.nodes[o.nodes.length - 1].z()),
                            wLocal, oToBorder < seamTol, oToBorder,
                            ohrx, ohrz, omrx, omrz);
                }
            }
            if (bestExcess < 0) {
                insideOther++;
                if (badOther.size() < 10) {
                    badOther.add(String.format("    源点 wu(%.0f,%.0f) 块(%d,%d) 侵入邻河谷壁 "
                                    + "%.1f 格  level=%d nodes=%d w0=%.2f",
                            hx, hz, (int) Math.floor(hx * hs), (int) Math.floor(hz * hs),
                            -bestExcess, r.level, r.nodes.length, r.width[0]));
                }
                // [TEMP] 侵入者的全程几何：沿自身节点走，看侵入深度何时归零（何处出谷）
                for (RiverLineRegion.RiverPolyline o : all) {
                    if (o == r) continue;
                    double ex0 = Double.POSITIVE_INFINITY;
                    int exitNode = -1;
                    for (int i = 0; i < r.nodes.length; i++) {
                        double px0 = r.nodes[i].x(), pz0 = r.nodes[i].z();
                        double bd = Double.POSITIVE_INFINITY;
                        int bi = 0;
                        for (int k = 0; k + 1 < o.nodes.length; k++) {
                            double ax = o.nodes[k].x(), az = o.nodes[k].z();
                            double bx = o.nodes[k + 1].x(), bz = o.nodes[k + 1].z();
                            double abx = bx - ax, abz = bz - az;
                            double l2 = abx * abx + abz * abz;
                            double t = l2 < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0,
                                    ((px0 - ax) * abx + (pz0 - az) * abz) / l2));
                            double d = Math.hypot(px0 - (ax + abx * t), pz0 - (az + abz * t));
                            if (d < bd) { bd = d; bi = k; }
                        }
                        double wL = Math.max(o.width[bi], 1.0) * 3.5 * hs;
                        double ex = bd * hs - wL;
                        if (ex < ex0) ex0 = ex;
                        if (ex >= 0 && exitNode < 0) exitNode = i;
                    }
                    if (ex0 < 0) {
                        System.out.printf("[TEMP] invader nodes=%d 最深侵入=%.1f格 出谷节点=%d"
                                        + " (占全程 %.0f%%) 被侵入河节点=%d wMax=%.1f%n",
                                r.nodes.length, -ex0, exitNode,
                                100.0 * (exitNode < 0 ? r.nodes.length : exitNode) / r.nodes.length,
                                o.nodes.length, o.width[0]);
                        break;
                    }
                }
            }
        }

        System.out.println("=== SourceValleyProbe ===");
        System.out.printf("seed=%d 横向采样距=%.0fwu  河总数=%d  其中缝头=%d（不考核）%n",
                seed, off, n + seam, seam);
        if (n == 0) { System.out.println("无可考核源头"); return; }
        System.out.printf("[M0] marginAvg=%.2f%n", sumMargin / n);
        System.out.printf("[M1] inValley=%d (%.0f%%)%n", inValley, inValley * 100.0 / n);
        System.out.printf("[M1b] onShoulder=%d (%.0f%%)%n", onShoulder, onShoulder * 100.0 / n);
        System.out.printf("[M2] onRidge=%d (%.0f%%)%n", onRidge, onRidge * 100.0 / n);
        System.out.printf("[M3] insideOther=%d (%.0f%%)%n",
                insideOther, insideOther * 100.0 / n);
        System.out.println("   （源头质量考核已排除源前细流 " + feedersExcluded + " 条）");
        if (!badValley.isEmpty()) {
            System.out.println("   非谷地源头样例：");
            for (String s : badValley) System.out.println(s);
        }
        if (!badOther.isEmpty()) {
            System.out.println("   侵入邻河谷地样例：");
            for (String s : badOther) System.out.println(s);
        }
        System.out.println("status=" + ((onRidge * 4 <= n && insideOther * 4 <= n) ? "PASS" : "FAIL"));
    }
}
