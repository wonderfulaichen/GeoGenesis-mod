package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>接线前置：{@code HydrologyPipeline.run} 的【单窗口耗时】实测</h1>
 *
 * <h2>为什么必须先测</h2>
 * <p>要把 `model/` 链接进生产（用户已批准），必须先确认它能在世界生成里跑得动。
 * 生产是【逐 chunk】调用的（`sampleBlockAll`），而本链是【窗口式】求解
 * （`run(中心, 半径)`）⇒ 必然要做【区域缓存】：一个区域解一次，区域内的 chunk 共享。</p>
 *
 * <p>⇒ 关键量 = <b>单区域求解耗时</b>。若达数十秒，世界创建会卡死（历史教训：
 * 2026-09-29 "创建世界卡死十分钟级"）。对照基线：legacy `riverline` 链
 * 实测约 <b>6.8 ms/chunk</b>（`ChunkLoadPerfProbe`）。</p>
 *
 * <h2>测什么</h2>
 * <ol>
 *   <li>不同 radius × samples 的单窗口耗时；</li>
 *   <li>折算到【每 chunk 成本】（假设区域边长 = 2×radius 块 ⇒ chunk 数 = (2r/16)²）；</li>
 *   <li>冷启动 vs 二次调用（缓存效应：CellGenerator 的 tile 缓存会复用）。</li>
 * </ol>
 *
 * <p><b>NOT VERIFIED</b>：未实测真实世界生成条件下的并发与内存压力。</p>
 */
public final class PipelinePerfProbe {

    private PipelinePerfProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        // {radius, samples, step} —— step=1 是正典出图口径（1401² 全量采样），
        // step=4 是生产形态（探针/契约常用），采样量少 16×。
        int[][] cases = {
                {256, 100_000, 1},
                {512, 200_000, 1},
                {512, 100_000, 1},
                {512, 50_000, 1},
                {700, 200_000, 1},
                {700, 50_000, 1},
                {512, 50_000, 4},
                {512, 100_000, 4},
                {700, 100_000, 4},
        };

        // ★ 方案 §P3 点名的两个口径问题（本轮修正）：
        //   ① 旧版注释写"每次换中心"但**实际没换** ⇒ tile 缓存全命中、耗时偏低；
        //   ② 旧版按【整个窗口】摊销 ms/chunk —— 但窗口含 halo 与边界带，
        //      **可发布核心区**只有 (2r-2×HALO)²。按核心区算才是真实接线成本。
        final int halo = 96;                     // 可发布核心外的保护带（块）
        int[] offsets = {0, 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x7000, 0x8000};
        StringBuilder report = new StringBuilder();
        report.append("=== HydrologyPipeline 单窗口耗时（接线可行性，方案 P3 口径）===\n");
        report.append(String.format("seed=%d  (halo=%d, 换中心避缓存命中)%n%n", seed, halo));
        String header = String.format("%8s %6s %10s %12s %14s %14s %14s%n",
                "radius", "step", "samples", "耗时ms", "整窗ms/chunk", "核心区ms/chunk", "二次ms/chunk");
        System.out.print(header);
        report.append(header);
        for (int ci = 0; ci < cases.length; ci++) {
            int radius = cases[ci][0], samples = cases[ci][1], step = cases[ci][2];
            int ccx = cx + offsets[ci], ccz = cz + offsets[ci];
            long t0 = System.nanoTime();
            var r = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(ccx, ccz, radius, step, climate, 0,
                            HydrologyPipeline.RiverModel.CONTINUOUS, samples));
            double ms = (System.nanoTime() - t0) / 1e6;
            // 二次调用（同窗口热缓存）：反映区域缓存命中后的真实成本
            t0 = System.nanoTime();
            HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(ccx, ccz, radius, step, climate, 0,
                            HydrologyPipeline.RiverModel.CONTINUOUS, samples));
            double ms2 = (System.nanoTime() - t0) / 1e6;
            int fullChunks = (2 * radius / 16) * (2 * radius / 16);
            int coreRadius = Math.max(0, radius - halo);
            int coreChunks = (2 * coreRadius / 16) * (2 * coreRadius / 16);
            String row = String.format("%8d %6d %10d %12.0f %14.2f %14.2f %14.2f%n",
                    radius, step, samples, ms,
                    ms / Math.max(1, fullChunks),
                    ms / Math.max(1, coreChunks),
                    ms2 / Math.max(1, coreChunks));
            System.out.print(row);
            report.append(row);
            if (r == null) break;
        }
        report.append("\n判读（方案 §P3）：按【可发布核心区】ms/chunk 对比 legacy 基线 6.8。\n");
        report.append("单窗口耗时 = 新区域首次求解延迟（玩家进新区域要等这么久）。\n");
        report.append("不含并发与冷 tile 竞争 —— 实机并发冷启动仍未测（如实）。\n");
        System.out.println();
        System.out.println("判读（方案 §P3）：");
        System.out.println("  按【可发布核心区】ms/chunk 对比 legacy 基线 6.8：");
        System.out.println("  <=6.8 ⇒ 接线可行；高 1 个数量级 ⇒ 先降 samples/radius 或按需求解。");
        System.out.println("  二次ms/chunk 反映区域缓存命中成本；冷/热差距大 ⇒ 缓存命中率是关键。");
        System.out.println("  ⚠ 本探针不含并发与冷 tile 竞争 —— 实机并发冷启动仍未测（如实）。");
        // 结果落盘（终端过滤会丢数据行 ⇒ 证据必须写文件）
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/perf"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/perf/pipeline-perf.txt"),
                report.toString());
        System.out.println("FILE=build/perf/pipeline-perf.txt");
    }
}