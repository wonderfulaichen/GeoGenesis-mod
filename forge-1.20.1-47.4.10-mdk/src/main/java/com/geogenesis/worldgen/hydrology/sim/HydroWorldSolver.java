package com.geogenesis.worldgen.hydrology.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无限世界的水文求解器：按需生成 tile，用端口把水跨 tile 接续起来。
 *
 * <h2>★ 规范性（顺序无关的实现方式，2026-09-29 重写）</h2>
 * <p>门禁 {@code runHydrologyDeterminismProbe} 实测抓到过 {@code max|Δheight| = 2.2e-4}
 * 的顺序依赖，根因是旧实现的三处"查询历史污染"：</p>
 * <ol>
 *   <li>环数截断按 {@code originTx}（谁在查）计算 ⇒ 同一 tile 在不同查询根下入流不同；</li>
 *   <li>{@code build()} 把"零入流半成品"无条件写进缓存 ⇒ 后续查询读到半成品；</li>
 *   <li>定点迭代的初值 = 缓存现状、工作集 = 本次触及集合 ⇒ 两者都随查询历史变。</li>
 * </ol>
 * <p>本实现改为 <b>plan §4.2 的规范形式</b>：</p>
 * <ul>
 *   <li><b>闭包自决</b>：{@code resolve(T)} 以 <b>T 自身</b>为根，沿 coreIngress（端口结构）
 *       向上游收集半径 {@code maxRings} 的 tile 集合 —— 结构只依赖 (seed, cfg) ⇒ 规范；</li>
 *   <li><b>零初值 + 固定序定点</b>：闭包内全部 tile 以"零入流"起算，按稳定键升序做
 *       Gauss-Seidel 迭代，收敛即停或到达规范轮数上限（未收敛 ⇒ 保守，不伪造）；</li>
 *   <li><b>只 memo 根</b>：闭包内部值是"以 T 为根"的中间量，可能与其他根不同 ⇒ 不入缓存；
 *       只有根 T 的规范解进入缓存，而它由 (T, cfg, sampler) 唯一决定 ⇒ 可安全复用。</li>
 * </ul>
 * <p>闭环截断（半径 &gt; maxRings 的上游）是结构属性 ⇒ 规范；其水量保守省略并标记未决，
 * <b>不伪造陆地断头</b>（LAND_SINK 由拓扑的 ε 微坡构造保证，与水量无关）。</p>
 */
public final class HydroWorldSolver {

    /** 定点迭代上限（规范常量；DAG 上通常 1~2 轮收敛，环/假环需更多）。 */
    private static final int MAX_FIXED_POINT_ROUNDS = 64;
    /** 收敛容差（相对 boundaryIn 变化）。 */
    private static final double CONVERGE_TOL = 1e-14;

    /**
     * ★ 2026-09-30【世界种子：可变更 —— 修"生产水文恒用 seed=0"的致命 bug】★
     *
     * <p>此前是 {@code final}：{@code GeoGenesisTerrain} 构造时以 {@code seed=0} 建
     * {@code HydrologyChunkEngine}，而 {@code seed(worldSeed)} 只清缓存、<b>改不到它</b>
     * ⇒ 生产水文的 terrain field 一直是 <b>seed=0 的地形</b>，与世界地形整体错位
     * （实测症状：落块层窗口内河流只有 4 格水、河不成网、河不贴谷）。</p>
     */
    private volatile long seed;
    private final HydroConfig cfg;
    private final HydroSampler sampler;

