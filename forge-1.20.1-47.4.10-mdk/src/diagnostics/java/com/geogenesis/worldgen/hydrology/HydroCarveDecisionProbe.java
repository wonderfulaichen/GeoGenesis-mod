package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.sim.FlowState;
import com.geogenesis.worldgen.hydrology.sim.HydroContract;
import com.geogenesis.worldgen.hydrology.sim.HydroFlowGeometry;
import com.geogenesis.worldgen.hydrology.sim.HydroSample;
import com.geogenesis.worldgen.hydrology.sim.HydroTileKey;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 落块决策判别探针（2026-09-30，"先测量后改"）—— 两段测量：
 *
 * <h3>① 四段瀑布（用户 bug 2"河不成河道"）</h3>
 * <p>逐格：核心状态 → sampleBlockAll 样本 → 车床 fillWater 四门控 → 落块 rt/可见水。
 * 哪一段掉链一目了然。深挖格会打开 {@code CARVE-TRACE}（门控逐项值）。</p>
 *
 * <h3>② 纵向链追踪（用户 bug 1"河到湖差一点" + 断续方块）</h3>
 * <p>从每个 SOURCE 沿真实 {@code down[]} 走：链长 / NONE 打断 / 断点是否 tile 边界
 * （rings 截断与端口丢水的指纹）/ 终点（SEA·LAKE·出窗）。横断面只看到河宽，
 * 纵向连续性必须沿链看 —— 这正是本段存在的原因。</p>
 *
 * <p>口径：全部取生产自身（同一 engine / carver / 落块）。运行：
 * {@code gradlew runHydroCarveDecisionProbe [-PprobeArgs="seed bx bz radius"]}</p>
 */
