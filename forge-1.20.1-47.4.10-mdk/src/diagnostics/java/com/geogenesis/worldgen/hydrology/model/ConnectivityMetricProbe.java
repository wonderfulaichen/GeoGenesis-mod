package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * <h1>连通性【度量口径】检验：4 连通 vs 8 连通</h1>
 *
 * <h2>假设（本轮最后一个候选，能一次解释四个已失败假设）</h2>
 * <p>河流沿 {@code parent} 链延伸，而 {@code parent} 由 8 邻域得出（含对角）。
 * 而 {@link CanonicalViewProbe} 的连通性度量用的是 <b>4 连通</b>
 * ⇒ <b>一条对角走向的细河，在 4 连通下会被数成"每格孤立"</b>。</p>
 *
 * <p><b>为什么这个假设值得检验（它同时解释四个已证伪的假设）</b>：</p>
 * <ul>
 *   <li>假设A/B（注入残留 / 平坦抖动）失败中 81% 归因"其它" ⇒ 若是对角锯齿，
 *       它们当然既不靠湖也不靠溢口、也不在平坦区；</li>
 *   <li>出现在【所有地形类型】上 ⇒ 锯齿是渲染/度量问题，与地形无关；</li>
 *   <li>平滑优于原始（159 vs 486）⇒ 平滑让河更宽 ⇒ 对角锯齿更少；</li>
 *   <li>加样本数无效 ⇒ 是几何（走位）问题，不是噪声。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <pre>
 *   [1] 4 连通分量数  vs  8 连通分量数
 *       → 若 8 连通把分量数砍掉一个数量级 ⇒ 强证据：原"碎块"多为对角锯ge
 *   [2] 孤立块（≤2 格）在两种口径下的数量
 *   [3] 河道格的【对角步占比】：相邻 channel 格中，对角相邻的比例
 *       → 若很高 ⇒ 河流大量沿对角走 ⇒ 1 像素渲染必然成"点状"
 * </pre>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class ConnectivityMetricProbe {

    private ConnectivityMetricProbe() { }

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

        // ---- [1] 4 连通 vs 8 连通 ----
        int comps4 = countComponents(ch, n, false);
        int comps8 = countComponents(ch, n, true);

        // ---- [2] 孤立块（≤2 格）在两种口径下 ----
        int[] s4 = componentSizes(ch, n, false);
        int[] s8 = componentSizes(ch, n, true);
        int iso4 = 0, iso8 = 0;
        for (int s : s4) if (s <= 2) iso4++;
        for (int s : s8) if (s <= 2) iso8++;

        // ---- [3] 对角步占比：河道格与其【河道邻居】的相邻关系 ----
        long orthoPairs = 0, diagPairs = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (!ch[k]) continue;
                // 正交邻居（只数右/下，避免重复）
                if (i + 1 < n && ch[(i + 1) * n + j]) orthoPairs++;
                if (j + 1 < n && ch[i * n + j + 1]) orthoPairs++;
                // 对角邻居（只数右下/左下）
                if (i + 1 < n && j + 1 < n && ch[(i + 1) * n + j + 1]) diagPairs++;
                if (i + 1 < n && j - 1 >= 0 && ch[(i + 1) * n + j - 1]) diagPairs++;
            }
        }
        long totalPairs = orthoPairs + diagPairs;

        StringBuilder log = new StringBuilder();
        log.append("=== 连通性度量口径检验（4 连通 vs 8 连通）===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d 样本=%d%n%n", seed, cx, cz, radius, samples));
        log.append(String.format("[1] 连通分量:  4连通=%d   8连通=%d   ⇒ 比值 %.2fx%n",
                comps4, comps8, comps4 / (double) Math.max(1, comps8)));
        log.append(String.format("[2] 孤立块(<=2格):  4连通=%d   8连通=%d%n", iso4, iso8));
        log.append(String.format("[3] 河道相邻对:  正交=%d  对角=%d  对角占比=%.1f%%（共 %d）%n",
                orthoPairs, diagPairs, 100.0 * diagPairs / Math.max(1, totalPairs), totalPairs));
        log.append(String.format("%n判读%n"));
        if (comps4 > 3 * Math.max(1, comps8)) {
            log.append(String.format("  ★ 8 连通把分量数从 %d 降到 %d ⇒ 【原先的「碎块」大量是对角锯齿】，%n",
                    comps4, comps8));
            log.append("    不是模型缺陷，而是【1 像素渲染 + 4 连通度量】的假象。\n");
            log.append("    ⇒ 之前所有【点状假河】的归因方向（注入/门槛/平滑）都建立在错误度量上。\n");
        } else {
            log.append("  ⇒ 两种口径差别不大 ⇒ 碎块是【真实断裂】，不是对角锯齿。\n");
            log.append("    ⇒ 需要回到模型层继续查（本假设否决）。\n");
        }
        if (totalPairs > 0 && diagPairs * 100.0 / totalPairs > 30) {
            log.append(String.format("  ★ 对角步占 %.1f%% ⇒ 河流大量沿对角走 ⇒ 1 像素渲染必然呈【点状/虚线】。%n",
                    100.0 * diagPairs / totalPairs));
        }

        java.nio.file.Path dir = java.nio.file.Path.of("build/metric");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("connectivity_metric.txt"), log);
        System.out.println(log);
    }

    private static int countComponents(boolean[] ch, int n, boolean eight) {
        return componentSizes(ch, n, eight).length;
    }

    private static int[] componentSizes(boolean[] ch, int n, boolean eight) {
        int cells = n * n;
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int[] stack = new int[cells];
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        int[][] dirs = eight
                ? new int[][]{{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}}
                : new int[][]{{-1,0},{1,0},{0,-1},{0,1}};
        int c = 0;
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !ch[k0]) continue;
            int sp = 0; stack[sp++] = k0; comp[k0] = c;
            int size = 0;
            while (sp > 0) {
                int k = stack[--sp]; size++;
                int i = k / n, j = k % n;
                for (int[] d : dirs) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int nk = a * n + b;
                    if (comp[nk] < 0 && ch[nk]) { comp[nk] = c; stack[sp++] = nk; }
                }
            }
            sizes.add(size);
            c++;
        }
        int[] out = new int[sizes.size()];
        for (int t = 0; t < out.length; t++) out[t] = sizes.get(t);
        return out;
    }
}