package com.geogenesis.client.preview;

/**
 * <b>沙盘尺度规格</b> —— ★ 2026-11-08 定稿（按参考项目结构，1 柱 ≈ 16 格方块）。
 *
 * <h2>用户裁定（原话）</h2>
 * <blockquote>
 * ①「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。」<br>
 * ②「这个预览功能其实就可以当成我们在外部核心层（地形建模软件）的视图。
 *    这样在这里跑的计算后续可以直接应用到进入世界加载中。」<br>
 * ③「既然这样其实我们改成按参考项目 16x16 方块吧。其他细节你看参考项目。」
 * </blockquote>
 *
 * <h2>★ 三层数据结构（照参考项目 {@code SiteMapSampler}，别再混为一谈）</h2>
 * <pre>
 *   columns  柱子数（高度点数）       默认 128
 *   fine     细格数 = columns × 2    默认 256      ← {@code HEIGHT_STRIDE = 2}
 *   step     相邻高度点的方块距       默认 16
 *   窗口      columns × step         默认 2048 格
 *   每柱      step 格方块 ≈ 16        ← 用户裁定的"16×16 方块"
 * </pre>
 *
 * <p><b>关键</b>：{@code fine} 只由 {@code columns × HEIGHT_STRIDE} 决定，
 * <b>与窗口方块数无关</b>。参考项目源码 {@code SiteMapSampler} 第 74-77 行：</p>
 * <pre>
 *   高度网格间隔（细格）：每 2 格采一个高度，一根柱子正好盖 2×2 细格
 *   ⇒ 柱子数和高度点数是同一个数，细格数是它们的 2 倍
 * </pre>
 *
 * <h2>★ 我此前的致命错误（直接导致 OOM 崩溃）</h2>
 * <p>我把 {@code gridFor()} 写成 {@code windowFor()}（2048）⇒
 * 每帧分配 2048×2048 = <b>419 万</b>个格子的十几个数组
 * ⇒ {@code OutOfMemoryError: Java heap space}（crash-2026-11-09_03.26.03 已取证）。</p>
 * <p>正确：{@code gridFor() = pillarsFor() × HEIGHT_STRIDE = 128 × 2 = 256}，
 * 3 个数量级之差。</p>
 *
 * <h2>参考项目常数对照</h2>
 * <table border="1">
 *   <tr><th>项</th><th>参考项目</th><th>本项目</th></tr>
 *   <tr><td>柱子数基准</td><td>{@code COLUMNS_MIN = 128}</td><td>128</td></tr>
 *   <tr><td>柱子数上限</td><td>{@code COLUMNS_HARD_MAX = 512}</td><td>512</td></tr>
 *   <tr><td>{@code HEIGHT_STRIDE}</td><td>2</td><td>2</td></tr>
 *   <tr><td>每柱方块（{@code step}）</td><td>≈31（±2000 窗口）</td><td><b>16</b>（用户裁定）</td></tr>
 *   <tr><td>窗口</td><td>4000</td><td>128×16 = <b>2048</b></td></tr>
 *   <tr><td>细格</td><td>256</td><td><b>256</b></td></tr>
 *   <tr><td>坡度分母</td><td>{@code 2×step×2 ≈ 124}</td><td>{@code 2×16 = 32}</td></tr>
 *   <tr><td>坡度增益 / 光照范围</td><td>0.55 / clamp(0.55, 1.30)</td><td>同</td></tr>
 *   <tr><td>水面衰减 / RELIEF / WATER_SINK</td><td>0.35 / 0.40 / 0.05</td><td>同</td></tr>
 * </table>
 *
 * <h2>错误史（十版，别再重犯）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>错法</th><th>后果</th></tr>
 *   <tr><td>1</td><td>柱数 ×2 且每柱 ÷2</td><td>窗口恒定，滚轮无反应</td></tr>
 *   <tr><td>2</td><td>每柱 75 格</td><td>柱子过粗</td></tr>
 *   <tr><td>3</td><td>每柱随缩放变</td><td>违背柱宽恒定</td></tr>
 *   <tr><td>4</td><td>独立 {@code ProgressiveSampler} 二次采样</td><td>数据源错</td></tr>
 *   <tr><td>5</td><td>perColumn=1（一柱=一方块）</td><td>450 柱 × 1.8px，地形糊成纹理</td></tr>
 *   <tr><td>6</td><td>perColumn=horizontalScale（2）</td><td>窗口仅 256 格</td></tr>
 *   <tr><td>7</td><td>用归一化 r 算坡度光照</td><td>亮度差 0.003 ⇒ 整幅均匀绿</td></tr>
 *   <tr><td>8</td><td><b>gridFor = windowFor（2048）</b></td><td><b>OOM 崩溃</b></td></tr>
 *   <tr><td>9</td><td>scale = areaSize×0.92</td><td>超出视口 2.6 倍 ⇒ 只看到局部缓坡</td></tr>
 *   <tr><td>10</td><td>圆形盘面裁剪</td><td>大沙盘应为方形铺满</td></tr>
 * </table>
 */