    /**
     * ⚠ 2026-09-29【线程安全 —— 实测崩溃后的修复，勿改回普通 HashMap】：
     * chunk 生成是多线程的（Worker-Main 池），且结构扫描线程（BiomeSource ring）
     * 也会经快速路径/绿洲进入本 solver ⇒ 并发读写缓存。
     * 旧实现 LinkedHashMap 直接 CME 崩溃（crash-2026-09-29_23.52.55，栈顶
     * {@code HydroWorldSolver.field} 的 HashMap.computeIfAbsent）。
     * 三个缓存全部换 {@link ConcurrentHashMap}；重量计算用"get → 本地算 → putIfAbsent"
     * 双检（不在锁内采样 —— field 采样含侵蚀 tile 生成，可达秒级；CHM 的
     * computeIfAbsent 会把重量计算锁在 bin 上）。并发同 key miss 会多算一次，
     * 但求解是纯函数 ⇒ 幂等同值，只浪费一次计算。
     */
    private final ConcurrentMap<Long, HydroTileResult> cache = new ConcurrentHashMap<>();
    /** 字段/拓扑缓存：(seed, tileKey, cfg) 的纯函数，可任意复用。 */
    private final ConcurrentMap<Long, HydroTileField> fieldCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, HydroTileTopology> topoCache = new ConcurrentHashMap<>();

    private final AtomicInteger buildCount = new AtomicInteger();
    private final AtomicInteger cycleDetections = new AtomicInteger();
    private final AtomicInteger truncations = new AtomicInteger();
    private final AtomicInteger nonConverged = new AtomicInteger();

    public HydroWorldSolver(long seed, HydroConfig cfg, HydroSampler sampler) {
        this.seed = seed;
        this.cfg = cfg;
        this.sampler = sampler;
    }

    public HydroConfig config() { return cfg; }
    public long seed() { return seed; }
    public HydroSampler sampler() { return sampler; }

    /** 切换世界种子（清空全部缓存；tile key 含 seed ⇒ 旧结果不可复用）。 */
    public void setSeed(long newSeed) {
        this.seed = newSeed;
        clearCache();
    }

    public int buildCount() { return buildCount.get(); }
    public int cycleDetections() { return cycleDetections.get(); }
    public int truncations() { return truncations.get(); }
    /** 定点未达收敛而取轮数上限的次数（保守未决信号）。 */
    public int nonConverged() { return nonConverged.get(); }
    public int cachedTiles() { return cache.size(); }
    public int cachedFieldTiles() { return fieldCache.size(); }
    public int touchedTiles() { return buildCount.get(); }

    public void clearCache() {
        cache.clear();
        fieldCache.clear();
        topoCache.clear();
    }

    public HydroTileResult result(int tx, int tz) {
        return resolve(new HydroTileKey(seed, tx, tz, 0));
    }

    public HydroTileResult result(HydroTileKey key) {
        return resolve(key);
    }

    /** 字段缓存（纯函数，跨查询复用安全）；双检模式 —— 不在 map 锁内做重量采样。 */
    private HydroTileField field(HydroTileKey key) {
        long k = key.stableKey();
        HydroTileField hit = fieldCache.get(k);
        if (hit != null) return hit;
        HydroTileField fresh = HydroTileField.sample(key, cfg, sampler);
        HydroTileField prev = fieldCache.putIfAbsent(k, fresh);
        return prev != null ? prev : fresh;
    }

    private HydroTileTopology topology(HydroTileKey key) {
        long k = key.stableKey();
        HydroTileTopology hit = topoCache.get(k);
        if (hit != null) return hit;
        HydroTileTopology fresh = HydroTileTopology.build(field(key));
        HydroTileTopology prev = topoCache.putIfAbsent(k, fresh);
        return prev != null ? prev : fresh;
    }

