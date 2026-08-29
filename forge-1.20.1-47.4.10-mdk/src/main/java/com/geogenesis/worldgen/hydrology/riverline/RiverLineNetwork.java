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
                               double depth, double dischargeArea,
                               boolean reachesOcean, boolean isLake) { }

    private static final int MAX_REGIONS = 256;

    /** 汇入评分中的邻近权重（PL-RGA RIVER_JOIN_DISTANCE_WEIGHT）：越低优先，等距时就近。 */
    private static final double RIVER_JOIN_DISTANCE_WEIGHT = 1e-6;

    /**
     * 诊断计数器：防交叉检测的"段比较"总次数（性能诊断用）。
     *
     * <p>{@link #segmentCrossesAny} 对全部已有段做线性扫描，是河网构建的 O(n²) 热点。
     * 单元尺度放大后段数暴涨，该值决定是否需要空间索引（见 platewiseregions 加速结构）。
     * 生产路径开销仅一次 addAndGet。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong CROSS_COMPARISONS =
            new java.util.concurrent.atomic.AtomicLong();

    /** 防交叉段比较总次数（自上次 reset 起）。 */
    public static long crossComparisons() {
        return CROSS_COMPARISONS.get();
    }

    /** 清零防交叉计数器（探针对比前后使用）。 */
    public static void resetCrossComparisons() {
        CROSS_COMPARISONS.set(0);
    }

    private final Map<Long, RiverLineRegion> regions = new ConcurrentHashMap<>();
    /** pass-1 region 缓存（无交接、含出口种子）。双-pass 交接：pass-2 只读邻 region 的 pass-1 种子。 */
    private final Map<Long, RiverLineRegion> regionsP1 = new ConcurrentHashMap<>();
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
        regionsP1.clear();
    }

    public void clear() {
        regions.clear();
        regionsP1.clear();
    }

    /**
     * 选线场高程：把原始汇流场 e 经 {@link RiverLineParams#routingE} 山压低后用于 FlowField 追踪，
     * 使河线在"压低地形"上走（贴谷、避峰），再经水面 blend 融回真实地形（PL-RGA firstHeightField）。
     */
    private double routingE(double wx, double wz) {
        return params.routingE(eSampler.eAt(wx, wz));
    }

    public int cachedRegions() {
        return regions.size();
    }

    /** region 边长（wu）。 */
    public double regionSize() {
        return params.regionSize();
    }

    // ===== 拓扑构建 =====

    public RiverLineRegion region(int rx, int rz) {
        long key = pack(rx, rz);
        RiverLineRegion r = regions.get(key);
        if (r != null) return r;
        if (!params.crossRegion()) {
            // 关闭跨 region 连续河：退化为单 pass（旧行为，到网格边整条回滚）。
            r = regions.computeIfAbsent(key, ignored -> build(rx, rz, false, List.of()));
            prune();
            return r;
        }
        // 双-pass：本 region 的 pass-2 吸收 4 邻 region 的 pass-1 出口种子作续流源。
        // pass-1 相互独立、无递归；pass-2 仅依赖邻 region 的 pass-1 结果（固定），顺序无关。
        List<RiverLineRegion.OutletSeed> incoming = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                RiverLineRegion nb = regionPass1(rx + dRX, rz + dRZ);
                for (RiverLineRegion.OutletSeed o : nb.outlets) {
                    if (o.dRX == -dRX && o.dRZ == -dRZ) incoming.add(o);  // 邻的出口指向本 region
                }
            }
        }
        r = regions.computeIfAbsent(key, ignored -> build(rx, rz, true, incoming));
        prune();
        return r;
    }

    /** pass-1 构建（无交接，记录出口种子）。独立、无递归。 */
    private RiverLineRegion regionPass1(int rx, int rz) {
        long key = pack(rx, rz);
        RiverLineRegion r = regionsP1.get(key);
        if (r != null) return r;
        return regionsP1.computeIfAbsent(key, ignored -> build(rx, rz, false, List.of()));
    }

    /**
     * 构建 region：从地形汇流场派生河网（2026-08-28 物理范式重写）。
     *
     * <p>高位布源 → 沿 D8 下坡追踪 → 汇入已有河，生成树状水系；
     * 每条河的水面 = 当地地表谷底（Streams 范式），河深/半宽由汇流面积驱动。</p>
     *
     * <p>跨 region 连续河（{@code crossRegion}）：当 {@code handoff=true} 时，吸收
     * {@code incoming} 出口种子作强制续流源（携带上游汇流面积/层级/水面），使河流跨越
     * 640wu 瓦片缝后不中断、宽度不重置。</p>
     */
    private RiverLineRegion build(int rx, int rz, boolean handoff,
                                  List<RiverLineRegion.OutletSeed> incoming) {
        double regionSize = params.regionSize();
        double cell = params.gridCell();
        double margin = regionSize * 0.5;
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        // ★ 选线场用"山压低"后的 e（routingE），使河线贴谷避峰；水面仍锚定真实地形（groundYAt）。
        FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell, this::routingE);

        int nx = field.cols(), nz = field.rows();
        boolean[] claimed = new boolean[nx * nz];
        double[] nodeE = new double[nx * nz];          // 已接受河路径格 e（就近汇入用）
        java.util.Arrays.fill(nodeE, Double.NaN);
        double[] nodeSurf = new double[nx * nz];       // 已接受河路径格水面（交汇点继承用）
        java.util.Arrays.fill(nodeSurf, Double.NaN);
        int[] levelAt = new int[nx * nz];              // 已接受河路径格的分支层级（0 = 无河）
        List<RiverPolyline> rivers = new ArrayList<>();
        List<RiverLineRegion.LakeNode> lakes = new ArrayList<>();
        List<RiverLineRegion.OutletSeed> outlets = new ArrayList<>();
        List<int[]> allSegments = new ArrayList<>();   // 全局段集合（防交叉）
        boolean outletOcean = false;
        double maxDischarge = 0.0;

        // 候选源：e > sourceMinE 且不在 region 边界安全距内，按 e 降序（高地优先）
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < nx * nz; i++) {
            if (field.eAt(i) > params.sourceMinE()
                    && !nearRegionBorder(field, i, rx, rz, params.borderDist())) cand.add(i);
        }
        cand.sort((a, b) -> Double.compare(field.eAt(b), field.eAt(a)));
        int sourceCount = cand.size();
        int rolledBack = 0, joinedCount = 0;

        int spacing = params.sourceSpacingCells();
        int stepSize = params.traceStep();
        List<Integer> accepted = new ArrayList<>();
        int acceptedCount = 0;

        // ===== 普通源追踪（本 region 高位布源）=====
        for (int s : cand) {
            if (acceptedCount >= params.riverCount()) break;   // 河数量上限
            if (claimed[s]) continue;
            int si = s % nx, sj = s / nx;
            boolean tooClose = false;
            for (int acc : accepted) {
                if (Math.abs((acc % nx) - si) <= spacing
                        && Math.abs((acc / nx) - sj) <= spacing) { tooClose = true; break; }
            }
            if (tooClose) continue;

            TraceOutcome out = traceRiver(field, s, stepSize, claimed, nodeE,
                    allSegments, nx, nz, rx, rz, Double.NaN);
            if (out == null) { rolledBack++; continue; }
            if (out.joined) joinedCount++;

            int level = 1;
            if (out.joined) {
                int last = out.cells.get(out.cells.size() - 1);
                int tl = levelAt[last];
                if (tl > 0) level = tl + 1;
            }
            CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                    levelAt, allSegments, rivers, lakes, accepted, nx, Double.NaN, rx, rz);
            maxDischarge = Math.max(maxDischarge, c.maxDischarge());
            if (c.reachedOcean()) outletOcean = true;
            if (c.poly() != null) {
                // ★ 仅"成功成为河"的源点参与后续源点的间距过滤。
                accepted.add(s);
                acceptedCount++;
                if (out.outlet) collectOutlet(out, field, rx, rz, outlets, c.tailSurface(), level);
            }
        }

        // ===== 跨 region 续流（handoff）：吸收上游邻 region 出口种子作强制源 =====
        if (handoff) {
            for (RiverLineRegion.OutletSeed seed : incoming) {
                if (acceptedCount >= params.riverCount()) break;
                int start = field.indexOf(seed.wx, seed.wz);
                if (start < 0) continue;
                // ★ 容错：邻 region 栅格相对上游偏移最多 16wu，种子格可能恰落局部极小（无下坡→续流即死）。
                //   在 1 格窗口内改选有真实下坡的格作续流起点，避免跨缝断流。
                start = bestHandoffStart(field, start, nx, nz);
                if (claimed[start]) continue;
                int si = start % nx, sj = start / nx;
                boolean tooClose = false;
                for (int acc : accepted) {
                    if (Math.abs((acc % nx) - si) <= spacing
                            && Math.abs((acc / nx) - sj) <= spacing) { tooClose = true; break; }
                }
                if (tooClose) continue;

                TraceOutcome out = traceRiver(field, start, stepSize, claimed, nodeE,
                        allSegments, nx, nz, rx, rz, seed.accum);
                if (out == null) continue;
                if (out.joined) joinedCount++;
                // 继承上游层级；若续流汇入本 region 已有河，则为该河支流（层级+1）
                int level = seed.level;
                if (out.joined) {
                    int last = out.cells.get(out.cells.size() - 1);
                    int tl = levelAt[last];
                    if (tl > 0) level = tl + 1;
                }
                // 续流首节点水面 = 上游尾节点水面（保证跨缝水面连续，无台阶）
                CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                        levelAt, allSegments, rivers, lakes, accepted, nx, seed.surfaceY, rx, rz);
                maxDischarge = Math.max(maxDischarge, c.maxDischarge());
                if (c.reachedOcean()) outletOcean = true;
                if (c.poly() != null) {
                    accepted.add(start);
                    acceptedCount++;
                }
            }
        }

        return new RiverLineRegion(rx, rz, rivers, lakes, outlets, outletOcean, maxDischarge,
                sourceCount, rolledBack, joinedCount);
    }

    /** 提交一条已追踪河流：认领/记录/裁剪/算宽深/水面，返回最终折线（null=被阈值丢弃）。 */
    private CommitOut commitRiver(FlowField field, TraceOutcome out, int level,
                                  boolean[] claimed, double[] nodeE, double[] nodeSurf,
                                  int[] levelAt, List<int[]> allSegments,
                                  List<RiverPolyline> rivers, List<RiverLineRegion.LakeNode> lakes,
                                  List<Integer> accepted, int nx, double forcedSrcH, int rx, int rz) {
        for (int c : out.cells) {
            claimed[c] = true;
            nodeE[c] = field.eAt(c);
            if (levelAt[c] == 0) levelAt[c] = level;   // 交汇节点已属主流，勿覆盖其层级（PL-RGA 节点共享）
        }
        for (int k = 0; k < out.cells.size() - 1; k++) {
            int a = out.cells.get(k), b = out.cells.get(k + 1);
            allSegments.add(new int[]{a % nx, a / nx, b % nx, b / nx});
        }
        // 汇流面积阈值裁剪源头细流（树状稀疏）
        int start = 0;
        while (start < out.cells.size()
                && out.accum[start] <= params.riverAccumThreshold()) start++;
        if (out.cells.size() - start < params.minRiverNodes())
            return new CommitOut(null, out.reachedOcean, 0.0, null, Double.NaN);
        int m = out.cells.size() - start;
        MidpointDisplacement.Node[] nodes = new MidpointDisplacement.Node[m];
        double[] rawSurf = new double[m], wid = new double[m], dep = new double[m];
        double acc = 0.0;
        for (int k = 0; k < m; k++) {
            int idx = out.cells.get(start + k);
            double wx = field.cellCenterX(idx), wz = field.cellCenterZ(idx);
            double a = out.accum[start + k];
            nodes[k] = new MidpointDisplacement.Node(wx, wz);
            rawSurf[k] = groundYAt(wx, wz);
            wid[k] = widthFromAccum(a, params);
            dep[k] = depthFromAccum(a, wid[k], params);
            acc = Math.max(acc, a);
        }
        // 出口水面：入海→海平面附近；汇入主流→继承主流在交汇点水面（PL-RGA 节点共享）；否则贴地形
        int junctionCell = out.cells.get(out.cells.size() - 1);
        double junctionGround = groundYAt(field.cellCenterX(junctionCell), field.cellCenterZ(junctionCell));
        double outletSurf;
        if (out.reachedOcean || junctionGround < curve.seaLevelY()) {
            // 入海/海侵出口：水面由海平面决定，不再沿河床继续下探到海底。
            outletSurf = curve.seaLevelY();
        } else if (out.joined && !Double.isNaN(nodeSurf[junctionCell])) {
            outletSurf = nodeSurf[junctionCell];   // 继承主流交汇点水面 → 交汇处零台阶
        } else {
            outletSurf = junctionGround;
        }
        double[] surf = applyRiverHeightSlopeDrop(nodes, rawSurf, wid, outletSurf, params,
                Double.isNaN(forcedSrcH) ? null : forcedSrcH, out.reachedOcean);
        for (int k = 0; k < m; k++) {
            nodeSurf[out.cells.get(start + k)] = surf[k];   // 记录本河水面供后续支流继承
        }
        RiverPolyline smoothed = smoothPath(nodes, surf, wid, dep, level);
        double tailSurface = surf[m - 1];
        rivers.add(smoothed);
        RiverLineRegion.LakeNode lake = null;
        if (out.isLake) {
            int last = out.cells.get(out.cells.size() - 1);
            lake = new RiverLineRegion.LakeNode(
                    field.cellCenterX(last), field.cellCenterZ(last), tailSurface);
        }
        return new CommitOut(smoothed, out.reachedOcean, acc, lake, tailSurface);
    }

    /** 收集出口种子：本河到达网格边（缝外 margin）时，记录其尾节点供下游邻 region 续流。 */
    private void collectOutlet(TraceOutcome out, FlowField field, int rx, int rz,
                               List<RiverLineRegion.OutletSeed> outlets, double tailSurface, int level) {
        int last = out.cells.get(out.cells.size() - 1);
        double wx = field.cellCenterX(last), wz = field.cellCenterZ(last);
        double accum = out.accum[out.accum.length - 1];
        double R = params.regionSize();
        double cx = rx * R + R * 0.5, cz = rz * R + R * 0.5;
        int dRX = wx >= cx ? 1 : -1;
        int dRZ = wz >= cz ? 1 : -1;
        outlets.add(new RiverLineRegion.OutletSeed(dRX, dRZ, wx, wz, accum, tailSurface, level));
    }

    /** 提交结果：最终折线（null=丢弃）+ 是否入海 + 主河汇流面积 + 内流湖 + 尾节点水面。 */
    private record CommitOut(RiverPolyline poly, boolean reachedOcean,
                             double maxDischarge, RiverLineRegion.LakeNode lake, double tailSurface) { }

    /** 追踪结果：路径格序列 + 终止类型 + 每格汇流面积 + 是否出口（到网格边）；null = 整条回滚。 */
    private static final class TraceOutcome {
        final List<Integer> cells;
        final boolean reachedOcean;
        final boolean isLake;
        final boolean joined;     // 终止于汇入已接受河（树状汇流）
        final double[] accum;     // 每格汇流面积（wu²）；交接续流时含上游携带面积
        final boolean outlet;     // 终止于网格边（缝外 margin）→ 出口种子，交下游续流
        TraceOutcome(List<Integer> cells, boolean reachedOcean, boolean isLake,
                     boolean joined, double[] accum, boolean outlet) {
            this.cells = cells; this.reachedOcean = reachedOcean; this.isLake = isLake;
            this.joined = joined; this.accum = accum; this.outlet = outlet;
        }
    }

    /**
     * 沿下坡窗口追踪一条河，带回滚/就近汇入/湖终止（PL-RGA 对齐）。
     * null = 整条回滚：无安全下坡且越界/边界、或自环/交叉且无汇入。
     *
     * <p>{@code initialAccum} 为交接续流的携带汇流面积（NaN=普通源，用 field.accumAt）；
     * 非 NaN 时首格面积=initialAccum，后续叠加本格 field 累积，保证宽度跨缝连续。</p>
     *
     * <p>到达网格边（缝外 margin）且 {@code crossRegion} 开启：不再回滚，标记 {@code outlet}
     * 保留该河，由 build 收集出口种子交下游邻 region 续流。</p>
     */
    private TraceOutcome traceRiver(FlowField field, int start, int stepSize,
                                    boolean[] claimed, double[] nodeE,
                                    List<int[]> allSegments, int nx, int nz,
                                    int rx, int rz, double initialAccum) {
        int cur = start;
        List<Integer> path = new ArrayList<>();
        List<Double> accumList = new ArrayList<>();
        boolean[] seen = new boolean[claimed.length];
        boolean reachedOcean = false, isLake = false, joined = false, outlet = false;
        while (true) {
            if (claimed[cur]) { pushCell(path, accumList, cur, field, initialAccum); joined = true; break; }   // 汇入已接受河
            if (seen[cur]) {                                     // 自环
                if (!nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    isLake = true; pushCell(path, accumList, cur, field, initialAccum); break;
                }
                return null;
            }
            pushCell(path, accumList, cur, field, initialAccum);
            seen[cur] = true;
            if (field.eAt(cur) <= params.oceanE()) { reachedOcean = true; break; }

            int join = nearbyDownhillNode(field, cur, stepSize, nodeE,  nx, nz, allSegments);
            if (join >= 0) { pushCell(path, accumList, join, field, initialAccum); joined = true; break; }   // 就近汇入树状

            int down = downhillNeighbor(field, cur, stepSize, nx, nz);
            if (down < 0) {
                // 无下坡：下坡在邻 region（网格边 或 边界伪极小）→ 出口交下游续流；
                // 仅远离边界的真洼地才成湖（PL-RGA：tile 边界伪极小不应成湖，应流向下一瓦片）。
                if (field.touchesGridEdge(cur)
                        || nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    outlet = true; break;
                }
                isLake = true; break;                            // 远离边界的真内流洼地 → 成湖
            }
            int cri = cur % nx, crj = cur / nx, dri = down % nx, drj = down / nx;
            if (segmentCrossesAny(cri, crj, dri, drj, allSegments)) {
                if (claimed[down]) { pushCell(path, accumList, down, field, initialAccum); joined = true; break; }   // 交叉但可汇入
                return null;                                      // 交叉且无汇入 → 回滚
            }
            cur = down;
        }
        if (path.size() < params.minRiverNodes()) return null;
        double[] accum = new double[accumList.size()];
        for (int k = 0; k < accum.length; k++) accum[k] = accumList.get(k);
        return new TraceOutcome(path, reachedOcean, isLake, joined, accum, outlet);
    }

    /** 入队一个格，同步写入 path 与 accum（保证两者长度相等）。 */
    private static void pushCell(List<Integer> path, List<Double> accumList, int c,
                                 FlowField field, double initialAccum) {
        path.add(c);
        double a = Double.isNaN(initialAccum) ? field.accumAt(c)
                : (path.size() == 1 ? initialAccum : initialAccum + field.accumAt(c));
        accumList.add(a);
    }

    /**
     * step 窗口内"已存在且更低"的河节点（PL-RGA _nearbyDownhillRiverNode）：树状汇入。
     *
     * <p>★ 对齐参考：① 连接会穿过已有河段则跳过（_wouldCrossExistingSegments），
     * 否则支流跨河汇入、在交汇处产生交叉支流；② 评分用 {@code ne + 1e-6·d²}
     * （越低越好、等距就近），而非纯最低点——避免长距离跳跃汇入；
     * ③ 不排除 source 在本实现中无害（源点也已 claimed，跨河跳跃已被①拦截）。</p>
     */
    private int nearbyDownhillNode(FlowField field, int cur, int step,
                                   double[] nodeE, int nx, int nz, List<int[]> allSegments) {
        int ci = cur % nx, cj = cur / nx;
        double curE = field.eAt(cur);
        int best = -1;
        double bestScore = curE + 1e30;
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double ne = nodeE[idx];
                if (Double.isNaN(ne)) continue;
                if (ne >= curE - params.minDrop()) continue;     // 须严格更低（PL-RGA RIVER_MIN_DROP）
                // 连接会穿过已有河段 → 跳过（PL-RGA _wouldCrossExistingSegments）
                if (segmentCrossesAny(ci, cj, i, j, allSegments)) continue;
                double d2 = (i - ci) * (i - ci) + (j - cj) * (j - cj);
                double score = ne + RIVER_JOIN_DISTANCE_WEIGHT * d2;  // 越低越好，等距就近
                if (score < bestScore) { bestScore = score; best = idx; }
            }
        }
        return best;
    }

    /** 格是否距 region 边界 < borderDist（wu）：边界附近不布源/不成湖（PL-RGA border/lake_safe_mask）。 */
    private boolean nearRegionBorder(FlowField field, int idx, int rx, int rz, double borderDist) {
        double wx = field.cellCenterX(idx), wz = field.cellCenterZ(idx);
        double lo = rx * params.regionSize() + borderDist;
        double hi = rx * params.regionSize() + params.regionSize() - borderDist;
        double loZ = rz * params.regionSize() + borderDist;
        double hiZ = rz * params.regionSize() + params.regionSize() - borderDist;
        return wx < lo || wx > hi || wz < loZ || wz > hiZ;
    }

    // ===== Catmull-Rom 细分平滑 =====

    private static final double SMOOTH_SPACING = 4.0; // 目标节点间距（wu）

    /**
     * Catmull-Rom 细分平滑：把 D8 粗折线重采样为 ~SMOOTH_SPACING 间距的光滑曲线。
     * 弯道处节点密集（弧长短→分点多），直线段稀疏，雕刻不再产生矩形切口。
     * 端点复制首尾避免 Catmull-Rom 边界发散。
     */
    private RiverPolyline smoothPath(MidpointDisplacement.Node[] rawNodes,
                                     double[] rawSurf, double[] rawWid, double[] rawDep,
                                     int level) {
        int n = rawNodes.length;
        if (n < 3) {
            clampMonotonicDownstream(rawSurf, rawWid, rawDep);
            applyBankCap(rawNodes, rawSurf, rawWid, params);
            return new RiverPolyline(rawNodes, rawSurf, rawWid, rawDep, level);
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
        // 沿程单调化（下游不抬床）：见 clampMonotonicDownstream
        clampMonotonicDownstream(rs, rw, rd);
        // Catmull-Rom 重采样后节点更密，但水面是节点间线性插值：
        // 原始节点间的局部地形可能更低，必须再按逐重采样节点收回到岸线 cap。
        applyBankCap(rn, rs, rw, params);
        return new RiverPolyline(rn, rs, rw, rd, level);
    }

    /** 逐节点把水面收回到两岸原始地形 cap，并向下游传播最小值（保持不回升）。 */
    private void applyBankCap(MidpointDisplacement.Node[] nodes, double[] surf,
                              double[] widths, RiverLineParams params) {
        double seaLevel = curve.seaLevelY();
        for (int i = 0; i < surf.length; i++) {
            // ★ 重采样会新增大量中间节点；海域锁平原本只作用于 PAVA 原始节点，
            //   导致落在海域的插值节点仍沿用上游陆地水位（入海口前高一格的真因）。
            //   这里对"地形已低于海平面"的重采样节点同样锁到海平面。
            if (rawTerrainY(nodes[i]) < seaLevel) {
                surf[i] = Math.min(surf[i], seaLevel);
            } else {
                // 海域内不存在河岸上界：岸线低于海平面时不再压低水面，保持海平面。
                double cap = bankCapY(nodes, i, widths[i], params);
                if (cap >= seaLevel) {
                    surf[i] = Math.min(surf[i], cap);
                }
            }
            if (i > 0) surf[i] = Math.min(surf[i], surf[i - 1]);
        }
    }

    /**
     * 沿程单调化（下游不抬床，2026-08-29）。
     *
     * <p>节点顺序恒为 head[0] → mouth[last]：head 高/浅/窄，mouth 低/深/宽。
     * 对单条折线的水力几何数组做运行约束：</p>
     * <ul>
     *   <li>{@code surfaceY} 运行取小 —— 下游水面不回升；</li>
     *   <li>{@code width}    运行取大 —— 下游河宽不减；</li>
     *   <li>{@code depth}    运行取大 —— 下游河深不减。</li>
     * </ul>
     *
     * <p>根因（探针 {@code RiverLineMicroUphillProbe}）：雕刻器在 {@code blendDist=100wu}
     * 内对邻近段做反距离平方混合 surfaceY/width/depth。同一条河自身在河曲/自身相邻段处
     * 被重复采样（hitCount>1），混合进更高 surfaceY、更小 depth 的"上游/侧向"段，使
     * 局部河床 {@code surfaceY − depth·profile} 抬升，产生方块级爬坡（占可见爬坡约 67%）。
     * 单条折线内先强制水力几何下游单调，从根消除此类同河内部爬坡；交汇处跨河段混合的
     * 残留非单调由雕刻器侧另行处理，不在此处。</p>
     */
    private static void clampMonotonicDownstream(double[] surf, double[] wid, double[] dep) {
        int m = surf.length;
        if (m <= 1) return;
        for (int i = 1; i < m; i++) {
            if (surf[i] > surf[i - 1]) surf[i] = surf[i - 1];
            if (wid[i] < wid[i - 1]) wid[i] = wid[i - 1];
            if (dep[i] < dep[i - 1]) dep[i] = dep[i - 1];
        }
    }

    // ===== 河网生成辅助（PL-RGA 对齐）=====

    /**
     * 下坡邻域：step×step 窗口内取"单位距离落差最大"的格（标准 D8 最陡下降定义）。
     *
     * <p>★ 2026-08-29 实机修复"河沿等高线横走"：旧评分 {@code e + 1e-5·dist²}
     * 的距离惩罚（≤4e-5）远小于 e 的横向差异量级，实际退化为"窗口内绝对最低点"——
     * 等高线方向只要有缓坡平台，河就横向漂移而非直下坡（用户实机红圈案例）。
     * 改为 slope = (curE − e)/dist 单位落差率评分：只有横向落差率真正更大时才横走，
     * 直下坡更陡时必然直下（水的物理走向）。</p>
     */
    private int downhillNeighbor(FlowField field, int cur, int step, int nx, int nz) {
        int ci = cur % nx, cj = cur / nx;
        double curE = field.eAt(cur);
        int best = -1;
        double bestSlope = 0.0; // 经 minDrop 门控的候选 slope 恒正 → 等价"严格更低"
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double e = field.eAt(idx);
                if (e >= curE - params.minDrop()) continue;
                double di = i - ci, dj = j - cj;
                double slope = (curE - e) / Math.sqrt(di * di + dj * dj);
                if (slope > bestSlope) { bestSlope = slope; best = idx; }
            }
        }
        return best;
    }

    /**
     * 交接种子起点容错：邻 region 栅格相对上游偏移最多 16wu，种子格可能恰落局部极小
     * （D8 无下坡或坡低于 minDrop → 续流即死）。在 1 格窗口内改选有真实下坡的格作续流起点，
     * 保证跨缝连续。
     */
    private int bestHandoffStart(FlowField field, int start, int nx, int nz) {
        if (downhillNeighbor(field, start, 1, nx, nz) >= 0) return start;
        int ci = start % nx, cj = start / nx;
        int best = start; double bestE = field.eAt(start);
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                int idx = j * nx + i;
                if (downhillNeighbor(field, idx, 1, nx, nz) >= 0 && field.eAt(idx) < bestE) {
                    best = idx; bestE = field.eAt(idx);
                }
            }
        }
        return best;
    }

    /** 河段 (ax,ay)-(bx,by) 是否与已有段集合任一相交（_segmentsCross）。 */
    private static boolean segmentCrossesAny(int ax, int ay, int bx, int by, List<int[]> segs) {
        // 诊断：按"扫描规模"计数量化 O(n²) 热点（实际比较数因短路更少，
        // 但空间索引要消除的正是这个线性扫描规模）。
        CROSS_COMPARISONS.addAndGet(segs.size());
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

    /**
     * 地形拟合的单调水面：对 {@code terrainY - surfaceSink} 做非递增单调回归（PAVA）。
     *
     * <p>相比运行最小值，PAVA 不会被单个小凹坑永久拖低；它在保证下游绝不回升的前提下，
     * 以最小平方误差贴合整条沿河地形。小凸起会被压平并切穿，小凹坑会形成有水的深槽，
     * 整体仍紧贴地势。出口和跨 region 源端用高权重锚定，维持汇口/瓦片缝连续。</p>
     */
    private double[] applyRiverHeightSlopeDrop(MidpointDisplacement.Node[] nodes,
                                               double[] rawSurf, double[] widths,
                                               double outletSurf, RiverLineParams params,
                                               Double forcedSrcH, boolean reachedOcean) {
        int m = rawSurf.length;
        if (m == 0) return new double[0];
        double sink = Math.max(0.0, params.surfaceSink());
        double seaLevel = curve.seaLevelY();
        double[] target = new double[m];
        double[] weight = new double[m];
        Arrays.fill(weight, 1.0);
        for (int k = 0; k < m; k++) {
            double bankCap = bankCapY(nodes, k, widths[k], params);
            if (rawSurf[k] < seaLevel || bankCap < seaLevel) {
                // 已进入海域（河心或河缘地形低于海平面）就没有"河岸"：
                // 水面由海平面决定，否则会被海底地形压低成"河流继续下探"。
                target[k] = seaLevel;
                continue;
            }
            // 陆上河面也不得低于海平面：surfaceSink 会把紧邻海平面的河段压到海平面以下，
            // 再经单调传播拉低整个入海口。
            target[k] = Math.max(seaLevel, Math.min(rawSurf[k] - sink, bankCap));
        }

        double outlet = Math.min(outletSurf, target[m - 1]);
        target[m - 1] = outlet;
        weight[m - 1] = 1e9;
        if (forcedSrcH != null) {
            // 跨 region 续流只能继承连续水位，不能突破本 region 入口两岸的原始地形上界。
            target[0] = Math.min(target[0], Math.max(outlet, forcedSrcH));
            weight[0] = 1e9;
        }

        double[] surf = isotonicNonIncreasing(target, weight);
        // 不再用 clamp 的"下界"把水面抬回 outlet/sourceCap：那会把水面重新抬到
        // 岸线 cap 之上（PAVA 块均值 + 强制下界 = 水面盖过河岸的根因）。
        // 只保留：不超过本节点两岸原始地形、且沿程不回升。
        for (int k = 0; k < m; k++) {
            surf[k] = Math.min(surf[k], target[k]);
            if (k > 0) surf[k] = Math.min(surf[k], surf[k - 1]);
        }
        if (forcedSrcH != null) surf[0] = Math.min(surf[0], Math.max(outlet, Math.min(forcedSrcH, target[0])));
        surf[m - 1] = Math.min(surf[m - 1], Math.max(outlet, target[m - 1]));
        return surf;
    }

    /**
     * 采样河线左右岸的多点原始地形，取最小值作为该节点水面硬上界。
     *
     * <p>只采单点会在地形起伏处漏判：河岸并非单调升高，横向地形可能比单点更低。
     * 这里沿法向在近岸与远岸各取一点（左右共 4 点），取最低值并留安全余量，
     * 保证水面始终嵌在河谷内，不会出现"一侧河岸被水盖过"。</p>
     */
    private double bankCapY(MidpointDisplacement.Node[] nodes, int i, double width,
                            RiverLineParams params) {
        int prev = Math.max(0, i - 1), next = Math.min(nodes.length - 1, i + 1);
        double tx = nodes[next].x() - nodes[prev].x();
        double tz = nodes[next].z() - nodes[prev].z();
        double len = Math.hypot(tx, tz);
        if (len < 1e-6) return rawTerrainY(nodes[i]);
        double nx = -tz / len, nz = tx / len;
        // ★ 必须采"河缘"(dist≈width)而不是远岸：水从河缘漫出，远岸再高也挡不住。
        double near = Math.max(1.0, width);
        double far = Math.max(2.0, width * 1.35);
        double lowest = Double.POSITIVE_INFINITY;
        for (double offset : new double[]{near, far}) {
            lowest = Math.min(lowest, rawTerrainY(nodes[i].x() + nx * offset,
                    nodes[i].z() + nz * offset));
            lowest = Math.min(lowest, rawTerrainY(nodes[i].x() - nx * offset,
                    nodes[i].z() - nz * offset));
        }
        return lowest - 0.25;
    }

    private double rawTerrainY(MidpointDisplacement.Node node) {
        return groundYAt(node.x(), node.z());
    }

    private double rawTerrainY(double x, double z) {
        return terrainY != null ? terrainY.yAt(x, z) : curve.heightFromE(eSampler.eAt(x, z));
    }

    /** 加权 PAVA：返回与 target 平方误差最小的非递增序列。 */
    private static double[] isotonicNonIncreasing(double[] target, double[] weight) {
        int n = target.length;
        double[] mean = new double[n], sumW = new double[n];
        int[] start = new int[n], end = new int[n];
        int blocks = 0;
        for (int i = 0; i < n; i++) {
            mean[blocks] = target[i]; sumW[blocks] = weight[i];
            start[blocks] = i; end[blocks] = i; blocks++;
            while (blocks >= 2 && mean[blocks - 2] < mean[blocks - 1]) {
                int a = blocks - 2, b = blocks - 1;
                double w = sumW[a] + sumW[b];
                mean[a] = (mean[a] * sumW[a] + mean[b] * sumW[b]) / w;
                sumW[a] = w; end[a] = end[b]; blocks--;
            }
        }
        double[] out = new double[n];
        for (int b = 0; b < blocks; b++) {
            Arrays.fill(out, start[b], end[b] + 1, mean[b]);
        }
        return out;
    }

    /**
     * 河面世界 Y（= 当地地表谷底）。
     *
     * <p>用 terrainEQuick 派生（与 D8 汇流场同源、确定、零侵蚀 tile）→ 保证 region 冷构建亚毫秒级。
     * 与最终地形（sampleWu）的差异仅剩侵蚀 delta（通常很小），不影响视觉嵌入感。</p>
     */
    private double groundYAt(double wx, double wz) {
        return terrainY != null ? terrainY.yAt(wx, wz)
                : curve.heightFromE(eSampler.eAt(wx, wz));
    }

    // ===== 下游水力几何（2026-08-29 宽深改革）=====

    /**
     * 河宽：W = minWidth × (A / A_ref)^bW，钳制到 maxWidth。
     *
     * <p>依据 Leopold-Maddock 下游水力几何 W ∝ Q^b（b≈0.5）、Q ∝ 汇流面积 A，
     * 本项目取 bW=0.42 以压缩超大汇流的宽度爆炸。</p>
     *
     * <p><b>★ 与成河门槛解耦</b>：宽度原点为独立的 {@link RiverLineParams#widthAreaRef()}，
     * 而非 {@link RiverLineParams#riverAccumThreshold()}。旧实现把两者绑死
     * （t = (logA − log A0)/log range，A0 即门槛），导致调河网密度时全河宽度整体平移。
     * 幂律也不会像 smoothstep 那样在中段饱和，沿程保持连续渐变。</p>
     */
    private static double widthFromAccum(double accum, RiverLineParams p) {
        double ratio = Math.max(1.0, accum) / Math.max(1.0, p.widthAreaRef());
        return Math.min(p.maxWidth(), p.minWidth() * Math.pow(ratio, p.widthExp()));
    }

    /**
     * 河深：D = minDepth × (A / A_ref)^bD，钳制到 maxDepth，再受宽深比护栏 D ≤ 0.9W 约束。
     *
     * <p>bD(0.40) &lt; bW(0.42) 使宽深比 W/D ∝ A^0.02 随下游缓慢增大（下游更宽浅，符合真实河流）。
     * 护栏防止窄而极深的非物理断面。</p>
     */
    private static double depthFromAccum(double accum, double width, RiverLineParams p) {
        double ratio = Math.max(1.0, accum) / Math.max(1.0, p.widthAreaRef());
        double d = Math.min(p.maxDepth(), p.minDepth() * Math.pow(ratio, p.depthExp()));
        return Math.min(d, p.maxDepthRatio() * width);
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
        List<RiverLineHit> hits = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverLineRegion r = region(rx + dx, rz + dz);
                if (!r.hasWater()) continue;
                hits.addAll(sampleRegion(r, wx, wz));
            }
        }
        hits.sort((a, b) -> Double.compare(a.distToCenter(), b.distToCenter()));
        return hits;
    }

    /**
     * 单 region 上 valley 半径内"每段独立命中"列表（供雕刻器 smooth-min 合并，
     * 根治属主在段间切换产生的放射折痕）。仅保留 dist ≤ valleyReach 的段——
     * 其 carve 才可能非零，远处段不影响 smin（carve=original）。
     */
    private List<RiverLineHit> sampleRegion(RiverLineRegion r, double wx, double wz) {
        List<RiverLineHit> out = new ArrayList<>();
        double bankFactor = params.bankFactor();
        double bestRiverDist = Double.POSITIVE_INFINITY;   // 最近河段距离（含 valley 外的）
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
                double dist = Math.sqrt(dx * dx + dz * dz);
                bestRiverDist = Math.min(bestRiverDist, dist);
                double f = i + t;
                int i0 = (int) Math.floor(f), i1 = Math.min(i0 + 1, line.size() - 1);
                double surface = lerp(pl.surfaceY[i0], pl.surfaceY[i1], t);
                double width = lerp(pl.width[i0], pl.width[i1], t);
                double depth = lerp(pl.depth[i0], pl.depth[i1], t);
                double valleyReach = Math.max(width * (1.0 + bankFactor), width * 3.0);
                if (dist <= valleyReach) {
                    out.add(new RiverLineHit(dist, surface, width, depth,
                            r.dischargeArea, r.outletOcean, false));
                }
            }
        }
        // 湖泊：影响范围内、且比最近河段更近才纳入（保持旧"湖/河竞争"语义；远处湖 carve≈original 无副作用）
        if (!r.lakes.isEmpty()) {
            double lakeDist2 = Double.POSITIVE_INFINITY;
            double lakeH = 0.0;
            for (RiverLineRegion.LakeNode ln : r.lakes) {
                double d2 = (wx - ln.x) * (wx - ln.x) + (wz - ln.z) * (wz - ln.z);
                if (d2 < lakeDist2) { lakeDist2 = d2; lakeH = ln.height; }
            }
            double lakeDist = Math.sqrt(lakeDist2);
            if (lakeDist <= params.lakeRadius() + params.lakeFadeDist()
                    && lakeDist <= bestRiverDist) {
                out.add(new RiverLineHit(lakeDist, lakeH, params.lakeRadius(),
                        params.minDepth(), r.dischargeArea, false, true));
            }
        }
        return out;
    }

    /**
     * 最近河线段的单位切向（流向），用于诊断时做垂直方向地形采样。
     *  无河流返回 null。
     */
    public double[] flowTangent(double wx, double wz) {
        int rx = floorDiv(wx, params.regionSize());
        int rz = floorDiv(wz, params.regionSize());
        RiverLineRegion bestRegion = null;
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
                            bestD2 = d2; bestRegion = r; bestRi = ri; bestSeg = i; bestT = t;
                        }
                    }
                }
            }
        }
        if (bestRegion == null || bestRi < 0 || bestSeg < 0) return null;
        RiverPolyline pl = bestRegion.rivers.get(bestRi);
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
        if (regions.size() > MAX_REGIONS) {
            var it = regions.keySet().iterator();
            while (regions.size() > MAX_REGIONS && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        if (regionsP1.size() > MAX_REGIONS) {
            var it = regionsP1.keySet().iterator();
            while (regionsP1.size() > MAX_REGIONS && it.hasNext()) {
                it.next();
                it.remove();
            }
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
