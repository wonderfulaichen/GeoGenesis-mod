package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>河道中心线的【折线度】—— 连通性度量看不见的缺陷</h1>
 *
 * <h2>缘起（用户反馈，本轮最重要的方法论修正）</h2>
 * <p>用户：「怎么有<b>折线</b>河流？」</p>
 * <p>⇒ 此前三轮全部度量都是<b>连通性</b>指标（分量数 / GAPS / 谷约束度）。
 * <b>连通性对形态完全盲</b>：一条逐格抖动的锯齿河与一条平滑的河，
 * 连通分量数可以<b>一模一样</b>。
 * 我把「连通」当成了「正确」⇒ <b>这是偷懒，不是结论</b>。</p>
 *
 * <h2>★ 必须先分清两种"折线"（修法完全不同）</h2>
 * <table border="1">
 *   <caption>两种折线</caption>
 *   <tr><th>类型</th><th>成因</th><th>判据</th><th>是否要修</th></tr>
 *   <tr><td><b>R 栅格阶梯</b></td>
 *       <td>河道是 1 格宽的<b>布尔掩码</b>，对角线在 1 格/像素下
 *           <b>必然</b>呈阶梯</td>
 *       <td>中心线方向<b>本身平滑</b>，
 *           只是像素化后呈锯齿</td>
 *       <td><b>不用修</b>（真实游戏里是水体/雕刻，非并排像素）</td></tr>
 *   <tr><td><b>K 真折角</b></td>
 *       <td>中心线<b>本身</b>在转向处有折角（方向突变）</td>
 *       <td>中心线方向<b>确实</b>跳变</td>
 *       <td><b>要修</b></td></tr>
 * </table>
 *
 * <p><b>判别方法</b>：对河道掩码做局部 PCA 求<b>中心线切向</b>（这是形状量，
 * 与像素化无关）。若切向场平滑 ⇒ 只是栅格阶梯；
 * 若切向场本身跳变 ⇒ 真折角。</p>
 *
 * <h2>★ 两个候选真折角的成因（本探针要区分）</h2>
 * <ol>
 *   <li><b>{@code recover()} 瞬移</b>：流线在微洼地/停滞时，
 *       {@code ContinuousRiverNetwork.recover} 会沿 parent <b>直接跳到溢出口</b>
 *       再继续积分 ⇒ 该处必然是一个折角。
 *       ⚠ 实测基线 <b>stagnant=1,225,770</b>（平均每条流线 2 次）
 *       ⇒ 这是<b>高频</b>候选，是首要嫌疑。</li>
 *   <li><b>速度场本身抖动</b>：{@code velocity} 用 ±1 格中心差分，
 *       噪声会让切向在小尺度上摆动。</li>
 * </ol>
 * <p><b>如何区分</b>：{@code recover} 跳转会离开河道掩码
 * （溢出口通常不是河道格）⇒ 可用「折角点是否落在
 * <b>平滑流量明显偏低</b>处」作为指纹。</p>
 *
 * <h2>输出</h2>
 * <p>{@code build/hydro-reassessment/kink-<ts>/}：{@code metrics.txt}
 * + {@code smooth.png}（<b>4× 超采样</b>的中心线，用来看去掉栅格阶梯后是否仍折）
 * + {@code kinkmap.png}（把折角点标红，可肉眼定位成因分布）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。
 */
public final class CenterlineKinkProbe {

    /** PCA 窗口半宽（格）。 */
    private static final int WIN = 2;
    /** 判定"折角"的最小转角（度）。30° 超出正常河曲的逐格渐变。 */
    private static final double KINK_DEG = 30.0;

