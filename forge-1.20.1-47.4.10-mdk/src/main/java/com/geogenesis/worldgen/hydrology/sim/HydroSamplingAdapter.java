package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 新核心 → 雕刻层的<b>唯一适配器</b>。
 *
 * <p>雕刻层（{@code HydrologyBlockCarver}）继续消费它既有的 {@code HydrologyBlockSample}，
 * 但样本身上的每一个量都<b>只</b>来自 {@link HydroWorldSolver}：
 * 状态、湖位、溢口、宽深、顺流中心线距离。适配器不做任何独立的水文推导 ——
 * 旧链"湖位在这里算一遍、雕刻层再算一遍"的水位不一致问题因此从结构上消失。</p>
 *
 * <h2>单位</h2>
 * <ul>
 *   <li>核心内部：方块（block）。</li>
 *   <li>{@code HydrologyBlockSample}：方块。</li>
 *   <li>{@code LakeNode} 的 {@code x/z} 是 wu ⇒ 这里按 {@code horizontalScale} 换算。</li>
 * </ul>
 */
public final class HydroSamplingAdapter implements HydroQuery {

    private final HydroWorldSolver solver;
    private final double horizontalScale;
    /** 每个 tile 的中心线空间索引：cellIdx → 段列表。
     *  ⚠ 2026-09-29：ConcurrentHashMap + 双检（chunk 生成多线程；旧 HashMap 与
     *    solver 缓存同批在 crash-2026-09-29_23.52.55 中证明会 CME）。 */
    private final java.util.concurrent.ConcurrentMap<Long, TileIndex> indexCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 中心线段：两端节点各带一套值，查询时按投影参数 t <b>线性插值</b>。
     *
     * <p>★ 2026-09-30：此前只存 A 端单值 ⇒ 宽度/深度/水面在段内**恒定**、跨段才跳变
     * （节点间距 = 1 水文格 = 4 块）⇒ 河道按 4 块台阶出现（用户判据"像几个方块拼起来"）。
     * 现两端各存一套并按 t 插值 ⇒ 段内连续、跨段 C0 连续 ⇒ 河槽与水面成为连续量。</p>
     */
    private record Seg(double ax, double az, double bx, double bz,
                       double surfaceYA, double widthA, double depthA,
                       double surfaceYB, double widthYB, double depthYB,
                       FlowState state, int cellA, int cellB) {

        /** 在投影参数 {@code t∈[0,1]} 处插值。 */
        double surfaceY(double t) { return surfaceYA + (surfaceYB - surfaceYA) * t; }
        double width(double t) { return widthA + (widthYB - widthA) * t; }
        double depth(double t) { return depthA + (depthYB - depthA) * t; }
    }

    private static final class TileIndex {
        final Map<Integer, List<Seg>> byCell = new HashMap<>();
        TileIndex(HydroTileResult res) {
            List<HydroFlowGeometry.HydroPolyline> pls = HydroFlowGeometry.centerlines(res);
            for (HydroFlowGeometry.HydroPolyline p : pls) {
                for (int i = 0; i + 1 < p.size(); i++) {
                    Seg s = new Seg(p.x()[i], p.z()[i], p.x()[i + 1], p.z()[i + 1],
                            p.surfaceY()[i], p.width()[i], p.depth()[i],
                            p.surfaceY()[i + 1], p.width()[i + 1], p.depth()[i + 1],
                            FlowState.values()[p.state()[i]], p.cellIdx()[i], p.cellIdx()[i + 1]);
                    put(res, s.cellA(), s);
                    put(res, s.cellB(), s);
                }
            }
        }

        private void put(HydroTileResult res, int cellIdx, Seg s) {
            // 段同时登记到其两端格，以及两端格的 8 邻（保证跨格查询能找到）
            HydroTileField f = res.topo.field;
            int lx = cellIdx / f.w, lz = cellIdx % f.w;
            for (int d = 0; d < 8; d++) {
                int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                if (!f.inside(nx, nz)) continue;
                int nb = nx * f.w + nz;
                byCell.computeIfAbsent(nb, k -> new ArrayList<>()).add(s);
            }
            byCell.computeIfAbsent(cellIdx, k -> new ArrayList<>()).add(s);
        }
    }

    public HydroSamplingAdapter(HydroWorldSolver solver, double horizontalScale) {
        this.solver = solver;
        this.horizontalScale = horizontalScale > 0.01 ? horizontalScale : 1.0;
    }

    public HydroWorldSolver solver() {
        return solver;
    }

