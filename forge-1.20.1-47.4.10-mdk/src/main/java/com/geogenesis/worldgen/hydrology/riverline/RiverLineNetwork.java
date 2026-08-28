package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion.RiverPolyline;
import com.geogenesis.worldgen.terrain.HeightCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 河线网络核心：region 级河网生成（汇流场派生）+ 距离场采样。
 *
 * <p><b>物理范式（2026-08-28 重写）</b>：河网不再由几何/分形线"画"出来，
 * 而是从地形汇流场"流"出来——高位布源、沿 D8 下坡追踪、汇入已有河，
 * 形成树状水系。每条河的水面 = 当地地表谷底（Streams 范式），河深/半宽由汇流面积驱动。</p>
 *
 * <p><b>确定性</b>：全部由 (worldSeed, rx, rz) 导出，复用 ConcurrentHashMap 惰性构建。</p>
 */
public final class RiverLineNetwork {

    /** 距离场采样结果。 */
    public record RiverLineHit(double distToCenter, double surfaceY, double width,
                               double depth, double dischargeArea, boolean reachesOcean) { }

    private static final int MAX_REGIONS = 256;

    private final Map<Long, RiverLineRegion> regions = new ConcurrentHashMap<>();
    /** 选线/贴谷用轻量 e 场（terrainEQuick，纯噪声基础场，无侵蚀 tile 依赖 → 无递归风险）。 */
    private final MidpointDisplacement.ElevationSampler eSampler;
    /** 剖面锚定用地形 Y 采样（sampleWu，含侵蚀 tile delta —— 河流必须贴真实地表走）。 */
    private final TerrainYSampler terrainY;
    private final HeightCurve curve;
    private final RiverLineParams params;
    private volatile long seed;

    /** 真实地表 Y 采样抽象（CellGenerator::sampleWu 注入；返回含侵蚀的最终 height）。 */
    public interface TerrainYSampler {
        double yAt(double wx, double wz);
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            HeightCurve curve, long seed) {
        this(eSampler, null, curve, seed, RiverLineParams.defaults());
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed) {
        this(eSampler, terrainY, curve, seed, RiverLineParams.defaults());
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, RiverLineParams params) {
        this.eSampler = eSampler;
        this.terrainY = terrainY;
        this.curve = curve;
        this.seed = seed;
        this.params = params;
    }

    public void setSeed(long next) {
        if (this.seed == next) return;
        this.seed = next;
        regions.clear();
    }

    public void clear() {
        regions.clear();
    }

    public int cachedRegions() {
        return regions.size();
    }

    // ===== 拓扑构建 =====

    public RiverLineRegion region(int rx, int rz) {
        long key = pack(rx, rz);
        RiverLineRegion r = regions.get(key);
        if (r != null) return r;
        r = regions.computeIfAbsent(key, ignored -> build(rx, rz));
        prune();
        return r;
    }

    /**
     * 构建 region：从地形汇流场派生河网（2026-08-28 物理范式重写）。
     *
     * <p>高位布源 → 沿 D8 下坡追踪 → 汇入已有河，生成树状水系；
     * 每条河的水面 = 当地地表谷底（Streams 范式），河深/半宽由汇流面积驱动。</p>
     */
    private RiverLineRegion build(int rx, int rz) {
        double regionSize = params.regionSize();
        double cell = params.gridCell();
        double margin = regionSize * 0.5;
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell, eSampler);

        int nx = field.cols(), nz = field.rows();
        boolean[] claimed = new boolean[nx * nz];
        List<RiverPolyline> rivers = new ArrayList<>();
        boolean outletOcean = false;
        double maxDischarge = 0.0;

