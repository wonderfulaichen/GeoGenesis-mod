package com.geogenesis.client.preview.chunk;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.TerrainClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 预览磁盘持久化（对标参考模组 PreviewStorageCacheManager：Java 序列化 + Zip + tmp 原子写）。
 * <p>
 * 让"已加载的地图被记录"：同一 seed 再次打开预览时，之前浏览过的位置直接从磁盘加载，
 * 不需要重新采样（"加载历史"体验）。配合 {@link CellCache#history}（会话内历史层），
 * 滑走又滑回 = 历史层回填秒显，跨会话 = 磁盘加载秒显。
 *
 * <p>格式（DataOutputStream + Deflater 压缩，比参考模组的 ObjectOutputStream 快且不受
 * Cell 内部结构演化影响）：
 * <pre>
 * header: MAGIC "GGPC" + VERSION(1) + seed(long) + configHash(long) + chunkCount(int)
 * per chunk: chunkX(int) chunkZ(int) stride(int) + (16/stride)² 个采样点 CellData
 * CellData: e height temperature humidity continentNoise shape
 *           riverNetDist riverNetDischarge riverDistance riverWetness  (10 × float)
 *           terrainType(byte) riverSourceType(byte) flags(byte)
 * </pre>
 * 只存采样点而非 16×16=256 格：stride=16 时每 chunk 仅 1 点（1:16 视口 21 万 chunks ≈ 12MB）。
 *
 * <p>configHash = TerrainParams.hashCode()：地形参数改动后旧缓存自动失效（读取时 hash 不匹配
 * 则返回空，重新采样）。
 */
public final class PreviewDiskCache {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final String MAGIC = "GGPC";
    private static final int VERSION = 1;
    private static final Path CACHE_DIR = Path.of("config/geogenesis/preview_cache");

    private static final int FLAG_LAKE_MASK = 2;
    private static final int FLAG_SNOW = 4;
    private static final int FLAG_EROSION = 16;

    /**
     * 一 chunk 的紧凑数据：只存 (16/stride)² 个采样点（stride=16 时 1 个），
     * 按 (lz,lx) 步进 stride 的顺序排列。渲染层需要时经 {@link #cells()} 展开为 16×16。
     */
    public record DiskChunk(int chunkX, int chunkZ, int stride, Cell[] samples) {

        /**
         * 从 16×16 展开数组提取紧凑采样点。
         * ★ 2026-08-08：统一 X 主序（cells[lx*16+lz]，与 GeoGenesisTerrain 一致）。
         *   此前 Z 主序读 + Z 主序展开 → 历史层回填渲染层时数据转置 → 16 块间距网格。
         */
        public static DiskChunk compact(int cx, int cz, int stride, Cell[] cells256) {
            int s = Math.max(1, stride);
            Cell[] samples = new Cell[(16 / s) * (16 / s)];
            int idx = 0;
            for (int lz = 0; lz < 16; lz += s) {
                for (int lx = 0; lx < 16; lx += s) {
                    samples[idx++] = cells256[lx * 16 + lz];
                }
            }
            return new DiskChunk(cx, cz, s, samples);
        }

        /** 展开为 16×16 Cell 数组（每个采样点覆盖 stride×stride 格，X 主序）。 */
        public Cell[] cells() {
            int s = stride;
            Cell[] out = new Cell[256];
            int idx = 0;
            for (int lz = 0; lz < 16; lz += s) {
                for (int lx = 0; lx < 16; lx += s) {
                    Cell c = samples[idx++];
                    int endX = Math.min(lx + s, 16);
                    int endZ = Math.min(lz + s, 16);
                    for (int ez = lz; ez < endZ; ez++) {
                        for (int ex = lx; ex < endX; ex++) {
                            out[ex * 16 + ez] = c;
                        }
                    }
                }
            }
            return out;
        }
    }

    private PreviewDiskCache() {}

    private static Path fileFor(long seed) {
        return CACHE_DIR.resolve("preview_" + seed + ".bin");
    }

    /**
     * 从磁盘加载指定 seed 的缓存。文件不存在 / 版本不符 / configHash 不符 → 返回空（重新采样）。
     */
    public static Map<Long, DiskChunk> load(long seed, long configHash) {
        Path file = fileFor(seed);
        if (!Files.exists(file)) return Map.of();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
            if (!in.readUTF().equals(MAGIC)) return Map.of();
            if (in.readInt() != VERSION) return Map.of();
            if (in.readLong() != seed) return Map.of();
            if (in.readLong() != configHash) {
                LOGGER.info("[PreviewCache] config changed, ignoring cache {}", file);
                return Map.of();
            }
            int count = in.readInt();
            Map<Long, DiskChunk> out = new HashMap<>(Math.max(16, count));
            for (int i = 0; i < count; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                int stride = Math.max(1, Math.min(16, in.readInt()));
                int samplesCount = (16 / stride) * (16 / stride);
                Cell[] samples = new Cell[samplesCount];
                for (int j = 0; j < samplesCount; j++) {
                    samples[j] = readCell(in);
                }
                out.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), new DiskChunk(cx, cz, stride, samples));
            }
            LOGGER.info("[PreviewCache] loaded {} chunks from {}", out.size(), file);
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[PreviewCache] failed to read {}, ignoring", file, e);
            return Map.of();
        }
    }

    /**
     * 保存历史层全部内容到磁盘（tmp 原子写）。调用方负责放后台线程。
     */
    public static void save(long seed, long configHash, Map<Long, DiskChunk> history) {
        if (history == null || history.isEmpty()) return;
        try {
            Files.createDirectories(CACHE_DIR);
            Path target = fileFor(seed);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp)))) {
                out.writeUTF(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(seed);
                out.writeLong(configHash);
                out.writeInt(history.size());
                for (DiskChunk dc : history.values()) {
                    out.writeInt(dc.chunkX());
                    out.writeInt(dc.chunkZ());
                    out.writeInt(dc.stride());
                    for (Cell c : dc.samples()) {
                        writeCell(out, c == null ? new Cell() : c);
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[PreviewCache] saved {} chunks to {}", history.size(), target);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[PreviewCache] failed to save cache", e);
        }
    }

    /** 删除该 seed 的缓存（"清除缓存"按钮用）。 */
    public static void clearFor(long seed) {
        try {
            Files.deleteIfExists(fileFor(seed));
        } catch (IOException e) {
            LOGGER.warn("[PreviewCache] failed to clear cache for seed {}", seed, e);
        }
    }

    // ============================================================
    //  序列化
    // ============================================================

    private static void writeCell(DataOutputStream out, Cell c) throws IOException {
        out.writeFloat((float) c.e);
        out.writeFloat((float) c.height);
        out.writeFloat((float) c.temperature);
        out.writeFloat((float) c.humidity);
        out.writeFloat((float) c.continentNoise);
        out.writeFloat((float) c.shape);
        out.writeByte(c.terrainType == null ? 0 : c.terrainType.ordinal());
        int flags = 0;
        if (c.lakeMask) flags |= FLAG_LAKE_MASK;
        if (c.isSnow) flags |= FLAG_SNOW;
        if (c.erosionMask) flags |= FLAG_EROSION;
        out.writeByte(flags);
    }

    private static Cell readCell(DataInputStream in) throws IOException {
        Cell c = new Cell();
        c.e = in.readFloat();
        c.height = in.readFloat();
        c.temperature = in.readFloat();
        c.humidity = in.readFloat();
        c.continentNoise = in.readFloat();
        c.shape = in.readFloat();
        int tIdx = in.readByte();
        TerrainClass[] tcs = TerrainClass.values();
        c.terrainType = (tIdx >= 0 && tIdx < tcs.length) ? tcs[tIdx] : TerrainClass.OCEAN;
        int flags = in.readByte();
        c.lakeMask = (flags & FLAG_LAKE_MASK) != 0;
        c.isSnow = (flags & FLAG_SNOW) != 0;
        c.erosionMask = (flags & FLAG_EROSION) != 0;
        // 兼容旧 API：同步别名字段
        c.isLake = c.lakeMask;
        c.continent = c.continentNoise;
        return c;
    }
}
