package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.GeoPalette;

/**
 * 光栅化参数快照（★ 2026-11-09）。
 *
 * <p>照 FreeTerraForged 的 {@code IPreviewHandler.RasterParams}：
 * 在<b>渲染线程</b>捕获、传给后台线程使用 ⇒ 避免后台读到正在被 UI 改的字段。</p>
 *
 * <p>零 MC 依赖：{@link GeoPalette.PreviewLayer} 是纯枚举。</p>
 *
 * @param width     画布宽（像素）
 * @param height    画布高（像素）
 * @param zoomFactor 缩放归一化值 [0,1] —— 两个视图各自解释语义
 *                  （平面：精度档；沙盘：视野档），但滑块物理位置一致
 * @param layer     当前图层（14 个，两视图共用）
 * @param seaLevel  海平面 Y（预览可配，不能硬编码 63）
 */
public record RasterParams(int width, int height, float zoomFactor,
                           GeoPalette.PreviewLayer layer, int seaLevel) {

    /** 画布像素总数。 */
    public int pixels() {
        return Math.max(0, width) * Math.max(0, height);
    }

    public RasterParams withSize(int w, int h) {
        return new RasterParams(w, h, zoomFactor, layer, seaLevel);
    }

    public RasterParams withZoom(float z) {
        return new RasterParams(width, height, z, layer, seaLevel);
    }

    public RasterParams withLayer(GeoPalette.PreviewLayer l) {
        return new RasterParams(width, height, zoomFactor, l, seaLevel);
    }
}
