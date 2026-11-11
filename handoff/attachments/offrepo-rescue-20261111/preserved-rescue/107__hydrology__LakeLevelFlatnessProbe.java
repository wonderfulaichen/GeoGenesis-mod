package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 「湖面是否绝对水平」探针（2026-09-19，P3 判据 J1）。
 *
 * <h2>⛔⛔ 2026-09-19：本探针的判据【已被实测证伪，结论无效】⛔⛔</h2>
 * <p>本探针用 {@code cell.isLake} 筛"纯湖"，再按 4 邻连通分组求 σ。
 * 但实测（{@code GeoGenesisTerrain} 埋点 {@code LAKE-LEVEL-SPLIT}）发现：
 * <b>{@code isLake} 在三处被赋值，其中【河分支】和【精修洪泛】也设它</b>：</p>
 * <pre>
 *   :685  cell.isLake = flooded && spill >= seaLevel;             // 湖分支
 *   :713  cell.isLake = column.fillWater() && ... >= seaLevel;    // ★ 河分支也设
 *   :857  cell.isLake = level >= seaLevel;                        // ★ 精修洪泛也设
 * </pre>
 * <p>⇒ {@code isLake=true} 只表示"<b>水位 ≥ 海平面</b>"，<b>不表示"这是湖"</b>；
 * 河列同样为 true。因此本探针报出的"σ&gt;0 的纯湖"极可能是一条
 * <b>按流向正常下降的河</b>（对河而言 σ&gt;0 是物理正确的）。</p>
 * <p><b>实测反证</b>（同一 seed 的埋点输出）：真正走湖分支的那个湖
 * {@code spill∈[169.331,169.331] 跨度=0.000}、{@code 最终水位∈[169.331,169.331] 跨度=0.000}
 * ⇒ <b>湖面本来就是平的</b>。且 25000 列里只有 1 个湖。</p>
 * <p><b>⇒ 结论：「湖面不平」是【假缺陷】。</b>本探针保留仅作历史留痕；
 * <b>不得</b>再依据它的输出改动生产代码（P2/P3 的前提已被推翻）。</p>
 * <p>若将来要真正判定"湖面平否"，必须用【走湖分支】这一事实来筛列
 * （例：{@code column.lakeNode() != null} 的列），而不是 {@code cell.isLake}。</p>
 *
 * <h2>判据（不预设任何成因，纯观测）</h2>
 * <p>把窗口内所有<b>出水格</b>（{@code riverType != 0}）按<b>连通性</b>分组
 * （每个连通水体 = 一个湖/一段水），对每组的 {@code cell.riverSurfaceY} 统计：</p>
 * <pre>
 *   σ（标准差）必须 = 0  ⇒  同一水体水面处处同高（静止水面物理要求）
 *   σ &gt; 0            ⇒  ★ 湖面不平（用户实机判据）
 * </pre>
 * <p>⛔ 刻意<b>不</b>按"湖节点/域/半径"分组 —— 那些都是我的假设；
 * 连通性是无假设的物理事实。这样才不会又出现"用错判据报假缺陷"。</p>
 *
 * <pre>{@code gradlew runLakeLevelFlatnessProbe [-PprobeArgs="seed bx bz radius"]}</pre>
 */
public final class LakeLevelFlatnessProbe {

    private LakeLevelFlatnessProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx0 = args.length > 1 ? Integer.parseInt(args[1]) : -377;
        int bz0 = args.length > 2 ? Integer.parseInt(args[2]) : -335;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 128;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        int w = radius * 2 + 1;
        int cx0 = Math.floorDiv(bx0 - radius, 16), cz0 = Math.floorDiv(bz0 - radius, 16);
        int ncx = w / 16 + 2, ncz = w / 16 + 2;

