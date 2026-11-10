package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.SandboxPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.TerrainViewMode;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * <b>沙盘光栅化器（实机）</b> —— 只负责「画」，不算任何几何。
 *
 * <h2>★ 算 / 画分离（本轮重构核心）</h2>
 * <pre>
 *   SandboxGeometry（纯函数，零 MC）   ← 投影/柱高/排序/光照/包围盒/拾取
 *            ↓ 同一份 Column[]
 *   ├─ SandboxGeometryProbe            → CPU 填充 → PNG（离线验证）
 *   └─ SandboxRasterizer（本类）        → 提交 vertex（实机）
 * </pre>
 *
 * <p>为什么这样分：此前所有数学与 OpenGL 混在 1132 行的 {@code SandboxRenderer} 里，
 * <b>无法离线验证</b>，只能实机试错 —— 六次翻车的根因全在这里。
 * 现在数学在 {@code SandboxGeometry}（探针 6 条判据全 PASS），
 * 本类只做「把已知四边形画出来」这件事。</p>
 *
 * <h2>照抄 yardsite 的关键细节（每条都有实机取证）</h2>
 * <ul>
 *   <li>用 {@code g.bufferSource().getBuffer(RenderType.gui())} + {@code g.flush()}
 *       —— 用 {@code Tesselator} 会<b>全黑</b>；</li>
 *   <li>按 {@code r[6]/r[7]/r[8]} 符号<b>只画 2 个侧面</b> —— 画 4 面会让遮挡错；</li>
 *   <li>侧墙只在 {@code topSnap > lowSnap} 时画 —— 否则每柱一圈，叠成筛子；
 *       <b>判据/顶面/侧墙必须用同一个吸附值</b>；</li>
 *   <li>{@code disableCull + depthMask(false)} + {@code flush} —— 遮挡靠提交顺序，
 *       深度写入会拒掉后画的字/按钮。</li>
 * </ul>
 */
public final class SandboxRasterizer {

    // ===== 视角状态（控制器持有，这里只读）=====
    private float yaw = SandboxGeometry.DEFAULT_YAW;
    private float pitch = SandboxGeometry.DEFAULT_PITCH;
    private float panX, panY;
    private float widthScale = 1.0f;
    /** 缩放档位（语义由 {@link com.geogenesis.client.preview.SandboxLod} 解释）。 */
    private float zoom = com.geogenesis.client.preview.SandboxLod.BASE_ZOOM;

    /** 上一帧的几何（hover 拾取要复用，避免重算）。 */
    private SandboxGeometry.Columns lastCols;
    private float[] lastR;
    private float lastScale, lastOriginX, lastOriginY;
    private int pickedColumn = -1;

    /** 点选视线推进步长（yardsite {@code MARCH_STEP=0.007}）。 */
    private static final float MARCH_STEP = 0.007f;

    /** 本渲染器对应的视图模式（★ v24：接口已删，但该方法仍是本类身份标识）。 */
    public TerrainViewMode mode() {
        return TerrainViewMode.SANDBOX;
    }

    // ----- 视角操作 -----

    /** 缩放档位（由 {@link com.geogenesis.client.preview.SandboxLod} 解释语义）。 */
    public float zoom() { return zoom; }

    public void setZoom(float v) {
        zoom = Math.max(com.geogenesis.client.preview.SandboxLod.ZOOM_MIN,
                Math.min(com.geogenesis.client.preview.SandboxLod.ZOOM_MAX, v));
    }

