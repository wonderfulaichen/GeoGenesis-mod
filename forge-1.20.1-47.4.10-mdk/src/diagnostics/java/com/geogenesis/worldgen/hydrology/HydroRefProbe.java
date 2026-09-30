package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * ★★★ 2026-09-25【物理水文参照图 —— 移植 geotransport 随机积分器】★★★
 *
 * <p><b>用户裁定</b>："我们是做物理水文参照图啊，你不要搞错了" +
 * "还有物理流体水文这不是有参考吗？（参考/sources/geotransport-main）"</p>
 *
 * <h2>为什么用这个参考（而不是自造的浅水/液滴模型）</h2>
 * <p>参照实现来自 <i>Stochastic Geomorphological Transport for Terrain Erosion
 * Simulation</i>（McDonald &amp; Cordonnier，erosiv.studio）的官方参考代码
 * {@code example/accumulate_dem.py} + {@code source/geotransport/{gradient,path}.cu}。
 * 它的水文口径是<b>正式发表的方法</b>，且方向连续、无格点量化：</p>
 * <ol>
 *   <li><b>速度场</b>：{@code v = −∇h}（中心差分梯度，边界退化为一侧差分；
 *       原实现 {@code __gradient}）。方向连续 ⇒ <b>没有 D8 的方向量化伪影</b>
 *       （旧水文在平原画出的"平行直线"正是 D8 的产物）。</li>
 *   <li><b>汇流量 = 蒙特卡洛流线积分</b>（原实现 {@code __solve_uniform}）：
 *       甩 {@code count} 个粒子从<b>连续随机位置</b>出发，沿流线逐格穿越
 *       （步长 = 到下一格边界的距离，clamp 到 √2），每进入新格累积一次通量。
 *       这是线性守恒律的无网格蒙特卡洛估计器 ⇒ 汇流量天然是概率密度的积分，
 *       而非"格点计数"。</li>
 *   <li><b>归一化</b>（原实现 {@code __normalize}）：把累积通量转成每格过流量。</li>
 * </ol>
 *
 * <h2>本移植的两处必要适配（都有物理依据，已在代码处标注）</h2>
 * <ul>
 *   <li><b>在填洼面上积分</b>：原实现对原始 DEM 积分，粒子在局部洼地停住
 *       （源码注释："pits basically don't really go away ... we rely on the
 *       well-structuredness of the velocity field"）。为使参照能给出<b>完整干流</b>
 *       （源头→入海），在 priority-flood 填洼面（含 ε 单调梯度）上取梯度
 *       ⇒ 速度场结构良好、粒子可穿过洼地（湖）继续下行。坑洼处的水面高度
 *       另由"洼地蓄水"图层给出。</li>
 *   <li><b>湖层 = 洼地蓄水</b>（用户定义）：{@code fill − h > 0.5} 即"水在洼地堆积"
 *       （0.5 = Minecraft 1 块格的放水底线）。参照湖不设任何面积/深度门槛。</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <pre>
 *   build/hydroref/ref_all.png    山体阴影 + 海 + 湖(蓝) + 河(青)
 *   build/hydroref/ref_flow.png   汇流量热力（黄=小、红=大）+ 河
 *   build/hydroref/ref_lakes.png  仅湖层
 * </pre>
 * <p><b>确定性</b>：固定种子 RNG、固定遍历顺序、单线程 ⇒ 逐位可复现。</p>
 * <p><b>不碰生产</b>：只读地形、不建河网、不生成 chunk、不改全局开关。</p>
 *
 * <pre>{@code gradlew runHydroRefProbe [-PprobeArgs="seed bx bz radius [margin samples streamFrac]"]}</pre>
 */
public final class HydroRefProbe {

    private HydroRefProbe() { }

    /** 粒子最小速度（|v| 低于此值判停滞；原实现 epsilon=1e-16，这里取更保守的 1e-12）。 */
    private static final double V_EPS = 1e-12;

