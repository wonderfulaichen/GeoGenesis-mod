package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★ 2026-10-12【跨 tract 缝共享湖域：盆地级 canonical owner（受控实验原型；默认关）】★
 *
 * <p>实测（{@code scratch/FINDINGS.md}）：{@code (-513,-250)} 在邻 tract 窗口里 bed 逐位相同、
 * 只有湖布尔/水位不同（176.89 vs 175.31）⇒ 缝上湖墙；根因 = 各 tract 用各自窗口把同一盆地截断在
 * 不同位置、各算一个水位。</p>
 *
 * <p><b>归属 = 盆地级 canonical（只对粗 bbox 判定）</b>：对每个【闭合 + 干环 + 有限 + 单平面】的粗盆地，
 * 取 {@code argmin(bed)}（tie: worldX,worldZ）为 anchor；canonical key = 「bbox 跨过的缝线」(轴优先 V，
 * 再 H；boundary 升序) 中<b>能让固定对称窗完整容纳 bbox+干环</b>的最小者，{@code alongTile} 由 anchor 导出。
 * 只有 canonical key 能控制该盆地 ⇒ 受压范围内两侧同裁决。</p>
 *
 * <p><b>受控体 = 选中细盆地的全部湖格 + 其外干环</b>（不按粗足迹裁剪）：粗层只提供候选联系
 * （谁跨缝 / canonical 归谁），绝不用它 clip 细层湖格。干环只加在本平面外侧，别湖格绝不覆盖。</p>
 *
 * <p><b>⚠ NOT VERIFIED</b>：canonical 只对【粗 bbox】判定，<b>未</b>逐格复核细盆地 canonical（细层分裂/
 * 合并时可能与本 key 不同）；一个 key 名下多个无关跨缝盆地时只受控首个（余者如实报告）。只修湖面，不修河断点。</p>
 *
 * <p><b>查询</b>：枚举所有<b>包含该点的</b>固定缝窗（区间重叠；≤8~12），任一窗受控即采用；同一盆地被
 * 多窗受控且平面不一致 ⇒ {@code COMPETING}。窗半边长 base={@code REGION/2+HALO}(352)，仅当 bbox
 * 装不下才放大，硬上限 {@code min(tract,512)}(1024²)。</p>
 */
public final class SharedLakeDomain {

    private SharedLakeDomain() { }

    /** 单平面容差（块）：盆地内水面极差超过它 ⇒ 判"不是同一个面" ⇒ 拒绝。 */
    public static final double PLANE_TOL = 0.5;
    /** 共享窗半边长硬上限（块）—— 无论盆地多大，窗不会无限增长。 */
    public static final int ABS_MAX_HALF = 512;

    /** 裁决状态（如实区分"受控 / 拒绝 / 竞争"）。 */
    public enum Status {
        NONE,               // 不落在任何候选缝窗内
        PENDING,            // 粗筛完成，实际窗口尚未被查询（未触发侵蚀细解）
        NOT_ELIGIBLE,       // 在窗内，但不在受控盆地足迹（含干环）里
        NO_CROSSING,        // 窗内没有跨缝盆地 ⇒ 粗筛选即止（永不细解）
        CONTROLLED,         // 闭合 + 干环 + 单平面 + 有限 + canonical ⇒ 受控
        REFUSED_TRUNCATED,  // 盆地触窗框（集水截断）⇒ 拒绝
        REFUSED_TOO_BIG,    // bbox+干环装不下 maxHalf 窗 ⇒ 拒绝（不移墙）
        REFUSED_LEAK,       // 环上有水 / 多平面 ⇒ 拒绝
        BASIN_LOST,         // 粗候选在该窗侵蚀地形下没有对应湖 ⇒ 记录（形态演化，非失败）
        COMPETING           // 同一盆地被多窗受控且平面不一致 ⇒ 未决
    }

    /** 缝键：{@code axis 0} = 竖缝（{@code x = boundary}），{@code 1} = 横缝（{@code z = boundary}）。 */
    public record Key(int axis, int boundary, int alongTile) implements Comparable<Key> {
        public String label() {
            return (axis == 0 ? "V@" : "H@") + boundary + "/t" + alongTile;
        }
        @Override public int compareTo(Key o) {
            if (axis != o.axis) return Integer.compare(axis, o.axis);
            if (boundary != o.boundary) return Integer.compare(boundary, o.boundary);
            return Integer.compare(alongTile, o.alongTile);
        }
    }

    /**
     * 裁决结果（探针直接读）。
     *
     * @param controlled 该点是否由本域受控（{@code false} ⇒ 本区细窗说了算）
     * @param lake       受控时的湖布尔（{@code controlled && !lake} = 明确判"此处无水"）
     * @param surface    受控且有水时的共享水位（否则 NaN）
     * @param basinId    裁决该点的<b>粗层盆地 id</b>（-1 = 不在任何粗盆地足迹内）
     */
    public record Decision(boolean controlled, boolean lake, double surface, Key key,
                           int basinId, Status status) {
        public static final Decision NONE =
                new Decision(false, false, Double.NaN, null, -1, Status.NONE);
    }

