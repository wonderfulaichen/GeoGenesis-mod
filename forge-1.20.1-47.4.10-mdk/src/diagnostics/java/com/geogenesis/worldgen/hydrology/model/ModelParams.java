package com.geogenesis.worldgen.hydrology.model;

/**
 * 重建水文【P1 物理模型】的参数与单位契约（零 MC 依赖，仅诊断源集）。
 *
 * <h2>单位（强制，全部为 block 系）</h2>
 * <table>
 *   <tr><th>量</th><th>符号</th><th>单位</th></tr>
 *   <tr><td>床面高程</td><td>{@code z}</td><td>block</td></tr>
 *   <tr><td>水深</td><td>{@code d ≥ 0}</td><td>block</td></tr>
 *   <tr><td>自由水面</td><td>{@code η = z + d}</td><td>block</td></tr>
 *   <tr><td>格面积</td><td>{@code A = cellSize²}</td><td>block²</td></tr>
 *   <tr><td>边长度</td><td>{@code L = cellSize}</td><td>block</td></tr>
 *   <tr><td>降水/入渗/蒸发率</td><td>{@code R / I / E}</td><td>block/时间</td></tr>
 *   <tr><td>体积</td><td>{@code V = d·A}</td><td>block³</td></tr>
 *   <tr><td>单宽流量</td><td>{@code q}</td><td>block²/时间</td></tr>
 * </table>
 *
 * <p><b>为何把单位写成契约</b>：旧实现把"降水长度率"当"每格体积率"、把湖的
 * 水深×面积存进河道流量数组（见 plan §2「单位」行）⇒ 量纲在同一数组里混用。
 * 本模型要求每个量只有一个名字、一个单位，并由 {@code ModelVerificationProbe}
 * 的量纲判据守住。</p>
 *
 * <h2>模型时间</h2>
 * <p>世界生成需要的是<b>稳态</b>而不是时间演化，但稳态必须由<b>同一套演化方程</b>
 * 走到收敛得到（否则"稳态"就是另一套魔法）。故本模型显式推进模型时间 {@code t}，
 * 直到水深变化率低于容差；{@code maxSteps} 是**收敛失败信号**而非正常出口。</p>
 *
 * @param cellSize   格边长（block）
 * @param manningN   曼宁糙率 {@code n}（s/block^{1/3}）；越大越"拖"，洪水越难铺开
 * @param rainRate   降水率 R（block/时间）；0 = 干旱
 * @param infilRate  入渗率上限 I（block/时间）；作用于**未饱和**格
 * @param evapRate   蒸发率 E（block/时间）；只作用于**有水**的格
 * @param dtMax      单步时间上限
 * @param steadyTol  <b>稳态判据（单位 1/时间）</b>：收敛当且仅当
 *                   {@code max|dd/dt| < steadyTol · max(1, dMax)}。
 *                   ⚠ 判据必须<b>与 dt 无关</b> —— 若改用"单步变化 {@code max|dd|}"，
 *                   把 dt 缩到极小就能让任何状态假装收敛（本探针实测踩过）。
 * @param maxSteps   步数上限（达到即报"未收敛"）
 */
public record ModelParams(double cellSize, double manningN,
                          double rainRate, double infilRate, double evapRate,
                          double dtMax, double steadyTol, int maxSteps) {

    /** 世界生成默认：1 块格、中等糙率、温和降水、可收敛。 */
    public static ModelParams defaults() {
        return new ModelParams(1.0, 0.03, 1.0e-3, 2.0e-4, 1.0e-4, 0.2, 1.0e-4, 400_000);
    }

    /**
     * <b>纯输运</b>配置：无降水、无入渗、无蒸发。
     *
     * <p>用于"守恒/静水/库容"类判据 —— 那些判据要检查的是<b>输运算子的守恒性</b>，
     * 而不是源汇。若沿用 {@link #defaults()}（含入渗/蒸发），水会合法地缓慢消失，
     * 于是"体积守恒"必然失败：<b>那是判据构造错误，不是模型缺陷</b>。</p>
     */
    public static ModelParams still() {
        return new ModelParams(1.0, 0.03, 0.0, 0.0, 0.0, 0.2, 1.0e-4, 400_000);
    }

    public ModelParams withRain(double r) {
        return new ModelParams(cellSize, manningN, r, infilRate, evapRate, dtMax, steadyTol, maxSteps);
    }

    public ModelParams withCellSize(double c) {
        return new ModelParams(c, manningN, rainRate, infilRate, evapRate, dtMax, steadyTol, maxSteps);
    }

    public ModelParams withMaxSteps(int n) {
        return new ModelParams(cellSize, manningN, rainRate, infilRate, evapRate, dtMax, steadyTol, n);
    }

    /** 格面积 A（block²）。 */
    public double cellArea() { return cellSize * cellSize; }

    /** 边长度 L（block）。 */
    public double edgeLength() { return cellSize; }

    public ModelParams {
        if (cellSize <= 0) throw new IllegalArgumentException("cellSize must be > 0");
        if (manningN <= 0) throw new IllegalArgumentException("manningN must be > 0");
        if (rainRate < 0 || infilRate < 0 || evapRate < 0) {
            throw new IllegalArgumentException("rates must be >= 0");
        }
        if (dtMax <= 0) throw new IllegalArgumentException("dtMax must be > 0");
        if (steadyTol <= 0) throw new IllegalArgumentException("steadyTol must be > 0");
        if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
    }
}
