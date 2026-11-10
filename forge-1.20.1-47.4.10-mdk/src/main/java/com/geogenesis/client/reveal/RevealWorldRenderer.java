package com.geogenesis.client.reveal;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

import java.util.Arrays;

/**
 * <b>落成动画的世界渲染器</b>（★ v37，步骤 A）。
 *
 * <h2>它做什么</h2>
 * <p>把沙盘那批柱子，从<b>正交投影</b>平滑过渡到<b>透视投影</b>，
 * 并让柱子按 {@link RevealSchedule} 的调度<b>一块块淡出</b>，
 * 露出下面已经加载好的真实 MC 地形。</p>
 *
 * <h2>照参考项目做的关键决策（每条都有原文依据）</h2>
 * <ul>
 *   <li><b>同一张图在两种投影间混合</b>，不是两张图 dissolve
 *       （{@code SiteRevealWorldRenderer} 第 20-24 行）。</li>
 *   <li><b>柱顶一动不动</b>，只改透明度（第 47-49 行）：
 *       「以前那一滑看着就是'柱子往上长一边散掉'，像往上飘」。</li>
 *   <li><b>柱子原地淡，不逐格错峰</b>（第 45-46 行）。</li>
 *   <li><b>动画时钟单帧封顶</b>（第 148-152 行）：建世界时渲染线程会被堵住，
 *       不封顶镜头会"停住再突然启动"。</li>
 *   <li><b>柱子盖满画面</b>：它们的作用就是遮住还没网格化的真区块。</li>
 * </ul>
 *
 * <h2>与本项目沙盘渲染的关系</h2>
 * <p>几何部分<b>完全复用</b> {@link SandboxGeometry}（柱高/边界/颜色/光照/排序），
 * 只在"投影"这一步换成 {@link PerspectiveCamera} + 正交混合。
 * 视图切换时沙盘自己那套（{@code SandboxRasterizer}）不受影响。</p>
 */
public final class RevealWorldRenderer {

    /** 单帧最多推进的动画毫秒数（参考项目 {@code MAX_FRAME_MS=120}）。 */
    private static final long MAX_FRAME_MS = 120L;

    /** 开场（正交→透视）时长（毫秒）。参考项目 {@code INTRO_MS=3000}。 */
    public static final int INTRO_MS = 3000;

    /** 空中段的俯角范围与距离（参考项目 {@code AERIAL_PITCH_MIN/MAX}、{@code END_DISTANCE}）。 */
    private static final float AERIAL_PITCH_MIN = 30f;
    private static final float AERIAL_PITCH_MAX = 45f;
    private static final float END_DISTANCE = 110f;
    /** 空中段视野上限（长焦，让沙盘盖住更远）。参考项目 {@code AERIAL_FOV=40}。 */
    private static final float AERIAL_FOV = 40f;
    /** 空中慢绕：度/秒 与 累计上限。参考项目 {@code DRIFT_DEG_PER_SEC/MAX_DEG}。 */
    private static final float DRIFT_DEG_PER_SEC = 1.5f;
    private static final float DRIFT_MAX_DEG = 18f;

    private final PerspectiveCamera camera = new PerspectiveCamera();
    /** 正交侧的行摊平矩阵（与 SandboxGeometry.rotationRows 同格式）。 */
    private final float[] orthoRows = new float[9];

    private long startedAt;
    private long lastTickAt;
    private float clockMs;
    private float orbitDeg;

    private float orthoWeight = 1f;
    private float heightScale = 1f;
    private float backdropAlpha = 1f;

    /** 落点（世界坐标）。 */
    private double siteX, siteY, siteZ;
    /** 空中机位参数。 */
    private float siteYaw, startPitch, sitePitch, aerialFov, endFov;
    /** 正交侧几何（由沙盘的当前视图继承，保证开场那一帧画面完全一致）。 */
    private double orthoOriginX, orthoOriginY;
    private double orthoScale;

    /** 当前帧的窗口尺寸。 */
    private int viewW = 1, viewH = 1;

