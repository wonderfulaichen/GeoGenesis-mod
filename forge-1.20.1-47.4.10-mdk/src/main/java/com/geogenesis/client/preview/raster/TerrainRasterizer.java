package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainSnapshot;
import com.geogenesis.client.preview.TerrainViewMode;

/**
 * <b>地形光栅化器</b> —— 一个视图 = 一个实现（★ 2026-11-09 统一重构）。
 *
 * <h2>设计来源：FreeTerraForged 的 {@code IPreviewHandler}</h2>
 * <p>RTF 把 2D/3D 的<b>全部共性</b>收进接口 default 方法（异步流水线、防抖、取消、
 * LRU 缓存、图例、点击/滚轮），只留下 <b>4 处真正不同</b>。
 * 其中唯一涉及算法的是 {@code createRasterData()} ——
 * <b>「一格怎么变成像素」</b>。本接口照此设计。</p>
 *
 * <h2>★ 与 RTF 的关键差异：算 / 画分离</h2>
 * <p>RTF 的 3D 是纯 Java 算 {@code int[]}（软件光栅化），我们的规模是它的 4 倍
 * （我们 512² = 262,144 柱 vs 它 256² = 65,536），软件光栅化不可行。</p>
 * <p>但 RTF 的真正价值是<b>可离线验证</b>。我们的对策是把「算」和「画」拆开：</p>
 * <pre>
 *   SandboxGeometry（纯函数）  ← 所有可能出错的数学在这里
 *          ↓ 同一份 Column[]
 *   ├─ 探针：CPU 填充 → PNG（离线验证）
 *   └─ SandboxRasterizer：提交 vertex（实机只画，不算）
 * </pre>
 *
 * <h2>★ 零 MC 依赖</h2>
 * <p>本接口与 {@link RasterParams} / {@link HoverInfo} 必须零 {@code net.minecraft.*}
 * import —— 探针进程没有 MC 环境，仍要能实例化两个 rasterizer 做结构验收。</p>
 */
public interface TerrainRasterizer {

    /** 本实现服务的视图模式。 */
    TerrainViewMode mode();

    /**
     * 给定快照与视口，本实现需要什么尺寸的参数。
     *
     * <p>2D 固定输出 256×256（与采样网格同尺寸）；3D 跟随视口宽高。</p>
     */
    RasterParams paramsFor(TerrainSnapshot snapshot, int viewportW, int viewportH,
                           float zoomFactor, GeoPalette.PreviewLayer layer, int seaLevel);

    /**
     * <b>光栅化成 ARGB 像素</b>（2D 俯视图 / 3D 等距投影图）。
     *
     * <p>返回数组长度 = {@code params.width * params.height}，
     * 字节序为 ABGR（与 {@code NativeImage.setPixelRGBA} 一致）。</p>
     *
     * <p>⚠ 3D 实现（我们）<b>不走这条路径</b>（性能不允许），
     * 它把 {@link #rasterize} 用于<b>离线验证</b>（探针出图），
     * 实机走 {@code SandboxRasterizer.draw()} 提交 OpenGL 顶点。
     * 两者消费同一份 {@code Column[]}，因此不会失同步。</p>
     */
    int[] rasterize(TerrainSnapshot snapshot, RasterParams params);

    /**
     * 屏幕坐标 → 该处的地形信息（悬停用）。
     *
     * @return 不在图上返回 {@code null}
     */
    HoverInfo hover(TerrainSnapshot snapshot, RasterParams params, int mx, int my);

    /**
     * 图例第一行（视图相关）。
     *
     * <p>例如平面「1:4 · 2048×2048 格」，沙盘「视野 2048 格 · 128 柱」。</p>
     */
    String legendLine0(TerrainSnapshot snapshot, RasterParams params);

    /** 释放 GPU 资源（纹理等）。2D 有纹理，3D 无。 */
    default void closeResources() { }
}
