package com.geogenesis.worldgen.terrain;

/**
 * 类型场【轴向对齐】诊断探针（2026-09-16 新建）。
 *
 * <h2>为何要补这个探针（一段被遗失的门禁）</h2>
 * <p>CHANGELOG / AGENTS 里长期记着一条已知缺陷：</p>
 * <pre>
 *   量化（新增 [G] 探针）：窗口 (-1500,1500) 最长水平直段 560 wu（0.19×），
 *   且正好落在网格中垂线 z=3204；另一窗口方向比 2.35 …
 * </pre>
 * <p>但全仓搜索 <b>560 / runLength / 水平直段</b> 只能命中 CHANGELOG 与 AGENTS，
 * <b>那个 [G] 探针根本不在仓库里</b> —— 指标被记录了下来，测它的工具却没留下。
 * 后果：<b>该缺陷从那以后再没有被客观测量过</b>（既无法确认现状，
 * 也无法验证任何修复是否真的生效）。本探针把这条门禁补回来。</p>
 *
 * <h2>度量方法：轴向 / 对角 的连续段长之比</h2>
 * <p>规则网格 Voronoi 的细胞边界是格点的<b>中垂线</b> ⇒ 在轴向（+x / +z）上会出现
 * 很长的"同一主导类型"连续段；而有机（不规则）边界在各方向上应当差不多。
 * 故统计 4 个方向（+x、+z、两条对角）上"主导类型不变"的<b>最长连续段</b>，
 * 取 轴向最长 / 对角最长 作为<b>方向比</b>：</p>
 * <ul>
 *   <li>≈1.0 ⇒ 各向同性（边界有机）；</li>
 *   <li>明显 &gt;1 ⇒ 边界被轴向直线主导（本缺陷）。</li>
 * </ul>
 * <p>⚠ 段长按<b>实际距离（块）</b>计 —— 对角每步是 {@code step×√2} 块，
 * 若不换算就会系统性低估对角段长、虚增方向比。</p>
 *
 * <h2>判据是【锚定】而非【达标】</h2>
 * <p>本缺陷至今<b>未修</b>（当年试修"种子抖动"虽把直段 560→120，却改坏排水格局
 * 导致 chunk 边界出现 12.8 块水面落差而被<b>全量回退</b>）。故这里把方向比
 * <b>锚定</b>在当前实测值附近：现在会通过，但一旦有人把它改得更糟就立刻报警。
 * <b>真正的目标值（≤1.5）尚未达成</b>，如实记录在输出与 AGENTS 里。</p>
 *
 * <pre>{@code gradlew runTypeAxisProbe [-PprobeArgs="seed windowBlocks step"]}</pre>
 */
public final class TypeAxisProbe {

    private TypeAxisProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int window = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        TerrainParams p = TerrainParams.defaults();
        ContinentField cf = new ContinentField(p);
        TypeLandShape tls = new TypeLandShape(p);
        cf.seed(seed);
        tls.seed(seed);

        int G = window / step;
        int x0 = -window / 2;
        int z0 = -window / 2;

