package com.geogenesis.worldgen.terrain.candidate;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S2：仅按基础标签的局部世界折线判断浅海，不读取邻居最终标签、不求高度。 */
final class ShallowRegionMatcher {
    static final int SHELF = RegionalMorphWorld.LAND_COUNT + OceanRecipeCatalog.Recipe.SHELF_RIDGE.id;
    static final double REACH = 2 * LocalBoundaryGeometry.BAND_HALF_BLOCKS;
    private final CoherentRegionDistribution base;
    private final double chord, maxSeg;
    private final Map<Long, Patch> patches = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Patch> e) { return size() > 128; }
    };
    private long builds;

    ShallowRegionMatcher(long seed) { this(seed, .02, 2); }
    ShallowRegionMatcher(long seed, double chord, double maxSeg) {
        base = new CoherentRegionDistribution(seed); this.chord = chord; this.maxSeg = maxSeg;
    }

    Match match(LocalBoundaryGeometry.Cell cell) {
        int before = base.choose(cell).recipe();
        if (before < RegionalMorphWorld.LAND_COUNT || before == SHELF)
            return new Match(before, before, Double.POSITIVE_INFINITY, 0, 0);
        double cx = cell.vx() * 600, cz = cell.vz() * 600;
        // 每点同格站点势<=4，赢家每轴差<=2格；再加warp每轴<=100及float坐标余量。
        double radius = 2 * 600 + 100 + 8 * Math.ulp((float) Math.max(Math.abs(cx), Math.abs(cz))) + 1;
        double margin = 2 * (chord + .02 + 4 * Math.ulp((float) (Math.max(Math.abs(cx), Math.abs(cz)) + radius + REACH)));
        double extent = radius + REACH + margin;
        List<Segment> own = new ArrayList<>(), coast = new ArrayList<>();
        for (int z = FullMorphLand.tileIndex(cz - extent); z <= FullMorphLand.tileIndex(cz + extent); z++)
            for (int x = FullMorphLand.tileIndex(cx - extent); x <= FullMorphLand.tileIndex(cx + extent); x++) {
                Patch p = patch(x, z);
                own.addAll(p.byCell().getOrDefault(cell.key(), List.of())); coast.addAll(p.coast());
            }
        if (own.isEmpty()) throw new IllegalStateException("missing full sea cell boundary " + cell);
        double minimum = minimum(own, coast, REACH + margin);
        return new Match(before, minimum <= REACH + margin ? SHELF : before, minimum, margin, own.size());
    }

    private Patch patch(int tx, int tz) {
        long key = LocalBoundaryGeometry.pack(tx, tz);
        Patch cached = patches.get(key); if (cached != null) return cached;
        var g = LocalBoundaryGeometry.of(CoherentRegionDistribution.microPartition(base.seed(), 600),
                FullMorphLand.center(tx), FullMorphLand.center(tz), 728, 4, chord);
        g.setMaxSeg(maxSeg);
        Map<Long, List<Segment>> byCell = new HashMap<>(); List<Segment> coast = new ArrayList<>();
        for (var e : g.edges()) {
            if (e.frameTruncated && e.wx.length > 0) {
                int last = e.wx.length - 1;
                requireFrameOutsideTile(g, e.wx[0], e.wz[0], tx, tz);
                requireFrameOutsideTile(g, e.wx[last], e.wz[last], tx, tz);
            }
            boolean landA = base.choose(e.a).recipe() < RegionalMorphWorld.LAND_COUNT;
            boolean landB = base.choose(e.b).recipe() < RegionalMorphWorld.LAND_COUNT;
            for (int i = 1; i < e.wx.length; i++) {
                Segment s = clip(e.wx[i - 1], e.wz[i - 1], e.wx[i], e.wz[i], tx, tz);
                if (s == null) continue;
                byCell.computeIfAbsent(e.a.key(), k -> new ArrayList<>()).add(s);
                byCell.computeIfAbsent(e.b.key(), k -> new ArrayList<>()).add(s);
                if (landA != landB) coast.add(s);
            }
        }
        Patch result = new Patch(byCell, coast); patches.put(key, result); builds++; return result;
    }

    /** 真边可以在远端盒框截断，但截断端点不能进入本patch负责的固定tile。 */
    private static void requireFrameOutsideTile(LocalBoundaryGeometry g, double x, double z, int tx, int tz) {
        double[] uv = g.warpForward(x, z), box = g.warpBox();
        double edgeDistance = Math.min(Math.min(Math.abs(uv[0] - box[0]), Math.abs(uv[0] - box[1])),
                Math.min(Math.abs(uv[1] - box[2]), Math.abs(uv[1] - box[3])));
        if (edgeDistance > 1e-3) return; // 该端点是内部真顶点，不是盒框截断端。
        if (x >= tx * 1024.0 - 1 && x <= (tx + 1) * 1024.0 + 1
                && z >= tz * 1024.0 - 1 && z <= (tz + 1) * 1024.0 + 1)
            throw new IllegalStateException("geometry frame entered responsible tile");
    }

    /** 只保留固定tile内真边片段；局部geometry盒外框不作为边界。 */
    private static Segment clip(double ax, double az, double bx, double bz, int tx, int tz) {
        double dx = bx - ax, dz = bz - az, x0 = tx * 1024.0, z0 = tz * 1024.0;
        double[] p = {-dx, dx, -dz, dz}, q = {ax - x0, x0 + 1024 - ax, az - z0, z0 + 1024 - az};
        double lo = 0, hi = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return null; }
            else if (p[i] < 0) lo = Math.max(lo, q[i] / p[i]);
            else hi = Math.min(hi, q[i] / p[i]);
        }
        return hi > lo ? new Segment(ax + lo * dx, az + lo * dz, ax + hi * dx, az + hi * dz) : null;
    }

    /** 仅需知道距离是否<=limit；按world240格索引海陆边，远端距离以Infinity标记为超界。 */
    private static double minimum(List<Segment> own, List<Segment> coast, double limit) {
        Map<Long, List<Segment>> bins = new HashMap<>();
        for (Segment b : coast) for (int z = bin(b.minZ()); z <= bin(b.maxZ()); z++)
            for (int x = bin(b.minX()); x <= bin(b.maxX()); x++)
                bins.computeIfAbsent(LocalBoundaryGeometry.pack(x, z), k -> new ArrayList<>()).add(b);
        double best = Double.POSITIVE_INFINITY;
        for (Segment a : own) for (int z = bin(a.minZ() - limit); z <= bin(a.maxZ() + limit); z++)
            for (int x = bin(a.minX() - limit); x <= bin(a.maxX() + limit); x++)
                for (Segment b : bins.getOrDefault(LocalBoundaryGeometry.pack(x, z), List.of())) {
                    if (a.boxDistance(b) > Math.min(best, limit)) continue;
                    best = Math.min(best, a.distance(b));
                    if (best == 0) return 0;
                }
        return best <= limit ? best : Double.POSITIVE_INFINITY;
    }

    long builds() { return builds; }
    int cachedPatches() { return patches.size(); }
    private static int bin(double v) { return (int) Math.floor(v / REACH); }
    record Match(int before, int after, double distance, double margin, int boundarySegments) { }
    private record Patch(Map<Long, List<Segment>> byCell, List<Segment> coast) { }
    record Segment(double ax, double az, double bx, double bz) {
        double minX() { return Math.min(ax, bx); } double maxX() { return Math.max(ax, bx); }
        double minZ() { return Math.min(az, bz); } double maxZ() { return Math.max(az, bz); }
        double boxDistance(Segment b) {
            return Math.hypot(Math.max(0, Math.max(minX() - b.maxX(), b.minX() - maxX())),
                    Math.max(0, Math.max(minZ() - b.maxZ(), b.minZ() - maxZ())));
        }
        double distance(Segment b) {
            if (Line2D.linesIntersect(ax, az, bx, bz, b.ax, b.az, b.bx, b.bz)) return 0;
            return Math.min(Math.min(Line2D.ptSegDist(ax, az, bx, bz, b.ax, b.az), Line2D.ptSegDist(ax, az, bx, bz, b.bx, b.bz)),
                    Math.min(Line2D.ptSegDist(b.ax, b.az, b.bx, b.bz, ax, az), Line2D.ptSegDist(b.ax, b.az, b.bx, b.bz, bx, bz)));
        }
    }
}