public final class SandboxLod {

    // ===== 三层结构（照参考项目）=====

    /** 细格 / 柱（每边）—— 参考项目 {@code SiteMapSampler.HEIGHT_STRIDE = 2}。 */
    public static final int HEIGHT_STRIDE = 2;

    /** 每柱覆盖的方块数（每边）—— 用户裁定 16。 */
    public static final int STEP_BLOCKS = 16;

    /**
     * 基准柱数（每边）—— ★ v26 由 128 改为 <b>256</b>。
     *
     * <p>用户 2026-11-10：「这个缩放太大了。4096 格视野都已经够了。」</p>
     * <p>256 柱 × 16 方块 = <b>4096 格</b>视野，正好是用户要的默认档；
     * 原先 128 柱 = 2048 格，柱子显得过大、看到的区域太小。</p>
     */
    public static final int BASE_PILLARS = 256;

    /** 柱数硬上限 —— 参考项目 {@code COLUMNS_HARD_MAX = 512}。 */
    public static final int MAX_PILLARS = 512;

    // ★ v66：BASE_ZOOM 已删除（缩放统一到 scaleBlockPos，沙盘无独立档位）。

    // ★ v66：WHEEL_STEP / MAX_ZOOM_*_STEPS / ZOOM_MIN / ZOOM_MAX 已删除
    //   —— 缩放统一到 scaleBlockPos 后，沙盘不再有自己的 zoom 档位。

    /** 柱子最小屏幕尺寸（像素）。 */
    public static final double MIN_PILLAR_PX = 2.0;

    private SandboxLod() { }

    /** 每柱细格数（每个方向）= {@link #HEIGHT_STRIDE}。 */
    public static int perColumn() {
        return HEIGHT_STRIDE;
    }

    /** 每柱方块数（恒定 16）。 */
    public static double blocksPerPillar() {
        return STEP_BLOCKS;
    }

