package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/**
 * 连续速度场河网：用 Manning 速度场 + 流线积分求汇流量，取代 D8 汇流图。
 *
 * <h2>为什么不用 D8/MFD 累积</h2>
 * <p>实测已确认：D8 是<b>连通图</b>（每格唯一下游），MFD 是加权连通图，
 * 二者都不是流体模型 —— 均匀坡面上流向必然平行、无横向输运。
 * 参考 {@code geotransport/example/accumulate_dem.py} 亦明确区分
 * fastflow（连通图）与 geotransport（连续速度场）。</p>
 *
 * <h2>方法</h2>
 * <p>速度场由<b>水面</b>（不是床面）驱动，Wide-channel Manning：</p>
 * <pre>  v = (h^{2/3}/n) · √|∇η| · û ,   û = −∇η/|∇η|</pre>
 * <p>汇流量用<b>蒙特卡洛流线积分</b>估计：在域内均匀撒 N 个源点，各自沿速度场向前积分，
 * 沿途经过的格计数。则第 c 格的汇流量</p>
 * <pre>  Q(c) ≈ runoff · A_domain · (经过 c 的流线数 / N)</pre>
 * <p>因为"从 c 上游出发的流线必经过 c"，该比例恰是 <b>c 的上游面积占域面积之比</b>。
 * 这是对 {@code Q = runoff × A_upstream} 的无偏估计，不需要任何图结构或拓扑排序。</p>
 *
 * <h2>边界（必须声明）</h2>
 * <ul>
 *   <li>汇流量本质是<b>上游面积</b>的函数 ⇒ 任何有限窗口都会截断外部上游。
 *       故边界带用 {@code borderBufferCells} 不输出河（与参考项目一致）。</li>
 *   <li>平静水面（{@code |∇η|≈0}）速度为零 ⇒ 湖内不产河，这是正确的。</li>
 *   <li>MONTE CARLO 有方差；样本数决定精度（可解析验证）。</li>
 *   <li>不含河槽下切、不含动量与急变流。</li>
 * </ul>
 */
public final class ContinuousRiverNetwork {

    /**
     * @param manning          Manning 糙率 n
     * @param runoffPerCell    <b>每格</b>径流量（与 {@link RiverNetwork} 同单位，便于直接对照）
     * @param channelThreshold 成河门槛汇流量（同单位）
     * @param widthRef         参考汇流量（对应河头宽度）
     * @param headWidth        河头半宽（block）
     * @param widthExponent    Leopold–Maddock 幂律指数
     * @param maxWidth         半宽上限
     */
    public record Params(double manning, double runoffPerCell, double channelThreshold,
                         double widthRef, double headWidth, double widthExponent, double maxWidth) {
        public Params {
            if (!(manning > 0) || !(runoffPerCell > 0) || !(channelThreshold > 0) || !(widthRef > 0)
                    || !(headWidth > 0) || !(widthExponent > 0) || !(maxWidth >= headWidth)) {
                throw new IllegalArgumentException("invalid parameters");
            }
        }

        /** 与 {@code RiverNetwork.Params.defaults()} 同门槛/同河宽律，便于两法直接对照。 */
        public static Params forRunoff(double runoffPerCell) {
            return new Params(0.03, runoffPerCell, 1.0, 1.0, 1.5, 0.45, 12.0);
        }
    }

    public record Result(double[] flux, boolean[] channel, double[] width,
                         int channelCells, double maxFlux, long streamSteps,
                         double[] deathMask) { }

    public static Result build(double[] bed, double[] level, boolean[] resolved,
                               int nx, int nz, double cellSize,
                               Params p, int samples, long seed, int borderBufferCells) {
        return build(bed, level, resolved, nx, nz, cellSize, p, samples, seed, borderBufferCells,
                null, null, null, null);
    }

    public static Result build(double[] bed, double[] level, boolean[] resolved,
                               int nx, int nz, double cellSize,
                               Params p, int samples, long seed, int borderBufferCells,
                               boolean[] sea) {
        return build(bed, level, resolved, nx, nz, cellSize, p, samples, seed, borderBufferCells,
                sea, null, null, null);
    }

    public static Result build(double[] bed, double[] level, boolean[] resolved,
                               int nx, int nz, double cellSize,
                               Params p, int samples, long seed, int borderBufferCells,
                               boolean[] sea, int[] parent, boolean[] lake) {
        return build(bed, level, resolved, nx, nz, cellSize, p, samples, seed, borderBufferCells,
                sea, parent, lake, null);
    }

    /**
     * @param sea    海掩码（排水终端）。非空时：流线<b>进入海格即终止</b>。
     *               依据 = SimpleHydrology {@code water.h::descend} 的第三种终止"进入 pool"，
     *               海是最下游的 pool。且 {@link EquilibriumWater} 的海格种子起点是
     *               {@code bed[k]}（海床地形）⇒ 海内 {@code level} 并非水平面，
     *               若不设终止规则，流线入海后会沿海床梯度<b>继续游走甚至打转</b>
     *               （实测 300k 样本 49% 跑满步数上限）。
     * @param parent 排水父指针（{@link EquilibriumWater.Surface#parent()}）。非空时启用
     *               <b>越池续流</b>：遇"静水但不在湖泊掩码内"（微洼地被填平）或
     *               <b>停滞振荡</b>（床面微洼地里两格互跳）⇒ 沿 parent 走到水位更低的
     *               溢出口后<b>继续积分</b>，而不是把河杀在那里。
     *               依据 = SimpleHydrology 博客原始 flood 算法"粒子入池 → 最低排水点 →
     *               出口续 descend"；参考项目里"河止于湖海"指的是<b>真湖</b>，微洼地
     *               蓄满即溢，不是河流终点。
     * @param lake      湖泊掩码（{@link LakeWaterBalance.Result#lake()}）。真湖 = 合法终端。
     * @param overflowAt 湖泊溢流（出口格 → 溢流量，与 {@link RiverNetwork} 同单位）。
     *                   <b>必须注入</b>：主撒点流线止于湖（合法终端）⇒ 湖的下游汇水区
     *                   无人供水 ⇒ 湖下游河道饥饿、成河判定在门槛附近随机翻转
     *                   （实测山地湖泊下游大量虚线/断续）。D8 模型经
     *                   {@code RiverNetwork} 的源项注入溢流，此处等价实现：
     *                   按 {@code n = 溢流量 / perCrossing} 换算成等效流线，
     *                   从溢出口的<b>非湖侧</b>起继续积分（与主撒点同一套规则）。
     */
    public static Result build(double[] bed, double[] level, boolean[] resolved,
                               int nx, int nz, double cellSize,
                               Params p, int samples, long seed, int borderBufferCells,
                               boolean[] sea, int[] parent, boolean[] lake,
                               java.util.Map<Integer, Double> overflowAt) {
        return build(bed, level, resolved, nx, nz, cellSize, p, samples, seed, borderBufferCells,
                sea, parent, lake, overflowAt, null);
    }