    /**
     * 求解某 tile：收集上游闭包（结构规范）⇒ 零初值定点 ⇒ 只缓存根。
     *
     * <p>入口统一走此方法：{@link #result(HydroTileKey)} 只是别名，保证不存在
     * "读缓存的旁路"（旧实现里 result 与 resolve 两条路径的缓存语义不同）。</p>
     */
    public HydroTileResult resolve(HydroTileKey key) {
        long ck = key.stableKey();
        HydroTileResult hit = cache.get(ck);
        if (hit != null) return hit;

        // ① 闭包：从 T 沿 coreIngress（上游方向）BFS，半径 ≤ maxRings，固定插入序。
        //    环（端口回边）与超径截断都是结构属性 ⇒ 与查询顺序无关。
        List<HydroTileKey> closure = new ArrayList<>();
        ArrayDeque<HydroTileKey> queue = new ArrayDeque<>();
        Set<Long> enqueued = new LinkedHashSet<>();
        queue.add(key);
        enqueued.add(key.stableKey());
        while (!queue.isEmpty()) {
            HydroTileKey cur = queue.poll();
            closure.add(cur);
            int depth = depthOf(key, cur);
            if (depth >= cfg.maxRings()) { truncations.incrementAndGet(); continue; }   // 超径：结构截断（规范）
            List<HydroTileTopology.CoreEdge> ing = topology(cur).coreIngress();
            for (HydroTileTopology.CoreEdge e : ing) {
                HydroTileKey.PortId id = e.ingressPortId();
                HydroTileKey up = new HydroTileKey(seed,
                        HydroTileKey.tileOfCell(id.gx()), HydroTileKey.tileOfCell(id.gz()), 0);
                // ⚠ 已在 enqueued ≠ 成环：上游汇流（多条边指向同一上游 tile）也会走到这
                //   ——BFS 的 visited 本就防环，重复即跳过即可（实测误计 cycles=1013）。
                if (!enqueued.add(up.stableKey())) continue;
                queue.add(up);
            }
        }

        // ② 零初值 + 稳定键升序的 Gauss-Seidel 定点
        Map<Long, HydroTileBalance> ctx = new LinkedHashMap<>();
        int rounds = 0;
        boolean converged = false;
        for (; rounds < MAX_FIXED_POINT_ROUNDS; rounds++) {
            double maxDelta = 0;
            for (HydroTileKey t : closure) {                       // closure 本身为固定 BFS 序
                double[] ingress = ingressFor(t, ctx);
                HydroTileBalance old = ctx.get(t.stableKey());
                double oldIn = old == null ? 0 : old.boundaryIn;
                HydroTileBalance bal = HydroTileBalance.solve(topology(t), cfg, ingress);
                ctx.put(t.stableKey(), bal);
                buildCount.incrementAndGet();
                maxDelta = Math.max(maxDelta, Math.abs(bal.boundaryIn - oldIn));
            }
            if (maxDelta <= CONVERGE_TOL * Math.max(1.0,
                    ctx.get(key.stableKey()) == null ? 1 : ctx.get(key.stableKey()).boundaryIn)) {
                converged = true;
                break;
            }
        }
        if (!converged) nonConverged.incrementAndGet();

        HydroTileResult root = new HydroTileResult(key, cfg, topology(key), ctx.get(key.stableKey()));
        cache.put(ck, root);                                       // 只 memo 根
        return root;
    }

    /** 距查询根的切比雪夫距离（闭包半径，规范）。 */
    private static int depthOf(HydroTileKey root, HydroTileKey t) {
        return Math.max(Math.abs(t.tx() - root.tx()), Math.abs(t.tz() - root.tz()));
    }

    /** 为 tile t 构造入流数组：上游在 ctx 中取规范解，不在闭包/超径 ⇒ 0（保守省略）。 */
    private double[] ingressFor(HydroTileKey t, Map<Long, HydroTileBalance> ctx) {
        List<HydroTileTopology.CoreEdge> ing = topology(t).coreIngress();
        double[] flows = new double[ing.size()];
        for (int i = 0; i < ing.size(); i++) {
            HydroTileKey.PortId id = ing.get(i).ingressPortId();
            HydroTileKey up = new HydroTileKey(seed,
                    HydroTileKey.tileOfCell(id.gx()), HydroTileKey.tileOfCell(id.gz()), 0);
            HydroTileBalance ub = ctx.get(up.stableKey());
            if (ub == null) continue;                             // 本轮未算（零初值语义）⇒ 0
            flows[i] = ub.egressFlow(id);
        }
        return flows;
    }
}