    /**
     * 拖动 → 世界位移（方块）。
     *
     * <p>屏幕位移按投影矩阵反解地面两轴 ⇒ 拖动方向与视觉一致，
     * 且<b>移动的是世界中心</b>（不是平移像素）—— 全屏单层没有"切层"退路，
     * 只平移像素会让地形边缘露出空白。</p>
     *
     * @param blockStep 每细格代表多少方块
     * @param out       输出 [dWorldX, dWorldZ]（中心该移动的量，已取反号）
     */
    public void dragToBlocks(double dx, double dy, int areaW, int areaH,
                             double blockStep, float[] out) {
        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        int colCount = lastCols != null ? lastCols.colCount : 1;
        // ★★ v29【修"拖动跟不上手 / 拖动量级错 2.85 倍"】★★
        //   这里原先调 {@code SandboxGeometry.dragToBlocks(...)} 的兼容重载，
        //   它内部自己算 {@code fitScaleWide(r, viewW, viewH)}；
        //   而 {@code draw()} 用的是 {@link #viewScale}（fillScale）。
        //   两套 scale 相差 <b>2.85 倍</b>（探针 runSandboxPanProbe 实测）
        //   ⇒ 拖动一格屏幕位移换算出的世界位移是应有值的 2.85 倍 ⇒ 跟不上手。
        //
        //   ⇒ 必须传<b>绘制时实际用的 scale</b>（lastScale）。
        float scale = lastScale > 0f ? lastScale : viewScale(r, areaW, areaH);
        SandboxGeometry.dragToBlocksWithScale(r, dx, dy, scale, widthScale,
                colCount, com.geogenesis.client.preview.SandboxLod.perColumn(),
                blockStep, out);
    }

    /** 拖动转视角（照抄 yardsite {@code dragRotate}：系数 0.006、pitch 是<b>减</b>）。 */
    public void dragRotate(double dx, double dy) {
        yaw = wrap(yaw - (float) (dx * 0.006));
        pitch = SandboxGeometry.clamp(pitch - (float) (dy * 0.006),
                SandboxGeometry.PITCH_MIN, SandboxGeometry.PITCH_MAX);
    }

    /** 屏幕平移。 */
    public void dragPan(double dx, double dy) {
        panX += (float) dx;
        panY += (float) dy;
    }

    /**
     * 清掉屏幕平移（★ v26）。
     *
     * <p>拖动期间用 {@code panX/panY} 做"零成本预览"，
     * 松手时把累积位移换算成世界中心位移并重采样 ——
     * 此时必须清掉屏幕平移，否则位移会被算两遍（画面跳一大截）。</p>
     */
    public void clearPan() {
        panX = 0f;
        panY = 0f;
    }

    public void resetView() {
        yaw = SandboxGeometry.DEFAULT_YAW;
        pitch = SandboxGeometry.DEFAULT_PITCH;
        panX = panY = 0f;
    }

    public void setWidthScale(float v) {
        widthScale = SandboxGeometry.clamp(v, 0.5f, 2.0f);
    }

    /** 柱子横向撑开倍数。 */
    public float widthScale() { return widthScale; }

