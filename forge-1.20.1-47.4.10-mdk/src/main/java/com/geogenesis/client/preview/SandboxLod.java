package com.geogenesis.client.preview;

/**
 * <b>沙盘比例尺（连续 LOD）</b>—— ★ 2026-11-08 重做。
 *
 * <h2>为什么要重做（上一版的错）</h2>
 * <p>上一版把 LOD 做成<b>离散档位</b>（{@code level = round(log2(zoom/2))}，
 * 只有 -1/0/+1/+2 四档）。实机结果是：滚轮一直在放大，但<b>档位长期停在某一档</b>
 * —— 实测 zoom 从 2.0 滚到 30，LOD 只走 0 → +1 → +2 三步，
 * 后面 <b>13 格滚轮全停在 998 格窗口</b>。用户看不到"比例尺变化"，
 * 因为离散档位把连续的缩放切成了几段台阶。</p>
 *
 * <h2>用户要的是什么</h2>
 * <p>原话：「这个其实也类似在用高德地图软件看地图的效果，比例尺会变化。」
 * ⇒ <b>连续缩放</b>：滚动滚轮时，覆盖范围平滑变化，同时画面上的
 * <b>比例尺读数</b>跟着变（"一格 = N 格方块"）。不是跳档。</p>
 *
 * <h2>连续 LOD 的做法</h2>
 * <p>关键认识：<b>LOD 不需要离散档位</b>。设</p>
 * <pre>
 *   每细格方块数 = BASE_STEP / zoomRatio      // zoomRatio = zoom / BASE_ZOOM
 *   窗口         = GRID × 每细格方块数          // GRID 恒定 ⇒ 采样点数恒定 ⇒ 成本恒定
 * </pre>
 * <p>于是 zoom 连续变化 ⇒ 窗口连续变化 ⇒ <b>精度连续变化</b>，
 * 而采样点数始终 256²，成本不随缩放增长。这正是地图软件的做法。</p>
 *
 * <h2>唯一仍离散的两件事（必须保留）</h2>
 * <ol>
 *   <li><b>窗口量化到整数格</b>：避免每滚一格就重采（{@link #windowForContinuous}）。</li>
 *   <li><b>是否查水文</b>：粗层水量按 tract 求解（635ms/个），
 *       窗口越大 tract 越多 ⇒ 只在细档开（{@link #usesCoarseWater}）。</li>
 * </ol>
 */
public final class SandboxLod {

    /**
     * 基准档（zoom = {@link #BASE_ZOOM}）每细格代表的方块数。
     *
     * <p>对齐参考项目比例：它 ±2000 格 /128 柱 ⇒ 一根柱约 31 格方块
     * ⇒ 细格（柱 = 2×2 细格）约 <b>15.6</b> 格。</p>
     */
    public static final double BASE_STEP = 15.6;

    /** 与 {@link #BASE_STEP} 对应的 zoom（沙盘默认缩放）。 */
    public static final float BASE_ZOOM = 2.0f;

    /**
     * 最粗档对应的 zoom 下限（滚轮拉到 0.6 时窗口最多 2.5 倍）。
     * 再粗河湖就彻底不可见。
     */
    public static final float ZOOM_MIN = 0.6f;

    /**
     * 最细档对应的 zoom 上限。
     *
     * <p>★ 硬约束：<b>每细格 ≥ 1 格方块</b>。地形按方块定义、采样坐标取整，
     * step &lt; 1 会让相邻细格落到同一个方块 = 重复采样，整幅呈梯田伪影
     * （实测：曾允许 step=0.375，出图全是同心条纹）。</p>
     * <p>step = 15.6/zoomRatio ≥ 1 ⇒ zoomRatio ≤ 15.6 ⇒ zoom ≤ 31.2。</p>
     */
    public static final float ZOOM_MAX = 31.2f;

    private SandboxLod() { }

    /** zoom → 相对基准的倍率（&gt;1 = 更细）。 */
    public static double zoomRatio(float zoom) {
        double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        return z / (double) BASE_ZOOM;
    }

    /**
     * 该 zoom 下每细格代表的方块数（<b>连续</b>，随缩放平滑变化）。
     *
     * <p>下限夹在 1.0（见 {@link #ZOOM_MAX} 的说明）。</p>
     */
    public static double stepFor(float zoom) {
        double step = BASE_STEP / zoomRatio(zoom);
        return Math.max(1.0, step);
    }