    /** 固定缝窗几何（由键 + 半边长导出）。 */
    public record Geom(int centerX, int centerZ, int originX, int originZ, int halfSpan,
                       int step, int n, int seamCell) { }

    /** 细解所需"窗外权威"（块坐标 → 水位；NaN = 查不到 ⇒ 管线退回本层地形）。 */
    public interface OwnerLevel { double level(int blockX, int blockZ); }

    /** 构建上下文（与生产同源参数；由 {@link ModelHydrologyProvider} 注入）。 */
    public record Ctx(CellGenerator gen, TerrainParams tp, HierarchicalHydrology.Params p,
                      int step, LakeWaterBalance.Climate climate, OwnerLevel owner) { }

    /** 受控平面（细层一个连通湖体；{@code level} = 实测均值，{@code spread} = 极差）。 */
    public record Plane(int id, int fineBasinId, double level, int cells, double spread,
                        int minI, int minJ, int maxI, int maxJ) { }

    // ======================================================================
    // 几何 / 半边长 / 键枚举（纯世界坐标函数；与提问 region 无关）
    // ======================================================================

    /** 基准窗半边长（生产窗半径）。 */
    public static int baseHalf(HierarchicalHydrology.Params p) { return p.fineRadius(); }

    /** 硬上限半边长（{: 盆地更大 ⇒ 拒绝). */
    public static int maxHalf(HierarchicalHydrology.Params p) {
        return Math.min(p.tract(), ABS_MAX_HALF);
    }

    /** 键 + 半边长 → 固定缝窗几何（窗中心 = 缝线 × 沿缝 tract 中心）。 */
    public static Geom geom(Key k, HierarchicalHydrology.Params p, int step, int half) {
        int along = k.alongTile() * p.tract() + p.tract() / 2;
        int cx = k.axis() == 0 ? k.boundary() : along;
        int cz = k.axis() == 0 ? along : k.boundary();
        return new Geom(cx, cz, cx - half, cz - half, half, step, 2 * half / step + 1, half / step);
    }

    /** 默认（基准）半边长几何。 */
    public static Geom geom(Key k, HierarchicalHydrology.Params p, int step) {
        return geom(k, p, step, baseHalf(p));
    }

    /** 方块盒所需最小半边长（用硬上限半边长评估；null = 装不下）。 */
    static Integer neededHalf(int minX, int maxX, int minZ, int maxZ,
                              HierarchicalHydrology.Params p, int pad) {
        int t = p.tract();
        Integer best = null;
        for (int b = Math.floorDiv(minX, t) * t; b <= maxX; b += t) {
            if (minX < b && maxX > b) best = min(best, halfFor(0, b, Math.floorDiv(minZ, t),
                    minX, maxX, minZ, maxZ, t, pad));
        }
        for (int b = Math.floorDiv(minZ, t) * t; b <= maxZ; b += t) {
            if (minZ < b && maxZ > b) best = min(best, halfFor(1, b, Math.floorDiv(minX, t),
                    minX, maxX, minZ, maxZ, t, pad));
        }
        return best;
    }

