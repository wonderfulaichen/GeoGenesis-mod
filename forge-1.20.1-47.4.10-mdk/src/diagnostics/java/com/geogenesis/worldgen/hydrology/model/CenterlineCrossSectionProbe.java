package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/**
 * <h1>中心线 + 横断面 vs 旧的"逐格过门槛"：同参对照</h1>
 *
 * <h2>判据（方案 §5 验收合同第 4 条：形态）</h2>
 * <ol>
 *   <li><b>河段数 / 骨架连通性</b>：中心线不得比证据掩码更碎
 *       （细化是保连通变换 ⇒ 分量数不得增加）；</li>
 *   <li><b>抹开</b>：新掩码的"5×5 密度≥0.7 的块格占比"必须【显著低于】旧掩码
 *       （旧口径下的"抹开块"就是用户圈出的成块现象）；</li>
 *   <li><b>散点</b>：新掩码的孤立小分量不得比旧掩码增加（防止"治抹开又成散点"）；</li>
 *   <li><b>湿宽一致性</b>：新掩码的横向湿宽应与设计全宽同量级
 *       （逐段比较"实测湿宽 / 设计全宽"的比值分布）；</li>
 *   <li><b>确定性</b>：同参数重跑逐位相同。</li>
 * </ol>
 *
 * <p>本探针只做【对照与度量】，不改 {@link HydrologyPipeline} 的产出 ——
 * 是否切换默认路径由用户决定（方案 §6：产出会变，须先确认）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class CenterlineCrossSectionProbe {

    /** "抹开"判据：5×5 邻域内河道占比 ≥ 此值（与 {@code ChannelSmearProbe} 同口径，便于对照）。 */
    private static final double SMEAR_DENSITY = 0.7;

    private CenterlineCrossSectionProbe() { }

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

        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;
        boolean[] sea = r.sea(), oldMask = r.channel();
        double[] bed = r.bed(), flux = r.discharge();

        var params = RiverCenterlines.Params.defaults();
        var cl = RiverCenterlines.build(bed, flux, oldMask, sea, n, n, params);
        boolean[] newMask = cl.channel;

        // ---- 判据 1：连通性（细化不得增加分量） ----
        int oldComp = components(oldMask, n);
        int newComp = components(newMask, n);

        // ---- 判据 2/3：抹开 与 散点谱 ----
        double oldSmear = smearShare(oldMask, n);
        double newSmear = smearShare(newMask, n);
        int[] oldSizes = componentSizes(oldMask, n);
        int[] newSizes = componentSizes(newMask, n);
        int oldTiny = countAtMost(oldSizes, 10), newTiny = countAtMost(newSizes, 10);

        // ---- 判据 4：湿宽 vs 设计全宽 ----
        //   对每个河段取其中间节点，沿法向量实际连续湿宽（同掩码内、过该节点的最长横跨）
        StringBuilder sb = new StringBuilder();
        double ratioSum = 0; int ratioN = 0; int wide = 0, narrow = 0;
        for (var reach : cl.reaches) {
            if (reach.nodeCount() < 3) continue;
            int mid = reach.nodeCount() / 2;
            int k = reach.cell[mid];
            int design = Math.max(1, (int) Math.round(reach.width[mid]));
            int measured = crossSectionWidth(newMask, n, k);
            if (measured <= 0) continue;
            double ratio = measured / (double) design;
            ratioSum += ratio; ratioN++;
            if (ratio > 1.5) wide++;
            if (ratio < 0.5) narrow++;
        }
        double meanRatio = ratioN > 0 ? ratioSum / ratioN : 0;

        // ---- 判据 5：确定性 ----
        var cl2 = RiverCenterlines.build(bed, flux, oldMask, sea, n, n, params);
        boolean deterministic = true;
        for (int k = 0; k < cells; k++) {
            if (cl.channel[k] != cl2.channel[k]
                    || cl.normalizedDistance[k] != cl2.normalizedDistance[k]) {
                deterministic = false; break;
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 中心线 + 横断面 vs 逐格过门槛（同参对照）===\n");
        log.append(String.format("seed=%d centre=(%d,%d) r=%d samples=%d  %dx%d%n%n",
                seed, cx, cz, radius, samples, n, n));
        log.append(String.format("旧掩码（flux≥门槛）：河道格=%d 分量=%d 抹开占比=%.2f%%%n",
                count(oldMask), oldComp, 100 * oldSmear));
        log.append(String.format("新掩码（中心线+全宽）：河道格=%d 分量=%d 抹开占比=%.2f%%%n",
                count(newMask), newComp, 100 * newSmear));
        log.append(String.format("  河段数=%d  骨架分量=%d%n", cl.reachCount(), compCount(cl)));
        log.append(String.format("%n[判据]%n"));
        boolean c1 = newComp <= oldComp;
        log.append(String.format("  ① 连通性不恶化：分量 %d → %d  %s%n", oldComp, newComp,
                c1 ? "PASS" : "FAIL"));
        boolean c2 = newSmear < oldSmear * 0.5;
        log.append(String.format("  ② 抹开显著下降（阈值 < 旧值的 50%%）：%.2f%% → %.2f%%  %s%n",
                100 * oldSmear, 100 * newSmear, c2 ? "PASS" : "FAIL"));
        boolean c3 = newTiny <= oldTiny;
        log.append(String.format("  ③ 散点不增加：≤10格分量 %d → %d  %s%n",
                oldTiny, newTiny, c3 ? "PASS" : "FAIL"));
        boolean c4 = ratioN > 0 && meanRatio > 0.5 && meanRatio < 2.0;
        log.append(String.format("  ④ 湿宽 vs 设计全宽：均值比=%.2f（n=%d, >1.5 的 %d, <0.5 的 %d）  %s%n",
                meanRatio, ratioN, wide, narrow, c4 ? "PASS" : "FAIL"));
        log.append(String.format("  ⑤ 确定性：%s%n", deterministic ? "PASS（逐位相同）" : "FAIL"));
        boolean all = c1 && c2 && c3 && c4 && deterministic;
        log.append(String.format("%n判定：%s%n", all ? "ALL PASS" : "★ 有判据 FAIL"));

        // ---- 出图（三图并排难以比较，故做成独立两张 + 一张差异图） ----
        Path dir = Path.of("build/centerline");
        Files.createDirectories(dir);
        writeImage(dir.resolve("old_mask.png"), n, oldMask, sea, r.lake(), bed, null);
        // 差异图：旧有新无 = 红（被移除的抹开），新有旧无 = 蓝（新增的湿宽）
        writeImage(dir.resolve("new_mask.png"), n, newMask, sea, r.lake(), bed, null);
        boolean[] removed = new boolean[cells], added = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            if (oldMask[k] && !newMask[k]) removed[k] = true;
            if (!oldMask[k] && newMask[k]) added[k] = true;
        }
        writeImage(dir.resolve("diff.png"), n, oldMask, sea, r.lake(), bed,
                new Diff(removed, added));
        log.append(String.format("%n差异图：红=被移除（多为抹开块） 蓝=新增（横断面展开）%n"));
        log.append(String.format("红格=%d 蓝格=%d%n", count(removed), count(added)));

        Files.writeString(dir.resolve("centerline.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("new_mask.png").toAbsolutePath());
        System.out.println("IMAGE=" + dir.resolve("diff.png").toAbsolutePath());
        System.exit(all ? 0 : 1);
    }

    private record Diff(boolean[] removed, boolean[] added) { }

    private static int count(boolean[] a) { int c = 0; for (boolean b : a) if (b) c++; return c; }

    private static int countAtMost(int[] sizes, int limit) {
        int c = 0;
        for (int s : sizes) if (s > 0 && s <= limit) c++;
        return c;
    }

    private static int compCount(RiverCenterlines.Result cl) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (var reach : cl.reaches) set.add(reach.component);
        return set.size();
    }

    /** 8 连通分量数。 */
    private static int components(boolean[] mask, int n) {
        return componentSizes(mask, n).length;
    }

    /** 8 连通分量尺寸（升序，含 0 长度的占位不影响计数逻辑）。 */
    private static int[] componentSizes(boolean[] mask, int n) {
        int cells = n * n;
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        ArrayList<Integer> sizes = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int c = 0;
        for (int s = 0; s < cells; s++) {
            if (!mask[s] || comp[s] >= 0) continue;
            int size = 0;
            q.add(s); comp[s] = c;
            while (!q.isEmpty()) {
                int k = q.removeFirst(); size++;
                int x = k / n, z = k % n;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                    int a = x + d[0], b = z + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int kk = a * n + b;
                    if (mask[kk] && comp[kk] < 0) { comp[kk] = c; q.addLast(kk); }
                }
            }
            sizes.add(size);
            c++;
        }
        int[] out = new int[sizes.size()];
        for (int i = 0; i < out.length; i++) out[i] = sizes.get(i);
        java.util.Arrays.sort(out);
        return out;
    }

    /** 5×5 邻域河道占比 ≥ {@link #SMEAR_DENSITY} 的格占全部河道格的比例。 */
    private static double smearShare(boolean[] mask, int n) {
        int total = 0, smear = 0;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                int k = x * n + z;
                if (!mask[k]) continue;
                total++;
                int cnt = 0, tot = 0;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int a = x + dx, b = z + dz;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        tot++;
                        if (mask[a * n + b]) cnt++;
                    }
                }
                if (tot > 0 && cnt / (double) tot >= SMEAR_DENSITY) smear++;
            }
        }
        return total > 0 ? smear / (double) total : 0;
    }

    /** 过该格的水平横跨宽度（同掩码内连续长度）——用于与设计全宽粗比。 */
    private static int crossSectionWidth(boolean[] mask, int n, int k) {
        int x = k / n, z = k % n;
        int left = 0, right = 0;
        for (int i = x - 1; i >= 0 && mask[i * n + z]; i--) left++;
        for (int i = x + 1; i < n && mask[i * n + z]; i++) right++;
        int horiz = left + right + 1;
        int up = 0, down = 0;
        for (int j = z - 1; j >= 0 && mask[x * n + j]; j--) up++;
        for (int j = z + 1; j < n && mask[x * n + j]; j++) down++;
        int vert = up + down + 1;
        return Math.min(horiz, vert);   // 取较小者：真河宽不会同时在两个方向都很大
    }

    private static void writeImage(Path path, int n, boolean[] mask, boolean[] sea,
                                   boolean[] lake, double[] bed, Diff diff) throws Exception {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int col;
                if (diff != null && diff.removed()[k]) col = 0xFF2020;
                else if (diff != null && diff.added()[k]) col = 0x2040FF;
                else if (sea[k]) col = ViewSpec.SEA_COLOR;
                else if (lake[k]) col = ViewSpec.LAKE_COLOR;
                else if (mask[k]) col = ViewSpec.RIVER_COLOR;
                else col = ViewSpec.contourFixed(bed, n, i, j);
                img.setRGB(i, j, col);
            }
        }
        ImageIO.write(img, "png", path.toFile());
    }
}
