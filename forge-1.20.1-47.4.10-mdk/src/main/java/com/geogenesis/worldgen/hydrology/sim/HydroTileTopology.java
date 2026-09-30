package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 一个 tile 的<b>拓扑层</b>：先把"水去哪儿"定死，再谈河线形态。
 *
 * <h2>算法（每步都有定义级保证）</h2>
 * <ol>
 *   <li><b>priority-flood 填洼</b>（播种 = window 边界格 + 海格）：产出 {@code fill ≥ h}。
 *       传播加 {@link HydroContract#FILL_EPS} ε 微坡 ⇒ 每个非播合格都有一个 fill
 *       严格更小的邻居 ⇒ <b>构造上不存在"流不动"的格</b>（旧链用补丁兜底的死尾在此消失）。</li>
 *   <li><b>D8 于填洼面</b>：{@code down[i]} = 8 邻中 fill 最小者，必须严格更小；
 *       无则 {@code down = -1}（只可能是 seed：window 边界或海）。
 *       fill 沿流向严格递减 ⇒ <b>流向图必为 DAG</b>（旧链的自环/兜底续流问题不存在）。</li>
 *   <li><b>盆地</b>：{@code fill − h > levelEps} 的格按 8 邻连通分量标记。
 *       洼地就是洼地，<b>无面积/深度门槛</b>（用户铁律：湖泊唯一定义 = 洼地蓄水）。</li>
 *   <li><b>最低溢口（minimax）</b>：{@code spillLevel = min over (盆内 b, 盆外邻 n) of max(h[b], h[n])}
 *       —— 水要漫过的最低鞍点，即用户语义的"最低溢出高度"。</li>
 * </ol>
 *
 * <h2>端口定位在 core 边界（关键设计）</h2>
 * <p>window = core + 2·halo。端口必须定义在 <b>core 边界</b>而不是 window 边界：
 * core 是 tile "拥有"的区域，相邻 tile 的 core 相接。同一条物理跨核边，两侧都能看到
 * （一侧在 core 内、一侧在 halo 内），从而算出<b>同一规范 id</b>：
 * {@code (外侧格, 指向对方的反方向)}。详见 {@link HydroPort#canonicalId}。</p>
 */
public final class HydroTileTopology {

    /**
     * 跨核边：core 侧格 + 指向窗外侧的方向。
     *
     * <p><b>规范端口 id = (上游格, 由上游指向下游的方向)</b>。同一条物理边两侧都算得出同一个 id：</p>
     * <ul>
     *   <li>拥有上游格的 tile（egress）：core 格就是上游格 ⇒ {@code (core, dir)}；</li>
     *   <li>拥有下游格的 tile（ingress）：core 格是下游格，上游格 = core + dir ⇒
     *       {@code (core + dir, opposite(dir))}。</li>
     * </ul>
     */
    public record CoreEdge(int coreIdx, int dir, boolean egress, int outerGx, int outerGz,
                           double level, double height) {

        /** 出流侧的规范 id（本 tile 的 core 格即上游格）。 */
        public HydroTileKey.PortId egressPortId() {
            return new HydroTileKey.PortId(outerGx - HydroContract.DIR_DX[dir],
                    outerGz - HydroContract.DIR_DZ[dir], dir);
        }

        /** 入流侧的规范 id（本 tile 的 core 格是下游格，上游格在窗外）。 */
        public HydroTileKey.PortId ingressPortId() {
            return new HydroTileKey.PortId(outerGx, outerGz, HydroPort.opposite(dir));
        }

        /** 该边两端格是否同属一个 tile 的 core（用于自检端口契约）。 */
        public boolean crossesCore() {
            return true;
        }
    }

    public final HydroTileField field;
    public final double[] fill;
    public final int[] down;
    public final int[] downDir;
    public final boolean[] sea;
    public final int[] basinId;
    public final int basinCount;

    public final double[] basinSpillLevel;
    public final int[] basinSpillCell;
    public final int[] basinSpillDir;
    public final int[] basinCellCount;
    /**
     * 每个盆地的<b>最低溢口候选是否落在 window 边框上</b>。
     *
     * <p>{@code true} ⇒ 真正的 rim 在 halo 之外，当前 minimax 水位只是下界 ⇒ 未决
     * （{@link #basinResolved}）。注意这<b>不是</b>"盆地是否越过 core 边"——
     * 盆地延伸到 halo 而 rim 仍在窗内时水位是精确的。</p>
     */
    public final boolean[] basinRimAtWindowBorder;
    public final double[] basinFloor;
    public final int[] basinDeepest;
    /** 盆地稳定序种子（盆内最深格的全球 X）——用于跨 tile 盆地去重。 */
    public final int[] basinSeedX;
    public final int[] basinSeedZ;
    /** 每个盆地的成员格（本地索引），构造期一次成型，避免平衡层重复扫描窗口。 */
    public final int[][] basinMembers;

    /** core 起点（全球格，含）。 */
    public final int coreMinX, coreMinZ, coreCells;

    private final List<CoreEdge> egress;
    private final List<CoreEdge> ingress;

    private HydroTileTopology(HydroTileField field, double[] fill, int[] down, int[] downDir,
                              boolean[] sea, int[] basinId, int basinCount,
                              double[] spillLevel, int[] spillCell, int[] spillDir,
                              int[] cellCount, boolean[] rimAtBorder, double[] floor, int[] deepest,
                              int[] seedX, int[] seedZ, int[][] members,
                              List<CoreEdge> egress, List<CoreEdge> ingress) {
        this.field = field;
        this.fill = fill;
        this.down = down;
        this.downDir = downDir;
        this.sea = sea;
        this.basinId = basinId;
        this.basinCount = basinCount;
        this.basinSpillLevel = spillLevel;
        this.basinSpillCell = spillCell;
        this.basinSpillDir = spillDir;
        this.basinCellCount = cellCount;
        this.basinRimAtWindowBorder = rimAtBorder;
        this.basinFloor = floor;
        this.basinDeepest = deepest;
        this.basinSeedX = seedX;
        this.basinSeedZ = seedZ;
        this.basinMembers = members;
        this.coreMinX = field.key.coreMinX();
        this.coreMinZ = field.key.coreMinZ();
        this.coreCells = field.cfg.coreCells();
        this.egress = List.copyOf(egress);
        this.ingress = List.copyOf(ingress);
    }

    public List<CoreEdge> coreEgress() {
        return egress;
    }

    public List<CoreEdge> coreIngress() {
        return ingress;
    }

    /** 该本地格是否位于 core 内。 */
    public boolean inCore(int idx) {
        int gx = field.globalX(idx), gz = field.globalZ(idx);
        return gx >= coreMinX && gx < coreMinX + coreCells
                && gz >= coreMinZ && gz < coreMinZ + coreCells;
    }

    /** 求解拓扑（纯函数：只依赖 field）。 */
    public static HydroTileTopology build(HydroTileField field) {
        int w = field.w;
        int n = w * w;
        double[] h = field.height;
        final double eps = HydroContract.FILL_EPS;

        // ---------- ① priority-flood ----------
        double[] fill = new double[n];
        System.arraycopy(h, 0, fill, 0, n);
        boolean[] settled = new boolean[n];
        boolean[] sea = new boolean[n];
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0]));
            if (c != 0) return c;
            return Integer.compare((int) a[1], (int) b[1]);   // idx 序 == (globalX, globalZ) 字典序
        });
        for (int idx = 0; idx < n; idx++) {
            if (h[idx] <= field.cfg.seaLevelY()) sea[idx] = true;
            if (field.isBorder(idx) || sea[idx]) {
                settled[idx] = true;
                pq.add(new long[]{Double.doubleToLongBits(fill[idx]), idx});
            }
        }
        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            double lvl = Double.longBitsToDouble(cur[0]);
            int idx = (int) cur[1];
            if (lvl > fill[idx]) continue;
            int lx = idx / w, lz = idx % w;
            for (int d = 0; d < 8; d++) {
                int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                if (!field.inside(nx, nz)) continue;
                int nb = nx * w + nz;
                if (settled[nb]) continue;
                double v = Math.max(h[nb], lvl + eps);
                fill[nb] = v;
                settled[nb] = true;
                pq.add(new long[]{Double.doubleToLongBits(v), nb});
            }
        }

        // ---------- ② D8 on fill ----------
        //   ⚠ 2026-09-30【曾试"连续坡度"选向，已实测否决并回退 —— 勿重试同法】：
        //   把选向从"fill 最小"改为"坡度 = Δfill / 真实距离(1 或 √2) 最大"，动机是
        //   修用户判据"河很直、都是 45° 折角"（怀疑斜向被系统性偏好）。
        //   实测（同 seed 同窗口）：连续性 98.7%→98.7%（无改善）、贴谷率 73.5%→**68.4%**（变差）、
        //   河格 4082→3848（略减），且 `runHydroLifecycleProbe` 的 [L3] 出现
        //   312 条"中游冒源头"（FAIL）⇒ **零收益 + 破门禁** ⇒ 回退。
        //   ⇒ 结论：直/折角来自【离散格拓扑】本身，靠改 D8 的"选哪个邻格"治不了；
        //     真解是把流向建成【连续场】（双线性 ∇h）而不是 8 选 1，属独立议题。
        int[] down = new int[n];
        int[] downDir = new int[n];
        Arrays.fill(down, -1);
        Arrays.fill(downDir, -1);
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < w; lz++) {
                int idx = lx * w + lz;
                int best = -1, bestDir = -1;
                for (int d = 0; d < 8; d++) {
                    int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                    if (!field.inside(nx, nz)) continue;
                    int nb = nx * w + nz;
                    if (fill[nb] >= fill[idx]) continue;
                    if (best < 0) { best = nb; bestDir = d; continue; }
                    int c = HydroContract.compareCells(fill[nb], field.globalX(nb), field.globalZ(nb),
                            fill[best], field.globalX(best), field.globalZ(best));
                    if (c < 0) { best = nb; bestDir = d; }
                }
                down[idx] = best;
                downDir[idx] = bestDir;
            }
        }

        // ---------- ③ 盆地连通分量（无门槛）----------
        int[] basinId = new int[n];
        Arrays.fill(basinId, -1);
        int[] stack = new int[n];
        List<int[]> basinCellList = new ArrayList<>();       // 每个盆地的格集合（保留；用于面积/去重）
        List<Double> spillList = new ArrayList<>();
        List<Integer> spillCellList = new ArrayList<>();
        List<Integer> spillDirList = new ArrayList<>();
        List<Boolean> touchList = new ArrayList<>();
        List<Double> floorList = new ArrayList<>();
        List<Integer> deepestList = new ArrayList<>();
        List<Integer> seedXList = new ArrayList<>();
        List<Integer> seedZList = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (basinId[start] >= 0) continue;
            if (!(fill[start] - h[start] > field.cfg.levelEps())) continue;
            int id = spillList.size();
            int sp = 0;
            stack[sp++] = start;
            basinId[start] = id;
            List<Integer> members = new ArrayList<>();
            double spill = Double.MAX_VALUE;
            int spillCell = -1, spillDir = -1;
            double minH = Double.MAX_VALUE;
            int deepest = start;
            boolean rimAtWindowBorder = false;
            while (sp > 0) {
                int c = stack[--sp];
                members.add(c);
                if (h[c] < minH) { minH = h[c]; deepest = c; }
                int lx = c / w, lz = c % w;
                for (int d = 0; d < 8; d++) {
                    int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                    if (!field.inside(nx, nz)) continue;
                    int nb = nx * w + nz;
                    if (fill[nb] - h[nb] > field.cfg.levelEps()) {
                        if (basinId[nb] < 0) { basinId[nb] = id; stack[sp++] = nb; }
                        continue;
                    }
                    // 盆外邻 ⇒ minimax 候选
                    double lvl = Math.max(h[c], h[nb]);
                    if (spillCell < 0) {
                        spill = lvl; spillCell = c; spillDir = d;
                        rimAtWindowBorder = field.isBorder(nb);
                        continue;
                    }
                    int cmp = HydroContract.compareCells(lvl, field.globalX(nb), field.globalZ(nb),
                            spill, field.globalX(spillCell), field.globalZ(spillCell));
                    if (cmp < 0 || (cmp == 0 && d < spillDir)) {
                        spill = lvl; spillCell = c; spillDir = d;
                        rimAtWindowBorder = field.isBorder(nb);
                    }
                }
            }
            int[] arr = new int[members.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = members.get(i);
            basinCellList.add(arr);
            spillList.add(spillCell < 0 ? h[deepest] : spill);
            spillCellList.add(spillCell);
            spillDirList.add(spillDir);
            touchList.add(rimAtWindowBorder);
            floorList.add(minH);
            deepestList.add(deepest);
            seedXList.add(field.globalX(deepest));
            seedZList.add(field.globalZ(deepest));
        }

        int bc = spillList.size();
        double[] spillLevel = new double[bc];
        int[] spillCellArr = new int[bc];
        int[] spillDirArr = new int[bc];
        int[] cellCountArr = new int[bc];
        boolean[] touchArr = new boolean[bc];
        double[] floorArr = new double[bc];
        int[] deepestArr = new int[bc];
        int[] seedXArr = new int[bc];
        int[] seedZArr = new int[bc];
        int[][] membersArr = new int[bc][];
        for (int b = 0; b < bc; b++) {
            spillLevel[b] = spillList.get(b);
            spillCellArr[b] = spillCellList.get(b);
            spillDirArr[b] = spillDirList.get(b);
            int[] mem = basinCellList.get(b);
            membersArr[b] = mem;
            cellCountArr[b] = mem.length;
            touchArr[b] = touchList.get(b);
            floorArr[b] = floorList.get(b);
            deepestArr[b] = deepestList.get(b);
            seedXArr[b] = seedXList.get(b);
            seedZArr[b] = seedZList.get(b);
        }

        // ---------- ④ 跨核边（端口）----------
        List<CoreEdge> egress = new ArrayList<>();
        List<CoreEdge> ingress = new ArrayList<>();
        int coreCells = field.cfg.coreCells();
        int coreMaxX = field.key.coreMinX() + coreCells - 1;
        int coreMaxZ = field.key.coreMinZ() + coreCells - 1;
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < w; lz++) {
                int idx = lx * w + lz;
                if (!inCoreOf(field, idx)) continue;
                int gx = field.globalX(idx), gz = field.globalZ(idx);
                boolean onCoreEdge = gx == field.key.coreMinX() || gx == coreMaxX
                        || gz == field.key.coreMinZ() || gz == coreMaxZ;
                if (!onCoreEdge) continue;
                for (int d = 0; d < 8; d++) {
                    int nx = lx + HydroContract.DIR_DX[d], nz = lz + HydroContract.DIR_DZ[d];
                    if (!field.inside(nx, nz)) continue;
                    int nb = nx * w + nz;
                    if (inCoreOf(field, nb)) continue;          // 只看跨核边
                    int ogx = field.globalX(nb), ogz = field.globalZ(nb);
                    // 流出 core：本格下坡方向指向窗外侧
                    if (down[idx] == nb) {
                        egress.add(new CoreEdge(idx, d, true, ogx, ogz, fill[idx], h[idx]));
                    }
                    // 流入 core：窗外侧格的下坡方向指向本格
                    if (down[nb] == idx) {
                        ingress.add(new CoreEdge(idx, d, false, ogx, ogz, fill[nb], h[nb]));
                    }
                }
            }
        }

        return new HydroTileTopology(field, fill, down, downDir, sea, basinId, bc,
                spillLevel, spillCellArr, spillDirArr, cellCountArr, touchArr, floorArr,
                deepestArr, seedXArr, seedZArr, membersArr, egress, ingress);
    }

    private static boolean inCoreOf(HydroTileField field, int idx) {        int gx = field.globalX(idx), gz = field.globalZ(idx);
        int c = field.cfg.coreCells();
        return gx >= field.key.coreMinX() && gx < field.key.coreMinX() + c
                && gz >= field.key.coreMinZ() && gz < field.key.coreMinZ() + c;
    }

    /**
     * 该盆地的溢口是否已精确确定。
     *
     * <p><b>判据（2026-09-29 修正）</b>：只看<b>溢口候选本身</b>是否落在 window 边框上。
     * window 边框格是填洼的"种子"（fill = 自身高度），若最低鞍点就落在那里，
     * 说明真正的 rim 在 halo 之外 ⇒ 水位只是下界 ⇒ 未决。</p>
     *
     * <p><b>被修掉的过保守</b>：原判据是"盆地只要越过 core 边就未决"。但 halo 的职责
     * 恰恰是提供 rim —— 盆地延伸到 halo 而 rim 仍在窗内时，minimax 水位是<b>精确</b>的，
     * 却被错标未决（探针实测：碗形地形整片湖被标 PENDING）。</p>
     */
    public boolean basinResolved(int b) {
        return !basinRimAtWindowBorder[b] && basinSpillCell[b] >= 0;
    }

    /** 盆地蓄水体积（block³）：水面取 level，积分盆内低于 level 的部分。 */
    public double basinStorageVolume(int b, double level) {
        double area = field.cfg.cellArea();
        double v = 0;
        int w = field.w;
        for (int idx = 0; idx < w * w; idx++) {
            if (basinId[idx] != b) continue;
            double d = level - field.height[idx];
            if (d > 0) v += d * area;
        }
        return v;
    }

    /** 诊断摘要。 */
    public String summary() {
        int total = 0;
        for (int c : basinCellCount) total += c;
        int resolved = 0;
        for (int b = 0; b < basinCount; b++) if (basinResolved(b)) resolved++;
        return "core=" + coreCells + "² window=" + field.w + "² basins=" + basinCount
                + " (resolved=" + resolved + ") lakeCells=" + total
                + " egress=" + egress.size() + " ingress=" + ingress.size();
    }

    /** 供平衡层使用的盆地成员快照（构造期已成型，O(1) 取用）。 */
    public int[] basinMemberArray(int b) {
        return basinMembers[b];
    }

    /** 拓扑出现环时的自检（ε 微坡下不应发生）。 */
    public int detectCycles() {
        int n = field.w * field.w;
        int[] state = new int[n];               // 0=未访问 1=在栈 2=完成
        ArrayDeque<Integer> st = new ArrayDeque<>();
        int cycles = 0;
        for (int s = 0; s < n; s++) {
            if (state[s] != 0) continue;
            st.push(s);
            while (!st.isEmpty()) {
                int c = st.peek();
                if (state[c] == 0) state[c] = 1;
                int nxt = down[c];
                if (nxt < 0) { state[c] = 2; st.pop(); continue; }
                if (state[nxt] == 1) { cycles++; state[c] = 2; st.pop(); continue; }
                if (state[nxt] == 2) { state[c] = 2; st.pop(); continue; }
                st.push(nxt);
            }
        }
        return cycles;
    }
}
