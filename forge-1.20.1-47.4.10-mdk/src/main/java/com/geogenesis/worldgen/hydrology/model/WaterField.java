package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.HeightCurve;

/**
 * <h1>闭式水位场 W(x,z) —— 跨 region 逐位一致的水位</h1>
 *
 * <p><b>来源</b>：本类自 B 线（worktree `fix/continuous-river-coherence`）的
 * {@code d529365`（2026-09-19，水系重构 T1.7）原样移植，仅改包路径
 * （`hydrology` → `hydrology.model`）。</p>
 *
 * <h2>要解决的问题（★ 与本轮遇到的是同一个）</h2>
 * <p>实测（`runLakeEdgeProbe`）：priority-flood 的溢流高程 {@code filledAt}
 * 在相邻 region 重叠区 <b>99.6% 不一致、最大差 21.65 block</b>
 * —— **即便已给 50% halo（320wu）仍 99.6%**。
 * 根因：{@code filledAt} 是<b>全局量</b> —— 水位取决于"水最终排到哪里"，
 * 那可能在天涯海角 ⇒ <b>逐区块复现不出来</b>；加宽窗口只是把同样的错误推到更远的边界。</p>
 *
 * <p>⚠ 本轮（2026-10-07~09）我在 model 链上反复调 `HALO`（96→512）+
 * 加 `inlandResolved`，**重走了这条已被证伪的路** —— 因为当时没查 git 历史。
 * 本条实测（0/1458 vs 1456/1458）是决定性的：**halo 治不了，必须换闭式**。</p>
 *
 * <p>⇒ 改走参考 FreeTerraForged 的路线：<b>水位 = 逐格闭式函数</b>
 * （FTF `ContinentalHydrology.getComplexWaterHeight` 同思路）。</p>
 *
 * <h2>公式（由实测反推，不是拍出来的）</h2>
 * <pre>
 *   h(wx,wz) = heightFromE(eField.eAt(wx,wz))       // 逐点地形高度
 *   hSmooth  = 低通(h, smoothWu)                     // ★ 全球对齐粗格 + 双线性
 *   W        = max( seaLevelY, hSmooth + offset )    // ★ "海是根"由 max 保证
 * </pre>
 *
 * <h3>为什么是"低通表面 − 偏置"</h3>
 * <p>实测标定表（`WaterFieldProbe` [2]，81×81 @ 8wu，seed 9139912035078620160）：</p>
 * <pre>
 *   smoothWu  offset   水格%   分量数  最大分量%
 *        16    -1.0    0.7%     19     15.9%   ← 最碎
 *        24    -0.5    2.5%     15     53.9%   ← ★ 选定默认
 *        24    -1.0    1.5%     10     31.6%
 *        32    -1.0    2.7%      9     27.5%
 *        48     0.0    4.5%      6     81.0%
 *        64     0.0    5.7%      4     91.2%
 *       256    -1.0   18.6%      1    100.0%   ← 平均范围太大 ⇒ 抹平洼地 ⇒ 一整片海
 * </pre>
 * <p><b>两个旋钮各管一件事：</b></p>
 * <ul>
 *   <li>{@code smoothWu} = <b>湖的粒度</b>（16→64：分量 25→4，最大分量 26%→91%）；</li>
 *   <li>{@code offset} = <b>水量</b>（0→−1：水格 4.2%→2.7%）。</li>
 * </ul>
 * <p>⇒ 物理意义：{@code W = 局部平均地面 − |offset|} ⇒ <b>只有比周围平均低
 * {@code |offset|} 块的洼地才积水</b> —— 这正是"湖"的定义。</p>
 *
 * <h3>为什么必须带低通（否则是"等高带"）</h3>
 * <p>若 {@code W} 只是逐点 e 的函数（{@code W = f(e)}），则 {@code h &lt; W} 的解集恒为
 * {@code { e &lt; e* }} —— <b>一条等高带</b>，只能造"海"，造不出盆地湖
 * （盆地湖的水位由该盆地的溢出口决定，那是非局部信息）。
 * 实测（[6]）：地形相对低通表面的残差 p90 仅 1.8~5.7 块且 std 随尺度暴涨
 * ⇒ 起伏是"噪声式"的、不是"盆地式"的 ⇒ 必须靠低通引入<b>非局部</b>信息。</p>
 *
 * <h2>不变式（硬约束）</h2>
 * <ol>
 *   <li><b>闭式</b>：W 只依赖 (wx,wz) ⇒ 跨 region <b>逐位一致</b>
 *       （粗格格点取 {@code k·smoothWu}（世界坐标），与 region 无关）；</li>
 *   <li><b>海是根</b>：{@code hSmooth + offset ≤ seaLevelY} ⇒ {@code W = seaLevelY}；</li>
 *   <li><b>平坦 = 湖</b>：低通面在洼地处近似水平 ⇒ 湖面平
 *       （对齐 RTF 的 {@code flatnessFactor} 语义：平坦不是缺陷，是湖）。</li>
 * </ol>
 *
 * <p>⚠ <b>本类当前无人调用</b>（移植时保持"零行为变更"）——
 * 接线见 {@link HydrologyPipeline} 的 {@code LAKE_FIELD_MODE} 开关。</p>
 */