    /** 投影输出缓冲（避免每帧分配）。 */
    private final double[] p0 = new double[3];
    private final double[] p1 = new double[3];
    private final double[] p2 = new double[3];
    private final double[] p3 = new double[3];

    /** 上次构建的柱子（跨帧复用，换图才重建）。 */
    private SandboxGeometry.Columns columns;
    private long columnsKey = Long.MIN_VALUE;

    /**
     * 初始化（在屏幕创建时调用一次）。
     *
     * @param spawnX/spawnZ 落点世界坐标
     * @param spawnY        落点地表 y
     * @param orthoScaleIn  从沙盘继承的正交缩放（保证开场第一帧与沙盘完全一致）
     * @param orthoOriginXIn / orthoOriginYIn 从沙盘继承的正交原点
     * @param sandboxYaw    沙盘当前 yaw（弧度）—— 透视侧的朝向从它换算
     * @param sandboxPitch  沙盘当前 pitch（弧度）
     * @param playerFov     玩家设置的视野（落机时展开回去）
     */
    public void begin(double spawnX, double spawnY, double spawnZ,
                      double orthoOriginXIn, double orthoOriginYIn, double orthoScaleIn,
                      float sandboxYaw, float sandboxPitch, float playerFov) {
        this.siteX = spawnX;
        this.siteY = spawnY;
        this.siteZ = spawnZ;
        this.orthoOriginX = orthoOriginXIn;
        this.orthoOriginY = orthoOriginYIn;
        this.orthoScale = orthoScaleIn;

        // 沙盘模型空间 → 世界坐标的朝向换算（参考项目第 206-213 行）
        //   沙盘 yaw/pitch 与 MC 的 yaw/pitch 语义不同：沙盘是"模型空间里怎么转"，
        //   MC 是"玩家朝哪看"。参考项目的换算式：
        //     mcYaw   = 180 - toDegrees(sandboxYaw)
        //     mcPitch = clamp(90 - toDegrees(sandboxPitch), 5, 85)
        this.siteYaw = 180f - (float) Math.toDegrees(sandboxYaw);
        this.startPitch = clamp(90f - (float) Math.toDegrees(sandboxPitch), 5f, 85f);
        this.endFov = Math.max(30f, playerFov);
        this.aerialFov = Math.min(endFov, AERIAL_FOV);
        this.sitePitch = solveAerialPitch();

        // 开场第一帧：相机参数必须让透视侧"接近"正交画面
        camera.set(siteX, siteY + 5, siteZ, siteYaw, startPitch, aerialFov);

        startedAt = 0L;
        lastTickAt = 0L;
        clockMs = 0f;
        orbitDeg = 0f;
        orthoWeight = 1f;
        heightScale = 1f;
        backdropAlpha = 1f;
    }

    /**
     * 挑空中俯角：够陡能盖住画面，又不至于变成顶视。
     *
     * <p>参考项目 {@code solveAerialPitch}：在 {@code [AERIAL_PITCH_MIN, AERIAL_PITCH_MAX]}
     * 里挑<b>最浅</b>还能盖住的 —— 越浅越像"侧着看院子"。</p>
     *
     * <p>我们简化：取区间中点偏浅（35°）。没有建筑遮挡需求，
     * 不必像参考项目那样按覆盖范围反解。</p>
     */
    private float solveAerialPitch() {
        return AERIAL_PITCH_MIN + (AERIAL_PITCH_MAX - AERIAL_PITCH_MIN) * 0.35f;
    }

