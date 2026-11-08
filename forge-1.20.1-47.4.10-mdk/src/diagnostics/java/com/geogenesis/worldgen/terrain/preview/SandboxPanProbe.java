package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.Locale;

/**
 * <b>沙盘拖动换算验证</b>（★ 2026-11-08）。
 *
 * <h2>要守住的用户诉求</h2>
 * <p>「我还不能移动位置。明明参考项目可以的。」</p>
 *
 * <p>我们是<b>全屏单层地图</b>，没有参考项目那种"切到区块精读层"的退路，
 * 拖动必须<b>移动世界中心</b>并重新采样。而"屏幕像素 → 世界方块"的换算是关键：
 * 换错会让拖动方向反了、或者斜着拉只动一个轴。</p>
 *
 * <h2>判据（纯数学，不依赖渲染）</h2>
 * <ol>
 *   <li><b>方向正确</b>：向右拖 ⇒ 地图内容向右走 ⇒ 中心向西移（x 减小）；</li>
 *   <li><b>斜向可分解</b>：沿屏幕对角线拖，两个轴都要有非零位移（不能只动一个轴）；</li>
 *   <li><b>量级合理</b>：拖动整屏宽度，中心移动量 ≈ 视野宽度（同一数量级）；</li>
 *   <li><b>缩放感知</b>：推近后同样的屏幕位移对应<b>更少</b>方块（地图放大时更精细）。</li>
 * </ol>
 *
 * <p>注：{@code SandboxRenderer.dragToBlocks} 依赖旋转矩阵（由 yaw/pitch 算出）。
 * 本探针用与渲染器<b>相同的矩阵构造</b>复算，避免依赖 MC 渲染类。</p>
 *
 * <p>用法：{@code gradlew runSandboxPanProbe}</p>
 */
public final class SandboxPanProbe {

    private static final float DEFAULT_YAW = 0.65f;
    private static final float DEFAULT_PITCH = 0.95f;
    private static final int AREA = 900;
    private static final int GRID = 256;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘拖动换算验证 ===");
        System.out.printf("视角 yaw=%.2f pitch=%.2f，视口 %dpx，网格 %d%n%n",
                DEFAULT_YAW, DEFAULT_PITCH, AREA, GRID);

        boolean pass = true;
        float[] r = rotationRows(DEFAULT_YAW, DEFAULT_PITCH);

        // ---- 1) 向右拖：中心应向西（x 减小）----
        float zoom = SandboxLod.BASE_ZOOM;
        double step = SandboxLod.effectiveStep(zoom);
        float[] out = new float[2];
        dragToBlocks(r, 100f, 0f, AREA, zoom, 1.0f, GRID, step, out);
        System.out.printf("[1] 向右拖 100px -> 中心位移 (%.1f, %.1f) 格方块%n", out[0], out[1]);
        if (out[0] >= 0f) {
            System.out.println("FAIL: 向右拖应让中心向西（out[0] < 0）");
            pass = false;
        } else {
            System.out.println("    OK（方向正确）");
        }

        // ---- 2) 斜向拖动：两个轴都要动 ----
        dragToBlocks(r, 100f, 100f, AREA, zoom, 1.0f, GRID, step, out);
        System.out.printf("%n[2] 斜向拖 (100,100)px -> (%.1f, %.1f)%n", out[0], out[1]);
        if (Math.abs(out[0]) < 1e-3f || Math.abs(out[1]) < 1e-3f) {
            System.out.println("FAIL: 斜向拖动只动了一个轴");
            pass = false;
        } else {
            System.out.println("    OK（两轴都有位移）");
        }

