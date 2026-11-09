package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;

import java.util.Locale;

/**
 * <b>沙盘拖动换算验证</b>（★ 2026-11-08 建立，★ 2026-11-09 B4 改为调用真实实现）。
 *
 * <h2>要守住的用户诉求</h2>
 * <p>「我还不能移动位置。明明参考项目可以的。」</p>
 * <p>我们是<b>全屏单层地图</b>，没有参考项目"切到区块精读层"的退路，
 * 拖动必须<b>移动世界中心</b>并重新采样。</p>
 *
 * <h2>★ 2026-11-09 B4：不再自己复刻数学</h2>
 * <p>本探针此前<b>自己实现</b>了旋转矩阵与拖动换算（且用的是旧口径
 * {@code s = areaSize * 0.46 * zoom}，与现在的 {@code fitScaleWide} 不一致）——
 * 正是本轮要消灭的"第二套实现"。</p>
 * <p>现在直接调用 {@link SandboxGeometry#dragToBlocks} ——<b>实机跑的同一份函数</b>。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>方向正确</b>：向右拖 ⇒ 中心向西移（x 减小）；</li>
 *   <li><b>斜向可分解</b>：沿对角线拖，两个轴都要有非零位移；</li>
 *   <li><b>量级合理</b>：拖整屏 ≈ 0.50~1.50 × 视野宽度；</li>
 *   <li><b>缩放感知</b>：推近后同样屏幕位移对应更少方块。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxPanProbe}</p>
 */
public final class SandboxPanProbe {

    private static final float DEFAULT_YAW = 0.65f;
    private static final float DEFAULT_PITCH = 0.95f;
    private static final int AREA = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘拖动换算验证（调用 SandboxGeometry.dragToBlocks）===");

        boolean pass = true;
        float[] r = SandboxGeometry.rotationRows(DEFAULT_YAW, DEFAULT_PITCH);
        float zoom = SandboxLod.BASE_ZOOM;
        int window = SandboxLod.windowFor(zoom);
        int grid = SandboxLod.gridFor(zoom);
        int colCount = (grid + SandboxLod.perColumn() - 1) / SandboxLod.perColumn();
        double step = SandboxLod.effectiveStep(zoom);
        float[] out = new float[2];

        System.out.printf("视野 %d 格，细格 %d，柱数 %d，每细格 %.1f 格%n%n",
                window, grid, colCount, step);

        // ---- 1) 向右拖：中心应向西 ----
        SandboxGeometry.dragToBlocks(r, 100, 0, AREA, AREA, 1.0f,
                colCount, SandboxLod.perColumn(), step, out);
        System.out.printf("[1] 向右拖 100px -> 中心位移 (%.1f, %.1f) 格方块%n", out[0], out[1]);
        if (out[0] >= 0f) {
            System.out.println("    FAIL: 向右拖应让中心向西（out[0] < 0）");
            pass = false;
        } else {
            System.out.println("    OK（方向正确）");
        }

        // ---- 2) 斜向拖动：两个轴都要动 ----
        SandboxGeometry.dragToBlocks(r, 100, 100, AREA, AREA, 1.0f,
                colCount, SandboxLod.perColumn(), step, out);
        System.out.printf("%n[2] 斜向拖 (100,100)px -> (%.1f, %.1f)%n", out[0], out[1]);
        if (Math.abs(out[0]) < 1e-3f || Math.abs(out[1]) < 1e-3f) {
            System.out.println("    FAIL: 斜向拖动只动了一个轴");
            pass = false;
        } else {
            System.out.println("    OK（两轴都有位移）");
        }

        // ---- 3) 量级：拖整屏 ≈ 视野宽度 ----
        SandboxGeometry.dragToBlocks(r, AREA, 0, AREA, AREA, 1.0f,
                colCount, SandboxLod.perColumn(), step, out);
        double moved = Math.hypot(out[0], out[1]);
        double ratio = moved / window;
        System.out.printf("%n[3] 拖整屏 %dpx -> 移动 %.0f 格方块（视野 %d 格，%.2f ×）%n",
                AREA, moved, window, ratio);
        if (ratio < 0.5 || ratio > 1.5) {
            System.out.printf("    FAIL: 量级不合理（应在 0.50~1.50）%n");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- 4) 缩放感知 ----
        float farZoom = SandboxLod.ZOOM_MIN;
        float nearZoom = SandboxLod.ZOOM_MAX;
        double farStep = SandboxLod.effectiveStep(farZoom);
        double nearStep = SandboxLod.effectiveStep(nearZoom);
        int farCols = (SandboxLod.gridFor(farZoom) + SandboxLod.perColumn() - 1)
                / SandboxLod.perColumn();
        int nearCols = (SandboxLod.gridFor(nearZoom) + SandboxLod.perColumn() - 1)
                / SandboxLod.perColumn();
        SandboxGeometry.dragToBlocks(r, AREA, 0, AREA, AREA, 1.0f,
                farCols, SandboxLod.perColumn(), farStep, out);
        double movedFar = Math.hypot(out[0], out[1]);
        SandboxGeometry.dragToBlocks(r, AREA, 0, AREA, AREA, 1.0f,
                nearCols, SandboxLod.perColumn(), nearStep, out);
        double movedNear = Math.hypot(out[0], out[1]);
        System.out.printf("%n[4] 同拖整屏：拉远 %.0f 格 vs 推近 %.0f 格%n", movedFar, movedNear);
        if (!(movedNear < movedFar)) {
            System.out.println("    FAIL: 推近后同位移应移动更少方块");
            pass = false;
        } else {
            System.out.printf("    OK（推近 %d 倍更精细）%n", (int) (movedFar / Math.max(1, movedNear)));
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxPanProbe() { }
}
