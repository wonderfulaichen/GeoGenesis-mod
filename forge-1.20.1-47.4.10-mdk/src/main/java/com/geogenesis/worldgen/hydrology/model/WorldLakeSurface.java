package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Block wetness inside one world-aligned cell; regions never define the flood boundary. */
final class WorldLakeSurface {
    static final int POLICY_VERSION = 1;
    record Plane(Point identity, double level, double sill) { }
    record Cell(int x, int z, int step, double[] levels) {
        double levelAt(int blockX, int blockZ) {
            return levels[(blockX - x) * step + blockZ - z];
        }
    }

    static Cell resolve(int x, int z, int step, Map<Point, WorldBasinFlow.Water> water,
                        WindowOutlets.Terrain terrain) {
        if (step < 1 || Math.floorMod(x, step) != 0 || Math.floorMod(z, step) != 0)
            throw new IllegalArgumentException("world-aligned lake cell required");
        var seeds = new LinkedHashMap<Plane, java.util.List<Point>>();
        var identities = new LinkedHashMap<Point, Plane>();
        for (int dx : new int[]{0, step}) for (int dz : new int[]{0, step}) {
            var p = new Point(x + dx, z + dz);
            var w = water.get(p);
            if (w == null || !w.known()) throw new IllegalStateException("missing/unknown lake corner at " + p);
            if (w.basin() == null) continue;
            var plane = new Plane(w.basin().identity(), w.level(), w.basin().sill());
            if (!Double.isFinite(plane.level()) || !(plane.level() < plane.sill()))
                throw new IllegalStateException("invalid lake plane at " + p);
            var old = identities.putIfAbsent(plane.identity(), plane);
            if (old != null && !old.equals(plane)) throw new IllegalStateException("conflicting lake plane " + p);
            seeds.computeIfAbsent(plane, k -> new java.util.ArrayList<>()).add(p);
        }
        double[] result = new double[step * step];
        Arrays.fill(result, Double.NaN);
        if (seeds.isEmpty()) return new Cell(x, z, step, result);
        int side = step + 1;
        double[] heights = new double[side * side];
        for (int dx = 0; dx <= step; dx++) for (int dz = 0; dz <= step; dz++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("lake cell interrupted");
            double h = terrain.height(x + dx, z + dz);
            if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite lake terrain");
            heights[dx * side + dz] = h;
        }
        Point[] owners = new Point[result.length];
        for (var entry : seeds.entrySet()) flood(x, z, step, heights, entry.getKey(), entry.getValue(), result, owners);
        return new Cell(x, z, step, result);
    }

    /**
     * 湖格「淹没高度」门槛：角点地形必须严格低于此值才算湖面。
     *
     * <p>★★ 2026-10-25【与落块层对齐，修湖缘缺空气】★★</p>
     *
     * <p><b>缺陷</b>：原写死 {@code plane.level() - 0.5}（要求低于水面半格以上），
     * 而落块层 {@code GeoGenesisTerrain} 的湖列判据已放宽为 {@code height < spill - 1e-9}。
     * 两个门槛不一致 ⇒ 湖缘一圈角点被判成「非湖」，其所在 {@code step×step} 整块
     * 拿不到 {@code lakePlan} ⇒ 图上就是湖边缺一块空气、没按水平面填水。
     * <b>这不是精度问题，是判据不一致。</b></p>
     *
     * <p><b>为什么不能直接写成 {@code -1e-9}</b>：本函数还承担「湖不越过鞍部」的职责。
     * 若门槛放到几乎等于水面，贴着 sill 的岸格会被纳入湖面 ⇒ 湖沿溢出口外溢。
     * 故取二者较小值：既不低于落块层门槛，又保证严格低于 {@code sill}。</p>
     *
     * @param plane 该湖的单平面（level 水面 / sill 鞍部）
     * @return 角点必须低于的高度
     */
    private static double floodThreshold(Plane plane) {
        return Math.min(plane.level() - LAKE_BLOCK_EPS, plane.sill() - LAKE_BLOCK_EPS);
    }

    /** 与 {@code GeoGenesisTerrain} 湖列判据同一 epsilon（水面严格高于地面）。 */
    private static final double LAKE_BLOCK_EPS = 1e-9;

    private static void flood(int x, int z, int step, double[] heights, Plane plane,
                              java.util.List<Point> seeds, double[] result, Point[] owners) {
        int side = step + 1;
        boolean[] seen = new boolean[heights.length];
        var queue = new ArrayDeque<Integer>();
        double threshold = floodThreshold(plane);
        for (var p : seeds) {
            int k = (p.x() - x) * side + p.z() - z;
            if (heights[k] < threshold && !seen[k]) { seen[k] = true; queue.add(k); }
        }
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("lake cell interrupted");
            int k = queue.remove(), dx = k / side, dz = k % side;
            if (dx < step && dz < step) {
                int out = dx * step + dz;
                if (owners[out] != null && !owners[out].equals(plane.identity()))
                    throw new IllegalStateException("competing lake identities at " + (x + dx) + "," + (z + dz)
                            + " prior=" + owners[out] + " candidate=" + plane.identity());
                owners[out] = plane.identity(); result[out] = plane.level();
            }
            // ★ 2026-10-25【按湖面等高线补全格内方块】—— 修「湖缘缺一块」。
            //
            // 【缺陷】本洪泛按【角点】推进，而方块只有在「其左下角 (dx,dz) 且 dx<step,dz<step」
            //   这一种情形下才被赋水位。step=2 时格内方块 (0,0)(0,1)(1,0)(1,1) 共用四角；
            //   若某方块的四角里【只有它自己那一角】高于水面、其余角低于水面，
            //   该角不会入队 ⇒ 方块永久拿不到水位 ⇒ 图上就是湖缘平白缺一块空气。
            //   实测：目标湖 x∈[-350,-188] z∈[-512,-428] 有 269 个此类缺口，
            //   其地形低于邻湖水位 0.12~1.16 块，却 isLake=false / riverType=0。
            //
            // 【修法】只要本角已确认在湖面内（heights[k] < threshold），就把【以它为任一角的
            //   方块】一并标定；方块水位取 plane.level()（湖面是单平面，等高线即水平面）。
            //   这不新增任何湖域判定依据 —— 只是让「角点已判为水下」的结论落实到相邻方块，
            //   避免因 step 栅格与方块栅格的错位而丢格。
            for (int ox = Math.max(0, dx - 1); ox <= Math.min(step - 1, dx); ox++) {
                for (int oz = Math.max(0, dz - 1); oz <= Math.min(step - 1, dz); oz++) {
                    int out = ox * step + oz;
                    if (owners[out] != null && !owners[out].equals(plane.identity()))
                        throw new IllegalStateException("competing lake identities at " + (x + ox) + "," + (z + oz)
                                + " prior=" + owners[out] + " candidate=" + plane.identity());
                    owners[out] = plane.identity();
                    result[out] = plane.level();
                }
            }
            // Face-connected water cannot jump diagonally through two dry banks.
            for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = dx + offset[0], nz = dz + offset[1];
                if (nx < 0 || nz < 0 || nx > step || nz > step) continue;
                int n = nx * side + nz;
                if (!seen[n] && heights[n] < threshold) { seen[n] = true; queue.add(n); }
            }
        }
    }
}