    private static Integer min(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    /** 某 (axis, boundary, alongTile) 容纳 bbox+pad 所需半边长。 */
    private static Integer halfFor(int axis, int boundary, int alongTile,
                                   int minX, int maxX, int minZ, int maxZ, int t, int pad) {
        int cx = axis == 0 ? boundary : alongTile * t + t / 2;
        int cz = axis == 0 ? alongTile * t + t / 2 : boundary;
        int hx = Math.max(Math.abs(minX - cx), Math.abs(maxX - cx)) + pad;
        int hz = Math.max(Math.abs(minZ - cz), Math.abs(maxZ - cz)) + pad;
        return Math.max(hx, hz);
    }

    /**
     * 盆地 bbox + anchor 的 <b>canonical key</b>（轴优先 V，再 H；boundary 升序），
     * 只取能让 maxHalf 窗容纳 bbox+pad 者；装不下 ⇒ null（调用方记 {@code REFUSED_TOO_BIG}）。
     */
    public static Key canonical(int minX, int maxX, int minZ, int maxZ,
                                int anchorX, int anchorZ, HierarchicalHydrology.Params p, int pad) {
        int t = p.tract(), mh = maxHalf(p);
        for (int b = Math.floorDiv(minX, t) * t; b <= maxX; b += t) {
            if (minX < b && maxX > b) {
                Integer h = halfFor(0, b, Math.floorDiv(anchorZ, t), minX, maxX, minZ, maxZ, t, pad);
                if (h != null && h <= mh) return new Key(0, b, Math.floorDiv(anchorZ, t));
            }
        }
        for (int b = Math.floorDiv(minZ, t) * t; b <= maxZ; b += t) {
            if (minZ < b && maxZ > b) {
                Integer h = halfFor(1, b, Math.floorDiv(anchorX, t), minX, maxX, minZ, maxZ, t, pad);
                if (h != null && h <= mh) return new Key(1, b, Math.floorDiv(anchorX, t));
            }
        }
        return null;
    }

    /** 点的<b>所有</b>候选缝键（区间重叠；任一固定窗包含该点即算）。 */
    public static List<Key> candidates(int blockX, int blockZ, HierarchicalHydrology.Params p) {
        return keysForBox(blockX, blockZ, blockX, blockZ, p);
    }

    /** 方块盒相交的全部候选缝键（稳定顺序 + 去重；四角并集）。 */
    public static List<Key> keysForBox(int x0, int z0, int x1, int z1,
                                       HierarchicalHydrology.Params p) {
        TreeSet<Key> out = new TreeSet<>();
        int t = p.tract(), h = maxHalf(p);
        for (int x : new int[]{x0, x1}) {
            for (int z : new int[]{z0, z1}) {
                addV(out, x, z, t, h);
                addH(out, z, x, t, h);
            }
        }
        return new ArrayList<>(out);
    }

    private static void addV(TreeSet<Key> out, int x, int z, int t, int h) {
        for (int b = Math.floorDiv(x - h, t) * t; b <= x + h; b += t) {
            if (Math.abs(x - b) > h) continue;
            for (int at = Math.floorDiv(z - h - t / 2, t); at <= Math.floorDiv(z + h - t / 2, t); at++) {
                if (Math.abs(z - (at * t + t / 2)) <= h) out.add(new Key(0, b, at));
            }
        }
    }

    private static void addH(TreeSet<Key> out, int z, int x, int t, int h) {
        for (int b = Math.floorDiv(z - h, t) * t; b <= z + h; b += t) {
            if (Math.abs(z - b) > h) continue;
            for (int at = Math.floorDiv(x - h - t / 2, t); at <= Math.floorDiv(x + h - t / 2, t); at++) {
                if (Math.abs(x - (at * t + t / 2)) <= h) out.add(new Key(1, b, at));
            }
        }
    }

    /** bbox(块) 是否落在窗内（含 pad 块）。 */
    static boolean insideWindow(Geom g, int minX, int maxX, int minZ, int maxZ, int pad) {
        return minX - pad >= g.originX() && maxX + pad <= g.originX() + g.n() * g.step()
                && minZ - pad >= g.originZ() && maxZ + pad <= g.originZ() + g.n() * g.step();
    }

    // ======================================================================
    // 构建：粗（maxHalf 窗）→ canonical → 细（所需半边长）→ 整盆地受控
    // ======================================================================

    /**
     * 构建一个缝窗的完整产物（纯函数：同 ctx + 同键 ⇒ 同结果）。
     *
     * <p><b>半边长按固定顺序试</b>：{@code base(352)} → {@code maxHalf(min(tract,512))}，取第一个
     * "canonical==本键 且 bbox+干环 装得下" 的粗盆地。顺序固定 ⇒ 确定性；两次试解 << 一次生产细解。
     * （粗解对窗敏感 ⇒ 不能一次就用 maxHalf：那会改变盆地本身。）</p>
     */
    public static Domain build(Ctx c, Key key) {
        return prepare(c, key).resolved();
    }

    static Domain prepare(Ctx c, Key key) {
        HierarchicalHydrology.Diag.SHARED_DOMAINS.incrementAndGet();
        int step = c.step(), pad = step;
        int[] halves = baseHalf(c.p()) == maxHalf(c.p())
                ? new int[]{baseHalf(c.p())} : new int[]{baseHalf(c.p()), maxHalf(c.p())};
        long coarseMs = 0;
        Map<Integer, Status> aggStatus = new TreeMap<>();
        for (int half : halves) {
            Geom gc = geom(key, c.p(), step, half);
            long t0 = System.nanoTime();
            HydrologyPipeline.Result coarse = coarseSolve(c, gc);
            coarseMs = (System.nanoTime() - t0) / 1_000_000L;
            HierarchicalHydrology.Diag.SHARED_COARSE_SCREENS.incrementAndGet();
            int[] cbid = coarse.lakeResult() == null ? null : coarse.lakeResult().basinId();
            if (cbid == null) continue;
            Map<Integer, HierarchicalHydrology.BasinFootprint> fps =
                    HierarchicalHydrology.basinFootprints(coarse);
            Map<Integer, Status> basinStatus = new TreeMap<>();
            int chosen = -1, eligible = 0;
            for (HierarchicalHydrology.BasinFootprint fp : fps.values()) {
                if (!crosses(fp, key, gc)) continue;
                int[] bb = blockBbox(fp, gc);
                int[] an = anchor(coarse, fp, gc);
                Key can = canonical(bb[0], bb[1], bb[2], bb[3], an[0], an[1], c.p(), pad);
                if (can == null) {
                    basinStatus.put(fp.basinId(), Status.REFUSED_TOO_BIG);
                    HierarchicalHydrology.Diag.SHARED_REFUSED_TOO_BIG.incrementAndGet();
                    continue;
                }
                if (!can.equals(key)) continue;
                if (fp.frameTouch()) {
                    basinStatus.put(fp.basinId(), Status.REFUSED_TRUNCATED);
                    HierarchicalHydrology.Diag.SHARED_REFUSED_TRUNCATED.incrementAndGet();
                    continue;
                }
                if (!insideWindow(gc, bb[0], bb[1], bb[2], bb[3], pad)) continue;
                eligible++;
                if (chosen < 0) chosen = fp.basinId();
            }
            // ★ 本 key 名下若有【多个无关跨缝盆地】，只取第一个受控；其余如实报告（不在本次范围）。
            if (eligible > 1) {
                HierarchicalHydrology.Diag.layer("sharedLake", "extraEligibleBasins", eligible - 1);
                HierarchicalHydrology.Diag.sharedSample(String.format(
                        "MULTI %s eligible=%d ⇒ 只受控首个（chosen=%d），余 %d 个如实报告未受控",
                        key.label(), eligible, chosen, eligible - 1));
            }
            aggStatus.putAll(basinStatus);
            if (chosen < 0) continue;                       // 本半边长无可用 canonical 盆地 ⇒ 试更大
            HierarchicalHydrology.BasinFootprint fp = fps.get(chosen);
            int[] bb = blockBbox(fp, gc);
            Integer need = neededHalf(bb[0], bb[1], bb[2], bb[3], c.p(), pad);
            if (need == null || need > maxHalf(c.p())) {
                basinStatus.put(chosen, Status.REFUSED_TOO_BIG);
                aggStatus.putAll(basinStatus);
                continue;
            }
            int h = Math.min(maxHalf(c.p()), align(Math.max(baseHalf(c.p()), need), step));
            Geom gf = geom(key, c.p(), step, h);
            final int selected = chosen;
            final long ms = coarseMs;
            Domain pending = empty(key, gf, Status.PENDING, null, Map.of(), Map.of(), ms);
            pending.solver = () -> fineStage(c, key, gc, gf, cbid, fp, selected, basinStatus, ms);
            return pending;
        }
        // 两档都无可用 ⇒ 如实回传【拒绝原因】（不吞成 NO_CROSSING），供 decide 给出明确 reason。
        Status st = aggStatus.isEmpty() ? Status.NO_CROSSING : domainStatus(aggStatus);
        Geom g = geom(key, c.p(), step, baseHalf(c.p()));
        return empty(key, g, st, null, new TreeMap<>(), aggStatus, coarseMs);
    }

    /** 细层：解析已准备好的所选粗盆地，整盆地校验并标记受控。 */
    private static Domain fineStage(Ctx c, Key key, Geom gc, Geom gf, int[] cbid,
                                    HierarchicalHydrology.BasinFootprint fp, int chosen,
                                    Map<Integer, Status> basinStatus, long coarseMs) {
        int pad = c.step(), h = gf.halfSpan();
        long t1 = System.nanoTime();
        HydrologyPipeline.Result fine = fineSolve(c, gf);
        long fineMs = (System.nanoTime() - t1) / 1_000_000L;
        HierarchicalHydrology.Diag.SHARED_FINE_SOLVES.incrementAndGet();
        HierarchicalHydrology.Diag.sharedTiming(coarseMs, fineMs);
        PlaneSet ps = candidatePlanes(fine, key, gf, c.p(), pad);
        int rep = pickRepresentative(ps, cbid, fp, gc, gf);
        Map<Integer, Integer> basinPlane = new TreeMap<>();
        if (rep >= 0) {
            basinPlane.put(chosen, rep);
            HierarchicalHydrology.Diag.SHARED_CONTROLLED_BASINS.incrementAndGet();
        } else {
            basinStatus.put(chosen, Status.BASIN_LOST);
            HierarchicalHydrology.Diag.SHARED_BASIN_LOST.incrementAndGet();
        }
        markSelected(fine, ps, basinPlane, chosen, gf);
        int ok = basinPlane.isEmpty() ? 0 : 1;
        Status st = ok > 0 ? Status.CONTROLLED : domainStatus(basinStatus);
        HierarchicalHydrology.Diag.layer("sharedLake", "controlledBasins", ok);
        HierarchicalHydrology.Diag.sharedSample(String.format(
                "DOMAIN %s %s chosen=%d controlled=%d half=%d coarse=%dms fine=%dms",
                key.label(), st, chosen, ok, h, coarseMs, fineMs));
        // ★ coarseBasinId 必须【投影到细层网格】（gc→gf 坐标投影一次）；否则 decide/cellAt
        //   用细索引去读粗尺寸数组 ⇒ OOB/错 id（两者半边长不同时尤为明显）。不强行让网格形状一致。
        int[] cbidFine = projectBasinId(cbid, gc, gf);
        Domain domain = new Domain(key, gf, st, cbidFine, ps.fineBasinId, fine.lake(), ps.planeOfCell,
                ps.planes, basinPlane, basinStatus, coarseMs, fineMs, fine.nx() * fine.nx(), ok);
        if (Boolean.getBoolean("geogenesis.drainageAudit")) domain.auditPipe = fine;
        return domain;
    }

    /** 半边长向上对齐到 step 的倍数（两窗原点同余 ⇒ 投影精确）。 */
    static int align(int v, int step) {
        return step <= 0 ? v : ((v + step - 1) / step) * step;
    }

    /**
     * 把粗网格的 {@code basinId} 投影到细网格（按【块坐标】查最近粗格；越出粗窗 ⇒ -1）。
     * 两窗中心相同、步长相同 ⇒ 投影是纯坐标映射，不要求两窗形状一致。
     */
    static int[] projectBasinId(int[] cbid, Geom gc, Geom gf) {
        int nf = gf.n(), cells = nf * nf;
        int[] out = new int[cells];
        java.util.Arrays.fill(out, -1);
        if (cbid == null) return out;
        int nc = gc.n();
        for (int k = 0; k < cells; k++) {
            int bx = gf.originX() + (k / nf) * gf.step();
            int bz = gf.originZ() + (k % nf) * gf.step();
            int ic = Math.round((bx - gc.originX()) / (float) gc.step());
            int jc = Math.round((bz - gc.originZ()) / (float) gc.step());
            if (ic < 0 || jc < 0 || ic >= nc || jc >= nc) continue;   // 越出粗窗 ⇒ -1
            out[k] = cbid[ic * nc + jc];
        }
        return out;
    }

    private static Domain empty(Key key, Geom g, Status st, int[] basinId,
                                Map<Integer, Integer> bp, Map<Integer, Status> bs, long coarseMs) {
        return new Domain(key, g, st, basinId, null, null, null, List.of(), bp, bs,
                coarseMs, -1, 0, 0);
    }

    /** 廉价屏幕判定的"无可见跨缝候选"域（不建窗）；不是 {@code NO_CROSSING}（那需真跑粗筛选）。 */
    public static Domain screened(Key key, HierarchicalHydrology.Params p) {
        HierarchicalHydrology.Diag.SHARED_SCREENED_OUT.incrementAndGet();
        Geom g = geom(key, p, 4, baseHalf(p));
        return empty(key, g, Status.NO_CROSSING, null, Map.of(), Map.of(), -1);
    }

    /** 粗盆地足迹是否跨过本 key 的缝线。 */
    private static boolean crosses(HierarchicalHydrology.BasinFootprint fp, Key key, Geom g) {
        int lo = key.axis() == 0 ? g.originX() + fp.minI() * g.step()
                : g.originZ() + fp.minJ() * g.step();
        int hi = key.axis() == 0 ? g.originX() + fp.maxI() * g.step()
                : g.originZ() + fp.maxJ() * g.step();
        return lo < key.boundary() && hi > key.boundary();
    }

    /** 粗足迹的块 bbox = {minX,maxX,minZ,maxZ}。 */
    private static int[] blockBbox(HierarchicalHydrology.BasinFootprint fp, Geom g) {
        return new int[]{g.originX() + fp.minI() * g.step(), g.originX() + fp.maxI() * g.step(),
                g.originZ() + fp.minJ() * g.step(), g.originZ() + fp.maxJ() * g.step()};
    }

    /** 粗盆地 anchor（绝对块）= argmin(bed)，tie: (worldX, worldZ)。 */
    private static int[] anchor(HydrologyPipeline.Result r, HierarchicalHydrology.BasinFootprint fp,
                                Geom g) {
        int n = r.nx(), step = r.cellBlocks();
        int[] bid = r.lakeResult().basinId();
        double[] bed = r.bed();
        double best = Double.POSITIVE_INFINITY;
        int bi = Integer.MAX_VALUE, bj = Integer.MAX_VALUE;
        for (int k = 0; k < bid.length; k++) {
            if (bid[k] != fp.basinId()) continue;
            int i = k / n, j = k % n;
            double bd = bed[k];
            if (bd < best || (bd == best && (i < bi || (i == bi && j < bj)))) {
                best = bd; bi = i; bj = j;
            }
        }
        if (bi == Integer.MAX_VALUE) return new int[]{g.originX(), g.originZ()};
        return new int[]{g.originX() + bi * step, g.originZ() + bj * step};
    }

    /** 粗筛选：指定半边长窗、粗层地形（零 sampleWu / 零 tile / 零预热）。 */
    private static HydrologyPipeline.Result coarseSolve(Ctx c, Geom g) {
        var req = new HydrologyPipeline.Request(g.centerX(), g.centerZ(), g.halfSpan(), g.step(),
                c.climate(), 0, HydrologyPipeline.RiverModel.D8, 1, null);
        return HydrologyPipeline.run(c.gen(), c.tp(), req, new HydrologyPipeline.Layer(
                HierarchicalHydrology.coarseTerrain(c.gen()), null, false, "shared-coarse"));
    }

    /** 细层：真实侵蚀地形（{@code erodedHeight}）+ 窗外 owner 粗层接口。 */
    private static HydrologyPipeline.Result fineSolve(Ctx c, Geom g) {
        var req = new HydrologyPipeline.Request(g.centerX(), g.centerZ(), g.halfSpan(), g.step(),
                c.climate(), 0, HydrologyPipeline.RiverModel.D8, 1, null);
        WindowOutlets.Terrain bnd = (bx, bz) ->
                c.owner().level((int) Math.floor(bx), (int) Math.floor(bz));
        return HydrologyPipeline.run(c.gen(), c.tp(), req, new HydrologyPipeline.Layer(
                null, bnd, true, "shared-fine"));
    }

    // ======================================================================
    // ======================================================================
    // 细层候选平面：闭合 + 干环 + 单平面 + 有限 + 落在本窗内
    //   ⚠ NOT VERIFIED：canonical 只对【粗 bbox】判定；细层仅要求"落窗内"。细层盆地分裂/合并
    //     时其 canonical 可能与本 key 不同（未复核）⇒ 本原型的受控范围未逐格证明 canonical 一致。
    //   ⚠ 干环只对【湖格】之外判"无水"（不剔除河/其他水面；只排除 lake 格以防覆盖）。
    // ======================================================================

    private static final class Acc {
        final int basinId;
        final double level;
        int cells, minI = Integer.MAX_VALUE, minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE, maxJ = Integer.MIN_VALUE;
        double sum, minLvl = Double.POSITIVE_INFINITY, maxLvl = Double.NEGATIVE_INFINITY;
        boolean frameTouch, ringDry = true, finite = true;
        Acc(int basinId, double level) { this.basinId = basinId; this.level = level; }
    }

    static final int NOT_CONTROLLED = -1;
    static final int CONTROLLED_DRY = -2;

    private static final class PlaneSet {
        final int[] fineBasinId;
        final int[] planeOfCell;
        final List<Plane> planes = new ArrayList<>();
        final Map<Integer, Integer> planeOfBasin = new TreeMap<>();
        PlaneSet(int cells, int[] fineBasinId) {
            this.fineBasinId = fineBasinId;
            this.planeOfCell = new int[cells];
            java.util.Arrays.fill(this.planeOfCell, NOT_CONTROLLED);
        }
    }

    /** 候选平面 = 闭合 + 干环 + 单平面 + 有限，且【完全落在本窗内】。 */
    static PlaneSet candidatePlanes(HydrologyPipeline.Result fine, Key key, Geom g,
                                    HierarchicalHydrology.Params p, int pad) {
        int n = fine.nx(), cells = n * n;
        int[] fb = fine.lakeResult() == null ? null : fine.lakeResult().basinId();
        PlaneSet ps = new PlaneSet(cells, fb == null ? new int[cells] : fb);
        if (fb == null || fine.lake() == null) return ps;
        Acc[] acc = accumulate(fine, fb, n, cells);
        for (Acc a : acc) {
            if (a == null || a.cells == 0) continue;
            Status bad = planeRejection(a);
            if (bad != null) continue;
            // key 已由【粗 bbox canonical】把关 ⇒ 细层只要求落在本窗内（不受细层分裂影响）。
            if (!insideWindow(g, g.originX() + a.minI * g.step(), g.originX() + a.maxI * g.step(),
                    g.originZ() + a.minJ * g.step(), g.originZ() + a.maxJ * g.step(), pad)) {
                continue;
            }
            double mean = a.sum / a.cells;
            int pid = ps.planes.size();
            ps.planes.add(new Plane(pid, a.basinId, mean, a.cells,
                    a.maxLvl - a.minLvl, a.minI, a.minJ, a.maxI, a.maxJ));
            ps.planeOfBasin.put(a.basinId, pid);
        }
        return ps;
    }

    private static Acc[] accumulate(HydrologyPipeline.Result fine, int[] fb, int n, int cells) {
        Acc[] acc = new Acc[fine.lakeResult().basins().size()];
        for (int b = 0; b < acc.length; b++) {
            acc[b] = new Acc(b, fine.lakeResult().basins().get(b).level());
        }
        double[] surf = fine.waterSurface() != null ? fine.waterSurface() : fine.level();
        boolean[] lake = fine.lake();
        for (int k = 0; k < cells; k++) {
            int b = fb[k];
            if (b < 0 || b >= acc.length || !lake[k]) continue;
            Acc a = acc[b];
            int i = k / n, j = k % n;
            a.cells++;
            a.minI = Math.min(a.minI, i);
            a.maxI = Math.max(a.maxI, i);
            a.minJ = Math.min(a.minJ, j);
            a.maxJ = Math.max(a.maxJ, j);
            a.sum += surf[k];
            a.minLvl = Math.min(a.minLvl, surf[k]);
            a.maxLvl = Math.max(a.maxLvl, surf[k]);
            if (!Double.isFinite(surf[k])) a.finite = false;
            if (i == 0 || j == 0 || i == n - 1 || j == n - 1) a.frameTouch = true;
        }
        ring(fine, fb, acc, n, cells);
        return acc;
    }

    private static void ring(HydrologyPipeline.Result fine, int[] fb, Acc[] acc, int n, int cells) {
        double[] bed = fine.bed();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int k = 0; k < cells; k++) {
            int b = fb[k];
            if (b < 0 || b >= acc.length || !fine.lake()[k]) continue;
            Acc a = acc[b];
            if (!a.ringDry) continue;
            int i = k / n, j = k % n;
            for (int[] d : dirs) {
                int ni = i + d[0], nj = j + d[1];
                if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                int nb = ni * n + nj;
                if (fb[nb] == b) continue;
                if (bed[nb] < a.level - PLANE_TOL) { a.ringDry = false; break; }
            }
        }
    }

