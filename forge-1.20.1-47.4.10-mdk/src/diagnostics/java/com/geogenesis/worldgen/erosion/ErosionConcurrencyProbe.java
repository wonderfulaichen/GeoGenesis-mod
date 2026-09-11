package com.geogenesis.worldgen.erosion;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 侵蚀 tile 并发的<b>死锁回归探针</b>（★ 2026-09-11，D3 修复配套）。
 *
 * <p><b>背景</b>：{@code ErosionEngine} 的两处平滑（{@code smoothErosionResult} /
 * {@code smoothDepositionZones}）曾用 {@code TILE_SAMPLER.execute + CountDownLatch.await}
 * 做并行。该模式会<b>永久死锁</b>：
 * <ul>
 *   <li>{@code TILE_SAMPLER} 是<b>有界池</b>（core 8 / max 16 / 队列 64 / CallerRunsPolicy）；</li>
 *   <li>但每次仅提交约 <b>8</b> 个子任务 → <b>队列永不满</b>
 *       → 唯一的救命稻草 {@code CallerRunsPolicy}（队满时由提交者自己跑）<b>永不触发</b>；</li>
 *   <li>于是当 16 个池线程同时走到平滑、全部阻塞在 {@code await} 时，
 *       <b>没有任何线程</b>能取走队列里的子任务 → 死锁。</li>
 *   <li>且 {@code CellGenerator:603} 会在池内任务里调 {@code generateErosionTile}
 *       → 构成"池内提交同池任务"，即 {@code CellGenerator:42} 记录过的
 *       <b>"池饥饿死锁 27 轮"</b>。</li>
 * </ul>
 *
 * <p><b>修复</b>：改用 {@code IntStream.range(...).parallel()}（ForkJoinPool.commonPool，
 * work-stealing）——<b>调用线程会参与执行子任务</b>，不存在"等自己"。</p>
 *
 * <p>本探针用<b>硬超时</b>守护该不变量：并发跑大量 tile，若挂起则 FAIL。</p>
 *
 * <p>用法：{@code gradlew runErosionConcurrencyProbe [seed] [threads]}</p>
 */
public final class ErosionConcurrencyProbe {

    /** 并发线程数（&gt; TILE_SAMPLER 上限 16 才有机会触发原死锁）。 */
    private static final int DEFAULT_THREADS = 24;
    /** 每线程采样次数。 */
    private static final int PER_THREAD = 40;
    /** 硬超时（秒）——超过即判定死锁。 */
    private static final long TIMEOUT_SEC = 180;

    private ErosionConcurrencyProbe() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_THREADS;
        TerrainParams tp = TerrainParams.defaults();
        System.out.printf("=== ErosionConcurrencyProbe seed=%d threads=%d perThread=%d ===%n",
            seed, threads, PER_THREAD);

        // ================= [1] 并发无死锁 =================
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    // 每线程取【互不相同】的坐标 → 强制生成大量不同侵蚀 tile（制造池压力）
                    for (int i = 0; i < PER_THREAD; i++) {
                        double x = 12000 + tid * 5000 + i * 48.0;
                        double z = 12000 + i * 48.0;
                        gen.sampleWu(x, z);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }, "ErosionProbe-" + t);
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }

        long t0 = System.nanoTime();
        start.countDown();
        boolean finished = done.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        double sec = (System.nanoTime() - t0) / 1e9;

        boolean pass1 = finished && errors.get() == 0;
        System.out.printf("[1] 并发 %d 线程 × %d 次 sampleWu: %s  用时 %.2fs  线程错误=%d  tile缓存=%d %s%n",
            threads, PER_THREAD, finished ? "全部完成" : "★ 超时（疑似死锁）★",
            sec, errors.get(), gen.erosionTileCacheSize(), pass1 ? "PASS" : "FAIL");
        if (!finished) {
            System.out.println("    → 超时：检查是否有『池内提交同池任务 + 阻塞等待』的成环路径");
        }

        // ================= [2] 并行确定性 =================
        //   并行化的前提是【输出与调度无关】：两个独立实例（同 seed）跑同一批坐标，
        //   结果必须逐位一致。若并行写冲突，这里会暴露。
        CellGenerator a = new CellGenerator(tp, tp.minY(), tp.maxY());
        a.seed(seed);
        CellGenerator b = new CellGenerator(tp, tp.minY(), tp.maxY());
        b.seed(seed);
        int n = 0, mismatchE = 0, mismatchH = 0, shown = 0;
        for (double z = -8000; z <= 8000; z += 311) {
            for (double x = -8000; x <= 8000; x += 313) {
                var ca = a.sampleWu(x, z);
                var cb = b.sampleWu(x, z);
                n++;
                // 用 Double.compare 而非 != ：后者对 NaN 恒真（NaN != NaN），
                // 会把"两者都是 NaN"误报成不一致。
                boolean eDiff = Double.compare(ca.e, cb.e) != 0;
                boolean hDiff = Double.compare(ca.height, cb.height) != 0;
                if (eDiff) mismatchE++;
                if (hDiff) mismatchH++;
                if ((eDiff || hDiff) && shown < 5) {
                    shown++;
                    System.out.printf("    失配 @ x=%.1f z=%.1f : a.e=%.17g b.e=%.17g | a.h=%.17g b.h=%.17g%n",
                        x, z, ca.e, cb.e, ca.height, cb.height);
                }
            }
        }
        boolean pass2 = n > 0 && mismatchE == 0 && mismatchH == 0;
        System.out.printf("[2] 并行确定性(两独立实例, n=%d): e 不一致=%d  height 不一致=%d %s%n",
            n, mismatchE, mismatchH, pass2 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