    /**
     * 带【死亡位置】输出的完整重载（诊断专用）。
     *
     * <p>{@code deathMask} 非空时：每有一条流线【非正常终止】（{@code failNoParent} /
     * {@code failBudget} —— 即既没入海、也没入湖、也没越界），就在其死亡格 +1。</p>
     *
     * <p><b>为什么要测这个</b>：河道碎块（286 分量 / 中位 5 格）的成因有多个候选假设。
     * 已用实验排除的：① 蒙特卡洛噪声（样本 ×10，GAPS 31→33 不变）；
     * ② 逐格门槛抖动（加下游传播后最大分量仅 1018→1085）。
     * 剩下的候选是<b>流线死亡</b>：终止统计显示 27% 的流线 {@code failBudget}
     * ⇒ 死在坡上 ⇒ 下游拿不到水 ⇒ 河道在此处断。本掩码就是验证它的直接证据。</p>
     */
    public static Result build(double[] bed, double[] level, boolean[] resolved,
                               int nx, int nz, double cellSize,
                               Params p, int samples, long seed, int borderBufferCells,
                               boolean[] sea, int[] parent, boolean[] lake,
                               java.util.Map<Integer, Double> overflowAt, double[] deathMask) {
        int cells = nx * nz;
        if (bed.length != cells || level.length != cells || resolved.length != cells) {
            throw new IllegalArgumentException("array length != nx*nz");
        }
        if (samples <= 0) throw new IllegalArgumentException("samples must be > 0");
        double[] crossings = new double[cells];
        long state = seed;
        int maxSteps = 4 * (nx + nz);
        double[] resumeAt = new double[2];   // recover 的回传位置（复用，避免热路径分配）

        // ★ 终止原因统计（诊断用）：河"断掉"必须能归因，不能靠猜。
        StreamStats mainStats = new StreamStats();
        mainStats.deathMask = deathMask;
        for (int s = 0; s < samples; s++) {
            state = mix(state + 0x9E3779B97F4A7C15L);
            double x = unit(state) * (nx - 2) + 1.0;
            state = mix(state + 0x9E3779B97F4A7C15L);
            double z = unit(state) * (nz - 2) + 1.0;
            trace(bed, level, resolved, nx, nz, cellSize, p.manning(), maxSteps, crossings,
                    sea, parent, lake, resumeAt, x, z, mainStats);
        }
        printStats("samples", samples, mainStats);

        // ★ 单位必须与 RiverNetwork 一致，否则两法不可比：
        //   P(样本经过 c) = A_upstream(c)/A_domain（确定性流向下的恒等式）
        //   ⇒ Q(c) = runoffPerCell · (nx·nz) · (crossings/N) = runoffPerCell · A_upstream_格数
        //   即"上游格数 × 每格径流"，与 D8 累积同单位。
        //   ⚠ 曾误用"单位面积率"作 runoff（差 5 个数量级），导致 maxFlux 比 D8 大 11 万倍、
        //     69% 的格被误判成河 —— 是量纲错误，不是模型差异。
        double perCrossing = p.runoffPerCell() * (nx * (double) nz) / samples;

        // ★ 溢流注入的全局预算（见下方注释：单出口上限曾形同虚设）。
        //   取值与主撒点数同量级：注入的物理意义是"补上湖的下游汇水区"，
        //   它不该超过主撒点本身的工作量。超预算则截断并打印，绝不静默。
        final int perOutletCap = 5_000;
        final int totalCap = samples;
        int injected = 0;
        boolean capped = false;

        // ★ 湖泊溢流注入（见方法 javadoc @param overflowAt）。
        if (overflowAt != null && !overflowAt.isEmpty()) {
            StreamStats ofStats = new StreamStats();
            int outlets = 0;
            // ★ 诊断：溢流出口被跳过的原因分类（"64 个出口只注入 10 个"的归因）
            int skipNoOutflow = 0, skipOutOfRange = 0, skipInSea = 0, skipExitNeg = 0;
            int zeroStreams = 0;
            int exitReasonSea = 0, exitReasonNoParent = 0, exitReasonBudget = 0;
            for (var e : overflowAt.entrySet()) {
                double outflow = e.getValue();
                if (!(outflow > 0)) { skipNoOutflow++; continue; }
                int k = e.getKey();
                if (k < 0 || k >= cells) { skipOutOfRange++; continue; }
                if (sea != null && sea[k]) { skipInSea++; continue; }          // 溢流入海：无需注入下游
                int start = overflowParentFallback
                        ? outletExit(k, lake, parent, sea, nz, level, bed, nx, nz)
                        : outletExit(k, lake, parent, sea, nz);
                if (start < 0) {
                    skipExitNeg++;
                    // ★ 细分失败原因：走到海(合理) / parent缺失(拓扑断) / 预算耗尽
                    int[] rr = new int[1];
                    outletExitDiag(k, lake, parent, sea, nz, rr);
                    if (rr[0] == 1) exitReasonSea++;
                    else if (rr[0] == 2) exitReasonNoParent++;
                    else exitReasonBudget++;
                    continue;
                }
                outlets++;
                // n = 溢流量 / 单流线贡献；上限防极端值（注入量同时打印，可审计）。
                //
                // ★★★ 2026-10 修复：单出口 50k 的上限【形同虚设】★★★。
                //   诊断层 A/B 实测（jstack 定位）：主线 600k 条流线 ~150s，
                //   而"湖溢流注入"跑到 45 分钟仍未结束（已注入 214,596 条并在增长）。
                //   根因：该上限是【每个出口】的，不是【全局】的 ⇒ 溢出口越多，
                //   总注入量 = Σ_outlets min(50k, …) 无上界；而叠加湖↔河耦合迭代
                //   （COUPLING_ROUNDS=4，每轮重新 build）⇒ 量级被反复放大。
                //   实测同一份代码 baseline/edgepsilon 两档各 ~50s，唯 noise 档卡死
                //   ⇒ 说明"噪声造出大量微洼地 ⇒ 微湖 ⇒ 溢出口暴涨"是放大器。
                //   修法：改成【全局预算】，与主撒点量级挂钩，并打印实际占比，
                //   使"注入量是否已失控"可审计（不能静默截断）。
                int n = (int) Math.min((double) perOutletCap, Math.round(outflow / perCrossing));
                if (injected + n > totalCap) {
                    n = Math.max(0, totalCap - injected);
                    capped = true;
                }
                injected += n;
                // ★ 诊断：n==0 的出口（溢流量 < 单流线贡献 ⇒ 一条流线都注不进）
                if (n <= 0) zeroStreams++;
                double cx = (start / nz) + 0.5, cz = (start % nz) + 0.5;
                for (int s2 = 0; s2 < n; s2++) {
                    trace(bed, level, resolved, nx, nz, cellSize, p.manning(), maxSteps, crossings,
                            sea, parent, lake, resumeAt, cx, cz, ofStats);
                }
            }
            System.out.printf("  [streams] overflow injection: outlets=%d streams=%d (cap=%d%s)%n",
                    outlets, injected, totalCap, capped ? ", CAPPED - 注入被截断" : "");
            // ★ 归因：为何"64 个溢流出口只注入 10 个"（"溢口→下泄"断裂的直接证据）
            System.out.printf("    [skip归因] overflowAt总数=%d | 无出流=%d 越界=%d 已在海=%d "
                            + "outletExit失败=%d (其中: 走到海=%d parent缺失=%d 预算耗尽=%d)"
                            + " | n<=0(注不进)=%d%n",
                    overflowAt.size(), skipNoOutflow, skipOutOfRange, skipInSea,
                    skipExitNeg, exitReasonSea, exitReasonNoParent, exitReasonBudget,
                    zeroStreams);
            printStats("overflow", injected, ofStats);
        }

        double[] flux = new double[cells];
        boolean[] channel = new boolean[cells];
        double[] width = new double[cells];
        int channelCells = 0;
        double maxFlux = 0;
        // ★ 成河判定用 3×3 平滑通量（原始 flux 原样输出，探针/对照口径不受影响）。
        //   根因（实测断口探测器）：perCrossing≈0.0011 ⇒ 门槛 1.0 ≈ 900 条流线，
        //   河头流量恰在门槛附近 ⇒ 相邻格在门槛两侧翻转 ⇒ 视觉上呈"虚线/断续"。
        //   平滑降方差、不改系统性流量 ⇒ 头部连续；海格不参与均值（flux 恒 0 会稀释河口）。
        //
        //   ★★★ 2026-10【顺序依赖 bug —— 河道碎块的真正根因】★★★
        //   原实现把 {@code flux[k] = ...} 与"读 3×3 邻居的 flux 求平均"放在<b>同一个
        //   双重循环</b>里 ⇒ 索引 {@code kk > k} 的邻居此刻 {@code flux[kk]} <b>仍是 0</b>
        //   ⇒ 每格的平滑值都被系统性低估（少算了"索引更大"的邻居）。
        //   实测铁证：探针复算同一格得 smooth=3.56（全填好后），
        //   而 build 内部据此判为"未达门槛 1.0"⇒ 不画河道 ⇒ 河道断开。
        //   症状与"加样本数无效"完全吻合 —— 这是<b>顺序依赖</b>，不是统计噪声。
        //   修法：两趟分离 —— 第一趟只填 flux，第二趟才算 smooth。
        for (int k = 0; k < cells; k++) {
            flux[k] = crossings[k] * perCrossing;
        }
        double[] smooth = new double[cells];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                if (sea != null && sea[k]) { smooth[k] = flux[k]; continue; }
                double sum = 0;
                int cnt = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                        int kk = a * nz + b;
                        if (sea != null && sea[kk]) continue;
                        sum += flux[kk];
                        cnt++;
                    }
                }
                smooth[k] = cnt > 0 ? sum / cnt : flux[k];
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                // ★ 未决格【也输出 channel】（本轮修正）：
                //   旧写法 `if (!resolved[k]) continue;` 让河流穿过未决带时<b>视觉断开</b>
                //   （流线连续、channel 却在带内不画 ⇒ 图上"河流动一点就断开"）。
                //   连续模型在此处的流量来自<b>床面梯度</b>流线：床面是确定的
                //   ⇒ 未决格上的流向与窗口切割无关（与 D8 经 level/parent 路由不同，
                //   D8 的未决路由确实是窗口相关的，其 resolved 闸门保留）。
                //   "未决不得落水块"的契约约束的是<b>水位/水深</b>（level/depth），
                //   不约束"这里有河经过"这一流量结论。
                maxFlux = Math.max(maxFlux, flux[k]);
                // ★ 判定量：成河用【原始流量】还是【3×3 平滑流量】（A/B 开关）。
                //   ⚠ 曾据 gap 局部读数推断"平滑会腰斩窄河"并试图改用原始流量 ——
                //   实测 A/B 证伪：平滑 159 分量 vs 原始 486 分量，【平滑明显更好】。
                //   详见 {@link #channelUseRawFlux} 的完整实测表与机理解释。
                double judge = channelUseRawFlux ? flux[k] : smooth[k];
                if (judge < p.channelThreshold()) continue;
                if (borderBufferCells > 0 && (i < borderBufferCells || j < borderBufferCells
                        || i >= nx - borderBufferCells || j >= nz - borderBufferCells)) {
                    continue;
                }
                channel[k] = true;
                channelCells++;
                double ratio = smooth[k] / p.widthRef();
                width[k] = Math.min(p.maxWidth(),
                        p.headWidth() * Math.pow(Math.max(1e-9, ratio), p.widthExponent()));
            }
        }

        // ★★★ 下游连续性传播（2026-10；用户："河流流动一点就断开了"）★★★
        //
        //   实测（CanonicalViewProbe 连通性度量，600 cells 窗口）：
        //     河道 10322 格 → 【286 个连通分量】，最大分量仅占 9.9%，
        //     分量长度中位【5 格】，33% 的碎块 ≤2 格。
        //   ⇒ 河道被切碎，视觉上正是"流动一点就断开"。
        //
        //   根因：逐格阈值判定。河流是【线状】的，汇流量沿程单调递增而河头恰在
        //   门槛附近 ⇒ 门槛上下随机开关 ⇒ 必然碎成多段。这不是参数问题，
        //   **加样本数也无效**（实测样本 ×10，GAPS 31→33 不变）。
        //
        //   物理约束：**河道不可能中断** —— 上游有水，下游必有水（水是连续的、
        //   只会越汇越多）。故沿排水下游传播：任一惊格成河，其下游必然成河。
        //
        //   实现：沿 parent 链向【水位更低】的方向传播，直到遇到
        //   ① 海/湖（合法终点，水归入水体）② 越出边界 ③ 水位不再下降（已到终端）。
        //   传播只在"该格尚未成河"时扩展，且带预算防环。
        propagateDownstream(bed, level, parent, sea, lake, smooth, flux, channel, width,
                nx, nz, p, borderBufferCells);

        // 传播会新增河格 ⇒ 重新计数（否则 Result.channelCells 与 channel[] 不一致）。
        channelCells = 0;
        for (int k = 0; k < cells; k++) if (channel[k]) channelCells++;

        return new Result(flux, channel, width, channelCells, maxFlux, mainStats.totalSteps,
                deathMask);
    }

    /**
     * 死亡细分：区分"物理上本来就该死"与"缺陷"。
     *
     * <p><b>真分水岭（deathFlatLevel，死亡是正确行为）</b>：
     * 该格 {@code level > bed + 1e-9} ⇒ 水面高于床面 ⇒ 它是<b>洼地/盆地</b>，
     * minimax 把它的水位抬到"翻越出口所需的最低高度"。此时沿 parent 链走，
     * 水位<b>必须先不降</b>（走过盆地底）才能下降 —— 若 parent 链存在，
     * {@code recover} 会在预算内找到下降点。
     * 所以仍走到失败 ⇒ 说明该盆地<b>没有出口</b>（内流盆地），
     * 流线死在这里<b>物理正确</b>：水确实到不了海。</p>
     *
     * <p><b>其它（deathOther，这一类才是缺陷）</b>：
     * {@code level == bed}（排水通畅的坡面）却走不下去
     * ⇒ 父指针链有问题（缺失、成环被强断、或指向窗外）
     * ⇒ 这是<b>真缺陷</b>，是"河流流动一点就断开"的剩余成因。</p>
     */
    private static void classifyDeath(StreamStats st, double[] level, double[] bed,
                                     int k, int[] parent, int nx, int nz) {
        boolean basin = level[k] > bed[k] + 1e-9;     // 水面高于床面 = 洼地
        if (basin) {
            st.deathFlatLevel++;
        } else {
            st.deathOther++;
            // 打样取证：坡面上走不下去是缺陷，取前 5 例定位。
            if (st.deathOther <= 5 && parent != null) {
                System.out.printf("  [death] 坡面死亡(%d,%d) level=%.3f bed=%.3f parent=%d%n",
                        k / nz, k % nz, level[k], bed[k], parent[k]);
            }
        }
    }

    /**
     * 河道下游连续性传播：保证"上游成河 ⇒ 下游成河"（水不会在坡上凭空消失）。
     *
     * <p>物理依据：水是连续介质，汇流量沿程单调不减 ⇒ 河道在空间上连通。
     * 逐格阈值判定违反此约束（河头恰在门槛附近 ⇒ 碎成多段，实测 286 段 / 中位 5 格）。</p>
     *
     * <p><b>⚠ 关键约束：传播不得脱离流量</b>。第一版实现【无条件】沿 {@code parent}
     * 扩格，实测把整片未决平原点亮（河道 10305→24976 格、GAPS 31→309）——
     * 因为 {@code parent} 是 minimax 洪泛的 EDT 破平局产物，在平原上是一张
     * <b>发散网</b>，它表示"水会流过的区域"，<b>不等于河道</b>。
     * 河道的定义仍是<b>汇流量</b>：只有汇流量达到门槛的格才是河。</p>
     *
     * <p>故本实现只在<b>下游侧仍有足够汇流量</b>时传播：
     * 从已成河格出发，沿 parent 前进，但每格都要求
     * {@code smooth[格] ≥ threshold * DOWNSTREAM_FLOOR}，一旦低于该下限即停。
     * 这保留了"下游必成河"的物理约束（门槛附近的抖动被抹平），
     * 又不会把流量确实不足的坡面点亮。</p>
     *
     * <p>{@code DOWNSTREAM_FLOOR} 取 0.5：下游流量按定义不低于上游，
     * 允许到门槛一半是给"离散化 + 3×3 平滑"留余量，而非放宽物理。</p>
     */
    private static final double DOWNSTREAM_FLOOR = 0.5;

    /**
     * ★★ 下游连续性传播的两道闸门（A/B，2026-10-05）★★
     *
     * <p><b>用户判据</b>：「河流应该一直流到湖/海才结束」。</p>
     *
     * <p><b>实测断因（runCanonicalViewProbe 的 [propagate] 行）</b>：</p>
     * <pre>
     *   流量不足 = 9761   ← 主因
     *   访问上限 = 12187  ← 次因
     *   入湖 = 939 / 无parent = 420 / ★上坡 = 0 / 缓冲带 = 0
     * </pre>
     * <p>（我原以为主因是"平坦区 level 上坡阻断"，<b>实测上坡=0，证伪</b>。）</p>
     *
     * <h3>闸门 1：{@code 流量不足}（{@code smooth < 0.5×门槛}）</h3>
     * <p>与"水只到湖/海"直接冲突：下游流量若因离散化/平滑波动而略低，河就断在这里。
     * 而物理上"上游有水 ⇒ 下游必有水"（水是连续的、只会越汇越多）。</p>
     *
     * <h3>闸门 2：{@code 访问上限}（{@code visits>8}）</h3>
     * <p><b>这是最可疑的一条</b>：它的本意是"防环"，但<b>汇流处本来就该被多条河经过</b>
     * ——那正是「主流+支流」的形态。把它当环掐断 ⇒ 主干在汇流点被截。
     * 参考 SimpleHydrology 对照：粒子只在 {@code age>maxAge / 越界 / 入池} 三处终止，
     * <b>没有任何"访问次数"闸门</b>。</p>
     *
     * <p>两个开关默认 false ⇒ 与改动前<b>逐位一致</b>；开启后做 A/B。</p>
     */
    public static volatile boolean relaxFloorGate =
            Boolean.getBoolean("hydro.relaxFloorGate");
    /**
     * ★ 下游传播的【流量下限】系数（默认 {@value #DOWNSTREAM_FLOOR} = 旧行为）。
     *
     * <p>判据来自实测：{@code relaxFloorGate=true}（下限 0）时河道格 49524 → 133367
     * （+169%，平原被点亮），说明"完全不设下限"不行；
     * 而 {code visitCap=0} 单独放开【结果完全不变】⇒ 访问上限不是问题。
     * ⇒ 真闸门是这个系数。本参数用于扫描合适值。</p>
     */
    public static volatile double floorMul =
            Double.parseDouble(System.getProperty("hydro.floorMul", "0.5"));
    /** 访问上限（默认 8 = 旧行为）。设为 0 表示不限制。 */
    public static volatile int visitCap = Integer.getInteger("hydro.visitCap", 8);

    private static void propagateDownstream(double[] bed, double[] level, int[] parent,
                                           boolean[] sea, boolean[] lake, double[] smooth,
                                           double[] flux,
                                           boolean[] channel, double[] width,
                                           int nx, int nz, Params p, int borderBufferCells) {
        if (parent == null) return;
        int cells = nx * nz;
        int[] visits = new int[cells];
        final int budget = 4 * (nx + nz);
        final double floor = p.channelThreshold() * floorMul;
        // ★★ 断因计数器（2026-10-05）★★
        //   用户判据："河流应该一直流到湖/海才结束"。
        //   实测 STARVED 分量出口流量 P50=0.968（只差 3% 门槛）却停在半路
        //   ⇒ 必须量清 propagateDownstream 到底断在哪一句，不许猜。
        long stopSea = 0, stopLake = 0, stopNoParent = 0, stopUphill = 0,
             stopFloor = 0, stopVisits = 0, stopBuffer = 0;
        for (int k0 = 0; k0 < cells; k0++) {
            if (!channel[k0]) continue;
            int cur = k0;
            for (int n = 0; n < budget; n++) {
                if (sea != null && sea[cur]) { stopSea++; break; }
                if (lake != null && lake[cur]) { stopLake++; break; }
                int nxt = parent[cur];
                if (nxt < 0 || nxt == cur) { stopNoParent++; break; }
                if (level[nxt] > level[cur] + 1e-9) { stopUphill++; break; }   // 不得上坡（防御异常 parent）
                // ★ 流量不足 ⇒ 此处不是河道（判据必须与主判定【同口径】，否则两套标准混用）
                //   A/B：relaxFloorGate=true 时把下限降到 0（只要求"有流量"），
                //   理由：用户要求"流到湖/海才结束"，且物理上"上游有水 ⇒ 下游必有水"。
                double judgeN = channelUseRawFlux ? flux[nxt] : smooth[nxt];
                double floorEff = relaxFloorGate ? 0.0 : floor;
                if (judgeN < floorEff) { stopFloor++; break; }
                // ★ 访问上限：visitCap<=0 ⇒ 不限制（旧行为 8；汇流处本就多条河经过）
                if (visitCap > 0 && visits[nxt]++ > visitCap) { stopVisits++; break; }
                if (!channel[nxt]) {
                    int ni = nxt / nz, nj = nxt % nz;
                    boolean inBuffer = borderBufferCells > 0
                            && (ni < borderBufferCells || nj < borderBufferCells
                                || ni >= nx - borderBufferCells || nj >= nz - borderBufferCells);
                    if (inBuffer) { stopBuffer++; break; }
                    channel[nxt] = true;
                    double ratio = smooth[nxt] / p.widthRef();
                    width[nxt] = Math.min(p.maxWidth(),
                            p.headWidth() * Math.pow(Math.max(1e-9, ratio), p.widthExponent()));
                }
                cur = nxt;
            }
        }
        // ★ 断因报告（判据：哪个原因占主导 ⇒ 决定修法）
        System.out.printf("  [propagate] 传播终止原因: 入海=%d 入湖=%d 无parent=%d "
                        + "★上坡=%d 流量不足=%d 访问上限=%d 缓冲带=%d%n",
                stopSea, stopLake, stopNoParent, stopUphill, stopFloor, stopVisits, stopBuffer);
    }

    /**
     * 终止/恢复计数（主撒点与溢流注入分开统计，保证主结果可跨实验对比）。
     *
     * <p><b>★ 死亡细分（2026-10）</b>：环修复收敛后，
     * {@code [recover] budget exhausted} 与 {@code CYCLE} 打样已完全消失
     * ⇒ 死因已变，但 {@code failNoParent}/{@code failBudget} 两个计数仍是黑盒。
     * 故按<b>死亡格的水位是否等于所在格的 level</b>细分：
     * <ul>
     *   <li>{@code deathFlatLevel} —— 该格 level 已等于"到海路径上的最高点"，
     *       沿 parent 走出去<b>必须翻越更高的地方</b> ⇒ 物理上就是分水岭，
     *       流线死在这里<b>是正确行为</b>（水确实到不了海）；</li>
     *   <li>{@code deathOther} —— 其它（含边界、父指针缺失等）。</li>
     * </ul>
     * 只有第二类才是缺陷。</p>
     */
    private static final class StreamStats {
        long edge, sea, flatLake, flatSuspicious, maxSteps;
        long pond, stagnant, failNoParent, failBudget;
        long totalSteps;
        long samples;   // 已打印的 maxSteps 打样数
        /** 诊断：非正常终止（failNoParent / failBudget）的死亡格计数，null = 不采集。 */
        double[] deathMask;
        /** 死亡细分：level 等于"到海路径最高点"（真分水岭，死亡是正确行为）。 */
        long deathFlatLevel;
        /** 死亡细分：其它（父指针缺失、边界等）—— 这一类才是缺陷。 */
        long deathOther;
    }

    private static void printStats(String tag, int streams, StreamStats st) {
        System.out.printf("  [streams-%s] streams=%d stops: edge=%d sea=%d lakeStill=%d flatUnresolved=%d maxSteps=%d"
                        + " | recovered: pond=%d stagnant=%d failNoParent=%d failBudget=%d%n",
                tag, streams, st.edge, st.sea, st.flatLake, st.flatSuspicious, st.maxSteps,
                st.pond, st.stagnant, st.failNoParent, st.failBudget);
        // ★ 死亡细分：盆地死亡（物理正确，水到不了海）vs 坡面死亡（缺陷）。
        long deaths = st.deathFlatLevel + st.deathOther;
        if (deaths > 0) {
            System.out.printf("    [death细分] 盆地/分水岭=%d(%.1f%%，物理正确) 坡面=%d(%.1f%%，缺陷) 合计=%d%n",
                    st.deathFlatLevel, 100.0 * st.deathFlatLevel / deaths,
                    st.deathOther, 100.0 * st.deathOther / deaths, deaths);
        }
    }

    /**
     * 从溢出口格出发，走到<b>非湖格</b>作为注入起点。
     *
     * <h3>★★ 2026-10-05 修复：parent 断裂时改用【水面梯度】下降</h3>
     *
     * <p><b>实测缺陷（LifecycleChainProbe + skip 归因）</b>：全周期
     * 「溢口→下泄→入海」整体断裂 —— 64 个溢流出口只有 10 个能注入，
     * 细分原因：<b>parent 缺失 = 54 / 达到海 = 0 / 预算耗尽 = 0</b>。</p>
     *
     * <p><b>根因（结构性，非参数）</b>：{@code overflowAt} 的键来自
     * {@code LakeWaterBalance.terminal[]}，而 terminal 在 parent 断开处被设成
     * <b>该格自己</b>（{@code if (p < 0) result = c;}）。湖盆内部格的 parent
     * 由 minimax 洪泛给出，常常 <b>parent = -1</b>（它们是洪泛终端）
     * ⇒ 出口格自身 parent=-1（实测 56/64）⇒ 本方法第一步就必然失败
     * ⇒ <b>溢流注入永远发生不了</b> ⇒ 湖与下游河网实际脱节。</p>
     *
     * <p><b>修法</b>：{@code parent < 0} 时不再放弃，改为沿<b>水面高程 level</b>
     * 下降方向走 —— 这正是物理上"水从盆地最低溢口溢出"的方向
     * （minimax 已把湖盆水位抬到溢出口高度 ⇒ 越过鞍口的方向就是 level 下降方向）。
     * 走出湖域即得注入起点；若邻域全是湖且无更低水面，才判失败。</p>
     *
     * @return 起点格下标；-1 = 走到海（无需注入）或确实无法走出（湖水不下泄）。
     */
    private static int outletExit(int outlet, boolean[] lake, int[] parent, boolean[] sea, int nz) {
        return outletExit(outlet, lake, parent, sea, nz, null, null, -1, -1);
    }

    /**
     * @param level 水面高程（用于 parent 断裂时的梯度下降）；null = 关闭该修复（旧行为）
     * @param bed   床面高程（用于"最低鞍口"判定；null ⇒ 退化为用 level 比较）
     */
    private static int outletExit(int outlet, boolean[] lake, int[] parent, boolean[] sea, int nz,
                                  double[] level, double[] bed, int nx, int nz2) {
        int cur = outlet;
        int guard = 0;
        while (guard++ < 4096) {
            if (cur < 0) return -1;
            if (sea != null && sea[cur]) return -1;
            if (lake == null || !lake[cur]) return cur;
            int nxt = parent[cur];
            if (nxt >= 0) { cur = nxt; continue; }
            // ★ parent 断裂：改用【水面梯度】下降（仅当传入 level 时启用）
            if (level == null || nx <= 0) return -1;
            int step = descendByLevel(cur, lake, level, bed, nx, nz2, sea);
            if (step < 0) return -1;
            cur = step;
        }
        return -1;
    }

    /**
     * 在湖域内沿水面高程下降一步（8 邻域）。
     *
     * <p>物理依据：minimax 把湖盆水面抬到"最低溢口"高度 ⇒
     * 湖内 level 近似为常数、在<b>鞍口处</b>出现向下台阶 ⇒
     * 朝 level 更低的方向走即朝溢出口走。</p>
     *
     * <h3>★★ 2026-10-05 修正：非湖邻居必须取【最低】而非【第一个】</h3>
     * <p><b>实测缺陷</b>：正典图溢口标记显示"黄点贴湖、下游无河道"，
     * 且注入统计 <code>lakeStill = 511537 / 600000</code>（<b>85% 注入流线又回到湖</b>）。</p>
     * <p><b>根因（本方法的 bug）</b>：旧写法 {@code if (!lake[k]) return k;} 返回
     * DIRS8 中遇到的<b>第一个</b>非湖格，<b>不比较高度</b> ⇒ 可能跳到朝内陆、
     * 地势更高的格（甚至盆地内侧）⇒ 水很快又流回湖。</p>
     * <p><b>修正</b>：在所有非湖邻居中取【床面最低】者（水从最低鞍口溢出，
     * 这是"最低溢口"的定义）。床面未传入时退化为水面 level 最低者。</p>
     *
     * @return 下一格；-1 = 无更低邻格（真·封闭盆地）。
     */
    private static int descendByLevel(int cur, boolean[] lake, double[] level, int nx, int nz,
                                      boolean[] sea) {
        return descendByLevel(cur, lake, level, null, nx, nz, sea);
    }

    private static int descendByLevel(int cur, boolean[] lake, double[] level, double[] bed,
                                      int nx, int nz, boolean[] sea) {
        int ci = cur / nz, cj = cur % nz;
        double bestLevel = level[cur];
        int best = -1;
        // 非湖邻居中取床面最低者（＝最低鞍口方向）；同时记录最低的湖格做兜底
        double bestExitHeight = Double.POSITIVE_INFINITY;
        int bestExit = -1;
        for (int[] d : DIRS8) {
            int a = ci + d[0], b = cj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int k = a * nz + b;
            if (sea != null && sea[k]) return k;          // 走到海 ⇒ 合法出口
            double lv = level[k];
            if (!Double.isFinite(lv)) continue;
            if (!lake[k]) {
                // ★ 非湖：取【最低】者（旧写法直接 return 第一个 ⇒ 可能朝内陆）
                double h = (bed != null) ? bed[k] : lv;
                if (h < bestExitHeight) { bestExitHeight = h; bestExit = k; }
                continue;
            }
            if (lv < bestLevel - 1e-9) { bestLevel = lv; best = k; }
        }
        // 优先走出湖域（到最低鞍口）；否则在湖内继续朝更低水位走
        return bestExit >= 0 ? bestExit : best;
    }

    /** ★ 诊断版 outletExit：记录失败原因（0=成功 1=走到海 2=parent缺失 3=预算耗尽）。 */
    private static int outletExitDiag(int outlet, boolean[] lake, int[] parent, boolean[] sea,
                                      int nz, int[] reasonOut) {
        int cur = outlet;
        for (int n = 0; n < 4096; n++) {
            if (sea != null && sea[cur]) { reasonOut[0] = 1; return -1; }
            if (lake == null || !lake[cur]) { reasonOut[0] = 0; return cur; }
            int nxt = parent[cur];
            if (nxt < 0) { reasonOut[0] = 2; return -1; }
            cur = nxt;
        }
        reasonOut[0] = 3;
        return -1;
    }

    /**
     * 单流线积分（主撒点与溢流注入<b>共用同一套规则</b>，杜绝两套路径逻辑漂移）：
     * 终止 = 越界 / 入海 / 入真湖；恢复 = 越过微洼地 / 停滞逃生（沿 parent）。
     */
    // ★★★ 2026-10 诊断出口：折线（平行线）归因 ★★★
    //
    // 用户在正典图上圈出：高原平坦区出现【平行短线/折线】河道。
    // 两个候选机制（修法完全不同，必须用数据区分）：
    //   A【链传播】：propagateDownstream 沿 parent 链把成河往下传（0.5×门槛）。
    //     平坦区 parent 由 EDT 指路 ⇒ 同一出口的 EDT 链是一组平行线
    //     ⇒ 高原流量悬在 0.5~1.0×门槛 ⇒ 一处过门槛 ⇒ 沿 EDT 链拖出"半门槛河"。
    //   B【recover 沉积】：流线停滞时 recover() 沿 parent 链逐格 crossings+1
    //     （见 trace/recover：主积分每格只计 1 次，recover 每步 +1、无上限）。
    //     基线 stagnant=1,225,770 次 ⇒ 同一条 EDT 链被成千上万条流线重复注入
    //     ⇒ 流量在单格宽的链上堆积成条纹。
    //
    // 判别：parallelInk[k] = recover 注入占总流量的比例（0=全来自主积分，1=全来自恢复）。
    // 若平行线区该比例 ≈1 ⇒ 机制 B；≈0 且被传播点亮 ⇒ 机制 A；两者都高 ⇒ 混合。
    public static double[] lastParallelInk = null;
    /** recover 注入的 crossings 总量（与主积分总量对照）。 */
    public static long lastRecoverInk = 0;
    /** 主积分（cell-entry）crossings 总量。 */
    public static long lastMainInk = 0;

    /**
     * ★★★ 2026-10 修复 A/B：recover 沿链【不记墨水】（默认 false = 逐位不变）★★★
     *
     * <p><b>机制（ParallelLineAttributionProbe 插桩 + ab_diff 图实证）</b>：
     * recover() 沿 parent 链逐格 {@code crossings+1}，而该链穿过的是
     * <b>已被填平的水体</b>（洼地蓄满区）——那些格在水下，是湖床，不是河道。
     * 链上墨水把【死水蓄量】记成了【河流水量】：
     * <ul>
     *   <li>基线 stagnant=122 万次/600k 样本，每次沿链数百格 ⇒ 同一条 EDT 链
     *       被海量流线重复注入 ⇒ 流量堆过门槛 ⇒ <b>单格宽平行条纹</b>
     *       （EDT 链指向同一出口 ⇒ 几何上就是平行线族 —— 用户圈出的形态）；</li>
     *   <li>实测平行线区河道格流量 &gt;80% 来自 recover 注入。</li>
     * </ul>
     *
     * <p><b>为什么不丢水</b>：流从溢出口（resume 点）重新积分，
     * 下游墨水照常记账 ⇒ 下游水量不受影响（旧注释"丢格会让下游流量凭空缺失"
     * 的担心不成立 —— 那些格本就在水体之下，不是过流河道）。</p>
     *
     * <p><b>⚠ 为什么不用 mainInkGate（已试、已否）</b>：主墨水门槛在
     * 【成河判定】环节拦截，但 propagateDownstream 随后按
     * {@code smooth ≥ 0.5×门槛} 把平行线<b>重新点亮</b>（它们 smooth≈0.85×门槛）
     * ⇒ 30% 删除删在了真河上（ab_diff 图红洞），平行线原样健在。
     * ⇒ 必须在【墨水来源】环节修，而不是在【判定】环节修。</p>
     */
    public static volatile boolean recoverNoInk =
            !Boolean.getBoolean("hydro.recoverKeepChainInk");

    private static void trace(double[] bed, double[] level, boolean[] resolved,
                              int nx, int nz, double cellSize, double manning,
                              int maxSteps, double[] crossings,
                              boolean[] sea, int[] parent, boolean[] lake,
                              double[] resumeAt, double x0, double z0, StreamStats st) {
        double x = x0, z = z0;
        double step = 0.5 * cellSize;
        int guard = 0;
        int lastCell = -1;
        // ★★ 动量方向（2026-10-05）★★
        //   【为什么需要】用户："那些位置从地形高度图上看明显不对，不应该成散点"。
        //   实测碎块特征：陡坡(1.19 vs 0.75) / 高海拔(175 vs 115) / **流量反而更高**
        //   ⇒ 陡坡上【墨水充足但分布破碎】⇒ 不是"没水"，是【流线方向乱跳】。
        //   机制：velocity 方向 = -∇s/|∇s|，逐格独立。陡坡 |∇s| 大、地形粗糙
        //   ⇒ 方向对局部噪声敏感 ⇒ 流线左右摆 ⇒ 停滞检测(净位移<2格)触发
        //   ⇒ recover 沿 parent 瞬移（不留墨水）⇒ 路径断裂 ⇒ 散点。
        //
        //   【参考依据】SimpleHydrology `water.h::descend`：
        //     speed += gravity·n̂ ;  fspeed = 该格历史动量 ;
        //     if (|fspeed|>0 && |speed|>0)
        //       speed += momentumTransfer·dot(f̂s, v̂)·fspeed / (volume+discharge)
        //   ⇒ 粒子有【方向惯性】：与历史方向一致时加强、偏离时被拉回。
        //     （`dot` 项：同向 ⇒ 正 ⇒ 加强；反向 ⇒ 负 ⇒ 抑制偏离）
        //   本实现取其精神：方向 = normalize(α·上一方向 + (1−α)·当前梯度方向)。
        //   α = 0 ⇒ 无动量（旧行为，逐位不变）；α > 0 ⇒ 有惯性。
        double dirX = 0, dirZ = 0;   // 上一有效方向（单位向量）
        boolean hasDir = false;
        // 停滞检测的检查点：每 40 步（20 格）回看净位移。
        //   每步固定走 0.5 格 ⇒ 40 步净位移应 ≥ ~19 格；
        //   净位移 < 2 格 ⇒ 必在原地打转（床面微洼地两格互跳是主要形态）。
        double ckX = x, ckZ = z;
        int sinceCk = 0;
        boolean failed = false;
        while (guard++ < maxSteps) {
            int i = (int) x, j = (int) z;
            if (i < 1 || j < 1 || i >= nx - 1 || j >= nz - 1) { st.edge++; return; }
            int k = i * nz + j;
            // ★ 进入海（最下游 pool）⇒ 终止。与"进入湖"（静水 g≈0 自然终止）对称，
            //   合起来保证：河流只终止于湖或海（其余只有越界的窗口截断）。
            if (sea != null && sea[k]) { st.sea++; return; }
            // ★★ 未决格【不再终止】流线（本轮修正，用户实测"河流流动一点就断开"）★★
            //
            //   旧写法 `if (!resolved[k]) break;` 把 6.8% 的"水位不可知"坡面当作断点，
            //   于是河流在这些区域整体断开。
            //   物理上：水位不可知 ≠ 没有水。过水坡面（水从上方流下来、继续往下流）
            //   在任何情况下都成立，其方向就是<b>床面梯度</b>。
            //   参考实现（SimpleHydrology water.h::descend）也只在"体积耗尽 / 越界 /
            //   进入 pool"三处终止 —— 没有"水位未知"这种终止条件。
            // ★ 只在【进入新格】时计一次，与 geotransport 的 cell-entry 累积同构。
            //   若每步都加，同一格会被重复计 2 次（步长=半格），且与"上游面积占比"口径不符。
            if (k != lastCell) {
                crossings[k] += 1.0;
                lastCell = k;
                lastMainInk++;                       // ★ 诊断：主积分记账
            }
            double[] v = velocity(bed, level, resolved, nx, nz, i, j, cellSize, manning);
            double speed = Math.hypot(v[0], v[1]);
            if (speed < 1e-12) {
                // 静水：真湖 = 合法终端；非湖（微洼地填平 / 未决残留平坦）= 越池续流。
                if (lake != null && lake[k]) { st.flatLake++; return; }
                int rec = recover(crossings, level, nx, nz, k, parent, sea, lake, resumeAt, st);
                if (rec == 1) { st.flatLake++; return; }          // 沿 parent 走到了真湖/海
                if (rec == 0) {
                    st.pond++;
                    x = resumeAt[0]; z = resumeAt[1];
                    ckX = x; ckZ = z; sinceCk = 0;
                    lastCell = (int) x * nz + (int) z;            // 溢出口格已计 crossings
                    continue;
                }
                // rec ∈ {2,3}（无 parent / 预算耗尽）：退回旧终止语义并暴露计数。
                if (rec == 2) st.failNoParent++; else st.failBudget++;
                if (st.deathMask != null) st.deathMask[k] += 1.0;   // 死亡格（诊断）
                classifyDeath(st, level, bed, k, parent, nx, nz);
                if (resolved[k]) st.flatLake++; else st.flatSuspicious++;
                return;
            }
            // ★★ 动量混合（A/B 开关，momentumWeight=0 ⇒ 逐位不变）★★
            double mvx = v[0] / speed, mvz = v[1] / speed;
            if (momentumWeight > 0 && hasDir) {
                double bx = momentumWeight * dirX + (1 - momentumWeight) * mvx;
                double bz = momentumWeight * dirZ + (1 - momentumWeight) * mvz;
                double bl = Math.hypot(bx, bz);
                if (bl > 1e-12) { mvx = bx / bl; mvz = bz / bl; }
            }
            dirX = mvx; dirZ = mvz; hasDir = true;
            x += step * mvx;
            z += step * mvz;
            st.totalSteps++;
            // ★ 停滞检测：净位移不足 ⇒ 沿 parent 逃生。
            if (++sinceCk >= 40) {
                double moved = Math.hypot(x - ckX, z - ckZ);
                ckX = x; ckZ = z; sinceCk = 0;
                if (moved < 2.0) {
                    int ci = (int) x, cj = (int) z;
                    if (ci < 1 || cj < 1 || ci >= nx - 1 || cj >= nz - 1) return;
                    int ck = ci * nz + cj;
                    int rec = recover(crossings, level, nx, nz, ck, parent, sea, lake, resumeAt, st);
                    if (rec == 1) { st.flatLake++; return; }       // 走到了终端
                    if (rec == 0) {
                        st.stagnant++;
                        x = resumeAt[0]; z = resumeAt[1];
                        ckX = x; ckZ = z; sinceCk = 0;
                        lastCell = (int) x * nz + (int) z;
                        continue;
                    }
                    if (rec == 2) st.failNoParent++; else st.failBudget++;
                    if (st.deathMask != null) st.deathMask[ck] += 1.0;   // 死亡格（诊断）
                    classifyDeath(st, level, bed, ck, parent, nx, nz);
                    failed = true; break;                          // 走不通 ⇒ 计入 maxSteps
                }
            }
        }
        if (failed || guard >= maxSteps) {
            st.maxSteps++;
            // 打样归因：若 maxSteps 占比仍高，这些坐标就是下一步的排查入口。
            if (st.maxSteps <= 5) {
                int i = (int) x, j = (int) z;
                if (i >= 0 && j >= 0 && i < nx && j < nz) {
                    System.out.printf("  [streams] maxSteps sample #%d at cell (%d,%d) level=%.3f bed=%.3f%n",
                            st.maxSteps, i, j, level[i * nz + j], bed[i * nz + j]);
                }
            }
        }
    }

    /**
     * 越池/停滞恢复：沿排水父指针走到"水位下降"的溢出口（或终端）。
     *
     * <p>依据 = SimpleHydrology 博客原始 flood 算法："粒子入池 → 测试平面 →
     * 最低排水点 → <b>出口续 descend</b>"。河止于<b>真湖/海</b>；微洼地填平后
     * 蓄满即溢，不是河流终点。</p>
     *
     * <p>沿途每格计一次 {@code crossings}（与 cell-entry 累积同构）：
     * 恢复走的是排水父指针路径 = 合法水流路径，丢格会让下游流量凭空缺失。</p>
     *
     * @return 0 = 已写入 {@code resumeAt}（溢出口格中心，沿途已计 crossings）；
     *         1 = 沿途遇到真湖/海（合法终端，调用方终止）；
     *         2 = 失败：无 parent 或走到 parent=-1 的非终端；
     *         3 = 失败：预算耗尽（水位始终不降 ⇒ plateau 远超预期，须暴露归因）。
     */
    private static int recover(double[] crossings, double[] level, int nx, int nz, int k,
                               int[] parent, boolean[] sea, boolean[] lake, double[] resumeAt,
                               StreamStats st) {
        if (parent == null) return 2;
        // 预算：微洼地越池只需几格；未决平坦区沿 parent（EDT 指向出口）走可达数百格。
        //   上限定 4096：最坏情况下 5 万次恢复 × 4096 = 2 亿次数组访问，Java 秒级。
        final int budget = 4096;
        int cur = k;
        for (int n = 0; n < budget; n++) {
            if (sea != null && sea[cur]) return 1;
            if (lake != null && lake[cur]) return 1;
            int nxt = parent[cur];
            if (nxt < 0) return 2;
            // ★ 修复 A/B（默认关 = 逐位不变）：链上不记墨水。
            //   链穿过的是被填平的水体（湖床），水在那里不成流；
            //   流从溢出口（resume）重新积分 ⇒ 下游墨水不受影响。
            //   开启后"死水蓄量"不再被记成"河流水量" ⇒ 平行条纹消失。
            if (!recoverNoInk) crossings[nxt] += 1.0;
            // ★ 诊断：recover 注入记账（见 lastParallelInk 的 javadoc —— 折线归因）
            if (!recoverNoInk) {
                lastRecoverInk++;
                if (lastParallelInk != null && nxt >= 0 && nxt < lastParallelInk.length) {
                    lastParallelInk[nxt] += 1.0;
                }
            }
            if (level[nxt] < level[k] - 1e-9) {     // 水位下降 = 越过溢出口
                resumeAt[0] = (nxt / nz) + 0.5;
                resumeAt[1] = (nxt % nz) + 0.5;
                return 0;
            }
            cur = nxt;
        }
        // ★ 预算耗尽 = "水位始终不降"，属反常现场：打样取证（前 3 次），不许静默失败。
        if (st != null && st.failBudget < 3) {
            System.out.printf("  [recover] budget exhausted: start cell (%d,%d) level=%.3f"
                            + " -> after %d steps at (%d,%d) level=%.3f parent=%d%n",
                    k / nz, k % nz, level[k], budget, cur / nz, cur % nz, level[cur], parent[cur]);
            // 环检测（仅打样路径）：重走一遍，记录首次重复的格 ⇒ 判定 parent 链是否成环。
            java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<>();
            int c2 = k;
            for (int n = 0; n <= budget; n++) {
                Integer prev = seen.put(c2, n);
                if (prev != null) {
                    System.out.printf("  [recover]   CYCLE detected: cell (%d,%d) repeats at step %d"
                                    + " (first seen %d), cycle length ~%d, level=%.3f%n",
                            c2 / nz, c2 % nz, n, prev, n - prev, level[c2]);
                    break;
                }
                int nx2 = parent[c2];
                if (nx2 < 0 || nx2 == c2) {
                    System.out.printf("  [recover]   no cycle; dead-end at (%d,%d) parent=%d after %d steps%n",
                            c2 / nz, c2 % nz, nx2, n);
                    break;
                }
                c2 = nx2;
            }
        }
        return 3;
    }

    /**
     * 过水坡面驱动的 Manning 速度，返回 {@code {vx, vz}}。
     *
     * <p><b>★ 过水坡面 = 已决格用水面 {@code level}，未决格用床面 {@code bed}
     * （本轮修正；上一版"全用 level"会造成平原假终止）</b></p>
     *
     * <h3>为什么全用 level 是错的（实测证据）</h3>
     * <p>A 组 minimax 在"须翻越远处山脊才能入海"的整片未决平原上取值
     * <b>恒等于该山脊高程</b>（= 每条出流路径上的最大值）⇒ 该区域
     * {@code ∇level == 0} ⇒ 速度归零 ⇒ 流线在平原中段<b>假终止</b>，
     * 视觉上就是"河流流动一点就断开"（用户实测反馈）。</p>
     *
     * <h3>为什么未决格用床面是物理正确的</h3>
     * <p>未决格的水位不可知（依赖窗口切割），但床面是确定的：过水坡面
     * （水从上方流下来、继续往下流）的方向就是<b>床面梯度</b>。
     * 已决坡面上 {@code level == bed}（下坡路径最大值=自身），两场在坡面交界处<b>连续</b>；
     * 已决湖泊内 {@code level} 水平 ⇒ {@code ∇=0} ⇒ 流线终止于湖（合法终端）。</p>
     *
     * <h3>海不在此列</h3>
     * <p>{@link EquilibriumWater} 的海格种子起点是 {@code bed[k]}（海床地形）
     * ⇒ 海内 {@code level} = 海床、梯度非零 ⇒ 入海终止由
     * {@code build(..., sea)} 的终端规则负责（见该方法 javadoc）。</p>
     *
     * <p>因此流线只在三种情况下结束：<b>入湖 / 入海 / 越出窗口</b>（窗口截断是
     * 有限域的固有限制，边界带不输出河）—— 与"河流只终止于湖泊或海洋"一致。</p>
     *
     * <p><b>⚠ 已知残余风险</b>：两场在"已决湖/填充缘"与未决床面的交界处存在
     * {@code level - bed} 的跳变 ⇒ 模糊梯度可能诱发短程来回振荡。
     * 上一版混用实验的 60% 打转<b>不能</b>归罪于混用本身 —— 当时未控制
     * "海内游走"变量（海终止规则是之后才加的）。本轮以
     * {@code flatUnresolved / maxSteps} 两个计数器重新归因，用数据说话。</p>
     */
    public static final double FILM_DEPTH = 0.01;

    /**
     * 成河判定用【3×3 平滑流量】({@code true}，默认) 还是【原始流量】({@code false})。
     *
     * <p><b>★ 同参实测 A/B 结论（2026-10，r=300 / 400k 样本 / 同种子）</b>：</p>
     * <pre>
     *   判据              平滑判定      原始流量判定
     *   连通分量            159           486
     *   分量长度 P50         11 格          3 格
     *   分量长度 P90        270 格         38 格
     *   孤立块(≤2格)         42           198
     *   单格断口 GAPS        56           115
     * </pre>
     * <p><b>平滑更好，且差距很大（分量数 3.1×）</b>。这与"平滑会把窄河腰斩"的
     * 初始推断相反 —— 该推断来自 gap 样本的<b>局部</b>读数（{@code rawFlux=1.985
     * smooth=0.930}），只看单格就下结论，属于<b>用局部证据推全局</b>。</p>
     *
     * <p><b>为什么平滑确实有效（与直觉相反）</b>：河道是【线状】，1 格宽，
     * 原始流量沿程单调增；但<b>流线采样是随机的</b> —— 窄河只有中心格被大量流线穿过，
     * 相邻干地格计数接近 0。若逐格用原始值判门槛，河道中心格与邻格读数<b>差异极大</b>
     * （窄河处中心 3.0、邻格 0.05）⇒ 门槛附近的抖动直接把河切成 1 格一段的碎屑。
     * 平滑把"河心"与"河缘"合并为一个一致读数 ⇒ 整段河道同进同退。</p>
     *
     * <p>⚠ 代价：平滑会轻微低估极窄河的流量 ⇒ 河宽偏小；河宽用平滑值是有意的
     * （连续量抗噪），已在实测中确认不影响连通性。</p>
     *
     * <p>开关保留供后续实验：{@code -Dhydro.channelUseRawFlux=true} 用原始流量。</p>
     */
    public static volatile boolean channelUseRawFlux =
            Boolean.getBoolean("hydro.channelUseRawFlux");

    /**
     * A/B 开关：溢流出口在 {@code parent} 断裂时是否改用【水面梯度】下降。
     *
     * <p>默认 <b>true</b>（本轮修复）。置 false = 旧行为（54/64 出口被丢弃）。</p>
     */
    public static volatile boolean overflowParentFallback = true;

    /**
     * ★★ 动量权重 α（A/B 开关，默认 0 = 逐位不变）★★
     *
     * <p>方向 = normalize(α·上一方向 + (1−α)·当前梯度方向)。</p>
     * <p>参考 SimpleHydrology {@code water.h::descend} 的动量传递：
     * 粒子保留方向惯性，与历史方向一致时加强、偏离时被拉回。
     * 本参数是该精神的简化形式（无速度大小、只做方向惯性）。</p>
     * <p><b>为何需要</b>：陡坡上层流（sheet flow）梯度方向逐格乱跳
     * ⇒ 流线左右摆 ⇒ 停滞检测触发 ⇒ 沿 parent 瞬移不留墨水 ⇒ 散点。
     * 加上惯性后流线沿大尺度坡向走 ⇒ 应形成"主流+支流"而非散点。</p>
     * <p>取值实验：0.0（旧）/ 0.45（参考量级）/ 0.85（强惯性）。</p>
     */
    public static volatile double momentumWeight =
            Double.parseDouble(System.getProperty("hydro.momentum", "0.0"));

    /** 8 邻域（{@link #descendByLevel} 用于在湖域内找溢出口方向）。 */
    private static final int[][] DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    static double[] velocity(double[] bed, double[] level, boolean[] resolved,
                             int nx, int nz, int i, int j, double cellSize, double manning) {
        int k = i * nz + j;
        // 过水坡面：已决=水面，未决=床面（水位不可知 ⇒ 按床面流）。
        double sK = resolved[k] ? level[k] : bed[k];
        // 薄层下限：干坡上 s==bed ⇒ 深度为 0，但确有薄层漫流 ⇒ 用 FILM_DEPTH 代替零。
        double h = Math.max(FILM_DEPTH, sK - bed[k]);
        // ────────────────────────────────────────────────────────────────
        // ⚠ 2026-10 已试并【撤销】：平坦区自适应加宽梯度（±R 差分）。
        //
        //   假设：折角源于低坡度区 ∇s 被 minimax 抹平 ⇒ 方向由噪声主导
        //         ⇒ 改用更大半径的差分可让方向来自大尺度地形。
        //
        //   ★ 同参 A/B 实测（r=1000 正典窗 / 600k 样本 / baseline 其余不变）：
        //        折角率  off=8.32%  →  on=8.76%   （**略变差**）
        //        河道格  off=151,913 →  on=156,385（**+4.5%**，流线被引到别处）
        //     根因（事后看清）：gradientRadius 从 R=1 起，只要 coarseSlope(R=1) ≥ FLAT_SLOPE
        //     即返回 1；而折角正集中在 ∇bed≈0.05~0.08 ⇒ **多数点根本没进入加宽分支**。
        //     ⇒ 阈值选在了分布的错侧。即便修正阈值，也**未证明**折角在视觉上是缺陷
        //       （可能是汇合点/绕障，属物理正确）⇒ 不应在此基础上继续调参。
        //
        //   ⇒ 已整体撤销，**不留死开关**（项目规范：无效旋钮不保留）。
        //     若日后要重做，先出"非汇合点孤立折角"定位图确认目标，再动代码。
        // ────────────────────────────────────────────────────────────────
        int km = (i - 1) * nz + j, kp = (i + 1) * nz + j;
        int jm = i * nz + (j - 1), jp = i * nz + (j + 1);
        double sW = resolved[km] ? level[km] : bed[km];
        double sE = resolved[kp] ? level[kp] : bed[kp];
        double sN = resolved[jm] ? level[jm] : bed[jm];
        double sS = resolved[jp] ? level[jp] : bed[jp];
        double gx = (sE - sW) / (2 * cellSize);
        double gz = (sS - sN) / (2 * cellSize);
        double g = Math.hypot(gx, gz);
        if (g < 1e-12) return ZERO;      // 水平水面（湖面）⇒ 终止
        double speed = Math.pow(h, 2.0 / 3.0) / manning * Math.sqrt(g);
        return new double[]{-speed * gx / g, -speed * gz / g};
    }

    private static final double[] ZERO = {0, 0};

    private static double unit(long bits) { return (bits >>> 11) * 0x1.0p-53; }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private ContinuousRiverNetwork() { }
}
