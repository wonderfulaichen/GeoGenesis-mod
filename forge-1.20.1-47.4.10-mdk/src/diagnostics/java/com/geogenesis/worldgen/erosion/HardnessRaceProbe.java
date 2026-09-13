package com.geogenesis.worldgen.erosion;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ 2026-09-14：<b>硬度网格并发安全压测</b>（复现并验证崩溃修复）。
 *
 * <h3>被复现的崩溃</h3>
 * <pre>
 *   ArrayIndexOutOfBoundsException: Index -2 out of bounds for length 11
 *     at ErosionEngine.sampleHardness(ErosionEngine.java:222)
 * </pre>
 * <p><b>根因</b>：硬度网格的尺寸/原点曾是 {@code ErosionEngine} 的<b>实例字段</b>，
 * 而该引擎实例被<b>多线程共享</b>（Minecraft 的 Worker-Main-N 与 TileSampler
 * 并发生成不同区块的侵蚀 tile）⇒ 一个线程构建的网格被另一个线程用其
 * {@code ox/oz} 查询 ⇒ 坐标与网格不匹配 ⇒ 负索引。</p>
 * <p>副作用：异常在每次液滴模拟中抛出（异常构造 + 栈展开极慢）⇒
 * 实测 chunk 生成 2.3 秒 → <b>23 秒</b>（用户"卡在 0%"）。</p>
 *
 * <h3>本探针</h3>
 * <p>用<b>多线程并发</b>调用同一个 {@link ErosionEngine} 实例生成<b>不同原点</b>的
 * tile —— 正是线上崩溃的条件。修复后应：<b>零异常</b>且各线程结果正确。</p>
 *
 * <pre>{@code gradlew runHardnessRaceProbe [-PprobeArgs="seed threads"]}</pre>
 */
public final class HardnessRaceProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        System.out.printf("=== HardnessRaceProbe seed=%d threads=%d ===%n", seed, threads);

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        // ★ 单实例共享（与生产一致 —— 这是崩溃的前提条件）
        CellGenerator gen2 = gen;   // gen 内部持有单个 ErosionEngine 实例（已注入硬度提供者）

        AtomicInteger errors = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();

        // 并发跑多个【不同原点】的 tile —— 原实现下会互相覆盖 hardGrid/hardOrigin*，
        // 导致 sampleHardness 收到越界索引。
        Future<?>[] fs = new Future[threads];
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            fs[t] = pool.submit(() -> {
                try {
                    for (int k = 0; k < 6; k++) {
                        // 原点彼此远离且含负值（覆盖负索引路径）
                        int cx = (tid * 7 - 30) * 48;
                        int cz = (k * 11 - 25) * 48;
                        gen2.runErosionAtForProbe(cx, cz, p.horizontalScale());
                        done.incrementAndGet();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    System.out.println("  [异常] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    // ★ 必须打堆栈 —— 否则无法区分"我方 bug"与"探针用法错误"
                    StackTraceElement[] st = e.getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) {
                        System.out.println("      at " + st[i]);
                    }
                }
            });
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("完成 tile=%d | 线程异常=%d | 耗时=%d ms%n", done.get(), errors.get(), ms);
        boolean pass = errors.get() == 0 && done.get() == threads * 6;
        System.out.println(pass ? "=== ALL PASS（并发无异常）===" : "=== FAILURES PRESENT ===");
        if (!pass) System.exit(1);
    }
}
