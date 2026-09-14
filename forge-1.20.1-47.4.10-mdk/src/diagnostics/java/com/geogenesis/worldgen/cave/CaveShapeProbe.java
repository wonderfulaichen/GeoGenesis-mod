package com.geogenesis.worldgen.cave;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 洞穴几何诊断探针（★ 2026-09-15 重写；配套 {@link CaveShape}）。
 *
 * <h3>★ 为什么重写：此前的探针给出了【错误结论】</h3>
 * <p>用户实测反馈"洞穴非常奇怪，完全不成洞穴的样子"，而本探针此前报 <b>ALL PASS</b>。
 * 根因是<b>诊断盲区</b>：旧版只渲染 <b>X-Y 垂直剖面</b>，而本实现（2D 柱体切挖）
 * 产出的空洞本质是<b>竖直柱体</b> —— 在 X-Y 剖面上，每列就是一个竖直白条，
 * 多条相邻柱体拼起来看像"斑块"，于是<b>图看着还行</b>。</p>
 *
 * <p>重写后补上两个决定性手段：</p>
 * <ol>
 *   <li><b>X-Z 水平切片</b>：柱体的水平截面是孤立小团 ⇒ 一眼看穿"不是隧道"；</li>
 *   <li><b>连通性判据</b>：用 3D 洪水填充统计连通分量。真隧道 = 少数几个<b>巨大</b>分量；
 *       竖直柱堆 = <b>海量小分量</b>。这是"像不像洞穴"的量化度量，
 *       比密度/剖面图都更本质。</li>
 * </ol>
 *
 * <h3>数据来源：合成网格（而非真实地形窗口）</h3>
 * <p>洞穴噪声特征尺度数百块，而真实 chunk 窗口只有数十~百余块
 * （<b>不足一个特征</b>）⇒ 统计剧烈波动且可能整窗是海（实测踩过）。
 * 故改用<b>大范围合成扫描</b>：固定地表高度、中性岩性，生成 {@code N×N×H} 体素网格。</p>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>{@code build/cave/horiz_y*.png} —— X-Z 水平切片（判"是否隧道"）</li>
 *   <li>{@code build/cave/vert_x*.png} —— Y-Z 垂直切片（判柱状/层状）</li>
 *   <li>{@code build/cave/vert_z*.png} —— X-Y 垂直切片</li>
 * </ul>
 *
 * <pre>{@code gradlew runCaveShapeProbe [-PprobeArgs="seed spanXY"]}</pre>
 */
public final class CaveShapeProbe {

    private CaveShapeProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;
    /** 合成网格的最高 Y。 */
    private static final int WORLD_MAX_Y = 180;
    /** 合成扫描固定地表高度。 */
    private static final int SYN_SURFACE = 113;

