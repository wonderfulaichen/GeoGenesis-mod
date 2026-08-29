package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 河道方块级局部爬坡诊断探针（2026-08-29，重写版）。
 *
 * <p>★ 诊断对象 = 实际河床 {@code carvedGroundY}（不是水面；水面只是跟着河床走）。</p>
 *
 * <p>现象：宏观（节点级）水面单调下降，但方块级河道有局部"爬坡"。本探针沿每条河的
 * <b>有向中心线</b>（head→mouth）逐方块追踪，复用生产雕刻管线
 * {@link HydrologyBlockCarver#carveChunk} 取真实 {@code carvedGroundY}，并复刻雕刻器的
 * IDW 混合 / smooth-min 距离场，把每个方块分解为 5 层：</p>
 * <pre>
 *   L1 surfaceY                 —— 混合后的单调水面
 *   L2 surfaceY − depth         —— 理论中心河床（忽略断面/外缘衰减）
 *   L3 bedTarget = min(surfY,orig) − depth·profile  —— 雕刻目标河床（忽略 outer 衰减）
 *   L4 carvedGroundY            —— 真实雕刻河床（生产值，含 outer/地形回弹）
 *   L5 floor(L4)                —— 最终游戏落块的河床方块 Y（用户看到的整数高度）
 * </pre>
 *
 * <p>控制变量归因：当 L5 出现上行（floor 升 ≥1 块）时，回溯 L1..L4 的逐层增量，定位是
 * 哪一层先抬升：</p>
 * <ul>
 *   <li><b>A · IDW 混水面</b>：L1 升 → 几何邻近但水文上游的河段水面被混入；</li>
 *   <li><b>B · 河深变浅</b>：L2/L3 升而 L1 不升 → depth 沿程变浅快于水面下降；</li>
 *   <li><b>C · outer 回弹 / 中心列偏移</b>：L3 降但 L4 升 → block 离中心/入谷壁使
 *       {@code outer<1}，雕刻被拉回原始地形（整数方块网格使 dist 在中心线两侧振荡放大）；</li>
 *   <li><b>D · 整数化放大</b>：L4 不升但 L5 升 → 连续河床仅微降却跨过整数边界，落块显出上行。</li>
 * </ul>
 *
 * <p>用法：{@code gradlew runRiverLineMicroUphillProbe -PprobeArgs="12345 1"}</p>
 */
public final class RiverLineMicroUphillProbe {
    private RiverLineMicroUphillProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1;   // region 半径（默认 1 → 9 个）
        double scale = 2.0;   // GeoGenesisConfig 默认 horizontalScale（wu / block）

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(tp, tp.minY(), tp.maxY());
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(terrain, seed);
        RiverLineNetwork net = engine.network();
        RiverLineParams P = RiverLineParams.defaults();

        double blendDist = P.heightBlendDist();        // wu，与雕刻器一致（不乘 scale）
        double k = P.smoothMinK();
        double bankFactor = P.bankFactor();
        double valleyExp = P.valleyExp();
        double seaLevel = terrain.heightCurve().seaLevelY();
        double EPS = 0.25;   // 连续层上行阈值（block）

        // ===== chunk 雕刻缓存 =====
        Map<Long, List<HydrologyBlockCarvedColumn>> chunkCache = new HashMap<>();
        Map<Long, HydrologyBlockCarvedColumn> colMap = new HashMap<>();

        // ===== 计数器 =====
        long macroViol = 0;                 // 节点级 pl.surfaceY 非单调（sanity）
        long[] up = new long[5];            // L1..L5 上行步数
        long upL5 = 0;                      // 用户可见的方块级河床爬坡
        long aIdw = 0, bDepth = 0, cOuter = 0, dQuant = 0, geo = 0;
        long l5Multi = 0, l5Vertex = 0, l5Boundary = 0;
        double maxL5 = 0.0;
        int worstRx = 0, worstRz = 0, worstBx = 0, worstBz = 0;
        int rivers = 0, blocks = 0;
        double distSum = 0, distSumSq = 0, distN = 0;

        System.out.println("=== River BED micro-scale local uphill diagnostics ===");
        System.out.println("seed=" + seed + "  regionRadius=" + n + "  scale=" + scale
                + "  blendDist(wu)=" + fmt(blendDist) + "  smoothMinK=" + k
                + "  EPS=" + EPS);

        for (int rz = -n; rz <= n; rz++) {
            for (int rx = -n; rx <= n; rx++) {
                RiverLineRegion reg = net.region(rx, rz);
                for (RiverLineRegion.RiverPolyline pl : reg.rivers) {
                    int m = pl.nodes.length;
                    if (m < 2) continue;
                    rivers++;

                    // sanity：节点级水面单调（下游只降）
                    for (int i = 1; i < m; i++) {
                        if (pl.surfaceY[i] > pl.surfaceY[i - 1] + 1e-6) macroViol++;
                    }

                    // 沿有向中心线逐方块取整数块坐标（去重、保序）
                    List<int[]> blks = new ArrayList<>();
                    for (int i = 0; i < m - 1; i++) {
                        MidpointDisplacement.Node a = pl.nodes[i];
                        MidpointDisplacement.Node b = pl.nodes[i + 1];
                        double segLen = Math.hypot(b.x() - a.x(), b.z() - a.z());
                        int steps = Math.max(1, (int) Math.ceil(segLen / scale));
                        for (int s = 0; s < steps; s++) {
                            double t = (double) s / steps;
                            double wx = a.x() + (b.x() - a.x()) * t;
                            double wz = a.z() + (b.z() - a.z()) * t;
                            int bx = (int) Math.round(wx * scale);
                            int bz = (int) Math.round(wz * scale);
                            if (!blks.isEmpty()) {
                                int[] last = blks.get(blks.size() - 1);
                                if (last[0] == bx && last[1] == bz) continue;
                            }
                            blks.add(new int[]{bx, bz, (s == 0 ? 1 : 0)}); // [bx,bz,isVertex]
                        }
                    }
                    // 末节点
                    {
                        MidpointDisplacement.Node lastN = pl.nodes[m - 1];
                        int bx = (int) Math.round(lastN.x() * scale);
                        int bz = (int) Math.round(lastN.z() * scale);
                        if (!blks.isEmpty()) {
                            int[] last = blks.get(blks.size() - 1);
                            if (!(last[0] == bx && last[1] == bz))
                                blks.add(new int[]{bx, bz, 1});
                        }
                    }

                    // 逐块计算 5 层
                    List<Rec> recs = new ArrayList<>();
                    for (int[] bb : blks) {
                        Rec r = computeBlock(bb[0], bb[1], bb[2] == 1,
                                engine, terrain, chunkCache, colMap,
                                blendDist, k, bankFactor, valleyExp, seaLevel, scale);
                        if (r == null) continue;
                        recs.add(r);
                        distSum += r.dist; distSumSq += r.dist * r.dist; distN++;
                    }

                    // 沿程比较相邻方块
                    Rec prev = null;
                    for (Rec r : recs) {
                        if (prev != null) {
                            double dL1 = r.L1 - prev.L1;
                            double dL2 = r.L2 - prev.L2;
                            double dL3 = r.L3 - prev.L3;
                            double dL4 = r.L4 - prev.L4;
                            int dL5 = r.L5 - prev.L5;
                            if (dL1 > EPS) up[0]++;
                            if (dL2 > EPS) up[1]++;
                            if (dL3 > EPS) up[2]++;
                            if (dL4 > EPS) up[3]++;
                            if (dL5 > 0) {
                                up[4]++;
                                upL5++;
                                // 归因
                                if (dL4 <= 0) dQuant++;
                                else if (dL3 <= 0) cOuter++;
                                else if (dL1 > EPS) aIdw++;
                                else bDepth++;
                                if (r.hitCount > 1) l5Multi++;
                                if (r.nearVertex) l5Vertex++;
                                if (r.nearBoundary) l5Boundary++;
                                if (dL5 > maxL5) {
                                    maxL5 = dL5;
                                    worstRx = rx; worstRz = rz;
                                    worstBx = r.bx; worstBz = r.bz;
                                }
                            }
                        }
                        prev = r;
                    }
                    blocks += recs.size();
                }
            }
        }

        // ===== 输出 =====
        distN = Math.max(1, distN);
        double distMean = distSum / distN;
        double distVar = Math.max(0, distSumSq / distN - distMean * distMean);
        double distStd = Math.sqrt(distVar);

        System.out.println();
        System.out.println("-- sanity: macro (node-level) monotonicity --");
        System.out.println("rivers=" + rivers + "  blocks(wu-step)=" + blocks);
        System.out.println("node-level surf violations = " + macroViol
                + (macroViol == 0 ? "  <- 宏观单调 OK" : "  <- 异常！"));
        System.out.println("centerline distToCenter: mean=" + fmt(distMean)
                + "  std=" + fmt(distStd) + " block"
                + "  (std 大 ⇒ 整数网格使中心列在两岸振荡)");

        System.out.println();
        System.out.println("-- micro (block-scale) uphill counts per layer --");
        System.out.println("L1 surfaceY                 uphill = " + up[0]);
        System.out.println("L2 surfaceY-depth          uphill = " + up[1]);
        System.out.println("L3 bedTarget               uphill = " + up[2]);
        System.out.println("L4 carvedGroundY (real)    uphill = " + up[3]);
        System.out.println("L5 floor(carved) [可见]    uphill = " + up[4]
                + "   <- 用户实际看到的河道爬坡");
        System.out.println("max single L5 climb = " + fmt(maxL5) + " block @ region("
                + worstRx + "," + worstRz + ") block(" + worstBx + "," + worstBz + ")");

        System.out.println();
        System.out.println("-- attribution of L5 uphill (n=" + upL5 + ") --");
        long denom = Math.max(1, upL5);
        System.out.println("A IDW混水面(L1升)        = " + aIdw
                + "  (" + pct(aIdw, denom) + ")");
        System.out.println("B 河深变浅(L2/L3升,L1平) = " + bDepth
                + "  (" + pct(bDepth, denom) + ")");
        System.out.println("C outer回弹/中心列偏移    = " + cOuter
                + "  (" + pct(cOuter, denom) + ")");
        System.out.println("D 整数化放大(L4平,L5升)   = " + dQuant
                + "  (" + pct(dQuant, denom) + ")");
        System.out.println("其他几何                  = " + geo
                + "  (" + pct(geo, denom) + ")");
        System.out.println("其中: 多段混合命中=" + l5Multi
                + "  近河曲顶点=" + l5Vertex + "  近 chunk 边界=" + l5Boundary);

        System.out.println();
        System.out.println("-- verdict --");
        long[] cnt = {aIdw, bDepth, cOuter, dQuant};
        String[] name = {"A·IDW混水面", "B·河深变浅", "C·outer回弹/中心列偏移", "D·整数化放大"};
        long maxC = -1; int maxI = -1;
        for (int i = 0; i < 4; i++) if (cnt[i] > maxC) { maxC = cnt[i]; maxI = i; }
        if (upL5 == 0) {
            System.out.println("未检测到方块级河床爬坡（与用户观察不符，需换种子/半径复测）。");
        } else {
            System.out.println("★ 主因 = " + name[maxI] + "（占 " + pct(maxC, denom)
                    + " 的可见爬坡）。");
            if (maxI == 2) {
                System.out.println("   佐证：centerline dist std=" + fmt(distStd)
                        + " block —— 整数方块网格使逐块\"中心列\"在河线两侧来回偏移，"
                        + "dist/profile/outer 随之振荡，雕刻被反复拉回原始地形 → 局部抬床。");
            }
        }
    }

    /** 单块 5 层分解；无命中返回 null。 */
    private static Rec computeBlock(int bx, int bz, boolean isVertex,
                                    HydrologyExperimentEngine engine, CellGenerator terrain,
                                    Map<Long, List<HydrologyBlockCarvedColumn>> chunkCache,
                                    Map<Long, HydrologyBlockCarvedColumn> colMap,
                                    double blendDist, double k, double bankFactor,
                                    double valleyExp, double seaLevel, double scale) {
        List<HydrologyBlockSample> samples = engine.sampleBlockAll(bx, bz, scale);
        if (samples.isEmpty()) return null;

        double nearestDist = samples.get(0).distToCenter();
        double dist = nearestDist;
        for (HydrologyBlockSample s : samples) {
            dist = smin(dist, s.distToCenter(), k);          // 复刻雕刻器 C1 距离场
        }

        double wSum = 0.0, sWid = 0.0, sDep = 0.0;
        int hitCount = 0;
        for (HydrologyBlockSample s : samples) {
            double d = s.distToCenter();
            if (d > blendDist) break;                         // sampleBlockAll 已按距离升序
            hitCount++;
            double fade = NoiseUtil.saturate(d / blendDist);
            double w = (1.0 - fade) * (1.0 - fade) / Math.max(d * d, 1.0);
            wSum += w;
            sWid += w * s.width();
            sDep += w * s.depth();
        }
        HydrologyBlockSample nearest = samples.get(0);
        double width, depth;
        if (wSum > 1e-9) {
            width = sWid / wSum;
            depth = sDep / wSum;
        } else {
            width = nearest.width();
            depth = nearest.depth();
        }
        double surfaceY = nearest.surfaceY();
        width = Math.max(width, 1.0);

        double bankW = width * bankFactor;
        double valley = Math.max(width + bankW, width * 3.0);
        double t = NoiseUtil.saturate(dist / width);
        double profile = 1.0 - t;                            // 中心 1.0 → 缘 0.0
        double valleyT = NoiseUtil.saturate((dist - width) / Math.max(1.0, valley - width));
        double outer = valleyOuter(valleyT, valleyExp);

        double wx = bx / scale, wz = bz / scale;
        double original = terrain.sampleWu(wx, wz).height;
        // 与生产雕刻器一致：水面由河线有向纵剖面决定，不再逐块贴局部地形。
        double waterSurface = surfaceY;
        double bedTarget = waterSurface - depth * profile;   // L3

        HydrologyBlockCarvedColumn col = getCarved(bx, bz, engine, terrain,
                chunkCache, colMap, scale);
        if (col == null) return null;
        double carved = col.carvedGroundY();                  // L4（生产值）

        boolean nearBoundary = ((bx & 15) < 2 || (bx & 15) > 13
                || (bz & 15) < 2 || (bz & 15) > 13);
        return new Rec(bx, bz, surfaceY, surfaceY - depth, bedTarget, carved,
                (int) Math.floor(carved), dist, profile, outer, width, depth,
                hitCount, original, isVertex, nearBoundary);
    }

    private static HydrologyBlockCarvedColumn getCarved(int bx, int bz,
                                                        HydrologyExperimentEngine engine,
                                                        CellGenerator terrain,
                                                        Map<Long, List<HydrologyBlockCarvedColumn>> chunkCache,
                                                        Map<Long, HydrologyBlockCarvedColumn> colMap,
                                                        double scale) {
        int cx = (int) Math.floorDiv(bx, 16);
        int cz = (int) Math.floorDiv(bz, 16);
        long ck = ((long) cx << 32) | (cz & 0xffffffffL);
        List<HydrologyBlockCarvedColumn> cols = chunkCache.get(ck);
        if (cols == null) {
            double[] og = new double[256];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int idx = lx * 16 + lz;
                    og[idx] = terrain.sampleWu((cx * 16 + lx) / scale,
                            (cz * 16 + lz) / scale).height;
                }
            }
            cols = HydrologyBlockCarver.carveChunk(engine, cx, cz, scale, og);
            chunkCache.put(ck, cols);
            for (HydrologyBlockCarvedColumn c : cols) {
                colMap.put(blockKey(c.blockX(), c.blockZ()), c);
            }
        }
        return colMap.get(blockKey(bx, bz));
    }

    private static long blockKey(int bx, int bz) {
        return ((long) bx << 32) | (bz & 0xffffffffL);
    }

    /** 二次 smooth-min（IQ），复刻雕刻器。 */
    private static double smin(double a, double b, double k) {
        double h = NoiseUtil.clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return (a * h + b * (1.0 - h)) - k * h * (1.0 - h);
    }

    /** 河谷壁外缘衰减，复刻雕刻器。 */
    private static double valleyOuter(double vt, double exp) {
        double inner = 1.0 - Math.pow(vt, exp);
        double tail = 1.0 - NoiseUtil.smooth(vt);
        double m = NoiseUtil.smooth(NoiseUtil.saturate((vt - 0.5) / 0.5));
        return inner * (1.0 - m) + tail * m;
    }

    private static final class Rec {
        final int bx, bz;
        final double L1, L2, L3, L4;
        final int L5;
        final double dist, profile, outer, width, depth, original;
        final int hitCount;
        final boolean nearVertex, nearBoundary;
        Rec(int bx, int bz, double L1, double L2, double L3, double L4, int L5,
            double dist, double profile, double outer, double width, double depth,
            int hitCount, double original, boolean nearVertex, boolean nearBoundary) {
            this.bx = bx; this.bz = bz; this.L1 = L1; this.L2 = L2; this.L3 = L3; this.L4 = L4;
            this.L5 = L5; this.dist = dist; this.profile = profile; this.outer = outer;
            this.width = width; this.depth = depth; this.hitCount = hitCount;
            this.original = original; this.nearVertex = nearVertex; this.nearBoundary = nearBoundary;
        }
    }

    private static String pct(long a, long b) {
        return String.format("%5.1f%%", 100.0 * a / b);
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}
