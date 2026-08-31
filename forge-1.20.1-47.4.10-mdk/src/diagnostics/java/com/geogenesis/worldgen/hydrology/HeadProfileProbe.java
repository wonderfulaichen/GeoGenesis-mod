package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 河头断面探针（2026-09-01）：验证源头端是否"淡出"而非被硬截断。
 *
 * <p>旧行为：幂律宽深有 minWidth/minDepth 下限，成河门槛裁到 4 格后节点 0 仍是
 * 半宽 1.78 / 深 2.78 的满断面槽并被直接截断 → 陡坡上的钝圆"浴缸尾"水池。
 * 本探针量测每条河源端的半宽/水深，以及沿程首节点的水深（越大越钝）。</p>
 */
public final class HeadProfileProbe {

    private HeadProfileProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineParams P = RiverLineParams.defaults();

        System.out.println("=== HeadProfileProbe ===");
        System.out.printf("seed=%d  minWidth=%.1f minDepth=%.1f 成河门槛=%.0fwu²%n",
                seed, P.minWidth(), P.minDepth(), P.riverAccumThreshold());

        // ★ 必须区分【真源头】与【跨 region 续流的缝头】：缝头按设计不淡出（淡出会在
        //   瓦片缝上造成宽度骤缩），若混在一起统计会把缝头误判成"钝头"。几何特征可用：
        //   真源点被 borderDist 排除在 region 边界之外，而缝头正好贴在边界上。
        double regionSize = P.regionSize();
        double seamTol = 48.0;
        int n = 0, blunt = 0, tapered = 0, seam = 0;
        double sumW0 = 0, sumD0 = 0;
        List<String> samples = new ArrayList<>();
        for (int rz = -1; rz <= 1; rz++) {
            for (int rx = -1; rx <= 1; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline r : region.rivers) {
                    if (r.width.length == 0) continue;
                    int m = r.width.length;
                    double hx = r.nodes[0].x(), hz = r.nodes[0].z();
                    double toBorder = Math.min(
                            Math.min(hx - rx * regionSize, (rx + 1) * regionSize - hx),
                            Math.min(hz - rz * regionSize, (rz + 1) * regionSize - hz));
                    if (toBorder < seamTol) { seam++; continue; }   // 缝头，不考核
                    double w0 = r.width[0], d0 = r.depth[0];
                    n++;
                    sumW0 += w0; sumD0 += d0;
                    int ref = Math.min(m - 1, 8);
                    double dRef = r.depth[ref];
                    if (d0 < dRef * 0.5) tapered++;
                    // 钝头判据：源端水深 ≥ 1.5 格（会露出可见的断面切口）
                    if (d0 >= 1.5) {
                        blunt++;
                        if (samples.size() < 10) {
                            samples.add(String.format("    半宽=%.2f 水深=%.2f | 下游第%d节点水深=%.2f"
                                    + " | 河长=%d节点 @ wu(%.0f,%.0f)",
                                    w0, d0, ref, dRef, m, hx, hz));
                        }
                    }
                }
            }
        }
        System.out.printf("河流总数=%d  其中跨region缝头=%d（按设计不淡出，不计入）%n", n + seam, seam);
        if (n == 0) { System.out.println("无真源头可考核"); return; }
        System.out.printf("真源头数=%d  源端平均半宽=%.2f  源端平均水深=%.2f%n", n, sumW0 / n, sumD0 / n);
        System.out.printf("已淡出（源端水深 < 下游第8节点的 50%%）= %d / %d  (%.0f%%)%n",
                tapered, n, tapered * 100.0 / n);
        System.out.printf("仍为钝头（源端水深 >= 1.5 格）= %d  (%.0f%%)  ← 应趋近 0%n",
                blunt, blunt * 100.0 / n);
        if (!samples.isEmpty()) {
            System.out.println("钝头样例：");
            for (String s : samples) System.out.println(s);
        }
        System.out.println(blunt == 0 ? "status=PASS" : "status=FAIL");
    }
}
