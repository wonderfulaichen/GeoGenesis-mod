package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一个 tile 的水量守恒层：降水源项 → 单跳下泄 → 盆地蓄水/损失 → 最低溢口 → 下游。
 *
 * <h2>单位与守恒（全部同量纲）</h2>
 * <ul>
 *   <li>{@code HydroSampler.source} = 降水强度（block / unitTime）；</li>
 *   <li>{@code sourceVolume = rainDepth × cellArea}（block³ / unitTime）；</li>
 *   <li>{@code decay} = 沿程衰减率（1 / block），路径长度 L 后的存活率
 *       {@code exp(-decay × L)}；</li>
 *   <li>每格损失 = {@code Qin − Qout}，盆地损失 = 盆内所有格的同口径损失之和。</li>
 * </ul>
 *
 * <p>湖只接收汇入盆地的水量；湖水位由同一份 inflow 与 basin loss 平衡，
 * 达到最低溢口后余量从唯一 spill edge 注入下游。没有第二湖定义，也没有"仅贴状态不输水"。</p>
 */
public final class HydroTileBalance {

    public final HydroTileTopology topo;
    public final HydroConfig cfg;
    /** 逐格流入量（block³/unitTime），包含本地 source 和上游流量。 */
    public final double[] accum;
    /** 逐格流出量（block³/unitTime），海/窗口出口格为 0（已终结）。 */
    public final double[] discharge;
    /** 逐格损失（block³/unitTime），非水格为 0。 */
    public final double[] cellLoss;
    /** 逐格水面高度（block Y；NaN = 无水）。 */
    public final double[] waterLevel;
    /** 逐格生命周期状态（FlowState ordinal）。 */
    public final byte[] state;

    public final double[] basinLevel;
    public final double[] basinSpillOut;
    public final double[] basinInflow;
    public final double[] basinLoss;
    public final boolean[] basinOverflows;

    public final List<HydroPort> egressPorts;
    public final List<HydroPort> ingressPorts;
    /** core 端口流量（匹配邻 tile）。 */
    public final double boundaryOut;
    public final double boundaryIn;
    /** window 边缘流出量（完整守恒的边界项）。 */
    public final double windowOut;
    /** 入海流量。 */
    public final double seaOut;
    /** 溢口未决造成的保守滞留。 */
    public final double unresolvedStorage;
    public final double totalSource;
    public final double totalLoss;
    public final double totalSpill;
    public final boolean pending;

    private HydroTileBalance(HydroTileTopology topo, HydroConfig cfg, double[] accum,
                             double[] discharge, double[] cellLoss, double[] waterLevel,
                             byte[] state, double[] basinLevel, double[] basinSpillOut,
                             double[] basinInflow, double[] basinLoss, boolean[] basinOverflows,
                             List<HydroPort> egress, List<HydroPort> ingress,
                             double boundaryOut, double boundaryIn, double windowOut,
                             double seaOut, double unresolvedStorage, double totalSource,
                             double totalLoss, double totalSpill, boolean pending) {
        this.topo = topo;
        this.cfg = cfg;
        this.accum = accum;
        this.discharge = discharge;
        this.cellLoss = cellLoss;
        this.waterLevel = waterLevel;
        this.state = state;
        this.basinLevel = basinLevel;
        this.basinSpillOut = basinSpillOut;
        this.basinInflow = basinInflow;
        this.basinLoss = basinLoss;
        this.basinOverflows = basinOverflows;
        this.egressPorts = List.copyOf(egress);
        this.ingressPorts = List.copyOf(ingress);
        this.boundaryOut = boundaryOut;
        this.boundaryIn = boundaryIn;
        this.windowOut = windowOut;
        this.seaOut = seaOut;
        this.unresolvedStorage = unresolvedStorage;
        this.totalSource = totalSource;
        this.totalLoss = totalLoss;
        this.totalSpill = totalSpill;
        this.pending = pending;
    }

