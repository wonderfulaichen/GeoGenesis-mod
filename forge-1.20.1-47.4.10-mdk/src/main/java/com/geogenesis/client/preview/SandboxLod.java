package com.geogenesis.client.preview;

/**
 * <b>沙盘 LOD（细节层级）</b>—— ★ 2026-11-08。
 *
 * <h2>要解决的问题</h2>
 * <p>修复前沙盘的世界窗口是 {@code texW * scaleBlockPos}（约 900×64 ≈ <b>57600 格</b>），
 * 而采样网格固定 256×256 ⇒ <b>一个细格代表 225 格方块</b>。
 * 河流（宽几格~几十格）、湖泊（几十~几百格）在这个尺度下<b>比一个采样格还小</b>，
 * 必然被抹掉；用户看到的"没放大高清显示、看不到河流湖泊"就是这个。</p>
 *
 * <h2>LOD 的做法：窗口随缩放分档，采样点数恒定</h2>
 * <p>像游戏地图那样：<b>拉远看格局（粗）、推近看细节（细）</b>。
 * 关键是<b>采样点数不变</b>（恒定 {@link ProgressiveSampler#GRID}²，
 * 成本恒定 ~150ms），变的是「每个细格代表多少方块」：</p>
 *
 * <pre>
 *   step = BASE_STEP / 2^level      // 每细格代表的方块数
 *   窗口 = GRID × step               // 世界覆盖范围
 * </pre>
 *
 * <table border="1">
 *   <caption>档位表（BASE_STEP=12，GRID=256）</caption>
 *   <tr><th>level</th><th>step（格/细格）</th><th>窗口（格）</th><th>能看清什么</th></tr>
 *   <tr><td>-1</td><td>24</td><td>6144</td><td>大洲格局</td></tr>
 *   <tr><td><b>0</b></td><td><b>12</b></td><td><b>3072</b></td><td><b>默认：地形类型分布</b></td></tr>
 *   <tr><td>+1</td><td>6</td><td>1536</td><td>山系、大型湖</td></tr>
 *   <tr><td>+2</td><td>3</td><td>768</td><td><b>河流主河道</b></td></tr>
 *   <tr><td>+3</td><td>1.5</td><td>384</td><td>支流、小湖</td></tr>
 *   <tr><td>+4</td><td>0.75</td><td>192</td><td>最细（单格级）</td></tr>
 * </table>
 *
 * <h2>档位量化 = 天然节流</h2>
 * <p>滚轮会连续改 {@code zoom}，若每帧都按 zoom 重算窗口就会每帧重采。
 * 所以这里把 zoom <b>量化到整数档</b>（跨档才变窗口）：
 * 只有 zoom 跨过一档的边界才触发重采，连续微调不会。</p>
 *
 * <h2>为什么用 2 的幂</h2>
 * <p>相邻档的窗口正好差 2 倍 ⇒ 切换时画面内容「放大/缩小一倍」，
 * 视觉上像地图 LOD 的层级切换，不会有突兀的采样密度跳变。</p>
 */
public final class SandboxLod {

    /**
     * 基准档（level 0）每细格代表的方块数。
     *
     * <h2>取值依据（对照参考项目实测比例）</h2>
     * <p>yardsite：窗口 ±2000 格（=4000）、128 根柱子 ⇒ <b>一根柱约 31 格方块</b>。
     * 它的柱子是「2×2 细格」，即细格边长约 15.6 格方块。</p>
     * <p>我们若取 step=6（细格 6 格方块、柱 12 格方块），柱子比参考<b>细 2.6 倍</b>
     * ⇒ 同一座山上出现 2.6 倍多的台阶，视觉上变成"格纹/噪点"，反而看不出地形。</p>
     * <p>⇒ 基准 <b>step = 15.6</b>（对齐参考的细格尺寸）：窗口 = 256×15.6 ≈ 3994 格，
     * 与参考的 4000 格同档，柱子粗细也一致 —— 这才是"看起来像参考项目"的根源。</p>
     */
    public static final double BASE_STEP = 15.6;
    /** 与 level 0 对应的 zoom（沙盘默认缩放，yardsite 值）。 */
    public static final float BASE_ZOOM = 2.0f;
    /**
     * 最粗档 = -1（step 12，窗口 3072）。
     * 再粗下去河流彻底不可见，没有意义。
     */
    public static final int MIN_LEVEL = -1;
    /**
     * 最细档 = <b>+2</b>（step 1.5，窗口 384）。
     *
     * <h2>为什么不能更细（★ 2026-11-08 实测修正）</h2>
     * <p>曾设 +4（step 0.375）。但地形场按<b>方块</b>定义，
     * 采样坐标是整数（{@code (int)Math.round(gx*step)}）⇒
     * <b>step &lt; 1 时相邻细格会落到同一个世界方块</b>，等于<b>重复采样</b>：
     * 4 个细格只有 3 个不同坐标 ⇒ 成片等值的"梯田"伪影
     * （离线出图实测：整幅呈密集同心条纹，地形全被抹平）。</p>
     * <p>⇒ 细档下限 = <b>step ≥ 1 格/细格</b>。1.5 是 2 的幂档里第一个满足的值。</p>
     * <p>这个精度足够看河：主河宽 8 格 ⇒ 5.3 个细格；支流 3 格 ⇒ 2 个细格。</p>
     */
    public static final int MAX_LEVEL = 2;

