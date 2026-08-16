package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 临时诊断：plate 段类型分布（验证支流是否真的在 plate 里）。
 */
public final class PlateDiagProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        RiverNetwork net = new RiverNetwork(gen::terrainEQuick, gen.heightCurve(),
            4, 3.0, 4.0, 0.6, 0.5, 3.0, gen.params().horizontalScale(), null);
        net.setWorldSeed(seed);
        int[] counts = new int[4];
        int plateCount = 0, tribPlates = 0;
        for (int tz = 0; tz < 8; tz++) {
            for (int tx = 0; tx < 8; tx++) {
                RiverPlate pl = net.plateForTile(tx, tz);
                plateCount++;
                boolean hasTrib = false;
                for (RiverSegment s : pl.segments()) {
                    counts[s.type.ordinal()]++;
                    if (s.type == RiverSegmentType.TRIBUTARY) hasTrib = true;
                }
                if (hasTrib) tribPlates++;
            }
        }
        System.out.println("plates=" + plateCount + " tribPlates=" + tribPlates);
        for (RiverSegmentType t : RiverSegmentType.values()) {
            System.out.println(t + ": " + counts[t.ordinal()]);
        }
    }
}
