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
    /** 一根柱子长高 + 淡入的时长（毫秒，yardsite FADE_MS=520）。 */
    private static final int FADE_MS = 520;
    /** 淡入期间至少还有这么不透明（yardsite FADE_FLOOR=0.25）。 */
    private static final float FADE_FLOOR = 0.25f;
    /**
     * 侧墙的最小屏幕高度（像素）：低于它就不画。
     *
     * <p>亚像素高的墙不产生任何视觉信息，只会在每根柱子边缘留下混叠亮线
     * ⇒ 整幅呈密集条纹（LOD 细档实测就是这样丢掉地形形状的）。</p>
     */
    private static final float MIN_WALL_PX = 1.0f;
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
     * 缩放（yardsite {@code zoom} 第 151 行，默认 <b>2.0</b>，滚轮 0.6~8.0）。
     *
     * <p>★ 2026-11-08 二次修正（用户裁定）：恢复 yardsite 口径。
     * 上一轮我把它改成「整图铺满 = zoom 1.0」—— <b>方向改反了</b>：
     * yardsite 的 0.46·zoom(2.0)=0.92 近景构图是参考项目观感的组成部分
     * （柱子粗大立体），不是该绕开的坑。看全图用滚轮缩小。</p>
     */
    /**
     * 缩放（用户可调）。
     *
     * <p>★ 2026-11-08：默认取 {@link SandboxLod#BASE_ZOOM}（= level 1，中间档），
     * 让用户<b>既能推近也能拉远</b>。此前取最粗档 ⇒ 只能向内推，
     * 向外滚完全没反应（实机"滚动缩放无效"）。</p>
     */
    private float zoom = SandboxLod.BASE_ZOOM;
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
    /** 每柱本帧的淡入透明度 0..255（yardsite colAlpha）。 */
    private int[] colAlpha = new int[0];
    /** 每柱出生时刻（毫秒）—— 生长动画用；0 = 未采到。 */
    private long[] bornAtCol = new long[0];
    /** 每柱是否是水面（粗层水位高于地形）—— 诊断/hover 用。 */
    private boolean[] colIsWater = new boolean[0];
    /** 每柱的边缘渐隐系数（yardsite edgeFade；全屏地图用）。 */
    private float[] edgeFadePerCol = new float[0];
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

    /** 多数群系投票用的复用数组（容量按 perColumn² 增长）。 */
    private int[] voteIds = new int[16];
    private int[] voteCnts = new int[16];

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

    /**
     * 屏幕拖动 → <b>世界该移动多少方块</b>（照抄 yardsite {@code dragToCells()} 第 276-299 行）。
     *
     * <p>做法：先按屏幕缩放折回模型单位，再用当前旋转的<b>地面两行</b>反解地面两个轴，
     * 最后取反号（中心往反方向走，画面才跟着手走）—— 所以斜着拉也是顺的，
     * 不会"横一下竖一下"。</p>
     *
     * <p>为什么需要它：我们是<b>全屏单层地图</b>，拖动必须移动世界中心并重新采样；
     * 只平移屏幕像素会让地形边缘露出空白。参考项目之所以只用 dragPan，
     * 是因为它靠"切到区块精读层"来看别处。</p>
     *
     * @param dx,dy     屏幕位移（像素）
     * @param areaSize  视口边长（像素）
     * @param blockStep 每个细格代表多少方块（{@code SandboxLod.effectiveStep}）
     * @param out       输出 [dWorldX, dWorldZ]（方块，已取反号 = 中心该移动的量）
     */
    public void dragToBlocks(double dx, double dy, int areaSize, double blockStep,
                             float[] out) {
        // ★ scale 与 render() 保持同一口径（不乘 zoom，否则拖动灵敏度会随缩放漂移）
        float s = areaSize * 0.46f * SandboxLod.BASE_ZOOM;
        if (s <= 0.0f) {
            out[0] = 0f;
            out[1] = 0f;
            return;
        }
        // 屏幕 → 模型单位（先按 widthScale 还原横向撑开）
        float a = (float) (dx / s) / widthScale;
        float b = (float) (dy / s) / widthScale;
        // 地面两行：屏幕 = [r0 r1; r3 r4]·(模型 x,y)（高度项对水平拖动无贡献）
        float det = r[0] * r[4] - r[1] * r[3];
        float mx, my;
        if (Math.abs(det) < 1.0E-4f) {
            mx = a;
            my = b;
        } else {
            mx = (r[4] * a - r[1] * b) / det;
            my = (r[0] * b - r[3] * a) / det;
        }
        // 模型单位 → 方块：
        //   模型跨度 2（-1..+1）覆盖 <b>colCount 根柱子</b>，每根柱 = perColumn 个细格，
        //   每细格 = blockStep 方块
        //   ⇒ 每模型单位 = (colCount / 2) × perColumn × blockStep 方块
        //
        //   ★ 2026-11-08 修正：曾漏掉 perColumn，导致拖动灵敏度只有正确值的 1/2
        //   （实测拖整屏只移动 0.27× 视野，手感偏慢）。
        double blocksPerModel = (Math.max(1, colCount) * 0.5) * perColumn * blockStep;
        out[0] = (float) (-mx * blocksPerModel);
        out[1] = (float) (-my * blocksPerModel);
    }

    /**
     * 缩放下限/上限。
     *
     * <p>与 {@link SandboxLod#ZOOM_MIN} / {@link SandboxLod#ZOOM_MAX} <b>必须一致</b>：
     * LOD 的步长下限（每细格 ≥ 1 格方块）由 ZOOM_MAX 决定，
     * 若这里允许更大的 zoom，画面会继续放大而采样精度不再提升
     * （柱子被拉到超采样，看着"糊"而不是"更清晰"）。</p>
     */
    public static final float ZOOM_MIN = SandboxLod.ZOOM_MIN;
    public static final float ZOOM_MAX = SandboxLod.ZOOM_MAX;

    /** 缩放（yardsite {@code setZoom()} 的 0.6~8.0 已按 LOD 需求放宽到 32）。 */
    public void addZoom(float factor) {
        zoom = clamp(zoom * factor, ZOOM_MIN, ZOOM_MAX);
    }

    public void setZoom(float v) {
        zoom = clamp(v, ZOOM_MIN, ZOOM_MAX);
    }

    /** 柱子横向撑开倍数（yardsite {@code setWidthScale()} 第 319-321 行，限 0.5~2.0）。 */
    public void setWidthScale(float v) {
        widthScale = clamp(v, 0.5f, 2.0f);
    }

    public float widthScale() { return widthScale; }

    /** 复位视角（保留缩放层级之外的相机参数）。 */
    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = SandboxLod.BASE_ZOOM;   // ★ 与字段初值同源，避免"复位后掉回最粗档"
        panX = 0f;
        panY = 0f;
    }

    /**
     * 绘制沙盘。
     *
     * @param g        GUI 上下文
     * @param data     采样数据（细格）
     * @param layer    当前图层（连续→GeoPalette 色带；离散→群系本色）
     * @param minY/maxY 世界高度范围
     * @param areaX/areaY 区域左上角
     * @param areaSize    区域边长（像素）
     * @param span        竖向比例分母
     * @param seaLevel   海平面 Y（预览可配）
     * @param now        当前时刻（毫秒）—— 生长动画用
     */
    public void render(GuiGraphics g, TerrainFieldData data, GeoPalette.PreviewLayer layer,
                       int minY, int maxY, int areaX, int areaY, int areaSize, float span,
                       double seaLevel, long now) {
        if (data == null || data.gridW <= 0) return;
        buildRotation();

        // ★★ 2026-11-08【根本修正】：scale <b>不再乘 zoom</b>，改为"窗口正好铺满视口"。
        //
        //   <h2>上一版为什么错（用户实机："滚轮感觉只是柱子变大了"）</h2>
        //   <pre>
        //     scale  = areaSize * 0.46 * zoom      ← 随 zoom 线性放大
        //     window = GRID * step / zoom          ← 随 zoom 线性缩小
        //   </pre>
        //   两者<b>同比例抵消</b> ⇒ 构图几乎不变，柱子屏幕尺寸反而变大：
        //   实测 zoom 2.0→31.2 时，柱子从 12.9px 涨到 201.8px，
        //   而画面内容还是同一片地方 ⇒ 看起来"只是柱子变大"。
        //
        //   <h2>正确做法（地图软件 / RTS 相机）</h2>
        //   <p>scale 只由「窗口覆盖范围」决定，与 zoom 无关：</p>
        //   <pre>
        //     scale = areaSize / 窗口宽度（模型单位）= areaSize * 0.46 * BASE_ZOOM
        //   </pre>
        //   <p>⇒ 缩放只改<b>窗口</b>（世界范围），柱子的<b>屏幕尺寸恒定</b>，
        //   画面内容"推近/拉远" —— 这才是用户要的"像镜头推进"。</p>
        //
        //   <p>柱数恒定 128、柱宽恒定 ⇒ 每柱代表的方块变少 ⇒ <b>放大后画面更精细</b>
        //   （zoom 2.0 时每柱 31 格，zoom 31.2 时每柱 2 格）。</p>
        float scale = areaSize * 0.46f * SandboxLod.BASE_ZOOM;

        // ★ 屏幕平移（yardsite render() 第 407-408 行：{@code areaX + areaSize/2 + panX}）
        float originX = areaX + areaSize / 2.0f + panX;
        float originY = areaY + areaSize / 2.0f + panY;

        collect(data, layer, minY, maxY, span, seaLevel);
        applyGrowAnimation(now);
        sortByDepth();
        if (readyColumns == 0) return;

        VertexConsumer c = g.bufferSource().getBuffer(RenderType.gui());

        // ★ 底板（yardsite floor() 第 778-781 行 / 调用处第 414-416 行）：
        //   没有它柱子看着是悬空的。画在柱子之前（更远），柱子会盖住它。
        quadFlat(c, originX, originY, scale, -1.0f, 1.0f, -1.0f, 1.0f, -0.02f, 0xF2171A22);

        boolean seeEast = r[6] > 0.0f;
        boolean seeWest = r[6] < 0.0f;
        boolean seeSouth = r[7] > 0.0f;
        boolean seeNorth = r[7] < 0.0f;
        boolean seeTop = r[8] > 0.0f;

        int hoverCol = pickedColumn;

        for (int i = 0; i < readyColumns; i++) {
            int col = sorted[i];
            float top = tops[col];
            if (top < 0.0f) continue;                      // 未出生（生长动画未到）
            int alpha = colAlpha[col];
            if (alpha <= 0) continue;
            boolean hovered = col == hoverCol;
            int rgb = colRgb[col];
            float x0 = edgeX0[col], x1 = edgeX1[col];
            float y0 = edgeY0[col], y1 = edgeY1[col];

            if (seeTop) {
                int flat = rgb | (alpha << 24);
                vertex(c, originX, originY, scale, x0, y0, top, flat);
                vertex(c, originX, originY, scale, x1, y0, top, flat);
                vertex(c, originX, originY, scale, x1, y1, top, flat);
                vertex(c, originX, originY, scale, x0, y1, top, flat);
            }
            // 侧墙：只在邻居更低时画（yardsite 第 448-475 行）。
            // 柱索引 col = cx * colRows + cz ⇒ 东/西 = ±colRows，南/北 = ±1
            //
            // ★★ 2026-11-08【修"斜向纹理假影"】：高差<b>量化到 1 像素</b>后再判。
            //
            //   实机截图里地面上有大量斜向细线 —— 根因是<b>亚像素侧墙</b>：
            //   相邻柱高差只有零点几像素，但 `top > low` 仍然成立 ⇒
            //   每根柱都画一条 0.3px 的侧墙，整幅叠成密集斜纹。
            //
            //   之前用 minWall 门槛不够：门槛只比较高度差，而不同角度下
            //   同一高差在屏幕上的投影长度不同，总有部分柱子刚好越过门槛。
            //   ⇒ 改为<b>先把柱顶高度吸附到 minWall 的整数倍</b>，
            //   亚像素差被抹成 0，侧墙自然不画；跨过 1px 的台阶照常显示。
            float minWall = MIN_WALL_PX / Math.max(1e-3f, scale);
            int ci = col / colRows, cj = col % colRows;
            if (seeEast) {
                float low = (ci + 1 < colCount) ? Math.max(tops[col + colRows], 0f) : 0f;
                if (snapTop(top, minWall) > snapTop(low, minWall)) {
                    wall(c, originX, originY, scale, x1, y0, x1, y1, low, top, rgb, alpha, 0.82f, 0.60f);
                }
            }
            if (seeWest) {
                float low = ci > 0 ? Math.max(tops[col - colRows], 0f) : 0f;
                if (snapTop(top, minWall) > snapTop(low, minWall)) {
                    wall(c, originX, originY, scale, x0, y1, x0, y0, low, top, rgb, alpha, 0.56f, 0.40f);
                }
            }
            if (seeSouth) {
                float low = (cj + 1 < colRows) ? Math.max(tops[col + 1], 0f) : 0f;
                if (snapTop(top, minWall) > snapTop(low, minWall)) {
                    wall(c, originX, originY, scale, x0, y1, x1, y1, low, top, rgb, alpha, 0.70f, 0.52f);
                }
            }
            if (seeNorth) {
                float low = cj > 0 ? Math.max(tops[col - 1], 0f) : 0f;
                if (snapTop(top, minWall) > snapTop(low, minWall)) {
                    wall(c, originX, originY, scale, x1, y0, x0, y0, low, top, rgb, alpha, 0.46f, 0.34f);
                }
            }
        }

        // ★ hover 高亮：叠在所有柱子之上（参考项目第 497-507 行的 overlay 层）
        //   —— 必须在柱子之后提交，否则会被后画的柱子盖住。
        hoverOverlay(c, hoverCol, originX, originY, scale);

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
     * hover 高亮：<b>照抄</b>参考项目 render() 第 503-507 行 ——
     * 在柱子之后用<b>半透明白</b>（{@code 0x4C}）在顶面稍上方再铺一个四边形。
     *
     * <p>为什么不像上一版那样直接把顶面颜色调亮：那样会破坏真实配色
     * （用户看到的颜色不再是该群系本色），而叠白只是"加亮一层"，
     * 颜色关系仍可读。</p>
     */
    private void hoverOverlay(VertexConsumer c, int col, float ox, float oy, float s) {
        if (col < 0 || col >= colCount * colRows) return;
        float top = tops[col];
        if (top < 0f) return;
        quadFlat(c, ox, oy, s, edgeX0[col], edgeX1[col], edgeY0[col], edgeY1[col],
                top + 0.006f, 0x4CFFFFFF);
    }

    /**
     * 模型坐标 → 柱索引（hover 拾取辅助；与排序/采样的列主序一致）。
     */
    private int columnOfModel(float mx, float my) {
        int cx = (int) Math.floor((mx + 1.0f) * 0.5f * colCount);
        int cz = (int) Math.floor((my + 1.0f) * 0.5f * colRows);
        if (cx < 0 || cx >= colCount || cz < 0 || cz >= colRows) return -1;
        return cx * colRows + cz;
    }

    /** 点选视线推进步长（yardsite MARCH_STEP=0.007，第 66 行）。 */
    private static final float MARCH_STEP = 0.007f;
    /** pick 输出：命中的柱索引（-1 = 未命中）。 */
    private int pickedColumn = -1;

    /**
     * 从鼠标位置沿视线往模型里走，第一个撞上的柱子就是玩家看到的这一格
     * （<b>照抄</b> yardsite {@code pick()} 第 1002-1042 行）。
     *
     * <p>命中按<b>格子</b>判定（跟柱子画多宽无关），宽度调成细的时候点缝里也能选中。
     * 输出经 {@link #pickedColumn()} 读取。</p>
     *
     * @param guiX/guiY 鼠标屏幕坐标
     */
    public void pick(double guiX, double guiY, int areaX, int areaY, int areaSize) {
        pickedColumn = -1;
        // ★ 与 render() 同一口径（scale 不随 zoom）
        float scale = areaSize * 0.46f * SandboxLod.BASE_ZOOM;
        if (scale <= 0.0f || readyColumns == 0) return;
        float w = widthScale;
        float a = (float) ((guiX - (areaX + areaSize / 2.0f + panX)) / scale);
        float b = (float) ((guiY - (areaY + areaSize / 2.0f + panY)) / scale);
        // 屏幕点 = 中心 + 缩放·M·(横向撑开后的模型点)：先按撑开空间反算，再把横向除回来
        float qx = r[0] * a + r[3] * b;
        float qy = r[1] * a + r[4] * b;
        float qz = r[2] * a + r[5] * b;
        // 深度行 = 视线方向（单位长度），t 越大越靠近观察者
        float dx = r[6], dy = r[7], dz = r[8];
        for (float t = 3.2f; t > -3.2f; t -= MARCH_STEP) {
            float mx = (qx + dx * t) / w;
            float my = (qy + dy * t) / w;
            // 最外圈柱子的边超出地面正方形半格，边界放宽一点才点得到
            if (mx < -1.02f || mx > 1.02f || my < -1.02f || my > 1.02f) continue;
            float mz = qz + dz * t;
            if (mz < 0.0f) continue;
            int col = columnOfModel(mx, my);
            if (col < 0) continue;
            float top = tops[col];
            if (top >= 0.0f && mz <= top) {
                pickedColumn = col;
                return;
            }
        }
    }

    /** 最近一次 {@link #pick} 的命中柱索引（-1 = 未命中）。 */
    public int pickedColumn() { return pickedColumn; }

    /** 命中柱的 X 柱坐标（-1 = 未命中）。 */
    public int pickedColX() { return pickedColumn < 0 ? -1 : pickedColumn / colRows; }

    /** 命中柱的 Z 柱坐标（-1 = 未命中）。 */
    public int pickedColZ() { return pickedColumn < 0 ? -1 : pickedColumn % colRows; }

    /** 命中柱的顶面高度（模型单位；0 = 未命中）。 */
    public float pickedTopY() {
        return pickedColumn >= 0 && pickedColumn < tops.length ? tops[pickedColumn] : 0f;
    }

    /** 清除 hover（鼠标离开沙盘区时调用）。 */
    public void clearPick() { pickedColumn = -1; }

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
    /**
     * 每根柱子盖住几个细格（每边）—— <b>可变</b>，由 {@link SandboxLod#perColumnFor} 按视口计算。
     *
     * <p>★ 2026-11-08：从常量 2 改为动态。细格按像素提到 450 后，
     * 若仍按 2 聚合 ⇒ 225 根柱、每根 4px ⇒ 每柱一圈侧墙，叠成密集涟漪纹理。
     * 现在按"目标柱数 = 视口/13px"反推聚合系数，柱子保持约 12px。</p>
     */
    private int perColumn = 2;

    /** 本图网格宽（细格）。 */
    private int cols = 0;
    private int rows = 0;
    /** 本图柱数（每边）= 细格数 / {@link #perColumn}。 */
    private int colCount = 0;
    /** 本图柱行数（Z 方向）。 */
    private int colRows = 0;


    // ------------------------------------------------------------------ 采集

    /**
     * 采集每根柱子：柱高（真实 Y 归一）、四角、颜色（含坡度光照）。
     *
     * <p>细格 {@code gridW×gridH} 按 {@link #perColumn}² 聚合成柱子 ——
     * 每根柱子取该组<b>多数群系</b>的细格（yardsite {@code majorityCell} 第 588 行语义：
     * 2×2 里占多数的那个；并列取先遇到）、柱高取组左上角细格
     * （yardsite {@code surfaceAtCell(s0, f0)} 第 596 行）。</p>
     *
     * <p>★ 2026-11-08 修正：颜色重新走 <b>图层</b>（{@link SandboxPalette#colorForLayer}）。
     * 上一版固定用群系本色，等价于砍掉原有图层能力（交接 §4.1 列为必须保留）。
     * 现在连续图层走 GeoPalette 色带（与平面窗口一致），离散图层走本色。</p>
     *
     * @param layer    当前图层
     * @param minY/maxY 世界高度范围（GeoPalette 需要）
     * @param seaLevel 海平面 Y（预览可配，不能写死 63）
     */
    private void collect(TerrainFieldData data, GeoPalette.PreviewLayer layer,
                         int minY, int maxY, float spanIn, double seaLevel) {
        int gw = data.gridW, gh = data.gridH;
        cols = gw;
        rows = gh;
        int fine = gw * gh;
        colCount = Math.max(1, (gw + perColumn - 1) / perColumn);
        colRows = Math.max(1, (gh + perColumn - 1) / perColumn);
        int pillars = colCount * colRows;
        ensureCapacity(Math.max(fine, pillars));
        readyColumns = 0;

        // ---- 1) 真实高度 → 归一化基准（yardsite collect() 第 660/677-687 行）----
        //   基准面 = 海平面 - 4；ratio = max((surface - base)/span, WATER_SINK)；tops = ratio*RELIEF
        double base = seaLevel - 4.0;
        double span = Math.max(20.0, spanIn);

        // ---- 2) 归一化高度 r（供坡度光照用，与柱高同量纲）----
        //   水域用海平面而不是海床高度 ⇒ 水面平，无假明暗斑块
        ensureR(fine);
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                int i = gx * gh + gz;
                boolean water = data.e[i] < 0.0;
                double surface = water ? seaLevel : data.height[i];
                rNorm[i] = (surface - base) / span;
            }
        }

        // ---- 2b) ★ 光照增益：按<b>量程</b>标定（2026-11-08 修"推近条纹"）----
        //
        //   上一版按"本帧可见窗口"的坡度分布统计（SandboxShade.adaptiveGain）。
        //   LOD 推近后窗口只有 144 格、地形近乎平坦 ⇒ P99.5 趋近 0
        //   ⇒ gain 被推到上限 60 ⇒ 2.5% 的高度差就产生 1.0 亮度差
        //   ⇒ 噪声放大成斜向条纹（实机与离线出图均可见，gain 日志 40.67）。
        //
        //   现改为 SandboxShade.calibratedGain(gridCells)：按数据的绝对量程解析推出，
        //   只依赖网格密度、不依赖窗口内实际地形 ⇒ 任何缩放下 gain 稳定。
        double lightGain = SandboxShade.calibratedGain(gw);

        // ---- 3) 逐柱（每柱聚合 PER_COLUMN² 个细格）----
        for (int cx = 0; cx < colCount; cx++) {
            for (int cz = 0; cz < colRows; cz++) {
                int col = cx * colRows + cz;

                int fx0 = cx * perColumn, fz0 = cz * perColumn;
                int fx1 = Math.min(gw - 1, fx0 + perColumn - 1);
                int fz1 = Math.min(gh - 1, fz0 + perColumn - 1);

                // 柱高取组内左上角（yardsite surfaceAtCell(s0, f0)）
                double surfaceY = data.height[fx0 * gh + fz0];

                // ★ 粗层水位（河道/湖面）：水面高于地形时，柱顶抬到<b>水面</b>。
                //
                //   这是"河道水面"的实现方式 —— 不是把地形抬高，而是<b>柱顶 = 水面</b>：
                //   河道处的柱子涨到水面高度、涂水色，看起来就是一条水面；
                //   旁边的陆地柱子不变 ⇒ 水面自然贴着地形，不需要单独的薄板几何。
                //   （粗层 step=4，所以水面是骨架级判断，见 SandboxWaterLookup 的精度声明。）
                int i0 = fx0 * gh + fz0;
                double wY = data.waterY[i0];
                boolean hasWater = !Double.isNaN(wY) && wY > surfaceY + 0.01;
                double topY = hasWater ? wY : surfaceY;

                // ★ ratio 保底 WATER_SINK，柱子永远向上长，绝不往下沉
                float ratio = (float) Math.max((topY - base) / span, WATER_SINK);
                tops[col] = ratio * RELIEF;
                colIsWater[col] = hasWater;

                // 四角模型坐标：用<b>格子边界</b>映射（除以 fine，不是 fine-1）
                //
                // ★★ 2026-11-08【修"横向细线"】：参考项目的 model() 分母是 fine-1，
                //   因为它把细格当"点"（柱心落在端点）。但我们是<b>铺满</b>的方块盘面，
                //   细格是"格子"（有面积）⇒ 必须用 fine 作分母，
                //   否则柱 i 的右边与柱 i+1 的左边不相邻，中间露出 1 整格缝隙
                //   （实机截图里的横向细线就是这个缝）。
                //   用 fine 后：柱 i 覆盖 [i/fine, (i+1)/fine]，相邻柱<b>严格相接</b>。
                edgeX0[col] = modelEdge(gw, fx0);
                edgeX1[col] = modelEdge(gw, fx1 + 1);
                edgeY0[col] = modelEdge(gh, fz0);
                edgeY1[col] = modelEdge(gh, fz1 + 1);

                // ---- 颜色：复用既有 35 项群系本色 + 水面渐变 + jitter ----
                //   多数群系细格：组内 biomeClassId 出现最多者（yardsite majorityCell 语义）
                //
                //   ⚠ 数组容量必须按 perColumn² 分配：perColumn 现在是自适应的（2~16），
                //   一组最多 256 个细格。曾固定 new int[4] ⇒ perColumn=6 时
                //   ArrayIndexOutOfBoundsException: Index 4 out of bounds for length 4
                //   （实机崩溃，latest.log 已取证）。
                int cellCount = (fx1 - fx0 + 1) * (fz1 - fz0 + 1);
                if (voteIds.length < cellCount) {
                    voteIds = new int[cellCount];
                    voteCnts = new int[cellCount];
                }
                int n = 0;
                for (int dx = 0; dx <= fx1 - fx0; dx++) {
                    for (int dz = 0; dz <= fz1 - fz0; dz++) {
                        int ii = (fx0 + dx) * gh + (fz0 + dz);
                        int b = data.biomeClassId[ii];
                        int found = -1;
                        for (int k = 0; k < n; k++) if (voteIds[k] == b) { found = k; break; }
                        if (found < 0) { voteIds[n] = b; voteCnts[n] = 1; n++; }
                        else voteCnts[found]++;
                    }
                }
                int majority = -1, bestCnt = 0;
                for (int k = 0; k < n; k++) {
                    if (voteCnts[k] > bestCnt) { bestCnt = voteCnts[k]; majority = voteIds[k]; }
                }
                // 取该组第一个匹配多数 id 的细格（保留光照的空间细节）
                int mfx = fx0, mfz = fz0;
                boolean picked = false;
                for (int dx = 0; dx <= fx1 - fx0 && !picked; dx++) {
                    for (int dz = 0; dz <= fz1 - fz0; dz++) {
                        int ii = (fx0 + dx) * gh + (fz0 + dz);
                        if (data.biomeClassId[ii] == majority) { mfx = fx0 + dx; mfz = fz0 + dz; picked = true; break; }
                    }
                }

                // 坡度光照（量纲已修正，SandboxShade 是渲染器与探针共用的唯一实现）
                int i = mfx * gh + mfz;
                boolean water = data.e[i] < 0.0;
                double light = slopeLightAt(gw, gh, mfx, mfz, water, lightGain);

                // 该柱还没采到（左上角细格无数据）→ 不画（生长动画的"未出生"态）
                long born = data.bornAt[fx0 * gh + fz0];
                if (born == 0L) {
                    tops[col] = -1.0f;
                    colAlpha[col] = 0;
                    colRgb[col] = 0;
                    bornAtCol[col] = 0L;
                    readyOrder[readyColumns++] = col;
                    continue;
                }
                bornAtCol[col] = born;
                double s2 = water ? seaLevel : data.height[i];
                // ★ 按图层分流上色（连续→GeoPalette 色带；离散→35 项群系本色）
                Cell cell = data.toDisplayCell(mfx, mfz, scratch);
                int rgb = SandboxPalette.colorForLayer(layer, cell, majority, light, s2, seaLevel,
                        data.snow[i], minY, maxY);
                // ★ 河道/湖面：无论当前图层是什么，水面都用<b>水深渐变蓝</b>覆盖
                //   —— 否则在高程/温度图层下，河面会被涂成"陆地色"而看不见。
                if (hasWater) {
                    rgb = SandboxPalette.waterColor(topY, seaLevel);
                }
                // 光照最后乘（与参考项目 colorFor 的 scale(rgb, light) 同位置）
                colRgb[col] = SandboxPalette.scale(rgb, light);
                colAlpha[col] = 0xFF;
                // 边界淡入的起始系数（全屏地图没有边框，边缘要"融进"背景）
                edgeFadePerCol[col] = edgeFade(cx, cz);
                readyOrder[readyColumns++] = col;
            }
        }
    }

    /**
     * 柱高吸附到 {@code step} 的整数倍（用于消除亚像素侧墙）。
     *
     * <p>为什么不是直接判 {@code top - low > minWall}：那样"刚好越过门槛"的柱子
     * 仍然会画一条 <b>1px 高的侧墙</b>，而它的邻居不画 ⇒ 一疏一密，
     * 在斜视角下叠成<b>斜向纹理</b>（实机截图可见）。
     * 吸附后：同一段台阶要么整体画、要么整体不画，不会出现交错细线。</p>
     */
    private static float snapTop(float v, float step) {
        if (step <= 0f) return v;
        return Math.round(v / step) * step;
    }

    /**
     * 该柱的<b>径向</b>边缘渐隐 + 圆形裁剪（0 = 不画，1 = 完全不透明）。
     *
     * <h2>★ 这是参考项目最显眼的视觉特征（2026-11-08）</h2>
     * <p>参考图是<b>圆形盘面</b>（浮岛感），不是方形。源码依据
     * {@code ChunkSitePlanner} 第 296-301 行：</p>
     * <pre>
     *   double dist = sqrt(dx² + dz²);              // 到中心距离（格）
     *   inside[index] = dist <= RADIUS + 0.5;       // 圆外不画
     *   edgeFadeTable[index] = clamp((RADIUS + 0.5 - dist) / FADE_RING, 0, 1);
     * </pre>
     * <p>⇒ 圆盘内 + 外圈 {@code FADE_RING} 格渐隐。我此前做成了方形 + 4 边渐隐，
     * 形态就完全不同。</p>
     *
     * <p>我们的实现：按<b>柱坐标到中心的归一化距离</b>算（与渲染的模型坐标一致），
     * 圆外返回 0（跳过绘制），圆内边缘 ±{@value #EDGE_FADE_RING} 柱渐隐。</p>
     *
     * @param ci,cj 柱坐标
     */
    private float edgeFade(int ci, int cj) {
        if (!edgeFadeEnabled) return 1.0f;
        // 归一化到 [-1,1] 的柱中心（与 modelOf 一致），圆心 = (0,0)
        float mx = modelOf(colCount, ci);
        float my = modelOf(colRows, cj);
        double dist = Math.sqrt(mx * mx + my * my);
        // ★ 渐隐宽度按<b>柱数</b>算（参考项目 FADE_RING=7 格）：
        //   之前写死 0.06（模型单位）在小柱数时会变成十几柱宽 ⇒ 边缘一层层碎块。
        //   现在 7 根柱宽，与参考项目一致。
        double ring = Math.max(1e-6, 7.0 / Math.max(1, colCount - 1) * 2.0);
        if (dist > EDGE_FADE_RADIUS) return 0f;      // 圆外：不画
        double t = (EDGE_FADE_RADIUS - dist) / ring;
        if (t >= 1.0) return 1.0f;
        return (float) (t * t * (3.0 - 2.0 * t));
    }

    /** 圆盘半径（模型坐标，1.0 = 方框边中点）。 */
    private static final double EDGE_FADE_RADIUS = 0.97;
    /** 是否启用圆形盘面 + 边缘渐隐。 */
    private boolean edgeFadeEnabled = true;

    /** 开关圆盘/渐隐（需要方形硬边时关）。 */
    public void setEdgeFade(boolean enabled) { this.edgeFadeEnabled = enabled; }


    /**
     * 设置每柱聚合的细格数（由 {@link SandboxLod#perColumnFor} 按视口算好）。
     *
     * <p>细格数固定、聚合系数变大 ⇒ 柱子数变少、每柱变粗 ⇒ 视觉尺寸稳定。</p>
     */
    public void setPerColumn(int per) {
        this.perColumn = Math.max(2, Math.min(16, per));
    }

    /** 一格的坡度光照（从 rNorm 数组取邻差，用自适应增益）。 */
    private double slopeLightAt(int gw, int gh, int gx, int gz, boolean water, double gain) {
        double h = rNorm[gx * gh + gz];
        double left = gx > 0 ? rNorm[(gx - 1) * gh + gz] : h;
        double right = gx < gw - 1 ? rNorm[(gx + 1) * gh + gz] : h;
        double up = gz > 0 ? rNorm[gx * gh + (gz - 1)] : h;
        double down = gz < gh - 1 ? rNorm[gx * gh + (gz + 1)] : h;
        return SandboxShade.lightFor(left, right, up, down, water, gain);
    }

    /**
     * 逐柱生长动画（★ 2026-11-08 P0-3，yardsite collect() 第 683-690 行）：
     * 每柱按出生时刻算 {@code t=(now-born)/520ms}，柱高 {@code ×smooth(t)}、
     * alpha 从 25% 淡入 —— 中心向外一圈圈长出来。
     */
    private void applyGrowAnimation(long now) {
        for (int k = 0; k < readyColumns; k++) {
            int col = readyOrder[k];
            float top = tops[col];
            if (top < 0.0f) continue;                     // 未采到，保持不画
            long born = bornAtCol[col];
            float t = clamp((now - born) / (float) FADE_MS, 0.0f, 1.0f);
            float s = t * t * (3.0f - 2.0f * t);          // yardsite smooth(t)
            tops[col] = top * s;
            // 参考项目 collect() 第 689-690 行：alpha = 255 * (FADE_FLOOR + (1-FADE_FLOOR)*t) * edgeFade
            float a = (FADE_FLOOR + (1.0f - FADE_FLOOR) * s) * edgeFadePerCol[col];
            colAlpha[col] = (int) (255.0f * a);
        }
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
     */
    private void vertex(VertexConsumer c, float ox, float oy, float s,
                        float mx, float my, float mz, int argb) {
        float w = widthScale;
        float gx = mx * w;
        float gy = my * w;
        c.vertex(ox + s * (r[0] * gx + r[1] * gy + r[2] * mz),
                 oy + s * (r[3] * gx + r[4] * gy + r[5] * mz),
                 Z_BASE + DEPTH_SCALE * (r[6] * mx + r[7] * my + r[8] * mz))
         .color(argb)
         .endVertex();
    }

    /** 一面墙（yardsite {@code wall()} 第 765-775 行，带 alpha）。 */
    private void wall(VertexConsumer c, float ox, float oy, float s,
                      float ax, float ay, float bx, float by,
                      float low, float high, int rgb, int alpha, float litTop, float litLow) {
        int top = (shade(rgb, litTop) & 0xFFFFFF) | (alpha << 24);
        int bottom = (shade(rgb, litLow) & 0xFFFFFF) | (alpha << 24);
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

    /**
     * 细格<b>边界</b>序号 → 模型坐标（柱子铺满用）。
     *
     * <p>{@code fine} 个细格把 [-1,1] 均分为 {@code fine} 段，
     * 第 {@code k} 条边界在 {@code -1 + 2k/fine}。相邻柱共享边界 ⇒ <b>无缝隙</b>。</p>
     *
     * <p>与参考项目的 {@code model(fine-1)} 不同：那是"细格当点"（柱心在端点），
     * 我们是"细格当格子"（有面积）—— 混用会露出整格宽的缝（实机横向细线）。</p>
     */
    private static float modelEdge(int fine, int k) {
        return fine <= 0 ? 0f : -1.0f + 2.0f * k / fine;
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
        colAlpha = new int[n];
        bornAtCol = new long[n];
        colIsWater = new boolean[n];
        edgeFadePerCol = new float[n];
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
