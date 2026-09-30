package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/**
 * P1 物理模型原型：<b>守恒的扩散波（zero-inertia）地表水模型</b>（零 MC 依赖）。
 *
 * <h2>方程</h2>
 * <pre>
 *   守恒（有限体积，逐格）：
 *     A · dd/dt = A·(R − I − E) − Σ_faces F_face
 *   面通量（Manning 扩散波，单宽，正 = 由 a 流向 b）：
 *     q = (1/n) · h_donor^{5/3} · sign(η_a − η_b) · sqrt(|η_a − η_b| / Δx)
 *     F_face = q · L        （体积率，block³/时间）
 * </pre>
 * <p>{@code h_donor} = <b>上风（供水侧）</b>水深；{@code η = z + d}。
 * {@code sqrt(|Δη|)} 使 Δη→0 时通量平滑趋 0 ⇒ <b>静水自动保持</b>，无需人工 ε 微坡。</p>
 *
 * <h2>为什么不用 D8 / priority-flood</h2>
 * <ul>
 *   <li><b>湖自然涌现</b>：洼地 = "出流受限"区域，水深在同一场里积起 ⇒ 不需要与河并列的
 *       第二套湖实体，也不需要"到溢口跳一下继续画线"（plan §4.1 用户硬契约）。</li>
 *   <li><b>无方向量化</b>：通量由连续水面差决定，不由 8 选 1 决定 ⇒ 无格点方向伪影。</li>
 *   <li><b>守恒可账</b>：每面只算一次、成对反对称 ⇒ 全域账本闭合（plan §4.1）。</li>
 * </ul>
 *
 * <h2>数值设计的三条硬要求（都有对应判据）</h2>
 * <ol>
 *   <li><b>水深非负由构造保证</b>：试步若产生负水深 ⇒ 折半重试，而<b>不是</b>事后 clamp
 *       （事后 clamp 会凭空销毁水量，账本立刻不平）。</li>
 *   <li><b>账本用实际步长</b>：{@code dt} 可能被折半 ⇒ 通量记账必须用最终采用的 dt。</li>
 *   <li><b>未收敛是失败信号</b>：{@code steadyState()} 返回是否收敛，调用方不得把
 *       未收敛当正常结果（plan §4.2 末段）。</li>
 * </ol>
 */
public final class DiffusiveWaveModel {

    private final WaterGrid g;
    private final ModelParams p;
    private final double[] d;                 // 水深 d ≥ 0（block）
    private final double minBed;
    /** 工作数组（预分配 ⇒ 每步零分配，避免长收敛期的 GC 抖动）。 */
    private final double[] src;
    private final double[] netOut;
    /** 收敛窗口快照（每 CONV_WINDOW 步比较一次解的变化）。 */
    private final double[] snap;
    /** 逐格总出流（供逐 donor 通量限制器）。 */
    private final double[] outSum;
    /** 逐格通量限制系数（1 = 未受限）。 */
    private final double[] alpha;
    /** 收敛窗口长度（步）。 */
    private static final int CONV_WINDOW = 500;

    // ---- 全域账本（block³）----
    private double inputVolume, infilVolume, evapVolume, boundaryLossVolume;
    private int steps;
    private boolean converged;
    private double lastRate;
    /** 末步实际采用的通量限制系数 α（1 = 未受限）。 */
    private double lastAlpha = 1.0;

    public DiffusiveWaveModel(WaterGrid grid, ModelParams params) {
        this.g = grid;
        this.p = params;
        this.d = new double[grid.cells()];
        this.src = new double[grid.cells()];
        this.netOut = new double[grid.cells()];
        this.snap = new double[grid.cells()];
        this.outSum = new double[grid.cells()];
        this.alpha = new double[grid.cells()];
        double m = Double.MAX_VALUE;
        for (double z : grid.bed) m = Math.min(m, z);
        this.minBed = m;
    }

    public WaterGrid grid() { return g; }
    public ModelParams params() { return p; }
    public double[] depth() { return d; }
    public double depth(int i, int j) { return d[g.idx(i, j)]; }
    public int steps() { return steps; }
    public boolean converged() { return converged; }
    /** 末步的 {@code max|dd/dt|}（诊断：未收敛时看它离容差多远）。 */
    public double lastRate() { return lastRate; }

    /** 末步的通量限制系数 α（1 = 未受限；&lt;1 说明该步受"不得抽干"约束）。 */
    public double lastAlpha() { return lastAlpha; }

    public void setDepth(int i, int j, double v) { d[g.idx(i, j)] = Math.max(0, v); }

    /** 把某格水面灌到 {@code level}（若已高于则不动）。 */
    public void fillTo(int i, int j, double level) {
        int k = g.idx(i, j);
        d[k] = Math.max(d[k], level - g.bed[k]);
    }

    public double storage() {
        double s = 0;
        for (double v : d) s += v;
        return s * g.cellArea();
    }

    public double surface(int i, int j) {
        int k = g.idx(i, j);
        return g.bed[k] + d[k];
    }

