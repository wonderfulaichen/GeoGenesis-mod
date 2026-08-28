package com.geogenesis.worldgen.hydrology;

import java.util.ArrayList;
import java.util.List;

/** 从汇水面积和坡度生成稳定的河道宽深，使用次幂关系避免尺寸突变。 */
public final class RiverProfileSolver {
    private RiverProfileSolver() { }

    public static List<RiverProfile> solve(HydrologyGrid grid) {
        List<RiverProfile> profiles = new ArrayList<>();
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            int downstream = FlowDirectionSolver.downstream(grid, i);
            double slope = downstream < 0 ? 0.0
                    : Math.max(0.0, (grid.filledElevation(i) - grid.filledElevation(downstream)) / grid.spacing());
            profiles.add(profileAt(grid, i));
        }
        return List.copyOf(profiles);
    }

    public static RiverProfile profileAt(HydrologyGrid grid, int index) {
        int downstream = FlowDirectionSolver.downstream(grid, index);
        double slope = downstream < 0 ? 0.0
                : Math.max(0.0, (grid.filledElevation(index) - grid.filledElevation(downstream)) / grid.spacing());
        double discharge = grid.contributingArea(index);
        double downstreamDischarge = downstream >= 0 && grid.river(downstream)
                ? grid.contributingArea(downstream) : discharge;
        return new RiverProfile(index, downstream, grid.filledElevation(index), slope,
                discharge, section(discharge, downstreamDischarge));
    }

    private static RiverCrossSection section(double discharge, double downstreamDischarge) {
        double flow = Math.max(1.0, Math.sqrt(Math.max(1.0, discharge)
                * Math.max(1.0, downstreamDischarge)));
        double width = 1.5 + 1.8 * Math.pow(flow, 0.22);
        double depth = 0.35 + 0.55 * Math.pow(flow, 0.18);
        double bankWidth = width * 2.0;
        double valleyWidth = bankWidth + 4.0 + 2.0 * Math.pow(flow, 0.14);
        double bankHeight = 0.25 + depth * 0.35;
        return new RiverCrossSection(width, depth, bankWidth, bankHeight, valleyWidth);
    }

}
