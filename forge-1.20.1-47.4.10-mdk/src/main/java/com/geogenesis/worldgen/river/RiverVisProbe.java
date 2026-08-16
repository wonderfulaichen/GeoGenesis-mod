package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * ASCII 河网可视化（2026-08-15 R12 临时诊断）：
 * gradlew runRiverVisProbe --args="seed ox oz size"
 * 输出 96×48 字符画：海=~ 陆=. 主河=# 河口=@ 支流=+
 */
public final class RiverVisProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int ox = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int oz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int size = args.length > 3 ? Integer.parseInt(args[3]) : 2048;

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);

        int w = 96, h = 48;
        double step = size / (double) w;
        char[][] grid = new char[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double wx = ox + x * step + step / 2;
                double wz = oz + y * step + step / 2;
                grid[y][x] = net.eAt(wx, wz) < 0 ? '~' : '.';
            }
        }
        // 段路径投影
        for (int tz = (oz - size) / 128 - 1; tz <= (oz + size) / 128 + 1; tz++) {
            for (int tx = (ox - size) / 128 - 1; tx <= (ox + size) / 128 + 1; tx++) {
                for (RiverSegment s : net.plateForTile(tx, tz).segments()) {
                    char c = s.type == RiverSegmentType.MOUTH ? '@'
                        : s.type == RiverSegmentType.TRIBUTARY ? '+' : '#';
                    for (RiverNode nd : s.path) {
                        int gx = (int) ((nd.x() - ox) / step);
                        int gy = (int) ((nd.z() - oz) / step);
                        if (gx >= 0 && gx < w && gy >= 0 && gy < h) grid[gy][gx] = c;
                    }
                }
            }
        }
        // 诊断：支流段位置（R19c 定位支流生成区域）
        java.util.HashSet<Integer> seenTrib = new java.util.HashSet<>();
        for (int tz = (oz - size) / 128 - 1; tz <= (oz + size) / 128 + 1; tz++) {
            for (int tx = (ox - size) / 128 - 1; tx <= (ox + size) / 128 + 1; tx++) {
                for (RiverSegment s : net.plateForTile(tx, tz).segments()) {
                    if (s.type == RiverSegmentType.TRIBUTARY && seenTrib.add(s.uid)) {
                        System.out.println("  trib uid=" + s.uid
                            + " start=(" + (int) s.path.get(0).x() + "," + (int) s.path.get(0).z() + ")"
                            + " pts=" + s.path.size()
                            + " bb=[" + (int) s.minX + ".." + (int) s.maxX
                            + "," + (int) s.minZ + ".." + (int) s.maxZ + "]");
                    }
                }
            }
        }
        System.out.println("=== River network (" + size + "x" + size + " @ " + ox + "," + oz + ") seed=" + seed + " ===");
        for (int y = 0; y < h; y++) {
            System.out.println(new String(grid[y]));
        }
    }
}
