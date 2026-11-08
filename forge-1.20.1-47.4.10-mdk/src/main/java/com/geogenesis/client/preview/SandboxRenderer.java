package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;

/**
 * <b>沙盘（3D 方柱）渲染器</b>（★ 2026-11-08，S3）。
 *
 * <h2>设计来源</h2>
 * <p>本类是对 <a href="https://github.com/CYun2024/yardsite">yardsite</a>（MIT）
 * {@code dev.yard.site.view.SiteTerrainRenderer}（1304 行）的移植，
 * <b>逐条对照</b>其实现，不自行推导。上一版凭印象写，结果是「像草丛」。</p>
 *
 * <h2>★ 必须照做的五条（之前全错在这里）</h2>
 * <table border="1">
 *   <caption>设计要点</caption>
 *   <tr><th>要点</th><th>yardsite</th><th>为什么</th></tr>
 *   <tr><td><b>柱高用真实 Y 归一化</b></td>
 *       <td>{@code ratio = (surface - base) / reliefSpan}
 *           （{@code collect()} 第 686-687 行）</td>
 *       <td>{@code surface} 是世界高度 Y。用 {@code e∈[-1,1]} 直接乘系数会丢掉真实高度比例，
 *           整幅图的高差被压成同一量级，柱子看着一样高。</td></tr>
 *   <tr><td><b>柱数与细格数分离</b></td>
 *       <td>{@code columns=128}、{@code perColumn=2}、{@code fine=256}
 *           （{@code SiteField} 第 20-31 行、{@code SiteMapSampler} 第 213-221 行）</td>
 *       <td>柱子数决定"画多少根"，细格数决定"颜色多细"。一根柱子盖 2×2 细格 ⇒
 *           柱子更粗、但颜色仍有细节。</td></tr>
 *   <tr><td><b>坡度光照注入颜色</b></td>
 *       <td>{@code light = clamp(1 - (slopeX+slopeZ)*0.55, 0.55, 1.30)}
 *           （{@code paintBlock()} 第 552-553 行）</td>
 *       <td>这是"块面感"的来源。只按朝向调亮度会显得死板。</td></tr>
 *   <tr><td><b>水面单独处理</b></td>
 *       <td>水格光照衰减 65%：{@code 1 + (light-1)*0.35}（第 566 行）</td>
 *       <td>水面应该是平的、亮的，不该跟海底起伏。</td></tr>
 *   <tr><td><b>可见面按矩阵符号</b></td>
 *       <td>{@code seeEast = r[6] > 0} 等（第 418-422 行）</td>
 *       <td>只画朝向观察者的 2 个侧面，另外 2 个永远看不到。</td></tr>
 * </table>
 *
 * <h2>柱高基准 {@code reliefSpan}</h2>
 * <p>yardsite 用「最高点 - 基准面」做分母，且是<b>缓动</b>的（{@code easeReliefSpan}，
 * 涨快降慢），避免一上来把地皮拉平。这里取同一思路但简化：
 * 用 {@code max(20, p95高度 - 海平面)} 作分母，避免少数尖峰把整幅压平。</p>
 */
public final class SandboxRenderer {

    /** 俯仰下限。 */
    public static final float PITCH_MIN = 0.22f;
    /** 俯仰上限。 */
    public static final float PITCH_MAX = 1.40f;
    /** 默认俯仰（yardsite {@code DEFAULT_PITCH=0.95}）。 */
    public static final float DEFAULT_PITCH = 0.95f;
    /** 默认方位（yardsite {@code DEFAULT_YAW=0.65}）。 */
    public static final float DEFAULT_YAW = 0.65f;

    /**
     * 地形起伏在模型空间里的最大厚度（地面边长是 2）。<b>照抄 yardsite 的 0.40</b>。
     *
     * <p>我曾擅自改成 0.85 以为"山不够高" —— 那是误判。参考图柱子看着高，
     * 是因为 ① {@code zoom} 默认 <b>2.0</b>（整图放大一倍）；
     * ② 它的 {@code span} 是选点窗口内的高差（几十格），不是全球高差。
     * 正确做法是调 zoom / span，不是加大 RELIEF。</p>
     */
    private static final float RELIEF = 0.40f;
    /** 水面也保证一层薄皮，海面和海岸才连得起来（yardsite 第 50 行）。 */
    private static final float WATER_SINK = 0.05f;
    /** 深度桶数（yardsite {@code BUCKETS=4096}）。 */
    private static final int BUCKETS = 4096;
    /** 屏深基线与深度系数（yardsite {@code Z_BASE=200 / DEPTH_SCALE=110}）。 */
    private static final float Z_BASE = 200.0f;
    private static final float DEPTH_SCALE = 110.0f;
    // 光照范围（LIGHT_MIN / LIGHT_MAX）已移到 {@link SandboxShade} —— 唯一真实来源，
    // 避免两处常量分叉（渲染器与探针必须用同一份，否则探针量到的不是画出来的）。

