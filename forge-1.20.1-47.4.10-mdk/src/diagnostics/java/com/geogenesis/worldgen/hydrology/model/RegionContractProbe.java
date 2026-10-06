package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.HashMap;
import java.util.Map;

/**
 * <h1>region-contract 契约：同一世界点，跨窗口中心必须一致</h1>
 *
 * <h2>为什么这是接线（方案 P3）前的硬闸门</h2>
 * <p>游戏按 chunk/区域求解，同一世界点会被<b>不同窗口</b>（不同中心）重复计算。
 * 若结果依赖"哪个窗口算的"，落块就依赖生成顺序 ⇒ 违反纯函数铁律。
 * 方案 §5.5 原文：「同seed/配置/世界点跨中心、跨半径、跨区、冷热缓存、
 * 查询顺序、并发与取消均一致」。</p>
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>窗口 A 连跑两次 ⇒ 判据 ① 确定性（逐位相同）；</li>
 *   <li>窗口 B = A 平移 (96,96) 块（step=4 的整倍 ⇒ 网格点全部对齐到 4 块格点）；</li>
 *   <li>取两窗共同覆盖且【双方都距窗缘 ≥ halo】的格点，逐点比较。</li>
 * </ol>
 *
 * <h2>各量的契约推理（判据为何这样定）</h2>
 * <ul>
 *   <li><b>level</b>：resolved 的定义 = "计入窗外出口后水位不变" ⇒
 *       双方都 resolved 的点，其 minimax 路径必完全在各自窗口内
 *       ⇒ 两边都等于【无限制的真实值】⇒ <b>必须 ≤1e-9 逐位相等</b>。</li>
 *   <li><b>lake</b>：湖判定依赖 level + 湖↔河耦合（含蒙特卡洛流量）⇒
 *       有采样噪声 ⇒ 容差判据（记录精确失配率）。</li>
 *   <li><b>channel/width</b>：连续模型流量是蒙特卡洛估计，
 *       门槛附近的格必然翻转（这不是缺陷，是估计量方差）⇒
 *       判据取"一致率 + 翻转集中在门槛邻域"双条件，
 *       而不是要求逐位相同（那是 D8 才可能的口径）。</li>
 * </ul>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染、并发/取消路径未覆盖。</p>
 */
public final class RegionContractProbe {

    /** 湖失配率上限（耦合含 MC 流量，方差不可避免；超标 = 耦合把噪声放大成结论）。 */
    private static final double MAX_LAKE_MISMATCH = 0.02;
    /** 河道一致率下限（双方 interior 全格）。 */
    private static final double MIN_CHANNEL_AGREE = 0.90;
    /** 河道翻转中"门槛邻域"应占的比例（证明翻转来自采样方差而非结构性差异）。 */
    private static final double MIN_NEAR_THRESHOLD = 0.90;
    /** 门槛邻域定义：|smooth - threshold| < 0.25×threshold。 */
    private static final double NEAR_THRESHOLD_FRACTION = 0.25;
    /** interior：距窗缘 ≥ 96 块（两侧窗口都排除边界带）。 */
    private static final int HALO = 96;

    public static void main(String[] args) throws java.io.IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -140;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : 137;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 512;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 150_000;
        int step = 4;
        int offset = 96;                       // 必须是 step 的整倍（网格对齐）

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        var req = new HydrologyPipeline.Request(cx, cz, radius, step, climate, 0,
                HydrologyPipeline.RiverModel.CONTINUOUS, samples);
        var reqB = new HydrologyPipeline.Request(cx + offset, cz + offset, radius, step,
                climate, 0, HydrologyPipeline.RiverModel.CONTINUOUS, samples);

        System.out.println("=== region contract: same world point across window centers ===");
        System.out.printf("seed=%d A=(%d,%d) B=(%d,%d) r=%d step=%d halo=%d samples=%d%n",
                seed, cx, cz, cx + offset, cz + offset, radius, step, HALO, samples);

        // ---- 判据 ①：同窗口两次 = 逐位相同（确定性）----
        long t0 = System.nanoTime();
        var a1 = HydrologyPipeline.run(gen, tp, req);
        double a1Ms = (System.nanoTime() - t0) / 1e6;
        var a2 = HydrologyPipeline.run(gen, tp, req);
        boolean deterministic = bitIdentical(a1, a2);

        t0 = System.nanoTime();
        var b = HydrologyPipeline.run(gen, tp, reqB);
        double bMs = (System.nanoTime() - t0) / 1e6;

        int n = a1.nx();
        // ---- 世界点映射：A 网格 (i,j) → 世界块 (wx,wz) → B 网格下标 ----
        int ax0 = cx - radius, az0 = cz - radius;
        int bx0 = (cx + offset) - radius, bz0 = (cz + offset) - radius;
        int q = HALO / step;
        int bedDiff = 0, compared = 0, unresolvedA = 0, unresolvedB = 0;
        int bothResolved = 0;
        double worstLevel = 0;
        int lakeMismatch = 0;
        int chA = 0, chB = 0, chAgree = 0, chDisagree = 0;
        int chDisagreeNear = 0;       // 翻转且 |smooth-thr| < 0.25 thr（任一侧）
        double widthAbsDiffSum = 0;
        int widthPairs = 0;

        double threshold = ContinuousRiverNetwork.Params
                .forRunoff(a1.lakeResult().runoffPerCell()).channelThreshold();

