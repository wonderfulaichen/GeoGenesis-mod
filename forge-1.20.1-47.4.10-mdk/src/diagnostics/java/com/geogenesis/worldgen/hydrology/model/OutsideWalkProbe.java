package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 从边框格出发，按 continueOutside 的算法（严格下降 8 邻、seen 去重）实际走一遍，
 * 看它到底停在哪、为什么停（无下降 / 步数耗尽 / 到海）。
 */
public final class OutsideWalkProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int startX = args.length > 1 ? Integer.parseInt(args[1]) : -458;
        int startZ = args.length > 2 ? Integer.parseInt(args[2]) : 608;
        int maxSteps = args.length > 3 ? Integer.parseInt(args[3]) : 2048;

        var tp = TerrainParams.defaults();
        double hs = Math.max(0.01, tp.horizontalScale());
        var gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double seaLevel = gen.seaLevel();

        int bx = startX, bz = startZ;
        double here = gen.erodedHeight(bx / hs, bz / hs);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        seen.add(((long) bx << 32) ^ (bz & 0xffffffffL));
        System.out.printf("start=(%d,%d) h=%.3f seaLevel=%.3f%n", bx, bz, here, seaLevel);

        String why = "EXHAUSTED";
        int i = 0;
        for (; i < maxSteps; i++) {
            int bestX = bx, bestZ = bz;
            double best = here;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int cx = bx + dx, cz = bz + dz;
                    long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                    if (seen.contains(key)) continue;
                    double h = gen.erodedHeight(cx / hs, cz / hs);
                    if (!Double.isFinite(h)) continue;
                    if (h < best - 1e-9) { best = h; bestX = cx; bestZ = cz; }
                }
            }
            if (bestX == bx && bestZ == bz) { why = "NO_DESCENT"; break; }
            bx = bestX; bz = bestZ; here = best;
            seen.add(((long) bx << 32) ^ (bz & 0xffffffffL));
            if (here < seaLevel) { why = "SEA"; break; }
        }
        System.out.printf("走了 %d 步后停在 (%d,%d) h=%.3f  reason=%s%n", i, bx, bz, here, why);
        System.out.println("SEA".equals(why) ? "能到海" : "到不了海 ⇒ continueOutside 会返回 null");
    }
}