    /** 命中柱的顶面高度（模型单位；0 = 未命中）。 */
    public float pickedTopY() {
        if (pickedColumn < 0 || lastCols == null) return 0f;
        if (pickedColumn >= lastCols.items.length) return 0f;
        return lastCols.items[pickedColumn].top;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    // ----- 参数构造 -----

    /** 构造本渲染器的参数对象。 */
    public RasterParams paramsFor(TerrainFieldData snapshot, int viewportW, int viewportH,
                                  float zoomFactor, GeoPalette.PreviewLayer layer, int seaLevel) {
        return new RasterParams(viewportW, viewportH, zoomFactor, layer, seaLevel);
    }

    /**
     * 软件光栅化 —— <b>只用于离线验证</b>（探针出图）。
     *
     * <p>实机不走这条路径：拉远档 512² = 262,144 柱，纯 Java 填充不可行
     * （RTF 是 256² = 65,536，我们是它的 4 倍）。实机用 {@link #draw}。</p>
     */
    public int[] rasterize(TerrainFieldData snapshot, RasterParams params) {
        if (snapshot == null || params.width() <= 0 || params.height() <= 0) return new int[0];
        int w = params.width(), h = params.height();
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF101018);

        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        lastR = r;   // vertex() 读它
        SandboxGeometry.Columns cols = buildCols(snapshot, params, r, 60f);
        int[] sorted = SandboxGeometry.sortByDepth(cols, r, null);
        float[] fc = SandboxGeometry.fitCenter(r);
        // ★ v27：与 draw() 同源
        float scale = viewScale(r, w, h);
        float ox = w / 2f - scale * fc[0];
        float oy = h / 2f - scale * fc[1];

        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column c = cols.items[sorted[k]];
            if (c.top < 0 || c.alpha <= 0) continue;
            int color = (c.alpha << 24) | c.rgb;
            float[] p0 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x0, c.y0, c.top);
            float[] p1 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x1, c.y0, c.top);
            float[] p2 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x1, c.y1, c.top);
            float[] p3 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x0, c.y1, c.top);
            fillTri(px, w, h, p0, p1, p2, color);
            fillTri(px, w, h, p0, p2, p3, color);
        }
        return px;
    }

    /**
     * <b>实机绘制</b>（OpenGL 逐柱提交）。
     *
     * @param globalAlpha 整张图的不透明度（视图切换过渡用，1.0 = 正常）
     * @param heightScale 整张图的高度比例（过渡用：0.45 = 压扁，1.0 = 正常）
     */
    public void draw(GuiGraphics g, TerrainFieldData snapshot, RasterParams params,
                     int areaX, int areaY, int areaW, int areaH,
                     float span, long now, float globalAlpha, float heightScale) {
        if (snapshot == null || snapshot.gridW <= 0) return;
        lastSpan = span;
        // ★ v26：本图第一次绘制 → 记下动画基准（此后跨帧稳定，见 animBaseMs 注释）
        if (animBaseMs == 0L) animBaseMs = now;

        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        SandboxGeometry.Columns cols = buildCols(snapshot, params, r, span);
        SandboxGeometry.applyGrow(cols, now);
        // ★ v25：过渡的高度比例（参考项目 collect() 第 687 行 {@code tops *= heightScale}）
        if (heightScale < 1.0f) {
            for (int k = 0; k < cols.readyCount; k++) {
                SandboxGeometry.Column c = cols.items[cols.ready[k]];
                if (c.top >= 0f) c.top *= heightScale;
            }
        }
        int[] sorted = SandboxGeometry.sortByDepth(cols, r, null);
        if (cols.readyCount == 0) return;

        float[] fc = SandboxGeometry.fitCenter(r);
        // ★★ v27【修"视野一缩放就超出底板"】★★
        //
        //   {@code scaleOf = areaSize × 0.46 × zoom} 的 0.46 是按"模型跨度 2"推的
        //   内切系数，但<b>斜投影后包围盒是 2.803×1.956 不是 2×2</b>
        //   ⇒ 即使 zoom=1.0，横向也到视口的 1.29 倍 ⇒ 底板左右边缘被裁。
        //   用户：「视野一缩放就超出底板了，参考项目是不会超出底板的」。
        //
        //   ⇒ 基准档（zoom ≤ BASE_ZOOM）用 {@link SandboxGeometry#fitScale}
        //     —— 按实际包围盒归一化，盘面完整可见；
        //     推近档（zoom > BASE_ZOOM）用 {@link SandboxGeometry#scaleOf}
        //     —— 放大到局部铺满屏幕是"推近"的应有之义。
        float scale = viewScale(r, areaW, areaH);
        float ox = areaX + areaW / 2f + panX - scale * fc[0];
        float oy = areaY + areaH / 2f + panY - scale * fc[1];

        // 缓存供 hover 用
        lastCols = cols;
        lastR = r;
        lastScale = scale;
        lastOriginX = ox;
        lastOriginY = oy;

        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());

        // ★★★ v30【补上参考项目的"椭圆四周暗"】★★★
        //
        //   参考项目有两种场：
        //     · 大沙盘 SiteMapSampler → showsFloor() = true（方形底板）
        //     · 精读   SiteReliefField → showsFloor() = false（<b>不画底板</b>）
        //       理由（SiteField 第 95-98 行原文）：
        //       「精读是<b>圆形窗口</b>，外圈几格逐渐透明收边」
        //       （SiteField 第 79 行）：「精读是圆形窗口，方板子的四个角会戳在圆外面，
        //        所以不画」
        //
        //   我们此前一直画方形底板 + edgeFade 恒 1.0
        //   ⇒ 用户看到的是"方形平板"，而参考项目是"椭圆+四周渐暗"。
        //   用户原话：「明明参考项目是椭圆四周暗的视觉效果…
        //              不是参数是功能缺失。」
        //
        //   ⇒ 现在：柱子的 edgeFade 走圆形渐隐（见 SandboxGeometry.edgeFadeAt），
        //     底板<b>不画</b>（方板四角会戳到圆外面，正是参考项目不画它的原因）。
        //     若日后要"方形大沙盘"档，再加回 showsFloor 开关。
        boolean showsFloor = false;
        if (showsFloor) {
            int plateAlpha = (int) (0xF2 * globalAlpha);
            quadFlat(vc, ox, oy, scale, -1f, 1f, -1f, 1f, -0.02f, (plateAlpha << 24) | 0x171A22);
        }

        boolean seeEast = r[6] > 0f, seeWest = r[6] < 0f;
        boolean seeSouth = r[7] > 0f, seeNorth = r[7] < 0f;
        boolean seeTop = r[8] > 0f;

        // ★ 吸附阈值只用于消除浮点噪声，<b>不是</b> 1px 门槛：
        //   中位相邻高差仅 0.2 格（≈0.9px），用 1px 门槛会把侧墙全抹平 ⇒ 整片平地板。
        final float eps = 1e-4f;

        for (int i = 0; i < cols.readyCount; i++) {
            int col = sorted[i];
            SandboxGeometry.Column c = cols.items[col];
            if (c.top < 0f || c.alpha <= 0) continue;
            float topSnap = SandboxGeometry.snapTop(c.top, eps);
            // ★ v25：整图不透明度（过渡用）
            int alpha = (int) (c.alpha * globalAlpha);
            if (alpha <= 0) continue;
            int rgb = c.rgb;

            float x0 = c.x0 * widthScale, x1 = c.x1 * widthScale;
            float y0 = c.y0 * widthScale, y1 = c.y1 * widthScale;

            if (seeTop) {
                int flat = rgb | (alpha << 24);
                vertex(vc, ox, oy, scale, x0, y0, topSnap, flat);
                vertex(vc, ox, oy, scale, x1, y0, topSnap, flat);
                vertex(vc, ox, oy, scale, x1, y1, topSnap, flat);
                vertex(vc, ox, oy, scale, x0, y1, topSnap, flat);
            }

            int ci = col / cols.colRows, cj = col % cols.colRows;
            if (seeEast) {
                float low = (ci + 1 < cols.colCount)
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col + cols.colRows].top, 0f), eps)
                        : 0f;
                if (topSnap > low) {
                    wall(vc, ox, oy, scale, x1, y0, x1, y1, low, topSnap, rgb, alpha, 0.82f, 0.60f);
                }
            }
            if (seeWest) {
                float low = ci > 0
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col - cols.colRows].top, 0f), eps)
                        : 0f;
                if (topSnap > low) {
                    wall(vc, ox, oy, scale, x0, y1, x0, y0, low, topSnap, rgb, alpha, 0.56f, 0.40f);
                }
            }
            if (seeSouth) {
                float low = (cj + 1 < cols.colRows)
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col + 1].top, 0f), eps)
                        : 0f;
                if (topSnap > low) {
                    wall(vc, ox, oy, scale, x0, y1, x1, y1, low, topSnap, rgb, alpha, 0.70f, 0.52f);
                }
            }
            if (seeNorth) {
                float low = cj > 0
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col - 1].top, 0f), eps)
                        : 0f;
                if (topSnap > low) {
                    wall(vc, ox, oy, scale, x1, y0, x0, y0, low, topSnap, rgb, alpha, 0.46f, 0.34f);
                }
            }
        }

        // hover 高亮（叠在最后；参考项目 503-507 行）
        if (pickedColumn >= 0 && pickedColumn < cols.items.length) {
            SandboxGeometry.Column hc = cols.items[pickedColumn];
            if (hc.top >= 0f) {
                quadFlat(vc, ox, oy, scale,
                        hc.x0 * widthScale, hc.x1 * widthScale,
                        hc.y0 * widthScale, hc.y1 * widthScale,
                        hc.top + 0.006f, 0x4CFFFFFF);
            }
        }

        // 遮挡靠提交顺序 ⇒ 关剔除；关深度写入（否则后画的字/按钮被拒）
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    // ----- 几何提交（照抄 yardsite vertex/wall/quadFlat）-----

    /** 顶点（横向按 widthScale 撑开由调用方已做）。 */
    private void vertex(VertexConsumer c, float ox, float oy, float s,
                        float mx, float my, float mz, int argb) {
        c.vertex(ox + s * (lastR[0] * mx + lastR[1] * my + lastR[2] * mz),
                 oy + s * (lastR[3] * mx + lastR[4] * my + lastR[5] * mz),
                 SandboxGeometry.Z_BASE + SandboxGeometry.DEPTH_SCALE
                         * (lastR[6] * mx + lastR[7] * my + lastR[8] * mz))
         .color(argb)
         .endVertex();
    }

    private void wall(VertexConsumer c, float ox, float oy, float s,
                      float ax, float ay, float bx, float by,
                      float low, float high, int rgb, int alpha,
                      float litTop, float litLow) {
        int t = shade(rgb, litTop, alpha);
        int b = shade(rgb, litLow, alpha);
        vertex(c, ox, oy, s, ax, ay, high, t);
        vertex(c, ox, oy, s, bx, by, high, t);
        vertex(c, ox, oy, s, bx, by, low, b);
        vertex(c, ox, oy, s, ax, ay, low, b);
    }

    private void quadFlat(VertexConsumer c, float ox, float oy, float s,
                          float x0, float x1, float y0, float y1, float z, int argb) {
        vertex(c, ox, oy, s, x0, y0, z, argb);
        vertex(c, ox, oy, s, x1, y0, z, argb);
        vertex(c, ox, oy, s, x1, y1, z, argb);
        vertex(c, ox, oy, s, x0, y1, z, argb);
    }

    private static int shade(int rgb, float mul, int alpha) {
        int r = SandboxGeometry.clampI((int) (((rgb >> 16) & 0xFF) * mul), 0, 255);
        int g = SandboxGeometry.clampI((int) (((rgb >> 8) & 0xFF) * mul), 0, 255);
        int b = SandboxGeometry.clampI((int) ((rgb & 0xFF) * mul), 0, 255);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    // ----- hover -----

    /**
     * 视线推进拾取（<b>照抄</b> yardsite {@code pick()} 1002-1042 行）。
     *
     * <h2>投影是正交的 3×3 旋转，逆就是转置</h2>
     * <p>正向（见 {@code vertex()}）：</p>
     * <pre>
     *   sx = r0·mx + r1·my + r2·mz
     *   sy = r3·mx + r4·my + r5·mz
     *   depth = r6·mx + r7·my + r8·mz
     * </pre>
     * <p>{@code r} 是<b>行摊平</b>的旋转矩阵（正交，逆 = 转置）⇒</p>
     * <pre>
     *   [mx my mz]ᵀ = Rᵀ · [sx sy depth]ᵀ
     *   mx = r0·a + r3·b + r6·t
     *   my = r1·a + r4·b + r7·t
     *   mz = r2·a + r5·b + r8·t
     * </pre>
     * <p>其中 {@code a,b} 是屏幕偏移、{@code t} 是<b>深度</b>（沿视线推进的参数）。
     * yardsite 就是这么做：从 {@code t=3.2} 扫到 {@code -3.2}，第一个
     * {@code mz <= top} 的柱就是玩家看到的那格（由近及远）。</p>
     */
    public void pick(double guiX, double guiY, int areaX, int areaY, int areaW, int areaH) {
        pickedColumn = -1;
        if (lastCols == null || lastR == null || lastScale <= 0f) return;
        float w = widthScale;
        float a = (float) ((guiX - lastOriginX) / lastScale);
        float b = (float) ((guiY - lastOriginY) / lastScale);
        // ★ 旋转矩阵正交 ⇒ 逆 = 转置：沿深度 t 推进
        float r0 = lastR[0], r1 = lastR[1], r2 = lastR[2];
        float r3 = lastR[3], r4 = lastR[4], r5 = lastR[5];
        float r6 = lastR[6], r7 = lastR[7], r8 = lastR[8];
        for (float t = 3.2f; t > -3.2f; t -= MARCH_STEP) {
            float mx = (r0 * a + r3 * b + r6 * t) / w;
            float my = (r1 * a + r4 * b + r7 * t) / w;
            float mz = r2 * a + r5 * b + r8 * t;
            if (mx < -1.02f || mx > 1.02f || my < -1.02f || my > 1.02f) continue;
            if (mz < 0f) continue;
            int cx = (int) Math.floor((mx + 1f) * 0.5f * lastCols.colCount);
            int cz = (int) Math.floor((my + 1f) * 0.5f * lastCols.colRows);
            if (cx < 0 || cx >= lastCols.colCount || cz < 0 || cz >= lastCols.colRows) continue;
            int col = cx * lastCols.colRows + cz;
            float top = lastCols.items[col].top;
            if (top >= 0f && mz <= top) {
                pickedColumn = col;
                return;
            }
        }
    }

    public void clearPick() { pickedColumn = -1; }

    public int pickedColumn() { return pickedColumn; }

    public int pickedColX() {
        return pickedColumn < 0 || lastCols == null ? -1 : pickedColumn / lastCols.colRows;
    }

    public int pickedColZ() {
        return pickedColumn < 0 || lastCols == null ? -1 : pickedColumn % lastCols.colRows;
    }

    public double pickedSurfaceY() {
        if (pickedColumn < 0 || lastCols == null) return Double.NaN;
        return lastCols.items[pickedColumn].surfaceY;
    }

    // ----- 内部 -----

    /**
     * <b>屏幕缩放（像素/模型单位）—— 全类唯一来源</b>（★ v27）。
     *
     * <p>{@code draw} / {@code prepare} / {@code pick} / {@code dragToBlocks}
     * 必须用<b>同一个</b>值，否则绘制与命中的位置会错开。</p>
     *
     * <pre>
     *   zoom ≤ BASE_ZOOM（基准/拉远）→ fitScale：按投影包围盒归一，盘面完整可见
     *   zoom &gt;  BASE_ZOOM（推近）  → scaleOf ：放大到局部铺满屏幕
     * </pre>
     */
    private float viewScale(float[] r, int areaW, int areaH) {
        // ★★ v29【修"还是没有铺满沙盘模式画面"】★★
        //
        //   此前是 `zoom <= BASE_ZOOM ? fillScale : scaleOf`。
        //   但 zoom 是<b>可被滚轮改</b>的（日志实测实机 zoom=0.50），
        //   一旦用户拉远，就落到 scaleOf 分支 ⇒ 盘面只占 74%/75% ⇒ 看着没铺满。
        //
        //   用户的意思很明确：<b>沙盘画面就该铺满</b>。
        //   ⇒ 只要盘面比视口小，就放大到铺满；比视口大（推近）则保持 zoom 放大。
        //   即：scale = max(铺满所需, zoom 放大所需) 的合理组合 ——
        //   统一为「以 fillScale 为<b>下限</b>」：
        //     · zoom=1.0（基准）→ 正好 fillScale（铺满）
        //     · zoom<1（拉远）  → 仍是 fillScale（不允许缩到比视口小）
        //     · zoom>1（推近）  → fillScale × (zoom/BASE_ZOOM)（放大看局部）
        float fill = SandboxGeometry.fillScale(r, areaW, areaH);
        float ratio = zoom / SandboxLod.BASE_ZOOM;
        if (ratio <= 1f) return fill;
        return SandboxGeometry.scaleOf(Math.min(areaW, areaH), zoom);
    }

    /**
     * 铺开动画的基准时刻（本图第一次绘制的毫秒时间戳；0 = 还没开始）。
     *
     * <p>★ 必须由光栅器<b>跨帧持有</b>：若每帧取 now，
     * {@code bornAt} 会一直往后跑 ⇒ 柱子永远停在"刚开始长"的状态
     * （用户反馈："动画有了但好像只是整体一起淡入，不是从中心一圈圈长出来"）。</p>
     */
    private long animBaseMs = 0L;

    /** 换图（视口/窗口/精度变了）时重置铺开动画，让新画面重新从中心长出。 */
    public void resetSpreadAnimation() {
        animBaseMs = 0L;
    }

    private SandboxGeometry.Columns buildCols(TerrainFieldData snap, RasterParams p, float[] r, float span) {
        return SandboxGeometry.buildColumns(snap, p.layer(), SandboxLod.perColumn(),
                span, 0, 0, p.seaLevel(), System.currentTimeMillis(), animBaseMs, lastColorSrc);
    }

    /** 竖向比例分母（`draw` 每次传入；`hover` 复用上一帧的值）。 */
    private float lastSpan = 60f;
    /** 底色来源（控制器注入；null = 全走群系本色）。 */
    private SandboxGeometry.ColorSource lastColorSrc;

    public void setColorSource(SandboxGeometry.ColorSource src) { this.lastColorSrc = src; }

    /** 上一帧某柱的柱顶高度（探针投影到柱顶用；-1 = 无该柱）。 */
    public float prepareTopAt(int col) {
        if (lastCols == null || col < 0 || col >= lastCols.items.length) return -1f;
        return lastCols.items[col].top;
    }

    /**
     * <b>只算不画</b> —— 供探针构建 {@code lastCols}/{@code lastR} 等缓存，
     * 使 {@link #pick} 与 {@link #hover} 能在无 MC 环境（无 {@code GuiGraphics}）下测试。
     *
     * <p>与 {@link #draw} 的前半段完全一致（同一份几何），
     * 只是不提交顶点 —— 这样探针验证的就是实机用的同一份数据。</p>
     */
    public void prepare(TerrainFieldData snapshot, RasterParams params,
                        int areaX, int areaY, int areaW, int areaH, float span) {
        if (snapshot == null || snapshot.gridW <= 0) return;
        lastSpan = span;
        float[] r = SandboxGeometry.rotationRows(yaw, pitch);
        lastR = r;
        SandboxGeometry.Columns cols = buildCols(snapshot, params, r, span);
        float[] fc = SandboxGeometry.fitCenter(r);
        // ★ v27：与 draw() 同源
        float scale = viewScale(r, areaW, areaH);
        lastCols = cols;
        lastScale = scale;
        lastOriginX = areaX + areaW / 2f + panX - scale * fc[0];
        lastOriginY = areaY + areaH / 2f + panY - scale * fc[1];
    }

    /** 悬停信息（读上一帧的拾取结果）。 */
    public HoverInfo hover(TerrainFieldData snapshot, RasterParams params, int mx, int my) {
        int col = pickedColumn;
        if (col < 0 || lastCols == null || col >= lastCols.items.length) return null;
        SandboxGeometry.Column c = lastCols.items[col];
        int cx = col / lastCols.colRows, cz = col % lastCols.colRows;
        // 柱中心 → 世界坐标
        int gx = Math.min(snapshot.gridW - 1, cx * SandboxLod.perColumn() + SandboxLod.perColumn() / 2);
        int gz = Math.min(snapshot.gridH - 1, cz * SandboxLod.perColumn() + SandboxLod.perColumn() / 2);
        int wx = snapshot.worldX(gx), wz = snapshot.worldZ(gz);
        String detail = String.format(java.util.Locale.ROOT, "Y=%.0f", c.surfaceY);
        String terrain = c.water ? "water" : "";
        return new HoverInfo(wx, wz, terrain, detail, c.surfaceY);
    }

    /** 图例第一行（沙盘尺度）。 */
    public String legendLine0(TerrainFieldData snapshot, RasterParams params) {
        if (snapshot == null) return "";
        int pillars = Math.max(1, snapshot.gridW / Math.max(1, SandboxLod.perColumn()));
        return String.format(java.util.Locale.ROOT,
                "%d 格 · %d 柱 · 1 柱 = %d 格方块",
                snapshot.blocksWide, pillars, SandboxLod.STEP_BLOCKS);
    }

    // ----- 软件填充（rasterize 用）-----

    private static void fillTri(int[] px, int w, int h, float[] a, float[] b, float[] c, int color) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(w - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(h - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (inTri(x + .5f, y + .5f, a, b, c)) px[y * w + x] = color;
            }
        }
    }

    private static boolean inTri(float px, float py, float[] a, float[] b, float[] c) {
        float d1 = (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1]);
        float d2 = (px - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (py - c[1]);
        float d3 = (px - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (py - a[1]);
        boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(neg && pos);
    }

    private static float wrap(float a) {
        float t = (float) (a % (Math.PI * 2));
        return t < 0 ? t + (float) (Math.PI * 2) : t;
    }
}
