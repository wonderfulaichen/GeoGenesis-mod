package com.geogenesis.client.preview;

import com.geogenesis.client.preview.raster.RasterParams;

/**
 * <b>预览共享状态</b> —— 两个视图共用一份（★ 2026-11-09 统一重构）。
 *
 * <h2>设计来源</h2>
 * <p>照 FreeTerraForged 的 {@code PreviewState}：2D 和 3D 各持一个实例，
 * 但<b>字段完全相同</b>，共享逻辑（接口 default 方法）从这里读写。
 * 我们更进一步：<b>整个控制器只持有一个实例</b>，两个视图真正共享同一份状态。</p>
 *
 * <h2>★ 共用 vs 各自记忆（用户裁定 2026-11-09）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>共用？</th><th>理由</th></tr>
 *   <tr><td>{@link #centerX}/{@link #centerZ}</td><td><b>共用（硬性）</b></td>
 *       <td>不共用则切换模式会跳到别处，失去"同一片地形两种看法"的意义</td></tr>
 *   <tr><td>{@link #zoomFactor}</td><td>共用</td>
 *       <td>各 rasterizer 按自己语义解释，但滑块物理位置一致</td></tr>
 *   <tr><td>{@link #layer}</td><td>共用</td>
 *       <td>用户要求温度/湿度/高程/群系在两个视图都能看</td></tr>
 *   <tr><td>{@link #seed}</td><td>共用</td>
 *       <td>本来就是同一个世界</td></tr>
 * </table>
 *
 * <h2>零 MC 依赖</h2>
 * <p>允许探针直接实例化做结构验收。</p>
 */
public final class PreviewState {

    // ===== 共用（两视图一致）=====

    /** 视图中心世界坐标（方块）—— <b>切换模式必须保持</b>。 */
    public int centerX, centerZ;

    /**
     * 缩放归一化值 [0,1]（照 RTF 的滑块语义）。
     *
     * <pre>
     *   平面：precision = 1 &lt;&lt; round(zoomFactor * 4)          // 1:1 ~ 1:16
     *   沙盘：windowBlocks = 2048 * 2^round((0.5-zf)*4)      // 512 ~ 8192 格
     * </pre>
     */
    public float zoomFactor = 0.5f;

    /** 当前图层（14 个，两视图共用）。 */
    public GeoPalette.PreviewLayer layer = GeoPalette.PreviewLayer.ELEVATION;

    /** 世界种子。 */
    public long seed;

    /** 当前视图模式。 */
    public TerrainViewMode viewMode = TerrainViewMode.PLANE;

    // ===== 数据（唯一一份）=====

    /** 当前快照；两个 rasterizer 共读。 */
    public TerrainSnapshot snapshot;

    /** 快照标识；变了才重建。 */
    public long snapshotKey = Long.MIN_VALUE;

    /** 光栅化参数（渲染线程捕获）。 */
    public RasterParams params;

    // ===== 图例（照 RTF 的 4 行结构）=====

    /** 固定 4 行标签；第 3 行（索引 2）随图层变。 */
    public final String[] legendLabels = { "范围", "地形", "图层值", "中心" };

    /** 4 行值。 */
    public final String[] legendValues = { "", "", "", "" };

    // ===== 悬停 =====

    /** 悬停世界坐标；-1 = 不在图上。 */
    public int hoverX = -1, hoverZ = -1;

    /** 悬停描述行（左下角浮窗）。 */
    public String hoverLine = "";

    // ===== 生命周期 =====

    /** 需要重建快照或重绘。 */
    public boolean dirty = true;

    /** 已关闭（禁止再提交异步结果）。 */
    public volatile boolean closed;

    /**
     * 光栅化结果版本号 —— 防止旧结果覆盖新结果（照 RTF 的 {@code rasterRequestVersion}）。
     */
    public long rasterVersion;

    /** 待提交的像素（异步光栅化完成后由主线程采纳）。 */
    public int[] pendingPixels;
    public int pendingW, pendingH;
    public boolean pendingReady;

    /** 悬停坐标是否有效。 */
    public boolean hasHover() {
        return hoverX != -1 || hoverZ != -1;
    }

    /** 清空悬停。 */
    public void clearHover() {
        hoverX = hoverZ = -1;
        hoverLine = "";
    }
}
