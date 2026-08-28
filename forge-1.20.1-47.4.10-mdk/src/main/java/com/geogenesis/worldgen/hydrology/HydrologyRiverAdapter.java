package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.HeightCurve;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将统一水文剖面转换为稳定的逐单元河流采样；水面/河床换算为世界 Y 块语义。 */
public final class HydrologyRiverAdapter {
    private HydrologyRiverAdapter() { }

    public static Map<Integer, HydrologyRiverSample> adapt(HydrologyGrid grid) {
        return adapt(grid, new HeightCurve(
                com.geogenesis.worldgen.terrain.TerrainParams.defaults(), -64, 320));
    }

    /** 水面低于地面的偏移（块）：保证河道下挖后能真实灌水。 */
    private static final double SURFACE_SINK = 1.0;
    /** 河面沿河网平滑遍数：消除相邻河胞间的水面台阶（chunk 边界跳变根因）。 */
    private static final int SURFACE_SMOOTH_PASSES = 3;

    public static Map<Integer, HydrologyRiverSample> adapt(HydrologyGrid grid, HeightCurve curve) {
        RiverWaterProfile[] water = RiverWaterSolver.solve(grid);
        int total = grid.size() * grid.size();
        // 1) 原始水面（世界 Y）
        double[] surfaces = new double[total];
        boolean[] isRiver = new boolean[total];
        for (int i = 0; i < total; i++) {
            if (water[i] == null) continue;
            surfaces[i] = curve.heightFromE(grid.filledElevation(i)) - SURFACE_SINK;
            isRiver[i] = true;
        }
        smoothSurfacesAlongNetwork(grid, surfaces, isRiver);
        // 2) 用平滑后的水面构建样本
        Map<Integer, HydrologyRiverSample> result = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            if (water[i] == null) continue;
            RiverProfile profile = RiverProfileSolver.profileAt(grid, i);
            int downstream = water[i].downstream() >= 0 && grid.river(water[i].downstream())
                    ? water[i].downstream() : -1;
            double surfaceY = surfaces[i];
            double bedY = surfaceY - profile.section().depth();
            result.put(i, new HydrologyRiverSample(i, downstream, surfaceY, bedY,
                    profile.section().width(), profile.section().depth(),
                    profile.section().bankWidth(), profile.section().valleyWidth(),
                    profile.discharge(), outletType(grid, i)));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * ★ 河面沿河网平滑：每个河胞与上下游河胞平均，多遍迭代。
     * 相邻河胞水面差被摊平 → 插值/吸附无论命中哪个河胞，水面都接近，
     * 根除 chunk 边界"吸附到不同河胞"造成的数块台阶。
     */
    private static void smoothSurfacesAlongNetwork(HydrologyGrid grid,
                                                   double[] surfaces, boolean[] isRiver) {
        int total = grid.size() * grid.size();
        // 上游表：downstream[cell] = 该河胞的上游河胞列表
        int[] upstreamCount = new int[total];
        int[] upstreamA = new int[total];
        int[] upstreamB = new int[total];
        ArraysFill: for (int i = 0; i < total; i++) upstreamCount[i] = 0;
        for (int i = 0; i < total; i++) {
            if (!isRiver[i]) continue;
            int next = FlowDirectionSolver.downstream(grid, i);
            if (next < 0 || !isRiver[next]) continue;
            if (upstreamCount[next] < 2) {
                if (upstreamCount[next] == 0) upstreamA[next] = i;
                else upstreamB[next] = i;
                upstreamCount[next]++;
            }
        }
        for (int pass = 0; pass < SURFACE_SMOOTH_PASSES; pass++) {
            double[] next = surfaces.clone();
            for (int i = 0; i < total; i++) {
                if (!isRiver[i]) continue;
                double sum = surfaces[i];
                int count = 1;
                int down = FlowDirectionSolver.downstream(grid, i);
                if (down >= 0 && isRiver[down]) { sum += surfaces[down]; count++; }
                if (upstreamCount[i] > 0) { sum += surfaces[upstreamA[i]]; count++; }
                if (upstreamCount[i] > 1) { sum += surfaces[upstreamB[i]]; count++; }
                next[i] = sum / count;
            }
            System.arraycopy(next, 0, surfaces, 0, total);
        }
    }

    private static RiverOutlet.Type outletType(HydrologyGrid grid, int cell) {
        int next = FlowDirectionSolver.downstream(grid, cell);
        if (next >= 0 && grid.ocean(next)) return RiverOutlet.Type.OCEAN;
        if (next >= 0 && grid.lake(next)) return RiverOutlet.Type.LAKE;
        if (next < 0) return RiverOutlet.Type.REGION_BOUNDARY;
        return RiverOutlet.Type.LAND_SINK;
    }
}
