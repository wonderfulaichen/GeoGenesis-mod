package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 多层像素缓存引擎。
 * <p>
 * 存储 Cell 网格 + 各图层像素缓冲区（List&lt;int[]&gt; 动态扩展），
 * 图层切换无需重新采样。
 * <p>
 * <b>线程安全</b>：所有公开方法均 synchronized，Worker 线程写入 → MC 主线程读取，
 * 借助 synchronized 的 happens-before 保证内存可见性。
 * <p>
 * 槽位安排（与 {@link GeoPalette.PreviewLayer#ordinal()} 对齐）：
 * 0=ELEVATION, 1=TEMPERATURE, 2=HUMIDITY, 3=CONTINENTALITY,
 * 4=RELIEF, 5=LATITUDE, 6=CLIMATE_ZONE, 7=BIOME,
 * 8=TERRAIN_TYPE, 9=RIVER_NETWORK, 10=BIOME_REAL,
 * 11=ROCK_LAYER(预留), 12=ROCK_TYPE(预留), 13=VEIN_MAP(预留)
 *
 * @deprecated 已被 {@code chunk/} 包的 CellCache 取代。
 * PreviewDisplay 现在直接读写 CellCache，不再使用 PreviewCache。
 * 此类保留仅用于参考。
 */
@Deprecated
public final class PreviewCache {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final int LAYER_COUNT = GeoPalette.PreviewLayer.values().length;

    private final List<int[]> layerBuffers;
    private long viewportId = -1;
    private int texWidth;
    private int texHeight;
    private Cell[][] cells;
    /** 渐进式采样进度 0.0~1.0。Worker 每批更新。 */
    private volatile double progress = 0.0;

    public PreviewCache() {
        this.layerBuffers = new ArrayList<>(LAYER_COUNT);
        for (int i = 0; i < LAYER_COUNT; i++) {
            layerBuffers.add(null);
        }
    }

    // ============================================================
    // 视口管理
    // ============================================================

    /** 当前视口唯一标识。 */
    public synchronized long viewportId() { return viewportId; }

    /** 检查缓存是否匹配当前视口。 */
    public synchronized boolean matches(long id, int w, int h) {
        return viewportId == id && texWidth == w && texHeight == h;
    }

    /** 更新视口标识与 Cell 网格。 */
    public synchronized void setViewport(long id, int w, int h, Cell[][] grid) {
        LOGGER.info("[DIAG PreviewCache] setViewport id={} w={} h={} grid={}x{}",
                id, w, h, (grid != null ? grid.length : 0), (grid != null && grid.length > 0 ? grid[0].length : 0));
        this.viewportId = id;
        this.texWidth = w;
        this.texHeight = h;
        this.cells = grid;
    }

    /** 设置渐进式采样进度 [0,1]。 */
    public synchronized void setProgress(double p) { this.progress = p; }

    /** 获取当前采样进度 [0,1]。 */
    public synchronized double getProgress() { return progress; }

    /** 清除所有缓冲（视口变更时调用）。 */
    public synchronized void invalidate() {
        if (viewportId >= 0)
            LOGGER.info("[DIAG PreviewCache] invalidate oldId={}", viewportId);
        this.viewportId = -1;
        this.cells = null;
        this.progress = 0.0;
        for (int i = 0; i < LAYER_COUNT; i++) {
            layerBuffers.set(i, null);
        }
    }

    // ============================================================
    // 图层缓冲存取
    // ============================================================

    /** 获取指定图层的像素缓冲（可能为 null）。 */
    public synchronized int[] getLayerPixels(GeoPalette.PreviewLayer layer) {
        return layerBuffers.get(layer.ordinal());
    }

    /** 存储指定图层的像素数据。 */
    public synchronized void storeLayer(GeoPalette.PreviewLayer layer, int[] pixels) {
        if (pixels != null)
            LOGGER.info("[DIAG PreviewCache] storeLayer {} len={}", layer.name(), pixels.length);
        layerBuffers.set(layer.ordinal(), pixels);
    }

    /** 检查图层数据是否已就绪。 */
    public synchronized boolean isLayerReady(GeoPalette.PreviewLayer layer) {
        boolean ready = viewportId >= 0 && layerBuffers.get(layer.ordinal()) != null;
        return ready;
    }

    /** 获取 Cell 网格。 */
    public synchronized Cell[][] getCells() {
        return cells;
    }

    public synchronized int texWidth()  { return texWidth; }
    public synchronized int texHeight() { return texHeight; }

    // ============================================================
    // 便利方法
    // ============================================================

    /** 图层总数。 */
    public static int layerCount() { return LAYER_COUNT; }
}
