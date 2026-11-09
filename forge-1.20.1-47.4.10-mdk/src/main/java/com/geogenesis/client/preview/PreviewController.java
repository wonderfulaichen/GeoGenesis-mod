package com.geogenesis.client.preview;

import com.geogenesis.client.preview.raster.HoverInfo;
import com.geogenesis.client.preview.raster.PlaneRasterizer;
import com.geogenesis.client.preview.raster.RasterParams;
import com.geogenesis.client.preview.raster.SandboxRasterizer;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.client.preview.raster.TerrainRasterizer;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.gui.GuiGraphics;

/**
 * <b>预览控制器</b> —— 两个视图的<b>统一状态与分派</b>（★ 2026-11-09 B3）。
 *
 * <h2>★ 解决的病：两套平行实现被塞进一个类</h2>
 * <p>用户原话：「明明我都说了要重构重构，结果各自完全独立分裂。」</p>
 * <p>旧结构（{@code PreviewDisplay} 2254 行）：</p>
 * <pre>
 *   renderWidget() {
 *       if (sandboxMode) { renderSandbox(...); ...; return; }   ← 分支 1
 *       ...平面精确管线 600 行...                                ← 分支 2
 *   }
 * </pre>
 *
 * <h2>新结构</h2>
 * <pre>
 *   PreviewController（本类）
 *     ├─ PreviewState     共享状态：中心 / 缩放 / 图层 / 视图模式
 *     └─ TerrainRasterizer  模式分派（平面 / 沙盘两个实现）
 *            ↑ 模式只存在于这一个字段上
 *
 *   绘制仍由 PreviewDisplay 承担（它是唯一绘制入口）：
 *     平面 → PreviewDisplay 的精确管线（预览 = 游戏）
 *     沙盘 → PreviewDisplay 内的 SandboxRasterizer（几何在 SandboxGeometry）
 * </pre>
 *
 * <h2>★ 为什么不把绘制也搬进控制器</h2>
 * <p>平面的精确管线（{@code TerrainQueue} → {@code CellCache} → {@code DynamicTexture}
 * + 坡度阴影 + 水文叠加 + 结构图标，1:1~1:16）是「<b>预览 = 游戏</b>」的唯一保证，
 * 已过指纹对拍。<b>重写它等于放弃这个保证</b> —— 那正是要修的病根。</p>
 * <p>⇒ 本类做<b>状态与分派</b>，绘制各走各自已验证的实现。</p>
 *
 * <h2>模式切换</h2>
 * <p>切模式 = 改 {@link #viewMode} 字段，<b>不重采</b>（数据都来自同一个
 * {@code CellCache}）⇒ 中心/缩放/图层全部保留，切换时构图不变。</p>
 */
public final class PreviewController implements AutoCloseable {

    private final PreviewState state = new PreviewState();

    private final PlaneRasterizer plane = new PlaneRasterizer();
    private final SandboxRasterizer sandbox = new SandboxRasterizer();

    /** 当前生效的 rasterizer —— <b>模式分派只在这里</b>。 */
    private TerrainRasterizer rasterizer = plane;

    /** 唯一绘制入口。 */
    private final PreviewDisplay display;

    private GeoGenesisTerrain terrain;
    private long seed;
    private TerrainParams params;

    public PreviewController(PreviewDisplay display, GeoGenesisTerrain terrain,
                             long seed, TerrainParams params) {
        this.display = display;
        this.terrain = terrain;
        this.seed = seed;
        this.params = params;
        this.state.seed = seed;
        this.state.viewMode = TerrainViewMode.PLANE;
    }

    // =================================================================
    // 生命周期
    // =================================================================

    /** 换地形实例（参数变更后）。 */
    public void setTerrain(GeoGenesisTerrain terrain, long seed, TerrainParams params) {
        this.terrain = terrain;
        this.seed = seed;
        this.params = params;
        this.state.seed = seed;
        this.state.snapshot = null;
        this.state.snapshotKey = Long.MIN_VALUE;
    }

    @Override
    public void close() {
        state.closed = true;
    }

    // =================================================================
    // 模式
    // =================================================================

    public TerrainViewMode viewMode() {
        return state.viewMode;
    }

    /**
     * 切换视图模式 —— <b>只换 rasterizer</b>，不重采、构图不变。
     */
    public void setViewMode(TerrainViewMode mode) {
        if (mode == null || mode == state.viewMode) return;
        state.viewMode = mode;
        rasterizer = (mode == TerrainViewMode.SANDBOX) ? sandbox : plane;
        // 让绘制入口知道当前模式（它负责实际画）
        display.setSandboxMode(mode == TerrainViewMode.SANDBOX);
    }

