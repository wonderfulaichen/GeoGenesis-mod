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

        int[][] cases = {
                // radius, samples
                {256, 100_000},
                {512, 200_000},
                {512, 100_000},
                {512, 50_000},
                {700, 200_000},
                {700, 50_000},
        };

        System.out.println("=== HydrologyPipeline 单窗口耗时（接线可行性）===");
        System.out.printf("seed=%d centre=(%d,%d)%n%n", seed, cx, cz);
        System.out.printf("%8s %10s %12s %12s %14s%n",
                "radius", "samples", "耗时ms", "chunk数", "ms/chunk");
        for (int[] c : cases) {
            int radius = c[0], samples = c[1];
            // 冷启动：每次换中心，避免 tile 缓存完全命中
            long t0 = System.nanoTime();
            var r = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                            HydrologyPipeline.RiverModel.CONTINUOUS, samples));
            double ms = (System.nanoTime() - t0) / 1e6;
            int chunks = (2 * radius / 16) * (2 * radius / 16);
            System.out.printf("%8d %10d %12.0f %12d %14.2f%n",
                    radius, samples, ms, chunks, ms / chunks);
            if (r == null) break;
        }
        System.out.println();
        System.out.println("判读：");
        System.out.println("  ms/chunk 若 <= legacy 基线(6.8) ⇒ 可直接接线；");
        System.out.println("  若高 1 个数量级 ⇒ 须先降 samples/radius，或只在需要处求解。");
    }
}