    /**
     * 推进动画时钟（每帧调一次）。
     *
     * <p>★ 单帧封顶 {@link #MAX_FRAME_MS}：建世界那几秒渲染线程会被堵住半秒到一秒多，
     * 不封顶镜头会"停住再突然启动"（参考项目第 148-152 行的实测记录）。</p>
     */
    public void tick(long nowMs) {
        if (startedAt == 0L) {
            startedAt = nowMs;
            lastTickAt = nowMs;
            return;
        }
        long dt = Math.min(MAX_FRAME_MS, Math.max(0L, nowMs - lastTickAt));
        lastTickAt = nowMs;
        clockMs += dt;

        // 开场进度：0 → 1
        float p = clamp(clockMs / INTRO_MS, 0f, 1f);
        float ease = p * p * (3f - 2f * p);          // smoothstep
        orthoWeight = 1f - ease;

        // 竖向比例：从沙盘的"压缩"展开成真实高度
        //   （参考项目第 525 行：heightScaleNow = lerp(压缩比, 1.0, ease)）
        heightScale = lerp(0.85f, 1f, ease);

        // 镜头：从落点附近拉起，推到空中机位
        double dist = lerp(20.0, END_DISTANCE, ease);
        double camY = siteY + 5 + Math.sin(Math.toRadians(sitePitch)) * dist;
        double horiz = Math.cos(Math.toRadians(sitePitch)) * dist;
        double dirX = -Math.sin(Math.toRadians(siteYaw));
        double dirZ = Math.cos(Math.toRadians(siteYaw));

        // 空中段单向慢绕（开场过半才起转，参考项目第 505-510 行）
        float ramp = smooth01((clockMs - INTRO_MS * 0.5f) / (INTRO_MS * 0.5f));
        if (ramp > 0f) {
            orbitDeg = Math.min(DRIFT_MAX_DEG,
                    orbitDeg + DRIFT_DEG_PER_SEC * ramp * (dt / 1000f));
        }

        float yaw = siteYaw + orbitDeg;
        camera.set(siteX - dirX * horiz, camY, siteZ - dirZ * horiz,
                yaw, sitePitch, aerialFov);

        // 不透明底：登录后淡掉（我们暂用统一淡出）
        backdropAlpha = 1f - ease * 0.0f;   // 步骤 A：保持不透明，柱子负责遮罩
    }

    /** 开场是否已结束（可以开始让位调度）。 */
    public boolean introDone() { return clockMs >= INTRO_MS; }

    /** 动画时钟（毫秒）。 */
    public float clockMs() { return clockMs; }

    /** 当前相机机位（落机航线的起点）。 */
    public float cameraEyeX() { return (float) camera.eyeX; }
    public float cameraEyeY() { return (float) camera.eyeY; }
    public float cameraEyeZ() { return (float) camera.eyeZ; }

    /** 落点世界坐标。 */
    public double siteX() { return siteX; }
    public double siteY() { return siteY; }
    public double siteZ() { return siteZ; }

    /**
     * 落机阶段：由 {@link DescentPath} 直接接管相机。
     *
     * <p>★ 参考项目 {@code setDrawCamera} 第 531-543 行：落机时<b>只改绘制机位</b>，
     * 不动基准机位 —— 否则下一帧会在上一帧结果上再插一次，越插越偏。</p>
     */
    public void applyDescentCamera(float x, float y, float z, float yaw, float pitch) {
        camera.set(x, y, z, yaw, pitch, endFov);
        // 落机阶段正交权重归零（已是纯透视）
        orthoWeight = 0f;
    }

