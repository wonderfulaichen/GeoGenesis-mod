package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;

/** 水文实验器管线门面，不依赖 Minecraft。 */
public final class HydrologySimulator {
    private HydrologySimulator() { }

    public static HydrologyResult run(CellGenerator terrain, long seed, int originX, int originZ,
                                      int coreSize, int halo, int spacing, double riverAreaThreshold) {
        HydrologyGrid grid = HydrologyGrid.sample(terrain, seed, originX, originZ, coreSize, halo, spacing);
        DrainageResolver.resolve(grid);
        RunoffAccumulator.accumulate(grid);
        // ★ 2026-08-27 实机假水修复：128wu² 阈值让 ~39% 陆地成河 → 山坡假水池塘。
        //   提高到 2048wu²（≈45×45 格汇水）只保留真实河谷水流。
        int rivers = RiverNetworkExtractor.markRivers(grid,
                Math.max(riverAreaThreshold, 2048.0));
        int outlets = 0, oceanOutlets = 0, lakes = 0, flowCycles = 0, boundaryRivers = 0;
        double maxFlow = 0.0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.flow(i) < 0) { outlets++; if (grid.ocean(i)) oceanOutlets++; }
            if (grid.lake(i)) lakes++;
            maxFlow = Math.max(maxFlow, grid.contributingArea(i));
        }
        return new HydrologyResult(grid, rivers, outlets, oceanOutlets, lakes,
                maxFlow, flowCycles, boundaryRivers, hash(grid));
    }

    private static boolean hasCycle(HydrologyGrid grid, int start) {
        int current = start;
        for (int steps = 0; steps <= grid.size() * grid.size(); steps++) {
            current = FlowDirectionSolver.downstream(grid, current);
            if (current < 0) return false;
            if (current == start) return true;
        }
        return true;
    }

    private static long hash(HydrologyGrid grid) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            h ^= Double.doubleToLongBits(grid.filledElevation(i)); h *= 0x100000001b3L;
            h ^= grid.flow(i); h *= 0x100000001b3L;
            h ^= Double.doubleToLongBits(grid.contributingArea(i)); h *= 0x100000001b3L;
        }
        return h;
    }
}