    /**
     * 求解。输入 {@code ingressFlows} 的顺序与 {@code topo.coreIngress()} 完全一致。
     * 核心采用 tile 内确定性拓扑序 + 有界溢流迭代；不保存帧历史。
     */
    public static HydroTileBalance solve(HydroTileTopology topo, HydroConfig cfg,
                                         double[] ingressFlows) {
        HydroTileField f = topo.field;
        int w = f.w, n = w * w, bc = topo.basinCount;
        List<HydroTileTopology.CoreEdge> ing = topo.coreIngress();
        List<HydroTileTopology.CoreEdge> eg = topo.coreEgress();

        double[] accum = new double[n];
        double[] discharge = new double[n];
        double[] cellLoss = new double[n];
        byte[] state = new byte[n];
        double[] waterLevel = new double[n];
        Arrays.fill(waterLevel, Double.NaN);
        double[] basinLevel = new double[bc];
        double[] basinSpill = new double[bc];
        double[] basinInflow = new double[bc];
        double[] basinLoss = new double[bc];
        boolean[] basinOverflows = new boolean[bc];

        double totalSource = 0;
        for (int i = 0; i < n; i++) {
            accum[i] = Math.max(0, f.source[i]) * cfg.cellArea();
            totalSource += accum[i];
        }
        for (int i = 0; ingressFlows != null && i < ing.size() && i < ingressFlows.length; i++) {
            if (ingressFlows[i] > 0) accum[ing.get(i).coreIdx()] += ingressFlows[i];
        }

        int[] egressOf = new int[n];
        Arrays.fill(egressOf, -1);
        for (int i = 0; i < eg.size(); i++) egressOf[eg.get(i).coreIdx()] = i;
        double[] egressFlow = new double[eg.size()];
        double[] spillSeed = new double[n];
        double windowOut = 0, seaOut = 0, unresolvedStorage = 0;
        double totalLoss = 0, totalSpill = 0;
        boolean pending = false;

        // 最小化必要：盆地出流可进入下游盆地，按稳定盆地 id 迭代至流量不变。
        for (int round = 0; round < 8; round++) {
            Arrays.fill(state, (byte) 0);
            Arrays.fill(waterLevel, Double.NaN);
            Arrays.fill(cellLoss, 0);
            Arrays.fill(discharge, 0);
            Arrays.fill(basinLevel, 0);
            Arrays.fill(basinSpill, 0);
            Arrays.fill(basinInflow, 0);
            Arrays.fill(basinLoss, 0);
            Arrays.fill(basinOverflows, false);
            Arrays.fill(egressFlow, 0);
            windowOut = 0;
            seaOut = 0;
            totalLoss = 0;
            totalSpill = 0;
            unresolvedStorage = 0;
            pending = false;

            for (int i = 0; i < n; i++) accum[i] = Math.max(0, f.source[i]) * cfg.cellArea() + spillSeed[i];
            for (int i = 0; ingressFlows != null && i < ing.size() && i < ingressFlows.length; i++) {
                if (ingressFlows[i] > 0) accum[ing.get(i).coreIdx()] += ingressFlows[i];
            }

            // fill 是严格 DAG 层级：先处理高处上游，再到下游低处。
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> {
                int c = Double.compare(topo.fill[b], topo.fill[a]);
                if (c != 0) return c;
                return HydroContract.compareCells(0, f.globalX(a), f.globalZ(a),
                        0, f.globalX(b), f.globalZ(b));
            });
            double[] basinIncoming = new double[bc];
            for (int idx : order) {
                double qin = accum[idx];
                if (topo.sea[idx]) {
                    state[idx] = (byte) FlowState.SEA.ordinal();
                    waterLevel[idx] = cfg.seaLevelY();
                    seaOut += qin;
                    continue;
                }
                int b = topo.basinId[idx];
                if (b >= 0) {
                    basinIncoming[b] += qin;
                    continue;
                }
                int dn = topo.down[idx];
                if (dn < 0) {
                    windowOut += qin;
                    discharge[idx] = qin;
                    int ei = egressOf[idx];
                    if (ei >= 0) egressFlow[ei] = qin;
                    continue;
                }
                double length = HydroContract.dirDist(topo.downDir[idx]) * cfg.cellBlocks();
                double survive = Math.exp(-f.decay[idx] * length);
                double qout = qin * survive;
                cellLoss[idx] = qin - qout;
                totalLoss += cellLoss[idx];
                discharge[idx] = qout;
                accum[dn] += qout;
                if (egressOf[idx] >= 0) egressFlow[egressOf[idx]] = qout;
                // 坡面水量始终参与下游输运；只有汇流量达到成河阈值才是河道格。
                // 旧实现无条件标 CHANNEL，导致整片坡面被渲染成河（实测新核心图几乎全青）。
                if (qout >= cfg.channelThreshold()) {
                    state[idx] = (byte) FlowState.CHANNEL.ordinal();
                    waterLevel[idx] = f.height[idx];
                }
            }

            double[] nextSpill = new double[n];
            for (int b = 0; b < bc; b++) {
                int[] members = topo.basinMemberArray(b);
                double qin = basinIncoming[b];
                basinInflow[b] = qin;
                if (members.length == 0) continue;
                double basinDecay = averageDecay(f, members);
                double spillLevel = topo.basinSpillLevel[b];
                double level = findLakeLevel(f, cfg, members, qin, basinDecay, spillLevel);
                double wetArea = wetArea(f, cfg, members, level);
                double evap = Math.min(qin, basinDecay * wetArea);
                double qspill = Math.max(0, qin - evap);
                boolean full = level >= spillLevel - cfg.levelEps();
                basinLevel[b] = level;
                basinLoss[b] = evap;
                basinSpill[b] = qspill;
                basinOverflows[b] = full && qspill > 0;
                totalLoss += evap;
                totalSpill += qspill;
                for (int c : members) {
                    if (f.height[c] < level + cfg.levelEps()) {
                        state[c] = (byte) FlowState.LAKE_STORAGE.ordinal();
                        waterLevel[c] = level;
                        discharge[c] = Math.max(0, level - f.height[c]) * cfg.cellArea();
                    }
                }
                if (qspill <= 0) continue;
                int sc = topo.basinSpillCell[b], sd = topo.basinSpillDir[b];
                if (sc < 0 || sd < 0) {
                    pending = true;
                    unresolvedStorage += qspill;
                    continue;
                }
                int lx = sc / w + HydroContract.DIR_DX[sd];
                int lz = sc % w + HydroContract.DIR_DZ[sd];
                if (!f.inside(lx, lz)) {
                    pending = true;
                    unresolvedStorage += qspill;
                    continue;
                }
                int dn = lx * w + lz;
                state[sc] = (byte) FlowState.SPILLWAY.ordinal();
                waterLevel[sc] = level;
                double len = HydroContract.dirDist(sd) * cfg.cellBlocks();
                double qdown = qspill * Math.exp(-f.decay[sc] * len);
                int db = topo.basinId[dn];
                if (db >= 0) {
                    nextSpill[dn] += qdown;         // 下游盆地下一轮接收
                } else {
                    nextSpill[dn] += qdown;         // 下一轮 D8 扫描继续传输
                    if (topo.sea[dn]) state[dn] = (byte) FlowState.SEA.ordinal();
                    else state[dn] = (byte) FlowState.DOWNSTREAM.ordinal();
                }
            }
            if (Arrays.equals(bits(nextSpill), bits(spillSeed))) break;
            spillSeed = nextSpill;
        }

