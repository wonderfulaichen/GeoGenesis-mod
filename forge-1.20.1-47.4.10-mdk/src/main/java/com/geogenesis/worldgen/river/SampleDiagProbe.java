package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 采样命中诊断（2026-08-15 R16）：在指定世界坐标采样河，打印最近河段与距离，
 * 定位"河生成位置不对"的坐标问题。
 */
public final class SampleDiagProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);
        // 采样网格：2048..2304 区域，每 32wu 一格
        int hits = 0, total = 0;
        for (double z = 2048; z < 2304; z += 32) {
            for (double x = 2048; x < 2304; x += 32) {
                total++;
                RiverSample s = net.sampleRiver(x, z);
                if (s.inChannel()) hits++;
            }
        }
        System.out.println("sample hits=" + hits + "/" + total
            + " (" + (100.0 * hits / total) + "%) @(2048..2304)");
        // 逐点打印河段位置（最近 5 条）
        RiverPlate pl = net.plateForTile(16, 16);
        System.out.println("plate(16,16) segs=" + pl.segments().size());
        for (RiverSegment sg : pl.segments()) {
            RiverNode a = sg.path.get(0), b = sg.path.get(sg.path.size() - 1);
            System.out.println("  seg(" + sg.basinX + "," + sg.basinZ + ") " + sg.type
                + " " + sg.path.size() + "pts (" + (int) a.x() + "," + (int) a.z() + ")->("
                + (int) b.x() + "," + (int) b.z() + ") w=" + (int) sg.width);
        }
    }
}
