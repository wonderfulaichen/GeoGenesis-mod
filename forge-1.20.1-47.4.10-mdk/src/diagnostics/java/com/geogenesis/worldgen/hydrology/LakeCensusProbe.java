package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 湖泊普查（2026-10）—— 列出附近所有【生产 LakeNode】的规模与水位。
 *
 * <p>用途：判定"面积巨大、沿谷延伸"的湖是不是被误判（本该是河谷/河流，却被填水）。</p>
 *
 * <pre>{@code gradlew runLakeCensusProbe [-PprobeArgs="seed regionX regionZ radius"]}</pre>
 */
public final class LakeCensusProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int rad = args.length > 3 ? Integer.parseInt(args[3]) : 2;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();
        double hs = tp.horizontalScale();

        System.out.printf("=== LakeCensusProbe seed=%d region(%d,%d)+-%d ===%n", seed, rx0, rz0, rad);
        System.out.printf("  skeletonRouting(生效) = %s   regionSize=640wu=%.0f块%n",
                RiverLineNetwork.flowSkeletonRouting, 640 * hs);

        int total = 0;
        for (int rz = rz0 - rad; rz <= rz0 + rad; rz++) {
            for (int rx = rx0 - rad; rx <= rx0 + rad; rx++) {
                RiverLineRegion r = net.region(rx, rz);
                for (RiverLineRegion.LakeNode ln : r.lakes) {
                    total++;
                    int cells = ln.cellX == null ? 0 : ln.cellX.length;
                    double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
                    if (ln.cellX != null) {
                        for (int i = 0; i < cells; i++) {
                            minX = Math.min(minX, ln.cellX[i]); maxX = Math.max(maxX, ln.cellX[i]);
                            minZ = Math.min(minZ, ln.cellZ[i]); maxZ = Math.max(maxZ, ln.cellZ[i]);
                        }
                    }
                    System.out.printf(
                            "  r(%2d,%2d) 湖心=(%7.0f,%7.0f)块 spill=%8.2f 轮廓格=%-5d bbox=%.0fx%.0f块 水深=%.2f%n",
                            rx, rz, ln.x * hs, ln.z * hs, ln.height, cells,
                            (maxX - minX) * hs, (maxZ - minZ) * hs, ln.depth);
                }
            }
        }
        System.out.printf("%n共 %d 个生产湖（%d 个 region）%n", total, (2 * rad + 1) * (2 * rad + 1));
        System.out.println("判读：bbox 达数百~上千块、且沿谷延伸 ⇒ 该湖可能是【河谷被当湖】（填水而非雕刻）");
    }

    private LakeCensusProbe() { }
}
