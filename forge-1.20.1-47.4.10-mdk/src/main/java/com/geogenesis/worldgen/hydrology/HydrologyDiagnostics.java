package com.geogenesis.worldgen.hydrology;

/** 对水文网格执行不修改数据的拓扑与物理一致性诊断。 */
public final class HydrologyDiagnostics {
    private HydrologyDiagnostics() { }

    public static HydrologyMetrics measure(HydrologyGrid grid, long hash) {
        int total = grid.size() * grid.size();
        int core = 0, rivers = 0, sources = 0, confluences = 0;
        int ocean = 0, lake = 0, boundary = 0, land = 0, violations = 0, maxChain = 0;
        double maxFlow = 0.0;
        for (int i = 0; i < total; i++) {
            if (!grid.isCore(i)) continue;
            core++;
            int downstream = FlowDirectionSolver.downstream(grid, i);
            if (downstream < 0) {
                if (grid.ocean(i)) ocean++;
                else if (grid.lake(i)) lake++;
                else if (isBoundary(grid, i)) boundary++;
                else land++;
            }
            if (!grid.river(i)) continue;
            rivers++;
            maxFlow = Math.max(maxFlow, grid.contributingArea(i));
            if (upstreamRiverCount(grid, i) == 0) sources++;
            if (upstreamRiverCount(grid, i) >= 2) confluences++;
            if (downstream >= 0 && grid.river(downstream)
                    && grid.contributingArea(downstream) + 1e-9 < grid.contributingArea(i)) violations++;
            maxChain = Math.max(maxChain, riverChain(grid, i));
        }
        return new HydrologyMetrics(core, rivers, sources, confluences, ocean, lake,
                boundary, land, violations, maxChain, maxFlow, hash);
    }

    private static int upstreamRiverCount(HydrologyGrid grid, int target) {
        int count = 0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.river(i) && FlowDirectionSolver.downstream(grid, i) == target) count++;
        }
        return count;
    }

    private static int riverChain(HydrologyGrid grid, int start) {
        int length = 0, current = start;
        boolean[] visited = new boolean[grid.size() * grid.size()];
        while (current >= 0 && grid.river(current) && !visited[current]) {
            visited[current] = true;
            length++;
            current = FlowDirectionSolver.downstream(grid, current);
        }
        return length;
    }

    private static boolean isBoundary(HydrologyGrid grid, int index) {
        int x = grid.x(index), z = grid.z(index);
        return x == grid.halo() || z == grid.halo()
                || x == grid.size() - grid.halo() - 1
                || z == grid.size() - grid.halo() - 1;
    }
}