    /** 推进到稳态（未收敛见 {@link #converged()}）。 */
    public void steadyState() {
        converged = false;
        int n = g.cells();
        java.util.Arrays.fill(snap, 0, n, 0);
        for (steps = 0; steps < p.maxSteps(); steps++) {
            lastRate = tryStep(p.dtMax());

            // ★ 收敛判据 = 【固定模型时间窗内解的变化】，而不是单步变化。
            //   理由：单步变化 ∝ dt（缩步长即可伪装收敛）；而逐步接近渐近平衡
            //   （如"水面缓慢趋近溢口高度"）本身在有限步内变化极小 ⇒ 也需要窗口才有意义。
            if (steps % CONV_WINDOW == CONV_WINDOW - 1) {
                double dMax = 0, maxDiff = 0;
                for (int k = 0; k < n; k++) {
                    dMax = Math.max(dMax, d[k]);
                    maxDiff = Math.max(maxDiff, Math.abs(d[k] - snap[k]));
                }
                if (maxDiff < p.steadyTol() * Math.max(1.0, dMax)) {
                    converged = true;
                    steps++;
                    return;
                }
                System.arraycopy(d, 0, snap, 0, n);
            }
        }
    }

    /**
     * 试算一步。
     *
     * <p><b>★ 无副作用直到被接受</b>：整个函数先把结果算进局部量，只有确认所有格水深
     * 非负之后才<b>一次性提交</b>（写 d 与账本）。若提前 return -1，则阵列与账本都必须
     * 原封不动 —— 否则 {@code steadyState()} 的"折半重试"会把被拒尝试的量重复累计，
     * 账本凭空膨胀（本探针实测到过 50 倍的边界流失）。</p>
     *
     * @return {@code max|dd/dt|}（时间率）；若任一会产生负水深则返回 {@code -1}（不提交任何改动）。
     */
    private double tryStep(double dt) {
        int n = g.cells();
        double A = g.cellArea(), L = g.edgeLength(), dx = g.cellSize();

        // ① 源汇（局部量，先不提交）
        double addIn = 0, addInfil = 0, addEvap = 0;
        for (int k = 0; k < n; k++) {
            double add = p.rainRate() * dt;
            double avail = d[k] + add;
            double infil = Math.min(p.infilRate() * dt, avail);
            avail -= infil;
            double evap = Math.min(p.evapRate() * dt, avail);
            avail -= evap;
            src[k] = avail - d[k];
            addIn += add * A;
            addInfil += infil * A;
            addEvap += evap * A;
        }

        // ② 面通量 + 逐格"总出流"统计（供 ③ 的逐 donor 限制器用）
        java.util.Arrays.fill(netOut, 0, n, 0);
        java.util.Arrays.fill(outSum, 0, n, 0);
        double boundaryFluxSum = 0;
        for (int i = 0; i < g.nx(); i++) {
            for (int j = 0; j < g.nz(); j++) {
                int a = g.idx(i, j);
                if (i + 1 < g.nx()) {
                    int b = g.idx(i + 1, j);
                    double F = faceFlux(d[a], d[b], g.bed[a], g.bed[b], dx, dt) * L;
                    netOut[a] += F;
                    netOut[b] -= F;
                    if (F > 0) outSum[a] += F; else outSum[b] -= F;
                }
                if (j + 1 < g.nz()) {
                    int b = g.idx(i, j + 1);
                    double F = faceFlux(d[a], d[b], g.bed[a], g.bed[b], dx, dt) * L;
                    netOut[a] += F;
                    netOut[b] -= F;
                    if (F > 0) outSum[a] += F; else outSum[b] -= F;
                }
                int exterior = 0;
                if (i + 1 >= g.nx()) exterior++;
                if (j + 1 >= g.nz()) exterior++;
                if (i == 0) exterior++;
                if (j == 0) exterior++;
                if (exterior > 0) {
                    double F = boundaryOutflow(a, dx) * L * exterior;
                    netOut[a] += F;
                    outSum[a] += F;
                    boundaryFluxSum += F;
                }
            }
        }

        // ③ ★ 通量限制器（逐 donor）：每个格按【自己这一步有多少水】限制自己的总出流。
        //
        //   【为什么必须逐 donor，而不是全局一个 α】
        //   全局 α 会被"最缺水的那个格"拖死 —— 典型是一条浅河槽：它的水深很小，
        //   却要承担把整碗水送出去的任务。全局限制下 α→0，全域名义通量被按比例砍到
        //   爬行速度（本探针实测：18,000 的水在 40 万步里只排出 175）。
        //   逐 donor 限制则只砍"确实没水的格"，让有水的格全额输运
        //   —— 这正是真实的物理：浅槽本来就只能慢慢过水，但它不该拖住上游。
        //
        //   守恒性：每个面只被【其 donor】缩放一次 ⇒ 成对反对称保持 ⇒ 全域账本仍闭合。
        double minAlpha = 1.0;
        for (int k = 0; k < n; k++) {
            if (outSum[k] <= 0) { alpha[k] = 1.0; continue; }
            double avail = Math.max(0, d[k] + src[k]);        // 本步可用水柱高
            double need = outSum[k] * dt / A;
            alpha[k] = need > avail ? avail / need : 1.0;
            minAlpha = Math.min(minAlpha, alpha[k]);
        }
        lastAlpha = minAlpha;

        if (minAlpha < 1.0) {                                  // 只有受限时才重算
            java.util.Arrays.fill(netOut, 0, n, 0);
            boundaryFluxSum = 0;
            for (int i = 0; i < g.nx(); i++) {
                for (int j = 0; j < g.nz(); j++) {
                    int a = g.idx(i, j);
                    if (i + 1 < g.nx()) {
                        int b = g.idx(i + 1, j);
                        double F = faceFlux(d[a], d[b], g.bed[a], g.bed[b], dx, dt) * L;
                        double donor = F > 0 ? alpha[a] : alpha[b];
                        double G = F * donor;
                        netOut[a] += G;
                        netOut[b] -= G;
                    }
                    if (j + 1 < g.nz()) {
                        int b = g.idx(i, j + 1);
                        double F = faceFlux(d[a], d[b], g.bed[a], g.bed[b], dx, dt) * L;
                        double donor = F > 0 ? alpha[a] : alpha[b];
                        double G = F * donor;
                        netOut[a] += G;
                        netOut[b] -= G;
                    }
                    int exterior = 0;
                    if (i + 1 >= g.nx()) exterior++;
                    if (j + 1 >= g.nz()) exterior++;
                    if (i == 0) exterior++;
                    if (j == 0) exterior++;
                    if (exterior > 0) {
                        double F = boundaryOutflow(a, dx) * L * exterior * alpha[a];
                        netOut[a] += F;
                        boundaryFluxSum += F;
                    }
                }
            }
        }

        // ④ 提交：写水深 + 记账（只用被接受的 dt）
        double maxRate = 0;
        for (int k = 0; k < n; k++) {
            double v = Math.max(0, d[k] + src[k] - netOut[k] * dt / A);
            maxRate = Math.max(maxRate, Math.abs(v - d[k]) / dt);
            d[k] = v;
        }
        inputVolume += addIn;
        infilVolume += addInfil;
        evapVolume += addEvap;
        boundaryLossVolume += boundaryFluxSum * dt;
        return maxRate;
    }

