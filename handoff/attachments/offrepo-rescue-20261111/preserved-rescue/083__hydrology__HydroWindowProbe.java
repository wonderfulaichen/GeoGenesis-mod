package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 【水文窗口逐格图】探针（2026-09-20，<b>零生产改动</b>）—— 用户："跑一遍我给的坐标附近
 * 几处河流与湖泊等水文，你看就知道什么问题了"。
 *
 * <h2>为什么需要它（既有探针不够）</h2>
 * <ul>
 *   <li>{@code WaterViewProbe} 只出 PNG，<b>读不出坐标</b>；其 ASCII 只画【湖掩膜】，
 *       看不到"河在哪里断"；</li>
 *   <li>{@code RiverEndProbe} 给的是河尾统计，<b>看不到空间形态</b>（断口、空档、干河）。</li>
 * </ul>
 *
 * <h2>输出（全部走生产路径 {@code getChunkCells}）</h2>
 * <ol>
 *   <li><b>水体 ASCII</b>：{@code ~} 河/湖水体（{@code riverType != 0}）· {@code O} 海洋 ·
 *       {@code !} <b>该有水却干</b>（{@code height < riverSurfaceY−0.5} 但 {@code riverType == 0}）·
 *       {@code .} 干地；</li>
 *   <li><b>河身份 ASCII</b>：把每条河线的节点按其序号打点（{@code 0-9a-z}），
 *       {@code H}=河头 {@code T}=河尾 ⇒ <b>直接看出"两条河之间有没有空档/断口"</b>；</li>
 *   <li>窗口内【河流清单】：序号 / level / 节点数 / 河头→河尾 / 尾水面 / 到其它河最近距离；</li>
 *   <li>窗口内【湖泊清单】：湖心 / 溢出口水面 Y / 半径 / 洼地格数；</li>
 *   <li>可选【定点行剖面】：逐块打印 {@code height / riverSurfaceY / riverType} ⇒
 *       读出"水在哪一格停、停时地形是否已高于水面"。</li>
 * </ol>
 *
 * <pre>{@code
 *   gradlew runHydroWindowProbe -PprobeArgs="seed blockX blockZ radiusBlocks step [profX profZ]"
 * }</pre>
 */
public final class HydroWindowProbe {

    private HydroWindowProbe() { }