        // 源头 + 入湖口派生。
        // ⚠ 源头判据必须是【上游没有已成河的格】，不能是"上游有任意水流"——
        //   每格都有降雨（discharge > 0 恒成立），用后者会导致 SOURCE 永远为 0
        //   （实测：首版就是如此，生命周期链追踪当场抓出"源头 0 条"）。
        // ⚠ 必须按 fill 降序（上游先于下游）遍历：下游格判断"上游是否已成河"时，
        //   上游必须已经完成 SOURCE/CHANNEL 判定，否则依赖顺序随机。
        Integer[] deriveOrder = new Integer[n];
        for (int i = 0; i < n; i++) deriveOrder[i] = i;
        Arrays.sort(deriveOrder, (a, b) -> {
            int c = Double.compare(topo.fill[b], topo.fill[a]);
            if (c != 0) return c;
            return HydroContract.compareCells(0, f.globalX(a), f.globalZ(a),
                    0, f.globalX(b), f.globalZ(b));
        });
        for (int i : deriveOrder) {
            if (state[i] != (byte) FlowState.CHANNEL.ordinal()) continue;
            boolean upstreamChannel = false;
            int x = i / w, z = i % w;
            for (int d = 0; d < 8 && !upstreamChannel; d++) {
                int nx = x + HydroContract.DIR_DX[d], nz = z + HydroContract.DIR_DZ[d];
                if (!f.inside(nx, nz)) continue;
                int up = nx * w + nz;
                if (topo.down[up] != i) continue;
                byte us = state[up];
                // 上游须是已成河的格（SOURCE / CHANNEL / 入湖口才算"已发展成河"）
                if (us == (byte) FlowState.CHANNEL.ordinal()
                        || us == (byte) FlowState.SOURCE.ordinal()
                        || us == (byte) FlowState.BASIN_ENTRY.ordinal()
                        || us == (byte) FlowState.DOWNSTREAM.ordinal()
                        || us == (byte) FlowState.SPILLWAY.ordinal()) {
                    upstreamChannel = true;
                }
            }
            if (!upstreamChannel) {
                // ⚠ 核心边界格不标源头：其上游可能落在 halo，而 halo 格的 fill/down 由本
                //   窗口重算（边框播种 ⇒ 与 owner 窗口不同源），邻居的 down 可能与 owner 视图
                //   不一致 ⇒ 上游"看不见"⇒ 中游被误标 SOURCE（生命周期追踪实测抓到过两次）。
                //   边界格的上游是否存在，只能由上游格的 owner 窗口回答（探针按此查链头）。
                boolean coreEdge = false;
                for (int d = 0; d < 8 && !coreEdge; d++) {
                    int nx = x + HydroContract.DIR_DX[d], nz = z + HydroContract.DIR_DZ[d];
                    if (f.inside(nx, nz) && !topo.inCore(nx * w + nz)) coreEdge = true;
                }
                if (!coreEdge) state[i] = (byte) FlowState.SOURCE.ordinal();
            }
            int dn = topo.down[i];
            if (dn >= 0 && topo.basinId[dn] >= 0 && basinLevel[topo.basinId[dn]] > 0) {
                state[i] = (byte) FlowState.BASIN_ENTRY.ordinal();
            }
        }

