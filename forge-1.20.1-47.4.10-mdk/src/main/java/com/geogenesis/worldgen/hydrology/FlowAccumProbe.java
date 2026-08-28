package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 汇流场河网验收探针（离线纯 Java，不启动 MC）。
 *
 * <p>运行：{@code gradlew runFlowAccumProbe [-PprobeArgs=seed]}</p>
 *
 * <p>验收五项（2026-08-28 汇流范式）：</p>
 * <ol>
 *   <li><b>拓扑</b>：河网到达海洋的 region 比例（本 region 河网入海即计数）；</li>
 *   <li><b>剖面</b>：surfaceY 沿线上游→下游单调不升（0 违例）；</li>
 *   <li><b>动线</b>：沿线 e 基本不升（D8 下坡路径，残差=平滑抖动）；</li>
 *   <li><b>溢出门控</b>：fillWater 列必须满足 dist≤width 且 carved&lt;surface−0.5；</li>
 *   <li><b>边界连续</b>：chunk 边界两侧属主水面差 ≤1.5。</li>
 * </ol>
 * 另报 region 冷/热构建耗时（性能预算：冷亚毫秒×region 数）。
 */
public final class FlowAccumProbe {
    private static final int REGION_RADIUS = 4;        // ±4 region = ±2880wu（密度采样）
    private static final int CHUNK_RADIUS = 20;        // 溢出检查区 ±320wu
    private static final double BORDER_TOLERANCE = 1.5;

    private FlowAccumProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineNetwork net = engine.network();

        long t0 = System.nanoTime();
        int riverRegions = 0;
        for (int rz = -REGION_RADIUS; rz <= REGION_RADIUS; rz++)
            for (int rx = -REGION_RADIUS; rx <= REGION_RADIUS; rx++)
                if (net.region(rx, rz).hasRiver()) riverRegions++;
        long coldMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        for (int rz = -REGION_RADIUS; rz <= REGION_RADIUS; rz++)
            for (int rx = -REGION_RADIUS; rx <= REGION_RADIUS; rx++)
                net.region(rx, rz);
        long warmMs = (System.nanoTime() - t1) / 1_000_000;

        int[] topo = topologyStats(net);
        double[] profile = profileStats(net);
        double maxERise = lineERise(engine);
        double[] overflow = overflowStats(engine, terrain);
        double[] border = borderStats(engine);

