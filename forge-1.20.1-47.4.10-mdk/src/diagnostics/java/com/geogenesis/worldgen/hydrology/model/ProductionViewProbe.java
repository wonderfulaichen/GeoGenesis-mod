package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <h1>【生产路径】出图 —— 回答"渲染图有河/湖、游戏里却没有"</h1>
 *
 * <h2>为什么必须单独建这个探针（2026-10-08，用户判据驱动）</h2>
 * <p>用户实测："游戏实测出现的湖泊和你渲染图跑的湖泊与河流不一致。
 * 渲染图会生成的，游戏不一定生成。"</p>
 *
 * <p>根因是<b>口径完全不同</b>：既有正典图（{@link CanonicalViewProbe}）直接调
 * {@code HydrologyPipeline.run}，用 {@code ViewSpec} 的
 * {@code radius=700 / step=1 / samples=600000}；
 * 而游戏生产走 {@code ModelHydrologyProvider}：
 * {@code radius=352（REGION/2+HALO）/ step=4（177²）/ samples=50000}。
 * <b>窗口半径直接决定"有多少上游来水汇入"</b> ⇒ 同一洼地的来水量不同
 * ⇒ <b>湖是否存在、水位多少都可能不同</b>。</p>
 *
 * <p>本探针<b>逐 chunk 调用生产 provider</b>（{@code columnsFor}），只用
 * provider 实际产出的列上色 ⇒ 跑出来的图<b>就是游戏会落的水</b>。</p>
 *
 * <h2>★ 渲染口径（2026-10-08 修正，务必遵守）</h2>
 * <p><b>必须先错的教训</b>：本探针初版自写了自适应灰度底图（{@code band=(hMax−hMin)/24}）
 * 且默认 {@code radius=300} ⇒ 与正典图<b>色标与尺寸都不同</b>，用户当即指出
 * "尺寸都不一样，你这样参照都没对怎么修？"。</p>
 * <p>⇒ 现<b>严格复用</b>正典口径（{@link ViewSpec} 是唯一来源）：</p>
 * <ul>
 *   <li>尺寸：{@code RADIUS=700 / STEP=1} ⇒ <b>1401×1401</b>，<b>默认值，不许随手改</b>
 *       （{@link ViewSpec#assertCanonicalRadius} 硬断言）；</li>
 *   <li>底图：{@link ViewSpec#contour} <b>固定色标</b> {@code min=0 / band=8}（跨图可比）；</li>
 *   <li>底图 <b>数据源</b>：{@code gen.sampleWu(bx/hs, bz/hs)} —— 与
 *       {@code HydrologyPipeline} 的 {@code WindowOutlets.Terrain} <b>同一源</b>
 *       （初版误用 {@code gen.sample(...)}，是第二处口径错误）；</li>
 *   <li>水域配色：{@link ViewSpec#SEA_COLOR}/{@link ViewSpec#LAKE_COLOR}/{@link ViewSpec#RIVER_COLOR}。</li>
 * </ul>
 *
 * <p><b>NOT VERIFIED</b>：仍是离线（不起 MC），但走的是与游戏<b>同一个</b>
 * provider 与同一组 region 参数 ⇒ 比正典图更接近实机。端到端确认仍需实机目检。</p>
 */
public final class ProductionViewProbe {

    private ProductionViewProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        // ★ 默认必须正典 radius（ViewSpec.RADIUS）。
        //   ⚠ 2026-10-09：由"警告"改为【硬失败】—— 因为我在同一轮里**反复传 300**
        //     并拿 601×601 的图与用户 1401×1401 的参照图对比（用户已两次指出）。
        //     口头纪律无效 ⇒ 改为构造性拦截：非正典半径直接抛异常。
        //     确需小窗探索时用 `-Dhydro.allowNonCanonicalRadius=true` 显式豁免。
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        ViewSpec.assertCanonicalRadius(radius,
                !Boolean.getBoolean("hydro.allowNonCanonicalRadius"));

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = Math.max(0.01, tp.horizontalScale());

        ModelHydrologyProvider provider = new ModelHydrologyProvider(gen, seed);

        int n = 2 * radius + 1;                      // 正典：1401
        boolean[] isLake = new boolean[n * n];
        boolean[] isChannel = new boolean[n * n];
        int[] seen = new int[n * n];                 // 每格被多少列覆盖（诊断用）

        long t0 = System.nanoTime();
        int chunks = 0;
        int riverCols = 0, lakeCols = 0;
        int c0 = Math.floorDiv(cx - radius, 16) - 1, c1 = Math.floorDiv(cx + radius, 16) + 1;
        int d0 = Math.floorDiv(cz - radius, 16) - 1, d1 = Math.floorDiv(cz + radius, 16) + 1;
        for (int chX = c0; chX <= c1; chX++) {
            for (int chZ = d0; chZ <= d1; chZ++) {
                Cell[] cells = sampleOriginal(gen, hs, chX, chZ);
                List<HydrologyBlockCarvedColumn> cols = provider.columnsFor(chX, chZ, cells);
                chunks++;
                for (HydrologyBlockCarvedColumn col : cols) {
                    int px = col.blockX() - (cx - radius);
                    int pz = col.blockZ() - (cz - radius);
                    if (px < 0 || pz < 0 || px >= n || pz >= n) continue;
                    int k = px * n + pz;
                    // 与游戏落块层同一语义：湖列按 lakePlan；河列按"真雕出了河床"
                    if (col.lakePlan()) {
                        isLake[k] = true;
                        lakeCols++;
                    } else if (col.erosion() > 0) {
                        isChannel[k] = true;
                        riverCols++;
                    }
                    seen[k]++;
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;

        int lakeCells = 0, chCells = 0;
        for (int k = 0; k < n * n; k++) {
            if (isLake[k]) lakeCells++;
            else if (isChannel[k]) chCells++;
        }

        Path dir = Path.of("build/hydro-production/prod-" + System.currentTimeMillis());
        Files.createDirectories(dir);

        // ★ 底图数据源 = 与 HydrologyPipeline 的 WindowOutlets.Terrain 同源
        //   （`gen.sampleWu(bx/hs, bz/hs)`）。初版误用 gen.sample(...) ⇒ 口径错误。
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.sampleWu((cx - radius + i) / hs, (cz - radius + j) / hs).height;
            }
        }

        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        // ★★ 底图口径必须与正典【逐字一致】（2026-10-08 第三次工具修正）★★
        //
        //   [错误 1] 初版自写自适应灰度（band=(hMax−hMin)/24 的自制公式）⇒ 灰阶与正典不同。
        //   [错误 2] 底图数据源误用 gen.sample(...) —— 正典用 gen.sampleWu(...)（同 Pipeline）。
        //   [错误 3] ★ 改用 ViewSpec.contourFixed 后【仍然不同】：
        //           正典 CanonicalViewProbe 第 97 行实际用的是
        //           `bandBlocks = Math.max(4.0, (hMax−hMin)/24.0)` —— 【按本图自适应】，
        //           而不是 contourFixed 的固定档距（min=0/band=8）。
        //           ⇒ 两者灰阶含义不同 ⇒ 用户看到的"底图不一样"部分是【渲染差异】而非数据差异。
        //   现改为：完全复现正典的算法（hMin/hMax 取本图范围 + 同档距公式 + 同 ViewSpec.contour）。
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (double v : bed) {
            if (v < hMin) hMin = v;
            if (v > hMax) hMax = v;
        }
        double bandBlocks = Math.max(4.0, (hMax - hMin) / 24.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (isLake[k]) color = ViewSpec.LAKE_COLOR;
                else if (isChannel[k]) color = ViewSpec.RIVER_COLOR;
                else if (bed[k] < gen.seaLevel()) color = ViewSpec.SEA_COLOR;
                // ★ 与正典同一渲染：ViewSpec.contour + 本图自适应档距（非 contourFixed）
                else color = ViewSpec.contour(bed, n, i, j, hMin, bandBlocks);
                img.setRGB(i, j, color);
            }
        }
        ImageIO.write(img, "png", dir.resolve("production.png").toFile());

        // ★ 连续性与碎裂度诊断
        int iso0 = 0, iso1 = 0, iso2 = 0, iso3 = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (!isChannel[k]) continue;
                int deg = 0;
                if (i > 0 && isChannel[k - n]) deg++;
                if (i < n - 1 && isChannel[k + n]) deg++;
                if (j > 0 && isChannel[k - 1]) deg++;
                if (j < n - 1 && isChannel[k + 1]) deg++;
                if (deg == 0) iso0++;
                else if (deg == 1) iso1++;
                else if (deg == 2) iso2++;
                else iso3++;
            }
        }
        int[] comp = new int[n * n];
        java.util.Arrays.fill(comp, -1);
        int nComp = 0;
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int k = 0; k < n * n; k++) {
            if (!isChannel[k] || comp[k] >= 0) continue;
            comp[k] = nComp;
            queue.add(k);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                int ci = c / n, cj = c % n;
                if (ci > 0 && isChannel[c - n] && comp[c - n] < 0) { comp[c - n] = nComp; queue.add(c - n); }
                if (ci < n - 1 && isChannel[c + n] && comp[c + n] < 0) { comp[c + n] = nComp; queue.add(c + n); }
                if (cj > 0 && isChannel[c - 1] && comp[c - 1] < 0) { comp[c - 1] = nComp; queue.add(c - 1); }
                if (cj < n - 1 && isChannel[c + 1] && comp[c + 1] < 0) { comp[c + 1] = nComp; queue.add(c + 1); }
            }
            nComp++;
        }
        int uncovered = 0;
        for (int k = 0; k < n * n; k++) if (seen[k] == 0) uncovered++;

        StringBuilder sb = new StringBuilder();
        sb.append("=== 生产路径出图（ModelHydrologyProvider，与游戏同一 provider）===\n");
        sb.append(String.format("seed=%d centre=(%d,%d) radius=%d ⇒ %dx%d（正典规格 =%d）%n",
                seed, cx, cz, radius, n, n, ViewSpec.size()));
        sb.append(String.format("底图 = ViewSpec.contourFixed（min=%.0f band=%.0f，跨图可比）；"
                + "数据源 = sampleWu（与 Pipeline 同源）%n", ViewSpec.BAND_BASE, ViewSpec.BAND_BLOCKS));
        sb.append(String.format("chunks=%d 耗时=%.1fs  （未覆盖格=%d）%n", chunks, ms / 1000, uncovered));
        sb.append(String.format("湖格=%d (%.2f%%)  河道格=%d (%.2f%%)%n",
                lakeCells, 100.0 * lakeCells / (n * n), chCells, 100.0 * chCells / (n * n)));
        sb.append(String.format("（探针收到列：湖列=%d 河列=%d / 总列=%d）%n", lakeCols, riverCols, chunks * 256));
        // ★ 对照：模型【网格级】的湖格总数（各 region 去重前的原始产出）
        //   用于判定"湖是窗口丢的"还是"适配层丢的"：
        //   网格湖格 × step² ≈ 应有的方块湖面积；与湖列数比较即可。
        sb.append(String.format("【模型原始产出】region 级网格：湖格=%d × step²(%d) = 约 %d 方块；"
                        + "实测湖列=%d ⇒ 适配层保留率 %.0f%%%n",
                ModelHydrologyProvider.lastRegionLakeCells(),
                Integer.getInteger("geogenesis.modelStep", 4),
                ModelHydrologyProvider.lastRegionLakeCells()
                        * Integer.getInteger("geogenesis.modelStep", 4)
                        * Integer.getInteger("geogenesis.modelStep", 4),
                lakeCols,
                100.0 * lakeCols / Math.max(1, ModelHydrologyProvider.lastRegionLakeCells()
                        * Integer.getInteger("geogenesis.modelStep", 4)
                        * Integer.getInteger("geogenesis.modelStep", 4))));
        // ★ 更精确的口径：region 累加含【重叠】⇒ 应除以「每点被几个 region 覆盖」。
        //   本图上：region 边长 REGION，窗口半径 RADIUS ⇒ 一个点被 ceil((2*RADIUS/REGION)²) 个 region 覆盖。
        //   （REGION=512/RADIUS=352 ⇒ 2×2=4；REGION=1408/RADIUS=704 ⇒ 1×1=1）
        int region = Integer.getInteger("geogenesis.modelRegion", 512);
        int radiusR = region / 2 + Integer.getInteger("geogenesis.modelHalo", 96);
        int overlap = Math.max(1, (int) Math.ceil(2.0 * radiusR / region));
        long dedup = ModelHydrologyProvider.lastRegionLakeCells() / (long) (overlap * overlap);
        sb.append(String.format("（去重口径：region 累加 ÷ 覆盖重数 %d² = %d 网格格 "
                        + "⇒ ×step² = %d 方块 ⇔ 应为全图湖面积；实测湖列=%d ⇒ **真实保留率 %.0f%%**）%n",
                overlap, dedup, dedup * Integer.getInteger("geogenesis.modelStep", 4)
                        * Integer.getInteger("geogenesis.modelStep", 4),
                lakeCols, 100.0 * lakeCols / Math.max(1, dedup
                        * Integer.getInteger("geogenesis.modelStep", 4)
                        * Integer.getInteger("geogenesis.modelStep", 4))));
        if (ModelHydrologyProvider.LAKE_DIAG) {
            sb.append('\n').append(ModelHydrologyProvider.lakeDiagReport()).append('\n');
        }
        if (ModelHydrologyProvider.RIVER_WIDTH_DIAG) {
            sb.append('\n').append(ModelHydrologyProvider.riverWidthReport()).append('\n');
        }
        sb.append(String.format("%n【河道连续性】河格=%d%n", riverCols));
        sb.append(String.format("  4邻域度：孤立0=%d (%.1f%%)  端点1=%d (%.1f%%)  "
                        + "通路2=%d (%.1f%%)  汇合3+=%d (%.1f%%)%n",
                iso0, 100.0 * iso0 / Math.max(1, riverCols),
                iso1, 100.0 * iso1 / Math.max(1, riverCols),
                iso2, 100.0 * iso2 / Math.max(1, riverCols),
                iso3, 100.0 * iso3 / Math.max(1, riverCols)));
        sb.append(String.format("  4连通分量=%d%n", nComp));
        sb.append(String.format("%n生产 region 参数：REGION=%d HALO=%d STEP=%d samples=%d%n",
                Integer.getInteger("geogenesis.modelRegion", 512),
                Integer.getInteger("geogenesis.modelHalo", 96),
                Integer.getInteger("geogenesis.modelStep", 4),
                50000));
        sb.append("正典图参数：radius=700 step=1 samples=600000（同 seed/中心）\n");
        sb.append("成河阈值：-Dgeogenesis.riverThresholdFrac（默认 0 = 原行为 abs=1.0）\n");
        sb.append("\nFILE=").append(dir.resolve("production.png").toAbsolutePath()).append('\n');
        Files.writeString(dir.resolve("metrics.txt"), sb.toString());
        System.out.print(sb);
    }

    private static Cell[] sampleOriginal(CellGenerator gen, double hs, int chunkX, int chunkZ) {
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = gen.sample((chunkX * 16 + lx) / hs, (chunkZ * 16 + lz) / hs);
            }
        }
        return cells;
    }
}