    /** 切换到下一个模式（按钮点击）。 */
    public void cycleViewMode() {
        setViewMode(state.viewMode.next());
    }

    // =================================================================
    // 共享状态
    // =================================================================

    public PreviewState state() { return state; }

    public void setLayer(GeoPalette.PreviewLayer layer) {
        state.layer = layer;
    }

    public GeoPalette.PreviewLayer layer() { return state.layer; }

    /** 缩放归一化值 [0,1]（两个视图共用同一滑块，各自解释语义）。 */
    public float zoomFactor() { return state.zoomFactor; }

    public void setZoomFactor(float z) {
        state.zoomFactor = Math.max(0f, Math.min(1f, z));
    }

    public int centerX() { return state.centerX; }
    public int centerZ() { return state.centerZ; }
    public void setCenter(int x, int z) { state.centerX = x; state.centerZ = z; }

    /** 当前快照（两个视图共用；无则 null）。 */
    public TerrainSnapshot snapshot() { return state.snapshot; }

    /** 当前 rasterizer（供外部读取图例/hover）。 */
    public TerrainRasterizer rasterizer() { return rasterizer; }

    /** 沙盘 rasterizer（供视角操作：拖动旋转/平移）。 */
    public SandboxRasterizer sandbox() { return sandbox; }

    /** 绘制入口（唯一）。 */
    public PreviewDisplay display() { return display; }

    // =================================================================
    // 图例 / hover
    // =================================================================

    /**
     * 更新图例四行（两模式共用同一结构，照 RTF）。
     *
     * <p>第 1 行随模式变化（视野/柱数），其余由 hover 决定。</p>
     */
    public void updateLegend(HoverInfo hover, String line0) {
        state.legendValues[0] = line0 == null ? "" : line0;
        if (hover != null) {
            state.hoverX = hover.worldX();
            state.hoverZ = hover.worldZ();
            state.hoverLine = hover.line();
            state.legendValues[1] = hover.terrain();
            state.legendValues[2] = hover.detail();
        } else {
            state.clearHover();
        }
        state.legendValues[3] = String.format(java.util.Locale.ROOT,
                "x%d z%d", state.centerX, state.centerZ);
    }

    /** 图例参数（沙盘用：视口尺寸 + 图层 + 海平面）。 */
    public RasterParams rasterParams(int w, int h, int seaLevel) {
        return rasterizer.paramsFor(state.snapshot, w, h, state.zoomFactor, state.layer, seaLevel);
    }

    // =================================================================
    // 沙盘渲染（★ 2026-11-09 B4a：从 PreviewDisplay 搬来）
    // =================================================================

    /**
     * <b>绘制沙盘</b>（斜视角方柱）。
     *
     * <p>数据来自平面视图的 {@code CellCache}（1 Cell = 1 方块，MC 方块级精度），
     * <b>零二次采样</b>。用户原话：「我们原来的平面视图不就有完整的数据吗？
     * 直接转换一下就好了。」</p>
     *
     * <p>★ 竖向比例照抄参考项目 {@code SiteTerrainRenderer.collect()} 第 661/677-687 行：
     * {@code span = 本图最高点 − 基准面}，经 {@code easeReliefSpan} 缓动（涨 8%/降 2%）。</p>
     */
    public void renderSandbox(GuiGraphics g, TerrainSnapshot snap, RasterParams rp,
                              int x, int y, int w, int h, float reliefSpan, long now) {
        if (snap == null) return;
        sandbox.setColorSource(colorSource());
        sandbox.draw(g, snap, rp, x, y, w, h, reliefSpan, now);
    }

    /** 沙盘 hover 拾取（视线推进）。 */
    public void pickSandbox(double mx, double my, int x, int y, int w, int h) {
        sandbox.pick(mx, my, x, y, w, h);
    }

    public void clearSandboxPick() {
        sandbox.clearPick();
    }

    public SandboxRasterizer sandboxRasterizer() {
        return sandbox;
    }

    /**
     * 沙盘底色来源 —— <b>与平面模式同图例同颜色</b>。
     *
     * <p>连续图层走 {@link GeoPalette#continuous}、离散图层走 {@link GeoPalette#discrete}，
     * 归一化公式与 {@code GeoPalette.color} 完全一致 ⇒ 切模式不会变色。</p>
     */
    private SandboxGeometry.ColorSource colorSource() {
        final GeoPalette.PreviewLayer layer = state.layer;
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

    private double elevEMin = -1.0, elevEMax = 1.0;

    public void setElevationRange(double emin, double emax) {
        this.elevEMin = emin;
        this.elevEMax = emax;
    }
}