        // 采样主导类型
        int[][] dom = new int[G][G];
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                var blend = tls.sampleBlend(x0 + i * step, z0 + j * step);
                dom[i][j] = TypeLandShape.dominantFromWeights(blend.typeWeights).ordinal();
            }
        }

        // ★ 必须量【边界线】的直线段，而不是"同一类型的连续区域"：
        //   后者的长度由细胞尺寸（CELL_SPACING=400）决定，与轴向无关 ——
        //   初版就是这么测的，结果 4 个方向都是 2000~3000 块、比值≈1，
        //   看起来"没有轴向问题"，实则是度量选错了对象（差点据此误判缺陷已消失）。
        //   边界像素定义：dom[p] != dom[p+n]（n 为法向）；直线段沿方向 d 连续。
        int maxH = maxBoundaryRun(dom, G, 1, 0, 0, 1);      // 水平边界（法向 +z），沿 +x
        int maxV = maxBoundaryRun(dom, G, 0, 1, 1, 0);      // 垂直边界（法向 +x），沿 +z
        int maxD1 = maxBoundaryRun(dom, G, 1, 1, 1, -1);    // 对角 ↘ 边界
        int maxD2 = maxBoundaryRun(dom, G, 1, -1, 1, 1);    // 对角 ↗ 边界

        double diagFactor = Math.sqrt(2.0);                       // 对角每步的实际块数
        double axisBlocks = Math.max(maxH, maxV) * (double) step;
        double diagBlocks = Math.max(maxD1, maxD2) * step * diagFactor;
        double ratio = diagBlocks > 0 ? axisBlocks / diagBlocks : Double.NaN;

        System.out.printf("=== TypeAxisProbe seed=%d window=%d step=%d grid=%d×%d ===%n",
                seed, window, step, G, G);
        System.out.printf("[1] 最长【边界直线段】（单位=块）:%n");
        System.out.printf("    水平边界 = %6.0f 块     垂直边界 = %6.0f 块%n",
                maxH * (double) step, maxV * (double) step);
        System.out.printf("    对角 ↘   = %6.0f 块     对角 ↗   = %6.0f 块%n",
                maxD1 * step * diagFactor, maxD2 * step * diagFactor);
        System.out.printf("[2] 方向比 = 轴向最长 / 对角最长 = %.2f%n", ratio);
        System.out.printf("    轴向最长段占窗口 = %.3f×（历史记录 0.19× = 560/%d）%n",
                axisBlocks / window, window);

        // 判据：锚定（防变差），非达标（未修）
        //   ★ 真正的目标是 ≤1.5（各向同性）；当前基线明显更高，属【已记录未修】缺陷。
        boolean pass1 = !Double.isNaN(ratio) && ratio <= 2.6;
        System.out.printf("%n[判据1] 方向比锚定 ≤ 2.6（防变差；目标 ≤1.5 尚未达成）: %s"
                        + "（实测 %.2f）%n", pass1 ? "PASS" : "FAIL", ratio);
        System.out.println("     说明：>1.5 说明边界仍被轴向直线主导（规则网格 Voronoi 中垂线所致）。");
        System.out.printf("     历史基线：最长水平直段 560 wu、方向比 2.35（窗口另取）；%n");
        System.out.println("     当年试修『种子抖动』可降到 120 wu，但因改坏排水（chunk 边界 12.8 块水面落差）已回退。");
        System.out.println(pass1 ? "ALL PASS" : "FAILURES");
    }

    /**
     * 沿方向 {@code d} 统计【边界像素】连续成线的最长段（格数）。
     *
     * <p>边界像素：{@code dom[p] != dom[p+n]}（{@code n} 为法向）。
     * 例如"水平边界"取 {@code n=(0,1)}、{@code d=(1,0)} ⇒ 得到沿 x 延伸的、
     * 位于某个 {@code z} 上的水平边界直线段 —— 即历史指标"最长水平直段"。</p>
     *
     * <p>只在<b>段的起点</b>展开（前一格不是边界或越界）⇒ 每格最多被访问两次，O(G²)。</p>
     */
    private static int maxBoundaryRun(int[][] g, int G, int dx, int dz, int nx, int nz) {
        // 边界像素标记
        boolean[][] b = new boolean[G][G];
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                int ni = i + nx, nj = j + nz;
                b[i][j] = ni >= 0 && ni < G && nj >= 0 && nj < G && g[ni][nj] != g[i][j];
            }
        }
        int best = 0;
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                if (!b[i][j]) continue;
                int pi = i - dx, pj = j - dz;
                boolean isStart = pi < 0 || pi >= G || pj < 0 || pj >= G || !b[pi][pj];
                if (!isStart) continue;
                int len = 1, ci = i, cj = j;
                while (true) {
                    int ni = ci + dx, nj = cj + dz;
                    if (ni < 0 || ni >= G || nj < 0 || nj >= G || !b[ni][nj]) break;
                    len++;
                    ci = ni;
                    cj = nj;
                }
                if (len > best) best = len;
            }
        }
        return best;
    }
}
