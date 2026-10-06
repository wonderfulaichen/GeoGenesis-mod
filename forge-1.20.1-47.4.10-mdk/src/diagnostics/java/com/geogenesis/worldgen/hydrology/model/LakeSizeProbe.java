package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>湖泊尺寸谱 —— 「极小散点蓄水坑」的规模与水量</h1>
 *
 * <h2>用户观察</h2>
 * <p>「湖泊还有一些极小的散点蓄水坑」（补充："我又不是不允许内陆湖"）。</p>
 *
 * <h2>本探针要回答（决定该不该修、怎么修）</h2>
 * <ol>
 *   <li>湖泊按【8 连通分量】的尺寸分布：多少是"极小坑"（≤N 格）；</li>
 *   <li>极小坑占【湖泊格数】与【蓄水体积】的比例
 *       —— 若占比极低 ⇒ 过滤它们几乎不损失水量，是安全修法；</li>
 *   <li>按尺寸着色出图：用户可一眼核对"我看到的散点"是否就是这些小分量。</li>
 * </ol>
 *
 * <h2>★ 已定位的直接来源（代码级）</h2>
 * <p>{@link LakeWaterBalance} 中：</p>
 * <pre>
 *   double level = fed || !assumeFullWhenUnfed
 *           ? solveLevel(group, inflow, ...)
 *           : sill - 1e-9;          // ★ 无来水 ⇒ 直接当【满湖】
 * </pre>
 * <p>{@code assumeFullWhenUnfed} <b>默认 true</b> ⇒ <b>凡没被来水喂到的洼地，
 * 一律按"齐平鞍口"的满湖处理</b> ⇒ 每个小凹坑都变成一片水
 * ⇒ 正是"极小散点蓄水坑"。</p>
 * <p>该类<b>已有 setter</b>（{@code assumeFullWhenUnfed(boolean)}），
 * 故本探针可通过系统属性做 A/B，无需改判定逻辑。</p>
 *
 * <h2>参考项目对照</h2>
 * <ul>
 *   <li><b>FastFlow</b>（{@code _fastflow_text.txt}）：水体由
 *       <b>流量阈值</b>判定（"cells with water discharge above a specified threshold
 *       can be tagged as belonging to bodies of water"）⇒ 无来水 = 无流量 = 不是水体。</li>
 *   <li><b>FreeTerraForged</b>：湖 = 河流的一段加宽，且需<b>概率抽签</b>
 *       （{@code shouldWidenOnPlateau}）通过 ⇒ 不会在每个洼地都成湖。</li>
 *   <li><b>SimpleHydrology</b>：根本没有湖对象，"水"是 discharge 场的
 *       {@code erf} 软饱和渲染。</li>
 * </ul>
 * <p>⇒ 三个项目<b>没有一个</b>会把"没来水的洼地"当作满湖。我们的
 * {@code assumeFullWhenUnfed=true} 是独有行为，且与用户观察的散点坑吻合。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class LakeSizeProbe {

    private LakeSizeProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;
        // 第 6 参：tiny 阈值（≤ 该格数视为"散点坑"），默认 4
        int tinyT = args.length > 5 ? Integer.parseInt(args[5]) : 4;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] lake = r.lake(), sea = r.sea();
        double[] depth = r.depth();

        // ---- 湖泊 8 连通分量（排除海）----
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int[] stack = new int[cells];
        int[][] D8 = {{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};
        int comps = 0;
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !lake[k0] || sea[k0]) continue;
            int sp = 0; stack[sp++] = k0; comp[k0] = comps;
            while (sp > 0) {
                int k = stack[--sp];
                int i = k / n, j = k % n;
                for (int[] d : D8) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int nk = a * n + b;
                    if (comp[nk] < 0 && lake[nk] && !sea[nk]) { comp[nk] = comps; stack[sp++] = nk; }
                }
            }
            comps++;
        }
        int[] sz = new int[comps];
        double[] vol = new double[comps];
        for (int k = 0; k < cells; k++) {
            if (comp[k] < 0) continue;
            sz[comp[k]]++;
            vol[comp[k]] += Math.max(0, depth[k]);   // step=1 ⇒ 格面积=1
        }

        // ---- 尺寸分档 ----
        int[] edges = {1, 2, 4, 11, 31, 101, 501, Integer.MAX_VALUE};
        String[] names = {"1 格", "2-3 格", "4-10 格", "11-30 格",
                          "31-100 格", "101-500 格", "501+ 格"};
        int[] cnt = new int[names.length];
        long[] cellsIn = new long[names.length];
        double[] volIn = new double[names.length];
        double totalVol = 0;
        long totalLakeCells = 0;
        int tinyCount = 0; long tinyCells = 0; double tinyVol = 0;
        for (int c = 0; c < comps; c++) {
            int b = binOf(sz[c]);
            cnt[b]++; cellsIn[b] += sz[c]; volIn[b] += vol[c];
            totalVol += vol[c]; totalLakeCells += sz[c];
            if (sz[c] <= tinyT) { tinyCount++; tinyCells += sz[c]; tinyVol += vol[c]; }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 湖泊尺寸谱（排海）===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d  %dx%d%n%n",
                seed, cx, cz, radius, samples, n, n));
        log.append(String.format("湖泊分量总数=%d  湖泊格=%d  总蓄水体积=%.0f 块³%n%n",
                comps, totalLakeCells, totalVol));
        log.append(String.format("%-12s %8s %10s %12s %12s%n",
                "尺寸档", "分量数", "占比", "格数占比", "体积占比"));
        for (int b = 0; b < names.length; b++) {
            log.append(String.format("%-12s %8d %9.1f%% %11.1f%% %11.2f%%%n", names[b], cnt[b],
                    100.0 * cnt[b] / Math.max(1, comps),
                    100.0 * cellsIn[b] / Math.max(1, totalLakeCells),
                    100.0 * volIn[b] / Math.max(1e-9, totalVol)));
        }
        log.append(String.format("%n★ 散点坑（≤%d 格）：分量=%d (%.1f%%)  格=%.1f%%  体积=%.2f%%%n",
                tinyT, tinyCount, 100.0 * tinyCount / Math.max(1, comps),
                100.0 * tinyCells / Math.max(1, totalLakeCells),
                100.0 * tinyVol / Math.max(1e-9, totalVol)));
        log.append(String.format("   单坑平均体积=%.2f 块³（对照：1 个方块 = 1 块³）%n",
                tinyVol / Math.max(1, tinyCount)));
        log.append(String.format("%n判读：%s%n", tinyVol < 0.01 * totalVol
                ? "散点坑体积占比 <1% ⇒ 过滤它们几乎不损失水量 ⇒ 可安全修（参考："
                        + "FastFlow 以流量定义水体；FTF 以概率抽签限制成湖）"
                : "散点坑体积占比可观 ⇒ 过滤会显著改变水量平衡，需谨慎"));
        log.append(String.format("%n[basins] basins=%d dryBasins=%d overflow出流点=%d%n",
                r.lakeResult().basins().size(), r.lakeResult().dryBasins(),
                r.lakeResult().overflowAt().size()));

        // ★★ 盆地级诊断：inflow / overflow / 尺寸的关系（决定过滤判据）★★
        //   实测教训：`fed` 与 `inflow < 蒸发一格所需` 两个判据都【永不成立】
        //   ⇒ 必须先把每个盆地的真实 inflow 分布量出来，再谈用什么判据过滤。
        log.append(String.format("%n★ 盆地级：面积 vs 来水（用于选过滤判据）%n"));
        log.append(String.format("%8s %10s %14s %12s %10s%n",
                "面积格", "inflow", "evapOut", "outflow", "overflow"));
        java.util.List<LakeWaterBalance.Basin> bl = r.lakeResult().basins();
        double minInflow = Double.MAX_VALUE, maxInflow = 0;
        int fedCount = 0;
        int tinyBasins = 0, tinyFed = 0;
        for (LakeWaterBalance.Basin b : bl) {
            minInflow = Math.min(minInflow, b.inflow());
            maxInflow = Math.max(maxInflow, b.inflow());
            if (b.inflow() > 0) fedCount++;
            if (b.areaCells() <= tinyT) {
                tinyBasins++;
                if (b.inflow() > 0) tinyFed++;
            }
        }
        // 打印最小与最大的若干盆地
        int shown = 0;
        for (LakeWaterBalance.Basin b : bl) {
            if (shown >= 6) break;
            log.append(String.format("%8.0f %10.6f %14.6f %12.6f %10b%n",
                    b.areaCells(), b.inflow(), b.evaporationOut(), b.outflow(), b.overflow()));
            shown++;
        }
        log.append(String.format("%n  盆地总数=%d  有来水(inflow>0)=%d  无来水=%d%n",
                bl.size(), fedCount, bl.size() - fedCount));
        log.append(String.format("  inflow 范围 = [%.8f, %.8f]%n", minInflow, maxInflow));
        log.append(String.format("  ≤%d 格的盆地=%d  其中 inflow>0 的=%d%n",
                tinyT, tinyBasins, tinyFed));
        log.append("  ⇒ 若【所有盆地 inflow>0】⇒ 用 inflow 无法区分"
                + "（每盆地至少含自身格 ⇒ 恒有来水）\n");
        log.append("     此时能区分【散点坑】的判据只能是【几何量】：面积/体积/深度。\n");

        // ---- 出图：按尺寸着色 ----
        Path dir = Path.of("build/lakesize");
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (sea[k]) col = ViewSpec.SEA_COLOR;
                else if (lake[k] && comp[k] >= 0) {
                    int s = sz[comp[k]];
                    if (s <= tinyT) col = 0xFF2020;           // 散点坑 = 红
                    else if (s <= 10) col = 0xFF9020;         // 小 = 橙
                    else if (s <= 100) col = 0xFFE000;        // 中 = 黄
                    else col = ViewSpec.LAKE_COLOR;           // 大 = 蓝
                }
                else if (r.channel()[k]) col = ViewSpec.RIVER_COLOR;
                else col = ViewSpec.contourFixed(r.bed(), n, i, j);
                img.setRGB(i, j, col);
            }
        }
        ImageIO.write(img, "png", dir.resolve("lakesize.png").toFile());
        Files.writeString(dir.resolve("lakesize.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("lakesize.png").toAbsolutePath());
        System.out.println(String.format("图例：红=散点坑(<=%d格) 橙=小(<=10) 黄=中(<=100) 蓝=大(>100) 青=河 深蓝=海",
                tinyT));
    }

    private static int binOf(int s) {
        if (s <= 1) return 0;
        if (s <= 3) return 1;
        if (s <= 10) return 2;
        if (s <= 30) return 3;
        if (s <= 100) return 4;
        if (s <= 500) return 5;
        return 6;
    }
}