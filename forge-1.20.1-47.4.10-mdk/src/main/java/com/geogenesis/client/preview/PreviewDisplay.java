package com.geogenesis.client.preview;

import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.chunk.TerrainPool;
import com.geogenesis.client.preview.chunk.TerrainQueue;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainClass;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

/**
 * 地形预览（参考 World-Preview-TFC 重构版）。
 *
 * <p>每帧执行：fillRect(底色) → queueGeneration → 覆盖已缓存 chunk → upload → blit。
 * 纹理始终上传和 blit，有数据就有画面，无数据显示底色。
 */
public class PreviewDisplay extends AbstractWidget {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");

    // ====== 存储 & 采样 ======
    private final CellCache cellCache = new CellCache();
    private final TerrainPool pool = new TerrainPool(4);
    private TerrainQueue queue;
    private GeoGenesisTerrain terrain;

    // ====== 纹理 ======
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation texLoc;
    private int texW, texH;
    /** 每纹理像素对应的世界块数。越大越缩。 */
    public int scaleBlockPos = 1;
    /** blockStride：每个 chunk 采样的步长。16=1 sample/chunk（最快），4=16 samples/chunk（精细） */
    public int blockStride;

    // ====== 视口 ======
    /** 视口中心世界坐标 */
    private int centerX = 0, centerZ = 0;
    /** 拖拽累积偏移（松手后并入 center） */
    private double totalDragX = 0, totalDragZ = 0;

    // ====== 图层 ======
    private GeoPalette.PreviewLayer activeLayer = GeoPalette.PreviewLayer.ELEVATION;

    // ====== UI ======
    private boolean showLegend = true;
    private int legendScrollOffset = 0;
    private int legendSortMode = 0;
    private static final String[] LEGEND_SORT_NAMES = {"默认", "名称", "分类"};
    /** 图例搜索：非空时过滤条目 */
    private String legendSearchQuery = "";
    /** 搜索框是否激活（接收键盘输入） */
    private boolean legendSearchActive = false;
    /** 图例是否展开（false 时只显示标题条） */
    private boolean legendExpanded = true;
    private int hoverX = -1, hoverZ = -1;

    // ====== 交互增强 ======
    /** 帧时间统计（最近 30 帧的毫秒数） */
    private final Queue<Long> frameTimes = new ArrayDeque<>();
    /** 右键复制坐标的消息（非 null 时显示） */
    private Component copiedToastMsg = null;
    /** 右键复制消息的创建时间 */
    private Instant copiedToastTime = null;
    /** 群系高亮选中 id（-1=未选中） */
    private int selectedBiomeId = -1;

    // ====== 数据 ======
    private long seed;
    private int seaLevel, maxY, minY, mountainCap;
    private boolean hydrology = false;
    private boolean slopeShading = false;
    /** 视口变化标记：需要清除纹理重新填充。pan/zoom/seed 变时设 true */
    public boolean needsClear = true;

