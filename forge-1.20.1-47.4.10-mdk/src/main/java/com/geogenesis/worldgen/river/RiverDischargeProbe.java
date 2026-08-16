package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * discharge 场 ASCII 可视化探针（2026-08-14）。
 *
 * <p>打印某区域 discharge 场（高值 = 液滴汇聚 = 真河道线）叠加主河路径，
 * 一图定位"路径是否贴液滴汇聚线"。用法：gradlew runRiverDischargeProbe --args="seed ox oz size"</p>
 */
public class RiverDischargeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int ox = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int oz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int size = args.length > 3 ? Integer.parseInt(args[3]) : 512; // wu

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), gen::sampleDischarge);

        // 若未指定 origin：自动定位第一条支流段中点（图才有 T——支流是用户关注重点）
        if (args.length <= 1) {
            for (int tz = 0; tz < 16; tz++) {
                for (int tx = 0; tx < 16; tx++) {
                    RiverPlate plate = net.plateForTile(tx, tz);
                    for (RiverSegment s : plate.segments()) {
                        if (s.type == RiverSegmentType.TRIBUTARY && !s.path.isEmpty()) {
                            RiverNode mid = s.path.get(s.path.size() / 2);
                            ox = (int) (mid.x() - size / 2);
                            oz = (int) (mid.z() - size / 2);
                            System.out.println("auto origin=(" + ox + "," + oz + ")");
                            break;
                        }
                    }
                    if (ox != 0 || oz != 0) break;
                }
                if (ox != 0 || oz != 0) break;
            }
        }

        int step = 8; // 每格 8wu → 64×64
        int nx = size / step, nz = size / step;
        char[][] grid = new char[nz][nx];
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                double d = gen.sampleDischarge(ox + ix * step + 4, oz + iz * step + 4);
                grid[iz][ix] = d > 10 ? '#' : d > 5 ? '+' : d > 2 ? '=' : d > 0.5 ? '-' : '.';
            }
        }
        // 叠加主河(R)+支流(T)路径（T 覆盖 R——支流是关注重点）
        int segCount = 0, nodeCount = 0, inGrid = 0;
        int tribSegs = 0, tribNodes = 0, tribInGrid = 0;
        for (int tz = 0; tz < 8; tz++) {
            for (int tx = 0; tx < 8; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    char c;
                    if (s.type == RiverSegmentType.TRIBUTARY) { c = 'T'; tribSegs++; }
                    else if (s.type == RiverSegmentType.REACH || s.type == RiverSegmentType.MOUTH) { c = 'R'; }
                    else continue;
                    segCount++;
                    for (RiverNode nd : s.path) {
                        nodeCount++;
                        int ix = (int) ((nd.x() - ox) / step);
                        int iz = (int) ((nd.z() - oz) / step);
                        if (ix >= 0 && ix < nx && iz >= 0 && iz < nz) {
                            inGrid++;
                            if (c == 'T') { tribInGrid++; tribNodes++; }
                            grid[iz][ix] = c;
                        }
                    }
                }
            }
        }
        System.out.println("--- segs=" + segCount + " nodes=" + nodeCount + " inGrid=" + inGrid
            + " (trib: " + tribSegs + " segs, " + tribInGrid + " pts in grid) ---");

        // ★ 支流高度剖面诊断（用户"完全不跟实际地形"）：打印每条支流的
        //   起点/终点/最高点/最低点地表高度——高度先升后降 = 横穿山脊实锤。
        int tribShown = 0;
        for (int tz = 0; tz < 16; tz++) {
            for (int tx = 0; tx < 16; tx++) {
                RiverPlate plate = net.plateForTile(tx, tz);
                for (RiverSegment s : plate.segments()) {
                    if (s.type != RiverSegmentType.TRIBUTARY || s.path.isEmpty()) continue;
                    double h0 = net.curve().heightFromE(net.eAt(s.path.get(0).x(), s.path.get(0).z()));
                    double hEnd = net.curve().heightFromE(net.eAt(s.path.get(s.path.size() - 1).x(), s.path.get(s.path.size() - 1).z()));
                    double hMax = h0, hMin = h0;
                    double maxRise = 0;
                    double prevH = h0;
                    for (int i = 1; i < s.path.size(); i++) {
                        double h = net.curve().heightFromE(net.eAt(s.path.get(i).x(), s.path.get(i).z()));
                        hMax = Math.max(hMax, h);
                        hMin = Math.min(hMin, h);
                        if (h > prevH + maxRise) maxRise = h - prevH;
                        prevH = h;
                    }
                    if (tribShown < 12 || (hMax - hEnd > 5 && tribShown < 20)) {
                        System.out.println("TRIB(" + s.basinX + "," + s.basinZ + ") startH=" + String.format("%.1f", h0)
                            + " endH=" + String.format("%.1f", hEnd)
                            + " maxH=" + String.format("%.1f", hMax)
                            + " minH=" + String.format("%.1f", hMin)
                            + " maxRise=" + String.format("%.1f", maxRise)
                            + (hMax - hEnd > 5 ? " <<< OVER-RIDGE" : ""));
                        tribShown++;
                    }
                }
            }
        }
        // 输出
        System.out.println("=== discharge field + river path (seed=" + seed + " origin=(" + ox + "," + oz
            + ") size=" + size + ") '#'=heavy '+','=','-','.'=light 'R'=river path ===");
        for (int iz = 0; iz < nz; iz++) {
            System.out.println(new String(grid[iz]));
        }
    }
}