        // 候选源：e > sourceMinE 的格，按 e 降序（高地优先，支流汇入主流）。
        // 参考 PL-RGA：source_min_height=0.5（山溪发源），min_source_spacing 控制密度。
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < nx * nz; i++) {
            if (field.eAt(i) > params.sourceMinE()) cand.add(i);
        }
        cand.sort((a, b) -> Double.compare(field.eAt(b), field.eAt(a)));

        int spacing = params.sourceSpacingCells();
        int stepSize = params.traceStep();
        List<Integer> accepted = new ArrayList<>();
        // 全局段集合（grid 坐标），用于防止河交叉（PL-RGA _wouldCrossExistingSegments）
        List<int[]> allSegments = new ArrayList<>();

        for (int s : cand) {
            if (claimed[s]) continue;
            int si = s % nx, sj = s / nx;
            boolean tooClose = false;
            for (int acc : accepted) {
                if (Math.abs((acc % nx) - si) <= spacing
                        && Math.abs((acc / nx) - sj) <= spacing) { tooClose = true; break; }
            }
            if (tooClose) continue;
            accepted.add(s);

            // 沿下坡窗口追踪（step_size 邻域取最陡下降格，非仅 8 邻 D8）；
            // 遇到已汇入的格或会交叉已有河段即停下（支流汇入主流 / 防交叉）。
            List<Integer> path = new ArrayList<>();
            int cur = s;
            boolean reachedOcean = false;
            while (cur >= 0 && !claimed[cur]) {
                claimed[cur] = true;
                path.add(cur);
                if (field.eAt(cur) <= params.oceanE()) { reachedOcean = true; break; }
                int down = downhillNeighbor(field, cur, stepSize, nx, nz);
                if (down < 0) break; // 局部洼地（内流终点）
                // 防交叉：若 (cur→down) 与已有河段相交，则在此汇入/终止
                int cri = cur % nx, crj = cur / nx;
                int dri = down % nx, drj = down / nx;
                if (segmentCrossesAny(cri, crj, dri, drj, allSegments)) break;
                cur = down;
            }
            if (path.size() < params.minRiverNodes()) continue;

            // 仅保留汇流面积高于阈值的部分（树状稀疏；源头细流不入河）。
            int start = 0;
            while (start < path.size()
                    && field.accumAt(path.get(start)) <= params.riverAccumThreshold()) {
                start++;
            }
            if (path.size() - start < params.minRiverNodes()) continue;

            // 记录本河所有段（用于后续河防交叉）
            for (int k = 0; k < path.size() - 1; k++) {
                int a = path.get(k), b = path.get(k + 1);
                allSegments.add(new int[]{a % nx, a / nx, b % nx, b / nx});
            }

            int m = path.size() - start;
            MidpointDisplacement.Node[] nodes = new MidpointDisplacement.Node[m];
            double[] rawSurf = new double[m], wid = new double[m], dep = new double[m];
            double logRange = Math.log(Math.max(2.0, params.areaLogRange()));
            double logMin = Math.log(Math.max(1.0, params.riverAccumThreshold()));
            double acc = 0.0;
            for (int k = 0; k < m; k++) {
                int idx = path.get(start + k);
                double wx = field.cellCenterX(idx), wz = field.cellCenterZ(idx);
                double a = field.accumAt(idx);
                nodes[k] = new MidpointDisplacement.Node(wx, wz);
                rawSurf[k] = groundYAt(wx, wz);   // 地形高度（用于河高插值）
                double t = NoiseUtil.saturate(
                        (Math.log(Math.max(params.riverAccumThreshold(), a)) - logMin) / logRange);
                double growth = t * t * (3.0 - 2.0 * t);
                wid[k] = params.minWidth() + (params.maxWidth() - params.minWidth()) * growth;
                dep[k] = params.minDepth() + (params.maxDepth() - params.minDepth()) * growth;
                acc = Math.max(acc, a);
            }
            // 河高沿程缓降（PL-RGA _applyRiverHeightSlopeDrop）：按地形高度线性插值，
            // 源端略降，保证水面单调顺滑、不随地形硬起伏。
            double[] surf = applyRiverHeightSlopeDrop(rawSurf, reachedOcean, curve, params);
            // Catmull-Rom 细分平滑：消除格点弯道处的矩形切口/地形断裂面。
            RiverPolyline smoothed = smoothPath(nodes, surf, wid, dep);
            maxDischarge = Math.max(maxDischarge, acc);
            rivers.add(smoothed);
            if (reachedOcean) outletOcean = true;
        }

        return new RiverLineRegion(rx, rz, rivers, outletOcean, maxDischarge);
    }

    // ===== Catmull-Rom 细分平滑 =====

    private static final double SMOOTH_SPACING = 4.0; // 目标节点间距（wu）

    /**
     * Catmull-Rom 细分平滑：把 D8 粗折线重采样为 ~SMOOTH_SPACING 间距的光滑曲线。
     * 弯道处节点密集（弧长短→分点多），直线段稀疏，雕刻不再产生矩形切口。
     * 端点复制首尾避免 Catmull-Rom 边界发散。
     */
    private RiverPolyline smoothPath(MidpointDisplacement.Node[] rawNodes,
                                     double[] rawSurf, double[] rawWid, double[] rawDep) {
        int n = rawNodes.length;
        if (n < 3) {
            return new RiverPolyline(rawNodes, rawSurf, rawWid, rawDep);
        }
        double[] px = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = rawNodes[i].x();
            pz[i] = rawNodes[i].z();
        }
        List<MidpointDisplacement.Node> outNodes = new ArrayList<>();
        List<Double> outSurf = new ArrayList<>();
        List<Double> outWid = new ArrayList<>();
        List<Double> outDep = new ArrayList<>();

        for (int i = 0; i < n - 1; i++) {
            double p0x = i > 0 ? px[i - 1] : px[0];
            double p0z = i > 0 ? pz[i - 1] : pz[0];
            double p1x = px[i], p1z = pz[i];
            double p2x = px[i + 1], p2z = pz[i + 1];
            double p3x = i + 2 < n ? px[i + 2] : px[n - 1];
            double p3z = i + 2 < n ? pz[i + 2] : pz[n - 1];
            double s0 = rawSurf[i], s1 = rawSurf[i + 1];
            double w0 = rawWid[i], w1 = rawWid[i + 1];
            double d0 = rawDep[i], d1 = rawDep[i + 1];
            double segLen = Math.hypot(p2x - p1x, p2z - p1z);
            int steps = Math.max(1, (int) Math.ceil(segLen / SMOOTH_SPACING));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                double t2 = t * t, t3 = t2 * t;
                double cx = 0.5 * ((2 * p1x) + (-p0x + p2x) * t
                        + (2 * p0x - 5 * p1x + 4 * p2x - p3x) * t2
                        + (-p0x + 3 * p1x - 3 * p2x + p3x) * t3);
                double cz = 0.5 * ((2 * p1z) + (-p0z + p2z) * t
                        + (2 * p0z - 5 * p1z + 4 * p2z - p3z) * t2
                        + (-p0z + 3 * p1z - 3 * p2z + p3z) * t3);
                outNodes.add(new MidpointDisplacement.Node(cx, cz));
                outSurf.add(s0 + (s1 - s0) * t);
                outWid.add(w0 + (w1 - w0) * t);
                outDep.add(d0 + (d1 - d0) * t);
            }
        }
        // 追加终点（不重复最后一个子段终点）
        outNodes.add(rawNodes[n - 1]);
        outSurf.add(rawSurf[n - 1]);
        outWid.add(rawWid[n - 1]);
        outDep.add(rawDep[n - 1]);

        // 蜿蜒（meander）：沿路径法向叠加正弦偏移，制造自然弯曲（参考 PL-RGA 河网形态）
        int m = outNodes.size();
        if (m >= 3 && params.meanderAmp() > 0.01) {
            double[] mx = new double[m], mz = new double[m];
            double[] arc = new double[m];
            for (int i = 0; i < m; i++) {
                mx[i] = outNodes.get(i).x();
                mz[i] = outNodes.get(i).z();
            }
            double acc = 0.0;
            for (int i = 1; i < m; i++) {
                acc += Math.hypot(mx[i] - mx[i - 1], mz[i] - mz[i - 1]);
                arc[i] = acc;
            }
            for (int i = 0; i < m; i++) {
                int prev = i > 0 ? i - 1 : 0;
                int next = i < m - 1 ? i + 1 : m - 1;
                double tx = mx[next] - mx[prev];
                double tz = mz[next] - mz[prev];
                double tl = Math.hypot(tx, tz);
                if (tl > 1e-6) { tx /= tl; tz /= tl; }
                double nx = -tz, nz = tx;   // 左转 90° 法向
                double off = params.meanderAmp() * Math.sin(2.0 * Math.PI * arc[i] / params.meanderWavelength());
                mx[i] += nx * off;
                mz[i] += nz * off;
            }
            for (int i = 0; i < m; i++) {
                outNodes.set(i, new MidpointDisplacement.Node(mx[i], mz[i]));
            }
        }

        MidpointDisplacement.Node[] rn = outNodes.toArray(new MidpointDisplacement.Node[0]);
        double[] rs = new double[outSurf.size()];
        double[] rw = new double[outWid.size()];
        double[] rd = new double[outDep.size()];
        for (int i = 0; i < rs.length; i++) {
            rs[i] = outSurf.get(i);
            rw[i] = outWid.get(i);
            rd[i] = outDep.get(i);
        }
        return new RiverPolyline(rn, rs, rw, rd);
    }

    // ===== 河网生成辅助（PL-RGA 对齐）=====

    /** 下坡邻域：在 step×step 窗口取最陡下降格（带微小距离惩罚），非仅 8 邻 D8（_downhillNeighbor）。 */
    private static int downhillNeighbor(FlowField field, int cur, int step, int nx, int nz) {
        int ci = cur % nx, cj = cur / nx;
        double curE = field.eAt(cur);
        int best = -1;
        double bestScore = curE; // 必须严格更低
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double e = field.eAt(idx);
                if (e >= curE - 1e-6) continue;
                double di = i - ci, dj = j - cj;
                double score = e + 1e-5 * (di * di + dj * dj);
                if (score < bestScore) { bestScore = score; best = idx; }
            }
        }
        return best;
    }

    /** 河段 (ax,ay)-(bx,by) 是否与已有段集合任一相交（_segmentsCross）。 */
    private static boolean segmentCrossesAny(int ax, int ay, int bx, int by, List<int[]> segs) {
        for (int[] s : segs) {
            if (segmentsCross(ax, ay, bx, by, s[0], s[1], s[2], s[3])) return true;
        }
        return false;
    }

    private static boolean segmentsCross(int ax, int ay, int bx, int by,
                                          int cx, int cy, int dx, int dy) {
        int o1 = orientation(ax, ay, bx, by, cx, cy);
        int o2 = orientation(ax, ay, bx, by, dx, dy);
        int o3 = orientation(cx, cy, dx, dy, ax, ay);
        int o4 = orientation(cx, cy, dx, dy, bx, by);
        return ((o1 > 0) != (o2 > 0)) && ((o3 > 0) != (o4 > 0));
    }

    private static int orientation(int ax, int ay, int bx, int by, int cx, int cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /** 河高沿程缓降：按地形高度线性插值，源端略降（_applyRiverHeightSlopeDrop）。 */
    private static double[] applyRiverHeightSlopeDrop(double[] rawSurf, boolean reachedOcean,
                                                      HeightCurve curve, RiverLineParams params) {
        int m = rawSurf.length;
        double[] surf = new double[m];
        if (m < 2) {
            System.arraycopy(rawSurf, 0, surf, 0, m);
            return surf;
        }
        double srcRaw = rawSurf[0];
        double outRaw = rawSurf[m - 1];
        double outH = reachedOcean ? Math.min(curve.seaLevelY() - 1.5, outRaw) : outRaw;
        double rawSpan = srcRaw - outRaw;
        double srcH = (rawSpan <= 1e-6) ? outH : Math.max(outH + 1e-4, srcRaw - params.slopeDrop());
        for (int k = 0; k < m; k++) {
            double t = NoiseUtil.saturate((rawSurf[k] - outRaw) / rawSpan);
            surf[k] = outH + (srcH - outH) * t;
        }
        return surf;
    }

    /**
     * 河面世界 Y（= 当地地表谷底）。
     *
     * <p>用 terrainEQuick 派生（与 D8 汇流场同源、确定、零侵蚀 tile）→ 保证 region 冷构建亚毫秒级。
     * 与最终地形（sampleWu）的差异仅剩侵蚀 delta（通常很小），不影响视觉嵌入感。</p>
     */
    private double groundYAt(double wx, double wz) {
        return curve.heightFromE(eSampler.eAt(wx, wz));
    }

    // ===== 距离场采样 =====

    /**
     * 点到河线距离场采样：查 3×3 邻域 region 的线段，
     * bbox 预滤后取最近命中。无河流返回 null。
     *
     * @param wx/wz 查询坐标（wu）
     */
    public RiverLineHit sample(double wx, double wz) {
        List<RiverLineHit> hits = sampleAll(wx, wz);
        if (hits.isEmpty()) return null;
        RiverLineHit best = hits.get(0);
        for (RiverLineHit h : hits) {
            if (h.distToCenter() < best.distToCenter()) best = h;
        }
        return best;
    }

    /**
     * 返回影响范围内的全部河线命中（按距离升序）。
     */
    public List<RiverLineHit> sampleAll(double wx, double wz) {
        int rx = floorDiv(wx, params.regionSize());
        int rz = floorDiv(wz, params.regionSize());
        List<RiverLineHit> hits = new ArrayList<>(4);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverLineRegion r = region(rx + dx, rz + dz);
                if (!r.hasRiver()) continue;
                RiverLineHit hit = sampleRegion(r, wx, wz);
                if (hit != null) hits.add(hit);
            }
        }
        hits.sort((a, b) -> Double.compare(a.distToCenter(), b.distToCenter()));
        return hits;
    }

    /** 单 region 折线上的最近命中（沿线插值 surface/width/depth）。 */
    private RiverLineHit sampleRegion(RiverLineRegion r, double wx, double wz) {
        double bestDist2 = Double.POSITIVE_INFINITY;
        int bestRi = -1, bestSeg = -1;
        double bestT = 0;
        for (int ri = 0; ri < r.rivers.size(); ri++) {
            RiverPolyline pl = r.rivers.get(ri);
            List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
            int segCount = line.size() - 1;
            for (int i = 0; i < segCount; i++) {
                MidpointDisplacement.Node a = line.get(i), b = line.get(i + 1);
                double abx = b.x() - a.x(), abz = b.z() - a.z();
                double len2 = abx * abx + abz * abz;
                double t = len2 < 1e-9 ? 0.0
                        : ((wx - a.x()) * abx + (wz - a.z()) * abz) / len2;
                t = NoiseUtil.clamp(t, 0.0, 1.0);
                double px = a.x() + abx * t, pz = a.z() + abz * t;
                double dx = wx - px, dz = wz - pz;
                double d2 = dx * dx + dz * dz;
                if (d2 < bestDist2) { bestDist2 = d2; bestRi = ri; bestSeg = i; bestT = t; }
            }
        }
        if (bestSeg < 0) return null;
        RiverPolyline pl = r.rivers.get(bestRi);
        List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
        double dist = Math.sqrt(bestDist2);
        double f = bestSeg + bestT;
        int i0 = (int) Math.floor(f), i1 = Math.min(i0 + 1, line.size() - 1);
        double surface = lerp(pl.surfaceY[i0], pl.surfaceY[i1], bestT);
        double width = lerp(pl.width[i0], pl.width[i1], bestT);
        double depth = lerp(pl.depth[i0], pl.depth[i1], bestT);
        return new RiverLineHit(dist, surface, width, depth, r.dischargeArea, r.outletOcean);
    }

    /**
     * 最近河线段的单位切向（流向），用于诊断时做垂直方向地形采样。
     *  无河流返回 null。
     */
    public double[] flowTangent(double wx, double wz) {
        int rx = floorDiv(wx, params.regionSize());
        int rz = floorDiv(wz, params.regionSize());
        int bestRi = -1, bestSeg = -1;
        double bestT = 0, bestD2 = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverLineRegion r = region(rx + dx, rz + dz);
                if (!r.hasRiver()) continue;
                for (int ri = 0; ri < r.rivers.size(); ri++) {
                    RiverPolyline pl = r.rivers.get(ri);
                    List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
                    int segCount = line.size() - 1;
                    for (int i = 0; i < segCount; i++) {
                        MidpointDisplacement.Node a = line.get(i), b = line.get(i + 1);
                        double abx = b.x() - a.x(), abz = b.z() - a.z();
                        double len2 = abx * abx + abz * abz;
                        double t = len2 < 1e-9 ? 0.0
                                : ((wx - a.x()) * abx + (wz - a.z()) * abz) / len2;
                        t = NoiseUtil.clamp(t, 0.0, 1.0);
                        double px = a.x() + abx * t, pz = a.z() + abz * t;
                        double ddx = wx - px, ddz = wz - pz;
                        double d2 = ddx * ddx + ddz * ddz;
                        if (d2 < bestD2) {
                            bestD2 = d2; bestRi = ri; bestSeg = i; bestT = t;
                        }
                    }
                }
            }
        }
        if (bestRi < 0 || bestSeg < 0) return null;
        RiverLineRegion r = region(rx, rz);
        RiverPolyline pl = r.rivers.get(bestRi);
        List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
        MidpointDisplacement.Node a = line.get(bestSeg), b = line.get(bestSeg + 1);
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return null;
        return new double[]{dx / len, dz / len};
    }

    /** 全部已缓存 region（诊断导出用）。 */
    public List<RiverLineRegion> cachedList() {
        return new ArrayList<>(regions.values());
    }

    private void prune() {
        if (regions.size() <= MAX_REGIONS) return;
        var it = regions.keySet().iterator();
        while (regions.size() > MAX_REGIONS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static int floorDiv(double v, int div) {
        return Math.floorDiv((int) Math.floor(v), div);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