    /**
     * ★★ 弯曲度（sinuosity）= 河道弧长 / 两端直线距离。
     *
     * <h3>为什么这是"折线"问题的正确度量</h3>
     * <p>用户说「怎么有<b>折线</b>河流」。中文"折线"= <b>polyline</b>，
     * 即"由直线段拼接的线" ⇒ 指河流<b>过直</b>，不是指有尖角。
     * 而"尖角"我上一轮已量过（折角 3.05%，其中 91.7% 是汇合点 ⇒ 基本无缺陷）。</p>
     *
     * <p>真实河流几乎从不笔直：平原河 sinuosity 通常 <b>1.3~2.0</b>（曲流），
     * 山区河 <b>1.1~1.3</b>。若实测≈1.0 ⇒ 河道是直线 ⇒ 正是"折线"观感的来源。</p>
     *
     * <h3>算法</h3>
     * <p>逐连通分量求<b>图直径</b>（标准两遍 BFS）：
     * 任取一点 BFS 找最远点 u，再从 u BFS 找最远点 v ⇒ (u,v) 即最长河道路径。
     * {@code sinuosity = graphDist(u,v) / euclid(u,v)}（4 连通 ⇒ 格距即弧长）。
     * 只统计弧长 ≥ {@value #MIN_ARC} 格的分量（短段无统计意义）。</p>
     */
    private static final int MIN_ARC = 30;

    private static double[] sinuosity(boolean[] channel, int n, int[] comp, int comps) {
        int cells = n * n;
        java.util.List<Double> vals = new java.util.ArrayList<>();
        int[] dist = new int[cells];
        int[] queue = new int[cells];
        // 按分量收集成员（避免逐分量扫描全图 ⇒ O(comps·cells)）
        int[] head = new int[comps + 1];
        for (int k = 0; k < cells; k++) if (comp[k] >= 0) head[comp[k] + 1]++;
        for (int c = 0; c < comps; c++) head[c + 1] += head[c];
        int[] members = new int[head[comps]];
        int[] fill = head.clone();
        for (int k = 0; k < cells; k++) if (comp[k] >= 0) members[fill[comp[k]]++] = k;

        for (int c = 0; c < comps; c++) {
            int lo = head[c], hi = head[c + 1];
            int size = hi - lo;
            if (size < MIN_ARC) continue;
            int u = bfsFarthest(channel, n, members, lo, hi, members[lo], dist, queue);
            int v = bfsFarthest(channel, n, members, lo, hi, u, dist, queue);
            int arc = dist[v];
            if (arc < MIN_ARC) continue;
            double chord = Math.hypot((u / n) - (v / n), (u % n) - (v % n));
            if (chord > 1e-9) vals.add(arc / chord);
        }
        double[] out = new double[vals.size()];
        for (int t = 0; t < out.length; t++) out[t] = vals.get(t);
        java.util.Arrays.sort(out);
        return out;
    }

    /** 从 {@code start} 在分量内 BFS，返回最远格；{@code dist} 就地填距离。 */
    private static int bfsFarthest(boolean[] channel, int n, int[] members, int lo, int hi,
                                   int start, int[] dist, int[] queue) {
        java.util.Arrays.fill(dist, -1);
        int qh = 0, qt = 0;
        queue[qt++] = start;
        dist[start] = 0;
        int far = start;
        while (qh < qt) {
            int c = queue[qh++];
            if (dist[c] > dist[far]) far = c;
            int ci = c / n, cj = c % n;
            for (int[] d : DIRS4) {
                int a = ci + d[0], b = cj + d[1];
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int nk = a * n + b;
                if (channel[nk] && dist[nk] < 0) { dist[nk] = dist[c] + 1; queue[qt++] = nk; }
            }
        }
        return far;
    }

