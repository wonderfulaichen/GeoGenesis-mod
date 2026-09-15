package com.geogenesis.worldgen.ore;

import com.geogenesis.worldgen.cave.CaveShape;
import com.geogenesis.worldgen.terrain.RockType;

/**
 * 矿脉<b>性能</b>诊断探针（★ 2026-09-15）。
 *
 * <h3>为何单独测（用户曾质问"性能没测试吗？"）</h3>
 * <p>矿脉接在 {@code GeoGenesisGenerator.fillTerrainColumn} 的<b>逐体素</b>层序查找里
 * —— 现有性能探针（{@code ChunkTimeProbe} / {@code AbChunkProbe}）都只测到
 * <b>地形层</b>（{@code sample/extract/hydro}），<b>不覆盖方块铺设</b>，
 * 故矿脉的成本<b>不被任何既有探针覆盖</b>。本探针补上。</p>
 *
 * <h3>测量口径</h3>
 * <p>按<b>生产调用模式</b>逐体素调用（含列级成矿带门控 + 深度窗口剪枝），
 * 统计"每 chunk 的等价值"：地板面积 16×16 列、地表下 ~380 格 Y 范围。</p>
 * <p>对照基准：</p>
 * <ul>
 *   <li>洞穴的 {@code geo_per_chunk_ms}（{@code runCavePerfProbe}，实测 2.5~3.1）；</li>
 *   <li>地形全链路 chunk 热态约 <b>几十 ms</b>（{@code extract=0ms, hydro≈30ms}）。</li>
 * </ul>
 * <p>⚠ 诚实说明：本探针测的是<b>纯计算</b>成本（噪声 + 整数门控），<b>不含</b>
 * MC 的 {@code setBlockState} 开销 —— 后者无论是否覆盖矿脉都要写一遍
 * （矿脉只是<b>改写了要写的方块类型</b>，不改变写入次数）。</p>
 *
 * <h3>★ 稳定化改造（2026-09-15，务必保留）</h3>
 *
 * <p><b>问题</b>：初版每个场景<b>只测一次</b>。实测同机连跑两次，
 * {@code [3] 最坏} 得到 <b>2.454 与 2.635 ms/chunk</b> —— 判据2 阈值恰为 2.5
 * ⇒ <b>在 PASS/FAIL 之间来回翻转</b>。这种"抖动判据"比没有判据更坏：
 * 它会随机报假警报，把排查引向不存在的退化（本轮已实际发生一次）。</p>
 *
 * <p><b>改造</b>：</p>
 * <ol>
 *   <li><b>best-of-N 取最小</b>（{@link #REPEAT}=3，另有 1 次预热丢弃）。
 *       GC / JIT 重编译 / CPU 降频只会让某次<b>更慢</b>，取最小即取"最接近真实
 *       算力"的那次，消除单侧抖动。</li>
 *   <li><b>同时报告单块 ns</b>。{@code ms/chunk} 的分母是<b>列数</b>
 *       （{@code 总耗时/列数×256}），故重量级的 <b>深度带宽度一变，该值会等比漂移</b>，
 *       不适合当"每次调用成本"的跨版本比较量；ns/块 才是。</li>
 * </ol>
 *
 * <h3>★ 跨版本对照实验 + "假退化"的完整取证（方法论案例，务必勿重犯）</h3>
 *
 * <p><b>起因</b>：本探针（改造前，单次测量）连续两次运行给出
 * {@code [判据2]} <b>FAIL 2.635 / PASS 2.454</b> —— 阈值恰为 2.5，
 * 判据在 PASS/FAIL 间翻转。这触发了"矿脉性能是否退化"的排查。</p>
 *
 * <p><b>第一步：同机 A/B</b>（把工作区切到 {@code ceedb32} = 矿脉首版，与
 * {@code HEAD} 各跑一次，排除"机器变慢"的干扰）。单次测量结果（单块 ns）：</p>
 * <table border="1">
 *   <tr><th>场景</th><th>ceedb32（旧）</th><th>HEAD（新）</th><th>表面结论</th></tr>
 *   <tr><td>[1] 典型</td><td>39.8</td><td>40.2</td><td>不变</td></tr>
 *   <tr><td>[3] 最坏</td><td>30.4</td><td>44.6</td><td><b>+47%（像是真退化）</b></td></tr>
 * </table>
 *
 * <p>两版 {@code veinAt} 函数体<b>逐行相同</b>（唯一差：{@code o.richness * exposure}
 * 与多一层 6→7 参委托，量级远不足以解释 47%）。此时最容易被误导成"去改代码"。</p>
 *
 * <p><b>第二步：改成 best-of-N 后重测</b>（{@link #REPEAT}=3 取最小）：</p>
 * <table border="1">
 *   <tr><th>场景</th><th>单次测量</th><th>best-of-3</th></tr>
 *   <tr><td>[1] 单块</td><td>40.2 ns</td><td><b>21.6~21.8 ns</b></td></tr>
 *   <tr><td>[3] 单块</td><td>44.6 / 47.9 ns</td><td><b>18.1~19.0 ns</b></td></tr>
 *   <tr><td>[3] ms/chunk</td><td>2.454 / 2.635（抖）</td><td><b>0.999 / 1.043 / 1.029（稳）</b></td></tr>
 * </table>
 *
 * <p><b>结论</b>：那个 <b>+47% 是测量假象</b>，不存在退化 ——</p>
 * <ol>
 *   <li>单次测量被<b>预热/JIT 噪声放大了一倍以上</b>（旧 30.4 与真值 18 差 1.7×，
 *       新 44.6 与真值 18 差 2.5×）；</li>
 *   <li>best-of-3 下 {@code [3]} 稳定值 <b>1.0 ms/chunk</b>，甚至<b>低于</b>
 *       {@code ceedb32} 当年记录的历史基线 1.35~1.48（那条也是单次测量，同被高估）；</li>
 *   <li>新版单次测量偏得更厉害，因为 {@code [1b]}（洞穴联动，{@code d058cf1} 才加入）
 *       跑在 {@code [3]} <b>之前</b>，其重量级调用与 {@code [3]} 共享 {@code veinHit}
 *       内联点 ⇒ 加剧 JIT 剖面污染。</li>
 * </ol>
 *
 * <p><b>方法论教训</b>（本项目已被"推理代替取证"坑过多次，此为性能侧同一类）：</p>
 * <ul>
 *   <li>微基准<b>绝不</b>用单次测量下结论，也必须<b>取最小、不取平均</b>
 *       （抖动是单侧的：GC/降频只会更慢，平均值会把离群值混进来掩盖真实算力）；</li>
 *   <li>性能判据的阈值<b>不能贴着测量值设</b>，否则会随机翻转、把排查引向
 *       不存在的退化（本轮真实发生过一次）；</li>
 *   <li>跨版本比较要同时看 <b>ns/块</b>（调用成本）与 <b>ms/chunk</b>
 *       （预算占用）—— 后者的分母是<b>列数</b>，深度带一变就等比漂移。</li>
 * </ul>
 *
 * <pre>{@code gradlew runOrePerfProbe [-PprobeArgs="seed chunks"]}</pre>
 */
