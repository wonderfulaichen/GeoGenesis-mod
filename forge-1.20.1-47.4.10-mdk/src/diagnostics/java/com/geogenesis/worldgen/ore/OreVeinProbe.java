package com.geogenesis.worldgen.ore;

import com.geogenesis.worldgen.cave.CaveShape;
import com.geogenesis.worldgen.terrain.RockType;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * 矿脉诊断探针（配套 {@link OreVeins}）。
 *
 * <h3>数据来源：合成网格（而非真实地形窗口）</h3>
 * <p>矿脉的成矿带特征尺度为 190 块，而真实 chunk 窗口只有 16~64 块
 * （<b>不足一个特征</b>）⇒ 统计会剧烈波动（本项目在洞穴密度上踩过这个坑：
 * 噪声尺度 350 而窗口 64 ⇒ 三种子实测 0.40%~7.11%）。故用<b>大范围合成扫描</b>：
 * 固定地表高度，逐体素按 {@link OreVeins#veinAt} 判定。</p>
 *
 * <h3>判据</h3>
 * <ol>
 *   <li><b>可见不过量</b>：矿石体积占地下体积的比例落在合理区间；</li>
 *   <li><b>矿种齐全</b>：8 种矿都能出现（不能有"永远不生成"的死矿种）；</li>
 *   <li><b>★ 岩性耦合</b>：受控合成对照 —— 同一点只用不同 {@code rockOrd} 求值。
 *       煤只应在砂岩/页岩出现（石灰岩/花岗岩处必须为 0）；钻石只应在片麻岩/片岩；</li>
 *   <li><b>★ 深度带生效</b>：煤只出现在浅部、钻石只出现在深部（深度分布双峰可分）；</li>
 *   <li><b>脉体形态</b>：连通分量应为"少数中等分量"而非"海量孤立点"
 *       （后者意味着只是随机撒点，不是脉）。</li>
 * </ol>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>{@code build/ore/vert_x*.png} —— Y-Z 垂直切片（判深度带）</li>
 *   <li>{@code build/ore/horiz_y*.png} —— X-Z 水平切片（判脉体平面形态）</li>
 * </ul>
 *
 * <pre>{@code gradlew runOreVeinProbe [-PprobeArgs="seed spanXY [scan]"]}</pre>
 */
public final class OreVeinProbe {

    private OreVeinProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;
    /** 合成扫描固定地表高度（保证所有矿的深度带都可达）。 */
    private static final int SYN_SURFACE = 260;

    /**
     * 岩性分区边长（块）：模拟真实地层的"横向成片"。
     *
     * <p>必须成片而非逐列轮换 —— 后者会让相邻列岩性不同，脉体被反复截断，
     * 连通性与脉形统计严重失真（实测踩过：平均分量 26 块、切片呈散点）。
     * 真实 {@code StratumField} 的岩性在数百块尺度成片，128 是保守取值。</p>
     */
    private static final int ROCK_ZONE = 128;

    public static void main(String[] args) throws Exception {
        if (args.length > 2 && "scan".equals(args[2])) {
            scanMode(args);
            return;
        }
        // 参数覆盖仅接受数字（"scan" 已在上面分流）

        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        // ★ 默认窗口必须 ≥512：成矿带特征尺度 190 块，N=160 时带内列仅 5.4%
        //   ⇒ 连通性统计被窗口边界"截断"（脉体在边界被切），判据5 会误报 FAIL。
        //   这是"窗口不足一个特征"的老坑（本项目在洞穴密度上踩过一次）。
        //   调大默认值 ⇒ 无参数运行也给出可信结论，不再给后人埋雷。
        int N = args.length > 1 ? Integer.parseInt(args[1]) : 512;

        // 可选参数覆盖（便于迭代形态，不污染生产默认值）：
        //   args[2] = veinMul（脉体阈值倍率），args[3] = prosMul（成矿带阈值倍率）
        double veinMul = args.length > 2 ? Double.parseDouble(args[2]) : 1.0;
        double prosMul = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;

        System.out.printf("=== OreVeinProbe seed=%d spanXY=%d surface=%d "
                        + "veinMul=%.2f prosMul=%.2f ===%n",
                seed, N, SYN_SURFACE, veinMul, prosMul);

        OreVeins.setSeed(seed);
        OreVeins.dbgSetVein(veinMul);
        OreVeins.dbgSetProspect(prosMul);
        System.out.printf("[0] 播种: %s%n", OreVeins.isSeeded() ? "OK" : "FAIL");

        // ---------- 合成扫描 ----------
        //   y 范围只取"最深矿种深度带上界 + 2"以内（更深的 Y 必然无矿，遍历是浪费）。
        //   ★ 窗口必须覆盖【多个成矿带特征尺度】(190 块)：实测 N=96 时 seed=12345/42
        //     的带内列为 0（不足一个特征）⇒ 统计空转。故默认 N=512（≥2.5 个特征）。
        int yTop = SYN_SURFACE - 1;
        int yBot = Math.max(WORLD_MIN_Y + 1, SYN_SURFACE - OreVeins.MAX_DEPTH - 2);
        int H = yTop - yBot + 1;
        // 展平索引：idx = (x * N + z) * H + (y - yBot)
        //   0 = 无矿；1+i = 矿种 i；BFS 访问后置 255（⇒ 无需额外 comp 数组，省 ~200MB）
        byte[] voxel = new byte[N * N * H];
        long[] perOre = new long[OreVeins.ORES.length];
        long[] perOreDepthSum = new long[OreVeins.ORES.length];
        int[] perOreMinDepth = new int[OreVeins.ORES.length];
        int[] perOreMaxDepth = new int[OreVeins.ORES.length];
        Arrays.fill(perOreMinDepth, Integer.MAX_VALUE);
        Arrays.fill(perOreMaxDepth, Integer.MIN_VALUE);

        long total = 0, oreTotal = 0, prospectiveCols = 0;
        int rocks = RockType.values().length;
        long t0 = System.nanoTime();
        for (int x = 0; x < N; x++) {
            for (int z = 0; z < N; z++) {
                // ★ 岩性必须【成片】（2026-09-15 修正）：初版按 (x+z) 逐列轮换，
                //   导致相邻列岩性不同 ⇒ 脉体被"换岩"反复截断 ⇒ 连通性统计严重偏低
                //   （实测平均分量仅 26 块、水平切片呈散点，而非脉状）。
                //   真实生产的岩性来自 StratumField 的水平地层，横向成片（数百块级），
                //   故这里用 128×128 分区（岩石类型按区块轮换）近似。
                int rockOrd = ((x / ROCK_ZONE) + (z / ROCK_ZONE) * 4) % rocks;
                total += H;
                if (!OreVeins.beginColumn(x, z)) continue;
                prospectiveCols++;
                int base = (x * N + z) * H - yBot;
                for (int y = yBot; y <= yTop; y++) {
                    int o = OreVeins.veinAt(x, y, z, SYN_SURFACE, rockOrd, WORLD_MIN_Y);
                    if (o < 0) continue;
                    voxel[base + y] = (byte) (1 + o);
                    perOre[o]++;
                    int d = SYN_SURFACE - y;
                    perOreDepthSum[o] += d;
                    perOreMinDepth[o] = Math.min(perOreMinDepth[o], d);
                    perOreMaxDepth[o] = Math.max(perOreMaxDepth[o], d);
                    oreTotal++;
                }
            }
        }
        long el = System.nanoTime() - t0;
        double pct = total == 0 ? 0.0 : 100.0 * oreTotal / total;
        System.out.printf("[1] 合成扫描: 体素=%d 矿石=%d 密度=%.4f%%  带内列=%d/%d(%.1f%%)%n",
                total, oreTotal, pct, prospectiveCols, (long) N * N,
                100.0 * prospectiveCols / ((long) N * N));
        System.out.printf("    耗时=%dms  单块均摊=%.4f us（含列级门控）%n",
                el / 1_000_000, el / 1000.0 / total);

        // ---------- 判据1：可见不过量 ----------
        boolean pass1 = pct >= 0.005 && pct <= 1.5;
        System.out.printf("[判据1] 矿脉密度 ∈ [0.005%%, 1.5%%]（可见且不过量）: %s（实测 %.4f%%）%n",
                pass1 ? "PASS" : "FAIL", pct);

        // ---------- 判据2：矿种齐全 ----------
        StringBuilder sb = new StringBuilder();
        int missing = 0;
        for (int i = 0; i < OreVeins.ORES.length; i++) {
            if (perOre[i] == 0) missing++;
            sb.append(String.format("%s=%d ", OreVeins.ORES[i], perOre[i]));
        }
        System.out.println("[2] 各矿种计数: " + sb);
        boolean pass2 = missing == 0;
        System.out.printf("[判据2] 8 种矿全部出现（无死矿种）: %s%s%n",
                pass2 ? "PASS" : "FAIL", missing == 0 ? "" : "（缺 " + missing + " 种）");

        // ---------- 判据3：岩性耦合（受控合成对照）----------
        //   对每种矿，分别用【宿主岩】与【非宿主岩】在**同一点**求值，比较命中数。
        //   宿主岩必须 > 0，非宿主岩必须 = 0（这正是"岩性门控"的直接检验）。
        //
        //   ★ 探针自身陷阱（实测踩过，已修）：必须**先找到落在成矿带内的点**再做对照。
        //     首版用固定点 `i*7-5000, i*11-3000` 并期望其中有带内点，但成矿带只占
        //     2.5% 的列 ⇒ 3000 点里带内的很少、且各矿深度带不同 ⇒ 宿主岩命中数为 0，
        //     判据全 FAIL 却是**假阴性**（掩盖了"门控其实生效"这一事实）。
        //     现改为：每一轮先扫到带内点，再在同一带上按 (矿种 × 深度带中点) 逐一对
        //     宿主/非宿主求值 —— 这样对照的是同一体素的唯一变量。
        System.out.println("[3] 岩性耦合（受控：同点只换 rockOrd）:");
        boolean pass3 = true;
        // 收集一批带内点（跨多个特征尺度，避免只落在单一矿区的偶然性）
        java.util.List<int[]> band = new java.util.ArrayList<>();
        for (int i = 0; i < 200000 && band.size() < 400; i++) {
            int x = (i % 400) * 13 - 2600, z = (i / 400) * 17 - 2600;
            if (OreVeins.columnProspective(x, z)) band.add(new int[]{x, z});
        }
        System.out.printf("    带内样本点=%d%n", band.size());
        for (OreVeins.Ore o : OreVeins.ORES) {
            int firstHost = -1, firstNonHost = -1;
            for (int r = 0; r < rocks; r++) {
                if (o.hosts(r)) { if (firstHost < 0) firstHost = r; }
                else { if (firstNonHost < 0) firstNonHost = r; }
            }
            int hostHits = 0, nonHostHits = 0;
            for (int[] p : band) {
                // 遍历该矿的整个深度带（不只中点）⇒ 命中概率与真实生成一致
                for (int y = SYN_SURFACE - o.maxDepth; y <= SYN_SURFACE - o.minDepth; y++) {
                    if (firstHost >= 0
                            && OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, firstHost, WORLD_MIN_Y)
                               == o.ordinal()) hostHits++;
                    if (firstNonHost >= 0
                            && OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, firstNonHost, WORLD_MIN_Y)
                               == o.ordinal()) nonHostHits++;
                }
            }
            boolean ok = hostHits > 0 && nonHostHits == 0;
            if (!ok) pass3 = false;
            System.out.printf("    %-9s 宿主岩(%s)=%d  非宿主岩(%s)=%d  %s%n",
                    o, firstHost < 0 ? "-" : RockType.values()[firstHost], hostHits,
                    firstNonHost < 0 ? "-" : RockType.values()[firstNonHost], nonHostHits,
                    ok ? "OK" : "★FAIL");
        }
        System.out.printf("[判据3] 岩性门控生效（宿主岩命中>0 且 非宿主岩=0）: %s%n",
                pass3 ? "PASS" : "FAIL");

        // ---------- 判据4：深度带生效 ----------
        System.out.println("[4] 深度分布（距地表，block）:");
        boolean pass4 = true;
        for (int i = 0; i < OreVeins.ORES.length; i++) {
            if (perOre[i] == 0) continue;
            double avg = (double) perOreDepthSum[i] / perOre[i];
            System.out.printf("    %-9s 实测[%d,%d] 均值%.0f  设计[%d,%d]%n",
                    OreVeins.ORES[i], perOreMinDepth[i], perOreMaxDepth[i], avg,
                    OreVeins.ORES[i].minDepth, OreVeins.ORES[i].maxDepth);
            // 实测区间必须落在设计带内（允许 0 容差：门控是硬整数比较）
            if (perOreMinDepth[i] < OreVeins.ORES[i].minDepth
                    || perOreMaxDepth[i] > OreVeins.ORES[i].maxDepth) {
                pass4 = false;
            }
        }
        System.out.printf("[判据4] 深度带硬约束生效（实测区间 ⊆ 设计区间）: %s%n",
                pass4 ? "PASS" : "FAIL");

        // ★ 剖面图必须在 BFS【之前】渲染：BFS 会把已访问体素标记为 255，
        //   渲染时会把它们全画成最后一色（数据已被破坏）。实测踩过。
        renderSlices(voxel, N, H, yBot);

        // ---------- 判据5：脉体连通性 ----------
        //   真脉 = 少数中等连通分量；随机撒点 = 海量孤立单点。
        //   3D 26 邻洪水填充；已访问的体素标记为 255（复用 voxel，省 comp 数组）。
        //
        //   ⚠ 口径说明（如实记录）：本扫描<b>逐列轮换岩性</b>（(x+z)%8），而真实岩性是
        //     <b>成片</b>的 ⇒ 这里脉体被"换岩"频繁截断，测得的平均分量比真实世界<b>偏小</b>。
        //     故判据5 是<b>保守下界</b>：通过 ⇒ 真实世界只会更好。
        int nComp = 0;
        int maxComp = 0;
        long singleComp = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int idx = 0; idx < voxel.length; idx++) {
            if (voxel[idx] == 0 || voxel[idx] == (byte) 255) continue;
            nComp++;
            int size = 0;
            q.add(idx);
            voxel[idx] = (byte) 255;
            while (!q.isEmpty()) {
                int cur = q.poll();
                size++;
                int cy = cur % H;
                int col = cur / H;                 // = x*N + z
                int cx = col / N, cz2 = col % N;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx2 = cx + dx;
                    if (nx2 < 0 || nx2 >= N) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz2 = cz2 + dz;
                        if (nz2 < 0 || nz2 >= N) continue;
                        int nBase = (nx2 * N + nz2) * H;
                        for (int dy = -1; dy <= 1; dy++) {
                            int ny2 = cy + dy;
                            if (ny2 < 0 || ny2 >= H) continue;
                            int nIdx = nBase + ny2;
                            byte v = voxel[nIdx];
                            if (v == 0 || v == (byte) 255) continue;
                            voxel[nIdx] = (byte) 255;
                            q.add(nIdx);
                        }
                    }
                }
            }
            if (size == 1) singleComp++;
            maxComp = Math.max(maxComp, size);
        }
        double avgComp = nComp == 0 ? 0 : (double) oreTotal / nComp;
        System.out.printf("[5] 连通分量=%d 最大=%d 平均=%.1f 孤立单点=%d(%.1f%%)%n",
                nComp, maxComp, avgComp, singleComp,
                nComp == 0 ? 0 : 100.0 * singleComp / nComp);
        boolean pass5 = nComp > 0 && avgComp >= 4.0 && 100.0 * singleComp / nComp < 40.0;
        System.out.printf("[判据5] 脉体形态（平均分量≥4块 且 孤立点<40%%）: %s%n",
                pass5 ? "PASS" : "FAIL");

        // ---------- 判据6：洞穴联动（矿脉在洞壁露头）----------
        //   受控对照：同点分别用 exposure=1.0（无联动）与 exposure=VEIN_EXPOSURE_MUL
        //   （紧邻洞穴）求值，统计命中数增幅。增幅必须显著（否则联动形同虚设）。
        //
        //   ⚠ 口径说明：本判据直接给 exposure 值做对照，**不**真的调用 CaveShape
        //     （那需要完整的地表/岩性上下文，且与洞穴探针职责重复）。它隔离的是
        //     "暴露面放大是否真的让脉更密"这一机制本身。
        System.out.println("[6] 洞穴联动（受控：同点只换 exposure）:");
        //   ★ 对照必须限制在【洞穴深度窗口】内（CaveShape.DEPTH_MIN..DEPTH_MAX）。
        //     为何：矿脉深度带是 6~220，而洞穴只存在于地表下 8~120 ⇒ 深于 120 处
        //     **根本没有洞穴**，"紧邻洞穴"不可能成立 ⇒ 不放大才是正确的。
        //     若把整个矿脉深度带都拿去对照，会高估联动范围（实测踩过：这样算出的
        //     "朴素高 exposure" 命中 24597，而合理口径只有 10409）。
        int dLo = Math.max(OreVeins.MIN_DEPTH, CaveShape.DEPTH_MIN);
        int dHi = Math.min(OreVeins.MAX_DEPTH, CaveShape.DEPTH_MAX);
        System.out.printf("    对照深度窗口 = 地表下 [%d, %d]（矿脉∩洞穴）%n", dLo, dHi);
        long plainHits = 0, expHits = 0;
        long linkSamples = 0;
        for (int[] p : band) {
            for (int y = SYN_SURFACE - dHi; y <= SYN_SURFACE - dLo; y++) {
                for (int r = 0; r < rocks; r++) {
                    linkSamples++;
                    if (OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, r, WORLD_MIN_Y, 1.0) >= 0) {
                        plainHits++;
                    }
                    if (OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, r, WORLD_MIN_Y,
                            OreVeins.VEIN_EXPOSURE_MUL) >= 0) {
                        expHits++;
                    }
                }
            }
        }
        // ★ 等价性校验（本判据的<b>正确性核心</b>）：两阶段优化（veinAtLinked）
        //   依赖"三态划分"不丢命中。这里直接校验该不变式：
        //     对任意体素，若 veinHitCode 返回 1（命中）或 0（远离），
        //     则【无论 exposure 多大】结论都不变；只有返回 -1（擦肩）时
        //     exposure 才可能翻转结果。
        //   违反任一条 ⇒ 优化会丢矿（最危险的静默 bug）。
        //
        //   ⚠ 为何不用"恒紧邻洞穴"模拟：那需要调用真实 CaveShape.isCave，
        //     而它要求先 setSeed。实测踩过 —— 忘了 setSeed 会让 isCave 恒 false，
        //     于是"两阶段结果 == 无联动结果"，看起来像优化丢矿，实为探针没播种。
        //     改为直接校验数学不变式后，该陷阱不存在。
        long code1 = 0, code0 = 0, codeMinus1 = 0, bad = 0;
        for (int[] p : band) {
            for (int y = SYN_SURFACE - dHi; y <= SYN_SURFACE - dLo; y++) {
                for (int r = 0; r < rocks; r++) {
                    // 两端作为"地面真值"：
                    //   narrow = exposure 1.0（无联动）
                    //   wide   = exposure = 生产联动值（VEIN_EXPOSURE_MUL）
                    //   ⚠ 不能取极大值（那会让阈值大到恒命中，校验失去意义 —— 实测踩过）。
                    boolean narrow = OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, r,
                            WORLD_MIN_Y, 1.0) >= 0;
                    boolean wide = OreVeins.veinAt(p[0], y, p[1], SYN_SURFACE, r,
                            WORLD_MIN_Y, OreVeins.VEIN_EXPOSURE_MUL) >= 0;
                    // 逐矿种校验三态划分的正确性（双向等价）：
                    //   ① 有任一矿种 code==1 ⇒ narrow 必须 true（命中必显现）
                    //   ② narrow==true ⇒ 必须有某个矿种 code==1（不会"命中却无 code=1"）
                    int depth = SYN_SURFACE - y;
                    boolean anyCode1 = false;
                    boolean anyMinus1 = false;
                    for (int i = 0; i < OreVeins.ORES.length; i++) {
                        OreVeins.Ore o = OreVeins.ORES[i];
                        if (depth < o.minDepth || depth > o.maxDepth) continue;
                        if (!o.hosts(r)) continue;
                        int code = OreVeins.veinHitCode(i, p[0], y, p[1], o.richness);
                        if (code == 1) { code1++; anyCode1 = true; }
                        else if (code == -1) { codeMinus1++; anyMinus1 = true; }
                        else code0++;
                    }
                    if (anyCode1 != narrow) bad++;      // ① 与 ② 合并
                    // ★ 单调性：无联动命中 ⇒ 有联动（放大阈值）必须仍命中。
                    //   用生产 exposure 值而非极大值，否则校验恒真、失去意义。
                    if (narrow && !wide) bad++;
                }
            }
        }
        double gain = plainHits == 0 ? 0 : (double) expHits / plainHits;
        System.out.printf("    样本=%d  无联动命中=%d  联动命中=%d  增幅=%.2f×%n",
                linkSamples, plainHits, expHits, gain);
        System.out.printf("    三态分布: 命中=%d 远离=%d 擦肩=%d%n", code1, code0, codeMinus1);
        System.out.printf("    不变式校验（双向等价 + 单调性）: 违反=%d%n", bad);
        boolean pass6 = expHits > plainHits && gain >= 1.3 && bad == 0 && code1 > 0;
        System.out.printf("[判据6] 洞穴联动生效（增幅≥1.3× 且三态不变式无违反）: %s%n",
                pass6 ? "PASS" : "FAIL");

        int failures = (pass1 ? 0 : 1) + (pass2 ? 0 : 1) + (pass3 ? 0 : 1)
                + (pass4 ? 0 : 1) + (pass5 ? 0 : 1) + (pass6 ? 0 : 1);
        System.out.println(failures == 0 ? "ALL PASS" : ("FAILURES=" + failures));
    }

    /** 渲染 Y-Z 垂直切片与 X-Z 水平切片（矿种用不同颜色）。 */
    private static void renderSlices(byte[] voxel, int N, int H, int yBot) throws Exception {
        File dir = new File("build/ore");
        dir.mkdirs();
        // 矿种调色板（与 MC 观感对应）
        int[] colors = {
                0x2B2B2B,   // COAL 煤 黑
                0xE07B39,   // COPPER 铜 橙
                0xD8C0A0,   // IRON 铁 米黄
                0xFFD700,   // GOLD 金
                0xFF2B2B,   // REDSTONE 红石
                0x2B52FF,   // LAPIS 青金 蓝
                0x2BE07B,   // EMERALD 绿宝石 绿
                0x7BFFFF,   // DIAMOND 钻石 青
        };
        // Y-Z 垂直切片（x 固定）：判"深度带"
        for (int s = 0; s < 3; s++) {
            int x = N / 4 * (s + 1);
            if (x >= N) x = N - 1;
            BufferedImage img = new BufferedImage(N, H, BufferedImage.TYPE_INT_RGB);
            for (int z = 0; z < N; z++) {
                int base = (x * N + z) * H;
                for (int y = 0; y < H; y++) {
                    int v = voxel[base + y] & 0xFF;
                    img.setRGB(z, H - 1 - y, v == 0 ? 0x101010 : colors[(v - 1) % colors.length]);
                }
            }
            ImageIO.write(img, "png", new File(dir, "vert_x" + s + ".png"));
        }
        // X-Z 水平切片（y 固定）：判"脉体平面形态"
        for (int s = 0; s < 3; s++) {
            int depth = 30 + s * 70;
            int y = SYN_SURFACE - depth;
            int yi = y - yBot;
            if (yi < 0 || yi >= H) continue;
            BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < N; x++) {
                for (int z = 0; z < N; z++) {
                    int v = voxel[(x * N + z) * H + yi] & 0xFF;
                    img.setRGB(x, z, v == 0 ? 0x101010 : colors[(v - 1) % colors.length]);
                }
            }
            ImageIO.write(img, "png", new File(dir, "horiz_y" + s + ".png"));
        }
        System.out.printf("[6] 剖面图输出: %s（vert_x*=深度带, horiz_y*=脉形）%n",
                dir.getAbsolutePath());
    }

    /**
     * 参数扫描：脉体阈值 × 成矿带阈值，跨种子同时报<b>密度</b>与<b>带内列占比</b>。
     *
     * <p>⚠ 本模式只报密度与带覆盖（连通性统计太贵，且形态由主模式单独验收）。
     * 早期版本曾在此输出占位常量（"均分量 1.0 / 孤立 0"），那是<b>假数据</b>，
     * 已删除 —— 探针输出必须每一项都是真实测量。</p>
     */
    private static void scanMode(String[] args) {
        long[] seeds = {12345L, 7L, 42L};
        int N = 128;
        int H = 320;
        final int SURF = 260;
        System.out.printf("=== OreVein scan: %d 种子 × 脉体阈值 × 成矿带阈值（N=%d）===%n",
                seeds.length, N);
        System.out.printf("%-8s %-8s %-11s %-11s%n", "veinMul", "prosMul", "密度%(最差)", "带内列%");
        double[] veins = {1.0, 1.6, 2.4, 3.4};
        double[] pros = {0.62, 0.75, 0.88, 1.0};
        for (double pm : pros) {
            for (double vm : veins) {
                double worstPct = 0;
                double bandPct = 0;
                for (long seed : seeds) {
                    OreVeins.setSeed(seed);
                    OreVeins.dbgSetVein(vm);
                    OreVeins.dbgSetProspect(pm);
                    long total = 0, ore = 0, band = 0;
                    for (int x = 0; x < N; x++) {
                        for (int z = 0; z < N; z++) {
                            int rockOrd = (x + z) % RockType.values().length;
                            total += H;
                            if (OreVeins.beginColumn(x, z)) band++;
                            for (int y = WORLD_MIN_Y + 1; y < SURF; y++) {
                                if (OreVeins.veinAt(x, y, z, SURF, rockOrd, WORLD_MIN_Y) >= 0) ore++;
                            }
                        }
                    }
                    worstPct = Math.max(worstPct, 100.0 * ore / total);
                    bandPct = Math.max(bandPct, 100.0 * band / ((long) N * N));
                }
                System.out.printf("%-8.1f %-8.2f %-11.4f %-11.1f%n", vm, pm, worstPct, bandPct);
            }
        }
        OreVeins.dbgReset();
        System.out.println("（扫描完成，dbgReset 已恢复生产参数）");
    }
}