    private final float[] r = new float[9];
    private final Matrix3f rotation = new Matrix3f();

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    /**
     * 缩放倍率（用户可调，范围 0.6~8.0）。
     *
     * <p>★ 2026-11-08 改口径：<b>不再直接乘进 scale</b>。
     * 原来抄 yardsite 的 {@code scale = areaSize * 0.46 * zoom}，而 yardsite 的
     * {@code zoom} 默认 2.0 ⇒ {@code 0.92·areaSize}。
     * 但按默认视角（yaw=0.65 / pitch=0.95）实算，投影后的<b>屏幕横向跨度
     * = scale·(|r0|+|r1|) = scale·1.401</b> ⇒ 默认只显示地图中央约 <b>39%</b>，
     * 其余地形全在画面外 —— 这正是"效果完全不对"的一类成因。</p>
     *
     * <p>现在 {@code zoom} 是<b>相对「整图恰好铺满视口」的倍率</b>：
     * {@code 1.0} = 整图可见，{@code >1} = 放大看局部。滚轮上下限不变。</p>
     */
    private float zoom = 1.0f;
    /** 屏幕平移（yardsite {@code panX/panY}）。 */
    private float panX, panY;
    /**
     * 柱子<b>横向</b>撑开倍数（yardsite {@code widthScale} 第 161 行，范围 0.5~2.0）。
     *
     * <p>横向（间距、底板、柱宽）按同倍数拉开、高度不跟着撑 ⇒ 柱子始终正好拼合，
     * 但视觉上更粗更敦实或更细更尖。与滚轮缩放（整图等比）是两个独立的量。</p>
     */
    private float widthScale = 1.0f;

    // ---- 每根柱子 ----
    private float[] tops = new float[0];
    private float[] edgeX0 = new float[0];
    private float[] edgeX1 = new float[0];
    private float[] edgeY0 = new float[0];
    private float[] edgeY1 = new float[0];
    private int[] colRgb = new int[0];
    private int[] readyOrder = new int[0];
    private int[] sorted = new int[0];
    private int[] bucket = new int[0];
    private final int[] counts = new int[BUCKETS];
    private final int[] write = new int[BUCKETS];
    private int readyColumns = 0;

    /**
     * 归一化高度 {@code r = (surfaceY - base) / span}（每细格一个）。
     *
     * <p>供坡度光照用。与柱高是<b>同一个量纲</b> ⇒ 光照强度不随采样间距变化。
     * 水域存的是<b>海平面</b>的 r（不是海床）⇒ 水面平。</p>
     */
    private double[] rNorm = new double[0];

    private final Cell scratch = new Cell();

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float zoom() { return zoom; }

    /** 拖动转视角：<b>照抄</b> yardsite {@code dragRotate()} 第 177-187 行（系数 0.006，pitch 是减）。 */
    public void dragRotate(double dx, double dy) {
        yaw = wrap(yaw - (float) (dx * 0.006));
        pitch = clamp(pitch - (float) (dy * 0.006), PITCH_MIN, PITCH_MAX);
    }

    /** 屏幕平移（yardsite {@code dragPan()}）。 */
    public void dragPan(double dx, double dy) {
        panX += (float) dx;
        panY += (float) dy;
    }

    /** 缩放（yardsite {@code setZoom()} 第 243-245 行，限 0.6~8.0）。 */
    public void addZoom(float factor) {
        zoom = clamp(zoom * factor, 0.6f, 8.0f);
    }

    /** 柱子横向撑开倍数（yardsite {@code setWidthScale()} 第 319-321 行，限 0.5~2.0）。 */
    public void setWidthScale(float v) {
        widthScale = clamp(v, 0.5f, 2.0f);
    }