    private static Status planeRejection(Acc a) {
        if (a.frameTouch) return Status.REFUSED_TRUNCATED;
        if (!a.finite) return Status.REFUSED_LEAK;
        if (a.maxLvl - a.minLvl > PLANE_TOL) return Status.REFUSED_LEAK;
        if (!a.ringDry) return Status.REFUSED_LEAK;
        return null;
    }

    /**
     * 标记【被选中细盆地】的<b>全部湖格</b>为受控平面 + 其外干环。
     *
     * <p>⚠ <b>不按粗足迹裁剪</b>：控制体是整盆地（whole closed basin）。粗层只提供候选联系
     * （哪个盆地跨缝、canonical key 归谁），<b>不</b>用粗 footprint 去 clip 细层湖格 —— 否则会把
     * 实际湖面按粗格切掉边缘（正是缝上残余 wall 的来源）。</p>
     * <p>干环只加在【本细平面】外侧，且【别湖格绝不覆盖】（保护其他湖/河）。</p>
     */
    private static void markSelected(HydrologyPipeline.Result fine, PlaneSet ps,
                                     Map<Integer, Integer> basinPlane, int chosen, Geom gf) {
        Integer pid = basinPlane.get(chosen);
        if (pid == null || pid < 0) return;
        int nf = gf.n(), cells = nf * nf;
        int fineId = ps.planes.get(pid).fineBasinId();
        boolean[] lake = fine.lake();
        for (int k = 0; k < cells; k++) {                       // ★ 整盆地湖格，无 coarse clip
            if (lake[k] && ps.fineBasinId[k] == fineId) ps.planeOfCell[k] = pid;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int k = 0; k < cells; k++) {
            if (ps.planeOfCell[k] < 0) continue;
            int i = k / nf, j = k % nf;
            for (int[] d : dirs) {
                int ni = i + d[0], nj = j + d[1];
                if (ni < 0 || nj < 0 || ni >= nf || nj >= nf) continue;
                int nb = ni * nf + nj;
                if (lake[nb]) continue;                        // ★ 别湖格绝不覆盖
                if (ps.planeOfCell[nb] == NOT_CONTROLLED) ps.planeOfCell[nb] = CONTROLLED_DRY;
            }
        }
    }