        for (int i = q; i < n - q; i++) {
            for (int j = q; j < n - q; j++) {
                int wx = ax0 + i * step;
                int wz = az0 + j * step;
                int bi = (wx - bx0) / step;
                int bj = (wz - bz0) / step;
                if (bi < q || bj < q || bi >= n - q || bj >= n - q) continue;   // B 侧 interior
                if ((wx - bx0) % step != 0 || (wz - bz0) % step != 0) continue;
                int ka = i * n + j, kb = bi * n + bj;
                compared++;
                if (a1.bed()[ka] != b.bed()[kb]) bedDiff++;
                if (!a1.resolved()[ka]) unresolvedA++;
                if (!b.resolved()[kb]) unresolvedB++;
                if (a1.resolved()[ka] && b.resolved()[kb]) {
                    bothResolved++;
                    worstLevel = Math.max(worstLevel,
                            Math.abs(a1.level()[ka] - b.level()[kb]));
                    if (a1.lake()[ka] != b.lake()[kb]) lakeMismatch++;
                }
                if (a1.channel()[ka] != b.channel()[kb]) {
                    chDisagree++;
                    double sA = smoothAt(a1, n, i, j);
                    double sB = smoothAt(b, n, bi, bj);
                    double m = Math.min(Math.abs(sA - threshold), Math.abs(sB - threshold));
                    if (m < NEAR_THRESHOLD_FRACTION * threshold) chDisagreeNear++;
                }
                if (a1.channel()[ka]) chA++;
                if (b.channel()[kb]) chB++;
                if (a1.channel()[ka] && b.channel()[kb]) {
                    chAgree++;
                    widthAbsDiffSum += Math.abs(a1.width()[ka] - b.width()[kb]);
                    widthPairs++;
                }
            }
        }

        double lakeMismatchRate = bothResolved > 0 ? lakeMismatch / (double) bothResolved : 0;
        double chAgreeRate = compared > 0
                ? 1.0 - chDisagree / (double) compared : 1.0;
        double nearRate = chDisagree > 0 ? chDisagreeNear / (double) chDisagree : 1.0;
        double meanWidthDiff = widthPairs > 0 ? widthAbsDiffSum / widthPairs : 0;

        StringBuilder log = new StringBuilder();
        log.append(String.format("%n[指标] compared=%d  bothResolved=%d  unresolved A=%d B=%d%n",
                compared, bothResolved, unresolvedA, unresolvedB));
        log.append(String.format("窗口耗时: A=%.0fms  B=%.0fms（同机、含冷缓存差异）%n%n", a1Ms, bMs));

        log.append("[判据]\n");
        boolean c1 = deterministic;
        log.append(String.format("  ① 确定性（同窗口重跑逐位相同）：%s%n", c1 ? "PASS" : "FAIL"));
        boolean c2 = bedDiff == 0;
        log.append(String.format("  ② 床面同点一致（差异格数=%d，期望 0）：%s%n", bedDiff,
                c2 ? "PASS" : "FAIL"));
        boolean c3 = bothResolved > 0 && worstLevel <= 1e-9;
        log.append(String.format("  ③ 双方 resolved 的水位逐位一致：max|Δlevel|=%.3e（≤1e-9）"
                        + " n=%d  %s%n", worstLevel, bothResolved, c3 ? "PASS" : "FAIL"));
        boolean c4 = lakeMismatchRate <= MAX_LAKE_MISMATCH;
        log.append(String.format("  ④ 湖判定失配率 %.4f%% ≤ %.1f%%  %s%n",
                100 * lakeMismatchRate, 100 * MAX_LAKE_MISMATCH, c4 ? "PASS" : "FAIL"));
        boolean c5 = chAgreeRate >= MIN_CHANNEL_AGREE && nearRate >= MIN_NEAR_THRESHOLD;
        log.append(String.format("  ⑤ 河道一致率 %.2f%% ≥ %.0f%% 且翻转集中门槛邻域 "
                        + "%.1f%% ≥ %.0f%%（A河道格=%d B河道格=%d）  %s%n",
                100 * chAgreeRate, 100 * MIN_CHANNEL_AGREE,
                100 * nearRate, 100 * MIN_NEAR_THRESHOLD, chA, chB, c5 ? "PASS" : "FAIL"));
        log.append(String.format("  [诊断] 双方均为河道的格上 |Δwidth| 均值=%.3f 块（n=%d，无阈值："
                        + "MC 流量方差下仅作观测）%n", meanWidthDiff, widthPairs));
        boolean ok = c1 && c2 && c3 && c4 && c5;
        log.append(String.format("%n判定：%s%n", ok
                ? "ALL PASS（跨中心契约成立：可按区域独立求解）"
                : "★ FAIL（存在窗口依赖 ⇒ 接线前必须归因）"));
        System.out.print(log);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/region-contract"));
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("build/region-contract/metrics.txt"), log);
        System.exit(ok ? 0 : 1);
    }

    /** 同窗口两次结果的逐位比较（关键四个数组）。 */
    private static boolean bitIdentical(HydrologyPipeline.Result x, HydrologyPipeline.Result y) {
        if (x.nx() != y.nx()) return false;
        for (int k = 0; k < x.nx() * x.nz(); k++) {
            if (x.level()[k] != y.level()[k]) return false;
            if (x.lake()[k] != y.lake()[k]) return false;
            if (x.channel()[k] != y.channel()[k]) return false;
            if (x.width()[k] != y.width()[k]) return false;
        }
        return true;
    }

    /** 复算 3×3 平滑判定量（与 ContinuousRiverNetwork 同口径）。 */
    private static double smoothAt(HydrologyPipeline.Result r, int n, int i, int j) {
        double sum = 0;
        int cnt = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                int a = i + di, b = j + dj;
                if (a < 0 || b < 0 || a >= n || b >= n) continue;
                int kk = a * n + b;
                if (r.sea()[kk]) continue;
                sum += r.discharge()[kk];
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private RegionContractProbe() { }
}