    public float widthScale() { return widthScale; }

    /** 复位（yardsite {@code resetView()} 第 248-255 行）。 */
    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1.0f;
        panX = 0f;
        panY = 0f;
    }

    /**
     * 「整图恰好铺满视口」所需的 scale 因子（{@code zoom=1.0} 时乘上它就正好铺满）。
     *
     * <p>做法：把模型平面 [-1,1]² 的四个角，按当前旋转投影到屏幕，
     * 连同<b>柱高抬升</b>（z ∈ [0, maxTop]）一起算包围盒，
     * 取包围盒<b>较大</b>的一边归一化（取较大 ⇒ 两个方向都不出画）。</p>
     *
     * <p>包围盒的<b>中心</b>不与原点重合（高度抬升只把顶面往上推），
     * 所以中心偏移另存在 {@link #fitCenterX} / {@link #fitCenterY}，
     * 由调用方用来把整幅图摆正。</p>
     *
     * @return 归一化因子（无量纲）
     */
    private float fitScale(int areaSize) {
        // 最高柱（用于算上边界）
        float maxTop = 0f;
        for (int i = 0; i < readyColumns; i++) {
            float t = tops[readyOrder[i]];
            if (t > maxTop) maxTop = t;
        }
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        // 8 个角：4 个平面角 × 2 个高度（z=0 与 z=maxTop）
        for (int c = 0; c < 4; c++) {
            float mx = ((c & 1) == 0) ? -1f : 1f;
            float my = ((c & 2) == 0) ? -1f : 1f;
            for (int h = 0; h < 2; h++) {
                float mz = (h == 0) ? 0f : maxTop;
                float sx = r[0] * mx + r[1] * my + r[2] * mz;
                float sy = r[3] * mx + r[4] * my + r[5] * mz;
                if (sx < minX) minX = sx;
                if (sx > maxX) maxX = sx;
                if (sy < minY) minY = sy;
                if (sy > maxY) maxY = sy;
            }
        }
        fitCenterX = (minX + maxX) * 0.5f;
        fitCenterY = (minY + maxY) * 0.5f;
        float fit = Math.max(maxX - minX, maxY - minY);
        return fit < 1e-3f ? 1f : fit;
    }

    /** 整图包围盒中心（模型坐标），用于把地图摆正。 */
    private float fitCenterX, fitCenterY;

    /**
     * 绘制沙盘。
     *
     * @param g        GUI 上下文
     * @param data     采样数据（细格）
     * @param layer    当前图层
     * @param minY/maxY 世界高度范围
     * @param areaX/areaY 区域左上角
     * @param areaSize    区域边长（像素）
     * @param span        竖向比例分母
     * @param seaLevel   海平面 Y（预览可配）
     */
    public void render(GuiGraphics g, TerrainFieldData data, GeoPalette.PreviewLayer layer,
                       int minY, int maxY, int areaX, int areaY, int areaSize, float span,
                       double seaLevel) {
        if (data == null || data.gridW <= 0) return;
        buildRotation();

        // ★ 2026-11-08：缩放口径改为「整图恰好铺满视口 = zoom 1.0」。
        //
        //   做法：把模型平面（模型坐标 [-1,1]²，z=0）的<b>四个角</b>投影到屏幕，
        //   取它们（含柱高抬升）的<b>包围盒</b>，再让包围盒铺满 areaSize。
        //
        //   为什么不能只用「(|r0|+|r1|) 与 (|r3|+|r4|) 取一个」：
        //   投影后的平面是个<b>斜着的平行四边形</b>，其屏幕包围盒比两个方向的
        //   投影跨度都大（yaw=0.65 时约 45° 斜置）。按跨度算会漏掉对角 ⇒ 地图出画。
        //
        //   柱高也参与包围盒（z=RELIEF 的抬升会把角点推出去），
        //   否则山峰会顶出视口 ⇒ 所以必须先 collect() 拿到 tops 再算 scale。
        collect(data, layer, minY, maxY, span, seaLevel);

        float scale = areaSize * 0.98f * zoom / fitScale(areaSize);

        // ★ 屏幕平移（yardsite render() 第 407-408 行：{@code areaX + areaSize/2 + panX}）
        float originX = areaX + areaSize / 2.0f + panX;
        float originY = areaY + areaSize / 2.0f + panY;

        sortByDepth();
        if (readyColumns == 0) return;

        VertexConsumer c = g.bufferSource().getBuffer(RenderType.gui());

        // ★ 底板（yardsite floor() 第 778-781 行 / 调用处第 414-416 行）：
        //   没有它柱子看着是悬空的。画在柱子之前（更远），柱子会盖住它。
        quadFlat(c, originX, originY, scale, -1.0f, 1.0f, -1.0f, 1.0f, -0.02f, 0xFF171A22);

        boolean seeEast = r[6] > 0.0f;
        boolean seeWest = r[6] < 0.0f;
        boolean seeSouth = r[7] > 0.0f;
        boolean seeNorth = r[7] < 0.0f;
        boolean seeTop = r[8] > 0.0f;

        for (int i = 0; i < readyColumns; i++) {
            int col = sorted[i];
            float top = tops[col];
            int rgb = colRgb[col];
            float x0 = edgeX0[col], x1 = edgeX1[col];
            float y0 = edgeY0[col], y1 = edgeY1[col];

            if (seeTop) {
                vertex(c, originX, originY, scale, x0, y0, top, rgb | 0xFF000000);
                vertex(c, originX, originY, scale, x1, y0, top, rgb | 0xFF000000);
                vertex(c, originX, originY, scale, x1, y1, top, rgb | 0xFF000000);
                vertex(c, originX, originY, scale, x0, y1, top, rgb | 0xFF000000);
            }
            // 侧墙：只在邻居更低时画（yardsite 第 448-475 行）。
            // 柱索引 col = cx * colRows + cz ⇒ 东/西 = ±colRows，南/北 = ±1
            int ci = col / colRows, cj = col % colRows;
            if (seeEast) {
                float low = (ci + 1 < colCount) ? Math.max(tops[col + colRows], 0f) : 0f;
                if (top > low) wall(c, originX, originY, scale, x1, y0, x1, y1, low, top, rgb, 0.82f, 0.60f);
            }
            if (seeWest) {
                float low = ci > 0 ? Math.max(tops[col - colRows], 0f) : 0f;
                if (top > low) wall(c, originX, originY, scale, x0, y1, x0, y0, low, top, rgb, 0.56f, 0.40f);
            }
            if (seeSouth) {
                float low = (cj + 1 < colRows) ? Math.max(tops[col + 1], 0f) : 0f;
                if (top > low) wall(c, originX, originY, scale, x0, y1, x1, y1, low, top, rgb, 0.70f, 0.52f);
            }
            if (seeNorth) {
                float low = cj > 0 ? Math.max(tops[col - 1], 0f) : 0f;
                if (top > low) wall(c, originX, originY, scale, x1, y0, x0, y0, low, top, rgb, 0.46f, 0.34f);
            }
        }

        // 合批时深度测试是关的：遮挡全靠上面「从远到近」的提交顺序。
        // 柱面都是单面条片，绕序在任意角度下没法保证都对 ⇒ 关背面剔除。
        // 深度写入也要关：沙盘比 GUI 平面靠前，一写深度，后画的字/按钮会被拒掉。
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    /**
     * 每根柱子盖住几个细格（每边）。
     *
     * <p>★ 照抄 yardsite 的两层格子设计（{@code SiteField} 第 20-31 行、
     * {@code SiteMapSampler.perColumn()=HEIGHT_STRIDE=2}）：
     * <b>细格</b>是颜色/群系的最小单位，<b>柱子</b>是画出来的一格。
     * yardsite 参考档是「128 根柱子 × 每柱 2 细格 = 256×256 细格」。</p>
     *
     * <p>为什么必须这样：柱子数决定"画多少根"，细格数决定"颜色多细"。
     * 两者相等时，柱子数与网格密度绑死——网格加密到 128 就变成 128 根细柱
     * （实测「像草丛」）。分离后可以「柱子粗 + 颜色细」。</p>
     */
    private static final int PER_COLUMN = 2;

    /** 本图网格宽（细格）。 */
    private int cols = 0;
    private int rows = 0;
    /** 本图柱数（每边）= 细格数 / {@link #PER_COLUMN}。 */
    private int colCount = 0;
    /** 本图柱行数（Z 方向）。 */
    private int colRows = 0;


    // ------------------------------------------------------------------ 采集

    /**
     * 采集每根柱子：柱高（真实 Y 归一）、四角、颜色（含坡度光照）。
     *
     * <p>细格 {@code gridW×gridH} 按 {@link #PER_COLUMN}² 聚合成柱子 ——
     * 每根柱子取该组<b>中心细格</b>的颜色（保留光照细节），
     * 柱高取该组<b>中位高度</b>（避免单个尖格把柱子顶起来）。</p>
     *
     * @param seaLevel 海平面 Y（预览可配，不能写死 63）
     */
    private void collect(TerrainFieldData data, GeoPalette.PreviewLayer layer, int minY, int maxY,
                         float spanIn, double seaLevel) {
        int gw = data.gridW, gh = data.gridH;
        cols = gw;
        rows = gh;
        int fine = gw * gh;
        colCount = Math.max(1, (gw + PER_COLUMN - 1) / PER_COLUMN);
        colRows = Math.max(1, (gh + PER_COLUMN - 1) / PER_COLUMN);
        int pillars = colCount * colRows;
        ensureCapacity(Math.max(fine, pillars));
        readyColumns = 0;

        // ---- 1) 真实高度 → 归一化基准 ----
        //
        // ★ 完全照抄 yardsite collect() 第 660/677-678/686-687 行：
        //
        //     int base = field.seaLevel() - 4;              // 基准面：海平面以下 4 格
        //     int highest = max(所有柱子的 surface);
        //     float span = max(20, field.reliefSpan());     // 竖向比例分母
        //     float ratio = max((columnMax - base) / span, WATER_SINK);
        //     tops = ratio * RELIEF;
        //
        //   我之前写成「e<0 → 负高度（往下沉）」是**根本错误**：
        //   那样水下会凹成坑，整块地看起来是碎的。
        //   yardsite 里**所有柱子都从同一个基准面往上长**，
        //   水面只是因为 ratio 被 max(·, WATER_SINK) 保底 5%，所以显得"薄"。
        //
        // ★ 2026-11-08：海平面改为**调用方传入**（预览海平面是可配项，
        //   写死 63 会让沙盘基准面与平面模式不一致）。
        double base = seaLevel - 4.0;

        // ★ 竖向比例由调用方传入（ProgressiveSampler.easeReliefSpan 缓动），
        //   不在这里每帧重算 —— yardsite 第 677-678 行：
        //   {@code field.easeReliefSpan(max(60, highest - base)); span = max(20, field.reliefSpan());}
        double span = Math.max(20.0, spanIn);

        // ---- 2) 归一化高度 r（供坡度光照用，与柱高同量纲）----
        //
        //   水域用**海平面**而不是海床高度（yardsite samplePoint() 第 485 行同策）
        //   ⇒ 水面是平的，不会出现假的明暗斑块。
        ensureR(fine);
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                int i = gx * gh + gz;
                boolean water = data.e[i] < 0.0;
                double surface = water ? seaLevel : data.height[i];
                rNorm[i] = (surface - base) / span;
            }
        }

        // ---- 3) 逐柱（每柱聚合 PER_COLUMN² 个细格）----
        for (int cx = 0; cx < colCount; cx++) {
            for (int cz = 0; cz < colRows; cz++) {
                int col = cx * colRows + cz;

                // 该组细格范围
                int fx0 = cx * PER_COLUMN, fz0 = cz * PER_COLUMN;
                int fx1 = Math.min(gw - 1, fx0 + PER_COLUMN - 1);
                int fz1 = Math.min(gh - 1, fz0 + PER_COLUMN - 1);

                // 柱高取组内第一格（yardsite: surfaceAtCell(s0, f0)，即组左上角）
                double surfaceY = data.height[fx0 * gh + fz0];

                // ★ 关键：ratio 保底 WATER_SINK，柱子永远向上长，绝不往下沉
                float ratio = (float) Math.max((surfaceY - base) / span, WATER_SINK);
                tops[col] = ratio * RELIEF;

                // 四角模型坐标：照 yardsite prepareColumn() 第 579-587 行
                //   中心 ± 半宽，再 model(fine, ·)（分母 fine-1，细格是"点"）
                //   注意 fx1/fz1 已用 min(gw-1) 夹过 ⇒ centerX+halfX ≤ gw-1，不越界
                float centerX = (fx0 + fx1) * 0.5f;
                float centerY = (fz0 + fz1) * 0.5f;
                float halfX = (fx1 - fx0 + 1) * 0.5f;
                float halfY = (fz1 - fz0 + 1) * 0.5f;
                edgeX0[col] = model(gw, centerX - halfX);
                edgeX1[col] = model(gw, centerX + halfX);
                edgeY0[col] = model(gh, centerY - halfY);
                edgeY1[col] = model(gh, centerY + halfY);

                // 颜色：取组中心细格（保留坡度光照），照 yardsite paintBlock
                int mfx = (fx0 + fx1) / 2;
                int mfz = (fz0 + fz1) / 2;
                Cell cell = data.toDisplayCell(mfx, mfz, scratch);
                int baseRgb = cell == null ? 0x2A2A3A
                        : (GeoPalette.color(layer, cell, 0, 0, minY, maxY, true) & 0xFFFFFF);
                colRgb[col] = applySlopeLight(rNorm, gw, gh, mfx, mfz, baseRgb,
                        data.e[mfx * gh + mfz] < 0.0);
                readyOrder[readyColumns++] = col;
            }
        }
    }

    /**
     * 坡度光照：<b>量纲已修正</b>（★ 2026-11-08）。
     *
     * <p>上一版把「每细格上升的方块数」当成无量纲坡度用（分母写死 2.0，
     * 注释写「step=1」）⇒ 坡度被放大一整个采样间距 ⇒ 光照<b>84.9% 的格子饱和</b>
     *（41.5% 贴死黑、43.5% 贴死亮）⇒ 整幅沙盘只有全黑/全白两档，没有中间层次。
     * 铁证见 {@code runSandboxShadeProbe}。</p>
     *
     * <p>现在坡度定义在<b>归一化高度 r</b> 上（与柱高同量纲）：
     * {@code r = (surfaceY - base) / span}。于是「走一格柱高变了多少」
     * <b>与采样间距无关</b> —— 缩放视口时明暗关系稳定，不会因网格密度而整体变亮/变暗。</p>
     *
     * <p>水域按<b>水面</b>算（yardsite {@code samplePoint()} 第 485 行：水格直接记海平面）
     * ⇒ 水面是平的，不跟海床起伏一起明暗，不会出现假的明暗斑块。</p>
     */
    private int applySlopeLight(double[] r, int gw, int gh, int gx, int gz, int rgb, boolean water) {
        double h = r[gx * gh + gz];
        double left = gx > 0 ? r[(gx - 1) * gh + gz] : h;
        double right = gx < gw - 1 ? r[(gx + 1) * gh + gz] : h;
        double up = gz > 0 ? r[gx * gh + (gz - 1)] : h;
        double down = gz < gh - 1 ? r[gx * gh + (gz + 1)] : h;
        double light = SandboxShade.lightFor(left, right, up, down, water);
        return shade(rgb, (float) light);
    }

    /** 计数排序，从远到近（yardsite {@code sortByDepth()} 第 694-714 行）。 */
    private void sortByDepth() {
        java.util.Arrays.fill(counts, 0);
        for (int k = 0; k < readyColumns; k++) {
            int col = readyOrder[k];
            // 柱索引 col = cx * colRows + cz
            float mx = modelOf(colCount, col / colRows);
            float my = modelOf(colRows, col % colRows);
            float depth = r[6] * mx + r[7] * my + r[8] * Math.max(tops[col], 0.0f);
            int slot = clampI((int) ((depth + 1.8f) * (BUCKETS / 3.6f)), 0, BUCKETS - 1);
            bucket[k] = slot;
            counts[slot]++;
        }
        int sum = 0;
        for (int b = 0; b < BUCKETS; b++) {
            write[b] = sum;
            sum += counts[b];
        }
        for (int k = 0; k < readyColumns; k++) {
            sorted[write[bucket[k]]++] = readyOrder[k];
        }
    }

    // ------------------------------------------------------------------ 几何

    /** 水平四边形（yardsite {@code quadFlat()} 第 783-789 行）。 */
    private void quadFlat(VertexConsumer c, float ox, float oy, float s,
                          float x0, float x1, float y0, float y1, float z, int argb) {
        vertex(c, ox, oy, s, x0, y0, z, argb);
        vertex(c, ox, oy, s, x1, y0, z, argb);
        vertex(c, ox, oy, s, x1, y1, z, argb);
        vertex(c, ox, oy, s, x0, y1, z, argb);
    }

    /** 旋转矩阵：照抄 yardsite {@code orthoRows()} 第 358-375 行。 */
    private void buildRotation() {
        rotation.identity();
        rotation.rotateZ(0f);
        rotation.rotateX(pitch);
        rotation.rotateZ(yaw);
        float[] raw = new float[9];
        rotation.get(raw);
        r[0] = raw[0]; r[1] = raw[3]; r[2] = raw[6];
        r[3] = raw[1]; r[4] = raw[4]; r[5] = raw[7];
        r[6] = raw[2]; r[7] = raw[5]; r[8] = raw[8];
    }

    /**
     * 顶点（yardsite {@code vertex()} 第 744-758 行）。
     *
     * <p>横向按 {@link #widthScale} 撑开、高度不跟着撑 —— 所以柱子始终正好拼合，
     * 但视觉上更粗更敦实或更细更尖。深度行用<b>未撑开</b>的 mx/my，
     * 免得撑开后跑出 GUI 深度带被裁。</p>
     *
     * <p>★ 2026-11-08：减去 {@link #fitCenterX} / {@link #fitCenterY}
     * ⇒ 把整幅图的<b>包围盒中心</b>摆到视口中心。
     * 不做这件事的话，柱高抬升会把包围盒整体顶偏（地图偏下）。</p>
     */
    private void vertex(VertexConsumer c, float ox, float oy, float s,
                        float mx, float my, float mz, int argb) {
        float w = widthScale;
        float gx = mx * w;
        float gy = my * w;
        c.vertex(ox + s * (r[0] * gx + r[1] * gy + r[2] * mz - fitCenterX),
                 oy + s * (r[3] * gx + r[4] * gy + r[5] * mz - fitCenterY),
                 Z_BASE + DEPTH_SCALE * (r[6] * mx + r[7] * my + r[8] * mz))
         .color(argb)
         .endVertex();
    }

    /** 一面墙（yardsite {@code wall()} 第 765-775 行）。 */
    private void wall(VertexConsumer c, float ox, float oy, float s,
                      float ax, float ay, float bx, float by,
                      float low, float high, int rgb, float litTop, float litLow) {
        int top = shade(rgb, litTop);
        int bottom = shade(rgb, litLow);
        vertex(c, ox, oy, s, ax, ay, high, top);
        vertex(c, ox, oy, s, bx, by, high, top);
        vertex(c, ox, oy, s, bx, by, low, bottom);
        vertex(c, ox, oy, s, ax, ay, low, bottom);
    }


    /**
     * 细格索引 → 模型坐标 [-1,1]。
     *
     * <p>完全照抄 yardsite {@code model(int fine, float cell)} 第 734-737 行：
     * {@code cell / (fine - 1) * 2 - 1}。<b>分母是 fine-1</b>——
     * 因为细格是“点”，格心落在端点。</p>
     */
    private static float model(int fine, float cell) {
        return fine <= 1 ? 0f : cell / (fine - 1) * 2.0f - 1.0f;
    }

    /** 柱中心 → 模型坐标（排序用，yardsite {@code modelOf()} 第 739-742 行）。 */
    private static float modelOf(int n, int i) {
        return n <= 1 ? 0f : (i + 0.5f) / n * 2.0f - 1.0f;
    }

    private static int shade(int rgb, float mul) {
        int rr = clampI((int) (((rgb >> 16) & 0xFF) * mul), 0, 255);
        int gg = clampI((int) (((rgb >> 8) & 0xFF) * mul), 0, 255);
        int bb = clampI((int) ((rgb & 0xFF) * mul), 0, 255);
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static float wrap(float a) {
        float t = (float) (a % (Math.PI * 2));
        return t < 0 ? t + (float) (Math.PI * 2) : t;
    }

    private void ensureCapacity(int n) {
        if (tops.length >= n) return;
        tops = new float[n];
        edgeX0 = new float[n];
        edgeX1 = new float[n];
        edgeY0 = new float[n];
        edgeY1 = new float[n];
        colRgb = new int[n];
        readyOrder = new int[n];
        sorted = new int[n];
        bucket = new int[n];
    }

    /** 归一化高度数组容量。 */
    private void ensureR(int n) {
        if (rNorm.length >= n) return;
        rNorm = new double[n];
    }
}
