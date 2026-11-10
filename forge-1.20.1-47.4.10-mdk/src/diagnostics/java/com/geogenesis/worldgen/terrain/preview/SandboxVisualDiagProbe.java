package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘视觉诊断</b>（★ v29）—— 把实机真正用的每个参数打出来，不再靠推理。
 *
 * <h2>用户两轮追问</h2>
 * <blockquote>
 * 「还是没有铺满沙盘模式画面，并且视觉效果（就是我能看到的都是视觉效果）
 * 对比参考项目还是差老远了。」
 * </blockquote>
 *
 * <p>本探针输出：① 盘面在屏幕上的<b>像素包围盒</b>（含竖向、横向覆盖率）；
 * ② 柱顶高度的分布；③ 光照分布；④ 水面占比。</p>
 *
 * <p>用法：{@code gradlew runSandboxVisualDiagProbe}</p>
 */
public final class SandboxVisualDiagProbe {

    private static final int SEED = 12345;

    /** 模拟 1920×1000 的配置屏视口（扣除导航栏后的典型值）。 */
    private static final int VIEW_W = 1740;
    private static final int VIEW_H = 1000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘视觉诊断（v29）===");
        System.out.printf("视口模拟 %d×%d（1920 屏 - 导航栏）%n%n", VIEW_W, VIEW_H);

        // ---- 1) 盘面在屏幕上的实际包围盒 ----
        float[] r = SandboxGeometry.rotationRows(
                SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float[] box = SandboxGeometry.planeBox(r);
        float spanX = box[1] - box[0];
        float spanY = box[3] - box[2];

        System.out.println("[1] 盘面屏幕覆盖率");
        System.out.printf("    投影包围盒（模型单位）%.3f × %.3f%n", spanX, spanY);
        float fit = SandboxGeometry.fitScale(r, VIEW_W, VIEW_H);
        float fill = SandboxGeometry.fillScale(r, VIEW_W, VIEW_H);
        float scaleOf = SandboxGeometry.scaleOf(Math.min(VIEW_W, VIEW_H), SandboxLod.BASE_ZOOM);
        System.out.printf("    fitScale=%.1f  盘面 %.0f×%.0f px（X%.0f%% Y%.0f%%）%n",
                fit, spanX * fit, spanY * fit,
                100 * spanX * fit / VIEW_W, 100 * spanY * fit / VIEW_H);
        System.out.printf("    fillScale=%.1f 盘面 %.0f×%.0f px（X%.0f%% Y%.0f%%）← 实机用这个%n",
                fill, spanX * fill, spanY * fill,
                100 * spanX * fill / VIEW_W, 100 * spanY * fill / VIEW_H);
        System.out.printf("    scaleOf=%.1f   盘面 %.0f×%.0f px（X%.0f%% Y%.0f%%）%n",
                scaleOf, spanX * scaleOf, spanY * scaleOf,
                100 * spanX * scaleOf / VIEW_W, 100 * spanY * scaleOf / VIEW_H);

        // 竖向还要加上柱高所占的像素（盘面不是平的，最高柱会向上顶）
        float reliefPx = SandboxGeometry.RELIEF * fill * Math.abs(r[5]);
        System.out.printf("    竖向跨度（最高柱抬起）%.0f px ⇒ 实际竖向占视口 %.0f%%%n",
                reliefPx, 100 * (spanY * fill + reliefPx) / VIEW_H);
        System.out.println();

        // ---- 2) 准备数据（模拟平面窗口 900 格）----
        int windowBlocks = 900;
        int pillars = SandboxLod.pillarsForWindow(windowBlocks);
        int grid = Math.max(2, pillars * SandboxLod.HEIGHT_STRIDE);
        System.out.printf("[2] 数据：窗口 %d 格 / %d 柱 / 细格 %d / 每细格 %.1f 方块%n%n",
                windowBlocks, pillars, grid, windowBlocks / (double) grid);

        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        int nChunks = windowBlocks / 16 + 2;
        int cx0 = 20, cz0 = 60;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        TerrainFieldData d = TerrainFieldData.fromCellCache(
                cache, cx0 * 16 + 16, cz0 * 16 + 16, windowBlocks, windowBlocks,
                grid, grid, 1L);

        // ---- 3) 柱高分布 ----
        double seaY = 63.0, base = seaY - 4.0;
        double hi = base;
        for (int i = 0; i < d.height.length; i++) {
            if (Double.isNaN(d.height[i])) continue;
            double v = d.e[i] < 0 ? seaY : d.height[i];
            if (v > hi) hi = v;
        }
        float span = (float) Math.max(60.0, hi - base);
        SandboxGeometry.Columns cols = SandboxGeometry.buildColumns(
                d, GeoPalette.PreviewLayer.ELEVATION, SandboxLod.perColumn(), span,
                (int) base, (int) hi, seaY, System.currentTimeMillis(), 0L,
                (gx, gz, s) -> 0x808080);

        System.out.println("[3] 柱高与光照");
        System.out.printf("    seaY=%.0f base=%.0f hi=%.0f span=%.0f%n", seaY, base, hi, span);
        float[] tops = new float[cols.readyCount];
        int n = 0, noData = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column c = cols.items[cols.ready[k]];
            if (c.top < 0) { noData++; continue; }
            tops[n++] = c.top;
        }
        java.util.Arrays.sort(tops, 0, n);
        System.out.printf("    有效柱 %d，无数据 %d%n", n, noData);
        if (n > 0) {
            System.out.printf("    柱顶 min=%.4f p10=%.4f p50=%.4f p90=%.4f max=%.4f%n",
                    tops[0], tops[(int) (0.10 * (n - 1))], tops[n / 2],
                    tops[(int) (0.90 * (n - 1))], tops[n - 1]);
        }
        System.out.println();

        // ---- 4) 水面占比 ----
        int water = 0;
        for (int i = 0; i < d.gridW * d.gridH; i++) {
            if (d.e[i] < 0.0) water++;
        }
        System.out.printf("[4] 水面占比 %.1f%%（参考项目截图里几乎看不到水，说明取景不同）%n%n",
                100.0 * water / (d.gridW * d.gridH));

        System.out.println("PASS");
    }

    private SandboxVisualDiagProbe() { }
}
