package com.geogenesis.worldgen.terrain;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ★ 2026-09-14：<b>并发 chunk 生成压测</b>（复现"卡在 0%"的真实场景）。
 *
 * <h3>为何单线程探针测不出问题</h3>
 * <p>用户日志的慢 chunk 达 <b>22 秒</b>，而单线程 {@code ChunkTimeProbe} 只测出 2 秒 ——
 * 相差 10 倍。根因是<b>并发超订</b>：
 * <ul>
 *   <li>{@code TILE_PARALLELISM} 个 tile 线程并发跑侵蚀 tile</li>
 *   <li>每个 tile 内部又用 {@code parallelRows}（ForkJoinPool.commonPool）做行级并行</li>
 *   <li>⇒ N × commonPool 的<b>嵌套并行</b> ⇒ 线程数远超核数 ⇒ 上下文切换风暴</li>
 * </ul>
 * 本探针用<b>与生产相同的线程数</b>并发跑 chunk，才能反映真实代价。</p>
 *
 * <pre>{@code gradlew runConcurrentChunkProbe [-PprobeArgs="seed threads chunks"]}</pre>
 */
public final class ConcurrentChunkProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : CellGenerator.TILE_PARALLELISM;
        int perThread = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        System.out.printf("=== ConcurrentChunkProbe seed=%d threads=%d chunks/thread=%d ===%n",
                seed, threads, perThread);

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        AtomicLong totalNs = new AtomicLong();
        AtomicLong maxNs = new AtomicLong();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();

        Future<?>[] fs = new Future[threads];
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            fs[t] = pool.submit(() -> {
                // 各线程取不同的 chunk 区域（模拟玩家周围大量 chunk 并发生成）
                for (int k = 0; k < perThread; k++) {
                    int cx = tid * 16 + k - 64;
                    int cz = tid * 3 - 60;
                    long s = System.nanoTime();
                    terrain.getChunkCells(cx, cz);
                    long d = System.nanoTime() - s;
                    totalNs.addAndGet(d);
                    maxNs.accumulateAndGet(d, Math::max);
                    done.incrementAndGet();
                }
            });
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        long wall = (System.nanoTime() - t0) / 1_000_000;

        int n = done.get();
        System.out.printf("完成 chunk=%d | 墙钟=%dms | 平均单chunk=%dms | 最慢=%dms%n",
                n, wall, n > 0 ? totalNs.get() / n / 1_000_000 : 0, maxNs.get() / 1_000_000);

        // 判据：并发下最慢 chunk 不应超过 5 秒（用户日志曾达 22 秒）
        boolean pass = maxNs.get() / 1_000_000 < 5000;
        System.out.printf("并发下最慢 chunk < 5s: %s%n", pass ? "PASS" : "FAIL");
        if (!pass) System.exit(1);
    }
}
