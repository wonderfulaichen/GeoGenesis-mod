package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>「点状青色」到底是什么 —— 分量尺寸全谱 + 尺寸分类着色</h1>
 *
 * <h2>为什么必须重做（我的错误）</h2>
 * <p>上一轮我报「点状假河 105→6（-94%）」，判据是<b>孤立块 ≤2 格</b>。
 * 但用户看渲染图后指出：<b>图中仍明显有一堆点状青色</b>（原话：
 * 「假个鬼啊，图中明显有一堆点状的青色。你没看到吗？」）。</p>
 * <p>⇒ <b>我的阈值选错了</b>：可见的"点"是 <b>3~10 格左右的小分量</b>，
 * 不是 ≤2 格。只统计 ≤2 格等于把问题定义掉了。</p>
 *
 * <h2>本探针做什么（不再用单一阈值）</h2>
 * <ol>
 *   <li><b>分量尺寸全谱直方图</b>：1 / 2 / 3-5 / 6-10 / 11-20 / 21-50 / 51-100 / 101+，
 *       每档报【分量数】与【格数占比】—— 直接看出"点多不多"；</li>
 *   <li><b>小分量的地形位置</b>：高程带 / 坡度 / 是否紧邻湖 —— 判断它们出现在哪；</li>
 *   <li><b>按尺寸分类着色出图</b>：小分量（&lt;20 格）标<b>红</b>，
 *       中大分量标青 ⇒ 用户一眼可核对我统计的"点"是否就是他看到的那些。</li>
 * </ol>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class ChannelDotProbe {

    private ChannelDotProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;
        int smallThreshold = args.length > 5 ? Integer.parseInt(args[5]) : 20;

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

        // ---- 8 连通分量 ----
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int[] stack = new int[cells];
        int[][] D8 = {{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};
        int comps = 0;
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !ch[k0]) continue;
            int sp = 0; stack[sp++] = k0; comp[k0] = comps;
            while (sp > 0) {
                int k = stack[--sp];
                int i = k / n, j = k % n;
                for (int[] d : D8) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int nk = a * n + b;
                    if (comp[nk] < 0 && ch[nk]) { comp[nk] = comps; stack[sp++] = nk; }
                }
            }
            comps++;
        }
        int[] sz = new int[comps];
        for (int k = 0; k < cells; k++) if (comp[k] >= 0) sz[comp[k]]++;

        // ---- [1] 尺寸全谱直方图 ----
        int[] binEdges = {1, 2, 3, 6, 11, 21, 51, 101, Integer.MAX_VALUE};
        String[] binNames = {"1 格", "2 格", "3-5 格", "6-10 格", "11-20 格",
                             "21-50 格", "51-100 格", "101+ 格"};
        int[] binComps = new int[binNames.length];
        long[] binCells = new long[binNames.length];
        int totalCh = 0;
        for (int c = 0; c < comps; c++) {
            int b = 0;
            while (b < binEdges.length - 1 && sz[c] >= binEdges[b + 1]) b++;
            // binEdges 是上界：1 → [1,1]; 2 → [2,2]; 3 → [3,5]...
            // 简化：按 sz 落桶
            b = szToBin(sz[c]);
            binComps[b]++;
            binCells[b] += sz[c];
            totalCh += sz[c];
        }

        // ---- [2] 小分量的地形位置 ----
        int smallComps = 0, smallCells = 0;
        double sumElev = 0, sumSlope = 0; int cnt = 0;
        int smallNearLake = 0;
        for (int c = 0; c < comps; c++) {
            if (sz[c] >= smallThreshold) continue;
            smallComps++; smallCells += sz[c];
            for (int k = 0; k < cells; k++) {
                if (comp[k] != c) continue;
                sumElev += r.bed()[k];
                int i = k / n, j = k % n;
                if (i > 0 && i < n - 1 && j > 0 && j < n - 1) {
                    double dx = (r.bed()[(i + 1) * n + j] - r.bed()[(i - 1) * n + j]) / 2;
                    double dz = (r.bed()[i * n + j + 1] - r.bed()[i * n + j - 1]) / 2;
                    sumSlope += Math.hypot(dx, dz);
                }
                cnt++;
                for (int[] d : D8) {
                    int a = i + d[0], b2 = j + d[1];
                    if (a < 0 || b2 < 0 || a >= n || b2 >= n) continue;
                    if (r.lake()[a * n + b2]) { smallNearLake++; break; }
                }
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 「点状青色」尺寸全谱分析 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d%n",
                seed, cx, cz, radius, samples));
        log.append(String.format("河道格=%d  8连通分量=%d%n%n", totalCh, comps));
        log.append("【[1] 分量尺寸全谱】（用户看到的「点」应落在小尺寸档）\n");
        log.append(String.format("  %-12s %8s %10s %10s%n", "尺寸档", "分量数", "占比", "格数占比"));
        for (int b = 0; b < binNames.length; b++) {
            log.append(String.format("  %-12s %8d %9.1f%% %9.1f%%%n", binNames[b],
                    binComps[b], 100.0 * binComps[b] / Math.max(1, comps),
                    100.0 * binCells[b] / Math.max(1, totalCh)));
        }
        int smallBins = binComps[0] + binComps[1] + binComps[2] + binComps[3];
        int mediumBins = binComps[4] + binComps[5];
        log.append(String.format("%n  ⇒ ≤10 格的分量 = %d (%.1f%%)  ← 视觉上就是「点/短虚线」%n",
                smallBins, 100.0 * smallBins / Math.max(1, comps)));
        log.append(String.format("     11~50 格 = %d (%.1f%%)  ← 短线段%n",
                mediumBins, 100.0 * mediumBins / Math.max(1, comps)));

        log.append(String.format("%n【[2] 小分量（<%d 格）的地形位置】%n", smallThreshold));
        log.append(String.format("  分量数=%d  格数=%d (占河道 %.1f%%)%n",
                smallComps, smallCells, 100.0 * smallCells / Math.max(1, totalCh)));
        log.append(String.format("  平均高程=%.1f 块  平均坡度=%.4f%n",
                sumElev / Math.max(1, cnt), sumSlope / Math.max(1, cnt)));
        log.append(String.format("  小分量格中紧邻湖的比例=%.1f%%%n",
                100.0 * smallNearLake / Math.max(1, cnt)));
        // 对照：全河道平均高程/坡度
        double allElev = 0, allSlope = 0; int allCnt = 0;
        for (int k = 0; k < cells; k++) {
            if (!ch[k]) continue;
            allElev += r.bed()[k];
            int i = k / n, j = k % n;
            if (i > 0 && i < n - 1 && j > 0 && j < n - 1) {
                double dx = (r.bed()[(i + 1) * n + j] - r.bed()[(i - 1) * n + j]) / 2;
                double dz = (r.bed()[i * n + j + 1] - r.bed()[i * n + j - 1]) / 2;
                allSlope += Math.hypot(dx, dz);
            }
            allCnt++;
        }
        log.append(String.format("  (对照) 全河道 平均高程=%.1f 块  平均坡度=%.4f%n",
                allElev / Math.max(1, allCnt), allSlope / Math.max(1, allCnt)));
        log.append(String.format("  ⇒ 小分量平均高程 %s 全河道；坡度 %s%n",
                (sumElev / Math.max(1, cnt)) > (allElev / Math.max(1, allCnt)) ? "高于" : "低于",
                (sumSlope / Math.max(1, cnt)) > (allSlope / Math.max(1, allCnt))
                        ? "更陡（山地特征）" : "更缓（平原特征）"));

        // ---- [3] 按尺寸分类着色出图 ----
        Path dir = Path.of("build/dots");
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (double v : r.bed()) { hMin = Math.min(hMin, v); hMax = Math.max(hMax, v); }
        double band = Math.max(4.0, (hMax - hMin) / 24.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (r.sea()[k]) col = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) col = ViewSpec.LAKE_COLOR;
                else if (comp[k] >= 0) {
                    int s = sz[comp[k]];
                    if (s < smallThreshold) col = 0xFF2020;        // ★ 小分量 = 红（待查的「点」）
                    else if (s < 51) col = 0xFFFF00;               // 中 = 黄
                    else col = 0x00E5D0;                           // 大 = 青（正常河）
                }
                else col = ViewSpec.contourFixed(r.bed(), n, i, j);
                img.setRGB(i, j, col);
            }
        }
        ImageIO.write(img, "png", dir.resolve("dots.png").toFile());
        Files.writeString(dir.resolve("dots.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("dots.png").toAbsolutePath());
        System.out.println("图例：红=<" + smallThreshold + "格(用户说的「点」) 黄=20~50格 青=51+格(正常河) 蓝=湖 深蓝=海");
    }

    private static int szToBin(int s) {
        if (s <= 1) return 0;
        if (s == 2) return 1;
        if (s <= 5) return 2;
        if (s <= 10) return 3;
        if (s <= 20) return 4;
        if (s <= 50) return 5;
        if (s <= 100) return 6;
        return 7;
    }
}