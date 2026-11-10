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

    /**
     * <b>出生点标记</b>所在的柱子（-1 = 未标记）。
     *
     * <p>★ v34：对照表 #44 的 {@code pin} 落点图钉 —— 参考项目用它标出玩家选定的落点
     * （金色十字，立在所选格子顶面上）。用户问：「这个不能作为确定玩家想的自定义出生点吗？」
     * ⇒ 可以，而且这正是它的原始用途。这里存"世界坐标"而不是"柱子下标"，
     * 因为重新采样后柱子下标会变。</p>
     */
    private int markWorldX = Integer.MIN_VALUE;
    private int markWorldZ = Integer.MIN_VALUE;

    /** 设置/清除出生点标记（世界坐标；传 {@code Integer.MIN_VALUE} 清除）。 */
    public void setSpawnMark(int worldX, int worldZ) {
        markWorldX = worldX;
        markWorldZ = worldZ;
    }

    public int spawnMarkX() { return markWorldX; }
    public int spawnMarkZ() { return markWorldZ; }
    public boolean hasSpawnMark() { return markWorldX != Integer.MIN_VALUE; }

    /**
     * <b>上一帧的平均柱高</b>（模型单位）—— 视觉重心居中补偿要用它。
     *
     * <p>★ v36：v32 用 {@code RELIEF/2}（最大可能柱高的一半）做补偿，
     * 但实测平均柱高只有 {@code RELIEF} 的约 1/5
     * （探针：平均 41px vs 最高 201px）⇒ 补偿过量 80px，视觉上仍偏高。
     * 用平均柱高才反映"内容真正的重心"。</p>
     *
     * <p>跨帧保持（不每帧归零），避免补偿量抖动导致画面上下轻微漂移。</p>
     */
    private float lastAvgTop = 0f;

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

    /**
     * <b>让镜头自己慢慢转</b>（★ v35，照参考项目 {@code orbit()} 197-199 行）。
     *
     * <p>参考项目注释：「等出生点区块那段时间用」—— 只改 yaw，不碰 pitch。
     * 我们用它做<b>"进入沙盘时的开场镜头"</b>：从偏一点的角度慢慢转到默认视角，
     * 让切换有个"落定"的过程，而不是硬停在某个角度。</p>
     */
    public void orbit(float radians) {
        yaw = wrap(yaw + radians);
    }

    /** 直接设定 yaw（开场动画用；内部会 wrap）。 */
    public void setYaw(float v) {
        yaw = wrap(v);
    }

    /** 默认 yaw（开场动画的落定点）。 */
    public static float defaultYaw() {
        return SandboxGeometry.DEFAULT_YAW;
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
        // ★ v32：与 draw() 同源（含内容质心居中补偿）
        float scale = viewScale(r, w, h);
        float oyBias = SandboxGeometry.RELIEF * Math.abs(r[5]) * scale * 0.5f;
        float ox = w / 2f;
        float oy = h / 2f + oyBias;

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
        // ★★★ v32【真正修掉"旋转中心偏高 / 下方大片空白"】★★★
        //
        //   v31 我先照抄了参考项目的 `originX/Y = 视口中心`，但那<b>不够</b>：
        //   参考项目能那样写，是因为它的 scale 让盘面<b>大幅溢出视口</b>
        //   （1080p 下 disc 高 1843px vs 视口 1024px）⇒ 上偏被裁掉、看不出来。
        //
        //   我们的 fillScale/fitScale 让<b>整块盘面完整可见</b>（Y≈93%），
        //   上偏就暴露了。探针 runSandboxCenterProbe 实测：
        //     地面平面（mz=0）在视口中心
        //     柱高抬升 RELIEF(0.40) × |r5|(0.813) × scale ≈ 202px 全部朝上
        //     ⇒ <b>内容质心比视口中心高 40.5%</b> ⇒ 地形挤在上半屏、下面全空。
        //
        //   ⇒ 既然我们选择"整盘可见"，就必须把<b>视觉重心</b>对到视口中心。
        //
        // ★★★ v36【再下调：v32 的补偿量算错了】★★★
        //   用户两轮反馈"还是偏高"。探针 runSandboxCenterProbe 用
        //   <b>实际柱高分布</b>（不是几何包围盒）重新度量后找到了原因：
        //     15376 柱，平均柱高仅 <b>41 px</b>，最高 201 px
        //     v32 的补偿 = RELIEF/2 = 101 px —— 按<b>最高柱</b>算的，
        //     比"视觉重心该下移的量"多了 80 px
        //   ⇒ 补偿后视觉重心仍在视口中心之上 80 px（8% 视高）。
        //
        //   正确做法：按<b>平均柱高</b>补偿，而不是按 RELIEF（最大可能柱高）。
        //   平均柱高来自上一帧的 Columns（跨帧稳定，不会抖）。
        float avgTop = lastAvgTop;
        if (avgTop <= 0f) {
            // 第一帧还没有统计 → 退回"盘面半径的固定比例"做初值
            avgTop = SandboxGeometry.RELIEF * 0.22f;
        }
        // 补偿 = 一半平均柱高 × |r5| × scale（把"柱子平均凸起的质心"压到视口中心）
        float reliefPx = avgTop * Math.abs(r[5]) * scale;
        float oyBias = reliefPx * 0.5f;
        float ox = areaX + areaW / 2f + panX;
        float oy = areaY + areaH / 2f + panY + oyBias;

        // 缓存供 hover 用
        lastCols = cols;
        lastR = r;
        lastScale = scale;
        lastOriginX = ox;
        lastOriginY = oy;

        // ★★★ v34【修"未加载时那个大圆盘不好看"】★★★
        //
        //   用户原话：「沙盘在开始未加载时显示一个圈不好看吧？建议其他四周与底板同色。」
        //
        //   v33 我画的是<b>椭圆底板</b>（为了贴合 edgeFade 形状），
        //   但数据还没到时柱子一根都没有 ⇒ 屏幕上只剩一个<b>光秃秃的深色椭圆</b>，
        //   四周是更暗的背景 ⇒ "一个圈"很扎眼。
        //
        //   ⇒ 底板应该是<b>整屏铺底</b>（无形状、无边界），
        //     椭圆感完全由<b>柱子的 edgeFade</b> 呈现（那才是参考项目的做法：
        //     它的 floor 画的是 `[-1,1]²` 方形板，但盘面大得溢出视口，
        //     所以视觉上"底板就是整个画面"）。
        //   ⇒ 现在：整屏填充同色底板，加载中/已加载都一样，不会有"圈"。
        //
        // ⚠⚠ v35【必须放在 getBuffer 之前】⚠⚠
        //   {@code g.fill(...)} 内部会<b>flush 并重置 BufferBuilder</b>。
        //   若在 {@code getBuffer()} 之后调它，之前拿到的 {@code VertexConsumer}
        //   就指向一个已提交/已重置的批次 ⇒ 再写顶点抛
        //   {@code IllegalStateException: BufferBuilder not started}（实机崩溃已取证：
        //   latest.log 22:07:32 SandboxRasterizer.vertex:397 → draw:326）。
        if (globalAlpha > 0.01f) {
            int plate = (((int) (0xF2 * globalAlpha)) << 24) | 0x171A22;
            g.fill((int) areaX, (int) areaY,
                    (int) (areaX + areaW), (int) (areaY + areaH), plate);
        }

        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());

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

        // ★ v34：出生点标记（对照表 #44 的 pin，参考项目 925-943 行）
        //   金色十字立在所选格子的顶面上。用户：「这个不能作为确定玩家想的自定义出生点吗？」
        //   ⇒ 可以，这就是它的用途。
        if (hasSpawnMark()) {
            drawSpawnPin(vc, ox, oy, scale, cols, globalAlpha);
        }
        // 遮挡靠提交顺序 ⇒ 关剔除；关深度写入（否则后画的字/按钮被拒）
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();

        // ★ v36：统计本帧平均柱高，供<b>下一帧</b>的视觉重心补偿使用。
        //   （必须用上一帧的值：oyBias 在绘制开始时就要定下来，
        //     那时本帧的 cols 还没跑完。一帧的滞后肉眼看不出，且能避免抖动。）
        float sumTop = 0f;
        int cnt = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column cc = cols.items[cols.ready[k]];
            if (cc.top < 0f) continue;
            sumTop += cc.top;
            cnt++;
        }
        if (cnt > 0) {
            // 指数平滑：避免地形突变（切窗口/改图层）时补偿量跳变
            float avg = sumTop / cnt;
            lastAvgTop = lastAvgTop <= 0f ? avg : (lastAvgTop * 0.8f + avg * 0.2f);
        }
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

    /* ★ v34：floorEllipse 已删除 —— 底板改为整屏铺底（见 render 里的注释）。
       椭圆观感完全由柱子的 edgeFade 呈现，不需要再画一个椭圆盘。 */

    /**
     * <b>出生点图钉</b>（★ v34，照参考项目 {@code pin()} 925-943 行）。
     *
     * <p>金色十字（两根互相垂直的竖片）立在所选格子的顶面上，
     * 高度 {@code 0.16}（模型单位）。参考项目用它标"落点"，
     * 我们用同一个东西标<b>自定义出生点</b>。</p>
     */
    private void drawSpawnPin(VertexConsumer c, float ox, float oy, float s,
                              SandboxGeometry.Columns cols, float globalAlpha) {
        // 世界坐标 → 柱下标。用上一帧缓存的世界窗口换算（lastCols 与本帧同源）。
        if (lastWindowOriginX == Integer.MIN_VALUE) return;
        double cellBlocks = lastWindowBlocks / (double) Math.max(1, lastWindowGrid);
        int per = Math.max(1, SandboxLod.perColumn());
        int fineX = (int) Math.round((markWorldX - lastWindowOriginX) / cellBlocks);
        int fineZ = (int) Math.round((markWorldZ - lastWindowOriginZ) / cellBlocks);
        if (fineX < 0 || fineZ < 0) return;
        int ci = fineX / per, cj = fineZ / per;
        if (ci >= cols.colCount || cj >= cols.colRows) return;
        int col = ci * cols.colRows + cj;
        if (col < 0 || col >= cols.items.length) return;
        SandboxGeometry.Column c0 = cols.items[col];
        if (c0.top < 0f) return;

        float mx = SandboxGeometry.modelOf(cols.colCount, ci);
        float my = SandboxGeometry.modelOf(cols.colRows, cj);
        float top = c0.top + 0.16f;
        float w = 0.012f;
        // GOLD 0xFFFFD24A（参考项目第 74 行）
        int gold = (((int) (0xFF * globalAlpha)) << 24) | 0xFFD24A;
        // 横片
        vertex(c, ox, oy, s, mx - w, my, 0.0f, gold);
        vertex(c, ox, oy, s, mx + w, my, 0.0f, gold);
        vertex(c, ox, oy, s, mx + w, my, top, gold);
        vertex(c, ox, oy, s, mx - w, my, top, gold);
        // 竖片
        vertex(c, ox, oy, s, mx, my - w, 0.0f, gold);
        vertex(c, ox, oy, s, mx, my + w, 0.0f, gold);
        vertex(c, ox, oy, s, mx, my + w, top, gold);
        vertex(c, ox, oy, s, mx, my - w, top, gold);
    }

    /** 世界窗口（由 {@code PreviewDisplay} 每帧通过 {@link #setWorldWindow} 告知）。 */
    private int lastWindowOriginX = Integer.MIN_VALUE;
    private int lastWindowOriginZ = Integer.MIN_VALUE;
    private int lastWindowBlocks = 1;
    private int lastWindowGrid = 1;

    /** 告知本渲染器当前数据的世界窗口（出生点标记需要它把世界坐标换成柱下标）。 */
    public void setWorldWindow(int originX, int originZ, int blocksWide, int grid) {
        lastWindowOriginX = originX;
        lastWindowOriginZ = originZ;
        lastWindowBlocks = Math.max(1, blocksWide);
        lastWindowGrid = Math.max(1, grid);
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
        if (lastCols == null || lastR == null || lastScale <= 0f) {
            // ★ v40 诊断：拾取失败时把状态打出来
            com.mojang.logging.LogUtils.getLogger().info(
                    "[PickDiag] 无法拾取：cols={} R={} scale={}",
                    lastCols == null ? "null" : lastCols.readyCount,
                    lastR == null ? "null" : "ok",
                    lastScale);
            return;
        }
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
                // ★★★ v41【照参考项目：直接存细格坐标，不在外面二次换算】★★★
                //
                //   参考项目 SiteTerrainRenderer.pick() 第 1036-1037 行：
                //     pickCellX = clamp(round(fx), 0, fine-1);
                //     pickCellY = clamp(round(fy), 0, fine-1);
                //   然后 YardSiteScreen 直接用它（terrain.pickCellX/Y），
                //   <b>不再从柱索引反算</b>。
                //
                //   我们此前存柱索引，再在外面
                //   `world = center - win/2 + (col + 0.5) * pillarBlocks`
                //   二次换算 ⇒ 多一层换算就多一层出错的机会（用户两轮反馈"不准"）。
                //   ⇒ 现在把细格坐标一起存下来，调用方直接用。
                float fx = (mx + 1f) * 0.5f * (lastCols.colCount * 2);
                float fz = (my + 1f) * 0.5f * (lastCols.colRows * 2);
                pickedCellX = SandboxGeometry.clampI(
                        Math.round(fx), 0, lastCols.colCount * 2 - 1);
                pickedCellZ = SandboxGeometry.clampI(
                        Math.round(fz), 0, lastCols.colRows * 2 - 1);
                return;
            }
        }
    }

    public void clearPick() { pickedColumn = -1; }

    public int pickedColumn() { return pickedColumn; }

    /**
     * <b>拾取到的细格 X</b>（★ v41，照参考项目 {@code SiteTerrainRenderer.pickCellX}）。
     *
     * <p>细格是"一格 = blocksPerCell 方块"的最小单位，调用方拿它换算世界坐标
     * 只需一次乘法，不需要再经柱索引绕一圈。</p>
     */
    private int pickedCellX = -1;
    private int pickedCellZ = -1;

    public int pickedCellX() { return pickedCellX; }
    public int pickedCellZ() { return pickedCellZ; }

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
    /**
     * 视图切换过渡用：镜头整体推近/拉远（1 = 正常）。
     *
     * <p>★ v33 补：对照表第 50 项 —— 参考项目 {@code YardSiteScreen} 第 404 行
     * {@code terrain.setZoomBoost(fading == null ? 1.0F : 0.84F + 0.16F * ease)}。
     * 切视图时镜头从 0.84 推到 1.0，给一个"推近落定"的纵深感。</p>
     */
    private float zoomBoost = 1.0f;

    /** 设置镜头推近倍率（过渡动画用；1 = 正常）。 */
    public void setZoomBoost(float boost) {
        zoomBoost = boost;
    }

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
        // ★ v33：乘 zoomBoost（切视图过渡时镜头从 0.84 推到 1.0）
        float base = (ratio <= 1f)
                ? fill
                : SandboxGeometry.scaleOf(Math.min(areaW, areaH), zoom);
        return base * zoomBoost;
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
        // ★ v36：与 draw() 同源（含 v36 的"按平均柱高"补偿）
        float scale = viewScale(r, areaW, areaH);
        float avgTop = lastAvgTop > 0f ? lastAvgTop : SandboxGeometry.RELIEF * 0.22f;
        float oyBias = avgTop * Math.abs(r[5]) * scale * 0.5f;
        lastCols = cols;
        lastScale = scale;
        lastOriginX = areaX + areaW / 2f + panX;
        lastOriginY = areaY + areaH / 2f + panY + oyBias;
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
