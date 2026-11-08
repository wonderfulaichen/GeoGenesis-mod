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
    private float zoom = 2.0f;
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
        float s = areaSize * 0.46f * zoom;
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
        //   模型跨度 2（-1..+1）覆盖 <b>colCount 根柱子</b>，每根柱 = PER_COLUMN 个细格，
        //   每细格 = blockStep 方块
        //   ⇒ 每模型单位 = (colCount / 2) × PER_COLUMN × blockStep 方块
        //
        //   ★ 2026-11-08 修正：曾漏掉 PER_COLUMN，导致拖动灵敏度只有正确值的 1/2
        //   （实测拖整屏只移动 0.27× 视野，手感偏慢）。
        double blocksPerModel = (Math.max(1, colCount) * 0.5) * PER_COLUMN * blockStep;
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

    /** 复位（yardsite {@code resetView()} 第 248-255 行）。 */
    public void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 2.0f;
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

        // ★ 缩放：照抄 yardsite scaleOf() 第 568-570 行。
        float scale = areaSize * 0.46f * zoom;

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
                int flat = hovered ? blendHighlight(rgb, alpha) : (rgb | (alpha << 24));
                vertex(c, originX, originY, scale, x0, y0, top, flat);
                vertex(c, originX, originY, scale, x1, y0, top, flat);
                vertex(c, originX, originY, scale, x1, y1, top, flat);
                vertex(c, originX, originY, scale, x0, y1, top, flat);
            }
            // 侧墙：只在邻居更低时画（yardsite 第 448-475 行）。
            // 柱索引 col = cx * colRows + cz ⇒ 东/西 = ±colRows，南/北 = ±1
            // 侧墙：只在邻居更低时画（yardsite 第 448-475 行）。
            //
            // ★ 可见性门槛（2026-11-08）：高度差换算到屏幕后不足 MIN_WALL_PX 时**不画**。
            //   否则 LOD 细档（相邻柱高差 <1 像素）会给每根柱子都画一条亚像素侧墙，
            //   整幅呈密集条纹/鳞片 —— 实测就是这样"看不出地形"的。
            //   门槛按当前 scale 算：1 像素高的墙不产生任何视觉信息，只会混叠。
            float minWall = MIN_WALL_PX / Math.max(1e-3f, scale);
            int ci = col / colRows, cj = col % colRows;
            if (seeEast) {
                float low = (ci + 1 < colCount) ? Math.max(tops[col + colRows], 0f) : 0f;
                if (top - low > minWall) wall(c, originX, originY, scale, x1, y0, x1, y1, low, top, rgb, alpha, 0.82f, 0.60f);
            }
            if (seeWest) {
                float low = ci > 0 ? Math.max(tops[col - colRows], 0f) : 0f;
                if (top - low > minWall) wall(c, originX, originY, scale, x0, y1, x0, y0, low, top, rgb, alpha, 0.56f, 0.40f);
            }
            if (seeSouth) {
                float low = (cj + 1 < colRows) ? Math.max(tops[col + 1], 0f) : 0f;
                if (top - low > minWall) wall(c, originX, originY, scale, x0, y1, x1, y1, low, top, rgb, alpha, 0.70f, 0.52f);
            }
            if (seeNorth) {
                float low = cj > 0 ? Math.max(tops[col - 1], 0f) : 0f;
                if (top - low > minWall) wall(c, originX, originY, scale, x1, y0, x0, y0, low, top, rgb, alpha, 0.46f, 0.34f);
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
     * hover 提亮：顶面混一层白（yardsite 第 476-484 行 highlight 同款手法）。
     */
    private static int blendHighlight(int rgb, int alpha) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 51);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + 51);
        int b = Math.min(255, (rgb & 0xFF) + 51);
        return ((r << 16) | (g << 8) | b) | (alpha << 24);
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
        float scale = areaSize * 0.46f * zoom;
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
        colCount = Math.max(1, (gw + PER_COLUMN - 1) / PER_COLUMN);
        colRows = Math.max(1, (gh + PER_COLUMN - 1) / PER_COLUMN);
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

        // ---- 2b) ★ 自适应光照增益（LOD 各档窗口不同，固定增益会出棋盘格纹）----
        //   采样坡度绝对值，取 P90 → 映射到亮度差 0.45（见 SandboxShade.adaptiveGain）
        double[] slopes = new double[fine];
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                double h0 = rNorm[gx * gh + gz];
                double l = gx > 0 ? rNorm[(gx - 1) * gh + gz] : h0;
                double rt = gx < gw - 1 ? rNorm[(gx + 1) * gh + gz] : h0;
                double up = gz > 0 ? rNorm[gx * gh + (gz - 1)] : h0;
                double dn = gz < gh - 1 ? rNorm[gx * gh + (gz + 1)] : h0;
                slopes[gx * gh + gz] = SandboxShade.absSlope(l, rt, up, dn);
            }
        }
        double lightGain = SandboxShade.adaptiveGain(slopes);

        // ---- 3) 逐柱（每柱聚合 PER_COLUMN² 个细格）----
        for (int cx = 0; cx < colCount; cx++) {
            for (int cz = 0; cz < colRows; cz++) {
                int col = cx * colRows + cz;

                int fx0 = cx * PER_COLUMN, fz0 = cz * PER_COLUMN;
                int fx1 = Math.min(gw - 1, fx0 + PER_COLUMN - 1);
                int fz1 = Math.min(gh - 1, fz0 + PER_COLUMN - 1);

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

                // 四角模型坐标：照 yardsite prepareColumn() 第 579-587 行
                float centerX = (fx0 + fx1) * 0.5f;
                float centerY = (fz0 + fz1) * 0.5f;
                float halfX = (fx1 - fx0 + 1) * 0.5f;
                float halfY = (fz1 - fz0 + 1) * 0.5f;
                edgeX0[col] = model(gw, centerX - halfX);
                edgeX1[col] = model(gw, centerX + halfX);
                edgeY0[col] = model(gh, centerY - halfY);
                edgeY1[col] = model(gh, centerY + halfY);

                // ---- 颜色（SandboxPalette：群系本色 + 水深渐变 + jitter）----
                //   多数群系细格：2×2 里 biomeId 出现最多者；并列取先遇到者
                //   （yardsite majorityCell 语义，第 588 行）
                int[] ids = new int[4];
                int[] cnts = new int[4];
                int n = 0;
                for (int dx = 0; dx <= fx1 - fx0; dx++) {
                    for (int dz = 0; dz <= fz1 - fz0; dz++) {
                        int ii = (fx0 + dx) * gh + (fz0 + dz);
                        int b = data.biomeId[ii];
                        int found = -1;
                        for (int k = 0; k < n; k++) if (ids[k] == b) { found = k; break; }
                        if (found < 0) { ids[n] = b; cnts[n] = 1; n++; }
                        else cnts[found]++;
                    }
                }
                int majority = -1, bestCnt = 0;
                for (int k = 0; k < n; k++) {
                    if (cnts[k] > bestCnt) { bestCnt = cnts[k]; majority = ids[k]; }
                }
                // 取该组第一个匹配多数 id 的细格（保留光照的空间细节）
                int mfx = fx0, mfz = fz0;
                boolean picked = false;
                for (int dx = 0; dx <= fx1 - fx0 && !picked; dx++) {
                    for (int dz = 0; dz <= fz1 - fz0; dz++) {
                        int ii = (fx0 + dx) * gh + (fz0 + dz);
                        if (data.biomeId[ii] == majority) { mfx = fx0 + dx; mfz = fz0 + dz; picked = true; break; }
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
                // ★ 按图层分流上色（连续→GeoPalette 色带；离散→群系本色）
                Cell cell = data.toDisplayCell(mfx, mfz, scratch);
                int rgb = SandboxPalette.colorForLayer(layer, cell, light, s2, seaLevel,
                        data.snow[i], minY, maxY);
                // ★ 河道/湖面：无论当前图层是什么，水面都用<b>水深渐变蓝</b>覆盖
                //   —— 否则在高程/温度图层下，河面会被涂成"陆地色"而看不见。
                //   与 BIOME 图层的处理一致：水面永远是水色（可读出"能不能过"）。
                if (hasWater) {
                    rgb = SandboxPalette.waterColor(topY, seaLevel, light);
                }
                colRgb[col] = rgb;
                colAlpha[col] = 0xFF;
                // 边界淡入的起始系数（全屏地图没有边框，边缘要"融进"背景）
                edgeFadePerCol[col] = edgeFade(cx, cz);
                readyOrder[readyColumns++] = col;
            }
        }
    }

    /**
     * 该柱的边缘渐隐系数（0 = 全透明，1 = 完全不透明）。
     *
     * <h2>参考项目 vs 我们</h2>
     * <p>参考项目大沙盘的 {@code edgeFade()} <b>恒返回 1.0</b>
     * （{@code SiteMapSampler} 第 344-346 行）—— 因为它在<b>大沙盘</b>里有硬边框，
     * 只有<b>区块精读</b>（圆形窗口）才用真正的渐隐。</p>
     *
     * <p>★ 2026-11-08：<b>我们不能照抄这个 1.0</b>。配置面板已是<b>全屏地图</b>
     * （地形铺满 (0,0,w,h)，没有边框、没有第二层视图可切），
     * 若边缘硬切，用户会看到一圈突兀的"断崖"，且拖动时新采样区突然弹出。
     * ⇒ 全屏地图需要渐隐，这是本项目形态决定的（用户明确指出：
     * "你忘记我们现在改成了全屏地图了啊，为什么拿旧设定当现在的设计"）。</p>
     *
     * <p>实现：按离最近的边有多少格线性淡出，最外 {@link #EDGE_FADE_CELLS} 个细格
     * 从 0 升到 1。这样地图像"融化"进背景，拖动时新区域也不会硬弹。</p>
     *
     * @param ci,cj 柱坐标（0..colCount-1）
     */
    private float edgeFade(int ci, int cj) {
        if (!edgeFadeEnabled) return 1.0f;
        int d = Math.min(Math.min(ci, colCount - 1 - ci), Math.min(cj, colRows - 1 - cj));
        if (d >= EDGE_FADE_CELLS) return 1.0f;
        float t = d / (float) EDGE_FADE_CELLS;
        // smoothstep：避免线性淡出在边界处仍有可见折线
        return t * t * (3.0f - 2.0f * t);
    }

    /** 边缘渐隐宽度（柱数）。 */
    private static final int EDGE_FADE_CELLS = 3;
    /** 是否启用边缘渐隐（全屏地图开启；窗口化时可由调用方关闭）。 */
    private boolean edgeFadeEnabled = true;

    /** 开关边缘渐隐（全屏地图开、需要硬边框时关）。 */
    public void setEdgeFade(boolean enabled) { this.edgeFadeEnabled = enabled; }

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
