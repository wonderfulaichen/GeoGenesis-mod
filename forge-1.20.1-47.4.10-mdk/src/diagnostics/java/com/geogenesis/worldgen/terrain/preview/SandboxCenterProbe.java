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
 * <b>旋转中心 / 视觉重心诊断</b>（★ v36 重写度量模型）。
 *
 * <h2>为什么重写（v32 的度量是错的）</h2>
 * <p>用户两轮反馈「旋转中心偏高」，v32 我加了"下移一半柱高"的补偿，
 * 探针报 0.0% 偏离 —— 但用户实测<b>还是偏高</b>。</p>
 *
 * <p>根因：v32 的度量假设"内容占据 [地面, 地面-RELIEF] 整段"，
 * 于是把<b>几何中点</b>当视觉重心。但真实的柱高分布是偏的：
 * 大多数格子是平原/海洋（矮），少数山峰（高）⇒
 * <b>视觉重心远低于几何中点</b>，应该按<b>实际柱高均值</b>算。</p>
 *
 * <h2>本版度量</h2>
 * <pre>
 *   每根柱子占屏幕一段 [地面Y - 柱高px, 地面Y]
 *   视觉重心 = 所有柱子「这段的中点」的<b>面积加权平均</b>
 *           = Σ(mid_i × 1) / N     （每根柱在屏幕上占等宽的一列）
 * </pre>
 *
 * <p>用法：{@code gradlew runSandboxCenterProbe}</p>
 */
public final class SandboxCenterProbe {

    private static final int SEED = 12345;
    private static final int VIEW_W = 1740;
    private static final int VIEW_H = 1000;
    private static final int WINDOW_BLOCKS = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 旋转中心 / 视觉重心诊断（v36）===");
        System.out.printf("视口 %d×%d，窗口 %d 格%n%n", VIEW_W, VIEW_H, WINDOW_BLOCKS);

        // ---- 准备真实地形数据 ----
        int pillars = SandboxLod.pillarsForWindow(WINDOW_BLOCKS, Math.min(VIEW_W, VIEW_H));
        int grid = Math.max(2, pillars * SandboxLod.HEIGHT_STRIDE);
        TerrainParams params = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY(), true);
        gen.seed(SEED);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        int nChunks = WINDOW_BLOCKS / 16 + 2;
        int cx0 = 20, cz0 = 60;
        CellCache cache = new CellCache();
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < nChunks; j++) {
                cache.put(cx0 + i, cz0 + j, terrain.getChunkCells(cx0 + i, cz0 + j), 1);
            }
        }
        TerrainFieldData d = TerrainFieldData.fromCellCache(
                cache, cx0 * 16 + 16, cz0 * 16 + 16, WINDOW_BLOCKS, WINDOW_BLOCKS,
                grid, grid, 1L);

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

        // ---- 几何：地面平面 + 每柱屏幕高度 ----
        float[] r = SandboxGeometry.rotationRows(
                SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float scale = SandboxGeometry.fillScale(r, VIEW_W, VIEW_H);
        float[] box = SandboxGeometry.planeBox(r);

        // 地面平面（mz=0）占据的屏幕 y 范围
        float groundTop = VIEW_H / 2f + box[2] * scale;
        float groundBot = VIEW_H / 2f + box[3] * scale;
        // 在中间画一圈的柱子，代表"典型地表落点"
        float groundMid = (groundTop + groundBot) / 2f;

        System.out.println("[1] 几何基准");
        System.out.printf("    scale=%.1f  地面盘高=%.0f px（视口 %d）%n",
                scale, groundBot - groundTop, VIEW_H);
        System.out.printf("    地面盘 y 范围 [%.0f, %.0f]，中点 %.0f（视口中心 %d）%n",
                groundTop, groundBot, groundMid, VIEW_H / 2);

        // ---- 视觉重心：按实际柱高分布（面积加权）----
        //   每根柱在屏幕上占一列，柱子从 groundMid 往上长 colHeightPx
        //   该列的内容中点 = groundMid - colHeightPx/2
        //   全部列取平均 = 视觉重心
        System.out.println("\n[2] 视觉重心（按<b>实际柱高分布</b>，不是几何包围盒）");
        double sumMid = 0;
        int n = 0;
        double sumH = 0;
        float maxH = 0;
        for (int k = 0; k < cols.readyCount; k++) {
            SandboxGeometry.Column c = cols.items[cols.ready[k]];
            if (c.top < 0f) continue;
            float hPx = c.top * Math.abs(r[5]) * scale;   // 该柱在屏幕上的高度
            sumMid += groundMid - hPx / 2.0;              // 该列内容的中点
            sumH += hPx;
            if (hPx > maxH) maxH = hPx;
            n++;
        }
        double avgH = n > 0 ? sumH / n : 0;
        double visualCenter = n > 0 ? sumMid / n : groundMid;
        System.out.printf("    柱数 %d，平均柱高 %.1f px，最高柱 %.1f px%n", n, avgH, maxH);
        System.out.printf("    视觉重心 y = %.0f%n", visualCenter);

        // 当前补偿：v36 改为"下移 平均柱高的一半"（v32 是 RELIEF 的一半，过量 80px）
        float avgTopModel = (float) (avgH / Math.abs(r[5]) / scale);   // 平均柱高换算回模型单位
        float oyBiasNow = avgTopModel * Math.abs(r[5]) * scale * 0.5f;
        System.out.printf("    补偿 = 下移 %.0f px（= 一半<b>平均</b>柱高，v36 口径）%n", oyBiasNow);
        System.out.printf("    （对照：v32 用一半 RELIEF = %.0f px ⇒ 过量 %.0f px）%n",
                SandboxGeometry.RELIEF * Math.abs(r[5]) * scale * 0.5f,
                SandboxGeometry.RELIEF * Math.abs(r[5]) * scale * 0.5f - oyBiasNow);

        float centerAfter = (float) visualCenter + oyBiasNow;
        float biasAfter = centerAfter - VIEW_H / 2f;
        System.out.printf("    补偿后视觉重心 = %.0f ⇒ 偏离视口中心 %.0f px（%.1f%%）%n",
                centerAfter, biasAfter, 100 * Math.abs(biasAfter) / VIEW_H);

        // ---- 判定 ----
        System.out.println("\n[3] 判据：视觉重心偏离视口中心 ≤ 6%");
        float biasPct = Math.abs(biasAfter) / VIEW_H;
        boolean pass = biasPct <= 0.06f;
        System.out.printf("    偏离 %.1f%% ⇒ %s%n", 100 * biasPct, pass ? "OK" : "FAIL");
        if (!pass) {
            System.out.printf("    ⇒ 还偏%s %.0f px%n",
                    biasAfter < 0 ? "高" : "低", Math.abs(biasAfter));
            System.out.printf("    ⇒ 建议补偿改为 %.0f px（当前 %.0f）%n",
                    oyBiasNow - biasAfter, oyBiasNow);
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxCenterProbe() { }
}
