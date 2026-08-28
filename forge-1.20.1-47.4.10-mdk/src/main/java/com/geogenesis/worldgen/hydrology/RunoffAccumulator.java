package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;
import java.util.PriorityQueue;

/** 按确定性拓扑顺序累积降雨与汇水面积。 */
public final class RunoffAccumulator {
    private RunoffAccumulator() { }

    public static void accumulate(HydrologyGrid grid) {
        int total = grid.size() * grid.size();
        double[] area = grid.contributingAreas();
        int[] indegree = new int[total];
        Arrays.fill(area, 0.0);
        for (int i = 0; i < total; i++) {
            area[i] = grid.rainfall(i);
            int downstream = FlowDirectionSolver.downstream(grid, i);
            if (downstream >= 0) indegree[downstream]++;
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < total; i++) if (indegree[i] == 0) ready.add(i);
        int processed = 0;
        while (!ready.isEmpty()) {
            int current = ready.poll();
            processed++;
            int downstream = FlowDirectionSolver.downstream(grid, current);
            if (downstream < 0) continue;
            area[downstream] += area[current];
            if (--indegree[downstream] == 0) ready.add(downstream);
        }
        if (processed != total) throw new IllegalStateException("flow graph contains a cycle");
    }
}
