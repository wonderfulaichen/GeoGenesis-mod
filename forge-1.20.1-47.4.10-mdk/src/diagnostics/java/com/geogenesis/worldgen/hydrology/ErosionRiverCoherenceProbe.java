package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 侵蚀-河流一致性探针（方案 A 验证，2026-09-09）。
 *
 * <p>走<b>生产路径</b> {@code GeoGenesisTerrain.getChunkCells}（sample → extractFromTile
 * 侵蚀 → applyHydrologyValley 雕刻回写），量化用户截图反映的两个症状：</p>
 * <ol>
 *   <li><b>干滩/断流（图2）</b>：河床被侵蚀抬升后顶穿水面。落块把水放在
 *       {@code y ∈ (floor(bed), floor(surface)]}，floor 相等时水柱为空 → 该列无
 *       水块、露出沙底。判据 {@code floor(bed) >= floor(surface)}。</li>
 *   <li><b>阶梯（图1）</b>：侵蚀让河道床面沿程非单调跳变。判据：水平相邻河道列
 *       的床面高差 ≥ 2 / ≥ 4 格的次数。</li>
 * </ol>
 *
 * <p><b>为什么需要本探针</b>：既有 {@code ValleySeamProbe} / {@code HydrologyWaterFillProbe}
 * 都<b>直接</b>调用 {@code HydrologyBlockCarver.carveChunk}，绕开了
 * {@code applyHydrologyValley}（侵蚀与河床的汇合点）。实测开关前后它们的数字逐位
 * 相同 → 无法覆盖本次修复，必须走门面生产路径才能观测到差异。</p>
 */
public final class ErosionRiverCoherenceProbe {

    private ErosionRiverCoherenceProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int regionR = args.length > 1 ? Integer.parseInt(args[1]) : 1;   // 河网 region 半径

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);

        // ★ 先用河网定位真正有河的 chunk：原点附近未必有河，盲扫网格会全部落空
        //   （实测 chunkRadius=6 盲扫 channelColumns=0）。
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);
        Set<Long> riverChunks = new LinkedHashSet<>();
        java.util.List<RiverLineRegion.RiverPolyline> rivers = new java.util.ArrayList<>();
        for (int rz = -regionR; rz <= regionR; rz++) {
            for (int rx = -regionR; rx <= regionR; rx++) {
                RiverLineRegion region = engine.network().region(rx, rz);
                for (RiverLineRegion.RiverPolyline river : region.rivers) {
                    rivers.add(river);
                    for (int i = 0; i < river.nodes.length; i++) {
                        int bx = (int) Math.floor(river.nodes[i].x() * hs);
                        int bz = (int) Math.floor(river.nodes[i].z() * hs);
                        riverChunks.add(key(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
                    }
                }
            }
        }

        int channel = 0, dry = 0, zeroWaterBlock = 0;
        double minDepth = Double.MAX_VALUE, maxDepth = -Double.MAX_VALUE, sumDepth = 0.0;
        Map<Long, Double> bed = new HashMap<>();

        for (long ck : riverChunks) {
            int cx = (int) (ck >> 32), cz = (int) ck;
            {
                Cell[] cells = terrain.getChunkCells(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        Cell c = cells[lx * 16 + lz];
                        if (c.riverType == 0) continue;
                        int bx = cx * 16 + lx, bz = cz * 16 + lz;
                        bed.put(key(bx, bz), c.height);
                        channel++;
                        double ws = c.riverSurfaceY;
                        double depth = ws - c.height;
                        sumDepth += depth;
                        if (depth < minDepth) minDepth = depth;
                        if (depth > maxDepth) maxDepth = depth;
                        if (depth <= 0.0) dry++;
                        // 落块语义：水柱 = y ∈ (floor(bed), floor(ws)] → floor 相等即无水块
                        if (Math.floor(c.height) >= Math.floor(ws)) zeroWaterBlock++;
                    }
                }
            }
        }

        // 相邻河道列床面高差（阶梯 / 非单调指标）
        int step2 = 0, step4 = 0;
        double maxStep = 0.0;
        for (Map.Entry<Long, Double> e : bed.entrySet()) {
            long k = e.getKey();
            Double right = bed.get(k + (1L << 32));
            if (right == null) continue;
            double d = Math.abs(right - e.getValue());
            if (d > maxStep) maxStep = d;
            if (d >= 2.0) step2++;
            if (d >= 4.0) step4++;
        }

        // ★ 沿河单调性（图1 阶梯的核心指标）：水平相邻列高差会被"计划内瀑布/峡谷横断面"
        //   主导（实测 maxStep≈37 格，而侵蚀 |Δ| 均值仅 0.29 格），测不到沿程非单调。
        //   这里沿河线逐节点走：用计划水面定向（水面低 = 下游），统计"沿下游床面反而抬高"。
        //   按量级区分：≤6 格 = 侵蚀量级（|Δ|max≈5.9，本次要根治的）；>6 格 = 计划瀑布（合法）。
        int pairs = 0, rises = 0, risesSmall = 0;
        double maxRise = 0.0;
        for (RiverLineRegion.RiverPolyline river : rivers) {
            for (int i = 0; i + 1 < river.nodes.length; i++) {
                int ax = (int) Math.floor(river.nodes[i].x() * hs);
                int az = (int) Math.floor(river.nodes[i].z() * hs);
                int bx = (int) Math.floor(river.nodes[i + 1].x() * hs);
                int bz = (int) Math.floor(river.nodes[i + 1].z() * hs);
                Cell ca = terrain.sampleCell(ax, az);
                Cell cb = terrain.sampleCell(bx, bz);
                if (ca.riverType == 0 || cb.riverType == 0) continue;
                pairs++;
                boolean aDown = ca.riverSurfaceY > cb.riverSurfaceY;   // 水面低的一侧 = 下游
                double upBed = aDown ? ca.height : cb.height;
                double downBed = aDown ? cb.height : ca.height;
                double rise = downBed - upBed;      // >0 = 沿下游反而抬高 = 非单调
                if (rise > 0.5) {
                    rises++;
                    if (rise <= 6.0) risesSmall++;
                    if (rise > maxRise) maxRise = rise;
                }
            }
        }

        System.out.println("=== ErosionRiverCoherenceProbe ===");
        System.out.println("seed=" + seed + " regionRadius=" + regionR
                + " riverChunks=" + riverChunks.size());
        System.out.println("channelColumns=" + channel);
        System.out.println("dry(depth<=0)=" + dry);
        System.out.println("zeroWaterBlock(floor(bed)>=floor(surface))=" + zeroWaterBlock);
        System.out.printf("depth min=%.3f mean=%.3f max=%.3f%n",
                channel > 0 ? minDepth : 0.0,
                channel > 0 ? sumDepth / channel : 0.0,
                channel > 0 ? maxDepth : 0.0);
        System.out.println("bedStep>=2=" + step2 + " bedStep>=4=" + step4
                + " maxStep=" + maxStep);
        System.out.println("alongRiver pairs=" + pairs + " riseDownstream=" + rises
                + " riseErosionScale(<=6)=" + risesSmall + " maxRise=" + maxRise);
        System.out.println("status=" + (zeroWaterBlock == 0 ? "PASS" : "FAIL(zeroWaterBlock>0)"));
    }

    private static long key(int bx, int bz) {
        return (((long) bx) << 32) | (bz & 0xffffffffL);
    }
}