public final class OrePerfProbe {

    private OrePerfProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;
    /** 世界最高 Y（与 {@code GeoGenesisGenerator.WORLD_MAX_Y} 一致）。 */
    private static final int WORLD_MAX_Y = 320;

    /**
     * 每个场景重复测量次数（取最小；另有 1 次预热不计）。
     *
     * <p>3 次已足以消除实测到的 2.454/2.635 级抖动；再增大只会拖慢探针。</p>
     */
    private static final int REPEAT = 3;

    /** 计数器（每次 {@code run} 前 {@link #reset} ⇒ 末次结果即最终结果）。 */
    private static final class Acc {
        long calls, hits, prospective;
        final long[] perOre = new long[OreVeins.ORES.length];
        void reset() {
            calls = hits = prospective = 0;
            java.util.Arrays.fill(perOre, 0);
        }
    }

    /**
     * 跑 {@code repeat} 次取<b>最小</b>耗时（ns）。第 1 次作为预热丢弃。
     *
     * <p>取最小而非平均：抖动是<b>单侧</b>的（只会更慢），平均会把
     * GC/降频的离群值混进来，反而掩盖真实算力。</p>
     */
    private static long bestOf(int repeat, Runnable body) {
        body.run();                                   // 预热（JIT 编译、缓存填充）
        long best = Long.MAX_VALUE;
        for (int r = 0; r < repeat; r++) {
            long t0 = System.nanoTime();
            body.run();
            long el = System.nanoTime() - t0;
            if (el < best) best = el;
        }
        return best;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 64;

        System.out.printf("=== OrePerfProbe seed=%d chunks=%d repeat=%d(取最小) ===%n",
                seed, chunks, REPEAT);
        OreVeins.setSeed(seed);

        int rocks = RockType.values().length;
        int side = (int) Math.ceil(Math.sqrt(chunks)) * 16;

        // ---------- 1) 逐体素总成本（生产模式：含列级门控 + 深度剪枝）----------
        //   地表高度按 chunks 铺开（每个模拟 chunk 16×16 列），地表高取变化值
        //   以覆盖不同的深度窗口对齐情况。
        Acc a1 = new Acc();
        long best1 = bestOf(REPEAT, () -> {
            a1.reset();
            for (int cx = 0; cx < side; cx++) {
                for (int cz = 0; cz < side; cz++) {
                    // 地表高在 [80, 260] 波动（模拟山地/平原），保证深度窗口有变化
                    int surface = 80 + ((cx * 31 + cz * 17) % 181);
                    // ★ 岩性分区边长 32（而非 128）：探针窗口只有 side×side（默认 128×128），
                    //   若分区取 128 则 cx/128 恒为 0 ⇒ 全局只有一种岩性 ⇒ 命中分布里
                    //   煤/铜等（宿主岩为砂岩/玄武岩）恒为 0，看起来像"死矿种"（实测踩过）。
                    //   32 ⇒ 128 宽得 4×4=16 个分区，足以覆盖全部 8 种岩性。
                    int rockOrd = ((cx / 32) + (cz / 32) * 4) % rocks;
                    boolean zone = OreVeins.beginColumn(cx, cz);
                    if (zone) a1.prospective++;
                    for (int y = WORLD_MIN_Y + 1; y < WORLD_MAX_Y; y++) {
                        a1.calls++;
                        // ★ 与生产同构：先列级门控，再深度剪枝，最后逐体素判定
                        //   （生产里前两层由外层的 if 承担，这里显式复现以保证口径一致）
                        if (!zone) break;                       // 生产里根本不会进这个 Y 循环
                        if (!OreVeins.depthInRange(y, surface)) continue;
                        int o = OreVeins.veinAt(cx, y, cz, surface, rockOrd, WORLD_MIN_Y);
                        if (o >= 0) { a1.hits++; a1.perOre[o]++; }
                    }
                }
            }
        });

        double us = best1 / 1000.0;
        double perChunkMs = us / 1000.0 / (double) (side * side) * 256.0;
        double nsPerVoxel = best1 / (double) Math.max(1, a1.calls);

        System.out.printf("[1] 逐体素（含列级门控+深度剪枝）:%n");
        System.out.printf("    列=%d（带内=%d，%.1f%%）  体素调用=%d  命中=%d%n",
                side * side, a1.prospective, 100.0 * a1.prospective / (side * side),
                a1.calls, a1.hits);
        System.out.printf("    best=%.1fms  单列=%.3f us  单块=%.1f ns%n",
                best1 / 1_000_000.0, us / (side * side), nsPerVoxel);
        System.out.printf("    ★ 折算每 chunk（256 列）: %.3f ms/chunk%n", perChunkMs);

        // ---------- 1b) ★ 含【洞穴联动】的成本（真实生产口径）----------
        //   ⚠ 上面 [1] 调的是**无 exposure** 版本 ⇒ 联动的开销（每体素 6 次
        //     CaveShape.isCave）**没被测量**。这正是本项目被批评过的"性能探针
        //     没覆盖到实际生产路径"问题，故必须单独测。
        //   口径：只用【紧邻洞穴】的最坏情况（exposure>1 且每次都做 6 邻判定）
        //     ⇒ 给出联动成本的上界。
        CaveShape.setSeed(seed);
        Acc a1b = new Acc();
        long best1b = bestOf(REPEAT, () -> {
            a1b.reset();
            for (int cx = 0; cx < side; cx++) {
                for (int cz = 0; cz < side; cz++) {
                    int surface = 80 + ((cx * 31 + cz * 17) % 181);
                    int rockOrd = ((cx / 32) + (cz / 32) * 4) % rocks;
                    boolean zone = OreVeins.beginColumn(cx, cz);
                    if (!zone) continue;
                    double litho = CaveShape.lithoFactor(rockOrd);
                    for (int y = WORLD_MIN_Y + 1; y < WORLD_MAX_Y; y++) {
                        if (!OreVeins.depthInRange(y, surface)) continue;
                        a1b.calls++;
                        // 与生产同构：两阶段判定（只有擦肩体素才做 6 邻洞穴预测）
                        int o = OreVeins.veinAtLinked(cx, y, cz, surface, rockOrd,
                                WORLD_MIN_Y, litho);
                        if (o >= 0) a1b.hits++;
                    }
                }
            }
        });
        double linkPerChunkMs = best1b / 1000.0 / 1000.0 / (side * side) * 256.0;
        System.out.printf("[1b] 含洞穴联动（6 邻预测）: 体素=%d 命中=%d best=%.1fms"
                        + " 单块=%.1f ns  ★ 折算每 chunk: %.3f ms/chunk（联动增量 %+.3f）%n",
                a1b.calls, a1b.hits, best1b / 1_000_000.0,
                best1b / (double) Math.max(1, a1b.calls),
                linkPerChunkMs, linkPerChunkMs - perChunkMs);

        // ---------- 2) 仅列级门控成本（不含逐体素）----------
        long best2 = bestOf(REPEAT, () -> {
            for (int i = 0; i < side * side; i++) {
                OreVeins.columnProspective(i * 3 - 4000, i * 7 - 4000);
            }
        });
        System.out.printf("[2] 仅列级门控: 列=%d  best=%.2fms  单列=%.1f ns%n",
                side * side, best2 / 1_000_000.0,
                best2 / (double) Math.max(1, side * side));

        // ---------- 3) 最坏情形：整列都在带内且深度窗口全开 ----------
        //   用于给出成本上界（真实世界里带内列约 27%）。
        final int[] worstCalls = {0};
        long best3 = bestOf(REPEAT, () -> {
            worstCalls[0] = 0;
            for (int i = 0; i < 2000; i++) {
                int x = i * 5 - 3000, z = i * 11 - 3000;
                int surface = 120;
                int rockOrd = i % rocks;
                for (int y = surface - OreVeins.MAX_DEPTH; y <= surface - OreVeins.MIN_DEPTH; y++) {
                    worstCalls[0]++;
                    OreVeins.veinAt(x, y, z, surface, rockOrd, WORLD_MIN_Y);
                }
            }
        });
        System.out.printf("[3] 最坏上界（深度窗口全开）: 体素=%d best=%.2fms 单块=%.1f ns%n",
                worstCalls[0], best3 / 1_000_000.0,
                best3 / (double) Math.max(1, worstCalls[0]));
        double worstPerChunk = best3 / 1000.0 / 1000.0 / 2000.0 * 256.0;
        System.out.printf("    ★ 折算每 chunk（若 256 列全在带内）: %.3f ms/chunk%n", worstPerChunk);

        // ---------- 判据 ----------
        boolean pass1 = perChunkMs <= 2.0;
        System.out.printf("[判据1] 典型成本 ≤ 2.0 ms/chunk（对照洞穴）: %s（实测 %.3f）%n",
                pass1 ? "PASS" : "FAIL", perChunkMs);
        boolean pass2 = worstPerChunk <= 2.5;
        System.out.printf("[判据2] 最坏成本 ≤ 2.5 ms/chunk（全部列都在成矿带内）: %s（实测 %.3f）%n",
                pass2 ? "PASS" : "FAIL", worstPerChunk);
        boolean pass3 = linkPerChunkMs <= 2.5;
        System.out.printf("[判据3] 含洞穴联动 ≤ 2.5 ms/chunk（联动增量应可忽略）: %s（实测 %.3f）%n",
                pass3 ? "PASS" : "FAIL", linkPerChunkMs);

        StringBuilder sb = new StringBuilder();
        for (long v : a1.perOre) sb.append(v).append(' ');
        System.out.println("[4] 命中分布（按矿种序号，仅供性能测量的路径覆盖参考）: " + sb);
        System.out.println("    ⚠ 本探针窗口小 + 地表高/岩性按模运算循环 ⇒ 某些矿的深度带"
                + "未必被采到（实测 seed=12345 只有 3 种有命中）。");
        System.out.println("      矿种齐全性【不以此为准】—— 见 runOreVeinProbe 判据2（ND=512 大窗口）。");
        System.out.println((pass1 && pass2 && pass3) ? "ALL PASS" : "FAILURES");
    }
}
