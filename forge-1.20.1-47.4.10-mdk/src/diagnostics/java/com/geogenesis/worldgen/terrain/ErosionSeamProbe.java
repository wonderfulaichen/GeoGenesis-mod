package com.geogenesis.worldgen.terrain;

import java.util.Arrays;

/**
 * 侵蚀【接缝 / 确定性】与【规划场 vs 最终场分歧】探针（2026-09-17 新建，水文重构 M0）。
 *
 * <h2>要回答的两个问题</h2>
 * <ol>
 *   <li><b>Q1（用户报"偶尔有缝"）</b>：同一个世界坐标的侵蚀增量 {@code delta}，
 *       是否会因为<b>邻居 tile 当时在不在缓存里</b>而取到不同的值？</li>
 *   <li><b>Q2（架构）</b>：水文规划用的场（{@code heightFromE(terrainEQuick)}）与
 *       最终地形（{@code sampleWu}，含侵蚀）差多少？—— 这是"跨场套用"的量化账单。</li>
 * </ol>
 *
 * <h2>Q1 为何这么测（读码得到的可疑点）</h2>
 * <p>{@code CellGenerator.blendTileDelta} 在 tile 右/下缘 6wu（{@code BLEND_START}）做
 * 4 向对称 blend，但其注释承认：液滴 delta 场在 tile 左缘（= 模拟域西界，液滴出生/截断
 * 不对称）<b>固有突变</b>，blend 就是为抹平它。而 blend 取邻居时用的是
 * <b>{@code erosionTileCache.get(...)}——只从缓存取，缺失即退回 d00（不 blend）</b>：</p>
 * <pre>
 *   ErosionTileResult tx = erosionTileCache.get(tileKey(ncx, tileCZ));   // 只读缓存
 *   double d10 = tx != null ? sampleTileField(tx.delta, ...) : d00;      // 缺失 → 不 blend
 * </pre>
 * <p>⇒ 同一坐标的 delta <b>取决于邻居当时是否已缓存</b>；而邻居会被 LRU 驱逐
 * （{@code pruneErosionCache}）、生成顺序也不确定 ⇒ <b>非确定性</b>
 * （违反"按 (seed,配置,坐标) 纯函数"铁律）⇒ 表现为<b>偶发的可见接缝</b>。</p>
 *
 * <p><b>本探针的测法</b>：在 tile 右缘 blend 带内取点，先在【邻居未缓存】时采样（Arm A），
 * 再<b>显式预生成邻居 tile</b>后采样同一批点（Arm B）。两者之差 = 纯缓存依赖。
 * ⚠ {@code erosionDeltaE} 只会生成<b>自己</b>的 tile、邻居一律走缓存 ⇒ Arm A 不会污染邻居。</p>
 *
 * <h2>Q2 的意义</h2>
 * <p>湖泊填洼层建在 {@code groundYAt}（{@code terrainEQuick} 派生，无侵蚀无雕刻）上，
 * 而落块用 {@code sampleWu}（含侵蚀）。两者若无"融合"关系，就会产出与最终地形不自洽的水位
 * （已实测到 {@code 最深 85~96 块、spill≈171} 的"湖"）。本节的 Δ 分布即该误差的量级。</p>
 *
 * <pre>{@code gradlew runErosionSeamProbe [-PprobeArgs="seed wuSpan"]}</pre>
 */
public final class ErosionSeamProbe {

    /** 与生产一致的 tile 几何（只读副本，用于定位边界；真值在 CellGenerator）。 */
    private static final int TILE_CENTER = 48;
    private static final int TILE_BORDER = 40;
    /** 生产：blend 带宽度（wu）——tile 右/下缘多少 wu 内会去查邻居。 */
    private static final int BLEND_START = 6;

    private ErosionSeamProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int span = args.length > 1 ? Integer.parseInt(args[1]) : 96;
        // ★ 可选：指定世界坐标（wu）——用于复现"用户看到缝的那个位置"。
        //   ⚠ 传【wu】(= 块坐标 ÷ horizontalScale)。tile 网格按 wu 对齐，故 wu 才是稳口径。
        Double atX = args.length > 2 ? Double.parseDouble(args[2]) : null;
        Double atZ = args.length > 3 ? Double.parseDouble(args[3]) : null;

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        System.out.printf("=== ErosionSeamProbe seed=%d span=%d wu ===%n", seed, span);
        System.out.printf("（tile: center=%d border=%d size=%d；blend 带=%d wu）%n",
                TILE_CENTER, TILE_BORDER, TILE_CENTER + TILE_BORDER * 2, BLEND_START);