public final class WaterField {

    /** 归一化高程场（生产同口径，如 {@code CellGenerator::terrainEQuick}）。 */
    @FunctionalInterface
    public interface ElevationField {
        double eAt(double wx, double wz);
    }

    /**
     * 默认低通尺度（wu）—— 实测选定（水格 2.5%、15 个湖、最大分量 54%）。
     *
     * <p>⚠ 该值是原提交（`d529365`）在 **`step=8` wu 采样、legacy 链口径**下扫出来的。
     * 移植到 model 链后【必须重新标定】—— 见 {@code LAKE_FIELD_MODE} 的接线说明。</p>
     */
    public static final double DEFAULT_SMOOTH_WU = 24.0;

    /**
     * 默认水面偏置（块）。
     *
     * <p><b>取值依据</b>：原 `WaterFieldProbe` 的**默认实参**是 {@code -4.0}
     * （见其 `args.length > 4 ? ... : -4.0`），而类文档里的扫描表列的是
     * {@code -0.5}（水格 2.5%）。两者不一致 ⇒ 以**探针实参 -4.0** 为准
     * （那是它实际跑出标定表的入口值）。</p>
     *
     * <p>⚠ 移植到 model 链后【必须重新标定】：两边的窗口尺度、判据链
     * （原用 {@code h < W} 直判；现在 model 用 {@code equilibrium} 定位 + 闭式场定水位）
     * 都不同。</p>
     */
    public static final double DEFAULT_OFFSET = -4.0;

    private final ElevationField eField;
    private final HeightCurve curve;
    private final double seaLevelY;
    /** 低通粗格间距（wu）；格点 = {@code k·smoothWu}（世界坐标，与 region 无关）。 */
    private final double smoothWu;
    /** 水面相对低通表面的偏置（块，通常为负）。 */
    private final double offset;

    /**
     * @param eField    归一化高程场（生产口径）
     * @param curve     高度曲线（提供 {@code heightFromE} 与海平面）
     * @param smoothWu  低通粗格间距（wu）；建议 {@link #DEFAULT_SMOOTH_WU}
     * @param offset    水面相对低通表面的偏置（块）；负值 ⇒ 只有洼地积水
     */
    public WaterField(ElevationField eField, HeightCurve curve,
                      double smoothWu, double offset) {
        this.eField = eField;
        this.seaLevelY = curve.seaLevelY();
        this.smoothWu = Math.max(1.0, smoothWu);
        this.offset = offset;
        this.curve = curve;
    }

    // ===================== 主入口 =====================

    /**
     * 水位（块）—— 由世界坐标直取。
     * <b>闭式、无状态、跨 region 逐位一致</b>（4 次 {@code eAt} + 双线性）。
     */
    public double waterYAt(double wx, double wz) {
        double w = smoothHeight(wx, wz) + offset;
        return w > seaLevelY ? w : seaLevelY;      // ★ 海是根
    }

    /**
     * 低通地形高度（块）—— 全球对齐粗格 + 双线性。
     * 格点坐标 = {@code k·smoothWu} ⇒ 同一点在任何 region 都得到<b>同一个值</b>。
     */
    public double smoothHeight(double wx, double wz) {
        double gx = wx / smoothWu, gz = wz / smoothWu;
        int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gz);
        double fx = gx - i0, fz = gz - j0;
        double h00 = heightAtLattice(i0, j0), h10 = heightAtLattice(i0 + 1, j0);
        double h01 = heightAtLattice(i0, j0 + 1), h11 = heightAtLattice(i0 + 1, j0 + 1);
        double a = h00 + (h10 - h00) * fx;
        double b = h01 + (h11 - h01) * fx;
        return a + (b - a) * fz;
    }

    /** 粗格格点处的地形高度（块）。 */
    private double heightAtLattice(int i, int j) {
        return curve.heightFromE(eField.eAt(i * smoothWu, j * smoothWu));
    }

    /**
     * 该点低于"区域平均地面"的深度（块）。&gt;0 ⇒ 在洼地里。
     *
     * <p>这是"成湖权重"的连续版本（对齐 RTF {@code flatnessFactor} / {@code basinDepth}），
     * 可直接驱动河谷拓宽与加深：越深 ⇒ 越像湖。</p>
     */
    public double depthBelowSmooth(double wx, double wz) {
        double h = curve.heightFromE(eField.eAt(wx, wz));
        return smoothHeight(wx, wz) - h;
    }

    /** 水位下界 = 海平面。 */
    public double seaLevelY() {
        return seaLevelY;
    }

    /** 低通尺度（wu）。 */
    public double smoothWu() {
        return smoothWu;
    }

    /** 水面偏置（块）。 */
    public double offset() {
        return offset;
    }
}