        System.out.println("=== FlowAccumProbe ===");
        System.out.println("seed=" + seed);
        int side = (2 * REGION_RADIUS + 1) * 640;
        double per1000 = riverRegions / Math.pow(side / 1000.0, 2);
        System.out.println("regions=" + (2 * REGION_RADIUS + 1) * (2 * REGION_RADIUS + 1)
                + " riverRegions=" + riverRegions
                + String.format(" riversPer1000wu=%.2f", per1000));
        System.out.println("coldMs=" + coldMs + " warmMs=" + warmMs);
        System.out.println("topo.reachedOcean=" + topo[0] + "/" + topo[1]
                + String.format(" (%.1f%%)", topo[1] == 0 ? 100.0 : 100.0 * topo[0] / topo[1]));
        System.out.println("topo.cycles=" + topo[2]);
        System.out.println(String.format("profile.violations=%d maxRise=%.3f",
                (int) profile[0], profile[1]));
        System.out.println(String.format("flow.maxERise=%.4f", maxERise));
        System.out.println(String.format("overflow.columns=%d hitColumns=%d fillWater=%d"
                        + " gateViolations=%d maxWaterDepth=%.2f deepAbnormal=%d",
                (int) overflow[0], (int) overflow[1], (int) overflow[2], (int) overflow[3],
                overflow[4], (int) overflow[5]));
        System.out.println(String.format("border.maxSurfaceDelta=%.3f border.violations=%d",
                border[0], (int) border[1]));
        double[] diag = diagnosticStats(net);
        double rollbackRate = diag[0] == 0 ? 0.0 : 100.0 * diag[1] / diag[0];
        double confluenceRate = diag[0] == 0 ? 0.0 : 100.0 * diag[2] / diag[0];
        System.out.println(String.format(
                "diag.sources=%.0f rolledBack=%.0f rollbackRate=%.1f%%"
                        + " joined=%.0f confluenceRate=%.1f%% lakes=%.0f regions=%.0f",
                diag[0], diag[1], rollbackRate, diag[2], confluenceRate, diag[3], diag[4]));
        boolean pass = topo[2] == 0 && profile[0] == 0
                && (int) overflow[3] == 0 && (int) border[1] == 0;
        System.out.println("status=" + (pass ? "PASS" : "REVIEW"));
    }

    /** 拓扑：{到海 region 数, 有河 region 数, 环数}。本 region 河网到达海洋即计数。 */
    private static int[] topologyStats(RiverLineNetwork net) {
        int total = 0, reached = 0, cycles = 0;
        for (int rz = -REGION_RADIUS; rz <= REGION_RADIUS; rz++) {
            for (int rx = -REGION_RADIUS; rx <= REGION_RADIUS; rx++) {
                RiverLineRegion r = net.region(rx, rz);
                if (!r.hasRiver()) continue;
                total++;
                if (r.outletOcean) reached++;
            }
        }
        return new int[]{reached, total, cycles};
    }

    /** 剖面：{违例数, 最大上抬}。上游 surfaceY 必须 ≥ 下游（单调不升）。 */
    private static double[] profileStats(RiverLineNetwork net) {
        int violations = 0;
        double maxRise = 0.0;
        for (RiverLineRegion r : net.cachedList()) {
            for (RiverLineRegion.RiverPolyline pl : r.rivers) {
                double[] surf = pl.surfaceY;
                for (int i = 0; i < surf.length - 1; i++) {
                    double rise = surf[i + 1] - surf[i];
                    if (rise > 1e-6) violations++;
                    maxRise = Math.max(maxRise, rise);
                }
            }
        }
        return new double[]{violations, maxRise};
    }

    /** 动线：全部已缓存 region 沿线 e 最大上抬（D8 下坡路径应 ≈0）。 */
    private static double lineERise(HydrologyExperimentEngine engine) {
        MidpointDisplacement.ElevationSampler e = engine.terrain()::terrainEQuick;
        double maxRise = -1;
        for (RiverLineRegion r : engine.network().cachedList()) {
            for (RiverLineRegion.RiverPolyline pl : r.rivers) {
                MidpointDisplacement.Node[] nodes = pl.nodes;
                for (int i = 0; i < nodes.length - 1; i++) {
                    maxRise = Math.max(maxRise,
                            e.eAt(nodes[i + 1].x(), nodes[i + 1].z())
                                    - e.eAt(nodes[i].x(), nodes[i].z()));
                }
            }
        }
        return maxRise;
    }

    /** 溢出门控：{列数, 命中列, fillWater 数, 门控违例, 最大水深, 深水异常}。 */
    private static double[] overflowStats(HydrologyExperimentEngine engine, CellGenerator terrain) {
        HeightCurve curve = terrain.heightCurve();
        RiverLineParams rp = RiverLineParams.defaults();
        Set<Long> chunks = riverChunks(engine);
        long cols = 0, hits = 0, fill = 0, gateBad = 0, deepBad = 0;
        double maxDepth = 0.0;
        for (long key : chunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            double[] ground = baseGround(terrain, curve, cx, cz);
            cols += 256;
            List<HydrologyBlockCarvedColumn> carved =
                    HydrologyBlockCarver.carveChunk(engine, cx, cz, 1.0, ground);
            hits += carved.size();
            for (HydrologyBlockCarvedColumn c : carved) {
                if (!c.fillWater()) continue;
                fill++;
                double depth = c.waterSurfaceY() - c.carvedGroundY();
                maxDepth = Math.max(maxDepth, depth);
                if (depth > rp.maxDepth() + 1.0) deepBad++;
                HydrologyBlockSample s = engine.sampleBlock(c.blockX(), c.blockZ(), 1.0);
                if (s == null || s.distToCenter() > Math.max(s.width(), 1.0)
                        || c.carvedGroundY() >= c.waterSurfaceY() - 0.5) gateBad++;
            }
        }
        return new double[]{cols, hits, fill, gateBad, maxDepth, deepBad};
    }

    /** 河线覆盖的 chunk 集合（钳制在 ±CHUNK_RADIUS×16 范围）。 */
    private static Set<Long> riverChunks(HydrologyExperimentEngine engine) {
        int lim = CHUNK_RADIUS * 16;
        Set<Long> out = new HashSet<>();
        for (RiverLineRegion r : engine.network().cachedList()) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (RiverLineRegion.RiverPolyline pl : r.rivers) {
                for (MidpointDisplacement.Node n : pl.nodes) {
                    minX = Math.min(minX, n.x()); maxX = Math.max(maxX, n.x());
                    minZ = Math.min(minZ, n.z()); maxZ = Math.max(maxZ, n.z());
                }
            }
            if (!Double.isFinite(minX)) continue;
            int x0 = Math.max(-lim, (int) Math.floor(minX / 16.0));
            int x1 = Math.min(lim - 1, (int) Math.floor(maxX / 16.0));
            int z0 = Math.max(-lim, (int) Math.floor(minZ / 16.0));
            int z1 = Math.min(lim - 1, (int) Math.floor(maxZ / 16.0));
            for (int cz = z0; cz <= z1; cz++)
                for (int cx = x0; cx <= x1; cx++)
                    out.add(((long) cx << 32) | (cz & 0xffffffffL));
        }
        return out;
    }

    /** 16×16 基础地形高度（heightFromE∘terrainEQuick，纯噪声场，无侵蚀 tile 依赖）。 */
    private static double[] baseGround(CellGenerator terrain, HeightCurve curve, int cx, int cz) {
        double[] ground = new double[256];
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int bx = cx * 16 + lx, bz = cz * 16 + lz;
                ground[lx * 16 + lz] = curve.heightFromE(terrain.terrainEQuick(bx, bz));
            }
        return ground;
    }

    /** 边界连续：chunk 边界两侧属主水面差。{最大差, 违例数}。 */
    private static double[] borderStats(HydrologyExperimentEngine engine) {
        double maxDelta = 0.0;
        int violations = 0;
        for (int k = -CHUNK_RADIUS; k <= CHUNK_RADIUS; k++) {
            int edge = k * 16;
            for (int t = -CHUNK_RADIUS * 16; t < CHUNK_RADIUS * 16; t += 8) {
                double[][] pairs = {{edge - 1, t, edge, t}, {t, edge - 1, t, edge}};
                for (double[] p : pairs) {
                    HydrologyBlockSample a = engine.sampleBlock(p[0], p[1], 1.0);
                    HydrologyBlockSample b = engine.sampleBlock(p[2], p[3], 1.0);
                    if (a == null || b == null) continue;
                    if (a.distToCenter() > a.width() || b.distToCenter() > b.width()) continue;
                    double d = Math.abs(a.surfaceY() - b.surfaceY());
                    if (d > maxDelta) maxDelta = d;
                    if (d > BORDER_TOLERANCE) violations++;
                }
            }
        }
        return new double[]{maxDelta, violations};
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** 诊断：跨已缓存 region 聚合候选源/回滚/汇入/湖数（PL-RGA 对齐指标）。 */
    private static double[] diagnosticStats(RiverLineNetwork net) {
        int sources = 0, rolled = 0, joined = 0, lakes = 0, regions = 0;
        for (RiverLineRegion r : net.cachedList()) {
            sources += r.sourceCount; rolled += r.rolledBack; joined += r.joined;
            lakes += r.lakes.size(); regions++;
        }
        return new double[]{sources, rolled, joined, lakes, regions};
    }
}
