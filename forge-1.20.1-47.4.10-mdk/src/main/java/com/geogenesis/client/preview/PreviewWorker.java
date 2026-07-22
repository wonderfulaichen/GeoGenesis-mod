package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 预览后台异步采样引擎。
 * <p>
 * 单线程 ExecutorService + Future.cancel(true) 支持中断。
 * 提供多级分辨率渐进式渲染（先低分再高分）和坡度阴影后处理。
 *
 * @deprecated 已被 {@code chunk/} 包的 CellCache + TerrainQueue + TerrainPool 架构取代。
 * PreviewDisplay 现在直接通过 TerrainQueue 调度 ChunkWorkUnit，
 * 不再使用 PreviewWorker + PreviewCache 模式。此类保留仅用于参考。
 */
@Deprecated
public final class PreviewWorker {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    /** 分辨率等级。stride 由 compute() 动态计算，确保首次采样样本数 ≤ targetSamples。 */
    public enum Resolution {
        QUARTER(0),   // stride 动态计算
        HALF(0),
        FULL(0);      // 保留未使用

        public final int placeholder;
        Resolution(int placeholder) { this.placeholder = placeholder; }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GeoGenesis-PreviewWorker");
        t.setDaemon(true);
        return t;
    });

    private Future<?> currentTask;
    private final PreviewCache cache;
    private final GeoGenesisTerrain terrain;

    private boolean slopeShading;
    private boolean hydrology;
    private int minY;
    private int maxY;
    /** 每像素对应的世界块数 = viewportWorldWidth / texW。由 setPixelToWorldScale 设置。 */
    private double pixelToWorldScale = 1.0;
    /** 当前视口的世界坐标原点（采样时设置，供 computeLayer/fillLayerPixels 计算世界坐标）。 */
    private int currentOriginX, currentOriginZ;
    /** QUARTER 相位目标采样数上限（越大越精细，默认 576=24×24，每块 ~10 像素）。 */
    private static final int TARGET_SAMPLES = 576;
    /** 每完成 N 个采样点触发一次渐进式纹理上传（首批 ~50ms 可见）。 */
    private static final int BATCH_SIZE = 24;

    /** 图层像素缓冲计算完成回调。 */
    private Consumer<PreviewCache> onComplete;

    public PreviewWorker(GeoGenesisTerrain terrain, PreviewCache cache) {
        this.terrain = terrain;
        this.cache = cache;
    }

    // ============================================================
    // 配置
    // ============================================================

    public void setSlopeShading(boolean enabled) { this.slopeShading = enabled; }
    public boolean isSlopeShading() { return slopeShading; }

    public void setHydrology(boolean on) { this.hydrology = on; }
    public boolean isHydrology() { return hydrology; }

    /** 设置世界高度范围（用于 ELEVATION 图层色带映射）。 */
    public void setHeightRange(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    /** 设置每像素对应的世界块数。由 PreviewDisplay.requestResample 在视口变化时更新。
     *  上限 4.0：超过此值时 CellGenerator.sample 会触发上千个 chunk 生成（Voronoi 噪声 + 缓存抖动），
     *  远超预览实时性要求。超过则夹到 4.0，使预览区缩为 ~1024 块宽（约 64 chunk），
     *  在 CACHE_SIZE=4096 缓存下可一次装下，秒级渲染。 */
    public void setPixelToWorldScale(double s) { this.pixelToWorldScale = Math.min(s, 4.0); }

    public void setOnComplete(Consumer<PreviewCache> callback) { this.onComplete = callback; }

    // ============================================================
    // 调度
    // ============================================================

    /**
     * 为当前视口计算指定图层（+ 坡度阴影）。
     * 不重新采样 Cell 网格——若 cache 已有 grids 则复用。
     */
    public void computeLayer(GeoPalette.PreviewLayer layer) {
        Cell[][] grid = cache.getCells();
        if (grid == null) {
            // ★ 无网格数据时静默返回！不触发 onComplete。
            //   之前 bug：立即触发 onComplete → uploadFromCache → checkerboard
            return;
        }

        cancel();
        currentTask = executor.submit(() -> {
            try {
                int texW = cache.texWidth();
                int texH = cache.texHeight();
                int gw = grid.length;
                // 网格可能为 stride 分辨率（QUARTER 阶段），计算上采样因子
                int gridStride = Math.max(1, texW / Math.max(1, gw));

                int[] pixels = new int[texW * texH];
                if (gridStride > 1) {
                    // stride > 1：先算小缓冲再 upsampleNearest
                    int sw = texW / gridStride;
                    int sh = texH / gridStride;
                    int[] small = new int[sw * sh];
                    fillLayerPixels(small, layer, grid, sw, sh,
                            1, currentOriginX, currentOriginZ, pixelToWorldScale);
                    if (Thread.interrupted()) return;
                    upsampleNearest(small, sw, sh, pixels, texW, texH, gridStride);
                } else {
                    fillLayerPixels(pixels, layer, grid, texW, texH,
                            1, currentOriginX, currentOriginZ, pixelToWorldScale);
                    if (layer == GeoPalette.PreviewLayer.ELEVATION && slopeShading) {
                        applySlopeShading(pixels, grid, texW, texH);
                    }
                }
                if (Thread.interrupted()) return;
                cache.storeLayer(layer, pixels);
                if (onComplete != null) onComplete.accept(cache);
            } catch (Throwable t) {
                t.printStackTrace();
                if (onComplete != null) onComplete.accept(cache);
            }
        });
    }

    /**
     * 同步计算图层（仅在已有 cell grid 时可用）。
     * <p>
     * 用于 setMode 等需要即时响应的场景：在主线程直接计算像素并上传，
     * 不经过 worker 后台线程，避免"切换图层短暂空帧"或"感觉到在刷新"的问题。
     * 阻塞时间约 30-80ms（24×24 网格 + 256×256 上采样），MC 主线程可接受。
     * <p>
     * 注意：不调用 onComplete（避免 mc.execute 异步转回主线程）。
     *       调用方（setMode）应自己负责 uploadFromCache。
     */
    public void computeLayerSync(GeoPalette.PreviewLayer layer) {
        Cell[][] grid = cache.getCells();
        if (grid == null) {
            return;
        }

        int texW = cache.texWidth();
        int texH = cache.texHeight();
        int gw = grid.length;
        int gridStride = Math.max(1, texW / Math.max(1, gw));

        int[] pixels = new int[texW * texH];
        if (gridStride > 1) {
            int sw = texW / gridStride;
            int sh = texH / gridStride;
            int[] small = new int[sw * sh];
            fillLayerPixels(small, layer, grid, sw, sh,
                    1, currentOriginX, currentOriginZ, pixelToWorldScale);
            upsampleNearest(small, sw, sh, pixels, texW, texH, gridStride);
        } else {
            fillLayerPixels(pixels, layer, grid, texW, texH,
                    1, currentOriginX, currentOriginZ, pixelToWorldScale);
            if (layer == GeoPalette.PreviewLayer.ELEVATION && slopeShading) {
                applySlopeShading(pixels, grid, texW, texH);
            }
        }
        cache.storeLayer(layer, pixels);
    }

    /**
     * 提交视口采样任务（含 Cell 网格采样 + 图层像素填充）。
     * 会取消当前运行中的任务。
     */
    public void queue(long viewportId, int originX, int originZ, int texW, int texH,
                      GeoPalette.PreviewLayer layer, Resolution resolution) {
        cancel();
        currentTask = executor.submit(() -> {
            try {
                compute(viewportId, originX, originZ, texW, texH, layer, resolution);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                t.printStackTrace();
                if (onComplete != null) onComplete.accept(cache);
            }
        });
    }

    /**
     * 渐进式渲染：先用 QUARTER 分辨率快速预览，再用 FULL 精化。
     * 每次完成回调 UI。
     */
    public void queueProgressive(long viewportId, int originX, int originZ, int texW, int texH,
                                 GeoPalette.PreviewLayer layer) {
        cancel();
        currentTask = executor.submit(() -> {
            try {
                compute(viewportId, originX, originZ, texW, texH, layer, Resolution.QUARTER);
                // FULL 相位跳过：由于 CellGenerator.sample() 含 Voronoi 等耗时计算，
                // 每个 chunk 生成约 150ms。以 cap=4 视口仍有 2752 unique chunk → 7+ 分钟。
                // QUARTER 粗采样（stride=16）仅 160 chunk → ~24 秒，上采样后即显示。
                // 用户可通过拖拽/缩放触发新 compute，逐步细化解锁新区域。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                t.printStackTrace();
                if (onComplete != null) onComplete.accept(cache);
            }
        });
    }

    // ============================================================
    // 核心计算
    // ============================================================

    private void compute(long viewportId, int originX, int originZ, int texW, int texH,
                         GeoPalette.PreviewLayer layer, Resolution resolution)
            throws InterruptedException {

        this.currentOriginX = originX;
        this.currentOriginZ = originZ;

        // ===== 动态 stride：保证样本数 ≤ TARGET_SAMPLES =====
        // 样本数 = sampleW * sampleH ≈ texW*texH / stride²
        // stride = ceil(sqrt(texW*texH / TARGET_SAMPLES))
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) texW * texH / TARGET_SAMPLES)));
        int sampleW = Math.max(1, texW / stride);
        int sampleH = Math.max(1, texH / stride);
        // 每个网格采样点之间的世界块距 = 每像素块数 × 步长
        double step = pixelToWorldScale * stride;

        int totalSamples = sampleW * sampleH;
        LOGGER.info("[DIAG Worker] compute stride={} sample={}x{} total={} step={} origin={},{}",
                stride, sampleW, sampleH, totalSamples, step, originX, originZ);

        // ===== 渐进式采样：每完成 BATCH_SIZE 个采样点触发一次纹理上传 =====
        // 优点：用户无需等待全部完成，首批 ~50ms 即可见
        Cell[][] grid = new Cell[sampleW][sampleH];
        // 立即建立空视口（确保 isLayerReady 后续可用）
        cache.setViewport(viewportId, texW, texH, grid);
        cache.setProgress(0.0);

        int sampled = 0;
        for (int gx = 0; gx < sampleW; gx++) {
            for (int gz = 0; gz < sampleH; gz++) {
                int wx = originX + (int) Math.round(gx * step);
                int wz = originZ + (int) Math.round(gz * step);
                if (Thread.interrupted()) throw new InterruptedException();
                grid[gx][gz] = terrain.sampleCell(wx, wz);
                sampled++;

                // 每完成一批采样 → 触发渐进式上传
                if (sampled % BATCH_SIZE == 0 || sampled == totalSamples) {
                    int[] pixels = new int[texW * texH];
                    int[] small = new int[totalSamples];
                    fillLayerPixels(small, layer, grid, sampleW, sampleH,
                            1, originX, originZ, pixelToWorldScale);
                    upsampleNearest(small, sampleW, sampleH, pixels, texW, texH, stride);
                    cache.storeLayer(layer, pixels);
                    cache.setProgress((double) sampled / totalSamples);
                    if (onComplete != null) onComplete.accept(cache);
                }
            }
        }
        LOGGER.info("[DIAG Worker] sample done. layer={} progress=100%", layer.name());
    }

    // ============================================================
    // 图层像素填充
    // ============================================================

    /**
     * 将 Cell 网格数据填充到像素缓冲中。
     *
     * @param grid          Cell 网格（stride 或全分辨率）
     * @param w             目标缓冲宽度（像素数）
     * @param h             目标缓冲高度
     * @param gridStride    网格步长：1 表示 grid 与目标同分辨（直接索引），
     *                      否则每个网格点对应 gridStride×gridStride 像素块
     * @param originX       视口世界 X 原点
     * @param originZ       视口世界 Z 原点
     * @param sampleScale   每像素对应的世界块数
     */
    private void fillLayerPixels(int[] pixels, GeoPalette.PreviewLayer layer,
                                  Cell[][] grid, int w, int h, int gridStride,
                                  int originX, int originZ, double sampleScale) {
        int gw = grid.length;
        int gh = (gw > 0) ? grid[0].length : 0;
        double step = sampleScale;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int gx = px / gridStride;
                int gz = py / gridStride;
                int idx = py * w + px;

                if (gx >= gw || gz >= gh) {
                    pixels[idx] = 0;
                    continue;
                }

                Cell c = grid[gx][gz];
                if (c == null) {
                    pixels[idx] = 0;
                    continue;
                }

                // 计算像素对应的真实世界坐标（用于 LATITUDE 图层和 overlayTerrain）
                int wx = originX + (int) Math.round(px * step);
                int wz = originZ + (int) Math.round(py * step);
                pixels[idx] = GeoPalette.color(layer, c, wx, wz, minY, maxY, hydrology);
            }
        }
    }

    // ============================================================
    // 升采样（最近邻）
    // ============================================================

    private static void upsampleNearest(int[] src, int srcW, int srcH,
                                         int[] dst, int dstW, int dstH, int stride) {
        for (int py = 0; py < dstH; py++) {
            int sy = Math.min(py / stride, srcH - 1);
            for (int px = 0; px < dstW; px++) {
                int sx = Math.min(px / stride, srcW - 1);
                dst[py * dstW + px] = src[sy * srcW + sx];
            }
        }
    }

    // ============================================================
    // 坡度阴影
    // ============================================================

    /** 坡度阴影后处理：计算相邻像素高度差产生法线，与光源方向点乘得亮度乘数。 */
    private void applySlopeShading(int[] pixels, Cell[][] grid, int w, int h) {
        // 光源方向（左上→右下）
        float lx = 0.5f, ly = 1.0f, lz = 0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;

        int[] heights = new int[w * h];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                Cell c = (px < grid.length && py < grid[0].length) ? grid[px][py] : null;
                heights[py * w + px] = (c != null) ? (int) c.height : 0;
            }
        }

        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                int idx = py * w + px;
                float dhdx = (heights[py * w + px + 1] - heights[py * w + px - 1]) * 0.5f;
                float dhdz = (heights[(py + 1) * w + px] - heights[(py - 1) * w + px]) * 0.5f;
                // 法线 N = normalize(-dhdx, 1, -dhdz)
                float nx = -dhdx, ny = 1.0f, nz = -dhdz;
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nLen < 0.001f) continue;
                nx /= nLen; ny /= nLen; nz /= nLen;
                float diff = Math.max(0, nx * lx + ny * ly + nz * lz) * 0.6f + 0.4f;

                int c = pixels[idx];
                int r = Math.min(255, (int) (((c >> 16) & 0xFF) * diff));
                int g = Math.min(255, (int) (((c >> 8) & 0xFF) * diff));
                int b = Math.min(255, (int) ((c & 0xFF) * diff));
                pixels[idx] = (r << 16) | (g << 8) | b;
            }
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 取消当前任务。 */
    public void cancel() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
    }

    /** 关闭线程池。 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
