package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「水位决策链」探针（2026-09-17）—— <b>控制变量：逐环节打印谁决定了水位</b>。
 *
 * <h2>要回答的问题</h2>
 * <p>实测（1 块精度）某水格水面 <b>166.63</b>，而它<b>紧邻 1~2 块</b>的旱地只有
 * <b>157.88</b> ⇒ 水本该流走（21.6% 的格如此，最坏 8.747 块）。
 * 本探针在给定块坐标上，把水位决策链的每一环节都打出来，看<b>哪个环节会给出 157.88</b>：</p>
 * <ol>
 *   <li>{@code spill}（{@code RiverLineHit.surfaceY}，无侵蚀）；</li>
 *   <li>{@code erodedWaterLevel}（侵蚀短板，当前生效）；</li>
 *   <li>{@code filledAt}（priority-flood 溢流高程）；</li>
 *   <li>{@code LakeNode} 的域 / {@code inFlood} / {@code floodLevel}（本次新增回传值）；</li>
 *   <li>该格 8 邻与 24 邻的最低非水地面（真实盆沿，1 块精度）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runWaterDecisionChainProbe [-PprobeArgs="seed blockX blockZ"]}</pre>
 */
public final class WaterDecisionChainProbe {

    private WaterDecisionChainProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -15;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 661;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double hs = tp.horizontalScale();
        double seaLevel = gen.heightCurve().seaLevelY();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        double wuX = bx / hs, wuZ = bz / hs;
        System.out.printf("=== WaterDecisionChainProbe seed=%d 块(%d,%d) = wu(%.2f,%.2f) ===%n",
                seed, bx, bz, wuX, wuZ);

        // ---- 实际放置值（权威口径）----
        Cell placed = gt.getChunkCells(bx >> 4, bz >> 4)[Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
        System.out.printf("%n[0] 实际放置：height=%.3f  riverType=%d  riverSurfaceY=%.3f%n",
                placed.height, placed.riverType, placed.riverSurfaceY);

        // ---- 河线侧 ----
        RiverLineNetwork net = new RiverLineNetwork(
                (wx, wz) -> gen.terrainEQuick(wx, wz),
                (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)),
                gen.heightCurve(), seed, hs, rp);
        RiverLineNetwork.RiverLineHit hit = net.sample(wuX, wuZ);
        if (hit == null) {
            System.out.println("[1] 河线命中：无");
        } else {
            System.out.printf("[1] 河线命中：dist=%.2f width=%.2f isLake=%s surfaceY=%.3f%n",
                    hit.distToCenter(), hit.width(), hit.isLake(), hit.surfaceY());
            RiverLineRegion.LakeNode ln = hit.lake();
            if (ln != null) {
                double eroded = ln.erodedWaterLevel((a, b) -> gen.sampleWu(a, b).height);
                System.out.printf("[2] 湖：中心 wu(%.1f,%.1f)  半径=%.1f  湖面(无侵蚀)=%.3f%n",
                        ln.x, ln.z, ln.radius, ln.height);
                System.out.printf("[3] erodedWaterLevel=%.3f   hasRim=%s  floodLevel(回传)=%.3f%n",
                        eroded, ln.hasRim(), ln.floodLevel());
                System.out.printf("[4] 域内?=%s   inFlood?=%s%n",
                        ln.inDomain(wuX, wuZ, rp.gridCell() * 2.0), ln.inFlood(wuX, wuZ));
            }
        }

        // ---- priority-flood（大窗细格）----
        double R = 192;   // wu
        FlowField f = new FlowField(wuX - R, wuZ - R, wuX + R, wuZ + R, 6.0,
                (wx, wz) -> gen.terrainEQuick(wx, wz));
        f.computeFill((wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz)), seaLevel);
        int idx = f.indexOf(wuX, wuZ);
        System.out.printf("[5] priority-flood（6wu 细格 ±%.0fwu）：地面=%.3f  fillE=%.3f  filledAt=%.3f  accum=%.0f%n",
                R, f.fillEAt(idx), f.fillEAt(idx), f.filledAt(idx), f.accumAt(idx));
        System.out.printf("    该格 flowTo=%d  isBasinCell=%s  basinDepth=%.3f%n",
                f.flowTo(idx), f.isBasinCell(idx), f.basinDepthAt(idx));

        // ---- 真实盆沿（1 块精度，多半径）----
        System.out.printf("%n[6] 真实盆沿（1 块精度，实际放置口径）：%n");
        int[] radii = {1, 2, 4, 8, 24};
        for (int r : radii) {
            double best = Double.MAX_VALUE;
            double bxx = 0, bzz = 0;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = bx + dx, nz = bz + dz;
                    Cell c = gt.getChunkCells(nx >> 4, nz >> 4)
                            [Math.floorMod(nx, 16) * 16 + Math.floorMod(nz, 16)];
                    if (c.riverType != 0) continue;
                    if (c.height < best) { best = c.height; bxx = nx; bzz = nz; }
                }
            }
            System.out.printf("     半径 %2d 块：最低旱地 %.3f @块(%.0f,%.0f)"
                            + " ⇒ 水位(%.3f) − 它 = %+.3f 块%n",
                    r, best, bxx, bzz, placed.riverSurfaceY, placed.riverSurfaceY - best);
        }

        System.out.println();
        System.out.println("判读：哪个环节的输出 ≈『半径 1~2 块的最低旱地』，它就是正确的短板实现。");
        System.out.println("      若【没有一个环节】给出该值 ⇒ 说明短板从未按『紧邻』尺度求解。");
    }
}
