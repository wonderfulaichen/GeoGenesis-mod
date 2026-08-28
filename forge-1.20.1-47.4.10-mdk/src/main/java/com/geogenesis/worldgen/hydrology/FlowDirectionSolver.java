package com.geogenesis.worldgen.hydrology;

/** 在填洼后的水面上选择确定性下游邻居；平地按固定方向优先级，绝不产生环。 */
public final class FlowDirectionSolver {
    public static final byte OUTLET = -1;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private FlowDirectionSolver() { }

    public static void solve(HydrologyGrid grid) {
        for (int z = 0; z < grid.size(); z++) {
            for (int x = 0; x < grid.size(); x++) {
                int i = grid.index(x, z);
                if (grid.ocean(i)) { grid.flows()[i] = OUTLET; continue; }
                int best = -1;
                double bestDrop = 0.0;
                for (int d = 0; d < DX.length; d++) {
                    int nx = x + DX[d], nz = z + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= grid.size() || nz >= grid.size()) continue;
                    int ni = grid.index(nx, nz);
                    double drop = grid.filledElevation(i) - grid.filledElevation(ni);
                    if (drop > bestDrop + 1e-9 || (Math.abs(drop - bestDrop) <= 1e-9 && d < best)) {
                        bestDrop = drop;
                        best = d;
                    }
                }
                grid.flows()[i] = (byte) (best >= 0 ? best : OUTLET);
            }
        }
    }

    public static int downstream(HydrologyGrid grid, int index) {
        int d = grid.flow(index);
        if (d < 0) return -1;
        int x = grid.x(index) + DX[d], z = grid.z(index) + DZ[d];
        return x < 0 || z < 0 || x >= grid.size() || z >= grid.size() ? -1 : grid.index(x, z);
    }

    public static int dx(int direction) { return DX[direction]; }
    public static int dz(int direction) { return DZ[direction]; }
}