        // 组装窗口：wet[idx] + level[idx]
        boolean[] wet = new boolean[w * w];
        boolean[] isLk = new boolean[w * w];
        double[] lev = new double[w * w];
        double sea = gt.heightCurve().seaLevelY();
        long wetCount = 0, seaCount = 0;
        for (int cj = 0; cj < ncz; cj++) {
            for (int ci = 0; ci < ncx; ci++) {
                int cx = cx0 + ci, cz = cz0 + cj;
                Cell[] cells = gt.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int bx = cx * 16 + lx, bz = cz * 16 + lz;
                        int i = bx - (bx0 - radius), j = bz - (bz0 - radius);
                        if (i < 0 || i >= w || j < 0 || j >= w) continue;
                        Cell c = cells[lx * 16 + lz];
                        if (c.riverType == 0) continue;
                        int k = j * w + i;
                        wet[k] = true;
                        lev[k] = c.riverSurfaceY;
                        isLk[k] = c.isLake;
                        wetCount++;
                        if (c.riverSurfaceY <= sea + 1e-9) seaCount++;
                    }
                }
            }
        }

        System.out.printf("=== LakeLevelFlatnessProbe seed=%d 块(%d,%d)±%d ===%n", seed, bx0, bz0, radius);
        System.out.printf("    出水格 = %d（其中水面 ≤ 海平面 %d —— 海洋/河口，另计）%n%n", wetCount, seaCount);

        // 连通分组（4 邻）
        boolean[] seen = new boolean[w * w];
        List<double[]> comps = new ArrayList<>();   // {size, min, max, mean, sd}
        int[] q = new int[w * w];
        int[][] NB = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int k0 = 0; k0 < w * w; k0++) {
            if (!wet[k0] || seen[k0]) continue;
            int head = 0, tail = 0;
            q[tail++] = k0;
            seen[k0] = true;
            List<Double> vals = new ArrayList<>();
            int lakeN = 0;
            while (head < tail) {
                int k = q[head++];
                vals.add(lev[k]);
                if (isLk[k]) lakeN++;
                int i = k % w, j = k / w;
                for (int[] d : NB) {
                    int ni = i + d[0], nj = j + d[1];
                    if (ni < 0 || ni >= w || nj < 0 || nj >= w) continue;
                    int nk = nj * w + ni;
                    if (!wet[nk] || seen[nk]) continue;
                    seen[nk] = true;
                    q[tail++] = nk;
                }
            }
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE, sum = 0;
            for (double v : vals) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; }
            double mean = sum / vals.size();
            double ss = 0;
            for (double v : vals) ss += (v - mean) * (v - mean);
            double sd = Math.sqrt(ss / vals.size());
            comps.add(new double[]{vals.size(), mn, mx, mean, sd, lakeN});
        }
        comps.sort((a, b) -> Double.compare(b[0], a[0]));

        System.out.printf("[J1] 连通水体分组（4 邻），共 %d 组；按大小取前 12：%n", comps.size());
        System.out.printf("    %-8s %-8s %-9s %-9s %-11s %-8s%n",
                "格数", "湖格", "最小水面", "最大水面", "★σ", "类型");
        System.out.println("    ---------------------------------------------------------------------");
        for (int i = 0; i < Math.min(12, comps.size()); i++) {
            double[] c = comps.get(i);
            boolean isSea = c[1] <= sea + 1e-9;
            String kind = (c[5] >= c[0] * 0.99) ? "纯湖" : (c[5] <= 0 ? "纯河" : "河湖混合");
            System.out.printf("    %-8.0f %-8.0f %-9.3f %-9.3f %-11.6f %-8s%s%n",
                    c[0], c[5], c[1], c[2], c[4], kind, isSea ? " ← 海" : "");
        }
        // ★ 判据只对【纯湖】成立：纯湖的水面必须处处同高（σ=0）。
        //   河（纯河/河湖混合）沿流向本就有坡度 ⇒ σ>0 正常，不计入。
        int badLake = 0, badMix = 0;
        for (double[] c : comps) {
            if (c[4] <= 1e-9 || c[1] <= sea + 1e-9 || c[0] < 20) continue;
            if (c[5] >= c[0] * 0.99) badLake++;
            else badMix++;
        }
        System.out.printf("%n    ★ 水面不平的【纯湖】水体（格数≥20 且 σ>0） = %d 组  ← J1 只认这个%n", badLake);
        System.out.printf("      含河流的水体（σ>0 属正常：河面沿流向本就变化）  = %d 组%n", badMix);
        System.out.println("    判读：纯湖 σ 应为 0；σ>0 ⇒ 湖面不平成立（P3 要修的就是它）。");
    }
}
