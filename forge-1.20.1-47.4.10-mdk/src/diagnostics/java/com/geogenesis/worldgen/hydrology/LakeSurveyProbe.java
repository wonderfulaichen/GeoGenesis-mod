package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 湖全量扫描探针（2026-09-10）。
 *
 * <p>对 region 范围内所有洼地湖打印：水位（原 spill / 侵蚀短板）、OOB 状态、
 * 淹没区覆盖洼地比例、以及"生产 Cell 判定为水"的实际格数 —— 用于定位用户
 * "湖一侧贴岸、另一侧停在半途/水没铺满洼地"的具体湖。</p>
 *
 * <p>用法：gradlew runLakeSurveyProbe -PprobeArgs="seed regionRadius"</p>
 */
public final class LakeSurveyProbe {

    private LakeSurveyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int regionR = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);
        RiverLineNetwork net = engine.network();

        List<RiverLineRegion.LakeNode> lakes = new ArrayList<>();
        for (int rz = -regionR; rz <= regionR; rz++) {
            for (int rx = -regionR; rx <= regionR; rx++) {
                lakes.addAll(net.region(rx, rz).lakes);
            }
        }
        System.out.println("=== LakeSurveyProbe seed=" + seed + " lakes=" + lakes.size() + " ===");
        if (lakes.isEmpty()) {
            System.out.println("no lakes found");
            return;
        }

        for (int li = 0; li < lakes.size(); li++) {
            RiverLineRegion.LakeNode lk = lakes.get(li);
            double rawSpill = lk.height;
            // 触发 carver 同款计算（侵蚀短板 + flood + OOB）
            double level = rawSpill;
            boolean oob = false;
            boolean hasRim = lk.hasRim();
            if (hasRim) {
                java.util.function.ToDoubleBiFunction<Double, Double> erodedY =
                        (wx, wz) -> generator.sampleWu(wx, wz).height;
                level = lk.erodedWaterLevel(erodedY);
                // ★ 2026-09-15：BFS 加密一倍，认领域基准用原始 gridCell。
                // ⚠ 2026-09-18 校正【口径差异】：本探针传 claimGrid*0.5（12wu），
                //   而生产（HydrologyBlockCarver.computeFlood 调用点）已加密为
                //   claimGrid*0.25（6wu）⇒ 本探针【并非与生产同口径】（原注释此说法不成立）。
                //   OOB 判定与覆盖判定均随 BFS 分辨率变化 ⇒ 本探针的绝对数值
                //   只能做【同探针历史自比】，不可直接代表生产。
                //   刻意保持 *0.5：改为 *0.25 会让历史实测数据全部断档，且慢约 4 倍。
                double claimGrid = com.geogenesis.worldgen.hydrology.riverline.RiverLineParams
                        .defaults().gridCell();
                oob = lk.computeFlood(erodedY, level, claimGrid * 0.5, claimGrid);
            }
            // 生产实际出水格数（湖域周边扫块）
            int wetCells = 0;
            double span = Math.max(30.0, lk.radius * 1.6);
            for (int dz = (int) -span; dz <= span; dz += 2) {
                for (int dx = (int) -span; dx <= span; dx += 2) {
                    double wx = lk.x + dx, wz = lk.z + dz;
                    Cell c = terrain.sampleCell(wx * hs, wz * hs);
                    if (c.isLake && c.riverSurfaceY > c.height) wetCells++;
                }
            }
            System.out.printf("lake[%d] wu=(%.0f,%.0f) cells=%d spill %.2f->%.2f rim=%s floodCells=%d OOB=%s wetProd=%d%n",
                    li, lk.x, lk.z, lk.hasOutline() ? lk.cellX.length : 0,
                    rawSpill, level, hasRim,
                    (lk.floodX != null) ? lk.floodX.length : -1,
                    oob, wetCells);
        }
    }
}
