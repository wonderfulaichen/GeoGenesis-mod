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
import com.mojang.blaze3d.systems.RenderSystem;
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
    /**
     * 平面精确管线（★ 2026-11-09 B4b：从本类剪切出去）。
     *
     * <p>「预览 = 游戏」的保证在这里：逐 chunk 采样 → 写纹理 → 坡度阴影。</p>
     */
    final PlanePainter plane = new PlanePainter(this);
    /**
     * ★ 2026-11-09 B4a：统一控制器（状态 + 模式分派）。
     *
     * <p>模式分派只发生在它的 {@code rasterizer} 字段上 —— 本类不再有
     * {@code if (sandboxMode)} 这样的分支（用户原话：「明明我都说了要重构重构，
     * 结果各自完全独立分裂。」）。</p>
     */
    TerrainQueue queue;
    GeoGenesisTerrain terrain;
    /**
     * 本预览是否<b>拥有</b> {@link #terrain} 的生命周期。
     *
     * <p>配置屏可能复用<b>游戏侧的存档单例</b>（共享已算的 chunk 与水文 region）。</p>
     * <p>这种共享实例<b>绝不能</b>被预览关闭：否则玩家关掉配置屏会关停游戏正在用的引擎
     * （其进程池已 shutdown）。⇒ 关屏/换地形时只在 {@code ownsTerrain} 为真时退役。</p>
     */
    boolean ownsTerrain = true;
    /** 结构扫描器（创建世界界面传入 registry 后可用；null = 预览无结构标记） */
    com.geogenesis.client.preview.chunk.StructureScanner structureScanner;
    /** 显示结构标记 */
    public boolean showStructures = true;
    /** 磁盘持久化：seed 变更时重建；close() 时保存（"已加载的地图被记录"） */
    long diskSeed = Long.MIN_VALUE;
    long diskConfigHash = Long.MIN_VALUE;

    // ====== 纹理 ======
    NativeImage image;
    DynamicTexture texture;
    ResourceLocation texLoc;
    int texW, texH;
    /** 每纹理像素对应的世界块数。越大越缩。 */
    public int scaleBlockPos = 1;
    /**
     * ★ 大范围模式（2026-09-11）：{@code scaleBlockPos > 16} 时走 {@link LargeAreaSampler}。
     *
     * <p><b>为什么必须换管线</b>：{@code chunk/} 引擎是【按 chunk 遍历】且
     * {@code ChunkWorkUnit.blockStride} 上限为 16 —— 视口 30 万格时绘制循环要约 3.7 亿次迭代。
     * 大范围必须走"固定采样数 + 步长缩放"（FreeTerraForged 同款），开销与视野无关。</p>
     */
    boolean largeArea = false;
    /** ★ S2：分块渐进采样器（粗档先铺，逐块补齐到 TARGET_GRID）。 */
    ProgressiveSampler largeSampler;
    /** 粗层水位查询（沙盘河道/湖面用；按 tract 缓存）。 */
    SandboxWaterLookup waterLookup;
    /** 已提交给 GPU 的采样视口 id（采完后需重画一次）。 */
    long largeSamplerIdDrawn = Long.MIN_VALUE;

    /** ★ S3：沙盘（3D 方柱）模式。与平面模式共用同一份采样数据，切换不重采。 */
    boolean sandboxMode = false;

    // =================================================================
    // ★ v25：视图切换过渡动画（照参考项目 YardSiteScreen 第 61/383-404 行）
    // =================================================================
    //
    // 参考项目在「沙盘 ↔ 区块精读」之间切换时有一段 620ms 的过渡：
    //   ① 旧图 alpha 渐隐 + 高度比例下沉（1 → 0.45）
    //   ② 新图 alpha 渐显 + 高度比例长起（0.45 → 1）
    //   ③ 镜头轻推近（zoomBoost 0.84 → 1.0）
    //   ④ zoom 与 widthScale 也用同一条 smoothstep 插值
    //
    // 我们原先是<b>硬切</b>（切模式下一帧直接换画面）⇒ 视觉上"啪"一下。
    // ⇒ 补上同口径的过渡。

    /** 过渡总时长（毫秒，参考项目 {@code TRANSITION_MS=620}）。 */
    private static final long TRANSITION_MS = 620L;

    /** 过渡结束时刻；0 = 没在过渡。 */
    private long transitionEndAt = 0L;
    /** 过渡起点（用于算进度）。 */
    private long transitionStartAt = 0L;

    /** 触发一次视图切换过渡（从当前模式切到另一模式）。 */
    private void beginViewTransition() {
        long now = System.currentTimeMillis();
        transitionStartAt = now;
        transitionEndAt = now + TRANSITION_MS;
    }

    /**
     * 当前过渡进度（0=起点，1=终点）；不在过渡时返回 1。
     *
     * <p>用参考项目同款 smoothstep：{@code p*p*(3-2p)}。</p>
     */
    private float transitionEase() {
        if (transitionEndAt == 0L) return 1f;
        long now = System.currentTimeMillis();
        if (now >= transitionEndAt) {
            transitionEndAt = 0L;
            return 1f;
        }
        float p = 1f - (transitionEndAt - now) / (float) TRANSITION_MS;
        return p * p * (3f - 2f * p);
    }

    /** 过渡是否正在进行。 */
    private boolean inTransition() {
        return transitionEndAt != 0L && System.currentTimeMillis() < transitionEndAt;
    }
    /**
     * ★ 2026-11-09 B3：沙盘的 rasterizer（只负责画，几何在 {@code SandboxGeometry}）。
     * 旧的 {@code SandboxRenderer}（1132 行，自带一套 collect/sort/pick/投影）已停用 ——
     * 它与 {@code SandboxGeometry} 构成两套沙盘实现，正是本轮要消灭的"独立分裂"。
     * B4 删文件。
     */
    private com.geogenesis.client.preview.raster.SandboxRasterizer sandboxRasterizer =
            new com.geogenesis.client.preview.raster.SandboxRasterizer();
    /** 绘制时复用的 Cell 实例，避免每像素分配。 */
    final Cell paintedCell = new Cell();
    /** 纹理超采样倍率（渲染分辨率 = 预览窗口逻辑分辨率 × renderScale）。
     *  1=显示器模型（纹理=窗口逻辑像素，GPU 负责 DPI 上采样，最快）；2/3/4=更锐利但更慢。 */
    public int renderScale = 1;

    // ====== 视口 ======
    /** 视口中心世界坐标 */
    int centerX = 0, centerZ = 0;
    /** 拖拽累积偏移（松手后并入 center） */
    double totalDragX = 0, totalDragZ = 0;
    /** 拖拽中标志：拖拽时跳过最贵的坡度阴影（松手后一次性补画），保证拖动流畅 */
    boolean dragging = false;

    // ====== 渲染脏检查（避免静止帧无谓重画/重传，消除"渲染打架"） ======
    /** 上帧已画进纹理的视口原点（对齐 scale 后），未变 → 视口未动 */
    long lastOriginWx = Long.MIN_VALUE, lastOriginWz = Long.MIN_VALUE;
    /** 上帧已画进纹理的 CellCache 版本号，未变 → 无新数据 */
    long lastCacheVersion = -1;
    /** 上帧已画进纹理的图层，未变 → 颜色无需重算 */
    int lastLayerOrdinal = -1;
    int lastSelectedLayerOrdinal = -1, lastSelectedId = -1;

    // ====== 图层 ======
    GeoPalette.PreviewLayer activeLayer = GeoPalette.PreviewLayer.ELEVATION;

    // ====== UI ======
    boolean showLegend = true;
    int legendScrollOffset = 0;
    int legendSortMode = 0;
    static final String[] LEGEND_SORT_NAMES = {"默认", "名称", "分类"};
    /** 图例搜索：非空时过滤条目 */
    String legendSearchQuery = "";
    /** 搜索框是否激活（接收键盘输入） */
    boolean legendSearchActive = false;
    /** 图例是否展开（false 时只显示标题条） */
    boolean legendExpanded = true;
    int hoverX = -1, hoverZ = -1;

    // ====== 交互增强 ======
    /** 帧时间统计（最近 30 帧的毫秒数） */
    final Queue<Long> frameTimes = new ArrayDeque<>();
    /** 右键复制坐标的消息（非 null 时显示） */
    Component copiedToastMsg = null;
    /** 右键复制消息的创建时间 */
    Instant copiedToastTime = null;
    /** 离散图层选中（通用）：选中图层 + 离散 id（-1=未选中）。
     *  用于点击地图/图例/列表高亮该色块、其余置灰，与参考项目选中行为一致。 */
    GeoPalette.PreviewLayer selectedLayer = null;
    int selectedId = -1;
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
    /** 显示侵蚀 tile 边界（ERODE_TILE_CENTER=48wu 网格线，诊断 tile 边缘错位/微断裂用）。
     *  块坐标线距 = 48 × horizontalScale；线处像素压暗。 */
    public boolean showTileBorders = false;
    /** 水平缩放（块/wu；构造时从 params 取，tile 边界线定位用） */
    double horizontalScale = 1.0;
    /** 拖动时简化视图（跳过最贵的坡度阴影保证 60fps；松手后第一帧补画）。false = 拖动也画完整阴影 */
    public boolean dragSimplify = true;
    /** 公开 CellCache 引用，供外部清除缓存 */
    public CellCache cellCache = new CellCache();
    /** 结构图标叠加层（可选 JSON，无数据则跳过） */
    private final StructureIconOverlay structureOverlay = new StructureIconOverlay();

    // ====== 数据 ======
    long seed;
    int seaLevel, maxY, minY, mountainCap;
    double peakFraction, verticalScale;
    /** 高程色阶映射的 e 区间（地形实际可达范围），供图例 Y 标签换算。 */
    double elevEMin = -1.0, elevEMax = 1.0;
    /** 视口变化标记：需要清除纹理重新填充。pan/zoom/seed 变时设 true */
    public boolean needsClear = true;

    private final Minecraft mc = Minecraft.getInstance();

    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode) {
        this(x, y, w, h, terrain, seed, params, mode, 1, true);
    }

    /**
     * @param ownsTerrain false = 该 terrain 是<b>共享游戏侧存档单例</b>，预览不得关闭它
     */
    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode, boolean ownsTerrain) {
        this(x, y, w, h, terrain, seed, params, mode, 1, ownsTerrain);
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
        this(x, y, w, h, terrain, seed, params, mode, renderScale, true);
    }

    /**
     * @param ownsTerrain 是否拥有该 terrain 实例（false = 共享游戏侧的存档单例，
     *                    预览不得关闭它，见 {@link #setTerrain(GeoGenesisTerrain, long, TerrainParams, boolean)}）
     */
    public PreviewDisplay(int x, int y, int w, int h,
                          GeoGenesisTerrain terrain, long seed,
                          TerrainParams params, int mode, int renderScale, boolean ownsTerrain) {
        super(x, y, w, h, Component.literal("GeoGenesis Preview"));
        this.terrain = terrain;
        this.ownsTerrain = ownsTerrain;
        this.seed = seed;
        this.seaLevel = params.seaLevel();
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        this.maxY = params.maxY();
        this.peakFraction = params.peakHeightFraction();
        this.verticalScale = params.verticalScale();
        this.horizontalScale = params.horizontalScale();
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
    //   2026-08-10 wu 化：侵蚀 tile 网格/坐标语义变更 → 旧磁盘缓存必须失效（15）。
    //   2026-08-10 骨架抬升衰减（高原平顶假峰修复）：地形产出变化 → 16。
    //   2026-08-10 骨架梯度 hs 换算（wu 化坡度漂移修复）：HS≠1 产出变化 → 17。
    //   2026-08-10 液滴 SLOPE_MIN_SKIP/CASCADE_MAXDIFF ×hs（wu 化阈值漂移修复）：HS≠1 产出变化 → 18。
    //   2026-08-11 引擎回退到 78bf8bc（用户认可版）+ cascade budget 修复（真 NaN 修复）：
    //   TF 对齐版（23）废弃；回退版产出 ≠ 18（带 budget）→ 19 强制重算。
    // 34 = 2026-09-10 河流绿洲（Cell.riverDistance + Cell.oasisNoise）
    // 33 = 2026-09-10 变体抖动波长 64→128 wu（变体斑块更完整）
    // 32 = 2026-09-10 群系变体抖动（Cell.variantTerrain；消除地形边界导致的长直线）
    // 31 = 2026-09-10 区界 5 倍频扰动（气候区边界形状改变）
    // 30 = 2026-09-10 气候双轨（Cell.biomeType 参与群系；eClimate/snowLineE 参与垂直带与雪）
    // ⚠ 任何影响 Cell 采样结果的代码改动都必须递增本版本，否则旧磁盘缓存会被静默复用，
    //   表现为"改了没生效 / 新旧数据混成矩形块"。
    // 35 = 2026-09-12 地质伪影修复（T5 断层去量化 + 褶皱脊线圆化 + T2 chain 去平行带
    //      + T3 高原去 pow 奇点）→ 地形产出变化，旧缓存必须失效
    // 36 = 2026-09-12 平滑正部（max(0,stress) → smoothPos）+ 去硬截断跳变
    //      → 消除沿 stress=0 等值线的折痕（笔直断裂线根因）
    // 37 = 2026-09-12 域扭曲改短波长(400wu)/低幅度(130) → 板块边界不再呈"笔直断裂线"
    // 38 = 2026-09-12 boundaryStrength 去掉 btype==INTERIOR 硬截断（dist≈320 环状跳变线/串珠虚线）
    // 39 = 2026-09-12 dist 环形模糊（对齐 worldgen elevation.rs 的 blur_grid）
    //      → 消除 Voronoi 顶点折痕（扇形射线/竖带）
    // 40 = 2026-09-12 valueNoise 升级 Catmull-Rom 双三次（C¹）→ 消除格线折角/扇形直线
    // 41 = 2026-09-12 T5 定位改用世界坐标构造带掩码 beltMask（reach 900/700→2600）
    //      → 不再把 Voronoi 多边形的直边/折角印到地形上
    // 42 = 2026-09-12 ★ 撤销 HILLS/PLATEAU 的 |2n−1| 折叠（TypeNoiseProvider.foldHills）
    //      → 消除"密集波浪状平行细线"（HILLS/PLATEAU 高通幅值 0.42/0.46 → 0.19/0.16；
    //        折叠使梯度处处满值 + 频率翻倍 + 折痕成对等值线，三者叠加即伪影）
    // 43 = 2026-09-12 ★ blurDist 硬切换改 smoothstep 渐隐（第七次伪影修复）
    //      → 原版在 dist=420 等值线（= Voronoi 多边形偏移网）上产生 dist 阶跃，
    //        经 boundaryStrength→T1 权重印进 eLand = "笔直线段 + Y 形交汇"伪影
    // 44 = 2026-09-13 ★ stress 改为【连续加权投票构造】（删 smoothStress/配对速度差）
    //      → 旧式应力是"分片常数场"（P50|∇|=7.9e-17），配对切换处沿 Voronoi
    //        直线网阶跃 = 笔直线段 + Y 形交汇伪影的最终根因
    // 45 = 2026-09-13 ★ Phase T6 海洋侧构造地形（对齐参考项目 worldgen 的 boundary_profile）：
    //      ① 洋中脊位置由【随机掩码】改为【构造离散带门控】（OceanFeatures.ridgeGate）
    //      ② 新增火山弧（俯冲带离轴隆起，TectonicField.oceanProfile 正部）
    //      → 海洋侧地形产出变化，旧缓存必须失效
    // 46 = 2026-09-13 ★ T6 补齐（对照参考项目 docs/map.png 与 properties.rs 标定）：
    //      ① 火山弧 0.06 → 0.10 e（对齐参考项目"弧高/深海深 ≈ 39%"的 29%）
    //      ② 新增海沟【窄槽】（此前负部被弃，只剩 T1 的宽带加深，没有"槽"）
    //      ③ 弧改用独立深海门控 arcFade + 软天花板（保证弧不露头，同参考项目）
    // 47 = 2026-09-13 ★ T6 剖面标定（实测俯冲带横剖面驱动）：
    //      arcFade 门控区间 (-0.30,-0.12) → (-0.16,-0.10)：原区间把典型汇聚点
    //      （eOcean≈-0.20）压到 0.39 倍 → 实测弧峰仅 0.0075e；修正后 0.0560e，
    //      剖面呈现"边界线窄槽 + 离轴 300wu 弧峰"的标准俯冲带形态
    // 48 = 2026-09-13 ★ Phase T7(P0) 地形分支判据由【形态学 landW/oceanW】
    //      改为【地质学壳属性 shellFromC(c)】→ 陆-洋汇聚不再"两边都造山"，
    //      而是陆侧造山 + 洋侧海沟（对齐 worldgen 的 current_pid 判据）
    // 49 = 2026-09-13 ★ Phase T7(P0.5) 海床护栏（OceanFeatures 特征不得抬出海平面）
    //      → 消除长期存在的"海洋特征造陆"（洋中脊 22 例 / 火山弧 17 例 → 0）
    // 50 = 2026-09-13 ★ Phase T7(P4) 陆地火山布点改由【俯冲带弧轴门控】驱动
    //      → 火山从"随机散布"改为"沿俯冲带成链"（实测弧轴上富集 4.02×）
    // 51 = 2026-09-13 ★ 两处用户反馈修复：
    //      ① 侵蚀后重分类未传 landFeat → 火山类型 100% 丢失（实测 0/41 保留）
    //      ② 海洋分带改用 eLand（海岸线本就由它定义）→ 陆架包住大陆
    //         （沿海 SHELF 覆盖率 52.2% → 81.4%）
    // 52 = 2026-09-13 ★ 火山类型判据改用【山体掩码】（不扣火口下凹）
    //      → 火口中心不再可能因 net 跌破阈值而被判成周围类型（防"环形火山"）
    // 53 = 2026-09-13 ★ 两处用户反馈修复：
    //      ① 特征海陆门控由 oceanW（类型场，内陆可达 0.42）→ landFactor（与地形同源）
    //         → 火山不再被削减（实测同点峰顶 217.9→245.5 格；分布 P50=24.5/P90=56.4 格）
    //      ② 火山类型阈值 0.05e(≈11.8格) → 0.010e(≈2.4格) → 类型覆盖扩展到可见山体
    // 54 = 2026-09-13 ★ 用户反馈"火山仍矮 + 火山区域边界有笔直斜线"：
    //      ① 陆地火山门控由 arcGate（含 Voronoi dist 高斯 ⇒ 等值线是直线 ⇒ 笔直边界）
    //         改为 arcVolcanism（纯 smoothPos(stress) 区域场）—— 对齐"禁止用 dist 作门控坐标"铁律
    //      ② 火山幅度上调：单体 0.12~0.30 → 0.20~0.42 e（半径同步 120~260 → 140~300）
    //         火山区 0.05~0.12 → 0.12~0.28 e（半径同步 40~90 → 70~160，保持坡度自然）
    //         ⇒ 实测火山抬升 P50 24.5 → 30.1 格、P90 56.4 → 78.1 格、max 124.9 格
    // 55 = 2026-09-14 ★ 用户反馈"近海火山被海陆过渡斜切、火口被压浅 + 孤立火山太少"：
    //      ① 火山海陆判定由【逐点 × landFactor(x,z)】改为【按火山中心整体缩放】
    //         （LandFeatures.centerFactorOf / CellGenerator.landFactorAt 注入）
    //         → 山体不再被斜切、火口深度完整；中心在深海则整座不生成（不造陆）
    //      ② 孤立火山恢复：基线概率 3% → 8%、弧轴 boost 0.55 → 0.35（互补）
    // 55 = 2026-09-14 ★ 用户反馈"近海火山被海陆过渡斜切、火口被压浅 + 孤立火山太少"：
    //      ① 火山海陆判定由【逐点 × landFactor(x,z)】改为【按火山中心整体缩放】
    //         （LandFeatures.centerFactorOf / CellGenerator.landFactorAt 注入）
    //         → 山体不再被斜切、火口深度完整；中心在深海则整座不生成（不造陆）
    //      ② 孤立火山恢复：基线概率 3% → 8%、弧轴 boost 0.55 → 0.35（互补）
    // 56 = 2026-09-14 ★ 用户反馈"火山地形出现被切掉一些的不自然情况"：
    //      ① 门控由【存在性】移到【幅度】：旧实现 `阈值 + boost×65536` 让连续门控
    //         扫过离散哈希值时整座锥突现/突消（实测 4wu 内 field 0→0.111、e 跳 26 格）
    //         → 改为存在性只看固定哈希、boost 只缩放高度（ampBoost）
    //      ② 火山区掩码阈值不再随门控下移（同一缺陷的"区域版"）
    //      → 地形产出变化，旧缓存必须失效
    // 57 = 2026-09-14 ★ Phase T8(P3)：岩性 → 侵蚀耦合（软岩成谷、硬岩成脊）
    //      · RockType.resistance()（既有预留接口）经 StratumField.resistanceAt
    //        → CellGenerator.rockResistanceAt → 注入 ErosionEngine
    //      · 侵蚀量按抗蚀性调制（倍率 = 1−0.6×resistance，上限 1 = 只压不放大）
    //      · 硬度场 8wu 粗采 + 双线性插值（避免阶跃；地质单元尺度达数百 wu）
    //      → 地形产出变化，旧缓存必须失效
    // 58 = 2026-09-14 ★ Phase T9：垂直岩层（岩性 → 方块，让地质"看得见"）
    //      · StratumField.sequenceIds/packSequence/seqAt（地层序列打包，零分配）
    //      · Cell.rockSeqPacked（3bit×4 层）
    //      · GeoGenesisGenerator.ROCK_BLOCKS：8 岩性 → 原版方块
    //        （花岗岩/砂岩/玄武岩/安山岩有同名方块；片麻岩+片岩→深板岩；
    //          页岩→黏土、石灰岩→方解石）
    //      · 陆地列地下按【深度】铺垂直岩层（层厚 24），海洋列保持原样
    // 59 = 2026-09-14 ★ Phase T9b：岩层厚度可变 + 方块映射遵守 MC 设定
    //      · StratumField：各层厚度由噪声驱动（5bit 级别 → 5~42 块，随空间变化）
    //        用户反馈"现实里面的岩层不可能固定厚度的"
    //      · 方块映射修订：弃 DEEPSLATE（MC 限定 Y<0，导致 29.8% 列被迫回退 STONE）
    //        → 片麻岩=闪长岩 / 片岩=凝灰岩 / 页岩=黏土（全部【深度无关】）
    //      · 地层序列修订：克拉通/造山带改为"花岗岩在前"（浅部成因岩先出露）
    // 60 = 2026-09-14 ★ Phase T9c：地表裸岩跟随岩层 + 方块映射放宽
    //      · 修复：陡坡裸岩 / 群系裸岩（原硬编码 STONE）改为按该列【最浅层岩性】出露
    //        （用户反馈"表面陡峭坡的裸露岩石还是石头，并不是岩层的方块"）
    //      · 映射放宽（用户建议"不一定要按现实名称，可用陶瓦等"）：
    //        页岩→陶瓦（硬化黏土=页岩固结，参考 MC 恶地地层）
    //        石灰岩→白色陶瓦（弃方解石：其仅生成于紫水晶洞 Y≤30，浅部违和）
    // 61 = 2026-09-14 ★ Phase T10：地层水平化（真实水平沉积，非披盖式）
    //      · 层界由【绝对 Y】决定（+ 区域倾斜 tilt 模拟褶皱），层序垂直循环
    //        ⇒ 同一海拔的岩性横向连续，山地被切割后山坡露出水平条带
    //        （对齐探索结论「区域层序 + 绝对 Y 基准 + 倾斜」，超越 RTF 的披盖式）
    //      · 保留 T9b 的可变层厚（5~42 块噪声）
    //      · StratumField.tiltAt（1/1200 低频倾斜噪声，幅度 ±30 块）
    //      · Cell.rockTilt（逐列缓存倾斜，方块层复用）
    //      · 查表 LUT（周期内每格 Y → 岩性）⇒ 逐 y O(1)，无性能损失
    //      · 地表裸岩同步：陡坡不同高度露出不同岩层 ⇒ 崖壁天然呈水平彩条
    //      注：地表植被/表土不变，仅地下与陡坡裸露受影响
    // 62 = 2026-09-14 ★ Phase T11：坡度分档（参考 RTF ErodeFeature）
    //      · 新增【碎石坡 scree】档：坡度 0.30~0.40（原只有 >0.40 裸岩一档，
    //        山地"草→裸岩"突变）。材质用 RTF placeScree 的加权混合：
    //        安山岩×2、凝灰岩×2、砾石×1、粗泥×1（岩土混杂 ⇒ 与草地自然过渡）
    //      · 新增【恶地色带】：干旱/沙漠群系的陡崖按坡度分档各色陶瓦
    //        （橙/棕/红/黄），即 MC 恶地群系表现地层的方式
    //      · 坡度判定加【逐格确定性抖动】(±0.06)，避免边界沿等坡度线成光滑曲线
    //      · 实测覆盖：裸岩 28.1% / 碎石坡 16.2% / 植被 55.7%（植被仍占多数）
    // 63 = 2026-09-14 ★ Phase T10b：层界多尺度起伏（修复"层界是平直直线"）
    //      · 用户反馈"你这个岩层没点轻微浮动吗？"+ 截图显示层界完全水平
    //      · 根因：T10 单用 1/1200 低频倾斜 ⇒ 玩家视野(~100块)内仅变化 ~2.5 块
    //      · 修复：多尺度叠加 —— 1/1200±30（区域倾斜）+ 1/220±7（局部褶皱）
    //        + 1/55±2.5（细节摆动），各层独立 salt
    //      · 实测 128×128 视野窗口内：平均起伏 6.5 块、最大 12.9 块
    //        （此前 ~2.5 块 ⇒ 视觉上平直）
    // 64 = 2026-09-14 ★ 性能修复（用户"速度没恢复"）：岩性硬度缓存量化
    //      · rockResistanceAt 的缓存键由【精确坐标】改为【16wu 网格量化】
    //        （原键导致命中率 ~0%，每点重算 continent+tectonic ≈22us）
    //      · 采样点同步改为网格中心 ⇒ 同一单元取值恒定，地形产出有细微变化
    //      · HARDNESS_SPACING 与量化粒度对齐（16wu）
    //      → 地形产出变化，旧缓存必须失效
    // 65 = 2026-09-14 ★ 河网冷启动性能（用户"刚创建加载有一段无动静的空闲期"）
    //      · region() 的 3×3 邻居 pass-1 改为并行（相互独立、无递归 ⇒ 逐位一致）
    //      · routingE / groundYAt 加坐标级缓存（纯函数 ⇒ 逐位一致）
    //      实测首 chunk 1996ms → 1327ms；e/gy 缓存均随 seed 失效，产出不变
    //      （CACHE 版本仅在产地形确实变化时才需升；本项为等价重构，用 65 保守失效一次）
    // 66 = 2026-09-14 ★ 构造地貌可见化 + 盆地抬离海平面（用户"构造地貌看不到""靠海出现盆地"）
    //      · TectonicDeformation.FAULT_AMP 0.045→0.090、SCARP_HALF_WIDTH 0.30→0.18、
    //        beltMask 偏置 +0.10（构造带覆盖率 ~35%→48%）⇒ eLand 产出变化
    //      · SplineConfig BASIN hi 0.02→0.05（盆顶抬到海平面上 9.6 块）⇒ 地形产出变化
    //      （碎石坡汇流门控只改方块材质，不改 e/高度，但其判定读 Cell ⇒ 缓存需同批失效）
    // 67 = 2026-09-14 ★ 地质→群系耦合（Phase T12，用户"地质和群系有关系吧？"）
    //      · BiomeClassifier.pickLandKey 的 LOWLAND 管道末端追加 soilVariant
    //        （成土母质变体：钙质土→MEADOW、酸性土→BIRCH_FOREST，噪声门控 30% 覆盖率）
    //      · 群系产出变化 ⇒ 预览 BIOME 图层与磁盘缓存必须失效
    //      （地形 e/高度不变，但 Cell 缓存含 biome 相关派生值）
    // 68 = 2026-09-14 ★ 移除 T5 最后一个 decay(dist)（CHANGELOG 遗留项）
    //      · foldOffset / faultOffsetUnit 不再乘 decay，定位仅由 beltMask 决定
    //      · 消除"贴合板块多边形"的形状来源（残留"淡淡多边形棱面"）
    //      · eLand 产出变化（形变略增：均值 0.64→0.67 块、p99 6.56→7.07 块）
    // 69 = 2026-09-14 ★ 峡谷（大峡谷式）：高原深谷的谷壁跨度收窄
    //      · HydrologyBlockCarver 在岸高 h≥canyonMinBank 的河段收窄谷壁跨度
    //        （bankSlopeRun 1.5→0.40、bankFactor 2.5→1.6）⇒ 陡壁
    //      · **水面完全不动** ⇒ 水文标定零影响（FlowAccum/WaterFill 实测逐位一致）
    //      · 雕刻产出变化 ⇒ 旧预览缓存必须失效
    // 70 = ⚠ 2026-09-18 校正：本条原写"已撤销、不再回收"，与 git 历史不符 —— 70 实际被用过
    //      【两次】：①"类型场启用域扭曲 WARP_AMP 0→40"（实机反馈河流/湖泊出问题 ⇒ 回退 69）；
    //      ②又被 07f0a10 复用为"湖岸 1 块精度精修（LAKE_FINE_FLOOD）"，直至 b465f9d 才升 71。
    //      ⇒ "不再回收"的约定实际已被破坏过一次。今后一律取【当前 max + 1】，不再回收。
    // 71 = 2026-09-17 ★ blendTileDelta 确定性修复（tile 边界 blend 不再随机缺失）
    //      · 产出不再依赖"邻居 tile 是否在缓存" ⇒ 旧预览缓存必须失效
    // 72 = 2026-09-17 ★ 湖岸粗格 BFS 12wu → 6wu（直边网格伪影）
    //  —— 2026-10-05 model 水文链开关 hydrologyModelPipelineEnabled 接入，**不升本版本**：
    //     它已进 GeoGenesisConfig.configFingerprint（经下方 cfg 混入 hash）⇒ 切换开关
    //     磁盘缓存自动失效重采；且默认 false = 产出逐位不变。留痕于此，免后人重复排查。
    //     ★ 2026-11-07：该键已删除（2026-10-20 起就是 no-op 常量，后已从指纹移除）。
    //       本条仅作历史留痕，不代表现在还有这个开关。
    // 73 = 2026-10-11 model 模式暂停 legacy 距离驱动的绿洲群系转换，旧 riverDistance 缓存失效。
    //      河湖几何、侵蚀与水位不变；legacy 模式保留绿洲。
    // 74 = 2026-10-11 预览必须 terrain.seed(seed)，修正只播地形却用水文seed=0的错误缓存。
    // 75 = 分层水文受控实验：策略指纹纳入预览键，实验与当前生产缓存互不混用。
    // 76 = 分层跨区湖域共享求解：同一候选盆地不再按区块窗口分别裁决水位。
    // 77 = 2026-10-14 侵蚀 tile 边界对称半权重混合，消除归属切换时 delta 互换阶跃。
    //      边界带地形及其派生河湖产出变化，旧预览缓存必须失效。
    // 78 = 2026-10-14 骨架合法短接力（入湖/入海/汇流/窗口接口）不受独立源长度门限截断。
    // 79 = 2026-10-14 等水位改道排除原父链返回本链的邻格，防止自JOIN伪汇流。
    // 80 = 2026-10-15 类型高度混合保留全部正权重，消除0.001硬截断的地形跳变。
    // 81 = 2026-10-16 短骨架回滚释放新认领海口，防止幽灵覆盖抢先截断后续汇流。
    // 82 = 2026-10-16 骨架交叉守卫使用严格异号，合法端点汇流不再被误判为穿越。
    // 83 = 2026-10-16 本链几何自交守卫及穿回父边子树禁入，避免平地改道打结而不删历史源。
    // 84 = 2026-10-16 等水位盆底沿父链爬升脱困后持续至水位下降，避免反复改道形成梳齿。
    // 85 = 2026-10-16 平地选邻先排除交叉候选，再选最低床面，避免合法次选/父链被整河回滚。
    // 86 = 2026-10-17 分层骨架采用世界格心后继和唯一上游单元门限，不再按接收窗口重新起河。
    // 87 = 2026-10-17 世界边裁切包含穿过区角的反对角线内部列，不仅检查两端点归属。
    // 88 = 2026-10-17 世界盆体统一湖面/稳态扣损净出流及平台后继；河面不再消费局部窗逃逸高程。
    // 89 = 2026-10-17 盆内仅发布真实过门限入流到溢口的支撑路径，存储BFS不再全足迹冒充河网。
    // 90 = 2026-10-17 按需门限证书代替逐格完整上游Q；保留盆蒸发与入口支撑，精确值和下界分型。
    // 91 = 2026-10-18 后继统一整数列可通过屏障，修溢口成员被raw跨脊归入另盆的双重雨源身份。
    // 92 = 2026-10-18 全首割Face共享唯一整数鞍点时按严格唯一外向坡率选口，重建见证并统一首接收盆。
    // 93 = 2026-10-18 model普通水面试用稳定源水，避免原版fluid tick衰减；legacy与瀑布水幕不变。
    // 94 = 2026-10-18 世界盆湖岸按固定cell四角同盆水位和逐块面连通细化，不再整4×4取低角身份。
    // 95 = 2026-10-19 世界同盆已发布河廊消费盆单平面，不再以逐列地形充当河面而顺流爬坡。
    // 96 = 2026-10-19 盆内树从蓄水低部连接唯一出口主干，再接入支流，避免纯lip BFS绕岸。
    // 97 = 2026-10-19 湿段优先接入后补齐被固定干主干隔开的支湾，保持已有后继而不漏盆成员。
    // 98 = 2026-10-20 生产移除 legacy RiverCenterlines 线：骨架成唯一河网线（SKELETON_TRACE 恒 true），
    //      探针/实机从此同线；riverModel 证据场不变。产出含河网几何变化 ⇒ 旧缓存必须失效。
    // 99 = 2026-10-20 legacy 水文链整体退役：HydrologyChunkEngine 恒走 model 链（实机水从 legacy
    //      切到 model 链产出）；湖逃逸重算/湖岸精修随 legacy LakeNode 移除，isLake 判据化简为
    //      lakePlan；绿洲河距永久旁路（恒 ∞）。实机产出整体变化 ⇒ 旧缓存必须失效。
    // 100 = 2026-10-21 河网密度对齐物理动线参照：成河门限 channelMinCells 150 → 40、
    //       河段长度下限 skeletonMinNodes 6 → 14。河格 +51%、参照覆盖率 +8.4pt；
    //       同时把参数派生收敛到 ModelHydrologyProvider.productionParams 单一来源。
    //       产出河网整体变化 ⇒ 旧缓存必须失效。
    // 101 = 2026-10-22 本区/邻区落块保留 SegmentIndex 的真实穿块命中，移除重复格心距离筛选。
    //       不改河线、半宽或水位，恢复被误丢的穿块列；旧预览缓存必须失效。
    // 102 = 2026-10-22 窗外续流保留出窗/再入窗的真实顺序，单点窗外尾段也参与几何。
    //       cell 保留再入窗拓扑终点，几何仅用出窗前缀接有序世界尾段，避免重排长弦。
    // 103 = 2026-10-22 本区/邻区边界带改为闭区间（低侧原漏一整行）。实测南北接缝 z=regLoZ+HALO
    //       处已有邻区几何却不查询 ⇒ 294 列不雕刻不出水；河列与相邻 region 接缝恢复。
    // 104 = 2026-10-22 移植 legacy bestHandoffStart：接力种子落局部极小时在 1 格窗口内
    //       改选有真实下坡的格，避免"种子到了却起链即死"（缝那侧有河这侧没有）。
    //       仅作用于【接力】起点，不改变源头/溢口，也不伪造终点（默认样例 0 次触发）。
    // 105 = 2026-10-22 JOIN 归因分离（JoinKind，只读）+ 修复 A 开关入库。**产出未变**：
    //       修复 A 单变量 A/B 实测开启后水平长直 26→32 条（新增 z=96/-416/-88 数条），
    //       用户要治的 z=608 两条一字未变 ⇒ 默认关闭，保持既有产出。
    // 106 = 2026-10-22 修复 B 开关入库（frameEscape）。**产出未变**：实测从边框格按窗外
    //       真实地形下降 92 步停在 (-366,653)，该处 8 邻全更高（真实洼地）⇒ 窗外续流
    //       如实返回 null，开关开/关产出一致。真相是「边框横向长直」+「窗外洼地」叠加，
    //       需先有盆/溢流出口才能修。默认关闭。
    //  ⚠ 本清单是【唯一留痕处】：新增产出改动请先在此补条目，再改下面的常量值。
    // 107 = 2026-10-25 迁移用户确认的目标效果：湖岸出水判据对齐方块放置（①）、
    //       溢口短段不再无条件豁免长度下限（②）、入湖末端延伸仅对 ≥6 节点（③），
    //       并把 boundaryPorts 默认置开（跨区端口/typed 接力/双 pass）。**产出有变**：
    //       湖 91336→105182、河 37304→37270、孤立河 17→6、河终点距湖>6格 20→0。
    // 108 = 2026-10-25 河形改用物理向量连续位置（贝塞尔素材，CONT_BLEND 默认 0.75）
    //       + 湖列判据放宽到「地面 == 水面」以触发水柱保护。**产出有变**：
    //       湖 105182→106026、河 37270→37587；河由格心共线直线变为带连续曲率的曲线。
    //  ⚠ 本清单是【唯一留痕处】：新增产出改动请先在此补条目，再改下面的常量值。
    // 109 = 2026-10-26 河道沿程宽度改为平滑趋势与真实JOIN/typed边界属性衔接；骨架坐标不变。
    // 110 = 2026-10-26 真源头最小起宽，同向路径携带pass1宽度并向下游继承，避免跨段重新收尖。
    // 111 = 2026-10-26 真河源到首个接点按弧长线性展宽，避免固定32格内突然恢复粗河。
    // 112 = 2026-10-26 河湖接口按实际湿宽限幅，出湖用净出流，真支流不再钉成干流宽度。
    // 113 = 2026-10-27 出湖半宽改为跨窗口取较小者（同一出口格在相邻窗口可能判成不同源盆）。
    // 114 = 河深/横断面候选接入（默认关闭），实验开关纳入缓存身份；保留认可的骨架/宽度。
    // 115 = 跨区借湖时同步借用命中湖的水位，修正区域边界整齐水面错层；不改河道断面。
    // 116 = 岸水高差候选（默认关闭）：按地形坡度下移河面0—1格，同步河床，湖海附近渐隐。
    // 117 = 普通河面不超侵蚀地形，陡坡额外向下补挖1格；宽深断面默认接入，W3中心2格。
    // 118 = 2026-10-29 骨架 PORT_EGRESS 默认由关改为开，与 Provider.BOUNDARY_PORTS 统一。
    //       此前游戏默认（无 -D）只开 Provider 一侧 ⇒ typed 端口/双 pass 半接线，
    //       1401² 生产图左带河道 24824→10771、全图 165016→146420，左侧水系成片消失。
    //       修复后默认产出即已认可标准图（PNG 20bde1c8…、river=59250）。
    // 119 = 2026-10-29 源头按比例均匀抽稀（sourceKeepFrac 默认 0.7）。用户看图确认：
    //       源头 1119→约783（−30%）、左带河道 −5.9%、最大连通分量 −1.4%（只少支流）。
    //       ⚠ 与 spacing/maxSources 不同——那两个会成片抹掉支流并打断主干，已实测否决。
    // 120 = P1 新世界 morph 基础地形；算法身份单独入键，旧世界仍走 legacy。
    // 121 = 修正旧骨架峰尖渐隐的反向smoothstep；P1及单一骨架候选均未切换生产默认。
    // 122 = 预览启用新水文叠加，磁盘缓存增存 riverType/riverSurfaceY，
    //       旧缓存缺河流水面字段，必须失效重建。
    // 123 = 存档种子原子绑定，禁止未绑定时用默认12345发布生产地形；旧身份缓存失效。
    // 124 = 2026-11-06 方案A候选：已认证浅湖降级门槛与湖缘EPS统一；恢复lake/no-carve。
    //       已授权限定窗口候选对拍，不代表用户视觉验收；不改骨架/湖外河/宽深参数。
    // 125 = B replaces A: authoritative lake-owned mouth water, original river cut fading
    //       over the last 4 blocks of the real mouth tangent into the existing lake floor.
    // 126 = Unaccepted authority-only fallback candidate: remove B's fixed-four fade,
    //       retain pre-B shore carving; receiving-bed morphology remains unresolved.
    // 127 = Water-ownership/connector decoupling diagnostic candidate: R3 retains pre-B
    //       river eligibility/surface/bed; lake authority applies only at publication.
    //       Receiving-bed morphology is NOT completed or visually accepted.
    // 128 = Candidate bounded same-basin inlet morphology, independent of published skeleton.
    //       Retains schema127 water authority; geometric design, not hydraulic/sediment physics.
    // 129 = Basin-wide receiver hard pins + original source section/gradient stencils.
    //       Prevents overlapping morphology from cutting another inlet's natural receiver.
    //       ★ 2026-11-07 用户实机视觉验收通过（同批实测启动进入世界 <3 分钟）⇒ 本条为定稿产出。
    //       仍有界：仅同盆认证湿域内改动，湖外普通河与骨架不变；非水力/泥沙模拟。
    static final int CACHE_SCHEMA_VERSION = 129;
    /** 参数、配置、算法与水文策略共同隔离磁盘缓存。 */
    private long cacheSchemaHash(com.geogenesis.worldgen.terrain.TerrainParams params) {
        long cfg = com.geogenesis.config.GeoGenesisConfig.configFingerprint();
        long strategy = com.geogenesis.worldgen.hydrology.model.ModelHydrologyProvider.outputStrategyFingerprint();
        long algorithm = terrain.usesMorphBase() ? 1L : 0L;
        return (((params.hashCode() * 31L + cfg) * 31L + strategy) * 31L + algorithm) * 31L
                + CACHE_SCHEMA_VERSION;
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

    /**
     * 公开绘制入口（★ 2026-11-09）。
     *
     * <p>配置屏需要<b>精确控制绘制顺序</b>：地形 → 导航栏 → 内容面板 → widget。
     * 若 preview 注册为 widget，{@code super.render()} 会在面板底板之前画它，
     * 而底板不透明 ⇒ 把 widget 全盖住（实机：种子输入框消失）。</p>
     *
     * <p>⇒ 不注册为 widget，由 {@code GeoGenesisConfigScreen.render} 按顺序调用本方法。</p>
     */
    public void drawSelf(GuiGraphics g, int mx, int my, float pt) {
        renderWidget(g, mx, my, pt);
    }

    /**
     * <b>是否由本控件自己画 HUD</b>（★ v7，修"切视图模式 GUI 就重叠"）。
     *
     * <p>两种宿主需要不同的所有权：</p>
     * <ul>
     *   <li>{@code TerrainPreview}（Swing 独立窗口）：{@code true} —— 没有别人帮它画，
     *       标题/图例/缩放/悬停全得自己来。</li>
     *   <li>{@code GeoGenesisConfigScreen}（全屏配置屏）：{@code false} ——
     *       导航栏、内容面板、右下状态栏由屏幕统一负责。若这里再画一遍，
     *       就会和导航栏叠在一起（实机：左上角两套文字压成一团）。</li>
     * </ul>
     */
    private boolean standaloneHud = true;

    /** 配置屏构造 preview 时调 {@code false}，独立窗口保持 {@code true}。 */
    public void setStandaloneHud(boolean v) { this.standaloneHud = v; }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        long frameStart = System.nanoTime();
        int x = getX(), y = getY(), w = width, h = height;
        g.fill(x, y, x + w, y + h, 0xFF1a1a2a);

        // ★ S3：沙盘模式分流 —— 数据与平面模式完全共用，只换绘制方式
        //
        // ★★ 2026-11-08【修"该有的功能都没有"】：
        //   这里原来是 `renderSandbox(...); return;` —— 直接跳过了后面的
        //   全部 HUD（缩放标签/操作提示/图例/悬停信息/帧时间/边框）。
        //   实机截图只有右下那条状态栏（那是 ConfigScreen 另画的），
        //   左上标题、操作说明、进度条、悬停浮窗全都不见了。
        //   ⇒ 沙盘画完地形后<b>继续走完 HUD 流程</b>，只是跳过 2D 纹理绘制。
        if (sandboxMode) {
            renderSandbox(g, w, h, mx, my);
            // ★ v7【修"切视图 GUI 重叠"】：HUD 只在独立窗口里画。
            //   配置屏内由 GeoGenesisConfigScreen 统一画（导航栏/面板/右下状态栏），
            //   这里再画一遍就会叠出两套标题与操作提示（实机取证：左上角压成一团）。
            if (standaloneHud) {
                drawZoomLabel(g, x, y, w, h);
                if (showLegend) drawLegend(g, mx, my);
                if (showFrameTime) drawFrameTime(g, x + w, y + 64);
                drawCopiedToast(g, x, y, w, h);
                // ★ 沙盘模式<b>不调 drawHoverInfo</b>：
                //   它是平面模式的跟随光标 tooltip；沙盘已有左下角常驻悬停信息
                //   （drawSandboxHints），两者同时存在会叠成两行（实机取证）。
                // 1px 边框（参考项目 draw 的收尾）
                g.fill(x - 1, y - 1, x + w + 1, y, 0xFF666666);
                g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF666666);
                g.fill(x - 1, y, x, y + h, 0xFF666666);
                g.fill(x + w, y, x + w + 1, y + h, 0xFF666666);
            }
            long elapsedSandbox = (System.nanoTime() - frameStart) / 1_000_000L;
            frameTimes.add(elapsedSandbox);
            if (frameTimes.size() > 30) frameTimes.poll();
            return;
        }

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

        // ★ 大范围模式不走 chunk 队列（按 chunk 遍历 + stride≤16 → 视口 30 万格约 3.7 亿次迭代）；
        //   其采样在 paintLargeArea 内按视口标识增量执行（固定 64×64，约 40ms）。
        if (!largeArea) {
            queue.queueGeneration(centerX + (int) totalDragX,
                    centerZ + (int) totalDragZ, blocksWide, blocksHigh);
        }

        // ★ 脏检查：只有视口移动 / 新数据到位 / 图层或选中变化 / 显式 needsClear 才重画+重传。
        //   静止帧（无拖拽、无新 chunk）直接 blit GPU 旧纹理 → 0 CPU 重画，消除"打架/撕裂"。
        boolean viewportMoved = originWx != lastOriginWx || originWz != lastOriginWz;
        boolean dataChanged = cellCache.version() != lastCacheVersion;
        boolean layerChanged = activeLayer.ordinal() != lastLayerOrdinal
                || (selectedLayer == null ? -1 : selectedLayer.ordinal()) != lastSelectedLayerOrdinal
                || selectedId != lastSelectedId;
        boolean dirty = needsClear || viewportMoved || dataChanged || layerChanged;

        // ★ S2：采样在后台线程池里跑（yardsite 同架构），渲染线程不做采样。
        //   这里只做一件事：采集完了就置脏重画（之前画的是半成品）。
        if (largeArea && largeSampler != null && largeSampler.isComplete()
                && largeSamplerIdDrawn != largeSampler.data().viewportId) {
            largeSamplerIdDrawn = largeSampler.data().viewportId;
            dirty = true;
        }

        if (dirty) {
            // ★ 无条件 fillRect：脏帧总是从干净状态重画。
            //   原"只在 needsClear 时清"逻辑有 bug——缩放/拖拽后第一帧 viewportMoved=true 触发 dirty，
            //   但 needsClear 已在 mouseScrolled/mouseReleased 处被清成 false，导致第二帧跳过 fillRect，
            //   image 残留旧视口像素（用户截图左侧 50px 竖条就是缩放前视口左边缘数据）。
            //   dirty 本身已被视口移动/新数据/图层变化严格门控，多一次 fillRect（O(texW×texH)≈1ms）安全。
            image.fillRect(0, 0, texW, texH, 0);
            needsClear = false;
            if (largeArea) {
                paintLargeArea(originWx, originWz, blocksWide, blocksHigh);
            } else {
                paintAvailableChunks(originWx, originWz, blocksWide, blocksHigh);
            }

            // 真实性地形阴影（逐像素高度梯度法线 · 光源点乘）：由 TerrainUnderlay 全局控制，
            // 对图层无关（气候/地形数据均可披真实地形明暗）。
            // 拖拽中按 dragSimplify 开关跳过（最贵的一步：全图 3 遍 + 每像素缓存查找），
            // 松手后 needsClear 补画。false = 拖动也画完整阴影（接受掉帧）。
            // ★ 大范围模式跳过：坡度阴影从 cellCache 取高度，而大范围模式不走 chunk 缓存（会是空数据）
            if (!largeArea && (!dragging || !dragSimplify)
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
        // ★ v25：视图切换过渡 —— 平面画面淡入（照参考项目 yardsite 第 430 行
        //   {@code alpha = colAlpha * globalAlpha} 的做法）。
        //   平面是纹理直贴（blit），用 {@code RenderSystem.setShaderColor} 的
        //   alpha 做淡入（1.20.1 的 GuiGraphics 没有 setColor/getColor）。
        float planeAlpha = inTransition() ? (0.45f + 0.55f * transitionEase()) : 1f;
        if (planeAlpha < 1f) {
            RenderSystem.setShaderColor(1f, 1f, 1f, planeAlpha);
        }
        g.blit(texLoc, x, y, w, h, 0, 0, texW, texH, texW, texH);
        if (planeAlpha < 1f) {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        drawMapMarkers(g, mx, my, x, y, w, h, originWx, originWz, blocksWide, blocksHigh);

        // ★ v7【修"切视图 GUI 重叠"】：以下全部是 HUD —— 只在独立窗口里画。
        //   配置屏内由 GeoGenesisConfigScreen 统一负责（导航栏/图例/右下状态栏），
        //   这里再画就会叠成两套（实机：左上两套文字、右上两个图例）。
        //   ★ 地图标记（上面那行）不算 HUD ⇒ 两种模式都保留。
        if (standaloneHud) {
            // 参考项目：1px 边框线
            g.fill(x - 1, y - 1, x + w + 1, y, 0xFF666666);
            g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF666666);
            g.fill(x - 1, y, x, y + h, 0xFF666666);
            g.fill(x + w, y, x + w + 1, y + h, 0xFF666666);

            drawZoomLabel(g, x, y, w, h);

            // ★ 角落所有权契约：右上 = 图例 ⇒ 帧时间画到图例下方（不再与它重叠）
            if (showLegend) drawLegend(g, mx, my);
            if (showFrameTime) drawFrameTime(g, x + w, y + 64);

            // 右键复制坐标消息
            drawCopiedToast(g, x, y, w, h);

            drawHoverInfo(g, mx, my);
        }

        // 帧时间统计
        long elapsed = (System.nanoTime() - frameStart) / 1_000_000L; // ms
        frameTimes.add(elapsed);
        if (frameTimes.size() > 30) frameTimes.poll();
    }

    /**
     * 地图标记：出生点 / 玩家位置 / 结构 / 结构图标叠加。
     *
     * <p>★ 2026-11-08：从平面模式抽出来，<b>沙盘也用</b>。
     * 这些是平面视图原有的能力（用户说"基于我们原有的优势设计"），
     * 而沙盘此前走独立渲染路径、把它们全丢了。</p>
     */
    private void drawMapMarkers(GuiGraphics g, double mx, double my,
                                int x, int y, int w, int h,
                                int originWx, int originWz, int blocksWide, int blocksHigh) {
        drawSpawnMarker(g);
        if (showPlayerMarkers) drawPlayerMarker(g);
        int imx0 = (int) mx, imy0 = (int) my;
        if (showStructures && structureScanner != null) drawStructureMarkers(g, imx0, imy0);
        String markerHover = structureOverlay.render(g, x, y, w, h,
                originWx, originWz, blocksWide, blocksHigh, imx0, imy0);
        if (markerHover != null) {
            int tw = mc.font.width(markerHover);
            g.fill(imx0 + 10, imy0 + 8, imx0 + 14 + tw, imy0 + 20, 0xCC000000);
            g.drawString(mc.font, markerHover, imx0 + 13, imy0 + 10, 0xFFFFFF);
        }
    }

    /**
     * 大范围绘制（★ 2026-09-11）：用 {@link LargeAreaSampler} 的固定采样网格上采样填充纹理。
     *
     * <p>采样数恒为 {@link LargeAreaSampler#DEFAULT_GRID}²，<b>与视野无关</b>
     * （FreeTerraForged {@code generateZoomed} 同款：固定采样缓冲 + 缩放只改步长）；
     * 仅当视口（原点/尺寸）变化才重采。</p>
     *
     * <p><b>取舍</b>：本路径经 {@code sampleCellCoarse} —— 跳过侵蚀 tile 与河谷雕刻，
     * 高度与实际落块相差一个侵蚀量级。用于看<b>气候格局 / 纬度分带 / 地形骨架</b>；
     * 需要"预览 = 游戏"请缩小到 ≤1:16 回到精确管线。</p>
     */

    /** 开启大范围时自动跳到该缩放档（1:64）——保证"点开即见效果"。 */
    static final int LARGE_AREA_ENTRY_SCALE = 64;

    /**
     * 切换大范围模式（>1:16 走 {@link LargeAreaSampler}；关闭时回落精确管线并复位缩放）。
     *
     * <p>★ 2026-09-11 修复：开启时<b>自动缩到 {@value #LARGE_AREA_ENTRY_SCALE} 档</b>。
     * 原实现只改采样管线、<b>不动缩放</b> → 点开开关后画面<b>毫无变化</b>
     * （仍在 1:1 精确档），用户会认为"大范围预览打不开"。现在点开即见宽视野格局。</p>
     */
    public void setLargeArea(boolean v) {
        if (this.largeArea == v) return;
        this.largeArea = v;
        // ★ 关掉大范围时先停采样线程池再丢弃引用，否则 10 个采样线程会一直空转
        if (largeSampler != null) largeSampler.stop();
        largeSampler = null;
        if (v) {
            if (scaleBlockPos < LARGE_AREA_ENTRY_SCALE) scaleBlockPos = LARGE_AREA_ENTRY_SCALE;
        } else if (scaleBlockPos > 16) {
            scaleBlockPos = 16;
        }
        needsClear = true;
        rebuildQueue();
    }

    /** 是否处于大范围模式。 */
    public boolean isLargeArea() { return largeArea; }

    // ------------------------------------------------------------------
    // ★ S3：沙盘（3D 方柱）模式
    // ------------------------------------------------------------------

    /** 是否处于沙盘模式。 */
    public boolean isSandboxMode() { return sandboxMode; }

    /**
     * 切换沙盘模式。
     *
     * <p>沙盘需要大范围采样数据（柱子太密反而糊），所以开启时自动打开大范围模式；
     * 关闭时不动大范围开关 —— 用户可能本来就在看大范围。</p>
     */
    /**
     * 切换视图模式（★ v24：<b>只换画法，不动任何视图参数</b>）。
     *
     * <h2>★ 为什么删掉 {@code setLargeArea(true)}</h2>
     * <p>此前进沙盘会强制 {@code setLargeArea(true)}，而那条链是：</p>
     * <pre>
     *   setLargeArea(true) → scaleBlockPos = max(scaleBlockPos, LARGE_AREA_ENTRY_SCALE)
     * </pre>
     * <p>⇒ <b>切一次视图，缩放就被改掉</b>（用户：「谁切换视图还改缩放等信息呢？」）。</p>
     *
     * <p>而且沙盘的数据来自 {@code cellCache}（{@link TerrainFieldData#fromCellCache}），
     * 与 {@code largeArea} / {@code largeSampler} <b>毫无关系</b> ——
     * 那个强制既无必要，又破坏了"切模式构图不变"的承诺。</p>
     */
    public void setSandboxMode(boolean v) {
        if (this.sandboxMode == v) return;
        this.sandboxMode = v;
        // ★ v25：切模式走一段过渡动画（照参考项目 yardsite 第 61/383-404 行）
        //   旧画面淡出下沉、新画面淡入长起、镜头轻推近 ⇒ 不再是"啪"一下硬切。
        beginViewTransition();
        // 只标记需要重画，不碰 scaleBlockPos / largeArea / center
        needsClear = true;
    }

    /** 沙盘渲染：直接提交 3D 方柱，不走 2D 纹理。 */
    private void renderSandbox(GuiGraphics g, int w, int h, double mx, double my) {
        // ★★ 像素驱动 LOD（2026-11-08 第二次重做，用户裁定）：
        //
        //   上一版网格固定 256²、窗口随缩放变 ⇒ 实机"放大后精度没变化"。
        //   对比我们的<b>平面视图</b>：它的采样点数 = 视口像素数（900²=81 万），
        //   而沙盘固定 6.5 万 ⇒ 差 12.4 倍。
        //
        //   正解：柱子屏幕尺寸恒定（13px）⇒ 网格数 = 视口像素/13 ≈ 70 柱 = 140 细格。
        //   推近只改"每柱代表多少方块" ⇒ 视野缩小、精度提高（用户要的就是这个）。
        //   成本反而更低：140² = 19,600 点 < 256² = 65,536 点。
        float zoom = sandboxRasterizer.zoom();
        // ★ 全屏地图：用<b>完整视口</b>（不是 min(w,h) 的正方形）。
        //   用正方形会让盘面在宽屏上只占中间一块、上下大片留白 ——
        //   用户要求「实际没有上下栏，类似像一个游戏地图那样全屏显示画面内容」。
        int side = Math.max(w, h);
        // ★★ v27【数据源统一：沙盘窗口 = 平面窗口】★★
        //
        //   用户原话：
        //     「我的想法是数据源统一，这样能保证计算复用。
        //       除非我们需要全局预览才不进行高精度计算复用」
        //     「视野怎么还是4096以上的视野？这么大完全没必要」
        //
        //   此前沙盘窗口 = SandboxLod.windowFor(zoom)，默认 4096 格；
        //   而平面窗口 = texW × scaleBlockPos ≈ 900 格。
        //   沙盘窗口是数据覆盖范围的 <b>4.5 倍</b> ⇒ 95% 区域没有数据
        //   （日志实测 缺失=249020/262144）⇒ 只能看到中心一小块。
        //
        //   ⇒ 正解不是给沙盘再写一套采样器（那是重复造轮子），
        //     而是<b>让沙盘直接看平面正在看的那块</b>：
        //     窗口 = 平面窗口，细格 = 窗口/步长。
        //     同一份 cellCache、同一个视口 ⇒ 真正的"计算复用"。
        //
        //   ★ 柱数仍按 zoom 档位走（决定"画多粗"），
        //     但<b>每柱代表多少方块</b>随窗口自动换算 ⇒ 分辨率不丢。
        int blocksWide = Math.max(64, texW * scaleBlockPos);
        int blocksHigh = Math.max(64, texH * scaleBlockPos);
        // ★ 柱数：窗口决定"画多少根"，zoom 决定"多细"。
        //   窗口 / 每柱方块 → 基准柱数；zoom 推近时柱数不变、柱子变粗
        //   （因为窗口变小）；这里再按 zoom 让分组粒度也跟着走，
        //   保证推近能看到更细的细节而不是放大同一批柱子。
        int basePillars = SandboxLod.pillarsForWindow(blocksWide);
        int grid = Math.max(2, basePillars * SandboxLod.HEIGHT_STRIDE);
        // ★ 记录进入沙盘时的初始中心（R 键回到这里）
        if (homeCenterX == null) {
            homeCenterX = centerX;
            homeCenterZ = centerZ;
        }

        // ★★ 2026-11-08【用户指出的正解：直接用平面视图的数据】
        //
        //   用户原话：「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。
        //   明明又有高度数据并且精度都到 MC 方块级了。」
        //   「这个预览功能其实就可以当成我们在外部核心层（地形建模软件）的视图。
        //    这样在这里跑的计算后续可以直接应用到进入世界加载中。」
        //
        //   平面视图的 {@link CellCache} 已按 chunk×16×16 存了完整 Cell
        //   （e / 高度 / 气候 / 群系 / 地形 / 水文），精度就是 MC 方块级。
        //   沙盘此前用 ProgressiveSampler <b>重新采样</b> —— 慢，且与平面视图不一致。
        //
        //   ⇒ 直接读缓存：1 细格 = 1 方块（真 MC 精度），零二次采样。
        //   ⚠ 前提：平面视图已把视口附近的 chunk 采进缓存（它一直在采）。
        //
        // ★ 柱子尺度（照参考项目三层结构）：
        //   柱数 columns=128、细格 fine=columns×HEIGHT_STRIDE=256、每柱 16 格方块。
        //   ⇒ perColumn = 2（每柱聚合 2×2 个细格）
        // ★ v25：沙盘的视口原点只认 centerX/centerZ。
        //   此前这里还叠加了 totalDragX/totalDragZ（那是<b>平面</b>的累积拖拽偏移），
        //   而沙盘平移是直接改 centerX/centerZ 的 ⇒ 两者会互相干扰
        //   （平面拖过之后切沙盘，视口会莫名其妙偏一段）。
        int originWx = centerX - blocksWide / 2;
        int originWz = centerZ - blocksHigh / 2;

        long id = (long) originWx * 31L + originWz;
        id = id * 131L + blocksWide;
        id = id * 131L + grid;
        id = id * 131L + cellCache.version();   // 缓存更新 ⇒ 重读

        // ★★ 必须缓存：每帧重建 = 每帧分配 grid² × 十几个数组
        //   （曾按窗口方块数 2048 建 ⇒ 419 万格 ⇒ OOM 崩溃）。
        //   只有 id 变了（视口/窗口/精度/缓存版本）才重建。
        if (sandboxData == null || sandboxDataId != id) {
            sandboxData = TerrainFieldData.fromCellCache(
                    cellCache, originWx, originWz, blocksWide, blocksHigh, grid, grid, id);
            sandboxDataId = id;
        }
        TerrainFieldData d = sandboxData;

        // 诊断：视口变化就记一条
        if (sandboxDiagId != id) {
            sandboxDiagId = id;
            LOGGER.info("[Preview] 沙盘 {} zoom={} 细格={} 窗口={} 数据={}ms 缺失={}",
                    SandboxLod.describe(zoom),
                    String.format(java.util.Locale.ROOT, "%.2f", zoom),
                    grid, blocksWide, d == null ? -1 : d.costMs, d == null ? -1 : d.missingCells);

        }
        // ★ 竖向比例：<b>照抄参考项目</b>（SiteTerrainRenderer.collect() 第 661/677-678 行）：
        //
        //     int highest = base;
        //     for (每根柱子) if (columnMax > highest) highest = columnMax;
        //     field.easeReliefSpan(max(60, highest - base));
        //     float span = max(20, field.reliefSpan());     // 缓动后的值
        //
        //   ⇒ span = <b>本图最高点 - 基准面</b>，经缓动（涨 8%/降 2%）。
        //
        //   ★ 我曾经两次改错这里（记录在交接 §12.3）：
        //     ① 下限硬编码 60 → 细档柱子全挤在同一高度；
        //     ② 改用"世界绝对量程 320"（怕 LOD 切换跳变）→ <b>柱子全被压扁</b>
        //        ：320 格量程下，本图高差只有 60~150 格，柱子只用到 20%~45% 的高度，
        //        立体感远不如参考项目。参考项目敢用"本图最高点"是因为它有缓动。
        //   ⇒ 恢复参考做法，靠 easeReliefSpan 的缓动吸收 LOD 切换时的跳变。
        double seaY = this.seaLevel;
        if (d != null) {
            double baseY = seaY - 4.0;
            double hi = baseY;
            for (int i = 0; i < d.height.length; i++) {
                // 水域按海平面参与（参考项目的水格 waterSurface = seaLevel）
                double columnMax = d.e[i] < 0.0 ? seaY : d.height[i];
                if (columnMax > hi) hi = columnMax;
            }
            reliefSpan = easeReliefSpan((float) Math.max(60.0, hi - baseY), reliefSpan);
        }
        // ★ P0-4：hover 拾取（视线推进），沙盘模式每帧一次
        //   拾取与标记仍用正方形边长 side（它们是屏幕坐标计算，与盘面尺度无关）
        int ax = getX() + (w - side) / 2;
        int ay = getY() + (h - side) / 2;
        // ★★ 2026-11-09 B3/B4：沙盘绘制走<b>统一 rasterizer</b>。
        //
        //   旧路径 SandboxRenderer（1132 行，自带一套 collect/sort/pick/投影）已删除 ——
        //   它与新的 SandboxGeometry 构成两套沙盘实现，正是本轮要消灭的"独立分裂"。
        //   新路径：SandboxRasterizer 只负责"画"，几何全在 SandboxGeometry（纯函数、探针可验证）。
        //   本类继续负责：读 CellCache、算竖向比例 span、hover、地图标记。
        //
        // ★ v24：<b>不再经过 TerrainSnapshot / PreviewController</b>。
        //   用户批评："明明后端数据都不需要修改，平面、沙盘都这样，
        //             就前端显示不一样而已。你这样完全是扔了原来的设计又重复造轮子。"
        //   ⇒ 沙盘直接吃 CellCache 产出的 TerrainFieldData（唯一数据源，零复制）。
        com.geogenesis.client.preview.raster.RasterParams rp =
                new com.geogenesis.client.preview.raster.RasterParams(
                        w, h, 0f, activeLayer, (int) seaY);
        sandboxRasterizer.setColorSource(sandboxColorSource());
        if (d != null && isMouseOver(mx, my)) {
            sandboxRasterizer.pick(mx, my, getX(), getY(), w, h);
        } else {
            sandboxRasterizer.clearPick();
        }
        if (d != null) {
            // ★ v25：视图切换过渡（照参考项目 yardsite 第 383-404 行）
            //   新画面：alpha 从 0.45 渐显到 1，高度比例从 0.45 长到 1
            //   （参考项目是"旧图淡出下沉 + 新图淡入长起"，我们只有一张图，
            //     所以让新图自己完成"淡入 + 长起"，观感等价）
            float ease = transitionEase();
            float globalAlpha = inTransition() ? (0.45f + 0.55f * ease) : 1f;
            float heightScale = inTransition() ? (0.45f + 0.55f * ease) : 1f;
            sandboxRasterizer.draw(g, d, rp, getX(), getY(), w, h,
                    reliefSpan, System.currentTimeMillis(), globalAlpha, heightScale);
        }

        // ★ 地图标记（出生点 / 玩家 / 结构）：平面视图原有能力，沙盘也要有。
        //   坐标用沙盘当前视口（originWx/originWz/blocksWide）——
        //   标记绘制按「世界坐标 → 屏幕」换算，与沙盘窗口一致。
        drawMapMarkers(g, mx, my, ax, ay, side, side, originWx, originWz, blocksWide, blocksHigh);
    }

    /** 沙盘诊断：上次已打印的视口 id。 */
    long sandboxDiagId = Long.MIN_VALUE;

    /**
     * 沙盘数据缓存（★ 2026-11-08：<b>绝不能每帧重建</b>）。
     *
     * <p>曾每帧调 {@code fromCellCache}，且细格数误用窗口方块数（2048）
     * ⇒ 2048² = 419 万格 × 十几个数组/帧 ⇒ {@code OutOfMemoryError}
     * （crash-2026-11-09_03.26.03 已取证）。</p>
     */
    TerrainFieldData sandboxData;
    long sandboxDataId = Long.MIN_VALUE;

    /**
     * 沙盘竖向比例（本图最高点 − 基准面），<b>缓动</b>（照抄 yardsite
     * {@code SiteField.easeReliefSpan}：涨 8%/帧、降 2%/帧，差 &lt; 0.6 时对齐）。
     * 缓动吸收 LOD 切换 / 拖动时的跳变，避免柱子高度抖动。
     */
    float reliefSpan = 60.0f;

    private static float easeReliefSpan(float target, float current) {
        float diff = target - current;
        if (Math.abs(diff) < 0.6f) return target;
        return current + diff * (diff > 0 ? 0.08f : 0.02f);
    }

    /** 坡度阴影后处理。
     *  1. 从 cellCache 提取高度数据
     *  2. 3×3 盒型模糊消除块边界跳变（参考项目无坡度阴影，用 colormap 平滑。
     *     我们用模糊再算 slope 来避免黑线。）
     */

    /** 从 CellCache 读取可见区内已缓存的 chunk 并逐块写入纹理。
     *  逐块采样（cells[lx*16+lz]，X 主序与 generateChunk 一致），每个块映射到其纹理像素。
     *  ★ 2026-08-08：1) 修复索引转置（Z 主序→X 主序，16 块间距网格根因，勿回退）；
     *    2) step=scaleBlockPos 稀疏采样（每纹理像素 1 采样点：16/step × 16/step 点恰好覆盖
     *       chunk 在纹理中的 scaleBlockPos² 像素区，无漏画无网格）。此前临时 step=1 全采样
     *       使拖拽每帧绘制量暴涨 16 倍 → 移动卡顿（用户反馈），已恢复稀疏。 */

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
            // ★ v25【统一交互】：右键在两种模式都是「平移」，由 mouseDragged 处理。
            //   沙盘原先在此置 sandboxPanning=true，那个状态已废弃。
            if (sandboxMode) {
                return true;
            }
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
        // ★★ v8【修"平面地图拖不动"】★★
        //   旧代码只在<b>沙盘</b>分支里置 dragging=true，平面模式下左键
        //   <b>从不进入拖拽态</b> ⇒ mouseDragged 里的位移累加全是空转，
        //   用户表现为"悬浮提示看得见地图，但拖不动"。
        //   两种模式都需要进入拖拽态，区别只在 dragged 里怎么解释位移。
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        // ★ v25【统一交互】后这里两种模式的行为一致了：
        //   左键松开 = 平移结束（提交偏移）或点击选中；中键 = 旋转结束。
        //
        //   沙盘的平移是<b>逐帧移动世界中心</b>的（panSandboxBy 里直接改 centerX/centerZ），
        //   松开时不需要再提交偏移，只需清状态 + 停采样批。
        //   平面是<b>累积偏移、松开才提交</b>（拖动时不重采样，避免每帧重建视口）。
        // ★ v26【修"沙盘拖动卡顿"】：拖动期间只做屏幕平移（零成本），
        //   <b>松手才</b>提交成世界中心位移并重采样一次。
        //   此前是逐帧改 centerX ⇒ 每帧全量重建 TerrainFieldData（340~650ms）。
        if (sandboxMode) {
            dragging = false;
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                // 有实际位移 → 提交平移；没有 → 当作点击选中
                if (Math.abs(sandboxCenterAccX) >= 1.0 || Math.abs(sandboxCenterAccZ) >= 1.0) {
                    commitSandboxPan();
                } else {
                    sandboxCenterAccX = 0;
                    sandboxCenterAccZ = 0;
                    sandboxRasterizer.clearPan();
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && hoverX != -1 && hoverZ != -1) {
                        // 左键点击（没怎么动）→ 与平面一样：离散图层选中色块
                        handleMapClick(mx, my);
                    }
                }
            }
            return true;
        }
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

    /** 沙盘拖动用的临时数组（避免每次分配）。 */
    final float[] sandboxDragOut = new float[2];

    /**
     * 沙盘右键拖动：<b>移动地图</b>（移动世界中心 + 触发重新采样）。
     *
     * <p>不是屏幕像素平移 —— 全屏单层地图没有"切层看别处"的退路，
     * 只挪像素会露出采样范围外的空白。</p>
     */
    /**
     * 沙盘拖动 → 屏幕平移（<b>拖动期间零重采样</b>）。
     *
     * <h2>★ v26【修"左键平移在沙盘里拖起来还是有点卡顿，不跟手"】</h2>
     * <p>此前每次位移 ≥1 方块就改 {@code centerX/centerZ} ⇒ 下一帧
     * {@code fromCellCache} <b>全量重建</b>（实测 340~650ms/次）
     * ⇒ 拖动过程每秒只能响应 1~2 次 ⇒ 严重卡顿。</p>
     * <p>用户原话：「不应该让后台计算影响前端视觉体验啊。」</p>
     *
     * <p>⇒ 拖动期间<b>只累加屏幕平移</b>（{@code panX/panY}，纯渲染层零成本），
     * <b>松手时才</b>把累积位移换算成世界中心位移并触发一次重采样。
     * 这样拖动完全跟手，计算只在松手后发生一次。</p>
     */
    private void panSandboxBy(double dx, double dy) {
        // 只累加屏幕平移（不含任何采样/重建）
        sandboxRasterizer.dragPan(dx, dy);
        // 同时记下"松手时该移动多少世界方块"（用于 committed 时重采样）
        int areaSize = Math.min(getWidth(), getHeight());
        double step = SandboxLod.effectiveStep(sandboxRasterizer.zoom());
        sandboxRasterizer.dragToBlocks(dx, dy, areaSize, areaSize, step, sandboxDragOut);
        sandboxCenterAccX += sandboxDragOut[0];
        sandboxCenterAccZ += sandboxDragOut[1];
    }

    /**
     * 沙盘拖动结束后提交：把累积的屏幕平移换成世界中心位移，重采样一次。
     *
     * <p>见 {@link #panSandboxBy} 的注释 —— 拖动期间不重采样，
     * 只在这里付一次计算的代价。</p>
     */
    private void commitSandboxPan() {
        int ix = (int) sandboxCenterAccX;
        int iz = (int) sandboxCenterAccZ;
        if (ix != 0 || iz != 0) {
            sandboxCenterAccX -= ix;
            sandboxCenterAccZ -= iz;
            centerX += ix;
            centerZ += iz;
            needsClear = true;
        }
        // ★★ v27【修"一缩放就超出底板"】★★
        //   这里原来是 `if (ix == 0 && iz == 0) return;` —— 位移不足 1 方块时
        //   <b>直接 return，clearPan() 没执行</b>
        //   ⇒ panX/panY 残留，下次拖动继续往上加
        //   ⇒ 底板与地形一起被推离视口中心（用户："视野一缩放就超出底板了"）。
        //   pan 必须<b>无条件</b>清掉：它只是"拖动期间的临时预览偏移"，
        //   一旦松手（无论提交多少世界位移）就不该留着。
        sandboxRasterizer.clearPan();
    }

    /** 拖动累积的世界位移余量（&lt;1 方块的部分）。 */
    double sandboxCenterAccX, sandboxCenterAccZ;

    /**
     * 进入沙盘时的初始世界中心（R 键回到这里）。
     *
     * <p>null = 尚未记录（首次进入沙盘时才记，见 {@link #renderSandbox}）。</p>
     */
    Integer homeCenterX, homeCenterZ;

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // ★★ v25【统一平面与沙盘的交互】★★
        //
        //   用户原话：「建议把平面交互与沙盘交互给统一了，
        //   现在在平面视图里左键平移，在沙盘里左键又变成旋转了。」
        //
        //   统一后的约定（两种模式<b>完全一致</b>）：
        //     左键拖动 = <b>平移地图</b>（移动世界中心）
        //     中键拖动 = <b>旋转视角</b>（仅沙盘有视角；平面无意义则忽略）
        //     右键     = 复制坐标（平面）/ 同样平移（沙盘，保留旧手感）
        //
        //   之前沙盘左键=旋转、平面左键=平移 ⇒ 换视图后手感突变。
        //   参考项目 yardsite 是「左键旋转 + 右键平移」，但我们既然要统一，
        //   就以<b>平面（左键平移）</b>为准 —— 它是主视图，也是用户的既有习惯。
        //   旋转改到中键（参考项目用中键做 roll，位置刚好空出来）。
        //
        //   ★ 平移必须「移动世界中心并重新采样」而不是平移屏幕像素 ——
        //   我们是全屏单层地图，只平移像素会让地形边缘露出空白。

        // ---- 中键 = 旋转视角（仅沙盘）----
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && sandboxMode) {
            sandboxRasterizer.dragRotate(dx, dy);
            return true;
        }

        // ---- 左键 / 右键 = 平移（两种模式一致）----
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (sandboxMode) {
                panSandboxBy(dx, dy);
                return true;
            }
            double guiScale = mc.getWindow().getGuiScale();
            totalDragX -= dx * guiScale * scaleBlockPos;
            totalDragZ -= dy * guiScale * scaleBlockPos;
            dragging = true;
            needsClear = true;  // 拖拽时每帧清纹理，避免旧位置数据残留（拖影）
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;

        // ★ S3：沙盘模式 → 滚轮缩放；按住 Ctrl 滚轮改柱子粗细（widthScale）
        if (sandboxMode) {
            boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
            if (ctrl) {
                sandboxRasterizer.setWidthScale(sandboxRasterizer.widthScale() * (delta > 0 ? 1.06f : 1f / 1.06f));
            } else {
                // ★ Q1-C 加减速：远快近慢（见 SandboxLod.nextZoom 的 javadoc）
                sandboxRasterizer.setZoom(SandboxLod.nextZoom(sandboxRasterizer.zoom(), delta > 0));
                // ★ v27：缩放会改 scale ⇒ 残留的屏幕平移会错位（"一缩放就超出底板"），清掉
                sandboxRasterizer.clearPan();
                sandboxCenterAccX = 0;
                sandboxCenterAccZ = 0;
            }
            return true;
        }

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
            if (current > 16) next = Math.max(16, current / 2);   // ★ 大范围档退回
            else if (current > 8) next = current / 2;
            else if (current > 4) next = 4;
            else if (current > 2) next = 2;
            else if (current > 1) next = 1;
        } else {
            // 向下滚→更粗（scale 增大）
            if (current < 2) next = 2;
            else if (current < 4) next = 4;
            else if (current < 8) next = 8;
            else if (current < 16) next = 16;
            // ★ 大范围：16 → 32 → … → 1024（走 LargeAreaSampler，开销与视野无关）
            else if (largeArea && current < 1024) next = current * 2;
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
        setTerrain(terrain, seed, params, true);
    }

    /**
     * 换地形引擎。
     *
     * @param ownsTerrain <b>本预览是否拥有这个实例</b>（★ 2026-11-08）。
     *        <p>配置屏可能<b>复用游戏那份存档单例</b>（{@code GeoGenesisGenerator.previewReusableTerrain}）
     *        来共享已算的 chunk 与水文 region。这种共享实例<b>绝不能</b>被预览关闭 ——
     *        否则玩家关掉配置屏就会把游戏正在用的地形引擎关停
     *        （它会复用一套已 shutdown 的进程池）。</p>
     *        <p>⇒ 只有自建实例才 {@code ownsTerrain=true}，才会在换/关时被退役。</p>
     */
    public void setTerrain(GeoGenesisTerrain terrain, long seed, TerrainParams params,
                           boolean ownsTerrain) {
        GeoGenesisTerrain retired = this.terrain;
        boolean retiredOwned = this.ownsTerrain;
        this.terrain = terrain;
        this.ownsTerrain = ownsTerrain;
        // 只退役"自己拥有的"旧实例；共享实例留给游戏侧管理生命周期
        if (retiredOwned && retired != null && retired != terrain) retired.closeProcessErosion();
        this.seed = seed;
        this.seaLevel = params.seaLevel();
        this.maxY = params.maxY();
        this.minY = params.minY();
        this.mountainCap = params.mountainCap();
        this.maxY = params.maxY();
        this.peakFraction = params.peakHeightFraction();
        this.verticalScale = params.verticalScale();
        this.horizontalScale = params.horizontalScale();
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

    /** e→世界高度 Y（与 HeightCurve.heightFromE 一致的非对称映射：e=0→海平面）。 */
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

    /** 绘制帧时间（右上角） */

    /** 绘制玩家位置标记：白圆点 + 深色描边 + 姓名标签（与出生点金色 X 区分）。 */

    /** 绘制自动检测的结构标记：彩色方块 + 白色中心点，悬停显示全名。 */

    /** 结构类型 → 标记色（按结构名粗分类，简单可辨） */


    /** 绘制右键复制坐标消息（8 秒渐隐） */

    // ================================================================
    //  图例
    // ================================================================

    /** 本地化：优先 I18n（MC 语言文件），缺失则回退到英文表（与 Swing 端一致）。 */

    /**
     * 图例（★ v7 改为公开）：配置屏在 {@code standaloneHud=false} 时
     * 关掉了预览自带的 HUD，但图例仍需显示 —— 由屏幕在合适位置调用本方法。
     * 它自己按 {@code getX()/getY()/width/height} 定位，不依赖调用方传坐标。
     */
    public void drawLegendAt(GuiGraphics g, int mx, int my) {
        drawLegend(g, mx, my);
    }

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
            g.drawString(mc.font, modeLabel, lx, modeY + 2, hoverMode ? 0xFF48DE56 : 0xFF9E9E9E);

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
                UiKit.scrollbar(g, lx + panelW - 3, entryY, entryMaxH, contentH, legendScrollOffset, false);
            }
        } else {
            // ★ v7 重做连续图层图例（用户：「右侧的高程图例怎么还是旧版的」）。
            //   旧版：12px 裸竖条 + 左侧浮在色块上的文字 + 青色标题 —— 与新版控件语言完全脱节。
            //   新版：右对齐的圆角卡片（深色底 + 圆角），内含标题行 + 色带 + 端点标签 + 中值刻度。
            drawContinuousLegendCard(g, layer, lx, ly);
        }
    }

    /**
     * <b>连续图层图例卡片</b>（★ v7）。
     *
     * <pre>
     *   ┌──────────────┐
     *   │ 高程          │  标题行（亮字）
     *   │ ┌──┐  Y=288  │  色带 + 上端值
     *   │ │▓▓│         │
     *   │ │▓▓│         │
     *   │ └──┘  Y=19   │  下端值
     *   └──────────────┘
     * </pre>
     */
    /**
     * 图例卡片的几何（★ v10：供屏幕把操作提示卡排在它<b>下方</b>）。
     *
     * @return {@code {x, y, w, h}}；当前图层不可图例化时返回 {@code null}
     */
    public int[] legendCardRect() {
        GeoPalette.PreviewLayer layer = getLayer();
        if (!layer.legendable) return null;
        if (layer.kind == GeoPalette.Kind.DISCRETE) {
            // 离散图例尺寸由条目数决定，这里给折叠态的最小占位
            return new int[]{getX() + width - 132, getY() + 8, 128, legendExpanded ? 220 : 14};
        }
        int barW = 20;
        // ★ 与 drawContinuousLegendCard 完全同一公式（v12：预留 120px 给提示卡）
        int available = height - 10 - 14 - 120;
        int bh = Math.max(110, Math.min(440, available));
        String title = I18n.get(layer.labelKey);
        String[] lbl = layer == GeoPalette.PreviewLayer.ELEVATION
                ? new String[]{"Y=999", "Y=99"}
                : GeoPalette.continuousLegendLabels(layer, this::localize);
        int labelW = Math.max(mc.font.width(lbl[0]), mc.font.width(lbl[1]));
        int cardW = 8 + barW + 15 + Math.max(labelW, mc.font.width(title)) + 8;
        int cardH = bh + 30;
        int cardX = getX() + width - cardW - 12;
        // ★ 与 drawContinuousLegendCard 同一坐标（图例贴顶）
        int cardY = getY() + 10;
        return new int[]{cardX, cardY, cardW, cardH};
    }

    private void drawContinuousLegendCard(GuiGraphics g, GeoPalette.PreviewLayer layer,
                                          int lx, int ly) {
        // ★★ v8【重做：用户「示意图右侧的高程图例实际还是没在游戏里完美复刻」】★★
        //
        //   旧版的问题（逐条对照示意图）：
        //     ① 色带只有 12px 宽 —— 示意图里是<b>成块的色阶条</b>（约 24px，看得出分层）
        //     ② 只有上下两个端点文字 —— 示意图是<b>分段刻度 + 每段数值</b>
        //     ③ 卡片高度按 height*0.62 浮动 —— 示意图里高度是<b>固定且饱满</b>的
        //     ④ 没有刻线、没有中间值 —— 用户没法从图例读出"这块地大概多高"
        //
        //   现在：色阶条 + 左侧刻度线 + 右侧数值（自适应步长，避免文字重叠）。
        int barW = 20;   // ★ v12：22 → 20，略收窄，给右侧数值列腾空间

        // ★★ v12【修"提示卡压着色带下沿"】★★
        //   上一版按 `height - 22 - 96` 估预算，但提示卡实际高度取决于文字行数
        //   （标题 + 4 行 + 行距 ≈ 78~86px，随字体缩放浮动）⇒ 96 不够，色带下沿
        //   与提示卡标题叠在一起（用户截图：`平面视图操作` 压在 `19` 刻度上）。
        //   现在把预算提高到 <b>120px</b> 并额外留 10px 安全边，
        //   同时把图例的最小高度降到 110 —— 小窗口下也能两者共存。
        int hintReserve = 120;
        int topPad = 10, bottomPad = 14;
        int available = height - topPad - bottomPad - hintReserve;
        int bh = Math.max(110, Math.min(440, available));

        String[] lbl;
        if (layer == GeoPalette.PreviewLayer.ELEVATION) {
            lbl = new String[]{"Y=" + (int) Math.round(heightFromE(elevEMax)),
                               "Y=" + (int) Math.round(heightFromE(elevEMin))};
        } else {
            lbl = GeoPalette.continuousLegendLabels(layer, this::localize);
        }

        String title = I18n.get(layer.labelKey);
        int titleW = mc.font.width(title);
        int labelW = Math.max(mc.font.width(lbl[0]), mc.font.width(lbl[1]));
        // ★ v12：内边距 10 → 8，标题与色带间距 28 → 24，整体更紧凑
        // ★ v13：色带与数值列之间留 15px（刻线 9 + 间隔 4 + 余量），卡片同步加宽
        int cardW = 8 + barW + 15 + Math.max(labelW, titleW) + 8;
        int cardH = bh + 30;

        int cardX = getX() + width - cardW - 12;
        int cardY = getY() + 10;

        // ★ v12：卡片底更透（0xD0 → 0xB8），减少对地形的遮挡；圆角略大更柔和
        com.geogenesis.client.preview.UiKit.roundRect(g, cardX, cardY,
                cardX + cardW, cardY + cardH, 7, 0xB8181C20);
        // 标题：字号不变但用次要色，避免与地形亮色抢注意力
        g.drawString(mc.font, title, cardX + 8, cardY + 7, 0xFFE8E8E8, false);

        int barX = cardX + 8;
        int barY = cardY + 24;

        // ---- 色阶条 ----
        for (int i = 0; i < bh; i++) {
            double p = 1.0 - (double) i / Math.max(1, bh - 1);
            int rgb = GeoPalette.continuous(layer, GeoPalette.legendGradientPos(layer, p));
            g.fill(barX, barY + i, barX + barW, barY + i + 1, GeoPalette.toARGB(rgb));
        }
        // ★ v12：色带外框从"整圈 1px 硬边"改为<b>只在上下两端加细圆角</b>，
        //   避免在色带两侧出现两条长直线（用户反馈"边框偏粗"的同类问题）。
        com.geogenesis.client.preview.UiKit.roundRectOutline(g, barX, barY,
                barX + barW, barY + bh, 2, 0x22FFFFFF);

        // ---- 刻度：自适应步长，保证文字不重叠 ----
        drawLegendTicks(g, layer, barX, barY, barW, bh, lbl);

        // ---- 中值刻度线（视觉锚点）----
        int midY = barY + bh / 2;
        g.fill(barX + barW + 2, midY, barX + barW + 6, midY + 1, 0x66FFFFFF);
    }

    /**
     * 连续图例的刻度（★ v8）。
     *
     * <p>步长按<b>可用高度</b>反推：每格至少 26px 才不会文字相撞。
     * 数值用图例自身的语义（高程给 Y 值，其它给 0..1 位置）。</p>
     */
    private void drawLegendTicks(GuiGraphics g, GeoPalette.PreviewLayer layer,
                                 int barX, int barY, int barW, int bh, String[] endLabels) {
        int minPx = 26;
        int slots = Math.max(2, bh / minPx);        // 可容纳的刻度数
        int steps = Math.max(2, Math.min(9, slots)); // 2..9 段

        boolean elevation = layer == GeoPalette.PreviewLayer.ELEVATION;
        double hi = 1.0, lo = 0.0;
        if (elevation) {
            hi = heightFromE(elevEMax);
            lo = heightFromE(elevEMin);
        }

        for (int s = 0; s <= steps; s++) {
            double t = 1.0 - (double) s / steps;   // 1 = 顶
            int y = barY + (int) Math.round((1 - t) * (bh - 1));
            // ★ v13 重做刻线：短横线改为「首尾加粗提亮、中间细淡」，与数值列分离
            boolean isEdge = (s == 0 || s == steps);
            int tickW = isEdge ? 9 : 6;
            int tickH = isEdge ? 2 : 1;
            g.fill(barX + barW + 2, y, barX + barW + 2 + tickW, y + tickH,
                    isEdge ? 0xE6FFFFFF : 0x66FFFFFF);
            // 数值
            String txt;
            if (elevation) {
                txt = String.valueOf((int) Math.round(lo + (hi - lo) * t));
            } else {
                txt = String.format(java.util.Locale.ROOT, "%.2f", t);
            }
            // ★ v13：数值统一到刻线右侧 4px 的同一列，不再紧贴刻线
            int ty = Math.max(barY - 1, Math.min(y - 4, barY + bh - 8));
            g.drawString(mc.font, txt, barX + barW + 15, ty,
                    isEdge ? 0xFFF2F2F2 : 0xFFB0B0B0, false);
        }
    }

    /** 读取 hover 位置 Cell（只读缓存，绝不触发地形采样——主线程采样会卡死渲染）。 */


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


    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ★ 沙盘快捷键（见左下角提示）：R = 重置视角并回到初始中心
        //   （与地图软件"回到初始视图"一致；只复位视角不回中心会让用户找不到地图）
        if (sandboxMode && !legendSearchActive && keyCode == GLFW.GLFW_KEY_R) {
            sandboxRasterizer.resetView();
            if (homeCenterX != null) {
                centerX = homeCenterX;
                centerZ = homeCenterZ;
            }
            sandboxCenterAccX = 0;
            sandboxCenterAccZ = 0;
            needsClear = true;
            return true;
        }
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

    /**
     * <b>交出 terrain 所有权</b>（★ 2026-11-09 预览 → 世界复用）。
     *
     * <h2>为什么需要</h2>
     * <p>用户点「应用 / 创建世界」后，本预览算好的 {@code GeoGenesisTerrain}
     * （含 4096-chunk 缓存 + 细层水文 region）要交给<b>进世界生成</b>接管，不重算。</p>
     *
     * <p>但 {@link #close()} 会 {@code terrain.closeProcessErosion()} —— 那会把即将
     * 被游戏使用的引擎关停。⇒ 交出前先调本方法，把 {@code ownsTerrain} 置 false，
     * 之后 {@code close()} 只释放自己的资源（线程池/纹理），不碰 terrain。</p>
     *
     * <p>语义与 {@code ownsTerrain=false}（共享存档单例）一致：<b>释放使用权，不销毁对方</b>。</p>
     */
    public void transferOwnership() {
        this.ownsTerrain = false;
    }

    /**
     * 沙盘底色来源（★ 2026-11-09 B3）。
     *
     * <p>与平面模式<b>同图例同颜色</b>：连续图层走 {@link GeoPalette} 色带、
     * 离散图层走其离散表。归一化公式必须与 {@code GeoPalette.color} 一致
     * （否则切模式会变色）。</p>
     */
    private com.geogenesis.client.preview.sandbox.SandboxGeometry.ColorSource sandboxColorSource() {
        final GeoPalette.PreviewLayer layer = activeLayer;
        final int mnY = minY, mxY = maxY;
        return (gx, gz, snap) -> {
            int i = snap.index(gx, gz);
            if (i < 0) return 0x505050;
            if (layer.kind == GeoPalette.Kind.DISCRETE) {
                int id = switch (layer) {
                    case TERRAIN_TYPE -> snap.terrainId[i] & 0xFFFF;
                    case RIVER_TYPE -> snap.extraId[i * 4] & 0xFFFF;
                    case ROCK_TYPE -> snap.extraId[i * 4 + 1] & 0xFFFF;
                    case ROCK_LAYER -> snap.extraId[i * 4 + 2] & 0xFFFF;
                    default -> snap.biomeClassId[i] & 0xFFFF;
                };
                return GeoPalette.discrete(layer, id) & 0xFFFFFF;
            }
            double pos = switch (layer) {
                case ELEVATION -> Math.max(0.0, Math.min(1.0,
                        (snap.e[i] - elevEMin) / Math.max(1e-6, elevEMax - elevEMin)));
                case TEMPERATURE -> (snap.temperature[i] + 1.0) * 0.5;
                case HUMIDITY -> (snap.humidity[i] + 1.0) * 0.5;
                case PRECIPITATION ->
                        Math.sqrt(Math.max(0.0, Math.min(1.0, snap.precipitation[i] / 1.2)));
                case CONTINENTALITY -> (snap.continent[i] + 1.0) * 0.5;
                case RELIEF -> (snap.shape[i] + 1.0) * 0.5;
                case RIVER_NETWORK -> snap.discharge[i] > 0
                        ? Math.min(1.0, Math.log1p(snap.discharge[i]) / 6.0) : 0.0;
                default -> 0.0;
            };
            return GeoPalette.continuous(layer, pos) & 0xFFFFFF;
        };
    }

    public void close() {
        pool.shutdown();
        // ★ 2026-11-08：沙盘采样线程池必须一起停。
        //   ProgressiveSampler 用的是独立线程池（核数÷2），漏停会让采样线程
        //   在关屏后继续跑、还持有 terrain 引用 → 线程泄漏 + 打到已关闭的 terrain。
        if (largeSampler != null) largeSampler.stop();
        // ★ 只关闭自己拥有的实例：共享的存档单例由游戏侧管理生命周期，
        //   关掉配置屏不能把游戏正在用的引擎关停（见 setTerrain 的 ownsTerrain 说明）。
        if (ownsTerrain && terrain != null) terrain.closeProcessErosion();
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
    /**
     * 一行状态文本，供全屏配置屏的右下角 HUD 使用（★ 2026-11-08 全屏地图改造）。
     *
     * <p>格式：{@code 1:64 · 大范围 · 64×64 · 32ms}。
     * 全屏后缩放标签从预览内部移到屏幕 HUD，避免压在地形正中央。</p>
     *
     * @return 永不为 null；无数据时返回 {@code "1:1"}
     */
    /**
     * 采样完成度 0..1（★ 2026-11-09 v6 布局：导航栏底部进度条）。
     *
     * <p>平面管线按 chunk 后台增量采样，没有全局百分比；
     * 用「已缓存 chunk / 视口所需 chunk」估算。大范围模式恒 1（一次采完固定网格）。</p>
     *
     * <p>★ 沙盘模式直接用采样器真实进度（它有准确的 progress()）。</p>
     */
    public float progressFraction() {
        if (sandboxMode && largeSampler != null && largeSampler.data() != null) {
            return largeSampler.progress();
        }
        if (largeArea) return 1.0f;
        // ★ 2026-11-09【修"进度条永远 17%"】：
        //   视口 chunk 数必须按<b>渲染纹理尺寸 × scale</b> 算，不是按屏幕像素。
        //   日志实测 viewport 54×30 = 1620 chunks；
        //   之前用 width/scale（1967 格）⇒ need 算成 125×76 = 9500，
        //   进度条永远停在 17%（实际早就采完了）。
        int blocksWide = texW * scaleBlockPos;
        int blocksHigh = texH * scaleBlockPos;
        int needCX = Math.max(1, blocksWide / 16 + 2);
        int needCZ = Math.max(1, blocksHigh / 16 + 2);
        int need = needCX * needCZ;
        return Math.min(1f, cellCache.chunkCount() / (float) need);
    }

    public String statusLine() {
        // ★ 沙盘模式：显示<b>比例尺</b>（像地图软件那样），而不是平面模式的 1:N
        if (sandboxMode) {
            StringBuilder sb = new StringBuilder(
                    SandboxLod.scaleLabel(sandboxRasterizer.zoom()));
            // 地图中心（拖动后能看出位置真的变了）
            sb.append(String.format(java.util.Locale.ROOT, " · 中心 %d,%d", centerX, centerZ));
            if (largeSampler != null && largeSampler.data() != null) {
                TerrainFieldData d = largeSampler.data();
                sb.append(" · ").append(d.gridW).append('×').append(d.gridH);
                sb.append(" · ").append(largeSampler.lastCostMs()).append("ms");
                if (!largeSampler.isComplete()) {
                    sb.append(" · ").append((int) (largeSampler.progress() * 100)).append('%');
                }
            }
            if (largeSampler != null && largeSampler.isHydroPath()) {
                sb.append(" · 水文管线");
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder("1:").append(scaleBlockPos);
        if (largeArea) {
            sb.append(" · ").append(localize("geogenesis.preview.large_area"));
            if (largeSampler != null && largeSampler.data() != null) {
                TerrainFieldData d = largeSampler.data();
                sb.append(" · ").append(d.gridW).append('×').append(d.gridH);
                sb.append(" · ").append(largeSampler.lastCostMs()).append("ms");
                if (!largeSampler.isComplete()) {
                    sb.append(" · ").append((int) (largeSampler.progress() * 100)).append('%');
                }
            }
        }
        return sb.toString();
    }

    /** 是否隐藏预览自带的缩放标签（全屏模式下由屏幕 HUD 代劳）。 */
    public void setDrawZoomLabel(boolean v) { this.drawZoomLabel = v; }

    boolean drawZoomLabel = true;

    /** 缩放比标签：1:N 右下角（★ 大范围模式附带标记——否则分不清当前走的是哪条管线） */

    /**
     * 沙盘 HUD —— <b>照参考项目布局</b>（{@code YardSiteScreen.drawTopLeft / drawProgress}）：
     *
     * <pre>
     *   左上：标题（金色）· 种子/范围/中心 · 操作说明（折行，暗灰）
     *   右缘：进度细竖条（4px，金色填充）
     *   左下：悬停处的群系 / 区块 / 方块坐标 / 高度
     *   右下：比例尺
     * </pre>
     *
     * <p>★ 之前只有一个右下角状态栏，与参考项目差距明显（用户指出"UI 布局完全不同"）。</p>
     */
    /**
     * 沙盘 HUD（★ 2026-11-09 B4a：已搬到 {@code SandboxHud}）。
     *
     * <p>本方法只负责<b>填数据快照</b>再委托 —— HUD 本身不碰采样器与地形实例。</p>
     */
    void drawSandboxHints(GuiGraphics g, int x, int y, int w, int h) {
        com.geogenesis.client.preview.raster.SandboxHud.HudData d =
                new com.geogenesis.client.preview.raster.SandboxHud.HudData();
        d.seed = seed;
        d.centerX = centerX + (int) totalDragX;
        d.centerZ = centerZ + (int) totalDragZ;
        d.zoom = sandboxRasterizer.zoom();
        if (largeSampler != null && largeSampler.data() != null && !largeSampler.isComplete()) {
            d.progress = largeSampler.progress();
        }
        int picked = sandboxRasterizer.pickedColumn();
        if (picked >= 0) {
            d.pickedColX = sandboxRasterizer.pickedColX();
            d.pickedColZ = sandboxRasterizer.pickedColZ();
            d.pickedTopY = sandboxRasterizer.pickedTopY();
            int windowBlocks = SandboxLod.windowFor(sandboxRasterizer.zoom());
            double step = SandboxLod.effectiveStep(sandboxRasterizer.zoom());
            d.hoverWorldX = d.centerX - windowBlocks / 2
                    + (int) Math.round(d.pickedColX * step);
            d.hoverWorldZ = d.centerZ - windowBlocks / 2
                    + (int) Math.round(d.pickedColZ * step);
        }
        com.geogenesis.client.preview.raster.SandboxHud.render(g, x, y, w, h, d);
    }

    // =================================================================
    // ★ 2026-11-09 B4b：平面管线委托给 {@link PlanePainter}
    //
    //   这些方法原本在本类里，B4b 原样剪切到 PlanePainter（零逻辑改动）。
    //   这里保留薄委托，让既有调用点无需改动 —— 平面视觉因此<b>逐像素不变</b>。
    // =================================================================

    private void drawZoomLabel(GuiGraphics g, int x, int y, int w, int h) {
        plane.drawZoomLabel(g, x, y, w, h);
    }

    private void drawFrameTime(GuiGraphics g, int rightX, int topY) {
        plane.drawFrameTime(g, rightX, topY);
    }

    private void drawCopiedToast(GuiGraphics g, int px, int py, int pw, int ph) {
        plane.drawCopiedToast(g, px, py, pw, ph);
    }

    private static int alignDown(int v, int scale) {
        return PlanePainter.alignDown(v, scale);
    }

    private void paintLargeArea(int originWx, int originWz, int blocksWide, int blocksHigh) {
        plane.paintLargeArea(originWx, originWz, blocksWide, blocksHigh);
    }

    private void paintAvailableChunks(int originWx, int originWz, int blocksWide, int blocksHigh) {
        plane.paintAvailableChunks(originWx, originWz, blocksWide, blocksHigh);
    }

    private void applySlopeShading(com.mojang.blaze3d.platform.NativeImage img,
                                   int originWx, int originWz) {
        plane.applySlopeShading(img, originWx, originWz);
    }

    private void drawSpawnMarker(GuiGraphics g) {
        plane.drawSpawnMarker(g);
    }

    private void drawPlayerMarker(GuiGraphics g) {
        plane.drawPlayerMarker(g);
    }

    private void drawStructureMarkers(GuiGraphics g, int mx, int my) {
        plane.drawStructureMarkers(g, mx, my);
    }

    private Cell sampleHoverCell(int wx, int wz) {
        return plane.sampleHoverCell(wx, wz);
    }

    private void drawHoverInfo(GuiGraphics g, int mx, int my) {
        plane.drawHoverInfo(g, mx, my);
    }

    private double heightFromE(double e) {
        return plane.heightFromE(e);
    }

    private String localize(String key) {
        return plane.localize(key);
    }

}