        // ★★★ 2026-09-30【连续河道水面 —— 修"河像方块拼起来"】★★★
        //   问题：此前河道每格 waterLevel = 本格地形高度（4 块格量化）⇒ 水面沿河逐格锯齿
        //   ⇒ 灌水判定（水面 > 地面）逐格翻转 ⇒ 水一格有一格无 = 方块拼接的河。
        //   正解（新水文自洽定义，不依赖任何旧实现）：水面沿程【只降不升】。
        //     水面[k] = min( 填洼面[k], max( 地形[k], 水面[下游] ) )
        //   · 拓扑序传递（fill 降序 ⇒ 下游先算）⇒ 构造上单调，与查询顺序无关；
        //   · 上界取填洼面：那是"水在此能站住的最高面" ⇒ 不会悬空漫山；
        //   · 下界取本格地形：河床不会被压到地面以下（水位恒不低于床）。
        //   湖面/溢口/海面不参与（它们有各自的物理水位，本步只处理 CHANNEL/SOURCE/下泄格）。
        monotoneRiverSurfaces(topo, state, waterLevel);

        List<HydroPort> egress = new ArrayList<>();
        List<HydroPort> ingressOut = new ArrayList<>();
        double boundaryOut = 0, boundaryIn = 0;
        for (int i = 0; i < eg.size(); i++) {
            double flow = egressFlow[i];
            if (flow <= 0) continue;
            HydroTileTopology.CoreEdge e = eg.get(i);
            HydroTileKey.PortId id = e.egressPortId();
            egress.add(new HydroPort(id.gx(), id.gz(), id.dir(), PortKind.EGRESS,
                    f.globalX(e.coreIdx()), f.globalZ(e.coreIdx()), topo.fill[e.coreIdx()],
                    e.height(), f.source[e.coreIdx()] * cfg.cellArea(), flow, -1, f.key.stableKey()));
            boundaryOut += flow;
        }
        for (int i = 0; i < ing.size(); i++) {
            HydroTileTopology.CoreEdge e = ing.get(i);
            double flow = ingressFlows != null && i < ingressFlows.length ? ingressFlows[i] : 0;
            HydroTileKey.PortId id = e.ingressPortId();
            ingressOut.add(new HydroPort(id.gx(), id.gz(), id.dir(), PortKind.INGRESS,
                    f.globalX(e.coreIdx()), f.globalZ(e.coreIdx()), topo.fill[e.coreIdx()],
                    e.height(), f.source[e.coreIdx()] * cfg.cellArea(), flow, -1, f.key.stableKey()));
            boundaryIn += flow;
        }
        return new HydroTileBalance(topo, cfg, accum, discharge, cellLoss, waterLevel, state,
                basinLevel, basinSpill, basinInflow, basinLoss, basinOverflows,
                egress, ingressOut, boundaryOut, boundaryIn, windowOut, seaOut,
                unresolvedStorage, totalSource, totalLoss, totalSpill, pending);
    }

    /**
     * 河道水面单调剖面：{@code s[k] = min(fill[k], max(h[k], s[down[k]]))}，按 fill 降序传递。
     *
     * <p>三重保证：① 拓扑序（下游 fill 更小 ⇒ 先算）⇒ 单遍即得、与查询顺序无关；
     * ② 由 {@code max(h, s[down])} 且 {@code fill[k] ≥ fill[down] ≥ s[down]} ⇒
     * <b>沿程只降不升</b>（构造证明，非调参）；③ 水面恒在 {@code [地形, 填洼面]} 之间
     * ⇒ 既不埋进地里，也不悬空漫山。</p>
     */
    private static void monotoneRiverSurfaces(HydroTileTopology topo, byte[] state,
                                              double[] waterLevel) {
        HydroTileField f = topo.field;
        int n = f.w * f.w;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(topo.fill[b], topo.fill[a]);
            if (c != 0) return c;
            return HydroContract.compareCells(0, f.globalX(a), f.globalZ(a),
                    0, f.globalX(b), f.globalZ(b));
        });
        for (int idx : order) {
            FlowState st = FlowState.values()[state[idx]];
            if (st != FlowState.SOURCE && st != FlowState.CHANNEL
                    && st != FlowState.DOWNSTREAM) {
                continue;                       // 湖/海/溢口/入湖口保留各自物理水位
            }
            int dn = topo.down[idx];
            double downstream = (dn >= 0 && !Double.isNaN(waterLevel[dn]))
                    ? waterLevel[dn] : f.height[idx];
            waterLevel[idx] = Math.min(topo.fill[idx], Math.max(f.height[idx], downstream));
        }
    }

    private static long[] bits(double[] a) {        long[] b = new long[a.length];
        for (int i = 0; i < a.length; i++) b[i] = Double.doubleToLongBits(a[i]);
        return b;
    }

    private static double findLakeLevel(HydroTileField f, HydroConfig cfg, int[] members,
                                        double qin, double decay, double spill) {
        if (members.length == 0) return spill;
        if (qin <= 0 || decay <= 0) return spill;
        Integer[] sorted = new Integer[members.length];
        for (int i = 0; i < members.length; i++) sorted[i] = members[i];
        Arrays.sort(sorted, (a, b) -> HydroContract.compareCells(
                f.height[a], f.globalX(a), f.globalZ(a), f.height[b], f.globalX(b), f.globalZ(b)));
        double area = 0;
        for (int c : sorted) {
            if (f.height[c] >= spill) break;
            area += cfg.cellArea();
            if (decay * area >= qin) return f.height[c];
        }
        return spill;
    }

    private static double wetArea(HydroTileField f, HydroConfig cfg, int[] members, double level) {
        double a = 0;
        for (int c : members) if (f.height[c] < level + cfg.levelEps()) a += cfg.cellArea();
        return a;
    }

    private static double averageDecay(HydroTileField f, int[] members) {
        if (members.length == 0) return 0;
        double sum = 0;
        for (int c : members) sum += f.decay[c];
        return sum / members.length;
    }

    /** 端口流量（供 solver 的上游 tile 查询）。 */
    public double egressFlow(HydroTileKey.PortId id) {
        for (HydroPort p : egressPorts) {
            if (p.gx() == id.gx() && p.gz() == id.gz() && p.dir() == id.dir()) return p.flow();
        }
        return 0;
    }

    /** 完整守恒残差。 */
    public double conservationResidual() {
        return totalSource + boundaryIn - totalLoss - windowOut - seaOut - unresolvedStorage;
    }

    public int[] stateCounts() {
        int[] c = new int[FlowState.values().length];
        for (byte s : state) c[s]++;
        return c;
    }

    public String stateSummary() {
        int[] c = stateCounts();
        StringBuilder sb = new StringBuilder();
        for (FlowState s : FlowState.values()) {
            if (c[s.ordinal()] > 0) sb.append(s).append('=').append(c[s.ordinal()]).append(' ');
        }
        return sb.toString().trim();
    }
}
