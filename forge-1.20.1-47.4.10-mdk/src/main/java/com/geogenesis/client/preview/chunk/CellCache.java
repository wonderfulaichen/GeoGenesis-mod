package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.Cell;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cell 级缓存（chunk × chunk → Cell[256]）。
 * <p>
 * 由 ChunkWorkUnit 写入，由 PreviewDisplay 读取用于渲染。
 * 与 ChunkStorage 并存：一个存原始值，一个存 Cell 对象。
 */
public class CellCache {

    private final ConcurrentHashMap<Long, Cell[]> map = new ConcurrentHashMap<>(1024);

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void put(int chunkX, int chunkZ, Cell[] cells) {
        if (cells != null && cells.length >= 256) {
            // clone 确保读线程看到完整数组（写线程在 put 前已完成填充）
            map.put(key(chunkX, chunkZ), cells.clone());
        }
    }

    public Cell[] get(int chunkX, int chunkZ) {
        return map.get(key(chunkX, chunkZ));
    }

    public boolean contains(int chunkX, int chunkZ) {
        return map.containsKey(key(chunkX, chunkZ));
    }

    public void invalidateAll() {
        map.clear();
    }

    public int chunkCount() { return map.size(); }

    /**
     * 快速读写：以 (chunkX, chunkZ, localX, localZ) 索引一个 Cell
     * @return 该位置的 Cell，或 null（未缓存/超出范围）
     */
    public Cell getCell(int chunkX, int chunkZ, int localX, int localZ) {
        Cell[] arr = map.get(key(chunkX, chunkZ));
        if (arr == null) return null;
        return arr[localZ * 16 + localX];
    }
}