    private SandboxLod() { }

    /**
     * 当前缩放对应的 LOD 档位。
     *
     * <p>{@code level = round(log2(zoom / BASE_ZOOM))}：zoom 越大档位越高（越细）。
     * 夹在 {@link #MIN_LEVEL}~{@link #MAX_LEVEL}。</p>
     */
    public static int levelFor(float zoom) {
        if (zoom <= 0f) return 0;
        double ratio = zoom / (double) BASE_ZOOM;
        int level = (int) Math.round(Math.log(ratio) / Math.log(2.0));
        if (level < MIN_LEVEL) level = MIN_LEVEL;
        if (level > MAX_LEVEL) level = MAX_LEVEL;
        return level;
    }

    /** 某档每细格代表多少方块。 */
    public static double stepFor(int level) {
        return BASE_STEP / Math.pow(2.0, level);
    }

    /**
     * 某档的世界窗口边长（方块）。
     *
     * @param grid 细格数（{@link ProgressiveSampler#GRID}）
     */
    public static int windowFor(int level, int grid) {
        return Math.max(16, (int) Math.round(grid * stepFor(level)));
    }

    /** 一整圈档位的人类可读摘要（日志/探针用）。 */
    public static String describe(int level, int grid) {
        return String.format(java.util.Locale.ROOT,
                "LOD %d（每细格 %.2f 格方块，窗口 %d 格，网格 %d²）",
                level, stepFor(level), windowFor(level, grid), grid);
    }

    /**
     * 该档是否查<b>粗层水位</b>（画河道/湖面）。
     *
     * <h2>为什么按档决定</h2>
     * <p>粗层成本 635ms/冷 tract、292ms/热 tract（实测 {@code runSandboxCoarseHydroProbe}），
     * 而一个窗口覆盖的 tract 数约为 {@code (ceil(窗口/512)+1)²}：</p>
     * <table border="1">
     *   <caption>档位与水位代价</caption>
     *   <tr><th>档</th><th>窗口</th><th>tract 数</th><th>水位开销</th></tr>
     *   <tr><td>+2</td><td>998</td><td>9</td><td>约 2.6~5.7s</td></tr>
     *   <tr><td>+1</td><td>1997</td><td>25</td><td>约 7~16s</td></tr>
     *   <tr><td>0</td><td>3994</td><td>81</td><td>约 24~51s（不可接受）</td></tr>
     * </table>
     *
     * <p>⇒ 只在<b>最细两档</b>（+1/+2）开水位。这两档正是"推近看河道"的场景，
     * 语义上也对：拉远看格局时不需要逐格水面。</p>
     * <p>粗档没有水位时，湖/海仍按 {@code e<0} 判定（廉价路径本来就有）。</p>
     */
    public static final int COARSE_WATER_MIN_LEVEL = 1;

    /** 该档是否查粗层水位。 */
    public static boolean usesCoarseWater(int level) {
        return level >= COARSE_WATER_MIN_LEVEL;
    }

    /**
     * 该档是否要走 <b>水文管线</b>（{@code sampleCell}，带河谷雕刻与湖河数据）。
     *
     * <h2>★ 实测结论：沙盘不走水文管线（2026-11-08）</h2>
     * <p>量过两条路的成本（{@code runSandboxHydroCostProbe}）：</p>
     * <ul>
     *   <li>{@code sampleCellCoarse}：<b>不做河谷雕刻、不生成河网</b>
     *       ⇒ 看不见河（但湖泊/海由 {@code e<0} 判定，<b>能看到</b>）；</li>
     *   <li>{@code sampleCell}（完整管线）：有水文，但<b>每个新水文 region 要 3.6~10.9 秒</b>
     *       （实测 {@code [HYDRO-MODEL] region solved in 10862 ms}），
     *       哪怕窗口缩到 6×6 区块，65536 个采样点仍会反复触发 ⇒ 分钟级，不可用。</li>
     * </ul>
     *
     * <p>⇒ 沙盘统一走廉价路径。河网的呈现方式是
     * <b>{@code RIVER_NETWORK} 图层</b>（流量场，coarse 路径有值），
     * 以及最粗档看大湖与海岸线。若将来要"细档看河道水面"，
     * 前提是水文侧提供<b>不建 region 的廉价水位查询</b> —— 目前没有。</p>
     */
    public static boolean usesHydroPath(int level) {
        return false;
    }
}