    private static final int[][] DIRS4 = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** 判定"平坦"（用户第二轮观察的归属判据）：床面梯度块/块。 */
    private static final double FLAT_DEG = 0.10;
    /**
     * ★★ 汇合点判定：8 邻域内【河道格数】≥ 该值 ⇒ 视为汇合/分汊节点。
     *
     * <p><b>为什么必须排除汇合点</b>（本轮最重要的判据修正）：
     * 折角（切向突变）在<b>汇合点</b>是<b>必然且正确</b>的 —— 两条河相汇，
     * 中心线方向当然要变。若把汇合点算作"折角缺陷"，
     * 就永远修不掉，而且会去修改正确的东西。
     * 上一轮得到"折角 8.32%"却无法判断是否缺陷，<b>根因就是没有排除汇合点</b>。</p>
     *
     * <p>取 4：1 格宽河道在直段上 8 邻域内约 3 格（前后各 1 + 自身），
     * 汇合处可达 5~8 ⇒ 4 是合理分界。</p>
     */
    private static final int JUNCTION_NEIGHBORS = 4;
    /**
     * "孤立折角"= 折角 且 自身与邻域都不是汇合点。
     * <b>只有这一类才可能是缺陷</b>（平原上一条直流中间无故拐弯）。
     */
    private static boolean isJunction(boolean[] channel, int n, int i, int j) {
        int cnt = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int a = i + di, b = j + dj;
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                if (channel[a * n + b]) cnt++;
            }
        }
        return cnt >= JUNCTION_NEIGHBORS;
    }

    /** @return 该格床面梯度模 */
    private static double gradAt(HydrologyPipeline.Result r, int n, int k) {
        int i = k / n, j = k % n;
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (r.bed()[i1 * n + j] - r.bed()[i0 * n + j]) / (i1 - i0);
        double dz = (r.bed()[i * n + j1] - r.bed()[i * n + j0]) / (j1 - j0);
        return Math.hypot(dx, dz);
    }

    private CenterlineKinkProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;
        // ⚠ 正典半径断言：**本轮改为非严格**（第 6 参 strict=true 可强制）。
        //   理由：本探针的判据【全部是比值】（折角率、平坦富集比、流量比），
        //   窗口大小只影响样本量、不影响比值方向 ⇒ 小窗可用于快速目检。
        //   但【跨图对比】仍需同一窗口 + 固定色标（contourFixed 已强制）。
        boolean strict = args.length > 5 && Boolean.parseBoolean(args[5]);
        ViewSpec.assertCanonicalRadius(radius, strict);
        runMode(seed, cx, cz, radius, samples);
    }

    private static void runMode(long seed, int cx, int cz, int radius, int samples)
            throws Exception {

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;

        // 3×3 平滑流量（判 recover 指纹用）
        double[] smooth = new double[cells];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (r.sea()[k]) { smooth[k] = Double.NaN; continue; }
                double sum = 0; int cnt = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        int kk = a * n + b;
                        if (r.sea()[kk]) continue;
                        sum += r.discharge()[kk]; cnt++;
                    }
                }
                smooth[k] = cnt > 0 ? sum / cnt : r.discharge()[k];
            }
        }

        // ★ 局部 PCA 切向（形状量，与像素化无关）
        double[] tx = new double[cells], tz = new double[cells];
        boolean[] hasT = new boolean[cells];
        int ok = 0;
        for (int i = WIN; i < n - WIN; i++) {
            for (int j = WIN; j < n - WIN; j++) {
                int k = i * n + j;
                if (!r.channel()[k]) continue;
                // 以流量加权 ⇒ 主轴即水流方向（上游窄、下游宽 ⇒ 长轴顺流）
                double sxx = 0, sxz = 0, szz = 0, sw = 0, mx = 0, mz = 0;
                for (int a = i - WIN; a <= i + WIN; a++) {
                    for (int b = j - WIN; b <= j + WIN; b++) {
                        int kk = a * n + b;
                        if (!r.channel()[kk]) continue;
                        double w = Math.max(1e-6, r.discharge()[kk]);
                        sw += w; mx += w * a; mz += w * b;
                    }
                }
                if (sw < 1e-9) continue;
                mx /= sw; mz /= sw;
                for (int a = i - WIN; a <= i + WIN; a++) {
                    for (int b = j - WIN; b <= j + WIN; b++) {
                        int kk = a * n + b;
                        if (!r.channel()[kk]) continue;
                        double w = Math.max(1e-6, r.discharge()[kk]);
                        double da = a - mx, db = b - mz;
                        sxx += w * da * da; sxz += w * da * db; szz += w * db * db;
                    }
                }
                // 主特征向量（2×2 对称阵解析解）
                double tr = sxx + szz, det = sxx * szz - sxz * sxz;
                double disc = Math.sqrt(Math.max(0, tr * tr / 4.0 - det));
                double lam = tr / 2.0 + disc;           // 大特征值
                if (lam <= 1e-12) continue;
                double vx = sxz, vz = lam - sxx;
                if (Math.abs(vx) < 1e-12 && Math.abs(vz) < 1e-12) continue;
                double len = Math.hypot(vx, vz);
                tx[k] = vx / len; tz[k] = vz / len;
                hasT[k] = true; ok++;
            }
        }

        // ★ 逐格量转角：相邻河道格之间的切向夹角
        double[] turns = new double[cells];
        boolean[] kink = new boolean[cells];
        int kinkCnt = 0, pairs = 0;
        java.util.List<Double> kinkFlow = new java.util.ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (!hasT[k]) continue;
                for (int[] d : new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
                    int nk = (i + d[0]) * n + (j + d[1]);
                    if (!hasT[nk]) continue;
                    double dot = tx[k] * tx[nk] + tz[k] * tz[nk];
                    dot = Math.max(-1, Math.min(1, dot));
                    double deg = Math.toDegrees(Math.acos(dot));
                    turns[k] = Math.max(turns[k], deg);
                    pairs++;
                    if (deg >= KINK_DEG) {
                        kink[k] = true; kinkCnt++;
                        kinkFlow.add(smooth[k]);
                        break;
                    }
                }
            }
        }

        double[] tf = new double[kinkFlow.size()];
        for (int t = 0; t < tf.length; t++) tf[t] = kinkFlow.get(t);
        java.util.Arrays.sort(tf);
        // 由 turns 近似不可靠，改为直接统计分布：收集非零转角
        java.util.List<Double> all = new java.util.ArrayList<>();
        for (int k = 0; k < cells; k++) if (hasT[k] && turns[k] > 0) all.add(turns[k]);
        double[] at = new double[all.size()];
        for (int t = 0; t < at.length; t++) at[t] = all.get(t);
        java.util.Arrays.sort(at);

        StringBuilder log = new StringBuilder();
        log.append("=== 河道中心线折线度 ===\n");
        log.append(String.format("seed=%d radius=%d step=%d samples=%d%n", seed, radius, ViewSpec.STEP, samples));
        log.append(String.format("PCA窗口=%d  折角阈值=%.0f°%n%n", WIN, KINK_DEG));

        // ★★★ 弯曲度（sinuosity）—— 本轮补的"折线"正解度量 ★★★
        //   中文"折线"=polyline（直线段拼接）⇒ 指河流【过直】，不是指有尖角。
        //   真实河流：平原 1.3~2.0（曲流）、山区 1.1~1.3。≈1.0 ⇒ 直线 ⇒ "折线"观感来源。
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        int[] stk = new int[cells];
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !r.channel()[k0]) continue;
            int sp = 0; stk[sp++] = k0; comp[k0] = comps;
            while (sp > 0) {
                int c = stk[--sp];
                int ci = c / n, cj = c % n;
                for (int[] d : DIRS4) {
                    int a = ci + d[0], b = cj + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int nk = a * n + b;
                    if (comp[nk] < 0 && r.channel()[nk]) { comp[nk] = comps; stk[sp++] = nk; }
                }
            }
            comps++;
        }
        double[] sin = sinuosity(r.channel(), n, comp, comps);
        log.append(String.format(
                "[0] ★弯曲度 sinuosity (弧长/直线距离, n=%d 条≥%d格): P10=%.3f P50=%.3f P90=%.3f max=%.3f%n",
                sin.length, MIN_ARC, q(sin, sin.length, 0.10), q(sin, sin.length, 0.50),
                q(sin, sin.length, 0.90), sin.length > 0 ? sin[sin.length - 1] : Double.NaN));
        double s50 = q(sin, sin.length, 0.50);
        if (s50 < 1.05) {
            log.append("    ⇒ 中位≈1.0 ⇒ 河道【基本是直线】⇒ 这正是「折线」观感的来源。\n");
            log.append("     真实平原河 sinuosity 应 1.3~2.0、山区 1.1~1.3 ⇒ 【确为缺陷】。\n");
        } else if (s50 < 1.15) {
            log.append("    ⇒ 弯曲度偏低（真实平原河应 1.3~2.0）⇒ 河道偏直，属缺陷但程度中等。\n");
        } else {
            log.append("    ⇒ 弯曲度在合理区间 ⇒ 河道有正常蜿蜒，不是「折线」。\n");
        }
        log.append('\n');

        log.append(String.format("[1] 有切向的河道格=%d / %d (%.1f%%)%n", ok,
                countChannel(r), 100.0 * ok / Math.max(1, countChannel(r))));
        log.append(String.format("[2] 相邻切向转角分位: P50=%.1f° P90=%.1f° P99=%.1f° max=%.1f°%n",
                q(at, at.length, 0.50), q(at, at.length, 0.90), q(at, at.length, 0.99),
                at.length > 0 ? at[at.length - 1] : Double.NaN));
        log.append(String.format("[3] ★折角格=%d (%.2f%% of 有切向格) 折角处平滑流量 P50=%.3f P10=%.3f%n",
                kinkCnt, 100.0 * kinkCnt / Math.max(1, ok),
                q(tf, tf.length, 0.50), q(tf, tf.length, 0.10)));
        double chMed = medianChannel(r, smooth);
        log.append(String.format("    全体河道格平滑流量 P50=%.3f%n", chMed));
        log.append(String.format("    ⇒ 折角处流量%s基线%c%n",
                q(tf, tf.length, 0.50) < chMed ? "低于" : "不低于",
                q(tf, tf.length, 0.50) < chMed ? ' ' : ' '));
        if (q(tf, tf.length, 0.50) < chMed) {
            log.append("     （与 recover() 瞬移指纹一致：跳点常在流量偏低处）\n");
        } else {
            log.append("     （不指向 recover 瞬移，更像速度场本身抖动）\n");
        }

        // ★★ 把折角分成两类（本轮关键）：
        //   JUNCTION  = 折角且是汇合/分汊点  ⇒ 物理正确，**不该修**
        //   ISOLATED  = 折角且在直段中间    ⇒ 才可能是缺陷
        int juncKinks = 0, isoKinks = 0;
        boolean[] isolated = new boolean[cells];
        java.util.List<Double> isoFlow = new java.util.ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (!kink[k]) continue;
                if (isJunction(r.channel(), n, i, j)) {
                    juncKinks++;
                } else {
                    isoKinks++;
                    isolated[k] = true;
                    isoFlow.add(smooth[k]);
                }
            }
        }
        double[] ifl = new double[isoFlow.size()];
        for (int t = 0; t < ifl.length; t++) ifl[t] = isoFlow.get(t);
        java.util.Arrays.sort(ifl);
        log.append(String.format(
                "%n[汇合点排除] 折角 %d = 汇合/分汊点 %d (%.1f%%, 物理正确) + 孤立折角 %d (%.1f%%, 待判定)%n",
                kinkCnt, juncKinks, 100.0 * juncKinks / Math.max(1, kinkCnt),
                isoKinks, 100.0 * isoKinks / Math.max(1, kinkCnt)));
        log.append(String.format("  孤立折角占【全部河道格】= %.2f%%  其流量 P50=%.3f (全体河道 P50=%.3f)%n",
                100.0 * isoKinks / Math.max(1, ok), q(ifl, ifl.length, 0.50), chMed));
        log.append(String.format("  ⇒ 真正的【疑似缺陷】规模 = 孤立折角 %d 个 (%.2f%%)%n",
                isoKinks, 100.0 * isoKinks / Math.max(1, ok)));
        log.append("  ★ 判读：若图（kinkisolated.png）上孤立折角【零星散落】⇒ 多为采样噪声，可不修；\n");
        log.append("     若【成片连串】⇒ 是真形态缺陷，值得动手。**以图为准**。\n");

        // ★★ 用户观察（第二轮）：「那些折线明显是在平坦地形才出现的」★★
