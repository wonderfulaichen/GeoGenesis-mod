package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainSnapshot;
import com.geogenesis.client.preview.TerrainViewMode;

/**
 * <b>平面光栅化器</b> —— 俯视正射（★ 2026-11-09 B2）。
 *
 * <h2>★ 定位：代理，不是重写</h2>
 * <p>本类<b>不重新实现</b>平面绘制。原因：{@code PreviewDisplay} 的平面管线
 * （{@code TerrainQueue} → {@code CellCache} → {@code DynamicTexture}，
 * 含坡度阴影 {@code applySlopeShading}、水文叠加、结构图标、精确 1:1~1:16）
 * 是「<b>预览 = 游戏</b>」的唯一保证 —— 它已经把地形产出做过指纹对拍。</p>
 *
 * <p>重写一版会让这个保证失效（那正是本轮要修的「两套平行实现」病）。
 * 本类做的是<b>接线</b>：把 {@link TerrainSnapshot} 适配成平面管线要的输入，
 * 并把「一行图例 + hover 结果」翻译成统一接口。</p>
 *
 * <h2>与沙盘的分工</h2>
 * <table border="1">
 *   <tr><th></th><th>平面</th><th>沙盘</th></tr>
 *   <tr><td>几何</td><td>无（1 格 = 1 像素）</td><td>{@code SandboxGeometry}（纯函数）</td></tr>
 *   <tr><td>颜色</td><td>完整 {@code GeoPalette}（14 图层 + 色带）</td><td>同（经 {@code ColorSource}）</td></tr>
 *   <tr><td>精度承诺</td><td><b>预览 = 游戏</b></td><td>看形态</td></tr>
 * </table>
 */
public final class PlaneRasterizer implements TerrainRasterizer {

    @Override
    public TerrainViewMode mode() {
        return TerrainViewMode.PLANE;
    }

    @Override
    public RasterParams paramsFor(TerrainSnapshot snapshot, int viewportW, int viewportH,
                                  float zoomFactor, GeoPalette.PreviewLayer layer, int seaLevel) {
        // ★ 画布<b>恒等于快照细格数</b>（1 细格 = 1 像素）。
        //   照 RTF：{@code RasterParams(SIZE, SIZE, 0)}，SIZE == tile 边长。
        //   曾用视口尺寸（512）对 64 细格 ⇒ 放大约 8 倍，
        //   hover 反向映射时两次取整累积出 40 格偏差（探针实测）。
        int side = snapshot == null ? 256 : Math.max(1, snapshot.gridW);
        return new RasterParams(side, side, zoomFactor, layer, seaLevel);
    }

    /**
     * 俯视正射光栅化。
     *
     * <p>本实现<b>独立于</b> {@code PreviewDisplay} 的纹理管线，只用于
     * 探针出图与结构验收（验证"同一份快照能喂给两个 rasterizer"）。
     * 实机走 {@code PreviewDisplay} 的 {@code DynamicTexture}。</p>
     */
    @Override
    public int[] rasterize(TerrainSnapshot snapshot, RasterParams params) {
        if (snapshot == null) return new int[0];
        int w = params.width(), h = params.height();
        if (w <= 0 || h <= 0) return new int[0];
        int[] px = new int[w * h];
        double sea = params.seaLevel();

        for (int py = 0; py < h; py++) {
            int gz = Math.min(snapshot.gridH - 1, py * snapshot.gridH / h);
            for (int px2 = 0; px2 < w; px2++) {
                int gx = Math.min(snapshot.gridW - 1, px2 * snapshot.gridW / w);
                int i = gx * snapshot.gridH + gz;
                int rgb = snapshot.isWater(i)
                        ? waterColor(snapshot.surfaceY(i, sea), sea)
                        : elevationColor(snapshot.e[i]);
                px[py * w + px2] = 0xFF000000 | rgb;
            }
        }
        return px;
    }

    @Override
    public HoverInfo hover(TerrainSnapshot snapshot, RasterParams params, int mx, int my) {
        if (snapshot == null || params.width() <= 0) return null;
        int gx = Math.max(0, Math.min(snapshot.gridW - 1,
                (mx - 0) * snapshot.gridW / Math.max(1, params.width())));
        int gz = Math.max(0, Math.min(snapshot.gridH - 1,
                (my - 0) * snapshot.gridH / Math.max(1, params.height())));
        int i = gx * snapshot.gridH + gz;
        return new HoverInfo(snapshot.worldX(gx), snapshot.worldZ(gz),
                snapshot.isWater(i) ? "water" : "",
                String.format(java.util.Locale.ROOT, "Y=%.0f",
                        snapshot.surfaceY(i, params.seaLevel())),
                snapshot.surfaceY(i, params.seaLevel()));
    }

    @Override
    public String legendLine0(TerrainSnapshot snapshot, RasterParams params) {
        if (snapshot == null) return "";
        return String.format(java.util.Locale.ROOT, "%d×%d 格 · 1:%d 精度",
                snapshot.blocksWide, snapshot.blocksHigh,
                Math.max(1, params.width() > 0 ? snapshot.blocksWide / params.width() : 1));
    }

    // ----- 取色（探针用；实机走 GeoPalette）-----

    private static int elevationColor(double e) {
        double t = Math.max(0.0, Math.min(1.0, (e + 1.0) * 0.5));
        double[][] stops = {
                {0.00, 0x1B3A6B}, {0.30, 0x2E7FA8}, {0.45, 0x3E8B4A},
                {0.65, 0xA8A040}, {0.82, 0x8B5A2B}, {1.00, 0xE8E8E8}
        };
        for (int k = 0; k < stops.length - 1; k++) {
            double a = stops[k][0], b = stops[k + 1][0];
            if (t <= b) {
                double f = (b - a) < 1e-9 ? 0 : Math.max(0, Math.min(1, (t - a) / (b - a)));
                int ca = (int) stops[k][1], cb = (int) stops[k + 1][1];
                int r = (int) ((ca >> 16 & 0xFF) * (1 - f) + (cb >> 16 & 0xFF) * f);
                int g = (int) ((ca >> 8 & 0xFF) * (1 - f) + (cb >> 8 & 0xFF) * f);
                int bl = (int) ((ca & 0xFF) * (1 - f) + (cb & 0xFF) * f);
                return (r << 16) | (g << 8) | bl;
            }
        }
        return 0xE8E8E8;
    }

    private static int waterColor(double surfaceY, double seaLevel) {
        float depth = (float) ((seaLevel - surfaceY) / 30.0f);
        depth = Math.max(0f, Math.min(1f, depth));
        int from = 0x6FA8D6, to = 0x1C3F6B;
        int r = (int) ((1 - depth) * ((from >> 16) & 0xFF) + depth * ((to >> 16) & 0xFF));
        int g = (int) ((1 - depth) * ((from >> 8) & 0xFF) + depth * ((to >> 8) & 0xFF));
        int b = (int) ((1 - depth) * (from & 0xFF) + depth * (to & 0xFF));
        return (r << 16) | (g << 8) | b;
    }
}