    /**
     * 画一帧。
     *
     * @param data    地形数据（来自 CellCache）
     * @param layer   当前图层
     * @param nowMs   当前时刻
     * @param schedule 让位调度（可为 null = 还没开始让位，全画）
     * @param span    竖向比例分母
     */
    public void render(GuiGraphics g, TerrainFieldData data, GeoPalette.PreviewLayer layer,
                       long nowMs, RevealSchedule schedule, float span,
                       int x, int y, int w, int h) {
        this.viewW = Math.max(1, w);
        this.viewH = Math.max(1, h);

        // 不透明背景（柱子是唯一遮罩，背景必须实心）
        int bg = ((int) (0xFF * backdropAlpha) << 24) | 0x0A0C12;
        g.fill(x, y, x + w, y + h, bg);

        // ★ v42：没数据也要画点东西（否则整屏纯黑，用户不知道在等什么）
        if (data == null || data.gridW <= 0) {
            return;
        }

        // 重建柱子（换图才重建）
        long key = data.viewportId * 31L + layer.ordinal();
        if (columns == null || columnsKey != key) {
            columns = SandboxGeometry.buildColumns(data, layer, 2, span,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, 63.0, nowMs, 0L,
                    (gx, gz, snap) -> solidColor(layer, snap, gx, gz));
            columnsKey = key;
        }
        SandboxGeometry.Columns cols = columns;
        if (cols.readyCount == 0) return;

        // 深度排序：透视下按到相机的距离（远→近）
        int[] sorted = sortByCameraDistance(cols);

        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        double centerX = x + w / 2.0;
        double centerY = y + h / 2.0;
        double halfH = h / 2.0;
        // 柱子在世界坐标里：一格 = blocksPerCell 方块
        double bpc = data.blocksPerCell();

        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (int k = 0; k < cols.readyCount; k++) {
            int col = sorted[k];
            SandboxGeometry.Column c = cols.items[col];
            if (c.top < 0f) continue;

            // 让位：按调度算这一块的不透明度
            float fade = 1f;
            if (schedule != null) {
                RevealSchedule.Block blk = schedule.blockAt(col);
                if (blk != null) {
                    fade = 1f - schedule.fadeProgress(blk, (long) clockMs);
                }
            }
            if (fade <= 0.01f) continue;

            int alpha = (int) (255 * fade);
            int rgb = c.rgb;

            // 柱子的世界坐标（柱中心）
            int ci = col / cols.colRows, cj = col % cols.colRows;
            double wx = data.worldX(ci * 2);
            double wz = data.worldZ(cj * 2);
            double wTop = c.top * span / SandboxGeometry.RELIEF * heightScale;
            double baseY = 63.0 - 4.0;

            // 四个水平角（世界坐标）
            double hx = (c.x1 - c.x0) * 0.5 * bpc;
            double hz = (c.y1 - c.y0) * 0.5 * bpc;
            double x0 = wx - hx, x1 = wx + hx;
            double z0 = wz - hz, z1 = wz + hz;

            // 顶面
            if (project(x0, baseY + wTop, z0, centerX, centerY, halfH, p0)
                    && project(x1, baseY + wTop, z0, centerX, centerY, halfH, p1)
                    && project(x1, baseY + wTop, z1, centerX, centerY, halfH, p2)
                    && project(x0, baseY + wTop, z1, centerX, centerY, halfH, p3)) {
                int flat = rgb | (alpha << 24);
                vtx(vc, p0, flat);
                vtx(vc, p1, flat);
                vtx(vc, p2, flat);
                vtx(vc, p3, flat);
            }

            // 侧墙：只在比邻居高时画（与沙盘同判据）
            float lowTop = neighborTop(cols, col, ci, cj);
            if (wTop > lowTop) {
                drawWall(vc, cols, c, ci, cj, wx, wz, hx, hz, baseY,
                        lowTop, (float) wTop, rgb, alpha, centerX, centerY, halfH);
            }
        }

        g.flush();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    // ------------------------------------------------------------------

    /** 邻居柱高（取四邻最大，0 = 没有邻居就用 0）。 */
    private float neighborTop(SandboxGeometry.Columns cols, int col, int ci, int cj) {
        float m = 0f;
        if (ci > 0) m = Math.max(m, cols.items[col - cols.colRows].top);
        if (ci + 1 < cols.colCount) m = Math.max(m, cols.items[col + cols.colRows].top);
        if (cj > 0) m = Math.max(m, cols.items[col - 1].top);
        if (cj + 1 < cols.colRows) m = Math.max(m, cols.items[col + 1].top);
        return Math.max(0f, m);
    }

    /** 画侧墙（只画朝相机的两面，判据与沙盘一致）。 */
    private void drawWall(VertexConsumer vc, SandboxGeometry.Columns cols,
                          SandboxGeometry.Column c, int ci, int cj,
                          double wx, double wz, double hx, double hz, double baseY,
                          float low, float high, int rgb, int alpha,
                          double centerX, double centerY, double halfH) {        // 用相机方向判可见面（简化：东西、南北各取一）
        boolean east = camera.rightX * (wx - camera.eyeX) + camera.rightZ * (wz - camera.eyeZ) > 0;
        boolean south = camera.lookX * (wx - camera.eyeX) + camera.lookZ * (wz - camera.eyeZ) > 0;

        double xw = east ? wx + hx : wx - hx;
        double zw = south ? wz + hz : wz - hz;

        double[] a = new double[3], b = new double[3], d = new double[3], e = new double[3];
        // 只在有位移时画（参考项目：top > low）
        if (project(xw, baseY + high, south ? wz - hz : wz + hz, centerX, centerY, halfH, a)
                && project(xw, baseY + high, south ? wz + hz : wz - hz, centerX, centerY, halfH, b)
                && project(xw, baseY + low, south ? wz + hz : wz - hz, centerX, centerY, halfH, d)
                && project(xw, baseY + low, south ? wz - hz : wz + hz, centerX, centerY, halfH, e)) {
            int top = shade(rgb, 0.82f, alpha);
            int bot = shade(rgb, 0.60f, alpha);
            vtx(vc, a, top);
            vtx(vc, b, top);
            vtx(vc, d, bot);
            vtx(vc, e, bot);
        }
    }

    /** 投影一个世界点（正交 ↔ 透视混合）。 */
    private boolean project(double wx, double wy, double wz,
                            double centerX, double centerY, double halfH, double[] out) {
        return camera.projectMixed(wx, wy, wz, orthoWeight, orthoRows,
                orthoOriginX, orthoOriginY, orthoScale,
                centerX, centerY, halfH, out);
    }

    private void vtx(VertexConsumer c, double[] p, int argb) {
        // GUI 批的深度带：用 depth 压到一个窄区间，避免被裁
        float z = (float) (200.0 + clampDepth(p[2]));
        c.vertex(p[0], p[1], z).color(argb).endVertex();
    }

    private static double clampDepth(double d) {
        // 透视下 depth 差异巨大；压到 [0,100] 的深度带里
        if (d <= 0) return 0;
        return Math.min(100.0, Math.log1p(d) * 12.0);
    }

    /** 按到相机的距离排序（远→近）。 */
    private int[] sortByCameraDistance(SandboxGeometry.Columns cols) {
        int n = cols.readyCount;
        int[] order = new int[n];
        double[] key = new double[n];
        for (int k = 0; k < n; k++) {
            int col = cols.ready[k];
            SandboxGeometry.Column c = cols.items[col];
            int ci = col / cols.colRows, cj = col % cols.colRows;
            key[k] = -camera.depthKey(ci * 16.0, 63.0, cj * 16.0);   // 负号：远的排前面
            order[k] = col;
        }
        // 简单插入排序（n 不大；避免装箱）
        for (int i = 1; i < n; i++) {
            double kk = key[i];
            int vv = order[i];
            int j = i - 1;
            while (j >= 0 && key[j] > kk) {
                key[j + 1] = key[j];
                order[j + 1] = order[j];
                j--;
            }
            key[j + 1] = kk;
            order[j + 1] = vv;
        }
        return order;
    }

    private static int solidColor(GeoPalette.PreviewLayer layer, TerrainFieldData d, int gx, int gz) {
        int i = d.index(gx, gz);
        if (i < 0) return 0x505050;
        // 步骤 A：统一用高程色带（与沙盘同一套 GeoPalette）
        double e = d.e[i];
        double t = Math.max(0.0, Math.min(1.0, (e + 1.0) * 0.5));
        return GeoPalette.continuous(GeoPalette.PreviewLayer.ELEVATION, t) & 0xFFFFFF;
    }

    private static int shade(int rgb, float mul, int alpha) {
        int r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) * mul)));
        int g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) * mul)));
        int b = Math.max(0, Math.min(255, (int) ((rgb & 0xFF) * mul)));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static double lerp(double a, double b, float t) { return a + (b - a) * t; }
    private static float smooth01(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