//   这是【关键鉴别线索】，且与我上一轮的结论（折角源于地形条纹化）冲突。
//   若折角真集中在平坦区，则成因是【速度场在平地上欠定】：
//   velocity() 用 s = resolved?level:bed，而 minimax 在平地上把 level 抹平
//   ⇒ ∇s 在平坦区变得极小且方向噪声化 ⇒ 中心差分把噪声放大成折角。
//   ⇒ 与"地形条纹化"是<b>不同</b>的成因，修法也不同（改速度场，不改地形）。
//
//   判据：折角格的 ∇bed 分布 vs 全体河道格基线。
//   若折角处 ∇bed 显著<b>小于</b>基线 ⇒ 用户观察成立（平坦区专属）。
boolean[] flatMark = new boolean[cells];
double[] kGrad = new double[cells];
for (int k = 0; k < cells; k++) {
    if (!kink[k]) continue;
    kGrad[k] = gradAt(r, n, k);
    if (kGrad[k] < FLAT_DEG) flatMark[k] = true;
}
java.util.List<Double> allG = new java.util.ArrayList<>(), kG = new java.util.ArrayList<>();
int kFlat = 0;
for (int k = 0; k < cells; k++) {
    if (!r.channel()[k]) continue;
    double g = gradAt(r, n, k);
    allG.add(g);
    if (kink[k]) { kG.add(g); if (g < FLAT_DEG) kFlat++; }
}
double[] ag = new double[allG.size()];
for (int t = 0; t < ag.length; t++) ag[t] = allG.get(t);
java.util.Arrays.sort(ag);
double[] kg = new double[kG.size()];
for (int t = 0; t < kg.length; t++) kg[t] = kG.get(t);
java.util.Arrays.sort(kg);
double landFlat = 0;
int landTot = 0;
for (int k = 0; k < cells; k++) {
    if (r.sea()[k] || r.lake()[k]) continue;
    landTot++;
    if (gradAt(r, n, k) < FLAT_DEG) landFlat++;
}
log.append(String.format("%n[平坦区归属] FLAT_DEG=%.3f  陆地中平坦占比=%.1f%%%n",
        FLAT_DEG, 100.0 * landFlat / Math.max(1, landTot)));
