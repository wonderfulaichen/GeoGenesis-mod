package com.geogenesis.worldgen.ore;

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
 *   <li>洞穴的 {@code geo_per_chunk_ms=2.48}（{@code runCavePerfProbe}）；</li>
 *   <li>地形全链路 chunk 热态约 <b>几十 ms</b>（{@code extract=0ms, hydro≈30ms}）。</li>
 * </ul>
 * <p>⚠ 诚实说明：本探针测的是<b>纯计算</b>成本（噪声 + 整数门控），<b>不含</b>
 * MC 的 {@code setBlockState} 开销 —— 后者无论是否覆盖矿脉都要写一遍
 * （矿脉只是<b>改写了要写的方块类型</b>，不改变写入次数）。</p>
 *
 * <pre>{@code gradlew runOrePerfProbe [-PprobeArgs="seed chunks"]}</pre>
 */
public final class OrePerfProbe {

    private OrePerfProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;
    /** 世界最高 Y（与 {@code GeoGenesisGenerator.WORLD_MAX_Y} 一致）。 */
    private static final int WORLD_MAX_Y = 320;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 64;

        System.out.printf("=== OrePerfProbe seed=%d chunks=%d ===%n", seed, chunks);
        OreVeins.setSeed(seed);

        int rocks = RockType.values().length;

        // ---------- 1) 逐体素总成本（生产模式：含列级门控 + 深度剪枝）----------
        //   地表高度按 chunks 铺开（每个模拟 chunk 16×16 列），地表高取变化值
        //   以覆盖不同的深度窗口对齐情况。
        long calls = 0, hits = 0, prospective = 0;
        long[] perOre = new long[OreVeins.ORES.length];
        long t0 = System.nanoTime();
        int side = (int) Math.ceil(Math.sqrt(chunks)) * 16;
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
                if (zone) prospective++;
                for (int y = WORLD_MIN_Y + 1; y < WORLD_MAX_Y; y++) {
                    calls++;
                    // ★ 与生产同构：先列级门控，再深度剪枝，最后逐体素判定
                    //   （生产里前两层由外层的 if 承担，这里显式复现以保证口径一致）
                    if (!zone) break;                       // 生产里根本不会进这个 Y 循环
                    if (!OreVeins.depthInRange(y, surface)) continue;
                    int o = OreVeins.veinAt(cx, y, cz, surface, rockOrd, WORLD_MIN_Y);
                    if (o >= 0) { hits++; perOre[o]++; }
                }
            }
        }
        long el = System.nanoTime() - t0;

        int chunkCount = (int) Math.ceil(chunks);
        double us = el / 1000.0;
        double perChunkMs = us / 1000.0 / (double) (side * side) * 256.0;

        System.out.printf("[1] 逐体素（含列级门控+深度剪枝）:%n");
        System.out.printf("    列=%d（带内=%d，%.1f%%）  体素调用=%d  命中=%d%n",
                side * side, prospective, 100.0 * prospective / (side * side), calls, hits);
        System.out.printf("    总耗时=%.1fms  单列=%.3f us  单块=%.4f us%n",
                us / 1000.0, us / (side * side), us / calls);
        System.out.printf("    ★ 折算每 chunk（256 列）: %.3f ms/chunk%n", perChunkMs);

        // ---------- 2) 仅列级门控成本（不含逐体素）----------
        long t1 = System.nanoTime();
        int zoneHits = 0;
        for (int i = 0; i < side * side; i++) {
            if (OreVeins.columnProspective(i * 3 - 4000, i * 7 - 4000)) zoneHits++;
        }
        long el1 = System.nanoTime() - t1;
        System.out.printf("[2] 仅列级门控: 列=%d 带内=%d  总=%.2fms  单列=%.4f us%n",
                side * side, zoneHits, (el1 / 1000.0) / 1000.0,
                el1 / 1000.0 / (side * side));

        // ---------- 3) 最坏情形：整列都在带内且深度窗口全开 ----------
        //   用于给出成本上界（真实世界里带内列约 27%）。
        long t2 = System.nanoTime();
        long worstCalls = 0;
        for (int i = 0; i < 2000; i++) {
            int x = i * 5 - 3000, z = i * 11 - 3000;
            int surface = 120;
            int rockOrd = i % rocks;
            for (int y = surface - OreVeins.MAX_DEPTH; y <= surface - OreVeins.MIN_DEPTH; y++) {
                worstCalls++;
                OreVeins.veinAt(x, y, z, surface, rockOrd, WORLD_MIN_Y);
            }
        }
        long el2 = System.nanoTime() - t2;
        System.out.printf("[3] 最坏上界（深度窗口全开）: 体素=%d 总=%.2fms 单块=%.4f us%n",
                worstCalls, (el2 / 1000.0) / 1000.0,
                el2 / 1000.0 / Math.max(1, worstCalls));
        double worstPerChunk = el2 / 1000.0 / 1000.0 / 2000.0 * 256.0;
        System.out.printf("    ★ 折算每 chunk（若 256 列全在带内）: %.3f ms/chunk%n", worstPerChunk);

        // ---------- 判据 ----------
        boolean pass1 = perChunkMs <= 2.0;
        System.out.printf("[判据1] 典型成本 ≤ 2.0 ms/chunk（对照洞穴 2.48）: %s（实测 %.3f）%n",
                pass1 ? "PASS" : "FAIL", perChunkMs);
        boolean pass2 = worstPerChunk <= 2.5;
        System.out.printf("[判据2] 最坏成本 ≤ 2.5 ms/chunk（全部列都在成矿带内）: %s（实测 %.3f）%n",
                pass2 ? "PASS" : "FAIL", worstPerChunk);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perOre.length; i++) sb.append(perOre[i]).append(' ');
        System.out.println("[4] 命中分布（按矿种序号，仅供性能测量的路径覆盖参考）: " + sb);
        System.out.println("    ⚠ 本探针窗口小 + 地表高/岩性按模运算循环 ⇒ 某些矿的深度带"
                + "未必被采到（实测 seed=12345 只有 3 种有命中）。");
        System.out.println("      矿种齐全性【不以此为准】—— 见 runOreVeinProbe 判据2（ND=512 大窗口）。");
        System.out.println((pass1 && pass2) ? "ALL PASS" : "FAILURES");
    }
}
