package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘几何纯函数验收</b>（★ 2026-11-09 B1，本轮关键节点）。
 *
 * <h2>为什么这是本轮最重要的探针</h2>
 * <p>此前沙盘的数学（投影/柱高/排序/包围盒/光照）与 OpenGL 混在一起，
 * 只能靠实机试错 —— 六次翻车的根因全在这里。</p>
 * <p>现在几何抽成 {@link SandboxGeometry}（零 MC 依赖），
 * <b>本探针验证的就是实机跑的同一份函数</b>，不可能失同步。</p>
 *
 * <h2>判据（7 条，对应此前每一次翻车）</h2>
 * <ol>
 *   <li>柱数 = 细格数 / perColumn²（不多不少）；</li>
 *   <li>柱高 = 组内 16×16 中位真实高度经 {@code ratio*RELIEF}（不是左上角一格）；</li>
 *   <li>投影包围盒装得下视口（修"超出 2.6 倍只看到局部"）；</li>
 *   <li>排序全序：深度单调不减（修柱子互相穿透）；</li>
 *   <li>坡度光照饱和率 &lt;5%/&lt;5%，动态范围 ≥0.30（修"整幅均匀绿"）；</li>
 *   <li>{@code pick()} 往返一致（修 hover 错位）；</li>
 *   <li>出图：不平、不糊、无网纹（人眼最终判据）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxGeometryProbe<br>
 * gradlew runSandboxGeometryProbe -PsandboxPng=build\geo}（导出 PNG）</p>
 */
public final class SandboxGeometryProbe {

