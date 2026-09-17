package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.terrain.Cell;

/**
 * 【区块加载性能】基准（2026-09-17）—— 用户反馈"区块加载变慢了不少"的量化基线。
 *
 * <h2>为什么现有探针测不出</h2>
 * <p>{@code WaterPhysicsProbe} / {@code ErosionSeamProbe} 只铺 24×24 chunk 的窗口 ⇒
 * <b>工作集装得下全部侵蚀 tile</b>（tile=48wu=96 块；24 chunk=384 块 ≈ 4×4 tile），
 * 既无缓存驱逐、也无"移动式冷启动" ⇒ 测不出真实负载下的回归。</p>
 *
 * <h2>本探针做什么</h2>
 * <ol>
 *   <li>按【蛇形/螺旋式移动】扫过 {@code span}×{@code span} 个 chunk（默认 64×64 = 4096 chunk，
 *       跨 ≈43×43 个侵蚀 tile）⇒ 制造真实的工作集与驱逐；</li>
 *   <li>统计总耗时、每 chunk 均值/分位；</li>
 *   <li>统计【侵蚀 tile 生成次数】——直接数日志行 {@code [PERF] erosion tile (x,z) took}；
 *       与"被触达的不同 tile 数"对比：<b>次数显著更多 ⇒ 存在重复生成</b>（确定性问题修复的常见副作用）。</li>
 * </ol>
 *
 * <p>日志计数用 {@code System.setOut} 包装 stdout 实现（探针专用，不改生产代码）。</p>
 *
 * <pre>{@code gradlew runChunkLoadPerfProbe [-PprobeArgs="seed spanChunks"]}</pre>
 */
public final class ChunkLoadPerfProbe {

    /** tile 几何（与生产一致）。 */
    private static final int TILE = 48;

    private ChunkLoadPerfProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int span = args.length > 1 ? Integer.parseInt(args[1]) : 64;

        // 包装 stdout：数 "[PERF] erosion tile (" 出现次数（= tile 生成次数）
        java.io.PrintStream real = System.out;
        final int[] genCount = {0};
        final long[] hydroSum = {0};
        final java.util.List<Integer> hydroList = new java.util.ArrayList<>();
        final long[] tileGenMs = {0};
        System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            private final StringBuilder buf = new StringBuilder();
            @Override public void write(int b) {
                if (b == '\n') {
                    String s = buf.toString();
                    buf.setLength(0);
                    if (s.contains("[PERF] erosion tile (") && s.contains(" took ")) {
                        genCount[0]++;
                        int i = s.indexOf(" took "), j = s.indexOf("ms", i);
                        if (j > i) tileGenMs[0] += Long.parseLong(s.substring(i + 6, j).trim());
                    }
                    int k = s.indexOf("hydro=");
                    if (k >= 0) {
                        int e = s.indexOf("ms", k);
                        if (e > k) {
                            int v = Integer.parseInt(s.substring(k + 6, e).trim());
                            hydroSum[0] += v;
                            hydroList.add(v);
                        }
                    }
                    real.println(s);
                } else {
                    buf.append((char) b);
                }
            }
        }, true));

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        // 预热 JIT（小范围，不计入统计；同时也让 tile 生成路径热起来）
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) gt.getChunkCells(cx, cz);
        }
        gen.tileCacheStats().reset();
        gen.seed(seed);   // 重置 tile 缓存（seed() 内 clear）→ 干净基线

        System.out.printf("=== ChunkLoadPerfProbe seed=%d span=%d chunk (%d×%d) ===%n",
                seed, span, span, span);
        long t0 = System.nanoTime();
        int chunks = 0;
        int lastChunkMs = -1;
        long worstChunkNs = 0;
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                long c0 = System.nanoTime();
                gt.getChunkCells(cx, cz);
                long d = System.nanoTime() - c0;
                worstChunkNs = Math.max(worstChunkNs, d);
                lastChunkMs = (int) (d / 1_000_000);
                chunks++;
            }
        }
        long total = System.nanoTime() - t0;
        int gens = genCount[0];
        long hits = gen.tileCacheStats().hits();
        long misses = gen.tileCacheStats().misses();
        long evict = gen.tileCacheStats().evictions();
        int cached = gen.erosionTileCacheSize();

        // 被触达的【不同】tile 数：由 chunk 范围换算（每 chunk=32 块=2/3 tile @hs=2）
        double hs = tp.horizontalScale();
        int tilesX = (int) Math.ceil((span * 16 * 1.0 / hs) / TILE) + 2;
        int tilesZ = tilesX;
        int distinctTiles = tilesX * tilesZ;

        System.out.printf("总耗时 = %.1f ms / %d chunk = %.2f ms/chunk%n",
                total / 1e6, chunks, total / 1e6 / chunks);
        System.out.printf("最慢单 chunk = %.1f ms（末 chunk = %d ms）%n",
                worstChunkNs / 1e6, lastChunkMs);
        System.out.printf("侵蚀 tile：生成 %d 次（tile 生成总耗时 %d ms）/ 触达不同 tile ≈ %d 个 ⇒ 重复生成率 = %.2f×%n",
                gens, tileGenMs[0], distinctTiles, gens / (double) distinctTiles);
        System.out.printf("缓存统计：hit=%d miss=%d evict=%d 当前条目=%d（容量 %d）%n",
                hits, misses, evict, cached, 512);
        // 水文段（湖/河雕刻）分布 —— "区块加载变慢"最可能的落点
        java.util.List<Integer> hyd = new java.util.ArrayList<>(hydroList);
        java.util.Collections.sort(hyd);
        long hydroTot = hydroSum[0];
        int nz = hyd.size();
        long over200 = hyd.stream().filter(v -> v > 200).count();
        long over1000 = hyd.stream().filter(v -> v > 1000).count();
        System.out.printf("水文(hydro)：总 %d ms（占 %.1f%%）/ 有耗时的 chunk %d 个"
                        + " ⇒ 均值 %.1f ms、中位 %d、P95 %d、最大 %d%n",
                hydroTot, hydroTot * 100.0 / (total / 1e6), nz,
                nz == 0 ? 0 : hydroTot / (double) nz,
                nz == 0 ? 0 : hyd.get(nz / 2),
                nz == 0 ? 0 : hyd.get((int) Math.min(nz - 1, nz * 0.95)),
                nz == 0 ? 0 : hyd.get(nz - 1));
        System.out.printf("水文尖峰：>200ms 的 chunk = %d 个、>1000ms = %d 个%n", over200, over1000);
    }
}
