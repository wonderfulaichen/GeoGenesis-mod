package com.geogenesis.worldgen.hydrology.sim;

/**
 * {@link HydroQuery} 的求解器实现：把世界坐标映射到"拥有该格的 tile"，再取其不可变快照。
 *
 * <p>注意"拥有"二字：查询点所属 tile 由 {@link HydroTileKey#tileOfCell(int)} 决定，
 * 而不是由"谁先被生成"决定 ⇒ 结果与查询顺序无关。</p>
 */
public final class HydroSolverQuery implements HydroQuery {

    private final HydroWorldSolver solver;

    public HydroSolverQuery(HydroWorldSolver solver) {
        this.solver = solver;
    }

    public HydroWorldSolver solver() {
        return solver;
    }

    @Override
    public HydroSample sample(double worldX, double worldZ) {
        int gx = HydroContract.cellX(worldX);
        int gz = HydroContract.cellZ(worldZ);
        int tx = HydroTileKey.tileOfCell(gx);
        int tz = HydroTileKey.tileOfCell(gz);
        HydroTileResult res = solver.resolve(new HydroTileKey(solver.seed(), tx, tz, 0));
        HydroTileField f = res.topo.field;
        int lx = gx - f.winMinX;
        int lz = gz - f.winMinZ;
        if (!f.inside(lx, lz)) return null;
        int idx = lx * f.w + lz;
        FlowState st = FlowState.values()[res.balance.state[idx]];
        int basin = res.topo.basinId[idx];
        double spill = basin >= 0 ? res.topo.basinSpillLevel[basin] : Double.NaN;
        double q = res.balance.discharge[idx];
        double width = st.isFlowing() ? HydroFlowGeometry.widthFor(q, res.cfg) : 0;
        double depth = st.isFlowing() ? HydroFlowGeometry.depthFor(q, width, res.cfg) : 0;
        double surface = res.balance.waterLevel[idx];
        OutcomeKind ok = outcomeOf(res, idx);
        return new HydroSample(st, basin, spill, f.height[idx], surface, q, width, depth,
                ok, HydroContract.SOLVER_VERSION, res.pending());
    }

    /** 单格归宿（委托 {@link HydroTileResult#outcomeOf(int)}，避免两份实现漂移）。 */
    public static OutcomeKind outcomeOf(HydroTileResult res, int idx) {
        return res.outcomeOf(idx);
    }
}