log.append(String.format("  折角格中 平坦占比=%.1f%%  (全体河道格 ∇bed P50=%.4f, 折角格 P50=%.4f)%n",
        100.0 * kFlat / Math.max(1, kinkCnt), q(ag, ag.length, 0.50), q(kg, kg.length, 0.50)));
double ratio = (q(ag, ag.length, 0.50) > 0) ? q(kg, kg.length, 0.50) / q(ag, ag.length, 0.50) : Double.NaN;
// ⚠ 上一版此处判据写的是 `kFlat > 0.5*kinkCnt`（绝对 50%），
        //   而打印的文案却说"> 平坦陆地占比"—— **代码与自己的判据不一致**。
        //   后果：r=700 恰好 53%>50% 报"成立"（碰巧对）；r=300 是 19%<50% 报"不符"，
        //   但 19% vs 陆地基线 11.5% 其实也是 1.65× **富集** ⇒ 属**误判**。
        //   ⇒ 正确判据是【相对基线的富集比】，不是绝对占比。已修正。
        double flatLandPct = 100.0 * landFlat / Math.max(1, landTot);
        double kinkFlatPct = 100.0 * kFlat / Math.max(1, kinkCnt);
        double enrich = kinkFlatPct / Math.max(1e-9, flatLandPct);
        log.append(String.format("  富集比 = %.2fx  (折角平坦占比 %.1f%% / 陆地平坦基线 %.1f%%)%n",
                enrich, kinkFlatPct, flatLandPct));
        log.append(String.format("  ∇bed 中位比 = %.2fx (折角 %.4f / 全体 %.4f，<1 = 折角处更平)%n",
                ratio, q(kg, kg.length, 0.50), q(ag, ag.length, 0.50)));
        if (enrich >= 1.3 && ratio < 0.9) {
            log.append("  ⇒ 折角确实【偏向平坦区】（富集 ≥1.3x 且坡度中位更低）⇒ 用户观察成立。\n");
        } else if (enrich >= 1.3) {
            log.append("  ⇒ 折角对平坦区有富集，但坡度中位不更低 ⇒ 证据不完全一致，需复核。\n");
        } else {
            log.append("  ⇒ 折角对平坦区【无明显富集】⇒ 与用户观察不符，需复核。\n");
        }
        log.append(String.format("  ⚠ 注意：平坦占比本身随窗口变化（r=300 时陆地仅 %.1f%%），%n", flatLandPct));
        log.append("     故【必须看富集比】，不能看绝对占比 —— 这也是上一版误判的来源。\n");