        if (atX != null && atZ != null) {
            sectionPlaced(gen, atX, atZ);
            sectionAt(gen, atX, atZ);
            sectionMap(gen, atX, atZ);
        }
        sectionSeam(gen, seed);
        sectionEdgeJump(gen);
        sectionPlanVsFinal(gen, span);
        sectionCaliber(gen, 64);
    }

    // ==================================================================
    // [4] ★ 三口径分歧：预览快速路径 / 含侵蚀 / 实际放置
    // ==================================================================

    /**
     * 在同一批点上比较三份地形口径，量化"跨场套用"的完整误差。
     *
     * <ul>
     *   <li><b>预览（快速路径）</b>：{@code sampleCellLight} —— 无侵蚀、无雕刻（为性能）；</li>
     *   <li><b>含侵蚀</b>：{@code sampleWu} —— + 侵蚀 tile delta；</li>
     *   <li><b>实际放置</b>：{@code getChunkCells} —— + 侵蚀 + <b>水文雕刻</b>（玩家看到的）。</li>
     * </ul>
     *
     * <p>⚠ 这三者<b>本应收敛</b>（AGENTS 记"快速路径在已探索区域与完整管线收敛"）；
     * 若不收敛，则"预览看到的"与"游戏生成的"是两回事 —— 本项目此前已因此产出
     * 86 块深、spill≈171 的异常湖。</p>
     *
     * <p>本节的数值即 M2（最终高度唯一化）的<b>验收基线</b>：改造后应显著收敛。</p>
     */
    private static void sectionCaliber(CellGenerator gen, int span) {
        GeoGenesisTerrain t = new GeoGenesisTerrain(gen);
        double hs = gen.params().horizontalScale();
        int half = span / 2;
        double[] dPreview = new double[span * span];
        double[] dEro = new double[span * span];
        int flips = 0, carve1 = 0, carve4 = 0, n = 0;
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) {
                double wuX = -half + i, wuZ = -half + j;
                int bx = (int) Math.floor(wuX * hs), bz = (int) Math.floor(wuZ * hs);
                int cx = bx >> 4, cz = bz >> 4;
                int lx = Math.floorMod(bx, 16), lz = Math.floorMod(bz, 16);
                Cell placed = t.getChunkCells(cx, cz)[lx * 16 + lz];
                // 预览快速路径 = sample()（无侵蚀、不触发 tile 生成）。
                // ⚠ 不存在 sampleCellLight 方法 —— 它只出现在 CellGenerator 的注释里（实测编译报"找不到符号"）。
                Cell light = gen.sample(wuX, wuZ);
                Cell ero = gen.sampleWu(wuX, wuZ);
                dPreview[n] = light.height - placed.height;
                dEro[n] = ero.height - placed.height;
                if (light.terrainType != placed.terrainType) flips++;
                double c = Math.abs(dEro[n]);
                if (c > 1.0) carve1++;
                if (c > 4.0) carve4++;
                n++;
            }
        }
        double[] sp = sortedAbs(dPreview), se = sortedAbs(dEro);
        System.out.println();
        System.out.printf("[4] ★ 三口径分歧（%d×%d 点，span=%d wu）—— 基准 = 【实际放置】getChunkCells%n",
                span, span, span);
        System.out.printf("    预览(sampleCellLight) − 放置：均值 %.3f  中位 %.3f  p95 %.3f  max %.3f 块%n",
                meanAbs(dPreview), sp[sp.length / 2], sp[(int) (sp.length * 0.95)], sp[sp.length - 1]);
        System.out.printf("    含侵蚀(sampleWu) − 放置：均值 %.3f  中位 %.3f  p95 %.3f  max %.3f 块%n",
                meanAbs(dEro), se[se.length / 2], se[(int) (se.length * 0.95)], se[se.length - 1]);
        System.out.printf("    预览与放置【地形类型不同】的点: %d / %d = %.1f%%%n",
                flips, n, 100.0 * flips / n);
        System.out.printf("    ★ 水文雕刻量（= 放置与「含侵蚀」之差）：|Δ|>1 块 %d 点(%.1f%%)、>4 块 %d 点(%.1f%%)%n",
                carve1, 100.0 * carve1 / n, carve4, 100.0 * carve4 / n);
        System.out.println("    判读：水文雕刻量 = 我此前一直用 sampleWu 却漏掉的那部分；");
        System.out.println("          预览偏差 = 玩家在预览里看到的与游戏里生成的不是同一份地形。");
    }

    private static double[] sortedAbs(double[] a) {
        double[] c = new double[a.length];
        for (int i = 0; i < a.length; i++) c[i] = Math.abs(a[i]);
        Arrays.sort(c);
        return c;
    }

    private static double meanAbs(double[] a) {
        double s = 0;
        for (double v : a) s += Math.abs(v);
        return s / Math.max(1, a.length);
    }

    // ==================================================================
    // [1a] ★ 口径核对：与实际【放置方块】同源的地形（getChunkCells）
    // ==================================================================

    /**
     * 用 {@code GeoGenesisTerrain.getChunkCells}（= 游戏实际放置方块的口径：
     * sample + 侵蚀 + **水文雕刻**）读取该点，与 {@code sampleWu}（**不含雕刻**）对比。
     *
     * <p><b>为何必须做</b>：此前探针一律用 {@code sampleWu}，它<b>不含河道/河谷雕刻</b>
     * ⇒ 量到的不是玩家看到的地形（用户 F3 显示 Y=192，而 sampleWu 读 210.6，**差 18 块**）。
     * 这正是"反复查不到用户所见伪影"的根因。</p>
     */
    private static void sectionPlaced(CellGenerator gen, double wuX, double wuZ) {
        double hs = gen.params().horizontalScale();
        int bx = (int) Math.floor(wuX * hs);   // wu → 块
        int bz = (int) Math.floor(wuZ * hs);
        int cx = bx >> 4, cz = bz >> 4;
        int lx = Math.floorMod(bx, 16), lz = Math.floorMod(bz, 16);
        GeoGenesisTerrain t = new GeoGenesisTerrain(gen);

        double hSampleWu = gen.sampleWu(wuX, wuZ).height;
        Cell[] cells = t.getChunkCells(cx, cz);
        Cell placed = cells[lx * 16 + lz];

        System.out.println();
        System.out.println("[1a] ★ 口径核对（同一世界点，两种口径）");
        System.out.printf("     点：wu=(%.1f,%.1f) = 块(%d,%d) = chunk(%d,%d) 内 (%d,%d)%n",
                wuX, wuZ, bx, bz, cx, cz, lx, lz);
        System.out.printf("     sampleWu()      Y = %9.3f  ← 含侵蚀、【不含雕刻】（我此前一直用这个）%n",
                hSampleWu);
        System.out.printf("     getChunkCells() Y = %9.3f  ← 实际放置口径（+水文雕刻）%n",
                placed.height);
        System.out.printf("     ⇒ 差值 = %+.3f 块   （玩家看到的是后者）%n", placed.height - hSampleWu);
        System.out.printf("     riverType=%d isLake=%s lakeMask=%s riverSurfaceY=%.3f riverLipY=%.3f%n",
                placed.riverType, placed.isLake, placed.lakeMask,
                placed.riverSurfaceY, placed.riverLipY);
        System.out.println("     判读：若差值显著（十几块），说明该点被【河谷雕刻】切开 ——");
        System.out.println("           玩家看到的陡壁就是雕刻谷壁，与类型/侵蚀伪影无关。");
    }

    // ==================================================================
    // [1b] 指定坐标（wu）附近的 tile 边界：delta 与【最终地面 Y】的阶跃
    // ==================================================================

    /**
     * 在给定 wu 坐标附近，找到最近的 tile 边界，沿 x 以 1wu 步长扫过 33 点，
     * 同时读 {@code erosionDeltaE}（增量）与 {@code sampleWu().height}（最终地面）。
     *
     * <p><b>为何两者都读</b>：可见性取决于【最终地面 Y 的阶跃】而非 delta 本身；
     * 且"阶跃 / 邻域典型步长"的比值决定肉眼是否看得出（陡坡上 1 块阶跃不显，缓坡上极显）。</p>
     */
    private static void sectionAt(CellGenerator gen, double wuX, double wuZ) {
        int tileX = Math.floorDiv((int) Math.floor(wuX), TILE_CENTER) * TILE_CENTER;
        int tileZ = Math.floorDiv((int) Math.floor(wuZ), TILE_CENTER) * TILE_CENTER;
        double dRight = (tileX + TILE_CENTER) - wuX;
        double dLeft = wuX - tileX;
        double edge = dRight <= dLeft ? tileX + TILE_CENTER : tileX;

        final int half = 16;
        double[] xs = new double[2 * half + 1];
        double[] ds = new double[xs.length];
        double[] hh = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = edge - half + i;
            ds[i] = gen.erosionDeltaE(xs[i], wuZ);
            hh[i] = gen.sampleWu(xs[i], wuZ).height;
        }
        double maxDH = 0, atH = 0, maxDD = 0, atD = 0, sumDH = 0;
        double[] steps = new double[xs.length - 1];
        for (int i = 1; i < xs.length; i++) {
            steps[i - 1] = Math.abs(hh[i] - hh[i - 1]);
            sumDH += steps[i - 1];
            if (steps[i - 1] > maxDH) { maxDH = steps[i - 1]; atH = xs[i]; }
            double dd = Math.abs(ds[i] - ds[i - 1]);
            if (dd > maxDD) { maxDD = dd; atD = xs[i]; }
        }
        double meanDH = sumDH / Math.max(1, steps.length);

        System.out.println();
        System.out.printf("[1b] 指定坐标附近：wu=(%.1f, %.1f)，所在 tile=(%d,%d)，最近边界 x=%.0f%n",
                wuX, wuZ, tileX, tileZ, edge);
        System.out.println("     x(wu)      delta(e)      最终地面Y      |Δdelta|    |ΔY|"
                + "    singleEdif  fieldEdif   类型");
        for (int i = 0; i < xs.length; i++) {
            double dd = i == 0 ? 0 : Math.abs(ds[i] - ds[i - 1]);
            double dh = i == 0 ? 0 : Math.abs(hh[i] - hh[i - 1]);
            String mark = Math.abs(xs[i] - edge) < 0.5 ? "  ← tile 边界" : "";
            // ★ 打出类型判定的实际输入（volcanoClass 用 >0.010 判 VOLCANO/VOLCANIC_FIELD），
            //   以定位"边界到底由哪个量决定"。
            Cell cc = gen.sampleWu(xs[i], wuZ);
            double se = cc.landFeat == null ? -1 : cc.landFeat.singleEdifice;
            double fe = cc.landFeat == null ? -1 : cc.landFeat.fieldEdifice;
            // ★ 追加"裸石判定"：仅用 gradient（零 MC 依赖）。
            //   ⚠ 不得调用 BiomeClassifier.surfaceOf —— 它依赖 Biomes 注册表，
            //     探针进程无法引导（实测 "Not bootstrapped" 崩溃）；
            //     项目铁律：不自建 registry 桩（"桩≠真"漂移）⇒ 只报 gradient 与阈值判定。
            System.out.printf("    %6.1f   %12.8f   %10.3f   %9.7f   %6.3f   %9.5f  %9.5f  %s"
                            + "   grad=%6.3f 裸石=%s%s%n",
                    xs[i], ds[i], hh[i], dd, dh, se, fe, cc.terrainType,
                    cc.gradient, cc.gradient > 0.40 ? "是" : "否", mark);
        }
        System.out.printf("    → max|ΔY| = %.3f 块 @ x=%.0f（均值 %.3f 块）⇒ 显著比 = %.1f×%n",
                maxDH, atH, meanDH, meanDH <= 1e-9 ? 0.0 : maxDH / meanDH);
        System.out.printf("    → max|Δdelta| = %.8f e ≈ %.2f 块 @ x=%.0f%n",
                maxDD, maxDD * 192.0, atD);
        System.out.println("    判读：max|ΔY| 若明显高于均值（比如 >3×）⇒ 该处接缝肉眼可见。");
    }

    // ==================================================================
    // [1c] 指定坐标的局部「诊断图」：主导类型 + 陡坡裸岩 + tile 网格线
    // ==================================================================

    /**
     * 打印以给定点为中心的 ASCII 图（±{@code half} wu，1wu 步长）。
     *
     * <p>两个用途：① 肉眼判断"平台/长直线"到底是<b>类型场边界</b>（PLATEAU/Voronoi）
     * 还是 <b>tile 网格</b>（侵蚀）；② 标记陡坡裸岩（{@code gradient>0.40}）以区分"方块感"来源。</p>
     */
    private static void sectionMap(CellGenerator gen, double wuX, double wuZ) {
        // ★ 两级视野：近了看"方块/裸岩"，远了看"类型边界直线"（长直线尺度可达上千块）。
        // ⚠ 步长必须足够细：圆/椭圆在粗格上会被画成"菱形"（走样）⇒ 会误导成"直边缺陷"。
        mapView(gen, wuX, wuZ, 48, 2, "近景（地形类型）±48wu / 2wu");
        // ★ 与山体阴影【同尺度】的类型图：判断"山体边缘的直边"是否 = 类型场边界。
        mapView(gen, wuX, wuZ, 384, 12, "同尺度类型图（对照山体阴影）±384wu / 12wu");
        // ★ 治本纠正：**类型在游戏里看不见**。用户看的是几何 ⇒ 必须看"可见量"。
        //   ASCII 在起伏地形上不够判别 ⇒ 再渲染山体阴影 PNG（可当图片读入细看）。
        stepMapView(gen, wuX, wuZ, 96, 3, "台阶图（可见几何）±96wu / 3wu");
        try {
            pngView(gen, wuX, wuZ, 192, 1, "build/seam/hillshade_at.png",
                    "山体阴影（1wu/px，±192wu）");
            waterView(gen, wuX, wuZ, 96, 1, "build/seam/water_view.png");
            sectionLakeShortboard(gen, wuX, wuZ, 96);
            sectionRiverPerch(gen, wuX, wuZ, 96, 1, 12);
        } catch (Exception e) {
            System.out.println("     审计失败: " + e);
        }
    }

    // ==================================================================
    // [6] ★ 河流悬空审计：河面 vs 半径 R 内最低非水地面（谷底）
    // ==================================================================

    /**
     * 对窗口内每个【河水格】计算 {@code 违反 = riverSurfaceY − 半径 R 内最低非水地面}。
     *
     * <p>违反 &gt; 0 ⇒ 河面高于周边谷底 ⇒ 河被"悬空"架在谷地上方
     * （水应从河岸流出却没流走）—— 这正是用户截图里"悬空水板/垂直水墙"的量化。</p>
     *
     * <p>⚠ 与 [5] 的区别：[5] 按水体分组（适合湖面为常量的湖）；
     * 河的水面沿程变化，必须<b>逐格</b>量，故本节用网格半径搜索。</p>
     */
    private static void sectionRiverPerch(CellGenerator gen, double wuX, double wuZ,
                                          int half, int step, int radiusWu) {
        double hs = gen.params().horizontalScale();
        int n = 2 * half / step + 1;
        double[][] h = new double[n][n];
        boolean[][] wat = new boolean[n][n];
        double[][] sf = new double[n][n];
        GeoGenesisTerrain t = new GeoGenesisTerrain(gen);
        int bx0 = (int) Math.floor((wuX - half) * hs), bx1 = (int) Math.floor((wuX + half) * hs);
        int bz0 = (int) Math.floor((wuZ - half) * hs), bz1 = (int) Math.floor((wuZ + half) * hs);
        for (int cx = bx0 >> 4; cx <= bx1 >> 4; cx++) {
            for (int cz = bz0 >> 4; cz <= bz1 >> 4; cz++) {
                Cell[] cs = t.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        double wx = (cx * 16 + lx) / hs, wz = (cz * 16 + lz) / hs;
                        int pi = (int) Math.round((wx - (wuX - half)) / step);
                        int pj = (int) Math.round((wz - (wuZ - half)) / step);
                        if (pi < 0 || pi >= n || pj < 0 || pj >= n) continue;
                        Cell c = cs[lx * 16 + lz];
                        h[pj][pi] = c.height;
                        wat[pj][pi] = c.riverType != 0;
                        sf[pj][pi] = c.riverSurfaceY;
                    }
                }
            }
        }
        int r = Math.max(1, radiusWu / step);
        int riverCells = 0;
        int[] bucket = new int[5];       // <2 / <5 / <10 / <20 / ≥20 块
        double worst = 0, wX = 0, wZ = 0, wL = 0, wV = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (!wat[j][i]) continue;
                riverCells++;
                double level = sf[j][i];
                double valleyMin = Double.MAX_VALUE;
                int ri = radiusWu / step;
                for (int dj = -ri; dj <= ri; dj++) {
                    int jj = j + dj;
                    if (jj < 0 || jj >= n) continue;
                    for (int di = -ri; di <= ri; di++) {
                        int ii = i + di;
                        if (ii < 0 || ii >= n) continue;
                        if (wat[jj][ii]) continue;
                        valleyMin = Math.min(valleyMin, h[jj][ii]);
                    }
                }
                if (valleyMin == Double.MAX_VALUE) continue;   // 半径内全是水
                double v = level - valleyMin;
                int b = v < 2 ? 0 : (v < 5 ? 1 : (v < 10 ? 2 : (v < 20 ? 3 : 4)));
                bucket[b]++;
                if (v > worst) { worst = v; wX = wuX - half + i * step; wZ = wuZ - half + j * step;
                    wL = level; wV = valleyMin; }
            }
        }
        System.out.println();
        System.out.printf("[6] ★ 河流悬空审计（±%dwu，河水格 %d，谷底搜索半径 %d wu）%n",
                half, riverCells, radiusWu);
        System.out.printf("    悬空 <2 块: %d   2~5: %d   5~10: %d   10~20: %d   ≥20: %d%n",
                bucket[0], bucket[1], bucket[2], bucket[3], bucket[4]);
        System.out.printf("    最坏悬空 = %.3f 块 @ wu(%.0f,%.0f)（河面 %.3f / 谷底 %.3f）%n",
                worst, wX, wZ, wL, wV);
        System.out.println("    判读：悬空格占比高 ⇒ 河面被架在谷地上方（用户所见『垂直水墙』）。");
    }

    // ==================================================================
    // [5] ★ 湖泊短板审计：已放置水位 vs 其紧邻旱地的最低高度
    // ==================================================================

    /**
     * 对窗口内每个【已放置水体】（按水位分组）计算：
     * <ul>
     *   <li>{@code level} = 该水体所有水格的 {@code riverSurfaceY}（湖面为常量）；</li>
     *   <li>{@code rimMin} = 紧邻该水体的【非水格】地面高度的最小值（= 真实盆沿短板）；</li>
     *   <li>{@code 违反} = level − rimMin。&gt;0 ⇒ 水会从该处流走却被硬放住 ⇒ 短板被违反。</li>
     * </ul>
     * <p>这是"水位不按短板"的<b>直接量化</b>：违反值就是水面上方多出来的那截水柱。</p>
     */
    private static void sectionLakeShortboard(CellGenerator gen, double wuX, double wuZ, int halfWu) {
        double hs = gen.params().horizontalScale();
        GeoGenesisTerrain t = new GeoGenesisTerrain(gen);
        int bx0 = (int) Math.floor((wuX - halfWu) * hs), bx1 = (int) Math.floor((wuX + halfWu) * hs);
        int bz0 = (int) Math.floor((wuZ - halfWu) * hs), bz1 = (int) Math.floor((wuZ + halfWu) * hs);
        // key = 水位×1000（湖面为常量 ⇒ 同一水体同 key）；value = {level, rimMin, cells}
        java.util.TreeMap<Long, double[]> bodies = new java.util.TreeMap<>();
        int waterCells = 0;
        for (int cx = bx0 >> 4; cx <= bx1 >> 4; cx++) {
            for (int cz = bz0 >> 4; cz <= bz1 >> 4; cz++) {
                Cell[] cs = t.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Cell c = cs[lx * 16 + lz];
                        if (c.riverType != 0) {
                            waterCells++;
                            long key = Math.round(c.riverSurfaceY * 1000.0);
                            double[] b = bodies.computeIfAbsent(key,
                                    k -> new double[]{c.riverSurfaceY, Double.MAX_VALUE, 0});
                            b[2]++;
                            continue;
                        }
                        // 旱地：查 8 邻是否有水，若有则把本格高度记入该水体的盆沿
                        int bx = cx * 16 + lx, bz = cz * 16 + lz;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dz == 0) continue;
                                int nbx = bx + dx, nbz = bz + dz;
                                Cell nb = t.getChunkCells(nbx >> 4, nbz >> 4)
                                        [Math.floorMod(nbx, 16) * 16 + Math.floorMod(nbz, 16)];
                                if (nb.riverType == 0) continue;
                                long key = Math.round(nb.riverSurfaceY * 1000.0);
                                bodies.computeIfAbsent(key,
                                        k -> new double[]{nb.riverSurfaceY, Double.MAX_VALUE, 0})[1] =
                                        Math.min(bodies.get(key)[1], c.height);
                            }
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.printf("[5] ★ 湖泊短板审计（窗口 ±%d wu，水格 %d，水体 %d）%n",
                halfWu, waterCells, bodies.size());
        System.out.println("     水面Y      水格数   紧邻旱地最低(rimMin)   违反(level−rimMin)");
        int violations = 0;
        double worst = 0;
        for (java.util.Map.Entry<Long, double[]> e : bodies.entrySet()) {
            double[] b = e.getValue();
            if (b[2] < 4) continue;                       // 过滤碎屑
            if (b[1] == Double.MAX_VALUE) continue;       // 盆沿在窗外
            double v = b[0] - b[1];
            if (v > 0.5) violations++;
            worst = Math.max(worst, v);
            System.out.printf("     %8.3f   %6d      %10.3f          %+.3f%s%n",
                    b[0], (int) b[2], b[1], v, v > 0.5 ? "  ← 违反" : "");
        }
        System.out.printf("    → 违反(>0.5 块)的水体 %d 个；最坏违反 = %+.3f 块%n", violations, worst);
        System.out.println("    判读：违反值 = 该水体上方多出的水柱高度（= 水该流走却没流走的那截）。");
    }

    /**
     * 图 E：<b>水体渲染图</b> —— 直接复现玩家看到的水平面。
     *
     * <p>蓝色 = 实际放置口径（{@code getChunkCells}，含水文雕刻）里 {@code riverType != 0}
     * 的列；其余为山体阴影灰度。<b>水面高度另存为文本</b>（便于量化"水位 vs 周边谷底"）。</p>
     */
    private static void waterView(CellGenerator gen, double wuX, double wuZ, int half, int step,
                                  String path) throws java.io.IOException {
        double hs = gen.params().horizontalScale();
        int n = 2 * half / step + 1;
        double[][] h = new double[n][n];
        boolean[][] wat = new boolean[n][n];
        double[] surf = new double[n * n];
        int bx0 = (int) Math.floor((wuX - half) * hs), bx1 = (int) Math.floor((wuX + half) * hs);
        int bz0 = (int) Math.floor((wuZ - half) * hs), bz1 = (int) Math.floor((wuZ + half) * hs);
        GeoGenesisTerrain t = new GeoGenesisTerrain(gen);
        int kc = 0;
        for (int cx = bx0 >> 4; cx <= bx1 >> 4; cx++) {
            for (int cz = bz0 >> 4; cz <= bz1 >> 4; cz++) {
                Cell[] cs = t.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        double wx = (cx * 16 + lx) / hs, wz = (cz * 16 + lz) / hs;
                        int pi = (int) Math.round((wx - (wuX - half)) / step);
                        int pj = (int) Math.round((wz - (wuZ - half)) / step);
                        if (pi < 0 || pi >= n || pj < 0 || pj >= n) continue;
                        Cell c = cs[lx * 16 + lz];
                        h[pj][pi] = c.height;
                        wat[pj][pi] = c.riverType != 0;
                        if (wat[pj][pi]) surf[kc++] = c.riverSurfaceY;
                    }
                }
            }
        }
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (wat[j][i]) { img.setRGB(i, j, 0x2E6FD6); continue; }
                double dzdx = (i + 1 < n ? h[j][i + 1] - h[j][i] : 0) / step;
                double dzdy = (j + 1 < n ? h[j + 1][i] - h[j][i] : 0) / step;
                double shade = 0.65 + 0.35 * (-(dzdx / 2.0) * 0.7071 - (dzdy / 2.0) * 0.7071);
                int g = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, shade)));
                img.setRGB(i, j, (g << 16) | (g << 8) | g);
            }
        }
        java.io.File f = new java.io.File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", f);
        double[] s = Arrays.copyOf(surf, kc);
        Arrays.sort(s);
        System.out.printf("     已写图 E「%s」→ %s（%d×%d px；水体 %d px，水面 Y %.3f ~ %.3f）%n",
                title(path), f.getAbsolutePath(), n, n, kc,
                kc == 0 ? 0 : s[0], kc == 0 ? 0 : s[kc - 1]);
    }

    private static String title(String p) { return p.substring(p.lastIndexOf('/') + 1); }

    /**
     * 图 D：把该坐标附近渲染成<b>山体阴影 PNG</b>（灰度），供人眼判断"直线/棱线"在哪。
     *
     * <p>夸张系数按 {@code |∇h|} 的 p99 自动归一 ⇒ 平缓地形也能显出结构（否则一片均匀灰）。
     * 光源来自西北。仅读 {@code sampleWu().height}（真实最终地形，零近似）。</p>
     */
    private static void pngView(CellGenerator gen, double wuX, double wuZ, int half, int step,
                                String path, String title) throws java.io.IOException {
        int n = 2 * half / step + 1;
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] = gen.sampleWu(wuX - half + i * step, wuZ - half + j * step).height;
            }
        }
        double[][] gx = new double[n][n];
        double[][] gy = new double[n][n];
        double[] mags = new double[n * n];
        int k = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                gx[j][i] = ((i + 1 < n ? h[j][i + 1] : h[j][i]) - (i > 0 ? h[j][i - 1] : h[j][i]))
                        / (2.0 * step);
                gy[j][i] = ((j + 1 < n ? h[j + 1][i] : h[j][i]) - (j > 0 ? h[j - 1][i] : h[j][i]))
                        / (2.0 * step);
                mags[k++] = Math.hypot(gx[j][i], gy[j][i]);
            }
        }
        double[] sorted = Arrays.copyOf(mags, mags.length);
        Arrays.sort(sorted);
        double p99 = Math.max(1e-6, sorted[(int) (sorted.length * 0.99)]);
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double nx = gx[j][i] / p99;
                double ny = gy[j][i] / p99;
                double shade = 0.65 + 0.35 * (-nx * 0.7071 - ny * 0.7071);
                int g = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, shade)));
                img.setRGB(i, j, (g << 16) | (g << 8) | g);
            }
        }
        java.io.File f = new java.io.File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", f);
        System.out.printf("     已写图 D「%s」→ %s（%d×%d px，p99|∇|=%.4f 块/wu）%n",
                title, f.getAbsolutePath(), n, n, p99);
    }

    /**
     * 图 C：<b>逐格 |ΔY| 台阶图</b> —— 只读真实最终地形 {@code sampleWu().height}（零近似）。
     *
     * <p>符号 = 该格到右邻/下邻的最大高度差（块/格）：
     * {@code .} &lt;0.5 · {@code :} &lt;1 · {@code o} &lt;2 · {@code #} ≥2。
     * 顶部标尺 {@code ^} 标出 tile 网格线（每 48wu）⇒ <b>异常是否与分块对齐，一眼可判</b>。</p>
     *
     * <p>⚠ 阈值是【绝对值】而非常规坡度归一：本项目地形平缓（实测该处 ~0.03wu/格），
     * 故正常坡在图上是一片 {@code .}，<b>任何真台阶都会以 {@code : o #} 凸显出来</b>。</p>
     */
    private static void stepMapView(CellGenerator gen, double wuX, double wuZ,
                                    int half, int step, String title) {
        int n = 2 * half / step + 1;
        double[][] h = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[j][i] = gen.sampleWu(wuX - half + i * step, wuZ - half + j * step).height;
            }
        }
        System.out.println();
        System.out.printf("[1d] %s：中心 wu=(%.0f,%.0f)，%d×%d 格，每格 %dwu%n",
                title, wuX, wuZ, n, n, step);
        System.out.println("     图C 台阶 |ΔY|（块/格）：. <0.5   : <1   o <2   # ≥2");
        System.out.println("     标尺 ^ = tile 网格线（每 48wu）；若某条直线恒在 ^ 上 ⇒ 分块伪影");
        StringBuilder ruler = new StringBuilder("     ");
        for (int i = 0; i < n; i++) {
            double x = wuX - half + i * step;
            boolean tile = Math.floorDiv((int) Math.floor(x), TILE_CENTER) * TILE_CENTER
                    == (int) Math.floor(x);
            ruler.append(tile ? '^' : ' ');
        }
        System.out.println(ruler);
        for (int j = 0; j < n; j++) {
            StringBuilder sb = new StringBuilder("     ");
            for (int i = 0; i < n; i++) {
                double s = 0;
                if (i + 1 < n) s = Math.max(s, Math.abs(h[j][i + 1] - h[j][i]));
                if (j + 1 < n) s = Math.max(s, Math.abs(h[j + 1][i] - h[j][i]));
                sb.append(s < 0.5 ? '.' : (s < 1.0 ? ':' : (s < 2.0 ? 'o' : '#')));
            }
            System.out.println(sb);
        }
    }

    /**
     * 打印一张诊断图。图 A = 主导类型（tile 网格线为 '+'）；
     * 图 B = <b>类型边界走向</b>：{@code |} 与左邻不同（竖走近）、{@code -} 与上邻不同（横走近）、
     * {@code +} 两者皆不同（角/交汇）、{@code .} 无边。
     * <p>图 B 是判断"长直线"的关键：规则网格 Voronoi 会让边界沿直线排列成整排的 {@code |} 或 {@code -}。</p>
     */
    private static void mapView(CellGenerator gen, double wuX, double wuZ, int half, int step, String title) {
        int n = 2 * half / step + 1;
        TerrainClass[][] t = new TerrainClass[n][n];
        double[][] g = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                Cell c = gen.sampleWu(wuX - half + i * step, wuZ - half + j * step);
                t[j][i] = c.terrainType;
                g[j][i] = c.gradient;
            }
        }
        System.out.println();
        System.out.printf("[1c] %s：中心 wu=(%.0f,%.0f)，%d×%d 格%n", title, wuX, wuZ, n, n);
        // ★ 钉死"块状量化"来自哪条路径：对比 sample()（无侵蚀）与 sampleWu()（含侵蚀 tile）。
        //   指标：某个坐标的 terrainType 是否等于【其所在 48wu tile 中心】的 terrainType。
        int sameNoEro = 0, sameEro = 0, total = 0, diffTwo = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = wuX - half + i * step, z = wuZ - half + j * step;
                int tx = Math.floorDiv((int) Math.floor(x), TILE_CENTER) * TILE_CENTER + TILE_CENTER / 2;
                int tz = Math.floorDiv((int) Math.floor(z), TILE_CENTER) * TILE_CENTER + TILE_CENTER / 2;
                TerrainClass a = gen.sample(x, z).terrainType;
                TerrainClass b = gen.sampleWu(x, z).terrainType;
                TerrainClass ac = gen.sample(tx, tz).terrainType;
                TerrainClass bc = gen.sampleWu(tx, tz).terrainType;
                total++;
                if (a == ac) sameNoEro++;
                if (b == bc) sameEro++;
                if (a != b) diffTwo++;
            }
        }
        System.out.printf("     ★ 类型是否等于【本 48wu tile 中心】的类型（块内一致性）：%n");
        System.out.printf("        sample()（无侵蚀）  : %d/%d = %.1f%%%n",
                sameNoEro, total, 100.0 * sameNoEro / total);
        System.out.printf("        sampleWu()（含侵蚀）: %d/%d = %.1f%%%n",
                sameEro, total, 100.0 * sameEro / total);
        System.out.printf("        两条路径类型不同的格 : %d/%d = %.1f%%%n",
                diffTwo, total, 100.0 * diffTwo / total);
        System.out.println("     图A 主导类型 B盆地 P平原 H丘陵 L高原 M山地 O海 D深海（+ = tile 网格线）");
        System.out.println("     图B 边界走向 | 竖走近  - 横走近  + 交汇  . 无边（# = 陡坡裸岩）");
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < n; j++) {
            a.append("     ");
            b.append("     ");
            for (int i = 0; i < n; i++) {
                double x = wuX - half + i * step, z = wuZ - half + j * step;
                boolean grid = Math.floorDiv((int) Math.floor(x), TILE_CENTER) * TILE_CENTER == (int) Math.floor(x)
                        || Math.floorDiv((int) Math.floor(z), TILE_CENTER) * TILE_CENTER == (int) Math.floor(z);
                a.append(grid ? '+' : typeLetter(t[j][i]));
                boolean vd = i > 0 && t[j][i - 1] != t[j][i];
                boolean hd = j > 0 && t[j - 1][i] != t[j][i];
                char cb = vd && hd ? '+' : (vd ? '|' : (hd ? '-' : (g[j][i] > 0.40 ? '#' : '.')));
                b.append(grid ? '+' : cb);
            }
            a.append('\n');
            b.append('\n');
        }
        System.out.println(a);
        System.out.println(b);
        if (!UNKNOWN.isEmpty()) {
            System.out.println("     ⚠ 本图出现的非基础类型（小写字母 = 其名字首字母）：" + UNKNOWN);
        }
    }

    /** 未知类型名（一次性收集，打印图例用）——避免"用猜的符号代替真值"。 */
    private static final java.util.LinkedHashSet<String> UNKNOWN = new java.util.LinkedHashSet<>();

    private static char typeLetter(TerrainClass t) {
        return switch (t) {
            case BASIN -> 'B';
            case PLAIN -> 'P';
            case HILLS -> 'H';
            case PLATEAU -> 'L';
            case MOUNTAINS -> 'M';
            case OCEAN -> 'O';
            case DEEP_OCEAN -> 'D';
            default -> {
                UNKNOWN.add(t.name());
                yield Character.toLowerCase(t.name().charAt(0));
            }
        };
    }

    // ==================================================================
    // [1] Q1：delta 的缓存依赖（= 偶发接缝的直接证据）
    // ==================================================================

    /** 在 tile(0,0) 右缘 blend 带内取点，比较"邻居未缓存 vs 已缓存"两种状态下读到的 delta。 */
    private static void sectionSeam(CellGenerator gen, long seed) {
        // tile(0,0) 覆盖 wuX∈[0,48)；右缘 blend 带 = wuX ∈ [48-6, 48) = [42,48)
        final int edge = TILE_CENTER;                 // = 48
        final double zLine = 20.0;                    // z 取 tile 内部（避免同时触发 z 向 blend）

        double[] a = new double[BLEND_START];
        for (int k = 0; k < BLEND_START; k++) {
            double x = edge - BLEND_START + k + 0.5;  // 42.5 .. 47.5
            a[k] = gen.erosionDeltaE(x, zLine);       // Arm A：邻居(48,0) 尚未缓存
        }
        // ★ 预热右邻居 tile (48,0)：采一个【属于它的内部点】——erosionDeltaE 只生成自己的 tile，
        //   而 60.5 距该 tile 左缘 12.5wu（> BLEND_START）⇒ 不触发任何邻居查询。
        //   （getOrGenTile 是 private，故用此公开 API 路径达到同样效果。）
        double warm = gen.erosionDeltaE(edge + 12.5, zLine);
        double[] b = new double[BLEND_START];
        for (int k = 0; k < BLEND_START; k++) {
            double x = edge - BLEND_START + k + 0.5;
            b[k] = gen.erosionDeltaE(x, zLine);       // Arm B：邻居已缓存
        }
        System.out.println();
        System.out.println("[1] Q1 接缝 / 确定性：delta 对【邻居 tile 缓存状态】的依赖");
        System.out.printf("    已预热右邻居 tile(48,0)（其内部点 delta=%.8f）%n", warm);
        System.out.println("    x(wu)      ArmA(邻居未缓存)   ArmB(邻居已缓存)   差");
        double maxDiff = 0;
        for (int k = 0; k < BLEND_START; k++) {
            double d = Math.abs(a[k] - b[k]);
            maxDiff = Math.max(maxDiff, d);
            System.out.printf("    %6.1f   %18.8f   %18.8f   %.8f%n",
                    edge - BLEND_START + k + 0.5, a[k], b[k], d);
        }
        System.out.printf("    → max|A−B| = %.8f（e 单位）%n", maxDiff);
        System.out.println("    判读：>0 即证明【同一坐标的 delta 随缓存状态变化】= 非确定性 ⇒ 偶发接缝。");
        System.out.println("    备注：1 块 ≈ 1/192 e ≈ 0.0052e（据此换算可见性）。");
    }

    // ==================================================================
    // [2] 固有边缘跳变：跨 tile 边界的 delta 阶跃（raw，未经 blend 抵消）
    // ==================================================================

    /** 沿 x 以 1wu 步长跨过 x=48 边界，观察 delta 的阶跃幅度。 */
    private static void sectionEdgeJump(CellGenerator gen) {
        System.out.println();
        System.out.println("[2] 固有边缘跳变：沿 x 跨过 tile 边界 x=48 的 delta 阶跃");
        final int edge = TILE_CENTER;
        final double z = 20.0;
        double prev = Double.NaN;
        double maxJump = 0, maxJumpAt = Double.NaN;
        System.out.print("    ");
        for (int x = edge - 8; x <= edge + 8; x++) {
            double d = gen.erosionDeltaE(x + 0.5, z);
            if (!Double.isNaN(prev)) {
                double j = Math.abs(d - prev);
                if (j > maxJump) { maxJump = j; maxJumpAt = x + 0.5; }
            }
            prev = d;
            if (x % 4 == 0) System.out.printf("%n    x=%4d d=%10.7f", x, d);
        }
        System.out.println();
        System.out.printf("    → 窗口内 max 阶跃 = %.8f e（≈ %.2f 块）@ x=%.1f%n",
                maxJump, maxJump * 192.0, maxJumpAt);
        System.out.println("    判读：若该阶跃明显大于窗口内平均 |Δ|，即为可见接缝的第二个来源。");
    }

    // ==================================================================
    // [3] Q2：规划场（无侵蚀）vs 最终场（含侵蚀）的分歧
    // ==================================================================

    /** 在同一批点上比较 heightFromE(terrainEQuick) 与 sampleWu().height。 */
    private static void sectionPlanVsFinal(CellGenerator gen, int span) {
        int n = 0;
        double sum = 0, maxAbs = 0, maxAt = 0;
        double[] diffs = new double[span * span];
        int flips = 0, minima = 0;
        int half = span / 2;

        double[] plan = new double[span * span];
        double[] fin = new double[span * span];
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) {
                double x = -half + i, z = -half + j;
                plan[j * span + i] = gen.heightCurve().heightFromE(gen.terrainEQuick(x, z));
                fin[j * span + i] = gen.sampleWu(x, z).height;
                diffs[n++] = fin[j * span + i] - plan[j * span + i];
            }
        }
        double[] sorted = Arrays.copyOf(diffs, n);
        Arrays.sort(sorted);
        for (int k = 0; k < n; k++) {
            double d = Math.abs(diffs[k]);
            sum += d;
            if (d > maxAbs) { maxAbs = d; maxAt = k; }
        }
        // 局部最低点（3×3 严格低）在两张场里的判定是否一致
        for (int i = 1; i < span - 1; i++) {
            for (int j = 1; j < span - 1; j++) {
                int idx = j * span + i;
                boolean lp = isLocalMin(plan, span, i, j);
                boolean lf = isLocalMin(fin, span, i, j);
                if (lp || lf) minima++;
                if (lp != lf) flips++;
            }
        }
        System.out.println();
        System.out.printf("[3] Q2 规划场 vs 最终场（含侵蚀）—— %d×%d 点，span=%d wu%n", span, span, span);
        System.out.printf("    Δ = 最终 − 规划（块）：均值 %.3f  中位 %.3f  p95 %.3f  p99 %.3f  max %.3f%n",
                sum / n, sorted[n / 2], sorted[(int) (n * 0.95)], sorted[(int) (n * 0.99)],
                sorted[n - 1]);
        System.out.printf("    |Δ| > 1 块 的点: %d / %d (%.2f%%)%n",
                countAbove(diffs, 1.0), n, 100.0 * countAbove(diffs, 1.0) / n);
        System.out.printf("    |Δ| > 4 块 的点: %d / %d (%.2f%%)%n",
                countAbove(diffs, 4.0), n, 100.0 * countAbove(diffs, 4.0) / n);
        System.out.printf("    ★ 局部最低点判定翻转：%d / %d（占局部最低点 %d 个的 %.1f%%）%n",
                flips, minima, minima, minima == 0 ? 0.0 : 100.0 * flips / minima);
        System.out.println("    判读：翻转率 = 「规划场里是洼地、最终场里不是（或反之）」的比例");
        System.out.println("          = 湖泊填洼层（建在规划场）与实际地形不符的直接占比。");
    }

    private static boolean isLocalMin(double[] a, int n, int i, int j) {
        double v = a[j * n + i];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                if (a[(j + dj) * n + (i + di)] < v) return false;
            }
        }
        return true;
    }

    private static int countAbove(double[] a, double t) {
        int c = 0;
        for (double v : a) if (Math.abs(v) > t) c++;
        return c;
    }
}
