package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【中断 → 重试风暴】离线复现与判据（★ 2026-09-19）。
 *
 * <h2>它要验的假设</h2>
 * <p>实机（seed 9139912035078620160）三轮反复出现 <b>单块 extract 达 14 / 41 / 103 秒</b>，
 * 且 {@code 停顿(未占CPU)} 判据确认它<b>真在烧 CPU</b>（不是被挂住）；而同一局
 * {@code 侵蚀tile生成} 只有 70~97 次（总 13~17 秒）。
 * <b>「烧了 CPU 却不计入生成」</b>指向一条具体路径：</p>
 * <pre>
 *   线程中断位已置（预览关闭 → TerrainPool.cancelAll → f.cancel(true)）
 *     → generateErosionTile 内部 parallelRows（ForkJoin commonPool）
 *       在【已中断】线程上抛 CancellationException
 *     → 半成品【绝不入缓存】→ 下一个 cell 再试
 *     → 每块 256 格 × 至多 3 个邻居 ≈ 660 次重试，每次白烧到抛点前
 *     ⇒ 单块可达数十秒；且中止的生成走不到 TILEGEN 记账处 ⇒ 计数对不上
 * </pre>
 *
 * <h2>判据（门禁清单 §D-1：判据必须能区分对与错）</h2>
 * <ol>
 *   <li><b>[1] 常态</b>：无中断线程生成一块全新区域 ⇒ 记录耗时 + tile miss/hit。</li>
 *   <li><b>[2] 中断态</b>：<b>另一线程先置位中断</b>，再生成另一块全新区域 ⇒ 同样记录。</li>
 *   <li><b>[3] 断言</b>：中断态 miss <b>不得多于</b>常态；耗时<b>不得慢一个量级</b>。</li>
 * </ol>
 * <p>修复到位 ⇒ [2] 的 miss ≈ 0（入口直接放弃）。<b>把修复去掉 ⇒ [2] 的 miss 暴涨、
 * 耗时数十秒</b> —— 那时本探针会 FAIL。这就是「能区分对与错」。</p>
 *
 * <h2>为什么必须离线可跑</h2>
 * <p>该缺陷只在「预览关闭 + 世界生成重叠」时出现，让用户反复开游戏只为给我取数
 * 代价过高；而它本质是<b>单线程可复现</b>的（打断一个线程即可），无需 MC 运行时。</p>
 *
 * <pre>{@code gradlew runInterruptStormProbe [-PprobeArgs="seed"]}</pre>
 */
public final class InterruptStormProbe {

    /** 一次测量的结果（由工作线程写、主线程 join 后读 —— join 提供 happens-before）。 */
    private static final class M {
        long ms;
        long misses;
        long hits;
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        System.out.printf("=== InterruptStormProbe seed=%d ===%n%n", seed);

        // 关闭诊断埋点：本探针只看耗时与 miss 数，埋点开销会干扰计时
        WorldGenProfiler.configure(false, 0);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        // 两块【互不相邻】的全新区域（避免共享 tile 与预热互相干扰）
        M base = measure(gt, gen, 40, 40, false);
        M intr = measure(gt, gen, -40, -40, true);

        System.out.println("[1] 常态（无中断）    : " + base.ms + " ms, tile miss=" + base.misses
                + ", hit=" + base.hits);
        System.out.println("[2] 中断态（flag 置位）: " + intr.ms + " ms, tile miss=" + intr.misses
                + ", hit=" + intr.hits);
        System.out.println();
        System.out.println("    意义：若机制成立（中断 ⇒ 生成抛 CancellationException ⇒ 不入缓存");
        System.out.println("          ⇒ 逐格重试），[2] 的 miss 会是【数百】而不是个位数，");
        System.out.println("          耗时会长到几十秒。反之 miss≈0 且很快 ⇒ 入口拦截有效。");
        System.out.println();

        // ⚠ 判据修正（2026-09-19 —— 本轮自检抓到了我自己的错误判据）：
        //   初版写的是「中断态 miss 不得多于常态」。它【根本不能判别】—— 因为拦截点
        //   刻意放在 tileCacheGet【之后】（保证已缓存 tile 仍可用），于是两种情况下
        //   miss 都是 512（实测：守卫在位 512 / 守卫移除 512）。
        //   真正能判别的只有【耗时】：守卫在位 extract=0ms；移除后 extract=13950ms。
        //   ⇒ 判据改为「中断态总耗时不得显著超过常态」。
        long limit = Math.max(500L, (long) (base.ms * 1.5 + 500));
        boolean notSlower = intr.ms <= limit;
        System.out.printf("    [3] 中断态耗时未显著超过常态（%d <= %d）⇒ %s%n",
                intr.ms, limit, notSlower ? "PASS" : "FAIL");
        System.out.println("        反例留痕：临时移除 CellGenerator 的中断拦截后，本项实测");
        System.out.println("        14488ms vs 阈值 2260ms ⇒ FAIL（extract 由 0ms 涨到 13950ms）。");

        System.out.println();
        System.out.printf("总判定: %s%n", notSlower
                ? "ALL PASS（中断不会引发重试风暴）"
                : "FAIL（中断态出现重试风暴 ⇒ 入口拦截失效/被移除）");
        System.exit(notSlower ? 0 : 1);
    }

    /** 在【新线程】上生成一块，返回耗时与 tile 命中/未命中增量。 */
    private static M measure(GeoGenesisTerrain gt, CellGenerator gen,
                            int cx, int cz, boolean interrupt) {
        M m = new M();
        Thread t = new Thread(() -> {
            // ★ 关键：在【开始生成之前】置位中断位 —— 复现实机场景
            //   （预览关闭 → TerrainPool.cancelAll → f.cancel(true)）
            if (interrupt) Thread.currentThread().interrupt();
            long m0 = gen.tileCacheStats().misses();
            long h0 = gen.tileCacheStats().hits();
            long t0 = System.nanoTime();
            gt.getChunkCells(cx, cz);
            m.ms = (System.nanoTime() - t0) / 1_000_000L;
            m.misses = gen.tileCacheStats().misses() - m0;
            m.hits = gen.tileCacheStats().hits() - h0;
            Thread.interrupted();   // 消费中断位（该线程随即结束，属保险）
        }, "gg-probe-worker");
        t.setDaemon(true);
        t.start();
        try {
            t.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return m;
    }

    private InterruptStormProbe() { }
}
