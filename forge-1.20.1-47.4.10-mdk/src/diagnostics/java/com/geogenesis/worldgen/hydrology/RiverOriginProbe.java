package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 河流列溯源（2026-10）—— 用户判据："河流路线贴着湖泊岸边附近走"。
 *
 * <p>对每个【河列】（riverType!=0 且 !isLake），判定它在 carver 层走了哪条分支：</p>
 * <ul>
 *   <li>{@code lakePlan=true}  ⇒ 走了【湖分支】（不雕刻，只铺水面）—— 不该是"河"；</li>
 *   <li>{@code lakeNode!=null} ⇒ 被拒列（登记了湖但未判水）—— 需查 lakeFineFlood；</li>
 *   <li>都不为 ⇒ 真·河分支。</li>
 * </ul>
 * <p>并统计每类的"紧邻湖泊"比例 ⇒ 定位"贴岸"的真实来源。</p>
 *
 * <pre>{@code gradlew runRiverOriginProbe [-PprobeArgs="seed blockX blockZ radius"]}</pre>
 */
public final class RiverOriginProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 200;

        System.out.printf("=== RiverOriginProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        System.out.printf("  skeletonRouting=%s%n", RiverLineNetwork.flowSkeletonRouting);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        int riverCols = 0;
        int lakePlanRiver = 0, rejectedRiver = 0, trueRiver = 0;
        int adjLake = 0, adjLakeTrueRiver = 0;
        int trueRiverTotal = 0;
        List<String> samples = new ArrayList<>();

        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || c.isLake) continue;      // 只要【河列】
                riverCols++;
                double orig = gen.sample(x / hs, z / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, x, z, orig, hs);
                boolean lp = col != null && col.lakePlan();
                boolean ln = col != null && col.lakeNode() != null;
                if (lp) lakePlanRiver++;
                else if (ln) rejectedRiver++;
                else trueRiver++;

                // 紧邻湖泊？
                boolean touch = false;
                for (int dx = -1; dx <= 1 && !touch; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Cell n = cellAt(gt, x + dx, z + dz);
                        if (n.isLake) { touch = true; break; }
                    }
                }
                if (touch) adjLake++;
                if (touch && !lp && !ln) adjLakeTrueRiver++;

                if (samples.size() < 10 && lp) {
                    samples.add(String.format("    [走湖分支却标河列] (%d,%d) h=%.2f surf=%.2f"
                                    + " carve=%.2f orig=%.2f",
                            x, z, c.height, c.riverSurfaceY,
                            col == null ? Double.NaN : col.carvedGroundY(),
                            col == null ? Double.NaN : col.originalGroundY()));
                }
            }
        }
        System.out.printf("%n河列（riverType!=0 且 !isLake） = %d%n", riverCols);
        System.out.printf("  · 走【湖分支】lakePlan=true    = %d (%.1f%%)  <== 不该算河%n",
                lakePlanRiver, 100.0 * lakePlanRiver / Math.max(1, riverCols));
        System.out.printf("  · 被拒列 lakeNode!=null       = %d (%.1f%%)%n",
                rejectedRiver, 100.0 * rejectedRiver / Math.max(1, riverCols));
        System.out.printf("  · 真·河分支                    = %d (%.1f%%)%n",
                trueRiver, 100.0 * trueRiver / Math.max(1, riverCols));
        System.out.printf("%n  紧邻湖泊的河列 = %d (%.1f%%)%n",
                adjLake, 100.0 * adjLake / Math.max(1, riverCols));
        System.out.printf("    其中真·河分支 = %d / %d (%.1f%%)  <== 真河的贴岸率%n",
                adjLakeTrueRiver, Math.max(1, trueRiver),
                100.0 * adjLakeTrueRiver / Math.max(1, trueRiver));
        for (String s : samples) System.out.println(s);
        System.out.printf("%nstatus=riverCols=%d lakePlan=%d rejected=%d trueRiver=%d adjLakeRate=%.1f%%%n",
                riverCols, lakePlanRiver, rejectedRiver, trueRiver,
                100.0 * adjLake / Math.max(1, riverCols));
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverOriginProbe() { }
}