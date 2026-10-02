package com.geogenesis.worldgen.hydrology.model;

/**
 * 出图规格的<b>唯一来源</b>（避免各探针各写一套导致尺寸/尺度漂移）。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>此前多个探针各自写死 {@code radius} 与 {@code step}，结果：</p>
 * <ul>
 *   <li>{@code step=4} → 4 方块/像素 ⇒ 视野只有正典的 1/16、画面发糊、河线被画粗成"凸起"；</li>
 *   <li>{@code radius=400} → 801×801，与用户熟悉的正典图尺寸不符。</li>
 * </ul>
 * <p>用户已明确指出这些是退步。本类把这些数固定为常量，探针只准引用、不准自写。</p>
 *
 * <h2>正典规格（与项目既有预览 {@code build/hydropreview/preview.png} 一致）</h2>
 * <ul>
 *   <li>{@code STEP = 1} —— <b>1 像素 = 1 方块</b>；</li>
 *   <li>{@code RADIUS = 700} —— 半径 700 块 ⇒ 图像 <b>1401×1401</b>；</li>
 *   <li>默认中心 {@code (-140, 137)}、seed {@code 9139912035078620160}。</li>
 * </ul>
 *
 * <h2>山体阴影口径</h2>
 * <p>NW 光源 {@code (-0.5, 0.7, 0.51)}，法线 {@code (-dx, 1, -dz)}，
 * 亮度 {@code 60 + 175·max(0, d)} —— 与 {@code HydroPreviewProbe.shade} 逐项相同。
 * 色调：海 {@code 0x1F4E79}、湖 {@code 0x4A90E2}、河 {@code 0x00E5D0}/{@code 0x00C8B4}、
 * 未决 {@code 0xC030C0}。</p>
 */
public final class ViewSpec {

    /** 1 像素 = 1 方块。 */
    public static final int STEP = 1;
    /** 半径（块）⇒ 图像边长 = 2·RADIUS + 1 = 1401。 */
    public static final int RADIUS = 700;
    public static final long SEED = 9139912035078620160L;
    public static final int CENTER_X = -140;
    public static final int CENTER_Z = 137;

    public static final int SEA_COLOR = 0x1F4E79;
    public static final int LAKE_COLOR = 0x4A90E2;
    public static final int RIVER_COLOR = 0x00E5D0;
    public static final int RIVER_WIDE_COLOR = 0x00C8B4;
    public static final int UNRESOLVED_COLOR = 0xC030C0;

    /** 图像边长。 */
    public static int size() { return 2 * RADIUS + 1; }

    /** 正典山体阴影（NW 光源）。{@code h} 为 row-major 的 {@code n×n} 高程数组。 */
    public static int shade(double[] h, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (h[i1 * n + j] - h[i0 * n + j]) / (i1 - i0);
        double dz = (h[i * n + j1] - h[i * n + j0]) / (j1 - j0);
        double nx = -dx, ny = 1, nz = -dz;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lx = -0.5, ly = 0.7, lz = 0.51;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double d = (nx * (lx / ll) + ny * (ly / ll) + nz * (lz / ll)) / nl;
        int g = (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d)));
        return (g << 16) | (g << 8) | g;
    }

    private ViewSpec() { }
}