    /**
     * ★★★ 2026-09-25【动量滤波 —— 修"河流一折一折"（用户判据）】★★★
     *
     * <p>成因：速度场建在 priority-flood 填洼面上。山区坡度大 ⇒ 梯度平滑 ⇒ 河线平滑；
     * 但【平原/填洼区】的 fill 表面是"平坦 + 每格 +1e-5 的 ε 阶梯"——其中心差分方向
     * 是阶梯树形 direction，一格一变 ⇒ 无惯性粒子逐格跟着折 ⇒ 图上折线。</p>
     *
     * <p><b>修法来自第二个参考 SimpleHydrology（同一作者的粒子水文）</b>：其水滴
     * {@code speed += 下坡力; speed = normalize(speed)} —— 方向是<b>累积（惯性）</b>出来的，
     * 不是每步重采样 ⇒ 平坦区靠惯性直行穿过 ε 阶梯，河线平滑蜿蜒。</p>
     *
     * <p>本移植：{@code dir = normalize(INERTIA·dir_prev + (1−INERTIA)·v̂(pos))}
     * —— 一阶动量滤波（INERTIA=0.85 ≈ 方向时间常数 ~6 格）。物理依据 = 水的惯性；
     * 兼容两个参考（geotransport 的流线积分 + SimpleHydrology 的动量粒子）。</p>
     */
    private static final double INERTIA = 0.85;

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 700;
        int margin = args.length > 4 ? Integer.parseInt(args[4]) : 192;
        // 粒子数（原实现示例用 8192×64 = 524,288；本窗口更大，默认 8M）
        int samples = args.length > 5 ? Integer.parseInt(args[5]) : 8_000_000;
        // 成河阈值 = 流域占比（该格上游面积 / 全域面积）。默认 0.0088 ≈ 旧口径
        // 7000 格 / 795,664 格（骨架 chanThreshold 的同源标定）。
        double streamFrac = args.length > 6 ? Double.parseDouble(args[6]) : 0.005;

        long t0 = System.nanoTime();
        TerrainParams tp = TerrainParams.defaults();
        double hs = tp.horizontalScale();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        // ★ 刻意不建 GeoGenesisTerrain / RiverLineNetwork —— 参照与现有水文完全独立
        double seaLevel = gen.heightCurve().seaLevelY();

        // ===== ① 采样侵蚀后地形（1 块分辨率，窗口 + margin）=====
        int N = 2 * (radius + margin) + 1;
        int off = margin;
        System.out.printf("物理参照（geotransport 随机积分）：窗口 %d 块 + margin %d ⇒ %d² 块%n",
                2 * radius + 1, margin, N);
        double[][] h = new double[N][N];
        long tS = System.nanoTime();
        for (int j = 0; j < N; j++) {
            double wz = (bz - radius - margin + j) / hs;
            for (int i = 0; i < N; i++) {
                double wx = (bx - radius - margin + i) / hs;
                h[j][i] = gen.erodedHeightForRouting(wx, wz);
            }
            if (j % 400 == 0) {
                System.out.printf("  采样 %d/%d 行（%d ms）%n", j, N,
                        (System.nanoTime() - tS) / 1_000_000L);
            }
        }
        System.out.printf("① 地形采样完成：%d ms%n", (System.nanoTime() - tS) / 1_000_000L);

        // ===== ② priority-flood 填洼（含 ε 单调梯度）=====
        //   用途①（速度场）：在填洼面上求梯度 ⇒ 粒子不会被局部坑洼卡死（可走完源头→入海）；
        //   用途②（湖层）  ：fill − h > 0.5 = 洼地蓄水（用户的湖泊定义）。
        long tF = System.nanoTime();
        double[][] fill = priorityFlood(h, seaLevel, N);
        System.out.printf("② 填洼完成：%d ms%n", (System.nanoTime() - tF) / 1_000_000L);