    private static final double WU_PER_BLOCK = 2.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -770;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -400;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 160;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);

        System.out.printf("=== HydroWindowProbe seed=%d 窗口 block x∈[%d,%d] z∈[%d,%d] 步长=%d ===%n",
                seed, bx - radius, bx + radius, bz - radius, bz + radius, step);

        printWaterAscii(gt, bx, bz, radius, step);

        // ---- 河线（用 RiverLineNetwork 取几何；与生产同源） ----
        RiverLineNetwork net = new RiverLineNetwork(gen::terrainEQuick, null,
                gen.heightCurve(), seed, RiverLineParams.defaults());
        int pad = 2;
        int rx0 = Math.floorDiv(bx - radius, 640) - pad, rx1 = Math.floorDiv(bx + radius, 640) + pad;
        int rz0 = Math.floorDiv(bz - radius, 640) - pad, rz1 = Math.floorDiv(bz + radius, 640) + pad;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) net.region(rx, rz);
        }
        List<RiverLineRegion> regions = net.cachedList();
        List<RiverLineRegion.RiverPolyline> inWin = collectInWindow(regions, bx, bz, radius);
        printRiverAscii(inWin, bx, bz, radius, step);
        printRivers(inWin, regions);
        printLakes(regions, bx, bz, radius);

        if (args.length >= 7) {
            int px = Integer.parseInt(args[5]), pz = Integer.parseInt(args[6]);
            printProfile(gt, bx, bz, radius, px, pz);
        }
    }

    // ==================== ① 水体 ASCII ====================

    private static void printWaterAscii(GeoGenesisTerrain gt, int bx, int bz, int radius, int step) {
        int n = 2 * radius + 1;
        int cntWet = 0, cntDry = 0, cntOcean = 0;
        for (int j = 0; j < n; j += step) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < n; i += step) {
                char ch = '.';
                boolean wet = false, ocean = false, dry = false;
                for (int dj = 0; dj < step && !ocean; dj++) {
                    for (int di = 0; di < step; di++) {
                        int x = bx - radius + i + di, z = bz - radius + j + dj;
                        Cell c = cellAt(gt, x, z);
                        if (c.isWater()) { ocean = true; break; }
                        if (c.riverType != 0) wet = true;
                        else if (c.height < c.riverSurfaceY - 0.5) dry = true;
                    }
                }
                if (ocean) { ch = 'O'; cntOcean++; }
                else if (wet) { ch = '~'; cntWet++; }
                else if (dry) { ch = '!'; cntDry++; }
                sb.append(ch);
            }
            System.out.println(sb);
        }
        System.out.printf("  [图例] ~ 河/湖水体 · O 海洋 · ! 该有水却干 · . 干地%n");
        System.out.printf("  [统计] 水格=%d  海洋=%d  ★该有水却干=%d%n%n", cntWet, cntOcean, cntDry);
    }

    // ==================== ② 河身份 ASCII ====================

    private static void printRiverAscii(List<RiverLineRegion.RiverPolyline> rs,
                                        int bx, int bz, int radius, int step) {
        int n = 2 * radius + 1;
        int gw = (n + step - 1) / step;
        char[][] grid = new char[gw][gw];
        for (char[] row : grid) java.util.Arrays.fill(row, '.');

        for (int idx = 0; idx < rs.size(); idx++) {
            RiverLineRegion.RiverPolyline p = rs.get(idx);
            char ch = idx < 10 ? (char) ('0' + idx) : (char) ('a' + idx - 10);
            for (int k = 0; k < p.nodes.length; k++) {
                int x = (int) Math.round(p.nodes[k].x() * WU_PER_BLOCK);
                int z = (int) Math.round(p.nodes[k].z() * WU_PER_BLOCK);
                put(grid, gw, bx, bz, radius, step, x, z, ch);
                if (k == 0) put(grid, gw, bx, bz, radius, step, x, z, 'H');
                if (k == p.nodes.length - 1) put(grid, gw, bx, bz, radius, step, x, z, 'T');
            }
        }
        System.out.printf("  ── 河身份 ASCII（同 ① 网格；每条河一个字符，H=河头 T=河尾）──%n");
        for (char[] row : grid) {
            System.out.print("  ");
            System.out.println(new String(row));
        }
        System.out.println();
    }

    private static void put(char[][] g, int gw, int bx, int bz, int radius, int step,
                            int x, int z, char ch) {
        int i = Math.floorDiv(x - (bx - radius), step);
        int j = Math.floorDiv(z - (bz - radius), step);
        if (i < 0 || j < 0 || i >= gw || j >= gw) return;
        g[j][i] = ch;
    }

    // ==================== ③ 河流清单 ====================

    private static List<RiverLineRegion.RiverPolyline> collectInWindow(
            List<RiverLineRegion> regions, int bx, int bz, int radius) {
        List<RiverLineRegion.RiverPolyline> out = new ArrayList<>();
        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.RiverPolyline p : r.rivers) {
                for (int k = 0; k < p.nodes.length; k++) {
                    double x = p.nodes[k].x() * WU_PER_BLOCK, z = p.nodes[k].z() * WU_PER_BLOCK;
                    if (Math.abs(x - bx) <= radius && Math.abs(z - bz) <= radius) {
                        out.add(p);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static void printRivers(List<RiverLineRegion.RiverPolyline> rs,
                                    List<RiverLineRegion> all) {
        System.out.printf("  ── 窗口内河流 %d 条 ──%n", rs.size());
        System.out.printf("  %-3s %-5s %-6s %-22s %-22s %-9s %-9s %s%n",
                "序", "level", "节点", "河头 block", "河尾 block", "头水面", "尾水面", "到其它河最近");
        for (int i = 0; i < rs.size(); i++) {
            RiverLineRegion.RiverPolyline p = rs.get(i);
            int n = p.nodes.length;
            double d = nearestOtherDist(all, p);
            System.out.printf("  %-3d %-5d %-6d %-22s %-22s %-9.1f %-9.1f %.0f block%n",
                    i, p.level, n,
                    fmt(p.nodes[0].x() * WU_PER_BLOCK, p.nodes[0].z() * WU_PER_BLOCK),
                    fmt(p.nodes[n - 1].x() * WU_PER_BLOCK, p.nodes[n - 1].z() * WU_PER_BLOCK),
                    p.surfaceY[0], p.surfaceY[n - 1], d);
        }
        System.out.println();
    }

    private static String fmt(double x, double z) {
        return String.format("(%.0f,%.0f)", x, z);
    }

    private static double nearestOtherDist(List<RiverLineRegion> all,
                                           RiverLineRegion.RiverPolyline self) {
        double best = Double.MAX_VALUE;
        double tx = self.nodes[self.nodes.length - 1].x(), tz = self.nodes[self.nodes.length - 1].z();
        for (RiverLineRegion r : all) {
            for (RiverLineRegion.RiverPolyline q : r.rivers) {
                if (q == self) continue;
                for (int k = 0; k < q.nodes.length; k++) {
                    double d = Math.hypot(q.nodes[k].x() - tx, q.nodes[k].z() - tz);
                    if (d < best) best = d;
                }
            }
        }
        return best == Double.MAX_VALUE ? -1 : best * WU_PER_BLOCK;
    }

    // ==================== ④ 湖泊清单 ====================

    private static void printLakes(List<RiverLineRegion> regions, int bx, int bz, int radius) {
        int cnt = 0;
        System.out.printf("  ── 窗口内湖泊 ──%n");
        for (RiverLineRegion r : regions) {
            for (RiverLineRegion.LakeNode lk : r.lakes) {
                double cx = lk.x * WU_PER_BLOCK, cz = lk.z * WU_PER_BLOCK;
                if (Math.abs(cx - bx) > radius || Math.abs(cz - bz) > radius) continue;
                cnt++;
                int cells = lk.cellX == null ? 0 : lk.cellX.length;
                System.out.printf("    湖 心=(%.0f,%.0f)  溢出口水面Y=%.2f  半径=%.0f block  "
                                + "洼地格数=%d  最大水深=%.2f  region(%d,%d)%n",
                        cx, cz, lk.height, lk.radius * WU_PER_BLOCK, cells, lk.depth, r.rx, r.rz);
            }
        }
        if (cnt == 0) System.out.println("    （窗口内无湖）");
        System.out.println();
    }

    // ==================== ⑤ 定点行剖面 ====================

    private static void printProfile(GeoGenesisTerrain gt, int bx, int bz, int radius,
                                     int px, int pz) {
        int n = 2 * radius + 1;
        System.out.printf("  ── 行剖面 z=%d，x∈[%d,%d]（h=地形 / surf=河水面 / rt=riverType）──%n",
                pz, bx - radius, bx + radius);
        boolean prevWet = false;
        for (int k = 0; k < n; k++) {
            int x = bx - radius + k;
            Cell c = cellAt(gt, x, pz);
            boolean wet = c.riverType != 0;
            boolean dryBelow = !wet && c.height < c.riverSurfaceY - 0.5;
            if (wet != prevWet || dryBelow) {
                System.out.printf("    x=%-7d h=%8.2f surf=%8.2f rt=%-2d %s%n", x, c.height,
                        c.riverSurfaceY, c.riverType,
                        wet ? "水" : (dryBelow ? "★该有水却干" : "干地"));
            }
            prevWet = wet;
        }
        System.out.println();
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }
}
