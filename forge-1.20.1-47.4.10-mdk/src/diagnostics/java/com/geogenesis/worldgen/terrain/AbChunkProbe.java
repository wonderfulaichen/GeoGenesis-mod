package com.geogenesis.worldgen.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ★ 2026-09-14：<b>基线 A/B 性能探针</b>（同一份代码同时兼容 505c81f 与 HEAD）。
 *
 * <h3>为何需要</h3>
 * <p>用户反馈"速度还没恢复到几个小时前"（基线 {@code 505c81f}），但此前所有
 * 单点优化（{@code landFactorAt} 重采样、LUT 分配、tile 嵌套并行）都只能证明
 * "某一项变快了"，<b>没有任何一次是拿基线直接对比的</b>。本探针用<b>同一段代码</b>
 * 在同一台机器上分别测量基线与当前，从而给出<b>可比的差值</b>，而不是推测。</p>
 *
 * <h3>测量口径（覆盖用户实际感知的路径）</h3>
 * <ol>
 *   <li><b>sample</b>：纯地形采样（{@code CellGenerator.sample}，无侵蚀 tile）
 *       —— 对应"每格基础地形"成本。</li>
 *   <li><b>seq</b>：顺序冷启动一批 chunk（{@code getChunkCells}，含侵蚀 tile 冷生成
 *       与水文 region 构建）—— 对应服务器/预览的 cells= 段。</li>
 *   <li><b>conc</b>：多线程并发冷启动（同生产 tile 线程数）
 *       —— 对应"准备生成区域"时的真实并发场景。</li>
 * </ol>
 *
 * <p>输出全部为 ASCII 键值行（{@code RESULT key=value}），便于跨版本对比与脚本解析。</p>
 *
 * <pre>{@code gradlew runAbChunkProbe -PprobeArgs="seed chunks threads"}</pre>
 */
public final class AbChunkProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 49;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;

        final int R = 9;   // 重复轮数（取最小轮以消除 GC/变频干扰）

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        // ---------- 1) 纯地形采样（无侵蚀 tile）----------
        //   ★ 可靠性设计（单次测量噪声达 ±40%，实测同码在不同 commit 间 13~22us 漂移）：
        //     ① 先充分预热 JIT（C2 编译完成后再计时）；
        //     ② 重复 R 轮，每轮取平均，最后取【各轮平均的最小值】——
        //        最小值最接近"无干扰下的真实成本"，可跨版本稳定比较。
        for (int i = 0; i < 20000; i++) gen.sample(i * 0.7, i * 0.3);
        int S = 20000;
        long[] rounds = new long[R];
        double acc = 0;
        for (int r = 0; r < R; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < S; i++) {
                acc += gen.sample((i % 5000) * 0.37, ((i / 5000) % 5000) * 0.71).e;
            }
            rounds[r] = (System.nanoTime() - t0) / S;
        }
        long[] sortedR = rounds.clone();
        java.util.Arrays.sort(sortedR);
        long sampleNs = sortedR[0];                       // 最小轮 = 最干净
        long sampleMed = sortedR[R / 2];
        long sampleMax = sortedR[R - 1];
        System.out.println("RESULT sample_ns=" + sampleNs
                + " sample_med_ns=" + sampleMed + " sample_max_ns=" + sampleMax);

        // ★ 实测结论（同进程 A/B）：地层段（packSequence 4 次噪声 + tiltAt 3 次噪声）
        //   仅占 322 ns（约 3%）—— 不是性能回归的主因，故不再保留该开关。

        // 只测 sample（快速迭代用：定位是哪项改动拖慢 sampleCore）
        if (args.length > 3 && "sampleonly".equals(args[3])) {
            System.out.println("RESULT mode=sampleonly checksum=" + (long) acc + " (ignore)");
            return;
        }

        // ★ 已用【同进程 A/B】得到的关键结论（避免跨进程噪声，故不再重复）：
        //   · landFactorAt 在 terrainEQuick 路径上开销 ≈ 0（火山中心是离散点，LF 缓存命中>99%）
        //   · 地层段（packSequence 4 次噪声 + tiltAt 3 次噪声）≈ 322 ns（3%）
        //   · P3 硬度网格：曾因【缓存键为精确坐标 ⇒ 命中率~0%】而昂贵；
        //     已改为【按 16wu 网格量化键】⇒ 开销回落到噪声水平。
        //   故此处只保留可跨版本（505c81f..HEAD）直接对比的三项。

        // ---------- 2) 顺序冷启动 chunk ----------
        //   ★ 第 4 参可指定区域偏移（默认 0）—— 供"交替测量"用不同区域，
        //     保证每次都是真冷启动（避免命中上次的 tile 缓存）。
        int baseOff = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        int side = (int) Math.ceil(Math.sqrt(n));
        long sumSeq = 0, maxSeq = 0;
        long tB0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int cx = (i % side) - side / 2 + baseOff;
            int cz = (i / side) - side / 2 + baseOff;
            long s = System.nanoTime();
            terrain.getChunkCells(cx, cz);
            long ms = (System.nanoTime() - s) / 1_000_000;
            sumSeq += ms;
            if (ms > maxSeq) maxSeq = ms;
        }
        long seqWall = (System.nanoTime() - tB0) / 1_000_000;
        System.out.println("RESULT seq_wall_ms=" + seqWall
                + " seq_avg_ms=" + (sumSeq / n) + " seq_max_ms=" + maxSeq);

        // ---------- 3) 并发冷启动 chunk（不同区域，保证冷）----------
        GeoGenesisTerrain t2 = new GeoGenesisTerrain(gen);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> fs = new ArrayList<>();
        final int per = Math.max(1, n / threads);
        long tC0 = System.nanoTime();
        for (int th = 0; th < threads; th++) {
            final int tid = th;
            fs.add(pool.submit(() -> {
                long mx = 0, sm = 0;
                for (int k = 0; k < per; k++) {
                    int cx = tid * 20 + k - 300;
                    int cz = tid * 9 - 150;
                    long s = System.nanoTime();
                    t2.getChunkCells(cx, cz);
                    long ms = (System.nanoTime() - s) / 1_000_000;
                    sm += ms;
                    if (ms > mx) mx = ms;
                }
                return new long[]{mx, sm, per};
            }));
        }
        long maxConc = 0, avgConc = 0;
        long tot = 0, cnt = 0;
        for (Future<long[]> f : fs) {
            long[] v = f.get();
            if (v[0] > maxConc) maxConc = v[0];
            tot += v[1];
            cnt += v[2];
        }
        pool.shutdown();
        long concWall = (System.nanoTime() - tC0) / 1_000_000;
        avgConc = cnt > 0 ? tot / cnt : 0;
        System.out.println("RESULT conc_wall_ms=" + concWall
                + " conc_avg_ms=" + avgConc + " conc_max_ms=" + maxConc
                + " threads=" + threads);

        System.out.println("RESULT checksum=" + (long) acc + " (ignore)");
    }
}