        // ===== ③ 速度场 v = −∇h（在【填洼面】上；原实现 __gradient）=====
        //   ⚠ 必须在 double 上求差分后转 float：ε 梯度量级 1e-5，若在 float(~100) 上
        //     直接差分会被浮点分辨率（≈7.6e-6）吞掉。
        long tG = System.nanoTime();
        float[] vx = new float[N * N], vz = new float[N * N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                double gx = (i > 0 && i < N - 1)
                        ? 0.5 * (fill[j][i + 1] - fill[j][i - 1])
                        : (i == 0 ? fill[j][1] - fill[j][0] : fill[j][N - 1] - fill[j][N - 2]);
                double gz = (j > 0 && j < N - 1)
                        ? 0.5 * (fill[j + 1][i] - fill[j - 1][i])
                        : (j == 0 ? fill[1][i] - fill[0][i] : fill[N - 1][i] - fill[N - 2][i]);
                vx[j * N + i] = (float) (-gx);      // 下坡方向（原实现 gradient 后 multiply(-1)）
                vz[j * N + i] = (float) (-gz);
            }
        }
        System.out.printf("③ 速度场就绪：%d ms%n", (System.nanoTime() - tG) / 1_000_000L);

        // ===== ④ 蒙特卡洛流线积分（原实现 __solve_uniform；decay=0, source=1）=====
        long tM = System.nanoTime();
        float[] flux = monteCarlo(vx, vz, N, samples, seed);
        System.out.printf("④ 蒙特卡洛积分完成：%d 采样 / %d ms%n", samples,
                (System.nanoTime() - tM) / 1_000_000L);

        // ===== ⑤ 图层：湖 = 洼地蓄水；河 = 汇流量 ≥ 阈值 =====
        boolean[][] lakeS = new boolean[N][N];
        boolean[][] riverS = new boolean[N][N];
        int lakeCells = 0, riverCells = 0, lakeCount = 0;
        float maxAcc = 0f;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                if (h[j][i] <= seaLevel) continue;                  // 海另画
                float acc = flux[j * N + i];                        // = 上游面积占比 ∈ [0,1]
                if (acc > maxAcc) maxAcc = acc;
                if (fill[j][i] - h[j][i] > 0.5) { lakeS[j][i] = true; lakeCells++; }
                else if (acc >= streamFrac) { riverS[j][i] = true; riverCells++; }
            }
        }
        // ===== ⑤b 湖编号 + 每湖最大汇流格（主入流口）=====
        int[] lakeId = new int[N * N];                    // 0 = 非湖；id 从 1 起
        int[] lakeMaxAccCell = new int[0];
        {
            boolean[][] seen = new boolean[N][N];
            java.util.List<Integer> maxAccCells = new java.util.ArrayList<>();
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    if (!lakeS[j][i] || seen[j][i]) continue;
                    final int id = maxAccCells.size() + 1;
                    float bestAcc = -1f;
                    int bestCell = j * N + i;
                    java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                    q.add(new int[]{i, j});
                    seen[j][i] = true;
                    while (!q.isEmpty()) {
                        int[] c = q.poll();
                        int k = c[1] * N + c[0];
                        lakeId[k] = id;
                        if (flux[k] > bestAcc) { bestAcc = flux[k]; bestCell = k; }
                        for (int dj = -1; dj <= 1; dj++) {
                            for (int di = -1; di <= 1; di++) {
                                int ni = c[0] + di, nj = c[1] + dj;
                                if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
                                if (lakeS[nj][ni] && !seen[nj][ni]) {
                                    seen[nj][ni] = true;
                                    q.add(new int[]{ni, nj});
                                }
                            }
                        }
                    }
                    maxAccCells.add(bestCell);
                }
            }
            lakeCount = maxAccCells.size();
            lakeMaxAccCell = new int[lakeCount];
            for (int l = 0; l < lakeCount; l++) lakeMaxAccCell[l] = maxAccCells.get(l);
        }

        // ===== ⑤c 流向图 next[]（速度方向；平坦退化 fill 面 D8）=====
        int[] nextIdx = new int[N * N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int k = j * N + i;
                nextIdx[k] = -1;
                if (h[j][i] <= seaLevel) continue;                    // 海
                int best = -1;
                double sx = vx[k], sz = vz[k];
                double len = Math.hypot(sx, sz);
                if (len > 1e-9) {
                    double dx = sx / len, dz = sz / len;
                    double bestDot = 0.25;                            // 需显著指向该邻居
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            if (di == 0 && dj == 0) continue;
                            int ni = i + di, nj = j + dj;
                            if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
                            double nl = Math.hypot(di, dj);
                            double dot = (di * dx + dj * dz) / nl;
                            if (dot > bestDot + 1e-12) { bestDot = dot; best = nj * N + ni; }
                        }
                    }
                }
                if (best < 0) {
                    // 平坦（ε 阶梯的分量太散）：fill 面 D8 —— 最低 fill 邻居；并列取小下标
                    double bf = fill[j][i] - 1e-9;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            if (di == 0 && dj == 0) continue;
                            int ni = i + di, nj = j + dj;
                            if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
                            double nf = fill[nj][ni];
                            if (nf < bf) { bf = nf; best = nj * N + ni; }
                        }
                    }
                }
                nextIdx[k] = best;
            }
        }

        // ===== ⑤d 每湖溢口：从主入流口沿 next[] 走到离开湖 ⇒ 第一个非湖格 =====
        int[] spillOut = new int[lakeCount];
        for (int l = 0; l < lakeCount; l++) {
            int c = lakeMaxAccCell[l];
            int so = -1;
            int guard = 0;
            while (c >= 0 && guard++ < 4 * N) {
                int nx = nextIdx[c];
                if (nx < 0) break;                                     // 出窗/终点 ⇒ 无溢口
                if (lakeId[nx] == 0) { so = nx; break; }               // 离湖 ⇒ 溢口外侧格
                c = nx;
            }
            spillOut[l] = so;
        }

        // ===== ⑤e 生命周期追踪：源头→汇流→入湖→【最低溢口】→下泄→入海 =====
        //   【修什么】用户判据："河流的起点位置没在蓄水溢出口。明明湖泊就是蓄水的
        //   状态，河流就是下泄的状态。" 旧画法 = 汇流量超阈值的格子染色 ⇒ 支流在
        //   流量达标处凭空开始（既不在源头也不在溢口）。正解 = 按拓扑追踪完整折线：
        //     · 起点 = 分水岭真源头（逆流回溯到无入流的脊格）；
        //     · 入湖 = 当前段终止（湖内不画线，湖面由湖层负责）；
        //     · 出湖 = 从【该湖最低溢口】接续下一段 —— 河流起点必在溢出口；
        //     · 终点 = 入海 / 内流湖（终态蓄水）/ 窗口边缘。
        long tT = System.nanoTime();
        int[] inflow = new int[N * N];                    // 河格的河格入度
        for (int k = 0; k < N * N; k++) {
            int nx = nextIdx[k];
            if (nx >= 0 && riverS[nx / N][nx % N] && riverS[k / N][k % N]) inflow[nx]++;
        }
        java.util.List<Integer> sources = new java.util.ArrayList<>();
        for (int k = 0; k < N * N; k++) {
            if (riverS[k / N][k % N] && inflow[k] == 0) sources.add(k);
        }
        // ★ 大河优先：源头按汇流量降序 ⇒ 主干先画，支流靠近时并入（邻域合并），
        //   避免平原"多股平行束"（每源头各画一条近似平行轨迹的叠画噪声）。
        sources.sort((a, b) -> Float.compare(flux[b], flux[a]));
        java.util.List<java.util.List<Integer>> runs = new java.util.ArrayList<>();
        int endSea = 0, endLakeTerminal = 0, endEdge = 0, endLoop = 0;
        int spillJumps = 0, basinEntries = 0;
        // ★ 2026-09-25【全局"已画"标记】：旧版每条轨迹只防自身成环 ⇒ 不同源头的
        //   轨迹在下游主干上反复叠画（实测图：平原出现几十条平行束/发卡弯）。
        //   正解 = 河网是一棵树：轨迹一碰到【任何先前轨迹已画的格】即并入停画，
        //   每格只画一次（这也是标准河网提取的做法）。
        int[] drawnStamp = new int[N * N];                // 记 traceId（0 = 未画）
        int[] lakeStamp = new int[lakeCount + 1];         // 同一条线内防湖环
        int traceId = 0;
        for (int src : sources) {
            // —— 逆流回溯到真源头（每步选汇流最大的入流邻居）——
            int cur = src;
            int guard = 0;
            while (guard++ < 4 * N) {
                int bestUp = -1;
                float bestAcc = 0f;
                int ci = cur % N, cj = cur / N;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
                        int nb = nj * N + ni;
                        if (nextIdx[nb] != cur) continue;               // 必须流入 cur
                        if (h[nb / N][nb % N] <= seaLevel) continue;
                        if (flux[nb] > bestAcc) { bestAcc = flux[nb]; bestUp = nb; }
                    }
                }
                if (bestUp < 0) break;                                  // 分水岭真源头
                cur = bestUp;
            }
            // —— 顺流追踪【动量粒子连续积分，与 MC 粒子同款】——
            //   ★ 不用逐格方向图 nextIdx 正向追踪：ε 阶梯在平原给出"电路板"式直角
            //     折线（实测图）。改为与蒙特卡洛粒子完全同款：双线性采样速度 +
            //     INERTIA 动量滤波 + 格心穿越步长 ⇒ 单条轨迹就是一滴水的真实流线。
            //   ★ 入湖即断段（湖内不画线，湖面由湖层负责），从该湖【最低溢口】
            //     （priority-flood 保证的最低通道）重新出发 ⇒ 河流起点必在溢出口。
            traceId++;
            java.util.ArrayList<Integer> run = new java.util.ArrayList<>();
            int endType = -1;                            // 0=SEA 1=LAKE 2=EDGE 3=LOOP
            int steps = 0;
            double px = cur % N + 0.5, pz = cur / N + 0.5;
            double dirx = 0, dirz = 0;
            boolean hasDir = false;
            int lastCell = -1;
            while (steps++ < 8 * N) {
                int ci = (int) px, cj = (int) pz;
                if (ci < 0 || cj < 0 || ci >= N || cj >= N) { endType = 2; break; }
                int k = cj * N + ci;
                if (k != lastCell) {
                    // ★ 并入检查（两道）：① 精确重合；② 邻域 5×5 内存在【别的轨迹】
                    //   已画的河格 ⇒ 本轨迹汇入既有河网，停画（平原平行束的解法）。
                    //   drawnStamp 记 traceId：本轨迹自己的格不算"并入"（允许靠近）。
                    if (drawnStamp[k] != 0 && drawnStamp[k] != traceId) { endType = 3; break; }
                    drawnStamp[k] = traceId;
                    boolean merged = false;
                    for (int dj2 = -2; dj2 <= 2 && !merged; dj2++) {
                        for (int di2 = -2; di2 <= 2; di2++) {
                            int ni2 = ci + di2, nj2 = cj + dj2;
                            if (ni2 < 0 || nj2 < 0 || ni2 >= N || nj2 >= N) continue;
                            int kk = nj2 * N + ni2;
                            if (drawnStamp[kk] != 0 && drawnStamp[kk] != traceId) {
                                merged = true;
                                break;
                            }
                        }
                    }
                    if (merged) { endType = 3; break; }
                    if (h[cj][ci] <= seaLevel) { endType = 0; break; }
                    int lid = lakeId[k];
                    if (lid > 0) {
                        if (!run.isEmpty()) { runs.add(run); run = new java.util.ArrayList<>(); }
                        basinEntries++;
                        if (lakeStamp[lid] == traceId) { endType = 3; break; }   // 湖环
                        lakeStamp[lid] = traceId;
                        int so = spillOut[lid - 1];
                        if (so < 0) { endType = 1; break; }                 // 内流湖：终态蓄水
                        spillJumps++;
                        px = so % N + 0.5;                                  // ★ 从最低溢口接续
                        pz = so / N + 0.5;
                        hasDir = false;                                     // 溢口处重新定向
                        lastCell = so;
                        continue;
                    }
                    run.add(k);
                    lastCell = k;
                }
                double sx = bilin(vx, px, pz, N);
                double sz = bilin(vz, px, pz, N);
                double len = Math.hypot(sx, sz);
                if (len < V_EPS) {
                    // 停滞：有惯性则靠惯性直行（穿平坦区），无惯性则终止
                    if (!hasDir || Math.hypot(dirx, dirz) < 1e-12) { endType = 2; break; }
                } else {
                    double tx = sx / len, tz = sz / len;
                    if (!hasDir) {
                        dirx = tx;
                        dirz = tz;
                        hasDir = true;
                    } else {
                        dirx = INERTIA * dirx + (1 - INERTIA) * tx;
                        dirz = INERTIA * dirz + (1 - INERTIA) * tz;
                        double dl = Math.hypot(dirx, dirz);
                        if (dl < 1e-12) { endType = 2; break; }
                        dirx /= dl;
                        dirz /= dl;
                    }
                }
                double st = stepSize(px, pz, dirx, dirz);
                px += dirx * st;
                pz += dirz * st;
            }
            if (!run.isEmpty()) runs.add(run);
            if (endType == 0) endSea++;
            else if (endType == 1) endLakeTerminal++;
            else if (endType == 2) endEdge++;
            else endLoop++;
        }
        // 溢口标记（河流"起点在溢出口"的显式锚点）
        java.util.List<Integer> spillMarks = new java.util.ArrayList<>();
        for (int l = 0; l < lakeCount; l++) {
            if (spillOut[l] >= 0) spillMarks.add(spillOut[l]);
        }
        // 汇流分位（标定成河阈值用：acc = 上游面积占比）
        float[] accs = flux.clone();
        java.util.Arrays.sort(accs);
        double p50 = accs[accs.length / 2], p90 = accs[(int) (accs.length * 0.90)],
                p99 = accs[(int) (accs.length * 0.99)];
        int win = 2 * radius + 1;
        System.out.printf("⑤ 图层：湖 %d 个 / %d 格 · 成河格 %d（acc≥%.4f）· acc 峰值 %.3f%n",
                lakeCount, lakeCells, riverCells, streamFrac, maxAcc);
        System.out.printf("   汇流分位：p50=%.5f p90=%.5f p99=%.5f%n", p50, p90, p99);
        System.out.printf("⑤e 生命周期追踪（%d ms）：源头 %d 条 → 入湖 %d 次 → 溢口接续 %d 次"
                        + " ⇒ 终点[入海 %d / 内流湖 %d / 窗口边 %d / 并入下游 %d] · 折线段 %d 条%n",
                (System.nanoTime() - tT) / 1_000_000L,
                sources.size(), basinEntries, spillJumps,
                endSea, endLakeTerminal, endEdge, endLoop, runs.size());
        System.out.println("   图例：深蓝=海 · 蓝=湖(洼地蓄水) · 青线=完整生命周期河流"
                + "（源头→汇流→入湖→溢口→下泄→入海）· 白点=湖溢口 · 灰=地形");

        // ===== ⑥ 出图（裁到窗口；山体阴影与游戏同源 Lambert）=====
        File dir = new File("build/hydroref");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        writeAll(dir, "ref_all.png", h, flux, lakeS, seaLevel, N, off, radius, streamFrac,
                runs, spillMarks);
        writeFlow(dir, "ref_flow.png", h, flux, lakeS, seaLevel, N, off, radius, streamFrac);
        writeLakes(dir, "ref_lakes.png", h, lakeS, seaLevel, N, off, radius);
        System.out.printf("⑥ 出图完成：%d ms 总耗时%n", (System.nanoTime() - t0) / 1_000_000L);
        System.out.println("对照：panels_3.png（现有水文）vs ref_all.png（物理参照）");
    }

    /**
     * ★ 蒙特卡洛流线积分（移植 {@code geotransport/source/geotransport/path.cu:__solve_uniform}）。
     *
     * <p>原实现要点逐条对齐：</p>
     * <ol>
     *   <li>粒子起点 = <b>连续均匀随机</b>（{@code curand_uniform * shape}）；</li>
     *   <li>每进入新格：{@code flux[ind] += S·att}（source=1、decay=0 ⇒ S 归一为 1、att 恒 1）；</li>
     *   <li>速度 = 该点<b>双线性采样</b>的速度场（{@code sample_t::gather}）；</li>
     *   <li>步长 = {@code __stepsize}：走到下一格边界的平均距离，上限 √2；</li>
     *   <li>终止：出界 / 速度低于 epsilon / 步数超曼哈顿界（{@code shape[0]+shape[1]}）。</li>
     * </ol>
     * <p>输出 {@code flux} 未做 {@code __normalize} 的除 norm（那是把通量转"过流量"的
     * 量纲归一化）——本参照要用的是<b>上游面积占比</b>（= 有多少比例的粒子经过该格），
     * 与阈值比较即可判"是否成河"，物理含义更直接（DR8 无关、格点计数无关）。</p>
     *
     * @return 每格的汇放占比 ∈ [0,1]（已除采样数）
     */
    private static float[] monteCarlo(float[] vx, float[] vz, int n, int samples, long seed) {
        // ★ 2026-09-25【并行化】：粒子彼此完全独立（原实现是 GPU 上每线程一个粒子）。
        //   每线程：独立 RNG 流（seed*31+线程号）+ 独立计数数组；最后【按线程号升序】
        //   汇总 ⇒ 浮点加法顺序固定 ⇒ 逐位确定（与串行结果同分布、同样可复现）。
        //   实测串行 8M 采样 27 分钟 ⇒ 8 核约 3.5 分钟。
        final int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int chunks = nThreads;
        int[][] tabs = new int[chunks][];
        Thread[] pool = new Thread[chunks];
        final int perChunk = (samples + chunks - 1) / chunks;
        for (int t = 0; t < chunks; t++) {
            final int tt = t;
            final int lo = t * perChunk;
            final int hi = Math.min(samples, lo + perChunk);
            pool[t] = new Thread(() -> {
                int[] cnt = new int[n * n];
                Random rng = new Random(seed * 31L + tt);
                final int maxStep = 2 * n;             // 曼哈顿界（原实现 shape[0]+shape[1]）
                for (int s = lo; s < hi; s++) {
                    double px = rng.nextDouble() * n;
                    double pz = rng.nextDouble() * n;
                    int ind = (int) pz * n + (int) px;
                    double dirx = 0, dirz = 0;         // ★ 动量方向（见 INERTIA 注释）
                    boolean hasDir = false;
                    int step = 0;
                    while (step++ < maxStep) {
                        int nx = (int) px, nz = (int) pz;
                        if (nx < 0 || nz < 0 || nx >= n || nz >= n) break;
                        int nind = nz * n + nx;
                        if (nind != ind) {             // 进入新格 ⇒ 累积
                            ind = nind;
                            cnt[ind]++;
                        }
                        double sx = bilin(vx, px, pz, n);
                        double sz = bilin(vz, px, pz, n);
                        double len = Math.hypot(sx, sz);
                        if (len < V_EPS) {
                            // 停滞：有惯性则靠惯性直行（穿平坦区），无惯性则终止
                            if (!hasDir || Math.hypot(dirx, dirz) < 1e-12) break;
                        } else {
                            double tx = sx / len, tz = sz / len;
                            if (!hasDir) {
                                dirx = tx;
                                dirz = tz;
                                hasDir = true;
                            } else {
                                // ★ 动量滤波（SimpleHydrology 的惯性机制）：
                                //   dir = normalize(INERTIA·dir + (1−INERTIA)·v̂)
                                dirx = INERTIA * dirx + (1 - INERTIA) * tx;
                                dirz = INERTIA * dirz + (1 - INERTIA) * tz;
                                double dl = Math.hypot(dirx, dirz);
                                if (dl < 1e-12) break;
                                dirx /= dl;
                                dirz /= dl;
                            }
                        }
                        double st = stepSize(px, pz, dirx, dirz);
                        px += dirx * st;
                        pz += dirz * st;
                    }
                }
                tabs[tt] = cnt;
            }, "gt-mc-" + t);
            pool[t].start();
        }
        for (Thread th : pool) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        float[] acc = new float[n * n];
        long[] sum = new long[n * n];
        for (int t = 0; t < chunks; t++) {                 // 固定顺序汇总（确定性）
            int[] c = tabs[t];
            if (c == null) continue;
            for (int k = 0; k < n * n; k++) sum[k] += c[k];
        }
        float inv = 1f / samples;
        for (int k = 0; k < n * n; k++) acc[k] = sum[k] * inv;
        return acc;
    }

    /**
     * 网格穿越步长（逐字移植 {@code __stepsize}）：到 x/y 方向下一格边界的距离，
     * 各方向取"最近穿出距离"，上限 √2，最后取平均。
     */
    private static double stepSize(double px, double pz, double dx, double dz) {
        final double tmax = Math.sqrt(2.0);
        double xNeg = Math.floor(px), yNeg = Math.floor(pz);
        double xPos = 1.0 + xNeg, yPos = 1.0 + yNeg;
        double tx = clampStep(xNeg, xPos, px, dx, tmax);
        double tz = clampStep(yNeg, yPos, pz, dz, tmax);
        return 0.5 * (tx + tz);
    }

    private static double clampStep(double neg, double pos, double p, double d, double tmax) {
        if (Math.abs(d) < 1e-12) return tmax;              // 该轴不移动 ⇒ 不贡献步长
        double t1 = (neg - p) / d;
        double t2 = (pos - p) / d;
        return Math.min(Math.max(t1, t2), tmax);
    }

    /** 双线性采样（格心在整数 + 0.5 处，与粒子坐标系一致；原实现 sample_t::gather）。 */
    private static double bilin(float[] f, double x, double z, int n) {
        double fx = x - 0.5, fz = z - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fz);
        i = Math.max(0, Math.min(n - 2, i));
        j = Math.max(0, Math.min(n - 2, j));
        double tx = Math.max(0, Math.min(1, fx - i));
        double tz = Math.max(0, Math.min(1, fz - j));
        double a = f[j * n + i], b = f[j * n + i + 1];
        double c = f[(j + 1) * n + i], d = f[(j + 1) * n + i + 1];
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    // ===== priority-flood + ε（Barnes 2014 变体；速度场与湖层共用）=====
    private static final double FILL_EPS = 1e-5;

    private static double[][] priorityFlood(double[][] h, double seaLevel, int n) {
        double[][] fill = new double[n][n];
        boolean[] closed = new boolean[n * n];
        java.util.PriorityQueue<double[]> pq =
                new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(a -> a[0]));
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (h[j][i] <= seaLevel || i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    fill[j][i] = h[j][i];
                    closed[j * n + i] = true;
                    pq.add(new double[]{h[j][i], j * n + i});
                }
            }
        }
        final int[] dxx = {1, -1, 0, 0};
        final int[] dzz = {0, 0, 1, -1};
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int idx = (int) cur[1];
            int ci = idx % n, cj = idx / n;
            for (int d = 0; d < 4; d++) {
                int ni = ci + dxx[d], nj = cj + dzz[d];
                if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                int nIdx = nj * n + ni;
                if (closed[nIdx]) continue;
                closed[nIdx] = true;
                fill[nj][ni] = Math.max(h[nj][ni], cur[0] + FILL_EPS);
                pq.add(new double[]{fill[nj][ni], nIdx});
            }
        }
        return fill;
    }

    // ===== 出图（NW 光 Lambert 山体阴影，与生产探针 WaterViewProbe.shadeGray 同口径）=====

    /**
     * ★ 山体阴影 = 生产同源口径（用户判据："你地形灰度图怎么和游戏里面的不一样？"）：
     * 标准 Lambert 光照（法线归一化）+ NW 光源 (-0.5, 0.7, 0.51)，输出 60 + 175·max(0,d)；
     * 梯度在【1 块分辨率】上求（±1 块中心差分）。
     * ⚠ 必然差异（非 bug）：本图底色 = 侵蚀后、雕刻前地形（水文的输入）；游戏底色 =
     * 侵蚀 + 水文雕刻后。参照必须用雕刻前地形，否则循环论证。
     */
    private static int shade(double[][] h, int i, int j) {
        final double lx = -0.5, ly = 0.7, lz = 0.51;
        double dhx = (h[j][i + 1] - h[j][i - 1]) / 2.0;
        double dhz = (h[j + 1][i] - h[j - 1][i]) / 2.0;
        double nx = -dhx, ny = 1.0, nz = -dhz;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double d = (nx * lx + ny * ly + nz * lz) / Math.max(1e-9, len);
        return (int) Math.max(0, Math.min(255, 60 + 175 * Math.max(0, d)));
    }

    private static void save(BufferedImage img, File dir, String name) throws Exception {
        ImageIO.write(img, "png", new File(dir, name));
        System.out.println("  写出 " + new File(dir, name).getAbsolutePath());
    }

    private static BufferedImage newImg(int radius) {
        int w = 2 * radius + 1;
        return new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * 主对照图：地形 + 海 + 湖 + 【完整生命周期河流折线】+ 溢口标记。
     *
     * <p>河流不再是"格子染色"（那会让支流在流量达标处凭空开始），而是 ⑤e 追踪出的
     * 完整折线段：粗 2px 青 = 成河段（acc ≥ 阈值）；细 1px 暗青 = 源头细流段。
     * 白色 2×2 点 = 湖溢口（河流从湖接出的锚点）。</p>
     */
    private static void writeAll(File dir, String name, double[][] h, float[] flux,
                                 boolean[][] lakeS, double seaLevel,
                                 int n, int off, int radius, double streamFrac,
                                 java.util.List<java.util.List<Integer>> runs,
                                 java.util.List<Integer> spillMarks)
            throws Exception {
        BufferedImage img = newImg(radius);
        for (int j = off; j <= n - 1 - off; j++) {
            for (int i = off; i <= n - 1 - off; i++) {
                int rgb;
                if (h[j][i] <= seaLevel) {
                    rgb = 0x1F4E79;
                } else if (lakeS[j][i]) {
                    rgb = 0x4A90E2;
                } else {
                    int g = shade(h, i, j);
                    rgb = (g << 16) | (g << 8) | g;
                }
                img.setRGB(i - off, j - off, rgb);
            }
        }
        // —— 完整生命周期折线 ——
        for (java.util.List<Integer> run : runs) {
            for (int s = 0; s + 1 < run.size(); s++) {
                int a = run.get(s), b = run.get(s + 1);
                int ax = a % n, az = a / n, bx = b % n, bz = b / n;
                boolean bigA = flux[a] >= streamFrac, bigB = flux[b] >= streamFrac;
                int col = (bigA || bigB) ? 0x00E5D0 : 0x2E8B8B;      // 成河=亮青 / 源头细流=暗青
                drawLine(img, ax - off, az - off, bx - off, bz - off, col,
                        (bigA || bigB) ? 2 : 1, off, radius);
            }
        }
        // —— 湖溢口标记（白点：河流"起点在溢出口"的锚点）——
        for (int sp : spillMarks) {
            int px = sp % n - off, pz = sp / n - off;
            if (px < 0 || pz < 0 || px >= img.getWidth() || pz >= img.getHeight()) continue;
            for (int dj = 0; dj < 2; dj++) {
                for (int di = 0; di < 2; di++) {
                    int x = px + di, z = pz + dj;
                    if (x < img.getWidth() && z < img.getHeight()) img.setRGB(x, z, 0xFFFFFF);
                }
            }
        }
        save(img, dir, name);
    }

    /** 简易线段光栅（Bresenham；宽 1=单像素，2=加右邻像素）。 */
    private static void drawLine(BufferedImage img, int x0, int z0, int x1, int z1,
                                 int col, int width, int off, int radius) {
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0, z = z0;
        int w = img.getWidth(), ht = img.getHeight();
        while (true) {
            if (x >= 0 && z >= 0 && x < w && z < ht) {
                img.setRGB(x, z, col);
                if (width >= 2 && x + 1 < w) img.setRGB(x + 1, z, col);
            }
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }
    }

    /** 汇流量热力（对数色标；与"哪里该有水"的最直接对照）。 */
    private static void writeFlow(File dir, String name, double[][] h, float[] flux,
                                  boolean[][] lakeS, double seaLevel,
                                  int n, int off, int radius, double streamFrac)
            throws Exception {
        BufferedImage img = newImg(radius);
        double logMax = Math.log(1 + Math.max(streamFrac * 10, 1e-6));
        for (int j = off; j <= n - 1 - off; j++) {
            for (int i = off; i <= n - 1 - off; i++) {
                int rgb;
                if (h[j][i] <= seaLevel) {
                    rgb = 0x1F4E79;
                } else if (lakeS[j][i]) {
                    rgb = 0x4A90E2;
                } else {
                    double a = flux[j * n + i];
                    if (a <= 0) {
                        int g = shade(h, i, j);
                        rgb = (g << 16) | (g << 8) | g;
                    } else {
                        // 黄 → 红 热力（对数标定，参考 geotransport 示例的 LogNorm）
                        double t = Math.min(1.0, Math.log(1 + a) / logMax);
                        int r = (int) (180 + 75 * t);
                        int g = (int) (200 - 60 * t);
                        int b = (int) (60 - 40 * t);
                        rgb = (r << 16) | (g << 8) | Math.max(0, b);
                    }
                }
                img.setRGB(i - off, j - off, rgb);
            }
        }
        save(img, dir, name);
    }

    private static void writeLakes(File dir, String name, double[][] h, boolean[][] lakeS,
                                   double seaLevel, int n, int off, int radius)
            throws Exception {
        BufferedImage img = newImg(radius);
        for (int j = off; j <= n - 1 - off; j++) {
            for (int i = off; i <= n - 1 - off; i++) {
                int rgb;
                if (h[j][i] <= seaLevel) {
                    rgb = 0x1F4E79;
                } else if (lakeS[j][i]) {
                    rgb = 0x4A90E2;
                } else {
                    int g = shade(h, i, j);
                    rgb = (g << 16) | (g << 8) | g;
                }
                img.setRGB(i - off, j - off, rgb);
            }
        }
        save(img, dir, name);
    }
}