    public static void main(String[] args) throws Exception {
        // ===== 参数扫描模式（第三参为 "scan"）=====
        //   ★ 为何需要：洞穴形态对 yScale/阈值极度敏感，且**跨种子不稳**
        //     （实测 yScale=1 时 seed=7 长段 39.9%；yScale=2 时 seed=12345 反升 56.9%）。
        //     单点试参数会陷入"修好这个种子、坏了那个种子"的循环 ⇒ 必须**网格扫描**，
        //     找**跨种子稳健**的组合。
        if (args.length > 2 && "scan".equals(args[2])) {
            scanMode(args);
            return;
        }

        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int N = args.length > 1 ? Integer.parseInt(args[1]) : 192;   // 水平边长（块）

        System.out.printf("=== CaveShapeProbe seed=%d spanXY=%d ===%n", seed, N);

        CaveShape.setSeed(seed);
        System.out.printf("[0] 播种: %s%n", CaveShape.isSeeded() ? "OK" : "FAIL");

        int H = WORLD_MAX_Y - WORLD_MIN_Y;
        // voxel[x][z][y] = 洞穴
        boolean[][][] cave = new boolean[N][N][H];
        int[] colTop = new int[N * N];          // 每列最高的洞穴 Y（-1 = 无洞）
        int[] colBot = new int[N * N];
        long spans = 0;
        double lithoAny = CaveShape.lithoFactor(0);    // 中性岩性用 1.0

        // ---------- 生成体素（逐体素 3D 判定，与生产同源）----------
        //   ★ 同时按【分量】分别建体素网格 ⇒ 一次实验即可定位
        //     "是隧道本身不行"还是"洞室/孔洞盖过了隧道"（受控诊断）。
        boolean[][][] tunnel = new boolean[N][N][H];
        boolean[][][] cavern = new boolean[N][N][H];
        boolean[][][] cheese = new boolean[N][N][H];
        long vTunnel = 0, vCavern = 0, vCheese = 0;
        int maxYseen = WORLD_MIN_Y, minYseen = WORLD_MAX_Y;
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                colTop[z * N + x] = Integer.MIN_VALUE;
                colBot[z * N + x] = Integer.MAX_VALUE;
                // 与 CaveCarver 相同的遍历窗口
                int yTop = SYN_SURFACE - CaveShape.SURFACE_LID;
                int yBot = Math.max(WORLD_MIN_Y + 1, SYN_SURFACE - 120);
                for (int y = yBot; y <= yTop; y++) {
                    int mask = CaveShape.components(x, y, z, SYN_SURFACE, WORLD_MIN_Y, 1.0);
                    if (mask == 0) continue;
                    int yi = y - WORLD_MIN_Y;
                    if (yi < 0 || yi >= H) continue;
                    cave[x][z][yi] = true;
                    spans++;
                    if ((mask & CaveShape.F_TUNNEL) != 0) { tunnel[x][z][yi] = true; vTunnel++; }
                    if ((mask & CaveShape.F_CAVERN) != 0) { cavern[x][z][yi] = true; vCavern++; }
                    if ((mask & CaveShape.F_CHEESE) != 0) { cheese[x][z][yi] = true; vCheese++; }
                    if (y > maxYseen) maxYseen = y;
                    if (y < minYseen) minYseen = y;
                    if (y > colTop[z * N + x]) colTop[z * N + x] = y;
                    if (y < colBot[z * N + x]) colBot[z * N + x] = y;
                }
            }
        }
        for (int i = 0; i < colTop.length; i++) {
            if (colTop[i] == Integer.MIN_VALUE) { colTop[i] = -1; colBot[i] = -1; }
        }

        // ---------- 1) 基础统计 ----------
        long caveVox = 0;
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                for (int y = 0; y < H; y++) if (cave[x][z][y]) caveVox++;
            }
        }
        long underVox = (long) N * N * Math.max(0, SYN_SURFACE - WORLD_MIN_Y - CaveShape.SURFACE_LID);
        System.out.printf("[1] 体素: 洞穴=%d 地下=%d 密度=%.2f%%  跨度=%d  列有洞=%d/%d%n",
                caveVox, underVox, 100.0 * caveVox / Math.max(1, underVox),
                spans, countCols(colTop, N), N * N);

        // ---------- 2) ★ 连通性（决定性判据）----------
        int[] comp = new int[N * N * H];
        int nComp = 0;
        int biggest = 0;
        int bigLabel = 0;                    // ★ 记录最大分量的 label（供包围盒用）
        long sumTop3 = 0;
        int[] top3 = new int[3];
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                for (int y = 0; y < H; y++) {
                    if (!cave[x][z][y]) continue;
                    int idx = (x * N + z) * H + y;
                    if (comp[idx] != 0) continue;
                    int label = nComp + 1;
                    int sz = flood(cave, comp, x, z, y, N, H, label);
                    if (sz > biggest) { biggest = sz; bigLabel = label; }
                    if (sz > top3[0]) { top3[2] = top3[1]; top3[1] = top3[0]; top3[0] = sz; }
                    else if (sz > top3[1]) { top3[2] = top3[1]; top3[1] = sz; }
                    else if (sz > top3[2]) { top3[2] = sz; }
                    nComp++;
                }
            }
        }
        sumTop3 = (long) top3[0] + top3[1] + top3[2];
        System.out.printf("[2] 连通分量: 总数=%d  最大=%d(%.1f%%洞穴)  前3大=%d(%.1f%%)  平均=%.1f%n",
                nComp, biggest, 100.0 * biggest / Math.max(1, caveVox),
                sumTop3, 100.0 * sumTop3 / Math.max(1, caveVox),
                nComp == 0 ? 0.0 : (double) caveVox / nComp);

        // ---------- 3) ★★ 形态：隧道 vs 竖直柱（决定性判据）----------
        //
        //   ⚠⚠ 方法论警告（本探针曾被此坑，必须记录）：
        //     **旧版的"纵横比 = 水平团大小/竖向厚度"指标是错的** —— 它曾给出 14.45
        //     并让我误判"像隧道"，而用户实测"完全不成洞穴的样子"。
        //     原因：该指标把**所有**水平团的平均大小当分子，而少数**巨团**（多个
        //     竖直柱恰好相连形成的块）会把平均值拉高，掩盖"大多数是孤立小团"的事实。
        //
        //   改用两个**不可被巨团欺骗**的量：
        //     ① 竖向连续段长度：隧道穿过一列时，只在 Y 上留下一小段（约等于管径）；
        //        竖直柱则留下**贯穿整条带**的长段。这是最直观的区分。
        //     ② 最大连通分量的**包围盒纵横比**：隧道在水平方向绵延很远、竖向很薄
        //        ⇒ (dx 或 dz) / dy >> 1；竖直柱则相反（dy 大）。
        long voxInLongRun = 0;          // 位于 ≥20 块长竖向段的体素
        double sumRun = 0;
        long nRun = 0, nRunCols = 0;
        int maxRun = 0;
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                int run = 0;
                boolean any = false;
                for (int y = 0; y < H; y++) {
                    if (cave[x][z][y]) {
                        run++;
                        any = true;
                    } else if (run > 0) {
                        sumRun += run; nRun++;
                        if (run >= 20) voxInLongRun += run;
                        maxRun = Math.max(maxRun, run);
                        run = 0;
                    }
                }
                if (run > 0) {
                    sumRun += run; nRun++;
                    if (run >= 20) voxInLongRun += run;
                    maxRun = Math.max(maxRun, run);
                }
                if (any) nRunCols++;
            }
        }
        double meanRun = nRun == 0 ? 0 : sumRun / nRun;
        System.out.printf("[3] 竖向连续段: 平均=%.1f块 最大=%d块 有洞列=%d"
                        + "  长段(≥20)体素占比=%.1f%%%n",
                meanRun, maxRun, nRunCols,
                100.0 * voxInLongRun / Math.max(1, caveVox));
        System.out.printf("    → 隧道预期：平均段长小（≈管径数块）、长段占比低；"
                + "竖直柱预期：平均段长大（贯穿带）、长段占比高%n");

        // 最大连通分量包围盒（扫一遍带 bigLabel 的体素）
        int[] bbox = { N, -1, N, -1, Integer.MAX_VALUE, -1 };   // x0,x1, z0,z1, y0,y1
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                for (int y = 0; y < H; y++) {
                    if (comp[(x * N + z) * H + y] != bigLabel) continue;
                    if (x < bbox[0]) bbox[0] = x;
                    if (x > bbox[1]) bbox[1] = x;
                    if (z < bbox[2]) bbox[2] = z;
                    if (z > bbox[3]) bbox[3] = z;
                    if (y < bbox[4]) bbox[4] = y;
                    if (y > bbox[5]) bbox[5] = y;
                }
            }
        }
        int dx = bbox[1] - bbox[0] + 1, dy = bbox[5] - bbox[4] + 1, dz = bbox[3] - bbox[2] + 1;
        double horizSpan = Math.max(dx, dz);
        double bboxAspect = dy <= 0 ? 0 : horizSpan / dy;
        System.out.printf("[3b] 最大分量包围盒: dx=%d dy=%d dz=%d  → 水平/竖向=%.2f"
                        + "（隧道应 >> 1）%n", dx, dy, dz, bboxAspect);

        // ---------- 3c) ★★ 分量分解（受控诊断：定位"是哪个分量不行"）----------
        System.out.printf("[3c] 分量密度: 隧道=%.2f%% 洞室=%.2f%% 孔洞=%.2f%%"
                        + "（三者可重叠，并集=%.2f%%）%n",
                100.0 * vTunnel / underVox, 100.0 * vCavern / underVox,
                100.0 * vCheese / underVox, 100.0 * caveVox / underVox);
        // 各分量的竖向段与包围盒
        reportComp("隧道", tunnel, N, H, underVox);
        reportComp("洞室", cavern, N, H, underVox);
        reportComp("孔洞", cheese, N, H, underVox);

        // ---------- 4) 渲染（并集 + 各分量，便于对照判读）----------
        File dir = new File("build/cave");
        dir.mkdirs();
        int[] levels = { WORLD_MIN_Y + 40, WORLD_MIN_Y + 80, WORLD_MIN_Y + 120 };
        for (int y : levels) {
            int yi = y - WORLD_MIN_Y;
            if (yi < 0 || yi >= H) continue;
            writeSlice(dir, cave, N, yi, "horiz_y" + y);
            writeSlice(dir, tunnel, N, yi, "horiz_y" + y + "_tunnel");
            writeSlice(dir, cavern, N, yi, "horiz_y" + y + "_cavern");
        }
        // 垂直切片（Y-Z）：并集与隧道对照 —— 隧道应表现为"短横条"而非"竖直柱"
        for (int k = 0; k < 2; k++) {
            int x0 = N / 3 + k * (N / 3);
            writeVertical(dir, cave, N, H, x0, "vert_x" + x0);
            writeVertical(dir, tunnel, N, H, x0, "vert_x" + x0 + "_tunnel");
        }
        System.out.printf("[4] 图像输出: %s（horiz_* 水平 / vert_* 垂直；_tunnel/_cavern 为分量）%n",
                dir.getAbsolutePath());

        // ---------- 判据 ----------
        double densityPct = 100.0 * caveVox / Math.max(1, underVox);
        boolean pass1 = densityPct >= 0.5 && densityPct <= 8.0;
        System.out.printf("[判据1] 洞穴密度 ∈ [0.5%%, 8%%]: %s（%.2f%%）%n",
                pass1 ? "PASS" : "FAIL", densityPct);

        boolean pass2 = true;   // 洞顶越界（合成地表下必须留 LID）
        for (int v : colTop) {
            if (v > SYN_SURFACE - CaveShape.SURFACE_LID) { pass2 = false; break; }
        }
        System.out.printf("[判据2] 未挖穿地表: %s%n", pass2 ? "PASS" : "FAIL");

        // ★★ 判据3：形态必须是"隧道"而非"竖直柱"
        //
        //   ⚠⚠ 方法论（两次修正的记录，务必保留）：
        //     ① 初版用"水平团平均大小/竖向厚度"，被少数**巨团**拉高（假阳性 14.45）；
        //     ② 二版用"最大分量的**全局**包围盒纵横比"，也不对 —— 分量跨越整个
        //        采样区是**正常**的（隧道本就该连成大网），全局包围盒恒接近
        //        N×H×N ⇒ 该指标恒 ≈1，无判别力（实测 1.13 假阴性）。
        //     ③ 现在只用**【局部】竖向连续段**：它直接反映"一根管道穿过一列时
        //        留下多长的一段"。竖直柱 ⇒ 段长≈带高；隧道 ⇒ 段长≈管径（数块）。
        //        这是唯一不可被"连通成大网"这件事污染的指标。
        boolean runOk = meanRun > 0 && meanRun <= 10.0;                 // 平均段长≈管径
        boolean longRunLow = voxInLongRun < caveVox * 0.20;             // 长段体素占比低
        boolean pass3 = runOk && longRunLow;
        System.out.printf("[判据3] 形态像隧道（局部竖向段）: %s%n", pass3 ? "PASS" : "FAIL");
        System.out.printf("         平均段≤10块: %s（平均 %.1f）| 长段(≥20)占比<20%%: %s（%.1f%%）%n",
                runOk ? "✓" : "✗", meanRun,
                longRunLow ? "✓" : "✗", 100.0 * voxInLongRun / Math.max(1, caveVox));
        System.out.printf("         （注：包围盒 dx=%d dy=%d dz=%d 水平/竖向=%.2f —— 已弃用，"
                        + "分量连通成网是正常的）%n", dx, dy, dz, bboxAspect);

        // ★★ 判据4：连通性 —— 洞穴应聚成少数大分量
        boolean pass4 = biggest >= 2000 && sumTop3 >= caveVox * 0.30;
        System.out.printf("[判据4] 连通成大网络（最大分量≥2000 且 前3大≥30%%）: %s"
                        + "（最大=%d，前3大占 %.1f%%）%n",
                pass4 ? "PASS" : "FAIL", biggest, 100.0 * sumTop3 / Math.max(1, caveVox));

        boolean all = pass1 && pass2 && pass3 && pass4;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }

    /**
     * 参数网格扫描：对 (yScaleMul × thresholdMul) 组合，在<b>多个种子</b>上统计形态，
     * 输出"跨种子最差"指标 ⇒ 选稳健组合而非"修好一个坏另一个"。
     */
    private static void scanMode(String[] args) throws Exception {
        int N = args.length > 1 ? Integer.parseInt(args[1]) : 96;
        long[] seeds = { 12345L, 7L, 42L };
        double[] yMuls = { 2.5, 3.0, 3.5, 4.0 };
        double[] tMuls = { 0.5, 0.6, 0.7, 0.8 };

        System.out.printf("=== 参数扫描 N=%d seeds=%d ===%n", N, seeds.length);
        System.out.printf("%6s %6s | %8s | %8s %8s %8s | %8s%n",
                "yMul", "tMul", "密度%(最差)", "平均段最差", "长段%最差", "连通%最差", "判定");

        int H = WORLD_MAX_Y - WORLD_MIN_Y;
        long underVox = (long) N * N * Math.max(0, SYN_SURFACE - WORLD_MIN_Y - CaveShape.SURFACE_LID);

        for (double tm : tMuls) {
            for (double ym : yMuls) {
                double worstDensity = 0, worstMeanRun = 0, worstLongRun = 0;
                double minConn = 1e9;
                for (long sd : seeds) {
                    CaveShape.setSeed(sd);
                    CaveShape.dbgSet(ym, tm);
                    boolean[][][] v = new boolean[N][N][H];
                    long vox = 0;
                    for (int x = 0; x < N; x++) {
                        for (int z = 0; z < N; z++) {
                            int yTop = SYN_SURFACE - CaveShape.SURFACE_LID;
                            int yBot = Math.max(WORLD_MIN_Y + 1, SYN_SURFACE - 120);
                            for (int y = yBot; y <= yTop; y++) {
                                if (!CaveShape.isCave(x, y, z, SYN_SURFACE, WORLD_MIN_Y, 1.0)) continue;
                                v[x][z][y - WORLD_MIN_Y] = true;
                                vox++;
                            }
                        }
                    }
                    // 竖向段
                    double sumRun = 0;
                    long nRun = 0, longVox = 0;
                    for (int x = 0; x < N; x++) {
                        for (int z = 0; z < N; z++) {
                            int run = 0;
                            for (int y = 0; y < H; y++) {
                                if (v[x][z][y]) run++;
                                else if (run > 0) {
                                    sumRun += run; nRun++;
                                    if (run >= 20) longVox += run;
                                    run = 0;
                                }
                            }
                            if (run > 0) {
                                sumRun += run; nRun++;
                                if (run >= 20) longVox += run;
                            }
                        }
                    }
                    // 连通（最大分量占比）
                    int[] comp = new int[N * N * H];
                    int nc = 0;
                    long big = 0;
                    for (int x = 0; x < N; x++) {
                        for (int z = 0; z < N; z++) {
                            for (int y = 0; y < H; y++) {
                                if (!v[x][z][y]) continue;
                                if (comp[(x * N + z) * H + y] != 0) continue;
                                int sz = flood(v, comp, x, z, y, N, H, ++nc);
                                big = Math.max(big, sz);
                            }
                        }
                    }
                    double density = 100.0 * vox / underVox;
                    double meanRun = nRun == 0 ? 999 : sumRun / nRun;
                    double longPct = vox == 0 ? 0 : 100.0 * longVox / vox;
                    double conn = vox == 0 ? 0 : 100.0 * big / vox;
                    worstDensity = Math.max(worstDensity, density);
                    worstMeanRun = Math.max(worstMeanRun, meanRun);
                    worstLongRun = Math.max(worstLongRun, longPct);
                    minConn = Math.min(minConn, conn);
                }
                boolean ok = worstMeanRun <= 10 && worstLongRun <= 20
                        && worstDensity >= 0.5 && worstDensity <= 8 && minConn >= 30;
                System.out.printf("%6.1f %6.1f | %10.2f | %10.1f %9.1f %10.1f | %8s%n",
                        ym, tm, worstDensity, worstMeanRun, worstLongRun, minConn,
                        ok ? "★OK" : "-");
            }
        }
        CaveShape.dbgReset();
        CaveShape.setSeed(12345L);
    }

    /** 报告某分量的竖向段/包围盒形态（受控诊断用）。 */
    private static void reportComp(String name, boolean[][][] v, int N, int H, long underVox) {
        long vox = 0;
        for (int x = 0; x < N; x++)
            for (int z = 0; z < N; z++)
                for (int y = 0; y < H; y++) if (v[x][z][y]) vox++;
        if (vox == 0) { System.out.printf("    %s: 空%n", name); return; }

        // 竖向连续段
        double sumRun = 0;
        long nRun = 0;
        int maxRun = 0;
        long longRunVox = 0;
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                int run = 0;
                for (int y = 0; y < H; y++) {
                    if (v[x][z][y]) run++;
                    else if (run > 0) {
                        sumRun += run; nRun++;
                        if (run >= 20) longRunVox += run;
                        maxRun = Math.max(maxRun, run); run = 0;
                    }
                }
                if (run > 0) {
                    sumRun += run; nRun++;
                    if (run >= 20) longRunVox += run;
                    maxRun = Math.max(maxRun, run);
                }
            }
        }
        // 包围盒（全分量）
        int x0 = N, x1 = -1, z0 = N, z1 = -1, y0 = Integer.MAX_VALUE, y1 = -1;
        for (int x = 0; x < N; x++)
            for (int z = 0; z < N; z++)
                for (int y = 0; y < H; y++) {
                    if (!v[x][z][y]) continue;
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                    z0 = Math.min(z0, z); z1 = Math.max(z1, z);
                    y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
        int dx = x1 - x0 + 1, dy = y1 - y0 + 1, dz = z1 - z0 + 1;
        System.out.printf("    %-4s 密度=%5.2f%%  竖向段平均=%.1f 最大=%d 长段占比=%.1f%%"
                        + "  包围盒 dx=%d dy=%d dz=%d 水平/竖向=%.2f%n",
                name, 100.0 * vox / underVox, nRun == 0 ? 0 : sumRun / nRun, maxRun,
                100.0 * longRunVox / vox, dx, dy, dz,
                dy == 0 ? 0 : (double) Math.max(dx, dz) / dy);
    }

    /** 写一张 X-Z 水平切片。 */
    private static void writeSlice(File dir, boolean[][][] v, int N, int yi, String name)
            throws Exception {
        BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                img.setRGB(x, z, v[x][z][yi] ? 0xFFFFFF : 0x101010);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    /** 写一张 Y-Z 垂直切片（y 向上）。 */
    private static void writeVertical(File dir, boolean[][][] v, int N, int H, int x0,
                                      String name) throws Exception {
        int yLo = WORLD_MIN_Y, yHi = WORLD_MAX_Y;
        int hh = yHi - yLo;
        BufferedImage img = new BufferedImage(N, hh, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < N; z++) {
            for (int y = yLo; y < yHi; y++) {
                int yi = y - WORLD_MIN_Y;
                boolean on = yi >= 0 && yi < H && v[x0][z][yi];
                img.setRGB(z, hh - 1 - (y - yLo), on ? 0xFFFFFF : 0x101010);
            }
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    private static int countCols(int[] colTop, int N) {
        int n = 0;
        for (int v : colTop) if (v >= 0) n++;
        return n;
    }



    /** 3D 洪水填充（6 邻域），返回分量大小。 */
    private static int flood(boolean[][][] cave, int[] comp, int x0, int z0, int y0,
                             int N, int H, int label) {
        comp[(x0 * N + z0) * H + y0] = label;
        int size = 0;
        int[] qx = new int[8192], qz = new int[8192], qy = new int[8192];
        int qh = 0, qt = 0;
        qx[qt] = x0; qz[qt] = z0; qy[qt] = y0; qt++;
        while (qh < qt) {
            int x = qx[qh], z = qz[qh], y = qy[qh]; qh++;
            size++;
            for (int d = 0; d < 6; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                int ny = y + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (nx < 0 || nx >= N || nz < 0 || nz >= N || ny < 0 || ny >= H) continue;
                if (!cave[nx][nz][ny]) continue;
                int ni = (nx * N + nz) * H + ny;
                if (comp[ni] != 0) continue;
                comp[ni] = label;
                if (qt >= qx.length) {
                    qx = java.util.Arrays.copyOf(qx, qt * 2);
                    qz = java.util.Arrays.copyOf(qz, qt * 2);
                    qy = java.util.Arrays.copyOf(qy, qt * 2);
                }
                qx[qt] = nx; qz[qt] = nz; qy[qt] = ny; qt++;
            }
        }
        return size;
    }

    /** 单层 2D 洪水填充（4 邻域），返回连通团大小。 */
    private static int flood2D(boolean[][][] cave, boolean[][] vis, int x0, int z0, int N, int yi) {
        int[] qx = new int[N * N], qz = new int[N * N];
        int qh = 0, qt = 0;
        qx[qt] = x0; qz[qt] = z0; qt++;
        vis[x0][z0] = true;
        int size = 0;
        while (qh < qt) {
            int x = qx[qh], z = qz[qh]; qh++;
            size++;
            for (int d = 0; d < 4; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nx >= N || nz < 0 || nz >= N) continue;
                if (vis[nx][nz] || !cave[nx][nz][yi]) continue;
                vis[nx][nz] = true;
                qx[qt] = nx; qz[qt] = nz; qt++;
            }
        }
        return size;
    }
}