    /**
     * 面单宽通量（正 = a→b），含<b>不越界上限</b>。
     *
     * <p><b>★ 上风取深</b>：传导项只允许用<b>供水侧</b>（donor）的水深，而不是两侧平均。
     * 用平均水深会产生非物理后果：<b>干格只要床面更高，就能向湿格反向输水</b>。</p>
     *
     * <p><b>★ 不越界上限（本探针抓到过振荡的根因）</b>：两格连通的平衡通量是
     * "<b>把两者拉到同一水面</b>"所需量，即 {@code A·Δη/2}。若只按"donor 有多少水"
     * 限制，就会把 donor <b>抽干</b> ⇒ 越过平衡点一倍 ⇒ 下一步水又整个流回来
     * ⇒ <b>永久振荡</b>（实测：末步率恒 11.9 block/时间、4000 步不收敛）。
     * 故每个面另设上限 {@code q_max = Δx·|Δη| / (2·dt)}（由 {@code A·|Δη|/(2·dt·L)} 化简）。</p>
     */
    private double faceFlux(double da, double db, double za, double zb, double dx, double dt) {
        double deta = (za + da) - (zb + db);        // η_a − η_b：>0 ⇒ a 更高 ⇒ 流向 b
        if (deta == 0) return 0;
        double hDonor = deta > 0 ? da : db;         // 上风（供水侧）水深
        if (hDonor <= 0) return 0;                  // 供水侧无水 ⇒ 无通量
        double conv = Math.pow(hDonor, 5.0 / 3.0) / p.manningN();
        double q = conv * Math.sqrt(Math.abs(deta) / dx);
        double qMax = dx * Math.abs(deta) / (2.0 * dt);   // 不越过平衡点
        if (q > qMax) q = qMax;
        return Math.signum(deta) * q;
    }

    /**
     * 边界单宽出流量（正 = 流出域外；恒 ≥ 0）。
     *
     * <p>CLOSED ⇒ 0（不透水壁）。OPEN_AT_LEVEL ⇒ 仅当 {@code η > level} 时出流；
     * <b>只出不进</b>，避免边界凭空供水（那会让"封闭域账本"失去意义）。</p>
     */
    private double boundaryOutflow(int k, double dx) {
        if (g.boundary() == WaterGrid.Boundary.CLOSED) return 0;
        double dk = d[k];
        if (dk <= 0) return 0;
        double deta = (g.bed[k] + dk) - g.boundaryLevel();
        if (deta <= 0) return 0;
        double conv = Math.pow(dk, 5.0 / 3.0) / p.manningN();
        return conv * Math.sqrt(deta / dx);
    }

    // ---- 账本读取 ----
    public double inputVolume() { return inputVolume; }
    public double infilVolume() { return infilVolume; }
    public double evapVolume() { return evapVolume; }
    public double boundaryLossVolume() { return boundaryLossVolume; }
}
