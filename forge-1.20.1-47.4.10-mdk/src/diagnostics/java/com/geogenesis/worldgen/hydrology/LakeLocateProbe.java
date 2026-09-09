package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.Map;

/**
 * 湖泊定位探针（2026-09-09：用户实测"湖水边没贴到地形、边缘一堆空气位"）。
 *
 * <p>给定游戏内 block 坐标，反查：该点 sampleAll 命中（湖/河、spill/水面）、
 * 附近湖的 spill 与逐格轮廓、以及【侵蚀后生产地形】在湖心/盆底/沿岸环带的高度，
 * 判断是"域截断"还是"spill 短板失效"。</p>
 *
 * <p>用法：gradlew runLakeLocateProbe -PprobeArgs="seed blockX blockZ"</p>
 */
public final class LakeLocateProbe {

    private LakeLocateProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int blockX = args.length > 1 ? Integer.parseInt(args[1]) : -456;
        int blockZ = args.length > 2 ? Integer.parseInt(args[2]) : -606;

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);
        RiverLineNetwork net = engine.network();

        double wx = blockX / hs, wz = blockZ / hs;
        System.out.println("=== LakeLocateProbe seed=" + seed
                + " block=(" + blockX + "," + blockZ + ") wu=(" + wx + "," + wz + ") ===");

        // 1) 该点采样命中
        var hits = net.sampleAll(wx, wz);
        System.out.println("-- sampleAll hits at point: " + hits.size());
        for (var h : hits) {
            System.out.printf("   dist=%.2f surf=%.2f width=%.2f isLake=%s frozen=%s fall=%.2f bank=%.2f%n",
                    h.distToCenter(), h.surfaceY(), h.width(), h.isLake(),
                    h.frozen(), h.fallDrop(), h.bankSurfaceY());
        }

        // 2) 该点生产 Cell（侵蚀后 + 雕刻回写）
        Cell pc = prodCell(terrain, wx, wz, hs);
        System.out.printf("-- prodCell: height=%.2f riverType=%d riverSurfaceY=%.2f isLake=%s%n",
                pc.height, pc.riverType, pc.riverSurfaceY, pc.isLake);
        System.out.printf("-- noErosion sample: height=%.2f%n", generator.sample(wx, wz).height);

        // 3) 400wu 内所有湖
        double regionSize = net.regionSize();
        int rcx = (int) Math.floor(wx / regionSize), rcz = (int) Math.floor(wz / regionSize);
        RiverLineRegion.LakeNode nearest = null;
        double nearestD = Double.POSITIVE_INFINITY;
        System.out.println("-- lakes within 400wu:");
        for (int drz = -1; drz <= 1; drz++) {
            for (int drx = -1; drx <= 1; drx++) {
                for (RiverLineRegion.LakeNode ln : net.region(rcx + drx, rcz + drz).lakes) {
                    double d = Math.hypot(wx - ln.x, wz - ln.z);
                    if (d > 400.0) continue;
                    if (d < nearestD) { nearestD = d; nearest = ln; }
                    System.out.printf("   lake wu=(%.1f,%.1f) spill=%.2f radius=%.1f depth=%.2f cells=%d dist=%.1f%s%n",
                            ln.x, ln.z, ln.height, ln.radius, ln.depth,
                            ln.hasOutline() ? ln.cellX.length : 0, d,
                            ln.floodOOB ? " [OOB-ABANDONED]" : "");
                }
            }
        }
        if (nearest == null) {
            System.out.println("no lake within 400wu -> this water is NOT a basin lake (check river-lake reach)");
            return;
        }

        // 4) 最近湖：盆底（轮廓格中心）与沿岸环带（侵蚀后生产高度）vs spill
        System.out.println("-- nearest lake basin floor (outline cells, prod vs noErosion):");
        double spill = nearest.height;
        // 与生产一致：水位用侵蚀后短板（LAKE_ERODED_SPILL=true 的真实水位）
        if (nearest.hasRim()) {
            spill = nearest.erodedWaterLevel((ax, az) -> generator.sampleWu(ax, az).height);
            System.out.printf("-- erodedSpill(shortboard)=%.2f (raw spill=%.2f)%n", spill, nearest.height);
        }
        if (nearest.hasOutline()) {
            for (int i = 0; i < nearest.cellX.length; i++) {
                double cx = nearest.cellX[i], cz = nearest.cellZ[i];
                double hp = prodCell(terrain, cx, cz, hs).height;
                double hr = generator.sample(cx, cz).height;
                System.out.printf("   cell[%d] wu=(%.1f,%.1f) prod=%.2f raw=%.2f belowSpill=%s%n",
                        i, cx, cz, hp, hr, hp < spill - 0.5);
            }
        }
        // 沿岸环带：以湖心为中心，半径 = 轮廓外扩 1 格 的圆上 32 点，取侵蚀后高度
        double extent = nearest.radius;
        if (nearest.hasOutline()) {
            double ex = 0.0;
            for (int i = 0; i < nearest.cellX.length; i++) {
                ex = Math.max(ex, Math.hypot(nearest.cellX[i] - nearest.x,
                        nearest.cellZ[i] - nearest.z));
            }
            extent = ex + 24.0;
        }
        System.out.println("-- rim ring r=" + String.format("%.1f", extent)
                + "wu, 32 pts, prodHeight vs spill=" + String.format("%.2f", spill) + ":");
        double minRim = Double.POSITIVE_INFINITY;
        double maxRim = Double.NEGATIVE_INFINITY;
        Map<Integer, Double> ring = new HashMap<>();
        for (int a = 0; a < 32; a++) {
            double ang = 2.0 * Math.PI * a / 32.0;
            double px = nearest.x + Math.cos(ang) * extent;
            double pz = nearest.z + Math.sin(ang) * extent;
            double hp = prodCell(terrain, px, pz, hs).height;
            minRim = Math.min(minRim, hp);
            maxRim = Math.max(maxRim, hp);
            ring.put(a, hp);
        }
        for (int a = 0; a < 32; a++) {
            double hp = ring.get(a);
            System.out.printf("   [%02d] h=%.2f %s%n", a, hp,
                    hp < spill ? "(below spill -> water should reach here)" : "(above spill)");
        }
        System.out.printf("-- RIM: min=%.2f max=%.2f spill=%.2f shortfall=%.2f (spill>minRim = water above banks)%n",
                minRim, maxRim, spill, spill - minRim);

        // 5) 以湖心为中心的 ASCII 水形图（生产路径，hs 为 1 block 采样 → wu 步长 hs）
        double cellW = Math.max(24.0, extent * 1.4);
        int grid = 41;   // 奇数，中心在中间
        double step = 2.0 * cellW / (grid - 1);
        System.out.println("-- production water ASCII (r=" + String.format("%.1f", cellW)
                + "wu, center=lake, " + grid + "x" + grid + ", '#'=water '.'=dry above spill):");
        StringBuilder sb = new StringBuilder();
        for (int gz = 0; gz < grid; gz++) {
            for (int gx = 0; gx < grid; gx++) {
                double px = nearest.x + (gx - grid / 2) * step;
                double pz = nearest.z + (gz - grid / 2) * step;
                Cell c = prodCell(terrain, px, pz, hs);
                boolean w = c.isLake && c.riverSurfaceY > c.height;
                // 只有【本湖域内】(inDomain) 的 dry 列才可能是漏判 —— 窗口边缘那些低地
                // 属于别的湖/山谷，套本湖 spill 判 missed 全是假阳性。
                // 生产用 inDomain(margin=2×gridCell)，探针也用同 margin 判域。
                boolean inThisLake = nearest.inDomain(px, pz, nearest.cellHalf * 2.0);
                double level = spill;
                if (inThisLake && (c.riverType != 0 || c.isLake)) level = c.riverSurfaceY;
                boolean missed = !w && inThisLake && c.height < level - 0.5;
                boolean dryNear = !w && !missed && inThisLake && c.height < level + 2.0;
                sb.append(w ? '#' : (missed ? '?' : (dryNear ? '.' : ' ')));
            }
            sb.append('\n');
        }
        System.out.print(sb);
    }

    /** sampleCell 入参是 BLOCK 坐标（内部 generateChunk 再 ÷hs 成 wu）。wu 必须先 ×hs。 */
    private static Cell prodCell(GeoGenesisTerrain terrain, double wx, double wz, double hs) {
        return terrain.sampleCell(wx * hs, wz * hs);
    }
}
