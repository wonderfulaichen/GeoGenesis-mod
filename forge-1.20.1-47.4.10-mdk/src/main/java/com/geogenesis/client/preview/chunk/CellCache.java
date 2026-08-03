package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.Cell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cell 级缓存（chunk × chunk → Cell[256]）。
 * <p>
 * 双层结构（对齐参考模组 PreviewStorage "内存全保留 + 磁盘备份" 的轻量化版）：
 * <ul>
 *   <li><b>渲染层 map</b>：当前视口附近的完整 Cell[256]，按需淘汰（OOM 防御，见
 *       {@link #trimToRect}）。渲染/坡度阴影/hover 都读这层。</li>
 *   <li><b>历史层 history</b>：所有曾采样过的 chunk 的<b>紧凑采样点</b>（每 chunk
 *       (16/stride)² 个 Cell，stride=16 时仅 1 个）——全量保留、永不淘汰，
 *       内存 = 磁盘文件量级（1:16 全视口 21 万 chunks ≈ 50MB）。
 *       <b>滑走又滑回时从历史层回填渲染层，不重新采样</b>（"加载过的保留"核心）。</li>
 * </ul>
 * 历史层由 {@link PreviewDiskCache} 持久化到磁盘，跨会话保留。
 */
public class CellCache {

    /** 渲染层缓存上限（chunk 数）。 */
    public static final int MAX_ENTRIES = 4096;

    private final ConcurrentHashMap<Long, Cell[]> map = new ConcurrentHashMap<>(1024);
    /** 渲染层每 chunk 的采样 stride（越小越精细）。 */
    private final ConcurrentHashMap<Long, Integer> strides = new ConcurrentHashMap<>(1024);
    /** 历史层：所有曾采样的 chunk 的紧凑采样点（全量保留，永不淘汰）。 */
    private final ConcurrentHashMap<Long, PreviewDiskCache.DiskChunk> history = new ConcurrentHashMap<>(1024);
    /** 数据版本号：任何 put/invalidate 都递增。渲染层据此判断"有新数据需要重画"（脏检查）。 */
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();

    public long version() { return version.get(); }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void put(int chunkX, int chunkZ, Cell[] cells) {
        put(chunkX, chunkZ, cells, 1);
    }

    public void put(int chunkX, int chunkZ, Cell[] cells, int stride) {
        if (cells != null && cells.length >= 256) {
            stride = Math.max(1, stride);
            // clone 确保读线程看到完整数组（写线程在 put 前已完成填充）
            map.put(key(chunkX, chunkZ), cells.clone());
            strides.put(key(chunkX, chunkZ), stride);
            // ★ 同步写入历史层（紧凑采样点，只存采样点不存 256 展开）
            history.put(key(chunkX, chunkZ),
                    PreviewDiskCache.DiskChunk.compact(chunkX, chunkZ, stride, cells));
            version.incrementAndGet();
        }
    }

    public Cell[] get(int chunkX, int chunkZ) {
        return map.get(key(chunkX, chunkZ));
    }

    public boolean contains(int chunkX, int chunkZ) {
        return map.containsKey(key(chunkX, chunkZ));
    }

    /**
     * 该 chunk 是否需要按所需 stride 重采。
     * 渲染层粒度够 → false（保留，秒显）。
     * 渲染层无 / 粒度更粗 → 查历史层：有 → <b>回填渲染层</b>（滑回秒显，不重新采样）→ false；
     * 历史层也无 → true（真正需要采样）。
     * 渲染层有但粒度更粗且历史层同粒度（需要细化）→ true（渐近细化）。
     */
    public boolean needsResample(int chunkX, int chunkZ, int requiredStride) {
        long k = key(chunkX, chunkZ);
        Integer have = strides.get(k);
        if (have != null && have <= requiredStride) {
            return false;  // 渲染层粒度足够（含刚回填的）
        }
        PreviewDiskCache.DiskChunk dc = history.get(k);
        if (dc != null) {
            if (dc.stride() <= requiredStride) {
                // 历史层粒度足够 → 回填渲染层（用历史层数据，可能比渲染层更细）。
                // ★ 必须 version++：脏检查依赖版本号变化才重画，否则滑回后画面不刷新。
                map.put(k, dc.cells().clone());
                strides.put(k, dc.stride());
                version.incrementAndGet();
                return false;
            }
            // 历史层也是粗数据 → 需要更细采样（细化任务完成后 put 覆盖两层）
            return true;
        }
        return true;
    }

    public void invalidateAll() {
        map.clear();
        strides.clear();
        history.clear();
        version.incrementAndGet();
    }

    public int chunkCount() { return map.size(); }

    /** 历史层全部条目（供磁盘保存用）。 */
    public Map<Long, PreviewDiskCache.DiskChunk> historyEntries() { return history; }

    /** 导入磁盘加载的历史（只进历史层，渲染层由 needsResample 按需回填）。 */
    public void importHistory(long key, PreviewDiskCache.DiskChunk dc) {
        if (dc != null) {
            history.put(key, dc);
        }
    }

    /**
     * 快速读写：以 (chunkX, chunkZ, localX, localZ) 索引一个 Cell
     * @return 该位置的 Cell，或 null（未缓存/超出范围）
     */
    public Cell getCell(int chunkX, int chunkZ, int localX, int localZ) {
        Cell[] arr = map.get(key(chunkX, chunkZ));
        if (arr == null) return null;
        return arr[localZ * 16 + localX];
    }

    /**
     * 容量兜底（渲染层矩形窗口淘汰）：超过 maxEntries 时，先淘汰视口矩形外的 chunk（按到矩形
     * 边缘的距离），矩形内数据永不淘汰。历史层不受影响——被淘汰的 chunk 滑回时经
     * {@link #needsResample} 从历史层回填（不重新采样）。
     */
    public void trimToRect(int maxEntries, int minCX, int minCZ, int maxCX, int maxCZ) {
        if (map.size() <= maxEntries) return;
        List<Map.Entry<Long, Cell[]>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Long.compare(
                rectDist(keyCX(b.getKey()), keyCZ(b.getKey()), minCX, minCZ, maxCX, maxCZ),
                rectDist(keyCX(a.getKey()), keyCZ(a.getKey()), minCX, minCZ, maxCX, maxCZ)));
        int removeCount = entries.size() - maxEntries;
        for (int i = 0; i < removeCount; i++) {
            map.remove(entries.get(i).getKey());
            strides.remove(entries.get(i).getKey());
        }
    }

    /** chunk 到视口矩形的距离：矩形内 = 0；矩形外 = 到最近边缘的 Chebyshev 距离平方。 */
    private static long rectDist(int cx, int cz, int minCX, int minCZ, int maxCX, int maxCZ) {
        long dx = cx < minCX ? (long) minCX - cx : (cx > maxCX ? (long) cx - maxCX : 0);
        long dz = cz < minCZ ? (long) minCZ - cz : (cz > maxCZ ? (long) cz - maxCZ : 0);
        return dx * dx + dz * dz;
    }

    private static int keyCX(long k) { return (int) (k >> 32); }
    private static int keyCZ(long k) { return (int) (k & 0xFFFFFFFFL); }
}