    private final Minecraft mc = Minecraft.getInstance();

    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode) {
        this(x, y, w, h, terrain, seed, params, mode, 16);
    }

    /**
     * 完整构造函数，允许指定采样步长。
     * @param blockStride 采样步长：16=每chunk 1次采样(最快), 4=每chunk 16次(精细)
     */
    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode, int blockStride) {
        super(x, y, w, h, Component.literal("GeoGenesis Preview"));
        this.terrain = terrain;
        this.seed = seed;
        this.seaLevel = params.seaLevel();
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        this.maxY = params.maxY();
        this.blockStride = Math.max(1, Math.min(16, blockStride));
        // 图例色带范围使用世界高度上限 maxY（默认 320），而非 mountainCap（256），
        // 否则 256-307 之间的地形全部被裁剪成白色
        GeoPalette.setElevationRange(minY, maxY);
        GeoPalette.setSeaLevel(seaLevel);
        // 高分辨率纹理：widget_width × gui_scale，硬件自动下采样到 widget 大小
        texW = Math.max(1, (int)(this.width * mc.getWindow().getGuiScale()));
        texH = Math.max(1, (int)(this.height * mc.getWindow().getGuiScale()));
        image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
        texture = new DynamicTexture(image);
        texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
        mc.getTextureManager().register(texLoc, texture);

        this.queue = new TerrainQueue(cellCache, pool, terrain, this.blockStride);
        this.activeLayer = GeoPalette.PreviewLayer.values()[
            Math.max(0, Math.min(GeoPalette.PreviewLayer.values().length - 1, mode))];
    }

    // ================================================================
    //  渲染（每帧）
    // ================================================================

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        long frameStart = System.nanoTime();
        int x = getX(), y = getY(), w = width, h = height;
        g.fill(x, y, x + w, y + h, 0xFF1a1a2a);

        // ★ 参考项目方式：只在视口变化时清除。正常帧保留旧数据，新 chunk 只覆盖其区域
        if (needsClear) {
            image.fillRect(0, 0, texW, texH, 0);
            needsClear = false;
        }
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        queue.queueGeneration(centerX + (int) totalDragX,
                centerZ + (int) totalDragZ, blocksWide, blocksHigh);

        int originWx = centerX + (int) totalDragX - blocksWide / 2;
        int originWz = centerZ + (int) totalDragZ - blocksHigh / 2;
        paintAvailableChunks(originWx, originWz, blocksWide, blocksHigh);

        // 坡度阴影：后处理，对所有 scaleBlockPos 级别生效。
        if (slopeShading && activeLayer == GeoPalette.PreviewLayer.ELEVATION) {
            applySlopeShading(image, originWx, originWz);
        }

        texture.upload();
        g.blit(texLoc, x, y, w, h, 0, 0, texW, texH, texW, texH);

        // 出生点标记
        drawSpawnMarker(g);
        // 结构/玩家图标（占位，后续实现）

        // 参考项目：1px 边框线
        g.fill(x - 1, y - 1, x + w + 1, y, 0xFF666666);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF666666);
        g.fill(x - 1, y, x, y + h, 0xFF666666);
        g.fill(x + w, y, x + w + 1, y + h, 0xFF666666);

        // 缩放比标签：1:N 右下角
        String zoomTxt = "1:" + scaleBlockPos;
        g.fill(x + w - mc.font.width(zoomTxt) - 8, y + h - 14, x + w - 2, y + h - 2, 0x88000000);
        g.drawString(mc.font, zoomTxt, x + w - mc.font.width(zoomTxt) - 5, y + h - 12, 0xCCCCCCCC);

        // 帧时间显示：右上角
        drawFrameTime(g, x + w, y);

        // 右键复制坐标消息
        drawCopiedToast(g, x, y, w, h);

        if (showLegend) drawLegend(g, mx, my);
        drawHoverInfo(g, mx, my);

        // 帧时间统计
        long elapsed = (System.nanoTime() - frameStart) / 1_000_000L; // ms
        frameTimes.add(elapsed);
        if (frameTimes.size() > 30) frameTimes.poll();
    }

    /** 坡度阴影后处理。
     *  1. 从 cellCache 提取高度数据
     *  2. 3×3 盒型模糊消除块边界跳变（参考项目无坡度阴影，用 colormap 平滑。
     *     我们用模糊再算 slope 来避免黑线。）
     */
    private void applySlopeShading(NativeImage img, int originWx, int originWz) {
        float lx = 0.5f, ly = 1.0f, lz = 0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;

        int w = img.getWidth(), h = img.getHeight();
        int total = w * h;
        int[] heights = new int[total];
        boolean[] valid = new boolean[total];

        // 1. 提取高度
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int wx = originWx + px;
                int wz = originWz + py;
                int cx = wx >> 4, cz = wz >> 4;
                int lx2 = wx & 15, lz2 = wz & 15;
                Cell c = cellCache.getCell(cx, cz, lx2, lz2);
                if (c != null) { heights[py * w + px] = (int) c.height; valid[py * w + px] = true; }
            }
        }

        // 2. 3×3 盒型平滑（消除块边界跳变）
        int[] smooth = heights.clone();
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                if (!valid[py * w + px]) continue;
                long sum = 0; int cnt = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int idx = (py + dy) * w + (px + dx);
                        if (valid[idx]) { sum += heights[idx]; cnt++; }
                    }
                }
                if (cnt >= 3) smooth[py * w + px] = (int) (sum / cnt);
            }
        }

        // 3. 坡度计算 + 亮度修正
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                int idx = py * w + px;
                if (!valid[idx] || !valid[idx - 1] || !valid[idx + 1]
                        || !valid[idx - w] || !valid[idx + w]) continue;
                float dhdx = (smooth[idx + 1] - smooth[idx - 1]) * 0.5f;
                float dhdz = (smooth[idx + w] - smooth[idx - w]) * 0.5f;
                float nx = -dhdx, ny = 1.0f, nz = -dhdz;
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nLen < 0.001f) continue;
                nx /= nLen; ny /= nLen; nz /= nLen;
                float diff = Math.max(0, nx * lx + ny * ly + nz * lz) * 0.6f + 0.4f;

                int abgr = img.getPixelRGBA(px, py);
                int a = (abgr >> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g2 = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                img.setPixelRGBA(px, py, (a << 24) | (Math.min(255, (int)(b * diff)) << 16)
                        | (Math.min(255, (int)(g2 * diff)) << 8) | Math.min(255, (int)(r * diff)));
            }
        }
    }

    /** 从 CellCache 读取可见区内已缓存的 chunk 并写入纹理。
     *  自适应纹理：scaleBlockPos 决定 chunk 在 tex 中的大小（texSize = BASE_RES / scaleBlockPos）。
     *  blockStride=16 → 1 sample/chunk：每个 chunk 一次 fillRect。
     *  边界 clamp：fillRect size 截断到纹理边（参考项目方式）。 */
    private int paintAvailableChunks(int originWx, int originWz, int blocksWide, int blocksHigh) {
        // chunksWide = texW * scaleBlockPos / 16 = (BASE_RES/scale) * scale / 16 = BASE_RES/16 = 16 ✓
        int minCX = originWx >> 4;
        int maxCX = (originWx + blocksWide) >> 4;
        int minCZ = originWz >> 4;
        int maxCZ = (originWz + blocksHigh) >> 4;

        int chunkTexSize = 16 / scaleBlockPos;
        if (chunkTexSize < 1) chunkTexSize = 1;
        if (chunkTexSize < 1) chunkTexSize = 1;

        int paintedCount = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Cell[] cells = cellCache.get(cx, cz);
                if (cells == null) continue;

                int baseTx = ((cx << 4) - originWx) / scaleBlockPos;
                int baseTz = ((cz << 4) - originWz) / scaleBlockPos;
                if (baseTx + chunkTexSize <= 0 || baseTx < 0 || baseTx >= texW
                        || baseTz + chunkTexSize <= 0 || baseTz < 0 || baseTz >= texH) continue;

                paintedCount++;
                // 单点采样：cells[0] = (0,0) 位置，覆盖整个 chunk
                Cell cell = cells[0];
                if (cell == null) continue;

                int color = GeoPalette.color(activeLayer, cell,
                        cx << 4, cz << 4, minY, maxY, hydrology);
                int abgr = GeoPalette.toABGR(color);
                // 边界 clamp：fillRect 不超过纹理边
                int w = Math.min(chunkTexSize, texW - baseTx);
                int h = Math.min(chunkTexSize, texH - baseTz);
                if (w > 0 && h > 0) {
                    image.fillRect(baseTx, baseTz, w, h, abgr);
                }
            }
        }
        return paintedCount;
    }

    // ================================================================
    //  交互
    // ================================================================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my)) return false;

        GeoPalette.PreviewLayer layer = getLayer();
        if (isOverLegend(mx, my) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int legendTop = getY() + 8;
            int titleH = 14;
            int modeH = 14;
            int searchH = 14;

            // 标题行点击：折叠/展开
            if (my >= legendTop && my <= legendTop + titleH) {
                legendExpanded = !legendExpanded;
                legendScrollOffset = 0;
                legendSearchActive = false;
                legendSearchQuery = "";
                return true;
            }

            // 排序行点击
            int modeY = legendTop + titleH;
            if (my >= modeY && my <= modeY + modeH) {
                legendSortMode = (legendSortMode + 1) % 3;
                legendScrollOffset = 0;
                return true;
            }
            // 搜索行点击
            int searchY = modeY + modeH;
            if (my >= searchY && my <= searchY + searchH) {
                legendSearchActive = true;
                if (!legendSearchQuery.isEmpty()) legendSearchQuery = "";
                return true;
            }
            // 点击图例其他区域：退出搜索
            if (legendSearchActive) legendSearchActive = false;
            return true;
        }

        // 点 legend 外 → 退出搜索
        if (legendSearchActive) legendSearchActive = false;

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // 右键复制坐标 + 8秒渐隐消息
            String coords = String.format("%d, %d", hoverX, hoverZ);
            mc.keyboardHandler.setClipboard(coords);
            copiedToastMsg = Component.translatable("geogenesis.preview.coords_copied", coords);
            copiedToastTime = Instant.now();
            return true;
        }

        // 左键：记录起始位置用于拖拽/点击判断
        totalDragX = 0;
        totalDragZ = 0;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        double absX = Math.abs(totalDragX), absZ = Math.abs(totalDragZ);
        if (absX > 4.0 || absZ > 4.0) {
            // 拖拽结束 → 提交偏移
            centerX += (int) Math.round(totalDragX);
            centerZ += (int) Math.round(totalDragZ);
            needsClear = true;
            queue.resetViewport();
        } else if (hoverX != -1 && hoverZ != -1) {
            // 点击（非拖拽）→ 尝试选中群系
            handleBiomeClick(mx, my);
        }
        totalDragX = 0;
        totalDragZ = 0;
        return true;
    }

    /** 处理左键点击：选中 hover 位置的群系 */
    private void handleBiomeClick(double mx, double my) {
        if (!isMouseOver(mx, my) || terrain == null) return;
        Cell c = sampleHoverCell(hoverX, hoverZ);
        if (c == null) return;
        // 仅在 BIOME 图层启用选中
        if (activeLayer == GeoPalette.PreviewLayer.BIOME) {
            BiomeClassifier.BiomeClass biome = BiomeClassifier.classify(c);
            if (biome != null) {
                int id = biome.ordinal();
                if (selectedBiomeId == id) {
                    selectedBiomeId = -1;  // 取消选中
                } else {
                    selectedBiomeId = id;
                }
            }
        } else {
            selectedBiomeId = -1;  // 非 BIOME 图层取消选中
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        double guiScale = mc.getWindow().getGuiScale();
        totalDragX -= dx * guiScale * scaleBlockPos;
        totalDragZ -= dy * guiScale * scaleBlockPos;
        needsClear = true;  // 拖拽时每帧清纹理，避免旧位置数据残留（拖影）
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;

        if (isOverLegend(mx, my)) {
            int entries = GeoPalette.discreteEntries(activeLayer).size();
            int contentH = entries * 14;
            int entryMaxH = Math.min(14 + 14 + contentH + 6, getHeight() - 40) - 14 - 14 - 6;
            int scrollableH = Math.max(0, contentH - entryMaxH);
            legendScrollOffset = Math.max(0, Math.min(scrollableH,
                    legendScrollOffset - (int) (delta * 20)));
            return true;
        }

        // 缩放：在 {1, 2, 4, 8, 16} 中循环
        int current = scaleBlockPos;
        int next = current;
        if (delta > 0) {
            // 向上滚→更细（scale 减小）
            if (current > 8) next = current / 2;
            else if (current > 4) next = 4;
            else if (current > 2) next = 2;
            else if (current > 1) next = 1;
        } else {
            // 向下滚→更粗（scale 增大）
            if (current < 2) next = 2;
            else if (current < 4) next = 4;
            else if (current < 8) next = 8;
            else if (current < 16) next = 16;
        }
        if (next != current) {
            scaleBlockPos = next;
            needsClear = true;
            queue.resetViewport();
        }
        return true;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (isMouseOver(mx, my)) {
            int originWx = centerX + (int) totalDragX - texW * scaleBlockPos / 2;
            int originWz = centerZ + (int) totalDragZ - texH * scaleBlockPos / 2;
            hoverX = originWx + (int) ((mx - getX()) / (double) width * texW) * scaleBlockPos;
            hoverZ = originWz + (int) ((my - getY()) / (double) height * texH) * scaleBlockPos;
        }
    }

    // ================================================================
    //  公共配置
    // ================================================================

    public void setTerrain(GeoGenesisTerrain terrain, long seed, TerrainParams params) {
        this.terrain = terrain;
        this.seed = seed;
        this.seaLevel = params.seaLevel();
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        this.maxY = params.maxY();
        GeoPalette.setElevationRange(minY, maxY);
        GeoPalette.setSeaLevel(seaLevel);
        cellCache.invalidateAll();
        pool.cancelAll();
        queue.resetViewport();
        centerX = 0; centerZ = 0;
        totalDragX = 0; totalDragZ = 0;
        needsClear = true;
    }

    public void setMode(int mode) {
        GeoPalette.PreviewLayer newLayer = GeoPalette.PreviewLayer.values()[
            Math.max(0, Math.min(GeoPalette.PreviewLayer.values().length - 1, mode))];
        if (newLayer == activeLayer) return;
        this.activeLayer = newLayer;
        legendScrollOffset = 0;
    }

    public int getMode() { return activeLayer.ordinal(); }
    public GeoPalette.PreviewLayer getLayer() { return activeLayer; }
    public void setHydrology(boolean on) { this.hydrology = on; }
    public boolean isHydrology() { return hydrology; }
    public void toggleLegend() { showLegend = !showLegend; }
    public boolean isSlopeShading() { return slopeShading; }
    public void setSlopeShading(boolean enabled) { this.slopeShading = enabled; }
    public void queueResetViewport() { queue.resetViewport(); }
    public void setElevationColormap(String name) { GeoPalette.setElevationColormap(name); }

    public void setPosition(int x, int y, int w, int h) {
        this.setX(x);
        this.setY(y);
        this.width = w;
        this.height = h;
        int newTexW = Math.max(1, (int)(w * mc.getWindow().getGuiScale()));
        int newTexH = Math.max(1, (int)(h * mc.getWindow().getGuiScale()));
        if (newTexW != texW || newTexH != texH) {
            texW = newTexW;
            texH = newTexH;
            // 重建纹理
            mc.getTextureManager().release(texLoc);
            image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
            texture = new DynamicTexture(image);
            texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
            mc.getTextureManager().register(texLoc, texture);
        }
        needsClear = true;
        queue.resetViewport();
    }

    // ================================================================
    //  交互增强
    // ================================================================

    /** 绘制世界出生点标记（金色罗盘 + 白色双圆范围框） */
    private void drawSpawnMarker(GuiGraphics g) {
        if (mc.level == null) return;
        BlockPos spawn = mc.level.getSharedSpawnPos();
        if (spawn == null) return;
        // 计算出生点在纹理中的坐标
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        int originWx = centerX + (int) totalDragX - blocksWide / 2;
        int originWz = centerZ + (int) totalDragZ - blocksHigh / 2;
        int sx = spawn.getX(), sz = spawn.getZ();
        int texSx = (sx - originWx) / scaleBlockPos;
        int texSz = (sz - originWz) / scaleBlockPos;
        if (texSx < 0 || texSx >= texW || texSz < 0 || texSz >= texH) return;

        // 映射到屏幕坐标
        int x = getX(), y = getY(), w = width, h = height;
        int screenSx = x + (int)((double)texSx / texW * w);
        int screenSz = y + (int)((double)texSz / texH * h);
        int markerSize = 6;

        // 金色 X 形罗盘标记
        int gold = 0xFFFFAA00;
        g.fill(screenSx - markerSize, screenSz - 1, screenSx - 1, screenSz + 1, gold);
        g.fill(screenSx + 1, screenSz - 1, screenSx + markerSize, screenSz + 1, gold);
        g.fill(screenSx - 1, screenSz - markerSize, screenSx + 1, screenSz - 1, gold);
        g.fill(screenSx - 1, screenSz + 1, screenSx + 1, screenSz + markerSize, gold);
        border(g, screenSx - markerSize - 1, screenSz - markerSize - 1, screenSx + markerSize + 1, screenSz + markerSize + 1, 0x44FFFFFF, 1);
    }

    /** 绘制帧时间（右上角） */
    private void drawFrameTime(GuiGraphics g, int rightX, int topY) {
        if (frameTimes.isEmpty()) return;
        long sum = 0;
        for (long t : frameTimes) sum += t;
        long avg = sum / frameTimes.size();
        int color;
        if (avg < 16) color = 0xFF44CC44;      // 绿：流畅
        else if (avg < 33) color = 0xFFFFAA00;  // 黄：可接受
        else color = 0xFFFF5555;                 // 红：卡顿
        String txt = avg + " ms";
        g.fill(rightX - mc.font.width(txt) - 8, topY + 2, rightX - 2, topY + 12, 0x88000000);
        g.drawString(mc.font, txt, rightX - mc.font.width(txt) - 5, topY + 3, color);
    }

    /** 绘制右键复制坐标消息（8 秒渐隐） */
    private void drawCopiedToast(GuiGraphics g, int px, int py, int pw, int ph) {
        if (copiedToastMsg == null || copiedToastTime == null) return;
        long elapsed = Duration.between(copiedToastTime, Instant.now()).toMillis();
        if (elapsed >= 8000) {
            copiedToastMsg = null;
            copiedToastTime = null;
            return;
        }
        int alpha = Math.max(0, Math.min(255, 220 - (int)(elapsed * 220 / 8000)));
        if (alpha <= 0) return;
        // 绘制居中底栏消息
        int textW = mc.font.width(copiedToastMsg);
        int bx = px + (pw - textW) / 2 - 4;
        int by = py + ph - 32;
        g.fill(bx, by, bx + textW + 8, by + 12, (alpha << 24) | 0x101418);
        g.drawString(mc.font, copiedToastMsg, bx + 4, by + 2, (alpha << 24) | 0xFFFFFF);
    }

    // ================================================================
    //  图例
    // ================================================================

    private void drawLegend(GuiGraphics g, int mx, int my) {
        GeoPalette.PreviewLayer layer = getLayer();
        int lx = getX() + width - 132, ly = getY() + 8;
        if (layer.legendable && layer.kind == GeoPalette.Kind.DISCRETE) {
            // 折叠态：只显示一条标题
            if (!legendExpanded) {
                g.fill(lx - 4, ly - 4, lx - 4 + 128, ly + 10, 0xAA101418);
                String title = (legendExpanded ? "▼ " : "▶ ") + I18n.get(layer.labelKey);
                g.drawString(mc.font, title, lx, ly, 0x66CCFF);
                return;
            }
            boolean hasSearch = legendSearchQuery.length() > 0;
            List<GeoPalette.LegendEntry> allEntries = getSortedEntries(layer);
            // 过滤
            List<GeoPalette.LegendEntry> entries = hasSearch ? allEntries.stream()
                .filter(e -> {
                    String name = I18n.get(e.labelKey);
                    if (name.equals(e.labelKey)) name = GeoPalette.englishLabel(e.labelKey);
                    return name.toLowerCase().contains(legendSearchQuery.toLowerCase());
                }).collect(java.util.stream.Collectors.toList()) : allEntries;

            int panelW = 128, rowH = 14, titleH = 14, modeH = 14, searchH = 14;
            int maxVisibleH = getHeight() - 40;
            int contentH = entries.size() * rowH;
            int topBarH = titleH + modeH + (hasSearch || legendSearchActive ? searchH : 0);
            int panelH = Math.min(topBarH + contentH + 6, maxVisibleH);

            g.fill(lx - 4, ly - 4, lx - 4 + panelW, ly - 4 + panelH, 0xAA101418);
            // 标题带：带 ▼ 表示可点击折叠
            String title = "▼ " + I18n.get(layer.labelKey);
            g.drawString(mc.font, title, lx, ly, 0x66CCFF);

            int modeY = ly + titleH;
            String modeLabel = "排序: " + LEGEND_SORT_NAMES[legendSortMode];
            boolean hoverMode = mx >= lx && mx <= lx + panelW && my >= modeY && my <= modeY + modeH;
            if (hoverMode) g.fill(lx - 2, modeY, lx + mc.font.width(modeLabel) + 8, modeY + modeH, 0x4400c896);
            g.drawString(mc.font, modeLabel, lx, modeY + 2, hoverMode ? 0xFF00c896 : 0xFF888888);

            // 搜索框
            int searchY = modeY + modeH;
            if (legendSearchActive || hasSearch) {
                String display = legendSearchActive && (System.currentTimeMillis() / 500) % 2 == 0
                    ? legendSearchQuery + "_" : legendSearchQuery;
                boolean hoverSearch = mx >= lx && mx <= lx + panelW && my >= searchY && my <= searchY + searchH;
                g.fill(lx, searchY, lx + panelW - 8, searchY + searchH, 0x44000000);
                g.drawString(mc.font, "🔍" + display, lx + 2, searchY + 2, legendSearchActive ? 0xFFFFFF : 0x888888);
            } else {
                // 不活跃时显示点击提示
                boolean hoverSearch = mx >= lx && mx <= lx + panelW && my >= searchY && my <= searchY + searchH;
                if (hoverSearch) {
                    g.fill(lx, searchY, lx + panelW - 8, searchY + searchH, 0x4400c896);
                    g.drawString(mc.font, "点击搜索", lx + 2, searchY + 2, 0x88CCCCCC);
                }
            }

            int entryY = searchY + (hasSearch || legendSearchActive ? searchH : 0);
            int entryMaxH = panelH - topBarH - 6;
            g.enableScissor(lx - 4, entryY, lx - 4 + panelW, entryY + entryMaxH);

            int cy = entryY - legendScrollOffset;
            for (GeoPalette.LegendEntry e : entries) {
                if (cy + rowH > entryY - rowH && cy < entryY + entryMaxH + rowH) {
                    g.fill(lx, cy, lx + 10, cy + 10, GeoPalette.toABGR(e.color));
                    String name = I18n.get(e.labelKey);
                    if (name.equals(e.labelKey)) name = GeoPalette.englishLabel(e.labelKey);
                    if (mc.font.width(name) > panelW - 18) {
                        while (mc.font.width(name + "...") > panelW - 18 && name.length() > 1)
                            name = name.substring(0, name.length() - 1);
                        name += "...";
                    }
                    g.drawString(mc.font, name, lx + 14, cy, 0xEEEEEE);
                }
                cy += rowH;
            }
            g.disableScissor();
            int scrollableH = contentH - entryMaxH;
            if (scrollableH > 0) {
                int barH = Math.max(12, entryMaxH * entryMaxH / contentH);
                int barY = entryY + legendScrollOffset * (entryMaxH - barH) / scrollableH;
                g.fill(lx + panelW - 3, barY, lx + panelW - 1, barY + barH, 0xFF00c896);
            }
        } else {
            int bx = getX() + width - 22, by = getY() + 24, bh = 200, bw = 12;
            // 全范围渐变 [0, 1]：底→深蓝(洋) → 绿(陆) → 棕(山) → 白(雪峰)
            for (int i = 0; i < bh; i++) {
                double p = 1.0 - (double) i / (bh - 1);
                int rgb = GeoPalette.continuous(layer, p);
                g.fill(bx, by + i, bx + bw, by + i + 1, GeoPalette.toABGR(rgb));
            }
            String topLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + maxY) : "1.0";
            String botLabel = (layer == GeoPalette.PreviewLayer.ELEVATION) ? ("Y=" + minY) : "0.0";
            g.drawString(mc.font, topLabel, bx - 28, by, 0xCCCCCC);
            g.drawString(mc.font, botLabel, bx - 28, by + bh - 8, 0xCCCCCC);
            g.drawString(mc.font, I18n.get(layer.labelKey), lx, ly, 0x66CCFF);
        }
    }

    /** 读取 hover 位置 Cell（优先用 cellCache，避免触发 chunk 生成） */
    private Cell sampleHoverCell(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        int lx = wx & 15, lz = wz & 15;
        Cell c = cellCache.getCell(cx, cz, lx, lz);
        if (c != null) return c;
        // 缓存 miss → 回退 terrain（会触发 chunk 生成）
        try { return terrain.sampleCell(wx, wz); } catch (Exception e) { return null; }
    }

    private void drawHoverInfo(GuiGraphics g, int mx, int my) {
        if (!isMouseOver(mx, my) || hoverX == -1 || terrain == null) return;
        Cell c = sampleHoverCell(hoverX, hoverZ);
        if (c == null) return;

        int lx = getX() + 6, ly = getY() + height - 85;
        // 第一行：坐标 + 高度
        String line0 = String.format("X=%d  Z=%d  Y=%d", hoverX, hoverZ, (int) Math.round(c.height));
        // 第二行：群系名 + 地形类型
        BiomeClassifier.BiomeClass biome = BiomeClassifier.classify(c);
        String biomeName = "—";
        if (biome != null) {
            String key = "geogenesis.biome." + biome.name();
            String localized = I18n.get(key);
            biomeName = localized.equals(key) ? biome.name() : localized;
        }
        String line1 = biomeName + "  " + typeLine(c);
        // 第三行：温度/湿度/大陆性
        String line2 = String.format("temp=%.2f  hum=%.2f  cont=%.2f", c.temperature, c.humidity, c.continentNoise);
        // 第四行：纬度 + 地形起伏
        String line3 = String.format("lat=%.2f  relief=%.2f",
            com.geogenesis.worldgen.climate.Latitude.latitude01(hoverZ),
            (c.shape + 1) * 0.5);
        // 第五行：水文信息
        String line4 = c.riverIsWaterfall ? I18n.get("geogenesis.preview.waterfall")
            : (c.riverSourceType == 3 ? I18n.get("geogenesis.preview.sourceLake")
            : (c.riverSourceType == 2 ? I18n.get("geogenesis.preview.spring")
            : (c.riverSourceType == 1 ? I18n.get("geogenesis.preview.creek")
            : (c.riverMask ? I18n.get("geogenesis.preview.river")
            : (c.riverDistance < 0.15 ? I18n.get("geogenesis.preview.valley")
            : (c.lakeMask ? I18n.get("geogenesis.preview.lake") : "—"))))));

        String[] lines = {line0, line1, line2, line3, line4};
        // 自动计算面板宽度
        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, mc.font.width(l));
        maxW = Math.max(maxW, 160);
        int panelH = lines.length * 13 + 4;
        g.fill(lx, ly, lx + maxW + 10, ly + panelH, 0xAA000000);
        for (int i = 0; i < lines.length; i++)
            g.drawString(mc.font, lines[i], lx + 4, ly + 4 + i * 13, 0xFFFFFF);
    }

    private List<GeoPalette.LegendEntry> getSortedEntries(GeoPalette.PreviewLayer layer) {
        List<GeoPalette.LegendEntry> entries = new ArrayList<>(GeoPalette.discreteEntries(layer));
        switch (legendSortMode) {
            case 1 -> entries.sort(Comparator.comparing(e -> {
                String n = I18n.get(e.labelKey);
                return n.equals(e.labelKey) ? GeoPalette.englishLabel(e.labelKey) : n;
            }));
            case 2 -> entries.sort(Comparator.comparing((GeoPalette.LegendEntry e) -> e.labelKey)
                    .thenComparingInt(e -> e.id));
            default -> entries.sort(Comparator.comparingInt(e -> e.id));
        }
        return entries;
    }

    private boolean isOverLegend(double mx, double my) {
        if (!showLegend) return false;
        GeoPalette.PreviewLayer layer = getLayer();
        if (!layer.legendable || layer.kind != GeoPalette.Kind.DISCRETE) return false;
        int lx = getX() + width - 136, ly = getY() + 4;
        return mx >= lx && mx <= lx + 132 && my >= ly && my <= ly + getHeight() - 40;
    }

    private static String typeLine(Cell c) {
        return switch (c.terrainType) {
            case OCEAN -> I18n.get("geogenesis.preview.type.ocean");
            case DEEP_OCEAN -> I18n.get("geogenesis.preview.type.deep_ocean");
            case CONTINENTAL_SHELF -> I18n.get("geogenesis.preview.type.continental_shelf");
            case SUBMARINE_RIDGE -> I18n.get("geogenesis.preview.type.submarine_ridge");
            case SEAMOUNT -> I18n.get("geogenesis.preview.type.seamount");
            case BEACH -> I18n.get("geogenesis.preview.type.beach");
            case PLAIN -> c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.plain");
            case HILLS -> c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.hills");
            case PLATEAU -> c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.plateau");
            case MOUNTAINS -> c.isSnow ? I18n.get("geogenesis.preview.type.snowy_slopes") : I18n.get("geogenesis.preview.type.mountains");
            case PEAK -> c.isSnow ? I18n.get("geogenesis.preview.type.snow") : I18n.get("geogenesis.preview.type.peak");
            case BASIN -> I18n.get("geogenesis.preview.type.basin");
            case RIVER -> I18n.get("geogenesis.preview.type.river");
            case LAKE -> I18n.get("geogenesis.preview.type.lake");
            case SNOW -> I18n.get("geogenesis.preview.type.snow");
            default -> I18n.get("geogenesis.preview.type.land");
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!legendSearchActive) return false;
        if (keyCode == 256 || keyCode == 257) { // ESC 或 Enter → 退出搜索
            legendSearchActive = false;
            return true;
        }
        if (keyCode == 259 && legendSearchQuery.length() > 0) { // Backspace
            legendSearchQuery = legendSearchQuery.substring(0, legendSearchQuery.length() - 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!legendSearchActive) return false;
        if (codePoint >= ' ' && codePoint < 127) {
            legendSearchQuery += codePoint;
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal("GeoGenesis terrain preview"));
    }

    /** 1px 边框绘制（Gf 无直接 border，用 4 条线模拟） */
    private static void border(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int width) {
        g.fill(x1, y1, x2, y1 + width, color);
        g.fill(x1, y2 - width, x2, y2, color);
        g.fill(x1, y1 + width, x1 + width, y2 - width, color);
        g.fill(x2 - width, y1 + width, x2, y2 - width, color);
    }

    public void close() {
        pool.shutdown();
        mc.getTextureManager().release(texLoc);
        texture.close();
    }
}
