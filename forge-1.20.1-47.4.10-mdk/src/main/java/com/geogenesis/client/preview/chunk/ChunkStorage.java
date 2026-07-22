package com.geogenesis.client.preview.chunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 累积式 Chunk 数据存储（对标参考项目 PreviewStorage）。
 * <p>
 * 按 (chunkX, chunkZ, layerFlag) 索引存储 16×16 short 网格。
 * 每个 entry 对应一个 MC chunk（16×16 块）的原始采样数据。
 * <p>
 * layerFlag 区分不同数据层：0=群系, 2=高度, 9=温度, 10=湿度, …
 * 一次采样可同时写入多个 flag。
 */
public final class ChunkStorage {

    /** 每个 chunk 的 block 数量 = 16×16 */
    public static final int CHUNK_SIZE = 256;

    /** key = pack(chunkX, chunkZ, flag) → data[256] */
    private final ConcurrentHashMap<Long, short[]> storage = new ConcurrentHashMap<>(1024);

    /** 打包 long key：chunkX(26) | chunkZ(26) | flag(12) */
    private static long key(int chunkX, int chunkZ, int flag) {
        return ((long) chunkX & 0x3FFFFFF) << 38
             | ((long) chunkZ & 0x3FFFFFF) << 12
             | (flag & 0xFFF);
    }

    /** 解包 chunkX */
    public static int unpackChunkX(long key) {
        return (int) (key >> 38);
    }

    /** 解包 chunkZ */
    public static int unpackChunkZ(long key) {
        return (int) (key >> 12) << 26 >> 26;
    }

    /** 解包 flag */
    public static int unpackFlag(long key) {
        return (int) (key & 0xFFF);
    }

    // ====== 读写 ======

    /** 获取指定 chunk+flag 的数据（null = 尚未采样） */
    public short[] get(int chunkX, int chunkZ, int flag) {
        return storage.get(key(chunkX, chunkZ, flag));
    }

    /** 检查指定 chunk+flag 是否已采样 */
    public boolean isReady(int chunkX, int chunkZ, int flag) {
        return storage.containsKey(key(chunkX, chunkZ, flag));
    }

    /** 存储 16×16 = 256 个 short 到指定 chunk+flag */
    public void put(int chunkX, int chunkZ, int flag, short[] data) {
        if (data != null && data.length >= CHUNK_SIZE) {
            storage.put(key(chunkX, chunkZ, flag), data);
        }
    }

    /** 存储单个 block 值到指定 chunk+flag（线程安全，自动创建数组） */
    public void setBlock(int chunkX, int chunkZ, int flag, int localX, int localZ, short value) {
        long k = key(chunkX, chunkZ, flag);
        short[] data = storage.computeIfAbsent(k, _k -> {
            short[] arr = new short[CHUNK_SIZE];
            // 用 -32768 标记"未采样"
            java.util.Arrays.fill(arr, Short.MIN_VALUE);
            return arr;
        });
        int idx = localZ * 16 + localX;
        if (idx >= 0 && idx < CHUNK_SIZE) {
            data[idx] = value;
        }
    }

    /** 单个 block 读取 */
    public short getBlock(int chunkX, int chunkZ, int flag, int localX, int localZ) {
        short[] data = get(chunkX, chunkZ, flag);
        if (data == null) return Short.MIN_VALUE;
        int idx = localZ * 16 + localX;
        return (idx >= 0 && idx < CHUNK_SIZE) ? data[idx] : Short.MIN_VALUE;
    }

    // ====== 存储层管理 ======

    /** 清除指定 flag 的所有数据（图层/分辨率切换时） */
    public void invalidateFlag(int flag) {
        storage.keySet().removeIf(k -> unpackFlag(k) == flag);
    }

    /** 清除所有数据 */
    public void invalidateAll() {
        storage.clear();
    }

    /** 当前缓存的 block 数（近似） */
    public int cachedBlockCount() {
        return storage.size() * CHUNK_SIZE;
    }

    /** 当前缓存的 chunk 数 */
    public int cachedChunkCount() {
        return storage.size();
    }
}
