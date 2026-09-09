package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 瀑布横断面探针（2026-09-09）。用户反馈：落差线旁岸坡出现与落差同向的薄墙，位于【上游岸坡】。
 * 对指定落差段，在落差线上/下游若干"沿河步"处做【法向横断面】，对比最终高度 vs 原始高度，
 * 定位墙的精确位置与成因（是雕刻下切还是天然陡崖）。
 */
public final class WaterfallCrossSectionProbe {

    private WaterfallCrossSectionProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        double hs = 2.0;

        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);

        // 定位 focus 附近最近河
        double wuX = -169.0 / hs, wuZ = -383.0 / hs;
        RiverLineRegion.RiverPolyline best = null;
        double bestD2 = Double.POSITIVE_INFINITY;
        int rx0 = (int) Math.floorDiv((int) Math.floor(wuX), 640);
        int rz0 = (int) Math.floorDiv((int) Math.floor(wuZ), 640);
        for (int drz = -1; drz <= 1; drz++) for (int drx = -1; drx <= 1; drx++) {
            RiverLineRegion reg = engine.network().region(rx0 + drx, rz0 + drz);
            for (RiverLineRegion.RiverPolyline pl : reg.rivers) {
                for (int i = 0; i < pl.nodes.length; i++) {
                    double d2 = (pl.nodes[i].x() - wuX) * (pl.nodes[i].x() - wuX)
                            + (pl.nodes[i].z() - wuZ) * (pl.nodes[i].z() - wuZ);
                    if (d2 < bestD2) { bestD2 = d2; best = pl; }
                }
            }
        }
        if (best == null) { System.out.println("no river"); return; }
        System.out.println("river: " + best.nodes.length + " nodes");

        // focus 最近节点 i；若 surf[i] 明显高于 surf[i+1]（落差上游）→ fallSeg=i，
        // 否则向前找最近的大落差上游节点
        int focusNode = -1;
        double bestD2n = Double.POSITIVE_INFINITY;
        for (int i = 0; i < best.nodes.length; i++) {
            double d2 = (best.nodes[i].x() - wuX) * (best.nodes[i].x() - wuX)
                    + (best.nodes[i].z() - wuZ) * (best.nodes[i].z() - wuZ);
            if (d2 < bestD2n) { bestD2n = d2; focusNode = i; }
        }
        int fallSeg = -1;
        double maxDrop = 0;
        for (int i = Math.max(0, focusNode - 2); i <= Math.min(best.nodes.length - 2, focusNode + 1); i++) {
            double drop = best.surfaceY[i] - best.surfaceY[i + 1];
            if (drop > maxDrop) { maxDrop = drop; fallSeg = i; }
        }
        if (fallSeg < 0 || maxDrop < 3.0) { System.out.println("no big fall near focus (drop=" + maxDrop + ")"); return; }
        System.out.printf("FALL seg[%d]->[%d] drop=%.2f blk=(%d,%d)->(%d,%d) surf %.1f->%.1f%n",
                fallSeg, fallSeg + 1, maxDrop,
                (int) (best.nodes[fallSeg].x() * hs), (int) (best.nodes[fallSeg].z() * hs),
                (int) (best.nodes[fallSeg + 1].x() * hs), (int) (best.nodes[fallSeg + 1].z() * hs),
                best.surfaceY[fallSeg], best.surfaceY[fallSeg + 1]);

        // 落差段几何
        double[] a = {best.nodes[fallSeg].x(), best.nodes[fallSeg].z()};
        double[] b = {best.nodes[fallSeg + 1].x(), best.nodes[fallSeg + 1].z()};
        double ux = b[0] - a[0], uz = b[1] - a[1];
        double len = Math.hypot(ux, uz);
        if (len < 1e-6) return;
        ux /= len; uz /= len;
        double px = -uz, pz = ux;      // 法向（横跨河谷）

        // 在沿河位置 s（block，以落差段尾 b 为 0，负=上游）处做法向横断面
        int[] steps = {-2, 0, 2};
        for (int s : steps) {
            double cxw = b[0] + ux * (s / hs), czw = b[1] + uz * (s / hs);
            System.out.printf("-- cross-section s=%+d (blk=%d,%d) --%n", s,
                    (int) (cxw * hs), (int) (czw * hs));
            System.out.printf("   n:");
            for (int n = -12; n <= 12; n++) System.out.printf("%5d", n);
            System.out.println();
            System.out.printf("   h:");
            for (int n = -12; n <= 12; n++) {
                double wx = cxw + px * (n / hs);
                double wz = czw + pz * (n / hs);
                int qx = (int) Math.floor(wx * hs), qz = (int) Math.floor(wz * hs);
                Cell c = terrain.sampleCell(qx, qz);
                System.out.printf("%5.0f", c.height);
            }
            System.out.println();
            System.out.printf(" orig:");
            for (int n = -12; n <= 12; n++) {
                double wx = cxw + px * (n / hs);
                double wz = czw + pz * (n / hs);
                int qx = (int) Math.floor(wx * hs), qz = (int) Math.floor(wz * hs);
                Cell orig = terrain.sampleCellLight(qx, qz);
                System.out.printf("%5.0f", orig != null ? orig.height : -1);
            }
            System.out.println();
            System.out.printf("   W:");
            for (int n = -12; n <= 12; n++) {
                double wx = cxw + px * (n / hs);
                double wz = czw + pz * (n / hs);
                int qx = (int) Math.floor(wx * hs), qz = (int) Math.floor(wz * hs);
                Cell c = terrain.sampleCell(qx, qz);
                System.out.printf("%5s", c.riverType != 0 ? "W" : "");
            }
            System.out.println();
        }
    }
}