    /**
     * 该 zoom 下的世界窗口边长（方块），量化为 16 的倍数。
     *
     * <p>量化理由：滚轮每格 1.12 倍，若每格都改窗口就会每格重采一次
     * （256² 采样约 150ms，连着滚会卡）。量化到 16 格后，
     * 小幅滚动沿用同一窗口，跨过 16 格才重采。</p>
     *
     * @param grid 细格数（{@link ProgressiveSampler#GRID}）
     */
    public static int windowFor(float zoom, int grid) {
        double raw = grid * stepFor(zoom);
        int q = (int) (Math.round(raw / 16.0) * 16);
        return Math.max(256, q);
    }

    /**
     * 该 zoom 下实际采用的分辨率（每细格方块数）—— 与 {@link #windowFor} 一致。
     *
     * <p>因为窗口被量化到 16，实际精度 = 窗口/GRID，而不是 {@link #stepFor} 的原值。
     * 比例尺显示<b>必须</b>用这个值，否则读数与画面不符。</p>
     */
    public static double effectiveStep(float zoom, int grid) {
        return windowFor(zoom, grid) / (double) grid;
    }

    /**
     * 比例尺文字（像地图软件那样显示"一格 = N 方块"）。
     *
     * @param zoom 当前缩放
     * @param grid 细格数
     */
    public static String scaleLabel(float zoom, int grid) {
        double step = effectiveStep(zoom, grid);
        int window = windowFor(zoom, grid);
        if (step >= 10.0) {
            return String.format(java.util.Locale.ROOT,
                    "比例尺：1 细格 = %.0f 格方块（视野 %d 格）", step, window);
        }
        return String.format(java.util.Locale.ROOT,
                "比例尺：1 细格 = %.1f 格方块（视野 %d 格）", step, window);
    }

    /**
     * 该 zoom 下窗口覆盖的 tract 数（决定粗层水位是否可负担）。
     *
     * <p>tract=512，{@code (ceil(窗口/512)+1)²}。</p>
     */
    public static int tractsFor(float zoom, int grid) {
        int window = windowFor(zoom, grid);
        int across = (int) Math.ceil(window / 512.0) + 1;
        return across * across;
    }

    /**
     * 该 zoom 是否查<b>粗层水位</b>（画河道/湖面）。
     *
     * <p>粗层 635ms/冷 tract；窗口 998 格需 9 个 tract（约 2.6s 可接受），
     * 窗口 4000 格需 81 个（约 24s 不可接受）。
     * ⇒ 只在小窗口（≤1600 格，即 ≤ 25 tract）时开。</p>
     */
    public static boolean usesCoarseWater(float zoom, int grid) {
        return tractsFor(zoom, grid) <= 25;
    }

    /**
     * 是否走完整水文管线（{@code sampleCell}）。
     *
     * <p><b>恒 false</b>：实测每个新 region 3.6~10.9 秒，沙盘不可用。
     * 河道水面由粗层水位提供（{@link #usesCoarseWater}）。
     * 详见 {@link SandboxWaterLookup} 的类注释。</p>
     */
    public static boolean usesHydroPath(float zoom) {
        return false;
    }

    /** 人类可读摘要（日志用）。 */
    public static String describe(float zoom, int grid) {
        return scaleLabel(zoom, grid);
    }

    /**
     * 滚轮步长：<b>远快近慢</b>（★ 2026-11-08，用户选定的 Q1-C 方案）。
     *
     * <h2>为什么需要变速</h2>
     * <p>固定 1.12 倍/步时，从 zoom 2.0 滚到 31.2 需要 <b>24 步</b>，
     * 而每步窗口只缩 11% ⇒ 画面"看起来没变化"（用户实机反馈：
     * "我滚动调整缩放 lod 精度没变化"）。但若全部加大步长，近处又调不准。</p>
     *
     * <p>⇒ 远处大步、近处小步：</p>
     * <ul>
     *   <li>zoom &lt; 4（广域）：<b>1.30 倍/步</b> —— 快速跨过"格局"层；</li>
     *   <li>4 ≤ zoom &lt; 12（中景）：<b>1.20 倍/步</b>；</li>
     *   <li>zoom ≥ 12（近景）：<b>1.12 倍/步</b> —— 精细调整。</li>
     * </ul>
     * <p>实测：从 2.0 到 31.2 的步数由 24 降到约 <b>12</b>，
     * 前几步就能看到明显变化，而近景仍可细调。</p>
     *
     * @param zoom    当前缩放
     * @param zoomIn  true = 滚轮向上（推近）
     * @return 新的 zoom（已夹在 [ZOOM_MIN, ZOOM_MAX]）
     */
    public static float nextZoom(float zoom, boolean zoomIn) {
        float step;
        if (zoom < 4.0f) step = 1.30f;
        else if (zoom < 12.0f) step = 1.20f;
        else step = 1.12f;
        float next = zoomIn ? zoom * step : zoom / step;
        return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next));
    }
}
