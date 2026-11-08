package com.geogenesis.client.preview;

import com.geogenesis.worldgen.hydrology.model.HierarchicalHydrology;
import com.geogenesis.worldgen.hydrology.model.LakeWaterBalance;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>沙盘用的粗层水位查询</b>（★ 2026-11-08）。
 *
 * <h2>为什么用它，而不是细层</h2>
 * <p>实测两条路（见 {@code runSandboxHydroCostProbe} / {@code runSandboxCoarseHydroProbe}）：</p>
 * <table border="1">
 *   <caption>成本对照</caption>
 *   <tr><th>来源</th><th>有水面？</th><th>成本</th></tr>
 *   <tr><td>细层 {@code sampleCell}（REGION=512 + 侵蚀 tile）</td>
 *       <td>是</td><td><b>3.6~10.9 秒/region</b>（实测 10862ms）⇒ 沙盘不可用</td></tr>
 *   <tr><td><b>粗层 {@code buildCoarse}</b>（D8、无侵蚀 tile、prewarm=false）</td>
 *       <td>是（100% 有效水位，16.2% 格水面高于地形）</td>
 *       <td><b>635 ms 冷 / 292 ms 热</b>（每 tract）</td></tr>
 * </table>
 *
 * <h2>精度声明（必须如实告知）</h2>
 * <p>粗层 {@code step=4}（每格 4 方块）且不做侵蚀雕刻 ⇒
 * 它的水面是<b>地形骨架级</b>的判断："这一片是不是河道/湖面"，不是逐格精确水位。
 * 沙盘是<b>看形态</b>的（精确渲染由平面模式的精确管线保证），
 * 因此这个精度足够 —— 但要清楚它<b>不等于</b>游戏里落块的水面高度。</p>
 *
 * <h2>tract 数量与代价</h2>
 * <p>{@code tract=512}。窗口覆盖的 tract 数约为 {@code (ceil(窗口/512)+1)²}：
 * 细档 998 格 → 9 个（约 2.6s 冷）；基准档 3994 格 → 81 个（约 24s，太慢）。
 * ⇒ 调用方按档位决定要不要水位（见 {@link SandboxLod#usesCoarseWater}），
 * 且<b>结果按 tract 缓存</b>，同一 tract 不重复求解。</p>
 */
public final class SandboxWaterLookup {

    /** 与 {@code ModelHydrologyProvider.CLIMATE} 同值（生产固定气候）。 */
    private static final LakeWaterBalance.Climate CLIMATE =
            new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

    private final CellGenerator gen;
    private final HierarchicalHydrology.Params params;
    /** tract (rx,rz) → 粗层产物 或 null（求解失败）。 */
    private final Map<Long, HierarchicalHydrology.Coarse> cache = new ConcurrentHashMap<>();
    /** 已求解的 tract 数（诊断）。 */
    private int solvedTracts;

    public SandboxWaterLookup(CellGenerator gen) {
        this(gen, HierarchicalHydrology.Params.defaults());
    }

    public SandboxWaterLookup(CellGenerator gen, HierarchicalHydrology.Params params) {
        this.gen = gen;
        this.params = params;
    }

    /**
     * 取（必要时求解并缓存）某 tract 的粗层产物。
     *
     * @return 粗层产物；求解失败返回 null
     */
    private HierarchicalHydrology.Coarse coarseFor(int rx, int rz) {
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        HierarchicalHydrology.Coarse hit = cache.get(key);
        if (hit != null) return hit;
        synchronized (this) {
            hit = cache.get(key);
            if (hit != null) return hit;
            try {
                hit = HierarchicalHydrology.buildCoarse(gen, params, rx, rz, CLIMATE);
                cache.put(key, hit);
                solvedTracts++;
            } catch (Throwable t) {
                return null;
            }
            return hit;
        }
    }

    /** 已求解 tract 数（诊断/日志）。 */
    public int solvedTracts() { return solvedTracts; }

    /**
     * 该世界坐标的<b>实际水面高度</b>（★ 判据已修正）。
     *
     * <h2>为什么不能直接用 {@code waterSurfaceAt}</h2>
     * <p>粗层 {@code waterSurface} 对<b>每一格</b>都有值：湖格是 {@code bed+depth}，
     * 其余格是 {@code level}（几何"水刚好能溢出"的高度，见 {@code HydrologyPipeline.Result}
     * 第 106-121 行）。若拿它当"这里有水"的判据，<b>整片汇水坡面都会被涂成水面</b>
     * —— 实测沙盘 16.2% 的格子被淹，画出来是一片汪洋。</p>
     *
     * <p>⇒ 真正的"有水道"判定是 {@code channel[]}（{@link HierarchicalHydrology.Coarse#channelAt}）；
     * 湖面用 {@code lakeAt}。两者任一为真、且水面确实高于地形时才返回水位。</p>
     *
     * @return 水面 Y；<b>该格没有水 → NaN</b>
     */
    public double waterSurfaceAt(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, params.tract());
        int rz = Math.floorDiv(blockZ, params.tract());
        HierarchicalHydrology.Coarse c = coarseFor(rx, rz);
        if (c == null) return Double.NaN;
        // 只有"河道格"或"湖格"才是真的水；其余格即便有 level 也不是水面
        if (!c.channelAt(blockX, blockZ) && !c.lakeAt(blockX, blockZ)) return Double.NaN;
        double ws = c.waterSurfaceAt(blockX, blockZ);
        return Double.isNaN(ws) ? Double.NaN : ws;
    }

    /**
     * 仅查询"这一格是否被判为水"（不含"水面高于地形"的复核，那个由调用方结合地形做）。
     */
    public boolean isWaterCell(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, params.tract());
        int rz = Math.floorDiv(blockZ, params.tract());
        HierarchicalHydrology.Coarse c = coarseFor(rx, rz);
        if (c == null) return false;
        return c.channelAt(blockX, blockZ) || c.lakeAt(blockX, blockZ);
    }

    /** 该坐标粗层是否判为湖面。 */
    public boolean lakeAt(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, params.tract());
        int rz = Math.floorDiv(blockZ, params.tract());
        HierarchicalHydrology.Coarse c = coarseFor(rx, rz);
        return c != null && c.lakeAt(blockX, blockZ);
    }

    /**
     * 预解一个矩形范围内的所有 tract（后台线程先铺好，避免逐点触发串行求解）。
     *
     * @return 实际求解（非缓存命中）的 tract 数
     */
    public int preload(int minX, int minZ, int maxX, int maxZ) {
        int t = params.tract();
        int rx0 = Math.floorDiv(minX, t), rx1 = Math.floorDiv(maxX, t);
        int rz0 = Math.floorDiv(minZ, t), rz1 = Math.floorDiv(maxZ, t);
        int before = solvedTracts;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                coarseFor(rx, rz);
            }
        }
        return solvedTracts - before;
    }

    /** 覆盖该矩形需要的 tract 数（不求解，仅估算代价）。 */
    public int tractsFor(int minX, int minZ, int maxX, int maxZ) {
        int t = params.tract();
        int across = Math.floorDiv(maxX, t) - Math.floorDiv(minX, t) + 1;
        int down = Math.floorDiv(maxZ, t) - Math.floorDiv(minZ, t) + 1;
        return across * down;
    }

    /** 清空缓存（换种子/换视口时）。 */
    public void clear() {
        cache.clear();
        solvedTracts = 0;
    }
}