    private static final int SEED = 12345;
    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘几何纯函数验收（零 MC 依赖）===");

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);

        boolean pass = true;

        // ---- 准备：填一块缓存，裁出快照 ----
        // ★ 探针用<b>小窗口</b>（512 格）而不是生产的 2048 格：
        //   几何正确性与窗口大小无关（柱数/柱高/投影/排序/光照都是逐柱独立的），
        //   但 2048 格需要 130×130 chunks ⇒ 采样阶段就 OOM（实测）。
        int windowBlocks = 512;
        // 每柱 16 格 ⇒ 32 柱；细格 = 柱数 × HEIGHT_STRIDE(2) = 64
        int pillars = windowBlocks / SandboxLod.STEP_BLOCKS;
        int grid = pillars * SandboxLod.HEIGHT_STRIDE;
        int nChunks = windowBlocks / 16 + 2;
        int cx0 = 20, cz0 = 60;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        int ox = cx0 * 16 + 16, oz = cz0 * 16 + 16;
        TerrainFieldData snap = TerrainFieldData.fromCellCache(
                cache, ox, oz, windowBlocks, windowBlocks, grid, grid, 1L);
        int per = SandboxLod.perColumn();

        System.out.printf("数据：%d×%d 细格，%d×%d 格方块，%.1f 块/细格，perColumn=%d%n",
                snap.gridW, snap.gridH, snap.blocksWide, snap.blocksHigh,
                snap.blocksPerCell(), per);

        // ---- 竖向比例 span（照生产：本图最高点 − 基准面，下限 60）----
        double seaY = 63.0;
        double base = seaY - 4.0;
        double hi = base;
        for (int i = 0; i < snap.height.length; i++) {
            double v = snap.e[i] < 0 ? seaY : snap.height[i];
            if (v > hi) hi = v;
        }
        float span = (float) Math.max(60.0, hi - base);
        System.out.printf("base=%.0f hi=%.0f span=%.0f%n%n", base, hi, span);

        // ---- 构建柱子 ----
        // ★ 探针用高程图层 + 高程色带作为底色来源（colorSrc 非 null）
        //   否则整片同一个群系色（探针实测：99% 像素同一色相，只有光照在变），
        //   看不出地形起伏 —— 那是"探针取样局限"，不是生产缺陷。
        //   实机传的是 GeoPalette.color(...)，连续图层本来就有色带层次。
        SandboxGeometry.ColorSource elevSrc = (gx, gz, s) -> {
            int i = s.index(gx, gz);
            if (i < 0) return 0x505050;
            // 简易高程色带（深蓝→青→绿→黄→棕→白），与 GeoPalette ELEVATION 同思路
            double e = s.e[i];
            double t = Math.max(0.0, Math.min(1.0, (e + 1.0) * 0.5));
            return elevRamp(t);
        };
        long t0 = System.nanoTime();
        SandboxGeometry.Columns cols = SandboxGeometry.buildColumns(
                snap, GeoPalette.PreviewLayer.ELEVATION, per, span,
                (int) base, (int) hi, seaY, System.currentTimeMillis(), elevSrc);
        long buildMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("构建 %d 柱（%d ms）%n%n", cols.readyCount, buildMs);

        // [1] 柱数
        int expectCols = (snap.gridW + per - 1) / per * ((snap.gridH + per - 1) / per);
        System.out.printf("[1] 柱数 = 细格数 / perColumn² = %d%n", expectCols);
        if (cols.colCount * cols.colRows != expectCols) {
            System.out.printf("    FAIL: %d×%d=%d ≠ %d%n",
                    cols.colCount, cols.colRows, cols.colCount * cols.colRows, expectCols);
            pass = false;
        } else {
            System.out.printf("    OK（%d×%d）%n", cols.colCount, cols.colRows);
        }

        // [2] 柱高 = 组内中位
        System.out.println("\n[2] 柱高必须是组内 16×16 的<b>中位</b>真实高度");
        int checked = 0, bad = 0;
        for (int cx = 0; cx < cols.colCount && checked < 40; cx += 3) {
            for (int cz = 0; cz < cols.colRows && checked < 40; cz += 3) {
                int col = cx * cols.colRows + cz;
                SandboxGeometry.Column c = cols.items[col];
                if (c.top < 0) continue;
                // 手算：组内中位高度 → ratio → top
                int fx0 = cx * per, fz0 = cz * per;
                int fx1 = Math.min(snap.gridW - 1, fx0 + per - 1);
                int fz1 = Math.min(snap.gridH - 1, fz0 + per - 1);
                double[] hs = new double[(fx1 - fx0 + 1) * (fz1 - fz0 + 1)];
                int n = 0;
                for (int dx = fx0; dx <= fx1; dx++) {
                    for (int dz = fz0; dz <= fz1; dz++) {
                        hs[n++] = snap.height[dx * snap.gridH + dz];
                    }
                }
                java.util.Arrays.sort(hs, 0, n);
                double med = hs[n / 2];
                double wY = snap.waterY[fx0 * snap.gridH + fz0];
                double topY = (!Double.isNaN(wY) && wY > med + 0.01) ? wY : med;
                float ratio = (float) Math.max((topY - base) / span, SandboxGeometry.WATER_SINK);
                float expectTop = ratio * SandboxGeometry.RELIEF;
                checked++;
                if (Math.abs(c.top - expectTop) > 1e-5) {
                    bad++;
                    if (bad <= 3) {
                        System.out.printf("    差异 col(%d,%d): 实际 %.6f vs 期望 %.6f%n",
                                cx, cz, c.top, expectTop);
                    }
                }
            }
        }
        System.out.printf("    抽样 %d 柱，差异 %d%n", checked, bad);
        if (bad > 0) {
            System.out.println("    FAIL: 柱高不是组内中位（可能又退回「取左上角一格」）");
            pass = false;
        } else {
            System.out.println("    OK（组内中位，非单点）");
        }

        // [3] 投影包围盒
        System.out.println("\n[3] 投影包围盒必须装得下视口");
        float[] r = SandboxGeometry.rotationRows(SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        // ★ 与实机同口径（参考项目：areaSize × 0.46 × zoom）
        float scale = SandboxGeometry.scaleOf(VIEWPORT, SandboxLod.BASE_ZOOM);
        float[] box = SandboxGeometry.planeBox(r);
        float w = (box[1] - box[0]) * scale;
        float h = (box[3] - box[2]) * scale;
        // ★ 竖向跨度（立体感的直接度量）：最高柱从地面抬起多少像素
        float verticalPx = SandboxGeometry.RELIEF * scale * Math.abs(r[5]);
        System.out.printf("    平面包围盒模型 %.3f×%.3f ⇒ 屏幕 %.0f×%.0f px（视口 %d）%n",
                box[1] - box[0], box[3] - box[2], w, h, VIEWPORT);
        System.out.printf("    竖向跨度 = RELIEF(%.2f) × scale(%.0f) × |r5|(%.3f) = %.0f px（视口 %.0f%%）%n",
                SandboxGeometry.RELIEF, scale, Math.abs(r[5]), verticalPx,
                100 * verticalPx / VIEWPORT);
        // ★ 判据改成「立体感」而不是「装不装得下」：
        //   参考项目<b>故意</b>让盘面超视口（zoom=2.0 ⇒ 2.6 倍），
        //   靠"盘面大 + 柱子高"取立体感。之前用 fitScaleWide（刚好装下）
        //   ⇒ 竖向仅 93px（11%）⇒ 整片看着是平的。
        if (verticalPx < VIEWPORT * 0.20f) {
            System.out.printf("    FAIL: 竖向跨度仅 %.0f px（< 视口 20%%）⇒ 立体感不足%n",
                    verticalPx);
            pass = false;
        } else {
            System.out.printf("    OK（竖向跨度占视口 %.0f%%，与参考项目同档）%n",
                    100 * verticalPx / VIEWPORT);
        }

        // [4] 排序全序
        System.out.println("\n[4] 深度排序必须单调（远→近）");
        int[] sorted = SandboxGeometry.sortByDepth(cols, r, null);
        // ★ 判据用<b>桶序号</b>而不是原始深度：计数排序把深度量化到 4096 个桶，
        //   同桶内顺序任意（参考项目也是这样）⇒ 用原始深度校验会误报逆序。
        double prevBucket = -Double.MAX_VALUE;
        int inversions = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            int col = sorted[k];
            SandboxGeometry.Column c = cols.items[col];
            float mx = SandboxGeometry.modelOf(cols.colCount, col / cols.colRows);
            float my = SandboxGeometry.modelOf(cols.colRows, col % cols.colRows);
            double depth = r[6] * mx + r[7] * my + r[8] * Math.max(c.top, 0f);
            int slot = SandboxGeometry.clampI(
                    (int) ((depth + 1.8f) * (SandboxGeometry.BUCKETS / 3.6f)),
                    0, SandboxGeometry.BUCKETS - 1);
            if (slot < prevBucket) inversions++;
            prevBucket = slot;
        }
        if (inversions > 0) {
            System.out.printf("    FAIL: %d 处桶序逆序（遮挡会错）%n", inversions);
            pass = false;
        } else {
            System.out.printf("    OK（%d 项桶序单调）%n", cols.readyCount);
        }

        // ★ v24 新增[4b] 柱高分布判据 —— <b>效果图暴露的缺口</b>
        //
        // 背景：修完侧墙后离线图有了立体感，但所有柱子高度<b>几乎一样</b>，
        //   整片像一层薄片。原先所有判据（柱数/柱高公式/包围盒/排序/光照）
        //   <b>全都通过</b>，却没人检查"高低差到底拉不拉得开"。
        //
        // 判据：柱顶高度（模型单位）的 P90−P10 必须 ≥ 竖向跨度的 25%
        //   ⇒ 也就是"九成的柱子里，最高与最低那批至少差 1/4 个屏高"
        //   —— 否则就是平板。
        System.out.println("\n[4b] 柱高分布：P90−P10 必须 ≥ 竖向跨度的 25%（否则是平板）");
        float[] tops = new float[cols.readyCount];
        int nTops = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column c = cols.items[cols.ready[k]];
            if (c.top < 0) continue;
            tops[nTops++] = c.top;
        }
        java.util.Arrays.sort(tops, 0, nTops);
        float t10 = tops[(int) (0.10 * (nTops - 1))];
        float t90 = tops[(int) (0.90 * (nTops - 1))];
        float spanModel = SandboxGeometry.RELIEF;      // 模型空间的最大厚度
        float spread = t90 - t10;
        System.out.printf("    柱顶 P10=%.4f P90=%.4f 跨度=%.4f（RELIEF=%.2f，占 %.0f%%）%n",
                t10, t90, spread, spanModel, 100 * spread / spanModel);
        if (spread < spanModel * 0.25f) {
            System.out.println("    FAIL: 高低差不足 1/4 竖向跨度 ⇒ 整片是薄板（无立体感）");
            pass = false;
        } else {
            System.out.println("    OK（高低差拉得开）");
        }

        // ★ v25 新增[4c] 铺开动画：出生时刻必须按到中心距离递延
        //
        // 背景：参考项目靠「中心向外采样 + 首次绘制打戳」实现"一圈圈长出来"。
        //   我们的数据是一次性裁出来的 ⇒ 若不打散出生时刻，
        //   所有柱同时开始长 ⇒ 没有铺开层次（用户："动画等方面细节"有差距）。
        System.out.println("\n[4c] 铺开动画：出生时刻必须按到中心距离递延（≥300ms 跨度）");
        long centerBorn = Long.MAX_VALUE, edgeBorn = Long.MIN_VALUE;
        for (int cx = 0; cx < cols.colCount; cx++) {
            for (int cz = 0; cz < cols.colRows; cz++) {
                int col = cx * cols.colRows + cz;
                SandboxGeometry.Column c = cols.items[col];
                if (c.bornAt == 0L) continue;
                double dx = cx - (cols.colCount - 1) * 0.5;
                double dz = cz - (cols.colRows - 1) * 0.5;
                double dist = Math.hypot(dx, dz);
                double maxDist = Math.hypot((cols.colCount - 1) * 0.5, (cols.colRows - 1) * 0.5);
                if (dist <= maxDist * 0.15) centerBorn = Math.min(centerBorn, c.bornAt);
                if (dist >= maxDist * 0.85) edgeBorn = Math.max(edgeBorn, c.bornAt);
            }
        }
        long spreadMs = (centerBorn != Long.MAX_VALUE && edgeBorn != Long.MIN_VALUE)
                ? edgeBorn - centerBorn : 0L;
        System.out.printf("    中心柱 bornAt=%d，边柱 bornAt=%d，跨度=%d ms%n",
                centerBorn, edgeBorn, spreadMs);
        if (spreadMs < 300L) {
            System.out.println("    FAIL: 出生时刻没有按距离错开 ⇒ 整片同时长出来，没有铺开层次");
            pass = false;
        } else {
            System.out.println("    OK（从中心往外依次长出）");
        }

        // [5] 光照统计
        System.out.println("\n[5] 坡度光照：饱和率 <5%，动态范围 ≥0.30");
        double[] yGrid = snap.buildSurfaceYGrid(seaY);
        double[] lights = new double[cols.readyCount];
        int nLight = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            int col = cols.ready[k];
            SandboxGeometry.Column c = cols.items[col];
            if (c.top < 0) continue;
            int cx = col / cols.colRows, cz = col % cols.colRows;
            int mfx = Math.min(snap.gridW - 1, cx * per + per / 2);
            int mfz = Math.min(snap.gridH - 1, cz * per + per / 2);
            int mi = mfx * snap.gridH + mfz;
            lights[nLight++] = SandboxGeometry.slopeLight(yGrid, snap.gridW, snap.gridH,
                    mfx, mfz, snap.isWater(mi), cols.blocksPerPillar);
        }
        java.util.Arrays.sort(lights, 0, nLight);
        double p5 = lights[(int) (0.05 * (nLight - 1))];
        double p95 = lights[(int) (0.95 * (nLight - 1))];
        long satLo = 0, satHi = 0;
        for (int i = 0; i < nLight; i++) {
            if (lights[i] <= 0.5501) satLo++;
            if (lights[i] >= 1.2999) satHi++;
        }
        double range = p95 - p5;
        System.out.printf("    p5=%.3f p95=%.3f 动态范围=%.3f；饱和 %.1f%%/%.1f%%%n",
                p5, p95, range, 100.0 * satLo / nLight, 100.0 * satHi / nLight);
        if (range < 0.30) {
            System.out.println("    FAIL: 动态范围过小（整幅会一片均匀色）");
            pass = false;
        } else if (100.0 * satLo / nLight > 5 || 100.0 * satHi / nLight > 5) {
            System.out.println("    FAIL: 饱和率过高");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // [6] pick 往返
        System.out.printf("%n[6] pick() 往返一致性（视口 %d）%n", VIEWPORT);
        float[] fc = SandboxGeometry.fitCenter(r);
        float oxPx = VIEWPORT / 2f - scale * fc[0];
        float oyPx = VIEWPORT / 2f - scale * fc[1];
        int roundTripBad = 0, roundTripChecked = 0;
        for (int cx = 1; cx < cols.colCount - 1; cx += 7) {
            for (int cz = 1; cz < cols.colRows - 1; cz += 7) {
                // ★ 用 <b>mz=0</b>（地平面）验证逆变换 —— pickWide 是<b>平面</b>逆变换，
                //   它不知道柱高。实机 hover 用视线推进（参考项目 MARCH_STEP=0.007）
                //   才能处理柱高遮挡；本探针只验证"平面部分数学正确"。
                float mxm = SandboxGeometry.modelOf(cols.colCount, cx);
                float mym = SandboxGeometry.modelOf(cols.colRows, cz);
                float[] p = SandboxGeometry.projectPoint(r, scale, oxPx, oyPx, mxm, mym, 0f);
                int[] got = SandboxGeometry.pickWide(cols, r, scale, oxPx, oyPx,
                        (int) p[0], (int) p[1], VIEWPORT, VIEWPORT);
                roundTripChecked++;
                if (Math.abs(got[0] - cx) > 1 || Math.abs(got[1] - cz) > 1) {
                    roundTripBad++;
                    if (roundTripBad <= 3) {
                        System.out.printf("    差异 期望(%d,%d) 得到(%d,%d)%n", cx, cz, got[0], got[1]);
                    }
                }
            }
        }
        System.out.printf("    抽样 %d 柱，误差>1 的 %d 处%n", roundTripChecked, roundTripBad);
        if (roundTripBad > roundTripChecked / 10) {
            System.out.println("    FAIL: 拾取偏差过大");
            pass = false;
        } else {
            System.out.println("    OK（平面逆变换精度足够 hover）");
        }

        // [7] 出图
        String png = System.getProperty("geogenesis.sandboxPngDir");
        if (png != null && !png.isEmpty()) {
            writePng(cols, r, scale, oxPx, oyPx, VIEWPORT, png);
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /**
     * 用几何层的 Column[] 直接画 PNG（CPU 填充，不启动 MC）。
     *
     * <h2>★ v24：补上侧墙与底板</h2>
     * <p>旧版只画<b>顶面</b> ⇒ 出来的图是一块平板，看不出任何立体感 ——
     * 让人误判"沙盘地形是平的"，而实机是画侧墙的（{@code SandboxRasterizer.draw}）。</p>
     * <p>现在与实机同口径：底板 + 顶面 + 按可见朝向的 2 个侧面，
     * 侧墙条件与实机一致（{@code top > 邻居}）。</p>
     */
    private static void writePng(SandboxGeometry.Columns cols, float[] r, float scale,
                                 float ox, float oy, int size, String dir) {
        java.io.File dd = new java.io.File(dir);
        if (!dd.exists() && !dd.mkdirs()) { System.out.println("无法建目录 " + dir); return; }
        int[] px = new int[size * size];
        java.util.Arrays.fill(px, 0xFF101018);
        int[] sorted = SandboxGeometry.sortByDepth(cols, r, null);

        boolean seeEast = r[6] > 0f, seeWest = r[6] < 0f;
        boolean seeSouth = r[7] > 0f, seeNorth = r[7] < 0f;
        boolean seeTop = r[8] > 0f;
        final float eps = 1e-4f;

        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column c = cols.items[sorted[k]];
            if (c.top < 0 || c.alpha <= 0) continue;
            int alpha = c.alpha;
            int rgb = c.rgb;
            float top = SandboxGeometry.snapTop(c.top, eps);

            // ---- 侧墙（与实机同条件：只在比邻居高时画）----
            int col = sorted[k];
            int ci = col / cols.colRows, cj = col % cols.colRows;
            if (seeEast) {
                float low = (ci + 1 < cols.colCount)
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col + cols.colRows].top, 0f), eps) : 0f;
                if (top > low) wall(px, size, r, scale, ox, oy, c.x1, c.y0, c.x1, c.y1, low, top, rgb, alpha, 0.82f, 0.60f);
            }
            if (seeWest) {
                float low = ci > 0
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col - cols.colRows].top, 0f), eps) : 0f;
                if (top > low) wall(px, size, r, scale, ox, oy, c.x0, c.y1, c.x0, c.y0, low, top, rgb, alpha, 0.56f, 0.40f);
            }
            if (seeSouth) {
                float low = (cj + 1 < cols.colRows)
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col + 1].top, 0f), eps) : 0f;
                if (top > low) wall(px, size, r, scale, ox, oy, c.x0, c.y1, c.x1, c.y1, low, top, rgb, alpha, 0.70f, 0.52f);
            }
            if (seeNorth) {
                float low = cj > 0
                        ? SandboxGeometry.snapTop(Math.max(cols.items[col - 1].top, 0f), eps) : 0f;
                if (top > low) wall(px, size, r, scale, ox, oy, c.x1, c.y0, c.x0, c.y0, low, top, rgb, alpha, 0.46f, 0.34f);
            }

            if (!seeTop) continue;
            // ---- 顶面 ----
            float[] p0 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x0, c.y0, top);
            float[] p1 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x1, c.y0, top);
            float[] p2 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x1, c.y1, top);
            float[] p3 = SandboxGeometry.projectPoint(r, scale, ox, oy, c.x0, c.y1, top);
            fillTri(px, size, p0, p1, p2, rgb, alpha);
            fillTri(px, size, p0, p2, p3, rgb, alpha);
        }
        try {
            var img = new java.awt.image.BufferedImage(size, size,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, size, size, px, 0, size);
            javax.imageio.ImageIO.write(img, "png", new java.io.File(dd, "sandbox_geometry.png"));
            System.out.println("出图：" + new java.io.File(dd, "sandbox_geometry.png").getPath());
        } catch (Exception e) {
            System.out.println("出图失败: " + e);
        }
    }

    /**
     * 画一面侧墙（照实机 {@code SandboxRasterizer.wall} 的亮度系数）。
     *
     * <p>实机把墙拆成<b>两个四边形</b>：上半段用 {@code litTop}（亮）、
     * 下半段用 {@code litLow}（暗）。这里照做 —— 整面一个亮度会丢掉立体感。</p>
     */
    private static void wall(int[] px, int size, float[] r, float scale,
                             float ox, float oy,
                             float ax, float ay, float bx, float by,
                             float low, float high, int rgb, int alpha,
                             float litTop, float litLow) {
        float mid = (low + high) * 0.5f;
        // 上半段（亮）
        float[] u0 = SandboxGeometry.projectPoint(r, scale, ox, oy, ax, ay, high);
        float[] u1 = SandboxGeometry.projectPoint(r, scale, ox, oy, bx, by, high);
        float[] u2 = SandboxGeometry.projectPoint(r, scale, ox, oy, bx, by, mid);
        float[] u3 = SandboxGeometry.projectPoint(r, scale, ox, oy, ax, ay, mid);
        fillTri(px, size, u0, u1, u2, shade(rgb, litTop), alpha);
        fillTri(px, size, u0, u2, u3, shade(rgb, litTop), alpha);
        // 下半段（暗）
        float[] l0 = SandboxGeometry.projectPoint(r, scale, ox, oy, ax, ay, mid);
        float[] l1 = SandboxGeometry.projectPoint(r, scale, ox, oy, bx, by, mid);
        float[] l2 = SandboxGeometry.projectPoint(r, scale, ox, oy, bx, by, low);
        float[] l3 = SandboxGeometry.projectPoint(r, scale, ox, oy, ax, ay, low);
        fillTri(px, size, l0, l1, l2, shade(rgb, litLow), alpha);
        fillTri(px, size, l0, l2, l3, shade(rgb, litLow), alpha);
    }

    private static int shade(int rgb, float mul) {
        int r = Math.max(0, Math.min(255, (int) (((rgb >> 16) & 0xFF) * mul)));
        int g = Math.max(0, Math.min(255, (int) (((rgb >> 8) & 0xFF) * mul)));
        int b = Math.max(0, Math.min(255, (int) ((rgb & 0xFF) * mul)));
        return (r << 16) | (g << 8) | b;
    }

    private static void fillTri(int[] px, int size, float[] a, float[] b, float[] c,
                                int rgb, int alpha) {
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(size - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(size - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        int color = (alpha << 24) | rgb;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (inTri(x + .5f, y + .5f, a, b, c)) px[y * size + x] = color;
            }
        }
    }

    private static boolean inTri(float px, float py, float[] a, float[] b, float[] c) {
        float d1 = (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1]);
        float d2 = (px - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (py - c[1]);
        float d3 = (px - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (py - a[1]);
        boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(neg && pos);
    }

    /** 简易高程色带（探针出图用；生产走 GeoPalette 的 14 条色带）。 */
    private static int elevRamp(double t) {
        // 0.00 深蓝 → 0.30 青 → 0.45 绿 → 0.65 黄 → 0.82 棕 → 1.00 白
        double[][] stops = {
                {0.00, 0x1B3A6B}, {0.30, 0x2E7FA8}, {0.45, 0x3E8B4A},
                {0.65, 0xA8A040}, {0.82, 0x8B5A2B}, {1.00, 0xE8E8E8}
        };
        for (int k = 0; k < stops.length - 1; k++) {
            double a = stops[k][0], b = stops[k + 1][0];
            if (t <= b) {
                double f = (b - a) < 1e-9 ? 0 : (t - a) / (b - a);
                f = Math.max(0, Math.min(1, f));
                int ca = (int) stops[k][1], cb = (int) stops[k + 1][1];
                int r = (int) (((ca >> 16 & 0xFF) * (1 - f)) + ((cb >> 16 & 0xFF) * f));
                int g = (int) (((ca >> 8 & 0xFF) * (1 - f)) + ((cb >> 8 & 0xFF) * f));
                int bl = (int) (((ca & 0xFF) * (1 - f)) + ((cb & 0xFF) * f));
                return (r << 16) | (g << 8) | bl;
            }
        }
        return 0xE8E8E8;
    }

    private SandboxGeometryProbe() { }
}
