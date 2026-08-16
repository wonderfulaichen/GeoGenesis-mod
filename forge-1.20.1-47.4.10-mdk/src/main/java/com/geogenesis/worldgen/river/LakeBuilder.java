package com.geogenesis.worldgen.river;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 洼地填水湖泊（汇水分析驱动 · 阶段 B）。
 *
 * <p><b>盆地语义</b>：D8 流向场中 {@code dir==-1} 的格点 = 洼地中心（局部最低，
 * 阶段 A 支流 sink 终止点）。以中心为根做 8 邻 BFS 粗盆（蓄水深度上限
 * {@link #MAX_DEPTH}），边界（盆外邻点）最低 e = <b>溢出口</b>（水满溢出高度）；
 * 溢出口 &gt; 盆底（有蓄水）= 封闭盆地 → 湖。湖面 Y = 溢出口 e → HeightCurve。
 * 内流盆地（无更低出口）自然填湖；有更低出口的"假洼地"（e 微差噪声）不蓄水。</p>
 *
 * <p><b>确定性铁律</b>（结构性无缝根）：一切判定只依赖世界坐标 + e 场 +
 * D8 流向场（均为纯函数），tile 扫描顺序与 BFS 顺序固定 → 任意时序构建
 * 结果一致。盆地中心 key 全局缓存（{@link #basins}）；采样查 3×3 tile
 * 列表（中心可能在邻 tile，3×3 覆盖跨 tile 盆地，无漏检）。</p>
 */
final class LakeBuilder {

    /** tile 边长（wu，与 RiverNetwork.BASIN_SIZE 对齐） */
    static final int SIZE = 128;
    /** 格点间距（wu，= FlowField 网格） */
    static final int STEP = 4;
    /** 凹陷检测半径（wu）：8wu 8 邻（★ 实锤：seed 12345/98765 地形 e 场在
     *  4-16wu 尺度无深凹陷（低频平滑场），洼地深度全 &lt;0.002e → 深门槛 0 湖；
     *  0.002 = 微洼地（0.2 块）也成浅湖（水洼），湖盆雕刻补深度） */
    static final int DEPTH_SCAN = 8;
    /** 凹陷门槛（e ≈ 0.2 块）：中心 e &lt; 8wu 邻最低 − 0.002 才算凹陷中心 */
    static final double DEPTH_MIN = 0.002;
    /** 蓄水深度上限（e；≈10 块，截断大低地） */
    static final double MAX_DEPTH = 0.1;
    /** 湖盆雕刻最小深（Y 块）：水面 = 溢出口（真实水位），湖底至少下挖 1.5 块
     *  → 微洼地也成视觉清晰湖（物理：挖深不影响水面） */
    static final double MIN_BED_DEPTH = 1.5;
    /** 盆地面积上限（4wu 格数；512 格 ≈ 半径 50wu 湖，大低地截断防 OOM） */
    static final int MAX_CELLS = 512;
    /** 湖列表缓存上限（每 tile ~10-40 盆地，防 OOM） */
    private static final int CACHE_MAX = 2048;

    /** 湖泊盆地（构建期确定，不可变） */
    record Basin(double cx, double cz, double waterY, double bedY, double radius,
                 Set<Long> cells, int cellCount) {}

    private final RiverNetwork net;
    /** tile → 本 tile 内洼地中心的湖盆地列表 */
    private final Map<Long, List<Basin>> tileLakes = new ConcurrentHashMap<>();
    /** 洼地中心格 key → 盆地（全局去重：跨 tile 同盆地只建一次） */
    private final Map<Long, Basin> basins = new ConcurrentHashMap<>();

    LakeBuilder(RiverNetwork net) {
        this.net = net;
    }

    /** 世界坐标 → 湖泊盆地（null = 无湖）。3×3 tile 列表查询（跨 tile 无漏检）。 */
    Basin sample(double wx, double wz) {
        int tx = floorDiv((int) Math.floor(wx), SIZE);
        int tz = floorDiv((int) Math.floor(wz), SIZE);
        long cellKey = pack((int) Math.floor(wx / STEP), (int) Math.floor(wz / STEP));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (Basin b : lakesFor(tx + dx, tz + dz)) {
                    if (b.cells.contains(cellKey)) return b;
                }
            }
        }
        return null;
    }

    /** tile → 湖盆地列表（缓存；扫描本 tile 格点找洼地中心 → 最深 16 个建盆）。
     *  ★ 全量建盆实锤：seed 98765 崎岖地形每 tile 几十洼地中心 × BFS 12 轮 ×
     *  512 格 → 百万级 eAt/tile → 卡死；按 e 升序（最深优先）截断 16 个。 */
    List<Basin> lakesFor(int tx, int tz) {
        long key = pack(tx, tz);
        List<Basin> cached = tileLakes.get(key);
        if (cached != null) return cached;
        List<Basin> list = new ArrayList<>();
        int n = SIZE / STEP;
        double ox = tx * SIZE, oz = tz * SIZE;
        List<double[]> sinks = new ArrayList<>();
        for (int iz = 0; iz <= n; iz++) {
            for (int ix = 0; ix <= n; ix++) {
                double wx = ox + ix * STEP, wz = oz + iz * STEP;
                if (net.flowDirAt(wx, wz) >= 0) continue; // 非洼地
                double e = net.eAt(wx, wz);
                if (e > 0) sinks.add(new double[]{e, wx, wz});
            }
        }
        sinks.sort((a, b) -> Double.compare(a[0], b[0])); // e 升序 = 最深优先
        int built = 0;
        for (double[] s : sinks) {
            if (built >= 16) break;
            Basin b = buildBasin(s[1], s[2]);
            if (b != null) {
                list.add(b);
                built++;
            }
        }
        List<Basin> prev = tileLakes.putIfAbsent(key, List.copyOf(list));
        prune();
        return prev != null ? prev : List.copyOf(list);
    }

    /**
     * 洼地中心 → 盆地（一次性 BFS + 封闭判定）。纯函数。
     *
     * <p>算法：从中心 BFS 收 e ≤ 盆底 + MAX_DEPTH 的连通格点（<b>过程中检查
     * 面积上限</b> → 大低地/平原直接截断返回 null，无 OOM）；同时记录
     * <b>盆外最低点</b> outE（溢出口候选）与<b>盆内最高点</b> maxIn。
     * 封闭盆地判定：outE &gt; maxIn（缺口高于盆内一切 → 水满不漏）且
     * outE − 盆底 ≥ 蓄水门槛。湖面 = min(outE, 盆底+MAX_DEPTH) 截断。
     * 复杂度 O(n)，n ≤ 512 格——★ 迭代版（12 轮重建）在 seed 98765 崎岖
     * 地形卡死/OOM 实锤。</p>
     */
    private Basin buildBasin(double wx, double wz) {
        long ck = pack((int) Math.floor(wx / STEP), (int) Math.floor(wz / STEP));
        Basin cached = basins.get(ck);
        if (cached != null) return cached;
        double ce = net.eAt(wx, wz);
        if (ce <= 0.0) return null; // 海底洼地不算湖（湖泊 = 陆地）
        // ★ 凹陷门槛：4wu 微洼地（噪声级，深度 <0.002e）不成湖——真凹陷
        //   = DEPTH_SCAN 邻最低比中心低 ≥DEPTH_MIN
        double minS = Double.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            minS = Math.min(minS, net.eAt(wx + FlowField.DX8[k] * DEPTH_SCAN,
                wz + FlowField.DZ8[k] * DEPTH_SCAN));
        }
        if (ce > minS - DEPTH_MIN) return null; // 非凹陷（平缓/斜坡）
        // ---- 一次性 BFS（面积截断 + outE/maxIn 记录）----
        Set<Long> cells = new HashSet<>();
        ArrayDeque<long[]> q = new ArrayDeque<>();
        long[] root = {(long) Math.floor(wx / STEP), (long) Math.floor(wz / STEP)};
        cells.add(pack(root[0], root[1]));
        q.add(root);
        double outE = Double.MAX_VALUE, maxIn = ce;
        double ceil = ce + MAX_DEPTH;
        while (!q.isEmpty()) {
            long[] g = q.poll();
            for (int k = 0; k < 8; k++) {
                long nx = g[0] + FlowField.DX8[k], nz = g[1] + FlowField.DZ8[k];
                long nk = pack(nx, nz);
                if (cells.contains(nk)) continue;
                double e = net.eAt(nx * STEP, nz * STEP);
                if (e <= ceil) {
                    if (cells.size() >= MAX_CELLS) return null; // 大低地截断（非湖）
                    cells.add(nk);
                    q.add(new long[]{nx, nz});
                    maxIn = Math.max(maxIn, e);
                } else {
                    outE = Math.min(outE, e); // 盆外最低（溢出口候选）
                }
            }
        }
        // ---- 封闭判定：缺口（outE）高于盆底 → 蓄水（水面 = 缺口）----
        if (outE <= ce + DEPTH_MIN) return null; // 缺口 ≤ 盆底 → 无蓄水
        double waterE = Math.min(outE, ceil);
        // 精筛：水面以上的格点（outE 截断时 cells 可能含 e > waterE）
        if (waterE < ceil) {
            cells.removeIf(c -> { // c 是 pack 后的格 key
                long gx = c >> 32, gz = c & 0xFFFFFFFFL;
                return net.eAt(gx * STEP, gz * STEP) > waterE;
            });
        }
        double waterY = net.curve().heightFromE(waterE);
        // ★ 湖盆雕刻：湖底至少 MIN_BED_DEPTH 块深（微洼地水面≈盆底 → 挖深成湖）
        double bedY = Math.min(net.curve().heightFromE(ce), waterY - MIN_BED_DEPTH);
        double radius = Math.sqrt(cells.size() / Math.PI) * STEP;
        Basin b = new Basin(wx, wz, waterY, bedY, radius, cells, cells.size());
        Basin prev = basins.putIfAbsent(ck, b);
        return prev != null ? prev : b;
    }

    /** prune：tile 列表 + 盆地全局缓存（★ OOM 实锤：seed 98765 崎岖地形盆地数万
     *  × 每盆 512 格 × Long 装箱 → 堆爆；两处都要有界） */
    private void prune() {
        if (tileLakes.size() > CACHE_MAX) {
            var it = tileLakes.keySet().iterator();
            int toRemove = Math.max(1, tileLakes.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        if (basins.size() > CACHE_MAX) {
            var it = basins.keySet().iterator();
            int toRemove = Math.max(1, basins.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    static int floorDiv(int v, int d) {
        int q = v / d;
        return (v % d != 0 && ((v ^ d) < 0)) ? q - 1 : q;
    }

    private static long pack(long a, long b) {
        return (a << 32) | (b & 0xFFFFFFFFL);
    }
}
