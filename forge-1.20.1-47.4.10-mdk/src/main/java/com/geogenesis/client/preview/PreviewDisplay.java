package com.geogenesis.client.preview;

import com.geogenesis.client.StructureIconOverlay;
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
    private final TerrainPool pool = new TerrainPool(4);
    private TerrainQueue queue;
    private GeoGenesisTerrain terrain;
    /** 结构扫描器（创建世界界面传入 registry 后可用；null = 预览无结构标记） */
    private com.geogenesis.client.preview.chunk.StructureScanner structureScanner;
    /** 显示结构标记 */
    public boolean showStructures = true;
    /** 磁盘持久化：seed 变更时重建；close() 时保存（"已加载的地图被记录"） */
    private long diskSeed = Long.MIN_VALUE;
    private long diskConfigHash = Long.MIN_VALUE;

    // ====== 纹理 ======
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation texLoc;
    private int texW, texH;
    /** 每纹理像素对应的世界块数。越大越缩。 */
    public int scaleBlockPos = 1;
    /** 纹理超采样倍率（渲染分辨率 = 预览窗口逻辑分辨率 × renderScale）。
     *  1=显示器模型（纹理=窗口逻辑像素，GPU 负责 DPI 上采样，最快）；2/3/4=更锐利但更慢。 */
    public int renderScale = 1;

    // ====== 视口 ======
    /** 视口中心世界坐标 */
    private int centerX = 0, centerZ = 0;
    /** 拖拽累积偏移（松手后并入 center） */
    private double totalDragX = 0, totalDragZ = 0;
    /** 拖拽中标志：拖拽时跳过最贵的坡度阴影（松手后一次性补画），保证拖动流畅 */
    private boolean dragging = false;

    // ====== 渲染脏检查（避免静止帧无谓重画/重传，消除"渲染打架"） ======
    /** 上帧已画进纹理的视口原点（对齐 scale 后），未变 → 视口未动 */
    private long lastOriginWx = Long.MIN_VALUE, lastOriginWz = Long.MIN_VALUE;
    /** 上帧已画进纹理的 CellCache 版本号，未变 → 无新数据 */
    private long lastCacheVersion = -1;
    /** 上帧已画进纹理的图层，未变 → 颜色无需重算 */
    private int lastLayerOrdinal = -1;
    private int lastSelectedLayerOrdinal = -1, lastSelectedId = -1;

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
    /** 离散图层选中（通用）：选中图层 + 离散 id（-1=未选中）。
     *  用于点击地图/图例/列表高亮该色块、其余置灰，与参考项目选中行为一致。 */
    private GeoPalette.PreviewLayer selectedLayer = null;
    private int selectedId = -1;
    /** 过滤模式（对齐参考模组 BiomeCheckboxList 思路）：开启后图例/地图点击切换勾选集合，
     *  未勾选的类型在地图上压暗显示；关闭 = 单选高亮。 */
    public boolean filterMode = false;
    public final java.util.Set<Integer> filterIds = new java.util.HashSet<>();

    // ====== 设置（显示类运行时状态，由 GeoGenesisConfigScreen 各设置页签面板读写） ======
    /** 显示帧时间 */
    public boolean showFrameTime = true;
    /** 显示玩家位置标记 */
    public boolean showPlayerMarkers = true;
    /** 显示 Voronoi 细胞边界（相邻格主导地形类型不同处画深色线，诊断类型混合用） */
    public boolean showCellBorders = false;
    /** 拖动时简化视图（跳过最贵的坡度阴影保证 60fps；松手后第一帧补画）。false = 拖动也画完整阴影 */
    public boolean dragSimplify = true;
    /** 公开 CellCache 引用，供外部清除缓存 */
    public CellCache cellCache = new CellCache();
    /** 结构图标叠加层（可选 JSON，无数据则跳过） */
    private final StructureIconOverlay structureOverlay = new StructureIconOverlay();

    // ====== 数据 ======
    private long seed;
    private int seaLevel, maxY, minY, mountainCap;
    private double peakFraction, verticalScale;
    /** 高程色阶映射的 e 区间（地形实际可达范围），供图例 Y 标签换算。 */
    private double elevEMin = -1.0, elevEMax = 1.0;
    /** 视口变化标记：需要清除纹理重新填充。pan/zoom/seed 变时设 true */
    public boolean needsClear = true;

    private final Minecraft mc = Minecraft.getInstance();

    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode) {
        this(x, y, w, h, terrain, seed, params, mode, 1);
    }

    /**
     * @param renderScale 纹理超采样倍率（1=显示器模型，纹理=窗口逻辑像素）。
     */

    /**
     * 完整构造函数，允许指定纹理超采样倍率。
     * @param renderScale 纹理超采样倍率（1=显示器模型，纹理=窗口逻辑像素，GPU 负责 DPI 上采样）。
     *                    渲染分辨率恒等于预览窗口显示分辨率，与缩放无关、不过采样。
     */
    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode, int renderScale) {
        super(x, y, w, h, Component.literal("GeoGenesis Preview"));
        this.terrain = terrain;
        this.seed = seed;
        this.seaLevel = params.seaLevel();
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        this.maxY = params.maxY();
        this.peakFraction = params.peakHeightFraction();
        this.verticalScale = params.verticalScale();
        this.renderScale = Math.max(1, Math.min(4, renderScale));
        // 高程色阶范围跟随地形实际可达 e 区间（由地形类型 e 界限换算），
        // 使地形最高处触顶雪白、最深触底深蓝，而非世界的绝对高度上下限。
        double[] er = params.elevationERange();
        this.elevEMin = er[0]; this.elevEMax = er[1];
        GeoPalette.setElevationERange(er[0], er[1]);
        GeoPalette.setSeaLevel(seaLevel);
        // 渲染分辨率 = 预览窗口显示分辨率（逻辑像素 × renderScale），类比电脑显示器：
        // 无论源地形多精细，屏幕只按自身分辨率渲染，GPU 负责 DPI/超采样上采样，
        // 避免按物理像素（gui_scale）超采样造成的 4× 浪费。
        texW = Math.max(1, (int)(this.width * this.renderScale));
        texH = Math.max(1, (int)(this.height * this.renderScale));
        image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
        texture = new DynamicTexture(image);
        texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
        mc.getTextureManager().register(texLoc, texture);

        this.queue = new TerrainQueue(cellCache, pool, terrain, effectiveStride(), structureScanner);
        this.activeLayer = GeoPalette.PreviewLayer.values()[
            Math.max(0, Math.min(GeoPalette.PreviewLayer.values().length - 1, mode))];
        // ★ 磁盘持久化：打开预览即加载历史（同一 seed 之前浏览过的位置秒显，不重采样）
        diskSeed = seed;
        diskConfigHash = cacheSchemaHash(params);
        loadDiskHistory();
    }

    /**
     * 缓存键 = 地形参数 hash × 分类版本号：分类/渲染逻辑变更（如 BEACH/SNOW 移除）时
     * 递增版本 → 旧磁盘缓存自动失效重采，无需用户手动清缓存。
     */
    // 2026-08-06 → 3：骨架算法变更（SLOPE_BOOST=8 坡度放大 + fadeTarget 只下切），旧磁盘缓存 bin 作废
    // 2026-08-06 → 4：海陆类型化（OCEAN/DEEP_OCEAN 入类型场，e=类型权重混合），旧缓存 bin 作废
    // 2026-08-06 → 5：海洋权重高次锐化 OCEAN_SHARP=5（海陆过渡带收窄，类型形态保持到海岸线），旧缓存 bin 作废
    // 2026-08-06 → 6：PLATEAU v7 丘陵式配方（foldHills+倍频放宽 2.5x+顶部削平 80%），旧缓存 bin 作废
    // 2026-08-06 → 7：PLATEAU 台地 mix（破权重包络圆包），旧缓存 bin 作废
    // 2026-08-07 → 8：PLATEAU v7.2 丘沟保留（computePlateau 压缩 80%→40% + platMod 收敛 4→2 倍），
    // 用户反馈"丘陵比高原自然"，旧缓存 bin 作废
    // 2026-08-07 → 9：v7.2 修正：computePlateau 不压缩（foldHills 全幅），
    // platMod 1.5 倍收敛——高原丘沟完整保留
    // 2026-08-07 → 10：v8 彻底参考丘陵：移除 platMix/platMod/platE 全部覆盖层，旧缓存 bin 作废
    // 2026-08-07 → 11：PLATEAU v8 配方 + 骨架 flatMask（用户否决——侵蚀必须无限制，已撤销）留档
    // 2026-08-08 → 13：河流图层修复（RIVER_NETWORK 改用 riverDistance，CellGenerator 同步设置 riverNetDist）
    // 2026-08-08 → 14：索引转置修复——paint/getCell/showCellBorders/DiskChunk.compact/cells 统一 X 主序
    //   （cells[lx*16+lz]，与 GeoGenesisTerrain.generateChunk 一致）。此前预览层用 Z 主序读取 X 主序数据
    //   → 16 块间距网格。磁盘格式语义变化（DiskChunk 存储约定）→ 旧缓存必须失效。
    //   注意：WIP stash 恢复曾把此值改回 13 导致伪影复现（6c30272）——本值禁止回退到 13。
    private static final int CACHE_SCHEMA_VERSION = 14;
    /** 2026-08-06：混入全配置指纹（含侵蚀/河流等运行时参数）——配置改动后磁盘缓存自动失效重采 */
    private static long cacheSchemaHash(com.geogenesis.worldgen.terrain.TerrainParams params) {
        long cfg = com.geogenesis.config.GeoGenesisConfig.configFingerprint();
        return (params.hashCode() * 31L + cfg) * 31L + CACHE_SCHEMA_VERSION;
    }

    /** 从磁盘加载历史数据到 CellCache 历史层（滑回/缩放需要时自动回填渲染层，不重新采样）。
     *  直接放入历史层而非渲染层：内存 = 紧凑采样点（≈磁盘文件量级），且当前视口
     *  经 queueGeneration → needsResample 命中历史层回填 → 首帧秒显。 */
    private void loadDiskHistory() {
        try {
            var chunks = com.geogenesis.client.preview.chunk.PreviewDiskCache.load(diskSeed, diskConfigHash);
            for (var e : chunks.entrySet()) {
                cellCache.importHistory(e.getKey(), e.getValue());
            }
            if (!chunks.isEmpty()) {
                LOGGER.info("[PreviewCache] loaded {} chunks from disk for seed {}", chunks.size(), diskSeed);
            }
        } catch (Throwable t) {
            LOGGER.warn("[PreviewCache] disk load failed, ignoring", t);
        }
    }

    // ================================================================
    //  渲染（每帧）
    // ================================================================

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        long frameStart = System.nanoTime();
        int x = getX(), y = getY(), w = width, h = height;
        g.fill(x, y, x + w, y + h, 0xFF1a1a2a);

        int scale = scaleBlockPos;
        int blocksWide = texW * scale;
        int blocksHigh = texH * scale;
        // ★ 统一视口原点，并对齐到 scale 倍数（floorDiv）：
        //   paint 里 tx=(wx-originWx)/scale 才能整除 → chunk 像素无缝、无重叠/缝隙/重影。
        //   hover / 出生点标记 / 阴影全部复用同一个对齐原点，杜绝"两层图错位打架"。
        int originWx = alignDown(centerX + (int) totalDragX - blocksWide / 2, scale);
        int originWz = alignDown(centerZ + (int) totalDragZ - blocksHigh / 2, scale);

        // 每帧直接用鼠标屏幕坐标反算世界坐标（参考项目 updateTooltip 做法），
        // 不依赖 mouseMoved 回调（Forge Screen 默认不向子 widget 转发 mouseMoved，
        // 否则 hoverX/hoverZ 永远为 -1、悬浮提示永不触发）。
        if (isMouseOver(mx, my)) {
            hoverX = originWx + (int) ((mx - getX()) / (double) width * texW) * scale;
            hoverZ = originWz + (int) ((my - getY()) / (double) height * texH) * scale;
        } else {
            hoverX = -1; hoverZ = -1;
        }

        queue.queueGeneration(centerX + (int) totalDragX,
                centerZ + (int) totalDragZ, blocksWide, blocksHigh);

        // ★ 脏检查：只有视口移动 / 新数据到位 / 图层或选中变化 / 显式 needsClear 才重画+重传。
        //   静止帧（无拖拽、无新 chunk）直接 blit GPU 旧纹理 → 0 CPU 重画，消除"打架/撕裂"。
        boolean viewportMoved = originWx != lastOriginWx || originWz != lastOriginWz;
        boolean dataChanged = cellCache.version() != lastCacheVersion;
        boolean layerChanged = activeLayer.ordinal() != lastLayerOrdinal
                || (selectedLayer == null ? -1 : selectedLayer.ordinal()) != lastSelectedLayerOrdinal
                || selectedId != lastSelectedId;
        boolean dirty = needsClear || viewportMoved || dataChanged || layerChanged;

        if (dirty) {
            // ★ 无条件 fillRect：脏帧总是从干净状态重画。
            //   原"只在 needsClear 时清"逻辑有 bug——缩放/拖拽后第一帧 viewportMoved=true 触发 dirty，
            //   但 needsClear 已在 mouseScrolled/mouseReleased 处被清成 false，导致第二帧跳过 fillRect，
            //   image 残留旧视口像素（用户截图左侧 50px 竖条就是缩放前视口左边缘数据）。
            //   dirty 本身已被视口移动/新数据/图层变化严格门控，多一次 fillRect（O(texW×texH)≈1ms）安全。
            image.fillRect(0, 0, texW, texH, 0);
            needsClear = false;
            paintAvailableChunks(originWx, originWz, blocksWide, blocksHigh);

            // 真实性地形阴影（逐像素高度梯度法线 · 光源点乘）：由 TerrainUnderlay 全局控制，
            // 对图层无关（气候/地形数据均可披真实地形明暗）。
            // 拖拽中按 dragSimplify 开关跳过（最贵的一步：全图 3 遍 + 每像素缓存查找），
            // 松手后 needsClear 补画。false = 拖动也画完整阴影（接受掉帧）。
            if ((!dragging || !dragSimplify)
                    && GeoPalette.getTerrainUnderlay() == GeoPalette.TerrainUnderlay.SHADE) {
                applySlopeShading(image, originWx, originWz);
            }

            texture.upload();
            lastOriginWx = originWx; lastOriginWz = originWz;
            lastCacheVersion = cellCache.version();
            lastLayerOrdinal = activeLayer.ordinal();
            lastSelectedLayerOrdinal = (selectedLayer == null ? -1 : selectedLayer.ordinal());
            lastSelectedId = selectedId;
        }
        g.blit(texLoc, x, y, w, h, 0, 0, texW, texH, texW, texH);

        // 出生点标记
            drawSpawnMarker(g);
            // 玩家位置标记
            if (showPlayerMarkers) drawPlayerMarker(g);
            // 自动结构标记（Worker 检测结果；无 scanner 或未命中则跳过）
            if (showStructures && structureScanner != null) drawStructureMarkers(g, mx, my);
            // 结构图标叠加（可选 JSON，无数据则跳过；返回悬停名）
            String markerHover = structureOverlay.render(g, x, y, w, h,
                originWx, originWz, blocksWide, blocksHigh, mx, my);
            if (markerHover != null) {
                int tw = mc.font.width(markerHover);
                g.fill(mx + 10, my + 8, mx + 14 + tw, my + 20, 0xCC000000);
                g.drawString(mc.font, markerHover, mx + 13, my + 10, 0xFFFFFF);
            }

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
        if (showFrameTime) drawFrameTime(g, x + w, y);

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
        // 标准地图学光源：屏幕左上方（lx<0 左、lz<0 屏幕上方），符合人眼"光从左上来"的直觉，
        // 避免旧 (0.5,1,0.3)=右下方光源造成的"陆地凹陷/海洋突出"错觉。
        float lx = -0.5f, ly = 1.0f, lz = -0.3f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;

        int w = img.getWidth(), h = img.getHeight();
        int total = w * h;
        int[] effH = new int[total];       // 有效高度：水域填海平面，陆地填 worldY
        boolean[] valid = new boolean[total];
        // 水下掩码：e<0 为海平面以下（海洋）。水面是平的，海底起伏不应被 hillshade 雕出浮雕，
        // 否则在左上方光源下仍会显"海洋突出"的错觉。水域像素保留纯色带（按深度渐变），不做明暗调制。
        boolean[] water = new boolean[total];

        // 1. 提取高度（必须与着色层同一世界坐标映射：纹理像素 px → 世界块 originWx + px*scaleBlockPos，
        //    否则缩放后阴影层相对着色层产生线性位移 → 视差/剪切错位）
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int wx = originWx + px * scaleBlockPos;
                int wz = originWz + py * scaleBlockPos;
                int cx = wx >> 4, cz = wz >> 4;
                int lx2 = wx & 15, lz2 = wz & 15;
                Cell c = cellCache.getCell(cx, cz, lx2, lz2);
                if (c != null) {
                    boolean isWater = c.e < 0.0;
                    water[py * w + px] = isWater;
                    // 水域用海平面高度代替海床：海岸处 陆地(≈seaLevel)−水域(seaLevel)≈0，
                    // 消除海底深度在海岸线陆地投下的伪阴影"悬崖"（海岸阴影截断）。
                    effH[py * w + px] = isWater ? seaLevel : (int) c.height;
                    valid[py * w + px] = true;
                }
            }
        }

        // 2. 3×3 盒型平滑（消除块边界跳变）；水域以 effH=seaLevel 参与模糊，
        //    使海岸处 陆地(≈seaLevel)−水域(seaLevel)≈0，海岸阴影从岸边平滑过渡到内陆起伏（不再截断）。
        int[] smooth = effH.clone();
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                if (!valid[py * w + px]) continue;
                long sum = 0; int cnt = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int idx = (py + dy) * w + (px + dx);
                        if (valid[idx]) { sum += effH[idx]; cnt++; }
                    }
                }
                if (cnt >= 3) smooth[py * w + px] = (int) (sum / cnt);
            }
        }

        // 3. 坡度计算 + 亮度修正（跳过水域像素，海面保持平涂）
        for (int py = 1; py < h - 1; py++) {
            for (int px = 1; px < w - 1; px++) {
                int idx = py * w + px;
                if (water[idx] || !valid[idx] || !valid[idx - 1] || !valid[idx + 1]
                        || !valid[idx - w] || !valid[idx + w]) continue;
                // 斜率按 scaleBlockPos 归一化到「每世界块」，使缩放各级阴影强度一致
                // （相邻纹理像素实际间隔 scaleBlockPos 个世界块）
                float inv = 0.5f / scaleBlockPos;
                float dhdx = (smooth[idx + 1] - smooth[idx - 1]) * inv;
                float dhdz = (smooth[idx + w] - smooth[idx - w]) * inv;
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

    /** 从 CellCache 读取可见区内已缓存的 chunk 并逐块写入纹理。
     *  逐块采样（cells[lx*16+lz]，X 主序与 generateChunk 一致），每个块映射到其纹理像素。
     *  ★ 2026-08-08：1) 修复索引转置（Z 主序→X 主序，16 块间距网格根因，勿回退）；
     *    2) step=scaleBlockPos 稀疏采样（每纹理像素 1 采样点：16/step × 16/step 点恰好覆盖
     *       chunk 在纹理中的 scaleBlockPos² 像素区，无漏画无网格）。此前临时 step=1 全采样
     *       使拖拽每帧绘制量暴涨 16 倍 → 移动卡顿（用户反馈），已恢复稀疏。 */
    private int paintAvailableChunks(int originWx, int originWz, int blocksWide, int blocksHigh) {
        int minCX = originWx >> 4;
        int maxCX = (originWx + blocksWide) >> 4;
        int minCZ = originWz >> 4;
        int maxCZ = (originWz + blocksHigh) >> 4;

        int step = Math.min(16, Math.max(1, scaleBlockPos));
        int paintedCount = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Cell[] cells = cellCache.get(cx, cz);
                if (cells == null) continue;

                for (int lx = 0; lx < 16; lx += step) {
                    for (int lz = 0; lz < 16; lz += step) {
                        int wx = (cx << 4) + lx;
                        int wz = (cz << 4) + lz;
                        int tx = (wx - originWx) / scaleBlockPos;
                        int tz = (wz - originWz) / scaleBlockPos;
                        if (tx < 0 || tx >= texW || tz < 0 || tz >= texH) continue;
                        // ★ 2026-08-08：修复索引转置——generateChunk 写 cells[lx*16+lz]（X 行 Z 列），
                        //   此处必须同约定读，否则 chunk 内 X/Z 交换 → 16 块间距网格（19df17e 后引入）。
                        Cell cell = cells[lx * 16 + lz];
                        if (cell == null) continue;
                        int color = GeoPalette.color(activeLayer, cell, wx, wz, minY, maxY, false);
                        // 离散图层：过滤模式 → 未勾选压暗；否则选中高亮其余压暗（每帧重绘，实时生效）
                        if (activeLayer.kind == GeoPalette.Kind.DISCRETE) {
                            int id = GeoPalette.discreteIdForCell(activeLayer, cell);
                            if (filterMode) {
                                if (!filterIds.contains(id)) color = grayTint(color);
                            } else if (selectedLayer == activeLayer && selectedId >= 0 && id != selectedId) {
                                color = grayTint(color);
                            }
                        }
                        // ★ 细胞边界叠加（诊断子图层）：与左/上邻居主导地形类型不同处画深色线。
                        //   对齐阴影等叠加层的表现方式——不改变数据色，仅压暗边界像素。
                        if (showCellBorders) {
                            int cxc = wx >> 4, czc = wz >> 4;
                            int lxc = wx & 15, lzc = wz & 15;
                            // X 主序：cells[lx*16+lz]（与 generateChunk 一致）
                            Cell left = (lxc > 0) ? cells[(lxc - 1) * 16 + lzc]
                                    : cellCache.getCell(cxc - 1, czc, 15, lzc);
                            Cell up = (lzc > 0) ? cells[lxc * 16 + (lzc - 1)]
                                    : cellCache.getCell(cxc, czc - 1, lxc, 15);
                            boolean leftDiff = left != null && left.terrainType != cell.terrainType;
                            boolean upDiff = up != null && up.terrainType != cell.terrainType;
                            if (leftDiff || upDiff) {
                                int r = (color >> 16) & 0xFF, gg = (color >> 8) & 0xFF, b = color & 0xFF;
                                r = r * 3 / 10; gg = gg * 3 / 10; b = b * 3 / 10;
                                color = (r << 16) | (gg << 8) | b;
                            }
                        }
                        image.setPixelRGBA(tx, tz, GeoPalette.toABGR(color));
                        paintedCount++;
                    }
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
            // 点击图例条目：选中对应离散色块（再次点取消）
            int hit = legendHitEntry(my);
            if (hit >= 0) {
                List<GeoPalette.LegendEntry> entries = visibleLegendEntries();
                GeoPalette.LegendEntry e = entries.get(hit);
                setSelected(activeLayer, e.id);
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
        dragging = false;
        double absX = Math.abs(totalDragX), absZ = Math.abs(totalDragZ);
        if (absX > 4.0 || absZ > 4.0) {
            // 拖拽结束 → 提交偏移 + 立即取消旧批（对齐参考模组 queueRangeReal 的 cancel+await），
            //   否则新视口边缘 chunks 要等旧批（按旧视口）跑完才能补 → 用户截图的黑块。
            centerX += (int) Math.round(totalDragX);
            centerZ += (int) Math.round(totalDragZ);
            needsClear = true;
            pool.cancelAll();  // 立即中断旧批（已写入 cache 的保留，未完成的丢弃）
            queue.resetViewport();  // firstQueue=true → 下帧绕过视口比较强制入队
        } else if (hoverX != -1 && hoverZ != -1) {
            // 点击（非拖拽）→ 尝试在离散图层选中对应色块
            handleMapClick(mx, my);
        }
        totalDragX = 0;
        totalDragZ = 0;
        return true;
    }

    /** 处理左键点击：在离散图层选中 hover 位置对应的色块（再次点同一项取消）。 */
    private void handleMapClick(double mx, double my) {
        if (!isMouseOver(mx, my) || terrain == null) return;
        if (activeLayer.kind != GeoPalette.Kind.DISCRETE) {
            selectedLayer = null; selectedId = -1;  // 连续图层点击不选中
            return;
        }
        Cell c = sampleHoverCell(hoverX, hoverZ);
        if (c == null) return;
        int id = GeoPalette.discreteIdForCell(activeLayer, c);
        if (id < 0) return;
        setSelected(activeLayer, id);
    }

    /** 设置/切换离散图层选中（点同一项取消）。过滤模式下改为多选勾选集合。 */
    public void setSelected(GeoPalette.PreviewLayer layer, int id) {
        if (layer == null || layer.kind != GeoPalette.Kind.DISCRETE) return;
        if (filterMode) {
            // 多选过滤：切换勾选状态（集合中的去掉，不在的加入）
            if (!filterIds.remove(id)) filterIds.add(id);
            selectedLayer = null; selectedId = -1;
        } else if (selectedLayer == layer && selectedId == id) {
            selectedLayer = null; selectedId = -1;  // 取消选中
        } else {
            selectedLayer = layer; selectedId = id;
        }
        needsClear = true;
    }

    public GeoPalette.PreviewLayer getSelectedLayer() { return selectedLayer; }
    public int getSelectedId() { return selectedId; }

    /** 选中色块高亮时，将非选中颜色去饱和为灰（保留亮度对比，不纯黑）。 */
    private static int grayTint(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        gray = Math.max(48, Math.min(200, gray));
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        double guiScale = mc.getWindow().getGuiScale();
        totalDragX -= dx * guiScale * scaleBlockPos;
        totalDragZ -= dy * guiScale * scaleBlockPos;
        dragging = true;
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
            // 重建队列以应用新的有效采样步长（scaleBlockPos / renderScale），避免缩放后网格。
            rebuildQueue();
        }
        return true;
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
        this.peakFraction = params.peakHeightFraction();
        this.verticalScale = params.verticalScale();
        double[] er = params.elevationERange();
        this.elevEMin = er[0]; this.elevEMax = er[1];
        GeoPalette.setElevationERange(er[0], er[1]);
        GeoPalette.setSeaLevel(seaLevel);
        cellCache.invalidateAll();
        pool.cancelAll();
        // ★ 磁盘持久化：seed 或参数变化时重新加载历史（否则保持当前内存缓存）
        long newHash = cacheSchemaHash(params);
        if (seed != diskSeed || newHash != diskConfigHash) {
            diskSeed = seed;
            diskConfigHash = newHash;
            loadDiskHistory();
        }
        // ★ 重建队列：TerrainQueue.terrain 是构造时传入的 final 字段，setTerrain 只更新了
        //   PreviewDisplay.this.terrain，不重建队列则 ChunkWorkUnit 仍用旧 terrain 采样。
        rebuildQueue();
        // 2026-08-05 修复：不再重置视口（center/totalDrag）——配置改动只应刷新画面内容，
        // 保留用户当前位置与缩放（回出生点请用 resetCenter 按钮）。
        needsClear = true;
    }

    public void setMode(int mode) {
        GeoPalette.PreviewLayer newLayer = GeoPalette.PreviewLayer.values()[
            Math.max(0, Math.min(GeoPalette.PreviewLayer.values().length - 1, mode))];
        if (newLayer == activeLayer) return;
        this.activeLayer = newLayer;
        legendScrollOffset = 0;
        needsClear = true;  // 脏检查下切图层必须显式触发重绘（颜色来自 activeLayer）
    }

    public int getMode() { return activeLayer.ordinal(); }
    public GeoPalette.PreviewLayer getLayer() { return activeLayer; }
    public void toggleLegend() { showLegend = !showLegend; }
    public void queueResetViewport() { queue.resetViewport(); }

    /** 向下对齐到 scale 的倍数（负数也用 floorDiv 正确处理）。
     *  <p>视口原点对齐后，paint 的像素坐标 (wx-originWx)/scale 恒整除，
     *  chunk 采样点精确映射到纹理像素，无缝隙/重叠/重影（"渲染打架"根因之一）。 */
    private static int alignDown(int v, int scale) {
        return Math.floorDiv(v, scale) * scale;
    }

    /** e→世界高度 Y（与 HeightCurve.heightFromE 一致的非对称映射：e=0→海平面）。 */
    private double heightFromE(double e) {
        if (e <= 0.0) return seaLevel - (-e) * (seaLevel - minY);
        double t = Math.max(0.0, Math.min(1.0, e * verticalScale));
        return seaLevel + t * (maxY - seaLevel) * peakFraction;
    }
    public void setElevationColormap(String name) {
        GeoPalette.setElevationColormap(name);
        needsClear = true;  // 色带变化 → 脏检查下必须显式触发重绘
    }

    public void setPosition(int x, int y, int w, int h) {
        this.setX(x);
        this.setY(y);
        this.width = w;
        this.height = h;
        int newTexW = Math.max(1, (int)(w * this.renderScale));
        int newTexH = Math.max(1, (int)(h * this.renderScale));
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

    /** 有效采样步长 = 纹理分辨率（屏幕像素 / 每像素覆盖块数）。
     *  恒等于「每屏幕像素 1 次采样」，即渲染分辨率 = 预览窗口显示分辨率 × renderScale，
     *  与缩放无关、不过采样：1:1 与 1:16 采样 cell 总数相同（均为 texW×texH）。 */
    private int effectiveStride() {
        return Math.max(1, Math.min(16, this.scaleBlockPos / Math.max(1, this.renderScale)));
    }

    /** 重建采样队列（缩放/超采样变化时调用，纹理尺寸不变则不重建纹理）。 */
    private void rebuildQueue() {
        // ★ 缩放 = 采样步长语义变化（对齐参考模组 onResolutionChanged→restartExecutors）：
        //   必须立即取消所有旧任务，否则新队列首个 queueGeneration 见 pool.isBusy()=true
        //   （旧 stride 任务还在跑，stride=1 时可能几十秒）→ 一直 return → 画面空白转圈。
        //   cancelAll 已实现 batch.cancel + 中断消费，安全无泄漏。
        pool.cancelAll();
        this.queue = new TerrainQueue(cellCache, pool, terrain, effectiveStride(), structureScanner);
        needsClear = true;
        queue.resetViewport();
    }

    /** 设置纹理超采样倍率并重建纹理 + 队列。 */
    public void setRenderScale(int scale) {
        int newScale = Math.max(1, Math.min(4, scale));
        if (newScale == this.renderScale) return;
        this.renderScale = newScale;
        int newW = Math.max(1, (int)(this.width * this.renderScale));
        int newH = Math.max(1, (int)(this.height * this.renderScale));
        if (newW != texW || newH != texH) {
            texW = newW; texH = newH;
            mc.getTextureManager().release(texLoc);
            image = new NativeImage(NativeImage.Format.RGBA, texW, texH, false);
            texture = new DynamicTexture(image);
            texLoc = ResourceLocation.tryParse("geogenesis:preview_" + System.nanoTime());
            mc.getTextureManager().register(texLoc, texture);
        }
        rebuildQueue();
    }

    /** 强制刷新预览（清除缓存 + 重绘） */
    public void forceRefresh() {
        needsClear = true;
        queue.resetViewport();
    }

    /** 清除全部缓存（运行时 + 当前 seed 的磁盘缓存），对齐参考模组 clearCache。 */
    public void clearAllCaches() {
        cellCache.invalidateAll();
        if (diskSeed != Long.MIN_VALUE) {
            com.geogenesis.client.preview.chunk.PreviewDiskCache.clearFor(diskSeed);
        }
        forceRefresh();
    }

    /** 一键回到出生点/0,0（对齐参考模组 RenderSettings.resetCenter）。 */
    public void centerOnSpawn() {
        if (mc.level != null) {
            BlockPos spawn = mc.level.getSharedSpawnPos();
            if (spawn != null) {
                centerX = spawn.getX();
                centerZ = spawn.getZ();
            } else {
                centerX = 0; centerZ = 0;
            }
        } else {
            centerX = 0; centerZ = 0;
        }
        totalDragX = 0; totalDragZ = 0;
        needsClear = true;
        queue.resetViewport();
    }

    /**
     * 注入结构检测上下文（创建世界界面：WorldCreationContext；游戏内：mc.level）。
     * seed 变化时由 setTerrain 重建 scanner。无法创建 → null（无结构标记，不崩）。
     */
    public void setStructureContext(net.minecraft.core.RegistryAccess registryAccess,
                                    net.minecraft.world.level.chunk.ChunkGenerator generator,
                                    long seed) {
        this.structureScanner = com.geogenesis.client.preview.chunk.StructureScanner.create(registryAccess, generator, seed);
    }

    /** 重置高度边界到默认值 */
    public void resetHeightBounds() {
        // 高度边界由 GeoGenesisConfig 管理，仅触发重绘
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
        // 计算出生点在纹理中的坐标（与渲染同源：对齐后的统一原点）
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        int originWx = alignDown(centerX + (int) totalDragX - blocksWide / 2, scaleBlockPos);
        int originWz = alignDown(centerZ + (int) totalDragZ - blocksHigh / 2, scaleBlockPos);
        int sx = spawn.getX(), sz = spawn.getZ();
        int texSx = (sx - originWx) / scaleBlockPos;
        int texSz = (sz - originWz) / scaleBlockPos;
        if (texSx < 0 || texSx >= texW || texSz < 0 || texSz >= texH) return;

        // 映射到屏幕坐标
        int x = getX(), y = getY(), w = width, h = height;
        int screenSx = x + (int)((double)texSx / texW * w);
        int screenSz = y + (int)((double)texSz / texH * h);
        int markerSize = 6;

        // 金色 X 形罗盘标记（移除白边框方框，避免与正常方块误判）
        int gold = 0xFFFFAA00;
        g.fill(screenSx - markerSize, screenSz - 1, screenSx - 1, screenSz + 1, gold);
        g.fill(screenSx + 1, screenSz - 1, screenSx + markerSize, screenSz + 1, gold);
        g.fill(screenSx - 1, screenSz - markerSize, screenSx + 1, screenSz - 1, gold);
        g.fill(screenSx - 1, screenSz + 1, screenSx + 1, screenSz + markerSize, gold);
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

    /** 绘制玩家位置标记：白圆点 + 深色描边 + 姓名标签（与出生点金色 X 区分）。 */
    private void drawPlayerMarker(GuiGraphics g) {
        if (mc.player == null) return;  // 非游戏世界（无玩家）不画
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        // 与渲染同源：统一对齐原点
        int originWx = alignDown(centerX + (int) totalDragX - blocksWide / 2, scaleBlockPos);
        int originWz = alignDown(centerZ + (int) totalDragZ - blocksHigh / 2, scaleBlockPos);
        int texX = (int) ((px - originWx) / scaleBlockPos);
        int texZ = (int) ((pz - originWz) / scaleBlockPos);
        if (texX < 0 || texX >= texW || texZ < 0 || texZ >= texH) return;  // 玩家在视口外

        int x = getX(), y = getY(), w = width, h = height;
        int sx = x + (int) ((double) texX / texW * w);
        int sy = y + (int) ((double) texZ / texH * h);

        // 深色描边（5×5 圆环） + 白色中心（3×3 圆点）
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 <= 4) {
                    int col = (d2 <= 1) ? 0xFFFFFFFF : 0xFF1a1a2a;
                    g.fill(sx + dx, sy + dy, sx + dx + 1, sy + dy + 1, col);
                }
            }
        }
        // 姓名标签（顶部）
        String name = mc.player.getGameProfile().getName();
        int tw = mc.font.width(name);
        g.fill(sx - tw / 2 - 2, sy - 14, sx + tw / 2 + 2, sy - 3, 0xAA000000);
        g.drawString(mc.font, name, sx - tw / 2, sy - 12, 0xFFFFFF);
    }

    /** 绘制自动检测的结构标记：彩色方块 + 白色中心点，悬停显示全名。 */
    private void drawStructureMarkers(GuiGraphics g, int mx, int my) {
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        int originWx = alignDown(centerX + (int) totalDragX - blocksWide / 2, scaleBlockPos);
        int originWz = alignDown(centerZ + (int) totalDragZ - blocksHigh / 2, scaleBlockPos);
        int x = getX(), y = getY(), w = width, h = height;
        // 悬停命中检测
        String hoverName = null;
        for (var entry : structureScanner.allHits()) {
            for (var hit : entry.getValue()) {
                int wx = (hit.chunkX() << 4) + 8;
                int wz = (hit.chunkZ() << 4) + 8;
                int texX = (wx - originWx) / scaleBlockPos;
                int texZ = (wz - originWz) / scaleBlockPos;
                if (texX < 0 || texX >= texW || texZ < 0 || texZ >= texH) continue;
                int sx = x + (int) ((double) texX / texW * w);
                int sy = y + (int) ((double) texZ / texH * h);
                int color = structureColor(hit.id());
                g.fill(sx - 2, sy - 2, sx + 2, sy + 2, color);
                g.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFFFFFFF);
                if (mx >= sx - 3 && mx <= sx + 3 && my >= sy - 3 && my <= sy + 3) {
                    hoverName = structureName(hit.id());
                }
            }
        }
        if (hoverName != null) {
            int tw = mc.font.width(hoverName);
            g.fill(mx + 10, my + 8, mx + 14 + tw, my + 20, 0xCC000000);
            g.drawString(mc.font, hoverName, mx + 13, my + 10, 0xFFFFFF);
        }
    }

    /** 结构类型 → 标记色（按结构名粗分类，简单可辨） */
    private static int structureColor(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return 0xFFFFAA00;
        String s = id.getPath();
        if (s.contains("village")) return 0xFF00FF88;      // 村庄绿
        if (s.contains("stronghold")) return 0xFF8844FF;   // 要塞紫
        if (s.contains("ancient_city")) return 0xFF00CCFF; // 古城青
        if (s.contains("mineshaft")) return 0xFF888888;    // 矿井灰
        if (s.contains("ruined")) return 0xFFCC8844;      // 遗迹棕
        if (s.contains("ocean_ruin")) return 0xFF2266AA;   // 海底遗迹蓝
        if (s.contains("shipwreck")) return 0xFFCC6644;    // 沉船橙
        if (s.contains("desert_pyramid")) return 0xFFFFCC44; // 沙漠神殿黄
        if (s.contains("jungle_pyramid")) return 0xFF33CC66; // 丛林神殿绿
        if (s.contains("igloo")) return 0xFFAAEEFF;        // 冰屋浅蓝
        if (s.contains("pillager")) return 0xFFCC5555;     // 掠夺者红
        if (s.contains("monument")) return 0xFF22AAAA;     // 海底神殿青
        if (s.contains("fortress")) return 0xFFAA4444;     // 下界要塞红
        if (s.contains("bastion")) return 0xFF883333;      // 猪灵堡垒棕
        if (s.contains("trail")) return 0xFFBBBB88;        // 古迹灰黄
        return 0xFF00AAFF;                                  // 其他蓝
    }

    private static String structureName(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return "结构";
        // 转换 minecraft:village_plains → Village Plains
        String p = id.getPath().replace('_', ' ');
        if (p.isEmpty()) return id.toString();
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
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
                    g.fill(lx, cy, lx + 10, cy + 10, GeoPalette.toARGB(e.color));
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
            int bx = getX() + width - 22, by = getY() + 24;
            // 自适应高度：占 widget 可用高度的 80%，最小 100，最大 500
            int bh = Math.max(100, Math.min(500, (int)(height * 0.8)));
            int bw = 12;
            // 使用已烘焙 LUT 每像素采样，避免运行期 getRGB 开销
            // LUT 256 级，200px → 每像素约 1.28 LUT 级，足够平滑
            for (int i = 0; i < bh; i++) {
                double p = 1.0 - (double) i / (bh - 1);
                int rgb = GeoPalette.continuous(layer, GeoPalette.legendGradientPos(layer, p));
                g.fill(bx, by + i, bx + bw, by + i + 1, GeoPalette.toARGB(rgb));
            }
            String[] lbl;
            if (layer == GeoPalette.PreviewLayer.ELEVATION) {
                lbl = new String[]{"Y=" + (int) Math.round(heightFromE(elevEMax)),
                                   "Y=" + (int) Math.round(heightFromE(elevEMin))};
            } else {
                lbl = GeoPalette.continuousLegendLabels(layer);
            }
            g.drawString(mc.font, lbl[0], bx - 28, by, 0xCCCCCC);
            g.drawString(mc.font, lbl[1], bx - 28, by + bh - 8, 0xCCCCCC);
            g.drawString(mc.font, I18n.get(layer.labelKey), lx, ly, 0x66CCFF);
        }
    }

    /** 读取 hover 位置 Cell（只读缓存，绝不触发地形采样——主线程采样会卡死渲染）。 */
    private Cell sampleHoverCell(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        int lx = wx & 15, lz = wz & 15;
        // 缓存 miss → 返回 null（tooltip 不显示）。地形采样一律交给后台 Worker。
        return cellCache.getCell(cx, cz, lx, lz);
    }

    private void drawHoverInfo(GuiGraphics g, int mx, int my) {
        if (!isMouseOver(mx, my) || hoverX == -1 || terrain == null) return;
        Cell c = sampleHoverCell(hoverX, hoverZ);
        if (c == null) return;

        // 跟随光标 tooltip（偏移 14px，夹取在屏幕内避免越界）
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int lx = (int) mx + 14;
        int ly = (int) my + 14;
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

        String line5 = activeLayer.kind == GeoPalette.Kind.DISCRETE
            ? "左键选中色块 · 右键复制坐标"
            : "右键复制坐标";
        String[] lines = {line0, line1, line2, line3, line4, line5};
        // 自动计算面板宽度
        int maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, mc.font.width(l));
        maxW = Math.max(maxW, 160);
        int panelH = lines.length * 13 + 4;
        if (lx + maxW + 10 > screenW) lx = (int) mx - maxW - 18;
        if (ly + panelH > screenH) ly = (int) my - panelH - 14;
        lx = Math.max(2, lx);
        ly = Math.max(2, ly);
        g.fill(lx, ly, lx + maxW + 10, ly + panelH, 0xCC000000);
        for (int i = 0; i < lines.length; i++) {
            int col = (i == lines.length - 1) ? 0xFF88CCAA : 0xFFFFFF;
            g.drawString(mc.font, lines[i], lx + 4, ly + 4 + i * 13, col);
        }
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

    /** 当前过滤（搜索 + 排序）后的图例条目列表，供图例点击命中索引对齐。 */
    private List<GeoPalette.LegendEntry> visibleLegendEntries() {
        GeoPalette.PreviewLayer layer = getLayer();
        List<GeoPalette.LegendEntry> all = getSortedEntries(layer);
        boolean hasSearch = legendSearchQuery.length() > 0;
        if (!hasSearch) return all;
        String q = legendSearchQuery.toLowerCase();
        List<GeoPalette.LegendEntry> filtered = new ArrayList<>();
        for (GeoPalette.LegendEntry e : all) {
            String name = I18n.get(e.labelKey);
            if (name.equals(e.labelKey)) name = GeoPalette.englishLabel(e.labelKey);
            if (name.toLowerCase().contains(q)) filtered.add(e);
        }
        return filtered;
    }

    /** 返回鼠标 y 命中的图例条目在 {@link #visibleLegendEntries()} 中的索引；未命中返回 -1。
     *  布局须与 {@link #drawLegend} 严格一致（标题/排序/搜索行高度 + 滚动偏移 + 条目行高）。 */
    private int legendHitEntry(double my) {
        if (!legendExpanded) return -1;
        GeoPalette.PreviewLayer layer = getLayer();
        if (!layer.legendable || layer.kind != GeoPalette.Kind.DISCRETE) return -1;
        int ly = getY() + 8;
        int titleH = 14, modeH = 14, searchH = 14;
        boolean showSearch = legendSearchActive || legendSearchQuery.length() > 0;
        int topBarH = titleH + modeH + (showSearch ? searchH : 0);
        int entryY = ly + topBarH;
        List<GeoPalette.LegendEntry> entries = visibleLegendEntries();
        int maxVisibleH = getHeight() - 40;
        int rowH = 14;
        int panelH = Math.min(topBarH + entries.size() * rowH + 6, maxVisibleH);
        int entryMaxH = panelH - topBarH - 6;

        double rel = my - entryY + legendScrollOffset;
        if (rel < 0) return -1;
        int idx = (int) (rel / rowH);
        if (idx < 0 || idx >= entries.size()) return -1;
        int cy = entryY - legendScrollOffset + idx * rowH;
        if (my < cy || my > cy + rowH) return -1;
        // 必须落在 scissor 裁剪可见区内
        if (cy < entryY - rowH || cy > entryY + entryMaxH + rowH) return -1;
        return idx;
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
            case PLAIN -> I18n.get("geogenesis.preview.type.plain");
            case HILLS -> I18n.get("geogenesis.preview.type.hills");
            case PLATEAU -> I18n.get("geogenesis.preview.type.plateau");
            case MOUNTAINS -> I18n.get("geogenesis.preview.type.mountains");
            case PEAK -> I18n.get("geogenesis.preview.type.peak");
            case BASIN -> I18n.get("geogenesis.preview.type.basin");
            case RIVER -> I18n.get("geogenesis.preview.type.river");
            case LAKE -> I18n.get("geogenesis.preview.type.lake");
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
        // ★ 磁盘持久化：关屏保存全部历史（后台线程 + tmp 原子写，不阻塞 UI）。
        //   保存历史层（紧凑数据，含本次会话所有采样 + 磁盘加载的），跨会话完整保留。
        long seedSnapshot = diskSeed;
        long hashSnapshot = diskConfigHash;
        var history = cellCache.historyEntries();
        if (seedSnapshot != Long.MIN_VALUE && !history.isEmpty()) {
            java.util.concurrent.CompletableFuture.runAsync(() ->
                    com.geogenesis.client.preview.chunk.PreviewDiskCache.save(
                            seedSnapshot, hashSnapshot, history));
        }
    }
}
