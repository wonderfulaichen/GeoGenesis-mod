package com.geogenesis.worldgen.geode;

import com.geogenesis.worldgen.terrain.RockType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 紫晶洞诊断探针（配套 {@link GeodeShape}）。
 *
 * <h3>为何用"合成地表 + 合成岩性"而非真地形</h3>
 * <p>与 {@code OreVeinProbe} 同理：本探针要验证的是 {@link GeodeShape} 自身的
 * <b>形状/门控逻辑</b>，不掺入地形/地层噪声。故用<b>平滑合成地表</b>
 * （正弦叠加，比逐列模运算更接近真实地形，避免深度带统计被截断偏斜）
 * 与<b>分区合成岩性</b>，逐体素调用 {@code geodeAt} 统计几何量。
 * 真地形下的表现由端到端 chunk 探针与实机确认负责。</p>
 *
 * <h3>★ 关键统计口径：晶洞个数 = 出现体素的不同【候选格】数</h3>
 * <p>{@link GeodeShape} 的论证保证"<b>整个椭球必然落在它自己那一格内</b>"
 * ⇒ 统计"有体素命中的候选格数"就等于<b>晶洞个数</b>，无需 3D 连通分量分析
 * （那贵且易错）。这同时也是对那条论证的一次<b>间接验证</b>：若论证不成立，
 * 会出现"相邻格体素属于同一个洞" ⇒ 尺寸分布异常偏大，
 * 且判据4（壳层同心序）会在洞边缘失败。</p>
 *
 * <h3>★ 判据4 的独立性</h3>
 * <p>壳层序<b>不</b>用 {@code GeodeShape} 内部的 {@code shellOf} 判定，
 * 而是用 {@link GeodeShape#dbgCellShape} 拿到的中心/半轴<b>重新算</b>
 * 每个体素的归一化半径，再看各材料层的平均半径是否单调 ——
 * 这样若 {@code shellOf} 的分档写反了，判据4 会真的失败
 * （若直接信内部实现，判据就成了恒真）。</p>
 *
 * <pre>{@code gradlew runGeodeProbe [-PprobeArgs="seed spanXY [scan]"]}</pre>
 */
public final class GeodeProbe {

    private GeodeProbe() { }

    /** 与 {@code GeoGenesisGenerator} 一致。 */
    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 320;

    /** 岩性分区边长。刻意<b>不是</b> {@code CELL}=32 的整数倍（80/32=2.5）⇒ 候选格必跨界。 */
    private static final int ROCK_PART = 80;

    /** 缺失层直方图用的短名（按材料下标：0=最外 … 4=最内）。 */
    private static final String[] MISSING_NAMES = {"外壳", "方解石", "紫水晶", "晶芽", "空腔"};

    /**
     * 合成地表：平滑起伏。
     *
     * <p>⚠ 初版若用 {@code 80 + (x*31+z*17)%181} 这类逐列模运算，地表会<b>逐列跳变</b>，
     * 深度带被频繁截断 ⇒ 统计偏斜。用低频正弦叠加更像真实地形。</p>
     */
    private static int synthSurface(int wx, int wz) {
        return 100 + (int) Math.round(
                40.0 * Math.sin(wx / 220.0) + 30.0 * Math.sin(wz / 170.0));
    }

    /** 合成岩性：按大地块轮转，保证 8 种岩性都出现。 */
    private static int synthRock(int wx, int wz) {
        int rocks = RockType.values().length;
        return Math.floorMod(Math.floorDiv(wx, ROCK_PART) * 5
                + Math.floorDiv(wz, ROCK_PART) * 3, rocks);
    }

    /** best-of-N 取最小（与 {@code OrePerfProbe} 同一方法论：抖动是单侧的）。 */
    private static final int REPEAT = 3;

    private static long bestOf(int repeat, Runnable body) {
        body.run();
        long best = Long.MAX_VALUE;
        for (int r = 0; r < repeat; r++) {
            long t0 = System.nanoTime();
            body.run();
            long el = System.nanoTime() - t0;
            if (el < best) best = el;
        }
        return best;
    }

    // ===================== 统计载体 =====================

    private static final class Result {
        final long[] matCount = new long[GeodeShape.MATERIALS];
        final Map<Long, long[]> perCell = new HashMap<>();       // key → [总块, 各材料块...]
        final Map<Long, double[]> perCellRSum = new HashMap<>(); // key → 各材料 r 之和
        final Map<Long, double[]> perCellRN = new HashMap<>();   // key → 各材料块数
        final List<int[]> cells = new ArrayList<>();
        long calls, hostHits, nonHostHits;
        int depthMin = Integer.MAX_VALUE, depthMax = Integer.MIN_VALUE;

        void clear() {
            java.util.Arrays.fill(matCount, 0);
            perCell.clear();
            perCellRSum.clear();
            perCellRN.clear();
            cells.clear();
            calls = hostHits = nonHostHits = 0;
            depthMin = Integer.MAX_VALUE;
            depthMax = Integer.MIN_VALUE;
        }
    }

    /**
     * 单趟扫描（可重复调用，结果确定）。
     *
     * <p>同时统计体积、晶洞数、岩性耦合、深度带与壳层半径 —— 一次遍历取齐，
     * 避免多趟重复计算（大窗口下多趟会明显拖慢探针）。</p>
     */
    private static void pass(long seed, int nd, Result res) {
        res.clear();
        for (int wx = 0; wx < nd; wx++) {
            for (int wz = 0; wz < nd; wz++) {
                int rockOrd = synthRock(wx, wz);
                int surf = synthSurface(wx, wz);
                boolean host = GeodeShape.isHostRock(rockOrd);
                int yBot = Math.max(WORLD_MIN_Y + 1, surf - GeodeShape.MAX_DEPTH);
                int yTop = Math.min(WORLD_MAX_Y - 1, surf - GeodeShape.MIN_DEPTH);
                for (int y = yBot; y <= yTop; y++) {
                    res.calls++;
                    int m = GeodeShape.geodeAt(wx, y, wz, surf, rockOrd, WORLD_MIN_Y);
                    if (m < 0) continue;
                    res.matCount[m]++;
                    if (host) res.hostHits++; else res.nonHostHits++;
                    int d = surf - y;
                    if (d < res.depthMin) res.depthMin = d;
                    if (d > res.depthMax) res.depthMax = d;

                    int cx = Math.floorDiv(wx, GeodeShape.CELL);
                    int cy = Math.floorDiv(y, GeodeShape.CELL);
                    int cz = Math.floorDiv(wz, GeodeShape.CELL);
                    long key = cellKey(cx, cy, cz);
                    long[] g = res.perCell.get(key);
                    if (g == null) {
                        g = new long[1 + GeodeShape.MATERIALS];
                        res.perCell.put(key, g);
                        res.cells.add(new int[]{cx, cy, cz});
                    }
                    g[0]++;
                    g[1 + m]++;

                    double[] shape = GeodeShape.dbgCellShape(cx, cy, cz);
                    if (shape != null) {
                        double rr = Math.sqrt(sq((wx + 0.5 - shape[0]) / shape[3])
                                + sq((y + 0.5 - shape[1]) / shape[4])
                                + sq((wz + 0.5 - shape[2]) / shape[5]));
                        double[] sr = res.perCellRSum.computeIfAbsent(key,
                                k -> new double[GeodeShape.MATERIALS]);
                        double[] sn = res.perCellRN.computeIfAbsent(key,
                                k -> new double[GeodeShape.MATERIALS]);
                        sr[m] += rr;
                        sn[m] += 1;
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        // ★ 默认窗口 512（不是 256）：晶洞极稀（约 1/44 chunk），256 窗口只含 256 个
        //   chunk 的列量 ⇒ 期望洞数仅 ~6，Poisson 噪声可达 ±40% ⇒ 判据6（密度）
        //   会随机 FAIL（实测：默认 256 时判据6 抖动，512 时稳定 PASS）。
        //   ⚠ 这是本项目反复踩的"窗口小于特征尺度"坑的又一实例（矿脉成矿带 190 块时
        //     N=96 也测到"带内列 0%"）。若要更稳，用更大窗口或配合 scan 模式折算。
        int nd = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        boolean scan = args.length > 2 && args[2].equalsIgnoreCase("scan");

        String[] names = {"平滑玄武岩", "方解石", "紫水晶块", "晶芽", "空腔"};

        if (scan) {
            scanDensity(seed, nd);
            return;
        }

        System.out.printf("=== GeodeProbe seed=%d span=%d×%d ===%n", seed, nd, nd);
        GeodeShape.setSeed(seed);
        System.out.printf("[0] 播种: %s%n", GeodeShape.isSeeded() ? "OK" : "FAIL");

        Result res = new Result();
        long best = bestOf(REPEAT, () -> pass(seed, nd, res));

        // ★ 密度判据专用：把 presence 放大 DENSITY_AMP 倍后再 /DENSITY_AMP 折算。
        //   理由：晶洞极稀（~1/44 chunk），直接数窗口内的洞 Poisson 噪声极大
        //   （512 窗口期望 ~23 个 ⇒ ±21%；256 窗口期望 ~6 个 ⇒ ±40%）⇒
        //   判据6 会随机 FAIL（实测踩到过）。逐格独立 ⇒ 放大与洞数严格线性，
        //   折算合法，且把样本量放大 DENSITY_AMP 倍 ⇒ 噪声降 √DENSITY_AMP。
        final double densityAmp = 8.0;
        GeodeShape.dbgSetPresence(densityAmp);
        Result resAmp = new Result();
        bestOf(1, () -> pass(seed, nd, resAmp));
        GeodeShape.dbgReset();
        double perChunkDensity = (resAmp.perCell.size() / ((double) nd * nd / 256.0)) / densityAmp;

        double chunkEq = (double) nd * nd / 256.0;              // 窗口列数 → 等价 chunk 数
        int geodes = res.perCell.size();
        long totalMat = 0;
        for (long v : res.matCount) totalMat += v;
        double perChunk = geodes / chunkEq;

        // ---------- [1] 体积 ----------
        System.out.printf("%n[1] 体积（窗口 %d×%d 列；体素调用 %d）%n", nd, nd, res.calls);
        for (int i = 0; i < GeodeShape.MATERIALS; i++) {
            System.out.printf("    %-10s %10d 块  (%6.2f%% of 晶洞总量)%n",
                    names[i], res.matCount[i],
                    totalMat == 0 ? 0.0 : 100.0 * res.matCount[i] / totalMat);
        }
        System.out.printf("    合计 %d 块 = 载体体积的 %.5f%%%n",
                totalMat, res.calls == 0 ? 0.0 : 100.0 * totalMat / res.calls);

        // ---------- [2] 晶洞数 ----------
        System.out.printf("%n[2] 晶洞数（不同候选格 = 不同晶洞）: %d 个%n", geodes);
        System.out.printf("    生产参数直测: %.4f 个/chunk（= 1 / %.1f chunk；样本少、噪声大）%n",
                perChunk, geodes == 0 ? 0.0 : chunkEq / geodes);
        System.out.printf("    ★ 密度标定（presenceMul=%.0f 放大后 /%.0f，低噪声）: %.4f 个/chunk"
                        + "（= 1 / %.1f chunk）%n",
                densityAmp, densityAmp, perChunkDensity,
                perChunkDensity <= 0 ? 0.0 : 1.0 / perChunkDensity);

        // ---------- [3] 岩性耦合 ----------
        System.out.printf("%n[3] 岩性耦合: 宿主岩命中=%d  非宿主岩命中=%d%n",
                res.hostHits, res.nonHostHits);

        // ---------- [4] 深度带 ----------
        System.out.printf("[4] 深度带: 实测 %s   设计 [%d, %d]%n",
                geodes == 0 ? "(无洞)" : res.depthMin + "~" + res.depthMax,
                GeodeShape.MIN_DEPTH, GeodeShape.MAX_DEPTH);

        // ---------- [5] 壳层同心序（独立复核）----------
        //   ★ 2026-09-16 改口径：对齐原版后，【晶芽不再是独立壳层】——
        //     原版把紫水晶层内 8.3% 的方块换成晶芽（use_alternate_layer0_chance），
        //     故晶芽与紫水晶【同层、相互交错】，二者之间不存在序关系。
        //     故改用"结构序对"：空腔 < {紫水晶, 晶芽} < 方解石 < 平滑玄武岩。
        int[][] orderPairs = {
                {GeodeShape.MAT_AIR, GeodeShape.MAT_AMETHYST},
                {GeodeShape.MAT_AIR, GeodeShape.MAT_BUDDING},
                {GeodeShape.MAT_AMETHYST, GeodeShape.MAT_CALCITE},
                {GeodeShape.MAT_BUDDING, GeodeShape.MAT_CALCITE},
                {GeodeShape.MAT_CALCITE, GeodeShape.MAT_BASALT},
        };
        Map<String, Integer> missingHist = new HashMap<>();
        int complete = 0, truncated = 0, orderBad = 0, tiny = 0;
        double minGap = Double.MAX_VALUE;
        for (Map.Entry<Long, long[]> e : res.perCell.entrySet()) {
            long[] g = e.getValue();
            if (g[0] < 40) { tiny++; continue; }
            double[] sr = res.perCellRSum.get(e.getKey());
            double[] sn = res.perCellRN.get(e.getKey());
            if (sr == null) { orderBad++; continue; }
            double[] meanR = new double[GeodeShape.MATERIALS];
            boolean[] present = new boolean[GeodeShape.MATERIALS];
            int nPresent = 0;
            for (int i = 0; i < GeodeShape.MATERIALS; i++) {
                if (sn[i] > 0) {
                    meanR[i] = sr[i] / sn[i];
                    present[i] = true;
                    nPresent++;
                } else {
                    meanR[i] = Double.NaN;
                }
            }

            // ① 序检查：按"结构序对"逐对比较，且【只对两边都出现过的】才比较。
            //    ★ 两个教训都保留：
            //      · 缺失层不能算失序 —— 晶洞被岩性/深度带"切平"是正确产状
            //        （杏仁体局限于单一熔岩流），初版把缺失当失序 ⇒ 误报 4 个；
            //      · 晶芽与紫水晶【同层交错】⇒ 二者不可比较（原版就是这样）。
            boolean ok = true;
            for (int[] pr : orderPairs) {
                if (!present[pr[0]] || !present[pr[1]]) continue;
                if (meanR[pr[0]] >= meanR[pr[1]]) { ok = false; break; }
            }
            if (!ok) { orderBad++; continue; }

            // ② 缺失层签名直方图（不再硬判"前缀"）。
            //    ★ 为何不硬判：门控把椭球切掉一块 ⇒ 哪些层会缺取决于切割几何。
            //      我最初以为"必缺最外层"，推演后发现<b>恰恰相反</b> ——
            //      深切割（切面越过中心）只留下远处的外层环，<b>先丢的是内层</b>
            //      （空腔最小，最先消失）。故"缺空腔而留外壳"是合理的，
            //      不该报异常。用直方图如实呈现，再由人工判读是否反常。
            StringBuilder sig = new StringBuilder();
            for (int i = GeodeShape.MATERIALS - 1; i >= 0; i--) {      // 内→外
                if (!present[i]) sig.append(MISSING_NAMES[i]);
            }
            String keySig = nPresent == GeodeShape.MATERIALS ? "完整（五层齐全）"
                    : "缺" + sig;
            missingHist.merge(keySig, 1, Integer::sum);

            if (nPresent == GeodeShape.MATERIALS) {
                complete++;
                double gap = meanR[GeodeShape.MAT_BASALT] - meanR[GeodeShape.MAT_CALCITE];
                if (gap < minGap) minGap = gap;
            } else {
                truncated++;
            }
        }
        System.out.printf("%n[5] 壳层结构（用 dbgCellShape 独立重算归一化半径复核）:%n");
        System.out.printf("    五层齐全洞=%d   有缺层洞=%d   失序=%d   体素<40 跳过=%d%n",
                complete, truncated, orderBad, tiny);
        System.out.printf("    期望结构序: 空腔 < {紫水晶块, 晶芽} < 方解石 < 平滑玄武岩%n");
        System.out.println("      （晶芽与紫水晶【同层交错】—— 原版把紫水晶层内 8.3% 换成晶芽，"
                + "二者无先后）");
        System.out.printf("    完整洞的最小\"外壳 - 方解石\"平均半径间隔: %s%n",
                minGap == Double.MAX_VALUE ? "(无有效样本)" : String.format("%.4f", minGap));
        List<String> sigKeys = new ArrayList<>(missingHist.keySet());
        sigKeys.sort((a, b) -> missingHist.get(b) - missingHist.get(a));
        System.out.println("    缺失层直方图（两种成因：①门控切平椭球 ②小洞装不下稀有层）:");
        for (String k : sigKeys) {
            System.out.printf("        %-24s %d 个%n", k, missingHist.get(k));
        }

        // ---------- [6] 尺寸分布 ----------
        long[] b = new long[6];
        for (long[] g : res.perCell.values()) {
            long v = g[0];
            int i = v < 20 ? 0 : v < 60 ? 1 : v < 150 ? 2 : v < 400 ? 3 : v < 1200 ? 4 : 5;
            b[i]++;
        }
        System.out.printf("%n[6] 尺寸分布（每洞体素数）: <20=%d  20~59=%d  60~149=%d"
                        + "  150~399=%d  400~1199=%d  >=1200=%d%n",
                b[0], b[1], b[2], b[3], b[4], b[5]);

        // ---------- [7] 性能 ----------
        double perChunkMs = best / 1e6 / chunkEq;
        System.out.printf("%n[7] 性能（best-of-%d 取最小）: best=%.1fms  单块=%.1f ns"
                        + "  ★ 折算每 chunk: %.3f ms/chunk%n",
                REPEAT, best / 1e6, best / (double) Math.max(1, res.calls), perChunkMs);
        System.out.println("    对照：洞穴 2.5~3.1、矿脉 0.13~0.19 ms/chunk");

        // ---------- [8] 剖面图 ----------
        if (!res.cells.isEmpty()) {
            int[] pick = null;
            long most = -1;
            for (int[] c : res.cells) {
                long v = res.perCell.get(cellKey(c[0], c[1], c[2]))[0];
                if (v > most) { most = v; pick = c; }
            }
            if (pick != null) {
                double[] shape = GeodeShape.dbgCellShape(pick[0], pick[1], pick[2]);
                if (shape != null) {
                    int midY = (int) Math.round(shape[1]);
                    int mx = (int) Math.round(shape[0]);
                    int mz = (int) Math.round(shape[2]);
                    System.out.printf("%n[8] 最大晶洞水平剖面（中心 y=%d, x=%d, z=%d；"
                                    + "B=外壳 C=方解石 A=紫水晶 a=晶芽 .=空腔 空白=岩体）%n",
                            midY, mx, mz);
                    int half = (int) Math.ceil(GeodeShape.MAX_SEMI_AXIS) + 1;
                    for (int dz = -half; dz <= half; dz++) {
                        StringBuilder sb = new StringBuilder("    ");
                        for (int dx = -half; dx <= half; dx++) {
                            int wx = mx + dx, wz = mz + dz;
                            int m = GeodeShape.geodeAt(wx, midY, wz, synthSurface(wx, wz),
                                    synthRock(wx, wz), WORLD_MIN_Y);
                            sb.append(switch (m) {
                                case GeodeShape.MAT_BASALT -> 'B';
                                case GeodeShape.MAT_CALCITE -> 'C';
                                case GeodeShape.MAT_AMETHYST -> 'A';
                                case GeodeShape.MAT_BUDDING -> 'a';
                                case GeodeShape.MAT_AIR -> '.';
                                default -> ' ';
                            });
                        }
                        System.out.println(sb);
                    }
                }
            }
        }

        // ---------- 判据 ----------
        System.out.println();
        boolean p1 = geodes > 0;
        System.out.printf("[判据1] 确实成洞（非恒 NONE）: %s（%d 个）%n",
                p1 ? "PASS" : "FAIL", geodes);
        boolean p2 = res.hostHits > 0 && res.nonHostHits == 0;
        System.out.printf("[判据2] 岩性门控生效（宿主岩命中>0 且 非宿主岩=0）: %s（宿主 %d / 非宿主 %d）%n",
                p2 ? "PASS" : "FAIL", res.hostHits, res.nonHostHits);
        boolean p3 = geodes > 0 && res.depthMin >= GeodeShape.MIN_DEPTH
                && res.depthMax <= GeodeShape.MAX_DEPTH;
        System.out.printf("[判据3] 深度带硬约束生效（实测区间 ⊆ 设计区间）: %s%n",
                p3 ? "PASS" : "FAIL");
        boolean p4 = orderBad == 0 && complete > 0;
        System.out.printf("[判据4] 壳层同心序正确 + 存在五层齐全的洞: %s"
                + "（完整 %d / 切平 %d / 失序 %d）%n",
                p4 ? "PASS" : "FAIL", complete, truncated, orderBad);
        boolean p5 = true;
        for (int i = 0; i < GeodeShape.MATERIALS; i++) p5 &= res.matCount[i] > 0;
        System.out.printf("[判据5] 五层材料全部出现（无死层）: %s%n", p5 ? "PASS" : "FAIL");
        // ★ 2026-09-16 改为对齐原版：原版每 chunk 有 1/24 概率尝试生成一个晶洞。
        //   容差 ±1.6×（本探针用合成岩性、岩石构成与实机不同 ⇒ 允许偏离）。
        boolean p6 = perChunkDensity >= 1.0 / 38.0 && perChunkDensity <= 1.0 / 15.0;
        System.out.printf("[判据6] 密度对齐原版（原版 1/24 chunk，容差 1/38~1/15）: %s"
                        + "（标定值 1/%.1f chunk）%n",
                p6 ? "PASS" : "FAIL",
                perChunkDensity <= 0 ? 0.0 : 1.0 / perChunkDensity);
        boolean p7 = perChunkMs <= 1.0;
        System.out.printf("[判据7] 性能 ≤ 1.0 ms/chunk（对照洞穴 2.5~3.1）: %s（实测 %.3f）%n",
                p7 ? "PASS" : "FAIL", perChunkMs);

        // ---------- 判据8（2026-09-16 新增，来自"去查原版数据"）----------
        //   ★ 这条判据的真正价值：**它当初就能抓住"薄壳 + 一大团水晶"的错误形态**。
        //     只判"是否同心 / 是否五层齐全"永远看不出来 —— 两种形态都同心、都五层。
        //   原版（layers 1.7/2.2/3.2/4.2）折算体积占比：
        //     外壳≈75% · 方解石≈21% · 紫水晶≈3.8%（其中晶芽 8.3%≈0.3%）· 空腔≈0.34%
        //   容差给得较宽，因为门控切平会剥掉一些外层（使外壳占比偏低）。
        double tm = Math.max(1, totalMat);
        double basaltPct = 100.0 * res.matCount[GeodeShape.MAT_BASALT] / tm;
        double calcitePct = 100.0 * res.matCount[GeodeShape.MAT_CALCITE] / tm;
        double amethystPct = 100.0 * res.matCount[GeodeShape.MAT_AMETHYST] / tm;
        double buddingPct = 100.0 * res.matCount[GeodeShape.MAT_BUDDING] / tm;
        double airPct = 100.0 * res.matCount[GeodeShape.MAT_AIR] / tm;
        boolean p8 = basaltPct >= 60.0 && basaltPct <= 88.0
                && calcitePct >= 10.0 && calcitePct <= 32.0
                && amethystPct >= 0.5 && amethystPct <= 12.0
                && airPct <= 4.0 && buddingPct <= 4.0;
        System.out.printf("[判据8] 方块占比符合原版量级（外壳 60~88 / 方解石 10~32 / "
                        + "紫水晶 0.5~12 / 空腔<=4 / 晶芽<=4，单位%%）: %s%n",
                p8 ? "PASS" : "FAIL");
        System.out.printf("        实测 外壳%.1f 方解石%.1f 紫水晶%.1f 晶芽%.1f 空腔%.1f"
                        + "   （初版凭观感定档时为 36.4/32.2/18.9/8.2/4.3 ⇒ 本条会 FAIL）%n",
                basaltPct, calcitePct, amethystPct, buddingPct, airPct);

        System.out.println(p1 && p2 && p3 && p4 && p5 && p6 && p7 && p8
                ? "ALL PASS" : "FAILURES");
    }

    // ===================== 密度扫描 =====================

    private static void scanDensity(long seed, int nd) {
        System.out.printf("=== 密度扫描（窗口 %d×%d）presenceMul -> 每 chunk 晶洞数 ===%n", nd, nd);
        double chunkEq = (double) nd * nd / 256.0;
        // ★ 用"放大后再线性折算"来标定：生产密度下大窗口也只有几个洞，
        //   Poisson 噪声可达 ±50%；放大 k 倍后洞数 ~k 倍，再 /k 得到低噪声估计。
        //   （逐格独立 ⇒ 放大 presence 与洞数严格线性，折算合法。）
        double[] muls = {2.0, 4.0, 8.0, 16.0};
        for (double mul : muls) {
            GeodeShape.setSeed(seed);
            GeodeShape.dbgSetPresence(mul);
            Result r = new Result();
            bestOf(1, () -> pass(seed, nd, r));
            int g = r.perCell.size();
            long blocks = 0;
            for (long v : r.matCount) blocks += v;
            // 折算到生产（mul=1）：逐格独立 ⇒ 洞数与 presence 成正比
            double perChunkProd = (g / chunkEq) / mul;
            System.out.printf("    presenceMul=%-5.2f  晶洞=%-5d  方块=%-6d"
                            + "  → 生产密度 %.4f/chunk（1/%.1f chunk）%n",
                    mul, g, blocks, perChunkProd,
                    perChunkProd <= 0 ? 0.0 : 1.0 / perChunkProd);
        }
        GeodeShape.dbgReset();
        System.out.println("    读数：2/4/8 三档的\"生产密度\"应基本一致（线性）—— 一致即折算可信；");
        System.out.println("    16 档偏低是 presence 饱和（BASE×16>1）所致，属预期，不参与标定。");
    }

    // ===================== 工具 =====================

    private static long cellKey(int cx, int cy, int cz) {
        return (((long) cx & 0x1FFFFF) << 42)
                | (((long) cy & 0x1FFFFF) << 21)
                | ((long) cz & 0x1FFFFF);
    }

    private static double sq(double v) {
        return v * v;
    }
}