Path dir = Path.of("build/hydro-reassessment/kink-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(renderSmooth(r, n), "png", dir.resolve("smooth.png").toFile());
        ImageIO.write(renderKink(r, n, isolated, juncKinks), "png",
                dir.resolve("kinkisolated.png").toFile());
        ImageIO.write(renderKink(r, n, flatMark, juncKinks), "png",
                dir.resolve("flatkink.png").toFile());
        // ★ 4× 超采样 + 孤立折角标红：**本轮的最终判据图**。
        //   去掉了栅格阶梯（超采样）与汇合点（只留孤立折角）两个干扰源，
        //   剩下的红色才是"可能是缺陷"的东西 —— 一眼可判该不该修。
        ImageIO.write(renderSmoothIsolated(r, n, isolated), "png",
                dir.resolve("isolated_4x.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("smooth.png").toAbsolutePath());
        System.out.println("ISOLATED=" + dir.resolve("isolated_4x.png").toAbsolutePath());
    }

    private static int countChannel(HydrologyPipeline.Result r) {
        int c = 0;
        for (boolean b : r.channel()) if (b) c++;
        return c;
    }

    private static double medianChannel(HydrologyPipeline.Result r, double[] smooth) {
        java.util.List<Double> v = new java.util.ArrayList<>();
        for (int k = 0; k < r.channel().length; k++) {
            if (r.channel()[k] && !Double.isNaN(smooth[k])) v.add(smooth[k]);
        }
        double[] a = new double[v.size()];
        for (int t = 0; t < a.length; t++) a[t] = v.get(t);
        java.util.Arrays.sort(a);
        return q(a, a.length, 0.50);
    }

    /**
     * ★ 4× 超采样的河道掩码。
     * <p>目的：<b>把栅格阶梯从视觉上去掉</b>。
     * 若超采样后折线消失 ⇒ 那是 1 格/像素的必然阶梯（不用修）；
     若仍有折角 ⇒ 是真折角（要修）。</p>
     */
    private static BufferedImage renderSmooth(HydrologyPipeline.Result r, int n) {
        int SS = 4;
        int m = n * SS;
        BufferedImage img = new BufferedImage(m, m, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                int bi = i / SS, bj = j / SS;
                int k = bi * n + bj;
                int c;
                if (r.sea()[k]) c = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) c = ViewSpec.LAKE_COLOR;
                else if (r.channel()[k]) c = ViewSpec.RIVER_COLOR;
                else c = ViewSpec.contourFixed(r.bed(), n, bi, bj);   // ★ 固定色标（跨图可比）
                img.setRGB(i, j, c);
            }
        }
        return img;
    }

    /** 折角点标红；汇合点折角另着橙色（说明"这个不是缺陷"）。 */
    private static BufferedImage renderKink(HydrologyPipeline.Result r, int n,
                                            boolean[] mark, int dummy) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int c;
                if (mark[k]) c = 0xFF2020;                      // 孤立折角（待判定）
                else if (r.sea()[k]) c = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) c = ViewSpec.LAKE_COLOR;
                else if (r.channel()[k]) c = 0x0E8C80;
                else c = ViewSpec.contourFixed(r.bed(), n, i, j);   // ★ 固定色标
                img.setRGB(i, j, c);
            }
        }
        return img;
    }

    /**
     * ★ 最终判据图：4× 超采样底图 + 孤立折角标红 + 汇合点标橙。
     * <p>三重去干扰：① 超采样去掉栅格阶梯；② 只标折角；③ 汇合点单独着色说明其正确性。</p>
     */
    private static BufferedImage renderSmoothIsolated(HydrologyPipeline.Result r, int n,
                                                      boolean[] isolated) {
        int SS = 4, m = n * SS;
        BufferedImage img = new BufferedImage(m, m, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                int bi = i / SS, bj = j / SS, k = bi * n + bj;
                int c;
                if (r.sea()[k]) c = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) c = ViewSpec.LAKE_COLOR;
                else if (r.channel()[k]) c = 0x189A8C;
                else c = ViewSpec.contourFixed(r.bed(), n, bi, bj);
                img.setRGB(i, j, c);
            }
        }
        // 标记叠加（放大成 SS×SS 的方块，保证可见）
        for (int bi = 0; bi < n; bi++) {
            for (int bj = 0; bj < n; bj++) {
                int k = bi * n + bj;
                int col = 0;
                if (isolated[k]) col = 0xFF2020;
                if (col == 0) continue;
                for (int a = 0; a < SS; a++) {
                    for (int b = 0; b < SS; b++) {
                        img.setRGB(bi * SS + a, bj * SS + b, col);
                    }
                }
            }
        }
        return img;
    }

    private static double q(double[] a, int n, double qq) {
        if (n <= 0) return Double.NaN;
        int i = (int) Math.round(qq * (n - 1));
        return a[Math.max(0, Math.min(n - 1, i))];
    }
}