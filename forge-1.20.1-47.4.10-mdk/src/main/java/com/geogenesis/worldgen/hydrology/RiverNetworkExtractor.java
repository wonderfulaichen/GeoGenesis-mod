package com.geogenesis.worldgen.hydrology;

import java.util.ArrayList;
import java.util.List;

/** 从汇水面积提取河网与源头/汇流节点之间的连续河段。 */
public final class RiverNetworkExtractor {
    private RiverNetworkExtractor() { }

    public static int markRivers(HydrologyGrid grid, double areaThreshold) {
        int rivers = 0;
        double threshold = threshold(grid, areaThreshold);
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.isCore(i) && !grid.ocean(i) && !grid.lake(i)
                    && grid.contributingArea(i) >= threshold) {
                grid.rivers()[i] = true;
                rivers++;
            }
        }
        return rivers;
    }

    public static List<RiverSegment> extractSegments(HydrologyGrid grid) {
        List<RiverSegment> segments = new ArrayList<>();
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i) || !isNode(grid, i)) continue;
            appendSegments(grid, i, segments);
        }
        return List.copyOf(segments);
    }

    public static RiverNetworkSummary summarize(HydrologyGrid grid) {
        List<RiverSegment> segments = extractSegments(grid);
        List<RiverOutlet> outlets = new ArrayList<>();
        RiverSegment main = null;
        double discharge = 0.0;
        for (RiverSegment segment : segments) {
            int next = FlowDirectionSolver.downstream(grid, segment.target());
            if (next < 0 || !grid.river(next)) {
                outlets.add(outlet(grid, next, segment.target(), segment.targetFlow()));
            }
            if (main == null || segment.targetFlow() > main.targetFlow()) main = segment;
            discharge += segment.targetFlow();
        }
        return new RiverNetworkSummary(List.copyOf(segments), List.copyOf(outlets), main, discharge);
    }

    private static RiverOutlet outlet(HydrologyGrid grid, int next, int cell, double flow) {
        if (next >= 0 && grid.ocean(next)) return new RiverOutlet(next, RiverOutlet.Type.OCEAN, flow);
        if (next >= 0 && grid.lake(next)) return new RiverOutlet(next, RiverOutlet.Type.LAKE, flow);
        if (grid.lake(cell)) return new RiverOutlet(cell, RiverOutlet.Type.LAKE, flow);
        if (isBoundary(grid, cell)) return new RiverOutlet(cell, RiverOutlet.Type.REGION_BOUNDARY, flow);
        return new RiverOutlet(cell, RiverOutlet.Type.LAND_SINK, flow);
    }

    private static void appendSegments(HydrologyGrid grid, int source, List<RiverSegment> segments) {
        int current = source;
        int length = 0;
        while (true) {
            int next = FlowDirectionSolver.downstream(grid, current);
            if (next < 0 || !grid.river(next)) {
                segments.add(new RiverSegment(source, current, length,
                        grid.contributingArea(source), grid.contributingArea(current)));
                return;
            }
            length++;
            current = next;
            if (isNode(grid, current)) {
                segments.add(new RiverSegment(source, current, length,
                        grid.contributingArea(source), grid.contributingArea(current)));
                return;
            }
        }
    }

    private static boolean isNode(HydrologyGrid grid, int index) {
        int upstream = 0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.river(i) && FlowDirectionSolver.downstream(grid, i) == index) upstream++;
        }
        return upstream != 1;
    }

    private static boolean isBoundary(HydrologyGrid grid, int index) {
        int x = grid.x(index), z = grid.z(index);
        return x == grid.halo() || z == grid.halo()
                || x == grid.size() - grid.halo() - 1
                || z == grid.size() - grid.halo() - 1;
    }

    private static double threshold(HydrologyGrid grid, double areaThreshold) {
        return Math.max(1.0, areaThreshold / (grid.spacing() * grid.spacing()));
    }
}
