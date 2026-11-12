package com.geogenesis.worldgen.terrain.morph;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 深海深度 vs 构造距离 —— **纯生产路径**测量（2026-11-12）。
 *
 * <h3>为什么重写（重要）</h3>
 * <p>此前几轮在探针里**手工重拼** {@code buildDeepOcean} 的节点树来"复刻"生产链，
 * 结果连续四轮给出互相矛盾的结论（selector r=−0.44 系漏种子偏移、oceanDetail 恒 0、
 * warp 占比基于不等价基线……）。根因不是算错个别常数，而是**方法错误**：
 * 复刻 ≠ 生产。项目规则（AGENTS.md「★ 渲染图 = 游戏实机」、HYDROLOGY.md §三）
 * 明确要求结论必须来自生产路径，否则只是"测试环境的结论"。</p>
 *
 * <h3>本探针的做法</h3>
 * <p>不再重拼任何节点。直接拿 {@link MorphTerrainSampler#oceanAssembler()} 返回的
 * <b>生产实例</b>，调它的 public {@code heightBlocks / e / sea}。这样测到的就是模组
 * 真实产出，不存在"复刻是否等价"的问题。</p>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>海侧深度分布（全部 / 纯深海段）</li>
 *   <li>{@code r(depth, dist)}：深度与离板块边界距离的相关性</li>
 *   <li>按 dist 分桶的均值深度（脊轴侧 vs 深海平原侧的实测梯度）</li>
 *   <li>{@code sea()} 分段计数，确认深海段样本量</li>
 * </ul>
 *
 * <p>用法：{@code SeaDepthProductionProbe <toml> <outDir> [size] [cx] [cz] [radiusBlocks]}</p>
 */
public final class SeaDepthProductionProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: SeaDepthProductionProbe <toml> <outDir> [size] [cx] [cz] [r]");
            return;
        }
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 401;
        double cx = args.length > 3 ? Double.parseDouble(args[3]) : 1590;
        double cz = args.length > 4 ? Double.parseDouble(args[4]) : 2250;
        double radiusBlocks = args.length > 5 ? Double.parseDouble(args[5]) : 900;

        try (CommentedFileConfig cfg = CommentedFileConfig.of(Path.of(args[0]), TomlFormat.instance())) {
            cfg.load();
            if (!GeoGenesisConfig.SPEC.isCorrect(cfg)) throw new IllegalStateException("config invalid");
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
            double hs = p.horizontalScale();

            // ★ 生产实例：与 CellGenerator / BaseWorldSampler 用的是同一套代码路径
            MorphTerrainSampler terrain = new MorphTerrainSampler(SEED, p, -64, 320);
            MorphOceanAssembler ocean = terrain.oceanAssembler();
            TectonicField tect = new TectonicField(SEED);
            double seaY = terrain.heightCurve().seaLevelY();

            System.out.printf(Locale.ROOT,
                    "=== 生产路径测量 seed=%d size=%d centre=(%.0f,%.0f) r=%.0f hs=%.1f seaY=%.1f ===%n",
                    SEED, size, cx, cz, radiusBlocks, hs, seaY);

            Acc allSea = new Acc("全部海侧（heightBlocks<0）");
            Acc deepOnly = new Acc("纯深海段（sea()==DEEP_OCEAN）");
            Acc shallowOnly = new Acc("浅海段（sea()==SHALLOW_OCEAN）");
            // ★ 玩家真正看到的高度：heightFromE(sampleE)，含海岸剖面 lerp 的最终结果
            Acc finalAll = new Acc("全部海侧（最终 Y，sampleE）");
            Acc finalDeep = new Acc("纯深海段（最终 Y，sampleE）");

            double[] edges = {0, 150, 300, 450, 650, 900, 1200, 1600, 1e9};
            double[] sumDeep = new double[edges.length];
            int[] cntDeep = new int[edges.length];
            double[] sumFinal = new double[edges.length];
            int[] cntFinal = new int[edges.length];

            int nDeep = 0, nShallow = 0, nCoast = 0, nLand = 0;
            double minD = 1e9, maxD = -1e9;

            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double bx = cx - radiusBlocks + (double) i / (size - 1) * 2 * radiusBlocks;
                    double bz = cz - radiusBlocks + (double) j / (size - 1) * 2 * radiusBlocks;
                    double wx = bx / hs, wz = bz / hs;

                    // ★ 生产调用：直接问装配器要高度（负格 = 水深）
                    double blocks = ocean.heightBlocks(wx, wz);
                    MorphOceanAssembler.Sea seg = ocean.sea(wx, wz);
                    switch (seg) {
                        case DEEP_OCEAN -> nDeep++;
                        case SHALLOW_OCEAN -> nShallow++;
                        case COAST -> nCoast++;
                        default -> nLand++;
                    }

                    // ★ 最终高度（玩家看到的那一个）：heightFromE(sampleE)
                    double eFinal = terrain.sampleE(wx, wz);
                    double finalDepth = eFinal < 0 ? seaY - terrain.heightCurve().heightFromE(eFinal) : Double.NaN;

                    if (blocks >= 0) continue;                 // 只统计水下
                    double depth = -blocks;
                    if (depth <= 0) continue;

                    double dist = tect.sample(wx, wz).dist();
                    minD = Math.min(minD, depth);
                    maxD = Math.max(maxD, depth);

                    allSea.add(depth, dist);
                    if (!Double.isNaN(finalDepth) && finalDepth > 0) finalAll.add(finalDepth, dist);
                    if (seg == MorphOceanAssembler.Sea.DEEP_OCEAN) {
                        deepOnly.add(depth, dist);
                        if (!Double.isNaN(finalDepth) && finalDepth > 0) finalDeep.add(finalDepth, dist);
                        for (int b = 0; b < edges.length - 1; b++) {
                            if (dist >= edges[b] && dist < edges[b + 1]) {
                                sumDeep[b] += depth; cntDeep[b]++;
                                if (!Double.isNaN(finalDepth) && finalDepth > 0) {
                                    sumFinal[b] += finalDepth; cntFinal[b]++;
                                }
                                break;
                            }
                        }
                    } else if (seg == MorphOceanAssembler.Sea.SHALLOW_OCEAN) {
                        shallowOnly.add(depth, dist);
                    }
                }
                if (j % 100 == 0) System.out.println("row " + j);
            }

            System.out.println();
            System.out.printf(Locale.ROOT,
                    "sea() 分段计数：DEEP=%d  SHALLOW=%d  COAST=%d  LAND=%d%n",
                    nDeep, nShallow, nCoast, nLand);
            System.out.printf(Locale.ROOT, "水深范围：%.2f ~ %.2f 格%n", minD, maxD);

            System.out.println();
            System.out.println("=== 相关性 r(depth, dist) —— 生产路径实测 ===");
            System.out.printf(Locale.ROOT, "  %-32s %8s %10s%n", "样本集", "n", "r");
            for (Acc a : new Acc[]{allSea, deepOnly, finalAll, finalDeep, shallowOnly}) {
                System.out.printf(Locale.ROOT, "  %-32s %8d %+10.4f%n", a.name, a.n, a.r());
            }

            System.out.println();
            System.out.println("=== 纯深海段：按到边界距离分桶 ===");
            System.out.printf(Locale.ROOT, "  %-16s %8s %12s %14s%n",
                    "dist[wu]", "n", "内部格深度", "最终Y深度");
            for (int b = 0; b < edges.length - 1; b++) {
                if (cntDeep[b] == 0) continue;
                String label = edges[b + 1] > 1e8
                        ? String.format(Locale.ROOT, ">=%.0f", edges[b])
                        : String.format(Locale.ROOT, "%.0f-%.0f", edges[b], edges[b + 1]);
                double meanFinal = cntFinal[b] > 0 ? sumFinal[b] / cntFinal[b] : Double.NaN;
                System.out.printf(Locale.ROOT, "  %-16s %8d %12.2f %14.2f%n",
                        label, cntDeep[b], sumDeep[b] / cntDeep[b], meanFinal);
            }

            System.out.println();
            System.out.println("=== 判读 ===");
            double r = deepOnly.r();
            if (Double.isNaN(r)) {
                System.out.println("  深海样本不足，无法判定");
            } else if (Math.abs(r) < 0.10) {
                System.out.printf(Locale.ROOT,
                        "  纯深海段 r=%+.4f（|r|<0.10）⇒ 深度与离脊距离**基本无关**，"
                                + "年龄-深度关系确实缺失%n", r);
            } else {
                System.out.printf(Locale.ROOT,
                        "  纯深海段 r=%+.4f（|r|>=0.10）⇒ 已有可观梯度，叠加前需先解释其来源%n", r);
            }
            System.out.println("  ★ 本测量全部来自生产实例 oceanAssembler()，不是复刻。");
        }
    }

    /** 滑动累加相关统计。 */
    private static final class Acc {
        final String name;
        long n;
        double sx, sy, sxx, syy, sxy;

        Acc(String name) { this.name = name; }

        void add(double y, double x) {
            n++;
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y;
        }

        double r() {
            if (n < 10) return Double.NaN;
            double mx = sx / n, my = sy / n;
            double vx = sxx / n - mx * mx;
            double vy = syy / n - my * my;
            if (vx <= 1e-12 || vy <= 1e-12) return Double.NaN;
            return (sxy / n - mx * my) / Math.sqrt(vx * vy);
        }
    }
}
