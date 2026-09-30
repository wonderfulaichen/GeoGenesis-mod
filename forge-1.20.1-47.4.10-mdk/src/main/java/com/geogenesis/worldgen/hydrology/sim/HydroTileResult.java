package com.geogenesis.worldgen.hydrology.sim;

/**
 * 一个 tile 的不可变求解快照（拓扑 + 水量），是缓存与跨 tile 匹配的唯一载体。
 *
 * <p>缓存键必须含 {@code (seed, tileKey, configHash, CONTRACT_VERSION, SOLVER_VERSION)}，
 * 否则改了算法却复用旧结果 —— 正是本项目 {@code CACHE_SCHEMA_VERSION} 纪律要防的事。</p>
 */
public final class HydroTileResult {

    public final HydroTileKey key;
    public final HydroConfig cfg;
    public final HydroTileTopology topo;
    public final HydroTileBalance balance;

    HydroTileResult(HydroTileKey key, HydroConfig cfg,
                    HydroTileTopology topo, HydroTileBalance balance) {
        this.key = key;
        this.cfg = cfg;
        this.topo = topo;
        this.balance = balance;
    }

    public long cacheKey() {
        return HydroContract.cacheSalt(key.stableKey(), cfg.hash());
    }

    /** 该 tile 的出流端口按规范 id 的流量（供下游 tile 取用）。 */
    public double egressFlow(HydroTileKey.PortId id) {
        return balance.egressFlow(id);
    }

    /** 按窗口本地索引取状态；越界返回 NONE。 */
    public FlowState stateOf(int idx) {
        if (idx < 0 || idx >= balance.state.length) return FlowState.NONE;
        return FlowState.values()[balance.state[idx]];
    }

    public double waterLevelOf(int idx) {
        if (idx < 0 || idx >= balance.waterLevel.length) return Double.NaN;
        return balance.waterLevel[idx];
    }

    public double dischargeOf(int idx) {
        if (idx < 0 || idx >= balance.discharge.length) return 0;
        return balance.discharge[idx];
    }

    /** 是否含未决端口（需更大半径或更多环重解）。 */
    public boolean pending() {
        return balance.pending;
    }

    /**
     * 该 tile 的归宿统计：<b>window 内每一格</b>沿 D8 走到终点，统计各类终点格数。
     *
     * <p>契约要求 {@code LAND_SINK} 恒为 0：ε 微坡保证 window 内部格必有严格下坡，
     * 只有 window 边框格与海格才 {@code down = -1}。因此"内部格无下游"即契约破坏。</p>
     *
     * <p><b>⚠ 为什么统计【全部格】而不是"河道格"</b>：曾只统计 {@code state != 0} 的格，
     * 而成河阈值越高、被统计的格越少 ⇒ <b>判据会被参数洗白</b>（阈值调到很高时判据无格可查
     * 而自动通过）。归宿是拓扑属性，与"是否已发展成河道"无关，故必须全覆盖。</p>
     */
    public int[] outcomeCounts() {
        int[] c = new int[OutcomeKind.values().length];
        HydroTileField f = topo.field;
        int w = f.w;
        for (int i = 0; i < w * w; i++) {
            c[outcomeOf(i).ordinal()]++;
        }
        return c;
    }

    /** 单格归宿（与 {@link #outcomeCounts()} 同一口径）。 */
    public OutcomeKind outcomeOf(int idx) {
        HydroTileField f = topo.field;
        int w = f.w;
        int cur = idx;
        for (int step = 0; step < w * w; step++) {
            if (topo.sea[cur]) return OutcomeKind.SEA;
            int b = topo.basinId[cur];
            if (b >= 0) {
                if (!topo.basinResolved(b)) return OutcomeKind.BOUNDARY_PENDING;
                return balance.basinOverflows[b]
                        ? OutcomeKind.SPILL_TO_BASIN : OutcomeKind.CLOSED_BASIN;
            }
            if (f.isBorder(cur)) return OutcomeKind.BOUNDARY_PENDING;
            int nxt = topo.down[cur];
            if (nxt < 0) return OutcomeKind.LAND_SINK;   // 内部格却无下游 ⇒ 契约破坏
            cur = nxt;
        }
        return OutcomeKind.BOUNDARY_PENDING;
    }

    public String summary() {
        return key + " " + topo.summary() + " | " + balance.stateSummary();
    }
}