    /**
     * <b>柱子数（每边）</b> —— 由<b>窗口</b>和<b>每柱方块数</b>反推。
     *
     * <h2>★ v27：改为"窗口决定柱数"（数据源统一的必然结果）</h2>
     * <p>此前是 {@code BASE_PILLARS / zoomRatio} —— 柱数由 zoom 档位独立决定，
     * 与世界窗口脱钩 ⇒ 窗口 4096 格、平面只采了 900 格 ⇒ 95% 无数据。</p>
     *
     * <p>用户：「我的想法是数据源统一，这样能保证计算复用。
     * 除非我们需要全局预览才不进行高精度计算复用」</p>
     *
     * <p>现在沙盘窗口 = 平面窗口，柱数 = 窗口 / 每柱方块，
     * 夹在 [8, 硬上限] 并保证偶数（{@code HEIGHT_STRIDE} 要求）。</p>
     *
     * @param windowBlocks 世界窗口（方块，每边）
     */
    /**
     * <b>柱子数（每边）</b> —— 由<b>窗口</b>反推，目标柱屏宽 ~8px（对齐参考项目）。
     *
     * <h2>★ v29：不再固定「1 柱 = 16 格」</h2>
     * <p>v27~v28 用 {@code windowBlocks / STEP_BLOCKS(16)} 推柱数：
     * 窗口 900 格 ⇒ 只有 <b>56 柱</b> ⇒ 每柱 16px ⇒ 柱子又粗又方，
     * 与参考项目（128 柱 / 7.8px）观感差很远
     * —— 用户：「视觉效果对比参考项目还是差老远了」。</p>
     *
     * <p>参考项目的柱数由 <b>COLUMNS_MIN=128 起、按半径翻倍升档</b> 决定，
     * <b>不是</b>由"每柱多少格"固定。它 4000 格窗口 / 128 柱
     * ⇒ 每柱 ≈ 31 格。所以"每柱 16 格"从来不是参考项目的约束。</p>
     *
     * <p>⇒ 改成：柱数 = 窗口 / (窗口 / 目标柱数)，
     * 目标柱数按<b>柱屏宽 ~8px</b> 与<b>窗口大小</b>共同决定，
     * 每柱方块数随窗口浮动（窗口小 ⇒ 每柱少格，保证柱数够密）。</p>
     *
     * @param windowBlocks 世界窗口（方块，每边）
     * @param viewportPx   视口边长（像素），决定"一屏该有多少柱"
     */
    public static int pillarsForWindow(int windowBlocks, int viewportPx) {
        // 目标柱数：一屏约 viewportPx/8 根（参考项目 900/7.8 ≈ 115，取 128 档）
        int wantPx = Math.max(32, viewportPx / 8);
        // 但柱数不能超过窗口能提供的信息量：每柱至少 2 格方块才有意义
        int maxByWindow = Math.max(8, windowBlocks / 2);
        int p = Math.min(wantPx, maxByWindow);
        // 与参考项目一样取偶数档（HEIGHT_STRIDE 要求），并夹在硬上限内
        p = Math.min(MAX_PILLARS, Math.max(8, p));
        return p - (p & 1);
    }

    /** 兼容重载（900px 视口）。 */
    public static int pillarsForWindow(int windowBlocks) {
        return pillarsForWindow(windowBlocks, 900);
    }

    /**
     * <b>每柱覆盖多少方块</b>（★ v29：随窗口浮动，不再固定 16）。
     *
     * <p>窗口 900 格 / 128 柱 ≈ 7 格/柱；窗口 4096 / 128 柱 ≈ 32 格/柱。
     * 参考项目就是后者（1 柱 ≈ 31 格）。</p>
     */
    public static double blocksPerPillarFor(int windowBlocks, int viewportPx) {
        int p = pillarsForWindow(windowBlocks, viewportPx);
        return windowBlocks / (double) Math.max(1, p);
    }

    /** 兼容重载。 */
    public static double blocksPerPillarFor(int windowBlocks) {
        return blocksPerPillarFor(windowBlocks, 900);
    }

    /**
     * <b>每细格方块数</b>（★ v29：由窗口与柱数共同决定）。
     *
     * <p>{@code = windowBlocks / (柱数 × HEIGHT_STRIDE)}。
     * 例：窗口 900 / 128 柱 / 2 = 3.5 格/细格。</p>
     */
    public static double effectiveStepFor(int windowBlocks, int viewportPx) {
        int p = pillarsForWindow(windowBlocks, viewportPx);
        return windowBlocks / (double) Math.max(1, p * HEIGHT_STRIDE);
    }

    /** 兼容重载。 */
    public static double effectiveStepFor(int windowBlocks) {
        return effectiveStepFor(windowBlocks, 900);
    }

}
