package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 瀑布岸坡薄墙探针 v2（2026-09-09）。
 *
 * <p>v1 沿【法向】扫外围冻结列，撞到的 3 格台阶大多是 dist 22~35 的远景自然地形。
 * v2 改为：对每条【含跌水（fallDrop&gt;0）】的河段，在河道两侧 2/4/6 格偏移处，
 * <b>沿河走向</b>跨过跌水点扫剖面 —— 薄墙与落差面同向 ⇒ 在"沿河"剖面上应表现为
 * 一处 ≥3 格的横向（沿河方向）台阶，dump 墙两侧列的雕刻中间量。</p>
 *
 * <p>用法：gradlew runWaterfallBankWallProbe [-PprobeArgs="seed cx cz"]</p>
 */
public final class WaterfallBankWallProbe {

    private WaterfallBankWallProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -160;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -345;
        int regR = 2;

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);

        System.out.println("=== WaterfallBankWallProbe v2 ===");
        System.out.println("seed=" + seed + " focus=(" + cx + "," + cz + ")");
        int walls = 0, fallRivers = 0;

        for (int rz = -regR; rz <= regR; rz++) {
            for (int rx = -regR; rx <= regR; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline pl : region.rivers) {
                    boolean hasFall = false;
                    for (int i = 0; i < pl.fallDrop.length; i++) if (pl.fallDrop[i] > 0.0) hasFall = true;
                    if (!hasFall) continue;
                    fallRivers++;
                    for (int i = 1; i + 1 < pl.nodes.length; i++) {
                        // 仅关注"上游侧有落差、下游侧水面已降"的跌水节点 i（i1=跌水后）
                        if (!(pl.fallDrop[i] > 0.0)) continue;
                        double[] up = {pl.nodes[i - 1].x(), pl.nodes[i - 1].z()};
                        double[] mid = {pl.nodes[i].x(), pl.nodes[i].z()};
                        double[] dn = {pl.nodes[i + 1].x(), pl.nodes[i + 1].z()};
                        // 沿河单位向量（上游→下游）
                        double ux = dn[0] - up[0], uz = dn[1] - up[1];
                        double len = Math.hypot(ux, uz);
                        if (len < 1e-6) continue;
                        ux /= len; uz /= len;
                        double px = -uz, pz = ux;   // 法向
                        double wx0 = mid[0], wz0 = mid[1];   // 跌水点（wu）
                        double surfU = pl.surfaceY[Math.max(0, i - 1)];
                        double surfD = pl.surfaceY[i];
                        for (double offBlock : new double[]{2.0, 4.0, 6.0, 8.0}) {
                            double off = offBlock / hs;   // wu
                            for (int side = -1; side <= 1; side += 2) {
                                // 沿河：从跌水点上游 -10 block 走到下游 +12 block
                                double prev = Double.NaN;
                                double prevOrig = Double.NaN;
                                for (int s = -10; s <= 12; s++) {
                                    double wx = wx0 + ux * (s / hs) + px * off * side;
                                    double wz = wz0 + uz * (s / hs) + pz * off * side;
                                    int bx = (int) Math.floor(wx * hs);
                                    int bz = (int) Math.floor(wz * hs);
                                    Cell c = terrain.sampleCell(bx, bz);
                                    Cell b = terrain.sampleCellLight(bx, bz);   // 无侵蚀基础场
                                    double origH = b != null ? b.height : Double.NaN;
                                    if (s > -10 && Math.abs(c.height - prev) >= 3.0) {
                                        walls++;
                                        double origStep = origH - prevOrig;
                                        System.out.printf(
                                            "WALL off=%+.0f side=%s s=%d col=(%d,%d): fin %.1f->%.1f (step %.1f) | orig %.1f->%.1f (step %.1f) | carve=%.1f | fall(lip=%.1f pool=%.1f)%n",
                                            offBlock, side > 0 ? "R" : "L", s, bx, bz, prev, c.height,
                                            c.height - prev, prevOrig, origH, origStep,
                                            c.height - origH, surfU, surfD);
                                    }
                                    prev = c.height;
                                    prevOrig = origH;
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("fallRivers=" + fallRivers + " walls=" + walls);
    }
}