    /** 选中粗盆地 → 其细平面 id（按细层分区内重叠最多）；无 ⇒ -1。 */
    private static int pickRepresentative(PlaneSet ps, int[] cbid,
                                          HierarchicalHydrology.BasinFootprint fp,
                                          Geom gc, Geom gf) {
        if (fp == null || ps.fineBasinId == null) return -1;
        int nf = gf.n();
        Map<Integer, Integer> hist = new TreeMap<>();
        // 细层格 (if,jf) → 世界 → 粗层格 → 判是否属该粗盆地（两窗中心/半边长不同 ⇒ 不能同索引）。
        for (int kf = 0; kf < ps.fineBasinId.length; kf++) {
            Integer pid = ps.planeOfBasin.get(ps.fineBasinId[kf]);
            if (pid == null) continue;
            int bx = gf.originX() + (kf / nf) * gf.step();
            int bz = gf.originZ() + (kf % nf) * gf.step();
            int ic = Math.round((bx - gc.originX()) / (float) gc.step());
            int jc = Math.round((bz - gc.originZ()) / (float) gc.step());
            if (ic < 0 || jc < 0 || ic >= gc.n() || jc >= gc.n()) continue;
            if (cbid[ic * gc.n() + jc] != fp.basinId()) continue;
            hist.merge(pid, 1, Integer::sum);
        }
        int rep = -1, best = 0;
        for (Map.Entry<Integer, Integer> e : hist.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); rep = e.getKey(); }
        }
        return rep;
    }

    private static Status domainStatus(Map<Integer, Status> status) {
        Status out = Status.NO_CROSSING;
        for (Status s : status.values()) {
            if (s == Status.REFUSED_TOO_BIG) return Status.REFUSED_TOO_BIG;
            if (s == Status.REFUSED_TRUNCATED) return Status.REFUSED_TRUNCATED;
            if (s == Status.COMPETING) return Status.COMPETING;
            if (s == Status.REFUSED_LEAK) out = Status.REFUSED_LEAK;
            else if (s == Status.BASIN_LOST && out == Status.NO_CROSSING) out = Status.BASIN_LOST;
        }
        return out;
    }

    // ======================================================================
    // 域产物（只读；探针直接读）
    // ======================================================================

    /** 一个缝窗的完整产物：粗筛选 + 细层侵蚀求解 + 受控平面（含干环）。 */
    public static final class Domain {
        private final Key key;
        private final Geom g;
        private final Status status;
        /** 粗盆地 id，已【投影到 {@link #g}（细层）网格】（构造时一次；越出粗窗 = -1）。 */
        private final int[] coarseBasinId;
        private final int[] fineBasinId;
        private final boolean[] fineLake;
        private final int[] planeOfCell;
        private final List<Plane> planes;
        private final Map<Integer, Integer> basinPlane;
        private final Map<Integer, Status> basinStatus;
        private final long coarseMs, fineMs;
        private final int fineCells, controlledBasins;
        private java.util.function.Supplier<Domain> solver;
        private volatile Domain completed;
        private HydrologyPipeline.Result auditPipe;

        HydrologyPipeline.Result pipeForProbe() { return resolved().auditPipe; }

        boolean intersectsBox(int x0, int z0, int x1, int z1) {
            int loX = g.originX() - g.step() / 2, loZ = g.originZ() - g.step() / 2;
            int hiX = g.originX() + g.n() * g.step() - g.step() / 2 - 1;
            int hiZ = g.originZ() + g.n() * g.step() - g.step() / 2 - 1;
            return x1 >= loX && x0 <= hiX && z1 >= loZ && z0 <= hiZ;
        }

        Domain resolved() {
            if (status != Status.PENDING) return this;
            Domain hit = completed;
            if (hit != null) return hit;
            synchronized (this) {
                if (completed == null) {
                    Domain d = solver.get();
                    completed = d;
                    solver = null;
                }
                return completed;
            }
        }

        @SuppressWarnings("checkstyle:ParameterNumber")
        Domain(Key key, Geom g, Status status, int[] coarseBasinId, int[] fineBasinId,
               boolean[] fineLake, int[] planeOfCell, List<Plane> planes,
               Map<Integer, Integer> basinPlane, Map<Integer, Status> basinStatus,
               long coarseMs, long fineMs, int fineCells, int controlledBasins) {
            this.key = key;
            this.g = g;
            this.status = status;
            this.coarseBasinId = coarseBasinId;
            this.fineBasinId = fineBasinId;
            this.fineLake = fineLake;
            this.planeOfCell = planeOfCell;
            this.planes = List.copyOf(planes);
            this.basinPlane = Collections.unmodifiableMap(new TreeMap<>(basinPlane));
            this.basinStatus = Collections.unmodifiableMap(new TreeMap<>(basinStatus));
            this.coarseMs = coarseMs;
            this.fineMs = fineMs;
            this.fineCells = fineCells;
            this.controlledBasins = controlledBasins;
        }

        public Key key() { return key; }
        public Geom geom() { return g; }
        public Status status() { return status; }
        public long coarseMs() { return coarseMs; }
        public long fineMs() { return fineMs; }
        public int fineCells() { return fineCells; }
        public int crossingBasins() { return controlledBasins; }
        public List<Plane> planes() { return planes; }
        public Map<Integer, Integer> basinPlane() { return basinPlane; }
        public Map<Integer, Status> basinStatus() { return basinStatus; }

        public int cellAt(int blockX, int blockZ) {
            int i = Math.floorDiv(2 * (blockX - g.originX()) + g.step(), 2 * g.step());
            int j = Math.floorDiv(2 * (blockZ - g.originZ()) + g.step(), 2 * g.step());
            if (i < 0 || j < 0 || i >= g.n() || j >= g.n()) return -1;
            return i * g.n() + j;
        }

        public int basinIdAt(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            return k < 0 || coarseBasinId == null ? -1 : coarseBasinId[k];
        }

        /**
         * 该点在本窗的裁决。{@code controlled=false} 时 {@code status} 给出<b>明确原因</b>：
         * 该点的粗盆地若被拒绝则回传其拒绝原因（{@code REFUSED_*}），否则 {@code NOT_ELIGIBLE}。
         */
        public Decision decide(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            if (k < 0) return Decision.NONE;
            if (status == Status.PENDING) return resolved().decide(blockX, blockZ);
            if (planeOfCell == null) return Decision.NONE;
            int b = coarseBasinId == null ? -1 : coarseBasinId[k];
            int mark = planeOfCell[k];
            if (mark == CONTROLLED_DRY) return new Decision(true, false, Double.NaN, key, b, Status.CONTROLLED);
            if (mark < 0) {
                Status s = basinStatus.getOrDefault(b, Status.NOT_ELIGIBLE);
                return new Decision(false, false, Double.NaN, key, b, s);
            }
            Plane pl = planes.get(mark);
            boolean lake = fineLake != null && fineLake[k] && fineBasinId != null
                    && fineBasinId[k] == pl.fineBasinId();
            // 平面格（受控湖）；水位取实测均值（= 真实 wet plane 常数）。
            return new Decision(true, lake, lake ? pl.level() : Double.NaN, key, b, Status.CONTROLLED);
        }

        public String label() {
            return key.label() + " " + status + " ctl=" + controlledBasins
                    + " planes=" + planes.size() + " coarse=" + coarseMs + "ms fine=" + fineMs + "ms";
        }
    }
}
