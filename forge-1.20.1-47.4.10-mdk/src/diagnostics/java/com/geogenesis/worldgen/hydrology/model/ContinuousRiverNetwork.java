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
                         int channelCells, double maxFlux, long streamSteps) { }

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

        // ★ 湖泊溢流注入（见方法 javadoc @param overflowAt）。
        if (overflowAt != null && !overflowAt.isEmpty()) {
            StreamStats ofStats = new StreamStats();
            int outlets = 0, injected = 0;
            for (var e : overflowAt.entrySet()) {
                double outflow = e.getValue();
                if (!(outflow > 0)) continue;
                int k = e.getKey();
                if (k < 0 || k >= cells) continue;
                if (sea != null && sea[k]) continue;          // 溢流入海：无需注入下游
                int start = outletExit(k, lake, parent, sea, nz);
                if (start < 0) continue;
                outlets++;
                // n = 溢流量 / 单流线贡献；上限 50k 防极端值（注入量同时打印，可审计）。
                int n = (int) Math.min(50_000, Math.round(outflow / perCrossing));
                injected += n;
                double cx = (start / nz) + 0.5, cz = (start % nz) + 0.5;
                for (int s2 = 0; s2 < n; s2++) {
                    trace(bed, level, resolved, nx, nz, cellSize, p.manning(), maxSteps, crossings,
                            sea, parent, lake, resumeAt, cx, cz, ofStats);
                }
            }
            System.out.printf("  [streams] overflow injection: outlets=%d streams=%d%n", outlets, injected);
            printStats("overflow", injected, ofStats);
        }

        double[] flux = new double[cells];
        boolean[] channel = new boolean[cells];
        double[] width = new double[cells];
        int channelCells = 0;
        double maxFlux = 0;
        // ★ 成河判定用 3×3 平滑通量（原始 flux 原样输出，探针/对照口径不受影响）。
        //   根因（实测断口探测器）：perCrossing=0.00144 ⇒ 门槛 1.0 ≈ 692 条流线，
        //   河头流量恰在门槛附近 ⇒ MC 方差 √692≈26（±4%）使相邻格在门槛两侧翻转
        //   ⇒ 视觉上呈"虚线/断续"。断口格实测 flux=0.30~0.95（贴门槛），非路径断裂。
        //   平滑降方差、不改系统性流量 ⇒ 头部连续；海格不参与均值（flux 恒 0 会稀释河口）。
        double[] smooth = new double[cells];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                flux[k] = crossings[k] * perCrossing;
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
                if (smooth[k] < p.channelThreshold()) continue;
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
        return new Result(flux, channel, width, channelCells, maxFlux, mainStats.totalSteps);
    }

    /** 终止/恢复计数（主撒点与溢流注入分开统计，保证主结果可跨实验对比）。 */
    private static final class StreamStats {
        long edge, sea, flatLake, flatSuspicious, maxSteps;
        long pond, stagnant, failNoParent, failBudget;
        long totalSteps;
        long samples;   // 已打印的 maxSteps 打样数
    }

    private static void printStats(String tag, int streams, StreamStats st) {
        System.out.printf("  [streams-%s] streams=%d stops: edge=%d sea=%d lakeStill=%d flatUnresolved=%d maxSteps=%d"
                        + " | recovered: pond=%d stagnant=%d failNoParent=%d failBudget=%d%n",
                tag, streams, st.edge, st.sea, st.flatLake, st.flatSuspicious, st.maxSteps,
                st.pond, st.stagnant, st.failNoParent, st.failBudget);
    }

    /**
     * 从溢出口格出发，沿 parent 走到<b>非湖格</b>作为注入起点。
     *
     * @return 起点格下标；-1 = 走到海/死路（无需或无法注入）。
     */
    private static int outletExit(int outlet, boolean[] lake, int[] parent, boolean[] sea, int nz) {
        int cur = outlet;
        for (int n = 0; n < 4096; n++) {
            if (sea != null && sea[cur]) return -1;
            if (lake == null || !lake[cur]) return cur;
            int nxt = parent[cur];
            if (nxt < 0) return -1;
            cur = nxt;
        }
        return -1;
    }

    /**
     * 单流线积分（主撒点与溢流注入<b>共用同一套规则</b>，杜绝两套路径逻辑漂移）：
     * 终止 = 越界 / 入海 / 入真湖；恢复 = 越过微洼地 / 停滞逃生（沿 parent）。
     */
    private static void trace(double[] bed, double[] level, boolean[] resolved,
                              int nx, int nz, double cellSize, double manning,
                              int maxSteps, double[] crossings,
                              boolean[] sea, int[] parent, boolean[] lake,
                              double[] resumeAt, double x0, double z0, StreamStats st) {
        double x = x0, z = z0;
        double step = 0.5 * cellSize;
        int guard = 0;
        int lastCell = -1;
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
                if (resolved[k]) st.flatLake++; else st.flatSuspicious++;
                return;
            }
            x += step * v[0] / speed;
            z += step * v[1] / speed;
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
            crossings[nxt] += 1.0;
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

    static double[] velocity(double[] bed, double[] level, boolean[] resolved,
                             int nx, int nz, int i, int j, double cellSize, double manning) {
        int k = i * nz + j;
        // 过水坡面：已决=水面，未决=床面（水位不可知 ⇒ 按床面流）。
        double sK = resolved[k] ? level[k] : bed[k];
        // 薄层下限：干坡上 s==bed ⇒ 深度为 0，但确有薄层漫流 ⇒ 用 FILM_DEPTH 代替零。
        double h = Math.max(FILM_DEPTH, sK - bed[k]);
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
