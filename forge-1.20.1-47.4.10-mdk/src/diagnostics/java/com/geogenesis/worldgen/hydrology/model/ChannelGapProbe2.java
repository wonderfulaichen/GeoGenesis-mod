package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>「gap 有墨水却不成河」的口径核对</h1>
 *
 * <h2>由 ChannelGapProbe 暴露的矛盾</h2>
 * <pre>
 *   分量#8  gap=3格 gap内maxFlux=10.091（10 倍门槛）【≥门槛却没成河】
 *   分量#12 gap=9格 gap内maxFlux=5.287                【≥门槛却没成河】
 * </pre>
 * <p>成因只能有两个（必须区分）：</p>
 * <ol>
 *   <li><b>口径差异</b>：成河判定用【3×3 平滑】{@code smooth}，
 *       而我的探针读 {@code discharge}（原始 flux）⇒ 二者不同。
 *       若 gap 格原始 flux 高但 smooth 低 ⇒ 判定量确实不够 ⇒ 一致，非缺陷。</li>
 *   <li><b>真矛盾</b>：gap 格的 smooth 也 ≥ 门槛，却没被标 channel
 *       ⇒ 判定逻辑有 bug（如遍历顺序、传播覆盖）。</li>
 * </ol>
 * <p>本探针对 gap 格同时打印 raw / smooth / channel，直接判定属哪一种。</p>
 */
public final class ChannelGapProbe2 {

    private ChannelGapProbe2() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 150_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        DirectionRule.setMode("baseline");

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] ch = r.channel();
        boolean[] sea = r.sea();
        double[] raw = r.discharge();

        // 复算 smooth（与 ContinuousRiverNetwork 内部口径一致：海格不参与）
        double[] smooth = new double[cells];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (sea[k]) { smooth[k] = raw[k]; continue; }
                double sum = 0; int cnt = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        int kk = a * n + b;
                        if (sea[kk]) continue;
                        sum += raw[kk]; cnt++;
                    }
                }
                smooth[k] = cnt > 0 ? sum / cnt : raw[k];
            }
        }

        // ★ 全图统计：smooth ≥ 门槛 但 channel=false 的格
        double threshold = 1.0;
        int aboveSmoothNotChannel = 0, aboveSmoothAndChannel = 0;
        int aboveRawNotChannel = 0, aboveRawAndChannel = 0;
        int aboveSmoothNotChannelNonBorder = 0;
        java.util.List<String> examples = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                boolean chOn = ch[k];
                if (smooth[k] >= threshold) {
                    if (chOn) aboveSmoothAndChannel++;
                    else {
                        aboveSmoothNotChannel++;
                        // 排除窗口边界缓冲（borderBufferCells=0 时无影响，仍记录）
                        if (i > 0 && j > 0 && i < n - 1 && j < n - 1) aboveSmoothNotChannelNonBorder++;
                        if (examples.size() < 8) {
                            examples.add(String.format(
                                    "   (%4d,%4d) raw=%.4f smooth=%.4f channel=%b resolved=%b sea=%b",
                                    i, j, raw[k], smooth[k], chOn, r.resolved()[k], sea[k]));
                        }
                    }
                }
                if (raw[k] >= threshold) {
                    if (chOn) aboveRawAndChannel++; else aboveRawNotChannel++;
                }
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 「gap 有墨水却不成河」口径核对 ===\n");
        log.append(String.format("seed=%d r=%d 门槛=%.1f%n%n", seed, radius, threshold));
        log.append(String.format("以【smooth】(成河判定实际用量) 为准：%n"));
        log.append(String.format("  smooth >= 门槛 且 已成河 = %d%n", aboveSmoothAndChannel));
        log.append(String.format("  smooth >= 门槛 但 未成河 = %d   ← 若>0 则是真矛盾（判定 bug）%n",
                aboveSmoothNotChannel));
        log.append(String.format("    其中非窗口边界格 = %d%n", aboveSmoothNotChannelNonBorder));
        log.append(String.format("以【raw】(我探针先前误用) 为准：%n"));
        log.append(String.format("  raw >= 门槛 且 已成河 = %d%n", aboveRawAndChannel));
        log.append(String.format("  raw >= 门槛 但 未成河 = %d   ← 若大 ⇒ 纯粹是【口径差异】(平滑拉低)，非缺陷%n",
                aboveRawNotChannel));
        log.append(String.format("%n样例（smooth≥门槛却未成河）：%n"));
        for (String s : examples) log.append(s).append('\n');
        log.append(String.format("%n判读：%s%n", aboveSmoothNotChannel == 0
                ? "【口径差异】原结论成立：gap 的 raw 高但 smooth 不够 ⇒ 判定一致，无 bug。"
                : "【真矛盾】存在 smooth 达标却未成河的格 ⇒ 判定逻辑需查（属真缺陷）。"));

        java.nio.file.Path dir = java.nio.file.Path.of("build/gap");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("channel_gap2.txt"), log);
        System.out.println(log);
    }
}