package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * {@link WorldGenProfiler} 自检（★ 2026-09-18）。
 *
 * <h2>为什么需要</h2>
 * <p>本项目已多次踩"功能写完没验证"的坑。profiler 是<b>诊断工具</b>，
 * 它自己出错会更危险 —— 你会照着<b>错误的数字</b>去改代码。
 * 故必须自检三件事：</p>
 * <ol>
 *   <li><b>关闭时零记录</b>：{@code enabled=false} ⇒ 所有计数器恒为 0
 *       （证明"默认关闭不影响任何东西"）；</li>
 *   <li><b>开启后确有记录</b>：跑若干 chunk ⇒ 阶段计数 &gt; 0、总耗时 &gt; 0
 *       （证明插桩点真的被调到，而不是"日志静默没输出"）；</li>
 *   <li><b>占比自洽</b>：各阶段占比之和 ≤ 100%（证明没有重复计时/串阶段）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runProfilerSelfCheckProbe}</pre>
 */
public final class ProfilerSelfCheckProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 24;

        System.out.printf("=== ProfilerSelfCheckProbe seed=%d chunks=%d ===%n%n", seed, chunks);

        // ---- [1] 关闭态：必须零记录 ----
        WorldGenProfiler.configure(false, 0);
        WorldGenProfiler.reset();
        runChunks(seed, chunks);
        boolean offClean = WorldGenProfiler.debugTotalRecords() == 0;
        System.out.printf("[1] 关闭态记录数 = %d（须为 0）⇒ %s%n%n",
                WorldGenProfiler.debugTotalRecords(), offClean ? "PASS" : "FAIL");

        // ---- [2] 开启态：必须有记录 ----
        WorldGenProfiler.configure(true, 0);
        runChunks(seed, chunks);
        long recs = WorldGenProfiler.debugTotalRecords();
        // ⚠ 口径说明（自检首轮就抓到了这点）：本探针走 getChunkCells ⇒ 只触发
        //   SAMPLE / EXTRACT / HYDRO 三个阶段（它们在 GeoGenesisTerrain.generateChunk 内）。
        //   而【单块总耗时】(endChunk) 与 PLACE / DECORATE / CARVE / ENSURE
        //   由 GeoGenesisGenerator.fillFromNoise 一侧记账 —— 本探针【不经过】该方法
        //   （它需要 MC 的 ChunkAccess / RandomState，离线起不来）。
        //   ⇒ 这里只断言"阶段记账生效"；单块记账由实机日志验证。
        boolean onWorks = recs > 0;
        System.out.printf("[2] 开启态：阶段记录 = %d（须 > 0）⇒ %s%n", recs, onWorks ? "PASS" : "FAIL");
        System.out.println("    ⚠ 单块记录 = " + WorldGenProfiler.debugChunkRecords()
                + "（本探针走 getChunkCells，不经过 fillFromNoise ⇒ 预期为 0；"
                + "实机由 fillFromNoise 记账）");
        System.out.println();

        // ---- [2b] 验证"部分阶段确实被记到"（防插桩点漏接）----
        System.out.println("[2b] 各阶段记录数（本探针路径下，仅 SAMPLE/EXTRACT/HYDRO 应 > 0）:");
        boolean[] seen = WorldGenProfiler.debugStageHasRecords();
        StringBuilder sb = new StringBuilder("    ");
        for (int i = 0; i < seen.length; i++) {
            sb.append(WorldGenProfiler.debugStageName(i)).append('=')
              .append(seen[i] ? "有" : "无").append("  ");
        }
        System.out.println(sb);
        System.out.println("    （PLACE/DECORATE/CARVE/ENSURE/MOBS 为『无』属正常：不在本探针路径上）");
        System.out.println();

        // ---- [3] 汇总输出（人工核对占比是否自洽）----
        System.out.println("[3] 汇总输出（下方 [WGP] 行；请核对占比之和 ≤ 100%）:");
        WorldGenProfiler.report("自检");

        boolean pass = offClean && onWorks;
        System.out.printf("%n总判定: %s%n", pass ? "ALL PASS" : "FAILURES");
        System.exit(pass ? 0 : 1);
    }

    /** 生成若干 chunk（走生产入口 getChunkCells，触发 SAMPLE/EXTRACT/HYDRO 插桩）。 */
    private static void runChunks(long seed, int chunks) {
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        int side = (int) Math.ceil(Math.sqrt(chunks));
        for (int cx = 0; cx < side; cx++) {
            for (int cz = 0; cz < side; cz++) {
                gt.getChunkCells(cx, cz);
            }
        }
    }

    private ProfilerSelfCheckProbe() { }
}