        // ---- 3) 量级：拖整屏宽 ≈ 视野宽度 ----
        int window = SandboxLod.windowFor(zoom);
        dragToBlocks(r, AREA, 0f, AREA, zoom, 1.0f, GRID, step, out);
        double moved = Math.hypot(out[0], out[1]);
        System.out.printf("%n[3] 拖整屏 %dpx -> 移动 %.0f 格方块（视野 %d 格）%n",
                AREA, moved, window);
        double ratio = moved / window;
        // 收紧判据：拖整屏应移动约"半个视野到一又二分一视野"
        // （旋转视角下地面在屏幕上的投影会缩短，1:1 是不可达的，
        //   但低于 0.5 手感明显偏慢、高于 1.5 会"一拖就飞出去"）
        if (ratio < 0.5 || ratio > 1.5) {
            System.out.printf("FAIL: 量级不合理（%.2f × 视野，应在 0.50~1.50）%n", ratio);
            pass = false;
        } else {
            System.out.printf("    OK（%.2f × 视野）%n", ratio);
        }

        // ---- 4) 缩放感知：推近后同位移 = 更少方块 ----
        float farZoom = SandboxLod.ZOOM_MIN;
        float nearZoom = SandboxLod.ZOOM_MAX;
        double farStep = SandboxLod.effectiveStep(farZoom);
        double nearStep = SandboxLod.effectiveStep(nearZoom);
        dragToBlocks(r, AREA, 0f, AREA, farZoom, 1.0f, GRID, farStep, out);
        double movedFar = Math.hypot(out[0], out[1]);
        dragToBlocks(r, AREA, 0f, AREA, nearZoom, 1.0f, GRID, nearStep, out);
        double movedNear = Math.hypot(out[0], out[1]);
        System.out.printf("%n[4] 同拖整屏：拉远 %.0f 格 vs 推近 %.0f 格%n", movedFar, movedNear);
        if (!(movedNear < movedFar)) {
            System.out.println("FAIL: 推近后同位移应移动更少方块");
            pass = false;
        } else {
            System.out.printf("    OK（推近 %d 倍更精细）%n", (int) (movedFar / Math.max(1, movedNear)));
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** 与 SandboxRenderer.buildRotation 相同的行摊平矩阵。 */
    private static float[] rotationRows(float yaw, float pitch) {
        // M = Rz(0) · Rx(pitch) · Rz(yaw)
        double cz = Math.cos(yaw), sz = Math.sin(yaw);
        double cx = Math.cos(pitch), sx = Math.sin(pitch);
        // Rx * Rz
        double[][] m = {
                {cz, -sz, 0},
                {cx * sz, cx * cz, -sx},
                {sx * sz, sx * cz, cx}
        };
        float[] r = new float[9];
        r[0] = (float) m[0][0]; r[1] = (float) m[0][1]; r[2] = (float) m[0][2];
        r[3] = (float) m[1][0]; r[4] = (float) m[1][1]; r[5] = (float) m[1][2];
        r[6] = (float) m[2][0]; r[7] = (float) m[2][1]; r[8] = (float) m[2][2];
        return r;
    }

    /** 与 SandboxRenderer.dragToBlocks 相同的换算。 */
    private static void dragToBlocks(float[] r, double dx, double dy, int areaSize,
                                     float zoom, float widthScale, int grid, double blockStep,
                                     float[] out) {
        float s = areaSize * 0.46f * zoom;
        if (s <= 0.0f) { out[0] = 0f; out[1] = 0f; return; }
        float a = (float) (dx / s) / widthScale;
        float b = (float) (dy / s) / widthScale;
        float det = r[0] * r[4] - r[1] * r[3];
        float mx, my;
        if (Math.abs(det) < 1.0E-4f) { mx = a; my = b; }
        else {
            mx = (r[4] * a - r[1] * b) / det;
            my = (r[0] * b - r[3] * a) / det;
        }
        int colCount = Math.max(1, grid / 2);
        // 模型跨度 2 覆盖 colCount 根柱，每柱 = PER_COLUMN(=2) 个细格，每细格 = blockStep 方块
        double blocksPerModel = (colCount * 0.5) * 2.0 * blockStep;
        out[0] = (float) (-mx * blocksPerModel);
        out[1] = (float) (-my * blocksPerModel);
    }

    private SandboxPanProbe() { }
}
