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
 * 未决 {@code 0xC030C0}、<b>溢流出口 {@code 0xFFFFFF}（白点，2026-10-05 新增）</b>。</p>
 */
public final class ViewSpec {

    /** 1 像素 = 1 方块。 */
    public static final int STEP = 1;
    /**
     * 半径（块）⇒ 图像边长 = 2·RADIUS + 1 = 1401。
     *
     * <p>⚠ <b>不要改这个值</b>。用户明确要求过渲染图"固定这样就好"。
     * 本轮曾把它 700→1000（我误判为"用户要求放大"），用户随即指出
     * "这个预览渲染图怎么又被改面积了" ⇒ <b>已回退到 700</b>。
     * 历史教训见 {@code HANDOFF-ENV.md} §2「正典规格」：700 = 1401×1401 是定稿值，
     * 改它会让新旧图不可比，也会让照文档传参的人对不上。</p>
     */
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

    /**
     * ★ 断言半径是正典值，防止"随手传小窗"污染跨图比较。
     *
     * <p><b>缘起</b>（用户指出："为什么你这渲染图老是变化呢"）：
     * 本轮我用 {@code r=300} 的图去印证 {@code r=700} 的结论，
     * 而两者<b>色标自适应各自的窗口</b> ⇒ 灰度含义不同 ⇒ 结论无效。
     * 历史教训已写过三次（944→272 块那次也源于传错半径），此处改为<b>硬断言</b>。</p>
     *
     * @param strict true = 非正典值直接抛异常（诊断结论类探针应开启）；
     *                false = 只打印警告（探索性小窗允许）
     */
    public static void assertCanonicalRadius(int radius, boolean strict) {
        if (radius == RADIUS) return;
        String msg = String.format(
                "半径 %d 非正典值（ViewSpec.RADIUS=%d）。%n"
                + "  后果：色标按各自窗口自适应 ⇒ 两张图的灰度含义不同 ⇒ 【不可互相印证】。%n"
                + "  若确为快速探索，请在结论中写明窗口，并改用 %s（固定色标）。",
                radius, RADIUS, "ViewSpec.contourFixed");
        if (strict) throw new IllegalArgumentException(msg);
        System.out.println("[WARN] " + msg);
    }

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

    /**
     * <b>★ 正典等高线色标：固定档距 + 固定下界</b>（2026-10 修正）。
     *
     * <p><b>为什么必须固定</b>（用户指出："为什么你这渲染图老是变化呢"）：
     * 此前各探针用 {@code band = (hMax - hMin) / 24}，即<b>按本图自身高程范围</b>自动缩放
     * ⇒ 同一片地形，窗口不同则色彩映射不同 ⇒ <b>两张图的同一种灰度不代表同一高程</b>
     * ⇒ 无法跨图比较，任何"两张图看起来不一样"的结论都不可靠。</p>
     *
     * <p>⇒ 改为<b>绝对高程</b>分档：下界固定 {@value #BAND_BASE} 块、
     * 档距固定 {@value #BAND_BLOCKS} 块，<b>与窗口无关</b>。
     * 这样任意两张图（任意 radius / 任意 seed）的灰度可逐格对比。</p>
     *
     * <p>⚠ 与 {@link #contour} 的旧签名并存是<b>有意的</b>：
     * 旧签名保留给"需要看该图自身完整动态范围"的用途，
     * 但<b>凡是要跨图比较，必须用本方法的固定色标</b>。</p>
     */
    public static final double BAND_BLOCKS = 8.0;
    public static final double BAND_BASE = 0.0;

    /** 正典固定色标的等高线着色（跨图可比）。 */
    public static int contourFixed(double[] h, int n, int i, int j) {
        return contour(h, n, i, j, BAND_BASE, BAND_BLOCKS);
    }

    /**
     * <b>★ 无光照等高线着色</b>（2026-10 新增，与 {@link #shade} 并存，默认用本法）。
     *
     * <p><b>为什么必须有无光照版本</b>：{@link #shade} 是 NW 光源 hillshade，
     * <b>方向性光照会把【谷画成脊】</b> —— 亮的一侧是向光坡、暗的一侧是背光坡，
     * 与"高/低"无关。本项目此前已因此误判过两次：
     * ① 误以为"河流走在山脊上"（实为走在谷底，
     *    {@code RiverProfileProbe} 实测 99.9% 河列在谷、0.1% 在脊）；
     * ② 用户看到渲染图后反问"你这渲染图地形怎么反过来了"。
     * 本项目铁律：<b>判定形态/伪影的最终依据是渲染图</b> ——
     * 若渲染方式本身有系统性误导，则该依据失效。</p>
     *
     * <p>本法只用<b>高程分档</b>（每 {@code bandBlocks} 块一档），亮暗即真实高低，
     * 无任何方向性成分 ⇒ 不会把谷画成脊。</p>
     *
     * <p>⚠ 本方法不自带色标作用域：{@code min}/{@code bandBlocks} 若由调用方
     * <b>按图自适应</b>给出，则跨图不可比（见上方 {@link #BAND_BLOCKS} 的说明）。
     * 跨图比较请用 {@link #contourFixed}。</p>
     *
     * @param h        row-major 的 n×n 高程数组
     * @param min      着色区间的下界（<b>跨图比较时须固定</b>，见 {@link #contourFixed}）
     * @param bandBlocks 每档对应的高程跨度（块）
     */
    public static int contour(double[] h, int n, int i, int j, double min, double bandBlocks) {
        int band = (int) ((h[i * n + j] - min) / bandBlocks);
        // 8 档循环 + 亮度渐变：高处更亮，且每 8 档重复一次以适应大高差
        int g = (int) Math.max(0, Math.min(255, 30 + 225 * ((band & 7) / 7.0)));
        return (g << 16) | (g << 8) | g;
    }

    private ViewSpec() { }
}