public final class HydroCarveDecisionProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 64;

        TerrainParams tp = TerrainParams.defaults();
        double hs = tp.horizontalScale();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        HydrologyExperimentEngine eng = new HydrologyExperimentEngine(gen, seed);

        Map<Long, double[]> originalCache = new HashMap<>();
        int x0 = bx - radius, z0 = bz - radius, w = 2 * radius + 1;

        // ---- 四段瀑布计数 ----
        int flowN = 0, flowNoSample = 0, flowNoFill = 0, flowRt0 = 0, flowNoWater = 0;
        // ★ 第 6 段【雕刻量】（2026-09-30 用户澄清"是雕刻的河道不生成"）：
        //   planCut = original(无侵蚀基线) − carvedGroundY(计划雕刻面)；
        //   ≥0.5 才算"真的挖出了河槽"。有水 ≠ 有槽 —— 这是独立判据。
        int flowCut = 0;
        double cutMin = 99, cutMax = -99, cutSum = 0;
        int noCutX = -1, noCutZ = -1;
        int lakeN = 0, lakeNoSample = 0, lakeNoFill = 0, lakeRt0 = 0, lakeNoWater = 0;
        int noneN = 0, noneHasSample = 0;
        int[] stCnt = new int[FlowState.values().length];
        int exFlowX = -1, exFlowZ = -1, exVisX = -1, exVisZ = -1;

        for (int z = z0; z < z0 + w; z++) {
            for (int x = x0; x < x0 + w; x++) {
                Cell cell = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                HydroSample s = eng.hydroSampleAt(x + 0.5, z + 0.5);
                FlowState st = s == null ? FlowState.NONE : s.state();
                stCnt[st.ordinal()]++;

                List<HydrologyBlockSample> samples = eng.sampleBlockAll(x, z, hs);
                double orig = originalAt(originalCache, gen, hs, x, z);
                HydrologyBlockCarvedColumn col = HydrologyBlockCarver.carveColumnAt(
                        eng, x, z, orig, hs);
                boolean rt = cell.riverType != 0;
                boolean visible = (rt && cell.riverSurfaceY > cell.height) || cell.isWater()
                        || cell.height < 62.0;
                boolean fill = col != null && col.fillWater();

                if (st.isFlowing()) {
                    flowN++;
                    if (samples.isEmpty()) flowNoSample++;
                    if (!fill) {
                        flowNoFill++;
                        if (exFlowX < 0) { exFlowX = x; exFlowZ = z; }
                    }
                    if (!rt) flowRt0++;
                    if (!visible) flowNoWater++;
                    if (col != null) {
                        double planCut = orig - col.carvedGroundY();
                        cutSum += planCut;
                        if (planCut < cutMin) cutMin = planCut;
                        if (planCut > cutMax) cutMax = planCut;
                        if (planCut >= 0.5) {
                            flowCut++;
                        } else if (noCutX < 0) {
                            noCutX = x; noCutZ = z;
                        }
                    }
                } else if (st == FlowState.LAKE_STORAGE) {
                    lakeN++;
                    if (samples.isEmpty()) lakeNoSample++;
                    if (!fill) {
                        lakeNoFill++;
                        if (exFlowX < 0) { exFlowX = x; exFlowZ = z; }
                    }
                    if (!rt) lakeRt0++;
                    if (!visible) lakeNoWater++;
                } else if (st == FlowState.NONE) {
                    noneN++;
                    if (!samples.isEmpty()) noneHasSample++;
                }

                if (fill && rt && !(cell.riverSurfaceY > cell.height)) {
                    if (exVisX < 0) { exVisX = x; exVisZ = z; }
                }
            }
        }

        System.out.println("=== HydroCarveDecisionProbe seed=" + seed
                + " 窗口=" + w + "×" + w + " (" + bx + "," + bz + "±" + radius + ") ===");
        StringBuilder sb = new StringBuilder("  状态: ");
        for (FlowState v : FlowState.values()) {
            if (stCnt[v.ordinal()] > 0) sb.append(v).append('=').append(stCnt[v.ordinal()]).append(' ');
        }
        System.out.println(sb);
        System.out.println("  —— 河流格(isFlowing) 四段瀑布 ——");
        System.out.printf("    状态=河 %d → 有样本 %d → 有fill %d → 落块rt≠0 %d → 可见水 %d%n",
                flowN, flowN - flowNoSample, flowN - flowNoFill,
                flowN - flowRt0, flowN - flowNoWater);
        System.out.println("  —— 湖泊格(LAKE_STORAGE) 四段瀑布 ——");
        System.out.printf("    状态=湖 %d → 有样本 %d → 有fill %d → 落块rt≠0 %d → 可见水 %d%n",
                lakeN, lakeN - lakeNoSample, lakeN - lakeNoFill,
                lakeN - lakeRt0, lakeN - lakeNoWater);
        System.out.println("  —— NONE 格 ——");
        System.out.printf("    %d 格 · 其中【有样本】%d（应=0：状态与样本矛盾）%n", noneN, noneHasSample);
        System.out.println("  ★ fill 判水但落块不可见 (riverSurfaceY≤groundY) 首例: "
                + (exVisX < 0 ? "无" : exVisX + "," + exVisZ));
        System.out.println("  ★ 河格无 fill 首例: " + (exFlowX < 0 ? "无" : exFlowX + "," + exFlowZ));
        System.out.println("  —— 雕刻量（第 6 段：河槽挖没挖）——");
        System.out.printf("    河格 %d · 挖出槽(计划下切≥0.5) %d (%.1f%%) · 计划下切 min/均/max = %.2f/%.2f/%.2f%n",
                flowN, flowCut, flowN == 0 ? 0 : 100.0 * flowCut / flowN,
                flowN == 0 ? 0 : cutMin, flowN == 0 ? 0 : cutSum / flowN, cutMax);
        if (noCutX >= 0) {
            System.out.println("  ★ 河格【未挖槽】首例: " + noCutX + "," + noCutZ);
            double o = originalAt(originalCache, gen, hs, noCutX, noCutZ);
            HydrologyBlockCarvedColumn nc = HydrologyBlockCarver.carveColumnAt(eng, noCutX, noCutZ, o, hs);
            List<HydrologyBlockSample> ns = eng.sampleBlockAll(noCutX, noCutZ, hs);
            System.out.printf("    它: original=%.2f carved=%.2f surf=%.2f 水深计划=%.2f 样本数=%d%n",
                    o, nc == null ? Double.NaN : nc.carvedGroundY(),
                    nc == null ? Double.NaN : nc.waterSurfaceY(),
                    nc == null ? Double.NaN : nc.waterSurfaceY() - nc.carvedGroundY(),
                    ns.size());
        }

        // ---- 深挖：CARVE-TRACE 四门控 ----
        if (exFlowX >= 0) {
            System.out.println();
            System.out.println("  [深挖] 首个无fill河格 (" + exFlowX + "," + exFlowZ + ") —— 重跑并打开门控 trace:");
            double orig = originalAt(originalCache, gen, hs, exFlowX, exFlowZ);
            HydrologyBlockCarver.TRACE_BLOCK = HydrologyBlockCarver.traceKey(exFlowX, exFlowZ);
            HydrologyBlockCarvedColumn c = HydrologyBlockCarver.carveColumnAt(
                    eng, exFlowX, exFlowZ, orig, hs);
            HydrologyBlockCarver.TRACE_BLOCK = Long.MIN_VALUE;
            System.out.println("  [深挖] fillWater=" + (c != null && c.fillWater())
                    + " carved=" + (c == null ? "-" : String.format("%.2f", c.carvedGroundY()))
                    + " surf=" + (c == null ? "-" : String.format("%.2f", c.waterSurfaceY())));
        }

        // ---- 出图：玩家实际看到的水（车床落块口径，1px = 1 块）----
        //   ⚠ 与核心状态图不同：核心状态是【4 块格】量化的，而玩家看到的河道是
        //   车床逐块判定（fillWater ∧ 水面 > 床面）的结果 ⇒ 验证"方块感"必须用这张图。
        int rr = Math.min(radius, 96);
        int rw = 2 * rr + 1;
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    rw, rw, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int waterPx = 0, dryChanPx = 0;
            for (int z = bz - rr; z <= bz + rr; z++) {
                for (int x = bx - rr; x <= bx + rr; x++) {
                    double o = originalAt(originalCache, gen, hs, x, z);
                    HydrologyBlockCarvedColumn c = HydrologyBlockCarver.carveColumnAt(eng, x, z, o, hs);
                    int px = x - (bx - rr), pz = z - (bz - rr);
                    int g = hillshade(originalCache, gen, hs, x, z, o);
                    if (c == null) { img.setRGB(px, pz, g); continue; }
                    boolean water = c.fillWater() && c.waterSurfaceY() > c.carvedGroundY() + 0.5;
                    boolean bed = c.carvedGroundY() < o - 0.5;
                    if (water) { img.setRGB(px, pz, 0x2E86FF); waterPx++; }
                    else if (bed) { img.setRGB(px, pz, 0x8B6B4A); dryChanPx++; }   // 挖了槽但无水
                    else img.setRGB(px, pz, g);
                }
            }
            java.io.File dir = new java.io.File("build/hydrocarve");
            dir.mkdirs();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, "player_view.png"));
            System.out.printf("  [出图] build/hydrocarve/player_view.png（1px=1块 · 水面 %d · 干河槽 %d）%n",
                    waterPx, dryChanPx);
        } catch (Exception e) {
            System.out.println("  出图失败: " + e);
        }

        // ---- 横断面（只看河宽；纵向看下面的链追踪）----
        System.out.println();
        System.out.println("  [横断面] z=" + bz + " 行状态压缩:");
        StringBuilder runs = new StringBuilder();
        FlowState prev = null;
        int cnt = 0;
        for (int x = x0; x < x0 + w; x++) {
            HydroSample s = eng.hydroSampleAt(x + 0.5, bz + 0.5);
            FlowState st = s == null ? FlowState.NONE : s.state();
            if (st == prev) { cnt++; continue; }
            if (prev != null) runs.append(prev).append('×').append(cnt).append(" → ");
            prev = st;
            cnt = 1;
        }
        runs.append(prev).append('×').append(cnt);
        System.out.println("    " + runs);

        // ---- 纵向链追踪：河断在哪 ----
        System.out.println();
        System.out.println("  [纵向链追踪] 每源沿 down 走（tile=64格；边界=格mod64∈{0,63}）:");
        int chains = 0, totalLen = 0, brokenByNone = 0, breakAtTileEdge = 0;
        int reachedSea = 0, reachedLake = 0, outWin = 0;
        StringBuilder chainLog = new StringBuilder();
        for (int z = z0; z < z0 + w; z++) {
            for (int x = x0; x < x0 + w; x++) {
                HydroSample s = eng.hydroSampleAt(x + 0.5, z + 0.5);
                if (s == null || s.state() != FlowState.SOURCE) continue;
                chains++;
                int len = 0, gapRun = 0, edgeGap = 0;
                int cgx = HydroContract.cellX(x), cgz = HydroContract.cellZ(z);
                String end = "?";
                for (int step = 0; step < 4000; step++) {
                    len++;
                    HydroSample cs = eng.hydroSampleAt(
                            HydroContract.cellCenterX(cgx), HydroContract.cellCenterZ(cgz));
                    if (cs == null) {
                        gapRun++;
                        int mx = Math.floorMod(cgx, 64), mz = Math.floorMod(cgz, 64);
                        if (mx == 0 || mx == 63 || mz == 0 || mz == 63) edgeGap++;
                        if (gapRun >= 32) {
                            end = "NONE断@" + cgx + "," + cgz
                                    + (edgeGap > 0 ? " [疑似tile边界]" : " [非边界]");
                            break;
                        }
                    } else {
                        gapRun = 0;
                        if (cs.state() == FlowState.SEA) { end = "SEA"; break; }
                        if (cs.state() == FlowState.LAKE_STORAGE) { end = "LAKE"; break; }
                    }
                    var res = eng.hydroSolver().result(new HydroTileKey(
                            eng.hydroSolver().seed(),
                            HydroTileKey.tileOfCell(cgx), HydroTileKey.tileOfCell(cgz), 0));
                    var f = res.topo.field;
                    int lx = cgx - f.winMinX, lz = cgz - f.winMinZ;
                    if (!f.inside(lx, lz)) { end = "出窗"; break; }
                    int d = res.topo.down[lx * f.w + lz];
                    if (d < 0) { end = "窗口边出口"; break; }
                    cgx = f.globalX(d);
                    cgz = f.globalZ(d);
                }
                totalLen += len;
                if (end.startsWith("NONE断")) {
                    brokenByNone++;
                    if (end.contains("疑似tile边界")) breakAtTileEdge++;
                }
                if ("SEA".equals(end)) reachedSea++;
                if ("LAKE".equals(end)) reachedLake++;
                if (end.contains("窗口") || end.contains("出窗")) outWin++;
                if (chains <= 12) {
                    chainLog.append(String.format("    源(%d,%d) 长%d 终点[%s]%n", x, z, len, end));
                }
            }
        }
        System.out.print(chainLog);
        System.out.printf("    汇总: 源%d 平均链长%.1f · NONE打断链 %d（tile边界 %d）· 到海 %d · 入湖 %d · 出窗 %d%n",
                chains, chains == 0 ? 0 : totalLen / (double) chains,
                brokenByNone, breakAtTileEdge, reachedSea, reachedLake, outWin);
        var sol = eng.hydroSolver();
        System.out.printf("    solver: cache=%d truncations=%d cycles=%d nonConverged=%d%n",
                sol.cachedTiles(), sol.truncations(), sol.cycleDetections(), sol.nonConverged());
        System.out.println("    ★ NONE 断点集中在 tile 边界 ⇒ rings 截断/端口丢水（跨 tile 断流根因）");

        // ---- 选档：阈值 → 河网密度 / 宽度（同一份解析，不重跑 solver）----
        //   物理依据：现实中"河道起始集水面积"约 10^4~10^6 块²（0.01~1 km²）；
        //   河网密度（河长/面积）约 1~5 km/km²。用它判断当前阈值偏不偏小。
        System.out.println();
        System.out.println("  [选档] 阈值 → 河格占比 · 河头密度 · 平均半宽（同窗口 " + (w * w) + " 格）:");
        double[] cands = {120, 300, 800, 1500, 3000, 6000, 12000};
        double cellB2 = (double) HydroContract.CELL_BLOCKS * HydroContract.CELL_BLOCKS;
        double winBlocks2 = w * (double) w * cellB2;
        for (double thr : cands) {
            long riv = 0, heads = 0;
            double wSum = 0;
            for (int z = z0; z < z0 + w; z++) {
                for (int x = x0; x < x0 + w; x++) {
                    int gx = HydroContract.cellX(x), gz = HydroContract.cellZ(z);
                    var res = eng.hydroSolver().result(new HydroTileKey(seed,
                            HydroTileKey.tileOfCell(gx), HydroTileKey.tileOfCell(gz), 0));
                    var fld = res.topo.field;
                    int lx = gx - fld.winMinX, lz = gz - fld.winMinZ;
                    if (!fld.inside(lx, lz)) continue;
                    int i = lx * fld.w + lz;
                    if (res.topo.sea[i] || res.topo.basinId[i] >= 0) continue;
                    double q = res.balance.discharge[i];
                    if (q < thr) continue;
                    riv++;
                    wSum += HydroFlowGeometry.widthFor(q, res.cfg);
                    boolean up = false;
                    for (int d = 0; d < 8 && !up; d++) {
                        int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                        if (!fld.inside(nx, nz)) continue;
                        int nb = nx * fld.w + nz;
                        if (res.topo.down[nb] != i) continue;
                        if (res.balance.discharge[nb] >= thr) up = true;
                    }
                    if (!up) heads++;
                }
            }
            System.out.printf("    阈值 %6.0f → 河格 %6.2f%% · 河头 %6.1f 个/10^6块² · 平均半宽 %.1f 块%n",
                    thr, 100.0 * riv / (w * (double) w),
                    heads * 1e6 / winBlocks2,
                    riv == 0 ? 0 : wSum / riv);
        }
        System.out.println("    （现实参照：河头约 1~100 个/10^6块²；河格占比约 0.3~3%）");
    }

    /** 廉价山体阴影（NW 光）；邻居高度走缓存，成本可忽略。 */
    private static int hillshade(Map<Long, double[]> cache, CellGenerator gen, double hs,
                                 int x, int z, double h) {
        double hx = originalAt(cache, gen, hs, x + 1, z);
        double hz = originalAt(cache, gen, hs, x, z + 1);
        double d = 0.7 * 1.0 + 0.5 * (-(h - hx)) + 0.51 * (-(h - hz));
        double len = Math.sqrt(0.25 + 0.49 + 0.2601);
        int g = (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d / len)));
        return (g << 16) | (g << 8) | g;
    }

    private static double originalAt(Map<Long, double[]> cache, CellGenerator gen,
                                     double hs, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        double[] arr = cache.get(key);
        if (arr == null) {
            Cell[] cells = HydrologyChunkSampling.sample(gen, hs, cx, cz);
            arr = new double[256];
            for (int i = 0; i < 256; i++) arr[i] = cells[i].height;
            cache.put(key, arr);
        }
        return arr[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private HydroCarveDecisionProbe() { }
}
