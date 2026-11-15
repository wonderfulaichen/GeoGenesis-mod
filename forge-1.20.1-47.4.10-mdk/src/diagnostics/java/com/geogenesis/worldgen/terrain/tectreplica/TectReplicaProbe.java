package com.geogenesis.worldgen.terrain.tectreplica;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * SimpleTectonics 纯 Java 复刻 —— 形态基准探针（非生产、不进门禁）。
 *
 * <p>用法：{@code gradlew runTectReplicaProbe [-PprobeArgs="<outDir> <steps> <seed>"]}
 * ；默认 outDir=build/tectreplica、steps=500（参考项目 SimpleTectonics.cpp:57-84
 * 被注释的批量模式口径：500 步 → 建高度图 → 停）。</p>
 *
 * <p>每步 = SimpleTectonics.cpp:175-204 主循环：各板块 update（碰撞/造山/生长/对流）
 * → subductHeat(25) → world.update()（清理 + 间隙增生 + Voronoi 重算）。
 * 结束后 buildHeightmap()（替代 sediment/cascading，见 TectWorld）→ 出图 + SHA + 统计。</p>
 *
 * <p>确定性：同 seed 双跑的 {@code stateSha256} 必须逐位一致（SplitMix64 + 纯 double
 * 运算，无时间/无并发）。</p>
 */
public final class TectReplicaProbe {

    private static final long DEFAULT_SEED = 9139912035078620160L;
    private static final int[] MILESTONES = {100, 1000, 5000};

    public static void main(String[] args) throws Exception {
        // ★ Mimosa 修复（2026-11-15）：out 钉在 build/ 下（防路径穿越，不再收命令行）。
        Path out = Path.of("build", "tectreplica").toAbsolutePath().normalize();
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_SEED;
        Files.createDirectories(out);
        long t0 = System.nanoTime();

        System.out.printf(Locale.ROOT, "=== SimpleTectonics replica seed=%d steps=%d out=%s ===%n",
                seed, steps, out);
        System.out.printf(Locale.ROOT, "R=%.6f (NDC)  R_px=%.4f  CR_px=%.4f  K=%d SIZE=%d nplates=%d%n",
                TectWorld.R, TectCluster.R_PX, TectWorld.SIZE * TectWorld.CR,
                TectWorld.K, TectWorld.SIZE, TectWorld.NPLATES);

        TectWorld world = new TectWorld(seed);
        TectRender.plateView(world, out.resolve("plates_000.png"), false);

        for (int step = 1; step <= steps; step++) {
            for (TectPlate p : world.plates) {
                p.update(world.cluster, world.heatmap);
            }
            world.subductHeat(25);
            world.updateWorld();
            if (step % 50 == 0) {
                double heatSum = 0;
                int bg = 0;
                double speedSum = 0;
                for (double v : world.heatmap) {
                    heatSum += v;
                }
                for (int p = 0; p < world.cluster.indexmap.length; p++) {
                    if (world.cluster.indexmap[p] < 0) {
                        bg++;
                    }
                }
                for (TectPlate p : world.plates) {
                    speedSum += Math.sqrt(p.speedX * p.speedX + p.speedY * p.speedY);
                }
                System.out.printf(Locale.ROOT, "step %d: segs=%d plates=%d meanHeat=%.4f bgPx=%d meanSpeed=%.5f%n",
                        step, world.cluster.segs.size(), world.plates.size(),
                        heatSum / world.heatmap.length, bg, speedSum / Math.max(1, world.plates.size()));
            }
            for (int m : MILESTONES) {
                if (step == m) {
                    TectRender.plateView(world, out.resolve("plates_" + m + ".png"), false);
                }
            }
        }

        world.buildHeightmap();
        renderFinal(world, out, steps);
        String sha = stateSha(world);
        String stats = stats(world, seed, steps, sha);
        Files.writeString(out.resolve("state-sha.txt"), stats);
        System.out.print(stats);
        System.out.printf(Locale.ROOT, "elapsed=%.2fs%n", (System.nanoTime() - t0) / 1e9);
        System.out.println("REPlica done (instrument only, no gate criterion)");
    }

    private static void renderFinal(TectWorld w, Path out, int steps) throws Exception {
        TectRender.plateView(w, out.resolve("plates_" + steps + ".png"), false);
        TectRender.plateView(w, out.resolve("plates_" + steps + "_shaded.png"), true);
        TectRender.terrainView(w, out.resolve("terrain_" + steps + ".png"), false);
        TectRender.terrainView(w, out.resolve("terrain_" + steps + "_shaded.png"), true);
        TectRender.heatView(w, out.resolve("heat_" + steps + ".png"));
        TectRender.voronoiView(w, out.resolve("voronoi_" + steps + ".png"));
    }

    private static String stateSha(TectWorld w) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        w.digestInto(md);
        return HexFormat.of().formatHex(md.digest());
    }

    /** 统计摘要：段/板块演化、高度与厚度分位、陆面占比、总质量（守恒粗检）。 */
    private static String stats(TectWorld w, long seed, int steps, String sha) {
        StringBuilder sb = new StringBuilder();
        List<Double> heights = new ArrayList<>();
        List<Double> thicks = new ArrayList<>();
        double totalMass = 0;
        int nonFinite = 0;
        for (TectSegment s : w.cluster.segs) {
            heights.add(s.height);
            thicks.add(s.thickness);
            totalMass += s.mass;
            if (!Double.isFinite(s.height) || !Double.isFinite(s.thickness)) {
                nonFinite++;
            }
        }
        heights.sort(Double::compare);
        thicks.sort(Double::compare);
        int bg = 0;
        int land = 0;
        for (int p = 0; p < w.heightmap.length; p++) {
            if (w.cluster.indexmap[p] < 0) {
                bg++;
            }
            if (w.heightmap[p] > 0.25) {
                land++;
            }
        }
        sb.append(String.format(Locale.ROOT, "seed=%d steps=%d%n", seed, steps));
        sb.append(String.format(Locale.ROOT, "stateSha256=%s%n", sha));
        sb.append(String.format(Locale.ROOT, "segments=%d plates=%d backgroundPx=%d/%d%n",
                w.cluster.segs.size(), w.plates.size(), bg, w.heightmap.length));
        sb.append(String.format(Locale.ROOT, "height  p10=%.4f p50=%.4f p90=%.4f max=%.4f%n",
                q(heights, 0.1), q(heights, 0.5), q(heights, 0.9), q(heights, 1.0)));
        sb.append(String.format(Locale.ROOT, "thick   p10=%.4f p50=%.4f p90=%.4f max=%.4f%n",
                q(thicks, 0.1), q(thicks, 0.5), q(thicks, 0.9), q(thicks, 1.0)));
        sb.append(String.format(Locale.ROOT, "totalMass=%.4f landFrac(h>sealevel)=%.4f nonFiniteSegs=%d%n",
                totalMass, (double) land / w.heightmap.length, nonFinite));
        return sb.toString();
    }

    private static double q(List<Double> sorted, double frac) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int i = (int) Math.min(sorted.size() - 1, Math.floor(frac * sorted.size()));
        return sorted.get(i);
    }
}