    /** 该点最近的中心线段（含端点段），失败返回 null。 */
    public Seg nearestSegment(double worldX, double worldZ) {
        int gx = HydroContract.cellX(worldX), gz = HydroContract.cellZ(worldZ);
        HydroTileKey key = new HydroTileKey(solver.seed(),
                HydroTileKey.tileOfCell(gx), HydroTileKey.tileOfCell(gz), 0);
        HydroTileResult res = solver.resolve(key);
        TileIndex idx = indexCache.computeIfAbsent(key.stableKey(), k -> new TileIndex(res));
        HydroTileField f = res.topo.field;
        int lx = gx - f.winMinX, lz = gz - f.winMinZ;
        if (!f.inside(lx, lz)) return null;
        List<Seg> segs = idx.byCell.get(lx * f.w + lz);
        if (segs == null) return null;
        Seg best = null;
        double bestD = Double.MAX_VALUE;
        for (Seg s : segs) {
            double d = distToSegment(worldX, worldZ, s);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** 到中心线的最近距离（方块）；无中心线返回 NaN。 */
    public double distanceToCenterline(double worldX, double worldZ) {
        Seg s = nearestSegment(worldX, worldZ);
        return s == null ? Double.NaN : distToSegment(worldX, worldZ, s);
    }

    public FlowState stateAt(double worldX, double worldZ) {
        HydroSample smp = sample(worldX, worldZ);
        return smp == null ? FlowState.NONE : smp.state();
    }

    /**
     * 到最近水体的距离（<b>方块</b>单位；单位换算由调用方负责）——绿洲规则的输入。
     *
     * <p>★ 2026-09-29【创建世界卡死的修复】：此前绿洲走旧链
     * {@code RiverLineNetwork.distanceToWater} → 每个沙漠格触发旧链 region 构建
     * （唯一管线化后单 region 0.9~16.7s）+ 湖水位 {@code sampleWu} 同步生成侵蚀 tile
     * （实测日志：605 个、每个 600~1100ms、坐标随 region 铺到 ±10224wu ⇒ 创建界面卡死
     * 十分钟级）。改走本核心后，查询命中<b>与雕刻同一个 solver / 同一个 tile 缓存</b>
     * （雕刻在同 chunk 必然已 resolve 该 tile）⇒ 边际成本≈0，且
     * "绿洲看到的水 = 雕刻刻出来的水"重新成立（单一事实来源）。</p>
     *
     * <p>口径：湖/海格 → 0（本格即水）；否则 = 到最近河道中心线的距离；
     * 无任何水体 → {@code +∞}。⚠ 与旧口径的差异：旧链给出"到湖中心"的距离，
     * 本实现湖面外的"到湖岸距离"暂不计算（湖岸带的绿洲暂缺，见 handoff 遗留）。</p>
     */
    public double distanceToWater(double blockX, double blockZ) {
        HydroSample s = sample(blockX, blockZ);
        if (s == null) return Double.POSITIVE_INFINITY;
        if (s.state() == FlowState.LAKE_STORAGE || s.state() == FlowState.SEA) return 0;
        double d = distanceToCenterline(blockX, blockZ);
        return Double.isNaN(d) ? Double.POSITIVE_INFINITY : d;
    }

    @Override
    public HydroSample sample(double worldX, double worldZ) {
        int gx = HydroContract.cellX(worldX), gz = HydroContract.cellZ(worldZ);
        HydroTileKey key = new HydroTileKey(solver.seed(),
                HydroTileKey.tileOfCell(gx), HydroTileKey.tileOfCell(gz), 0);
        HydroTileResult res = solver.resolve(key);
        HydroTileField f = res.topo.field;
        int lx = gx - f.winMinX, lz = gz - f.winMinZ;
        if (!f.inside(lx, lz)) return null;
        int cellIdx = lx * f.w + lz;
        FlowState st = FlowState.values()[res.balance.state[cellIdx]];
        if (st == FlowState.NONE && !res.topo.sea[cellIdx]) return null;
        int basin = res.topo.basinId[cellIdx];
        double spill = basin >= 0 ? res.topo.basinSpillLevel[basin] : Double.NaN;
        double q = res.balance.discharge[cellIdx];
        double width = st.isFlowing() ? HydroFlowGeometry.widthFor(q, res.cfg) : 0;
        double depth = st.isFlowing() ? HydroFlowGeometry.depthFor(q, width, res.cfg) : 0;
        double surface = res.balance.waterLevel[cellIdx];
        Seg seg = nearestSegment(worldX, worldZ);
        double dist = seg == null ? 0 : distToSegment(worldX, worldZ, seg);
        // 沿中心线【按投影参数插值】取水面/宽深 ⇒ 段内连续（去掉 4 块量化台阶）
        if (seg != null && st.isFlowing()) {
            double t = projT(worldX, worldZ, seg);
            surface = seg.surfaceY(t);
            width = seg.width(t);
            depth = seg.depth(t);
            // 水面不得高于本格填洼面（防插值越界悬空）
            surface = Math.min(surface, res.topo.fill[cellIdx]);
        }
        return new HydroSample(st, basin, spill, f.height[cellIdx], surface, q, width, depth,
                HydroSolverQuery.outcomeOf(res, cellIdx), HydroContract.SOLVER_VERSION, res.pending());
    }

    /** 湖列判定：直接取核心状态（不再有第二套湖定义）。 */
    public boolean isLakeColumn(double worldX, double worldZ) {
        HydroSample s = sample(worldX, worldZ);
        return s != null && s.state() == FlowState.LAKE_STORAGE;
    }

    /** 查询点在段上的投影参数 {@code t∈[0,1]}（与 distToSegment 同一几何）。 */
    private static double projT(double px, double pz, Seg s) {
        double vx = s.bx() - s.ax(), vz = s.bz() - s.az();
        double len2 = vx * vx + vz * vz;
        if (len2 < 1e-12) return 0;
        double t = ((px - s.ax()) * vx + (pz - s.az()) * vz) / len2;
        return Math.max(0, Math.min(1, t));
    }

    private static double distToSegment(double px, double pz, Seg s) {
        double vx = s.bx() - s.ax(), vz = s.bz() - s.az();
        double wx = px - s.ax(), wz = pz - s.az();
        double len2 = vx * vx + vz * vz;
        double t = len2 < 1e-12 ? 0 : (wx * vx + wz * vz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = s.ax() + t * vx, cz = s.az() + t * vz;
        return Math.hypot(px - cx, pz - cz);
    }

    /** 缓存统计（诊断）。 */
    public int indexedTiles() {
        return indexCache.size();
    }

    public void clearIndex() {
        indexCache.clear();
    }
}
